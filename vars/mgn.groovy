// vars/mgn.groovy
def STACK_DIR = 'orchestration/iac/stacks/aws/ec2-liftshift'

private Map buildTfvars(wave, plParams) {
  return [
    wave_id             : wave.name,
    targets             : wave.targets,
    region              : plParams.region,
    vpc_id              : plParams.vpc_id,
    private_subnet_ids  : plParams.private_subnet_ids,
    security_group_ids  : plParams.security_group_ids,
    instance_type_map   : plParams.instance_type_map,
    tg_health_check_path: (plParams.tg_health_check_path ?: '/healthz'),
    blue_green          : (plParams.blue_green ?: true),
    tags                : (plParams.tags ?: [:]),
    attach_backup       : (plParams.attach_backup ?: true),
    kms_key_alias       : (plParams.kms_key_alias ?: 'alias/tenant-data'),
    copy_to_region      : plParams.copy_to_region
  ]
}

def plan(Map m = [:]) {
  def tfvarsJson = groovy.json.JsonOutput.toJson(buildTfvars(m.wave, m.placement.params))
  // full path (same everywhere)
  terraform.plan(STACK_DIR, tfvarsJson)
}

def execute(Map m = [:]) {
  def wave    = m.wave
  def pl      = m.placement
  def p       = pl.params
  def tfvars  = buildTfvars(wave, p)
  def tfvarsJson = groovy.json.JsonOutput.toJson(tfvars)
  // safe single-quote escaping for the heredoc
  def esc = tfvarsJson.replace("'", "'\\''")

  // create tf state backend + tfvars in THIS job’s workspace, then apply
  sh """
    cd '${STACK_DIR}'
    rm -rf .terraform
    terraform --version
    terraform init -input=false -reconfigure \
      -backend-config=\$WORKSPACE/.tfbackend/backend.hcl
    echo '${esc}' > wave.auto.tfvars.json
  """
  terraform.apply(STACK_DIR)
}

def cutover(Map m = [:]) {
  sh "echo 'Cutover ${m.mode} placeholder for placement ${m.placement?.id}'"
}

return this





// def plan(Map m = [:]) {
//   // m.dir (e.g. 'aws/ec2-liftshift'); m.wave; m.placement
//   def wave = m.wave
//   def pl   = m.placement
//   def p    = pl.params

//   // create minimal tfvars for module
//   def tfvars = [
//     wave_id: wave.name,
//     targets: wave.targets,
//     region : p.region,
//     vpc_id : p.vpc_id,
//     private_subnet_ids: p.private_subnet_ids,
//     security_group_ids: p.security_group_ids,
//     instance_type_map: p.instance_type_map,
//     tg_health_check_path: p.tg_health_check_path ?: '/healthz',
//     blue_green: (p.blue_green ?: true),
//     tags: (p.tags ?: [:]),
//     attach_backup: (p.attach_backup ?: true),
//     kms_key_alias: (p.kms_key_alias ?: 'alias/tenant-data'),
//     copy_to_region: p.copy_to_region
//   ]

//   def tfvarsJson = groovy.json.JsonOutput.toJson(tfvars)
//   terraform.plan('orchestration/iac/stacks/aws/ec2-liftshift',
//                  groovy.json.JsonOutput.toJson(tfvars))
// }

// def execute(Map m = [:]) {
//   terraform.apply(m.dir)
// }

// def cutover(Map m = [:]) {
//   // For MGN, you may call AWS CLI or a small python helper to do test/prod cutover.
//   // Placeholder: you can keep this as a no-op until you wire CLI/SDK.
//   sh "echo 'Cutover ${m.mode} placeholder for placement ${m.placement.id}'"
// }

// return this
