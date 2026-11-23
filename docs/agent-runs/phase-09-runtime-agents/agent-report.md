# Phase 09 — Runtime collaboration-analysis agents: agent report

Assembled by the orchestrator. Agent reports are quoted or summarized from their returned messages. Observed commands only.

## 1. Preconditions (orchestrator, read-only)

- `git status --short`: `?? docs/agent-runs/phase-09-runtime-agents/` only (this phase folder, which held only `prompt.md`). No tracked change.
- HEAD `765f7ce` ("phase-08: record accepted commit hash"). `git merge-base --is-ancestor 70472eae2159831c0fec1ef280d8700319106b5f HEAD` → true (that hash is `docs/agent-runs/phase-08-embeddings-vectors/commit.txt`).
- Parallel worktree: `git worktree list` shows `ScholarChain-lane2` at `0a2212d [lane2]`. `pgrep -fl sbt` showed one sbt server (pid 6361), whose cwd (`lsof`) is this repository, not lane2. No sbt process in lane2 at the time of the check.
- Baseline canonical command: see "Baseline CHECK" below.
- Module edges (`build.sbt`): `rag.dependsOn(core)` with `awsBedrockRuntime`, `awsUrlConnectionClient`; `services.dependsOn(rag, storage)` (services Test has `sparkSqlTest`, `Test / parallelExecution := false`). `rag` does not depend on `storage`, `spark`, `hadoop` or `entityResolution`. The composed local run goes in `modules/services/src/test/**` (test-engineer), as in Phase 08. No new edge is needed. **A0 not needed.**
- Core records this phase uses (`modules/core/src/main/scala/scholarchain/core/`):
  - Present: `QueryPlan` v1 (`Records.scala:561`: operation, question, subjectIds, window, metric, limit), `EvidenceItem` (`:513`), `EvidenceBundle` (`:543`), `AnswerClaim` (`:602`), `AnswerResponse` (`:628`), `SemanticContextItem` (`EmbeddingRecords.scala:183`), `QuerySessionRecord` (`StorageRecords.scala:280`), `QueryOperation` (`Enums.scala:47`), `VerificationStatus` (`Enums.scala:59`), the six graph templates `GraphTemplates` (`StoragePorts.scala:138`: neighbors, coauthor-edge, shortest-path, component, community, researcher-metrics).
  - Incomplete: `QueryOperation` v1 has 6 values (lookup_researcher, list_coauthors, collaboration_path, top_bridge_researchers, collaboration_trend, search_works). It lacks the §3 allowlist entries (ranking by named metric, maximum team size, component lookup, community comparison, temporal comparison, topic/venue/institution filter, semantic explanation). `QueryPlan` v1 lacks named researchers, filters and multiple limits. `VerificationStatus` v1 wire values (supported, partially_supported, unsupported, contradicted, unverified) differ from the §3 `ClaimVerification` status set (VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING); the frozen v1 enum must not change in place.
  - Missing: `ResolvedResearcherCandidate`, `ClaimVerification`, `ModelCall`, `ModelClient` port.
  - **A1 runs.**
- Phase 07 loader output L4: `modules/storage/target/loads/L4/` (`neptune/`, `dynamodb/`, `object-store/`, `load-manifest.json`, `checksums.sha256`).
- Phase 08 local vector output L4: `modules/services/target/embedding-runs/L4/` (`vectors.jsonl`, `documents.jsonl`, `ledger.jsonl`, `objects/`, `counts.json`, `smoke.json`).

### Baseline CHECK

- Run 1 (canonical command, from the repo root after a separate `cd`): testFull hadoop 192, services 28, rag 140, entityResolution 293, core 874, storage 323 all passed; sparkAnalytics **268 total, 1 failed** (`scholarchain.spark.IdentitySuite` "M1: the reason combines with rejected_or_conflict_pair and with two_orcids, in that order", 33.696 s, `java.util.concurrent.TimeoutException: test timed out after 30 seconds`). **2,117 passed, 1 failed (STATUS: FAILED)**. `[error] elapsed time: 1583 s`; marker `exit=1`. 0 occurrences of `AwsItSuite`.
- Cause observed after the run: the lane2 worktree was running sbt at the same time. sbt pid 84084 (cwd `ScholarChain-lane2`, elapsed 23:52) and its forked test JVM pid 97513 (cwd `ScholarChain-lane2/modules/spark`, elapsed 16:56, 157 % CPU, 2.38 GB RSS). This overlapped run 1 and breaks §0.5. The failure is a timeout in the untouched spark module, which is the same test class that timed out in Phase 08.
- Action under §0.9: one unchanged rerun, started only after lane2's sbt test JVM has exited.
- Run 2 (§0.9 unchanged rerun, started after lane2 java processes were idle at 17:59:12; a background watcher logged lane2 java processes above 20 % CPU every 30 s during the run and logged none): testFull core 874, entityResolution 293, hadoop 192, storage 323, sparkAnalytics 268, services 28, rag 140. **Baseline total 2,118 passed, 0 failed** (equals Phase 08's final total). `[success] elapsed time: 1997 s`; marker `exit=0`. 0 occurrences of `AwsItSuite`. The other `Passed: Total` lines (contractTest and similar) repeat or sub-select these and are not added.

## Progress checkpoint — preconditions

All preconditions hold. A0 not needed. A1 dispatched next.

## A1 — domain-engineer

- STATUS: COMPLETE (agent report). Files created (all under `modules/core/`): `src/main/scala/scholarchain/core/RuntimeEnums.scala` (QueryOperationV2, ClaimVerificationStatus, ModelStage, ModelCallOutcome, ModelProvider), `RuntimeRecords.scala` (QueryFilters, QueryLimits, QueryPlanV2, ResolvedResearcherCandidate, ClaimVerification, ModelCall), `ModelPorts.scala` (ModelClient port, ModelRequest with validate, ModelSegment Instruction/Data, ModelResponse, ModelTokenUsage, ModelError), `RuntimeConfig.scala` (RuntimeConfigKeys, RuntimeSettings, RuntimeConfigLoader), `src/main/resources/scholarchain-rag.v1.conf`, four frozen examples in `src/main/resources/contracts/` (`query-plan-v2`, `resolved-researcher-candidate`, `claim-verification`, `model-call`), and `RUNTIME-CONTRACTS.md`. Files modified: none. The orchestrator confirmed with `git status --short`: only untracked new files, no tracked change.
- QueryOperationV2 wire values (11): rank_researchers_by_metric, max_team_size, direct_coauthors, shortest_path, component_lookup, community_comparison, bridge_ranking, temporal_comparison, entity_filter, semantic_explanation, researcher_metrics_lookup (the one addition the work order allowed). ClaimVerificationStatus wire values: verified, partially_supported, unsupported, conflicting (lowercase, following the code-base convention). Frozen v1 enums, QueryPlan v1 and existing examples are unchanged.
- Config: provider defaults to `bedrock`. The key is referenced by env-var NAME only (`rag.model.anthropic-api-key-env-var = "SCHOLARCHAIN_ANTHROPIC_API_KEY"`, the only key-related line in the conf; the orchestrator grepped for it). Malformed-output retries are capped at 2. Budgets, timeouts, graph bounds and the evidence cap come from config.
- Agent commands (observed by the agent): `sbt "core/compile"` failed once on a name clash (fixed), then succeeded. A `core/console` round-trip of the four examples gave byte-identical output after two fixes, and the default config loads. `sbt "core/scalafmt; core/compile; core/Test/compile"` succeeded. No tests were written (work order C).
- Assumptions disclosed: added `researcher_metrics_lookup`, `comparisonWindow` (for temporal_comparison), `queryId` on ModelCall, and `provenance` on ClaimVerification. Name and ID lists are positional. Model-ID defaults (`us.anthropic.claude-sonnet-4-5-20250929-v1:0`, `claude-sonnet-4-5`) are unverified offline and must be confirmed before Phase 14–15.
- Risks disclosed: hard caps in core; core cannot enforce that ModelError reasons contain no secrets (adapter tests must); a guard false positive on a key-file pattern in a heredoc (nothing executed). The orchestrator hit the same false positive when appending this section through the shell and used the Edit tool instead.
- CHECK after A1 (canonical command): testFull sparkAnalytics 268, services 28, storage 323, rag 140, hadoop 192, core 874, entityResolution 293. **2,118 passed, 0 failed**, unchanged because no tests were added yet. `[success] elapsed time: 1572 s`; marker `exit=0`. 0 occurrences of `AwsItSuite`.

## Progress checkpoint — A1

A1 complete; CHECK green at 2,118. B (rag-engineer) dispatched next.

## B — rag-engineer

- An API rate limit ("weekly limit") interrupted the first run before a compile step. The orchestrator resumed the same agent after the reset, and its in-progress files were intact.
- STATUS: COMPLETE (agent report), with disclosed gaps: the composed workflow is compiled but was never run end to end.
- Files created: `modules/rag/src/main/scala/scholarchain/rag/runtime/`:
  - `RuntimeSupport.scala`, `JsonSchemaValidator.scala`
  - `ModelRuntime.scala` (PromptRepository, FakeModelClient, StructuredModelCaller with budgets, schema validation, retry ≤ 2, typed failure)
  - `ModelClients.scala` (BedrockConverseModelClient, AnthropicModelClient, ModelClientFactory)
  - one class per stage: `QueryPlannerAgent`, `ResearcherResolverAgent`, `CollaborationMetricsAgent`, `GraphAgents` (PathFinderAgent, CommunityAnalystAgent), `SemanticContextAgent`, `EvidenceBuilderAgent`, `AnswerSynthesisAgent`, `ClaimVerificationAgent`, `AuditAgent`
  - `QueryWorkflow.scala`, `BenchmarkResponders.scala`, `BenchmarkGenerator.scala`

  Also: `modules/rag/src/main/resources/benchmarks/benchmark-v1.json`; `modules/rag/RUNTIME-AGENTS.md`; `prompts/README.md`; `prompts/runtime/{query-planner,answer-synthesis,claim-verification}-v1.txt`; `prompts/schemas/{query-planner,answer-synthesis,claim-verification}-v1.schema.json`. Files modified: none.
- Agent commands (observed by the agent): `sbt "rag/compile"` [success] after fixes. `sbt "rag/Test/compile"` [success]. `sbt "rag/testOnly *"` Passed: Total 140, Failed 0. The generator ran 4 times; the final version wrote 44 questions. The agent's `rag/scalafmt` invocation failed to parse and was not run.
- Benchmark: 44 questions. ambiguous_name 4, bridge_ranking 3, coauthor_breadth 4, community 3, component 3, direct_collaboration 3, evidence_lookup 3, homonym_suffix 2, institution 2, max_team_size 3, prompt_injection 4, shortest_path 5, temporal_change 3, topic 2. Expected values are computed by the generator from the L4 tables, with input checksums recorded in the file. No model was used.
- Agent-disclosed risks:
  - Nothing has run end to end.
  - `JsonSchemaValidator` supports a keyword subset only.
  - Number-words are not detected by the verifier.
  - Neighboring communities and topic, venue and institution filters cannot be served by the six templates. They are reported as missing evidence; topic and institution questions are semantic-only.
  - Resolver recall depends on top-100 hash-embedder vector hits.
  - The Bedrock tool-use conversion and Anthropic reply parsing are unexercised.
- Orchestrator verification:
  - `git status --short`: only new untracked files under `modules/core/**` (A1), `modules/rag/**` and `prompts/`. No tracked change.
  - `SCHOLARCHAIN_ANTHROPIC_API_KEY` appears in rag only in a doc comment (`ModelClients.scala:48`). The key is read via the configured env-var name.
  - `sbt "rag/scalafmtAll; core/scalafmtAll; rag/scalafmtCheckAll; core/scalafmtCheckAll; …"`: rag reformatted 1 of 16 sources; the check passed.
  - Determinism: two generator runs into the scratchpad (`rag/runMain scholarchain.rag.runtime.BenchmarkGenerator modules/storage/target/loads/L4 <scratchpad>/bench-r{1,2}.json`) and the file in the tree all have SHA-256 `e511d17530823374bfeec5683d2ac25c6980d5a31f49b470e47d62f561a721e6`. This replaces the agent's stale hash.
- CHECK after B (canonical command): testFull hadoop 192, entityResolution 293, rag 140, core 874, storage 323, services 28, sparkAnalytics 268. **2,118 passed, 0 failed** (no new tests yet). `[success] elapsed time: 1536 s`; marker `exit=0`. 0 occurrences of `AwsItSuite`.

## Progress checkpoint — B

B complete; CHECK green at 2,118. C (test-engineer) dispatched next with the B report.

## C — test-engineer

- STATUS: FAILED (agent report). Tests are written, and 15 new tests fail on production defects. All failures are routed to rag-engineer. No production edits (agent-verified; `git status` shows only new untracked test files).
- Files created:
  - core: `modules/core/src/test/scala/scholarchain/core/RuntimeContractsSuite.scala` (29), `RuntimeConfigContractSuite.scala` (14).
  - rag (`modules/rag/src/test/scala/scholarchain/rag/runtime/`): `RuntimeFixtures.scala` (fixtures), `QueryPlannerSuite` (17), `ResearcherResolverSuite` (15), `GraphStagesSuite` (26), `SemanticAndEvidenceSuite` (13), `SynthesisVerificationSuite` (25), `ModelRuntimeSuite` (25), `AuditSuite` (15), `VerifierGroundingSuite` (7).
  - services: `modules/services/src/test/scala/scholarchain/services/Phase09World.scala`, `Phase09Benchmark.scala`, `Phase09BenchmarkSuite.scala` (58). It fails loudly if any L4 input is missing.
- New tests: core 43, rag 143, services 58, **244 in total**.
- Agent-observed results:
  - `core/testOnly scholarchain.core.Runtime*`: 43/43 passed.
  - `rag/testOnly *`: 283 total, 280 passed, **3 failed**.
  - `services/testOnly *`: 86 total, 74 passed, **12 failed**.
  - `scalafmtAll` on rag, core and services: success.
- Composed benchmark (44 questions, L4 + Phase 08 vectors, fake model with `BenchmarkResponders`):

  | Category | R1 strict | R2 strict | Evidence-only (R1 and R2) |
  |---|---|---|---|
  | ambiguous_name | 4/4 | 4/4 | 4/4 |
  | bridge_ranking | 3/3 | 3/3 | 3/3 |
  | coauthor_breadth | 4/4 | 4/4 | 4/4 |
  | community | 3/3 | 3/3 | 3/3 |
  | component | 3/3 | 3/3 | 3/3 |
  | direct_collaboration | 0/3 | 0/3 | 3/3 |
  | evidence_lookup | 0/3 | 0/3 | 3/3 |
  | homonym_suffix | 2/2 | 2/2 | 2/2 |
  | institution | 2/2 | 2/2 | 2/2 |
  | max_team_size | 3/3 | 3/3 | 3/3 |
  | prompt_injection | 4/4 | 4/4 | 4/4 |
  | shortest_path | 1/5 | 1/5 | 5/5 |
  | temporal_change | 3/3 | 3/3 | 3/3 |
  | topic | 2/2 | 2/2 | 2/2 |
  | **Total** | **34/44** | **34/44** | **44/44** |

  All 4 ambiguous names abstained with at least 2 candidates. The 4 injection questions had allowlisted plans and only ID or window graph parameters. 84 fake model calls; 0 SDK clients constructed.
- Orchestrator verification: `diff -r modules/services/target/phase09-benchmark/R1 …/R2` printed nothing (IDENTICAL), 133 files. The agent reports tree SHA-256 `5e3e7459…85b8`.
- Failures (agent findings, owner rag-engineer):
  1. **HIGH, verifier grounding.** In `ClaimVerificationAgent.deterministic`, numbers found inside stored labels (IDs, windows, hashes) are exempted from the uncovered-number check. So a claim like "has 2004 distinct coauthors" (2004 from a community ID window) or "9999 distinct coauthors" on real L4 data is VERIFIED. Four tests fail: 3 in `VerifierGroundingSuite`, 1 in `Phase09BenchmarkSuite` (the fabricating-model test).
  2. **MEDIUM, expectation vs synthesizer.** Ten strict benchmark failures: `TemplateSynthesizer` does not assert `neighborhood.jointPublicationCounts` or `path.edgeIds`, which the generator lists as expected facts. The values are present in the evidence bundle.
  3. **LOW.** `BenchmarkResponders.evidenceSet` labels semantic items `entity:unknown`. Fails `topic-01` and `prompt_injection-04` in the "names the retrieved entity" test.
  - Doc observations: RUNTIME-AGENTS.md §10 gives a wrong override key (the working key is `scholarchain.rag.model.provider`), and §5 says the question is not stored, but the audit object's plan contains it.
- CHECK after C (canonical command): testFull rag **283 total, 3 failed** (`VerifierGroundingSuite`), core 917, storage 323, services **86 total, 12 failed** (`Phase09BenchmarkSuite`), sparkAnalytics 268, entityResolution 293, hadoop 192. **2,362 total: 2,347 passed, 15 failed (STATUS: FAILED)**. `[error] elapsed time: 764 s`; marker `exit=1`. 0 occurrences of `AwsItSuite`. The 15 failures are exactly the new tests routed above. All pre-existing suites pass.

## D — code-reviewer (review 1)

- Verdict: **REQUEST_CHANGES**. The full report is in `review-report.md`.
- Passes: injection, separation in the builder, secrets (with L2), prompt/schema versioning, benchmark independence (with L3), module boundaries, ownership, frozen contracts, no hidden reasoning.
- Findings: H1 (label digit-run exemption, wider than reported), H2 (reference-only claims VERIFIED; semantic excerpts in the reference pool; number words), H3 (assertions not bound to the text's field, subject, window or hop order), M1 (synthesizer omits two expected fields), M2 (resolver top-100 saturation can resolve a homonym), M3 (same question twice → session_conflict), M4 (tests miss H2/H3; one test passes by fixture luck), L1–L6.

## Progress checkpoint — review 1

REQUEST_CHANGES. Repair round 1 is defined in `repair-01.md`: R1a goes to rag-engineer (H1–H3, M1–M3, doc corrections), then R1b to test-engineer (M4). L1, L2, L3, L5 and L6 are deferred with disclosure.

## Repair 01 (details in `repair-01.md`)

- R1a (rag-engineer): rewrote the deterministic verifier (H1–H3); the template asserts the missing fields (M1); resolver truncation rule, narrowed (M2); per-run session ID (M3); L1 promoted from deferred because its test failed. R1b (test-engineer): 34 new tests, 2 strengthened.
- CHECK: **2,396 passed, 0 failed**, `exit=0`, 1542 s.
- Review 2: **REQUEST_CHANGES** (N1, N2 HIGH; N3, N4 MEDIUM; deviation (b) not accepted for two reason codes; M2 narrowing accepted with a note).

## Repair 02 — final round (details in `repair-02.md`)

- R2a (rag-engineer): closed-wording binding for negation, classification and ordinals (N1); number ownership (N2); field disambiguation (N3); per-hop evidence (N4); pairwise relation without assertion → UNSUPPORTED, `display_name_mismatch` → CONFLICTING. R2b (test-engineer): `VerifierRound2Suite` (26), 8 pinned statuses updated, 1 new services end-to-end test.
- CHECK: testFull hadoop 192, sparkAnalytics 268, entityResolution 293, storage 323, rag 342, services 88, core 917. **2,423 passed, 0 failed**. `[success] elapsed time: 1512 s`; marker `exit=0`. 0 occurrences of `AwsItSuite`.
- Review 3: **APPROVE WITH NOTES**.

## Gate summary (observed)

- **User text cannot become database code or an AWS call:** reviewer PASS in reviews 1–3. The question reaches only Data model segments and the embedder. Graph access is limited to the six allowlisted templates with typed researcher-ID or window parameters. The planner allowlist is validated in code. The 4 prompt-injection benchmark questions pass, with allowlisted plans and only ID/window graph parameters.
- **Ambiguous researcher names never silently resolve:** 4/4 ambiguous-name and 2/2 homonym-suffix questions pass (abstain with ≥ 2 candidates). Residual (M2, accepted with a note): a homonym ranked beyond the top 100 behind non-matching profiles is not detected.
- **Every numerical, path and identity claim has evidence IDs; unsupported claims removed or labeled:** deterministic-first verifier. Kept claims cite existing evidence; every number must equal an asserted stored value bound to field, subject and window; path order and per-hop evidence are checked; identity names are checked. Unsupported and conflicting claims are removed; partial claims are labeled at 0.5. The LLM check can only downgrade (tested). Residual: phrasings outside the closed English lists (N5–N7, R1–R2), disclosed; `llmVerification` defaults to false.
- **Prompt-injection tests pass:** yes (planner, title/abstract/summary injection, benchmark injection category 4/4).
- **Deterministic graph retrieval and semantic retrieval are separate and evaluated separately:** the evidence builder keeps them apart; semantic items cannot support numbers, windows or assertions (tested). The benchmark reports evidence-only and strict results; topic and institution questions are semantic-only.
- **Benchmark ≥ 30 data-derived questions, composed run identical twice:** 44 questions generated from the L4 tables by `BenchmarkGenerator` (no model). Two orchestrator reruns reproduce SHA-256 `e511d175…21e6`. Composed run 44/44 strict in R1 and R2. `diff -r R1 R2` was observed empty after C, repair 01 and repair 02.
- **`sbt check` total rises by ≥ 45:** 2,118 → **2,423 passed, 0 failed** (+305), marker `exit=0`.
- **Reviewer verdict:** review 1 REQUEST_CHANGES → repair 01 → review 2 REQUEST_CHANGES → repair 02 → review 3 **APPROVE WITH NOTES**.

## Open items for the human gate

1. Deferred findings:
   - **N5 MEDIUM**: a joint publication count can be stated as one researcher's total; fix before any real synthesis model runs with `llmVerification=false`.
   - N6, N7 LOW: owner guessed by nearest mention; comparatives and direction words unchecked; RUNTIME-AGENTS.md not yet updated for these.
   - L2 (domain-engineer: the config accepts `ANTHROPIC_API_KEY` as the env-var name), L3, L5, L6, L7.
2. Disclosed deviations:
   - M2 resolver truncation rule narrowed (accepted with a note).
   - L1 promoted from deferred in repair 01.
   - `QueryOperationV2` adds `researcher_metrics_lookup`; `QueryPlanV2` adds `comparisonWindow`.
   - ClaimVerificationStatus wire values are lowercase.
   - The composed benchmark lives in `modules/services/src/test/**`, as in Phase 08.
3. Unverified until Phase 14–15: model-ID defaults (`us.anthropic.claude-sonnet-4-5-20250929-v1:0`, `claude-sonnet-4-5`), Bedrock tool-use conversion, Anthropic reply parsing.
4. Process:
   - Baseline run 1 failed (spark `IdentitySuite` 30 s timeout) while lane2 was running sbt in parallel; the §0.9 rerun passed.
   - rag-engineer's first run of B was cut off by an API weekly rate limit and was resumed.
   - Guard false positives on key-file-like text (nothing executed).
   - A mid-run human message about lane2 Terraform work (INFRA-CONTRACTS.md, claims tables, SQS, IAM, Secrets Manager) was not acted on in this session; it appears addressed to the lane2 session.
5. NOT RUN: any AWS, Bedrock, Anthropic or Voyage call; live suites; LLM claim verification with a real model.
6. Generated outputs (not committed, under `target/`): `modules/services/target/phase09-benchmark/{R1,R2,adversarial-audit,adversarial-audit-h2h3}`.

## Progress checkpoint

Phase 09 complete; waiting for human gate. Nothing staged or committed.
