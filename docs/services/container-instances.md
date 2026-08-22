# Azure Container Instances (ACI)

Compatible with the `azure-resourcemanager-containerinstance` SDK, the `az container` CLI,
Terraform's `azurerm_container_group`, and any ARM-speaking client.

> **Container-backed mode (default): Docker required.** Each container group becomes a Docker
> *pod*: one infrastructure container owns the network namespace and carries every host port
> binding, and each ACI container joins it with Docker network mode `container:<id>`. Containers
> in one group therefore reach each other on `localhost`, exactly as they do in Azure.
>
> **Mocked mode:** set `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_MOCKED=true` to serve container
> groups as pure ARM control-plane state. Nothing is started, every container reports `Running`,
> and `logs` returns empty content. This keeps the service usable with no Docker socket.
>
> If the Docker daemon is unreachable, a container group is **not** an error: it is created in a
> degraded state that behaves like mocked mode and carries a `DockerUnavailable` event in its
> `instanceView`.

The pinned API version is `2023-05-01`. Any `api-version` is accepted — including absent and
preview values — because the Java SDK, the Azure CLI, and the `azurerm` provider each send a
different one.

---

## Features

- **Lifecycle** — CreateOrUpdate, Get (with `?$expand=instanceView`), UpdateTags, Delete,
  List by resource group and by subscription
- **Actions** — `start`, `stop`, `restart`, all synchronous and terminal
- **Logs** — `GET .../containers/{container}/logs` with `tail` and `timestamps`, bounded by the
  configured byte and line caps
- **instanceView** — group `state` plus per-container `currentState`, `previousState`,
  `restartCount`, and `events`
- **Restart policy** — `Always`, `OnFailure`, `Never`, with the emulator (not Docker) owning the
  restart decision
- **Shared network namespace** — containers in a group reach each other on `localhost`
- **Volumes** — `emptyDir`, `secret` (read-only, populated over the Docker archive API), and
  `azureFile` (backed by a local Docker volume)
- **Init containers** — run sequentially to completion before the main containers
- **Private registries** — `imageRegistryCredentials` are used for the image pull
- **Read-only collections** — `locations/{loc}/usages`, `.../capabilities`, `.../cachedImages`,
  and `outboundNetworkDependenciesEndpoints`

---

## Endpoints

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
PATCH  .../containerGroups/{name}
DELETE .../containerGroups/{name}
POST   .../containerGroups/{name}/{start|stop|restart}
GET    .../containerGroups/{name}/containers/{container}/logs
GET    .../containerGroups/{name}/outboundNetworkDependenciesEndpoints
POST   .../containerGroups/{name}/containers/{container}/{exec|attach}     -> 501 NotImplemented
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups
GET    /subscriptions/{sub}/providers/Microsoft.ContainerInstance/containerGroups
GET    /subscriptions/{sub}/providers/Microsoft.ContainerInstance/locations/{loc}/{usages|capabilities|cachedImages}
```

Use `?$expand=instanceView` on a Get to embed `properties.instanceView` and each container's
`properties.instanceView`. A list response never carries an instance view.

---

## Quickstart

### 1 — Create a container group

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group?api-version=2023-05-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "osType": "Linux",
      "restartPolicy": "Always",
      "containers": [
        {
          "name": "web",
          "properties": {
            "image": "alpine:3.20",
            "command": ["sh", "-c", "while true; do echo hello; sleep 2; done"],
            "ports": [{"port": 8080, "protocol": "TCP"}],
            "environmentVariables": [
              {"name": "GREETING", "value": "hello"},
              {"name": "API_TOKEN", "secureValue": "s3cr3t-token"}
            ],
            "resources": {"requests": {"cpu": 1.0, "memoryInGB": 1.0}}
          }
        }
      ],
      "ipAddress": {
        "type": "Public",
        "dnsNameLabel": "floci-demo-group",
        "ports": [{"port": 8080, "protocol": "TCP"}]
      }
    }
  }'
```

The group is returned with `properties.provisioningState = "Succeeded"`. `API_TOKEN` comes back
with its name only — a `secureValue` is never persisted, logged, or returned.

### 2 — With the Azure CLI

```bash
az container create \
  -g my-rg -n demo-group -l eastus \
  --image alpine:3.20 --os-type Linux \
  --cpu 1 --memory 1 \
  --restart-policy Always \
  --ports 8080 \
  --dns-name-label floci-demo-group \
  --environment-variables GREETING=hello \
  --secure-environment-variables API_TOKEN=s3cr3t-token \
  --command-line "sh -c 'while true; do echo hello; sleep 2; done'"
```

### 3 — Read state and logs

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerInstance/containerGroups/demo-group"
curl -s "$BASE?api-version=2023-05-01&\$expand=instanceView"
curl -s "$BASE/containers/web/logs?api-version=2023-05-01&tail=20&timestamps=true"
```

### 4 — Reach the group from the host

`ipAddress.ip` is `127.0.0.1` when floci-az runs on the host. The port actually bound on the
host may differ from the advertised Azure port — ports below 1024 need root, and a busy port is
not the emulator's to claim. Read the real mapping from the `PortMapped` event:

```bash
curl -s "$BASE?api-version=2023-05-01&\$expand=instanceView" \
  | jq -r '.properties.instanceView.events[] | select(.name=="PortMapped") | .message'
# Container group port 8080 published on host port 8500
```

### 5 — Stop, start, restart, delete

```bash
curl -s -X POST "$BASE/stop?api-version=2023-05-01"       # -> 204, instanceView.state = Stopped
curl -s -X POST "$BASE/start?api-version=2023-05-01"      # -> 204, instanceView.state = Running
curl -s -X POST "$BASE/restart?api-version=2023-05-01"    # -> 204, restartCount + 1
curl -s -X DELETE "$BASE?api-version=2023-05-01"          # -> 204, idempotent
```

---

## Configuration

```yaml
floci-az:
  services:
    container-instance:
      enabled: true
      mocked: false             # false = real Docker containers. true = no Docker, pure ARM state
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
  storage:
    services:
      container-instance:
        flush-interval-ms: 5000
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_MOCKED` | `false` | Mocked mode (no Docker) |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_INFRA_IMAGE` | `alpine:3.20` | Image for the namespace-owning infrastructure container |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_DEFAULT_LOCATION` | `eastus` | Location used when a request omits it, and the FQDN region label |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_BASE_PORT` | `8500` | Low end of the host-port fallback range |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_MAX_PORT` | `8599` | High end of the host-port fallback range |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_RECONCILE_INTERVAL_SECONDS` | `3` | Reconciler period |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_STOP_TIMEOUT_SECONDS` | `10` | `docker stop` grace period |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_LOG_MAX_BYTES` | `4194304` | Byte cap for a running container's logs (Azure: 4 MB) |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_LOG_MAX_LINES` | `100000` | Line cap for a running container's logs |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_STOPPED_LOG_MAX_BYTES` | `16384` | Byte cap for a stopped container's logs (Azure: 16 KB) |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_STOPPED_LOG_MAX_LINES` | `1000` | Line cap for a stopped container's logs (Azure: 1 000 lines) |
| `FLOCI_AZ_SERVICES_CONTAINER_INSTANCE_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave containers running when the emulator stops |
| `FLOCI_AZ_STORAGE_SERVICES_CONTAINER_INSTANCE_MODE` | inherits `floci-az.storage.mode` | Per-service storage override |

---

## Secret handling

`secureValue` environment variables, `secret` volume contents, registry passwords,
`diagnostics.logAnalytics.workspaceKey` / `workspaceResourceId`, and
`azureFile.storageAccountKey` are stripped from the request before the group is stored. They
live only in memory, for the lifetime of the process, and are used solely to create containers.

- A `GET` returns `secureValue`-carrying environment variables with their **name only**, and a
  secret volume as `"secret": {}` — the same as Azure.
- Nothing secret is written to `data/containerinstance.json` in any storage mode, and nothing
  secret appears in a log line.
- After an emulator restart the secrets are gone. Running containers are re-adopted by name, but
  a group whose containers must be re-created without its secrets is marked `Failed` with a
  `SecretsUnavailableAfterRestart` event rather than silently started broken.

---

## Notes & limitations

| # | Deviation from Azure | Emulator behaviour |
|---|---|---|
| D1 | Long-running operations | `PUT`, `DELETE`, `start` and `restart` complete synchronously and never emit `Azure-AsyncOperation`, `Location` or `Retry-After` |
| D2 | `api-version` is not enforced | Any value, including absent, is served with the `2023-05-01` shape |
| D3 | FQDN is cosmetic | `{dnsNameLabel}.{region}.azurecontainer.io` is returned but nothing resolves it to the group |
| D4 | Advertised port may differ from the host port | `ipAddress.ports[]` echoes the Azure ports; the real host binding is published as a `PortMapped` event |
| D5 | Single-node scheduling | `zones` is echoed and ignored |
| D6 | No regional quotas | The per-group hard limits (60 containers, 20 volumes, 5 ports, 31 CPU, 240 GB) are enforced; `usages` reports counts against Azure's documented limits |
| D7 | CPU is not enforced | `resources.requests.cpu` / `limits.cpu` are validated and echoed but not applied to Docker. Memory **is** enforced |
| D8 | `exec` and `attach` | Return `501 NotImplemented` — floci-az serves no websocket data plane |
| D9 | `azureFile` volumes | Backed by a local Docker volume; contents are **not** shared with the emulated Blob/File service. An `AzureFileEmulated` event records this |
| D10 | `gitRepo` volumes | Rejected with `400 NotSupported` |
| D11 | Windows containers | `osType: "Windows"` is accepted and echoed; the runtime always creates Linux containers |
| D12 | `Never` restart policy | Never restarts, whatever the exit code — Azure's documented behaviour here is non-deterministic |
| D13 | Managed identity | `identity` is echoed with synthetic `principalId`/`tenantId`; no IMDS endpoint is injected into the containers |
| D14 | Delete status | Always `204`, never `202`, and idempotent |
| D15 | `provisioningState` | Only `Succeeded` or `Failed` ever reach the wire; `Creating` is never observable because `PUT` blocks until terminal |
| D16 | Cached images | `locations/{loc}/cachedImages` is always empty |

Also not implemented: virtual-network integration (`subnetIds`, `serviceAssociationLinks` are
echoed only), Log Analytics forwarding, GPU scheduling, confidential containers, `extensions`,
and list pagination (`nextLink` is never emitted).

The full specification — architecture, wire contract, Docker mapping, and test plan — is in
[the design set](../design/container-instances-index.md).
