# ScholarChain API reference

Draft (Phase 17a). Source of truth: [modules/services/API.md](../modules/services/API.md) (service-engineer), the frozen wire contracts in [modules/core/REST-CONTRACTS.md](../modules/core/REST-CONTRACTS.md) with their JSON examples under `modules/core/src/main/resources/contracts/rest-*.example.json`, the error enum in `modules/core/src/main/scala/scholarchain/core/rest/RestEnums.scala` and the route table in `modules/services/src/main/scala/scholarchain/services/QueryService.scala` (lines 851 to 858) and `RetrievalApi.scala` (line 137). This file adds nothing to those; if they disagree with this file, they win. The API was exercised locally only (in-process and loopback tests, a scripted local session); it has never answered a request on AWS: `[[PENDING-AWS: Phase 14 smoke test and first real query]]`.

Base URL (deployed): the Terraform output `query_api_url` (HTTP only while the ALB has no certificate; ingress limited to one /32). Local mode: `http://localhost:8080` via `sbt "services/runMain scholarchain.services.LocalSessionMain run-1"`.

## 1. Conventions

| Item | Rule |
|---|---|
| Authentication | every `/v1` route needs header `X-API-Key` equal to `SCHOLARCHAIN_SERVICES_API_KEY` (a single shared key; digests compared in constant time). `/healthz`, `/health`, `/readyz` need no key |
| Internal routes | retrieval `POST /internal/v1/<op>` need `X-Internal-Api-Key` equal to `SCHOLARCHAIN_INTERNAL_API_KEY` |
| Trace IDs | `X-Trace-Id` and `X-Request-Id` accepted when valid tokens, otherwise generated; both returned on every response and in every error body |
| Idempotency | optional `Idempotency-Key` header on `POST /v1/query` (token of 1 to 128 characters) |
| Body | at most 32,768 bytes; unknown fields are rejected |
| Error body | `{"error": <code>, "message": <fixed text>, "traceId": ..., "retryable": <bool>}`; messages never contain a request value, key or stack trace |

## 2. Public routes (query-api, port 8080)

| Method and path | Purpose | Success | Main errors |
|---|---|---|---|
| `POST /v1/query` | ask a question; synchronous answer within `SCHOLARCHAIN_SERVICES_SYNC_DEADLINE_MILLIS` (default 20,000 ms), else a job | 200 `SyncQueryResponse` (`mode: synchronous`, `answer`, `claims` with evidence IDs and verification status, `links`); 202 `AsyncQueryResponse` (`mode: asynchronous`, `jobId`, `status`, `statusUrl`) | 400, 401, 409 (`IDEMPOTENCY_CONFLICT`, `INCOMPATIBLE_STATE`), 422, 429, 502, 503, 504 |
| `GET /v1/jobs/{jobId}` | job state `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, with `stage`, timestamps, `resultUrl`, `error` | 200 `JobStatus` | 400, 404 `JOB_NOT_FOUND` |
| `GET /v1/jobs/{jobId}/result` | the stored result of a succeeded job | 200 `SyncQueryResponse` | 404; 409 `INCOMPATIBLE_STATE` while it runs; the stored error status for a failed job |
| `GET /v1/queries/{queryId}/evidence` | evidence items of an answer (at most 100) | 200 `{"queryId", "items": [EvidenceView]}` | 404 `QUERY_NOT_FOUND` |
| `GET /v1/evidence/{evidenceId}` | one evidence item: type, work ID, source dataset and record, snapshot, compact text (clipped to 1,500 characters, flagged `textTruncated`), score, provenance with a `sha256:` text hash | 200 `EvidenceView` | 404 `EVIDENCE_NOT_FOUND` |
| `GET /v1/graph/nodes/{nodeId}` | a researcher node (label from the identity cluster, allowlisted properties) | 200 `GraphNodeView` | 404 `NODE_NOT_FOUND` (other node kinds and unknown IDs) |
| `GET /v1/graph/nodes/{nodeId}/neighbors` | bounded neighbor exploration | 200 `NeighborsPage` | 400, 404, 422, 504 |
| `GET /v1/explain/{queryId}` | plan, stages executed with latency, retrieval modes, query-template IDs, indexes queried, metadata filters, prompt versions, model IDs, evidence IDs, claim-verification outcomes, latency and retry counters; no prompt text, key or reasoning | 200 `ExplainView` | 404 |
| `GET /healthz`, `GET /health` | liveness (alias) | 200 `{"status":"ok","service":"<name>"}` | none |
| `GET /readyz` | one cheap read per dependency (`key_value`, `graph`, `vectors`; also prompts and config) | 200 `{"status":"ready",...}` | 503 `{"status":"not_ready","checks":[...]}` with a reason code (`timeout`, `circuit_open`, `unavailable`) per failing check |

### 2.1 `POST /v1/query` request

Frozen example: [rest-query-request.example.json](../modules/core/src/main/resources/contracts/rest-query-request.example.json). Fields (all validated; every string, list and map is bounded):

| Field | Meaning | Notes from the contract |
|---|---|---|
| `question` | the natural-language question | required; stored as data, never as an instruction |
| `constraints` | `yearFrom`, `yearTo` (both or neither), `topics`, `institutions`, `venues` | topics, venues and institutions with more than one entry or with whitespace are 422 |
| `analysis` | `rankingMetric` (`DISTINCT_COAUTHOR_COUNT`, `WEIGHTED_DEGREE`, `BETWEENNESS`, `PAGERANK`, `CLUSTERING_COEFFICIENT`, `BRIDGE_SCORE`), `includeTeamSize`, `includeBridgeMetrics`, `includeCommunities`, `includePaths` | this deployment refuses `includeTeamSize: true` and sets of bridge, communities and paths true with 422 |
| `retrieval` | `graphLimit`, `vectorLimit`, `maxPathDepth` | `graphLimit: 0` (vector-only) and any `vectorLimit` other than the default 30 are 422 `UNSUPPORTED_RETRIEVAL_MODE` |
| `output` | `includeEvidence`, `includeScores`, `includeUncertainty` | `includeEvidence: false` is 422 |

The REST request has no researcher-name field; names come from the planner. Same `Idempotency-Key` and same body returns the stored result; a different body is 409 `IDEMPOTENCY_CONFLICT`; a retryable stored failure is run again. An answer that abstained is still 200 with `abstained: true` and an `abstainReason` token such as `no_verified_claims`.

### 2.2 Neighbors query parameters

`direction` (`in|out|both`, default `both`), `depth` 1 to 3 (default 1), `limit` 1 to 200 (default 50), `edgeTypes` (comma list from `COAUTHORED_WITH, AUTHORED, HAS_TOPIC, AFFILIATED_WITH, PUBLISHED_IN`), `cursor` (opaque, bound to the request scope). All other parameters are 400. Only `COAUTHORED_WITH` is stored in the graph today, so the other four edge types return an empty page. Exploration is bounded to 1,000 nodes, 60 graph reads and 8 seconds.

## 3. Internal routes (retrieval, not exposed by the ALB)

| Route | Body | Reply |
|---|---|---|
| `POST /internal/v1/graph-query` | allowlisted template name, typed parameters, bounds (the six templates `neighbors`, `coauthor-edge`, `shortest-path`, `component`, `community`, `researcher-metrics`) | rows and `truncated`; a template outside the allowlist is 400 |
| `POST /internal/v1/vector-query` | index, embedding, topK, optional metadata filter | hits |
| `POST /internal/v1/vector-get` | index, key | the entry or `{"found":false}` |
| `POST /internal/v1/embed` | at most 16 texts | vectors |

The embedding-worker has no listener (it consumes SQS).

## 4. Error codes (from `ApiErrorCode`)

| Code | Status | Retryable |
|---|---|---|
| `MALFORMED_REQUEST`, `INVALID_FIELD`, `UNKNOWN_FIELD` | 400 | no |
| `UNAUTHENTICATED` | 401 | no |
| `FORBIDDEN` | 403 | no (no route produces it: one API key) |
| `NOT_FOUND`, `QUERY_NOT_FOUND`, `JOB_NOT_FOUND`, `NODE_NOT_FOUND`, `EVIDENCE_NOT_FOUND` | 404 | no |
| `IDEMPOTENCY_CONFLICT` | 409 | no |
| `INCOMPATIBLE_STATE` | 409 | yes |
| `UNSUPPORTED_CONSTRAINT`, `UNSUPPORTED_RETRIEVAL_MODE`, `UNSUPPORTED_RETRIEVAL_PLAN` | 422 | no |
| `RATE_LIMITED` | 429 | yes (bounded query queue full: 4 running, 16 queued) |
| `BUDGET_EXCEEDED` | 429 | no |
| `DOWNSTREAM_FAILURE` | 502 | yes |
| `NOT_READY`, `UNAVAILABLE` | 503 | yes |
| `TIMEOUT` | 504 | yes (also a downstream timeout; an asynchronous run ends `FAILED` with `TIMEOUT` after 120,000 ms by default) |
| `INTERNAL` | 500 | no |

## 5. Examples

Local (fixture) session, which exercises health, readiness, a synchronous query, evidence and explain, an idempotent repeat, a 409, an asynchronous query with its job and result, a node, two neighbors pages, a rejected depth, a 401 and a 422:

```bash
sbt "services/runMain scholarchain.services.LocalSessionMain run-1"
# output: modules/services/target/local-run/run-1/transcript.json, normalized.json, logs.jsonl
```

Deployed (`[[PENDING-AWS]]`, not replayed; the key comes from a mode-600 file outside the repository):

```bash
KEY="$(cat "$SC_SMOKE_API_KEY_FILE")"
curl -sS -X POST "$QUERY_API_URL/v1/query" -H "X-API-Key: $KEY" -H "Idempotency-Key: demo-1" -H "Content-Type: application/json" \
  --data @modules/core/src/main/resources/contracts/rest-query-request.example.json
curl -sS "$QUERY_API_URL/v1/graph/nodes/<researcherId>/neighbors?depth=2&limit=2" -H "X-API-Key: $KEY"
```

## 6. Known limits (from API.md)

- Jobs and idempotency records carry no caller ID: any holder of the one API key can read any job; the idempotency key is scoped by a hash of the caller's key.
- A result over 300,000 bytes is returned but stored as a retryable failure.
- `researchers` in an answer lists resolved subject IDs only; per-researcher metrics are in the evidence.
- The model client and embedder are not behind the downstream guard (the rag clients enforce their own timeouts).
- `ClaimView.uncertainty` is not set; `scoreComponents` and `cacheHits` in the explain view are not tracked yet and are empty.
- API.md states "The service image does not contain `prompts/`"; the Phase 11 gate records that the three rebuilt images do contain `prompts/` ([phase-11 gate](agent-runs/phase-11-adversarial-review/human-gate.md) section 2), so that API.md sentence is out of date. It is owned by service-engineer and was not changed by the documentation draft.

## 7. Machine-readable summary

An OpenAPI-style summary is at the end of [modules/services/API.md](../modules/services/API.md) ("OpenAPI-style summary"). No separate OpenAPI file exists in the repository.
