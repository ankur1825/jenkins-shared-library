package kubernetes

object := input

containers := [c | object.spec.template.spec.containers[_] == c]

has_label(key) if {
    object.metadata.labels[key]
}

get_label(key) := val if {
    val := object.metadata.labels[key]
}
