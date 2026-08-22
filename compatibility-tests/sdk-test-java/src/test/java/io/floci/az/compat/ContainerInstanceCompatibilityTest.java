package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.containerinstance.ContainerInstanceManager;
import com.azure.resourcemanager.containerinstance.models.ContainerGroup;
import com.azure.resourcemanager.containerinstance.models.ContainerGroupRestartPolicy;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatibility test for Microsoft.ContainerInstance/containerGroups driven by the fluent
 * management SDK {@code com.azure.resourcemanager:azure-resourcemanager-containerinstance},
 * which sends {@code api-version=2023-05-01}.
 *
 * <p>The resource group is created with a raw {@link HttpClient} PUT, because
 * {@code ContainerInstanceManager} exposes no {@code resourceManager()} accessor; this mirrors
 * {@link VmCompatibilityTest}, which drives the ARM plane the same way.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Container Instances Compatibility")
class ContainerInstanceCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String RG = "aci-rg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String GROUP = "aci-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String RG_API = "2021-04-01";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static ContainerInstanceManager manager;

    @BeforeAll
    static void setup() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();

        // Two adjustments are needed to point the management SDK at a plain-HTTP emulator:
        //
        //  1. ContainerInstanceManager builds a StorageManager, which builds an
        //     AuthorizationManager, which dereferences microsoftGraphResourceId — so that key
        //     must be present in the environment map as well as the four ARM ones.
        //  2. Azure Core refuses to attach a bearer token to a non-HTTPS request. The endpoints
        //     are therefore declared as https:// and ForceHttpPolicy rewrites each request back
        //     to http:// after the authentication policy has run — the same trick
        //     EmulatorConfig already uses for the App Configuration SDK.
        String httpsBase = BASE.replace("http://", "https://");
        AzureEnvironment env = new AzureEnvironment(new HashMap<>(Map.of(
                "managementEndpointUrl", httpsBase,
                "resourceManagerEndpointUrl", httpsBase,
                "activeDirectoryEndpointUrl", httpsBase + "/",
                "activeDirectoryResourceId", httpsBase,
                "microsoftGraphResourceId", httpsBase + "/")));

        TokenCredential credential = request ->
                Mono.just(new AccessToken("floci-az-fake-token", OffsetDateTime.now().plusHours(1)));

        manager = ContainerInstanceManager.configure()
                .withPolicy(new EmulatorConfig.ForceHttpPolicy())
                .authenticate(credential, new AzureProfile(TENANT, SUBSCRIPTION, env));

        String rgUrl = BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "?api-version=" + RG_API;
        HttpResponse<String> rgResponse = http.send(
                HttpRequest.newBuilder(URI.create(rgUrl))
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"location\":\"eastus\"}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(rgResponse.statusCode() >= 200 && rgResponse.statusCode() < 300,
                "create resource group failed: " + rgResponse.statusCode() + " " + rgResponse.body());
    }

    @AfterAll
    static void teardown() {
        if (manager != null) {
            try {
                manager.containerGroups().deleteByResourceGroup(RG, GROUP);
            } catch (Exception ignored) {
                // already removed by the delete test
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("define/create returns a Succeeded container group")
    void createContainerGroup() {
        ContainerGroup group = manager.containerGroups()
                .define(GROUP)
                .withRegion("eastus")
                .withExistingResourceGroup(RG)
                .withLinux()
                .withPublicImageRegistryOnly()
                .withEmptyDirectoryVolume("scratch-volume")
                .defineContainerInstance("web")
                    .withImage("alpine:3.20")
                    .withExternalTcpPort(8080)
                    .withCpuCoreCount(1.0)
                    .withMemorySizeInGB(1.0)
                    .withEnvironmentVariable("GREETING", "hello")
                    .withEnvironmentVariableWithSecuredValue("API_TOKEN", "s3cr3t-token")
                    .withVolumeMountSetting("scratch-volume", "/mnt/scratch")
                    .withStartingCommandLine("sh", "-c",
                        "while true; do echo hello-from-java-sdk; sleep 2; done")
                    .attach()
                .withRestartPolicy(ContainerGroupRestartPolicy.ALWAYS)
                .withDnsPrefix("floci-sdk-aci")
                .withTag("suite", "sdk-test-java")
                .create();

        assertEquals(GROUP, group.name());
        assertEquals("eastus", group.regionName());
        assertEquals("Succeeded", group.provisioningState());
        assertEquals(1, group.containers().size());
        assertTrue(group.containers().containsKey("web"));
        assertEquals("alpine:3.20", group.containers().get("web").image());
        assertEquals("sdk-test-java", group.tags().get("suite"));
        assertNotNull(group.ipAddress());
        assertEquals("floci-sdk-aci.eastus.azurecontainer.io", group.fqdn());
    }

    @Test
    @Order(2)
    @DisplayName("getByResourceGroup round-trips the group and redacts the secure value")
    void getContainerGroup() {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        assertEquals(GROUP, group.name());
        assertEquals("Linux", group.osType().toString());
        assertEquals("Always", group.restartPolicy().toString());
        assertEquals(1, group.volumes().size());
        assertTrue(group.volumes().containsKey("scratch-volume"));
        group.containers().get("web").environmentVariables()
                .forEach(v -> assertNull(v.secureValue(),
                        "secureValue must never be returned by the emulator"));
    }

    @Test
    @Order(3)
    @DisplayName("refresh reports every container Running")
    void instanceView() throws Exception {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        for (int i = 0; i < 60 && !"Running".equals(group.state()); i++) {
            Thread.sleep(1000);
            group = group.refresh();
        }
        assertEquals("Running", group.state());
        assertEquals("Running",
                group.containers().get("web").instanceView().currentState().state());
    }

    @Test
    @Order(4)
    @DisplayName("getLogContent returns the container's stdout")
    void logs() throws Exception {
        String content = "";
        for (int i = 0; i < 60 && content.isEmpty(); i++) {
            content = manager.containerGroups().getLogContent(RG, GROUP, "web", 50);
            if (content.isEmpty()) {
                Thread.sleep(1000);
            }
        }
        assertTrue(content.contains("hello-from-java-sdk"),
                "expected container stdout in the log content, got: " + content);
    }

    @Test
    @Order(5)
    @DisplayName("restart, stop and start drive the group state")
    void lifecycleActions() throws Exception {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        group.restart();
        group.stop();

        group = group.refresh();
        assertEquals("Stopped", group.state());

        manager.containerGroups().start(RG, GROUP);
        for (int i = 0; i < 60 && !"Running".equals(group.state()); i++) {
            Thread.sleep(1000);
            group = group.refresh();
        }
        assertEquals("Running", group.state());
    }

    @Test
    @Order(6)
    @DisplayName("list by resource group and by subscription both contain the group")
    void listing() {
        assertTrue(manager.containerGroups().listByResourceGroup(RG).stream()
                .anyMatch(g -> GROUP.equals(g.name())));
        assertTrue(manager.containerGroups().list().stream()
                .anyMatch(g -> GROUP.equals(g.name())));
    }

    @Test
    @Order(7)
    @DisplayName("update replaces the tag collection")
    void updateTags() {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        ContainerGroup updated = group.update()
                .withoutTag("suite")
                .withTag("stage", "compat")
                .apply();
        assertEquals("compat", updated.tags().get("stage"));
        assertNull(updated.tags().get("suite"));
    }

    @Test
    @Order(8)
    @DisplayName("delete removes the group from both listings")
    void deleteContainerGroup() {
        manager.containerGroups().deleteByResourceGroup(RG, GROUP);
        assertTrue(manager.containerGroups().listByResourceGroup(RG).stream()
                .noneMatch(g -> GROUP.equals(g.name())));
        assertTrue(manager.containerGroups().list().stream()
                .noneMatch(g -> GROUP.equals(g.name())));
    }
}
