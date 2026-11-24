variable "name" {
  type = string
}

variable "account_id" {
  type = string
}

variable "region" {
  type = string
}

variable "bucket_arns" {
  description = "Bucket role (raw, curated, reports, logs, artifacts) to ARN."
  type        = map(string)
}

variable "table_arns" {
  description = "Logical table key to ARN (operational and analytics tables)."
  type        = map(string)
}

variable "operational_table_keys" {
  type = list(string)
}

variable "analytics_table_keys" {
  type = list(string)
}

variable "vector_index_arns" {
  description = "Index name to ARN, including the test index."
  type        = map(string)
}

variable "embedding_queue_arn" {
  type = string
}

variable "embedding_dlq_arn" {
  type = string
}

variable "service_secrets" {
  description = "Service name to the internal keys of the secrets it injects."
  type        = map(list(string))
}

variable "secret_arns" {
  description = "Secret short name to ARN. Values are never known to Terraform."
  type        = map(string)
}

variable "ecr_repository_arns" {
  type = map(string)
}

variable "log_group_arns" {
  description = "Log group short name to ARN."
  type        = map(string)
}

variable "neptune_enabled" {
  description = "Whether Neptune exists. A plan-time boolean (the resource id itself is unknown until apply)."
  type        = bool
}

variable "neptune_cluster_resource_id" {
  description = "Resource id used in neptune-db ARNs. Only read when neptune_enabled is true."
  type        = string
  default     = ""
}

variable "embedding_provider" {
  description = "bedrock: the embedding-worker and retrieval roles may invoke exactly the embedding model. voyage: no Bedrock embedding grant."
  type        = string
}

variable "embedding_model_id" {
  type = string
}

variable "bedrock_model_arns" {
  description = "GENERATION model ARNs for query-api only (never used for embeddings). Empty list: query-api has no Bedrock permission."
  type        = list(string)
  default     = []
}

variable "deployment_principal_arns" {
  description = "IAM principals allowed to assume the deployment role. Empty list: the deployment role is not created."
  type        = list(string)
  default     = []
}

variable "cluster_name" {
  type = string
}

variable "service_names" {
  type = list(string)
}

variable "state_machine_name" {
  type = string
}
