# Service limits and Region availability

Draft (Phase 17a). Every value below is copied from a committed file that records its own source and access date. **No network lookup was made while writing this document**, so no limit was re-verified; where a committed file says "NOT VERIFIED" that is repeated here. Anything the committed files do not contain is `[[PENDING-AWS: ...]]`. Region and environment used by the implementation: `us-east-2`, environment `course` ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 1).

## 1. Region availability (verified by the human or recorded with a source)

| Item | Statement | Source and date |
|---|---|---|
| Neptune engine 1.5.0.1 with `db.serverless` | verified in `us-east-2` by the human's read-only `describe-orderable-db-instance-options` | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 4; [phase-12 gate](agent-runs/phase-12-terraform/human-gate.md) section 2; 2026-11-24 |
| EMR release `emr-spark-8.1.0` | verified in `us-east-2` (`describe-release-label`); applications listed: AmazonCloudWatchAgent, JupyterEnterpriseGateway, Livy, Spark (no Hadoop application; `hadoop-mapred 3.4.2-amzn-3` is a component per the release guide) | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 6.2; 2026-11-24 |
| EMR release `emr-7.14.0` (fallback for MapReduce only) | exists per the release guide; Spark there is 3.5.8 with Scala 2.12.18, so Spark jobs built for Spark 4.1.1 and Scala 2.13.17 will not run on it | same, section 6.2; 2026-11-24 |
| Bedrock: Claude Haiku 4.5 through the `us.` inference profile | the account lists the profile and a 5-token Converse call through it succeeded in `us-east-2` | [phase-11 gate](agent-runs/phase-11-adversarial-review/human-gate.md) decision 1; 2026-11-24. That this runtime accepts the profile ID end to end is `[[PENDING-AWS: Phase 14 smoke test]]` |
| Bedrock: Titan Text Embeddings V2 (`amazon.titan-embed-text-v2:0`) in `us-east-2` | the IAM grant and the 1024-dimension choice are specified; model availability in the Region for this account `[[PENDING-AWS: Phase 14/15c]]` | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) sections 3a and 4 |
| S3 Vectors in `us-east-2` | the Terraform provider schema has `aws_s3vectors_vector_bucket` and `aws_s3vectors_index` (verified locally, provider 6.73.0); service availability in the Region `[[PENDING-AWS: first plan apply, Phase 14]]` | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 1 |
| Terraform AWS provider 6.73.0 | published 2026-11-11 per the registry, fetched 2026-11-22 | same |

## 2. Service limits used by the implementation

### S3 Vectors (enforced in `S3VectorsLimits`; sources and access date 2026-11-21 in the contract)

| Limit | Value |
|---|---|
| Vectors per PutVectors call | 500 |
| Request payload | 20 MiB |
| Vectors per GetVectors call | 100 |
| Dimension | 1 to 4,096 (project default 1024) |
| Top-K per QueryVectors request | up to 10,000; up to 100 results per page |
| Vector key length | 1 to 1,024 (core policy is stricter: 512) |
| Metadata keys per vector | up to 50 |
| Filterable metadata per vector | up to 2 KB on CLASSIC indexes (the adapter enforces the smaller CLASSIC sizes) |
| Combined insert and delete rate per index | up to 2,500 vectors per second; PutVectors and DeleteVectors up to 1,000 requests per second per index; up to 140 MBps |
| Cosine distance | zero vectors and NaN or Infinity are rejected; data is float32 |

Source: [STORAGE-CONTRACTS.md](../modules/storage/STORAGE-CONTRACTS.md) section 5.1. Not verified there: the exact byte accounting of filterable metadata, per-request throttling behavior, and the error code for a rejected vector inside a PutVectors call. Index mode: ENHANCED for buckets created on or after 2026-09-30, CLASSIC before (same source). The instructor-approved vector count is up to 10,000 ([instructor-approvals.md](agent-runs/instructor-approvals.md) item 2).

### DynamoDB, SQS, Neptune, EMR, Fargate

| Service | Limit used | Source |
|---|---|---|
| DynamoDB | item size 400 KB (409,600 bytes); partition key value at most 2,048 bytes; sort key at most 1,024; BatchWriteItem up to 25 puts (2026-11-21). Evidence bundles above 300,000 bytes spill to S3 | [STORAGE-CONTRACTS.md](../modules/storage/STORAGE-CONTRACTS.md) section 4 and Phase 07 gate |
| SQS | message body 1 byte to 1,048,576 bytes; `MaxNumberOfMessages` 1 to 10 (some upper bounds NOT VERIFIED in the source); visibility 300 s, 4-day retention, long polling 20 s, redrive `maxReceiveCount` 4 to a DLQ with 14-day retention | [STORAGE-CONTRACTS.md](../modules/storage/STORAGE-CONTRACTS.md) section 7; [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 5 |
| Neptune Serverless | minimum 1 NCU, maximum 2.5 NCU ("the smallest value you can set for the maximum capacity is 2.5 NCUs", fetched 2026-11-24); bulk load via openCypher CSV; openCypher query timeout by hint (engine 1.3.2.0 or later) plus a client-side timeout | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 4; [STORAGE-CONTRACTS.md](../modules/storage/STORAGE-CONTRACTS.md) section 3.5 |
| EMR | at most 3 nodes in every run (1 primary plus at most 2 core/task nodes; the primary cannot be scaled and is outside the managed-scaling limit per the AWS pages fetched 2026-11-24); on-demand `m5.xlarge`; idle auto-termination 900 s does **not** fire while a YARN application runs; Step Functions does not terminate a cluster when an execution stops; per-step timeout 3 h in the state machine and 1 h in `run-bounded-pipeline.sh`; the CLI cluster guard blocks EMR resize above `SCHOLARCHAIN_EMR_MAX_INSTANCE_COUNT` (default 3) | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) sections 6, 6.1, 14.2; [docs/agents.md](agents.md) section 5 |
| ECS Fargate | 0.25 vCPU, 512 MiB per task; `ecs_max_tasks` capped at 4 by validation | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 5 |
| Bedrock quotas (tokens or requests per minute for Haiku 4.5 and Titan Embed v2) | `[[PENDING-AWS: account quotas; instructor approval 4 allows the Anthropic API and Voyage as fallback if Bedrock quotas prevent timely execution, but the runtime decision is Bedrock only]]` | [instructor-approvals.md](agent-runs/instructor-approvals.md); [phase-11 gate](agent-runs/phase-11-adversarial-review/human-gate.md) |
| Budget notification | AWS Budgets data refreshes up to three times a day, about 8 to 12 hours apart; an alert does not stop spending | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 7 |
| Secrets Manager | 7-day recovery window; a destroy followed by a re-apply within 7 days fails on secret names unless force-deleted | [phase-12 gate](agent-runs/phase-12-terraform/human-gate.md) G12-4 |

### Application limits (not AWS limits, but limits the deployment relies on)

| Limit | Value | Source |
|---|---|---|
| Request body | at most 32,768 bytes, JSON nesting at most 8 | [REST-CONTRACTS.md](../modules/core/REST-CONTRACTS.md) |
| Query concurrency | 4 running, 16 queued, then 429 | [API.md](../modules/services/API.md) |
| Synchronous deadline | 20,000 ms default, then 202 with a job; asynchronous run fails with TIMEOUT after 120,000 ms | [API.md](../modules/services/API.md) |
| Downstream timeout and retries | 8,000 ms per call, at most 3 attempts for reads, circuit breaker after 5 failures | [API.md](../modules/services/API.md) |
| Graph query ceilings | depth 10, results 1,000, timeout 60,000 ms (defaults 6, 100 and 5,000 ms); neighbors: at most 1,000 nodes, 60 reads, 8 s | [STORAGE-CONTRACTS.md](../modules/storage/STORAGE-CONTRACTS.md) section 3.5; [API.md](../modules/services/API.md) |
| Model call bounds | input 30,000 tokens, output 2,048 tokens (cap 8,192), 30 s, at most 2 malformed-output retries; context 60,000 characters | [RUNTIME-AGENTS.md](../modules/rag/RUNTIME-AGENTS.md) section 7 |
| Hadoop pair hard cap | 499,500 pairs per team | [phase-04 gate](agent-runs/phase-04-hadoop-mapreduce/human-gate.md) section 2 |

## 3. Pricing and cost-model limits

Cost figures are in [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 10: estimate LOW $4.48 and HIGH $16.95 for cleanup on 2026-11-27 ($4.68 and $17.04 if the project slips to 2026-11-30), human spend limit $20 before credits, project ceiling $25. These are estimates computed from price files fetched on 2026-11-23 and 2026-11-24, not measurements. The earlier model price in that section names a model (Haiku 5.5) other than the model chosen in the Phase 11 gate (Haiku 4.5); until the estimate is reissued the Bedrock line is `[[PENDING-AWS: Bedrock cost recomputed for Haiku 4.5]]`. Measured cost: `[[PENDING-AWS: Cost Explorer before credits after the Phase 15c windows]]`.

## 4. Verification procedure for a grader

1. Compare each limit with the cited AWS page on the day of grading; the access dates above are the last time a human or agent looked.
2. The live suites (`*AwsItSuite`) run only with `SCHOLARCHAIN_AWS_IT=1` ([STORAGE-CONTRACTS.md](../modules/storage/STORAGE-CONTRACTS.md) section 9) and were not run: `[[PENDING-AWS: live limit behavior]]`.
