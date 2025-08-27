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

def checkoutModules(String repo, String ref) {
  checkout([$class: 'GitSCM',
    branches: [[name: ref]],
    userRemoteConfigs: [[url: repo, credentialsId: 'github-token']]
  ])
}

def plan(String dir, String tfvarsJson) {
  sh """
    cd '${dir}'
    terraform --version
    terraform init -input=false -backend-config=../../.tfbackend/backend.hcl
    echo '${tfvarsJson}' > wave.auto.tfvars.json
    terraform plan -input=false -out=plan.tfplan
  """
}

def apply(String dir) {
  sh """
    cd '${dir}'
    terraform apply -input=false -auto-approve plan.tfplan || terraform apply -input=false -auto-approve
  """
}

def destroy(String dir, String tfvarsJson) {
  sh """
    set -e
    cd '${dir}'
    terraform --version
    # Use the same backend file your plan/apply uses
    terraform init -input=false -reconfigure -backend-config=../../.tfbackend/backend.hcl
    # Recreate the same tfvars the stacks expect
    cat > wave.auto.tfvars.json <<'JSON'
${tfvarsJson}
JSON
    terraform destroy -input=false -auto-approve
  """
}

return this
