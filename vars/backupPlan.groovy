// vars/backupPlan.groovy
def apply(Map m = [:]) {
  // -------- Context (with sensible fallbacks) --------
  def region   = m.region ?: (env.AWS_REGION ?: env.AWS_DEFAULT_REGION ?: 'us-east-1')
  def tenant   = (m.tenant ?: m.tenantId ?: env.TENANT ?: env.TENANT_ID ?: 'tenant')
  def envName  = (m.env ?: m.environment ?: env.ENV ?: env.ENVIRONMENT ?: 'prod')
  def wave     = (m.wave ?: m.waveId ?: m.wave_id ?: env.WAVE ?: env.WAVE_ID ?: 'wave')

  // Safe, deterministic suffix for names
  def norm = { v -> v?.toString()?.trim()?.toLowerCase()?.replaceAll('[^a-z0-9-]', '-') }
  def suffix = [tenant, envName, wave].collect(norm).findAll { it }.join('-')

  // -------- Option B naming (unique per wave/tenant/env) --------
  def planName  = m.plan_name  ?: "maas-backup-plan-${suffix}"
  def vaultName = m.vault_name ?: "maas-backup-vault"   // keep shared to avoid vault sprawl

  // -------- Selection tags --------
  // Always include Backup=true. Optionally add Wave=<id> if caller asks or already tagging resources with Wave.
  Map selTags = ["Backup": "true"]
  if (m.selection_tag_map instanceof Map) {
    selTags.putAll(m.selection_tag_map.collectEntries { k, v -> [(k.toString()): v.toString()] })
  }
  def waveTagKey = (m.wave_tag_key ?: 'Wave').toString()
  if (m.addWaveTag == true || (m.tags instanceof Map && m.tags.containsKey(waveTagKey))) {
    selTags[waveTagKey] = (m.tags?.get(waveTagKey) ?: wave).toString()
  }

  // -------- Cross-Region copy mapping --------
  def enableXCopy = (m.copyToRegion != null && "${m.copyToRegion}".trim())
  def tfvars = [
    region                        : region,
    tags                          : (m.tags ?: [:]) + ["Backup": "true"],
    plan_name                     : planName,
    vault_name                    : vaultName,
    selection_tag_map             : selTags,
    schedule_cron                 : (m.schedule_cron ?: "cron(0 5 ? * * *)"),
    transition_to_cold_after_days : (m.transition_to_cold_after_days ?: 0),
    delete_after_days             : (m.delete_after_days ?: 35),
    enable_cross_region_copy      : enableXCopy as boolean,
  ]

  if (enableXCopy) {
    tfvars.destination_region        = "${m.copyToRegion}"
    tfvars.destination_vault_name    = (m.destination_vault_name ?: "${vaultName}-dr")
    if (m.destination_vault_kms_key_arn) {
      tfvars.destination_vault_kms_key_arn = m.destination_vault_kms_key_arn
    }
  }

  // Optional KMS for source vault (expects ARN; alias lookup should be done in TF if needed)
  if (m.vault_kms_key_arn) {
    tfvars.vault_kms_key_arn = m.vault_kms_key_arn
  }

  // -------- Execute --------
  def tfvarsJson = groovy.json.JsonOutput.toJson(tfvars)
  echo "backupPlan tfvars: ${tfvarsJson}"

  terraform.plan('orchestration/iac/stacks/aws/backup-plan', tfvarsJson)
  terraform.apply('orchestration/iac/stacks/aws/backup-plan')
}

return this
