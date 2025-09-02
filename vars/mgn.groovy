// vars/mgn.groovy
// Terraform orchestration + MGN agent install helpers for lift-and-shift waves.

import groovy.transform.Field
import groovy.json.JsonOutput

@Field final String STACK_DIR = 'orchestration/iac/stacks/aws/ec2-liftshift'

// ------------------------------
// Path + tfvars helpers
// ------------------------------

/** Resolve the stack directory against env.IAC_DIR (if set) and sanity-check it. */
private String resolveStackDir(String overrideDir) {
  def base  = (env.IAC_DIR ?: '.').trim()
  def rel   = (overrideDir ?: STACK_DIR).trim()
  def stack = "${base}/${rel}".replaceAll('/+', '/')

  echo "Using IaC stack dir: ${stack}"
  sh "ls -la ${stack} || true"

  def hasTf = (sh(returnStatus: true, script: "ls ${stack}/*.tf >/dev/null 2>&1") == 0)
  if (!hasTf) {
    error "No Terraform files found under '${stack}'. Check your repo path. (IAC_DIR='${base}', rel='${rel}')"
  }
  return stack
}

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

// ------------------------------
// Import-if-exists helper (ALB/TG/LT)
// ------------------------------

/** Import existing infra (by well-known names) into TF state so re-runs are idempotent. */
private void importExistingInfra(String stack, String region, String backendCfgPath) {
  dir(stack) {
    sh "terraform init -input=false -reconfigure -backend-config=${backendCfgPath}"

    def inState = { String addr ->
      sh(returnStatus: true, script: "terraform state show ${addr} >/dev/null 2>&1") == 0
    }

    // ALB
    def albArn = sh(returnStdout: true, script: """
      aws elbv2 describe-load-balancers \
        --region ${region} --names maas-alb \
        --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null || true
    """).trim()
    if (albArn && albArn != 'None' && !inState('aws_lb.app')) {
      echo "Importing existing ALB into state: ${albArn}"
      sh "terraform import -input=false aws_lb.app ${albArn} || true"
    }

    // TG
    def tgArn = sh(returnStdout: true, script: """
      aws elbv2 describe-target-groups \
        --region ${region} --names maas-tg-prod \
        --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || true
    """).trim()
    if (tgArn && tgArn != 'None' && !inState('aws_lb_target_group.prod')) {
      echo "Importing existing Target Group into state: ${tgArn}"
      sh "terraform import -input=false aws_lb_target_group.prod ${tgArn} || true"
    }

    // Launch Template
    def ltId = sh(returnStdout: true, script: """
      aws ec2 describe-launch-templates \
        --region ${region} --filters Name=launch-template-name,Values=maas-lt-* \
        --query 'LaunchTemplates[0].LaunchTemplateId' --output text 2>/dev/null || true
    """).trim()
    if (ltId && ltId != 'None' && !inState('aws_launch_template.lt')) {
      echo "Importing existing Launch Template into state: ${ltId}"
      sh "terraform import -input=false aws_launch_template.lt ${ltId} || true"
    }
  }
}

// ------------------------------
// Terraform entrypoints
// ------------------------------

def plan(Map m = [:]) {
  def stack  = resolveStackDir(m.dir)
  def tfvars = makeTfvars(m.wave, m.placement)
  terraform.plan(stack, JsonOutput.toJson(tfvars))
}

def execute(Map m = [:]) {
  def stack   = resolveStackDir(m.dir)
  def tfvars  = makeTfvars(m.wave, m.placement)
  def account = m.account  // optional {role_arn, external_id}

  // Install MGN agent first if this placement defines an EC2 source
  if (m?.placement?.params?.source?.type == 'aws-ec2') {
    echo "Installing MGN agent for placement ${m.placement.id}..."
    installAgentFromWave(wave: m.wave, placement: m.placement, account: account)
  }

  // Ensure TF has the same tfvars that 'plan' would write
  writeFile file: "${stack}/wave.auto.tfvars.json", text: JsonOutput.toJson(tfvars)

  // Import existing resources and then apply
  def backendCfg = "${pwd()}/.tfbackend/backend.hcl"
  importExistingInfra(stack, (tfvars.region ?: ''), backendCfg)
  terraform.plan(stack, JsonOutput.toJson(tfvars))
  terraform.apply(stack)
}

def destroy(Map m = [:]) {
  def stack  = resolveStackDir(m.dir)
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
  return (
    '''$ErrorActionPreference='Stop'
$u  = 'https://aws-application-migration-service-''' + destRegion + '''.s3.''' + destRegion + '''.amazonaws.com/latest/windows/AWSReplicationWindowsInstaller.exe'
$dst = "$env:TEMP\\AWSReplicationWindowsInstaller.exe"
Invoke-WebRequest -Uri $u -OutFile $dst
& $dst /quiet /norestart /log "$env:TEMP\\mgn-install.log" /region ''' + destRegion + '''
'''
  ).stripIndent().trim()
}

/** Resolve a mix of instanceIds and Name tags into instanceIds */
private List<String> resolveInstanceIds(String srcRegion, List<String> idsOrNames) {
  def ids   = idsOrNames.findAll { it?.trim()?.startsWith('i-') }.collect { it.trim() }
  def names = idsOrNames.findAll { !(it?.trim()?.startsWith('i-')) }.collect { it.trim() }

  if (names) {
    def namesCsv = names.collect { it.replaceAll(',', '\\\\,') }.join(',')
    def json = sh(returnStdout: true, script:
      '''aws ec2 describe-instances \
        --region ''' + srcRegion + ''' \
        --filters Name=tag:Name,Values=''' + namesCsv + ''' \
        --query 'Reservations[].Instances[].InstanceId' \
        --output json
      '''
    ).trim()
    def more = (new groovy.json.JsonSlurperClassic().parseText(json) ?: []) as List
    ids.addAll(more)
  }
  return ids.unique()
}

/** Helper: are source and destination accounts the same? */
private boolean sameAccountRef(Map pl) {
  def src  = pl?.params?.source
  if (!src || src.type != 'aws-ec2') return false
  def dest = pl.params.account_ref
  def sref = (src.account_ref ?: dest)
  return (sref?.toString() == dest?.toString())
}

/** Create MGN service-linked role (if missing) & check SSM connectivity.
 *  Runs under CURRENT credentials (no assume-role here). */
def ensureMgnPrereqs(Map args) {
  def region      = args.region
  def instanceIds = (args.instanceIds ?: []) as List<String>

  def roleOk = sh(returnStatus: true, script:
    '''aws iam get-role --role-name AWSServiceRoleForApplicationMigrationService >/dev/null 2>&1'''
  ) == 0
  if (!roleOk) {
    echo "Creating MGN service-linked role..."
    sh('''aws iam create-service-linked-role --aws-service-name mgn.amazonaws.com''')
  } else {
    echo "MGN service-linked role exists."
  }

  if (instanceIds) {
    writeFile file: 'ids.json', text: JsonOutput.toJson(instanceIds)
    def info = sh(returnStdout: true, script:
      '''aws ssm describe-instance-information --region ''' + region + ''' \
        --instance-information-filter-list '[{"key":"InstanceIds","valueSet":'$(cat ids.json)'}]' \
        --query 'InstanceInformationList[].InstanceId' --output json
      '''
    ).trim()
    def managed = (new groovy.json.JsonSlurperClassic().parseText(info) ?: []) as List
    def unmanaged = (instanceIds as Set) - (managed as Set)
    if (unmanaged) {
      echo "WARNING: These instances are not managed by SSM (no agent/role or no connectivity): ${unmanaged}"
    } else {
      echo "All instances appear in SSM inventory."
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
  def cmdId = (new groovy.json.JsonSlurperClassic().parseText(out)).Command.CommandId
  echo "SSM commandId=${cmdId}"

  if (!wait) return cmdId

  timeout(time: 15, unit: 'MINUTES') {
    waitUntil {
      def inv = sh(returnStdout: true, script: """
        aws ssm list-command-invocations --region ${region} \
          --command-id ${cmdId} --details --output json
      """).trim()
      def statuses = (new groovy.json.JsonSlurperClassic().parseText(inv)).CommandInvocations*.Status
      echo "SSM statuses=${statuses}"
      return statuses && statuses.every { it in ['Success','Cancelled','Failed','TimedOut'] }
    }
  }
  return cmdId
}

/** Install the MGN agent on a set of *source* EC2 instances via SSM.
 *  Provide EITHER (srcRoleArn[/srcExternalId]) OR (srcAccountRef),
 *  OR set skipAssume=true to reuse current creds. */
def installAgentOnEc2(Map args) {
  def srcRoleArn    = args.srcRoleArn
  def srcExternalId = args.srcExternalId
  def srcAccountRef = args.srcAccountRef
  def srcRegion     = args.srcRegion
  def destRegion    = args.destRegion
  def idsOrNames    = (args.idsOrNames ?: []) as List
  def skipAssume    = (args.skipAssume ?: false) as boolean

  if (!srcRegion || !destRegion || !idsOrNames) {
    error "installAgentOnEc2 requires srcRegion, destRegion, idsOrNames"
  }
  if (!skipAssume && !srcRoleArn && !srcAccountRef) {
    error "installAgentOnEc2 requires either srcRoleArn[/srcExternalId] or srcAccountRef (or skipAssume=true)"
  }

  def run = {
    def instanceIds = resolveInstanceIds(srcRegion, idsOrNames)
    if (!instanceIds) {
      error "No matching instances found for: ${idsOrNames}"
    }
    echo "Will install MGN agent on: ${instanceIds}"

    // Ensure SLR + SSM visibility (under CURRENT creds)
    ensureMgnPrereqs(region: srcRegion, instanceIds: instanceIds)

    // Classify OS via SSM inventory (unknowns -> Linux)
    writeFile file: 'ids.json', text: JsonOutput.toJson(instanceIds)
    def invJson = sh(returnStdout: true, script:
      '''aws ssm describe-instance-information --region ''' + srcRegion + ''' \
        --instance-information-filter-list '[{"key":"InstanceIds","valueSet":'$(cat ids.json)'}]' \
        --query 'InstanceInformationList[].{Id:InstanceId,Platform:PlatformType}' --output json
      '''
    ).trim()
    def info = (new groovy.json.JsonSlurperClassic().parseText(invJson) ?: []) as List
    def linuxIds   = info.findAll { (it.Platform ?: '').toString().toLowerCase().contains('linux') }.collect { it.Id }
    def windowsIds = info.findAll { (it.Platform ?: '').toString().toLowerCase().contains('windows') }.collect { it.Id }
    def known = (linuxIds + windowsIds) as Set
    def unknown = instanceIds.findAll { !(it in known) }
    linuxIds.addAll(unknown)

    // Chunk to avoid SSM limits
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

  if (skipAssume) {
    // Same account: reuse current creds (no inner AssumeRole).
    run()
  } else if (srcRoleArn) {
    withAwsTenant(roleArn: srcRoleArn, externalId: srcExternalId, region: srcRegion) { run() }
  } else {
    withAwsTenant(accountRef: srcAccountRef, region: srcRegion) { run() }
  }
}

/** Convenience wrapper: reads wave/placement and installs the agent if defined.
 *  Optionally pass account: [role_arn:'...', external_id:'...'] to avoid accountRef. */
def installAgentFromWave(Map m = [:]) {
  def wave = m.wave
  def pl   = m.placement
  def acc  = m.account  // optional {role_arn, external_id}
  if (!wave || !pl) error "installAgentFromWave needs wave and placement"

  def destRegion = pl.params.region
  def src = pl.params.source
  if (!src || src.type != 'aws-ec2') {
    echo "No EC2 source defined for placement ${pl.id}; skipping agent install."
    return
  }

  // Skip inner AssumeRole if source and destination accounts are the same.
  def skip = sameAccountRef(pl)

  def args = [
    srcRegion   : src.region,
    destRegion  : destRegion,
    idsOrNames  : (src.server_ids ?: []),
    skipAssume  : skip
  ]
  if (!skip) {
    if (acc?.role_arn) {
      args.srcRoleArn    = acc.role_arn
      args.srcExternalId = acc.external_id
    } else {
      args.srcAccountRef = (src.account_ref ?: pl.params.account_ref)
    }
  }
  installAgentOnEc2(args)
}

return this
