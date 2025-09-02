@Library('jenkins-shared-library@main') _
pipeline {
  agent any
  options { timestamps(); ansiColor('xterm') }

  parameters {
    string(name: 'IAC_REF',        defaultValue: 'main', description: 'IaC ref (tag/branch)')
    string(name: 'WAVE_ID',        defaultValue: '',     description: 'Wave ID')
    text  (name: 'WAVE_JSON',      defaultValue: '{}',   description: 'Wave JSON (plain or base64)')
    text  (name: 'TENANT_CONTEXT', defaultValue: '{}',   description: 'Tenant context JSON (plain or base64)')
    string(name: 'REQUESTED_BY',   defaultValue: '',     description: 'User requesting teardown')
  }

  environment {
    IAC_REPO    = 'https://github.com/ankur1825/Self-Service-CICD-Pipeline-backend-multicloud.git'
    IAC_REF     = "${params.IAC_REF}"
    GITHUB_CRED = 'github-user'
  }

  stages {
    stage('Parse Inputs') {
      steps {
        script {
          def parseJsonParam = { String v ->
            def s = (v ?: '').trim()
            if (!s) return [:]
            if ((s ==~ /^[A-Za-z0-9+\/=\s]+$/) && s.endsWith('=')) { try { s = new String(s.decodeBase64(),'UTF-8') } catch(e){} }
            try { readJSON text: s } catch (e) { echo "JSON parse failed: ${e}"; [:] }
          }
          def wave      = parseJsonParam(params.WAVE_JSON)
          def tenantCtx = parseJsonParam(params.TENANT_CONTEXT)
          currentBuild.description = "DESTROY wave=${params.WAVE_ID} placements=${wave?.placements?.size() ?: 0}"
          env.__WAVE_JSON      = groovy.json.JsonOutput.toJson(wave)
          env.__TENANT_CONTEXT = groovy.json.JsonOutput.toJson(tenantCtx)
          echo "Teardown requested by: ${params.REQUESTED_BY}"
        }
      }
    }

    stage('Checkout IaC') {
      steps {
        script {
          terraform.checkoutModules(env.IAC_REPO, env.IAC_REF, env.GITHUB_CRED, '.')  // into workspace root
          env.IAC_DIR = '.'  // so mgn.groovy resolves its default stack path correctly
        }
      }
    }

    stage('Destroy per Placement') {
      when { expression { readJSON(text: env.__WAVE_JSON).placements?.size() > 0 } }
      steps {
        script {
          def wave      = readJSON(text: env.__WAVE_JSON)
          def tenantCtx = readJSON(text: env.__TENANT_CONTEXT)

          for (pl in wave.placements) {
            if (pl.provider != 'aws') { echo "Skip ${pl.provider} (not supported yet)"; continue }

            def acc = tenantCtx.placements.find { it.id == pl.id }?.account
            if (!acc) { error "No account context for placement ${pl.id}" }

            def region = acc.region ?: pl.params.region
            echo "Destroying placement ${pl.id} in ${region} (account_ref=${pl.params.account_ref})"

            withAwsTenant(roleArn: acc.role_arn, externalId: acc.external_id, region: region) {
              terraform.withBackend(bucket: acc.state_bucket, table: acc.lock_table,
                                    prefix: "waves/${params.WAVE_ID}/${pl.id}",
                                    region: region) {

                // If your shared-lib backup wrapper exists, you can optionally tear it down here first.

                // Destroy the EC2 liftshift stack (let mgn.groovy pick the correct default path)
                mgn.destroy(wave: wave, placement: pl)
              }
            }
          }
        }
      }
    }
  }
}
