# Phase 07 — Storage adapters and loading (work order)

Phase folder: `docs/agent-runs/phase-07-storage/`
Spec sections: "Phase 7: implement storage adapters and loading", "S3 Vectors design", "Research graph model".
Input: the committed Phase 06 output (sparkS1r2) and its load manifests. **No AWS in this phase.** Everything is built and tested locally against fakes. Live AWS integration suites exist behind an explicit, disabled-by-default profile and are reported as NOT RUN; they run in Phase 14–15.

Ownership follows `docs/agents.md` §1 and §1.1.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A0 | build-engineer (+ dependency-updater for version values) | `build.sbt`, `project/**` | always (AWS SDK modules are needed) |
| A1 | domain-engineer | `modules/core/**` except `src/test/**` | only if a contract in §3 is missing |
| B | storage-engineer | `modules/storage/**` except `src/test/**` | always |
| C | test-engineer | `modules/storage/src/test/**`, `modules/core/src/test/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code, tests, contracts or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. Canonical test command, exactly: `sbt check; echo "exit=$?" > .claude/.last-test-run`. Subagents cannot run it; you run it from the repo root, with `cd` as its own call first.
4. Speed rule: agents use targeted commands (`sbt "storage/testOnly *"`, `sbt "storage/Test/compile"`). Use `testOnly *` for counts. The full canonical command runs once per work order. Run `scalafmt` on new files before CHECK.
5. One sbt user at a time.
6. Append a `## Progress checkpoint` to `agent-report.md` after each work order. Keep reports concise.
7. **No AWS calls, no credentials read.** sbt dependency resolution from Maven Central is allowed. Never read `.env`, `~/.aws` or any key.
8. Do not stage or commit. Unattended mode: continue without asking, except at the STOP points in §7.
9. **Repair policy:** fix HIGH and MEDIUM findings that concern correctness, security, data loss or idempotency. Defer LOW findings with disclosure.

## 1. Preconditions (orchestrator, read-only)

Record under `## Preconditions` in `agent-report.md`:

- `git status --short` is clean, and HEAD descends from `docs/agent-runs/phase-06-spark-analytics/commit.txt`. If not, STOP.
- The canonical command exits 0. Record the baseline (Phase 06 ended at 1,513).
- Locate sparkS1r2 and its load manifests (Neptune, DynamoDB, S3 Vectors, ordinary S3). Record the table list and row counts.
- From `CLAUDE.md` module boundaries, record what `storage` may depend on. `storage` must not depend on `spark`, `hadoop` or `entityResolution` code; it reads their outputs as files plus manifests. If a loader needs Spark SQL to read Parquet, it may use the Spark library (Provided), but not `modules/spark` code. If CLAUDE.md forbids that, STOP and ask.
- Record from the Phase 05 and 06 gates the items this phase must respect: provenance as a JSON string column, `CoauthorEdgeV2` and `ResearcherMetricsV2`, the DBLP base ID as the canonical researcher ID, and external-identity vertices.

## 2. A0 — build-engineer + dependency-updater

- dependency-updater pins, in `project/Versions.scala`, one AWS SDK for Java v2 version (BOM or aligned modules) current at implementation time, recording the version source. build-engineer adds only `s3`, `dynamodb`, `s3vectors`, `neptunedata`, `sqs` and `sts` (sts for the live profile's identity check only), plus any HTTP client the SDK needs, pinned. Bedrock comes in Phase 08.
- Wire `storage → core`.
- Add a setting that **disables live AWS integration suites by default**: suites named `*AwsItSuite` run only when `SCHOLARCHAIN_AWS_IT=1`. `sbt check` must never touch AWS.
- Run the canonical command and report.

## 3. A1 — domain-engineer (conditional)

Inspect first; add only what is missing, as versioned immutable records with validation and a verbatim JSON example:
- `VectorRecord` (vector key, index name, dimension, embedding model ID and version, text hash, text S3 URI, compact metadata map, run ID).
- `RunLedgerEntry`, `ArtifactRegistryEntry`, `QuerySessionRecord`, `EntityLinkCacheEntry`, `EvidenceBundle` storage form, if absent.
- Ports (traits) for the object store, graph store, key-value store, vector store and work queue.

Run the canonical command and report.

## 4. B — storage-engineer

Everything goes in `modules/storage/**`, plus `modules/storage/STORAGE-CONTRACTS.md`.

**Adapters (each behind a core port, each with an in-memory or filesystem fake for tests):**
1. **Ordinary S3:** raw, curated, graph, rag, reports and logs prefixes from config; versioned keys; content hashes; run manifests. The fake is filesystem-backed.
2. **Neptune:**
   - A loader that converts the Phase 06 node and edge tables into **Neptune openCypher bulk-load CSV** (correct header and type syntax), for Researcher, Work, Venue, Institution, Topic, external-identity and the selected context nodes; and AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM and SUPPORTED_BY as implemented. COAUTHORED_WITH carries jointPublicationCount, first and last years, window and the evidence pointer.
   - A bulk-load request builder (S3 source, role ARN and format from config).
   - A read-only query adapter using the `neptunedata` openCypher API, with **parameterized templates only**: an allowlist of neighbors, coauthor edge lookup, shortest path within a depth bound, component and community lookup, and researcher metrics. Every template enforces depth, result-count and timeout bounds. No string-built Cypher from user text.
   - An invariant check: every edge endpoint exists in the vertex files, and Neptune IDs equal the Spark IDs.
3. **DynamoDB:** tables RunLedger, EntityLinkCache, QuerySession, EvidenceBundle and ArtifactRegistry, plus ResearcherMetricCache only if the documented access patterns justify it. Explicit partition and sort keys documented against access patterns; conditional writes for idempotency; TTL only on genuinely disposable data. The fake enforces conditional-write semantics.
4. **S3 Vectors:** vector-bucket and index abstractions for the five required indexes (`researcher-profile-v1`, `collaboration-community-v1`, `paper-abstract-v1`, `collaboration-path-v1`, `topic-institution-summary-v1`). Batched `PutVectors` within the service limits; look them up in current AWS documentation and record each with its source URL and date in STORAGE-CONTRACTS.md. Do not invent limits. Compact filterable metadata only (researcher, window, source, topic, institution, community, document type, model version, text hash, run ID, text S3 URI). **Long text never goes in metadata.** A vector key that collides with a different text hash is rejected and logged. Transient failures retry with bounded backoff; rejected vectors are recorded. The fake implements cosine top-k with metadata filters.
5. **Optional OpenSearch:** NOT implemented; state that.

**Live AWS integration suites** (`*AwsItSuite`, disabled by default): a bounded S3 put/get, a DynamoDB conditional write, an S3 Vectors put/query of at most 10 vectors, and a Neptune read query. Written now, reported as NOT RUN.

**Local run:** a loader main that reads sparkS1r2 and writes, under `modules/storage/target/loads/`, the Neptune CSV files and the DynamoDB item files (JSONL). Run it twice and compare; the output must be identical. Report file counts, row counts per label and edge type, the dangling-endpoint check result, and the cleanup procedure for each store.

## 5. C — test-engineer (at least 30 new tests)

Paste the B report.
- Round trips for every new record.
- Neptune CSV header and type syntax; every edge endpoint present; IDs equal to the Spark IDs; COAUTHORED_WITH properties and evidence pointer.
- Query templates reject an out-of-bound depth, limit or timeout and never interpolate raw text (including injection strings in parameters).
- DynamoDB keys support every documented access pattern; conditional-write conflicts; idempotent reload (twice gives identical state).
- S3 Vectors batching within limits; metadata without long text; a duplicate key with a different text hash is rejected; malformed or missing metadata; retry and backoff on transient failures, with a cap.
- **Profile gate:** with `SCHOLARCHAIN_AWS_IT` unset, no AWS suite runs and no AWS client is constructed (prove it with a credentials provider that fails if called).

Leave failing tests in place and route them. Run `scalafmt`; the orchestrator runs CHECK.

## 6. D — code-reviewer (read-only)

Review in this order: idempotency and data loss; identifier and provenance continuity from Spark to Neptune; injection (Cypher templates, metadata); secrets and IAM assumptions; long text in vector metadata; key-collision handling; the AWS-off-by-default guarantee; service limits backed by cited documentation; tests that would pass if broken; claims not backed by output. Severity, file:line, failing input and owner for each finding. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 7. Repair loop and STOP points

- On REQUEST_CHANGES: write `repair-NN.md` grouped by owner, route the findings within the repair policy (§0.9), run CHECK, rerun the local loader twice if production source changed, and have D reread only the repair. At most **2 rounds**.
- **STOP and ask the human** if: a precondition fails; a module edge outside §1 seems needed; a finding would change an earlier frozen contract, the identity rules, ownership or anything constitutional; any step would need AWS credentials or a live call; CHECK fails after a repair; the two loader runs differ; or a third round would be needed.
- Otherwise, after APPROVE or APPROVE WITH NOTES: write the final checkpoint "Phase 07 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- Long text is not stuffed into vector metadata.
- Neptune IDs match the Spark IDs; COAUTHORED_WITH edges trace to supporting works; 0 dangling endpoints.
- DynamoDB keys support the stated access patterns; loaders are idempotent.
- AWS suites exist and are off by default; `sbt check` makes no AWS call.
- The two local loader runs are identical. The `sbt check` total rises by at least 30. Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

Embeddings and the ingestion queue (Phase 08); live AWS calls (Phase 14–15); Terraform; runtime agents and services; OpenSearch.