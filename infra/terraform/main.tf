# ---------------------------------------------------------------------------
# Network: public + private subnets across two AZs, NAT for egress, SGs.
# ---------------------------------------------------------------------------
module "network" {
  source = "./modules/network"

  project  = local.name_prefix
  vpc_cidr = "10.42.0.0/16"
  az_count = 2
}

# ---------------------------------------------------------------------------
# Data tier: RDS PostgreSQL (ACID booking core), ElastiCache Redis (holds +
# waiting room + caches), OpenSearch (event search index).
# ---------------------------------------------------------------------------
module "rds" {
  source = "./modules/rds"

  name           = "${local.name_prefix}-pg"
  subnet_ids     = module.network.private_subnet_ids
  ingress_sg_id  = module.network.services_sg_id
  instance_class = "db.t4g.medium"
  multi_az       = true
  database_name  = "tocketmaster"
}

module "redis" {
  source = "./modules/redis"

  name          = "${local.name_prefix}-redis"
  subnet_ids    = module.network.private_subnet_ids
  ingress_sg_id = module.network.services_sg_id
  node_type     = "cache.t4g.small"
}

module "opensearch" {
  source = "./modules/opensearch"

  name          = "${local.name_prefix}-search"
  subnet_id     = module.network.private_subnet_ids[0]
  ingress_sg_id = module.network.services_sg_id
  instance_type = "t3.small.search"
}

# ---------------------------------------------------------------------------
# Edge: ALB in front of services; CloudFront in front of everything.
# /api/* forwards to the ALB; everything else is S3-hosted SPA assets.
# ---------------------------------------------------------------------------
module "alb" {
  source = "./modules/alb"

  name       = local.name_prefix
  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.public_subnet_ids
  sg_ids     = [module.network.edge_sg_id]
}

resource "aws_ecs_cluster" "main" {
  name = local.name_prefix

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# ---------------------------------------------------------------------------
# One ECS Fargate service per microservice (see local.services in variables.tf).
# ---------------------------------------------------------------------------
module "services" {
  source   = "./modules/service"
  for_each = local.services

  name               = "${local.name_prefix}-${each.key}"
  module_name        = each.value.module_name
  container_port     = each.value.port
  desired_count      = each.value.desired_count
  cpu                = each.value.cpu
  memory             = each.value.memory
  image_tag          = var.container_image_tag

  cluster_name       = aws_ecs_cluster.main.name
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  services_sg_id     = module.network.services_sg_id

  listener_arn       = module.alb.https_listener_arn != null ? module.alb.https_listener_arn : module.alb.http_listener_arn
  path_patterns      = each.value.path_patterns
  rule_priority      = each.value.priority

  alb_dns_name   = module.alb.dns_name
  db_endpoint    = module.rds.endpoint
  db_secret_arn  = module.rds.master_secret_arn
  redis_endpoint = module.redis.endpoint
  opensearch_url = module.opensearch.endpoint
}

# ---------------------------------------------------------------------------
# Frontend: SPA in S3 behind CloudFront (OAC).
# ---------------------------------------------------------------------------
module "frontend_cdn" {
  source = "./modules/frontend-cdn"

  name         = local.name_prefix
  alb_dns_name = module.alb.dns_name
}
