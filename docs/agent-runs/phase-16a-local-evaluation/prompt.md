# Phase 16a — Local evaluation groups (work order)

Phase folder: `docs/agent-runs/phase-16a-local-evaluation/`
Lane: **lane 2** (worktree `ScholarChain-lane2`, branch `lane2`), after Phase 17a is committed.
Purpose: run now every Phase 16 evaluation group that needs no AWS, so Phase 16 only adds the AWS-dependent groups after Phases 14–15c. Phases 16a and 16 are combined into one Phase 16 at the end.
Spec: `COURSE-SPEC.local.md` (read-only local copy, excluded from git), section "Phase 16: evaluate data quality, retrieval, answers, and the agentic factory".

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| E | experiment-engineer | `experiments/**` (raw and derived evaluation outputs under `experiments/results/local/`) | always |
| C | test-engineer | `tests/experiments/**` | only if E changes or adds scripts |
| D | code-reviewer | nothing | always |

## 0. Rules

1. You route; owners write. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. No invented numbers. Every derived number cites its raw file; raw outputs are write-once and never edited.
3. **No AWS, no network, no sbt** (lane 1 is running Phase 14 with sbt). Python 3 standard library and the committed Phase 15b scripts only. Inputs are committed files and the local real-data outputs already on disk from earlier phases (read-only); if a needed local output is missing, record it as NOT AVAILABLE, do not regenerate it with sbt.
4. Do not edit production code (`modules/**`), `scripts/**`, `infra/**`, the rule files, `docs/agent-runs/**` (human-owned), or the documentation files. Do not change the Phase 05 gold set or its results.
5. Every output is labeled with its evidence class: **L** local unit/contract tests, **F** fixture demos, **R** local runs on real data (1K/10K), **A** AWS-validated. This phase produces only L, F and R. SYNTHETIC fixture output is never presented as a result.
6. Do not stage or commit. `## Progress checkpoint` after each work order. One repair round at most. Target time: 1.5 hours.
7. If the stop hook demands sbt because of merge timestamps, do not run it; I run the canonical CHECK later.

## 1. Preconditions (read-only)

- HEAD on `lane2` contains Phase 17a; `git status --short` clean except this folder.
- Read the spec's Phase 16 section, `experiments/evaluation/**` and `experiments/README.md` (Phase 15b scaffolding), the Phase 05 gold-set results and gate, the Phase 06–09 gates and agent reports (known-graph tests, local real-data runs, the benchmark), and every `docs/agent-runs/*/` folder.

## 2. E — experiment-engineer

Run the Phase 15b evaluation scaffolding on real committed inputs and write raw outputs under `experiments/results/local/<group>/<run-id>/` plus a derived summary per group:

1. **Group 1, entity resolution (R):** re-derive from the committed Phase 05 evaluation outputs only (no recomputation of the policy): precision, recall, F1, confusion matrix, coverage, unresolved rate and metrics by matching path, separately for researcher and work resolution. Every figure must equal the committed Phase 05 results; any difference is a finding, not a correction.
2. **Group 2, graph correctness (L/R):** from the committed known-graph test fixtures and the local real-data Spark/storage outputs already on disk: referential integrity, duplicate rate, orphan rate, coauthor-edge support rate, and a sampled provenance audit (a seeded random sample of 25 coauthor edges traced back to supporting work IDs and source records). Known small-graph results (degree, components, paths, communities, bridge, k-core) are cited from the committed tests and gates, not re-run.
3. **Group 3, retrieval (R, local only):** only if a local, label-backed retrieval run already exists (Phase 08/09 benchmark); otherwise write the group skeleton with `[[PENDING-AWS]]`. Graph-only, vector-only and fused comparison on AWS belongs to Phase 15c/16.
4. **Group 4, answer quality (F/R, local only):** from the committed Phase 09 benchmark runs (fixture model): claim support rate, identifier and numerical accuracy, contradiction rate and abstention behavior, labeled as fixture-model results. Leave the human rating sheet empty for me.
5. **Group 6, agentic software factory (from `docs/agent-runs/**`):** first-pass acceptance rate by agent, BLOCKED-ON events, ownership violations caught, reviewer findings by severity, repair iterations per phase, manual interventions, hooks or tests that prevented an unsafe action (including the guard blocks and the stop-hook overrides), and artifacts that needed substantial human rewrite. Each count lists the file and line it came from; ambiguous cases are listed, not guessed.
6. **Group 7, reproducibility (L, partial):** clean-checkout validation through the non-sbt checks only (agent validator, manifest validators, Python suites, script harness), with exact outputs saved as raw files. `sbt clean check`, the bounded pipeline replay and deployment from the approved plan are `[[PENDING-EVAL]]` / `[[PENDING-AWS]]`.
7. **Group 5, performance and elasticity:** skeleton only, `[[PENDING-AWS]]`.
8. `experiments/results/local/SUMMARY.md`: one table per group with value, evidence class, raw-file path, and threats to validity; negative results kept.

## 3. C — test-engineer (only if E added or changed scripts)

Tests for any new or changed evaluation script on hand-computed examples; run the Python suites and report exact counts.

## 4. D — code-reviewer

Check: every derived number traces to a raw file; Group 1 equals the committed Phase 05 results; evidence classes correct and never mixed; no SYNTHETIC data presented as a result; factory counts traceable to `docs/agent-runs/**` lines; nothing outside the allowed paths changed. Verdict.

## 5. STOP

STOP if a precondition fails, a boundary would be crossed, any AWS, network or sbt call is needed, or a second repair round would be needed. Otherwise write "Phase 16a complete; waiting for human gate" and STOP. Do not commit or stage.