def call(Map params = [:]) {
    if (!params.inputJson) {
        error "Missing required parameter: inputJson"
    }

    def inputJson = params.inputJson
    def opaUrl = params.get('opaUrl', "http://opa.horizon-relevance-dev.svc.cluster.local:8181/v1/data/docker/security/deny")

    echo "Evaluating OPA Policy..."
    writeFile file: 'opa-input.json', text: inputJson

    def opaResponse = sh(script: """
        curl -s -X POST ${opaUrl} -d @opa-input.json -H "Content-Type: application/json"
    """, returnStdout: true).trim()

    echo "OPA Response: ${opaResponse}"

    def opaResult = readJSON text: opaResponse
    return opaResult.result
}
