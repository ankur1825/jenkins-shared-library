package kubernetes

object := input

containers := [c | c := object.spec.template.spec.containers[_]]

has_label(key) := true {
    object.metadata.labels[key]
}