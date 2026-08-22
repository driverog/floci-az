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
