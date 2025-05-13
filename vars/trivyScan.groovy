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

    sh """
        helm upgrade --install trivy-cli-scan ./trivy_scanner/trivy-cli-scan \
            --namespace horizon-relevance-dev \
            --set scan.imageName=${imageName} \
            --set scan.uploadResults=${uploadResults}
    """

    echo "✅ Trivy scan triggered successfully for ${imageName}"
}
