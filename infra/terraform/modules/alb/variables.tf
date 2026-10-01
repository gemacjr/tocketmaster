variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "sg_ids" {
  type = list(string)
}

variable "acm_certificate_arn" {
  description = "Optional: enables the HTTPS listener"
  type        = string
  default     = ""
}
