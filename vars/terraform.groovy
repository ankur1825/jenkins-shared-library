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
    cd '${dir}'
    # optional but keeps things clean if the backend changed
    rm -rf .terraform
    terraform --version
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    terraform plan -input=false -out=plan.tfplan
  """
}

def apply(String dir) {
  // tfvars should already exist (mgn.execute writes it); just init + apply
  sh """
    cd '${dir}'
    terraform --version
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    terraform apply -input=false -auto-approve plan.tfplan || terraform apply -input=false -auto-approve
  """
}

def destroy(String dir, String tfvarsJson) {
  // ensure the same tfvars and backend are used for destroy
  writeFile file: "${dir}/wave.auto.tfvars.json", text: tfvarsJson
  sh """
    cd '${dir}'
    terraform --version
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    terraform destroy -input=false -auto-approve
  """
}

return this
