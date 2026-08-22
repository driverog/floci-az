package io.floci.az.services.containerinstance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.BASE;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.FULL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.actionUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.groupUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.logsUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.rgCollectionUrl;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-Docker-socket guarantee: this class runs unconditionally, including in CI where no
 * Docker socket exists, and is the gate that keeps the service usable without a daemon.
 */
@QuarkusTest
@TestProfile(ContainerInstanceProfiles.MockedProfile.class)
@DisplayName("Container Instances — no Docker socket")
class ContainerInstanceNoDockerTest {

    @Test
    void serviceWorksInMockedModeWithNoDockerSocket() {
        given().post("/_admin/reset").then().statusCode(204);

        List<LogRecord> errors = new ArrayList<>();
        Handler capture = capturingHandler(errors);
        Logger root = LogManager.getLogManager().getLogger("");
        root.addHandler(capture);
        try {
            given().contentType("application/json").body(FULL)
                    .when().put(groupUrl("nodocker-group")).then().statusCode(201);

            given().when().get(groupUrl("nodocker-group")).then().statusCode(200);

            given().when().get(groupUrl("nodocker-group", "&$expand=instanceView"))
                    .then().statusCode(200)
                    .body("properties.instanceView.state", equalTo("Running"));

            given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
                    .when().patch(groupUrl("nodocker-group")).then().statusCode(200);

            given().when().get(rgCollectionUrl()).then().statusCode(200);

            for (String action : List.of("start", "stop", "restart")) {
                given().when().post(actionUrl("nodocker-group", action)).then().statusCode(204);
            }

            assertEquals("{\"content\":\"\"}",
                    given().when().get(logsUrl("nodocker-group", "web"))
                            .then().statusCode(200).extract().asString());

            assertEquals("[]", given()
                    .when().get(BASE + "/containerGroups/nodocker-group"
                            + "/outboundNetworkDependenciesEndpoints?api-version=2023-05-01")
                    .then().statusCode(200).extract().asString());

            given().when().delete(groupUrl("nodocker-group")).then().statusCode(204);
        } finally {
            root.removeHandler(capture);
        }

        List<String> messages = errors.stream()
                .filter(record -> record.getLoggerName() != null
                        && record.getLoggerName().startsWith("io.floci.az"))
                .map(LogRecord::getMessage)
                .toList();
        assertTrue(messages.isEmpty(), "the emulator must log no ERROR in mocked mode: " + messages);
    }

    private static Handler capturingHandler(List<LogRecord> sink) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    synchronized (sink) {
                        sink.add(record);
                    }
                }
            }

            @Override
            public void flush() {
                // Nothing is buffered: publish() appends straight to the sink.
            }

            @Override
            public void close() {
                // No resource is held open by this handler.
            }
        };
    }
}

/**
 * Real-Docker mode pointed at a socket that does not exist. Every case runs unconditionally:
 * this is what proves the graceful-degradation contract holds in CI, where no daemon exists.
 */
@QuarkusTest
@TestProfile(ContainerInstanceDeadDaemonTest.DeadDaemonProfile.class)
@DisplayName("Container Instances — real mode with no Docker daemon")
class ContainerInstanceDeadDaemonTest {

    static final java.nio.file.Path DEAD_DAEMON_LOG =
            java.nio.file.Path.of("target", "aci-dead-daemon.log");

    public static class DeadDaemonProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                    "floci-az.services.container-instance.mocked", "false",
                    "floci-az.docker.docker-host", "unix:///nonexistent/docker.sock",
                    "quarkus.log.file.enable", "true",
                    "quarkus.log.file.append", "false",
                    "quarkus.log.file.path", DEAD_DAEMON_LOG.toString());
        }
    }

    @Test
    void realModeWithNoDockerDaemonDegradesGracefully() {
        given().post("/_admin/reset").then().statusCode(204);

        given().contentType("application/json").body(FULL)
                .when().put(groupUrl("degraded-group"))
                .then().statusCode(201)
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.instanceView.state", equalTo("Running"))
                .body("properties.instanceView.events.name",
                        org.hamcrest.Matchers.hasItem("DockerUnavailable"))
                .body("properties.instanceView.events.find { it.name == 'DockerUnavailable' }.type",
                        equalTo("Warning"))
                .body("properties.instanceView.events.find { it.name == 'DockerUnavailable' }.message",
                        equalTo("The Docker daemon is not reachable; this container group is "
                                + "emulated without running containers."));

        assertEquals("{\"content\":\"\"}",
                given().when().get(logsUrl("degraded-group", "web"))
                        .then().statusCode(200).extract().asString());

        for (String action : List.of("start", "stop", "restart")) {
            given().when().post(actionUrl("degraded-group", action)).then().statusCode(204);
        }
        given().when().delete(groupUrl("degraded-group")).then().statusCode(204);
    }

    @Test
    void startupSucceedsWithNoDockerDaemon() throws java.io.IOException {
        given().when().get("/health").then().statusCode(200);

        assertTrue(java.nio.file.Files.exists(DEAD_DAEMON_LOG),
                "the startup log file must exist at " + DEAD_DAEMON_LOG);
        boolean banner = java.nio.file.Files
                .readAllLines(DEAD_DAEMON_LOG, java.nio.charset.StandardCharsets.UTF_8).stream()
                .anyMatch(line -> line.matches(".*\\s+aci\\s+\\[enabled \\]\\s+docker: .*"));
        assertTrue(banner, "the banner must carry the aci line even with no Docker daemon");
    }

    @Test
    void resetSurvivesADeadDockerDaemon() {
        given().contentType("application/json").body(FULL)
                .when().put(groupUrl("reset-degraded-group")).then().statusCode(201);
        given().post("/_admin/reset").then().statusCode(204);
        given().post("/_admin/reset").then().statusCode(204);
    }
}
