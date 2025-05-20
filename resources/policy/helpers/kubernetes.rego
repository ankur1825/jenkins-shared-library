package kubernetes

default object := input

# Return all containers in a Deployment or Pod
containers := [c | 
    some i
    c := object.spec.template.spec.containers[i]
]

# Check if label key exists
has_label(key) if {
    object.metadata.labels[key]
}

# Get label value
get_label(key) := val if {
    val := object.metadata.labels[key]
}
