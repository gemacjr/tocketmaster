output "endpoint" {
  description = "VPC endpoint URL (https) for the OpenSearch domain"
  value       = "https://${aws_opensearch_domain.this.endpoints["vpc"]}"
}
