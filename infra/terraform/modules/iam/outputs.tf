output "ecs_execution_role_arns" {
  value = { for k, r in aws_iam_role.ecs_execution : k => r.arn }
}

output "task_role_arns" {
  value = { for k, r in aws_iam_role.task : k => r.arn }
}

output "emr_service_role_arn" {
  value = aws_iam_role.emr_service.arn
}

output "emr_instance_profile_arn" {
  value = aws_iam_instance_profile.emr_ec2.arn
}

output "emr_instance_profile_name" {
  value = aws_iam_instance_profile.emr_ec2.name
}

output "emr_ec2_role_arn" {
  value = aws_iam_role.emr_ec2.arn
}

output "sfn_role_arn" {
  value = aws_iam_role.sfn.arn
}

output "deployer_role_arn" {
  description = "Empty until deployment_principal_arns is set."
  value       = try(aws_iam_role.deployer[0].arn, "")
}
