def call(Map params = [:]) {
    // Validate required param
    if (!params.imageName) {
        error "Missing required parameter: imageName"
    }

    def imageName = params.imageName
    def uploadResults = params.get('uploadResults', true) // Default to true

    echo "Starting Trivy Scan..."
    echo "Scanning Image: ${imageName}"
    echo "Upload Results: ${uploadResults}"

    sh 'mkdir -p trivy-scan-workdir'
    dir('trivy-scan-workdir') {
        writeFile file: 'Chart.yaml', text: libraryResource('trivy_helm/trivy-cli-scan/Chart.yaml')
        writeFile file: 'values.yaml', text: libraryResource('trivy_helm/trivy-cli-scan/values.yaml')
        // Copy other necessary templates if needed
        sh 'mkdir -p templates'
        writeFile file: 'templates/deployment.yaml', text: libraryResource('trivy_helm/trivy-cli-scan/templates/deployment.yaml')
        // Add other files under templates/ as needed
    }

    sh """
        helm upgrade --install trivy-cli-scan ./trivy-scan-workdir \
            --namespace horizon-relevance-dev \
            --set scan.imageName=${imageName} \
            --set scan.uploadResults=${uploadResults}
    """

    // sh """
    //     helm upgrade --install trivy-cli-scan ./trivy_scanner/trivy-cli-scan \
    //         --namespace horizon-relevance-dev \
    //         --set scan.imageName=${imageName} \
    //         --set scan.uploadResults=${uploadResults}
    // """

    echo "✅ Trivy scan triggered successfully for ${imageName}"
}
