package main
import data.kubernetes

violation[msg] {
  container := kubernetes.containers[_]
  kubernetes.image_tag(container.image) == "latest"
  msg := sprintf("%s in %s %s is using the 'latest' image tag", [container.name, kubernetes.kind, kubernetes.name])
}

violation[msg] {
  container := kubernetes.containers[_]
  not container.resources.limits.memory
  msg := sprintf("%s in %s %s does not have memory limits", [container.name, kubernetes.kind, kubernetes.name])
}
