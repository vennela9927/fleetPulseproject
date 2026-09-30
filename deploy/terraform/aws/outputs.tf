# Everything values-aws.yaml needs, so deploying the chart is: terraform output -> helm values.
output "cluster_name" { value = module.eks.cluster_name }
output "kafka_bootstrap" { value = aws_msk_cluster.kafka.bootstrap_brokers }
output "postgres_host" { value = aws_db_instance.postgres.address }
output "redis_url" { value = "rediss://${aws_elasticache_replication_group.redis.primary_endpoint_address}:6379" }
output "image_registry" { value = split("/", aws_ecr_repository.images["api"].repository_url)[0] }
output "buckets" { value = { for k, b in aws_s3_bucket.data : k => b.bucket } }
output "app_secret_name" { value = aws_secretsmanager_secret.app.name }
