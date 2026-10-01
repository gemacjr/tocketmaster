output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "edge_sg_id" {
  value = aws_security_group.edge.id
}

output "services_sg_id" {
  value = aws_security_group.services.id
}
