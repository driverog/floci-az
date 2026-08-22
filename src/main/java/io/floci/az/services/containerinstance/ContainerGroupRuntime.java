package io.floci.az.services.containerinstance;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerRuntimeState;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.ImageCacheService;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerGroup;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerRecord;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.GroupSecrets;
import io.floci.az.services.containerinstance.ContainerInstanceModels.GroupStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.PortMapping;
import io.floci.az.services.containerinstance.ContainerInstanceModels.RegistryCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Docker side of a container group: one <em>infrastructure container</em> that owns the
 * network namespace and every host port binding, plus one Docker container per ACI container
 * joined to it with network mode {@code container:<infraContainerId>}.
 *
 * <p>Three properties of that arrangement are load-bearing and were established experimentally
 * against a live daemon:</p>
 * <ul>
 *   <li>Host port bindings must live on the infra container and <strong>only</strong> there —
 *       Docker rejects port publishing on a container using {@code container:} network mode.</li>
 *   <li>The infra container must be started before, and stopped after, every member: a member
 *       cannot join the namespace of a container that is not running.</li>
 *   <li>The infra container may never be restarted in place while members run — it would come
 *       back in a <em>new</em> namespace and strand them in the old one. A restart is a
 *       sequenced stop-all / start-all.</li>
 * </ul>
 *
 * <p>This class never builds a {@link jakarta.ws.rs.core.Response} and never reads the storage
 * backend; it mutates the {@link ContainerGroup} it is handed and throws on failure. It reaches
 * Docker only through {@link ContainerBuilder}, {@link ContainerLifecycleManager},
 * {@link ImageCacheService} and {@link PortAllocator}.</p>
 */
@ApplicationScoped
public class ContainerGroupRuntime {

    private static final Logger LOG = Logger.getLogger(ContainerGroupRuntime.class);

    /** Where secret volumes are mounted on the infra container so they can be populated. */
    private static final String SECRET_STAGING_ROOT = "/floci-secrets/";
    private static final Duration LOG_TIMEOUT = Duration.ofSeconds(30);
    private static final long INIT_TIMEOUT_MS = 300_000L;
    private static final long INIT_POLL_MS = 500L;
    private static final int MAX_EVENTS = 20;

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCacheService;
    private final PortAllocator portAllocator;
    private final ContainerDetector containerDetector;

    /**
     * Write-only material, keyed by storage key, for the lifetime of the process. Never
     * persisted, never logged, never returned.
     */
    private final ConcurrentHashMap<String, GroupSecrets> secretsByKey = new ConcurrentHashMap<>();

    /**
     * One lock per container group, shared by the request handler and the reconciler.
     *
     * <p>Without it the reconciler races an in-flight request: a {@code PUT} over an existing
     * group removes the old infra container, and before the new group is persisted the
     * reconciler sees the stored group's infra container missing and "repairs" it — removing by
     * name the container the request has just created. The same race turns a {@code stop} into a
     * spurious namespace rebuild. The handler blocks on the lock; the reconciler skips a group
     * whose lock it cannot take, and picks it up on the next tick.</p>
     */
    private final ConcurrentHashMap<String, ReentrantLock> locksByKey = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(String storageKey) {
        return locksByKey.computeIfAbsent(storageKey, key -> new ReentrantLock());
    }

    @Inject
    public ContainerGroupRuntime(EmulatorConfig config,
                                 ContainerBuilder containerBuilder,
                                 ContainerLifecycleManager lifecycleManager,
                                 ImageCacheService imageCacheService,
                                 PortAllocator portAllocator,
                                 ContainerDetector containerDetector) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.imageCacheService = imageCacheService;
        this.portAllocator = portAllocator;
        this.containerDetector = containerDetector;
    }

    /** Thrown when a group could not be brought up; carries the failing step for the log line. */
    public static class ContainerGroupRuntimeException extends RuntimeException {
        private final boolean dockerUnavailable;

        public ContainerGroupRuntimeException(String message, Throwable cause, boolean dockerUnavailable) {
            super(message, cause);
            this.dockerUnavailable = dockerUnavailable;
        }

        /** True when the Docker daemon itself could not be reached — the degradation trigger. */
        public boolean isDockerUnavailable() {
            return dockerUnavailable;
        }
    }

    // ── Secret custody ─────────────────────────────────────────────────────────────────────

    public void rememberSecrets(String storageKey, GroupSecrets secrets) {
        secretsByKey.put(storageKey, secrets);
    }

    public GroupSecrets secrets(String storageKey) {
        return secretsByKey.get(storageKey);
    }

    public boolean hasSecrets(String storageKey) {
        return secretsByKey.containsKey(storageKey);
    }

    public void forgetSecrets(String storageKey) {
        // The lock deliberately outlives the secrets: dropping it here could hand a concurrent
        // caller a fresh lock object while another thread still holds the old one.
        secretsByKey.remove(storageKey);
    }

    public void forgetAllSecrets() {
        secretsByKey.clear();
        locksByKey.clear();
    }

    // ── Daemon liveness ────────────────────────────────────────────────────────────────────

    /**
     * Whether the Docker daemon answers. This is a liveness probe, not container management,
     * which is why it goes through the raw client accessor.
     */
    public boolean daemonReachable() {
        return daemonUnreachableReason().isEmpty();
    }

    /** Empty when the daemon answers; otherwise the failure message, for the caller's log line. */
    public java.util.Optional<String> daemonUnreachableReason() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return java.util.Optional.empty();
        } catch (Exception e) {
            LOG.debugv("Docker daemon ping failed: {0}", e.getMessage());
            return java.util.Optional.of(String.valueOf(e.getMessage()));
        }
    }

    public ContainerRuntimeState inspect(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return ContainerRuntimeState.ABSENT;
        }
        return lifecycleManager.inspectState(containerId);
    }

    // ── Creation ───────────────────────────────────────────────────────────────────────────

    /**
     * Brings a container group up: ports, volumes, infra container, secret volumes, init
     * containers, then app containers — in that order. On any failure everything created is
     * rolled back in reverse order and a {@link ContainerGroupRuntimeException} is thrown.
     */
    public void createGroup(ContainerGroup group, GroupSecrets secrets) {
        if (!daemonReachable()) {
            throw new ContainerGroupRuntimeException(
                    "Docker daemon is not reachable for container group " + group.getName(), null, true);
        }
        List<String> startedContainerIds = new ArrayList<>();
        List<Integer> reservedPorts = new ArrayList<>();
        try {
            resolveHostPorts(group, reservedPorts);
            ensureVolumes(group);
            startInfraContainer(group);
            resolveGroupIp(group);
            populateSecretVolumes(group, secrets);
            runInitContainers(group, secrets, startedContainerIds);
            startAppContainers(group, secrets, startedContainerIds);
            group.setProvisioningState("Succeeded");
            group.setGroupState(GroupStateValue.RUNNING);
        } catch (RuntimeException e) {
            rollback(group, startedContainerIds, reservedPorts);
            if (e instanceof ContainerGroupRuntimeException cgre) {
                throw cgre;
            }
            throw new ContainerGroupRuntimeException(
                    "Failed to create container group " + group.getName(), e, false);
        }
    }

    /** Step 1 — reuse the Azure port when it is unprivileged and free, else take one from the range. */
    private void resolveHostPorts(ContainerGroup group, List<Integer> reservedPorts) {
        List<Map<String, Object>> ports = groupPorts(group);
        List<PortMapping> existing = new ArrayList<>(group.getPortMappings());
        List<PortMapping> mappings = new ArrayList<>();
        for (Map<String, Object> port : ports) {
            int azurePort = intValue(port.get("port"));
            String protocol = port.get("protocol") == null ? "TCP" : String.valueOf(port.get("protocol"));
            Integer carried = existing.stream()
                    .filter(m -> m.getGroupPort() == azurePort)
                    .map(PortMapping::getHostPort)
                    .findFirst().orElse(null);
            int hostPort;
            if (carried != null) {
                // A PUT on an existing group keeps its published host ports (transition G16).
                hostPort = carried;
                portAllocator.markReserved(hostPort);
            } else if (azurePort >= 1024 && portAllocator.isPortFree(azurePort)) {
                hostPort = azurePort;
                portAllocator.markReserved(hostPort);
            } else {
                try {
                    hostPort = portAllocator.allocate(config.services().containerInstance().basePort(),
                            config.services().containerInstance().maxPort());
                } catch (RuntimeException e) {
                    String message = "No free host port available in range "
                            + config.services().containerInstance().basePort() + "-"
                            + config.services().containerInstance().maxPort()
                            + " for container group port " + azurePort;
                    LOG.errorv("No free host port in range {0}-{1} for container group {2}",
                            config.services().containerInstance().basePort(),
                            config.services().containerInstance().maxPort(), group.getName());
                    appendEvent(group.getGroupEvents(), "Failed", message, "Warning");
                    throw new ContainerGroupRuntimeException(message, e, false);
                }
            }
            reservedPorts.add(hostPort);
            mappings.add(new PortMapping(azurePort, hostPort, protocol));
        }
        group.setPortMappings(mappings);
    }

    /** Step 2 — one Docker named volume per declared ACI volume. */
    private void ensureVolumes(ContainerGroup group) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> volume : volumes(group)) {
            String aciName = String.valueOf(volume.get("name"));
            String dockerName = volumeDockerName(group, volume);
            try {
                lifecycleManager.ensureVolume(dockerName);
            } catch (RuntimeException e) {
                LOG.errorv("Failed to create volume {0} for container group {1}: {2}",
                        dockerName, group.getName(), e.getMessage());
                throw new ContainerGroupRuntimeException(
                        "Failed to create volume " + dockerName, e, false);
            }
            names.add(dockerName);
            if (volume.get("azureFile") instanceof Map<?, ?> azureFile) {
                appendEvent(group.getGroupEvents(), "AzureFileEmulated",
                        "Azure File share '" + azureFile.get("shareName") + "' in account '"
                                + azureFile.get("storageAccountName")
                                + "' is emulated by local Docker volume '" + dockerName
                                + "'; contents are not shared with the emulated Blob or File service.",
                        "Normal");
            }
            LOG.debugv("Container group {0}: volume {1} backs ACI volume {2}",
                    group.getName(), dockerName, aciName);
        }
        group.setVolumeNames(names);
    }

    /** Steps 3–5 — the namespace owner, created first and destroyed last. */
    private void startInfraContainer(ContainerGroup group) {
        String infraName = infraName(group);
        String infraImage = config.services().containerInstance().infraImage();
        ContainerBuilder.Builder infra = containerBuilder.newContainer(infraImage)
                .withName(infraName)
                .withCmd(List.of("tail", "-f", "/dev/null"))
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withHostDockerInternalOnLinux()
                .withLogRotation();
        applyLabels(infra, group, "__infra__", "infra");
        for (PortMapping mapping : group.getPortMappings()) {
            infra.withPortBinding(mapping.getGroupPort(), mapping.getHostPort());
        }
        for (int port : distinctContainerPorts(group)) {
            infra.withExposedPort(port);
        }
        for (Map<String, Object> volume : volumes(group)) {
            if (volume.get("secret") instanceof Map) {
                infra.withNamedVolume(volumeDockerName(group, volume),
                        SECRET_STAGING_ROOT + volume.get("name"), false);
            }
        }
        try {
            lifecycleManager.removeIfExists(infraName);
            group.setInfraContainerId(lifecycleManager.createAndStart(infra.build()).containerId());
        } catch (RuntimeException e) {
            LOG.errorv("Failed to start infrastructure container for container group {0}: {1}",
                    group.getName(), e.getMessage());
            throw new ContainerGroupRuntimeException(
                    "Failed to start infrastructure container for container group " + group.getName(),
                    e, false);
        }
        for (PortMapping mapping : group.getPortMappings()) {
            appendEvent(group.getGroupEvents(), "PortMapped",
                    "Container group port " + mapping.getGroupPort()
                            + " published on host port " + mapping.getHostPort(), "Normal");
        }
    }

    /**
     * Re-resolves the address a client should connect to. Docker gives a container a new
     * network-namespace IP every time it starts, so a stop/start cycle invalidates the address
     * reported at create time whenever floci-az itself runs inside Docker.
     */
    public void refreshGroupIp(ContainerGroup group) {
        if (group.getInfraContainerId() == null) {
            return;
        }
        try {
            resolveGroupIp(group);
        } catch (Exception e) {
            LOG.debugv("Could not refresh the IP of container group {0}: {1}",
                    group.getName(), e.getMessage());
        }
    }

    /** Step 6 — the address a client in the caller's position can actually reach. */
    private void resolveGroupIp(ContainerGroup group) {
        if (!containerDetector.isRunningInContainer()) {
            group.setIpAddress("127.0.0.1");
            return;
        }
        String ip = "127.0.0.1";
        for (PortMapping mapping : group.getPortMappings()) {
            try {
                var endpoint = lifecycleManager.resolveEndpoint(group.getInfraContainerId(),
                        mapping.getGroupPort());
                if (endpoint != null && endpoint.host() != null && !endpoint.host().isBlank()) {
                    ip = endpoint.host();
                    break;
                }
            } catch (RuntimeException e) {
                LOG.debugv("Could not resolve the group IP for {0} on port {1}: {2}",
                        group.getName(), mapping.getGroupPort(), e.getMessage());
            }
        }
        group.setIpAddress(ip);
    }

    /** Step 7 — secret files land inside the named volume through the running infra container. */
    private void populateSecretVolumes(ContainerGroup group, GroupSecrets secrets) {
        for (Map<String, Object> volume : volumes(group)) {
            if (!(volume.get("secret") instanceof Map)) {
                continue;
            }
            String aciName = String.valueOf(volume.get("name"));
            Map<String, String> contents = secrets == null ? Map.of()
                    : secrets.secretVolumes().getOrDefault(aciName, Map.of());
            for (Map.Entry<String, String> entry : contents.entrySet()) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(entry.getValue());
                    lifecycleManager.copyBytesToContainer(group.getInfraContainerId(), decoded,
                            SECRET_STAGING_ROOT + aciName + "/" + entry.getKey());
                } catch (RuntimeException e) {
                    LOG.errorv("Failed to populate secret volume {0} for container group {1}: {2}",
                            volumeDockerName(group, volume), group.getName(), e.getMessage());
                    throw new ContainerGroupRuntimeException(
                            "Failed to populate secret volume " + aciName, e, false);
                }
            }
        }
    }

    /** Step 8 — init containers run to completion, in array order, before the main containers. */
    private void runInitContainers(ContainerGroup group, GroupSecrets secrets, List<String> started) {
        for (Map<String, Object> container : containerEntries(group, "initContainers")) {
            String name = String.valueOf(container.get("name"));
            Map<String, Object> props = containerProperties(container);
            Object image = props.get("image");
            if (image == null || String.valueOf(image).isBlank()) {
                LOG.debugv("Init container {0} of container group {1} declares no image; skipping",
                        name, group.getName());
                continue;
            }
            ContainerRecord record = group.container(name);
            String id = createAppContainer(group, container, secrets, initName(group, name), record);
            started.add(id);
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            ContainerRuntimeState state = lifecycleManager.inspectState(id);
            while (state.running() && System.currentTimeMillis() < deadline) {
                sleep(INIT_POLL_MS);
                state = lifecycleManager.inspectState(id);
            }
            if (state.running()) {
                String message = "Init container " + name + " in container group " + group.getName()
                        + " did not complete within 300 seconds";
                LOG.error(message);
                failContainer(record, message);
                throw new ContainerGroupRuntimeException(message, null, false);
            }
            applyTerminated(record, state.exitCode(), state.finishedAt());
            if (state.exitCode() != 0) {
                String message = "Init container " + name + " in container group " + group.getName()
                        + " exited with code " + state.exitCode();
                LOG.error(message);
                appendEvent(record.getEvents(), "Failed", message, "Warning");
                throw new ContainerGroupRuntimeException(message, null, false);
            }
        }
    }

    /** Step 9 — the app containers, each joining the infra container's namespace. */
    private void startAppContainers(ContainerGroup group, GroupSecrets secrets, List<String> started) {
        for (Map<String, Object> container : containerEntries(group, "containers")) {
            String name = String.valueOf(container.get("name"));
            ContainerRecord record = group.container(name);
            String id = createAppContainer(group, container, secrets, appName(group, name), record);
            started.add(id);
            ContainerRuntimeState state = lifecycleManager.inspectState(id);
            record.setState(ContainerStateValue.RUNNING);
            record.setStartTime(state.startedAt() != null ? state.startedAt() : Instant.now());
            record.setDetailStatus("");
            record.setExitCode(null);
            record.setFinishTime(null);
        }
    }

    /**
     * Pulls the image with any per-request registry credential, builds the spec, and starts the
     * container. Note what is deliberately absent: no {@code withPortBinding}, no
     * {@code withExposedPort}, no {@code withDockerNetwork} and no {@code withEmbeddedDns} — all
     * four are properties of the namespace, which this container inherits from the infra
     * container, and Docker rejects port publishing outright in {@code container:} network mode.
     */
    private String createAppContainer(ContainerGroup group, Map<String, Object> container,
                                      GroupSecrets secrets, String dockerName, ContainerRecord record) {
        Map<String, Object> props = containerProperties(container);
        String name = String.valueOf(container.get("name"));
        String image = String.valueOf(props.get("image"));
        boolean init = record != null && record.isInit();

        pullImage(group, record, image, secrets);

        ContainerBuilder.Builder app = containerBuilder.newContainer(image)
                .withName(dockerName)
                .withNetworkMode("container:" + group.getInfraContainerId())
                .withLogRotation();
        applyLabels(app, group, name, init ? "init" : "app");

        List<String> command = stringList(props.get("command"));
        if (!command.isEmpty()) {
            app.withCmd(command);
        }
        app.withEnv(envStrings(name, props, secrets, init));
        double memoryInGB = memoryInGB(props);
        if (memoryInGB > 0) {
            app.withMemoryBytes(Math.round(memoryInGB * 1024d * 1024d * 1024d));
        }
        applyVolumeMounts(app, group, props);
        applySecurityContext(app, props);

        try {
            lifecycleManager.removeIfExists(dockerName);
            String id = lifecycleManager.createAndStart(app.build()).containerId();
            if (record != null) {
                record.setContainerId(id);
                appendEvent(record.getEvents(), "Started", "Started container " + name, "Normal");
            }
            return id;
        } catch (RuntimeException e) {
            LOG.errorv("Failed to start container {0} in container group {1}: {2}",
                    name, group.getName(), e.getMessage());
            failContainer(record, "Failed to start container " + name + ": " + e.getMessage());
            throw new ContainerGroupRuntimeException(
                    "Failed to start container " + name + " in container group " + group.getName(),
                    e, false);
        }
    }

    private void pullImage(ContainerGroup group, ContainerRecord record, String image,
                           GroupSecrets secrets) {
        List<RegistryCredential> credentials =
                secrets == null ? List.of() : secrets.registryCredentials();
        if (record != null) {
            appendEvent(record.getEvents(), "Pulling", "pulling image \"" + image + "\"", "Normal");
        }
        RegistryCredential auth = registryAuthFor(image, credentials);
        try {
            imageCacheService.ensureImageExists(image,
                    auth == null ? null : auth.server(),
                    auth == null ? null : auth.username(),
                    auth == null ? null : auth.password());
            if (record != null) {
                appendEvent(record.getEvents(), "Pulled",
                        "Successfully pulled image \"" + image + "\"", "Normal");
            }
        } catch (RuntimeException e) {
            LOG.errorv("Failed to pull image {0} for container {1} in container group {2}: {3}",
                    image, record == null ? "?" : record.getName(), group.getName(), e.getMessage());
            if (record != null) {
                boolean alreadyFailed = record.getEvents().stream()
                        .anyMatch(event -> "Failed".equals(event.getName()));
                appendEvent(record.getEvents(), "Failed",
                        "Failed to pull image \"" + image + "\": " + e.getMessage(), "Warning");
                if (alreadyFailed) {
                    appendEvent(record.getEvents(), "BackOff",
                            "Back-off pulling image \"" + image + "\"", "Normal");
                }
                applyTerminated(record, 1, Instant.now());
            }
            throw new ContainerGroupRuntimeException(
                    "Failed to pull image " + image + " for container group " + group.getName(), e, false);
        }
    }

    /**
     * The first credential whose {@code server} matches the image's registry host, or null so
     * the configured {@code floci-az.docker.registry-credentials} apply instead. The credential
     * is handed to {@link ImageCacheService} as plain strings: no type from the docker-java API
     * crosses into a service package.
     */
    private static RegistryCredential registryAuthFor(String image,
                                                      List<RegistryCredential> credentials) {
        String host = ImageCacheService.extractRegistryHost(image);
        for (RegistryCredential credential : credentials) {
            if (credential.server() != null && credential.server().equalsIgnoreCase(host)) {
                return credential;
            }
        }
        return null;
    }

    // ── Teardown, stop, start, restart ─────────────────────────────────────────────────────

    /** App containers in reverse order, then the infra container, then volumes, then ports. */
    public void destroyGroup(ContainerGroup group) {
        List<ContainerRecord> records = new ArrayList<>(group.getContainers());
        for (int i = records.size() - 1; i >= 0; i--) {
            ContainerRecord record = records.get(i);
            if (record.getContainerId() == null) {
                continue;
            }
            try {
                lifecycleManager.stopAndRemove(record.getContainerId(), null);
            } catch (Exception e) {
                LOG.warnv("Error removing container {0} of container group {1}: {2}",
                        record.getName(), group.getName(), e.getMessage());
            }
            record.setContainerId(null);
        }
        if (group.getInfraContainerId() != null) {
            try {
                lifecycleManager.stopAndRemove(group.getInfraContainerId(), null);
            } catch (Exception e) {
                LOG.warnv("Error removing the infrastructure container of container group {0}: {1}",
                        group.getName(), e.getMessage());
            }
            group.setInfraContainerId(null);
        }
        removeVolumes(group);
        for (PortMapping mapping : group.getPortMappings()) {
            portAllocator.release(mapping.getHostPort());
        }
    }

    private void removeVolumes(ContainerGroup group) {
        Set<String> secretVolumes = new LinkedHashSet<>();
        Set<String> shareVolumes = new LinkedHashSet<>();
        for (Map<String, Object> volume : volumes(group)) {
            if (volume.get("secret") instanceof Map) {
                secretVolumes.add(volumeDockerName(group, volume));
            } else if (volume.get("azureFile") instanceof Map) {
                shareVolumes.add(volumeDockerName(group, volume));
            }
        }
        boolean prune = ContainerStorageHelper.shouldPruneVolume(config);
        for (String volumeName : group.getVolumeNames()) {
            // An azureFile volume may still be mounted by another group; a secret volume is
            // removed unconditionally so secret material never outlives its group.
            if (shareVolumes.contains(volumeName)) {
                continue;
            }
            if (!prune && !secretVolumes.contains(volumeName)) {
                continue;
            }
            try {
                lifecycleManager.removeVolume(volumeName);
            } catch (Exception e) {
                LOG.warnv("Error removing volume {0} of container group {1}: {2}",
                        volumeName, group.getName(), e.getMessage());
            }
        }
    }

    /** App containers in reverse array order, then the infra container. */
    public void stopGroup(ContainerGroup group) {
        int timeout = config.services().containerInstance().stopTimeoutSeconds();
        List<ContainerRecord> records = new ArrayList<>(group.getContainers());
        for (int i = records.size() - 1; i >= 0; i--) {
            stopOne(records.get(i).getContainerId(), timeout, group);
        }
        stopOne(group.getInfraContainerId(), timeout, group);
        appendEvent(group.getGroupEvents(), "Killing",
                "Stopping container group " + group.getName(), "Normal");
    }

    private void stopOne(String containerId, int timeout, ContainerGroup group) {
        if (containerId == null) {
            return;
        }
        try {
            lifecycleManager.stop(containerId, timeout);
        } catch (Exception e) {
            LOG.warnv("Error stopping a container of container group {0}: {1}",
                    group.getName(), e.getMessage());
        }
    }

    /** Infra container first, then app containers in array order. */
    public void startGroup(ContainerGroup group) {
        if (group.getInfraContainerId() != null) {
            lifecycleManager.start(group.getInfraContainerId());
        }
        for (ContainerRecord record : group.getContainers()) {
            if (record.isInit() || record.getContainerId() == null) {
                continue;
            }
            try {
                lifecycleManager.start(record.getContainerId());
                appendEvent(record.getEvents(), "Started",
                        "Started container " + record.getName(), "Normal");
            } catch (Exception e) {
                LOG.warnv("Error starting container {0} of container group {1}: {2}",
                        record.getName(), group.getName(), e.getMessage());
            }
        }
    }

    /**
     * A full ordered stop-all / start-all. {@code docker restart} on the infra container is
     * forbidden: it would return in a new namespace and strand every member in the old one.
     */
    public void restartGroup(ContainerGroup group) {
        stopGroup(group);
        startGroup(group);
    }

    /**
     * Transition G14 — the namespace owner died. Every member must be stopped, the infra
     * container recreated with the same name and bindings, and every member re-created against
     * the new namespace id: a member's recorded {@code NetworkMode} names the old container and
     * Docker resolves it at start time, so a plain restart would fail.
     */
    public void repairInfra(ContainerGroup group, GroupSecrets secrets) {
        int timeout = config.services().containerInstance().stopTimeoutSeconds();
        List<ContainerRecord> records = new ArrayList<>(group.getContainers());
        for (int i = records.size() - 1; i >= 0; i--) {
            ContainerRecord record = records.get(i);
            if (record.getContainerId() != null) {
                stopOne(record.getContainerId(), timeout, group);
                try {
                    lifecycleManager.stopAndRemove(record.getContainerId(), null);
                } catch (Exception e) {
                    LOG.warnv("Error removing container {0} while repairing container group {1}: {2}",
                            record.getName(), group.getName(), e.getMessage());
                }
                record.setContainerId(null);
            }
        }
        if (group.getInfraContainerId() != null) {
            try {
                lifecycleManager.stopAndRemove(group.getInfraContainerId(), null);
            } catch (Exception e) {
                LOG.warnv("Error removing the dead infrastructure container of container group {0}: {1}",
                        group.getName(), e.getMessage());
            }
        }
        startInfraContainer(group);
        resolveGroupIp(group);
        populateSecretVolumes(group, secrets);
        for (Map<String, Object> container : containerEntries(group, "containers")) {
            String name = String.valueOf(container.get("name"));
            ContainerRecord record = group.container(name);
            String id = createAppContainer(group, container, secrets, appName(group, name), record);
            ContainerRuntimeState state = lifecycleManager.inspectState(id);
            if (record != null) {
                record.rememberCurrentAsPrevious();
                record.setRestartCount(record.getRestartCount() + 1);
                record.setState(ContainerStateValue.RUNNING);
                record.setStartTime(state.startedAt() != null ? state.startedAt() : Instant.now());
                record.setDetailStatus("");
                record.setExitCode(null);
                record.setFinishTime(null);
            }
        }
        appendEvent(group.getGroupEvents(), "InfraRestarted",
                "The container group's network namespace was recreated; all containers were restarted.",
                "Warning");
    }

    /** Re-attaches to containers that outlived the emulator process, by name. */
    public void adopt(ContainerGroup group) {
        for (PortMapping mapping : group.getPortMappings()) {
            portAllocator.markReserved(mapping.getHostPort());
        }
        if (!lifecycleManager.inspectState(group.getInfraContainerId()).exists()) {
            lifecycleManager.findByName(infraName(group))
                    .ifPresent(container -> group.setInfraContainerId(container.getId()));
        }
        for (ContainerRecord record : group.getContainers()) {
            if (record.getContainerId() != null
                    && lifecycleManager.inspectState(record.getContainerId()).exists()) {
                continue;
            }
            String name = record.isInit()
                    ? initName(group, record.getName())
                    : appName(group, record.getName());
            lifecycleManager.findByName(name)
                    .ifPresent(container -> record.setContainerId(container.getId()));
        }
    }

    /** Restarts one container in place; used by the reconciler's restart-policy transitions. */
    public void startContainer(String containerId) {
        lifecycleManager.start(containerId);
    }

    // ── Logs ───────────────────────────────────────────────────────────────────────────────

    /**
     * Bounded log retrieval. A running container gets the 4 MB / 100 000-line caps, a stopped
     * one the 16 KB / 1 000-line caps, matching Azure's documented limits. Every failure yields
     * empty content rather than an error: a log read must never fail a request.
     */
    public String readLogs(ContainerGroup group, String containerName, Integer tail, boolean timestamps) {
        ContainerRecord record = group.container(containerName);
        if (record == null || record.getContainerId() == null) {
            return "";
        }
        EmulatorConfig.ContainerInstanceConfig aci = config.services().containerInstance();
        boolean running = lifecycleManager.isContainerRunning(record.getContainerId());
        long maxBytes = running ? aci.logMaxBytes() : aci.stoppedLogMaxBytes();
        int maxLines = running ? aci.logMaxLines() : aci.stoppedLogMaxLines();
        try {
            ContainerLifecycleManager.LogResult result = lifecycleManager.fetchLogs(
                    record.getContainerId(), tail, timestamps, maxBytes, maxLines, LOG_TIMEOUT);
            if (result.truncated()) {
                LOG.debugv("Log read for container {0} truncated at the configured cap", containerName);
            }
            return result.content();
        } catch (Exception e) {
            // A container removed out of band is the common case and gets its own message; the
            // absent/present split avoids naming a docker-java exception type in a service package.
            if (!lifecycleManager.inspectState(record.getContainerId()).exists()) {
                LOG.warnv("Container {0} of container group {1} no longer exists in Docker; "
                        + "returning empty logs", containerName, group.getName());
            } else {
                LOG.warnv("Failed to read logs for container {0} of container group {1}: {2}",
                        containerName, group.getName(), e.getMessage());
            }
            return "";
        }
    }

    // ── Rollback ───────────────────────────────────────────────────────────────────────────

    private void rollback(ContainerGroup group, List<String> startedContainerIds,
                          List<Integer> reservedPorts) {
        for (int i = startedContainerIds.size() - 1; i >= 0; i--) {
            try {
                lifecycleManager.stopAndRemove(startedContainerIds.get(i), null);
            } catch (Exception e) {
                LOG.warnv("Rollback: error removing container {0} of container group {1}: {2}",
                        startedContainerIds.get(i), group.getName(), e.getMessage());
            }
        }
        for (ContainerRecord record : group.getContainers()) {
            record.setContainerId(null);
        }
        if (group.getInfraContainerId() != null) {
            try {
                lifecycleManager.stopAndRemove(group.getInfraContainerId(), null);
            } catch (Exception e) {
                LOG.warnv("Rollback: error removing the infrastructure container of {0}: {1}",
                        group.getName(), e.getMessage());
            }
            group.setInfraContainerId(null);
        }
        // Named volumes are left in place: an azureFile volume may be shared, and an emptyDir
        // volume is cheap to leave behind.
        for (int port : reservedPorts) {
            portAllocator.release(port);
        }
    }

    // ── Naming ─────────────────────────────────────────────────────────────────────────────

    public String infraName(ContainerGroup group) {
        return ContainerStorageHelper.dockerName(config, "aci-" + group.getGroupId() + "-infra");
    }

    public String appName(ContainerGroup group, String containerName) {
        return ContainerStorageHelper.dockerName(config,
                "aci-" + group.getGroupId() + "-" + sanitize(containerName));
    }

    public String initName(ContainerGroup group, String containerName) {
        return ContainerStorageHelper.dockerName(config,
                "aci-" + group.getGroupId() + "-init-" + sanitize(containerName));
    }

    private String volumeDockerName(ContainerGroup group, Map<String, Object> volume) {
        String name = String.valueOf(volume.get("name"));
        if (volume.get("azureFile") instanceof Map<?, ?> azureFile) {
            return ContainerStorageHelper.dockerName(config, "aci-share-"
                    + sanitize(String.valueOf(azureFile.get("storageAccountName"))) + "-"
                    + sanitize(String.valueOf(azureFile.get("shareName"))));
        }
        String infix = volume.get("secret") instanceof Map ? "-sec-" : "-vol-";
        return ContainerStorageHelper.dockerName(config,
                "aci-" + group.getGroupId() + infix + sanitize(name));
    }

    /** Belt-and-braces normaliser; the names have already passed their validation regexes. */
    static String sanitize(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    // ── Spec assembly helpers ──────────────────────────────────────────────────────────────

    private void applyLabels(ContainerBuilder.Builder builder, ContainerGroup group,
                             String containerName, String role) {
        builder.withLabel("floci_aci_group", group.getName())
                .withLabel("floci_aci_group_id", group.getGroupId())
                .withLabel("floci_aci_subscription", group.getSubscriptionId())
                .withLabel("floci_aci_resource_group", group.getResourceGroup())
                .withLabel("floci_aci_container", containerName)
                .withLabel("floci_aci_role", role);
    }

    /** {@code NAME=value} strings; a secure value is inlined from the in-memory secret map only. */
    private List<String> envStrings(String containerName, Map<String, Object> props,
                                    GroupSecrets secrets, boolean init) {
        List<String> env = new ArrayList<>();
        Map<String, String> secure = secrets == null ? Map.of()
                : secrets.secureEnv().getOrDefault(secretEnvKey(containerName, init), Map.of());
        for (Object element : listOf(props.get("environmentVariables"))) {
            if (!(element instanceof Map<?, ?> entry)) {
                continue;
            }
            String name = String.valueOf(entry.get("name"));
            String value = secure.containsKey(name)
                    ? secure.get(name)
                    : (entry.get("value") == null ? "" : String.valueOf(entry.get("value")));
            env.add(name + "=" + value);
        }
        return env;
    }

    /** Init and main containers may share a name across the two arrays, so the key is qualified. */
    static String secretEnvKey(String containerName, boolean init) {
        return init ? "init:" + containerName : containerName;
    }

    private void applyVolumeMounts(ContainerBuilder.Builder builder, ContainerGroup group,
                                   Map<String, Object> props) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> volume : volumes(group)) {
            byName.put(String.valueOf(volume.get("name")), volume);
        }
        for (Object element : listOf(props.get("volumeMounts"))) {
            if (!(element instanceof Map<?, ?> mount)) {
                continue;
            }
            Map<String, Object> volume = byName.get(String.valueOf(mount.get("name")));
            if (volume == null) {
                continue;
            }
            boolean readOnly = volume.get("secret") instanceof Map
                    || Boolean.TRUE.equals(mount.get("readOnly"))
                    || (volume.get("azureFile") instanceof Map<?, ?> azureFile
                            && Boolean.TRUE.equals(azureFile.get("readOnly")));
            builder.withNamedVolume(volumeDockerName(group, volume),
                    String.valueOf(mount.get("mountPath")), readOnly);
        }
    }

    private void applySecurityContext(ContainerBuilder.Builder builder, Map<String, Object> props) {
        if (!(props.get("securityContext") instanceof Map<?, ?> context)) {
            return;
        }
        Object runAsUser = context.get("runAsUser");
        Object runAsGroup = context.get("runAsGroup");
        if (runAsUser instanceof Number user) {
            builder.withUser(runAsGroup instanceof Number gid
                    ? user.longValue() + ":" + gid.longValue()
                    : String.valueOf(user.longValue()));
        }
        builder.withPrivileged(Boolean.TRUE.equals(context.get("privileged")));
    }

    // ── Container-record state helpers ─────────────────────────────────────────────────────

    static void applyTerminated(ContainerRecord record, int exitCode, Instant finishTime) {
        if (record == null) {
            return;
        }
        record.setState(ContainerStateValue.TERMINATED);
        record.setExitCode(exitCode);
        record.setFinishTime(finishTime != null ? finishTime : Instant.now());
        record.setDetailStatus(exitCode == 0 ? "Completed" : "Error");
    }

    private static void failContainer(ContainerRecord record, String message) {
        if (record == null) {
            return;
        }
        appendEvent(record.getEvents(), "Failed", message, "Warning");
        applyTerminated(record, 1, Instant.now());
    }

    /**
     * Appends an event, or increments the count of an identical (name, message) pair and
     * refreshes its {@code lastTimestamp}. Each list is capped so a crash-looping container
     * cannot grow the stored record without bound.
     */
    static void appendEvent(List<ContainerInstanceModels.EventRecord> events, String name,
                            String message, String type) {
        Instant now = Instant.now();
        for (ContainerInstanceModels.EventRecord event : events) {
            if (event.getName().equals(name) && event.getMessage().equals(message)) {
                event.setCount(event.getCount() + 1);
                event.setLastTimestamp(now);
                return;
            }
        }
        events.add(new ContainerInstanceModels.EventRecord(name, message, type, now));
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    // ── Properties navigation ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> containerEntries(ContainerGroup group, String arrayName) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object element : listOf(group.getProperties().get(arrayName))) {
            if (element instanceof Map) {
                entries.add((Map<String, Object>) element);
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> containerProperties(Map<String, Object> container) {
        Object props = container.get("properties");
        return props instanceof Map ? (Map<String, Object>) props : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> volumes(ContainerGroup group) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object element : listOf(group.getProperties().get("volumes"))) {
            if (element instanceof Map) {
                entries.add((Map<String, Object>) element);
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groupPorts(ContainerGroup group) {
        Object ipAddress = group.getProperties().get("ipAddress");
        if (!(ipAddress instanceof Map)) {
            return List.of();
        }
        List<Map<String, Object>> ports = new ArrayList<>();
        for (Object element : listOf(((Map<String, Object>) ipAddress).get("ports"))) {
            if (element instanceof Map) {
                ports.add((Map<String, Object>) element);
            }
        }
        return ports;
    }

    private static Set<Integer> distinctContainerPorts(ContainerGroup group) {
        Set<Integer> ports = new LinkedHashSet<>();
        for (Map<String, Object> container : containerEntries(group, "containers")) {
            for (Object element : listOf(containerProperties(container).get("ports"))) {
                if (element instanceof Map<?, ?> port && port.get("port") instanceof Number number) {
                    ports.add(number.intValue());
                }
            }
        }
        return ports;
    }

    private static double memoryInGB(Map<String, Object> props) {
        if (props.get("resources") instanceof Map<?, ?> resources
                && resources.get("requests") instanceof Map<?, ?> requests
                && requests.get("memoryInGB") instanceof Number number) {
            return number.doubleValue();
        }
        return 0;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object element : listOf(value)) {
            out.add(String.valueOf(element));
        }
        return out;
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerGroupRuntimeException("Interrupted while waiting for an init container",
                    e, false);
        }
    }
}
