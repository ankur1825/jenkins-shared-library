def call(Map params) {
    // Existing params (Self-Service CI/CD)
    def credentialsId   = params.CREDENTIALS_ID
    def appType         = params.APP_TYPE
    def repoUrl         = params.REPO_URL
    def branch          = params.BRANCH
    def enableSonarQube = params.ENABLE_SONARQUBE?.toBoolean() ?: false
    def enableOpa       = params.ENABLE_OPA?.toBoolean() ?: false
    def enableTrivy     = params.ENABLE_TRIVY?.toBoolean() ?: false

    // NEW: router for Devops Pipeline
    def pipelineKind = (params.PIPELINE_KIND ?: '').toString().trim()      // "DEVOPS" for the new service
    def serviceName  = (params.SERVICE_NAME ?: '').toString().trim()
    def isDevops     = pipelineKind.equalsIgnoreCase('DEVOPS') || serviceName.toLowerCase().contains('devops pipeline')

    if (!repoUrl?.trim()) {
        error "Repository URL is missing. Please provide a valid URL."
    }

    node {
        stage('Clone shared library') {
            git credentialsId: credentialsId, url: 'https://github.com/ankur1825/jenkins-shared-library.git', branch: 'main'
        }

        stage('Print Incoming Parameters') {
            script {
                echo "==== Incoming Parameters ===="
                // Print everything for easy debug
                params.each { k, v -> echo "${k} = ${v}" }
                echo "Router says isDevops = ${isDevops}"
                echo "============================="
            }
        }

        stage('Check Directory') {
            sh 'pwd'
            sh 'ls -lrth'
        }

        stage('Load and Run Pipeline') {
            def pipelineScript = load 'jenkins_template/AppPipeline.groovy'
            if (isDevops) {
                // NEW: Devops Pipeline entrypoint
                pipelineScript.runDevops(params)
            } else {
                // Existing flow (Self-Service CI/CD)
                pipelineScript.run(params)
            }
        }
    }
}
