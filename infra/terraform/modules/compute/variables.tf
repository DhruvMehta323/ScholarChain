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

variable "service_env" {
  description = "Per-service extra environment variables (service name to map). Values only, never secrets. Applied after common_env and the built-in per-service variables."
  type        = map(map(string))
  default     = {}
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

variable "ops_task_role_arn" {
  description = "Task role of the ops task definition (least privilege, iam module)."
  type        = string
}

variable "ops_main_class" {
  description = "SERVICE_MAIN of the ops task (the entrypoint runs this class)."
  type        = string
  default     = "scholarchain.services.OpsLoadMain"
}

variable "ops_java_opts" {
  description = "JAVA_OPTS of the ops task (appended by entrypoint.sh, so it can override the 512 MiB defaults). 1 GiB task: about 700 MiB heap."
  type        = string
  default     = "-XX:MaxRAMPercentage=75.0"
}

variable "ops_cpu" {
  description = "Fargate vCPU units for the ops task. 1024 pairs with 4096 MiB (valid Fargate pair)."
  type        = number
  default     = 1024

  validation {
    condition     = contains([256, 512, 1024, 2048, 4096], var.ops_cpu)
    error_message = "ops_cpu must be a valid Fargate CPU value."
  }
}

variable "ops_memory" {
  description = "MiB. 4096 gives OpsLoadMain heap room for rendering about 87K summary documents (estimated peak 600-700 MiB vs about 750 MiB heap at 1 GiB); pairs with 1024 CPU."
  type        = number
  default     = 4096

  validation {
    condition     = var.ops_memory >= 1024 && var.ops_memory <= 8192
    error_message = "ops_memory must be between 1024 and 8192 MiB (valid for 1024 CPU Fargate)."
  }
}
