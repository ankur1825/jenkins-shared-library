package docker.security

default allow = true
default deny = []

# Deny images running as root user
deny[msg] if {
  input.root_user == true
  msg = "Docker image is running as root user. This is a critical risk."
}

# Deny images with detected secrets
deny[msg] if {
  input.secrets_detected == true
  msg = "Secrets detected in Docker image layers. Critical vulnerability."
}

# Deny images exposing sensitive ports
deny[msg] if {
  some port
  input.ports[_] == port
  port == 22
  msg = sprintf("SSH port (%v) exposed in image. Medium risk.", [port])
}

deny[msg] if {
  some port
  input.ports[_] == port
  port == 2375
  msg = sprintf("Docker Daemon port (%v) exposed in image. Critical risk.", [port])
}

allow if {
  not deny
}
