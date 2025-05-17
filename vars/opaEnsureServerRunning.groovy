def call() {
    echo "🔍 Checking if OPA is running in horizon-relevance-dev..."

    def opaPods = sh(
        script: "kubectl get pods -n horizon-relevance-dev -l app=opa --no-headers | grep Running || true",
        returnStdout: true
    ).trim()

    if (opaPods) {
        echo "✅ OPA Server is already running."
    } else {
        echo "🚀 OPA not found. Deploying it now..."

        writeFile file: 'opa-deployment.yaml', text: libraryResource('opa/opa-deployment.yaml')

        sh '''
            kubectl apply -f opa-deployment.yaml
            echo "⏳ Waiting for OPA to be ready..."
            kubectl rollout status deployment/opa -n horizon-relevance-dev --timeout=60s
        '''
    }
}
