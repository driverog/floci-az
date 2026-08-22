# Container Instances — Roadmap

**Status:** Proposed architecture

**Last updated:** 2026-08-22

The execution plan for `Microsoft.ContainerInstance/containerGroups`: an ordered commit
sequence, each with its conventional-commit subject, the exact files it creates and modifies,
the tests that must be green before the next commit starts, and how to roll it back.

The architecture is in [Container Instances](container-instances.md). The wire contract is in
the [resource model](container-instances-resource-model.md), the Docker mapping in the
[runtime design](container-instances-runtime.md), and the test cases named below in the
[test plan](container-instances-test-plan.md).

## Decision summary

Seven commits, in order. Commits 1–4 land a complete mocked-mode service that works with no
Docker socket. Commits 5–7 add the Docker runtime, logs, and the compatibility suites.

**Every commit ships working routes.** No commit introduces a route that returns a placeholder,
and no commit leaves a CDI-registered bean unreachable — the two failures that got
`ContainerAppService` deleted in PR #106. Each commit's "green before moving on" list is the
gate.

## Goals

- An order in which every commit is independently reviewable and independently revertable.
- A clear split between what ships now and what is deferred, with the reason.
- A forward-compatibility contract with Azure Container Apps
  ([floci-io/floci-az#62](https://github.com/floci-io/floci-az/issues/62)) so this runtime layer
  does not have to be rewritten to support it.

## Non-goals

- Estimating effort or scheduling.
- Prescribing branch or pull-request granularity beyond the review-boundary section.

## Commit sequence

### Commit 1 — config, storage, and banner wiring

`feat(aci): register container instance configuration and storage`

**Creates:** nothing.

**Modifies:**

| File | Change |
|---|---|
| `src/main/java/io/floci/az/config/EmulatorConfig.java` | Add `ContainerInstanceConfig containerInstance();` to `ServicesConfig` (after `VmConfig vm();`, line 129) and the `interface ContainerInstanceConfig` with the fourteen `@WithDefault` methods from [the configuration table](container-instances.md#configuration-keys). Add `ServiceStorageConfig containerInstance();` to `ServicesStorageConfig` (line 79). |
| `src/main/resources/application.yml` | Add the `container-instance:` block after the `vm:` block (line 189) and the `container-instance:` storage block after `monitor:` (line 75). |
| `src/main/java/io/floci/az/core/storage/StorageFactory.java` | Add `case "containerinstance" -> Optional.of(config.storage().services().containerInstance());` to `serviceConfig` (line 136). |
| `src/main/java/io/floci/az/core/BannerLogger.java` | Add the `aci` block after the `vm` block (lines 97–101), using `serviceStatusDocker` (line 168). |

**Green before moving on:** `./mvnw test` passes unchanged. The application starts and the
banner shows the `aci` line — asserted later by
`ContainerInstanceStorageTest#bannerReportsTheService`, which lands in commit 4.

**Rollback:** revert the commit. Nothing depends on the new config yet; SmallRye tolerates the
extra `application.yml` keys disappearing.

### Commit 2 — models, validator, and error catalog

`feat(aci): add container group models and request validation`

**Creates:**

| File | Contents |
|---|---|
| `src/main/java/io/floci/az/services/containerinstance/ContainerInstanceModels.java` | `ContainerGroup`, `ContainerRecord`, `EventRecord`, `PortMapping`, `GroupSecrets`, and the enums `ContainerStateValue` (`WAITING`, `RUNNING`, `TERMINATED`), `GroupStateValue` (`PENDING`, `RUNNING`, `SUCCEEDED`, `STOPPED`, `FAILED`), `RestartPolicy` (`ALWAYS`, `ON_FAILURE`, `NEVER`). All `@RegisterForReflection`, `@JsonIgnoreProperties(ignoreUnknown = true)`, `@JsonInclude(NON_NULL)`. |
| `src/main/java/io/floci/az/services/containerinstance/ContainerInstanceErrors.java` | One static factory per row of the [error catalog](container-instances-resource-model.md#error-catalog), each returning a `Response` built on `ArmErrors.error` with a `target` field. Includes the `{reason}` string table. |
| `src/main/java/io/floci/az/services/containerinstance/ContainerGroupValidator.java` | `@ApplicationScoped`, no I/O collaborators. One method per rule group, applied fail-fast in the order V1–V50. Returns `Optional<Response>`. |

**Modifies:** `src/main/java/io/floci/az/core/arm/ArmErrors.java` — add
`public static Response error(int status, String code, String message, String target)` that
omits `target` from the JSON when it is null or blank. The three-argument overload delegates
with `null`. This is additive; no existing call site changes.

**Green before moving on:** the whole
[validation section](container-instances-test-plan.md#validation) of the test plan — every case
V1–V50 plus `firstFailingRuleWins` — written as a plain JUnit test against
`ContainerGroupValidator` (no `@QuarkusTest` needed, because the validator is pure). The two
route-level cases (`logsForUnknownContainerReturns404`, `invalidTailParameterRejected`) are
deferred to commit 3 because they need the handler.

**Rollback:** revert. Nothing references these classes yet.

### Commit 3 — the handler, mocked mode only

`feat(aci): serve Microsoft.ContainerInstance container groups`

**Creates:**

| File | Contents |
|---|---|
| `src/main/java/io/floci/az/services/containerinstance/ContainerInstanceHandler.java` | `@ApplicationScoped`, implements `AzureServiceHandler` and `Resettable`. `getServiceType()` returns `"containerinstance"`; `routes()` returns `ServiceRoutes.builder().provider("Microsoft.ContainerInstance").build()`; `enabled(String)` returns `config.services().containerInstance().enabled()`. Implements every row of the [routing table](container-instances.md#routing-table) in mocked-mode semantics. Constructor-injects `EmulatorConfig`, `ContainerGroupValidator`, and `StorageFactory`. |

**Modifies:** nothing. The routing filter picks the handler up from `routes()` at
`@PostConstruct` (`src/main/java/io/floci/az/core/AzureRoutingFilter.java:177`), and
`AzureServiceRegistry.isEnabled` (`.../AzureServiceRegistry.java:44`) delegates to the handler's
own `enabled(String)`. **This is the commit that would have failed under the #106 pattern**, and
it does not: the handler declares its route, owns its enablement, reads its config, and every
route returns a real body.

**Green before moving on:**

- [Routing](container-instances-test-plan.md#routing) — all seven cases, including
  `providerNamespaceIsClaimedExactlyOnce` and `everyRoutingTableRowReturnsARealBody`.
- [CRUD](container-instances-test-plan.md#crud) — all nineteen cases.
- [Actions](container-instances-test-plan.md#actions) — all eight cases.
- [Logs](container-instances-test-plan.md#logs) — all three cases.
- [Reset](container-instances-test-plan.md#reset) — `resetRemovesEveryContainerGroup` and
  `resetIsIdempotent`.
- [No-Docker-socket guarantee](container-instances-test-plan.md#no-docker-socket-guarantee) —
  `serviceWorksInMockedModeWithNoDockerSocket`.
- The two deferred validation cases from commit 2.

**Rollback:** revert. The routing filter simply stops seeing the provider claim and
`Microsoft.ContainerInstance` paths fall through to the generic ARM handler, as they do today.

### Commit 4 — storage modes and documentation

`docs(aci): document container instances service`

**Creates:**

| File | Contents |
|---|---|
| `docs/services/container-instances.md` | The user-facing service page, following the shape of `docs/services/vm.md`: what is supported, configuration keys, a worked `az container create` example, the deviations table condensed from [the register](container-instances.md#deviations-register). |
| `src/test/java/io/floci/az/services/containerinstance/ContainerInstanceStorageTest.java` | The four cases from [storage modes](container-instances-test-plan.md#storage-modes). |

**Modifies:**

| File | Change |
|---|---|
| `mkdocs.yml` | Add `- Azure Container Instances: services/container-instances.md` to the `Services` nav, after `Azure Container Registry`. |
| `docs/services/index.md` | Add the service to the overview table. |
| `README.md` | Add the service to the supported-services list. |

**Green before moving on:** `ContainerInstanceStorageTest`, and `mkdocs build --strict` if
mkdocs is available.

**Rollback:** revert. Documentation only, plus one test class.

### Commit 5 — core Docker capabilities

`feat(docker): add bounded log retrieval and container state inspection`

**Creates:** nothing.

**Modifies:**

| File | Change |
|---|---|
| `src/main/java/io/floci/az/core/docker/ContainerLifecycleManager.java` | Add the `LogResult` record, `fetchLogs(String, Integer, boolean, long, int, Duration)`, the `ContainerRuntimeState` record, and `inspectState(String)` — all specified in the [runtime design](container-instances-runtime.md#new-core-methods). Imports `LogContainerCmd`; `Frame` (line 15) and `ResultCallback` (line 5) are already imported. |
| `src/main/java/io/floci/az/core/docker/ImageCacheService.java` | Add `ensureImageExists(String, AuthConfig)`; the existing one-argument method delegates with `null`. `resolveAuth` gains a null-check so a supplied `AuthConfig` wins over configuration. |

**Green before moving on:** a new
`src/test/java/io/floci/az/core/docker/ContainerLifecycleManagerLogTest.java`, Docker-guarded
with the `assumeTrue` from `VmDockerTest` (`src/test/java/io/floci/az/services/vm/VmDockerTest.java:72-74`),
asserting the four spike results:

| Case | Assertion |
|---|---|
| `fetchLogsReturnsStdoutAndStderrInterleaved` | Content contains all of `out-1`…`out-5` and `err-1`…`err-5` |
| `fetchLogsHonoursTail` | `withTail(3)` returns exactly 3 lines |
| `fetchLogsHonoursTimestamps` | Every line matches `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z ` |
| `fetchLogsStopsAtTheByteCap` | A container writing 5 MB returns at most `maxBytes` characters and `truncated()` is `true` |
| `fetchLogsWorksOnAStoppedContainer` | An exited container's logs are still returned |
| `fetchLogsThrowsNotFoundForARemovedContainer` | `NotFoundException` is thrown |
| `inspectStateReportsExitCodeAndTimes` | `exists` true, `running` false, `exitCode` 3, `startedAt` and `finishedAt` non-null |
| `inspectStateReturnsAbsentForAMissingContainer` | `ContainerRuntimeState.ABSENT` |

**Rollback:** revert. Both additions are purely additive to core; nothing calls them yet.

### Commit 6 — the Docker runtime

`feat(aci): back container groups with real Docker containers`

**Creates:**

| File | Contents |
|---|---|
| `src/main/java/io/floci/az/services/containerinstance/ContainerGroupRuntime.java` | `@ApplicationScoped`. `createGroup`, `destroyGroup`, `stopGroup`, `startGroup`, `restartGroup`, `repairInfra`, `readLogs`, and the private helpers named in the [runtime design](container-instances-runtime.md#container-group-creation). Holds the process-lifetime `ConcurrentHashMap<String, GroupSecrets>`. |
| `src/main/java/io/floci/az/services/containerinstance/ContainerGroupReconciler.java` | `@ApplicationScoped`. The `aci-reconciler` scheduled executor, the daemon ping pre-check, the adoption pass, and the two state machines. |
| `src/test/java/io/floci/az/services/containerinstance/ContainerInstanceDockerTest.java` | Every case from the [state machine](container-instances-test-plan.md#state-machine) and [allocation and identity stability](container-instances-test-plan.md#allocation-and-identity-stability) sections. |
| `src/test/java/io/floci/az/services/containerinstance/ContainerInstanceSecretSafetyTest.java` | The seven cases from [secret safety](container-instances-test-plan.md#secret-safety). |
| `src/test/java/io/floci/az/services/containerinstance/ContainerInstanceNoDockerTest.java` | The three cases from [the no-Docker-socket guarantee](container-instances-test-plan.md#no-docker-socket-guarantee). |
| `src/test/java/io/floci/az/services/containerinstance/ContainerInstanceFixtures.java` | The shared fixture file from the [test plan](container-instances-test-plan.md#containerinstancefixturesjava). |

**Modifies:**

| File | Change |
|---|---|
| `src/main/java/io/floci/az/services/containerinstance/ContainerInstanceHandler.java` | Inject `ContainerGroupRuntime` and `ContainerGroupReconciler`; branch on `mocked`; add `@PostConstruct` starting the reconciler and `@PreDestroy` tearing groups down; implement the graceful-degradation contract; implement `clear()`'s Docker teardown. |

**Green before moving on:** every test from commits 3 and 4, still passing, plus all three new
test classes. `ContainerInstanceDockerTest` and `ContainerInstanceSecretSafetyTest`'s
persistent-storage case need a Docker socket and skip cleanly without one;
`ContainerInstanceNoDockerTest` runs unconditionally and is the CI gate.

**Rollback:** revert this commit alone and the service falls back to mocked mode, still fully
functional. This is the deliberate design of the split: commit 6 is the riskiest change and is
independently revertable without breaking the wire contract.

### Commit 7 — compatibility suites

`test(aci): add container instance SDK, CLI and Terraform compatibility tests`

**Creates:**

| File | Contents |
|---|---|
| `compatibility-tests/sdk-test-java/src/test/java/io/floci/az/compat/ContainerInstanceCompatibilityTest.java` | The complete test from the [test plan](container-instances-test-plan.md#sdk-test-java). |
| `compatibility-tests/compat-azcli/test/container-instances.bats` | The complete bats file from the [test plan](container-instances-test-plan.md#compat-azcli). |

**Modifies:**

| File | Change |
|---|---|
| `compatibility-tests/sdk-test-java/pom.xml` | Add `com.azure.resourcemanager:azure-resourcemanager-containerinstance:2.53.13`. |
| `compatibility-tests/compat-azcli/test/test_helper/common-setup.bash` | Add `ACI_NAME` and `ACI_DNS_LABEL` exports. |
| `compatibility-tests/compat-terraform/main.tf` | Add the `azurerm_container_group` resource and its three outputs. |
| `compatibility-tests/compat-terraform/test/terraform.bats` | Add the three spot checks. |
| `compatibility-tests/compat-opentofu/main.tf` and `test/*.bats` | The same additions, verbatim. |
| `Makefile` | Add the `test-aci-compat` target next to `test-servicebus-compat` (line 248). |

**Environment-variable sync check** — the AGENTS.md obligation, discharged explicitly:

1. `git diff` the `Makefile` and confirm no `SUITE_ENV_*` variable changed.
2. `git diff` `.github/workflows/compatibility.yml` and confirm it is untouched.
3. Confirm no suite Dockerfile gained an `ENV`.

**No suite in this feature introduces a new environment variable**, so all three checks pass by
inspection and neither the `Makefile` `SUITE_ENV_*` block (lines 40–49) nor the CI matrix
`extra_env` entries change. Record that in the pull-request description so the reviewer sees the
obligation was considered rather than missed.

**Green before moving on:** `make test-aci-compat`, `make test-azcli-compat`, and
`make test-terraform-compat` all pass against an emulator started with
`FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_MOCKED=false` and the Docker socket mounted.

**Rollback:** revert. Test-only.

## Review boundaries

| Reviewable independently | Commits | Why |
|---|---|---|
| Wiring and contract | 1, 2, 3, 4 | A complete, tested, mocked-mode ARM service. A reviewer can check the wire contract against the [resource model](container-instances-resource-model.md) without thinking about Docker. |
| Core Docker capability | 5 | Two additive methods on `ContainerLifecycleManager` and `ImageCacheService`, with their own Docker-guarded tests. Reviewable by anyone who knows `core/docker/` and nothing about ACI. |
| Runtime | 6 | The pod pattern, the reconciler, and the state machines. The largest change and the one that most needs the [spike evidence](container-instances-runtime.md#spike-shared-network-namespace) in front of the reviewer. |
| Compatibility | 7 | Test-only; reviewable against the three client APIs. |

Commits 1–4 can merge before 5–7 exist and leave the emulator in a coherent, useful state:
container groups are ARM-visible and Terraform-manageable, they just do not run containers.

## Deferred

| Deferred | Why | What would unblock it |
|---|---|---|
| `Containers_ExecuteCommand` and `Containers_Attach` | Both return a websocket URI and a password. floci-az serves no websocket data plane, and `ContainerLifecycleManager.execInContainer` (`src/main/java/io/floci/az/core/docker/ContainerLifecycleManager.java:518`) is request/response, not a stream. | A websocket endpoint in the emulator plus a bridge from `execCreateCmd`/`execStartCmd` to it. Azure Container Apps (#62) wants the same thing, so it should be built once, in `core/`. |
| A resolvable `{dnsNameLabel}.{region}.azurecontainer.io` | `EmbeddedDnsServer.resolveARecord` (`src/main/java/io/floci/az/core/dns/EmbeddedDnsServer.java:159`) can only answer with floci-az's own container IP; it has no per-name record table. | Replace that `Optional.of(myIp)` with a lookup in a `Map<String, String>` registry that services write into, then register each group's FQDN against the infra container's IP. Shared with #62's ingress FQDNs. |
| CPU request and limit enforcement | `ContainerSpec` (`src/main/java/io/floci/az/core/docker/ContainerSpec.java:35-56`) has no CPU field, and adding one for a single consumer changes shared core. | Add `nanoCpus` to `ContainerSpec` and `HostConfig.withNanoCPUs` in `buildHostConfig` (`.../ContainerLifecycleManager.java:609`), when a second consumer wants it. |
| Virtual-network integration (`subnetIds`, `serviceAssociationLinks`) | Requires the emulated `Microsoft.Network` service to model real Docker networks per subnet, which it does not. | A subnet-to-Docker-network mapping in `NetworkService`. |
| Log Analytics forwarding (`diagnostics.logAnalytics`) | The emulated Monitor service has no ingestion endpoint for container logs. | A log-ingestion route in `services/monitor/`. |
| Managed-identity tokens inside containers | Requires injecting an IMDS endpoint address into every container and pointing it at floci-az's managed-identity service. | `AZURE_POD_IDENTITY_AUTHORITY_HOST` injection into the container's environment, which the compatibility suites already use for the emulator itself (`Makefile:38`). Shared with #62. |
| `gitRepo` volumes | Cloning arbitrary repositories from a local emulator performs unbounded network I/O and is out of scope. | A decision to accept that cost. Currently rejected with `400 NotSupported` (rule V29). |
| Azure Files data-plane sharing | An `azureFile` volume is a local Docker volume; it is not the same bytes as the emulated File/Blob service. | A FUSE or sidecar bridge between a Docker volume and the emulated storage account. |
| Pagination (`nextLink`) | No client in the compatibility suites pages a container-group list, and the emulator holds a small number of groups. | A `$skipToken` implementation shared with the other ARM list endpoints, none of which page today. |
| Windows containers | The Docker daemon this emulator targets runs Linux containers. Azure itself restricts multi-container groups, `emptyDir`, and secret volumes to Linux. | A Windows Docker daemon, which is out of scope for floci-az. |

## Forward compatibility with Azure Container Apps (#62)

Issue #62 (`Microsoft.App/managedEnvironments` + `Microsoft.App/containerApps`) is explicitly
built on this work: *"build on the ACI container lifecycle and pull images via the `acr`
registry sidecar."* This section states what it inherits and what it must **not** try to
inherit, so this design does not have to be rewritten when #62 lands.

### What Container Apps reuses, unchanged

| Capability | Where it lives after this work | Why it is reusable |
|---|---|---|
| Container lifecycle: create, start, stop, restart, remove | `ContainerLifecycleManager` (unchanged) plus the ordering rules in `ContainerGroupRuntime` | Nothing in the ordering rules is ACI-specific: they are properties of the Docker pod pattern. A Container Apps *replica* is a pod with the same constraints. |
| The pod pattern itself: infra container owning the namespace, members joining with `container:<id>` | `ContainerGroupRuntime.createGroup` steps 3–9 | A Container Apps revision with a sidecar has exactly the same shape. |
| Bounded log retrieval | `ContainerLifecycleManager.fetchLogs` — added in `core/docker/`, not in the service | Deliberately general-purpose: no ACI type appears in its signature. Container Apps' `getLogs` calls it directly. |
| Container state inspection | `ContainerLifecycleManager.inspectState` — also in `core/docker/` | Same reason. |
| Per-request registry credentials | `ImageCacheService.ensureImageExists(String, AuthConfig)` | Container Apps' `registries[]` block maps onto the same `AuthConfig`. |
| Host port allocation and release | `PortAllocator.allocate` / `release` / `markReserved`, plus the free-port-preference rule | Container Apps ingress needs a host port per revision. The prefer-the-declared-port-then-fall-back rule applies unchanged. |
| Volume backing for `emptyDir` and `secret` | `ContainerGroupRuntime`'s volume helpers | Container Apps has the same two volume kinds under different names. |
| Naming, labelling, and namespacing | `ContainerStorageHelper.dockerName` plus the `floci_aci_*` label scheme | Container Apps adds `floci_app_*` labels with the same structure. |
| Graceful degradation with no Docker daemon | The [degradation contract](container-instances.md#graceful-degradation-contract) and the `DockerUnavailable` event | Identical requirement. |

**Recommended refactor at the start of #62, not before:** move the pod mechanics
(`createPod`, `destroyPod`, `stopPod`, `startPod`, `repairPodInfra`) out of
`ContainerGroupRuntime` into a new `core/docker/DockerPodManager`, leaving
`ContainerGroupRuntime` as the ACI-specific translation layer. Doing it now would be a shared
abstraction with one consumer, which AGENTS.md warns against
(*"Follow existing project patterns before introducing new abstractions"*). Doing it when the
second consumer exists is the right moment, and the split point is already clean: nothing in
`createGroup` steps 3–9 references an ACI type except through the parameters it is handed.

### What Container Apps must **not** inherit from this design

| Concept | Why it does not transfer |
|---|---|
| Ingress routing | ACI has no HTTP proxy: a group publishes a host port and that is all. Container Apps needs `ingress.external`, `ingress.targetPort`, an FQDN that actually routes, and traffic splitting across revisions. That is an HTTP reverse proxy inside floci-az, not a port binding, and it must be built fresh. Do not try to express it as `ipAddress.ports[]`. |
| Revisions | ACI's `PUT` destroys and re-creates the group (assumption A13). Container Apps must keep the previous revision's containers running while the new one starts, and split traffic between them. The ACI create path is deliberately destructive and is the wrong base. |
| Scale-to-zero and replica counts | ACI runs exactly one instance of each container, forever, and the reconciler restarts anything that dies. Container Apps must scale a revision to N replicas and to zero, which means N pods per revision and a scaler that decides N. The ACI reconciler's "one container per spec entry, always restart it" loop is the wrong shape and must not be extended into a scaler. |
| `restartPolicy` semantics | ACI's `Always` / `OnFailure` / `Never` is a group-level property with the emulator owning restarts. Container Apps replicas are always restarted; the policy concept does not exist. Reusing `RestartPolicy` would import a distinction Container Apps does not have. |
| `instanceView` shape | `ContainerGroupPropertiesInstanceView` with `state` and `events`, and per-container `currentState`/`previousState`/`restartCount`, is the ACI contract. Container Apps reports revision health and replica status through a different schema. Share the *observation* (`inspectState`), not the *projection*. |
| Synchronous LRO | ACI's `PUT` blocks until terminal ([deviation D1](container-instances.md#deviations-register)) because a container group has one terminal outcome. A Container Apps revision rollout is genuinely progressive and may need a real `Azure-AsyncOperation`. Do not assume the synchronous shortcut carries over. |
| The `Microsoft.ContainerInstance` provider claim | `AzureRoutingFilter.rejectDuplicateProviders` (`src/main/java/io/floci/az/core/AzureRoutingFilter.java:232`) fails startup on a duplicate claim. Container Apps claims `Microsoft.App` and must be its own `AzureServiceHandler`, with its own `enabled(String)` and its own config block — the same anti-#106 structure this design uses. |

### The one thing #62 should copy verbatim

The **anti-placeholder rule**: a route exists in the routing table only when it has a test that
asserts a concrete response body, and the config, banner, storage, and `enabled(String)` wiring
land in the same commit as the handler. That is what makes a handler reachable by construction
and is precisely what `ContainerAppService` lacked when PR #106 deleted it.

## Authoritative references

- [floci-io/floci-az#59](https://github.com/floci-io/floci-az/issues/59) — the requirement
  baseline for this work.
- [floci-io/floci-az#62](https://github.com/floci-io/floci-az/issues/62) — Azure Container Apps,
  the consumer this roadmap's forward-compatibility section is written for.
- floci-az commit `8af0ad5` (PR #106), *"chore: remove unreachable ContainerAppService, rename
  SasTokenParser to SasTokenVerifier"* — the failure mode the commit sequence is structured to
  prevent.
- `AGENTS.md`, sections *Service Implementation Pattern*, *Configuration Rules*,
  *Storage Rules*, *Keeping the Makefile and CI in sync*, and *Pull Request Guidelines*.
- Repository: `src/main/java/io/floci/az/config/EmulatorConfig.java`,
  `src/main/java/io/floci/az/core/AzureRoutingFilter.java`,
  `src/main/java/io/floci/az/core/BannerLogger.java`,
  `src/main/java/io/floci/az/core/storage/StorageFactory.java`,
  `src/main/java/io/floci/az/core/docker/`, `src/main/resources/application.yml`,
  `Makefile`, `compatibility-tests/`.
