# Phase 08 — Embeddings and the S3 Vectors ingestion path: agent report

Assembled by the orchestrator. Agent reports are quoted or summarized from their returned messages. Observed commands only.

## 1. Preconditions (orchestrator, read-only)

- `git status --short`: `?? docs/agent-runs/phase-08-embeddings-vectors/` only (this phase folder, which held only `prompt.md`). No tracked change.
- HEAD `0d1611f` ("phase-07: record accepted commit hash") is the child of `d423d53`, which equals `docs/agent-runs/phase-07-storage/commit.txt` (`d423d530ed5153770710d9610b55874de3e32f72`). HEAD descends from it.
- Baseline canonical command (`sbt check; echo "exit=$?" > .claude/.last-test-run`, run from the repo root after a separate `cd`): testFull core 825, entityResolution 293, hadoop 192, storage 213, sparkAnalytics 268, rag 0, services 0; Failed 0. **Baseline total 1,791 passed, 0 failed** (equals Phase 07's final total). contractTest passed (its lines are repeats, not added). `[success] elapsed time: 1191 s`; marker `exit=0`. 0 occurrences of `AwsItSuite` in the output.
- sparkS1r2: `modules/spark/target/runs/sparkS1r2/`, run `run:4d8735e6be30cabf2ded3d31e41316a10b34aa70aaceee0c83e4713b3826ceaa`, code version `baa31d5+phase06r2`. `run-summary.json` `tables.semantic-summaries` = 8,958 rows; `load-manifests/s3-vectors.json` names table `semantic-summaries` (partitioned by `window`; key columns `entityId, window, documentType`; text column `text`; hash column `textHash`). Counts by document type are not in the run summary and the orchestrator has no Parquet reader outside sbt; they are to be reported from the B2/local-run output (observed, not inferred).
- Phase 07 ports and adapters (read): core `VectorStore` (`putBatch`, `query`, `get`; rejections `DimensionMismatch`, `KeyCollision`, `EmbeddingMismatch`, `InvalidRecord`, `ServiceRejected`) and `WorkQueue` (`send` with optional dedup ID, `receive` with visibility timeout, `delete`; `QueueMessage.receiveCount`) in `modules/core/src/main/scala/scholarchain/core/StoragePorts.scala`. Storage: `InMemoryVectorStore`, `S3VectorsStore` (`VectorStores.scala`), `InMemoryWorkQueue`, `SqsWorkQueue` (`WorkQueues.scala`). Live suites gated by name `*AwsItSuite` in `modules/storage/src/it/scala` (build filter: run only when `SCHOLARCHAIN_AWS_IT=1`).
- Core contracts for §3: `EmbeddingDocument`, `EmbeddingJob`, `SemanticContextItem` and an embedder port are **absent** (grep over `modules/*/src`). A1 therefore runs.
- `rag → storage`: CLAUDE.md permits only `services` to compose `rag` and `storage`; a module consumes `core` contracts and does not reach into another module's internals. Build: `rag.dependsOn(core)`, `services.dependsOn(rag, storage)` (already present). So `rag` uses only core ports.

## STOP at preconditions and human decision

- STOP reason (§1/§7, a needed edge seemed forbidden): the local run composes rag (rendering, test embedder, smoke queries) with storage (Parquet reader, queue and vector-store fakes). `rag` cannot depend on `storage`, and `rag` has no Parquet reader.
- Human decision (recorded verbatim in substance from the human's answer): Option 1, with limits:
  - A0 (build-engineer): `services.dependsOn(rag, storage)` if missing (it is present). Spark SQL in services **Test scope only**, never Compile or Runtime, no new library or version.
  - The local-run harness lives only in `modules/services/src/test/**` (test-engineer). No file under `modules/services/src/main` is created.
  - `rag` and `storage` stay core-only. The test embedder stays in rag (B2).
  - **Disclosed deviation:** the work order's local run is composed in a services test harness, and C's write list is extended to `modules/services/src/test/**`.
  - Two harness runs, compared by the orchestrator, must be identical.

## A0 — build-engineer + dependency-updater

- build-engineer (STATUS: COMPLETE). Modified `project/Dependencies.scala`: `awsBedrockRuntime = awsModule("bedrockruntime")` (same apache/netty exclusions, `Versions.awsSdkV2`); `sparkSqlTest = sparkSql.withConfigurations(Some("test"))`. Modified `build.sbt`: rag gets `awsBedrockRuntime`, `awsUrlConnectionClient`; `rag.dependsOn(core)` unchanged (no rag → storage); services gets `sparkSqlTest` and `Test / parallelExecution := false`; `services.dependsOn(rag, storage)` unchanged; module-graph comment updated. Agent command: `sbt "rag/update; services/update; rag/Test/compile; services/Test/compile; scalafmtSbtCheck"` → `[success]`. Agent-observed classpaths: spark-sql 4.1.1 only on services/Test (not Compile/Runtime); bedrockruntime 2.59.7 and url-connection-client 2.59.7 on rag/Compile and (transitively) services; no apache-client or netty-nio-client jar. Orchestrator read the diff of both files; it matches.
- dependency-updater (STATUS: COMPLETE). `project/Versions.scala`: comment only (bedrockruntime added to the `awsSdkV2` module list; no value change). Created `reports/dependency-updates/phase-08-bedrockruntime.md`. Agent-observed: OSV querybatch for bedrockruntime and url-connection-client 2.59.7 http=200, `{"results":[{},{}]}`; Maven Central metadata latest/release 2.59.7. Not checked: spark-sql 4.1.1 advisories, transitives.
- Risk (build-engineer): the services Test Parquet path is not exercised until the harness exists.
- CHECK after A0 (orchestrator, canonical command): testFull core 825, entityResolution 293, hadoop 192, storage 213, sparkAnalytics 268, rag 0, services 0; **1,791 passed, 0 failed**. `[success] elapsed time: 1276 s`; marker `exit=0`. 0 occurrences of `AwsItSuite`.

## Progress checkpoint

A0 done (CHECK 1,791 passed, exit=0). Next: A1 (domain-engineer).

## A1 — domain-engineer

- STATUS: COMPLETE. Created (all under `modules/core/src/main/`): `scala/scholarchain/core/EmbeddingRecords.scala` (`EmbeddingDocumentTypes`, `EmbeddingDocument`, `EmbeddingJobStatus`, `EmbeddingJob`, `SemanticContextItem`), `scala/scholarchain/core/EmbeddingPorts.scala` (`EmbeddingError`, `EmbeddingResult`, `Embedder`), `scala/scholarchain/core/EmbeddingConfig.scala` (`EmbeddingConfigKeys`, `EmbeddingSettings`, `ResolvedEmbeddingSettings`, `EmbeddingConfigLoader`), `resources/scholarchain-embedding.v1.conf`, and frozen examples `resources/contracts/embedding-document.example.json`, `embedding-job.example.json`, `semantic-context-item.example.json`. No file modified; no frozen Phase 06/07 record touched.
- Document types: the four Phase 06 `SemanticSummary.documentType` values (`researcher_profile`, `community_profile`, `path_summary`, `topic_summary`) plus new `paper_abstract`; each maps to one of the five `VectorIndexes` (researcher-profile-v1, collaboration-community-v1, collaboration-path-v1, topic-institution-summary-v1, paper-abstract-v1).
- `EmbeddingDocument`: no text; `documentId = idFor(type, entityId, window, ordinal)` (intended vector key, at most 512 chars); `textS3Uri`, SHA-256 `textHash`, `chunkOrdinal`/`chunkCount` (1..1000), `sourceRunId`.
- `EmbeddingJob`: 1..50 documents (distinct IDs), serialized at most 204,800 bytes, model ID and version, `attempt` >= 1 (1-based), status `pending|succeeded|failed|dead_lettered`, `failureReason` required only for failed/dead_lettered (at most 500 chars).
- `SemanticContextItem`: excerpt at most 300 chars; scaladoc says exact counts, paths, components and bridges never come from it.
- `Embedder`: `modelId`, `modelVersion`, `dimension`, `maxBatchSize`, `embed`; `EmbeddingError` (`retryable` only for Throttled/Unavailable) must never carry bodies, text, headers or credentials.
- Config `embedding.*` (separate v1 contract, env `SCHOLARCHAIN_EMBEDDING_<KEY>`): model id `amazon.titan-embed-text-v2:0`, version `v2`, dimension 1024; the Voyage env-var NAME `SCHOLARCHAIN_VOYAGE_API_KEY` (a name only, never a value); queue `scholarchain-embedding-jobs`, DLQ `scholarchain-embedding-jobs-dlq`, max-attempts 3, max-documents-per-job 50; test index `scholarchain-it-embedding-test` (rejected if equal to any production index or any configured `StorageVectors` name).
- Agent commands: `sbt "core/scalafmt; core/compile; core/Test/compile; core/scalafmtCheck"` `[success]`; a throwaway `core/console` check printed the three examples valid and the default config resolving (observed by the agent). One guard false positive on heredoc text (no effect).
- Orchestrator read the three examples and `EmbeddingPorts.scala`.
- Deviation (disclosed): config entries were added in A1 (not literally in §3's list) because B1/B2 need the model ID, dimension and test index name "from config", and configuration contracts are core's.
- Risk: Phase 06 produces no `paper_abstract` rows; B2 must source paper text from source records or leave that index empty (to be reported, not fabricated).
- CHECK after A1 (canonical command): testFull core 825, entityResolution 293, hadoop 192, storage 213, sparkAnalytics 268, rag 0, services 0; **1,791 passed, 0 failed** (no new tests yet; contract tests are C's). `[success] elapsed time: 1234 s`; marker `exit=0`. 0 `AwsItSuite`.

## Progress checkpoint

A1 done (CHECK 1,791, exit=0). Next: B1 (storage-engineer), then B2 (rag-engineer), sequentially.

## B1 — storage-engineer

- STATUS: COMPLETE (agent). Created under `modules/storage/`: `src/main/scala/scholarchain/storage/EmbeddingQueue.scala` (`EmbeddingSource`, `EmbeddingJobMessage`, `EmbeddingProducerConfig`, `ProducerReport`, `EmbeddingProducer`), `EmbeddingWorker.scala` (`EmbeddingWorkerConfig`, `EmbeddingRejection`, `EmbeddingCounts`, `EmbeddingDrainResult`, `EmbeddingWorker`), `EmbeddingLedger.scala` (RunLedger entries, injected clock), `VectorText.scala` (`VectorTextResolver`, `ResolvedText`, `VectorTestIndex.resolve/guard`), `src/it/scala/scholarchain/storage/EmbeddingQueueAwsItSuite.scala` (at most 5 jobs; NOT RUN). Modified: `VectorStores.scala` (`againstStored` ignores `runId`), `NeptuneQueries.scala` (`NeptuneBulkLoad.RequiredFormat`/`checkConfig`; only `opencypher`), `AwsClients.scala` (`graphStore` checks the load config first), `src/it/.../S3VectorsAwsItSuite.scala` (validated test index + guard), `STORAGE-CONTRACTS.md` (new §12; edits to 3.4, 5.3, 9, 10). Orchestrator `git status --short` confirms exactly these storage paths; the agent's temporary smoke main is absent.
- Semantics (agent-reported; detail in STORAGE-CONTRACTS.md §12):
  - Idempotency first: `VectorStore.get` before any text fetch or embed call. Same key + hash + model ID/version + dimension + pointer → skipped. Different hash → `key-collision`. Same hash, different model/version/pointer → `embedding-mismatch`. Never overwritten.
  - Retry: explicit re-enqueue of only the failed documents as the same job with `attempt+1` (dedup ID digest); at `attempt >= max-attempts` → DLQ (`retries-exhausted`). The original is deleted only after follow-up sends succeed; `receiveCount > max-attempts` → `redelivery-bound-exceeded`.
  - Non-retryable failures (key-collision, embedding-mismatch, dimension-mismatch, invalid-record, text-missing/hash-mismatch, model-mismatch, invalid message, …) → DLQ immediately; DLQ body is an `EmbeddingJob` with status `dead_lettered` and a code-only `failureReason`; an unparseable body is replaced by a body hash.
  - Metadata: documentType, entityId (or entityIdHash over 128 chars), window, chunkOrdinal, chunkCount; no text.
- Agent commands (observed by the agent): `sbt "storage/scalafmt; storage/compile"` `[success]`; `sbt "storage/scalafmt; storage/Test/scalafmt; storage/compile; storage/Test/compile"` `[success]`; three runs of a temporary `TmpSmoke` main (since deleted) with throttling, exhaustion and reload scenarios (numbers in the agent report; manual, not tests); `sbt "storage/scalafmtCheck; storage/Test/scalafmtCheck; storage/testOnly *Vector* *Queue* *Neptune* *AwsOff*"` → `Passed: Total 127, Failed 0` (existing suites only).
- **Deviation (disclosed, constitutional precedence):** carry-over 2 asked live suites to delete the vectors they wrote by exact key. The ratified `.claude/agents/storage-engineer.md` rule 11 says "Never issue deletes … Data destruction is escalated to a human", and docs/agents.md §4 makes data destruction human-only; the core `VectorStore` port has no delete. So the suites write only deterministic keys into the validated test index (an identical rerun is a no-op), and the exact keys are listed in STORAGE-CONTRACTS.md §10 for human removal. The orchestrator follows the constitution over the work-order wording; **human decision needed at the gate** if a delete path is wanted (core port change plus approval).
- **Limitation (disclosed):** `EmbeddingQueueAwsItSuite` covers SQS, S3 and S3 Vectors with a local hash embedder; Bedrock is not covered, because the Bedrock embedder is in rag and storage may not depend on rag (no Bedrock module in storage). Queue URLs and the test index require Terraform (infra-engineer) before any live run.
- Agent-reported risks: new code is unit-untested until C; a text change under the same documentId or a new model version under the same key is dead-lettered by design; one bad text fails its embed batch; S3 Vectors has no conditional put (run one worker per index); jobs dead-lettered before processing get no ledger entry; core config loader still accepts `csv` (rejection is in storage).
- CHECK after B1, run 1 (canonical command): core 825, entityResolution 293, storage 213, sparkAnalytics 268 passed; **hadoop `Failed: Total 192, Failed 1`** — `scholarchain.hadoop.AdversarialSuite` "a giant team of 1,000 gives exactly 499,500 valid distinct pairs, none truncated" `java.util.concurrent.TimeoutException: test timed out after 30 seconds` (30.834 s). **1,790 passed, 1 failed; STATUS: FAILED.** `[error] elapsed time: 605 s`; marker `exit=1`. `git status --short modules/hadoop` empty (untouched this phase); load average at the time 4.28 / 4.96 / 4.20.
- CHECK after B1, run 2 (same command, no file changed between runs; same handling as the Phase 07 human precedent of one unchanged rerun): core 825, entityResolution 293, hadoop 192, storage 213, sparkAnalytics 268; **1,791 passed, 0 failed**. `[success] elapsed time: 1344 s`; marker `exit=0`. 0 `AwsItSuite`. Run 1's failure is recorded as a wall-clock timeout in an untouched module that did not reproduce; its cause is not established (risk for the human: a 30 s munit timeout on a loaded machine).

## Progress checkpoint

B1 done (CHECK run 1 FAILED with a hadoop timeout, run 2 1,791 passed, exit=0). Next: B2 (rag-engineer).

## B2 — rag-engineer

- STATUS: COMPLETE (agent). Created `modules/rag/RAG-EMBEDDING-CONTRACTS.md` and, under `modules/rag/src/main/scala/scholarchain/rag/`: `Chunker.scala`, `NumberCheck.scala`, `EmbeddingDocumentRenderer.scala` (`RenderedDocument`, `RenderSkip`, `RenderResult`, `TextUris`), `EmbedderSupport.scala` (`ValidatingEmbedder`, `ModelStamp`, `ModelVersioning`), `LocalHashEmbedder.scala`, `VoyageEmbedder.scala`, `BedrockEmbedder.scala`, `EmbedderFactory.scala`, `SemanticContext.scala`, `RetrievalSmoke.scala`. No core edit; nothing under `prompts/`. Orchestrator `find` confirms exactly these 11 files; the throwaway `TmpSmoke` main is absent.
- Design (agent-reported):
  - Four summary types use the Phase 06 `SemanticSummary.text` verbatim; chunked only above 2,000 UTF-16 chars (200 overlap, whitespace cuts), 0-based ordinals with chunkCount; output sorted by type, entityId, window, ordinal.
  - `paper_abstract`: rendered only from workId, title, year, venue name, topic labels and DOI (absent fields omitted). **No abstract is fabricated; the index holds title-level text under the frozen name** (disclosed). All-time window. Templates contain no numeric literal, so `NumberCheck` verifies every number against the source row.
  - textS3Uri content-addressed: `TextUris.contentAddressed(bucket, "rag/text")` → `s3://<bucket>/rag/text/<sha256>.txt`.
  - Embedders: `BedrockEmbedder` (Titan V2; SDK client built lazily, factory returns Left unless `liveProfile = true`; `SdkBedrockInvoker.clientsConstructed` counter), `VoyageEmbedder` (key read once from the env var named in config via an injected lookup; only in the Authorization header to an injected transport; `toString` redacts; errors carry fixed phrases and an HTTP status class), `LocalHashEmbedder` (`local-hash-bow` v1, FNV-1a hashed bag-of-words, unit norm). Dimension mismatch rejected in adapters, `ValidatingEmbedder` and `ModelVersioning.checkEntry`.
  - `SemanticContext.build` → `SemanticContextItem`s (excerpt at most 300 chars); docs state exact counts, paths, components and bridges come from graph or metric stores.
  - Smoke: self-deriving. Query = first 8 tokens of a document's own text that occur in no other document of the same type (min 5 distinctive); that document's entity is expected top-1. No hand-typed expectations.
- Agent commands (observed by the agent): `sbt "rag/scalafmt; rag/compile"` `[success]`; four runs of a throwaway main on synthetic data (runs 1–2 errored from the agent's own synthetic-data mistakes, runs 3–4 `[success]`; 3,000 synthetic docs, 0 NumberCheck failures, reversed input identical, smoke 30/30 at minDistinctive 5 — synthetic, not sparkS1r2); `sbt "rag/scalafmt; rag/compile; rag/Test/compile; rag/scalafmtCheck"` `[success]`.
- NOT OBSERVED: semantic-summary counts by document type (no Parquet reader in rag); to come from the harness. NOT RUN: any Bedrock, Voyage, AWS or LLM call.
- Agent-reported risks: smoke could fail on a hashed-bucket collision on real data; Bedrock SDK and JDK Voyage transport paths compile-checked only; Titan/Voyage response shapes from documentation knowledge, not live; a token longer than a chunk window is hard-cut.
- CHECK after B2 (canonical command): core 825, entityResolution 293, hadoop 192, storage 213, sparkAnalytics 268, rag 0, services 0; **1,791 passed, 0 failed**. `[success] elapsed time: 1275 s`; marker `exit=0`. 0 `AwsItSuite`.

## Progress checkpoint

B2 done (CHECK 1,791, exit=0). Next: C (test-engineer: tests + services local-run harness), then the local run x2.

## C — test-engineer

- STATUS: FAILED (agent; one failing test left in place and routed). No production, build or doc file edited (agent; orchestrator `git status` shows no new tracked modification).
- New test files (agent-reported counts):
  - core: `EmbeddingRecordsContractSuite` 26, `EmbeddingConfigContractSuite` 14.
  - storage: `EmbeddingFx` (fixtures), `EmbeddingWorkerSuite` 35, `EmbeddingProducerSuite` 20, `Phase07CarryOverSuite` 16, `VectorTextResolverSuite` 9, `EmbeddingAwsOffSuite` 8.
  - rag: `RagFx` (fixtures), `EmbeddingDocumentRendererSuite` 28, `ChunkerSuite` 14, `EmbedderContractSuite` 23, `VoyageSecretSuite` 17, `BedrockEmbedderSuite` 14, `RetrievalSmokeSuite` 21.
  - services (human-authorized extension): `LocalEmbeddingPipeline.scala`, `LocalEmbeddingRun.scala` (harness, test-only), `LocalEmbeddingRunSuite` 24 (tiny fixture, no Spark).
  - Total new: 269.
- Targeted results (agent-observed): core `Passed: Total 40`; storage (new + Vector/Neptune/AwsOff suites) `Passed: Total 215`; rag `Failed: Total 117, Failed 1, Passed 116`; services `Passed: Total 24`.
- Failing test: `scholarchain.rag.EmbeddingDocumentRendererSuite` "which duplicate wins does not depend on the input order (determinism)" (line 153). Defect: `renderDetailed` sorts by (type, entityId, window) only; the stable sort plus first-wins dedupe makes the kept conflicting duplicate depend on input order. Owner rag-engineer.
- Other findings: MEDIUM smoke coverage on sparkS1r2 at default minDistinctive 5 (16 queries: researcher_profile 1, community_profile 0, path/topic/paper 5 each); LOW `VoyageEmbedder.create` `.trim` accepts a trailing control character. Both routed to rag-engineer.
- Harness CLI: `sbt "services/Test/runMain scholarchain.services.LocalEmbeddingRun --run-root <abs sparkS1r2> --out-dir <abs new dir> --code-version <v>"`; outputs `documents.jsonl`, `vectors.jsonl`, `objects/`, `ledger.jsonl`, `dlq.jsonl` (if any), `counts.json`, `smoke.json`; exit 1 on any invariant failure (number-check, producer rejects, dead letters, pass-2 writes, smoke failure, …).
- Agent dev runs (not the official L1/L2; output under `modules/services/target/embedding-runs/`): `dev` — documents 9,958 (researcher-profile-v1 6,937; collaboration-community-v1 1,755; collaboration-path-v1 63; topic-institution-summary-v1 203; paper-abstract-v1 1,000), pass-1 vectorsWritten 9,958, pass-2 skippedUnchanged 9,958 with 0 embed calls, dead-lettered 0, smoke 16/16. `dev-md3` (minDistinctive 3): smoke 24/25, exit 1.

## Routing of C findings (before review)

- To rag-engineer: the determinism defect, the smoke coverage gap and the trim note (work order §5 "leave failing tests in place and route them").
- rag-engineer (STATUS: COMPLETE). Modified `EmbeddingDocumentRenderer.scala` (text is the last sort key; among same-(type, entity, window) duplicates the smallest text is kept, others recorded as `duplicate-key-different-text`), `RetrievalSmoke.scala` (`define` rewritten: tokens unique within the type from anywhere in the text; candidates by distinctive-token count then SHA-256 of documentId; a data-derived collision guard for the hashed embedder (`bucketDimension` 1024, `minRelativeMargin` 0.1); `DefaultMinDistinctive` 5 → 3; pass criterion unchanged), `VoyageEmbedder.scala` (trim only ASCII blanks; a trailing other control character is Left "malformed"), `RAG-EMBEDDING-CONTRACTS.md`. Agent-observed `rag/testOnly *`: first run 116/117 (its own trim regression), second run `Passed: Total 117, Failed 0`. Agent risk: real-data smoke coverage unmeasured until the harness runs; the smoke tests wiring end to end, not embedding quality.
- CHECK after C + rag fixes (canonical command): core 865, entityResolution 293, hadoop 192, storage 301, sparkAnalytics 268, rag 117 passed; **services `Failed: Total 24, Failed 1`** — `LocalEmbeddingRunSuite` "CLI: required options, absolute paths, positive integers" `ComparisonFailException` at line 641 (the test asserts a literal default 5; rag's default is now 3). **2,059 passed, 1 failed; STATUS: FAILED.** `[error] elapsed time: 647 s`; marker `exit=1`. 0 `AwsItSuite`.
- Routed to test-engineer (test-side assertion of another module's default literal).
- test-engineer follow-up (STATUS: COMPLETE). Modified (services test tree only): `LocalEmbeddingRunSuite.scala` (asserts the default against `RetrievalSmoke.DefaultMinDistinctive`), `LocalEmbeddingRun.scala` (scaladoc), `LocalEmbeddingPipeline.scala` (passes `bucketDimension = embedder.dimension` to `RetrievalSmoke.define`, so the collision guard models the embedder actually used). Agent-observed: services `Passed: Total 24`, rag `Passed: Total 117`. No new tests added.

## Local run (orchestrator, two official harness runs)

- Command, run from the repo root (absolute paths; `R` = repo root): `sbt "services/Test/runMain scholarchain.services.LocalEmbeddingRun --run-root $R/modules/spark/target/runs/sparkS1r2 --out-dir $R/modules/services/target/embedding-runs/L1 --code-version 0d1611f+phase08"`, then the same with `L2`.
- L1: completed; outputs `counts.json`, `documents.jsonl`, `ledger.jsonl`, `objects/`, `smoke.json`, `vectors.jsonl` (no `dlq.jsonl`).
- The first L2 attempt hung with no output for about 20 minutes. Observed: one sbt server JVM (pid 45942) had been up about 10 hours and had served every sbt call this session; `runMain` is not forked, so L1 ran Spark inside it. The orchestrator stopped its own L2 client and that local sbt server (`kill`, no data touched; no L2 directory had been created) and reran L2 on a fresh server: stdout `documents=9958 pass1.vectorsWritten=9958 pass2.skippedUnchanged=9958 deadLettered=0 smoke=25/25`, `[success] elapsed time: 19 s`. The cause of the hang is inferred, not established (risk: forking `services / Test / run` is a build-engineer change, not made).
- **L1 = L2:** `diff -rq L1 L2` empty; SHA-256 of the sorted per-file SHA-256 listing `e79cb1e27cecfb1b6f4d53a6509e4615931099a3b4f330bd1c131c7a629e00fd` for both; 19,921 files each.
- From L1 `counts.json` (identical in L2):
  - Input rows: semantic-summaries 8,958, works 1,000, venues 155, topics 206; convert rejects `{}`; render skips `{}`; number-check failures 0.
  - **Semantic-summary counts by document type (observed here):** researcher_profile 6,937 (3,893 entities), community_profile 1,755, path_summary 63, topic_summary 203 (sum 8,958).
  - **Documents per index:** researcher-profile-v1 6,937; collaboration-community-v1 1,755; collaboration-path-v1 63; topic-institution-summary-v1 203; paper-abstract-v1 1,000 (title-level text, no abstracts). Total 9,958; no chunking occurred.
  - Embedder `local-hash-bow` v1, dimension 1024. Embedding run IDs: pass 1 `run:c7991e30…80ff6`, pass 2 `run:ef04b954…7e3f2`.
  - Pass 1: producer textsWritten 9,958, jobsEnqueued 202; worker jobsProcessed 202, documentsEmbedded 9,958, **vectorsWritten 9,958**, embedCalls 202, retried 0, dead-lettered 0, stoppedBy `empty`.
  - Pass 2 (new run ID on the same stores): textsUnchanged 9,958, textsWritten 0; **skippedUnchanged 9,958**, embedCalls 0, vectorsWritten 0, rejected `{}` (identical reload from a new run is a no-op).
  - **Dead-lettered: 0.** Ledger entries 406. Invariant failures `[]`.
- **Smoke (25/25 passed, rank 1 each):** 5 queries per type. Each query is up to 8 tokens that occur in exactly one document of its type; the harness and the `why` field state that the expected document is the only one containing them and its hashed-embedder cosine beats every other document of the type by at least 1.1×, so it must rank first. Expected entities:
  - researcher_profile: `researcher:sc1:ccc063ce…`, `a95f25ae…`, `46bad081…`, `f10110d7…`, `ffff296f…` (queries are the researcher's name tokens plus the ID token).
  - community_profile: `community:1000-9999:researcher:sc1:16965f6d…`, `13de2169…`, `b3876f36…`, `2fb24aeb…`, `128f2ab0…`.
  - path_summary: five `path:<window>:…` entities (2000-2004 ×2, 2010-2014, 2015-2019 ×2), queried by the endpoint researcher names.
  - topic_summary: `topic:openalex:T12727`, `T11307`, `T11196`, `T12122`, `T10761`, queried by topic label tokens.
  - paper_abstract: `work:dblp:conf/chi/EppNHFKB26`, `journals/tcasII/MaMWMY24`, `journals/neuroimage/OersVZWHKRD10`, `journals/neuroimage/Krieger-Redwood16`, `journals/remotesensing/YuanWCZSZ24`, queried by title tokens.
  - Limitation (disclosed): this is a self-retrieval smoke. It proves render → queue → embed → store → query wiring and key/pointer integrity end to end. It does not measure semantic retrieval quality (several queries include the entity-ID token).
- Dev runs by test-engineer (`dev`, `dev-md3`) also remain under `modules/services/target/embedding-runs/` (generated, untracked build output).

## CHECK after C (canonical command, after L1/L2)

- testFull: core 865, entityResolution 293, hadoop 192, storage 301, sparkAnalytics 268, rag 117, services 24; Failed 0. **2,060 passed, 0 failed** (+269 over the 1,791 baseline). contractTest passed. `[success] elapsed time: 1343 s`; marker `exit=0`. 0 occurrences of `AwsItSuite`.

## Progress checkpoint

C done; local run L1 = L2; CHECK 2,060 passed, exit=0. Next: D (code-reviewer).

## D — code-reviewer, review 1

- Verdict **REQUEST_CHANGES**: 0 HIGH, 3 MEDIUM (M1 path vectors never become SemanticContextItems; M2 Bedrock client not hermetic; M3 concurrent workers can silently change a key's text), 9 LOW (deferred). Full text in `review-report.md`.

## Repair 01

- Plan and outcomes in `repair-01.md`: R1a domain-engineer (claims-table config), R1b storage-engineer (conditional key claims), R1c rag-engineer (entity-ID recovery from the vector key; hermetic Bedrock client), R1d test-engineer (+58 −1 tests, harness claims wiring).
- Local run after repair: L3 = L4 (`diff -rq` empty, SHA-256 `be10564b…ebffcf`), smoke 25/25, dead-lettered 0.
- CHECK after repair: **2,116 passed, 2 failed (STATUS: FAILED)**, marker `exit=1` — two 30 s timeouts in the untouched `scholarchain.spark.IdentitySuite` (M1, M2 tests).

## Progress checkpoint — STOP (work order §7)

STOP point reached: "CHECK fails after a repair." Code-reviewer has not yet reread the repair. Nothing staged or committed. Waiting for the human decision.

- Human decision: rerun CHECK once. Run 2 (unchanged tree, sbt servers stopped first): **2,116 passed, 2 failed (STATUS: FAILED)**, marker `exit=1` — hadoop `AdversarialSuite` 30 s timeout and spark `IdentitySuite` `SparkOutOfMemoryError`, both untouched modules (details in `repair-01.md`).

## Progress checkpoint — STOP again

Same class of resource failure recurred; per the human's instruction the orchestrator stops again. Nothing staged or committed.

## Repair 02 (build-only, human-authorized)

- build-engineer modified `build.sbt` only: `Global / concurrentRestrictions ++= Seq(Tags.limit(Tags.ForkedTestGroup, 1), Tags.limit(Tags.Test, 1))` and `Test / javaOptions += "-Xmx2g"`. No timeout or test change. Spark test sessions confirmed at `local[2]` (read-only).
- CHECK (run 3 after repair 01, nothing else running): **2,117 passed, 1 failed (STATUS: FAILED)**, `[error] elapsed time: 916 s`, marker `exit=1`. The only failure: hadoop `AdversarialSuite` 30 s timeout (34.756 s). Spark OOM did not recur. Full three-run table in `repair-02.md`.

## Progress checkpoint — FINAL STOP for the human

- Per the human's instruction ("If it fails again, STOP and report; do not retry"), the orchestrator stops. No further rerun, repair or review.
- State: all Phase 08 suites pass (core 874, storage 323, rag 140, services 28; +326 tests over the 1,791 baseline counting the repair tests); L3 = L4 after repair 01; the single remaining failure is the untouched hadoop `AdversarialSuite` 30 s timeout. Code-reviewer has not reread repair 01 or repair 02. Phase 08 is **not** complete. Nothing staged or committed.

## Repair 03 (final, human-authorized)

- Step 1 (build-engineer): `hadoop / Test / parallelExecution := false` in `build.sbt`. CHECK run 4: 2,117 passed, 1 failed (`AdversarialSuite` 30 s timeout, 32.683 s), 1,082 s, exit=1.
- Step 3 (test-engineer, triggered by run 4): that one test's timeout raised 30 s → 120 s via a per-test override in `modules/hadoop/src/test/scala/scholarchain/hadoop/AdversarialSuite.scala`, with the reason in a comment; assertion and input unchanged. CHECK run 5: **2,118 passed, 0 failed**, `[success] elapsed time: 2269 s`, marker `exit=0`, 0 `AwsItSuite`.
- Step 4 (code-reviewer, review 2 of repairs 01–03): **APPROVE WITH NOTES** — M1, M2, M3 RESOLVED; new LOW N1–N4; repairs 02 and 03 within scope.
- All five post-repair-01 CHECK runs are tabulated in `repair-03.md`.

## Gate summary (observed)

- **Same vector key cannot silently refer to different text; model version on every vector:** single-writer idempotency check plus a conditional key claim (`EmbeddingKeyClaims`, `PutCondition.NotExists`) before every vector write; different hash → `key-collision` dead-letter; same hash with another model/pointer/index → `embedding-mismatch`. Model ID and version stored in vector metadata; dimension checked in adapters, `ValidatingEmbedder` and `ModelVersioning`. Reviewer: M3 RESOLVED; tests `EmbeddingClaimsSuite` (22) etc.
- **Source text fetchable from a vector result via the S3 pointer:** content-addressed `textS3Uri` (`s3://<bucket>/rag/text/<sha256>.txt`), `VectorTextResolver` with hash verification; tested in storage and in the services end-to-end suite.
- **Queue worker retries, dead-letters poison messages, is idempotent:** explicit re-enqueue with `attempt+1`, DLQ at max-attempts, immediate DLQ for non-retryable failures, unparseable bodies dead-lettered as a hash envelope; L1–L4 pass 2 skipped 9,958 of 9,958 with 0 embed calls; dead-lettered 0.
- **Retrieval smoke passes locally; AWS 10K ingestion NOT RUN (Phase 15):** smoke 25/25 (5 per type, including 5 path_summary queries confirmed via key recovery) in L1–L4. It is a self-retrieval wiring smoke, not a neighbour-quality test (L3, disclosed). No AWS, Bedrock, Voyage or LLM call was made; live suites NOT RUN.
- **Exact counts, paths, components and bridges come from graph or metric stores, not vector similarity:** `SemanticContextItem` has no count field; the rule is in code docs and both contract docs; excerpts may still carry Phase 06 counts, so Phase 09 claim verification must reject numbers sourced from semantic context (L4).
- **Two local runs identical:** L1 = L2 (SHA-256 `e79cb1e2…00fd`) before repair 01; L3 = L4 (SHA-256 `be10564b…ebffcf`) after repair 01; 19,921 files each. Not repeated after repairs 02–03 (build and hadoop test only).
- **`sbt check` total rises by at least 25:** 1,791 → **2,118 passed, 0 failed** (+327), marker `exit=0`.
- **Reviewer verdict:** review 1 REQUEST_CHANGES → repairs 01–03 → review 2 **APPROVE WITH NOTES**.

## Open items for the human gate

1. Deviations (disclosed):
   - The local run is composed in a services test harness (`modules/services/src/test/**`, human-approved at the precondition STOP).
   - Live suites do not delete their test vectors (storage-engineer rule 11 and §4: destruction is human-only); keys are listed in `STORAGE-CONTRACTS.md` §10. Claims written by a live run are also permanent and not in that list (N1).
   - `EmbeddingQueueAwsItSuite` does not cover Bedrock; a Bedrock live suite is needed before Phase 15.
   - `paper-abstract-v1` holds title-level text (no abstracts upstream).
   - Embedding config (incl. the claims table) was added in A1/R1a.
   - Self-retrieval smoke, not neighbour quality.
   - Repair 03 raised one hadoop test's timeout to 120 s (human-authorized); whether munit actually applies it is unverified (N2).
2. Deferred LOW findings: review 1 L1–L9; review 2 N1–N4.
3. Phase 12 prerequisites (infra-engineer, human-approved): DynamoDB claims table and a separate test claims table, the vector test index, the embedding queue and DLQ, IAM; the Voyage key must reach the runtime from Secrets Manager (L7).
4. CHECK history: several resource-failure runs (hadoop 30 s timeout after B1 run 1; repair-01 runs 1–4) — all recorded. With serialized test tasks, CHECK now takes 916–2,269 s.
5. Process disclosures: the first official L2 attempt hung (Spark inside the long-lived sbt server, since `runMain` is not forked); the orchestrator stopped its own local sbt servers several times (no data touched). Guard false positives on `.key` / credentials-path text in several agent and orchestrator commands (nothing executed). One auto-mode classifier denial of a test-engineer string split (dropped). Code-version labels for the local runs are hand-typed (L9). Generated outputs (`dev`, `dev-md3`, L1–L4) remain under `modules/services/target/embedding-runs/`.
6. NOT RUN: any AWS call, live suites, Bedrock/Voyage calls, the 10K ingestion (Phase 15), DynamoDB claims against the real service.

## Progress checkpoint

Phase 08 complete; waiting for human gate. Nothing staged or committed.
