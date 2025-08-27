// vars/backupPlan.groovy
def apply(Map m = [:]) {
  // -------- Context --------
  def region   = m.region ?: (env.AWS_REGION ?: env.AWS_DEFAULT_REGION ?: 'us-east-1')
  def tenant   = (m.tenant ?: m.tenantId ?: env.TENANT ?: env.TENANT_ID ?: 'tenant')
  def envName  = (m.env ?: m.environment ?: env.ENV ?: env.ENVIRONMENT ?: 'prod')
  def wave     = (m.wave ?: m.waveId ?: m.wave_id ?: env.WAVE ?: env.WAVE_ID ?: 'wave')

  def norm   = { v -> v?.toString()?.trim()?.toLowerCase()?.replaceAll('[^a-z0-9-]', '-') }
  def suffix = [tenant, envName, wave].collect(norm).findAll { it }.join('-')

  // Base names (unique plan per wave/tenant/env; shared vault)
  def basePlanName = m.plan_name ?: "maas-backup-plan-${suffix}"
  // ✅ FIX: qualify env var, fall back sensibly
  def vaultName    = m.vault_name ?: (env.VAULT_NAME ?: env.BACKUP_VAULT_NAME ?: "maas-backup-vault")

  // Keep plan_name ≤ 40 so "-selection" and "-daily" stay <50 chars
  final int PLAN_MAX = 40
  def safePlanName = basePlanName.length() > PLAN_MAX ? basePlanName.substring(0, PLAN_MAX) : basePlanName

  // Selection tags
  Map selTags = ["Backup": "true"]
  if (m.selection_tag_map instanceof Map) {
    selTags.putAll(m.selection_tag_map.collectEntries { k, v -> [(k.toString()): v.toString()] })
  }
  def waveTagKey = (m.wave_tag_key ?: 'Wave').toString()
  if (m.addWaveTag == true || (m.tags instanceof Map && m.tags.containsKey(waveTagKey))) {
    selTags[waveTagKey] = (m.tags?.get(waveTagKey) ?: wave).toString()
  }

  // Cross-Region copy
  boolean enableXCopy = (m.copyToRegion != null && "${m.copyToRegion}".trim())

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

    // 🔧 Structural flags to avoid AlreadyExists on shared resources
    create_vault                  : (m.create_vault     != null ? m.create_vault     : false),
    create_iam_role               : (m.create_iam_role  != null ? m.create_iam_role  : false),
    iam_role_name                 : (m.iam_role_name ?: (env.IAM_ROLE_NAME ?: "maas-backup-role")),
  ]

  if (enableXCopy) {
    tfvars.destination_region      = "${m.copyToRegion}"
    tfvars.destination_vault_name  = (m.destination_vault_name ?: "${vaultName}-dr")
    if (m.destination_vault_kms_key_arn) {
      tfvars.destination_vault_kms_key_arn = m.destination_vault_kms_key_arn
    }
  }

  if (m.vault_kms_key_arn ?: env.VAULT_KMS_KEY_ARN) {
    tfvars.vault_kms_key_arn = (m.vault_kms_key_arn ?: env.VAULT_KMS_KEY_ARN)
  }

  def tfvarsJson = groovy.json.JsonOutput.toJson(tfvars)
  echo "backupPlan tfvars: ${tfvarsJson}"

  terraform.plan('orchestration/iac/stacks/aws/backup-plan', tfvarsJson)
  terraform.apply('orchestration/iac/stacks/aws/backup-plan')
}

return this
