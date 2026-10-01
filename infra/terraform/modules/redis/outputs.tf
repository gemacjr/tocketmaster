output "endpoint" {
  description = "Primary Redis endpoint (no port suffix)"
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}
