# ScholarChain services: API reference (Phase 10)

Owner: service-engineer. Code: `modules/services/src/main/scala/scholarchain/services/`. Wire models are the
frozen core contracts in `modules/core/REST-CONTRACTS.md` (package `scholarchain.core.rest`); this file does not
redefine them. HTTP server: `com.sun.net.httpserver`; JSON: uPickle. No other library.

## Services

| Service | Main class | Port | Role |
|---|---|---|---|
| query-api | `scholarchain.services.QueryApiMain` | 8080 | All public `/v1` routes, health and readiness. Runs every answer through the Phase 09 `QueryWorkflow`. |
| retrieval | `scholarchain.services.RetrievalMain` | 8080 | Versioned internal routes for graph, vector and embedding reads, plus health and readiness. |
| embedding-worker | `scholarchain.services.EmbeddingWorkerMain` | none | Loop over the Phase 08 `EmbeddingWorker` and the SQS queue port. No listener. |

In AWS mode query-api reaches graph, vector and embedding reads only through retrieval
(`SCHOLARCHAIN_SERVICES_RETRIEVAL_URL`, `SCHOLARCHAIN_INTERNAL_API_KEY`); it has no Neptune client.

## Cross-cutting behavior

- **Trace and request ids.** `X-Trace-Id` and `X-Request-Id` are accepted when they are valid tokens
  (`TraceIds.isValid`), otherwise generated. Both are returned on every response, appear in every error body
  (`traceId`) and in every log line. A rejected caller value is never echoed.
- **Errors.** Every error is an `ApiError` (`error`, `message`, `traceId`, `retryable`). Messages are fixed text:
  no request value, key, stack trace or endpoint. Statuses: 400, 401, 404, 409, 422, 429, 500, 502, 503, 504. 403 is in
  the contract's code set but no route produces it (single API key). 429 `RATE_LIMITED` (retryable) is returned
  when the bounded query queue (or the HTTP queue) is full, before any model work.
- **Authentication.** Every `/v1` route needs header `X-API-Key` equal to `SCHOLARCHAIN_SERVICES_API_KEY`
  (SHA-256 digests compared with `MessageDigest.isEqual`). `/healthz`, `/health` and `/readyz` need no key.
  Outside local mode the process refuses to start when the key is missing. Retrieval's `/internal/v1/*` routes
  need `X-Internal-Api-Key` equal to `SCHOLARCHAIN_INTERNAL_API_KEY` (same comparison).
- **Bodies.** At most 32768 bytes (`RestLimits.MaxBodyBytes`), larger is 400. Unknown fields are 400.
- **Downstream ports.** Key-value, graph, vector and object ports run behind a guard: per-call timeout
  (`SCHOLARCHAIN_SERVICES_DOWNSTREAM_TIMEOUT_MILLIS`, default 8000), at most 3 attempts for reads (none for
  writes), and a circuit breaker (opens after 5 consecutive failures, one probe after 10 s). Timeout is 504,
  open breaker 503, other failure 502. A workflow run that abstained only because a dependency failed is
  reported as that 502/503/504, not as "no answer". Retrieval replies: 400/422 are a rejected request (400);
  401/403/5xx, timeouts and unreachable are failures (502/503/504), counted by the breaker and the run's probe.
- **Concurrency.** At most `SCHOLARCHAIN_SERVICES_MAX_CONCURRENT_QUERIES` (default 4) queries run at once, with
  `SCHOLARCHAIN_SERVICES_MAX_QUEUED_QUERIES` (default 16) waiting; more get 429. Each run builds its own
  `QueryWorkflow`, so no resolver cache is shared between threads. `/healthz`, `/health` and `/readyz` run on a
  separate small pool and are not queued behind query work.
- **Logs.** One JSON line per request and event: `ts`, `level`, `service`, `event`, `traceId`, `requestId` and
  a few fixed fields (method, path without query, status, latency). Never a body, key or header value.
- **Stored items** (QuerySession table, `KeyValueStore`, always with a TTL): `job#<id>`, `idem#<hash>` (core
  `RestKeys`), and, written by the service, `evid#<evidenceId>`, `qevid#<queryId>`, `explain#<queryId>`.

## Public routes (query-api)

### POST /v1/query

Body: core `QueryRequest` (see REST-CONTRACTS.md). Validated by `QueryRequest.parse` and `checkSupported`.
Optional header `Idempotency-Key` (token, 1 to 128 characters).

| Outcome | Status | Body |
|---|---|---|
| Finished within `SCHOLARCHAIN_SERVICES_SYNC_DEADLINE_MILLIS` (default 20000) | 200 | `SyncQueryResponse` (`mode: synchronous`, claims with evidence ids and verification status, `links`) |
| Still running at the deadline | 202 | `AsyncQueryResponse` (`mode: asynchronous`, `jobId`, `status`, `statusUrl`); the run continues |
| Malformed JSON, unknown field, bad value | 400 | `ApiError` |
| Missing or wrong key | 401 | `ApiError` |
| Same `Idempotency-Key` with a different body | 409 `IDEMPOTENCY_CONFLICT` | `ApiError` |
| Same key while the first request is still being processed | 409 `INCOMPATIBLE_STATE` (retryable) | `ApiError` |
| Too many queries in progress (bounded queue full) | 429 `RATE_LIMITED` (retryable) | `ApiError` |
| Unsupported constraint, metric or plan; topics/venues/institutions with more than one entry or with whitespace | 422 | `ApiError` |
| Downstream failure, open breaker, timeout | 502, 503, 504 | `ApiError` |

An answer that abstained is still 200 and carries `abstained: true` and `abstainReason` (a token such as
`no_verified_claims`; absent when not abstained); `query.completed` logs both. `ClaimView.uncertainty` is not set
(the workflow does not expose per-claim uncertainty).

Request options this deployment does not honor are refused with 422, never ignored: `retrieval.graphLimit: 0`
(vector-only) and any `retrieval.vectorLimit` other than the default 30 (`UNSUPPORTED_RETRIEVAL_MODE`),
`output.includeEvidence: false`, `analysis.includeTeamSize: true`, and `includeBridgeMetrics`,
`includeCommunities`, `includePaths` set true (the capabilities passed to `checkSupported` are graph and vector
retrieval and all ranking metrics, with bridge, communities and paths off). `output.includeScores` and
`includeUncertainty` false are honored trivially (the response has neither).

Idempotency: the key is claimed with an `in_progress` record that is a lease of `syncDeadline + 10 s` from
`createdAt`. After the lease, or after the record's `expiresAtEpochSeconds`, the record is reclaimed by a
conditional put on the payload read, so two retries cannot both run. An unexpected internal failure is stored as
a retryable error. An asynchronous run ends `FAILED` with `TIMEOUT` after
`SCHOLARCHAIN_SERVICES_ASYNC_DEADLINE_MILLIS` (default 120000).

Same key and same body returns the stored result: the stored 200 body, or for an async request the job's state
(202 while it runs, 200 with the result once it succeeded). A stored failure that was retryable is run again.
Mapping to the workflow: `yearFrom`+`yearTo` become the window (one without the other is 422), the single
topic/venue/institution becomes a filter, `rankingMetric` becomes the metric, `graphLimit` the result count and
`maxPathDepth` the path depth. The REST request has no researcher-name field: names come from the planner.

### GET /v1/jobs/{jobId}

`JobStatus` (200) with state `QUEUED|RUNNING|SUCCEEDED|FAILED`, stage, timestamps, `resultUrl` (when
succeeded) and `error`. 400 for a malformed id, 404 `JOB_NOT_FOUND`.

### GET /v1/jobs/{jobId}/result

200 with the `SyncQueryResponse` of a succeeded job; the stored error status for a failed job; 409
`INCOMPATIBLE_STATE` (retryable) while it runs; 404 for an unknown job.

### GET /v1/evidence/{evidenceId}

`EvidenceView` (200): evidence type, work id, source dataset and record, snapshot, compact text (clipped to 1500
characters and flagged `textTruncated`), score, provenance with a `sha256:` text hash. 404 `EVIDENCE_NOT_FOUND`.

### GET /v1/queries/{queryId}/evidence

The link in every synchronous response. 200 `{"queryId": ..., "items": [EvidenceView, ...]}` (at most 100 items).
404 `QUERY_NOT_FOUND` for a query the service has not answered (or whose items expired).

### GET /v1/graph/nodes/{nodeId}

`GraphNodeView` for a researcher node (label from the identity cluster, a few allowlisted properties). Other node
kinds and unknown ids are 404 `NODE_NOT_FOUND`.

### GET /v1/graph/nodes/{nodeId}/neighbors

Query parameters (all others are 400): `direction` (`in|out|both`, default `both`), `depth` 1..3 (default 1),
`limit` 1..200 (default 50), `edgeTypes` (comma list from `COAUTHORED_WITH, AUTHORED, HAS_TOPIC,
AFFILIATED_WITH, PUBLISHED_IN`; a well-formed name outside the list is 422), `cursor` (opaque, bound to the
request scope; a cursor from another request is 400).
Only `COAUTHORED_WITH` is stored in the graph today, so the other four edge types return an empty page; coauthorship is
undirected, so the three directions return the same set. Exploration is bounded: at most 1000 nodes collected, 60
graph reads and 8 seconds per request (504 on overrun); every graph read carries the configured bounds. Items are
ordered by (distance, node id); `nextCursor` is present only when more items exist.

### GET /v1/explain/{queryId}

`ExplainView` (200): plan, stages executed with latency, retrieval modes, query-template ids, indexes queried,
metadata filters, prompt versions, model ids, evidence ids, claim-verification outcomes (status and reason
codes), latency and retry counters. Not tracked yet and therefore empty: `scoreComponents`, `cacheHits`
(the workflow does not record them). `indexesQueried` lists all five vector indexes whenever semantic evidence was
used (the workflow does not record which index served which hit). Contains no prompt text, key or reasoning.

### GET /healthz, GET /health, GET /readyz

`/healthz` and `/health` (alias): 200 `{"status":"ok","service":"<name>"}`, process alive, no dependency state.
`/readyz`: one cheap read per dependency (`key_value`, `graph`, `vectors`; retrieval checks `graph`, `vectors`).
200 `{"status":"ready",...}` when all are up, 503 `{"status":"not_ready","checks":[...]}` with a reason code
(`timeout`, `circuit_open`, `unavailable`) per failing check. No endpoint, key or exception text is exposed.

## Internal routes (retrieval)

All `POST /internal/v1/<op>` with header `X-Internal-Api-Key`; 401 otherwise. Each forwards to a core port; none
contains retrieval logic.

| Op | Body | Reply |
|---|---|---|
| `graph-query` | allowlisted template, typed parameters, bounds | rows and `truncated` (template outside `GraphTemplates.all` is 400) |
| `vector-query` | index, embedding, topK, optional metadata filter | hits |
| `vector-get` | index, key | the entry or `{"found":false}` |
| `embed` | at most 16 texts | vectors |

## Configuration

Core loaders: `RuntimeConfigLoader`, `StorageConfigLoader`, `EmbeddingConfigLoader` (all `SCHOLARCHAIN_*` names in
`modules/core/RUNTIME-CONTRACTS.md` and the storage and embedding contracts). Service-only variables:
`SCHOLARCHAIN_ENVIRONMENT` (`local` selects local mode; anything else is AWS mode), `SCHOLARCHAIN_SERVICES_PORT`,
`SCHOLARCHAIN_SERVICES_API_KEY`, `SCHOLARCHAIN_INTERNAL_API_KEY`, `SCHOLARCHAIN_SERVICES_RETRIEVAL_URL`,
`SCHOLARCHAIN_SERVICES_SYNC_DEADLINE_MILLIS`, `SCHOLARCHAIN_SERVICES_DOWNSTREAM_TIMEOUT_MILLIS`,
`SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT` (required by retrieval in AWS mode; a missing value is a start-up error),
`SCHOLARCHAIN_AWS_REGION`, `SCHOLARCHAIN_PROMPTS_DIR` is NOT read: prompts are found by `PromptRepository.locate()`
(system property `scholarchain.prompts.dir` or a `prompts/` folder above the working directory). The model key
variable is `SCHOLARCHAIN_ANTHROPIC_API_KEY` only (see Model and embedder selection below). Worker: `SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL`,
`SCHOLARCHAIN_SERVICES_EMBEDDING_DLQ_URL`. Secrets arrive as environment variables injected from Secrets Manager;
nothing here reads or logs a value.

### Model and embedder selection (Bedrock-only course deployment)

Stated from `Composition.scala` and the core config (`scholarchain-rag.v1.conf`, `RuntimeConfig.scala`):

- Embedder (retrieval and embedding-worker): the Voyage embedder is built only when
  `SCHOLARCHAIN_VOYAGE_API_KEY` is present and non-empty (`Composition.scala` lines 412-413 and 455-456);
  otherwise the Bedrock embedder is built (lines 414 and 457). The course deployment uses Bedrock, so
  `SCHOLARCHAIN_VOYAGE_API_KEY` must NOT be injected into these services.
- Answer model (query-api): `SCHOLARCHAIN_ANTHROPIC_API_KEY` is required at start-up only when
  `SCHOLARCHAIN_RAG_MODEL_PROVIDER=anthropic` (`Composition.scala` lines 347-349); a missing or empty value is a
  start-up error naming the variable. The core default provider is `bedrock`. The course deployment sets
  `SCHOLARCHAIN_RAG_MODEL_PROVIDER=bedrock` and
  `SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID=us.anthropic.claude-haiku-4-5-20251001-v1:0`
  (env name rule `SCHOLARCHAIN_RAG_<KEY>`, from `rag.model.provider` and `rag.model.bedrock-model-id`).
  `SCHOLARCHAIN_ANTHROPIC_API_KEY` need not be injected.
- `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` is defined in core `ModelConfig` (`models.generation-model-id`, `modules/core/src/main/scala/scholarchain/core/Config.scala:36` and `:80`) but is not consumed by rag or services.

## Local mode and the local session

Local mode wires storage's in-memory key-value and vector stores, a graph store that reads the Phase 07 L4
Neptune CSVs, text objects from the Phase 08 run, the deterministic local embedder and the rag fake model client.
Inputs: `modules/storage/target/loads/L4/**` and `modules/services/target/embedding-runs/L4/**`; a missing input
is a start-up error. No Spark or Parquet is read and no AWS client is built.

```
sbt "services/runMain scholarchain.services.LocalSessionMain run-1"
```

Runs: health, readiness, a synchronous query, evidence and explain for its answer, an idempotent repeat, a 409,
an asynchronous query to completion with its result, evidence and explain, a node, two neighbors pages, a
rejected depth, a 401 and a 422. Output in `modules/services/target/local-run/<run>/`: `transcript.json`,
`normalized.json` (trace ids, request ids and timestamps replaced), `logs.jsonl`. Compare two runs with
`diff <(grep -v '"run":' run-1/normalized.json) <(grep -v '"run":' run-2/normalized.json)`.

## OpenAPI-style summary

```yaml
openapi: 3.0.3
info: {title: ScholarChain query-api, version: v1}
servers: [{url: http://localhost:8080}]
components:
  securitySchemes:
    apiKey: {type: apiKey, in: header, name: X-API-Key}
  schemas:
    ApiError: {type: object, required: [error, message, traceId, retryable]}
paths:
  /healthz: {get: {security: [], responses: {'200': {description: process alive}}}}
  /readyz:  {get: {security: [], responses: {'200': {description: ready}, '503': {description: not ready}}}}
  /v1/query:
    post:
      security: [{apiKey: []}]
      parameters: [{name: Idempotency-Key, in: header, schema: {type: string, maxLength: 128}}]
      responses: {'200': {description: SyncQueryResponse}, '202': {description: AsyncQueryResponse},
        '400': {}, '401': {}, '409': {}, '422': {}, '502': {}, '503': {}, '504': {}}
  /v1/jobs/{jobId}:               {get: {security: [{apiKey: []}], responses: {'200': {description: JobStatus}, '404': {}}}}
  /v1/jobs/{jobId}/result:        {get: {security: [{apiKey: []}], responses: {'200': {}, '404': {}, '409': {}}}}
  /v1/evidence/{evidenceId}:      {get: {security: [{apiKey: []}], responses: {'200': {description: EvidenceView}, '404': {}}}}
  /v1/queries/{queryId}/evidence: {get: {security: [{apiKey: []}], responses: {'200': {}, '404': {}}}}
  /v1/graph/nodes/{nodeId}:       {get: {security: [{apiKey: []}], responses: {'200': {description: GraphNodeView}, '404': {}}}}
  /v1/graph/nodes/{nodeId}/neighbors:
    get:
      security: [{apiKey: []}]
      parameters:
        - {name: direction, in: query, schema: {enum: [in, out, both]}}
        - {name: depth, in: query, schema: {type: integer, minimum: 1, maximum: 3}}
        - {name: limit, in: query, schema: {type: integer, minimum: 1, maximum: 200}}
        - {name: edgeTypes, in: query, schema: {type: string}}
        - {name: cursor, in: query, schema: {type: string, maxLength: 512}}
      responses: {'200': {description: NeighborsPage}, '400': {}, '404': {}, '422': {}, '504': {}}
  /v1/explain/{queryId}:          {get: {security: [{apiKey: []}], responses: {'200': {description: ExplainView}, '404': {}}}}
```

## Known limits (disclosed)

- Jobs and idempotency records carry no caller id: any holder of the one API key can read any job. The
  idempotency key is scoped by a hash of the caller's key.
- An `in_progress` idempotency record left by a crashed process blocks that key (409, retryable) only until its
  lease ends (sync deadline plus 10 s), then it is reclaimed. A result over 300000 bytes is returned but stored
  as a retryable failure.
- `researchers` in an answer lists the resolved subject ids only; per-researcher metrics are in the evidence.
- The model client and the embedder are not behind the guard (the rag clients enforce their own timeouts); the
  four storage-backed ports are.
- The service image does not contain `prompts/`: AWS mode needs the prompts folder supplied (packaging).

## ops task: OpsLoadMain (Phase 14)

`scholarchain.services.OpsLoadMain` (file `OpsLoad.scala`) is a one-shot task, not a service: no listener, no loop. It
runs in the embedding-worker image. `packaging/entrypoint.sh` forwards no arguments and RunTask cannot override the
entry point, so the main is selected by the `SERVICE_MAIN` environment variable and every argument is a
`SCHOLARCHAIN_OPS_*` environment variable (`scripts/run-bounded-pipeline.sh` sets them per run through
`containerOverrides.environment`):

| Variable (flag) | Meaning |
|---|---|
| `SERVICE_MAIN=scholarchain.services.OpsLoadMain` | selects the main (image setting, not read by the program) |
| `SCHOLARCHAIN_OPS_NEPTUNE_SOURCE` (`--neptune-source`) | `s3://bucket/prefix/<runId>/` holding the Neptune CSVs (required) |
| `SCHOLARCHAIN_OPS_ITEMS_SOURCE` (`--items-source`) | `s3://bucket/prefix/<runId>/` holding `load-manifest.json`, `checksums.sha256`, `dynamodb/`, `embedding-inputs/` (required) |
| `SCHOLARCHAIN_OPS_RUN_ID` (`--run-id`) | last path segment of both sources (required) |
| `SCHOLARCHAIN_OPS_CODE_VERSION` (`--code-version`) | code version recorded in the provenance (required) |
| `SCHOLARCHAIN_OPS_MAX_VECTORS` (`--max-vectors`) | cap on rendered embedding documents, default and hard maximum 10000, 0 or above is exit 2 |
| `SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS` (`--neptune-timeout-seconds`) | default 1800, at most 21600 |
| `SCHOLARCHAIN_OPS_MAX_ITEMS` (`--max-items`) | optional cap on DynamoDB items, default and maximum 1000000 |
| `SCHOLARCHAIN_OPS_DRY_RUN` (`--dry-run [true\|false]`) | `true`: verify, render, count; send and write nothing |
| `SCHOLARCHAIN_IMAGE_TAG` | optional, recorded in the manifest when it is a valid token |

An argv flag wins over its variable. Settings come from the core loaders and the same variables the embedding worker
uses (`SCHOLARCHAIN_AWS_REGION`, `SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT`, `SCHOLARCHAIN_STORAGE_CURATED_BUCKET`,
`SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL`, the storage and embedding configuration). The task role supplies the
credentials; nothing secret is read or printed.

Flow: (1) plan, reads only: verify the four `embedding-inputs/*.jsonl` files (SHA-256 against `checksums.sha256` and
the manifest, line count against the manifest), convert them without Spark exactly as the test-scope `ParquetInput`
does (rejects counted by a short reason, never content), render with `EmbeddingDocumentRenderer` and apply the cap
(summaries first, then `paperLimit = cap - summaryDocs`, papers by ascending work id; still over is a refusal); (2)
`LiveLoad.run` (Neptune bulk load, DynamoDB items); (3) `EmbeddingProducer.enqueue` (texts to
`s3://<curated>/rag/text/<sha256>.txt` with putIfAbsent, jobs to the embedding queue); (4) `ops-manifest.json` to
`<items-source>ops-manifest.json` with putIfAbsent: never overwritten. The manifest holds the inputs and their checksums,
source run id and code version, parameters, image tag, counts by type, skip and reject reasons, the producer report and
the LiveLoad provenance. It is the completion record: a failed or refused run writes none (its record is the JSON line
on stdout, CloudWatch), so a corrected rerun of the same run id can still write it. A rerun repeats the idempotent steps
(items unchanged, texts unchanged, same job ids) and leaves an existing manifest as it is.

Dry run: nothing is written to S3 and nothing is sent. The manifest (`dryRun: true`) is one JSON line
(`event: ops.manifest.dry-run`) on stdout. It is not persisted because the ops role may write only
`.../ops-manifest.json`, and a dry-run object at that key would block the real run.

Exit codes: 0 completed or dry-run verified; 1 runtime failure (Neptune, store, queue, manifest write); 2 usage or
configuration (bad argument, missing setting, bad source URI); 3 refusal (checksum or source invalid, cap exceeded, no
documents, a rendered number absent from its source row, documents rejected by the producer).
