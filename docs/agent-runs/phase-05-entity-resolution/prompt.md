# Phase 05 — Entity resolution (work order)

Phase folder: `docs/agent-runs/phase-05-entity-resolution/`
Spec sections: "Phase 5: implement entity resolution" and "Entity linkage policy". Instructor-approved deviation: a gold set of at least 170 human-reviewed pairs, stratified 60/40 into 102 calibration and 68 held-out pairs, both containing positives and hard negatives. Thresholds are tuned only on calibration; precision, recall, F1 and the confusion matrix are reported on held-out.

This phase runs in **two parts** with a human labeling gate between them:
- **Part A** builds the subsystem and the blind review queue, then STOPS.
- **Part B** runs after the human has labeled the queue. It can run in the same session or a fresh one: "Execute Part B of …/prompt.md".

Ownership follows `docs/agents.md` section 1 and 1.1.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A0 | build-engineer | `build.sbt`, `project/**` (not `Versions.scala`) | only if a precondition fails |
| A1 | domain-engineer | `modules/core/**` except `src/test/**` | always |
| A2 | test-engineer | `modules/core/src/test/**` | always (after A1) |
| B1 | entity-resolution-engineer | `modules/entity-resolution/**` except `src/test/**` | always |
| B2 | hadoop-engineer | `modules/hadoop/**` except `src/test/**` | always |
| C | test-engineer | `modules/entity-resolution/src/test/**`, `modules/hadoop/src/test/**` | always |
| D | code-reviewer | nothing | always |
| Q | entity-resolution-engineer | `modules/entity-resolution/gold/**` | last step of Part A |
| P1–P3 | see Part B | | Part B |

---

## 0. Rules for the orchestrator (both parts)

1. You route; you do not write code, tests, contracts, prompts or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. The canonical test command is exactly `sbt check; echo "exit=$?" > .claude/.last-test-run`.
4. **Speed rule.** While iterating, agents use targeted commands (`sbt "entityResolution/testOnly <Suite>"`, `sbt "hadoop/testOnly *ItSuite"`, `sbt "<project>/Test/compile"`). They run the full canonical command once, at the end of each work order. Integration suites run LocalJobRunner once per suite (`beforeAll`, 1 reducer). No test minimum or gate is reduced by this.
5. **One sbt user at a time.** Do not run two source-changing agents in parallel; it causes Stop-hook loops, which hit Phase 04.
6. After each work order, append a `## Progress checkpoint` to `agent-report.md`. Keep reports concise and never paste whole files.
7. **No AWS calls. No downloads.** No LLM API call by any agent: real LLM adjudication is run by the human in Part B (P2). Never read, print or log any API key.
8. Do not process the full 5.4 GB DBLP file. The input is the committed Phase 04 1K pipeline (real data, local).
9. **The gold labels are human-authored.** No agent creates, edits, fills in or "suggests" labels in any labels file. The review queue must be **blind**: it shows no model score, no feature values and no predicted outcome.
10. **Leakage rule.** Thresholds and any tunable parameter may be fit only on calibration rows. Held-out rows are read exactly once, by the final evaluation, after the policy is frozen. If the policy changes after a held-out evaluation, that is a new, disclosed evaluation, not a silent rerun.

## 1. Preconditions (orchestrator, read-only)

Record each item under `## Preconditions` in `agent-report.md`:

- `git status --short` is clean, and HEAD descends from the commit in `docs/agent-runs/phase-04-hadoop-mapreduce/commit.txt`.
- The canonical test command exits 0. Record the baseline count (Phase 04 ended at 638).
- Read Phase 04's `human-gate.md` (sections 4 and 6) and the pass 2 section of its `review-report.md`. List the items Phase 05 must close:
  - F7 (move `PersonAlias`, `DuplicateRecord`, `LargeTeam`, `OversizeTeam` into core);
  - N1–N4;
  - the Phase 04 decision that the mapping from mention-keyed `Mention*` records to researcher-keyed records is defined in this phase.
- In `CLAUDE.md` module boundaries and `build.sbt`, check and record:
  - (a) `entityResolution` depends on `core`;
  - (b) whether `hadoop` may depend on `entityResolution`, and whether it does.

  If CLAUDE.md forbids the hadoop → entityResolution edge, **stop and ask the human**. The fallback design is that B2's MapReduce jobs call ER functions through a core-defined interface, but that is the human's choice. If the edge is allowed but missing, run A0.
- Confirm the real 1K inputs from Phase 04 exist locally: `datasets/raw/dblp/derived/dblp_selection_1000.xml`, `dblp.dtd`, and the Crossref/OpenAlex/ORCID caches.

## 2. A0 — build-engineer (conditional)

Make only the minimal `dependsOn` edges found missing in section 1. No new libraries, versions or flags. Run the canonical command and report.

## 3. A1 — domain-engineer (contracts)

Inspect first, then add only what is missing. Each item is a versioned, immutable record with validation. Never change a frozen wire format in place: bump the version. Give a verbatim JSON example of each new record in the report.

1. **F7.** Move `PersonAlias`, `DuplicateRecord`, `LargeTeam` and `OversizeTeam` from `modules/hadoop` into core, with validation (`PersonAlias.aliasKey` valid, names non-empty). Hadoop-side deletion of the old definitions happens in B2 (hadoop-engineer owns those files). Report the exact symbols B2 must switch to.
2. **`MatchCandidate`** v1:
   - left record ID and right record ID, in canonical order;
   - entity kind `researcher | work`;
   - block key and block-key type (primary or fallback);
   - raw feature map (name → Double);
   - feature-set version;
   - provenance.

   No score and no decision.
3. **`MatchDecision`.** Compare the Phase 03 shape with spec "Entity linkage policy". If any of these are missing, add a v2 with them: rules fired, match path (`exact_id | deterministic | probabilistic | llm`), model/prompt version (optional), confidence, outcome (ACCEPTED/REJECTED/UNRESOLVED/CONFLICT), reason codes, policy version, timestamp field (a contract-defined value, not wall-clock inside jobs), run ID.
4. **`AdjudicationRecord`** v1:
   - candidate IDs, which must equal the input candidate's IDs;
   - cited input fields;
   - outcome `accept | reject | abstain`;
   - confidence in [0,1];
   - reason codes;
   - prompt version, model ID, policy version;
   - input hash.

   Validation rejects any ID not present in the candidate.
5. **Mention → researcher mapping contract.** A `ResolvedMention` (or equivalent) record links a `mention:` ID to a researcher ID, with outcome, confidence and policy version. Phase 06 Spark will use it to turn `MentionPairCandidate` into researcher-keyed edges. Unresolved mentions keep a stable, versioned ScholarChain identity and are never force-merged.

Run the canonical command and report.

## 4. A2 — test-engineer (core contract tests)

Paste A1's report. Write round-trip, frozen-wire and validation-rejection tests for every new or changed record. Include: an adjudication with a fabricated ID is rejected; abstain is valid; a confidence outside [0,1] is rejected; a reversed candidate order is rejected. Run the canonical command. Report exact counts.

## 5. B1 — entity-resolution-engineer (the subsystem)

Paste the Preconditions and the A1 report. Everything goes in `modules/entity-resolution/**` (not `src/test/**`), with a module document `modules/entity-resolution/ER-CONTRACTS.md`.

1. **Normalization (pure functions).**
   - Names: Unicode NFKC, punctuation, whitespace, initials, name order ("Last, First"), DBLP homonym suffixes. A suffix such as `0001` is identity evidence; strip it only for comparison features and record that it was present.
   - Titles: markup and whitespace. Reuse core's `Titles.titleKey`.
   - Identifiers: always core's DOI/arXiv/ORCID/OpenAlex normalizers. No second rule.
   - Document how NFC/NFD and confusable characters are handled (Phase 04 gaps 1 and 2).
2. **Blocking-key functions (pure).**
   - Researcher: primary key = normalized family name plus first initial; fallbacks add a year bucket or coauthor context.
   - Work: primary key = title-token key plus year bucket; fallback adds venue.
   - Candidates are cross-source only (DBLP against an external source), plus DBLP ↔ DBLP person-alias evidence where useful. Document this.
3. **Feature functions (pure, versioned feature set):**
   - researcher-name similarity (a documented string metric, implemented in-module, no new library);
   - initials compatibility;
   - author-position agreement on DOI-linked works;
   - coauthor overlap;
   - affiliation compatibility (when present);
   - ORCID or external-ID agreement or disagreement;
   - title similarity;
   - year distance;
   - venue compatibility;
   - source reliability.

   Citation-neighborhood evidence is not available in this corpus. State that, rather than faking a feature.
4. **Scoring policy `er-v1`:**
   - a documented scoring function over the features;
   - **separate** accept, reject and LLM-band thresholds from config (`scholarchain.er.*` through core's `ModuleConfig`, with defaults in an ER-owned versioned resource);
   - initial placeholder thresholds, labeled as such, which Part B replaces by calibration-only tuning.

   The match path is recorded on every decision.
5. **Conflict detection.** Authoritative identifiers that disagree (for example two different ORCIDs, or an exact-ID CONFLICT from Phase 04 HJ3) give outcome CONFLICT. **Never a silent merge.**
6. **LLM adjudication (code only; no live call in Part A):**
   - A strict JSON schema with outcomes accept/reject/abstain, candidate IDs, cited fields, confidence and reason codes. Every model output is validated; anything malformed or carrying a fabricated ID is treated as abstain, with a reason code. Malformed output gets a bounded retry policy.
   - The versioned prompt template lives in ER resources (for example `src/main/resources/er-prompts/er-adjudication-v1.txt`). Retrieved record text is treated as data, never as instructions.
   - `LlmClient` is a trait with:
     - (a) a fake for tests;
     - (b) an Anthropic Messages API client using the JDK `HttpClient` (no new library), with model ID from config and the API key read from env var `SCHOLARCHAIN_ANTHROPIC_API_KEY` only. Never use `ANTHROPIC_API_KEY`: that name would switch Claude Code itself to paid API billing. The key is never logged or echoed.
     - (c) Provider config value `bedrock` exists and fails with a clear "not implemented in Phase 05" message.
   - A cache keyed by input hash + prompt version + model ID + policy version, as a JSONL file under a gitignored path. A cache hit makes no call.
   - Bounded: config `scholarchain.er.llm.max-calls` (default 60) and an estimated-cost guard. Only candidates in the configured unresolved band are eligible.
   - A runnable tool `AdjudicateBand` that the **human** runs in Part B (P2). It prints its plan (count, model, estimated cost) and requires `--confirm` to make any call.
7. **Tools (runnable mains, documented in ER-CONTRACTS.md):**
   - `BuildReviewQueue`: from the real candidate output, sample about 200 pairs, stratified by entity kind (target about 150 researcher and 50 work) and by score band. Oversample near-threshold and same-surname pairs so the set includes hard negatives. Fixed seed. Write the blind CSV (rule 0.9).
   - `SplitGold`: reads the human labels and makes a deterministic seeded split, stratified by entity kind × label. With 170 definitive labels this gives exactly 102/68; with more, keep 60/40. Writes the split file. UNSURE rows are excluded and counted.
   - `Calibrate`: reads **calibration rows only**. A threshold grid search with the objective documented (maximize F1 on calibration; ties go to higher precision). Writes the frozen policy record and a threshold-sensitivity table.
   - `Evaluate`: reads the held-out rows once and writes JSON and Markdown results. These contain precision, recall, F1, confusion matrix, coverage and unresolved rate, **separately for researcher and work**, plus metrics by match path and an error list (all FP and FN, at least 10 of each when available).
   - `FinalDecisions`: merges the policy decisions with the adjudication cache, as follows. Abstain stays UNRESOLVED. CONFLICT is never overridden by the LLM.
8. **Data layout:** `modules/entity-resolution/gold/` holds the queue, the labels and the split. `modules/entity-resolution/evaluation/` holds the results. No raw ORCID personal data is committed beyond what the blind queue needs to judge identity: names, coauthors, titles, year, venue, public IDs, affiliation strings.

Run the canonical command. Report the API of each pure function (B2 and C test against it) and the config keys.

## 6. B2 — hadoop-engineer (MapReduce for blocking and decisions, plus Phase 04 carry-overs)

Paste the A1 and B1 reports.

1. **F7.** Use the core records and remove the old definitions from `Model.scala`. Output must not change; prove it by comparing a fresh 1K run with Phase 04 rootC for the unchanged tables.
2. **HJ6 BlockingCandidateJob (spec J05 + J06).**
   - A block-size counting pass with a **combiner**.
   - Then a candidate pass that routes keys over the config `maxBlockSize` to their fallback key, using the oversize-key list as a side file or distributed-cache file. A block still oversize after its fallback is skipped and listed in `oversize-blocks`, with counter OVERSIZE_BLOCK.
   - No reducer group ever produces candidates from more than `maxBlockSize` members.
   - Calls B1's blocking-key and feature functions only. Outputs `MatchCandidate`.
3. **HJ7 MatchDecisionJob.** Applies B1's `er-v1` policy function and writes `match-decisions`, split by outcome. Exact-ID evidence from HJ3 feeds the `exact_id` path and CONFLICT. No LLM calls in MapReduce.
4. **Phase 04 notes:**
   - N1: provenance growth. Keep the tree input and the winner's own envelope, plus a merged-file count or digest. If that needs a contract change, report `BLOCKED-ON domain-engineer`.
   - N2: the recorded `export-reducers` value must equal the value the job uses; refuse invalid values.
   - N3: document the residual reader edge cases in JOB-CONTRACTS.md.
   - N4: compare `retrievedAt` as an instant, with the JSON text as tie-break.
5. Update JOB-CONTRACTS.md. Integration tests: at least 8 new `*ItSuite` tests. They cover: oversize fallback and skip; that no group exceeds the cap; the combiner present and the counts correct; the decision outcomes; CONFLICT never accepted; determinism of HJ6/HJ7; and F7 output equality.
6. **Real run.** Run the 1K pipeline into two new roots, rootE and rootF, with `--code-version "$(git rev-parse --short HEAD)+uncommitted"`. Then compare them (ALL TABLES EQUAL) and paste:
   - candidate counts by entity kind;
   - the largest block;
   - OVERSIZE_BLOCK;
   - decision counts by outcome and path;
   - counters.json.

Run the canonical command once at the end.

## 7. C — test-engineer (unit and adversarial; at least 40 new)

Paste the B1 and B2 reports.

**Entity resolution, at least 30, in `modules/entity-resolution/src/test/**`:**
- Normalization: NFKC; "Last, First"; initials; homonym suffix; confusables, with the documented behavior.
- Each feature on hand-computed values.
- Blocking keys and their fallbacks.
- Policy: separate thresholds; band routing; CONFLICT on disagreeing ORCIDs; CONFLICT never overridden.
- Adjudication: a valid accept; a valid abstain; malformed JSON becomes abstain; a fabricated ID becomes abstain; out-of-range confidence; the cache key changes with prompt, model or policy version; a cache hit makes no call (fake client counts calls); max-calls enforced; no call without `--confirm`; the key is never in a log line or exception message.
- `SplitGold`: deterministic for a seed; stratified; exactly 102/68 for 170; UNSURE excluded.
- `Calibrate` reads no held-out row. Assert this by poisoning held-out rows in a fixture.
- `Evaluate` metrics on a hand-computed confusion matrix.

**Hadoop, at least 10, in `modules/hadoop/src/test/**`:** blocking mapper and reducer behavior through the in-process harness, oversize routing, and the decision mapper.

Leave failing tests that expose defects in place, and route them. Run the canonical command. Report exact counts.

## 8. D — code-reviewer (read-only)

Review the full diff in this order:
1. Over-merging and silent merges, especially CONFLICT handling.
2. **Leakage:** gold labels or held-out rows reachable from any tuning or scoring path. Check the "blind" property of the queue.
3. LLM safety: fabricated IDs, abstain path, key handling, call bounds, the cache key, prompt injection through record text.
4. Contract drift and the F7 move.
5. Ownership, including no agent-written labels and no prompt files under `prompts/**`.
6. Distribution: combiner correctness, the oversize policy, determinism.
7. N1–N4.
8. Tests that would pass even if the behavior were broken.
9. Claims not backed by output.

Give severity, file:line, a failing input and the owner for each finding. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 9. Repair loop

Group findings by owner as `repair-NN.md`. Each owner fixes and runs the canonical command, test-engineer reruns the affected tests, and code-reviewer rereads. Maximum 2 rounds, then escalate to the human.

## 10. Q — entity-resolution-engineer (blind review queue; last step of Part A)

After the review approves, run `BuildReviewQueue` on the final rootE output. Write `modules/entity-resolution/gold/review-queue-v1.csv`. Columns:

- `pair_id`, `entity_kind`;
- for left and right in turn: `left_source`, `left_id`, `left_name_or_title`, `left_coauthors_or_authors` (at most 8), `left_work_title`, `left_year`, `left_venue`, `left_ids` (DOI/ORCID/OpenAlex), `left_affiliation`, and the same `right_*` columns;
- `label` (empty), `notes` (empty).

Also write `gold/LABELING-GUIDE.md`:
- MATCH = same real-world person or work;
- NON_MATCH = different;
- UNSURE = cannot tell from the evidence shown;
- a few worked examples using synthetic names only.

Report the row counts by entity kind and the seed. Then **STOP. Part A ends here.**

The orchestrator writes a final checkpoint: "Part A complete; waiting for human labels in `gold/gold-labels-v1.csv`".

---

# Part B — after human labeling

Precondition: the human has saved `modules/entity-resolution/gold/gold-labels-v1.csv` (a copy of the queue with `label` filled in). The human wrote this file outside any agent. Agents only read it.

## P1 — entity-resolution-engineer (split and calibrate)

1. Validate the labels file:
   - at least 170 definitive labels (MATCH or NON_MATCH);
   - both classes present for each entity kind;
   - every `pair_id` exists in the queue;
   - no other column changed (compare against the queue).

   If any check fails, stop and report to the human.
2. Run `SplitGold`. Commit-ready outputs: the split file, with counts by kind × label × split.
3. Run `Calibrate` on calibration rows only. Write the frozen policy `er-v1` (thresholds, the objective, the grid, the calibration metrics) and the sensitivity table. Define the LLM band from the calibration result.
4. Run `AdjudicateBand` in **plan mode only** (no `--confirm`), for the unresolved gold pairs in both splits plus the unresolved pairs of the rootE run, capped by `max-calls`. Paste the plan: count, model, estimated cost.

Run the canonical command if any source changed. Report.

## P2 — HUMAN (live LLM adjudication; no agent)

The human runs, in their own terminal, the exact command from ER-CONTRACTS.md, with `SCHOLARCHAIN_ANTHROPIC_API_KEY` exported only for that command, and with `--confirm`. The human pastes the tool's summary (calls made, cache hits, abstains, observed tokens and cost) into `agent-report.md` under "P2 (human)". If the human skips P2, the LLM path is reported as "not executed", with the reason.

## P3 — entity-resolution-engineer (evaluate and finalize)

1. Run `Evaluate` once on held-out, with the frozen policy plus the adjudication cache.
2. Run `FinalDecisions` for rootE. Write `match-decisions-final` locally, and a summary: counts by outcome and path.
3. Write `evaluation/er-v1-results.md` and `.json`. They contain:
   - the split composition;
   - P/R/F1 and the confusion matrix for researcher and for work, separately;
   - coverage and unresolved rate;
   - metrics by path (exact_id, deterministic, probabilistic, llm);
   - the threshold-sensitivity table;
   - the error analysis, with every FP and FN and its likely cause;
   - limitations: small n, the 1K bounded subset, single labeler, no citation features.

Run the canonical command if any source changed.

Then: code-reviewer **pass on Part B** (leakage, metric correctness against the stored predictions, error-list completeness), the repair loop if needed, and then STOP at the human gate.

## Gate (what the human checks)

- No silent merges: CONFLICT exists and is never overridden.
- The LLM can abstain, invalid output becomes abstain, and no fabricated IDs get through.
- Labels are human-authored, the queue was blind, and the split is seeded and stratified with 102/68 (or 60/40 if more labels).
- Thresholds were tuned on calibration only. Held-out was evaluated once, and P/R/F1 and the confusion matrix are reported separately per entity kind.
- The Hadoop blocking job has a combiner and an oversize policy, and no group exceeds the cap.
- The two real runs are equal.
- The `sbt check` total rises by at least 48 (8 integration + 40 unit), plus A2's tests.
- The reviewer verdict is APPROVE or APPROVE WITH NOTES for both Part A and Part B.

## Out of scope

Spark; storage; RAG and the `prompts/**` tree; AWS and EMR; Terraform and scripts; the full DBLP file; any agent-made LLM call; any agent-written label.