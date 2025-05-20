package main
import data.kubernetes

warn[msg] {
  container := kubernetes.containers[_]
  some i
  container.env[i].valueFrom.secretKeyRef
  msg := sprintf("Container %s in %s %s has secrets as env vars", [container.name, kubernetes.kind, kubernetes.name])
}