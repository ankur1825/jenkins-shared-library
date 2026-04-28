// vars/terraform.groovy

def withBackend(Map b = [:], Closure body) {
  // b: bucket, table, prefix, region (optional)
  b.region = b.region ?: (env.AWS_DEFAULT_REGION ?: 'us-east-1')
  sh "mkdir -p .tfbackend"
  writeFile file: ".tfbackend/backend.hcl", text: """
bucket         = "${b.bucket}"
key            = "${b.prefix}/terraform.tfstate"
region         = "${b.region}"
dynamodb_table = "${b.table}"
"""
  body()
}

def checkoutModules(String repo, String ref, String credId, String dirName = '.') {
  dir(dirName) {
    checkout([$class: 'GitSCM',
      branches: [[name: ref]],
      userRemoteConfigs: [[url: repo, credentialsId: credId]]
    ])
  }
}

def plan(String dir, String tfvarsJson) {
  // write tfvars as a file to avoid shell quoting problems
  writeFile file: "${dir}/wave.auto.tfvars.json", text: tfvarsJson
  sh """
    set -e
    cd '${dir}'
    # optional but keeps things clean if the backend changed
    rm -rf .terraform
    terraform --version
    terraform fmt -check -recursive
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    terraform validate
    if command -v trivy >/dev/null 2>&1; then
      trivy config --severity "\${SECURITY_FAIL_ON_SEVERITY:-CRITICAL,HIGH}" --exit-code 1 .
    elif command -v checkov >/dev/null 2>&1; then
      checkov -d .
    elif command -v tfsec >/dev/null 2>&1; then
      tfsec .
    else
      echo "No Terraform security scanner found. Install trivy, checkov, or tfsec for stronger IaC guardrails."
    fi
    terraform plan -input=false -out=plan.tfplan
    terraform show -json plan.tfplan > plan.json
  """
  archiveArtifacts artifacts: "${dir}/plan.tfplan,${dir}/plan.json", allowEmptyArchive: true
}

def apply(String dir) {
  // tfvars should already exist (mgn.execute writes it); just init + apply
  sh """
    set -e
    cd '${dir}'
    terraform --version
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    test -f plan.tfplan
    terraform apply -input=false -auto-approve plan.tfplan
  """
}

def destroy(String dir, String tfvarsJson) {
  // ensure the same tfvars and backend are used for destroy
  writeFile file: "${dir}/wave.auto.tfvars.json", text: tfvarsJson
  sh """
    set -e
    cd '${dir}'
    terraform --version
    terraform fmt -check -recursive
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    terraform validate
    terraform destroy -input=false -auto-approve
  """
}

return this
