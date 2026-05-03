import groovy.json.JsonOutput

def call(Map params = [:]) {
    def configPath = 'config.json'
    echo "Policy validation enabled: ${params.ENABLE_OPA}"

    def userConfig
    if (fileExists(configPath)) {
        userConfig = readJSON file: configPath
    } else {
        echo "config.json not found; generating deployment config from pipeline metadata."
        def imageUri = (env.IMAGE_URI ?: params.IMAGE_URI ?: '').toString().trim()
        def imageRepo = imageUri
        def imageTag = (env.TAG ?: params.TARGET_IMAGE_TAG ?: 'latest').toString().split(',')[0].trim()
        if (imageUri.contains('@sha256:')) {
            imageTag = ''
        }
        userConfig = [
            appName     : params.PROJECT_NAME ?: 'default-app',
            replicaCount: 1,
            image       : [
                repository: imageRepo ?: 'nginx',
                tag       : imageTag,
                pullPolicy: 'IfNotPresent'
            ],
            service     : [type: 'ClusterIP', port: 80],
            env         : [:],
            envFromSecrets: [],
            volumeMounts: [],
            volumes     : [],
            ingress     : [enabled: false]
        ]
    }

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

    userConfig.appName      = safeName(userConfig.AppName ?: userConfig.appName ?: params.PROJECT_NAME ?: 'default-app')
    def deployTarget        = resolveDeployTarget(params, userConfig)
    userConfig.namespace    = deployTarget.namespace
    userConfig.replicaCount = userConfig.replicaCount ?: 1

    writeFile file: 'custom-values.yaml', text: JsonOutput.prettyPrint(JsonOutput.toJson(userConfig))
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

    // OPA Scan - Kubernetes Policies
    if (params.ENABLE_OPA?.toBoolean()) {
        echo "Running policy validation for Kubernetes manifests."

        sh 'mkdir -p opa-policies/helpers opa-policies/deny opa-policies/violation opa-policies/warn'

        writeFile file: 'opa-policies/deny/deny.rego', text: libraryResource('policy/deny/deny.rego')
        writeFile file: 'opa-policies/helpers/kubernetes.rego', text: libraryResource('policy/helpers/kubernetes.rego')
        writeFile file: 'opa-policies/violation/violation.rego', text: libraryResource('policy/violation/violation.rego')
        writeFile file: 'opa-policies/warn/warn.rego', text: libraryResource('policy/warn/warn.rego')

        sh """
            helm template ${helmChartDir} > rendered.yaml
            set +e
            conftest test rendered.yaml -p ./opa-policies --output json > opa-k8s-result.json
            echo \$? > opa-k8s-exit-code.txt
            set -e
        """

        if (!fileExists('opa-k8s-result.json') || readFile('opa-k8s-result.json').trim() == '') {
            error("OPA policy scan output is empty or failed to parse.")
        }

        def opaResult = readJSON file: 'opa-k8s-result.json'
        def violations = []

        opaResult.each { entry ->
            (entry.failures ?: []).each { failure ->
                violations << [
                    source: 'OPA-Kubernetes',
                    target: userConfig.appName,
                    package_name: 'OPA Policy',
                    installed_version: "N/A",
                    violation: failure.msg?.toString() ?: "OPA violation occurred",
                    severity: failure.metadata?.severity?.toUpperCase() ?: 'HIGH',
                    risk_score: failure.metadata?.score ?: 70,
                    description: failure.metadata?.description ?: failure.msg?.toString(),
                    remediation: failure.metadata?.remediation ?: 'N/A'
                ]
            }
        }

        def conftestExitCode = readFile('opa-k8s-exit-code.txt').trim()

        if (violations.size() > 0 || conftestExitCode != '0') {
            def jobName = env.JOB_NAME ?: 'unknown'
            def buildNumber = env.BUILD_NUMBER ?: '0'
            def jenkinsUrl = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}"

            def opaPayload = [
                application: userConfig.appName,
                risks: violations.collect { v ->
                    v + [
                        jenkins_job: jobName,
                        build_number: buildNumber.toInteger(),
                        jenkins_url: jenkinsUrl
                    ]
                }
            ]

            writeJSON file: 'opa-k8s-upload.json', json: opaPayload, pretty: 2

            sh "curl -fsS -X POST https://horizonrelevance.com/pipeline/api/opa/risks/ -H 'Content-Type: application/json' -d @opa-k8s-upload.json"

            error("Policy violations found in Helm/Kubernetes manifests. Failing pipeline.")
        } else {
            echo "No Kubernetes policy violations found."
        }
    } else {
        echo "Policy validation is disabled by configuration. Skipping."
    }

    def releaseName = safeName(userConfig.appName)
    def ns = deployTarget.namespace

    withEnv(assumeRoleEnv(deployTarget.roleArn, deployTarget.region)) {
        sh """
            set -e
            aws sts get-caller-identity

            if [ -n '${deployTarget.clusterName}' ]; then
              aws eks update-kubeconfig \
                --region '${deployTarget.region}' \
                --name '${deployTarget.clusterName}' \
                --alias '${deployTarget.clusterName}'
            else
              echo "No cluster name supplied for ${deployTarget.targetEnv}; using current Jenkins kubeconfig context."
            fi

            echo "Ensuring namespace '${ns}' exists for ${deployTarget.targetEnv}..."
            kubectl get namespace ${ns} >/dev/null 2>&1 || kubectl create namespace ${ns}

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
    }

    echo "🚀 Helm deployment completed successfully."
}

def resolveDeployTarget(Map params, Map userConfig) {
    def envName = (params.TARGET_ENV ?: userConfig.targetEnv ?: 'DEV').toString().trim().toUpperCase()
    if (envName == 'EKS-NONPROD') {
        envName = 'DEV'
    }
    if (envName == 'EKS-PROD') {
        envName = 'PROD'
    }

    def clientId = safeName(params.CLIENT_ID ?: 'client')
    def appName = safeName(userConfig.appName ?: params.PROJECT_NAME ?: 'app')
    def namespaceStrategy = (params.NAMESPACE_STRATEGY ?: 'auto').toString().trim().toLowerCase()
    def namespaceOverride = (params.APP_NAMESPACE ?: '').toString().trim()
    def namespaceByEnv = [
        DEV  : params.DEV_NAMESPACE,
        QA   : params.QA_NAMESPACE,
        STAGE: params.STAGE_NAMESPACE,
        PROD : params.PROD_NAMESPACE
    ]

    def namespace = namespaceStrategy == 'manual' && namespaceOverride
        ? namespaceOverride
        : (namespaceByEnv[envName] ?: "${clientId}-${appName}-${envName.toLowerCase()}").toString()

    def clusterByEnv = [
        DEV  : params.DEV_CLUSTER_NAME,
        QA   : params.QA_CLUSTER_NAME,
        STAGE: params.STAGE_CLUSTER_NAME,
        PROD : params.PROD_CLUSTER_NAME
    ]
    def roleByEnv = [
        DEV  : params.NONPROD_AWS_ROLE_ARN ?: params.CLIENT_AWS_ROLE_ARN,
        QA   : params.NONPROD_AWS_ROLE_ARN ?: params.CLIENT_AWS_ROLE_ARN,
        STAGE: params.NONPROD_AWS_ROLE_ARN ?: params.CLIENT_AWS_ROLE_ARN,
        PROD : params.TARGET_AWS_ROLE_ARN ?: params.CLIENT_AWS_ROLE_ARN
    ]

    return [
        targetEnv  : envName,
        region     : (params.AWS_REGION ?: 'us-east-1').toString().trim(),
        roleArn    : (roleByEnv[envName] ?: '').toString().trim(),
        clusterName: (clusterByEnv[envName] ?: '').toString().trim(),
        namespace  : safeName(namespace)
    ]
}

def assumeRoleEnv(String roleArn, String region) {
    if (!roleArn?.trim()) {
        return ["AWS_DEFAULT_REGION=${region}", "AWS_REGION=${region}"]
    }

    def sessionName = "horizon-deploy-${env.BUILD_NUMBER ?: 'manual'}"
    def creds = sh(
        script: """
          aws sts assume-role \
            --region '${region}' \
            --role-arn '${roleArn}' \
            --role-session-name '${sessionName}' \
            --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
            --output text
        """,
        returnStdout: true
    ).trim().split()

    if (creds.size() < 3) {
        error "Unable to assume deployment role: ${roleArn}"
    }

    return [
        "AWS_ACCESS_KEY_ID=${creds[0]}",
        "AWS_SECRET_ACCESS_KEY=${creds[1]}",
        "AWS_SESSION_TOKEN=${creds[2]}",
        "AWS_DEFAULT_REGION=${region}",
        "AWS_REGION=${region}"
    ]
}

def safeName(Object value) {
    return (value ?: 'default').toString().toLowerCase()
        .replaceAll(/[^a-z0-9-]/, '-')
        .replaceAll(/-+/, '-')
        .replaceAll(/^-|-$/, '')
        .take(63)
}
