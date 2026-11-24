# Load generator (Phase 15b)

Python standard library only (threads). Sends `POST /v1/query` (route shape from `modules/services/API.md` on main) at one concurrency level and writes an
immutable raw CSV plus `.meta.json`. Never run against AWS from an agent session; the verification below used a local dummy server on 127.0.0.1 only.

```
python3 -B load-tests/loadgen.py --manifest experiments/manifests/exp-d-query-load.json --run-id RUN --concurrency 4 --max-requests 9 \
    --duration 300 --ramp 5 --benchmark /path/to/benchmark-v1.json [--base-url URL] [--expect-host HOST] [--allow-insecure-http] \
    [--mode fused|graph-only|vector-only] [--fetch-explain] [--out-dir DIR] [--ledger FILE] [--question-limit N] [--categories a,b] [--seed N] [--dry-run]
python3 -B load-tests/run_suite.py --manifest MANIFEST [--run-id RUN] [--benchmark FILE] [same connection flags] [--dry-run]    # all levels of the manifest
```

Environment (names only): `SC_RUN_ID` and `SC_LOADTEST_BENCHMARK` (used when `--run-id` / `--benchmark` are absent; needed because `scripts/run-experiment.sh` runs the manifest command verbatim and does not export its run id), `SC_RESULTS_ROOT` (raw results root; SYNTHETIC ledger follows it, the REAL ledger does not), `SC_LOADTEST_API_KEY_FILE` (fallback `SC_SMOKE_API_KEY_FILE`): mode 600 or 400, outside the repository, non-empty, never printed;
`SC_LOADTEST_BASE_URL`; `SC_LOADTEST_EXPECT_HOST` (host of the Terraform output `query_api_url`; a non-loopback host that differs is refused, so the key goes nowhere else).

Benchmark: `benchmark-v1.json` exists only on `main` (commit `932fd2f21ec0abcc5cbe3a31a07c1536be3b5242` when authored, 44 questions, SHA-256
`e511d17530823374bfeec5683d2ac25c6980d5a31f49b470e47d62f561a721e6`). It is NOT copied here: `git show main:modules/rag/src/main/resources/benchmarks/benchmark-v1.json > <file outside the repo>`
and pass that file; its SHA-256 is checked against the manifest. A benchmark file with `"synthetic": true` forces SYNTHETIC labeling and is accepted only for a loopback target; sent to a non-loopback host it is REFUSED
(`load-tests/testing/SYNTHETIC-benchmark.json`, 20 invented questions). For a non-loopback target the SHA-256 check is mandatory.

Request body: `{"question": <text>}` plus, for `graph-only`, `"retrieval":{"graphLimit":10,"vectorLimit":0}` and for `vector-only`, `"retrieval":{"graphLimit":0,"vectorLimit":30}`.
API.md refuses both with 422; those rows are recorded as failures. Headers: `X-API-Key` (rename with `--auth-header`), `X-Trace-Id: lt-<run>-<seq>`, JSON content type.
`--fetch-explain` adds `GET /v1/explain/{queryId}` after a 200 (no model call) to record observed `counters.modelCalls` and the per-stage latencies.

## Caps (hard)

From the manifest `loadgen` block; the command line can only lower them: `max_requests_per_run`, `max_model_calls_per_run`, `max_duration_seconds`, `max_concurrency`,
`model_cost_cap_usd`. Before each request the generator reserves 3 assumed model calls and about $0.021 and stops when any cap would be exceeded
(`stop_reason`: `request_cap`, `duration_cap`, `model_call_cap`, `model_cost_cap`, `error_rate_abort`). Error-rate guard: more than 50 percent failures in any 10 consecutive
finished requests stops the level (exit 4, rows kept). A cumulative ledger (`experiments/results/model-spend-ledger.jsonl`; `SYNTHETIC-` prefixed for loopback runs) refuses a level
that would take the total above `loadgen_ledger_limit_usd`. Assumptions and sources: `experiments/manifests/model-budget.json`.

## Non-loopback target rules (exit 3, before the key file is read and before any request)

For any base URL host other than 127.0.0.1, localhost, ::1: `--ledger` and `--budget-file` are refused (defaults only); the manifest must pass
`experiments/validate_manifests.py` and have `"approved": true` and a non-empty `"approved_by"`; the benchmark must not declare `"synthetic": true`; and its SHA-256
must equal `loadgen.benchmark.sha256`. `run_suite.py` applies the same rules once before level 1. Loopback runs keep the overrides (tests, dummy server) and are SYNTHETIC.

## Output (write-once, exclusive create)

`experiments/results/<experiment id>/<run id>/[SYNTHETIC-]loadgen-<run>-c<concurrency>-<mode>.csv` and `.meta.json`. A loopback base URL or a synthetic benchmark makes the run SYNTHETIC
(file prefix, `synthetic` column, meta `label`). CSV columns, in order:

`run_id, experiment_id, synthetic, concurrency, mode, seq, question_id, category, t_start_epoch_ms, t_start_utc, latency_ms, http_status, outcome, error_class, trace_id, query_id,
response_mode, abstained, claims_count, claims_verified, returned_researcher_ids, model_calls_assumed, model_calls_observed, explain_http_status, stage_latency_ms_json, bytes`

`outcome` is `ok` (200), `async` (202), `http_error`, `timeout` or `conn_error`; `returned_researcher_ids` is `;`-separated in answer order; `claims_verified` counts claims with
`verificationStatus` VERIFIED (added after the first local verification run `synth-local-1`/`synth-local-e1`, whose files lack it; they stay as written). Response bodies are never stored.

Exit codes: 0 level completed, 1 internal failure (partial rows kept), 2 usage, 3 refused (http without the flag, host, caps raised, ledger, existing output), 4 error-rate abort, 6 key file unusable.

## Local verification tools

`testing/dummy_server.py --secret-file FILE [--port-file FILE]` (127.0.0.1, SYNTHETIC; mirrors 401, 422, 429 and explain), `testing/make_synthetic_benchmark.py`.
