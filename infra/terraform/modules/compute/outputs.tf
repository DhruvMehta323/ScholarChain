output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  value = aws_ecs_cluster.this.arn
}

output "service_names" {
  value = [for k, s in aws_ecs_service.this : s.name]
}

output "alb_dns_name" {
  description = "Empty when create_alb is false."
  value       = try(aws_lb.this[0].dns_name, "")
}

output "alb_arn_suffix" {
  value = try(aws_lb.this[0].arn_suffix, "")
}

output "target_group_arn_suffix" {
  value = try(aws_lb_target_group.query_api[0].arn_suffix, "")
}

output "retrieval_internal_url" {
  value = "http://retrieval.${var.service_discovery_namespace}:${var.service_port}"
}

output "ops_task_definition_family" {
  value = aws_ecs_task_definition.ops.family
}

output "ops_task_definition_arn" {
  value = aws_ecs_task_definition.ops.arn
}
