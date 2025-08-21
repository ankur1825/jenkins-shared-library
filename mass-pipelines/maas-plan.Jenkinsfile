@Library('jenkins-shared-library@main') _
pipeline {
  agent any
  options { timestamps(); ansiColor('xterm') }
  parameters {
    string(name: 'IAC_REF', defaultValue: 'main', description: 'IaC ref (tag/branch)')
    string(name: 'WAVE_ID', defaultValue: '', description: 'Wave ID')
    text(name: 'WAVE_JSON', defaultValue: '', description: 'Wave document (JSON)')
    text(name: 'TENANT_CONTEXT', defaultValue: '', description: 'Tenant + placements (JSON)')
  }
  environment {
    IAC_REPO = 'https://github.com/your-org/cloud-migration-iac.git' //new backend repo has to be replaced 
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
    stage('Checkout IaC') {
      steps {
        script { terraform.checkoutModules(env.IAC_REPO, env.IAC_REF, env.GITHUB_CRED) }
      }
    }
    stage('Plan per Placement') {
      steps {
        script {
          for (pl in wave.placements) {
            if (pl.provider != 'aws') { echo "Skip ${pl.provider} (not yet supported)"; continue }
            def acc = tenantCtx.placements.find { it.id == pl.id }?.account
            if (!acc) error "No account context for placement ${pl.id}"

            echo "Planning placement ${pl.id} in ${pl.params.region} (account_ref=${pl.params.account_ref})"

            withAwsTenant(roleArn: acc.role_arn, externalId: acc.external_id, region: acc.region ?: pl.params.region) {
              terraform.withBackend(bucket: acc.state_bucket, table: acc.lock_table,
                                    prefix: "waves/${params.WAVE_ID}/${pl.id}",
                                    region: acc.region ?: pl.params.region) {
                mgn.plan(dir: 'aws/ec2-liftshift', wave: wave, placement: pl)
                if (pl.params.attach_backup) {
                  backupPlan.apply(tags: pl.params.tags, kmsAlias: pl.params.kms_key_alias, copyToRegion: pl.params.copy_to_region)
                }
              }
            }
          }
        }
      }
    }
  }
  post {
    always { archiveArtifacts artifacts: 'iac/**/plan.tfplan', allowEmptyArchive: true }
  }
}
