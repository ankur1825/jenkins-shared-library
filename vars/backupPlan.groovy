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
  def roleName     = m.iam_role_name ?: "maas-backup-role"  // used by import guard

  // ---- Enforce AWS length limits (50 total). Keep plan_name ≤ 40 so
  //      "${plan_name}-selection" and "${plan_name}-daily" stay valid.
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

  // ---------- Quick fix: import guard for pre-existing vault/role ----------
  def stackDir    = 'orchestration/iac/stacks/aws/backup-plan'
  def backendFile = "${env.WORKSPACE}/.tfbackend/backend.hcl"

  sh """
    set -euo pipefail
    cd '${stackDir}'
    # Ensure state backend is wired before import
    terraform init -input=false -reconfigure -backend-config='${backendFile}' >/dev/null

    VAULT_NAME='${vaultName}'
    ROLE_NAME='${roleName}'

    # Import vault if it exists in AWS but not in state
    if ! terraform state show aws_backup_vault.src >/dev/null 2>&1; then
      if aws backup describe-backup-vault --backup-vault-name "$VAULT_NAME" >/dev/null 2>&1; then
        echo "Importing existing backup vault: $VAULT_NAME"
        terraform import aws_backup_vault.src "$VAULT_NAME"
      fi
    fi

    # Import IAM role if it exists in AWS but not in state
    if ! terraform state show aws_iam_role.backup >/dev/null 2>&1; then
      if aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
        echo "Importing existing IAM role: $ROLE_NAME"
        terraform import aws_iam_role.backup "$ROLE_NAME"
      fi
    fi
  """
  // -------------------------------------------------------------------------

  terraform.plan(stackDir, tfvarsJson)
  terraform.apply(stackDir)
}

return this

