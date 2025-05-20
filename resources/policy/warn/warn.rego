package main

import data.kubernetes

warn[msg] if {
  kubernetes.is_deployment
  not input.spec.template.spec.automountServiceAccountToken == false
  msg := kubernetes.format(sprintf("Deployment %s: automountServiceAccountToken is not disabled. Consider setting it to false to avoid unnecessary token exposure.", [kubernetes.name]))
}

warn[msg] if {
  kubernetes.containers[container]
  some env
  container.env[env].valueFrom.secretKeyRef
  msg := kubernetes.format(sprintf("Container %s in deployment %s references a secret as an environment variable. Mount secrets as volumes for better security.", [container.name, kubernetes.name]))
}

warn[msg] if {
  kubernetes.is_deployment
  not input.metadata.annotations["autoscaling.alpha.kubernetes.io/minReplicas"]
  msg := kubernetes.format(sprintf("Deployment %s does not define autoscaling annotations. Consider enabling HPA (Horizontal Pod Autoscaler) for workload flexibility.", [kubernetes.name]))
}
