package kubernetes

object := input

name := object.metadata.name
kind := object.kind

is_workload if kind == "Deployment"
is_workload if kind == "StatefulSet"
is_workload if kind == "DaemonSet"
is_workload if kind == "ReplicaSet"
is_workload if kind == "Job"
is_workload if kind == "CronJob"
is_pod if kind == "Pod"
is_service if kind == "Service"
is_ingress if kind == "Ingress"

pod_spec := object.spec if is_pod
pod_spec := object.spec.template.spec if {
    is_workload
    kind != "CronJob"
}
pod_spec := object.spec.jobTemplate.spec.template.spec if kind == "CronJob"

containers contains c if {
    c := pod_spec.containers[_]
}

containers contains c if {
    c := pod_spec.initContainers[_]
}

volumes contains v if {
    v := pod_spec.volumes[_]
}

has_label(key) if {
    object.metadata.labels[key]
}

has_annotation(key) if {
    object.metadata.annotations[key]
}

get_label(key) := val if {
    val := object.metadata.labels[key]
}

container_name(container) := name if {
    name := container.name
}

container_name(container) := "unnamed" if {
    not container.name
}

image_tag(image) := tag if {
    path_parts := split(image, "/")
    last := path_parts[count(path_parts) - 1]
    contains(last, ":")
    tag_parts := split(last, ":")
    tag := tag_parts[count(tag_parts) - 1]
}

image_tag(image) := "latest" if {
    path_parts := split(image, "/")
    last := path_parts[count(path_parts) - 1]
    not contains(last, ":")
}

image_registry(image) := registry if {
    parts := split(image, "/")
    count(parts) > 1
    is_registry(parts[0])
    registry := parts[0]
}

image_registry(_) := "docker.io" if true

is_registry(part) if contains(part, ".")
is_registry(part) if contains(part, ":")
is_registry(part) if part == "localhost"

approved_registry(registry) if {
    registry == "docker.io"
}

approved_registry(registry) if {
    registry == "public.ecr.aws"
}

approved_registry(registry) if {
    endswith(registry, ".dkr.ecr.us-east-1.amazonaws.com")
}

is_true(value) if value == true
is_false(value) if value == false
