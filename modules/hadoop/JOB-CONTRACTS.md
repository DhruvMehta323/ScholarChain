# Hadoop job contracts (Phase 04)

Owner: hadoop-engineer. Package `scholarchain.hadoop`. Hadoop 3.4.2 (client jars `Provided`), MapReduce API
(`org.apache.hadoop.mapreduce`), Scala 3, no new dependencies.

## 1. Mapping to the spec jobs

| Job | Class | Spec | Notes |
|---|---|---|---|
| HJ1 | `DblpExtractJob` | J01 | DBLP XML to works, mentions, aliases, duplicates, rejects |
| HJ2 | `SourceNormalizeJob` | J02 | Crossref, OpenAlex, ORCID envelopes to the shared contracts |
| HJ3 | `ExactIdentifierJob` | J03 + J04 | exact-identifier evidence (not decisions) |
| HJ4a/b | `TeamPairJob` (`submitTeams`, `submitPairs`) | J07 + J08 | teams, authorship, pair candidates |
| HJ5 | `GraphCandidateExportJob` | J09 | Spark-ready curated layout |
| deferred | none | **J05 BlockingKeyJob, J06 CandidateJoinJob** | **Deferred to Phase 05** (rule 0.3). No blocking keys, match features, scores, thresholds or decisions exist in this module. |

Job graph (all intermediate tables are `sortKey TAB json` lines under `<root>/work/<job>/<table>/`):

```
dblp xml + dtd --HJ1--> hj1/{works, author-mentions, id-refs, person-aliases, duplicates, rejects}
crossref/openalex/orcid --HJ2--> hj2/{works, author-mentions, institutions, topics, id-refs, duplicates, rejects}
hj1/id-refs + hj2/id-refs --HJ3--> hj3/exact-identifier-evidence (+ rejects)
hj1/works + hj1/author-mentions --HJ4a--> hj4a/{teams, authorship, large-teams, oversize-teams, pair-stage, rejects}
hj4a/pair-stage --HJ4b--> hj4b/pair-candidates
all tables --HJ5--> <root>/curated/<table>/snapshot=<s>/run_id=<hex>/[year=<y>/]part-r-NNNNN.gz
```

## 2. Running locally (exact command)

Run from the repository root. `Provided` Hadoop jars are on the Test classpath, so no build change is needed.
`--output` must be a NEW root under `modules/hadoop/target/` (gitignored). Use `file://` (or plain absolute paths) locally,
`s3://` on EMR later.

```
sbt "hadoop/Test/runMain scholarchain.hadoop.PipelineDriver \
  --dblp   $PWD/datasets/raw/dblp/derived/dblp_selection_1000.xml \
  --dtd    $PWD/datasets/raw/dblp/derived/dblp.dtd \
  --crossref $PWD/datasets/raw/crossref/works \
  --openalex $PWD/datasets/raw/openalex/works \
  --orcid  $PWD/datasets/raw/orcid/works \
  --output $PWD/modules/hadoop/target/runs/<new-root-name> \
  --snapshot dblp-1k \
  --code-version "$(git rev-parse --short HEAD)+uncommitted""
```

(Enter it on one line.) `--code-version <token>` is **required** (no default; a missing or whitespace value exits 2 before anything is
written). It names the code that produced the output and is part of the run ID, so use exactly the form
`"$(git rev-parse --short HEAD)+uncommitted"` for a working tree with uncommitted changes, and `"$(git rev-parse --short HEAD)"` for a clean
committed tree (on EMR too). Optional: `--attempt <n>` (default 1). Settings go through `-D` (see section 4), for example
`-Dscholarchain.hadoop.large-team-threshold=50`. The same jar runs on EMR as
`hadoop jar scholarchain-hadoop-assembly.jar scholarchain.hadoop.PipelineDriver <same arguments>`; no code change.
Exit codes: 0 success, 1 job or invariant failure, 2 bad arguments or refused run.

Tools: `python3 -I modules/hadoop/tools/compare_roots.py <rootA> <rootB>` (per-table sorted-line SHA-256 equality) and
`python3 -I modules/hadoop/tools/summarize_run.py <root> [--counters]` (read-only).

One `Tool` per job also exists (`DblpExtractTool`, `SourceNormalizeTool`, `ExactIdentifierTool`, `PublicationTeamPairTool`,
`GraphCandidateExportTool`); they take the same arguments and read the earlier jobs' outputs from the root's `work/` directory.

## 3. Run layout, idempotency, determinism

- Run ID: `Ids.runId("hadoop-pipeline", codeVersion, attempt, config, inputs)` (Phase 03 formula, content-derived, no clock).
  Directory names use the hex part (`run_id=<64 hex>`): `run:` contains a colon, which Hadoop `Path` cannot hold.
- `<root>/run/snapshot=<s>/run_id=<hex>/` holds `manifest.json` (`PipelineRunManifest`, no `createdAt`), `counters.json`
  (every job's counters from `Job.getCounters`) and `_SUCCESS` (written last).
- **Idempotency:** if that directory already holds `_SUCCESS` and `manifest.json`, the driver exits 2 with a message before any
  job starts. There is no overwrite flag and no delete code in the module; Hadoop itself refuses an existing job output
  directory. A rerun goes to a new output root. Files are created with `fs.create(path, false)`.
- **Determinism:** every reducer sorts its values (JSON strings) before use, and sets that reach output (HJ3 `sources`, `provenance.inputs`,
  the merged `provenance.inputs` of HJ5) are sorted explicitly, because on a cluster the order of values within a key is arbitrary. Audited:
  HJ1 (`DblpReducer`: values sorted, winner by byte offset), HJ2 (`NormalizeReducer`: sorted, winner by `retrieved_at` then checksum), HJ3
  (`IdentifierReducer`: refs sorted, then classified; fixed in repair-02), HJ4a (`TeamReducer`: sorted, mentions by position), HJ4b
  (`PairReducer`: sorted, groups by work id), HJ5 (`ExportReducer`: sorted, plus the merge rule of section 6). No HashMap iteration order reaches output; partitioning is a fixed hash
  of the key bytes; records carry no wall-clock value. Two roots built from the same inputs and config are logically identical
  (compare with `compare_roots.py`). Compressed byte layout is not part of the check.
- Scratch (`hadoop.tmp.dir`, staging) is set to `<root>/_tmp` on a local file system.

## 4. Configuration

`HadoopConfigLoader` (core). Precedence: environment `SCHOLARCHAIN_HADOOP_*`, then `-D scholarchain.hadoop.<key>`, then
`scholarchain-hadoop.v1.conf`. Keys: `large-team-threshold` (50, authors), `pair-expansion-hard-cap` (499500, pairs),
`reducers.{normalize,teams,pairs,identifiers}` (2 each), `output-codec` (gzip), `schema-version`. Two Hadoop-side keys outside
the core contract: `scholarchain.hadoop.export-reducers` (HJ5 reducers, default 1) and `scholarchain.hadoop.dblp.max-record-bytes`
(default 16 MiB; a longer record is cut and rejected as `oversized_record`; it must be an integer >= 1 or the run is refused). The resolved
values of all of these (`hadoop.dblp.max-record-bytes` and `hadoop.export-reducers` included) are put in the manifest `config`, hence in the run
ID, and in every record's `provenance.parameters` (with `job`). Any setting that can change output must be added there; nothing else is read from
the Hadoop configuration by the tasks.
Compression: map output is always compressed (codec follows `output-codec`; `none` uses DefaultCodec). Output codec `gzip`
(portable default), `snappy` (documented for EMR; native Snappy may be missing on macOS), or `none`.

## 5. Provenance

Every record type with a `provenance` field gets a real one: `inputs` (with SHA-256 checksums), `codeVersion`, `parameters`, `runId`.
DBLP records: the XML and the DTD (checksums of the files; the XML record's byte offset is in `parameters.inputByteOffset`).
Enrichment records: the source's tree input (`<source>/works`, checksum = SHA-256 of the sorted `name:sha256` lines of its `*.json`
files) plus the single envelope file (name, `retrieved_at`, checksum), and `retrievedAt`. Evidence records: the inputs of every source
that took part. **`RejectRecord` has no provenance field by contract**; it links to the manifest through `runId` and names its
origin through `jobId`, `sourceRecordId` and `inputByteOffset`.

## 6. Job contracts

All jobs: text output (JSON Lines, `LazyOutputFormat`, tables through `MultipleOutputs`), codec as in section 4, partitioner
`KeyHashPartitioner` (hash of the key bytes, deterministic), no combiner (none is semantically safe: every reducer needs the full
group). Counter group: enum `PipelineCounter` (group name `scholarchain.hadoop.PipelineCounter`) plus group `rows` (rows written per
table) and group `rejects` (per reason code). INPUT_RECORDS = records the mappers read; ACCEPTED = records in the job's primary output;
REJECTED = reject rows.

### HJ1 DblpExtractJob
- Input: `DblpRecordInputFormat` (key `LongWritable` byte offset of the record's start tag, value `BytesWritable` raw bytes of one top-level
  record). Synchronizes on `<article|inproceedings|proceedings|book|incollection|phdthesis|mastersthesis|www|data` start tags; a record belongs to
  the split its start tag begins in; the reader reads past the split end. **Compressed input (`.gz`, any Hadoop codec) is not splittable: it is one
  split, read whole by one mapper, and the split-end cut does not apply** (the split length is the compressed size, offsets count decompressed
  bytes; fixed in repair-02, regression test `RecordReaderItSuite` F1). Keys and `inputByteOffset` are then offsets in the DECOMPRESSED stream.
  The XML declaration encoding is sniffed on the decompressed bytes. A single mapper reads the whole file, so full DBLP on EMR runs HJ1's map
  phase on one task (use the uncompressed file for parallelism).
  **Unclosed records:** the reader stops a record at the next top-level start tag (outside CDATA sections and comments), leaves that tag unread and
  emits the truncated record, which the parser rejects as `malformed_record`; the next record is read normally. Inside `<![CDATA[...]]>` and
  `<!--...-->` neither a start tag nor an end tag counts.
- Map out: key `Text` DBLP key, value `ParsedDblp` JSON; persons under key `!person|<key>`; rejects under `!reject|<offset>`.
- Reduce (default 2 reducers): duplicates by DBLP key, winner = lowest byte offset (others to `duplicates`, counter DUPLICATE); emits `WorkRecord`,
  `AuthorMention`, id references, `PersonAlias`. Team of mentions = distinct author strings (see HJ4).
- Parser: DBLP named entities come from the LOCAL DTD passed as `--dtd` (read in `setup()` through the Hadoop FileSystem; on EMR an `s3://` or
  distributed-cache path works the same). Only `<!ENTITY name "&#NNN;">` declarations are used. The record is parsed with the JDK default StAX
  reader with external entities and DTD access disabled and a resolver that throws; a record containing any markup declaration is rejected.
- Rejects (reject-v1): `malformed_record` (XML error, undeclared entity, record-level DOCTYPE/ENTITY, no key), `encoding_error`, `missing_title`,
  `missing_year`, `invalid_year`, `missing_authors` (editors are not authors), `unsupported_type` (for example `data`), `oversized_record`,
  `schema_violation` (contract refused the record, counter SCHEMA_FAILURE). A bad `ee` URL or ORCID attribute is skipped and counted
  (INVALID_IDENTIFIER_SKIPPED); the record is kept.
- Other counters: INPUT_DBLP, WWW_RECORDS, PERSON_ALIASES, DUPLICATE, SELF_PAIR_SKIPPED (a repeated author string in one list is dropped).
- Fixtures: `dblp-synth.xml`, `dblp-synth.dtd`, `dblp-edge-synth.xml` (all synthetic).

### HJ2 SourceNormalizeJob
- Input: `MultipleInputs`, one `WholeFileInputFormat` per source (key = file path, value = file bytes), path filter `JsonFileFilter` (`*.json` and
  extension-less names; skips `errors.jsonl`). The driver's input checksum uses the very same filter (`RunSetup.JsonFilter`), so it covers exactly the
  files the job processes. Mappers: `CrossrefNormalizeMapper`, `OpenAlexNormalizeMapper`, `OrcidNormalizeMapper`.
- Format read (Phase 03 raw envelope, `datasets/schemas/*.md`): `{identifier, request_url, retrieved_at, http_status, missing, body_is_json, body, ...}`.
  Chosen because it is what the fetchers store (it carries `retrieved_at` for provenance and `missing` for 404s); the committed samples hold no
  names or IDs. A `missing:true` envelope is counted (UPSTREAM_MISSING) and is not a record.
- Map out: key `<source>|<source record id>` (DOI for Crossref, `W...` for OpenAlex, the ORCID iD), value `Bundle` JSON (rows). Reduce
  (default 2): dedupe by source record id, winner = latest `retrieved_at`, ties by file checksum; losers to `duplicates`.
- Crossref: `work:crossref:<doi>`; title (markup stripped), year from `issued`, then `published`, `published-print`, `published-online`;
  authors (given+family, or `name`), ORCID, affiliations to `Institution` (`institution:name:<sha256 of titleKey>`), `subject` to `Topic`
  (`topic:name:<sha256>`). Missing year follows the Phase 03 rule: kept (identifiers and mentions), counted MISSING_YEAR_KEPT, and no `WorkRecord`
  is written because `WorkRecord.year` is mandatory in core.
- OpenAlex: `work:openalex:W..`; DOI, title, `publication_year`, authorships (display name, ORCID, OpenAlex author id kept as an id reference),
  institutions (`institution:openalex:I..`, country), topics (`topic:openalex:T..`).
- ORCID (works section): per work group a `WorkRecord` (`work:doi:<doi>`, else `Ids.workIdFallback(title, year, orcid)`), id references for DOI and
  arXiv; the iD itself is an id reference `orcid:<id>`. A group with no year is skipped and counted (WORK_SKIPPED). No person data beyond the iD is kept.
- Rejects: `malformed_record` (not JSON, no `message`), `schema_violation` (bad `retrieved_at`, contract refusal), `invalid_identifier` (bad DOI,
  OpenAlex id or ORCID), `missing_title`.
- Counters: INPUT_CROSSREF, INPUT_OPENALEX, INPUT_ORCID, UPSTREAM_MISSING, MISSING_YEAR_KEPT, WORK_SKIPPED, INVALID_IDENTIFIER_SKIPPED, DUPLICATE.
- Fixtures: `crossref/*`, `openalex/*`, `orcid/*` (synthetic; ORCID iDs are public documentation examples).

### HJ3 ExactIdentifierJob
- Input: `TextInputFormat` over `hj1/id-refs` and `hj2/id-refs` (`IdRef` JSON). Map out key `<type>|<normalized value>` (types doi, arxiv, orcid,
  openalex), value `IdRef`. A work with no external id emits type `none` and is counted MISSING_KEY. Reduce (default 2): groups of fewer than two
  distinct records write nothing.
- Classification (priority): CONFLICT (doi/arxiv/openalex key on several distinct DBLP works, or the group disagrees on another authoritative
  id), EXACT_LINK (DBLP plus at least one other source), DUPLICATE (several records of one source). For ORCID keys several mentions of one source
  is DUPLICATE (one iD on several mentions). Output `ExactIdentifierEvidence` (no score, confidence or threshold).

### HJ4 PublicationTeamPairJob (J07 + J08)
- HJ4a input: `hj1/works` and `hj1/author-mentions`; map out key work id, value `W<TAB>json` or `M<TAB>json`; reduce keyed by work id (default 2):
  `MentionTeam`, one `MentionAuthorship` per mention, `LargeTeam` / `OversizeTeam` flags, staged pairs. The mention-keyed records are used,
  not the researcher-keyed post-ER records.
- **Team size** = the number of distinct mentions of the work: distinct author strings after duplicate-name handling (a repeat is dropped in HJ1,
  counter SELF_PAIR_SKIPPED; homonym suffixes such as `Wei Wang 0001` and `0002` are distinct). A team of one has no pairs.
- Large team: n > `large-team-threshold` flags it (LARGE_AUTHOR_TEAM, `large-teams` row) and still emits pairs. Pair count > `pair-expansion-hard-cap`:
  the whole team goes to `oversize-teams` (OVERSIZE_AUTHOR_TEAM) and no pair is emitted; team and authorship rows are still written. Never truncated.
- Invariant: EMITTED_PAIRS (counted per pair written) must equal EXPECTED_PAIRS (sum of n(n-1)/2 over non-oversize teams, from the formula); the driver
  also checks ACCEPTED + DUPLICATE_PAIR of HJ4b equals EMITTED_PAIRS and that HJ5 wrote that many rows, and fails the run (exit 1, no manifest) otherwise.
- HJ4b input: `hj4a/pair-stage` (`<first>|<second><TAB>json`); key = canonical pair key; partitioner `KeyHashPartitioner`; reducers
  `reducers.pairs` (2). One candidate per (pair, work); a repeat is dropped and counted DUPLICATE_PAIR. Weights are not aggregated (Spark, Phase 06).

### HJ5 GraphCandidateExportJob
- Input: every intermediate table directory; key `table SOH partition SOH sortKey`; reducers `export-reducers` (1). Writes
  `curated/<table>/snapshot=<s>/run_id=<hex>/[year=<y>/]part-r-NNNNN.gz`. `authorship` and `pair-candidates` are partitioned by `year=YYYY`
  (`year=unknown` when a record has no valid year; core requires a year, so it does not occur today).
- **Unique IDs (repair-02, F5).** `works`, `institutions` and `topics` have exactly one row per `workId` / `institutionId` / `topicId`. The same ID
  arises when several ORCID records list one DOI (`work:doi:<doi>`), and when one institution or topic occurs in several works (HJ2 writes them per
  work). HJ5 keys these tables by the entity ID and merges the rows of one ID: the **winner** is the row with the latest `provenance.retrievedAt`
  (an absent value sorts first), ties broken by the larger JSON text, so the result does not depend on value order. The winner keeps its fields and
  its `provenance.parameters`; its `provenance.inputs` become the **sorted union** (by inputId, version, checksum) of the `inputs` of every merged
  row, so no provenance input is lost. Counters (group `PipelineCounter`, job HJ5): `WORK_MERGED` (works rows merged away) and `ENTITY_MERGED`
  (institutions and topics rows merged away). Row-count identity: `rows(works) = hj1 works + hj2 works - WORK_MERGED`. Other curated tables are
  already unique by their ID by construction (`author-mentions` and `authorship` by mentionId, `teams` / `large-teams` / `oversize-teams` by workId,
  `person-aliases` by aliasKey, `exact-identifier-evidence` by (type, value); `pair-candidates` by (pair, work)); `duplicates` and `rejects` are logs.
- Tables: works, author-mentions, authorship, teams, pair-candidates, exact-identifier-evidence, institutions, topics, person-aliases, rejects,
  plus duplicates, large-teams, oversize-teams. An empty table has no directory.

## 7. Pure-function API (what unit tests call)

- `DblpEntities.fromDtd(bytes): DblpEntities`
- `DblpParser(entities, charset).parse(recordBytes): Either[ParseFailure, ParsedRecord]` and `DblpParser.parseRecord(bytes, charset, entities)`;
  `ParsedWork(DblpWork, warnings)`, `ParsedPersonRecord(DblpPerson, warnings)`; `ParseFailure(reason: RejectReason, message, sourceRecordId)`.
- `Normalizers.crossref | openalex | orcid (fileName, bytes, run: RunContext): Either[ParseFailure, Normalized]` (`Normalized.rows` are `Row(table, sortKey, json)`).
- `PairExpander.pairCount(n): Long`; `PairExpander.expand(workId, year, teamSize, prov): Iterator[Either[ValidationError, MentionPairCandidate]]`.
- `RunContext(runId, codeVersion, snapshot, attempt, config, inputs, dblpCharset)` with `config` keys `hadoop.large-team-threshold`, `hadoop.pair-expansion-hard-cap`, ...
  and `inputs` keys `dblp`, `dtd`, `crossref`, `openalex`, `orcid`.
- `PipelineArgs.parse`, `RunSetup.prepare`, `PipelineDriver.invariantError(outcomes)`.

## 8. Known gaps (need a core contract, not worked around)

- **Author names are kept code-point exact.** No Unicode normalization is applied: the NFC and NFD forms of "Müller" are two distinct mentions (and
  give one pair). Case, spacing beyond trimming and homonym suffixes are untouched too.
- **No confusable folding.** A Cyrillic U+0430 inside a Latin name is a different string and therefore a different author at this stage. Folding
  confusables, accents or transliterations is entity-resolution match policy (Phase 05), not a Hadoop rule.
- **A DOI is derived from a DBLP `ee` only when `ee` is a DOI URL** (recognised by core `ExternalLinks.fromEe`). A bare DOI string or a `doi:10.x/y` value in `ee` is ignored (no DOI, no reject).
- A reject row that fails `reject-v1` validation is never written: the task fails loudly (`IllegalStateException`). A reject carries an
  `inputByteOffset` only when it is a real offset (DBLP records, whole-file envelopes at 0); identifier-evidence rejects carry just the key.
- `WorkRecord` has no field for abstract, venue name, volume, pages, DBLP `ee`/`url` links or record type; `AuthorMention` has no affiliation link;
  `Institution` and `Topic` rows are merged by ID in the export (see section 6, HJ5). The parsed values exist in `DblpWork` but are not persisted.
- `WorkRecord.year` is mandatory, so a year-less Crossref/OpenAlex record has no work row (see HJ2).
- Enrichment input is one split per file; at 100K files use `CombineFileInputFormat` (not done in Phase 04).
- Hadoop task logging uses the SLF4J no-op binding locally (no logger on the classpath).
