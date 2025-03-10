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
    
    stage('Install Dependencies') {
        script {
            if (fileExists('requirements.txt')) {
                sh 'pip install -r requirements.txt'
            } else {
                echo "Skipping as no valid requirement file was found."
            }    
        }
    }

    stage('Unit Test') {
        script {
            if (fileExists('pytest')) {
                sh 'pytest'
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
            def imageName = params.IMAGE_NAME ?: 'my-python-app'
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