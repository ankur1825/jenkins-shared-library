package kubernetes.security

default allow := true

deny[msg] {
    some i
    input.spec.containers[i].securityContext.privileged == true
    msg := "Privileged container found. High security risk."
}

deny[msg] {
    some i
    input.spec.containers[i].securityContext.runAsUser == 0
    msg := "Container running as root user (UID 0). Critical risk."
}

deny[msg] {
    some i
    not input.spec.containers[i].resources.limits.cpu
    msg := sprintf("Container %d is missing CPU resource limit. Best practice violation.", [i])
}

deny[msg] {
    some i
    not input.spec.containers[i].resources.limits.memory
    msg := sprintf("Container %d is missing Memory resource limit. Best practice violation.", [i])
}

allow {
    not deny
}
