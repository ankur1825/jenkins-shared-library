def call(String projectKey, String sonarToken) {
    writeFile file: 'scripts/process_sonar_ml.py', text: libraryResource('sonar/process_sonar_ml.py')

    // Write token securely, pass projectKey via env or interpolate early
    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
        sh """
            curl -u admin:$SONAR_TOKEN \
                 'https://horizonrelevance.com/sonarqube/api/issues/search?componentKeys=${projectKey}' \
                 -o issues.json
        """
    }

    sh "python3 scripts/process_sonar_ml.py issues.json ai_sonar_results.json"

    sh """
        curl -X POST https://horizonrelevance.com/pipeline/api/vulnerabilities \
             -H "Content-Type: application/json" \
             -d @ai_sonar_results.json
    """
}
