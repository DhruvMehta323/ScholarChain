# Phase 04 — test reports

Assembled by the orchestrator. Reports pasted verbatim.

## Work Order A2 + DS-T — test-engineer (final report, verbatim)

STATUS: FAILED (A2 is green with 524 passed. DS-T has 1 failing test, which exposes a defect in `datasets/scripts/dblp_extract_subset.py`.)

OWNER: test-engineer

WORK ORDER: Phase 04 A2, core contract tests for the new pipeline records, helpers and Hadoop config. DS-T, extractor tests for `datasets/scripts/dblp_extract_subset.py`. No production code, script or manifest was edited.

### A2: core contract tests

FILES CREATED (all in `/Users/dhruv_mehta/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/`)
- `PipelineFx.scala`: shared synthetic fixtures. They use A1's full Provenance, W, M0, M1 and run IDs. This is not a suite.
- `PipelineRecordsContractSuite.scala`: round trips, frozen wire examples and every record rejection.
- `ArxivIdContractSuite.scala`: table-driven arXiv accepts and rejects.
- `OpenAlexIdContractSuite.scala`: A1r's six accept examples and all of its rejection cases.
- `IdentifierHelpersContractSuite.scala`: `Titles.titleKey`, `Doi.isDoiUrl`, `ExternalLinks.doiFromEe`/`fromEe`, `OrderedPair.canonicalMentions`/`validateMentions`.
- `HadoopConfigContractSuite.scala`: `HadoopConfigLoader` defaults, precedence, origins, invalid values.

I ran `sbt core/Test/scalafmt` on these files so the format check passes.

PER-SUITE TEST COUNTS (new)

| Suite | Tests |
|---|---|
| PipelineRecordsContractSuite | 93 |
| ArxivIdContractSuite | 52 |
| OpenAlexIdContractSuite | 24 |
| IdentifierHelpersContractSuite | 20 |
| HadoopConfigContractSuite | 28 |
| Total new | 217 |

The existing suites are unchanged: Doi 24, DoiVectors 124, Identifier 27, Serialization 66, Config 18, ConfigLoader 12, RecordValidation 23, WindowAndPair 11, Core 2.

EXACT RESULT (canonical `sbt check; echo "exit=$?" > .claude/.last-test-run`)
- The core line reads `Passed: Total 524, Failed 0, Errors 0, Passed 524`. The same line printed three times (the test and contractTest tasks and one more pass). The baseline was 307, and 307 + 217 = 524.
- Every other module printed `Passed: Total 0`.
- The run ended with `[info] markTestRun: wrote …/.claude/.last-test-run` and `[success]`.
- I read the marker afterwards and it holds `exit=0`.

FAILING TESTS (A2): none.
- OpenAlex: A1r's six accept examples and all 13 rejection cases passed as written, with no mismatch against `Identifiers.scala`. These had not been executed before.
- A1r's claim that a non-canonical `openalex` evidence ID gives `InvalidField` (lowercase or URL form) also held.

COVERAGE NOTES
- Frozen JSON: for each of the seven examples, `write(value) == json` and `read(json) == Right(value)`.
  - The seven are MentionAuthorship, MentionTeam, MentionPairCandidate, ExactIdentifierEvidence doi, ExactIdentifierEvidence openalex, RejectRecord and PipelineRunManifest.
  - A1's abbreviated values are substituted with the full ones.
  - The full M0 and M1 IDs and both `run:` IDs are asserted as literals. The manifest `runId` matches the formula.
- Rejections: every case in A1 and A1r has one test asserting the specific `ValidationError` case and field. Examples are `InvalidField("mentionId", _)`, `InvalidYear(999)`, `InvalidPair(M1, M0, "not in canonical order")` and `MissingField("sourceRecordId or inputByteOffset")`.
  - An unknown enum string is asserted as `Malformed(_)`, because that is what `Wire.read` returns for it.
- Existing 21-key loader: covered by the existing `ConfigContractSuite` "env names: 21 distinct keys" and `ConfigLoaderContractSuite` "shipped resource carries no values: exactly 19 missing keys". I added two checks: `ConfigKeys.all.size == 21` with no `hadoop.` keys, and 19 missing keys even when all `SCHOLARCHAIN_HADOOP_*` env vars are set.

MUTATION PROOF
- I changed `HadoopConfig(1, 50, 499500, …)` to `1225` in the "defaults from scholarchain-hadoop.v1.conf" test of `HadoopConfigContractSuite.scala`.
- It failed with `==> X scholarchain.core.HadoopConfigContractSuite.defaults from scholarchain-hadoop.v1.conf … ComparisonFailException`.
- The diff was `-  pairExpansionHardCap = 1225` / `+  pairExpansionHardCap = 499500`, and the run printed `Failed: Total 28, Failed 1, Errors 0, Passed 27`.
- I restored the file from a saved copy. The grep for `499500, HadoopReducers` returned 1, and the canonical `sbt check` was green afterwards (524 passed, `exit=0`).

FROZEN OPENALEX WIRE JSON (captured from a real `Wire.write` run, and its `Wire.read` round trip returned the same value)
```
{"schemaVersion":1,"identifierType":"openalex","normalizedIdentifier":"W2718513939","linkedRecordIds":["mention:bbab2007a569a5629af0b15b0e6ad83d704fc65e67f952bdb489bcd531dd1728","mention:e3125a2371b81d5c1f529ae9addc2da90f66f9555897db9513bc2a5582db75e1"],"outcome":"exact_link","provenance":{"schemaVersion":1,"sourceType":"dblp","inputs":[{"inputId":"dblp-sample","version":"2026-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"codeVersion":"0.1.0","parameters":{"k":"v"}}}
```
The capture used a temporary suite, which I deleted.

### DS-T: extractor tests

FILES CREATED
- `/Users/dhruv_mehta/Desktop/ScholarChain/tests/datasets/test_dblp_extract_subset.py`: unittest, same runner as the other tests in `tests/datasets`. The script is imported through `sys.path` plus `importlib`. All fixtures are tiny synthetic XML, built in a temp directory and labelled synthetic.

PER-CLASS TEST COUNT (23 in the new file)

| Class | Tests |
|---|---|
| ExtractionBytesTests | 11 |
| IdempotenceTests | 4 |
| ProvenanceTests | 3 |
| FailureTests | 5 |

The cases you listed map as follows:
- (a): two records on one line, extracted exactly.
- (b): `&uuml;`, `&eacute;`, inline `<i>`, `<sub>` and `<sup>`, CRLF and a raw 0xFC byte, all preserved.
- (c): a missing key is reported (exit 1, in stdout and the sidecar), and the output is still written.
- (d): output is in selection-TSV order (the TSV order differs from source order). A rerun is byte-identical, both after removing the sidecar and into a fresh directory.
- (e): the "up to date" run does not rescan or write, and `inode`, `mtime_ns` and bytes are unchanged. A tampered output, changed selection, changed script version and an unparseable sidecar each force a rewrite.
- (f): decoys do not leak. The decoys are a non-selected record, `<crossref>` text, a nested `<note key=…>`, escaped `&lt;article key=…&gt;`, an `xkey=` attribute, and a longer key `A10` against selected `A1`.
- (g): the sidecar's counts, hashes, validation block and disagreement reporting.
- Extras: chunk-edge splits with `CHUNK` at 7, 61, 97 and 211 bytes, a source with duplicate records (first wins and it is reported), and failure modes (hash mismatch, missing input, duplicate selection keys, a different existing DTD, a record with no end tag).

COMMAND AND RESULT: `python3 -B -I -m unittest discover -s tests/datasets -p 'test_*.py'` printed `Ran 83 tests … FAILED (failures=1)`.
- That is 82 passed, 1 failed, 0 errors for the whole `tests/datasets` suite.
- The new file alone: `Ran 23 tests`, 22 passed, 1 failed.
- No `__pycache__` was written by my runs (`-B`). `tests/datasets/__pycache__` already existed.

FAILING TEST, owner dataset-engineer
- `test_f_start_tag_inside_an_xml_comment_is_not_extracted` (`ExtractionBytesTests`).
- The input was an XML comment holding `<article key="journals/syn/A1"><title>FAKE</title></article>`, placed before the real A1 record.
- Output:
```
AssertionError: b'<?x[89 chars]icle key="journals/syn/A1"><title>FAKE</title>[17 chars]p>\n' != b'<?x[89 chars]icle mdate="2020-01-01" key="journals/syn/A1">[114 chars]p>\n' : comment text leaked
```
- Cause: `START_RE` is a byte regex with no XML awareness, so start-tag text inside a comment is taken as a record. The first match wins, and the real record is then reported as a duplicate.
- Impact: the real `dblp.xml` has no comments, so this is low severity for the planned run. It does contradict the docstring's "exact key attribute on a top-level publication element".

OBSERVATION (no test written)
- On a manifest-hash mismatch the script exits 2 and writes no output or sidecar, as the tests assert. It has already copied `dblp.dtd` next to the output by then, which differs from the docstring's "(nothing written)".

### Validation and handoff

COMMANDS RUN
- `sbt "core/testOnly scholarchain.core.TmpCapture"`: exit 0. The temp capture suite was deleted afterwards.
- `sbt core/Test/scalafmt`: exit 0.
- `sbt "core/testOnly …"` for the five new suites: 217 passed.
- Python discover, new file only: 23 run, 1 failed.
- Python discover, whole suite: 83 run, 1 failed.
- Mutation run: `sbt "core/testOnly scholarchain.core.HadoopConfigContractSuite"` ran twice, exit 1 (the failure shown above).
- `sbt check; echo "exit=$?" > .claude/.last-test-run`: exit 0, marker holds `exit=0`.
- My first attempt at the canonical command was rejected by the guard because I prefixed a `cd`. Nothing ran. The bare command from the repo root then succeeded.

ASSUMPTIONS
- A1's lowercase wire names for `outcome` and `identifierType` are the contract.
- Where A1 gave no reason text, I asserted the error type, the field and the echoed input. I asserted exact reason strings only where A1 or the code states them.

RISKS
- The frozen JSON now locks all new wire shapes and the `reject-v1` reason list.
- The comment probe is a stricter expectation than the docstring states. Dataset-engineer may decide the script should instead document the limit. That would change the test, and test-engineer would make the change.

MANUAL ACTION REQUIRED: none from me. Per docs/agents.md, review and acceptance are left to code-reviewer and the human.

BLOCKED-ON OWNER: none for A2. For DS-T, dataset-engineer owns the failing script.

BLOCKED-ON EVIDENCE: n/a. The failing script is `datasets/scripts/dblp_extract_subset.py`, owned by dataset-engineer under `datasets/**`.

NEXT GATE: orchestrator routes the DS-T failure to dataset-engineer (a fix, or a documented limit followed by a test change). Then code-reviewer's verdict, then human acceptance.

**Follow-up:** the DS-T failure was fixed in repair-01 (dataset-engineer). After it, `python3 -B -I -m unittest discover -s tests/datasets -p 'test_*.py'` reported 83 run, OK (see repair-01.md).

## Work Order C — test-engineer (final report, verbatim)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 04 Work Order C. Write at least 35 unit and adversarial tests (at least 23 unit, at least 12 adversarial) for the Hadoop module, covering every prompt section 6 bullet. Write only under modules/hadoop/src/test/**. No production edits.

FILES CREATED (all under /Users/dhruv_mehta/Desktop/ScholarChain/modules/hadoop/src/test/):
- scala/scholarchain/hadoop/UnitSupport.scala: shared helpers. These include an in-process Reducer and Mapper harness built on ReduceContextImpl, MapContextImpl and MultipleOutputs. No LocalJobRunner is launched.
- scala/scholarchain/hadoop/DoiVectors.scala: reader for the shared DOI TSV.
- scala/scholarchain/hadoop/DblpParserUnitSuite.scala
- scala/scholarchain/hadoop/NormalizerUnitSuite.scala
- scala/scholarchain/hadoop/ExactIdentifierUnitSuite.scala
- scala/scholarchain/hadoop/PairExpanderUnitSuite.scala
- scala/scholarchain/hadoop/AdversarialSuite.scala
- resources/unit-fixtures/: dblp-unit.dtd, dblp-types-unit.xml, crossref/cr-happy-unit.json, crossref/cr-noyear-unit.json, openalex/oa-happy-unit.json, orcid/or-happy-unit.json. Each is labeled SYNTHETIC.
FILES MODIFIED: none. I did not touch src/main, src/it or core. Scalafmt reformatted only the new test files.

COMMANDS RUN:
- Many targeted sbt "hadoop/Test/compile" and sbt "hadoop/testOnly <Suite>" runs. All exited 0 at the end. Two intermediate failures were my own test bugs and were fixed. The first was a DOI-vector idempotence check on rows that contain a literal %. The second was an emoji row that I had wrongly classified as illegal XML.
- sbt "hadoop/Test/scalafmt". It reformatted 1 source.
- Canonical command, run once at the end with nothing prefixed or appended: `sbt check; echo "exit=$?" > .claude/.last-test-run`. Observed marker content: `exit=0`.

TEST RESULTS (canonical run):
- `[info] Passed: Total 524, Failed 0, Errors 0, Passed 524` (core)
- `[info] Passed: Total 90, Failed 0, Errors 0, Passed 90` (hadoop)
- The run ended with `[info] markTestRun: wrote .../.claude/.last-test-run` and `[success] elapsed time: 128 s`.
- Total is 614 passed, 0 failed, 0 errors (baseline 552 = core 524 + hadoop 28).
- Hadoop went from 28 to 90, so my suites add 62. The 28 existing tests are hadoop-engineer's src/it suites: ParserSafetyItSuite 4, RecordReaderItSuite 3, PipelineItSuite 16, DeterminismIdempotencyItSuite 5. They still pass.
- Per-suite counts (observed lines "0 failed, 0 ignored, N total"):

| Suite | Type | Count |
|---|---|---|
| DblpParserUnitSuite | unit | 15 |
| NormalizerUnitSuite | unit | 12 |
| ExactIdentifierUnitSuite | unit | 7 |
| PairExpanderUnitSuite | unit | 9 |
| AdversarialSuite | adversarial | 19 |

- Unit total is 43 (required at least 23). Adversarial total is 19 (required at least 12). Grand total is 62 (required at least 35).
- Failing tests: none.

SECTION 6 MAPPING (test names are shortened; each starts "unit:" or "adversarial:"):
Parser (unit, DblpParserUnitSuite):
- `&uuml;` decoded: "a DTD entity (&uuml;) decodes"
- multiline title: "a multiline title collapses whitespace"
- inline markup flattened: "inline markup in a title is flattened"
- missing year: "a missing year is rejected as missing_year" (plus the invalid_year test)
- missing title (rejected): "a missing title is rejected as missing_title"
- repeated author: "a repeated author string is one mention..."
- orcid attribute preserved: "the orcid attribute is preserved"
- DOI and arXiv from ee: "DOI and arXiv come from ee links" and "an invalid DOI in ee keeps the record"
- DOI vector TSV through the Hadoop path: "every row of the shared DOI vector TSV, run through the Hadoop ee to DOI path"
- editors excluded: "editors are excluded from authors..."
- www homepages routed to aliases: "a www homepage record is routed to person aliases"
- each supported record type: "each supported record type parses; www is a person; data is unsupported_type"
- XXE not resolved: "an external-entity (XXE) attempt is not resolved"

Normalizers (unit, NormalizerUnitSuite):
- Crossref happy path: "Crossref happy path yields work, mentions..."
- Crossref missing fields: "Crossref with missing fields"
- Crossref malformed (rejected): "Crossref malformed envelopes are rejected"
- Crossref missing-year rule: "Crossref missing-year rule: kept..., no WorkRecord"
- source IDs preserved: "Crossref preserves the source id and the retrieved_at / file checksum"; source IDs are also asserted in the OpenAlex and ORCID happy-path tests
- OpenAlex: "OpenAlex happy path", "OpenAlex missing fields", "OpenAlex malformed envelopes"
- ORCID: "ORCID happy path", "ORCID with missing fields", "ORCID malformed envelopes"
- missing:true envelope: "a missing:true envelope is an upstream-missing marker"

Exact identifiers (unit, ExactIdentifierUnitSuite):
- EXACT_LINK, DUPLICATE and CONFLICT each have their own test. A fourth test shows CONFLICT outranks EXACT_LINK.
- MISSING_KEY: "MISSING_KEY: a work with no external id is counted"
- Also covered: a single-record key writes nothing, and ORCID one-iD-on-several-mentions gives DUPLICATE.

Pair expander (unit, PairExpanderUnitSuite):
- n = 0 and 1: "n = 0 and n = 1 give no pairs"
- n = 2, 3 and 5: three separate tests with exact pair lists
- n = 120 gives 7,140: "a team of 120 gives 7,140 distinct pairs"
- duplicate name gives no self-pair and no duplicate pair: "team size = distinct author strings: a repeat gives no self-pair, no duplicate pair". The reducer-level check is "the HJ1 reducer counts the repeat as SELF_PAIR_SKIPPED..."
- canonical order and provenance on every pair: "every pair is canonical (first < second), valid, and carries the provenance"
- pairCount equals n(n-1)/2 and the emitted count for n = 0..40
- team size definition: the two tests above (a repeat is dropped and counted as SELF_PAIR_SKIPPED)

Adversarial (AdversarialSuite):
- unclosed tag mid-record: "an unclosed tag in the middle of a record is malformed_record"
- CDATA: "CDATA in a title is taken literally"
- literal `<article` in a title: "a literal <article inside a title is text, and is not a record start". It runs through the real DblpRecordReader.
- UTF-8 BOM: "a UTF-8 BOM before the document does not shift or lose records"
- extremely long title: "an extremely long title (1,000,000 characters) is kept whole"
- start tag exactly at a split boundary: "a record start tag exactly at a split boundary is read once, by the later split". It uses the real DblpRecordReader with FileSplit, with no job.
- invalid UTF-8, entity bomb and deep nesting: "invalid UTF-8 bytes are encoding_error; declarations, entity bombs and deep nesting are malformed"
- NFC versus NFD: "NFC and NFD forms of one name are NOT folded (observed, undocumented)"
- Cyrillic confusable: "a Cyrillic confusable inside a Latin name stays a distinct author (observed, undocumented)"
- combining diacritics in titles: "combining diacritics in titles survive code-point exact..."
- no authors, no year, no venue: three separate tests, each on its documented route
- same DBLP key twice with different content: "the same DBLP key twice ... has a deterministic winner (lowest offset)". It is checked in both input orders.
- same DOI on two DBLP works: "the same DOI on two DBLP works is CONFLICT end to end (HJ1 id-refs into HJ3)"
- homonyms `Wei Wang 0001` and `0002`: "DBLP homonyms ... stay distinct: one pair, no self-pair"
- giant teams: "a giant team of 300 gives exactly 44,850..." and "a giant team of 1,000 gives exactly 499,500 valid distinct pairs, none truncated". Both use the pure expander, with all pairs valid, distinct and untruncated.
- rawExcerpt bounded to 512: "RejectRecord.rawExcerpt is bounded to 512 characters, never splitting a surrogate pair"

VALIDATION RESULTS:
- Exact pass and fail report is above. No production edits were made.
- The DOI vector TSV is NOT on the hadoop Test classpath. build.sbt has `dependsOn(core)` without `test->test`. I read it by its repository-relative path, modules/core/src/test/resources/doi-normalization-vectors.tsv, using my own unescape reader in DoiVectors.scala that mirrors core's.
- No LocalJobRunner was used in any of my suites. HJ1 and HJ3 reducers and the HJ3 mapper are driven in-process through hand-built Hadoop contexts. The record reader is driven directly.

PRODUCTION DEFECTS FOUND: none (0 failing tests).

DOCUMENTATION GAPS (owner hadoop-engineer; JOB-CONTRACTS.md):
1. Unicode normalization of author names is not documented. NFC and NFD forms of "Müller" stay two distinct mentions and give one pair. Names are stored code-point exact. The tests assert this as "observed, undocumented".
2. Confusable folding is not documented. A Cyrillic U+0430 inside a Latin name is a distinct author. This is observed, undocumented, and is presumably Phase 05 entity-resolution policy.
3. Section 7's pure-function API does not list exact-identifier classification or duplicate-key winner selection. Both exist only inside the Reducer classes (IdentifierReducer, DblpReducer). I tested them through an in-process reducer harness that uses Hadoop context internals (ReduceContextImpl and MultipleOutputs). A pure `classify(refs)` function would make those tests simpler and less brittle.
4. A bare DOI or `doi:` string in a DBLP ee is ignored, because only DOI URLs are recognized (`ExternalLinks.fromEe`). The only documentation is "A bad ee URL ... is skipped". The test asserts the observed rule: ee must be a DOI URL.
5. A lone UTF-16 surrogate in an ee becomes `?` when the record is encoded to bytes. I cannot say whether that is intended, because the document does not cover it.

ASSUMPTIONS:
- Row JSON field names (workId, rawName, outcome, linkedRecordIds, institutionId and similar) come from the core case classes.
- A DOI-vector row whose input contains an XML-illegal control character is expected to make the record malformed_record, since the character cannot appear in XML.

RISKS:
- The harness depends on Hadoop internals (ReduceContextImpl, MapContextImpl, WrappedReducer, FileOutputCommitter). It could break on a Hadoop upgrade.
- AdversarialSuite takes about 18 s, mostly the n = 1,000 case. Temp files are written under modules/hadoop/target, which is gitignored.
- Tests use `Files.createTempFile` under modules/hadoop/target, so running outside the repo layout would fail `repoRoot` lookup.
- The PostToolUse hook auto-formats test files; one guard-dangerous.sh false positive on the text `.key` forced me to create several files with the Write tool instead of bash heredocs.
- The canonical command only passes when run without a `cd` prefix.

MANUAL ACTION REQUIRED: human review and commit (I did not commit). Optionally route documentation gaps 1 to 5 to hadoop-engineer.

BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: orchestrator routes the documentation gaps to hadoop-engineer, then code-reviewer verdict (Work Order D).
