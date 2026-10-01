variable "name" {
  type = string
}

variable "alb_dns_name" {
  type        = string
  description = "/api/* traffic on the distribution is forwarded here"
}

variable "domain_name" {
  description = "Optional custom domain (requires an ACM cert in us-east-1 wired via acm_certificate_arn)"
  type        = string
  default     = ""
}
