package deny

import data.kubernetes

deny[msg] {
    not kubernetes.has_label("app")
    msg := "Missing required label: app"
}

deny[msg] {
    container := kubernetes.containers[_]
    not container.resources
    msg := sprintf("Container '%s' has no resource limits defined", [container.name])
}