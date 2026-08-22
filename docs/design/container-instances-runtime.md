# Container Instances — Runtime and State

**Status:** Proposed architecture

**Last updated:** 2026-08-22

This document specifies how a container group becomes Docker containers, how those containers
become `instanceView` state, and what happens when things fail. It contains the results of the
two spikes that this design could not be written without: **bounded container log retrieval**
and **a shared network namespace across a group's containers**.

The architecture is in [Container Instances](container-instances.md). The wire contract is in
the [resource model](container-instances-resource-model.md).

## Decision summary

A container group maps onto a Docker **pod**: one *infrastructure container* that owns the
network namespace and every host port binding, plus one Docker container per ACI container,
each joined to the infra container with network mode `container:<infraContainerId>`. Init
containers run in the same namespace, sequentially, before the main containers.

The emulator — not Docker — owns restart decisions. A single `ScheduledExecutorService` polls
Docker every 3 seconds, observes exit codes, applies `restartPolicy`, and writes
`instanceView`.

Both mechanisms were proven against Docker 29.7.2 on Linux before being specified. The evidence
is in [the two spikes](#spike-shared-network-namespace).

## Goals

- Name every Docker mechanism used, with the exact existing repository method and its file:line.
- Specify creation, start, stop, restart, and teardown order precisely enough that an
  implementer never guesses.
- Specify the group and container state machines as transition tables covering every
  `restartPolicy`, container exit, image-pull failure, and Docker-daemon outage.
- Specify a bounded log-retrieval API that cannot exhaust the heap.

## Non-goals

- CPU throttling, GPU scheduling, virtual networks, websocket exec/attach — see the
  [non-goals of the main design](container-instances.md#non-goals).
- Cross-host scheduling. There is exactly one Docker daemon.

## Azure concept to Docker mechanism

One row per Azure concept. **Method** names the existing repository method to call, or marks
the mechanism as new work.

| Azure concept | Docker mechanism | Method to call |
|---|---|---|
| Container group | A pod: one infra container plus N app containers sharing its network namespace | `ContainerLifecycleManager.createAndStart(ContainerSpec)` — `src/main/java/io/floci/az/core/docker/ContainerLifecycleManager.java:80` |
| Shared network namespace (`localhost` between containers) | `HostConfig.NetworkMode = "container:<infraContainerId>"` | `ContainerBuilder.Builder.withNetworkMode(String)` — `src/main/java/io/floci/az/core/docker/ContainerBuilder.java:243`; applied by `ContainerLifecycleManager.buildHostConfig` at `.../ContainerLifecycleManager.java:643` |
| `containers[].properties.image` | Docker image, pulled once per process | `ImageCacheService.ensureImageExists(String)` — `src/main/java/io/floci/az/core/docker/ImageCacheService.java:40`, called from `ContainerLifecycleManager.create` at `.../ContainerLifecycleManager.java:104`. **New overload required** — see [image pull](#image-pull-and-registry-credentials) |
| `containers[].properties.command` | Docker `Cmd` | `ContainerBuilder.Builder.withCmd(List<String>)` — `.../ContainerBuilder.java:169` |
| `containers[].properties.environmentVariables[]` (`value` and `secureValue`) | Docker `Env`, as `NAME=value` strings | `ContainerBuilder.Builder.withEnv(List<String>)` — `.../ContainerBuilder.java:161` |
| `containers[].properties.resources.requests.memoryInGB` | Docker `HostConfig.Memory` in bytes | `ContainerBuilder.Builder.withMemoryBytes(long)` — `.../ContainerBuilder.java:209`, with `(long) Math.round(memoryInGB * 1024d * 1024d * 1024d)` |
| `containers[].properties.ports[].port` | Docker `ExposedPorts` on the **infra** container | `ContainerBuilder.Builder.withExposedPort(int)` — `.../ContainerBuilder.java:235` |
| `ipAddress.ports[].port` | Docker `HostConfig.PortBindings` on the **infra** container | `ContainerBuilder.Builder.withPortBinding(int containerPort, int hostPort)` — `.../ContainerBuilder.java:217` |
| Host port selection | Reuse the Azure port number when free, else allocate from a configured range | `PortAllocator.isPortFree(int)` — `src/main/java/io/floci/az/core/docker/PortAllocator.java:88`; `PortAllocator.allocate(int, int)` — `.../PortAllocator.java:33` |
| Host port release on delete | Return the port to the allocator | `PortAllocator.release(int)` — `.../PortAllocator.java:58` |
| `volumes[].emptyDir` | Docker named volume, read-write | `ContainerLifecycleManager.ensureVolume(String)` — `.../ContainerLifecycleManager.java:201`; `ContainerBuilder.Builder.withNamedVolume(String, String, boolean)` — `.../ContainerBuilder.java:286` |
| `volumes[].secret` | Docker named volume, populated over the archive API, mounted read-only | `ensureVolume` + `ContainerLifecycleManager.copyBytesToContainer(String, byte[], String)` — `.../ContainerLifecycleManager.java:556` |
| `volumes[].azureFile` | Docker named volume keyed by account and share | `ensureVolume` + `withNamedVolume` |
| `volumeMounts[]` | Docker `HostConfig.Mounts` entries of type `VOLUME` | `withNamedVolume(volumeName, mountPath, readOnly)` — `.../ContainerBuilder.java:286` |
| `securityContext.runAsUser` / `runAsGroup` | Docker `User` as `"uid"` or `"uid:gid"` | `ContainerBuilder.Builder.withUser(String)` — `.../ContainerBuilder.java:390` |
| `securityContext.privileged` | Docker `HostConfig.Privileged` | `ContainerBuilder.Builder.withPrivileged(boolean)` — `.../ContainerBuilder.java:372` |
| Docker network membership | The configured shared network, applied to the **infra** container only | `ContainerBuilder.Builder.withDockerNetwork(Optional<String>)` — `.../ContainerBuilder.java:252`, passed `config.services().dockerNetwork()` (`src/main/java/io/floci/az/config/EmulatorConfig.java:143`) |
| DNS resolution inside the group | floci-az's embedded DNS injected into the **infra** container | `ContainerBuilder.Builder.withEmbeddedDns()` — `.../ContainerBuilder.java:412` |
| Log rotation | Docker `json-file` driver with `max-size` / `max-file` from config | `ContainerBuilder.Builder.withLogRotation()` — `.../ContainerBuilder.java:343` |
| Resource attribution | Docker labels `floci=true`, `floci_emulator=floci-az`, plus ACI-specific labels | `ContainerBuilder.Builder.withLabel(String, String)` — `.../ContainerBuilder.java:318`; default labels are merged by `ContainerLifecycleManager.create` |
| Container naming | `floci-az-[{namespace}-]{token}` | `ContainerStorageHelper.dockerName(EmulatorConfig, String)` — `src/main/java/io/floci/az/core/docker/ContainerStorageHelper.java:51` |
| Stale-container cleanup before create | Remove by name, force | `ContainerLifecycleManager.removeIfExists(String)` — `.../ContainerLifecycleManager.java:418` |
| Group `stop` action | `docker stop` each container, keep them | `ContainerLifecycleManager.stop(String, int)` — `.../ContainerLifecycleManager.java:441` |
| Group `start` action | `docker start` each container | `ContainerLifecycleManager.start(String)` — `.../ContainerLifecycleManager.java:429` |
| Group `restart` action | Sequenced stop-then-start (**not** `docker restart`) | `stop` then `start`, in the order given in [ordering rules](#ordering-rules-restated) |
| Group delete | `docker stop` + `docker rm -f` each container | `ContainerLifecycleManager.stopAndRemove(String, Closeable)` — `.../ContainerLifecycleManager.java:168` |
| Volume pruning on delete | `docker volume rm` when policy allows | `ContainerLifecycleManager.removeVolume(String)` — `.../ContainerLifecycleManager.java:324`, guarded by `ContainerStorageHelper.shouldPruneVolume(EmulatorConfig)` — `.../ContainerStorageHelper.java:124` |
| Liveness observation | `docker inspect`, `State.Running` | `ContainerLifecycleManager.isContainerRunning(String)` — `.../ContainerLifecycleManager.java:473` |
| Exit code, start time, finish time observation | `docker inspect`, `State.ExitCode` / `State.StartedAt` / `State.FinishedAt` | **New work**: `ContainerLifecycleManager.inspectState(String)` — see [new core methods](#new-core-methods) |
| Container logs | `docker logs --tail N --timestamps` | **New work**: `ContainerLifecycleManager.fetchLogs(...)` — see [bounded log retrieval](#bounded-log-retrieval) |
| Adopting containers that survived an emulator restart | Look up by name | `ContainerLifecycleManager.findByName(String)` — `.../ContainerLifecycleManager.java:340` |

`ContainerGroupRuntime` calls only the methods in this table. It never imports
`com.github.dockerjava`.

## Naming scheme

`groupId` is 12 lowercase hexadecimal characters, produced once at create as
`UUID.randomUUID().toString().replace("-", "").substring(0, 12)` and never regenerated
(assumption A11). It is stable across `PUT` updates, `stop`/`start`/`restart`, and emulator
restarts.

`sanitize(s)` is `s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")` truncated to 40
characters. Container names have already passed the container-name regex, so `sanitize` is a
belt-and-braces normaliser, not the validation.

| Docker object | Name | Example |
|---|---|---|
| Infra container | `ContainerStorageHelper.dockerName(config, "aci-" + groupId + "-infra")` | `floci-az-aci-3f9a1c7b2e5d-infra` |
| App container | `ContainerStorageHelper.dockerName(config, "aci-" + groupId + "-" + sanitize(containerName))` | `floci-az-aci-3f9a1c7b2e5d-web` |
| Init container | `ContainerStorageHelper.dockerName(config, "aci-" + groupId + "-init-" + sanitize(containerName))` | `floci-az-aci-3f9a1c7b2e5d-init-setup` |
| `emptyDir` volume | `ContainerStorageHelper.dockerName(config, "aci-" + groupId + "-vol-" + sanitize(volumeName))` | `floci-az-aci-3f9a1c7b2e5d-vol-scratch-volume` |
| `secret` volume | `ContainerStorageHelper.dockerName(config, "aci-" + groupId + "-sec-" + sanitize(volumeName))` | `floci-az-aci-3f9a1c7b2e5d-sec-secret-volume` |
| `azureFile` volume | `ContainerStorageHelper.dockerName(config, "aci-share-" + sanitize(storageAccountName) + "-" + sanitize(shareName))` | `floci-az-aci-share-mystorage-myshare` |

`dockerName` prefixes `floci-az-` and inserts `floci-az.docker.resource-namespace` when
configured, so two emulator processes on one daemon do not collide
(`ContainerStorageHelper.java:51-56`).

`azureFile` volume names deliberately omit `groupId`: two groups mounting the same share see
the same data, which is the Azure behaviour.

**Collision handling.** Before every container create, call
`lifecycleManager.removeIfExists(name)` (`.../ContainerLifecycleManager.java:418`), as AGENTS.md
requires. Since `groupId` is unique per group and container names are unique within a group, a
collision only ever means a stale container from a previous run of the same group, which is
exactly what should be removed.

**Labels** applied to every ACI container, merged over the default `floci=true` /
`floci_emulator=floci-az` labels:

| Label | Value |
|---|---|
| `floci_aci_group` | the container group name |
| `floci_aci_group_id` | `groupId` |
| `floci_aci_subscription` | the subscription id |
| `floci_aci_resource_group` | the resource group name |
| `floci_aci_container` | the ACI container name, or `__infra__` for the infra container |
| `floci_aci_role` | `infra`, `init`, or `app` |

These make `docker ps --filter label=floci_aci_group_id=3f9a1c7b2e5d` a complete view of one
group, which the reconciler uses as a fallback when stored container ids are stale.

## Spike: shared network namespace

**Question.** Can two Docker containers share one network namespace so that one reaches the
other on `localhost`, with ports published from the namespace owner, and what happens when the
owner dies?

**Environment.** Docker 29.7.2, Linux (CachyOS), `alpine:3.20`.
`ContainerSpec.networkMode` is a free-form `String`
(`src/main/java/io/floci/az/core/docker/ContainerSpec.java:44`), and nothing in `src/` uses a
`container:` value today — verified by grepping `src/main/java` for `withNetworkMode`, whose
only callers are `ContainerLauncher.java:111` (a plain network name) and
`ContainerLifecycleManager.java:643` (the `HostConfig` application).

`ContainerBuilder` does apply its own network wiring: `withDockerNetwork`
(`.../ContainerBuilder.java:252`) resolves the service network, the global
`services.dockerNetwork()`, then the detected current-container network, and sets
`networkMode` from it. `withNetworkMode` (`.../ContainerBuilder.java:243`) sets the same field
directly and therefore **overrides** it, whichever is called last. The app-container spec calls
`withNetworkMode` and never calls `withDockerNetwork`; the infra-container spec calls
`withDockerNetwork` and never calls `withNetworkMode`.

There is one interaction to be aware of in `ContainerLifecycleManager.startCreated`
(`.../ContainerLifecycleManager.java:143-153`): after starting a container it calls
`connectToNetworkCmd` when `spec.networkMode()` is set **and** `spec.hasPortBindings()` **and**
floci-az is not itself running in a container. App containers have no port bindings, so this
branch never runs for them, and `container:<id>` is never passed to `connectToNetworkCmd`.
`buildHostConfig` (`.../ContainerLifecycleManager.java:640-644`) sets
`HostConfig.NetworkMode` when `networkMode` is set and there are no port bindings — which is
exactly the app-container case. The existing code therefore does the right thing for both
container roles with no change.

### What was run and what it proved

**1. Two containers, one namespace, `localhost` reachability.**

```bash
docker run -d --name owner -p 18080:8080 alpine:3.20 \
  sh -c 'while true; do echo -e "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nOWNER" | nc -l -p 8080; done'
OWNER_ID=$(docker inspect -f '{{.Id}}' owner)
docker run -d --name member --network "container:$OWNER_ID" alpine:3.20 \
  sh -c 'while true; do wget -q -O- http://localhost:8080; sleep 3; done'
docker logs member
```

Output: `OWNER`, repeatedly. The member reached the owner's listener on `localhost:8080`.
`curl http://127.0.0.1:18080` from the host returned `OWNER`.

**2. Ports are published from the owner, but the listener may live in a member.**

```bash
docker run -d --name owner2 -p 19090:9090 alpine:3.20 sh -c 'sleep 3600'
O=$(docker inspect -f '{{.Id}}' owner2)
docker run -d --name member2 --network "container:$O" alpine:3.20 \
  sh -c 'while true; do echo -e "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nMEMBER" | nc -l -p 9090; done'
curl -s http://127.0.0.1:19090
```

Output: `MEMBER`. The owner never listened on 9090; the member did, and the owner's published
binding carried the traffic. **This is what makes the design work**: the infra container can
publish every group port without knowing which container serves it.

**3. A member may not publish its own ports.**

```bash
docker run --rm --network "container:$O" -p 19091:9091 alpine:3.20 true
```

Output:

```text
docker: Error response from daemon: conflicting options: port publishing and the container type network mode
```

**All host port bindings must therefore live on the infra container.** This is a hard Docker
constraint, not a design preference.

**4. A member cannot start while the owner is stopped.**

```bash
docker stop owner2
docker run -d --network "container:$O" alpine:3.20 sleep 60
```

Output: the container is **created** (an id is printed) and then fails to **start**:

```text
docker: Error response from daemon: cannot join network namespace of a non running container: container owner2 is exited
```

With a nonexistent owner:

```text
docker: Error response from daemon: joining network namespace of container: No such container: deadbeefdeadbeef
```

The create/start split matters: `ContainerLifecycleManager.create`
(`.../ContainerLifecycleManager.java:101`) would succeed and `startCreated`
(`.../ContainerLifecycleManager.java:140`) would throw. `createAndStart`
(`.../ContainerLifecycleManager.java:80`) already removes the created container on a failed
start (`.../ContainerLifecycleManager.java:85-90`), so no orphan is left behind.

**5. When the owner dies, members keep running but become externally unreachable.**

```bash
docker run -d --name owner3 -p 19098:9098 alpine:3.20 sh -c 'sleep 3600'
O3=$(docker inspect -f '{{.Id}}' owner3)
docker run -d --name member3 --network "container:$O3" alpine:3.20 \
  sh -c 'while true; do echo -e "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nMEMBER" | nc -l -p 9098; done'
curl -s http://127.0.0.1:19098        # -> MEMBER
docker kill owner3
curl -s --max-time 4 http://127.0.0.1:19098; echo "curl rc=$?"
docker inspect -f '{{.State.Running}}' member3
```

Output: `curl rc=7` (connection refused) and `member3` still reports `true`. The namespace
survives because a member still holds it, but the owner's port bindings are gone.

**6. Restarting the owner while members run strands them; a full ordered cycle works.**

Restarting the owner in place, with members still running, put the owner in a **new** namespace
while the members stayed in the old one — the published port then reached nothing.

The ordered cycle works:

```bash
docker stop -t 1 member4        # members first
docker stop -t 1 owner4         # owner last
docker start owner4             # owner first
docker start member4            # members after
curl -s http://127.0.0.1:19099  # -> MEMBER
```

### What the spike settles

| Rule | Proven by |
|---|---|
| The pod pattern works on this Docker version; `localhost` reachability is real | Test 1 |
| Host port bindings must be on the infra container, and only there | Tests 2 and 3 |
| The infra container must be started before any member is started | Test 4 |
| The infra container must be stopped after every member is stopped | Test 6 |
| `docker restart` on the infra container is forbidden; a restart must be a sequenced stop-all / start-all | Test 6 |
| An infra container that dies leaves members running but externally unreachable — the group is broken and must be repaired, not merely reported | Test 5 |
| Docker's own restart policies cannot be used, because Docker would restart an app container into a dead namespace | Tests 4, 5 and 6 combined |

The spike containers and images were created only in the local Docker daemon; every
`aci-spike-*` container was removed afterwards and no repository file was touched.

## Container group creation

`ContainerGroupRuntime.createGroup(ContainerGroup group, GroupSecrets secrets)`.
Every step is ordered; a failure at any step runs the [rollback](#creation-failure-and-rollback).

1. **Resolve host ports.** For each `ipAddress.ports[i]`, in array order:
   `hostPort = (azurePort >= 1024 && portAllocator.isPortFree(azurePort)) ? azurePort
   : portAllocator.allocate(basePort, maxPort)`. Record the pair in `group.portMappings`.
   When `azurePort` is chosen directly, also call `portAllocator.markReserved(azurePort)`
   (`src/main/java/io/floci/az/core/docker/PortAllocator.java:50`) so a concurrent group cannot
   take it.
2. **Ensure volumes.** For each `properties.volumes[i]`, compute the volume name from the
   [naming scheme](#naming-scheme) and call `lifecycleManager.ensureVolume(name)`
   (`.../ContainerLifecycleManager.java:201`). Record every name in `group.volumeNames`.
   `azureFile` volumes also append an `AzureFileEmulated` group event.
3. **Build the infra spec.**

   ```java
   ContainerBuilder.Builder infra = containerBuilder.newContainer(config.infraImage())   // alpine:3.20
           .withName(infraName)
           .withCmd(List.of("tail", "-f", "/dev/null"))
           .withDockerNetwork(config.services().dockerNetwork())
           .withEmbeddedDns()
           .withHostDockerInternalOnLinux()
           .withLogRotation()
           .withLabel("floci_aci_group", group.name())
           .withLabel("floci_aci_group_id", group.groupId())
           .withLabel("floci_aci_subscription", group.subscriptionId())
           .withLabel("floci_aci_resource_group", group.resourceGroup())
           .withLabel("floci_aci_container", "__infra__")
           .withLabel("floci_aci_role", "infra");
   for (PortMapping m : group.portMappings()) {
       infra.withPortBinding(m.groupPort(), m.hostPort());
   }
   // every container port declared by any container, so intra-namespace ports are visible
   for (int p : distinctContainerPorts(group)) {
       infra.withExposedPort(p);
   }
   // secret volumes are mounted here so they can be populated after start
   for (SecretVolumeSpec s : secretVolumes(group)) {
       infra.withNamedVolume(s.volumeName(), "/floci-secrets/" + s.aciName(), false);
   }
   ```

   `tail -f /dev/null` is the same keep-alive `VmContainerManager` uses
   (`src/main/java/io/floci/az/services/vm/VmContainerManager.java:42`).
4. **Remove any stale infra container** — `lifecycleManager.removeIfExists(infraName)`.
5. **Create and start the infra container** — `lifecycleManager.createAndStart(infra.build())`.
   Store `info.containerId()` on `group.infraContainerId`. Append one `PortMapped` group event
   per mapping.
6. **Resolve the group IP.** When `containerDetector.isRunningInContainer()` is `false`,
   `group.ipAddress = "127.0.0.1"`. Otherwise inspect the infra container and use its IP on the
   configured Docker network — `info.getEndpoint(port).host()` for any published port, falling
   back to `"127.0.0.1"` when there are no published ports.
7. **Populate secret volumes.** For each `secret` volume and each key/value pair, Base64-decode
   the value and call
   `lifecycleManager.copyBytesToContainer(infraContainerId, decodedBytes, "/floci-secrets/" + aciVolumeName + "/" + key)`
   (`.../ContainerLifecycleManager.java:556`). The infra container is running, so its volume
   mounts are live and the bytes land inside the named volume rather than in the container
   layer. `copyBytesToContainer` creates the directory tree.
8. **Run init containers, in array order.** For each `properties.initContainers[i]` that
   declares an `image`: build the spec exactly as an app container (step 9), create and start
   it, then poll `lifecycleManager.inspectState(id)` every 500 ms until `running` is `false`,
   for at most 300 seconds. A nonzero exit code, or the timeout, fails the group with a
   `Failed` event on that init container and the group `provisioningState: "Failed"`. An init
   container is never restarted. Init containers are **not** removed after they exit: their
   logs stay retrievable through the logs route.
9. **Create and start each app container, in `properties.containers[]` array order.**

   ```java
   ContainerBuilder.Builder app = containerBuilder.newContainer(c.image())
           .withName(appName(group, c))
           .withNetworkMode("container:" + group.infraContainerId())
           .withLogRotation()
           .withLabel("floci_aci_group", group.name())
           .withLabel("floci_aci_group_id", group.groupId())
           .withLabel("floci_aci_subscription", group.subscriptionId())
           .withLabel("floci_aci_resource_group", group.resourceGroup())
           .withLabel("floci_aci_container", c.name())
           .withLabel("floci_aci_role", "app");
   if (!c.command().isEmpty()) {
       app.withCmd(c.command());
   }
   app.withEnv(envStrings(c, secrets));                       // "NAME=value", secure values inlined
   app.withMemoryBytes(Math.round(c.memoryInGB() * 1024d * 1024d * 1024d));
   for (VolumeMountSpec m : c.volumeMounts()) {
       app.withNamedVolume(volumeName(group, m.name()), m.mountPath(), m.readOnlyEffective());
   }
   if (c.runAsUser() != null) {
       app.withUser(c.runAsGroup() != null ? c.runAsUser() + ":" + c.runAsGroup()
                                           : String.valueOf(c.runAsUser()));
   }
   app.withPrivileged(c.privileged());
   ```

   `m.readOnlyEffective()` is `true` for every `secret` volume regardless of the request's
   `readOnly`, and `m.readOnly()` otherwise.

   No `withPortBinding`, no `withExposedPort`, no `withDockerNetwork`, no `withEmbeddedDns` on
   an app container — spike test 3 proves port publishing is rejected, and the other three are
   properties of the namespace, which the app container inherits.

   Call `lifecycleManager.removeIfExists(appName)` then
   `lifecycleManager.createAndStart(app.build())`; record `info.containerId()` and append a
   `Started` event.
10. **Set terminal state.** `provisioningState: "Succeeded"`, `instanceView.state: "Running"`,
    every container `currentState.state: "Running"` with `startTime` from
    `inspectState(id).startedAt()`.

### Creation failure and rollback

Any exception in steps 1–9 is caught by `createGroup`, which then:

1. Stops and removes every app and init container it created, in **reverse** creation order,
   with `lifecycleManager.stopAndRemove(id, null)`.
2. Stops and removes the infra container, if created.
3. Leaves named volumes in place (they may be shared `azureFile` volumes, and `emptyDir`
   volumes are cheap).
4. Releases every host port it reserved with `portAllocator.release(hostPort)`.
5. Rethrows a `ContainerGroupRuntimeException` carrying the failing step, the container name,
   and the cause.

`ContainerInstanceHandler` catches it and produces:

- when the cause is an image-pull failure: `provisioningState: "Failed"`, an `instanceView`
  with a `Failed` event on the offending container, and an HTTP `201`/`200` — Azure also
  returns success and reports the failure through `provisioningState`, as its
  `ContainerGroupsGet_Failed` example shows;
- when the cause is a Docker-daemon connectivity failure: the
  [graceful-degradation contract](container-instances.md#graceful-degradation-contract) applies
  and the group is marked degraded.

There is no path that returns HTTP `500`.

## Image pull and registry credentials

`ContainerLifecycleManager.create` already calls
`imageCacheService.ensureImageExists(spec.image())` before creating a container
(`src/main/java/io/floci/az/core/docker/ImageCacheService.java:40` via
`.../ContainerLifecycleManager.java:104`). That method resolves credentials only from
`floci-az.docker.registry-credentials` through the private `resolveAuth`
(`.../ImageCacheService.java:132-144`), which matches the image's registry host against
configured entries and otherwise returns an empty `AuthConfig`.

Per-request `imageRegistryCredentials` cannot flow through that path, so a new overload is
required.

### New method — `ImageCacheService.ensureImageExists(String, AuthConfig)`

```java
/**
 * Ensures {@code imageUri} is present locally, pulling it with the supplied credentials when
 * it is not. Behaves exactly like {@link #ensureImageExists(String)} — pull-once per process,
 * transient-failure retry, five-minute pull timeout — except that {@code auth}, when non-null,
 * replaces the credentials {@code resolveAuth} would have derived from configuration.
 *
 * @param imageUri the image reference, for example {@code myregistry.example.com/app:1.0}
 * @param auth     registry credentials to use for this pull, or {@code null} to fall back to
 *                 the configured {@code floci-az.docker.registry-credentials}
 */
public void ensureImageExists(String imageUri, AuthConfig auth)
```

The existing single-argument method becomes `ensureImageExists(imageUri, null)`. The
`pulledImages` set and per-image lock are unchanged, so a second group requesting the same image
does not re-pull it and does not need credentials.

### Passing credentials through to the pull

`ContainerSpec` has no credential field and gains none — adding one would put a secret into an
immutable record that is logged in `ContainerLifecycleManager.create`
(`.../ContainerLifecycleManager.java:102` logs `spec.image()` and `spec.name()`, but the record
would still be a secret carrier). Instead:

`ContainerGroupRuntime` pulls **before** building the spec:

```java
AuthConfig auth = registryAuthFor(container.image(), secrets.registryCredentials());
imageCacheService.ensureImageExists(container.image(), auth);   // may throw
// ... then build the spec and call lifecycleManager.createAndStart(spec)
```

`ContainerLifecycleManager.create`'s own `ensureImageExists(spec.image())` then finds the image
in `pulledImages` and returns immediately (`.../ImageCacheService.java:41-43`).

`registryAuthFor(imageUri, credentials)`:

1. Compute the registry host with the same rule `ImageCacheService.extractRegistryHost` uses
   (`.../ImageCacheService.java:146-149`): the first `/`-separated segment, but only when it
   contains a `.` or a `:`; otherwise the empty string, meaning Docker Hub.
2. Return the first `ImageRegistryCredential` whose `server` equals that host, case-insensitively,
   as `new AuthConfig().withUsername(cred.username()).withPassword(cred.password()).withRegistryAddress(cred.server())`.
3. Return `null` when no credential matches, so the configured credentials apply.

Pulling from the emulated Azure Container Registry works without special handling: `AcrHandler`
returns a `loginServer` of `localhost:{port}/{registryName}` in non-mocked mode
(`src/main/java/io/floci/az/services/acr/AcrRegistryManager.java:107-110`), which
`extractRegistryHost` recognises as a registry host because it contains a `:`, and the backing
registry runs anonymously so no credential is needed.

### Events emitted around the pull

| When | Event `name` | `type` | `message` |
|---|---|---|---|
| Before the pull, when the image is not already local | `Pulling` | `Normal` | `pulling image "{image}"` |
| After a successful pull, or when the image was already local | `Pulled` | `Normal` | `Successfully pulled image "{image}"` |
| After a failed pull | `Failed` | `Warning` | `Failed to pull image "{image}": {exceptionMessage}` |
| After a failed pull, when a previous attempt for the same group already failed | `BackOff` | `Normal` | `Back-off pulling image "{image}"` |

These four names and message shapes match what the ACI troubleshooting documentation shows in
`az container show` output.

## Host port allocation and release

| Situation | Behaviour |
|---|---|
| `ipAddress.ports[i].port` is ≥ 1024 and `portAllocator.isPortFree(port)` returns `true` | Bind host port = Azure port. Call `portAllocator.markReserved(port)`. |
| Otherwise | `hostPort = portAllocator.allocate(config.basePort(), config.maxPort())` — a synchronised scan that both probes and reserves (`src/main/java/io/floci/az/core/docker/PortAllocator.java:33-43`). |
| The range is exhausted | `allocate` throws `RuntimeException("No free port available in range 8500-8599")`. `createGroup` rolls back and the group becomes `provisioningState: "Failed"` with a group event `Failed` / `Warning` / `No free host port available in range {base}-{max} for container group port {azurePort}`. |
| Group deleted, or creation rolled back | `portAllocator.release(hostPort)` for every mapping (`.../PortAllocator.java:58`). |
| Group stopped | Ports stay reserved. The infra container keeps its bindings; a later `start` reuses them. |
| Emulator restarts with containers still running | The reconciler's adoption pass calls `portAllocator.markReserved(hostPort)` for every mapping it reads back from storage, so the allocator does not hand the same port to a new group. |

Container Instances is the first production caller of `PortAllocator.release(int)` and
`PortAllocator.markReserved(int)`; both exist but are unused on `upstream/main`.

## IP address, FQDN, and what "resolvable" means

| Field | Value | Reachable? |
|---|---|---|
| `ipAddress.ip` when floci-az runs on the host | `127.0.0.1` | Yes, at the **host** port from `portMappings`, not necessarily at `ipAddress.ports[].port` |
| `ipAddress.ip` when floci-az runs inside Docker | The infra container's IP on `floci-az.services.docker-network` | Yes, at the container port, from any container on that network |
| `ipAddress.fqdn` | `{dnsNameLabel}.{normalizedLocation}.azurecontainer.io` | **No** — cosmetic |

`EmbeddedDnsServer` runs only when floci-az itself is inside Docker
(`src/main/java/io/floci/az/core/dns/EmbeddedDnsServer.java:74-76`) and answers **one** address
for every name it claims: `resolveARecord` returns `Optional.of(myIp)` — floci-az's own
container IP — for any name matching a configured suffix
(`.../EmbeddedDnsServer.java:159-164`), and forwards everything else upstream.
`matchesSuffix` (`.../EmbeddedDnsServer.java:145-157`) matches a name equal to a suffix or
ending in `"." + suffix`.

So adding `azurecontainer.io` to `floci-az.dns.extra-suffixes`
(`src/main/java/io/floci/az/config/EmulatorConfig.java:438`) would make
`floci-demo-group.eastus.azurecontainer.io` resolve — **to floci-az, not to the container
group**. That is worse than not resolving, because a client would then reach floci-az's HTTP
port instead of the container. floci-az therefore does **not** register the suffix, and the FQDN
is documented as cosmetic (deviation D3).

Making the FQDN real would require a per-name record table in `EmbeddedDnsServer` —
concretely, replacing the `Optional.of(myIp)` in `resolveARecord` with a lookup in a
`Map<String, String>` that services can register into. That is a core change with more than one
consumer and is deferred to the [roadmap](container-instances-roadmap.md#deferred).

**What a client can do today:** read `instanceView.events` for the `PortMapped` entry and
connect to `127.0.0.1:{hostPort}`, or when floci-az runs in Docker connect to
`ipAddress.ip:{groupPort}` from the shared Docker network.

## Volume backing

| ACI volume | Docker backing | Mount mode | Populated |
|---|---|---|---|
| `emptyDir` | Named volume `floci-az-[ns-]aci-{groupId}-vol-{name}` | Read-write, or read-only when the mount says so | Empty at creation |
| `secret` | Named volume `floci-az-[ns-]aci-{groupId}-sec-{name}` | **Always read-only** in app containers | Populated on the infra container after it starts, via `copyBytesToContainer`, one file per key with the Base64-decoded value |
| `azureFile` | Named volume `floci-az-aci-share-{account}-{share}` | Read-write, or read-only when `azureFile.readOnly` or the mount says so | Whatever a previous group left there |

A named volume is shared across every container in the group that mounts it, which is what
`emptyDir` promises: *"The `emptyDir` volume provides a writable directory accessible to each
container in a container group."*

Azure backs secret volumes with tmpfs so *"their contents are never written to nonvolatile
storage."* floci-az uses a named volume instead, because a tmpfs mount cannot be populated
through the Docker archive API before the app containers start. The consequence — secret
contents live in the Docker volume until the group is deleted — is recorded as a security note
in the [main design](container-instances.md#security-boundaries-and-secret-handling).
On group delete, secret volumes are removed **unconditionally**, ignoring
`ContainerStorageHelper.shouldPruneVolume` (`.../ContainerStorageHelper.java:124`), so secret
material never outlives its group.

`emptyDir` volumes are removed on group delete only when `shouldPruneVolume` returns `true`,
which it does in `memory` storage mode or when `floci-az.storage.prune-volumes-on-delete` is
set. `azureFile` volumes are **never** removed, because another group may still mount the same
share.

## Bounded log retrieval

### Spike: `logContainerCmd`

**Question.** Nothing in the repository uses docker-java's `logContainerCmd` — confirmed by
`grep -rn "logContainerCmd" src/`, which returns no matches on `upstream/main`. What is the
exact call shape, does it work against a stopped container, how do stdout and stderr interleave,
and can the stream be cut off without buffering everything?

**Environment.** docker-java `3.7.1` — the version `pom.xml` declares for
`docker-java-api`, `docker-java-core`, `docker-java-transport`, and
`docker-java-transport-httpclient5`. Docker 29.7.2 on Linux, `alpine:3.20`.

**Interface, read from `docker-java-api-3.7.1.jar`.** `LogContainerCmd` extends
`AsyncDockerCmd<LogContainerCmd, Frame>` and declares:

```java
LogContainerCmd withContainerId(String containerId);
LogContainerCmd withFollowStream(Boolean followStream);
LogContainerCmd withTimestamps(Boolean timestamps);
LogContainerCmd withStdOut(Boolean stdout);
LogContainerCmd withStdErr(Boolean stderr);
LogContainerCmd withTailAll();
LogContainerCmd withTail(Integer tail);
LogContainerCmd withSince(Integer since);
LogContainerCmd withUntil(Integer until);
```

**Fixture.** A container that writes five interleaved stdout/stderr line pairs then exits `3`:

```bash
docker run -d --name fixture alpine:3.20 \
  sh -c 'for i in 1 2 3 4 5; do echo "out-$i"; echo "err-$i" 1>&2; sleep 0.2; done; exit 3'
```

**Results**, from a Java program run against the live daemon with docker-java 3.7.1:

| Call | Result |
|---|---|
| `withStdOut(true).withStdErr(true).withFollowStream(false).withTailAll()` on the **exited** container | 10 frames, full log returned. Logs of a stopped container are retrievable. |
| `.withTail(3).withTimestamps(true)` | Exactly 3 frames: `2026-08-22T04:51:48.488474892Z err-4`, `...690213101Z out-5`, `...690249198Z err-5`. `tail` counts lines across the **merged** stream, and the timestamp is RFC 3339 with nanosecond precision, a `Z` suffix, and a single space before the line. |
| `Frame.getStreamType()` | Alternated `STDOUT`, `STDERR`, `STDOUT`, … — one frame per line, in chronological order. `com.github.dockerjava.api.model.StreamType` distinguishes them. |
| Byte cap: close the callback from `onNext` once 30 bytes are buffered | The stream stopped after 6 frames instead of 10; 30 bytes were returned. |
| Line cap: close the callback from `onNext` once 4 lines are buffered | The stream stopped after 5 frames; 24 bytes were returned. |
| `logContainerCmd("no-such-container-xyz")` | `com.github.dockerjava.api.exception.NotFoundException` with message `Status 404: {"message":"No such container: no-such-container-xyz"}` |

The byte and line caps are the important result: **calling `close()` on the `ResultCallback`
from inside `onNext` terminates the stream**, so the implementation never buffers more than the
cap regardless of how large the container's log is.

The equivalent behaviour was cross-checked with the CLI: `docker logs --tail 3`,
`docker logs --timestamps`, `docker logs` on a stopped container (works) and on a removed
container (`Error response from daemon: No such container: fixture`).

The spike program lived only in the scratch directory and was never added to the repository;
every spike container was removed afterwards.

### New method — `ContainerLifecycleManager.fetchLogs`

Added to `ContainerLifecycleManager` so no service calls `dockerClient` directly. It is
general-purpose: nothing in it is ACI-specific.

```java
/**
 * Result of a bounded log read.
 *
 * @param content   the log text, UTF-8 decoded, newline-terminated per line
 * @param truncated true when the read stopped because a cap was reached rather than because
 *                  the container's log ended
 */
public record LogResult(String content, boolean truncated) {}

/**
 * Reads a container's logs without following, stopping as soon as either cap is reached.
 *
 * <p>Both streams are read and interleaved in the order the daemon recorded them, matching
 * {@code docker logs}. The read never buffers more than {@code maxBytes} of log text: the
 * result callback is closed from within its own {@code onNext}, which terminates the
 * underlying HTTP stream, so a container producing gigabytes of output costs a bounded
 * amount of heap.</p>
 *
 * <p>A stopped container's logs are still available; a removed container has none. Docker
 * retains logs subject to the container's log-driver rotation settings — floci-az containers
 * use the {@code json-file} driver with {@code max-size} and {@code max-file} from
 * {@code floci-az.docker.log-max-size} / {@code log-max-file}.</p>
 *
 * @param containerId the container id or name
 * @param tail        number of lines to read from the end of the merged stream, or
 *                    {@code null} for all available lines
 * @param timestamps  when true, each line is prefixed with an RFC 3339 nanosecond UTC
 *                    timestamp and a single space, exactly as {@code docker logs --timestamps}
 *                    emits it
 * @param maxBytes    hard cap on the number of UTF-8 characters accumulated; the read stops
 *                    at the first frame that would exceed it, and that frame is discarded
 * @param maxLines    hard cap on the number of newline characters accumulated; the read stops
 *                    at the first frame that would exceed it, and that frame is discarded
 * @param timeout     maximum time to wait for the stream to complete
 * @return the bounded log content and whether a cap cut it short
 * @throws com.github.dockerjava.api.exception.NotFoundException when no such container exists
 */
public LogResult fetchLogs(String containerId, Integer tail, boolean timestamps,
                           long maxBytes, int maxLines, java.time.Duration timeout)
```

Reference implementation, which is the spike code with the caps parameterised:

```java
public LogResult fetchLogs(String containerId, Integer tail, boolean timestamps,
                           long maxBytes, int maxLines, Duration timeout) {
    StringBuilder sink = new StringBuilder();
    AtomicInteger lines = new AtomicInteger();
    AtomicBoolean truncated = new AtomicBoolean(false);

    LogContainerCmd cmd = dockerClient.logContainerCmd(containerId)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(false)
            .withTimestamps(timestamps);
    if (tail == null) {
        cmd.withTailAll();
    } else {
        cmd.withTail(tail);
    }

    ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
        @Override
        public void onNext(Frame frame) {
            if (truncated.get()) {
                return;
            }
            String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
            long newlines = text.chars().filter(c -> c == '\n').count();
            if (sink.length() + text.length() > maxBytes || lines.get() + newlines > maxLines) {
                truncated.set(true);
                try {
                    close();
                } catch (IOException e) {
                    LOG.debugv("Closing log stream for {0} after cap: {1}",
                            containerId, e.getMessage());
                }
                return;
            }
            sink.append(text);
            lines.addAndGet((int) newlines);
        }
    };

    try {
        cmd.exec(callback);
        callback.awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted reading logs for container " + containerId, ie);
    } finally {
        try {
            callback.close();
        } catch (IOException e) {
            LOG.debugv("Error closing log callback for {0}: {1}", containerId, e.getMessage());
        }
    }
    return new LogResult(sink.toString(), truncated.get());
}
```

`Frame`, `StreamType`, `LogContainerCmd`, and `ResultCallback` all come from
`com.github.dockerjava.api`, which `ContainerLifecycleManager` already imports
(`Frame` at `src/main/java/io/floci/az/core/docker/ContainerLifecycleManager.java:15`,
`ResultCallback` at line 5).

**Stdout and stderr are always both requested and are never separated.** ACI's `Logs.content`
is a single string, and `docker logs` merges the streams in daemon-recorded order, which is the
closest thing to what a user sees on a terminal.

### How the handler uses it

`ContainerGroupRuntime.readLogs(group, containerName, tail, timestamps)`:

| Condition | Behaviour |
|---|---|
| Group is mocked or degraded | Return `""` without touching Docker |
| No container in the group has that name | The handler returns `404 ResourceNotFound` with the container-not-found message |
| The container has no recorded `containerId` (never started) | Return `""` |
| The container is running (`isContainerRunning(id)` is `true`) | `fetchLogs(id, tail, timestamps, config.logMaxBytes(), config.logMaxLines(), Duration.ofSeconds(30))` — defaults 4 194 304 bytes and 100 000 lines |
| The container is not running | `fetchLogs(id, tail, timestamps, config.stoppedLogMaxBytes(), config.stoppedLogMaxLines(), Duration.ofSeconds(30))` — defaults 16 384 bytes and 1 000 lines |
| `fetchLogs` throws `NotFoundException` (the container was removed out of band) | Return `""` and log at `WARN`: `Container {containerName} of container group {group} no longer exists in Docker; returning empty logs` |
| `fetchLogs` throws anything else | Return `""` and log at `WARN`: `Failed to read logs for container {containerName} of container group {group}: {message}` |

The two cap pairs implement Azure's documented limits: *"Container instance log size — running
instance: 4 MB"* and *"Container instance log size — stopped instance: 16 KB or 1,000 lines"*.

`LogResult.truncated()` is **not** surfaced on the wire — `Logs` has only a `content`
property — but it is logged at `DEBUG`: `Log read for container {name} truncated at the
configured cap`.

## New core methods

Two additions to `src/main/java/io/floci/az/core/docker/`, both general-purpose.

### `ContainerLifecycleManager.fetchLogs(...)`

Specified above.

### `ContainerLifecycleManager.inspectState(String)`

`isContainerRunning` (`.../ContainerLifecycleManager.java:473`) answers only a boolean, and the
reconciler needs exit codes and timestamps.

```java
/**
 * A container's runtime state, as reported by {@code docker inspect}.
 *
 * @param exists     false when the container is unknown to the daemon
 * @param running    true when {@code State.Running} is true
 * @param exitCode   {@code State.ExitCode}; meaningful only when {@code running} is false
 * @param startedAt  {@code State.StartedAt}, or null when the container has never started
 * @param finishedAt {@code State.FinishedAt}, or null when the container has not exited
 * @param oomKilled  {@code State.OOMKilled}
 */
public record ContainerRuntimeState(boolean exists, boolean running, int exitCode,
                                    Instant startedAt, Instant finishedAt, boolean oomKilled) {

    public static final ContainerRuntimeState ABSENT =
            new ContainerRuntimeState(false, false, 0, null, null, false);
}

/**
 * Inspects a container and returns its runtime state. A missing container yields
 * {@link ContainerRuntimeState#ABSENT}. Any other Docker error is logged at WARN and also
 * yields {@code ABSENT}, mirroring {@link #isContainerRunning}: a false "absent" costs a
 * clean re-create, while a false "running" would strand a dead container.
 */
public ContainerRuntimeState inspectState(String containerId)
```

Docker reports `StartedAt` and `FinishedAt` as RFC 3339 strings, and uses the sentinel
`0001-01-01T00:00:00Z` for "never". Parse with `Instant.parse` and map the sentinel — and any
unparseable value — to `null`, logging at `DEBUG`.

## Container state machine

States are the three values of `containers[].properties.instanceView.currentState.state`.

| State | Meaning | Terminal? |
|---|---|---|
| `Waiting` | The container exists in the group's spec but is not running: the image is being pulled, the container is being created, or a restart is pending | No |
| `Running` | The Docker container reports `State.Running == true` | No |
| `Terminated` | The Docker container has exited, with `exitCode` and `finishTime` recorded | No — a restart moves it back to `Waiting` then `Running`. Terminal only under `restartPolicy: Never`, or under `OnFailure` with exit code `0`, or after a group `stop`. |

### Transitions

`P` is the group's `restartPolicy`. Events come from the reconciler unless marked otherwise.

| # | State | Event | Next state | Side effects | `instanceView` produced |
|---|---|---|---|---|---|
| C1 | *(none)* | Container spec accepted, image pull starts | `Waiting` | `Pulling` event | `currentState: {state: "Waiting", startTime: <now>, detailStatus: ""}`, `restartCount: 0` |
| C2 | `Waiting` | Image pull succeeds | `Waiting` | `Pulled` event | unchanged |
| C3 | `Waiting` | Image pull fails | `Terminated` | `Failed` event; group becomes `provisioningState: "Failed"`, `instanceView.state: "Failed"` | `currentState: {state: "Terminated", startTime: <pullStart>, exitCode: 1, finishTime: <now>, detailStatus: "Error"}` |
| C4 | `Waiting` | `createAndStart` succeeds | `Running` | `Started` event | `currentState: {state: "Running", startTime: <inspect.startedAt>, detailStatus: ""}` |
| C5 | `Waiting` | `createAndStart` fails for a non-pull reason | `Terminated` | `Failed` event; group `Failed` | `currentState: {state: "Terminated", startTime: <now>, exitCode: 1, finishTime: <now>, detailStatus: "Error"}` |
| C6 | `Running` | `inspectState` reports `running == false`, `exitCode == 0` | `Terminated` | — | `currentState: {state: "Terminated", startTime: <prevStart>, exitCode: 0, finishTime: <inspect.finishedAt>, detailStatus: "Completed"}` |
| C7 | `Running` | `inspectState` reports `running == false`, `exitCode != 0` | `Terminated` | — | as C6 with the real `exitCode` and `detailStatus: "Error"` |
| C8 | `Running` | `inspectState` reports `exists == false` (removed out of band) | `Terminated` | `Failed` event, message `Container {name} disappeared from the Docker daemon` | `currentState: {state: "Terminated", startTime: <prevStart>, exitCode: 1, finishTime: <now>, detailStatus: "Error"}` |
| C9 | `Terminated` (exit `0`) | Reconciler tick, `P == Always` | `Waiting` | move `currentState` into `previousState`, `restartCount += 1`, `docker start` | `previousState` = the old `currentState`; `currentState: {state: "Waiting", startTime: <now>, detailStatus: ""}` |
| C10 | `Terminated` (exit `0`) | Reconciler tick, `P == OnFailure` | `Terminated` | none — the container ran to completion | unchanged; the group may become `Succeeded` |
| C11 | `Terminated` (exit `0`) | Reconciler tick, `P == Never` | `Terminated` | none | unchanged |
| C12 | `Terminated` (exit ≠ `0`) | Reconciler tick, `P == Always` or `P == OnFailure` | `Waiting` | as C9 | as C9 |
| C13 | `Terminated` (exit ≠ `0`) | Reconciler tick, `P == Never` | `Terminated` | none | unchanged; the group becomes `Failed` |
| C14 | `Waiting` (restart pending) | `docker start` succeeds | `Running` | `Started` event, `count` incremented on the existing event | `currentState: {state: "Running", startTime: <inspect.startedAt>, detailStatus: ""}` |
| C15 | `Waiting` (restart pending) | `docker start` fails | `Terminated` | `Failed` event, message `Failed to restart container {name}: {message}` | `currentState: {state: "Terminated", startTime: <now>, exitCode: 1, finishTime: <now>, detailStatus: "Error"}` |
| C16 | `Running` | `POST .../stop` (handler) | `Terminated` | `docker stop` with the configured grace period; `Killing` group event | `currentState: {state: "Terminated", startTime: <prevStart>, exitCode: <inspect.exitCode>, finishTime: <inspect.finishedAt>, detailStatus: "Error"` when nonzero, `"Completed"` when `0``}` |
| C17 | `Terminated` (stopped by the user) | `POST .../start` (handler) | `Running` | `docker start`; `Started` event | `currentState: {state: "Running", startTime: <inspect.startedAt>, detailStatus: ""}`; `restartCount` unchanged |
| C18 | any | `POST .../restart` (handler) | `Running` | full ordered stop-all / start-all; `restartCount += 1` per container | `previousState` = the state before the restart; `currentState: {state: "Running", ...}` |
| C19 | any | Docker daemon unreachable during a reconciler tick | unchanged | tick aborts; `WARN` logged; the group is **not** marked degraded | unchanged — stale state is preferred to wrong state |
| C20 | any | `DELETE` on the group (handler) | *(removed)* | container stopped and removed | the group and its `instanceView` cease to exist |

Every state has at least one inbound and one outbound transition: `Waiting` in via C1/C9/C12,
out via C2/C3/C4/C5/C14/C15; `Running` in via C4/C14/C17/C18, out via C6/C7/C8/C16/C18/C20;
`Terminated` in via C3/C5/C6/C7/C8/C15/C16, out via C9/C12/C17/C18/C20. `Terminated` is
terminal only in the cases C10, C11, and C13 name, and those are documented above as
deliberate.

Note C11 and C13 differ from Azure's documented `Never` behaviour, which says the platform
*might* still restart a container that exits nonzero. floci-az never does — deviation D12.

## Container group state machine

States are the values of `properties.instanceView.state`.

| State | Meaning | Terminal? |
|---|---|---|
| `Pending` | The group has been accepted but at least one container has not yet reached `Running` or `Terminated` | No |
| `Running` | At least one container is `Running` and no container has failed | No |
| `Succeeded` | Every container is `Terminated` with exit code `0`, under `restartPolicy` `OnFailure` or `Never` | No — a `start` action resumes it |
| `Stopped` | A `stop` action was applied; every container is `Terminated` | No — a `start` action resumes it |
| `Failed` | The image pull failed, an init container exited nonzero, a container is `Terminated` nonzero under `Never`, or the infra container could not be repaired | No — a `restart` action or a `PUT` re-create resumes it |

`provisioningState` is a separate, coarser value with only `Succeeded` and `Failed`
(assumption A1). It is `Failed` exactly when `instanceView.state` is `Failed` at the end of the
`PUT`, and `Succeeded` otherwise; the reconciler moves `instanceView.state` afterwards but
never changes `provisioningState` — matching Azure, where `provisioningState` describes the
deployment, not the runtime.

### Transitions

| # | State | Event | Next state | Side effects | `instanceView` produced |
|---|---|---|---|---|---|
| G1 | *(none)* | `PUT` accepted, validation passed | `Pending` | infra container created, volumes ensured, ports reserved | `state: "Pending"`, `events` gains one `PortMapped` per mapping |
| G2 | `Pending` | Every app container reached `Running` | `Running` | — | `state: "Running"` |
| G3 | `Pending` | An image pull or a container start failed | `Failed` | `provisioningState: "Failed"` | `state: "Failed"`; the offending container carries the `Failed` event |
| G4 | `Pending` | An init container exited nonzero | `Failed` | remaining containers are not started; `provisioningState: "Failed"` | `state: "Failed"` |
| G5 | `Pending` | The Docker daemon is unreachable | `Running` | group marked `degraded`; `provisioningState: "Succeeded"` | `state: "Running"`, `events` gains `DockerUnavailable` (`Warning`) |
| G6 | `Running` | Every container `Terminated` with exit `0`, `P` is `OnFailure` or `Never` | `Succeeded` | — | `state: "Succeeded"` |
| G7 | `Running` | At least one container `Terminated` nonzero, `P == Never` | `Failed` | — | `state: "Failed"` |
| G8 | `Running` | A container terminated and `P` says restart | `Running` | the container transitions per C9/C12 | `state: "Running"` |
| G9 | `Running` | `POST .../stop` | `Stopped` | app containers stopped in reverse order, then the infra container; ports stay reserved | `state: "Stopped"`, `events` gains `Killing` |
| G10 | `Stopped` | `POST .../start` | `Running` | infra container started **first**, then app containers in array order | `state: "Running"`, `events` gains `Started` |
| G11 | `Succeeded` | `POST .../start` | `Running` | as G10 | `state: "Running"` |
| G12 | `Failed` | `POST .../restart` | `Running` or `Failed` | full ordered restart; `Failed` again when it still cannot start | `state` per outcome |
| G13 | any | `POST .../restart` | `Running` | stop app containers in reverse order → stop infra → start infra → start app containers in array order; every `restartCount += 1` | `state: "Running"` |
| G14 | `Running` | The infra container is no longer running (spike test 5) | `Running` | **repair**: stop every app container, remove and re-create the infra container with the same name and port bindings, start it, then re-create every app container with the new namespace id; `InfraRestarted` event | `state: "Running"`; every container's `restartCount += 1` |
| G15 | `Running` | Repair in G14 fails | `Failed` | `Failed` event with the repair error | `state: "Failed"` |
| G16 | any | `PUT` on an existing group | `Pending` | the whole group is destroyed and re-created, keeping `groupId`, `name`, `timeCreated`, and the port mappings | `state: "Pending"`, then G2/G3/G4 |
| G17 | any | The Docker daemon is unreachable during a reconciler tick | unchanged | tick aborts; `WARN` logged | unchanged |
| G18 | any | `DELETE` | *(removed)* | app containers stopped and removed in reverse order, then the infra container; volumes pruned per policy; ports released | the group ceases to exist |

Every state has at least one inbound and one outbound transition. None is terminal: `Failed` is
reachable out of via G12 and G16; `Succeeded` via G11 and G16; `Stopped` via G10 and G16.

### Ordering rules, restated

These come straight from [the spike](#spike-shared-network-namespace) and are the part an
implementer most easily gets wrong:

| Operation | Order |
|---|---|
| Create | volumes → infra container → populate secrets → init containers (sequential, each to completion) → app containers in array order |
| Stop | app containers in **reverse** array order → infra container |
| Start | infra container → app containers in array order |
| Restart | stop (above) → start (above). **Never** `docker restart` the infra container while app containers run. |
| Delete | app containers in reverse array order (stop + remove) → infra container (stop + remove) → volumes → release ports |
| Infra repair (G14) | stop every app container → remove infra → create and start a new infra with the same name and bindings → remove and re-create every app container against the new infra id |

G14 must **re-create** the app containers rather than restart them, because a container's
`HostConfig.NetworkMode` records the old infra container's id and Docker resolves it at start
time — spike test 4 shows the start would fail with `cannot join network namespace of a non
running container`.

## Events

Every event the emulator emits, with its exact message template. `{name}` is a container name,
`{group}` a container group name, `{image}` an image reference, `{message}` an exception
message, `{azurePort}` and `{hostPort}` port numbers, `{share}` and `{account}` Azure Files
identifiers, `{volume}` a Docker volume name.

| `name` | `type` | Level | `message` template |
|---|---|---|---|
| `Pulling` | `Normal` | container | `pulling image "{image}"` |
| `Pulled` | `Normal` | container | `Successfully pulled image "{image}"` |
| `Failed` | `Warning` | container | `Failed to pull image "{image}": {message}` |
| `BackOff` | `Normal` | container | `Back-off pulling image "{image}"` |
| `Started` | `Normal` | container | `Started container {name}` |
| `Failed` | `Warning` | container | `Failed to restart container {name}: {message}` |
| `Failed` | `Warning` | container | `Container {name} disappeared from the Docker daemon` |
| `Killing` | `Normal` | group | `Stopping container group {group}` |
| `PortMapped` | `Normal` | group | `Container group port {azurePort} published on host port {hostPort}` |
| `AzureFileEmulated` | `Normal` | group | `Azure File share '{share}' in account '{account}' is emulated by local Docker volume '{volume}'; contents are not shared with the emulated Blob or File service.` |
| `DockerUnavailable` | `Warning` | group | `The Docker daemon is not reachable; this container group is emulated without running containers.` |
| `InfraRestarted` | `Warning` | group | `The container group's network namespace was recreated; all containers were restarted.` |
| `SecretsUnavailableAfterRestart` | `Warning` | group | `Secret values for this container group were not retained across an emulator restart; its containers cannot be recreated.` |
| `Failed` | `Warning` | group | `No free host port available in range {base}-{max} for container group port {azurePort}` |

**Deduplication.** An event is identified by the pair (`name`, `message`). Appending an event
whose pair already exists increments its `count` and updates `lastTimestamp`; otherwise a new
entry is appended with `count: 1` and both timestamps set. Each event list is capped at 20
entries; when full, the oldest entry by `firstTimestamp` is dropped. This keeps a crash-looping
container from growing the stored record without bound.

## Reconciliation

**What runs it.** One `ScheduledExecutorService` with a single daemon thread named
`aci-reconciler`, created in `ContainerGroupReconciler`, started from `@PostConstruct` only when
`floci-az.services.container-instance.mocked` is `false`. This mirrors `VmHandler`'s readiness
poller (`src/main/java/io/floci/az/services/vm/VmHandler.java:76-80` and
`.../VmHandler.java:419-434`).

**How often.** `scheduleAtFixedRate(task, 2, config.reconcileIntervalSeconds(), TimeUnit.SECONDS)`
— an initial delay of 2 seconds and a default period of 3 seconds, matching `VmHandler`
(`.../VmHandler.java:433`).

**What one tick does**, for every group returned by `storage.scan(k -> true)`:

1. Skip the group when it is `degraded`, or when `instanceView.state` is `Stopped`.
2. Skip the group and append `SecretsUnavailableAfterRestart` once when the group needs its
   secrets to re-create containers (it has a `secureValue`, a `secret` volume, or an
   `imageRegistryCredentials` password recorded in `properties` as having been supplied) and the
   in-memory secret map has no entry for it. Set `instanceView.state: "Failed"`.
3. `inspectState(group.infraContainerId)`. When `exists` is false or `running` is false, run the
   G14 repair. If the repair throws, apply G15.
4. For every app container with a recorded `containerId`, `inspectState(id)` and apply the
   [container transitions](#container-state-machine) C6, C7, C8, C9, C12, C14, C15.
5. Recompute `instanceView.state` from the container states, per
   [group transitions](#container-group-state-machine) G2, G6, G7, G8.
6. Persist the group with `storage.put` **only when something changed**. The tick computes a
   change flag as it goes; an unchanged group is not rewritten, so a `wal` or `persistent`
   backend is not churned every 3 seconds.

**Adoption after an emulator restart.** The first tick after startup, for every stored group:

- `portAllocator.markReserved(hostPort)` for every recorded mapping.
- When `inspectState(infraContainerId)` reports `exists == false`, look the infra container up
  by name with `lifecycleManager.findByName(infraName)`
  (`src/main/java/io/floci/az/core/docker/ContainerLifecycleManager.java:340`) and adopt its id
  when found. Do the same for every app container. This recovers from a container id that
  changed because the container was re-created out of band.
- When neither the id nor the name resolves, the group is treated as having lost its containers
  and step 2's secret check decides whether it can be re-created.

**When Docker disappears mid-life.** `inspectState` returns `ABSENT` for every container
(`isContainerRunning` already treats any Docker error as not-running,
`.../ContainerLifecycleManager.java:479-487`, and `inspectState` follows the same rule). Acting
on that would mark every container `Terminated` and trigger a restart storm. The reconciler
therefore performs a **daemon liveness pre-check** at the start of each tick:

```java
try {
    lifecycleManager.getDockerClient().pingCmd().exec();
} catch (Exception e) {
    LOG.warnv("Docker daemon unreachable; skipping container-instance reconciliation tick: {0}",
            e.getMessage());
    return;
}
```

This is the single place where `ContainerGroupReconciler` touches `dockerClient`, through the
existing accessor `ContainerLifecycleManager.getDockerClient()`
(`.../ContainerLifecycleManager.java:506`). It is a liveness probe, not container management,
so it does not violate the AGENTS.md rule against managing containers through `dockerClient`.
When the ping fails, the tick returns without changing any state — transition C19 / G17.

**Shutdown.** `@PreDestroy` on `ContainerInstanceHandler`:

1. `reconciler.shutdown()` — `poller.shutdownNow()`.
2. When `mocked` is `false` and `keep-running-on-shutdown` is `false`, destroy every stored
   group with the delete ordering, each inside a `try`/`catch (Exception e)` logging at `WARN`:
   `Error removing Docker resources for container group {name}: {message}`.
3. When `keep-running-on-shutdown` is `true`, leave everything running and log at `INFO`:
   `Leaving {n} container group(s) running (keep-running-on-shutdown=true)`.

## Failure and degradation paths

| Failure | Detected where | Log level | Log message | Client-visible result |
|---|---|---|---|---|
| Docker daemon unreachable at `PUT` | `createGroup` | `ERROR` | `Docker unavailable for container group {name}; degrading to mocked state` | `201`/`200`, `provisioningState: "Succeeded"`, `DockerUnavailable` event |
| Image pull fails (image missing, auth rejected) | `ensureImageExists` | `ERROR` | `Failed to pull image {image} for container {name} in container group {group}: {message}` | `201`/`200`, `provisioningState: "Failed"`, `Failed` event on the container |
| Infra container fails to start | `createAndStart` | `ERROR` | `Failed to start infrastructure container for container group {name}: {message}` | `201`/`200`, `provisioningState: "Failed"` |
| App container fails to start | `createAndStart` | `ERROR` | `Failed to start container {name} in container group {group}: {message}` | `201`/`200`, `provisioningState: "Failed"`, rollback of the whole group |
| Init container exits nonzero | init loop | `ERROR` | `Init container {name} in container group {group} exited with code {exitCode}` | `201`/`200`, `provisioningState: "Failed"` |
| Init container exceeds 300 s | init loop | `ERROR` | `Init container {name} in container group {group} did not complete within 300 seconds` | `201`/`200`, `provisioningState: "Failed"` |
| Host port range exhausted | port resolution | `ERROR` | `No free host port in range {base}-{max} for container group {name}` | `201`/`200`, `provisioningState: "Failed"`, `Failed` group event |
| Volume creation fails | `ensureVolume` | `ERROR` | `Failed to create volume {volume} for container group {name}: {message}` | `201`/`200`, `provisioningState: "Failed"` |
| Secret population fails | `copyBytesToContainer` | `ERROR` | `Failed to populate secret volume {volume} for container group {name}: {message}` | `201`/`200`, `provisioningState: "Failed"` |
| Infra container dies at runtime | reconciler step 3 | `WARN` | `Infrastructure container for container group {name} is not running; recreating the network namespace` | `InfraRestarted` event, `restartCount` bumped |
| Infra repair fails | reconciler step 3 | `ERROR` | `Failed to recreate the network namespace for container group {name}: {message}` | `instanceView.state: "Failed"` |
| Docker daemon disappears mid-life | reconciler ping | `WARN` | `Docker daemon unreachable; skipping container-instance reconciliation tick: {message}` | State frozen at its last observed value |
| Log read on a removed container | `readLogs` | `WARN` | `Container {name} of container group {group} no longer exists in Docker; returning empty logs` | `200` `{"content":""}` |
| Log read fails for any other reason | `readLogs` | `WARN` | `Failed to read logs for container {name} of container group {group}: {message}` | `200` `{"content":""}` |
| Container removal fails at delete | `destroyGroup` | `WARN` | `Error removing container {name} of container group {group}: {message}` | `204` — delete always succeeds from the client's view |
| Volume removal fails at delete | `destroyGroup` | `WARN` | `Error removing volume {volume} of container group {group}: {message}` | `204` |
| Secrets lost across an emulator restart | reconciler step 2 | `WARN` | `Secrets for container group {name} were not retained across restart; its containers will not be recreated` | `instanceView.state: "Failed"`, `SecretsUnavailableAfterRestart` event |

No failure produces an HTTP `5xx` except the two deliberate `501`s for `exec` and `attach`.
Every `catch` block logs; none is empty, per AGENTS.md.

## Authoritative references

- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-container-groups>,
  section *Networking*: *"Within a container group, container instances can reach each other via
  `localhost` on any port, even if those ports aren't exposed externally on the group's IP
  address or from the container."* This is the behaviour the pod pattern reproduces.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-restart-policy>
  — `Always` is the default; `Never` does not restart on exit code `0`; `OnFailure` restarts only
  on a nonzero exit; a container stopped under `Never` or `OnFailure` has status `Terminated`.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-quotas>,
  section *Unchangeable (Hard) Limits* — running-instance log size 4 MB, stopped-instance log
  size 16 KB or 1 000 lines.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-troubleshooting>,
  section *Unable to pull image* — the `Pulling` / `Failed` / `BackOff` event names and message
  shapes.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-volume-emptydir>
  — an `emptyDir` volume is writable and shared by every container in the group.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-volume-secret>
  — secret volumes are read-only once deployed and are Base64-encoded in the request.
- Spike evidence: Docker Engine 29.7.2 on Linux, docker-java 3.7.1, images `alpine:3.20`.
  Reproduce with the commands transcribed in
  [the shared-namespace spike](#spike-shared-network-namespace) and
  [the log spike](#spike-logcontainercmd).
- Repository: `src/main/java/io/floci/az/core/docker/ContainerBuilder.java`,
  `.../ContainerLifecycleManager.java`, `.../ContainerSpec.java`, `.../ImageCacheService.java`,
  `.../PortAllocator.java`, `.../ContainerStorageHelper.java`,
  `src/main/java/io/floci/az/core/dns/EmbeddedDnsServer.java`,
  `src/main/java/io/floci/az/services/vm/VmContainerManager.java`,
  `src/main/java/io/floci/az/services/vm/VmHandler.java`,
  `src/main/java/io/floci/az/services/acr/AcrRegistryManager.java`, `pom.xml`.
