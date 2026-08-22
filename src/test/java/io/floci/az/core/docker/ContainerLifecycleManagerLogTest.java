package io.floci.az.core.docker;

import com.github.dockerjava.api.exception.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two new core Docker capabilities, asserted against a live daemon: bounded log retrieval
 * and container state inspection.
 *
 * <p>Skipped automatically when Docker is unavailable, following the {@code VmDockerTest}
 * precedent.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ContainerLifecycleManager — fetchLogs and inspectState (Docker required)")
class ContainerLifecycleManagerLogTest {

    private static final String IMAGE = "alpine:3.20";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ImageCacheService imageCacheService;

    @Inject
    ContainerBuilder containerBuilder;

    private final List<String> created = new ArrayList<>();

    /** Pure filesystem check — safe to run before Quarkus is fully ready. */
    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping real container-log tests");
        imageCacheService.ensureImageExists(IMAGE);
    }

    @AfterAll
    void cleanup() {
        for (String id : created) {
            try {
                lifecycleManager.stopAndRemove(id, null);
            } catch (Exception e) {
                // Best effort: a container the test already removed is fine to miss here.
                System.err.println("cleanup of " + id + " failed: " + e.getMessage());
            }
        }
    }

    /** Starts a container running {@code sh -c script} and waits for it to exit. */
    private String runToCompletion(String name, String script) throws InterruptedException {
        String id = start(name, script);
        for (int i = 0; i < 120 && lifecycleManager.inspectState(id).running(); i++) {
            Thread.sleep(250);
        }
        return id;
    }

    private String start(String name, String script) {
        String containerName = "floci-az-acilogtest-" + name;
        lifecycleManager.removeIfExists(containerName);
        ContainerSpec spec = containerBuilder.newContainer(IMAGE)
                .withName(containerName)
                .withCmd(List.of("sh", "-c", script))
                .build();
        String id = lifecycleManager.createAndStart(spec).containerId();
        created.add(id);
        return id;
    }

    private static final String FIVE_LINE_PAIRS =
            "for i in 1 2 3 4 5; do echo \"out-$i\"; echo \"err-$i\" 1>&2; sleep 0.2; done";

    @Test
    void fetchLogsReturnsStdoutAndStderrInterleaved() throws Exception {
        String id = runToCompletion("interleaved", FIVE_LINE_PAIRS);
        String content = lifecycleManager.fetchLogs(id, null, false, 1_000_000L, 10_000, TIMEOUT)
                .content();
        for (int i = 1; i <= 5; i++) {
            assertTrue(content.contains("out-" + i), "missing out-" + i + " in: " + content);
            assertTrue(content.contains("err-" + i), "missing err-" + i + " in: " + content);
        }
    }

    @Test
    void fetchLogsHonoursTail() throws Exception {
        String id = runToCompletion("tail", FIVE_LINE_PAIRS);
        String content = lifecycleManager.fetchLogs(id, 3, false, 1_000_000L, 10_000, TIMEOUT)
                .content();
        assertEquals(3, content.lines().count(), "withTail(3) must return exactly 3 lines: " + content);
    }

    @Test
    void fetchLogsHonoursTimestamps() throws Exception {
        String id = runToCompletion("timestamps", FIVE_LINE_PAIRS);
        String content = lifecycleManager.fetchLogs(id, 3, true, 1_000_000L, 10_000, TIMEOUT)
                .content();
        content.lines().forEach(line -> assertTrue(
                line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z .*"),
                "line is not RFC 3339 timestamped: " + line));
    }

    @Test
    void fetchLogsStopsAtTheByteCap() {
        long maxBytes = 4096L;
        String id = start("loud", "yes floci-az-log-line | head -c 5000000; sleep 300");
        long started = System.nanoTime();
        ContainerLifecycleManager.LogResult result =
                lifecycleManager.fetchLogs(id, null, false, maxBytes, 1_000_000, TIMEOUT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(result.content().length() <= maxBytes,
                "content must not exceed the byte cap, was " + result.content().length());
        assertTrue(result.truncated(), "a 5 MB log read under a 4 KB cap must report truncation");
        assertTrue(elapsedMs < TIMEOUT.toMillis(),
                "the capped read must terminate the stream, not wait for the timeout");
    }

    /**
     * Azure's caps describe the most recent output. Accumulating from the start of the stream and
     * stopping at the cap returned a container's oldest output and none of what it had just
     * written — on a busy container, startup noise instead of the crash being investigated.
     */
    @Test
    void fetchLogsReturnsTheMostRecentOutput() throws Exception {
        String id = runToCompletion("recent", "for i in $(seq 1 400); do echo \"line-$i\"; done");
        String content = lifecycleManager.fetchLogs(id, null, false, 200L, 10_000, TIMEOUT)
                .content();

        assertTrue(content.contains("line-400"), "the newest line must survive: " + content);
        assertFalse(content.contains("line-1\n"), "the oldest lines are the ones to drop: " + content);
    }

    /** The line cap counts from the end too, and dropping lines to reach it is a truncation. */
    @Test
    void fetchLogsLineCapKeepsTheNewestLines() throws Exception {
        String id = runToCompletion("linecap", "for i in $(seq 1 50); do echo \"line-$i\"; done");
        ContainerLifecycleManager.LogResult result =
                lifecycleManager.fetchLogs(id, null, false, 1_000_000L, 5, TIMEOUT);

        assertEquals(5, result.content().lines().count(), "expected 5 lines: " + result.content());
        assertTrue(result.content().contains("line-50"), "the newest line must survive");
        assertTrue(result.truncated(), "holding back 45 lines is a truncation");
    }

    /**
     * The cap is a byte cap, and the config names it one. Measuring Java chars let three-byte
     * UTF-8 output return roughly three times the configured bound.
     */
    @Test
    void fetchLogsByteCapCountsUtf8BytesNotChars() throws Exception {
        long maxBytes = 512L;
        String id = runToCompletion("multibyte",
                "for i in $(seq 1 200); do echo \"日本語テキスト-$i\"; done");
        String content = lifecycleManager.fetchLogs(id, null, false, maxBytes, 10_000, TIMEOUT)
                .content();

        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= maxBytes, "content was " + bytes + " bytes against a " + maxBytes
                + "-byte cap: " + content);
        assertFalse(content.isBlank(), "the cap must still return the most recent output");
    }

    /**
     * The daemon delivers one log entry as several frames, split at a fixed buffer size that no
     * multi-byte character is obliged to respect. Decoding each frame on its own turned the
     * character straddling the boundary into a replacement character on both sides.
     */
    @Test
    void fetchLogsDoesNotCorruptCharactersSplitAcrossFrames() throws Exception {
        String id = runToCompletion("frames",
                "s=''; i=0; while [ $i -lt 600 ]; do s=\"$s日本語\"; i=$((i+1)); done; echo \"$s\"");
        String content = lifecycleManager.fetchLogs(id, null, false, 1_000_000L, 10_000, TIMEOUT)
                .content();

        assertFalse(content.contains("�"),
                "a character split across a frame boundary decoded to U+FFFD");
        assertEquals(1800, content.strip().length(),
                "every character of the 1800-character line must survive the frame boundaries");
    }

    @Test
    void fetchLogsWorksOnAStoppedContainer() throws Exception {
        String id = runToCompletion("stopped", "echo stopped-output; exit 0");
        assertFalse(lifecycleManager.inspectState(id).running());
        assertTrue(lifecycleManager.fetchLogs(id, null, false, 1_000_000L, 10_000, TIMEOUT)
                .content().contains("stopped-output"));
    }

    @Test
    void fetchLogsThrowsNotFoundForARemovedContainer() {
        assertThrows(NotFoundException.class, () -> lifecycleManager.fetchLogs(
                "floci-az-acilogtest-no-such-container-xyz", null, false,
                1_000_000L, 10_000, TIMEOUT));
    }

    @Test
    void inspectStateReportsExitCodeAndTimes() throws Exception {
        String id = runToCompletion("exitcode", "echo bye; exit 3");
        ContainerLifecycleManager.ContainerRuntimeState state = lifecycleManager.inspectState(id);
        assertTrue(state.exists());
        assertFalse(state.running());
        assertEquals(3, state.exitCode());
        assertNotNull(state.startedAt());
        assertNotNull(state.finishedAt());
    }

    @Test
    void inspectStateReturnsAbsentForAMissingContainer() {
        assertEquals(ContainerLifecycleManager.ContainerRuntimeState.ABSENT,
                lifecycleManager.inspectState("floci-az-acilogtest-no-such-container-xyz"));
    }
}
