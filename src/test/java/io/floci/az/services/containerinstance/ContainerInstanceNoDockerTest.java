package io.floci.az.services.containerinstance;

import io.quarkus.test.junit.QuarkusTest;
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
