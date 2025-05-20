package kubernetes

object := input

containers := [c | object.spec.template.spec.containers[_] == c]

has_label(key) = result {
    result := object.metadata.labels[key]
}