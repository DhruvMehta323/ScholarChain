# Phase 08 — code-reviewer report

Stored by the orchestrator from code-reviewer's returned message (code-reviewer has no write tools). Findings summarized faithfully; file:line references are the reviewer's.

## Review 1 — full Phase 08 diff against HEAD 0d1611f

**Verdict: REQUEST_CHANGES.** 0 HIGH, 3 MEDIUM (M1 correctness, M2 security, M3 idempotency), 9 LOW.

### MEDIUM

- **M1 (correctness). collaboration-path-v1 vectors can never become a `SemanticContextItem`.** `modules/storage/src/main/scala/scholarchain/storage/EmbeddingWorker.scala:501-504` stores entity IDs over 128 chars only as `entityIdHash`; `modules/rag/src/main/scala/scholarchain/rag/SemanticContext.scala:44` requires `entityId` and skips the hit (`entity-id-not-in-metadata`). Real data: path entity IDs are ~172 chars; all 63 path_summary vectors in `L1/vectors.jsonl` carry `entityIdHash`. `RetrievalSmokeSuite.scala:241-255` enshrines the skip; `RetrievalSmoke.scala:174` uses `.forall` on a missing entityId so the smoke does not catch it. Not disclosed. Owners: rag-engineer, storage-engineer; test-engineer for tests.
- **M2 (security). Bedrock SDK client not hermetic.** `modules/rag/src/main/scala/scholarchain/rag/BedrockEmbedder.scala:29-35` builds `BedrockRuntimeClient` with region and HTTP client only — no injected `AwsCredentialsProvider`, no empty-profile override — unlike the Phase 07 rules in `AwsClients.scala:19-36,128-133`. A `liveProfile = true` run would resolve the default chain (`~/.aws/credentials`, `AWS_PROFILE`, IMDS). Not reachable in Phase 08. Owner: rag-engineer.
- **M3 (idempotency under concurrency). Same key can silently change text with multiple workers.** Check-then-write in `EmbeddingWorker.scala:377-393` / `:432-434`; S3 Vectors GetVectors-then-PutVectors without a conditional put (`VectorStores.scala:412-418,483-535`). Two workers draining jobs for key K with hashes H1 and H2 both see None and both write; last writer wins while the other job's ledger says succeeded. Disclosed by B1 as "run one worker per index" but not enforced, and Phase 15 scales this worker. Owner: storage-engineer.

### LOW (deferred with disclosure under §0.9 unless noted)

- L1: `ProducerReport.documentsSeen` undercounts on non-duplicate rejections (`EmbeddingQueue.scala:170` vs the invariant at `:67-69`). storage-engineer.
- L2: `EmbeddingAwsOffSuite.scala:132-135` tests a copy of the filter, not build.sbt; `assume(...)` at `:38,:102,:118` turns a missing file into a skip. test-engineer.
- L3: smoke passes by construction and checks self-retrieval, not neighbours (`RetrievalSmoke.scala:89`; queries contain the entity's ID token). rag-engineer; human gate note.
- L4: excerpts carry exact counts from Phase 06 text; "counts never from similarity" is enforced only by documentation. Phase 09 claim verification obligation. rag-engineer.
- L5: one bad text dead-letters its whole embed batch (`EmbeddingWorker.scala:408-416`); a Bedrock throttle on text n discards n-1 embeddings (`BedrockEmbedder.scala:88-91`). storage-engineer, rag-engineer.
- L6: no provider/model consistency check; Voyage with default settings would send `amazon.titan-embed-text-v2:0` (`VoyageEmbedder.scala:167-172`). Fails loudly. rag-engineer, domain-engineer.
- L7: Voyage key via env var must be injected from Secrets Manager (Phase 12, infra-engineer, human ack); the env-var *name* is overridable (`EmbeddingConfig.scala:24,137-139`), so an override could send another env value as a Bearer token. domain-engineer, infra-engineer.
- L8: harness invariants ignore render skips (`LocalEmbeddingPipeline.scala:391-424`). test-engineer.
- L9: `--code-version 0d1611f+phase08` is a hand label, not the tree state. Orchestrator.

### Checks in §6 order (reviewer)

1. Idempotency and key collisions: PASS single-writer; FAIL under concurrency (M3).
2. Queue and dead-letter: PASS.
3. Secrets: PASS with L7.
4. Long text in metadata: PASS (0 `"text"`/`"excerpt"` keys in L1 jsonl).
5. Invented numbers: PASS (numberCheckFailures 0; summary text verbatim; paper templates have no numeric literal).
6. Model-version integrity: PASS.
7. AWS off by default: PASS with M2.
8. Counts/paths/bridges never from similarity: PASS in code; obligation carried (L4).
9. Tests that would pass if broken: L2, L3, and M1's enshrining test.
10. Claims: L1 counts, smoke 25/25, test-count arithmetic (269; 270 `test(` incl. the filtered *AwsItSuite) and absence of `modules/services/src/main` verified by the reviewer. UNVERIFIED by the reviewer (no stored logs): CHECK totals and elapsed times, the L1 = L2 diff and checksum (spot-checked `L2/counts.json` lines 1-12), the spark-sql classpath scoping, OSV/Maven results, the hadoop timeout.

Boundaries: PASS (rag and storage core-only; rag/services imports only in services test files). Ownership: PASS by path; actual authorship unverified.

Disclosed deviations: (1) services test harness ACCEPT; (2) no deletes in live suites ACCEPT (correct precedence); (3) no Bedrock in the live queue suite ACCEPT with gap (a Bedrock live suite is needed before Phase 15); (4) paper_abstract title-level text ACCEPT (document it); (5) embedding config in A1 ACCEPT; (6) self-retrieval smoke ACCEPT as a wiring smoke only — human gate decision.

## Review 2 — reread of repairs 01, 02 and 03 together

**Verdict: APPROVE WITH NOTES.** M1, M2 and M3 RESOLVED; 0 HIGH, 0 MEDIUM new findings; 4 LOW notes (N1–N4).

- **M1 RESOLVED.** `SemanticContext.scala:76-112` (`entityIdOf`: metadata entityId, else recovery from the vector key accepted only if type/window/chunkOrdinal match metadata, `Hashing.textHash(entity) == entityIdHash`, and `EmbeddingDocument.idFor(...) == key`); `RetrievalSmoke.scala:172-175` requires a confirmed entity (no `.forall`). Real data read by the reviewer: `L3/vectors.jsonl` 63 `entityIdHash` (all path_summary), `L3/smoke.json` 5 path_summary queries all passed, 0 failed, smoke 25/25. Reverting fails `RetrievalSmokeSuite.scala:286-318, 425-431, 433-457` and `LocalEmbeddingRunSuite.scala:381-460`.
- **M2 RESOLVED.** `BedrockEmbedder.scala:38-48, 73-90` (injected provider, no default; empty CONFIGURATION profile file + fixed profile name, identical to storage `AwsClients.scala:113-133`); `EmbedderFactory.scala:20-30`. No default/profile/instance credentials provider in rag or services main. Reverting fails `BedrockEmbedderSuite.scala:185-207` (or compilation). Narrowed source test (`:99-130`) is **acceptable** (only the exact `AwsCredentialsProvider` import line is exempt). Residual: `assume` at `:107`; no scan for the shared-credentials-file path literal or `AWS_PROFILE`; `AWS_ENDPOINT_URL*` not suppressed (disclosed).
- **M3 RESOLVED** (single conditional-put authority). `EmbeddingClaims.scala:37-52` (`PutCondition.NotExists`, identity excludes runId/jobId); `EmbeddingWorker.scala:440-454` claims every embedded document before the write at `:457-479`; `StoreFailed` → bounded retry, no unclaimed write path. The DynamoDB adapter returns the old item on condition failure (`KeyValueStores.scala:208-212, 223-224`), so Identical vs KeyCollision is distinguishable in production. Reverting fails `EmbeddingClaimsSuite.scala:174-196, 198-224, 245-257, 325-383`. Residual (disclosed): claims are permanent; a non-deterministic embedder can dead-letter `embedding-mismatch`; not run against DynamoDB.

New LOW notes:
- **N1:** `EmbeddingQueueAwsItSuite.scala:95-97,103` uses `indexOverride = Some(testIndex)` but the default (production) claims table, contrary to `STORAGE-CONTRACTS.md:327`; cleanup list (`:274`) omits claims; a second `AwsClients` is built. Not run. storage-engineer; Phase 12 test claims table (infra-engineer).
- **N2 (mechanism UNVERIFIED):** the per-test 120 s timeout in `AdversarialSuite.scala:27-43` takes effect only if munit reads `munitTimeout` after the transform sets the flag; CHECK run 5 passed but the single-test duration was not recorded (suite 36.095 s). test-engineer, if proof is wanted.
- **N3:** `LocalEmbeddingRunSuite.scala:462-470` ("a second pass never reclaims") does not assert claim puts; behaviour is covered by `EmbeddingClaimsSuite.scala:274-290`. test-engineer.
- **N4:** claims table name not checked against storage table names; regex duplicated (`EmbeddingConfig.scala:108,175-177`). domain-engineer.

Scope: repair 02 consistent (`build.sbt:33-40, 68-69` only); repair 03 consistent (`build.sbt:122-123` inside `lazy val hadoop`; `AdversarialSuite.scala:20-43, 345-350` — only the named test gets 120 s, others keep 30 s, assertions unchanged); repair 01 within the R1a–R1d plan; boundaries and ownership pass (authorship unverified).

UNVERIFIED by the reviewer: all sbt results and the marker; L3 = L4 diff/checksum (read L3 only); that build.sbt and AdversarialSuite.scala differ from HEAD only in the described hunks (no git access); the sbt ForkedTestGroup default and munit javap claims; whether `Tags.Test` serialized module runs; the N2 mechanism. Note: CHECK wall time grew (916–2,269 s).
