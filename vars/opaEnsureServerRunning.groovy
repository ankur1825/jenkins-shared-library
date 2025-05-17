def call() {
    def isRunning = sh(
        script: "kubectl get pods -n horizon-relevance-dev -l app=opa --field-selector=status.phase=Running | grep opa || true",
        returnStatus: true
    ) == 0

    if (isRunning) {
        echo "OPA Server already running. Skipping deployment."
    } else {
        echo "Deploying OPA Server..."
        writeFile file: 'opa-deployment.yaml', text: libraryResource('opa/opa-deployment.yaml')

        sh '''
            kubectl apply -f opa-deployment.yaml
            echo "Waiting for OPA pod to be ready..."
            kubectl rollout status deployment/opa -n horizon-relevance-dev --timeout=60s
        '''
    }
}
