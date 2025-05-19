package docker.security

default allow := true

deny[msg] {
  input.root_user == true
  msg := "Docker image is running as root user. This is a critical risk."
}

deny[msg] {
  input.secrets_detected == true
  msg := "Secrets detected in Docker image layers. Critical vulnerability."
}

deny[msg] {
  some i
  port := input.ports[i]
  port == 22
  msg := sprintf("SSH port (%v) exposed in image. Medium risk.", [port])
}

deny[msg] {
  some i
  port := input.ports[i]
  port == 2375
  msg := sprintf("Docker Daemon port (%v) exposed in image. Critical risk.", [port])
}
