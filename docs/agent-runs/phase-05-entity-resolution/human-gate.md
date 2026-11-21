# Phase 05 — Human gate

Phase: 05, entity resolution (Parts A and B)
Date: 2026-11-20
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with the decisions, disclosures and remaining risks recorded below.

## 1. What I reviewed

- The work order `prompt.md` (written by me; saved verbatim) and the routed repair work orders `repair-01.md` to `repair-05.md`.
- `agent-report.md`: preconditions, A0, A1, A2, B1, B2, C, the real-run reports, Q, P1, P3 and the progress checkpoints.
- `review-report.md`: Part A pass 1 (REQUEST_CHANGES), pass 2 (REQUEST_CHANGES), pass 3 (APPROVE WITH NOTES); Part B pass (APPROVE WITH NOTES) and its repair reread (APPROVE WITH NOTES).
- `modules/entity-resolution/ER-CONTRACTS.md`, `modules/hadoop/JOB-CONTRACTS.md`, the blind queue and `LABELING-GUIDE.md`, the split file, the frozen policy record and `modules/entity-resolution/evaluation/er-v1-results.md` / `.json`.
- My own hands-on checks (section 6).

## 2. What was accepted

- **Core contracts (domain-engineer):** F7 records moved to `scholarchain.core.pipeline`; new `MatchCandidate` v1, `MatchDecisionV2` (v1 untouched), `AdjudicationRecord` v1 with `validateAgainst`, `ResolvedMention` (mention-to-researcher mapping for Phase 06; unresolved mentions keep a versioned ScholarChain identity and are never force-merged).
- **Entity-resolution module (entity-resolution-engineer):** normalization, blocking keys, feature set `er-features-v2`, policy `er-v1` with separate researcher and work thresholds, CONFLICT detection that the LLM can never override, LLM adjudication code (strict schema, abstain, fabricated-ID rejection, bounded retries, cache with validated keys, max-calls, `--confirm` gate, key read only from `SCHOLARCHAIN_ANTHROPIC_API_KEY`), and the tools BuildViews, BuildReviewQueue, SplitGold, Calibrate, Evaluate, FinalDecisions and AdjudicateBand.
- **Hadoop (hadoop-engineer):** HJ6 BlockingCandidateJob (block-size count with a combiner, fallback routing, a cap on every reducer group, oversize skip list) and HJ7 MatchDecisionJob (no LLM calls); Phase 04 carry-overs N1, N2 and N4 fixed, N3 documented.
- **Tests:** 638 → **1,125 passed, 0 failed** (core 640, hadoop 192, entityResolution 293). Final canonical run: marker `exit=0`, `[success]` in 237 s.
- **Real local runs** on the 1K DBLP selection plus the cached Crossref/OpenAlex/ORCID records: rootI and rootI2, code-version label `74eee4e+repair04`, ALL TABLES EQUAL on 12 tables.
- **Gold set and evaluation:** 240-row blind queue (seed 20260101; researcher 227, work 13); 237 definitive human labels; seeded stratified 60/40 split into 142 calibration and 95 held-out (3 UNSURE excluded); thresholds tuned on calibration only; held-out evaluated once.

## 3. Results (from `evaluation/er-v1-results.md`)

Frozen `er-v1`, calibration only: researcher accept 0.77, reject 0.52; work 0.79 / 0.79 (based on 7 MATCH and 1 NON_MATCH, descriptive only).

| Held-out | n | TP / FP / FN / TN | Precision | Recall | F1 |
|---|---|---|---|---|---|
| researcher | 90 | 40 / 3 / 7 / 40 | 0.930 | 0.851 | 0.889 |
| work | 5 | 3 / 0 / 2 / 0 | not measurable | 0.60 | not measurable |

Researcher coverage 0.744, unresolved rate 0.233. All 12 held-out errors are listed in the results. FinalDecisions on rootI: 2,191 decisions (999 accepted, 712 rejected, 470 unresolved, 10 conflict). The code-reviewer recomputed every held-out metric from the stored predictions and labels, and all matched.

## 4. Decisions taken during the phase

1. **hadoop → entityResolution build edge (A0):** allowed. CLAUDE.md routes boundary changes to build-engineer plus review, the graph stays acyclic, and the core-interface alternative would still need ER classes at runtime. The CLAUDE.md / docs/agents.md boundary wording is not yet updated (constitutional; deferred to a factory session).
2. **ORCID policy (repair-03, M1/H1):** a plain ORCID disagreement gives REJECTED; CONFLICT only beside strong match evidence. An ORCID match is accepted only if the names fit.
3. **Name rules (repair-04, F1/F2):** Unicode dashes are mapped to "-" and soft hyphens dropped before tokenizing; the ORCID route requires family_exact = 1 or family-name Jaro-Winkler ≥ 0.92. These floors (and the 0.85 name_sim floor and the 0.9 preprint title floor) are hand-set and were not tuned on any labels.
4. **Run identity (M4):** the phase is committed once, at the end. Real runs used the hand-chosen labels `74eee4e+repair03` and `74eee4e+repair04`, which separate the run IDs but are not content addresses. The orchestrator confirmed before each run that no source was newer than the last passing check.
5. **Queue size:** the first queue had 161 rows (hard-coded stratum caps plus a work pool of 228 candidates, 220 of them identifier matches). It was regenerated with `--researcher 227 --work 60` (command line only, no source edit) before any labeling.
6. **P2 (live LLM adjudication): not executed.** Reason: LLM provider decision pending (Bedrock vs. Anthropic API) under the $25 budget. The plan-mode run showed 470 eligible pairs and 60 planned calls at an estimated $0.13 using placeholder prices. The LLM path is tested with a fake client only.
7. **Deferred:** N1 (the 0.92 route admits short surnames one letter apart), N2 (additive block key for names such as "Ffrench Constant"), L1a (x1 keys typed primary), L1d, the HJ7 ACCEPTED counter (reports quote the Decisions counter group only), L3, L4, L7, L8, L9, and M5.
8. **Phase 06 constraint (M5):** accepted pairs must not be unioned into clusters until a cannot-link rule exists.

## 5. Gold labels and label audit

- I labeled all 240 queue rows myself in one session of about 25 minutes, outside any agent, using a local HTML display tool that shows only the blind queue columns and writes only the `label` and `notes` columns. No agent created, edited or suggested a label. A script confirmed 0 changed non-label cells.
- Composition: researcher 117 MATCH / 107 NON_MATCH; work 12 MATCH / 1 NON_MATCH; 3 UNSURE.
- After the evaluation, the reviewer flagged possible label inconsistencies (Lei Ren / Luquan Ren labeled MATCH in three rows and NON_MATCH in one; P254a0c96efc2; the three held-out false positives; pairs Pb2c129f30845, Pe49d5d15b332, P390d92778854, Pf59aa783dec0, Pcd31c083d4d9). **Decision: labels stay frozen.** Relabeling after seeing held-out results would leak evaluation information into the labels. The flagged rows are recorded here as a limitation (single labeler, fast pass, possible errors), and the reported metrics may be biased by them in either direction.

## 6. My own checks

- [x] `git status` shows only Phase 05 files; nothing under `datasets/raw/` or `target/` is tracked.
- [x] `.claude/.last-test-run` reads `exit=0`.
- [x] `compare_roots.py rootI rootI2` prints ALL TABLES EQUAL.
- [x] `review-queue-v1.csv` header has no score, feature or outcome column.
- [x] `er-v1-results.md` shows the per-kind table above and lists all 12 errors.
- [x] `grep -rn "SCHOLARCHAIN_ANTHROPIC_API_KEY" modules/entity-resolution/src/main` shows only the env-var name, never a value.

## 7. Disclosures

- Claude Code ran in accept-edits mode: file writes were automatic, and each new kind of shell command needed my approval. Remote Control was used to monitor the session from my phone.
- Subagents cannot run the canonical test command (their shell cwd resets, and the guard blocks a `cd` prefix). The orchestrator ran it from the repository root after each work order.
- The orchestrator deleted the 161-row queue file and its sidecar by exact path before regeneration, after confirming they held no labels and that byte-identical backups existed (my instruction to overwrite).
- Superseded runs, kept as history only: rootE/rootF (before repair-02), rootG/rootF2 (before repair-03), rootH/rootH2 (before repair-04).
- Exact-ID-path metrics are partly circular, because the blind queue shows ORCIDs and DOIs (review note L6).
- Venue and affiliation are not persisted by Phase 04, so those features score a neutral 0.5 on real data. Citation-neighborhood evidence is not available in this corpus.
- The oversize-block path has no real-data evidence (OVERSIZE_BLOCK 0 at cap 500); it is covered by synthetic tests only. HJ6/HJ7 are tested in-process and by the integration suites. Safety tests were not mutation-tested.
- The AdjudicateBand plan run received the split file (including held-out pair IDs) only to order its queue; no held-out label was scored there.
- Wording leftover: `er-v1-results.md` line 231 still carries an old clause for Pb2c129f30845 that the JSON dropped. Unfixed (repair rounds used up); numbers are unaffected.
- The work kind has only 13 queue rows and 1 NON_MATCH, so work precision and F1 cannot be measured on held-out.
- Outcome wire names are lowercase (accepted, rejected, unresolved, conflict).

## 8. Manual changes

None to repository source. I authored the gold labels (section 5) and the decisions in this file.

## 9. Remaining risk

Small held-out set (90 researcher pairs), single labeler, 1K bounded subset, placeholder LLM prices, uncalibrated work thresholds, and the deferred items in section 4.

Accepted commit: see `commit.txt`.