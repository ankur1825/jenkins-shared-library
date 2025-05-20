package kubernetes

# Determine if the input is coming from Gatekeeper
is_gatekeeper if {
    has_field(input, "review")
    has_field(input.review, "object")
}

# Resolve the object under test
object = input if {
    not is_gatekeeper
}

object = input.review.object if {
    is_gatekeeper
}

# Format the violation message for Gatekeeper or standard
format(msg) = {"msg": msg} if is_gatekeeper
format(msg) = msg if not is_gatekeeper

# Common metadata references
name = object.metadata.name
kind = object.kind

# Workload types
is_service if kind == "Service"
is_deployment if kind == "Deployment"
is_daemonset if kind == "DaemonSet"
is_pod if kind == "Pod"

# Image parsing
split_image(image) = [image, "latest"] if not contains(image, ":")
split_image(image) = parts if {
    parts := split(image, ":")
    count(parts) == 2
}

# Collect containers
pod_containers(pod) = containers if {
    keys := {"containers", "initContainers"}
    containers := [c | k := keys[_]; c := pod.spec[k][_]]
}

containers[container] if {
    pod := pods[_]
    all := pod_containers(pod)
    container := all[_]
}

containers[container] if {
    all := pod_containers(object)
    container := all[_]
}

# Resolve pods from workload
pods[pod] if {
    is_daemonset
    pod := object.spec.template
}

pods[pod] if {
    is_deployment
    pod := object.spec.template
}

pods[pod] if {
    is_pod
    pod := object
}

# Volumes used
volumes[volume] {
  pod := pods[_]
  has_field(pod, "spec")
  has_field(pod.spec, "volumes")
  volume := pod.spec.volumes[_]
}

# Capability helpers
dropped_capability(container, cap) if container.securityContext.capabilities.drop[_] == cap
added_capability(container, cap) if container.securityContext.capabilities.add[_] == cap

# Field existence
has_field(obj, field) if obj[field]

# Read-only filesystem check
no_read_only_filesystem(c) if not has_field(c, "securityContext")
no_read_only_filesystem(c) if {
    has_field(c, "securityContext")
    not has_field(c.securityContext, "readOnlyRootFilesystem")
}

# Privilege escalation check
priviledge_escalation_allowed(c) if not has_field(c, "securityContext")
priviledge_escalation_allowed(c) if {
    has_field(c, "securityContext")
    has_field(c.securityContext, "allowPrivilegeEscalation")
}

# CPU normalization
canonify_cpu(orig) = new if {
    is_number(orig)
    new := orig * 1000
}
canonify_cpu(orig) = new if {
    endswith(orig, "m")
    new := to_number(replace(orig, "m", ""))
}
canonify_cpu(orig) = new if {
    not endswith(orig, "m")
    re_match("^[0-9]+$", orig)
    new := to_number(orig) * 1000
}

# Memory multipliers
mem_multiple := {
    "E": 1e21,
    "P": 1e18,
    "T": 1e15,
    "G": 1e12,
    "M": 1e9,
    "k": 1e6,
    "":  1e3,
    "m": 1,
    "Ki": 1024000,
    "Mi": 1048576000,
    "Gi": 1073741824000,
    "Ti": 1099511627776000,
    "Pi": 1125899906842624000,
    "Ei": 1152921504606846976000,
}

# Memory suffix detection
get_suffix(mem) = suffix if {
    is_string(mem)
    suffixes := ["Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "E", "P", "T", "G", "M", "k", "m", ""]
    suffix := suffixes[_]
    endswith(mem, suffix)
}

# Canonical memory conversion
canonify_mem(orig) = new if {
    is_number(orig)
    new := orig * 1000
}
canonify_mem(orig) = new if {
    suffix := get_suffix(orig)
    raw := replace(orig, suffix, "")
    re_match("^[0-9]+$", raw)
    new := to_number(raw) * mem_multiple[suffix]
}

# Label/selector helpers
required_deployment_labels if {
    object.metadata.labels["app.kubernetes.io/name"]
    object.metadata.labels["app.kubernetes.io/instance"]
    object.metadata.labels["app.kubernetes.io/version"]
    object.metadata.labels["app.kubernetes.io/component"]
    object.metadata.labels["app.kubernetes.io/part-of"]
    object.metadata.labels["app.kubernetes.io/managed-by"]
}

required_deployment_selectors if {
    object.spec.selector.matchLabels["app.kubernetes.io/name"]
    object.spec.selector.matchLabels["app.kubernetes.io/instance"]
}

# Template helper
workload_with_pod_template if is_deployment
workload_with_pod_template if is_daemonset

# Probe checks
has_readiness_probe(container) if not is_null(container.readinessProbe)
has_liveness_probe(container) if not is_null(container.livenessProbe)

# Null check
is_null(x) if x == null

# Secret reference check
has_secret_env_var(container) if {
    some i
    container.env[i].valueFrom.secretKeyRef
}

# Registry resolution
resolve_registry(image) = registry if {
    parts := split(image, "/")
    count(parts) > 1
    is_possible_registry(parts[0])
    registry := parts[0]
}
resolve_registry(_) = "unknown registry"

is_possible_registry(part) if contains(part, ".")
is_possible_registry(part) if part == "localhost"
is_possible_registry(part) if contains(part, ":")

known_registry(registry) if registry == trusted_registries[_]
trusted_registries := {}

# Replica check
pod_replicas_lt_or_equal_one(replicas) if replicas <= 1