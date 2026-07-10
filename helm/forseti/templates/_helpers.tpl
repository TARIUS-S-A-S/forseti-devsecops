{{/* Helm helpers */}}

{{- define "forseti.labels" -}}
app.kubernetes.io/name: forseti
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "forseti.backend.selectorLabels" -}}
app.kubernetes.io/name: forseti
app.kubernetes.io/component: backend
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "forseti.frontend.selectorLabels" -}}
app.kubernetes.io/name: forseti
app.kubernetes.io/component: frontend
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "forseti.postgres.selectorLabels" -}}
app.kubernetes.io/name: forseti
app.kubernetes.io/component: postgres
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
