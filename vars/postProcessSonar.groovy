def call(String projectKey, String repoUrl, String triggeredBy) {
    if (!projectKey?.trim()) {
        error "Sonar project key is empty or null – cannot proceed with post-processing."
    }

    echo "Post-processing SonarQube results for projectKey: ${projectKey}"

    writeFile file: 'scripts/process_sonar_ml.py', text: libraryResource('sonar/process_sonar_ml.py')

    withCredentials([usernamePassword(credentialsId: 'sonar_secret', usernameVariable: 'SONAR_USER', passwordVariable: 'SONAR_PASS')]) {
        withEnv(["PROJECT_KEY=${projectKey}"]) {
            sh '''
                echo "Downloading issues from SonarQube API for project: $PROJECT_KEY"
                curl -u "$SONAR_USER:$SONAR_PASS" \
                     "https://horizonrelevance.com/sonarqube/api/issues/search?componentKeys=$PROJECT_KEY&statuses=OPEN" \
                     -o issues.json

                if [ ! -s issues.json ]; then
                    echo "[INFO] No issues found. Creating empty output file."
                    echo "[]" > ai_sonar_results.json
                    echo "NO_ISSUES=true" > sonar_flag.txt
                else
                    echo "NO_ISSUES=false" > sonar_flag.txt
                fi
            '''
        }
    }

    def noIssues = readFile('sonar_flag.txt').contains("NO_ISSUES=true")
    def failPipeline = false
    def application = projectKey  // use Sonar projectKey as application name

    if (!noIssues) {
        def output = sh(script: 'python3 scripts/process_sonar_ml.py issues.json ai_sonar_results.json', returnStdout: true).trim()
        if (output.contains("FAIL_PIPELINE=true")) {
            echo "[ERROR] SonarQube scan found High or Critical issues."
            failPipeline = true  // defer error call until after upload
        }

        sh """
            COUNT=\$(jq '. | length' ai_sonar_results.json)
            if [ "\$COUNT" -gt 0 ]; then
                echo '{' > wrapper.json
                echo '  "application": "${application}",' >> wrapper.json
                echo '  "repo_url": "${repoUrl}",' >> wrapper.json
                echo '  "requestedBy": "${triggeredBy}",' >> wrapper.json
                echo '  "jenkins_job": "${env.JOB_NAME}",' >> wrapper.json
                echo '  "build_number": ${env.BUILD_NUMBER},' >> wrapper.json
                echo '  "jenkins_url": "${env.JENKINS_URL}/job/${env.JOB_NAME}/${env.BUILD_NUMBER}/console",' >> wrapper.json
                echo '  "vulnerabilities":' >> wrapper.json
                cat ai_sonar_results.json >> wrapper.json
                echo '}' >> wrapper.json

                curl -s -X POST https://horizonrelevance.com/pipeline/api/upload_vulnerabilities \\
                    -H "Content-Type: application/json" \\
                    -d @wrapper.json
            else
                echo "[INFO] No SonarQube vulnerabilities to upload."
            fi
        """
    } else {
        echo "[INFO] No SonarQube issues to process."
    }

    if (failPipeline) {
        error "Failing pipeline due to High/Critical SonarQube issues."
    }

    echo "Post-processing complete. AI-enhanced SonarQube vulnerabilities handled."
}
