# Phase 15b — Experiment harness (work order)

Phase folder: `docs/agent-runs/phase-15b-experiment-harness/`
Lane: **lane 2**, after Phase 13 is committed. Runs unattended (overnight is fine).
Runtime model for every experiment: Claude Haiku 4.5 on **Bedrock** (inference profile `us.anthropic.claude-haiku-4-5-20251001-v1:0`); embeddings: Bedrock Titan Embed Text v2. No Anthropic API and no Voyage calls are budgeted.
Spec sections: "Phase 15: run the data pipeline and elasticity experiments", "Required scaling rules", "Phase 16" evaluation groups 5–7, "Observability requirements".
Purpose: everything Phase 15 (the AWS runs) and Phase 16 need, built and tested locally so the AWS window is short and cheap. **No AWS calls, no sbt.**

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A | experiment-engineer | `experiments/**`, `load-tests/**` | always |
| C | test-engineer | `tests/experiments/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. **Boundary:** no agent edits anything outside the paths above (no `modules/**`, `scripts/**`, `infra/**`, `build.sbt`, `.claude/**`, `CLAUDE.md`, `docs/agents.md`). If one seems necessary, STOP and ask.
4. **No AWS calls, no sbt, no network** (a local dummy HTTP server on localhost is allowed for tests). Python 3 standard library preferred; matplotlib only if already installed (record it), otherwise plots are produced as CSV plus a documented plotting step.
5. Do not stage or commit. Append a `## Progress checkpoint` after each work order. Unattended mode: continue without asking except at the STOP points.
6. Repair policy: fix HIGH and MEDIUM; defer LOW. At most one repair round.

## 1. Preconditions (orchestrator, read-only)

- The cwd is the lane 2 worktree on `lane2`; HEAD is or descends from the Phase 13 accepted commit given in the launch message (its `commit.txt` is added with this phase's commit); tree clean except this phase folder.
- Files that exist only on `main` (Phase 09–11: `modules/services/API.md`, `modules/rag/src/main/resources/benchmarks/benchmark-v1.json`, the service routes) are read **read-only** with `git show main:<path>`; never check out or modify main. Note: main may have uncommitted Phase 11 work in its own worktree; read only committed content via `git show main:`.
- Read `scripts/run-experiment.sh`, `scripts/run-bounded-pipeline.sh`, `scripts/env-window.sh`, `scripts/smoke-test.sh` (Phase 13), `docs/agent-runs/phase-13-automation/human-gate.md` (smoke key file rule, plain-HTTP rule), the Terraform outputs and dashboard definition (Phase 12), the service contract and API routes (`infra/terraform/INFRA-CONTRACTS.md`; `modules/services/API.md`), the benchmark file, and the instructor approvals (10K and 50K scales; up to 10K vectors; $25 budget).

## 2. A — experiment-engineer

1. **Manifests** (`experiments/manifests/*.yaml`), one per experiment, each with: ID, purpose, commit SHA placeholder filled at run time, dataset snapshot, scale, resource limits (EMR max nodes, ECS max tasks), duration cap, cost cap, metrics to collect, and stop conditions.
   - **A** Hadoop and Spark baseline at 10K (minimum EMR capacity): input and output records, task counts, duration, shuffle, failures, estimated cost.
   - **B** Burst: the 50K run (only after A, with explicit approval), observing EMR managed scaling within the cap: requested, provisioned and released capacity over time. This is also the 10K-vs-50K scale comparison the instructor approved (time, utilization, cost).
   - **C** Embedding backlog: enqueue a controlled number of chunks (target the ≤10K-vector approval); observe SQS depth, worker task count, throughput, retries, drain time.
   - **D** Query load: at least four concurrency levels (for example 1, 5, 10, 20, chosen to fit the budget and the service caps); p50, p95 and p99 latency, throughput, error rate, ECS task count, CPU, memory, Neptune latency, DynamoDB throttles, vector-query latency, LLM latency.
   - **E** Retrieval modes: graph-only, vector-only and fused, on the benchmark questions.
   - **F** Failure and recovery: one safe failure (stop one worker task, or send one poison message); show retry, dead-letter, idempotency and recovery.
2. **Load generator** (`load-tests/`): Python stdlib, async or threaded, reads the benchmark questions, sends `POST /v1/query` with the API key read from a file path given in an environment variable (same rule as the Phase 13 smoke key: mode 600, outside the repo; the key is never printed), supports a concurrency level, duration, ramp and request cap, records per-request latency, status and trace ID to an immutable raw CSV. A hard cap on total requests and model calls per run, from the manifest. Default caps keep the total Bedrock model spend for all experiments under $2 (record the per-request token assumptions used for the estimate). Plain HTTP requires an explicit `--allow-insecure-http` flag.
3. **Collectors** (`experiments/collect/`): scripts that pull CloudWatch metrics (ECS, SQS, Neptune, DynamoDB, ALB, custom API metrics), EMR step and cluster timelines, and cost (Cost Explorer daily cost by service, before credits) for a time window, using the AWS CLI; written now, run in Phase 15. Raw outputs are immutable once written (write-once file names with run IDs; refuse to overwrite).
4. **Analysis** (`experiments/analysis/`): reproducible scripts that turn raw files into tables and plots: latency percentiles per concurrency level, task counts over time with synchronized timestamps (scale-out and scale-in visible), queue depth and drain, EMR capacity over time, 10K vs 50K comparison, retrieval-mode comparison (recall@K, MRR, nDCG where labels allow), cost per experiment. Failed runs are kept and reported, never dropped.
5. **Evaluation scaffolding for Phase 16** (`experiments/evaluation/`): templates and scripts for the seven evaluation groups (entity resolution: read the committed Phase 05 results; graph correctness; retrieval; answer quality, including claim support rate and an empty human-rating sheet; performance and elasticity; agentic factory metrics computed from `docs/agent-runs/**`, such as repair iterations per phase, BLOCKED-ON events and reviewer findings by severity; reproducibility). Nothing is filled with invented numbers.
6. `experiments/README.md`: how to run each experiment with the Phase 13 scripts, in order, with the approval points and the cost and time cap of each.

**Local verification:** run the load generator against a local dummy HTTP server at two concurrency levels and show raw output; run every analysis script on synthetic fixture data clearly labeled `SYNTHETIC` (never presented as results); run collectors in `--dry-run` with a stub `aws` binary.

## 3. C — test-engineer (at least 15 tests, `tests/experiments/**`)

Load generator honors the concurrency, duration and request caps; the key never appears in output or raw files; raw files are write-once; analysis percentiles on a hand-computed fixture; scale-event detection on a synthetic timeline; retrieval metrics (recall@K, MRR, nDCG) on hand-computed examples; manifests validate against a schema and require cost and duration caps; synthetic data is always labeled. Run the Python tests; report exact counts.

## 4. D — code-reviewer

Review: caps on requests, model calls, duration and cost; secret handling; immutability of raw data; synthetic data never presented as results; correctness of percentile and retrieval metrics; manifests covering experiments A–F and the instructor's two scales; tests that would pass if broken. Verdict.

## 5. STOP points

STOP if: a precondition fails; a boundary would be crossed; any AWS or network call is needed; or a second repair round would be needed. Otherwise write the final checkpoint "Phase 15b complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- Manifests for A–F, each with cost and duration caps; 50K requires the 10K run and approval.
- The load generator, collectors and analysis run locally on synthetic data, clearly labeled.
- Raw outputs are write-once; failed runs are kept.
- Evaluation scaffolding for Phase 16 exists with no invented numbers.
- Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

Any AWS run (Phase 15c); application, Terraform and script changes; filling evaluation results.