def call(Map params = [:]) {
    if (!params.inputJson) {
        error "Missing required parameter: inputJson"
    }

    def inputJson = params.inputJson
    def opaUrl = params.get('opaUrl', "http://opa.horizon-relevance-dev.svc.cluster.local:8181/v1/data/docker/security/deny")
    def backendUrl = params.get('backendUrl', "https://horizonrelevance.com/pipeline/api/opa/risks/")
    def imageName = params.get('imageName', 'unknown')

    echo "Evaluating OPA Policy..."
    writeFile file: 'opa-input.json', text: inputJson

    def opaResponse = sh(script: """
        curl -s -X POST ${opaUrl} -d @opa-input.json -H "Content-Type: application/json"
    """, returnStdout: true).trim()

    echo "OPA Response: ${opaResponse}"

    def opaResult = readJSON text: opaResponse

    if (!opaResult?.result) {
        error "OPA policy evaluation failed: ${opaResult.warning ?: 'No result returned.'}"
    }

    def enriched = []
    for (msg in opaResult.result) {
        def severity = msg.toLowerCase().contains("critical") ? "HIGH" :
                       msg.toLowerCase().contains("medium")   ? "MEDIUM" : "LOW"
        def score = severity == "HIGH" ? 80 :
                    severity == "MEDIUM" ? 50 : 20

        enriched << [
            source: "OPA",
            target: imageName,
            violation: msg,
            severity: severity,
            risk_score: score
        ]
    }

    writeJSON file: "opa-risk-upload.json", json: [risks: enriched], pretty: 4

    sh """
        curl -s -X POST ${backendUrl} \
            -H "Content-Type: application/json" \
            -d @opa-risk-upload.json
    """

    return enriched
}
