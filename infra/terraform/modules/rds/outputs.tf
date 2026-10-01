output "endpoint" {
  description = "host:port of the writer"
  value       = aws_db_instance.this.address
}

output "master_secret_arn" {
  value = aws_secretsmanager_secret.master.arn
}
