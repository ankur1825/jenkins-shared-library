// vars/main_template.groovy
def call(Map params = [:]) {
    // ---- Normalize & read params (works for String "true"/"false" too)
    def str      = { it -> (it ?: '').toString().trim() }
    def asBool   = { it -> it instanceof Boolean ? it : str(it).equalsIgnoreCase('true') }

    def credentialsId   = str(params.CREDENTIALS_ID)
    def appType         = str(params.APP_TYPE)
    def repoUrl         = str(params.REPO_URL)
    def branch          = str(params.BRANCH ?: 'main')

    def enableSonarQube = asBool(params.ENABLE_SONARQUBE)
    def enableOpa       = asBool(params.ENABLE_OPA)
    def enableTrivy     = asBool(params.ENABLE_TRIVY)

    // Router for DevOps pipeline
    def pipelineKind = str(params.PIPELINE_KIND)
    def serviceName  = str(params.SERVICE_NAME)
    def isDevops     = pipelineKind.equalsIgnoreCase('DEVOPS') ||
                       serviceName.toLowerCase().contains('devops pipeline')
    def isProdDevops = pipelineKind.equalsIgnoreCase('PROD_DEVOPS') ||
                       serviceName.toLowerCase().contains('prod devops pipeline')

    if (!isProdDevops && !repoUrl) {
        error "Repository URL is missing. Please provide a valid URL."
    }

    node {
        stage('Print Incoming Parameters') {
            script {
                echo "==== Incoming Parameters ===="
                params.each { k, v ->
                    def sensitive = k.toString().toUpperCase().contains('TOKEN') ||
                                    k.toString().toUpperCase().contains('PASSWORD') ||
                                    k.toString().toUpperCase().contains('SECRET') ||
                                    k.toString().toUpperCase().contains('LICENSE_KEY') ||
                                    k.toString().toUpperCase().contains('SIGNATURE')
                    echo "${k} = ${sensitive ? '****' : v}"
                }
                echo "Router says isProdDevops = ${isProdDevops}"
                echo "Router says isDevops = ${isDevops}"
                echo "============================="
            }
        }

        stage('Check Workspace') {
            sh 'pwd'
            sh 'ls -lrth || true'
        }

        stage('Load and Run Pipeline') {
            // IMPORTANT: load from the shared library version (the one from @Library), not from workspace
            def tmpPath = '.loaded_AppPipeline.groovy'
            writeFile file: tmpPath, text: libraryResource('jenkins_template/AppPipeline.groovy')
            def pipelineScript = load tmpPath

            if (isProdDevops) {
                pipelineScript.runProdDevops(params)
            } else if (isDevops) {
                pipelineScript.runDevops(params)
            } else {
                pipelineScript.run(params)
            }
        }
    }
}
