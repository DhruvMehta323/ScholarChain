# ScholarChain runbook

Draft (Phase 17a). Steps are taken from the committed script headers, [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) and the phase gates. **No step in this runbook has been run against AWS**; every AWS outcome is `[[PENDING-AWS: ...]]`. Commands marked **HUMAN-ONLY** are never run by an agent or a script ([docs/agents.md](agents.md) section 4). Replay status of each command is stated in section 9.

Common conventions: run from the repository root; `AWS_PROFILE` is set to a named profile and `AWS_REGION=us-east-2`; secret values never appear on a command line, in a report or in the repository.

## 1. Deployment

The ordered checklist is [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 14.4. In order:

1. Local validation: `bash scripts/check.sh` (add `--skip-sbt` if `sbt check` was just run) and `bash scripts/tests/test-scripts.sh`. Both must pass. The 13 gate records the harness at `RESULT passed=253 failed=0` ([phase-13 gate](agent-runs/phase-13-automation/human-gate.md) section 2); fresh replay output for the harness and for `bash scripts/check.sh --dry-run` is [[PENDING-EVAL: replay output not yet committed]]; the full `check.sh` was not run for this draft (it includes `sbt check` and Terraform).
2. Plan (read-only, human, own terminal): `AWS_PROFILE=<profile> AWS_REGION=us-east-2 bash scripts/plan.sh`. Note the plan SHA-256 (also in `infra/terraform/plans/<base>.sha256`). Review the summary and the cost table. The Phase 12 plan is superseded: a new plan after MERGE-1 and MERGE-2 is required ([phase-13 gate](agent-runs/phase-13-automation/human-gate.md) decision 3).
3. Record the decision and the approved plan SHA-256 in `docs/agent-runs/<phase>/human-gate.md` (written by the human, committed so the tree is clean).
4. **HUMAN-ONLY:** apply the saved plan in your own terminal: `AWS_PROFILE=<profile> terraform -chdir=infra/terraform/environments/course apply infra/terraform/plans/<base>.tfplan`.
5. Confirm the SNS email subscription and the AWS Budgets subscription from the alert inbox (G12-6). Until confirmed, alarm emails are not delivered.
6. Set the four secret values by hand (outside Terraform and outside the repository) as described in [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 9 step 5: write each value to a mode-600 temporary file outside the repository, store it with `aws secretsmanager put-secret-value --secret-id <name> --secret-string file://<temp file>`, then delete the temporary file. Which secrets the Bedrock-only deployment still needs depends on MERGE-2 (the Anthropic and Voyage secrets are not used): `[[PENDING-AWS: secret set after MERGE-2]]`. A task fails to start while a secret it injects is empty.
7. Package from the clean committed merge commit: `bash scripts/package.sh`. Do not deploy the `932fd2f-dirty` images ([phase-11 gate](agent-runs/phase-11-adversarial-review/human-gate.md) section 6). Check that the printed image tag equals the commit SHA ([phase-13 gate](agent-runs/phase-13-automation/human-gate.md) note N2).
8. Switch Neptune and the load balancer on for the window: `bash scripts/env-window.sh up` prints the human steps (`create_neptune = true`, `create_alb = true` in the gitignored `terraform.tfvars`, plan, review, human apply). Cost: about $0.16 per hour for Neptune and about $0.04 per hour for the ALB ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 9a). The window total is limited to 8 hours (operating rule A, human-enforced).
9. Deploy (deploy-engineer, after the recorded approval): `APPROVED_PLAN_SHA256=<sha> AWS_PROFILE=<profile> AWS_REGION=us-east-2 bash scripts/deploy.sh --gate-file docs/agent-runs/<phase>/human-gate.md`. Desired counts default to 1, 1 and 0. The script pushes images to ECR by commit tag, registers a new task-definition revision per service, updates the three services, waits, verifies, and writes `reports/deployments/<sha>/deployment.json` (first as `in-progress`, finally `succeeded` or `failed`). It refuses a dirty tree, a plan record that is not under `infra/terraform/plans/`, a `.tfplan` whose hash differs, a gate file that lacks the SHA-256, and non-release artifacts. Terraform will show the task-definition and image as drift afterwards (M13-6): set `image_tag` to the deployed tag before any later apply.
10. Smoke test: see section 2.
11. Load and enqueue stages of the bounded pipeline have no EMR entry point yet (M13-2); until it is resolved `run-bounded-pipeline.sh` refuses them with exit 7 ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 14.3). `[[PENDING-AWS: how the load and enqueue steps ran in Phase 14]]`.

Bounded pipeline: `AWS_PROFILE=<profile> AWS_REGION=us-east-2 bash scripts/run-bounded-pipeline.sh --scale 1k|10k|50k [--stages hadoop,spark,load,enqueue] [--core-count 1|2] [--run-id <token>] [--dry-run]`. First run `--smoke` (a tiny MapReduce job) to confirm MapReduce works on `emr-spark-8.1.0`; the fallback `--hadoop-fallback` (`emr-7.14.0`) is for MapReduce only and is decided only after the smoke job fails for a release reason ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 6.2). 50K is refused without a successful 10K record and `--approve-50k`; the human must also compare measured Neptune I/O with 3M requests first (operating rule E). The cluster terminates itself (EMR auto-termination, `ActionOnFailure = TERMINATE_CLUSTER`, 1-hour step limit); the script never terminates a cluster.

## 2. Smoke tests

`SC_SMOKE_API_KEY_FILE=<mode-600 file outside the repository> AWS_PROFILE=<profile> AWS_REGION=us-east-2 bash scripts/smoke-test.sh --allow-insecure-http`

- The key file: a human creates it with their own credentials (`bash scripts/smoke-test.sh --print-key-help` prints the steps); the script never reads Secrets Manager (the guard blocks that) and never prints the key; delete the file after the window ([phase-13 gate](agent-runs/phase-13-automation/human-gate.md) decision 1).
- `--allow-insecure-http` is required while the ALB has no TLS listener; the key crosses the network in clear text, so ingress is limited to your /32 and the key is rotated after the last smoke or experiment run (decision 2).
- Checks: `/healthz`, `/readyz`, one bounded `POST /v1/query`, evidence and explain for the returned claim, an idempotent repeat with the same `Idempotency-Key`, and a 401 without a key. Output: `reports/deployments/<sha>/smoke-test.json` (check names, statuses, latencies only).
- Also force one CloudWatch alarm and confirm its email arrives ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 7).
- Result: `[[PENDING-AWS: smoke-test.json from Phase 14]]`. In particular whether the runtime accepts the `us.` Haiku 4.5 inference profile is first observed here.

## 3. Common failures

| Symptom | Likely cause (source) | Action |
|---|---|---|
| `scripts/plan.sh` refuses before planning | account or Region differs from `expected_account_id` / `aws_region`; forbidden `*.auto.tfvars`, override files, `TF_WORKSPACE`, `TF_CLI_ARGS*` ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 9-plan) | fix the input; the script never prints account IDs |
| `deploy.sh` exit 3 | dirty tree, plan record or `.tfplan` mismatch, gate file lacks the SHA-256, non-release artifacts | re-run `package.sh` from a clean tree; fix the gate file (human) |
| `deploy.sh` exit 4 | Terraform outputs missing (nothing applied) | the script prints the exact human command; do not run apply from a script |
| ECS task fails to start | an injected secret is still empty ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 9 step 5); or Neptune is off while the services require `SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT` (open point O1) | set the secrets; switch Neptune on (section 1 step 8) |
| `/readyz` returns 503 | a dependency or a required prompt file or config is missing; the response carries a reason code (`timeout`, `circuit_open`, `unavailable`) ([API.md](../modules/services/API.md)) | read the reason code; the ALB checks only `/healthz`, so readiness failures do not cycle tasks |
| Queries return 502, 503 or 504 | downstream timeout or open circuit breaker (opens after 5 consecutive failures, probes after 10 s); mismatched internal keys give a failure rather than a partial answer | check retrieval health and `SCHOLARCHAIN_INTERNAL_API_KEY` on both services |
| `POST /v1/query` returns 422 | an option this deployment does not honor (for example `retrieval.graphLimit: 0`, or any `vectorLimit` other than 30) ([API.md](../modules/services/API.md)) | use the documented request shape |
| 429 `RATE_LIMITED` | 4 running and 16 queued queries reached | back off; retryable |
| Embedding messages in the DLQ | malformed content or repeated failure; the worker dead-letters with an envelope after `receiveCount` exceeds 3; alarm on DLQ depth above 0 and on queue age above 600 s | inspect the envelope; see incident section 5 |
| EMR step runs forever | idle auto-termination does not fire while a YARN application runs; Step Functions does not terminate the cluster ([INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 6.1) | the script's step limit and `TERMINATE_CLUSTER` apply; a human checks the EMR console and the budget dashboard; exposure about $0.76 per hour for 3 nodes |
| Terraform plan shows service drift | `deploy.sh` registered task definitions outside Terraform (M13-6) | set `image_tag` to the deployed tag |
| Destroy then re-apply fails on secret names | 7-day recovery window (G12-4) | see section 6 step 8 |
| Destroy fails on ECR | repositories hold images (`force_delete = false`) (G12-5) | empty ECR first (section 6 step 3) |
| A failing `sbt check` with a timeout or out-of-memory in a module the change did not touch | known flakiness under concurrent load; Hadoop AdversarialSuite timeout raised from 30 s to 120 s in Phase 08 ([phase-08 gate](agent-runs/phase-08-embeddings-vectors/human-gate.md) section 4) | rerun once unchanged and record both runs; do not hide a failure |

## 4. Rollback

`AWS_PROFILE=<profile> AWS_REGION=us-east-2 bash scripts/rollback.sh <mode> --reason "<text>" --approval-ref <file>` with exactly one mode: `--from-record <deployment.json>` (undo that deployment, using each service's `previous_task_definition`), `--to-sha <git sha>`, or `--revisions query-api=N,retrieval=N,embedding-worker=N`. It changes no data and no infrastructure definition and leaves desired counts as they are. A human approval record must exist (the script checks that the file exists and is not empty; it does not interpret it). Run it through `/rollback` (deploy-engineer) with a recorded human approval, then repeat the smoke test ([docs/agents.md](agents.md) section 6). Rollback of data (Neptune, DynamoDB, S3 Vectors loads) has no automatic path: the options are listed in [STORAGE-CONTRACTS.md](../modules/storage/STORAGE-CONTRACTS.md) section 10 and each needs a human decision. Rollback evidence for the spec: `[[PENDING-AWS: rollback exercise record]]`.

## 5. Incident escalation

- Start with `/incident <id> <environment> <symptom>` (incident-responder; records go to `docs/incidents/**`) ([docs/agents.md](agents.md) section 6). incident-responder executes only approved rollback and bounded recovery actions.
- Human decision, recorded in the phase `human-gate.md` before any action, is required for: constitutional changes, data destruction (including raw experiment results), security changes (IAM, KMS, network exposure, secrets, authentication), Terraform apply or destroy, deployment, expensive scale changes (EMR above 3 instances per group, larger instance classes, Neptune or OpenSearch sizing, provisioned capacity), and ambiguous ownership ([docs/agents.md](agents.md) section 4).
- The incident record must contain: incident ID, start and end time, detection source, user-visible impact, relevant trace and run IDs, timeline, root cause, recovery action, data-integrity assessment, follow-up owner, preventive test or control (course specification, section "Incident and rollback requirements"). Destructive incidents are not manufactured in a live shared account.
- Planned safe exercise: experiment F, failure and recovery ([experiments/README.md](../experiments/README.md) row 7). Its manifest command ends in `--dry-run`; `failure_drill.py` with `--execute --yes` and `--dry-run` together records a failed action that never ran, so never combine them ([phase-15b gate](agent-runs/phase-15b-experiment-harness/human-gate.md) N5). No incident record exists yet: `[[PENDING-AWS: incident exercise, Phase 15c]]`.

## 6. Cleanup (complete checklist)

Sources: [scripts/cleanup.sh](../scripts/cleanup.sh) header and printed commands, [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) sections 4, 9, 9a, 9b, 14.4, [phase-12 gate](agent-runs/phase-12-terraform/human-gate.md) G12-4 and G12-5, and the course specification section "Cleanup requirements". `scripts/cleanup.sh` is **HUMAN-EXECUTED**: the guard blocks agents from running it because its text contains the Terraform commands it prints. It deletes nothing except ECR images, and only with all three flags. Rehearsal in a safe environment is a Phase 17 gate item and has not happened: `[[PENDING-AWS: cleanup rehearsal and evidence]]`. The script was exercised only through the offline harness (stub `aws` and `terraform`) ([phase-13 gate](agent-runs/phase-13-automation/human-gate.md) section 5).

The cleanup agent or script must not delete evidence required for grading before the instructor approves its archival location (course specification, section "Cleanup requirements"); the instructor-approved archive location is `[[HUMAN: archive location]]`.

Ordered human steps:

1. **Inventory before.** `AWS_PROFILE=<profile> AWS_REGION=us-east-2 bash scripts/cleanup.sh --dry-run` lists what a cleanup would remove and prints the commands; without `--dry-run` (and without `--empty-ecr`) it also writes `reports/cleanup/<utc>/inventory-before.json` (counts of active EMR clusters, scholarchain Neptune clusters, DynamoDB tables, and capped bucket object counts) and `inventory-after.json`. Use `--no-aws` for a state-only inventory. The "after" inventory written in the same run is not the real after-state until the destroy is done; run the script again at step 9.
2. **Archive evidence and record the decision.** `bash scripts/archive-evidence.sh --to-s3` (copies test reports, plan checksum, release metadata, deployment records, run manifests and experiment records into `reports/evidence/<sha>/` with a SHA-256 `MANIFEST.json`; refuses to overwrite). Then the human records the cleanup decision in `docs/agent-runs/<phase>/human-gate.md`.
3. **Environment window down (ephemeral EMR, ECS services, load balancer, Neptune).** Ephemeral EMR clusters are self-terminating (idle auto-termination and `TERMINATE_CLUSTER`); confirm none is active from the inventory. `bash scripts/env-window.sh down --yes` scales the three ECS services to 0 through the ECS API and prints the human Terraform steps to switch Neptune and the ALB off. Application Auto Scaling may raise a service again if its minimum is above 0 (default 0); [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 9b gives the Terraform way (`ecs_min_tasks` and `ecs_max_tasks` at 0, plan, human apply). Neptune off needs `neptune_deletion_protection=false` applied first; it drops the graph (rebuilt from S3 on the next window) unless `neptune_skip_final_snapshot=false`. A cheaper partial stop without removing data is `create_neptune=false` and/or `create_alb=false`.
4. **ECR emptying.** Repositories have `force_delete = false`, so destroy fails while images remain. Empty them with the script: `bash scripts/cleanup.sh --empty-ecr --confirm-empty-ecr --gate-file docs/agent-runs/<phase>/human-gate.md` (needs all three; the gate file must exist and be non-empty; it removes images only, never repositories).
5. **Protection switches (human).** Printed commands B and C: plan with `-var neptune_deletion_protection=false -var dynamodb_deletion_protection=false -var force_destroy_intermediate=true -var vector_force_destroy=true`, review, then apply exactly that saved plan (this turns protections off and removes nothing). **HUMAN-ONLY.**
6. **Evidence preservation (human-only state change).** Printed command D drops the `raw` and `reports` buckets and their seven Terraform sub-resources each (bucket, public-access block, ownership controls, SSE configuration, versioning, lifecycle, TLS-only policy) from Terraform state, using `state rm -dry-run` first, so the following teardown cannot delete them. They remain in AWS intact (public access blocked, TLS-only, versioned, encrypted) but are no longer managed by Terraform; re-importing or deleting them is a separate human data decision. **Do not run step 7 if the state file was lost or replaced before this step.** Order matters: ECR, then B and C, then D, then E.
7. **The human destroy.** Printed command E: preview the removal (read-only plan), then run it. **HUMAN-ONLY**; no agent or script runs a destroy. It removes, per resource class:

   | Class (course specification) | What the committed design removes or leaves | Source |
   |---|---|---|
   | Ephemeral EMR resources | no persistent cluster exists; clusters are created per run; the state machine and EMR roles are removed by destroy | INFRA-CONTRACTS sections 6, 14.2 |
   | ECS services and tasks | cluster, 3 services, task definitions, autoscaling targets removed by destroy; scale to 0 first (step 3) | sections 5, 9b |
   | Load balancers and networking | ALB (only exists when `create_alb = true`), target group, listener, VPC, subnets, security groups, S3 and DynamoDB gateway endpoints removed by destroy; no NAT gateway or interface endpoint ever existed | sections 2, 9a |
   | Neptune resources | cluster and instance removed once deletion protection is off; graph lost unless a final snapshot was requested | sections 4, 9a |
   | DynamoDB tables | 17 tables removed once deletion protection is off (the test claims table has none); disposable and rebuilt from S3 | section 4 |
   | S3 Vectors indexes and vector buckets | 6 indexes and the vector bucket removed with `vector_force_destroy=true`; test-index vectors listed in STORAGE-CONTRACTS section 10 are removed with it | section 4; STORAGE-CONTRACTS section 10 |
   | Optional OpenSearch resources | none: OpenSearch was not implemented and is not created | INFRA-CONTRACTS section 5 |
   | ECR images | emptied in step 4; the 3 repositories removed by destroy | section 5, G12-5 |
   | S3 buckets and retained evidence | `curated`, `logs`, `artifacts` buckets removed (`force_destroy_intermediate=true`); `raw` and `reports` stay in AWS (step 6) | section 4, `cleanup.sh` |
   | Terraform state | the local state file (`environments/course/terraform.tfstate`, gitignored) is the only record of what exists; keep it until step 9; its final handling `[[HUMAN: where the final state file and plan files are retained]]` | section 1 |
   | KMS and Secrets Manager retention | **No customer-managed KMS key is created** (nothing to schedule for deletion; AWS-managed keys are not removable). The four secrets have a 7-day recovery window | section 3, G12-4 |

8. **Secrets Manager recovery-window note (G12-4).** After the destroy the four secrets stay scheduled for deletion for 7 days, and a new apply within those 7 days fails on the secret names. If the stack will be applied again sooner, the human may force-delete each secret **after** the removal: `aws secretsmanager delete-secret --force-delete-without-recovery --secret-id <name>` (printed by `cleanup.sh` for each of the four names, never run by it; this is data destruction and needs a human decision in `human-gate.md` first). Otherwise leave them to expire.
9. **Inventory after.** Run `bash scripts/cleanup.sh` again (or `--no-aws` for state only) and compare `inventory-after.json` with `inventory-before.json`. Explain every retained resource: the `raw` and `reports` buckets (evidence, retained by decision), the scheduled-for-deletion secrets (until the recovery window ends), any CloudWatch log groups or Budgets objects not in Terraform state `[[HUMAN: confirm after the inventory]]`, and the local files. This explanation is the D20 "retained-state explanation": `[[PENDING-AWS: inventory-before.json, inventory-after.json and the explanation]]`.
10. **Retained evidence.** `reports/evidence/<sha>/`, the human gates, the raw experiment results (write-once; never deleted by an agent; deletion of raw experiment results is a human-only data destruction, [docs/agents.md](agents.md) section 4), `reports/cleanup/<utc>/`, and the experiment and deployment records. The Phase 12 budget and tags carry `expiration-date` 2026-11-30 ([phase-12 gate](agent-runs/phase-12-terraform/human-gate.md) section 2).
11. **Local clean-up (human).** Delete the mode-600 key files outside the repository (`SC_SMOKE_API_KEY_FILE`, `SC_LOADTEST_API_KEY_FILE`) and rotate `SCHOLARCHAIN_SERVICES_API_KEY` if it was ever used over plain HTTP.

## 7. Cost watch during any window

Budget `scholarchain-budget`: $25 monthly, alerts at $5, $10 and $15 on actual spend before credits; alerts lag by 8 to 12 hours and do not stop spending; the human hard stop is $20 ([phase-12 gate](agent-runs/phase-12-terraform/human-gate.md) section 4). Rules A (windows at most 8 hours), C (logs about 0.5 GB), D (Fargate about 56 task-hours) and E (measure Neptune I/O before 50K) are human-enforced. Measured cost: `[[PENDING-AWS: Cost Explorer after each window]]`.

## 8. Experiments

Order, caps and approval points: [experiments/README.md](../experiments/README.md). Experiments are manifests that must be `"approved": true` with `approved_by` before `bash scripts/run-experiment.sh <id> --run-id <token>` runs them; raw results are write-once and failed runs stay on disk. Evaluation methods: [docs/evaluation.md](evaluation.md).

## 9. Replay status of commands in this runbook

| Command | Status |
|---|---|
| `bash scripts/tests/test-scripts.sh` | recorded in the 13 gate (`RESULT passed=253 failed=0`); fresh replay [[PENDING-EVAL: replay output not yet committed]] |
| `bash scripts/check.sh --dry-run` | [[PENDING-EVAL: replay output not yet committed]] |
| `python3 -B experiments/validate_manifests.py` | recorded in the 15b gate (`RESULT PASSED (0 problem(s))`); fresh replay [[PENDING-EVAL: replay output not yet committed]] |
| `bash scripts/plan.sh`, apply, `package.sh`, `deploy.sh`, `smoke-test.sh`, `run-bounded-pipeline.sh`, `env-window.sh`, `rollback.sh`, `archive-evidence.sh`, `cleanup.sh`, `run-experiment.sh` | **not replayed**: need AWS or are blocked for agents; each was exercised only in the offline harness during Phase 13 |
| `aws secretsmanager ...` | not replayed (secret-store commands are denied to agents) |
