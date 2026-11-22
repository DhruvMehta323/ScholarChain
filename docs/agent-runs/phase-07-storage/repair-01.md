# Phase 07 — Repair 01

Trigger: review 1 verdict REQUEST_CHANGES (`review-report.md`). Repair policy (work order §0.9): fix HIGH and MEDIUM findings concerning correctness, security, data loss or idempotency; defer LOW with disclosure. Round 1 of at most 2.

None of these repairs changes a frozen contract, the identity rules, ownership or anything constitutional, and none needs AWS. The core changes are additive.

## Routed, by owner (in execution order)

### dependency-updater
- **M7:** run an advisory check for `software.amazon.awssdk` 2.59.7 (the artifacts in use) against a public advisory database (OSV API, read-only), and record the observed result in `reports/dependency-updates/phase-07-aws-sdk-v2.md`. No version change unless an advisory requires one; if one does, STOP and report.

### domain-engineer (additive only)
- **M8 (config):** add physical DynamoDB table-name keys for the analytics item tables the loader writes (researcher-metrics, coauthor-edge-works, bridges, coreness, temporal-changes, paths, path-estimates, window-summary, identity-clusters, mention-researcher) to `StorageModuleConfig`, with placeholder defaults and the existing validation (valid and distinct names).
- **M3 (only if storage-engineer needs it):** a `VectorRejection` case for "same key and text, different embedding or model version", if the existing `KeyCollision` cannot express it.

### storage-engineer
- **M1:** `ObjectStores.validKey` and `FileObjectStore`: a key the fake cannot store must be rejected up front as a non-retryable `InvalidRequest` (or the fake must store it). A failed write must leave no partial data file or sidecar. An OS I/O failure that is not transient must not map to retryable `Unavailable`.
- **M2:** an existing EntityLinkCache or QuerySession item whose `expiresAtEpochSeconds` has passed is replaceable, under a DynamoDB condition on the stored expiry (no unconditional overwrite). The fake behaves identically.
- **M3:** vector idempotency compares the full stored record (text hash, embedding model ID and version, metadata) and the embedding. Identical → idempotent no-op. Same key, same text, different embedding or model version → a defined, recorded outcome, never a silent `Accepted` with nothing written. Choose replace-under-condition or reject, and document it.
- **M4:** `query` paginates with `nextToken` up to `topK`, respecting 100 results per page (or caps `topK` with a documented rejection). The fake honors the same contract. Record the two further limits from the AWS page (2,500 vectors inserted and deleted per second per index; 140 MBps) in `STORAGE-CONTRACTS.md` §5.1.
- **M5:** make `AwsClients` hermetic. Every client builder must use an explicitly supplied, empty profile file and a fixed profile name (no shared AWS config or credentials file read) plus explicit region and credentials. Document it.
- **M6:** every Neptune vertex and edge row carries `loadRunId:String`, `sparkRunId:String` and `provenanceDigest:String` (the digest of the row provenance, or of the table `_provenance.json` for tables without row provenance). `load-manifest.json` maps digests to full provenance. `ObjectRunManifest` publication of the load manifest is part of the load procedure through the ObjectStore port (fake locally).
- **M8 (adapter):** a repository that writes the analytics items into their configured tables through `KeyValueStore`, with conditional idempotent writes (identical reload → Unchanged; different payload → Conflict). Typed JSON values are stored losslessly (for example, the canonical JSON item as one string attribute alongside the key attributes); the mapping is documented. The access pattern "metrics of one researcher in one window" is served and documented.
- After the changes, rerun the local loader twice into new roots (`loads/L4`, `loads/L5`) and compare.

### test-engineer
- **M5:** `AwsOffByDefaultSuite` must not build any SDK client or sign any request during `sbt check`. Keep the throwing-provider test of `AwsClients` construction and the fakes. Drop the client-building and signing tests, or replace them with a check that the builders are configured hermetically without building a client. State what is no longer covered.
- Feature tests for M1, M2, M3, M4, M6 and M8 (each failing on the pre-repair behavior), and an update to `FileObjectStoreSuite.scala:126` to the repaired contract.

## Deferred (LOW, with disclosure)

L1–L13, as listed in `review-report.md`. The human should see in particular:
- L4: config accepts format `csv`, which is Gremlin CSV.
- L8: the live vector suite writes into the real index.
- L10: `LocalLoad` and `ParquetTables` ship in `src/main`.
- L11: ratification of the `src/it` ownership reading.

## Outcome

- **Done:**
  - M7: OSV found 0 vulnerabilities across 35 SDK artifacts.
  - M8 config and `EmbeddingMismatch` (domain-engineer).
  - M1–M6 and the M8 adapter (storage-engineer); loader runs L4 = L5 identical.
  - Tests updated and added (test-engineer): core 825, storage 213 in its targeted runs.
- **Review 2:** APPROVE WITH NOTES (M1–M8 resolved; new LOW notes N1–N6), conditional on CHECK passing.
- **Canonical CHECK: FAILED.** 1,790 passed, 1 failed (corrected; first written as 2,072 by an orchestrator arithmetic error), `exit=1`. The failure is the untouched Phase 06 `IdentitySuite` with `SparkOutOfMemoryError: [UNABLE_TO_ACQUIRE_MEMORY]`.
- **Work order §7 STOP point "CHECK fails after a repair" reached.** The human decided: rerun once, unchanged; a build-only round 2 only if the same OOM recurs.
- **CHECK run 2 (unchanged tree): 1,791 passed, 0 failed, `exit=0`.** The OOM did not reproduce. Round 2 was not needed and not run.
- Final state: Phase 07 complete; waiting for the human gate.
