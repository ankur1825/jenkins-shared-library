node {
    stage('Clone Repository') {
        script {
            def repoUrl = params.REPO_URL
            if (!repoUrl) {
                error "Repository URL is missing. Please provide a valid URL."
            }
            echo "Cloning repository: ${repoUrl}"
            git url: repoUrl
        }
    }

    stage('Build & Compile') {
        script {
            if (fileExists('gradlew')) {
                sh './gradlew build'
            } else if (fileExists('pom.xml')) {
                sh 'mvn clean package'
            } else {
                error "Build tool not found. Please include either a Gradle or Maven build file."
            }
        }
    }

    stage('Unit Test') {
        script {
            if (fileExists('gradlew')) {
                sh './gradlew test'
            } else if (fileExists('pom.xml')) {
                sh 'mvn test'
            } else {
                echo "Skipping tests as no valid build tool was found."
            }
        }
    }

    if (params.ENABLE_SONARQUBE?.toBoolean()) {
        stage('Static Code Analysis') {
            script {
                echo "Running SonarQube analysis..."
                sh 'sonar-scanner -Dsonar.projectKey=java-app'
            }
        }
    } else {
        echo "Skipping Static Code Analysis as SonarQube is disabled."
    }

    stage('Build Docker Image') {
        script {
            def imageName = params.IMAGE_NAME ?: 'my-java-app'
            sh "docker build -t ${imageName} ."
        }
    }

    if (params.ENABLE_OPA?.toBoolean()) {
        stage('Docker Security Scan') {
            script {
                echo "Running Docker Security Scan with OPA..."
                sh 'opa eval --input dockerfile.json policy.rego || true'
                sh 'trivy image my-java-app || true'
            }
        }
    } else {
        echo "Skipping Docker Security Scan as OPA is disabled."
    }

    stage('Kubernetes Config Validation') {
        script {
            if (fileExists('k8s/deployment.yaml')) {
                sh 'kube-linter lint k8s/deployment.yaml'
            } else {
                echo "No Kubernetes configuration found. Skipping validation."
            }
        }
    }

    stage('Deploy to Kubernetes') {
        script {
            if (fileExists('k8s/deployment.yaml')) {
                sh 'kubectl apply -f k8s/deployment.yaml'
            } else {
                echo "No deployment file found. Skipping deployment."
            }
        }
    }
}
