# Phase 08 — repair 01

Trigger: code-reviewer review 1, REQUEST_CHANGES (0 HIGH, 3 MEDIUM, 9 LOW). See `review-report.md`.
Policy (work order §0.9): fix HIGH and MEDIUM findings on correctness, security, data loss or idempotency; defer LOW with disclosure. Round 1 of at most 2.

## Scope, grouped by owner (routed one at a time)

### R1a — domain-engineer (`modules/core/**` except `src/test/**`)
- For M3: add one additive config entry to the Phase 08 `embedding.*` contract (new this phase, not frozen): the key-claim table name (e.g. `embedding.claims.table-name`, default `scholarchain-embedding-key-claims`), validated like other table names. No change to frozen Phase 06/07 records or `StorageModuleConfig`.

### R1b — storage-engineer (`modules/storage/**` except `src/test/**`)
- M3: enforce "the same vector key can never silently refer to different text" under concurrent workers. Before writing a vector, claim the key with a conditional put through the existing core `KeyValueStore` port (item keyed by vector key; value textHash + model ID/version + dimension + textS3Uri). Identical claim → Unchanged (proceed/idempotent); different claim → key-collision (dead-letter, never write). Only the claim winner writes the vector. Document in STORAGE-CONTRACTS.md §12, including the new DynamoDB table needed in Phase 12 (infra-engineer) and the remaining limits.
- M1 (storage side): document in STORAGE-CONTRACTS.md that the full entity ID is recoverable from the vector key (`EmbeddingDocument.idFor` format) when metadata carries only `entityIdHash`. No change to metadata limits (frozen core `VectorRecord` bounds).

### R1c — rag-engineer (`modules/rag/**` except `src/test/**`)
- M1: `SemanticContext.build` must produce an item for hits whose metadata carries only `entityIdHash`, recovering entityId (and type, window, ordinal) from the vector key and cross-checking it against `entityIdHash` and the metadata; a hit whose key cannot be parsed or does not match is skipped with a fixed reason. `RetrievalSmoke.run` must fail, not pass, when an expected entity cannot be confirmed (no `.forall` on a missing value).
- M2: `SdkBedrockInvoker` / `EmbedderFactory.bedrock` must take an injected `AwsCredentialsProvider` and build the client hermetically (no default chain; override configuration so no shared profile or credentials file is read), mirroring storage `AwsClients`. Still lazy; still Left unless `liveProfile = true`.

### R1d — test-engineer (`modules/*/src/test/**`)
- Replace the test that enshrines the M1 skip (`RetrievalSmokeSuite.scala:241-255`) with tests that a path-length (> 128 chars) entity ID hit yields a valid `SemanticContextItem` with the full entity ID, that a mismatching hash or unparseable key is skipped, and that the smoke fails when an expected entity cannot be confirmed.
- M2 tests: the Bedrock client requires an injected provider; with `liveProfile = false` nothing is constructed; source/config checks for the hermetic override.
- M3 tests: two workers (or two interleaved jobs) for the same key with different hashes → exactly one vector written, the other dead-lettered as key-collision; identical claims from a new run → no-op; claim store failure → retry, never an unclaimed write.
- Harness: create the claim table in the services harness wiring if the worker requires it.

## Deferred LOW (disclosed; for the human gate)
L1 (documentsSeen undercount), L2 (AwsOff test copies the filter; `assume` skips), L3 (self-retrieval smoke), L4 (excerpts carry counts — Phase 09 claim-verification obligation), L5 (batch-level dead-letter / Bedrock partial progress), L6 (no provider/model consistency check), L7 (Voyage key must come from Secrets Manager via Terraform; overridable env-var name), L8 (harness ignores render skips), L9 (hand-typed code-version label).

## After repair
CHECK (canonical command); rerun the local run twice (production source changes) and diff; code-reviewer rereads only the repair.

## Outcome
(Filled in below as each step completes.)

### R1a — domain-engineer: COMPLETE
- Modified `modules/core/src/main/scala/scholarchain/core/EmbeddingConfig.scala` and `modules/core/src/main/resources/scholarchain-embedding.v1.conf`: `EmbeddingConfigKeys.ClaimsTableName` = `embedding.claims.table-name` (env `SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME`), `EmbeddingSettings.claimsTableName` (last parameter, default `scholarchain-embedding-key-claims`), validated with `^[A-Za-z0-9_.\-]{3,255}$` (duplicated from the private StorageConfigLoader regex; drift risk). `resolve` fills the default when an explicit file layer lacks the key.
- Agent-observed: `sbt "core/scalafmt; core/compile; core/Test/compile; core/testOnly *Embedding*"` → first run 1 failure (existing resolve-layers test, fixed by the default fill-in, no test edited), rerun `Passed: Total 40, Failed 0`. One guard false positive on a command containing `.key` (nothing executed).

### R1b — storage-engineer: BLOCKED-ON test-engineer (expected; tests are R1d)
- Created `modules/storage/src/main/scala/scholarchain/storage/EmbeddingClaims.scala` (`EmbeddingKeyClaims`, `ClaimOutcome` = Won | Identical | KeyCollision | EmbeddingMismatch | StoreFailed). Modified `EmbeddingWorker.scala` (required `claims: KeyValueStore` constructor input before `ledger`; `EmbeddingWorkerConfig.claimsTable` with default; `fromSettings` passes `claimsTableName`; new retry code `claim-store-failed`), `src/it/.../EmbeddingQueueAwsItSuite.scala` (passes the claims store; still NOT RUN), `STORAGE-CONTRACTS.md` (§12.3 note, new §12.4a claims, new §12.4b entity-ID recovery from the vector key, §12.9 limits).
- Semantics (agent-reported): per document `VectorStore.get` fast path → text fetch → embed → claim (`PutCondition.NotExists` on pk = vector key; identity = textHash, model ID, version, dimension, textS3Uri, indexName; runId/jobId provenance only) → `putBatch`. Won/Identical → write (identical existing vector is an idempotent Accepted); different hash → `key-collision` DLQ; same hash, other model/pointer/index → `embedding-mismatch` DLQ; store failure → retried as `claim-store-failed` (bounded by max-attempts), never an unclaimed write.
- Agent-observed: `storage/compile` `[success]`; `storage/Test/compile` fails with 3 errors at test-engineer call sites (`EmbeddingFx.scala:243`, `EmbeddingWorkerSuite.scala:290`, `:572`); the services harness needs the same change. Phase 12 needs the DynamoDB claims table and IAM (infra-engineer, human-approved).
- Agent risks (R1b): a claim is permanent (a re-rendered text under the same key stays `key-collision` until a human removes the claim); an `indexOverride` run needs its own claims table; claims not run against DynamoDB.

### R1c — rag-engineer: COMPLETE (tests pending R1d)
- Modified under `modules/rag/`: `SemanticContext.scala` (`entityIdOf(hit)`: metadata `entityId`, else recover from the vector key `<type>:<entityId>:<window>:<ordinal>` and accept only if type/window/chunkOrdinal match metadata, `Hashing.textHash(entityId) == entityIdHash`, and `EmbeddingDocument.idFor(...) == vectorKey`; new skip reasons `vector-key-unparseable`, `vector-key-metadata-mismatch`), `RetrievalSmoke.scala` (top-1 requires `entityIdOf(h).contains(expectedEntityId)`; the `.forall` is removed), `BedrockEmbedder.scala` (`SdkBedrockInvoker(region, credentials)` with injected provider and `hermetic` override: empty profile file, fixed profile name), `EmbedderFactory.scala` (`bedrock(..., credentials, invokerFor)`; still Left unless `liveProfile`), `RAG-EMBEDDING-CONTRACTS.md`.
- Agent-observed: `rag/compile` `[success]`; `rag/Test/compile` 8 errors, all in `BedrockEmbedderSuite.scala` (signature change; test-engineer's). One guard block on a comment containing a credentials-file path (nothing executed; comment reworded).
- Agent risk: `AWS_ENDPOINT_URL*` environment variables are not suppressed (same limitation as storage).

### R1d — test-engineer: COMPLETE
- Created `modules/storage/src/test/scala/scholarchain/storage/EmbeddingClaimsSuite.scala` (22). Modified: core `EmbeddingConfigContractSuite` (+9, total 23); storage `EmbeddingFx` (claims store in the Rig, `FlakyClaimStore`, `secondWorker`), `EmbeddingWorkerSuite` (compile fixes); rag `BedrockEmbedderSuite` (new signatures with a throwing credentials-provider stub, +4, total 18; the "SDK only in BedrockEmbedder.scala" source test now allows exactly the `AwsCredentialsProvider` import in `EmbedderFactory.scala` — reviewer to confirm), `RetrievalSmokeSuite` (removed the test enshrining the M1 skip, +20, total 40); services `LocalEmbeddingPipeline` (claims store wiring), `LocalEmbeddingRunSuite` (+4, total 28). Net +58 −1.
- Agent-observed final targeted runs: core `*Embedding*` `Passed: Total 49`; storage `*Embedding* *Vector* *Phase07CarryOver*` `Passed: Total 162`; rag `Passed: Total 140`; services `Passed: Total 28`. Earlier failures were the agent's own test bugs (fixed). Two shell commands were refused before execution: a guard false positive on a Scala identifier, and an auto-mode classifier denial of a string split that would have evaded the guard (dropped). Disclosed gap: the M2 source scan does not search for the shared-credentials-file path literal or `AWS_PROFILE`.

### Local run after repair (orchestrator)
- Same harness command with `--code-version 0d1611f+phase08r1`, out dirs `L3` and `L4`, each on a fresh sbt server (the orchestrator stopped the local sbt server between runs to avoid the earlier in-process Spark hang). Both: `documents=9958 pass1.vectorsWritten=9958 pass2.skippedUnchanged=9958 deadLettered=0 smoke=25/25`, `[success] elapsed time: 21 s`.
- **L3 = L4:** `diff -rq` empty; checksum-listing SHA-256 `be10564b163a43a17145e91bfa73b7ce2e7e7f7beab18be7ca548b5b97ebffcf` for both; 19,921 files.
- L1 vs L3: `documents.jsonl`, `smoke.json` and `objects/` identical; `vectors.jsonl`, `counts.json` and `ledger.jsonl` differ, as expected, because the code-version label changed and the embedding run IDs derive from it (L3 run IDs `run:8d62a150…88ec2`, `run:1883fb40…48ba2`). L3 dead-lettered 0, invariant failures `[]`.

### CHECK after repair 01 (canonical command) — STATUS: FAILED
- testFull: core 874, entityResolution 293, hadoop 192, storage 323, rag 140, services 28 passed; **sparkAnalytics `Failed: Total 268, Failed 2, Passed 266`**:
  - `scholarchain.spark.IdentitySuite` "M1: the reason combines with rejected_or_conflict_pair and with two_orcids, in that order" — `java.util.concurrent.TimeoutException: test timed out after 30 seconds` (46.411 s).
  - `scholarchain.spark.IdentitySuite` "M2: a REJECTED and a CONFLICT pair inside one base identity are ignored, listed and counted" — same timeout (34.086 s).
- **2,116 passed, 2 failed.** `[error] elapsed time: 1651 s`; marker `exit=1`. 0 `AwsItSuite`.
- Observations: `git status --short modules/spark modules/hadoop modules/entity-resolution` empty (untouched this phase); the same suite passed in every earlier Phase 08 CHECK; load average after the run 5.38 / 7.10 / 7.10; two idle sbt server JVMs (pids 59626, 59856, both started ~28 min before, i.e. around the start of this CHECK) were present afterwards — a second server may have competed for CPU (not established). The run took 1,651 s vs 1,234–1,344 s for earlier passing runs.
- **STOP (work order §7: "CHECK fails after a repair").** Not rerun, not repaired; waiting for the human.

### Human decision at the STOP
- "Rerun CHECK once": stop both leftover sbt servers, rerun unchanged, record both runs; if it passes, code-reviewer rereads repair 01; if the same timeouts recur, STOP again.

### CHECK after repair 01, run 2 (human-authorized rerun, unchanged tree) — STATUS: FAILED
- Before the run: the orchestrator stopped the two idle sbt servers (pids 59626, 59856). Load average was 27.33 / 11.18 / 5.62 (a short spike; no heavy process was visible in `ps`).
- testFull: core 874, entityResolution 293, storage 323, rag 140, services 28 passed; **hadoop `Failed: Total 192, Failed 1`** — `scholarchain.hadoop.AdversarialSuite` "a giant team of 1,000 gives exactly 499,500 valid distinct pairs, none truncated" `TimeoutException: test timed out after 30 seconds` (35.756 s); **sparkAnalytics `Failed: Total 268, Failed 1`** — `scholarchain.spark.IdentitySuite` "two clusters sharing one ORCID both keep their DBLP IDs and both point at the ORCID" `SparkOutOfMemoryError: [UNABLE_TO_ACQUIRE_MEMORY] Unable to acquire 262144 bytes of memory, got 0` (21.827 s).
- **2,116 passed, 2 failed.** `[error] elapsed time: 1030 s`; marker `exit=1`. 0 `AwsItSuite`. Load average after the run 4.08 / 5.09 / 5.39.
- Pattern across Phase 07–08 (observed): every failure is a resource failure (30 s munit timeouts, Spark local OOM) in modules untouched in this phase (hadoop, spark), and every failing test passed in other runs. Phase 08 added three more forked test JVMs worth of work (new storage, rag and services suites). Whether parallel forked test JVMs contend for CPU and memory is **not established**.
- **STOP again** (human instruction: "if the same timeouts recur, STOP again"). Nothing rerun or repaired after this run.
