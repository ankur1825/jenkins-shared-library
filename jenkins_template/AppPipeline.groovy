// ================== EXISTING PIPELINE (unchanged) ==================
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
                    if (!fileExists('config.json')) {
                        error "config.json file not found."
                    }
                    // Use Pipeline Utility Steps instead of external tools
                    def cfg = readJSON file: 'config.json'
                    env.PRIVATE_REPO = (cfg.PRIVATE_REPO ?: '').toString().trim()
                    env.TAG          = (cfg.tag ?: '').toString().trim()
                    env.APP_NAME     = (cfg.AppName ?: '').toString().trim()

                    if (!env.PRIVATE_REPO || !env.TAG || !env.APP_NAME) {
                        error "Missing necessary fields in config.json. Need PRIVATE_REPO, tag, AppName."
                    }
                }
            }

            if (params.ENABLE_SONARQUBE?.toBoolean()) {
                stage('Static Code Analysis') {
                    def scannerHome = tool 'SONAR-SCANNER'

                    if (!fileExists('sonar-project.properties')) {
                        error "sonar-project.properties not found!"
                    }

                    // Read sonar-project.properties without shelling out
                    def propsText = readFile 'sonar-project.properties'
                    def sonarProjectKey = propsText.readLines()
                        .find { it.trim().startsWith('sonar.projectKey=') }
                        ?.split('=', 2)?.getAt(1)?.trim()
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

                    timeout(time: 10, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: false
                    }

                    wrap([$class: 'BuildUser']) {
                        def triggeredBy = env.BUILD_USER ?: "unknown"
                        echo "Build triggered by: ${triggeredBy}"

                        script {
                            withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                                postProcessSonar(sonarProjectKey, params.REPO_URL, triggeredBy)
                            }
                        }
                    }
                }
            }

            if (params.ENABLE_TRIVY?.toBoolean()) {
                stage('Trivy Scan Base Image') {
                    trivyScan(imageName: env.BASE_IMAGE, uploadResults: true, application: env.APP_NAME, buildNumber: env.BUILD_NUMBER, jenkinsJob: env.JOB_NAME)
                }
            }

            if (params.ENABLE_OPA?.toBoolean()) {
                stage('OPA Policy Evaluation') {
                    def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    opaEnsureServerRunning()
                    def opaInput = createOPAInput(imageName, env.TAG)
                    def enrichedOPAResults = opaEvaluateCurl(inputJson: opaInput, imageName: imageName, application: env.APP_NAME, jobName: env.JOB_NAME, buildNumber: env.BUILD_NUMBER, requestedBy: env.BUILD_USER_ID)
                    echo "OPA Risk Evaluation Complete. Total Enriched Risks: ${enrichedOPAResults.size()}"
                }
            }

            stage('Build Docker Image') {
                def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                sh "docker build -t ${imageName} ."
            }

            if (params.ENABLE_TRIVY?.toBoolean()) {
                stage('Trivy Scan Built Image') {
                    def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    trivyScan(imageName: imageName, uploadResults: true, application: env.APP_NAME, buildNumber: env.BUILD_NUMBER, jenkinsJob: env.JOB_NAME)
                }
            }

            stage('Push Docker Image') {
                def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh """
                        echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
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

// ================== NEW: DEVOPS PIPELINE ==================
def runDevops(Map params) {
    /*
      Expected params:
        PROJECT_NAME, PROJECT_TYPE, REPO_TYPE, REPO_URL, BRANCH, CREDENTIALS_ID
        ENABLE_SONARQUBE, ENABLE_SOAPUI, ENABLE_JMETER, ENABLE_SELENIUM, ENABLE_NEWMAN
        TARGET_ENV (EKS-PROD|EKS-NONPROD), NOTIFY_EMAIL, REQUESTED_BY
        AWS_REGION, ECR_REGISTRY, ECR_REPOSITORY, ARTIFACT_BUCKET, CLIENT_AWS_ROLE_ARN
        ENABLE_NOTIFICATIONS, SNS_TOPIC_ARN
    */
    node {
        try {
            stage('Checkout Application Repo') {
                if (!params.REPO_URL || !params.REPO_URL.toString().contains('/')) {
                    error "REPO_URL is invalid ('${params.REPO_URL}'). Provide a full Git URL, e.g. https://github.com/org/repo.git"
                }
                echo "Cloning repository: ${params.REPO_URL} (branch ${params.BRANCH})"
                git credentialsId: params.CREDENTIALS_ID, url: params.REPO_URL, branch: params.BRANCH
                sh 'pwd && ls -lrth'
            }

            stage('Prepare ECR Metadata') {
                script {
                    env.AWS_REGION = (params.AWS_REGION ?: 'us-east-1').toString().trim()
                    env.ECR_REGISTRY = (params.ECR_REGISTRY ?: '').toString().trim()
                    env.ECR_REPOSITORY = (params.ECR_REPOSITORY ?: params.PROJECT_NAME ?: '').toString().trim()
                    env.ARTIFACT_BUCKET = (params.ARTIFACT_BUCKET ?: '').toString().trim()
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short=11 HEAD', returnStdout: true).trim()
                    env.ECR_URI = "${env.ECR_REGISTRY}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${env.ECR_REPOSITORY}".toLowerCase()
                    env.APP_NAME = env.ECR_REPOSITORY
                    env.TAG = env.IMAGE_TAG

                    if (!env.ECR_REGISTRY || !env.ECR_REPOSITORY || !env.ARTIFACT_BUCKET) {
                        error "Missing required ECR/S3 settings. Need ECR_REGISTRY, ECR_REPOSITORY, and ARTIFACT_BUCKET."
                    }
                    if (fileExists('Dockerfile')) {
                        env.BASE_IMAGE = sh(script: "awk '/^FROM/ {print \$2; exit}' Dockerfile", returnStdout: true).trim()
                        echo "Base Docker Image: ${env.BASE_IMAGE}"
                    }
                }
            }

            // -------- Compile & Package (generic) --------
            stage("Compile & Package (${params.PROJECT_TYPE})") {
                compileAndPackage(params.PROJECT_TYPE)
            }

            // -------- QUALITY GATES --------
            if (params.ENABLE_SONARQUBE?.toBoolean()) {
                stage('SonarQube') {
                    def scannerHome = tool 'SONAR-SCANNER'
                    if (!fileExists('sonar-project.properties')) {
                        error "sonar-project.properties not found!"
                    }
                    withSonarQubeEnv('sonarqube') {
                        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                            sh """
                                ${scannerHome}/bin/sonar-scanner \
                                -Dproject.settings=sonar-project.properties \
                                -Dsonar.host.url=https://horizonrelevance.com/sonarqube
                            """
                        }
                    }
                    timeout(time: 10, unit: 'MINUTES') { waitForQualityGate abortPipeline: false }
                }
            }

            if (params.ENABLE_SOAPUI?.toBoolean()) {
                stage('SoapUI Tests') {
                    if (!fileExists('tests/soapui/project.xml')) {
                        error "SoapUI project not found at tests/soapui/project.xml"
                    }
                    sh 'testrunner.sh -j -f reports/soapui tests/soapui/project.xml'
                }
            }

            if (params.ENABLE_JMETER?.toBoolean()) {
                stage('JMeter Tests') {
                    if (!fileExists('tests/jmeter/test.jmx')) { error "JMeter test not found at tests/jmeter/test.jmx" }
                    sh '''
                      mkdir -p reports/jmeter
                      jmeter -n -t tests/jmeter/test.jmx -l reports/jmeter/results.jtl -e -o reports/jmeter/html
                    '''
                }
            }

            if (params.ENABLE_SELENIUM?.toBoolean()) {
                stage('Selenium UI Tests') {
                    def ran = false
                    if (fileExists('pom.xml')) { sh 'mvn -B -Dtest=*UITest* test'; ran = true }
                    else if (fileExists('package.json')) {
                        def hasScript = sh(returnStatus:true, script: "node -e \"try{let p=require('./package.json');process.exit(p.scripts&&p.scripts['test:e2e']?0:1)}catch(e){process.exit(1)}\"") == 0
                        if (hasScript) { sh 'npm ci && npm run test:e2e'; ran = true }
                    }
                    if (!ran) { error "No Selenium UI test command found." }
                }
            }

            if (params.ENABLE_NEWMAN?.toBoolean()) {
                stage('Newman/Postman') {
                    if (!fileExists('tests/postman/collection.json')) {
                        error "Postman collection not found at tests/postman/collection.json"
                    }
                    sh 'newman run tests/postman/collection.json --reporters cli,junit --reporter-junit-export reports/newman/results.xml'
                }
            }

            // -------- Build & Scan --------
            stage('Build Docker Image') {
                sh """
                  docker build \
                    -t ${env.ECR_URI}:${env.IMAGE_TAG} \
                    -t ${env.ECR_URI}:latest \
                    .
                """
            }

            if (params.ENABLE_TRIVY?.toBoolean()) {
                stage('Trivy Scan Built Image') {
                    def imageName = "${env.ECR_URI}:${env.IMAGE_TAG}".toLowerCase()
                    trivyScan(imageName: imageName, uploadResults: true, application: env.APP_NAME, buildNumber: env.BUILD_NUMBER, jenkinsJob: env.JOB_NAME)
                }
            }

            // -------- Publish --------
            stage('Push Image to ECR') {
                withEnv(awsClientEnv(params)) {
                    sh """
                      aws ecr describe-repositories \
                        --region ${env.AWS_REGION} \
                        --repository-names ${env.ECR_REPOSITORY} >/dev/null 2>&1 \
                      || aws ecr create-repository \
                        --region ${env.AWS_REGION} \
                        --repository-name ${env.ECR_REPOSITORY} >/dev/null

                      aws ecr get-login-password --region ${env.AWS_REGION} \
                      | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}.dkr.ecr.${env.AWS_REGION}.amazonaws.com

                      docker push ${env.ECR_URI}:${env.IMAGE_TAG}
                      docker push ${env.ECR_URI}:latest

                      aws ecr describe-images \
                        --region ${env.AWS_REGION} \
                        --repository-name ${env.ECR_REPOSITORY} \
                        --image-ids imageTag=${env.IMAGE_TAG} \
                        --query 'imageDetails[0].imageDigest' \
                        --output text > image-sha.txt
                    """
                    env.IMAGE_SHA = readFile('image-sha.txt').trim()
                }
            }

            stage('Publish Image Metadata Artifacts') {
                def imageUri = "${env.ECR_URI}@${env.IMAGE_SHA}"
                writeFile file: 'image.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                    ImageURI : imageUri,
                    ImageSHA : env.IMAGE_SHA,
                    ImageRepo: env.ECR_URI,
                    ImageTag : env.IMAGE_TAG
                ]))
                writeFile file: 'templateconfiguration.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                    Parameters: [
                        ProjectType: params.PROJECT_TYPE,
                        ImageName  : env.ECR_REPOSITORY,
                        ImageURI   : imageUri,
                        ImageRepo  : env.ECR_URI,
                        ImageTag   : env.IMAGE_TAG,
                        TargetEnv  : params.TARGET_ENV ?: 'EKS-NONPROD'
                    ]
                ]))

                archiveArtifacts artifacts: 'image.json,templateconfiguration.json', fingerprint: true

                withEnv(awsClientEnv(params)) {
                    sh """
                      aws s3 cp image.json s3://${env.ARTIFACT_BUCKET}/devops-pipeline/${params.PROJECT_NAME}/${env.IMAGE_TAG}/image.json --region ${env.AWS_REGION}
                      aws s3 cp templateconfiguration.json s3://${env.ARTIFACT_BUCKET}/devops-pipeline/${params.PROJECT_NAME}/${env.IMAGE_TAG}/templateconfiguration.json --region ${env.AWS_REGION}
                    """
                }
            }

            publishSns(params, 'SUCCESS', "Devops pipeline completed for ${params.PROJECT_NAME}. Image: ${env.ECR_URI}@${env.IMAGE_SHA}")

            // -------- Deploy --------
            stage("Deploy (${params.TARGET_ENV})") {
                deployHelm(ENABLE_OPA: params.ENABLE_OPA, TARGET_ENV: params.TARGET_ENV ?: 'EKS-NONPROD')
            }

        } catch (Exception e) {
            echo "Devops Pipeline failed: ${e.message}"
            currentBuild.result = 'FAILURE'
            publishSns(params, 'FAILED', "Devops pipeline failed for ${params.PROJECT_NAME}. Reason: ${e.message}")
            if (params.NOTIFY_EMAIL) {
                emailext subject: "[Devops Pipeline][FAILED] ${params.PROJECT_NAME} #${env.BUILD_NUMBER}",
                         body: """<p>Build failed for <b>${params.PROJECT_NAME}</b>.</p>
                                  <p>Reason: ${e.message}</p>
                                  <p>Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}</p>
                                  <p>Requester: ${params.REQUESTED_BY ?: 'n/a'}</p>""",
                         to: params.NOTIFY_EMAIL
            }
            throw e
        }
    }
}

def awsClientEnv(Map params) {
    def roleArn = (params.CLIENT_AWS_ROLE_ARN ?: '').toString().trim()
    if (!roleArn) {
        return []
    }

    def region = (params.AWS_REGION ?: 'us-east-1').toString().trim()
    def sessionName = "horizon-devops-${env.BUILD_NUMBER ?: 'manual'}"
    def creds = sh(
        script: """
          aws sts assume-role \
            --region ${region} \
            --role-arn '${roleArn}' \
            --role-session-name '${sessionName}' \
            --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
            --output text
        """,
        returnStdout: true
    ).trim().split()

    if (creds.size() < 3) {
        error "Unable to assume client AWS role: ${roleArn}"
    }

    return [
        "AWS_ACCESS_KEY_ID=${creds[0]}",
        "AWS_SECRET_ACCESS_KEY=${creds[1]}",
        "AWS_SESSION_TOKEN=${creds[2]}"
    ]
}

def publishSns(Map params, String status, String message) {
    if (!params.ENABLE_NOTIFICATIONS?.toBoolean() || !params.SNS_TOPIC_ARN) {
        return
    }

    def region = (params.AWS_REGION ?: 'us-east-1').toString().trim()
    writeFile file: 'sns-message.txt', text: message
    withEnv(awsClientEnv(params)) {
        sh """
          aws sns publish \
            --region ${region} \
            --topic-arn '${params.SNS_TOPIC_ARN}' \
            --subject '[Devops Pipeline][${status}] ${params.PROJECT_NAME}' \
            --message file://sns-message.txt
        """
    }
}

/* ================== Helpers for Devops compile/package ================== */
def compileAndPackage(String projectTypeRaw) {
    def projectType = (projectTypeRaw ?: '').toLowerCase()
    switch (projectType) {
        case 'docker':
            echo "Docker project: no host build required (will build image)."
            env.ARTIFACT_PATH = ''
            return

        case 'springboot':
        case 'springboot-java11':
            if (fileExists('pom.xml')) {
                sh 'mvn -B -DskipTests clean package'
                env.ARTIFACT_PATH = sh(script: "ls -1 target/*.jar | head -n1", returnStdout: true).trim()
                env.ARTIFACT_NAME = env.ARTIFACT_PATH ? env.ARTIFACT_PATH.split('/').last() : ''
            } else if (fileExists('gradlew') || fileExists('build.gradle')) {
                sh './gradlew clean build -x test || gradle clean build -x test'
                env.ARTIFACT_PATH = sh(script: "ls -1 build/libs/*.jar | head -n1", returnStdout: true).trim()
                env.ARTIFACT_NAME = env.ARTIFACT_PATH ? env.ARTIFACT_PATH.split('/').last() : ''
            } else {
                error "Spring Boot project but no pom.xml or Gradle build found."
            }
            return

        case 'angular':
            sh '''
              if [ -f package-lock.json ]; then npm ci; else npm install; fi
              npm install -g @angular/cli@latest
              npm run prodbuild || ng build --configuration=production
              mkdir -p artifact
              if [ -d dist ]; then tar -czf artifact/angular-dist.tgz dist; fi
            '''
            env.ARTIFACT_PATH = fileExists('artifact/angular-dist.tgz') ? 'artifact/angular-dist.tgz' : ''
            env.ARTIFACT_NAME = env.ARTIFACT_PATH ? 'angular-dist.tgz' : ''
            return

        case 'nodejs':
            sh '''
              if [ -f package-lock.json ]; then npm ci; else npm install; fi
              npm run build || npm run build:prod || true
              mkdir -p artifact && tar -czf artifact/app-dist.tgz dist || tar -czf artifact/app-dist.tgz build || true
            '''
            env.ARTIFACT_PATH = 'artifact/app-dist.tgz'
            env.ARTIFACT_NAME = 'app-dist.tgz'
            return

        case 'webcomponent':
            sh '''
              if [ -f package-lock.json ]; then npm ci; else npm install; fi
              npm run build
              mkdir -p artifact
              if [ -d dist ]; then tar -czf artifact/webcomponent-dist.tgz dist; fi
            '''
            env.ARTIFACT_PATH = fileExists('artifact/webcomponent-dist.tgz') ? 'artifact/webcomponent-dist.tgz' : ''
            env.ARTIFACT_NAME = env.ARTIFACT_PATH ? 'webcomponent-dist.tgz' : ''
            return

        default:
            error "Unsupported PROJECT_TYPE '${projectTypeRaw}'. Supported: Docker, Angular, SpringBoot, SpringBoot-Java11, NodeJs, WebComponent"
    }
}

return this
