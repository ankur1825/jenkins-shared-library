package kubernetes

# Select the object depending on whether Gatekeeper-style input exists
object := input.review.object if input.review
object := input if not input.review

# Resource metadata
name := object.metadata.name
kind := object.kind

# Kinds
is_deployment if kind == "Deployment"
is_pod if kind == "Pod"

# Container access for both Pod and Deployment
containers[c] if {
    some i
    c := object.spec.template.spec.containers[i]
}

containers[c] if {
    some i
    c := object.spec.containers[i]
}

# Probes
has_readiness_probe(c) if c.readinessProbe
has_liveness_probe(c) if c.livenessProbe

# Image tag split
split_image(image) := parts if {
  parts := split(image, ":")
}

# Format message for Gatekeeper or raw Conftest
format(msg) := {"msg": msg} if input.review
format(msg) := msg if not input.review
