package io.floci.az.services.containerinstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerGroup;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerRecord;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.EventRecord;
import io.floci.az.services.containerinstance.ContainerInstanceModels.GroupStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.RestartPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP handler for Azure Container Instances
 * ({@code Microsoft.ContainerInstance/containerGroups}) management-plane requests.
 *
 * <h2>Routing</h2>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerInstance/containerGroups
 *   GET    .../resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups
 *   PUT    .../containerGroups/{name}
 *   GET    .../containerGroups/{name}            (?$expand=instanceView)
 *   PATCH  .../containerGroups/{name}            tags only
 *   DELETE .../containerGroups/{name}
 *   POST   .../containerGroups/{name}/{start|stop|restart}
 *   GET    .../containerGroups/{name}/containers/{container}/logs
 *   GET    .../containerGroups/{name}/outboundNetworkDependenciesEndpoints
 *   POST   .../containerGroups/{name}/containers/{container}/{exec|attach}   → 501
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerInstance/locations/{loc}/{usages|capabilities|cachedImages}
 * </pre>
 *
 * <p>There is deliberately <strong>no</strong> {@code locations/{loc}/operations/{opId}} route:
 * every operation completes synchronously and no {@code Azure-AsyncOperation} header is ever
 * emitted, so such a route would be unreachable code.</p>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.container-instance.mocked=true}, no Docker call is made:
 * groups are provisioned {@code Succeeded} with every container {@code Running}, actions are
 * pure state transitions, and logs are empty. This keeps the service usable without a Docker
 * socket.</p>
 */
@ApplicationScoped
public class ContainerInstanceHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(ContainerInstanceHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String CI_MARKER = "/providers/Microsoft.ContainerInstance/";
    private static final String RESOURCE_TYPE = "Microsoft.ContainerInstance/containerGroups";

    /** Azure's changeable per-region limits, reported by {@code Location_ListUsage}. */
    private static final int USAGE_LIMIT = 100;

    private final EmulatorConfig config;
    private final ContainerGroupValidator validator;
    private final StorageBackend<String, StoredObject> storage;

    @Inject
    public ContainerInstanceHandler(EmulatorConfig config,
                                    ContainerGroupValidator validator,
                                    StorageFactory storageFactory) {
        this.config = config;
        this.validator = validator;
        this.storage = storageFactory.create("containerinstance");
    }

    @Override
    public String getServiceType() {
        return "containerinstance";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().containerInstance().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.ContainerInstance")
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest req) {
        return "containerinstance".equals(req.serviceType());
    }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath() == null ? "" : req.resourcePath();
        String method = req.method().toUpperCase(Locale.ROOT);
        String tail = tail(fullPath);
        String sub = ArmPaths.segmentAfter(fullPath, "subscriptions", "unknown");
        String rg = ArmPaths.segmentAfter(fullPath, "resourcegroups", "unknown");

        LOG.debugf("ContainerInstanceHandler: %s %s (tail=%s)", method, fullPath, tail);

        if (tail.matches("containerGroups/[^/]+/containers/[^/]+/logs(?:[?].*)?")) {
            return "GET".equals(method)
                    ? handleLogs(sub, rg, segment(tail, 1), segment(tail, 3), req)
                    : ContainerInstanceErrors.methodNotAllowed();
        }
        if (tail.matches("containerGroups/[^/]+/containers/[^/]+/exec(?:[?].*)?")) {
            return "POST".equals(method)
                    ? ContainerInstanceErrors.execNotImplemented()
                    : ContainerInstanceErrors.methodNotAllowed();
        }
        if (tail.matches("containerGroups/[^/]+/containers/[^/]+/attach(?:[?].*)?")) {
            return "POST".equals(method)
                    ? ContainerInstanceErrors.attachNotImplemented()
                    : ContainerInstanceErrors.methodNotAllowed();
        }
        if (tail.matches("containerGroups/[^/]+/outboundNetworkDependenciesEndpoints(?:[?].*)?")) {
            return "GET".equals(method)
                    ? handleOutboundNetworkDependencies(sub, rg, segment(tail, 1))
                    : ContainerInstanceErrors.methodNotAllowed();
        }
        if (tail.matches("containerGroups/[^/]+/(start|stop|restart)(?:[?].*)?")) {
            return "POST".equals(method)
                    ? handleAction(sub, rg, segment(tail, 1), segment(tail, 2))
                    : ContainerInstanceErrors.methodNotAllowed();
        }
        if (tail.matches("containerGroups/[^/]+(?:[?].*)?")) {
            String name = segment(tail, 1);
            return switch (method) {
                case "GET"    -> handleGet(sub, rg, name, expandsInstanceView(req));
                case "PUT"    -> handleCreateOrUpdate(sub, rg, name, req);
                case "PATCH"  -> handleUpdateTags(sub, rg, name, req);
                case "DELETE" -> handleDelete(sub, rg, name);
                default       -> ContainerInstanceErrors.methodNotAllowed();
            };
        }
        if (tail.matches("containerGroups(?:[?].*)?")) {
            if (!"GET".equals(method)) {
                return ContainerInstanceErrors.methodNotAllowed();
            }
            return isResourceGroupScoped(fullPath)
                    ? handleListByResourceGroup(sub, rg)
                    : handleListBySubscription(sub);
        }
        if (tail.matches("locations/[^/]+/(usages|capabilities|cachedImages)(?:[?].*)?")) {
            if (!"GET".equals(method)) {
                return ContainerInstanceErrors.methodNotAllowed();
            }
            String location = segment(tail, 1);
            return switch (segment(tail, 2)) {
                case "usages"       -> handleListUsage(sub, location);
                case "capabilities" -> handleListCapabilities(location);
                default             -> handleListCachedImages();
            };
        }
        return ContainerInstanceErrors.unsupportedPath(tail);
    }

    // ── CRUD ───────────────────────────────────────────────────────────────────────────────

    private Response handleCreateOrUpdate(String sub, String rg, String name, AzureRequest req) {
        JsonNode body = readBody(req.bodyStream());
        Optional<Response> invalid = validator.validate(name, body);
        if (invalid.isPresent()) {
            return invalid.get();
        }

        String key = storageKey(sub, rg, name);
        Optional<ContainerGroup> existing = read(key);
        boolean isNew = existing.isEmpty();

        ContainerGroup group = new ContainerGroup();
        group.setSubscriptionId(sub);
        group.setResourceGroup(rg);
        group.setName(name);
        group.setGroupId(existing.map(ContainerGroup::getGroupId).orElseGet(ContainerInstanceHandler::newGroupId));
        group.setTimeCreated(existing.map(ContainerGroup::getTimeCreated).orElseGet(Instant::now));

        JsonNode locationNode = body.get("location");
        String location = locationNode != null && locationNode.isTextual() && !locationNode.asText().isBlank()
                ? locationNode.asText()
                : config.services().containerInstance().defaultLocation();
        group.setLocation(location);
        group.setTags(parseTags(body.get("tags")));
        group.setZones(parseStringList(body.get("zones")));
        group.setIdentity(resolveIdentity(body.get("identity"),
                existing.map(ContainerGroup::getIdentity).orElse(null)));

        Map<String, Object> properties = redactSecrets(stripReadOnly(objectToMap(body.get("properties"))));
        group.setProperties(properties);
        group.setRestartPolicy(RestartPolicy.fromWire(String.valueOf(properties.get("restartPolicy"))));
        properties.put("restartPolicy", group.getRestartPolicy().wire());
        group.setContainers(buildContainerRecords(properties));
        group.setIpAddress("127.0.0.1");
        group.setFqdn(resolveFqdn(properties, group));

        provisionMocked(group);
        write(key, group);
        return Response.status(isNew ? 201 : 200)
                .entity(toArmResponse(group, true))
                .type("application/json")
                .build();
    }

    /** Mocked-mode provisioning: everything is immediately up, with no Docker call. */
    private void provisionMocked(ContainerGroup group) {
        Instant now = Instant.now();
        group.setProvisioningState("Succeeded");
        group.setGroupState(GroupStateValue.RUNNING);
        for (ContainerRecord container : group.getContainers()) {
            container.setState(ContainerStateValue.RUNNING);
            container.setStartTime(now);
            container.setDetailStatus("");
            container.setExitCode(null);
            container.setFinishTime(null);
            container.setRestartCount(0);
        }
    }

    private Response handleGet(String sub, String rg, String name, boolean expandInstanceView) {
        return read(storageKey(sub, rg, name))
                .map(group -> Response.ok(toArmResponse(group, expandInstanceView))
                        .type("application/json").build())
                .orElseGet(() -> ContainerInstanceErrors.groupNotFound(name, rg));
    }

    private Response handleUpdateTags(String sub, String rg, String name, AzureRequest req) {
        String key = storageKey(sub, rg, name);
        Optional<ContainerGroup> found = read(key);
        if (found.isEmpty()) {
            return ContainerInstanceErrors.groupNotFound(name, rg);
        }
        ContainerGroup group = found.get();
        // ContainerGroups_Update's request body is a bare Resource: it carries no `properties`,
        // so anything sent there is ignored and the tag collection is replaced wholesale.
        group.setTags(parseTags(readBody(req.bodyStream()).get("tags")));
        write(key, group);
        return Response.ok(toArmResponse(group, true)).type("application/json").build();
    }

    private Response handleDelete(String sub, String rg, String name) {
        // 204 rather than 202: the azurerm provider's DeleteThenPoll would otherwise poll the
        // collection endpoint forever. 204 is terminal and idempotent for an absent group.
        storage.delete(storageKey(sub, rg, name));
        return Response.status(204).build();
    }

    private Response handleListBySubscription(String sub) {
        String prefix = sub + "/";
        return listResponse(group -> group.storageKey().startsWith(prefix));
    }

    private Response handleListByResourceGroup(String sub, String rg) {
        String prefix = (sub + "/" + rg + "/").toLowerCase(Locale.ROOT);
        return listResponse(group -> group.storageKey().toLowerCase(Locale.ROOT).startsWith(prefix));
    }

    private Response listResponse(java.util.function.Predicate<ContainerGroup> filter) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ContainerGroup group : scanAll()) {
            if (filter.test(group)) {
                items.add(toArmResponse(group, false));
            }
        }
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    // ── Actions ────────────────────────────────────────────────────────────────────────────

    private Response handleAction(String sub, String rg, String name, String action) {
        String key = storageKey(sub, rg, name);
        Optional<ContainerGroup> found = read(key);
        if (found.isEmpty()) {
            return ContainerInstanceErrors.groupNotFound(name, rg);
        }
        ContainerGroup group = found.get();
        switch (action) {
            case "stop"    -> applyMockedStop(group);
            case "start"   -> applyMockedStart(group, false);
            default        -> applyMockedStart(group, true);
        }
        write(key, group);
        // Terminal, with no Azure-AsyncOperation / Location / Retry-After header: the emulator
        // completes every operation synchronously.
        return Response.status(204).build();
    }

    private void applyMockedStop(ContainerGroup group) {
        Instant now = Instant.now();
        group.setGroupState(GroupStateValue.STOPPED);
        for (ContainerRecord container : group.getContainers()) {
            container.setState(ContainerStateValue.TERMINATED);
            container.setExitCode(0);
            container.setFinishTime(now);
            container.setDetailStatus("Completed");
        }
    }

    private void applyMockedStart(ContainerGroup group, boolean restart) {
        Instant now = Instant.now();
        group.setGroupState(GroupStateValue.RUNNING);
        for (ContainerRecord container : group.getContainers()) {
            if (restart) {
                container.rememberCurrentAsPrevious();
                container.setRestartCount(container.getRestartCount() + 1);
            }
            container.setState(ContainerStateValue.RUNNING);
            container.setStartTime(now);
            container.setExitCode(null);
            container.setFinishTime(null);
            container.setDetailStatus("");
        }
    }

    // ── Logs ───────────────────────────────────────────────────────────────────────────────

    private Response handleLogs(String sub, String rg, String name, String containerName, AzureRequest req) {
        Optional<ContainerGroup> found = read(storageKey(sub, rg, name));
        if (found.isEmpty()) {
            return ContainerInstanceErrors.groupNotFound(name, rg);
        }
        ContainerGroup group = found.get();
        if (group.container(containerName) == null) {
            return ContainerInstanceErrors.containerNotFound(containerName, name, rg);
        }
        String rawTail = queryParam(req, "tail");
        Integer tailLines = null;
        if (rawTail != null && !rawTail.isBlank()) {
            try {
                tailLines = Integer.valueOf(rawTail.trim());
            } catch (NumberFormatException e) {
                LOG.debugv("Rejecting non-numeric tail parameter ''{0}'' for container group {1}: {2}",
                        rawTail, name, e.getMessage());
                return ContainerInstanceErrors.invalidParameter("tail", rawTail,
                        ContainerInstanceErrors.REASON_AT_LEAST_ONE);
            }
            if (tailLines < 1) {
                return ContainerInstanceErrors.invalidParameter("tail", rawTail,
                        ContainerInstanceErrors.REASON_AT_LEAST_ONE);
            }
        }
        boolean timestamps = Boolean.parseBoolean(queryParam(req, "timestamps"));
        return Response.ok(Map.of("content", readLogs(group, containerName, tailLines, timestamps)))
                .type("application/json").build();
    }

    /**
     * Mocked mode has no container to read from, so the log content is always empty. Commit 6
     * overrides this for real-Docker mode.
     */
    protected String readLogs(ContainerGroup group, String containerName, Integer tail, boolean timestamps) {
        return "";
    }

    // ── Read-only collections ──────────────────────────────────────────────────────────────

    private Response handleOutboundNetworkDependencies(String sub, String rg, String name) {
        if (read(storageKey(sub, rg, name)).isEmpty()) {
            return ContainerInstanceErrors.groupNotFound(name, rg);
        }
        // NetworkDependenciesResponse is documented as "always empty list".
        return Response.ok(List.of()).type("application/json").build();
    }

    private Response handleListUsage(String sub, String location) {
        String prefix = sub + "/";
        int groupCount = 0;
        double cpuTotal = 0;
        for (ContainerGroup group : scanAll()) {
            if (!group.storageKey().startsWith(prefix)) {
                continue;
            }
            groupCount++;
            cpuTotal += requestedCpu(group);
        }
        String base = "/subscriptions/" + sub + "/providers/Microsoft.ContainerInstance/locations/"
                + location + "/usages/";
        List<Map<String, Object>> values = List.of(
                usage(base + "ContainerGroups", groupCount, "ContainerGroups", "Container Groups"),
                usage(base + "StandardCores", (int) Math.ceil(cpuTotal), "StandardCores", "Standard Cores"));
        return Response.ok(Map.of("value", values)).type("application/json").build();
    }

    private static Map<String, Object> usage(String id, int currentValue, String value, String localized) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("unit", "Count");
        entry.put("currentValue", currentValue);
        entry.put("limit", USAGE_LIMIT);
        entry.put("name", Map.of("value", value, "localizedValue", localized));
        return entry;
    }

    private Response handleListCapabilities(String location) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("resourceType", "containerGroups");
        entry.put("osType", "Linux");
        entry.put("location", location);
        entry.put("ipAddressType", "Public");
        entry.put("gpu", "None");
        entry.put("capabilities", Map.of(
                "maxMemoryInGB", 240.0,
                "maxCpu", 31.0,
                "maxGpuCount", 0.0));
        return Response.ok(Map.of("value", List.of(entry))).type("application/json").build();
    }

    private Response handleListCachedImages() {
        // The emulator has no curated image cache; ImageCacheService caches per process.
        return Response.ok(Map.of("value", List.of())).type("application/json").build();
    }

    // ── ARM response assembly ──────────────────────────────────────────────────────────────

    Map<String, Object> toArmResponse(ContainerGroup group, boolean expandInstanceView) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provisioningState", group.getProvisioningState());
        properties.putAll(deepCopy(group.getProperties()));
        decorateIpAddress(properties, group);
        decorateContainers(properties, group, expandInstanceView);
        if (expandInstanceView) {
            Map<String, Object> instanceView = new LinkedHashMap<>();
            instanceView.put("state", group.getGroupState().wire());
            instanceView.put("events", renderEvents(group.getGroupEvents()));
            properties.put("instanceView", instanceView);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", group.armId());
        out.put("name", group.getName());
        out.put("type", RESOURCE_TYPE);
        out.put("location", group.getLocation());
        if (group.getTags() != null && !group.getTags().isEmpty()) {
            out.put("tags", group.getTags());
        }
        if (group.getZones() != null && !group.getZones().isEmpty()) {
            out.put("zones", group.getZones());
        }
        if (group.getIdentity() != null && !group.getIdentity().isEmpty()) {
            out.put("identity", group.getIdentity());
        }
        out.put("properties", properties);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void decorateIpAddress(Map<String, Object> properties, ContainerGroup group) {
        Object ipAddress = properties.get("ipAddress");
        if (!(ipAddress instanceof Map)) {
            return;
        }
        Map<String, Object> ip = (Map<String, Object>) ipAddress;
        ip.put("ip", group.getIpAddress());
        ip.putIfAbsent("autoGeneratedDomainNameLabelScope", "Unsecure");
        if (group.getFqdn() != null) {
            ip.put("fqdn", group.getFqdn());
        }
    }

    @SuppressWarnings("unchecked")
    private void decorateContainers(Map<String, Object> properties, ContainerGroup group,
                                    boolean expandInstanceView) {
        for (String arrayName : List.of("containers", "initContainers")) {
            Object array = properties.get(arrayName);
            if (!(array instanceof List)) {
                continue;
            }
            for (Object element : (List<Object>) array) {
                if (!(element instanceof Map)) {
                    continue;
                }
                Map<String, Object> entry = (Map<String, Object>) element;
                Object containerProps = entry.get("properties");
                if (!(containerProps instanceof Map)) {
                    continue;
                }
                Map<String, Object> cp = (Map<String, Object>) containerProps;
                if (!expandInstanceView) {
                    cp.remove("instanceView");
                    continue;
                }
                ContainerRecord record = group.container(String.valueOf(entry.get("name")));
                if (record != null) {
                    cp.put("instanceView", renderContainerInstanceView(record));
                }
            }
        }
    }

    private static Map<String, Object> renderContainerInstanceView(ContainerRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("restartCount", record.getRestartCount());
        view.put("currentState", renderState(record.getState(), record.getStartTime(),
                record.getExitCode(), record.getFinishTime(), record.getDetailStatus()));
        if (record.getPreviousState() != null) {
            view.put("previousState", renderState(record.getPreviousState(),
                    record.getPreviousStartTime(), record.getPreviousExitCode(),
                    record.getPreviousFinishTime(), detailStatusFor(record.getPreviousExitCode())));
        }
        view.put("events", renderEvents(record.getEvents()));
        return view;
    }

    private static Map<String, Object> renderState(ContainerStateValue state, Instant startTime,
                                                   Integer exitCode, Instant finishTime,
                                                   String detailStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state.wire());
        if (startTime != null) {
            out.put("startTime", rfc3339(startTime));
        }
        if (state == ContainerStateValue.TERMINATED) {
            out.put("exitCode", exitCode == null ? 0 : exitCode);
            if (finishTime != null) {
                out.put("finishTime", rfc3339(finishTime));
            }
        }
        out.put("detailStatus", detailStatus == null ? "" : detailStatus);
        return out;
    }

    /** A19: {@code ""} while waiting or running, {@code Completed} on 0, {@code Error} otherwise. */
    static String detailStatusFor(Integer exitCode) {
        if (exitCode == null) {
            return "";
        }
        return exitCode == 0 ? "Completed" : "Error";
    }

    private static List<Map<String, Object>> renderEvents(List<EventRecord> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (events == null) {
            return out;
        }
        for (EventRecord event : events) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("count", event.getCount());
            entry.put("firstTimestamp", rfc3339(event.getFirstTimestamp()));
            entry.put("lastTimestamp", rfc3339(event.getLastTimestamp()));
            entry.put("name", event.getName());
            entry.put("message", event.getMessage());
            entry.put("type", event.getType());
            out.add(entry);
        }
        return out;
    }

    // ── Request parsing ────────────────────────────────────────────────────────────────────

    /** Builds one {@link ContainerRecord} per declared container, main containers first. */
    @SuppressWarnings("unchecked")
    static List<ContainerRecord> buildContainerRecords(Map<String, Object> properties) {
        List<ContainerRecord> records = new ArrayList<>();
        for (String arrayName : List.of("containers", "initContainers")) {
            Object array = properties.get(arrayName);
            if (!(array instanceof List)) {
                continue;
            }
            for (Object element : (List<Object>) array) {
                if (!(element instanceof Map)) {
                    continue;
                }
                ContainerRecord record = new ContainerRecord();
                record.setName(String.valueOf(((Map<String, Object>) element).get("name")));
                record.setInit("initContainers".equals(arrayName));
                records.add(record);
            }
        }
        return records;
    }

    /** Read-only properties are ignored on input and regenerated on output. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stripReadOnly(Map<String, Object> properties) {
        properties.remove("provisioningState");
        properties.remove("instanceView");
        Object ipAddress = properties.get("ipAddress");
        if (ipAddress instanceof Map) {
            ((Map<String, Object>) ipAddress).remove("ip");
            ((Map<String, Object>) ipAddress).remove("fqdn");
        }
        forEachContainer(properties, (name, containerProps) -> containerProps.remove("instanceView"));
        return properties;
    }

    /**
     * Removes every {@code x-ms-secret} value before the group is persisted or returned. This is
     * the only place secrets leave the request; {@code ContainerGroupRuntime} reads them from the
     * body separately and keeps them in memory.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> redactSecrets(Map<String, Object> properties) {
        forEachContainer(properties, (name, containerProps) -> {
            Object env = containerProps.get("environmentVariables");
            if (env instanceof List) {
                for (Object entry : (List<Object>) env) {
                    if (entry instanceof Map) {
                        ((Map<String, Object>) entry).remove("secureValue");
                    }
                }
            }
        });
        Object volumes = properties.get("volumes");
        if (volumes instanceof List) {
            for (Object element : (List<Object>) volumes) {
                if (!(element instanceof Map)) {
                    continue;
                }
                Map<String, Object> volume = (Map<String, Object>) element;
                if (volume.get("secret") instanceof Map) {
                    volume.put("secret", new LinkedHashMap<String, Object>());
                }
                if (volume.get("azureFile") instanceof Map) {
                    ((Map<String, Object>) volume.get("azureFile")).remove("storageAccountKey");
                }
            }
        }
        Object credentials = properties.get("imageRegistryCredentials");
        if (credentials instanceof List) {
            for (Object element : (List<Object>) credentials) {
                if (element instanceof Map) {
                    ((Map<String, Object>) element).remove("password");
                }
            }
        }
        Object diagnostics = properties.get("diagnostics");
        if (diagnostics instanceof Map) {
            Object logAnalytics = ((Map<String, Object>) diagnostics).get("logAnalytics");
            if (logAnalytics instanceof Map) {
                ((Map<String, Object>) logAnalytics).remove("workspaceKey");
                ((Map<String, Object>) logAnalytics).remove("workspaceResourceId");
            }
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static void forEachContainer(Map<String, Object> properties,
                                         java.util.function.BiConsumer<String, Map<String, Object>> action) {
        for (String arrayName : List.of("containers", "initContainers")) {
            Object array = properties.get(arrayName);
            if (!(array instanceof List)) {
                continue;
            }
            for (Object element : (List<Object>) array) {
                if (!(element instanceof Map)) {
                    continue;
                }
                Map<String, Object> entry = (Map<String, Object>) element;
                Object containerProps = entry.get("properties");
                if (containerProps instanceof Map) {
                    action.accept(String.valueOf(entry.get("name")), (Map<String, Object>) containerProps);
                }
            }
        }
    }

    /**
     * Echoes {@code identity} with generated, stable read-only ids. {@code principalId} survives an
     * update so a client polling {@code GET} never sees it change.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveIdentity(JsonNode identityNode, Map<String, Object> existing) {
        if (identityNode == null || !identityNode.isObject()) {
            return null;
        }
        Map<String, Object> identity = objectToMap(identityNode);
        String type = String.valueOf(identity.getOrDefault("type", ""));
        if (type.contains("SystemAssigned")) {
            Object previous = existing == null ? null : existing.get("principalId");
            identity.put("principalId", previous != null ? previous : UUID.randomUUID().toString());
            identity.put("tenantId", config.services().entra().defaultTenantId());
        }
        Object assigned = identity.get("userAssignedIdentities");
        if (assigned instanceof Map) {
            Map<String, Object> previousAssigned = existing == null ? Map.of()
                    : (Map<String, Object>) existing.getOrDefault("userAssignedIdentities", Map.of());
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) assigned).entrySet()) {
                Map<String, Object> value = entry.getValue() instanceof Map
                        ? new LinkedHashMap<>((Map<String, Object>) entry.getValue())
                        : new LinkedHashMap<>();
                Object previous = previousAssigned.get(entry.getKey());
                Map<String, Object> previousValue = previous instanceof Map
                        ? (Map<String, Object>) previous : Map.of();
                value.put("principalId", previousValue.getOrDefault("principalId",
                        UUID.randomUUID().toString()));
                value.put("clientId", previousValue.getOrDefault("clientId",
                        UUID.randomUUID().toString()));
                entry.setValue(value);
            }
        }
        return identity;
    }

    @SuppressWarnings("unchecked")
    private static String resolveFqdn(Map<String, Object> properties, ContainerGroup group) {
        Object ipAddress = properties.get("ipAddress");
        if (!(ipAddress instanceof Map)) {
            return null;
        }
        Object label = ((Map<String, Object>) ipAddress).get("dnsNameLabel");
        if (label == null || String.valueOf(label).isBlank()) {
            return null;
        }
        return label + "." + group.normalizedLocation() + ".azurecontainer.io";
    }

    @SuppressWarnings("unchecked")
    private static double requestedCpu(ContainerGroup group) {
        Object containers = group.getProperties().get("containers");
        if (!(containers instanceof List)) {
            return 0;
        }
        double total = 0;
        for (Object element : (List<Object>) containers) {
            if (!(element instanceof Map)) {
                continue;
            }
            Object props = ((Map<String, Object>) element).get("properties");
            if (!(props instanceof Map)) {
                continue;
            }
            Object resources = ((Map<String, Object>) props).get("resources");
            if (!(resources instanceof Map)) {
                continue;
            }
            Object requests = ((Map<String, Object>) resources).get("requests");
            if (requests instanceof Map) {
                Object cpu = ((Map<String, Object>) requests).get("cpu");
                if (cpu instanceof Number number) {
                    total += number.doubleValue();
                }
            }
        }
        return total;
    }

    // ── Storage ────────────────────────────────────────────────────────────────────────────

    Optional<ContainerGroup> read(String key) {
        return storage.get(key).map(stored -> {
            try {
                return MAPPER.readValue(stored.data(), ContainerGroup.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize container group {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    void write(String key, ContainerGroup group) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(group);
            storage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize container group " + key, e);
        }
    }

    List<ContainerGroup> scanAll() {
        List<ContainerGroup> groups = new ArrayList<>();
        storage.scan(k -> true).forEach(stored -> {
            try {
                ContainerGroup group = MAPPER.readValue(stored.data(), ContainerGroup.class);
                if (group != null) {
                    groups.add(group);
                }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable container group entry: {0}", e.getMessage());
            }
        });
        return groups;
    }

    /** Wipes every container group — used by {@code POST /_admin/reset}. */
    @Override
    public void clear() {
        storage.clear();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    private static String tail(String fullPath) {
        int idx = fullPath.indexOf(CI_MARKER);
        return idx >= 0 ? fullPath.substring(idx + CI_MARKER.length()) : fullPath;
    }

    private static boolean isResourceGroupScoped(String fullPath) {
        return fullPath.toLowerCase(Locale.ROOT).contains("/resourcegroups/");
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("[/?]");
        return index < parts.length ? parts[index] : "";
    }

    static String storageKey(String sub, String rg, String name) {
        return sub + "/" + rg + "/" + name;
    }

    private static String newGroupId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static boolean expandsInstanceView(AzureRequest req) {
        String expand = queryParam(req, "$expand");
        return expand != null && expand.toLowerCase(Locale.ROOT).contains("instanceview");
    }

    private static String queryParam(AzureRequest req, String name) {
        return req.queryParams() == null ? null : req.queryParams().get(name);
    }

    static String rfc3339(Instant instant) {
        return instant == null ? null
                : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isObject()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        return tags.isEmpty() ? null : tags;
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(element -> values.add(element.asText()));
        return values.isEmpty() ? null : values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(source, LinkedHashMap.class);
    }

    private static JsonNode readBody(java.io.InputStream stream) {
        try {
            if (stream == null) {
                return MAPPER.createObjectNode();
            }
            byte[] bytes = stream.readAllBytes();
            if (bytes.length == 0) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(bytes);
        } catch (Exception e) {
            LOG.debugv("Container group request body did not parse as JSON: {0}", e.getMessage());
            return null;
        }
    }
}
