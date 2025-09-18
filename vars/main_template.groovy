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
    def pipelineKind = str(params.PIPELINE_KIND)             // expect "DEVOPS"
    def serviceName  = str(params.SERVICE_NAME)
    def isDevops     = pipelineKind.equalsIgnoreCase('DEVOPS') ||
                       serviceName.toLowerCase().contains('devops pipeline')

    if (!repoUrl) {
        error "Repository URL is missing. Please provide a valid URL."
    }

    node {
        stage('Print Incoming Parameters') {
            script {
                echo "==== Incoming Parameters ===="
                params.each { k, v -> echo "${k} = ${v}" }
                echo "Router says isDevops = ${isDevops}"
                echo "============================="
            }
        }

        // Optional: you don't need to clone the shared library again here,
        // but if you have extra templates/assets in the repo root, keep this.
        stage('Clone shared library (optional)') {
            checkout([$class: 'GitSCM',
                userRemoteConfigs: [[credentialsId: credentialsId, url: 'https://github.com/ankur1825/jenkins-shared-library.git']],
                branches: [[name: '*/main']],
                doGenerateSubmoduleConfigurations: false
            ])
        }

        stage('Check Workspace') {
            sh 'pwd && ls -lrth'
        }

        stage('Load and Run Pipeline') {
            // Load returns a Script (not a global step). We must call methods on it.
            def app = load 'jenkins_template/AppPipeline.groovy'

            // Guardrails: make sure the functions exist
            def hasRun       = app.metaClass.getMetaMethod('run', Map)
            def hasRunDevops = app.metaClass.getMetaMethod('runDevops', Map)

            if (isDevops) {
                if (!hasRunDevops) {
                    error "AppPipeline.groovy does not define runDevops(Map). " +
                          "Create jenkins_template/AppPipeline.groovy with def runDevops(Map params) { ... }"
                }
                app.runDevops(params)
            } else {
                if (!hasRun) {
                    error "AppPipeline.groovy does not define run(Map). " +
                          "Create jenkins_template/AppPipeline.groovy with def run(Map params) { ... }"
                }
                app.run(params)
            }
        }
    }
}
