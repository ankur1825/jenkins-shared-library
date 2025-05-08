// Refactor main_template.groovy to be callable as a function if needed
// Refactored main_template.groovy to be callable as a function
def call(Map params) {
    // Parameters passed to this function
    def appType = params.APP_TYPE
    def repoUrl = params.REPO_URL
    def branch = params.BRANCH
    def enableSonarQube = params.ENABLE_SONARQUBE ?: false
    def enableOpa = params.ENABLE_OPA ?: false
    def imageName = params.IMAGE_NAME ?: 'my-app'

    // Check if repository URL is provided
    if (!repoUrl) {
        error "Repository URL is missing. Please provide a valid URL."
    }

    // Stage to clone the repository
    stage('Clone Repository') {
        git url: repoUrl, branch: branch
    }

    // Stage to validate the repository type
    stage('Validate Repository') {
        script {
            def detectedType = ''

            // Detect the type of application based on file existence
            if (fileExists('pom.xml')) {
                detectedType = 'Java'
            } else if (fileExists('requirements.txt')) {
                detectedType = 'Python'
            } else if (fileExists('package.json')) {
                detectedType = 'NPM'
            } else {
                error "Unsupported or unknown repository type."
            }

            // Check if the app type matches the detected repository type
            if (appType != detectedType) {
                error "Mismatch: Selected app type is ${appType}, but repository seems to be ${detectedType}."
            }
        }
    }

    // Load specific pipeline based on the selected app type
    if (appType == 'Java') {
        load 'jenkins_template/javaPipeline.groovy'
    } else if (appType == 'Spring Boot') {
        load 'jenkins_template/springBootPipeline.groovy'
    } else if (appType == 'Python') {
        load 'jenkins_template/pythonPipeline.groovy'
    } else if (appType == 'Docker') {
        load 'jenkins_template/dockerPipeline.groovy'
    } else if (appType == 'NPM') {
        load 'jenkins_template/npmPipeline.groovy'
    } else if (appType == '.NET') {
        load 'jenkins_template/dotnetPipeline.groovy'
    } else {
        error 'Unsupported application type'
    }
}