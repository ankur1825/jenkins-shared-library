package kubernetes

# Support for both Pod and Deployment
containers[c] {
  input.kind == "Deployment"
  c := input.spec.template.spec.containers[_]
}

containers[c] {
  input.kind == "Pod"
  c := input.spec.containers[_]
}

# Extract image tag
image_tag(image) = tag {
  parts := split(image, ":")
  count(parts) == 2
  tag := parts[1]
}

image_tag(image) = "latest" {
  not contains(image, ":")
}

# Get name and kind
name := input.metadata.name
kind := input.kind