# Phase 15b — Experiment harness: orchestrator log and agent reports

Assembled by the orchestrator (main session). Agent reports are quoted from the subagents' returned messages.

## Progress checkpoint — preconditions (orchestrator, read-only)

- cwd: `/Users/dhruv_mehta/Desktop/ScholarChain-lane2`, branch `lane2` (observed `git branch --show-current`).
- Tree: `git status --porcelain` printed only `?? docs/agent-runs/phase-15b-experiment-harness/`.
- HEAD: `3115aa3 Phase 13: build, deploy, pipeline and experiment automation (gate accepted with notes)`.
- **Assumption (Phase 13 accepted hash):** the launch message contained the unfilled placeholder `<hash from Step 1>` instead of a hash. Phase 13 has no `commit.txt` yet (its human gate says it is added with this phase's commit). The orchestrator treats `3115aa3` as the Phase 13 accepted commit, because it is the only Phase 13 commit and its message records the gate acceptance. HEAD equals that commit. **Human check:** confirm `3115aa3` when writing Phase 13 `commit.txt`.
- Inputs present: `scripts/run-experiment.sh`, `scripts/run-bounded-pipeline.sh`, `scripts/env-window.sh`, `scripts/smoke-test.sh`, `docs/agent-runs/phase-13-automation/human-gate.md`, `infra/terraform/INFRA-CONTRACTS.md`, `infra/terraform/modules/observability/` (dashboard), `docs/agent-runs/instructor-approvals.md`.
- Read from `main` with `git show main:` (committed content only): `modules/services/API.md` (exists), `modules/rag/src/main/resources/benchmarks/benchmark-v1.json` (exists).
- `experiments/`, `load-tests/` and `tests/experiments/` do not exist yet.
- Tooling observed: Python 3.14.2; matplotlib 3.10.8 installed; pytest 9.0.2 installed; PyYAML 6.0.3 installed.
- **Known conflict, recorded before dispatch:** this work order forbids sbt, but the constitutional Stop/SubagentStop hook (`.claude/hooks/verify-tests-ran.sh`) blocks ending a turn when any file under `tests/` is newer than `.claude/.last-test-run` (currently `exit=1`, dated Nov 22). Work order C writes `tests/experiments/**`, so the hook will fire. The orchestrator will not run sbt (rule 4). See the final checkpoint for the outcome.

## Progress checkpoint — Work Order A (experiment-engineer): STATUS COMPLETE

- Agent report: STATUS: COMPLETE; 122 files created under `experiments/**` and `load-tests/**`; nothing modified elsewhere (agent's report).
- Orchestrator verification (observed): `git status --porcelain` shows only `?? docs/agent-runs/phase-15b-experiment-harness/`, `?? experiments/`, `?? load-tests/` (no boundary crossed). `python3 -B experiments/validate_manifests.py` → 6 manifests OK (A $3.00/10800 s, B $4.00/14400 s, C $1.00/5400 s, D $1.80/3600 s, E $1.20/3600 s, F $0.80/2700 s; all approved=False); model caps 1.85 USD (limit 2.00); cost caps 11.80 USD (budget 25.00); `RESULT PASSED`, exit 0.
- Deviation reported by agent: manifests are `.json`, not `.yaml`, because `scripts/run-experiment.sh` reads `<id>.json` with jq.
- Local verification reported by agent (not re-run by orchestrator): loadgen against a 127.0.0.1 dummy at c=1 and c=4 (plus suite 1/4/8/16), all guards refused as designed; collectors `--dry-run` with stub `aws`, exit 0; all analysis scripts on SYNTHETIC fixtures, exit 0; hand-checked percentile (p50=50, p95=100, p99=100) and retrieval metrics (MRR 0.5, recall@3 0.6667, nDCG@3 0.5169).
- Open items from agent, for the human: Haiku 4.5 price ($1/$5 per M tokens) UNVERIFIED; experiment E graph-only/vector-only modes are refused (422) by the service on main (`graphLimit 0`, `vectorLimit != 30`) — needs service-engineer / rag-engineer; C blocked by M13-2; tiny n means p99 = max and ECS scale-out may not fire in D; decide whether `experiments/results/SYNTHETIC-*` verification artifacts are committed.
- Full agent report is retained in the orchestrator session transcript; key fields summarized above.

## Progress checkpoint — Work Order C (test-engineer): STATUS COMPLETE

- Agent report: 4 files created under `tests/experiments/` (conftest.py, test_loadgen.py, test_analysis.py, test_manifests_and_collectors.py); no production edits; 0 production defects found.
- Orchestrator re-run (observed): `PYTHONDONTWRITEBYTECODE=1 python3 -B -m pytest -p no:cacheprovider tests/experiments -q` → `59 passed in 13.39s`. `git status --porcelain` afterwards: only the phase folder, `experiments/`, `load-tests/`, `tests/experiments/` untracked.
- Agent-reported mutation check (scratchpad copy, not the repo): five deliberate breakages (request cap, model-call cap, key-mode check, percentile rank, nDCG IDCG, B gating) → `14 failed, 45 passed`. Not re-run by the orchestrator.
- Not covered by tests (agent's report): `--fetch-explain`, `--ramp`, `run_suite.py`, `scale_comparison`, `queue_drain`, `emr_capacity`, `cost_report`, `evaluation/**`, CloudWatch/EMR/logs/drill collectors.
- Observation for review: `experiments/analysis/output/SYNTHETIC-examples/cost-per-experiment.csv` has no SYNTHETIC- prefix and `synthetic=false` rows (contains only "NOT RUN / NO COST FILE" placeholders).
- `sbt check`: NOT RUN (work order rule 4). Marker not written.

## Progress checkpoint — Stop hook fired (orchestrator)

- `.claude/hooks/verify-tests-ran.sh` blocked the orchestrator's stop twice (newer file: `tests/experiments/conftest.py`).
- `sbt check` NOT RUN: the work order (rule 4) forbids sbt and network. Only Python test files under `tests/experiments/` changed; no Scala, build or `modules/**` file changed.
- **Human decision at the gate:** run the canonical test command (CLAUDE.md section "Definition of done") yourself, or accept the deviation.

## Progress checkpoint — Work Order D (code-reviewer), first review: REQUEST CHANGES

- Verdict: REQUEST CHANGES — HIGH 0, MEDIUM 6, LOW 12. Full text to be stored by the orchestrator as `review-report.md`.
- MEDIUM: M1 ledger/caps bypass via `--ledger`, `--budget-file`, unvalidated `--manifest`, synthetic benchmark against a real host; M2 failure drill mutates before checking its write-once record (and uncaught TimeoutExpired); M3 drill performs two failures, work order says one; M4 D/E manifest commands cannot run through `scripts/run-experiment.sh` (missing `--benchmark`, hardcoded run id, no way to add `--allow-insecure-http`); M5 raw outputs under `experiments/results/` dirty the tree so `run-experiment.sh` refuses later experiments; M6 analysis outputs carry no provenance (CLAUDE.md provenance rule).
- Repair policy (rule 6): fix HIGH and MEDIUM, defer LOW, at most one repair round. Repair 01 routed: experiment-engineer (M1–M6, plus L9 since it is a one-line labeling fix tied to the synthetic-labeling gate), then test-engineer (tests for M1–M3 and the L9 test gap), then code-reviewer re-review. See `repair-01.md`.

## Progress checkpoint — Repair 01 and re-review

- Repair 01 (`repair-01.md`): experiment-engineer fixed M1–M6 and L9; test-engineer updated two M6-affected tests, strengthened the cap tests, and added 72 tests. Orchestrator re-run: `131 passed in 38.32s`.
- Code-reviewer Review 2 (`review-report.md`): **APPROVE WITH NOTES** — HIGH 0, MEDIUM 0, LOW 5 new (N1–N5). M1–M6 and L9 FIXED. No second repair round (rule 6); LOWs deferred.

## Final checkpoint — Phase 15b complete; waiting for human gate

STATUS: COMPLETE (implementation and review gates); `sbt check` NOT RUN (see below)

- Files: untracked `experiments/`, `load-tests/`, `tests/experiments/`, plus this phase folder (`agent-report.md`, `review-report.md`, `repair-01.md`). Nothing staged or committed. Final `git status --porcelain` showed only these four untracked paths.
- Tests: `tests/experiments` → `131 passed in 38.32s` (orchestrator-observed, final run). Manifest validator → `RESULT PASSED (0 problem(s))`.
- No AWS call, no network beyond 127.0.0.1, no sbt, no edits outside the work order's paths (agent reports; reviewer's ownership check passed).
- **`sbt check` NOT RUN.** Work order rule 4 forbids sbt; the Stop hook (`.claude/hooks/verify-tests-ran.sh`) blocked the orchestrator's turn end repeatedly because `tests/experiments/*.py` are newer than the test marker. Only Python test files changed under the hook's roots; no Scala, build or `modules/**` file changed. **Human decision:** run the canonical command from CLAUDE.md, or accept the deviation.

### Items for the human gate

1. Confirm `3115aa3` as the Phase 13 accepted commit (the launch message had an unfilled placeholder).
2. **N3 (privacy):** `experiments/analysis/output/SYNTHETIC-examples/SYNTHETIC-cost-per-experiment.csv.provenance.json` (lines ~98, 101) contains an absolute home-directory path. Decide before commit (exclude, or route a fix in a later work order).
3. Decide whether `experiments/results/SYNTHETIC-*` verification artifacts and the SYNTHETIC ledger ($1.765 of $1.80 simulated) are committed.
4. Deferred LOWs: review 1 L1–L8, L10–L12 and review 2 N1–N5 (notably N1 ledger-key collision; N2 commit the manifest approval edit before `run-experiment.sh`; N5 drill record when `--dry-run` is left in).
5. Verify the Bedrock Haiku 4.5 price (agent used $1/$5 per M tokens, UNVERIFIED) and edit `experiments/manifests/model-budget.json` if needed.
6. Experiment E: graph-only and vector-only modes are refused (422) by the service on `main` — route to service-engineer / rag-engineer. Experiment C: blocked on M13-2.
7. Routing outside this phase: infra-engineer — add `experiments/results` to `SC_GENERATED_EXCLUDES` (`scripts/lib/common.sh`); factory-engineer — owner for `reports/experiments/**` if wanted, and the Stop-hook vs no-sbt conflict.
8. Deviation: manifests are `.json` (not `.yaml`) because `scripts/run-experiment.sh` reads `<id>.json` with jq.
