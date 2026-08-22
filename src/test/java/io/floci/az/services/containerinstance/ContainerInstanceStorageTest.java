package io.floci.az.services.containerinstance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.MINIMAL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.groupUrl;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage wiring for the {@code containerinstance} backend: the persistent backend writes
 * {@code containerinstance.json} keyed by {@code subscription/resourceGroup/name}.
 *
 * <p>The four storage-mode cases need three mutually exclusive global configurations, and a
 * Quarkus test profile is per test class, so the sibling classes in this file carry the other
 * two. Nested classes cannot hold their own {@code @TestProfile}.</p>
 */
@QuarkusTest
@TestProfile(ContainerInstanceStorageTest.PersistentProfile.class)
@DisplayName("Container Instances — persistent storage mode")
class ContainerInstanceStorageTest {

    static final Path PERSISTENT_DIR = Path.of("target", "aci-storage-persistent");

    public static class PersistentProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.storage.mode", "persistent",
                    "floci-az.storage.persistent-path", PERSISTENT_DIR.toString(),
                    "floci-az.services.container-instance.mocked", "true");
        }
    }

    @Test
    void groupSurvivesAcrossBackendReloadInPersistentMode() throws IOException {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("persist-group")).then().statusCode(201);

        Path file = PERSISTENT_DIR.resolve("containerinstance.json");
        assertTrue(Files.exists(file), "persistent mode must write " + file);
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(contents.contains("test-sub-aci/test-rg-aci/persist-group"),
                "the stored file must be keyed by subscription/resourceGroup/name, was: " + contents);
    }
}

/**
 * The per-service storage override: the global mode is {@code memory} but
 * {@code floci-az.storage.services.container-instance.mode} is {@code persistent}.
 */
@QuarkusTest
@TestProfile(ContainerInstanceStorageOverrideTest.OverrideProfile.class)
@DisplayName("Container Instances — per-service storage override")
class ContainerInstanceStorageOverrideTest {

    static final Path OVERRIDE_DIR = Path.of("target", "aci-storage-override");

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.storage.mode", "memory",
                    "floci-az.storage.services.container-instance.mode", "persistent",
                    "floci-az.storage.persistent-path", OVERRIDE_DIR.toString(),
                    "floci-az.services.container-instance.mocked", "true");
        }
    }

    @Test
    void storageModeOverrideIsHonoured() {
        // The persistent file survives previous runs of this suite; reset so the PUT is a create.
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("override-group")).then().statusCode(201);

        // Proves StorageFactory.serviceConfig gained its `containerinstance` case: without it the
        // override would be invisible and the global `memory` mode would apply.
        assertTrue(Files.exists(OVERRIDE_DIR.resolve("containerinstance.json")),
                "the per-service persistent override must write containerinstance.json");
    }
}

/**
 * Memory mode writes nothing to disk, and the startup banner reports the service — the two
 * assertions that prove the {@code BannerLogger} and default-mode wiring landed.
 */
@QuarkusTest
@TestProfile(ContainerInstanceStorageMemoryTest.MemoryProfile.class)
@DisplayName("Container Instances — memory storage mode and banner")
class ContainerInstanceStorageMemoryTest {

    static final Path MEMORY_DIR = Path.of("target", "aci-storage-memory");
    static final Path BANNER_LOG = Path.of("target", "aci-banner.log");

    public static class MemoryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.storage.mode", "memory",
                    "floci-az.storage.persistent-path", MEMORY_DIR.toString(),
                    "floci-az.services.container-instance.mocked", "true",
                    "quarkus.log.file.enable", "true",
                    "quarkus.log.file.append", "false",
                    "quarkus.log.file.path", BANNER_LOG.toString());
        }
    }

    @Test
    void memoryModeWritesNothingToDisk() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("memory-group")).then().statusCode(201);

        assertFalse(Files.exists(MEMORY_DIR.resolve("containerinstance.json")),
                "memory mode must not write containerinstance.json");
    }

    @Test
    void bannerReportsTheService() throws IOException {
        assertTrue(Files.exists(BANNER_LOG), "the startup log file must exist at " + BANNER_LOG);
        boolean found = Files.readAllLines(BANNER_LOG, StandardCharsets.UTF_8).stream()
                .anyMatch(line -> line.matches(".*\\s+aci\\s+\\[enabled \\]\\s+docker: .*"));
        assertTrue(found, "the startup banner must carry an `aci` line; log was:\n"
                + Files.readString(BANNER_LOG, StandardCharsets.UTF_8));
    }
}
