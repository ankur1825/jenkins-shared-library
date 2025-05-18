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
  msg = "S