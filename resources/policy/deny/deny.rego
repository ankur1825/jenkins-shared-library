package main

import data.kubernetes

deny contains msg if {
    not kubernetes.has_label("app")
    msg := "Deployment is missing 'app' label"
}

deny contains msg if {
    container := kubernetes.containers[_]
    not container.resources.limits
    msg := sprintf("Container '%s' is missing resource limits", [container.name])
}
