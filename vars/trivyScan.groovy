def call(Map params = [:]) {
    // Validate required param
    if (!params.imageName) {
        error "Missing required parameter: imageName"
    }

    def imageName = params.imageName
    def uploadResults = params.get('uploadResults', true) // Default to true
    def appName = params.get('application', env.APP_NAME)
    def jenkinsJob = env.JOB_NAME
    def buildNumber = env.BUILD_NUMBER
    def requestedBy = params.get('requestedBy', currentBuild.getBuildCauses()[0]?.userId ?: "jenkins@horizonrelevance.com")
    def failOnSeverity = params.get('failOnSeverity', env.SECURITY_FAIL_ON_SEVERITY ?: 'CRITICAL,HIGH')
    def scanners = params.get('scanners', env.IMAGE_SECURITY_SCANNERS ?: 'vuln,secret,misconfig')
    def scanId = "b${buildNumber}-${UUID.randomUUID().toString().take(8)}".toLowerCase()
    def namespace = params.get('namespace', 'horizon-relevance-dev')

    echo "Starting image security analysis."
    echo "Scanning image: ${imageName}"
    echo "Blocking severities: ${failOnSeverity}"
    echo "Scanner set: ${scanners}"
    echo "Upload results: ${uploadResults}"
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
        set -e
        helm upgrade --install trivy-cli-scan-${scanId} ./trivy-scan-workdir \
            --namespace ${namespace} \
            --set-string scan.id=${scanId} \
            --set-string scan.imageName='${imageName}' \
            --set-string scan.uploadResults='${uploadResults}' \
            --set-string scan.application='${appName}' \
            --set-string scan.repoUrl='${params.repoUrl ?: ''}' \
            --set-string scan.jenkinsUrl='${env.BUILD_URL}' \
            --set-string scan.jenkinsJob='${jenkinsJob}' \
            --set-string scan.buildNumber='${buildNumber}' \
            --set-string scan.requestedBy='${requestedBy}' \
            --set-string scan.failOnSeverity='${failOnSeverity}' \
            --set-string scan.scanners='${scanners}'

        if ! kubectl wait --namespace ${namespace} \
            --for=condition=complete \
            job -l horizonrelevance.com/scan-id=${scanId} \
            --timeout=20m; then
            kubectl logs --namespace ${namespace} \
              -l horizonrelevance.com/scan-id=${scanId} \
              --all-containers=true --tail=-1 || true
            helm uninstall trivy-cli-scan-${scanId} --namespace ${namespace} || true
            exit 1
        fi

        kubectl logs --namespace ${namespace} \
          -l horizonrelevance.com/scan-id=${scanId} \
          --all-containers=true --tail=-1 || true

        helm uninstall trivy-cli-scan-${scanId} --namespace ${namespace} || true
    """

    // sh """
    //     helm upgrade --install trivy-cli-scan ./trivy_scanner/trivy-cli-scan \
    //         --namespace horizon-relevance-dev \
    //         --set scan.imageName=${imageName} \
    //         --set scan.uploadResults=${uploadResults}
    // """

    echo "Image security analysis completed for ${imageName}"
}
