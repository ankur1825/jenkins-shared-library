def call(Map params) {
    def credentialsId = params.CREDENTIALS_ID
    def appType = params.APP_TYPE
    def repoUrl = params.REPO_URL
    def branch = params.BRANCH
    def enableSonarQube = params.ENABLE_SONARQUBE.toBoolean() ?: false
    def enableOpa = params.ENABLE_OPA.toBoolean() ?: false
    def enableTrivy = params.ENABLE_TRIVY.toBoolean() ?: false

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
                echo "APP_TYPE = ${appType}"
                echo "REPO_URL = ${repoUrl}"
                echo "BRANCH = ${branch}"
                echo "ENABLE_SONARQUBE = ${enableSonarQube}"
                echo "ENABLE_OPA = ${enableOpa}"
                echo "ENABLE_TRIVY = ${enableTrivy}"
                echo "=============================="
            }
        }

        stage('Check Directory') {
            sh 'pwd'
            sh 'ls -lrth'
        }

        stage('Load and Run Application Pipeline') {
            def pipelineScript = load 'jenkins_template/AppPipeline.groovy'
            pipelineScript.run(params)  // still pass params here for compatibility
        }
    }
}





// def call(Map params) {
//     def credentialsId = params.CREDENTIALS_ID
//     def appType = params.APP_TYPE
//     def repoUrl = params.REPO_URL
//     def branch = params.BRANCH
//     def enableSonarQube = params.ENABLE_SONARQUBE.toBoolean() ?: false
//     def enableOpa = params.ENABLE_OPA.toBoolean() ?: false
//     def enableTrivy = params.ENABLE_TRIVY.toBoolean() ?: false
//     if (!repoUrl) {
//         error "Repository URL is missing. Please provide a valid URL."
//     }

//     node {

//         stage('Clone shared library'){
//             git credentialsId: credentialsId, url: 'https://github.com/ankur1825/jenkins-shared-library.git', branch: 'main'
//         }

//         stage('Print Incoming Parameters') {
//             script {
//                 echo "==== Incoming Parameters ===="
//                 params.each { key, value ->
//                     echo "${key} = ${value}"
//                 }
//                 echo "=============================="
//             }
//         }

//         // Stage to check dir and content
//         stage('check current dir') {
//             sh 'pwd'
//             sh 'ls -lrth '
//         }

//         // Load and run the appropriate app-specific pipeline
//         stage('Load and Run Application Pipeline') {
//             script {

//                 // @Library('jenkins-shared-library@main') _
//                 def pipelineScript = null

//                 if (appType == 'java') {
//                     pipelineScript = load 'jenkins_template/AppPipeline.groovy'
//                 } else if (appType == 'python') {
//                     pipelineScript = load 'jenkins_template/AppPipeline.groovy'
//                 } else if (appType == 'npm') {
//                     pipelineScript = load 'jenkins_template/AppPipeline.groovy'
//                 } else {
//                     error "Unsupported application type: ${appType}. Please choose 'java', 'python', or 'npm'."
//                 }

//                 pipelineScript.run(params)
//             }
//         }
//     }
// }        
