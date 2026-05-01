{{- define "sonarqube.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sonarqube.fullname" -}}
{{- default (include "sonarqube.name" .) .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sonarqube.labels" -}}
app.kubernetes.io/name: {{ include "sonarqube.name" . }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "sonarqube.selectorLabels" -}}
app.kubernetes.io/name: {{ include "sonarqube.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
