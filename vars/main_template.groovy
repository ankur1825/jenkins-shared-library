pipeline {
    agent any
    
    parameters {
        string(name: 'APP_TYPE', defaultValue: '', description: 'Select Application Type (Java, Spring Boot, Python, Docker, NPM, .NET)')
        string(name: 'REPO_URL', defaultValue: '', description: 'Git Repository URL')
        string(name: 'BRANCH', defaultValue: '', description: 'Git Branch')
        booleanParam(name: 'ENABLE_SONARQUBE', defaultValue: false, description: 'Enable SonarQube Analysis')
        booleanParam(name: 'ENABLE_OPA', defaultValue: false, description: 'Enable OPA Security Scan')
        string(name: 'IMAGE_NAME', defaultValue: 'my-app', description: 'Docker Image Name')
    }

    stages {
        stage('Select Application') {
            steps {
                script {
                    def appType = params.APP_TYPE
                    def repoUrl = params.REPO_URL
                    
                    if (!repoUrl) {
                        error "Repository URL is missing. Please provide a valid URL."
                    }
                    
                    stage('Clone Repository') {
                        git url: repoUrl
                    }

                    stage('Validate Repository') {
                        script {
                            def detectedType = ''

                            if (fileExists('pom.xml')) {
                                detectedType = 'Java'
                            } else if (fileExists('requirements.txt')) {
                                detectedType = 'Python'
                            } else if (fileExists('package.json')) {
                                detectedType = 'NPM'
                            } else {
                                error "Unsupported or unknown repository type."
                            }

                            if (appType != detectedType) {
                                error "Mismatch: Selected app type is ${appType}, but repository seems to be ${detectedType}."
                            }
                        }
                    }

                    if (appType == 'Java') {
                        load 'jenkins_template/javaPipeline.groovy'
                    } else if (appType == 'Python') {
                        load 'Cost-optimization/jenkins_template/pythonPipeline.groovy'
                    } else if (appType == 'Spring Boot') {
                        load 'Cost-optimization/jenkins_template/springBootPipeline.groovy'
                    } else if (appType == 'Docker') {
                        load 'Cost-optimization/jenkins_template/dockerPipeline.groovy'
                    } else if (appType == 'NPM') {
                        load 'Cost-optimization/jenkins_template/npmPipeline.groovy'
                    } else if (appType == '.NET') {
                        load 'Cost-optimization/jenkins_template/dotnetPipeline.groovy'
                    } else {
                        error 'Unsupported application type'
                    }
                }
            }
        }
    }
}
