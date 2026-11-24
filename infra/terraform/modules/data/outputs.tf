output "bucket_names" {
  value = { for k, b in aws_s3_bucket.this : k => b.bucket }
}

output "bucket_arns" {
  value = { for k, b in aws_s3_bucket.this : k => b.arn }
}

output "table_arns" {
  description = "Logical table key (for example RunLedger, analytics-bridges) to ARN."
  value       = { for k, t in aws_dynamodb_table.this : k => t.arn }
}

output "table_names" {
  value = { for k, t in aws_dynamodb_table.this : k => t.name }
}

output "vector_bucket_name" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_name
}

output "vector_bucket_arn" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_arn
}

output "vector_index_arns" {
  value = { for k, i in aws_s3vectors_index.this : k => i.index_arn }
}
