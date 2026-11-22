# Phase 08 — Embeddings and the S3 Vectors ingestion path (work order)

Phase folder: `docs/agent-runs/phase-08-embeddings-vectors/`
Spec sections: "Phase 8: create embeddings and populate S3 Vectors", "S3 Vectors design".
Input: the committed Phase 06 semantic summaries (sparkS1r2) and the Phase 07 storage ports and vector-store adapter. **No AWS and no embedding API calls in this phase.** The bounded AWS ingestion of up to 10K real vectors (instructor approval item 2) runs in Phase 15 and is reported here as NOT RUN.

Ownership follows `docs/agents.md` §1 and §1.1.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A0 | build-engineer (+ dependency-updater for version values) | `build.sbt`, `project/**` | always (Bedrock runtime module) |
| A1 | domain-engineer | `modules/core/**` except `src/test/**` | only if a contract in §3 is missing |
| B1 | storage-engineer | `modules/storage/**` except `src/test/**` | always |
| B2 | rag-engineer | `modules/rag/**` except `src/test/**` | always |
| C | test-engineer | `modules/storage/src/test/**`, `modules/rag/src/test/**`, `modules/core/src/test/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code, tests, contracts or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. Canonical test command, exactly: `sbt check; echo "exit=$?" > .claude/.last-test-run`. Subagents cannot run it; you run it from the repo root, with `cd` as its own call first.
4. Speed rule: targeted commands while iterating (`sbt "rag/testOnly *"`, `sbt "storage/testOnly *"`); the full canonical command once per work order. Run `scalafmt` on new files before CHECK.
5. One sbt user at a time. B1 and B2 run one after the other, never in parallel.
6. Append a `## Progress checkpoint` to `agent-report.md` after each work order.
7. **No AWS calls, no embedding or LLM API calls, no credentials read.** Never read `.env`, `~/.aws` or any key.
8. Do not stage or commit. Unattended mode: continue without asking, except at the STOP points in §7.
9. **Repair policy:** fix HIGH and MEDIUM findings that concern correctness, security, data loss or idempotency. Defer LOW findings with disclosure.

## 1. Preconditions (orchestrator, read-only)

- `git status --short` is clean, and HEAD descends from `docs/agent-runs/phase-07-storage/commit.txt`. If not, STOP.
- The canonical command exits 0. Record the baseline (Phase 07's final total).
- Record the semantic-summary counts by document type in sparkS1r2, and the Phase 07 vector-store port, the S3 Vectors adapter, the queue port and the `*AwsItSuite` gate.
- From `CLAUDE.md`, record whether `rag` may depend on `storage`. If not, rag uses only core ports. If a needed edge is forbidden, STOP and ask.

## 2. A0 — build-engineer + dependency-updater

Add the AWS SDK `bedrockruntime` module at the Phase 07 SDK version. No other new library. Wire `rag → core` (and `rag → storage` only if allowed). Run the canonical command and report.

## 3. A1 — domain-engineer (conditional)

Add only what is missing, as versioned immutable records with validation and a verbatim JSON example:
- `EmbeddingDocument` (document ID, document type, entity ID, window, source text reference, text hash, chunk ordinal).
- `EmbeddingJob` (job ID, document references, model ID and version, attempt, status).
- `SemanticContextItem` (vector key, score, document type, entity ID, window, text S3 URI, short excerpt).
- The embedder port, if absent.

Run the canonical command and report.

## 4. Work orders

### B1 — storage-engineer (ingestion path)

Everything goes in `modules/storage/**`; update `STORAGE-CONTRACTS.md`.
- **Work queue:** an SQS adapter and an in-memory fake behind the Phase 07 queue port. The producer enqueues `EmbeddingJob` batches from a manifest. The worker loop receives a job, embeds through the embedder port, writes vectors through the vector-store port, then deletes the message. After a bounded number of attempts, a poison message goes to a dead-letter path. This is what the Phase 15 backlog and elasticity experiment will scale.
- **Idempotency:** skip unchanged text by (text hash, embedding model version). The same vector key can never silently refer to different text.
- **Ledger:** record attempts, failures and retry counts in RunLedger.
- **Text storage:** authoritative source text and generated documents go to ordinary S3 (the filesystem fake locally); vectors carry only the S3 pointer and compact metadata.
- **Live suite:** add an `*AwsItSuite` that enqueues and drains at most 5 jobs against SQS, S3 Vectors and Bedrock. Disabled by default; reported as NOT RUN. Put it wherever Phase 07's live suites live (`modules/storage/src/it`, pending ratification); do not create any other new path.
- **Phase 07 carry-overs (fix in this work order):**
  1. The Neptune load config must accept only the openCypher bulk-load format; reject `csv` and other values with a clear error.
  2. Every live suite that writes vectors uses a dedicated test index (name from config, never one of the five production indexes) and deletes only the vectors it wrote, by exact key.
  3. Reloading an identical vector (same key, same text hash, same model version) from a new run is an idempotent no-op, not a rejection. A same key with a different text hash is still rejected.

### B2 — rag-engineer (documents, embedders, context)

Everything goes in `modules/rag/**`, plus `modules/rag/RAG-EMBEDDING-CONTRACTS.md`.
- **Rendering and chunking:** deterministic templates that turn the Phase 06 semantic summaries and source records into `EmbeddingDocument`s for each of the five indexes, and a chunking policy for longer text (size, overlap, ordinals). Every number in a rendered text must appear in its source row.
- **Embedders behind the core port:**
  - Bedrock Titan Text Embeddings V2: the default, with model ID and dimension from config.
  - Voyage AI: the approved fallback. The key comes only from env `SCHOLARCHAIN_VOYAGE_API_KEY` and is never logged.
  - A **deterministic local test embedder** (for example, hashed bag-of-words) for tests and local runs.
  - No live call in this phase.
- **Model versioning:** model ID, version and dimension travel with every vector; a dimension mismatch is rejected.
- **Semantic-context format:** `SemanticContextItem`s for the Phase 09 SemanticContextAgent, kept separate from graph facts.
- **Local retrieval smoke test:** with the test embedder and the Phase 07 vector-store fake, known queries return the expected researcher, topic or community neighbors. Report which neighbors and why they are expected.

**Local run (orchestrator routes after B1 and B2):** build the embedding documents from sparkS1r2, enqueue them through the fake queue, drain with the test embedder into the vector-store fake, and run the smoke queries. Run it twice; document and vector output must be identical. Report document counts per index, vectors written, skipped-unchanged on the second run, dead-lettered count, and the smoke results.

## 5. C — test-engineer (at least 25 new tests)

Paste the B1 and B2 reports.
- **Carry-overs:** the Neptune format other than openCypher is rejected; live vector suites can only target the configured test index; an identical vector reload from a new run is a no-op, while a different text hash under the same key is still rejected.
- **Queue:** the worker deletes on success, retries on failure, and dead-letters a poison message after the bound; unchanged text is skipped; a key collision with a different text hash is rejected; ledger entries are written.
- **RAG:** deterministic rendering; every number in a rendered text exists in its source row; chunk boundaries and ordinals; dimension mismatch rejected; the Voyage key never appears in a log line or exception; the vector result resolves to its S3 text pointer; smoke neighbors.
- **Profile gate:** with `SCHOLARCHAIN_AWS_IT` unset, no Bedrock, SQS or S3 Vectors client is constructed.

Leave failing tests in place and route them. Run `scalafmt`; the orchestrator runs CHECK.

## 6. D — code-reviewer (read-only)

Review in this order: idempotency and key collisions; queue and dead-letter semantics; secrets handling; long text in metadata; invented numbers in rendered text; model-version integrity; the AWS-off-by-default guarantee; the rule that exact counts, paths and bridges never come from vector similarity; tests that would pass if broken; claims not backed by output. Severity, file:line, failing input and owner for each finding. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 7. Repair loop and STOP points

- On REQUEST_CHANGES: write `repair-NN.md` grouped by owner, route within the repair policy, run CHECK, rerun the local run twice if production source changed, and have D reread only the repair. At most **2 rounds**.
- **STOP and ask the human** if: a precondition fails; a forbidden module edge seems needed; a finding would change an earlier frozen contract, ownership or anything constitutional; any step would need credentials or a live call; CHECK fails after a repair; the two local runs differ; or a third round would be needed.
- Otherwise, after APPROVE or APPROVE WITH NOTES: write the final checkpoint "Phase 08 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- The same vector key cannot silently refer to different text; the model version is stored on every vector.
- Source text and generated documents are fetchable from a vector result through the S3 pointer.
- The queue worker retries, dead-letters poison messages and is idempotent.
- Retrieval smoke tests pass locally; the AWS 10K ingestion is NOT RUN (Phase 15).
- Exact counts, paths, components and bridges come from graph or metric stores, not vector similarity.
- The two local runs are identical. The `sbt check` total rises by at least 25. Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

Live AWS and embedding calls (Phase 14–15); runtime agents (Phase 09); services; Terraform; OpenSearch.