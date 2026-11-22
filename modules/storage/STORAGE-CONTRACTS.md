# Storage contracts (Phase 07)

Owner: storage-engineer. Module `scholarchain-storage` (`modules/storage`), package `scholarchain.storage`. Depends on `core` only. It reads the Phase 06
(`modules/spark`) output as FILES plus load manifests; it never imports `modules/spark` code. Spark SQL 4.1.1 is `Provided`, used only by `ParquetTables` /
`LocalLoad` (a `Test`-classpath main) to read Parquet. The AWS SDK for Java v2 (2.59.7) is used with SYNC clients and the URL-connection HTTP client only.

No AWS call is made in this phase. Everything is built and tested locally against fakes. Live suites exist (section 9) and are reported NOT RUN.

Access date of every cited documentation page: **2026-11-21**.

## 1. Ports, adapters and fakes

| Port (core `StoragePorts.scala`) | AWS adapter | Fake (no AWS classes) |
|---|---|---|
| `ObjectStore` | `S3ObjectStore` | `FileObjectStore` (filesystem) |
| `GraphStore` | `TemplateGraphStore` + `NeptuneAws.executor` / `NeptuneAws.loader` | `TemplateGraphStore` over `RecordingCypherExecutor` + `FakeBulkLoader` |
| `KeyValueStore` | `DynamoKeyValueStore` | `InMemoryKeyValueStore` |
| `VectorStore` | `S3VectorsStore` | `InMemoryVectorStore` |
| `WorkQueue` | `SqsWorkQueue` | `InMemoryWorkQueue` |

Every operation returns `Either[StorageError, A]`. `StorageError` messages are built from the exception class, HTTP status and AWS error code only (`AwsErrors`), never
from `getMessage`, so no resource name, ARN, endpoint or request ID is echoed.

### AWS-off-by-default

* `AwsClients(credentials: AwsCredentialsProvider, region: Region, neptuneEndpoint: Option[URI])` is the ONLY place a client is built. Each client is a `lazy val`
  (built on first use of its accessor). Nothing in the module builds a client in an object initializer or in a default code path.
* The credentials provider is injected. No code in `src/main` calls `DefaultCredentialsProvider`, reads an environment variable for AWS, a profile file or instance metadata.
  (`LocalLoad` reads none either.) The provider is invoked by the SDK only when a request is signed.
* The Neptune endpoint is never defaulted (`graphStore` returns `Left(InvalidRequest)` without one). Table, bucket, index and queue names come from `StorageModuleConfig`
  (`scholarchain-storage.v1.conf`, env `SCHOLARCHAIN_STORAGE_<KEY>`) or from the caller.
* Fakes need no AWS class at all. The AWS adapters take a client as a constructor argument.
* **Hermetic builders (`AwsClients.hermetic`):** every client builder (S3, DynamoDB, S3 Vectors, SQS, Neptune data) is given (a) the injected credentials provider, (b) an explicit region, and (c) a client override
  configuration with an explicitly supplied EMPTY profile file (`AwsClients.emptyProfileFile`, built from an empty in-memory stream) and the fixed profile name `scholarchain-hermetic`. The SDK therefore never reads the shared
  `~/.aws/config` or `~/.aws/credentials` for client defaults (region, retry mode, endpoint settings in a profile). Not suppressed: environment variables that the SDK reads for client defaults (for example `AWS_ENDPOINT_URL*`);
  they are not files, and a deployment that must forbid them has to scrub its environment. Building `AwsClients.hermetic` makes no AWS call and reads nothing from disk, so a test can check the configuration without building a client.

## 2. Ordinary S3 (`ObjectStores.scala`)

* Prefixes `raw/ curated/ graph/ rag/ reports/ logs/` come from `StorageModuleConfig.s3` (core validates them). The bucket is part of the `ObjectLocation`, supplied by the caller.
* **Versioned keys:** `ObjectStores.versionedKey(prefix, logicalName, runId, fileName)` = `<prefix><logicalName>/run=<64-hex run id>/<fileName>`. The core run ID is part of
  the key, so two runs never overwrite one another and a key names the run that produced it. Keys are valid when non-empty, at most 1024 UTF-8 bytes, no leading `/`, no `\`,
  no control character, no empty, `.` or `..` segment (so a key cannot leave the fake's root), and **no path segment longer than 240 UTF-8 bytes** (`ObjectStores.MaxSegmentBytes`). The last rule is part of the shared key contract: file systems limit a
  name to 255 bytes and the fake stores a sidecar `<segment>.json`; S3 itself would accept longer segments, but both adapters reject such a key up front as the non-retryable `InvalidRequest` (never an I/O failure). The fake also rejects, as `InvalidRequest`, a key that
  is a path prefix of another stored key or extends a stored key (S3 allows `a` and `a/b`; a file system cannot).
* **Content hashes:** every put computes the SHA-256 of the bytes; `expectedHash` mismatch gives `IntegrityMismatch` and stores nothing. `S3ObjectStore` sends the base64 SHA-256 as
  the S3 checksum (S3 rejects a corrupted upload) and stores the hex hash as user metadata `sha256`; `get` re-hashes the bytes and checks them against it. `FileObjectStore` recomputes the
  hash from the bytes on `head` and `get` (it never trusts its sidecar). `list` on S3 returns `contentHash = ""` (the hash of listed objects is unknown until read).
* **Idempotency:** `ObjectStores.putIdempotent` = `putIfAbsent` (S3: `If-None-Match: *`; fake: `CREATE_NEW`); when the object exists with the same SHA-256 it is a no-op, with a different hash it is
  `IntegrityMismatch` (a conflict, never an overwrite). The store has no delete operation.
* **`FileObjectStore` failure semantics (M1):** a write goes to temporary files (data and sidecar) first; `putIfAbsent` claims the data path atomically with a hard link (`AlreadyExists` when taken); the sidecar is then moved into place. If any step fails, the data file this call created is
  removed, a replaced sidecar is restored and the temporary files are removed: no partial data file or sidecar remains (an overwritten data file by `put` cannot be restored; `put` replaces by contract). A missing file is `NotFound`; every other local I/O failure is the non-retryable
  `Failed` (never the retryable `Unavailable`: nothing local is transient).
* **Run manifests:** `ObjectRunManifest.publish` writes the artifacts under their versioned keys and then `manifest.json` (sorted keys, no timestamp): `runId`, `stage`, `codeVersion`,
  `parameters`, `inputs` (id, version, SHA-256) and the `artifacts` (key, SHA-256, size).

## 3. Neptune

### 3.1 openCypher bulk-load CSV (`NeptuneCsv.scala`)

Syntax follows https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-format-opencypher.html (accessed 2026-11-21):

* UTF-8, RFC 4180, comma-separated, one header row; nodes and relationships in separate files; all files of one load under one S3 prefix.
* Node file: `:ID` (required), `:LABEL`, then `name:Type` property columns. Relationship file: `:ID`, `:START_ID`, `:END_ID`, `:TYPE`, then property columns.
  `:ID` of relationships is required because `userProvidedEdgeIds` is true by default (`load-api-reference-load.html`).
* Types written: `String`, `Int`, `Long`, `Double`, `Bool`. A comma, double quote or line break in a field puts it in double quotes with embedded quotes doubled (`CsvFormat.escape`; a field with
  a leading or trailing space is also quoted). Line ends are LF. A property name must match `[A-Za-z_][A-Za-z0-9_]*` (the page forbids space, comma, CR, LF in headers).
* An empty field means "no property" in Neptune: null cells AND empty strings are written empty (an empty string is therefore not stored). Control characters other than tab, LF and CR make the load fail.
* **Arrays are NOT written as `Type[]` columns.** The openCypher data-model page (https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-opencypher-data-model.html) says array values should not be inserted into an
  openCypher graph: the bulk loader would store a set of multi-valued properties instead of one list. The openCypher CSV page documents no array syntax. Arrays and maps (`orcids`, `representativeWorkIds`,
  `evidenceMentionIds`) are therefore one `String` property holding compact JSON (`["a","b"]`), which keeps order and duplicates. (The work order named `name:String[]` with `;`; this is a deliberate, documented deviation.)
* Undirected edges (`COAUTHORED_WITH`, `COMMUNITY_LINK`) are stored once, from the smaller to the larger ID (`researcherA < researcherB` in Phase 06). Queries match them without a direction.

### 3.2 Mapping (driven by `load-manifests/neptune.json`; labels, ID columns, property columns and key columns are read from it)

| Vertex label | Table | ID (equals the Spark ID byte for byte) | Rows (L1) |
|---|---|---|---|
| Researcher | researchers | `researcherId` (core researcher ID) | 3,893 |
| ExternalIdentity | external-identities | `externalId` = `researcher:orcid:<orcid>` | 1,423 |
| Work | works | `workId` | 1,000 |
| Topic / Institution / Venue | topics / institutions / venues | `topicId` / `institutionId` / `venueId` | 206 / 250 / 155 |
| Community / Component (windowed) | communities / components | `communityId` / `componentId` (both embed the window: `community:<window>:<smallest member>`) | 1,755 / 1,735 |

| Edge type | Table | Edge ID | From -> To | Rows (L1) |
|---|---|---|---|---|
| COAUTHORED_WITH (windowed) | coauthor-edges | the Spark `edgeId` (`edge:coauthor:<hash>`, which includes the window label) | researcherA -> researcherB | 15,107 |
| AUTHORED | authored | derived | researcherId -> workId | 3,928 |
| RESOLVES_TO | resolves-to | derived | sourceId -> targetId | 1,423 |
| HAS_TOPIC / PUBLISHED_IN | has-topic / published-in | derived | workId -> topicId / venueId | 297 / 1,000 |
| MEMBER_OF / IN_COMPONENT (windowed) | community-membership / component-membership | derived | researcherId -> communityId / componentId | 6,937 / 6,937 |
| COMMUNITY_LINK (windowed) | community-cross-edges | derived | communityA -> communityB | 20 |

* **Derived edge ID:** `nedge:` + `Hashing.digest("scholarchain.storage.neptune-edge.v1", edgeType :: keyColumnValues)` (core length-prefixed SHA-256), key columns in manifest order (for MEMBER_OF: `window`,
  `researcherId`). Deterministic and collision-checked (a duplicate edge ID fails the load). Windowed edges and vertices cannot collide across windows: COAUTHORED_WITH IDs and Community / Component IDs embed the window, and the
  derived IDs of windowed memberships include the `window` key column.
* **COAUTHORED_WITH properties:** `window`, `jointPublicationCount:Int`, `firstCollaborationYear:Int`, `lastCollaborationYear:Int`, `supportingWorksDigest`, `supportingWorksTruncated:Bool`, `representativeWorkIds`
  (JSON string), `evidenceTable` (`coauthor-edge-works`), `evidenceKey` (= edgeId), `evidencePartition` (= window).
* **Not produced by Phase 06, therefore absent (nothing fabricated):** `AFFILIATED_WITH`, `DERIVED_FROM`, `SUPPORTED_BY` edges. `Institution` vertices are loaded; no edge points to them. Recorded in `load-manifest.json` (`absentEdgeTypes`).
* **Provenance:** the row-level `provenance` JSON string of `coauthor-edges`, `researcher-metrics` and `resolves-to` is parsed with `Wire.read[Provenance]` and its `runId` must equal the manifest run. Every `coauthor-edges` row is rebuilt as a core
  `CoauthorEdgeV2` and every `researcher-metrics` row as a `ResearcherMetricsV2` and validated by its core contract (`RowContracts`). * **Lineage columns (M6):** with a `Lineage`, every vertex and edge row ends with `loadRunId:String` (the core run ID of the storage load), `sparkRunId:String` (the Spark run of the manifest) and `provenanceDigest:String`: the SHA-256 of the row-level `provenance` JSON string where the table has that column
  (`coauthor-edges`, `researcher-metrics`, `resolves-to`), otherwise the SHA-256 of the table's `_provenance.json` bytes. `load-manifest.json` maps each digest to the full provenance document (`provenanceDocuments`) and lists the table-level digests per table (`neptuneLineage`). A property
  column named like a lineage column fails the load. The invariants (IDs, endpoints, duplicates) are unchanged. The full provenance text is not copied onto rows.

### 3.3 Invariants (`NeptuneInvariants`, checked on the written CSV bytes, parsed again independently of the writer; any violation fails the load)

1. every `:START_ID` / `:END_ID` is a vertex `:ID` in the written vertex files, and has the expected label for the edge type (0 dangling endpoints, 0 label mismatches);
2. IDs equal the Spark IDs byte for byte: vertex `:ID` = the table ID column, edge endpoints = the table from / to columns, edge `:ID` = the Spark or derived ID of the same row;
3. vertex IDs and edge IDs are unique across all files; 4. Researcher and ExternalIdentity IDs pass `Ids.checkResearcherId`; 5. headers and `:LABEL` / `:TYPE` columns are as expected; 6. row counts equal the manifest.
`RowContracts.traceEvidence` additionally checks that for every COAUTHORED_WITH row the `coauthor-edge-works` rows number `jointPublicationCount`, hash (core `CoauthorEdgeV2.expectedDigest`) to `supportingWorksDigest`,
contain `representativeWorkIds`, and that every evidence work is a `Work` vertex.

### 3.4 Bulk-load request (`NeptuneBulkLoad.buildRequest`)

Fields follow https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html: `source` (S3 URI), `format` (`opencypher`), `iamRoleArn`, `s3BucketRegion`, `mode`, `failOnError`, `parallelism`,
`updateSingleCardinalityProperties`, `queueRequest`. `source` must be an `s3://<bucket>/<prefix>/` URI under the configured `storage.neptune.bulk-load.source-s3-uri`; `format` must equal the configured one AND be `opencypher` (Phase 08 carry-over L4: `NeptuneBulkLoad.checkConfig`/`buildRequest` and `AwsClients.graphStore` reject `csv`, which is the Gremlin CSV format and would misread the openCypher files, and any other value, with `InvalidRequest` "Neptune bulk-load format must be 'opencypher' ..."; core's config loader still accepts `csv`, a core matter, so the check lives here); the role comes from
configuration only. `mode = AUTO` (a load from the same source resumes a previous job), `failOnError = true`, `updateSingleCardinalityProperties = false` (default: a reload of identical data is a no-op; a changed value of an existing single-cardinality property
is an error, not a silent overwrite), `queueRequest = false`, `parallelism = MEDIUM`.

### 3.5 Read-only query templates (`GraphTemplateCatalog`)

Allowlist = the core `GraphTemplates` names: `neighbors`, `coauthor-edge`, `shortest-path`, `component`, `community`, `researcher-metrics` (the last is degree-based, computed from the graph; the authoritative
`ResearcherMetricsV2` items are in DynamoDB). Rules:

* The Cypher text of a template is a constant. Request values travel only in the openCypher `parameters` JSON as `$name`; no Cypher is built from request text. The only holes in a template are `{{DEPTH}}` and `{{TIMEOUT}}`, replaced by
  integers computed from validated bounds (a variable-length bound and a query hint value cannot be parameters).
* Parameters: `researcherId` / `from` / `to` / `researcherA` / `researcherB` must pass `Ids.checkResearcherId`; `window` must match `^[0-9]{4}-[0-9]{4}$`; optional `maxHops` is an integer in `1..bounds.maxDepth`. Unknown template names, unknown or missing
  parameters and mistyped values are `InvalidRequest` BEFORE any call.
* Bounds on every call: `maxDepth` in `1..ceiling.maxDepth`, `maxResults` in `1..ceiling.maxResults`, `timeoutMillis` in `1..ceiling.timeoutMillis`; the ceiling is the configured bound (default 6 / 100 / 5000 ms) and itself must be within the hard
  ceilings `HardMaxDepth = 10`, `HardMaxResults = 1000`, `HardMaxTimeoutMillis = 60000` (ScholarChain policy). Result limit: `LIMIT $limit` with `maxResults + 1` (to report `truncated`). Timeout: the query hint `USING QUERY:TIMEOUTMILLISECONDS <n>`
  (https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-query-hints.html: the Query-scope hint `TIMEOUTMILLISECONDS` is supported; engine 1.3.2.0 or later) plus a client-side call timeout of `n + 1000` ms. Depth: `shortest-path` uses
  `shortestPath((a)-[:COAUTHORED_WITH*1..{{DEPTH}}]-(b))`; the other templates are fixed one-hop patterns.
* **NOT RUN against Neptune.** The texts were never executed (no AWS). Whether Neptune accepts the `WHERE all(...)` predicate after `shortestPath`, and `LIMIT $limit`, is first tested by `NeptuneReadAwsItSuite` in Phase 14 to 15.

## 4. DynamoDB

Physical layout for all tables (`DynamoKeys`, shared by the adapter and the fake): string partition key attribute `pk`, string sort key attribute `sk` (only where stated), TTL attribute `expiresAt` (number, epoch seconds, only on disposable items).
`pk`, `sk` and `expiresAt` are reserved attribute names. Item = `payload` (the validated core wire JSON of the record) + `payloadHash` (SHA-256 of `payload`) [+ `status` for lifecycle items].
Terraform (infra-engineer) must create the tables with this key schema; the capacity mode is infra's decision.

Limits (https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html, 2026-11-21): item size 400 KB (409,600 bytes), partition key value at most 2048 bytes, sort key value at most 1024 bytes, `BatchWriteItem` up to 25 puts.
TTL deletion is asynchronous ("typically within a few days", https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html); repositories therefore treat an item whose `expiresAt` is in the past as absent.

| Table (config key) | pk | sk | Access patterns served | Write rule | TTL |
|---|---|---|---|---|---|
| RunLedger (`run-ledger-table`) | `run#<runId>` / `stage#<stage>` | `META` / `<startedAt>#<runId>` | A1 get a run by ID (GetItem). A2 newest runs of a stage (Query `stage#<stage>`, descending) | run item: `NotExists`; `started` -> `succeeded` or `failed` via `status = started`; the stage index item is immutable | none (audit trail) |
| ArtifactRegistry (`artifact-registry-table`) | `<runId>` | `<artifactType>#<sha256(artifactUri)>` | A1 artifacts of a run (Query pk). A2 of one type (`begins_with(sk, "<type>#")`). A3 one artifact (GetItem) | immutable: `NotExists` | none |
| EntityLinkCache (`entity-link-cache-table`) | `<mentionId>` | `<policyVersion>#<cacheKey>` | A1 cached decision of a mention under one policy (`begins_with`). A2 all policy versions of a mention | immutable: `NotExists` | only when the entry has `expiresAtEpochSeconds` |
| QuerySession (`query-session-table`) | `<sessionId>` | none | A1 get a session (GetItem). Lifecycle `received` -> `answered` / `rejected` / `failed` | `NotExists`; transition via `status = received` | always (disposable, at most 30 days by the record contract) |
| EvidenceBundle (`evidence-bundle-table`) | `<queryId>` | `<bundleHash>` (SHA-256 of the bundle JSON) | A1 all bundles of a query (Query pk). A2 one bundle (GetItem) | content-addressed, immutable: `NotExists` | none (bundles back audited answers). A bundle above 300,000 bytes is stored in S3 and the item holds `payloadUri` + hash |

The artifact URI is not the sort key because an S3 URI can be up to 1100 characters, above the 1024-byte sort key limit.

**Idempotency (`ItemWrites.write`):** put with `NotExists`. Written -> `Written`. Condition failed with an existing item of the same `payloadHash` -> `Unchanged` (a reload is a no-op). Existing item with a different hash -> `Conflict` (reported, never overwritten). Only a
lifecycle item whose stored `status` is in the allowed set is replaced, and then with the condition `status = <the status just seen>`, so two writers cannot both make the transition.
**Expired items (M2):** every TTL item (EntityLinkCache with an expiry, QuerySession) also carries the string attribute `expiry` = the decimal TTL. When the `NotExists` put fails and the stored item's `expiresAt` is `<= now` (readers already treat it as absent; DynamoDB's TTL sweep is asynchronous), the item is replaced
with the condition `expiry = <the stored expiry text>` (`ConditionExpression #a = :v` on the expiry attribute; the fake implements the same `AttributeEquals`). That applies whatever the new payload is (a same-payload rewrite refreshes the TTL). A concurrent writer or a refreshed item (different expiry) makes the condition fail and the result is `Conflict`.
Never an unconditional overwrite. An expired item without an `expiry` attribute stays a `Conflict`. `InMemoryKeyValueStore` implements `NotExists` / `AttributeEquals` / `Always`, key-schema checks,
the size limits and UTF-8 byte ordering of sort keys identically to the adapter. It never deletes (TTL included).

**ResearcherMetricCache: omitted as a separate table.** Its only access pattern (metrics of one researcher in one window) is served by `AnalyticsRepository.researcherMetrics(researcherId, window)` (one `GetItem` on the configured `researcher-metrics` analytics table: pk researcher ID, sk window) and
`researcherMetricsAllWindows(researcherId, limit)` (one `Query` on the partition), see 4.1.

### 4.1 Analytics item files (from `load-manifests/dynamodb.json`)

`LocalLoad` writes one JSONL file per manifest table (`dynamodb/items/<table>.jsonl`): `researcher-metrics` (pk `researcherId`, sk `window`; metrics of a researcher over the windows), `coauthor-edge-works` (pk `edgeId`, sk `workId`; the evidence behind
an edge pointer), `bridges` (pk `researcherId`, sk `window|measure`), `coreness`, `temporal-changes`, `paths` (pk `startId`, sk `window|endId`), `path-estimates`, `window-summary` (pk `window`), `identity-clusters` (pk `researcherId`), `mention-researcher` (pk `mentionId`).
A line is `{"attributes":{...},"payloadHash":"<sha256 of the attributes JSON>","pk":"..","sk":"..","table":".."}` with sorted keys. Several sort columns are joined with `|`; inside a component `%` becomes `%25` and `|` becomes `%7C`. Row-level `provenance`
is replaced by `provenanceDigest` + `provenanceRunId` (texts by digest in `load-manifest.json`). JSON numbers do not keep the int / double distinction (`3` for `3.0`); the loader that reads these files maps them by column type.
**Physical names (M8):** core `StorageModuleConfig.tables.analytics` (config keys `storage.dynamodb.analytics.<logical-name>-table`, ten logical names in `StorageConfigKeys.AnalyticsLogicalNames`) maps the logical name to the physical DynamoDB table. The Terraform tables are infra-engineer's; each has key schema `pk` (S) and, except for
`path-estimates`, `window-summary`, `identity-clusters` and `mention-researcher`, `sk` (S) (`AnalyticsRepository.HasSortKey`, from the manifest).

**`AnalyticsRepository` (writes the ten tables through `KeyValueStore`):** an item is `pk`, `sk` (components encoded as above), `payload` = the canonical compact JSON text of the typed `attributes` object stored as ONE string (the port holds strings only, so ints, doubles, nulls, arrays and objects are kept whole and read back as the same JSON; no per-column mapping is needed and nothing is
lost) and `payloadHash` = its SHA-256 (the `payloadHash` of the JSONL line). Writes use `ItemWrites.write`: new key -> `Written`; same key and hash -> `Unchanged`; same key, different hash -> `Conflict`; never an overwrite. Reads verify the hash. `putAll` counts the outcomes (and describes the first five conflicts) without stopping.
The 400 KB item limit is checked by the `AnalyticsItems` builder and the store. `LocalLoad` loads every item file into an `InMemoryKeyValueStore` twice and reports `written` / `unchanged` / `conflicts` per pass (the second pass must be all `Unchanged`); the load fails otherwise.

## 5. S3 Vectors

Five indexes (`VectorIndexes`): `researcher-profile-v1`, `collaboration-community-v1`, `paper-abstract-v1`, `collaboration-path-v1`, `topic-institution-summary-v1`; the bucket name is `storage.vectors.bucket-name`. Index dimensions are not in the core
configuration (the embedding model of Phase 08 decides): `InMemoryVectorStore` and `S3VectorsStore` take `indexName -> dimension` from the caller.

### 5.1 Service limits (`S3VectorsLimits`; every row has its source; none is assumed)

| Limit | Value | Source URL | Accessed |
|---|---|---|---|
| Vectors per PutVectors call | 500 | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html (also `API_S3VectorBuckets_PutVectors.html`: array members 1 to 500) | 2026-11-21 |
| Request payload size | 20 MiB | s3-vectors-limitations.html | 2026-11-21 |
| Vectors per GetVectors call | 100 | s3-vectors-limitations.html | 2026-11-21 |
| Vectors per DeleteVectors call | 500 (not used: no deletes) | s3-vectors-limitations.html | 2026-11-21 |
| Dimension per vector | 1 to 4,096 | s3-vectors-limitations.html (also `API_S3VectorBuckets_CreateIndex.html`) | 2026-11-21 |
| Top-K per QueryVectors request | up to 10,000 | s3-vectors-limitations.html | 2026-11-21 |
| Results per page in a QueryVectors response | up to 100 | s3-vectors-limitations.html | 2026-11-21 |
| Vector key length | 1 to 1,024 (core policy is stricter: 512) | https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_PutInputVector.html | 2026-11-21 |
| Total metadata keys per vector | up to 50 | s3-vectors-limitations.html | 2026-11-21 |
| Total metadata per vector | up to 40 KB on CLASSIC indexes, 100 KB on ENHANCED | s3-vectors-limitations.html | 2026-11-21 |
| Filterable metadata per vector | up to 2 KB on CLASSIC indexes, 40 KB on ENHANCED | s3-vectors-limitations.html | 2026-11-21 |
| Filterable metadata string value | up to 2 KB per value | s3-vectors-limitations.html | 2026-11-21 |
| Non-filterable metadata keys per index | up to 10 (not used) | s3-vectors-limitations.html, `API_S3VectorBuckets_MetadataConfiguration.html` | 2026-11-21 |
| PutVectors + DeleteVectors requests per second per index | up to 1,000 | s3-vectors-limitations.html | 2026-11-21 |
| Combined vectors inserted and deleted per second per vector index | Up to 2,500 (verbatim: "Combined vectors inserted and deleted per second per vector index: Up to 2,500") | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html | 2026-11-21 (verified verbatim by the orchestrator) |
| Maximum throughput for PutVectors and DeleteVectors | Up to 140 MBps (verbatim: "Maximum throughput for PutVectors and DeleteVectors: Up to 140 MBps") | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html | 2026-11-21 (verified verbatim by the orchestrator) |
| Index mode: ENHANCED for buckets created on or after 2026-09-30, CLASSIC before | n/a | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-metadata-filtering.html | 2026-11-21 |
| Metadata filter operators `$eq`, `$in`, `$and` | n/a | s3-vectors-metadata-filtering.html | 2026-11-21 |
| Cosine: zero vectors and NaN / Infinity are not allowed; data is float32 | n/a | `API_S3VectorBuckets_PutInputVector.html`, `API_S3VectorBuckets_PutVectors.html` | 2026-11-21 |

The same page also confirms "Top-K results per QueryVectors request: Up to 10,000" and "Results per page in a QueryVectors response: Up to 100". The 2,500 vectors per second and 140 MBps limits are per index and not enforced by the adapter (it has no rate limiter): a caller that loads
faster than that is throttled and retried by `VectorLoader`; a Phase 08 load must pace its batches (500 vectors per call, so at most 5 calls per second per index at the vector limit).

NOT VERIFIED: the exact byte accounting of "filterable metadata size" (the adapter measures the compact JSON of the metadata object, UTF-8, which is an approximation), the per-request throttling behavior, and the error code returned for a rejected vector inside a PutVectors call.
The adapter enforces the smaller CLASSIC sizes so it is correct for both index modes.

### 5.2 Metadata policy and validation (`VectorChecks`)

Stored metadata = the record's compact `metadata` (at most 16 entries, names at most 64 and values at most 128 characters: core policy) plus the system keys `textHash`, `textS3Uri`, `embeddingModelId`, `embeddingModelVersion`, `runId`
(`VectorMetadata`). A record's own metadata must not use the system names. Researcher, window, source, topic, institution, community and document type are record metadata keys chosen by the producer. **Long text never goes in metadata**: the text lives in S3
(`textS3Uri`), and `textHash` is its SHA-256. A vector is `Rejected` when: the record names another index (`InvalidRecord`), the embedding length differs from the record or index dimension (`DimensionMismatch`), the record fails its core contract, the key exceeds 1,024 bytes, the metadata uses
a reserved name, holds more than 50 keys, exceeds 2,048 bytes of JSON, or the embedding holds NaN / Infinity / is the zero vector (`InvalidRecord`).

### 5.3 Collisions, batching, retry, rejections

* **Key collision and idempotency (M3, `VectorChecks.againstStored`):** the existing vector is compared in full: stored metadata (text hash, model ID and version, `textS3Uri`, `runId` and the record metadata) and the embedding against the new entry. Identical -> idempotent (`Accepted`, nothing written). **Phase 08 (carry-over N2/c): `runId` is provenance, not identity.** `VectorMetadata.withoutRun` removes it from both sides, so the same key, text hash, model ID and version, `textS3Uri`, metadata and embedding reloaded from a NEW run is an idempotent no-op, reported `Accepted` without a write (the stored vector keeps its first run ID). A different text hash under the same key is still `Rejected(KeyCollision)`; any other difference is still `Rejected(EmbeddingMismatch)`.
  A different `textHash` -> `Rejected(KeyCollision)`. The same `textHash` with any other difference (a corrected embedding, a new model ID or version, other metadata) -> `Rejected(EmbeddingMismatch)`. **Decision: reject, not replace-under-condition**, because S3 Vectors has no conditional put and the loader never overwrites; replacing a vector is a human-approved
  re-embedding procedure (a new vector key or index version), not a silent loader action. Both rejections are recorded in the `RejectionSink` (key, reason, fixed text; no embedding and no metadata value). The same rules apply to a duplicate key inside one batch. Embeddings are compared exactly (`Vector[Float]` equality): if the service returned a re-encoded float, an identical reload would
  be reported `EmbeddingMismatch` (documented risk, to be observed in the Phase 14 live suite).
  `S3VectorsStore` reads the existing metadata AND data of the batch keys (GetVectors with `returnData` and `returnMetadata`, at most 100 per call) before PutVectors. S3 Vectors has no conditional put: two concurrent writers of the same NEW key can both pass the check (the run's loader is the only writer; documented risk).
* **Batching (`VectorChecks.batches`):** at most 500 vectors and an estimated 20 MiB per PutVectors call (each float counted as 16 bytes of JSON, an upper bound).
* **Retry (`Retry`, `VectorLoader`):** a retryable error (`Throttled`, `Unavailable`) is retried with delay `min(maxDelay, base * 2^(attempt-1))` (no jitter), at most `storage.retry.max-attempts` attempts (core caps: 10 attempts, 60 s). The wait goes through an injectable `Sleeper`. A batch that still fails marks
  each of its vectors `Rejected(ServiceRejected)`, records them, and the loader continues with the next batch. `Left` is returned only for an unknown index or invalid arguments.
* **Query pagination (M4, `VectorChecks.collectPages`):** `topK` is the total wanted (1 to 10,000). `S3VectorsStore.query` sends `topK` with each call and follows `nextToken` (QueryVectors returns at most 100 results per page) until `topK` hits are collected or there is no next token; it is bounded to `ceil(topK / 100) + 1` calls and stops on a repeated token. The hits are sorted by descending
  score and cut to `topK`. `InMemoryVectorStore` honors the same contract: `queryPage` returns at most 100 hits and an offset token, and `query` collects pages with the same function, so `topK = 500` returns up to 500 hits in both.
* `InMemoryVectorStore.query` is exact cosine top-k (ties broken by key), with `Equals` / `In` / `And` metadata filters, the same validation as the adapter, and the same `1..10,000` top-k bound.

## 6. Optional OpenSearch

**NOT implemented** (out of scope of Phase 07; `storage.openSearchEndpoint` stays unused).

## 7. WorkQueue

`InMemoryWorkQueue` (at-least-once, visibility timeout on an injectable clock, receive counts, deduplication ID, delete by the latest receipt handle) and `SqsWorkQueue` (queue URLs come from the caller, no URL is built; FIFO queues get a group and the deduplication ID). No live call.
Limits (https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html and `.../APIReference/API_ReceiveMessage.html`, 2026-11-21): message body 1 byte to 1,048,576 bytes; `MaxNumberOfMessages` 1 to 10. NOT VERIFIED: the upper bounds
of `WaitTimeSeconds` and `VisibilityTimeout` (only `>= 0` is checked locally). Note: `WorkQueue.delete` acknowledges (removes) one processed work message; it is the port's ack operation, not data destruction.

## 8. Local run (no AWS)

`LocalLoad` reads a Phase 06 run directory and writes, under the given NEW directory (it refuses an existing one): `neptune/vertices/<Label>.csv`, `neptune/edges/<TYPE>.csv`, `dynamodb/items/<table>.jsonl`, `load-manifest.json`
(inputs with versions and checksums, Spark run ID and code version, loader code version, parameters, the derived `loadRunId` = core `Ids.runId("storage-load", codeVersion, 1, parameters, inputs)`, row counts, invariant results, output SHA-256, `provenanceDocuments` (digest to full provenance), `neptuneLineage`, and the analytics `reload` result),
`object-store/` (a `FileObjectStore` root, bucket `local-load`: `load-manifest.json` published through the `ObjectStore` port with `ObjectRunManifest.publish` under `loads/storage-load/run=<hex>/`, with its `manifest.json` listing runId, stage, code version, parameters, input checksums and the artifact SHA-256; the CSV and item files are uploaded in the same way by the Phase 14 load) and `checksums.sha256`.
Output is deterministic: sorted rows and keys, no timestamps, no host paths. Spark is `Provided`, so run from the repository root with the Test classpath and ABSOLUTE paths:

```
sbt "storage/Test/runMain scholarchain.storage.LocalLoad --run-root $PWD/modules/spark/target/runs/sparkS1r2 \
  --out-dir $PWD/modules/storage/target/loads/<name> --code-version \"$(git rev-parse --short HEAD)+phase07\""
diff -r modules/storage/target/loads/L1 modules/storage/target/loads/L2
```

Exit status 0 only when every invariant holds; any other result prints `FAILED:` lines and exits 1 (2 for a usage error).

## 9. Live AWS suites (`src/it/scala`, NOT RUN in this phase)

`S3ObjectStoreAwsItSuite` (one 64-byte put, idempotent re-put, get), `DynamoConditionalWriteAwsItSuite` (conditional writes on a QuerySession item with a one-hour TTL), `S3VectorsAwsItSuite` (10 vectors, one filtered query, in the dedicated test index, see 12.8), `EmbeddingQueueAwsItSuite` (Phase 08, at most 5 jobs, see 12.7), `NeptuneReadAwsItSuite` (one `neighbors` template query).
They run only when `SCHOLARCHAIN_AWS_IT=1` (the build filters `*AwsItSuite` otherwise; each suite also sets `munitIgnore`). They read names and endpoints from the environment at run time: `AWS_REGION`, `SCHOLARCHAIN_IT_S3_BUCKET`, `SCHOLARCHAIN_IT_VECTOR_DIMENSION`,
`SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT`, `SCHOLARCHAIN_IT_RESEARCHER_ID`, and the `SCHOLARCHAIN_STORAGE_*` keys; credentials come from the default AWS provider chain, created only inside an enabled suite. Only in an approved environment, within the bounds above
(1 + 1 + 10 + 1 small writes or reads).

## 10. Cleanup procedures (documented only; NEVER executed by agents or code; every one needs a human decision, docs/agents.md section 4)

No adapter or loader in this module issues a delete, drop or removal. For each store, removal of a load is a human action, planned from the files a load writes:

* **S3:** every object of a run is under `<prefix><logicalName>/run=<hex>/` and listed with its SHA-256 in that run's `manifest.json`. A human reviews the manifest and removes that prefix (or lets a Terraform-defined lifecycle rule expire it). On a versioned bucket, removal leaves delete
  markers and old versions: they need their own removal.
* **Neptune:** a bulk load has no rollback. Options: (a) restore a cluster snapshot taken before the load; (b) a human-run, reviewed openCypher removal limited to the IDs in the `:ID` columns of that load's CSVs (the `checksums.sha256` and `load-manifest.json` identify the files); (c) recreate the cluster through Terraform.
  Reloading identical data is a no-op (deterministic IDs), so a repeated load needs no cleanup.
* **DynamoDB:** the keys of every loaded item are the `pk` / `sk` pairs of the JSONL files (or the key layouts of section 4). A human removes them (batches of at most 25) or recreates the table through Terraform. Disposable data (QuerySession, TTL-bearing EntityLinkCache entries, the live-suite probe item) also expires by TTL
  once TTL is enabled on the table.
* **S3 Vectors:** keys are listed by `ListVectors`; a human removes them with DeleteVectors (at most 500 per call) or deletes the index through Terraform. The live-suite probe keys are `it-probe-0` to `it-probe-9` in the TEST index `embedding.test.vector-index-name` (Phase 08; before it they were written to `researcher-profile-v1`, a Phase 07 defect, L8: a human should check that production index for those ten keys). The embedding live suite's vector keys are the five `documentId`s `EmbeddingDocument.idFor("researcher_profile", Ids.researcherIdDblpPerson("It Probe <i>"), 2000-2004, 0)` for i = 0..4 (test index), its text objects are `<logs prefix>it-embedding/0.txt` .. `4.txt`.

## 11. Known limits and risks

* Neptune openCypher texts, the S3 Vectors request mapping and the DynamoDB / S3 / SQS adapters were compiled but never run against AWS.
* `S3ObjectStore.list` cannot return SHA-256 hashes (`contentHash = ""`).
* S3 Vectors collision detection is read-then-write (no conditional put).
* `FileObjectStore` and `InMemoryKeyValueStore` model the documented semantics; AWS behavior beyond what is cited was not verified.
* The analytics table names gap (section 4.1).
* The AWS SDK version has had little soak time and no advisory check was done (see `reports/dependency-updates/phase-07-aws-sdk-v2.md`).

## 12. Embedding ingestion path (Phase 08)

Source: `EmbeddingQueue.scala` (producer, message codec), `EmbeddingWorker.scala` (worker, counts), `EmbeddingLedger.scala`, `VectorText.scala` (resolver, test index). Contracts consumed from core: `EmbeddingDocument`, `EmbeddingJob`, `Embedder`/`EmbeddingError`, `EmbeddingSettings` (`embedding.*`). Storage depends on core only; the embedder and the document rendering are composed by the caller (the local run harness in services tests).

**Exact counts, paths, components and bridges NEVER come from vector similarity.** A vector hit is semantic context only: it can suggest which researcher, community, path or topic text is relevant. Counts, paths, components and bridges come from the graph store (Neptune) or the metric stores (DynamoDB analytics tables). Nothing in this path produces or stores such a fact.

### 12.1 Queue message format

* Body = the `EmbeddingJob` JSON written by `Wire.write` (validated before send, `EmbeddingJobMessage.encode`) and read by `Wire.read` (`decode`; a body that is not a valid job is a `Left`, never an exception). A job holds 1 to 50 `EmbeddingDocument` references (NO text), the model ID and version, `attempt` (1-based), `status`, `failureReason`. The serialized job is at most 204,800 bytes (core), far under the SQS 1 MiB limit (`WorkQueueLimits`).
* Producer queue = `embedding.queue.name`; dead-letter queue = `embedding.queue.dead-letter-name`. Both are logical names mapped to URLs by configuration (`SqsWorkQueue`); no URL is built here.
* Deduplication ID = `EmbeddingJobMessage.dedupId(jobId, attempt, purpose)` = SHA-256 digest (64 hex). Purposes: `enqueue`, `retry`, `dlq-<receiveCount>`. `InMemoryWorkQueue` deduplicates on it; SQS FIFO does within its window; a standard queue does not, and the worker is idempotent anyway.
* Job ID = `embjob-` + 32 hex of a digest of (run ID, model ID, model version, document IDs). Deterministic.
* A DLQ message is an `EmbeddingJob` with `status = dead_lettered`, only the failed documents, and `failureReason` = `dead_lettered: <code>=<n>,...` (stable codes only, at most 500 characters; never text, request bodies or credentials). An unparseable original goes to the DLQ as the envelope `{status, failureReason: "invalid-job-message", messageId, bodySha256}`; its body is not copied.

### 12.2 Producer (`EmbeddingProducer.enqueue(runId, List[EmbeddingSource])`)

1. Each document must satisfy its core contract and `Hashing.textHash(text) == textHash` (else rejected `invalid-document` / `text-hash-mismatch`; empty or non-UTF-8-round-trip text `text-not-encodable`).
2. The text is written to the ObjectStore at `textS3Uri` (UTF-8, `text/plain; charset=utf-8`, `putIfAbsent` with the expected hash; ordinary S3 via `S3ObjectStore`, `FileObjectStore` locally). Identical content already there is a no-op (`textsUnchanged`); different content is `text-conflict` and is never overwritten. The same document ID twice with different text hashes: `duplicate-id-different-text`. A retryable store error aborts the run before any job is sent.
3. Accepted documents are grouped by document type (a job targets one index), sorted by ID, and cut into jobs of at most `maxDocumentsPerJob` (`embedding.queue.max-documents-per-job`, at most 50) documents and at most 204,800 serialized bytes. `maxDocumentsPerRun` bounds one call (`InvalidRequest` above it). Jobs are sent in order with their dedup IDs.
4. Output is deterministic: sources sorted by (document type, document ID), no clock, no random ID. `ProducerReport` lists run ID, counts, rejected (document ID, code), job IDs.

### 12.3 Worker (`EmbeddingWorker.runOnce()` / `drain(maxMessages)`)

Per document, in order: (1) **idempotency first**: `VectorStore.get(index, documentId)`; found with the same text hash, model ID, model version, dimension and text pointer -> skipped (no text fetch, no embed call, no write); found with a different text hash -> rejected `key-collision`; found with the same text hash but another model, version, dimension or pointer -> rejected `embedding-mismatch` (never overwritten; a new model version needs a new index or key namespace, a human-approved procedure). (2) fetch the text at `textS3Uri`, verify SHA-256 = `textHash`. (3) embed through the `Embedder` port in batches of `maxBatchSize`; the job's model ID and version must equal the embedder's (else the whole job is dead-lettered `model-mismatch` with no embed call), `expectedDimension` (config) must equal the embedder's, and each returned vector must have `embedder.dimension` entries. (4) `putBatch` one `VectorRecord` per document: key = `documentId`, model ID, version, dimension, `textHash`, `textS3Uri`, `runId` = the job's run, and COMPACT metadata only: `documentType`, `entityId` (or `entityIdHash` = SHA-256 when over 128 characters, never truncated), `window`, `chunkOrdinal`, `chunkCount`. No text and no excerpt in metadata. Index = `EmbeddingDocumentTypes.indexByType` (or `indexOverride`, used by the live suite for the test index). Then the message is deleted. Step (4) is preceded by the **key claim** (12.4a): the vector is written only by a caller that won or matched the claim.

**Retry mechanism (deterministic, explicit re-enqueue).** A retryable failure (embedder `Throttled`/`Unavailable`; a retryable `StorageError` from the object or vector store; a vector-store `ServiceRejected`) does not wait: the worker re-enqueues ONLY the affected documents as the same job (same job ID, same run) with `attempt + 1`, status `pending`, dedup ID `(jobId, attempt + 1, retry)`, and deletes the original. The `attempt` in the body is authoritative; no backoff and no visibility-timeout wait is needed, so a local run needs no clock. Production backoff, if wanted, is the embedder adapter's own concern. When `attempt >= embedding.queue.max-attempts` the failed documents are dead-lettered instead (`retries-exhausted`). Documents already written or skipped in an attempt are not part of the retry job.

**Non-retryable failures dead-letter IMMEDIATELY** (never silently dropped, never retried): `key-collision`, `embedding-mismatch`, `dimension-mismatch`, `invalid-record`, `text-missing`, `text-hash-mismatch`, `text-not-decodable`, `text-store-failed`, `vector-store-failed`, `embed-invalid-request`, `embed-failed`, `embed-count-mismatch`, `unknown-index`, `model-mismatch`, `invalid-job-message`. The DLQ job holds only those documents; the good documents of the same job are stored and acknowledged.

**Ordering of follow-ups and delete.** The retry job and the DLQ job are sent first; the original message is deleted only after both sends succeeded. If a send fails (`queueFailures`), the original is not deleted and is redelivered by the visibility timeout; redelivery is safe (stored documents are skipped). A message that keeps coming back is dead-lettered when `receiveCount > max-attempts` (`redelivery-bound-exceeded`); a job with `attempt > max-attempts` is dead-lettered `attempt-bound-exceeded`. A failed `delete` after good sends is counted (`queueFailures`) and also leads to a harmless redelivery.

**Counts** (`EmbeddingCounts`, plain data; reason keys sorted): `jobsProcessed`, `documentsSeen`, `documentsEmbedded`, `vectorsWritten`, `skippedUnchanged`, `jobsRetried`, `documentsRetried`, `jobsDeadLettered`, `documentsDeadLettered`, `embedCalls`, `ledgerFailures`, `queueFailures`, `rejectedByReason`. `documentsSeen` counts a retried document again on each delivery. `drain` stops at an empty queue, at the message bound, or on a receive error (`EmbeddingDrainResult.stoppedBy`).

### 12.4 Idempotency and key collisions

The vector key is `EmbeddingDocument.documentId`. The same key can never silently refer to different text: the worker checks before embedding, and the vector store (`VectorChecks.againstStored`) rejects `KeyCollision` on a different text hash, in both the fake and the S3 Vectors adapter. Same key + same text hash + same model version + same pointer from a new run is a no-op (5.3). A new `runId` alone is never a conflict.

### 12.4a Key claims (Phase 08 repair 01, M3)

S3 Vectors has no conditional put, so two workers handling the same key could both pass `VectorStore.get` and the last writer would win. The authority is therefore a **claim** written with a conditional put (`PutCondition.NotExists`) through the core `KeyValueStore` port (`EmbeddingKeyClaims`, `EmbeddingKeyClaims.scala`). `VectorStore.get` stays as the cheap skip-unchanged fast path before any text fetch or embed call; the claim is taken after the text is verified and embedded, immediately before the vector write.

* **Table** `embedding.claims.table-name` (default `scholarchain-embedding-key-claims`): partition key `pk` = vector key (string, at most 512 characters), NO sort key (`EmbeddingKeyClaims.Schema` = `KvTableSchema(hasSortKey = false)`), on-demand capacity, no TTL (claims are permanent). **Phase 12 (infra-engineer) must create this DynamoDB table**, and the worker's role needs `dynamodb:PutItem` on it (conditional put; no `GetItem`, `Query` or delete is used). No table exists yet; the worker cannot run against AWS until it does.
* **Item attributes** (strings): identity = `textHash`, `embeddingModelId`, `embeddingModelVersion`, `dimension`, `textS3Uri`, `indexName`; provenance (never identity) = `runId`, `jobId`, `claimVersion` (`1`). The vector key is claimed across indexes (an `indexOverride` run must use its own claims table or a distinct key namespace).
* **Outcomes** (`ClaimOutcome`): `Won` (item written) -> write the vector. `Identical` (the stored item has the same identity; `runId` and `jobId` are ignored, so a reload from a new run is a no-op) -> proceed to the vector write. `KeyCollision` (different `textHash`) -> dead-letter `key-collision`, never write. `EmbeddingMismatch` (same hash, any other identity attribute differs) -> dead-letter `embedding-mismatch`, never write. `StoreFailed` (any `Left` from the store, or a failed condition with no item returned) -> the document is RETRIED (code `claim-store-failed`, bounded by `max-attempts`, then `retries-exhausted`); it never falls through to an unclaimed write.
* **Identical claim, vector missing** (a previous claimant crashed between claim and write, or two workers hold the same claim): the worker performs the write. The vector store treats an identical existing record as an idempotent `Accepted` (5.3), so this is safe. A crash after the claim and before the write therefore never blocks the key; the retry or redelivery completes it.
* **Remaining limits.** (1) Two workers with an identical claim may both embed and both write; with a non-deterministic embedder the second write can be `embedding-mismatch` because the stored embedding differs (it is dead-lettered, the first vector stays). (2) A claim is permanent: a claim without a vector (embedder retries exhausted after a won claim) is harmless for the same text and model, but a legitimately re-rendered text under that key stays a `key-collision` until a human removes the claim (data destruction, human-only, section 10). (3) Claims do not cover vectors written outside the worker. (4) Not run against DynamoDB; the in-memory store implements the same conditional semantics (`InMemoryKeyValueStore`).

### 12.4b Entity ID recovery from the vector key (M1)

Metadata carries `entityId` only when it is at most 128 characters (frozen `VectorRecord.MaxMetadataValueLength`); otherwise it carries `entityIdHash` (SHA-256, `Hashing.textHash`), as for every `path_summary` entity. In that case the FULL entity ID is recoverable from the vector key, which equals `EmbeddingDocument.idFor(documentType, entityId, window, chunkOrdinal)` = `<documentType>:<entityId>:<window label>:<chunkOrdinal>`. A reader splits off the document type (first `:`), and the window label and ordinal (last two `:`-separated parts; the entity ID in between may itself contain `:`), and cross-checks `Hashing.textHash(entityId)` against `entityIdHash` and the other metadata. Metadata limits are unchanged.

### 12.5 Ledger (`EmbeddingLedger`, `RunLedgerRepository`)

Stage `embedding-enqueue`: one entry per producer run (inputs: the source run and a digest of the document ID + text hash list; parameters: model ID and version, bounds, counts, queue). Stage `embedding-job`: one entry per job delivery attempt; `attempt` = the job's attempt, so retries show as attempt 2, 3 (retry count = attempt - 1); inputs: the job's run and each document (ID, text hash); parameters: job ID, model ID and version, dimension, document count, max attempts, index. `runId` = `Ids.runId(stage, codeVersion, attempt, parameters, inputs)`. Entries start `started` and end `succeeded` (all documents stored or skipped) or `failed` with a short code-only reason (`retry 2 doc(s) as attempt 2 of 3: throttled=2`, `dead_lettered 1 doc(s): key-collision=1`). Timestamps come from the injected clock (`() => ISO-8601 instant`; `finishedAt` is never before `startedAt`). Jobs dead-lettered before processing (invalid message, model or attempt bound) get no ledger entry; their DLQ message and the counts are the record. A ledger failure is counted (`ledgerFailures`) and never undoes the work.

### 12.6 Text pointer resolution (`VectorTextResolver(objectStore)`)

`resolve(VectorRecord | VectorEntry | VectorHit): StorageResult[ResolvedText]` fetches the object at `textS3Uri`, verifies SHA-256 = `textHash` (`IntegrityMismatch` otherwise, no text returned) and decodes UTF-8. A `VectorHit` carries `textS3Uri` and `textHash` in its system metadata (5.2), so a query result resolves without a second lookup; a hit without them is `NotFound`.

### 12.7 Live suite `EmbeddingQueueAwsItSuite` (`src/it/scala`, NOT RUN)

Enqueues five one-document jobs and drains at most 5 messages through SQS, S3 (text objects) and S3 Vectors (the test index). Disabled by default: filtered by name and `SCHOLARCHAIN_AWS_IT=1`, plus `munitIgnore`; no client is constructed unless it is set (clients are built inside the test body). **Limitation: Bedrock is not covered.** The Bedrock embedder is in `rag`, which storage may not depend on, and the storage build has no Bedrock SDK module; the suite uses a local deterministic hash embedder (`it-local-hash`) defined in the suite file. Extra environment: `SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL`, `SCHOLARCHAIN_IT_EMBEDDING_DLQ_URL`. The test index and queues must exist (Terraform, infra-engineer).

### 12.8 Phase 07 carry-overs fixed here

* **L4 / (a) Neptune format:** only `opencypher` is accepted (3.4).
* **L8 / (b) Test index:** every live suite that writes vectors (`S3VectorsAwsItSuite`, `EmbeddingQueueAwsItSuite`) targets `embedding.test.vector-index-name`, validated by `VectorTestIndex.resolve` (core `EmbeddingSettings.checkTestIndex`: never one of the five production indexes nor a configured storage index) and wrapped with `VectorTestIndex.guard`, which refuses every other index. **Deviation, disclosed:** the work order asked the suites to delete the vectors they wrote by exact key. Law 11 of storage-engineer forbids any delete and escalates data destruction to a human, and the `VectorStore` port has no delete. So the suites write only deterministic keys into the test index, an identical rerun is a no-op (N2 fix), and the exact keys are listed in section 10 for a human to remove.
* **N2 / (c) New-run reload:** section 5.3.

### 12.9 Known limits (Phase 08)

* Not run against AWS: SQS, S3 Vectors and S3 adapters in this path are exercised only through the fakes; the 10K ingestion is Phase 15.
* A document whose text changes under the same ID (a re-rendered summary) is `key-collision` and dead-lettered by design; so is a new model version under the same key (`embedding-mismatch`). Re-embedding needs a human-approved new key namespace or index.
* An embedder batch error rejects or retries the whole batch (one bad text can fail its neighbors' batch; the retry subset is then smaller only by documents that finished).
* `InMemoryWorkQueue` deduplicates forever; SQS standard queues do not deduplicate. Both are covered by the idempotent worker.
* Concurrent workers: S3 Vectors has no conditional put (5.3); the claims table (12.4a) closes the different-text window. Its remaining limits are listed there, and the table itself is a Phase 12 (infra-engineer) prerequisite.
