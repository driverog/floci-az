package io.floci.az.services.containerinstance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Domain model for Azure Container Instances
 * ({@code Microsoft.ContainerInstance/containerGroups}).
 *
 * <p>The submitted {@code properties} block is stored verbatim so {@code GET} round-trips
 * faithfully for SDKs and Terraform — <em>with every secret value stripped before storage</em>.
 * Secrets live only in {@link GroupSecrets}, which is held in memory for the lifetime of the
 * process and never persisted, logged, or returned.</p>
 */
public class ContainerInstanceModels {

    /** {@code containers[].properties.instanceView.currentState.state}. */
    @RegisterForReflection
    public enum ContainerStateValue {
        WAITING("Waiting"),
        RUNNING("Running"),
        TERMINATED("Terminated");

        private final String wire;

        ContainerStateValue(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static ContainerStateValue fromWire(String value) {
            for (ContainerStateValue v : values()) {
                if (v.wire.equalsIgnoreCase(value)) {
                    return v;
                }
            }
            return WAITING;
        }
    }

    /** {@code properties.instanceView.state}. */
    @RegisterForReflection
    public enum GroupStateValue {
        PENDING("Pending"),
        RUNNING("Running"),
        SUCCEEDED("Succeeded"),
        STOPPED("Stopped"),
        FAILED("Failed");

        private final String wire;

        GroupStateValue(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static GroupStateValue fromWire(String value) {
            for (GroupStateValue v : values()) {
                if (v.wire.equalsIgnoreCase(value)) {
                    return v;
                }
            }
            return PENDING;
        }
    }

    /** {@code properties.restartPolicy}. */
    @RegisterForReflection
    public enum RestartPolicy {
        ALWAYS("Always"),
        ON_FAILURE("OnFailure"),
        NEVER("Never");

        private final String wire;

        RestartPolicy(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static RestartPolicy fromWire(String value) {
            for (RestartPolicy v : values()) {
                if (v.wire.equalsIgnoreCase(value)) {
                    return v;
                }
            }
            return ALWAYS;
        }
    }

    /** One entry of an {@code instanceView.events} array. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventRecord {
        private int count;
        private Instant firstTimestamp;
        private Instant lastTimestamp;
        private String name;
        private String message;
        private String type;

        public EventRecord() {
        }

        public EventRecord(String name, String message, String type, Instant when) {
            this.count = 1;
            this.firstTimestamp = when;
            this.lastTimestamp = when;
            this.name = name;
            this.message = message;
            this.type = type;
        }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public Instant getFirstTimestamp() { return firstTimestamp; }
        public void setFirstTimestamp(Instant firstTimestamp) { this.firstTimestamp = firstTimestamp; }

        public Instant getLastTimestamp() { return lastTimestamp; }
        public void setLastTimestamp(Instant lastTimestamp) { this.lastTimestamp = lastTimestamp; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    /** An Azure group port and the host port the infra container actually publishes it on. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortMapping {
        private int groupPort;
        private int hostPort;
        private String protocol;

        public PortMapping() {
        }

        public PortMapping(int groupPort, int hostPort, String protocol) {
            this.groupPort = groupPort;
            this.hostPort = hostPort;
            this.protocol = protocol;
        }

        public int getGroupPort() { return groupPort; }
        public void setGroupPort(int groupPort) { this.groupPort = groupPort; }

        public int getHostPort() { return hostPort; }
        public void setHostPort(int hostPort) { this.hostPort = hostPort; }

        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
    }

    /** Emulator-owned runtime state for one ACI container (main or init). */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContainerRecord {
        private String name;
        private String containerId;
        private ContainerStateValue state = ContainerStateValue.WAITING;
        private Instant startTime;
        private ContainerStateValue previousState;
        private Integer previousExitCode;
        private Instant previousStartTime;
        private Instant previousFinishTime;
        private Integer exitCode;
        private Instant finishTime;
        private String detailStatus = "";
        private int restartCount;
        private List<EventRecord> events = new ArrayList<>();
        /** True for an entry of {@code properties.initContainers[]}. */
        private boolean init;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }

        public ContainerStateValue getState() { return state; }
        public void setState(ContainerStateValue state) { this.state = state; }

        public Instant getStartTime() { return startTime; }
        public void setStartTime(Instant startTime) { this.startTime = startTime; }

        public ContainerStateValue getPreviousState() { return previousState; }
        public void setPreviousState(ContainerStateValue previousState) { this.previousState = previousState; }

        public Integer getPreviousExitCode() { return previousExitCode; }
        public void setPreviousExitCode(Integer previousExitCode) { this.previousExitCode = previousExitCode; }

        public Instant getPreviousStartTime() { return previousStartTime; }
        public void setPreviousStartTime(Instant previousStartTime) { this.previousStartTime = previousStartTime; }

        public Instant getPreviousFinishTime() { return previousFinishTime; }
        public void setPreviousFinishTime(Instant previousFinishTime) { this.previousFinishTime = previousFinishTime; }

        public Integer getExitCode() { return exitCode; }
        public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

        public Instant getFinishTime() { return finishTime; }
        public void setFinishTime(Instant finishTime) { this.finishTime = finishTime; }

        public String getDetailStatus() { return detailStatus; }
        public void setDetailStatus(String detailStatus) { this.detailStatus = detailStatus; }

        public int getRestartCount() { return restartCount; }
        public void setRestartCount(int restartCount) { this.restartCount = restartCount; }

        public List<EventRecord> getEvents() { return events; }
        public void setEvents(List<EventRecord> events) { this.events = events == null ? new ArrayList<>() : events; }

        public boolean isInit() { return init; }
        public void setInit(boolean init) { this.init = init; }

        /** Moves the current state into {@code previousState}, as transitions C9/C12/C18 require. */
        public void rememberCurrentAsPrevious() {
            previousState = state;
            previousStartTime = startTime;
            previousExitCode = exitCode;
            previousFinishTime = finishTime;
        }
    }

    /**
     * A persisted container group. Secrets are stripped from {@link #properties} before this
     * object ever reaches the storage backend.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContainerGroup {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String groupId;
        private Instant timeCreated;
        private Map<String, String> tags;
        private List<String> zones;
        private Map<String, Object> identity;
        private Map<String, Object> properties = new LinkedHashMap<>();
        private String provisioningState = "Succeeded";
        private GroupStateValue groupState = GroupStateValue.PENDING;
        private boolean degraded;
        /**
         * Whether the request that created this group carried any write-only material.
         *
         * <p>Recorded rather than inferred: {@code properties} is stored redacted, and a
         * redacted {@code secureValue} is indistinguishable on the wire from an environment
         * variable declared with a name and no value, which Azure allows. {@code null} marks a
         * record written before this field existed, where the shape is all there is to go on.</p>
         */
        private Boolean secretsDeclared;
        private String infraContainerId;
        private RestartPolicy restartPolicy = RestartPolicy.ALWAYS;
        private List<ContainerRecord> containers = new ArrayList<>();
        private List<PortMapping> portMappings = new ArrayList<>();
        private List<String> volumeNames = new ArrayList<>();
        private String ipAddress;
        private String fqdn;
        private List<EventRecord> groupEvents = new ArrayList<>();

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }

        public Instant getTimeCreated() { return timeCreated; }
        public void setTimeCreated(Instant timeCreated) { this.timeCreated = timeCreated; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public List<String> getZones() { return zones; }
        public void setZones(List<String> zones) { this.zones = zones; }

        public Map<String, Object> getIdentity() { return identity; }
        public void setIdentity(Map<String, Object> identity) { this.identity = identity; }

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) {
            this.properties = properties == null ? new LinkedHashMap<>() : properties;
        }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public GroupStateValue getGroupState() { return groupState; }
        public void setGroupState(GroupStateValue groupState) { this.groupState = groupState; }

        public boolean isDegraded() { return degraded; }
        public void setDegraded(boolean degraded) { this.degraded = degraded; }
        public Boolean getSecretsDeclared() { return secretsDeclared; }
        public void setSecretsDeclared(Boolean secretsDeclared) { this.secretsDeclared = secretsDeclared; }

        public String getInfraContainerId() { return infraContainerId; }
        public void setInfraContainerId(String infraContainerId) { this.infraContainerId = infraContainerId; }

        public RestartPolicy getRestartPolicy() { return restartPolicy; }
        public void setRestartPolicy(RestartPolicy restartPolicy) { this.restartPolicy = restartPolicy; }

        public List<ContainerRecord> getContainers() { return containers; }
        public void setContainers(List<ContainerRecord> containers) {
            this.containers = containers == null ? new ArrayList<>() : containers;
        }

        public List<PortMapping> getPortMappings() { return portMappings; }
        public void setPortMappings(List<PortMapping> portMappings) {
            this.portMappings = portMappings == null ? new ArrayList<>() : portMappings;
        }

        public List<String> getVolumeNames() { return volumeNames; }
        public void setVolumeNames(List<String> volumeNames) {
            this.volumeNames = volumeNames == null ? new ArrayList<>() : volumeNames;
        }

        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

        public String getFqdn() { return fqdn; }
        public void setFqdn(String fqdn) { this.fqdn = fqdn; }

        public List<EventRecord> getGroupEvents() { return groupEvents; }
        public void setGroupEvents(List<EventRecord> groupEvents) {
            this.groupEvents = groupEvents == null ? new ArrayList<>() : groupEvents;
        }

        /** ARM resource id. The {@code resourceGroups} segment always uses this exact casing. */
        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.ContainerInstance/containerGroups/" + name;
        }

        /** Storage key: {@code subscriptionId/resourceGroup/name}. */
        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }

        /** {@code location} lowercased with spaces removed, as the FQDN region label. */
        public String normalizedLocation() {
            return location == null ? "" : location.toLowerCase(Locale.ROOT).replace(" ", "");
        }

        public ContainerRecord container(String containerName) {
            for (ContainerRecord c : containers) {
                if (c.getName().equals(containerName)) {
                    return c;
                }
            }
            return null;
        }
    }

    /**
     * The write-only material of one container group: everything the runtime needs to create
     * containers and nothing that may be persisted, logged, or returned. Held in a
     * process-lifetime map inside {@code ContainerGroupRuntime}.
     */
    public static final class GroupSecrets {
        /** Container name (prefixed {@code init:} for init containers) → env var name → secure value. */
        private final Map<String, Map<String, String>> secureEnv = new LinkedHashMap<>();
        /** ACI volume name → secret key → Base64 value. */
        private final Map<String, Map<String, String>> secretVolumes = new LinkedHashMap<>();
        /** Registry credentials in request order. */
        private final List<RegistryCredential> registryCredentials = new ArrayList<>();

        public Map<String, Map<String, String>> secureEnv() { return secureEnv; }

        public Map<String, Map<String, String>> secretVolumes() { return secretVolumes; }

        public List<RegistryCredential> registryCredentials() { return registryCredentials; }

        public boolean isEmpty() {
            return secureEnv.isEmpty() && secretVolumes.isEmpty() && registryCredentials.isEmpty();
        }
    }

    /** A single {@code properties.imageRegistryCredentials[]} entry, password included. */
    public record RegistryCredential(String server, String username, String password) {
    }

    private ContainerInstanceModels() {
    }
}
