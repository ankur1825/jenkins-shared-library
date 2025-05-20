package violation

import data.kubernetes

violation[msg] {
    container := kubernetes.containers[_]
    not container.readinessProbe
    msg := sprintf("Container '%s' is missing a readinessProbe", [container.name])
}

violation[msg] {
    container := kubernetes.containers[_]
    not container.livenessProbe
    msg := sprintf("Container '%s' is missing a livenessProbe", [container.name])
}