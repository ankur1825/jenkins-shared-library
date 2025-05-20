package main
import data.kubernetes

deny[msg] {
  input.kind == "Deployment"
  not input.spec.template.spec.serviceAccountName
  msg := sprintf("%s %s has no serviceAccountName set", [kubernetes.kind, kubernetes.name])
}

deny[msg] {
  input.kind == "Deployment"
  not input.spec.selector.matchLabels["app"]
  msg := sprintf("%s %s is missing 'app' selector label", [kubernetes.kind, kubernetes.name])
}