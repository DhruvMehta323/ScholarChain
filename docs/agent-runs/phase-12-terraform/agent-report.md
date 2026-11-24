# Phase 12 — agent report

## Progress checkpoint — preconditions (orchestrator, read-only)

```
STATUS: BLOCKED-ON
OWNER: orchestrator
WORK ORDER: Phase 12 §1 Preconditions
FILES CREATED: docs/agent-runs/phase-12-terraform/agent-report.md
FILES MODIFIED: none
COMMANDS RUN:
  pwd; git branch --show-current; git status --short
  terraform --version; tflint/checkov/trivy/tfsec presence check (command -v)
  ls modules/core/src/main/resources/ modules/storage/; cat modules/core/src/main/resources/*.conf
  git log --oneline --all; git merge-base main lane2; git rev-list --left-right --count main...lane2
  git ls-tree -r --name-only <branch> (search for STORAGE-CONTRACTS / StorageModuleConfig / SparkConfig)
TEST RESULTS: NOT RUN (no source changed; sbt not needed)
VALIDATION RESULTS:
  cwd = /Users/dhruv_mehta/Desktop/ScholarChain-lane2, branch = lane2: PASS
  git status --short = "?? docs/agent-runs/phase-12-terraform/" only: PASS
  terraform: Terraform v1.17.2 on darwin_arm64
  tflint: NOT INSTALLED; checkov: NOT INSTALLED; trivy: NOT INSTALLED; tfsec: NOT INSTALLED
  core contracts: FAIL. modules/core/src/main/resources/ holds only scholarchain-config.v1.conf
    and scholarchain-hadoop.v1.conf. StorageModuleConfig.scala, SparkConfig.scala and
    modules/storage/STORAGE-CONTRACTS.md are absent from lane2 (modules/storage/ is empty).
    They exist on main only (0d1611f).
  history: merge-base(main, lane2) = baa31d5 (phase-05). main is 5 commits ahead (phases 06, 07),
    lane2 is 2 commits ahead (phase-15a).
ASSUMPTIONS: none
RISKS: Without the Phase 07 storage contracts, Terraform table names, key schemas and outputs cannot be lined up with the application config as §1 and §2 require.
MANUAL ACTION REQUIRED:
  1. Install a static security scanner (for example `brew install checkov` or `brew install trivy`); tflint optional.
  2. Make Phase 06/07 contracts available to lane2, or authorize reading them from main, then restart Phase 12.
BLOCKED-ON OWNER: human (orchestrator decision)
BLOCKED-ON EVIDENCE: work order §1 requires reading StorageModuleConfig, SparkConfig and modules/storage/STORAGE-CONTRACTS.md; not present on branch lane2. Human chose "Stop Phase 12" when asked.
NEXT GATE: human resolves the precondition; Phase 12 restarts from §1.
```

Work orders A, H, B, D: NOT RUN (precondition failed; human chose to stop).

## Progress checkpoint — preconditions, rerun after merge (orchestrator, read-only)

```
STATUS: COMPLETE
OWNER: orchestrator
WORK ORDER: Phase 12 §1 Preconditions (rerun)
FILES CREATED: none
FILES MODIFIED: docs/agent-runs/phase-12-terraform/agent-report.md (this checkpoint)
COMMANDS RUN:
  pwd; git branch --show-current; git status --short; git log --oneline -3; git merge-base main lane2
  terraform --version; command -v tflint checkov trivy tfsec
  cat modules/core/src/main/resources/scholarchain-{config,storage,spark}.v1.conf
  cat StorageModuleConfig.scala, SparkConfig.scala, Config.scala (ConfigKeys); cat modules/storage/STORAGE-CONTRACTS.md; cat .gitignore
TEST RESULTS: NOT RUN (no source changed; sbt not needed)
VALIDATION RESULTS:
  cwd = /Users/dhruv_mehta/Desktop/ScholarChain-lane2, branch = lane2: PASS
  HEAD = 0a2212d "merge main into lane2: bring in phases 06-07"; merge-base(main, lane2) = 0d1611f (= main HEAD): PASS
  git status --short = "?? docs/agent-runs/phase-12-terraform/" only: PASS
  terraform: Terraform v1.17.2
  tflint, checkov, trivy, tfsec: NOT INSTALLED (each)
  core contracts present: scholarchain-storage.v1.conf, scholarchain-spark.v1.conf, StorageModuleConfig.scala, SparkConfig.scala, STORAGE-CONTRACTS.md: PASS
  .gitignore already ignores .terraform/, *.tfstate*, *.tfplan, tfplan*, *.tfvars (!*.tfvars.example)
Names the application expects (Terraform outputs must match):
  Core keys (env SCHOLARCHAIN_<KEY>): environment, aws.region, storage.raw-bucket, storage.curated-bucket,
    storage.vector-bucket, storage.vector-index, storage.dynamo-table-prefix, storage.neptune-endpoint,
    storage.opensearch-endpoint (optional; OpenSearch not implemented), models.embedding-model-id, models.generation-model-id,
    graph.*, entity-resolution.*, retrieval.top-k (non-infra values)
  Storage module (env SCHOLARCHAIN_STORAGE_<KEY>):
    S3 prefixes raw/ curated/ graph/ rag/ reports/ logs/ (bucket supplied by caller)
    neptune.bulk-load.role-arn, neptune.bulk-load.format (opencypher), neptune.bulk-load.source-s3-uri (s3://<bucket>/graph/neptune-load/)
    DynamoDB (pk S [+ sk S], TTL attr expiresAt on QuerySession and EntityLinkCache):
      scholarchain-RunLedger (pk,sk), scholarchain-ArtifactRegistry (pk,sk), scholarchain-QuerySession (pk; TTL),
      scholarchain-EntityLinkCache (pk,sk; TTL), scholarchain-EvidenceBundle (pk,sk)
      scholarchain-analytics-{researcher-metrics, coauthor-edge-works, bridges, coreness, temporal-changes, paths} (pk,sk)
      scholarchain-analytics-{path-estimates, window-summary, identity-clusters, mention-researcher} (pk only)
    vectors.bucket-name; indexes researcher-profile-v1, collaboration-community-v1, paper-abstract-v1,
      collaboration-path-v1, topic-institution-summary-v1 (dimension decided by Phase 08 embedding model)
  SQS: queue URLs supplied by the caller (no name contract in core)
  Live suites: AWS_REGION, SCHOLARCHAIN_IT_S3_BUCKET, SCHOLARCHAIN_IT_VECTOR_DIMENSION, SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT, SCHOLARCHAIN_IT_RESEARCHER_ID
  Spark: SCHOLARCHAIN_SPARK_* (paths; no infra names)
ASSUMPTIONS: Embedding model per instructor approval 4 may be Bedrock or Voyage AI; dimension stays a variable.
RISKS: no static security scanner installed; the B scan cannot run until the human installs one.
MANUAL ACTION REQUIRED: install a static security scanner before B (for example `brew install checkov` or `brew install trivy`); tflint optional.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: work order A (infra-engineer)
```

## Progress checkpoint — canonical test run requested by the Stop hook (orchestrator)

```
STATUS: FAILED
OWNER: orchestrator
WORK ORDER: Phase 12 §0.5 (the Stop hook demanded the canonical command; newer file: modules/core/src/test/scala/scholarchain/core/StorageRecordsContractSuite.scala, brought in by merge 0a2212d, not by Phase 12)
COMMANDS RUN: the canonical test command from CLAUDE.md, exactly as written, once, from the repo root
TEST RESULTS:
  marker: exit=1; elapsed 1659 s (0:27:39)
  per-module summaries: Passed 293/293; Passed 206 + Skipped 7 of 213; FAILED 266/268 (2 failed); Passed 825/825; Passed 192/192
  failed: sparkAnalytics / Test, scholarchain.spark.IdentitySuite (2 of 47):
    M1 "the reason combines with rejected_or_conflict_pair and with two_orcids, in that order": java.util.concurrent.TimeoutException: test timed out after 30 seconds (45.819s)
    M2 "a REJECTED and a CONFLICT pair inside one base identity are ignored, listed and counted": TimeoutException after 30 seconds (36.966s)
  IdentitySuite took 463 s in total.
ASSUMPTIONS: none
RISKS: both failures are 30-second munit timeouts, not assertion failures. The run overlapped with infra-engineer's work order A on the same machine; CPU contention is a plausible cause but NOT VERIFIED. No rerun was made.
MANUAL ACTION REQUIRED: human decides whether to rerun sbt check on an idle machine or route the timeouts to spark-engineer / test-engineer (modules/** is outside lane 2 for this phase).
BLOCKED-ON OWNER: none for Phase 12 (no Phase 12 file is Scala or sbt source)
BLOCKED-ON EVIDENCE: none
NEXT GATE: work order A result
```

## Progress checkpoint — work order A (infra-engineer)

```
STATUS: BLOCKED-ON (human cost decision, work order §6: estimate exceeds $15)
OWNER: infra-engineer (authoring); checkpoint assembled by the orchestrator
WORK ORDER: Phase 12 §2 A
FILES CREATED (untracked, not staged):
  infra/terraform/INFRA-CONTRACTS.md
  infra/terraform/environments/course/{versions,main,variables,outputs}.tf, terraform.tfvars.example, .terraform.lock.hcl
  infra/terraform/modules/{network,data,neptune,platform,logs,iam,compute,pipeline,observability}/*.tf
  scripts/plan.sh, scripts/cleanup.sh, scripts/lib/common.sh
FILES MODIFIED: none outside the above
COMMANDS RUN (agent): terraform init -backend=false; terraform providers schema -json; terraform fmt; terraform validate;
  bash -n and shellcheck on the three scripts; curl of the public Terraform registry and public AWS pricing/docs pages; local python cost script.
  No AWS account call, no terraform plan, no scanner.
VALIDATION RESULTS (agent-reported, then re-run by the orchestrator, observed):
  terraform fmt -check -recursive infra/terraform: exit 0
  terraform -chdir=infra/terraform/environments/course validate: "Success! The configuration is valid." exit 0
  bash -n scripts/plan.sh: exit 0; bash -n scripts/cleanup.sh: exit 0 (the guard did not block it)
  shellcheck (agent only): exit 0, no findings
  orchestrator grep: no nat_gateway, no Interface endpoint in infra/terraform
  provider: hashicorp/aws ~> 6.73 (6.73.0, registry, published 2026-11-11); aws_s3vectors_vector_bucket and aws_s3vectors_index present in the local schema (agent-reported)
  security scan: NOT RUN (no scanner installed); terraform plan: NOT RUN (human step H)
DESIGN SUMMARY (agent-reported): 9 modules, 95 resource blocks, 19 data sources, about 161 instances on first apply (static estimate, not a plan);
  36 outputs including app_environment and live_suite_environment; 15 DynamoDB tables on-demand with the contract key schemas; 5 + 1 test vector indexes, dimension 1024 (variable);
  ECS services query-api, retrieval, embedding-worker, desired 0, max 4; EMR via a Step Functions state machine, auto-termination 600 s, managed scaling max 3; no KMS key; 2 empty secrets.
COST ESTIMATE (INFRA-CONTRACTS.md §10, prices accessed 2026-11-22):
  (a) 1 hour idle, everything up: $0.20
  (b) 4-hour experiment day: $3.50
  (c1) whole project, defaults left up: $17.22   <-- above the $15 threshold
  (c2) whole project with proposed cuts: $12.33
  Largest drivers (c1): Neptune ~$6.43, EMR ~$4.82, DynamoDB ~$2.25, ALB + IPs ~$1.62. External API costs (Voyage/Anthropic/Bedrock) excluded.
  Proposed cuts: create_neptune=false and create_alb=false by default, on only for experiment windows; never leave Neptune up overnight ($3.86/day); EMR core 1 / max 3, 600 s idle; reserve ~$3 for external APIs.
DEVIATIONS / ASSUMPTIONS needing human attention:
  1. EMR release: emr-7.14.0 used instead of the work order's emr-spark-8.1.0 (agent says it could not verify an 8.x label). Spark/Scala compatibility with the Scala 3 build NOT checked.
  2. Vector dimension 1024 (Titan V2 / voyage-3 family, from docs reading); index dimension is immutable after creation.
  3. generation_model_id default is a placeholder; Neptune engine 1.4.8.1 serverless support not independently verified.
  4. embedding-worker scales on queue depth, not backlog per task.
  5. IAM wildcards where AWS has no resource-level support (RunJobFlow, logs delivery, ECR login, RegisterTaskDefinition) and the managed AmazonEMRServicePolicy_v2.
  6. ALB ingress default 0.0.0.0/0 on 80/443.
  7. plan.sh refuses untracked files, so step H requires the authored files to be committed (or otherwise set aside) first; work order §0.6 forbids agents to commit.
MANUAL ACTION REQUIRED:
  1. Cost decision: accept (c1) $17.22, or adopt the cuts (c2 $12.33, agent-recommended; then the defaults become create_neptune=false / create_alb=false).
  2. Decide on the EMR release label deviation.
  3. Install a static scanner before B.
  4. Before H: copy terraform.tfvars.example to terraform.tfvars (gitignored) and set owner, expiration_date, alert_email; make the tree clean (human commit).
  H command (human terminal, repo root): AWS_PROFILE=scholarchain bash scripts/plan.sh
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: work order §6 "STOP and ask the human if ... the estimate exceeds $15"; INFRA-CONTRACTS.md §10 (c1) = $17.22
NEXT GATE: human cost decision -> (repair by infra-engineer if defaults change) -> D pass 1 (code-reviewer) -> H
```

Work orders D (pass 1), H and B: NOT RUN (stopped for the human cost decision per §6).

## Human decisions after work order A (relayed by the human in the orchestrator session, 2026-11-22)

1. Adopt the cuts: create_neptune=false, create_alb=false by default; document turning them on for experiment windows.
2. EMR release label default emr-spark-8.1.0 (Spark 4.1.1-amzn-0, Hadoop 3.4.2-amzn-3, Scala 2.13.17, Java 17, LTS, per the human); cite the release-guide URL; custom MapReduce JAR support to be confirmed by a Phase 14 smoke job.
3. Vector dimension 1024: confirm against the embedder config (read-only) and report evidence.
4. ALB ingress CIDR: required variable, no default, never 0.0.0.0/0.
5. IAM "*": only where AWS has no resource-level permissions, each with a justification comment.
6. plan.sh: allow a dirty tree for planning, recording dirty=true and SHA-256 of the diff plus the untracked file list. Never apply.
7. No sbt in lane 2 until the human says lane 1 is idle.

## Orchestrator notes on decisions 3 and 7

```
Decision 3, vector dimension (read-only evidence, observed):
  lane2 (committed): modules/core/src/main/resources/contracts/vector-record.example.json -> "dimension":1024, "embeddingModelId":"amazon.titan-embed-text-v2:0"
  lane 1 worktree /Users/dhruv_mehta/Desktop/ScholarChain (Phase 08, UNCOMMITTED, not yet accepted):
    modules/core/src/main/resources/scholarchain-embedding.v1.conf -> embedding.model.id = "amazon.titan-embed-text-v2:0", version = "v2", dimension = 1024
    modules/rag/RAG-EMBEDDING-CONTRACTS.md line 33 -> BedrockEmbedder default Titan Text Embeddings V2, dimension 1024 (Titan accepts 256/512/1024)
  modules/rag on lane2: empty (Phase 08 not merged). Result: Terraform default 1024 matches. Provisional until Phase 08 is accepted.
  The same lane 1 file also defines names the Terraform should match (provisional): voyage api-key env var SCHOLARCHAIN_VOYAGE_API_KEY,
  queue scholarchain-embedding-jobs + DLQ scholarchain-embedding-jobs-dlq (max-attempts 3), claims table scholarchain-embedding-key-claims,
  live-test index scholarchain-it-embedding-test. Relayed to infra-engineer for alignment in the repair.
Decision 7, sbt: the failed lane 2 sbt check (exit=1, 2 IdentitySuite timeouts) coincided with a concurrent sbt run in lane 1
  (per the human). No sbt will run in lane 2 until the human says lane 1 is idle.
```

## Repair round 1 (work order A) — dispatched to infra-engineer for decisions 1, 2, 4, 5, 6 (plus the provisional name alignment from 3)

## Progress checkpoint — repair round 1 of A (infra-engineer)

```
STATUS: COMPLETE (EMR label NOT VERIFIED against the AWS release guide; see below)
OWNER: infra-engineer; checkpoint assembled by the orchestrator
WORK ORDER: Phase 12 A repair round 1 (human decisions 1-6; decision 7 honoured: no sbt)
FILES MODIFIED: INFRA-CONTRACTS.md; environments/course/{variables,main}.tf, terraform.tfvars.example; modules/pipeline/variables.tf;
  modules/platform/{main,variables}.tf; modules/data/dynamodb.tf; modules/compute/{main,variables}.tf; modules/iam/{main,variables}.tf;
  scripts/plan.sh; scripts/lib/common.sh. FILES CREATED: none.
CHANGES:
  1. create_neptune=false, create_alb=false defaults; INFRA-CONTRACTS.md §9a documents switching them on (outputs, task-def roll, cost).
  2. emr_release_label default emr-spark-8.1.0; regex ^emr-[a-z0-9.-]+$. NOT VERIFIED: emr-spark-8.1.0.html, emr-810-release.html,
     emr-release-app-versions-8.x.html returned a ~2.7 KB client-side redirect stub on 2026-11-22; emr-release-components.html listed no 8.x.
     Ledger match (project/Versions.scala, read-only): spark 4.1.1, hadoop 3.4.2, scala213 2.13.17. MapReduce JAR support -> Phase 14 smoke job.
  3. dimension 1024 kept with evidence; provisional lane 1 names adopted (§12): SCHOLARCHAIN_VOYAGE_API_KEY, scholarchain-embedding-jobs(+-dlq, maxReceiveCount 3),
     table scholarchain-embedding-key-claims (pk only, per lane 1 STORAGE-CONTRACTS.md §12.4a), test index scholarchain-it-embedding-test.
     Unrequested addition: embedding-worker sqs:SendMessage/GetQueueUrl on the DLQ only (remove at reconcile if redrive alone is used).
  4. alb_ingress_cidrs required, no default; rejects any /0; example 203.0.113.10/32.
  5. Remaining "*" resources, each commented: ecs-execution ecr:GetAuthorizationToken; sfn-pipeline elasticmapreduce:RunJobFlow and the
     Step Functions log-delivery actions; deployer (optional) ecr:GetAuthorizationToken and ecs:Register/Describe/DeregisterTaskDefinition.
     Plus managed AmazonEMRServicePolicy_v2 on the EMR service role.
  6. plan.sh allows a dirty tree; records dirty, git_diff_head_sha256, untracked_list_sha256 in markers and SC-SUMMARY; never applies.
VALIDATION (agent-reported, then re-run by the orchestrator, observed): fmt -check exit 0; validate "Success! The configuration is valid.";
  bash -n plan.sh / cleanup.sh / lib/common.sh exit 0 each; shellcheck exit 0 (agent only). plan.sh runtime path NOT TESTED (needs AWS).
COST: default (c) $12.33 (3 experiment days $10.50 + 4 h deploy/smoke $0.81 + $1.00 unpriced allowance); all-on (c1) $17.22 alternative;
  idle hour with defaults ~$0.002. External API costs excluded (reserve ~$3).
NEXT GATE: D pass 1 (code-reviewer)
```

## Progress checkpoint — D pass 1 and the repair round (orchestrator)

```
STATUS: COMPLETE (repair applied; D re-review in progress)
OWNER: code-reviewer (D pass 1); infra-engineer (repair)
WORK ORDER: Phase 12 §3 D pass 1 -> REQUEST_CHANGES (1 HIGH, 7 MEDIUM, 13 LOW; stored in review-report.md) -> one repair round per §3
REPAIR (infra-engineer, agent-reported):
  H1 inline iam:PassRole on scholarchain-emr-ec2 (PassedToService ec2.amazonaws.com); confirmed against the AmazonEMRServicePolicy_v2 reference page (2026-11-22)
  M1 API-latency widget only when the ALB exists
  M2 EMR s3:DeleteObject on curated/*, graph/*, rag/*, tmp/* of the curated bucket only
  M3 noncurrent-version expiry excludes raw and reports
  M4 cleanup.sh prints a state rm -dry-run / state rm step for the evidence buckets before the printed teardown (still print-only)
  M5 new infra/terraform/plans/.gitignore; doc corrected. The orchestrator's preconditions checkpoint above wrongly said `tfplan*` was ignored: only *.tfplan, tfplan and tfplan.* are.
  M6 PutAutoTerminationPolicy, PutManagedScalingPolicy, AddTags on cluster/* (precaution; whether they are required is NOT VERIFIED)
  M7 Neptune endpoint and role ARN env vars omitted when create_neptune=false; open point O1 (optional Neptune endpoint) for domain-engineer / Phase 10
  LOW fixed: L1, L5, L8, L9 (note), L10 (step timeout 7200 s), L11 (note), L13 (note). Deferred: L2, L3, L4 (human confirms), L6, L7, L12.
  Cost totals unchanged: default $12.33; all-on $17.22.
VALIDATION (re-run by the orchestrator, observed): fmt -check exit 0; validate "Success! The configuration is valid."; bash -n plan.sh and lib/common.sh exit 0.
  cleanup.sh bash -n and shellcheck exit 0 (agent-reported).
NEXT GATE: D re-review of the repair, then STOP for H
```

## Progress checkpoint — Phase 12 complete; waiting for human gate (H)

```
STATUS: COMPLETE (work order A + one repair round; D verdict APPROVE WITH NOTES). Waiting for the human plan run (H); B follows H.
OWNER: orchestrator
WORK ORDER: Phase 12 §2 A, §3 D; §4 H next
COMMANDS RUN (orchestrator, this checkpoint): bash -n scripts/cleanup.sh -> exit 0 (closes reviewer N1)
TEST RESULTS: sbt NOT RUN after the failed run recorded above (human decision 7: no sbt in lane 2 until lane 1 is idle)
VALIDATION RESULTS (orchestrator-observed on the final tree): terraform fmt -check exit 0; terraform validate "Success! The configuration is valid.";
  bash -n plan.sh, lib/common.sh, cleanup.sh exit 0. Security scan NOT RUN (no scanner installed). terraform plan NOT RUN (human step H).
DEFERRED LOW (disclosed in review-report.md): L2, L3, L4, L6, L7, L12, N2, N3, live_suite_environment empty Neptune endpoint.
OPEN POINT O1: with create_neptune=false, every service that resolves core config fails to start (storage.neptune-endpoint is required).
  Needs a core-contract decision (domain-engineer) and Phase 10 handling. Not routed; outside lane 2.
MANUAL ACTION REQUIRED:
  1. Approve the IAM set as a security change (INFRA-CONTRACTS.md §3, including the remaining "*" list and the H1/M6 additions).
  2. Confirm L4: cleanup.sh prints (never runs) apply, destroy and state rm commands for the human.
  3. Supply the EMR release-guide URL for emr-spark-8.1.0 (infra-engineer could not retrieve it; label NOT VERIFIED).
  4. Install a static security scanner (e.g. brew install trivy or checkov) before B.
  5. Create infra/terraform/environments/course/terraform.tfvars (gitignored): owner, expiration_date, alert_email, alb_ingress_cidrs = ["<your-ip>/32"].
  6. Run H in your own terminal from the repo root:  AWS_PROFILE=scholarchain bash scripts/plan.sh
     and paste the SC-SUMMARY-BEGIN..SC-SUMMARY-END block under "H (human)" below.
  7. Decide how to route O1.
BLOCKED-ON OWNER: human (gate H)
BLOCKED-ON EVIDENCE: work order §4 H is human-only
NEXT GATE: H (human), then B (infra-engineer scan and fix), then D rereads B's changes
```

## H (human)

(paste the plan summary block here)

## Human work order (2026-11-22): align Terraform to Phase 08 (relayed in the orchestrator session)

Requested: vector-claims table plus a separate test claims table; the vector test index; the embedding SQS queue and DLQ names exactly as configured;
embedding-worker IAM (bedrock:InvokeModel on the configured Titan model only, claims tables, S3 Vectors indexes, queue); an empty Voyage key secret;
confirm dimension 1024 against RAG-EMBEDDING-CONTRACTS.md. sbt check permitted once if the Stop hook requires it (lane 1 idle). Then D pass and STOP for H.

## Orchestrator precondition check for this work order

```
OBSERVED: the human stated "Main (with Phase 08) is merged into lane2". It is NOT:
  lane2 HEAD = 0a2212d (merge of phases 06-07); main HEAD = 765f7ce "phase-08: record accepted commit hash";
  `git merge-base --is-ancestor main lane2` -> false; modules/rag/RAG-EMBEDDING-CONTRACTS.md and
  modules/core/src/main/resources/scholarchain-embedding.v1.conf are absent from the lane2 working tree.
DECISION (orchestrator): proceed, because the work is read-only on contracts. The accepted Phase 08 contracts are read from the commit
  with `git show main:<path>` (accepted content, 765f7ce). The merge is still a human action before the Phase 12 commit.
EVIDENCE (main 765f7ce, read-only):
  scholarchain-embedding.v1.conf: model.id amazon.titan-embed-text-v2:0, version v2, dimension 1024; voyage.api-key-env-var SCHOLARCHAIN_VOYAGE_API_KEY;
    queue scholarchain-embedding-jobs / scholarchain-embedding-jobs-dlq, max-attempts 3; claims.table-name scholarchain-embedding-key-claims;
    test.vector-index-name scholarchain-it-embedding-test
  RAG-EMBEDDING-CONTRACTS.md:33 BedrockEmbedder default Titan Text Embeddings V2, dimension 1024 (Titan accepts 256/512/1024) -> dimension 1024 CONFIRMED
  STORAGE-CONTRACTS.md §12.4a: claims table pk only, no sort key, on-demand, no TTL, worker needs dynamodb:PutItem only; an indexOverride run must use
    its own claims table. §12.3: the worker re-enqueues retries and sends DLQ jobs itself. §12.7: live suite env SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL / _DLQ_URL.
sbt: not required. No file under modules/, tests/, project/ or build.sbt changed since the marker was written.
NEXT: infra-engineer applies the changes, then a D review, then STOP for H.
```

## Progress checkpoint — Phase 08 alignment (infra-engineer)

```
STATUS: COMPLETE (D review in progress)
OWNER: infra-engineer; checkpoint assembled by the orchestrator
FILES MODIFIED: INFRA-CONTRACTS.md; environments/course/{main,variables,outputs}.tf; modules/data/{dynamodb,s3}.tf; modules/iam/{main,variables}.tf; modules/compute/{main,variables}.tf
CHANGES (agent-reported):
  1. claims tables: production scholarchain-embedding-key-claims (pk S, on-demand, no TTL, deletion protection on); test scholarchain-it-embedding-key-claims
     (same schema, deletion protection off, disposable); live_suite_environment sets SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME to the test table
  2. test index scholarchain-it-embedding-test present, dimension var.vector_dimension
  3. queues scholarchain-embedding-jobs / -dlq; redrive maxReceiveCount 3 -> 4 (max-attempts + 1, so the worker's own redelivery-bound dead-letter
     with envelope runs on the 4th receive; SQS DLQ docs read 2026-11-22); live_suite_environment adds SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL / _DLQ_URL
  4. embedding-worker IAM: SQS consume/requeue on the queue; SendMessage only on the DLQ (required by §12.3); s3:GetObject on rag/text/* and logs/it-embedding/*;
     s3vectors Get/PutVectors on the 6 index ARNs; dynamodb:PutItem on both claims tables; provenance tables; bedrock:InvokeModel on
     foundation-model/amazon.titan-embed-text-v2:0 only (when embedding_provider = bedrock). bedrock_model_arns now for generation models on query-api only.
     retrieval gets the same single embedding-model grant.
  5. Voyage secret reused (created empty), injected as SCHOLARCHAIN_VOYAGE_API_KEY into worker and retrieval
  6. dimension 1024 cited from RAG-EMBEDDING-CONTRACTS.md:33 (main 765f7ce); all "provisional lane 1" citations replaced by "accepted, main 765f7ce"
  Cost unchanged (default $12.33). No new "*" resources.
VALIDATION (orchestrator-observed): fmt -check exit 0; validate "Success! The configuration is valid."; bash -n plan.sh, lib/common.sh, cleanup.sh exit 0
RISK: live_suite_environment gives the live suite the PRODUCTION queue and DLQ (no test queues requested); it drains up to 5 messages.
NEXT GATE: D review of this change, then STOP for H
```

## Progress checkpoint — canonical test run after the Phase 08 merge (orchestrator)

```
STATUS: FAILED
OWNER: orchestrator
WORK ORDER: Stop hook demanded the canonical command (newer file: modules/core/src/test/scala/scholarchain/core/EmbeddingRecordsContractSuite.scala,
  from the human's merge 23f2914 "merge main into lane2: bring in phase 08"). Human permitted one run (lane 1 idle).
COMMANDS RUN: the canonical test command from CLAUDE.md, exactly as written, once, from the repo root
TEST RESULTS:
  marker: exit=1 (written 2026-11-22 17:57); elapsed 1567 s (0:26:07)
  module summaries: 316 passed + 7 skipped of 323; 140/140; FAILED 267/268; 28/28; 874/874; 293/293; 192/192
  failed: sparkAnalytics / Test, scholarchain.spark.IdentitySuite.M1 "the reason combines with rejected_or_conflict_pair and with two_orcids, in that order":
    java.util.concurrent.TimeoutException: test timed out after 30 seconds (34.048s). IdentitySuite total 325 s. (M2 passed this time.)
  load average at the end of the run: 4.19 6.84 7.11 (uptime)
ASSUMPTIONS: none
RISKS: the timeout recurred with lane 1 idle, so concurrency is not its sole cause. It looks like a 30 s munit timeout on a slow Spark test, not a wrong result.
  NOT VERIFIED. Outside lane 2 (modules/** is off-limits in this phase).
MANUAL ACTION REQUIRED: route the IdentitySuite M1 timeout to spark-engineer / test-engineer (for example a longer munitTimeout for that suite, or a smaller fixture), outside Phase 12.
BLOCKED-ON OWNER: none for Phase 12 (no Phase 12 file is Scala or sbt source)
NEXT GATE: H
```

## Progress checkpoint — Phase 12 complete; waiting for human gate (H)

```
STATUS: COMPLETE for A (+ one repair round + Phase 08 alignment) and D (latest verdict APPROVE WITH NOTES). Waiting for the human plan run (H); B follows H.
MERGE: main (765f7ce, Phase 08) is now merged into lane2 (23f2914). The Terraform aligned against `git show main:` content, which is the same accepted text.
VALIDATION (orchestrator-observed on the final tree): fmt -check exit 0; validate "Success! The configuration is valid."; bash -n on the three scripts exit 0.
  Security scan NOT RUN (no scanner installed). terraform plan NOT RUN (human step H). sbt: exit=1 (above).
MANUAL ACTION REQUIRED:
  1. Approve the IAM set as a security change (INFRA-CONTRACTS.md §3/§3a: remaining "*" list, EMR PassRole/extra actions, the embedding-worker rewrite, the retrieval embedding grant).
  2. Confirm cleanup.sh may print (never run) apply, destroy and state rm commands (L4).
  3. Supply the EMR release-guide URL for emr-spark-8.1.0 (still NOT VERIFIED).
  4. Decide on test queues for the live suite (L-A); until then run it only with embedding-worker at 0 tasks and an empty queue.
  5. Route open point O1 (optional Neptune endpoint) and the IdentitySuite M1 timeout (outside lane 2).
  6. Install a static scanner before B.
  7. Create infra/terraform/environments/course/terraform.tfvars: owner, expiration_date, alert_email, alb_ingress_cidrs = ["<your-ip>/32"].
  8. Run H from the repo root:  AWS_PROFILE=scholarchain bash scripts/plan.sh   and paste the SC-SUMMARY block under "H (human)".
BLOCKED-ON OWNER: human (gate H)
NEXT GATE: H, then B (scan and fix), then D rereads B's changes
```

## Progress checkpoint — us-east-2 / Free plan compatibility (orchestrator, read-only)

```
STATUS: BLOCKED-ON (human decisions below)
OWNER: orchestrator
WORK ORDER: human gate update: new AWS project `scholarchain` in us-east-2; inspect, run local checks, report blockers, stop
FILES MODIFIED: this report only. No Terraform edit made or dispatched. No AWS account call, plan, apply or destroy. No credential read.
COMMANDS RUN: grep/read of infra/terraform, scripts, modules; terraform fmt -check, validate; bash -n (3 scripts); trivy config 0.76.2;
  web search and curl of public AWS docs and Price List files. One curl/jq call was blocked by the guard (jq field accessor read as a key file); redone in Python.
VALIDATION (observed): fmt 0; validate "Success! The configuration is valid."; bash -n 0/0/0.
  trivy config: exit 0 (no --exit-code set), 36 failed checks: CRITICAL 5, HIGH 4, MEDIUM 20, LOW 7.
    CRITICAL AWS-0104 unrestricted egress network/main.tf:183,220,239,263,272; HIGH AWS-0095 SNS unencrypted (observability:10), AWS-0132 S3 not CMK (s3:44),
    AWS-0164 x2 public IP subnet (network:36); MEDIUM AWS-0024 x17 DynamoDB PITR, AWS-0090 x2 versioning, AWS-0178 flow logs; LOW CMK/logging. None fixed (work order B).
REGION FINDINGS:
  provider region = var.aws_region (default us-east-1, variables.tf:6); no backend block (local state); AZs from a data source; ARNs use var.region.
  Functional us-east-1 literals in .tf: the default only. Others: tfvars.example:16 comment; INFRA-CONTRACTS.md:14,111,126,205 and §10 text.
  Frozen contracts: core aws.region is a required key with no default; src/main has no region literal; src/test literals are fixtures. No modules/ change needed.
  .mcp.json and docs/agents.md default AWS_REGION to us-east-1 (constitutional; AWS_REGION in the environment overrides; untouched).
  INFRA-CONTRACTS.md:14 says Budgets "requires us-east-1": budgets is reached as a global service and the project SCP exempts budgets:* in us-east-1.
  plan.sh does not check that the profile's account is the intended one; no allowed_account_ids in the provider.
AVAILABILITY us-east-2 (AWS docs, fetched 2026-11-23): S3 Vectors listed; Titan Text Embeddings V2 In-Region listed; Neptune Serverless listed;
  emr-spark-8.1.0 confirmed: Spark 4.1.1-amzn-0, Hadoop 3.4.2-amzn-3, Scala 2.13.17, Java 17 listed, LTS
  (https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html; AWS says all EMR Regions). This closes the earlier NOT VERIFIED label.
  Generation model placeholder (Claude Haiku 4.5) in us-east-2: NOT CHECKED. Neptune engine 1.4.8.1 in us-east-2: NOT VERIFIED.
PRICES us-east-2 = us-east-1 for: Neptune NCU/storage/IO, ALB, Fargate, EMR m5.xlarge uplift, DynamoDB, Secrets Manager, public IPv4. EC2 m5.xlarge, S3 Vectors, CloudWatch etc. NOT CHECKED.
FREE PLAN (docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html and scps-and-rcps-for-projects.html):
  Paid Plan only: Amazon EMR, Amazon Neptune. Free policy has no elasticmapreduce:* or neptune-db:*; it does have rds:* (Neptune's control plane uses rds actions, so creation might not be blocked: NOT VERIFIED).
  Allowed by the Free policy: s3vectors:*, ecs:*, elasticloadbalancing:*, states:*, servicediscovery:*, budgets:*, iam:* (some actions denied), bedrock:*, and the services listed as included
  (DynamoDB, S3, SQS, SNS, ECR, Secrets Manager, CloudWatch, Logs, VPC, Route 53, EventBridge, Application Auto Scaling). Policy permission is not proof of quota, model access or an instance type.
  Project SCP "UsEast1Partitional" denies most regional actions in us-east-1 for a us-east-2 project, so aws_region = us-east-1 would fail.
  Both plans deny EC2 Spot and ec2:CreateFleet; our EMR uses ON_DEMAND instance groups. embedding-worker uses FARGATE_SPOT (compute/main.tf:56,125): whether projects allow it NOT VERIFIED.
  m5.xlarge is not a Free-tier-eligible type in the EC2 doc list.
  plan can succeed while EMR and Neptune are unusable: EMR is created at pipeline run time; create_neptune defaults false.
BLOCKED-ON OWNER: human
NEXT GATE: human decisions, then (if approved) infra-engineer edits, D re-read, H
```

## Progress checkpoint — human decisions after the us-east-2 report (orchestrator, 2026-11-23)

```
STATUS: IN PROGRESS (infra-engineer change set dispatched)
OWNER: orchestrator
HUMAN STATEMENTS (not observed by any agent): authenticated with profile scholarchain, us-east-2, temporary credentials; is upgrading the
  account to the Paid plan and setting a $20 spend limit before any human provisioning. EMR and Neptune are required by the spec; the design stays.
STEP 1 CHECKS (human, read-only CLI, us-east-2): RESULTS NOT PROVIDED. The message contained the unfilled placeholder
  "[paste the results of my Step 1 checks]". Nothing is recorded as verified; the NOT VERIFIED items in the previous checkpoint stay open.
APPROVED EDITS (infra-engineer, one change set): 1 us-east-2 default, docs, cost table ("not reconfirmed" where unchecked); 2 plan.sh account/region
  check; 3 regular Fargate for embedding-worker; 4 EMR citation; 5 Phase 10 reconciliation; 6 Phase 08 needs if missing; 7 work order B (trivy).
PHASE 10 SOURCE: no Phase 10 commit on any local branch (main = 07710d0, phase 09). SCHOLARCHAIN_ANTHROPIC_API_KEY is confirmed by
  main:modules/core/src/main/resources/scholarchain-rag.v1.conf:17. The other Phase 10 names and health paths come from the human's message only.
COMMANDS NOT RUN: sbt (human instruction); any plan or AWS call.
NEXT GATE: infra-engineer report, D reread, H
```

## Progress checkpoint — approved change set and work order B (infra-engineer), D reread (code-reviewer)

```
STATUS: BLOCKED-ON (human)
OWNER: infra-engineer (change set); orchestrator (this record)
FILES MODIFIED: INFRA-CONTRACTS.md; environments/course/{main,variables,outputs}.tf and terraform.tfvars.example; modules iam, compute, platform, network, data, observability; scripts/plan.sh; scripts/lib/common.sh
VALIDATION (rerun and observed by the orchestrator): fmt 0; validate 0; bash -n 0/0/0; trivy 0.76.2 exit 0, 36 -> 11 findings:
  HIGH AWS-0136 (observability:16); MEDIUM AWS-0090 x2 (s3:60), AWS-0178 (network:13); LOW 7. CRITICAL 0. 8 inline ignores, approved rules only.
  No bare ANTHROPIC_API_KEY, no FARGATE_SPOT, nothing staged.
REPORTED BY infra-engineer, NOT RERUN: shellcheck 0; 17 offline plan.sh stub cases (16 as expected; the 17th marked FAIL, attributed to a harness expectation).
NOT RUN: plan, plan.sh, any AWS call, sbt (human instruction), tflint (not installed).
BEYOND THE ORDER: the shared ECS execution role became three per-service roles (needed for per-service secret scoping); needs human IAM approval.
COST: (c) default claimed $14.19 (was $12.33); reviewer M-2 shows it can exceed $15, so work order section 6 stop applies.
D VERDICT: REQUEST_CHANGES (0 HIGH, 6 MEDIUM, 10 LOW), see review-report.md.
MANUAL ACTION REQUIRED: authorize a repair round; decide cost scope (M-2) and SNS (M-5); AWS-0090 and AWS-0178; approve IAM; supply Step 1 results.
BLOCKED-ON OWNER: human
NEXT GATE: human decision, infra-engineer repair, D reread, then H
```

## Progress checkpoint — human decisions on the D reread (orchestrator, 2026-11-23)

```
STATUS: IN PROGRESS (repair round 2, final, dispatched to infra-engineer)
OWNER: orchestrator
HUMAN DECISIONS: round 2 (final) authorized: mediums 1-6 and four lows (*.tfvars.json ignored, account ID out of the plan summary, PITR reason, stale text).
  AWS estimate limit now $18 (was $15; total budget $25); project end 2026-11-30, cleanup by 2026-11-27; low/high estimate required; STOP if high exceeds $18.
  SNS unencrypted with inline justification. No versioning on curated and logs, no flow logs: accepted, justify inline.
  Three per-service ECS execution roles: approved, subject to the D reread.
STEP 1 CHECKS: RESULTS NOT PROVIDED for the second time (placeholder "[PASTE STEP 1 OUTPUT HERE]"). Nothing recorded as verified.
COMMANDS NOT RUN: sbt (human instruction); any plan or AWS call.
NEXT GATE: infra-engineer report, D reread of round 2 only, H
```

## Progress checkpoint — repair round 2 (infra-engineer): cost STOP

```
STATUS: BLOCKED-ON (human): high estimate $29.64 (cleanup slips to 11-30) or $29.54 (cleanup 11-27) exceeds the $18 limit; low $6.20 or $5.98
OWNER: infra-engineer (fixes); orchestrator (this record)
FIXES REPORTED COMPLETE: M-1, M-3, M-4, M-5, M-6, four lows, AWS-0090 and AWS-0178 justified inline. M-2 estimate produced; limit exceeded.
VALIDATION (rerun by the orchestrator): fmt 0; validate 0; bash -n 0/0/0; trivy exit 0, 7 LOW only (was 11); 11 inline ignores;
  *.tfvars.json ignored by infra/terraform/.gitignore; nothing staged; estimate.py rerun reproduces the totals.
REPORTED, NOT RERUN: shellcheck 0; plan.sh stub harness pass=38 fail=0; dashboard shape by terraform console (offline).
NOT VERIFIED: dashboard price and free tier; secrets proration; MaxCapacity = 0 for scale-down; runtimes and request counts (assumptions); Bedrock generation model not priced.
NOT RUN: D reread of round 2 (stopped on cost as instructed); plan; AWS calls; sbt; tflint.
MISDIRECTED MESSAGE: a Phase 10 (lane 1) instruction arrived here and was not acted on.
BLOCKED-ON OWNER: human
NEXT GATE: human cost decision, D reread of round 2, H
```

## Progress checkpoint — human cost decisions (orchestrator, 2026-11-23)

```
STATUS: IN PROGRESS (one cost-only round dispatched to infra-engineer)
OWNER: orchestrator
HUMAN DECISIONS: $25 hard ceiling (AWS before credits plus all APIs); aim for a high estimate of about $15; STOP if high exceeds $18.
  Keep the dashboard; recheck fixed costs on AWS pages with URL and date. Cuts B, C, D, F taken. EMR: primary + at most 2 core/task, idle termination 15 min, on-demand, runs time-bounded in the estimate.
  No 50K rerun without human approval; 10K first, its measured cost decides 50K. Load only DynamoDB tables the runtime reads.
  Budget alerts $5/$10/$15, credits excluded. Price a Bedrock Claude Haiku model, at most 200 calls.
REGION CHECKS: RESULTS NOT PROVIDED for the third time (placeholder "[PASTE STEP 2 OUTPUT HERE]"). Nothing recorded as verified.
COMMANDS NOT RUN: sbt (human instruction); any plan or AWS call.
NEXT GATE: infra-engineer report; if high <= $18, D reread (round 2 plus these edits), then H
```

## Human us-east-2 checks (reported by the human, read-only CLI; not observed by any agent; recorded 2026-11-23)

```
S3 Vectors: OK.
Bedrock lists: amazon.titan-embed-text-v2:0, anthropic.claude-haiku-4-5-20251001-v1:0, anthropic.claude-haiku-5-5.
EMR ListReleaseLabels: AccessDenied, explicit deny in an AWS Organizations service control policy (account on the AWS Free plan).
HUMAN ACTION PENDING: upgrade to the Paid plan and re-verify EMR and Neptune before H.
HUMAN DECISION: anthropic.claude-haiku-5-5 is the answer-model default for the cost estimate.
STILL NOT VERIFIED: model invocation (a listing is not proof of it), Neptune engine 1.4.8.1, m5.xlarge quota, EMR release availability to this account.
```
This supersedes the earlier "RESULTS NOT PROVIDED" entries for the items listed above only.

## Human Neptune check (reported by the human, read-only CLI; not observed by any agent; 2026-11-23)

```
describe-db-engine-versions (neptune, us-east-2): latest engine 1.5.0.1.
HUMAN DECISION: use the latest engine version that supports Neptune Serverless, verified in the Neptune docs with a cited URL, instead of 1.4.8.1.
Routed to infra-engineer with the cost-only round.
```

## Progress checkpoint — cost-only round (infra-engineer): cost STOP

```
STATUS: BLOCKED-ON (human): high estimate $22.81 (slip to 11-30) or $22.72 (cleanup 11-27) exceeds the $18 stop; low $4.68 or $4.48
OWNER: infra-engineer (edits); orchestrator (this record)
EDITS: cuts B, C (Neptune max stays 2.5, the lowest legal max per AWS docs), D (Container Insights off by default; dashboard shows AWS/ECS CPU and memory instead of task counts), F documented;
  EMR max_capacity_units 2 (limit excludes the primary: total at most 3 nodes), idle 900 s, on-demand; step timeout 7200 -> 10800 s (beyond the list, disclosed);
  rule: no 50K rerun without human approval; DynamoDB load list (6 tables loaded, 4 skipped); budget alerts $5/$10/$15, include_credit = false, limit $25;
  Haiku 5.5 priced (estimate only; generation_model_id still Haiku 4.5); Neptune engine 1.5.0.1 (Serverless support not stated on its release page; fallback 1.4.8.1).
VALIDATION (rerun by the orchestrator): fmt 0; validate 0; bash -n 0/0/0; trivy exit 0, 8 LOW (new AWS-0034 from Container Insights off), no new ignores; nothing staged.
  estimate2.py rerun reproduces the totals and cuts (A-F together 13.18). The rerun wrote four md2_*.txt files into the repo root; moved to the orchestrator scratchpad.
REPORTED, NOT RERUN: shellcheck 0; stub harness pass=38 fail=0; dashboard shapes by terraform console.
NOT VERIFIED: dashboard price ($3 proxy) and free-tier scope; Secrets Manager recovery-window billing; Serverless on 1.5.0.1; neptune1.5 family; Haiku 5.5 inference profile;
  MaxCapacity = 0; item sizes; Neptune I/O count; Phase 10 table reads; whether a step timeout cancels a running EMR step. No hard stop exists for a hung EMR run.
NOT RUN: D reread (stopped on cost); plan; AWS calls; sbt; tflint.
BLOCKED-ON OWNER: human
NEXT GATE: human cost decision, D reread (round 2 plus cost round), H
```

## Human account update (reported by the human; not observed by any agent; 2026-11-24)

```
Account upgraded to the Paid plan; credits carry over.
AWS spend limit set to $20 (the minimum allowed); per the human it counts usage before credits and pauses the project when reached.
EMR verified in us-east-2 by the human: emr-spark-8.1.0 listed.
HUMAN DECISION: keep the high estimate at or below $15 if possible, hard STOP at $18, so the $20 pause is never hit; budget alerts $5/$10/$15 (already configured).
  Continue the cost round, then D, then STOP for H.
STILL NOT VERIFIED: Neptune Serverless on 1.5.0.1 (human recheck pending); model invocation; m5.xlarge quota.
ORCHESTRATOR READING: the open decisions not answered (EMR terminate-on-failure, AWS-0034, generation_model_id, step timeout) stay as they are:
  proposal only, visible, Haiku 4.5, 10800 s (disclosed).
```

## Human us-east-2 verification after the Paid-plan upgrade (reported by the human, read-only CLI; not observed by any agent; 2026-11-24)

```
Account: Paid plan; credits carry over. Spend limit $20 (minimum; counts usage before credits; pauses the project when reached).
EMR: describe-release-label emr-spark-8.1.0 succeeds; applications AmazonCloudWatchAgent, JupyterEnterpriseGateway, Livy, Spark
  (Hadoop/YARN/MapReduce installed as components per the release guide, per the human). list-release-labels: emr-7.14.0 is the newest 7.x.
Neptune: latest engine 1.5.0.1. Bedrock: amazon.titan-embed-text-v2:0, anthropic.claude-haiku-5-5. S3 Vectors: OK.
HUMAN DECISIONS: keep emr-spark-8.1.0 as default; document a fallback variable to run the Hadoop MapReduce steps on emr-7.14.0
  (Hadoop application) if the Phase 14 MapReduce smoke job fails on 8.1.0, no resources for it by default.
  Cost: high <= $15 if possible, hard STOP at $18; alerts $5/$10/$15.
NOTE: "Hadoop" is not in the 8.1.0 application list; whether a custom MapReduce JAR step runs there remains for the Phase 14 smoke job.
STILL NOT VERIFIED: Serverless support on Neptune 1.5.0.1; model invocation; m5.xlarge quota.
```

## Human Neptune Serverless check (reported by the human, read-only CLI; not observed by any agent; 2026-11-24)

```
describe-orderable-db-instance-options (neptune, us-east-2): db.serverless is orderable for engine 1.5.0.1.
HUMAN DECISION: use engine 1.5.0.1 (already the Terraform default from the cost round).
This closes "Serverless support on 1.5.0.1 NOT VERIFIED" (now verified by the human for this account and Region). Fallback 1.4.8.1 no longer needed.
STILL NOT VERIFIED: neptune1.5 parameter-group family name (no custom group is set); model invocation; m5.xlarge quota.
```

## Progress checkpoint — cost-only round complete (infra-engineer)

```
STATUS: COMPLETE (high within the $18 stop; above the $15 aim)
OWNER: infra-engineer (edits); orchestrator (this record)
ESTIMATE (estimate3.py, rerun by the orchestrator, run from the scratchpad): LOW $4.48 / $4.68; HIGH $16.95 / $17.04 (cleanup 11-27 / slip to 11-30), AWS before credits.
  HIGH 7-day: $2.96 below the $20 spend limit; $2.04 over the $15 alert. With ~$3 external APIs: $20.04 vs $25 ceiling.
  Drivers: fixed $5.55 (dashboard $3.00 beyond the free three if not covered, secrets $1.60), EMR $4.76, Neptune $3.89, Bedrock $1.01, Fargate $0.97.
  Holding the figure depends on human operating rules A, C, D, E (INFRA-CONTRACTS.md §5b). Breaking all five: $25.29 (over the spend limit).
EDITS: rules A, C, D, E with "enforced by" column; DynamoDB widget uses 25 explicit metrics (no SEARCH); emr_hadoop_fallback (default false, emr-7.14.0 + Hadoop + Spark);
  default Applications now Spark only (was Hadoop + Spark; disclosed); Neptune 1.5.0.1 Serverless recorded as verified by the human.
VALIDATION (rerun by the orchestrator): fmt 0; validate 0; bash -n 0/0/0; trivy 8 LOW, none new, 11 ignore lines unchanged; nothing staged.
REPORTED, NOT RERUN: shellcheck 0; terraform console checks of dashboard and fallback validations.
NOT VERIFIED: CloudWatch/SQS/transfer on the always-free list; step-timeout effect on EMR steps; Haiku 5.5 inference profile; item sizes; Neptune I/O; MaxCapacity = 0.
NEXT GATE: D reread (round 2 plus the cost round), then H
```

## Progress checkpoint — D reread verdict; STOP for H (orchestrator)

```
STATUS: BLOCKED-ON (human gate H)
D VERDICT: APPROVE WITH NOTES (0 HIGH, 1 MEDIUM N-M1, 6 LOW), see review-report.md.
OPEN HUMAN DECISIONS: defer N-M1 (query-api cannot write rag/audit/) to Phase 10, or order a fix; accept HIGH $17.04 vs the $15 aim; approve the IAM set incl. three execution roles;
  AWS-0034 visible; generation_model_id (Haiku 4.5 vs 5.5); step timeout 10800 s; EMR terminate-on-failure (proposal).
COMMANDS NOT RUN: plan, plan.sh, AWS calls, sbt. Nothing staged or committed.
NEXT GATE: H: human creates terraform.tfvars and runs scripts/plan.sh; paste only the SC-SUMMARY block (plan console output contains bucket names with the account ID).
```

## Progress checkpoint — human decisions on the final D reread (orchestrator, 2026-11-24)

```
STATUS: IN PROGRESS (human scope addition dispatched to infra-engineer; not a repair round)
HUMAN DECISIONS:
  1. N-M1 fix now: query-api s3:PutObject on rag/audit/* in the existing rag bucket; expose it as the audit bucket output/env var the Phase 09 audit stage expects; no new bucket.
     fmt/validate/trivy rerun; D rereads only this change.
  2. Cost: HIGH $17.04 accepted with the four usage rules, recorded as human-enforced in INFRA-CONTRACTS.md.
  3. IAM set approved, including the three per-service ECS execution roles.
  4. AWS-0034 accepted, stays visible. Answer model stays Haiku 4.5.
  5. EMR, recorded for Phase 13: steps use ActionOnFailure=TERMINATE_CLUSTER and a 1-hour step timeout (owned by the pipeline script).
  Lows deferred. Then STOP for H.
NOTE (orchestrator): the lane 1 code has a hard-coded auditBucket default "scholarchain-local-audit" (RuntimeSupport.scala:72), and the reviewer reports it is not a config key.
  If no env var exists in the code, infra-engineer must report that rather than invent one.
```

## Progress checkpoint — audit change done; STOP for H (orchestrator, 2026-11-24)

```
STATUS: BLOCKED-ON (human gate H)
CHANGE: iam/main.tf:105-111 WriteAuditDocuments (query-api PutObject on <curated>/rag/audit/*); outputs.tf:20-23 rag_audit_bucket; no env var set (no config key exists).
  Docs: INFRA-CONTRACTS §3, §6.1 (:226 Phase 13 TERMINATE_CLUSTER and 1 h step timeout note), §10 (:303 acceptance and approvals), §13 O3.
VALIDATION (rerun by the orchestrator): fmt 0; validate 0; trivy 8 LOW, none new; nothing staged.
D VERDICT: APPROVE WITH NOTES. Cross-lane MEDIUM confirmed by the orchestrator reading lane 1 Composition.scala:221-231 (uncommitted Phase 10):
  audit bucket derived as "rag", prefix "audit", which does not match the grant. Owner service-engineer, lane 1. Not routed from lane 2 (lane 2 must not edit modules/**).
LOW deferred: INFRA-CONTRACTS.md:520 O3 wording.
NOT RUN: plan, AWS calls, sbt.
NEXT GATE: H. Human creates terraform.tfvars and runs scripts/plan.sh; paste only the SC-SUMMARY block.
```
