package kubernetes

# Select object for conftest vs gatekeeper
object = input {
  not input.review
}

object = input.review.object {
  input.review
}

# Helpers
name = object.metadata.name
kind = object.kind

is_deployment if kind == "Deployment"
is_pod if kind == "Pod"

# Iterate over containers
containers[c] if object.spec.template.spec.containers[_] == c
containers[c] if object.spec.containers[_] == c

# Probes
has_readiness_probe(c) if c.readinessProbe
has_liveness_probe(c) if c.livenessProbe

# Image tag parser
split_image(image) = parts {
  parts := split(image, ":")
}

# Format message
format(msg) = {"msg": msg} {
  input.review
}

format(msg) = msg {
  not input.review
}
