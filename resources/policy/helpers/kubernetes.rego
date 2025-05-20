package kubernetes

# Detect if input is wrapped (Gatekeeper) or raw (Conftest)
default is_gatekeeper := false

is_gatekeeper := true {
  input.review.object
}

object := input {
  not is_gatekeeper
}

object := input.review.object {
  is_gatekeeper
}

# Format message based on context
format(msg) := output {
  is_gatekeeper
  output := {"msg": msg}
}

format(msg) := msg {
  not is_gatekeeper
}

# Common fields
name := object.metadata.name
kind := object.kind

# Basic kind checks
is_deployment := kind == "Deployment"
is_pod := kind == "Pod"

# Extract containers
containers[container] {
  is_pod
  container := object.spec.containers[_]
}

containers[container] {
  is_deployment
  container := object.spec.template.spec.containers[_]
}

# Check for image using latest tag
split_image(image) := [_, tag] {
  parts := split(image, ":")
  count(parts) == 2
  tag := parts[1]
}

split_image(image) := [_, "latest"] {
  not contains(image, ":")
}

# Registry resolution (simple version)
resolve_registry(image) := registry {
  parts := split(image, "/")
  count(parts) > 1
  registry := parts[0]
}

resolve_registry(_) := "unknown"

# Simple memory suffix extractor (supports "Mi" style)
get_suffix(mem) := suffix {
  endswith(mem, suffix)
  suffix := "Mi"
}

# Memory parsing
canonify_mem(mem) := val {
  suffix := get_suffix(mem)
  raw := replace(mem, suffix, "")
  val := to_number(raw) * 1048576
}

# Field check
has_field(obj, field) {
  obj[field]
}

# Liveness and readiness probe check
has_readiness_probe(container) {
  container.readinessProbe
}

has_liveness_probe(container) {
  container.livenessProbe
}