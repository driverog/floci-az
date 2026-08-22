package io.floci.az.services.containerinstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One case per validation rule V1–V50 of the resource model, asserting the exact HTTP status,
 * {@code error.code}, {@code error.target} and {@code error.message}.
 *
 * <p>{@link ContainerGroupValidator} performs no I/O and has no injected collaborators, so this
 * is a plain JUnit test rather than a {@code @QuarkusTest} — the validator is constructed
 * directly.</p>
 */
@DisplayName("ContainerGroupValidator — rules V1–V50")
class ContainerInstanceValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContainerGroupValidator validator = new ContainerGroupValidator();

    // ── V1 — container group name ──────────────────────────────────────────────────────────

    private static String invalidNameMessage(String name) {
        return "The Resource Name '" + name + "' is invalid. Container group names must be 1 to 63 "
                + "characters long, contain only lowercase letters, numbers and hyphens, must not "
                + "start or end with a hyphen, and must not contain consecutive hyphens.";
    }

    @Test
    void groupNameWithUnderscoreRejected() {
        assertError(validate("Demo_Group", ContainerInstanceFixtures.MINIMAL),
                400, "InvalidResourceName", "containerGroupName", invalidNameMessage("Demo_Group"));
    }

    @Test
    void groupNameWithConsecutiveHyphensRejected() {
        assertError(validate("demo--group", ContainerInstanceFixtures.MINIMAL),
                400, "InvalidResourceName", "containerGroupName", invalidNameMessage("demo--group"));
    }

    @Test
    void groupNameWithTrailingHyphenRejected() {
        assertError(validate("demo-", ContainerInstanceFixtures.MINIMAL),
                400, "InvalidResourceName", "containerGroupName", invalidNameMessage("demo-"));
    }

    @Test
    void groupNameTooLongRejected() {
        String name = "a".repeat(64);
        assertError(validate(name, ContainerInstanceFixtures.MINIMAL),
                400, "InvalidResourceName", "containerGroupName", invalidNameMessage(name));
    }

    @Test
    void minimalBodyPassesEveryRule() {
        assertTrue(validate("demo-group", ContainerInstanceFixtures.MINIMAL).isEmpty());
        assertTrue(validate("demo-group", ContainerInstanceFixtures.FULL).isEmpty());
    }

    // ── V2–V6 — envelope ───────────────────────────────────────────────────────────────────

    @Test
    void bodyThatIsNotAnObjectRejected() {
        assertError(validate("demo-group", "[]"), 400, "InvalidRequestContent", null,
                "The request content was invalid and could not be deserialized: "
                        + "the request body must be a JSON object.");
    }

    @Test
    void missingPropertiesRejected() {
        assertError(validate("demo-group", "{\"location\":\"eastus\"}"),
                400, "InvalidRequestContent", "properties", missingMessage("properties"));
    }

    @Test
    void missingOsTypeRejected() {
        String body = """
                {"location":"eastus","properties":{
                  "containers":[{"name":"web","properties":{"image":"alpine:3.20",
                    "resources":{"requests":{"cpu":1.0,"memoryInGB":1.0}}}}]}}""";
        assertError(validate("demo-group", body),
                400, "InvalidRequestContent", "properties.osType", missingMessage("properties.osType"));
    }

    @Test
    void invalidOsTypeRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Plan9\"", CONTAINERS)),
                400, "InvalidParameter", "properties.osType",
                invalidMessage("Plan9", "properties.osType",
                        "Valid values are 'Linux' and 'Windows'."));
    }

    @Test
    void missingContainersRejected() {
        assertError(validate("demo-group", "{\"location\":\"eastus\",\"properties\":{\"osType\":\"Linux\"}}"),
                400, "InvalidRequestContent", "properties.containers",
                missingMessage("properties.containers"));
    }

    @Test
    void emptyContainersRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", "\"containers\":[]")),
                400, "InvalidRequestContent", "properties.containers",
                missingMessage("properties.containers"));
    }

    // ── V7–V12 — container identity and image ──────────────────────────────────────────────

    @Test
    void tooManyContainersRejected() {
        String containers = IntStream.rangeClosed(1, 61)
                .mapToObj(i -> container("c" + i, "\"image\":\"alpine:3.20\","
                        + "\"resources\":{\"requests\":{\"cpu\":0.1,\"memoryInGB\":0.1}}"))
                .collect(Collectors.joining(",", "\"containers\":[", "]"));
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidParameter", "properties.containers",
                invalidMessage("61", "properties.containers",
                        "A container group may contain at most 60 containers."));
    }

    @Test
    void missingContainerNameRejected() {
        String containers = "\"containers\":[{\"properties\":{\"image\":\"alpine:3.20\","
                + "\"resources\":{\"requests\":{\"cpu\":1.0,\"memoryInGB\":1.0}}}}]";
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidRequestContent", "properties.containers[0].name",
                missingMessage("properties.containers[0].name"));
    }

    @Test
    void uppercaseContainerNameRejected() {
        String containers = "\"containers\":[" + container("Web", DEFAULT_CONTAINER_PROPS) + "]";
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidParameter", "properties.containers[0].name",
                invalidMessage("Web", "properties.containers[0].name",
                        "Container names must be 1 to 63 characters long and contain only lowercase "
                                + "letters, numbers and hyphens, with a hyphen allowed anywhere except "
                                + "the first or last character."));
    }

    @Test
    void duplicateContainerNameRejected() {
        String containers = "\"containers\":[" + container("web", DEFAULT_CONTAINER_PROPS) + ","
                + container("web", DEFAULT_CONTAINER_PROPS) + "]";
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidParameter", "properties.containers[1].name",
                invalidMessage("web", "properties.containers[1].name",
                        "Container names must be unique within a container group."));
    }

    @Test
    void missingContainerPropertiesRejected() {
        String containers = "\"containers\":[{\"name\":\"web\"}]";
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidRequestContent", "properties.containers[0].properties",
                missingMessage("properties.containers[0].properties"));
    }

    @Test
    void missingImageRejected() {
        String containers = "\"containers\":[" + container("web",
                "\"resources\":{\"requests\":{\"cpu\":1.0,\"memoryInGB\":1.0}}") + "]";
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidRequestContent", "properties.containers[0].properties.image",
                missingMessage("properties.containers[0].properties.image"));
    }

    @Test
    void blankImageRejected() {
        String containers = "\"containers\":[" + container("web",
                "\"image\":\"  \",\"resources\":{\"requests\":{\"cpu\":1.0,\"memoryInGB\":1.0}}") + "]";
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidRequestContent", "properties.containers[0].properties.image",
                missingMessage("properties.containers[0].properties.image"));
    }

    // ── V13–V18 — resources ────────────────────────────────────────────────────────────────

    @Test
    void missingResourcesRejected() {
        assertError(validate("demo-group", oneContainer("\"image\":\"alpine:3.20\"")),
                400, "InvalidRequestContent",
                "properties.containers[0].properties.resources.requests",
                missingMessage("properties.containers[0].properties.resources.requests"));
    }

    @Test
    void missingCpuRejected() {
        assertError(validate("demo-group", oneContainer(
                        "\"image\":\"alpine:3.20\",\"resources\":{\"requests\":{\"memoryInGB\":1.0}}")),
                400, "InvalidRequestContent",
                "properties.containers[0].properties.resources.requests",
                missingMessage("properties.containers[0].properties.resources.requests"));
    }

    @Test
    void zeroCpuRejected() {
        assertError(validate("demo-group", oneContainer(
                        "\"image\":\"alpine:3.20\",\"resources\":{\"requests\":{\"cpu\":0,\"memoryInGB\":1.0}}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.resources.requests.cpu",
                invalidMessage("0.0", "properties.containers[0].properties.resources.requests.cpu",
                        "The value must be greater than 0."));
    }

    @Test
    void negativeMemoryRejected() {
        assertError(validate("demo-group", oneContainer(
                        "\"image\":\"alpine:3.20\",\"resources\":{\"requests\":{\"cpu\":1.0,\"memoryInGB\":-1}}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.resources.requests.memoryInGB",
                invalidMessage("-1.0",
                        "properties.containers[0].properties.resources.requests.memoryInGB",
                        "The value must be greater than 0."));
    }

    @Test
    void groupCpuSumOverLimitRejected() {
        String big = "\"image\":\"alpine:3.20\","
                + "\"resources\":{\"requests\":{\"cpu\":16,\"memoryInGB\":1.0}}";
        String containers = "\"containers\":[" + container("a", big) + "," + container("b", big) + "]";
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", containers)),
                400, "InvalidParameter", "properties.containers",
                invalidMessage("32.0", "properties.containers",
                        "The total CPU requested by a container group may not exceed 31."));
    }

    @Test
    void groupMemorySumOverLimitRejected() {
        assertError(validate("demo-group", oneContainer(
                        "\"image\":\"alpine:3.20\",\"resources\":{\"requests\":{\"cpu\":1.0,\"memoryInGB\":241}}")),
                400, "InvalidParameter", "properties.containers",
                invalidMessage("241.0", "properties.containers",
                        "The total memory requested by a container group may not exceed 240 GB."));
    }

    @Test
    void limitBelowRequestRejected() {
        assertError(validate("demo-group", oneContainer("\"image\":\"alpine:3.20\",\"resources\":{"
                        + "\"requests\":{\"cpu\":2,\"memoryInGB\":1.0},\"limits\":{\"cpu\":1}}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.resources.limits.cpu",
                invalidMessage("1.0", "properties.containers[0].properties.resources.limits.cpu",
                        "A resource limit must be greater than or equal to the corresponding "
                                + "resource request."));
    }

    @Test
    void invalidGpuSkuRejected() {
        assertError(validate("demo-group", oneContainer("\"image\":\"alpine:3.20\",\"resources\":{"
                        + "\"requests\":{\"cpu\":1.0,\"memoryInGB\":1.0,"
                        + "\"gpu\":{\"count\":1,\"sku\":\"A100\"}}}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.resources.requests.gpu.sku",
                invalidMessage("A100",
                        "properties.containers[0].properties.resources.requests.gpu.sku",
                        "Valid values are 'K80', 'P100' and 'V100'."));
    }

    @Test
    void missingGpuCountRejected() {
        assertError(validate("demo-group", oneContainer("\"image\":\"alpine:3.20\",\"resources\":{"
                        + "\"requests\":{\"cpu\":1.0,\"memoryInGB\":1.0,\"gpu\":{\"sku\":\"K80\"}}}")),
                400, "InvalidRequestContent",
                "properties.containers[0].properties.resources.requests.gpu.count",
                missingMessage("properties.containers[0].properties.resources.requests.gpu.count"));
    }

    // ── V19, V20 — container ports ─────────────────────────────────────────────────────────

    @Test
    void containerPortOutOfRangeRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"ports\":[{\"port\":70000}]")),
                400, "InvalidParameter", "properties.containers[0].properties.ports[0].port",
                invalidMessage("70000", "properties.containers[0].properties.ports[0].port",
                        "A port number must be between 1 and 65535."));
    }

    @Test
    void containerPortZeroRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"ports\":[{\"port\":0}]")),
                400, "InvalidParameter", "properties.containers[0].properties.ports[0].port",
                invalidMessage("0", "properties.containers[0].properties.ports[0].port",
                        "A port number must be between 1 and 65535."));
    }

    @Test
    void invalidPortProtocolRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"ports\":[{\"port\":80,\"protocol\":\"SCTP\"}]")),
                400, "InvalidParameter", "properties.containers[0].properties.ports[0].protocol",
                invalidMessage("SCTP", "properties.containers[0].properties.ports[0].protocol",
                        "Valid values are 'TCP' and 'UDP'."));
    }

    // ── V21, V22 — environment variables ───────────────────────────────────────────────────

    @Test
    void envVarNameWithHyphenRejected() {
        assertError(validate("demo-group", oneContainer(DEFAULT_CONTAINER_PROPS
                        + ",\"environmentVariables\":[{\"name\":\"MY-VAR\",\"value\":\"x\"}]")),
                400, "InvalidParameter",
                "properties.containers[0].properties.environmentVariables[0].name",
                invalidMessage("MY-VAR",
                        "properties.containers[0].properties.environmentVariables[0].name",
                        "Environment variable names must be 1 to 63 characters long and contain only "
                                + "letters, numbers and underscores, with an underscore allowed anywhere "
                                + "except the first or last character."));
    }

    @Test
    void envVarWithBothValueAndSecureValueRejected() {
        assertError(validate("demo-group", oneContainer(DEFAULT_CONTAINER_PROPS
                        + ",\"environmentVariables\":[{\"name\":\"T\",\"value\":\"a\",\"secureValue\":\"b\"}]")),
                400, "InvalidParameter",
                "properties.containers[0].properties.environmentVariables[0]",
                invalidMessage("T", "properties.containers[0].properties.environmentVariables[0]",
                        "An environment variable may set either 'value' or 'secureValue', not both."));
    }

    // ── V23, V24, V25 — volume mounts ──────────────────────────────────────────────────────

    @Test
    void missingVolumeMountPathRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"volumeMounts\":[{\"name\":\"v\"}]")),
                400, "InvalidRequestContent",
                "properties.containers[0].properties.volumeMounts[0].mountPath",
                missingMessage("properties.containers[0].properties.volumeMounts[0].mountPath"));
    }

    @Test
    void relativeMountPathRejected() {
        assertError(validate("demo-group", oneContainer(DEFAULT_CONTAINER_PROPS
                        + ",\"volumeMounts\":[{\"name\":\"scratch-volume\",\"mountPath\":\"mnt/data\"}]")),
                400, "InvalidParameter",
                "properties.containers[0].properties.volumeMounts[0].mountPath",
                invalidMessage("mnt/data",
                        "properties.containers[0].properties.volumeMounts[0].mountPath",
                        "A mount path must be absolute and must not contain a colon."));
    }

    @Test
    void mountPathWithColonRejected() {
        assertError(validate("demo-group", oneContainer(DEFAULT_CONTAINER_PROPS
                        + ",\"volumeMounts\":[{\"name\":\"scratch-volume\",\"mountPath\":\"/mnt:data\"}]")),
                400, "InvalidParameter",
                "properties.containers[0].properties.volumeMounts[0].mountPath",
                invalidMessage("/mnt:data",
                        "properties.containers[0].properties.volumeMounts[0].mountPath",
                        "A mount path must be absolute and must not contain a colon."));
    }

    @Test
    void volumeMountWithNoMatchingVolumeRejected() {
        assertError(validate("demo-group", oneContainer(DEFAULT_CONTAINER_PROPS
                        + ",\"volumeMounts\":[{\"name\":\"ghost-volume\",\"mountPath\":\"/mnt/data\"}]")),
                400, "InvalidParameter",
                "properties.containers[0].properties.volumeMounts[0].name",
                invalidMessage("ghost-volume",
                        "properties.containers[0].properties.volumeMounts[0].name",
                        "No volume with that name is declared in properties.volumes."));
    }

    // ── V26–V31 — volumes ──────────────────────────────────────────────────────────────────

    private static final String VOLUME_NAME_REASON =
            "Volume names must be 5 to 63 characters long, contain only lowercase letters, numbers "
                    + "and hyphens, must not start or end with a hyphen, must not contain consecutive "
                    + "hyphens, and must be unique within a container group.";
    private static final String VOLUME_KIND_REASON =
            "Exactly one of 'azureFile', 'emptyDir', 'secret' or 'gitRepo' must be specified.";
    private static final String SECRET_REASON =
            "Secret volume values must be Base64-encoded and secret keys may contain only letters, "
                    + "numbers, dot, underscore and hyphen.";

    @Test
    void tooManyVolumesRejected() {
        String volumes = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "{\"name\":\"vol-%04d\",\"emptyDir\":{}}".formatted(i))
                .collect(Collectors.joining(",", "\"volumes\":[", "]"));
        assertError(validate("demo-group", props("\"osType\":\"Linux\"", CONTAINERS, volumes)),
                400, "InvalidParameter", "properties.volumes",
                invalidMessage("21", "properties.volumes",
                        "A container group may contain at most 20 volumes."));
    }

    @Test
    void shortVolumeNameRejected() {
        assertError(validate("demo-group", withVolumes("{\"name\":\"abc\",\"emptyDir\":{}}")),
                400, "InvalidParameter", "properties.volumes[0].name",
                invalidMessage("abc", "properties.volumes[0].name", VOLUME_NAME_REASON));
    }

    @Test
    void duplicateVolumeNameRejected() {
        assertError(validate("demo-group", withVolumes(
                        "{\"name\":\"scratch-volume\",\"emptyDir\":{}},"
                                + "{\"name\":\"scratch-volume\",\"emptyDir\":{}}")),
                400, "InvalidParameter", "properties.volumes[1].name",
                invalidMessage("scratch-volume", "properties.volumes[1].name", VOLUME_NAME_REASON));
    }

    @Test
    void volumeWithNoKindRejected() {
        assertError(validate("demo-group", withVolumes("{\"name\":\"scratch-volume\"}")),
                400, "InvalidParameter", "properties.volumes[0]",
                invalidMessage("scratch-volume", "properties.volumes[0]", VOLUME_KIND_REASON));
    }

    @Test
    void volumeWithTwoKindsRejected() {
        assertError(validate("demo-group", withVolumes(
                        "{\"name\":\"scratch-volume\",\"emptyDir\":{},\"secret\":{\"k\":\"aGk=\"}}")),
                400, "InvalidParameter", "properties.volumes[0]",
                invalidMessage("scratch-volume", "properties.volumes[0]", VOLUME_KIND_REASON));
    }

    @Test
    void gitRepoVolumeRejected() {
        assertError(validate("demo-group", withVolumes(
                        "{\"name\":\"repo-volume\",\"gitRepo\":{\"repository\":\"https://example.com/r.git\"}}")),
                400, "NotSupported", "properties.volumes[0].gitRepo",
                "Volume type 'gitRepo' is not supported by floci-az. "
                        + "Use 'emptyDir', 'secret' or 'azureFile' instead.");
    }

    @Test
    void nonBase64SecretRejected() {
        assertError(validate("demo-group", withVolumes(
                        "{\"name\":\"secret-volume\",\"secret\":{\"k\":\"not base64!!\"}}")),
                400, "InvalidParameter", "properties.volumes[0].secret",
                invalidMessage("k", "properties.volumes[0].secret", SECRET_REASON));
    }

    @Test
    void secretKeyWithSlashRejected() {
        assertError(validate("demo-group", withVolumes(
                        "{\"name\":\"secret-volume\",\"secret\":{\"a/b\":\"aGk=\"}}")),
                400, "InvalidParameter", "properties.volumes[0].secret",
                invalidMessage("a/b", "properties.volumes[0].secret", SECRET_REASON));
    }

    @Test
    void azureFileWithoutShareNameRejected() {
        assertError(validate("demo-group", withVolumes(
                        "{\"name\":\"share-volume\",\"azureFile\":{\"storageAccountName\":\"sa\"}}")),
                400, "InvalidRequestContent", "properties.volumes[0].azureFile.shareName",
                missingMessage("properties.volumes[0].azureFile.shareName"));
    }

    // ── V32, V33, V34 — group enums ────────────────────────────────────────────────────────

    @Test
    void invalidRestartPolicyRejected() {
        assertError(validate("demo-group",
                        props("\"osType\":\"Linux\",\"restartPolicy\":\"Sometimes\"", CONTAINERS)),
                400, "InvalidParameter", "properties.restartPolicy",
                invalidMessage("Sometimes", "properties.restartPolicy",
                        "Valid values are 'Always', 'OnFailure' and 'Never'."));
    }

    @Test
    void invalidSkuRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\",\"sku\":\"Premium\"", CONTAINERS)),
                400, "InvalidParameter", "properties.sku",
                invalidMessage("Premium", "properties.sku",
                        "Valid values are 'Standard', 'Dedicated' and 'Confidential'."));
    }

    @Test
    void invalidPriorityRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\",\"priority\":\"Low\"", CONTAINERS)),
                400, "InvalidParameter", "properties.priority",
                invalidMessage("Low", "properties.priority", "Valid values are 'Regular' and 'Spot'."));
    }

    // ── V35–V41 — ipAddress ────────────────────────────────────────────────────────────────

    @Test
    void missingIpAddressTypeRejected() {
        assertError(validate("demo-group", withIpAddress("{\"ports\":[{\"port\":8080}]}")),
                400, "InvalidRequestContent", "properties.ipAddress.type",
                missingMessage("properties.ipAddress.type"));
    }

    @Test
    void invalidIpAddressTypeRejected() {
        assertError(validate("demo-group",
                        withIpAddress("{\"type\":\"Internal\",\"ports\":[{\"port\":8080}]}")),
                400, "InvalidParameter", "properties.ipAddress.type",
                invalidMessage("Internal", "properties.ipAddress.type",
                        "Valid values are 'Public' and 'Private'."));
    }

    @Test
    void ipAddressWithoutPortsRejected() {
        assertError(validate("demo-group", withIpAddress("{\"type\":\"Public\"}")),
                400, "InvalidRequestContent", "properties.ipAddress.ports",
                missingMessage("properties.ipAddress.ports"));
    }

    @Test
    void tooManyGroupPortsRejected() {
        assertError(validate("demo-group", withIpAddress("{\"type\":\"Public\",\"ports\":["
                        + "{\"port\":80},{\"port\":81},{\"port\":82},"
                        + "{\"port\":83},{\"port\":84},{\"port\":85}]}")),
                400, "InvalidParameter", "properties.ipAddress.ports",
                invalidMessage("6", "properties.ipAddress.ports",
                        "A container group may expose at most 5 ports, and each port number may "
                                + "appear only once."));
    }

    @Test
    void duplicateGroupPortRejected() {
        assertError(validate("demo-group",
                        withIpAddress("{\"type\":\"Public\",\"ports\":[{\"port\":80},{\"port\":80}]}")),
                400, "InvalidParameter", "properties.ipAddress.ports",
                invalidMessage("2", "properties.ipAddress.ports",
                        "A container group may expose at most 5 ports, and each port number may "
                                + "appear only once."));
    }

    @Test
    void groupPortOutOfRangeRejected() {
        assertError(validate("demo-group",
                        withIpAddress("{\"type\":\"Public\",\"ports\":[{\"port\":99999}]}")),
                400, "InvalidParameter", "properties.ipAddress.ports[0].port",
                invalidMessage("99999", "properties.ipAddress.ports[0].port",
                        "A port number must be between 1 and 65535."));
    }

    @Test
    void groupPortNotExposedByAnyContainerRejected() {
        assertError(validate("demo-group",
                        withIpAddress("{\"type\":\"Public\",\"ports\":[{\"port\":8081}]}")),
                400, "InvalidParameter", "properties.ipAddress.ports[0].port",
                invalidMessage("8081", "properties.ipAddress.ports[0].port",
                        "The port is not exposed by any container in the container group."));
    }

    private static final String DNS_LABEL_REASON =
            "A DNS name label must be 5 to 63 characters long and contain only letters, numbers and "
                    + "hyphens, with a hyphen allowed anywhere except the first or last character.";

    @Test
    void shortDnsNameLabelRejected() {
        assertError(validate("demo-group", withIpAddress(
                        "{\"type\":\"Public\",\"ports\":[{\"port\":8080}],\"dnsNameLabel\":\"abcd\"}")),
                400, "InvalidParameter", "properties.ipAddress.dnsNameLabel",
                invalidMessage("abcd", "properties.ipAddress.dnsNameLabel", DNS_LABEL_REASON));
    }

    @Test
    void dnsNameLabelWithUnderscoreRejected() {
        assertError(validate("demo-group", withIpAddress(
                        "{\"type\":\"Public\",\"ports\":[{\"port\":8080}],\"dnsNameLabel\":\"my_label\"}")),
                400, "InvalidParameter", "properties.ipAddress.dnsNameLabel",
                invalidMessage("my_label", "properties.ipAddress.dnsNameLabel", DNS_LABEL_REASON));
    }

    @Test
    void invalidDomainNameLabelScopeRejected() {
        assertError(validate("demo-group", withIpAddress("{\"type\":\"Public\","
                        + "\"ports\":[{\"port\":8080}],"
                        + "\"autoGeneratedDomainNameLabelScope\":\"Global\"}")),
                400, "InvalidParameter", "properties.ipAddress.autoGeneratedDomainNameLabelScope",
                invalidMessage("Global", "properties.ipAddress.autoGeneratedDomainNameLabelScope",
                        "Valid values are 'Unsecure', 'TenantReuse', 'SubscriptionReuse', "
                                + "'ResourceGroupReuse' and 'Noreuse'."));
    }

    // ── V42–V47 — echoed blocks ────────────────────────────────────────────────────────────

    @Test
    void dnsConfigWithoutNameServersRejected() {
        assertError(validate("demo-group", props(
                        "\"osType\":\"Linux\",\"dnsConfig\":{\"options\":\"ndots:2\"}", CONTAINERS)),
                400, "InvalidRequestContent", "properties.dnsConfig.nameServers",
                missingMessage("properties.dnsConfig.nameServers"));
    }

    @Test
    void diagnosticsWithoutWorkspaceKeyRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\","
                        + "\"diagnostics\":{\"logAnalytics\":{\"workspaceId\":\"w\"}}", CONTAINERS)),
                400, "InvalidRequestContent", "properties.diagnostics.logAnalytics.workspaceKey",
                missingMessage("properties.diagnostics.logAnalytics.workspaceKey"));
    }

    @Test
    void encryptionWithoutKeyVersionRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\","
                        + "\"encryptionProperties\":{\"vaultBaseUrl\":\"u\",\"keyName\":\"k\"}", CONTAINERS)),
                400, "InvalidRequestContent", "properties.encryptionProperties.keyVersion",
                missingMessage("properties.encryptionProperties.keyVersion"));
    }

    @Test
    void subnetIdWithoutIdRejected() {
        assertError(validate("demo-group", props(
                        "\"osType\":\"Linux\",\"subnetIds\":[{\"name\":\"s\"}]", CONTAINERS)),
                400, "InvalidRequestContent", "properties.subnetIds[0].id",
                missingMessage("properties.subnetIds[0].id"));
    }

    @Test
    void invalidIdentityTypeRejected() {
        String body = "{\"location\":\"eastus\",\"identity\":{\"type\":\"Managed\"},"
                + "\"properties\":{\"osType\":\"Linux\"," + CONTAINERS + "}}";
        assertError(validate("demo-group", body),
                400, "InvalidParameter", "identity.type",
                invalidMessage("Managed", "identity.type",
                        "Valid values are 'SystemAssigned', 'UserAssigned', "
                                + "'SystemAssigned, UserAssigned' and 'None'."));
    }

    @Test
    void extensionWithoutNameRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\",\"extensions\":[{}]", CONTAINERS)),
                400, "InvalidRequestContent", "properties.extensions[0].name",
                missingMessage("properties.extensions[0].name"));
    }

    // ── V48 — registry credentials ─────────────────────────────────────────────────────────

    @Test
    void registryServerWithSchemeRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\","
                        + "\"imageRegistryCredentials\":[{\"server\":\"https://r.io\"}]", CONTAINERS)),
                400, "InvalidParameter", "properties.imageRegistryCredentials[0].server",
                invalidMessage("https://r.io", "properties.imageRegistryCredentials[0].server",
                        "A registry server must be a host name without a scheme."));
    }

    @Test
    void registryCredentialWithoutServerRejected() {
        assertError(validate("demo-group", props("\"osType\":\"Linux\","
                        + "\"imageRegistryCredentials\":[{\"username\":\"u\"}]", CONTAINERS)),
                400, "InvalidRequestContent", "properties.imageRegistryCredentials[0].server",
                missingMessage("properties.imageRegistryCredentials[0].server"));
    }

    // ── V49 — security context ─────────────────────────────────────────────────────────────

    @Test
    void negativeRunAsUserRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"securityContext\":{\"runAsUser\":-1}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.securityContext.runAsUser",
                invalidMessage("-1", "properties.containers[0].properties.securityContext.runAsUser",
                        "The value must be greater than or equal to 0."));
    }

    // ── V50 — probes ───────────────────────────────────────────────────────────────────────

    @Test
    void probePortOutOfRangeRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"livenessProbe\":{\"httpGet\":{\"port\":0}}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.livenessProbe.httpGet.port",
                invalidMessage("0", "properties.containers[0].properties.livenessProbe.httpGet.port",
                        "A port number must be between 1 and 65535."));
    }

    @Test
    void probeSchemeRejected() {
        assertError(validate("demo-group", oneContainer(DEFAULT_CONTAINER_PROPS
                        + ",\"livenessProbe\":{\"httpGet\":{\"port\":80,\"scheme\":\"ftp\"}}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.livenessProbe.httpGet.scheme",
                invalidMessage("ftp",
                        "properties.containers[0].properties.livenessProbe.httpGet.scheme",
                        "Valid values are 'http' and 'https'."));
    }

    @Test
    void probeThresholdBelowOneRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"readinessProbe\":{\"periodSeconds\":0}")),
                400, "InvalidParameter",
                "properties.containers[0].properties.readinessProbe.periodSeconds",
                invalidMessage("0", "properties.containers[0].properties.readinessProbe.periodSeconds",
                        "The value must be greater than or equal to 1."));
    }

    @Test
    void probeWithoutHttpGetPortRejected() {
        assertError(validate("demo-group", oneContainer(
                        DEFAULT_CONTAINER_PROPS + ",\"livenessProbe\":{\"httpGet\":{\"path\":\"/\"}}")),
                400, "InvalidRequestContent",
                "properties.containers[0].properties.livenessProbe.httpGet.port",
                missingMessage("properties.containers[0].properties.livenessProbe.httpGet.port"));
    }

    // ── Fail-fast ordering ─────────────────────────────────────────────────────────────────

    @Test
    void firstFailingRuleWins() {
        Optional<Response> result = validate("demo-group",
                props("\"osType\":\"Plan9\",\"restartPolicy\":\"Sometimes\"", CONTAINERS));
        assertTrue(result.isPresent());
        Map<String, String> error = error(result.get());
        assertEquals("properties.osType", error.get("target"));
        assertFalse(error.get("message").contains("restartPolicy"),
                "the lower-numbered rule V5 must win over V32");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    private static final String DEFAULT_CONTAINER_PROPS =
            "\"image\":\"alpine:3.20\",\"resources\":{\"requests\":{\"cpu\":1.0,\"memoryInGB\":1.0}}";
    private static final String CONTAINERS =
            "\"containers\":[{\"name\":\"web\",\"properties\":{" + DEFAULT_CONTAINER_PROPS + "}}]";

    private Optional<Response> validate(String groupName, String body) {
        return validator.validate(groupName, parse(body));
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("test fixture is not valid JSON: " + body, e);
        }
    }

    private static String container(String name, String properties) {
        return "{\"name\":\"" + name + "\",\"properties\":{" + properties + "}}";
    }

    /** MINIMAL with {@code containers[0].properties} replaced wholesale. */
    private static String oneContainer(String containerProperties) {
        return props("\"osType\":\"Linux\"",
                "\"containers\":[" + container("web", containerProperties) + "]");
    }

    private static String props(String... propertyFragments) {
        return "{\"location\":\"eastus\",\"properties\":{" + String.join(",", propertyFragments) + "}}";
    }

    /** One container exposing port 8080, plus the supplied {@code ipAddress} block. */
    private static String withIpAddress(String ipAddress) {
        return props("\"osType\":\"Linux\"",
                "\"containers\":[" + container("web",
                        DEFAULT_CONTAINER_PROPS + ",\"ports\":[{\"port\":8080}]") + "]",
                "\"ipAddress\":" + ipAddress);
    }

    private static String withVolumes(String volumeEntries) {
        return props("\"osType\":\"Linux\"", CONTAINERS, "\"volumes\":[" + volumeEntries + "]");
    }

    private static String missingMessage(String path) {
        return "The request content was invalid and could not be deserialized: required property '"
                + path + "' is missing or of the wrong type.";
    }

    private static String invalidMessage(String value, String path, String reason) {
        return "The value '" + value + "' provided for '" + path + "' is not valid. " + reason;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> error(Response response) {
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        return (Map<String, String>) entity.get("error");
    }

    private static void assertError(Optional<Response> result, int status, String code,
                                    String target, String message) {
        assertTrue(result.isPresent(), "expected a validation failure");
        Response response = result.get();
        assertEquals(status, response.getStatus());
        Map<String, String> error = error(response);
        assertEquals(code, error.get("code"));
        assertEquals(message, error.get("message"));
        if (target == null) {
            assertNull(error.get("target"), "target must be omitted");
        } else {
            assertEquals(target, error.get("target"));
        }
    }
}
