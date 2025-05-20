package kubernetes

object := input

containers := [c | object.spec.template.spec.containers[_] == c]

has_label(result) if {
    result := object.metadata.labels["app"]
}