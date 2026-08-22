# Container Instances — Test Plan

**Status:** Proposed architecture

**Last updated:** 2026-08-22

Every test that must exist for `Microsoft.ContainerInstance/containerGroups`, by name, with its
concrete payload and expected response. Nothing here says "assert the scenario works": every
case names the request body it sends and the response body it expects.

The architecture is in [Container Instances](container-instances.md). The wire contract, whose
validation rules V1–V50 and error catalog this plan covers case by case, is in the
[resource model](container-instances-resource-model.md). The runtime behaviour under test is in
the [runtime design](container-instances-runtime.md).

## Decision summary

Four layers of coverage:

| Layer | Location | Runs in CI without Docker? |
|---|---|---|
| Handler tests, mocked mode | `src/test/java/io/floci/az/services/containerinstance/ContainerInstanceHandlerTest.java` | Yes |
| Validation tests, mocked mode | `.../ContainerInstanceValidationTest.java` | Yes |
| Docker tests, real mode | `.../ContainerInstanceDockerTest.java` | Skipped via `Assumptions.assumeTrue` when no Docker socket |
| Compatibility suites | `compatibility-tests/sdk-test-java`, `compat-azcli`, `compat-terraform` | Docker required |

Unit tests use `@QuarkusTest` with RestAssured, per AGENTS.md. Docker-dependent tests follow the
`VmDockerTest` precedent exactly (`src/test/java/io/floci/az/services/vm/VmDockerTest.java:72-74`):

```java
boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
        || System.getenv("DOCKER_HOST") != null;
assumeTrue(dockerAvailable, "Docker socket not available — skipping real container-instance tests");
```

## Goals

- One test per validation rule in the [error catalog](container-instances-resource-model.md#error-catalog),
  asserting the exact `code`, `target`, and `message`.
- One test per row of the [routing table](container-instances.md#routing-table), asserting a
  concrete response body — this is the mechanism that makes a #106-style placeholder route
  impossible to merge.
- Full coverage of both state machines, including every `restartPolicy` and the
  infra-container-death repair.
- Proof that no secret ever leaves the process.
- Proof that the whole service works with no Docker socket.

## Non-goals

- Performance or load testing.
- Testing routes the emulator does not serve.

## Shared fixtures

### `ContainerInstanceFixtures.java`

New file: `src/test/java/io/floci/az/services/containerinstance/ContainerInstanceFixtures.java`.

```java
package io.floci.az.services.containerinstance;

/** Request bodies shared by the container-instance tests. */
final class ContainerInstanceFixtures {

    static final String SUB = "test-sub-aci";
    static final String RG  = "test-rg-aci";
    static final String API = "?api-version=2023-05-01";
    static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG
                    + "/providers/Microsoft.ContainerInstance";
    static final String SUB_BASE =
            "/subscriptions/" + SUB + "/providers/Microsoft.ContainerInstance";

    /** Smallest body that passes every validation rule. */
    static final String MINIMAL = """
            {
              "location": "eastus",
              "properties": {
                "osType": "Linux",
                "containers": [
                  {
                    "name": "web",
                    "properties": {
                      "image": "alpine:3.20",
                      "resources": {"requests": {"cpu": 1.0, "memoryInGB": 1.0}}
                    }
                  }
                ]
              }
            }
            """;

    /** Two containers, a port, an emptyDir volume, a secret volume, secure env, tags. */
    static final String FULL = """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "properties": {
                "osType": "Linux",
                "restartPolicy": "Always",
                "containers": [
                  {
                    "name": "web",
                    "properties": {
                      "image": "alpine:3.20",
                      "command": ["sh", "-c", "while true; do echo hello-from-web; sleep 2; done"],
                      "ports": [{"port": 8080, "protocol": "TCP"}],
                      "environmentVariables": [
                        {"name": "GREETING", "value": "hello"},
                        {"name": "API_TOKEN", "secureValue": "s3cr3t-token"}
                      ],
                      "resources": {"requests": {"cpu": 1.0, "memoryInGB": 1.0}},
                      "volumeMounts": [
                        {"name": "scratch-volume", "mountPath": "/mnt/scratch"},
                        {"name": "secret-volume", "mountPath": "/mnt/secrets", "readOnly": true}
                      ]
                    }
                  },
                  {
                    "name": "sidecar",
                    "properties": {
                      "image": "alpine:3.20",
                      "command": ["sh", "-c", "while true; do echo sidecar-alive; sleep 2; done"],
                      "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}},
                      "volumeMounts": [
                        {"name": "scratch-volume", "mountPath": "/mnt/scratch"}
                      ]
                    }
                  }
                ],
                "ipAddress": {
                  "type": "Public",
                  "dnsNameLabel": "floci-aci-test",
                  "ports": [{"port": 8080, "protocol": "TCP"}]
                },
                "volumes": [
                  {"name": "scratch-volume", "emptyDir": {}},
                  {"name": "secret-volume", "secret": {"mysecret1": "aGVsbG8tc2VjcmV0Cg=="}}
                ]
              }
            }
            """;

    /** One container that exits 0 after a short run. Used for restart-policy tests. */
    static final String RUN_TO_COMPLETION = """
            {
              "location": "eastus",
              "properties": {
                "osType": "Linux",
                "restartPolicy": "%s",
                "containers": [
                  {
                    "name": "task",
                    "properties": {
                      "image": "alpine:3.20",
                      "command": ["sh", "-c", "echo task-output; exit %d"],
                      "resources": {"requests": {"cpu": 1.0, "memoryInGB": 1.0}}
                    }
                  }
                ]
              }
            }
            """;

    /** Two containers where the second reaches the first on localhost. */
    static final String LOCALHOST_PAIR = """
            {
              "location": "eastus",
              "properties": {
                "osType": "Linux",
                "restartPolicy": "Always",
                "containers": [
                  {
                    "name": "server",
                    "properties": {
                      "image": "alpine:3.20",
                      "command": ["sh", "-c",
                        "while true; do printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 6\\r\\n\\r\\nSERVER' | nc -l -p 9000; done"],
                      "ports": [{"port": 9000, "protocol": "TCP"}],
                      "resources": {"requests": {"cpu": 1.0, "memoryInGB": 1.0}}
                    }
                  },
                  {
                    "name": "client",
                    "properties": {
                      "image": "alpine:3.20",
                      "command": ["sh", "-c",
                        "sleep 2; while true; do wget -q -O- http://localhost:9000 && echo ' <- via localhost'; sleep 2; done"],
                      "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}
                    }
                  }
                ],
                "ipAddress": {
                  "type": "Public",
                  "ports": [{"port": 9000, "protocol": "TCP"}]
                }
              }
            }
            """;

    static String groupUrl(String name)          { return BASE + "/containerGroups/" + name + API; }
    static String groupUrl(String name, String q){ return BASE + "/containerGroups/" + name + API + q; }
    static String actionUrl(String name, String a) {
        return BASE + "/containerGroups/" + name + "/" + a + API;
    }
    static String logsUrl(String group, String container) {
        return BASE + "/containerGroups/" + group + "/containers/" + container + "/logs" + API;
    }
    static String rgCollectionUrl()  { return BASE + "/containerGroups" + API; }
    static String subCollectionUrl() { return SUB_BASE + "/containerGroups" + API; }

    private ContainerInstanceFixtures() {}
}
```

### Test profiles

```java
public static class MockedProfile implements QuarkusTestProfile {
    @Override public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.container-instance.mocked", "true");
    }
}

public static class RealModeProfile implements QuarkusTestProfile {
    @Override public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci-az.services.container-instance.mocked", "false",
                "floci-az.services.container-instance.reconcile-interval-seconds", "1");
    }
}

public static class DisabledProfile implements QuarkusTestProfile {
    @Override public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.container-instance.enabled", "false");
    }
}
```

Every mocked-mode test class starts each case with

```java
@BeforeEach
void reset() {
    given().post("/_admin/reset").then().statusCode(204);
}
```

matching `VmHandlerTest` (`src/test/java/io/floci/az/services/vm/VmHandlerTest.java:62-66`).

## Routing

`src/test/java/io/floci/az/services/containerinstance/ContainerInstanceRoutingTest.java`,
`@QuarkusTest` with `MockedProfile`.

### `providerNamespaceIsClaimedExactlyOnce`

**Arrange** — inject `AzureRoutingFilter` and call the package-private
`providerRoutesInMatchOrder()` (`src/main/java/io/floci/az/core/AzureRoutingFilter.java:268`).

**Act** — filter the returned entries for key `/providers/Microsoft.ContainerInstance/`.

**Assert** — exactly one entry, whose value is `"containerinstance"`.

This is the guard against #106: a handler that forgets `routes()` fails here.

### `everyRoutingTableRowReturnsARealBody`

**Arrange** — create `demo-group` with `MINIMAL`.

**Act and assert** — one call per row of the
[routing table](container-instances.md#routing-table), each asserting a concrete body:

| Request | Expected status | Expected body assertion |
|---|---|---|
| `GET {SUB_BASE}/containerGroups?api-version=2023-05-01` | 200 | `value.name` contains `demo-group` |
| `GET {BASE}/containerGroups?api-version=2023-05-01` | 200 | `value.name` contains `demo-group` |
| `PUT {BASE}/containerGroups/demo-group?...` with `MINIMAL` | 200 | `properties.provisioningState` equals `Succeeded` |
| `GET {BASE}/containerGroups/demo-group?...` | 200 | `name` equals `demo-group`, `properties.instanceView.state` equals `Running` |
| `GET {BASE}/containerGroups/demo-group?...&$expand=instanceView` | 200 | `properties.instanceView.state` equals `Running` |
| `PATCH {BASE}/containerGroups/demo-group?...` with `{"tags":{"a":"b"}}` | 200 | `tags.a` equals `b` |
| `POST {BASE}/containerGroups/demo-group/start?...` | 204 | body is empty |
| `POST {BASE}/containerGroups/demo-group/stop?...` | 204 | body is empty |
| `POST {BASE}/containerGroups/demo-group/restart?...` | 204 | body is empty |
| `GET {BASE}/containerGroups/demo-group/containers/web/logs?...` | 200 | `content` equals `""` |
| `GET {BASE}/containerGroups/demo-group/outboundNetworkDependenciesEndpoints?...` | 200 | body equals `[]` |
| `POST {BASE}/containerGroups/demo-group/containers/web/exec?...` | 501 | `error.code` equals `NotImplemented` |
| `POST {BASE}/containerGroups/demo-group/containers/web/attach?...` | 501 | `error.code` equals `NotImplemented` |
| `GET {SUB_BASE}/locations/eastus/usages?...` | 200 | `value.size()` equals 2, `value[0].id` ends with `/usages/ContainerGroups` |
| `GET {SUB_BASE}/locations/eastus/capabilities?...` | 200 | `value[0].capabilities.maxCpu` equals `31.0` |
| `GET {SUB_BASE}/locations/eastus/cachedImages?...` | 200 | `value` is an empty array |
| `DELETE {BASE}/containerGroups/demo-group?...` | 204 | body is empty |

### `unknownPathReturns404WithTail`

**Act** — `GET {BASE}/containerGroups/demo-group/nonsense?api-version=2023-05-01`.

**Assert** — `404`, body:

```json
{
  "error": {
    "code": "ResourceNotFound",
    "message": "Unsupported Microsoft.ContainerInstance path: containerGroups/demo-group/nonsense"
  }
}
```

### `unsupportedMethodReturns405`

**Arrange** — create `demo-group` with `MINIMAL`.

**Act** — `HEAD {BASE}/containerGroups/demo-group?api-version=2023-05-01`.

**Assert** — `405`, `error.code` equals `MethodNotAllowed`, `error.message` equals
`Method not allowed`.

### `anyApiVersionIsAccepted`

**Act** — three `GET`s on `{BASE}/containerGroups/demo-group` with
`?api-version=2023-05-01`, `?api-version=2024-05-01-preview`, and `?api-version=2025-09-01`,
after creating the group.

**Assert** — all three return `200` with `name` equal to `demo-group` and byte-identical bodies.

### `missingApiVersionIsAccepted`

**Act** — `GET {BASE}/containerGroups/demo-group` with no query string.

**Assert** — `200`, `name` equals `demo-group`.

### `disabledServiceFallsThroughToGenericArm`

`@TestProfile(DisabledProfile.class)`.

**Act** — `GET {BASE}/containerGroups/demo-group?api-version=2023-05-01`.

**Assert** — the status is not `200` and the body does **not** contain
`Microsoft.ContainerInstance/containerGroups` as a `type`. The generic ARM handler answers; the
exact status is whatever `ArmHandler` returns for an unknown provider resource and is asserted
as `equalTo(404)`.

## CRUD

`src/test/java/io/floci/az/services/containerinstance/ContainerInstanceHandlerTest.java`,
`@QuarkusTest` with `MockedProfile`.

### `createReturns201WithSucceededAndEchoedProperties`

**Act** — `PUT` `{groupUrl("demo-group")}` with `FULL`.

**Assert** — `201`, and:

```
name                                          == "demo-group"
type                                          == "Microsoft.ContainerInstance/containerGroups"
id                                            == "/subscriptions/test-sub-aci/resourceGroups/test-rg-aci/providers/Microsoft.ContainerInstance/containerGroups/demo-group"
location                                      == "eastus"
tags.env                                      == "test"
properties.provisioningState                  == "Succeeded"
properties.osType                             == "Linux"
properties.restartPolicy                      == "Always"
properties.containers.size()                  == 2
properties.containers[0].name                 == "web"
properties.containers[0].properties.image     == "alpine:3.20"
properties.containers[0].properties.resources.requests.memoryInGB == 1.0f
properties.ipAddress.type                     == "Public"
properties.ipAddress.ip                       == "127.0.0.1"
properties.ipAddress.fqdn                     == "floci-aci-test.eastus.azurecontainer.io"
properties.ipAddress.ports[0].port            == 8080
properties.instanceView.state                 == "Running"
properties.containers[0].properties.instanceView.currentState.state == "Running"
properties.containers[0].properties.instanceView.restartCount        == 0
```

### `createWithoutLocationDefaultsToEastus`

**Act** — `PUT` with `MINIMAL` minus its `"location"` line.

**Assert** — `201`, `location` equals `eastus`.

### `updateReturns200AndKeepsIdentity`

**Arrange** — create `demo-group` with `MINIMAL`; capture nothing (identity is not on the wire).

**Act** — `PUT` the same URL again with `FULL`.

**Assert** — `200`, `properties.containers.size()` equals 2, `tags.env` equals `test`.

### `getUnknownGroupReturns404`

**Act** — `GET {groupUrl("no-such-group")}`.

**Assert** — `404`, body exactly:

```json
{
  "error": {
    "code": "ResourceNotFound",
    "message": "The Resource 'Microsoft.ContainerInstance/containerGroups/no-such-group' under resource group 'test-rg-aci' was not found."
  }
}
```

### `getIncludesInstanceViewWithoutExpand`

**Arrange** — create `demo-group` with `FULL`.

**Act** — `GET {groupUrl("demo-group")}`.

**Assert** — `200`, `properties.instanceView.state` equals `Running` and
`properties.containers[0].properties.instanceView.currentState.state` equals `Running`.

`ContainerGroups_Get` has no `$expand` parameter in the `2023-05-01` contract, so the instance
view is part of every single-resource Get — see
[the correction in the resource model](container-instances-resource-model.md#instanceview-and-expand).
`listOmitsInstanceView` below is the counterpart that still holds: a *list* response omits it.

### `getWithExpandInstanceViewIncludesState`

**Act** — `GET {groupUrl("demo-group", "&$expand=instanceView")}`.

**Assert** — `200`, `properties.instanceView.state` equals `Running`,
`properties.containers[0].properties.instanceView.currentState.state` equals `Running`.

### `expandIsCaseInsensitive`

**Act** — `GET {groupUrl("demo-group", "&$expand=InstanceView")}`.

**Assert** — `200`, `properties.instanceView.state` equals `Running`.

### `patchReplacesTags`

**Arrange** — create `demo-group` with `FULL` (`tags.env == "test"`).

**Act** — `PATCH {groupUrl("demo-group")}` with `{"tags":{"owner":"floci"}}`.

**Assert** — `200`, `tags.owner` equals `floci`, `tags.env` is null.

### `patchIgnoresProperties`

**Act** — `PATCH {groupUrl("demo-group")}` with
`{"tags":{"a":"b"},"properties":{"restartPolicy":"Never"}}`.

**Assert** — `200`, `tags.a` equals `b`, `properties.restartPolicy` equals `Always`.

### `patchUnknownGroupReturns404`

**Act** — `PATCH {groupUrl("no-such-group")}` with `{"tags":{}}`.

**Assert** — `404`, `error.code` equals `ResourceNotFound`.

### `deleteReturns204AndIsIdempotent`

**Arrange** — create `demo-group` with `MINIMAL`.

**Act and assert** — `DELETE {groupUrl("demo-group")}` returns `204` with an empty body;
`GET {groupUrl("demo-group")}` returns `404`; a second `DELETE` returns `204`.

### `listInResourceGroupReturnsOnlyThatGroup`

**Arrange** — create `group-a` and `group-b` in `test-rg-aci`; create `group-c` under a second
resource group `other-rg` by using its URL.

**Act** — `GET {rgCollectionUrl()}`.

**Assert** — `200`, `value.name` contains `group-a` and `group-b` and does not contain
`group-c`.

### `listInResourceGroupIsCaseInsensitive`

**Arrange** — create `group-a` under `test-rg-aci`.

**Act** — `GET` the collection URL with the resource group spelled `TEST-RG-ACI`.

**Assert** — `200`, `value.name` contains `group-a`.

### `listInSubscriptionReturnsAllGroups`

**Act** — `GET {subCollectionUrl()}`.

**Assert** — `200`, `value.name` contains `group-a`, `group-b`, and `group-c`.

### `listOmitsInstanceView`

**Act** — `GET {rgCollectionUrl()}`.

**Assert** — `200`, `value[0].properties.instanceView` is null.

### `emptyListReturnsEmptyValueArray`

**Act** — after `_admin/reset`, `GET {rgCollectionUrl()}`.

**Assert** — `200`, body exactly `{"value":[]}`.

### `outboundNetworkDependenciesReturnsEmptyArray`

**Assert** — `200`, body exactly `[]`.

### `usagesReflectStoredGroups`

**Arrange** — create `demo-group` with `FULL` (cpu 1.0 + 0.5 = 1.5).

**Act** — `GET {SUB_BASE}/locations/eastus/usages?api-version=2023-05-01`.

**Assert** — `200`, and:

```
value.size()                == 2
value[0].id                 == ".../locations/eastus/usages/ContainerGroups"
value[0].currentValue       == 1
value[0].limit              == 100
value[0].unit               == "Count"
value[0].name.value         == "ContainerGroups"
value[1].id                 == ".../locations/eastus/usages/StandardCores"
value[1].currentValue       == 2
value[1].limit              == 100
```

### `capabilitiesReportAzureMaximums`

**Assert** — `200`, `value[0].resourceType` equals `containerGroups`, `value[0].osType` equals
`Linux`, `value[0].location` equals `eastus`, `value[0].ipAddressType` equals `Public`,
`value[0].gpu` equals `None`, `value[0].capabilities.maxCpu` equals `31.0`,
`value[0].capabilities.maxMemoryInGB` equals `240.0`,
`value[0].capabilities.maxGpuCount` equals `0.0`.

### `cachedImagesIsEmpty`

**Assert** — `200`, body exactly `{"value":[]}`.

## Validation

`src/test/java/io/floci/az/services/containerinstance/ContainerInstanceValidationTest.java`,
`@QuarkusTest` with `MockedProfile`. One case per rule V1–V50.

Every case sends a `PUT` to `{groupUrl(name)}` and asserts the exact `error.code`,
`error.target`, and `error.message`. The table gives the mutation applied to `MINIMAL` (or the
URL, for V1) and the full expected error body.

| Test method | Rule | Mutation | Expected `code` / `target` / `message` |
|---|---|---|---|
| `groupNameWithUnderscoreRejected` | V1 | URL name `Demo_Group` | `InvalidResourceName` / `containerGroupName` / `The Resource Name 'Demo_Group' is invalid. Container group names must be 1 to 63 characters long, contain only lowercase letters, numbers and hyphens, must not start or end with a hyphen, and must not contain consecutive hyphens.` |
| `groupNameWithConsecutiveHyphensRejected` | V1 | URL name `demo--group` | as above with `'demo--group'` |
| `groupNameWithTrailingHyphenRejected` | V1 | URL name `demo-` | as above with `'demo-'` |
| `groupNameTooLongRejected` | V1 | URL name of 64 `a` characters | as above with that name |
| `bodyThatIsNotAnObjectRejected` | V2 | body `[]` | `InvalidRequestContent` / *(absent)* / `The request content was invalid and could not be deserialized: the request body must be a JSON object.` |
| `missingPropertiesRejected` | V3 | remove `properties` | `InvalidRequestContent` / `properties` / `The request content was invalid and could not be deserialized: required property 'properties' is missing or of the wrong type.` |
| `missingOsTypeRejected` | V4 | remove `properties.osType` | `InvalidRequestContent` / `properties.osType` / `...required property 'properties.osType' is missing or of the wrong type.` |
| `invalidOsTypeRejected` | V5 | `"osType": "Plan9"` | `InvalidParameter` / `properties.osType` / `The value 'Plan9' provided for 'properties.osType' is not valid. Valid values are 'Linux' and 'Windows'.` |
| `missingContainersRejected` | V6 | remove `properties.containers` | `InvalidRequestContent` / `properties.containers` |
| `emptyContainersRejected` | V6 | `"containers": []` | `InvalidRequestContent` / `properties.containers` |
| `tooManyContainersRejected` | V7 | 61 containers named `c1`…`c61` | `InvalidParameter` / `properties.containers` / `The value '61' provided for 'properties.containers' is not valid. A container group may contain at most 60 containers.` |
| `missingContainerNameRejected` | V8 | drop `name` from `containers[0]` | `InvalidRequestContent` / `properties.containers[0].name` |
| `uppercaseContainerNameRejected` | V9 | `"name": "Web"` | `InvalidParameter` / `properties.containers[0].name` / `The value 'Web' provided for 'properties.containers[0].name' is not valid. Container names must be 1 to 63 characters long and contain only lowercase letters, numbers and hyphens, with a hyphen allowed anywhere except the first or last character.` |
| `duplicateContainerNameRejected` | V10 | two containers both named `web` | `InvalidParameter` / `properties.containers[1].name` / `...Container names must be unique within a container group.` |
| `missingContainerPropertiesRejected` | V11 | drop `properties` from `containers[0]` | `InvalidRequestContent` / `properties.containers[0].properties` |
| `missingImageRejected` | V12 | drop `image` | `InvalidRequestContent` / `properties.containers[0].properties.image` |
| `blankImageRejected` | V12 | `"image": "  "` | `InvalidRequestContent` / `properties.containers[0].properties.image` |
| `missingResourcesRejected` | V13 | drop `resources` | `InvalidRequestContent` / `properties.containers[0].properties.resources.requests` |
| `missingCpuRejected` | V13 | `"requests": {"memoryInGB": 1.0}` | `InvalidRequestContent` / `properties.containers[0].properties.resources.requests` |
| `zeroCpuRejected` | V14 | `"cpu": 0` | `InvalidParameter` / `properties.containers[0].properties.resources.requests.cpu` / `The value '0.0' provided for '...cpu' is not valid. The value must be greater than 0.` |
| `negativeMemoryRejected` | V14 | `"memoryInGB": -1` | `InvalidParameter` / `...requests.memoryInGB` / `...The value must be greater than 0.` |
| `groupCpuSumOverLimitRejected` | V15 | two containers with `cpu: 16` each | `InvalidParameter` / `properties.containers` / `The value '32.0' provided for 'properties.containers' is not valid. The total CPU requested by a container group may not exceed 31.` |
| `groupMemorySumOverLimitRejected` | V16 | one container with `memoryInGB: 241` | `InvalidParameter` / `properties.containers` / `...The total memory requested by a container group may not exceed 240 GB.` |
| `limitBelowRequestRejected` | V17 | `requests.cpu 2`, `limits.cpu 1` | `InvalidParameter` / `properties.containers[0].properties.resources.limits.cpu` / `...A resource limit must be greater than or equal to the corresponding resource request.` |
| `invalidGpuSkuRejected` | V18 | `"gpu": {"count": 1, "sku": "A100"}` | `InvalidParameter` / `properties.containers[0].properties.resources.requests.gpu.sku` / `...Valid values are 'K80', 'P100' and 'V100'.` |
| `missingGpuCountRejected` | V18 | `"gpu": {"sku": "K80"}` | `InvalidRequestContent` / `properties.containers[0].properties.resources.requests.gpu.count` |
| `containerPortOutOfRangeRejected` | V19 | `"ports": [{"port": 70000}]` | `InvalidParameter` / `properties.containers[0].properties.ports[0].port` / `...A port number must be between 1 and 65535.` |
| `containerPortZeroRejected` | V19 | `"ports": [{"port": 0}]` | as above with `'0'` |
| `invalidPortProtocolRejected` | V20 | `"ports": [{"port": 80, "protocol": "SCTP"}]` | `InvalidParameter` / `properties.containers[0].properties.ports[0].protocol` / `...Valid values are 'TCP' and 'UDP'.` |
| `envVarNameWithHyphenRejected` | V21 | `{"name": "MY-VAR", "value": "x"}` | `InvalidParameter` / `properties.containers[0].properties.environmentVariables[0].name` / `...Environment variable names must be 1 to 63 characters long and contain only letters, numbers and underscores, with an underscore allowed anywhere except the first or last character.` |
| `envVarWithBothValueAndSecureValueRejected` | V22 | `{"name": "T", "value": "a", "secureValue": "b"}` | `InvalidParameter` / `properties.containers[0].properties.environmentVariables[0]` / `...An environment variable may set either 'value' or 'secureValue', not both.` |
| `missingVolumeMountPathRejected` | V23 | `"volumeMounts": [{"name": "v"}]` | `InvalidRequestContent` / `properties.containers[0].properties.volumeMounts[0].mountPath` |
| `relativeMountPathRejected` | V24 | `"mountPath": "mnt/data"` | `InvalidParameter` / `properties.containers[0].properties.volumeMounts[0].mountPath` / `...A mount path must be absolute and must not contain a colon.` |
| `mountPathWithColonRejected` | V24 | `"mountPath": "/mnt:data"` | as above |
| `volumeMountWithNoMatchingVolumeRejected` | V25 | mount `ghost-volume`, no `volumes` entry | `InvalidParameter` / `properties.containers[0].properties.volumeMounts[0].name` / `...No volume with that name is declared in properties.volumes.` |
| `tooManyVolumesRejected` | V26 | 21 `emptyDir` volumes | `InvalidParameter` / `properties.volumes` / `...A container group may contain at most 20 volumes.` |
| `shortVolumeNameRejected` | V27 | volume named `abc` | `InvalidParameter` / `properties.volumes[0].name` / `...Volume names must be 5 to 63 characters long, contain only lowercase letters, numbers and hyphens, must not start or end with a hyphen, must not contain consecutive hyphens, and must be unique within a container group.` |
| `duplicateVolumeNameRejected` | V27 | two volumes named `scratch-volume` | as above, target `properties.volumes[1].name` |
| `volumeWithNoKindRejected` | V28 | `{"name": "scratch-volume"}` | `InvalidParameter` / `properties.volumes[0]` / `...Exactly one of 'azureFile', 'emptyDir', 'secret' or 'gitRepo' must be specified.` |
| `volumeWithTwoKindsRejected` | V28 | both `emptyDir` and `secret` | as above |
| `gitRepoVolumeRejected` | V29 | `{"name": "repo-volume", "gitRepo": {"repository": "https://example.com/r.git"}}` | `NotSupported` / `properties.volumes[0].gitRepo` / `Volume type 'gitRepo' is not supported by floci-az. Use 'emptyDir', 'secret' or 'azureFile' instead.` |
| `nonBase64SecretRejected` | V30 | `"secret": {"k": "not base64!!"}` | `InvalidParameter` / `properties.volumes[0].secret` / `...Secret volume values must be Base64-encoded and secret keys may contain only letters, numbers, dot, underscore and hyphen.` |
| `secretKeyWithSlashRejected` | V30 | `"secret": {"a/b": "aGk="}` | as above |
| `azureFileWithoutShareNameRejected` | V31 | `"azureFile": {"storageAccountName": "sa"}` | `InvalidRequestContent` / `properties.volumes[0].azureFile.shareName` |
| `invalidRestartPolicyRejected` | V32 | `"restartPolicy": "Sometimes"` | `InvalidParameter` / `properties.restartPolicy` / `The value 'Sometimes' provided for 'properties.restartPolicy' is not valid. Valid values are 'Always', 'OnFailure' and 'Never'.` |
| `invalidSkuRejected` | V33 | `"sku": "Premium"` | `InvalidParameter` / `properties.sku` / `...Valid values are 'Standard', 'Dedicated' and 'Confidential'.` |
| `invalidPriorityRejected` | V34 | `"priority": "Low"` | `InvalidParameter` / `properties.priority` / `...Valid values are 'Regular' and 'Spot'.` |
| `missingIpAddressTypeRejected` | V35 | `"ipAddress": {"ports":[{"port":80}]}` | `InvalidRequestContent` / `properties.ipAddress.type` |
| `invalidIpAddressTypeRejected` | V35 | `"type": "Internal"` | `InvalidParameter` / `properties.ipAddress.type` / `...Valid values are 'Public' and 'Private'.` |
| `ipAddressWithoutPortsRejected` | V36 | `"ipAddress": {"type": "Public"}` | `InvalidRequestContent` / `properties.ipAddress.ports` |
| `tooManyGroupPortsRejected` | V37 | 6 ports | `InvalidParameter` / `properties.ipAddress.ports` / `...A container group may expose at most 5 ports, and each port number may appear only once.` |
| `duplicateGroupPortRejected` | V37 | ports `80`, `80` | as above |
| `groupPortOutOfRangeRejected` | V38 | `{"port": 99999}` | `InvalidParameter` / `properties.ipAddress.ports[0].port` / `...A port number must be between 1 and 65535.` |
| `groupPortNotExposedByAnyContainerRejected` | V39 | group port `8081`, container port `8080` | `InvalidParameter` / `properties.ipAddress.ports[0].port` / `The value '8081' provided for 'properties.ipAddress.ports[0].port' is not valid. The port is not exposed by any container in the container group.` |
| `shortDnsNameLabelRejected` | V40 | `"dnsNameLabel": "abcd"` | `InvalidParameter` / `properties.ipAddress.dnsNameLabel` / `...A DNS name label must be 5 to 63 characters long and contain only letters, numbers and hyphens, with a hyphen allowed anywhere except the first or last character.` |
| `dnsNameLabelWithUnderscoreRejected` | V40 | `"dnsNameLabel": "my_label"` | as above |
| `invalidDomainNameLabelScopeRejected` | V41 | `"autoGeneratedDomainNameLabelScope": "Global"` | `InvalidParameter` / `properties.ipAddress.autoGeneratedDomainNameLabelScope` / `...Valid values are 'Unsecure', 'TenantReuse', 'SubscriptionReuse', 'ResourceGroupReuse' and 'Noreuse'.` |
| `dnsConfigWithoutNameServersRejected` | V42 | `"dnsConfig": {"options": "ndots:2"}` | `InvalidRequestContent` / `properties.dnsConfig.nameServers` |
| `diagnosticsWithoutWorkspaceKeyRejected` | V43 | `"diagnostics": {"logAnalytics": {"workspaceId": "w"}}` | `InvalidRequestContent` / `properties.diagnostics.logAnalytics.workspaceKey` |
| `encryptionWithoutKeyVersionRejected` | V44 | `"encryptionProperties": {"vaultBaseUrl": "u", "keyName": "k"}` | `InvalidRequestContent` / `properties.encryptionProperties.keyVersion` |
| `subnetIdWithoutIdRejected` | V45 | `"subnetIds": [{"name": "s"}]` | `InvalidRequestContent` / `properties.subnetIds[0].id` |
| `invalidIdentityTypeRejected` | V46 | `"identity": {"type": "Managed"}` | `InvalidParameter` / `identity.type` / `...Valid values are 'SystemAssigned', 'UserAssigned', 'SystemAssigned, UserAssigned' and 'None'.` |
| `extensionWithoutNameRejected` | V47 | `"extensions": [{}]` | `InvalidRequestContent` / `properties.extensions[0].name` |
| `registryServerWithSchemeRejected` | V48 | `"imageRegistryCredentials": [{"server": "https://r.io"}]` | `InvalidParameter` / `properties.imageRegistryCredentials[0].server` / `...A registry server must be a host name without a scheme.` |
| `registryCredentialWithoutServerRejected` | V48 | `[{"username": "u"}]` | `InvalidRequestContent` / `properties.imageRegistryCredentials[0].server` |
| `negativeRunAsUserRejected` | V49 | `"securityContext": {"runAsUser": -1}` | `InvalidParameter` / `properties.containers[0].properties.securityContext.runAsUser` / `...The value must be greater than or equal to 0.` |
| `probePortOutOfRangeRejected` | V50 | `"livenessProbe": {"httpGet": {"port": 0}}` | `InvalidParameter` / `properties.containers[0].properties.livenessProbe.httpGet.port` / `...A port number must be between 1 and 65535.` |
| `probeSchemeRejected` | V50 | `"livenessProbe": {"httpGet": {"port": 80, "scheme": "ftp"}}` | `InvalidParameter` / `properties.containers[0].properties.livenessProbe.httpGet.scheme` / `...Valid values are 'http' and 'https'.` |
| `probeThresholdBelowOneRejected` | V50 | `"readinessProbe": {"periodSeconds": 0}` | `InvalidParameter` / `properties.containers[0].properties.readinessProbe.periodSeconds` / `...The value must be greater than or equal to 1.` |
| `probeWithoutHttpGetPortRejected` | V50 | `"livenessProbe": {"httpGet": {"path": "/"}}` | `InvalidRequestContent` / `properties.containers[0].properties.livenessProbe.httpGet.port` |

Two further cases assert catalog rows that no `PUT` reaches:

| Test method | Case | Expected |
|---|---|---|
| `logsForUnknownContainerReturns404` | Create `demo-group` with `MINIMAL`, then `GET {logsUrl("demo-group", "ghost")}` | `404` / `ResourceNotFound` / `containerName` / `The container 'ghost' was not found in container group 'demo-group' under resource group 'test-rg-aci'.` |
| `invalidTailParameterRejected` | `GET {logsUrl("demo-group", "web")}&tail=0` | `400` / `InvalidParameter` / `tail` / `The value '0' provided for 'tail' is not valid. The value must be greater than or equal to 1.` |

And one negative case proving fail-fast ordering:

### `firstFailingRuleWins`

**Act** — `PUT` a body that violates V5 (`"osType": "Plan9"`) **and** V32
(`"restartPolicy": "Sometimes"`).

**Assert** — `400`, `error.target` equals `properties.osType` — the lower-numbered rule — and
the response contains no `restartPolicy` text.

## Actions

Still in `ContainerInstanceHandlerTest`, `MockedProfile`.

### `stopReturns204AndSetsStoppedState`

**Arrange** — create `demo-group` with `FULL`.

**Act** — `POST {actionUrl("demo-group", "stop")}`.

**Assert** — `204`; then `GET {groupUrl("demo-group", "&$expand=instanceView")}` returns `200`
with `properties.instanceView.state` equal to `Stopped`,
`properties.containers[0].properties.instanceView.currentState.state` equal to `Terminated`,
and `...currentState.exitCode` equal to `0`.

### `startAfterStopReturnsRunning`

**Act** — `POST {actionUrl("demo-group", "start")}`.

**Assert** — `204`; `instanceView.state` is `Running`, every container `currentState.state` is
`Running`, and `restartCount` is unchanged at `0`.

### `restartIncrementsRestartCount`

**Act** — `POST {actionUrl("demo-group", "restart")}`.

**Assert** — `204`; `properties.containers[0].properties.instanceView.restartCount` equals `1`;
a second restart makes it `2`.

### `actionsOnUnknownGroupReturn404`

**Act** — `POST` each of `start`, `stop`, `restart` on `no-such-group`.

**Assert** — all three return `404` with `error.code` equal to `ResourceNotFound`.

### `actionsEmitNoAsyncOperationHeaders`

**Act** — `POST {actionUrl("demo-group", "start")}`.

**Assert** — `204`, and the response has no `Azure-AsyncOperation`, no `Location`, and no
`Retry-After` header. This locks in [deviation D1](container-instances.md#deviations-register).

### `putEmitsNoAsyncOperationHeaders`

**Assert** — the `201` from a create has no `Azure-AsyncOperation` and no `Location` header, and
`properties.provisioningState` is already `Succeeded`.

### `execReturns501`

**Assert** — `501`, body exactly:

```json
{
  "error": {
    "code": "NotImplemented",
    "message": "Container exec is not implemented by floci-az: the emulator serves no websocket data plane."
  }
}
```

### `attachReturns501`

**Assert** — `501`, same shape with `Container attach is not implemented by floci-az: the emulator serves no websocket data plane.`

## Logs

### `logsInMockedModeReturnEmptyContent`

`MockedProfile`.

**Arrange** — create `demo-group` with `FULL`.

**Act** — `GET {logsUrl("demo-group", "web")}`.

**Assert** — `200`, body exactly `{"content":""}`.

### `logsAcceptTailAndTimestampsQueryParameters`

**Act** — `GET {logsUrl("demo-group", "web")}&tail=5&timestamps=true`.

**Assert** — `200`, body exactly `{"content":""}` (mocked mode ignores both).

### `logsForUnknownGroupReturn404`

**Act** — `GET {logsUrl("no-such-group", "web")}`.

**Assert** — `404`, `error.code` equals `ResourceNotFound`, message is the group-not-found
template.

## State machine

`ContainerInstanceDockerTest`, `RealModeProfile`, Docker required, ordered with
`@TestMethodOrder(MethodOrderer.OrderAnnotation.class)`. The `alpine:3.20` image is pulled on
first run, so every state assertion polls with a 120-second timeout and a 1-second interval.

### `restartPolicyAlwaysRestartsAfterCleanExit`

**Arrange** — `PUT` `RUN_TO_COMPLETION.formatted("Always", 0)` as `always-group`.

**Act** — poll `GET {groupUrl("always-group", "&$expand=instanceView")}` until
`properties.containers[0].properties.instanceView.restartCount` is at least `2`.

**Assert** — `restartCount >= 2`; `currentState.state` is `Running` or `Waiting`;
`previousState.state` is `Terminated` with `exitCode` `0` and `detailStatus` `Completed`;
`properties.instanceView.state` is `Running`.

### `restartPolicyOnFailureDoesNotRestartAfterCleanExit`

**Arrange** — `PUT` `RUN_TO_COMPLETION.formatted("OnFailure", 0)` as `onfailure-zero-group`.

**Act** — poll until `currentState.state` is `Terminated`, then wait 5 further seconds.

**Assert** — `restartCount` equals `0`; `currentState` equals

```json
{
  "state": "Terminated",
  "startTime": "<any RFC 3339>",
  "exitCode": 0,
  "finishTime": "<any RFC 3339>",
  "detailStatus": "Completed"
}
```

and `properties.instanceView.state` equals `Succeeded`.

### `restartPolicyOnFailureRestartsAfterNonZeroExit`

**Arrange** — `PUT` `RUN_TO_COMPLETION.formatted("OnFailure", 7)` as `onfailure-fail-group`.

**Act** — poll until `restartCount` is at least `1`.

**Assert** — `previousState.exitCode` equals `7`, `previousState.detailStatus` equals `Error`,
`properties.instanceView.state` equals `Running`.

### `restartPolicyNeverDoesNotRestartAfterCleanExit`

**Arrange** — `PUT` `RUN_TO_COMPLETION.formatted("Never", 0)` as `never-zero-group`.

**Act** — poll until `currentState.state` is `Terminated`, wait 5 further seconds.

**Assert** — `restartCount` equals `0`, `exitCode` equals `0`, `detailStatus` equals
`Completed`, `properties.instanceView.state` equals `Succeeded`.

### `restartPolicyNeverDoesNotRestartAfterNonZeroExit`

**Arrange** — `PUT` `RUN_TO_COMPLETION.formatted("Never", 9)` as `never-fail-group`.

**Act** — poll until `currentState.state` is `Terminated`, wait 5 further seconds.

**Assert** — `restartCount` equals `0`, `exitCode` equals `9`, `detailStatus` equals `Error`,
`properties.instanceView.state` equals `Failed`. This locks in
[deviation D12](container-instances.md#deviations-register).

### `imagePullFailureProducesFailedGroupWithEvent`

**Arrange** — `PUT` `MINIMAL` with `"image": "floci-az-nonexistent/no-such-image:0.0.0"` as
`badimage-group`.

**Assert** — the `PUT` returns `201` (not `5xx`), `properties.provisioningState` equals
`Failed`, and `GET ...&$expand=instanceView` shows
`properties.instanceView.state` equal to `Failed` and
`properties.containers[0].properties.instanceView.events` containing an entry with
`name` equal to `Failed`, `type` equal to `Warning`, and `message` starting with
`Failed to pull image "floci-az-nonexistent/no-such-image:0.0.0": `.

### `infraContainerDeathTriggersRepair`

**Arrange** — `PUT` `FULL` as `repair-group`; poll until `instanceView.state` is `Running`;
read the infra container id by listing Docker containers with label
`floci_aci_group=repair-group` and `floci_aci_role=infra` through an injected
`ContainerLifecycleManager.findByName(...)` on the expected name.

**Act** — kill the infra container out of band with
`lifecycleManager.getDockerClient().killContainerCmd(infraId).exec()`.

**Assert** — within 60 seconds, `GET ...&$expand=instanceView` shows
`properties.instanceView.state` equal to `Running`,
`properties.instanceView.events` containing an entry with `name` equal to `InfraRestarted`, and
every container's `restartCount` at least `1`.

### `sharedNetworkNamespaceLetsContainersReachEachOtherOnLocalhost`

**Arrange** — `PUT` `LOCALHOST_PAIR` as `localhost-group`; poll until `instanceView.state` is
`Running`.

**Act** — poll `GET {logsUrl("localhost-group", "client")}&tail=20` until non-empty, for at most
60 seconds.

**Assert** — `content` contains `SERVER <- via localhost`.

This is the regression test for the entire pod design.

### `groupPortIsReachableFromTheHost`

**Arrange** — `localhost-group` from the previous test; read the host port from the
`PortMapped` event message in `properties.instanceView.events`, parsing the integer after
`published on host port `.

**Act** — open a `java.net.Socket("127.0.0.1", hostPort)`, write
`GET / HTTP/1.0\r\n\r\n`, and read the response.

**Assert** — the response body contains `SERVER`.

### `stopThenStartPreservesReachability`

**Arrange** — `localhost-group`, running and reachable.

**Act** — `POST .../stop`, wait for `instanceView.state` to be `Stopped`, `POST .../start`, wait
for `Running`.

**Assert** — the same host port is still reachable and still returns `SERVER`. This is the
regression test for the [ordering rules](container-instances-runtime.md#ordering-rules-restated):
a naive restart strands the members in a dead namespace and this test fails.

### `emptyDirVolumeIsSharedBetweenContainers`

**Arrange** — `PUT` a group with two containers mounting one `emptyDir` at `/mnt/scratch`:
the first runs `sh -c "echo shared-payload > /mnt/scratch/f; sleep 300"`, the second runs
`sh -c "sleep 5; cat /mnt/scratch/f; sleep 300"`.

**Act** — poll `GET .../containers/reader/logs`.

**Assert** — `content` contains `shared-payload`.

### `secretVolumeIsReadableAndReadOnly`

**Arrange** — `PUT` a group with one container mounting a `secret` volume containing
`{"mysecret1": "aGVsbG8tc2VjcmV0Cg=="}` (Base64 of `hello-secret\n`) at `/mnt/secrets`,
running `sh -c "cat /mnt/secrets/mysecret1; touch /mnt/secrets/w 2>&1 || echo READONLY; sleep 300"`.

**Act** — poll the container's logs.

**Assert** — `content` contains `hello-secret` and contains `READONLY`.

### `secureEnvironmentVariableReachesTheContainer`

**Arrange** — `PUT` a group with `{"name": "API_TOKEN", "secureValue": "s3cr3t-token"}` and
command `sh -c "echo token-is-$API_TOKEN; sleep 300"`.

**Act** — poll the container's logs.

**Assert** — `content` contains `token-is-s3cr3t-token`.

### `logsReturnRealOutputWithTailAndTimestamps`

**Arrange** — `PUT` a group whose container runs
`sh -c "for i in 1 2 3 4 5; do echo out-$i; echo err-$i 1>&2; sleep 0.2; done; sleep 300"`.

**Act and assert**:

| Request | Assertion |
|---|---|
| `GET .../logs` | `content` contains all ten of `out-1`…`out-5` and `err-1`…`err-5` |
| `GET .../logs&tail=3` | `content` has exactly 3 newline-terminated lines |
| `GET .../logs&tail=2&timestamps=true` | every line matches `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z .*$` |

### `logsOfATerminatedContainerAreStillAvailable`

**Arrange** — `RUN_TO_COMPLETION.formatted("Never", 0)` with command
`sh -c "echo task-output; exit 0"`.

**Act** — poll until `currentState.state` is `Terminated`, then `GET .../logs`.

**Assert** — `content` contains `task-output`.

### `logsAreBoundedForALoudContainer`

**Arrange** — a `RealModeProfile` variant overriding
`floci-az.services.container-instance.log-max-bytes` to `1024`; container command
`sh -c "yes floci-az-log-line | head -c 5000000; sleep 300"`.

**Act** — `GET .../logs`.

**Assert** — `content.length()` is at most `1024`, and the request completes within 30 seconds.
This is the regression test for [bounded log retrieval](container-instances-runtime.md#bounded-log-retrieval).

### `deleteRemovesEveryDockerResource`

**Arrange** — `FULL` as `cleanup-group`, running.

**Act** — `DELETE {groupUrl("cleanup-group")}`.

**Assert** — `204`; within 30 seconds
`lifecycleManager.findByName("floci-az-aci-" + groupId + "-infra")` is empty and
`lifecycleManager.findByName("floci-az-aci-" + groupId + "-web")` is empty. `groupId` is read
from Docker labels before the delete.

## Allocation and identity stability

### `hostPortIsReusedWhenTheAzurePortIsFree`

`RealModeProfile`.

**Arrange** — `PUT` a group with a single group port `18099`, which is above 1023 and free.

**Assert** — the `PortMapped` event message is
`Container group port 18099 published on host port 18099`.

### `hostPortFallsBackToTheConfiguredRangeForPrivilegedPorts`

**Arrange** — `PUT` a group with group port `80`.

**Assert** — the `PortMapped` event message matches
`^Container group port 80 published on host port 85\d\d$` — a port from the default
`8500`–`8599` range.

### `hostPortIsReleasedOnDelete`

**Arrange** — create a group with group port `80`, read its host port from the `PortMapped`
event, delete the group.

**Act** — create a second group with group port `80`.

**Assert** — the second group's host port equals the first group's host port. The allocator
handed the same port back because `release` was called.

### `groupIdIsStableAcrossUpdatesAndRestarts`

`RealModeProfile`.

**Arrange** — `PUT` `MINIMAL` as `stable-group`; read the Docker container name via
`findByName` probing, or read `floci_aci_group_id` from the container's labels.

**Act** — `PUT` `FULL` to the same URL, then `POST .../restart`.

**Assert** — the infra container name is unchanged across both operations.

### `identityPrincipalIdIsStableAcrossGets`

`MockedProfile`.

**Arrange** — `PUT` a body with `"identity": {"type": "SystemAssigned"}`.

**Act** — `GET` the group three times.

**Assert** — `identity.principalId` is a non-empty UUID string and is identical in all three
responses; `identity.tenantId` equals `00000000-0000-0000-0000-000000000002`.

## Secret safety

`ContainerInstanceSecretSafetyTest`, `@QuarkusTest` with `MockedProfile` unless noted.

### `secureValueIsNeverReturnedInAnyResponse`

**Arrange** — `PUT` `FULL`, which contains `"secureValue": "s3cr3t-token"`.

**Act** — collect the raw response bodies of: the `PUT`, `GET`, `GET` with
`$expand=instanceView`, the resource-group list, the subscription list, and `PATCH`.

**Assert** — none of the six strings contains `s3cr3t-token`; each contains
`"name":"API_TOKEN"` with no adjacent `value` or `secureValue` key.

### `secretVolumeContentsAreNeverReturned`

**Assert** — every response body renders the secret volume as `"secret":{}` and contains
neither `aGVsbG8tc2VjcmV0Cg==` nor `hello-secret`.

### `registryPasswordIsNeverReturned`

**Arrange** — `PUT` a body whose `imageRegistryCredentials` is
`[{"server": "myregistry.example.com", "username": "u", "password": "p4ssw0rd"}]`.

**Assert** — the `GET` body contains `myregistry.example.com` and `"username":"u"` and does not
contain `p4ssw0rd` and does not contain the key `password`.

### `workspaceKeyIsNeverReturned`

**Arrange** — `PUT` with
`"diagnostics": {"logAnalytics": {"workspaceId": "w-id", "workspaceKey": "w-key-secret"}}`.

**Assert** — the `GET` body contains `w-id` and does not contain `w-key-secret`.

### `storageAccountKeyIsNeverReturned`

**Arrange** — `PUT` with an `azureFile` volume whose `storageAccountKey` is `sa-key-secret`.

**Assert** — the `GET` body contains the `shareName` and `storageAccountName` and does not
contain `sa-key-secret`.

### `secretsAreNeverWrittenToPersistentStorage`

`@TestProfile` overriding `floci-az.storage.mode` to `persistent` and
`floci-az.storage.persistent-path` to a JUnit `@TempDir`.

**Arrange** — `PUT` `FULL` plus registry credentials, diagnostics, and an `azureFile` key.

**Act** — force a flush by calling `POST /_admin/reset`? No — instead read
`{tempDir}/containerinstance.json` directly after the `PUT` (the `persistent` backend writes
through on `put`).

**Assert** — the file exists and its contents contain none of `s3cr3t-token`,
`aGVsbG8tc2VjcmV0Cg==`, `p4ssw0rd`, `w-key-secret`, `sa-key-secret`.

### `secretsAreNeverLogged`

**Arrange** — attach a JBoss Logging handler capturing every record emitted during the test, or
run with `quarkus.log.file.enable=true` into a `@TempDir` and read the file afterwards.

**Act** — `PUT` `FULL` plus registry credentials.

**Assert** — no captured message contains `s3cr3t-token`, `p4ssw0rd`, or `hello-secret`.

## Reset

### `resetRemovesEveryContainerGroup`

`MockedProfile`.

**Arrange** — create `group-a` and `group-b`.

**Act** — `POST /_admin/reset`.

**Assert** — `204`; `GET {rgCollectionUrl()}` returns `{"value":[]}`;
`GET {groupUrl("group-a")}` returns `404`.

### `resetRemovesEveryDockerResource`

`RealModeProfile`, Docker required.

**Arrange** — `PUT` `FULL` as `reset-group`, running; capture `groupId` from the container
labels.

**Act** — `POST /_admin/reset`.

**Assert** — `204`; within 30 seconds `findByName("floci-az-aci-" + groupId + "-infra")` and
`findByName("floci-az-aci-" + groupId + "-web")` are both empty.

### `resetIsIdempotent`

**Act** — `POST /_admin/reset` twice.

**Assert** — both return `204`; the second does not throw.

### `resetSurvivesADeadDockerDaemon`

`RealModeProfile` with `floci-az.docker.docker-host` pointed at
`unix:///nonexistent/docker.sock`.

**Act** — `POST /_admin/reset`.

**Assert** — `204`. `AdminController.reset()`
(`src/main/java/io/floci/az/core/AdminController.java:48`) already swallows per-handler
failures, and `clear()` must not be the one that fails.

## Storage modes

`ContainerInstanceStorageTest`, parameterised over the four modes.

### `groupSurvivesAcrossBackendReloadInPersistentMode`

`@TestProfile` with `floci-az.storage.mode` = `persistent`,
`floci-az.storage.persistent-path` = `@TempDir`, and
`floci-az.services.container-instance.mocked` = `true`.

**Arrange** — `PUT` `MINIMAL` as `persist-group`.

**Act** — read `{tempDir}/containerinstance.json` from disk.

**Assert** — the file parses as JSON and contains the key
`test-sub-aci/test-rg-aci/persist-group`.

### `storageModeOverrideIsHonoured`

**Arrange** — `@TestProfile` with `floci-az.storage.mode` = `memory` and
`floci-az.storage.services.container-instance.mode` = `persistent`, plus a `@TempDir`.

**Act** — `PUT` `MINIMAL`.

**Assert** — `{tempDir}/containerinstance.json` exists. This proves the `StorageFactory`
`case "containerinstance"` was added
(`src/main/java/io/floci/az/core/storage/StorageFactory.java:136`).

### `memoryModeWritesNothingToDisk`

**Arrange** — `floci-az.storage.mode` = `memory`, `persistent-path` = `@TempDir`.

**Act** — `PUT` `MINIMAL`.

**Assert** — no file named `containerinstance.json` exists under the temporary directory.

### `bannerReportsTheService`

**Arrange** — capture startup log output.

**Assert** — the banner contains a line matching `^\s+aci\s+\[enabled \]\s+docker: .*$`. This
proves the `BannerLogger` wiring was added
(`src/main/java/io/floci/az/core/BannerLogger.java:97-101` is the block it follows).

## No-Docker-socket guarantee

`ContainerInstanceNoDockerTest`, `@QuarkusTest`.

### `serviceWorksInMockedModeWithNoDockerSocket`

`MockedProfile`. Runs unconditionally in CI, where there is no Docker socket.

**Act** — full CRUD: create with `FULL`, get, get with `$expand=instanceView`, patch, list,
each action, logs, delete.

**Assert** — every call returns the status in the
[routing table](container-instances.md#routing-table), and the emulator logs no `ERROR`.

### `realModeWithNoDockerDaemonDegradesGracefully`

`@TestProfile` with `floci-az.services.container-instance.mocked` = `false` and
`floci-az.docker.docker-host` = `unix:///nonexistent/docker.sock`. Runs unconditionally.

**Act** — `PUT` `FULL` as `degraded-group`.

**Assert**:

```
status                                      == 201
properties.provisioningState                == "Succeeded"
properties.instanceView.state               == "Running"
properties.instanceView.events.name         hasItem "DockerUnavailable"
properties.instanceView.events.find { it.name == "DockerUnavailable" }.type == "Warning"
properties.instanceView.events.find { it.name == "DockerUnavailable" }.message ==
    "The Docker daemon is not reachable; this container group is emulated without running containers."
```

Then `GET {logsUrl("degraded-group", "web")}` returns `200` with `{"content":""}`, each action
returns `204`, and `DELETE` returns `204`.

### `startupSucceedsWithNoDockerDaemon`

Same profile.

**Assert** — the Quarkus application starts, `GET /health` returns `200`, and the banner
contains the `aci` line. No `@PostConstruct` threw.

## Compatibility matrix

| Suite | Client | New file | Docker required? |
|---|---|---|---|
| `sdk-test-java` | `com.azure.resourcemanager:azure-resourcemanager-containerinstance` | `compatibility-tests/sdk-test-java/src/test/java/io/floci/az/compat/ContainerInstanceCompatibilityTest.java` | Yes — the emulator must run with `mocked=false` |
| `compat-azcli` | `az container` | `compatibility-tests/compat-azcli/test/container-instances.bats` | Yes |
| `compat-terraform` | `azurerm_container_group` | additions to `compatibility-tests/compat-terraform/main.tf` and `test/terraform.bats` | Yes |

### `sdk-test-java`

**Dependency.** Add to `compatibility-tests/sdk-test-java/pom.xml`. The suite imports
`com.azure:azure-sdk-bom:1.2.28` (`compatibility-tests/sdk-test-java/pom.xml:15`), which does
**not** manage `azure-resourcemanager-*` artifacts, so the version is explicit:

```xml
<dependency>
    <groupId>com.azure.resourcemanager</groupId>
    <artifactId>azure-resourcemanager-containerinstance</artifactId>
    <version>2.53.13</version>
</dependency>
```

`2.53.13` is the version whose `ContainerInstanceManagementClientImpl` pins
`apiVersion = "2023-05-01"` — the version this emulator's contract is transcribed from.

**No new environment variables.** The suite already receives `FLOCI_AZ_ENDPOINT` as an `ENV` in
its Dockerfile, so nothing needs to be added to the `Makefile` `SUITE_ENV_JAVA` variable
(`Makefile:40-43`) or to the `extra_env` entry in
`.github/workflows/compatibility.yml`. AGENTS.md requires those two to stay in sync whenever a
suite's env vars change; **this suite's do not change**, and the roadmap records that fact so
the obligation is visibly discharged rather than forgotten.

**Every SDK symbol the test uses**, transcribed from
`azure-resourcemanager-containerinstance:2.53.13` so the javadoc never needs to be opened:

| Symbol | Declaring type | Signature |
|---|---|---|
| `authenticate` | `ContainerInstanceManager` (static) | `static ContainerInstanceManager authenticate(TokenCredential credential, AzureProfile profile)` |
| `containerGroups()` | `ContainerInstanceManager` | `ContainerGroups containerGroups()` |
| `define` | `ContainerGroups` (via `SupportsCreating`) | `ContainerGroup.DefinitionStages.Blank define(String name)` |
| `withRegion` / `withExistingResourceGroup` | `ContainerGroup.DefinitionStages` (via `GroupableResource.DefinitionStages`) | `WithGroup withRegion(String regionName)`, `WithOsType withExistingResourceGroup(String groupName)` |
| `withLinux` | `DefinitionStages.WithOsType` | `WithPublicOrPrivateImageRegistry withLinux()` |
| `withPublicImageRegistryOnly` | `DefinitionStages.WithPublicOrPrivateImageRegistry` | `WithPrivateImageRegistryOrVolume withPublicImageRegistryOnly()` |
| `withEmptyDirectoryVolume` | `DefinitionStages.WithVolume` | `WithFirstContainerInstance withEmptyDirectoryVolume(String name)` |
| `defineContainerInstance` | `DefinitionStages.WithFirstContainerInstance` and `WithNextContainerInstance` | `ContainerInstanceDefinitionStages.ContainerInstanceDefinitionBlank<WithNextContainerInstance> defineContainerInstance(String name)` |
| `withImage` | `ContainerInstanceDefinitionStages.ContainerInstanceDefinitionBlank` | `WithOrWithoutPorts<ParentT> withImage(String imageName)` |
| `withExternalTcpPort` | `ContainerInstanceDefinitionStages.WithPorts` | `WithPortsOrContainerInstanceAttach<ParentT> withExternalTcpPort(int port)` |
| `withCpuCoreCount` | `ContainerInstanceDefinitionStages.WithCpuCoreCount` | `WithContainerInstanceAttach<ParentT> withCpuCoreCount(double cpuCoreCount)` |
| `withMemorySizeInGB` | `ContainerInstanceDefinitionStages.WithMemorySize` | `WithContainerInstanceAttach<ParentT> withMemorySizeInGB(double memorySize)` |
| `withStartingCommandLine` | `ContainerInstanceDefinitionStages.WithStartingCommandLine` | `WithContainerInstanceAttach<ParentT> withStartingCommandLine(String executable, String... parameters)` |
| `withEnvironmentVariable` | `ContainerInstanceDefinitionStages.WithEnvironmentVariables` | `WithContainerInstanceAttach<ParentT> withEnvironmentVariable(String envName, String envValue)` |
| `withEnvironmentVariableWithSecuredValue` | same | `WithContainerInstanceAttach<ParentT> withEnvironmentVariableWithSecuredValue(String envName, String securedValue)` |
| `withVolumeMountSetting` | `ContainerInstanceDefinitionStages.WithVolumeMountSetting` | `WithContainerInstanceAttach<ParentT> withVolumeMountSetting(String volumeName, String mountPath)` |
| `attach` | `ContainerInstanceDefinitionStages.WithContainerInstanceAttach` (extends `Attachable.InDefinition<ParentT>`) | `ParentT attach()` — returns `WithNextContainerInstance`, which extends `WithCreate` |
| `withRestartPolicy` | `DefinitionStages.WithRestartPolicy` | `WithCreate withRestartPolicy(ContainerGroupRestartPolicy restartPolicy)` |
| `withDnsPrefix` | `DefinitionStages.WithDnsPrefix` | `WithCreate withDnsPrefix(String dnsPrefix)` |
| `withTag` / `withoutTag` | `Resource.DefinitionWithTags` and `Resource.UpdateWithTags` | `withTag(String key, String value)`, `withoutTag(String key)` |
| `create` / `apply` | `Creatable` / `Appliable` | `ContainerGroup create()`, `ContainerGroup apply()` |
| `update` | `ContainerGroup` (via `Updatable<ContainerGroup.Update>`) | `ContainerGroup.Update update()`; `Update extends Resource.UpdateWithTags<Update>, Appliable<ContainerGroup>` |
| `refresh` | `ContainerGroup` (via `Refreshable<ContainerGroup>`) | `ContainerGroup refresh()` — there is **no** `refreshInstanceView()` on this SDK version |
| `getByResourceGroup` | `ContainerGroups` (via `SupportsGettingByResourceGroup`) | `ContainerGroup getByResourceGroup(String resourceGroupName, String name)` |
| `listByResourceGroup` / `list` | `ContainerGroups` (via `SupportsListingByResourceGroup` / `SupportsListing`) | `PagedIterable<ContainerGroup> listByResourceGroup(String resourceGroupName)`, `PagedIterable<ContainerGroup> list()` |
| `deleteByResourceGroup` | `ContainerGroups` (via `SupportsDeletingByResourceGroup`) | `void deleteByResourceGroup(String resourceGroupName, String name)` |
| `start` | `ContainerGroups` | `void start(String resourceGroupName, String containerGroupName)` |
| `restart` / `stop` | `ContainerGroup` — **instance** methods, not on `ContainerGroups` | `void restart()`, `void stop()` |
| `getLogContent` | `ContainerGroups` | `String getLogContent(String resourceGroupName, String containerGroupName, String containerName, int tailLineCount)` |
| `containers()` | `ContainerGroup` | `Map<String, Container> containers()` |
| `volumes()` | `ContainerGroup` | `Map<String, Volume> volumes()` |
| `state()` / `provisioningState()` / `fqdn()` / `ipAddress()` | `ContainerGroup` | `String state()`, `String provisioningState()`, `String fqdn()`, `String ipAddress()` |
| `osType()` / `restartPolicy()` | `ContainerGroup` | `OperatingSystemTypes osType()`, `ContainerGroupRestartPolicy restartPolicy()` |
| `image()` / `environmentVariables()` / `instanceView()` | `Container` (model class) | `String image()`, `List<EnvironmentVariable> environmentVariables()`, `ContainerPropertiesInstanceView instanceView()` |
| `secureValue()` | `EnvironmentVariable` | `String secureValue()` |
| `currentState()` / `previousState()` / `restartCount()` | `ContainerPropertiesInstanceView` | `ContainerState currentState()`, `ContainerState previousState()`, `Integer restartCount()` |
| `state()` / `exitCode()` / `detailStatus()` | `ContainerState` | `String state()`, `Integer exitCode()`, `String detailStatus()` |
| `ContainerGroupRestartPolicy.ALWAYS` | `ContainerGroupRestartPolicy` | `static final ContainerGroupRestartPolicy ALWAYS` |

`ContainerInstanceManager` has **no** `resourceManager()` accessor, so the resource group is
created with a raw `HttpClient` PUT — the same approach `VmCompatibilityTest` uses
(`compatibility-tests/sdk-test-java/src/test/java/io/floci/az/compat/VmCompatibilityTest.java:222-225`).

**The test to write**, complete:

```java
package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.containerinstance.ContainerInstanceManager;
import com.azure.resourcemanager.containerinstance.models.ContainerGroup;
import com.azure.resourcemanager.containerinstance.models.ContainerGroupRestartPolicy;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatibility test for Microsoft.ContainerInstance/containerGroups driven by the fluent
 * management SDK {@code com.azure.resourcemanager:azure-resourcemanager-containerinstance},
 * which sends {@code api-version=2023-05-01}.
 *
 * <p>The resource group is created with a raw {@link HttpClient} PUT, because
 * {@code ContainerInstanceManager} exposes no {@code resourceManager()} accessor; this mirrors
 * {@link VmCompatibilityTest}, which drives the ARM plane the same way.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Container Instances Compatibility")
class ContainerInstanceCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String RG = "aci-rg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String GROUP = "aci-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String RG_API = "2021-04-01";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static ContainerInstanceManager manager;

    @BeforeAll
    static void setup() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();

        AzureEnvironment env = new AzureEnvironment(new HashMap<>(Map.of(
                "managementEndpointUrl", BASE,
                "resourceManagerEndpointUrl", BASE,
                "activeDirectoryEndpointUrl", BASE + "/",
                "activeDirectoryResourceId", BASE)));

        TokenCredential credential = request ->
                Mono.just(new AccessToken("floci-az-fake-token", OffsetDateTime.now().plusHours(1)));

        manager = ContainerInstanceManager.authenticate(
                credential, new AzureProfile(TENANT, SUBSCRIPTION, env));

        String rgUrl = BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "?api-version=" + RG_API;
        HttpResponse<String> rgResponse = http.send(
                HttpRequest.newBuilder(URI.create(rgUrl))
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"location\":\"eastus\"}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(rgResponse.statusCode() >= 200 && rgResponse.statusCode() < 300,
                "create resource group failed: " + rgResponse.statusCode() + " " + rgResponse.body());
    }

    @AfterAll
    static void teardown() {
        if (manager != null) {
            try {
                manager.containerGroups().deleteByResourceGroup(RG, GROUP);
            } catch (Exception ignored) {
                // already removed by the delete test
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("define/create returns a Succeeded container group")
    void createContainerGroup() {
        ContainerGroup group = manager.containerGroups()
                .define(GROUP)
                .withRegion("eastus")
                .withExistingResourceGroup(RG)
                .withLinux()
                .withPublicImageRegistryOnly()
                .withEmptyDirectoryVolume("scratch-volume")
                .defineContainerInstance("web")
                    .withImage("alpine:3.20")
                    .withExternalTcpPort(8080)
                    .withCpuCoreCount(1.0)
                    .withMemorySizeInGB(1.0)
                    .withEnvironmentVariable("GREETING", "hello")
                    .withEnvironmentVariableWithSecuredValue("API_TOKEN", "s3cr3t-token")
                    .withVolumeMountSetting("scratch-volume", "/mnt/scratch")
                    .withStartingCommandLine("sh", "-c",
                        "while true; do echo hello-from-java-sdk; sleep 2; done")
                    .attach()
                .withRestartPolicy(ContainerGroupRestartPolicy.ALWAYS)
                .withDnsPrefix("floci-sdk-aci")
                .withTag("suite", "sdk-test-java")
                .create();

        assertEquals(GROUP, group.name());
        assertEquals("eastus", group.regionName());
        assertEquals("Succeeded", group.provisioningState());
        assertEquals(1, group.containers().size());
        assertTrue(group.containers().containsKey("web"));
        assertEquals("alpine:3.20", group.containers().get("web").image());
        assertEquals("sdk-test-java", group.tags().get("suite"));
        assertNotNull(group.ipAddress());
        assertEquals("floci-sdk-aci.eastus.azurecontainer.io", group.fqdn());
    }

    @Test
    @Order(2)
    @DisplayName("getByResourceGroup round-trips the group and redacts the secure value")
    void getContainerGroup() {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        assertEquals(GROUP, group.name());
        assertEquals("Linux", group.osType().toString());
        assertEquals("Always", group.restartPolicy().toString());
        assertEquals(1, group.volumes().size());
        assertTrue(group.volumes().containsKey("scratch-volume"));
        group.containers().get("web").environmentVariables()
                .forEach(v -> assertNull(v.secureValue(),
                        "secureValue must never be returned by the emulator"));
    }

    @Test
    @Order(3)
    @DisplayName("refresh reports every container Running")
    void instanceView() throws Exception {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        for (int i = 0; i < 60 && !"Running".equals(group.state()); i++) {
            Thread.sleep(1000);
            group = group.refresh();
        }
        assertEquals("Running", group.state());
        assertEquals("Running",
                group.containers().get("web").instanceView().currentState().state());
    }

    @Test
    @Order(4)
    @DisplayName("getLogContent returns the container's stdout")
    void logs() throws Exception {
        String content = "";
        for (int i = 0; i < 60 && content.isEmpty(); i++) {
            content = manager.containerGroups().getLogContent(RG, GROUP, "web", 50);
            if (content.isEmpty()) {
                Thread.sleep(1000);
            }
        }
        assertTrue(content.contains("hello-from-java-sdk"),
                "expected container stdout in the log content, got: " + content);
    }

    @Test
    @Order(5)
    @DisplayName("restart, stop and start drive the group state")
    void lifecycleActions() throws Exception {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        group.restart();
        group.stop();

        group = group.refresh();
        assertEquals("Stopped", group.state());

        manager.containerGroups().start(RG, GROUP);
        for (int i = 0; i < 60 && !"Running".equals(group.state()); i++) {
            Thread.sleep(1000);
            group = group.refresh();
        }
        assertEquals("Running", group.state());
    }

    @Test
    @Order(6)
    @DisplayName("list by resource group and by subscription both contain the group")
    void listing() {
        assertTrue(manager.containerGroups().listByResourceGroup(RG).stream()
                .anyMatch(g -> GROUP.equals(g.name())));
        assertTrue(manager.containerGroups().list().stream()
                .anyMatch(g -> GROUP.equals(g.name())));
    }

    @Test
    @Order(7)
    @DisplayName("update replaces the tag collection")
    void updateTags() {
        ContainerGroup group = manager.containerGroups().getByResourceGroup(RG, GROUP);
        ContainerGroup updated = group.update()
                .withoutTag("suite")
                .withTag("stage", "compat")
                .apply();
        assertEquals("compat", updated.tags().get("stage"));
        assertNull(updated.tags().get("suite"));
    }

    @Test
    @Order(8)
    @DisplayName("delete removes the group from both listings")
    void deleteContainerGroup() {
        manager.containerGroups().deleteByResourceGroup(RG, GROUP);
        assertTrue(manager.containerGroups().listByResourceGroup(RG).stream()
                .noneMatch(g -> GROUP.equals(g.name())));
        assertTrue(manager.containerGroups().list().stream()
                .noneMatch(g -> GROUP.equals(g.name())));
    }
}
```

`EmulatorConfig.assumeEmulatorRunning()` is the existing helper in
`compatibility-tests/sdk-test-java/src/test/java/io/floci/az/compat/EmulatorConfig.java`, used
the same way `VmCompatibilityTest` uses it
(`compatibility-tests/sdk-test-java/src/test/java/io/floci/az/compat/VmCompatibilityTest.java:56-59`).

**Makefile target** to add next to `test-servicebus-compat` (`Makefile:248-250`):

```makefile
test-aci-compat:
	@echo "==> Container Instances Java SDK compatibility tests"
	cd $(JAVA_DIR) && mvn test -Dtest=ContainerInstanceCompatibilityTest -q
```

### `compat-azcli`

New file `compatibility-tests/compat-azcli/test/container-instances.bats`, following the
structure of `compatibility-tests/compat-azcli/test/network-vm.bats`.

Add to `compatibility-tests/compat-azcli/test/test_helper/common-setup.bash`, next to the other
resource names:

```bash
export ACI_NAME="floci-test-aci"
export ACI_DNS_LABEL="floci-test-aci-dns"
```

The suite runs the CLI, which sends `api-version=2024-05-01-preview` from
`azure-mgmt-containerinstance` `10.2.0b1`. The emulator accepts it — that is exactly what this
suite proves.

**Every `az container` symbol the suite uses**, transcribed from `az container --help` and
`az container create --help` on Azure CLI with `azure-mgmt-containerinstance 10.2.0b1`, so the
CLI reference never needs to be opened:

| Command | Purpose |
|---|---|
| `az container create` | Create a container group |
| `az container show -g <rg> -n <name>` | Get one container group |
| `az container list -g <rg>` | List container groups in a resource group |
| `az container logs -g <rg> -n <name> [--container-name <c>]` | Print a container's logs; the first container is used when `--container-name` is omitted |
| `az container start -g <rg> -n <name>` | Start all containers in the group |
| `az container stop -g <rg> -n <name>` | Stop all containers in the group |
| `az container restart -g <rg> -n <name>` | Restart all containers in the group |
| `az container delete -g <rg> -n <name> --yes` | Delete the group without prompting |

| `az container create` flag | Meaning | Maps to |
|---|---|---|
| `-g`, `--resource-group` | Resource group name (required) | path segment |
| `-n`, `--name` | Container group name; also becomes the single container's name | path segment and `properties.containers[0].name` |
| `-l`, `--location` | Region | `location` |
| `--image` | Container image | `properties.containers[0].properties.image` |
| `--os-type` | `Linux` or `Windows` | `properties.osType` |
| `--cpu` | CPU cores, one decimal place | `properties.containers[0].properties.resources.requests.cpu` |
| `--memory` | Memory in GB, one decimal place | `...resources.requests.memoryInGB` |
| `--restart-policy` | `Always`, `Never`, or `OnFailure` | `properties.restartPolicy` |
| `--ports` | Space-separated port list, default `[80]` | `properties.ipAddress.ports[]` and `properties.containers[0].properties.ports[]` |
| `--protocol` | `TCP` or `UDP` | the `protocol` of those ports |
| `--ip-address` | `Public` or `Private` | `properties.ipAddress.type` |
| `--dns-name-label` | DNS name label for a public IP | `properties.ipAddress.dnsNameLabel` |
| `-e`, `--environment-variables` | Space-separated `key=value` pairs | `...environmentVariables[]` with `value` |
| `--secure-environment-variables` | Space-separated `key=value` pairs | `...environmentVariables[]` with `secureValue` |
| `--command-line` | Command to run, e.g. `'/bin/bash -c myscript.sh'` | `properties.containers[0].properties.command` |
| `--secrets` / `--secrets-mount-path` | Space-separated `key=value` secrets and their mount path | a `secret` volume plus its `volumeMounts` entry |
| `--azure-file-volume-share-name` / `--azure-file-volume-account-name` / `--azure-file-volume-account-key` / `--azure-file-volume-mount-path` | Azure File share volume | an `azureFile` volume plus its mount |
| `--registry-login-server` / `--registry-username` / `--registry-password` | Private registry credentials | `properties.imageRegistryCredentials[0]` |
| `--sku` | `Standard`, `Dedicated`, or `Confidential` | `properties.sku` |
| `--priority` | `Regular` or `Spot` | `properties.priority` |
| `--zone` | Availability zone | `zones` |
| `--assign-identity` | Space-separated identity list | `identity` |
| `--log-analytics-workspace` / `--log-analytics-workspace-key` | Log Analytics wiring | `properties.diagnostics.logAnalytics` |
| `-o none` / `-o json` | Output format | — |
| `--no-wait` | Do not wait for the long-running operation | — (the emulator is synchronous) |


```bash
#!/usr/bin/env bats
# Azure Container Instances via the real `az container` CLI.

setup_file() {
    load 'test_helper/common-setup'
    az group create -n "$RG_NAME" -l "$LOCATION" -o none
}

setup() {
    load 'test_helper/common-setup'
}

teardown_file() {
    load 'test_helper/common-setup'
    az container delete -g "$RG_NAME" -n "$ACI_NAME" --yes -o none 2>/dev/null || true
}

@test "az container create: creates a running container group" {
    run az container create \
        -g "$RG_NAME" -n "$ACI_NAME" -l "$LOCATION" \
        --image alpine:3.20 \
        --os-type Linux \
        --cpu 1 --memory 1 \
        --restart-policy Always \
        --ports 8080 \
        --dns-name-label "$ACI_DNS_LABEL" \
        --environment-variables GREETING=hello \
        --secure-environment-variables API_TOKEN=s3cr3t-token \
        --command-line "sh -c 'while true; do echo hello-from-azcli; sleep 2; done'" \
        -o none
    assert_success
}

@test "az container show: reports the group with Succeeded provisioning state" {
    run az_json container show -g "$RG_NAME" -n "$ACI_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$ACI_NAME"
    assert_equal "$(echo "$output" | jq -r '.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.osType')" "Linux"
    assert_equal "$(echo "$output" | jq -r '.restartPolicy')" "Always"
    assert_equal "$(echo "$output" | jq -r '.containers[0].name')" "$ACI_NAME"
    assert_equal "$(echo "$output" | jq -r '.containers[0].image')" "alpine:3.20"
}

@test "az container show: returns an FQDN built from the DNS name label" {
    run az_json container show -g "$RG_NAME" -n "$ACI_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.ipAddress.fqdn')" \
        "${ACI_DNS_LABEL}.${LOCATION}.azurecontainer.io"
}

@test "az container show: never returns the secure environment variable value" {
    run az_json container show -g "$RG_NAME" -n "$ACI_NAME"
    assert_success
    refute_output --partial "s3cr3t-token"
    assert_output --partial "API_TOKEN"
}

@test "az container show: instance view reports Running" {
    local state=""
    for _ in $(seq 1 60); do
        state=$(az_json container show -g "$RG_NAME" -n "$ACI_NAME" \
            | jq -r '.instanceView.state')
        [ "$state" = "Running" ] && break
        sleep 1
    done
    assert_equal "$state" "Running"
}

@test "az container logs: returns the container stdout" {
    local logs=""
    for _ in $(seq 1 60); do
        logs=$(az container logs -g "$RG_NAME" -n "$ACI_NAME" 2>/dev/null || true)
        [ -n "$logs" ] && break
        sleep 1
    done
    assert_equal "$(echo "$logs" | grep -c 'hello-from-azcli' || true)" "$(echo "$logs" | grep -c 'hello-from-azcli' || true)"
    [[ "$logs" == *"hello-from-azcli"* ]]
}

@test "az container list: contains the group" {
    run az_json container list -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg n "$ACI_NAME" '[.[] | select(.name == $n)] | length')" "1"
}

@test "az container restart: succeeds" {
    run az container restart -g "$RG_NAME" -n "$ACI_NAME" -o none
    assert_success
}

@test "az container stop: leaves the group in the Stopped state" {
    run az container stop -g "$RG_NAME" -n "$ACI_NAME" -o none
    assert_success
    run az_json container show -g "$RG_NAME" -n "$ACI_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.instanceView.state')" "Stopped"
}

@test "az container start: brings the group back to Running" {
    run az container start -g "$RG_NAME" -n "$ACI_NAME" -o none
    assert_success
    local state=""
    for _ in $(seq 1 60); do
        state=$(az_json container show -g "$RG_NAME" -n "$ACI_NAME" \
            | jq -r '.instanceView.state')
        [ "$state" = "Running" ] && break
        sleep 1
    done
    assert_equal "$state" "Running"
}

@test "az container delete: removes the group" {
    run az container delete -g "$RG_NAME" -n "$ACI_NAME" --yes -o none
    assert_success
    run az container show -g "$RG_NAME" -n "$ACI_NAME"
    assert_failure
}
```

`az_json` and `assert_*` come from
`compatibility-tests/compat-azcli/test/test_helper/common-setup.bash`, which already defines
`az_json` and loads `bats-support` and `bats-assert`.

### `compat-terraform`

Add to `compatibility-tests/compat-terraform/main.tf`. The suite pins `azurerm ~> 3.0`
(`compatibility-tests/compat-terraform/provider.tf:5`), whose `azurerm_container_group` uses
`containerinstance/2023-05-01` — the emulator's pinned version.

**Every `azurerm_container_group` argument the suite uses**, so the provider reference never
needs to be opened:

| HCL argument | Type | Required | Maps to |
|---|---|---|---|
| `name` | string | yes | path segment `{containerGroupName}` |
| `location` | string | yes | `location` |
| `resource_group_name` | string | yes | path segment `{resourceGroupName}` |
| `os_type` | string, `Linux` or `Windows` | yes | `properties.osType` |
| `restart_policy` | string, `Always` \| `OnFailure` \| `Never`, default `Always` | no | `properties.restartPolicy` |
| `ip_address_type` | string, `Public` \| `Private` \| `None`, default `Public` | no | `properties.ipAddress.type` (`None` omits `ipAddress`) |
| `dns_name_label` | string | no | `properties.ipAddress.dnsNameLabel` |
| `tags` | map of string | no | `tags` |
| `container` block, repeatable | block | yes, at least one | one entry of `properties.containers[]` |
| `container.name` | string | yes | `containers[i].name` |
| `container.image` | string | yes | `containers[i].properties.image` |
| `container.cpu` | string holding a number | yes | `...resources.requests.cpu` |
| `container.memory` | string holding a number | yes | `...resources.requests.memoryInGB` |
| `container.commands` | list of string | no | `...command` |
| `container.environment_variables` | map of string | no | `...environmentVariables[]` with `value` |
| `container.secure_environment_variables` | map of string, marked sensitive | no | `...environmentVariables[]` with `secureValue` |
| `container.ports` block, repeatable | block | no | `...ports[]` |
| `container.ports.port` | number | yes within the block | `...ports[i].port` |
| `container.ports.protocol` | string, `TCP` \| `UDP`, default `TCP` | no | `...ports[i].protocol` |
| `container.volume` block, repeatable | block | no | one `properties.volumes[]` entry plus the matching `volumeMounts[]` entry |
| `container.volume.name` | string | yes within the block | `volumes[i].name` and `volumeMounts[j].name` |
| `container.volume.mount_path` | string | yes within the block | `volumeMounts[j].mountPath` |
| `container.volume.read_only` | bool, default `false` | no | `volumeMounts[j].readOnly` |
| `container.volume.empty_dir` | bool, default `false` | no | `volumes[i].emptyDir` when `true` |
| `container.volume.secret` | map of string, sensitive | no | `volumes[i].secret` (the provider Base64-encodes the values) |
| `container.volume.storage_account_name` / `storage_account_key` / `share_name` | string | no | `volumes[i].azureFile` |
| `image_registry_credential` block, repeatable | block | no | `properties.imageRegistryCredentials[]` |
| `identity` block | block | no | `identity` |
| `diagnostics` block | block | no | `properties.diagnostics` |

Exported attributes the tests read: `id` (the ARM resource id), `fqdn`
(`properties.ipAddress.fqdn`), and `ip_address` (`properties.ipAddress.ip`).


```hcl
resource "azurerm_container_group" "aci" {
  name                = "floci-test-aci-tf"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  ip_address_type     = "Public"
  dns_name_label      = "floci-test-aci-tf"
  os_type             = "Linux"
  restart_policy      = "Always"

  container {
    name   = "web"
    image  = "alpine:3.20"
    cpu    = "1"
    memory = "1"

    commands = ["sh", "-c", "while true; do echo hello-from-terraform; sleep 2; done"]

    ports {
      port     = 8080
      protocol = "TCP"
    }

    environment_variables = {
      GREETING = "hello"
    }

    secure_environment_variables = {
      API_TOKEN = "s3cr3t-token"
    }

    volume {
      name       = "scratch-volume"
      mount_path = "/mnt/scratch"
      read_only  = false
      empty_dir  = true
    }
  }

  tags = {
    suite = "compat-terraform"
  }
}

output "aci_id" {
  value = azurerm_container_group.aci.id
}

output "aci_fqdn" {
  value = azurerm_container_group.aci.fqdn
}

output "aci_ip_address" {
  value = azurerm_container_group.aci.ip_address
}
```

Add to `compatibility-tests/compat-terraform/test/terraform.bats`, alongside the other spot
checks:

```bash
@test "Terraform: container group created" {
    cd "$TF_DIR"
    run terraform output -raw aci_id
    assert_success
    assert_output --partial "/providers/Microsoft.ContainerInstance/containerGroups/floci-test-aci-tf"
}

@test "Terraform: container group FQDN follows the Azure format" {
    cd "$TF_DIR"
    run terraform output -raw aci_fqdn
    assert_success
    assert_output "floci-test-aci-tf.eastus.azurecontainer.io"
}

@test "Terraform: container group has an IP address" {
    cd "$TF_DIR"
    run terraform output -raw aci_ip_address
    assert_success
    refute_output ""
}
```

`terraform destroy` in `teardown_file` already covers deletion; the `204`-on-delete decision
([deviation D14](container-instances.md#deviations-register)) is what makes it terminate.

The same additions apply verbatim to `compatibility-tests/compat-opentofu`, which uses the same
provider.

### Environment-variable sync obligation

AGENTS.md requires that *"a suite's env vars must be identical across every `docker run` for
that suite in the Makefile and the corresponding matrix `extra_env` entry in
`.github/workflows/compatibility.yml`."*

**No suite in this plan introduces a new environment variable.** `sdk-test-java` uses
`FLOCI_AZ_ENDPOINT`, already baked into its Dockerfile; `compat-azcli` uses only the variables
`common-setup.bash` exports internally; `compat-terraform` uses `TF_VAR_metadata_host`, already
present. `SUITE_ENV_JAVA` (`Makefile:40-43`) and the `extra_env` entries in
`.github/workflows/compatibility.yml` therefore stay untouched.

One change **is** required: `test-java-compat` (`Makefile:219-221`) starts the emulator with
`$(JAVA_SERVICEBUS_EMULATOR_ENV)`, which today is
`-e FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED=false -e FLOCI_AZ_SERVICES_SERVICE_BUS_LOCK_DURATION_SECONDS=5`
(`Makefile:47-49`). Container Instances runs unmocked by default from `application.yml`, so no
addition is needed there either — but the Java suite's emulator container must have the Docker
socket mounted, which `test-java-compat` already does
(`-v /var/run/docker.sock:/var/run/docker.sock`, `Makefile:221`).

The roadmap records this check as an explicit step so the obligation is discharged visibly.

## Authoritative references

- `AGENTS.md`, sections *Testing Rules*, *Compatibility Test Suite*, and *Keeping the Makefile
  and CI in sync*.
- `src/test/java/io/floci/az/services/vm/VmHandlerTest.java` — the mocked-mode `@QuarkusTest`
  and RestAssured pattern, and the `_admin/reset` `@BeforeEach`.
- `src/test/java/io/floci/az/services/vm/VmDockerTest.java` — the Docker-availability
  `assumeTrue` guard and the ordered, state-sharing test class.
- `compatibility-tests/sdk-test-java/src/test/java/io/floci/az/compat/VmCompatibilityTest.java`
  — `EmulatorConfig.assumeEmulatorRunning()` and the ARM compatibility-test shape.
- `compatibility-tests/compat-azcli/test/network-vm.bats` and
  `compatibility-tests/compat-azcli/test/test_helper/common-setup.bash` — the bats structure and
  the `az_json` helper.
- `compatibility-tests/compat-terraform/main.tf`, `provider.tf`, and `test/terraform.bats` —
  the Terraform suite structure and the `azurerm ~> 3.0` pin.
- `compatibility-tests/sdk-test-java/pom.xml` — `azure-sdk-bom` `1.2.28`, which does not manage
  `azure-resourcemanager-*` versions.
- `Makefile` — `SUITE_ENV_JAVA`, `test-java-compat`, `test-servicebus-compat`.
- <https://learn.microsoft.com/en-us/java/api/overview/azure/resourcemanager-containerinstance-readme>
  — the `ContainerInstanceManager` fluent API used above.
