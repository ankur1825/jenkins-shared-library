package kubernetes

default is_gatekeeper := false

is_gatekeeper if {
	has_field(input, "review")
	has_field(input.review, "object")
}

object := input if {
	not is_gatekeeper
}

object := input.review.object if {
	is_gatekeeper
}

format(msg) := gatekeeper_format if {
	is_gatekeeper
	gatekeeper_format := {"msg": msg}
}

format(msg) := msg if {
	not is_gatekeeper
}

name := object.metadata.name
kind := object.kind

is_service if {
	kind == "Service"
}

is_deployment if {
	kind == "Deployment"
}

is_daemonset if {
	kind == "DaemonSet"
}

is_pod if {
	kind == "Pod"
}

# ✅ FIXED: Safe image splitting
split_image(image) := result if {
	not contains(image, ":")
	result := [image, "latest"]
}

split_image(image) := result if {
	parts := split(image, ":")
	count(parts) == 2
	result := parts
}

pod_containers(pod) := all_containers if {
	keys := {"containers", "initContainers"}
	all_containers := [c | k := keys[_]; c := pod.spec[k][_]]
}

containers[container] if {
	pod := pods[_]
	all_containers := pod_containers(pod)
	container := all_containers[_]
}

containers[container] if {
	all_containers := pod_containers(object)
	container := all_containers[_]
}

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

volumes[volume] if {
	pod := pods[_]
	volume := pod.spec.volumes[_]
}

dropped_capability(container, cap) if {
	container.securityContext.capabilities.drop[_] == cap
}

added_capability(container, cap) if {
	container.securityContext.capabilities.add[_] == cap
}

has_field(obj, field) if {
	obj[field]
}

no_read_only_filesystem(c) if {
	not has_field(c, "securityContext")
}

no_read_only_filesystem(c) if {
	has_field(c, "securityContext")
	not has_field(c.securityContext, "readOnlyRootFilesystem")
}

priviledge_escalation_allowed(c) if {
	not has_field(c, "securityContext")
}

priviledge_escalation_allowed(c) if {
	has_field(c, "securityContext")
	has_field(c.securityContext, "allowPrivilegeEscalation")
}

canonify_cpu(orig) := new if {
	is_number(orig)
	new := orig * 1000
}

canonify_cpu(orig) := new if {
	not is_number(orig)
	endswith(orig, "m")
	new := to_number(replace(orig, "m", ""))
}

canonify_cpu(orig) := new if {
	not is_number(orig)
	not endswith(orig, "m")
	re_match("^[0-9]+$", orig)
	new := to_number(orig) * 1000
}

# Memory unit mapping
mem_multiple("E") := 1e21
mem_multiple("P") := 1e18
mem_multiple("T") := 1e15
mem_multiple("G") := 1e12
mem_multiple("M") := 1e9
mem_multiple("k") := 1e6
mem_multiple("") := 1e3
mem_multiple("m") := 1
mem_multiple("Ki") := 1024 * 1e3
mem_multiple("Mi") := 1024 * 1024 * 1e3
mem_multiple("Gi") := 1024 * 1024 * 1024 * 1e3
mem_multiple("Ti") := 1024 * 1024 * 1024 * 1024 * 1e3
mem_multiple("Pi") := 1024 * 1024 * 1024 * 1024 * 1024 * 1e3
mem_multiple("Ei") := 1024 * 1024 * 1024 * 1024 * 1024 * 1024 * 1e3

get_suffix(mem) := suffix if {
	is_string(mem)
	count(mem) > 1
	sub := substring(mem, count(mem) - 2, -1)
	mem_multiple(sub)
	suffix := sub
}

get_suffix(mem) := suffix if {
	is_string(mem)
	count(mem) > 0
	sub := substring(mem, count(mem) - 1, -1)
	mem_multiple(sub)
	suffix := sub
}

get_suffix(_) := ""  # fallback

canonify_mem(orig) := new if {
	is_number(orig)
	new := orig * 1000
}

canonify_mem(orig) := new if {
	not is_number(orig)
	suffix := get_suffix(orig)
	raw := replace(orig, suffix, "")
	re_match("^[0-9]+$", raw)
	new := to_number(raw) * mem_multiple(suffix)
}

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

workload_with_pod_template if {
	is_deployment
} else if {
	is_daemonset
}

has_readiness_probe(container) if {
	not is_null(container.readinessProbe)
}

has_liveness_probe(container) if {
	not is_null(container.livenessProbe)
}

is_null(value) if {
	value == null
}

has_secret_env_var(container) if {
	container.env[_].valueFrom.secretKeyRef
}

resolve_registry(image) := registry if {
	parts := split(image, "/")
	count(parts) > 1
	is_possible_registry(parts[0])
	registry := parts[0]
}

resolve_registry(_) := "unknown registry"

is_possible_registry(part) if {
	contains(part, ".")
} else if {
	part == "localhost"
} else if {
	contains(part, ":")
}

known_registry(registry) if {
	registry == trusted_registries[_]
}

trusted_registries := {
	"docker.io",
	"quay.io",
	"public.ecr.aws"
}

pod_replicas_lt_or_equal_one(replicas) if {
	replicas <= 1
}
