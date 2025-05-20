package main

import data.kubernetes

name := input.metadata.name
kind := input.kind

deny[msg] if {
    kubernetes.is_deployment
    not kubernetes.required_deployment_selectors
    msg := sprintf("Deployment %s must provide app/release labels for pod selectors", [name])
}

deny[msg] if {
    kubernetes.is_deployment
    not input.spec.template.spec.nodeSelector.agentpool == "user"
    msg := sprintf("Deployment %s must declare agentpool nodeSelector as 'user' for node pool selection", [name])
}

deny[msg] if {
    kubernetes.is_deployment
    not kubernetes.required_deployment_labels
    msg := sprintf("%s must include Kubernetes recommended labels: https://kubernetes.io/docs/concepts/overview/working-with-objects/common-labels/#labels", [name])
}

deny[msg] if {
    kubernetes.workload_with_pod_template
    not input.spec.template.spec.serviceAccountName
    msg := sprintf("%s must specify a serviceAccountName in the template", [kind])
}

# Deny containers running as root
deny[msg] if {
    kubernetes.containers[container]
    not container.securityContext.runAsNonRoot
    msg := sprintf("Container %s in %s must not run as root. Set 'runAsNonRoot: true'", [container.name, name])
}

# Deny use of latest tag
deny[msg] if {
    kubernetes.containers[container]
    [image_name, tag] := split(container.image, ":")
    tag == "latest"
    msg := sprintf("Container %s in %s is using the 'latest' image tag: %s", [container.name, name, tag])
}


# Optional: Uncomment and update as needed
# deny[msg] if {
#     kubernetes.is_deployment
#     not input.spec.template.spec.securityContext.runAsNonRoot
#     msg := sprintf("Containers must not run as root in Deployment %s", [name])
# }
