package io.floci.az.services.containerinstance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerRuntimeState;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerGroup;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerRecord;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.GroupSecrets;
import io.floci.az.services.containerinstance.ContainerInstanceModels.GroupStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.RestartPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The 3-second poller that owns restart decisions. Docker's own restart policies cannot be
 * used: Docker would restart an app container into a dead network namespace, so the emulator —
 * not the daemon — decides when a container comes back.
 *
 * <p>Each tick begins with a daemon liveness probe. Without it, a daemon outage would report
 * every container as absent, mark them all {@code Terminated}, and trigger a restart storm;
 * stale state is preferred to wrong state.</p>
 */
@ApplicationScoped
public class ContainerGroupReconciler {

    private static final Logger LOG = Logger.getLogger(ContainerGroupReconciler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final EmulatorConfig config;
    private final ContainerGroupRuntime runtime;
    private final StorageBackend<String, StoredObject> storage;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "aci-reconciler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean adopted = new AtomicBoolean(false);

    @Inject
    public ContainerGroupReconciler(EmulatorConfig config,
                                    ContainerGroupRuntime runtime,
                                    StorageFactory storageFactory) {
        this.config = config;
        this.runtime = runtime;
        // StorageFactory hands back the same backend instance for the same file, so this is the
        // handler's store, not a second copy of it.
        this.storage = storageFactory.create("containerinstance");
    }

    /** Starts the poller. Never throws: a reconciler that cannot start must not fail startup. */
    public void start() {
        try {
            poller.scheduleAtFixedRate(this::tick, 2,
                    Math.max(1, config.services().containerInstance().reconcileIntervalSeconds()),
                    TimeUnit.SECONDS);
            LOG.infov("Container-instance reconciler started (period {0}s)",
                    config.services().containerInstance().reconcileIntervalSeconds());
        } catch (Exception e) {
            LOG.errorv(e, "Failed to start the container-instance reconciler; "
                    + "container groups will not be reconciled");
        }
    }

    public void shutdown() {
        poller.shutdownNow();
    }

    void tick() {
        try {
            java.util.Optional<String> unreachable = runtime.daemonUnreachableReason();
            if (unreachable.isPresent()) {
                LOG.warnv("Docker daemon unreachable; skipping container-instance "
                        + "reconciliation tick: {0}", unreachable.get());
                return;
            }
            boolean firstTick = adopted.compareAndSet(false, true);
            for (ContainerGroup group : scanAll()) {
                java.util.concurrent.locks.ReentrantLock lock = runtime.lockFor(group.storageKey());
                if (!lock.tryLock()) {
                    // A request is mutating this group right now; reconciling a half-applied
                    // state would fight it. The next tick picks the group up.
                    LOG.debugv("Container group {0} is busy; deferring reconciliation",
                            group.getName());
                    continue;
                }
                try {
                    // Re-read under the lock: a request may have replaced the group between the
                    // scan and the lock, and writing back the scanned copy would undo it.
                    ContainerGroup current = read(group.storageKey()).orElse(null);
                    if (current == null) {
                        continue;
                    }
                    reconcile(current, firstTick);
                } catch (Exception e) {
                    LOG.warnv("Error reconciling container group {0}: {1}",
                            group.getName(), e.getMessage());
                } finally {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            LOG.error("Error in the container-instance reconciler tick", e);
        }
    }

    private void reconcile(ContainerGroup group, boolean firstTick) {
        if (group.isDegraded() || group.getGroupState() == GroupStateValue.STOPPED) {
            return;
        }
        String key = group.storageKey();
        if (firstTick) {
            runtime.adopt(group);
        }
        if (needsSecrets(group) && !runtime.hasSecrets(key)) {
            if (group.getGroupState() != GroupStateValue.FAILED) {
                LOG.warnv("Secrets for container group {0} were not retained across restart; "
                        + "its containers will not be recreated", group.getName());
                ContainerGroupRuntime.appendEvent(group.getGroupEvents(),
                        "SecretsUnavailableAfterRestart",
                        "Secret values for this container group were not retained across an "
                                + "emulator restart; its containers cannot be recreated.", "Warning");
                group.setGroupState(GroupStateValue.FAILED);
                write(key, group);
            }
            return;
        }

        boolean changed = firstTick;
        GroupSecrets secrets = runtime.secrets(key);

        ContainerRuntimeState infra = runtime.inspect(group.getInfraContainerId());
        if (!infra.exists() || !infra.running()) {
            LOG.warnv("Infrastructure container for container group {0} is not running; "
                    + "recreating the network namespace", group.getName());
            try {
                runtime.repairInfra(group, secrets);
                group.setGroupState(GroupStateValue.RUNNING);
            } catch (Exception e) {
                LOG.errorv("Failed to recreate the network namespace for container group {0}: {1}",
                        group.getName(), e.getMessage());
                ContainerGroupRuntime.appendEvent(group.getGroupEvents(), "Failed",
                        "Failed to recreate the network namespace: " + e.getMessage(), "Warning");
                group.setGroupState(GroupStateValue.FAILED);
            }
            write(key, group);
            return;
        }

        for (ContainerRecord record : group.getContainers()) {
            if (record.isInit()) {
                continue;
            }
            changed |= reconcileContainer(group, record);
        }

        GroupStateValue next = computeGroupState(group);
        if (next != group.getGroupState()) {
            group.setGroupState(next);
            changed = true;
        }
        if (changed) {
            write(key, group);
        }
    }

    /** Transitions C6, C7, C8, C9, C12, C14 and C15 for one app container. */
    private boolean reconcileContainer(ContainerGroup group, ContainerRecord record) {
        if (record.getContainerId() == null) {
            return false;
        }
        ContainerRuntimeState state = runtime.inspect(record.getContainerId());

        if (record.getState() == ContainerStateValue.RUNNING) {
            if (!state.exists()) {
                // C8 — removed out of band.
                ContainerGroupRuntime.appendEvent(record.getEvents(), "Failed",
                        "Container " + record.getName() + " disappeared from the Docker daemon",
                        "Warning");
                ContainerGroupRuntime.applyTerminated(record, 1, Instant.now());
                return true;
            }
            if (!state.running()) {
                // C6 / C7 — exited cleanly or with a failure.
                ContainerGroupRuntime.applyTerminated(record, state.exitCode(), state.finishedAt());
                return true;
            }
            return false;
        }

        if (record.getState() == ContainerStateValue.WAITING && state.running()) {
            // C14 — a pending restart came up.
            record.setState(ContainerStateValue.RUNNING);
            record.setStartTime(state.startedAt() != null ? state.startedAt() : Instant.now());
            record.setDetailStatus("");
            record.setExitCode(null);
            record.setFinishTime(null);
            return true;
        }

        if (record.getState() != ContainerStateValue.TERMINATED) {
            return false;
        }
        if (!shouldRestart(group.getRestartPolicy(), record.getExitCode())) {
            // C10, C11, C13 — the container has run to its policy's conclusion.
            return false;
        }
        return restart(group, record);
    }

    /** C9 / C12 followed immediately by C14 or C15. */
    private boolean restart(ContainerGroup group, ContainerRecord record) {
        record.rememberCurrentAsPrevious();
        record.setRestartCount(record.getRestartCount() + 1);
        record.setState(ContainerStateValue.WAITING);
        record.setStartTime(Instant.now());
        record.setDetailStatus("");
        record.setExitCode(null);
        record.setFinishTime(null);
        try {
            runtime.startContainer(record.getContainerId());
            ContainerRuntimeState state = runtime.inspect(record.getContainerId());
            record.setState(ContainerStateValue.RUNNING);
            record.setStartTime(state.startedAt() != null ? state.startedAt() : Instant.now());
            ContainerGroupRuntime.appendEvent(record.getEvents(), "Started",
                    "Started container " + record.getName(), "Normal");
        } catch (Exception e) {
            LOG.warnv("Failed to restart container {0} of container group {1}: {2}",
                    record.getName(), group.getName(), e.getMessage());
            ContainerGroupRuntime.appendEvent(record.getEvents(), "Failed",
                    "Failed to restart container " + record.getName() + ": " + e.getMessage(),
                    "Warning");
            ContainerGroupRuntime.applyTerminated(record, 1, Instant.now());
        }
        return true;
    }

    /**
     * {@code Always} restarts unconditionally, {@code OnFailure} only on a nonzero exit, and
     * {@code Never} never — deviation D12, where Azure's own behaviour is documented as
     * non-deterministic.
     */
    static boolean shouldRestart(RestartPolicy policy, Integer exitCode) {
        return switch (policy) {
            case ALWAYS -> true;
            case ON_FAILURE -> exitCode != null && exitCode != 0;
            case NEVER -> false;
        };
    }

    /** Transitions G2, G6, G7 and G8. */
    static GroupStateValue computeGroupState(ContainerGroup group) {
        boolean anyRunning = false;
        boolean allTerminated = true;
        boolean anyNonZero = false;
        boolean anyApp = false;
        for (ContainerRecord record : group.getContainers()) {
            if (record.isInit()) {
                continue;
            }
            anyApp = true;
            switch (record.getState()) {
                case RUNNING -> anyRunning = true;
                case TERMINATED -> anyNonZero |= record.getExitCode() != null && record.getExitCode() != 0;
                default -> { /* Waiting: a restart is pending, so the group is not terminal. */ }
            }
            if (record.getState() != ContainerStateValue.TERMINATED) {
                allTerminated = false;
            }
        }
        if (!anyApp || anyRunning) {
            return GroupStateValue.RUNNING;
        }
        if (!allTerminated) {
            return GroupStateValue.RUNNING;
        }
        if (group.getRestartPolicy() == RestartPolicy.ALWAYS) {
            return GroupStateValue.RUNNING;
        }
        // G8: OnFailure restarts a container that exited nonzero, so the group is not terminal
        // either — it is between restarts. Only Never lets a nonzero exit settle into Failed (G7).
        if (anyNonZero && group.getRestartPolicy() == RestartPolicy.ON_FAILURE) {
            return GroupStateValue.RUNNING;
        }
        return anyNonZero ? GroupStateValue.FAILED : GroupStateValue.SUCCEEDED;
    }

    /**
     * Whether a group declared write-only material it would need to re-create its containers.
     *
     * <p>The stored {@code properties} are already redacted, so the evidence is the shape the
     * redaction leaves behind: a {@code secret} volume comes back as {@code {}} but the key is
     * still there, and an environment variable that carried a {@code secureValue} comes back
     * with a name and neither {@code value} nor {@code secureValue}.</p>
     */
    static boolean needsSecrets(ContainerGroup group) {
        for (Object element : asList(group.getProperties().get("volumes"))) {
            if (element instanceof Map<?, ?> volume && volume.containsKey("secret")) {
                return true;
            }
        }
        for (String arrayName : List.of("containers", "initContainers")) {
            for (Object element : asList(group.getProperties().get(arrayName))) {
                if (!(element instanceof Map<?, ?> container)
                        || !(container.get("properties") instanceof Map<?, ?> props)) {
                    continue;
                }
                for (Object variable : asList(props.get("environmentVariables"))) {
                    if (variable instanceof Map<?, ?> entry
                            && entry.containsKey("name") && !entry.containsKey("value")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    // ── Storage ────────────────────────────────────────────────────────────────────────────

    private java.util.Optional<ContainerGroup> read(String key) {
        return storage.get(key).map(stored -> {
            try {
                return MAPPER.readValue(stored.data(), ContainerGroup.class);
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable container group {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private List<ContainerGroup> scanAll() {
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

    private void write(String key, ContainerGroup group) {
        try {
            storage.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(group), Map.of(),
                    Instant.now(), key));
        } catch (Exception e) {
            LOG.warnv("Failed to persist container group {0}: {1}", key, e.getMessage());
        }
    }
}
