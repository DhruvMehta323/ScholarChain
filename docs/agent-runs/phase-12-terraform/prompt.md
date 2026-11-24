# Phase 12 — AWS infrastructure in Terraform (work order)

Phase folder: `docs/agent-runs/phase-12-terraform/`
Lane: **lane 2** (worktree `ScholarChain-lane2`, branch `lane2`), in parallel with lane 1 (Phases 08–11).
Spec sections: "Phase 12: create the AWS infrastructure in Terraform", "Cost controls", "Security requirements", "Required scaling rules".
Budget: **$25 total** for the whole project, including AWS before credits and all external API costs. Design for the cheapest configuration that still meets the spec, with everything sized for hours of use, not weeks.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A | infra-engineer | `infra/terraform/**`, `scripts/plan.sh`, `scripts/cleanup.sh`, `scripts/lib/**` | always |
| H | **HUMAN** (no agent) | gitignored plan output | after A |
| B | infra-engineer | same as A | after H |
| D | code-reviewer | nothing | after A, and after B |

---

## 0. Rules for the orchestrator

1. You route; you do not write Terraform, scripts or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. **Lane 2 boundary:** no agent edits `build.sbt`, `project/**`, `modules/**`, `datasets/**`, `CLAUDE.md`, `docs/agents.md` or `.claude/**`. If one seems necessary, STOP and ask.
4. **No AWS calls by any agent.** No `terraform init` with a remote backend, no `terraform plan`, no `aws` command that reaches AWS. Allowed: `terraform fmt`, `terraform init -backend=false`, `terraform validate`, `bash -n`, and a local static security scanner. Never read `~/.aws`, `.env` or any credential. `terraform apply` and `destroy` are human-only and never appear in a script.
5. sbt is not needed. If the Stop hook asks for the canonical command, run exactly `sbt check; echo "exit=$?" > .claude/.last-test-run` once from the repo root.
6. Do not stage or commit. Append a `## Progress checkpoint` to `agent-report.md` after each work order.
7. Repair policy: fix HIGH and MEDIUM findings on security, cost, correctness or stateful-resource safety. Defer LOW findings with disclosure.

## 1. Preconditions (orchestrator, read-only)

- The cwd is the lane 2 worktree on branch `lane2`; `git status --short` is clean except this phase folder.
- Record the installed versions of `terraform`, and of any of `tflint`, `checkov`, `trivy` and `tfsec`. If no static security scanner is installed, record MANUAL ACTION REQUIRED (the human installs one with Homebrew) and continue; the scan runs in B.
- Read `docs/agents.md` §4–5 and `CLAUDE.md`: apply and destroy are human-only, and the guard blocks those phrases in Bash text.
- Read the core configuration contracts as documentation only (`modules/core/src/main/resources/*.conf`, `StorageModuleConfig`, `SparkConfig`) and list every bucket, table, index, queue, endpoint and environment variable name the application expects. Terraform outputs must line up with these names.

## 2. A — infra-engineer (author, validate, no AWS)

Everything under `infra/terraform/` (environment `infra/terraform/environments/course/` plus modules under `infra/terraform/modules/`), and `infra/terraform/INFRA-CONTRACTS.md`.

**Foundation:** pinned Terraform and AWS provider versions (current provider version that includes the `aws_s3vectors_*` resources; record its source). Region `us-east-1`, from a variable. Required tags on everything: course, project, environment, owner, expiration date. **State: local and gitignored** for this course environment (documented decision; no remote backend to bootstrap). Variables files contain no secrets.

**Network, the cheapest compliant design:**
- A VPC in two Availability Zones, with public and private subnets.
- **No NAT gateway.** Fargate tasks and EMR run in public subnets with public IPs and tight security groups (no inbound from the internet except the load balancer to the query service). Neptune and other data resources sit in private subnets.
- A free S3 gateway endpoint (Neptune's bulk loader needs it) and a free DynamoDB gateway endpoint. **No interface endpoints** (each costs hourly).
- Security groups chained by role (load balancer → query service → Neptune), never broad CIDR ranges except the load balancer listener.

**Identity and encryption:**
- Least-privilege roles, each scoped to named resources: EMR service and instance profile, ECS task roles per service, ECS execution role, Neptune loader role, and a deployment role (separate from runtime roles).
- AWS-managed encryption (SSE-S3, DynamoDB default, Neptune storage encryption), unless a customer-managed KMS key is required; justify any KMS key (it costs monthly).
- Secrets Manager entries are referenced by ARN only; Terraform never holds a secret value. (A Voyage key secret is created empty, with its value set by the human.)

**Data:**
- S3: raw, curated, graph, rag, artifacts, logs and reports buckets (or prefixes in fewer buckets; justify), with public-access block, encryption, versioning where needed, lifecycle rules expiring intermediate data, and `force_destroy = false`.
- DynamoDB: the five operational tables plus the ten analytics tables from `StorageModuleConfig`, on-demand capacity, with the key schemas documented in `modules/storage/STORAGE-CONTRACTS.md` (read it; do not change it). PITR off for the course environment (justified: disposable, rebuilt from S3).
- **Neptune Serverless** at the minimum capacity range, one instance, private subnets, encryption on, the loader role attached, `deletion_protection` as a variable (default true; the human flips it for cleanup), `skip_final_snapshot` as a variable.
- **S3 Vectors:** one vector bucket and the five indexes (`researcher-profile-v1`, `collaboration-community-v1`, `paper-abstract-v1`, `collaboration-path-v1`, `topic-institution-summary-v1`), plus a separate small test index for live suites. Dimension from a variable matching the embedding model.

**Compute and workflow:**
- **EMR** (release `emr-spark-8.1.0` from a variable) with Hadoop and Spark. Defined as a launch template or module the pipeline script uses (not an always-on cluster), with **auto-termination**, managed scaling with min 1 / max 3 core-or-task units (the guard's limit), spot task capacity optional, and logs to S3.
- **ECR:** repositories with immutable tags and scan-on-push.
- **ECS Fargate:** one cluster; **at least three independently deployable services**: `query-api` (behind the load balancer), `retrieval` (graph plus vector retrieval, internal) and `embedding-worker` (SQS consumer). Write these names and their environment variables in `INFRA-CONTRACTS.md` §Services; Phase 10 implements against that contract. Smallest Fargate sizes; desired count 0 by default (the human scales up for experiments); target-tracking autoscaling (CPU or requests for query-api and retrieval; SQS backlog per task for embedding-worker) with bounded maximums (for example 4).
- **Load balancer:** one Application Load Balancer for `query-api` only, with HTTP, or HTTPS if a certificate variable is given.
- **SQS:** the embedding queue plus a dead-letter queue, with redrive.
- **Step Functions:** one state machine for the bounded pipeline (EMR step submit, then load) or for the asynchronous query path; document which. Keep it small.

**Observability and cost:**
- CloudWatch log groups with 7-day retention; one dashboard showing ECS desired and running tasks, SQS depth and age, API latency, Neptune and DynamoDB errors; alarms on 5xx, DLQ depth and queue age.
- An AWS Budgets budget with alerts at $5, $12 and $20 (email from a variable).
- Outputs: every name and ARN the application config needs, matching §1.

**Scripts:**
- `scripts/plan.sh`: bash strict mode; refuses a dirty tree; `terraform init` then `terraform plan -out` for the selected environment, using `AWS_PROFILE` from the environment; prints greppable step markers; writes the plan file (gitignored), its SHA-256, the commit SHA, the account ID and Region, and a summary of add/change/destroy counts, IAM changes and stateful resources. Never applies.
- `scripts/cleanup.sh`: lists every resource that would be removed, preserves the evidence buckets per policy, and prints the exact human commands; it never runs destroy itself.

**Validation (agent-runnable, no AWS):** `terraform fmt -check -recursive`, `terraform init -backend=false`, `terraform validate`, `bash -n` on both scripts, `tflint` if installed.

**Cost estimate:** a table in `INFRA-CONTRACTS.md` of every billable resource with its hourly or per-use price from the official AWS pricing pages (cite the URL and date; do not guess), and the estimated cost of (a) one hour idle with everything up, (b) a 4-hour experiment day, (c) the whole planned project. Flag the largest drivers (expected: Neptune Serverless minimum capacity, the load balancer, EMR hours). If (c) exceeds $15 of the $25 budget, propose cuts.

Report the module tree, resources by type, the outputs, the validation results, the cost table, and the exact human command for H. Then **STOP for the human (H)**.

## 3. D (pass 1) — code-reviewer

Review A before the human plans: IAM scope (no `*` actions or resources without justification), public exposure, secrets in source, the NAT-free design's correctness, stateful-resource safety (deletion protection, `force_destroy`), cost drivers and bounded defaults, apply or destroy anywhere in scripts, output names matching the application config. Verdict. On REQUEST_CHANGES, one repair round before H.

## 4. H — HUMAN (plan; no agent)

The human installs a static security scanner if one is missing, then runs `AWS_PROFILE=scholarchain bash scripts/plan.sh` in their own terminal (read-only planning; no resources are created), and pastes the plan summary block under "H (human)" in agent-report.md.

## 5. B — infra-engineer (scan and fix)

Run the static security scanner on `infra/terraform/`, fix HIGH and MEDIUM findings or justify each skipped check inline, rerun validation, and update the cost table if the plan summary showed surprises. If anything changed, the human reruns `plan.sh` (record both summaries). D rereads only B's changes.

## 6. STOP points

STOP and ask the human if: a precondition fails; the estimate exceeds $15; a design needs a NAT gateway or interface endpoints; any step needs AWS credentials; a lane boundary would be crossed; or a second repair round is needed. Otherwise write the final checkpoint "Phase 12 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- The plan shows no unexplained destroy or replacement of stateful resources.
- IAM policies are scoped to actual resources and actions; runtime and deployment roles are separate.
- No bucket or database is public; no NAT gateway; no interface endpoints.
- Defaults are bounded (ECS desired 0, max 4; EMR max 3; auto-termination on).
- No secret appears in source or plan output.
- The cost estimate is cited and within budget.
- Service names and environment variables are written in `INFRA-CONTRACTS.md` for Phase 10.
- Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

`terraform apply` (Phase 14, human); deployment, smoke, pipeline and experiment scripts (Phase 13); application code; any AWS call by an agent.