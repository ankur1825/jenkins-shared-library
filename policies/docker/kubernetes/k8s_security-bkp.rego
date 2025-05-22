package kubernetes.security

default allow = true
default deny = []

# Deny pods with privileged escalation
deny[msg] {
  input.spec.containers[_].securityContext.privileged == true
  msg := "Privileged container found. High security risk."
}

# Deny containers running as root
deny[msg] {
  input.spec.containers[_].securityContext.runAsUser == 0
  msg := "Container running as root user (UID 0). Critical risk."
}

# Deny deployments missing resource limits
deny[msg] {
  not input.spec.containers[_].resources.limits.cpu
  msg := "Container missing CPU resource limit. Best practice violation."
}

deny[msg] {
  not input.spec.containers[_].resources.limits.memory
  msg := "Container missing Memory resource limit. Best practice violation."
}

allow {
  not deny
}
