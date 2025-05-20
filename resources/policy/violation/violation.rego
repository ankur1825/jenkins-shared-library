package main

import data.kubernetes

name := input.metadata.name
kind := input.kind

violation[msg] {
  kubernetes.containers[container]
  [image_name, "latest"] := split(container.image, ":")
  msg := sprintf("%s in the %s %s has an image, %s, using the latest tag", [container.name, kind, name, image_name])
}

violation[msg] {
  kubernetes.containers[container]
  not container.resources.limits.memory
  msg := sprintf("%s in the %s %s does not have a memory limit set", [container.name, kind, name])
}

violation[msg] {
  kubernetes.containers[container]
  not container.resources.requests.memory
  msg := sprintf("%s in the %s %s does not have requests memory limit set", [container.name, kind, name])
}

violation[msg] {
  kubernetes.containers[container]
  container.securityContext.privileged == true
  msg := sprintf("%s in the %s %s is privileged", [container.name, kind, name])
}

violation[msg] {
  kubernetes.containers[container]
  container.securityContext.allowPrivilegeEscalation == true
  msg := sprintf("%s in the %s %s allows privilege escalation", [container.name, kind, name])
}

violation[msg] {
  kubernetes.containers[container]
  container.securityContext.runAsUser < 10000
  msg := sprintf("%s in the %s %s has a UID of less than 10000", [container.name, kind, name])
}

violation[msg] {
  kubernetes.pods[pod]
  pod.spec.hostAliases
  msg := sprintf("The %s %s is managing host aliases", [kind, name])
}

violation[msg] {
  kubernetes.pods[pod]
  pod.spec.hostIPC
  msg := sprintf("%s %s is sharing the host IPC namespace", [kind, name])
}

violation[msg] {
  kubernetes.pods[pod]
  pod.spec.hostNetwork
  msg := sprintf("The %s %s is connected to the host network", [kind, name])
}

violation[msg] {
  kubernetes.pods[pod]
  pod.spec.hostPID
  msg := sprintf("The %s %s is sharing the host PID", [kind, name])
}

violation[msg] {
  kubernetes.volumes[volume]
  volume.hostPath.path == "/var/run/docker.sock"
  msg := sprintf("The %s %s is mounting the Docker socket", [kind, name])
}

violation[msg] {
  kubernetes.is_deployment
  kubernetes.containers[container]
  not container.readinessProbe
  msg := sprintf("The '%s' has container '%s' missing a readinessProbe block configuration", [kind, container.name])
}

violation[msg] {
  kubernetes.is_deployment
  kubernetes.containers[container]
  not container.livenessProbe
  msg := sprintf("%s in the %s %s is missing a livenessProbe block configuration", [container.name, kind, name])
}

violation[msg] {
  kubernetes.is_deployment
  kubernetes.containers[container]
  container.readinessProbe == container.livenessProbe
  msg := sprintf("%s in the %s %s has identical readinessProbe and livenessProbe block configurations", [container.name, kind, name])
}

violation[msg] {
  kubernetes.is_deployment
  kubernetes.containers[container]
  registry := split(container.image, "/")[0]
  not registry == "docker.io"
  msg := sprintf("Container '%s' in %s '%s' uses an untrusted image source from registry: %s", [container.name, kind, name, registry])
}

violation[msg] {
  kubernetes.is_deployment
  replicas := input.spec.replicas
  replicas <= 1
  msg := sprintf("Deployment %s: replicas is %d. Replicas need to be more than 1 for High Availability guarantees", [name, replicas])
}