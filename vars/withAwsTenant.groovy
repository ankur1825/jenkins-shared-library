def call(Map cfg = [:], Closure body) {
  def roleArn   = cfg.roleArn
  def external  = cfg.externalId
  def region    = cfg.region ?: 'us-east-1'
  def sessName  = "jenkins-${env.BUILD_ID}"

  def j = sh(
    script: """
      aws sts assume-role \
        --role-arn '${roleArn}' \
        --role-session-name '${sessName}' \
        --external-id '${external}' \
        --duration-seconds 3600 \
        --output json
    """,
    returnStdout: true
  ).trim()

  def creds = readJSON text: j
  withEnv([
    "AWS_ACCESS_KEY_ID=${creds.Credentials.AccessKeyId}",
    "AWS_SECRET_ACCESS_KEY=${creds.Credentials.SecretAccessKey}",
    "AWS_SESSION_TOKEN=${creds.Credentials.SessionToken}",
    "AWS_DEFAULT_REGION=${region}"
  ]) {
    body()
  }
}
