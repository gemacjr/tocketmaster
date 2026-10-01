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
  type = string
}

variable "node_type" {
  type    = string
  default = "cache.t4g.small"
}
