# Phase 16a local evaluation summary

Scope: groups 1-7 that need no AWS. No AWS, network or sbt was used. Python 3 standard library only, inputs read-only, raw outputs write-once. All raw paths are relative to `experiments/results/local/`. Every run's code state is `git_head f680ca5ef65648e64de3fed35616ea9e321bda2e` with an untracked-dirty tree (`tree_dirty: true`).

## Evidence-class legend

- **L** = local unit/contract tests, or checks run locally on committed code without real data.
- **F** = fixture demos (fixture model, scripted responders). They are not results about a real model.
- **R** = local runs on real data (1K/10K).
- **A** = AWS-validated. This phase has none.
- **C-DOC** = count derived from committed documents, not a run.
- `[[PENDING-AWS]]` = needs AWS (Phases 14/15c/16). `[[PENDING-EVAL]]` = needs sbt, run by the human.
- No SYNTHETIC fixture output (`experiments/results/SYNTHETIC-*`, `experiments/results/exp-*/synth-*`) is presented as a result here.

## Run index

| Group | Final run | Script sha256 (in that run's meta.json) | Superseded or other runs |
|---|---|---|---|
| g1 entity resolution | f2 | 7080fa8758abefa4a3264c97556a02dba908ee983018b4c7c2748e8a5744e1eb | f1, r1, r2 earlier runs |
| g2 graph correctness | f2 | 7080fa87... (same as g1) | f1, r1 |
| g3 retrieval | f2 | 7080fa87... | f1, r1 |
| g4 answer quality | f2 | 7080fa87... | f1, r1 |
| g5 performance, elasticity | f2 | 7080fa87... | f1, r1 |
| g6 agentic factory | **f3 FINAL** | **9624849f74e38e8ec842a1e792c89f6bcf9d30117e27079211c50c1a4d0ef2a5** | **f2 SUPERSEDED** (script sha 7080fa87...); f1, r1, r2, r3 earlier |
| g7 reproducibility | f2 | 7080fa87... | f1, r1 |

g6 f3 differs from g6 f2 only in meta.json (generated_at_utc, run_id, script_sha256). The only code change between the scripts is in `stated_counts`, which only g6 calls. Regenerating g1-g5 and g7 with the new script (scratch copy, not stored here) gave identical data files, with differences only in meta.json, in g7's timing and temp-directory text, and in the g1 `er_report_py/` subfolder (see Threats to validity, group 1).

## Group 1: entity resolution (R). Raw: `g1-entity-resolution/f2/`

Source: committed Phase 05 evaluation outputs, no recomputation of the policy (policy er-v1, code_version 74eee4e+repair04, LLM not used). Held-out file `er-heldout-metrics.csv`.

| Kind | n | TP / FP / FN / TN | Precision | Recall | F1 | Coverage | Unresolved | Conflict | Raw file |
|---|---|---|---|---|---|---|---|---|---|
| researcher | 90 | 40 / 3 / 7 / 40 | 0.930 | 0.851 | 0.889 | 0.744 | 0.233 | 0.022 | er-heldout-metrics.csv |
| work | 5 | 3 / 0 / 2 / 0 | not measurable | 0.600 | not measurable | 0.600 | 0 | 0.400 | er-heldout-metrics.csv |

Metrics by matching path (`er-by-path.csv`):

| Kind | Path | n | TP / FP / FN / TN | Precision | Recall | F1 |
|---|---|---|---|---|---|---|
| researcher | exact_id | 20 | 8 / 0 / 4 / 8 | 1.000 | 0.667 | 0.800 |
| researcher | deterministic | 31 | 30 / 1 / 0 / 0 | 0.968 | 1.000 | 0.984 |
| researcher | probabilistic | 39 | 2 / 2 / 3 / 32 | 0.500 | 0.400 | 0.444 |
| researcher | llm | 0 | NOT RUN | not measurable | not measurable | not measurable |
| work | exact_id | 5 | 3 / 0 / 2 / 0 | not measurable | 0.600 | not measurable |
| work | deterministic, probabilistic | 0 | n/a: no rows on this path | not measurable | not measurable | not measurable |
| work | llm | 0 | NOT RUN | not measurable | not measurable | not measurable |

Gold split (`er-gold-split-counts.csv`; `er-summary.json`): 240 rows read, 3 UNSURE excluded, 237 labelled pairs; calibration 142, held-out 95. Researcher calibration 70 MATCH / 64 NON_MATCH, held-out 47 / 43. Work calibration 7 MATCH / 1 NON_MATCH, held-out 5 MATCH / 0 NON_MATCH.

Cross-check against the committed Phase 05 results:
- The gate table row (researcher 90, 40 / 3 / 7 / 40, 0.930, 0.851, 0.889) matches the committed gate. `crosscheck-eval-vs-results-md.csv` has 9 items checked and 0 differences.
- `crosscheck-eval-vs-results-json.csv` lists 24 differing keys between er-v1-heldout-eval.json and er-v1-results.json (mostly `ABSENT` or None for notes and status fields, plus work exact_id precision 1 and F1 0.75 in the eval json, where results.json prints them as not measurable). This is a FINDING; nothing was corrected.
- Instructor requirement (at least 170 pairs, 60/40 into 102 and 68): 237 pairs meets 170, but the split is 142 / 95 against the required 102 / 68. Reported, not changed.

Threats to validity:
- The work held-out set has 5 MATCH and 0 NON_MATCH, so work precision and F1 are not measurable and recall rests on n=5.
- The llm path was NOT RUN. The unresolved rate counts the probabilistic band that would go to the LLM.
- The probabilistic path is weak (precision 0.500, recall 0.400 on n=39, only 4 predicted or actual positives each way). Negative result kept.
- Whether a NON_MATCH is a "hard" negative is not recorded in the committed split summary.
- The committed f2 folder contains `er_report_py/`, a copy of the Phase 05 tool outputs that keeps raw work precision values. It must not be read as a precision result. A regeneration with the current script does not reproduce this subfolder, and its origin is not established by this check.

## Group 2: graph correctness (R; the known-small-graph list is L). Raw: `g2-graph-correctness/f2/`

Source: local load on disk (`modules/storage/target/loads/L5`, main checkout, untracked; manifest codeVersion 1f6c6f0+phase07r1, spark codeVersion baa31d5+phase06r2).

| Measure | Observed | Raw file |
|---|---|---|
| Edge rows / vertex rows | 35,649 / 10,417 | graph-summary.json |
| Dangling endpoints, all 8 edge types | 0 of 35,649 (rate 0.0000) | referential-integrity.csv |
| Duplicate edge or vertex ids; duplicate unordered (pair, window) rows | 0; 0 (duplicate rate 0.0) | graph-summary.json |
| Researcher orphan rate (3,893 researchers) | 0.0 | graph-summary.json |
| Work orphan rate (1,000 works) | 0.0 | graph-summary.json |
| Vertices with no incident edge | Institution 250, Topic 3; overall rate 0.0243 | graph-summary.json |
| COAUTHORED_WITH edge support | 15,107 of 15,107 (support_rate 1.0); 8,416 all-time pairs, loaded 8,416, 0 missing, 0 unexpected | graph-summary.json |
| Checks GC-01..GC-08 (self loops, duplicates, metrics vs degree, component and community coverage, manifest counts, evidence window, stored shortest paths vs independent BFS 0 of 63) | all PASS | graph-correctness-checks.csv |
| Provenance audit: seeded sample (seed 160101) of 25 coauthor edges from 15,107 | 25 of 25 fully traced to work ids and source records; 0 evidence rows failed; source XML sha256 3dfcab53... | provenance-audit.csv, provenance-audit.json |

Known small-graph results (degree, components, k-core, shortest paths, communities, bridge): 13 committed Spark tests are cited by file and line in `known-small-graph-citations.md` (evidence class L). They were NOT re-run. The recorded pass counts (`sparkAnalytics 160` at phase-06 agent-report.md:143, `sparkAnalytics 268` at phase-09 agent-report.md:25) are as written in the committed reports, not re-observed.

Threats to validity: single 1K-scale load; 25-edge sample is small; the checks come from the same pipeline lineage as the load (consistency, not independent ground truth), though shortest paths and all-time pairs are checked against independent recomputation; the input load sits in an untracked path of the main checkout.

## Group 3: retrieval (R, restricted). Raw: `g3-retrieval/f2/`

Only one label-backed local retrieval run exists: the Phase 08 known-item smoke set (embedder local-hash-bow, dim 1024, 25 queries, K capped at 5). Result: recall@1, @3, @5, MRR and nDCG@5 are all 1.0000 for all 25 queries and for each of the 5 document types (5 queries each). Raw: `retrieval-vector-only-local.csv`.

Threats to validity:
- The smoke.json files of L1-L4 are byte-identical (sha256 05627e72...), so the four embedding-run rows are ONE measurement repeated, not four runs.
- Queries use tokens unique to the target document: lexical known-item lookups, not relevance-judged retrieval. A perfect score is expected and says nothing about semantic quality.
- Graph-only, real-embedder vector-only, fused and OpenSearch comparisons are `[[PENDING-AWS]]` (`retrieval-modes-pending.csv`).

## Group 4: answer quality (F, fixture model). Raw: `g4-answer-quality/f2/`

Source: Phase 09 composed benchmark, run R2 (R1 identical per the Phase 09 report), scripted `fake-model-v1`. 44 questions.

| Measure | Value | Raw file |
|---|---|---|
| Claims total / verified / partially_supported | 228 / 213 / 15 | answer-quality-summary.json |
| Claim support rate (verified) | 0.934 (213 of 228) | answer-quality-summary.json |
| Partially supported by category | institution 6, topic 6, prompt_injection 3 (reason semantic_context_only) | claims-by-category-status.csv |
| Identifier accuracy | 46 of 46 identity facts | answer-quality-summary.json |
| Numerical accuracy | 250 of 250 harness fact checks, 250 of 250 assert checks, 0 failed | answer-quality-summary.json |
| Contradiction rate | 0.0 (0 claims outside verified or partially_supported) | answer-quality-summary.json |
| Abstention | TP 4, FP 0, FN 0, TN 40 (precision 1.0, recall 1.0); ambiguous candidates listed 10 of 10 expected | answer-quality-summary.json |
| Forbidden strings | 0 violations in 18 checked | answer-quality-summary.json |
| Harness: all checks passed | 44 of 44 questions | answer-quality-summary.json, per-question.csv |

Human rating sheet: intentionally EMPTY for the human (`human-rating-sheet-EMPTY.csv`).

Threats to validity:
- Fixture model: these show wiring, verifier and abstention logic, not real-model answer quality.
- Expected values were generated from the same L4 tables the answers read, so accuracy checks are data-consistency checks, not independent judgments.
- Zero contradictions is an observation on 44 fixture answers, not a detection rate.
- The 15 partially_supported claims (5 questions with 3 of 3 claims not verified) are kept as a negative result.

## Group 5: performance and elasticity (skeleton). Raw: `g5-performance-elasticity/f2/`

Every item is `[[PENDING-AWS]]` (latency p50/p95/p99, throughput, error rate, ECS scale events, queue backlog and drain, DLQ depth, EMR nodes, wall seconds, node-hours, cost, failure recovery at 10k and 50k scale). No measurement was made. File: `performance-elasticity-PENDING.csv`. Evidence class: none (skeleton).

## Group 6: agentic software factory (C-DOC). Raw: `g6-agentic-factory/f3/` (FINAL; f2 SUPERSEDED)

Evidence class C-DOC: counts derived from committed `docs/agent-runs/**` text, not a run. 17 phase folders scanned. Each count below lists its source in the f3 files (`events.csv`, `phase-table.csv`, `severity-by-phase.csv`, `severity-stated-lines.csv`, `by-agent.csv`, `ambiguous.md`; totals in `factory-summary.json`).

| Measure | Value | Raw file |
|---|---|---|
| Phases scanned; phases with a stored review verdict | 17; 16 | factory-summary.json |
| First-pass acceptance by phase: yes / no / ambiguous (not scored) / n/a | 0 / 15 / 1 (phase-01) / 1 (phase-00) | phase-table.csv, factory-summary.json |
| First-pass rate over yes and no only | 0.0 (0 of 15); bounds if the ambiguous phase were all-no or all-yes: 0.0 to 0.0625 | factory-summary.json |
| Repair files (repair-NN.md), total | 22 (per phase in phase-table.csv; 5 phases with a first REQUEST_CHANGES have no repair file because the repair is recorded in agent-report.md or human-gate.md, so this undercounts) | phase-table.csv, ambiguous.md |
| STATUS: BLOCKED-ON lines | 11 (STATUS lines in total: 70) | events.csv, phase-table.csv |
| Ownership violations caught by a hook | confirmed 0; 4 matching lines are candidates or probe notes; the only claim (phase-04 review-report.md:140) is second-hand | ambiguous.md |
| Guard-hook block matching lines | 30 (matching lines, not distinct events) | events.csv |
| Stop-hook matching lines | 39 (matching lines, not distinct events) | events.csv |
| Manual-intervention matching lines | 145 (human-gate.md files present: 17) | events.csv |
| Substantial human rewrite | not derivable (2 candidate lines) | events.csv, ambiguous.md |
| Confirmed unsafe-action preventions | the post-ratification probes at phase-01 probe-report.md:11 (forbidden phrase blocked by guard-dangerous.sh) and :12 (secret-file read denied) | ambiguous.md |

Reviewer findings by severity, first line that states counts per phase (round not identified; no cross-phase total is given because the rounds and rubrics differ; see `severity-by-phase.csv` for file:line):

| Phase | Stated counts |
|---|---|
| phase-01 | HIGH 0 (review-report.md:70) |
| phase-03 | INFO 4, LOW 5 (review-report.md:534) |
| phase-06 | HIGH 0 (review-report.md:5) |
| phase-07 | HIGH 0, MEDIUM 8, LOW 13 (review-report.md:7) |
| phase-08 | HIGH 0, MEDIUM 3, LOW 9 (review-report.md:7) |
| phase-10 | MEDIUM 9, HIGH 0 (review-report.md:5) |
| phase-12 | HIGH 1, MEDIUM 7, LOW 13 (review-report.md:13) |
| phase-13 | HIGH 0, MEDIUM 8, LOW 9, INFO 3 (review-report.md:5, round 1) |
| phase-15b | HIGH 0, MEDIUM 6, LOW 12 (review-report.md:7) |
| phase-17a | MEDIUM 3, LOW 11, LOW 3 (human-gate.md:10; two LOW figures are as parsed from one line, see severity-stated-lines.csv) |
| phase-00, 02, 04, 05, 09, 11, 15a | no stated count (enumerated finding lines are in severity-by-phase.csv, all rounds, not deduplicated) |

Per-agent: `by-agent.csv` gives report STATUS counts and a co-occurrence share (agent not named in a repair file). It is NOT a first-pass rate.

Run note: f3 supersedes f2 after a fix to the parser `stated_counts` (adjacent counts such as "HIGH 0 MEDIUM 2" could produce phantom pairs). Every f3 value equals f2; `diff -rq f2 f3` differs only in meta.json (generated_at_utc, run_id, script_sha256). f2 is retained, not deleted.

Threats to validity:
- Counts are regex matches over prose. Guard and stop-hook figures are matching lines, not distinct events, and include false positives.
- "First review" is taken from an explicit statement, else the first stored verdict line, which in some reports is the newest round.
- Severity counts mix rounds and rubrics.
- The rate of 0 of 15 first-pass is scored against the reviewer's first verdict, which is a strict definition; the repair files may cover orchestrator-routed build or test fixes, not only defects.

## Group 7: reproducibility (L, partial). Raw: `g7-reproducibility/f2/`

Non-sbt checks in a fresh local git clone of HEAD f680ca5 (clone not dirty; no network; env CLAUDE_PROJECT_DIR and SC_RESULTS_ROOT removed), plus two checks in the worktree. Source: `clean-checkout-results.csv`, raw stdout and stderr in `raw-*.txt`.

| Check | Exit | Last line |
|---|---|---|
| validate-agent-files.sh (clean clone) | 0 | FAILURES: 0 |
| dataset manifest validator (clean clone) | 0 | checks passed: 346; failed: 0 |
| experiment manifest validator (clean clone) | 0 | RESULT PASSED (0 problem(s)) |
| pytest tests/datasets (clean clone) | 0 | 149 passed, 4 subtests passed in 16.14s |
| pytest tests/experiments (clean clone) | 0 | 131 passed in 36.20s |
| scripts/tests/test-scripts.sh (clean clone) | 0 | RESULT passed=253 failed=0 |
| dataset manifest validator (worktree) | 0 | checks passed: 355; failed: 0 |
| experiment manifest validator (worktree) | 0 | RESULT PASSED (0 problem(s)) |

Pending (`reproducibility-pending.csv`): `sbt clean check` from a clean checkout `[[PENDING-EVAL]]`; bounded pipeline replay `[[PENDING-AWS]]`; deployment from the approved plan `[[PENDING-AWS]]`; regeneration of reported tables and plots from Phase 15c/16 raw results `[[PENDING-AWS]]`.

Threats to validity:
- The clean clone contains only committed files. gitignored data under datasets/raw is absent (which is why the clean-clone dataset validator has 346 checks vs 355 in the worktree), and untracked tests, including `tests/experiments/test_local_eval.py` and the new local-evaluation script, are not exercised there.
- A reproducibility pass of the non-sbt checks does not cover the Scala build.
- Stdout hashes change between reruns because of timing text and temp-directory names; exit codes and result lines do not.

## Not covered in Phase 16a

All AWS-dependent measurements (group 5, graph-only, fused and real-embedder retrieval in group 3, pipeline replay and deployment in group 7) are `[[PENDING-AWS]]` or `[[PENDING-EVAL]]` and belong to Phases 15c and 16. No class-A result exists.
