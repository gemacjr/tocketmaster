variable "region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "project" {
  type    = string
  default = "tocketmaster"
}

variable "container_image_tag" {
  description = "Image tag pushed to each service's ECR repo (built from infra/docker/service.Dockerfile)"
  type        = string
  default     = "latest"
}

variable "domain_name" {
  description = "Optional public domain for CloudFront/Route53. Empty = use the default CloudFront domain."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------- sizing

variable "event_service_desired" {
  type    = number
  default = 3
}

variable "booking_service_desired" {
  type    = number
  default = 2
}

variable "search_service_desired" {
  type    = number
  default = 2
}

variable "queue_service_desired" {
  type    = number
  default = 2
}

locals {
  name_prefix = "${var.project}-${var.environment}"

  services = {
    event = {
      module_name    = "event-service"
      port           = 8081
      desired_count  = var.event_service_desired
      cpu            = 512
      memory         = 1024
      path_patterns  = ["/api/events*", "/api/admin/events*"]
      priority       = 100
    }
    search = {
      module_name   = "search-service"
      port          = 8082
      desired_count = var.search_service_desired
      cpu           = 512
      memory        = 1024
      path_patterns = ["/api/search*", "/api/admin/search*", "/internal/*"]
      priority      = 110
    }

    booking = {
      module_name   = "booking-service"
      port          = 8083
      desired_count = var.booking_service_desired
      cpu           = 1024
      memory        = 2048
      # bookings/payments/users all live on the booking service
      path_patterns = ["/api/bookings*", "/api/payments*", "/api/users*"]
      priority      = 120
    }
    queue = {
      module_name   = "queue-service"
      port          = 8084
      desired_count = var.queue_service_desired
      cpu           = 512
      memory        = 1024
      path_patterns = ["/api/queue*", "/api/admin/queue*"]
      priority      = 130
    }
  }
}
