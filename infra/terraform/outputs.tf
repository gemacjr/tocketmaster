output "alb_dns_name" {
  value = module.alb.dns_name
}

output "cloudfront_domain" {
  value = module.frontend_cdn.cloudfront_domain
}

output "ecr_repositories" {
  value = { for k, s in module.services : k => s.ecr_repository_url }
}

output "rds_endpoint" {
  value = module.rds.endpoint
}

output "redis_endpoint" {
  value = module.redis.endpoint
}

output "opensearch_endpoint" {
  value       = module.opensearch.endpoint
  sensitive   = false
}
