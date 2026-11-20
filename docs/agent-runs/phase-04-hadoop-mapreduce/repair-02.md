# Phase 04 repair 02 — review pass 1 findings (round 1 of at most 2)

Source: review-report.md, review pass 1 (REQUEST_CHANGES; F1 blocking). Routed by the orchestrator under prompt.md §8, grouped by owner. Each owner receives only its findings plus the context it needs.

## Routing

| Owner | Findings | Order |
|---|---|---|
| dataset-engineer | F11 (stale `tests` claim in the manifest and sidecar) | 1 (small; runs first so no two agents overlap) |
| hadoop-engineer | F1 (blocking), F2, F3, F4, F5, F8, F9, F10; JOB-CONTRACTS.md corrections (lines 66, 76–78, the run command with `--code-version`) and C's documentation gaps 1, 2, 4; regression tests in `src/it` for F1, F2, F3, F5, F8; rerun of the real 1K pipeline into new roots C and D, with an equality check | 2 |
| test-engineer | unit regressions in `src/test`: an HJ3 reducer value-order permutation test (F2) and a compressed-input record-reader test (F1); rerun of the affected suites; canonical run | 3 |
| code-reviewer | reread of the repair diff | 4 |

## Not routed in this round (held for the human gate)
- **F6** (missing-year Crossref/OpenAlex records produce mentions but no WorkRecord). Needs a contract decision (optional year or a v2 work record), so it is a human decision. Documented and counted today.
- **F7** (`PersonAlias`, `DuplicateRecord`, `LargeTeam`, `OversizeTeam` defined in hadoop rather than core). The reviewer rates it non-blocking now and required before Phase 05. It is a cross-module contract move (domain-engineer, then hadoop-engineer, then test-engineer), and the reviewer asks for human approval. Held for the gate.
- **Item 4 / reject provenance.** `RejectRecord` links to provenance only through `runId`. Needs explicit human acceptance, or reject-v2.
- **Item 12.** A1's open wire choices (lowercase names, the redundant `schema` field, the reason list, `Mention*` beside the researcher-keyed records). Human decisions; the reviewer recommends accepting all four.
- Reviewer NOTES: CDATA/comment `<article` across a split boundary; driver-side SHA-256 cost at full scale (Phase 15); a `DblpParser` ORCID comment mismatch. hadoop-engineer may fix the comment mismatch in passing.

## Results

### F11 — dataset-engineer (final report, verbatim)

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 04 repair 02, F11. Replace the stale "NOT RUN ... pending" extractor tests claim in the manifest and the provenance sidecar with the observed test result. Run validate_manifest.py.
FILES CREATED: none
FILES MODIFIED: /Users/dhruv_mehta/Desktop/ScholarChain/datasets/dataset-manifest.yaml (line 238, the extractor `tests` field). It now names tests/datasets/test_dblp_extract_subset.py (23 tests) and the observed run: 83 tests OK for the whole suite, run 2026-11-20. It also states that the sidecar has no tests field and was left unchanged.
COMMANDS RUN:
- python3 -B -I -m unittest discover -s tests/datasets -p 'test_*.py' (exit not captured; output was "Ran 83 tests in 2.433s / OK")
- python3 -B -I -m unittest discover -s tests/datasets -p 'test_dblp_extract_subset.py' (exit not captured; output was "Ran 23 tests in 0.634s / OK")
- python3 -B -I -m unittest -v tests/datasets/test_dblp_extract_subset.py (exit not captured; output was "Ran 1 test ... FAILED (errors=1)"). I passed a file path where unittest expects a module name, so this was my invocation error and not a test failure. The discover run above is the valid one.
- python3 datasets/scripts/validate_manifest.py (exit not captured; output was "checks passed: 244; failed: 0")
- grep and sed reads, plus one inline python3 string replacement on the manifest.
TEST RESULTS: unittest discover over tests/datasets: Ran 83 tests, OK. Extractor file alone: Ran 23 tests, OK. sbt was not run, as instructed.
VALIDATION RESULTS: validate_manifest.py summary line: "checks passed: 244; failed: 0".
ASSUMPTIONS: The sidecar claim the reviewer flagged does not exist in the current file. I grepped datasets/raw/dblp/derived/dblp_selection_1000.xml.provenance.json for tests, pending and NOT RUN and got no match. I also read the whole file: it has no tests key. The stale text was only in the manifest.
RISKS: The sidecar sits under datasets/raw/ (gitignored). It records the output hash, and the script uses it for the "up to date" check. I did not edit it, which keeps the output XML, its hash and the sidecar untouched. If the reviewer saw a tests claim elsewhere in a sidecar, point me to it.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: code-reviewer re-check of F11, then human acceptance.

**Orchestrator note (F11):** the reviewer's sidecar remark came from repair-01's own wording ("The extractor's sidecar and the manifest `tests` field still say the tests are pending"). dataset-engineer found no tests field in the sidecar. Only the manifest was stale.

### F1–F5, F8–F10 — hadoop-engineer (final report, verbatim)

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 04 repair-02 round 1. Fix review findings F1, F2, F3, F4, F5, F8, F9, F10 and the ORCID comment note. Update JOB-CONTRACTS.md and add regression ITs. Rerun the real 1K pipeline into new roots rootC and rootD and compare them. F6 and F7 were not touched, as instructed.

FILES CREATED
- modules/hadoop/src/it/resources/fixtures/orcid/p4-shared-doi.json: a second ORCID envelope sharing DOI 10.1000/synth.alpha with p1.json (F5 test).
- modules/hadoop/src/it/resources/fixtures/dblp-unclosed-synth.xml: a synthetic DBLP file with an unclosed record (F8 test).

FILES MODIFIED (all under modules/hadoop)
- src/main/.../InputFormats.scala: F1 and F8.
- src/main/.../PipelineDriver.scala: F3, F4, F10 and the compressed-file charset sniff.
- src/main/.../JobSupport.scala: sets max-record-bytes in the job conf from the resolved value.
- src/main/.../RunContext.scala: F9 and a `maxRecordBytes` accessor.
- src/main/.../Hj3ExactIdentifier.scala: F2 and F9.
- src/main/.../Hj5Export.scala: F5 (the new `Dedupe` object, entity-keyed mapper).
- src/main/.../Counters.scala: new counters WORK_MERGED and ENTITY_MERGED.
- src/main/.../DblpParser.scala: the ORCID comment now matches the code.
- JOB-CONTRACTS.md: see the item 9 note below.
- src/it/.../ItSupport.scala: passes `--code-version`, lists the two new fixtures.
- src/it/.../PipelineItSuite.scala, RecordReaderItSuite.scala, DeterminismIdempotencyItSuite.scala: regression tests and updated expectations.

FINDINGS, FIXES AND REGRESSION TESTS
- F1 (blocking). InputFormats.scala, `initialize`: for a compressed file the reader sets `splitEnd = Long.MaxValue` and `splitStart = 0`. The file is one split, read whole, and keys are decompressed offsets.
  - Progress uses `raw.getPos / fileLen`.
  - `sniffCharset` in PipelineDriver.scala now reads the decompressed bytes.
  - Tests in RecordReaderItSuite:
    - "F1: a gzipped file much larger decompressed is read whole": 400 records, compressed size times 5 is below plain size, the last record starts past the compressed length, `isSplitable` is false.
    - "F1: HJ1 over the gzipped file writes one works row per record (400) and no reject".
- F2. Hj3ExactIdentifier.scala:
  - `refs` are built from the sorted value strings.
  - `sources` are sorted.
  - `sortedInputs` orders `provenance.inputs` by (inputId, version, checksum).
  - Test: PipelineItSuite "F2: provenance.inputs of exact-identifier evidence are sorted". For alpha the inputs are crossref/works, dblp/dblp-synth.xml, dblp/dblp.dtd, openalex/works, orcid/works.
  - Audit of reducers (JOB-CONTRACTS.md §3 lists it). I checked HJ1 `DblpReducer`, HJ2 `NormalizeReducer`, HJ4a `TeamReducer`, HJ4b `PairReducer` and HJ5 `ExportReducer`. Each sorts its values and picks winners by explicit keys, so no change was needed. HJ3 was the only one with the problem. HJ5's new merge is order-independent.
- F3. PipelineDriver.scala: `hadoop.dblp.max-record-bytes` is now in the config map. It is validated as an integer >= 1, otherwise the run is refused. So it appears in the manifest `config`, in `provenance.parameters` and in the runId.
  - JobSupport.newJob passes the resolved value to the reader and mapper.
  - I found no other setting the tasks read. `hadoop.export-reducers` was already in the config.
  - Test: DeterminismIdempotencyItSuite "F3: max-record-bytes is in the manifest config and changes the run ID". It asserts the default value 16777216 and the override value 1000, different runIds, parameters on works rows, and that "zero" is refused. No job launch.
- F4. `--code-version` is required (`need("--code-version")`). A missing value gives "missing --code-version" and exit 2 with nothing written. A whitespace token is refused.
  - JOB-CONTRACTS.md §2 command now ends with `--code-version "$(git rev-parse --short HEAD)+uncommitted"`. It says to use exactly that form (the plain short SHA for a clean committed tree).
  - Tests: "F4: a missing --code-version is refused with exit 2 and a clear message" and "F4: the code version is part of the run ID".
- F5. Hj5Export.scala:
  - The export mapper keys `works`, `institutions` and `topics` by entity ID.
  - `Dedupe` merges the rows of one ID. The winner has the latest `provenance.retrievedAt`, ties go to the larger JSON text. The winner keeps its fields and parameters. `provenance.inputs` become the sorted union of all merged rows' inputs, so no input is lost.
  - Counters: WORK_MERGED (works) and ENTITY_MERGED (institutions and topics).
  - Uniqueness audit on rootA: works 8468 rows / 8295 distinct (173 duplicates); institutions 274 / 250; topics 300 / 206. The other tables were already unique: author-mentions 4711/4711, authorship 3928/3928, teams 1000/1000, exact-identifier-evidence 296/296, person-aliases, large-teams, oversize-teams (all 0). pair-candidates are unique by (pair, work) by construction in HJ4b.
  - I went beyond works and also merged institutions and topics. They are keyed by ID in the records but were written per work. This is a scope decision for you to accept.
  - Tests in PipelineItSuite:
    - "F5: two ORCID envelopes sharing a DOI give ONE works row; no provenance input is lost": one row, title from the later envelope, both p1.json and p4-shared-doi.json present, inputs sorted, WORK_MERGED = 1, all workIds distinct.
    - "F5: institutions and topics are unique by their ID too".
- F8. InputFormats.scala `startsTopLevelRecord` plus a multi-byte pushback stack. While reading a record body, a top-level start tag outside CDATA and comments ends the record. The tag is left unread and the next call reads it. The truncated record goes to the parser, which rejects it as `malformed_record`.
  - Tests in RecordReaderItSuite: "F8: an unclosed record does not swallow the next one; it is cut and rejected" (keys un/A, un/B, un/C with exact offsets; the parser gives MalformedRecord for A and Right for B and C) and "F8: the same holds when a tiny split cuts the unclosed record".
  - The CDATA and comment handling was needed because AdversarialSuite has a case with a raw `<article ` inside CDATA (see "src/test tests affected").
- F9. RunContext.scala `Rejects.json` now throws `IllegalStateException` on an invalid reject-v1 row. The unvalidated fallback is gone. The made-up `inputByteOffset = Some(0L)` is removed from HJ3 (the sourceRecordId is present).
  - I left HJ2's `Some(0L)` in place, because it is the real offset 0 of a whole-file envelope. It is documented.
  - No separate test: it is reached only through a broken-invariant path. The reject rows in the passing suites are all validated.
- F10. `RunSetup.JsonFilter` (a `JsonFileFilter`) is used for both the checksum listing and the job's input filter. No dedicated test.
- DblpParser comment: it now says the ORCID kept is the first one found on any occurrence of the author string.
- JOB-CONTRACTS.md (item 9):
  - Determinism line corrected, with the reducer audit.
  - Config section corrected (F3).
  - F1 compressed-input and F8 unclosed-record behaviour documented.
  - F5 merge rule and unique-ID audit documented.
  - F10 filter documented.
  - Gaps 1, 2 and 4 added to §8: names code-point exact with no NFC/NFD folding; no confusable folding (Phase 05 policy); a DOI is derived from `ee` only when `ee` is a DOI URL.
  - The §8 institutions note is updated.

IT job launches by suite
- RecordReaderItSuite: 3 (reader job, HJ1 tool on the plain fixtures, HJ1 tool on the gz file). The F1 and F8 reader tests drive the reader directly with no job.
- PipelineItSuite: 6 (unchanged; the new F2 and F5 tests reuse beforeAll).
- DeterminismIdempotencyItSuite: 12 (unchanged; the F3 and F4 tests launch no job).
- ParserSafetyItSuite: unchanged (0).

COMMANDS RUN
- `sbt "hadoop/scalafmt; hadoop/Test/scalafmt; hadoop/Test/compile"`: success.
- `sbt "hadoop/test"`: first run had 2 failures. Both were fixed: my own PipelineItSuite constant-codeVersion assertion, and the AdversarialSuite CDATA case (see below).
- `sbt "hadoop/testOnly AdversarialSuite RecordReaderItSuite PipelineItSuite"`: Passed 45, Failed 0.
- Two `sbt "hadoop/Test/runMain scholarchain.hadoop.PipelineDriver ..."` runs (rootC, rootD): exit=0 each.
- `python3 -I modules/hadoop/tools/compare_roots.py rootC rootD`: exit 0.
- `sbt "hadoop/scalafmt; hadoop/Test/scalafmt"` before the final check: reformatted nothing.
- Canonical `sbt check; echo "exit=$?" > .claude/.last-test-run`: run once at the end. My first attempt was prefixed with other commands, so the guard blocked it before anything ran. It is the only run on record.

TEST RESULTS
- Canonical check: `[info] markTestRun: wrote /Users/dhruv_mehta/Desktop/ScholarChain/.claude/.last-test-run` and `[success] elapsed time: 128 s`.
- I read the marker file only through the sbt `markTestRun` message; I did not cat it.
- Totals quoted from the output: `[info] Passed: Total 524, Failed 0, Errors 0, Passed 524` (core) and `[info] Passed: Total 100, Failed 0, Errors 0, Passed 100` (hadoop). That is 624 against the 614 baseline, +10 new ITs (hadoop src/it 28 to 38).
- All 62 src/test tests are green, none edited.
- Per-suite hadoop lines:
  - RecordReaderItSuite: 7 total, 0 failed
  - PipelineItSuite: 19, 0 failed
  - DeterminismIdempotencyItSuite: 8, 0 failed
  - AdversarialSuite: 19, 0 failed
  - ParserSafetyItSuite: 4, 0 failed
  - NormalizerUnitSuite: 12, 0 failed
  - DblpParserUnitSuite: 15, 0 failed
  - ExactIdentifierUnitSuite: 7, 0 failed
  - PairExpanderUnitSuite: 9, 0 failed

VALIDATION RESULTS: real 1K rerun (item 11)

Commands (from the repo root; CODE=`$(git rev-parse --short HEAD)+uncommitted`, which resolved to `6335b5a+uncommitted`):
```
sbt "hadoop/Test/runMain scholarchain.hadoop.PipelineDriver --dblp $PWD/datasets/raw/dblp/derived/dblp_selection_1000.xml --dtd $PWD/datasets/raw/dblp/derived/dblp.dtd --crossref $PWD/datasets/raw/crossref/works --openalex $PWD/datasets/raw/openalex/works --orcid $PWD/datasets/raw/orcid/works --output $PWD/modules/hadoop/target/runs/rootC --snapshot dblp-1k --code-version \"$(git rev-parse --short HEAD)+uncommitted\""
```
The same command ran with `rootD` as the output. Both exited 0, all 6 jobs success=true.
- Run ID, identical in C and D: `run:2bfe14820cbceab4360c4c2c8df885190e3cce3ba842357afd4437a109920d2d`.
- Compare command: `python3 -I modules/hadoop/tools/compare_roots.py modules/hadoop/target/runs/rootC modules/hadoop/target/runs/rootD`
```
author-mentions              rows A=  4711 B=  4711 sha256(A)=84636ef429bb7e3c sha256(B)=84636ef429bb7e3c EQUAL
authorship                   rows A=  3928 B=  3928 sha256(A)=fa745972b7e0a3a6 sha256(B)=fa745972b7e0a3a6 EQUAL
exact-identifier-evidence    rows A=   296 B=   296 sha256(A)=e534fdbaa9dab184 sha256(B)=e534fdbaa9dab184 EQUAL
institutions                 rows A=   250 B=   250 sha256(A)=661830fb9c8f62ee sha256(B)=661830fb9c8f62ee EQUAL
pair-candidates              rows A=  8420 B=  8420 sha256(A)=29d8809b7bae7150 sha256(B)=29d8809b7bae7150 EQUAL
teams                        rows A=  1000 B=  1000 sha256(A)=78a48c538c6ec67f sha256(B)=78a48c538c6ec67f EQUAL
topics                       rows A=   206 B=   206 sha256(A)=2e56d1c664d0fac7 sha256(B)=2e56d1c664d0fac7 EQUAL
works                        rows A=  8295 B=  8295 sha256(A)=f9dcb97ab1362200 sha256(B)=f9dcb97ab1362200 EQUAL
RESULT: ALL TABLES EQUAL
```
- Per-table row counts for rootC (equal to D): works 8295, author-mentions 4711, authorship 3928, teams 1000, pair-candidates 8420, exact-identifier-evidence 296, institutions 250, topics 206, person-aliases 0, rejects 0, duplicates 0, large-teams 0, oversize-teams 0.
- HJ5 counters in rootC: `{'ACCEPTED': 27106, 'ENTITY_MERGED': 118, 'INPUT_RECORDS': 27397, 'WORK_MERGED': 173}`. Pair invariant: EMITTED 8420 = EXPECTED 8420, HJ4b ACCEPTED 8420.
- Works uniqueness check (Python with gzip and json over `rootC/curated/works/**/part-*.gz`, run with `python3 -I -`): rootC 8295 rows, 8295 distinct workIds; rootD 8295 rows, 8295 distinct workIds. Institutions 250/250 distinct, topics 206/206 distinct.
- Evidence row with sorted `provenance.inputs` (exact-identifier-evidence): inputIds in order are `crossref/works`, `dblp/dblp.dtd`, `dblp/dblp_selection_1000.xml`, `openalex/works`. The DBLP checksum is 3dfcab53e2764639f8777707fe55f2c3bc067e040282c63c24cdc72341868db6 and the DTD checksum is 2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6. Its codeVersion is `6335b5a+uncommitted`, and its parameters include `hadoop.dblp.max-record-bytes: 16777216`.
- Merged works row (`work:doi:10.1002/adma.201600602`): `provenance.inputs` are two ORCID envelope files plus `orcid/works`, sorted. 165 works rows carry more than 2 inputs.
- Row-count differences from rootA (same data and inputs, new code):
  - works 8468 to 8295 (-173): duplicate ORCID `work:doi:` rows merged. Equals WORK_MERGED = 173.
  - institutions 274 to 250 (-24) and topics 300 to 206 (-94): merged by ID. These two sum to ENTITY_MERGED = 118.
  - All other tables are identical to rootA: author-mentions 4711, authorship 3928, teams 1000, pair-candidates 8420, evidence 296.
  - The rootA and rootC runIds differ because the codeVersion and config (now including max-record-bytes) differ.

SRC/TEST TESTS AFFECTED
- None remain failing. AdversarialSuite "a literal <article inside a title is text, and is not a record start" (a test-engineer test, not edited) failed after my first F8 change. A raw `<article key="fake/4">` inside a CDATA section was read as a new record:
  ```
  Obtained List("k/1","k/3","fake/4")  expected List("k/1","k/3")
  ```
  I fixed the reader rather than the test: inside `<![CDATA[...]]>` and `<!--...-->` neither start nor end tags count. The test passes unmodified.
- Behaviour you may want a unit test for (test-engineer):
  - a reducer fed the same HJ3 refs in two orders gives the same row (F2);
  - `Dedupe.rows` winner and merge rule;
  - `Rejects.json` throws on an invalid reject;
  - `PipelineArgs.parse` without `--code-version`.

ASSUMPTIONS
- Clean tree uses the plain short SHA; with local changes, "+uncommitted" (documented).
- The max-record-bytes `-D` value is the only reader-side setting that changes output.
- Merging institutions and topics in the export is within the F5 request ("check every other ID-keyed table is unique").

RISKS
- Compressed DBLP is read by a single mapper, so HJ1's map phase on full gzipped DBLP on EMR has no parallelism. This is documented; the uncompressed file is preferred.
- An unterminated CDATA or comment inside a record swallows the rest until EOF (documented). DBLP has none.
- Merged `provenance.inputs` for popular institutions or topics grow with the number of works that mention them. This is bounded at 1K but could be large at full scale.
- I did not run the F1/F8 tests against the pre-fix code, so they have not been shown to fail without the fix. The F1 test asserts the last record starts beyond the compressed length, which only holds if the cut is gone.
- HJ2 still writes reject `inputByteOffset = Some(0L)` for whole-file envelopes.
- F6, F7 and F11 are untouched. The test-engineer reducer-harness fragility is unchanged.

MANUAL ACTION REQUIRED: human review and decision. This agent did not commit. Decide on F6 and F7, and on the extra merge of institutions and topics (F5 scope).
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: test-engineer adds unit regressions in src/test (F2 value-order, Dedupe, Rejects.json, required `--code-version`), then code-reviewer review pass 2, then human acceptance.

**Orchestrator verification (observed):** marker `exit=0`. `compare_roots.py rootC rootD` → `RESULT: ALL TABLES EQUAL`. An independent gzip/json count over `rootC/curated/{works,institutions,topics}`: works 8295 rows / 8295 distinct; institutions 250/250; topics 206/206. Grep for `.delete(`, `deleteOnExit`, `fullyDelete` in `modules/hadoop/src`: no matches.

### Unit regressions — test-engineer (final report, verbatim)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 04 repair-02 round 1, test-engineer part. Add unit regressions for review findings F1, F2, F4, F5, F8 and F9 under modules/hadoop/src/test/**. No production or src/it edits, and no LocalJobRunner.

FILES CREATED:
- /Users/dhruv_mehta/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/Repair02RegressionSuites.scala: 5 suites, 14 tests. Top-level names are Hj3ValueOrderUnitSuite, CompressedDblpReaderUnitSuite, UnclosedRecordUnitSuite, DedupeProbe, DedupeUnitSuite, RejectsAndArgsUnitSuite and a private Repair02Support. None of them exist in src/it.
FILES MODIFIED: none (scalafmt reformatted the new file only).

REGRESSION TO TEST MAPPING:
1. F2, Hj3ValueOrderUnitSuite.
   - "F2: unit: HJ3 rows are byte-identical for forward, reversed and rotated value order". Four refs from 4 sources are fed in 4 orders to the in-process IdentifierReducer. The exact-identifier-evidence rows, the rejects rows and the EXACT_LINK count must all match.
   - "F2: unit: provenance.inputs are sorted by (inputId, version, checksum) in every order". It asserts the concrete list unit:crossref, unit:dblp, unit:dtd, unit:openalex, unit:orcid, and that the sorted linkedRecordIds are unchanged.
2. F1, CompressedDblpReaderUnitSuite.
   - "F1: unit: every key of a gzipped file is read exactly once, including past the compressed length". 500 records, compressed size times 5 is below the decompressed size, one split of the compressed length. It asserts 500 distinct keys in order, 500 distinct ascending offsets, and more than 400 records starting at an offset of at least the compressed length.
   - "F1: unit: a compressed file is not splitable" asserts isSplitable == false.
3. F5, DedupeUnitSuite. It drives Dedupe.rows through DedupeProbe with the values in the order given, unsorted.
   - "F5: unit: the latest provenance.retrievedAt wins; inputs are the sorted union; order-independent". Four orderings give identical output. The winner is "Late", with its parameters kept. The inputs are the sorted union of orcid:a/v2, orcid:b/v1, orcid:c/v1 and orcid:z/v9. WORK_MERGED is 2.
   - "F5: unit: equal retrievedAt: the larger JSON text wins, in either order".
   - "F5: unit: a single row is passed through unchanged and nothing is counted".
   - "F5: unit: institutions are merged by the same rule and counted as ENTITY_MERGED". This covers the institutions table, beyond what you listed.
4. F9, RejectsAndArgsUnitSuite.
   - "F9: unit: Rejects.json throws on an invalid reject-v1 row and returns no text". It uses a bad runId and expects an IllegalStateException whose message starts with "invalid reject-v1 row".
   - "F9: unit: Rejects.json writes a valid row".
5. F4, RejectsAndArgsUnitSuite.
   - "F4: unit: a missing --code-version is refused". The message contains "missing --code-version".
   - "F4: unit: whitespace-only and embedded-whitespace code versions are refused". Values tried: " ", "   ", tab, "a b" and "".
   - "F4: unit: a token code version is accepted and kept verbatim".
6. F8, UnclosedRecordUnitSuite. Test: "F8: unit: an unclosed record is cut before the next start tag; the next is read intact".
   - Keys are u/A, u/B and u/C, with exact start offsets.
   - Record A has no u/B text and no closing tag.
   - B and C are byte-identical to their source text.
   - The parser rejects A as MalformedRecord and accepts B.

COMMANDS RUN:
- `sbt "hadoop/Test/scalafmt; hadoop/Test/compile"`: success. A first attempt with two separate quoted arguments was a command-syntax error.
- `sbt "hadoop/testOnly ..."` over the five new suites: Passed 14, Failed 0.
- The first canonical attempt was rejected by the guard before it ran. I had put a `cd` in front, so the command was not the exact text. I reran the exact command, with nothing prefixed or appended.
- `sbt check; echo "exit=$?" > .claude/.last-test-run`: it ran to completion, and the marker contains `exit=0`.

TEST RESULTS (canonical run, exact lines):
- `[info] Passed: Total 524, Failed 0, Errors 0, Passed 524` (core)
- `[info] Passed: Total 114, Failed 0, Errors 0, Passed 114` (hadoop; baseline 100 plus my 14)
- Total 638 passed, 0 failed, 0 errors.
- The new suites each report `0 failed`: RejectsAndArgsUnitSuite 5, UnclosedRecordUnitSuite 1, CompressedDblpReaderUnitSuite 2, DedupeUnitSuite 4, Hj3ValueOrderUnitSuite 2.
- The existing Work Order C suites also passed: ExactIdentifierUnitSuite 7, AdversarialSuite 19, DblpParserUnitSuite 15, NormalizerUnitSuite 12, PairExpanderUnitSuite 9.
- No failing tests.

VALIDATION RESULTS: no production, src/it, build or constitutional files were edited. The marker is `exit=0`. The new tests passed against hadoop-engineer's fixes; none exposed a production defect.

ASSUMPTIONS:
- Dedupe has no public entry point without a Hadoop context, so DedupeProbe wraps `Dedupe.rows` in the in-process reducer harness.
- The F2 tests only show order-independence for the permutations I tried (forward, reversed, rotated and a few shuffles). I did not run every permutation.
- I did not run these tests against the pre-fix code, so I have not shown that they fail without the fixes.

RISKS:
- F10 (JSON filter) is still without a unit test, as hadoop-engineer left it. I was not asked to cover it.
- The new suites create temp directories under modules/hadoop/target and do not delete them. They are covered by the ignored target tree.

MANUAL ACTION REQUIRED: none from me. Commit and approval stay with the human.
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: code-reviewer verdict, which the orchestrator routes.

**Orchestrator observation:** a Stop-hook-triggered canonical run by the orchestrator on the same final tree printed `[info] Passed: Total 524, Failed 0, Errors 0, Passed 524`, `[info] Passed: Total 114, Failed 0, Errors 0, Passed 114`, `markTestRun: wrote …` and `[success] elapsed time: 208 s`. Total 638 passed, 0 failed.

