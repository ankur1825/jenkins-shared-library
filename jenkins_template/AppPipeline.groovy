// ================== EXISTING PIPELINE (unchanged) ==================
def run(Map params) {
    node {
        try {
            stage('Validate License') {
                validateLicense(params + [PIPELINE_NAME: params.SERVICE_NAME ?: 'Application Pipeline'])
            }

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
                stage('Code Quality Analysis') {
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
                    echo "Code analysis project key: ${sonarProjectKey}"

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
                stage('Base Image Security Analysis') {
                    trivyScan(imageName: env.BASE_IMAGE, uploadResults: true, application: env.APP_NAME, repoUrl: params.REPO_URL, requestedBy: params.REQUESTED_BY)
                }
            }

            if (params.ENABLE_OPA?.toBoolean()) {
                stage('Policy Validation') {
                    def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    opaEnsureServerRunning()
                    def opaInput = createOPAInput(imageName, env.TAG)
                    def enrichedOPAResults = opaEvaluateCurl(inputJson: opaInput, imageName: imageName, application: env.APP_NAME, jobName: env.JOB_NAME, buildNumber: env.BUILD_NUMBER, requestedBy: env.BUILD_USER_ID)
                    echo "Policy validation complete. Total findings: ${enrichedOPAResults.size()}"
                }
            }

            stage('Build Docker Image') {
                def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                sh "docker build -t ${imageName} ."
            }

            if (params.ENABLE_TRIVY?.toBoolean()) {
                stage('Image Security Analysis') {
                    def imageName = "${env.PRIVATE_REPO}/${env.APP_NAME}:${env.TAG}".toLowerCase()
                    trivyScan(imageName: imageName, uploadResults: true, application: env.APP_NAME, repoUrl: params.REPO_URL, requestedBy: params.REQUESTED_BY)
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
            stage('Validate License') {
                validateLicense(params + [PIPELINE_NAME: 'Devops Pipeline'])
            }

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
                    } else {
                        error "Dockerfile is required because the product always creates and publishes a container image."
                    }
                }
            }

            stage('Security Preflight') {
                runSecurityPreflight(params)
            }

            // -------- Compile & Package (generic) --------
            stage("Compile & Package (${params.PROJECT_TYPE})") {
                compileAndPackage(params.PROJECT_TYPE)
            }

            // -------- QUALITY GATES --------
            if (params.ENABLE_SONARQUBE?.toBoolean()) {
                stage('Code Quality Analysis') {
                    def scannerHome = tool 'SONAR-SCANNER'
                    if (!fileExists('sonar-project.properties')) {
                        error "sonar-project.properties not found!"
                    }
                    def propsText = readFile 'sonar-project.properties'
                    def sonarProjectKey = propsText.readLines()
                        .find { it.trim().startsWith('sonar.projectKey=') }
                        ?.split('=', 2)?.getAt(1)?.trim()
                    if (!sonarProjectKey) {
                        error "sonar.projectKey is missing or empty!"
                    }
                    echo "Code analysis project key: ${sonarProjectKey}"

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
                    postProcessSonar(sonarProjectKey, params.REPO_URL, params.REQUESTED_BY ?: 'unknown')
                }
            }

            runSelectedQualityTool('Checkmarx', params.ENABLE_CHECKMARX, params)
            runSelectedQualityTool('SoapUI', params.ENABLE_SOAPUI, params)
            runSelectedQualityTool('JMeter', params.ENABLE_JMETER, params)
            runSelectedQualityTool('Selenium', params.ENABLE_SELENIUM, params)
            runSelectedQualityTool('Newman', params.ENABLE_NEWMAN, params)

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
                stage('Image Security Analysis') {
                    def imageName = "${env.ECR_URI}:${env.IMAGE_TAG}".toLowerCase()
                    trivyScan(imageName: imageName, uploadResults: true, application: env.APP_NAME, repoUrl: params.REPO_URL, requestedBy: params.REQUESTED_BY)
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

def runSecurityPreflight(Map params = [:]) {
    def failOnSeverity = (params.SECURITY_FAIL_ON_SEVERITY ?: env.SECURITY_FAIL_ON_SEVERITY ?: 'CRITICAL,HIGH').toString()

    sh """
      set -e
      mkdir -p security-preflight

      echo "Checking workspace for high-risk secret patterns..."
      set +e
      grep -RInE '(-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|aws_secret_access_key|AWS_SECRET_ACCESS_KEY|password[[:space:]]*[:=][[:space:]]*[^[:space:]]+|token[[:space:]]*[:=][[:space:]]*[^[:space:]]+)' . \\
        --exclude-dir=.git \\
        --exclude-dir=node_modules \\
        --exclude-dir=target \\
        --exclude-dir=build \\
        --exclude-dir=dist \\
        --exclude='package-lock.json' \\
        --exclude='*.png' \\
        --exclude='*.jpg' \\
        --exclude='*.jpeg' \\
        > security-preflight/secrets.txt
      secret_status=\\$?
      set -e
      if [ "\\$secret_status" -eq 0 ]; then
        echo "Potential secrets detected in source. See security-preflight/secrets.txt"
        exit 1
      fi

      if find . -name '*.tf' -not -path './.git/*' | grep -q .; then
        echo "Terraform files detected. Running IaC hygiene checks."
        if command -v terraform >/dev/null 2>&1; then
          terraform fmt -check -recursive | tee security-preflight/terraform-fmt.txt
        else
          echo "terraform CLI not installed; skipping terraform fmt check." | tee security-preflight/terraform-fmt.txt
        fi
      fi

      if command -v trivy >/dev/null 2>&1; then
        echo "Running filesystem IaC/secret security analysis."
        trivy fs --format json --scanners secret,config --severity '${failOnSeverity}' --exit-code 1 \\
          --output security-preflight/filesystem-security.json .
      else
        echo "trivy CLI not installed on this Jenkins agent; filesystem scan is handled by image analysis later when enabled." \\
          | tee security-preflight/filesystem-security.txt
      fi
    """

    def manifestCandidates = sh(
        script: "find . \\( -path './k8s/*' -o -path './kubernetes/*' -o -path './manifests/*' \\) -type f \\( -name '*.yaml' -o -name '*.yml' \\) -not -path './.git/*' | head -n 1",
        returnStdout: true
    ).trim()

    if (manifestCandidates) {
        sh 'mkdir -p security-preflight/opa-policies/helpers security-preflight/opa-policies/deny security-preflight/opa-policies/violation security-preflight/opa-policies/warn'
        writeFile file: 'security-preflight/opa-policies/deny/deny.rego', text: libraryResource('policy/deny/deny.rego')
        writeFile file: 'security-preflight/opa-policies/helpers/kubernetes.rego', text: libraryResource('policy/helpers/kubernetes.rego')
        writeFile file: 'security-preflight/opa-policies/violation/violation.rego', text: libraryResource('policy/violation/violation.rego')
        writeFile file: 'security-preflight/opa-policies/warn/warn.rego', text: libraryResource('policy/warn/warn.rego')
        sh """
          if command -v conftest >/dev/null 2>&1; then
            manifest_dirs=""
            [ -d k8s ] && manifest_dirs="\\$manifest_dirs k8s"
            [ -d kubernetes ] && manifest_dirs="\\$manifest_dirs kubernetes"
            [ -d manifests ] && manifest_dirs="\\$manifest_dirs manifests"
            conftest test \\$manifest_dirs -p security-preflight/opa-policies --output json \\
              > security-preflight/kubernetes-policy.json
          else
            echo "conftest CLI not installed; skipping Kubernetes manifest policy validation." \\
              | tee security-preflight/kubernetes-policy.txt
          fi
        """
    }

    archiveArtifacts artifacts: 'security-preflight/**', allowEmptyArchive: true
}

def runSelectedQualityTool(String tool, Object enabled, Map params) {
    if (!enabled?.toString()?.equalsIgnoreCase('true')) {
        return
    }

    def displayName = qualityToolDisplayName(tool)
    stage("${displayName}") {
        def toolKey = tool.toLowerCase()
        def reportDir = "reports/${toolKey}"
        sh "mkdir -p '${reportDir}'"

        try {
            switch (tool) {
                case 'Checkmarx':
                    runCheckmarx(reportDir, params)
                    break
                case 'SoapUI':
                    runSoapUi(reportDir)
                    break
                case 'JMeter':
                    runJMeter(reportDir)
                    break
                case 'Selenium':
                    runSelenium(reportDir)
                    break
                case 'Newman':
                    runNewman(reportDir)
                    break
                default:
                    error "Unsupported quality tool: ${tool}"
            }

            writeFile file: "${reportDir}/status.txt", text: "${displayName} completed successfully\n"
            publishQualityResults(tool, reportDir, params)
            publishSns(params, 'SUCCESS', "${displayName} completed for ${params.PROJECT_NAME}.")
        } catch (Exception e) {
            writeFile file: "${reportDir}/status.txt", text: "${displayName} failed: ${e.message}\n"
            publishQualityResults(tool, reportDir, params)
            publishSns(params, 'FAILED', "${displayName} failed for ${params.PROJECT_NAME}. Reason: ${e.message}")
            throw e
        }
    }
}

def qualityToolDisplayName(String tool) {
    switch (tool) {
        case 'Checkmarx':
            return 'Static Application Security'
        case 'SoapUI':
            return 'Service Contract Testing'
        case 'JMeter':
            return 'Performance Load Testing'
        case 'Selenium':
            return 'Browser Workflow Testing'
        case 'Newman':
            return 'API Regression Testing'
        default:
            return 'Quality Gate'
    }
}

def runCheckmarx(String reportDir, Map params) {
    def projectName = params.PROJECT_NAME ?: env.JOB_NAME
    def team = params.CHECKMARX_TEAM ?: ''
    sh """
      if command -v cx >/dev/null 2>&1; then
        cx scan create --project-name '${projectName}' ${team ? "--team '${team}'" : ''} --source . --report-format json --output '${reportDir}/checkmarx.json'
      elif command -v checkmarx >/dev/null 2>&1; then
        checkmarx scan --project '${projectName}' --source . --output '${reportDir}/checkmarx.json'
      else
        echo 'Checkmarx CLI is not installed on this Jenkins agent.' | tee '${reportDir}/checkmarx.txt'
        exit 1
      fi
    """
}

def runSoapUi(String reportDir) {
    if (!fileExists('tests/soapui/project.xml')) {
        error "SoapUI project not found at tests/soapui/project.xml"
    }
    sh "testrunner.sh -j -f '${reportDir}' tests/soapui/project.xml"
}

def runJMeter(String reportDir) {
    if (!fileExists('tests/jmeter/test.jmx')) {
        error "JMeter test not found at tests/jmeter/test.jmx"
    }
    sh """
      jmeter -n -t tests/jmeter/test.jmx -l '${reportDir}/results.jtl' -e -o '${reportDir}/html'
    """
}

def runSelenium(String reportDir) {
    def ran = false
    if (fileExists('pom.xml')) {
        sh "mvn -B -Dtest=*UITest* -Dsurefire.reportsDirectory='${reportDir}' test"
        ran = true
    } else if (fileExists('package.json')) {
        def hasScript = sh(returnStatus:true, script: "node -e \"try{let p=require('./package.json');process.exit(p.scripts&&p.scripts['test:e2e']?0:1)}catch(e){process.exit(1)}\"") == 0
        if (hasScript) {
            sh """
              if [ -f package-lock.json ]; then npm ci; else npm install; fi
              npm run test:e2e -- --outputPath='${reportDir}' || npm run test:e2e
            """
            ran = true
        }
    }
    if (!ran) {
        error "No Selenium UI test command found. Expected Maven UI tests or npm script test:e2e."
    }
}

def runNewman(String reportDir) {
    if (!fileExists('tests/postman/collection.json')) {
        error "Postman collection not found at tests/postman/collection.json"
    }
    sh "newman run tests/postman/collection.json --reporters cli,junit,json --reporter-junit-export '${reportDir}/results.xml' --reporter-json-export '${reportDir}/results.json'"
}

def publishQualityResults(String tool, String reportDir, Map params) {
    archiveArtifacts artifacts: "${reportDir}/**", allowEmptyArchive: true, fingerprint: true

    if (!env.ARTIFACT_BUCKET) {
        echo "ARTIFACT_BUCKET is not configured; skipping S3 upload for ${tool}."
        return
    }

    def imageTag = env.IMAGE_TAG ?: (env.BUILD_NUMBER ?: 'manual')
    withEnv(awsClientEnv(params)) {
        sh """
          aws s3 sync '${reportDir}' 's3://${env.ARTIFACT_BUCKET}/devops-pipeline/${params.PROJECT_NAME}/${imageTag}/test-results/${tool.toLowerCase()}/' --region ${env.AWS_REGION}
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
              npm run build || npm run build:prod
              mkdir -p artifact
              if [ -d dist ]; then
                tar -czf artifact/app-dist.tgz dist
              elif [ -d build ]; then
                tar -czf artifact/app-dist.tgz build
              else
                echo "No dist/ or build/ output found after Node.js build."
                exit 1
              fi
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
