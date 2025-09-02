@Library('jenkins-shared-library@main') _
pipeline {
  agent any
  options { timestamps(); ansiColor('xterm') }

  parameters {
    string(name: 'IAC_REF',        defaultValue: 'main', description: 'IaC ref (tag/branch)')
    string(name: 'WAVE_ID',        defaultValue: '',     description: 'Wave ID')
    text  (name: 'WAVE_JSON',      defaultValue: '{}',   description: 'Wave JSON (plain or base64)')
    text  (name: 'TENANT_CONTEXT', defaultValue: '{}',   description: 'Tenant context JSON (plain or base64)')
  }

  environment {
    IAC_REPO    = 'https://github.com/ankur1825/Self-Service-CICD-Pipeline-backend-multicloud.git'
    IAC_REF     = "${params.IAC_REF}"
    GITHUB_CRED = 'github-user'   // match maas-plan (fixes “CredentialId ... not found”)
  }

  stages {
    stage('Parse Inputs') {
      steps {
        script {
          def parseJsonParam = { String v ->
            def s = (v ?: '').trim()
            if (!s) return [:]
            if ((s ==~ /^[A-Za-z0-9+\/=\s]+$/) && s.endsWith('=')) {  // allow base64-ish
              try { s = new String(s.decodeBase64(), 'UTF-8') } catch (ignored) {}
            }
            try { readJSON text: s } catch (e) { echo "JSON parse failed (len=${s.size()}): ${e}"; [:] }
          }
          def wave      = parseJsonParam(params.WAVE_JSON)
          def tenantCtx = parseJsonParam(params.TENANT_CONTEXT)

          currentBuild.description = "wave=${params.WAVE_ID} placements=${wave?.placements?.size() ?: 0}"
          echo "wave targets=${wave?.targets?.size() ?: 0}, placements=${wave?.placements?.size() ?: 0}"
          echo "tenantCtx placements=${tenantCtx?.placements?.size() ?: 0}"

          env.__WAVE_JSON      = groovy.json.JsonOutput.toJson(wave)
          env.__TENANT_CONTEXT = groovy.json.JsonOutput.toJson(tenantCtx)
        }
      }
    }

    stage('Checkout IaC') {
      steps {
        script {
          terraform.checkoutModules(env.IAC_REPO, env.IAC_REF, env.GITHUB_CRED, '.')
          env.IAC_DIR = '.'
        }
      }
    }

    stage('Apply per Placement') {
      when { expression { readJSON(text: env.__WAVE_JSON).placements?.size() > 0 } }
      steps {
        script {
          def wave      = readJSON(text: env.__WAVE_JSON)
          def tenantCtx = readJSON(text: env.__TENANT_CONTEXT)

          for (pl in wave.placements) {
            if (pl.provider != 'aws') { echo "Skip ${pl.provider}"; continue }

            def acc = tenantCtx.placements.find { it.id == pl.id }?.account
            if (!acc) { error "No account context for placement ${pl.id}" }

            def region = acc.region ?: pl.params.region
            withAwsTenant(roleArn: acc.role_arn, externalId: acc.external_id, region: region) {
              terraform.withBackend(bucket: acc.state_bucket, table: acc.lock_table,
                                    prefix: "waves/${params.WAVE_ID}/${pl.id}",
                                    region: region) {

                // ⬇️ Pass account so mgn.execute can assume the right role for SSM/MGN agent install
                mgn.execute(
                  wave: wave,
                  placement: pl,
                  account: [role_arn: acc.role_arn, external_id: acc.external_id]
                )
              }
            }
          }
        }
      }
    }
  }
}
