import groovy.json.JsonOutput

def call(Map params = [:]) {
    def configPath = 'config.json'

    if (!fileExists(configPath)) {
        error "config.json not found in workspace!"
    }

    def userConfig = readJSON file: configPath

    // 🛠️ Transform image repository if PRIVATE_REPO or imageRepo is present
    def rawRepo = userConfig.imageRepo ?: userConfig.PRIVATE_REPO
    if (rawRepo) {
        def transformedRepo = rawRepo.replace(
            "docker-snapshot-local.kc.cernerrepos.net",
            "docker-snapshot.kc.cernerrepos.net"
        )
        def finalRepo = userConfig.AppName ? "${transformedRepo}/${userConfig.AppName}".toLowerCase() : transformedRepo

        // Set .image field in-place
        userConfig.image = [
            repository : finalRepo,
            tag        : userConfig.tag ?: 'latest',
            pullPolicy : 'IfNotPresent'
        ]
    }

    // Inject defaults if not present
    userConfig.appName      = userConfig.AppName ?: userConfig.appName ?: 'default-app'
    userConfig.namespace    = userConfig.namespace ?: 'default'
    userConfig.replicaCount = userConfig.replicaCount ?: 1

    // Write full config to custom-values.yaml dynamically
    def jsonString = JsonOutput.toJson(userConfig)
    def prettyYaml = JsonOutput.prettyPrint(jsonString)
    writeFile file: 'custom-values.yaml', text: prettyYaml

    echo "Generated custom-values.yaml with all dynamic fields from config.json"

    // Prepare Helm chart from shared lib
    def helmChartDir = "generic-helm"
    sh "mkdir -p ${helmChartDir}/templates"

    writeFile file: "${helmChartDir}/Chart.yaml", text: libraryResource('Helm-chart/Chart.yaml')
    writeFile file: "${helmChartDir}/values.yaml", text: libraryResource('Helm-chart/values.yaml')
    writeFile file: "${helmChartDir}/templates/_helpers.tpl", text: libraryResource('Helm-chart/templates/_helpers.tpl')
    writeFile file: "${helmChartDir}/templates/deployment.yaml", text: libraryResource('Helm-chart/templates/deployment.yaml')
    writeFile file: "${helmChartDir}/templates/service.yaml", text: libraryResource('Helm-chart/templates/service.yaml')

    // Conditionally include ingress.yaml if user enables ingress
    if (userConfig.ingress?.enabled) {
        writeFile file: "${helmChartDir}/templates/ingress.yaml", text: libraryResource('Helm-chart/templates/ingress.yaml')
    }

    // Use IRSA-based access (no KUBECONFIG needed anymore)
    def releaseName = userConfig.appName.toLowerCase().replaceAll(/[^a-z0-9\-]/, '-')
    def ns = userConfig.namespace

    sh """#!/bin/bash
        set -e
        echo "Using IRSA for AWS authentication"
        aws sts get-caller-identity

        echo "Assuming namespace '${ns}' already exists and Jenkins has access"

        echo "Checking if Helm release '${releaseName}' exists in namespace '${ns}'..."
        if helm status ${releaseName} --namespace ${ns} > /dev/null 2>&1; then
            echo "Release exists. Running helm upgrade..."
            helm upgrade ${releaseName} ${helmChartDir} -f custom-values.yaml --namespace ${ns}
        else
            echo "Release does not exist. Running helm install..."
            helm install ${releaseName} ${helmChartDir} -f custom-values.yaml --namespace ${ns}
        fi
    """

    echo "Helm deployment completed."
}
