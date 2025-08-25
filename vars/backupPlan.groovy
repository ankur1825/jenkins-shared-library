// vars/backupPlan.groovy
def apply(Map m = [:]) {
  // Infer region from call-site or env
  def region = m.region ?: (env.AWS_REGION ?: env.AWS_DEFAULT_REGION ?: 'us-east-1')
  // m.tags, m.kmsAlias, m.copyToRegion
  def tfvars = [
    // ensure Backup tag is always present
    region        : region,
    tags           : (m.tags ?: [:]) + [ "Backup" : "true" ],
    kms_key_alias  : (m.kmsAlias ?: "alias/tenant-data"),
    copy_to_region : m.copyToRegion  // can be null
  ]

  def tfvarsJson = groovy.json.JsonOutput.toJson(tfvars)
  echo "backupPlan tfvars: ${tfvarsJson}"

  // 👇 pass the right variable
  terraform.plan('orchestration/iac/stacks/aws/backup-plan', tfvarsJson)
  terraform.apply('orchestration/iac/stacks/aws/backup-plan')
}

return this
