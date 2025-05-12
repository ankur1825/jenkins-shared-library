def run(Map params) {
    node {
        try {
            if (params.REPO_URL) {
                stage('Clone Repository') {
                    echo "Cloning repository: ${params.REPO_URL}"
                    git credentialsId: params.CREDENTIALS_ID, url: params.REPO_URL, branch: params.BRANCH
                }

                stage('directory check') {
                    echo "checking directory"
                    sh 'ls -lrth'
                    sh 'pwd'
                }
            }

            def config
            stage('Read config.json') {
                script {
                    if (fileExists('config.json')) {
                        // Extract values using grep and awk with escaped backslashes
                        def privateRepo = sh(script: "grep -oP '\\\"PRIVATE_REPO\\\":\\s*\\\"[^\"]*\\\"' config.json | awk -F '\\\"' '{print \$4}'", returnStdout: true).trim()
                        def tag = sh(script: "grep -oP '\\\"tag\\\":\\s*\\\"[^\"]*\\\"' config.json | awk -F '\\\"' '{print \$4}'", returnStdout: true).trim()
                        def appName = sh(script: "grep -oP '\\\"AppName\\\":\\s*\\\"[^\"]*\\\"' config.json | awk -F '\\\"' '{print \$4}'", returnStdout: true).trim()

                        if (privateRepo && tag && appName) {
                            env.PRIVATE_REPO = privateRepo
                            env.TAG = tag
                            env.APP_NAME = appName

                            echo "Loaded config: PRIVATE_REPO=${env.PRIVATE_REPO}, TAG=${env.TAG}, APP_NAME=${env.APP_NAME}"
                        } else {
                            error "Missing necessary fields in config.json: PRIVATE_REPO, tag, AppName"
                        }
                    } else {
                        error "config.json file not found. Please make sure it exists in the repository."
                    }
                }
            }


            if (params.ENABLE_SONARQUBE?.toBoolean()) {
                stage('Static Code Analysis') {
                    echo "Running SonarQube analysis..."
                    def scannerHome = tool 'SONAR-SCANNER'
                    withSonarQubeEnv('sonarqube') {
                      sh 'java -version' 
                      sh "${scannerHome}/bin/sonar-scanner"
                    }
                    timeout(time:2, unit:'MINUTES'){
                        script{
                            waitForQualityGate abortPipeline: true
                        }
                    }
                }
            } else {
                echo "Skipping Static Code Analysis as SonarQube is disabled."
            }

            stage('Build Docker Image') {
                script {
                    def repo = env.PRIVATE_REPO?.trim().toLowerCase().replaceAll('/$', '')
                    def imageName = "${repo}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    echo "Building Docker image: ${imageName}"
                    sh "docker build -t ${imageName} ."
                }
            }

            stage('Push Docker Image') {
                script {
                    def repo = env.PRIVATE_REPO?.trim().toLowerCase().replaceAll('/$', '')
                    def imageName = "${repo}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    echo "Pushing Docker image: ${imageName}"
                    withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh """
                        echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                        docker push ${imageName}
                    """
                }}
            }

            if (params.ENABLE_OPA?.toBoolean()) {
                stage('Docker Security Scan') {
                    echo "Running Docker Security Scan with OPA and Trivy..."
                    sh 'opa eval --input dockerfile.json policy.rego || true'
                    sh "trivy image ${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG} || true"
                }
            } else {
                echo "Skipping Docker Security Scan as OPA is disabled."
            }

            stage('deploy to Kubernetes ') {
                script {
                    deployHelm()
                }
            }
            
            /*stage('Kubernetes Config Validation') {
                script {
                    if (fileExists('k8s/deployment.yaml')) {
                        sh 'kube-linter lint k8s/deployment.yaml'
                    } else {
                        echo "No Kubernetes configuration found. Skipping validation."
                    }
                }
            }*/

        } catch (Exception e) {
            echo "Pipeline failed due to: ${e.message}"
            currentBuild.result = 'FAILURE'
            throw e
        }
    }
}

return this
