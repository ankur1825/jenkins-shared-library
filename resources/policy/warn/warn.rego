package warn

import data.kubernetes

warn[msg] {
    container := kubernetes.containers[_]
    container.image == "nginx:latest"
    msg := sprintf("Container '%s' is using a potentially unstable image tag 'latest'", [container.name])
}