resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "this" {
  name_prefix = "${var.name}-"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.ingress_sg_id]
  }
}

# Single-node for dev. For prod: num_cache_clusters = 2 + automatic_failover_enabled.
# Holds are an availability optimization only — Postgres OCC is the correctness
# boundary, so a Redis failover degrades UX briefly but can never double-sell (docs/05).
resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "Distributed holds, waiting room, read caches"

  engine                = "redis"
  engine_version        = "7.1"
  node_type             = var.node_type
  num_cache_clusters    = 1
  port                  = 6379

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  # holds are non-durable by design; AOF/RDB adds nothing here
  snapshot_retention_limit = 0
}
