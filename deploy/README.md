# Deploying FleetPulse

The application is seven container images configured only through environment variables. The
Helm chart turns cloud-specific endpoints (values files) into those variables, so moving between
clouds changes values and infrastructure, never application code.

| Piece | Local (docker compose) | AWS | Google Cloud | Azure |
|---|---|---|---|---|
| Kubernetes | - | EKS | GKE | AKS |
| Kafka | apache/kafka (1 broker) | MSK | Managed Service for Apache Kafka | Event Hubs (Kafka API) or Confluent Cloud |
| Postgres 16 + pgvector | pgvector/pgvector | RDS for PostgreSQL | Cloud SQL | Azure Database for PostgreSQL |
| ClickHouse | clickhouse-server | ClickHouse Cloud (or the Altinity operator in EKS) | ClickHouse Cloud | ClickHouse Cloud |
| Redis | redis | ElastiCache | Memorystore | Azure Cache for Redis |
| Object storage (cold tier, archives) | SeaweedFS (S3 API) | S3 | GCS (S3-compatible XML API) | Blob via an S3 gateway |
| OIDC | Keycloak | Keycloak on EKS or Cognito | Keycloak or Identity Platform | Keycloak or Entra ID |

Every service reaches these through standard protocols (Kafka, Postgres wire, ClickHouse HTTP,
Redis, S3, OIDC), which is what keeps the code cloud-neutral.

## AWS end to end

```bash
cd deploy/terraform/aws
terraform init && terraform apply -var environment=prod     # VPC, EKS, RDS, MSK, ElastiCache, S3, ECR

# Images: build once, push to the registry Terraform created.
REG=$(terraform output -raw image_registry)
for s in ingest-gateway normalizer stream-processor simulator; do
  docker build -f services/Dockerfile.java --build-arg SERVICE=$s -t $REG/fleetpulse/$s:1.0.0 . && docker push $REG/fleetpulse/$s:1.0.0
done
docker build -t $REG/fleetpulse/api:1.0.0 services/api && docker push $REG/fleetpulse/api:1.0.0
docker build -t $REG/fleetpulse/ml:1.0.0 services/ml && docker push $REG/fleetpulse/ml:1.0.0
docker build -t $REG/fleetpulse/web:1.0.0 --build-arg VITE_KEYCLOAK_URL=https://auth.fleetpulse.example.com web && docker push $REG/fleetpulse/web:1.0.0

# Schema and secrets: run infra/postgres/init and infra/clickhouse/init once against the managed
# databases; put the passwords in Secrets Manager (terraform output app_secret_name), synced to the
# `fleetpulse-secrets` Kubernetes secret by External Secrets.

helm upgrade --install fleetpulse deploy/helm/fleetpulse -f deploy/helm/fleetpulse/values-aws.yaml
```

Google Cloud is the same with `values-gcp.yaml`; a GCP Terraform module (GKE, Cloud SQL,
Memorystore, Managed Kafka, GCS) would mirror `terraform/aws` resource for resource.

## What the chart sets up

- Gateway, API and web as Deployments with autoscaling on CPU; normalizer and stream processor as
  StatefulSets (Kafka Streams keeps local state on a volume, rebuilt from changelogs if lost).
- Replicas spread across zones, a PodDisruptionBudget per service, readiness and liveness probes.
- Pods run as a non-root user with a read-only root filesystem and no Linux capabilities.
- A network policy admitting traffic only from inside the release and from the ingress controller.
- Model jobs as CronJobs: scoring nightly, training weekly, feed drift every five minutes.
- No secrets in values: every password is a reference to a Kubernetes secret.
- Demo controls and the simulator are off unless `demo.enabled=true`.

## Validated here

`helm lint` passes and `helm template` renders 25 resources for both values files; `terraform
validate` passes for `terraform/aws`. Neither has been applied to a real cloud account from this
repository.
