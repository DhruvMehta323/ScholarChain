# ECS Fargate cluster with three independently deployable services (contract: INFRA-CONTRACTS.md section Services):
#   query-api         behind the ALB
#   retrieval         internal, reached through Cloud Map DNS (retrieval.<namespace>)
#   embedding-worker  SQS consumer, no inbound, regular FARGATE (no Spot)
# Desired count 0 by default; scale-up is done through min_tasks (autoscaling minimum), never by hand.

locals {
  services = {
    query-api = {
      sg        = var.security_group_ids["query_api"]
      port      = var.service_port
      lb        = var.create_alb
      discovery = false
      extra_env = { SCHOLARCHAIN_SERVICES_RETRIEVAL_URL = "http://retrieval.${var.service_discovery_namespace}:${var.service_port}", SERVICE_NAME = "query-api" }
    }
    retrieval = {
      sg        = var.security_group_ids["retrieval"]
      port      = var.service_port
      lb        = false
      discovery = true
      extra_env = { SERVICE_NAME = "retrieval" }
    }
    embedding-worker = {
      sg        = var.security_group_ids["embedding_worker"]
      port      = 0
      lb        = false
      discovery = false
      extra_env = { SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL = var.embedding_queue_url, SCHOLARCHAIN_SERVICES_EMBEDDING_DLQ_URL = var.embedding_dlq_url, SCHOLARCHAIN_EMBEDDING_QUEUE_NAME = var.embedding_queue_name, SCHOLARCHAIN_EMBEDDING_QUEUE_DEAD_LETTER_NAME = var.embedding_dlq_name, SCHOLARCHAIN_EMBEDDING_QUEUE_MAX_ATTEMPTS = tostring(var.embedding_max_attempts), SERVICE_NAME = "embedding-worker" }
    }
  }
}

resource "aws_ecs_cluster" "this" {
  name = "${var.name}-cluster"

  setting {
    name  = "containerInsights"
    value = var.enable_container_insights ? "enabled" : "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = var.service_discovery_namespace
  description = "Internal service names"
  vpc         = var.vpc_id
}

resource "aws_service_discovery_service" "retrieval" {
  name = "retrieval"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }
}

resource "aws_ecs_task_definition" "this" {
  for_each                 = local.services
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = var.execution_role_arns[each.key]
  task_role_arn            = var.task_role_arns[each.key]

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name      = each.key
    image     = "${var.ecr_repository_urls[each.key]}:${var.image_tag}"
    essential = true
    portMappings = each.value.port > 0 ? [{
      containerPort = each.value.port
      protocol      = "tcp"
    }] : []
    environment = [for k, v in merge(var.common_env, each.value.extra_env, lookup(var.service_env, each.key, {})) : { name = k, value = v }]
    secrets     = [for k in var.service_secrets[each.key] : { name = var.secret_env_names[k], valueFrom = var.secret_arns[k] }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = var.log_group_names[each.key]
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = each.key
      }
    }
  }])
}

# Ops task definition: run-task only (no service, no autoscaling, no health check). Same embedding-worker image and tag as the service.
# packaging/entrypoint.sh runs `java ... "${SERVICE_MAIN}"` and does not forward arguments, and RunTask cannot override the entryPoint, so
# there is no command: SERVICE_MAIN selects OpsLoadMain and the arguments are SCHOLARCHAIN_OPS_* environment variables, set per run through
# containerOverrides.environment by scripts/run-bounded-pipeline.sh. The defaults below make a run-task WITHOUT an override harmless (dry run,
# no sources). Logs go to the embedding-worker log group, stream prefix "ops" (stream ops/ops/<task-id>).
locals {
  ops_env = merge(var.common_env, local.services["embedding-worker"].extra_env, {
    SERVICE_NAME                 = "ops"
    SERVICE_MAIN                 = var.ops_main_class
    SCHOLARCHAIN_OPS_DRY_RUN     = "true"
    SCHOLARCHAIN_OPS_MAX_VECTORS = "0"
    JAVA_OPTS                    = var.ops_java_opts
  })
}

resource "aws_ecs_task_definition" "ops" {
  family                   = "${var.name}-ops"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.ops_cpu
  memory                   = var.ops_memory
  execution_role_arn       = var.execution_role_arns["embedding-worker"]
  task_role_arn            = var.ops_task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name        = "ops"
    image       = "${var.ecr_repository_urls["embedding-worker"]}:${var.image_tag}"
    essential   = true
    environment = [for k, v in local.ops_env : { name = k, value = v }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = var.log_group_names["embedding-worker"]
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "ops"
      }
    }
  }])
}

resource "aws_ecs_service" "this" {
  for_each                           = local.services
  name                               = each.key
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.this[each.key].arn
  desired_count                      = 0
  enable_execute_command             = false
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = each.value.lb ? 60 : null

  dynamic "capacity_provider_strategy" {
    for_each = ["FARGATE"]
    content {
      capacity_provider = capacity_provider_strategy.value
      weight            = 1
    }
  }

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [each.value.sg]
    assign_public_ip = true # NAT-free design: image pulls and AWS API calls leave through the internet gateway; no inbound is open
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = each.value.lb ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.query_api[0].arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }

  dynamic "service_registries" {
    for_each = each.value.discovery ? [1] : []
    content {
      registry_arn = aws_service_discovery_service.retrieval.arn
    }
  }

  # desired_count is owned by Application Auto Scaling (minimum = var.min_tasks, default 0).
  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this, aws_lb_listener.http]
}

############################ Load balancer (query-api only) ############################

resource "aws_lb" "this" {
  count                      = var.create_alb ? 1 : 0
  name                       = "${var.name}-alb"
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = var.public_subnet_ids
  security_groups            = [var.security_group_ids["alb"]]
  drop_invalid_header_fields = true
  enable_deletion_protection = var.alb_deletion_protection
}

resource "aws_lb_target_group" "query_api" {
  count       = var.create_alb ? 1 : 0
  name        = "${var.name}-query-api"
  port        = var.service_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id

  health_check {
    path                = var.health_check_path
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 30
}

resource "aws_lb_listener" "http" {
  count             = var.create_alb ? 1 : 0
  load_balancer_arn = aws_lb.this[0].arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = var.certificate_arn == "" ? "forward" : "redirect"
    target_group_arn = var.certificate_arn == "" ? aws_lb_target_group.query_api[0].arn : null

    dynamic "redirect" {
      for_each = var.certificate_arn == "" ? [] : [1]
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = var.create_alb && var.certificate_arn != "" ? 1 : 0
  load_balancer_arn = aws_lb.this[0].arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.query_api[0].arn
  }
}

############################ Autoscaling (bounded) ############################

resource "aws_appautoscaling_target" "this" {
  for_each           = local.services
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this[each.key].name}"
  min_capacity       = var.min_tasks[each.key]
  max_capacity       = var.max_tasks[each.key]
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = toset(["query-api", "retrieval"])
  name               = "${var.name}-${each.value}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.value].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[each.value].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[each.value].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_percent
    scale_in_cooldown  = 120
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "requests" {
  count              = var.create_alb ? 1 : 0
  name               = "${var.name}-query-api-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this["query-api"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["query-api"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["query-api"].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 200
    scale_in_cooldown  = 120
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.this[0].arn_suffix}/${aws_lb_target_group.query_api[0].arn_suffix}"
    }
  }
}

# embedding-worker: scale on queue backlog. Visible messages is used because ECS publishes no free running-task metric
# to divide by; the target is "messages per unit of desired capacity" (documented deviation, INFRA-CONTRACTS.md).
resource "aws_appautoscaling_policy" "queue_backlog" {
  name               = "${var.name}-embedding-worker-backlog"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this["embedding-worker"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["embedding-worker"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["embedding-worker"].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.queue_backlog_target
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    customized_metric_specification {
      namespace   = "AWS/SQS"
      metric_name = "ApproximateNumberOfMessagesVisible"
      statistic   = "Average"
      dimensions {
        name  = "QueueName"
        value = var.embedding_queue_name
      }
    }
  }
}
