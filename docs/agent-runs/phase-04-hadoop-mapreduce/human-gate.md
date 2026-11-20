# Phase 04 — Human gate

Phase: 04, raw ingestion and Hadoop MapReduce
Date: 2026-11-20
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with the decisions and remaining risks recorded below.

## 1. What I reviewed

- The work order `prompt.md` (written by me; the orchestrator saved it verbatim).
- `agent-report.md`: preconditions, carried items from Phase 03, A0, DS, A1, A1r and B reports, and the progress checkpoints.
- `test-report.md`: A2 + DS-T and C reports.
- `review-report.md`: pass 1 (REQUEST_CHANGES, F1 blocking) and pass 2 (APPROVE WITH NOTES).
- `repair-01.md` (extractor comment leak) and `repair-02.md` (review pass 1 findings).
- `modules/hadoop/JOB-CONTRACTS.md`.
- My own hands-on checks (section 5).

## 2. What was accepted

- **Hadoop pipeline.** Jobs HJ1–HJ5 (6 MapReduce jobs including HJ4a/HJ4b) in `modules/hadoop/**`, with the driver, counters, reject-v1 output, provenance, determinism and idempotency (no overwrite, no delete code).
- **Core contracts** added by domain-engineer: `MentionAuthorship`, `MentionTeam`, `MentionPairCandidate`, `ExactIdentifierEvidence`, `RejectRecord`, `PipelineRunManifest`; arXiv, OpenAlex and title-key helpers; module config `scholarchain.hadoop.*` with a pair hard cap of 499,500.
- **Build:** `src/it` wired into Test (+5 lines in `build.sbt`).
- **Dataset:** bounded 1,000-record XML extractor `datasets/scripts/dblp_extract_subset.py` (v1.1.0) and its manifest entry.
- **Tests:** baseline 307 → **638 passed, 0 failed** (core contract tests +217, hadoop integration 38, hadoop unit/adversarial 76). Plus 23 extractor tests in `tests/datasets` (Python, 83/83 in that suite).
- **Real local runs** on the 1,000-work DBLP selection plus the cached Crossref/OpenAlex/ORCID records, under LocalJobRunner. Final runs rootC and rootD:
  - run ID `run:2bfe1482…920d2d`, code version `6335b5a+uncommitted`;
  - ALL TABLES EQUAL;
  - works 8,295 (unique by ID), authorship 3,928, teams 1,000, pair candidates 8,420 (= sum of n(n−1)/2 over the selection), exact-identifier evidence 296, institutions 250, topics 206;
  - 0 rejects;
  - largest team 42 authors (861 pairs).

## 3. Decisions taken during the phase

1. **Bounded subset extract (exception to work-order rule 0.7).** The 1K selection was a TSV without author lists or XML. I approved a one-time, read-only streaming pass over the full `dblp.xml` by a Python extractor (dataset-engineer). It wrote only the 1,000 selected records to the gitignored `datasets/raw/dblp/derived/`, at sha256 `3dfcab53…`. Hadoop never processed the full file.
2. **Speed rule (mid-phase instruction).** For the rest of the phase, agents used targeted sbt commands while iterating and ran the full canonical command once per work order. No test minimum or gate requirement was reduced.
3. **J05/J06 deferral.** Blocking keys and match features are owned by entity-resolution-engineer under `docs/agents.md`, so the spec's J05 (BlockingKeyJob) and J06 (CandidateJoinJob) are deferred to Phase 05. Phase 04 contains no blocking, match-feature, score, threshold or decision code (confirmed by the reviewer).

## 4. Decisions on the items held for this gate

1. **F7, moving pipeline output records into core:** ACCEPTED as a **Phase 05 precondition**. `PersonAlias`, `DuplicateRecord`, `LargeTeam` and `OversizeTeam` move from `modules/hadoop` to core (domain-engineer). hadoop-engineer then imports them. This is the first work order of Phase 05.
2. **F6, missing-year Crossref/OpenAlex records** (7 in the real run): ACCEPTED as a documented gap. They keep mentions and identifiers but get no WorkRecord, because `WorkRecord.year` is mandatory. All 1,000 DBLP works have a year, and the coauthorship graph is built from DBLP, so the graph is unaffected. To be revisited in Phase 05 only if entity resolution needs those titles.
3. **Reject provenance:** ACCEPTED as run-ID-only. A reject carries `runId`, `jobId` and `sourceRecordId` and/or `inputByteOffset`. The run ID joins to the manifest's input checksums, code version and config. No reject-v2.
4. **A1 wire choices:** ACCEPTED. These are: lowercase snake-case outcome names (`exact_link`); the redundant `schema: "reject-v1"` field; the 12 reason codes, including the two currently unused ones; and `Mention*` records alongside the frozen researcher-keyed records. Phase 05 must define the mapping from mention records to the researcher-keyed records.
5. **Merge scope:** ACCEPTED. hadoop-engineer merged institutions (274 → 250) and topics (300 → 206) by ID as well as works. The reviewer supports this. N1 is tracked (section 6).

## 5. My hands-on checks

- `sbt check` on the final tree: 638 passed (core 524, hadoop 114), 0 failed, `[success]` in 128 s; marker `exit=0` (checked with `cat .claude/.last-test-run`).
- `compare_roots.py rootC rootD`: ALL TABLES EQUAL (8 tables; works 8295, pair-candidates 8420, authorship 3928, teams 1000).
- `counters.json` (rootC): EMITTED_PAIRS = EXPECTED_PAIRS = 8420; WORK_MERGED = 173; no REJECTED counter (0 rejects on the real data).
- Inspected one pair-candidates record by hand (`work:dblp:journals/jsyml/ChwistekH38`, 1938): canonical order (first < second by mention ID; positions 1 and 0), year present, full provenance with input checksums, code version `6335b5a+uncommitted`, all config parameters, run ID.
- `git check-ignore`: `modules/hadoop/target` (.gitignore:14) and `datasets/raw/dblp/derived` (.gitignore:2) are ignored.
- Copied rootC `manifest.json`, `counters.json` and the comparison output to `evidence/` in this folder.

## 6. Remaining risks and open items

- **N1 (MEDIUM).** Merged topics and institutions list every source envelope in `provenance.inputs`. At full scale this can produce very large rows and exhaust reducer memory. To be fixed by hadoop-engineer in Phase 05's Hadoop work order, and before Phase 15 in any case.
- **N2–N4 (LOW).**
  - The `export-reducers` value recorded in the manifest can differ from the value the job used.
  - Two reader edge cases are undocumented: an unterminated CDATA or comment, and an unterminated attribute quote.
  - "Latest retrievedAt" is compared as a string, not as an instant.

  These go in the same Phase 05 Hadoop work order.
- **Local only.** No EMR run, no S3 paths and no Snappy codec were exercised. All results are local LocalJobRunner runs on real DBLP and real cached enrichment data. EMR is Phase 14/15. Compressed DBLP is read by a single mapper (correct, but not parallel).
- **Reject path on real data** was not exercised: the 1K selection produced 0 rejects. It is tested only with synthetic fixtures.
- **Regression tests were not run against the pre-fix code.** The reviewer judged by reading that they would fail on a revert.
- **Test harness fragility.** test-engineer's in-process reducer harness relies on Hadoop internals and may break on a Hadoop upgrade.
- **Not tested:** the F10 input filter, and a job actually honouring a max-record-bytes override.
- **Guard anomaly (unverified).** During A1r, domain-engineer reported that a guard-rejected edit "still seems to have run", producing a duplicate block that it then removed. The reviewer confirmed exactly one `OpenAlexId` definition in the final tree. The guard behaviour itself was not reproduced. To be probed in the deferred factory session.

## 7. Manual changes and process disclosures

- **No human edits to code, tests, contracts or agent reports.** My contributions were the work order, the subset-extract decision, the speed-rule instruction, the gate decisions above, and this file.
- **Session.** One orchestrator session in Claude Code 2.1.311: the orchestrator ran on Opus 5.5, the implementation agents on Sonnet, and code-reviewer on Opus.
- **Usage limit.** Work Order B stopped once on the Claude usage limit (HTTP 429) before writing any files. I continued with Claude extra usage credits in the same session, and the same agent context was resumed.
- **Interim snapshot runs.** Several orchestrator `sbt check` runs were forced by the Stop hook while a subagent was mid-edit. One failed on formatting. These are recorded as snapshots, not as agent results.
- **Lost report.** dataset-engineer's first consolidated DS report was lost to a Stop-hook loop. The orchestrator verified the extract output directly (sha256, record count, DOCTYPE, gitignore).

## 8. Accepted commit

Accepted commit hash: see `commit.txt`.