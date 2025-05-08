def call(Map params) {
    def credentialsId = params.CREDENTIALS_ID
    def appType = params.APP_TYPE
    def repoUrl = params.REPO_URL
    def branch = params.BRANCH
    def enableSonarQube = params.ENABLE_SONARQUBE ?: false
    def enableOpa = params.ENABLE_OPA ?: false
    if (!repoUrl) {
        error "Repository URL is missing. Please provide a valid URL."
    }
    stage('Clone shared library'){
        git credentialsId: credentialsId, url: 'https://github.com/ankur1825/jenkins-shared-library.git', branch: 'main'
    }

    // Stage to check dir and content
    stage('check current dir') {
        sh 'pwd'
        sh 'ls -lrth '
    }

    // Load and run the appropriate app-specific pipeline
    script {

        // @Library('jenkins-shared-library@main') _
        def pipelineScript = null

        if (appType == 'java') {
            pipelineScript = load 'jenkins_template/AppPipeline.groovy'
        } else if (appType == 'python') {
            pipelineScript = load 'jenkins_template/AppPipeline.groovy'
        } else if (appType == 'npm') {
            pipelineScript = load 'jenkins_template/AppPipeline.groovy'
        } else {
            error "Unsupported application type: ${appType}. Please choose 'java', 'python', or 'npm'."
        }

        pipelineScript.run(params)
    }
}
