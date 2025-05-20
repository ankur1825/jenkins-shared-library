package kubernetes

default object = input

# Extract object depending on Gatekeeper or Conftest
object = input.review.object if input.review

# Metadata helpers
name = object.metadata.name
kind = object.kind

is_deployment if kind == "Deployment"
is_pod if kind == "Pod"

# Basic container iterator
containers[c] if object.spec.template.spec.containers[_] == c
containers[c] if object.spec.containers[_] == c

# Check readiness and liveness
has_readiness_probe(c) if c.readinessProbe
has_liveness_probe(c) if c.livenessProbe

# Parse image tag (e.g., "nginx:latest" -> ["nginx", "latest"])
split_image(image) = parts if {
  parts := split(image, ":")
}

# Format message for both OPA CLI and Gatekeeper
format(msg) = {"msg": msg} if input.review
format(msg) = msg if not input.review
