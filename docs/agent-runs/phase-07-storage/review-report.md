# Phase 07 — code-reviewer verdicts

Stored by the orchestrator from the code-reviewer's report messages. Condensed; the substance of each finding is kept.

## Review 1 (after A0, A1, B, C)

**Verdict: REQUEST_CHANGES.** 0 HIGH, 8 MEDIUM, 13 LOW.

Verified PASS by reading:
- Module boundaries: storage → core only, Spark SQL Provided, no imports of other modules.
- Cypher injection: constant templates; values only in `parameters`; digits-only holes filled after bound checks; unknown parameter names rejected; 11 injection strings tested with zero executor calls.
- DynamoDB conditions use placeholders.
- Long text kept out of vector metadata: 128-character values, 16 entries, 2,048-byte JSON cap.
- No delete or overwrite on the load paths.
- Secrets: placeholders only; `AwsErrors.translate` never echoes `getMessage`.
- AWS suites are filtered and self-ignore.
- No fabricated edge types.
- L1 invariants all 0; identical L1/L3 checksum listings and loadRunId; 193 test declarations.

### MEDIUM

- **M1. The definition of done is not met.** `FileObjectStoreSuite.scala:126` fails.
  - `ObjectStores.validKey` (`ObjectStores.scala:35-38`) accepts a 300-byte segment. The filesystem rejects it, and the IOException maps to retryable `Unavailable` (lines 184-188, 253).
  - A 252–255-byte segment writes the data file and then fails on the `.json` sidecar, leaving data on disk while reporting Unavailable.
  - Owner: storage-engineer.
- **M2. Expired EntityLinkCache and QuerySession items block rewrites until DynamoDB's TTL deletion.**
  - The `NotExists` put fails, the hashes differ, and the result is `Conflict`, while reads filter out the expired item (`Repositories.scala:206-230`, `ItemWrites.write` 63-85).
  - Owner: storage-engineer.
- **M3. Vector idempotency compares only `textHash`** (`VectorStores.scala:273-284`, 403-416).
  - Re-embedding with a new model version, or a corrected embedding, is reported `Accepted` and nothing is written: stale data reported as success.
  - Owner: storage-engineer (domain-engineer if a new `VectorRejection` case is needed).
- **M4. The per-page query limit is cited but not enforced.**
  - `S3VectorsLimits.MaxQueryResultsPerPage = 100` (`VectorStores.scala:45`) is never used. `topK` up to 10,000 is accepted and `query` does not paginate (lines 458, 475-493), so topK 500 silently returns at most 100.
  - The orchestrator confirmed both limits on the AWS page (see agent-report.md).
  - Owner: storage-engineer.
- **M5. `sbt check` builds real SDK clients.**
  - `AwsOffByDefaultSuite.scala:160-186` builds all five SDK clients and signs a request, against §5 "no AWS client is constructed".
  - SDK v2 builders read the default shared AWS config and credentials files for client defaults. This is inferred, not observed. CLAUDE.md forbids reading the credentials file.
  - Owners: test-engineer (rework), storage-engineer (hermetic `AwsClients`), build-engineer (optional).
- **M6. No Neptune vertex or edge carries a runId or provenance digest.**
  - Full provenance exists only in the local `load-manifest.json`, which nothing publishes. Once several runs share a cluster, graph elements cannot be attributed to their Spark run.
  - Owner: storage-engineer.
- **M7. No vulnerability or advisory review of AWS SDK 2.59.7** (`reports/dependency-updates/phase-07-aws-sdk-v2.md:30-39`).
  - Owner: dependency-updater.
- **M8. The ResearcherMetricCache omission rests on an unimplemented table.**
  - The omission relies on the `researcher-metrics` analytics table, which has no physical name in config, no repository and no writer.
  - Its JSONL is typed JSON, while `KvItem.attributes` holds strings only.
  - So the access pattern "metrics of one researcher in one window" is not served by any adapter.
  - Owners: domain-engineer (config keys), storage-engineer (repository and loader).

### LOW (deferred with disclosure unless fixed incidentally)

- **L1. `#` in sort keys.** `#` is allowed in `Ids.token`, so distinct sort keys can collide (`Repositories.scala:162, 186, 214, 226`). Owner: domain-engineer or storage-engineer.
- **L2. RunLedger ordering.** "Newest runs" sort keys compare as strings, not time (`Repositories.scala:117-118, 149-153`). A Succeeded write with a different `startedAt` creates a second stage-index item. Owner: storage-engineer.
- **L3. Shortest-path template.** It filters windows after `shortestPath` (`NeptuneQueries.scala:166-170`), so it may miss a within-window path. Neptune support is unverified. Owner: storage-engineer, Phase 14.
- **L4. Bulk-load semantics and format.** The reload semantics are uncited (`NeptuneQueries.scala:43-47`, `STORAGE-CONTRACTS.md:105`). Config accepts format `csv` (Gremlin CSV), which would misread the openCypher files (`StorageModuleConfig.scala:160`). Owners: storage-engineer, domain-engineer.
- **L5. `$`-prefixed names.** `$`-prefixed metadata and filter names are not rejected (`VectorStores.scala:182-213`). Owners: storage-engineer, domain-engineer.
- **L6. Duplicate rejection records.** Rejection records duplicate on retry, and `VectorLoader` also records ServiceRejected for the same keys (`VectorStores.scala:399-415, 569-584`). Owner: storage-engineer.
- **L7. Tests that would pass if the code were broken.**
  - No mocked-client tests for `S3VectorsStore`, `DynamoKeyValueStore` or `S3ObjectStore`.
  - The build-filter test checks text and a copy of the predicate.
  - `LocalLoadOutputSuite` skips when L1 is absent and checks stale output.
  - Owner: test-engineer.
- **L8. Live suite writes into the real index.** `S3VectorsAwsItSuite.scala:15` writes probe vectors into `researcher-profile-v1` and never deletes them. Owner: storage-engineer; human decision for Phase 14.
- **L9. IAM.**
  - No IAM permissions are documented.
  - The `GraphStore` port bundles `submitBulkLoad` with reads (`StoragePorts.scala:173-181`).
  - `AwsClients` accepts an `http://` Neptune endpoint (line 75).
  - Owners: storage-engineer, infra-engineer, domain-engineer.
- **L10. Loader location.** `LocalLoad` and `ParquetTables` are in `src/main`, though documented as Test-classpath mains, so they ship to `services` without Spark. Owner: storage-engineer.
- **L11. `src/it` ownership.** Giving `modules/storage/src/it/**` to storage-engineer extends §1.1 item 2, which covers hadoop only; it needs ratification. Owners: factory-engineer, human.
- **L12. Fake object store details.** `FileObjectStore.putIfAbsent` writes non-atomically; `list` throws on a bad continuation token (`ObjectStores.scala:313-315`). Owner: storage-engineer.
- **L13. Code version.** `codeVersion` `1f6c6f0+phase07` names uncommitted code. L1/L2 and L3 come from different source (a formatting-only change) with the same codeVersion and loadRunId. Rerun after the commit.

### Deviations for the human gate (review 1)

1. Arrays and maps are written as JSON strings in the Neptune CSV. This is documented; the reviewer did not verify the documentation claim.
2. Items carry a provenance digest instead of the full string, and edges carry nothing (M6).
3. AFFILIATED_WITH, DERIVED_FROM and SUPPORTED_BY are absent, not fabricated (verified).
4. Analytics table names are missing from config (M8).

## Review 2 (repair 01 only)

**Verdict: APPROVE WITH NOTES**, stated by the reviewer as conditional on the canonical check exiting 0 (it exited 1; see agent-report.md).

- M1 RESOLVED: segments over 240 bytes rejected as InvalidRequest (`ObjectStores.scala:37-57`); temp-file and `createLink` commit with rollback (248-289); non-NotFound IO → `Failed` (200-204, 286). Tests `FileObjectStoreSuite.scala:126-247`.
- M2 RESOLVED: the `expiry` string on TTL items (`Repositories.scala:47`); expired items replaced only under `AttributeEquals("expiry", seen)` (77-86); writers and readers use the same injected clock (106-110); the adapter and fake match (`KeyValueStores.scala:127-128, 213-218`). Tests `DynamoRepositoriesSuite.scala:410-558`.
- M3 RESOLVED: `againstStored` compares metadata and embedding (`VectorStores.scala:142-157`); `EmbeddingMismatch` (`StoragePorts.scala:257`); the adapter uses `returnData(true)` (446). Tests `VectorStoreSuite.scala:234-330`.
- M4 RESOLVED: bounded `collectPages` (`VectorStores.scala:165-186`), fake `queryPage` (347-386), adapter `nextToken` (551-576; read only, no mocked-client test); limits recorded (`STORAGE-CONTRACTS.md:199-200`). Tests `VectorStoreSuite.scala:544-666`.
- M5 RESOLVED for the gate: no test builds a client or signs a request; all five builders apply the empty profile file and fixed profile name (`AwsClients.scala:46-91, 128-133`). That the SDK reads no shared config at runtime is inferred, not observed.
- M6 RESOLVED: lineage columns (`NeptuneCsv.scala:123-134, 271-288`; `LocalLoad.scala:127-141`); `provenanceDocuments` and the published manifest (`LocalLoad.scala:280-291, 394-419`); L4/L5 checksum listings identical.
- M7 RESOLVED as a documented review (`phase-07-aws-sdk-v2.md:32-56`); the network results are not stored.
- M8 RESOLVED: 15-way distinctness (`StorageModuleConfig.scala:274-276`), `AnalyticsRepository` conditional writes and hash-checked reads (41-118); second reload pass all Unchanged (`L4/load-manifest.json:73-77`).

New LOW notes: N1 (`LocalLoadOutputSuite.scala:147-160` does not check the hash; `LocalLoad.scala:395` stores re-serialized provenance); N2 (`VectorStores.scala:149` includes runId and textS3Uri in identity); N3 (`ObjectStores.scala:273-282` rollback after `createLink` untested, `UnsupportedOperationException` escapes, a concurrent-writer case); N4 (the fake rejects prefix keys S3 accepts; `.tmp-` hidden by `list`, `ObjectStores.scala:233-258, 364`); N5 (expiry replacement uses the writer's clock, `Repositories.scala:77-80`); N6 (process). Deferred L1–L13 unchanged.
