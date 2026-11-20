# Phase 04 — agent reports

Assembled by the orchestrator (main Claude Code session, claude-opus-5-5). Agent reports are pasted verbatim under their headings.

## Preconditions

Observed by the orchestrator on 2026-11-20.

1. **Tree and ancestry.** `git status --short` printed nothing (clean). `git log -1 --oneline` printed `6335b5a phase-03 evidence: record accepted commit hash`. `commit.txt` holds `71b8dd71ba661f6abf651bbc9a45d15c4277423f`. `git merge-base --is-ancestor 71b8dd7… HEAD` succeeded (ancestor=yes).
2. **Baseline.** `sbt check; echo "exit=$?" > .claude/.last-test-run` printed `[info] Passed: Total 307, Failed 0, Errors 0, Passed 307`, `markTestRun: wrote …/.claude/.last-test-run`, `[success]`. Marker: `exit=0`. **Baseline = 307 tests** (all in `core`; hadoop had "No tests to run").
3. **Dataset inputs** (paths from `datasets/dataset-manifest.yaml`):

   | Input | Path | Exists locally |
   |---|---|---|
   | DBLP 1,000-work selection | `datasets/samples/dblp/dblp_selection_1000.tsv` (+ `.provenance.json`), sha256 `52bbb09f…`, columns `key, type, year, venue, doi, n_authors, title` | yes (committed) |
   | DBLP DTD | `datasets/raw/dblp/dblp.dtd`, sha256 `2d864498…`, 13,134 bytes | yes (gitignored) |
   | DBLP XML (full) | `datasets/raw/dblp/dblp.xml`, 5,405,851,335 bytes | yes (gitignored; not to be processed by Hadoop) |
   | Crossref cache | `datasets/raw/crossref/works/` (100 `.json` + 1 `.jsonl`); committed sample `datasets/samples/crossref/crossref_works_sample.jsonl` (100 lines) | yes |
   | OpenAlex cache | `datasets/raw/openalex/works/` (100 `.json`); committed sample `datasets/samples/openalex/openalex_works_sample.jsonl` (100 lines) | yes |
   | ORCID cache | `datasets/raw/orcid/works/` (100 `.json`) and `datasets/raw/orcid/orcid_ids.json`, all gitignored; committed sample is aggregate only (`datasets/samples/orcid/orcid_coverage_summary.json`) | yes (local only) |

   **Gap found:** the 1K selection is a TSV with no author lists and no XML, so HJ1 (XML record reader) and HJ4 (pairs) cannot run on it. Getting XML for the 1,000 selected keys needs one streaming read of the full `dblp.xml`, which rule 0.7 forbids as written.
   **Human decision (2026-11-20, asked by the orchestrator):** "Bounded subset extract". dataset-engineer writes an extractor under `datasets/scripts/` that makes one read-only streaming pass over `dblp.xml` and writes only the 1,000 selected records as XML under `datasets/raw/dblp/derived/` (gitignored), with sha256 and a manifest entry. Hadoop never processes the full file. Routed as pre-step DS (`BLOCKED-ON dataset-engineer`).
4. **build.sbt.**
   - (a) `hadoop` `.dependsOn(core)`: **pass** (build.sbt line 93).
   - (b) `Dependencies.hadoopClientApi` and `hadoopClientRuntime`, `Provided` scope (on compile and test classpaths), version `Versions.hadoop = "3.4.2"`: **pass**.
   - (c) `modules/hadoop/src/it/**` wired into Test: **fail** (no wiring present). Work Order A0 required.
   - Observation passed to A0/B: `commonSettings` has `Tests.Filter(name => !name.endsWith("IntegrationSuite"))`, so `src/it` suites must not use that suffix or `sbt check` will silently skip them. `Test / javaOptions` already carries the JDK 17 `--add-opens` set.

## Carried from Phase 03

- **Open findings:** none at REQUEST_CHANGES level. Final verdict on Repair 02 was APPROVE WITH NOTES (verdict 3). Accepted, unrouted notes: V3-1…V3-5, N12, N13. Deferred notes N4, N5, N9, N10, N11, N14 concern fetch scripts and core validation, and none blocks Hadoop work.
- **Canonical DOI rule:** `scholarchain.core` DOI `normalize`/`parse` in `modules/core/src/main/scala/scholarchain/core/Identifiers.scala`, with the shared vector file `modules/core/src/test/resources/doi-normalization-vectors.tsv` (121 rows). Hadoop must call it, not reimplement it.
- **ORCID:** `Orcid` object in `Identifiers.scala` (canonical `dddd-dddd-dddd-dddX`, ISO 7064 mod 11-2 check). No arXiv normalizer and no public title-key normalizer were found in core (the title key appears only inside the `workIdFallback` formula). Routed to A1 for inspection.
- **DBLP cache validation:** the eligible-index cache is invalid under eligibility policy 2, and select refuses to run (gate decisions 5 and 14). Phase 04 uses only the committed 1K selection, and does not reindex or reselect.
- **Crossref missing-year rule (N15):** a Crossref record with no year is kept, counted (`crossref_matches_without_year`), and excluded from year figures. It is not rejected.
- **Stable IDs:** `workIdFromSource`, `workIdFallback`, `researcherIdFromOrcid`, `researcherIdScholarChain`, `coauthorEdgeId`, `graphEdgeId`, `evidenceId`, `mentionId(workId, position)`, `runId(…)` (timestamp-independent), plus `check*` validators. Formulas and wire shapes are frozen (gate decision 1).
- **Pair canonicalization:** `canonical(x, y): Either[ValidationError, OrderedPair]` (lexicographic, rejects self-pairs) and `validate(p)`.
- **Existing records relevant to A1:** `PublicationTeam`, `AuthorshipRecord`, `CoauthorPairCandidate`, `RunManifest`, `Institution`, `Topic`, `WorkRecord`, `AuthorMention` exist. `ExactIdentifierEvidence`, `RejectRecord` and `PipelineRunManifest` were not found by name. A1 runs.

## Work Order A0 — build-engineer (final report, verbatim)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Wire `modules/hadoop/src/it/scala` and `src/it/resources` into the hadoop project's `Test` source and resource directories, so `sbt check` compiles and runs suites placed there. Prove it with a temporary placeholder, then remove it and rerun.
FILES CREATED: none left. I made `modules/hadoop/src/it/scala/WiringProofItSuite.scala` as a temporary proof and removed it. `modules/hadoop/src/` did not exist before, so I created and then removed the directories as well. `modules/hadoop` is back to empty.
FILES MODIFIED: `/Users/dhruv_mehta/Desktop/ScholarChain/build.sbt`. In the `hadoop` project settings, 5 lines were added and 0 removed: 3 comment lines and 2 settings.
```scala
Test / unmanagedSourceDirectories   += baseDirectory.value / "src" / "it" / "scala"
Test / unmanagedResourceDirectories += baseDirectory.value / "src" / "it" / "resources"
```
No dependency, version, scope or JVM-flag change. The `IntegrationSuite` filter in `commonSettings` is unchanged.
COMMANDS RUN:
- `sbt check; echo "exit=$?" > .claude/.last-test-run` with the placeholder present: exit 0.
- The same command after removing the placeholder: exit 0.
- An earlier attempt with a trailing `cat` appended was rejected by the guard before running. Nothing was executed.
- `git status --short` shows only `build.sbt` modified, plus the untracked phase-04 evidence directory.

TEST RESULTS:
- **Run 1, with placeholder:** the hadoop `Test` run printed `Test run WiringProofItSuite finished: 0 failed, 0 ignored, 1 total` and `Passed: Total 1, Failed 0, Errors 0, Passed 1`. The core run printed `Passed: Total 307, Failed 0, Errors 0, Passed 307`. That is 308 in total (hadoop 1 plus core 307). scalafmt checks passed, and the marker was written.
- **Run 2, placeholder removed:** `Passed: Total 307, Failed 0, Errors 0, Passed 307` in both the `Test/testFull` and `contractTest` steps. The hadoop run reported `No tests to run`, so the total is back to the baseline 307.
- Marker `.claude/.last-test-run` contains `exit=0`.

VALIDATION RESULTS: `scalafmtCheckAll` and `scalafmtSbtCheck` stayed green in both runs. Code review is pending.

**Suite-naming convention for `src/it`:** suites must be named `*ItSuite`, for example `WordCountLocalItSuite`. They run under `Test/testFull`. The only suffix `commonSettings` excludes is `*IntegrationSuite`, which is reserved for live-service tests. Any other suffix would also run, but `*ItSuite` is the convention.

ASSUMPTIONS:
- `src/it` suites run in the same forked JVM as unit tests, with the same `jdk17Opens` flags.
- `ContractSuite` names are still picked up by the `contractTest` alias. An `*ItSuite` that is not a `ContractSuite` does not run in that step. It runs in `testFull`.

RISKS:
- `src/it` code shares the `Test` classpath and compile unit with `src/test`. A duplicate top-level class name in the two trees would clash.
- `src/it` suites that need a cluster or long runtime would slow `sbt check`. Keep them local and small.
- `modules/hadoop/src/` did not exist. Whoever creates it needs the `it/scala` path exactly.

MANUAL ACTION REQUIRED: none beyond code review and human acceptance.
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: code-reviewer verdict, then human acceptance. After that, hadoop-engineer may add `*ItSuite` suites under `modules/hadoop/src/it/scala`.

## Progress checkpoint

- Work order: A0 (build-engineer). Status: COMPLETE.
- Files touched: `build.sbt` (+5 lines: wiring of hadoop `src/it/scala` and `src/it/resources` into Test).
- Last test result: `sbt check` 307/307 passed, marker `exit=0` (placeholder removed).
- Pending: DS (dataset-engineer, running), A1 next.

## Pre-step DS — dataset-engineer (status)

The consolidated DS report was not delivered to the orchestrator: the Stop hook kept the agent's turn open while A1's core edits were newer than the test marker. The final DS message received states STATUS: BLOCKED-ON (owner test-engineer, for the extractor tests at `tests/datasets/test_dblp_extract_subset.py`, which guard-writes.sh refused). It reports: 1000 of 1000 records found, no missing or duplicate keys, 0 disagreements on type, year, title or author count; source sha256 matches the manifest; output 575,598 bytes, sha256 `3dfcab53e2764639f8777707fe55f2c3bc067e040282c63c24cdc72341868db6`; `validate_manifest.py`: "checks passed: 244; failed: 0"; dataset unittest discover 60/60 OK; extractor tests NOT RUN.

Orchestrator verification (observed): `shasum -a 256` of `datasets/raw/dblp/derived/dblp_selection_1000.xml` = `3dfcab53…68db6`; `wc -c` = 575598; 1000 lines with ` key="`; header `<?xml version="1.0" encoding="ISO-8859-1"?>` / `<!DOCTYPE dblp SYSTEM "dblp.dtd">` / `<dblp>`; `git check-ignore` confirms it is ignored; `datasets/raw/dblp/derived/dblp.dtd` sha256 `2d864498…` equals the manifest DTD hash (byte-identical copy). `git diff --stat datasets/`: manifest +23, `validate_manifest.py` +5. The stray `tests/datasets/__pycache__/*.pyc` is gitignored (`.gitignore:30`).

Orchestrator interim run (Stop hook, during A1's unfinished edits): `sbt check` printed `Passed: Total 307, Failed 0, Errors 0, Passed 307`, `markTestRun: wrote …`, `[success]`. A snapshot only, not A1's result.

## Work Order A1 — domain-engineer (final report, verbatim)

STATUS: COMPLETE

OWNER: domain-engineer

WORK ORDER: Phase 04 A1. Inventory the `core` contracts and add the missing pre-ER (pre-entity-resolution) pipeline records. Add arXiv and title-key helpers and an `ee`-to-DOI path. Add a module-scoped config loader for `scholarchain.hadoop.*`. No frozen wire format was changed.

FILES CREATED (all under `/Users/dhruv_mehta/Desktop/ScholarChain/modules/core/src/main/`)
- `scala/scholarchain/core/PipelineRecords.scala`: `MentionAuthorship`, `MentionTeam`, `MentionPairCandidate`, `ExactIdentifierEvidence`, `RejectRecord`, `PipelineRunManifest`, `JobSummary`, `OutputRef`.
- `scala/scholarchain/core/ModuleConfig.scala`: generic module config resolver, `HadoopConfigKeys`, `HadoopConfig`, `HadoopConfigLoader`, `OutputCodec`.
- `resources/scholarchain-hadoop.v1.conf`: the versioned Hadoop defaults.

FILES MODIFIED
- `scala/scholarchain/core/Identifiers.scala`: added `Titles.titleKey`, `ArxivId`, `ArxivRef`, `ExternalLinks`, `ExternalId` and `Doi.isDoiUrl`. Added `OrderedPair.canonicalMentions` and `validateMentions`. `workIdFallback` now calls `Titles.titleKey`.
- `scala/scholarchain/core/Enums.scala`: added `IdentifierType`, `ExactIdentifierOutcome`, `RejectReason`.
- `scala/scholarchain/core/ValidationError.scala`: added the case `InvalidArxiv` (code `invalid-arxiv`). This is additive, and no other module matches on `ValidationError`.
- `scala/scholarchain/core/ConfigLoader.scala`: `parse` and `flatten` now take a key list that defaults to `ConfigKeys.all`, so the 21-key behavior is unchanged. Added `private[core] moduleFileLayer`.

COMMANDS RUN
- `sbt check; echo "exit=$?" > .claude/.last-test-run`: the first run failed at `scalafmtCheck` on `ModuleConfig.scala`, so the marker held exit=1 at that point.
- `sbt core/scalafmt`: reformatted that one file.
- `sbt check; echo "exit=$?" > .claude/.last-test-run` again: passed. Marker written, `[success] elapsed time: 15 s`.
- `sbt core/console` with a scratch script: produced the wire examples below. It only read code and changed no sources.
- One `sed` rename of `None_` to `Uncompressed`, and some Python edit scripts run from the scratchpad.

TEST RESULTS
- The final `sbt check` printed `Passed: Total 307, Failed 0, Errors 0, Passed 307` for core, with all other modules at 0 tests.
- The frozen suites were all green: `DoiVectorsContractSuite` 124, `IdentifierContractSuite` 27, `SerializationContractSuite` 66.
- I did not read the marker file after the final run. The sbt output states `markTestRun: wrote .claude/.last-test-run` and the shell command is the exact canonical one, so the marker should hold exit=0.
- There are no tests for the new code yet, because test-engineer writes them in A2. The new code compiled and the REPL round-trips below passed.

### (1) Inventory

Phase 03 already has same-named records, but they are keyed by resolved researcher ID (post-ER). They stay unchanged, because changing them in place would break the freeze. The pre-ER pipeline needs mention-keyed shapes. I therefore added new types with distinct names, all at `schemaVersion` 1.

| Record asked | Phase 03 status | Fields | Action |
|---|---|---|---|
| `PublicationTeam` | exists (v1) | `schemaVersion, workId, year, researcherIds` (at least 2, strictly ascending), `provenance` | Untouched. Added `MentionTeam` v1: `schemaVersion, workId, year, teamSize, mentionIds, provenance`. |
| `AuthorshipRecord` | exists (v1) | `schemaVersion, workId, researcherId, mentionId, position, identityConfidence, identityOutcome, provenance`. It has no year. | Untouched. Added `MentionAuthorship` v1: `schemaVersion, mentionId, workId, position, year, provenance`. |
| `CoauthorPairCandidate` | exists (v1) | `schemaVersion, workId, year, pair: OrderedPair` (researcher IDs), `provenance`. It has no positions. | Untouched. Added `MentionPairCandidate` v1: `schemaVersion, workId, year, pair, firstPosition, secondPosition, provenance`. |
| `ExactIdentifierEvidence` | missing | n/a | Added v1: `schemaVersion, identifierType, normalizedIdentifier, linkedRecordIds, outcome, provenance`. |
| `RejectRecord` | missing | n/a | Added: `schemaVersion, schema, runId, jobId, source, sourceRecordId?, inputByteOffset?, reasonCode, message, rawExcerpt`. |
| `PipelineRunManifest` | `RunManifest` exists (v1) | `schemaVersion, runId, stage, codeVersion, attempt, parameters, inputs, createdAt?`. It has no snapshot, config, jobs or outputs. | `RunManifest` untouched. Added `PipelineRunManifest` v1: `schemaVersion, runId, stage, attempt, snapshot, codeVersion, inputs, config, jobs[JobSummary(jobId, counters, outputs[OutputRef(path, rowCount, checksum?)])], createdAt?`. |

### (2) Wire examples (verbatim) and rules

**Provenance** used in the examples below. `<PROV>` stands for this exact object:
`{"schemaVersion":1,"sourceType":"dblp","inputs":[{"inputId":"dblp-sample","version":"2026-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"codeVersion":"0.1.0","parameters":{"k":"v"}}`

**Work ID and mention IDs** used in the examples (the IDs are real outputs):
- `W` is `work:dblp:journals/x/A1`.
- `M0` is `mention:bbab2007a569a5629af0b15b0e6ad83d704fc65e67f952bdb489bcd531dd1728`.
- `M1` is `mention:e3125a2371b81d5c1f529ae9addc2da90f66f9555897db9513bc2a5582db75e1`.
- The examples show the IDs in full. Each example below came from `Wire.write` and passed `Wire.read == Right(original)`, except that the `<PROV>` object is abbreviated.

**MentionAuthorship**
```
{"schemaVersion":1,"mentionId":"mention:bbab2007a569a5629af0b15b0e6ad83d704fc65e67f952bdb489bcd531dd1728","workId":"work:dblp:journals/x/A1","position":0,"year":2020,"provenance":<PROV>}
```
- Rules: valid schema, valid work ID, `position >= 0`, and `mentionId == Ids.mentionId(workId, position)`. The year must be four digits, and provenance must be valid.
- Rejections to encode: a wrong `mentionId`; `position` of -1; year 999; invalid provenance (empty inputs, blank code version); schema 2.

**MentionTeam**
```
{"schemaVersion":1,"workId":"work:dblp:journals/x/A1","year":2020,"teamSize":2,"mentionIds":["mention:bbab…1728","mention:e312…75e1"],"provenance":<PROV>}
```
(`mentionIds` is abbreviated here. In the real output the full IDs appear.)
- Rules: `mentionIds` is non-empty, so a single-author team is valid. `teamSize == mentionIds.size`. `mentionIds(i) == Ids.mentionId(workId, i)`, so the list is in author order with contiguous positions.
- Rejections to encode: an empty list; `teamSize` 3 with 2 ids; ids swapped; a gap in positions; ids from another work.

**MentionPairCandidate**
```
{"schemaVersion":1,"workId":"work:dblp:journals/x/A1","year":2020,"pair":{"first":"mention:bbab…1728","second":"mention:e312…75e1"},"firstPosition":0,"secondPosition":1,"provenance":<PROV>}
```
- This example came from `MentionPairCandidate.build(W, 2020, 1, 0, prov)`. The arguments were `(1, 0)`, and it canonicalized to first=M0 at position 0 and second=M1 at position 1.
- The order is by `String.compareTo` on the mention IDs, as in `OrderedPair`. It is not by position, so `firstPosition` may be greater than `secondPosition`.
- Rules: `OrderedPair.validateMentions` must pass (each id a valid `mention:`, no self-pair, `first < second`). Each position is `>= 0`. `pair.first == mentionId(workId, firstPosition)`, and likewise for `second`.
- Rejections to encode: reversed pair (`invalid-pair`); self-pair, including `build` with equal positions; positions that do not match the ids; a researcher ID in place of a mention ID; a negative position.

**ExactIdentifierEvidence**
```
{"schemaVersion":1,"identifierType":"doi","normalizedIdentifier":"10.1000/abc","linkedRecordIds":["mention:bbab…1728","mention:e312…75e1"],"outcome":"exact_link","provenance":<PROV>}
```
- Wire names: `identifierType` is `doi|arxiv|orcid`. `outcome` is `exact_link|duplicate|conflict`. I used lowercase snake case to match the repo's existing enum convention, not the uppercase spelling in the work order. Please confirm.
- Rules: `normalizedIdentifier` must already be canonical for its type (`Doi.normalize`, `ArxivId.normalize` or `Orcid.parse` returns the same string). `linkedRecordIds` has at least 2 entries, each `Ids.checkAnyRef`-valid, strictly ascending (so distinct). There is no confidence, score or threshold, so it is evidence and not a match decision.
- Rejections to encode: a non-canonical DOI such as `DOI:10.1000/ABC`; arXiv with a version `2101.00001v2`; a bad ORCID checksum; a single linked id; an unsorted list; duplicate ids; an unknown outcome string.

**RejectRecord** (`reject-v1`)
```
{"schemaVersion":1,"schema":"reject-v1","runId":"run:e18d3d9837bbd1d123d801ab9ecafc37c13fc0f316329b629c9426bc467b20da","jobId":"parse-dblp","source":"dblp","sourceRecordId":"journals/x/A1","inputByteOffset":1024,"reasonCode":"missing_year","message":"no year element","rawExcerpt":"<article>..."}
```
- I added a `schema` string field in addition to the integer `schemaVersion`. `Wire.read` requires an integer `schemaVersion`, and the spec names the schema `reject-v1`. It must equal `"reject-v1"`. Decide if the redundancy is acceptable.
- Reason codes: `malformed_record, encoding_error, missing_title, missing_year, invalid_year, missing_authors, invalid_identifier, unsupported_type, oversized_record, pair_cap_exceeded, duplicate_record, schema_violation`. The list is mine, so confirm it covers hadoop-engineer's needs. An unknown reason fails to read.
- Rules: `runId` is a valid `run:` ID. `jobId` is a non-blank token with no whitespace. At least one of `sourceRecordId` and `inputByteOffset` is present, and the offset is `>= 0`. The message is non-blank. `rawExcerpt` is at most 512 Unicode code points (counted as code points, so a surrogate pair counts as one). `RejectRecord.boundExcerpt(raw)` truncates to 512 without splitting a pair, and `RejectRecord.MaxExcerpt` is 512.
- There is no provenance field in the spec's list. The `runId` links a reject to the manifest's inputs, code version and parameters. If you want per-reject `Provenance`, that is a v2.
- Rejections to encode: schema string `reject-v2`; excerpt of 513 code points (accepted at 512); neither locator; offset -1; invalid `runId`; blank message; unknown reason.

**PipelineRunManifest**
```
{"schemaVersion":1,"runId":"run:8ecfd4548c5f4331f23466bd0bfe9baf65b2ddee2cacffb661e96751255adb48","stage":"hadoop-pipeline","attempt":1,"snapshot":"dblp-sample-2026-01","codeVersion":"0.1.0","inputs":[{"inputId":"s3://b/raw/dblp.xml","version":"2026-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"config":{"hadoop.large-team-threshold":"50"},"jobs":[{"jobId":"parse-dblp","counters":{"records.in":10,"records.rejected":1},"outputs":[{"path":"s3://b/out/works","rowCount":9,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}]}
```
- Decision: this is a new type. `RunManifest` is frozen and has no fields for jobs or outputs, so it was not extended.
- `runId` is the existing `Ids.runId(stage, codeVersion, attempt, config, inputs)`. There is no new ID formula, and `PipelineRunManifest.build(...)` computes it. `snapshot` is descriptive and not hashed, because the input checksums already pin its content.
- Rules: `runId` matches the formula. `snapshot` is a token with no whitespace. `inputs` is non-empty, and every input must carry a SHA-256 checksum (stricter than `RunManifest`). Job IDs are distinct tokens. Counter names are tokens and counter values are `>= 0`. Output `path` is non-blank, `rowCount >= 0`, and the checksum, when present, is 64 lowercase hex characters. `createdAt` must parse as an ISO-8601 instant.
- Rejections to encode: a tampered `runId`; an input without a checksum; empty inputs; duplicate `jobId`; a negative counter or row count; a blank output path; a malformed output checksum.

### (3) Helpers

- **`ArxivId.parse(input): Either[ValidationError, ArxivRef(id, version: Option[Int])]` and `ArxivId.normalize(input): Either[ValidationError, String]`**
  - `normalize` returns the canonical form: lowercase, with the version stripped. Versions are revisions of one paper, so identity linking must not depend on them. `parse` returns the version separately.
  - Prefixes accepted, ASCII case-insensitive: `arxiv:` (optionally followed by spaces), and `http(s)://[www.]arxiv.org/abs/` or `/pdf/`. After `/pdf/`, a trailing `.pdf` is removed. Queries, fragments and percent-escapes are rejected.
  - New style must satisfy: YYMM 0704 to 1412 with 4 digits, or 1501 and later with 5 digits. Old style is `archive/YYMMNNN` for the years 1991 to 2007. The version, if present, is `v1` to `v9999`.
  - Errors are `Left(InvalidArxiv(input, reason))`, code `invalid-arxiv`.
  - Examples (observed):
    - `arXiv:2101.00001v3` gives `ArxivRef(2101.00001, Some(3))`.
    - `https://arxiv.org/abs/hep-th/9901001v2` gives `hep-th/9901001`.
    - `https://arxiv.org/pdf/1501.00001.pdf` gives `1501.00001`.
    - `math.GT/0309136` gives `math.gt/0309136`.
    - `2101.0001` gives `Left(InvalidArxiv(…, "identifiers from 1501 have 5 digits"))`.
- **`Titles.titleKey(title: String): String`**
  - This is the exact formula: NFKC, `toLowerCase(Locale.ROOT)`, `trim`, then `replaceAll("\\s+", " ")`.
  - It returns a string and may return an empty one. `workIdFallback` still rejects empty keys with `InvalidField("work", …)`.
  - Example: `Titles.titleKey("  Graph  Mining ")` gives `"graph mining"`.
  - **Equivalence evidence:** I moved the four-call chain verbatim out of `workIdFallback` into `Titles.titleKey`, and `workIdFallback` now calls it. The frozen vector `work:sc1:e47f9aa9…0a61` is asserted in `IdentifierContractSuite` at the existing lines 48-49 (`"  Graph  Mining "` and `"graph mining"` with year 2001, author `"a"`). That suite is green: 27/27 in the final `sbt check`. I did not build a second differential test.
- **`Doi.isDoiUrl(input): Boolean`**
  - True when the trimmed input starts with one of the four DOI resolver prefixes. It reuses the existing prefix list and the existing case rule.
  - `Doi.normalize` is unchanged, so the DOI vector suite (124) is untouched and green.
- **`ExternalLinks.doiFromEe(ee): Either[ValidationError, Option[String]]`**
  - Returns `Right(None)` for a non-DOI link, `Right(Some(doi))` for a DOI URL, and `Left(InvalidDoi)` for a DOI URL with an invalid DOI.
  - Examples (observed): `https://doi.org/bad` gives `Left(InvalidDoi(…))`. A DOI URL for `10.1145/3292500.3330701` gives `Some("10.1145/3292500.3330701")`.
- **`ExternalLinks.fromEe(ee): Either[ValidationError, Option[ExternalId(identifierType, value)]]`**
  - Handles DOI and arXiv links only.
  - Examples (observed): a DOI URL gives `ExternalId(Doi, "10.1145/3292500.3330701")`. `https://arxiv.org/abs/2101.00001` gives `ExternalId(Arxiv, "2101.00001")`. `db/journals/x.html` gives `Right(None)`.
  - There is no second DOI rule, because it delegates to `Doi.normalize`.
- **`OrderedPair.canonicalMentions(x, y)` and `OrderedPair.validateMentions(p)`**
  - The same canonical rule as for researcher IDs, applied to `mention:` IDs.
  - `MentionPairCandidate.build(workId, year, posA, posB, provenance)` uses them.

### (4) Config

- **Can the loader take module keys today?** No. `ConfigLoader` and `ConfigResolver` handle exactly the 21 keys in `ConfigKeys.all`. The file layer ignores any other key, and nothing reads system properties. The 21 keys' meaning and the existing loader behavior are unchanged.
- **Mechanism:** `ModuleConfig.resolve(keys, env, overrides, file)` and `ModuleConfig.load(keys, resource, env, overrides)`. The HOCON parse stays inside `ConfigLoader` as `moduleFileLayer`, so it is still the only code that imports typesafe config. Hadoop uses `HadoopConfigLoader.load(env = sys.env, overrides = Map.empty)` and `HadoopConfigLoader.resolve(...)` for pure use.
- **Hadoop keys and defaults:** the defaults live in `scholarchain-hadoop.v1.conf` and are not hardcoded in Scala.

| File key / `-D` name | Default | Validation |
|---|---|---|
| `hadoop.schema-version` / `scholarchain.hadoop.schema-version` | 1 | must be a supported schema version |
| `hadoop.large-team-threshold` | 50 | int >= 2 |
| `hadoop.pair-expansion-hard-cap` | 1225 | int >= 1 |
| `hadoop.reducers.normalize` | 2 | int >= 1 |
| `hadoop.reducers.teams` | 2 | int >= 1 |
| `hadoop.reducers.pairs` | 2 | int >= 1 |
| `hadoop.reducers.identifiers` | 2 | int >= 1 |
| `hadoop.output-codec` | `gzip` | `none`, `gzip` or `snappy` |

  The `-D` name is `scholarchain.` plus the file key for every row. The defaults are placeholders for hadoop-engineer to tune (1225 is C(50,2)).
- **Precedence, highest first:**
  1. Environment variable `SCHOLARCHAIN_HADOOP_<KEY>` (for example `SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD`). A blank value counts as unset.
  2. A caller-supplied overrides map keyed `scholarchain.hadoop.<key>`. This is where hadoop-engineer passes `-D` values from the Hadoop `Configuration`. JVM system properties are never read implicitly, matching the Phase 03 loader.
  3. The versioned file.
- This keeps the Phase 03 rule (environment first, then file) and adds the overrides layer in between.
- Origins are reported per key as `env:<NAME>`, `override:<name>` or `file`, and never include values. Errors name only the key.
- **Observed:** `load(Map.empty, Map.empty)` gives `HadoopConfig(1,50,1225,HadoopReducers(2,2,2,2),Gzip)`. With env threshold 7, override threshold 9 and override codec `none`: threshold 7 (origin `env:SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD`), codec `Uncompressed` (origin `override:scholarchain.hadoop.output-codec`), and the other keys from `file`.

### (5) Observed totals
Core: Total 307, Failed 0, Errors 0, Passed 307, from `sbt check` (the counts the run printed). The last `sbt check; echo "exit=$?" > .claude/.last-test-run` run showed `[success]`.

VALIDATION RESULTS
- `sbt check` is green with 307/307.
- scalafmt passes after I formatted `ModuleConfig.scala`.
- Contract tests for the new code do not exist yet, so code review and test-engineer's A2 are still pending.

ASSUMPTIONS
- The mention key is the existing `mention:<hex>` ID, which is `Ids.mentionId(workId, position)`.
- `schemaVersion` stays at 1 for all new types. They are new types, so the global `SchemaVersion` is not bumped. A bump would change what the frozen records accept.
- Outcome wire values are lowercase snake case, not the uppercase spelling in the work order.
- The `RejectReason` list, the config defaults, and the `.pdf`-suffix and `www.` arXiv URL forms are my choices.

RISKS
- The new wire shapes become contracts on first use. Changing the reason list, outcome names, `OutputCodec` names or any key name later needs a new schema version and a migration note.
- `ValidationError` gained a case (`InvalidArxiv`). No other module matches on it today.
- The ER records `PublicationTeam`, `AuthorshipRecord` and `CoauthorPairCandidate` coexist with the `Mention*` records. Phase 05 must define how mention records map onto them.
- The 307-test count did not change, because no new tests exist yet. The new code is only checked through REPL round-trips.

MANUAL ACTION REQUIRED: none from me. A human should ratify the open choices above: the lowercase outcome wire names, the redundant `schema` field on `RejectRecord`, the reason list, and the config defaults.

BLOCKED-ON OWNER: n/a (test-engineer's A2 is the planned next step, not a blocker for this report)

BLOCKED-ON EVIDENCE: n/a

NEXT GATE: test-engineer A2, where contract tests encode the rules and rejection cases above (plus a table-driven arXiv suite and config-precedence tests). Then the code-reviewer verdict, then human acceptance.

### Orchestrator check of A1 against the work order (before A2)

1. **OpenAlex IDs missing.** HJ3 (§5.3) needs DOI, arXiv, ORCID **and OpenAlex** identifiers. `IdentifierType` has only `doi|arxiv|orcid`.
2. **Hard-cap default would trigger on the planned runs.** §5.3 requires that the default `pairExpansionHardCap` not trigger on the 1K run or the planned 10K/50K runs. Observed maximum `n_authors` (awk over the selection TSVs): 1K max 42 (`journals/neuroimage/LucaILPSZANFBZF21`, 861 pairs); 10K max 88 (`journals/cphysics/DongVBMFPAAAABBBBCCCGLC25a`, 3,828 pairs, 1 work with n > 50); 100K max 182 (`conf/iccvw/KristanBZRGBDDN19`, 16,471 pairs, 10 works with n > 50). A default of 1,225 pairs would route the 10K run's largest team to oversize. (The 10K/100K selections are stale under policy 2, and are used here only as size proxies.)
Both routed to domain-engineer as repair A1r, before A2 freezes the contracts.

## Progress checkpoint

- Work order: A1 (domain-engineer). Status: COMPLETE, with repair A1r routed (OpenAlex identifier type; hard-cap default).
- Files touched: `modules/core/src/main/scala/scholarchain/core/{PipelineRecords.scala (new), ModuleConfig.scala (new), Identifiers.scala, Enums.scala, ValidationError.scala, ConfigLoader.scala}`, `modules/core/src/main/resources/scholarchain-hadoop.v1.conf` (new).
- Last test result: `sbt check` 307/307, `[success]` (A1's run).
- Pending: A1r, then A2 + DS-T (test-engineer), then B.

## Repair A1r — domain-engineer (final report, verbatim)

STATUS: COMPLETE

OWNER: domain-engineer

WORK ORDER: Repair A1r. Add OpenAlex identifier support (`openalex` type, `OpenAlexId` normalizer, evidence validation). Change the default `hadoop.pair-expansion-hard-cap` to 499500 and state its unit.

FILES CREATED: none

FILES MODIFIED (all under `/Users/dhruv_mehta/Desktop/ScholarChain/modules/core/src/main/`):
- `scala/scholarchain/core/Identifiers.scala`: added `OpenAlexEntity`, `OpenAlexRef` and `OpenAlexId`.
- `scala/scholarchain/core/ValidationError.scala`: added the `InvalidOpenAlex(input, reason)` case, with code `invalid-openalex` and a message.
- `scala/scholarchain/core/Enums.scala`: `IdentifierType.OpenAlex`, wire `"openalex"`.
- `scala/scholarchain/core/PipelineRecords.scala`: `ExactIdentifierEvidence` now validates `openalex` through `OpenAlexId.normalize`.
- `scala/scholarchain/core/ModuleConfig.scala`: doc comments now state the unit of each key.
- `resources/scholarchain-hadoop.v1.conf`: new cap default and an evidence comment.

OPENALEX FORMS SEEN: In `openalex_works_sample.jsonl` I read the first 3 lines. I counted `https://openalex.org/<letter>` over the whole file: 100 matches, all `W`. The field is `openalex_id`, for example `"https://openalex.org/W2718513939"`. The sample has no A, I, S or T IDs.

NEW SIGNATURES:
- `OpenAlexId.parse(input: String): Either[ValidationError, OpenAlexRef]`
- `OpenAlexId.normalize(input: String): Either[ValidationError, String]`
- `final case class OpenAlexRef(entity: OpenAlexEntity, id: String)`
- `enum OpenAlexEntity(letter: Char, apiPath: String)`: Work `W` (works), Author `A` (authors), Institution `I` (institutions), Source `S` (sources), Topic `T` (topics).
- `ValidationError.InvalidOpenAlex(input: String, reason: String)`

The input to output pairs below come from reading the code. I did not execute them, because no OpenAlex tests exist yet.
- `"W2741809807"` gives `Right("W2741809807")`.
- `"w2741809807"` gives `Right("W2741809807")`.
- `"https://openalex.org/W2718513939"` gives `Right("W2718513939")`.
- `"HTTP://OpenAlex.org/A5023888391"` gives `Right("A5023888391")`.
- `"https://api.openalex.org/works/W2718513939"` gives `Right("W2718513939")`.
- `"  A5023888391 "` gives `Right("A5023888391")`.

Rule:
- Trim whitespace, then remove at most one prefix, matched case-insensitively.
- The body is one letter from W, A, I, S, T plus 1 to 12 digits, with no leading zero.
- Concepts, funders and publishers are rejected.

WIRE EXAMPLE: not produced verbatim. I did not run serialization, and I did not want to show an unverified full record. The only new wire fact is the enum value: `"identifierType": "openalex"`, with `normalizedIdentifier` equal to a canonical ID such as `"W2718513939"`. test-engineer should capture the verbatim record from a round trip.

REJECTION CASES to encode (each gives `Left(InvalidOpenAlex(input, reason))`):
- `""`.
- `"W"` (no digits).
- `"2741809807"` (no letter).
- `"W0123"` (leading zero).
- `"W1234567890123"` (13 digits).
- `"C2741809807"` (concept) and `"F4320"` (funder), both unsupported entity kinds.
- `"https://openalex.org/W1/"` (trailing slash).
- `"https://openalex.org/W1?x=1"` (query).
- `"https://api.openalex.org/authors/W2718513939"` (path and letter disagree).
- `"https://api.openalex.org/concepts/C1"`.
- `"ftp://openalex.org/W1"`.
- `"W12 3"` (inner space).

In `ExactIdentifierEvidence`, an `openalex` record whose `normalizedIdentifier` is not canonical is rejected. Examples are a lowercase ID or a URL form, which give `InvalidField` via `V.matches`.

CONFIG TABLE ROWS:

| key | unit | default | min | env var |
|---|---|---|---|---|
| `hadoop.large-team-threshold` | authors, flag only | 50 | 2 | `SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD` |
| `hadoop.pair-expansion-hard-cap` | pairs, n(n-1)/2, not authors | 499500 (= C(1000,2)), was 1225 | 1 | `SCHOLARCHAIN_HADOOP_PAIR_EXPANSION_HARD_CAP` |

The conf comment cites the evidence: 1K run n=42 gives 861 pairs, 10K run n=88 gives 3,828 pairs, 100K proxy n=182 gives 16,471 pairs.

COMMANDS RUN:
- `sbt check; echo "exit=$?" > .claude/.last-test-run`: run four times from the repository root.
  - The first run failed on scalafmt.
  - I then ran `sbt "core/Compile/scalafmt"` (success, 4 files reformatted).
  - The second run failed to compile because of a duplicated `OpenAlexId` block. My first edit script had applied twice, because the guard-rejected call still seems to have run. I removed the duplicate with `sed`.
  - I ran `sbt "core/Compile/scalafmt"` again (success, 1 file).
  - The final `sbt check` run succeeded. It printed `[success] elapsed time: 14 s` and `markTestRun: wrote .../.claude/.last-test-run`. I did not read the marker file, so I did not see the `exit=` value.

TEST RESULTS: the final `sbt check` printed `Passed: Total 307, Failed 0, Errors 0, Passed 307` for core. The other modules reported "No tests to run". There are no tests for `OpenAlexId`, the `openalex` evidence type or the new config default yet.

VALIDATION RESULTS: compile and scalafmt are clean in the final run. No Phase 03 wire shape or ID formula was touched. The changes are additive (an enum case on `IdentifierType` and one on `ValidationError`).

ASSUMPTIONS:
- I accepted institutions, sources and topics on top of the required W and A.
- I accepted at most 12 digits.
- I rejected leading zeros so each ID has one canonical form.
- I read "50K run" as covered by the 10K and 100K evidence. I have no 50K measurement of my own.

RISKS:
- The new type or enum case can break any exhaustive match on `IdentifierType` or `ValidationError` in other modules. Currently only core matches on them.
- A future OpenAlex ID with more than 12 digits or a leading zero would be rejected.
- A cap of 499500 means one huge team could emit about 500K pairs for a single work. That is only bounded, not small.

MANUAL ACTION REQUIRED: none from me. Review is needed for the additive contract changes: the `IdentifierType` wire value `"openalex"` and the `ValidationError` code `invalid-openalex`.

BLOCKED-ON OWNER: n/a (the contract tests for these shapes are pending with test-engineer)

BLOCKED-ON EVIDENCE: n/a

NEXT GATE: test-engineer encodes the OpenAlex and config-default contract tests. Then comes the code-reviewer verdict, then human acceptance.

**Orchestrator note:** the agent reports that a guard-rejected edit call "still seems to have run", which produced a duplicate block it then removed. The orchestrator did not reproduce this. It is recorded for code-reviewer to check that `Identifiers.scala` has exactly one `OpenAlexId` definition.

## Progress checkpoint

- Work order: A1r (domain-engineer). Status: COMPLETE.
- Files touched: core `Identifiers.scala`, `ValidationError.scala`, `Enums.scala`, `PipelineRecords.scala`, `ModuleConfig.scala`, `scholarchain-hadoop.v1.conf`.
- Last test result: `sbt check` 307/307, `[success]` (A1r's final run).
- Next: A2 + DS-T (test-engineer, one dispatch), then B.

## Progress checkpoint

- Work order: A2 + DS-T (test-engineer). Report verbatim in `test-report.md`. Status: A2 green (217 new core contract tests; `sbt check` 524/524, marker `exit=0`; mutation proof shown). DS-T: 23 new extractor tests, 22 pass, 1 fail (comment leak in `datasets/scripts/dblp_extract_subset.py`); `tests/datasets` 83 run, 1 failure.
- Files touched: `modules/core/src/test/scala/scholarchain/core/{PipelineFx, PipelineRecordsContractSuite, ArxivIdContractSuite, OpenAlexIdContractSuite, IdentifierHelpersContractSuite, HadoopConfigContractSuite}.scala`, `tests/datasets/test_dblp_extract_subset.py`.
- Last test result: `sbt check` 524/524, `exit=0`.
- Routed: repair-01 (dataset-engineer) for the comment leak and the early DTD copy. Next: B (hadoop-engineer) after repair-01.

## Progress checkpoint

- repair-01 (dataset-engineer): COMPLETE (see repair-01.md). `tests/datasets` 83/83 OK; extractor output sha256 unchanged (`3dfcab53…`); validate_manifest 244 passed, 0 failed.
- Work order B (hadoop-engineer) dispatched. The agent stopped on the Claude usage limit (HTTP 429, session limit) right after starting to read the spec. The orchestrator confirmed nothing had been written under `modules/hadoop`. After the limit reset, the same agent was resumed (context preserved), not restarted.
- **Human instruction (speed rule, received mid-phase, applies to all agents for the rest of Phase 04):** while iterating, use only targeted commands (for example `sbt "hadoop/Test/compile"`, `sbt "hadoop/testOnly *ItSuite"`, `sbt "hadoop/testOnly <SuiteName>"`). Run the full canonical command once, at the end of each work order, before reporting. In integration suites, run the LocalJobRunner pipeline once per suite (`beforeAll`) with 1 reducer and assert many things against that one output, rather than launching a job per test. No test minimum or gate requirement is reduced. Relayed verbatim in substance to hadoop-engineer; it will be included in every later work order.
- Orchestrator interim run (Stop hook, during B's unfinished edits): `sbt check` **FAILED** at `hadoop / Compile / scalafmtCheck`, "6 files must be formatted" (Counters, Model, Normalizers, InputFormats, DblpParser, RunContext); marker exit=1. A snapshot of in-progress work only. hadoop-engineer was told to format before its final run.

## Work Order B — hadoop-engineer (final report, verbatim)

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 04 Work Order B. Implement the Hadoop MapReduce pipeline HJ1 to HJ5 in `modules/hadoop/**` with counters, reject-v1 output, provenance, determinism, idempotency, docs and integration tests, then run `sbt check` and the real 1K run.

FILES CREATED (all under `/Users/dhruv_mehta/Desktop/ScholarChain/modules/hadoop/`):
- `src/main/scala/scholarchain/hadoop/Counters.scala`: `PipelineCounter` enum, which is the single counter group.
- `Model.scala`: internal and hadoop-owned records (`IdRef`, `DblpWork`, `PersonAlias`, `DuplicateRecord`, `LargeTeam`, `OversizeTeam`, `Bundle`).
- `RunContext.scala`: run context, `Layout`, reject builder.
- `DblpParser.scala`: pure DBLP parser plus the entity table read from the local DTD.
- `InputFormats.scala`: `DblpRecordInputFormat` and its reader, `WholeFileInputFormat`, `JsonFileFilter`.
- `Normalizers.scala`: pure Crossref, OpenAlex and ORCID normalizers.
- `JobSupport.scala`: job setup, compression, `Tables` writer, `KeyHashPartitioner`.
- `Hj1DblpExtract.scala`, `Hj2SourceNormalize.scala`, `Hj3ExactIdentifier.scala`, `Hj4TeamPair.scala` (with `PairExpander`), `Hj5Export.scala`: the five jobs.
- `PipelineDriver.scala`: driver, args, run setup, manifest and counters.json, invariant check, one `Tool` per job.
- `JOB-CONTRACTS.md`: K/V contracts, HJ-to-J mapping, run command, pure-function API, known gaps.
- `tools/compare_roots.py` and `tools/summarize_run.py`: read-only per-table equality and summary tools.
- `src/it/scala/scholarchain/hadoop/`: `ItSupport.scala`, `PipelineItSuite.scala`, `DeterminismIdempotencyItSuite.scala`, `RecordReaderItSuite.scala`, `ParserSafetyItSuite.scala`.
- `src/it/resources/fixtures/`: synthetic DBLP xml, dtd and edge file, plus Crossref, OpenAlex and ORCID envelopes.

FILES MODIFIED: none.

COMMANDS RUN (exit status observed):
- `sbt "hadoop/scalafmt; hadoop/Test/scalafmt"` succeeded. It formatted source after the orchestrator's earlier `scalafmtCheck` failure.
- Targeted `hadoop/testOnly …` runs succeeded after fixes.
- Real 1K driver run into rootA: all 6 jobs succeeded.
- Real 1K driver run into rootB: all 6 jobs succeeded.
- `python3 -I modules/hadoop/tools/compare_roots.py rootA rootB` exited 0.
- `sbt check; echo "exit=$?" > .claude/.last-test-run` ran once. Marker file reads `exit=0`.

TEST RESULTS:
- Canonical command: `[success] elapsed time: 127 s`.
- Totals: core 524 passed, 0 failed (the baseline). Hadoop 28 passed, 0 failed.
- Test count delta: +28 integration tests, with no change to core. The tests are PipelineItSuite 16, DeterminismIdempotencyItSuite 5, RecordReaderItSuite 3, ParserSafetyItSuite 4.
- Job launches per suite:
  - PipelineItSuite: 6 (one full pipeline, 1 reducer per job, 700-byte splits).
  - DeterminismIdempotencyItSuite: 12 (two full pipelines). The refusal check launches no job.
  - RecordReaderItSuite: 2 (one map-only job with many splits, then the HJ1 tool).
  - ParserSafetyItSuite: 0.
- Determinism, synthetic: two roots built with different split sizes have identical sorted content in every table, the same runId, and identical manifest.json.
- Determinism, real 1K: see the 5.7 section below.

VALIDATION RESULTS (5.7 items, observed):

**Item 2. Exact run commands.** Run from the repo root, entered as one line, with `R=$PWD`, `D=$R/datasets/raw` and `X` set to `rootA` and then `rootB` (note: this one-line form is not the shell variable form shown in JOB-CONTRACTS.md):
```
sbt "hadoop/Test/runMain scholarchain.hadoop.PipelineDriver --dblp $D/dblp/derived/dblp_selection_1000.xml --dtd $D/dblp/derived/dblp.dtd --crossref $D/crossref/works --openalex $D/openalex/works --orcid $D/orcid/works --output $R/modules/hadoop/target/runs/$X --snapshot dblp-1k"
```
- Result: both runs reported `run run:5022d962ab647debb4d552d4166a72e5f0dbd08f5487b400c5e7223f4de5b329 complete`. Each took about 35 s.
- Documented defaults were used: 2 reducers per job, gzip, threshold 50, cap 499500.

**Item 3. Equality check** (`python3 -I modules/hadoop/tools/compare_roots.py <rootA> <rootB>`, exit 0):
```
author-mentions            rows A= 4711 B= 4711 sha256(A)=d256b2dccd5916c9 sha256(B)=d256b2dccd5916c9 EQUAL
authorship                 rows A= 3928 B= 3928 sha256(A)=248186564c4d8dfa sha256(B)=248186564c4d8dfa EQUAL
exact-identifier-evidence  rows A=  296 B=  296 sha256(A)=8c3095ff18d4572b sha256(B)=8c3095ff18d4572b EQUAL
institutions               rows A=  274 B=  274 sha256(A)=eaebefd812021429 sha256(B)=eaebefd812021429 EQUAL
pair-candidates            rows A= 8420 B= 8420 sha256(A)=21d3c21550328e07 sha256(B)=21d3c21550328e07 EQUAL
teams                      rows A= 1000 B= 1000 sha256(A)=5730a2f8d09e75e7 sha256(B)=5730a2f8d09e75e7 EQUAL
topics                     rows A=  300 B=  300 sha256(A)=85e23bbec3f9aec9 sha256(B)=85e23bbec3f9aec9 EQUAL
works                      rows A= 8468 B= 8468 sha256(A)=7fdf0465d7126ec4 sha256(B)=7fdf0465d7126ec4 EQUAL
RESULT: ALL TABLES EQUAL
```

**Item 4a. `counters.json` from rootA** (condensed from the printed file; per job, listing counters, then rows):
- hj1-dblp-extract:
  - ACCEPTED 1000, INPUT_DBLP 1000, INPUT_RECORDS 1000, SELF_PAIR_SKIPPED 0.
  - rows: author-mentions 3928, id-refs 2286, works 1000.
- hj2-source-normalize:
  - ACCEPTED 299, INPUT_CROSSREF 100, INPUT_OPENALEX 100, INPUT_ORCID 100, INPUT_RECORDS 300, MISSING_YEAR_KEPT 7, UPSTREAM_MISSING 1, WORK_SKIPPED 57.
  - rows: author-mentions 783, id-refs 7173, institutions 274, topics 300, works 7468.
- hj3-exact-identifier:
  - ACCEPTED 296, DUPLICATE 53, EXACT_LINK 243, INPUT_RECORDS 9459. CONFLICT and MISSING_KEY are 0.
  - rows: exact-identifier-evidence 296.
- hj4a-team-expand:
  - ACCEPTED 1000, EMITTED_PAIRS 8420, EXPECTED_PAIRS 8420, INPUT_RECORDS 4928.
  - rows: authorship 3928, pair-stage 8420, teams 1000.
- hj4b-pair-dedupe:
  - ACCEPTED 8420, DUPLICATE_PAIR 0, INPUT_RECORDS 8420.
  - rows: pair-candidates 8420.
- hj5-graph-candidate-export:
  - ACCEPTED 27397, INPUT_RECORDS 27397.
  - rows: works 8468, author-mentions 4711, authorship 3928, teams 1000, pair-candidates 8420, evidence 296, institutions 274, topics 300.
- The `rejects` group is empty in every job.
- Every job reports `"success": true`. The full file is at `modules/hadoop/target/runs/rootA/run/snapshot=dblp-1k/run_id=5022d962…/counters.json`.

**Item 4b. Per-table row counts (rootA):** author-mentions 4711, authorship 3928, exact-identifier-evidence 296, institutions 274, pair-candidates 8420, teams 1000, topics 300, works 8468.
- person-aliases, duplicates, large-teams, oversize-teams and rejects have no rows and no directory.

**Item 4c. Rejects by reason code:** none. The 1K selection is clean, so the reject path was exercised only on the synthetic fixtures.

**Item 4d. 3 largest teams (workId, n, pairs):**
```
work:dblp:journals/neuroimage/LucaILPSZANFBZF21      42  861
work:dblp:journals/remotesensing/JanardananMTWTV20   23  253
work:dblp:conf/igarss/ArcioniBBDDDFGHHIKKKLLLMSS10   20  190
```
- Maximum observed team size is 42. The largest pair expansion is 861 pairs.
- OVERSIZE_AUTHOR_TEAM and LARGE_AUTHOR_TEAM are both 0 at the defaults (50 and 499500).

**Item 4e. One sample record per table (rootA).** All provenance blocks have the same shape. I show one in full and abbreviate the rest to `prov{…}`.
- Full provenance example, from `authorship`: `"provenance": {"schemaVersion":1, "sourceType":"dblp", "inputs":[{"inputId":"dblp/dblp_selection_1000.xml","version":"dblp-1k","checksum":"3dfcab53e2764639f8777707fe55f2c3bc067e040282c63c24cdc72341868db6"},{"inputId":"dblp/dblp.dtd","version":"dtd","checksum":"2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6"}], "codeVersion":"scholarchain-hadoop-0.1.0", "parameters":{…, "job":"hj4a-team-expand", …}, "runId":"run:5022d962ab647debb4d552d4166a72e5f0dbd08f5487b400c5e7223f4de5b329"}`
- works: `{"workId":"work:crossref:10.1007/978-3-319-60525-8_36","title":"STAMP Analysis of the Hanmi Short Selling Episode","year":2017,"doi":"10.1007/978-3-319-60525-8_36","topicIds":[],"provenance":prov{crossref, tree input plus envelope file 53e71fe3….json with its checksum, retrievedAt 2026-11-20T03:34:47Z}}`
- author-mentions: `{"mentionId":"mention:0000ce5a…","workId":"work:dblp:conf/cdc/SandhuLDT09","position":3,"rawName":"Allen R. Tannenbaum","provenance":prov{dblp, parameters.inputByteOffset 111362}}`
- authorship: `{"mentionId":"mention:0000ce5a…","workId":"work:dblp:conf/cdc/SandhuLDT09","position":3,"year":2009,"provenance":prov{…}}`
- teams: `{"workId":"work:dblp:conf/aaai/Biswas0BP22","year":2022,"teamSize":4,"mentionIds":[4 ids],"provenance":prov{…}}`
- pair-candidates: `{"workId":"work:dblp:conf/aaai/Biswas0BP22","year":2022,"pair":{"first":"mention:63b11c9d…","second":"mention:d7bedc48…"},"firstPosition":1,"secondPosition":3,"provenance":prov{…}}`
- exact-identifier-evidence: `{"identifierType":"doi","normalizedIdentifier":"10.1007/978-3-319-60525-8_36","linkedRecordIds":["work:crossref:10.1007/978-3-319-60525-8_36","work:dblp:conf/ahfe/LeeKS17","work:openalex:W2718513939"],"outcome":"exact_link","provenance":prov{scholarchain, dblp xml, openalex tree, crossref tree, dtd}}`
- institutions: `{"institutionId":"institution:name:095468ed…","name":"Institute of Information Management, Fuzhou University, Fuzhou, China","provenance":prov{crossref, envelope 4ebe7cc8….json}}`
- topics: `{"topicId":"topic:openalex:T10015","label":"Genomics and Phylogenetic Studies","provenance":prov{openalex, envelope 313ab1dc….json}}`
- person-aliases, rejects, duplicates, large-teams, oversize-teams: NOT AVAILABLE in the real run (0 rows). They are covered by the synthetic PipelineItSuite.
- No abstracts are stored, because core has no field for them.

**Other facts**
- Job graph, K/V contracts, partitioner, reducer defaults, and the exact local command are in `modules/hadoop/JOB-CONTRACTS.md`.
- Reducer counts: HJ1 and HJ2 use 2 (`reducers.normalize`), HJ3 uses 2, HJ4a uses 2, HJ4b uses 2, HJ5 uses 1 (`export-reducers`). Partitioner is `KeyHashPartitioner` everywhere.
- Output paths follow `<root>/curated/<table>/snapshot=dblp-1k/run_id=<hex>/[year=YYYY/]part-r-0000N.gz`.
- Manifest, counters.json and `_SUCCESS` are in `<root>/run/snapshot=dblp-1k/run_id=<hex>/`.
- Provenance: every record type with a `provenance` field carries inputs with checksums, the code version, the parameters and the run ID. `RejectRecord` has no provenance field by contract. It links to the manifest through `runId`, plus `jobId`, `sourceRecordId` and `inputByteOffset`.
- Local-run feasibility: `sbt "hadoop/Test/runMain …"` works with no build change.

ASSUMPTIONS:
- ORCID work groups are normalized into `WorkRecord`s (source `orcid`). ORCID data stays in gitignored `target/` and the fixtures are synthetic. The ORCID iDs in fixtures are public documentation examples.
- Missing year on a Crossref or OpenAlex record: kept for identifiers and mentions, counted, no `WorkRecord`, because `WorkRecord.year` is mandatory.
- Team size is the number of distinct author strings. A repeat is dropped (SELF_PAIR_SKIPPED) and homonym suffixes stay distinct.
- Directory names use `run_id=<hex>`, without the `run:` prefix, because Hadoop `Path` cannot hold a colon.
- The default `--code-version` is the constant `scholarchain-hadoop-0.1.0`. Pass the git SHA for real runs.

RISKS:
- Core gaps (no `domain-engineer` change requested): `WorkRecord` has no abstract, venue name, pages, ee or url, or record type. `AuthorMention` has no affiliation link. Institutions are per source record and not deduplicated. These values are parsed but not persisted.
- Enrichment input uses one split per file. At 100K files this needs `CombineFileInputFormat`.
- HJ5 defaults to 1 reducer, which is a bottleneck at 50K scale. Raise `scholarchain.hadoop.export-reducers`.
- No logger is bound locally (SLF4J no-op).
- The DTD is read from the given path through the Hadoop FileSystem rather than the distributed cache. This works for `s3://` paths but was not tested on EMR.
- No EMR or Snappy run was done. Snappy is documented only.
- The reject path on real data is untested, since the 1K selection has no rejects.

MANUAL ACTION REQUIRED: none for this work order. EMR execution needs an approved bounded run through deploy-engineer.

BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a

NEXT GATE: Work Order C (test-engineer, unit and adversarial tests against the pure-function API in `JOB-CONTRACTS.md` section 7), then the code-reviewer verdict, then human acceptance.

### Orchestrator verification of B (observed)
- Marker after B: `exit=0`.
- Independent check against the selection TSV (awk over `datasets/samples/dblp/dblp_selection_1000.tsv`): sum of `n_authors` = 3928 (= B's DBLP author-mentions / authorship rows) and sum of n(n-1)/2 = 8420 (= B's EMITTED_PAIRS and pair-candidates rows).
- `grep -rnE "\.delete\(|deleteOnExit|FileUtil\.fullyDelete|rm -"` over `modules/hadoop/src` and `modules/hadoop/tools`: no matches.
- `modules/hadoop/src` contains only `it` and `main` (no `src/test` writes by hadoop-engineer).
- Rerun by the orchestrator: `python3 -I modules/hadoop/tools/compare_roots.py modules/hadoop/target/runs/rootA modules/hadoop/target/runs/rootB` → `RESULT: ALL TABLES EQUAL`.
- Points passed to code-reviewer: hadoop-owned records in `Model.scala` (notably `PersonAlias`, a Phase 05 input) are not core contracts; parsed fields not persisted (abstract, venue name, pages, ee/url, record type, affiliation links) against §5.3 "preserve … per the contracts"; constant default `--code-version`; JOB-CONTRACTS.md command form differs from the one-line form actually run; real-data reject path not exercised.

## Progress checkpoint

- Work order: B (hadoop-engineer). Status: COMPLETE.
- Files touched: `modules/hadoop/**` (main, it, JOB-CONTRACTS.md, tools/), all new.
- Last test result: `sbt check` 524 core + 28 hadoop = 552 passed, `exit=0`.
- Next: C (test-engineer).

## Progress checkpoint

- Work order: C (test-engineer). Status: COMPLETE. Report verbatim in `test-report.md`.
- Files touched: `modules/hadoop/src/test/**` (7 Scala files, 6 synthetic fixtures), all new.
- Last test result: `sbt check` core 524 + hadoop 90 = **614 passed, 0 failed**; the orchestrator read the marker as `exit=0`. Delta from the 307 baseline: +307 (A2 217, B 28, C 62). Gate §10 requires at least +50 plus A2's tests; observed +90 plus A2's 217.
- Documentation gaps 1–5 from C (hadoop-engineer) are held for the review, so that all hadoop-engineer findings go in one repair round.
- Next: D (code-reviewer).

## Progress checkpoint

- Work order: D (code-reviewer), pass 1: REQUEST_CHANGES (F1 blocking). Verbatim in `review-report.md`.
- Repair 02, round 1 (see `repair-02.md`): dataset-engineer F11 COMPLETE; hadoop-engineer F1–F5, F8–F10 COMPLETE (real 1K rerun into rootC/rootD, ALL TABLES EQUAL, runId `run:2bfe1482…`); test-engineer unit regressions COMPLETE (+14).
- Held for the human gate: F6, F7, reject provenance via runId only, A1's four wire choices, and hadoop-engineer's extension of F5 to institutions and topics.
- Last test result: `sbt check` core 524 + hadoop 114 = **638 passed, 0 failed**, `[success]`.
- Next: code-reviewer pass 2.

## Progress checkpoint (final; stopped at the human gate)

- code-reviewer pass 2: **APPROVE WITH NOTES** (verbatim in `review-report.md`). All routed findings F1–F5 and F8–F11 FIXED. New non-blocking notes N1 (MEDIUM, provenance growth and reducer memory for skewed entity keys at full scale), N2–N4 (LOW). The repair loop ended after round 1 of 2.
- Last test result: `sbt check` 638 passed, 0 failed; marker `exit=0` (read by the orchestrator).
- Not done by any agent: human-gate.md, commit.txt, any commit, any AWS call, any EMR run, any Phase 05 work.
