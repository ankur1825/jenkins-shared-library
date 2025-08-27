@Library('jenkins-shared-library@main') _
pipeline {
  agent any
  options { timestamps(); ansiColor('xterm') }

  parameters {
    string(name: 'IAC_REF',       defaultValue: 'main', description: 'IaC ref (tag/branch)')
    string(name: 'WAVE_ID',       defaultValue: '',     description: 'Wave ID')
    text  (name: 'WAVE_JSON',     defaultValue: '',     description: 'Wave document (JSON)')
    text  (name: 'TENANT_CONTEXT',defaultValue: '',     description: 'Tenant + placements (JSON)')
    string(name: 'REQUESTED_BY',  defaultValue: '',     description: 'User requesting teardown')
  }

  environment {
    IAC_REPO   = 'https://github.com/ankur1825/Self-Service-CICD-Pipeline-backend-multicloud.git' // replace with new repo when ready
    IAC_REF    = "${params.IAC_REF}"
    GITHUB_CRED = 'github-token'
  }

  stages {
    stage('Parse Inputs') {
      steps {
        script {
          wave      = readJSON text: (params.WAVE_JSON ?: '{}')
          tenantCtx = readJSON text: (params.TENANT_CONTEXT ?: '{}')
          echo "Teardown requested by: ${params.REQUESTED_BY}"
        }
      }
    }

    stage('Checkout IaC') {
      steps {
        script { terraform.checkoutModules(env.IAC_REPO, env.IAC_REF, env.GITHUB_CRED) }
      }
    }

    stage('Destroy per Placement') {
      steps {
        script {
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

                // 1) Destroy backup plan first (if it was created during plan/execute)
                if (pl.params.attach_backup) {
                  // If your shared lib exposes this:
                  try {
                    backupPlan.destroy(tags: pl.params.tags,
                                       kmsAlias: pl.params.kms_key_alias,
                                       copyToRegion: pl.params.copy_to_region)
                  } catch (Throwable t) {
                    echo "backupPlan.destroy not found in library, falling back to raw terraform"
                    def bvars = [
                      region                        : pl.params.region,
                      tags                          : (pl.params.tags ?: [:]) + [Backup: 'true'],
                      plan_name                     : "maas-backup-plan-${params.WAVE_ID}-${pl.id}",
                      vault_name                    : "maas-backup-vault",
                      selection_tag_map             : [Backup: 'true'],
                      schedule_cron                 : "cron(0 5 ? * * *)",
                      transition_to_cold_after_days : 0,
                      delete_after_days             : 35,
                      enable_cross_region_copy      : (pl.params.copy_to_region ? true : false),
                      destination_region            : (pl.params.copy_to_region ?: null),
                      destination_vault_name        : "maas-backup-vault-dr"
                    ]
                    terraform.destroy('aws/backup-plan', groovy.json.JsonOutput.toJson(bvars))
                  }
                }

                // 2) Destroy the EC2 lift-and-shift stack
                try {
                  // Prefer the shared lib wrapper if available
                  mgn.destroy(dir: 'aws/ec2-liftshift', wave: wave, placement: pl)
                } catch (Throwable t) {
                  echo "mgn.destroy not found in library, falling back to raw terraform"
                  def tfvars = [
                    region               : pl.params.region,
                    vpc_id               : pl.params.vpc_id,
                    private_subnet_ids   : pl.params.private_subnet_ids,
                    security_group_ids   : pl.params.security_group_ids,
                    instance_type_map    : pl.params.instance_type_map,
                    tg_health_check_path : (pl.params.tg_health_check_path ?: '/healthz'),
                    blue_green           : (pl.params.blue_green ?: true),
                    tags                 : (pl.params.tags ?: [:]),
                    attach_backup        : (pl.params.attach_backup ?: true),
                    kms_key_alias        : (pl.params.kms_key_alias ?: 'alias/tenant-data'),
                    copy_to_region       : (pl.params.copy_to_region ?: null)
                  ]
                  terraform.destroy('aws/ec2-liftshift', groovy.json.JsonOutput.toJson(tfvars))
                }
              }
            }
          }
        }
      }
    }
  }
}
