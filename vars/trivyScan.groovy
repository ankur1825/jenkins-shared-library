def call(Map params = [:]) {
    // Validate required param
    if (!params.imageName) {
        error "Missing required parameter: imageName"
    }

    def imageName = params.imageName
    def uploadResults = params.get('uploadResults', true) // Default to true
    def appName = env.APP_NAME
    def jenkinsJob = env.JOB_NAME
    def buildNumber = env.BUILD_NUMBER
    def requestedBy = currentBuild.getBuildCauses()[0]?.userId ?: "jenkins@horizonrelevance.com"

    echo "Starting Trivy Scan..."
    echo "Scanning Image: ${imageName}"
    echo "Upload Results: ${uploadResults}"
    echo "Application: ${appName}"

    sh 'mkdir -p trivy-scan-workdir'
    dir('trivy-scan-workdir') {
        writeFile file: 'Chart.yaml', text: libraryResource('trivy_helm/trivy-cli-scan/Chart.yaml')
        writeFile file: 'values.yaml', text: libraryResource('trivy_helm/trivy-cli-scan/values.yaml')
        // Copy other necessary templates if needed
        sh 'mkdir -p templates'
        writeFile file: 'templates/job.yaml', text: libraryResource('trivy_helm/trivy-cli-scan/templates/job.yaml')
        // Add other files under templates/ as needed
    }

    sh """
        helm upgrade --install trivy-cli-scan ./trivy-scan-workdir \
            --namespace horizon-relevance-dev \
            --set scan.imageName=${imageName} \
            --set scan.uploadResults=${uploadResults} \
            --set scan.application=${appName} \
            --set scan.repoUrl=${params.repoUrl} \
            --set scan.jenkinsUrl=${env.BUILD_URL} \
            --set scan.jenkinsJob=${jenkinsJob} \
            --set scan.buildNumber=${buildNumber} \
            --set scan.requestedBy=${requestedBy}
    """

    // sh """
    //     helm upgrade --install trivy-cli-scan ./trivy_scanner/trivy-cli-scan \
    //         --namespace horizon-relevance-dev \
    //         --set scan.imageName=${imageName} \
    //         --set scan.uploadResults=${uploadResults}
    // """

    echo "✅ Trivy scan triggered successfully for ${imageName}"
}
