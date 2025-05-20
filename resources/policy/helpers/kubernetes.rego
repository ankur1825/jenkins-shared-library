package kubernetes

default is_gatekeeper := false

is_gatekeeper {
    has_field(input, "review")
    has_field(input.review, "object")
}

object := input {
	not is_gatekeeper
}

object := input.review.object {
	is_gatekeeper
}

format(msg) := {"msg": msg} {
	is_gatekeeper
}

format(msg) := msg {
	not is_gatekeeper
}

name := object.metadata.name
kind := object.kind

is_service := kind == "Service"
is_deployment := kind == "Deployment"
is_daemonset := kind == "DaemonSet"
is_pod := kind == "Pod"

split_image(image) := [image, "latest"] {
	not contains(image, ":")
}

split_image(image) := parts {
	parts := split(image, ":")
	count(parts) == 2
}

pod_containers(pod) := all_containers {
	keys := {"containers", "initContainers"}
	all_containers := [c | k := keys[_]; c := pod.spec[k][_]]
}

containers[container] {
	pod := pods[_]
	all := pod_containers(pod)
	container := all[_]
}

containers[container] {
	all := pod_containers(object)
	container := all[_]
}

pods[pod] {
	is_daemonset
	pod := object.spec.template
}

pods[pod] {
	is_deployment
	pod := object.spec.template
}

pods[pod] {
	is_pod
	pod := object
}

volumes[volume] {
	pod := pods[_]
	volume := pod.spec.volumes[_]
}

dropped_capability(container, cap) {
	container.securityContext.capabilities.drop[_] == cap
}

added_capability(container, cap) {
	container.securityContext.capabilities.add[_] == cap
}

has_field(obj, field) {
	obj[field]
}

no_read_only_filesystem(c) {
	not has_field(c, "securityContext")
} else {
	has_field(c, "securityContext")
	not has_field(c.securityContext, "readOnlyRootFilesystem")
}

priviledge_escalation_allowed(c) {
	not has_field(c, "securityContext")
} else {
	has_field(c, "securityContext")
	has_field(c.securityContext, "allowPrivilegeEscalation")
}

canonify_cpu(orig) := new {
	is_number(orig)
	new := orig * 1000
}

canonify_cpu(orig) := new {
	endswith(orig, "m")
	new := to_number(replace(orig, "m", ""))
}

canonify_cpu(orig) := new {
	not endswith(orig, "m")
	re_match("^[0-9]+$", orig)
	new := to_number(orig) * 1000
}

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

get_suffix(mem) := suffix {
	is_string(mem)
	suffixes := ["Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "E", "P", "T", "G", "M", "k", "m", ""]
	suffix := suffixes[_]
	endswith(mem, suffix)
}

canonify_mem(orig) := new {
	is_number(orig)
	new := orig * 1000
}

canonify_mem(orig) := new {
	suffix := get_suffix(orig)
	raw := replace(orig, suffix, "")
	re_match("^[0-9]+$", raw)
	new := to_number(raw) * mem_multiple[suffix]
}

required_deployment_labels {
	object.metadata.labels["app.kubernetes.io/name"]
	object.metadata.labels["app.kubernetes.io/instance"]
	object.metadata.labels["app.kubernetes.io/version"]
	object.metadata.labels["app.kubernetes.io/component"]
	object.metadata.labels["app.kubernetes.io/part-of"]
	object.metadata.labels["app.kubernetes.io/managed-by"]
}

required_deployment_selectors {
	object.spec.selector.matchLabels["app.kubernetes.io/name"]
	object.spec.selector.matchLabels["app.kubernetes.io/instance"]
}

workload_with_pod_template {
	is_deployment
} else {
	is_daemonset
}

has_readiness_probe(container) {
	not is_null(container.readinessProbe)
}

has_liveness_probe(container) {
	not is_null(container.livenessProbe)
}

is_null(x) {
	x == null
}

has_secret_env_var(container) {
	some i
	container.env[i].valueFrom.secretKeyRef
}

resolve_registry(image) := registry {
	parts := split(image, "/")
	count(parts) > 1
	is_possible_registry(parts[0])
	registry := parts[0]
}

resolve_registry(_) := "unknown registry"

is_possible_registry(part) {
	contains(part, ".")
} else {
	part == "localhost"
} else {
	contains(part, ":")
}

known_registry(registry) {
	registry == trusted_registries[_]
}

trusted_registries := {}

pod_replicas_lt_or_equal_one(replicas) {
	replicas <= 1
}
