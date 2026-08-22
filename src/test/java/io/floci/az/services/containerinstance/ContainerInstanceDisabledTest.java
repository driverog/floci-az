package io.floci.az.services.containerinstance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.groupUrl;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * With {@code floci-az.services.container-instance.enabled=false} the handler declines and the
 * routing filter falls through to the generic ARM handler.
 */
@QuarkusTest
@TestProfile(ContainerInstanceProfiles.DisabledProfile.class)
@DisplayName("ContainerInstanceHandler — disabled service")
class ContainerInstanceDisabledTest {

    @Test
    void disabledServiceFallsThroughToGenericArm() {
        Response response = given().when().get(groupUrl("demo-group"));
        assertEquals(404, response.statusCode());
        // The generic ARM handler answers. Its 404 body echoes the requested path, so the
        // discriminator is the absence of a container-group resource envelope, not a substring.
        assertNotEquals("Microsoft.ContainerInstance/containerGroups",
                response.jsonPath().get("type"),
                "the generic ARM handler answers, not the container-instance handler; body was: "
                        + response.asString());
    }
}
