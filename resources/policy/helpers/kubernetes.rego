package kubernetes

# Return all containers in a Deployment or Pod
containers := [c | 
    obj := input
    containers := obj.spec.template.spec.containers
    some i
    c := containers[i]
]

# Check if label key exists
has_label(key) if {
    obj := input
    labels := obj.metadata.labels
    labels[key]
}

# Get label value
get_label(key) := val if {
    obj := input
    labels := obj.metadata.labels
    val := labels[key]
}
