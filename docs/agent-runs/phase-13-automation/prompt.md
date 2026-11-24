# Phase 13 — Build, deployment and experiment automation (work order)

Phase folder: `docs/agent-runs/phase-13-automation/`
Lane: **lane 2** (worktree `ScholarChain-lane2`, branch `lane2`). Phase 12 accepted commit: **5edd031**.
Spec sections: "Phase 13: create reproducible build, deployment, and experiment automation", "Cost controls", "Cleanup requirements".

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A | infra-engineer | `scripts/**`, `infra/terraform/INFRA-CONTRACTS.md` (script section only) | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write scripts inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. **Lane 2 boundary:** no agent edits `build.sbt`, `project/**`, `modules/**`, `datasets/**`, `infra/terraform/**` (except the INFRA-CONTRACTS script section), `CLAUDE.md`, `docs/agents.md` or `.claude/**`. If one seems necessary, STOP and ask.
4. **No AWS calls and no sbt** in this phase (lane 1 is running sbt). Allowed: `bash -n`, `shellcheck` if installed, running scripts in `--dry-run` / `--help` modes and against stub binaries on `PATH`. Never `terraform apply`, `destroy` or state commands; never `aws` commands that reach AWS; never `docker push`.
5. Do not stage or commit. Append a `## Progress checkpoint` to `agent-report.md` after each work order.
6. Repair policy: fix HIGH and MEDIUM (security, cost, correctness, destructive behavior); defer LOW with disclosure. At most one repair round.
7. Speed: this phase should take about 1.5 hours. If any step will exceed 30 minutes, STOP and say why.

## 1. Preconditions (orchestrator, read-only)

- The cwd is the lane 2 worktree on `lane2`; HEAD is or descends from `5edd031` (Phase 12 accepted); `git status --short` is clean except this phase folder.
- Read `docs/agent-runs/phase-12-terraform/human-gate.md` (findings G12-4, G12-5, G12-6 are routed into this phase; G12-1 to G12-3 are for the merge and out of scope here).
- Read `scripts/plan.sh` and `scripts/cleanup.sh` (Phase 12), `infra/terraform/INFRA-CONTRACTS.md` (outputs, services, cost rules, the Neptune/ALB toggle procedure), `docs/agents.md` §4–6 (human-only apply and destroy; deploy-engineer runs only approved scripts; the guard blocks scripts whose text contains `terraform apply` or `destroy`).
- Record the build commands the scripts must call: `sbt check` (canonical), the Phase 10 image build alias or command, and the Hadoop and Spark assembly tasks (read `build.sbt`, do not change it). Note: Phase 10/11 service code lives on main, not lane2; read the image build definition from `build.sbt` as it exists on lane2 and record any gap as a merge item, do not cross the boundary.

## 2. A — infra-engineer

All scripts: bash strict mode (`set -euo pipefail`), greppable step markers (`==> STEP name`), explicit exit codes, timeouts on cloud calls, retries only for safe idempotent calls, no secrets in output, `--dry-run` and `--help` on every script, `AWS_PROFILE` and region from the environment, and a fail-fast check of account and region against the Terraform outputs.

1. **`scripts/check.sh`:** validate agent files (`.claude/hooks/validate-agent-files.sh`), JSON configs, hook syntax, run the canonical `sbt check` (unless `--skip-sbt`), contract and schema validation present in the repo, `bash -n` on all scripts, `terraform fmt -check` and `validate` (with `-backend=false`). Exit nonzero on any failure.
2. **`scripts/package.sh`:** build the Hadoop and Spark assemblies and the three service images; tag images with the Git commit SHA (refuse a dirty tree unless `--allow-dirty` for local testing); record artifact checksums and image IDs to `reports/release/<sha>/artifacts.json`.
3. **`scripts/deploy.sh`** (run by deploy-engineer after the human has applied the plan): refuse a dirty tree; require `APPROVED_PLAN_SHA256` matching the recorded plan checksum; read Terraform outputs; push images to ECR by Git SHA tag; register task definitions and update the three ECS services to that tag (desired counts from arguments, default 1 for query-api and retrieval, 0 for the worker); wait for stabilization with a timeout; verify running task definitions and image digests match; write a deployment record to `reports/deployments/<sha>/`. **It never runs `terraform apply`**; it prints the exact human apply command if outputs are missing.
4. **`scripts/rollback.sh`:** roll the three services back to a named previous task-definition revision or image SHA, wait, verify, record. No data changes.
5. **`scripts/smoke-test.sh`:** check `/healthz` and `/readyz`; run one bounded researcher query through `POST /v1/query` with the services API key from Secrets Manager (read at run time, never printed); fetch the evidence and explain for one returned claim; verify an idempotent repeat and one expected error (401 without a key); write a JSON result.
6. **`scripts/run-bounded-pipeline.sh`:** given `--scale 10k|50k|1k`, upload the selected DBLP XML and enrichment cache (as a few large objects, per the Phase 12 cost rule) to the raw bucket; submit an EMR cluster from the Phase 12 configuration (emr-spark-8.1.0 by default, the documented emr-7.14.0 fallback for MapReduce steps behind a flag) with auto-termination; run the Hadoop jobs HJ1–HJ7, then the Spark pipeline, then the storage loader (Neptune bulk load, DynamoDB items for the tables the runtime reads), then the embedding enqueue; record run IDs, step timings, configuration and output locations to `reports/pipeline/<scale>/<run>/`. A `--smoke` mode runs a tiny MapReduce job first to confirm MapReduce works on the release. **Refuse 50K unless a 10K run record exists and `--approve-50k` is passed.** Every EMR step uses `ActionOnFailure=TERMINATE_CLUSTER` and a 1-hour step timeout.
7. **`scripts/run-experiment.sh`:** run an approved experiment manifest from `experiments/manifests/` (Phase 15b; the folder may not exist yet on lane2 — handle its absence with a clear error); record the commit SHA, resource limits, start and end times, region and metric locations.
8. **`scripts/archive-evidence.sh`:** gather test reports, plan checksum, image digests, run manifests, selected CloudWatch metric exports, experiment outputs and release metadata into `reports/evidence/<sha>/` (and optionally an S3 prefix).
9. **`scripts/env-window.sh up|down`:** the cost toggle. `up` prints the exact human `terraform` commands to set `create_neptune=true` and `create_alb=true` (it does not run apply); `down` prints the commands to turn them off and scales all three services to 0 directly through the ECS API. Also prints the current hourly cost estimate from INFRA-CONTRACTS.md.
10. **`scripts/cleanup.sh`** (extend the Phase 12 version): inventory before and after (writes both to `reports/cleanup/`), preserves the evidence buckets per policy, prints the human destroy commands; never destroys itself. Additionally (from the Phase 12 gate):
    - **G12-5:** empties the three ECR repositories (batch-delete images) before printing the human destroy command, because the repos have `force_delete = false`. Behind an explicit confirmation flag; dry-run lists what would be deleted.
    - **G12-4:** prints a note that the four secrets have a 7-day recovery window, and prints (never runs) the human-only `aws secretsmanager delete-secret --force-delete-without-recovery --secret-id <name>` commands needed if the stack will be re-applied within 7 days.

Update the INFRA-CONTRACTS.md script section with each script's purpose, inputs, environment variables by name, approval points and outputs. Include a **Phase 14 apply checklist** with: the human apply command for the approved plan; **G12-6:** confirm the SNS email subscription from the alert inbox; set the secret values by hand; then `deploy.sh`, then `smoke-test.sh`.

**Local verification (no AWS):** `bash -n` and `shellcheck` (if installed) on every script; each script's `--help` and `--dry-run` against stub `aws`, `terraform`, `docker` and `sbt` binaries on `PATH` that log their arguments; a test that `deploy.sh` refuses a dirty tree and a wrong plan checksum; a test that `run-bounded-pipeline.sh` refuses 50K without a 10K record and the approval flag; a test that EMR step submissions carry `TERMINATE_CLUSTER` and the timeout; a check that no script's text contains the guarded apply or destroy phrases. Report the exact command lines and the environment variable names each script needs.

## 3. D — code-reviewer

Review: destructive or high-cost actions without a human approval point (including the ECR emptying); secrets in output; apply or destroy anywhere; dirty-tree and checksum enforcement; cost toggles and the 50K approval rule; EMR failure termination and timeouts; idempotent retries; consistency with Terraform output names and the service contract; tests that would pass if broken. Verdict.

## 4. STOP points

STOP and ask the human if: a precondition fails; a lane boundary would be crossed; a script would need to apply or destroy; any AWS call is needed; or a second repair round would be needed. Otherwise write the final checkpoint "Phase 13 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- A clean checkout can run all non-cloud validation through `scripts/check.sh`.
- Deployment cannot proceed without the approved plan checksum and a clean tree; no script applies or destroys.
- The pipeline script refuses 50K without a 10K record and explicit approval; EMR auto-terminates and failed steps terminate the cluster.
- `env-window.sh down` scales services to 0 and prints the commands to remove Neptune and the ALB.
- `cleanup.sh` empties ECR behind confirmation and prints the secret force-delete commands.
- Smoke tests exercise a real bounded collaboration query (to be run in Phase 14).
- Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

Running anything against AWS (Phase 14–15); Terraform resource changes; application code; the model-provider wiring (G12-1/G12-2, merge item); experiment manifests and load generators (Phase 15b).