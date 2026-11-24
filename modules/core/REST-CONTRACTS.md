# Core REST contracts (Phase 10)

Owner: domain-engineer. Additive: no earlier record, enum, wire form, default or configuration contract
changes. Code: `modules/core/src/main/scala/scholarchain/core/rest/` (package `scholarchain.core.rest`, a
separate package so that `QueryRequest` does not clash with the rag runtime's `QueryRequest`). Core depends
on no other module and contains no AWS code. Field names follow the course specification's REST examples;
where this document differs from the spec, the difference is listed under "Deviations".

## Records and frozen examples

Examples are classpath resources under `contracts/`. A body in the HTTP API has no `schemaVersion`.

| Scala type (file) | Used for | Frozen example |
|---|---|---|
| `QueryRequest`, `QueryConstraints`, `AnalysisOptions`, `RetrievalOptions`, `OutputOptions`, `RestCapabilities` (`QueryRequest.scala`) | `POST /v1/query` body | `rest-query-request.example.json` |
| `SyncQueryResponse`, `AsyncQueryResponse` under sealed `QueryResponse`; `AnswerView`, `ResearcherView`, `ClaimView`, `QueryLinks` (`RestViews.scala`) | `POST /v1/query` results, `mode` discriminates | `rest-query-response-sync.example.json`, `rest-query-response-async.example.json` |
| `JobStatus` | `GET /v1/jobs/{jobId}` | `rest-job-status.example.json` |
| `EvidenceView`, `EvidenceProvenanceView`, `GraphPathView` | `GET /v1/evidence/{evidenceId}` | `rest-evidence.example.json` |
| `GraphNodeView`, `GraphEdgeView`, `NeighborView`, `NeighborsPage`, `NeighborsRequest`, `NeighborCursor` (`Neighbors.scala`) | graph node and neighbors | `rest-graph-node.example.json`, `rest-neighbors-page.example.json` |
| `ExplainView` and its parts (`PlanView`, `StageRunView`, `ScoreComponentView`, `PromptVersionView`, `ModelVersionView`, `ClaimOutcomeView`, `CacheStatView`, `ExplainCounters`) | `GET /v1/explain/{queryId}` | `rest-explain.example.json` |
| `ApiError`, `ApiErrorCode`, `RestRejection` | every error body | `rest-api-error.example.json` |
| `HealthView`, `ReadinessView`, `DependencyCheck` | `GET /healthz`, `GET /readyz` | `rest-health.example.json`, `rest-readiness.example.json` |
| `JobRecord`, `IdempotencyRecord`, `RestKeys` (`RestPersisted.scala`) | internal payloads in QuerySession | `rest-job-record.example.json`, `rest-idempotency-record.example.json` |

Enums (`RestEnums.scala`) use the spec's wire names: `QueryMode` (`synchronous`, `asynchronous`),
`RankingMetric` (`DISTINCT_COAUTHOR_COUNT`, `WEIGHTED_DEGREE`, `BETWEENNESS`, `PAGERANK`,
`CLUSTERING_COEFFICIENT`, `BRIDGE_SCORE`; each maps to a `CollaborationMetric`), `JobState` (`QUEUED`,
`RUNNING`, `SUCCEEDED`, `FAILED`), `JobStage` (the workflow stages, for example `CLAIM_VERIFICATION`),
`ClaimStatus` and `EvidenceKind` (one-to-one with `ClaimVerificationStatus` and `EvidenceType`),
`NeighborDirection` (`in|out|both`), `EdgeType` (`COAUTHORED_WITH`, `AUTHORED`, `HAS_TOPIC`,
`AFFILIATED_WITH`, `PUBLISHED_IN`: the closed allowlist), `RetrievalMode`, `ReadinessState`,
`DependencyState`, `IdempotencyState`, and the closed `ApiErrorCode`.

## Entry points

- `QueryRequest.parse(body)`: strict, total, returns `Either[RestRejection, QueryRequest]`.
  `QueryRequest.validate(r)` for a request built in code. `QueryRequest.checkSupported(r, caps)` returns the
  422 rejections for a deployment. `QueryRequest.requestHash(r)` is the idempotency body hash.
- `NeighborsRequest.fromParams(nodeId, params)`, `NeighborsRequest.decodedCursor`.
- `RestJson.write(view)` validates, then serializes; `RestJson.read[A](json)` decodes, then validates.
  Persisted payloads use `Wire.write` / `Wire.read` like every other stored record.
- `RestRejection` carries the `ApiErrorCode` (so the HTTP status), a field name and a fixed reason.
  `rejection.toApiError(traceId)` builds the body. Reasons never contain a request value.

## 400 versus 422

The validation result is a `RestRejection`; `isMalformed` is a 400 code, `isUnsupported` a 422 code.

- 400: invalid JSON, nesting above 8, body above 32768 bytes, a non-object body, a wrong type, a missing
  `question`, a blank, over-long or control-character string, a list above 10 entries or with duplicates,
  an out-of-range number, year order, an unknown field (`UNKNOWN_FIELD`), an out-of-range, repeated or
  unknown neighbors parameter, a malformed id, cursor or `Idempotency-Key`.
- 422: valid input that names something outside a closed capability set: a well-formed ranking metric not in
  `RankingMetric` or not enabled (`UNSUPPORTED_CONSTRAINT`), a retrieval mode the deployment does not offer
  (`UNSUPPORTED_RETRIEVAL_MODE`), a request that selects no retrieval mode (`graphLimit` and `vectorLimit`
  both 0, `UNSUPPORTED_RETRIEVAL_PLAN`), analysis the deployment does not offer, and a well-formed edge type
  not on the allowlist. A malformed token for either (lower case, wrong characters) stays 400.

## Bounds (every string, list and map is bounded)

Question 1..2000 characters, no control characters. Constraint lists at most 10 distinct entries of 1..128
characters. Years four digits, `yearFrom <= yearTo`. `graphLimit` 0..100, `vectorLimit` 0..100,
`maxPathDepth` 1..6. Neighbors: depth 1..3 (default 1), limit 1..200 (default 50), at most five distinct
edge types (none means all five), cursor at most 512 characters of base64url. Evidence text at most 1500
characters (clipped and flagged by `EvidenceView.from`). Summary at most 4000, researchers at most 100,
claims at most 100, explain lists at most 50 entries (200 evidence ids). Persisted `resultJson` and
`responseJson` at most 300000 UTF-8 bytes (a DynamoDB item is at most 409600). The full table of constants is
`RestLimits`.

## Wire decisions

1. **Identifiers.** The spec's examples show `qry-01J...`, `job-01J...`, `openalex:A123`, `ev-work-33`. Core
   keeps its own reference forms: `query:<token>` (`RestIds.checkQueryId`: the Phase 09 workflow derives
   `query:<64 hex>`), `job:<token>` (`RestIds.newJobId(seed)` gives `job:<32 hex>`), `researcher:sc1:...` or
   `researcher:orcid:...`, `evidence:<64 hex>`, `claim:<token>`, `component:...`, `community:...`, `work:...`,
   `gedge:<64 hex>`. The ids are validated with the existing `Ids` functions. Path segments carry the id
   percent-encoded (`RestPaths.encode`; `:` stays literal; `RestPaths.decode` is strict and total). A
   researcher id the pipeline did not resolve is never invented.
2. **Null versus absent.** Core's frozen rule stands: an absent optional value is omitted, a `null` on read is
   absent. The one exception is `JobStatus`, which always writes `resultUrl` and `error` (`null` when
   absent), as the spec's job example shows.
3. **Versioning.** Bodies carry no `schemaVersion`, as in the spec's examples. The version is carried by the
   URL path (`/v1`, `RestContracts.ApiVersion`) and by the contract name and version
   (`RestContracts`, for example `rest.query-response/1`), which the service may send in the
   `X-ScholarChain-Contract` response header (`RestHeaders.Contract`). A body change that breaks a reader
   is a new path version and a new contract version. Persisted payloads (`JobRecord`,
   `IdempotencyRecord`) carry `schemaVersion` like every stored record, because storage reads them with
   `Wire.read`.
4. **Unknown fields.** A request with an unknown field is rejected (400 `UNKNOWN_FIELD`) at every nesting
   level and for query parameters. Reason: ignoring a misspelled field (`yearFrm`) silently widens the
   query, and the idempotency hash must distinguish two different bodies. The message names the parent
   object only, never the unknown key, so a hostile key cannot reach a log or a response. Duplicate keys in
   one JSON object follow the parser (last wins). Responses and persisted payloads are read tolerantly
   (unknown fields ignored), the rule of every other core record.
5. **Enum wire names.** Phase 09 core enums are lower-case snake_case. REST enums use the spec's names
   (`RUNNING`, `TEXT_CHUNK`, `COAUTHORED_WITH`). `ClaimStatus.from` and `EvidenceKind.from` map the core
   enums one-to-one. `RankingMetric.DISTINCT_COAUTHOR_COUNT` is the coauthor-graph degree.
6. **Evidence hash.** The spec shows `textHash: "sha256:..."`; the view writes `sha256:` plus the 64 hex of
   the core `contentHash`. `provenance.schemaVersion` is the fixed label `evidence-v1`.
7. **Defaults of a request.** Only `question` is required. Absent sections take `QueryRequest.Defaults`:
   no constraints; no ranking metric and all analysis flags false; `graphLimit` 100, `vectorLimit` 30,
   `maxPathDepth` 6; all output flags true. `retrieval` fields have no way to express an unbounded request.
8. **Researcher fields.** Only `researcherId` is required. Display name, publication count, distinct coauthor
   count, weighted degree, average coauthors per paper, maximum team size, team work ids, component,
   community and bridge metric are optional: a value the pipeline did not produce is absent.
9. **Idempotency body hash.** `QueryRequest.requestHash` hashes the canonical JSON of the normalized request,
   so whitespace and absent-versus-default sections do not change it.
10. **Neighbors cursor.** `NeighborCursor` is base64url of `{v, k, s}`: last key and a hash of the scope (node,
    direction, depth, edge types). `NeighborsRequest.decodedCursor` rejects a cursor from a different scope.
    It is not authenticated (core holds no secret). The service must treat the key as untrusted and keep
    its own bounds and authorization on every page.
11. **Explain view.** Every spec bullet has a field: `queryPlan`, `retrievalModes`, `queryTemplateIds`,
    `indexesQueried`, `metadataFilters`, `scoreComponents`, `promptVersions`, `modelVersions`, `evidenceIds`,
    `claimOutcomes`, `counters` (latency and retries), `cacheHits`, plus `stagesExecuted`. Prompt, model and
    score fields hold ids and numbers only. Apart from the caller's own `question`, every string is a
    token or a reason code, so no prompt text, secret or reasoning has a place to go.
    `PromptVersionView.from`, `ModelVersionView.from` and `ClaimOutcomeView.from` build entries from
    `ModelCall` and `ClaimVerification` (which hold hashes and ids only).

## Error codes

| Status | Codes (default `retryable`) |
|---|---|
| 400 | `MALFORMED_REQUEST`, `INVALID_FIELD`, `UNKNOWN_FIELD` (false) |
| 401 | `UNAUTHENTICATED` (false) |
| 403 | `FORBIDDEN` (false) |
| 404 | `NOT_FOUND`, `QUERY_NOT_FOUND`, `JOB_NOT_FOUND`, `NODE_NOT_FOUND`, `EVIDENCE_NOT_FOUND` (false) |
| 409 | `IDEMPOTENCY_CONFLICT` (false), `INCOMPATIBLE_STATE` (true: for example the result of a running job) |
| 422 | `UNSUPPORTED_CONSTRAINT`, `UNSUPPORTED_RETRIEVAL_MODE`, `UNSUPPORTED_RETRIEVAL_PLAN` (false) |
| 429 | `RATE_LIMITED` (true), `BUDGET_EXCEEDED` (false) |
| 502 | `DOWNSTREAM_FAILURE` (true) |
| 503 | `NOT_READY`, `UNAVAILABLE` (true) |
| 504 | `TIMEOUT` (true) |
| 500 | `INTERNAL` (false) |

`ApiError.traceId` is a token of 1..128 characters (`TraceIds.isValid`). A caller-supplied trace id is
accepted only if valid (`TraceIds.accept`); otherwise the service generates one and never echoes the value.

## Persisted payloads (human decision 5)

Stored in the QuerySession table through `KeyValueStore`. `QuerySessionRecord` and its table contract
(`pk = <sessionId>`, no sort key, TTL always) are untouched.

| Item | Partition key | Payload | TTL bounds |
|---|---|---|---|
| job | `job#<id>` (`RestKeys.jobPartition("job:<id>")`) | `JobRecord` | 60 s to 7 days after `createdAt` |
| idempotency | `idem#<sha256(scope, key)>` (`RestKeys.idempotencyPartition`) | `IdempotencyRecord` | 60 s to 24 hours after `createdAt` |

Both prefixes differ from `session-...`, so the item kinds cannot collide. The key hash covers the caller
scope (never the secret itself) and the client key, so a raw `Idempotency-Key` never appears in a key and two
callers never share a record. `JobRecord.toStatus` builds the public `JobStatus`. An `IdempotencyRecord` is
`in_progress` (claim taken, no outcome), `completed` (exactly one of `responseJson` or `jobId`) or `failed`
(`error`). Same key and same `requestHash` replays; same key with another `requestHash` is 409. The stored
JSON must read back as a valid response for the same `queryId`, so a corrupted record is rejected by its
contract. Both records can be written with the storage `ItemWrites` helper like `QuerySessionRecord`.

## Deviations from the spec excerpt

- Id forms (see 1). `links.evidence` and `links.explain` are relative paths with the core id.
- `ApiErrorCode` is a closed set. The spec shows only `UNSUPPORTED_RETRIEVAL_MODE`.
- Extra fields beyond the spec's examples: `EvidenceView.textTruncated`, `GraphPathView`, `PlanView`,
  `StageRunView`, `ReadinessView.checks`, and the response fields of `NeighborsPage`.
- `rankingMetric` is optional (the spec example always sends it), and unknown metric names are 422.

## Contract tests to write (test-engineer)

Each item is a test of behavior listed above; none exists yet.

- Every frozen example under `contracts/rest-*.example.json` reads, validates and re-serializes byte for
  byte (`RestJson.read` then `RestJson.write`; `Wire.read` / `Wire.write` for the two records). The sync
  and async response examples read as `QueryResponse` and pick the right case; no `$type` appears on write.
- `JobStatus` writes `"resultUrl":null` and `"error":null` when absent; reads accept null and absent. No
  other optional field is written as `null`.
- `QueryRequest.parse`: minimal body takes the defaults; the spec's request parses; each of the 400 cases
  (invalid JSON, array body, unknown field at top level, in each section, wrong types, missing, blank,
  2001-character and control-character question, 11 list entries, duplicates, year order, a fractional
  year, `graphLimit` 101 and -1, `vectorLimit` 101, `maxPathDepth` 0 and 7, body above 32768 bytes, nesting
  above 8) with the exact code; the message never contains the unknown key or any submitted value.
- 422: `rankingMetric` `CITATIONS` is 422, `degree` (lower case) and `3` are 400; `checkSupported` for each
  disabled capability and for both limits 0.
- `requestHash` is equal for whitespace variants and for an absent section versus its defaults, and differs
  for any changed field or list order.
- `RestRejection.isMalformed` / `isUnsupported` and `toApiError` (traceId validated, `retryable` is the
  code default). `ApiErrorCode`: every status in `Statuses` has a code, no other status exists, wire names
  unique, retryable defaults per the table, `forStatus`.
- `NeighborsRequest.fromParams`: defaults; depth 0 and 4, limit 0 and 201, non-digit, repeated parameter,
  unknown parameter, unknown direction (400); unknown edge type (422); lower-case or empty edge type,
  repeated edge type (400); six edge types; `effectiveEdgeTypes`; cursor round trip, cursor from another
  scope, tampered and oversized cursor, `NeighborCursor.decode` on non-base64 and on valid base64 of
  non-JSON.
- `NeighborsPage` contract: items above `limit`, distance above depth, bad cursor.
- `EvidenceView.from`: text above 1500 characters is clipped, flagged and does not split a surrogate pair;
  control characters replaced; `workId` only for work ids; textHash `sha256:` form; more than 20
  provenance inputs is an error; contract rejects a bad hash, a missing text with `textTruncated`, a path
  whose edge count is wrong.
- `GraphNodeView.from` caps properties at 16 and values at 256; `GraphEdgeView.from` is `None` for an edge
  type off the allowlist; `EdgeType.fromGraphLabel` case handling.
- `SyncQueryResponse` carries `abstained: Boolean` (always written) and `abstainReason: Option[String]`
  (token, at most 64 characters; required and non-empty when `abstained`, absent otherwise, omitted when
  `None`). Field order: `queryId, mode, answer, claims, abstained, abstainReason, links`.
  `QueryResponse.sync(queryId, answer, claims, abstained = false, abstainReason = None)`. Additive in
  Phase 10 (repair R1); the frozen example carries `"abstained":false`.
- `SyncQueryResponse` / `AsyncQueryResponse` contracts: wrong `mode`, async `status` other than
  `QUEUED`/`RUNNING`, duplicate claim ids, a verified claim without evidence, 101 claims or researchers,
  non-finite numbers, invalid ids, abstained without reason, reason without abstained. `ResearcherView` with only `researcherId` serializes with no other
  field.
- `JobStatus` / `JobRecord` state rules (succeeded needs result and no error, failed needs error, queued
  has no stage, `updatedAt` before `createdAt`, expiry bounds 60 s and 7 days, resultJson for another
  query, resultJson above 300000 bytes). `JobRecord.toStatus` sets `resultUrl` only when succeeded.
- `IdempotencyRecord` state rules (all three states, both-or-neither of `jobId` and `responseJson`,
  24 hour bound). `RestKeys`: prefixes, partition differs per scope and per key, raw key absent from the
  partition, `isRestPartition`, no overlap with `session-` keys.
- `ExplainView`: the example validates; non-token strings, over-long lists, negative counters,
  mismatched `queryPlan.queryId` rejected; `PlanView.from`, `PromptVersionView.from`,
  `ModelVersionView.from`, `ClaimOutcomeView.from` map a `QueryPlanV2`, `ModelCall` and
  `ClaimVerification`; the serialized view contains no prompt text.
- `ReadinessView`: `of` computes ready/not_ready; the contract rejects a status that disagrees with the
  checks and a reason on an `up` check. `HealthView` status must be `ok`.
- `RestPaths`: encode/decode round trip for a work id with `/`, `?`, `#`, space and non-ASCII; `decode`
  is `None` for `%zz`, `%`, `%c3`. `RestIds`, `TraceIds`, `IdempotencyKeys` accept and reject cases.
- No frozen contract changed: the existing contract suites still pass unchanged.
