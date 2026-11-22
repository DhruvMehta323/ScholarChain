# Phase 08 — Human gate

Phase: 08, embeddings and the S3 Vectors ingestion path
Date: 2026-11-22
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with the decisions, disclosures and remaining risks below.

## 1. What I reviewed

- `prompt.md` (written by me; saved verbatim, including the three Phase 07 carry-overs), `repair-01.md`, `repair-02.md`, `repair-03.md`.
- `agent-report.md` (preconditions, A0, A1, B1, B2, C, the local runs, the three repair rounds, the CHECK table and the gate summary).
- `review-report.md`: review 1 (REQUEST_CHANGES, three MEDIUM findings) and the combined reread of repairs 01–03 (APPROVE WITH NOTES).
- `modules/storage/STORAGE-CONTRACTS.md` and `modules/rag/RAG-EMBEDDING-CONTRACTS.md`.
- My own checks (section 7).

## 2. What was accepted

- **Build:** the AWS SDK `bedrockruntime` module at the Phase 07 SDK version; `services` composes `rag` and `storage` for the local-run harness, with Spark SQL in services Test scope only. [Confirm the exact A0 change list from agent-report.md.]
- **Core:** `EmbeddingDocument`, `EmbeddingJob`, `SemanticContextItem` and the embedder port, as added in A1. [Confirm from agent-report.md.]
- **Storage (B1):** SQS work queue adapter and fake; a worker that embeds, writes, deletes on success, retries and dead-letters poison messages; idempotency by text hash and model version; per-key claims with conditional writes (repair 01) so concurrent workers cannot overwrite; RunLedger attempts and failures. Phase 07 carry-overs fixed: Neptune format restricted to openCypher; live vector suites write only to the configured test index; an identical vector reload from a new run is a no-op, while a different text hash under the same key is rejected.
- **RAG (B2):** deterministic rendering of the Phase 06 summaries into embedding documents for the five indexes; chunking; Bedrock Titan Text Embeddings V2 (default), Voyage AI (approved fallback, key from env only) and a deterministic local test embedder; model ID, version and dimension on every vector; `SemanticContextItem` output kept separate from graph facts; path vectors map to semantic-context items with the full entity ID recovered from the vector key (repair 01). The Bedrock client uses injected credentials and reads no shared profile file (repair 01).
- **Local runs on sparkS1r2:** L3 and L4 (after repair 01) are byte-identical (19,921 files, same SHA-256). 9,958 documents across the five indexes and 9,958 vectors written; the second pass with a new run ID skipped all 9,958 with 0 embedding calls; 0 dead-lettered; smoke 25/25.
- **Tests:** 1,791 → **2,118 passed, 0 failed** (+327), marker `exit=0`.

## 3. Decisions on deviations

1. **Local run composed in a services test harness** (`modules/services/src/test/**`, test-engineer): accepted. `rag` and `storage` stay core-only; no file under `modules/services/src/main` was created. I authorized this at the §1 STOP.
2. **Live suites do not delete their test vectors:** accepted. The storage rule forbids agent deletes; they write only to the dedicated test index, and the exact keys are listed in STORAGE-CONTRACTS.md §10 for me to remove (or the whole test index at cleanup).
3. **The live queue suite does not cover Bedrock:** accepted. Real Bedrock embedding is exercised by the Phase 15 ingestion.
4. **`paper-abstract-v1` holds title-level text only:** accepted for the 1K run, because no abstracts exist upstream at this scale. The 50K OpenAlex fetch includes abstracts (in OpenAlex's inverted-index form); Phase 15 may use them.
5. **Smoke tests check self-retrieval only:** accepted as a local smoke check. Real neighbour quality is measured in the Phase 16 retrieval evaluation.
6. **N1, live claims in the production claims table:** not acceptable for a live run. Phase 12 (in progress) adds a separate test claims table, and the live suite must target it. Until then the live suites stay disabled.

## 4. Run history (disclosed)

Five CHECK runs after repair 01, all recorded in `repair-03.md`:
1. After repair 01: 2,116 passed, 2 failed (Spark IdentitySuite timeouts).
2. Unchanged rerun: 2,116 passed, 2 failed (the Hadoop AdversarialSuite timeout and a Spark out-of-memory error).
3. After repair 02 (build-only: one module's forked tests at a time, `-Xmx2g`): 2,117 passed, 1 failed (Hadoop timeout).
4. After repair 03 step 1 (build-only: `hadoop / Test / parallelExecution := false`): 2,117 passed, 1 failed (AdversarialSuite at 32.7 s).
5. After repair 03 step 3 (test-engineer raised only that test's timeout from 30 s to 120 s, with the reason in a comment; assertion and input unchanged): **2,118 passed, 0 failed** (2,269 s).

Every failure was a timeout or out-of-memory error in a module untouched by this phase. At least one of these runs overlapped an `sbt check` in the parallel worktree, which likely contributed. N2: the reviewer could not confirm that munit applies the 120 s limit to that one test, and the test's own duration in run 5 was not recorded. A full CHECK now takes 15–38 minutes because test tasks run one at a time.

## 5. Deferred notes

L1–L9 (review 1) and N3 (a test title promises more than it checks) and N4 (claims-table name validation gaps) are deferred and listed in agent-report.md.

## 6. Phase 12 prerequisites recorded

The claims table and a separate test claims table, the vector test index, the embedding queue with its dead-letter queue, the matching IAM permissions, and delivery of the Voyage key from Secrets Manager.

## 7. My own checks

- [ ] `.claude/.last-test-run` reads `exit=0`.
- [ ] I read the 120 s timeout change and its comment in AdversarialSuite.
- [ ] I spot-checked that a vector's metadata holds an S3 text pointer, not text.
- [ ] `git status` shows no `target/` or `datasets/raw/` files tracked.

## 8. Disclosures

- Claude Code 2.1.323 in accept-edits mode on a second Claude Pro account, monitored through Remote Control; orchestrator on Opus. The session ran for about 15 hours of wall time across my breaks.
- No AWS, Bedrock, Voyage or LLM call was made. The live suites and the 10K AWS ingestion are NOT RUN (Phase 15).
- Phase 12 (Terraform) and the end of Phase 15a ran in parallel in a separate worktree on the same machine.
- The first official local run hung because the harness runs Spark inside the sbt server; the orchestrator stopped its local sbt servers and reran it. Forking that run is a deferred build change.

## 9. Manual changes

None to repository source. I wrote the decisions in this file.

## 10. Remaining risk

Live behavior untested until Phase 14–15; the unconfirmed per-test timeout (N2); the CHECK flakiness under load; title-only paper vectors at 1K scale.

Accepted commit: see `commit.txt`.