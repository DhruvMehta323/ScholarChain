# ScholarChain experiments (Phase 15b harness, for the Phase 15c AWS window)

Owner: experiment-engineer. Everything here was built and tested locally: **no AWS call, no sbt, no network** was made while writing it.
Nothing in this folder is a result of an AWS run. Files named `SYNTHETIC-*` hold invented or dummy-server data and must never be quoted as results.

Run Python as `python3 -B ...` (no bytecode caches). Standard library only; matplotlib is optional (plots fall back to CSV when it cannot be imported;
3.10.8 was installed where this was written).

## Layout

| Path | What |
|---|---|
| `manifests/exp-{a..f}-*.json` | one manifest per experiment (JSON, not YAML: `scripts/run-experiment.sh` reads `<id>.json` with `jq`) |
| `manifests/manifest.schema.json`, `validate_manifests.py` | executable schema and cross-field rules |
| `manifests/model-budget.json` | model-spend cap ($2 total), price and token assumptions (UNVERIFIED, see below) |
| `../load-tests/` | query load generator, suite runner, local dummy server |
| `collect/` | AWS CLI collectors (read-only), live sampler, failure drill, stub `aws` for offline tests |
| `analysis/` | scripts that turn raw files into tables and plots; `fixtures/` holds SYNTHETIC inputs |
| `evaluation/` | Phase 16 scaffolding (seven groups), templates and scripts, no invented numbers |
| `results/` | raw results (write-once), or `$SC_RESULTS_ROOT` when that variable is set. `SYNTHETIC-*` files here come from local verification only |

Validate the manifests any time: `python3 -B experiments/validate_manifests.py` (exit 0 expected).

## Rules that hold for every run

1. Raw files are write-once. Names carry the run id; every writer uses exclusive create and refuses to overwrite (exit 3). A correction is a NEW file
   that references the original. Failed and aborted runs stay on disk and are reported.
2. A manifest must be `"approved": true` with a non-empty `"approved_by"` (a human edit) before `scripts/run-experiment.sh` runs it. Committed manifests are unapproved.
3. Cloud runs only in the approved environment (`course`, Region `us-east-2`), inside the manifest caps. Stop when a stop condition in the manifest is met.
4. The API key is read from a mode-600 file outside the repository named by `SC_LOADTEST_API_KEY_FILE` (or `SC_SMOKE_API_KEY_FILE`), never printed, never written.
   The ALB is HTTP: `--allow-insecure-http` is a knowing human decision (Phase 13 human-gate decision 2; ingress limited to the human's /32; rotate the key afterwards).
5. Model spend for all experiments stays under $2: see "Cost and assumptions".

## Order of the Phase 15c window, with approval points

Preconditions (Phase 14): deployed stack, tree clean, release packaged (`scripts/package.sh`), `AWS_PROFILE` and `AWS_REGION=us-east-2` set.
Each step below names the Phase 13 script, the manifest cap, and where a human must decide.

| # | Experiment | Phase 13 script or tool | Duration cap | Cost cap | Approval point |
|---|---|---|---|---|---|
| 1 | **A** baseline 10K | `bash scripts/run-experiment.sh exp-a-baseline-10k --run-id <token>` (runs `scripts/run-bounded-pipeline.sh --scale 10k --stages hadoop,spark`; the pipeline generates its own run id) | 3 h | $3.00 | human sets `approved` and `approved_by` in the manifest AND appends `--dblp-xml <file> --dtd <file> --enrichment-dir <dir>` to its `command` (the script exits 2 without them). Expected about $1.33 (INFRA-CONTRACTS section 10, estimate) |
| 2 | collect A | `python3 -B experiments/collect/collect_emr.py ...`, `collect_cloudwatch.py --groups emr`, then `collect_cost.py` the day after | | | none (read-only). **Then commit the raw results (see "Between experiments")** |
| 3 | **B** burst 50K | `bash scripts/run-experiment.sh exp-b-burst-50k --run-id <token>` | 4 h | $4.00 | **explicit human approval recorded in a human-gate file**, after A succeeded and its cost was measured; the human appends `--approve-50k` (and the three input arguments) to the manifest `command`. The pipeline needs the A record (`reports/pipeline/10k/*/run.json`, status succeeded). Expected about $2.47 (estimate) |
| 4 | **C** embedding backlog | `bash scripts/run-experiment.sh exp-c-embedding-backlog --run-id <token>` (`--stages enqueue`) plus `collect/sample_resources.py` running for the window | 1.5 h | $1.00 (model $0.20) | **blocked until M13-2**: no EMR entry point for the enqueue exists. Human raises `ecs_min_tasks`/`ecs_max_tasks` (max 4) for embedding-worker through a reviewed plan |
| 5 | **D** query load | `bash scripts/env-window.sh up` (printed human steps), then the exact command below, plus `sample_resources.py` | 1 h | $1.80 (model $0.90) | human approves the manifest AND appends `--allow-insecure-http` to its `command` (Phase 13 decision 2) |
| 6 | **E** retrieval modes | same as D with `exp-e-retrieval-modes` | 1 h | $1.20 (model $0.60) | as D. Known gap: graph-only and vector-only are refused with 422 by the service as deployed (see the manifest note) |
| 7 | **F** failure and recovery | `SC_RUN_ID=<token> bash scripts/run-experiment.sh exp-f-failure-recovery --run-id <token>` prints the plan (the manifest command ends in `--dry-run`); `collect/sample_resources.py` runs for the window | 45 min | $0.80 (model $0.15) | the manifest must be approved, and the human edits `command`: remove `--dry-run`, append `--queue-url <main queue URL> --execute --yes` (or switch to `--action stop-task --cluster-name C`). The drill performs exactly ONE failure per run (`--action poison-message` or `stop-task`, mutually exclusive), after exclusively creating its write-once record |
| 8 | wind down | `bash scripts/env-window.sh down --yes` (ECS to 0), then the printed human steps for Neptune and the ALB | | | human |

Commands for D and E (the manifest `command` is `python3 -B load-tests/run_suite.py --manifest experiments/manifests/<id>.json`, run verbatim by
`scripts/run-experiment.sh`, which passes no extra arguments and does not export its run id, so the values come from environment variables):

```
export SC_RUN_ID=<token>                                   # the same token as --run-id below
export SC_LOADTEST_BENCHMARK=/path/outside/repo/benchmark-v1.json   # git show main:modules/rag/src/main/resources/benchmarks/benchmark-v1.json > FILE
export SC_LOADTEST_API_KEY_FILE=/path/outside/repo/key-file         # mode 600; never printed or written
export SC_LOADTEST_BASE_URL=<Terraform output query_api_url>  SC_LOADTEST_EXPECT_HOST=<its host>
bash scripts/run-experiment.sh exp-d-query-load --run-id "$SC_RUN_ID"       # after approval and after appending --allow-insecure-http to the manifest command
```
Non-loopback targets are refused unless the manifest validates and is approved, no `--ledger`/`--budget-file` override is given, and the benchmark
SHA-256 equals the manifest value (a `"synthetic": true` benchmark is refused). Experiment F and the pipeline manifests (A, B, C) follow the same
pattern: `SC_RUN_ID` replaces a hardcoded `--run-id` (F), and the pipeline script generates its own run id (A, B, C).

### Between experiments (human step, every time)

`scripts/run-experiment.sh` and `scripts/run-bounded-pipeline.sh` refuse a dirty working tree, and `scripts/lib/common.sh` excludes only `reports/*`
(not `experiments/results/`) from that check. Raw results are immutable evidence, so they are COMMITTED, not deleted. After each experiment and its
collectors, and before the next `run-experiment.sh` call, the human does:

```
git status --short experiments/results        # review: only the new run's files (and the ledger) appear
git add experiments/results/<experiment id>/<run id> experiments/results/model-spend-ledger.jsonl   # ledger only after D or E
git commit -m "Raw results: <experiment id> <run id>"
```
At each step of the run order above: after step 2 (A and its collectors), after step 3 (B), after step 4 (C), after step 5 (D), after step 6 (E),
after step 7 (F, including `failure_drill-<run>-record.jsonl`). Derived tables from `analysis/` are not needed for the next run; commit them
separately whenever convenient. Alternative: `export SC_RESULTS_ROOT=/path/outside/the/repository` before running the load generator and the collectors
(the REAL model-spend ledger stays at `experiments/results/model-spend-ledger.jsonl` regardless, because moving it would reset the cumulative cap, so
commit that file after D and E). With `SC_RESULTS_ROOT` set, derived outputs default to `<SC_RESULTS_ROOT>-derived/<analysis|evaluation>`.
Two items are for other owners and are NOT done here: adding `experiments/results` to `SC_GENERATED_EXCLUDES` in `scripts/lib/common.sh`
(infra-engineer) and an owner for `reports/experiments/**` (factory-engineer), both routed by the orchestrator.

`scripts/run-experiment.sh` writes `reports/experiments/<id>/<run>/run.json` and `command.log`; the raw collector and load-generator files go to
`<results root>/<experiment id>/<run id>/` (`experiments/results/` unless `SC_RESULTS_ROOT` is set).

## Collectors (read-only AWS CLI; `--dry-run` prints the commands)

```
python3 -B experiments/collect/collect_cloudwatch.py --experiment-id ID --run-id RUN --start ISO --end ISO --region us-east-2 --groups ecs,sqs,alb,neptune,dynamodb,emr ...
python3 -B experiments/collect/collect_emr.py         --experiment-id ID --run-id RUN --cluster-id j-XXXX --region us-east-2
python3 -B experiments/collect/collect_cost.py        --experiment-id ID --run-id RUN --start YYYY-MM-DD --end YYYY-MM-DD   (Cost Explorer, credits excluded)
python3 -B experiments/collect/collect_logs.py        --experiment-id ID --run-id RUN --log-group /scholarchain/query-api --start ISO --end ISO
python3 -B experiments/collect/sample_resources.py    --experiment-id ID --run-id RUN --region us-east-2 --interval 15 --duration 3600 ...   (run during the window)
```
Why a live sampler: ECS running task counts and EMR requested versus running counts are not CloudWatch metrics here (Container Insights is off by
default, INFRA-CONTRACTS section 7), so they are sampled. The first ten minutes of the window are the best place to confirm the sampler works.

Offline test of any collector: put `experiments/collect/testing/stub-bin` first on `PATH` and add `--dry-run`. With the stub the command is executed
against SYNTHETIC canned output; with a real `aws` on PATH, `--dry-run` only prints and executes nothing.

## Analysis (turns raw files into tables and plots)

```
latency_percentiles.py  RAW.csv ...                  p50/p95/p99, throughput, error rate per concurrency level
scale_timeline.py       SAMPLES.jsonl [--loadgen ..] ECS task counts over time and scale-out/in events
queue_drain.py          SAMPLES.jsonl [--cloudwatch]  SQS backlog, peak, drain time, DLQ
emr_capacity.py         SAMPLES.jsonl                 requested / provisioned / released nodes, node-hours
scale_comparison.py     --a-run .. --b-run ..         10K vs 50K: time, nodes, cost
retrieval_metrics.py    RAW.csv ... --benchmark FILE  recall@K, MRR, nDCG by mode and category
cost_report.py          [--cost EXP=FILE ...]         cost per experiment against the caps
```
All write to `--out-dir` (default `analysis/output/`, or `<SC_RESULTS_ROOT>-derived/analysis` when that variable is set), refuse to overwrite derived files
unless `--force`, add a `synthetic` column, and prefix SYNTHETIC names when any input is synthetic. Every table, plot and evaluation output gets a sidecar
`<output file name>.provenance.json` (for example `latency-by-concurrency.csv.provenance.json`) with: `script`, `argv`, `parameters` (all parsed options such as
`--k`, `--node-hour-usd`), `inputs` (path and SHA-256 each), `benchmark_sha256` where a benchmark is used, `git_head`, `tree_dirty`, `generated_at_utc`,
`synthetic`, `output_sha256`. The sidecar follows the table's write-once and `--force` rule. Definitions (nearest-rank percentiles, event rule, drain rule) are in each script's docstring and in `analysis/metrics.py`.

## Cost and assumptions (all estimates, none measured)

* Haiku 4.5 on Bedrock, `us.anthropic.claude-haiku-4-5-20251001-v1:0`: **$1.00 per million input tokens and $5.00 per million output tokens: UNVERIFIED** (author's
  recollection, no network). `INFRA-CONTRACTS.md` prices a different model (Haiku 5.5) at $0.11 / $0.55; the higher figures are used so the caps are conservative.
  Check the Bedrock price page before the window and edit `manifests/model-budget.json` (a human-approved change) if they differ.
* Titan Embed Text v2: $0.00002 per 1K input tokens (INFRA-CONTRACTS, recorded there as verified 2026-11-23; not re-verified here).
* Per query request: 3 model calls (the frozen explain example shows 2), 4000 input and 600 output tokens each, one 500-token query embedding: about $0.021 per request.
* Allocation: D $0.90 (42 requests), E $0.60 (27 requests), F $0.15, C $0.20 (10,000 chunks x 300 tokens, about $0.06): total $1.85, under $2. The load generator also keeps a
  cumulative ledger (`results/model-spend-ledger.jsonl`, limit $1.80 for D and E together with the reserve) and refuses to start a level that would exceed it.
* Request counts are small because of the budget. Percentiles at n = 5 to 16 requests per level are weak (p99 equals the maximum). With these caps the load is unlikely
  to trigger ECS autoscaling (CPU 60 percent or 200 requests per target); raising the caps is a human cost decision, not something the harness does.
