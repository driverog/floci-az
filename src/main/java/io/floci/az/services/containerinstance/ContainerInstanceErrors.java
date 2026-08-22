package io.floci.az.services.containerinstance;

import io.floci.az.core.arm.ArmErrors;
import jakarta.ws.rs.core.Response;

/**
 * The complete Container Instances error catalog, one static factory per row.
 *
 * <p>Every body is an ARM CloudError —
 * {@code {"error":{"code":"...","message":"...","target":"..."}}} — built on
 * {@link ArmErrors#error(int, String, String, String)}, which omits {@code target} when it is
 * blank.</p>
 */
public final class ContainerInstanceErrors {

    // ── {reason} strings for InvalidParameter, one per validation rule ────────────────────

    static final String REASON_OS_TYPE = "Valid values are 'Linux' and 'Windows'.";
    static final String REASON_TOO_MANY_CONTAINERS = "A container group may contain at most 60 containers.";
    static final String REASON_CONTAINER_NAME =
            "Container names must be 1 to 63 characters long and contain only lowercase letters, "
                    + "numbers and hyphens, with a hyphen allowed anywhere except the first or last character.";
    static final String REASON_CONTAINER_NAME_UNIQUE =
            "Container names must be unique within a container group.";
    static final String REASON_GREATER_THAN_ZERO = "The value must be greater than 0.";
    static final String REASON_CPU_SUM = "The total CPU requested by a container group may not exceed 31.";
    static final String REASON_MEMORY_SUM =
            "The total memory requested by a container group may not exceed 240 GB.";
    static final String REASON_LIMIT_BELOW_REQUEST =
            "A resource limit must be greater than or equal to the corresponding resource request.";
    static final String REASON_GPU_SKU = "Valid values are 'K80', 'P100' and 'V100'.";
    static final String REASON_PORT_RANGE = "A port number must be between 1 and 65535.";
    static final String REASON_PORT_PROTOCOL = "Valid values are 'TCP' and 'UDP'.";
    static final String REASON_ENV_NAME =
            "Environment variable names must be 1 to 63 characters long and contain only letters, "
                    + "numbers and underscores, with an underscore allowed anywhere except the first or "
                    + "last character.";
    static final String REASON_ENV_EXCLUSIVE =
            "An environment variable may set either 'value' or 'secureValue', not both.";
    static final String REASON_MOUNT_PATH = "A mount path must be absolute and must not contain a colon.";
    static final String REASON_NO_SUCH_VOLUME = "No volume with that name is declared in properties.volumes.";
    static final String REASON_TOO_MANY_VOLUMES = "A container group may contain at most 20 volumes.";
    static final String REASON_VOLUME_NAME =
            "Volume names must be 5 to 63 characters long, contain only lowercase letters, numbers and "
                    + "hyphens, must not start or end with a hyphen, must not contain consecutive hyphens, "
                    + "and must be unique within a container group.";
    static final String REASON_VOLUME_KIND =
            "Exactly one of 'azureFile', 'emptyDir', 'secret' or 'gitRepo' must be specified.";
    static final String REASON_SECRET_VOLUME =
            "Secret volume values must be Base64-encoded and secret keys may contain only letters, "
                    + "numbers, dot, underscore and hyphen.";
    static final String REASON_RESTART_POLICY = "Valid values are 'Always', 'OnFailure' and 'Never'.";
    static final String REASON_SKU = "Valid values are 'Standard', 'Dedicated' and 'Confidential'.";
    static final String REASON_PRIORITY = "Valid values are 'Regular' and 'Spot'.";
    static final String REASON_IP_ADDRESS_TYPE = "Valid values are 'Public' and 'Private'.";
    static final String REASON_GROUP_PORTS =
            "A container group may expose at most 5 ports, and each port number may appear only once.";
    static final String REASON_PORT_NOT_EXPOSED =
            "The port is not exposed by any container in the container group.";
    static final String REASON_DNS_NAME_LABEL =
            "A DNS name label must be 5 to 63 characters long and contain only letters, numbers and "
                    + "hyphens, with a hyphen allowed anywhere except the first or last character.";
    static final String REASON_DOMAIN_LABEL_SCOPE =
            "Valid values are 'Unsecure', 'TenantReuse', 'SubscriptionReuse', 'ResourceGroupReuse' "
                    + "and 'Noreuse'.";
    static final String REASON_IDENTITY_TYPE =
            "Valid values are 'SystemAssigned', 'UserAssigned', 'SystemAssigned, UserAssigned' and 'None'.";
    static final String REASON_REGISTRY_SERVER = "A registry server must be a host name without a scheme.";
    static final String REASON_AT_LEAST_ZERO = "The value must be greater than or equal to 0.";
    static final String REASON_AT_LEAST_ONE = "The value must be greater than or equal to 1.";
    static final String REASON_PROBE_SCHEME = "Valid values are 'http' and 'https'.";

    private ContainerInstanceErrors() {
    }

    /** V1 — the container group name fails the ARM naming rule. */
    public static Response invalidResourceName(String group) {
        return ArmErrors.error(400, "InvalidResourceName",
                "The Resource Name '" + group + "' is invalid. Container group names must be 1 to 63 "
                        + "characters long, contain only lowercase letters, numbers and hyphens, must not "
                        + "start or end with a hyphen, and must not contain consecutive hyphens.",
                "containerGroupName");
    }

    /** V2 — the request body is not a JSON object. */
    public static Response bodyNotAnObject() {
        return ArmErrors.error(400, "InvalidRequestContent",
                "The request content was invalid and could not be deserialized: "
                        + "the request body must be a JSON object.",
                null);
    }

    /** A required property is missing or has the wrong JSON type. */
    public static Response missingProperty(String path) {
        return ArmErrors.error(400, "InvalidRequestContent",
                "The request content was invalid and could not be deserialized: required property '"
                        + path + "' is missing or of the wrong type.",
                path);
    }

    /** A present property carries an unacceptable value. */
    public static Response invalidParameter(String path, String value, String reason) {
        return ArmErrors.error(400, "InvalidParameter",
                "The value '" + value + "' provided for '" + path + "' is not valid. " + reason,
                path);
    }

    /** V29 — a {@code gitRepo} volume was requested. */
    public static Response gitRepoNotSupported(String path) {
        return ArmErrors.error(400, "NotSupported",
                "Volume type 'gitRepo' is not supported by floci-az. "
                        + "Use 'emptyDir', 'secret' or 'azureFile' instead.",
                path);
    }

    /** The container group does not exist. */
    public static Response groupNotFound(String group, String resourceGroup) {
        return ArmErrors.error(404, "ResourceNotFound",
                "The Resource 'Microsoft.ContainerInstance/containerGroups/" + group
                        + "' under resource group '" + resourceGroup + "' was not found.",
                null);
    }

    /** The group exists but has no container with that name. */
    public static Response containerNotFound(String container, String group, String resourceGroup) {
        return ArmErrors.error(404, "ResourceNotFound",
                "The container '" + container + "' was not found in container group '" + group
                        + "' under resource group '" + resourceGroup + "'.",
                "containerName");
    }

    /** No route matched the path below {@code /providers/Microsoft.ContainerInstance/}. */
    public static Response unsupportedPath(String tail) {
        return ArmErrors.error(404, "ResourceNotFound",
                "Unsupported Microsoft.ContainerInstance path: " + tail, null);
    }

    /** A matched route with an unsupported HTTP method. */
    public static Response methodNotAllowed() {
        return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed", null);
    }

    /** {@code Containers_ExecuteCommand} — permanently unimplemented. */
    public static Response execNotImplemented() {
        return ArmErrors.error(501, "NotImplemented",
                "Container exec is not implemented by floci-az: "
                        + "the emulator serves no websocket data plane.",
                null);
    }

    /** {@code Containers_Attach} — permanently unimplemented. */
    public static Response attachNotImplemented() {
        return ArmErrors.error(501, "NotImplemented",
                "Container attach is not implemented by floci-az: "
                        + "the emulator serves no websocket data plane.",
                null);
    }
}
