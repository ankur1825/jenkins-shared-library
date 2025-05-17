package docker.security

default deny = []

deny[msg] {
  input.root_user == true
  msg := "Image runs as root user."
}

deny[msg] {
  input.secrets_detected == true
  msg := "Secrets detected inside image layers."
}

deny[msg] {
  some port
  input.ports[port]
  port == 22
  msg := "SSH port (22) exposed."
}
