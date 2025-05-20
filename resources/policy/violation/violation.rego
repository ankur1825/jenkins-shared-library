package main

violation[msg] {
    container := input.spec.template.spec.containers[_]
    split(container.image, ":")[1] == "latest"
    msg := sprintf("%s is using the latest image tag", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    not container.resources.limits.memory
    msg := sprintf("%s is missing memory limit", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    not container.resources.requests.memory
    msg := sprintf("%s is missing memory requests", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    container.securityContext.privileged
    msg := sprintf("%s is running as privileged", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    container.securityContext.allowPrivilegeEscalation
    msg := sprintf("%s allows privilege escalation", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    container.securityContext.runAsUser < 10000
    msg := sprintf("%s is using UID less than 10000", [container.name])
}

violation[msg] if {
    input.spec.template.spec.hostAliases
    msg := "Pod is managing hostAliases"
}

violation[msg] if {
    input.spec.template.spec.hostIPC
    msg := "Pod is sharing host IPC namespace"
}

violation[msg] if {
    input.spec.template.spec.hostNetwork
    msg := "Pod is using hostNetwork"
}

violation[msg] if {
    input.spec.template.spec.hostPID
    msg := "Pod is sharing host PID"
}

violation[msg] if {
    volume := input.spec.template.spec.volumes[_]
    volume.hostPath.path == "/var/run/docker.sock"
    msg := "Pod is mounting Docker socket"
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    not container.readinessProbe
    msg := sprintf("%s is missing readinessProbe", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    not container.livenessProbe
    msg := sprintf("%s is missing livenessProbe", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    container.readinessProbe == container.livenessProbe
    msg := sprintf("%s has identical readiness and liveness probes", [container.name])
}

violation[msg] if {
    container := input.spec.template.spec.containers[_]
    registry := split(container.image, "/")[0]
    not registry == "docker.io"
    msg := sprintf("%s is using untrusted registry: %s", [container.name, registry])
}

violation[msg] if {
    input.spec.replicas <= 1
    msg := sprintf("Deployment has only %d replicas. Must be more than 1 for HA.", [input.spec.replicas])
}
