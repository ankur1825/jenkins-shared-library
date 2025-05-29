def run(Map params) {
    node {
        try {
            if (params.REPO_URL) {
                stage('Clone Repository') {
                    echo "Cloning repository: ${params.REPO_URL}"
                    git credentialsId: params.CREDENTIALS_ID, url: params.REPO_URL, branch: params.BRANCH
                }

                stage('directory check & Extract Base Image from Dockerfile') {
                    script {
                        echo "checking directory"
                        sh 'ls -lrth'
                        sh 'pwd'
                        if (fileExists('Dockerfile')) {
                            env.BASE_IMAGE = sh(script: "awk '/^FROM/ {print \$2; exit}' Dockerfile", returnStdout: true).trim()
                            echo "Base Docker Image found: ${env.BASE_IMAGE}"
                        } else {
                            error "Dockerfile not found!"
                        }
                    }
                }
            }

            stage('Read config.json') {
                script {
                    if (fileExists('config.json')) {
                        def privateRepo = sh(script: '''
                            grep -oP '"PRIVATE_REPO":\\s*"[^"]*"' config.json | awk -F '"' '{print $4}'
                        ''', returnStdout: true).trim()

                        def tag = sh(script: '''
                            grep -oP '"tag":\\s*"[^"]*"' config.json | awk -F '"' '{print $4}'
                        ''', returnStdout: true).trim()

                        def appName = sh(script: '''
                            grep -oP '"AppName":\\s*"[^"]*"' config.json | awk -F '"' '{print $4}'
                        ''', returnStdout: true).trim()


                        if (privateRepo && tag && appName) {
                            env.PRIVATE_REPO = privateRepo
                            env.TAG = tag
                            env.APP_NAME = appName
                        } else {
                            error "Missing necessary fields in config.json."
                        }
                    } else {
                        error "config.json file not found."
                    }
                }
            }

            if (params.ENABLE_SONARQUBE?.toBoolean()) {
                stage('Static Code Analysis') {
                    def scannerHome = tool 'SONAR-SCANNER'

                    if (!fileExists('sonar-project.properties')) {
                        error "sonar-project.properties not found!"
                    }

                    def sonarProjectKey = sh(
                        script: "grep '^sonar.projectKey=' sonar-project.properties | awk -F '=' '{print \$2}' | tr -d '\\r\\n' | xargs",
                        returnStdout: true
                    ).trim()
                    if (!sonarProjectKey) {
                        error "sonar.projectKey is missing or empty!"
                    }
                    echo "Sonar Project Key: ${sonarProjectKey}"

                    withSonarQubeEnv('sonarqube') {
                        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                            sh """
                                ${scannerHome}/bin/sonar-scanner \
                                -Dproject.settings=sonar-project.properties \
                                -Dsonar.host.url=https://horizonrelevance.com/sonarqube
                            """
                        }
                    }

                    timeout(time: 2, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: false
                    }

                    script {
                        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                            postProcessSonar(sonarProjectKey)
                        }
                    }
                }
            }

            if (params.ENABLE_TRIVY?.toBoolean()) {
                stage('Trivy Scan Base Image') {
                    trivyScan(imageName: env.BASE_IMAGE, uploadResults: true, application: env.APP_NAME, buildNumber: env.BUILD_NUMBER, jenkinsJob: env.JOB_NAME)
                }

                stage('Trivy Scan Built Image') {
                    def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    trivyScan(imageName: imageName, uploadResults: true, application: env.APP_NAME, buildNumber: env.BUILD_NUMBER, jenkinsJob: env.JOB_NAME)
                }
            }

            if (params.ENABLE_OPA?.toBoolean()) {
                stage('OPA Policy Evaluation') {
                    def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    opaEnsureServerRunning()
                    def opaInput = createOPAInput(imageName, env.TAG)
                    def enrichedOPAResults = opaEvaluateCurl(inputJson: opaInput, imageName: imageName)
                    echo "OPA Risk Evaluation Complete. Total Enriched Risks: ${enrichedOPAResults.size()}"
                }
            }

            stage('Build Docker Image') {
                def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                sh "docker build -t ${imageName} ."
            }

            stage('Push Docker Image') {
                def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh """
                        echo \"$DOCKER_PASS\" | docker login -u \"$DOCKER_USER\" --password-stdin
                        docker push ${imageName}
                    """
                }
            }

            stage('Deploy to Kubernetes') {
                deployHelm(ENABLE_OPA: params.ENABLE_OPA)
            }
        } catch (Exception e) {
            echo "Pipeline failed due to: ${e.message}"
            currentBuild.result = 'FAILURE'
            throw e
        }
    }
}

return this;

// def run(Map params) {
//     node {
//         try {
//             if (params.REPO_URL) {
//                 stage('Clone Repository') {
//                     echo "Cloning repository: ${params.REPO_URL}"
//                     git credentialsId: params.CREDENTIALS_ID, url: params.REPO_URL, branch: params.BRANCH
//                 }

//                 stage('directory check & Extract Base Image from Dockerfile') {
//                     script {
//                         echo "checking directory"
//                         sh 'ls -lrth'
//                         sh 'pwd'
//                         if (fileExists('Dockerfile')) {
//                             env.BASE_IMAGE = sh(script: "awk '/^FROM/ {print \$2; exit}' Dockerfile", returnStdout: true).trim()
//                             echo "Base Docker Image found: ${env.BASE_IMAGE}"
//                         } else {
//                             error "Dockerfile not found!"
//                         }
//                     }
//                 }
//             }

//             def config
//             stage('Read config.json') {
//                 script {
//                     if (fileExists('config.json')) {
//                         // Extract values using grep and awk with escaped backslashes
//                         def privateRepo = sh(script: "grep -oP '\\\"PRIVATE_REPO\\\":\\s*\\\"[^\"]*\\\"' config.json | awk -F '\\\"' '{print \$4}'", returnStdout: true).trim()
//                         def tag = sh(script: "grep -oP '\\\"tag\\\":\\s*\\\"[^\"]*\\\"' config.json | awk -F '\\\"' '{print \$4}'", returnStdout: true).trim()
//                         def appName = sh(script: "grep -oP '\\\"AppName\\\":\\s*\\\"[^\"]*\\\"' config.json | awk -F '\\\"' '{print \$4}'", returnStdout: true).trim()

//                         if (privateRepo && tag && appName) {
//                             env.PRIVATE_REPO = privateRepo
//                             env.TAG = tag
//                             env.APP_NAME = appName

//                             echo "Loaded config: PRIVATE_REPO=${env.PRIVATE_REPO}, TAG=${env.TAG}, APP_NAME=${env.APP_NAME}"
//                         } else {
//                             error "Missing necessary fields in config.json: PRIVATE_REPO, tag, AppName"
//                         }
//                     } else {
//                         error "config.json file not found. Please make sure it exists in the repository."
//                     }
//                 }
//             }


//             if (params.ENABLE_SONARQUBE?.toBoolean()) {
//                 stage('Static Code Analysis') {
//                     echo "Running SonarQube analysis..."
//                     def scannerHome = tool 'SONAR-SCANNER'

//                     if (!fileExists('sonar-project.properties')) {
//                         error "sonar-project.properties not found in the repository!"
//                     }
//                     // Extract sonar.projectKey and fail if missing or empty
//                     def sonarProjectKey = sh(
//                         script: "grep '^sonar.projectKey=' sonar-project.properties | cut -d'=' -f2",
//                         returnStdout: true
//                     ).trim()
//                     if (!sonarProjectKey) {
//                         error "sonar.projectKey not found or empty in sonar-project.properties!"
//                     }
//                     echo "Detected Sonar Project Key: ${sonarProjectKey}"

//                     withSonarQubeEnv('sonarqube') {
//                       withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
//                             sh """
//                                 ${scannerHome}/bin/sonar-scanner \
//                                 -Dproject.settings=sonar-project.properties \
//                                 #-Dsonar.projectKey=webserice-application \
//                                 #-Dsonar.projectName=webserice-application \
//                                 -Dsonar.host.url=https://horizonrelevance.com/sonarqube 
//                             """
//                       }
//                     }
//                     timeout(time:2, unit:'MINUTES'){
//                         script{
//                             waitForQualityGate abortPipeline: true
//                         }
//                     }

//                     // AI Post-Processing & Push to Backend
//                     script {
//                         withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
//                             postProcessSonar(sonarProjectKey, SONAR_TOKEN)
//                         }
//                     }
//                 }
//             } else {
//                 echo "Skipping Static Code Analysis as SonarQube is disabled."
//             }

//             if (params.ENABLE_TRIVY?.toBoolean()) {
//                 stage('Trivy Scan Base Image') {
//                     trivyScan(imageName: env.BASE_IMAGE)
//                 }
//             } else {
//                 echo "Skipping Docker Security Scan as Trivy is disabled."
//             }

//             if (params.ENABLE_TRIVY?.toBoolean()) {
//                 stage('Trivy Scan Built Image') {
//                     script {
//                         def repo = env.PRIVATE_REPO?.trim().toLowerCase().replaceAll('/$', '')
//                         def imageName = "${repo}/${env.APP_NAME}:${env.TAG}".toLowerCase()
//                         trivyScan(imageName: imageName, uploadResults: true)
//                     }
//                 }
//             } else {
//                 echo "Skipping Docker Security Scan as Trivy is disabled."
//             }

//             if (params.ENABLE_OPA?.toBoolean()) {
//                 stage('OPA Policy Evaluation') {
//                     script {
//                         def repo = env.PRIVATE_REPO?.trim().toLowerCase().replaceAll('/$', '')
//                         def imageName = "${repo}/${env.APP_NAME}:${env.TAG}".toLowerCase()

//                         opaEnsureServerRunning()  // ensure OPA pod is deployed

//                         def opaInput = createOPAInput(imageName, env.TAG)

//                         def enrichedOPAResults = opaEvaluateCurl(
//                             inputJson: opaInput,
//                             imageName: imageName
//                         )

//                         echo "OPA Risk Evaluation Complete. Total Enriched Risks: ${enrichedOPAResults.size()}"
//                     }
//                 }
//             } else {
//                 echo "Skipping OPA Evaluation as it is disabled."
//             }


//             stage('Build Docker Image') {
//                 script {
//                     def repo = env.PRIVATE_REPO?.trim().toLowerCase().replaceAll('/$', '')
//                     def imageName = "${repo}/${env.APP_NAME}:${env.TAG}".toLowerCase()
//                     echo "Building Docker image: ${imageName}"
//                     sh "docker build -t ${imageName} ."
//                 }
//             }

//             stage('Push Docker Image') {
//                 script {
//                     def repo = env.PRIVATE_REPO?.trim().toLowerCase().replaceAll('/$', '')
//                     def imageName = "${repo}/${env.APP_NAME}:${env.TAG}".toLowerCase()
//                     echo "Pushing Docker image: ${imageName}"
//                     withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
//                     sh """
//                         echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
//                         docker push ${imageName}
//                     """
//                 }}
//             }

//             // if (params.ENABLE_OPA?.toBoolean()) {
//             //     stage('Docker Security Scan') {
//             //         echo "Running Docker Security Scan with OPA and Trivy..."
//             //         sh 'opa eval --input dockerfile.json policy.rego || true'
//             //         sh "trivy image ${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG} || true"
//             //     }
//             // } else {
//             //     echo "Skipping Docker Security Scan as OPA is disabled."
//             // }

//             stage('deploy to Kubernetes ') {
//                 script {
//                     deployHelm()
//                 }
//             }
            
//             /*stage('Kubernetes Config Validation') {
//                 script {
//                     if (fileExists('k8s/deployment.yaml')) {
//                         sh 'kube-linter lint k8s/deployment.yaml'
//                     } else {
//                         echo "No Kubernetes configuration found. Skipping validation."
//                     }
//                 }
//             }*/

//         } catch (Exception e) {
//             echo "Pipeline failed due to: ${e.message}"
//             currentBuild.result = 'FAILURE'
//             throw e
//         }
//     }
// }

// return this
