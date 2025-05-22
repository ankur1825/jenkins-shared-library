def call(projectKey, sonarToken) {
    writeFile file: 'scripts/process_sonar_ml.py', text: libraryResource('sonar/process_sonar_ml.py')

    sh """
        curl -u admin:${sonarToken} \
            'https://horizonrelevance.com/sonarqube/api/issues/search?componentKeys=${projectKey}' \
            -o issues.json

        python3 scripts/process_sonar_ml.py issues.json ai_sonar_results.json
    """

    sh """
        curl -X POST https://horizonrelevance.com/pipeline/api/vulnerabilities \
            -H "Content-Type: application/json" \
            -d @ai_sonar_results.json
    """
}
