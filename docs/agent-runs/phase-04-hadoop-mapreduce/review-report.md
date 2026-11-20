# Phase 04 — code review reports

Each code-reviewer pass is pasted verbatim. The reviewer has read-only tools (Read, Grep, Glob).

## Review pass 1 — Work Order D (verbatim)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04 Work Order D. A read-only review of the full Phase 04 change set (A0, DS, A1, A1r, A2, DS-T, repair-01, B, C), following prompt.md section 7 plus the orchestrator's 12 items. The verdict comes back as this message.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (I have no shell; I used only Read, Grep and Glob)
TEST RESULTS: none run. The 614 passed / exit=0 result in agent-report.md and test-report.md is the orchestrator's observation, and I could not re-run it. What I could check from the files: the `test(` declarations in modules/hadoop/src number exactly 90 (src/it 28 = 16+5+3+4, src/test 62 = 15+12+7+9+19). That matches the per-suite counts B and C reported. The rootA manifest.json and counters.json match B's pasted 5.7 output line for line.
VALIDATION RESULTS: read-only verified verdict, below.

VERDICT: REQUEST_CHANGES. One blocking finding (F1). Everything else is non-blocking or a note. Once F1 is fixed (or compressed input is explicitly refused) and F2–F4 are fixed, I expect to recommend APPROVE WITH NOTES.

### Findings

**F1 — BLOCKING. Compressed DBLP input silently loses records.**
- Where: `modules/hadoop/src/main/scala/scholarchain/hadoop/InputFormats.scala` lines 55, 60–62 and 100–101.
- Cause: for a compressed file, `splitEnd = fs.getStart + fs.getLength`, which is the size of the compressed file. `nextPos` counts decompressed bytes from 0, and line 101 stops at `tagStart >= splitEnd`.
- Failing input: `dblp-synth.xml.gz` with about 10 KB compressed and 50 KB uncompressed. Every record whose start tag sits at a decompressed offset of 10 KB or more is never read. There is no reject, and no counter shows the loss beyond a lower INPUT_RECORDS.
- Impact: JOB-CONTRACTS.md line 102 documents "`.gz` input is read whole". The manifest's `update_method` names `dblp.xml.gz` as the upstream artifact. An EMR run on gzipped DBLP would drop roughly 80% or more of the records silently. No test covers compressed input (Grep for `.gz` and `getCodec` in src finds only production code and an output reader in ItSupport).
- Fix: when a codec is present, don't apply the `splitEnd` check (the file is one split), or refuse compressed input with a clear error. Add a regression test.
- Owners: hadoop-engineer for the fix. The regression test is test-engineer's in `src/test`, or hadoop-engineer's in `src/it`.

**F2 — NON-BLOCKING (fix in this repair round). HJ3 output depends on the order reducer values arrive in.**
- Where: `Hj3ExactIdentifier.scala` lines 60, 64 and 83. `refs` is not sorted, and `sources = refs.map(_.source).distinct` sets the order of `provenance.inputs`.
- Evidence: B's sample evidence record shows inputs in the order "dblp xml, openalex tree, crossref tree, dtd". That is neither sorted nor fixed.
- Scenario: on a cluster, values for one key arrive in segment-merge order, so two EMR runs can emit the same evidence with permuted `inputs`. `compare_roots.py` would then report the runs as not equal.
- Impact: breaks the §5.4 determinism requirement. JOB-CONTRACTS.md line 66 ("reducers sort their values before use") is false for HJ3. Local runs happened to agree.
- Owner: hadoop-engineer (sort `refs`/`sources`). Suggestion for test-engineer: a reducer test that feeds the same refs in two different orders.

**F3 — NON-BLOCKING. One setting that changes output is missing from provenance and the run ID.**
- Where: `PipelineDriver.scala` lines 147–157 (the config map) against `Hj1DblpExtract.scala` lines 36–37 and 65.
- Scenario: `-Dscholarchain.hadoop.dblp.max-record-bytes=1000` turns valid records into `oversized_record` rejects. The runId, manifest `config` and every `provenance.parameters` stay identical to a default run.
- Impact: breaks the CLAUDE.md provenance rule ("its parameters"). JOB-CONTRACTS.md lines 76–78 claim the resolved values go into the manifest, which is false for this key.
- Owner: hadoop-engineer.

**F4 — NON-BLOCKING. The code version is a constant default, so it is not traceable (item 4).**
- Where: `PipelineDriver.scala` line 66.
- Evidence: the real rootA and rootB runs used the default. The manifest records `"codeVersion":"scholarchain-hadoop-0.1.0"` for uncommitted code.
- Scenario: code changes, rerun on the same inputs and config. The result is the same runId and the same provenance codeVersion, so outputs from different code can't be told apart. A rerun into the same root is refused as a "duplicate" run.
- Comparison: the DS extractor records `6335b5a…+uncommitted`.
- Owner: hadoop-engineer. Make `--code-version` required, or derive it. Deriving it from the build (BuildInfo or git) would need build-engineer.

**F5 — NON-BLOCKING. Duplicate `workId` rows in curated `works`.**
- Where: `Normalizers.scala` lines 517–518 (ORCID gives `work:doi:<doi>`) and `Hj2SourceNormalize.scala` line 122. HJ2 dedupes by source record (the ORCID iD), not by work ID, and `Hj5Export.scala` line 112 keeps both rows.
- Scenario: two co-authors' ORCID envelopes both list DOI 10.1/x. Curated `works` then holds two rows with workId `work:doi:10.1/x` and different provenance.
- Impact: work-ID uniqueness breaks for Spark in Phase 06.
- I did not count occurrences in rootA, because the outputs are gzipped.
- Owner: hadoop-engineer.

**F6 — NON-BLOCKING / human decision. Missing-year Crossref and OpenAlex records (item 3, partly).**
- Where: `Normalizers.scala` lines 238–246 and 396–413.
- Behaviour: mentions are written, but no WorkRecord, because core `WorkRecord.year` is mandatory. In rootA, 7 records (MISSING_YEAR_KEPT=7) leave curated `author-mentions` rows pointing at workIds with no `works` row. Their titles survive nowhere in curated output, because `id-refs` is not exported.
- It is documented (JOB-CONTRACTS.md §8) and counted, so it is not silent. But it only partly meets N15's "kept".
- Abstract, venue name, pages, ee/url and record type are documented gaps (§8). They are acceptable "per the contracts", which have no such fields.
- Owners: domain-engineer for a contract change (an optional year or a v2 work record), only if the human wants these values kept. hadoop-engineer otherwise.

**F7 — NON-BLOCKING now, required before Phase 05 (item 2).**
- Where: `modules/hadoop/src/main/scala/scholarchain/hadoop/Model.scala` lines 57–126.
- `PersonAlias`, `DuplicateRecord`, `LargeTeam` and `OversizeTeam` are written to curated tables that other modules will consume. `PersonAlias` is a named Phase 05 input. Under CLAUDE.md ("consumes a contract defined in core"), they belong in core. Otherwise entity-resolution and Spark would have to depend on hadoop internals or redefine the shapes.
- `PersonAlias`'s contract also does not validate `aliasKey` or require non-empty names.
- Owner: domain-engineer (move them to core and freeze them). hadoop-engineer then imports them.

**F8 — NON-BLOCKING. An unclosed top-level record swallows the next record.**
- Where: `InputFormats.scala` lines 150–157. The reader scans for `</name>` with no stop at the next top-level start tag.
- Failing input: `<article key="A">…(no </article>)<article key="B">…</article>`. One `malformed_record` reject is written for A, and B is never counted or emitted.
- DBLP is well-formed, so the risk is low.
- Owner: hadoop-engineer.

**F9 — NOTE. Reject fidelity.**
- `RunContext.scala` line 96: when `Wire.write` fails, `Rejects.json` falls back to unvalidated `WirePickle.write`. An invalid reject-v1 row can then reach `rejects`.
- `Hj3ExactIdentifier.scala` line 108 sets a made-up `inputByteOffset = Some(0L)` even though `sourceRecordId` is already present.
- Owner: hadoop-engineer.

**F10 — NOTE. The input checksum misses some processed files.**
- `PipelineDriver.scala` line 102 hashes only `*.json`, but `JsonFileFilter` (`InputFormats.scala` line 217) also accepts extension-less files.
- An extension-less file in `works/` would be normalized but left out of the input checksum.
- Owner: hadoop-engineer.

**F11 — NON-BLOCKING (item 11). Stale manifest claim.**
- `datasets/dataset-manifest.yaml` line 238 still says `tests: "NOT RUN … pending"`, while repair-01 reports 83/83 OK. repair-01 says the sidecar is stale the same way.
- Owner: dataset-engineer.

**NOTES (no action required for the gate)**
- **Item 5:** the JOB-CONTRACTS.md command (lines 35–42) points at the same files as the command actually run. The manifest input checksums `3dfcab53…` and `2d864498…` confirm it, and bash joins backslash-newline inside double quotes. It reproduces the run, but should also name `--code-version` after F4.
- **CDATA, comment or processing-instruction text** holding a raw `<article ` inside a record that crosses a split boundary would make the later split emit a spurious record (`InputFormats.scala` lines 99–112). DBLP contains none.
- **Driver-side SHA-256** of every input (`PipelineDriver.scala` lines 85–95): on EMR with the full DBLP file, that is a serial read of about 5.4 GB on the driver. This is a Phase 15 concern.
- **`DblpParser.scala` lines 217–223:** the comment says "first occurrence keeps its ORCID", but the code takes the first ORCID from any occurrence.

### Orchestrator items
1. **PASS.** Exactly one `object OpenAlexId` (`Identifiers.scala:308`), one `enum OpenAlexEntity` (:283) and one `OpenAlexRef` (:291).
2. See F7.
3. See F6. This is documented gaps, not silent loss. The missing-year handling is only partly "kept".
4. See F4. `RejectRecord` carries `runId`, which joins to the manifest's inputs, checksums, code version and config. That matches the A1 field list but not the literal wording "every output record carries provenance". It needs an explicit human acceptance, or reject-v2 with provenance. Every other curated table carries a full `provenance`.
5. See notes. Equivalent.
6. **PASS.**
   - `DblpParser.scala` lines 63–74: JDK default StAX, `IS_SUPPORTING_EXTERNAL_ENTITIES=false`, `ACCESS_EXTERNAL_DTD/SCHEMA=""`, and a resolver that throws.
   - Lines 81–87: records containing `<!` declarations are rejected.
   - `DblpEntities` lines 32–33: only numeric character-reference entities are accepted.
   - No network or file access path exists. Excerpts are bounded (4096 bytes, then 512 code points, `PipelineRecords.scala` lines 194–196).
7. **PASS.**
   - No `.delete(`, `deleteOnExit`, `fullyDelete` or `rm -` in modules/hadoop (excluding target), and no remove, unlink or shutil in `tools/*.py`.
   - Files are created with `fs.create(p, false)` (`PipelineDriver.scala:302`).
   - No custom committer. The only cleanup is Hadoop's own `FileOutputCommitter` removing its `_temporary` directory, which is framework behaviour.
   - The refusal is at `PipelineDriver.scala` lines 197–202, with exit 2, tested in `DeterminismIdempotencyItSuite` lines 68–75.
8. **Mostly PASS.** No clock values (Grep finds `nanoTime` only in it-test temp directory names). `groupBy` results are either sorted or used only for lookups and `exists`. The exception is HJ3 (F2). `retrievedAt` comes from the envelope's source data.
9. **No production defects.**
   - Gaps 1, 2 and 4 are documentation and Phase 05 policy.
   - Gap 3 is design: §5.3 requires pure functions only for parsing, the normalizers and the pair expander.
   - Gap 5 comes from the test building a String with `getBytes`. Valid UTF-8 input can't contain a lone surrogate.
10. **The tests are meaningful.**
   - The harness drives the real reducers through `ReduceContextImpl` and `MultipleOutputs`, and asserts concrete rows and counters (for example `ExactIdentifierUnitSuite` lines 16–111).
   - The IT suites assert exact pair lists, the oversize route with its team and authorship rows still present (`PipelineItSuite` lines 231–259), and reader coverage with 20 or more splits.
   - Two blind spots: no test permutes value order (so F2 slips through), and none covers compressed input (so F1 slips through).
   - The harness depends on Hadoop internals and is fragile across upgrades, as C noted.
11. See F11.
12. **Recommendations (human decisions, not findings):**
   - Accept the lowercase snake-case wire names, which follow the repo's enum convention.
   - Accept the redundant `schema:"reject-v1"`, which is validated equal and harmless.
   - The reason list covers every reason the code uses. `pair_cap_exceeded` and `duplicate_record` are unused, because oversize teams and duplicates go to their own tables. A2 has already frozen them, so keep them or drop them only through a v2.
   - Accept `Mention*` beside the frozen researcher-keyed records, on the condition that Phase 05 defines the mapping.

### Section 7 checks
- **Pair completeness: PASS.**
  - `Hj4TeamPair.scala` lines 121–142: oversize teams go whole to `oversize-teams` with their team and authorship rows kept.
  - Lazy expansion.
  - The invariant is enforced at `PipelineDriver.scala` lines 241–257.
  - rootA: EMITTED 8420 = EXPECTED 8420 = exported 8420.
- **Contract drift: PASS.**
  - Hadoop has no DOI rule of its own. It uses `ExternalLinks.fromEe`, which delegates to `Doi.normalize`.
  - IDs come from `Ids.*`, and pairs from `MentionPairCandidate.build`.
  - No frozen wire format was changed in place.
- **Ownership: consistent with section 1 for every changed path.**
  - build.sbt: build-engineer. datasets/**: dataset-engineer. core main: domain-engineer. core test, hadoop src/test and tests/datasets: test-engineer. hadoop main, src/it, JOB-CONTRACTS.md and tools: hadoop-engineer.
  - No blocking, match-feature or score code (Grep of hadoop main: no matches).
  - Who actually wrote each file is UNVERIFIED, since I can't run git. DS records that guard-writes refused one cross-owner write.
- **Distribution: PASS** apart from F2 and the driver-hash note.
  - Mappers and reducers are top-level, with state set in `setup()`.
  - No static caches, no hardcoded paths, buckets or thresholds. Thresholds come from the core loader.
- **Claims not backed by output:**
  - JOB-CONTRACTS.md line 66 (F2) and lines 76–78 (F3) are inaccurate.
  - The A1 and A1r REPL-only claims were later backed by A2 tests.
  - No reported command result contradicts the files I read.

ASSUMPTIONS:
- The files I read are the current working tree.
- Phase 04 owns the compressed-input path because JOB-CONTRACTS.md documents it.
- Hadoop does not guarantee the order of values within a key on a cluster.

RISKS:
- I could not run tests or inspect the gzipped curated outputs, so I did not count how often F5 happens in rootA.
- The test-harness fragility across Hadoop versions remains.

MANUAL ACTION REQUIRED:
- Human decision on this verdict.
- Human decisions on item 12 and F6, and explicit acceptance of runId-only provenance for rejects (item 4).
- Approval of a Phase 05 prerequisite: F7 routed to domain-engineer.

BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: human decision on the verdict. Then a repair round grouped by owner:
- hadoop-engineer: F1–F5, F8–F10
- test-engineer: regression tests for F1 and F2
- domain-engineer: F7, and F6 if chosen
- dataset-engineer: F11

After that, the code-reviewer rereads the changes.

## Review pass 2 — after repair 02 round 1 (verbatim)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04 review pass 2, read-only. I re-checked the repair-02 round 1 changes for F1, F2, F3, F4, F5, F8, F9, F10, F11 and the DblpParser ORCID comment. I also looked for regressions, checked JOB-CONTRACTS.md accuracy, judged whether the new tests would fail if the fixes were reverted, checked ownership, and assessed the merge of institutions and topics.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have no shell and used only Read, Grep and Glob.
TEST RESULTS: none run. The reported totals (core 524, hadoop 114, 0 failed, marker exit=0) were observed by the orchestrator, and I could not verify them myself. What the files do show: there are 114 `test(` declarations in modules/hadoop/src. src/it has 38 (Pipeline 19, RecordReader 7, Determinism 8, ParserSafety 4), which is 28 + 10. src/test has 76, which is 62 + 14 in Repair02RegressionSuites.scala. tests/datasets/test_dblp_extract_subset.py has 23 `def test_`. The rootC manifest.json and counters.json match the reported figures. works: 1000 + 7468 − WORK_MERGED 173 = 8295. institutions plus topics: 274 + 300 − ENTITY_MERGED 118 = 456 = 250 + 206. HJ5: INPUT_RECORDS 27397 − ACCEPTED 27106 = 291 = 173 + 118. codeVersion is `6335b5a+uncommitted`, and `hadoop.dblp.max-record-bytes` is 16777216 in the config.
VALIDATION RESULTS: my verdict, from my own reading, is below.

### VERDICT: APPROVE WITH NOTES

F1, the blocking finding, is fixed and has regression tests at both levels. Every other routed finding is fixed. I found no regression that causes data loss or nondeterminism at the scope of this phase. The new findings below are all non-blocking.

### Routed findings

- **F1 (compressed input): FIXED.**
  - InputFormats.scala:72-79: when a codec is present, `splitStart = 0`, `splitEnd = Long.MaxValue`, and offsets count decompressed bytes.
  - :18-19: a file with a codec is not splittable.
  - :221-224: progress is `raw.getPos / fileLen` on the compressed stream, which is sound. It is 1 when finished.
  - PipelineDriver.scala:115-125: the charset sniff reads decompressed bytes.
  - Tests: RecordReaderItSuite:142-170 and CompressedDblpReaderUnitSuite (Repair02RegressionSuites.scala:92-136).
    - The unit test requires 500 keys and more than 400 records starting beyond the compressed length.
    - Under the old cut (stop when `tagStart >= compressed size`), those records would never be read. So by my reading both tests fail if the fix is reverted.
- **F2 (HJ3 value order): FIXED.**
  - Hj3ExactIdentifier.scala:65-66 sorts the value strings before parsing. :70 sorts `sources`. :55-57 and :87 sort `provenance.inputs`. The outcome depends only on sizes and `exists`, so it is order-independent.
  - The reducer audit in JOB-CONTRACTS.md §3 (lines 69-73) matches the code: Hj1:119, Hj2:93/98, Hj4:87/166/171, Hj5:128.
  - Hj3ValueOrderUnitSuite feeds values in a fixed order through the harness. `UnitSupport.reduce` uses a stable `sortBy(_._1)` (UnitSupport.scala:148), so value order is preserved. Before the fix, inputs followed the order in which sources first appeared, so this test would fail on the old code.
- **F3 (max-record-bytes in provenance): FIXED.**
  - PipelineDriver.scala:150-157 validates the value as an integer of at least 1, and :174 puts it in the config. It therefore reaches the manifest, the runId and `provenance.parameters` (RunContext.scala:43).
  - JobSupport.scala:69 passes the resolved value to the reader (InputFormats:61) and to the mapper (Hj1:37). The rootC manifest confirms it.
  - The IT (Determinism:83-114) checks config, runId and parameters only. It never launches a job with the override (acceptable). See N2 for a related gap.
- **F4 (code version): FIXED.** PipelineDriver.scala:67-70 makes it required and rejects whitespace. It is part of the runId (:192). Unit and IT tests cover a missing value, whitespace, keeping the value verbatim, and the runId changing.
- **F5 (duplicate workIds): FIXED.**
  - Hj5Export.scala:102-107 keys entity rows by ID. `Dedupe` (:143-201) picks the winner by `maxBy((retrievedAt, json))`, which is a total and deterministic order. `inputs` become the sorted, distinct union of every merged row's inputs, so no input is lost (:158).
  - Only ORCID produces `work:doi:` (Normalizers.scala:518). Crossref and OpenAlex use `work:crossref:` and `work:openalex:` (:178, :317). So the works merge never mixes sources.
  - Data that is dropped on purpose: the losing rows' title and year (ORCID works have empty `topicIds`, :536). JOB-CONTRACTS.md:181-182 documents this ("winner keeps its fields").
  - Tests: DedupeUnitSuite (4 orderings, the tie-break, a single row, institutions) and PipelineItSuite:430-455. The IT would fail before the fix, because there would be 2 rows.
  - Risks: see N1 (unbounded growth) and N4 (how timestamps are compared).
- **F8 (unclosed record): FIXED, with one residual limitation (N3).**
  - InputFormats.scala:132-151 (`startsTopLevelRecord`) pushes everything back. That is at most 34 bytes: up to 32 letters, the delimiter and `<`.
  - A non-letter delimiter `<` is pushed first, so it comes out last, when the stack is already empty. The stack therefore never holds more than 34 entries, well inside its 64 slots.
  - Byte order and the `lastPos` offsets are restored correctly, and EOF during the lookahead is safe.
  - :190-213: inside CDATA (`<` + `![CDATA[` matched over a 9-byte window) and inside comments, neither start tags nor end tags count. This also fixes an old case where a `</article>` inside CDATA ended the record early.
  - Split behaviour: when an unclosed record crosses a split boundary, the next record still belongs to the split its start tag begins in, so no record is lost or duplicated. This holds whether the next start tag is before or after `splitEnd`. The test at RecordReaderItSuite:197-202 covers 40-byte splits.
  - No real DBLP element uses a record-tag name inside a record (datasets/raw/dblp/derived/dblp.dtd:38-286), so the new cut cannot truncate valid records.
  - Tests: RecordReaderItSuite:172-202 and UnclosedRecordUnitSuite. Before the fix, B would be swallowed, so both tests would fail on the old code.
- **F9 (reject fidelity): FIXED.** RunContext.scala:100-103 now throws instead of writing an unvalidated row. Hj3:111 passes offset `None`. HJ2's offset 0 for whole-file envelopes is real and documented (JOB-CONTRACTS.md:209-210). Tests are in RejectsAndArgsUnitSuite.
- **F10 (input checksum filter): FIXED.** PipelineDriver.scala:89/108 and Hj2SourceNormalize.scala:140 use the same `JsonFileFilter`, so no processed file is left out of the checksum any more. There is no test.
  - Note: the JOB-CONTRACTS.md:134-135 claim that the checksum covers "exactly" the processed files is slightly too strong.
  - Hadoop's FileInputFormat also skips `_*` and `.*` names. So a file such as `_SUCCESS` or `.x.json` would be hashed but not processed.
  - This errs on the safe side for provenance.
- **F11 (stale manifest claim): FIXED.** datasets/dataset-manifest.yaml:238 now records 23 tests and "Ran 83 tests, OK". The 23 matches the file.
- **DblpParser ORCID comment: FIXED.** DblpParser.scala:217-221 now matches the code: the ORCID kept is the first one found on any occurrence.

### Item for you to assess: merging institutions and topics by ID
In principle the merge is sound, and I support it.
- `Institution` and `Topic` (core Records.scala:183-220) have no field that ties them to one work. The work link existed only in the HJ2 sort key `"$id|$workId"` (Normalizers.scala:255/268/418/426), which was never exported. So no relationship is lost.
- IDs are either source IDs (`institution:openalex:`, `topic:openalex:`) or digests of the name (`:name:`), so rows with the same ID describe the same entity.
- The only open issue is cost; see N1.

### JOB-CONTRACTS.md accuracy
These parts are accurate:
- determinism and the reducer audit (§3)
- config and provenance (§4, lines 80-87)
- compressed input (lines 111-115)
- unclosed records (lines 116-118)
- the merge rule (lines 179-187)
- gaps 1, 2 and 4 (lines 204-208)

Exceptions are N2, N3 and the F10 wording above.

The run command (lines 35-43) is the same as the rootC/rootD command in its flags and paths. It differs only in quoting:
- The command actually run used `\"$(git rev-parse --short HEAD)+uncommitted\"`.
- The documented form ends `"$(…)+uncommitted""`. That closes and reopens the sbt argument's quotes, so the expansion is unquoted.
- Both produce `6335b5a+uncommitted`, because the git output has no whitespace. The rootC manifest confirms that value.
- Recommendation: copy the escaped form into the document.

### New findings (all non-blocking)
- **N1. MEDIUM. Unbounded provenance growth and reducer memory for skewed entity keys.**
  - Where: Hj5Export.scala:128 and :152-158.
  - Input that triggers it: a Crossref subject such as "Computer Science Applications" (`topic:name:<digest>`) or a large OpenAlex institution that appears in N works.
    - The reducer loads, sorts and parses all N rows in memory.
    - It emits one row with about N `InputRef`s (one envelope file per work, Normalizers.scala:333/481).
  - Impact: at the 1K sample this is harmless (topics 300 → 206). At full scale, one row can reach megabytes, the reducer can run out of memory, and downstream size limits can be hit (for example, the per-item cap in a key-value store in Phase 07).
  - The tree-level input (`<source>/works`, whose checksum covers every file) already pins provenance. So listing every file is redundant.
  - Suggested fix: keep the tree input and the winner's own envelope, and record a count or digest of the merged files. If that changes the contract, it needs domain-engineer.
  - Owner: hadoop-engineer. Deadline: before Phase 07 or Phase 15 full-scale runs.
- **N2. LOW. `export-reducers` value recorded in the manifest can differ from the value the job uses.**
  - Where: PipelineDriver.scala:159-163 records `toIntOption.filter(_ >= 1).getOrElse(1)`, but Hj5Export.scala:205 reads `conf.getInt("scholarchain.hadoop.export-reducers", 1)` directly.
  - Inputs that trigger it:
    - `-Dscholarchain.hadoop.export-reducers=0`: the manifest says "1", but HJ5 runs as a map-only job (no merge, raw composite keys).
    - `=abc`: the manifest says "1", but the job throws NumberFormatException.
  - The code is older than this round. However, JOB-CONTRACTS.md:85-87 now claims the recorded values are the resolved ones.
  - Fix: use `s.run.config("hadoop.export-reducers")`, or refuse invalid values the way max-record-bytes does.
  - Owner: hadoop-engineer.
- **N3. LOW. Residual F8 cases are undocumented.**
  - An unterminated CDATA or comment inside a record swallows everything until EOF, the hadoop-engineer report says. The same report says this is "documented", but JOB-CONTRACTS.md never mentions it (a Grep for "unterminated" or "EOF" finds nothing).
  - An unterminated attribute quote in the start tag (InputFormats.scala:178-186 has no top-level cut) also swallows the next record's start tag. Example input: `<article key="A><author>X</author></article>\n<article key="B">…</article>`, where B is lost.
  - With an uncompressed file split mid-record, the result can differ from a single-split read.
  - DBLP is well-formed, so the risk is low.
  - Fix: document both cases in §6 HJ1, or apply the cut in the start-tag phase as well.
  - Owner: hadoop-engineer.
- **N4. LOW. "Latest retrievedAt" is compared as a string, not as an instant.**
  - Where: Hj5Export.scala:157 (and HJ2's existing rule at Hj2SourceNormalize.scala:98).
  - Core accepts any `Instant.parse` value (Records.scala:101-103). For example, `2026-01-01T00:00:00Z` beats `2026-01-01T00:00:00.500Z`, because 'Z' > '.'.
  - The result is still deterministic, but the "latest" wording is not exact. The fetchers appear to write one fixed format.
  - Fix: compare `Instant.parse` values, keeping the JSON text as the tie-break.
  - Owner: hadoop-engineer.

### Would the tests fail if the fixes were reverted?
Neither agent ran the new tests against the pre-fix code. My judgement from reading:
- **Would fail on revert:**
  - F1 (unit and IT): records past the compressed length would be missing.
  - F2 (unit and IT): pass 1 found the inputs in an unsorted order.
  - F3 IT: the config key would be missing.
  - F4 and F9 (unit and IT).
  - F5 IT: there would be 2 rows.
  - F8 (unit and IT): B would be swallowed.
- **Dedupe unit tests:** they target new code and would not compile without it.
- **Not tested:** F10, and the job actually honouring a max-record-bytes override.

### Ownership
Every changed path belongs to the agent that reported changing it:
- hadoop-engineer: modules/hadoop/src/main/**, src/it/** and JOB-CONTRACTS.md
- test-engineer: modules/hadoop/src/test/** (Repair02RegressionSuites.scala only)
- dataset-engineer: datasets/dataset-manifest.yaml

AdversarialSuite.scala:70-80 matches the test the report says was left unedited. Who actually wrote each file is UNVERIFIED, because I can't run git and modules/hadoop/ is untracked as a whole.

ASSUMPTIONS:
- The files I read are the current working tree.
- Hadoop does not guarantee the order of values within a key.
- The fetchers write `retrieved_at` in one fixed ISO format.
- ORCID iD 0000-0002-1694-233X in the new fixture is a public documentation example, as the earlier fixtures claim.

RISKS:
- N1 at full scale.
- I did not count rows in the gzipped curated outputs myself. I relied on the orchestrator's independent uniqueness count and on the counter arithmetic above.
- The test harness still depends on Hadoop internals.
- The earlier note about a raw `<article` inside CDATA across a split boundary still applies to the start of a split.

MANUAL ACTION REQUIRED:
- Human decision on this verdict.
- Accept or reject the institutions and topics merge. I recommend accepting it, with N1 tracked for full scale.
- Decide F6, F7, runId-only reject provenance, and A1's wire choices, which are held for the gate.
- Optionally route N1–N4 to hadoop-engineer, now or as a Phase 05/07 prerequisite.

BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: human decision on the verdict, recorded in docs/agent-runs/phase-04-hadoop-mapreduce/human-gate.md.
