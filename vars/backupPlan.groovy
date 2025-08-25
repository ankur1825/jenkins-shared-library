// vars/backupPlan.groovy
def apply(Map m = [:]) {
  // -------- Context --------
  def region   = m.region ?: (env.AWS_REGION ?: env.AWS_DEFAULT_REGION ?: 'us-east-1')
  def tenant   = (m.tenant ?: m.tenantId ?: env.TENANT ?: env.TENANT_ID ?: 'tenant')
  def envName  = (m.env ?: m.environment ?: env.ENV ?: env.ENVIRONMENT ?: 'prod')
  def wave     = (m.wave ?: m.waveId ?: m.wave_id ?: env.WAVE ?: env.WAVE_ID ?: 'wave')

  def norm = { v -> v?.toString()?.trim()?.toLowerCase()?.replaceAll('[^a-z0-9-]', '-') }
  def suffix = [tenant, envName, wave].collect(norm).findAll { it }.join('-')

  // Base names (Option B: unique plan per wave/tenant/env; shared vault)
  def basePlanName = m.plan_name ?: "maas-backup-plan-${suffix}"
  def vaultName    = m.vault_name ?: "maas-backup-vault"

  // ---- Enforce AWS length limits (50 total). Keep plan_name ≤ 40 so
  //      "${plan_name}-selection" (10 chars) and "${plan_name}-daily" (6) stay valid.
  final int PLAN_MAX = 40
  def safePlanName = basePlanName.length() > PLAN_MAX ? basePlanName.substring(0, PLAN_MAX) : basePlanName

  // Selection tags (always include Backup=true; optionally Wave)
  Map selTags = ["Backup": "true"]
  if (m.selection_tag_map instanceof Map) {
    selTags.putAll(m.selection_tag_map.collectEntries { k, v -> [(k.toString()): v.toString()] })
  }
  def waveTagKey = (m.wave_tag_key ?: 'Wave').toString()
  if (m.addWaveTag == true || (m.tags instanceof Map && m.tags.containsKey(waveTagKey))) {
    selTags[waveTagKey] = (m.tags?.get(waveTagKey) ?: wave).toString()
  }

  // Cross-Region copy mapping
  def enableXCopy = (m.copyToRegion != null && "${m.copyToRegion}".trim())
  def tfvars = [
    region                        : region,
    tags                          : (m.tags ?: [:]) + ["Backup": "true"],
    plan_name                     : safePlanName,
    vault_name                    : vaultName,
    selection_tag_map             : selTags,
    schedule_cron                 : (m.schedule_cron ?: "cron(0 5 ? * * *)"),
    transition_to_cold_after_days : (m.transition_to_cold_after_days ?: 0),
    delete_after_days             : (m.delete_after_days ?: 35),
    enable_cross_region_copy      : enableXCopy as boolean,
  ]

  if (enableXCopy) {
    tfvars.destination_region     = "${m.copyToRegion}"
    tfvars.destination_vault_name = (m.destination_vault_name ?: "${vaultName}-dr")
    if (m.destination_vault_kms_key_arn) {
      tfvars.destination_vault_kms_key_arn = m.destination_vault_kms_key_arn
    }
  }

  if (m.vault_kms_key_arn) {
    tfvars.vault_kms_key_arn = m.vault_kms_key_arn
  }

  def tfvarsJson = groovy.json.JsonOutput.toJson(tfvars)
  echo "backupPlan tfvars: ${tfvarsJson}"

  terraform.plan('orchestration/iac/stacks/aws/backup-plan', tfvarsJson)
  terraform.apply('orchestration/iac/stacks/aws/backup-plan')
}

return this
