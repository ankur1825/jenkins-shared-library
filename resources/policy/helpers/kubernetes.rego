package kubernetes

object := input

# Safely generate containers list using generator expression
containers[c] {
    some i
    c := object.spec.template.spec.containers[i]
}

# Check if a specific label key exists
has_label(key) {
    object.metadata.labels[key]
}

# Return the value of a label by key
get_label(key) := val {
    val := object.metadata.labels[key]
}
