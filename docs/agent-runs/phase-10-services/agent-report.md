# Phase 10: agent report (assembled by the orchestrator)

## Human amendments (2026-11-23, after the first precondition STOP)

Proceed without merging Phase 12. Read the contract read-only in lane2 (do not copy). Order: R, A0, A1 (if needed), B, C, D. The phase's own untracked folder satisfies "clean". Run the baseline CHECK (one unchanged rerun allowed only for a timeout or OOM in an untouched module). Add "Contract reconciliation" to the final checkpoint. STOP if §Services is ambiguous.

## Provisional contract, Phase 12 not yet accepted

Source (read-only): `../ScholarChain-lane2/infra/terraform/INFRA-CONTRACTS.md` §5 Services, uncommitted there.
SHA-256 (whole file): `6b17edab36be9ce1ed5174447c28bfe2d0f07e336ebcfc739d16f0faec8e2114`

| Service | Port | Health path | Exposure | Own variables |
|---|---|---|---|---|
| `query-api` | 8080 | `GET /health` (200) | ALB | `SCHOLARCHAIN_SERVICES_RETRIEVAL_URL`; secret `ANTHROPIC_API_KEY` |
| `retrieval` | 8080 | none stated | `http://retrieval.scholarchain.local:8080`, from query-api only | secret `SCHOLARCHAIN_VOYAGE_API_KEY` |
| `embedding-worker` | none | none | no inbound, SQS consumer | `SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL`, `SCHOLARCHAIN_SERVICES_EMBEDDING_DLQ_URL`, `SCHOLARCHAIN_EMBEDDING_QUEUE_NAME`, `SCHOLARCHAIN_EMBEDDING_QUEUE_DEAD_LETTER_NAME`, `SCHOLARCHAIN_EMBEDDING_QUEUE_MAX_ATTEMPTS`; secret `SCHOLARCHAIN_VOYAGE_API_KEY` |

Injected into every service: `SCHOLARCHAIN_ENVIRONMENT`, `SCHOLARCHAIN_AWS_REGION`, `AWS_REGION`, `SCHOLARCHAIN_STORAGE_RAW_BUCKET`, `SCHOLARCHAIN_STORAGE_CURATED_BUCKET`, `SCHOLARCHAIN_STORAGE_VECTOR_BUCKET`, `SCHOLARCHAIN_STORAGE_VECTORS_BUCKET_NAME`, `SCHOLARCHAIN_STORAGE_VECTOR_INDEX`, `SCHOLARCHAIN_STORAGE_DYNAMO_TABLE_PREFIX`, `SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT` (omitted while Neptune is off), `SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_ROLE_ARN` (omitted while off), `SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_FORMAT`, `SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_SOURCE_S3_URI`, `SCHOLARCHAIN_MODELS_EMBEDDING_MODEL_ID`, `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID`, `SCHOLARCHAIN_EMBEDDING_MODEL_ID`, `SCHOLARCHAIN_EMBEDDING_MODEL_DIMENSION`, `SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME`, `SERVICE_NAME`.

AWS resources (contract §2, §3): query-api: DynamoDB read on 15 tables, write on QuerySession and EvidenceBundle; S3 read `curated/*` and `rag/*`, write `rag/evidence/*`; S3 Vectors read; Neptune read-only (IAM); Bedrock generation models only. retrieval: the same read-only, Bedrock embedding model only. embedding-worker: contract §3a. Security groups: `alb -> query-api -> retrieval -> neptune` (query-api has no direct path to the Neptune group).

## Preconditions

- HEAD `07710d0` descends from `40a461d`: pass. Tree: only this folder untracked (accepted by amendment 3).
- Baseline CHECK (canonical command, one run): marker `exit=0`, elapsed 1508 s. Module totals 88 + 192 + 342 + 268 + 917 + 323 + 293 = 2,423 passed, 0 failed. Matches the Phase 09 total.
- `services` depends on `rag` and `storage` (and `core` transitively): `build.sbt`. Spark SQL is Test scope there.
- Phase 09 carry-overs: N5, N6, N7, L2.

## STOP: §Services conflicts with the work order (human decision needed)

1. **Model key variable.** §5 delivers secret `ANTHROPIC_API_KEY` to query-api. Work order R.3 (L2) allows only `SCHOLARCHAIN_ANTHROPIC_API_KEY`. §5 also says renaming needs infra-engineer.
2. **Health path.** §5 requires `GET /health`; work order §4 requires `/healthz` and `/readyz`.
3. **Client API key.** Work order §4 requires an environment variable for the API-key authentication; §5 has neither a variable nor a secret for it.
4. **Route placement and retrieval boundary.** §5 gives roles but no routes, and no internal authentication. query-api holds Neptune IAM but has no network path to Neptune, so graph reads must go through retrieval, whose HTTP interface is unspecified.
5. **Job and idempotency storage.** query-api may write only QuerySession and EvidenceBundle; §5 names no job or idempotency table.
6. **Neptune off (contract O1).** Core requires `storage.neptune-endpoint`, so services cannot start with the contract's default environment. Startup or readiness behavior is undecided.

L2 ownership: the Phase 09 review assigns it to domain-engineer (`RuntimeConfig.scala`); work order R assigns it to rag-engineer, who cannot edit core.

No agent has been dispatched.

## Human decisions on the contract conflicts (2026-11-23) = Contract reconciliation items for Phase 12

1. `SCHOLARCHAIN_ANTHROPIC_API_KEY` only. Phase 12 renames the secret injection.
2. Serve `/healthz` and `/readyz`; `/health` is an alias of `/healthz`.
3. `SCHOLARCHAIN_SERVICES_API_KEY` for public clients; refuse to start without it outside local mode. Phase 12 adds the secret.
4. All public `/v1` routes on query-api; retrieval exposes versioned internal routes for graph and vector reads, authenticated with the separate `SCHOLARCHAIN_INTERNAL_API_KEY` (constant-time compare). Phase 12 adds that secret and keeps the security-group chain so only query-api reaches retrieval.
5. Jobs and idempotency records in QuerySession under distinct key prefixes; STOP if its schema cannot hold them.
6. Neptune endpoint stays required; AWS mode fails at startup with a clear error when missing; `/readyz` reports dependency reachability. Frozen core contract unchanged.

L2: split. New work order A1-L2 (domain-engineer, RuntimeConfig check); rag-engineer also rejects the name in the model client (R). Order: R, A1-L2, A0, A1 (if needed), B, C, D.

## Progress checkpoint — R (rag-engineer)

- STATUS: COMPLETE. Modified: `ClaimVerificationAgent.scala`, `ModelClients.scala`, `RUNTIME-AGENTS.md` (all under `modules/rag`). No test files.
- N5 both directions, N6 and N7 implemented; L2 is an exact allowlist in the `AnthropicModelClient` constructor. The first delivery accepted any other name; the orchestrator sent it back once. The follow-up also found and fixed a wrongly VERIFIED false statement ("fell short of").
- Incident: the agent's sbt `set` override persisted in the shared sbt server and compiled its scratch suites into rag tests (344 instead of 342). Orchestrator ran `session clear-all`; before CHECK `show rag/Test/unmanagedSourceDirectories` listed no scratch path.
- CHECK (canonical): hadoop 192, sparkAnalytics 268, entityResolution 293, storage 323, rag 342, services 88, core 917 = 2,423 passed, 0 failed; 1477 s; marker `exit=0`. No new tests yet.
- Composed benchmark: R1 and R2 written by this CHECK (15:52); 44/44; `diff -r` exit 0.
- Residual (agent-reported): closed English word lists; synonyms outside them may reach `supported`; "A and B have N" is now refused.
- Agent-observed verdicts for C are in the agent's messages and are passed to test-engineer.

Open question to the human: the course spec is not in this repository, but A1/B/C cite it. Asked 2026-11-23; no answer at this time.

## Progress checkpoint — A1-L2 (domain-engineer) and C-0 (test-engineer)

- A1-L2: `RuntimeConfig.scala` accepts exactly `SCHOLARCHAIN_ANTHROPIC_API_KEY`, error does not echo the value; doc and conf comments updated.
- C-0 (a slice of C moved forward by the orchestrator so later CHECKs stay meaningful): the one test asserting the old `MY_KEY_VAR` acceptance was replaced. Agent-observed 14/14 in the suite.
- CHECK (canonical, once for both): marker `exit=0`, written 16:27:16; 293 + 342 + 917 + 88 + 323 + 192 + 268 = 2,423 passed, 0 failed; 1530 s. R1/R2 `diff -r` exit 0. Docker server 29.1.3 is running.

## A0 (build-engineer): reported complete, CHECK in progress

- Files: `build.sbt`, `project/ServiceImages.scala`, `packaging/{Dockerfile.template,entrypoint.sh,.dockerignore,README.md}`.
- Agent-observed: `servicesGuard` OK over 58 runtime entries (no spark/hadoop); missing or non-hex Git SHA fails the image build; missing mains fail the image build unless a bootstrap flag is set; images amd64, user 10001:10001. Guard failure path NOT exercised. Images with real mains NOT BUILT until B.
- Side effects (accepted by the human, 2026-11-23): shared sbt server pid 9502 killed and replaced after a broken build edit hung it; public `eclipse-temurin:17.0.21_9-jre-jammy` pulled; three placeholder images tagged `07710d0…` built locally from an uncommitted tree with the bootstrap flag, unable to start, not pushed.
- The CHECK was slowed by the Mac sleeping (pmset log). Human then enabled caffeinate.

## Spec excerpt

`spec-excerpt.md` supplied by the human. Human decisions win over it. Note: the spec's synchronous response links to `/v1/queries/{queryId}/evidence`, absent from the work order's route list; B serves it so the link resolves.

## Progress checkpoint — A0

CHECK (canonical, first run): 917 + 342 + 88 + 192 + 268 + 293 + 323 = 2,423 passed, 0 failed; marker `exit=0` at 19:19:53; 9425 s wall clock (Mac slept during the run). A1 dispatched next: the REST records do not exist in core.

## Speed amendment (human decision, recorded 2026-11-23 19:32)

Hard limit 2 to 3 hours. At most two more canonical CHECKs: one after C (covering A1, R, B, C), one after a repair if needed. Agents run targeted commands only. B: spec minimum. C: required categories, at least 40 tests, no mutation testing. Local session scripted once, run twice. One D pass; repair HIGH and MEDIUM only, at most one round, D rereads only that; all LOW deferred. No image rebuild in this phase (so images with real mains stay NOT RUN). Any single step heading over 45 minutes: STOP and report.
State when received: R, A1-L2, C-0 and A0 already complete and CHECKed; A1 running.

## Progress checkpoint — A1 (domain-engineer)

- New package `scholarchain.core.rest` (7 sources), 13 frozen `rest-*.example.json`, `modules/core/REST-CONTRACTS.md` (decisions, deviations, contract-test list). Additive only; agent reports no existing file modified.
- Agent-observed: scalafmtCheck ok, `core/Test/compile` ok, `core/testOnly *ContractSuite` 888 passed, 0 failed. New records have no tests yet (C). Round trips and 400/422 cases were checked only by agent scratch programs.
- Kept beyond the strict minimum (additive, already compiled): opaque neighbor cursor, view mappers, capability checks, ID/path/trace helpers. Trimming would cost another cycle.
- Disclosed: one stray file `/tmp/x` created outside the repo by the agent; its resumed run later ended with an authentication error after its report was delivered.
- Open for B: request topics with spaces vs rag `QueryFilters` single tokens (map or 422); cursor is unauthenticated (service re-checks bounds); clipped evidence text flagged `textTruncated`.

## Progress checkpoint — B (service-engineer)

- New: `modules/services/API.md`, `src/main/scala/scholarchain/services/{Http,Resilience,RetrievalApi,Composition,LocalFixtures,QueryService,Mains,LocalSession}.scala`. No existing file modified.
- Agent-observed: services compile, Test/compile, scalafmtCheck ok; `services/testOnly *` 88 passed, 0 failed (existing suites, benchmark 44/44); `servicesGuard` OK (58 entries, no spark/hadoop).
- Local session (`services/runMain scholarchain.services.LocalSessionMain run-N`), twice: "19 steps, 20 checks, failed: none" each. Orchestrator verified: normalized transcripts `diff` exit 0; raw transcripts differ only in the run label and two job createdAt/updatedAt values; no key pattern in logs or transcripts.
- Disclosures and open items for review/human:
  1. Extra QuerySession prefixes `evid#`, `qevid#`, `explain#` beyond decision 5's `job#`/`idem#` (schema holds them).
  2. Images contain no `prompts/` folder; AWS mode would fail on its first query. Images are not rebuilt this phase (speed amendment); packaging fix for build-engineer, deferred.
  3. AWS composition and Remote* clients compile but were never executed (Phase 14).
  4. Model client and embedder are not behind the circuit breaker (own timeouts only).
  5. No rate limiting (429, 403 never produced); no /metrics; explain leaves scoreComponents and cacheHits empty.
  6. Neighbors covers COAUTHORED_WITH only; other allowlisted edge types return empty pages.
  7. Topics/venues/institutions: one single-token entry only, otherwise 422.

## Progress checkpoint — C (test-engineer)

- New test files: services `ApiHarness` (0), `QueryApiSpecSuite` (27), `TraceAndSecretSuite` (13), `NeighborsApiSuite` (17), `EndToEndApiSuite` (1); rag `Phase10CarryOverSuite` (13); core `rest/RestExamplesContractSuite` (23), `rest/NeighborsBoundsContractSuite` (9). 103 new tests. No production edits.
- Agent-observed targeted: services 146/146, rag 355/355, core 949/949, 0 failed.
- Agent-disclosed gaps: N5 reverse and N6 negatives assert "not VERIFIED" only; async idempotent repeat and result-while-running 409 untested; retrieval internal happy paths untested; 403/429 never produced; tests depend on local L4 and embedding fixtures under target/ (as the Phase 09 suites do).
- Canonical CHECK started after C; D (code-reviewer, read-only, no sbt) dispatched in parallel to save time.

## Progress checkpoint — CHECK after C, and D

- CHECK (canonical): core 949, rag 355, entityResolution 293, storage 323, sparkAnalytics 268, hadoop 192, services 146 = **2,526 passed, 0 failed** (+103 over 2,423); marker `exit=0` at 20:28:42; 1470 s.
- D verdict: REQUEST_CHANGES (9 MEDIUM, 0 HIGH, 12 LOW), stored in `review-report.md`. Repair round 01 defined in `repair-01.md`; F4 routed to service-engineer via the reviewer's alternative fix (per-request workflow), so rag is not edited. F5 adds fields to a contract created this phase (not an earlier frozen contract), so no STOP.

## Progress checkpoint — paused (orchestrator usage limit)

Repair round 01 production and tests done (R1, R3, R4, R3b; see repair-01.md). Canonical CHECK after the repair was started in the background; its result is in `.claude/.last-test-run` and must be read and recorded. Remaining: record CHECK totals; D rereads only the repair; final checkpoint with deferred LOW list and the Contract reconciliation items (decisions 1-6, plus disclosures B.1-B.7). Not committed or staged.

## Progress checkpoint — repair 01 closed

- CHECK after repair 01: 2,555 passed, 0 failed, marker `exit=0` (21:25:12). D reread: APPROVE WITH NOTES (review-report.md, Review 2). Local run-5/run-6 normalized diff exit 0 (orchestrator).
- Account change: the human re-logged in mid-phase after the orchestrator's usage limit; no work was lost.

## Final checkpoint — Phase 10 complete; waiting for human gate

```
STATUS: COMPLETE (waiting for human gate)
WORK ORDERS: R, A1-L2, C-0, A0, A1, B, C, D; repair 01 (R1, R3, R4, R3b); D reread.
CHECK: baseline 2,423 -> final 2,555 passed, 0 failed (+132); marker exit=0 at 21:25:12.
  CHECK runs this phase: baseline, after R, after A1-L2+C-0, after A0, after C, after repair 01 (all exit 0, none rerun).
BENCHMARK: Phase 09 composed benchmark 44/44, R1/R2 diff -r exit 0 in every CHECK.
LOCAL SESSION: run-1/2 (B), run-3/4 (R3), run-5/6 (R3b): each pair normalized-identical (orchestrator diff exit 0); 20 checks, none failed.
REVIEW: D pass REQUEST_CHANGES (9 MEDIUM); reread APPROVE WITH NOTES.
```

### Gate items (work order)

| Gate | Status |
|---|---|
| Routes do not embed retrieval; every answer passes verification | Reviewer PASS (claims are the verifier's kept claims) |
| Error responses include trace IDs; health exposes nothing secret | Tested (`TraceAndSecretSuite`, `QueryApiSpecSuite`) |
| Request/response examples match contract tests | `RestExamplesContractSuite`, `AbstainedContractSuite` |
| End-to-end API test reaches an evidence-backed answer | `EndToEndApiSuite` (cited evidence IDs resolve) |
| ≥ 3 independently deployable services with images; no Spark at runtime | **Partially met.** Three image definitions; `servicesGuard` OK. Images with the real mains NOT BUILT (speed amendment item 7); only the A0 placeholder images exist. Images also lack `prompts/` (B.2). |
| N5–N7 and L2 fixed and tested | Yes (`Phase10CarryOverSuite`, `RuntimeConfigContractSuite`) |
| Two local sessions identical | Yes |
| `sbt check` total rises by ≥ 40 | +132 |
| Reviewer verdict | APPROVE WITH NOTES |

### Contract reconciliation (when Phase 12 merges)

Compare the final INFRA-CONTRACTS.md §5 against SHA-256 `6b17edab36be9ce1ed5174447c28bfe2d0f07e336ebcfc739d16f0faec8e2114` and the names recorded at the top of this report; any difference becomes a small follow-up. Phase 12 changes required by the human decisions:
1. Rename the query-api secret injection `ANTHROPIC_API_KEY` -> `SCHOLARCHAIN_ANTHROPIC_API_KEY`.
2. ALB health check path: `/health` stays valid (alias of `/healthz`); `/readyz` is the readiness path.
3. Add secret `SCHOLARCHAIN_SERVICES_API_KEY` (query-api).
4. Add secret `SCHOLARCHAIN_INTERNAL_API_KEY` (query-api and retrieval); keep the alb -> query-api -> retrieval security-group chain.
5. Jobs and idempotency (and the `evid#`, `qevid#`, `explain#` prefixes) live in QuerySession: query-api's write grant on QuerySession already covers them.
6. Neptune endpoint stays required: retrieval refuses to start in AWS mode without it (open point O1 unchanged).
Also new optional service variables (API.md "Configuration"): `SCHOLARCHAIN_SERVICES_MAX_CONCURRENT_QUERIES`, `..._MAX_QUEUED_QUERIES`, `..._ASYNC_DEADLINE_MILLIS` and the others listed there.

### Deferred (disclosed)

- LOW from review 1: L1–L12 (review-report.md). L3 (/readyz blind to the missing `prompts/` folder) must be fixed before Phase 14.
- LOW from review 2: N1–N7.
- B.2 images lack `prompts/`; images with real mains not built (build-engineer, before Phase 14).
- AWS composition and Remote* clients over real AWS never executed (Phase 14).
- No 403/429-by-budget, no /metrics, empty explain scoreComponents/cacheHits; claim uncertainty not carried.

### Disclosures

- Contract read provisionally from the uncommitted lane2 worktree (Phase 12 not accepted).
- A0 side effects accepted by the human: shared sbt server replaced; Temurin base image pulled; three placeholder images built and not pushed.
- R's sbt `set` override leaked into the shared server (cleared before any CHECK).
- C-0 moved forward and F4 routed to service-engineer (reviewer's alternative) by the orchestrator to save time.
- R3b completed repair round 01 after R4 found a defect; it was not a second round.
- R3b added `LocalPlannerResponder.scala` (local mode only).
- Stray file `/tmp/x` created by domain-engineer outside the repo.
- The Mac slept during the A0 CHECK (2 h 37 min); caffeinate enabled afterwards.
- The human re-logged in after the orchestrator's usage limit.

Nothing staged or committed.
