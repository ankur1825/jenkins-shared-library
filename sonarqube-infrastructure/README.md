# SonarQube Infrastructure

This folder contains the Helm chart used to deploy SonarQube for the Horizon AI DevSecOps platform.

The chart is designed for client-hosted deployments:

- LDAP/OpenLDAP settings are configurable through `sonarqube/values.yaml`.
- LDAP bind credentials are stored in a Kubernetes Secret, not a ConfigMap.
- Namespace, ingress host, TLS secret, storage class, and storage sizes are Helm values.
- Jenkins talks to SonarQube through the in-cluster service URL by default.

## Deploy

```bash
helm upgrade --install sonarqube ./sonarqube \
  -n horizon-relevance-dev
```

For a client environment, override at least:

```bash
helm upgrade --install sonarqube ./sonarqube \
  -n <client-namespace> \
  --set namespaceOverride=<client-namespace> \
  --set ingress.host=sonarqube.<client-domain> \
  --set ldap.url=ldap://openldap.<client-namespace>.svc.cluster.local:389 \
  --set ldap.bindDn='cn=admin,dc=client,dc=local' \
  --set ldap.userBaseDn='ou=users,dc=client,dc=local' \
  --set ldap.groupBaseDn='ou=groups,dc=client,dc=local'
```

## Jenkins Integration

Jenkins requires:

- `SONAR_HOST_URL`, usually `http://sonarqube.<namespace>.svc.cluster.local:9000/sonarqube`
- `SONAR_TOKEN`, stored in the Kubernetes Secret `sonarqube-jenkins-token`
- SonarScanner CLI installed at `/var/jenkins_home/tools/sonar-scanner`

The Jenkins Helm values install the scanner into the persistent Jenkins home volume during pod startup.
