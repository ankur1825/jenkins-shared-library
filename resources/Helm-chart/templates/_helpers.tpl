_helpers.tpl

{{/*
Expand the name of the app from values.appName (coming from config.json)
*/}}
{{- define "generic-app.name" -}}
{{- .Values.appName | default .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/*
Create a fully qualified app name using appName and Release.Name
*/}}
{{- define "generic-app.fullname" -}}
{{- printf "%s-%s" (include "generic-app.name" .) .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end }}
