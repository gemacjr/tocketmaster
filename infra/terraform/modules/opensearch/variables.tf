variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  description = "Single-AZ dev placement; prod should use two AZs with zone-awareness"
  type        = string
}

variable "ingress_sg_id" {
  type = string
}

variable "instance_type" {
  type    = string
  default = "t3.small.search"
}
