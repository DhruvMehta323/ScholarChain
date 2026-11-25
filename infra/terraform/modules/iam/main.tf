# Least-privilege roles, each scoped to named resources. Every "*" resource below carries a justification comment.
# Runtime roles (task roles, EMR instance profile, Step Functions role) are separate from the deployment role.

locals {
  ops_table_arns       = [for k in var.operational_table_keys : var.table_arns[k]]
  analytics_table_arns = [for k in var.analytics_table_keys : var.table_arns[k]]
  # claims table excluded on purpose: only the embedding-worker may touch it (PutItem only)
  all_table_arns    = concat(local.ops_table_arns, local.analytics_table_arns)
  index_arns        = values(var.vector_index_arns)
  neptune_enabled   = var.neptune_enabled
  neptune_db_arn    = "arn:aws:neptune-db:${var.region}:${var.account_id}:${var.neptune_cluster_resource_id}/*"
  bedrock_enabled   = length(var.bedrock_model_arns) > 0
  embedding_bedrock = var.embedding_provider == "bedrock"
  # Exactly one model, built from the configured model id (foundation-model ARNs carry no account id). No wildcard model.
  embedding_model_arn = "arn:aws:bedrock:${var.region}::foundation-model/${var.embedding_model_id}"

  ecs_trust_account = {
    test     = "StringEquals"
    variable = "aws:SourceAccount"
    values   = [var.account_id]
  }
}

############################ shared trust documents ############################

data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = local.ecs_trust_account.test
      variable = local.ecs_trust_account.variable
      values   = local.ecs_trust_account.values
    }
  }
}

############################ ECS execution role ############################

# One execution role per service: each may read only the exact secret ARNs that service injects.
data "aws_iam_policy_document" "ecs_execution" {
  for_each = toset(var.service_names)
  statement {
    sid       = "EcrAuthToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # ecr:GetAuthorizationToken: AWS has no resource-level permission for this action
  }
  statement {
    sid       = "EcrPull"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = [var.ecr_repository_arns[each.value]]
  }
  statement {
    sid       = "TaskLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${var.log_group_arns[each.value]}:*"]
  }
  # MERGE-2: embedding-worker injects no secret, so it gets no statement (an empty resource list is invalid IAM).
  dynamic "statement" {
    for_each = length(var.service_secrets[each.value]) > 0 ? [1] : []
    content {
      sid       = "ReadInjectedSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = [for k in var.service_secrets[each.value] : var.secret_arns[k]]
    }
  }
}

resource "aws_iam_role" "ecs_execution" {
  for_each           = toset(var.service_names)
  name               = "${var.name}-ecs-execution-${each.value}"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy" "ecs_execution" {
  for_each = toset(var.service_names)
  name     = "execution"
  role     = aws_iam_role.ecs_execution[each.value].id
  policy   = data.aws_iam_policy_document.ecs_execution[each.value].json
}

############################ ECS task roles (one per service) ############################

# query-api: reads graph, vectors, tables, curated/rag objects; writes sessions and evidence bundles.
data "aws_iam_policy_document" "query_api" {
  statement {
    sid       = "TablesRead"
    actions   = ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:DescribeTable"]
    resources = local.all_table_arns
  }
  statement {
    sid       = "SessionAndEvidenceWrite"
    actions   = ["dynamodb:PutItem", "dynamodb:UpdateItem"]
    resources = [var.table_arns["QuerySession"], var.table_arns["EvidenceBundle"]]
  }
  statement {
    sid       = "ReadCuratedAndRag"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arns["curated"]}/curated/*", "${var.bucket_arns["curated"]}/rag/*"]
  }
  statement {
    sid       = "WriteLargeEvidenceBundles"
    actions   = ["s3:PutObject"]
    resources = ["${var.bucket_arns["curated"]}/rag/evidence/*"]
  }
  # Phase 09 AuditAgent (main/lane 1 AuditAgent.scala:199-200, 258) writes audit documents and spilled evidence bundles under
  # rag/audit with ObjectStore.putIfAbsent = S3 PutObject only (ObjectStores.scala doPut; no head/list on that path).
  statement {
    sid       = "WriteAuditDocuments"
    actions   = ["s3:PutObject"]
    resources = ["${var.bucket_arns["curated"]}/rag/audit/*"]
  }
  statement {
    sid       = "VectorsRead"
    actions   = ["s3vectors:QueryVectors", "s3vectors:GetVectors", "s3vectors:ListVectors", "s3vectors:GetIndex"]
    resources = local.index_arns
  }
  dynamic "statement" {
    for_each = local.neptune_enabled ? [1] : []
    content {
      sid       = "NeptuneRead"
      actions   = ["neptune-db:ReadDataViaQuery", "neptune-db:GetQueryStatus", "neptune-db:CancelQuery"]
      resources = [local.neptune_db_arn]
    }
  }
  dynamic "statement" {
    for_each = local.bedrock_enabled ? [1] : []
    content {
      sid = "BedrockInvoke"
      # The Converse and ConverseStream APIs are authorised by bedrock:InvokeModel and bedrock:InvokeModelWithResponseStream (no separate action).
      actions   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      resources = var.bedrock_model_arns
    }
  }
}

# retrieval: strictly read-only.
data "aws_iam_policy_document" "retrieval" {
  statement {
    sid       = "TablesRead"
    actions   = ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:DescribeTable"]
    resources = local.all_table_arns
  }
  statement {
    sid       = "ReadCuratedAndRag"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arns["curated"]}/curated/*", "${var.bucket_arns["curated"]}/rag/*"]
  }
  statement {
    sid       = "VectorsRead"
    actions   = ["s3vectors:QueryVectors", "s3vectors:GetVectors", "s3vectors:ListVectors", "s3vectors:GetIndex"]
    resources = local.index_arns
  }
  dynamic "statement" {
    for_each = local.neptune_enabled ? [1] : []
    content {
      sid       = "NeptuneRead"
      actions   = ["neptune-db:ReadDataViaQuery", "neptune-db:GetQueryStatus", "neptune-db:CancelQuery"]
      resources = [local.neptune_db_arn]
    }
  }
  # retrieval builds the query embedder: the embedding model only
  dynamic "statement" {
    for_each = local.embedding_bedrock ? [1] : []
    content {
      sid       = "BedrockInvokeEmbeddingModel"
      actions   = ["bedrock:InvokeModel"]
      resources = [local.embedding_model_arn]
    }
  }
}

# embedding-worker: consumes the embedding queue, writes vectors (no deletes), records run provenance.
data "aws_iam_policy_document" "embedding_worker" {
  # STORAGE-CONTRACTS (main 765f7ce) 12.3: the worker receives, re-enqueues retries itself (SendMessage on the same queue),
  # dead-letters itself (SendMessage on the DLQ) and deletes the original only after both sends succeeded.
  statement {
    sid       = "ConsumeAndRequeue"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:SendMessage", "sqs:GetQueueAttributes"]
    resources = [var.embedding_queue_arn]
  }
  statement {
    sid       = "DeadLetterSendOnly"
    actions   = ["sqs:SendMessage"] # required by STORAGE-CONTRACTS 12.3 (the worker sends DLQ jobs itself); nothing else on the DLQ
    resources = [var.embedding_dlq_arn]
  }
  # Text objects only (textS3Uri under rag/text/, 12.2/12.6) and the live suite's text objects (logs/it-embedding/, 12.7, section 10).
  statement {
    sid     = "ReadTextObjects"
    actions = ["s3:GetObject"]
    resources = [
      "${var.bucket_arns["curated"]}/rag/text/*",
      "${var.bucket_arns["curated"]}/logs/it-embedding/*",
    ]
  }
  # VectorStore.get (idempotency check) and putBatch (12.3). The adapter calls GetVectors, PutVectors (and QueryVectors only for search,
  # which the worker does not do). Scoped by index ARN: the five production indexes plus the test index.
  statement {
    sid       = "VectorsGetPut"
    actions   = ["s3vectors:GetVectors", "s3vectors:PutVectors"]
    resources = local.index_arns
  }
  # Claims: conditional put only (12.4a). Production table, plus the separate test table so a worker-image live run with
  # indexOverride can claim into its own table and never touch the production one.
  statement {
    sid       = "ClaimVectorKeys"
    actions   = ["dynamodb:PutItem"]
    resources = [var.table_arns["embedding-key-claims"], var.table_arns["it-embedding-key-claims"]]
  }
  statement {
    sid       = "ProvenanceTables"
    actions   = ["dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:GetItem", "dynamodb:Query", "dynamodb:DescribeTable"]
    resources = [var.table_arns["RunLedger"], var.table_arns["ArtifactRegistry"]]
  }
  # Titan embedding model only, from the configured model id. No wildcard model, no generation model.
  dynamic "statement" {
    for_each = local.embedding_bedrock ? [1] : []
    content {
      sid       = "BedrockInvokeEmbeddingModel"
      actions   = ["bedrock:InvokeModel"]
      resources = [local.embedding_model_arn]
    }
  }
}

resource "aws_iam_role" "task" {
  for_each           = toset(["query-api", "retrieval", "embedding-worker"])
  name               = "${var.name}-task-${each.value}"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy" "task" {
  for_each = {
    "query-api"        = data.aws_iam_policy_document.query_api.json
    "retrieval"        = data.aws_iam_policy_document.retrieval.json
    "embedding-worker" = data.aws_iam_policy_document.embedding_worker.json
  }
  name   = "runtime"
  role   = aws_iam_role.task[each.key].id
  policy = each.value
}

############################ ops task role (one-off Neptune bulk load and embedding enqueue) ############################
# Sources: INFRA-CONTRACTS.md section 14.6. Used only by the ops task definition (run-task, no service). No Bedrock, no S3 Vectors,
# no Secrets Manager, no deletes, no wildcard action. Neptune statements exist only when Neptune exists.
data "aws_iam_policy_document" "ops" {
  # Source: storage-engineer's LiveLoad (reads <neptune source> and <items source>), service-engineer's EmbeddingProducer (rag/text/*).
  statement {
    sid     = "ReadLoadFiles"
    actions = ["s3:GetObject"]
    resources = [
      "${var.bucket_arns["curated"]}/graph/neptune-load/*",
      "${var.bucket_arns["curated"]}/curated/loads/*",
      "${var.bucket_arns["curated"]}/rag/text/*", # EmbeddingProducer.storeText: put-if-absent, HeadObject/GetObject on an existing text object
    ]
  }
  statement {
    sid       = "ListLoadPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [var.bucket_arns["curated"]]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/neptune-load/*", "curated/loads/*"]
    }
  }
  # ops-manifest.json only (OpsLoadMain writes <items-source>ops-manifest.json with putIfAbsent = PutObject with If-None-Match: *, OpsLoad.scala:450-459;
  # LiveLoad.writeProvenance is not called), so the uploaded load files cannot be overwritten; and the content-addressed embedding
  # texts s3://<curated>/rag/text/<sha256>.txt (INFRA-CONTRACTS 3a). No DeleteObject anywhere. The bucket is SSE-S3 (AES256), so no kms:* is needed.
  statement {
    sid     = "WriteManifestAndTexts"
    actions = ["s3:PutObject"]
    resources = [
      "${var.bucket_arns["curated"]}/curated/loads/*/ops-manifest.json",
      "${var.bucket_arns["curated"]}/rag/text/*",
    ]
  }
  # LiveLoad writes the ten analytics item tables (cfg.tables.analytics) with put and get; nothing else.
  statement {
    sid       = "LoadAnalyticsItems"
    actions   = ["dynamodb:PutItem", "dynamodb:GetItem"]
    resources = local.analytics_table_arns
  }
  # WorkQueues.scala SqsWorkQueue.send = SendMessage; GetQueueUrl for a name-to-URL lookup. The embedding queue only, not the DLQ.
  statement {
    sid       = "EnqueueEmbeddingJobs"
    actions   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
    resources = [var.embedding_queue_arn]
  }
  dynamic "statement" {
    for_each = local.neptune_enabled ? [1] : []
    content {
      sid       = "NeptuneBulkLoad"
      actions   = ["neptune-db:StartLoaderJob", "neptune-db:GetLoaderJobStatus"]
      resources = [local.neptune_db_arn]
    }
  }
}

resource "aws_iam_role" "ops" {
  name               = "${var.name}-task-ops"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy" "ops" {
  name   = "ops-load"
  role   = aws_iam_role.ops.id
  policy = data.aws_iam_policy_document.ops.json
}

############################ EMR roles ############################

data "aws_iam_policy_document" "emr_service_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["elasticmapreduce.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:elasticmapreduce:${var.region}:${var.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "emr_service" {
  name               = "${var.name}-emr-service"
  assume_role_policy = data.aws_iam_policy_document.emr_service_trust.json
}

# AWS-managed policy (the standard service role policy; it acts only on resources tagged
# for-use-with-amazon-emr-managed-policies=true, which the network module applies). Flagged in INFRA-CONTRACTS.md.
resource "aws_iam_role_policy_attachment" "emr_service" {
  role       = aws_iam_role.emr_service.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

# H1: AmazonEMRServicePolicy_v2 allows iam:PassRole only for role/EMR_EC2_DefaultRole (condition iam:PassedToService
# ec2.amazonaws.com*; policy reference https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AmazonEMRServicePolicy_v2.html,
# read 2026-11-22). The custom instance-profile role must therefore be passable explicitly, or CreateCluster fails.
data "aws_iam_policy_document" "emr_service_extra" {
  statement {
    sid       = "PassInstanceProfileRole"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_ec2.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "emr_service_extra" {
  name   = "pass-instance-profile-role"
  role   = aws_iam_role.emr_service.id
  policy = data.aws_iam_policy_document.emr_service_extra.json
}

data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "emr_ec2" {
  statement {
    sid       = "ReadRaw"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arns["raw"]}/*"]
  }
  statement {
    sid       = "ReadArtifacts"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arns["artifacts"]}/*"]
  }
  statement {
    sid       = "ReadWriteCurated"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"]
    resources = ["${var.bucket_arns["curated"]}/*"]
  }
  # M2: Hadoop/Spark output committers (and EMRFS rename = copy + delete of _temporary/) and overwrite need DeleteObject.
  # Only the curated-bucket prefixes jobs write. Never raw/ and never reports/ (evidence).
  statement {
    sid     = "DeleteJobOutputs"
    actions = ["s3:DeleteObject"]
    resources = [
      "${var.bucket_arns["curated"]}/curated/*",
      "${var.bucket_arns["curated"]}/graph/*",
      "${var.bucket_arns["curated"]}/rag/*",
      "${var.bucket_arns["curated"]}/tmp/*",
    ]
  }
  statement {
    sid       = "WriteReports"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${var.bucket_arns["reports"]}/*"]
  }
  statement {
    sid       = "WriteClusterLogs"
    actions   = ["s3:PutObject"]
    resources = ["${var.bucket_arns["logs"]}/emr/*"]
  }
  statement {
    sid       = "ListPipelineBuckets"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketMultipartUploads"]
    resources = [for k in ["raw", "curated", "reports", "artifacts", "logs"] : var.bucket_arns[k]]
  }
  statement {
    sid       = "LoadAnalyticsAndProvenance"
    actions   = ["dynamodb:PutItem", "dynamodb:BatchWriteItem", "dynamodb:UpdateItem", "dynamodb:GetItem", "dynamodb:Query", "dynamodb:DescribeTable"]
    resources = concat(local.analytics_table_arns, [var.table_arns["RunLedger"], var.table_arns["ArtifactRegistry"], var.table_arns["EntityLinkCache"]])
  }
  dynamic "statement" {
    for_each = local.neptune_enabled ? [1] : []
    content {
      sid       = "NeptuneBulkLoadTrigger"
      actions   = ["neptune-db:StartLoaderJob", "neptune-db:GetLoaderJobStatus", "neptune-db:ListLoaderJobs"]
      resources = [local.neptune_db_arn]
    }
  }
}

resource "aws_iam_role" "emr_ec2" {
  name               = "${var.name}-emr-ec2"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

resource "aws_iam_role_policy" "emr_ec2" {
  name   = "pipeline"
  role   = aws_iam_role.emr_ec2.id
  policy = data.aws_iam_policy_document.emr_ec2.json
}

resource "aws_iam_instance_profile" "emr_ec2" {
  name = "${var.name}-emr-ec2"
  role = aws_iam_role.emr_ec2.name
}

############################ Step Functions role ############################

data "aws_iam_policy_document" "sfn_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

data "aws_iam_policy_document" "sfn" {
  statement {
    sid       = "CreateCluster"
    actions   = ["elasticmapreduce:RunJobFlow"]
    resources = ["*"] # elasticmapreduce:RunJobFlow: AWS has no resource-level permission for this action (the cluster does not exist yet)
  }
  statement {
    sid = "ManageClusterAndSteps"
    actions = [
      "elasticmapreduce:DescribeCluster", "elasticmapreduce:AddJobFlowSteps", "elasticmapreduce:DescribeStep",
      "elasticmapreduce:CancelSteps", "elasticmapreduce:ListSteps",
      # M6: RunJobFlow here sets AutoTerminationPolicy, ManagedScalingPolicy and Tags. These actions exist as separate
      # actions on the cluster resource (Service Authorization Reference, list_amazonelasticmapreduce.html, read 2026-11-22).
      # Whether RunJobFlow strictly needs them is NOT VERIFIED (no documentation states the dependency); granted scoped to cluster/*.
      "elasticmapreduce:PutAutoTerminationPolicy", "elasticmapreduce:PutManagedScalingPolicy", "elasticmapreduce:AddTags",
      # Needed by the .sync integration to clean up the cluster it created when an execution is aborted.
      "elasticmapreduce:TerminateJobFlows",
    ]
    resources = ["arn:aws:elasticmapreduce:${var.region}:${var.account_id}:cluster/*"]
  }
  statement {
    sid       = "PassEmrRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_service.arn, aws_iam_role.emr_ec2.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["elasticmapreduce.amazonaws.com", "ec2.amazonaws.com"]
    }
  }
  statement {
    sid     = "ManagedEventRulesForSync"
    actions = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
    resources = [
      "arn:aws:events:${var.region}:${var.account_id}:rule/StepFunctionsGetEventForEMRRunJobFlowRule",
      "arn:aws:events:${var.region}:${var.account_id}:rule/StepFunctionsGetEventForEMRAddJobFlowStepsRule",
      "arn:aws:events:${var.region}:${var.account_id}:rule/StepFunctionsGetEventForEMRTerminateJobFlowsRule",
    ]
  }
  statement {
    sid = "ExecutionLogDelivery"
    actions = [
      "logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery", "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries", "logs:PutResourcePolicy", "logs:DescribeResourcePolicies", "logs:DescribeLogGroups",
    ]
    resources = ["*"] # logs:CreateLogDelivery, GetLogDelivery, UpdateLogDelivery, DeleteLogDelivery, ListLogDeliveries, PutResourcePolicy, DescribeResourcePolicies, DescribeLogGroups: no resource-level permission (AWS-documented for Step Functions logging)
  }
}

resource "aws_iam_role" "sfn" {
  name               = "${var.name}-sfn-pipeline"
  assume_role_policy = data.aws_iam_policy_document.sfn_trust.json
}

resource "aws_iam_role_policy" "sfn" {
  name   = "pipeline"
  role   = aws_iam_role.sfn.id
  policy = data.aws_iam_policy_document.sfn.json
}

############################ Deployment role (separate from every runtime role) ############################
# Used by deploy-engineer to push images, roll services and start the pipeline. It cannot change IAM, networks, data stores
# or Terraform state. Not created until deployment_principal_arns is set.

data "aws_iam_policy_document" "deployer_trust" {
  count = length(var.deployment_principal_arns) > 0 ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = var.deployment_principal_arns
    }
  }
}

data "aws_iam_policy_document" "deployer" {
  count = length(var.deployment_principal_arns) > 0 ? 1 : 0
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # ecr:GetAuthorizationToken: AWS has no resource-level permission for this action
  }
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload",
      "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages", "ecr:DescribeRepositories",
    ]
    resources = values(var.ecr_repository_arns)
  }
  statement {
    sid       = "RollServices"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = [for s in var.service_names : "arn:aws:ecs:${var.region}:${var.account_id}:service/${var.cluster_name}/${s}"]
  }
  statement {
    sid       = "TaskDefinitions"
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition", "ecs:DeregisterTaskDefinition"]
    resources = ["*"] # ecs:RegisterTaskDefinition, DescribeTaskDefinition, DeregisterTaskDefinition: AWS has no resource-level permission for task definitions
  }
  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = concat([for r in aws_iam_role.ecs_execution : r.arn], [for r in aws_iam_role.task : r.arn])
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
  statement {
    sid       = "PublishArtifacts"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${var.bucket_arns["artifacts"]}/*"]
  }
  statement {
    sid       = "ArchiveEvidence"
    actions   = ["s3:PutObject"]
    resources = ["${var.bucket_arns["reports"]}/*"]
  }
  statement {
    sid     = "StartPipeline"
    actions = ["states:StartExecution", "states:DescribeExecution", "states:StopExecution"]
    resources = [
      "arn:aws:states:${var.region}:${var.account_id}:stateMachine:${var.state_machine_name}",
      "arn:aws:states:${var.region}:${var.account_id}:execution:${var.state_machine_name}:*",
    ]
  }
  statement {
    sid       = "ReadLogs"
    actions   = ["logs:GetLogEvents", "logs:FilterLogEvents", "logs:DescribeLogStreams"]
    resources = [for g in values(var.log_group_arns) : "${g}:*"]
  }
}

resource "aws_iam_role" "deployer" {
  count              = length(var.deployment_principal_arns) > 0 ? 1 : 0
  name               = "${var.name}-deployer"
  assume_role_policy = data.aws_iam_policy_document.deployer_trust[0].json
}

resource "aws_iam_role_policy" "deployer" {
  count  = length(var.deployment_principal_arns) > 0 ? 1 : 0
  name   = "deploy"
  role   = aws_iam_role.deployer[0].id
  policy = data.aws_iam_policy_document.deployer[0].json
}
