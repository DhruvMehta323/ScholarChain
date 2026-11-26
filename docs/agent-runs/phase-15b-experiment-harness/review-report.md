# Phase 15b — code-reviewer report

Stored by the orchestrator from the code-reviewer's returned message. The reviewer is read-only (Read, Grep, Glob; no shell).

## Review 1 (Work Order D, before repair)

**Verdict: REQUEST CHANGES.** HIGH 0, MEDIUM 6, LOW 12.

Scope: untracked `experiments/**`, `load-tests/**`, `tests/experiments/**`. Not re-run by the reviewer (no shell): the orchestrator's "59 passed in 13.39s" and the validator's "RESULT PASSED" (UNVERIFIED by the reviewer; by reading, `tests/experiments/` defines 59 test items, so the count matches). The test-engineer's mutation check ("14 failed, 45 passed") is UNVERIFIED. `sbt check` NOT RUN (rule 4).

### Checks that pass (reviewer read the code)

- Ownership and boundary: every new path is under `experiments/**`, `load-tests/**` (experiment-engineer) or `tests/experiments/**` (test-engineer). No `__pycache__` under these paths.
- Secrets (`load-tests/loadgen.py:95-117`): key from an env-named file, outside the repo (realpath), mode 600/400; errors never include the path or key; key only in the auth header (246, 294); meta.json records only the variable name (480); exceptions logged by class name only (254-255); HTTPS default verifying context (231); non-loopback host must equal `--expect-host` (412); plain HTTP needs the flag (410); tests scan stdout, stderr, outputs and ledger for the key.
- Caps: `lower()` (385-390) refuses CLI values above the manifest for requests, duration, concurrency; model calls and cost reserved before each request (206-225); refusal up front if requests × cost exceeds the cap (401); global ledger limit 1.80 (443-445).
- Budget arithmetic: 3 × (4000 × $1/M + 600 × $5/M) + 500 × $0.00002/1K = $0.02101 per request; model caps sum 1.85 < 2; cost caps sum 11.80 < 25.
- Immutability: O_CREAT|O_EXCL in `sc_common.open_new`, `loadgen.open_new`, collectors (`collect_common.py:52-71`), fixtures (`make_synthetic_fixtures.py:42-51`); `--force` only on derived outputs; failed requests become rows; partial runs get meta status=failed.
- Collectors: read-only allowlist (`awscli.py:20-37`); MUTATING only in `failure_drill.py:58`; dry-run executes only the stub; tested; no delete/terminate/purge; drill needs `--execute --yes` and an approved manifest.
- Metrics hand-verified against `metrics.py`: nearest-rank fixture 10..100 → p50 50, p95 100, p99 100; retrieval fixture strict MRR 0.5, R@3 0.6667, nDCG@3 0.5169.
- Manifests: six (A–F), each with `cost_cap_usd` and `duration_cap_seconds`; A 10,000 and B 50,000 works (instructor item 1); C ceiling 10,000 vectors (item 2); B requires the A record and `--approve-50k`; all approved=false; `<id>.json` as `run-experiment.sh:49-54` requires.
- Evaluation scaffolding: templates contain no numeric values; `er_report.py` copies committed Phase 05 values with SHA-256; factory metrics count from `docs/agent-runs`.

### MEDIUM

- **M1** Ledger and caps can be bypassed without numeric flags (`loadgen.py:349-350, 374, 419-422, 431-432`; `cost_report.py:48-51`): `--ledger` to a fresh file resets the $1.80 ledger; `--budget-file` can change cost assumptions or limit; `--manifest` accepts any JSON with no schema or `approved` check; a `"synthetic": true` benchmark aimed at a real host is labeled SYNTHETIC, uses the synthetic ledger, skips the benchmark SHA check, and is excluded from real totals.
- **M2** `failure_drill.py:79-101` performs AWS mutations before checking that its write-once record can be written; a rerun from shell history mutates again and then fails to record. `subprocess.TimeoutExpired` (`awscli.py:79`) not caught.
- **M3** The drill does two failures (poison message and stop task) unless `--skip-stop-task`; the work order says one.
- **M4** D and E manifest commands cannot run through `scripts/run-experiment.sh` (runs `.command` verbatim, 59/73): missing required `--benchmark`, hardcoded `--run-id`, no way to add `--allow-insecure-http`.
- **M5** Raw outputs under `experiments/results/` dirty the tree; `scripts/lib/common.sh:312-325` excludes only `reports/*`, so `run-experiment.sh` refuses the next experiment. README does not mention committing between experiments.
- **M6** Analysis outputs carry no provenance (`an_common.py:42-51`), contrary to the CLAUDE.md provenance rule.

### LOW (deferred under rule 6 unless noted)

- L1 percentile rank computed with `ceil(p/100.0*n)` can be off by one for some p (not p50/p95/p99).
- L2 ledger key overwrite on rerun with a different `--out-dir`; concurrent-process race.
- L3 per-experiment model caps apply per invocation, not across reruns.
- L4 unexpected exception in a worker thread drops the row; meta still says completed.
- L5 duration cap can be overshot by in-flight requests (up to the 60 s request timeout).
- L6 async (202) replies count as answered with an empty list.
- L7 `scale_comparison.py:53` labeling ignores synthetic samples/cost inputs.
- L8 committed synthetic artifacts have weak provenance; the synthetic ledger is nearly full ($1.765 of $1.80).
- L9 `experiments/analysis/output/SYNTHETIC-examples/cost-per-experiment.csv` has no SYNTHETIC- prefix and synthetic=false rows; the guarding test checks only prefixed files. (Routed in repair 01.)
- L10 test gaps: failure_drill, run_suite, `--fetch-explain`, cost_report, scale_comparison, the real (non-loopback) path; two cap tests assert only rc==3.
- L11 no Bedrock metrics collector; the $2 cap rests on assumed tokens.
- L12 known documented gaps: E graph-only/vector-only return 422 on the deployed service (owners service-engineer / rag-engineer); C blocked on M13-2; drill does not check queue URL region.

### Reviewer's manual actions

1. Decide on a repair round for M1–M6. 2. Decide whether `experiments/results/SYNTHETIC-*` and the synthetic ledger are committed. 3. For M5, decide: commit between experiments, route `reports/experiments/**` ownership to factory-engineer, or an infra-engineer change to `SC_GENERATED_EXCLUDES`. 4. Resolve the no-sbt vs Stop-hook conflict. 5. Verify the Haiku 4.5 Bedrock price.

## Review 2 (re-review of Repair 01)

**Verdict: APPROVE WITH NOTES.** HIGH 0, MEDIUM 0, LOW 5 new (N1–N5). Reviewer ran nothing (no shell); the orchestrator's `131 passed in 38.32s` is UNVERIFIED by the reviewer.

### Status of review-1 findings (reviewer read the code)

- **M1 FIXED** — `enforce_target_rules` (`load-tests/loadgen.py:396-410`), called at :463 and :470, before the plan print/dry-run (:491) and `load_key` (:500). `--ledger`/`--budget-file` refused for non-loopback (:400-401); real ledger is the fixed `REAL_LEDGER` (:72, :483); manifest must pass `check_manifest` and be approved with an approver (:402-406); synthetic benchmark refused for non-loopback (:407-408); benchmark SHA required (:409-410). Loopback is an exact match on `127.0.0.1`/`localhost`/`::1` (:75, :388-393); other aliases get the strict rules (fails safe). `run_suite.py:46-57` applies the same rules; `cost_report.py:54-64` counts non-loopback "synthetic" metas as real and flags them.
- **M2 FIXED** — `create_start_record` (`experiments/collect/failure_drill.py:52-57`) at :137-147 before every AWS call; `run_action` (:60-74) logs `action_begin` before the mutation and records `TimeoutExpired` as `outcome_unknown`, exit 5 (:163), no retry.
- **M3 FIXED** — required `--action` with choices poison-message / stop-task (:87); one mutation per run; manifest and `experiments/README.md:47` match.
- **M4 FIXED** — no hardcoded run ids; `SC_RUN_ID`/`SC_LOADTEST_BENCHMARK` fallbacks (`run_suite.py:28-29`, `loadgen.py:355,360`, `failure_drill.py:83-85`); compatible with `scripts/run-experiment.sh:59,73`.
- **M5 FIXED within boundary** — `sc_common.results_root()`/`derived_root()` (:34-47); README "Between experiments" (:64-81). `SC_GENERATED_EXCLUDES` change routed to infra-engineer.
- **M6 FIXED** — `sc_common.write_provenance` (:167-181), `check_free` (:146-151), `an_common._provenance` (:33-36); all analysis scripts call `begin`; evaluation scripts write sidecars.
- **L9 FIXED** — everything under `SYNTHETIC-examples/` is prefixed and has a sidecar.

### Regression checks
Secrets pass; caps pass; write-once pass; synthetic labeling pass (stricter); raw files under `experiments/results/` unchanged by reading (names, ledger contents $1.76484, timestamps 08:39–08:47Z vs repair outputs 09:09Z; not hash-verified); ownership pass.

### New findings (LOW)
- **N1** Real ledger can undercount through a ledger-key collision (`loadgen.py:191-197`, key :485 excludes out-dir): retry with the same `SC_RUN_ID` and a different `SC_RESULTS_ROOT`/`--out-dir` drops earlier spend. Each rerun is still capped per run. Suggested: refuse a reused key for non-loopback targets. (Old L2, now also reachable via `SC_RESULTS_ROOT`.)
- **N2** README does not tell the human to commit the manifest approval edit; `run-experiment.sh:55` then refuses on the dirty tree (fails safe).
- **N3** `SYNTHETIC-cost-per-experiment.csv.provenance.json:98,101` stores an absolute home-directory path (`cost_report.py:74`, `extra=vars(a)` not made relative). Privacy and portability.
- **N4** Test gaps: no positive non-loopback control (approved manifest, matching SHA, reaching the key step); no provenance tests for the evaluation scripts.
- **N5** `failure_drill.py` with `--execute --yes` but `--dry-run` left in, with a real aws: the record shows `action_begin` and `action_end outcome=failed`, exit 4, for a call that never ran; run id used up. Fails safe, misleading record.

### Deferred LOWs changed by the repair
L10 partly closed; L8 examples now have sidecars (synthetic ledger unchanged at $1.765 of $1.80); L2 see N1. L1, L3–L7, L11, L12 unchanged.

### Residual risk (by design)
A loopback target is trusted by its label; port-forwarding `localhost` to the real ALB would book real spend as SYNTHETIC. Requires a deliberate act.
