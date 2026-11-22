# Phase 07 — Human gate

Phase: 07, storage adapters and loading
Date: 2026-11-21
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with the decisions, disclosures and remaining risks below.

## 1. What I reviewed

- `prompt.md` (written by me, saved verbatim) and `repair-01.md`.
- `agent-report.md`: preconditions, A0, A1, the A0 follow-up, B, C, repair 01 (R1a–R1d), both CHECK runs after the repair, and the gate summary.
- `review-report.md`: review 1 (REQUEST_CHANGES: 0 HIGH, 8 MEDIUM, 13 LOW) and review 2 of repair 01 (APPROVE WITH NOTES, M1–M8 resolved, conditional on CHECK passing; it passed on run 2).
- `modules/storage/STORAGE-CONTRACTS.md` and `reports/dependency-updates/phase-07-aws-sdk-v2.md`.
- My own checks (section 7).

## 2. What was accepted

- **Build (dependency-updater, build-engineer):** AWS SDK for Java v2 at 2.59.7, pinned in `project/Versions.scala`, with the modules s3, dynamodb, s3vectors, neptunedata, sqs and sts, plus `url-connection-client` as the single HTTP client (apache and netty clients excluded). `storage` depends on `core` only, with Spark SQL as Provided for reading Parquet as files. A test filter skips `*AwsItSuite` unless `SCHOLARCHAIN_AWS_IT=1`.
- **Core (domain-engineer):** `VectorRecord`, `RunLedgerEntry`, `ArtifactRegistryEntry`, `QuerySessionRecord`, `EntityLinkCacheEntry`, the enums `RunStatus` and `QuerySessionStatus`, `VectorIndexes`; the ports `ObjectStore`, `GraphStore` (six allowlisted templates, query bounds, bulk-load), `KeyValueStore` (conditional puts), `VectorStore` and `WorkQueue`, all AWS-free and returning `Either[StorageError, A]`; `StorageModuleConfig` with 37 keys (including the 10 analytics table names added in repair 01); five frozen JSON examples. The existing `EvidenceBundle` is reused as the storage form.
- **Storage (storage-engineer), each adapter with a fake:**
  - Ordinary S3 (`FileObjectStore` / `S3ObjectStore`), with versioned keys and run manifests.
  - Neptune: openCypher bulk-load CSV writer, bulk-load request builder, and `TemplateGraphStore` with parameter-only templates and enforced depth, result and timeout bounds.
  - DynamoDB: RunLedger, ArtifactRegistry, EntityLinkCache, QuerySession and EvidenceBundle repositories, plus `AnalyticsRepository` for the ten analytics tables. Conditional writes give Written / Unchanged / Conflict, never an overwrite. TTL only on QuerySession and EntityLinkCache. Evidence bundles spill to S3 above 300,000 bytes.
  - S3 Vectors: batching at 500 vectors / 20 MiB, compact metadata only, key-collision and embedding-mismatch rejection, paginated queries up to topK, bounded retry.
  - SQS work queue adapter and fake.
  - OpenSearch: not implemented (optional tier).
- **Local loader runs** on sparkS1r2 (run `run:4d8735e6…26ceaa`). After repair 01, runs L4 and L5 are identical (`diff -r` empty; checksum-listing SHA-256 `edff0cc9…93be` for both), 32 files each:
  - Vertices: Researcher 3,893; ExternalIdentity 1,423; Work 1,000; Topic 206; Institution 250; Venue 155; Community 1,755; Component 1,735.
  - Edges: AUTHORED 3,928; COAUTHORED_WITH 15,107; RESOLVES_TO 1,423; HAS_TOPIC 297; PUBLISHED_IN 1,000; MEMBER_OF 6,937; IN_COMPONENT 6,937; COMMUNITY_LINK 20.
  - DynamoDB items: 63,121 in total across the ten analytics tables; a second reload gave 63,121 Unchanged and 0 conflicts.
  - Invariants: 0 dangling endpoints, 0 ID mismatches against the Spark IDs, 0 duplicates, 0 label mismatches. All 15,107 COAUTHORED_WITH rows validate as `CoauthorEdgeV2` and all 6,937 metrics rows as `ResearcherMetricsV2`; the evidence trace has 0 mismatches. Every Neptune row carries `loadRunId`, `sparkRunId` and `provenanceDigest`.
- **Tests:** 1,513 → **1,791 passed, 0 failed** (+278), marker `exit=0`. Live AWS suites: 0 runs in every CHECK output.

## 3. Decisions on deviations

1. **Neptune array and map properties as JSON strings** (for example `representativeWorkIds:String`): accepted. The Neptune openCypher documentation advises against array properties and documents no array syntax for the CSV format. Readers parse the strings.
2. **Provenance digests:** accepted. Neptune rows and DynamoDB items carry a provenance digest and run IDs; the full provenance lives once in `load-manifest.json`. This keeps items small.
3. **AFFILIATED_WITH, DERIVED_FROM, SUPPORTED_BY absent:** accepted and disclosed. Phase 06 does not produce them (affiliations are not persisted upstream), so Institution vertices currently have no incident edge. Nothing was fabricated.
4. **240-byte key-segment limit applied to the S3 adapter too:** accepted as a documented tightening (S3 allows longer keys).
5. **Live suites in `modules/storage/src/it`:** this path is not assigned in `docs/agents.md` (only `modules/hadoop/src/it/**` is). Not ratified here; it goes into one combined factory session before Phase 12, together with the pending CLAUDE.md §7 amendment and the hadoop → entityResolution boundary wording. The suites stay disabled and unrun until then.

## 4. Deferred notes

Review 1 L1–L13 and review 2 N1–N6 are deferred and listed in agent-report.md. Three are carried into the Phase 08 work order for storage-engineer, because they matter before any live load:
- L4: the Neptune format config accepts `csv`; it must accept only the openCypher format.
- L8: the live vector suite writes into a real index; it must use a dedicated test index and delete only its own vectors.
- N2: vector identity includes the run ID and text URI, so an identical reload from a new run is rejected; it must be an idempotent no-op.

Also deferred: L7 (no mocked-client tests for the real adapters), L10 (`LocalLoad` and `ParquetTables` live in `src/main`), and N1 (one digest test does not recompute the hash).

## 5. Run history (disclosed)

- After repair 01, CHECK run 1 had 1,790 passed and 1 failed: a `SparkOutOfMemoryError` (UNABLE_TO_ACQUIRE_MEMORY) in the unchanged Phase 06 `IdentitySuite`, while other heavy processes were running on the machine. I authorized one unchanged rerun: run 2 had **1,791 passed, 0 failed**. The build-only round 2 was not needed. The cause is not established, and it may recur under load.
- The orchestrator first recorded run 1 as "2,072 passed" (it added the contractTest lines into the total). It corrected the files and disclosed the correction.
- After its report, the original storage-engineer agent ran the canonical command twice in the background, against rule 0.3. The orchestrator stopped it and used neither result.
- Several agents used `cd` prefixes, against the instruction; test-engineer ran scalafmt over main sources (it reports no main file changed); two guard false positives hit the orchestrator's own report text. None changed any result.

## 6. Security and dependencies

- An OSV batch query found 0 vulnerabilities across 35 AWS SDK artifacts at 2.59.7, and GitHub advisories returned none. The raw responses were not stored. Version 2.59.7 was published on 2026-11-21 (little soak time).
- Transitive third-party libraries (Netty, Jackson, slf4j and others) were not checked. That is scheduled for the Phase 11–12 security scan.
- No test builds an AWS SDK client. `AwsClients` uses an empty profile file and explicit credentials and region; that the SDK reads no shared configuration at runtime is inferred, not observed.

## 7. My own checks

- [x] `.claude/.last-test-run` reads `exit=0`.
- [x] I read the `COAUTHORED_WITH.csv` header in L4 (ends with `loadRunId:String,sparkRunId:String,provenanceDigest:String`).
- [x] I spot-checked one query template: parameters only, no string-built Cypher.
- [x] I spot-checked that vector metadata holds no long text (STORAGE-CONTRACTS.md §5).
- [x] `git status` shows no `target/` or `datasets/raw/` files tracked.

## 8. Disclosures

- Claude Code 2.1.323 in accept-edits mode on a second Claude Pro account, monitored through Remote Control; orchestrator model Opus; subagent models as pinned in `.claude/agents/`.
- No AWS call was made. The live suites are written and NOT RUN; Neptune's acceptance of the CSV and the Cypher templates is unverified until Phase 14.
- A separate download task (Phase 15a, in its own worktree) ran on the same machine during this phase and added to the machine load around the CHECK run 1 OOM.

## 9. Manual changes

None to repository source. I wrote the decisions in this file.

## 10. Remaining risk

Live AWS behavior untested until Phase 14 (including S3 Vectors float re-encoding on reload and Neptune template acceptance); service limits taken from documentation; read-then-write collision checks on S3 Vectors (no conditional put); the unowned `src/it` path awaits ratification; a possible OOM recurrence under load.

Accepted commit: see `commit.txt`.