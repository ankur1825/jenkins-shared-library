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

                    runSonarAnalysisAndGate(sonarProjectKey)

                    wrap([$class: 'BuildUser']) {
                        def triggeredBy = env.BUILD_USER ?: "unknown"
                        echo "Build triggered by: ${triggeredBy}"

                        script {
                            postProcessSonar(sonarProjectKey, params.REPO_URL, triggeredBy)
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

            // -------- Compile & Package (generic) --------
            stage("Compile & Package (${params.PROJECT_TYPE})") {
                compileAndPackage(params.PROJECT_TYPE)
            }

            // Security, quality, and test gates are intentionally handled by Test Devops Pipeline.

            // -------- Build --------
            stage('Build Docker Image') {
                sh """
                  docker build \
                    -t ${env.ECR_URI}:${env.IMAGE_TAG} \
                    -t ${env.ECR_URI}:latest \
                    .
                """
            }

            // Image scanning is intentionally handled by Test Devops Pipeline.


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
                deployHelm(params + [ENABLE_OPA: params.ENABLE_OPA, TARGET_ENV: params.TARGET_ENV ?: 'EKS-NONPROD'])
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

// ================== TEST DEVOPS PIPELINE ==================
def runTestDevops(Map params) {
    /*
      Test Devops Pipeline owns security, quality, and functional test gates.
      It does not build or deploy application images; it checks out the client
      repository, runs the selected scanners/test suites, archives results, and
      publishes results to the client artifact bucket when configured.
    */
    node {
        try {
            stage('Validate License') {
                validateLicense(params + [PIPELINE_NAME: 'Test Devops Pipeline', PIPELINE_KIND: 'TEST_DEVOPS'])
            }

            stage('Checkout Application Repo') {
                if (!params.REPO_URL || !params.REPO_URL.toString().contains('/')) {
                    error "REPO_URL is invalid ('${params.REPO_URL}'). Provide a full Git URL."
                }
                echo "Cloning repository: ${params.REPO_URL} (branch ${params.BRANCH ?: 'main'})"
                git credentialsId: (params.CREDENTIALS_ID ?: 'github-token'), url: params.REPO_URL, branch: (params.BRANCH ?: 'main')
                sh 'pwd && ls -lrth'
            }

            stage('Prepare Test Context') {
                script {
                    env.AWS_REGION = (params.AWS_REGION ?: 'us-east-1').toString().trim()
                    env.ARTIFACT_BUCKET = (params.ARTIFACT_BUCKET ?: '').toString().trim()
                    env.APP_NAME = (params.PROJECT_NAME ?: env.JOB_NAME ?: 'application').toString().trim()
                    env.IMAGE_TAG = (params.BUILD_TAG_OVERRIDE ?: env.BUILD_NUMBER ?: 'manual').toString().trim()
                    env.TEST_RESULTS_PREFIX = "test-devops-pipeline/${env.APP_NAME}/${env.BUILD_NUMBER ?: env.IMAGE_TAG}"
                }
            }

            if (params.ENABLE_SONARQUBE?.toBoolean()) {
                stage('Code Quality Analysis') {
                    runSonarQubeAnalysis(params)
                }
            }

            if (params.ENABLE_TRIVY?.toBoolean() || params.ENABLE_OPA?.toBoolean()) {
                stage('Security Preflight') {
                    runSecurityPreflight(params)
                }
            }

            if (params.ENABLE_TRIVY?.toBoolean() && params.IMAGE_URI) {
                stage('Image Security Analysis') {
                    trivyScan(
                        imageName: params.IMAGE_URI.toString().trim(),
                        uploadResults: true,
                        application: env.APP_NAME,
                        repoUrl: params.REPO_URL,
                        requestedBy: params.REQUESTED_BY
                    )
                }
            }

            runSelectedQualityTool('Checkmarx', params.ENABLE_CHECKMARX, params)
            runSelectedQualityTool('SoapUI', params.ENABLE_SOAPUI, params)
            runSelectedQualityTool('JMeter', params.ENABLE_JMETER, params)
            runSelectedQualityTool('Selenium', params.ENABLE_SELENIUM, params)
            runSelectedQualityTool('Newman', params.ENABLE_NEWMAN, params)
            runSelectedQualityTool('RestAssured', params.ENABLE_RESTASSURED, params)
            runSelectedQualityTool('UFT', params.ENABLE_UFT, params)

            stage('Publish Test Summary') {
                writeFile file: 'test-summary.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                    application: env.APP_NAME,
                    projectType: params.PROJECT_TYPE ?: '',
                    repoUrl: params.REPO_URL ?: '',
                    branch: params.BRANCH ?: 'main',
                    requestedBy: params.REQUESTED_BY ?: 'unknown',
                    buildUrl: env.BUILD_URL,
                    resultPrefix: env.TEST_RESULTS_PREFIX,
                    completedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('UTC'))
                ]))
                archiveArtifacts artifacts: 'test-summary.json,reports/**,security-preflight/**', allowEmptyArchive: true, fingerprint: true

                if (env.ARTIFACT_BUCKET) {
                    withEnv(awsClientEnv(params)) {
                        sh """
                          aws s3 cp test-summary.json s3://${env.ARTIFACT_BUCKET}/${env.TEST_RESULTS_PREFIX}/test-summary.json --region ${env.AWS_REGION}
                        """
                    }
                }
            }

            publishSns(params, 'SUCCESS', "Test Devops pipeline completed for ${env.APP_NAME}. Results prefix: ${env.TEST_RESULTS_PREFIX}")
        } catch (Exception e) {
            echo "Test Devops Pipeline failed: ${e.message}"
            currentBuild.result = 'FAILURE'
            publishSns(params, 'FAILED', "Test Devops pipeline failed for ${params.PROJECT_NAME}. Reason: ${e.message}")
            if (params.NOTIFY_EMAIL) {
                emailext subject: "[Test Devops Pipeline][FAILED] ${params.PROJECT_NAME} #${env.BUILD_NUMBER}",
                         body: """<p>Test pipeline failed for <b>${params.PROJECT_NAME}</b>.</p>
                                  <p>Reason: ${e.message}</p>
                                  <p>Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}</p>
                                  <p>Requester: ${params.REQUESTED_BY ?: 'n/a'}</p>""",
                         to: params.NOTIFY_EMAIL
            }
            throw e
        }
    }
}

// ================== PROD DEVOPS PIPELINE ==================
def runProdDevops(Map params) {
    /*
      Production promotion pipeline:
      - does not checkout source
      - does not build image
      - reads image.json/templateconfiguration.json from client S3
      - promotes immutable image digest to production tag/repository
      - requires approval before deployment
    */
    node {
        try {
            stage('Validate License') {
                validateLicense(params + [PIPELINE_NAME: 'Prod Devops Pipeline'])
            }

            stage('Load Release Metadata') {
                script {
                    env.AWS_REGION = (params.AWS_REGION ?: 'us-east-1').toString().trim()
                    env.ARTIFACT_BUCKET = (params.ARTIFACT_BUCKET ?: '').toString().trim()
                    env.ARTIFACT_PREFIX = (params.ARTIFACT_PREFIX ?: '').toString().trim().replaceAll('^/|/$', '')
                    env.IMAGE_JSON_PATH = (params.IMAGE_JSON_PATH ?: "${env.ARTIFACT_PREFIX}/image.json").toString().trim().replaceAll('^/', '')
                    env.TEMPLATE_CONFIG_PATH = (params.TEMPLATE_CONFIG_PATH ?: "${env.ARTIFACT_PREFIX}/templateconfiguration.json").toString().trim().replaceAll('^/', '')
                    env.SOURCE_ECR_REGISTRY = (params.SOURCE_ECR_REGISTRY ?: '').toString().trim()
                    env.SOURCE_ECR_REPOSITORY = (params.SOURCE_ECR_REPOSITORY ?: params.PROJECT_NAME ?: '').toString().trim()
                    env.TARGET_ECR_REGISTRY = (params.TARGET_ECR_REGISTRY ?: '').toString().trim()
                    env.TARGET_ECR_REPOSITORY = (params.TARGET_ECR_REPOSITORY ?: params.PROJECT_NAME ?: '').toString().trim()
                    env.TARGET_IMAGE_TAG = (params.TARGET_IMAGE_TAG ?: 'prod').toString().trim()
                    env.TARGET_ENV = (params.TARGET_ENV ?: 'EKS-PROD').toString().trim()
                    env.APP_NAME = env.TARGET_ECR_REPOSITORY

                    if (!env.ARTIFACT_BUCKET || !env.IMAGE_JSON_PATH || !env.SOURCE_ECR_REGISTRY || !env.SOURCE_ECR_REPOSITORY || !env.TARGET_ECR_REGISTRY || !env.TARGET_ECR_REPOSITORY) {
                        error "Missing production promotion inputs. Need artifact bucket/path and source/target ECR settings."
                    }
                    if (!env.TARGET_ENV.toUpperCase().contains('PROD')) {
                        error "Prod Devops Pipeline only supports production target environments."
                    }

                    withEnv(awsClientEnv(params, 'SOURCE_AWS_ROLE_ARN')) {
                        sh """
                          set -e
                          aws s3 cp s3://${env.ARTIFACT_BUCKET}/${env.IMAGE_JSON_PATH} image.json --region ${env.AWS_REGION}
                          aws s3 cp s3://${env.ARTIFACT_BUCKET}/${env.TEMPLATE_CONFIG_PATH} templateconfiguration.json --region ${env.AWS_REGION}
                          test -s image.json
                          test -s templateconfiguration.json
                        """
                    }

                    def imageMeta = readJSON file: 'image.json'
                    env.SOURCE_IMAGE_DIGEST = (imageMeta.ImageSHA ?: imageMeta.imageDigest ?: '').toString().trim()
                    env.SOURCE_IMAGE_TAG = (params.SOURCE_IMAGE_TAG ?: imageMeta.ImageTag ?: '').toString().trim()
                    env.SOURCE_IMAGE_REPO = (imageMeta.ImageRepo ?: "${env.SOURCE_ECR_REGISTRY}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${env.SOURCE_ECR_REPOSITORY}").toString().trim()

                    if (!env.SOURCE_IMAGE_DIGEST?.startsWith('sha256:')) {
                        error "image.json must include immutable ImageSHA/imageDigest."
                    }

                    archiveArtifacts artifacts: 'image.json,templateconfiguration.json', fingerprint: true
                    echo "Loaded release image digest ${env.SOURCE_IMAGE_DIGEST} for ${params.PROJECT_NAME}."
                }
            }

            stage('Validate Artifact Evidence') {
                withEnv(awsClientEnv(params, 'SOURCE_AWS_ROLE_ARN')) {
                    sh """
                      set -e
                      aws ecr describe-images \
                        --region ${env.AWS_REGION} \
                        --registry-id ${env.SOURCE_ECR_REGISTRY} \
                        --repository-name ${env.SOURCE_ECR_REPOSITORY} \
                        --image-ids imageDigest=${env.SOURCE_IMAGE_DIGEST} >/dev/null

                      aws s3 ls s3://${env.ARTIFACT_BUCKET}/${env.ARTIFACT_PREFIX}/ --region ${env.AWS_REGION} >/dev/null
                    """
                }
            }

            stage('Create / Update Secrets') {
                if (!params.SECRET_ENABLED?.toBoolean()) {
                    echo "Secret management disabled for this production deployment."
                } else {
                    def xids = (params.XID_ARRAY ?: '').toString().split(',').collect { it.trim() }.findAll { it }
                    if (!xids) {
                        error "SECRET_ENABLED is true, but XID_ARRAY is empty."
                    }
                    withEnv(awsClientEnv(params, 'TARGET_AWS_ROLE_ARN')) {
                        xids.each { xid ->
                            def secretName = "/horizon/${params.PROJECT_NAME}/${env.TARGET_ENV}/${xid}"
                            sh """
                              set +x
                              aws secretsmanager describe-secret --secret-id '${secretName}' --region ${env.AWS_REGION} >/dev/null 2>&1 \
                              || aws secretsmanager create-secret \
                                   --name '${secretName}' \
                                   --description 'Managed by Horizon Relevance production pipeline for ${params.PROJECT_NAME}' \
                                   --secret-string '{"managedBy":"horizon-relevance","application":"${params.PROJECT_NAME}","environment":"${env.TARGET_ENV}","xid":"${xid}"}' \
                                   --region ${env.AWS_REGION} >/dev/null
                            """
                        }
                    }
                }
            }

            stage('Promote Image To Production ECR') {
                withEnv(awsClientEnv(params, 'SOURCE_AWS_ROLE_ARN')) {
                    sh """
                      set -e
                      aws ecr batch-get-image \
                        --region ${env.AWS_REGION} \
                        --registry-id ${env.SOURCE_ECR_REGISTRY} \
                        --repository-name ${env.SOURCE_ECR_REPOSITORY} \
                        --image-ids imageDigest=${env.SOURCE_IMAGE_DIGEST} \
                        --query 'images[0].imageManifest' \
                        --output text > image-manifest.json

                      test -s image-manifest.json
                    """
                }
                withEnv(awsClientEnv(params, 'TARGET_AWS_ROLE_ARN')) {
                    sh """
                      set -e
                      aws ecr describe-repositories \
                        --region ${env.AWS_REGION} \
                        --registry-id ${env.TARGET_ECR_REGISTRY} \
                        --repository-names ${env.TARGET_ECR_REPOSITORY} >/dev/null 2>&1 \
                      || aws ecr create-repository \
                        --region ${env.AWS_REGION} \
                        --repository-name ${env.TARGET_ECR_REPOSITORY} >/dev/null

                      for tag in \$(echo "${env.TARGET_IMAGE_TAG}" | tr ',' ' '); do
                        clean_tag="\$(echo "\$tag" | sed 's/^ *//;s/ *\$//')"
                        [ -z "\$clean_tag" ] && continue
                        aws ecr put-image \
                          --region ${env.AWS_REGION} \
                          --registry-id ${env.TARGET_ECR_REGISTRY} \
                          --repository-name ${env.TARGET_ECR_REPOSITORY} \
                          --image-tag "\$clean_tag" \
                          --image-manifest file://image-manifest.json >/dev/null
                      done
                    """
                    env.PROD_IMAGE_URI = "${env.TARGET_ECR_REGISTRY}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${env.TARGET_ECR_REPOSITORY}@${env.SOURCE_IMAGE_DIGEST}".toLowerCase()
                }
            }

            stage('Manual Approval') {
                def approver = (params.APPROVER ?: params.REQUESTED_BY ?: 'release-approver').toString()
                timeout(time: 60, unit: 'MINUTES') {
                    input message: "Approve production deployment for ${params.PROJECT_NAME} to ${env.TARGET_ENV} using ${env.PROD_IMAGE_URI}?", ok: 'Approve'
                }
                writeFile file: 'approval.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                    application: params.PROJECT_NAME,
                    targetEnv: env.TARGET_ENV,
                    imageUri: env.PROD_IMAGE_URI,
                    requestedBy: params.REQUESTED_BY ?: 'unknown',
                    approver: approver,
                    approvedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('UTC')),
                    buildUrl: env.BUILD_URL
                ]))
                archiveArtifacts artifacts: 'approval.json', fingerprint: true
            }

            stage("Deploy Approved Image (${env.TARGET_ENV})") {
                env.IMAGE_URI = env.PROD_IMAGE_URI
                deployHelm(params + [ENABLE_OPA: true, TARGET_ENV: env.TARGET_ENV])
            }

            stage('Publish Deployment Evidence') {
                writeFile file: 'deployment.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                    application: params.PROJECT_NAME,
                    sourceEnv: params.SOURCE_ENV ?: 'STAGE',
                    targetEnv: env.TARGET_ENV,
                    imageUri: env.PROD_IMAGE_URI,
                    imageDigest: env.SOURCE_IMAGE_DIGEST,
                    targetTags: env.TARGET_IMAGE_TAG,
                    jenkinsJob: env.JOB_NAME,
                    buildNumber: env.BUILD_NUMBER,
                    buildUrl: env.BUILD_URL,
                    deployedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('UTC'))
                ]))
                archiveArtifacts artifacts: 'deployment.json', fingerprint: true
                withEnv(awsClientEnv(params, 'TARGET_AWS_ROLE_ARN')) {
                    sh """
                      aws s3 cp approval.json s3://${env.ARTIFACT_BUCKET}/${env.ARTIFACT_PREFIX}/prod/approval.json --region ${env.AWS_REGION}
                      aws s3 cp deployment.json s3://${env.ARTIFACT_BUCKET}/${env.ARTIFACT_PREFIX}/prod/deployment.json --region ${env.AWS_REGION}
                    """
                }
            }

            publishSns(params, 'SUCCESS', "Prod deployment completed for ${params.PROJECT_NAME}. Image: ${env.PROD_IMAGE_URI}")
        } catch (Exception e) {
            echo "Prod Devops Pipeline failed: ${e.message}"
            currentBuild.result = 'FAILURE'
            publishSns(params, 'FAILED', "Prod deployment failed for ${params.PROJECT_NAME}. Reason: ${e.message}")
            if (params.NOTIFY_EMAIL) {
                emailext subject: "[Prod Devops Pipeline][FAILED] ${params.PROJECT_NAME} #${env.BUILD_NUMBER}",
                         body: """<p>Production deployment failed for <b>${params.PROJECT_NAME}</b>.</p>
                                  <p>Reason: ${e.message}</p>
                                  <p>Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}</p>
                                  <p>Requester: ${params.REQUESTED_BY ?: 'n/a'}</p>""",
                         to: params.NOTIFY_EMAIL
            }
            throw e
        }
    }
}

def awsClientEnv(Map params, String preferredRoleParam = 'CLIENT_AWS_ROLE_ARN') {
    def roleArn = (params[preferredRoleParam] ?: params.CLIENT_AWS_ROLE_ARN ?: '').toString().trim()
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

def runSonarQubeAnalysis(Map params) {
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

    runSonarAnalysisAndGate(sonarProjectKey)
    postProcessSonar(sonarProjectKey, params.REPO_URL, params.REQUESTED_BY ?: 'unknown')
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
      secret_status=\$?
      set -e
      if [ "\$secret_status" -eq 0 ]; then
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
            [ -d k8s ] && manifest_dirs="\$manifest_dirs k8s"
            [ -d kubernetes ] && manifest_dirs="\$manifest_dirs kubernetes"
            [ -d manifests ] && manifest_dirs="\$manifest_dirs manifests"
            conftest test \$manifest_dirs -p security-preflight/opa-policies --output json \\
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
                    runSelenium(reportDir, params)
                    break
                case 'Newman':
                    runNewman(reportDir)
                    break
                case 'RestAssured':
                    runRestAssured(reportDir)
                    break
                case 'UFT':
                    runUft(reportDir)
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
        case 'RestAssured':
            return 'API Contract Testing'
        case 'UFT':
            return 'Enterprise Functional Testing'
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

def runSelenium(String reportDir, Map params = [:]) {
    def ran = false
    def targetAppUrl = (params.TARGET_APP_URL ?: params.APPLICATION_URL ?: params.APP_URL ?: env.TARGET_APP_URL ?: '').toString().trim()
    if (!targetAppUrl && params.TARGET_ENV) {
        echo "TARGET_APP_URL was not provided. Selenium tests will use the test suite default URL if configured."
    }
    if (fileExists('pom.xml')) {
        withEnv(["TARGET_APP_URL=${targetAppUrl}", "SELENIUM_REPORT_DIR=${reportDir}"]) {
            sh "mvn -B -Dtest=*UITest* -Dsurefire.reportsDirectory='${reportDir}' test"
        }
        ran = true
    } else if (fileExists('package.json')) {
        def nodeHome = ensureNodeRuntime()
        def nodePathEnv = nodeHome ? "PATH+NODE=${nodeHome}/bin" : "PATH+NODE="
        withEnv([nodePathEnv]) {
            def hasScript = sh(returnStatus:true, script: "node -e \"try{let p=require('./package.json');process.exit(p.scripts&&p.scripts['test:e2e']?0:1)}catch(e){process.exit(1)}\"") == 0
            if (hasScript) {
                withEnv(["TARGET_APP_URL=${targetAppUrl}", "SELENIUM_REPORT_DIR=${reportDir}", "CI=true"]) {
                    sh """
                      set -e
                      if [ -f package-lock.json ]; then npm ci; else npm install; fi
                      if node -e "let p=require('./package.json');process.exit(p.devDependencies&&p.devDependencies['@playwright/test']?0:1)" >/dev/null 2>&1; then
                        npx playwright install chromium
                      fi
                      npm run test:e2e
                    """
                }
                ran = true
            }
        }
    }
    if (!ran) {
        error "No Selenium UI test command found. Expected Maven UI tests or npm script test:e2e."
    }
}

def ensureNodeRuntime() {
    def existingNode = sh(returnStdout: true, script: "command -v node || true").trim()
    if (existingNode) {
        echo "Using existing Node.js runtime: ${existingNode}"
        return ''
    }

    def nodeVersion = 'v20.11.1'
    def nodeHome = "${pwd()}/.tools/node/current"
    sh """
      set -e
      mkdir -p .tools/node/current
      if [ ! -x .tools/node/current/bin/node ]; then
        arch="\$(uname -m)"
        case "\$arch" in
          x86_64|amd64) node_arch="x64" ;;
          aarch64|arm64) node_arch="arm64" ;;
          *) echo "Unsupported architecture for Node.js bootstrap: \$arch"; exit 1 ;;
        esac
        archive="node-${nodeVersion}-linux-\${node_arch}.tar.gz"
        url="https://nodejs.org/dist/${nodeVersion}/\${archive}"
        echo "Downloading Node.js ${nodeVersion} from \$url"
        curl -fsSL "\$url" -o .tools/node/node.tar.gz
        tar -xzf .tools/node/node.tar.gz -C .tools/node/current --strip-components=1
      fi
      export PATH="\$PWD/.tools/node/current/bin:\$PATH"
      node --version
      npm --version
    """
    return nodeHome
}

def runNewman(String reportDir) {
    if (!fileExists('tests/postman/collection.json')) {
        error "Postman collection not found at tests/postman/collection.json"
    }
    sh "newman run tests/postman/collection.json --reporters cli,junit,json --reporter-junit-export '${reportDir}/results.xml' --reporter-json-export '${reportDir}/results.json'"
}

def runRestAssured(String reportDir) {
    def ran = false
    if (fileExists('pom.xml')) {
        sh "mvn -B -Dtest=*RestAssured*,*ApiTest* -Dsurefire.reportsDirectory='${reportDir}' test"
        ran = true
    } else if (fileExists('gradlew') || fileExists('build.gradle')) {
        sh "./gradlew test --tests '*RestAssured*' --tests '*ApiTest*' || gradle test --tests '*RestAssured*' --tests '*ApiTest*'"
        sh "mkdir -p '${reportDir}' && cp -R build/test-results/test/* '${reportDir}/' 2>/dev/null || true"
        ran = true
    }
    if (!ran) {
        error "No RestAssured test command found. Expected Maven/Gradle tests named *RestAssured* or *ApiTest*."
    }
}

def runUft(String reportDir) {
    if (fileExists('tests/uft/run-uft.sh')) {
        sh "chmod +x tests/uft/run-uft.sh && tests/uft/run-uft.sh '${reportDir}'"
    } else if (fileExists('tests/uft')) {
        sh "echo 'UFT assets found under tests/uft, but no run-uft.sh launcher exists.' | tee '${reportDir}/uft.txt'; exit 1"
    } else {
        error "UFT test assets not found. Expected tests/uft/run-uft.sh."
    }
}

def publishQualityResults(String tool, String reportDir, Map params) {
    archiveArtifacts artifacts: "${reportDir}/**", allowEmptyArchive: true, fingerprint: true

    if (!env.ARTIFACT_BUCKET) {
        echo "ARTIFACT_BUCKET is not configured; skipping S3 upload for ${tool}."
        return
    }

    def pipelinePrefix = params.PIPELINE_KIND?.toString()?.equalsIgnoreCase('TEST_DEVOPS') ? 'test-devops-pipeline' : 'devops-pipeline'
    def imageTag = env.IMAGE_TAG ?: (env.BUILD_NUMBER ?: 'manual')
    withEnv(awsClientEnv(params)) {
        sh """
          aws s3 sync '${reportDir}' 's3://${env.ARTIFACT_BUCKET}/${pipelinePrefix}/${params.PROJECT_NAME}/${imageTag}/test-results/${tool.toLowerCase()}/' --region ${env.AWS_REGION}
        """
    }
}

def sonarHostUrl() {
    return (env.SONAR_HOST_URL ?: 'http://sonarqube.horizon-relevance-dev.svc.cluster.local:9000/sonarqube').trim()
}

def runSonarAnalysisAndGate(String projectKey) {
    def hostUrl = sonarHostUrl()
    withEnv(["SONAR_HOST_URL=${hostUrl}", "SONAR_PROJECT_KEY=${projectKey}"]) {
        sh '''
          set +x
          SCANNER="$(command -v sonar-scanner || true)"
          if [ -z "$SCANNER" ] && [ -x /opt/sonar-scanner/bin/sonar-scanner ]; then
            SCANNER=/opt/sonar-scanner/bin/sonar-scanner
          fi
          if [ -z "$SCANNER" ] && [ -x /var/jenkins_home/tools/sonar-scanner/bin/sonar-scanner ]; then
            SCANNER=/var/jenkins_home/tools/sonar-scanner/bin/sonar-scanner
          fi
          if [ -z "$SCANNER" ]; then
            echo "sonar-scanner CLI is not available in the Jenkins agent image."
            exit 1
          fi

          TOKEN_ARG=""
          if [ -n "${SONAR_TOKEN:-}" ]; then
            TOKEN_ARG="-Dsonar.token=${SONAR_TOKEN}"
          fi

          "$SCANNER" \
            -Dproject.settings=sonar-project.properties \
            -Dsonar.host.url="$SONAR_HOST_URL" \
            $TOKEN_ARG
        '''

        timeout(time: 10, unit: 'MINUTES') {
            sh '''
              set +x
              if [ ! -f .scannerwork/report-task.txt ]; then
                echo "Sonar scanner report-task.txt was not created."
                exit 1
              fi

              CE_TASK_ID="$(awk -F= '/^ceTaskId=/{print $2}' .scannerwork/report-task.txt)"
              if [ -z "$CE_TASK_ID" ]; then
                echo "Sonar Compute Engine task id is missing."
                exit 1
              fi

              AUTH_ARGS=""
              if [ -n "${SONAR_TOKEN:-}" ]; then
                AUTH_ARGS="-u ${SONAR_TOKEN}:"
              fi

              for i in $(seq 1 60); do
                curl -fsS $AUTH_ARGS "$SONAR_HOST_URL/api/ce/task?id=$CE_TASK_ID" -o sonar-ce-task.json
                STATUS="$(python3 - <<'PY'
import json
print(json.load(open('sonar-ce-task.json')).get('task', {}).get('status', ''))
PY
)"
                if [ "$STATUS" = "SUCCESS" ]; then
                  python3 - <<'PY' > sonar-analysis-id.txt
import json
print(json.load(open('sonar-ce-task.json')).get('task', {}).get('analysisId', ''))
PY
                  break
                fi
                if [ "$STATUS" = "FAILED" ] || [ "$STATUS" = "CANCELED" ]; then
                  echo "Sonar Compute Engine task ended with status: $STATUS"
                  exit 1
                fi
                sleep 10
              done

              ANALYSIS_ID="$(cat sonar-analysis-id.txt 2>/dev/null || true)"
              if [ -z "$ANALYSIS_ID" ]; then
                echo "Timed out waiting for Sonar analysis completion."
                exit 1
              fi

              curl -fsS $AUTH_ARGS "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$ANALYSIS_ID" -o sonar-quality-gate.json
              QG_STATUS="$(python3 - <<'PY'
import json
print(json.load(open('sonar-quality-gate.json')).get('projectStatus', {}).get('status', 'UNKNOWN'))
PY
)"
              echo "Sonar quality gate status: $QG_STATUS"
              if [ "$QG_STATUS" = "ERROR" ]; then
                echo "Sonar quality gate failed."
                exit 1
              fi
            '''
        }
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
              if ! command -v npm >/dev/null 2>&1; then
                echo "npm is not installed on this Jenkins agent; skipping host Angular build."
                echo "The Angular Dockerfile will run npm install/build inside the Docker image build."
                mkdir -p artifact
                exit 0
              fi
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
