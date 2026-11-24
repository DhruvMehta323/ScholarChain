# ScholarChain evaluation

Draft (Phase 17a). This file is **structure plus the results that already exist in committed evidence**. Everything else is a placeholder: `[[PENDING-EVAL: ...]]` for Phase 16 evaluation and `[[PENDING-AWS: ...]]` for numbers that need the deployed system or the Phase 15c runs. Evidence classes are kept separate throughout: **L** = local automated test, **F** = fixture or deterministic local demonstration, **R** = local run on real data (the 1,000-work DBLP selection), **A** = AWS-validated run on real data. Class A has no entry yet.

The scaffolding for the seven evaluation groups is [experiments/evaluation/](../experiments/evaluation/) (templates and scripts; its README states that nothing there holds a result). Raw results will live under `experiments/results/` (write-once); files named `SYNTHETIC-*` hold invented data for harness tests and are never results ([experiments/README.md](../experiments/README.md)).

## 1. Methods per group

| # | Group | Method (committed scaffold or contract) | Raw-result pointer | Status |
|---|---|---|---|---|
| 1 | Entity resolution | frozen policy `er-v1`; thresholds tuned on the calibration split only; held-out evaluated once; the ledger records the single evaluation | [er-v1-results.md](../modules/entity-resolution/evaluation/er-v1-results.md), [er-v1-heldout-eval.json](../modules/entity-resolution/evaluation/er-v1-heldout-eval.json), [gold-split-v1.summary.json](../modules/entity-resolution/gold/gold-split-v1.summary.json), [heldout-evaluations.jsonl](../modules/entity-resolution/gold/heldout-evaluations.jsonl) | **done locally (R)**; section 2 |
| 2 | Graph correctness | eight checks comparing Spark output, the Neptune load record and hand-written fixture expectations | [graph_correctness_template.csv](../experiments/evaluation/graph_correctness_template.csv) | local invariants exist (section 3); the template is empty: `[[PENDING-EVAL: graph correctness table]]` |
| 3 | Retrieval | recall@K, MRR and nDCG from experiment E raw files and the 44-question benchmark labels; graph-only and vector-only modes need the service to accept them (currently 422) | [retrieval_metrics.py](../experiments/analysis/retrieval_metrics.py), [retrieval_results_template.csv](../experiments/evaluation/retrieval_results_template.csv), benchmark `benchmark-v1.json` ([RUNTIME-AGENTS.md](../modules/rag/RUNTIME-AGENTS.md) section 9) | `[[PENDING-AWS: experiment E raw results]]`, `[[PENDING-EVAL: retrieval table]]` |
| 4 | Answer quality | claim support rate computed by the service verifier (a count, not truth) plus a human rating sheet (correctness, groundedness, completeness on 1 to 5, unsupported-claim count, per-row rater) with the rubric agreed before rating | [claim_support_rate.py](../experiments/evaluation/claim_support_rate.py), [make_rating_sheet.py](../experiments/evaluation/make_rating_sheet.py), [answer_quality_summary_template.csv](../experiments/evaluation/answer_quality_summary_template.csv) | `[[PENDING-EVAL: answer quality, needs live Bedrock answers]]` |
| 5 | Performance and elasticity | latency percentiles, scale events, queue drain, EMR capacity, 10K versus 50K, cost per experiment | [experiments/analysis/](../experiments/analysis/), [performance_elasticity_template.csv](../experiments/evaluation/performance_elasticity_template.csv), manifests A to F in [experiments/manifests/](../experiments/manifests/) | `[[PENDING-AWS: Phase 15c raw results]]` |
| 6 | Agentic factory | repairs per phase, BLOCKED-ON events, reviewer findings by severity, counted from `docs/agent-runs/**` (heuristic lower bounds, method printed by the script) | [agentic_factory_metrics.py](../experiments/evaluation/agentic_factory_metrics.py); hand-derived counts in the [final report](../reports/final-report.md) section 19 and the [index draft](agent-runs-index-draft.md) | partial: counts from committed files exist; script output `[[PENDING-EVAL: run agentic_factory_metrics.py in Phase 16]]` |
| 7 | Reproducibility | SHA-256 recomputation of every raw file in the run metadata; comparison of commit, manifest hash, seed and benchmark hash between two runs | [verify_raw_integrity.py](../experiments/evaluation/verify_raw_integrity.py), [reproducibility_template.csv](../experiments/evaluation/reproducibility_template.csv) | local determinism evidence exists (section 3); AWS rerun `[[PENDING-AWS: bounded pipeline rerun]]` |

## 2. Entity-resolution evaluation (existing result, class R)

Policy `er-v1`, feature set `er-features-v2`, code `74eee4e+repair04`, run root `rootI` on the 1K selection. All numbers below are from [er-v1-results.md](../modules/entity-resolution/evaluation/er-v1-results.md) and [gold-split-v1.summary.json](../modules/entity-resolution/gold/gold-split-v1.summary.json). The code-reviewer recomputed every held-out metric from the stored predictions and labels and all matched ([phase-05 gate](agent-runs/phase-05-entity-resolution/human-gate.md) section 3).

**Gold-set composition and split.** 240-row blind review queue (seed 20260101; 227 researcher pairs and 13 work pairs); 237 definitive human labels and 3 UNSURE excluded; a seeded stratified 60/40 split into 142 calibration and 95 held-out rows. Instructor-approved minimum: 170 reviewed pairs, split 102 calibration and 68 held-out, both with positives and hard negatives ([instructor-approvals.md](agent-runs/instructor-approvals.md) item 3); the committed set is larger than that minimum.

| Kind | Label | Calibration | Held-out |
|---|---|---|---|
| researcher | MATCH | 70 | 47 |
| researcher | NON_MATCH | 64 | 43 |
| work | MATCH | 7 | 5 |
| work | NON_MATCH | 1 | 0 |

(Researcher totals 117 MATCH and 107 NON_MATCH and work 12 MATCH and 1 NON_MATCH, as in the gate.)

**Frozen thresholds** (calibration only): researcher accept 0.77, reject 0.52; work 0.79 and 0.79 (based on 7 MATCH and 1 NON_MATCH, descriptive only).

**Held-out confusion matrices** (positive means ACCEPTED against label MATCH; rejected, unresolved and conflict count as predicted non-match):

| Kind | n | TP | FP | FN | TN | Precision | Recall | F1 |
|---|---|---|---|---|---|---|---|---|
| researcher | 90 | 40 | 3 | 7 | 40 | 0.9302 | 0.8511 | 0.8889 |
| work | 5 | 3 | 0 | 2 | 0 | not measurable (0 non-matches) | 0.60 | not measurable |

Researcher outcomes: 43 accepted, 24 rejected, 21 unresolved, 2 conflict; coverage 0.7444, unresolved rate 0.2333, conflict rate 0.0222. By match path (researcher): exact identifier n=20, precision 1.0000, recall 0.6667; deterministic n=31, precision 0.9677, recall 1.0000; probabilistic n=39, precision 0.5000, recall 0.4000, F1 0.4444; LLM path **not run** (0 rows). Final decisions on rootI (not a metric): 2,191 decisions, 999 accepted, 712 rejected, 470 unresolved, 10 conflict ([er-v1-results.md](../modules/entity-resolution/evaluation/er-v1-results.md)). Full threshold sensitivity: [er-v1-threshold-sensitivity.csv](../modules/entity-resolution/evaluation/er-v1-threshold-sensitivity.csv).

**Uncertainty and limits (from the gate).** One human labeler working in about 25 minutes through a local display tool that shows only the blind queue columns; no agent created, edited or suggested a label ([phase-05 gate](agent-runs/phase-05-entity-resolution/human-gate.md) section 5). After the evaluation the reviewer flagged possible label inconsistencies (for example one researcher pair labeled MATCH in three rows and NON_MATCH in one); **the labels stayed frozen** to avoid leaking held-out information, and the reported metrics may be biased in either direction. The held-out researcher set has 90 pairs, so one more or fewer false positive moves precision by about 2.3 percentage points (1 of 43 accepted) and one false negative moves recall by about 2.1 points (1 of 47 matches); this is arithmetic on the table above, not a computed interval. The exact-ID path metrics are partly circular because the blind queue shows ORCIDs and DOIs. Venue and affiliation features score a neutral 0.5 on real data (not persisted upstream). The work kind has 13 queue rows and 1 NON_MATCH, so work precision and F1 cannot be measured. Live LLM adjudication (P2) was not executed; the plan-mode run showed 470 eligible pairs and 60 planned calls at an estimated $0.13 with placeholder prices ([phase-05 gate](agent-runs/phase-05-entity-resolution/human-gate.md) section 4 item 6).

`[[PENDING-EVAL: any ER re-evaluation on the 10K or 50K subsets; no gold labels exist beyond the 1K slice]]`.

## 3. Local invariants and determinism evidence (classes L, F and R, not AWS)

| Check | Observed result | Source |
|---|---|---|
| Hadoop determinism | rootC and rootD, rootI and rootI2: "ALL TABLES EQUAL" | [phase-04 gate](agent-runs/phase-04-hadoop-mapreduce/human-gate.md), [phase-05 gate](agent-runs/phase-05-entity-resolution/human-gate.md) |
| Hadoop counters | EMITTED_PAIRS = EXPECTED_PAIRS = 8,420; 0 rejects on the 1K real data | [phase-04 gate](agent-runs/phase-04-hadoop-mapreduce/human-gate.md) section 5 |
| Spark determinism and invariants | sparkS1r2 and sparkS2r2 "ALL TABLES EQUAL"; 28 invariants checked, 28 OK; 0 dangling edge endpoints across 8 edge tables | [phase-06 gate](agent-runs/phase-06-spark-analytics/human-gate.md), [phase-06 agent-report](agent-runs/phase-06-spark-analytics/agent-report.md) |
| Spark metrics on the 1K sample (all-time window) | 3,893 nodes, 8,416 edges, 1,000 works, 969 components (largest 42), 983 communities, maximum team size 42; estimated average path length 1.0 over a sparse sample, so these demonstrate the pipeline and not research conclusions | [phase-06 agent-report](agent-runs/phase-06-spark-analytics/agent-report.md) |
| Storage loader determinism | L4 and L5 identical (`diff -r` empty); 0 dangling endpoints, 0 duplicates, 0 label mismatches; second DynamoDB reload 63,121 Unchanged and 0 conflicts | [phase-07 gate](agent-runs/phase-07-storage/human-gate.md) section 2 |
| Embedding ingestion (deterministic local embedder, class F) | 9,958 documents and vectors; a second pass with a new run ID skipped all 9,958 with 0 embedding calls; 0 dead-lettered; smoke 25 of 25 (self-retrieval only) | [phase-08 gate](agent-runs/phase-08-embeddings-vectors/human-gate.md) |
| Runtime benchmark (fake model client, class F) | 44 of 44 questions pass in two composed runs, byte-identical; 4 of 4 ambiguous-name questions abstain with at least 2 candidates; 4 of 4 prompt-injection questions pass; 84 model calls recorded in the run summary | [phase-09 gate](agent-runs/phase-09-runtime-agents/human-gate.md), [phase-09 review](agent-runs/phase-09-runtime-agents/review-report.md) |
| API local session (class F) | scripted session run twice, all response pairs identical | [phase-10 gate](agent-runs/phase-10-services/human-gate.md) |
| Automated tests (class L) | `sbt check` 2,662 passed, 0 failed at Phase 11 (per-phase counts: [index draft](agent-runs-index-draft.md)); Python dataset tests 149, experiment tests 131, script harness 253 | [phase-11 gate](agent-runs/phase-11-adversarial-review/human-gate.md); counts as recorded in the [15a gate](agent-runs/phase-15a-dataset-scale/human-gate.md) (149), [15b gate](agent-runs/phase-15b-experiment-harness/human-gate.md) (131) and [13 gate](agent-runs/phase-13-automation/human-gate.md) (253) |

## 4. Table skeletons to fill (nothing in these tables is a result)

### 4.1 Elasticity (experiments A to D)

| Run id | Scale | Nodes (min, max) | Stage durations (s) | Peak ECS tasks | Scale-out event time | Scale-in event time | Cost (USD, before credits) | Raw file |
|---|---|---|---|---|---|---|---|---|
| `[[PENDING-AWS: exp-a 10K]]` | 10K | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | n/a | n/a | n/a | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` |
| `[[PENDING-AWS: exp-b 50K]]` | 50K | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | n/a | n/a | n/a | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` |
| `[[PENDING-AWS: exp-d query load]]` | n/a | n/a | n/a | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` |

Manifest caps (committed, not results): sum of cost caps 11.80 USD and of model caps 1.85 USD per `python3 -B experiments/validate_manifests.py`.

### 4.2 Query latency and errors (experiment D, four concurrency levels)

| Concurrency | Requests | p50 (ms) | p95 (ms) | p99 (ms) | Non-2xx | Timeouts | 429 |
|---|---|---|---|---|---|---|---|
| per manifest | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` |

### 4.3 Retrieval modes (experiment E and the benchmark)

| Mode | recall@K | MRR | nDCG | Abstention rate | Claim support rate |
|---|---|---|---|---|---|
| graph + vector | `[[PENDING-EVAL]]` | `[[PENDING-EVAL]]` | `[[PENDING-EVAL]]` | `[[PENDING-EVAL]]` | `[[PENDING-EVAL]]` |
| graph only | `[[PENDING-EVAL: service refuses this mode with 422 as built]]` | | | | |
| vector only | `[[PENDING-EVAL: service refuses this mode with 422 as built]]` | | | | |

### 4.4 Failure and recovery (experiment F)

| Fault | Detection source | Time to detect | Recovery action | Time to recover | Data-integrity check | Incident record |
|---|---|---|---|---|---|---|
| `[[PENDING-AWS]]` | | | | | | `[[PENDING-AWS: docs/incidents/...]]` |

### 4.5 S3 Vectors ingestion (experiment C)

| Vectors written (target up to 10,000) | Throughput | Rejected | Retries | Query p50 and p95 | DLQ count |
|---|---|---|---|---|---|
| `[[PENDING-AWS: actual count]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` | `[[PENDING-AWS]]` |

### 4.6 Agentic factory (group 6)

Hand-derived counts from committed files are in [reports/final-report.md](../reports/final-report.md) section 19 (repair files per phase, reviewer verdict history, findings by severity). The script output `[[PENDING-EVAL: agentic_factory_metrics.py output]]` should be compared with them; the script's counts are heuristic lower bounds.

## 5. Uncertainty

- Confidence intervals are not computed anywhere in the committed evidence. For ER, the held-out researcher set has n=90, so a binomial interval would be wide; `[[PENDING-EVAL: intervals if the human wants them]]`.
- Performance results will come from single runs per scale unless Phase 15c repeats them; repetition count `[[PENDING-AWS]]`.
- Placeholder model prices are used in every cost estimate that includes LLM calls ([phase-05 gate](agent-runs/phase-05-entity-resolution/human-gate.md), [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 10).

## 6. Threats to validity

| Threat | Direction | Source |
|---|---|---|
| Single labeler, fast labeling pass, flagged inconsistent rows left unchanged | metrics may be biased either way | [phase-05 gate](agent-runs/phase-05-entity-resolution/human-gate.md) section 5 |
| Gold labels cover only the 1K slice | ER results do not transfer to 10K or 50K without new labels | same |
| The 1K sample is sparse (largest components are single-paper cliques) | graph metrics demonstrate the pipeline, not collaboration structure | [phase-06 gate](agent-runs/phase-06-spark-analytics/human-gate.md) section 7 |
| Identity rules (M1, M5, suffix rules) fired 0 times on real 1K data | proven by fixtures only | [phase-06 gate](agent-runs/phase-06-spark-analytics/human-gate.md) |
| Benchmark expectations are computed from Phase 07 tables by a deterministic generator and run against a fake model client | tests the pipeline and verifier, not a live model's wording | [phase-09 gate](agent-runs/phase-09-runtime-agents/human-gate.md) |
| LLM verification is off by default; free wording can pass the deterministic verifier | answer-quality metrics may overstate groundedness | [RUNTIME-AGENTS.md](../modules/rag/RUNTIME-AGENTS.md) section 11 |
| Narrowed homonym rule: a same-name researcher below the top 100 vector hits can go undetected | measured in Phase 16 | [phase-09 gate](agent-runs/phase-09-runtime-agents/human-gate.md) decision 3 |
| Title-only paper vectors at 1K | retrieval over paper text is weaker than with abstracts | [phase-08 gate](agent-runs/phase-08-embeddings-vectors/human-gate.md) decision 4 |
| Self-retrieval smoke checks only | do not measure neighbor quality | same, decision 5 |
| Crossref coverage lower for DataCite DOIs; ORCID is a 5,000-iD sample; 10K and 50K selections come from an index built under the older eligibility policy (0 of 10,000 and 0 of 50,000 selected rows ineligible when re-checked) | enrichment is biased toward DOIs Crossref and OpenAlex cover | [phase-15a gate](agent-runs/phase-15a-dataset-scale/human-gate.md) |
| Cost and latency are shared-account and Region specific (`us-east-2`); a Paid-plan account; free-tier allowances not assumed in HIGH | not generalizable | [INFRA-CONTRACTS.md](../infra/terraform/INFRA-CONTRACTS.md) section 10 |
| Test infrastructure flakiness under concurrent load (timeouts and out-of-memory errors unrelated to the change) | a CHECK result can differ between runs | [phase-07 gate](agent-runs/phase-07-storage/human-gate.md) section 5, [phase-08 gate](agent-runs/phase-08-embeddings-vectors/human-gate.md) section 4 |
| Reviewers ran no commands in several rounds and relied on the orchestrator's counts | counts in some gates are orchestrator-observed, not reviewer-verified | [phase-09 review](agent-runs/phase-09-runtime-agents/review-report.md), [phase-15b gate](agent-runs/phase-15b-experiment-harness/human-gate.md) section 4 |

## 7. Replaying the local part of this evaluation

```bash
python3 -B experiments/validate_manifests.py          # recorded in the 15b gate: RESULT PASSED (0 problem(s))
python3 -B -m pytest -p no:cacheprovider -q tests/experiments     # recorded in the 15b gate: 131 passed
python3 -B experiments/evaluation/er_report.py         # re-tabulates the committed ER files; writes under experiments/evaluation/output/ (not replayed by this draft: it writes into an experiments path)
```
