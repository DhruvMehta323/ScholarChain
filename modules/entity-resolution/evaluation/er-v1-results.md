# er-v1 results (held-out, frozen policy)

Policy `er-v1`, feature set `er-features-v2`, code `74eee4e+repair04`, run root `modules/hadoop/target/runs/rootI`. Split checksum `1fad6c2d47aaf68c9f8d3621a53dd935f21881da8b8a9988869edce77d2f8e34`. Thresholds origin: `calibrated-on-calibration-split-only`.

## Statement of evaluation

Evaluate scored all 95 held-out rows once. The author of this report then read the Evaluate output (metrics, the 12 error rows with stored features and reason codes) and the split file columns (pair_id, entity_kind, label, split). Queue text (names, titles, ids) of held-out pairs was not read. No threshold, feature, policy or split was changed after Evaluate.

`Evaluate` ran ONCE on held-out (ledger `modules/entity-resolution/gold/heldout-evaluations.jsonl` has one line, `disclosedReEvaluation` null). LLM path: **not executed: provider decision pending (Bedrock vs. Anthropic API, $25 budget)**. No LLM call, no API key read, adjudication cache absent.

## Frozen thresholds

| kind | accept | reject | band low | band high |
|---|---|---|---|---|
| researcher | 0.77 | 0.52 | 0.52 | 0.77 |
| work | 0.79 | 0.79 | 0.79 | 0.79 |

## Split composition (gold-split-v1, seed 20260101)

240 labelled rows, 3 UNSURE excluded; calibration 142, held-out 95.

| kind | label | calibration | held-out |
|---|---|---|---|
| researcher | MATCH | 70 | 47 |
| researcher | NON_MATCH | 64 | 43 |
| work | MATCH | 7 | 5 |
| work | NON_MATCH | 1 | 0 |

## Held-out metrics

### researcher (n=90)

Confusion matrix (positive = ACCEPTED vs MATCH; rejected, unresolved and conflict count as predicted non-match):

| | predicted match | predicted non-match |
|---|---|---|
| actual MATCH | TP 40 | FN 7 |
| actual NON_MATCH | FP 3 | TN 40 |

Precision 0.9302, recall 0.8511, F1 0.8889.

Outcomes: accepted 43, rejected 24, unresolved 21, conflict 2. Coverage (accepted + rejected) 0.7444, unresolved rate 0.2333, conflict rate 0.0222.

| path | n | TP | FP | FN | TN | precision | recall | F1 |
|---|---|---|---|---|---|---|---|---|
| exact_id | 20 | 8 | 0 | 4 | 8 | 1.0000 | 0.6667 | 0.8000 |
| deterministic | 31 | 30 | 1 | 0 | 0 | 0.9677 | 1.0000 | 0.9836 |
| probabilistic | 39 | 2 | 2 | 3 | 32 | 0.5000 | 0.4000 | 0.4444 |
| llm | 0 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

### work (n=5)

DESCRIPTIVE ONLY: held-out work has 5 MATCH and 0 NON_MATCH. Precision, specificity and FP are not measurable; no precision conclusion is drawn.

Confusion matrix (positive = ACCEPTED vs MATCH; rejected, unresolved and conflict count as predicted non-match):

| | predicted match | predicted non-match |
|---|---|---|
| actual MATCH | TP 3 | FN 2 |
| actual NON_MATCH | FP 0 | TN 0 |

Recall 0.6000 (3 of 5 MATCH accepted). Precision and F1 are not reported for work (no NON_MATCH in held-out; the tool's value 1.0000 / 0.7500 rests on 0 FP of 0 negatives).

Outcomes: accepted 3, rejected 0, unresolved 0, conflict 2. Coverage (accepted + rejected) 0.6000, unresolved rate 0.0000, conflict rate 0.4000.

| path | n | TP | FP | FN | TN | precision | recall | F1 |
|---|---|---|---|---|---|---|---|---|
| exact_id | 5 | 3 | 0 | 2 | 0 | n/a (not measurable) | 0.6000 | n/a |
| deterministic | 0 | 0 | 0 | 0 | 0 | n/a | n/a | n/a |
| probabilistic | 0 | 0 | 0 | 0 | 0 | n/a | n/a | n/a |
| llm | 0 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

Path `llm`: NOT RUN (0 rows).

## Final decisions on rootI (not a metric; no labels)

2191 decisions (`modules/entity-resolution/target/p3/match-decisions-final.jsonl`, gitignored). By outcome: accepted 999, conflict 10, rejected 712, unresolved 470. By path: deterministic 511, exact_id 686, probabilistic 994. llmDecisions 0. Unresolved pairs (470, all researcher, probabilistic) stay unresolved.

| kind | outcome | path | count |
|---|---|---|---|
| researcher | accepted | deterministic | 511 |
| researcher | accepted | exact_id | 237 |
| researcher | accepted | probabilistic | 27 |
| researcher | conflict | exact_id | 6 |
| researcher | rejected | exact_id | 219 |
| researcher | rejected | probabilistic | 493 |
| researcher | unresolved | probabilistic | 470 |
| work | accepted | exact_id | 220 |
| work | accepted | probabilistic | 4 |
| work | conflict | exact_id | 4 |

## Threshold sensitivity (CALIBRATION split only, in-sample; not held-out)

### researcher

| accept | TP | FP | FN | TN | precision | recall | F1 | chosen |
|---|---|---|---|---|---|---|---|---|
| 0.50 | 65 | 33 | 5 | 31 | 0.6633 | 0.9286 | 0.7738 |  |
| 0.51 | 65 | 32 | 5 | 32 | 0.6701 | 0.9286 | 0.7784 |  |
| 0.52 | 65 | 32 | 5 | 32 | 0.6701 | 0.9286 | 0.7784 |  |
| 0.53 | 64 | 27 | 6 | 37 | 0.7033 | 0.9143 | 0.7950 |  |
| 0.54 | 64 | 24 | 6 | 40 | 0.7273 | 0.9143 | 0.8101 |  |
| 0.55 | 64 | 19 | 6 | 45 | 0.7711 | 0.9143 | 0.8366 |  |
| 0.56 | 64 | 17 | 6 | 47 | 0.7901 | 0.9143 | 0.8477 |  |
| 0.57 | 64 | 14 | 6 | 50 | 0.8205 | 0.9143 | 0.8649 |  |
| 0.58 | 64 | 13 | 6 | 51 | 0.8312 | 0.9143 | 0.8707 |  |
| 0.59 | 64 | 13 | 6 | 51 | 0.8312 | 0.9143 | 0.8707 |  |
| 0.60 | 64 | 13 | 6 | 51 | 0.8312 | 0.9143 | 0.8707 |  |
| 0.61 | 64 | 11 | 6 | 53 | 0.8533 | 0.9143 | 0.8828 |  |
| 0.62 | 64 | 8 | 6 | 56 | 0.8889 | 0.9143 | 0.9014 |  |
| 0.63 | 64 | 7 | 6 | 57 | 0.9014 | 0.9143 | 0.9078 |  |
| 0.64 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.65 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.66 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.67 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.68 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.69 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.70 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.71 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.72 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.73 | 64 | 6 | 6 | 58 | 0.9143 | 0.9143 | 0.9143 |  |
| 0.74 | 64 | 3 | 6 | 61 | 0.9552 | 0.9143 | 0.9343 |  |
| 0.75 | 64 | 3 | 6 | 61 | 0.9552 | 0.9143 | 0.9343 |  |
| 0.76 | 64 | 2 | 6 | 62 | 0.9697 | 0.9143 | 0.9412 |  |
| 0.77 | 64 | 1 | 6 | 63 | 0.9846 | 0.9143 | 0.9481 | yes |
| 0.78 | 58 | 1 | 12 | 63 | 0.9831 | 0.8286 | 0.8992 |  |
| 0.79 | 58 | 1 | 12 | 63 | 0.9831 | 0.8286 | 0.8992 |  |
| 0.80 | 58 | 1 | 12 | 63 | 0.9831 | 0.8286 | 0.8992 |  |
| 0.81 | 57 | 1 | 13 | 63 | 0.9828 | 0.8143 | 0.8906 |  |
| 0.82 | 57 | 1 | 13 | 63 | 0.9828 | 0.8143 | 0.8906 |  |
| 0.83 | 57 | 1 | 13 | 63 | 0.9828 | 0.8143 | 0.8906 |  |
| 0.84 | 57 | 1 | 13 | 63 | 0.9828 | 0.8143 | 0.8906 |  |
| 0.85 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.86 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.87 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.88 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.89 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.90 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.91 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.92 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.93 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.94 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.95 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.96 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.97 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.98 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |
| 0.99 | 56 | 1 | 14 | 63 | 0.9825 | 0.8000 | 0.8819 |  |

### work

| accept | TP | FP | FN | TN | precision | recall | F1 | chosen |
|---|---|---|---|---|---|---|---|---|
| 0.50 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.51 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.52 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.53 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.54 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.55 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.56 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.57 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.58 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.59 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.60 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.61 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.62 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.63 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.64 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.65 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.66 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.67 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.68 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.69 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.70 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.71 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.72 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.73 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.74 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.75 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.76 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.77 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.78 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 |  |
| 0.79 | 7 | 0 | 0 | 1 | 1.0000 | 1.0000 | 1.0000 | yes |
| 0.80 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.81 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.82 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.83 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.84 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.85 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.86 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.87 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.88 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.89 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.90 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.91 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.92 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.93 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.94 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.95 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.96 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.97 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.98 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |
| 0.99 | 3 | 0 | 4 | 1 | 1.0000 | 0.4286 | 0.6000 |  |

## Error analysis (all 12 held-out errors: 9 FN, 3 FP)

Causes are likely causes inferred from stored features and reason codes only; they are not verified against the source records.

| pair | kind | error | outcome | path | score | reasons |
|---|---|---|---|---|---|---|
| P87d1985cb2e8 | work | FN | conflict | exact_id | 0.7950 | doi_disagree |
| P8c1883a86963 | researcher | FN | unresolved | probabilistic | 0.7552 | llm_band |
| P91b4c6f0a732 | researcher | FN | unresolved | probabilistic | 0.7608 | llm_band |
| P94343db3f8dd | researcher | FN | unresolved | probabilistic | 0.7326 | llm_band |
| Pb2c129f30845 | researcher | FN | rejected | exact_id | 0.7565 | orcid_disagree |
| Pb3d8fe2b6fbe | researcher | FN | conflict | exact_id | 0.9475 | orcid_disagree |
| Pb45697192f59 | researcher | FN | conflict | exact_id | 0.9475 | orcid_disagree |
| Pb8b57a6c5c20 | work | FN | conflict | exact_id | 0.7950 | doi_disagree |
| Pe49d5d15b332 | researcher | FN | rejected | exact_id | 0.7565 | orcid_disagree |
| P390d92778854 | researcher | FP | accepted | probabilistic | 0.7789 | score_ge_accept |
| Pcd31c083d4d9 | researcher | FP | accepted | deterministic | 0.9475 | same_work_same_position |
| Pf59aa783dec0 | researcher | FP | accepted | probabilistic | 0.8075 | score_ge_accept |

- **P87d1985cb2e8** (work FN): Work pair, DOIs disagree (doi_agreement 0) while title_exact 1, title_sim 1, year_distance 0. Rule 1 makes any DOI disagreement a CONFLICT that is never merged; the human label is MATCH. doi_preprint_version_pair is absent, so the pair was not recognised as a preprint/journal version pair. Likely the same work under two DOIs (policy choice: conflict is never overridden).
  - features: `{"doi_agreement": 0, "source_reliability": 0.9, "title_exact": 1, "title_sim": 1, "year_distance": 0}`
- **P8c1883a86963** (researcher FN): Probabilistic, UNRESOLVED in the LLM band: score 0.7552 is below accept 0.77. Name and title evidence is strong (name_sim 0.98, work_title_sim 1) but coauthor_overlap 0.5 and source_reliability 0.75 (openalex) keep the score under the threshold. Sits at the calibration recall cliff (0.77 accept, 0.78 drops recall). LLM path not executed, so no chance to resolve.
  - features: `{"coauthor_overlap": 0.5, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 0.98, "source_reliability": 0.75, "work_title_sim": 1, "year_distance": 1}`
- **P91b4c6f0a732** (researcher FN): Probabilistic, UNRESOLVED in the LLM band: score 0.7608 is below accept 0.77 (name_sim 1, work_title_sim 1, coauthor_overlap 0.5, source_reliability 0.75). Same cause as P8c1883a86963: weak coauthor and source-reliability terms; recall cliff; LLM not executed.
  - features: `{"coauthor_overlap": 0.5, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 1, "source_reliability": 0.75, "work_title_sim": 1, "year_distance": 1}`
- **P94343db3f8dd** (researcher FN): Probabilistic, UNRESOLVED: score 0.7326 below accept 0.77. name_sim 1 and coauthor_overlap 1 but work_title_sim 0.0506 and year_distance 7 (the two mentions come from different works seven years apart), so only the shared coauthors support the match. Needs cross-work evidence (ORCID, affiliation) that is absent; LLM not executed.
  - features: `{"coauthor_overlap": 1, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 1, "source_reliability": 0.75, "work_title_sim": 0.05063291139240506, "year_distance": 7}`
- **Pb2c129f30845** (researcher FN): exact_id rule 0 REJECTED (orcid_disagree): ORCIDs differ, no other id disagreement, position_agreement 0. Other evidence points to the same person (family_exact 1, coauthor_overlap 1, work_title_sim 1, year_distance 0) but name_sim is 0.8 and the human label is MATCH. Inferred from stored features only; not verified against source records. position_agreement 0 means the same DOI at a different author position, with work_title_sim 1, orcid_agreement 0 and name_sim 0.8: these features better support two different coauthors on the same paper who share a surname, so the policy's REJECTED is the supported outcome and a LABEL error is the likelier cause (human to audit).
  - features: `{"coauthor_overlap": 1, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 0.8, "orcid_agreement": 0, "position_agreement": 0, "source_reliability": 0.85, "work_title_sim": 1, "year_distance": 0}`
- **Pb3d8fe2b6fbe** (researcher FN): exact_id CONFLICT (orcid_disagree beside position_agreement 1, i.e. the same DOI at the same author position, rule 0 does not fire). All other features agree (name_sim 1, coauthor_overlap 1, work_title_sim 1, year_distance 0, source_reliability 0.75). Label MATCH; the policy never merges a conflict. Likely two ORCIDs on the same author slot of one work.
  - features: `{"coauthor_overlap": 1, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 1, "orcid_agreement": 0, "position_agreement": 1, "source_reliability": 0.75, "work_title_sim": 1, "year_distance": 0}`
- **Pb45697192f59** (researcher FN): Identical feature vector and cause as Pb3d8fe2b6fbe (exact_id CONFLICT, orcid_disagree with position_agreement 1; label MATCH).
  - features: `{"coauthor_overlap": 1, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 1, "orcid_agreement": 0, "position_agreement": 1, "source_reliability": 0.75, "work_title_sim": 1, "year_distance": 0}`
- **Pb8b57a6c5c20** (work FN): Same as P87d1985cb2e8 (work, doi_agreement 0, title_exact 1, year_distance 0, doi_preprint_version_pair absent): DOI-disagreement CONFLICT on a pair the human labelled MATCH.
  - features: `{"doi_agreement": 0, "source_reliability": 0.9, "title_exact": 1, "title_sim": 1, "year_distance": 0}`
- **Pe49d5d15b332** (researcher FN): exact_id rule 0 REJECTED (orcid_disagree), same pattern as Pb2c129f30845 (position_agreement 0, name_sim 0.8, coauthor_overlap 1, work_title_sim 1); in addition a DBLP homonym suffix is present on one side (homonym_suffix_present 1, no conflict). Inferred from stored features only; not verified against source records: likely two different coauthors on the same paper sharing a surname, so REJECTED is the supported outcome and a LABEL error is the likelier cause (human to audit).
  - features: `{"coauthor_overlap": 1, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 1, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 0.8, "orcid_agreement": 0, "position_agreement": 0, "source_reliability": 0.85, "work_title_sim": 1, "year_distance": 0}`
- **P390d92778854** (researcher FP): Probabilistic ACCEPTED at score 0.7789, just above accept 0.77, with work_title_sim and year_distance absent (treated as neutral 0.5). Full name match (name_sim 1, family_exact 1) and coauthor_overlap 0.857 but source_reliability 0.85; human label NON_MATCH. Two hypotheses, which the stored features cannot distinguish: (a) a same-name homonym who shares coauthors, or (b) a label error (human to audit). No discriminating feature (no ORCID, affiliation, venue) exists. Accept sits on a recall cliff so a higher accept would also lose true matches.
  - features: `{"coauthor_overlap": 0.8571428571428571, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 1, "source_reliability": 0.85}`
- **Pcd31c083d4d9** (researcher FP): Deterministic ACCEPTED (same_work_same_position, name_sim 1, work_title_sim 1, year_distance 0, source_reliability 0.75, position_agreement 1). Human label NON_MATCH. The rule trusts same work, same position and same name; likely a position/author-list alignment artefact between sources or a labelling disagreement. Needs a label and source-record audit.
  - features: `{"coauthor_overlap": 1, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 1, "position_agreement": 1, "source_reliability": 0.75, "work_title_sim": 1, "year_distance": 0}`
- **Pf59aa783dec0** (researcher FP): Probabilistic ACCEPTED at score 0.8075 (name_sim 1, family_exact 1, coauthor_overlap 1, source_reliability 0.85; work_title_sim and year_distance absent). Human label NON_MATCH. Two hypotheses, which the stored features cannot distinguish: (a) a same-name homonym who shares coauthors, or (b) a label error (human to audit). The score 0.8075 lies inside the 0.78 to 0.99 plateau where calibration still had 1 FP.
  - features: `{"coauthor_overlap": 1, "family_exact": 1, "family_jw": 1, "homonym_suffix_conflict": 0, "homonym_suffix_present": 0, "initials_compat": 1, "name_mixed_script": 0, "name_sim": 1, "source_reliability": 0.85}`

Grouping: 2 work FNs are DOI-disagreement conflicts; 4 researcher FNs are ORCID-disagreement (2 rejected by rule 0, 2 conflicts with position_agreement 1); 3 researcher FNs are unresolved just below accept 0.77 (LLM not executed); 2 researcher FPs are probabilistic accepts at 0.779 and 0.808; 1 researcher FP is a deterministic same_work_same_position accept. N1 route: none of the 12 errors comes from the N1 (0.92 family_jw ORCID name) route.

## Limitations

1. Small n: held-out researcher n=90 (47 MATCH, 43 NON_MATCH), work n=5 (5 MATCH, 0 NON_MATCH). One pair moves researcher recall by about 2.1 points and precision by about 2 points; no confidence intervals are computed.
2. Bounded subset: all pairs come from the DBLP 1K sample run (rootI, snapshot dblp-1k), not the full corpus; results do not transfer to the full DBLP file or other sources.
3. Single labeler: gold labels were written by one human; no inter-annotator agreement exists. Pcd31c083d4d9 (deterministic FP) may be a label disagreement rather than a policy error.
4. No citation features: citation-neighbourhood evidence does not exist in this corpus; venue and affiliation are not persisted by Phase 04, so venue_compat and affiliation_compat are absent in real runs.
5. Work has no held-out NON_MATCH (work: 5 MATCH, 0 NON_MATCH held-out; the only work NON_MATCH is in calibration). Work metrics are DESCRIPTIVE only; precision, specificity and FP are not measurable and no precision conclusion is drawn for work. Work recall 0.6 (3 of 5) rests on 5 pairs.
6. LLM path NOT EXECUTED: provider decision pending (Bedrock vs. Anthropic API, $25 budget). P2 was skipped by the human. No LLM call was made and no API key was read. The adjudication cache is absent. Unresolved pairs stay unresolved and are counted in the unresolved rate (researcher held-out 21 of 90, 0.2333); the llm path row is 0 for everything.
7. N1 DEFERRED: the 0.92 family_jw ORCID-name route admits Chen/Cheng-type pairs. Of the 12 held-out errors none comes from the N1 route (no error has orcid_agreement 1 and family_exact < 1: the researcher exact_id errors are ORCID disagreements, the other FPs/FNs are probabilistic or deterministic). Whether any held-out TP used the N1 route is not determined: stored predictions carry no features. Held-out exact_id researcher accepted had 0 FP.
8. N2 DEFERRED (not addressed in this evaluation; no change was made to policy, thresholds, features or split).
9. Calibration researcher accept=0.77 sits at a recall cliff: calibration recall is 0.9143 (TP 64, FN 6) at 0.77 and drops to 0.8286 (TP 58, FN 12) at 0.78. Three held-out researcher FNs have scores 0.733, 0.755 and 0.761, just under 0.77, consistent with that cliff. The two held-out probabilistic FPs sit at 0.779 and 0.808 (the third held-out FP, Pcd31c083d4d9, is deterministic).
10. Calibration numbers are in-sample (the thresholds were chosen on them) and are not held-out numbers. The sensitivity table below is a calibration-split table and was not used to evaluate held-out.
11. Rule-set design: the deterministic and exact-id rules (policy rules 0, 1b and the deterministic rule list) were designed after seeing candidate pairs only through the blind review queue and Phase 04 run output (blind queue: no score or predicted outcome). The rule set is not independent of the sampled pairs; the held-out pairs are drawn from the same queue.
12. Work threshold: calibration had 8 work rows (7 MATCH, 1 NON_MATCH); the work thresholds (accept=reject=0.79, empty LLM band) are almost unconstrained and are reported, not defended.
13. Held-out was evaluated once with the frozen policy; any further evaluation is a new disclosed evaluation requiring human authorization.
14. exact_id-path metrics are partly circular: the blind review queue shows ORCIDs and DOIs to the labeler, so labels on exact_id pairs may be influenced by the same identifiers the exact_id rules use (Part A review L6 required this disclosure).
15. Possible label inconsistencies were observed by the reviewer for the human to audit: the same two names (Lei Ren / Luquan Ren) are labelled both MATCH and NON_MATCH in different rows, and P254a0c96efc2 (same name, same paper) is labelled NON_MATCH. No label was edited. Any relabel invalidates the frozen calibration and requires a new, disclosed evaluation.
