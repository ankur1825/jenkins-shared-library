@Library('jenkins-shared-library@main') _
pipeline {
  agent any
  options { timestamps(); ansiColor('xterm') }
  parameters {
    string(name: 'IAC_REF', defaultValue: 'main', description: 'IaC ref (tag/branch)')
    string(name: 'WAVE_ID', defaultValue: '', description: 'Wave ID')
    choice(name: 'MODE', choices: ['test','prod'], description: 'Cutover mode')
    text(name: 'WAVE_JSON', defaultValue: '', description: 'Wave document (JSON)')
    text(name: 'TENANT_CONTEXT', defaultValue: '', description: 'Tenant + placements (JSON)')
  }
  environment {
    IAC_REPO = 'https://github.com/ankur1825/Self-Service-CICD-Pipeline-backend-multicloud.git' //new backend repo has to be replaced 
    IAC_REF  = "${params.IAC_REF}"                                  //'v0.1.0'
    GITHUB_CRED = 'github-token'
  }
  stages {
    stage('Parse Inputs') {
      steps {
        script {
          wave = readJSON text: params.WAVE_JSON
          tenantCtx = readJSON text: params.TENANT_CONTEXT
        }
      }
    }
    stage('Cutover per Placement') {
      steps {
        script {
          for (pl in wave.placements) {
            if (pl.provider != 'aws') { echo "Skip ${pl.provider}"; continue }
            def acc = tenantCtx.placements.find { it.id == pl.id }?.account
            if (!acc) error "No account context for placement ${pl.id}"

            withAwsTenant(roleArn: acc.role_arn, externalId: acc.external_id, region: acc.region ?: pl.params.region) {
              // When you wire up MGN/API cutover, call it here
              mgn.cutover(mode: params.MODE, wave: wave, placement: pl)
            }
          }
        }
      }
    }
  }
}
