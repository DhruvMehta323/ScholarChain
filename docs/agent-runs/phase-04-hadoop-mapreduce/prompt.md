# Phase 04 — Hadoop MapReduce pipeline (work order)

Phase folder: `docs/agent-runs/phase-04-hadoop-mapreduce/`
Spec section: "Phase 4: implement raw ingestion and Hadoop MapReduce" and "Detailed Hadoop requirements".
Ownership follows `docs/agents.md` section 1 and 1.1.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A0 | build-engineer | `build.sbt`, `project/**` (not `project/Versions.scala`) | only if a precondition fails |
| A1 | domain-engineer | `modules/core/**` except `src/test/**` | only if a contract is missing |
| A2 | test-engineer | `modules/core/src/test/**` | only if A1 ran |
| B | hadoop-engineer | `modules/hadoop/**` except `src/test/**` (integration tests in `modules/hadoop/src/it/**`) | always |
| C | test-engineer | `modules/hadoop/src/test/**`, `tests/**` | always |
| D | code-reviewer | nothing (verdict returned as a message) | always |

---

## 0. Rules for the orchestrator in this session

1. You route work. You do not write Scala, tests, contracts or docs inline.
2. Subagents do not call each other. Paste a prior report into a later work order only where this file says so.
3. **Phase 04 / Phase 05 boundary (already decided; do not re-ask).** `docs/agents.md` gives blocking keys and match features to `entity-resolution-engineer`. So the spec's J05 (BlockingKeyJob) and J06 (CandidateJoinJob) are **deferred to Phase 05**. In Phase 05, `entity-resolution-engineer` will write the pure blocking-key and feature functions first, and `hadoop-engineer` will then write the MapReduce jobs that call them. In Phase 04, no agent writes blocking keys, match features, MatchCandidate records, scores, thresholds or match decisions.
4. Honesty floor: no agent reports a command, test count or output it did not observe. Anything not run is reported as "NOT RUN", with the reason.
5. The canonical test command is exactly `sbt check; echo "exit=$?" > .claude/.last-test-run`. Every agent that changes source runs it and quotes the observed result.
6. Usage is limited. Keep reports structured and concise, and do not paste whole source files. After **each** work order, append a `## Progress checkpoint` to `agent-report.md` (work order, status, files touched, last test result). If the session is interrupted, the human resumes from the last checkpoint with a continuation work order.
7. Do **not** process the full 5.4 GB DBLP file. Do not call AWS or download anything. 10K/50K runs belong to Phase 15; the EMR run belongs to Phase 14/15.

## 1. Preconditions (orchestrator, read-only)

Record each item in `agent-report.md` under `## Preconditions`:

- `git status --short` is clean, and `git log -1 --oneline` matches `docs/agent-runs/phase-03-contracts-datasets/commit.txt`. (The hash-record commit sits on top; what matters is that the Phase 03 implementation commit is an ancestor.)
- Run the canonical test command. It must exit 0. Record the total test count as the baseline.
- Read Phase 03's `review-report.md`, every `repair-*.md` and `human-gate.md`. Under `## Carried from Phase 03`, list:
  - any finding still open. If a REQUEST_CHANGES-level finding is unresolved, stop and report to the human.
  - the notes that constrain Hadoop work: the canonical DOI rule and its shared test-vector TSV, DBLP cache validation, the Crossref missing-year rule, the stable ID functions, and the undirected pair canonicalization rule.
- From the dataset manifest under `datasets/` (use the file Phase 03 created; do not guess), locate the 1,000-work DBLP selection, the local `dblp.dtd`, and the cached Crossref, OpenAlex and ORCID records. Record each path and whether it exists locally. If one is missing, stop: `BLOCKED-ON dataset-engineer`.
- In `build.sbt`, check and record three things:
  - (a) the `hadoop` project depends on `core`;
  - (b) Hadoop client libraries are on the hadoop compile and test classpath, with versions from `project/Versions.scala`;
  - (c) sources and resources under `modules/hadoop/src/it/**` are compiled and run by `sbt check`. sbt 2 has no `IntegrationTest` configuration, so `src/it` must be wired in explicitly, for example as an extra Test source directory.

  If (a) or (c) fails, run Work Order A0. If (b) fails, stop and report it (a dependency change needs build-engineer plus dependency-updater, with human approval).

## 2. Work Order A0 — `build-engineer` (conditional)

Only the minimal change needed to pass precondition (a) and/or (c): add `dependsOn(core)` to `hadoop`, and/or wire `modules/hadoop/src/it/scala` and `src/it/resources` into the hadoop project's Test sources and resources so that `sbt check` runs them. Add a placeholder test only if one is needed to prove the wiring, and remove it afterwards. Do not add dependencies or change versions. If Hadoop's LocalJobRunner later needs JVM flags on JDK 17 (`--add-opens`), that is a separate A0 repair, triggered by hadoop-engineer's `BLOCKED-ON` with the exact error. Run the canonical command and report.

## 3. Work Order A1 — `domain-engineer` (conditional contracts)

Inspect `modules/core` and list which of these exist and with which fields:

- `PublicationTeam`: work ID, ordered author mentions, team size, year, provenance.
- `AuthorshipRecord` (the author-paper record): author mention key, work ID, position, year, provenance.
- `CoauthorPairCandidate`: canonical ordered mention keys, work ID, year, both positions, provenance.
- `ExactIdentifierEvidence`: identifier type, normalized identifier, linked record IDs, outcome EXACT_LINK | DUPLICATE | CONFLICT, provenance. This is evidence, not a MatchDecision.
- `RejectRecord` (schema `reject-v1`): run ID, job ID, source, source record ID or input byte offset, reason-code enum, message, raw excerpt bounded to 512 characters, schema version.
- `PipelineRunManifest`: run ID, snapshot, input paths plus checksums, config values used, per-job counters, output paths, row counts, code version.

For each missing record or field, add an immutable, versioned record with uPickle serialization and validation (provenance required, excerpt bound, valid reason code, canonical pair order). Do not change an existing frozen wire format in place: bump the schema version. Put a representative JSON wire example for each new record in the report, verbatim, so test-engineer can freeze it. Run the canonical command and report.

If nothing is missing, the orchestrator records "A1 not needed" with the evidence, and skips A1 and A2.

## 4. Work Order A2 — `test-engineer` (contract tests; only if A1 ran)

Paste A1's report. In `modules/core/src/test/**`, add serialization round-trip tests, frozen wire-example tests (using A1's examples verbatim) and validation-rejection tests for every record A1 added or changed. Run the canonical command. Report exact pass/fail counts. Do not edit production code. Route any defect to domain-engineer.

## 5. Work Order B — `hadoop-engineer` (the MapReduce pipeline)

Implement the pipeline in `modules/hadoop/**` (not `src/test/**`) against the Phase 03 contracts plus any added in A1. Paste the Preconditions, A0 and A1 results into this work order.

### 5.1 Scope boundary

The spec's J05/J06 (blocking and candidate features) are deferred to Phase 05 (see rule 0.3). Exact-identifier output is evidence, not a decision.

### 5.2 Reuse, don't reimplement

All DOI, arXiv, ORCID and title normalization, stable IDs and pair canonicalization call the `core` functions. There must be no second DOI rule in Hadoop code.

### 5.3 Job chain

Document the mapping to spec J01–J09 in `modules/hadoop/JOB-CONTRACTS.md`.

**HJ1 DblpExtractJob (spec J01)**
- Custom `DblpRecordInputFormat` / `RecordReader` (key = byte offset, value = one complete top-level record). It synchronizes on top-level record start tags. A record belongs to the split it starts in, and the reader reads past the split end to finish it. It never splits a record across mappers.
- Publication record types present in the snapshot: `article`, `inproceedings`, `proceedings`, `book`, `incollection`, `phdthesis`, `mastersthesis`. `www` records are not works. Count them, and emit `homepages/` person records (name variants plus any ORCID/URL) to a `dblp-person-aliases` side output for Phase 05.
- Parse with JDK StAX (no new dependency). DBLP named entities (for example `&uuml;`) must decode correctly using the **local** `dblp.dtd` or an entity table derived from it. Network entity resolution and XXE must be impossible.
- Extract the DBLP key, record type, title (inline markup flattened, multiline titles joined), ordered authors with the `orcid` attribute preserved, year, venue fields (journal/booktitle/volume/number/pages/crossref), `ee` links and URLs. Derive the DOI and arXiv ID from `ee` through `core`. Editors are not authors. DBLP homonym suffixes (for example `Wei Wang 0001` and `Wei Wang 0002`) are kept as distinct mention keys and never stripped.
- The reduce stage is keyed by DBLP key and detects duplicates: counter DUPLICATE, deterministic winner = lowest byte offset, the others go to a `duplicates` output. Emit `WorkRecord` and `AuthorMention` records. Malformed or unsupported records go to `rejects` (reject-v1).
- Expose the parsing as pure functions (record bytes in, typed result out) with a stable documented API, so that test-engineer can unit-test them.

**HJ2 SourceNormalizeJob (spec J02)**
- One normalizer per enrichment source (Crossref, OpenAlex, ORCID), wired with `MultipleInputs` or an equivalent, reading the Phase 03 cached files in their actual format (document the format).
- Map into the shared contracts without losing source-specific IDs. Preserve author identifiers, affiliations/institutions, topics, abstracts (per the contracts) and work identifiers. The reduce stage dedupes by source record ID. Malformed records go to rejects. Crossref records with a missing year follow the Phase 03 rule.
- Each normalizer is exposed as a pure function.

**HJ3 ExactIdentifierJob (spec J03 + J04)**
- The mapper emits `(identifierType|normalizedIdentifier) -> record reference` for DOI, arXiv, ORCID (including the DBLP author `orcid` attribute) and OpenAlex IDs.
- The reducer classifies each key:
  - EXACT_LINK: DBLP plus at least one external source.
  - DUPLICATE: several records of one source share the identifier.
  - CONFLICT: one identifier sits on several distinct DBLP works, or linked records carry disagreeing authoritative identifiers.

  Counters: DUPLICATE, CONFLICT, MISSING_KEY. The output is `ExactIdentifierEvidence`.

**HJ4 PublicationTeamPairJob (spec J07 + J08)**
- For every retained DBLP work, emit:
  - one `PublicationTeam` record (ordered mentions, team size n);
  - one `AuthorshipRecord` per mention;
  - for n >= 2, exactly n*(n-1)/2 `CoauthorPairCandidate` records in canonical order, each with work ID, year, both positions and provenance.
- Define team size precisely (distinct mentions after duplicate-name handling) and document it. A repeated author string in one list must not produce a self-pair (counter SELF_PAIR_SKIPPED), or a duplicated pair for the same work (the reduce stage dedupes; counter DUPLICATE_PAIR).
- Large teams: **never silently truncate and never emit a partial pair set for a work.**
  - Config `largeTeamThreshold` flags a team: counter LARGE_AUTHOR_TEAM, plus a `large-teams` output with work ID, n and pair count.
  - Config `pairExpansionHardCap` routes a team that exceeds it, whole, to an `oversize-teams` output: counter OVERSIZE_AUTHOR_TEAM. Its team and authorship records are still emitted, so team-size statistics stay correct.
  - The documented default hard cap must not trigger on the 1,000-work run or the planned 10K/50K runs.
- Partition by canonical pair key (hash partitioner), with a configurable reducer count. Do not aggregate weights: that is Spark's job in Phase 06.
- Expose the pair expander as a pure function.

**HJ5 GraphCandidateExportJob (spec J09)**
- Write Spark-ready outputs under `<outputRoot>/curated/<table>/snapshot=<snapshot>/run_id=<runId>/` for: works, author-mentions, authorship, teams, pair-candidates, exact-identifier evidence, institutions, topics, person-aliases, rejects.
- Partition authorship and pair-candidates by `year=YYYY`, with `year=unknown` for missing years.
- The driver writes `PipelineRunManifest` JSON and `counters.json` (every job's counters, from `Job.getCounters` after completion) next to the outputs.

### 5.4 Operational requirements

- **Driver.** One `PipelineDriver` (Hadoop `Tool`/`ToolRunner`) runs HJ1–HJ5, plus one `Tool` per job.
  - Paths are arguments: `file://` locally, `s3://` on EMR later.
  - Settings come through `-D` keys and the `core` config loader under `scholarchain.hadoop.*`. If the loader cannot take module-scoped keys: `BLOCKED-ON domain-engineer`.
  - The same jar must later run as `hadoop jar <jar> <DriverClass> ...` on EMR with no code change.
- **Idempotency.** The run ID comes from the Phase 03 function (timestamp-independent). The driver **refuses** to write into a run directory that already holds `_SUCCESS` plus a manifest, with a non-zero exit and a clear message. There is no overwrite flag and no delete code anywhere in the module. A rerun goes to a new output root.
- **Determinism.** Same inputs and config give logically identical output. Stable ordering within reducers, no reliance on HashMap iteration order, and no wall-clock values in records beyond fields the contracts define.
- **No mutable shared state.** No mutable singletons or static caches. Per-task state lives in `setup()`. Mapper/Reducer classes are top-level with public no-arg constructors.
- **Counters.** One enum group with at least: INPUT_RECORDS, ACCEPTED, REJECTED, DUPLICATE, CONFLICT, OVERSIZE_AUTHOR_TEAM, LARGE_AUTHOR_TEAM, EMITTED_PAIRS, MISSING_KEY, SCHEMA_FAILURE, SELF_PAIR_SKIPPED, DUPLICATE_PAIR, plus per-source input counts. (OVERSIZE_BLOCK arrives with blocking in Phase 05.)
- **Invariant.** EMITTED_PAIRS equals the sum of n*(n-1)/2 over non-oversize teams. The driver fails the run if it is violated.
- **Compression.** Map-output compression is on. The codec is configurable: a portable default locally (DefaultCodec or gzip, since native Snappy may be missing on macOS), with Snappy documented for EMR. Outputs are JSON Lines with configurable compression.
- **Partitioning.** Document the partitioner, key and default reducer count for each job.
- **Local execution** uses LocalJobRunner (`mapreduce.framework.name=local`) on `file://` paths under the module's `target/`, which is gitignored. Any other location needs a `.gitignore` change, which is build-engineer's.
- **No new dependencies.** No MRUnit (retired). A new library, scope change or JVM flag means `BLOCKED-ON build-engineer` (version values go through dependency-updater), with the exact error.

### 5.5 Module documentation

`modules/hadoop/JOB-CONTRACTS.md` covers, for every job:
- input format;
- input K/V, and mapper output K/V;
- combiner (if any) and partitioner;
- reducer input/output;
- counters and reject conditions;
- default reducer count;
- local and S3 path patterns;
- codec and idempotency rule;
- fixture names.

It also contains the pure-function API that test-engineer tests against, the HJ-to-J mapping (stating that J05/J06 are deferred to Phase 05), and the exact local run command.

### 5.6 Integration tests (hadoop-engineer; at least 15, in `modules/hadoop/src/it/**`)

Run them with LocalJobRunner on small synthetic fixtures in `modules/hadoop/src/it/resources/`, each labeled synthetic. Each test asserts concrete values:

- The record reader with a forced tiny `mapreduce.input.fileinputformat.split.maxsize`: every record read exactly once (compared with the expected key list), including records at file start and file end.
- HJ1, HJ2, HJ3, HJ4 and HJ5 each run end to end, asserting per-output row counts, specific counter values and reject contents.
- The full pipeline: output path layout, manifest contents, `counters.json`.
- The EMITTED_PAIRS invariant holds; a team above the hard cap goes whole to `oversize-teams` with its team record still present.
- Determinism: two runs into two roots give identical sorted content.
- Idempotency: the driver refuses an existing complete run directory with a non-zero exit.

### 5.7 Verification hadoop-engineer must run and paste (observed output only)

1. The canonical test command, with the test totals.
2. The local bounded pipeline on the real 1,000-work DBLP selection plus the Phase 03 cached Crossref, OpenAlex and ORCID records, into output root A, using the exact command in JOB-CONTRACTS.md.
3. The same command into root B, then a per-table content-equality check (sorted lines hashed), with the command and its result.
4. From root A, paste:
   - `counters.json`;
   - per-table row counts;
   - rejects by reason code;
   - the 3 largest teams (work ID, n, pairs);
   - one sample record per table (abstracts may be truncated).

## 6. Work Order C — `test-engineer` (unit and adversarial tests; at least 35)

Paste B's report, including the pure-function API from JOB-CONTRACTS.md. Write only to `modules/hadoop/src/test/**` (and `tests/**` if it is wired into `sbt check`; if it is not, use the module test tree). Fixtures go in `modules/hadoop/src/test/resources/`, labeled synthetic. Do not modify production code. Each test asserts concrete values.

**Unit tests (at least 23)**
- **Parser:** `&uuml;` decoded; a multiline title; inline markup flattened; a missing year; a missing title (rejected); a repeated author; the `orcid` attribute preserved; DOI and arXiv from `ee` (also run the shared DOI vector TSV through the Hadoop path); editors excluded; `www` homepages routed to aliases; each supported record type; an external-entity/XXE attempt not resolved.
- **Normalizers:** for each of Crossref, OpenAlex and ORCID, one happy path, one with missing fields and one malformed (rejected); source IDs preserved; the Crossref missing-year rule.
- **Exact identifiers:** EXACT_LINK, DUPLICATE, CONFLICT and MISSING_KEY classification.
- **Pair expander (hand-checkable):** n = 0, 1, 2, 3 and 5 with the exact expected pair lists; n = 120 gives 7,140; a duplicate name gives no self-pair and no duplicate pair; canonical order and provenance on every pair; team size definition.

**Adversarial tests (at least 12)**
- XML: an unclosed tag mid-record; CDATA; a literal `&lt;article` inside a title (not a record start); a UTF-8 BOM; an extremely long title; a record start tag exactly at a split boundary.
- Unicode: NFC and NFD forms of the same name; a Cyrillic confusable inside a Latin name, with the documented expected behavior; combining diacritics in titles.
- Missing fields: no authors, no year, no venue, each on its documented route.
- Duplicate IDs: the same DBLP key twice with different content (deterministic winner); the same DOI on two DBLP works (CONFLICT).
- Common names: DBLP homonyms `Wei Wang 0001` and `Wei Wang 0002` on one paper stay distinct and produce one pair, not a self-pair.
- A giant team: a pure-function count for n = 1,000 (499,500 pairs, nothing truncated). If B exposes a test harness, a LocalJobRunner-free check that n = 300 gives 44,850 pairs.

Leave a failing test that exposes a production defect in place and failing. Report it with the exact output and the owning agent. Run the canonical command. Report exact pass/fail counts.

## 7. Work Order D — `code-reviewer` (read-only)

Review the full Phase 04 diff and the affected files, in this order:
1. Correctness and data loss: record boundaries; pair completeness for large teams; dedupe rules; oversize routes never silently dropping data.
2. Contract drift from Phase 03: DOI rule duplicated, pair order, ID functions, schema versions.
3. Ownership violations against `docs/agents.md` section 1. In particular: hadoop-engineer writing in `src/test/**`, test-engineer touching production code, domain-engineer writing core tests, and any blocking or match-feature code in Phase 04.
4. Distribution mistakes: mutable shared state, driver-side collection, non-deterministic ordering, hardcoded paths, thresholds or bucket names.
5. Security: XXE and external entities, unbounded reject excerpts, delete code.
6. Tests that would pass even if the behavior were broken.
7. Report claims not backed by pasted output.

Verify every finding against the files. For each finding give severity, file:line, a concrete failing input, the impact and the owning agent. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES. No edits.

## 8. Repair loop

Group the findings by owner. Each owner gets only its findings plus the needed context, as `repair-NN.md` in the phase folder. The owner fixes and runs the canonical command. test-engineer reruns the affected tests, and code-reviewer rereads the diff. Repeat until APPROVE or APPROVE WITH NOTES, or escalate to the human after 2 rounds.

## 9. Evidence files (orchestrator saves)

In `docs/agent-runs/phase-04-hadoop-mapreduce/`:

- `agent-report.md`: Preconditions, Carried from Phase 03, every work order's final report verbatim in order, and the progress checkpoints.
- `test-report.md`: the A2 (if run) and C reports.
- `review-report.md`: every reviewer pass.
- `repair-NN.md`: each repair and its result.

Do not create `human-gate.md` or `commit.txt`.

Report contract: `docs/agents.md` section 10. hadoop-engineer's phase-specific items:
- job graph;
- K/V contracts (or a pointer to JOB-CONTRACTS.md);
- partition and reducer counts;
- counters;
- rejects by reason;
- output paths;
- maximum observed team size;
- largest pair expansion;
- test count delta;
- determinism result.

## 10. Gate (what the human checks)

- At least two real MapReduce jobs with reducers ran under LocalJobRunner on real DBLP records.
- The record reader handles split boundaries.
- Pair generation passes the 0/1/2/3/5/120 cases, and large teams are never truncated.
- Counters and rejects are visible in `counters.json` and the `rejects` table.
- Two runs give equivalent output.
- Every output record carries provenance.
- The test-engineer tests exist and pass, or each failure is routed.
- The reviewer verdict is APPROVE or APPROVE WITH NOTES.
- The `sbt check` total rises from the baseline by at least 50 (15 integration + 35 unit/adversarial), plus A2's tests if A2 ran.

## 11. Out of scope for this phase

- Blocking keys, match features, MatchCandidate, scores, thresholds and decisions (Phase 05).
- EMR execution and any AWS call.
- `scripts/**`.
- Terraform.
- Spark.
- `docs/**` outside this phase folder.
- `.gitignore` and version values.
- Network downloads.
- The full DBLP file.
