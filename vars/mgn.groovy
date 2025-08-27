// vars/mgn.groovy
import groovy.transform.Field
import groovy.json.JsonOutput

@Field final String STACK_DIR = 'orchestration/iac/stacks/aws/ec2-liftshift'

// Build the exact tfvars shape your stack expects
private Map makeTfvars(wave, pl) {
  def p = pl.params
  return [
    wave_id             : wave.name,
    targets             : wave.targets,
    region              : p.region,
    vpc_id              : p.vpc_id,
    private_subnet_ids  : p.private_subnet_ids,
    security_group_ids  : p.security_group_ids,
    instance_type_map   : p.instance_type_map,
    tg_health_check_path: (p.tg_health_check_path ?: '/healthz'),
    blue_green          : (p.blue_green ?: true),
    tags                : (p.tags ?: [:]),
    attach_backup       : (p.attach_backup ?: true),
    kms_key_alias       : (p.kms_key_alias ?: 'alias/tenant-data'),
    copy_to_region      : p.copy_to_region
  ]
}

def plan(Map m = [:]) {
  def stack = (m.dir ?: STACK_DIR)
  def tfvars = makeTfvars(m.wave, m.placement)
  terraform.plan(stack, JsonOutput.toJson(tfvars))
}

def execute(Map m = [:]) {
  def stack = (m.dir ?: STACK_DIR)
  def tfvars = makeTfvars(m.wave, m.placement)

  // Ensure a fresh workspace has the tfvars file before apply
  writeFile file: "${stack}/wave.auto.tfvars.json", text: JsonOutput.toJson(tfvars)

  // `terraform.apply()` will init using the workspace backend file
  terraform.apply(stack)
}

def destroy(Map m = [:]) {
  def stack = (m.dir ?: STACK_DIR)
  def tfvars = makeTfvars(m.wave, m.placement)

  // Drive destroy through the shared terraform helper
  // (it writes tfvars and re-inits with ${WORKSPACE}/.tfbackend/backend.hcl)
  terraform.destroy(stack, JsonOutput.toJson(tfvars))
}

def cutover(Map m = [:]) {
  sh "echo 'Cutover ${m.mode} placeholder for placement ${m.placement?.id}'"
}

return this
