package io.floci.az.services.containerinstance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a {@code containerGroups} PUT body against rules V1–V50 of the resource model.
 *
 * <p>Pure: no injected collaborator performs I/O, so this class is unit-testable without a CDI
 * container. Validation is <strong>fail-fast in numeric rule order</strong> — the first rule
 * that fails produces the response — which makes the error for a body violating several rules
 * deterministic.</p>
 */
@ApplicationScoped
public class ContainerGroupValidator {

    private static final Pattern GROUP_NAME =
            Pattern.compile("^[a-z0-9]([a-z0-9]|-(?!-))*[a-z0-9]$|^[a-z0-9]$");
    private static final Pattern CONTAINER_NAME =
            Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$");
    private static final Pattern DNS_LABEL =
            Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$");
    private static final Pattern VOLUME_NAME =
            Pattern.compile("^[a-z0-9]([a-z0-9]|-(?!-))*[a-z0-9]$");
    private static final Pattern ENV_NAME =
            Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9_]*[A-Za-z0-9])?$");
    private static final Pattern SECRET_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,255}$");

    private static final Set<String> OS_TYPES = Set.of("Linux", "Windows");
    private static final Set<String> RESTART_POLICIES = Set.of("Always", "OnFailure", "Never");
    private static final Set<String> SKUS = Set.of("Standard", "Dedicated", "Confidential");
    private static final Set<String> PRIORITIES = Set.of("Regular", "Spot");
    private static final Set<String> IP_TYPES = Set.of("Public", "Private");
    private static final Set<String> PROTOCOLS = Set.of("TCP", "UDP");
    private static final Set<String> GPU_SKUS = Set.of("K80", "P100", "V100");
    private static final Set<String> DOMAIN_LABEL_SCOPES =
            Set.of("Unsecure", "TenantReuse", "SubscriptionReuse", "ResourceGroupReuse", "Noreuse");
    private static final Set<String> IDENTITY_TYPES =
            Set.of("SystemAssigned", "UserAssigned", "SystemAssigned, UserAssigned", "None");
    private static final Set<String> PROBE_SCHEMES = Set.of("http", "https");

    private static final int MAX_CONTAINERS = 60;
    private static final int MAX_VOLUMES = 20;
    private static final int MAX_GROUP_PORTS = 5;
    private static final double MAX_GROUP_CPU = 31.0;
    private static final double MAX_GROUP_MEMORY_GB = 240.0;

    /** One entry of {@code properties.containers[]} or {@code properties.initContainers[]}. */
    private record ContainerEntry(String path, JsonNode node, boolean init) {
    }

    /**
     * Applies V1–V50 in order.
     *
     * @param groupName the {@code {containerGroupName}} path segment
     * @param body      the parsed request body, or {@code null} when it did not parse
     * @return the error response for the first failing rule, or empty when the body is valid
     */
    public Optional<Response> validate(String groupName, JsonNode body) {
        // V1 — group name
        if (groupName == null || groupName.isEmpty() || groupName.length() > 63
                || !GROUP_NAME.matcher(groupName).matches()) {
            return Optional.of(ContainerInstanceErrors.invalidResourceName(String.valueOf(groupName)));
        }
        // V2 — body is a JSON object
        if (body == null || !body.isObject()) {
            return Optional.of(ContainerInstanceErrors.bodyNotAnObject());
        }
        // V3 — properties present and an object
        JsonNode props = body.get("properties");
        if (props == null || !props.isObject()) {
            return Optional.of(ContainerInstanceErrors.missingProperty("properties"));
        }
        // V4 / V5 — osType
        JsonNode osType = props.get("osType");
        if (osType == null || !osType.isTextual() || osType.asText().isBlank()) {
            return Optional.of(ContainerInstanceErrors.missingProperty("properties.osType"));
        }
        if (!OS_TYPES.contains(osType.asText())) {
            return Optional.of(ContainerInstanceErrors.invalidParameter(
                    "properties.osType", osType.asText(), ContainerInstanceErrors.REASON_OS_TYPE));
        }
        // V6 — containers present, an array, non-empty
        JsonNode containers = props.get("containers");
        if (containers == null || !containers.isArray() || containers.isEmpty()) {
            return Optional.of(ContainerInstanceErrors.missingProperty("properties.containers"));
        }
        JsonNode initContainers = props.get("initContainers");
        List<ContainerEntry> all = allContainers(containers, initContainers);

        // V7 — at most 60 containers, counting init containers
        if (all.size() > MAX_CONTAINERS) {
            return Optional.of(ContainerInstanceErrors.invalidParameter(
                    "properties.containers", String.valueOf(all.size()),
                    ContainerInstanceErrors.REASON_TOO_MANY_CONTAINERS));
        }

        Optional<Response> failure =
                checkContainerNames(all)                              // V8, V9, V10
                        .or(() -> checkContainerProperties(all))      // V11, V12
                        .or(() -> checkResources(containers))         // V13–V18
                        .or(() -> checkContainerPorts(containers))    // V19, V20
                        .or(() -> checkEnvironmentVariables(all))     // V21, V22
                        .or(() -> checkVolumeMounts(all, props))      // V23, V24, V25
                        .or(() -> checkVolumes(props))                // V26–V31
                        .or(() -> checkGroupEnums(props))             // V32, V33, V34
                        .or(() -> checkIpAddress(props, containers))  // V35–V41
                        .or(() -> checkEchoedBlocks(body, props))     // V42–V47
                        .or(() -> checkRegistryCredentials(props))    // V48
                        .or(() -> checkSecurityContexts(all))         // V49
                        .or(() -> checkProbes(containers));           // V50
        return failure;
    }

    // ── V8, V9, V10 — container names ─────────────────────────────────────────────────────

    private Optional<Response> checkContainerNames(List<ContainerEntry> all) {
        for (ContainerEntry e : all) {
            JsonNode name = e.node().get("name");
            if (name == null || !name.isTextual() || name.asText().isEmpty()) {
                return Optional.of(ContainerInstanceErrors.missingProperty(e.path() + ".name"));
            }
        }
        for (ContainerEntry e : all) {
            String name = e.node().get("name").asText();
            if (name.length() > 63 || !CONTAINER_NAME.matcher(name).matches()) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(
                        e.path() + ".name", name, ContainerInstanceErrors.REASON_CONTAINER_NAME));
            }
        }
        Set<String> seen = new HashSet<>();
        for (ContainerEntry e : all) {
            String name = e.node().get("name").asText();
            if (!seen.add(name)) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(
                        e.path() + ".name", name, ContainerInstanceErrors.REASON_CONTAINER_NAME_UNIQUE));
            }
        }
        return Optional.empty();
    }

    // ── V11, V12 — container properties and image ─────────────────────────────────────────

    private Optional<Response> checkContainerProperties(List<ContainerEntry> all) {
        for (ContainerEntry e : all) {
            JsonNode cp = e.node().get("properties");
            if (cp == null || !cp.isObject()) {
                return Optional.of(ContainerInstanceErrors.missingProperty(e.path() + ".properties"));
            }
        }
        for (ContainerEntry e : all) {
            if (e.init()) {
                // An init container's image is optional; one without an image is skipped at runtime.
                continue;
            }
            JsonNode image = e.node().get("properties").get("image");
            if (image == null || !image.isTextual() || image.asText().isBlank()) {
                return Optional.of(
                        ContainerInstanceErrors.missingProperty(e.path() + ".properties.image"));
            }
        }
        return Optional.empty();
    }

    // ── V13–V18 — resources ───────────────────────────────────────────────────────────────

    private Optional<Response> checkResources(JsonNode containers) {
        // V13 — requests present with both cpu and memoryInGB
        for (int i = 0; i < containers.size(); i++) {
            String path = "properties.containers[" + i + "].properties.resources.requests";
            JsonNode requests = containers.get(i).path("properties").path("resources").get("requests");
            if (requests == null || !requests.isObject()
                    || !requests.path("cpu").isNumber() || !requests.path("memoryInGB").isNumber()) {
                return Optional.of(ContainerInstanceErrors.missingProperty(path));
            }
        }
        // V14 — cpu and memoryInGB greater than 0
        for (int i = 0; i < containers.size(); i++) {
            JsonNode requests = containers.get(i).path("properties").path("resources").path("requests");
            String base = "properties.containers[" + i + "].properties.resources.requests";
            if (requests.path("cpu").asDouble() <= 0) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(base + ".cpu",
                        decimal(requests.path("cpu").asDouble()),
                        ContainerInstanceErrors.REASON_GREATER_THAN_ZERO));
            }
            if (requests.path("memoryInGB").asDouble() <= 0) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(base + ".memoryInGB",
                        decimal(requests.path("memoryInGB").asDouble()),
                        ContainerInstanceErrors.REASON_GREATER_THAN_ZERO));
            }
        }
        // V15 / V16 — group sums
        double cpuSum = 0;
        double memorySum = 0;
        for (JsonNode c : containers) {
            JsonNode requests = c.path("properties").path("resources").path("requests");
            cpuSum += requests.path("cpu").asDouble();
            memorySum += requests.path("memoryInGB").asDouble();
        }
        if (cpuSum > MAX_GROUP_CPU) {
            return Optional.of(ContainerInstanceErrors.invalidParameter("properties.containers",
                    decimal(cpuSum), ContainerInstanceErrors.REASON_CPU_SUM));
        }
        if (memorySum > MAX_GROUP_MEMORY_GB) {
            return Optional.of(ContainerInstanceErrors.invalidParameter("properties.containers",
                    decimal(memorySum), ContainerInstanceErrors.REASON_MEMORY_SUM));
        }
        // V17 — limits at least the matching request
        for (int i = 0; i < containers.size(); i++) {
            JsonNode resources = containers.get(i).path("properties").path("resources");
            JsonNode limits = resources.get("limits");
            if (limits == null || !limits.isObject()) {
                continue;
            }
            String base = "properties.containers[" + i + "].properties.resources.limits";
            JsonNode requests = resources.path("requests");
            for (String field : List.of("cpu", "memoryInGB")) {
                JsonNode limit = limits.get(field);
                if (limit == null || !limit.isNumber()) {
                    continue;
                }
                if (limit.asDouble() <= 0 || limit.asDouble() < requests.path(field).asDouble()) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(base + "." + field,
                            decimal(limit.asDouble()),
                            ContainerInstanceErrors.REASON_LIMIT_BELOW_REQUEST));
                }
            }
        }
        // V18 — gpu
        for (int i = 0; i < containers.size(); i++) {
            JsonNode resources = containers.get(i).path("properties").path("resources");
            for (String bucket : List.of("requests", "limits")) {
                JsonNode gpu = resources.path(bucket).get("gpu");
                if (gpu == null || !gpu.isObject()) {
                    continue;
                }
                String base = "properties.containers[" + i + "].properties.resources." + bucket + ".gpu";
                Optional<Response> gpuFailure = checkGpu(gpu, base);
                if (gpuFailure.isPresent()) {
                    return gpuFailure;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Response> checkGpu(JsonNode gpu, String base) {
        JsonNode count = gpu.get("count");
        if (count == null || !count.isNumber()) {
            return Optional.of(ContainerInstanceErrors.missingProperty(base + ".count"));
        }
        JsonNode sku = gpu.get("sku");
        if (sku == null || !sku.isTextual()) {
            return Optional.of(ContainerInstanceErrors.missingProperty(base + ".sku"));
        }
        if (count.asInt() < 1) {
            return Optional.of(ContainerInstanceErrors.invalidParameter(base + ".count",
                    String.valueOf(count.asInt()), ContainerInstanceErrors.REASON_AT_LEAST_ONE));
        }
        if (!GPU_SKUS.contains(sku.asText())) {
            return Optional.of(ContainerInstanceErrors.invalidParameter(base + ".sku", sku.asText(),
                    ContainerInstanceErrors.REASON_GPU_SKU));
        }
        return Optional.empty();
    }

    // ── V19, V20 — container ports ────────────────────────────────────────────────────────

    private Optional<Response> checkContainerPorts(JsonNode containers) {
        for (int i = 0; i < containers.size(); i++) {
            JsonNode ports = containers.get(i).path("properties").get("ports");
            if (ports == null || !ports.isArray()) {
                continue;
            }
            for (int j = 0; j < ports.size(); j++) {
                String path = "properties.containers[" + i + "].properties.ports[" + j + "].port";
                Optional<Response> f = checkPortValue(ports.get(j).get("port"), path);
                if (f.isPresent()) {
                    return f;
                }
            }
        }
        for (int i = 0; i < containers.size(); i++) {
            JsonNode ports = containers.get(i).path("properties").get("ports");
            if (ports == null || !ports.isArray()) {
                continue;
            }
            for (int j = 0; j < ports.size(); j++) {
                JsonNode protocol = ports.get(j).get("protocol");
                if (protocol != null && !protocol.isNull() && !PROTOCOLS.contains(protocol.asText())) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(
                            "properties.containers[" + i + "].properties.ports[" + j + "].protocol",
                            protocol.asText(), ContainerInstanceErrors.REASON_PORT_PROTOCOL));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Response> checkPortValue(JsonNode port, String path) {
        if (port == null || !port.isNumber()) {
            return Optional.of(ContainerInstanceErrors.missingProperty(path));
        }
        if (port.asInt() < 1 || port.asInt() > 65535) {
            return Optional.of(ContainerInstanceErrors.invalidParameter(path,
                    String.valueOf(port.asInt()), ContainerInstanceErrors.REASON_PORT_RANGE));
        }
        return Optional.empty();
    }

    // ── V21, V22 — environment variables ──────────────────────────────────────────────────

    private Optional<Response> checkEnvironmentVariables(List<ContainerEntry> all) {
        for (ContainerEntry e : all) {
            JsonNode env = e.node().path("properties").get("environmentVariables");
            if (env == null || !env.isArray()) {
                continue;
            }
            for (int j = 0; j < env.size(); j++) {
                String path = e.path() + ".properties.environmentVariables[" + j + "].name";
                JsonNode name = env.get(j).get("name");
                if (name == null || !name.isTextual() || name.asText().isEmpty()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(path));
                }
                if (name.asText().length() > 63 || !ENV_NAME.matcher(name.asText()).matches()) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(path, name.asText(),
                            ContainerInstanceErrors.REASON_ENV_NAME));
                }
            }
        }
        for (ContainerEntry e : all) {
            JsonNode env = e.node().path("properties").get("environmentVariables");
            if (env == null || !env.isArray()) {
                continue;
            }
            for (int j = 0; j < env.size(); j++) {
                JsonNode entry = env.get(j);
                if (entry.hasNonNull("value") && entry.hasNonNull("secureValue")) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(
                            e.path() + ".properties.environmentVariables[" + j + "]",
                            entry.get("name").asText(), ContainerInstanceErrors.REASON_ENV_EXCLUSIVE));
                }
            }
        }
        return Optional.empty();
    }

    // ── V23, V24, V25 — volume mounts ─────────────────────────────────────────────────────

    private Optional<Response> checkVolumeMounts(List<ContainerEntry> all, JsonNode props) {
        for (ContainerEntry e : all) {
            JsonNode mounts = e.node().path("properties").get("volumeMounts");
            if (mounts == null || !mounts.isArray()) {
                continue;
            }
            for (int j = 0; j < mounts.size(); j++) {
                String base = e.path() + ".properties.volumeMounts[" + j + "]";
                JsonNode name = mounts.get(j).get("name");
                if (name == null || !name.isTextual() || name.asText().isEmpty()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(base + ".name"));
                }
                JsonNode mountPath = mounts.get(j).get("mountPath");
                if (mountPath == null || !mountPath.isTextual() || mountPath.asText().isEmpty()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(base + ".mountPath"));
                }
            }
        }
        for (ContainerEntry e : all) {
            JsonNode mounts = e.node().path("properties").get("volumeMounts");
            if (mounts == null || !mounts.isArray()) {
                continue;
            }
            for (int j = 0; j < mounts.size(); j++) {
                String mountPath = mounts.get(j).get("mountPath").asText();
                if (!mountPath.startsWith("/") || mountPath.contains(":")) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(
                            e.path() + ".properties.volumeMounts[" + j + "].mountPath", mountPath,
                            ContainerInstanceErrors.REASON_MOUNT_PATH));
                }
            }
        }
        Set<String> declared = declaredVolumeNames(props);
        for (ContainerEntry e : all) {
            JsonNode mounts = e.node().path("properties").get("volumeMounts");
            if (mounts == null || !mounts.isArray()) {
                continue;
            }
            for (int j = 0; j < mounts.size(); j++) {
                String name = mounts.get(j).get("name").asText();
                if (!declared.contains(name)) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(
                            e.path() + ".properties.volumeMounts[" + j + "].name", name,
                            ContainerInstanceErrors.REASON_NO_SUCH_VOLUME));
                }
            }
        }
        return Optional.empty();
    }

    // ── V26–V31 — volumes ─────────────────────────────────────────────────────────────────

    private Optional<Response> checkVolumes(JsonNode props) {
        JsonNode volumes = props.get("volumes");
        if (volumes == null || !volumes.isArray()) {
            return Optional.empty();
        }
        // V26
        if (volumes.size() > MAX_VOLUMES) {
            return Optional.of(ContainerInstanceErrors.invalidParameter("properties.volumes",
                    String.valueOf(volumes.size()), ContainerInstanceErrors.REASON_TOO_MANY_VOLUMES));
        }
        // V27
        for (int i = 0; i < volumes.size(); i++) {
            JsonNode name = volumes.get(i).get("name");
            if (name == null || !name.isTextual() || name.asText().isEmpty()) {
                return Optional.of(
                        ContainerInstanceErrors.missingProperty("properties.volumes[" + i + "].name"));
            }
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < volumes.size(); i++) {
            String name = volumes.get(i).get("name").asText();
            boolean valid = name.length() >= 5 && name.length() <= 63
                    && VOLUME_NAME.matcher(name).matches() && seen.add(name);
            if (!valid) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(
                        "properties.volumes[" + i + "].name", name,
                        ContainerInstanceErrors.REASON_VOLUME_NAME));
            }
        }
        // V28
        for (int i = 0; i < volumes.size(); i++) {
            JsonNode v = volumes.get(i);
            int kinds = 0;
            for (String kind : List.of("azureFile", "emptyDir", "secret", "gitRepo")) {
                if (v.hasNonNull(kind)) {
                    kinds++;
                }
            }
            if (kinds != 1) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(
                        "properties.volumes[" + i + "]", v.get("name").asText(),
                        ContainerInstanceErrors.REASON_VOLUME_KIND));
            }
        }
        // V29
        for (int i = 0; i < volumes.size(); i++) {
            if (volumes.get(i).hasNonNull("gitRepo")) {
                return Optional.of(ContainerInstanceErrors.gitRepoNotSupported(
                        "properties.volumes[" + i + "].gitRepo"));
            }
        }
        // V30
        for (int i = 0; i < volumes.size(); i++) {
            JsonNode secret = volumes.get(i).get("secret");
            if (secret == null || !secret.isObject()) {
                continue;
            }
            String path = "properties.volumes[" + i + "].secret";
            var fields = secret.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!SECRET_KEY.matcher(field.getKey()).matches() || !isBase64(field.getValue())) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(path, field.getKey(),
                            ContainerInstanceErrors.REASON_SECRET_VOLUME));
                }
            }
        }
        // V31
        for (int i = 0; i < volumes.size(); i++) {
            JsonNode azureFile = volumes.get(i).get("azureFile");
            if (azureFile == null || !azureFile.isObject()) {
                continue;
            }
            String base = "properties.volumes[" + i + "].azureFile";
            for (String field : List.of("shareName", "storageAccountName")) {
                JsonNode value = azureFile.get(field);
                if (value == null || !value.isTextual() || value.asText().isBlank()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(base + "." + field));
                }
            }
        }
        return Optional.empty();
    }

    // ── V32, V33, V34 — group-level enums ─────────────────────────────────────────────────

    private Optional<Response> checkGroupEnums(JsonNode props) {
        return enumCheck(props.get("restartPolicy"), "properties.restartPolicy", RESTART_POLICIES,
                ContainerInstanceErrors.REASON_RESTART_POLICY)
                .or(() -> enumCheck(props.get("sku"), "properties.sku", SKUS,
                        ContainerInstanceErrors.REASON_SKU))
                .or(() -> enumCheck(props.get("priority"), "properties.priority", PRIORITIES,
                        ContainerInstanceErrors.REASON_PRIORITY));
    }

    // ── V35–V41 — ipAddress ───────────────────────────────────────────────────────────────

    private Optional<Response> checkIpAddress(JsonNode props, JsonNode containers) {
        JsonNode ip = props.get("ipAddress");
        if (ip == null || !ip.isObject()) {
            return Optional.empty();
        }
        // V35
        JsonNode type = ip.get("type");
        if (type == null || !type.isTextual() || type.asText().isEmpty()) {
            return Optional.of(ContainerInstanceErrors.missingProperty("properties.ipAddress.type"));
        }
        if (!IP_TYPES.contains(type.asText())) {
            return Optional.of(ContainerInstanceErrors.invalidParameter("properties.ipAddress.type",
                    type.asText(), ContainerInstanceErrors.REASON_IP_ADDRESS_TYPE));
        }
        // V36
        JsonNode ports = ip.get("ports");
        if (ports == null || !ports.isArray() || ports.isEmpty()) {
            return Optional.of(ContainerInstanceErrors.missingProperty("properties.ipAddress.ports"));
        }
        // V37
        Set<Integer> distinct = new LinkedHashSet<>();
        for (JsonNode p : ports) {
            distinct.add(p.path("port").asInt());
        }
        if (ports.size() > MAX_GROUP_PORTS || distinct.size() != ports.size()) {
            return Optional.of(ContainerInstanceErrors.invalidParameter("properties.ipAddress.ports",
                    String.valueOf(ports.size()), ContainerInstanceErrors.REASON_GROUP_PORTS));
        }
        // V38
        for (int i = 0; i < ports.size(); i++) {
            Optional<Response> f = checkPortValue(ports.get(i).get("port"),
                    "properties.ipAddress.ports[" + i + "].port");
            if (f.isPresent()) {
                return f;
            }
        }
        // V39
        Set<Integer> exposed = new HashSet<>();
        for (JsonNode c : containers) {
            JsonNode cps = c.path("properties").get("ports");
            if (cps != null && cps.isArray()) {
                for (JsonNode cp : cps) {
                    exposed.add(cp.path("port").asInt());
                }
            }
        }
        for (int i = 0; i < ports.size(); i++) {
            int port = ports.get(i).path("port").asInt();
            if (!exposed.contains(port)) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(
                        "properties.ipAddress.ports[" + i + "].port", String.valueOf(port),
                        ContainerInstanceErrors.REASON_PORT_NOT_EXPOSED));
            }
        }
        // V40
        JsonNode label = ip.get("dnsNameLabel");
        if (label != null && !label.isNull()) {
            String value = label.asText();
            if (value.length() < 5 || value.length() > 63 || !DNS_LABEL.matcher(value).matches()) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(
                        "properties.ipAddress.dnsNameLabel", value,
                        ContainerInstanceErrors.REASON_DNS_NAME_LABEL));
            }
        }
        // V41
        return enumCheck(ip.get("autoGeneratedDomainNameLabelScope"),
                "properties.ipAddress.autoGeneratedDomainNameLabelScope", DOMAIN_LABEL_SCOPES,
                ContainerInstanceErrors.REASON_DOMAIN_LABEL_SCOPE);
    }

    // ── V42–V47 — accepted-and-echoed blocks ──────────────────────────────────────────────

    private Optional<Response> checkEchoedBlocks(JsonNode body, JsonNode props) {
        // V42
        JsonNode dnsConfig = props.get("dnsConfig");
        if (dnsConfig != null && dnsConfig.isObject()) {
            JsonNode nameServers = dnsConfig.get("nameServers");
            if (nameServers == null || !nameServers.isArray() || nameServers.isEmpty()) {
                return Optional.of(
                        ContainerInstanceErrors.missingProperty("properties.dnsConfig.nameServers"));
            }
        }
        // V43
        JsonNode diagnostics = props.get("diagnostics");
        if (diagnostics != null && diagnostics.isObject()) {
            JsonNode logAnalytics = diagnostics.get("logAnalytics");
            if (logAnalytics == null || !logAnalytics.isObject()) {
                return Optional.of(ContainerInstanceErrors.missingProperty(
                        "properties.diagnostics.logAnalytics.workspaceId"));
            }
            for (String field : List.of("workspaceId", "workspaceKey")) {
                JsonNode value = logAnalytics.get(field);
                if (value == null || !value.isTextual() || value.asText().isBlank()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(
                            "properties.diagnostics.logAnalytics." + field));
                }
            }
        }
        // V44
        JsonNode encryption = props.get("encryptionProperties");
        if (encryption != null && encryption.isObject()) {
            for (String field : List.of("vaultBaseUrl", "keyName", "keyVersion")) {
                JsonNode value = encryption.get(field);
                if (value == null || !value.isTextual() || value.asText().isBlank()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(
                            "properties.encryptionProperties." + field));
                }
            }
        }
        // V45
        JsonNode subnetIds = props.get("subnetIds");
        if (subnetIds != null && subnetIds.isArray()) {
            for (int i = 0; i < subnetIds.size(); i++) {
                JsonNode id = subnetIds.get(i).get("id");
                if (id == null || !id.isTextual() || id.asText().isBlank()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(
                            "properties.subnetIds[" + i + "].id"));
                }
            }
        }
        // V46
        JsonNode identity = body.get("identity");
        if (identity != null && identity.isObject()) {
            Optional<Response> f = enumCheck(identity.get("type"), "identity.type", IDENTITY_TYPES,
                    ContainerInstanceErrors.REASON_IDENTITY_TYPE);
            if (f.isPresent()) {
                return f;
            }
        }
        // V47
        JsonNode extensions = props.get("extensions");
        if (extensions != null && extensions.isArray()) {
            for (int i = 0; i < extensions.size(); i++) {
                JsonNode name = extensions.get(i).get("name");
                if (name == null || !name.isTextual() || name.asText().isBlank()) {
                    return Optional.of(ContainerInstanceErrors.missingProperty(
                            "properties.extensions[" + i + "].name"));
                }
            }
        }
        return Optional.empty();
    }

    // ── V48 — registry credentials ────────────────────────────────────────────────────────

    private Optional<Response> checkRegistryCredentials(JsonNode props) {
        JsonNode credentials = props.get("imageRegistryCredentials");
        if (credentials == null || !credentials.isArray()) {
            return Optional.empty();
        }
        for (int i = 0; i < credentials.size(); i++) {
            String path = "properties.imageRegistryCredentials[" + i + "].server";
            JsonNode server = credentials.get(i).get("server");
            if (server == null || !server.isTextual() || server.asText().isBlank()) {
                return Optional.of(ContainerInstanceErrors.missingProperty(path));
            }
            if (server.asText().contains("://")) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(path, server.asText(),
                        ContainerInstanceErrors.REASON_REGISTRY_SERVER));
            }
        }
        return Optional.empty();
    }

    // ── V49 — security contexts ───────────────────────────────────────────────────────────

    private Optional<Response> checkSecurityContexts(List<ContainerEntry> all) {
        for (ContainerEntry e : all) {
            JsonNode context = e.node().path("properties").get("securityContext");
            if (context == null || !context.isObject()) {
                continue;
            }
            for (String field : List.of("runAsUser", "runAsGroup")) {
                JsonNode value = context.get(field);
                if (value != null && value.isNumber() && value.asInt() < 0) {
                    return Optional.of(ContainerInstanceErrors.invalidParameter(
                            e.path() + ".properties.securityContext." + field,
                            String.valueOf(value.asInt()),
                            ContainerInstanceErrors.REASON_AT_LEAST_ZERO));
                }
            }
        }
        return Optional.empty();
    }

    // ── V50 — probes ──────────────────────────────────────────────────────────────────────

    private Optional<Response> checkProbes(JsonNode containers) {
        for (int i = 0; i < containers.size(); i++) {
            for (String probeName : List.of("livenessProbe", "readinessProbe")) {
                JsonNode probe = containers.get(i).path("properties").get(probeName);
                if (probe == null || !probe.isObject()) {
                    continue;
                }
                String base = "properties.containers[" + i + "].properties." + probeName;
                Optional<Response> f = checkProbe(probe, base);
                if (f.isPresent()) {
                    return f;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Response> checkProbe(JsonNode probe, String base) {
        JsonNode httpGet = probe.get("httpGet");
        if (httpGet != null && httpGet.isObject()) {
            JsonNode port = httpGet.get("port");
            if (port == null || !port.isNumber()) {
                return Optional.of(ContainerInstanceErrors.missingProperty(base + ".httpGet.port"));
            }
            if (port.asInt() < 1 || port.asInt() > 65535) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(base + ".httpGet.port",
                        String.valueOf(port.asInt()), ContainerInstanceErrors.REASON_PORT_RANGE));
            }
            JsonNode scheme = httpGet.get("scheme");
            if (scheme != null && !scheme.isNull() && !PROBE_SCHEMES.contains(scheme.asText())) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(base + ".httpGet.scheme",
                        scheme.asText(), ContainerInstanceErrors.REASON_PROBE_SCHEME));
            }
        }
        JsonNode initialDelay = probe.get("initialDelaySeconds");
        if (initialDelay != null && initialDelay.isNumber() && initialDelay.asInt() < 0) {
            return Optional.of(ContainerInstanceErrors.invalidParameter(base + ".initialDelaySeconds",
                    String.valueOf(initialDelay.asInt()), ContainerInstanceErrors.REASON_AT_LEAST_ZERO));
        }
        for (String field : List.of("periodSeconds", "failureThreshold", "successThreshold",
                "timeoutSeconds")) {
            JsonNode value = probe.get(field);
            if (value != null && value.isNumber() && value.asInt() < 1) {
                return Optional.of(ContainerInstanceErrors.invalidParameter(base + "." + field,
                        String.valueOf(value.asInt()), ContainerInstanceErrors.REASON_AT_LEAST_ONE));
            }
        }
        return Optional.empty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private static List<ContainerEntry> allContainers(JsonNode containers, JsonNode initContainers) {
        List<ContainerEntry> all = new ArrayList<>();
        for (int i = 0; i < containers.size(); i++) {
            all.add(new ContainerEntry("properties.containers[" + i + "]", containers.get(i), false));
        }
        if (initContainers != null && initContainers.isArray()) {
            for (int i = 0; i < initContainers.size(); i++) {
                all.add(new ContainerEntry("properties.initContainers[" + i + "]",
                        initContainers.get(i), true));
            }
        }
        return all;
    }

    private static Set<String> declaredVolumeNames(JsonNode props) {
        Set<String> names = new HashSet<>();
        JsonNode volumes = props.get("volumes");
        if (volumes != null && volumes.isArray()) {
            for (JsonNode v : volumes) {
                JsonNode name = v.get("name");
                if (name != null && name.isTextual()) {
                    names.add(name.asText());
                }
            }
        }
        return names;
    }

    private static Optional<Response> enumCheck(JsonNode node, String path, Set<String> allowed,
                                                String reason) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        String value = node.asText();
        if (!allowed.contains(value)) {
            return Optional.of(ContainerInstanceErrors.invalidParameter(path, value, reason));
        }
        return Optional.empty();
    }

    private static boolean isBase64(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(node.asText());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Renders a resource quantity the way the error catalog's {@code {value}} shows it. */
    private static String decimal(double value) {
        return String.valueOf(value);
    }
}
