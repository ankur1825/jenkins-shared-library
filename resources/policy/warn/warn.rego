package main

import data.kubernetes

warn[msg] if {
    container := kubernetes.containers[_]
    not container.readinessProbe
    msg := sprintf("Container '%s' does not have a readinessProbe", [container.name])
}
