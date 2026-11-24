variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "security_group_ids" {
  description = "Map with keys alb, query_api, retrieval, embedding_worker."
  type        = map(string)
}

variable "ecr_repository_urls" {
  type = map(string)
}

variable "image_tag" {
  description = "Immutable image tag deployed to every service. 'bootstrap' is a placeholder until Phase 14 pushes images."
  type        = string
}

variable "cpu_architecture" {
  type    = string
  default = "X86_64"
  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}

variable "task_cpu" {
  description = "Fargate CPU units per task. 256 is the smallest size."
  type        = number
  default     = 256
}

variable "task_memory" {
  description = "Fargate memory (MiB) per task. 512 is the smallest size for 256 CPU units."
  type        = number
  default     = 512
}

variable "execution_role_arns" {
  description = "Service name to its own ECS execution role ARN."
  type        = map(string)
}

variable "service_secrets" {
  type = map(list(string))
}

variable "secret_env_names" {
  description = "Internal secret key to the environment variable name injected into the task."
  type        = map(string)
}

variable "task_role_arns" {
  type = map(string)
}

variable "log_group_names" {
  type = map(string)
}

variable "common_env" {
  description = "Environment variables for every service (SCHOLARCHAIN_* contract names)."
  type        = map(string)
}

variable "embedding_queue_url" {
  type = string
}

variable "embedding_dlq_url" {
  type = string
}

variable "embedding_dlq_name" {
  type = string
}

variable "embedding_max_attempts" {
  type = number
}

variable "embedding_queue_name" {
  type = string
}

variable "secret_arns" {
  description = "Secret short name to ARN. Injected by ECS from Secrets Manager; Terraform never sees a value."
  type        = map(string)
}

variable "service_port" {
  type    = number
  default = 8080
}

variable "health_check_path" {
  description = "ALB health check path of query-api: liveness (/healthz), so a missing dependency does not make the ALB cycle tasks. /readyz is readiness and /health is an alias of /healthz (human-stated Phase 10 contract, NOT VERIFIED against code)."
  type        = string
  default     = "/healthz"
}

variable "min_tasks" {
  description = "Per-service autoscaling minimum. 0 by default: nothing runs until the human raises it and applies."
  type        = map(number)
}

variable "max_tasks" {
  description = "Per-service autoscaling maximum (bounded, default 4)."
  type        = map(number)
}

variable "cpu_target_percent" {
  type    = number
  default = 60
}

variable "queue_backlog_target" {
  description = "Target visible messages per desired worker capacity for the embedding-worker scaling policy."
  type        = number
  default     = 100
}

variable "create_alb" {
  type    = bool
  default = true
}

variable "alb_deletion_protection" {
  type    = bool
  default = false
}

variable "certificate_arn" {
  description = "ACM certificate ARN. Empty: HTTP listener only. Set: HTTPS listener, HTTP redirects to HTTPS."
  type        = string
  default     = ""
}

variable "enable_container_insights" {
  type    = bool
  default = true
}

variable "service_discovery_namespace" {
  description = "Private DNS namespace for internal service calls (retrieval)."
  type        = string
}
