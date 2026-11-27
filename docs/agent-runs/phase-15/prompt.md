

<!-- ===== phase-15a-dataset-scale/prompt.md ===== -->

# Phase 15a — Dataset scale: ~10K and ~50K DBLP subsets with enrichment (work order)

Phase folder: `docs/agent-runs/phase-15a-dataset-scale/`
Lane: **lane 2** (git worktree `ScholarChain-lane2`, branch `lane2`), run in parallel with lane 1 (Phases 06–11).
Why: the instructor approval (2026-11-20, item 1) requires two real DBLP scales, about 10K and about 50K works, for the Phase 15 experiments. Only the 1K slice exists. This is dataset-engineer work under the Phase 03 manifest rules, run early because enrichment fetches are slow.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A | dataset-engineer | `datasets/**` | always |
| H | **HUMAN** (no agent) | gitignored `datasets/raw/**` via the documented command | after A |
| B | dataset-engineer | `datasets/**` | after H |
| C | test-engineer | `tests/datasets/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code, tests or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. **Lane 2 boundary:** no agent edits `build.sbt`, `project/**`, `modules/**`, `infra/**`, `scripts/**`, `CLAUDE.md`, `docs/agents.md` or `.claude/**`. If one of these seems necessary, STOP and ask the human.
4. No sbt run is needed in this phase. If the Stop hook asks for the canonical command because a file under a source path changed, run it once from the repo root: `sbt check; echo "exit=$?" > .claude/.last-test-run`.
5. Network: bounded, read-only requests to the public APIs already in the manifest (Crossref, OpenAlex, ORCID) are allowed. No AWS. No LLM calls. **The DBLP XML is never re-downloaded**; use the local copy.
6. Never print, log or commit an API key, token or email secret. A contact email for polite-pool headers comes only from an environment variable, and is never echoed.
7. Do not stage or commit. The human commits the phase at the end.
8. Append a `## Progress checkpoint` to `agent-report.md` after each work order.

## 1. Preconditions (orchestrator, read-only)

Record each item under `## Preconditions` in `agent-report.md`:

- The current directory is the lane 2 worktree on branch `lane2`, and `git status --short` is clean.
- `datasets/raw` resolves to the main repository's raw folder (a symlink the human created). The full `dblp.xml`, `dblp.dtd` and the existing Crossref/OpenAlex/ORCID caches are visible through it. If not, STOP.
- List the existing assets with sizes and versions: `datasets/dataset-manifest.yaml`, the selection files (1K, 10K and 100K selections from Phase 03, if present), `dblp_extract_subset.py` (v1.1.0), the fetch and cache scripts, `validate_manifest.py` and `check_no_secrets.py`.
- Record whether OpenAlex, Crossref and ORCID access currently requires a key or has a usage budget, as stated in the manifest and the scripts. If a paid key would be required, STOP and ask.

## 2. A — dataset-engineer (selections, extraction, fetch plan)

1. **Nested, deterministic selections.**
   - `dblp-10k`: reuse the Phase 03 10K selection if it exists and is valid.
   - `dblp-50k`: must contain all of `dblp-10k` (nested, so the scale comparison is clean). Fill the rest from the Phase 03 100K selection with a fixed, documented seed. If the 100K selection is missing or does not contain the 10K one, define a new nested rule over the full DBLP index, documented and seeded.
   - Report the exact counts, the rule, the seed, and the record-type mix of each selection.
2. **XML extraction.** Run `dblp_extract_subset.py` (bump its version only if it needs a change) as a single read-only streaming pass over the local `dblp.xml`. Write `dblp_selection_10000.xml` and `dblp_selection_50000.xml` under `datasets/raw/dblp/derived/` (gitignored). Validate well-formedness, record counts against the selection, and the DTD reference. Record sha256 and size for each.
3. **Enrichment fetch plan.** Using the existing fetch scripts (extend them if needed; keep them idempotent and bounded):
   - **DOI-driven:** Crossref and OpenAlex for every selected work with a DOI. Use batched lookups wherever the official API supports them (for example OpenAlex `filter=doi:a|b|…`), and say how many DOIs fit in one request.
   - **ORCID:** only ORCIDs that appear in the fetched Crossref or OpenAlex records for these works. Cap the count by config.
   - **Required behavior:** cache-first (skip anything already cached, including the 1K cache), resumable after interruption, a polite rate limit with backoff on 429 and 5xx, a hard request cap per source, the polite-pool contact from env only, and a progress log with counts every N requests. Defaults stay bounded: nothing large runs without explicit flags.
   - **Dry-run first:** a `--plan` mode that prints DOI counts, cached vs. to-fetch per source, the number of requests after batching, the expected duration at the configured rate, and the disk estimate.
   - **Smoke test:** a real fetch for at most 200 uncached DOIs, to prove the path works end to end.
4. Write the exact human command for the full fetch into `datasets/scripts/FETCH-SCALE.md`. It must be resumable, run under `caffeinate -i` on macOS, write its log to a file, and come with a one-line check command for progress.

Report: the selection counts, the extraction checksums, the `--plan` output for both scales, the smoke-test result, and the exact command. Then **STOP for the human (H)**, and say so in the final checkpoint.

## 3. H — HUMAN (full fetch; no agent)

The human runs the command from `FETCH-SCALE.md` in their own terminal and lets it finish (it can be stopped and resumed). The human pastes the tool's final summary (requests made per source, cache hits, failures, duration) into `agent-report.md` under "H (human)", then tells the orchestrator to continue with B.

## 4. B — dataset-engineer (validate and record)

1. Validate the caches for both scales: coverage per source (works with a DOI; Crossref hit rate, OpenAlex hit rate, ORCID records), failure counts and reasons, and schema validity of the cached records.
2. Update `datasets/dataset-manifest.yaml` with entries `dblp-10k` and `dblp-50k`: purpose (Phase 15 scales, instructor approval item 1), selection rule and seed, nesting, record counts, file names, sha256, sizes, enrichment coverage per source, retrieval dates, staleness, limitations (bounded DOI-driven enrichment is a design choice under the spec's bounded-retrieval rule, **not** an approved deviation), privacy notes for ORCID, and validation commands.
3. Commit-safe outputs only: small stats files (counts, checksums, coverage) may go under `datasets/`; nothing under `datasets/raw/` is committed.
4. Run `validate_manifest.py` and `check_no_secrets.py` and quote the results.

## 5. C — test-engineer (at least 10 new tests, in `tests/datasets/**`)

- The selections are deterministic for a seed, and 10K ⊂ 50K.
- The extractor gives the same record count as its selection on a small fixture.
- Batch splitting respects the documented batch size.
- Cache-first: a cached DOI makes no request (use a fake transport).
- Resume: an interrupted run continues without re-fetching.
- Backoff on 429 and 5xx; the hard request cap is enforced.
- `--plan` makes no network call.
- No secret or contact email appears in any log line.
- The manifest validator passes for the new entries and rejects a missing checksum.

Run the Python test suite and report exact counts.

## 6. D — code-reviewer (read-only)

Review: unbounded downloads or missing caps; secrets handling; determinism and nesting of the selections; manifest accuracy (every number traceable to an observed output); the license and privacy notes; tests that would pass if the behavior were broken; claims not backed by output. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 7. Repair and STOP points

- On REQUEST_CHANGES: one repair round, by the owners, then D rereads.
- STOP and ask the human if: a precondition fails; a paid key would be needed; the plan shows more than 12 hours of fetching at the polite rate; a lane 2 boundary would be crossed; or a second repair round would be needed.
- Otherwise write the final checkpoint "Phase 15a complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- Two real, nested subsets (~10K ⊂ ~50K) with checksums, counts and a documented seed.
- Enrichment coverage per source reported from observed counts, failures included.
- The manifest entries validate; no secrets anywhere.
- Fetching was bounded, cache-first and resumable; the full fetch was run by the human.
- Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

Running Hadoop, ER or Spark on the new subsets (that is Phase 15, on EMR); any code under `modules/**`; AWS; Terraform.


<!-- ===== phase-15b-experiment-harness/prompt.md ===== -->

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

<!-- ===== phase-15c-aws-experiments/prompt.md ===== -->

# Phase 15c — AWS data and elasticity experiments (final work order)

Phase folder: `docs/agent-runs/phase-15c-aws-experiments/` (this file is its `prompt.md`).
Lane: main (`~/Desktop/ScholarChain`), fresh orchestrator session, same AWS window as Phase 14.
Spec: `COURSE-SPEC.local.md` (read-only), "Phase 15" and "Required scaling rules".
Phases 15a, 15b and 15c are combined into one Phase 15 at the end (17b).
**Time box: 75 minutes.** At the box, keep partial raw data, mark the rest `NOT EXECUTED (time)`, write the summary, commit.

## 0. State at start (verified facts, do not re-derive)

- HEAD clean on main, including Phase 14 (`d494931`, commit.txt `b648dc4`) and the merged lane2 (15b harness, 16a, 17a).
- Live release `38f2160a6e95` on query-api, retrieval, embedding-worker and the ops task. Embedding worker autoscaling min 1 / max 4 (target tracking on SQS backlog). query-api and retrieval autoscale on CPU / requests (Phase 12 policies).
- 10K graph loaded by run `r20261125T214348Z-63958-s4`: Neptune bulk load complete, DynamoDB 687,359 items, 9,968 of 10,000 vectors (2 jobs in the DLQ, F14-16). Hadoop HJ1–HJ7 ran on EMR (run `r20261125T214348Z-63958`); **Spark ran locally on Dhruv's Mac**, so no Spark-elasticity claim may be made.
- Runtime model Haiku 4.5 on Bedrock (`us.` inference profile); embeddings Titan Embed Text v2. Query API through the ALB on plain HTTP with the smoke key file `~/.scholarchain/services-api-key` (15b rules).

## 1. Delegated authority and limits (Dhruv's decision; record in agent-report.md)

- You route; owners run approved scripts and manifests only (`scripts/run-experiment.sh`, the 15b collectors and analysis scripts, read-only AWS calls). No production-code, Terraform, script or rule-file edits. **No `deploy.sh`, no `terraform apply/destroy`, no `cleanup.sh`, no `env-window.sh down`.** If a step truly needs `deploy.sh`, message Dhruv that one line and continue with other experiments meanwhile.
- You may commit this phase yourself after the diff-only review: `git diff --cached | grep -c <account id>` must be 0 (use a local grep, never write the ID anywhere); never stage `reports/`. If a script needs a clean tree before you start, first commit this `prompt.md` alone as `phase-15c: work order`.
- Cost: read month-to-date cost before credits at the start and after each experiment; **STOP if it exceeds $15.** Actual spend is ≈ $9 (Cost Explorer lags). Bedrock: at most 40 query calls in this phase. **No new EMR clusters.**
- Start `caffeinate -dimsu &` first. Honesty floor: raw outputs write-once, failed or partial runs kept, nothing tuned mid-run, every number cites its raw file.
- `## Progress checkpoint` in agent-report.md after each experiment. One repair round at most (analysis only).

| Work order | Agent | Writes only to |
|---|---|---|
| X | experiment-engineer | `experiments/**`, `reports/experiments/**`, `reports/evidence/**` |
| I | incident-responder | `docs/incidents/**` |
| D | code-reviewer | nothing |

## 2. X — experiments, in this order

1. **A, Hadoop baseline (collect only, ≈ 10 min):** from the `…63958` run records and EMR/CloudWatch for that cluster window: HJ1–HJ7 step durations, Hadoop counters, task counts, failures, instance hours, cost estimate. Also list every earlier 10K attempt (`…79065`, `…43602`, the C7 attempt) as failed runs with their cause (F14-5/6/7).
2. **B, EMR capacity timeline (collect only, ≈ 5 min):** requested / provisioned / released capacity over time for the `…63958` cluster. Disclose: no separate burst or 50K run; Spark was not on EMR for this run.
3. **C, embedding backlog (≈ 15 min):** first use the CloudWatch window of the Phase 14 s4 drain (SQS visible/in-flight messages, worker desired/running tasks, scaling activities, throughput, retries, DLQ count, drain time). Only if that window shows no scale-out above 1 task, enqueue one fresh capped batch per the 15b manifest (≤ 2,000 chunks, fresh text so the hash skip does not apply) and observe up to 15 min.
4. **D, query load (≈ 20 min):** two concurrency levels per the manifest (e.g. 5 then 20 clients, 4 min each), within the Bedrock cap, then up to 8 min idle observation. Record p50/p95/p99, throughput, error rate, ECS desired/running for query-api and retrieval, CPU, Neptune latency, DynamoDB throttles, vector-query latency, LLM latency, synchronized timestamps. Must show ≥1 scale-out and ≥1 scale-in; if scale-in does not happen in the window, record that and the policy cooldown honestly.
5. **F, failure and recovery (≈ 5 min):** stop one running retrieval task (ECS replaces it) OR send one poison message to the embedding queue (retries → DLQ). Record timestamps and recovery. Also reference the real Phase 14 incident (F14-4, the C4 image-permission rollback).
6. **E, retrieval modes:** NOT here; it runs in Phase 16b group 3.
7. Run the analysis scripts on the raw data: latency percentiles per level, task counts over time, queue depth and drain, EMR timeline, cost per experiment. Write `experiments/results/aws/SUMMARY.md` with a limitations section (no 50K run, Spark local, short durations, small corpus, single runs).

## 3. I — incident-responder

1. `docs/incidents/` report for experiment F with every spec field: incident ID, start and end time, detection source, user-visible impact, trace and run IDs, timeline, root cause, recovery action, data-integrity assessment, follow-up owner, preventive test or control.
2. `docs/incidents/INC-001-c4-image-file-modes.md` for the real Phase 14 incident F14-4, from committed Phase 14 evidence only (CloudWatch error window 15:58–16:08 UTC on 2026-11-25, automatic ECS circuit-breaker rollback, no user impact, root cause 0600 jars vs uid 10001, fix C5). Unknowns are `[[UNKNOWN]]`.

## 4. D — code-reviewer

Diff-only pass: every number traces to raw files; scale-out and scale-in visible with timestamps; failed runs kept; limitations honest; incident reports complete; no account ID or secrets. Verdict.

## 5. Finish

1. Commit: `phase-15c experiment-engineer, incident-responder: AWS experiments and incidents`.
2. Then STOP with:
   - "Phase 15c committed: <sha>"
   - results table (A, B, C, D, F: one line each, with raw paths)
   - cost reading, anything NOT EXECUTED
   - a **draft `human-gate.md`** in your message (Decision line left for Dhruv, authority recorded, gate lines from the spec's Phase 15 gate: scale-out and scale-in shown, synchronized timestamps, raw data preserved, costs and limits stated, failures reported). Do not write `human-gate.md` yourself.

STOP early on cost over $15, a failed AWS step that one retry does not fix, or a boundary.
