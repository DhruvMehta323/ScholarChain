# Every name and ARN the application configuration needs (INFRA-CONTRACTS.md section Outputs). No secret values.

output "account_id" {
  value = local.account_id
}

output "region" {
  value = var.aws_region
}

output "app_environment" {
  description = "SCHOLARCHAIN_* environment variables for the applications (also injected into the ECS services)."
  value       = local.app_environment
}

output "raw_bucket" {
  value = module.data.bucket_names["raw"]
}

output "rag_audit_bucket" {
  description = "Bucket (the curated bucket) holding rag/audit/ written by query-api. No application config key or env var exists for it yet (open point O3); the name is NOT set on any service."
  value       = module.data.bucket_names["curated"]
}

output "curated_bucket" {
  value = module.data.bucket_names["curated"]
}

output "reports_bucket" {
  value = module.data.bucket_names["reports"]
}

output "logs_bucket" {
  value = module.data.bucket_names["logs"]
}

output "artifacts_bucket" {
  value = module.data.bucket_names["artifacts"]
}

output "neptune_endpoint" {
  description = "https://<cluster-endpoint>:8182 (empty when create_neptune = false)."
  value       = local.neptune_endpoint_url
}

output "neptune_reader_endpoint" {
  value = module.neptune.reader_endpoint
}

output "neptune_bulk_load_role_arn" {
  value = module.neptune.loader_role_arn
}

output "neptune_bulk_load_source_s3_uri" {
  value = "s3://${module.data.bucket_names["curated"]}/graph/neptune-load/"
}

output "dynamodb_table_names" {
  description = "Logical table key to physical name."
  value       = module.data.table_names
}

output "vector_bucket_name" {
  value = module.data.vector_bucket_name
}

output "vector_index_names" {
  value = concat(local.vector_indexes, [var.vector_test_index_name])
}

output "vector_dimension" {
  value = var.vector_dimension
}

output "embedding_queue_url" {
  description = "Supplied to the app by the caller (no core name contract)."
  value       = module.platform.embedding_queue_url
}

output "embedding_dlq_url" {
  value = module.platform.embedding_dlq_url
}

output "ecr_repository_urls" {
  value = module.platform.ecr_repository_urls
}

output "secret_arns" {
  description = "ARNs only. Values are set by a human and never held by Terraform."
  value       = module.platform.secret_arns
}

output "ecs_cluster_name" {
  value = module.compute.cluster_name
}

output "ecs_service_names" {
  value = module.compute.service_names
}

output "query_api_url" {
  description = "ALB DNS name of query-api (empty when create_alb = false). Scheme is https when certificate_arn is set."
  value       = module.compute.alb_dns_name == "" ? "" : "${var.certificate_arn == "" ? "http" : "https"}://${module.compute.alb_dns_name}"
}

output "retrieval_internal_url" {
  value = module.compute.retrieval_internal_url
}

output "pipeline_state_machine_arn" {
  value = module.pipeline.state_machine_arn
}

output "task_role_arns" {
  value = module.iam.task_role_arns
}

output "ecs_execution_role_arns" {
  value = module.iam.ecs_execution_role_arns
}

output "emr_service_role_arn" {
  value = module.iam.emr_service_role_arn
}

output "emr_instance_profile_arn" {
  value = module.iam.emr_instance_profile_arn
}

output "deployer_role_arn" {
  value = module.iam.deployer_role_arn
}

output "vpc_id" {
  value = module.network.vpc_id
}

output "public_subnet_ids" {
  value = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "dashboard_name" {
  value = module.observability.dashboard_name
}

output "alerts_topic_arn" {
  value = module.observability.alerts_topic_arn
}

output "live_suite_environment" {
  description = "Values for the live AWS suites (storage module section 9). SCHOLARCHAIN_IT_RESEARCHER_ID is a data value and is chosen by the human. Neptune is private: the Neptune suite must run from inside the VPC."
  value = {
    AWS_REGION                       = var.aws_region
    SCHOLARCHAIN_IT_S3_BUCKET        = module.data.bucket_names["curated"]
    SCHOLARCHAIN_IT_VECTOR_DIMENSION = tostring(var.vector_dimension)
    SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT = local.neptune_endpoint_url
    SCHOLARCHAIN_IT_VECTOR_INDEX     = var.vector_test_index_name
    # Phase 08 live suites (STORAGE-CONTRACTS 12.7). The suite selects its claims table through the core key
    # embedding.claims.table-name = SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME: set it to the TEST table so the production table is never written.
    SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME      = module.data.table_names["it-embedding-key-claims"]
    SCHOLARCHAIN_EMBEDDING_TEST_VECTOR_INDEX_NAME = var.vector_test_index_name
    SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL           = module.platform.embedding_queue_url
    SCHOLARCHAIN_IT_EMBEDDING_DLQ_URL             = module.platform.embedding_dlq_url
  }
}
