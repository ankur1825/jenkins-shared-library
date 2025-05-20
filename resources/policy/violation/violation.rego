package main

import data.kubernetes

violation[msg] {
  container := kubernetes.containers[_]
  parts := kubernetes.split_image(container.image)
  parts[1] == "latest"
  msg := kubernetes.format(sprintf("%s in the %s %s has an image, %s, using the latest tag", [container.name, kubernetes.kind, parts[0], kubernetes.name]))
}

violation[msg] {
  container := kubernetes.containers[_]
  not container.resources.limits.memory
  msg := kubernetes.format(sprintf("%s in the %s %s does not have a memory limit set", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  container := kubernetes.containers[_]
  not container.requests.limits.memory
  msg := kubernetes.format(sprintf("%s in the %s %s does not have requests memory limit set", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  container := kubernetes.containers[_]
  kubernetes.added_capability(container, "CAP_SYS_ADMIN")
  msg := kubernetes.format(sprintf("%s in the %s %s has SYS_ADMIN capabilties", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  container := kubernetes.containers[_]
  container.securityContext.privileged
  msg := kubernetes.format(sprintf("%s in the %s %s is privileged", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  container := kubernetes.containers[_]
  kubernetes.priviledge_escalation_allowed(container)
  msg := kubernetes.format(sprintf("%s in the %s %s allows priviledge escalation", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  container := kubernetes.containers[_]
  container.securityContext.runAsUser < 10000
  msg := kubernetes.format(sprintf("%s in the %s %s has a UID of less than 10000", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  pod := kubernetes.pods[_]
  pod.spec.hostAliases
  msg := kubernetes.format(sprintf("The %s %s is managing host aliases", [kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  pod := kubernetes.pods[_]
  pod.spec.hostIPC
  msg := kubernetes.format(sprintf("%s %s is sharing the host IPC namespace", [kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  pod := kubernetes.pods[_]
  pod.spec.hostNetwork
  msg := kubernetes.format(sprintf("The %s %s is connected to the host network", [kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  pod := kubernetes.pods[_]
  pod.spec.hostPID
  msg := kubernetes.format(sprintf("The %s %s is sharing the host PID", [kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  volume := kubernetes.volumes[_]
  volume.hostpath.path == "/var/run/docker.sock"
  msg := kubernetes.format(sprintf("The %s %s is mounting the Docker socket", [kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  kubernetes.is_deployment
  container := kubernetes.containers[_]
  not kubernetes.has_readiness_probe(container)
  msg := kubernetes.format(sprintf("The '%s' has container '%s' missing a readinessProbe block configuration", [kubernetes.kind, container.name]))
}

violation[msg] {
  kubernetes.is_deployment
  container := kubernetes.containers[_]
  not kubernetes.has_liveness_probe(container)
  msg := kubernetes.format(sprintf("%s in the %s %s is missing a livenessProbe block configuration", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  kubernetes.is_deployment
  container := kubernetes.containers[_]
  container.readinessProbe == container.livenessProbe
  msg := kubernetes.format(sprintf("%s in the %s %s has identical readinessProbe and livenessProbe block configurations", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] {
  kubernetes.is_deployment
  container := kubernetes.containers[_]
  registry := kubernetes.resolve_registry(container.image)
  not kubernetes.known_registry(registry)
  msg := kubernetes.format(sprintf("Container '%s' in %s '%s' uses an untrusted image source: %s from registry: %s", [container.name, kubernetes.kind, kubernetes.name, container.image, registry]))
}

violation[msg] {
  kubernetes.is_deployment
  replicas := input.spec.replicas
  kubernetes.pod_replicas_lt_or_equal_one(replicas)
  msg := kubernetes.format(sprintf("Deployment %s: replicas is %d. Replicas need to be more than 1 for High Availability guarantees", [kubernetes.name, replicas]))
}