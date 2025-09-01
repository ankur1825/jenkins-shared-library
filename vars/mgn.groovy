// vars/mgn.groovy
// Terraform orchestration + MGN agent install helpers for lift-and-shift waves.

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

@Field final String STACK_DIR = 'orchestration/iac/stacks/aws/ec2-liftshift'

// ------------------------------
// Terraform helpers (unchanged)
// ------------------------------
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
  writeFile file: "${stack}/wave.auto.tfvars.json", text: JsonOutput.toJson(tfvars)
  terraform.apply(stack)
}

def destroy(Map m = [:]) {
  def stack = (m.dir ?: STACK_DIR)
  def tfvars = makeTfvars(m.wave, m.placement)
  terraform.destroy(stack, JsonOutput.toJson(tfvars))
}

def cutover(Map m = [:]) {
  sh "echo 'Cutover ${m.mode ?: 'test'} placeholder for placement ${m.placement?.id}'"
}

// -----------------------------------------------------
// MGN agent install (inter-region EC2 -> EC2 via SSM)
// -----------------------------------------------------

/** Quiet installer commands for Linux targeting the DEST region */
private List<String> linuxInstallCommands(String destRegion) {
  return [
    'set -euo pipefail',
    "curl -fsSL -o /tmp/aws-repl-init.py https://aws-application-migration-service-${destRegion}.s3.${destRegion}.amazonaws.com/latest/linux/aws-replication-installer-init.py",
    "sudo python3 /tmp/aws-repl-init.py --region ${destRegion} --no-prompt"
  ]
}

/** Quiet installer for Windows targeting the DEST region */
private String windowsInstallCommands(String destRegion) {
  return """
  \$ErrorActionPreference='Stop'
  \$u  = 'https://aws-application-migration-service-${destRegion}.s3.${destRegion}.amazonaws.com/latest/windows/AWSReplicationWindowsInstaller.exe'
  \$dst = "\$env:TEMP\\AWSReplicationWindowsInstaller.exe"
  Invoke-WebRequest -Uri \$u -OutFile \$dst
  & \$dst /quiet /norestart /log "\$env:TEMP\\mgn-install.log" /region ${destRegion}
  """.stripIndent().trim()
}

/** Resolve a mix of instanceIds and Name tags into instanceIds */
private List<String> resolveInstanceIds(String srcRegion, List<String> idsOrNames) {
  def ids   = idsOrNames.findAll { it?.trim()?.startsWith('i-') }.collect { it.trim() }
  def names = idsOrNames.findAll { !(it?.trim()?.startsWith('i-')) }.collect { it.trim() }

  if (names) {
    def namesCsv = names.collect { it.replaceAll(',', '\\\\,') }.join(',')
    def json = sh(returnStdout: true, script: """
      aws ec2 describe-instances \
        --region ${srcRegion} \
        --filters Name=tag:Name,Values=${namesCsv} \
        --query 'Reservations[].Instances[].InstanceId' \
        --output json
    """).trim()
    def more = (new JsonSlurper().parseText(json) ?: []) as List
    ids.addAll(more)
  }
  return ids.unique()
}

/** Create MGN service-linked role if missing and check SSM connectivity. */
def ensureMgnPrereqs(Map args) {
  def accountRef = args.accountRef
  def region     = args.region
  def instanceIds = (args.instanceIds ?: []) as List<String>

  withAwsTenant(accountRef: accountRef, region: region) {
    // Service-linked role
    def roleOk = sh(returnStatus: true, script: """
      aws iam get-role --role-name AWSServiceRoleForApplicationMigrationService >/dev/null 2>&1
    """) == 0
    if (!roleOk) {
      echo "Creating MGN service-linked role..."
      sh """
        aws iam create-service-linked-role --aws-service-name mgn.amazonaws.com
      """
    } else {
      echo "MGN service-linked role exists."
    }

    // Basic SSM reachability (are these instances managed?)
    if (instanceIds) {
      writeFile file: 'ids.json', text: JsonOutput.toJson(instanceIds)
      def info = sh(returnStdout: true, script: """
        aws ssm describe-instance-information --region ${region} \
          --instance-information-filter-list '[{"key":"InstanceIds","valueSet":'$(cat ids.json)'}]' \
          --query 'InstanceInformationList[].InstanceId' --output json
      """).trim()
      def managed = (new JsonSlurper().parseText(info) ?: []) as List
      def unmanaged = (instanceIds as Set) - (managed as Set)
      if (unmanaged) {
        echo "WARNING: These instances are not managed by SSM (no agent/role or no connectivity): ${unmanaged}"
      } else {
        echo "All instances appear in SSM inventory."
      }
    }
  }
}

/** Send an SSM Run Command with simple waiter */
private String sendSsm(String region, List<String> instanceIds, String docName, Map params, boolean wait=true) {
  writeFile file: 'ssm-params.json', text: JsonOutput.prettyPrint(JsonOutput.toJson(params))
  def out = sh(returnStdout: true, script: """
    aws ssm send-command \
      --region ${region} \
      --document-name ${docName} \
      --instance-ids ${instanceIds.join(' ')} \
      --parameters file://ssm-params.json \
      --comment "Install MGN agent" \
      --output json
  """).trim()
  def cmdId = (new JsonSlurper().parseText(out)).Command.CommandId
  echo "SSM commandId=${cmdId}"

  if (!wait) return cmdId

  timeout(time: 15, unit: 'MINUTES') {
    waitUntil {
      def inv = sh(returnStdout: true, script: """
        aws ssm list-command-invocations --region ${region} \
          --command-id ${cmdId} --details --output json
      """).trim()
      def statuses = (new JsonSlurper().parseText(inv)).CommandInvocations*.Status
      echo "SSM statuses=${statuses}"
      return statuses && statuses.every { it in ['Success','Cancelled','Failed','TimedOut'] }
    }
  }
  return cmdId
}

/**
 * Install the MGN agent on a set of *source* EC2 instances via SSM.
 * args:
 *   srcAccountRef  - tenant accountRef for the *source* account (can be same as target)
 *   srcRegion      - region where the source EC2s live
 *   destRegion     - region you are migrating *to*
 *   idsOrNames     - ['i-0abc…', 'app01', ...] (mix allowed)
 */
def installAgentOnEc2(Map args) {
  def srcAccountRef = args.srcAccountRef
  def srcRegion     = args.srcRegion
  def destRegion    = args.destRegion
  def idsOrNames    = (args.idsOrNames ?: []) as List

  if (!srcAccountRef || !srcRegion || !destRegion || !idsOrNames) {
    error "installAgentOnEc2 requires srcAccountRef, srcRegion, destRegion, idsOrNames"
  }

  withAwsTenant(accountRef: srcAccountRef, region: srcRegion) {
    def instanceIds = resolveInstanceIds(srcRegion, idsOrNames)
    if (!instanceIds) {
      error "No matching instances found for: ${idsOrNames}"
    }
    echo "Will install MGN agent on: ${instanceIds}"

    // Ensure service-linked role and sanity-check SSM
    ensureMgnPrereqs(accountRef: srcAccountRef, region: srcRegion, instanceIds: instanceIds)

    // Classify OS using SSM inventory (unknowns default to Linux)
    writeFile file: 'ids.json', text: JsonOutput.toJson(instanceIds)
    def invJson = sh(returnStdout: true, script: """
      aws ssm describe-instance-information --region ${srcRegion} \
        --instance-information-filter-list '[{"key":"InstanceIds","valueSet":'$(cat ids.json)'}]' \
        --query 'InstanceInformationList[].{Id:InstanceId,Platform:PlatformType}' --output json
    """).trim()
    def info = (new JsonSlurper().parseText(invJson) ?: []) as List
    def linuxIds   = info.findAll { (it.Platform ?: '').toString().toLowerCase().contains('linux') }.collect { it.Id }
    def windowsIds = info.findAll { (it.Platform ?: '').toString().toLowerCase().contains('windows') }.collect { it.Id }
    def known = (linuxIds + windowsIds) as Set
    def unknown = instanceIds.findAll { !(it in known) }
    linuxIds.addAll(unknown)

    // Chunk to avoid SSM 50-target limits
    def chunk = { List l, int n -> l.collate(n) }
    if (linuxIds) {
      def cmds = linuxInstallCommands(destRegion)
      chunk(linuxIds, 25).each { ids -> sendSsm(srcRegion, ids, 'AWS-RunShellScript', [commands: cmds], true) }
    }
    if (windowsIds) {
      def ps = windowsInstallCommands(destRegion)
      chunk(windowsIds, 25).each { ids -> sendSsm(srcRegion, ids, 'AWS-RunPowerShellScript', [commands: [ps]], true) }
    }
  }
}

/**
 * Convenience wrapper: read wave/placement shape and install the agent
 * when placement.params.source = [type:'aws-ec2', account_ref:'...', region:'...', server_ids:['app01','i-...']]
 */
def installAgentFromWave(Map m = [:]) {
  def wave = m.wave
  def pl   = m.placement
  if (!wave || !pl) error "installAgentFromWave needs wave and placement"

  def destRegion = pl.params.region
  def src = pl.params.source
  if (!src || src.type != 'aws-ec2') {
    echo "No EC2 source defined for placement ${pl.id}; skipping agent install."
    return
  }
  installAgentOnEc2([
    srcAccountRef: (src.account_ref ?: pl.params.account_ref),
    srcRegion    : src.region,
    destRegion   : destRegion,
    idsOrNames   : (src.server_ids ?: [])
  ])
}

return this
