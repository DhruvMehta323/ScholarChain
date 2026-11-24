# Phase 10 — code-reviewer report (D pass)

Stored by the orchestrator from the code-reviewer's returned message. The reviewer had Read/Grep/Glob only and ran no commands.

**Verdict: REQUEST_CHANGES.** Nine MEDIUM findings on failure handling, idempotency, concurrency, grounding visibility and test strength. No HIGH.

## Checks that pass (reviewer's evidence)

- Constant-time auth: `Http.scala:103-112` (SHA-256 digests, `MessageDigest.isEqual`; missing header compared as "").
- Keys never logged or echoed: fixed log fields (`Http.scala:27-47`, `:225-234`, no query string); fixed 401 text (`QueryService.scala:705`); fixed startup errors (`Mains.scala:10-22`). Grep of `modules/services/target/local-run/**` for key patterns: 0 matches.
- Startup refusal outside local mode: `Mains.scala:67-68`, `:89-90`; query-api AWS mode also requires the internal key (`Composition.scala:227`); local mode only for exact `local` (`Composition.scala:32`).
- Injection: strict path decoding and ID validation (`Http.scala:184`, `RestSupport.scala:156-223`); strict query parameters (`Neighbors.scala:88-156`); parameterized allowlisted graph templates (`QueryService.scala:630-637`, `RetrievalApi.scala:153`); validated trace/request headers (`RestSupport.scala:118-122`); bounded JSON size and nesting (`QueryRequest.scala:106-117`).
- Grounding: API claims are the verifier's kept claims (`QueryWorkflow.scala:314-320`, summary from kept claims `:434`); no route synthesizes or retrieves itself.
- Writes not retried: `Resilience.scala:161-162`, `:199-206`.
- Images: `USER 10001:10001` (`packaging/Dockerfile.template:20`); no baked secrets; Spark `Provided` (`project/Dependencies.scala:28-34`); guard regex `build.sbt:209`. Images with real mains not built: UNVERIFIED.
- L2: `RuntimeConfig.scala:110`, exact allowlist `ModelClients.scala:69-72`, `:153`; no echo.
- Ownership: every changed path matches its docs/agents.md §1 owner; `modules/services/target/**` gitignored; `Versions.scala` unmodified.

## Findings

- **F1 MEDIUM (service-engineer).** `RetrievalApi.scala:232,239` maps every retrieval 4xx (including 401/403) to `InvalidRequest`, which `Resilience.scala:117-119,155-157` counts as success. With mismatched internal keys, `POST /v1/query` returns 200 with a `no_evidence` abstention, `/neighbors` returns 400, the breaker never opens, `/healthz` stays 200. Fix: 401/403/5xx are failures (502); InvalidRequest only for 400/422.
- **F2 MEDIUM (service-engineer).** Idempotency/job records can stick: final `writeIdem` failure only logged (`QueryService.scala:198-206`); InProgress always 409 (`:220-221`); failed job update only logged (`:325-328`); `fut.get()` unbounded (`:339`); unexpected exception stored as non-retryable 500 (`:265`, `:222-224`). Scenarios: transient put failure or restart leaves `in_progress` → 409 for up to 24 h; `responseJson` over 300000 bytes fails the contract (`RestPersisted.scala:177-179`); expired records replayed (lazy TTL); hung async job never FAILED. Fix: lease with conditional reclaim, ignore expired records, bound async run, Internal failures retryable or not stored.
- **F3 MEDIUM (service-engineer).** Unbounded queues on fixed pools (`QueryService.scala:48-49`, `:254`, `:307`), 16 HTTP threads (`Http.scala:130,136`): bursts queue model calls without limit (cost exhaustion/queue flooding); 16 slow sync queries starve `/healthz` and the 3 s container healthcheck (`build.sbt:237-238`). Spec requires bounded concurrency. Fix: bounded queues with 429/503; health not behind query work.
- **F4 MEDIUM (rag-engineer, alt. service-engineer).** `ResearcherResolverAgent.scala:97-106` unsynchronized, unbounded `mutable.Map` in the single workflow (`Composition.scala:75`) shared by 4 threads (`QueryService.scala:48`): races can lose entries or throw (500 cached under F2); unbounded growth. Fix: per-run or bounded concurrent cache.
- **F5 MEDIUM (domain-engineer contract + service-engineer).** Abstentions are 200/SUCCEEDED with `claims: []` and the text in `answer.summary` (`QueryService.scala:367-403`); no abstained field (`RestViews.scala:96,147-153`) or log field (`QueryService.scala:270-277`), so abstention rate is not derivable. `ClaimView.uncertainty` set from `o.abstainReason` (`:388`), always None for kept claims. Fix: `answer.abstained`, `answer.abstainReason`, log them, map synthesis uncertainty.
- **F6 MEDIUM (service-engineer, domain-engineer for capabilities).** `QueryService.scala:111-120` drops `retrieval.vectorLimit`, `output.*`, `analysis.include*`, coerces `graphLimit=0` to 1; `RestCapabilities.all` claims support (`QueryRequest.scala:55-56`). A vector-only request runs graph retrieval and returns graph claims. Fix: honor or 422; capabilities match.
- **F7 MEDIUM (test-engineer).** 502/504/503 tests (`QueryApiSpecSuite.scala:228-257`) fail in `readIdem` (`QueryService.scala:209`) before the workflow; `FailingKv` unguarded (`ApiHarness.scala:143`). Removing the `p.anyFailure` branch would still pass. Fix: no idempotency key, guarded failing/slow graph, assert 502/504/503 and an async FAILED job.
- **F8 MEDIUM (test-engineer).** `Phase10CarryOverSuite.scala:179,192,210` assert only `!= Verified`; partial claims stay in the answer (`RUNTIME-AGENTS.md:76`). Fix: assert Unsupported/Conflicting with reason code and not kept.
- **F9 MEDIUM (test-engineer + service-engineer).** The production retrieval path (`RetrievalApi.scala:123-300`) never runs; the local session starts query-api in-process only (`LocalSession.scala:77-94`); F1 lives there. Fix: loopback test query-api → retrieval on port 0 (happy, wrong key, 5xx, timeout).

## LOW (defer with disclosure)

- L1 (test-engineer): idempotent-repeat checks pass without replay under the fixed clock; assert model call count unchanged.
- L2 (service-engineer): trace ID not sent to retrieval nor in workflow/audit records.
- L3 (service + build): `/readyz` unauthenticated, 3 downstream reads per call through shared breakers, blind to the prompts folder (images report ready while queries 500). Fix before Phase 14.
- L4 (service): retrieval internal routes trust caller `bounds`, `topK`, `index`.
- L5 (service): equal public and internal keys not refused.
- L6 (service): neighbors silently truncated at caps; cursor parameter 256 vs contract 512; full BFS per page.
- L7 (service): evidence persistence best-effort; cited evidence may 404.
- L8 (service): job stage stays PLANNING; orphaned run after `writeJob` failure; expired job replay 404 on POST.
- L9 (build): base image by tag not digest; curl in Temurin image UNVERIFIED by the reviewer.
- L10 (service): unbounded cached guard pool; timed-out calls keep threads.
- L11 (service): local mode without a key binds 0.0.0.0 with auth off.
- L12 (disclosed): no 403/429, no /metrics, empty explain scoreComponents and cacheHits.

## Claims the reviewer could not verify

CHECK results and timings, targeted counts, servicesGuard output, normalized diff exit 0: UNVERIFIED (no shell). The 20 local-session checks and absence of keys: confirmed by reading. Disclosures B.1–B.7 and C gaps consistent with the code.

Reviewer's limits: N5–N7 verifier logic not re-derived line by line; `RestEnums.scala`, `RestJson.scala` and API.md not reviewed in depth; F1, F3, L3 severities depend on the Phase 14 deployment shape.

---

# Review 2 (reread of repair 01 only)

**Verdict: APPROVE WITH NOTES.** No new HIGH or MEDIUM; F1–F9 all RESOLVED; 7 new LOW. The reviewer ran no commands; the CHECK total and the run-5/run-6 diff are UNVERIFIED by the reviewer (confirmed by reading: run-6 shows 20 checks true; no key patterns in run-5/run-6).

| Finding | Resolution | Evidence (reviewer) | Test fails on revert |
|---|---|---|---|
| F1 | RESOLVED | `RetrievalApi.scala:234-245`, `Resilience.scala:117-119,145-154,29` | yes, `LoopbackRetrievalSuite.scala:86-124` |
| F2 | RESOLVED | lease `QueryService.scala:81-82,221-224`; reclaim `:295,306,311`; expiry `:293-295`; retryable internal `:216-219`; oversize `:256-268`; retry `:277-278`; async bound `:464,478-480` | yes, `RepairSuite.scala:136-205` |
| F3 | RESOLVED | `QueryService.scala:62-78,351,420-429`; `Http.scala:143-172,186-189` | yes, `RepairSuite.scala:218-267` |
| F4 | RESOLVED by construction | `Composition.scala:87-102`, `QueryService.scala:349` | no targeted test (N1) |
| F5 | RESOLVED (claim uncertainty None, disclosed) | `RestViews.scala:146-156,207-211`; `QueryService.scala:530-540,375-384,522` | yes, `AbstainedContractSuite`, `RepairSuite.scala:271-304` |
| F6 | RESOLVED | `QueryService.scala:156-172,46-53` | yes, `RepairSuite.scala:311-337` |
| F7 | RESOLVED | `RepairSuite.scala:33-78`; branch `QueryService.scala:498-502` | yes |
| F8 | RESOLVED | `Phase10CarryOverSuite.scala:174-231` | yes |
| F9 | RESOLVED (AWS composition still unexecuted) | `LoopbackRetrievalSuite.scala:21-84` | yes |

New changes: (a) `clean()` acceptable, does not break grounding (status, evidence IDs, outcomes joined by claimId); (b) `LocalPlannerResponder` local only, output still passes planner validation; (c) any final guarded failure fails the query: acceptable, fails closed; ThreadLocal probe is a residual risk if port calls move to other threads; (d) port exceptions → 502 correct; (e) 422 refusals acceptable and documented.

New LOW (defer): N1 no concurrency test for F4; N2 verified text can be truncated (1000/4000 chars) instead of refused; N3 local planner name heuristic, local results are not evidence of real planner behavior; N4 API.md:35-36 understates the stricter failure rule; N5 audit-write failure discards a verified, already-paid answer; N6 retrieval serialization exceptions still return 400 ("rejected"); N7 includeScores/includeUncertainty not honored, `/readyz` shares the 2-thread health pool.
