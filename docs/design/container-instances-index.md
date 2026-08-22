# Container Instances (index)

**Status:** Proposed architecture

**Last updated:** 2026-08-22

The complete specification for Azure Container Instances
(`Microsoft.ContainerInstance/containerGroups`) in floci-az, covering
[floci-io/floci-az#59](https://github.com/floci-io/floci-az/issues/59).

The set is written to be self-contained: an implementer with these five documents and the
repository can build and verify the whole feature **with the network switched off**. Every
Azure fact is transcribed inline; the links to Microsoft Learn and the Azure REST API specs
exist as provenance for a reviewer auditing the work, never as homework for the implementer.

## Decision summary

| Decision | Value | Where it is argued |
|---|---|---|
| Integration lane | Filter lane: `AzureServiceHandler` + `ServiceRoutes.builder().provider("Microsoft.ContainerInstance")` | [Lane analysis](container-instances.md#lane-analysis-and-the-106-failure-mode) |
| Pinned API version | `2023-05-01`; any `api-version` accepted | [API version handling](container-instances.md#api-version-handling) |
| Container-group runtime | Docker pod: one infra container owning the network namespace, app containers joining with `container:<id>` | [Shared-namespace spike](container-instances-runtime.md#spike-shared-network-namespace) |
| Restart ownership | The emulator's reconciler, not Docker's restart policies | [Container state machine](container-instances-runtime.md#container-state-machine) |
| Log retrieval | New `ContainerLifecycleManager.fetchLogs`, bounded by byte and line caps | [Log spike](container-instances-runtime.md#spike-logcontainercmd) |
| Long-running operations | Fully synchronous; no `Azure-AsyncOperation` header is ever emitted | [LRO semantics](container-instances.md#long-running-operation-semantics) |
| Default mode | `mocked` defaults to `true` in the config interface, `false` in `application.yml` | [Mocked and real-Docker modes](container-instances.md#mocked-and-real-docker-modes) |

## Goals

- Give a single entry point into the five documents.
- Point every cross-cutting register — deviations, assumptions, open questions — at its home.

## Non-goals

- Restating any content. Everything below is a pointer.

## The documents

| Document | What it answers |
|---|---|
| [Container Instances](container-instances.md) | Why this lane, what the components are, the complete routing table, api-version handling, mocked versus real Docker, the storage model, configuration keys, `_admin/reset`, security boundaries, LRO semantics, the architecture invariants, the deviations register, the assumptions register, and the open-questions section. |
| [Resource model](container-instances-resource-model.md) | The wire contract: every field of every request and response body with type, requirement, read-only marker, default, validation rule and error code; naming regexes; the complete error catalog; complete copy-pasteable example bodies for every operation; and a transcribed-schema appendix covering every type in scope. |
| [Runtime and state](container-instances-runtime.md) | Azure concept to Docker mechanism with the exact method and `file:line` to call; naming and collision handling; image pull and registry credentials; the shared-namespace design with its spike evidence; host port allocation and release; IP and FQDN; volume backing; bounded log retrieval with its spike evidence; both state machines as transition tables; reconciliation; and every failure path with its log level and message. |
| [Test plan](container-instances-test-plan.md) | Every unit and integration test by name with its concrete payload and expected body, one validation case per catalog rule, plus the `sdk-test-java`, `compat-azcli`, and `compat-terraform` suites written out as real client code. |
| [Roadmap](container-instances-roadmap.md) | The seven-commit sequence with subjects, files, gating tests, and rollback stories; review boundaries; what is deferred and why; and the forward-compatibility contract with Azure Container Apps. |

## Cross-cutting registers

| Register | Location |
|---|---|
| Deviations from Azure (D1–D16) | [Container Instances § Deviations register](container-instances.md#deviations-register) |
| Assumptions (A1–A20) | [Container Instances § Assumptions register](container-instances.md#assumptions-register) |
| Assumption A21 (the 1 CPU / 1 GB group minimum) | [Resource model § Assumption A21](container-instances-resource-model.md#assumption-a21) |
| Open questions | [Container Instances § Open questions](container-instances.md#open-questions) — **empty by design** |
| Validation rules V1–V50 | [Resource model § Validation rules](container-instances-resource-model.md#validation-rules) |
| Error catalog | [Resource model § Error catalog](container-instances-resource-model.md#error-catalog) |
| Architecture invariants | [Container Instances § Architecture invariants](container-instances.md#architecture-invariants) |

## Reading order

1. [Container Instances](container-instances.md) — decide nothing; understand the shape.
2. [Resource model](container-instances-resource-model.md) — the contract to implement.
3. [Runtime and state](container-instances-runtime.md) — how the contract becomes containers.
4. [Test plan](container-instances-test-plan.md) — what proves it.
5. [Roadmap](container-instances-roadmap.md) — the order to build it in.

An implementer following the roadmap reads 1 and 2 once, then works commit by commit,
consulting 3 for commits 5 and 6 and 4 throughout.

## Authoritative references

Each document carries its own reference list. The primary sources across the set are:

- The `2023-05-01` Container Instances swagger and its examples in
  <https://github.com/Azure/azure-rest-api-specs>.
- The Azure Container Instances documentation on Microsoft Learn: container groups, restart
  policies, quotas, troubleshooting, `emptyDir` volumes, secret volumes, and the quickstart.
- The Azure Resource Manager naming-rules reference.
- `com.azure.resourcemanager:azure-resourcemanager-containerinstance:2.53.13`.
- `hashicorp/terraform-provider-azurerm` `v3.117.0`.
- Azure CLI with `azure-mgmt-containerinstance` `10.2.0b1`.
- The floci-az repository: `AGENTS.md`, `core/`, `core/docker/`, `core/dns/`, `services/vm/`,
  `src/main/resources/application.yml`, `Makefile`, `compatibility-tests/`.
- [floci-io/floci-az#59](https://github.com/floci-io/floci-az/issues/59) and
  [floci-io/floci-az#62](https://github.com/floci-io/floci-az/issues/62), and commit `8af0ad5`
  (PR #106).
