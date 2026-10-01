variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "ingress_sg_id" {
  description = "SG allowed to reach Postgres (the services SG)"
  type        = string
}

variable "instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "multi_az" {
  type    = bool
  default = true
}

variable "database_name" {
  type    = string
  default = "tocketmaster"
}

variable "engine_version" {
  type    = string
  default = "16.3"
}
