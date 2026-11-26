# Phase 15b — Repair 01 (the only repair round allowed by rule 6)

Trigger: review 1 REQUEST CHANGES (HIGH 0, MEDIUM 6, LOW 12). Policy: fix HIGH and MEDIUM, defer LOW.

## Routing

1. experiment-engineer: M1 (ledger/caps bypass), M2 (drill record ordering, TimeoutExpired), M3 (exactly one drill failure), M4 (manifest commands runnable through `scripts/run-experiment.sh`), M5 (configurable results root plus documented commit between experiments), M6 (provenance sidecars on derived outputs). Also L9 (non-prefixed file in `SYNTHETIC-examples`), because it bears directly on the synthetic-labeling gate. Other LOWs deferred.
2. test-engineer: tests for M1, M2, M3 and the L9 test gap; update any test the fixes intentionally invalidate.
3. code-reviewer: re-review of the repair.

## Items outside this phase's boundary (routed, not done)

- Adding `experiments/results` to `SC_GENERATED_EXCLUDES` in `scripts/lib/common.sh`: infra-engineer, future work order.
- An owner for `reports/experiments/**` if raw results should move there: factory-engineer (ownership gap).

## Outcome

(filled in below after the repair)

### Step 1 — experiment-engineer: STATUS COMPLETE

- Agent report: M1–M6 and L9 fixed inside `experiments/**` and `load-tests/**`; no raw file under `experiments/results/` touched; derived SYNTHETIC examples regenerated with provenance sidecars; non-prefixed `cost-per-experiment.csv` removed (replaced by `SYNTHETIC-cost-per-experiment.csv`).
- Key changes (agent's report): non-loopback runs refuse `--ledger`/`--budget-file`, unapproved or schema-invalid manifests, synthetic benchmarks and benchmark SHA mismatch, all before the key is read (exit 3); drill writes a write-once `record.jsonl` start event before any AWS call, records each action, catches TimeoutExpired (exit 5); drill takes a required single `--action poison-message|stop-task`; `SC_RUN_ID`, `SC_LOADTEST_BENCHMARK`, `SC_RESULTS_ROOT` env fallbacks; manifest commands runnable verbatim by `run-experiment.sh`; README "Between experiments" commit step; `<output>.provenance.json` sidecars on every derived output.
- Orchestrator verification (observed): `git status --porcelain` unchanged (only the four untracked trees); validator `RESULT PASSED (0 problem(s))`; `ls SYNTHETIC-examples | grep -v '^SYNTHETIC-'` prints nothing; pytest `2 failed, 57 passed in 13.79s` — the two failures (`test_real_looking_input_is_not_labeled_synthetic`, `test_synthetic_benchmark_labels_retrieval_outputs`) assert an exact directory listing that now also contains the M6 sidecar, as the agent predicted.
- Not shown by the agent: the `run-experiment.sh` SC-DRYRUN command line (the dirty-tree check refuses before it; expected until commit).

### Step 2 — test-engineer: STATUS COMPLETE

- Agent report: 4 new test files (`test_target_rules.py` 30, `test_failure_drill.py` 12, `test_manifest_commands.py` 7, `test_provenance.py` 23); `test_analysis.py` (two M6 listing assertions updated to the exact data file plus sidecar, one tightened) and `test_loadgen.py` (cap tests now assert the specific refusal messages) modified. No production edits; no production defect found.
- Orchestrator re-run (observed): `PYTHONDONTWRITEBYTECODE=1 python3 -B -m pytest -p no:cacheprovider tests/experiments -q` → `131 passed in 38.32s`. `git status --porcelain` unchanged (four untracked trees only).

### Step 3 — code-reviewer re-review: APPROVE WITH NOTES

- M1–M6 and L9 FIXED (file:line evidence in `review-report.md`, Review 2). HIGH 0, MEDIUM 0, 5 new LOW (N1–N5), not repaired: rule 6 allows no second round, and LOWs are deferred.
