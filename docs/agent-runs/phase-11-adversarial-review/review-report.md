# Phase 11 — Review report (code-reviewer, work order R)

Stored by the orchestrator from the code-reviewer's returned report (condensed; the findings, file:line references and verdict are as returned). Read-only review; the reviewer ran no commands.

**Verdict: REQUEST_CHANGES**

Phase 12 contract source: `infra/terraform/` does not exist on main; the reviewer read the uncommitted lane-2 worktree (`ScholarChain-lane2/infra/terraform/INFRA-CONTRACTS.md`, `environments/course/main.tf`, `variables.tf`, `modules/iam/main.tf`, `modules/compute/main.tf`). Its hash was not computed. The comparisons hold only for that worktree as read.

Ownership check: pass (P1 in service-engineer paths, P2 in build-engineer paths, tests in test-engineer paths).

## HIGH

- **H1, generation model settings not connected (breaks Phase 14).** `modules/core/src/main/resources/scholarchain-rag.v1.conf:13-14` defaults to `provider = "bedrock"` and the Sonnet 4.5 inference profile; `RuntimeConfig.scala:20-21` reads `rag.model.provider` / `rag.model.bedrock-model-id`; `Composition.scala:342-363`. Lane-2 `environments/course/main.tf:83` sets only `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` (never read by services); no `SCHOLARCHAIN_RAG_*` is set; `variables.tf:151-155` `bedrock_model_arns = []`; `modules/iam/main.tf:12,125-132` adds Bedrock grant only when non-empty. Result: every model call AccessDenied; the intended Haiku model is never used. Owner: infra-engineer (set `SCHOLARCHAIN_RAG_MODEL_PROVIDER`, `SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID` and the ARNs), or domain-engineer (map `models.generation-model-id` into the rag runtime; changes the core config contract, human decision).
- **H2, embedder choice keyed on Voyage secret presence (breaks Phase 14).** `Composition.scala:411-415` (retrieval), `:454-458` (worker) choose Voyage whenever `SCHOLARCHAIN_VOYAGE_API_KEY` is non-empty; lane-2 `main.tf:37-41` injects it unconditionally; INFRA-CONTRACTS §9 step 5: a task will not start while an injected secret is empty; `VoyageEmbedder.scala:77,172` sends `SCHOLARCHAIN_EMBEDDING_MODEL_ID` (Terraform: `amazon.titan-embed-text-v2:0`). Result: the Bedrock embedder is unreachable, and every embed fails. Owners: service-engineer (explicit provider selection), infra-engineer (inject the secret only for `embedding_provider = "voyage"`; pass the provider). A new `embedding.provider` key would change the embedding config contract (human decision).

## MEDIUM

- **M1, readiness not acted on; config probe cannot fail.** The ALB checks only `/healthz` (lane-2 `modules/compute/variables.tf:109-112`, `main.tf:177`); no ECS `healthCheck` (`modules/compute/main.tf:80-98`). `missingConfig` (`Composition.scala:313-326`) checks the same names that startup already requires (`:338-341, 395-400`). Owners: deploy-engineer (Phase 14 smoke tests GET `/readyz`), infra-engineer (optional container health check), service-engineer (document as backstop).

## LOW

- L1 `Composition.scala:397-399`: the Neptune URI filter is always true; a scheme-less value fails later in `AwsClients.scala:84-91`. service-engineer.
- L2 `build.sbt:269-270`: the tag is not compared with HEAD or tree state; push/deploy scripts must refuse `-dirty`. build-engineer / infra-engineer.
- L3 `scholarchain-rag.v1.conf:35` graph timeout 10000 ms > downstream 8000 / client 7500 (`Composition.scala:366`); `Resilience.scala:147-151` retries timeouts up to 3 times. service-engineer.
- L4 `QueryService.scala:84, 388-418`: async jobs are orphaned on task stop until the 24 h TTL. service-engineer.
- L5 lane-2 `main.tf:39`: retrieval receives an unused services secret. infra-engineer.
- Side note: IAM `rag/evidence/*` grant unused by code (`iam/main.tf:100-104`). infra-engineer.

## Known items

- (a) `rag/audit` without trailing slash: correct; keys `rag/audit/<hash>/<hash>.json`, `rag/audit/bundles/<hash>.json` (`AuditAgent.scala:200, 258`) fall within IAM `rag/audit/*`. Not a finding.
- (b) Neptune-only endpoint override: LOW, acceptable; other clients lazy (`AwsClients.scala:46-76`); unsigned Voyage/Anthropic transports are not covered by the tripwire but are not called. storage-engineer if changed.
- (c) Local mode not self-contained: LOW, service-engineer; `LocalFixtures.scala:22-30` throws rather than refusing; the audit dir under `/opt/scholarchain` is not writable by uid 10001.
- (d) Local `/readyz` requires prompts: correct. Not a finding.

## Test quality

No test at HEAD covers the P1 code. The new `P1FixesSuite.scala` is meaningful (credential tripwire, reason codes without paths), but its environment map is not the Phase 12 one (`SCHOLARCHAIN_ENVIRONMENT=aws`, Voyage key set, no `SCHOLARCHAIN_RAG_*`), so it would pass with H1 and H2 present. Recommendation: a dry start that uses the Phase 12 per-service variables and asserts the chosen embedder and model provider.

## Reread of repair-01 (code-reviewer, read-only)

**Verdict on the repair: REQUEST_CHANGES.** The only required change is formatting plus a recorded passing CHECK; the logic of all three items is acceptable.

- H1 source evidence in `agent-report.md`: VERIFIED (`RuntimeConfig.scala:20-21`, `Config.scala:21-22, 111`, `ModuleConfig.scala:43-44`, `ModelClients.scala:260, 390`). Bedrock acceptance of the profile ID remains unverified until Phase 14.
- Ownership: PASS.
- F-SPARK-1: PASS. `KCore.scala:35` dedupes and drops self arcs on the initial arcs only; a self-arc-only node keeps coreness 0 via the coalesce at line 48; pipeline results are unchanged (`Graph.scala:79-80, 121+`); `SPARK-CONTRACTS.md:186` is accurate; the tests at `AdversarialGraphSuite.scala:113-124` still expect the original values.
- F-SVC-1: PASS with LOW 2.
- F-TEST-1: PASS on substance; weak spots in LOW 3-6.

Findings:
1. MEDIUM, test-engineer: the new suites exceed `maxColumn = 100` (`.scalafmt.conf:3`), and `check` starts with `scalafmtCheckAll` (`build.sbt:342-345`): `MissingMetadataAnswerSuite.scala:25`, `MissingMetadataVectorSuite.scala:31, 41`, `er/MissingMetadataSuite.scala:44`. (Confirmed by CHECK 2, which failed on scalafmtCheck in these four modules and in spark Compile.)
2. LOW, service-engineer: `API.md:181` says there is no reference in `modules/*/src/main`, but `models.generation-model-id` is defined in core (`Config.scala:36, 80`). It is not consumed by rag or services. Reword.
3. LOW, test-engineer: `MissingMetadataApiSuite.scala:37-44` passes vacuously if `items` is empty; add `assert(items.nonEmpty)`.
4. LOW, test-engineer: `MissingMetadataApiSuite.scala:46-61` would pass if the institution constraint were ignored (covered at the workflow level by the rag suite).
5. LOW, test-engineer: `er/MissingMetadataSuite.scala:40-45` is near-tautological; `MissingMetadataAnswerSuite.scala:34` uses the substring `"mit"` (fragile); `:40` `missingEvidence.nonEmpty` is weak.
6. LOW, test-engineer: the title of `MissingMetadataNormalizerSuite.scala:44` says "bad or missing country", but the test covers only a bad country.

Note: the reviewer read before the orchestrator wrote the CHECK 2 checkpoint, so its report says no post-repair CHECK was recorded. CHECK 2 had in fact run and failed on scalafmtCheck (see `agent-report.md`).

## Not reviewed in depth this pass

Hadoop, Spark and entity-resolution sources; storage adapters beyond `AwsClients` and `EmbeddingClaims`; RAG grounding beyond `PromptRepository` and `ModelWrapping`. No claim is made about these.
