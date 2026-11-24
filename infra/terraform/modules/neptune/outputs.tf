output "enabled" {
  value = var.enabled
}

output "cluster_endpoint" {
  description = "Writer endpoint host name (empty when Neptune is disabled)."
  value       = try(aws_neptune_cluster.this[0].endpoint, "")
}

output "reader_endpoint" {
  value = try(aws_neptune_cluster.this[0].reader_endpoint, "")
}

output "port" {
  value = try(aws_neptune_cluster.this[0].port, 8182)
}

output "cluster_resource_id" {
  description = "Resource ID used in neptune-db IAM resource ARNs."
  value       = try(aws_neptune_cluster.this[0].cluster_resource_id, "")
}

output "cluster_arn" {
  value = try(aws_neptune_cluster.this[0].arn, "")
}

output "loader_role_arn" {
  value = try(aws_iam_role.loader[0].arn, "")
}
