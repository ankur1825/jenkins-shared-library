def call(String projectKey) {
    if (!projectKey?.trim()) {
        error "Sonar project key is empty or null – cannot proceed with post-processing."
    }

    echo "Post-processing SonarQube results for projectKey: ${projectKey}"

    // Write Python script to workspace
    writeFile file: 'scripts/process_sonar_ml.py', text: libraryResource('sonar/process_sonar_ml.py')

    // Fetch issues from SonarQube API using username/password from Jenkins credentials
    withCredentials([usernamePassword(credentialsId: 'sonar_secret', usernameVariable: 'SONAR_USER', passwordVariable: 'SONAR_PASS')]) {
        withEnv(["PROJECT_KEY=${projectKey}"]) {
            sh '''
                echo "Downloading issues from SonarQube API for project: $PROJECT_KEY"
                curl -u "$SONAR_USER:$SONAR_PASS" \
                    "https://horizonrelevance.com/sonarqube/api/issues/search?componentKeys=$PROJECT_KEY&statuses=OPEN" \
                    -o issues.json
            '''
        }
    }

    // Fail if issues.json is missing or empty
    sh '''
        if [ ! -s issues.json ]; then
            echo "[INFO] No issues found. Skipping upload."
            echo "[]" > ai_sonar_results.json
        fi
    '''

    // Run the ML script and capture FAIL_PIPELINE output
    def output = sh(script: 'python3 scripts/process_sonar_ml.py issues.json ai_sonar_results.json', returnStdout: true).trim()

    // Check if High/Critical were found
    if (output.contains("FAIL_PIPELINE=true")) {
        echo "[ERROR] SonarQube scan found High or Critical issues."
        error "Failing pipeline due to code quality issues."
    }

    // Upload results to FastAPI backend only if vulnerabilities exist
    sh '''
        COUNT=$(jq '. | length' ai_sonar_results.json)
        if [ "$COUNT" -gt 0 ]; then
            echo '{"vulnerabilities":' > wrapper.json
            cat ai_sonar_results.json >> wrapper.json
            echo '}' >> wrapper.json

            curl -X POST https://horizonrelevance.com/pipeline/api/vulnerabilities \
                 -H "Content-Type: application/json" \
                 -d @wrapper.json
        else
            echo "[INFO] No SonarQube vulnerabilities to upload."
        fi
    '''

    echo "Post-processing complete. AI-enhanced SonarQube vulnerabilities handled."
}
