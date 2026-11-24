output "ecr_repository_urls" {
  value = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "ecr_repository_arns" {
  value = { for k, r in aws_ecr_repository.this : k => r.arn }
}

output "embedding_queue_url" {
  value = aws_sqs_queue.embedding.url
}

output "embedding_queue_arn" {
  value = aws_sqs_queue.embedding.arn
}

output "embedding_queue_name" {
  value = aws_sqs_queue.embedding.name
}

output "embedding_dlq_url" {
  value = aws_sqs_queue.embedding_dlq.url
}

output "embedding_dlq_arn" {
  value = aws_sqs_queue.embedding_dlq.arn
}

output "embedding_dlq_name" {
  value = aws_sqs_queue.embedding_dlq.name
}

output "secret_arns" {
  description = "Secret short name to ARN (ARN only; no value)."
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.arn }
}
