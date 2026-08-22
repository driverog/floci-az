# Container Instances — Resource Model

**Status:** Proposed architecture

**Last updated:** 2026-08-22

This document is the complete wire contract for
`Microsoft.ContainerInstance/containerGroups` in floci-az. It is self-contained: every enum
value, default, bound, regex, error message, and example body is stated inline. The
[schema appendix](#appendix-transcribed-schema) transcribes every type in scope so the Azure
swagger never needs to be opened again for this feature.

The architecture is in [Container Instances](container-instances.md). The Docker mapping is in
the [runtime design](container-instances-runtime.md).

## Decision summary

The pinned API version is **`2023-05-01`**, transcribed from
`specification/containerinstance/resource-manager/Microsoft.ContainerInstance/ContainerInstance/stable/2023-05-01/containerInstance.json`
in the `Azure/azure-rest-api-specs` repository (`"info": {"version": "2023-05-01"}`; 15 paths,
58 definitions).

The emulator serves the `2023-05-01` body shape for every request, whatever `api-version` the
client sends — see [API version handling](container-instances.md#api-version-handling) for the
cross-check against the Java SDK, the Azure CLI, and the `azurerm` provider.

Every request property falls into exactly one of three buckets, marked in the tables below:

- **In scope** — parsed, validated, and applied to the Docker runtime.
- **Accepted and echoed** — parsed, validated where a rule exists, stored, returned verbatim on
  `GET`, with no runtime effect.
- **Rejected** — produces a `400` from the [error catalog](#error-catalog).

## Goals

- Give the implementer every field, type, requirement, default, and bound without a second source.
- Give the implementer a complete, valid, copy-pasteable request and response body for every operation.
- Give the implementer an exhaustive error catalog in which every entry is reachable from a
  stated validation rule.

## Non-goals

- Describing operations outside the [routing table](container-instances.md#routing-table).
- Describing preview-only properties introduced after `2023-05-01`.

## Resource identity

```text
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}
  /providers/Microsoft.ContainerInstance/containerGroups/{containerGroupName}
```

| Response field | Value |
|---|---|
| `id` | `/subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}/providers/Microsoft.ContainerInstance/containerGroups/{containerGroupName}` — the `resourceGroups` segment is emitted with that exact casing regardless of the request's casing |
| `name` | `{containerGroupName}`, exactly as supplied in the path |
| `type` | `Microsoft.ContainerInstance/containerGroups` |

## Naming rules

All three regexes are anchored and applied to the raw string.

| Thing | Regex | Length | Case | Error on violation |
|---|---|---|---|---|
| Container group name (path segment `{containerGroupName}`) | `^[a-z0-9](?:[a-z0-9]\|-(?!-))*[a-z0-9]$\|^[a-z0-9]$` | 1–63 | lowercase only | `400` `InvalidResourceName` |
| Container name (`properties.containers[].name`, `properties.initContainers[].name`) | `^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$` | 1–63 | lowercase only | `400` `InvalidParameter` |
| DNS name label (`properties.ipAddress.dnsNameLabel`) | `^(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$` | 5–63 | case-insensitive | `400` `InvalidParameter` |
| Volume name (`properties.volumes[].name`) | `^[a-z0-9](?:[a-z0-9]\|-(?!-))*[a-z0-9]$` | 5–63 | lowercase only | `400` `InvalidParameter` |
| Environment variable name (`...environmentVariables[].name`) | `^[A-Za-z0-9](?:[A-Za-z0-9_]*[A-Za-z0-9])?$` | 1–63 | case-insensitive | `400` `InvalidParameter` |

The container-group rule — 1–63 characters, lowercase letters, numbers and hyphens, no leading
or trailing hyphen, no consecutive hyphens — is the Azure Resource Manager naming rule for
`Microsoft.ContainerInstance` / `containerGroups`. The other four are the Azure Container
Instances naming-conventions table: container name 1–63 lowercase alphanumeric plus hyphen
anywhere except first or last; DNS name label 5–63 case-insensitive alphanumeric plus hyphen
anywhere except first or last; environment variable 1–63 case-insensitive alphanumeric plus
underscore anywhere except first or last; volume name 5–63 lowercase alphanumeric plus hyphens
anywhere except first or last and no two consecutive hyphens.

A convenient Java form of the group-name rule, avoiding the alternation:

```java
private static final java.util.regex.Pattern GROUP_NAME =
        java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9]|-(?!-))*[a-z0-9]$|^[a-z0-9]$");
```

## Field reference — `containerGroups`

Columns: **JSON path** (relative to the resource root), **type**, **Req** (required in a `PUT`
body), **RO** (read-only — ignored on input, generated on output), **Default**, **Validation**,
**Error code**, **Scope** (`in` = applied to Docker, `echo` = accepted and echoed,
`rej` = rejected).

### Root

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `id` | string | no | **yes** | generated | input ignored | — | in |
| `name` | string | no | **yes** | from path | input ignored | — | in |
| `type` | string | no | **yes** | `Microsoft.ContainerInstance/containerGroups` | input ignored | — | in |
| `location` | string | no | no | `floci-az.services.container-instance.default-location` (`eastus`) | non-blank when present | `InvalidParameter` | in (region label in FQDN) |
| `tags` | object&lt;string,string&gt; | no | no | absent | every value must be a JSON string | `InvalidRequestContent` | in (echoed; also settable by `PATCH`) |
| `zones` | array&lt;string&gt; | no | no | absent | — | — | echo |
| `identity` | `ContainerGroupIdentity` | no | no | absent | see below | — | echo |
| `properties` | object | **yes** | no | — | must be a JSON object | `InvalidRequestContent` | in |

### `identity`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `identity.type` | string enum | no | no | absent | one of `SystemAssigned`, `UserAssigned`, `SystemAssigned, UserAssigned`, `None` (closed enum, exact strings including the comma-space in the third) | `InvalidParameter` | echo |
| `identity.principalId` | string | no | **yes** | generated when `type` contains `SystemAssigned`: a random UUID, stable for the group's lifetime | input ignored | — | echo |
| `identity.tenantId` | string | no | **yes** | generated when `type` contains `SystemAssigned`: `00000000-0000-0000-0000-000000000002` (floci-az's default tenant, `floci-az.services.entra.default-tenant-id`) | input ignored | — | echo |
| `identity.userAssignedIdentities` | object keyed by ARM resource id | no | no | absent | each value must be a JSON object | `InvalidRequestContent` | echo |
| `identity.userAssignedIdentities.{id}.principalId` | string | no | **yes** | generated: a random UUID, stable per key | input ignored | — | echo |
| `identity.userAssignedIdentities.{id}.clientId` | string | no | **yes** | generated: a random UUID, stable per key | input ignored | — | echo |

### `properties`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `properties.provisioningState` | string | no | **yes** | `Succeeded` or `Failed` | input ignored | — | in |
| `properties.containers` | array&lt;`Container`&gt; | **yes** | no | — | non-empty; at most 60 entries; names unique within the array | `InvalidRequestContent` (missing/not array/empty), `InvalidParameter` (>60, duplicate name) | in |
| `properties.osType` | string enum | **yes** | no | — | `Linux` or `Windows` | `InvalidRequestContent` (missing), `InvalidParameter` (other value) | echo (`Windows` runs as Linux — deviation D11) |
| `properties.restartPolicy` | string enum | no | no | `Always` | `Always`, `OnFailure`, or `Never` | `InvalidParameter` | in |
| `properties.imageRegistryCredentials` | array&lt;`ImageRegistryCredential`&gt; | no | no | `[]` | see below | — | in |
| `properties.ipAddress` | `IpAddress` | no | no | absent | see below | — | in |
| `properties.volumes` | array&lt;`Volume`&gt; | no | no | `[]` | at most 20 entries; names unique | `InvalidParameter` | in |
| `properties.instanceView` | object | no | **yes** | generated | input ignored | — | in |
| `properties.initContainers` | array&lt;`InitContainerDefinition`&gt; | no | no | `[]` | names unique across `containers` and `initContainers` combined; counted toward the 60-container limit | `InvalidParameter` | in |
| `properties.diagnostics` | `ContainerGroupDiagnostics` | no | no | absent | when present, `logAnalytics.workspaceId` and `logAnalytics.workspaceKey` are both required | `InvalidRequestContent` | echo |
| `properties.subnetIds` | array&lt;`ContainerGroupSubnetId`&gt; | no | no | absent | each entry requires `id` | `InvalidRequestContent` | echo |
| `properties.dnsConfig` | `DnsConfiguration` | no | no | absent | `nameServers` required and non-empty when `dnsConfig` is present | `InvalidRequestContent` | echo |
| `properties.sku` | string enum | no | no | `Standard` | `Standard`, `Dedicated`, or `Confidential` | `InvalidParameter` | echo |
| `properties.encryptionProperties` | `EncryptionProperties` | no | no | absent | when present, `vaultBaseUrl`, `keyName`, `keyVersion` all required | `InvalidRequestContent` | echo |
| `properties.extensions` | array&lt;`DeploymentExtensionSpec`&gt; | no | no | absent | each entry requires `name` | `InvalidRequestContent` | echo |
| `properties.confidentialComputeProperties` | object | no | no | absent | — | — | echo |
| `properties.priority` | string enum | no | no | absent | `Regular` or `Spot` | `InvalidParameter` | echo |

### `properties.containers[]` and `properties.initContainers[]`

`Container` and `InitContainerDefinition` share the same outer shape:
`{"name": "...", "properties": {...}}`. `InitContainerPropertiesDefinition` is a strict subset
of `ContainerProperties`: it has no `ports`, no `resources`, no `livenessProbe`, and no
`readinessProbe`, and its `image` is optional.

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `containers[].name` | string | **yes** | no | — | container-name regex, 1–63, unique in the group | `InvalidRequestContent` (missing), `InvalidParameter` (regex/length/duplicate) | in |
| `containers[].properties` | object | **yes** | no | — | must be a JSON object | `InvalidRequestContent` | in |
| `containers[].properties.image` | string | **yes** | no | — | non-blank | `InvalidRequestContent` | in |
| `containers[].properties.command` | array&lt;string&gt; | no | no | `[]` | every element a JSON string | `InvalidRequestContent` | in (Docker `Cmd`) |
| `containers[].properties.ports` | array&lt;`ContainerPort`&gt; | no | no | `[]` | see below | — | in |
| `containers[].properties.environmentVariables` | array&lt;`EnvironmentVariable`&gt; | no | no | `[]` | see below | — | in |
| `containers[].properties.resources` | `ResourceRequirements` | **yes** | no | — | see below | `InvalidRequestContent` | in (memory only) |
| `containers[].properties.volumeMounts` | array&lt;`VolumeMount`&gt; | no | no | `[]` | see below | — | in |
| `containers[].properties.instanceView` | object | no | **yes** | generated | input ignored | — | in |
| `containers[].properties.livenessProbe` | `ContainerProbe` | no | no | absent | see below | — | echo |
| `containers[].properties.readinessProbe` | `ContainerProbe` | no | no | absent | see below | — | echo |
| `containers[].properties.securityContext` | `SecurityContextDefinition` | no | no | absent | `runAsUser` and `runAsGroup` must be ≥ 0 when present | `InvalidParameter` | in (`runAsUser`/`runAsGroup` → Docker `User`; `privileged` → Docker `Privileged`; `capabilities` and `seccompProfile` echoed only) |
| `initContainers[].properties.image` | string | no | no | absent | non-blank when present; an init container with no `image` is skipped at runtime | `InvalidParameter` | in |

### `containers[].properties.ports[]` — `ContainerPort`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `ports[].port` | integer (int32) | **yes** | no | — | 1 ≤ port ≤ 65535 | `InvalidRequestContent` (missing), `InvalidParameter` (out of range) | in (Docker `ExposedPorts`) |
| `ports[].protocol` | string enum | no | no | `TCP` | `TCP` or `UDP` (case-sensitive as written) | `InvalidParameter` | echo (Docker exposes TCP only) |

### `containers[].properties.environmentVariables[]` — `EnvironmentVariable`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `environmentVariables[].name` | string | **yes** | no | — | env-var regex, 1–63 | `InvalidRequestContent` (missing), `InvalidParameter` (regex/length) | in |
| `environmentVariables[].value` | string | no | no | `""` | mutually exclusive with `secureValue` | `InvalidParameter` | in |
| `environmentVariables[].secureValue` | string | no | no | absent | mutually exclusive with `value`. **Secret** — never persisted, never returned | `InvalidParameter` | in (write-only) |

Both `value` and `secureValue` present on one entry is an error; neither present is treated as
`value: ""`.

### `containers[].properties.resources` — `ResourceRequirements`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `resources.requests` | `ResourceRequests` | **yes** | no | — | must be a JSON object | `InvalidRequestContent` | in |
| `resources.requests.cpu` | number (double) | **yes** | no | — | > 0; sum across all containers ≤ 31 | `InvalidRequestContent` (missing), `InvalidParameter` (≤ 0 or group sum > 31) | echo (deviation D7) |
| `resources.requests.memoryInGB` | number (double) | **yes** | no | — | > 0; sum across all containers ≤ 240 | `InvalidRequestContent` (missing), `InvalidParameter` (≤ 0 or group sum > 240) | in (Docker memory limit) |
| `resources.requests.gpu` | `GpuResource` | no | no | absent | see below | — | echo |
| `resources.limits` | `ResourceLimits` | no | no | absent | must be a JSON object | `InvalidRequestContent` | echo |
| `resources.limits.cpu` | number (double) | no | no | absent | > 0 and ≥ `requests.cpu` | `InvalidParameter` | echo |
| `resources.limits.memoryInGB` | number (double) | no | no | absent | > 0 and ≥ `requests.memoryInGB` | `InvalidParameter` | echo |
| `resources.limits.gpu` | `GpuResource` | no | no | absent | see below | — | echo |
| `...gpu.count` | integer (int32) | **yes** within `gpu` | no | — | ≥ 1 | `InvalidRequestContent` (missing), `InvalidParameter` (< 1) | echo |
| `...gpu.sku` | string enum | **yes** within `gpu` | no | — | `K80`, `P100`, or `V100` | `InvalidRequestContent` (missing), `InvalidParameter` (other) | echo |

The minimum-allocation rule from the ACI documentation — *"Allocate a minimum of 1 CPU and 1 GB
of memory to a container group"* — is **not** enforced. A group whose CPU sum is below 1 is
accepted. Rationale: enforcing it would reject valid local test payloads for no local benefit,
and Azure itself permits individual containers below 1 CPU / 1 GB. This is
[assumption A21](#assumption-a21).

### `containers[].properties.volumeMounts[]` — `VolumeMount`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `volumeMounts[].name` | string | **yes** | no | — | must match a `properties.volumes[].name` | `InvalidRequestContent` (missing), `InvalidParameter` (no matching volume) | in |
| `volumeMounts[].mountPath` | string | **yes** | no | — | must start with `/`; must not contain `:` | `InvalidRequestContent` (missing), `InvalidParameter` (relative or contains colon) | in |
| `volumeMounts[].readOnly` | boolean | no | no | `false` | — | — | in (forced `true` for `secret` volumes) |

### `containers[].properties.livenessProbe` / `readinessProbe` — `ContainerProbe`

Accepted and echoed. Validated only for internal consistency.

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `...Probe.exec.command` | array&lt;string&gt; | no | no | absent | every element a JSON string | `InvalidRequestContent` | echo |
| `...Probe.httpGet.path` | string | no | no | absent | — | — | echo |
| `...Probe.httpGet.port` | integer (int32) | **yes** within `httpGet` | no | — | 1 ≤ port ≤ 65535 | `InvalidRequestContent` (missing), `InvalidParameter` (range) | echo |
| `...Probe.httpGet.scheme` | string enum | no | no | absent | `http` or `https` (lowercase) | `InvalidParameter` | echo |
| `...Probe.httpGet.httpHeaders[].name` | string | no | no | absent | — | — | echo |
| `...Probe.httpGet.httpHeaders[].value` | string | no | no | absent | — | — | echo |
| `...Probe.initialDelaySeconds` | integer (int32) | no | no | absent | ≥ 0 | `InvalidParameter` | echo |
| `...Probe.periodSeconds` | integer (int32) | no | no | absent | ≥ 1 | `InvalidParameter` | echo |
| `...Probe.failureThreshold` | integer (int32) | no | no | absent | ≥ 1 | `InvalidParameter` | echo |
| `...Probe.successThreshold` | integer (int32) | no | no | absent | ≥ 1 | `InvalidParameter` | echo |
| `...Probe.timeoutSeconds` | integer (int32) | no | no | absent | ≥ 1 | `InvalidParameter` | echo |

### `properties.imageRegistryCredentials[]` — `ImageRegistryCredential`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `imageRegistryCredentials[].server` | string | **yes** | no | — | non-blank; must not contain `://` | `InvalidRequestContent` (missing), `InvalidParameter` (contains a scheme) | in |
| `imageRegistryCredentials[].username` | string | no | no | absent | — | — | in |
| `imageRegistryCredentials[].password` | string | no | no | absent | **Secret** — never persisted, never returned | — | in (write-only) |
| `imageRegistryCredentials[].identity` | string | no | no | absent | — | — | echo |
| `imageRegistryCredentials[].identityUrl` | string | no | no | absent | — | — | echo |

The response echoes `server`, `username`, `identity`, and `identityUrl`, and omits `password` —
matching the `ContainerGroupsGet_Succeeded` example, whose credential entry has only `server`
and `username`.

### `properties.ipAddress` — `IpAddress`

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `ipAddress.type` | string enum | **yes** within `ipAddress` | no | — | `Public` or `Private` | `InvalidRequestContent` (missing), `InvalidParameter` (other) | in |
| `ipAddress.ports` | array&lt;`Port`&gt; | **yes** within `ipAddress` | no | — | non-empty; at most 5 entries; port numbers unique | `InvalidRequestContent` (missing/not array/empty), `InvalidParameter` (>5, duplicate) | in |
| `ipAddress.ports[].port` | integer (int32) | **yes** | no | — | 1 ≤ port ≤ 65535 | `InvalidRequestContent` (missing), `InvalidParameter` (range) | in |
| `ipAddress.ports[].protocol` | string enum | no | no | `TCP` | `TCP` or `UDP` | `InvalidParameter` | echo (only TCP is published) |
| `ipAddress.ip` | string | no | **yes** | generated | input ignored | — | in |
| `ipAddress.dnsNameLabel` | string | no | no | absent | DNS-label regex, 5–63 | `InvalidParameter` | in (FQDN only) |
| `ipAddress.autoGeneratedDomainNameLabelScope` | string enum | no | no | `Unsecure` | `Unsecure`, `TenantReuse`, `SubscriptionReuse`, `ResourceGroupReuse`, or `Noreuse` | `InvalidParameter` | echo |
| `ipAddress.fqdn` | string | no | **yes** | generated when `dnsNameLabel` is present: `{dnsNameLabel}.{normalizedLocation}.azurecontainer.io` | input ignored | — | echo (deviation D3) |

`normalizedLocation` is `location.toLowerCase(Locale.ROOT).replace(" ", "")`, so a request with
`"location": "West US"` yields `...westus.azurecontainer.io`.

`ipAddress.ip` is generated per
[assumption A8](container-instances.md#assumptions-register): `127.0.0.1` when floci-az runs on
the host, and the infra container's Docker-network IP when floci-az runs inside Docker
(`ContainerDetector.isRunningInContainer()`,
`src/main/java/io/floci/az/core/docker/ContainerDetector.java`).

`ipAddress.ports[]` are the group-level published ports and are echoed **unchanged**. The port
actually bound on the host is reported through the `PortMapped` event — see deviation D4.

Every `ipAddress.ports[].port` must also appear as a `containers[].properties.ports[].port` on
at least one container in the group. Violating this is `InvalidParameter` with target
`properties.ipAddress.ports[{i}].port`.

### `properties.volumes[]` — `Volume`

Exactly one of `azureFile`, `emptyDir`, `secret`, `gitRepo` must be present.

| JSON path | Type | Req | RO | Default | Validation | Error code | Scope |
|---|---|---|---|---|---|---|---|
| `volumes[].name` | string | **yes** | no | — | volume-name regex, 5–63, unique in the array | `InvalidRequestContent` (missing), `InvalidParameter` (regex/length/duplicate) | in |
| `volumes[].emptyDir` | object | no | no | absent | must be an object; contents ignored | `InvalidRequestContent` | in |
| `volumes[].secret` | object&lt;string,string&gt; | no | no | absent | every value must be a valid Base64 string; every key must match `^[A-Za-z0-9._-]{1,255}$`. **Secret** — never persisted, returned as `{}` | `InvalidParameter` | in (write-only) |
| `volumes[].azureFile.shareName` | string | **yes** within `azureFile` | no | — | non-blank | `InvalidRequestContent` | in |
| `volumes[].azureFile.storageAccountName` | string | **yes** within `azureFile` | no | — | non-blank | `InvalidRequestContent` | in |
| `volumes[].azureFile.storageAccountKey` | string | no | no | absent | **Secret** — never persisted, never returned | — | echo (write-only) |
| `volumes[].azureFile.readOnly` | boolean | no | no | `false` | — | — | in |
| `volumes[].gitRepo` | object | no | no | absent | **rejected** | `NotSupported` (400) | rej |

Zero or more than one volume kind on a single entry is `InvalidParameter` with target
`properties.volumes[{i}]`.

### `properties.instanceView` (read-only)

| JSON path | Type | Notes |
|---|---|---|
| `properties.instanceView.state` | string | One of `Pending`, `Running`, `Succeeded`, `Stopped`, `Failed` — see the [group state machine](container-instances-runtime.md#container-group-state-machine) |
| `properties.instanceView.events` | array&lt;`Event`&gt; | Group-level events |

<a id="instanceview-and-expand"></a>

Present in **every** `GET`, `PUT` and `PATCH` response for a single container group. Never
present in a list response — matching the swagger's `ListResultContainerGroup` type, which the
`ContainerGroupsList` example renders without `instanceView`.

> **Correction (found during implementation).** An earlier revision of this document gated
> `instanceView` on a `$expand=instanceView` query parameter, by analogy with
> `Microsoft.Compute`. That is wrong for Container Instances and was corrected in the
> implementation commit. Three pieces of evidence, all already inside this document set:
>
> - `ContainerGroups_Get` in `2023-05-01` declares **no** `$expand` parameter — the
>   [global-parameters table](#global-parameters), transcribed from the swagger, lists only
>   `subscriptionId`, `resourceGroupName`, `containerGroupName`, `containerName`, `location`,
>   `api-version`, `tail` and `timestamps`.
> - The swagger's own `ContainerGroupsGet_Succeeded.json` example, which this document models
>   its Get response on, carries `instanceView`.
> - The [test plan](container-instances-test-plan.md)'s own compatibility suites read it from a
>   plain Get: the Java SDK's `ContainerGroup.state()` after `getByResourceGroup`, and
>   `az container show --query instanceView.state`. Under the gating rule both return `null`.
>
> `$expand=instanceView` is still accepted and is a no-op, so a client that sends it — as the
> [routing](container-instances-test-plan.md#routing) and [CRUD](container-instances-test-plan.md#crud)
> cases do — receives the same body.

### `properties.containers[].properties.instanceView` (read-only)

| JSON path | Type | Notes |
|---|---|---|
| `...instanceView.restartCount` | integer (int32) | Number of restarts the reconciler has performed |
| `...instanceView.currentState` | `ContainerState` | See below |
| `...instanceView.previousState` | `ContainerState` | Absent until the first restart |
| `...instanceView.events` | array&lt;`Event`&gt; | Per-container events |

### `ContainerState` (read-only)

| JSON path | Type | Notes |
|---|---|---|
| `state` | string | `Waiting`, `Running`, or `Terminated` |
| `startTime` | string (RFC 3339, `Z`, second precision) | When the current state began |
| `exitCode` | integer (int32) | Present only when `state` is `Terminated` |
| `finishTime` | string (RFC 3339, `Z`) | Present only when `state` is `Terminated` |
| `detailStatus` | string | `""` while `Waiting`/`Running`; `Completed` on exit `0`; `Error` on any nonzero exit |

### `Event` (read-only)

| JSON path | Type | Notes |
|---|---|---|
| `count` | integer (int32) | Number of occurrences; incremented in place for repeats |
| `firstTimestamp` | string (RFC 3339, `Z`) | First occurrence |
| `lastTimestamp` | string (RFC 3339, `Z`) | Most recent occurrence |
| `name` | string | `Pulling`, `Pulled`, `Failed`, `BackOff`, `Started`, `Killing`, `PortMapped`, `AzureFileEmulated`, `DockerUnavailable`, `InfraRestarted`, `SecretsUnavailableAfterRestart` |
| `message` | string | See [runtime events](container-instances-runtime.md#events) for the exact template of each |
| `type` | string | `Normal` or `Warning` |

## Validation rules

The complete list, in the order `ContainerGroupValidator` applies them. Every rule maps to
exactly one row of the [error catalog](#error-catalog); every catalog row is reachable from at
least one rule here.

| # | Rule | Error |
|---|---|---|
| V1 | The path segment `{containerGroupName}` matches the group-name regex and is 1–63 characters | `InvalidResourceName` |
| V2 | The request body parses as a JSON object | `InvalidRequestContent`, target `` (empty) |
| V3 | `properties` is present and is a JSON object | `InvalidRequestContent`, target `properties` |
| V4 | `properties.osType` is present | `InvalidRequestContent`, target `properties.osType` |
| V5 | `properties.osType` is `Linux` or `Windows` | `InvalidParameter`, target `properties.osType` |
| V6 | `properties.containers` is present, is an array, and is non-empty | `InvalidRequestContent`, target `properties.containers` |
| V7 | `properties.containers` plus `properties.initContainers` has at most 60 entries | `InvalidParameter`, target `properties.containers` |
| V8 | Every `containers[i].name` and `initContainers[i].name` is present | `InvalidRequestContent`, target `properties.containers[{i}].name` |
| V9 | Every container name matches the container-name regex and is 1–63 characters | `InvalidParameter`, target `properties.containers[{i}].name` |
| V10 | Container names are unique across `containers` and `initContainers` combined | `InvalidParameter`, target `properties.containers[{i}].name` |
| V11 | Every `containers[i].properties` is present and is a JSON object | `InvalidRequestContent`, target `properties.containers[{i}].properties` |
| V12 | Every `containers[i].properties.image` is present and non-blank | `InvalidRequestContent`, target `properties.containers[{i}].properties.image` |
| V13 | Every `containers[i].properties.resources.requests` is present with both `cpu` and `memoryInGB` | `InvalidRequestContent`, target `properties.containers[{i}].properties.resources.requests` |
| V14 | Every `requests.cpu` and `requests.memoryInGB` is greater than 0 | `InvalidParameter`, target `properties.containers[{i}].properties.resources.requests.cpu` (or `.memoryInGB`) |
| V15 | The sum of `requests.cpu` across all containers is at most 31 | `InvalidParameter`, target `properties.containers` |
| V16 | The sum of `requests.memoryInGB` across all containers is at most 240 | `InvalidParameter`, target `properties.containers` |
| V17 | Every `limits.cpu` / `limits.memoryInGB`, when present, is at least the matching request | `InvalidParameter`, target `properties.containers[{i}].properties.resources.limits.cpu` (or `.memoryInGB`) |
| V18 | Every `gpu.sku`, when a `gpu` object is present, is `K80`, `P100`, or `V100`, and `gpu.count` is present and at least 1 | `InvalidRequestContent` when `count`/`sku` missing, otherwise `InvalidParameter`, target `properties.containers[{i}].properties.resources.requests.gpu.sku` |
| V19 | Every `containers[i].properties.ports[j].port` is present and between 1 and 65535 | `InvalidRequestContent` when missing, otherwise `InvalidParameter`, target `properties.containers[{i}].properties.ports[{j}].port` |
| V20 | Every `ports[j].protocol`, when present, is `TCP` or `UDP` | `InvalidParameter`, target `properties.containers[{i}].properties.ports[{j}].protocol` |
| V21 | Every `environmentVariables[j].name` is present and matches the env-var regex, 1–63 characters | `InvalidRequestContent` when missing, otherwise `InvalidParameter`, target `properties.containers[{i}].properties.environmentVariables[{j}].name` |
| V22 | No `environmentVariables[j]` has both `value` and `secureValue` | `InvalidParameter`, target `properties.containers[{i}].properties.environmentVariables[{j}]` |
| V23 | Every `volumeMounts[j].name` and `mountPath` is present | `InvalidRequestContent`, target `properties.containers[{i}].properties.volumeMounts[{j}].name` (or `.mountPath`) |
| V24 | Every `volumeMounts[j].mountPath` starts with `/` and contains no `:` | `InvalidParameter`, target `properties.containers[{i}].properties.volumeMounts[{j}].mountPath` |
| V25 | Every `volumeMounts[j].name` matches a `properties.volumes[].name` | `InvalidParameter`, target `properties.containers[{i}].properties.volumeMounts[{j}].name` |
| V26 | `properties.volumes` has at most 20 entries | `InvalidParameter`, target `properties.volumes` |
| V27 | Every `volumes[i].name` is present, matches the volume-name regex, is 5–63 characters, and is unique | `InvalidRequestContent` when missing, otherwise `InvalidParameter`, target `properties.volumes[{i}].name` |
| V28 | Every `volumes[i]` has exactly one of `azureFile`, `emptyDir`, `secret`, `gitRepo` | `InvalidParameter`, target `properties.volumes[{i}]` |
| V29 | No `volumes[i]` has `gitRepo` | `NotSupported`, target `properties.volumes[{i}].gitRepo` |
| V30 | Every `volumes[i].secret` value is valid Base64 and every key matches `^[A-Za-z0-9._-]{1,255}$` | `InvalidParameter`, target `properties.volumes[{i}].secret` |
| V31 | Every `volumes[i].azureFile` has `shareName` and `storageAccountName` | `InvalidRequestContent`, target `properties.volumes[{i}].azureFile.shareName` (or `.storageAccountName`) |
| V32 | `properties.restartPolicy`, when present, is `Always`, `OnFailure`, or `Never` | `InvalidParameter`, target `properties.restartPolicy` |
| V33 | `properties.sku`, when present, is `Standard`, `Dedicated`, or `Confidential` | `InvalidParameter`, target `properties.sku` |
| V34 | `properties.priority`, when present, is `Regular` or `Spot` | `InvalidParameter`, target `properties.priority` |
| V35 | `properties.ipAddress.type`, when `ipAddress` is present, is present and is `Public` or `Private` | `InvalidRequestContent` when missing, otherwise `InvalidParameter`, target `properties.ipAddress.type` |
| V36 | `properties.ipAddress.ports`, when `ipAddress` is present, is present, is an array, and is non-empty | `InvalidRequestContent`, target `properties.ipAddress.ports` |
| V37 | `properties.ipAddress.ports` has at most 5 entries and no duplicate port numbers | `InvalidParameter`, target `properties.ipAddress.ports` |
| V38 | Every `ipAddress.ports[i].port` is present and between 1 and 65535 | `InvalidRequestContent` when missing, otherwise `InvalidParameter`, target `properties.ipAddress.ports[{i}].port` |
| V39 | Every `ipAddress.ports[i].port` appears as a `containers[].properties.ports[].port` on some container | `InvalidParameter`, target `properties.ipAddress.ports[{i}].port` |
| V40 | `properties.ipAddress.dnsNameLabel`, when present, matches the DNS-label regex and is 5–63 characters | `InvalidParameter`, target `properties.ipAddress.dnsNameLabel` |
| V41 | `properties.ipAddress.autoGeneratedDomainNameLabelScope`, when present, is one of the five enum values | `InvalidParameter`, target `properties.ipAddress.autoGeneratedDomainNameLabelScope` |
| V42 | `properties.dnsConfig.nameServers`, when `dnsConfig` is present, is present and non-empty | `InvalidRequestContent`, target `properties.dnsConfig.nameServers` |
| V43 | `properties.diagnostics.logAnalytics`, when `diagnostics` is present, has `workspaceId` and `workspaceKey` | `InvalidRequestContent`, target `properties.diagnostics.logAnalytics.workspaceId` (or `.workspaceKey`) |
| V44 | `properties.encryptionProperties`, when present, has `vaultBaseUrl`, `keyName`, and `keyVersion` | `InvalidRequestContent`, target `properties.encryptionProperties.vaultBaseUrl` (or `.keyName`, `.keyVersion`) |
| V45 | `properties.subnetIds[i].id`, when `subnetIds` is present, is present | `InvalidRequestContent`, target `properties.subnetIds[{i}].id` |
| V46 | `identity.type`, when present, is one of the four enum values | `InvalidParameter`, target `identity.type` |
| V47 | `properties.extensions[i].name`, when `extensions` is present, is present | `InvalidRequestContent`, target `properties.extensions[{i}].name` |
| V48 | `properties.imageRegistryCredentials[i].server` is present, non-blank, and contains no `://` | `InvalidRequestContent` when missing, otherwise `InvalidParameter`, target `properties.imageRegistryCredentials[{i}].server` |
| V49 | `properties.containers[i].properties.securityContext.runAsUser` / `runAsGroup`, when present, are at least 0 | `InvalidParameter`, target `properties.containers[{i}].properties.securityContext.runAsUser` |
| V50 | Every probe's `httpGet.port`, when `httpGet` is present, is present and between 1 and 65535; `httpGet.scheme`, when present, is `http` or `https`; every present `initialDelaySeconds` is ≥ 0 and every present `periodSeconds`, `failureThreshold`, `successThreshold`, `timeoutSeconds` is ≥ 1 | `InvalidRequestContent` when `httpGet.port` missing, otherwise `InvalidParameter`, target `properties.containers[{i}].properties.livenessProbe.httpGet.port` (path adjusted for the actual probe and field) |

Validation is **fail-fast**: the first rule that fails produces the response, and the rules run
in the numeric order above. This is deterministic, so a test can assert exactly one error for a
body that violates several rules.

## Error catalog

Every error response body is
`{"error":{"code":"{code}","message":"{message}","target":"{target}"}}`, produced through
`ArmErrors.error(status, code, message)`
(`src/main/java/io/floci/az/core/arm/ArmErrors.java:18`) extended to carry `target`.
`target` is omitted from the JSON when it is empty.

`{group}` is the container group name from the path, `{rg}` the resource group,
`{sub}` the subscription id, `{container}` the container name from the path,
`{tail}` the unmatched path tail, `{path}` the JSON path of the offending property,
`{value}` the offending value, `{method}` the HTTP method.

| Condition (rule) | HTTP | `code` | `target` | Message template |
|---|---|---|---|---|
| Group name fails the naming rule (V1) | 400 | `InvalidResourceName` | `containerGroupName` | `The Resource Name '{group}' is invalid. Container group names must be 1 to 63 characters long, contain only lowercase letters, numbers and hyphens, must not start or end with a hyphen, and must not contain consecutive hyphens.` |
| Body is not a JSON object (V2) | 400 | `InvalidRequestContent` | *(omitted)* | `The request content was invalid and could not be deserialized: the request body must be a JSON object.` |
| A required property is missing or has the wrong JSON type (V3, V4, V6, V8, V11, V12, V13, V18, V19, V21, V23, V27, V31, V35, V36, V38, V42, V43, V44, V45, V47, V48, V50) | 400 | `InvalidRequestContent` | `{path}` | `The request content was invalid and could not be deserialized: required property '{path}' is missing or of the wrong type.` |
| A present property has an unacceptable value (V5, V7, V9, V10, V14, V15, V16, V17, V20, V22, V24, V25, V26, V27, V28, V30, V32, V33, V34, V37, V39, V40, V41, V46, V48, V49, V50) | 400 | `InvalidParameter` | `{path}` | `The value '{value}' provided for '{path}' is not valid. {reason}` |
| A `gitRepo` volume is requested (V29) | 400 | `NotSupported` | `{path}` | `Volume type 'gitRepo' is not supported by floci-az. Use 'emptyDir', 'secret' or 'azureFile' instead.` |
| An image is declared for `osType: Windows` and the emulator cannot run it | 400 | `OsVersionNotSupported` | `properties.containers[{i}].properties.image` | `The OS version of image '{value}' is not supported.` |
| `GET`, `PATCH`, `DELETE`, or an action on a container group that does not exist | 404 | `ResourceNotFound` | *(omitted)* | `The Resource 'Microsoft.ContainerInstance/containerGroups/{group}' under resource group '{rg}' was not found.` |
| `GET .../containers/{container}/logs` where the group exists but the container name does not | 404 | `ResourceNotFound` | `containerName` | `The container '{container}' was not found in container group '{group}' under resource group '{rg}'.` |
| A path under `/providers/Microsoft.ContainerInstance/` that no route matches | 404 | `ResourceNotFound` | *(omitted)* | `Unsupported Microsoft.ContainerInstance path: {tail}` |
| A matched route with an unsupported HTTP method | 405 | `MethodNotAllowed` | *(omitted)* | `Method not allowed` |
| `POST .../containers/{container}/exec` | 501 | `NotImplemented` | *(omitted)* | `Container exec is not implemented by floci-az: the emulator serves no websocket data plane.` |
| `POST .../containers/{container}/attach` | 501 | `NotImplemented` | *(omitted)* | `Container attach is not implemented by floci-az: the emulator serves no websocket data plane.` |

`{reason}` for `InvalidParameter` is a single sentence chosen by rule. The complete set:

| Rule | `{reason}` |
|---|---|
| V5 | `Valid values are 'Linux' and 'Windows'.` |
| V7 | `A container group may contain at most 60 containers.` |
| V9 | `Container names must be 1 to 63 characters long and contain only lowercase letters, numbers and hyphens, with a hyphen allowed anywhere except the first or last character.` |
| V10 | `Container names must be unique within a container group.` |
| V14 | `The value must be greater than 0.` |
| V15 | `The total CPU requested by a container group may not exceed 31.` |
| V16 | `The total memory requested by a container group may not exceed 240 GB.` |
| V17 | `A resource limit must be greater than or equal to the corresponding resource request.` |
| V20 | `Valid values are 'TCP' and 'UDP'.` |
| V22 | `An environment variable may set either 'value' or 'secureValue', not both.` |
| V24 | `A mount path must be absolute and must not contain a colon.` |
| V25 | `No volume with that name is declared in properties.volumes.` |
| V26 | `A container group may contain at most 20 volumes.` |
| V27 | `Volume names must be 5 to 63 characters long, contain only lowercase letters, numbers and hyphens, must not start or end with a hyphen, must not contain consecutive hyphens, and must be unique within a container group.` |
| V28 | `Exactly one of 'azureFile', 'emptyDir', 'secret' or 'gitRepo' must be specified.` |
| V30 | `Secret volume values must be Base64-encoded and secret keys may contain only letters, numbers, dot, underscore and hyphen.` |
| V32 | `Valid values are 'Always', 'OnFailure' and 'Never'.` |
| V33 | `Valid values are 'Standard', 'Dedicated' and 'Confidential'.` |
| V34 | `Valid values are 'Regular' and 'Spot'.` |
| V37 | `A container group may expose at most 5 ports, and each port number may appear only once.` |
| V39 | `The port is not exposed by any container in the container group.` |
| V40 | `A DNS name label must be 5 to 63 characters long and contain only letters, numbers and hyphens, with a hyphen allowed anywhere except the first or last character.` |
| V41 | `Valid values are 'Unsecure', 'TenantReuse', 'SubscriptionReuse', 'ResourceGroupReuse' and 'Noreuse'.` |
| V46 | `Valid values are 'SystemAssigned', 'UserAssigned', 'SystemAssigned, UserAssigned' and 'None'.` |
| V48 | `A registry server must be a host name without a scheme.` |
| V49 | `The value must be greater than or equal to 0.` |
| V50 (port) | `A port number must be between 1 and 65535.` |
| V50 (scheme) | `Valid values are 'http' and 'https'.` |
| V50 (thresholds) | `The value must be greater than or equal to 1.` |
| V19 (port range) | `A port number must be between 1 and 65535.` |
| V21 | `Environment variable names must be 1 to 63 characters long and contain only letters, numbers and underscores, with an underscore allowed anywhere except the first or last character.` |

## Example bodies

Every body below is complete and valid JSON. Nothing is elided.

The shared fixture: subscription `00000000-0000-0000-0000-000000000001`, resource group
`aci-rg`, container group `demo-group`, two containers `web` and `sidecar`, one `emptyDir`
volume, one `secret` volume, one exposed port.

### Create — request

`PUT /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group?api-version=2023-05-01`

```json
{
  "location": "eastus",
  "tags": {
    "env": "dev",
    "owner": "floci"
  },
  "identity": {
    "type": "SystemAssigned"
  },
  "properties": {
    "osType": "Linux",
    "restartPolicy": "Always",
    "sku": "Standard",
    "containers": [
      {
        "name": "web",
        "properties": {
          "image": "mcr.microsoft.com/azuredocs/aci-helloworld:latest",
          "command": [],
          "ports": [
            {
              "port": 80,
              "protocol": "TCP"
            }
          ],
          "environmentVariables": [
            {
              "name": "GREETING",
              "value": "hello"
            },
            {
              "name": "API_TOKEN",
              "secureValue": "s3cr3t-token"
            }
          ],
          "resources": {
            "requests": {
              "cpu": 1.0,
              "memoryInGB": 1.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            },
            {
              "name": "secret-volume",
              "mountPath": "/mnt/secrets",
              "readOnly": true
            }
          ]
        }
      },
      {
        "name": "sidecar",
        "properties": {
          "image": "alpine:3.20",
          "command": [
            "sh",
            "-c",
            "while true; do wget -q -O- http://localhost:80 >/dev/null && echo probe-ok; sleep 10; done"
          ],
          "ports": [],
          "environmentVariables": [],
          "resources": {
            "requests": {
              "cpu": 0.5,
              "memoryInGB": 0.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            }
          ]
        }
      }
    ],
    "imageRegistryCredentials": [],
    "ipAddress": {
      "type": "Public",
      "dnsNameLabel": "floci-demo-group",
      "ports": [
        {
          "port": 80,
          "protocol": "TCP"
        }
      ]
    },
    "volumes": [
      {
        "name": "scratch-volume",
        "emptyDir": {}
      },
      {
        "name": "secret-volume",
        "secret": {
          "mysecret1": "TXkgZmlyc3Qgc2VjcmV0IEZPTwo=",
          "mysecret2": "TXkgc2Vjb25kIHNlY3JldCBCQVIK"
        }
      }
    ]
  }
}
```

### Create — response

`201 Created`, `Content-Type: application/json`

```json
{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group",
  "name": "demo-group",
  "type": "Microsoft.ContainerInstance/containerGroups",
  "location": "eastus",
  "tags": {
    "env": "dev",
    "owner": "floci"
  },
  "identity": {
    "type": "SystemAssigned",
    "principalId": "6b1f0f1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
    "tenantId": "00000000-0000-0000-0000-000000000002"
  },
  "properties": {
    "provisioningState": "Succeeded",
    "osType": "Linux",
    "restartPolicy": "Always",
    "sku": "Standard",
    "containers": [
      {
        "name": "web",
        "properties": {
          "image": "mcr.microsoft.com/azuredocs/aci-helloworld:latest",
          "command": [],
          "ports": [
            {
              "port": 80,
              "protocol": "TCP"
            }
          ],
          "environmentVariables": [
            {
              "name": "GREETING",
              "value": "hello"
            },
            {
              "name": "API_TOKEN"
            }
          ],
          "resources": {
            "requests": {
              "cpu": 1.0,
              "memoryInGB": 1.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            },
            {
              "name": "secret-volume",
              "mountPath": "/mnt/secrets",
              "readOnly": true
            }
          ],
          "instanceView": {
            "restartCount": 0,
            "currentState": {
              "state": "Running",
              "startTime": "2026-08-22T10:15:03Z",
              "detailStatus": ""
            },
            "events": [
              {
                "count": 1,
                "firstTimestamp": "2026-08-22T10:15:01Z",
                "lastTimestamp": "2026-08-22T10:15:01Z",
                "name": "Pulling",
                "message": "pulling image \"mcr.microsoft.com/azuredocs/aci-helloworld:latest\"",
                "type": "Normal"
              },
              {
                "count": 1,
                "firstTimestamp": "2026-08-22T10:15:02Z",
                "lastTimestamp": "2026-08-22T10:15:02Z",
                "name": "Pulled",
                "message": "Successfully pulled image \"mcr.microsoft.com/azuredocs/aci-helloworld:latest\"",
                "type": "Normal"
              },
              {
                "count": 1,
                "firstTimestamp": "2026-08-22T10:15:03Z",
                "lastTimestamp": "2026-08-22T10:15:03Z",
                "name": "Started",
                "message": "Started container web",
                "type": "Normal"
              }
            ]
          }
        }
      },
      {
        "name": "sidecar",
        "properties": {
          "image": "alpine:3.20",
          "command": [
            "sh",
            "-c",
            "while true; do wget -q -O- http://localhost:80 >/dev/null && echo probe-ok; sleep 10; done"
          ],
          "ports": [],
          "environmentVariables": [],
          "resources": {
            "requests": {
              "cpu": 0.5,
              "memoryInGB": 0.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            }
          ],
          "instanceView": {
            "restartCount": 0,
            "currentState": {
              "state": "Running",
              "startTime": "2026-08-22T10:15:04Z",
              "detailStatus": ""
            },
            "events": [
              {
                "count": 1,
                "firstTimestamp": "2026-08-22T10:15:03Z",
                "lastTimestamp": "2026-08-22T10:15:03Z",
                "name": "Pulled",
                "message": "Successfully pulled image \"alpine:3.20\"",
                "type": "Normal"
              },
              {
                "count": 1,
                "firstTimestamp": "2026-08-22T10:15:04Z",
                "lastTimestamp": "2026-08-22T10:15:04Z",
                "name": "Started",
                "message": "Started container sidecar",
                "type": "Normal"
              }
            ]
          }
        }
      }
    ],
    "imageRegistryCredentials": [],
    "ipAddress": {
      "type": "Public",
      "ip": "127.0.0.1",
      "dnsNameLabel": "floci-demo-group",
      "autoGeneratedDomainNameLabelScope": "Unsecure",
      "fqdn": "floci-demo-group.eastus.azurecontainer.io",
      "ports": [
        {
          "port": 80,
          "protocol": "TCP"
        }
      ]
    },
    "volumes": [
      {
        "name": "scratch-volume",
        "emptyDir": {}
      },
      {
        "name": "secret-volume",
        "secret": {}
      }
    ],
    "instanceView": {
      "state": "Running",
      "events": [
        {
          "count": 1,
          "firstTimestamp": "2026-08-22T10:15:00Z",
          "lastTimestamp": "2026-08-22T10:15:00Z",
          "name": "PortMapped",
          "message": "Container group port 80 published on host port 8500",
          "type": "Normal"
        }
      ]
    }
  }
}
```

Note the three redactions, all visible in this body: `API_TOKEN` comes back with `name` only
and no value; `secret-volume` comes back as `"secret": {}`; and no `storageAccountKey` or
registry `password` appears anywhere.

### Get — request and response

`GET /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group?api-version=2023-05-01`

`200 OK`. The body is the create response above, `instanceView` included. The body below is
rendered without the instance views only to keep the example short; a real response carries
them, exactly as the [`$expand=instanceView` example](#get-with-expandinstanceview) shows:

```json
{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group",
  "name": "demo-group",
  "type": "Microsoft.ContainerInstance/containerGroups",
  "location": "eastus",
  "tags": {
    "env": "dev",
    "owner": "floci"
  },
  "identity": {
    "type": "SystemAssigned",
    "principalId": "6b1f0f1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
    "tenantId": "00000000-0000-0000-0000-000000000002"
  },
  "properties": {
    "provisioningState": "Succeeded",
    "osType": "Linux",
    "restartPolicy": "Always",
    "sku": "Standard",
    "containers": [
      {
        "name": "web",
        "properties": {
          "image": "mcr.microsoft.com/azuredocs/aci-helloworld:latest",
          "command": [],
          "ports": [
            {
              "port": 80,
              "protocol": "TCP"
            }
          ],
          "environmentVariables": [
            {
              "name": "GREETING",
              "value": "hello"
            },
            {
              "name": "API_TOKEN"
            }
          ],
          "resources": {
            "requests": {
              "cpu": 1.0,
              "memoryInGB": 1.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            },
            {
              "name": "secret-volume",
              "mountPath": "/mnt/secrets",
              "readOnly": true
            }
          ]
        }
      },
      {
        "name": "sidecar",
        "properties": {
          "image": "alpine:3.20",
          "command": [
            "sh",
            "-c",
            "while true; do wget -q -O- http://localhost:80 >/dev/null && echo probe-ok; sleep 10; done"
          ],
          "ports": [],
          "environmentVariables": [],
          "resources": {
            "requests": {
              "cpu": 0.5,
              "memoryInGB": 0.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            }
          ]
        }
      }
    ],
    "imageRegistryCredentials": [],
    "ipAddress": {
      "type": "Public",
      "ip": "127.0.0.1",
      "dnsNameLabel": "floci-demo-group",
      "autoGeneratedDomainNameLabelScope": "Unsecure",
      "fqdn": "floci-demo-group.eastus.azurecontainer.io",
      "ports": [
        {
          "port": 80,
          "protocol": "TCP"
        }
      ]
    },
    "volumes": [
      {
        "name": "scratch-volume",
        "emptyDir": {}
      },
      {
        "name": "secret-volume",
        "secret": {}
      }
    ]
  }
}
```

### Get with `$expand=instanceView`

`GET /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group?api-version=2023-05-01&$expand=instanceView`

`200 OK`. `$expand` is accepted and ignored, so this is byte for byte the body a plain `GET`
returns, and identical to the **create response** above except that `provisioningState` and the
state values reflect the current moment. The following body shows
the same group after the `web` container has crashed once and been restarted under
`restartPolicy: Always`:

```json
{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group",
  "name": "demo-group",
  "type": "Microsoft.ContainerInstance/containerGroups",
  "location": "eastus",
  "tags": {
    "env": "dev",
    "owner": "floci"
  },
  "identity": {
    "type": "SystemAssigned",
    "principalId": "6b1f0f1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
    "tenantId": "00000000-0000-0000-0000-000000000002"
  },
  "properties": {
    "provisioningState": "Succeeded",
    "osType": "Linux",
    "restartPolicy": "Always",
    "sku": "Standard",
    "containers": [
      {
        "name": "web",
        "properties": {
          "image": "mcr.microsoft.com/azuredocs/aci-helloworld:latest",
          "command": [],
          "ports": [
            {
              "port": 80,
              "protocol": "TCP"
            }
          ],
          "environmentVariables": [
            {
              "name": "GREETING",
              "value": "hello"
            },
            {
              "name": "API_TOKEN"
            }
          ],
          "resources": {
            "requests": {
              "cpu": 1.0,
              "memoryInGB": 1.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            },
            {
              "name": "secret-volume",
              "mountPath": "/mnt/secrets",
              "readOnly": true
            }
          ],
          "instanceView": {
            "restartCount": 1,
            "currentState": {
              "state": "Running",
              "startTime": "2026-08-22T10:20:11Z",
              "detailStatus": ""
            },
            "previousState": {
              "state": "Terminated",
              "startTime": "2026-08-22T10:15:03Z",
              "exitCode": 137,
              "finishTime": "2026-08-22T10:20:09Z",
              "detailStatus": "Error"
            },
            "events": [
              {
                "count": 2,
                "firstTimestamp": "2026-08-22T10:15:03Z",
                "lastTimestamp": "2026-08-22T10:20:11Z",
                "name": "Started",
                "message": "Started container web",
                "type": "Normal"
              }
            ]
          }
        }
      },
      {
        "name": "sidecar",
        "properties": {
          "image": "alpine:3.20",
          "command": [
            "sh",
            "-c",
            "while true; do wget -q -O- http://localhost:80 >/dev/null && echo probe-ok; sleep 10; done"
          ],
          "ports": [],
          "environmentVariables": [],
          "resources": {
            "requests": {
              "cpu": 0.5,
              "memoryInGB": 0.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            }
          ],
          "instanceView": {
            "restartCount": 0,
            "currentState": {
              "state": "Running",
              "startTime": "2026-08-22T10:15:04Z",
              "detailStatus": ""
            },
            "events": [
              {
                "count": 1,
                "firstTimestamp": "2026-08-22T10:15:04Z",
                "lastTimestamp": "2026-08-22T10:15:04Z",
                "name": "Started",
                "message": "Started container sidecar",
                "type": "Normal"
              }
            ]
          }
        }
      }
    ],
    "imageRegistryCredentials": [],
    "ipAddress": {
      "type": "Public",
      "ip": "127.0.0.1",
      "dnsNameLabel": "floci-demo-group",
      "autoGeneratedDomainNameLabelScope": "Unsecure",
      "fqdn": "floci-demo-group.eastus.azurecontainer.io",
      "ports": [
        {
          "port": 80,
          "protocol": "TCP"
        }
      ]
    },
    "volumes": [
      {
        "name": "scratch-volume",
        "emptyDir": {}
      },
      {
        "name": "secret-volume",
        "secret": {}
      }
    ],
    "instanceView": {
      "state": "Running",
      "events": [
        {
          "count": 1,
          "firstTimestamp": "2026-08-22T10:15:00Z",
          "lastTimestamp": "2026-08-22T10:15:00Z",
          "name": "PortMapped",
          "message": "Container group port 80 published on host port 8500",
          "type": "Normal"
        }
      ]
    }
  }
}
```

### List in resource group

`GET /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups?api-version=2023-05-01`

`200 OK`. Entries never carry `instanceView`. There is no paging: `nextLink` is always omitted.

```json
{
  "value": [
    {
      "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group",
      "name": "demo-group",
      "type": "Microsoft.ContainerInstance/containerGroups",
      "location": "eastus",
      "tags": {
        "env": "dev",
        "owner": "floci"
      },
      "identity": {
        "type": "SystemAssigned",
        "principalId": "6b1f0f1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
        "tenantId": "00000000-0000-0000-0000-000000000002"
      },
      "properties": {
        "provisioningState": "Succeeded",
        "osType": "Linux",
        "restartPolicy": "Always",
        "sku": "Standard",
        "containers": [
          {
            "name": "web",
            "properties": {
              "image": "mcr.microsoft.com/azuredocs/aci-helloworld:latest",
              "command": [],
              "ports": [
                {
                  "port": 80,
                  "protocol": "TCP"
                }
              ],
              "environmentVariables": [
                {
                  "name": "GREETING",
                  "value": "hello"
                },
                {
                  "name": "API_TOKEN"
                }
              ],
              "resources": {
                "requests": {
                  "cpu": 1.0,
                  "memoryInGB": 1.5
                }
              },
              "volumeMounts": [
                {
                  "name": "scratch-volume",
                  "mountPath": "/mnt/scratch",
                  "readOnly": false
                },
                {
                  "name": "secret-volume",
                  "mountPath": "/mnt/secrets",
                  "readOnly": true
                }
              ]
            }
          },
          {
            "name": "sidecar",
            "properties": {
              "image": "alpine:3.20",
              "command": [
                "sh",
                "-c",
                "while true; do wget -q -O- http://localhost:80 >/dev/null && echo probe-ok; sleep 10; done"
              ],
              "ports": [],
              "environmentVariables": [],
              "resources": {
                "requests": {
                  "cpu": 0.5,
                  "memoryInGB": 0.5
                }
              },
              "volumeMounts": [
                {
                  "name": "scratch-volume",
                  "mountPath": "/mnt/scratch",
                  "readOnly": false
                }
              ]
            }
          }
        ],
        "imageRegistryCredentials": [],
        "ipAddress": {
          "type": "Public",
          "ip": "127.0.0.1",
          "dnsNameLabel": "floci-demo-group",
          "autoGeneratedDomainNameLabelScope": "Unsecure",
          "fqdn": "floci-demo-group.eastus.azurecontainer.io",
          "ports": [
            {
              "port": 80,
              "protocol": "TCP"
            }
          ]
        },
        "volumes": [
          {
            "name": "scratch-volume",
            "emptyDir": {}
          },
          {
            "name": "secret-volume",
            "secret": {}
          }
        ]
      }
    }
  ]
}
```

### List in subscription

`GET /subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.ContainerInstance/containerGroups?api-version=2023-05-01`

`200 OK`. Same shape as the resource-group list. With no groups in the subscription:

```json
{
  "value": []
}
```

### Patch tags — request

`PATCH /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group?api-version=2023-05-01`

```json
{
  "tags": {
    "env": "staging",
    "cost-center": "1234"
  }
}
```

### Patch tags — response

`200 OK`. `tags` are **replaced**, not merged (Azure's `ContainerGroups_Update` replaces the tag
collection). Body is the full container group; only the changed part is shown here as the
remainder is identical to the [get response](#get-request-and-response):

```json
{
  "id": "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group",
  "name": "demo-group",
  "type": "Microsoft.ContainerInstance/containerGroups",
  "location": "eastus",
  "tags": {
    "env": "staging",
    "cost-center": "1234"
  },
  "identity": {
    "type": "SystemAssigned",
    "principalId": "6b1f0f1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
    "tenantId": "00000000-0000-0000-0000-000000000002"
  },
  "properties": {
    "provisioningState": "Succeeded",
    "osType": "Linux",
    "restartPolicy": "Always",
    "sku": "Standard",
    "containers": [
      {
        "name": "web",
        "properties": {
          "image": "mcr.microsoft.com/azuredocs/aci-helloworld:latest",
          "command": [],
          "ports": [
            {
              "port": 80,
              "protocol": "TCP"
            }
          ],
          "environmentVariables": [
            {
              "name": "GREETING",
              "value": "hello"
            },
            {
              "name": "API_TOKEN"
            }
          ],
          "resources": {
            "requests": {
              "cpu": 1.0,
              "memoryInGB": 1.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            },
            {
              "name": "secret-volume",
              "mountPath": "/mnt/secrets",
              "readOnly": true
            }
          ]
        }
      },
      {
        "name": "sidecar",
        "properties": {
          "image": "alpine:3.20",
          "command": [
            "sh",
            "-c",
            "while true; do wget -q -O- http://localhost:80 >/dev/null && echo probe-ok; sleep 10; done"
          ],
          "ports": [],
          "environmentVariables": [],
          "resources": {
            "requests": {
              "cpu": 0.5,
              "memoryInGB": 0.5
            }
          },
          "volumeMounts": [
            {
              "name": "scratch-volume",
              "mountPath": "/mnt/scratch",
              "readOnly": false
            }
          ]
        }
      }
    ],
    "imageRegistryCredentials": [],
    "ipAddress": {
      "type": "Public",
      "ip": "127.0.0.1",
      "dnsNameLabel": "floci-demo-group",
      "autoGeneratedDomainNameLabelScope": "Unsecure",
      "fqdn": "floci-demo-group.eastus.azurecontainer.io",
      "ports": [
        {
          "port": 80,
          "protocol": "TCP"
        }
      ]
    },
    "volumes": [
      {
        "name": "scratch-volume",
        "emptyDir": {}
      },
      {
        "name": "secret-volume",
        "secret": {}
      }
    ]
  }
}
```

### Logs

`GET /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group/containers/web/logs?api-version=2023-05-01&tail=3&timestamps=true`

`200 OK`

```json
{
  "content": "2026-08-22T10:15:05.121308472Z listening on port 80\n2026-08-22T10:15:12.004918331Z GET / 200\n2026-08-22T10:15:22.517700104Z GET /favicon.ico 404\n"
}
```

Query parameters:

| Parameter | Type | Default | Validation | Meaning |
|---|---|---|---|---|
| `tail` | integer | absent — all available logs, subject to the byte and line caps | when present must parse as an integer ≥ 1; otherwise `InvalidParameter` with target `tail` and reason `The value must be greater than or equal to 1.` | Number of lines from the end of the log |
| `timestamps` | boolean | `false` | `true` and `false` case-insensitively; any other value is treated as `false` | Prefix each line with an RFC 3339 nanosecond UTC timestamp and a single space |

With no logs available, or in mocked/degraded mode:

```json
{
  "content": ""
}
```

### Stop

`POST /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group/stop?api-version=2023-05-01`

`204 No Content`, empty body, no headers beyond the defaults.

Afterwards, `GET ...?$expand=instanceView` reports:

```json
{
  "state": "Stopped",
  "events": [
    {
      "count": 1,
      "firstTimestamp": "2026-08-22T10:15:00Z",
      "lastTimestamp": "2026-08-22T10:15:00Z",
      "name": "PortMapped",
      "message": "Container group port 80 published on host port 8500",
      "type": "Normal"
    },
    {
      "count": 1,
      "firstTimestamp": "2026-08-22T10:30:00Z",
      "lastTimestamp": "2026-08-22T10:30:00Z",
      "name": "Killing",
      "message": "Stopping container group demo-group",
      "type": "Normal"
    }
  ]
}
```

and every container's `currentState` becomes:

```json
{
  "state": "Terminated",
  "startTime": "2026-08-22T10:15:03Z",
  "exitCode": 137,
  "finishTime": "2026-08-22T10:30:01Z",
  "detailStatus": "Error"
}
```

Exit code `137` is `128 + SIGKILL(9)` and is what Docker reports for a container killed after
the stop grace period. A container that exits cleanly on `SIGTERM` reports `exitCode: 0` and
`detailStatus: "Completed"`.

### Start

`POST /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group/start?api-version=2023-05-01`

`204 No Content`, empty body.

### Restart

`POST /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group/restart?api-version=2023-05-01`

`204 No Content`, empty body. Every container's `restartCount` increases by 1.

### Delete

`DELETE /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group?api-version=2023-05-01`

`204 No Content`, empty body. Repeating the request also returns `204`.

### Outbound network dependencies

`GET /subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/aci-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group/outboundNetworkDependenciesEndpoints?api-version=2023-05-01`

`200 OK`. The swagger's `NetworkDependenciesResponse` is documented as *"Response for network
dependencies, always empty list."*

```json
[]
```

### Usages

`GET /subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.ContainerInstance/locations/eastus/usages?api-version=2023-05-01`

`200 OK`, with one container group and 1.5 CPU currently in use in that subscription:

```json
{
  "value": [
    {
      "id": "/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.ContainerInstance/locations/eastus/usages/ContainerGroups",
      "unit": "Count",
      "currentValue": 1,
      "limit": 100,
      "name": {
        "value": "ContainerGroups",
        "localizedValue": "Container Groups"
      }
    },
    {
      "id": "/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.ContainerInstance/locations/eastus/usages/StandardCores",
      "unit": "Count",
      "currentValue": 2,
      "limit": 100,
      "name": {
        "value": "StandardCores",
        "localizedValue": "Standard Cores"
      }
    }
  ]
}
```

`currentValue` for `ContainerGroups` is the number of stored groups in the subscription.
`currentValue` for `StandardCores` is the sum of every container's `requests.cpu` across those
groups, rounded up to the nearest integer (`1.0 + 0.5 = 1.5` → `2`).

### Capabilities

`GET /subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.ContainerInstance/locations/eastus/capabilities?api-version=2023-05-01`

`200 OK`

```json
{
  "value": [
    {
      "resourceType": "containerGroups",
      "osType": "Linux",
      "location": "eastus",
      "ipAddressType": "Public",
      "gpu": "None",
      "capabilities": {
        "maxMemoryInGB": 240.0,
        "maxCpu": 31.0,
        "maxGpuCount": 0.0
      }
    }
  ]
}
```

### Cached images

`GET /subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.ContainerInstance/locations/eastus/cachedImages?api-version=2023-05-01`

`200 OK`

```json
{
  "value": []
}
```

### Error bodies

Group name violates the naming rule:

`PUT .../containerGroups/Demo_Group?api-version=2023-05-01` → `400 Bad Request`

```json
{
  "error": {
    "code": "InvalidResourceName",
    "message": "The Resource Name 'Demo_Group' is invalid. Container group names must be 1 to 63 characters long, contain only lowercase letters, numbers and hyphens, must not start or end with a hyphen, and must not contain consecutive hyphens.",
    "target": "containerGroupName"
  }
}
```

Missing `properties.osType`:

`400 Bad Request`

```json
{
  "error": {
    "code": "InvalidRequestContent",
    "message": "The request content was invalid and could not be deserialized: required property 'properties.osType' is missing or of the wrong type.",
    "target": "properties.osType"
  }
}
```

Invalid `restartPolicy`:

`400 Bad Request`

```json
{
  "error": {
    "code": "InvalidParameter",
    "message": "The value 'Sometimes' provided for 'properties.restartPolicy' is not valid. Valid values are 'Always', 'OnFailure' and 'Never'.",
    "target": "properties.restartPolicy"
  }
}
```

`gitRepo` volume:

`400 Bad Request`

```json
{
  "error": {
    "code": "NotSupported",
    "message": "Volume type 'gitRepo' is not supported by floci-az. Use 'emptyDir', 'secret' or 'azureFile' instead.",
    "target": "properties.volumes[0].gitRepo"
  }
}
```

Unknown container group:

`404 Not Found`

```json
{
  "error": {
    "code": "ResourceNotFound",
    "message": "The Resource 'Microsoft.ContainerInstance/containerGroups/no-such-group' under resource group 'aci-rg' was not found."
  }
}
```

Unknown container in an existing group:

`404 Not Found`

```json
{
  "error": {
    "code": "ResourceNotFound",
    "message": "The container 'no-such-container' was not found in container group 'demo-group' under resource group 'aci-rg'.",
    "target": "containerName"
  }
}
```

Exec:

`501 Not Implemented`

```json
{
  "error": {
    "code": "NotImplemented",
    "message": "Container exec is not implemented by floci-az: the emulator serves no websocket data plane."
  }
}
```

## Scope summary

Complete classification of every `2023-05-01` container-group property.

**In scope — applied to the Docker runtime:**
`location` (FQDN region), `tags`, `properties.containers[]` (`name`, `image`, `command`,
`ports[].port`, `environmentVariables[].name`/`value`/`secureValue`,
`resources.requests.memoryInGB`, `volumeMounts[]`, `securityContext.privileged`,
`securityContext.runAsUser`, `securityContext.runAsGroup`), `properties.initContainers[]`,
`properties.osType` (validated), `properties.restartPolicy`,
`properties.imageRegistryCredentials[]` (`server`, `username`, `password`),
`properties.ipAddress.type`, `properties.ipAddress.ports[]`,
`properties.ipAddress.dnsNameLabel`, `properties.volumes[].emptyDir`,
`properties.volumes[].secret`, `properties.volumes[].azureFile`.

**Accepted and echoed — no runtime effect:**
`zones`, `identity` (all of it), `properties.containers[].properties.resources.requests.cpu`,
`properties.containers[].properties.resources.limits`,
`properties.containers[].properties.resources.requests.gpu`,
`properties.containers[].properties.ports[].protocol`,
`properties.containers[].properties.livenessProbe`,
`properties.containers[].properties.readinessProbe`,
`properties.containers[].properties.securityContext.capabilities`,
`properties.containers[].properties.securityContext.allowPrivilegeEscalation`,
`properties.containers[].properties.securityContext.seccompProfile`,
`properties.diagnostics`, `properties.subnetIds`, `properties.dnsConfig`, `properties.sku`,
`properties.encryptionProperties`, `properties.extensions`,
`properties.confidentialComputeProperties`, `properties.priority`,
`properties.ipAddress.autoGeneratedDomainNameLabelScope`,
`properties.imageRegistryCredentials[].identity`,
`properties.imageRegistryCredentials[].identityUrl`,
`properties.volumes[].azureFile.storageAccountKey` (accepted, never returned).

**Rejected:** `properties.volumes[].gitRepo`.

**Read-only — input ignored, output generated:**
`id`, `name`, `type`, `properties.provisioningState`, `properties.instanceView`,
`properties.containers[].properties.instanceView`,
`properties.initContainers[].properties.instanceView`, `properties.ipAddress.ip`,
`properties.ipAddress.fqdn`, `identity.principalId`, `identity.tenantId`,
`identity.userAssignedIdentities.{id}.principalId`,
`identity.userAssignedIdentities.{id}.clientId`.

## Assumption A21

**Question.** Azure documents *"Allocate a minimum of 1 CPU and 1 GB of memory to a container
group"*. Does the emulator enforce it?

**Recommended value.** No. A group whose CPU sum or memory sum is below 1 is accepted.

**Reasoning.** The same documentation immediately adds that *"individual container instances
within a group can be provisioned with less than one CPU and 1 GB of memory"*, and the minimum
exists because Azure bills and schedules whole vCPUs. Locally there is nothing to schedule.
Enforcing it would reject small, valid test payloads for no benefit. This is recorded here
rather than in the main assumptions register because it belongs with the resource limits it
qualifies; it is otherwise an ordinary entry of that register.

## Appendix — transcribed schema

Every type in scope, transcribed from
`stable/2023-05-01/containerInstance.json`. **Req** = listed in the type's `required` array;
**RO** = `readOnly: true` in the swagger. Enum values are quoted verbatim from the swagger's
`enum` arrays. This appendix is complete for the feature: nothing in scope requires opening the
swagger.

Where a type's definition is inline in the swagger rather than a named `$ref`, the name used
here is noted.

### `ContainerGroup`

`"allOf": ["#/definitions/Resource", "#/definitions/ContainerGroupProperties"]` — the flattened
result is the union of the two tables below.

### `Resource`

| Property | Type | Req | RO |
|---|---|---|---|
| `id` | string | no | **yes** |
| `name` | string | no | **yes** |
| `type` | string | no | **yes** |
| `location` | string | no | no |
| `tags` | object (`additionalProperties: string`) | no | no |
| `zones` | array&lt;string&gt; | no | no |

### `ContainerGroupProperties`

| Property | Type | Req | RO |
|---|---|---|---|
| `identity` | `ContainerGroupIdentity` | no | no |
| `properties` | inline object (below) | **yes** | no |

### `ContainerGroupProperties.properties` (inline)

| Property | Type | Req | RO | Enum / default |
|---|---|---|---|---|
| `provisioningState` | string | no | **yes** | — |
| `containers` | array&lt;`Container`&gt; | **yes** | no | — |
| `imageRegistryCredentials` | array&lt;`ImageRegistryCredential`&gt; | no | no | — |
| `restartPolicy` | string | no | no | `Always`, `OnFailure`, `Never` (`x-ms-enum` `ContainerGroupRestartPolicy`, `modelAsString: true`) |
| `ipAddress` | `IpAddress` | no | no | — |
| `osType` | string | **yes** | no | `Windows`, `Linux` (`x-ms-enum` `OperatingSystemTypes`, `modelAsString: true`) |
| `volumes` | array&lt;`Volume`&gt; | no | no | — |
| `instanceView` | inline object: `{events: array<Event> (RO), state: string (RO)}` | no | **yes** | — |
| `diagnostics` | `ContainerGroupDiagnostics` | no | no | — |
| `subnetIds` | array&lt;`ContainerGroupSubnetId`&gt; | no | no | — |
| `dnsConfig` | `DnsConfiguration` | no | no | — |
| `sku` | `ContainerGroupSku` | no | no | — |
| `encryptionProperties` | `EncryptionProperties` | no | no | — |
| `initContainers` | array&lt;`InitContainerDefinition`&gt; | no | no | — |
| `extensions` | array&lt;`DeploymentExtensionSpec`&gt; | no | no | — |
| `confidentialComputeProperties` | `ConfidentialComputeProperties` | no | no | — |
| `priority` | string | no | no | `Regular`, `Spot` (`x-ms-enum` `ContainerGroupPriority`, `modelAsString: true`) |

### `ContainerGroupIdentity`

| Property | Type | Req | RO | Enum |
|---|---|---|---|---|
| `principalId` | string | no | **yes** | — |
| `tenantId` | string | no | **yes** | — |
| `type` | string | no | no | `SystemAssigned`, `UserAssigned`, `SystemAssigned, UserAssigned`, `None` (`x-ms-enum` `ResourceIdentityType`, `modelAsString: false` — a closed enum) |
| `userAssignedIdentities` | object (`additionalProperties: UserAssignedIdentities`) | no | no | — |

### `UserAssignedIdentities`

| Property | Type | Req | RO |
|---|---|---|---|
| `principalId` | string | no | **yes** |
| `clientId` | string | no | **yes** |

Keys are ARM resource ids of the form
`/subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{identityName}`.

### `Container`

| Property | Type | Req | RO |
|---|---|---|---|
| `name` | string | **yes** | no |
| `properties` | `ContainerProperties` | **yes** | no |

### `ContainerProperties`

| Property | Type | Req | RO |
|---|---|---|---|
| `image` | string | **yes** | no |
| `command` | array&lt;string&gt; | no | no |
| `ports` | array&lt;`ContainerPort`&gt; | no | no |
| `environmentVariables` | array&lt;`EnvironmentVariable`&gt; | no | no |
| `instanceView` | inline object (below) | no | **yes** |
| `resources` | `ResourceRequirements` | **yes** | no |
| `volumeMounts` | array&lt;`VolumeMount`&gt; | no | no |
| `livenessProbe` | `ContainerProbe` | no | no |
| `readinessProbe` | `ContainerProbe` | no | no |
| `securityContext` | `SecurityContextDefinition` | no | no |

### `ContainerProperties.instanceView` (inline)

| Property | Type | Req | RO |
|---|---|---|---|
| `restartCount` | integer (int32) | no | **yes** |
| `currentState` | `ContainerState` | no | **yes** |
| `previousState` | `ContainerState` | no | **yes** |
| `events` | array&lt;`Event`&gt; | no | **yes** |

### `ContainerState`

| Property | Type | Req | RO |
|---|---|---|---|
| `state` | string | no | **yes** |
| `startTime` | string (date-time) | no | **yes** |
| `exitCode` | integer (int32) | no | **yes** |
| `finishTime` | string (date-time) | no | **yes** |
| `detailStatus` | string | no | **yes** |

The swagger's description of `exitCode`: *"The container instance exit codes correspond to those
from the `docker run` command."* No enum is declared for `state`.

### `Event`

| Property | Type | Req | RO |
|---|---|---|---|
| `count` | integer (int32) | no | **yes** |
| `firstTimestamp` | string (date-time) | no | **yes** |
| `lastTimestamp` | string (date-time) | no | **yes** |
| `name` | string | no | **yes** |
| `message` | string | no | **yes** |
| `type` | string | no | **yes** |

### `ContainerPort`

| Property | Type | Req | RO | Enum |
|---|---|---|---|---|
| `protocol` | string | no | no | `TCP`, `UDP` (`x-ms-enum` `ContainerNetworkProtocol`, `modelAsString: true`) |
| `port` | integer (int32) | **yes** | no | — |

### `Port`

The group-level port type. Identical in shape to `ContainerPort`; its enum is named differently.

| Property | Type | Req | RO | Enum |
|---|---|---|---|---|
| `protocol` | string | no | no | `TCP`, `UDP` (`x-ms-enum` `ContainerGroupNetworkProtocol`, `modelAsString: true`) |
| `port` | integer (int32) | **yes** | no | — |

### `EnvironmentVariable`

| Property | Type | Req | RO | Notes |
|---|---|---|---|---|
| `name` | string | **yes** | no | — |
| `value` | string | no | no | — |
| `secureValue` | string | no | no | `x-ms-secret: true` |

### `ResourceRequirements`

| Property | Type | Req | RO |
|---|---|---|---|
| `requests` | `ResourceRequests` | **yes** | no |
| `limits` | `ResourceLimits` | no | no |

### `ResourceRequests`

| Property | Type | Req | RO |
|---|---|---|---|
| `memoryInGB` | number (double) | **yes** | no |
| `cpu` | number (double) | **yes** | no |
| `gpu` | `GpuResource` | no | no |

### `ResourceLimits`

| Property | Type | Req | RO |
|---|---|---|---|
| `memoryInGB` | number (double) | no | no |
| `cpu` | number (double) | no | no |
| `gpu` | `GpuResource` | no | no |

### `GpuResource`

| Property | Type | Req | RO | Enum |
|---|---|---|---|---|
| `count` | integer (int32) | **yes** | no | — |
| `sku` | string | **yes** | no | `K80`, `P100`, `V100` (`x-ms-enum` `GpuSku`, `modelAsString: true`) |

### `VolumeMount`

| Property | Type | Req | RO | Notes |
|---|---|---|---|---|
| `name` | string | **yes** | no | — |
| `mountPath` | string | **yes** | no | swagger: *"Must not contain colon (:)."* |
| `readOnly` | boolean | no | no | — |

### `Volume`

| Property | Type | Req | RO |
|---|---|---|---|
| `name` | string | **yes** | no |
| `azureFile` | `AzureFileVolume` | no | no |
| `emptyDir` | `EmptyDirVolume` | no | no |
| `secret` | `SecretVolume` | no | no |
| `gitRepo` | `GitRepoVolume` | no | no |

### `AzureFileVolume`

| Property | Type | Req | RO |
|---|---|---|---|
| `shareName` | string | **yes** | no |
| `readOnly` | boolean | no | no |
| `storageAccountName` | string | **yes** | no |
| `storageAccountKey` | string | no | no |

### `EmptyDirVolume`

`{"type": "object", "properties": {}}` — an empty object, no properties.

### `SecretVolume`

`{"type": "object", "additionalProperties": {"type": "string"}}` — an arbitrary map of string
to string. Values are Base64-encoded secret contents; keys become file names in the volume.

### `GitRepoVolume`

| Property | Type | Req | RO |
|---|---|---|---|
| `directory` | string | no | no |
| `repository` | string | **yes** | no |
| `revision` | string | no | no |

Rejected by floci-az (V29).

### `ImageRegistryCredential`

| Property | Type | Req | RO | Notes |
|---|---|---|---|---|
| `server` | string | **yes** | no | swagger: *"The Docker image registry server without a protocol such as `http` and `https`."* |
| `username` | string | no | no | — |
| `password` | string | no | no | `x-ms-secret: true` |
| `identity` | string | no | no | — |
| `identityUrl` | string | no | no | — |

### `IpAddress`

| Property | Type | Req | RO | Enum / default |
|---|---|---|---|---|
| `ports` | array&lt;`Port`&gt; | **yes** | no | — |
| `type` | string | **yes** | no | `Public`, `Private` (`x-ms-enum` `ContainerGroupIpAddressType`, `modelAsString: true`) |
| `ip` | string | no | no | — |
| `dnsNameLabel` | string | no | no | — |
| `autoGeneratedDomainNameLabelScope` | string | no | no | `Unsecure`, `TenantReuse`, `SubscriptionReuse`, `ResourceGroupReuse`, `Noreuse`; `"default": "Unsecure"` (`x-ms-enum` `dnsNameLabelReusePolicy`, `modelAsString: true`) |
| `fqdn` | string | no | **yes** | — |

Note: the swagger declares `ip` without `readOnly`, but no client sets it and Azure always
generates it. floci-az treats it as read-only.

### `ContainerGroupSku`

A bare string enum, not an object: `Standard`, `Dedicated`, `Confidential`
(`x-ms-enum` `ContainerGroupSku`, `modelAsString: true`).

### `ContainerGroupDiagnostics`

| Property | Type | Req | RO |
|---|---|---|---|
| `logAnalytics` | `LogAnalytics` | no | no |

### `LogAnalytics`

| Property | Type | Req | RO | Enum / notes |
|---|---|---|---|---|
| `workspaceId` | string | **yes** | no | — |
| `workspaceKey` | string | **yes** | no | `x-ms-secret: true` |
| `logType` | string | no | no | `ContainerInsights`, `ContainerInstanceLogs` (`x-ms-enum` `LogAnalyticsLogType`, `modelAsString: true`) |
| `metadata` | object (`additionalProperties: string`) | no | no | — |
| `workspaceResourceId` | string | no | no | `x-ms-secret: true` |

### `ContainerGroupSubnetId`

| Property | Type | Req | RO |
|---|---|---|---|
| `id` | string | **yes** | no |
| `name` | string | no | no |

### `DnsConfiguration`

| Property | Type | Req | RO |
|---|---|---|---|
| `nameServers` | array&lt;string&gt; | **yes** | no |
| `searchDomains` | string | no | no |
| `options` | string | no | no |

### `EncryptionProperties`

| Property | Type | Req | RO |
|---|---|---|---|
| `vaultBaseUrl` | string | **yes** | no |
| `keyName` | string | **yes** | no |
| `keyVersion` | string | **yes** | no |
| `identity` | string | no | no |

### `InitContainerDefinition`

| Property | Type | Req | RO |
|---|---|---|---|
| `name` | string | **yes** | no |
| `properties` | `InitContainerPropertiesDefinition` | **yes** | no |

### `InitContainerPropertiesDefinition`

| Property | Type | Req | RO |
|---|---|---|---|
| `image` | string | no | no |
| `command` | array&lt;string&gt; | no | no |
| `environmentVariables` | array&lt;`EnvironmentVariable`&gt; | no | no |
| `instanceView` | inline object: `{restartCount: int32 (RO), currentState: ContainerState (RO), previousState: ContainerState (RO), events: array<Event> (RO)}` | no | **yes** |
| `volumeMounts` | array&lt;`VolumeMount`&gt; | no | no |
| `securityContext` | `SecurityContextDefinition` | no | no |

### `SecurityContextDefinition`

| Property | Type | Req | RO |
|---|---|---|---|
| `privileged` | boolean | no | no |
| `allowPrivilegeEscalation` | boolean | no | no |
| `capabilities` | `SecurityContextCapabilitiesDefinition` | no | no |
| `runAsGroup` | integer (int32) | no | no |
| `runAsUser` | integer (int32) | no | no |
| `seccompProfile` | string | no | no |

### `SecurityContextCapabilitiesDefinition`

| Property | Type | Req | RO |
|---|---|---|---|
| `add` | array&lt;string&gt; | no | no |
| `drop` | array&lt;string&gt; | no | no |

### `ContainerProbe`

| Property | Type | Req | RO |
|---|---|---|---|
| `exec` | `ContainerExec` | no | no |
| `httpGet` | `ContainerHttpGet` | no | no |
| `initialDelaySeconds` | integer (int32) | no | no |
| `periodSeconds` | integer (int32) | no | no |
| `failureThreshold` | integer (int32) | no | no |
| `successThreshold` | integer (int32) | no | no |
| `timeoutSeconds` | integer (int32) | no | no |

### `ContainerExec`

| Property | Type | Req | RO |
|---|---|---|---|
| `command` | array&lt;string&gt; | no | no |

### `ContainerHttpGet`

| Property | Type | Req | RO | Enum |
|---|---|---|---|---|
| `path` | string | no | no | — |
| `port` | integer (int32) | **yes** | no | — |
| `scheme` | string | no | no | `http`, `https` (`x-ms-enum` `Scheme`, `modelAsString: true`) |
| `httpHeaders` | array&lt;`HttpHeader`&gt; | no | no | — |

### `HttpHeader`

| Property | Type | Req | RO |
|---|---|---|---|
| `name` | string | no | no |
| `value` | string | no | no |

### `DeploymentExtensionSpec`

| Property | Type | Req | RO |
|---|---|---|---|
| `name` | string | **yes** | no |
| `properties` | inline object: `{extensionType: string, version: string, settings: object, protectedSettings: object}` | no | no |

### `ConfidentialComputeProperties`

| Property | Type | Req | RO |
|---|---|---|---|
| `ccePolicy` | string | no | no |

### `Logs`

The `Containers_ListLogs` `200` response body.

| Property | Type | Req | RO |
|---|---|---|---|
| `content` | string | no | no |

### `ContainerGroupListResult`

| Property | Type | Req | RO |
|---|---|---|---|
| `value` | array&lt;`ListResultContainerGroup`&gt; | no | no |
| `nextLink` | string | no | no |

`ListResultContainerGroup` is `allOf: [Resource, ListResultContainerGroupProperties]`, and
`ListResultContainerGroupProperties` has exactly the same two members as
`ContainerGroupProperties` (`identity`, `properties`). Its `properties` object is the same
inline object as `ContainerGroupProperties.properties`. floci-az omits `nextLink` and never
pages.

### `Usage` / `UsageListResult`

| Property | Type | Req | RO |
|---|---|---|---|
| `Usage.id` | string | no | **yes** |
| `Usage.unit` | string | no | **yes** |
| `Usage.currentValue` | integer (int32) | no | **yes** |
| `Usage.limit` | integer (int32) | no | **yes** |
| `Usage.name` | inline object: `{value: string (RO), localizedValue: string (RO)}` | no | **yes** |
| `UsageListResult.value` | array&lt;`Usage`&gt; | no | **yes** |

### `Capabilities` / `CapabilitiesListResult`

| Property | Type | Req | RO |
|---|---|---|---|
| `Capabilities.resourceType` | string | no | **yes** |
| `Capabilities.osType` | string | no | **yes** |
| `Capabilities.location` | string | no | **yes** |
| `Capabilities.ipAddressType` | string | no | **yes** |
| `Capabilities.gpu` | string | no | **yes** |
| `Capabilities.capabilities` | inline object: `{maxMemoryInGB: double (RO), maxCpu: double (RO), maxGpuCount: double (RO)}` | no | **yes** |
| `CapabilitiesListResult.value` | array&lt;`Capabilities`&gt; | no | no |
| `CapabilitiesListResult.nextLink` | string | no | no |

### `cachedImages` / `CachedImagesListResult`

| Property | Type | Req | RO |
|---|---|---|---|
| `cachedImages.osType` | string | **yes** | no |
| `cachedImages.image` | string | **yes** | no |
| `CachedImagesListResult.value` | array&lt;`cachedImages`&gt; | no | no |
| `CachedImagesListResult.nextLink` | string | no | no |

### `NetworkDependenciesResponse`

`{"type": "object", "properties": {}}` — swagger description: *"Response for network
dependencies, always empty list."* floci-az returns a JSON array `[]`, matching what the Java
SDK's `getOutboundNetworkDependenciesEndpoints` deserialises (a `List<String>`).

### `CloudError` / `CloudErrorBody`

| Property | Type | Req | RO |
|---|---|---|---|
| `CloudError.error` | `CloudErrorBody` | no | no |
| `CloudErrorBody.code` | string | no | no |
| `CloudErrorBody.message` | string | no | no |
| `CloudErrorBody.target` | string | no | no |
| `CloudErrorBody.details` | array&lt;`CloudErrorBody`&gt; | no | no |

floci-az never populates `details`.

### `ContainerExecRequest` / `ContainerExecResponse` / `ContainerAttachResponse`

Not served — both operations return `501`. Transcribed for completeness:

| Property | Type | Req | RO | Notes |
|---|---|---|---|---|
| `ContainerExecRequest.command` | string | no | no | — |
| `ContainerExecRequest.terminalSize` | inline object: `{rows: int32, cols: int32}` | no | no | — |
| `ContainerExecResponse.webSocketUri` | string | no | no | — |
| `ContainerExecResponse.password` | string | no | no | `x-ms-secret: true` |
| `ContainerAttachResponse.webSocketUri` | string | no | no | — |
| `ContainerAttachResponse.password` | string | no | no | `x-ms-secret: true` |

### Global parameters

| Name | In | Required | Type |
|---|---|---|---|
| `subscriptionId` | path | yes | string |
| `resourceGroupName` | path | yes | string |
| `containerGroupName` | path | yes | string |
| `containerName` | path | yes | string |
| `location` | path | yes | string |
| `api-version` | query | yes | string |
| `tail` | query | no | integer (int32) — swagger: *"The number of lines to show from the tail of the container instance log. If not provided, all available logs are shown up to 4mb."* |
| `timestamps` | query | no | boolean — swagger: *"If true, adds a timestamp at the beginning of every line of log output. If not provided, defaults to false."* |

The swagger declares **no** `pattern`, `minLength`, or `maxLength` on any path parameter. The
naming rules in this document therefore come from the Azure Resource Manager naming-rules
reference and the Azure Container Instances troubleshooting guide, both cited below.

## Authoritative references

- `specification/containerinstance/resource-manager/Microsoft.ContainerInstance/ContainerInstance/stable/2023-05-01/containerInstance.json`
  in <https://github.com/Azure/azure-rest-api-specs> — every table in the
  [schema appendix](#appendix-transcribed-schema), every enum, every `required` and `readOnly`
  marker, and every `x-ms-secret` marker.
- The same folder's `examples/ContainerGroupsCreateOrUpdate.json`,
  `ContainerGroupsGet_Succeeded.json`, `ContainerGroupsGet_Failed.json`,
  `ContainerGroupsList.json`, `ContainerGroupsUpdate.json`, `ContainerListLogs.json` — the
  response shapes the examples in this document are modelled on.
- <https://learn.microsoft.com/en-us/azure/azure-resource-manager/management/resource-name-rules>,
  section `Microsoft.ContainerInstance` — the container-group naming rule.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-troubleshooting>,
  section *Naming conventions* — the container-name, container-port, DNS-name-label,
  environment-variable, and volume-name rules; section *OS version of image not supported* —
  the `OsVersionNotSupported` error body.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-quotas>,
  sections *Unchangeable (Hard) Limits*, *Changeable Limits*, and *Standard Container Resources* —
  60 containers per group, 20 volumes per group, 5 ports per IP, 4 MB running-instance and
  16 KB / 1 000-line stopped-instance log caps, 100 container groups and 100 standard cores per
  region per subscription, max 31 CPU and 240 GB memory.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-container-groups>,
  section *Minimum and maximum allocation* — the 1 CPU / 1 GB group minimum discussed in
  [assumption A21](#assumption-a21).
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-quickstart> —
  the FQDN format `aci-demo.eastus.azurecontainer.io`.
- <https://learn.microsoft.com/en-us/azure/container-instances/container-instances-volume-secret> —
  secret volumes are read-only and Base64-encoded in the request.
- Repository: `src/main/java/io/floci/az/core/arm/ArmErrors.java`,
  `src/main/java/io/floci/az/core/docker/ContainerDetector.java`,
  `src/main/java/io/floci/az/services/vm/VmHandler.java`.
