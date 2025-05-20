package kubernetes

# Detect if input is from Gatekeeper (admission review)
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

is_service {
	kind == "Service"
}

is_deployment {
	kind == "Deployment"
}

is_daemonset {
	kind == "DaemonSet"
}

is_pod {
	kind == "Pod"
}

# Image splitting logic — FIXED
split_image(image) = [image, "latest"] {
	not contains(image, ":")
}

split_image(image) = [image_name, tag] {
	parts := split(image, ":")
	count(parts) == 2
	image_name := parts[0]
	tag := parts[1]
}

pod_containers(pod) = all_containers {
	keys := {"containers", "initContainers"}
	all_containers := [c | k := keys[_]; c := pod.spec[k][_]]
}

containers[container] {
	pod := pods[_]
	all_containers := pod_containers(pod)
	container := all_containers[_]
}

containers[container] {
	all_containers := pod_containers(object)
	container := all_containers[_]
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
	pod.spec.volumes
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
}

no_read_only_filesystem(c) {
	has_field(c, "securityContext")
	not has_field(c.securityContext, "readOnlyRootFilesystem")
}

priviledge_escalation_allowed(c) {
	not has_field(c, "securityContext")
}

priviledge_escalation_allowed(c) {
	has_field(c, "securityContext")
	has_field(c.securityContext, "allowPrivilegeEscalation")
}

canonify_cpu(orig) = new {
	is_number(orig)
	new := orig * 1000
}

canonify_cpu(orig) = new {
	not is_number(orig)
	endswith(orig, "m")
	new := to_number(replace(orig, "m", ""))
}

canonify_cpu(orig) = new {
	not is_number(orig)
	not endswith(orig, "m")
	re_match("^[0-9]+$", orig)
	new := to_number(orig) * 1000
}

mem_multiple("E") = 1000000000000000000000
mem_multiple("P") = 1000000000000000000
mem_multiple("T") = 1000000000000000
mem_multiple("G") = 1000000000000
mem_multiple("M") = 1000000000
mem_multiple("k") = 1000000
mem_multiple("") = 1000
mem_multiple("m") = 1
mem_multiple("Ki") = 1024000
mem_multiple("Mi") = 1048576000
mem_multiple("Gi") = 1073741824000
mem_multiple("Ti") = 1099511627776000
mem_multiple("Pi") = 1125899906842624000
mem_multiple("Ei") = 1152921504606846976000

get_suffix(mem) = suffix {
	is_string(mem)
	count(mem) > 1
	sub := substring(mem, count(mem) - 2, -1)
	mem_multiple(sub)
	suffix := sub
}

get_suffix(mem) = suffix {
	is_string(mem)
	count(mem) > 0
	sub := substring(mem, count(mem) - 1, -1)
	mem_multiple(sub)
	suffix := sub
}

get_suffix(_) = ""

canonify_mem(orig) = new {
	is_number(orig)
	new := orig * 1000
}

canonify_mem(orig) = new {
	not is_number(orig)
	suffix := get_suffix(orig)
	raw := replace(orig, suffix, "")
	re_match("^[0-9]+$", raw)
	new := to_number(raw) * mem_multiple(suffix)
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

is_null(value) {
	value == null
}

has_secret_env_var(container) {
	not is_null(container.env.secret)
}

resolve_registry(image) = registry {
	parts := split(image, "/")
	count(parts) > 1
	is_possible_registry(parts[0])
	registry := parts[0]
}

resolve_registry(_) = "unknown registry"

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
