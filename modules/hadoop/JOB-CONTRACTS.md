# Hadoop job contracts (Phase 04, extended in Phase 05)

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
| HJ6a-d | `BlockingJobs` (`submitViews`, `submitCount`, `submitCandidates`, `submitDedupe`) | J05 + J06 | Phase 05: entity views, block-size count (with a combiner), candidate generation with fallback routing, candidate dedupe |
| HJ7 | `MatchDecisionJob` | (decision step) | Phase 05: applies the `er-v1` policy to every candidate; split by outcome |

HJ1 to HJ5 contain no blocking key, feature, score, threshold or decision. All of those live in the `entity-resolution` module
(`scholarchain.er`, see `modules/entity-resolution/ER-CONTRACTS.md`); HJ6 and HJ7 call its pure functions and nothing else. The
`hadoop -> entityResolution` build edge was approved by the human (Phase 05 preconditions).

Job graph (all intermediate tables are `sortKey TAB json` lines under `<root>/work/<job>/<table>/`):

```
dblp xml + dtd --HJ1--> hj1/{works, author-mentions, id-refs, person-aliases, duplicates, rejects}
crossref/openalex/orcid --HJ2--> hj2/{works, author-mentions, institutions, topics, id-refs, duplicates, rejects}
hj1/id-refs + hj2/id-refs --HJ3--> hj3/exact-identifier-evidence (+ rejects)
hj1/works + hj1/author-mentions --HJ4a--> hj4a/{teams, authorship, large-teams, oversize-teams, pair-stage, rejects}
hj4a/pair-stage --HJ4b--> hj4b/pair-candidates
all tables --HJ5--> <root>/curated/<table>/snapshot=<s>/run_id=<hex>/[year=<y>/]part-r-NNNNN.gz
curated/works + curated/author-mentions --HJ6a--> hj6a/entity-views            (JSON Lines: EntityView)
hj6a/entity-views --HJ6b (combiner)--> hj6b/{block-sizes, oversize-keys}         (sortKey TAB json)
hj6a/entity-views + hj6b/oversize-keys + hj3 CONFLICT evidence --HJ6c--> hj6c/{candidate-stage, oversize-blocks, rejects}
hj6c/candidate-stage --HJ6d--> hj6d/match-candidates                            (JSON Lines: MatchCandidate)
hj6d/match-candidates --HJ7--> hj7/match-decisions/outcome=<accepted|rejected|unresolved|conflict>/   (JSON Lines: MatchDecisionV2)
```
The ER tables of HJ6a, HJ6d and HJ7 are pure JSON Lines (no sort key), because the entity-resolution tools read them directly (`--views`,
`--candidates`). `block-sizes`, `oversize-keys`, `candidate-stage`, `oversize-blocks` and `rejects` of HJ6b/HJ6c use the `sortKey TAB json`
form of the other intermediate tables.

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
`-Dscholarchain.hadoop.large-team-threshold=50` or `-Dscholarchain.er.max-block-size=200`. The same jar runs on EMR as
`hadoop jar scholarchain-hadoop-assembly.jar scholarchain.hadoop.PipelineDriver <same arguments>`; no code change.
Exit codes: 0 success, 1 job or invariant failure, 2 bad arguments or refused run.

Tools: `python3 -I modules/hadoop/tools/compare_roots.py <rootA> <rootB>` (per-table sorted-line SHA-256 equality) and
`python3 -I modules/hadoop/tools/summarize_run.py <root> [--counters]` (read-only).

Add `--ignore-run-fields` to compare roots built by different code versions (drops `provenance` and `runId`; curated tables only). The compare
script also covers the ER tables under `work/hj6*` and `work/hj7`.

One `Tool` per job also exists (`DblpExtractTool`, `SourceNormalizeTool`, `ExactIdentifierTool`, `PublicationTeamPairTool`,
`GraphCandidateExportTool`, `BlockingCandidateTool` (HJ6a to HJ6d), `MatchDecisionTool`); they take the same arguments and read the earlier jobs' outputs from the root's `work/` directory.

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
- **ER jobs (Phase 05).** HJ6a sorts the work and mention rows of a work ID; HJ6c sorts members by view ID and stages pairs under
  the canonical `leftId|rightId` key; HJ6d folds `Blocking.preferred` over the sorted candidates (a total order: block key type, then key);
  HJ7 decides one candidate per reducer key. Decision timestamps come from `er.decision-epoch` (a configured instant), never the clock.
  `ErDeterminismItSuite` compares two runs (one with a 600-byte split size) line for line. The framework counters COMBINE_INPUT_RECORDS and
  COMBINE_OUTPUT_RECORDS depend on spills and the split layout: they are written to `counters.json` under `framework`, never to the manifest,
  and are excluded from every equality check.
- Scratch (`hadoop.tmp.dir`, staging) is set to `<root>/_tmp` on a local file system.

## 4. Configuration

`HadoopConfigLoader` (core). Precedence: environment `SCHOLARCHAIN_HADOOP_*`, then `-D scholarchain.hadoop.<key>`, then
`scholarchain-hadoop.v1.conf`. Keys: `large-team-threshold` (50, authors), `pair-expansion-hard-cap` (499500, pairs),
`reducers.{normalize,teams,pairs,identifiers}` (2 each), `output-codec` (gzip), `schema-version`. Two Hadoop-side keys outside
the core contract: `scholarchain.hadoop.export-reducers` (HJ5 reducers, default 1) and `scholarchain.hadoop.dblp.max-record-bytes`
(default 16 MiB; a longer record is cut and rejected as `oversized_record`; it must be an integer >= 1 or the run is refused).
**N2:** `export-reducers` is validated the same way (an integer >= 1, otherwise the run is refused with exit 2 before any job starts), and HJ5
reads the RECORDED value (`run.config("hadoop.export-reducers")`), so the manifest value always equals the value used. The resolved
values of all of these (`hadoop.dblp.max-record-bytes` and `hadoop.export-reducers` included) are put in the manifest `config`, hence in the run
ID, and in every record's `provenance.parameters` (with `job`).

**Entity-resolution settings (Phase 05).** `scholarchain.er.*` keys (`ErConfigLoader`: environment `SCHOLARCHAIN_ER_*`, then
`-D scholarchain.er.<key>`, then `scholarchain-er.v1.conf`) are resolved ONCE by the driver and recorded as `er.policy-version`,
`er.feature-set-version`, `er.decision-epoch`, `er.max-block-size` and `er.{researcher,work}.threshold.{accept,reject,llm-band-low,llm-band-high}`.
They are part of the manifest `config` and of the run ID, and they are added to the provenance parameters of HJ6 and HJ7 records only (the Phase 04
records are unchanged). Tasks rebuild the ER configuration from the recorded values; they never read the task environment. An invalid value
(for example `max-block-size` < 2, or thresholds out of order) refuses the run. The LLM settings are not used in MapReduce. Reducer counts of the new
jobs reuse existing keys: HJ6a `reducers.teams`, HJ6b `reducers.identifiers`, HJ6c, HJ6d and HJ7 `reducers.pairs`. Any setting that can change output must be added there; nothing else is read from
the Hadoop configuration by the tasks.
Compression: map output is always compressed (codec follows `output-codec`; `none` uses DefaultCodec). Output codec `gzip`
(portable default), `snappy` (documented for EMR; native Snappy may be missing on macOS), or `none`.

## 5. Provenance

Every record type with a `provenance` field gets a real one: `inputs` (with SHA-256 checksums), `codeVersion`, `parameters`, `runId`.
DBLP records: the XML and the DTD (checksums of the files; the XML record's byte offset is in `parameters.inputByteOffset`).
Enrichment records: the source's tree input (`<source>/works`, checksum = SHA-256 of the sorted `name:sha256` lines of its `*.json`
files) plus the single envelope file (name, `retrieved_at`, checksum), and `retrievedAt`. Evidence records: the inputs of every source
that took part. **N1 (bounded merge provenance):** a merged `works`, `institutions` or `topics` row keeps its tree input and its OWN envelope file,
and records the merge as the parameters `merged.rows`, `merged.files` (distinct envelope inputs merged away) and `merged.inputs-digest` (a
SHA-256 digest over those inputs, sorted), so the size stays bounded by the number of sources instead of growing with the number of merged rows
(rootC, Phase 04: up to 11 inputs on a topic; Phase 05: at most 2). ER records (HJ6, HJ7) carry the raw-input checksums of the run (`provAll`),
the code version, the run parameters including the `er.*` settings, and the run ID; a candidate also records `leftSource`, `rightSource` and
`featureSetVersion`. **`RejectRecord` has no provenance field by contract**; it links to the manifest through `runId` and names its
origin through `jobId`, `sourceRecordId` and `inputByteOffset`.

## 6. Job contracts

All jobs: text output (JSON Lines, `LazyOutputFormat`, tables through `MultipleOutputs`), codec as in section 4, partitioner
`KeyHashPartitioner` (hash of the key bytes, deterministic), no combiner in HJ1 to HJ5 (none is semantically safe: every reducer needs the full
group). HJ6b is the one job with a combiner (a sum). Counter group: enum `PipelineCounter` (group name `scholarchain.hadoop.PipelineCounter`) plus group `rows` (rows written per
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
  (default 2): dedupe by source record id, winner = latest `retrieved_at` **compared as an instant** (N4; `2026-01-01T09:00:00+02:00` is
  earlier than `2026-01-01T08:00:00Z`), ties by file checksum, then by the bundle JSON text; losers to `duplicates`.
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
  **compared as an instant** (N4; an absent or unparsable value sorts first), ties broken by the larger JSON text, so the result does not depend on
  value order. The winner keeps its fields; its `provenance.inputs` are its own inputs (tree input and own envelope) plus the run-level inputs (tree, DBLP
  XML, DTD) of the merged rows, sorted, and its `provenance.parameters` gain `merged.rows`, `merged.files` and `merged.inputs-digest` (N1, section 5),
  so the merge stays visible without unbounded growth. The legacy 3-argument `Dedupe.rows(table, sorted, ctx)` (the unbounded sorted union) remains
  only because `DedupeUnitSuite` pins it; HJ5 does not use it. Counters (group `PipelineCounter`, job HJ5): `WORK_MERGED` (works rows merged away) and `ENTITY_MERGED`
  (institutions and topics rows merged away). Row-count identity: `rows(works) = hj1 works + hj2 works - WORK_MERGED`. Other curated tables are
  already unique by their ID by construction (`author-mentions` and `authorship` by mentionId, `teams` / `large-teams` / `oversize-teams` by workId,
  `person-aliases` by aliasKey, `exact-identifier-evidence` by (type, value); `pair-candidates` by (pair, work)); `duplicates` and `rejects` are logs.
- Tables: works, author-mentions, authorship, teams, pair-candidates, exact-identifier-evidence, institutions, topics, person-aliases, rejects,
  plus duplicates, large-teams, oversize-teams. An empty table has no directory.

### HJ6 BlockingCandidateJob (spec J05 + J06)
Four MapReduce passes. All blocking keys, eligibility rules and features come from `scholarchain.er` (`EntityViews.build`, `Blocking.primary |
fallbacks | eligible | candidate | preferred`, `Features.compute`); the jobs add routing, counting and the cap. `max-block-size` is
`er.max-block-size` (default 500).
- **HJ6a** (`ViewSourceMapper`, `ViewReducer`): input `curated/works` and `curated/author-mentions` (read recursively), keyed by work ID; the
  reducer calls `EntityViews.build` and writes `entity-views` (one `EntityView` per work and per mention). A work row twice or an unreadable row
  is a reject-v1 row (counter SCHEMA_FAILURE). Counters: INPUT_RECORDS, ENTITY_VIEWS, ACCEPTED.
- **HJ6b** (`BlockCountMapper`, `BlockCountCombiner`, `BlockCountReducer`): emits (primary block key, 1) per view; the **combiner** sums partial
  counts; the reducer writes `block-sizes` (every key and its member count) and `oversize-keys` (keys with more than `max-block-size` members; the
  side file). A view without a primary key is counted (UNBLOCKED_VIEW) and is not blocked by name or title. Counters: INPUT_RECORDS, BLOCK_KEYS,
  OVERSIZE_KEY, UNBLOCKED_VIEW; framework counters COMBINE_INPUT_RECORDS / COMBINE_OUTPUT_RECORDS in `counters.json` (`framework`).
- **HJ6c** (`CandidateMapper`, `CandidateReducer`): each mapper loads `hj6b/oversize-keys` in `setup()` through the Hadoop FileSystem (a side file; works
  for `file://`, `s3://` and the distributed cache alike). A view goes to its primary key (`P|<key>`), or, when that key is in the side file, to every
  fallback key of the view (`F|<key>`). Independently, a view with a DOI, arXiv ID, OpenAlex ID or ORCID also goes to the exact-identifier key
  `X|x1|<type>|<normalized id>` (a primary-type key, so a pair that agrees on an identifier is a candidate even when its titles or names block
  apart; this goes beyond the single-JVM reference `Blocking.generate`, and when a pair is found under several keys `Blocking.preferred` orders candidates by block-key type, `primary` before `fallback`, then by the lexicographically smaller key. An x1 key is typed `primary`, so it beats any title or name fallback key; against a title or name primary key the winner is decided by key comparison alone, not by key kind).
  **A reducer group never holds more than `max-block-size` members and never produces a candidate from a larger one:** it counts all members, keeps at most
  `max-block-size`, and if the count is over the cap it writes one `oversize-blocks` row (`blockKey`, `blockKeyType`, `members`, `maxBlockSize`) and
  counts OVERSIZE_BLOCK. A block that is still oversize after its fallback is therefore skipped and listed; a view whose primary block is oversize and has no
  fallback key is dropped. Pairs: `Blocking.eligible` (exactly one side DBLP; DBLP-DBLP researcher pairs need alias evidence, which HJ6 does not
  supply, as in `Blocking.generate`), then `Blocking.candidate`. The feature `exact_id_conflict` is set when both records are linked by the same HJ3
  CONFLICT evidence row (`hj3/exact-identifier-evidence`, outcome conflict). Counters: INPUT_RECORDS, BLOCK_GROUPS, OVERSIZE_BLOCK, CANDIDATE_STAGED,
  CANDIDATE_CONFLICT_FLAGGED.
  **x1 blocks have no fallback.** HJ6b counts only title or name primary keys, never `x1|...` keys, so `oversize-keys` never contains an x1 key and no
  view is rerouted from one. An x1 block with more than `max-block-size` members is caught only by the HJ6c reducer cap: it is skipped and listed in
  `oversize-blocks`, never routed to a fallback. This is safe: no candidate is produced from the skipped block, so no pair can be created or
  mis-scored from it, and the listing keeps the loss visible (OVERSIZE_BLOCK). A pair in that block can still be a candidate through its title or name block.
  Known label limitation: the HJ6c reducer records an x1 block with `blockKeyType` `primary` (the core enum `BlockKeyType` has only `primary` and `fallback`).
- **HJ6d** (`PairLineMapper`, `CandidateDedupeReducer`): key `leftId|rightId`; a pair found under several keys keeps the `Blocking.preferred`
  candidate (primary before fallback, then the smaller key); output `match-candidates` (`MatchCandidate`). Counters: ACCEPTED (unique pairs),
  CANDIDATE_DUPLICATE; invariant ACCEPTED + CANDIDATE_DUPLICATE = CANDIDATE_STAGED.
- Rejects: a candidate that fails the `MatchCandidate` contract becomes a reject-v1 row in `hj6c/rejects`.
- Phase 04 does not persist venue or affiliation, so the venue and affiliation features are absent in real runs (nothing is faked).

### HJ7 MatchDecisionJob
Input `hj6d/match-candidates`; one reducer key per pair. For each candidate: `Policy.decide(kind, features, thresholds)`, then `Decisions.build`
(policy version `er-v1`, features plus `policy.score` and `policy.threshold.*`, the decision timestamp from `er.decision-epoch`), written to
`hj7/match-decisions/outcome=<outcome>/part-r-NNNNN[.gz]`. Exact-identifier evidence: HJ6 puts the HJ3 CONFLICT evidence in the feature
`exact_id_conflict`, and the identifier-agreement features come from the views; the policy turns them into path `exact_id` (ACCEPTED on an agreeing id,
CONFLICT on a disagreeing id or on HJ3 CONFLICT evidence). A candidate with `exact_id_conflict` = 1 that is not decided CONFLICT fails the task
(invariant; it cannot happen with `er-v1`). **No LLM is called**: an UNRESOLVED candidate inside the LLM band is counted (LLM_ELIGIBLE) and stays
UNRESOLVED; Part B adjudicates outside MapReduce. Counters: INPUT_RECORDS, DECISIONS, ACCEPTED (rows written), CONFLICT, LLM_ELIGIBLE, and the group
`decisions` with one counter per `<outcome>.<matchPath>` (in the manifest as `DECISIONS_<outcome>_<matchPath>`). The thresholds are the placeholders of
`scholarchain-er.v1.conf` unless overridden; applying a frozen policy file is not done here (the thresholds are passed as `scholarchain.er.*` settings).
Driver invariants (exit 1 on violation): HJ6d reads what HJ6c staged; unique + duplicates = staged; HJ7 decides exactly the unique candidates; the
decision rows equal the decisions counted.

## 7. Pure-function API (what unit tests call)

- `DblpEntities.fromDtd(bytes): DblpEntities`
- `DblpParser(entities, charset).parse(recordBytes): Either[ParseFailure, ParsedRecord]` and `DblpParser.parseRecord(bytes, charset, entities)`;
  `ParsedWork(DblpWork, warnings)`, `ParsedPersonRecord(DblpPerson, warnings)`; `ParseFailure(reason: RejectReason, message, sourceRecordId)`.
- `Normalizers.crossref | openalex | orcid (fileName, bytes, run: RunContext): Either[ParseFailure, Normalized]` (`Normalized.rows` are `Row(table, sortKey, json)`).
- `PairExpander.pairCount(n): Long`; `PairExpander.expand(workId, year, teamSize, prov): Iterator[Either[ValidationError, MentionPairCandidate]]`.
- `RunContext(runId, codeVersion, snapshot, attempt, config, inputs, dblpCharset)` with `config` keys `hadoop.large-team-threshold`, `hadoop.pair-expansion-hard-cap`, ...
  and `inputs` keys `dblp`, `dtd`, `crossref`, `openalex`, `orcid`.
- `PipelineArgs.parse`, `RunSetup.prepare`, `PipelineDriver.invariantError(outcomes)`.
- `Dedupe.rows(table, sorted, ctx, known)`, `Dedupe.instantKey(retrievedAt)`; `ErSettings.resolve`, `RunContext.erConfig | erParams | allConfig`;
  `BlockingJobs.submitViews | submitCount | submitCandidates | submitDedupe`, `MatchDecisionJob.submit`, `EntityResolutionJobs.runAll | invariantError`.

## 8. Known gaps (need a core contract, not worked around)

### 8.1 Residual reader edge cases (N3; not defects fixed, documented)
`DblpRecordReader` is bounded in memory (`dblp.max-record-bytes`) and always makes progress, but three malformed-input shapes can cost records:
- **Unterminated CDATA section or comment** (`<![CDATA[` with no `]]>`, `<!--` with no `-->`). Inside such a section neither a start tag nor an end tag
  counts (by design), so the record never ends: the reader keeps consuming to the end of the file, the record is cut at `max-record-bytes` and rejected as
  `oversized_record` (or `malformed_record` at end of file), and **every later record of that file is lost** (one reject row stands for all of them). A
  later `]]>` or `-->` anywhere in the file ends the section. Real DBLP records contain neither construct.
- **Unterminated attribute quote in a start tag** (`<article key="x>`). The reader honors quotes while looking for the closing `>`, so the tag continues to the
  next matching quote character, which can be inside a following record; the swallowed records are lost, bounded by `max-record-bytes` (cut, rejected
  `oversized_record`). The loss ends at the next quote/`>` pair.
- **A split that begins inside a CDATA section or comment** has no way to know it; text inside it that looks like a record start tag can be taken as a record
  start. Such a record would normally be rejected as `malformed_record` or deduplicated by DBLP key (not exercised by a test); a rejection is visible in
  REJECTED, but the case is not prevented.
In all three cases the loss is visible (a reject row and the REJECTED counter) and deterministic for a fixed input; none can corrupt another record or
exhaust memory.

### 8.2 Contract gaps

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
