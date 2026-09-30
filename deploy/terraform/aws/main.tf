# FleetPulse on AWS: network, Kubernetes, and the managed services the Helm chart points at.
# ClickHouse runs as ClickHouse Cloud (or the Altinity operator inside EKS); both speak the same
# HTTP interface, so the application does not change. See deploy/README.md.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.60" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

provider "aws" {
  region = var.region
  default_tags { tags = { project = "fleetpulse", environment = var.environment } }
}

data "aws_availability_zones" "available" { state = "available" }

locals {
  name = "fleetpulse-${var.environment}"
  azs  = slice(data.aws_availability_zones.available.names, 0, 3)
}

# ------------------------------------------------------------------ network: 3 AZs, private data tier
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.13"

  name               = local.name
  cidr               = "10.40.0.0/16"
  azs                = local.azs
  private_subnets    = ["10.40.0.0/19", "10.40.32.0/19", "10.40.64.0/19"]
  public_subnets     = ["10.40.96.0/22", "10.40.100.0/22", "10.40.104.0/22"]
  database_subnets   = ["10.40.112.0/24", "10.40.113.0/24", "10.40.114.0/24"]
  enable_nat_gateway = true
  single_nat_gateway = var.environment != "prod"

  private_subnet_tags = { "kubernetes.io/role/internal-elb" = 1 }
  public_subnet_tags  = { "kubernetes.io/role/elb" = 1 }
}

# ------------------------------------------------------------------ Kubernetes
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.24"

  cluster_name                   = local.name
  cluster_version                = "1.31"
  vpc_id                         = module.vpc.vpc_id
  subnet_ids                     = module.vpc.private_subnets
  cluster_endpoint_public_access = true
  enable_irsa                    = true

  eks_managed_node_groups = {
    app = {
      instance_types = ["m6i.xlarge"]
      min_size       = 3
      max_size       = 12
      desired_size   = 3
    }
  }
}

# Security group for the data services: reachable from the cluster's nodes only.
resource "aws_security_group" "data" {
  name   = "${local.name}-data"
  vpc_id = module.vpc.vpc_id

  ingress {
    description     = "Postgres, Redis and Kafka from EKS nodes"
    from_port       = 5432
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [module.vpc.vpc_cidr_block]
  }
}

# ------------------------------------------------------------------ Postgres (pgvector is built into RDS 16)
resource "random_password" "db_admin" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = module.vpc.database_subnets
}

resource "aws_db_instance" "postgres" {
  identifier                   = local.name
  engine                       = "postgres"
  engine_version               = "16.4"
  instance_class               = var.db_instance_class
  allocated_storage            = 100
  max_allocated_storage        = 500
  storage_type                 = "gp3"
  storage_encrypted            = true
  db_name                      = "fleet"
  username                     = "fleet_admin"
  password                     = random_password.db_admin.result
  multi_az                     = var.environment == "prod"
  db_subnet_group_name         = aws_db_subnet_group.this.name
  vpc_security_group_ids       = [aws_security_group.data.id]
  backup_retention_period      = 14
  deletion_protection          = var.environment == "prod"
  skip_final_snapshot          = var.environment != "prod"
  final_snapshot_identifier    = "${local.name}-final"
  performance_insights_enabled = true
}

# ------------------------------------------------------------------ Redis (live state; rebuildable from Kafka)
resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = module.vpc.private_subnets
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = local.name
  description                = "FleetPulse live vehicle state"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = "cache.r7g.large"
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.data.id]
}

# ------------------------------------------------------------------ Kafka (MSK, 3 brokers across AZs)
resource "aws_msk_cluster" "kafka" {
  cluster_name           = local.name
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = "kafka.m7g.large"
    client_subnets  = module.vpc.private_subnets
    security_groups = [aws_security_group.data.id]
    storage_info {
      ebs_storage_info { volume_size = 500 }
    }
  }
  encryption_info {
    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT"
      in_cluster    = true
    }
  }
}

# ------------------------------------------------------------------ object storage: ClickHouse cold tier, model artifacts
resource "aws_s3_bucket" "data" {
  for_each = toset(["clickhouse-cold", "telemetry-archive", "ml-artifacts"])
  bucket   = "${local.name}-${each.key}"
}

resource "aws_s3_bucket_public_access_block" "data" {
  for_each                = aws_s3_bucket.data
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  for_each = aws_s3_bucket.data
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "aws:kms" }
  }
}

# ------------------------------------------------------------------ images
resource "aws_ecr_repository" "images" {
  for_each             = toset(["ingest-gateway", "normalizer", "stream-processor", "simulator", "api", "ml", "web"])
  name                 = "fleetpulse/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
}

# ------------------------------------------------------------------ secrets, read into the cluster by External Secrets
resource "aws_secretsmanager_secret" "app" {
  name = "${local.name}/app"
}
