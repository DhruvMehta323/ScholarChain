# Phase 07 — Storage adapters and loading: agent report

Assembled by the orchestrator. Agent reports are pasted in condensed form. Nothing is reported as run unless its output was observed.

## Preconditions

- `git status --short`: the only entry is `?? docs/agent-runs/phase-07-storage/` (this phase's evidence folder holding the work order). Otherwise clean.
- HEAD `1f6c6f0` descends from Phase 06 `commit.txt` `609ea219584987789120165a0fdf6b072565ad8b` (`git merge-base --is-ancestor`: DESCENDS).
- Baseline canonical command `sbt check; echo "exit=$?" > .claude/.last-test-run` (one idle sbt server for this repo, pid 45942, was reused):
  - testFull: core `Passed: Total 760`, entityResolution 293, hadoop 192, sparkAnalytics 268; Failed 0. **1,513 passed, 0 failed**, matching the Phase 06 end state.
  - `[success] elapsed time: 1200 s`; marker `exit=0`.
- Input: `modules/spark/target/runs/sparkS1r2/` (run `run:4d8735e6…26ceaa`, code version `baa31d5+phase06r2`). Tables are Parquet under `scholarchain-graph/<table>/` (each with `_provenance.json`); windowed tables are partitioned by `window=`. Load manifests in `load-manifests/`: `neptune.json`, `dynamodb.json`, `s3-vectors.json`, `s3.json`.
  - Neptune manifest, vertices (label, rows): Researcher 3,893; ExternalIdentity 1,423; Work 1,000; Topic 206; Institution 250; Venue 155; Community 1,755 (windowed); Component 1,735 (windowed).
  - Neptune manifest, edges (type, rows): AUTHORED 3,928; COAUTHORED_WITH 15,107 (windowed); RESOLVES_TO 1,423; HAS_TOPIC 297; PUBLISHED_IN 1,000; MEMBER_OF 6,937 (windowed); IN_COMPONENT 6,937 (windowed); COMMUNITY_LINK 20 (windowed).
  - Not produced by Phase 06: AFFILIATED_WITH, DERIVED_FROM, SUPPORTED_BY edges (the work order says "as implemented").
  - DynamoDB manifest (table, rows, PK / SK): researcher-metrics 6,937 (researcherId / window); coauthor-edge-works 15,111 (edgeId / workId); bridges 20,811 (researcherId / window, measure); coreness 6,937; temporal-changes 4,632; paths 63 (startId / window, endId); path-estimates 6; window-summary 6; identity-clusters 3,907; mention-researcher 4,711.
  - S3 Vectors manifest: semantic-summaries 8,958 rows (keys entityId, window, documentType; textColumn `text`, textHashColumn `textHash`); embeddings not computed (Phase 08).
  - S3 manifest: 30 tables, 118,150 rows in total.
- Module boundaries (CLAUDE.md, `build.sbt`): `storage.dependsOn(core)` already exists; `storage` has no edge to `sparkAnalytics`, `hadoop` or `entityResolution`, and it will read their outputs as files plus manifests. CLAUDE.md does not forbid a library dependency; reading Parquet may use the Spark SQL library as `Provided` (same as `sparkAnalytics`), never `modules/spark` code. No module edge outside §1 is needed. Note: `build.sbt` already filters `*IntegrationSuite`; the new `*AwsItSuite` gate is A0 work.
- Items carried from the Phase 05 and 06 gates: provenance is a JSON string column (it must parse into core `Provenance`); edges and metrics are `CoauthorEdgeV2` and `ResearcherMetricsV2` (frozen v1 untouched); the canonical researcher ID is the DBLP base ID (`Ids.researcherIdDblpPerson`, a merged cluster takes its smallest member key); ORCID is a property plus a RESOLVES_TO edge to an `ExternalIdentity` vertex (`researcher:orcid:<orcid>`); COAUTHORED_WITH evidence is the pointer (`evidenceTable` coauthor-edge-works, `evidenceKey` = edgeId, `evidencePartition` = window).
- Core inspection for A1: `EvidenceBundle` exists (`Records.scala:543`); `StorageConfig` exists with buckets, one vector index, table prefix and Neptune endpoint. Missing: `VectorRecord`, `RunLedgerEntry`, `ArtifactRegistryEntry`, `QuerySessionRecord`, `EntityLinkCacheEntry`, and the five store ports. A1 runs.

## A0 — dependency-updater + build-engineer (condensed)

- dependency-updater: STATUS COMPLETE. `project/Versions.scala` +5 lines: `val awsSdkV2 = "2.59.7"`, with the source `https://repo1.maven.org/maven2/software/amazon/awssdk/bom/maven-metadata.xml` and the date checked (2026-11-21). The agent reports `latest = release = 2.59.7` in the metadata of bom, s3, dynamodb, s3vectors, neptunedata, sqs, sts, url-connection-client and apache-client, and HTTP 200 for each 2.59.7 POM. Report: `reports/dependency-updates/phase-07-aws-sdk-v2.md`. **No CVE or advisory check was performed** (disclosed in the report); the version was published 2026-11-21, so it has had little soak time.
- build-engineer: STATUS COMPLETE. The orchestrator checked `git diff`:
  - `project/Dependencies.scala` +16: `awsS3`, `awsDynamodb`, `awsS3vectors`, `awsNeptunedata`, `awsSqs` and `awsSts` (sts for the live identity check only), each excluding `apache-client` and `netty-nio-client`, plus `awsUrlConnectionClient` (the single pinned HTTP client). No Bedrock.
  - `build.sbt` +23/−1: `storage` keeps `.dependsOn(core)` only, and adds the 7 AWS dependencies, `Dependencies.sparkSql` (Provided, for reading Parquet as files) and `Test / parallelExecution := false`. In `commonSettings`, a new `Tests.Filter` skips `*AwsItSuite` unless `SCHOLARCHAIN_AWS_IT` is exactly `1`. The header comment documents the variable.
  - Agent commands: `sbt scalafmtSbt` exit 0; `sbt "storage/update; storage/Test/compile; scalafmtSbtCheck"` success; `storage/dependencyTree` success. The agent reports that apache-client and netty-nio-client are absent from the storage tree and that sbt printed no eviction warnings. dependencyTree shows slf4j-api 1.7.36 → 2.0.17 and Spark's netty 4.1.119 → 4.2.7 (Spark transitives); the AWS SDK uses its shaded `third-party-jackson-core`.
  - Disclosures: two sbt typo runs (no effect); one `cd` inside a Python edit command, against the instruction.
  - Risks the agent named: sync clients only (async would need netty, a new decision); Spark is Provided, so only the loader may use Spark SQL, never the service runtime; the AWS SDK reaches `services` transitively through `storage`.
- Orchestrator canonical run after A0: testFull core 760, entityResolution 293, hadoop 192, sparkAnalytics 268, storage 0; Failed 0. **1,513 passed, 0 failed** (unchanged). `[success] elapsed time: 1289 s`; marker `exit=0`. No `*AwsItSuite` exists yet, so the filter is loaded but not exercised.

## Progress checkpoint

A0 done (1,513 passed, exit=0). Next: A1, domain-engineer.

## A1 — domain-engineer (condensed)

- STATUS: COMPLETE. Additive only; `git diff --stat` shows no modified core file. Created under `modules/core/src/main/`:
  - `StorageRecords.scala`: `VectorRecord`, `RunLedgerEntry`, `ArtifactRegistryEntry`, `QuerySessionRecord`, `EntityLinkCacheEntry`; enums `RunStatus` (started|succeeded|failed) and `QuerySessionStatus` (received|answered|rejected|failed); `VectorIndexes` (the five index names).
  - `StoragePorts.scala`: `StorageError` (NotFound, AlreadyExists, IntegrityMismatch, InvalidRequest, Throttled, Unavailable, Failed; `retryable` for Throttled and Unavailable) and the ports `ObjectStore`, `GraphStore` (with `GraphTemplates`: neighbors, coauthor-edge, shortest-path, component, community, researcher-metrics; `QueryBounds`; bulk-load submit and status), `KeyValueStore` (`PutCondition` Always / NotExists / AttributeEquals; `ConditionFailed` is an outcome, not an error), `VectorStore` (per-key Accepted / Rejected: DimensionMismatch, KeyCollision, InvalidRecord, ServiceRejected; `MetadataFilter` Equals / In / And) and `WorkQueue`. No AWS type; every call returns `Either[StorageError, A]`.
  - `StorageModuleConfig.scala` + `resources/scholarchain-storage.v1.conf`: keys under `storage.` (env `SCHOLARCHAIN_STORAGE_<KEY>`, override `scholarchain.storage.<key>`): 6 S3 prefixes, Neptune bulk-load role ARN / format / S3 source, 5 DynamoDB table names, the vector bucket (`storage.vectors.bucket-name`, chosen so it does not collide with the frozen `storage.vector-bucket`) and the 5 index names, query bounds (defaults depth 6, results 100, timeout 5000 ms), and retry (5 attempts, 100–5000 ms; caps 10 attempts and 60 s). Defaults are placeholders (dummy account `000000000000`, `placeholder-scholarchain-bucket`). The existing `StorageConfig` is untouched.
  - 5 examples in `resources/contracts/` (`vector-record`, `run-ledger-entry`, `artifact-registry-entry`, `query-session-record`, `entity-link-cache-entry` `.example.json`), generated by `Wire.write` from a scratch main that was then deleted. The files have no trailing newline.
- Key rules: `VectorRecord` metadata at most 16 entries, names ≤ 64 characters (non-blank), values ≤ 128 characters (**ScholarChain policy, not AWS limits**); dimension 1..4096; text only as `textS3Uri` + SHA-256 `textHash`. `RunLedgerEntry.runId` must equal `Ids.runId(stage, codeVersion, attempt, parameters, inputs)`, and status implies which of finishedAt / failureReason are present. `QuerySessionRecord` requires an expiry within 30 days and stores only the question hash. `EntityLinkCacheEntry` has an optional expiry, and `match` requires `researcherId`. `ArtifactRegistryEntry` and the link cache require `provenance.runId` = `runId` when present. Errors never echo values.
- `EvidenceBundle`: the existing record (`Records.scala:543`, versioned, with a contract) is used as the storage form; there is no second form. Size-spill to S3 is an adapter decision.
- Agent commands: `sbt "core/scalafmt; core/compile; core/Test/compile"` success; the scratch generator and a config probe via `core/runMain` succeeded (defaults load; `SCHOLARCHAIN_STORAGE_RETRY_MAX_ATTEMPTS=99` gives `Left(... 'storage.retry.max-attempts': must be <= 10)`, with no value echoed); after deleting the scratch file, `core/scalafmtCheck; core/compile; core/Test/compile` success.
- No tests yet; routed to C (contract tests for each record, the config loader and the port value types).
- Orchestrator canonical run after A1: testFull core 760, entityResolution 293, hadoop 192, sparkAnalytics 268; Failed 0. **1,513 passed, 0 failed**. `[success] elapsed time: 1361 s`; marker `exit=0`.

## Progress checkpoint

A1 done (1,513 passed, exit=0). Next: B, storage-engineer.

## A0 follow-up — build-engineer (condensed)

- Reason: the work order asks storage-engineer to write the `*AwsItSuite` suites, but storage-engineer cannot write `src/test/**`. Following the hadoop precedent (docs/agents.md §1.1 item 2), they go in `modules/storage/src/it/**` (storage-engineer's territory under §1).
- STATUS: COMPLETE. `build.sbt`, storage project only: `Test / unmanagedSourceDirectories += src/it/scala` and `Test / unmanagedResourceDirectories += src/it/resources`, plus a comment that AWS suites there must be named `*AwsItSuite`. Agent: `sbt scalafmtSbt` exit 0 (with a `cd` prefix, against the instruction, harmless); `sbt "scalafmtSbtCheck; storage/Test/compile"` exit 0.
- Canonical check: folded into B's run (one full run per work order).

## B — storage-engineer (condensed)

- STATUS: COMPLETE. Created `modules/storage/STORAGE-CONTRACTS.md`; 19 sources in `modules/storage/src/main/scala/scholarchain/storage/` (Cells, Manifests, ParquetTables, NeptuneCsv, NeptuneInvariants, RowContracts, NeptuneLoader, NeptuneQueries, NeptuneAws, ObjectStores, KeyValueStores, Repositories, AnalyticsItems, VectorStores, Retry, AwsErrors, WorkQueues, AwsClients, LocalLoad); 5 files in `src/it/scala/scholarchain/storage/` (AwsItSupport plus `S3ObjectStoreAwsItSuite`, `DynamoConditionalWriteAwsItSuite`, `S3VectorsAwsItSuite`, `NeptuneReadAwsItSuite`). Nothing outside `modules/storage/` was modified. A scratch smoke main was created and deleted.
- Adapters: `FileObjectStore` (fake) and `S3ObjectStore`; Neptune openCypher CSV writer, bulk-load request builder, `TemplateGraphStore` over `neptunedata` with the six allowlisted templates (parameters only in the openCypher `parameters` JSON; `{{DEPTH}}` and `{{TIMEOUT}}` are replaced by digits only after bounds checks; `LIMIT maxResults + 1` reports `truncated`); `InMemoryKeyValueStore` (conditional-write semantics) and `DynamoKeyValueStore` with RunLedger, ArtifactRegistry, EntityLinkCache, QuerySession and EvidenceBundle repositories (identical reload → Unchanged, different payload → Conflict; TTL only on QuerySession and EntityLinkCache; EvidenceBundle spills to S3 above 300,000 bytes); `InMemoryVectorStore` (cosine top-k, Equals / In / And filters), `S3VectorsStore`, and `VectorLoader` (batching, bounded retry with an injectable sleeper, a rejection sink); `InMemoryWorkQueue` and `SqsWorkQueue`. ResearcherMetricCache is omitted (served by the researcher-metrics items). OpenSearch is NOT implemented (stated).
- AWS-off design: `AwsClients(credentialsProvider, region)` builds clients lazily and only through injected credentials. The orchestrator's grep finds `DefaultCredentialsProvider`/profile providers only in `src/it/AwsItSupport.scala` and in a comment in `AwsClients.scala`.
- Service limits: recorded in `STORAGE-CONTRACTS.md` §5.1 with URLs and access date 2026-11-21 (curl of public AWS docs, HTTP 200 each). S3 Vectors (source `https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html`): 500 vectors per PutVectors, 20 MiB payload, dimension 1–4,096, topK ≤ 10,000, key 1–1,024, ≤ 50 metadata keys, ≤ 10 non-filterable keys, filterable metadata 2 KB on CLASSIC indexes (40 KB on ENHANCED), total 40 KB (CLASSIC). The adapter enforces CLASSIC. Also Neptune CSV format and loader API, DynamoDB constraints and TTL, and SQS quotas. **NOT VERIFIED (disclosed):** the exact byte accounting of filterable metadata; the per-vector rejection error inside PutVectors; the SQS `WaitTimeSeconds`/`VisibilityTimeout` upper bounds; that Neptune accepts the template Cypher; the semantics of the query-timeout hint value.
- Deviations and assumptions the human should see:
  1. Array and map properties are written to Neptune as JSON strings (for example `representativeWorkIds:String`), not `String[]`. The agent's reason is that the Neptune openCypher docs advise against array properties and the openCypher CSV page documents no array syntax.
  2. Row-level provenance is not copied onto Neptune edges or items; items carry `provenanceDigest` + `provenanceRunId`, and the full provenance is listed once in `load-manifest.json`.
  3. Edges without a Spark ID get a deterministic `nedge:` ID (a core length-prefixed SHA-256 of the label and the manifest key columns); undirected edges are stored once (smaller to larger ID).
  4. **Gap:** the analytics tables (researcher-metrics, coauthor-edge-works and the rest) have no physical DynamoDB table names in core `StorageModuleConfig` and no Terraform. This is routed as a follow-up (domain-engineer config, infra-engineer Terraform) and not worked around.
  5. S3 Vectors has no conditional put, so collision detection is read-then-write (a race between concurrent writers is possible).
- Agent commands (observed by the agent): curl of public AWS docs; `sbt "storage/scalafmt; storage/Test/scalafmt; storage/compile; storage/Test/compile; storage/scalafmtCheck; storage/Test/scalafmtCheck"` [success] (twice); three early loader runs failed without output (partition-column order, bigint mapping, a `toCell` bug) and were fixed; then three loader runs `sbt "storage/Test/runMain scholarchain.storage.LocalLoad --run-root <abs sparkS1r2> --out-dir <abs loads/L1|L2|L3> --code-version 1f6c6f0+phase07"` [success]. L1 and L2 were produced before a formatting-only change; L3 after it.
- Local loader result (L1): 28 files (8 vertex CSVs, 8 edge CSVs, 10 DynamoDB JSONL, `load-manifest.json`, `checksums.sha256`); loadRunId `run:6f1c7c4f…3d1` in all three runs.
  - Vertices: Researcher 3,893; ExternalIdentity 1,423; Work 1,000; Topic 206; Institution 250; Venue 155; Community 1,755; Component 1,735 (all equal to the manifest; the loader fails otherwise).
  - Edges: AUTHORED 3,928; COAUTHORED_WITH 15,107; RESOLVES_TO 1,423; HAS_TOPIC 297; PUBLISHED_IN 1,000; MEMBER_OF 6,937; IN_COMPONENT 6,937; COMMUNITY_LINK 20. AFFILIATED_WITH, DERIVED_FROM and SUPPORTED_BY are absent from the Phase 06 output and were not fabricated; Institution vertices have no incident edge.
  - DynamoDB items: researcher-metrics 6,937; coauthor-edge-works 15,111; bridges 20,811; coreness 6,937; temporal-changes 4,632; paths 63; path-estimates 6; window-summary 6; identity-clusters 3,907; mention-researcher 4,711.
  - Invariants on the re-parsed CSV (agent-reported): dangling endpoints 0; endpoint label mismatches 0; duplicate vertex and edge IDs 0; vertex, endpoint and edge-ID mismatches against the Spark IDs 0. All 15,107 COAUTHORED_WITH rows validated as `CoauthorEdgeV2`, and all 6,937 metrics rows as `ResearcherMetricsV2`; provenance parsed with runId equal to the manifest run; the evidence trace (count, digest, representative works ⊆ supporting works, all evidence works are Work vertices) had 0 failures.
- **Orchestrator verification:** `diff -r L1 L2` empty → identical; `diff -r L1 L3` empty → identical. The SHA-256 of the sorted per-file checksum listing is `380765d6dad308ec673eef1e91f1c30ca870a8ae559dea2d8ed3cced2772b124` for both L1 and L2. 28 files in L1. The `COAUTHORED_WITH.csv` header was inspected: `:ID,:START_ID,:END_ID,:TYPE,window:String,jointPublicationCount:Int,firstCollaborationYear:Int,lastCollaborationYear:Int,supportingWorksDigest:String,supportingWorksTruncated:Bool,representativeWorkIds:String,evidenceTable:String,evidenceKey:String,evidencePartition:String`.
- Cleanup procedures for S3, Neptune, DynamoDB and S3 Vectors are documented in `STORAGE-CONTRACTS.md` §10 (documented only, never executed). Live `*AwsItSuite` suites: **NOT RUN** (AWS off; Phase 14–15).
- Orchestrator canonical run after B (also covers the A0 follow-up): testFull core 760, entityResolution 293, hadoop 192, sparkAnalytics 268, storage 0; Failed 0. **1,513 passed, 0 failed**. `[success] elapsed time: 1360 s`; marker `exit=0`. The run output contains 0 occurrences of `AwsItSuite` (the four suites compiled but did not run).

## Progress checkpoint

B done (1,513 passed, exit=0; L1 = L2 = L3). Next: C, test-engineer.

## C — test-engineer (condensed)

- STATUS: FAILED (one deliberate red test exposing a production defect; no production edits). Created 2 core suites in `modules/core/src/test/scala/scholarchain/core/`, and 10 suites plus `StorageFx.scala` in `modules/storage/src/test/scala/scholarchain/storage/`.

| Suite | New tests |
|---|---|
| core StorageRecordsContractSuite | 34 |
| core StorageConfigContractSuite | 19 |
| storage NeptuneCsvSuite | 14 |
| storage NeptuneInvariantsSuite | 22 |
| storage NeptuneQueryTemplatesSuite | 17 |
| storage DynamoRepositoriesSuite | 28 |
| storage VectorStoreSuite | 22 |
| storage VectorLoaderRetrySuite | 11 |
| storage FileObjectStoreSuite | 14 |
| storage AwsOffByDefaultSuite | 9 |
| storage LocalLoadOutputSuite | 3 |
| **New total** | **193** (core 53, storage 140) |

- Agent targeted runs: `core/testOnly *` 813 passed, 0 failed; `storage/testOnly *` Total 140, Passed 139, **Failed 1**; test scalafmt and scalafmtCheck success for both. Some read-only Bash calls had a `cd` prefix, against the instruction.
- **Failing test:** `FileObjectStoreSuite.scala:126` "a key that ObjectStores.validKey accepts is storable (a 300-byte path segment is a valid S3 key)". The result is `Left(Unavailable(put: I/O failure))`. `ObjectStores.validKey` accepts a 300-byte segment (a valid S3 key), but `FileObjectStore.put` hits the OS 255-byte filename limit and maps the `IOException` to `Unavailable`, which is **retryable**, so a retry would loop on a permanent error. Owner: storage-engineer (`ObjectStores.scala`). The agent rates it LOW (fake only).
- Coverage notes: the five examples round-trip byte-identically; the invariant negative controls (dangling endpoint, wrong label, changed vertex, endpoint and edge IDs, duplicate ID, bad ID shape, corrupted header); 11 injection strings rejected with zero executor calls, and valid input leaves the query text equal to the constant template; one test per documented DynamoDB access pattern; idempotent reload → Unchanged with identical state; different payload → Conflict; batching at 500 vectors and 20 MiB; retry with a recording sleeper; a profile gate with a throwing credentials provider that is never called after constructing `AwsClients`, using the fakes, or building every SDK client, plus a positive control (the provider IS called when a Neptune request is signed against loopback `127.0.0.1:9`, failing before any connection).
- Not tested (agent-stated): that no `*AwsItSuite` ran cannot be observed from inside a test (the test checks the build.sbt filter text and a copy of the predicate; the orchestrator observed 0 `AwsItSuite` lines in the CHECK output); the real AWS adapters beyond the signing control; `AwsErrors.translate`; the work queue beyond a send/receive smoke test; ParquetTables and LocalLoad (only through the L1 output checks, which skip if L1 is absent).
- Orchestrator canonical run after C:
  - testFull: core 813, entityResolution 293, hadoop 192 and sparkAnalytics 268 passed. Storage: `Failed: Total 140, Failed 1, Errors 0, Passed 139`. The failing test is `FileObjectStoreSuite` "a key that ObjectStores.validKey accepts is storable…" (`FileObjectStoreSuite.scala:126`, `Left(Unavailable(put: I/O failure))`).
  - **1,705 passed, 1 failed. STATUS: FAILED.** `[error] elapsed time: 592 s`; marker `exit=1`.
  - The output contains 0 occurrences of `AwsItSuite`.

## Progress checkpoint

C done with 1 failing test (1,705 passed, 1 failed, exit=1). Next: D, code-reviewer (read-only, run in parallel with CHECK).

## D — code-reviewer

- Verdict: **REQUEST_CHANGES** (condensed review in `review-report.md`): 0 HIGH, 8 MEDIUM (M1–M8), 13 LOW (L1–L13).
- The reviewer ran in parallel with the CHECK above, so it saw the stale `exit=0` marker from before C. The CHECK result is recorded above.
- Orchestrator check of M4. A web-fetch of `https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html` (2026-11-21) gave, verbatim:
  - "Top-K results per QueryVectors request: Up to 10,000"
  - "Results per page in a QueryVectors response: Up to 100"
  - QueryVectors returns `nextToken`.
- So the cited limits are correct; the reviewer's recollection of a topK maximum of 100 is not. M4 is a code defect: `query` does not paginate.
- The page also lists "Combined vectors inserted and deleted per second per vector index: Up to 2,500" and "Maximum throughput for PutVectors and DeleteVectors: Up to 140 MBps". STORAGE-CONTRACTS.md should record both.

## Progress checkpoint

D done (REQUEST_CHANGES: 0 HIGH, 8 MEDIUM, 13 LOW). `repair-01.md` written. Next: repair 01.

## Repair 01 (`repair-01.md`)

### R1a — dependency-updater (M7, condensed)
- STATUS: COMPLETE. `reports/dependency-updates/phase-07-aws-sdk-v2.md` updated.
  - OSV `POST https://api.osv.dev/v1/querybatch` returned http 200, with 35 results for 35 SDK artifacts at 2.59.7 (s3, dynamodb, s3vectors, neptunedata, sqs, sts, url-connection-client, sdk-core, aws-core, auth, regions, third-party-jackson-core, the protocol, checksum, retry and http-auth modules, and others): **0 vulnerabilities**.
  - GitHub advisories (`affects=software.amazon.awssdk:s3@2.59.7`, unauthenticated) returned http 200 with `[]`.
  - No version change.
- Not checked (disclosed): third-party transitives (Netty, Jackson, slf4j, reactive-streams), NVD, AWS security bulletins and the changelog.

### R1b — domain-engineer (M8 config, M3 port case, condensed)
- STATUS: COMPLETE. Additive only.
  - `StorageModuleConfig.scala` and `scholarchain-storage.v1.conf` gain ten keys, `storage.dynamodb.analytics.<logical-name>-table`, for researcher-metrics, coauthor-edge-works, bridges, coreness, temporal-changes, paths, path-estimates, window-summary, identity-clusters and mention-researcher. Placeholder defaults are `scholarchain-analytics-<name>`. The distinctness check covers all 15 table names.
  - New lookups: `StorageConfigKeys.AnalyticsLogicalNames`, `AnalyticsTableKeys`, `StorageDynamoTables.analytics`, `analyticsTable(name)` and `allWithAnalytics`. `tables.all` is unchanged (5). `StorageConfigKeys.all` goes from 27 to 37 keys.
  - `StoragePorts.scala` gains `VectorRejection.EmbeddingMismatch` (same key and text hash, different embedding or model version), because `KeyCollision` is documented as "different text hash".
- Agent command: `sbt "core/scalafmt; core/compile; core/Test/compile"` [success]. Tests have not run yet.

### R1c — storage-engineer (M1–M6, M8, condensed)

- STATUS: COMPLETE. No core or test file was touched, and no LOW finding was fixed. Created `AnalyticsRepository.scala`. Modified `ObjectStores`, `Repositories`, `VectorStores`, `AwsClients`, `NeptuneCsv`, `NeptuneLoader`, `AnalyticsItems` and `LocalLoad`, plus `STORAGE-CONTRACTS.md` §1, §2, §3.2, §4, §4.1, §5.1, §5.3 and §8.
- **M1:**
  - `validKey` rejects a segment over 240 UTF-8 bytes (`ObjectStores.scala:37-50`) as non-retryable `InvalidRequest`. The limit also applies to the S3 adapter, a documented tightening.
  - A key that is a prefix of a stored key is rejected.
  - Writes go to temp files and are claimed with `createLink`; a failed sidecar move removes the data file this call created.
  - A non-NotFound IOException becomes non-retryable `Failed`.
- **M2:** TTL items carry an `expiry` string attribute. An expired existing item is replaced under `AttributeEquals("expiry", <stored>)` (`Repositories.scala:35, 80, 106`): Written, or Conflict on a concurrent change. A pre-existing expired item without `expiry` stays a Conflict.
- **M3:** `VectorChecks.againstStored` (`VectorStores.scala:142`):
  - an identical record and embedding is a no-op;
  - a different textHash is `KeyCollision`;
  - the same textHash with any other difference is `EmbeddingMismatch`, rejected and recorded (no replace, since S3 Vectors has no conditional put).
  - The adapter's GetVectors now uses `returnData(true)`.
- **M4:**
  - `collectPages` follows `nextToken` (100 per page) until topK, bounded to ceil(topK/100)+1 pages, and stops on a repeated token (`VectorStores.scala:165`, adapter 530).
  - The fake has `queryPage`/`query` with the same contract.
  - §5.1 adds the two verbatim limits (2,500 vectors per second per index; 140 MBps).
- **M5:** every builder applies `AwsClients.hermetic` (an empty `ProfileFile`, profile name `scholarchain-hermetic`) together with the explicit region and the injected credentials. Environment variables such as `AWS_ENDPOINT_URL*` are not suppressed (documented). The behavior is verified by compilation only.
- **M6:**
  - Every vertex and edge CSV row ends with `loadRunId:String,sparkRunId:String,provenanceDigest:String`. The digest is the SHA-256 of the row provenance string, or of the table's `_provenance.json`.
  - `load-manifest.json` gains `provenanceDocuments` (digest → full provenance).
  - The loader publishes the load manifest through `ObjectRunManifest.publish` into a `FileObjectStore` under `<out>/object-store`.
  - With `lineage = None`, the output is unchanged.
- **M8:**
  - `AnalyticsRepository` writes the ten tables through `KeyValueStore`. Items are pk, sk, `payload` (canonical compact JSON) and `payloadHash`; writes are Written / Unchanged / Conflict, never an overwrite; reads verify the hash.
  - `researcherMetrics(id, window)` is one GetItem; `researcherMetricsAllWindows` is one Query.
  - LocalLoad reloads all item files twice into an `InMemoryKeyValueStore` and fails unless the second pass is all Unchanged.
- Agent commands:
  - `sbt "storage/scalafmt; storage/compile; storage/Test/compile; storage/scalafmtCheck"` [success]. Existing tests compile; the agent expects some to fail at runtime because they encode the pre-repair behavior.
  - Loader runs into L4 and L5 (`--code-version 1f6c6f0+phase07r1`), each exit 0.
- Loader result (L4): loadRunId `run:2b0b16cb…7dd`, 32 files.
  - Vertex and edge counts are unchanged from L1.
  - Invariants: dangling 0; all ID mismatches 0; duplicates 0; label mismatches 0.
  - 15,107 CoauthorEdgeV2 and 6,937 ResearcherMetricsV2 rows validated; evidence trace 0 mismatches.
  - M8 reload: pass 1 written 63,121; pass 2 **unchanged 63,121**, conflicts 0.
  - All 46,066 Neptune rows carry a `provenanceDigest` that is present in `provenanceDocuments` (agent's read-only Python check).
- **Orchestrator verification:** `diff -r L4 L5` empty → identical. The sorted checksum-listing SHA-256 is `edff0cc94eceba9e83bd418fe126ed04f3d40ceabbbc6296e6aac04eddaf93be` for both. L4 has 32 files. The `COAUTHORED_WITH.csv` header ends with `loadRunId:String,sparkRunId:String,provenanceDigest:String`.
- Risks the agent named:
  - S3 Vectors may return re-encoded floats, so an identical reload could be reported EmbeddingMismatch (Phase 14 observation).
  - The adapters still have no mocked-client tests (L7 deferred).
  - The 2,500 vectors per second limit is documented, not enforced.

### R1d — test-engineer (condensed)

- STATUS: COMPLETE. Tests only: created `StorageAnalyticsConfigContractSuite` (core, 12 tests) and `AnalyticsRepositorySuite` (storage, 19 tests); modified `AwsOffByDefaultSuite`, `FileObjectStoreSuite`, `DynamoRepositoriesSuite`, `VectorStoreSuite`, `NeptuneCsvSuite` and `LocalLoadOutputSuite`.
- M5: removed the two tests that built SDK clients and signed a request. Added checks that `AwsClients.hermetic` has profile name `scholarchain-hermetic` and an empty profile file (no client built), plus a source-text check that all five builders apply `overrideConfiguration(hermetic)`, the injected credentials and the region. **No longer covered:** that the SDK builders honor the override in practice; that no shared AWS config file is read; that the provider is called only at signing.
- `FileObjectStoreSuite:126` now expects non-retryable `InvalidRequest`. New tests cover M1 (lengths 241–256 rejected with nothing on disk; 240 storable; prefix and extension keys rejected; no data, sidecar or temp file after a failed write; non-NotFound IO failure is non-retryable `Failed`), M2 (expired EntityLinkCache and QuerySession items replaced; unexpired → Conflict; the spy store records exactly `[NotExists, AttributeEquals("expiry", …)]`; concurrent change → Conflict; a legacy item without `expiry` stays Conflict), M3 (EmbeddingMismatch for a different embedding, model version, model ID or metadata; stored vector unchanged; KeyCollision for a different text hash; in-batch duplicates), M4 (250 vectors with topK 250 → 250 hits, pages 100/100/50; the page bound; repeated-token stop; topK > 10,000 or < 1 rejected), M6 (lineage headers and cells; the row-provenance digest checked against an independent SHA-256; colliding property names → Left; `lineage=None` byte-identical) and M8 (reload all Unchanged; Conflict leaves the stored item unchanged; typed JSON round trip; both read patterns; unknown table; tampered payload → IntegrityMismatch; `fromConfig`). `LocalLoadOutputSuite` now reads the newest `loads/L<n>` (L5) and reloads its 63,121 items twice (second pass all Unchanged).
- Agent runs: `core/testOnly *` 825 passed, 0 failed; `storage/testOnly *` 213 passed, 0 failed (run twice); test scalafmt and scalafmtCheck [success]. One compile error in its own test was fixed. Deviation: `storage/scalafmt; core/scalafmt` also covers main sources; the agent checked mtimes and reports that no `src/main` file changed.
- Disclosed: "would fail on pre-repair code" holds by construction and was not observed red-then-green. L7 (no mocked-client adapter tests) remains open.

### Process disclosure (repair 01)

- The original work-order-B storage-engineer agent resumed on its own after its report and ran the canonical command twice in the background, against rule 0.3. One run ended 20:40:15 with marker `exit=1` (before test-engineer's updates); the other started about 20:44 and ended 21:06:49 with `exit=0`. The orchestrator did not start a concurrent run, told that agent to stand down, and did not use either result as evidence. The canonical run below is the orchestrator's, on the final tree.

### Canonical run after repair 01 (orchestrator)

- The canonical command on the final tree:
  - testFull passed: core 825, entityResolution 293, hadoop 192, storage 213; contractTest 825 + 10.
  - sparkAnalytics `Failed: Total 268, Failed 1, Errors 0, Passed 267`.
  - **1,790 passed, 1 failed (corrected; first written as 2,072 by an orchestrator arithmetic error). STATUS: FAILED.** `[error] elapsed time: 1217 s`; marker `exit=1` (21:27:10).
- The failing test is `scholarchain.spark.IdentitySuite` "two clusters sharing one ORCID both keep their DBLP IDs and both point at the ORCID" (7.877 s). The error is `org.apache.spark.SparkException: Job aborted due to stage failure … org.apache.spark.memory.SparkOutOfMemoryError: [UNABLE_TO_ACQUIRE_MEMORY] Unable to acquire 262144 bytes of memory, got 0. SQLSTATE: 53200`.
- Observations:
  - This is a resource error inside the local Spark session, not an assertion failure.
  - `modules/spark` has no change in this phase (`git status --short modules/spark` is empty).
  - The same suite passed in the baseline and in the A0, A1 and B runs (sparkAnalytics 268 passed each time).
  - What changed in the sparkAnalytics test JVM's environment this phase: the build gives `storage` its own Spark-based forked tests, and core gained more tests.
  - Whether this is a transient flake or a reproducible memory regression is **not established**: the orchestrator has not rerun it.
  - 0 occurrences of `AwsItSuite` in the output.

## D — code-reviewer, review 2 (repair 01 only)

- Verdict: **APPROVE WITH NOTES**, conditional on the canonical `sbt check` exiting 0. It did not, so under the reviewer's own condition the verdict becomes REQUEST_CHANGES until CHECK passes.
- M1–M8 are all RESOLVED, with file:line evidence (see `review-report.md`, review 2). M5 is resolved for the gate; that the SDK reads no shared config at runtime is inferred, not observed. M7's network results have no stored raw response.
- New LOW notes:
  - N1: `LocalLoadOutputSuite` "row digest is SHA-256 …" does not check the hash; the manifest stores re-serialized provenance, so table-level digests cannot be re-derived from it.
  - N2: vector identity includes `runId` and `textS3Uri`, so an identical reload from a new run is rejected as EmbeddingMismatch (Phase 08).
  - N3: the commit rollback path after `createLink` is untested; `UnsupportedOperationException` escapes; a narrow concurrent-writer case.
  - N4: the fake rejects prefix keys that S3 accepts; `.tmp-` names are hidden from `list`.
  - N5: expiry replacement depends on the writer's clock.
  - N6: process items (disclosed above).

## Progress checkpoint — STOP (work order §7)

- STOP point reached: **"CHECK fails after a repair."** The orchestrator stops and asks the human. It does not rerun, repair or start round 2 on its own.
- State:
  - Repair 01 is done (M1–M8 resolved per review 2).
  - L4 = L5 identical (orchestrator `diff -r`).
  - CHECK 1,790 passed, 1 failed (corrected; first written as 2,072 by an orchestrator arithmetic error): a Spark OOM in the untouched Phase 06 `IdentitySuite`.
  - Nothing is staged or committed.

## Human decision at the STOP (recorded by the orchestrator from the human's answer)

1. Rerun CHECK once, unchanged, and record both runs honestly.
   - If run 2 passes: write the final checkpoint and STOP for the gate.
   - If run 2 fails again with the same OOM: repair round 2 is authorized as **build-only**. build-engineer raises the forked test-JVM heap (for example `-Xmx3g`) and limits Spark test parallelism if needed. Then CHECK runs, D rereads only that build change, and the orchestrator STOPs for the gate.
   - If run 2 fails with anything other than an OOM: STOP and report.

### CHECK run 2 (human-authorized rerun, unchanged tree)

- Same canonical command, no change to any file between run 1 and run 2.
- testFull: core 825, entityResolution 293, hadoop 192, sparkAnalytics 268 (`IdentitySuite` passed), storage 213; Failed 0; contractTest passed.
- **1,791 passed, 0 failed.** `[success] elapsed time: 1155 s`; marker `exit=0` (21:59:35). 0 occurrences of `AwsItSuite`.
- Honest record of both runs: run 1 had 1,790 passed and 1 failed (the `SparkOutOfMemoryError` in the untouched `IdentitySuite`); run 2 had 1,791 passed and 0 failed. The OOM did not reproduce, so it is treated as a transient resource failure. Its cause is not established, and it may recur on a loaded machine (risk for the human). Build-only repair round 2 was **not needed and not run**.
- Correction: run 1 was first written as "2,072 passed" because the orchestrator added the contractTest lines into the total. The correct count is 1,790 passed and 1 failed, now fixed in this file and in `repair-01.md`.

## Gate summary (observed)

- **Long text not in vector metadata:** values ≤ 128 characters and ≤ 16 entries (core contract), a 2,048-byte filterable JSON cap (adapter), and text only as `textS3Uri` + `textHash`. Reviewer PASS; tested.
- **Neptune IDs equal Spark IDs; COAUTHORED_WITH traces to supporting works; 0 dangling endpoints:** on L4 (agent-reported invariants, confirmed by reviewer reading of `load-manifest.json`), vertex, endpoint and edge-ID mismatches are 0, dangling endpoints 0, and the evidence trace has 0 mismatches over 15,107 edges and 15,111 evidence rows. Every row carries `loadRunId`, `sparkRunId` and `provenanceDigest` (M6).
- **DynamoDB keys support the stated access patterns; loaders are idempotent:** one test per documented pattern; conditional writes (identical → Unchanged, different → Conflict, never an overwrite); expired TTL items are replaced only under the expiry condition (M2); the analytics tables have config names and a repository (M8); the L4 reload's second pass is 63,121 Unchanged, 0 conflicts.
- **AWS suites exist and are off by default; `sbt check` makes no AWS call:** four `*AwsItSuite` suites in `modules/storage/src/it`, filtered unless `SCHOLARCHAIN_AWS_IT=1`, with 0 occurrences in every CHECK output. No test builds an SDK client (M5). AwsClients is hermetic by configuration; that the SDK reads no shared config at runtime is not observed. Live suites: **NOT RUN** (Phase 14–15).
- **Two local loader runs identical:** L1 = L2 = L3 (before repair) and L4 = L5 (after repair), by `diff -r` and the checksum-listing SHA-256 (`edff0cc9…93be` for L4 and L5), observed by the orchestrator.
- **`sbt check` total rises by at least 30:** 1,513 → **1,791** passed, 0 failed (+278), marker `exit=0`.
- **Reviewer verdict:** review 1 REQUEST_CHANGES → repair 01 → review 2 **APPROVE WITH NOTES** (conditional on CHECK passing; run 2 passed).

## Open items for the human gate

1. Deviations:
   - arrays and maps written as JSON strings in the Neptune CSV;
   - items carry a provenance digest, with the full text in `load-manifest.json`;
   - AFFILIATED_WITH, DERIVED_FROM and SUPPORTED_BY absent (not produced by Phase 06);
   - the 240-byte key-segment limit is also applied to the S3 adapter.
2. Deferred LOW notes:
   - review 1: L1–L13, in particular L4 (format `csv` accepted), L8 (the live vector suite writes into the real index), L10 (`LocalLoad`/`ParquetTables` in `src/main`) and L11 (ratification of the `src/it` ownership reading, factory-engineer);
   - review 2: N1–N6, in particular N2 (vector identity includes runId, relevant to Phase 08).
3. M7: third-party transitives (Netty, Jackson) were not advisory-checked, and the OSV raw responses were not stored.
4. The transient Spark OOM in run 1 (cause not established).
5. Process disclosures:
   - the original B agent ran the canonical command twice in the background, against rule 0.3; those results were not used;
   - several agents used `cd` prefixes;
   - test-engineer ran `storage/scalafmt` and `core/scalafmt` over main sources (it reports no main file changed);
   - the orchestrator's arithmetic error (corrected above);
   - two guard false positives on the orchestrator's own report text (no effect).
6. NOT RUN: live AWS suites, any AWS call, Neptune acceptance of the Cypher templates and of the CSV, and OpenSearch (not implemented).

## Progress checkpoint

Phase 07 complete; waiting for human gate. Nothing staged or committed.
