package main

import data.kubernetes

violation[msg] if {
  kubernetes.containers[container]
  [image_name, tag] := kubernetes.split_image(container.image)
  tag == "latest"
  msg := kubernetes.format(sprintf("%s in the %s %s has an image, %s, using the latest tag", [container.name, kubernetes.kind, image_name, kubernetes.name]))
}

violation[msg] if {
  kubernetes.containers[container]
  not container.resources.limits.memory
  msg := kubernetes.format(sprintf("%s in the %s %s does not have a memory limit set", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.containers[container]
  not container.resources.requests.memory
  msg := kubernetes.format(sprintf("%s in the %s %s does not have a memory request set", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.containers[container]
  kubernetes.added_capability(container, "CAP_SYS_ADMIN")
  msg := kubernetes.format(sprintf("%s in the %s %s has SYS_ADMIN capabilities", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.containers[container]
  container.securityContext.privileged == true
  msg := kubernetes.format(sprintf("%s in the %s %s is privileged", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.containers[container]
  kubernetes.priviledge_escalation_allowed(container)
  msg := kubernetes.format(sprintf("%s in the %s %s allows privilege escalation", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.containers[container]
  container.securityContext.runAsUser < 10000
  msg := kubernetes.format(sprintf("%s in the %s %s has a UID of less than 10000", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.pods[pod]
  pod.spec.hostAliases
  msg := kubernetes.format(sprintf("The %s %s is managing host aliases", [kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.pods[pod]
  pod.spec.hostIPC
  msg := kubernetes.format(sprintf("%s %s is sharing the host IPC namespace", [kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.pods[pod]
  pod.spec.hostNetwork
  msg := kubernetes.format(sprintf("The %s %s is connected to the host network", [kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.pods[pod]
  pod.spec.hostPID
  msg := kubernetes.format(sprintf("The %s %s is sharing the host PID", [kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.volumes[volume]
  volume.hostPath.path == "/var/run/docker.sock"
  msg := kubernetes.format(sprintf("The %s %s is mounting the Docker socket", [kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.is_deployment
  kubernetes.containers[container]
  not kubernetes.has_readiness_probe(container)
  msg := kubernetes.format(sprintf("The '%s' has container '%s' missing a readinessProbe block configuration", [kubernetes.kind, container.name]))
}

violation[msg] if {
  kubernetes.is_deployment
  kubernetes.containers[container]
  not kubernetes.has_liveness_probe(container)
  msg := kubernetes.format(sprintf("%s in the %s %s is missing a livenessProbe block configuration", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.is_deployment
  kubernetes.containers[container]
  container.readinessProbe == container.livenessProbe
  msg := kubernetes.format(sprintf("%s in the %s %s has identical readinessProbe and livenessProbe block configurations", [container.name, kubernetes.kind, kubernetes.name]))
}

violation[msg] if {
  kubernetes.is_deployment
  kubernetes.containers[container]
  registry := kubernetes.resolve_registry(container.image)
  not kubernetes.known_registry(registry)
  msg := kubernetes.format(sprintf("Container '%s' in %s '%s' uses an untrusted image source: %s from registry: %s", [container.name, kubernetes.kind, input.metadata.name, container.image, registry]))
}

violation[msg] if {
  kubernetes.is_deployment
  replicas := input.spec.replicas
  kubernetes.pod_replicas_lt_or_equal_one(replicas)
  msg := kubernetes.format(sprintf("Deployment %s: replicas is %d. Replicas need to be more than 1 for High Availability guarantees", [kubernetes.name, replicas]))
}
