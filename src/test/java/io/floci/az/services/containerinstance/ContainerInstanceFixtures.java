package io.floci.az.services.containerinstance;

/** Request bodies and URL helpers shared by the container-instance tests. */
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

    /** One container that exits with a chosen code after a short run. Restart-policy tests. */
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
