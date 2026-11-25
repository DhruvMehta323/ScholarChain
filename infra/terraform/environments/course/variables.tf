############################ Identity and tagging (no secrets in any variable) ############################

variable "expected_account_id" {
  description = "REQUIRED, no default. The 12-digit AWS account this environment may be applied to. Used as provider allowed_account_ids (Terraform refuses any other account) and compared by scripts/plan.sh with the STS caller account before planning. An account ID is not a secret, but keep the real value in the gitignored terraform.tfvars."
  type        = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.expected_account_id))
    error_message = "expected_account_id must be exactly 12 digits."
  }
}

variable "aws_region" {
  description = "AWS Region. plan.sh refuses to plan unless the caller's effective region equals this value."
  type        = string
  default     = "us-east-2"
}

variable "environment" {
  type    = string
  default = "course"
}

variable "course" {
  type    = string
  default = "CS441"
}

variable "project" {
  type    = string
  default = "ScholarChain"
}

variable "owner" {
  description = "Owner tag value (a person or team name, not an email secret)."
  type        = string
}

variable "expiration_date" {
  description = "Expiration date tag, YYYY-MM-DD. The environment is meant to be destroyed by then."
  type        = string
  validation {
    condition     = can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", var.expiration_date))
    error_message = "expiration_date must look like 2026-12-20."
  }
}

variable "name_prefix" {
  description = "Prefix of resource names."
  type        = string
  default     = "scholarchain"
}

variable "alert_email" {
  description = "Email that receives AWS Budgets alerts and CloudWatch alarm notifications. Supply through a gitignored tfvars file or TF_VAR_alert_email."
  type        = string
}

############################ Network ############################

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "alb_ingress_cidrs" {
  description = "REQUIRED, no default. CIDRs allowed to reach the load balancer listener (use your own /32). IPv4 only, prefix /16 or narrower; 0.0.0.0/0 and ::/0 are rejected."
  type        = list(string)
  validation {
    condition = length(var.alb_ingress_cidrs) > 0 && alltrue([
      for c in var.alb_ingress_cidrs : can(cidrnetmask(c)) && !contains(["0.0.0.0/0", "::/0"], c) && tonumber(split("/", c)[1]) >= 16
    ])
    error_message = "alb_ingress_cidrs must be a non-empty list of valid IPv4 CIDRs with prefix /16 or narrower (no 0.0.0.0/0, no ::/0, no IPv6)."
  }
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS. Empty: HTTP listener only."
  type        = string
  default     = ""
}

############################ Application contract names ############################

variable "dynamodb_table_prefix" {
  description = "Keep 'scholarchain' so the defaults of scholarchain-storage.v1.conf match the physical table names. If changed, set the SCHOLARCHAIN_STORAGE_DYNAMODB_* overrides listed in INFRA-CONTRACTS.md."
  type        = string
  default     = "scholarchain"
}

variable "vector_dimension" {
  description = "Dimension of all S3 Vectors indexes. Default 1024: the default output size of Amazon Titan Text Embeddings V2 and of Voyage voyage-3 / voyage-3.5 models, so either provider fits without recreating the indexes (index dimension is immutable)."
  type        = number
  default     = 1024
  validation {
    condition     = var.vector_dimension >= 1 && var.vector_dimension <= 4096
    error_message = "S3 Vectors supports dimensions 1 to 4096."
  }
}

variable "vector_test_index_name" {
  type    = string
  default = "scholarchain-it-embedding-test"
}

variable "embedding_queue_name" {
  description = "Accepted, main 765f7ce: embedding.queue.name in scholarchain-embedding.v1.conf."
  type        = string
  default     = "scholarchain-embedding-jobs"
}

variable "embedding_dlq_name" {
  description = "Accepted, main 765f7ce: embedding.queue.dead-letter-name."
  type        = string
  default     = "scholarchain-embedding-jobs-dlq"
}

variable "embedding_max_attempts" {
  description = "Accepted, main 765f7ce: embedding.queue.max-attempts. The SQS maxReceiveCount is this value + 1 (STORAGE-CONTRACTS 12.3)."
  type        = number
  default     = 3
}

variable "default_vector_index" {
  description = "Value of SCHOLARCHAIN_STORAGE_VECTOR_INDEX (core key storage.vector-index)."
  type        = string
  default     = "paper-abstract-v1"
}

variable "embedding_model_id" {
  description = "SCHOLARCHAIN_MODELS_EMBEDDING_MODEL_ID. Bedrock ID, or the Voyage model name when the instructor-approved fallback is used."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

variable "generation_inference_profile_id" {
  description = "Bedrock inference profile ID used as SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID (query-api only). Claude Haiku 4.5 through the US cross-region profile (human decision, Phase 11 MERGE-1)."
  type        = string
  default     = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "generation_foundation_model_id" {
  description = "Foundation model the inference profile routes to; one foundation-model ARN is granted per Region in generation_profile_regions."
  type        = string
  default     = "anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "generation_profile_regions" {
  description = "Regions the US cross-region profile routes to (us-east-1, us-east-2, us-west-2): confirmed by the human with aws bedrock get-inference-profile, 2026-11-24. A missing Region causes AccessDenied on some invocations; an extra Region only widens an invoke grant on the same single model."
  type        = list(string)
  default     = ["us-east-1", "us-east-2", "us-west-2"]
}

variable "embedding_provider" {
  description = "bedrock (default): embedding-worker and retrieval may invoke exactly the model embedding_model_id. voyage: no Bedrock embedding grant. Since MERGE-2 no task definition injects a Voyage or Anthropic key, so voyage only removes the Bedrock grant; the application then has no embedder unless a key is supplied by other means."
  type        = string
  default     = "bedrock"
  validation {
    condition     = contains(["bedrock", "voyage"], var.embedding_provider)
    error_message = "embedding_provider must be bedrock or voyage."
  }
}

variable "bedrock_model_arns" {
  description = "EXTRA Bedrock generation model or inference-profile ARNs, added to the Haiku 4.5 profile and foundation-model ARNs built from the generation_* variables (query-api only). Titan is not listed here: retrieval and embedding-worker get it from embedding_model_id. Default empty."
  type        = list(string)
  default     = []
}

variable "secrets" {
  description = "Empty Secrets Manager entries (no version; a human sets each value). Key = internal Terraform key, name = Secrets Manager name, env = environment variable the ECS task receives."
  type        = map(object({ name = string, env = string }))
  default = {
    voyage-api-key    = { name = "scholarchain/voyage-api-key", env = "SCHOLARCHAIN_VOYAGE_API_KEY" }
    anthropic-api-key = { name = "SCHOLARCHAIN_ANTHROPIC_API_KEY", env = "SCHOLARCHAIN_ANTHROPIC_API_KEY" }
    services-api-key  = { name = "SCHOLARCHAIN_SERVICES_API_KEY", env = "SCHOLARCHAIN_SERVICES_API_KEY" }
    internal-api-key  = { name = "SCHOLARCHAIN_INTERNAL_API_KEY", env = "SCHOLARCHAIN_INTERNAL_API_KEY" }
  }
}

############################ Neptune ############################

variable "create_neptune" {
  description = "Default false (cost cut). true creates Neptune for an experiment window; with false there is no Neptune cost and SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT is not set (omitted, never blank), so apps that resolve core configuration cannot start (INFRA-CONTRACTS.md open point O1)."
  type        = bool
  default     = false
}

variable "neptune_engine_version" {
  description = "Neptune engine version. 1.5.0.1 (released 2026-11-12) is the newest on https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases.html (fetched 2026-11-24). Neptune Serverless (db.serverless) on 1.5.0.1 in us-east-2 is VERIFIED by the human's read-only describe-orderable-db-instance-options check (2026-11-24); the release page itself does not mention Serverless."
  type        = string
  default     = "1.5.0.1"
}

variable "neptune_min_ncu" {
  type    = number
  default = 1
}

variable "neptune_max_ncu" {
  type    = number
  default = 2.5
}

variable "neptune_deletion_protection" {
  description = "Default true. The human sets false (and applies) before cleanup."
  type        = bool
  default     = true
}

variable "neptune_skip_final_snapshot" {
  description = "Default true: the graph is rebuilt deterministically from S3. Set false to keep a final snapshot (costs storage until deleted)."
  type        = bool
  default     = true
}

############################ DynamoDB, S3 Vectors ############################

variable "dynamodb_pitr" {
  type    = bool
  default = false
}

variable "dynamodb_deletion_protection" {
  description = "Default true. The human sets false (and applies) before cleanup."
  type        = bool
  default     = true
}

variable "force_destroy_intermediate" {
  description = "Cleanup switch: lets Terraform delete non-empty curated/logs/artifacts buckets and ECR repositories with images. Never affects the raw and reports (evidence) buckets. Default false."
  type        = bool
  default     = false
}

variable "vector_force_destroy" {
  type    = bool
  default = false
}

############################ EMR pipeline ############################

variable "emr_release_label" {
  # allowed values enforced in the pipeline module: emr-spark-8.1.0 or emr-7.14.0
  description = "EMR release label, human-decided default emr-spark-8.1.0 (Spark 4.1.1-amzn-0, Hadoop 3.4.2-amzn-3, Scala 2.13.17, Java 17; verified on https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html, 2026-11-23). Custom MapReduce JAR support is still to be confirmed by the Phase 14 smoke job."
  type        = string
  default     = "emr-spark-8.1.0"
}

variable "emr_hadoop_fallback" {
  description = "Default false. true switches the pipeline cluster to emr-7.14.0 with the Hadoop and Spark applications, ONLY if the Phase 14 MapReduce smoke job fails on emr-spark-8.1.0. It changes the RunJobFlow parameters inside the existing state machine and nothing else (no new resource, IAM or network change). Decide after the smoke job; see INFRA-CONTRACTS.md section 6.2."
  type        = bool
  default     = false
}

variable "emr_instance_type" {
  type    = string
  default = "m5.xlarge"
}

variable "emr_core_instance_count" {
  description = "Initial core nodes. The cluster has one primary plus core/task nodes; the total can never exceed 3 (see emr_max_capacity_units)."
  type        = number
  default     = 1
  validation {
    condition     = var.emr_core_instance_count >= 1 && var.emr_core_instance_count <= 2
    error_message = "emr_core_instance_count must be 1 or 2 (primary + at most 2 core/task = 3 nodes)."
  }
}

variable "emr_max_capacity_units" {
  description = "Managed scaling maximum for core + task nodes only (AWS: the limit applies to core and task nodes, never the primary: https://docs.aws.amazon.com/emr/latest/APIReference/API_ComputeLimits.html). 2 + the primary = 3 nodes total, the guard limit."
  type        = number
  default     = 2
  validation {
    condition     = var.emr_max_capacity_units >= 1 && var.emr_max_capacity_units <= 2 && var.emr_max_capacity_units >= var.emr_core_instance_count
    error_message = "emr_max_capacity_units must be 1 or 2 and at least emr_core_instance_count, so the cluster never exceeds 3 nodes (1 primary + 2)."
  }
}

variable "emr_idle_timeout_seconds" {
  description = "EMR auto-termination idle timeout. 900 s. Idle is judged only when no YARN application and no pending step exist, so it does NOT end a hung run with an active step."
  type        = number
  default     = 900
}

############################ ECS services ############################

variable "image_tag" {
  description = "Immutable image tag for all services. 'bootstrap' is a placeholder until images exist."
  type        = string
  default     = "bootstrap"
}

variable "ecs_cpu_architecture" {
  type    = string
  default = "X86_64"
}

variable "ecs_min_tasks" {
  description = "Autoscaling minimum per service (the way to scale up for an experiment). 0 = nothing is kept running. Default 0 for all three services: they cost money per task-hour and nothing needs to run outside experiment windows. To stop everything after a scale-up set min AND ecs_max_tasks to 0 (INFRA-CONTRACTS.md section 9b)."
  type        = map(number)
  default     = { "query-api" = 0, "retrieval" = 0, "embedding-worker" = 0 }
  validation {
    condition     = alltrue([for v in values(var.ecs_min_tasks) : v >= 0])
    error_message = "ecs_min_tasks values must not be negative."
  }
}

variable "ecs_max_tasks" {
  description = "Autoscaling maximum per service."
  type        = map(number)
  default     = { "query-api" = 4, "retrieval" = 4, "embedding-worker" = 4 }
  validation {
    condition     = alltrue([for v in values(var.ecs_max_tasks) : v >= 0 && v <= 4])
    error_message = "ecs_max_tasks values must be between 0 and 4. 0 is the scale-down setting (see INFRA-CONTRACTS.md section 9b)."
  }
  validation {
    condition     = alltrue([for k, v in var.ecs_max_tasks : v >= lookup(var.ecs_min_tasks, k, 0)])
    error_message = "ecs_max_tasks must be at least ecs_min_tasks for every service."
  }
}

variable "create_alb" {
  description = "Default false (cost cut). true creates the load balancer for an experiment window; query_api_url is empty while false."
  type        = bool
  default     = false
}

variable "alb_deletion_protection" {
  type    = bool
  default = false
}

variable "enable_container_insights" {
  description = "Default false (cost): Container Insights bills its metrics. Without it the dashboard shows ECS CPU/memory per service (AWS/ECS) instead of desired/running task counts, which exist only with Container Insights. Autoscaling and alarms do not use Container Insights metrics."
  type        = bool
  default     = false
}

############################ Deployment role, budget ############################

variable "deployment_principal_arns" {
  description = "IAM principals (for example the human's role or user ARN) allowed to assume the deployment role. Empty: the deployment role is not created."
  type        = list(string)
  default     = []
}

variable "budget_limit_usd" {
  description = "Monthly budget limit. 25 equals the whole-project ceiling (AWS and all external APIs); the AWS-only target is about 15, so the alerts below warn early. Never above 25."
  type        = number
  default     = 25
  validation {
    condition     = var.budget_limit_usd > 0 && var.budget_limit_usd <= 25
    error_message = "budget_limit_usd must be between 0 and 25 (the project ceiling)."
  }
}

variable "budget_alert_thresholds_usd" {
  description = "Absolute actual-spend thresholds (credits excluded from the measured cost)."
  type        = list(number)
  default     = [5, 10, 15]
  validation {
    condition     = alltrue([for t in var.budget_alert_thresholds_usd : t > 0 && t < var.budget_limit_usd])
    error_message = "Every threshold must be above 0 and below budget_limit_usd."
  }
}
