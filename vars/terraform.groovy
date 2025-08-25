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
  sh """
    cd '${dir}'
    # optional but keeps things clean if the backend changed
    rm -rf .terraform
    terraform --version
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    echo '${tfvarsJson}' > wave.auto.tfvars.json
    terraform plan -input=false -out=plan.tfplan
  """
}

def apply(String dir) {
  sh """
    cd '${dir}'
    terraform init -input=false -reconfigure \\
      -backend-config='${env.WORKSPACE}/.tfbackend/backend.hcl'
    terraform apply -input=false -auto-approve plan.tfplan || terraform apply -input=false -auto-approve
  """
}

return this
