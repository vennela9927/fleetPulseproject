{{- define "fleetpulse.labels" -}}
app.kubernetes.io/part-of: fleetpulse
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- define "fleetpulse.image" -}}
{{ .root.Values.image.registry }}/{{ .name }}:{{ .root.Values.image.tag }}
{{- end }}

{{- define "fleetpulse.secret" -}}
valueFrom: { secretKeyRef: { name: {{ .root.Values.external.secretName }}, key: {{ .key }} } }
{{- end }}

{{/* Runs as the image's unprivileged user, read-only root filesystem, no privilege escalation. */}}
{{- define "fleetpulse.podSecurity" -}}
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  fsGroup: 10001
  seccompProfile: { type: RuntimeDefault }
{{- end }}

{{- define "fleetpulse.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: [ALL] }
{{- end }}

{{/* Environment per service: only endpoints and secret references, all from values. */}}
{{- define "fleetpulse.env" -}}
{{- $v := .root.Values -}}
{{- $jdbc := printf "jdbc:postgresql://%s:%v/%s" $v.external.postgres.host $v.external.postgres.port $v.external.postgres.database -}}
{{- if has .name (list "ingest-gateway" "normalizer" "stream-processor") }}
- { name: KAFKA_BOOTSTRAP, value: {{ $v.external.kafkaBootstrap | quote }} }
- { name: TOPIC_REPLICATION, value: {{ $v.external.kafkaReplicationFactor | quote }} }
{{- end }}
{{- if eq .name "stream-processor" }}
- { name: POSTGRES_URL, value: {{ $jdbc | quote }} }
- name: SERVICE_DB_PASSWORD
  {{- include "fleetpulse.secret" (dict "root" .root "key" "service-db-password") | nindent 2 }}
- { name: REDIS_URL, value: {{ $v.external.redisUrl | quote }} }
- { name: CLICKHOUSE_URL, value: {{ $v.external.clickhouseUrl | quote }} }
- name: CLICKHOUSE_PASSWORD
  {{- include "fleetpulse.secret" (dict "root" .root "key" "clickhouse-password") | nindent 2 }}
{{- end }}
{{- if eq .name "api" }}
- name: APP_DB_PASSWORD
  {{- include "fleetpulse.secret" (dict "root" .root "key" "app-db-password") | nindent 2 }}
- { name: API_POSTGRES_DSN, value: {{ printf "postgresql://fleet_app:$(APP_DB_PASSWORD)@%s:%v/%s" $v.external.postgres.host $v.external.postgres.port $v.external.postgres.database | quote }} }
- { name: API_REDIS_URL, value: {{ printf "%s/0" $v.external.redisUrl | quote }} }
- { name: API_CLICKHOUSE_URL, value: {{ $v.external.clickhouseUrl | quote }} }
- name: API_CLICKHOUSE_PASSWORD
  {{- include "fleetpulse.secret" (dict "root" .root "key" "clickhouse-api-password") | nindent 2 }}
- { name: API_OIDC_ISSUER, value: {{ $v.external.oidcIssuer | quote }} }
- { name: API_OIDC_JWKS_URL, value: {{ $v.external.oidcJwksUrl | quote }} }
- { name: API_CORS_ORIGINS, value: {{ printf "[\"https://%s\"]" $v.ingress.host | quote }} }
- { name: API_KAFKA_BOOTSTRAP, value: {{ $v.external.kafkaBootstrap | quote }} }
- { name: API_NORMALIZER_URL, value: "http://normalizer:8082" }
- { name: API_SIMULATOR_URL, value: "http://simulator:8090" }
- { name: API_DEMO_CONTROLS, value: {{ $v.demo.enabled | quote }} }
- name: GEMINI_API_KEY
  valueFrom: { secretKeyRef: { name: {{ $v.external.secretName }}, key: gemini-api-key, optional: true } }
{{- end }}
{{- end }}
