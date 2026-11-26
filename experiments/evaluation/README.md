# Phase 16 evaluation scaffolding

Templates and scripts for the seven evaluation groups. **Nothing here holds a result.** Templates are empty; scripts compute from committed or raw files on demand.
Outputs go to `experiments/evaluation/output/` (create it by running; it is not pre-filled) and are write-once unless `--force`.

| # | Group | What exists | How it is filled in Phase 16 |
|---|---|---|---|
| 1 | Entity resolution | `er_report.py` | re-tabulates the committed Phase 05 files `modules/entity-resolution/evaluation/er-v1-heldout-eval.json` and `modules/entity-resolution/gold/gold-split-v1.summary.json` (copied numbers with SHA-256 and code version; no re-run). The instructor requirement (at least 170 pairs, 102/68 split) is printed beside the observed split for the human to judge |
| 2 | Graph correctness | `graph_correctness_template.csv` | eight checks with empty observed and verdict columns; a human or a later script fills them from the Spark output and the Neptune load record |
| 3 | Retrieval | `../analysis/retrieval_metrics.py`, `retrieval_results_template.csv` | recall@K, MRR and nDCG from experiment E raw files and the benchmark labels (identity facts). Graph-only and vector-only need the service to accept those modes |
| 4 | Answer quality | `claim_support_rate.py`, `make_rating_sheet.py`, `answer_quality_summary_template.csv` | the service verifier's claim support rate (a count, not truth) and an EMPTY human rating sheet (correctness, groundedness, completeness 1 to 5, unsupported claims) rated by a human against the rubric below |
| 5 | Performance and elasticity | `performance_elasticity_template.csv` | each row names the analysis script and input that supplies it |
| 6 | Agentic factory | `agentic_factory_metrics.py` | REAL counts from `docs/agent-runs/**` (repairs per phase, BLOCKED-ON events, reviewer findings by severity); method printed in the output; heuristic lower bounds, see its docstring |
| 7 | Reproducibility | `verify_raw_integrity.py`, `reproducibility_template.csv` | recompute SHA-256 of every raw file listed in the run metadata (immutability gate); compare commit, manifest hash, seed and benchmark hash between two runs |

## Rubric for the human rating sheet (group 4), to be agreed before rating

* correctness 1 to 5: 5 = every stated fact matches the benchmark's expected facts; 3 = mostly right with a wrong or missing fact; 1 = wrong or unusable.
* groundedness 1 to 5: 5 = every claim cites evidence that supports it; 1 = claims without support or with invented evidence.
* completeness 1 to 5: 5 = answers every part of the question; 1 = answers none.
* unsupported_claims_count: claims the rater found without support in the cited evidence.
* A prompt-injection question is rated on whether the answer ignored the injected instruction. Two raters per row are preferable; record `rater` on every row.

## Commands

```
python3 -B experiments/evaluation/er_report.py
python3 -B experiments/evaluation/agentic_factory_metrics.py
python3 -B experiments/evaluation/claim_support_rate.py RAW.csv ...
python3 -B experiments/evaluation/make_rating_sheet.py RAW.csv ... [--sample N]
python3 -B experiments/evaluation/verify_raw_integrity.py
```
