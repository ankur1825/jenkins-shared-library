package main

import data.kubernetes

violation contains msg if {
    container := kubernetes.containers[_]
    split(container.image, ":")[1] == "latest"
    msg := sprintf("Container '%s' is using the 'latest' image tag", [container.name])
}

violation contains msg if {
    container := kubernetes.containers[_]
    not container.livenessProbe
    msg := sprintf("Container '%s' does not have a livenessProbe", [container.name])
}
