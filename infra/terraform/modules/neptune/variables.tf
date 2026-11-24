variable "enabled" {
  description = "Create the Neptune cluster (false = no Neptune resources, no Neptune cost)."
  type        = bool
  default     = true
}

variable "name" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "curated_bucket_arn" {
  type = string
}

variable "load_prefix" {
  description = "S3 prefix the bulk loader may read (graph/neptune-load/)."
  type        = string
  default     = "graph/neptune-load/"
}

variable "engine_version" {
  type = string
}

variable "min_capacity" {
  description = "Minimum NCUs (Neptune Serverless minimum is 1)."
  type        = number
  default     = 1
}

variable "max_capacity" {
  type    = number
  default = 2.5
}

variable "deletion_protection" {
  type    = bool
  default = true
}

variable "skip_final_snapshot" {
  type    = bool
  default = true
}
