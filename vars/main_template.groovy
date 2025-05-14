def call(Map params) {
    def credentialsId = params.CREDENTIALS_ID
    def appType = params.APP_TYPE
    def repoUrl = params.REPO_URL
    def branch = params.BRANCH
    def enableSonarQube = params.ENABLE_SONARQUBE.toBoolean() ?: false
    def enableOpa = params.ENABLE_OPA.toBoolean() ?: false
    def enableTrivy = params.ENABLE_TRIVY.toBoolean() ?: false
    if (!repoUrl) {
        error "Repository URL is missing. Please provide a valid URL."
    }

    node {

        stage('Clone shared library'){
            git credentialsId: credentialsId, url: 'https://github.com/ankur1825/jenkins-shared-library.git', branch: 'main'
        }

        stage('Print Incoming Parameters') {
            script {
                echo "==== Incoming Parameters ===="
                params.each { key, value ->
                    echo "${key} = ${value}"
                }
                echo "=============================="
            }
        }

        // Stage to check dir and content
        stage('check current dir') {
            sh 'pwd'
            sh 'ls -lrth '
        }

        // Load and run the appropriate app-specific pipeline
        stage('Load and Run Application Pipeline') {
            script {

                // @Library('jenkins-shared-library@main') _
                def pipelineScript =