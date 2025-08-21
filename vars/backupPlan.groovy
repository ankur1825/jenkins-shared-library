def apply(Map m = [:]) {
  // m.tags, m.kmsAlias, m.copyToRegion
  def tfvars = [
    tags: (m.tags ?: [:]) + [ "Backup" : "true" ],
    kms_key_alias: (m.kmsAlias ?: "alias/tenant-data"),
    copy_to_region: m.copyToRegion
  ]
  def tfvarsJson = groovy.json.JsonOutput.toJson(tfvars)
  terraform.plan('orchestration/iac/stacks/aws/backup-plan', js)
  terraform.apply('orchestration/iac/stacks/aws/backup-plan')
}
return this
