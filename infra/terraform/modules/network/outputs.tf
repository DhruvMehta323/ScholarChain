output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "security_group_ids" {
  value = {
    alb              = aws_security_group.alb.id
    query_api        = aws_security_group.query_api.id
    retrieval        = aws_security_group.retrieval.id
    embedding_worker = aws_security_group.embedding_worker.id
    emr              = aws_security_group.emr.id
    neptune          = aws_security_group.neptune.id
    ops              = aws_security_group.ops.id
  }
}

output "gateway_endpoint_ids" {
  value = { s3 = aws_vpc_endpoint.s3.id, dynamodb = aws_vpc_endpoint.dynamodb.id }
}
