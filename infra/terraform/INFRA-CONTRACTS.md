# INFRA-CONTRACTS (Phase 12, work order A)

Owner: infra-engineer. Environment: `infra/terraform/environments/course/`. Modules: `infra/terraform/modules/`.
Status: authored and statically validated only (repair round 1 applied: human decisions 1 to 6). NOTHING here has been planned against AWS or applied. A human runs plan (`scripts/plan.sh`) and, later, apply.
Phase 10 (service-engineer) implements against section 5 (Services). Phase 13/14 scripts consume section 8 (Outputs).

## 1. Foundation

| Item | Decision | Source or reason |
|---|---|---|
| Terraform | `>= 1.9.0, < 2.0.0` | `terraform version` on the authoring machine printed v1.17.2 |
| AWS provider | `hashicorp/aws ~> 6.73` (lock file pins 6.73.0) | Latest release listed by `https://registry.terraform.io/v1/providers/hashicorp/aws`: version 6.73.0, published 2026-11-11 (fetched 2026-11-22) |
| S3 Vectors resources | `aws_s3vectors_vector_bucket` and `aws_s3vectors_index` exist in 6.73.0 | Verified locally: after `terraform init -backend=false`, `terraform providers schema -json` lists both resource types (schema read, 2026-11-22). `aws_neptune_cluster` also exposes `serverless_v2_scaling_configuration` and `iam_roles` |
| Region and account | Region `us-east-2` (variable `aws_region`, default `us-east-2`, human decision). Account: required variable `expected_account_id` (12 digits, no default), used as provider `allowed_account_ids` | Terraform refuses any other account. `scripts/plan.sh` fails before planning unless the STS account equals `expected_account_id` and the caller's effective Region (`AWS_REGION`, else `AWS_DEFAULT_REGION`, else the profile region) equals `aws_region`. AWS Budgets is a global service, so it needs no particular Region (the earlier "Budgets requires us-east-1" text was wrong; per the work order, not independently re-verified here) |
| Required tags (provider `default_tags`) | `course`, `project`, `environment`, `owner`, `expiration-date` | Work order. `owner` and `expiration_date` have no default and must be supplied |
| State | LOCAL (`environments/course/terraform.tfstate`, gitignored) | Documented decision: single-person disposable environment, no remote backend to bootstrap. The state file is the only record of what exists: the human must not delete it before cleanup |
| Variables files | `terraform.tfvars.example` only (dummy values). Real `terraform.tfvars` is gitignored | No secrets in any variable. `alert_email` is the only personal value |
| Lock file | `.terraform.lock.hcl` kept (commit-worthy). It currently holds hashes for the authoring platform only (darwin_arm64); a different platform adds its own hash on `init` | `terraform providers lock -platform=...` was not run (not in the permitted command set) |

## 2. Network (no NAT gateway, no interface endpoints)

- One VPC `10.20.0.0/16` (variable), two AZs, two public `/24` and two private `/24` subnets.
- Public subnets route `0.0.0.0/0` to the internet gateway. Fargate tasks and EMR run there with public IPs; no inbound is open except the ALB listener.
- Private subnets have only the local route. Neptune lives there. They cannot reach the internet.
- Free gateway endpoints for S3 and DynamoDB are attached to both route tables. The S3 gateway endpoint is what the Neptune bulk loader needs. No interface endpoint exists (each costs hourly).
- The default security group is emptied.
- Security groups chained by role: internet (variable `alb_ingress_cidrs`: REQUIRED, no default, validation accepts IPv4 CIDRs with prefix /16 or narrower only: no `0.0.0.0/0`, no `::/0`, no IPv6; ports 80 and, with a certificate, 443) -> `alb` -> `query-api` -> `retrieval` -> `neptune`; `emr` -> `neptune`. Egress is port-scoped (443, 80 for EMR package repos, 8182 to the Neptune group).
- Consequence for live suites: Neptune is private. `NeptuneReadAwsItSuite` (needs a reachable `SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT`) can only run from inside the VPC (an ECS task or an EMR node). The S3, DynamoDB and S3 Vectors suites run from anywhere with credentials.
- Subnets, VPC and the EMR security group carry `for-use-with-amazon-emr-managed-policies=true`, which the AWS-managed EMR service policy requires.

## 3. Identity and encryption

Encryption: SSE-S3 on every bucket, DynamoDB default encryption, Neptune `storage_encrypted = true` (default AWS key), SQS-managed SSE, S3 Vectors SSE `AES256`. **No customer-managed KMS key is created** (each key costs monthly; nothing in the work order requires one). The SNS alarm topic is **unencrypted on purpose** (human decision, repair round 2): it carries CloudWatch alarm notifications only (alarm name and state text, no data), and encrypting it with the AWS-managed key `alias/aws/sns` is expected to stop CloudWatch alarms from publishing (section 7). The trivy AWS-0095 finding is suppressed inline with that justification (`modules/observability/main.tf`, ignore at line 13).

| Role | Trust | Permissions (all scoped to named resources unless noted) |
|---|---|---|
| `scholarchain-ecs-execution-<service>` (three roles, one per service) | `ecs-tasks` (source account) | ECR pull on that service's repo; log streams on that service's log group; `GetSecretValue` on the exact ARNs of the secrets that service injects (query-api: `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY`; retrieval: `scholarchain/voyage-api-key`, `SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY`; embedding-worker: `scholarchain/voyage-api-key`). `ecr:GetAuthorizationToken` on `*` (no resource-level support) |
| `scholarchain-task-query-api` | `ecs-tasks` | DynamoDB read on the 15 operational and analytics tables (not the claims tables); write on QuerySession and EvidenceBundle; S3 read `curated/*` and `rag/*`, write `rag/evidence/*` and `rag/audit/*` (audit documents and spilled bundles, `s3:PutObject` only; the audit path uses `putIfAbsent`, which is PutObject, with no head or list: `AuditAgent.scala:199-200, 258`, `ObjectStores.scala` `doPut`; GetObject on `rag/*` already covers reads) in the curated bucket; S3 Vectors query/get/list on the 6 indexes (5 production + test); Neptune read-only queries; Bedrock invoke only on the GENERATION models in `bedrock_model_arns` (default none; this variable is for query-api only) |
| `scholarchain-task-retrieval` | `ecs-tasks` | Read-only version of the above (no writes); Bedrock invoke only on the embedding model `arn:aws:bedrock:<region>::foundation-model/<embedding_model_id>` (query embedder), only when `embedding_provider = "bedrock"` |
| `scholarchain-task-embedding-worker` | `ecs-tasks` | the statements listed in section 3a below |
| `scholarchain-emr-service` | `elasticmapreduce` (source account/ARN) | AWS-managed `AmazonEMRServicePolicy_v2` (see flag below) + inline `iam:PassRole` on `scholarchain-emr-ec2` only, condition `iam:PassedToService = ec2.amazonaws.com` (review H1: the managed policy passes only `role/EMR_EC2_DefaultRole`, condition `ec2.amazonaws.com*`; confirmed on https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AmazonEMRServicePolicy_v2.html, read 2026-11-22) |
| `scholarchain-emr-ec2` (+ instance profile) | `ec2` | S3 read raw/artifacts, read-write curated, `s3:DeleteObject` on curated `curated/*`, `graph/*`, `rag/*`, `tmp/*` only (output committers; never raw or reports), write reports, write `logs/emr/*`; DynamoDB write on the 10 analytics tables + RunLedger, ArtifactRegistry, EntityLinkCache; Neptune loader start/status/list |
| `scholarchain-neptune-load` | `rds.amazonaws.com` | `GetObject` on `curated/graph/neptune-load/*`, `ListBucket` with that prefix. Attached to the cluster via `iam_roles` |
| `scholarchain-sfn-pipeline` | `states` (source account) | EMR RunJobFlow (`*`, no resource-level support), cluster/step APIs on `cluster/*` (including `PutAutoTerminationPolicy`, `PutManagedScalingPolicy`, `AddTags`: these exist as cluster-scoped actions per https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazonelasticmapreduce.html, read 2026-11-22; that `RunJobFlow` needs them for the parameters used is NOT VERIFIED, no AWS page states it, so they are granted as a precaution), `iam:PassRole` for the two EMR roles only, three named EventBridge managed rules, CloudWatch Logs delivery APIs (`*`, AWS-documented) |
| `scholarchain-deployer` | `deployment_principal_arns` (variable, default empty: role NOT created) | ECR push on the 3 repos; `ecs:UpdateService` on the 3 services; task-definition registration (`*`, no resource-level support); `PassRole` on the 6 ECS roles (3 execution + 3 task) to `ecs-tasks` only; put objects to artifacts and reports buckets; start/stop executions of the one state machine; read the log groups. Cannot touch IAM, networks, data stores or state |

MANUAL ACTION REQUIRED flags (security changes, human approval): the whole IAM set above; `AmazonEMRServicePolicy_v2` is an AWS-managed policy broader than named resources (it is the standard EMR service role policy and acts only on tagged resources). `alb_ingress_cidrs` has no default and rejects any `/0`: the human sets a `/32`.

**Every remaining `"*"` resource (all others are scoped to named ARNs; each has a justification comment in `modules/iam/main.tf`):**

| Role | Actions on `*` | Why `*` |
|---|---|---|
| `scholarchain-ecs-execution-<service>` (x3) | `ecr:GetAuthorizationToken` | AWS has no resource-level permission for this action |
| `scholarchain-sfn-pipeline` | `elasticmapreduce:RunJobFlow` | AWS has no resource-level permission for this action (the cluster does not exist yet) |
| `scholarchain-sfn-pipeline` | `logs:CreateLogDelivery`, `GetLogDelivery`, `UpdateLogDelivery`, `DeleteLogDelivery`, `ListLogDeliveries`, `PutResourcePolicy`, `DescribeResourcePolicies`, `DescribeLogGroups` | No resource-level permission; AWS-documented requirement for Step Functions logging |
| `scholarchain-deployer` (only if created) | `ecr:GetAuthorizationToken` | Same as above |
| `scholarchain-deployer` (only if created) | `ecs:RegisterTaskDefinition`, `DescribeTaskDefinition`, `DeregisterTaskDefinition` | AWS has no resource-level permission for task definitions |

The query-api and retrieval roles do not see the claims tables. No `*` resource was added by the embedding alignment.

### 3a. Every IAM statement of `scholarchain-task-embedding-worker` (sources: STORAGE-CONTRACTS.md on main 765f7ce, sections 12.2, 12.3, 12.4a, 12.6, 12.7)

| Sid | Actions | Resources | Why |
|---|---|---|---|
| ConsumeAndRequeue | `sqs:ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility`, `SendMessage`, `GetQueueAttributes` | the production queue `scholarchain-embedding-jobs` | 12.3: receives, re-enqueues retries itself, deletes the original after both follow-up sends |
| DeadLetterSendOnly | `sqs:SendMessage` | the DLQ `scholarchain-embedding-jobs-dlq` | 12.3 confirms the worker sends DLQ jobs itself; nothing else on the DLQ. (The adapter takes queue URLs from configuration, so no `GetQueueUrl`) |
| ReadTextObjects | `s3:GetObject` | curated bucket `rag/text/*` and `logs/it-embedding/*` | 12.2/12.6: `textS3Uri` is `s3://<bucket>/rag/text/<sha256>.txt`; 12.7 and section 10: the live suite's text objects are `<logs prefix>it-embedding/<i>.txt`. The worker never writes text (the producer does) |
| VectorsGetPut | `s3vectors:GetVectors`, `s3vectors:PutVectors` | the 5 production index ARNs + the test index ARN (index ARN only) | 12.3: `VectorStore.get` then `putBatch`. The adapter calls `queryVectors` only for search, which the worker does not do; not granted |
| ClaimVectorKeys | `dynamodb:PutItem` | `scholarchain-embedding-key-claims` and `scholarchain-it-embedding-key-claims` | 12.4a: conditional put only (no get, query or delete). The test-table grant exists so a worker-image live run with `indexOverride` claims into its own table and never writes the production table |
| ProvenanceTables | `dynamodb:PutItem`, `UpdateItem`, `GetItem`, `Query`, `DescribeTable` | RunLedger, ArtifactRegistry | 12.5 ledger (stage `embedding-job`) |
| BedrockInvokeEmbeddingModel (only when `embedding_provider = "bedrock"`) | `bedrock:InvokeModel` | `arn:aws:bedrock:<region>::foundation-model/<embedding_model_id>` = `amazon.titan-embed-text-v2:0` by default | exactly one model built from the configured id; no wildcard and no generation model. With `embedding_provider = "voyage"` there is no Bedrock grant |

Not verified: the exact S3 object prefix used by a worker-image live run (assumed `logs/it-embedding/`, from section 10 and `EmbeddingQueueAwsItSuite`); whether the Bedrock adapter in `rag` needs any action beyond `bedrock:InvokeModel` (the Bedrock embedder is in `rag`, whose source is not in the lane 2 tree; `RAG-EMBEDDING-CONTRACTS.md` was read, the code was not).

Secrets (all created EMPTY: no secret version; Terraform never holds a value; they appear in state only as ARNs; a human sets each value after apply):

| Secrets Manager name | Env variable in the task | Injected into |
|---|---|---|
| `scholarchain/voyage-api-key` | `SCHOLARCHAIN_VOYAGE_API_KEY` (name from `embedding.voyage.api-key-env-var` in `scholarchain-embedding.v1.conf`, accepted, main 765f7ce) | retrieval, embedding-worker (the services that build the embedder; query-api builds none). This existing entry satisfies the Voyage requirement; no duplicate exists |
| `SCHOLARCHAIN_ANTHROPIC_API_KEY` | `SCHOLARCHAIN_ANTHROPIC_API_KEY` | query-api |
| `SCHOLARCHAIN_SERVICES_API_KEY` | `SCHOLARCHAIN_SERVICES_API_KEY` | query-api, retrieval |
| `SCHOLARCHAIN_INTERNAL_API_KEY` | `SCHOLARCHAIN_INTERNAL_API_KEY` | query-api, retrieval |

The three `SCHOLARCHAIN_*_API_KEY` names are the human's Phase 10 names (not committed anywhere yet; not verified against code). The bare name used by the vendor SDK convention does not appear as an environment or secret name anywhere in this configuration. Each service's execution role can read only its own secrets (section 3 table). Secrets are billed per secret-month; see section 10.

## 4. Data

S3 (5 buckets, `scholarchain-course-<account-id>-<role>`; fewer buckets would blur lifecycle and evidence-preservation rules):

| Role | Prefixes (core `storage.s3.*`) | Versioning | Lifecycle | `force_destroy` |
|---|---|---|---|---|
| raw | `raw/` | on | none (evidence) | false, no switch |
| curated | `curated/`, `graph/`, `rag/` (and `tmp/`, `it/`, `logs/it-embedding/`) | off (suspended; trivy AWS-0090 accepted by the human, `modules/data/s3.tf:69`) | `tmp/`, `it/` and `logs/it-embedding/` 7 days; `graph/neptune-load/` 30 days | variable `force_destroy_intermediate` (default false) |
| reports | `reports/` | on | none (evidence) | false, no switch |
| logs | `logs/` (EMR logs go to `emr/`) | off | everything 14 days | variable `force_destroy_intermediate` |
| artifacts | jars, scripts, `emr-scratch/` | on | `emr-scratch/` 7 days | variable `force_destroy_intermediate` |

All: public-access block (all four), BucketOwnerEnforced, SSE-S3, a deny-non-TLS bucket policy, abort-incomplete-multipart after 3 days. Noncurrent versions expire after 7 days on curated, logs and artifacts only; raw and reports (evidence) keep every noncurrent version so an accidental overwrite or delete stays recoverable (cost is bounded: they hold datasets and reports only).

DynamoDB (17 tables: 5 operational, 10 analytics, 2 embedding key claims; on-demand, PITR off by default: disposable and rebuilt from S3; deletion protection ON by default). Physical names equal the defaults of `scholarchain-storage.v1.conf`; key schemas follow `modules/storage/STORAGE-CONTRACTS.md` section 4:

| Table | pk | sk | TTL |
|---|---|---|---|
| scholarchain-RunLedger, -ArtifactRegistry, -EvidenceBundle | S | S | none |
| scholarchain-EntityLinkCache | S | S | `expiresAt` |
| scholarchain-QuerySession | S | none | `expiresAt` |
| scholarchain-analytics-researcher-metrics, -coauthor-edge-works, -bridges, -coreness, -temporal-changes, -paths | S | S | none |
| scholarchain-embedding-key-claims (accepted, main 765f7ce, STORAGE-CONTRACTS.md 12.4a: pk = vector key, no sort key, on-demand, no TTL, permanent, PutItem only; deletion protection ON) | S | none | none |
| scholarchain-it-embedding-key-claims (TEST claims table for live suites and `indexOverride` runs; same schema; deletion protection OFF, because it is disposable test data and cleanup then needs no extra flip; it must never hold production claims) | S | none | none |
| scholarchain-analytics-path-estimates, -window-summary, -identity-clusters, -mention-researcher | S | none | none |

If `dynamodb_table_prefix` is changed from `scholarchain`, every `SCHOLARCHAIN_STORAGE_DYNAMODB_*_TABLE` override (and the analytics `..._DYNAMODB_ANALYTICS_*_TABLE` ones) must be set to the new physical names; the core `DYNAMO_TABLE_PREFIX` variable alone does not rename the storage-module defaults. Keep the default.

S3 Vectors: bucket `scholarchain-course-vectors`; indexes `researcher-profile-v1`, `collaboration-community-v1`, `paper-abstract-v1`, `collaboration-path-v1`, `topic-institution-summary-v1` and the live-suite test index `scholarchain-it-embedding-test` (name from `embedding.test.vector-index-name` in `scholarchain-embedding.v1.conf`, accepted, main 765f7ce; present, dimension = `var.vector_dimension`, same as the production indexes). All float32, cosine, SSE `AES256`. **Dimension default 1024** (variable `vector_dimension`): 1024 is the default output size of Amazon Titan Text Embeddings V2 and of the Voyage voyage-3 family, so both the Bedrock path and the instructor-approved Voyage fallback fit without recreating indexes (an index dimension cannot change). **Evidence for 1024 (read-only):** (1) lane 2 core contract example `modules/core/src/main/resources/contracts/vector-record.example.json` has `"dimension":1024` with `"embeddingModelId":"amazon.titan-embed-text-v2:0"`; (2) accepted, main 765f7ce: `modules/core/src/main/resources/scholarchain-embedding.v1.conf` has `embedding.model.id = "amazon.titan-embed-text-v2:0"`, `version = "v2"`, `dimension = 1024`; (3) accepted, main 765f7ce: `modules/rag/RAG-EMBEDDING-CONTRACTS.md` line 33 reads: "| `BedrockEmbedder` | Default. Titan Text Embeddings V2 | model ID, version, dimension from `embedding.model.*` (`amazon.titan-embed-text-v2:0`, `v2`, 1024; Titan accepts 256/512/1024). ..." (read with `git show main:...`). If the Voyage fallback is used, confirm its output size matches 1024 before apply. The live suites read `SCHOLARCHAIN_IT_VECTOR_DIMENSION` = the same value and write only to the test index `scholarchain-it-embedding-test` (STORAGE-CONTRACTS 12.8; the earlier Phase 07 probe of `researcher-profile-v1` was a defect fixed on main).

Neptune Serverless: one cluster, one `db.serverless` instance, minimum 1 NCU and maximum 2.5 NCU (variables; **2.5 is the lowest legal maximum**: "the smallest value you can set for the maximum capacity is 2.5 NCUs", and the lowest minimum is 1.0, https://docs.aws.amazon.com/neptune/latest/userguide/neptune-serverless-capacity-scaling.html, fetched 2026-11-24; the Terraform provider schema has no lower bound of its own), private subnets, storage encrypted, IAM database authentication on, 1-day backup, `deletion_protection` default **true**, `skip_final_snapshot` default **true** (graph is rebuilt deterministically from S3; set false to keep a snapshot). `create_neptune = false` removes Neptune and its cost.

**Engine version: default `1.5.0.1`** (was 1.4.8.1). Evidence, fetched 2026-11-24: https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases.html lists 1.5.0.1 (released 2026-11-12, active, end of life TBD) as the newest; the release page https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases-1.5.0.1.html confirms deployment to all Regions on 2026-11-12. **Serverless on 1.5.0.1 in us-east-2 is VERIFIED by the human's read-only `describe-orderable-db-instance-options` check on 2026-11-24, which shows `db.serverless` for that engine** (the release page itself does not mention Serverless; the Serverless page says 1.2.0.1 or above, https://docs.aws.amazon.com/neptune/latest/userguide/neptune-serverless-using.html; the 1.5.0.0 notes include a Serverless scale-down fix, https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases-1.5.0.0.html). The Serverless capacity range depends on engine AND platform version (same page); the platform-version minimum listed is 1.0 NCU; no per-engine max/min change for 1.5 was found.

Consequences checked for 1.5.0.x (read-only; nothing in `modules/` changed): (1) **Parameter group family:** the docs describe the naming rule `neptuneX.Y` and list `neptune1.2`, `neptune1.3`, `neptune1.4`; **a `neptune1.5` family is not listed** in the page read, and this configuration sets no custom parameter group, so there is nothing to rename; if a custom group is added later, confirm the family name for 1.5. (2) **Query languages for 1.5.0.1:** Gremlin 3.8.0 to 3.8.1 (newer Gremlin client minimums: Java 11+, Python 3.10+, Go 1.25+, .NET 6.0+), openCypher `Neptune-9.0.20190305-1.1`, SPARQL 1.1. The storage adapters use openCypher through `neptunedata` and openCypher CSV bulk load, not Gremlin; the 1.5.0.1 fix for openCypher `SKIP` is relevant to any templated query using it. (3) **Bulk loader:** 1.5.0.0 adds a fast offline loader for Gremlin CSV into empty graphs; the standard loader used here (openCypher) is not noted as changed; no IAM change is mentioned. (4) Upgrade path to 1.5.0.1 is from 1.2.0.0 or above; a new cluster is unaffected. (5) Adapter behavior on 1.5.0.1 was not run (no AWS).

Consequences checked for 1.5.0.x (read-only; nothing in `modules/` changed): (1) **Parameter group family:** the docs describe the naming rule `neptuneX.Y` and list `neptune1.2`, `neptune1.3`, `neptune1.4`; **a `neptune1.5` family is not listed** in the page read, and this configuration sets no custom parameter group (the default group of the engine's family is used), so there is nothing to rename; if a custom group is added later, confirm the family name for 1.5. (2) **Query languages for 1.5.0.1:** Gremlin 3.8.0 to 3.8.1 (TinkerPop 3.8; 1.5.0.0 notes list newer Gremlin client minimums: Java 11+, Python 3.10+, Go 1.25+, .NET 6.0+), openCypher `Neptune-9.0.20190305-1.1`, SPARQL 1.1. The storage adapters use openCypher through the `neptunedata` API and openCypher CSV bulk load, not Gremlin, so the Gremlin change does not apply; the 1.5.0.1 fix for openCypher `SKIP` is relevant to any templated query using `SKIP`. (3) **Bulk loader:** 1.5.0.0 adds a "fast bulk load (offline loader)" for Gremlin CSV into empty graphs; the standard loader (which this design uses, openCypher format) is unchanged in the notes; 1.5.0.1 has "miscellaneous improvements to the offline bulk loader". No IAM change is mentioned: the load role and `neptune-db` actions stay as they are. (4) Upgrade path to 1.5.0.1 is from 1.2.0.0 or above; a new cluster is unaffected. (5) Behavior of the adapters on 1.5.0.1 was not run (no AWS).

## 5. Services (contract for Phase 10)

One ECS cluster `scholarchain-cluster`, Fargate, 0.25 vCPU / 512 MiB, Linux X86_64 (variable for ARM64), task logs to `/scholarchain/<service>` (7 days). Images: `<account>.dkr.ecr.us-east-2.amazonaws.com/scholarchain/<service>:<image_tag>` (immutable tags, scan on push; default tag `bootstrap` is a placeholder).

| Service | Role | Port | Exposure | Capacity | Autoscaling (min default 0, max 4) |
|---|---|---|---|---|---|
| `query-api` | REST API | 8080 | ALB listener 80 (443 + redirect if `certificate_arn` set). ALB target health check `GET /healthz` (liveness) must return 200 | FARGATE | CPU 60 percent; ALB requests per target 200 |
| `retrieval` | graph + vector retrieval | 8080 | internal only: `http://retrieval.scholarchain.local:8080` (Cloud Map A record), reachable from `query-api` only | FARGATE | CPU 60 percent |
| `embedding-worker` | SQS consumer | none | no inbound at all | FARGATE (regular, on-demand; no Spot path exists in the configuration) | queue backlog (see deviation 3) |

Desired count is 0 at creation and ignored afterwards. **To scale up for an experiment the human raises `ecs_min_tasks` (for example `{"query-api"=1,"retrieval"=1,"embedding-worker"=1}`) in a gitignored tfvars and applies a reviewed plan.** `ecs_max_tasks` is capped at 4 by validation.

Environment variables injected into every service (names; values are outputs, see `app_environment`):

| Variable | Value |
|---|---|
| `SCHOLARCHAIN_ENVIRONMENT` | `course` |
| `SCHOLARCHAIN_AWS_REGION`, `AWS_REGION` | `us-east-2` (variable `aws_region`) |
| `SCHOLARCHAIN_STORAGE_RAW_BUCKET` | raw bucket name |
| `SCHOLARCHAIN_STORAGE_CURATED_BUCKET` | curated bucket name |
| `SCHOLARCHAIN_STORAGE_VECTOR_BUCKET`, `SCHOLARCHAIN_STORAGE_VECTORS_BUCKET_NAME` | vector bucket name |
| `SCHOLARCHAIN_STORAGE_VECTOR_INDEX` | `paper-abstract-v1` (variable `default_vector_index`) |
| `SCHOLARCHAIN_STORAGE_DYNAMO_TABLE_PREFIX` | `scholarchain` |
| `SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT` | `https://<cluster-endpoint>:8182`. **NOT SET by default** (`create_neptune = false`): the variable is omitted, never set to an empty string. Consequence (core `Config.scala` treats a blank or missing variable as unset, and `storage.neptune-endpoint` is a REQUIRED key): any service that resolves core configuration fails to start until Neptune is switched on (section 9a). See open point O1 |
| `SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_ROLE_ARN` | loader role ARN. **NOT SET while Neptune is off** (omitted): the storage module then falls back to the placeholder ARN of `scholarchain-storage.v1.conf` (account `000000000000`), which is harmless only because nothing can load into a cluster that does not exist |
| `SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_FORMAT` | `opencypher` |
| `SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_SOURCE_S3_URI` | `s3://<curated>/graph/neptune-load/` |
| `SCHOLARCHAIN_MODELS_EMBEDDING_MODEL_ID` | variable `embedding_model_id` (default `amazon.titan-embed-text-v2:0`) |
| `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` | variable `generation_model_id` (default is a PLACEHOLDER model ID; set the real one) |
| `SCHOLARCHAIN_EMBEDDING_MODEL_ID`, `SCHOLARCHAIN_EMBEDDING_MODEL_DIMENSION` | model id and `1024` (keys of `scholarchain-embedding.v1.conf`, accepted, main 765f7ce) |
| `SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME` | `scholarchain-embedding-key-claims` (production; accepted, main 765f7ce, `embedding.claims.table-name`). **Live suites must override it with the TEST table** `scholarchain-it-embedding-key-claims` (output `live_suite_environment`): the core key is `embedding.claims.table-name`, selected through this variable, so setting it is how a suite chooses its table |
| `SERVICE_NAME` | the service name |

Per service:

- `query-api`: `SCHOLARCHAIN_SERVICES_RETRIEVAL_URL=http://retrieval.scholarchain.local:8080`; secrets `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY` (from Secrets Manager).
- `retrieval`: secrets `SCHOLARCHAIN_VOYAGE_API_KEY`, `SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY`.
- `embedding-worker`: `SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL` (the embedding queue URL), `SCHOLARCHAIN_EMBEDDING_QUEUE_NAME=scholarchain-embedding-jobs`, `SCHOLARCHAIN_EMBEDDING_QUEUE_DEAD_LETTER_NAME=scholarchain-embedding-jobs-dlq`, `SCHOLARCHAIN_EMBEDDING_QUEUE_MAX_ATTEMPTS=3` (accepted, main 765f7ce), `SCHOLARCHAIN_SERVICES_EMBEDDING_DLQ_URL` (the DLQ URL, because the worker sends DLQ jobs itself); secret `SCHOLARCHAIN_VOYAGE_API_KEY`.

Not set by Terraform because they are not infrastructure: `SCHOLARCHAIN_STORAGE_OPENSEARCH_ENDPOINT` (OpenSearch is not created), graph, entity-resolution and retrieval tuning keys, the `SCHOLARCHAIN_STORAGE_S3_*_PREFIX` keys (defaults `raw/ curated/ graph/ rag/ reports/ logs/` match the buckets above), `SCHOLARCHAIN_AWS_IT` and `SCHOLARCHAIN_IT_RESEARCHER_ID`. The names `SCHOLARCHAIN_SERVICES_RETRIEVAL_URL`, `SCHOLARCHAIN_SERVICES_EMBEDDING_*` and `SERVICE_NAME` are infra-chosen: Phase 10 may rename them only together with infra-engineer.

SQS: `scholarchain-embedding-jobs` (visibility 300 s, 4-day retention, long polling 20 s, redrive to `scholarchain-embedding-jobs-dlq` with `maxReceiveCount = max-attempts + 1 = 4`) and the DLQ (14-day retention). Names are variables (`embedding_queue_name`, `embedding_dlq_name`, `embedding_max_attempts`). URLs are outputs `embedding_queue_url`, `embedding_dlq_url`.

**Why maxReceiveCount is 4, not 3 (STORAGE-CONTRACTS 12.3, main 765f7ce).** The worker re-enqueues retries itself with `attempt + 1`, dead-letters itself, and dead-letters a message that keeps coming back when `receiveCount > max-attempts` (3), code `redelivery-bound-exceeded`, writing a `dead_lettered` envelope to the DLQ before deleting the original. AWS defines `maxReceiveCount` as the number of times a consumer can receive a message before it is moved to the DLQ (https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html, read 2026-11-22). With 3, the message would be moved on the attempt that is the 4th receive, never delivered to the worker, so no envelope. With 4 the 4th receive reaches the worker, which dead-letters it with the envelope and deletes it; the redrive policy remains only as a safety net.

**Live-suite environment (`output live_suite_environment`, STORAGE-CONTRACTS 12.7 and section 9).** `AWS_REGION`, `SCHOLARCHAIN_IT_S3_BUCKET` (curated bucket; text objects land in `logs/it-embedding/`, expired after 7 days; probe objects in `it/`), `SCHOLARCHAIN_IT_VECTOR_DIMENSION`, `SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT`, `SCHOLARCHAIN_IT_VECTOR_INDEX` and `SCHOLARCHAIN_EMBEDDING_TEST_VECTOR_INDEX_NAME` (both `scholarchain-it-embedding-test`), `SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME` (the TEST claims table, so a suite never writes the production table), `SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL`, `SCHOLARCHAIN_IT_EMBEDDING_DLQ_URL`. No separate test queues exist: the suite drains up to 5 messages from whatever queue it is given, so pointing it at the production queue would consume real jobs (see RISKS in the work-order report). The suite's own credentials (the human's) write the claims, vectors and text objects; the worker role grants above matter only for a worker-image run.

Health endpoints (Phase 10 contract as stated by the human; **NOT VERIFIED against code**): every service exposes `GET /healthz` (liveness), `GET /readyz` (readiness) and `GET /health` as an alias of `/healthz`. The ALB target group checks `/healthz` (variable `health_check_path`), so a missing dependency (for example Neptune off) fails readiness only and does not make the ALB cycle tasks.

Network reach of retrieval (already true, no change needed): the retrieval security group has exactly one ingress rule, from the query-api security group on the service port (`modules/network/main.tf`, `retrieval_from_query_api`). Nothing else, including the load balancer, the embedding-worker and EMR, can reach retrieval. Its egress is 443 to the internet and 8182 to Neptune only.

## 5b. Operating rules for Phases 13 to 15 (cost-driven)

- **Rule A:** no 50K run (and no rerun of any pipeline) without human approval. Run 10K first, measure its actual cost, and let the measured cost decide whether 50K is affordable.
- **Rule E, DynamoDB tables to load.** Derived read-only from `modules/storage/STORAGE-CONTRACTS.md` section 4.1 and, on `git show main` at 07710d0 (Phase 09), `modules/rag/RUNTIME-AGENTS.md` section 5 and `RuntimeSupport.scala`/the agents' `reader.get(...)` calls. Item counts from the phase 07 report (63,121 items in total):

| Table | Items | Decision | Reason |
|---|---|---|---|
| researcher-metrics | 6,937 | LOAD | read by metrics, resolver, graph agents |
| coauthor-edge-works | 15,111 | LOAD | read by the path agent (supporting works) |
| temporal-changes | 4,632 | LOAD | read by metrics.temporal |
| window-summary | 6 | LOAD | read by metrics (max team size) |
| identity-clusters | 3,907 | LOAD | read by the resolver |
| mention-researcher | 4,711 | LOAD | read by the resolver |
| bridges | 20,811 | SKIP | no runtime agent reads it (bridge answers come from Neptune/graph templates per section 5 of the runtime doc: not in the "Used by" list) |
| coreness | 6,937 | SKIP | `coreness` is an attribute inside researcher-metrics items; no agent reads this table |
| paths | 63 | SKIP | shortest paths are computed through the graph store; not in the "Used by" list |
| path-estimates | 6 | SKIP | not in the "Used by" list |

  Loaded: 35,304 items; skipped: 27,817. **Phase 10 reads are unverified** (the services are not written yet); if a Phase 10 route reads a skipped table, load it then. Table definitions are all kept (an empty on-demand table costs nothing). Cost rows use item counts x an assumed item size (1 KB LOW, 2 KB HIGH: one write request unit per KB; the item files were not available to measure).
- **Rule F, enrichment cache for Phases 13/15:** archive the 10K/50K enrichment cache (about 105K small JSON files from Crossref, OpenAlex and ORCID, about 2.6 GB) as **a few large S3 objects** (for example one compressed archive per source and scale) and read it that way, not as one object per work. This keeps S3 PUT/LIST and GET request counts in the thousands (the estimate assumes 5,000 to 40,000 PUTs) instead of over 100,000, and keeps the EMR step's S3 listing small.

- **Operating rules A, C, D, E (cost bounds; the estimate depends on them):**

| Rule | Bound | Enforced by | Evidence for the figure |
|---|---|---|---|
| A. Neptune and ALB windows | at most **8 h in total** per project; `create_neptune` and `create_alb` are human toggles; the 3 load hours stay at 2.5 NCU, other window hours are priced at the 2.5 NCU maximum as well (the Terraform maximum is the cap) | human rule; Terraform caps capacity (max 2.5 NCU) | the toggles default to false; nothing starts them except a human plan |
| C. Log volume | about **0.5 GB** CloudWatch Logs ingestion | human rule and application log level; Terraform: 7-day retention, Step Functions level ERROR, Container Insights off. **Ingestion is not capped by AWS or Terraform** | services log at the app's level; EMR logs go to S3 |
| D. Fargate | about **56 task-hours** (for example 7 tasks x 8 h) | Terraform: desired 0, autoscaling max 4 per service, min tasks 0 by default; hours are a human rule | task cost 0.25 vCPU / 0.5 GB |
| E. Neptune I/O | **stop rule: after the 10K pipeline run, read the measured Neptune I/O (CloudWatch/billing) and compare with 3M requests before any 50K run; if it is above 3M, do not run 50K without a new estimate and human approval.** The HIGH case uses 3M only because this rule exists; without it the estimate would keep 10M ($1.40 more) | human rule (checked before the 50K run) | none yet: no measurement exists; 3M is a bound set by the rule, not a measured value |

## 6. Pipeline (Step Functions + EMR)

State machine `scholarchain-pipeline` documents the **bounded pipeline** (not the asynchronous query path). There is no always-on EMR cluster and no EMR launch template: the cluster definition lives in the state machine, which creates it per execution.

1. `CreateCluster` (`elasticmapreduce:createCluster.sync`, 30 min timeout): release label from `emr_release_label` (or `emr-7.14.0` with `emr_hadoop_fallback`, section 6.2), application Spark (Hadoop on the fallback), **one primary + core/task nodes, never more than 3 nodes in total**: `m5.xlarge` on-demand (no Spot), initial 1 core (`emr_core_instance_count`, 1 or 2), managed scaling minimum 1 and maximum 2 core/task nodes (`emr_max_capacity_units`, validated 1 to 2 and at least the core count). **Why 2 and not 3:** AWS states the managed-scaling limits "only apply to the core and task nodes" and "the master node cannot be scaled after initial configuration" (https://docs.aws.amazon.com/emr/latest/APIReference/API_ComputeLimits.html and https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-managed-scaling.html, fetched 2026-11-24), so the primary is NOT in the limit; primary 1 + max 2 = 3 nodes, the guard limit. (The earlier maximum of 3 allowed up to 4 nodes.) `AutoTerminationPolicy` idle timeout **900 s**, `KeepJobFlowAliveWhenNoSteps = true` so steps can be added, logs to `s3://<logs>/emr/`. No spot task group.
2. `RunSteps` (Map, one at a time, `addStep.sync`, `command-runner.jar`).
Execution input: `{ "steps": [ { "name": "hadoop-parse", "args": ["spark-submit", "--class", "...", "s3://<artifacts>/jars/x.jar", "..."] } ] }`.
The state machine never terminates the cluster itself; EMR auto-termination does (see 6.1 for its limits). Execution timeout 4 h, per-step timeout 3 h. The managed-scaling maximum (core+task) and the core count are validated to 1 or 2, so with the primary the cluster has at most 3 nodes.

### 6.2 MapReduce fallback: emr-7.14.0 with the Hadoop application

**Default (no change from the plan): `emr-spark-8.1.0`, application `Spark`.** The human's read-only `describe-release-label` (us-east-2) lists the applications AmazonCloudWatchAgent, JupyterEnterpriseGateway, Livy and Spark for it, **not Hadoop**; the release guide (https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html, fetched 2026-11-24) lists `hadoop-mapred 3.4.2-amzn-3` ("MapReduce execution engine libraries"), `hadoop-yarn-resourcemanager` and `hadoop-yarn-nodemanager` as components. The earlier configuration requested `Hadoop` and `Spark` as applications, which would not match that list, so the default now requests only `Spark`. This changes only the RunJobFlow `Applications` parameter inside the existing state machine (no new resource; there is no applied state yet, so the first plan has the same resources).

**Fallback, only if the Phase 14 MapReduce smoke job fails on 8.1.0:** set `emr_hadoop_fallback = true` in the gitignored `terraform.tfvars`. It selects release label `emr-7.14.0` with applications `Hadoop` and `Spark`, and changes nothing else: same state machine, IAM, network, 3-node cap (1 primary + at most 2 core/task), idle timeout 900 s, on-demand. Validation (`modules/pipeline/variables.tf`): only `emr-spark-8.1.0` and `emr-7.14.0` are accepted, applications must be drawn from `Hadoop` and `Spark`, and `emr-7.14.0` is rejected without the Hadoop application (tested offline with `terraform console`: 8.1.0+Spark and 7.14.0+Hadoop,Spark accepted; 7.14.0+Spark, an unknown label and an unknown application rejected).

Switch procedure (human): (1) run the Phase 14 smoke job on 8.1.0 and record its result; (2) only if the MapReduce step fails for a release reason, decide, in writing, to switch; (3) set `emr_hadoop_fallback = true`; (4) `AWS_PROFILE=scholarchain AWS_REGION=us-east-2 bash scripts/plan.sh`, review that the only change is the state machine definition; (5) a human applies the saved plan; (6) rerun the smoke job. It is decided only after the smoke job, never before.

Facts checked (fetched 2026-11-24): `emr-7.14.0` exists (https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-7140-release.html and the 7.x versions page, newest 7.x). Its Hadoop is 3.4.2-amzn-2 and the page lists `hadoop-mapred 3.4.2-amzn-2` ("MapReduce execution engine libraries for running a MapReduce application") with YARN components, so the Hadoop application provides MapReduce. **Report only, versus the job JARs** (`project/Versions.scala`: hadoop 3.4.2, spark 4.1.1, scala213 2.13.17; `build.sbt`: Java release 17): Hadoop 3.4.2 matches in version; default Java for Hadoop/Spark on 7.14.0 is 17 (also 11 and 8 available), matching `--release 17`. **Spark on 7.14.0 is 3.5.8-amzn-0 with Scala 2.12.18, so Spark jobs built for Spark 4.1.1 / Scala 2.13.17 will NOT run on the fallback**; the fallback is for the Hadoop MapReduce steps only (the Spark steps would have to run on 8.1.0 in a separate execution). IAM does not depend on the release label: the EMR service role (managed policy plus the instance-profile PassRole), the instance profile role, and the Step Functions role's RunJobFlow (`*`, no resource-level support) never mention it; the label appears only in the RunJobFlow parameters (`modules/pipeline/main.tf`, checked by grep). **Cost effect: none** (the EMR uplift and EC2 prices are per instance type, not per release label in the price files); a failed 8.1.0 smoke job plus a re-run on 7.14.0 is the HIGH case's doubled smoke run, which is not in the current HIGH total (smoke is one run): add one smoke run, about $0.95, if the fallback is used.

### 6.1 What actually bounds a run (honest limits)

*Phase 13 note:* the pipeline script will own `ActionOnFailure = TERMINATE_CLUSTER` and a 1-hour step timeout; the state machine here still has `CANCEL_AND_WAIT` and 3 h. Not changed now; Phase 13 reconciles (section 13).

- **Per step:** the state machine's `AddStep` state has `TimeoutSeconds = step_timeout_seconds` (default 10,800 s = 3 h). When it fires, the state fails, but **Step Functions does not terminate the EMR cluster when an execution stops**: "Step Functions does not terminate an Amazon EMR cluster automatically if execution is stopped ... your cluster may continue running indefinitely, and can accrue additional charges" (https://docs.aws.amazon.com/step-functions/latest/dg/connect-emr.html, fetched 2026-11-24). Whether the timeout also cancels the running EMR step is not stated there: NOT VERIFIED.
- **Per execution:** `TimeoutSeconds = 14,400` (4 h) stops the execution, with the same non-termination caveat.
- **Idle auto-termination (900 s) does not fire during a step:** EMR counts a cluster as idle only when there are no active YARN applications and no pending steps (https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-auto-termination-policy.html, fetched 2026-11-24). A hung YARN application therefore keeps the cluster up.
- **So nothing enforces a hard stop for a hung run.** The real limits are the node cap (3 nodes, about $0.76 per hour for the whole cluster) and a human noticing: the budget alerts (which lag, section 7) and the dashboard. Exposure of an unattended hung cluster is about $0.76 per hour, $18 per day; **this is not included in the totals** below. *Proposals, NOT implemented, for the human:* (1) set `ActionOnFailure = TERMINATE_CLUSTER` on the step so a step failure ends the cluster (the current value is `CANCEL_AND_WAIT`); (2) a CloudWatch alarm on EMR `IsIdle`/cluster runtime; (3) a Step Functions Catch that calls `terminateCluster.sync` after a timeout (this needs `elasticmapreduce:TerminateJobFlows`, already granted to the state machine role).

## 7. Observability and cost controls

- Log groups (7-day retention): `/scholarchain/query-api`, `/retrieval`, `/embedding-worker`, `/sfn-pipeline`.
- Dashboard `scholarchain-overview`: ECS CPU and memory per service (`AWS/ECS`, free; with `enable_container_insights = true` the widget switches to desired and running task counts from Container Insights), SQS depth and age, ALB p95 latency and 5xx, DynamoDB UserErrors plus read/write throttles and SystemErrors for the runtime tables (QuerySession, EvidenceBundle, researcher-metrics), **explicit metrics only, no SEARCH**: the dashboard has 25 metrics in total (6 + 3 + 11 + 2 + 3), well under the 50-metric free-dashboard condition; the other tables are not charted, Neptune capacity, CPU and rolled-back transactions.
- Alarms (to SNS topic `scholarchain-alerts`, email subscription when `alert_email` is set): query-api target 5xx, DLQ depth greater than 0, embedding queue age above 600 s.
- **SNS topic encryption: NOT encrypted (human decision, repair round 2; this supersedes the earlier order to use `alias/aws/sns`).** The topic carries alarm notifications only (alarm name and state text; no data). The reason it is left unencrypted: the key policy of the AWS-managed key `alias/aws/sns` cannot be edited, so it cannot allow `cloudwatch.amazonaws.com` to `kms:GenerateDataKey*` and `kms:Decrypt`, the statement AWS documents for service publishers (https://docs.aws.amazon.com/sns/latest/dg/sns-key-management.html, read 2026-11-23). CloudWatch alarms are the only publishers in this design, so with that key every alarm email would be expected to go undelivered. That failure is **expected but NOT VERIFIED** (no page read states it outright; nothing was tested, there is no AWS access). AWS Budgets is unaffected: the budget emails its subscribers directly and does not use this topic. trivy AWS-0095 (unencrypted SNS topic, HIGH) is suppressed with `#trivy:ignore:AWS-0095` directly above the topic (`modules/observability/main.tf:13`) with this justification. Alternative if encryption is ever required: a customer-managed KMS key whose key policy allows `cloudwatch.amazonaws.com` (about $1 per month per key, not price-checked; trivy then reports AWS-0136 unless that key is used). Phase 14 smoke test: force one alarm and confirm the email arrives.
- **Container Insights is OFF by default** (`enable_container_insights = false`). What is lost: the ECS `DesiredTaskCount` and `RunningTaskCount` widget (those metrics exist only with Container Insights). Nothing else depends on it: autoscaling uses `ECSServiceAverageCPUUtilization`, ALB request count and the SQS queue depth; the alarms use ALB and SQS metrics. For an elasticity run the dashboard still shows per-service CPU and memory (these exist only while tasks run), SQS backlog and age (the embedding-worker's scaling signal), ALB p95 latency and 5xx; the **task count itself is not on the dashboard** (use the ECS service view or `describe-services` read-only). Turn Container Insights on for a window if the count must be charted.
- **Log ingestion is not capped.** CloudWatch has no native hard cap. What Terraform enforces: log-group retention 7 days (`modules/logs`), Step Functions logging level `ERROR` only, no Neptune audit or ECS exec logs, EMR logs go to S3 (14-day lifecycle) not CloudWatch. NOT enforced: a limit on bytes ingested per day. *Proposal, not implemented:* an `IncomingBytes` alarm per log group, $0.10 per alarm per month (price verified), about $0.40 for four groups.
- Budget `scholarchain-budget` (monthly period; AWS Budgets monthly periods end on the last day of the month, so the budget resets on 2026-12-01 while the project ends 2026-11-30): limit $25 (equal to the whole-project ceiling and never above it: validated), email alerts when actual spend exceeds **$5, $10 and $15** (`alert_email`). `cost_types` are explicit: **credits EXCLUDED** (`include_credit = false`), unblended (`use_blended = false`, `use_amortized = false`), refunds NOT netted (`include_refund = false`, conservative), discounts, tax, support and subscriptions included. Provider defaults were not relied on. Budgets lag: data refreshes up to three times a day, about 8 to 12 hours apart, and a notification can arrive after further spend (https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html, fetched 2026-11-24). A budget alert is notification only; it does not stop spending.

## 8. Outputs (names the application config needs)

`app_environment` (map of every variable in section 5), `raw_bucket`, `curated_bucket`, `reports_bucket`, `logs_bucket`, `artifacts_bucket`, `neptune_endpoint`, `neptune_reader_endpoint`, `neptune_bulk_load_role_arn`, `neptune_bulk_load_source_s3_uri`, `dynamodb_table_names`, `vector_bucket_name`, `vector_index_names`, `vector_dimension`, `embedding_queue_url`, `embedding_dlq_url`, `ecr_repository_urls`, `secret_arns` (ARNs only), `ecs_cluster_name`, `ecs_service_names`, `query_api_url`, `retrieval_internal_url`, `pipeline_state_machine_arn`, `task_role_arns`, `ecs_execution_role_arns` (map, one per service), `emr_service_role_arn`, `emr_instance_profile_arn`, `deployer_role_arn`, `vpc_id`, `public_subnet_ids`, `private_subnet_ids`, `dashboard_name`, `alerts_topic_arn`, `account_id`, `region`, and `live_suite_environment` (the `SCHOLARCHAIN_IT_*` values: `SCHOLARCHAIN_IT_S3_BUCKET` = the curated bucket, probe objects land under `it/` and expire in 7 days; `SCHOLARCHAIN_IT_VECTOR_DIMENSION`; `SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT`; `SCHOLARCHAIN_IT_RESEARCHER_ID` is a data value the human chooses).

## 9. Human runbook (after the plan is approved)

1. Copy `terraform.tfvars.example` to `terraform.tfvars` (gitignored) and fill the required `expected_account_id` (12 digits), `owner`, `expiration_date`, `alert_email` and the required `alb_ingress_cidrs` (your /32).
2. `AWS_PROFILE=scholarchain bash scripts/plan.sh` (read-only). Review the summary block and the saved plan.
3. After approval a human applies the saved plan (apply is never run by an agent or a script).
4. Confirm the AWS Budgets and SNS email subscriptions (AWS sends confirmation emails).
5. Set the four secret values (human only, outside Terraform and outside the repository; nothing is committed): `scholarchain/voyage-api-key`, `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY`. For each, in your own terminal with your own credentials, write the value to a mode-600 temporary file outside the repository, store it with `aws secretsmanager put-secret-value --secret-id <name> --secret-string file://<temp file>`, then delete the temporary file. Do not paste a value on a command line or into any report. Generate the two internal keys randomly (they are shared secrets between query-api and retrieval, and the services key is what callers present). **A task fails to start while any secret it injects is still empty** (ECS cannot resolve the value): query-api needs three, retrieval three, embedding-worker one. Set all of them before raising `ecs_min_tasks`.
6. Scale up services only through `ecs_min_tasks`. Before scaling up, the human sets all four secret values (step 5): while a secret is empty the tasks that inject it fail to start (query-api needs `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY`; retrieval needs `SCHOLARCHAIN_VOYAGE_API_KEY`, `SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY`; embedding-worker needs `SCHOLARCHAIN_VOYAGE_API_KEY`), and with Neptune off the core-config services also fail (open point O1).
7. The budget is MONTHLY ($25 limit, actual-spend alerts at $5/$12/$20). A project spanning two billing months can exceed $25 in total without any single month alert reaching the limit: track cumulative spend yourself.
8. Cleanup: `bash scripts/cleanup.sh` lists and prints commands; a human runs them in the printed order. Evidence posture after cleanup: the `raw` and `reports` buckets and their seven Terraform sub-resources each (bucket, public-access block, ownership controls, SSE configuration, versioning, lifecycle, TLS-only policy) are first removed from Terraform state by a human-only command, so teardown cannot delete the buckets or strip their settings. They remain in AWS intact (public access blocked, TLS-only, versioned, encrypted) but are no longer managed by Terraform; re-importing them or deleting them is a separate human data decision. Do not run teardown if the state file has been lost or replaced.

## 9-plan. What `scripts/plan.sh` accepts (repair round 2)

plan.sh refuses, before any AWS call or planning, every input it cannot parse the same way Terraform does:
- Variable files: only `terraform.tfvars` is accepted, as a regular file (not a symlink), in the simple form `name = "value"` with at most one assignment per name. Any `*.tfvars.json`, `*.auto.tfvars` or `*.auto.tfvars.json` in the environment directory is refused (symlinks included). `terraform.tfvars` is also refused if it has a byte-order mark, a block comment (`/*`, `*/`) or a heredoc (`<<`); `#` line comments are fine.
- Configuration: any `override.tf`, `override.tf.json`, `*_override.tf`, `*_override.tf.json`, any `*.tf.json`, or a symlinked `.tf` file in the environment directory or in the local modules is refused.
- Environment: `TF_WORKSPACE`, `TF_DATA_DIR` and any `TF_CLI_ARGS*` variable set to anything (even empty) are refused; a non-default workspace in `.terraform/environment` is refused. A set-but-empty `TF_VAR_expected_account_id` or `TF_VAR_aws_region` is refused (Terraform would treat it as the empty string overriding the default).
- Value precedence it uses for the two guard variables: default in `variables.tf` < `TF_VAR_<name>` < `terraform.tfvars`. It loads the variables first, compares the caller's effective Region, then makes the read-only STS call and compares the account, all before `terraform init` and `plan`.
- After planning it re-reads the variables Terraform actually used (`terraform show -json`) and, if the account or Region differs from the caller, deletes the plan file and exits non-zero. Account IDs are never printed (messages and the SC-SUMMARY block carry only `account_check=match`). The nested `.gitignore` files (`infra/terraform/.gitignore` for `*.tfvars.json`, `infra/terraform/plans/.gitignore` for plan output) keep these files out of git; the root `.gitignore` is build-engineer's and was not changed.

## 9a. Experiment windows: switching Neptune and the load balancer on and off

Both default to **false** (human decision 1). In the gitignored `terraform.tfvars` (or as `-var` on plan):

```
create_neptune = true     # Neptune cluster + loader role association
create_alb     = true     # load balancer, target group, listener, request-count scaling policy
```

Plan and review, then a human applies; set them back to `false` afterwards and apply again. What changes:

- Neptune on: the two `SCHOLARCHAIN_STORAGE_NEPTUNE_*` variables above appear in `app_environment` and in every ECS task definition (they are absent, not blank, while Neptune is off); outputs `neptune_endpoint` (`https://<endpoint>:8182`), `neptune_reader_endpoint`, `neptune_bulk_load_role_arn` become non-empty; the ECS services' `SCHOLARCHAIN_STORAGE_NEPTUNE_*` variables are filled (task definitions are replaced, so services roll); the runtime roles gain the `neptune-db` statements; the dashboard gains two Neptune widgets. Cost: about $0.16 per hour.
- Neptune off again: needs `neptune_deletion_protection=false` applied first (cleanup flow), skip_final_snapshot defaults true so the graph is dropped and must be reloaded from S3 on the next window. This is data removal and a human decision.
- ALB on: output `query_api_url` becomes non-empty; the alarm on 5xx and the latency widget appear. Cost: about $0.04 per hour (ALB, one LCU assumption, 2 public IPv4).
- With both on and nothing else running, idle cost is the (a) figure below.

## 9b. Scale-down procedure (human)

Defaults (confirmed): every service is created with `desired_count = 0` (`modules/compute/main.tf:106`), the autoscaling minimum is 0 for all three services (`environments/course/variables.tf`, `ecs_min_tasks`) and `ignore_changes = [desired_count]` leaves the running count to Application Auto Scaling (`modules/compute/main.tf:147-149`). Why 0: a Fargate task costs money every hour whether it serves traffic or not (about $0.017 per task-hour including its public IPv4, about $0.42 per task-day), and nothing needs to run outside an experiment window.

Reviewer concern (M-3): after a scale-up, lowering `ecs_min_tasks` back to 0 may leave one task running, because Terraform ignores `desired_count` and target tracking only scales in as metrics allow. AWS documents the relevant behavior: "If a service's desired count is set below its minimum capacity value, and an alarm initiates a scale-out activity, Service Auto Scaling scales the desired count up to the minimum" and "a scale-in activity does not adjust the desired count, because it is already below the minimum" (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-auto-scaling.html, read 2026-11-23): lowering the minimum does not by itself reduce a running service. And for `RegisterScalableTarget`: "If it's below the minimum capacity or above the maximum capacity, Application Auto Scaling adjusts the capacity of the scalable target to place it within these bounds" (https://docs.aws.amazon.com/autoscaling/application/APIReference/API_RegisterScalableTarget.html, read 2026-11-23). So setting the maximum to 0 is the documented way to force the count down. Terraform's `aws_appautoscaling_target` re-registers the target when `ecs_max_tasks` changes.

Steps (human, own credentials):
1. In `terraform.tfvars` set `ecs_min_tasks = { "query-api" = 0, "retrieval" = 0, "embedding-worker" = 0 }` and `ecs_max_tasks = { "query-api" = 0, "retrieval" = 0, "embedding-worker" = 0 }` (variable validation accepts 0 to 4 and requires max >= min per service). This covers the embedding-worker too.
2. Run `AWS_PROFILE=scholarchain bash scripts/plan.sh`, review that the only changes are the three scalable targets, and have a human apply the saved plan.
3. Verify the running count is 0 for each service, for example with the read-only call `aws ecs describe-services --cluster scholarchain-cluster --services query-api retrieval embedding-worker --query "services[].[serviceName,desiredCount,runningCount]"`, repeated until every `runningCount` is 0 (tasks drain; allow a few minutes). Also confirm in the ECS console that there are no tasks.
4. To scale up again, restore `ecs_max_tasks` first (to at most 4), then raise `ecs_min_tasks`.

**NOT VERIFIED:** that the Application Auto Scaling API accepts `MaxCapacity = 0` for an ECS service (the API page read does not state a lower bound); and that the count reaches 0 without further action (not testable without AWS). If the call is rejected, or a task is still running after a few minutes, stop and escalate to the human and the orchestrator; changing the desired count outside Terraform is a manual console or CLI mutation that this plan does not endorse.

## 10. Cost estimate (US East (Ohio) us-east-2; prices fetched 2026-11-23/24; cost-only round 2026-11-24)

Account is on the **Paid plan**, credits carry over; the human's spend limit is **$20** (counts usage before credits and pauses the project when hit). Whole-project ceiling **$25** (AWS before credits plus all external APIs). Aim: HIGH about $15, hard stop above $18. Fixed items are shown for cleanup on 2026-11-27 (4 days) and for slipping to 2026-11-30 (7 days). Computed by `estimate3.py` (kept in the work-order scratch space, not the repository; observed output quoted in the report). Every row is quantity x price with an "Enforced by" column (Terraform, AWS or human rule). Runtimes and counts are assumptions, labelled; the operating rules of section 5b bound several of them.

### Result (AWS before credits)

**Human decisions recorded 2026-11-24:** the HIGH figure of $17.04 is **accepted by the human**. Operating rules A, C, D and E are **human-enforced, not enforced by Terraform or AWS**. The IAM set is approved by the human, including the three per-service ECS execution roles. trivy AWS-0034 (Container Insights off) is accepted and stays visible. The answer model stays Haiku 4.5 (`generation_model_id` unchanged; the estimate still prices Haiku 5.5). The LOW findings stay deferred.

| Case | Cleanup 2026-11-27 | Slip to 2026-11-30 | vs $20 spend limit | vs $15 alert |
|---|---|---|---|---|
| LOW | **$4.48** | **$4.68** | $15.32 headroom | below |
| HIGH (3 nodes in every EMR run, one 50K run, 15-min tails, Neptune at 2.5 NCU for all 8 window hours, free tiers NOT assumed, secrets a full month, dashboard priced, operating rules A, C, D, E followed) | **$16.95** | **$17.04** | $2.96 headroom (7-day) | **over the $15 alert by $2.04**, so the alert will fire |

HIGH is within the $18 stop line ($0.96 under, 7-day) but above the $15 aim. AWS HIGH plus about $3 external APIs: **$20.04 against the $25 ceiling** ($4.96 headroom). Haiku 5.5 is AWS-billed on Bedrock and is inside the AWS figure; the ~$3 external line is only for non-AWS services, so Haiku is not counted twice.

HIGH drivers (7-day $17.04): fixed items $5.55 (dashboard $3.00, secrets full month $1.60, alarms $0.30, logs $0.25, SQS/SFN/transfer $0.31, ECR/Cloud Map $0.09), EMR $4.76 (50K single run $2.47, 10K $1.33, smoke $0.95), Neptune $3.89 (20 NCU-hours at 2.5 NCU $3.22, I/O 3M requests $0.60, storage $0.07), Bedrock $1.01 (Haiku 5.5 $0.89, Titan $0.12), Fargate $0.97, S3 $0.40, ALB $0.32, DynamoDB $0.11.

**If a rule is NOT followed (sensitivity, not in the totals):** I/O 10M instead of 3M: $18.44 (+$1.40); windows 14 h instead of 8 h: $19.69 (+$2.66); Fargate 112 task-hours: $18.01 (+$0.97); logs 2 GB: $17.79 (+$0.75); a second 50K run: $19.51 (+$2.47); all five broken together: **$25.29**, over the $20 spend limit. The project pauses at $20 of usage, so the rules matter. A hung EMR cluster adds $0.76 per hour (section 6.1) and is not included.

### Allowances: findings for a Paid-plan account (AWS pages, fetched 2026-11-24)

- **Plans** (https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier.html and https://aws.amazon.com/free/): "30+ AWS services are always free within monthly usage limits on both the Free and Paid plans"; "Paid plan accounts might have Short-term trial and Always Free offerings active"; credits apply beyond the limits. So an always-free allowance **is not lost on upgrade and is independent of the credit balance**. The service list behind "30+" is rendered by script and could not be retrieved, so whether each of the following is on it is **not confirmed by that page**.
- **Step Functions** (https://aws.amazon.com/step-functions/pricing/): 4,000 transitions per month, "does not automatically expire ... available to both existing and new AWS customers indefinitely": **always-free, confirmed**. Used in LOW and HIGH ($0.005 in HIGH anyway).
- **SQS** (https://aws.amazon.com/sqs/pricing/): "All customers can make 1 million Amazon SQS requests for free each month", computed across regions: no term stated, account-wide (may already be used). Treated as always-free in LOW; **priced in HIGH** because the "30+ services" list could not be checked.
- **CloudWatch** (https://aws.amazon.com/cloudwatch/pricing/): free tier 3 custom dashboards (up to 50 metrics each), 10 alarm metrics, 5 GB logs, 10 custom metrics, "Paid tier: no upfront commitment". No term or plan is stated for the free tier, usage is per account. LOW uses the free tier; **HIGH prices it**. **Dashboard price beyond the free three: $3.00 per dashboard per month**, from the CloudWatch price API (https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/cloudwatch/USD/current/cloudwatch.json, region "Any", "Basic Dashboard", the same rate code the pricing page uses for "per dashboard per month"), fetched 2026-11-24.
- **Data transfer out** (https://aws.amazon.com/ec2/pricing/on-demand/): 100 GB per month free, aggregated across services and Regions (account-wide, may already be used): LOW free, HIGH priced at $0.09 per GB (AWSDataTransfer price file).
- **Dashboard within the free condition:** the DynamoDB widget formerly used `SEARCH` and could have exceeded 50 metrics. It now uses explicit metrics: the whole dashboard has **25 metrics** (checked offline with `terraform console`: ECS 6, SQS 3, DynamoDB 11, ALB 2, Neptune 3; no SEARCH expression). What is lost: DynamoDB errors for the other tables, and ECS task counts unless Container Insights is on. The elasticity view stays: per-service CPU and memory, SQS backlog and age, ALB latency, and ECS counts when Container Insights is enabled for a window.

### Haiku 5.5 on Bedrock

Price, `AmazonBedrockFoundationModels` price file for us-east-2 (publication 2026-11-23): Claude Haiku 5.5 Standard (in-region) input $0.11 per million tokens, output $0.55 per million (Global $0.10 and $0.50; long context $0.55 and $2.75). 200 calls maximum (plan limit; **not enforced by infrastructure**). HIGH: 30,000 input and 2,048 output tokens per call (model-runtime budgets in `RUNTIME-AGENTS.md` on main; output cap 8,192 would cost $1.56 in total instead of $0.89); LOW: 8,000 and 1,000. The Terraform `generation_model_id` stays the Haiku 4.5 default (unchanged by order); the estimate prices 5.5. Inference-profile need: NOT VERIFIED (section 11 item 2).

### Time bounds and what enforces them (section 6.1)

EMR runs are priced at an assumed bound: smoke 1.0 h, 10K 1.5 h, 50K 3.0 h on 3 nodes (LOW 0.5 h, 0.75 h, 1.5 h on 2 nodes) plus a 15-minute idle tail. The per-step (3 h) and execution (4 h) timeouts fail the state but do not terminate a running cluster, and idle termination does not fire during a step; the bound is an assumption plus human vigilance.

### Price status (us-east-2 files fetched 2026-11-23/24)

| Item | Price used | Status |
|---|---|---|
| Neptune Serverless | $0.1608 per NCU-hour; $0.10 per GB-month; $0.20 per million I/O | verified us-east-2 (AmazonNeptune, 2026-09-11) |
| ALB; public IPv4 | $0.0225 per hour + $0.008 per LCU-hour; $0.005 per hour | verified (AWSELB 2026-11-19; AmazonVPC 2026-11-23) |
| EC2 m5.xlarge; EMR uplift; EBS gp2 | $0.192; $0.048 per hour; $0.10 per GB-month | verified (AmazonEC2 2026-11-23; ElasticMapReduce 2026-11-17) |
| Fargate | $0.04048 per vCPU-hour, $0.004445 per GB-hour | verified (AmazonECS, 2026-11-11) |
| DynamoDB on-demand | $0.625 per million writes, $0.125 per million reads | verified (2026-11-11) |
| Secrets Manager; alarm; Logs ingestion; dashboard | $0.40 per secret-month; $0.10 per alarm-month; $0.50 per GB; $3.00 per dashboard-month | verified (2026-11-11; AmazonCloudWatch 2026-11-19; CloudWatch price API 2026-11-24) |
| Step Functions; ECR; Cloud Map; SQS; data transfer out | $0.000025 per transition; $0.10 per GB-month; $0.10 per resource-month; $0.0000004 per request; $0.09 per GB | verified us-east-2 (2026-11-04, 11-13, 11-06, 11-11, 11-19) |
| S3 Standard; S3 Vectors | $0.023 per GB-month, PUT $0.000005, GET $0.0000004; Vectors $0.06 per GB-month, PUT $0.20 per GB, query $0.0000025 | verified (S3 price API, Ohio, 2026-11-23); S3 Vectors approved by the human |
| Titan Text Embeddings V2; Claude Haiku 5.5 | $0.00002 per 1K input tokens; $0.11 in / $0.55 out per million | verified in the price files (2026-11-23); invocability not verified |
| NAT gateway, interface endpoints, customer-managed KMS, OpenSearch | none created | design |

### Detailed rows

**LOW, cleanup 2026-11-27 (4 days):**

| Group | Line (quantity x price) | Quantity | Unit price | USD | Enforced by | Source / status |
|---|---|---|---|---|---|---|
| EMR | Phase 14 EMR smoke: 1 x (0.5 h run + 0.25 h idle tail) x 2 nodes | 1.5 node-h | $0.2537671233 | 0.38 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 10K pipeline: 1 x (0.75 h run + 0.25 h idle tail) x 2 nodes | 2 node-h | $0.2537671233 | 0.51 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 50K pipeline (single run): 1 x (1.5 h run + 0.25 h idle tail) x 2 nodes | 3.5 node-h | $0.2537671233 | 0.89 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| ALB | ALB hours in windows (1 LCU, 2 IPv4) | 6 h | $0.0405 | 0.24 | human rule (create_alb toggle, 8 h total with Neptune) | verified us-east-2 |
| Neptune | Neptune NCU-hours: 6 h x 1.0 NCU | 6 NCU-h | $0.1608 | 0.96 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified us-east-2; HIGH at the lowest legal max 2.5 NCU all window hours |
| Neptune | Neptune storage (prorated) | 0.13 GB-mo | $0.1 | 0.01 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified; GB assumed |
| Neptune | Neptune I/O requests | 1,000,000 IO | $0.0000002 | 0.20 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified price; count ASSUMED (largest uncertainty) |
| Fargate | Fargate task-hours incl. public IPv4 | 24 task-h | $0.0173425 | 0.42 | Terraform (desired 0, max 4 per service) + human rule (56 task-hours) | verified us-east-2 |
| DynamoDB | analytics load: 35,304 items x 1 KB x 1 load(s) (loaded tables only) | 35,304 WRU | $0.000000625 | 0.02 | human rule (load only the 6 read tables) | verified; item counts from phase-07 report; item size ASSUMED; reload = conditional put, still billed |
| DynamoDB | embedding claims (10K docs x 1 WRU x loads) + ledger/provenance writes | 12,000 WRU | $0.000000625 | 0.01 | human rule (load only the 6 read tables) | verified; counts from phase-08 contract (claim per document) |
| DynamoDB | runtime QuerySession/EvidenceBundle writes (200 queries) | 1,000 WRU | $0.000000625 | 0.00 | human rule (load only the 6 read tables) | verified; sizes ASSUMED |
| DynamoDB | runtime analytics reads (200 queries) | 6,000 RRU | $0.000000125 | 0.00 | human rule (load only the 6 read tables) | verified; counts ASSUMED; Phase 10 reads unverified |
| S3 | S3 storage (prorated; raw ~2.7 GB + outputs) | 0.53 GB-mo | $0.023 | 0.01 | human rule (few large objects) | verified (S3 price API) |
| S3 | S3 PUT/COPY/POST/LIST | 5,000 req | $0.000005 | 0.03 | human rule (few large objects) | verified price; count ASSUMED (enrichment cache as a few large objects, see section 5) |
| S3 | S3 GET | 50,000 req | $0.0000004 | 0.02 | human rule (few large objects) | verified price; count ASSUMED |
| S3 Vectors | storage + PUT + queries + data processed (10K x 1024 float32) | 1 lump | $0.025720292 | 0.03 | Terraform (6 indexes) + human rule | verified (S3 price API); human OK for S3 Vectors |
| Bedrock | Titan Text Embeddings V2 input tokens (AWS-billed) | 4,000 1K tok | $0.00002 | 0.08 | human rule (200 calls; nothing in infra) | verified us-east-2; tokens/doc ASSUMED; reruns skip unchanged docs (12.3) |
| Bedrock | Claude Haiku 5.5 in: 200 calls x 8,000 tok (AWS-billed) | 1.6 M tok | $0.11 | 0.18 | human rule (200 calls; nothing in infra) | AmazonBedrockFoundationModels us-east-2 2026-11-23, Standard (in-region) input; HIGH = config input budget 30,000 |
| Bedrock | Claude Haiku 5.5 out: 200 calls x 1,000 tok | 0.2 M tok | $0.55 | 0.11 | human rule (200 calls; nothing in infra) | same file, Standard output; HIGH = config default output 2,048 (cap 8,192) |
| Fixed | Secrets Manager 4 x 4/30 month | 0.53 secret-mo | $0.4 | 0.21 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified; proration by hours stated on the Secrets Manager pricing page 2026-11-24 |
| Fixed | CloudWatch alarms 3 x 0 (10 alarm metrics in the CloudWatch free tier) | 0 alarm-mo | $0.1 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; free tier from the CloudWatch pricing page, always-free vs plan-limited NOT stated, account-wide |
| Fixed | CloudWatch dashboard x 0 (3 free dashboards, <=50 metrics each) | 0 dash-mo | $3 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | price verified (CloudWatch price API, 2026-11-24); free tier 3 dashboards x 50 metrics: always-free status NOT confirmed so HIGH prices it; dashboard now has explicit metrics, 25 total |
| Fixed | ECR storage (prorated) | 0.13 GB-mo | $0.1 | 0.01 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | CloudWatch Logs ingestion (Container Insights off; 5 GB in free tier NOT assumed) | 0.3 GB | $0.5 | 0.15 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; volume ASSUMED |
| Fixed | Cloud Map resource (1, prorated) | 0.13 res-mo | $0.1 | 0.01 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | Step Functions transitions (HIGH: free tier not assumed) | 0 trans | $0.000025 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; 4,000 free/month stated as available indefinitely (pricing page 2026-11-24) |
| Fixed | SQS requests (HIGH: free 1M not assumed) | 0 req | $0.0000004 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 standard Tier1 price; 1M free per month account-wide (SQS pricing page) |
| Fixed | Internet data transfer out (HIGH: 100 GB free not assumed) | 0 GB | $0.09 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 (AWSDataTransfer, first 10 TB beyond the free 100 GB) |
| | **TOTAL** | | | **4.48** | | |

**LOW, slip to 2026-11-30 (7 days):**

| Group | Line (quantity x price) | Quantity | Unit price | USD | Enforced by | Source / status |
|---|---|---|---|---|---|---|
| EMR | Phase 14 EMR smoke: 1 x (0.5 h run + 0.25 h idle tail) x 2 nodes | 1.5 node-h | $0.2537671233 | 0.38 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 10K pipeline: 1 x (0.75 h run + 0.25 h idle tail) x 2 nodes | 2 node-h | $0.2537671233 | 0.51 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 50K pipeline (single run): 1 x (1.5 h run + 0.25 h idle tail) x 2 nodes | 3.5 node-h | $0.2537671233 | 0.89 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| ALB | ALB hours in windows (1 LCU, 2 IPv4) | 6 h | $0.0405 | 0.24 | human rule (create_alb toggle, 8 h total with Neptune) | verified us-east-2 |
| Neptune | Neptune NCU-hours: 6 h x 1.0 NCU | 6 NCU-h | $0.1608 | 0.96 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified us-east-2; HIGH at the lowest legal max 2.5 NCU all window hours |
| Neptune | Neptune storage (prorated) | 0.23 GB-mo | $0.1 | 0.02 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified; GB assumed |
| Neptune | Neptune I/O requests | 1,000,000 IO | $0.0000002 | 0.20 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified price; count ASSUMED (largest uncertainty) |
| Fargate | Fargate task-hours incl. public IPv4 | 24 task-h | $0.0173425 | 0.42 | Terraform (desired 0, max 4 per service) + human rule (56 task-hours) | verified us-east-2 |
| DynamoDB | analytics load: 35,304 items x 1 KB x 1 load(s) (loaded tables only) | 35,304 WRU | $0.000000625 | 0.02 | human rule (load only the 6 read tables) | verified; item counts from phase-07 report; item size ASSUMED; reload = conditional put, still billed |
| DynamoDB | embedding claims (10K docs x 1 WRU x loads) + ledger/provenance writes | 12,000 WRU | $0.000000625 | 0.01 | human rule (load only the 6 read tables) | verified; counts from phase-08 contract (claim per document) |
| DynamoDB | runtime QuerySession/EvidenceBundle writes (200 queries) | 1,000 WRU | $0.000000625 | 0.00 | human rule (load only the 6 read tables) | verified; sizes ASSUMED |
| DynamoDB | runtime analytics reads (200 queries) | 6,000 RRU | $0.000000125 | 0.00 | human rule (load only the 6 read tables) | verified; counts ASSUMED; Phase 10 reads unverified |
| S3 | S3 storage (prorated; raw ~2.7 GB + outputs) | 0.93 GB-mo | $0.023 | 0.02 | human rule (few large objects) | verified (S3 price API) |
| S3 | S3 PUT/COPY/POST/LIST | 5,000 req | $0.000005 | 0.03 | human rule (few large objects) | verified price; count ASSUMED (enrichment cache as a few large objects, see section 5) |
| S3 | S3 GET | 50,000 req | $0.0000004 | 0.02 | human rule (few large objects) | verified price; count ASSUMED |
| S3 Vectors | storage + PUT + queries + data processed (10K x 1024 float32) | 1 lump | $0.026020292 | 0.03 | Terraform (6 indexes) + human rule | verified (S3 price API); human OK for S3 Vectors |
| Bedrock | Titan Text Embeddings V2 input tokens (AWS-billed) | 4,000 1K tok | $0.00002 | 0.08 | human rule (200 calls; nothing in infra) | verified us-east-2; tokens/doc ASSUMED; reruns skip unchanged docs (12.3) |
| Bedrock | Claude Haiku 5.5 in: 200 calls x 8,000 tok (AWS-billed) | 1.6 M tok | $0.11 | 0.18 | human rule (200 calls; nothing in infra) | AmazonBedrockFoundationModels us-east-2 2026-11-23, Standard (in-region) input; HIGH = config input budget 30,000 |
| Bedrock | Claude Haiku 5.5 out: 200 calls x 1,000 tok | 0.2 M tok | $0.55 | 0.11 | human rule (200 calls; nothing in infra) | same file, Standard output; HIGH = config default output 2,048 (cap 8,192) |
| Fixed | Secrets Manager 4 x 7/30 month | 0.93 secret-mo | $0.4 | 0.37 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified; proration by hours stated on the Secrets Manager pricing page 2026-11-24 |
| Fixed | CloudWatch alarms 3 x 0 (10 alarm metrics in the CloudWatch free tier) | 0 alarm-mo | $0.1 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; free tier from the CloudWatch pricing page, always-free vs plan-limited NOT stated, account-wide |
| Fixed | CloudWatch dashboard x 0 (3 free dashboards, <=50 metrics each) | 0 dash-mo | $3 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | price verified (CloudWatch price API, 2026-11-24); free tier 3 dashboards x 50 metrics: always-free status NOT confirmed so HIGH prices it; dashboard now has explicit metrics, 25 total |
| Fixed | ECR storage (prorated) | 0.23 GB-mo | $0.1 | 0.02 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | CloudWatch Logs ingestion (Container Insights off; 5 GB in free tier NOT assumed) | 0.3 GB | $0.5 | 0.15 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; volume ASSUMED |
| Fixed | Cloud Map resource (1, prorated) | 0.23 res-mo | $0.1 | 0.02 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | Step Functions transitions (HIGH: free tier not assumed) | 0 trans | $0.000025 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; 4,000 free/month stated as available indefinitely (pricing page 2026-11-24) |
| Fixed | SQS requests (HIGH: free 1M not assumed) | 0 req | $0.0000004 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 standard Tier1 price; 1M free per month account-wide (SQS pricing page) |
| Fixed | Internet data transfer out (HIGH: 100 GB free not assumed) | 0 GB | $0.09 | 0.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 (AWSDataTransfer, first 10 TB beyond the free 100 GB) |
| | **TOTAL** | | | **4.68** | | |

**HIGH, cleanup 2026-11-27 (4 days):**

| Group | Line (quantity x price) | Quantity | Unit price | USD | Enforced by | Source / status |
|---|---|---|---|---|---|---|
| EMR | Phase 14 EMR smoke: 1 x (1.0 h run + 0.25 h idle tail) x 3 nodes | 3.75 node-h | $0.2537671233 | 0.95 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 10K pipeline: 1 x (1.5 h run + 0.25 h idle tail) x 3 nodes | 5.25 node-h | $0.2537671233 | 1.33 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 50K pipeline (single run, bounded 3 h): 1 x (3.0 h run + 0.25 h idle tail) x 3 nodes | 9.75 node-h | $0.2537671233 | 2.47 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| ALB | ALB hours in windows (1 LCU, 2 IPv4) | 8 h | $0.0405 | 0.32 | human rule (create_alb toggle, 8 h total with Neptune) | verified us-east-2 |
| Neptune | Neptune NCU-hours: 8 h x 2.5 NCU | 20 NCU-h | $0.1608 | 3.22 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified us-east-2; HIGH at the lowest legal max 2.5 NCU all window hours |
| Neptune | Neptune storage (prorated) | 0.4 GB-mo | $0.1 | 0.04 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified; GB assumed |
| Neptune | Neptune I/O requests | 3,000,000 IO | $0.0000002 | 0.60 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified price; count ASSUMED (largest uncertainty) |
| Fargate | Fargate task-hours incl. public IPv4 | 56 task-h | $0.0173425 | 0.97 | Terraform (desired 0, max 4 per service) + human rule (56 task-hours) | verified us-east-2 |
| DynamoDB | analytics load: 35,304 items x 2 KB x 2 load(s) (loaded tables only) | 141,216 WRU | $0.000000625 | 0.09 | human rule (load only the 6 read tables) | verified; item counts from phase-07 report; item size ASSUMED; reload = conditional put, still billed |
| DynamoDB | embedding claims (10K docs x 1 WRU x loads) + ledger/provenance writes | 22,000 WRU | $0.000000625 | 0.01 | human rule (load only the 6 read tables) | verified; counts from phase-08 contract (claim per document) |
| DynamoDB | runtime QuerySession/EvidenceBundle writes (200 queries) | 5,000 WRU | $0.000000625 | 0.00 | human rule (load only the 6 read tables) | verified; sizes ASSUMED |
| DynamoDB | runtime analytics reads (200 queries) | 10,000 RRU | $0.000000125 | 0.00 | human rule (load only the 6 read tables) | verified; counts ASSUMED; Phase 10 reads unverified |
| S3 | S3 storage (prorated; raw ~2.7 GB + outputs) | 1.07 GB-mo | $0.023 | 0.02 | human rule (few large objects) | verified (S3 price API) |
| S3 | S3 PUT/COPY/POST/LIST | 40,000 req | $0.000005 | 0.20 | human rule (few large objects) | verified price; count ASSUMED (enrichment cache as a few large objects, see section 5) |
| S3 | S3 GET | 400,000 req | $0.0000004 | 0.16 | human rule (few large objects) | verified price; count ASSUMED |
| S3 Vectors | storage + PUT + queries + data processed (10K x 1024 float32) | 1 lump | $0.03370073 | 0.03 | Terraform (6 indexes) + human rule | verified (S3 price API); human OK for S3 Vectors |
| Bedrock | Titan Text Embeddings V2 input tokens (AWS-billed) | 6,000 1K tok | $0.00002 | 0.12 | human rule (200 calls; nothing in infra) | verified us-east-2; tokens/doc ASSUMED; reruns skip unchanged docs (12.3) |
| Bedrock | Claude Haiku 5.5 in: 200 calls x 30,000 tok (AWS-billed) | 6 M tok | $0.11 | 0.66 | human rule (200 calls; nothing in infra) | AmazonBedrockFoundationModels us-east-2 2026-11-23, Standard (in-region) input; HIGH = config input budget 30,000 |
| Bedrock | Claude Haiku 5.5 out: 200 calls x 2,048 tok | 0.41 M tok | $0.55 | 0.23 | human rule (200 calls; nothing in infra) | same file, Standard output; HIGH = config default output 2,048 (cap 8,192) |
| Fixed | Secrets Manager 4 x full month | 4 secret-mo | $0.4 | 1.60 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified; proration by hours stated on the Secrets Manager pricing page 2026-11-24 |
| Fixed | CloudWatch alarms 3 x full month (free tier not assumed) | 3 alarm-mo | $0.1 | 0.30 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; free tier from the CloudWatch pricing page, always-free vs plan-limited NOT stated, account-wide |
| Fixed | CloudWatch dashboard x full month | 1 dash-mo | $3 | 3.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | price verified (CloudWatch price API, 2026-11-24); free tier 3 dashboards x 50 metrics: always-free status NOT confirmed so HIGH prices it; dashboard now has explicit metrics, 25 total |
| Fixed | ECR storage (prorated) | 0.4 GB-mo | $0.1 | 0.04 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | CloudWatch Logs ingestion (Container Insights off; 5 GB in free tier NOT assumed) | 0.5 GB | $0.5 | 0.25 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; volume ASSUMED |
| Fixed | Cloud Map resource (1, prorated) | 0.13 res-mo | $0.1 | 0.01 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | Step Functions transitions (HIGH: free tier not assumed) | 200 trans | $0.000025 | 0.01 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; 4,000 free/month stated as available indefinitely (pricing page 2026-11-24) |
| Fixed | SQS requests (HIGH: free 1M not assumed) | 300,000 req | $0.0000004 | 0.12 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 standard Tier1 price; 1M free per month account-wide (SQS pricing page) |
| Fixed | Internet data transfer out (HIGH: 100 GB free not assumed) | 2 GB | $0.09 | 0.18 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 (AWSDataTransfer, first 10 TB beyond the free 100 GB) |
| | **TOTAL** | | | **16.95** | | |

**HIGH, slip to 2026-11-30 (7 days):**

| Group | Line (quantity x price) | Quantity | Unit price | USD | Enforced by | Source / status |
|---|---|---|---|---|---|---|
| EMR | Phase 14 EMR smoke: 1 x (1.0 h run + 0.25 h idle tail) x 3 nodes | 3.75 node-h | $0.2537671233 | 0.95 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 10K pipeline: 1 x (1.5 h run + 0.25 h idle tail) x 3 nodes | 5.25 node-h | $0.2537671233 | 1.33 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| EMR | Phase 15 50K pipeline (single run, bounded 3 h): 1 x (3.0 h run + 0.25 h idle tail) x 3 nodes | 9.75 node-h | $0.2537671233 | 2.47 | AWS (node cap 3, validated in Terraform) + human rule (hours) | EC2 0.192 + EMR 0.048 + EBS 64 GB + IPv4 0.005; verified us-east-2 |
| ALB | ALB hours in windows (1 LCU, 2 IPv4) | 8 h | $0.0405 | 0.32 | human rule (create_alb toggle, 8 h total with Neptune) | verified us-east-2 |
| Neptune | Neptune NCU-hours: 8 h x 2.5 NCU | 20 NCU-h | $0.1608 | 3.22 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified us-east-2; HIGH at the lowest legal max 2.5 NCU all window hours |
| Neptune | Neptune storage (prorated) | 0.7 GB-mo | $0.1 | 0.07 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified; GB assumed |
| Neptune | Neptune I/O requests | 3,000,000 IO | $0.0000002 | 0.60 | Terraform (max 2.5 NCU) + human rule (8 h windows, I/O measured before 50K) | verified price; count ASSUMED (largest uncertainty) |
| Fargate | Fargate task-hours incl. public IPv4 | 56 task-h | $0.0173425 | 0.97 | Terraform (desired 0, max 4 per service) + human rule (56 task-hours) | verified us-east-2 |
| DynamoDB | analytics load: 35,304 items x 2 KB x 2 load(s) (loaded tables only) | 141,216 WRU | $0.000000625 | 0.09 | human rule (load only the 6 read tables) | verified; item counts from phase-07 report; item size ASSUMED; reload = conditional put, still billed |
| DynamoDB | embedding claims (10K docs x 1 WRU x loads) + ledger/provenance writes | 22,000 WRU | $0.000000625 | 0.01 | human rule (load only the 6 read tables) | verified; counts from phase-08 contract (claim per document) |
| DynamoDB | runtime QuerySession/EvidenceBundle writes (200 queries) | 5,000 WRU | $0.000000625 | 0.00 | human rule (load only the 6 read tables) | verified; sizes ASSUMED |
| DynamoDB | runtime analytics reads (200 queries) | 10,000 RRU | $0.000000125 | 0.00 | human rule (load only the 6 read tables) | verified; counts ASSUMED; Phase 10 reads unverified |
| S3 | S3 storage (prorated; raw ~2.7 GB + outputs) | 1.87 GB-mo | $0.023 | 0.04 | human rule (few large objects) | verified (S3 price API) |
| S3 | S3 PUT/COPY/POST/LIST | 40,000 req | $0.000005 | 0.20 | human rule (few large objects) | verified price; count ASSUMED (enrichment cache as a few large objects, see section 5) |
| S3 | S3 GET | 400,000 req | $0.0000004 | 0.16 | human rule (few large objects) | verified price; count ASSUMED |
| S3 Vectors | storage + PUT + queries + data processed (10K x 1024 float32) | 1 lump | $0.03400073 | 0.03 | Terraform (6 indexes) + human rule | verified (S3 price API); human OK for S3 Vectors |
| Bedrock | Titan Text Embeddings V2 input tokens (AWS-billed) | 6,000 1K tok | $0.00002 | 0.12 | human rule (200 calls; nothing in infra) | verified us-east-2; tokens/doc ASSUMED; reruns skip unchanged docs (12.3) |
| Bedrock | Claude Haiku 5.5 in: 200 calls x 30,000 tok (AWS-billed) | 6 M tok | $0.11 | 0.66 | human rule (200 calls; nothing in infra) | AmazonBedrockFoundationModels us-east-2 2026-11-23, Standard (in-region) input; HIGH = config input budget 30,000 |
| Bedrock | Claude Haiku 5.5 out: 200 calls x 2,048 tok | 0.41 M tok | $0.55 | 0.23 | human rule (200 calls; nothing in infra) | same file, Standard output; HIGH = config default output 2,048 (cap 8,192) |
| Fixed | Secrets Manager 4 x full month | 4 secret-mo | $0.4 | 1.60 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified; proration by hours stated on the Secrets Manager pricing page 2026-11-24 |
| Fixed | CloudWatch alarms 3 x full month (free tier not assumed) | 3 alarm-mo | $0.1 | 0.30 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; free tier from the CloudWatch pricing page, always-free vs plan-limited NOT stated, account-wide |
| Fixed | CloudWatch dashboard x full month | 1 dash-mo | $3 | 3.00 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | price verified (CloudWatch price API, 2026-11-24); free tier 3 dashboards x 50 metrics: always-free status NOT confirmed so HIGH prices it; dashboard now has explicit metrics, 25 total |
| Fixed | ECR storage (prorated) | 0.7 GB-mo | $0.1 | 0.07 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | CloudWatch Logs ingestion (Container Insights off; 5 GB in free tier NOT assumed) | 0.5 GB | $0.5 | 0.25 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; volume ASSUMED |
| Fixed | Cloud Map resource (1, prorated) | 0.23 res-mo | $0.1 | 0.02 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified |
| Fixed | Step Functions transitions (HIGH: free tier not assumed) | 200 trans | $0.000025 | 0.01 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified price; 4,000 free/month stated as available indefinitely (pricing page 2026-11-24) |
| Fixed | SQS requests (HIGH: free 1M not assumed) | 300,000 req | $0.0000004 | 0.12 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 standard Tier1 price; 1M free per month account-wide (SQS pricing page) |
| Fixed | Internet data transfer out (HIGH: 100 GB free not assumed) | 2 GB | $0.09 | 0.18 | Terraform (4 secrets, 3 alarms, 7-day retention) + AWS (free tiers if applicable) | verified us-east-2 (AWSDataTransfer, first 10 TB beyond the free 100 GB) |
| | **TOTAL** | | | **17.04** | | |

Counts are derived from reading the configuration, not from a plan; the human's plan output is authoritative.

## 11. Deviations, assumptions and limits

1. **EMR release label (human decision 2) - now VERIFIED against the AWS page.** Default `emr_release_label = "emr-spark-8.1.0"`. Source: https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html, fetched 2026-11-23 (HTTP 200). The page title is "AWS runtime for Apache Spark (emr-spark-8.1.0)"; the extracted text lists Spark 4.1.1-amzn-0, Hadoop 3.4.2-amzn-3, Scala 2.13.17, Java 17 as the bold (default) Corretto version for Spark and Hadoop, and an LTS lifecycle with end of life September 8, 2029. Earlier attempts at other URLs (`emr-spark-8.1.0.html`, `emr-810-release.html`) returned only a redirect stub; the URL above is the one that works. The validation regex is relaxed to `^emr-[a-z0-9.-]+$`. Version-ledger match (read-only, `project/Versions.scala`): `spark = "4.1.1"`, `hadoop = "3.4.2"`, `scala213 = "2.13.17"` agree (the ledger holds no `-amzn` suffix and no Java version). **Custom MapReduce JAR support on this release is still to be confirmed by a Phase 14 smoke job** (the state machine submits steps through `command-runner.jar`; an invalid label would fail at `CreateCluster`). The release label does not by itself prove the account and Region offer it; also confirmed only in Phase 14.
2. **Neptune engine 1.5.0.1** (section 4; Serverless verified by the human on 2026-11-24) is a variable. The **generation model ID** default is not validated against AWS. **Generation model mismatch (report only, not changed):** the Terraform default is `generation_model_id = anthropic.claude-haiku-4-5-20251001-v1:0`, while the estimate prices **`anthropic.claude-haiku-5-5`** (the model the human wants for answer generation; listed by Bedrock in us-east-2 in the human's check, "listed is not proof of invocation"). If the human keeps 5.5, `generation_model_id` must be set to it. Inference profile: the AWS price file lists both a region ("Standard") rate and a "Global" rate for Claude Haiku 5.5 in us-east-2, so a global cross-region profile may exist; **whether on-demand invocation needs an inference profile (the `us.`/`global.` prefix) is NOT VERIFIED** (the pages read say each model's profile IDs are on its model page, which was not fetched). IAM consequence if a profile is required: invoking through a profile needs permission on the profile ARN `arn:aws:bedrock:us-east-2:<account>:inference-profile/<profile id>` and on the underlying foundation-model ARNs in every Region the profile routes to (https://docs.aws.amazon.com/bedrock/latest/userguide/inference-profiles-prereq.html, fetched 2026-11-24); the current query-api policy takes whatever ARNs are in `bedrock_model_arns` (default empty), so the human must add the right ones. The 200-call limit is an assumption of the plan, not enforced by any infrastructure; the model-runtime budgets in code (input 30,000 tokens, output 2,048, cap 8,192, from `RUNTIME-AGENTS.md` on main) bound each call only.
3. **embedding-worker autoscaling.** Spec: SQS backlog per task. ECS publishes no free running-task metric to divide by, so the policy tracks `ApproximateNumberOfMessagesVisible` (target 100) with min 0. This scales on backlog but not per task. Exact backlog-per-task needs a metric-math target tracking policy on Container Insights metrics (extra cost); not implemented.
4. **Tags and the EMR cluster.** Clusters created by the state machine are not covered by provider `default_tags`; the same tags are written into the cluster definition.
5. **No VPC flow logs, no ALB access logs, no WAF**: each adds cost; not required by the work order. Public subnets auto-assign public IPs because EMR nodes need one in a NAT-free design.
6. **Container filesystem** is writable and no container health check is defined (the ALB health check covers `query-api`); Phase 10 may tighten this.
7. **Bedrock** permissions: the worker and retrieval get exactly the embedding model (when `embedding_provider = "bedrock"`); `bedrock_model_arns` is granted to query-api only, for generation models, and is empty by default; with the Voyage/Anthropic fallback (instructor approval 4) the services reach the external APIs over HTTPS through the public subnets and the secrets (`scholarchain/voyage-api-key`, `SCHOLARCHAIN_ANTHROPIC_API_KEY`; four secrets in all).
8. **Security scan (work order B, trivy 0.76.2 `trivy config`, no AWS).** Baseline 2026-11-23: 36 findings (5 CRITICAL, 4 HIGH, 20 MEDIUM, 7 LOW). After repair round 2: **7 findings, all LOW, no MEDIUM, HIGH or CRITICAL.**
   - Justified in source with `#trivy:ignore:<rule>` directly above the exact resource plus a reason comment (no ignore file): AWS-0104 x5 (open egress; `modules/network/main.tf` lines 186, 225, 246, 272, 283), AWS-0164 (public subnet IPs, `modules/network/main.tf:35`), AWS-0178 (no VPC flow logs, accepted by the human, `modules/network/main.tf:15`), AWS-0095 (SNS topic unencrypted, `modules/observability/main.tf:13`, see section 7), AWS-0024 x17 (PITR off by human decision, real loss stated, `modules/data/dynamodb.tf:40`), AWS-0132 (SSE-S3, `modules/data/s3.tf:45`; this one resource covers all five buckets by design), AWS-0090 x2 (no versioning on curated and logs, accepted by the human, `modules/data/s3.tf:69`).
   - Scoping of the AWS-0090 exception: the versioning configuration is split into two resources. `aws_s3_bucket_versioning.this` (raw, reports, artifacts: Enabled) carries no ignore; `aws_s3_bucket_versioning.unversioned` (curated and logs only: Suspended) carries the ignore. A change that suspended versioning on raw, reports or artifacts would therefore still be reported. The raw and reports addresses printed by `scripts/cleanup.sh` (`aws_s3_bucket_versioning.this["raw"]`, `["reports"]`) are unchanged.
   - LOW deferred, disclosed (customer-managed keys or extra buckets would cost money and were not ordered): AWS-0017 (log groups without CMK, `modules/logs/main.tf:3`), AWS-0025 (DynamoDB without CMK, `modules/data/dynamodb.tf:41`), AWS-0033 x3 (ECR without CMK, `modules/platform/main.tf:15`), AWS-0089 (S3 access logging, `modules/data/s3.tf:19`), AWS-0098 (Secrets Manager without CMK, `modules/platform/main.tf:60`).
9. **Plans** are written to `infra/terraform/plans/`, which contains its own `.gitignore` (`*` and `!.gitignore`). The root `.gitignore` covers only `*.tfplan` (not the `.sha256`, `.summary.txt` and `.untracked.txt` files), so the directory-level ignore is what keeps the checksum, the summary (it holds the account ID) and the untracked list out of git and out of the next run's untracked hash.

## 12. Embedding contracts (accepted, main 765f7ce)

Phase 08 is accepted on main at commit 765f7ce but not merged into lane2 (lane2 HEAD 0a2212d). Sources read with `git show main:<path>`: `modules/core/src/main/resources/scholarchain-embedding.v1.conf`, `modules/rag/RAG-EMBEDDING-CONTRACTS.md`, `modules/storage/STORAGE-CONTRACTS.md` sections 9, 10 and 12.1 to 12.9. Terraform follows them; if main changes, only variable defaults change.

| Key (source) | Value | Terraform |
|---|---|---|
| `embedding.model.id` / `version` / `dimension` (conf) | `amazon.titan-embed-text-v2:0` / `v2` / `1024` | `vector_dimension = 1024`, `embedding_model_id`; RAG-EMBEDDING-CONTRACTS.md line 33 |
| `embedding.voyage.api-key-env-var` (conf) | `SCHOLARCHAIN_VOYAGE_API_KEY` | ECS secret injection name (existing `scholarchain/voyage-api-key` secret) |
| `embedding.queue.name` / `dead-letter-name` / `max-attempts` (conf) | `scholarchain-embedding-jobs` / `scholarchain-embedding-jobs-dlq` / `3` | SQS names exact; `maxReceiveCount = 4` (12.3; reasoning in section 5, "Why maxReceiveCount is 4") |
| `embedding.claims.table-name` (conf; 12.4a) | `scholarchain-embedding-key-claims` | pk (S) only, no sort key, on-demand, no TTL, deletion protection on; confirmed against 12.4a. Separate test table `scholarchain-it-embedding-key-claims` (12.4a: an `indexOverride` run must use its own claims table) |
| `embedding.test.vector-index-name` (conf; 12.8) | `scholarchain-it-embedding-test` | extra S3 Vectors index, dimension = `var.vector_dimension` |
| `SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL`, `SCHOLARCHAIN_IT_EMBEDDING_DLQ_URL` (12.7) | queue and DLQ URLs | `live_suite_environment` output |

Closed: the earlier note that the worker's DLQ send permission might be removed at reconcile. It is required by STORAGE-CONTRACTS 12.3 (the worker sends DLQ jobs itself).

## 13. Open points

- **O3 audit bucket name (BLOCKED-ON domain-engineer / rag-engineer for the config key).** `RuntimeTables.auditBucket` is a hard-coded default `scholarchain-local-audit` with `auditPrefix = "rag/audit"` (`RuntimeSupport.scala:40-73`); no config key or environment variable for it exists in `scholarchain-config.v1.conf`, `scholarchain-storage.v1.conf` or the rag runtime config that I could find, so none is set on query-api (nothing invented). Terraform exposes the bucket as output `rag_audit_bucket` (the curated bucket) and grants `PutObject` on `rag/audit/*`. Proposed name for Phase 10, pending that owner: `SCHOLARCHAIN_RAG_AUDIT_BUCKET`. Until it exists the audit stage would write to `scholarchain-local-audit`, which is neither this bucket nor permitted.
- **Phase 13 reconciliation (ActionOnFailure and step timeout).** The pipeline steps are to use `ActionOnFailure = TERMINATE_CLUSTER` and a 1-hour step timeout, owned by the pipeline script (`scripts/run-bounded-pipeline`, Phase 13). Terraform still has `CANCEL_AND_WAIT` and `step_timeout_seconds = 10800` (3 h) in the state machine; deliberately unchanged now. Phase 13 must reconcile the two (the estimate prices 3 h for the 50K step).

- **O1 optional Neptune endpoint (for domain-engineer and Phase 10).** Core requires `storage.neptune-endpoint` and treats a blank environment variable as unset, so with `create_neptune = false` every service that resolves core configuration fails to start. Terraform now omits the variable (no empty string). Needs a decision in the core contract (make the key optional, or let services start without a graph) before services are scaled up without Neptune. No change was made under `modules/`.
- **O2 (closed)** the embedding-worker `sqs:SendMessage` on the DLQ is required by STORAGE-CONTRACTS 12.3 (main 765f7ce).
- **Cost table notes (review L13).** The EMR instance-hour rate in section 10 includes the public IPv4 charge ($0.005 per hour per node). Container Insights is off by default and not in the estimate.
