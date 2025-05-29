import groovy.json.JsonOutput

def call(Map params = [:]) {
    def configPath = 'config.json'
    echo "OPA Flag Received: ${params.ENABLE_OPA}"

    if (!fileExists(configPath)) {
        error "config.json not found in workspace!"
    }

    def userConfig = readJSON file: configPath

    def rawRepo = userConfig.imageRepo ?: userConfig.PRIVATE_REPO
    if (rawRepo) {
        def transformedRepo = rawRepo.replace(
            "docker-snapshot-local.kc.cernerrepos.net",
            "docker-snapshot.kc.cernerrepos.net"
        )
        def finalRepo = userConfig.AppName ? "${transformedRepo}/${userConfig.AppName}".toLowerCase() : transformedRepo

        userConfig.image = [
            repository : finalRepo,
            tag        : userConfig.tag ?: 'latest',
            pullPolicy : 'IfNotPresent'
        ]
    }

    userConfig.appName      = userConfig.AppName ?: userConfig.appName ?: 'default-app'
    userConfig.namespace    = userConfig.namespace ?: 'default'
    userConfig.replicaCount = userConfig.replicaCount ?: 1

    def jsonString = JsonOutput.toJson(userConfig)
    def prettyYaml = JsonOutput.prettyPrint(jsonString)
    writeFile file: 'custom-values.yaml', text: prettyYaml

    echo "Generated custom-values.yaml with all dynamic fields from config.json"

    def helmChartDir = "generic-helm"
    sh "mkdir -p ${helmChartDir}/templates"

    writeFile file: "${helmChartDir}/Chart.yaml", text: libraryResource('Helm-chart/Chart.yaml')
    writeFile file: "${helmChartDir}/values.yaml", text: libraryResource('Helm-chart/values.yaml')
    writeFile file: "${helmChartDir}/templates/_helpers.tpl", text: libraryResource('Helm-chart/templates/_helpers.tpl')
    writeFile file: "${helmChartDir}/templates/deployment.yaml", text: libraryResource('Helm-chart/templates/deployment.yaml')
    writeFile file: "${helmChartDir}/templates/service.yaml", text: libraryResource('Helm-chart/templates/service.yaml')

    if (userConfig.ingress?.enabled) {
        writeFile file: "${helmChartDir}/templates/ingress.yaml", text: libraryResource('Helm-chart/templates/ingress.yaml')
    }

    // 📦 OPA Scan - Kubernetes Rego
    if (params.ENABLE_OPA?.toBoolean()) {
        echo "🔍 Running OPA policy check for Kubernetes manifests..."

        sh 'mkdir -p opa-policies/helpers opa-policies/deny opa-policies/violation opa-policies/warn'

        writeFile file: 'opa-policies/deny/deny.rego', text: libraryResource('policy/deny/deny.rego')
        writeFile file: 'opa-policies/helpers/kubernetes.rego', text: libraryResource('policy/helpers/kubernetes.rego')
        writeFile file: 'opa-policies/violation/violation.rego', text: libraryResource('policy/violation/violation.rego')
        writeFile file: 'opa-policies/warn/warn.rego', text: libraryResource('policy/warn/warn.rego')

        // Ignore exit code for conftest, handle manually
        sh """
            helm template ${helmChartDir} > rendered.yaml
            conftest test rendered.yaml -p ./opa-policies --output json > opa-k8s-result.json || true
        """

        if (!fileExists('opa-k8s-result.json') || readFile('opa-k8s-result.json').trim() == '') {
            error("OPA policy scan output is empty or failed to parse.")
        }

        def opaResult = readJSON file: 'opa-k8s-result.json'
        def violations = []

        opaResult.each { entry ->
            entry.failures.each { failure ->
                violations << [
                    source: 'OPA-Kubernetes',
                    target: userConfig.appName,
                    package_name: 'OPA Policy',
                    installed_version: "N/A", 
                    vulnerability_id: failure.msg,
                    severity: failure.metadata?.severity?.toUpperCase() ?: 'HIGH',
                    risk_score: failure.metadata?.score ?: 70,
                    description: failure.metadata?.description ?: failure.msg,
                    fixed_version: failure.metadata?.remediation ?: 'N/A'
                ]
            }
        }

        if (violations.size() > 0) {
            def jobName = env.JOB_NAME ?: 'unknown'
            def buildNumber = env.BUILD_NUMBER ?: '0'
            def jenkinsUrl = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}"
            def opaPayload = [
                application: userConfig.appName,
                vulnerabilities: violations.collect { v ->
                    v + [
                        jenkins_job: jobName,
                        build_number: buildNumber,
                        jenkins_url: jenkinsUrl
                    ]
                }
            ]
            writeJSON file: 'opa-k8s-upload.json', json: opaPayload, pretty: 2
            sh "curl -s -X POST https://horizonrelevance.com/pipeline/api/opa/risks/ -H 'Content-Type: application/json' -d @opa-k8s-upload.json"
            error("OPA policy violations found in Helm/K8s manifests. Failing pipeline.")
        } else {
            echo "No OPA Kubernetes policy violations found."
        }
    } else {
        echo "OPA policy scan is disabled by configuration. Skipping."
    }    

    def releaseName = userConfig.appName.toLowerCase().replaceAll(/[^a-z0-9\-]/, '-')
    def ns = userConfig.namespace

    sh """
        set -e
        echo "Using IRSA for AWS authentication"
        aws sts get-caller-identity

        echo "Assuming namespace '${ns}' already exists and Jenkins has access"

        echo "Checking for stale resources from previous release..."
        kubectl delete deployment ${releaseName}-${releaseName} -n ${ns} --ignore-not-found || true
        kubectl delete service ${releaseName}-${releaseName} -n ${ns} --ignore-not-found || true

        echo "Checking if Helm release '${releaseName}' exists in namespace '${ns}'..."
        if helm status ${releaseName} --namespace ${ns} > /dev/null 2>&1; then
            echo "Release exists. Running helm upgrade..."
            helm upgrade ${releaseName} ${helmChartDir} -f custom-values.yaml --namespace ${ns}
        else
            echo "Release does not exist. Running helm install..."
            helm install ${releaseName} ${helmChartDir} -f custom-values.yaml --namespace ${ns}
        fi
    """

    echo "🚀 Helm deployment completed successfully."
}
