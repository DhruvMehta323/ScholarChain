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
`updateSingleCardinalityProperties`, `queueRequest`. `source` must be an `s3://<bucket>/<prefix>/` URI under the configured `storage.neptune.bulk-load.source-s3-uri`; `format` must equal the configured one; the role comes from
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

* **Key collision and idempotency (M3, `VectorChecks.againstStored`):** the existing vector is compared in full: stored metadata (text hash, model ID and version, `textS3Uri`, `runId` and the record metadata) and the embedding against the new entry. Identical -> idempotent (`Accepted`, nothing written).
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

`S3ObjectStoreAwsItSuite` (one 64-byte put, idempotent re-put, get), `DynamoConditionalWriteAwsItSuite` (conditional writes on a QuerySession item with a one-hour TTL), `S3VectorsAwsItSuite` (10 vectors, one filtered query), `NeptuneReadAwsItSuite` (one `neighbors` template query).
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
* **S3 Vectors:** keys are listed by `ListVectors`; a human removes them with DeleteVectors (at most 500 per call) or deletes the index through Terraform. The live-suite probe keys are `it-probe-0` to `it-probe-9` in `researcher-profile-v1`.

## 11. Known limits and risks

* Neptune openCypher texts, the S3 Vectors request mapping and the DynamoDB / S3 / SQS adapters were compiled but never run against AWS.
* `S3ObjectStore.list` cannot return SHA-256 hashes (`contentHash = ""`).
* S3 Vectors collision detection is read-then-write (no conditional put).
* `FileObjectStore` and `InMemoryKeyValueStore` model the documented semantics; AWS behavior beyond what is cited was not verified.
* The analytics table names gap (section 4.1).
* The AWS SDK version has had little soak time and no advisory check was done (see `reports/dependency-updates/phase-07-aws-sdk-v2.md`).
