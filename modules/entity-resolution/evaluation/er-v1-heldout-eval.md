# er-v1: held-out evaluation

Policy `er-v1`, thresholds origin `calibrated-on-calibration-split-only`, code `74eee4e+repair04`.
Ledger `/Users/dhruv_mehta/Desktop/ScholarChain/modules/entity-resolution/gold/heldout-evaluations.jsonl`, split checksum `1fad6c2d47aaf68c9f8d3621a53dd935f21881da8b8a9988869edce77d2f8e34`, overrides: none.

Held-out rows: 95. Positive class: ACCEPTED vs MATCH; rejected, unresolved and conflict count as predicted non-match.

## researcher (n=90)

| TP | FP | FN | TN | precision | recall | F1 | coverage | unresolved | conflict |
|---|---|---|---|---|---|---|---|---|---|
| 40 | 3 | 7 | 40 | 0.9302 | 0.8511 | 0.8889 | 0.7444 | 0.2333 | 0.0222 |

By match path:

| path | n | TP | FP | FN | TN |
|---|---|---|---|---|---|
| exact_id | 20 | 8 | 0 | 4 | 8 |
| deterministic | 31 | 30 | 1 | 0 | 0 |
| probabilistic | 39 | 2 | 2 | 3 | 32 |
| llm | 0 | 0 | 0 | 0 | 0 |

## work (n=5)

| TP | FP | FN | TN | precision | recall | F1 | coverage | unresolved | conflict |
|---|---|---|---|---|---|---|---|---|---|
| 3 | 0 | 2 | 0 | 1.0000 | 0.6000 | 0.7500 | 0.6000 | 0.0000 | 0.4000 |

By match path:

| path | n | TP | FP | FN | TN |
|---|---|---|---|---|---|
| exact_id | 5 | 3 | 0 | 2 | 0 |
| deterministic | 0 | 0 | 0 | 0 | 0 |
| probabilistic | 0 | 0 | 0 | 0 | 0 |
| llm | 0 | 0 | 0 | 0 | 0 |

## Errors (12: every FP and FN)

| pair | kind | error | label | outcome | path | score |
|---|---|---|---|---|---|---|
| P87d1985cb2e8 | work | FN | MATCH | conflict | exact_id | 0.795 |
| P8c1883a86963 | researcher | FN | MATCH | unresolved | probabilistic | 0.755 |
| P91b4c6f0a732 | researcher | FN | MATCH | unresolved | probabilistic | 0.761 |
| P94343db3f8dd | researcher | FN | MATCH | unresolved | probabilistic | 0.733 |
| Pb2c129f30845 | researcher | FN | MATCH | rejected | exact_id | 0.757 |
| Pb3d8fe2b6fbe | researcher | FN | MATCH | conflict | exact_id | 0.948 |
| Pb45697192f59 | researcher | FN | MATCH | conflict | exact_id | 0.948 |
| Pb8b57a6c5c20 | work | FN | MATCH | conflict | exact_id | 0.795 |
| Pe49d5d15b332 | researcher | FN | MATCH | rejected | exact_id | 0.757 |
| P390d92778854 | researcher | FP | NON_MATCH | accepted | probabilistic | 0.779 |
| Pcd31c083d4d9 | researcher | FP | NON_MATCH | accepted | deterministic | 0.948 |
| Pf59aa783dec0 | researcher | FP | NON_MATCH | accepted | probabilistic | 0.808 |
