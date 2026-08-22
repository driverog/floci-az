package io.floci.az.services.containerinstance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.FULL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.groupUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.rgCollectionUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.subCollectionUrl;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof that no secret ever leaves the process. Every write-only value in the wire contract —
 * {@code secureValue}, {@code secret} volume contents, a registry {@code password},
 * {@code workspaceKey} / {@code workspaceResourceId}, and {@code storageAccountKey} — is
 * asserted absent from every response body.
 */
@QuarkusTest
@TestProfile(ContainerInstanceProfiles.MockedProfile.class)
@DisplayName("Container Instances — secret safety")
class ContainerInstanceSecretSafetyTest {

    static final String SECURE_ENV = "s3cr3t-token";
    static final String SECRET_VOLUME_VALUE = "aGVsbG8tc2VjcmV0Cg==";
    static final String SECRET_VOLUME_PLAINTEXT = "hello-secret";
    static final String REGISTRY_PASSWORD = "p4ssw0rd";
    static final String WORKSPACE_KEY = "w-key-secret";
    static final String STORAGE_ACCOUNT_KEY = "sa-key-secret";

    /** {@code FULL} plus a registry credential, diagnostics, and an azureFile volume key. */
    static final String FULL_WITH_EVERY_SECRET = FULL
            .replace("\"restartPolicy\": \"Always\",",
                    "\"restartPolicy\": \"Always\","
                            + "\"imageRegistryCredentials\": [{\"server\": \"myregistry.example.com\","
                            + "\"username\": \"u\",\"password\": \"" + REGISTRY_PASSWORD + "\"}],"
                            + "\"diagnostics\": {\"logAnalytics\": {\"workspaceId\": \"w-id\","
                            + "\"workspaceKey\": \"" + WORKSPACE_KEY + "\"}},")
            .replace("{\"name\": \"secret-volume\", \"secret\": {\"mysecret1\": \""
                            + SECRET_VOLUME_VALUE + "\"}}",
                    "{\"name\": \"secret-volume\", \"secret\": {\"mysecret1\": \""
                            + SECRET_VOLUME_VALUE + "\"}},"
                            + "{\"name\": \"share-volume\", \"azureFile\": {"
                            + "\"shareName\": \"floci-aci-share\","
                            + "\"storageAccountName\": \"flociacisa\","
                            + "\"storageAccountKey\": \"" + STORAGE_ACCOUNT_KEY + "\"}}");

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(FULL_WITH_EVERY_SECRET)
                .when().put(groupUrl("secret-group")).then().statusCode(201);
    }

    /** The raw bodies of every response shape that can carry a container group. */
    private static List<String> everyResponseBody() {
        return List.of(
                given().contentType("application/json").body(FULL_WITH_EVERY_SECRET)
                        .when().put(groupUrl("secret-group")).then().statusCode(200)
                        .extract().asString(),
                given().when().get(groupUrl("secret-group")).then().statusCode(200)
                        .extract().asString(),
                given().when().get(groupUrl("secret-group", "&$expand=instanceView"))
                        .then().statusCode(200).extract().asString(),
                given().when().get(rgCollectionUrl()).then().statusCode(200).extract().asString(),
                given().when().get(subCollectionUrl()).then().statusCode(200).extract().asString(),
                given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
                        .when().patch(groupUrl("secret-group")).then().statusCode(200)
                        .extract().asString());
    }

    private static void assertNoneContain(List<String> bodies, String secret) {
        for (String body : bodies) {
            assertFalse(body.contains(secret),
                    "a response body leaked '" + secret + "': " + body);
        }
    }

    @Test
    void secureValueIsNeverReturnedInAnyResponse() {
        List<String> bodies = everyResponseBody();
        assertNoneContain(bodies, SECURE_ENV);
        for (String body : bodies) {
            assertTrue(body.contains("\"name\":\"API_TOKEN\""),
                    "the environment variable name must still be echoed: " + body);
            assertFalse(body.contains("secureValue"),
                    "no response may carry a secureValue key: " + body);
        }
        // Positionally: the API_TOKEN entry is a bare {"name":"API_TOKEN"} object.
        given().when().get(groupUrl("secret-group")).then().statusCode(200)
                .body("properties.containers[0].properties.environmentVariables[1].name",
                        equalTo("API_TOKEN"))
                .body("properties.containers[0].properties.environmentVariables[1].value",
                        org.hamcrest.Matchers.nullValue())
                .body("properties.containers[0].properties.environmentVariables[1].secureValue",
                        org.hamcrest.Matchers.nullValue());
    }

    @Test
    void secretVolumeContentsAreNeverReturned() {
        List<String> bodies = everyResponseBody();
        assertNoneContain(bodies, SECRET_VOLUME_VALUE);
        assertNoneContain(bodies, SECRET_VOLUME_PLAINTEXT);
        for (String body : bodies) {
            assertTrue(body.contains("\"secret\":{}"),
                    "a secret volume must be rendered as an empty object: " + body);
        }
    }

    @Test
    void registryPasswordIsNeverReturned() {
        String body = given().when().get(groupUrl("secret-group"))
                .then().statusCode(200).extract().asString();
        assertTrue(body.contains("myregistry.example.com"), body);
        assertTrue(body.contains("\"username\":\"u\""), body);
        assertFalse(body.contains(REGISTRY_PASSWORD), body);
        assertFalse(body.contains("password"), "no response may carry a password key: " + body);
    }

    @Test
    void workspaceKeyIsNeverReturned() {
        String body = given().when().get(groupUrl("secret-group"))
                .then().statusCode(200).extract().asString();
        assertTrue(body.contains("w-id"), body);
        assertFalse(body.contains(WORKSPACE_KEY), body);
    }

    @Test
    void storageAccountKeyIsNeverReturned() {
        String body = given().when().get(groupUrl("secret-group"))
                .then().statusCode(200).extract().asString();
        assertTrue(body.contains("floci-aci-share"), body);
        assertTrue(body.contains("flociacisa"), body);
        assertFalse(body.contains(STORAGE_ACCOUNT_KEY), body);
    }
}

/**
 * Nothing secret reaches {@code data/containerinstance.json}, in real-Docker mode with a
 * persistent backend — the mode where the runtime genuinely holds the values in memory.
 */
@QuarkusTest
@TestProfile(ContainerInstanceSecretPersistenceTest.PersistentRealProfile.class)
@DisplayName("Container Instances — secrets never reach persistent storage")
class ContainerInstanceSecretPersistenceTest {

    static final Path DIR = Path.of("target", "aci-secret-persistent");

    public static class PersistentRealProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.storage.mode", "persistent",
                    "floci-az.storage.persistent-path", DIR.toString(),
                    "floci-az.services.container-instance.mocked", "false",
                    "floci-az.services.container-instance.reconcile-interval-seconds", "1");
        }
    }

    @Test
    void secretsAreNeverWrittenToPersistentStorage() throws IOException {
        given().post("/_admin/reset").then().statusCode(204);
        try {
            given().contentType("application/json")
                    .body(ContainerInstanceSecretSafetyTest.FULL_WITH_EVERY_SECRET)
                    .when().put(groupUrl("persisted-secret-group")).then().statusCode(201);

            Path file = DIR.resolve("containerinstance.json");
            assertTrue(Files.exists(file), "the persistent backend must have written " + file);
            String contents = Files.readString(file, StandardCharsets.UTF_8);
            for (String secret : List.of(
                    ContainerInstanceSecretSafetyTest.SECURE_ENV,
                    ContainerInstanceSecretSafetyTest.SECRET_VOLUME_VALUE,
                    ContainerInstanceSecretSafetyTest.REGISTRY_PASSWORD,
                    ContainerInstanceSecretSafetyTest.WORKSPACE_KEY,
                    ContainerInstanceSecretSafetyTest.STORAGE_ACCOUNT_KEY)) {
                assertFalse(contents.contains(secret),
                        "containerinstance.json leaked '" + secret + "'");
            }
        } finally {
            given().when().delete(groupUrl("persisted-secret-group"));
        }
    }
}

/**
 * Nothing secret reaches a log line. Runs in real-Docker mode at DEBUG so both the runtime path
 * and the degradation path are exercised; with no daemon the group degrades and the ERROR-level
 * degradation log is still checked.
 */
@QuarkusTest
@TestProfile(ContainerInstanceSecretLoggingTest.DebugLoggingProfile.class)
@DisplayName("Container Instances — secrets never reach a log line")
class ContainerInstanceSecretLoggingTest {

    static final Path LOG_FILE = Path.of("target", "aci-secret-logging.log");

    public static class DebugLoggingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.container-instance.mocked", "false",
                    "floci-az.services.container-instance.reconcile-interval-seconds", "1",
                    "quarkus.log.category.\"io.floci.az\".level", "DEBUG",
                    "quarkus.log.min-level", "DEBUG",
                    "quarkus.log.file.enable", "true",
                    "quarkus.log.file.append", "false",
                    "quarkus.log.file.level", "DEBUG",
                    "quarkus.log.file.path", LOG_FILE.toString());
        }
    }

    @Test
    void secretsAreNeverLogged() throws IOException, InterruptedException {
        given().post("/_admin/reset").then().statusCode(204);
        try {
            given().contentType("application/json")
                    .body(ContainerInstanceSecretSafetyTest.FULL_WITH_EVERY_SECRET)
                    .when().put(groupUrl("logged-secret-group")).then().statusCode(201);
            given().when().get(groupUrl("logged-secret-group", "&$expand=instanceView"))
                    .then().statusCode(200);
            // Let at least one reconciler tick run so its log lines are captured too.
            Thread.sleep(2500);

            assertTrue(Files.exists(LOG_FILE), "the log file must exist at " + LOG_FILE);
            String contents = Files.readString(LOG_FILE, StandardCharsets.UTF_8);
            for (String secret : List.of(
                    ContainerInstanceSecretSafetyTest.SECURE_ENV,
                    ContainerInstanceSecretSafetyTest.REGISTRY_PASSWORD,
                    ContainerInstanceSecretSafetyTest.SECRET_VOLUME_PLAINTEXT,
                    ContainerInstanceSecretSafetyTest.SECRET_VOLUME_VALUE,
                    ContainerInstanceSecretSafetyTest.WORKSPACE_KEY,
                    ContainerInstanceSecretSafetyTest.STORAGE_ACCOUNT_KEY)) {
                assertFalse(contents.contains(secret), "a log line leaked '" + secret + "'");
            }
        } finally {
            given().when().delete(groupUrl("logged-secret-group"));
        }
    }
}
