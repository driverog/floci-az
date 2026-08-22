package io.floci.az.services.containerinstance;

import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.BASE;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.FULL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.LOCALHOST_PAIR;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.MINIMAL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.RUN_TO_COMPLETION;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.actionUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.groupUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.logsUrl;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The state machines and the pod pattern, against a live Docker daemon. Skipped automatically
 * when no socket is available, following the {@code VmDockerTest} precedent.
 */
@QuarkusTest
@TestProfile(ContainerInstanceProfiles.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Container Instances — real Docker runtime (Docker required)")
class ContainerInstanceDockerTest {

    private static final long POLL_TIMEOUT_MS = 120_000L;
    private static final long POLL_INTERVAL_MS = 1_000L;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ContainerDetector containerDetector;

    /** Pure filesystem check — safe to run before Quarkus is fully ready. */
    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable,
                "Docker socket not available — skipping real container-instance tests");
    }

    @AfterAll
    void cleanup() {
        try {
            slow().post("/_admin/reset");
        } catch (Exception e) {
            System.err.println("reset during cleanup failed: " + e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    /**
     * A request spec with a long socket timeout. Creates, deletes, actions and {@code _admin/reset}
     * are synchronous and pull, start, stop and remove real containers, which routinely exceeds
     * the HTTP client's 30-second default.
     */
    private static RequestSpecification slow() {
        return given().config(RestAssured.config().httpClient(
                HttpClientConfig.httpClientConfig().setParam("http.socket.timeout", 180_000)));
    }

    private static void create(String name, String body, int expectedStatus) {
        String response = slow().contentType("application/json").body(body)
                .when().put(groupUrl(name)).then().statusCode(expectedStatus)
                .extract().asString();
        assertEquals("Succeeded", JsonPath.from(response).getString("properties.provisioningState"),
                "container group " + name + " was not provisioned: " + response);
    }

    private static JsonPath view(String name) {
        return given().when().get(groupUrl(name, "&$expand=instanceView"))
                .then().statusCode(200).extract().jsonPath();
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("timed out waiting for: " + description);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final Pattern PORT_MAPPED =
            Pattern.compile("Container group port (\\d+) published on host port (\\d+)");

    private static String portMappedMessage(String group) {
        List<String> messages = view(group).getList(
                "properties.instanceView.events.findAll { it.name == 'PortMapped' }.message");
        assertTrue(messages != null && !messages.isEmpty(),
                "no PortMapped event on container group " + group);
        return messages.get(0);
    }

    private static int hostPortOf(String group) {
        Matcher matcher = PORT_MAPPED.matcher(portMappedMessage(group));
        assertTrue(matcher.matches(), "unexpected PortMapped message: " + portMappedMessage(group));
        return Integer.parseInt(matcher.group(2));
    }

    /**
     * Where a client in this test's position must connect, which is exactly the contract the
     * design documents: {@code 127.0.0.1:hostPort} when floci-az runs on the host, and
     * {@code ipAddress.ip:groupPort} from the shared Docker network when it runs inside Docker.
     */
    private String[] connectTarget(String group, int groupPort) {
        if (containerDetector.isRunningInContainer()) {
            String ip = view(group).getString("properties.ipAddress.ip");
            return new String[] {ip, String.valueOf(groupPort)};
        }
        return new String[] {"127.0.0.1", String.valueOf(hostPortOf(group))};
    }

    private static String httpGet(String host, int port) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── Restart policies ───────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void restartPolicyAlwaysRestartsAfterCleanExit() {
        // The reset lives here rather than in @BeforeAll: with PER_CLASS lifecycle that hook runs
        // before the Quarkus HTTP port is bound, so only a filesystem check is safe there.
        slow().post("/_admin/reset").then().statusCode(204);
        create("always-group", RUN_TO_COMPLETION.formatted("Always", 0), 201);
        await("restartCount >= 2 under Always", () ->
                view("always-group").getInt(
                        "properties.containers[0].properties.instanceView.restartCount") >= 2);

        JsonPath view = view("always-group");
        String current = view.getString(
                "properties.containers[0].properties.instanceView.currentState.state");
        assertTrue(List.of("Running", "Waiting").contains(current),
                "expected Running or Waiting, got " + current);
        assertEquals("Terminated", view.getString(
                "properties.containers[0].properties.instanceView.previousState.state"));
        assertEquals(0, (int) view.getInt(
                "properties.containers[0].properties.instanceView.previousState.exitCode"));
        assertEquals("Completed", view.getString(
                "properties.containers[0].properties.instanceView.previousState.detailStatus"));
        assertEquals("Running", view.getString("properties.instanceView.state"));
        slow().when().delete(groupUrl("always-group")).then().statusCode(204);
    }

    @Test
    @Order(2)
    void restartPolicyOnFailureDoesNotRestartAfterCleanExit() {
        create("onfailure-zero-group", RUN_TO_COMPLETION.formatted("OnFailure", 0), 201);
        await("container Terminated under OnFailure/0", () -> "Terminated".equals(view(
                "onfailure-zero-group").getString(
                "properties.containers[0].properties.instanceView.currentState.state")));
        sleep(5_000);

        JsonPath view = view("onfailure-zero-group");
        assertEquals(0, (int) view.getInt(
                "properties.containers[0].properties.instanceView.restartCount"));
        assertEquals("Terminated", view.getString(
                "properties.containers[0].properties.instanceView.currentState.state"));
        assertEquals(0, (int) view.getInt(
                "properties.containers[0].properties.instanceView.currentState.exitCode"));
        assertEquals("Completed", view.getString(
                "properties.containers[0].properties.instanceView.currentState.detailStatus"));
        assertNotNull(view.getString(
                "properties.containers[0].properties.instanceView.currentState.startTime"));
        assertNotNull(view.getString(
                "properties.containers[0].properties.instanceView.currentState.finishTime"));
        assertEquals("Succeeded", view.getString("properties.instanceView.state"));
        slow().when().delete(groupUrl("onfailure-zero-group")).then().statusCode(204);
    }

    @Test
    @Order(3)
    void restartPolicyOnFailureRestartsAfterNonZeroExit() {
        create("onfailure-fail-group", RUN_TO_COMPLETION.formatted("OnFailure", 7), 201);
        await("restartCount >= 1 under OnFailure/7", () ->
                view("onfailure-fail-group").getInt(
                        "properties.containers[0].properties.instanceView.restartCount") >= 1);

        JsonPath view = view("onfailure-fail-group");
        assertEquals(7, (int) view.getInt(
                "properties.containers[0].properties.instanceView.previousState.exitCode"));
        assertEquals("Error", view.getString(
                "properties.containers[0].properties.instanceView.previousState.detailStatus"));
        assertEquals("Running", view.getString("properties.instanceView.state"));
        slow().when().delete(groupUrl("onfailure-fail-group")).then().statusCode(204);
    }

    @Test
    @Order(4)
    void restartPolicyNeverDoesNotRestartAfterCleanExit() {
        create("never-zero-group", RUN_TO_COMPLETION.formatted("Never", 0), 201);
        await("container Terminated under Never/0", () -> "Terminated".equals(view(
                "never-zero-group").getString(
                "properties.containers[0].properties.instanceView.currentState.state")));
        sleep(5_000);

        JsonPath view = view("never-zero-group");
        assertEquals(0, (int) view.getInt(
                "properties.containers[0].properties.instanceView.restartCount"));
        assertEquals(0, (int) view.getInt(
                "properties.containers[0].properties.instanceView.currentState.exitCode"));
        assertEquals("Completed", view.getString(
                "properties.containers[0].properties.instanceView.currentState.detailStatus"));
        assertEquals("Succeeded", view.getString("properties.instanceView.state"));
    }

    @Test
    @Order(5)
    void restartPolicyNeverDoesNotRestartAfterNonZeroExit() {
        create("never-fail-group", RUN_TO_COMPLETION.formatted("Never", 9), 201);
        await("container Terminated under Never/9", () -> "Terminated".equals(view(
                "never-fail-group").getString(
                "properties.containers[0].properties.instanceView.currentState.state")));
        sleep(5_000);

        JsonPath view = view("never-fail-group");
        assertEquals(0, (int) view.getInt(
                "properties.containers[0].properties.instanceView.restartCount"));
        assertEquals(9, (int) view.getInt(
                "properties.containers[0].properties.instanceView.currentState.exitCode"));
        assertEquals("Error", view.getString(
                "properties.containers[0].properties.instanceView.currentState.detailStatus"));
        assertEquals("Failed", view.getString("properties.instanceView.state"));
        slow().when().delete(groupUrl("never-fail-group")).then().statusCode(204);
        slow().when().delete(groupUrl("never-zero-group")).then().statusCode(204);
    }

    // ── Failure paths ──────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void imagePullFailureProducesFailedGroupWithEvent() {
        String body = MINIMAL.replace("alpine:3.20", "floci-az-nonexistent/no-such-image:0.0.0");
        slow().contentType("application/json").body(body)
                .when().put(groupUrl("badimage-group"))
                .then().statusCode(201)
                .body("properties.provisioningState", org.hamcrest.Matchers.equalTo("Failed"));

        JsonPath view = view("badimage-group");
        assertEquals("Failed", view.getString("properties.instanceView.state"));
        List<Map<String, Object>> events = view.getList(
                "properties.containers[0].properties.instanceView.events.findAll { it.name == 'Failed' }");
        assertTrue(events != null && !events.isEmpty(), "expected a Failed event on the container");
        Object message = events.get(0).get("message");
        assertEquals("Warning", events.get(0).get("type"));
        assertTrue(String.valueOf(message).startsWith(
                        "Failed to pull image \"floci-az-nonexistent/no-such-image:0.0.0\": "),
                "unexpected Failed event message: " + message);
        slow().when().delete(groupUrl("badimage-group")).then().statusCode(204);
    }

    // ── The pod pattern ────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void sharedNetworkNamespaceLetsContainersReachEachOtherOnLocalhost() {
        create("localhost-group", LOCALHOST_PAIR, 201);
        await("localhost-group Running", () ->
                "Running".equals(view("localhost-group").getString("properties.instanceView.state")));

        await("the client container reached the server on localhost", () -> {
            String content = given()
                    .when().get(logsUrl("localhost-group", "client") + "&tail=20")
                    .then().statusCode(200).extract().jsonPath().getString("content");
            return content != null && content.contains("SERVER <- via localhost");
        });
    }

    @Test
    @Order(8)
    void groupPortIsReachableFromTheHost() {
        String[] target = connectTarget("localhost-group", 9000);
        // The fixture re-execs `nc` after every connection, so a refused connect between two
        // accepts is expected; the assertion is that a response does arrive.
        awaitServerResponse(target);
    }

    private static void awaitServerResponse(String[] target) {
        StringBuilder seen = new StringBuilder();
        await("the group port answers with SERVER at " + target[0] + ":" + target[1], () -> {
            try {
                String response = httpGet(target[0], Integer.parseInt(target[1]));
                seen.setLength(0);
                seen.append(response);
                return response.contains("SERVER");
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(seen.toString().contains("SERVER"), "unexpected response: " + seen);
    }

    @Test
    @Order(9)
    void stopThenStartPreservesReachability() {
        slow().when().post(actionUrl("localhost-group", "stop")).then().statusCode(204);
        await("localhost-group Stopped", () ->
                "Stopped".equals(view("localhost-group").getString("properties.instanceView.state")));

        slow().when().post(actionUrl("localhost-group", "start")).then().statusCode(204);
        await("localhost-group Running again", () ->
                "Running".equals(view("localhost-group").getString("properties.instanceView.state")));

        awaitServerResponse(connectTarget("localhost-group", 9000));
        slow().when().delete(groupUrl("localhost-group")).then().statusCode(204);
    }

    @Test
    @Order(10)
    void infraContainerDeathTriggersRepair() {
        create("repair-group", FULL, 201);
        await("repair-group Running", () ->
                "Running".equals(view("repair-group").getString("properties.instanceView.state")));

        String groupId = view("repair-group").getString("properties.instanceView.state") == null
                ? null : dockerGroupId("repair-group");
        assertNotNull(groupId, "could not read the group id from the container labels");
        String infraName = "floci-az-aci-" + groupId + "-infra";
        String infraId = lifecycleManager.findByName(infraName)
                .orElseThrow(() -> new AssertionError("no infra container named " + infraName))
                .getId();

        lifecycleManager.getDockerClient().killContainerCmd(infraId).exec();

        await("the namespace was recreated", () -> {
            JsonPath view = view("repair-group");
            List<String> names = view.getList("properties.instanceView.events.name");
            return names != null && names.contains("InfraRestarted")
                    && "Running".equals(view.getString("properties.instanceView.state"));
        });
        JsonPath view = view("repair-group");
        assertTrue(view.getInt("properties.containers[0].properties.instanceView.restartCount") >= 1);
        assertTrue(view.getInt("properties.containers[1].properties.instanceView.restartCount") >= 1);
        slow().when().delete(groupUrl("repair-group")).then().statusCode(204);
    }

    /** Reads {@code floci_aci_group_id} from any container labelled with the group name. */
    private String dockerGroupId(String group) {
        return lifecycleManager.getDockerClient().listContainersCmd().withShowAll(true).exec()
                .stream()
                .filter(container -> container.getLabels() != null
                        && group.equals(container.getLabels().get("floci_aci_group")))
                .map(container -> container.getLabels().get("floci_aci_group_id"))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    // ── Volumes and environment ────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void emptyDirVolumeIsSharedBetweenContainers() {
        String body = """
                {
                  "location": "eastus",
                  "properties": {
                    "osType": "Linux",
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "writer", "properties": {
                        "image": "alpine:3.20",
                        "command": ["sh", "-c", "echo shared-payload > /mnt/scratch/f; sleep 300"],
                        "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}},
                        "volumeMounts": [{"name": "scratch-volume", "mountPath": "/mnt/scratch"}]}},
                      {"name": "reader", "properties": {
                        "image": "alpine:3.20",
                        "command": ["sh", "-c", "sleep 5; cat /mnt/scratch/f; sleep 300"],
                        "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}},
                        "volumeMounts": [{"name": "scratch-volume", "mountPath": "/mnt/scratch"}]}}
                    ],
                    "volumes": [{"name": "scratch-volume", "emptyDir": {}}]
                  }
                }
                """;
        create("emptydir-group", body, 201);
        await("the reader saw the writer's file", () -> logsOf("emptydir-group", "reader")
                .contains("shared-payload"));
        slow().when().delete(groupUrl("emptydir-group")).then().statusCode(204);
    }

    @Test
    @Order(12)
    void secretVolumeIsReadableAndReadOnly() {
        String body = """
                {
                  "location": "eastus",
                  "properties": {
                    "osType": "Linux",
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "reader", "properties": {
                        "image": "alpine:3.20",
                        "command": ["sh", "-c",
                          "cat /mnt/secrets/mysecret1; touch /mnt/secrets/w 2>&1 || echo READONLY; sleep 300"],
                        "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}},
                        "volumeMounts": [{"name": "secret-volume", "mountPath": "/mnt/secrets"}]}}
                    ],
                    "volumes": [
                      {"name": "secret-volume", "secret": {"mysecret1": "aGVsbG8tc2VjcmV0Cg=="}}
                    ]
                  }
                }
                """;
        create("secretvol-group", body, 201);
        await("the secret file was readable and the mount read-only", () -> {
            String content = logsOf("secretvol-group", "reader");
            return content.contains("hello-secret") && content.contains("READONLY");
        });
        slow().when().delete(groupUrl("secretvol-group")).then().statusCode(204);
    }

    @Test
    @Order(13)
    void secureEnvironmentVariableReachesTheContainer() {
        String body = """
                {
                  "location": "eastus",
                  "properties": {
                    "osType": "Linux",
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "echoer", "properties": {
                        "image": "alpine:3.20",
                        "command": ["sh", "-c", "echo token-is-$API_TOKEN; sleep 300"],
                        "environmentVariables": [
                          {"name": "API_TOKEN", "secureValue": "s3cr3t-token"}
                        ],
                        "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}}}
                    ]
                  }
                }
                """;
        create("secureenv-group", body, 201);
        await("the secure value reached the container", () ->
                logsOf("secureenv-group", "echoer").contains("token-is-s3cr3t-token"));
        slow().when().delete(groupUrl("secureenv-group")).then().statusCode(204);
    }

    // ── Logs ───────────────────────────────────────────────────────────────────────────────

    private static String logsOf(String group, String container) {
        String content = given().when().get(logsUrl(group, container))
                .then().statusCode(200).extract().jsonPath().getString("content");
        return content == null ? "" : content;
    }

    @Test
    @Order(14)
    void logsReturnRealOutputWithTailAndTimestamps() {
        String body = """
                {
                  "location": "eastus",
                  "properties": {
                    "osType": "Linux",
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "noisy", "properties": {
                        "image": "alpine:3.20",
                        "command": ["sh", "-c",
                          "for i in 1 2 3 4 5; do echo out-$i; echo err-$i 1>&2; sleep 0.2; done; sleep 300"],
                        "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}}}
                    ]
                  }
                }
                """;
        create("logs-group", body, 201);
        await("all ten lines are present", () -> {
            String content = logsOf("logs-group", "noisy");
            for (int i = 1; i <= 5; i++) {
                if (!content.contains("out-" + i) || !content.contains("err-" + i)) {
                    return false;
                }
            }
            return true;
        });

        String tailed = given().when().get(logsUrl("logs-group", "noisy") + "&tail=3")
                .then().statusCode(200).extract().jsonPath().getString("content");
        assertEquals(3, tailed.lines().count(), "tail=3 must return 3 lines: " + tailed);

        String stamped = given()
                .when().get(logsUrl("logs-group", "noisy") + "&tail=2&timestamps=true")
                .then().statusCode(200).extract().jsonPath().getString("content");
        stamped.lines().forEach(line -> assertTrue(
                line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z .*$"),
                "line is not timestamped: " + line));
        slow().when().delete(groupUrl("logs-group")).then().statusCode(204);
    }

    @Test
    @Order(15)
    void logsOfATerminatedContainerAreStillAvailable() {
        create("terminated-logs-group", RUN_TO_COMPLETION.formatted("Never", 0), 201);
        await("the task terminated", () -> "Terminated".equals(view("terminated-logs-group")
                .getString("properties.containers[0].properties.instanceView.currentState.state")));
        assertTrue(logsOf("terminated-logs-group", "task").contains("task-output"));
        slow().when().delete(groupUrl("terminated-logs-group")).then().statusCode(204);
    }

    // ── Allocation and identity stability ──────────────────────────────────────────────────

    private static String withGroupPort(int port) {
        return """
                {
                  "location": "eastus",
                  "properties": {
                    "osType": "Linux",
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "web", "properties": {
                        "image": "alpine:3.20",
                        "command": ["sh", "-c", "sleep 300"],
                        "ports": [{"port": %d, "protocol": "TCP"}],
                        "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}}}
                    ],
                    "ipAddress": {"type": "Public", "ports": [{"port": %d, "protocol": "TCP"}]}
                  }
                }
                """.formatted(port, port);
    }

    @Test
    @Order(16)
    void hostPortIsReusedWhenTheAzurePortIsFree() {
        create("port-reuse-group", withGroupPort(18099), 201);
        assertEquals("Container group port 18099 published on host port 18099",
                portMappedMessage("port-reuse-group"));
        slow().when().delete(groupUrl("port-reuse-group")).then().statusCode(204);
    }

    @Test
    @Order(17)
    void hostPortFallsBackToTheConfiguredRangeForPrivilegedPorts() {
        create("port-fallback-group", withGroupPort(80), 201);
        String message = portMappedMessage("port-fallback-group");
        assertTrue(message.matches("^Container group port 80 published on host port 85\\d\\d$"),
                "expected a port from the 8500-8599 range, got: " + message);
        slow().when().delete(groupUrl("port-fallback-group")).then().statusCode(204);
    }

    @Test
    @Order(18)
    void hostPortIsReleasedOnDelete() {
        create("port-release-a", withGroupPort(80), 201);
        int first = hostPortOf("port-release-a");
        slow().when().delete(groupUrl("port-release-a")).then().statusCode(204);

        create("port-release-b", withGroupPort(80), 201);
        assertEquals(first, hostPortOf("port-release-b"),
                "the allocator must hand the released port back");
        slow().when().delete(groupUrl("port-release-b")).then().statusCode(204);
    }

    @Test
    @Order(19)
    void groupIdIsStableAcrossUpdatesAndRestarts() {
        create("stable-group", MINIMAL, 201);
        await("stable-group Running", () ->
                "Running".equals(view("stable-group").getString("properties.instanceView.state")));
        String groupId = dockerGroupId("stable-group");
        assertNotNull(groupId);
        String infraName = "floci-az-aci-" + groupId + "-infra";
        assertTrue(lifecycleManager.findByName(infraName).isPresent(), infraName + " must exist");

        create("stable-group", FULL, 200);
        assertEquals(groupId, dockerGroupId("stable-group"), "groupId must survive a PUT");
        assertTrue(lifecycleManager.findByName(infraName).isPresent(),
                infraName + " must still exist after a PUT");

        slow().when().post(actionUrl("stable-group", "restart")).then().statusCode(204);
        assertEquals(groupId, dockerGroupId("stable-group"), "groupId must survive a restart");
        assertTrue(lifecycleManager.findByName(infraName).isPresent(),
                infraName + " must still exist after a restart");
        slow().when().delete(groupUrl("stable-group")).then().statusCode(204);
    }

    // ── Teardown ───────────────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void deleteRemovesEveryDockerResource() {
        create("cleanup-group", FULL, 201);
        await("cleanup-group Running", () ->
                "Running".equals(view("cleanup-group").getString("properties.instanceView.state")));
        String groupId = dockerGroupId("cleanup-group");
        assertNotNull(groupId);

        slow().when().delete(groupUrl("cleanup-group")).then().statusCode(204);

        await("every Docker resource was removed", () ->
                lifecycleManager.findByName("floci-az-aci-" + groupId + "-infra").isEmpty()
                        && lifecycleManager.findByName("floci-az-aci-" + groupId + "-web").isEmpty());
    }

    @Test
    @Order(21)
    void resetRemovesEveryDockerResource() {
        create("reset-group", FULL, 201);
        await("reset-group Running", () ->
                "Running".equals(view("reset-group").getString("properties.instanceView.state")));
        String groupId = dockerGroupId("reset-group");
        assertNotNull(groupId);

        slow().post("/_admin/reset").then().statusCode(204);

        await("reset removed every Docker resource", () ->
                lifecycleManager.findByName("floci-az-aci-" + groupId + "-infra").isEmpty()
                        && lifecycleManager.findByName("floci-az-aci-" + groupId + "-web").isEmpty());
    }
}

/**
 * The bounded-log regression test, which needs a much smaller byte cap than the default 4 MB.
 * A Quarkus test profile is per class, so this case has its own.
 */
@QuarkusTest
@TestProfile(ContainerInstanceDockerLogCapTest.SmallLogCapProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Container Instances — bounded log retrieval (Docker required)")
class ContainerInstanceDockerLogCapTest {

    public static class SmallLogCapProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.container-instance.mocked", "false",
                    "floci-az.services.container-instance.reconcile-interval-seconds", "1",
                    "floci-az.services.container-instance.log-max-bytes", "1024");
        }
    }

    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping bounded-log test");
    }

    @Test
    void logsAreBoundedForALoudContainer() {
        given().post("/_admin/reset").then().statusCode(204);
        String body = """
                {
                  "location": "eastus",
                  "properties": {
                    "osType": "Linux",
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "loud", "properties": {
                        "image": "alpine:3.20",
                        "command": ["sh", "-c", "yes floci-az-log-line | head -c 5000000; sleep 300"],
                        "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}}}
                    ]
                  }
                }
                """;
        given().contentType("application/json").body(body)
                .when().put(groupUrl("loud-group")).then().statusCode(201);

        long started = System.currentTimeMillis();
        String content = given().when().get(logsUrl("loud-group", "loud"))
                .then().statusCode(200).extract().jsonPath().getString("content");
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(content.length() <= 1024,
                "the log read must respect the 1024-byte cap, was " + content.length());
        assertTrue(elapsed < 30_000, "the capped read must complete quickly, took " + elapsed + "ms");
        given().when().delete(groupUrl("loud-group")).then().statusCode(204);
    }
}
