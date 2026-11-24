# Phase 10 — RESTful microservices (work order)

Phase folder: `docs/agent-runs/phase-10-services/`
Spec sections: "Phase 10: expose the runtime through RESTful microservices", "REST API contract", "Error model", "Observability requirements", "Security requirements".
Input: the committed Phase 09 runtime (`QueryWorkflow` over core ports) and the Phase 12 service contract in `infra/terraform/INFRA-CONTRACTS.md` §Services (service names, ports, environment variables). **No AWS or model calls in this phase**; everything runs against fakes. Deployment is Phase 14.

Ownership follows `docs/agents.md` §1 and §1.1.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A0 | build-engineer (+ dependency-updater for version values) | `build.sbt`, `project/**`, `packaging/**` | always (service mains and images) |
| A1 | domain-engineer | `modules/core/**` except `src/test/**` | only if a contract in §3 is missing |
| R | rag-engineer | `modules/rag/**` except `src/test/**`, `prompts/**` | always (Phase 09 carry-overs) |
| B | service-engineer | `modules/services/**` except `src/test/**` | always |
| C | test-engineer | `modules/services/src/test/**`, `modules/rag/src/test/**`, `modules/core/src/test/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code, tests or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. Canonical test command, exactly: `sbt check; echo "exit=$?" > .claude/.last-test-run`, run by you from the repo root, once per work order (15–38 minutes). **Never while the parallel worktree runs sbt**; ask the human if unsure.
4. Speed rule: targeted commands while iterating (`sbt "services/testOnly *"`); `testOnly *` for counts; `scalafmt` on new files before CHECK. One sbt user at a time.
5. Append a `## Progress checkpoint` to `agent-report.md` after each work order.
6. **No AWS, model or embedding calls; no credentials read.** Never set or read `ANTHROPIC_API_KEY`.
7. Docker: building images locally is allowed only if Docker is installed and running; otherwise record NOT RUN. Never push an image.
8. Do not stage or commit. Unattended mode: continue without asking, except at the STOP points in §7.
9. Repair policy: fix HIGH and MEDIUM findings on correctness, security, grounding or failure handling; defer LOW with disclosure. A CHECK failure that is only a timeout or out-of-memory error in a module untouched by this phase may be rerun once, unchanged; record both runs.

## 1. Preconditions (orchestrator, read-only)

- `git status --short` is clean, and HEAD descends from `docs/agent-runs/phase-09-runtime-agents/commit.txt`. `infra/terraform/INFRA-CONTRACTS.md` exists on this branch (the Phase 12 merge). If either fails, STOP.
- The canonical command exits 0. Record the baseline (Phase 09 ended at 2,423).
- From INFRA-CONTRACTS.md §Services, record each service name, its port, health path, environment variables and the AWS resources it may use. Phase 10 implements **exactly** these services; if the contract is ambiguous or seems wrong, STOP and ask.
- From `CLAUDE.md` and `build.sbt`, record that `services` may depend on `core`, `rag` and `storage`.
- From the Phase 09 gate, list the carry-overs (N5, N6, N7, L2).

## 2. A0 — build-engineer + dependency-updater

- One runnable main per service from the contract. Use the JDK's built-in HTTP server (`com.sun.net.httpserver`) and the existing uPickle, with no new web framework, unless the contract requires otherwise. If a library is truly needed, dependency-updater pins it and the reason is reported.
- Container images per service in `packaging/**` (or via the existing `dockerLocal` alias): the pinned Temurin 17 base image from the version ledger, a non-root user, percentage-based memory flags (`-XX:MaxRAMPercentage`), a health check, and the image tag from the Git SHA passed in by the build. No secrets or endpoints baked in. Spark SQL must not be on any service runtime classpath.
- Run the canonical command and report.

## 3. A1 — domain-engineer (conditional)

Add only what is missing, versioned, with validation and frozen JSON examples: the REST request and response models from the spec's REST API contract (`QueryRequest`, synchronous and asynchronous `QueryResponse`, `JobStatus`, `EvidenceView`, `NeighborsPage` with cursor, `ExplainView`), and `ApiError` (error code, message, trace ID, retryable). The JSON field names follow the spec's examples.

## 4. Work orders

### R — rag-engineer (Phase 09 carry-overs; runs first)
1. **N5:** the deterministic verifier must reject a claim that presents a joint publication count (edge weight) as a researcher's total publication count or degree, and the reverse. Check the metric's semantic kind, not only the number.
2. **N6, N7:** close the similar wording gaps the review describes, and document N5–N7 in RUNTIME-AGENTS.md.
3. **L2:** reject `ANTHROPIC_API_KEY` as a configured key variable name, with a clear error; only `SCHOLARCHAIN_ANTHROPIC_API_KEY` is valid.
4. Rerun the composed benchmark twice through the existing services test harness (orchestrator routes the run); results must be identical and still 44/44, or the report explains each change.

### B — service-engineer
Everything goes in `modules/services/**` (not `src/test/**`), plus `modules/services/API.md` (the route reference and an OpenAPI-style description).

**Routes** (path-versioned, JSON), placed on the services the contract names:
- `POST /v1/query`: validate; create a query ID and an idempotency record (`Idempotency-Key` header; same key and same body returns the stored result, same key and a different body returns 409); run synchronously within the configured deadline, otherwise return 202 with a job reference and continue asynchronously.
- `GET /v1/jobs/{jobId}` and `GET /v1/jobs/{jobId}/result`: state, timestamps, stage, error, result link (job state stored through the key-value port).
- `GET /v1/evidence/{evidenceId}`: provenance, source record, source URI, graph path or text pointer, compact content.
- `GET /v1/graph/nodes/{nodeId}` and `GET /v1/graph/nodes/{nodeId}/neighbors`: bounded depth (1–3), edge types from an allowlist, limit (1–200), cursor pagination, timeout. Unbounded exploration is rejected.
- `GET /v1/explain/{queryId}`: the QueryPlan, stages executed, template IDs, indexes, filters, prompt and model versions, evidence IDs, verification outcomes, latency and retry counters. No secrets, no hidden reasoning.
- `GET /healthz` (process alive) and `GET /readyz` (dependencies reachable; distinct from health); `GET /metrics` if the contract asks for it.

**HTTP behavior:** a request ID and trace ID on every request (accepted from or generated into a header) and in every log line and error; the spec's error model (400, 401, 403, 404, 409, 422, 429, 502, 503, 504, 500) with typed `ApiError` bodies; timeouts, bounded retries and a circuit breaker around each downstream port; **authentication** by an API key header compared in constant time against a value from the environment (delivered from Secrets Manager at deploy time), with health and readiness unauthenticated; structured JSON logs with no secrets or full payloads; thin routes that delegate to the Phase 09 workflow and the storage ports.

**Composition:** each service main wires real adapters from configuration (as in Phase 07–09) behind the ports, but `sbt check` never constructs an AWS client. A local mode wires the fakes, loaded from the Phase 07 L4 output and the Phase 08 vectors, with the fake model client.

**Local run:** start the services in local mode, run a scripted session (health, readiness, one synchronous query, one asynchronous query to completion, evidence and explain for its answer, a neighbors page, an idempotent repeat, one 409, one 401, one 422), save the transcript under `modules/services/target/local-run/`, and run it twice; the responses (ignoring trace IDs and timestamps) must be identical.

## 5. C — test-engineer (at least 40 new tests)

- **The spec's required tests:** successful synchronous query; asynchronous query path; invalid JSON; unsupported query constraint (422); missing evidence (404); idempotent repeated request; idempotency conflict (409); timeout and downstream failure (504, 502, circuit breaker open); unauthorized (401); health and readiness distinction; the full query-to-evidence lifecycle with fakes.
- Plus: trace IDs in every error body and log line; no secret in any response or log; bounded neighbors (depth, limit, edge types, cursor); request and response examples matching the frozen contract examples.
- **Phase 09 carry-overs:** N5 (a joint count stated as a total is rejected, and the reverse), N6, N7, and L2 (`ANTHROPIC_API_KEY` rejected).
- **At least one end-to-end API test** reaches an evidence-backed answer using the bounded fixtures.

Leave failing tests in place and route them. Run `scalafmt`; the orchestrator runs CHECK.

## 6. D — code-reviewer (read-only)

Review in this order: authentication and secret handling; injection through any route or parameter; grounding preserved end to end (no route bypasses verification); idempotency and job-state correctness; error model and trace IDs; timeouts, retries and circuit breakers; unbounded queries; images (non-root, no secrets, no Spark on the runtime classpath); the carry-overs; tests that would pass if broken; claims not backed by output. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 7. Repair loop and STOP points

- On REQUEST_CHANGES: `repair-NN.md` by owner, route within the repair policy, CHECK, rerun the local session twice if production source changed, D rereads only the repair. At most **2 rounds**.
- **STOP and ask the human** if: a precondition fails; the service contract is ambiguous; a forbidden module edge or a new framework seems needed; a finding would change an earlier frozen contract, ownership or anything constitutional; any step needs AWS or a live model call; CHECK fails after a repair for a non-resource reason; the two local sessions differ; or a third round would be needed.
- Otherwise, after APPROVE or APPROVE WITH NOTES: write the final checkpoint "Phase 10 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- Routes do not embed retrieval algorithms; every answer passes verification.
- Error responses include trace IDs; health checks expose nothing secret.
- Request and response examples match the contract tests.
- At least one end-to-end API test reaches an evidence-backed answer with bounded fixtures.
- At least three independently deployable services with images; no Spark on their runtime classpath.
- N5–N7 and L2 are fixed and tested.
- The two local sessions are identical. The `sbt check` total rises by at least 40. Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

Deployment and live AWS or model calls (Phase 14–15); Terraform changes (route any needed change to the human for infra-engineer); load tests (Phase 15).
