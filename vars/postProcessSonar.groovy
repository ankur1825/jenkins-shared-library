def call(String projectKey, String sonarToken) {
    if (!projectKey?.trim()) {
        error "Sonar project key is empty or null – cannot proceed with post-processing."
    }

    echo "Post-processing SonarQube results for projectKey: ${projectKey}"

    // Write Python script from shared library resource
    writeFile file: 'scripts/process_sonar_ml.py', text: libraryResource('sonar/process_sonar_ml.py')

    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
        withEnv(["PROJECT_KEY=${projectKey}"]) {
            sh '''
                echo "Downloading issues from SonarQube API for project: $PROJECT_KEY"
                curl -u admin:$SONAR_TOKEN \
                     "https://horizonrelevance.com/sonarqube/api/issues/search?componentKeys=$PROJECT_KEY" \
                     -o issues.json
            '''
        }
    }

    // Run Python processing script
    sh """
        if [ ! -s issues.json ]; then
            echo '[ERROR] Input file issues.json is empty or missing.'
            exit 1
        fi

        python3 scripts/process_sonar_ml.py issues.json ai_sonar_results.json
    """

    // Upload processed vulnerabilities to FastAPI backend
    sh """
        curl -X POST https://horizonrelevance.com/pipeline/api/vulnerabilities \
             -H "Content-Type: application/json" \
             -d @ai_sonar_results.json
    """

    echo "Post-processing complete. AI-enhanced SonarQube vulnerabilities uploaded."
}
