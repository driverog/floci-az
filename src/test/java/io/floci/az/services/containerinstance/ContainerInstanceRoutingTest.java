package io.floci.az.services.containerinstance;

import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.AzureServiceRegistry;
import io.floci.az.core.ServiceRoutes;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.BASE;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.MINIMAL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.SUB_BASE;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.groupUrl;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One case per row of the routing table, each asserting a concrete response body.
 *
 * <p>This is the structural guard against the PR #106 failure mode: a handler that forgot
 * {@code routes()} fails {@link #providerNamespaceIsClaimedExactlyOnce}, and a route that
 * returned a placeholder fails {@link #everyRoutingTableRowReturnsARealBody}.</p>
 */
@QuarkusTest
@TestProfile(ContainerInstanceProfiles.MockedProfile.class)
@DisplayName("ContainerInstanceHandler — routing")
class ContainerInstanceRoutingTest {

    @Inject
    AzureServiceRegistry registry;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void providerNamespaceIsClaimedExactlyOnce() {
        List<String> claimants = new ArrayList<>();
        for (AzureServiceHandler handler : registry.handlers()) {
            for (ServiceRoutes.ProviderRoute provider : handler.routes().providers()) {
                if ("/providers/Microsoft.ContainerInstance/".equals(provider.marker())) {
                    claimants.add(handler.getServiceType());
                }
            }
        }
        assertEquals(List.of("containerinstance"), claimants,
                "exactly one handler must claim Microsoft.ContainerInstance, and it must be "
                        + "the container-instance handler");
    }

    @Test
    void everyRoutingTableRowReturnsARealBody() {
        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("demo-group"))
                .then().statusCode(201);

        given().when().get(SUB_BASE + "/containerGroups?api-version=2023-05-01")
                .then().statusCode(200).body("value.name", hasItem("demo-group"));

        given().when().get(BASE + "/containerGroups?api-version=2023-05-01")
                .then().statusCode(200).body("value.name", hasItem("demo-group"));

        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("demo-group"))
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(groupUrl("demo-group"))
                .then().statusCode(200)
                .body("name", equalTo("demo-group"))
                .body("properties.instanceView", nullValue());

        given().when().get(groupUrl("demo-group", "&$expand=instanceView"))
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Running"));

        given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
                .when().patch(groupUrl("demo-group"))
                .then().statusCode(200).body("tags.a", equalTo("b"));

        for (String action : List.of("start", "stop", "restart")) {
            String body = given()
                    .when().post(BASE + "/containerGroups/demo-group/" + action
                            + "?api-version=2023-05-01")
                    .then().statusCode(204).extract().asString();
            assertEquals("", body, action + " must return an empty body");
        }

        given().when().get(BASE + "/containerGroups/demo-group/containers/web/logs"
                        + "?api-version=2023-05-01")
                .then().statusCode(200).body("content", equalTo(""));

        String outbound = given()
                .when().get(BASE + "/containerGroups/demo-group/outboundNetworkDependenciesEndpoints"
                        + "?api-version=2023-05-01")
                .then().statusCode(200).extract().asString();
        assertEquals("[]", outbound);

        given().when().post(BASE + "/containerGroups/demo-group/containers/web/exec"
                        + "?api-version=2023-05-01")
                .then().statusCode(501).body("error.code", equalTo("NotImplemented"));

        given().when().post(BASE + "/containerGroups/demo-group/containers/web/attach"
                        + "?api-version=2023-05-01")
                .then().statusCode(501).body("error.code", equalTo("NotImplemented"));

        given().when().get(SUB_BASE + "/locations/eastus/usages?api-version=2023-05-01")
                .then().statusCode(200)
                .body("value.size()", equalTo(2))
                .body("value[0].id", endsWith("/usages/ContainerGroups"));

        given().when().get(SUB_BASE + "/locations/eastus/capabilities?api-version=2023-05-01")
                .then().statusCode(200)
                .body("value[0].capabilities.maxCpu", equalTo(31.0f));

        given().when().get(SUB_BASE + "/locations/eastus/cachedImages?api-version=2023-05-01")
                .then().statusCode(200).body("value.size()", equalTo(0));

        String deleted = given().when().delete(groupUrl("demo-group"))
                .then().statusCode(204).extract().asString();
        assertEquals("", deleted, "DELETE must return an empty body");
    }

    @Test
    void unknownPathReturns404WithTail() {
        given().when().get(BASE + "/containerGroups/demo-group/nonsense?api-version=2023-05-01")
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"))
                .body("error.message", equalTo("Unsupported Microsoft.ContainerInstance path: "
                        + "containerGroups/demo-group/nonsense"))
                .body("error.target", nullValue());
    }

    @Test
    void unsupportedMethodReturns405() {
        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("demo-group")).then().statusCode(201);

        // HTTP HEAD carries no entity by definition, so the error body is asserted on a second
        // matched route reached with an unsupported method: PUT on the start action.
        given().when().head(groupUrl("demo-group")).then().statusCode(405);

        given().contentType("application/json").body("{}")
                .when().put(BASE + "/containerGroups/demo-group/start?api-version=2023-05-01")
                .then().statusCode(405)
                .body("error.code", equalTo("MethodNotAllowed"))
                .body("error.message", equalTo("Method not allowed"));
    }

    @Test
    void anyApiVersionIsAccepted() {
        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("demo-group")).then().statusCode(201);

        String first = null;
        for (String apiVersion : List.of("2023-05-01", "2024-05-01-preview", "2025-09-01")) {
            String body = given()
                    .when().get(BASE + "/containerGroups/demo-group?api-version=" + apiVersion)
                    .then().statusCode(200)
                    .body("name", equalTo("demo-group"))
                    .extract().asString();
            if (first == null) {
                first = body;
            } else {
                assertEquals(first, body, "every api-version must be served the same body");
            }
        }
    }

    @Test
    void missingApiVersionIsAccepted() {
        given().contentType("application/json").body(MINIMAL)
                .when().put(groupUrl("demo-group")).then().statusCode(201);

        given().when().get(BASE + "/containerGroups/demo-group")
                .then().statusCode(200).body("name", equalTo("demo-group"));
    }
}
