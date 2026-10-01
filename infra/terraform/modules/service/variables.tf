variable "name" {
  description = "Unique name for this service's resources"
  type        = string
}

variable "module_name" {
  description = "Gradle subproject under services/ (also the ECR repo name)"
  type        = string
}

variable "container_port" {
  type = number
}

variable "desired_count" {
  type = number
}

variable "cpu" {
  type = number
}

variable "memory" {
  type = number
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "cluster_name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "services_sg_id" {
  type = string
}

variable "listener_arn" {
  description = "ALB listener that receives this service's path-based routing rules"
  type        = string
}

variable "path_patterns" {
  type = list(string)
}

variable "rule_priority" {
  type = number
}

variable "alb_dns_name" {
  description = "Used for service-to-service calls and the payment webhook self-route"
  type        = string
}

variable "db_endpoint" {
  type = string
}

variable "db_secret_arn" {
  description = "Secrets Manager ARN holding {username,password} JSON"
  type        = string
}

variable "redis_endpoint" {
  type = string
}

variable "opensearch_url" {
  type    = string
  default = ""
}
