package io.floci.az.services.containerinstance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.BASE;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.FULL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.MINIMAL;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.SUB;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.SUB_BASE;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.actionUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.groupUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.logsUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.rgCollectionUrl;
import static io.floci.az.services.containerinstance.ContainerInstanceFixtures.subCollectionUrl;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CRUD, actions and logs for {@code Microsoft.ContainerInstance/containerGroups}, mocked mode. */
@QuarkusTest
@TestProfile(ContainerInstanceProfiles.MockedProfile.class)
@DisplayName("ContainerInstanceHandler — CRUD, actions and logs")
class ContainerInstanceHandlerTest {

    private static final String OTHER_RG_BASE =
            "/subscriptions/" + SUB + "/resourceGroups/other-rg/providers/Microsoft.ContainerInstance";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void create(String name, String body) {
        given().contentType("application/json").body(body)
                .when().put(groupUrl(name)).then().statusCode(201);
    }

    // ── CRUD ───────────────────────────────────────────────────────────────────────────────

    @Test
    void createReturns201WithSucceededAndEchoedProperties() {
        given().contentType("application/json").body(FULL)
                .when().put(groupUrl("demo-group"))
                .then().statusCode(201)
                .body("name", equalTo("demo-group"))
                .body("type", equalTo("Microsoft.ContainerInstance/containerGroups"))
                .body("id", equalTo("/subscriptions/test-sub-aci/resourceGroups/test-rg-aci"
                        + "/providers/Microsoft.ContainerInstance/containerGroups/demo-group"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.osType", equalTo("Linux"))
                .body("properties.restartPolicy", equalTo("Always"))
                .body("properties.containers.size()", equalTo(2))
                .body("properties.containers[0].name", equalTo("web"))
                .body("properties.containers[0].properties.image", equalTo("alpine:3.20"))
                .body("properties.containers[0].properties.resources.requests.memoryInGB",
                        equalTo(1.0f))
                .body("properties.ipAddress.type", equalTo("Public"))
                .body("properties.ipAddress.ip", equalTo("127.0.0.1"))
                .body("properties.ipAddress.fqdn", equalTo("floci-aci-test.eastus.azurecontainer.io"))
                .body("properties.ipAddress.ports[0].port", equalTo(8080))
                .body("properties.instanceView.state", equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.currentState.state",
                        equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.restartCount", equalTo(0));
    }

    @Test
    void createWithoutLocationDefaultsToEastus() {
        String withoutLocation = MINIMAL.replace("\"location\": \"eastus\",", "");
        given().contentType("application/json").body(withoutLocation)
                .when().put(groupUrl("demo-group"))
                .then().statusCode(201)
                .body("location", equalTo("eastus"));
    }

    @Test
    void updateReturns200AndKeepsIdentity() {
        create("demo-group", MINIMAL);
        given().contentType("application/json").body(FULL)
                .when().put(groupUrl("demo-group"))
                .then().statusCode(200)
                .body("properties.containers.size()", equalTo(2))
                .body("tags.env", equalTo("test"));
    }

    @Test
    void getUnknownGroupReturns404() {
        given().when().get(groupUrl("no-such-group"))
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"))
                .body("error.message", equalTo(
                        "The Resource 'Microsoft.ContainerInstance/containerGroups/no-such-group' "
                                + "under resource group 'test-rg-aci' was not found."))
                .body("error.target", nullValue());
    }

    @Test
    void getOmitsInstanceViewWithoutExpand() {
        create("demo-group", FULL);
        given().when().get(groupUrl("demo-group"))
                .then().statusCode(200)
                .body("properties.instanceView", nullValue())
                .body("properties.containers[0].properties.instanceView", nullValue());
    }

    @Test
    void getWithExpandInstanceViewIncludesState() {
        create("demo-group", FULL);
        given().when().get(groupUrl("demo-group", "&$expand=instanceView"))
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.currentState.state",
                        equalTo("Running"));
    }

    @Test
    void expandIsCaseInsensitive() {
        create("demo-group", FULL);
        given().when().get(groupUrl("demo-group", "&$expand=InstanceView"))
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Running"));
    }

    @Test
    void patchReplacesTags() {
        create("demo-group", FULL);
        given().contentType("application/json").body("{\"tags\":{\"owner\":\"floci\"}}")
                .when().patch(groupUrl("demo-group"))
                .then().statusCode(200)
                .body("tags.owner", equalTo("floci"))
                .body("tags.env", nullValue());
    }

    @Test
    void patchIgnoresProperties() {
        create("demo-group", FULL);
        given().contentType("application/json")
                .body("{\"tags\":{\"a\":\"b\"},\"properties\":{\"restartPolicy\":\"Never\"}}")
                .when().patch(groupUrl("demo-group"))
                .then().statusCode(200)
                .body("tags.a", equalTo("b"))
                .body("properties.restartPolicy", equalTo("Always"));
    }

    @Test
    void patchUnknownGroupReturns404() {
        given().contentType("application/json").body("{\"tags\":{}}")
                .when().patch(groupUrl("no-such-group"))
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void deleteReturns204AndIsIdempotent() {
        create("demo-group", MINIMAL);
        assertEquals("", given().when().delete(groupUrl("demo-group"))
                .then().statusCode(204).extract().asString());
        given().when().get(groupUrl("demo-group")).then().statusCode(404);
        given().when().delete(groupUrl("demo-group")).then().statusCode(204);
    }

    @Test
    void listInResourceGroupReturnsOnlyThatGroup() {
        create("group-a", MINIMAL);
        create("group-b", MINIMAL);
        given().contentType("application/json").body(MINIMAL)
                .when().put(OTHER_RG_BASE + "/containerGroups/group-c?api-version=2023-05-01")
                .then().statusCode(201);

        given().when().get(rgCollectionUrl())
                .then().statusCode(200)
                .body("value.name", hasItem("group-a"))
                .body("value.name", hasItem("group-b"))
                .body("value.name", not(hasItem("group-c")));
    }

    @Test
    void listInResourceGroupIsCaseInsensitive() {
        create("group-a", MINIMAL);
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/TEST-RG-ACI"
                        + "/providers/Microsoft.ContainerInstance/containerGroups?api-version=2023-05-01")
                .then().statusCode(200)
                .body("value.name", hasItem("group-a"));
    }

    @Test
    void listInSubscriptionReturnsAllGroups() {
        create("group-a", MINIMAL);
        create("group-b", MINIMAL);
        given().contentType("application/json").body(MINIMAL)
                .when().put(OTHER_RG_BASE + "/containerGroups/group-c?api-version=2023-05-01")
                .then().statusCode(201);

        given().when().get(subCollectionUrl())
                .then().statusCode(200)
                .body("value.name", hasItem("group-a"))
                .body("value.name", hasItem("group-b"))
                .body("value.name", hasItem("group-c"));
    }

    @Test
    void listOmitsInstanceView() {
        create("group-a", FULL);
        given().when().get(rgCollectionUrl())
                .then().statusCode(200)
                .body("value[0].properties.instanceView", nullValue())
                .body("value[0].properties.containers[0].properties.instanceView", nullValue());
    }

    @Test
    void emptyListReturnsEmptyValueArray() {
        assertEquals("{\"value\":[]}",
                given().when().get(rgCollectionUrl()).then().statusCode(200).extract().asString());
    }

    @Test
    void outboundNetworkDependenciesReturnsEmptyArray() {
        create("demo-group", MINIMAL);
        assertEquals("[]", given()
                .when().get(BASE + "/containerGroups/demo-group/outboundNetworkDependenciesEndpoints"
                        + "?api-version=2023-05-01")
                .then().statusCode(200).extract().asString());
    }

    @Test
    void usagesReflectStoredGroups() {
        create("demo-group", FULL);
        given().when().get(SUB_BASE + "/locations/eastus/usages?api-version=2023-05-01")
                .then().statusCode(200)
                .body("value.size()", equalTo(2))
                .body("value[0].id", equalTo("/subscriptions/" + SUB
                        + "/providers/Microsoft.ContainerInstance/locations/eastus/usages/ContainerGroups"))
                .body("value[0].currentValue", equalTo(1))
                .body("value[0].limit", equalTo(100))
                .body("value[0].unit", equalTo("Count"))
                .body("value[0].name.value", equalTo("ContainerGroups"))
                .body("value[1].id", equalTo("/subscriptions/" + SUB
                        + "/providers/Microsoft.ContainerInstance/locations/eastus/usages/StandardCores"))
                .body("value[1].currentValue", equalTo(2))
                .body("value[1].limit", equalTo(100));
    }

    @Test
    void capabilitiesReportAzureMaximums() {
        given().when().get(SUB_BASE + "/locations/eastus/capabilities?api-version=2023-05-01")
                .then().statusCode(200)
                .body("value[0].resourceType", equalTo("containerGroups"))
                .body("value[0].osType", equalTo("Linux"))
                .body("value[0].location", equalTo("eastus"))
                .body("value[0].ipAddressType", equalTo("Public"))
                .body("value[0].gpu", equalTo("None"))
                .body("value[0].capabilities.maxCpu", equalTo(31.0f))
                .body("value[0].capabilities.maxMemoryInGB", equalTo(240.0f))
                .body("value[0].capabilities.maxGpuCount", equalTo(0.0f));
    }

    @Test
    void cachedImagesIsEmpty() {
        assertEquals("{\"value\":[]}", given()
                .when().get(SUB_BASE + "/locations/eastus/cachedImages?api-version=2023-05-01")
                .then().statusCode(200).extract().asString());
    }

    // ── Actions ────────────────────────────────────────────────────────────────────────────

    @Test
    void stopReturns204AndSetsStoppedState() {
        create("demo-group", FULL);
        given().when().post(actionUrl("demo-group", "stop")).then().statusCode(204);

        given().when().get(groupUrl("demo-group", "&$expand=instanceView"))
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Stopped"))
                .body("properties.containers[0].properties.instanceView.currentState.state",
                        equalTo("Terminated"))
                .body("properties.containers[0].properties.instanceView.currentState.exitCode",
                        equalTo(0));
    }

    @Test
    void startAfterStopReturnsRunning() {
        create("demo-group", FULL);
        given().when().post(actionUrl("demo-group", "stop")).then().statusCode(204);
        given().when().post(actionUrl("demo-group", "start")).then().statusCode(204);

        given().when().get(groupUrl("demo-group", "&$expand=instanceView"))
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.currentState.state",
                        equalTo("Running"))
                .body("properties.containers[1].properties.instanceView.currentState.state",
                        equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.restartCount", equalTo(0));
    }

    @Test
    void restartIncrementsRestartCount() {
        create("demo-group", FULL);
        given().when().post(actionUrl("demo-group", "restart")).then().statusCode(204);
        given().when().get(groupUrl("demo-group", "&$expand=instanceView"))
                .then().statusCode(200)
                .body("properties.containers[0].properties.instanceView.restartCount", equalTo(1));

        given().when().post(actionUrl("demo-group", "restart")).then().statusCode(204);
        given().when().get(groupUrl("demo-group", "&$expand=instanceView"))
                .then().statusCode(200)
                .body("properties.containers[0].properties.instanceView.restartCount", equalTo(2));
    }

    @Test
    void actionsOnUnknownGroupReturn404() {
        for (String action : List.of("start", "stop", "restart")) {
            given().when().post(actionUrl("no-such-group", action))
                    .then().statusCode(404)
                    .body("error.code", equalTo("ResourceNotFound"));
        }
    }

    @Test
    void actionsEmitNoAsyncOperationHeaders() {
        create("demo-group", FULL);
        Response response = given().when().post(actionUrl("demo-group", "start"));
        assertEquals(204, response.statusCode());
        assertNull(response.getHeader("Azure-AsyncOperation"));
        assertNull(response.getHeader("Location"));
        assertNull(response.getHeader("Retry-After"));
    }

    @Test
    void putEmitsNoAsyncOperationHeaders() {
        Response response = given().contentType("application/json").body(FULL)
                .when().put(groupUrl("demo-group"));
        assertEquals(201, response.statusCode());
        assertNull(response.getHeader("Azure-AsyncOperation"));
        assertNull(response.getHeader("Location"));
        assertEquals("Succeeded", response.jsonPath().getString("properties.provisioningState"));
    }

    @Test
    void execReturns501() {
        assertEquals("{\"error\":{\"code\":\"NotImplemented\",\"message\":"
                        + "\"Container exec is not implemented by floci-az: "
                        + "the emulator serves no websocket data plane.\"}}",
                given().when().post(BASE + "/containerGroups/demo-group/containers/web/exec"
                                + "?api-version=2023-05-01")
                        .then().statusCode(501).extract().asString());
    }

    @Test
    void attachReturns501() {
        assertEquals("{\"error\":{\"code\":\"NotImplemented\",\"message\":"
                        + "\"Container attach is not implemented by floci-az: "
                        + "the emulator serves no websocket data plane.\"}}",
                given().when().post(BASE + "/containerGroups/demo-group/containers/web/attach"
                                + "?api-version=2023-05-01")
                        .then().statusCode(501).extract().asString());
    }

    // ── Logs ───────────────────────────────────────────────────────────────────────────────

    @Test
    void logsInMockedModeReturnEmptyContent() {
        create("demo-group", FULL);
        assertEquals("{\"content\":\"\"}",
                given().when().get(logsUrl("demo-group", "web"))
                        .then().statusCode(200).extract().asString());
    }

    @Test
    void logsAcceptTailAndTimestampsQueryParameters() {
        create("demo-group", FULL);
        assertEquals("{\"content\":\"\"}",
                given().when().get(logsUrl("demo-group", "web") + "&tail=5&timestamps=true")
                        .then().statusCode(200).extract().asString());
    }

    @Test
    void logsForUnknownGroupReturn404() {
        given().when().get(logsUrl("no-such-group", "web"))
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"))
                .body("error.message", equalTo(
                        "The Resource 'Microsoft.ContainerInstance/containerGroups/no-such-group' "
                                + "under resource group 'test-rg-aci' was not found."));
    }

    // ── Route-level validation (the two catalog rows no PUT reaches) ───────────────────────

    @Test
    void logsForUnknownContainerReturns404() {
        create("demo-group", MINIMAL);
        given().when().get(logsUrl("demo-group", "ghost"))
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"))
                .body("error.target", equalTo("containerName"))
                .body("error.message", equalTo("The container 'ghost' was not found in container "
                        + "group 'demo-group' under resource group 'test-rg-aci'."));
    }

    @Test
    void invalidTailParameterRejected() {
        create("demo-group", MINIMAL);
        given().when().get(logsUrl("demo-group", "web") + "&tail=0")
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidParameter"))
                .body("error.target", equalTo("tail"))
                .body("error.message", equalTo("The value '0' provided for 'tail' is not valid. "
                        + "The value must be greater than or equal to 1."));
    }

    // ── Identity ───────────────────────────────────────────────────────────────────────────

    @Test
    void identityPrincipalIdIsStableAcrossGets() {
        String body = MINIMAL.replace("\"location\": \"eastus\",",
                "\"location\": \"eastus\", \"identity\": {\"type\": \"SystemAssigned\"},");
        create("demo-group", body);

        String principalId = null;
        for (int i = 0; i < 3; i++) {
            Response response = given().when().get(groupUrl("demo-group"));
            assertEquals(200, response.statusCode());
            String current = response.jsonPath().getString("identity.principalId");
            assertNotNull(current);
            assertTrue(!current.isBlank(), "principalId must be a non-empty UUID string");
            if (principalId == null) {
                principalId = current;
            } else {
                assertEquals(principalId, current, "principalId must be stable across GETs");
            }
            assertEquals("00000000-0000-0000-0000-000000000002",
                    response.jsonPath().getString("identity.tenantId"));
        }
    }

    // ── Reset ──────────────────────────────────────────────────────────────────────────────

    @Test
    void resetRemovesEveryContainerGroup() {
        create("group-a", MINIMAL);
        create("group-b", MINIMAL);
        given().post("/_admin/reset").then().statusCode(204);

        assertEquals("{\"value\":[]}",
                given().when().get(rgCollectionUrl()).then().statusCode(200).extract().asString());
        given().when().get(groupUrl("group-a")).then().statusCode(404);
    }

    @Test
    void resetIsIdempotent() {
        given().post("/_admin/reset").then().statusCode(204);
        given().post("/_admin/reset").then().statusCode(204);
    }
}
