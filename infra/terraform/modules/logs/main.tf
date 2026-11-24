# CloudWatch log groups with short retention. Default service-side encryption (no KMS key).

resource "aws_cloudwatch_log_group" "this" {
  for_each          = toset(var.group_names)
  name              = "/${var.name}/${each.value}"
  retention_in_days = var.retention_days
}

variable "name" {
  type = string
}

variable "group_names" {
  type = list(string)
}

variable "retention_days" {
  type    = number
  default = 7
}

output "log_group_names" {
  value = { for k, g in aws_cloudwatch_log_group.this : k => g.name }
}

output "log_group_arns" {
  value = { for k, g in aws_cloudwatch_log_group.this : k => g.arn }
}
