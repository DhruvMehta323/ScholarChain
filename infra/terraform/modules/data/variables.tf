variable "bucket_names" {
  description = "Physical S3 bucket names by role: raw, curated, reports, logs, artifacts."
  type        = map(string)
}

variable "dynamodb_table_prefix" {
  description = "Prefix of every DynamoDB physical name. The default 'scholarchain' keeps the defaults of scholarchain-storage.v1.conf valid."
  type        = string
}

variable "dynamodb_pitr" {
  description = "Point-in-time recovery for DynamoDB. Off for the disposable course environment (tables are rebuilt from S3)."
  type        = bool
  default     = false
}

variable "dynamodb_deletion_protection" {
  type    = bool
  default = true
}

variable "vector_bucket_name" {
  type = string
}

variable "vector_dimension" {
  description = "Dimension of every vector index. Must equal the output size of the embedding model."
  type        = number
}

variable "vector_indexes" {
  description = "S3 Vectors index names (core contract VectorIndexes)."
  type        = list(string)
}

variable "vector_test_index_name" {
  type = string
}

variable "vector_force_destroy" {
  type    = bool
  default = false
}

variable "force_destroy_intermediate" {
  description = "Allow Terraform to delete non-empty curated, logs and artifacts buckets. Never applies to raw and reports (evidence). Default false; the human flips it for cleanup."
  type        = bool
  default     = false
}
