# Phase 05 — Entity resolution: agent report (Part A)

Assembled by the orchestrator. Agent reports are pasted in condensed form. Nothing is reported as run unless its output was observed.

## Preconditions

- `git status --short`: the only entry is `?? docs/agent-runs/phase-05-entity-resolution/`, which is this phase's evidence folder holding the work order. The tree is otherwise clean.
- HEAD `74eee4e` descends from Phase 04 `commit.txt` `e1578928ed928c5e36195399ed898482691e401a`. Checked with `git merge-base --is-ancestor`: DESCENDS.
- Baseline canonical command `sbt check; echo "exit=$?" > .claude/.last-test-run`:
  - `Passed: Total 114` (hadoop) and `Passed: Total 524` (core), so **638 passed and 0 failed**;
  - `[success] elapsed time: 129 s`; the marker is `exit=0`.
- Items Phase 05 must close, from Phase 04 `human-gate.md` §4 and §6 and `review-report.md` pass 2:
  - **F7.** Move `PersonAlias`, `DuplicateRecord`, `LargeTeam` and `OversizeTeam` into core. Today they are defined in `modules/hadoop/src/main/scala/scholarchain/hadoop/Model.scala` and used in Hj1DblpExtract, Hj2SourceNormalize and Hj4TeamPair.
  - **N1 (MEDIUM).** Unbounded provenance growth in merged topics and institutions (Hj5Export.scala:128, :152-158).
  - **N2 (LOW).** The recorded `export-reducers` value can differ from the value the job uses (PipelineDriver.scala:159-163 vs Hj5Export.scala:205).
  - **N3 (LOW).** Undocumented reader edge cases: an unterminated CDATA or comment, and an unterminated attribute quote.
  - **N4 (LOW).** `retrievedAt` is compared as a string, not as an instant (Hj5Export.scala:157, Hj2SourceNormalize.scala:98).
  - **Gate decision 4.4.** Define the mapping from mention-keyed `Mention*` records to researcher-keyed records in this phase.
- Module boundaries:
  - (a) `entityResolution` depends on `core`: YES (`build.sbt:105-106`). The module has no source files yet.
  - (b) `hadoop` depends only on `core` (`build.sbt:92-93`), so the hadoop → entityResolution edge is MISSING.
    - CLAUDE.md forbids only cycles and dependencies on `services`. Its line "consumes a contract defined in `core` … does not reach into that module's internals" is ambiguous on this point.
    - The orchestrator asked the human. **Human decision: allow the direct edge.** A0 therefore runs.
- Real 1K inputs are present locally:
  - `datasets/raw/dblp/derived/dblp_selection_1000.xml` (plus `.provenance.json`) and `dblp.dtd`;
  - caches: `datasets/raw/crossref/works` (101 files), `datasets/raw/openalex/works` (100), `datasets/raw/orcid/works` (100) and `orcid_ids.json`.

## A0 — build-engineer (condensed)

- STATUS: COMPLETE.
- `build.sbt`: `hadoop.dependsOn(core)` → `.dependsOn(core, entityResolution)`. This is a one-line diff, which the orchestrator confirmed with `git diff --stat`.
- Canonical run: core Passed 524, hadoop Passed 114, Failed 0; `[success] elapsed time: 129 s`; marker `exit=0`. The orchestrator confirmed that the marker (14:54) is newer than `build.sbt` (14:50).
  - The agent reports one trial `sbt check` without the marker. Its first attempt at a wrapped form of the canonical command was blocked by guard-dangerous.sh, and nothing ran.
- Cycle check: entityResolution depends on core only, so the graph is acyclic.
- Assembly: not run, because it is not part of `sbt check`.
- Risks: CLAUDE.md and `docs/agents.md` wording on module boundaries is not updated, which is a constitutional matter for factory-engineer and the human. The edge awaits code review (D).

## Progress checkpoint

A0 done (638 passed, exit=0). Next: A1, domain-engineer.

## A1 — domain-engineer (condensed)

- STATUS: COMPLETE. Files, all under `modules/core/src/main/scala/scholarchain/core/`:
  - created `Resolution.scala` and `pipeline/PipelineModel.scala`;
  - modified `Enums.scala` and `Identifiers.scala`.
- **F7 symbols for B2:** `scholarchain.core.pipeline.{PersonAlias, DuplicateRecord, LargeTeam, OversizeTeam}`.
  - They sit in a separate package so that the old `Model.scala` definitions still compile until B2 deletes them.
  - Fields, order and wire (`schemaVersion` 1) are identical to Phase 04. `sbt "hadoop/Test/compile"` succeeded with both sets present.
- **New records:**
  - `MatchCandidate` v1 (with a `build` that canonicalizes ID order).
  - `MatchDecisionV2`. The Phase 03 `MatchDecision` lacks entityKind, matchPath, rulesFired, reasonCodes, modelVersion, timestamp, runId and CONFLICT. v1's code and wire are untouched.
  - `AdjudicationRecord` v1, with `validateAgainst(record, candidate)`.
  - `ResolvedMention`, which maps a mention to a researcher.
- **Unresolved identity:** `researcher:sc1:H("scholarchain.researcher.v1", "unresolved.v1:" + mentionId)`, via `Ids.researcherIdUnresolved`. Only an `accepted` outcome may map to a shared researcher. ER must not choose identity keys that start with `unresolved.v1:`.
- New enums: `EntityKind`, `BlockKeyType`, `MatchPath`, `ResolutionOutcome`, `AdjudicationOutcome`. Wire names are lowercase.
- JSON examples of each record are in the agent's report. The agent says they came from a real `sbt core/console` run. They are not reproduced here.
- Canonical run: core Passed 524, hadoop Passed 114, Failed 0 (638, unchanged). `[success] elapsed time: 130 s`.
  - The first `sbt check` failed at `scalafmtCheck`, which the agent fixed with `sbt core/scalafmt`.
  - The agent also ran one non-canonical `sbt check` and discarded its output.
  - Orchestrator check: the marker reads `exit=0` and no source is newer than it.

Notes for the human:
- **Outcome wire names** are lowercase (`accepted`, `rejected`, `unresolved`, `conflict`). The spec wrote them in capitals. Phase 04 already accepted lowercase snake-case, so I kept it, but you can change it before B1 builds on it.
- `PersonAlias` now rejects empty `names` and whitespace in `aliasKey`. The rootC output has no `person-aliases` rows, so B2's equality proof will not exercise it.
- Both `MatchDecision` versions carry `schemaVersion` 1, because `SchemaVersion.Supported` is {1}.
- `MatchCandidate` has no source field. B1 and B2 record the DBLP-versus-external distinction in provenance or the block key.

## Progress checkpoint

A1 done (638 passed, exit=0). The new records have no tests yet. Next: A2, test-engineer.

## A2 — test-engineer (condensed)

- STATUS: FAILED, by design. The tests expose 2 production defects and were left in place.
- Created `modules/core/src/test/scala/scholarchain/core/ResolutionContractSuite.scala` with **116 new tests**:
  - frozen JSON for the 11 records, and round trips for every record;
  - the mandatory rejections (fabricated adjudication ID, reversed candidate order, confidence outside [0,1], the `ResolvedMention` singleton rule, the llm/modelVersion rule);
  - abstain valid;
  - all 5 enums, with unknown, differently cased and empty values rejected.
- Canonical run, as the agent observed it: core Total 640, **Passed 638, Failed 2**; hadoop Passed 114, Failed 0. Overall 752 of 754.
  - Orchestrator check: the marker reads `exit=1`.
  - A first attempt failed at `scalafmtCheck`, which `core/Test/scalafmt` then fixed.
- Failing tests, routed to domain-engineer in `repair-01.md`:
  1. `ResolutionContractSuite.scala:377`. `Wire.read` accepts `"outcome":null` and returns a record with a null enum field. `Wire.enumRw` (`Wire.scala:26-36`) lets JSON null through, and this affects every enum in core. A null `outcome` could bypass the "only accepted may merge" checks.
  2. `ResolutionContractSuite.scala:549`. `ResolvedMention.unresolved(..., Accepted, ...)` returns Right: an "accepted" record carrying the mention's own singleton ID, which contradicts the helper's "never merged" doc comment.
- The agent notes that some rejection tests assert only `Left` or any failure, without pinning the specific error.

## Progress checkpoint

A2 done: 116 new tests, 2 failing (repair-01 to domain-engineer). Marker `exit=1`. B1 is held until the core defects are fixed, because the ER module builds on `enumRw` and `ResolvedMention`.

## Repair 01 (domain-engineer, details in `repair-01.md`)

- Both A2 defects fixed (null enums rejected in `Wire.enumRw`; `ResolvedMention.unresolved` rejects `Accepted`).
- Orchestrator canonical run from the repo root: core 640, hadoop 114, Failed 0, `[success]`, marker `exit=0`. Total **754 passed** (baseline 638 + 116 new).

## Progress checkpoint

A1, A2 and repair-01 are done: 754 passed, 0 failed, exit=0. Next: B1, entity-resolution-engineer.

## B1 — entity-resolution-engineer (condensed)

- STATUS: COMPLETE for code and docs. The agent could not write the marker, because its shell cwd could not reach the repo root. The orchestrator ran the canonical command (result below).
- Created, all under `modules/entity-resolution/`:
  - `src/main/scala/scholarchain/er/`: `Text.scala` (Strings, Metrics, Names), `Identifiers.scala`, `EntityView.scala`, `Features.scala`, `Blocking.scala`, `Policy.scala`, `ErConfig.scala`, `Adjudication.scala`, `Io.scala`, `Gold.scala`, `tools/Queue.scala` (BuildViews, BuildReviewQueue) and `tools/GoldTools.scala` (SplitGold, Calibrate, Evaluate, FinalDecisions, AdjudicateBand).
  - resources: `scholarchain-er.v1.conf` and `er-prompts/er-adjudication-v1.txt`.
  - `ER-CONTRACTS.md`.
  - `gold/` and `evaluation/` exist but are empty: no labels were written.
- No file modified. No `.gitignore` change was needed: the agent reports that `git check-ignore -v` showed the LLM cache path already ignored by `**/target/`.
- Key APIs for B2 and C (details in ER-CONTRACTS.md):
  - `Blocking.candidate`, `generate` and `eligible`;
  - `Features.compute(a, b, hj3Conflict)`;
  - `Policy.decide(kind, features, Thresholds)`, `Policy.applyAdjudication` and `Decisions.build`;
  - `Adjudicator`, `AdjudicationCache` and `AdjudicationValidator`;
  - `GoldSplitter`, `Calibration` and `Evaluate`.
- Config keys `scholarchain.er.*`, with defaults in `scholarchain-er.v1.conf`:
  - placeholder thresholds for both kinds: accept 0.85, reject 0.45, LLM band 0.55–0.80. They are hand-chosen and uncalibrated, and Part B replaces them;
  - `llm.max-calls` 60, `llm.max-cost-usd` 2.0, provider `anthropic`;
  - the model `claude-haiku-4-5` and the prices are placeholders the human must confirm before P2.
- Smoke checks were run on synthetic fixtures in the agent's console and reported as observed:
  - AdjudicateBand makes no call without `--confirm`;
  - provider `bedrock` fails with the "not implemented in Phase 05" message;
  - a fake-client cache hit makes no call, and max-calls is enforced;
  - GoldSplitter gives 102/68 for 170 rows, deterministically;
  - an orcid disagreement stays CONFLICT even when a cached LLM accept exists.
  - Not exercised end to end: the CSV-reading mains SplitGold, Calibrate and Evaluate, because a labels file would be a label file. Calibration numbers on synthetic data are meaningless and are not reported.
- entityResolution has 0 tests so far (C writes them).

Risks and items for review (from the agent):
- CONFLICT is raised on any authoritative-ID disagreement. A preprint and its published version carry two different DOIs, so they become CONFLICT, not reject.
- Phase 04 does not persist venue or affiliation, so those features are absent in real runs and score a neutral 0.5. That weakens separation on real data.
- The year buckets (5 years for researchers, 3 for works) lose boundary pairs.
- The weights and deterministic rules in `Policy.scala` are hand-set and need the match-policy review.
- The tools call `sys.exit(1)` on error, which is unverified under `sbt runMain`. The default `--code-version` is `unversioned`.

## Progress checkpoint

B1 done. The orchestrator's canonical run afterwards: core 640, hadoop 114, 754 passed, 0 failed, marker `exit=0`, `[success] elapsed time: 248 s`. Next: B2, hadoop-engineer.

## B2 — hadoop-engineer (condensed)

- STATUS: COMPLETE. Files under `modules/hadoop/`:
  - Created `Hj6Blocking.scala` (HJ6a view build, HJ6b block-size count with combiner, HJ6c candidate pass with fallback routing and cap, HJ6d dedupe), `Hj7Decision.scala` and `ErSupport.scala`. Created the ER IT support plus 5 new `*ItSuite` files in `src/it`: `BlockingCandidateItSuite`, `BlockCombinerItSuite`, `MatchDecisionItSuite`, `ErDeterminismItSuite` and `RunSetupItSuite`.
  - Modified `Model.scala` (F7: the four old classes deleted), Hj1/Hj2/Hj4/Hj5, `PipelineDriver`, `RunContext`, `Counters`, `JobSupport`, `PipelineItSuite`, `DeterminismIdempotencyItSuite` (comment only), `JOB-CONTRACTS.md`, and the tools `compare_roots.py` and `summarize_run.py`.
- Phase 04 carry-overs: N1 (bounded merge provenance: max `provenance.inputs` per row fell from 4/11/4 to 2/2/2 for institutions/topics/works), N2 (the recorded `export-reducers` is the value used; invalid values refused), N3 (residual reader edge cases documented in JOB-CONTRACTS.md §8.1; not fixed) and N4 (`retrievedAt` compared as an instant, JSON text as tie-break).
- Canonical run (agent-reported; the orchestrator confirmed the marker): hadoop 143 passed (114 + 29 new IT tests), core 640, **783 passed, 0 failed**, `[success] elapsed time: 232 s`. Orchestrator check: the marker reads `exit=0` at 15:58 and no source is newer.
  - Agent's note on the mandatory item-5 tests: 8 satisfy it, including oversize fallback and skip (3), the cap (1), the combiner (4), decision outcomes with CONFLICT never accepted (6), determinism (4) and F7 (2).
- Real 1K runs, roots `modules/hadoop/target/runs/rootE` and `rootF` (gitignored), code version `74eee4e+uncommitted`, identical run ID `run:0df0a579…88a75`.
  - Orchestrator re-ran the comparisons: `compare_roots.py rootE rootF` → **ALL TABLES EQUAL**, 12 tables. `compare_roots.py rootC rootE --ignore-run-fields` → ALL TABLES EQUAL on the 8 Phase 04 tables.
  - That comparison ignores the top-level `provenance` and `runId`. They cannot match (code version and config are in the run ID, and N1 legitimately changed the merged rows).
  - rootC has no rows in person-aliases, duplicates, large-teams, oversize-teams or rejects. The real run therefore does not exercise F7 on any of the four moved tables. F7 evidence for them is the synthetic IT tests.
- Candidates (agent-reported): **2194** (researcher 1966, work 228), all with a primary block key; 2636 staged, 442 removed by dedupe.
- Largest block: `r1|liu|y` with 26 members; 10604 distinct block keys. **OVERSIZE_BLOCK = 0** at the cap of 500, so the oversize path has no real-data evidence and is proven only by the synthetic suites (cap 3).
- Decisions at the **placeholder thresholds** (2194): accepted 973 (deterministic 514, exact_id 459), conflict 229 (all exact_id), unresolved 992 (probabilistic), rejected 0.
  - CONFLICT rows with outcome accepted: 0. LLM-eligible: 163. No LLM was called.
  - All 229 conflicts come from identifier disagreement in the features (225 `orcid_disagree`, 4 `doi_disagree`). The HJ3 evidence has 0 CONFLICT rows.
- `counters.json` is at `modules/hadoop/target/runs/rootE/run/snapshot=dblp-1k/run_id=0df0…/counters.json` (not pasted).

Items for the human or reviewer:
- **ORCID disagreement as CONFLICT.** 225 of 1966 researcher candidates are CONFLICT only because two mentions carry different ORCIDs. The work order says disagreeing ORCIDs give CONFLICT, so this follows the spec. The agent notes that different ORCIDs on the same surname and initial usually mean two different people, so `rejected` might fit better. No reject appears at the placeholder thresholds. This is for the match-policy review.
- **Exact-identifier block keys.** HJ6c adds `x1|<type>|<id>` block keys beyond `Blocking.generate`, so that a pair sharing a DOI or ORCID but blocking apart still reaches the `exact_id` path. This is the agent's judgment call, and the reviewer should confirm it.
- `DedupeUnitSuite` (test-engineer's) pins the old unbounded merge through the 3-argument `Dedupe.rows`, which the agent kept. The new bounded path is covered only by an IT. C should add unit tests for `Dedupe.rows(..., known)` and `Dedupe.instantKey`.
- N3 is documented, not fixed. The COMBINE counters depend on splits and are excluded from equality checks.

## Progress checkpoint

B2 done: 783 passed, 0 failed, marker `exit=0`. rootE equals rootF on all tables. Next: C, test-engineer (at least 30 ER tests and 10 hadoop tests).

## C — test-engineer (condensed)

- STATUS: FAILED, by design (5 failing tests expose production defects). New tests: ER 207 and hadoop 43 (250 total). Hadoop 186 passed. core 640 passed.
- Files: ER test suites under `modules/entity-resolution/src/test/scala/scholarchain/er/` (Normalization, Metrics, Features, Blocking, Policy, Adjudication, Gold, Tools, Secret, Adversarial) and `ErFx.scala`; `modules/hadoop/src/test/.../ErJobsUnitSuite.scala` (43 tests); `UnitSupport.scala` (harness only, defaults unchanged).
- Orchestrator canonical run: marker `exit=1`; entityResolution `Failed: Total 207, Failed 5, Passed 202`; hadoop 186 and core 640 all passed. Failing suite: `AdversarialSuite` only.
- C did not mutation-test the safety tests. Not covered: `BuildViews` and `BuildReviewQueue`, and HJ6/HJ7 through a real job (in-process only).
- The human saw this output and approved the repair: repair-02 (root causes 1-4).

## Progress checkpoint

Repair-02 done by the agent (ER 207/207 on its own run). Orchestrator canonical run still pending. Next: test-engineer adds `BuildViews` and `BuildReviewQueue` tests, then the canonical run, then D.

## Repair 02 and C follow-up (condensed)

- repair-02 (entity-resolution-engineer): the four root causes are fixed (hyphenated family name rule, code-point tokens, single-pass prompt render, `validateAgainst` on cache hit, cache load and `Resolver.decide` / `Policy.applyAdjudication`). Details in `repair-02.md`. No test edited, and no threshold, weight or wire format changed.
- test-engineer follow-up: `QueueSuite.scala`, 9 new tests (blind CSV header and no score or feature values in any cell, a fixed seed gives a byte-identical file, both entity kinds present, `allocate` on hand-computed numbers, `isHard`, `BuildViews`, canonical row order).
  - Agent-observed ER total 216, 0 failed. It left `QueueSuite.scala` unformatted.
- CHECK 1 (orchestrator, canonical, from the repo root): **FAILED**, marker `exit=1`. Stopped at `entityResolution / Test / scalafmtCheck`: `QueueSuite.scala isn't formatted properly!`. No tests ran in that run. Shown to the human before any further step.
- Formatting fix (test-engineer, not a repair round): `sbt "entityResolution/Test/scalafmt"`, exit 0, "Reformatted 1 Scala sources". No hand edits.
- CHECK 2 (orchestrator, canonical, from the repo root): **marker `exit=0`**, `[success] elapsed time: 234 s`.
  - core Passed 640, hadoop Passed 186, entityResolution Passed 216; Failed 0, Errors 0 in each.
  - Total **1,042 passed, 0 failed** (baseline 638, +404).

Known limits: the hyphen fix changes the researcher block key on real data, so rootE and rootF predate repair-02 and are kept as history only (disclosed). C did not mutation-test the safety tests. HJ6/HJ7 are covered in-process and by the integration suites only.

## Progress checkpoint

CHECK 2 passed (1,042 passed, exit=0). Next: hadoop-engineer reruns the real 1K pipeline into new roots rootG and rootF2 (no source edits), then D reviews rootG, then Q samples from rootG.

## Real runs on the final code: rootG and rootF2 (hadoop-engineer, no source edits)

- Roots `modules/hadoop/target/runs/rootG` and `rootF2` (gitignored), snapshot `dblp-1k`, code version `74eee4e+uncommitted`, run ID `run:0df0a579…88a75`. Nothing was overwritten; rootE and rootF stay as history and **predate repair-02**.
- Orchestrator re-ran the comparisons:
  - `compare_roots.py rootG rootF2` → **ALL TABLES EQUAL**, 12 tables.
  - `compare_roots.py rootE rootG` → DIFFERENCES FOUND in exactly 3 tables: `hj6b/block-sizes` (10604 → 10608), `hj6d/match-candidates` (2194 → 2192) and `hj7/match-decisions` (2194 → 2192). The 8 curated tables and `hj6a/entity-views` are equal.
- Candidates, agent-reported (rootE in parentheses):
  - 2192 (2194): researcher 1964 (1966), work 228 (228). All with primary block keys. 2633 staged, 441 removed by dedupe.
  - By pair id: 4 only in rootE, 2 only in rootG, 2190 shared. Of the shared pairs, 14 have different features, and the agent inspected 3 of them.
  - The pairs that moved are hyphenated names, for example "Moreno-Valenzuela", "Gatmir-Motahari" and "Bin-Da Liu". The six block keys that changed size include `liu|b` 7→8 and `motahari|s` 3→2.
- Decisions, agent-reported (rootE in parentheses), at the **placeholder thresholds**: 2192 (2194): accepted 971 (973), conflict 227 (229), unresolved 994 (992), rejected 0 (0). Conflicts: orcid_disagree 223 (225), doi_disagree 4 (4). LLM_ELIGIBLE 163 (163). No conflict row is accepted.
- Largest block `r1|liu|y` with 26 members, then `wang|y` 22, `wang|j` 19, `zhang|z` 18, `li|z` 17 (same as rootE). OVERSIZE_BLOCK is absent from counters.json, which the agent reads as 0. It did not check how zero counters are written.

Provenance risk (agent-reported, orchestrator confirmed from the compare output): rootE and rootG carry the **same run ID** although their outputs differ, because the run ID covers inputs, code-version token and config, and the token `74eee4e+uncommitted` is the same. Only the output hashes tell them apart. Passed to D.

## Progress checkpoint

rootG equals rootF2; rootG supersedes rootE. Marker `exit=0` (1,042 passed) and no source newer. Next: D, code-reviewer, against rootG.

## Review pass 1 and repair-03 (condensed)

- D (code-reviewer): REQUEST_CHANGES (H1, M2, M3 must change; M1 needed a human decision). Full text in `review-report.md`.
- Human decisions: M1 REJECTED for a plain ORCID disagreement (CONFLICT only beside strong evidence); L2 reason code `doi_disagree_possible_version` (outcome stays CONFLICT); M4 no convention change, the final run uses the WIP commit hash as `--code-version`. Deferred: L3, L4, L7, L8, L9, M5, CLAUDE.md wording.
- repair-03 done by three agents (details in `repair-03.md`). L1a is not done and is waiting for the human.
- CHECK 3 (orchestrator, canonical, from the repo root): marker `exit=0`, core 640 + hadoop 192 + entityResolution 262 = **1,094 passed, 0 failed** (baseline 638).

## Progress checkpoint

repair-03 done and CHECK 3 passed (exit=0). STOPPED for the human WIP commit. Next, after the hash: hadoop-engineer runs two new roots with that hash and reports how many rootG exact_id accepts change outcome; D re-reviews only the repair-03 changes; then Q samples from the new root.

## Real runs on the repair-03 code: rootH and rootH2 (hadoop-engineer, no source edits)

- Roots `modules/hadoop/target/runs/rootH` and `rootH2` (gitignored), snapshot `dblp-1k`, `--code-version "74eee4e+repair03"` (a hand-chosen label, not a content address; M4 reduced, not closed). Run ID `run:3ee96c12…bd79`, different from rootE/rootG's `0df0a579…`.
- Orchestrator re-ran the comparisons: `compare_roots.py rootH rootH2` → **ALL TABLES EQUAL**, 12 tables. `rootG` vs `rootH` with `--ignore-run-fields` (this flag compares only the 8 curated tables) → ALL TABLES EQUAL. Without the flag, raw hashes differ because the run ID and code-version label sit in each row's provenance.
- Source unchanged during the runs (agent-recorded and orchestrator-checked): `git status --short` 35 lines and diff stat `19 files changed, 535 insertions(+), 146 deletions(-)` before the first run and after the second. The marker is still CHECK 3 (`exit=0`), and no source is newer than it. These counts are coarse.
- rootG vs rootH, ER tables compared by the agent with provenance and runId stripped (agent-reported):
  - Entity views (13006) and block sizes (10608) are equal. `hj6d/match-candidates` has the same 2192 pairs; **2 rows differ**, only in `features`: rootH adds `doi_preprint_version_pair: 1` (the L2 feature) on two work pairs (the RoMe and TreeP pairs, with `doi_agreement` 0, `title_sim` 1). So HJ6 is not byte-identical, but the pair set and block keys are.
- Decisions, from the `Decisions` counter group in counters.json (orchestrator re-read it; `ACCEPTED` is not used): accepted.deterministic 512, accepted.exact_id 458, conflict.exact_id 9, rejected.exact_id 219, unresolved.probabilistic 994. Total 2192. By outcome: accepted 970, conflict 9, rejected 219, unresolved 994 (rootG: 971 / 227 / 0 / 994). Placeholder thresholds, uncalibrated. Candidates: researcher 1964, work 228. LLM_ELIGIBLE 163. OVERSIZE_BLOCK absent (read as 0). Largest block `r1|liu|y`, 26.
- Conflicts by reason (agent-reported, from the decision table): orcid_disagree 4, doi_disagree_possible_version 2, doi_disagree 2, orcid_name_mismatch 1. Rejected: orcid_disagree 219.
- How rootG's exact_id decisions changed (agent-reported, matched by pair ID):
  - 459 accepted: 458 stayed accepted (220 `doi_equal`, 238 `orcid_equal`); **1 became conflict `orcid_name_mismatch`**. None became rejected or unresolved.
  - 227 conflicts: 219 became rejected `orcid_disagree`; 4 stayed conflict `orcid_disagree`; 2 stayed `doi_disagree`; 2 became `doi_disagree_possible_version`.
  - No probabilistic or deterministic decision changed.
- Observation (agent): the one accepted-to-conflict change is "Ángela Hernández‐Solana" vs "Ángela Hernández-Solana", which differ only by a Unicode hyphen (U+2010) versus an ASCII hyphen. The names are the same person, yet H1's name check fails them. Passed to D.

## Progress checkpoint

rootH equals rootH2. Marker `exit=0` (1,094 passed), no source newer. Next: D re-review of the repair-03 changes only. Q waits for an approving verdict.

## Review pass 2 and repair-04 (condensed)

- D pass 2 (code-reviewer): REQUEST_CHANGES, F1 HIGH (Unicode dashes split family names), F2 MEDIUM, F3-F6 LOW. Details in `review-report.md`.
- Human decisions: F1 to F6 all fixed in one final round (repair-04); deferrals unchanged.
- CHECK 4 (orchestrator, canonical, from the repo root): marker `exit=0`, core 640 + hadoop 192 + entityResolution 293 = **1,125 passed, 0 failed** (baseline 638). Details in `repair-04.md`.

## Progress checkpoint

repair-04 done and CHECK 4 passed (exit=0). Next: hadoop-engineer runs rootI and rootI2 with `--code-version "74eee4e+repair04"` (no source edits) and compares them; D re-reviews only the repair-04 changes; then Q on rootI.

## Real runs on the repair-04 code: rootI and rootI2 (hadoop-engineer, no source edits)

- Roots `modules/hadoop/target/runs/rootI` and `rootI2` (gitignored), snapshot `dblp-1k`, `--code-version "74eee4e+repair04"` (hand-chosen, not a content address; M4 reduced, not closed). Run ID `run:89884d89…6610`, different from the earlier roots.
- Orchestrator re-ran `compare_roots.py rootI rootI2`: **ALL TABLES EQUAL**, 12 tables. Source unchanged during the runs (agent-recorded 35 status lines and `19 files changed, 535 insertions(+), 146 deletions(-)` before and after; orchestrator re-checked after, with the marker newer than all sources). These counts are coarse.
- Decisions from the `decisions` group of the hj7 job in counters.json (orchestrator re-read it; `ACCEPTED` is not used), at the **placeholder thresholds**: accepted.deterministic 511, accepted.exact_id 457, conflict.exact_id 10, rejected.exact_id 219, unresolved.probabilistic 994. Total 2191. By outcome: accepted 968, conflict 10, rejected 219, unresolved 994 (rootH: 970 / 9 / 219 / 994).
- Candidates (agent-reported, from the tables): 2191 (rootH 2192): researcher 1963, work 228. Conflicts by reason: orcid_disagree 4, doi_disagree_possible_version 2, doi_disagree 2, orcid_name_mismatch 2. Rejected: orcid_disagree 219. Largest block `r1|liu|y`, 26. LLM_ELIGIBLE 163. OVERSIZE_BLOCK absent (read as 0).
- rootH to rootI (agent-reported, ER tables compared with provenance and runId stripped, matched by pair ID):
  - Entity views identical (13006). Block sizes: 3 block keys differ. Candidates: 1 pair only in rootH, 0 only in rootI, 2191 shared; 1965 of the shared pairs have different features (1955 only through the new `family_jw`; 7 also in `coauthor_overlap`; 1 in `family_exact`, `name_sim` and `family_jw`; 2 work pairs in the key name `author_overlap`). `featureSetVersion` is `er-features-v2` in all rootI rows.
  - 3 decisions changed outcome; none changed reason or path within an outcome:
    - 1 conflict to accepted (`orcid_equal`): "Ángela Hernández‐Solana" vs "Ángela Hernández-Solana" (the F1 fix; `family_exact` 1).
    - 2 accepted to conflict (`orcid_name_mismatch`): "Michael Märker" vs "Michael Maerker" (`family_jw` 0.8952) and "Ángela Hernández" vs "Ángela Hernández-Solana" (`family_jw` 0.9125). Both are plausibly the same person.
  - ORCID-only accepts in rootI: 237, all with `family_exact` = 1 and `family_jw` = 1. **None passes through the 0.92 family_jw route** (`family_exact` = 0): there is nothing to list.
- **Recall loss from the F1 rule (agent-reported):** the pair "Charles Ffrench Constant" | "Charles ffrench‐Constant" (U+2010 in the second) was accepted in rootH (deterministic, same work and position) and is gone in rootI. The fixed code reads the dash form as the family "ffrench constant" (block `r1|ffrenchconstant|c`) while the space form gives the family "constant" (block `r1|constant|c`), so a true match no longer blocks together. This is inherent in joining a hyphenated family name and not in a bug in the dash mapping. Net: -1 candidate, -1 accepted. Passed to D.

## Progress checkpoint

rootI equals rootI2. Marker `exit=0` (1,125 passed), no source newer. Next: D re-review of the repair-04 changes only. Q waits for an approving verdict.

## Progress checkpoint

D pass 3 on repair-04: APPROVE WITH NOTES (no blockers). Notes N1 (policy, human decision before Part B freezes `er-v1`) and N2 (recall, additive block key, would need an HJ6/HJ7 rerun) are for the human. Next: Q on rootI.

## Q: blind review queue on rootI (entity-resolution-engineer; verified by the orchestrator)

- Built from rootI (`run:89884d89…6610`, `--code-version "74eee4e+repair04"`) with `BuildViews` on the curated works and author-mentions, then `BuildReviewQueue --seed 20260101 --researcher 150 --work 50`. Output: "review queue: 161 rows (researcher 150, work 11), seed 20260101, candidates without views: 0".
- Files under `modules/entity-resolution/gold/`: `review-queue-v1.csv`, `review-queue-v1.sampling.json` (aggregates only: tool, codeVersion, policyVersion, featureSet, seed, requested, selected, candidatesRead, candidatesNoView, inputs, strata, blind) and `LABELING-GUIDE.md`. No labels file exists, and no agent wrote or suggested a label.
- Orchestrator checks on the CSV (observed): 22 columns in the order of prompt.md section 10, ending in `label` and `notes`; 161 rows (researcher 150, work 11); 0 non-empty `label` cells and 0 non-empty `notes` cells; 161 distinct `pair_id`; 0 rows where `left_id` is not smaller than `right_id`; at most 8 coauthors or authors per side; no header name matching score, feature, outcome, stratum, block, run, decision, sim, jw or conf; no decimal-like values in the file; none of the tokens score, stratum, run_id, bin0-bin4, ACCEPT, REJECT, CONFLICT, exact_, name_sim, family_jw, er-v1 or er-features appears. The substring `block` appears only inside data ("Hans Ulrich Block" as a coauthor, "Blockchain" in a work title).
- rootI candidates by kind: researcher 1,963, work 228 (220 of the work candidates share an identifier, the `exact` stratum, which the tool caps at n/10).
- **Correction by the orchestrator:** an earlier version of this line said no source changed in this step and no further canonical run was needed. That was wrong. The orchestrator's check looked only at `.scala`, `.sbt`, `.py`, `.conf` and `.txt` files, but the Stop hook counts any file under `modules/`, and the new files under `gold/` were newer than the CHECK 4 marker. The orchestrator ran CHECK 5 (below).

**Shortfall, stated plainly:** the queue has 161 pairs, not about 200, and only 11 work pairs, not about 50. The labeling gate needs at least 170 definitive labels, with both classes present in each entity kind, so this queue cannot meet it even if every row is definitive. The work kind will have very few non-exact pairs (4 near-threshold, 2 conflict), so both classes in the work kind may be out of reach. The agent did not resize the queue, because that changes the mix you requested.
- Options (not taken): rerun `BuildReviewQueue` with a larger `--researcher` (200 gives about 211 rows); raise `--work` (lifts the exact cap but adds mostly identifier-match pairs); or change the tool's stratum cap (a source change). The sidecar records `codeVersion` as `unversioned`, as the tool wrote it.

## Final totals (orchestrator, observed)

- Baseline 638. CHECK 4 on the final tree: core 640, hadoop 192, entityResolution 293, Failed 0, marker `exit=0`. **Total 1,125 passed** (+487). The `sbt check` total rises by more than the gate's 48.
- Real runs: rootI equals rootI2 on all 12 tables. rootE to rootH are superseded history.
- Reviews: pass 1 REQUEST_CHANGES, pass 2 REQUEST_CHANGES, pass 3 APPROVE WITH NOTES (`review-report.md`).
- Open for the human: N1 (the 0.92 `family_jw` route admits Chen/Cheng-type pairs; decide before Part B freezes `er-v1`), N2 (additive block key for the Ffrench Constant case; needs an HJ6 and HJ7 rerun if taken), the queue size above, M4 (the code-version label is hand-chosen, not a content address), the CLAUDE.md boundary wording for the hadoop to entityResolution edge, and the deferred items (L1a, the HJ7 `ACCEPTED` counter, L1d, L3, L4, L7, L8, L9, M5 before Phase 06).
- Nothing was committed or staged by any agent, and Part B was not started.

- CHECK 5 (orchestrator, canonical, from the repo root, after the queue files were written): **marker `exit=0`**, `[success] elapsed time: 234 s`. core Passed 640, hadoop Passed 192, entityResolution Passed 293; Failed 0, Errors 0 in each. Total **1,125 passed, 0 failed** (baseline 638). No file outside `target/` is newer than the marker.

## Queue regeneration to 240 rows (human request; entity-resolution-engineer; verified by the orchestrator)

- **Why 161, not about 200** (orchestrator's reading of `Queue.scala:119-139` and the sidecar): `BuildReviewQueue.select` hard-codes the exact-identifier stratum to n/10 and the conflict stratum to n/20, and the work pool is tiny. Counts (available / selected) in the first 161-row queue: researcher bin2 1154/48, bin3 35/35, bin4 531/46, conflict 6/6, exact 237/15 (bin0 and bin1 empty); work bin3 4/4, conflict 4/2, exact 220/5 (all other bins empty). Work gave 11 of 50 requested; researcher gave all 150.
- **Command (command-line change only, no source edit):** `sbt "entityResolution/runMain scholarchain.er.tools.BuildReviewQueue --candidates <repo>/modules/hadoop/target/runs/rootI/work/hj6d/match-candidates --views <scratchpad>/views.jsonl --out <repo>/modules/entity-resolution/gold/review-queue-v1.csv --seed 20260101 --researcher 227 --work 60"`. Seed **20260101**. The views file came from the earlier Q step (built 20:22 from rootI's curated tables; not rebuilt, not re-verified).
- **Overwrite:** the tool refuses to overwrite an existing `--out` file. On the human's instruction to overwrite the unlabeled queue, the orchestrator first confirmed that the scratchpad backups (`old-queue.csv`, `old-sampling.json`) were byte-identical to the originals and that the file had 0 non-empty `label` or `notes` cells, then removed `review-queue-v1.csv` and `review-queue-v1.sampling.json` by exact path, then had the agent regenerate. The old files exist only in the scratchpad.
- **Result (agent-reported, orchestrator re-verified on the CSV):** `review queue: 240 rows (researcher 227, work 13), seed 20260101, candidates without views: 0`. Sidecar strata (available / selected): researcher bin2 1154/83, bin3 35/35, bin4 531/81, conflict 6/6, exact 237/22; work bin3 4/4, conflict 4/3, exact 220/6. All 161 pair IDs of the old queue are in the new one; 79 are new.
- **Orchestrator checks on the new CSV (observed):** 22 columns ending in `label` and `notes`; 240 rows (researcher 227, work 13); 0 non-empty `label` cells and 0 non-empty `notes` cells; 240 distinct `pair_id`; 0 rows with `left_id` not smaller than `right_id`; no header name suggesting a score, feature, outcome or stratum; 0 decimal-like values; no `gold-labels-v1.csv` exists. The only token hits are `block` (the surname "Block" and the word "Blockchain") and `reject` ("Common-Mode Rejection" in one work title), all data content.
- `LABELING-GUIDE.md` gained one note that the work kind has very few non-identifier pairs, so the labeler must report a missing class. The guide quoted no old row counts. Hard-negative count: NOT MEASURED (the sidecar reports none).
- CHECK 6 (orchestrator, canonical, from the repo root, after the queue files changed): **marker `exit=0`**, `[success] elapsed time: 233 s`. core 640, hadoop 192, entityResolution 293 passed; Failed 0, Errors 0. Total **1,125 passed, 0 failed** (baseline 638). No file outside `target/` is newer than the marker. No source file was edited in this step.

**Still open for the human:** the work kind has only 13 rows and 9 of them are identifier matches or conflicts, so a NON_MATCH class in the work kind may be missing, and Part B's "both classes per entity kind" check may fail however many researcher rows are labeled (more work pairs need a source change to the stratum caps, and would mostly add identifier matches). With 240 rows, the 170-definitive-label minimum leaves room for about 70 UNSURE or excluded rows. N1, N2, M4, the CLAUDE.md boundary wording and the deferred items are as listed above.

## Progress checkpoint

Part A complete; waiting for human labels in gold/gold-labels-v1.csv


## Part B (human decisions, as received)
Labels saved by the human: gold/gold-labels-v1.csv (human-authored, outside any agent). 237 definitive (researcher 117 MATCH / 107 NON_MATCH; work 12 MATCH / 1 NON_MATCH), 3 UNSURE excluded. N1 DEFER (disclose; held-out measures it). N2 DEFER. P2 SKIP: LLM path reported as "not executed: provider decision pending (Bedrock vs. Anthropic API, $25 budget)". Work kind: descriptive metrics only, no precision conclusions. At most one repair round.

## P1 (entity-resolution-engineer; agent-reported unless marked observed)
- Orchestrator check before dispatch (observed, python3 -I): 240 label rows = 240 queue rows, header equal, all pair_ids in queue, 0 changed non-label cells, 0 notes; counts as above.
- SplitGold (seed 20260101, 60/40 by kind x label): calibration 142, held-out 95, UNSURE 3 excluded. researcher MATCH 70/47, NON_MATCH 64/43; work MATCH 7/5, NON_MATCH 1/0 (calibration/held-out). Split sha256 1fad6c2d...8e34. The only work NON_MATCH went to calibration, so held-out work has no NON_MATCH.
- Calibrate (calibration rows only), frozen er-v1 / er-features-v2: researcher accept 0.77, reject 0.52 (in-sample P 0.9846, R 0.9143, F1 0.9481; recall falls to 0.8286 at 0.78); work 0.79/0.79 (n=8, descriptive only). Sensitivity table in evaluation/er-v1-threshold-sensitivity.csv.
- AdjudicateBand PLAN ONLY (no --confirm, no key): 470 eligible, 60 planned calls (max-calls), model claude-haiku-4-5, est. USD 0.1260 (placeholder prices). The split file (incl. held-out pair ids) was passed as --priority-pairs for ordering only (reviewer: not a leakage concern; F6 recommends a pair_id-only file).
- P2: SKIPPED by the human. LLM path: not executed: provider decision pending (Bedrock vs. Anthropic API, $25 budget).

## P3 (entity-resolution-engineer; agent-reported unless marked observed)
- Evaluate ran ONCE on held-out (ledger has one line, disclosedReEvaluation null; no cache, no LLM). FinalDecisions on rootI: 2191 decisions; accepted 999, conflict 10, rejected 712, unresolved 470; by path deterministic 511, exact_id 686, probabilistic 994; llm 0.
- Held-out researcher (n=90): TP 40, FP 3, FN 7, TN 40; P 0.9302, R 0.8511, F1 0.8889; coverage 0.7444, unresolved 0.2333, conflict 0.0222. Work (n=5, 5 MATCH, 0 NON_MATCH): TP 3, FN 2, recall 0.60; precision/F1 not measurable.
- 12 errors, all listed in evaluation/er-v1-results.md/.json. N1 route: none of the 12 errors comes from it.

## Part B review (code-reviewer): APPROVE WITH NOTES
Recomputed all held-out metrics from stored predictions and labels: all match. Leakage controls hold. F1-F5 documentation fixes (causes for two ORCID-disagreement FNs better explained by different coauthors on one paper, so a label error is likelier; FP causes should name a label-error hypothesis; work precision/F1 and n=0 path metrics must not appear as numbers in the JSON; L6 circularity disclosure missing; two wording errors). F6 info. F7 (this section was missing) is fixed by these entries. Reviewer observed possible label inconsistencies for the human to audit (Lei Ren / Luquan Ren labelled both MATCH and NON_MATCH in different rows; one same-name-same-paper pair labelled NON_MATCH); any relabel invalidates the frozen calibration and needs a new, disclosed evaluation.
- CHECK 7 (orchestrator, canonical, from repo root, observed): marker exit=0, [success] 237 s; core 640, hadoop 192, entityResolution 293 passed, 0 failed, 0 errors = 1,125. No source changed in Part B.

## Part B repair round (the single allowed round; documentation only)
- entity-resolution-engineer fixed F1-F5 in evaluation/er-v1-results.md and .json (agent-reported): causes reworded as inferred from stored features only, label-error hypothesis added; work held-out precision/F1 and every n=0 path metric set to null/n-a; L6 circularity limitation added; two wording errors fixed; label-inconsistency note added as a limitation. No Evaluate, Calibrate, SplitGold or FinalDecisions rerun; no metric, threshold, split, label or ledger change.
- Reviewer re-read: APPROVE WITH NOTES. All metrics unchanged. One leftover: er-v1-results.md line 231 still carries the old clause "Other evidence points to the same person ..." before the new conclusion for Pb2c129f30845, so the md contradicts itself and differs from the JSON. NOT fixed: the repair round is used up; the human decides whether to authorize a wording-only fix.
- CHECK 8 (orchestrator, canonical, from repo root): see the marker result recorded by the final report message.

## Progress checkpoint
Part B complete through the reviewer pass; waiting for the human gate. P2 not executed (provider decision pending). Nothing committed or staged.
