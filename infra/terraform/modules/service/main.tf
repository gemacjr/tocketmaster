resource "aws_ecr_repository" "this" {
  name = var.module_name

  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}


# ---------------------------------------------------------------- IAM

resource "aws_iam_role" "execution" {
  name = "${var.name}-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "db_secret" {
  name = "read-db-secret"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [var.db_secret_arn]
    }]
  })
}

# ---------------------------------------------------------------- task definition

locals {
  db_url        = "jdbc:postgresql://${var.db_endpoint}:5432/tocketmaster"
  redis_uri     = "redis://${var.redis_endpoint}:6379"
  via_alb       = "http://${var.alb_dns_name}"

  base_env = [
    { name = "SPRING_DATASOURCE_URL", value = local.db_url },
    { name = "REDIS_URI",             value = local.redis_uri },
    { name = "JAVA_OPTS",             value = "-XX:MaxRAMPercentage=75" },
  ]

  service_env = (
    var.module_name == "event-service" ? [
      { name = "SEARCH_SERVICE_URL", value = local.via_alb }
    ] : var.module_name == "search-service" ? [
      { name = "OPENSEARCH_URL", value = var.opensearch_url }
    ] : var.module_name == "booking-service" ? [
      { name = "APP_SELF_URL", value = local.via_alb }
    ] : []
  )

  secrets = [
    {
      name      = "SPRING_DATASOURCE_USERNAME"
      valueFrom = "${var.db_secret_arn}:username::"
    },
    {
      name      = "SPRING_DATASOURCE_PASSWORD"
      valueFrom = "${var.db_secret_arn}:password::"
    }
  ]
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn

  container_definitions = jsonencode([{
    name      = var.module_name
    image     = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"
    essential = true

    portMappings = [{
      containerPort = var.container_port
      protocol      = "tcp"
    }]

    environment = concat(local.base_env, local.service_env)
    secrets     = local.secrets

    healthCheck = {
      command     = ["CMD-SHELL", "curl -sf http://localhost:${var.container_port}/actuator/health || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 6
      startPeriod = 60
    }

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = data.aws_region.current.name
        awslogs-stream-prefix = var.name
      }
    }

    ulimits = [{
      name      = "nofile"
      softLimit = 65536
      hardLimit = 65536
    }]
  }])
}

data "aws_region" "current" {}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name}"
  retention_in_days = 14
}

# ---------------------------------------------------------------- target group + routing

resource "aws_lb_target_group" "this" {
  name        = var.name
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 30
}

resource "aws_lb_listener_rule" "route" {
  listener_arn = var.listener_arn
  priority     = var.rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    path_pattern {
      values = var.path_patterns
    }
  }
}

# ---------------------------------------------------------------- service

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = var.cluster_name
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.private_subnet_ids
    security_groups = [var.services_sg_id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = var.module_name
    container_port   = var.container_port
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # Fargate rolling deploys; CodePipeline/CodeDeploy blue-green can wrap this later.
}

# ---------------------------------------------------------------- autoscaling

resource "aws_appautoscaling_target" "cpu" {
  max_capacity       = max(var.desired_count * 3, 4)
  min_capacity       = var.desired_count
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.this.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-cpu-60"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.cpu.resource_id
  scalable_dimension = aws_appautoscaling_target.cpu.scalable_dimension
  service_namespace  = aws_appautoscaling_target.cpu.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    disable_scale_in   = false
    scale_in_cooldown  = 60
    scale_out_cooldown = 30

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
