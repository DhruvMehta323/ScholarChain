# ScholarChain course environment. Authored by infra-engineer; applied only by a human.
# Wiring only: every resource lives in modules/. No secret values anywhere.

provider "aws" {
  region = var.aws_region

  # Terraform refuses to plan or apply against any other account.
  allowed_account_ids = [var.expected_account_id]

  default_tags {
    tags = local.tags
  }
}

data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  account_id = data.aws_caller_identity.current.account_id
  name       = var.name_prefix

  # Required tags on everything: course, project, environment, owner, expiration date.
  tags = {
    course            = var.course
    project           = var.project
    environment       = var.environment
    owner             = var.owner
    "expiration-date" = var.expiration_date
  }

  service_names = ["query-api", "retrieval", "embedding-worker"]

  # Secrets each service may read (execution role scoped to exactly these ARNs) and receives as environment variables.
  service_secrets = {
    # MERGE-2: SCHOLARCHAIN_ANTHROPIC_API_KEY and SCHOLARCHAIN_VOYAGE_API_KEY are no longer injected (Bedrock only; the Voyage
    # embedder is built only when its variable is present, so absence selects Bedrock Titan). The two secret resources stay, empty and unused.
    "query-api"        = ["services-api-key", "internal-api-key"]
    "retrieval"        = ["services-api-key", "internal-api-key"]
    "embedding-worker" = []
  }

  # MERGE-1: generation model, Bedrock only. Env names follow SCHOLARCHAIN_RAG_<KEY> for core keys rag.model.provider and
  # rag.model.bedrock-model-id. Only query-api builds a model client (Composition.scala awsQueryApi); retrieval and
  # embedding-worker do not, so they do not get these variables.
  service_env = {
    "query-api" = {
      SCHOLARCHAIN_RAG_MODEL_PROVIDER         = "bedrock"
      SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID = var.generation_inference_profile_id
    }
  }

  # IAM resources for query-api generation: the account-scoped inference profile plus the Haiku 4.5 foundation model in every Region
  # the profile routes to (inference-profiles-prereq). Titan is granted separately to retrieval and embedding-worker by the iam module.
  generation_model_arns = concat(
    ["arn:aws:bedrock:${var.aws_region}:${local.account_id}:inference-profile/${var.generation_inference_profile_id}"],
    [for r in var.generation_profile_regions : "arn:aws:bedrock:${r}::foundation-model/${var.generation_foundation_model_id}"],
    var.bedrock_model_arns,
  )

  bucket_names = {
    for role in ["raw", "curated", "reports", "logs", "artifacts"] :
    role => "${local.name}-${var.environment}-${local.account_id}-${role}"
  }

  vector_bucket_name = "${local.name}-${var.environment}-vectors"
  vector_indexes = [
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1",
  ]

  operational_table_keys = ["RunLedger", "ArtifactRegistry", "QuerySession", "EntityLinkCache", "EvidenceBundle"]
  analytics_table_keys = [
    "analytics-researcher-metrics", "analytics-coauthor-edge-works", "analytics-bridges", "analytics-coreness",
    "analytics-temporal-changes", "analytics-paths", "analytics-path-estimates", "analytics-window-summary",
    "analytics-identity-clusters", "analytics-mention-researcher",
  ]

  state_machine_name = "${local.name}-pipeline"
  namespace          = "${local.name}.local"

  neptune_endpoint_url = module.neptune.enabled ? "https://${module.neptune.cluster_endpoint}:${module.neptune.port}" : ""

  # Environment variables of the applications (docs: INFRA-CONTRACTS.md). Values only; no secrets.
  app_environment = merge({
    SCHOLARCHAIN_ENVIRONMENT                             = var.environment
    SCHOLARCHAIN_AWS_REGION                              = var.aws_region
    AWS_REGION                                           = var.aws_region
    SCHOLARCHAIN_STORAGE_RAW_BUCKET                      = module.data.bucket_names["raw"]
    SCHOLARCHAIN_STORAGE_CURATED_BUCKET                  = module.data.bucket_names["curated"]
    SCHOLARCHAIN_STORAGE_VECTOR_BUCKET                   = module.data.vector_bucket_name
    SCHOLARCHAIN_STORAGE_VECTOR_INDEX                    = var.default_vector_index
    SCHOLARCHAIN_STORAGE_DYNAMO_TABLE_PREFIX             = var.dynamodb_table_prefix
    SCHOLARCHAIN_EMBEDDING_MODEL_DIMENSION               = tostring(var.vector_dimension)
    SCHOLARCHAIN_EMBEDDING_MODEL_ID                      = var.embedding_model_id
    SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME             = module.data.table_names["embedding-key-claims"]
    SCHOLARCHAIN_MODELS_EMBEDDING_MODEL_ID               = var.embedding_model_id
    SCHOLARCHAIN_STORAGE_VECTORS_BUCKET_NAME             = module.data.vector_bucket_name
    SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_FORMAT        = "opencypher"
    SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_SOURCE_S3_URI = "s3://${module.data.bucket_names["curated"]}/graph/neptune-load/"
    }, var.create_neptune ? {
    # M7: only when Neptune exists. Never set to an empty string: core treats a blank variable as unset.
    SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT           = local.neptune_endpoint_url
    SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_ROLE_ARN = module.neptune.loader_role_arn
  } : {})
}

module "network" {
  source            = "../../modules/network"
  name              = local.name
  vpc_cidr          = var.vpc_cidr
  azs               = slice(data.aws_availability_zones.available.names, 0, 2)
  alb_ingress_cidrs = var.alb_ingress_cidrs
  alb_https_enabled = var.certificate_arn != ""
  neptune_enabled   = var.create_neptune
}

module "data" {
  source                       = "../../modules/data"
  bucket_names                 = local.bucket_names
  dynamodb_table_prefix        = var.dynamodb_table_prefix
  dynamodb_pitr                = var.dynamodb_pitr
  dynamodb_deletion_protection = var.dynamodb_deletion_protection
  vector_bucket_name           = local.vector_bucket_name
  vector_dimension             = var.vector_dimension
  vector_indexes               = local.vector_indexes
  vector_test_index_name       = var.vector_test_index_name
  vector_force_destroy         = var.vector_force_destroy
  force_destroy_intermediate   = var.force_destroy_intermediate
}

module "neptune" {
  source              = "../../modules/neptune"
  enabled             = var.create_neptune
  name                = local.name
  private_subnet_ids  = module.network.private_subnet_ids
  security_group_id   = module.network.security_group_ids["neptune"]
  curated_bucket_arn  = module.data.bucket_arns["curated"]
  engine_version      = var.neptune_engine_version
  min_capacity        = var.neptune_min_ncu
  max_capacity        = var.neptune_max_ncu
  deletion_protection = var.neptune_deletion_protection
  skip_final_snapshot = var.neptune_skip_final_snapshot
}

module "platform" {
  source        = "../../modules/platform"
  name          = local.name
  service_names = local.service_names
  secret_names  = { for k, v in var.secrets : k => v.name }
  queue_name    = var.embedding_queue_name
  dlq_name      = var.embedding_dlq_name
  # max-attempts + 1 (= 4). STORAGE-CONTRACTS 12.3: the worker dead-letters itself, with a dead_lettered envelope, when
  # receiveCount > max-attempts (redelivery-bound-exceeded), i.e. on the 4th receive. AWS: maxReceiveCount is the number of times a consumer
  # can receive a message before it is moved (sqs-dead-letter-queues.html), so a redrive value of 3 would move the message instead of
  # delivering it a 4th time and the worker would never write its envelope. 4 lets the 4th receive reach the worker; redrive is only the safety net.
  max_receive_count = var.embedding_max_attempts + 1

  ecr_force_delete = var.force_destroy_intermediate
}

module "logs" {
  source      = "../../modules/logs"
  name        = local.name
  group_names = concat(local.service_names, ["sfn-pipeline"])
}

module "iam" {
  source                      = "../../modules/iam"
  name                        = local.name
  account_id                  = local.account_id
  region                      = var.aws_region
  bucket_arns                 = module.data.bucket_arns
  table_arns                  = module.data.table_arns
  operational_table_keys      = local.operational_table_keys
  analytics_table_keys        = local.analytics_table_keys
  vector_index_arns           = module.data.vector_index_arns
  embedding_queue_arn         = module.platform.embedding_queue_arn
  embedding_dlq_arn           = module.platform.embedding_dlq_arn
  secret_arns                 = module.platform.secret_arns
  service_secrets             = local.service_secrets
  ecr_repository_arns         = module.platform.ecr_repository_arns
  log_group_arns              = module.logs.log_group_arns
  neptune_enabled             = var.create_neptune
  neptune_cluster_resource_id = module.neptune.cluster_resource_id
  bedrock_model_arns          = local.generation_model_arns
  embedding_provider          = var.embedding_provider
  embedding_model_id          = var.embedding_model_id
  deployment_principal_arns   = var.deployment_principal_arns
  cluster_name                = "${local.name}-cluster"
  service_names               = local.service_names
  state_machine_name          = local.state_machine_name
}

module "compute" {
  source                      = "../../modules/compute"
  name                        = local.name
  region                      = var.aws_region
  vpc_id                      = module.network.vpc_id
  public_subnet_ids           = module.network.public_subnet_ids
  security_group_ids          = module.network.security_group_ids
  ecr_repository_urls         = module.platform.ecr_repository_urls
  image_tag                   = var.image_tag
  cpu_architecture            = var.ecs_cpu_architecture
  execution_role_arns         = module.iam.ecs_execution_role_arns
  task_role_arns              = module.iam.task_role_arns
  ops_task_role_arn           = module.iam.ops_task_role_arn
  log_group_names             = module.logs.log_group_names
  common_env                  = local.app_environment
  embedding_queue_url         = module.platform.embedding_queue_url
  embedding_queue_name        = module.platform.embedding_queue_name
  embedding_dlq_url           = module.platform.embedding_dlq_url
  embedding_dlq_name          = module.platform.embedding_dlq_name
  embedding_max_attempts      = var.embedding_max_attempts
  secret_arns                 = module.platform.secret_arns
  service_secrets             = local.service_secrets
  service_env                 = local.service_env
  secret_env_names            = { for k, v in var.secrets : k => v.env }
  min_tasks                   = var.ecs_min_tasks
  max_tasks                   = var.ecs_max_tasks
  create_alb                  = var.create_alb
  alb_deletion_protection     = var.alb_deletion_protection
  certificate_arn             = var.certificate_arn
  enable_container_insights   = var.enable_container_insights
  service_discovery_namespace = local.namespace
}

module "pipeline" {
  source                = "../../modules/pipeline"
  name                  = local.name
  state_machine_name    = local.state_machine_name
  sfn_role_arn          = module.iam.sfn_role_arn
  log_group_arn         = module.logs.log_group_arns["sfn-pipeline"]
  release_label         = var.emr_hadoop_fallback ? "emr-7.14.0" : var.emr_release_label
  applications          = var.emr_hadoop_fallback ? ["Hadoop", "Spark"] : ["Spark"]
  instance_type         = var.emr_instance_type
  core_instance_count   = var.emr_core_instance_count
  max_capacity_units    = var.emr_max_capacity_units
  idle_timeout_seconds  = var.emr_idle_timeout_seconds
  service_role_name     = basename(module.iam.emr_service_role_arn)
  instance_profile_name = module.iam.emr_instance_profile_name
  logs_bucket_name      = module.data.bucket_names["logs"]
  subnet_id             = module.network.public_subnet_ids[0]
  security_group_id     = module.network.security_group_ids["emr"]
  tags                  = local.tags
}

module "observability" {
  source                      = "../../modules/observability"
  name                        = local.name
  region                      = var.aws_region
  account_id                  = local.account_id
  alert_email                 = var.alert_email
  budget_email                = var.alert_email
  budget_limit_usd            = var.budget_limit_usd
  budget_alert_thresholds_usd = var.budget_alert_thresholds_usd
  cluster_name                = module.compute.cluster_name
  service_names               = module.compute.service_names
  queue_name                  = module.platform.embedding_queue_name
  dlq_name                    = module.platform.embedding_dlq_name
  dynamodb_runtime_tables = {
    "QuerySession"       = module.data.table_names["QuerySession"]
    "EvidenceBundle"     = module.data.table_names["EvidenceBundle"]
    "researcher-metrics" = module.data.table_names["analytics-researcher-metrics"]
  }
  alb_enabled                = var.create_alb
  container_insights_enabled = var.enable_container_insights
  alb_arn_suffix             = module.compute.alb_arn_suffix
  target_group_arn_suffix    = module.compute.target_group_arn_suffix
  neptune_enabled            = var.create_neptune
  neptune_cluster_identifier = var.create_neptune ? "${local.name}-neptune" : ""
}
