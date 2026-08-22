# Azure Container Instances

**Status:** Proposed architecture

**Last updated:** 2026-08-22

**Implementation:** Not started. This document set is the complete specification for
[floci-io/floci-az#59](https://github.com/floci-io/floci-az/issues/59).

This document defines the architecture for `Microsoft.ContainerInstance/containerGroups`
in floci-az: a management-plane provider that maps each Azure container group onto a set
of real local Docker containers sharing one network namespace, and reports their state,
IP, and logs through the ARM control plane.

The wire contract is in the [resource model](container-instances-resource-model.md).
The Docker mapping and state machines are in the [runtime design](container-instances-runtime.md).
Test coverage is in the [test plan](container-instances-test-plan.md).
The execution sequence is in the [roadmap](container-instances-roadmap.md).
The document set is indexed in [Container Instances (index)](container-instances-index.md).

Every fact in this document set is stated inline. Citations exist so a reviewer can audit
the work; an implementer never needs to open one.

## Decision summary

floci-az will implement `Microsoft.ContainerInstance/containerGroups` as a **filter-lane
`AzureServiceHandler`** that claims the provider namespace from `routes()`, exactly as
`VmHandler` claims `Microsoft.Compute`.

```text
PUT /subscriptions/{sub}/resourceGroups/{rg}
    /providers/Microsoft.ContainerInstance/containerGroups/{name}?api-version=2023-05-01
    |
    v
AzureRoutingFilter.routeArmProviders          (src/main/java/io/floci/az/core/AzureRoutingFilter.java:466)
    |  matches "/providers/Microsoft.ContainerInstance/"
    v
AzureServiceRegistry.resolve("containerinstance")   (src/main/java/io/floci/az/core/AzureServiceRegistry.java:63)
    |
    v
ContainerInstanceHandler.handle(AzureRequest)
    |
    +--> ContainerGroupValidator      request validation, ARM CloudError on failure
    +--> ContainerGroupRuntime        Docker pod: one infra container + N app containers
    +--> ContainerGroupReconciler     3s poller: exit codes, restart policy, instanceView
    +--> StorageBackend("containerinstance")
```

The pinned API version is **`2023-05-01`**. Any `api-version` query value is accepted,
including absent and preview values — see [API version handling](#api-version-handling).

A container group is backed by **one Docker network namespace**. A dedicated *infrastructure
container* owns the namespace and carries every host port binding; each ACI container joins
it with Docker network mode `container:<infraContainerId>`. This is the pod pattern, and it is
what makes `localhost` reachability between containers in a group real rather than simulated.
The design is proven end to end in [the shared-namespace spike](container-instances-runtime.md#spike-shared-network-namespace).

## Goals

- Serve the standard `Microsoft.ContainerInstance/containerGroups` REST surface so the
  Azure SDK for Java, the Azure CLI, and the `azurerm` Terraform provider work unmodified.
- Run real Docker containers per container group: image, command, environment variables,
  ports, memory, and volume mounts all take effect.
- Report container-group and per-container state through `instanceView`, including
  `currentState`, `previousState`, `restartCount`, and `events`.
- Honour `restartPolicy` `Always`, `OnFailure`, and `Never` with the emulator, not Docker,
  owning the restart decision.
- Serve `GET .../containers/{containerName}/logs` with bounded, non-buffering log retrieval.
- Serve `start`, `stop`, and `restart` group actions against real containers.
- Give containers in one group a shared network namespace so they reach each other on
  `localhost`, as they do in Azure.
- Degrade gracefully and non-fatally when the Docker daemon is absent, so the service is
  usable in CI without a Docker socket.
- Reuse `ContainerBuilder`, `ContainerLifecycleManager`, `ImageCacheService`,
  `PortAllocator`, `ContainerStorageHelper`, and `StorageFactory` rather than calling
  `dockerClient` directly from service code.
- Leave a runtime layer that Azure Container Apps
  ([floci-io/floci-az#62](https://github.com/floci-io/floci-az/issues/62)) can build on
  without rewriting it.

## Non-goals

- Websocket data-plane operations: `Containers_ExecuteCommand` and `Containers_Attach`.
  Both return `501 NotImplemented` — see the [deviations register](#deviations-register).
- Virtual-network integration: `subnetIds`, `serviceAssociationLinks`, private IP routing.
  `subnetIds` is accepted and echoed but has no runtime effect.
- Windows containers. `osType: Windows` is accepted and echoed; the runtime always creates
  Linux containers.
- GPU scheduling. `resources.requests.gpu` and `resources.limits.gpu` are accepted and echoed.
- Confidential containers (`sku: Confidential`, `confidentialComputeProperties`) and
  `extensions`. Accepted and echoed, no runtime effect.
- Managed-identity token issuance inside container-group containers. `identity` is accepted,
  echoed, and given synthetic `principalId`/`tenantId`, but no IMDS endpoint is injected.
- Log Analytics forwarding. `diagnostics.logAnalytics` is accepted and echoed; nothing is
  forwarded to the emulated Monitor service.
- Azure Files data-plane integration. `azureFile` volumes are backed by local Docker volumes
  and are **not** shared with floci-az's emulated Blob/File service.
- Quota enforcement beyond the hard per-group limits listed in the
  [resource model](container-instances-resource-model.md#validation-rules).
- Multi-node scheduling, availability zones, and regional capacity modelling.
- CPU limit enforcement. `resources.requests.cpu` / `resources.limits.cpu` are accepted,
  echoed, and validated, but not applied to Docker.

## Current repository fit

### Incoming request path

`AzureRoutingFilter` is a `@PreMatching` JAX-RS filter that runs a fixed sequence of routing
stages. The ARM provider stage is `routeArmProviders`
(`src/main/java/io/floci/az/core/AzureRoutingFilter.java:466`):

```text
AzureRoutingFilter.filter
    -> ... host-suffix stages, account-suffix stages, literal stages ...
    -> routeArmProviders            requires path.startsWith("subscriptions/")
       iterates providerRoutes, matching "/providers/{namespace}/" against the path
    -> routeArmGeneric              fallback for every other subscriptions/ path
```

`providerRoutes` is not hand-maintained. `buildRoutingTables`
(`src/main/java/io/floci/az/core/AzureRoutingFilter.java:177`) runs at `@PostConstruct` and
walks every CDI-discovered `AzureServiceHandler`, collecting each handler's
`routes().providers()` into the dispatch table. A handler that declares

```java
@Override
public ServiceRoutes routes() {
    return ServiceRoutes.builder()
            .provider("Microsoft.ContainerInstance")
            .build();
}
```

is therefore reachable by construction, with no edit anywhere else in the filter.

Two startup guards make wiring mistakes fatal rather than silent:

- `rejectDuplicateProviders` (`src/main/java/io/floci/az/core/AzureRoutingFilter.java:232`)
  throws `IllegalStateException` if two handlers claim the same provider marker. Today no
  handler claims `Microsoft.ContainerInstance` — the claimed namespaces are
  `Microsoft.ContainerRegistry` (`src/main/java/io/floci/az/services/acr/AcrHandler.java`),
  `Microsoft.ContainerService` (`.../aks/AksHandler.java`),
  `Microsoft.DBforMariaDB`, `Microsoft.Communication`, `Microsoft.Cache`,
  `Microsoft.DBforMySQL`, `Microsoft.ManagedIdentity`, `Microsoft.Sql`,
  `Microsoft.DBforPostgreSQL`, and `Microsoft.Compute`.
- `rejectDuplicateSuffixes` does the same for host and account suffixes.

### Lane analysis and the #106 failure mode

Management providers currently use two integration styles:

| Lane | Registration | Dispatch entry point | Examples |
|---|---|---|---|
| Filter lane | `AzureServiceHandler.routes().provider(...)` | `AzureRoutingFilter.routeArmProviders` | Compute (`VmHandler`), AKS, ACR, Redis, Managed Identity, SQL, PostgreSQL, MySQL, MariaDB, Communication |
| ArmHandler lane | `ArmProviderService.providerNamespaces()` | inside `ArmHandler`'s resource-group branch | Event Grid, API Management, Monitor |

**Container Instances belongs in the filter lane.** The reasons are specific, not stylistic:

1. **Child routes below the resource name.** ACI has `containerGroups/{name}/containers/{c}/logs`
   and three POST actions. The `ArmProviderService` lane is shaped around the generic ARM
   resource-group envelope (`PUT`/`GET`/`DELETE` on a resource path); the filter lane hands the
   handler the full path and lets it own its own route table, which `VmHandler` already does
   for `instanceView` and six power actions
   (`src/main/java/io/floci/az/services/vm/VmHandler.java:131-181`).
2. **Docker-backed runtime with process-lifetime state.** ACI owns a reconciler thread, Docker
   containers, named volumes, and allocated host ports. Every other Docker-backed provider in
   this repository — Compute, AKS, ACR, Redis — is in the filter lane.
3. **Closest working precedent.** `VmHandler` is an ARM-only service that claims its namespace
   from `routes()`, serves CRUD plus `?$expand=instanceView` plus POST actions, delegates to a
   container manager, and ships `mocked` defaulting to `true`. ACI is the same shape with a
   richer runtime. AGENTS.md says: *"copy an existing service pattern before introducing a new one."*

**How this specification makes the #106 mistake structurally impossible.**

Commit `8af0ad5` (PR #106) deleted `src/main/java/io/floci/az/services/containerapp/ContainerAppService.java`
with the message *"CDI-registered but unreachable (no route, no config, no `isEnabled` case;
placeholder responses only)."* The deleted class was a 50-line `@ApplicationScoped`
`AzureServiceHandler` whose `handle()` returned `501 NotImplemented` for every request and
which did not override `routes()`.

Three of those four failure modes are now impossible by construction, and this specification
closes the fourth:

| #106 failure mode | Why it cannot recur | Enforced by |
|---|---|---|
| *No route* | `routes()` returning a `provider()` claim is the only way a provider handler is registered, and `buildRoutingTables` reads it at startup. A handler that forgets `routes()` fails the mandatory routing test `ContainerInstanceRoutingTest#providerNamespaceIsClaimedExactlyOnce`. | [Test plan — routing](container-instances-test-plan.md#routing) |
| *No `isEnabled` case* | The central `AzureServiceRegistry.isEnabled` switch that #106 referred to no longer exists. `AzureServiceRegistry.isEnabled` (`src/main/java/io/floci/az/core/AzureServiceRegistry.java:44`) delegates to the handler's own `enabled(String)` (`src/main/java/io/floci/az/core/AzureServiceHandler.java:40`), which defaults to `true`. Enablement lives next to the service. | `ContainerInstanceHandler.enabled(String)` returning `config.services().containerInstance().enabled()` |
| *No config* | Commit 1 of the [roadmap](container-instances-roadmap.md) adds `ContainerInstanceConfig` to `EmulatorConfig.ServicesConfig`, the `application.yml` block, the `BannerLogger` line, and the `StorageFactory` case **in the same commit as the handler**. The banner test asserts the service appears. | [Roadmap commit 1](container-instances-roadmap.md#commit-1-config-storage-and-banner-wiring) |
| *Placeholder responses only* | **Rule: no route in the routing table may return a placeholder.** Every row of the [routing table](#routing-table) is either fully implemented in the commit that introduces it, or absent from the table and answered by the `Unsupported Microsoft.ContainerInstance path` 404. The two deliberate `501 NotImplemented` routes (`exec`, `attach`) are not placeholders: they are a documented, tested, permanent contract with a matching entry in the [deviations register](#deviations-register). | [Test plan — routing](container-instances-test-plan.md#routing), which asserts a real response body for every table row |

The last rule is the important one. **A route exists in the table only when it has a test
that asserts a concrete response body.** There is no "scaffold now, fill in later" step in the
roadmap; each commit ships routes that work, and the [roadmap](container-instances-roadmap.md)
lists the tests that must be green before the next commit starts.

There is one further anti-#106 rule: **do not add an LRO status route.** `VmHandler` returns
`Azure-AsyncOperation` headers and therefore needs
`locations/{loc}/operations/{opId}` (`src/main/java/io/floci/az/services/vm/VmHandler.java:139`).
Container Instances is fully synchronous and never emits those headers, so that route would be
unreachable code of exactly the #106 kind. It is deliberately absent.

### Existing infrastructure this design reuses

Every symbol below was confirmed to exist on `upstream/main` at the cited line.

| Symbol | Location | Used for |
|---|---|---|
| `ServiceRoutes.Builder.provider(String)` | `src/main/java/io/floci/az/core/ServiceRoutes.java:79` | Claiming `Microsoft.ContainerInstance` |
| `AzureServiceHandler.routes()` | `src/main/java/io/floci/az/core/AzureServiceHandler.java:24` | Route declaration |
| `AzureServiceHandler.enabled(String)` | `src/main/java/io/floci/az/core/AzureServiceHandler.java:40` | Per-service enablement |
| `Resettable.clear()` | `src/main/java/io/floci/az/core/Resettable.java:12` | `POST /_admin/reset` |
| `AdminController.reset()` | `src/main/java/io/floci/az/core/AdminController.java:48` | Reset dispatch |
| `ArmErrors.error(int, String, String)` | `src/main/java/io/floci/az/core/arm/ArmErrors.java:18` | ARM CloudError bodies |
| `ArmErrors.notFound(String)` | `src/main/java/io/floci/az/core/arm/ArmErrors.java:25` | 404 `ResourceNotFound` |
| `ArmPaths.segmentAfter(String, String, String)` | `src/main/java/io/floci/az/core/arm/ArmPaths.java:46` | Subscription / resource-group extraction |
| `StorageFactory.create(String)` | `src/main/java/io/floci/az/core/storage/StorageFactory.java:49` | Persistence backend |
| `ContainerBuilder.newContainer(String)` | `src/main/java/io/floci/az/core/docker/ContainerBuilder.java:60` | Spec construction |
| `ContainerLifecycleManager.createAndStart(ContainerSpec)` | `src/main/java/io/floci/az/core/docker/ContainerLifecycleManager.java:80` | Infra + app container start |
| `ContainerLifecycleManager.create(ContainerSpec)` | `.../ContainerLifecycleManager.java:101` | Create before start |
| `ContainerLifecycleManager.startCreated(String, ContainerSpec)` | `.../ContainerLifecycleManager.java:140` | Start after pre-start setup |
| `ContainerLifecycleManager.stopAndRemove(String, Closeable)` | `.../ContainerLifecycleManager.java:168` | Teardown |
| `ContainerLifecycleManager.removeIfExists(String)` | `.../ContainerLifecycleManager.java:418` | Stale-container cleanup |
| `ContainerLifecycleManager.start(String)` | `.../ContainerLifecycleManager.java:429` | Group start action |
| `ContainerLifecycleManager.stop(String, int)` | `.../ContainerLifecycleManager.java:441` | Group stop action |
| `ContainerLifecycleManager.isContainerRunning(String)` | `.../ContainerLifecycleManager.java:473` | Reconciliation |
| `ContainerLifecycleManager.ensureVolume(String)` | `.../ContainerLifecycleManager.java:201` | `emptyDir` / `secret` / `azureFile` backing |
| `ContainerLifecycleManager.removeVolume(String)` | `.../ContainerLifecycleManager.java:324` | Volume pruning on delete |
| `ContainerLifecycleManager.copyBytesToContainer(String, byte[], String)` | `.../ContainerLifecycleManager.java:556` | Populating `secret` volumes |
| `ImageCacheService.ensureImageExists(String)` | `src/main/java/io/floci/az/core/docker/ImageCacheService.java:40` | Image pull, called from `create` |
| `PortAllocator.allocate(int, int)` | `src/main/java/io/floci/az/core/docker/PortAllocator.java:33` | Host port allocation |
| `PortAllocator.release(int)` | `src/main/java/io/floci/az/core/docker/PortAllocator.java:58` | Host port release on delete |
| `PortAllocator.isPortFree(int)` | `src/main/java/io/floci/az/core/docker/PortAllocator.java:88` | Preferring the Azure port number |
| `ContainerStorageHelper.dockerName(EmulatorConfig, String)` | `src/main/java/io/floci/az/core/docker/ContainerStorageHelper.java:51` | Container / volume naming |
| `ContainerStorageHelper.shouldPruneVolume(EmulatorConfig)` | `.../ContainerStorageHelper.java:124` | Volume prune policy |
| `EmbeddedDnsServer.getServerIp()` | `src/main/java/io/floci/az/core/dns/EmbeddedDnsServer.java:104` | DNS injection availability |
| `ContainerBuilder.Builder.withEmbeddedDns()` | `src/main/java/io/floci/az/core/docker/ContainerBuilder.java:412` | DNS injection into the infra container |

Two core methods do **not** exist today and are specified as new work:

- `ContainerLifecycleManager.fetchLogs(...)` — nothing in `src/` calls docker-java's
  `logContainerCmd`; verified by `grep -rn "logContainerCmd" src/` returning no matches on
  `upstream/main`. The full signature and semantics are in the
  [runtime design](container-instances-runtime.md#bounded-log-retrieval).
- `ImageCacheService.ensureImageExists(String, AuthConfig)` — the existing one-argument method
  (`ImageCacheService.java:40`) resolves credentials only from
  `floci-az.docker.registry-credentials` via the private `resolveAuth`
  (`ImageCacheService.java:132`). Per-request `imageRegistryCredentials` cannot flow through it.
  The overload is specified in the [runtime design](container-instances-runtime.md#image-pull-and-registry-credentials).

`PortAllocator.release(int)` and `PortAllocator.markReserved(int)` exist but have **no
production callers** on `upstream/main` (verified by grepping `src/main/java` for
`release(` and `markReserved(`; the only hits are `WarmPool.release(ContainerHandle)` and
`FunctionsExecutorService`, which are unrelated). Container Instances is the first caller of
`release(int)`.

## Target components

All new service code lives in `src/main/java/io/floci/az/services/containerinstance/`.

| Class | Scope | Responsibility |
|---|---|---|
| `ContainerInstanceHandler` | `@ApplicationScoped`, implements `AzureServiceHandler` and `Resettable` | Route table, request parsing, ARM response assembly, `clear()`. Holds the `StorageBackend`. Owns no Docker logic. |
| `ContainerInstanceModels` | plain class of nested types | `ContainerGroup`, `ContainerRecord`, `ContainerStateValue`, `GroupStateValue`, `RestartPolicy`, `EventRecord`, `PortMapping`. Jackson-annotated, `@RegisterForReflection`. |
| `ContainerGroupValidator` | `@ApplicationScoped` | Pure validation of the parsed request body against the [error catalog](container-instances-resource-model.md#error-catalog). Returns `Optional<ValidationError>`; performs no I/O. |
| `ContainerGroupRuntime` | `@ApplicationScoped` | The Docker pod: infra container, app containers, volumes, host ports, log retrieval, start/stop/restart/delete. Calls only `ContainerBuilder`, `ContainerLifecycleManager`, `ImageCacheService`, `PortAllocator`, `ContainerStorageHelper`. |
| `ContainerGroupReconciler` | `@ApplicationScoped` | The 3-second poller: reads container state from Docker, applies `restartPolicy`, updates `instanceView`, repairs a dead infra container, persists changes. |
| `ContainerInstanceErrors` | final class, private constructor | Every error in the catalog as a named static factory returning `Response`, built on `ArmErrors`. |

Dependency direction is strictly one-way:

```text
ContainerInstanceHandler
    -> ContainerGroupValidator      (pure)
    -> ContainerGroupRuntime        (Docker)
    -> ContainerInstanceErrors      (pure)
ContainerGroupReconciler
    -> ContainerGroupRuntime
    -> StorageBackend
```

`ContainerGroupRuntime` never reads the `StorageBackend` and never builds a `Response`.
`ContainerInstanceHandler` never calls `dockerClient`. Constructor injection everywhere,
per AGENTS.md.

## Routing table

Marker: `/providers/Microsoft.ContainerInstance/`. `tail` below is everything after that
marker, with the query string stripped for matching. `{sub}` and `{rg}` are extracted with
`ArmPaths.segmentAfter(path, "subscriptions", "unknown")` and
`ArmPaths.segmentAfter(path, "resourcegroups", "unknown")`.

| Method | `tail` pattern | Scope | Handler method | Success response |
|---|---|---|---|---|
| `GET` | `containerGroups` | subscription (path has no `/resourceGroups/`) | `handleListBySubscription` | `200` `{"value":[...]}` |
| `GET` | `containerGroups` | resource group | `handleListByResourceGroup` | `200` `{"value":[...]}` |
| `PUT` | `containerGroups/{name}` | resource group | `handleCreateOrUpdate` | `201` on create, `200` on update, full container group body |
| `GET` | `containerGroups/{name}` | resource group | `handleGet` | `200` full container group body; `instanceView` included when `$expand` contains `instanceview` (case-insensitive) |
| `PATCH` | `containerGroups/{name}` | resource group | `handleUpdateTags` | `200` full container group body |
| `DELETE` | `containerGroups/{name}` | resource group | `handleDelete` | `204` No Content, idempotent |
| `POST` | `containerGroups/{name}/start` | resource group | `handleStart` | `204` No Content |
| `POST` | `containerGroups/{name}/stop` | resource group | `handleStop` | `204` No Content |
| `POST` | `containerGroups/{name}/restart` | resource group | `handleRestart` | `204` No Content |
| `GET` | `containerGroups/{name}/containers/{containerName}/logs` | resource group | `handleLogs` | `200` `{"content":"..."}` |
| `GET` | `containerGroups/{name}/outboundNetworkDependenciesEndpoints` | resource group | `handleOutboundNetworkDependencies` | `200` `[]` |
| `POST` | `containerGroups/{name}/containers/{containerName}/exec` | resource group | `handleExec` | `501` `NotImplemented` |
| `POST` | `containerGroups/{name}/containers/{containerName}/attach` | resource group | `handleAttach` | `501` `NotImplemented` |
| `GET` | `locations/{location}/usages` | subscription | `handleListUsage` | `200` `{"value":[...]}` |
| `GET` | `locations/{location}/capabilities` | subscription | `handleListCapabilities` | `200` `{"value":[...]}` |
| `GET` | `locations/{location}/cachedImages` | subscription | `handleListCachedImages` | `200` `{"value":[]}` |
| any other | — | — | — | `404` `ResourceNotFound`, message `Unsupported Microsoft.ContainerInstance path: {tail}` |
| known path, wrong method | `containerGroups/{name}` with e.g. `HEAD` | — | — | `405` `MethodNotAllowed`, message `Method not allowed` |

Matching is performed in the order above with Java regular expressions on `tail`, mirroring
`VmHandler.handle` (`src/main/java/io/floci/az/services/vm/VmHandler.java:131-181`). The exact
regexes are:

```java
private static final String CI_MARKER = "/providers/Microsoft.ContainerInstance/";

// order matters: longest / most specific first
"containerGroups/[^/]+/containers/[^/]+/logs(?:[?].*)?"
"containerGroups/[^/]+/containers/[^/]+/exec(?:[?].*)?"
"containerGroups/[^/]+/containers/[^/]+/attach(?:[?].*)?"
"containerGroups/[^/]+/outboundNetworkDependenciesEndpoints(?:[?].*)?"
"containerGroups/[^/]+/(start|stop|restart)(?:[?].*)?"
"containerGroups/[^/]+(?:[?].*)?"
"containerGroups(?:[?].*)?"
"locations/[^/]+/(usages|capabilities|cachedImages)(?:[?].*)?"
```

### Routes deliberately not served

| Azure route | Why absent | What the emulator returns |
|---|---|---|
| `GET /providers/Microsoft.ContainerInstance/operations` | `routeArmProviders` only runs for paths starting `subscriptions/` (`AzureRoutingFilter.java:467`). A tenant-scope path never reaches this handler. | Whatever `routeArmGeneric` / `ArmHandler` already returns for `/providers` |
| `DELETE .../Microsoft.Network/virtualNetworks/{v}/subnets/{s}/providers/Microsoft.ContainerInstance/serviceAssociationLinks/default` | VNet integration is a non-goal. The path's first `/providers/` segment is `Microsoft.Network`, so the Network handler sees it first. | Network handler behaviour, unchanged |
| `GET .../Microsoft.ContainerInstance/locations/{loc}/operations/{opId}` | The emulator is fully synchronous and never emits `Azure-AsyncOperation`. Adding this route would create unreachable code — the exact #106 defect. | `404 ResourceNotFound`, `Unsupported Microsoft.ContainerInstance path: locations/{loc}/operations/{opId}` |

## API version handling

**Pinned version: `2023-05-01`.**

Provenance, and how it was cross-checked against real clients:

| Client | Version resolved | `api-version` sent | Evidence |
|---|---|---|---|
| Azure SDK for Java, `com.azure.resourcemanager:azure-resourcemanager-containerinstance` | `2.53.13` (latest on Maven Central at the time of writing) | `2023-05-01` | `ContainerInstanceManagementClientImpl.java:201` in the `-sources` jar: `this.apiVersion = "2023-05-01";` |
| Terraform `hashicorp/azurerm`, constraint `~> 3.0` as pinned by `compatibility-tests/compat-terraform/provider.tf` | `v3.117.0` | `2023-05-01` | `internal/services/containers/container_group_resource.go:22` imports `go-azure-sdk/resource-manager/containerinstance/2023-05-01/containerinstance` |
| Azure CLI, bundled `azure-mgmt-containerinstance` | `10.2.0b1` | `2024-05-01-preview` | Captured from a live request: `GET /subscriptions/.../providers/Microsoft.ContainerInstance/containerGroups?api-version=2024-05-01-preview` |
| Terraform `azurerm` v4 / current `main` | — | `2025-09-01` | `internal/services/containers/container_group_resource.go:23` on `main` |

**The clients disagree, so the emulator does not enforce a version at all.**
`api-version` is read for logging only. Requests with no `api-version`, with
`2023-05-01`, with `2024-05-01-preview`, or with `2025-09-01` are all served identically,
using the `2023-05-01` response shape. Rationale:

- `2023-05-01` is the version the two clients this repository's compatibility suites actually
  use (`sdk-test-java`, `compat-terraform`).
- The `2023-05-01` container-group body is a strict subset of `2024-05-01-preview` and
  `2025-09-01` for every property in scope, so a newer client deserialising a `2023-05-01`
  body loses nothing it uses.
- Rejecting `2024-05-01-preview` would break `az container` outright.

This matches the repository's existing behaviour: `VmHandler` also never validates
`api-version` (`src/main/java/io/floci/az/services/vm/VmHandler.java:131-181` has no
version check). The discrepancy and the decision are recorded in the
[deviations register](#deviations-register).

## Mocked and real-Docker modes

Two configuration flags control the runtime:

- `floci-az.services.container-instance.enabled` — when `false`, the handler declines and the
  routing filter falls through to the generic ARM handler. Default `true`.
- `floci-az.services.container-instance.mocked` — when `true`, no Docker container is created.
  Interface default `true`; `application.yml` sets `false`.

This is the pattern already used by ACR (`EmulatorConfig.java` `AcrConfig` declares
`@WithDefault("true") boolean mocked();` while `src/main/resources/application.yml:204` sets
`mocked: false`) and by AKS and Redis. The interface default keeps `@QuarkusTest` runs that
supply no YAML from needing a Docker socket; `application.yml` gives the shipped emulator its
real behaviour.

### Mocked mode

`mocked=true`:

- No Docker call is made at any point. `ContainerGroupRuntime` is not consulted.
- `PUT` returns `provisioningState: "Succeeded"` immediately.
- `instanceView.state` is `"Running"`; every container's `currentState.state` is `"Running"`
  with `startTime` set to the create time, `detailStatus` `""`, `restartCount` `0`, and no
  `previousState`.
- `ipAddress.ip` is `"127.0.0.1"`; `ipAddress.fqdn` is
  `{dnsNameLabel}.{location}.azurecontainer.io` when `dnsNameLabel` is set, absent otherwise.
- `stop` sets `instanceView.state` to `"Stopped"` and every container's `currentState.state`
  to `"Terminated"` with `exitCode: 0`.
- `start` and `restart` set `instanceView.state` back to `"Running"`; `restart` increments
  every container's `restartCount` by 1.
- `GET .../logs` returns `{"content":""}`.
- The reconciler thread is not started.

### Real-Docker mode

`mocked=false`: full behaviour as specified in the
[runtime design](container-instances-runtime.md).

### Graceful-degradation contract

AGENTS.md requires Docker failures to be non-fatal. The contract is:

1. **Any** exception thrown by `ContainerGroupRuntime` during `PUT` is caught by
   `ContainerInstanceHandler`, logged at `ERROR` with
   `Docker unavailable for container group {name}; degrading to mocked state`, and the group is
   marked **degraded**.
2. A degraded group behaves exactly as a mocked-mode group for every subsequent operation:
   `provisioningState: "Succeeded"`, containers `Running`, `logs` returns `{"content":""}`,
   actions are pure state transitions.
3. A degraded group is detectable by a client: its `instanceView.events` contains exactly one
   entry

   ```json
   {
     "count": 1,
     "firstTimestamp": "2026-08-22T10:15:00Z",
     "lastTimestamp": "2026-08-22T10:15:00Z",
     "name": "DockerUnavailable",
     "message": "The Docker daemon is not reachable; this container group is emulated without running containers.",
     "type": "Warning"
   }
   ```

4. The reconciler skips degraded groups entirely.
5. `@PostConstruct` never throws. If the reconciler cannot start, the failure is logged at
   `ERROR` and the service continues in degraded-for-all-groups mode.

This mirrors `VmHandler`'s degradation (`src/main/java/io/floci/az/services/vm/VmHandler.java:220-225`),
with the addition of the client-visible `DockerUnavailable` event, because unlike a VM a
container group makes promises (logs, exit codes) that a degraded group cannot keep.

## Storage model

**Backend.** `StorageFactory.create("containerinstance")`
(`src/main/java/io/floci/az/core/storage/StorageFactory.java:49`), obtained once in the
`ContainerInstanceHandler` constructor. This resolves to
`data/containerinstance.json` in `persistent`, `hybrid`, and `wal` modes, and to an in-memory
map in `memory` mode (the global default, `src/main/resources/application.yml:48`).

`StorageFactory.serviceConfig` (`.../StorageFactory.java:136`) needs a new
`case "containerinstance" -> Optional.of(config.storage().services().containerInstance());`
and `EmulatorConfig.ServicesStorageConfig` (`src/main/java/io/floci/az/config/EmulatorConfig.java:79`)
needs a `ServiceStorageConfig containerInstance();` accessor, per the AGENTS.md storage
checklist.

**Key.** `"{subscriptionId}/{resourceGroup}/{containerGroupName}"`, byte-for-byte as the
request supplied them. This matches `VmHandler.storageKey`
(`src/main/java/io/floci/az/services/vm/VmHandler.java:505`).

Listing in a resource group filters `key.toLowerCase().startsWith((sub + "/" + rg + "/").toLowerCase())`;
listing in a subscription filters `key.startsWith(sub + "/")`. Again identical to
`VmHandler.handleListByResourceGroup` / `handleListSubscription`
(`.../VmHandler.java:286-302`).

**Serialized shape.** One `StoredObject` per group, whose `data()` is the Jackson
serialisation of `ContainerInstanceModels.ContainerGroup`:

```json
{
  "subscriptionId": "00000000-0000-0000-0000-000000000001",
  "resourceGroup": "aci-rg",
  "name": "demo-group",
  "location": "eastus",
  "groupId": "3f9a1c7b2e5d",
  "timeCreated": "2026-08-22T10:15:00Z",
  "tags": {"env": "dev"},
  "properties": {
    "osType": "Linux",
    "restartPolicy": "Always",
    "containers": [
      {
        "name": "web",
        "properties": {
          "image": "nginx:1.27",
          "resources": {"requests": {"cpu": 1.0, "memoryInGB": 1.0}},
          "ports": [{"port": 80, "protocol": "TCP"}],
          "environmentVariables": [{"name": "API_TOKEN"}]
        }
      }
    ],
    "ipAddress": {
      "type": "Public",
      "dnsNameLabel": "demo-group",
      "ports": [{"port": 80, "protocol": "TCP"}]
    },
    "volumes": [{"name": "scratch-volume", "emptyDir": {}}]
  },
  "provisioningState": "Succeeded",
  "groupState": "Running",
  "degraded": false,
  "infraContainerId": "9fda5afa3ffe",
  "restartPolicy": "Always",
  "containers": [
    {
      "name": "web",
      "containerId": "9a4f26bc501d",
      "state": "Running",
      "startTime": "2026-08-22T10:15:03Z",
      "previousState": null,
      "previousExitCode": null,
      "previousStartTime": null,
      "previousFinishTime": null,
      "exitCode": null,
      "finishTime": null,
      "detailStatus": "",
      "restartCount": 0,
      "events": []
    }
  ],
  "portMappings": [{"groupPort": 80, "hostPort": 80, "protocol": "TCP"}],
  "volumeNames": ["floci-az-aci-3f9a1c7b2e5d-vol-scratch"],
  "ipAddress": "127.0.0.1",
  "fqdn": "demo-group.eastus.azurecontainer.io",
  "groupEvents": []
}
```

The submitted `properties` block is stored verbatim so `GET` round-trips faithfully for SDKs
and Terraform, exactly as `VmModels.VirtualMachine` does
(`src/main/java/io/floci/az/services/vm/VmModels.java:13-17`) — **with the secret redactions
below applied before storage**.

**What is never persisted and never returned.** These four values are stripped from the stored
`properties` block and held in a process-lifetime `ConcurrentHashMap<String, GroupSecrets>`
keyed by the storage key, inside `ContainerGroupRuntime`:

| JSON path | Reason |
|---|---|
| `properties.containers[].properties.environmentVariables[].secureValue` | `x-ms-secret: true` in the ACI swagger |
| `properties.initContainers[].properties.environmentVariables[].secureValue` | same |
| `properties.volumes[].secret.*` (every value) | secret volume contents |
| `properties.imageRegistryCredentials[].password` | `x-ms-secret: true` |
| `properties.diagnostics.logAnalytics.workspaceKey` | `x-ms-secret: true` |
| `properties.diagnostics.logAnalytics.workspaceResourceId` | `x-ms-secret: true` |
| `properties.volumes[].azureFile.storageAccountKey` | credential |

Consequences, stated explicitly so the implementer does not have to infer them:

- A `GET` after a restart of the emulator returns the group **without** those values. This is
  correct: Azure also never returns them (`secureValue` and `password` are write-only in the
  swagger; `secret` volumes come back as `{}` in the `ContainerGroupsCreateOrUpdate`
  response example).
- After an emulator restart, an existing group's containers keep running (Docker owns them) and
  the reconciler re-adopts them by name. Secrets are only needed to *create* containers, so a
  re-created group after restart would lose them. The reconciler therefore never re-creates
  containers for a group whose secrets are missing; it marks the group `Failed` with a
  `SecretsUnavailableAfterRestart` event. See the
  [runtime reconciliation section](container-instances-runtime.md#reconciliation).
- Nothing secret is ever written to `data/containerinstance.json`, in any storage mode.

## Configuration keys

New block under `floci-az.services.container-instance`. SmallRye maps `.` and `-` to `_` and
uppercases, so `floci-az.services.container-instance.mocked` is
`FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_MOCKED` — the same transformation that produces
`FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED` from `floci-az.services.service-bus.mocked`, which the
`Makefile` already uses.

| Key | Type | Default | Environment variable | Meaning |
|---|---|---|---|---|
| `floci-az.services.container-instance.enabled` | boolean | `true` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_ENABLED` | Serve the provider at all |
| `floci-az.services.container-instance.mocked` | boolean | `true` (interface) / `false` (`application.yml`) | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_MOCKED` | `true` = pure ARM state, no Docker |
| `floci-az.services.container-instance.infra-image` | string | `alpine:3.20` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_INFRA_IMAGE` | Image for the namespace-owning infra container |
| `floci-az.services.container-instance.default-location` | string | `eastus` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_DEFAULT_LOCATION` | `location` used when the request omits it, and the region label in the FQDN |
| `floci-az.services.container-instance.base-port` | int | `8500` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_BASE_PORT` | Low end of the host-port fallback range |
| `floci-az.services.container-instance.max-port` | int | `8599` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_MAX_PORT` | High end of the host-port fallback range |
| `floci-az.services.container-instance.reconcile-interval-seconds` | int | `3` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_RECONCILE_INTERVAL_SECONDS` | Reconciler period |
| `floci-az.services.container-instance.stop-timeout-seconds` | int | `10` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_STOP_TIMEOUT_SECONDS` | `docker stop` grace period |
| `floci-az.services.container-instance.log-max-bytes` | long | `4194304` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_LOG_MAX_BYTES` | Byte cap for a running container's logs (Azure: 4 MB) |
| `floci-az.services.container-instance.log-max-lines` | int | `100000` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_LOG_MAX_LINES` | Line cap for a running container's logs |
| `floci-az.services.container-instance.stopped-log-max-bytes` | long | `16384` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_STOPPED_LOG_MAX_BYTES` | Byte cap for a stopped container's logs (Azure: 16 KB) |
| `floci-az.services.container-instance.stopped-log-max-lines` | int | `1000` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_STOPPED_LOG_MAX_LINES` | Line cap for a stopped container's logs (Azure: 1 000 lines) |
| `floci-az.services.container-instance.keep-running-on-shutdown` | boolean | `false` | `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_KEEP_RUNNING_ON_SHUTDOWN` | Leave containers running when the emulator stops (matches `floci-az.services.aks.keep-running-on-shutdown`, `application.yml:188`) |
| `floci-az.storage.services.container-instance.mode` | string | inherits `floci-az.storage.mode` | `FLOCI_AZ_STORAGE_SERVICES_CONTAINER_INSTANCE_MODE` | Per-service storage override |
| `floci-az.storage.services.container-instance.flush-interval-ms` | long | `5000` | `FLOCI_AZ_STORAGE_SERVICES_CONTAINER_INSTANCE_FLUSH_INTERVAL_MS` | Hybrid-mode flush interval |

The `application.yml` block, to be inserted after the `vm:` block
(`src/main/resources/application.yml:189-192`):

```yaml
    container-instance:
      enabled: true
      mocked: false         # false = back container groups with real Docker containers (default). true = no Docker; pure ARM state
      infra-image: "alpine:3.20"
      default-location: "eastus"
      base-port: 8500
      max-port: 8599
      reconcile-interval-seconds: 3
      stop-timeout-seconds: 10
      log-max-bytes: 4194304
      log-max-lines: 100000
      stopped-log-max-bytes: 16384
      stopped-log-max-lines: 1000
      keep-running-on-shutdown: false
```

and under `floci-az.storage.services` (after `monitor:`, `src/main/resources/application.yml:75-76`):

```yaml
      container-instance:
        flush-interval-ms: 5000
```

The `BannerLogger` line, to be inserted after the `vm` block
(`src/main/java/io/floci/az/core/BannerLogger.java:97-101`), using the existing
`serviceStatusDocker` helper (`.../BannerLogger.java:168`):

```java
if (config.services().containerInstance().enabled()) {
    String aciInfo = config.services().containerInstance().mocked()
            ? "mocked  (no docker)"
            : "infra:" + config.services().containerInstance().infraImage()
                    + "  ports:" + config.services().containerInstance().basePort()
                    + "-" + config.services().containerInstance().maxPort();
    sb.append(serviceStatusDocker("aci", true, aciInfo));
}
```

`BannerLogger.getStorageMode` (`.../BannerLogger.java:150`) is **not** changed: the ACI line
uses the Docker variant, like `vm`, `aks`, `redis`, and `acr`.

## `_admin/reset` behaviour

`ContainerInstanceHandler` implements `Resettable`. `AdminController.reset()`
(`src/main/java/io/floci/az/core/AdminController.java:48`) iterates every CDI-discovered
`Resettable` in non-deterministic order and swallows individual failures, so `clear()` must be
self-contained and idempotent (`src/main/java/io/floci/az/core/Resettable.java:6-12`).

`ContainerInstanceHandler.clear()` performs, in order:

1. Read every stored group with `storage.scan(k -> true)`.
2. For each group, when `mocked=false` and the group is not degraded, call
   `runtime.destroyGroup(group)` inside a `try`/`catch (Exception e)` that logs at `WARN`:
   `Reset: failed to remove Docker resources for container group {name}: {message}`.
   `destroyGroup` stops and removes app containers first, then the infra container, then
   removes named volumes when `ContainerStorageHelper.shouldPruneVolume(config)`
   (`.../ContainerStorageHelper.java:124`) returns `true`, then releases every allocated host
   port with `PortAllocator.release(int)`.
3. Clear the in-memory secret map.
4. `storage.clear()`.

`clear()` does **not** stop the reconciler; the reconciler is idempotent against an empty store.

## Security boundaries and secret handling

| Rule | Enforcement |
|---|---|
| No secret value ever appears in a response body | `ContainerInstanceHandler` builds responses from the **stored** `properties`, which never contains secrets. There is no code path from `GroupSecrets` to a `Response`. |
| No secret value ever appears in a log line | `ContainerGroupRuntime` logs env-var **names** only. The container spec's `env` list is never logged. `ImageCacheService` already logs only the registry host (`ImageCacheService.java:136`). |
| No secret value is ever persisted | Stripped before `storage.put`. See [storage model](#storage-model). |
| Secret volumes are read-only inside containers | `withNamedVolume(name, mountPath, true)` (`ContainerBuilder.java:286`), matching Azure: *"a secret volume is read-only."* |
| Secret volume contents are never written to a host bind mount | Backed by a Docker **named volume**, populated over the Docker archive API with `copyBytesToContainer` (`ContainerLifecycleManager.java:556`). |
| Registry passwords are used once, for the pull, and not retained beyond the group's lifetime | Held in `GroupSecrets`, removed by `destroyGroup` and by `clear()`. |
| The emulator performs no authorization | Consistent with the rest of floci-az. `AzureRequest.authContext()` is populated by the auth pipeline but Container Instances does not inspect it, exactly as `VmHandler` does not. |

Azure marks these properties `x-ms-secret: true` in the `2023-05-01` swagger:
`EnvironmentVariable.secureValue`, `ImageRegistryCredential.password`,
`LogAnalytics.workspaceKey`, `LogAnalytics.workspaceResourceId`,
`ContainerExecResponse.password`, `ContainerAttachResponse.password`.
`SecretVolume` is `"type": "object"` with `additionalProperties: {"type": "string"}` and is not
marked, but is treated as secret here because its documented purpose is secret material.

## Long-running-operation semantics

The `2023-05-01` swagger marks four operations `x-ms-long-running-operation: true`:
`ContainerGroups_CreateOrUpdate` (PUT), `ContainerGroups_Delete` (DELETE),
`ContainerGroups_Restart` (POST restart), and `ContainerGroups_Start` (POST start).
`ContainerGroups_Stop` is **not** marked long-running.

**The emulator completes all four synchronously and never emits `Azure-AsyncOperation`,
`Location`, or `Retry-After`.**

| Operation | Azure | Emulator | Why the client is satisfied |
|---|---|---|---|
| PUT | `201`/`200` then poll until `provisioningState` is terminal | `201` (create) or `200` (update) with `provisioningState: "Succeeded"` or `"Failed"` already terminal | Azure Core's LRO strategy for `azure-async-operation`/`location`/`provisioning-state` treats an initial response whose body already has a terminal `provisioningState` and no polling headers as complete. |
| DELETE | `200`/`202`/`204` | `204` No Content, idempotent for an absent group | A `204` with no `Location` header is terminal. This also avoids the `azurerm` `DeleteThenPoll` hazard that made `VmHandler.handleDelete` return `204` (`src/main/java/io/floci/az/services/vm/VmHandler.java:279-283`): a `202` would make the provider poll the collection endpoint indefinitely. |
| POST start | `202` or `204` | `204` No Content | Terminal, no polling headers. |
| POST restart | `204` | `204` No Content | Terminal. |
| POST stop | `204` | `204` No Content | Matches Azure exactly. |

`PUT` is synchronous in a stronger sense than Azure: it does not return until the infra
container and every app container have been created and started, or a failure has been
recorded. On a cold image cache this can take as long as the pull
(`ImageCacheService.ensureImageExists` waits up to 5 minutes per image,
`src/main/java/io/floci/az/core/docker/ImageCacheService.java:60`). The resulting
`provisioningState` is therefore always terminal.

The deliberate consequence: a client that reads `provisioningState: "Creating"` from a
floci-az response is reading a **degenerate** case that only occurs when the reconciler has not
yet observed a container it just created — which cannot happen, because `PUT` blocks. There is
no `Creating` state on the wire. It exists only inside `ContainerGroupRuntime`.

## Architecture invariants

These are the rules a reviewer checks. Violating one is a defect, not a style preference.

1. **No direct `dockerClient` use in `services/containerinstance/`.** Everything goes through
   `ContainerBuilder` and `ContainerLifecycleManager` (AGENTS.md, *Do Not*). The single new
   Docker capability — bounded log retrieval — is added **to** `ContainerLifecycleManager`, not
   to the service.
2. **No unreachable route.** Every row in the [routing table](#routing-table) has a test that
   asserts a concrete response body. No route returns a placeholder.
3. **Docker failure is never fatal.** No `@PostConstruct`, `@PreDestroy`, reconciler tick, or
   request handler propagates a Docker exception to the caller as a 500. Every catch block logs
   with enough context to diagnose (AGENTS.md, *Code Style*).
4. **The handler is the only place a `Response` is built.** `ContainerGroupRuntime` and
   `ContainerGroupReconciler` return domain objects or throw.
5. **The runtime is the only place Docker is touched.** `ContainerInstanceHandler` and
   `ContainerGroupReconciler` never import `com.github.dockerjava`.
6. **Validation is pure.** `ContainerGroupValidator` has no injected collaborators that perform
   I/O and is unit-testable without a CDI container.
7. **Constructor injection only**, all fields `final` (AGENTS.md, *Dependency injection*).
8. **Every model type is reflection-safe for native image**: `@RegisterForReflection`,
   `@JsonIgnoreProperties(ignoreUnknown = true)`, `@JsonInclude(NON_NULL)` — matching
   `VmModels` (`src/main/java/io/floci/az/services/vm/VmModels.java:19-21`).
9. **The infra container is created first and destroyed last.** Proven necessary: a Docker
   container using network mode `container:<id>` cannot be started while its owner is stopped.
10. **Secrets never cross the storage or logging boundary.** See
    [security boundaries](#security-boundaries-and-secret-handling).

## Deviations register

Every place the emulator knowingly differs from Azure.

| # | Deviation | Azure behaviour | Emulator behaviour | Rationale | Client-detectable? |
|---|---|---|---|---|---|
| D1 | Synchronous LRO | PUT/DELETE/start/restart are long-running; clients poll | All four return terminal responses with no polling headers | A local emulator has no queue; polling would only add latency. `VmHandler` already does this for DELETE. | Yes — the absence of `Azure-AsyncOperation` on PUT is observable. Harmless: the SDK LRO short-circuits on a terminal body. |
| D2 | `api-version` not enforced | Unknown versions are rejected with `InvalidApiVersionParameter` | Any value, including absent, is accepted and served with the `2023-05-01` shape | The three real clients send three different versions (`2023-05-01`, `2024-05-01-preview`, `2025-09-01`). Enforcing any one breaks the others. | Yes — sending `api-version=1999-01-01` succeeds instead of failing. |
| D3 | FQDN is cosmetic | `{dnsNameLabel}.{region}.azurecontainer.io` resolves to the group's public IP | The same string is returned, but nothing resolves it to the group | `EmbeddedDnsServer.resolveARecord` (`src/main/java/io/floci/az/core/dns/EmbeddedDnsServer.java:159`) can only answer with floci-az's own container IP; it has no per-name record table. | Yes — a DNS lookup of the FQDN fails on the host, and inside Docker resolves to floci-az rather than to the group. |
| D4 | Advertised group port may differ from the reachable host port | `ipAddress.ip:port` is directly reachable | `ipAddress.ports[]` echoes the Azure port numbers; the actual host binding is the same number when free and above 1023, otherwise a port from `base-port`–`max-port`. The mapping is published as a `PortMapped` event. | Ports below 1024 need root, and the emulator host's ports are not the emulator's to claim. | Yes — read `instanceView.events[?name=='PortMapped']`. |
| D5 | Single-node scheduling | Groups are scheduled across a fleet; `zones` selects an availability zone | Every group runs on the one local Docker daemon; `zones` is echoed and ignored | There is one node. | No — `zones` round-trips unchanged. |
| D6 | No quotas beyond hard per-group limits | Regional core and container-group quotas apply; `Location_ListUsage` reports real usage | Per-group limits (60 containers, 20 volumes, 5 ports, 31 CPU, 240 GB) are enforced; `usages` reports a fixed `currentValue` computed from stored groups against Azure's documented limits | Regional capacity is meaningless locally. | Yes — 200 container groups can be created locally. |
| D7 | CPU requests and limits are not enforced | `cpu` throttles the container | `cpu` is validated and echoed; no Docker CPU constraint is applied | `ContainerSpec` (`src/main/java/io/floci/az/core/docker/ContainerSpec.java:35-56`) has no CPU field; adding one for a single consumer changes shared core. Memory *is* enforced via `withMemoryBytes`. | Only by measurement. |
| D8 | `exec` and `attach` are not implemented | Return a websocket URI and a password | `501` with `{"error":{"code":"NotImplemented","message":"..."}}` | floci-az serves no websocket data plane. | Yes — explicit. |
| D9 | `azureFile` volumes are local Docker volumes | Mount a real Azure File share | Mount a Docker named volume derived from `{storageAccountName}/{shareName}`; contents are not shared with the emulated Blob/File service | Wiring ACI to the emulated storage data plane is a separate feature. | Yes — an `AzureFileEmulated` event is emitted. |
| D10 | `gitRepo` volumes are rejected | Clone the repository into the volume | `400 NotSupported` | Cloning arbitrary repositories from a local emulator is out of scope and would perform unbounded network I/O. | Yes — explicit. |
| D11 | Windows containers run as Linux | `osType: Windows` schedules Windows containers | Accepted, echoed, and run as Linux | The Docker daemon this emulator targets runs Linux containers. Azure itself restricts multi-container groups, `emptyDir`, and secret volumes to Linux. | Only by inspecting the running container. |
| D12 | `Never` restart policy is absolute | *"If the container exits with a nonzero exit code, the platform might restart the container"* even under `Never` | `Never` never restarts, whatever the exit code | Azure's behaviour here is explicitly non-deterministic (*"might"*). A deterministic emulator is more useful, and matches what users expect `Never` to mean. | Yes — a nonzero-exit container under `Never` stays `Terminated` locally. |
| D13 | Managed identity is metadata only | `identity` grants the containers a token endpoint | `identity` is echoed with synthetic `principalId`/`tenantId`; no IMDS endpoint is injected into the containers | Injecting IMDS into arbitrary containers is a separate feature shared with the Managed Identity service. | Yes — an SDK inside the container gets no token. |
| D14 | Deleting a group returns `204`, never `202` | `200`, `202`, or `204` | Always `204`, idempotent | `azurerm`'s `DeleteThenPoll` polls forever on `202` against the emulator; `VmHandler` hit the same problem (`src/main/java/io/floci/az/services/vm/VmHandler.java:279-283`). | Yes — but every documented Azure status is accepted by clients. |
| D15 | `provisioningState` never reports `Creating` on the wire | Transitions `Pending` → `Creating` → `Succeeded`/`Failed` | Only `Succeeded` or `Failed` ever leave the handler, because `PUT` blocks until terminal | See [LRO semantics](#long-running-operation-semantics). | Yes — a client polling for `Creating` never observes it. |
| D16 | No image cached-image list | `Location_ListCachedImages` returns the platform's cached images | Returns `{"value":[]}` | The emulator has no curated cache; `ImageCacheService` caches per process. | Yes — the list is always empty. |

## Assumptions register

Rules that could not be grounded in the ACI swagger, the ACI documentation, or the repository.
Each carries the recommended default and the reasoning. An implementer follows the
**Recommended value** column; no further decision is required.

| # | Question | Recommended value | Reasoning |
|---|---|---|---|
| A1 | `provisioningState` has no enum in the `2023-05-01` swagger (`"type": "string"`, `readOnly`). Which values does the emulator emit? | `"Succeeded"` and `"Failed"` only | These are the two the swagger's own examples use (`ContainerGroupsGet_Succeeded.json` → `Succeeded`; `ContainerGroupsGet_Failed.json` → `Failed`). Mirrors `VmHandler`, which emits `Creating`/`Succeeded`. |
| A2 | `instanceView.state` for a container group has no enum. Which values? | `"Pending"`, `"Running"`, `"Succeeded"`, `"Stopped"`, `"Failed"` | `Pending` appears verbatim in `ContainerGroupsGet_Failed.json`. `Running`, `Succeeded`, `Stopped`, and `Failed` are the natural terminal/steady counterparts and are what `az container show --query instanceView.state` prints in practice. |
| A3 | `containers[].properties.instanceView.currentState.state` has no enum. Which values? | `"Waiting"`, `"Running"`, `"Terminated"` | `Waiting` appears in `ContainerGroupsGet_Failed.json`; `Terminated` is stated verbatim in the restart-policy documentation (*"the container's status is set to `Terminated`"*); `Running` is the third and only remaining steady state. |
| A4 | Which error `code` for a container-group name that violates the naming regex? | `InvalidResourceName`, HTTP `400` | ARM's standard code for a name that fails a provider's naming rules. |
| A5 | Which error `code` for a body that fails schema validation (missing required property, wrong type)? | `InvalidRequestContent`, HTTP `400` | ARM's standard code for a malformed request body. |
| A6 | Which error `code` for a semantically invalid but well-formed property (bad enum value, out-of-range number, duplicate name, exceeded per-group limit)? | `InvalidParameter`, HTTP `400`, with `target` set to the offending JSON path | ARM's standard code when the body parses but a value is unacceptable. Using one code with a precise `target` is more useful than inventing per-rule codes that no client recognises. |
| A7 | Which error `code` for an unsupported-but-well-formed feature (`gitRepo` volume, `exec`, `attach`)? | `NotSupported` (HTTP `400`) for `gitRepo`; `NotImplemented` (HTTP `501`) for `exec`/`attach` | `gitRepo` is a request the emulator will never accept — a client error. `exec`/`attach` are valid requests the emulator cannot serve — a server limitation. |
| A8 | What `ip` does the emulator report for `ipAddress.type: "Public"`? | `"127.0.0.1"` when floci-az runs on the host; the infra container's Docker-network IP when floci-az runs inside Docker | This is the address that actually works from the client's position, and mirrors `ContainerLifecycleManager.resolveEndpoint`'s host/container split (`.../ContainerLifecycleManager.java:674`). |
| A9 | What `ip` for `ipAddress.type: "Private"`? | The same value as `Public` | There is no VNet locally; reporting a fictional `10.x` address that nothing routes to would be worse. |
| A10 | Where does the `{region}` in the FQDN come from? | `properties` `location`, lowercased with spaces removed; `floci-az.services.container-instance.default-location` when `location` is absent | Azure builds `aci-demo.eastus.azurecontainer.io` from the region. The swagger example shows `dnsnamelabel1.azure-container.io`, which is a placeholder, not the real format; the ACI quickstart shows the real one. |
| A11 | How is `groupId` generated, and is it stable across updates? | `UUID.randomUUID().toString().replace("-", "")` truncated to 12 characters, assigned once on create and never regenerated | Matches `VmContainerManager.containerName`'s treatment of `vmId` (`src/main/java/io/floci/az/services/vm/VmContainerManager.java:146-154`). Stability is required so container names survive an update. |
| A12 | Does `PATCH` merge `properties` or only `tags`? | Only `tags`. `properties` in a `PATCH` body is ignored. | `ContainerGroups_Update`'s request body in the swagger is a `Resource` (`id`, `name`, `type`, `location`, `tags`, `zones`) — it has no `properties`. `VmHandler.handleUpdateTags` does the same (`src/main/java/io/floci/az/services/vm/VmHandler.java:249-265`). |
| A13 | Does `PUT` on an existing group re-create its containers? | Yes: the existing group is destroyed and re-created, keeping `groupId`, `name`, and `timeCreated` | ACI has no in-place container mutation; Azure replaces the group. Keeping `groupId` keeps container names and volume names stable. |
| A14 | What does `Location_ListUsage` report? | Two `Usage` entries — `id: "ContainerGroups"` with `limit: 100` and `id: "StandardCores"` with `limit: 100`, `currentValue` computed from the stored groups in that subscription | The changeable-limit table in the ACI quota documentation gives 100 for both. |
| A15 | What does `Location_ListCapabilities` report? | One entry: `resourceType: "containerGroups"`, `osType: "Linux"`, `location: {location}`, `ipAddressType: "Public"`, `gpu: "None"`, `capabilities: {"maxMemoryInGB": 240.0, "maxCpu": 31.0, "maxGpuCount": 0.0}` | The standard-container-resources table in the ACI quota documentation gives max CPU 31 and max memory 240 GB. |
| A16 | Is `location` required on `PUT`? | No. When absent, `floci-az.services.container-instance.default-location` (`eastus`) is used and echoed. | The swagger's `Resource.location` is not in a `required` list. `VmHandler` defaults to `"eastus"` the same way (`src/main/java/io/floci/az/services/vm/VmHandler.java:188`). |
| A17 | What happens to a group whose secrets are lost across an emulator restart? | The reconciler does not re-create its containers; the group becomes `provisioningState: "Failed"` with a `SecretsUnavailableAfterRestart` event | Silently re-creating containers without their secrets would produce a running-but-broken group, which is worse than an explicit failure. |
| A18 | Is `initContainers` implemented? | Yes, as sequential app containers started before the main containers and waited to exit `0`. A nonzero exit fails the group. | Azure runs init containers to completion, in order, before the main containers. Implementing it is cheap given the runtime already sequences container starts. |
| A19 | What `detailStatus` value is used? | `""` while `Waiting` or `Running`; `"Completed"` on exit code `0`; `"Error"` on any nonzero exit code | The swagger's `Waiting` example uses `""`. `Completed`/`Error` are what `az container show` prints for terminated containers. |
| A20 | Does the emulator emit `Pulling`/`Pulled`/`Failed`/`BackOff` events? | Yes: `Pulling` and `Pulled` on a successful pull, `Pulling` and `Failed` on a failed pull, with the exact message templates in the [runtime design](container-instances-runtime.md#events) | The ACI troubleshooting documentation shows exactly these four event names and message shapes; matching them makes emulator output recognisable to anyone who has read a real `az container show`. |

## Open questions

**None.** Every decision above is made. The [assumptions register](#assumptions-register)
records the twenty rules that Azure does not pin down, each with the value to implement.
No item in this document set requires a human decision or further research before
implementation can start.

## Authoritative references

- Azure REST API specs, `containerinstance`, stable `2023-05-01`:
  `specification/containerinstance/resource-manager/Microsoft.ContainerInstance/ContainerInstance/stable/2023-05-01/containerInstance.json`
  in <https://github.com/Azure/azure-rest-api-specs> — the pinned contract.
- Same folder, `examples/`: `ContainerGroupsCreateOrUpdate.json`,
  `ContainerGroupsGet_Succeeded.json`, `ContainerGroupsGet_Failed.json`,
  `ContainerGroupsList.json`, `ContainerGroupsUpdate.json`, `ContainerListLogs.json`.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-container-groups>
  — container-group concepts, shared network namespace, `localhost` reachability, resource
  allocation, minimum 1 CPU / 1 GB per group.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-restart-policy>
  — `Always` / `OnFailure` / `Never` semantics, `Terminated` state, nonzero-exit behaviour.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-quotas>
  — hard limits (60 containers, 20 volumes, 5 ports per IP, 4 MB / 16 KB / 1 000-line log caps)
  and standard container resources (max 31 CPU, 240 GB memory, 50 GB storage).
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-troubleshooting>
  — naming conventions table, `OsVersionNotSupported`, image-pull event shapes.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-volume-emptydir>
  — `emptyDir` semantics, Linux-only, 50 GB maximum.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-volume-secret>
  — secret volumes are read-only, tmpfs-backed, Base64-encoded in the request.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-quickstart>
  — the real FQDN format, `aci-demo.eastus.azurecontainer.io`.
- <https://learn.microsoft.com/en-us/azure/azure-resource-manager/management/resource-name-rules>
  — `Microsoft.ContainerInstance` / `containerGroups`: resource-group scope, 1–63 characters,
  lowercase letters, numbers, and hyphens, no leading or trailing hyphen, no consecutive hyphens.
- <https://learn.microsoft.com/en-us/java/api/overview/azure/resourcemanager-containerinstance-readme>
  — Java management SDK overview.
- `com.azure.resourcemanager:azure-resourcemanager-containerinstance:2.53.13`,
  `ContainerInstanceManagementClientImpl.java:201` — `apiVersion = "2023-05-01"`.
- `hashicorp/terraform-provider-azurerm` `v3.117.0`,
  `internal/services/containers/container_group_resource.go:22` — `containerinstance/2023-05-01`.
- Repository: `AGENTS.md`, `src/main/java/io/floci/az/core/AzureRoutingFilter.java`,
  `.../core/ServiceRoutes.java`, `.../core/AzureServiceRegistry.java`,
  `.../core/docker/`, `.../core/dns/EmbeddedDnsServer.java`,
  `.../services/vm/`, `src/main/resources/application.yml`.
- [floci-io/floci-az#59](https://github.com/floci-io/floci-az/issues/59) — requirement baseline.
- [floci-io/floci-az#62](https://github.com/floci-io/floci-az/issues/62) — Azure Container Apps,
  which builds on this work.
- floci-az commit `8af0ad5` (PR #106) — removal of the unreachable `ContainerAppService`.
