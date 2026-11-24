# Phase 11 — Agent report (orchestrator-assembled)

## Progress checkpoint — preconditions

- First attempt (HEAD `f77b312`): STOPPED at preconditions. `docs/agent-runs/phase-10-services/human-gate.md` was byte-identical to this phase's `prompt.md` (`cmp` reported identical), so Phase 10 gate §4 could not be read. Nothing was dispatched or written.
- Human restored the gate (commit `932fd2f`, "phase-10: restore human gate"). The human's message left the commit hash as a placeholder; the orchestrator read it with `git rev-parse --short HEAD` → `932fd2f`.
- Re-run: `git status --short` clean; `git merge-base --is-ancestor 189f0b8 HEAD` → ancestor ok; the gate now differs from prompt.md and reads "Decision: ACCEPTED", §4 lists fixes 1 (audit path), 2 (images with real mains and prompts/), 3 (L3, /readyz blind to prompts/), plus "the AWS composition code ... has never been executed".
- Deferred notes read: Phase 05 §4.7, Phase 06 §4.11, Phase 07 §4, Phase 08 §5, Phase 09 §3.4, Phase 10 §5 (L1–L12 except L3, N1–N7, F4).
- Baseline CHECK: not run (work order §1).

## Progress checkpoint — P1 (service-engineer)

- STATUS: COMPLETE (agent report). Files modified: `modules/services/src/main/scala/scholarchain/services/Composition.scala`, `Mains.scala`.
- Agent-observed commands: `sbt services/compile` (first run failed on its own errors, rerun `[success]`); `sbt services/scalafmt` `[success]`; `sbt services/test` → `Passed: Total 169, Failed 0, Errors 0`.
- Fix 1: `Composition.auditLocation(curatedBucket, ragPrefix)` → `(bucket, "rag/audit")`; bucket from `SCHOLARCHAIN_STORAGE_CURATED_BUCKET` (now required by `awsQueryApi`, `awsRetrieval`). Key prefix carries no trailing slash because rag joins with `/` (agent's stated deviation from the literal `rag/audit/`).
- Fix 3 (L3): `Readiness.promptsCheck`; reason codes `prompts_dir_missing`, `prompt_version_missing`, `prompt_invalid`, `config_missing`; required prompts `query-planner`, `answer-synthesis`, `claim-verification` at `v1`. Local mode now also requires prompts for readiness.
- Dry start: `AwsSeams(credentials, neptuneEndpoint)` parameter (default `AwsSeams.Production`); pure `QueryApiMain.assemble`, `RetrievalMain.assemble`, `EmbeddingWorkerMain.assemble`.
- Agent-disclosed risk: the storage `AwsClients` accepts an endpoint override for Neptune only; S3, DynamoDB, SQS and S3 Vectors clients are lazy, so the dry start reaches no network call, but a test cannot redirect a real call to loopback without a storage-engineer change. Not routed (would add a feature; left for review R to judge).
- Prompts location (rag `PromptRepository.locate`): system property `scholarchain.prompts.dir`, else a `prompts/` folder found walking up from `user.dir`. Passed to P2.

## Progress checkpoint — P2 (build-engineer)

- STATUS: COMPLETE (agent report). Files modified: `build.sbt` (stage copies `prompts/runtime` and `prompts/schemas`; tag regex `[0-9a-f]{7,40}(-dirty)?`), `packaging/Dockerfile.template` (prompts at `/opt/scholarchain/prompts`, root-owned, read-only), `packaging/entrypoint.sh` (`-Dscholarchain.prompts.dir=/opt/scholarchain/prompts`), `packaging/.dockerignore` (adds `!prompts/` only), `packaging/README.md`.
- Agent-observed: `sbt shutdown`; `SCHOLARCHAIN_GIT_SHA=932fd2f-dirty sbt dockerLocal` → `[success]`, no ALLOW_MISSING_MAINS, no push, no login. `sbt servicesGuard` → `OK, 58 runtime classpath entries, none match spark-* or hadoop-*`.
- Orchestrator-observed: `docker image ls` lists `scholarchain/{query-api,retrieval,embedding-worker}:932fd2f-dirty`, 447MB each (agent reports content size 126MB).
- Agent-observed in image: `id` → `uid=10001(scholarchain) gid=10001(scholarchain)`; `/opt/scholarchain/prompts/runtime` has the three `*-v1.txt`, `schemas/` the three `*-v1.schema.json`, all `root` and read-only; `ls lib | grep -ciE "spark|hadoop"` → 0.
- HTTP (local mode, loopback ports): query-api `/healthz` 200, `/readyz` 200 with checks key_value, graph, vectors, prompts all up; retrieval `/healthz` 200, `/readyz` 200 (graph, vectors up). embedding-worker: no local mode (`startup.refused ... has no local mode`, exit 2); in `dev` without region, `startup.refused ... SCHOLARCHAIN_AWS_REGION is required`, exit 2.
- Disclosed: the 200s required a bind-mounted scratch dir with an empty `build.sbt` and copies of the L4 fixture trees, because `LocalFixturePaths.repoRoot` (`LocalFixtures.scala:29`) throws `repository root (build.sbt) not found above /opt/scholarchain` without it. Local mode is therefore not self-contained in the image (finding for service-engineer; severity to be judged by R; does not affect AWS mode). Containers `sc-p2-*` removed; images kept. The `-dirty` images contain uncommitted code and must not be deployed.

## Progress checkpoint — T (test-engineer)

- STATUS: FAILED (agent report; 2 new tests fail and are left in place, as the work order requires). Test-only files created: `modules/services/src/test/scala/scholarchain/services/P1FixesSuite.scala` (37), `modules/services/src/test/scala/scholarchain/services/ResilienceAdversarialSuite.scala` (16), `modules/rag/src/test/scala/scholarchain/rag/runtime/InjectionWrappingSuite.scala` (10), `modules/spark/src/test/scala/scholarchain/spark/AdversarialGraphSuite.scala` (19). 82 new tests in total.
- Agent-observed targeted runs: P1FixesSuite `Passed: Total 37, Failed 0`; ResilienceAdversarialSuite `Passed: Total 16, Failed 0`; InjectionWrappingSuite `Passed: Total 10, Failed 0`; AdversarialGraphSuite `Failed: Total 19, Failed 2, Errors 0, Passed 17`. Two earlier invocations were discarded (wrong project id `spark/`; a combined run with no rag output).
- Failing (owner spark-engineer, agent-rated LOW and latent, because the pipeline never emits duplicate or self arcs): "k-core: an arc listed twice does not inflate coreness" (observed `a->2, b->2`, expected 1); "k-core: a self arc does not lift a node out of the 0-core" (observed `a->1`, expected 0).
- P1 fixes covered: audit `(curated bucket, rag/audit)` including a spy ObjectStore that observed the keys `rag/audit/...` in the curated bucket; `/readyz` 503 with `prompts_dir_missing`, `prompt_version_missing`, `config_missing` and no path in the body; dry start `assemble` returns Right for all three mains with a tripwire credentials provider (0 calls).
- Coverage gap disclosed: "missing topic, institution, abstract or profile metadata", not added and existing coverage not verified. Hadoop, ER, DOI, vector-key and verifier categories rely on existing suites found by grep.
- Guard false positives: two test-engineer Bash commands, and one orchestrator command writing this checkpoint, were blocked for containing the text `.key`; the file tools were used instead.

## Progress checkpoint — R (code-reviewer)

- Verdict: **REQUEST_CHANGES**. Read-only; no files written.
- H1 HIGH (breaks Phase 14): the services read the generation model from `rag.model.provider` / `rag.model.bedrock-model-id` (default bedrock + Sonnet 4.5 profile, `modules/core/src/main/resources/scholarchain-rag.v1.conf:13-14`), while Terraform (lane-2 worktree, uncommitted) sets only `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` and grants Bedrock only through `bedrock_model_arns` (default empty). The fix needs Terraform and/or a core config-contract change.
- H2 HIGH (breaks Phase 14): retrieval and the worker choose Voyage whenever `SCHOLARCHAIN_VOYAGE_API_KEY` is non-empty (`Composition.scala:411-415, 454-458`); Terraform always injects that secret, and a task cannot start while it is empty, so the Bedrock embedder path is unreachable. The fix needs service code, Terraform, and possibly a new embedding config key (contract).
- M1 MEDIUM: nothing in deployment calls `/readyz` (the ALB checks `/healthz`; no ECS healthCheck); the `config` probe cannot fail in AWS mode because startup already refuses. Owners: deploy-engineer (Phase 14 smoke tests), infra-engineer.
- LOW: L1 the Neptune URI filter is a no-op; L2 image tags are not checked against HEAD (push scripts must refuse `-dirty`); L3 the graph timeout of 10 s exceeds the 8 s downstream timeout, so retries multiply Neptune load; L4 async jobs are orphaned on task stop; L5 retrieval receives an unused services secret. Known items: (a) `rag/audit` correct; (b) the Neptune-only override is acceptable (LOW); (c) local mode is not self-contained, and `repoRoot` throws instead of refusing (LOW, service-engineer); (d) correct.
- Not re-reviewed this pass (reviewer's disclosure): Hadoop, Spark and ER sources; storage adapters beyond AwsClients and EmbeddingClaims; RAG grounding beyond PromptRepository and ModelWrapping.
- Orchestrator spot-check (read-only grep of the lane-2 worktree): `environments/course/main.tf:39-40` injects the Voyage secret into retrieval and embedding-worker; `INFRA-CONTRACTS.md:152` lists only `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID`; `INFRA-CONTRACTS.md:496` says `bedrock_model_arns` is empty by default. This is consistent with H1 and H2.
- §6 STOP condition met: H1 and H2 are HIGH, and their fixes need Terraform (out of scope, route to human) and/or a frozen config-contract change.

## Progress checkpoint — CHECK 1 (after P1 + P2 + T)

- Command (orchestrator, repository root, exactly): `sbt check; echo "exit=$?" > .claude/.last-test-run`. Before running it, the only sbt processes were in this repository (none in lane-2).
- Observed per-module lines: `Passed: Total 955`, `Passed: Total 323`, `Failed: Total 287, Failed 2, Errors 0, Passed 285`, `Passed: Total 293`, `Passed: Total 192`, `Passed: Total 365`, `Passed: Total 222`. `[error] Failed tests: scholarchain.spark.AdversarialGraphSuite`; `(sparkAnalytics / Test / testFull) sbt.TestsFailedException`; `elapsed time: 736 s`.
- Sum: **2,637 total, 2,635 passed, 2 failed** (Phase 10: 2,555; +82, equal to T's 82 new tests). Marker: `exit=1`. **STATUS: FAILED.**
- The 2 failures are the two KCore tests T reported (`AdversarialGraphSuite.scala:118`, `:123`), left in place on purpose. No other failure.

## Progress checkpoint — STOP (§6), waiting for human decision

The repair round has NOT started. Reason: H1 and H2 are HIGH, would break Phase 14, and their fixes need Terraform (out of Phase 11 scope) and/or a change to the frozen core or embedding configuration contract. Proposed grouping in `repair-01.md` (draft, not routed). Not staged or committed.

## Progress checkpoint — human decisions received (2026-11-24)

Recorded as given by the human (summary; the human's text is authoritative):
1. H1: no core contract change. Runtime is Bedrock only (no Anthropic API). Provider `bedrock`, model Claude Haiku 4.5 through the US cross-region inference profile `us.anthropic.claude-haiku-4-5-20251001-v1:0`. Routed to the lane2→main merge as **MERGE-1** (Terraform): set the runtime's `rag.model.*` env overrides, remove `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID`, set `bedrock_model_arns` = Titan Embed Text v2 ARN + Haiku 4.5 inference profile ARN + Haiku 4.5 foundation-model ARNs in the regions the profile routes to.
2. H2: no contract change. Routed to the merge as **MERGE-2** (Terraform): stop injecting `SCHOLARCHAIN_VOYAGE_API_KEY` and `SCHOLARCHAIN_ANTHROPIC_API_KEY`. Bedrock Titan is the embedder. service-engineer documents in its ops doc that Voyage is selected only when its env var is present.
3. Repair round: spark-engineer fixes KCore (dedupe arcs, drop self-loops; the two new tests unchanged); test-engineer adds "missing topic, institution, abstract or profile metadata" tests and checks existing coverage. M1 and all LOWs deferred with disclosure.
- H1 and H2 are accepted by the human as routed to the merge, not open within Phase 11.

### H1 source evidence (orchestrator, read-only)

- Env var names the runtime reads. `RuntimeConfig.scala:20-21` defines keys `rag.model.provider` and `rag.model.bedrock-model-id`. `Config.scala:21-22`: `envName = ConfigResolver.EnvPrefix + key.toUpperCase.replace('.', '_').replace('-', '_')`. `Config.scala:111`: `EnvPrefix = "SCHOLARCHAIN_"`. `ModuleConfig.scala:43-44` reads `env` by `k.envName`, and `RuntimeConfig.scala:7` documents `SCHOLARCHAIN_RAG_<KEY>`. The names are therefore:
  - provider: **`SCHOLARCHAIN_RAG_MODEL_PROVIDER`** (accepted values `bedrock`, `anthropic`, `fake`; `RuntimeConfig.scala:158`)
  - bedrock model id: **`SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID`**
- Inference profile ID as the model id:
  - Validation (`RuntimeConfig.scala:61, 159`) checks only that the value is a token: non-blank, no whitespace, at most 200 characters. `us.anthropic.claude-haiku-4-5-20251001-v1:0` (44 characters) passes.
  - `ModelClients.scala:388-391` passes `s.bedrockModelId` unchanged into `BedrockConverseModelClient`, and `ModelClients.scala:258-260` sets it verbatim as `ConverseRequest.builder().modelId(call.modelId)`. No prefix or format rewriting happens.
  - The shipped default `scholarchain-rag.v1.conf:14` is already an inference profile ID (`us.anthropic.claude-sonnet-4-5-20250929-v1:0`).
  - Not verified: that the Bedrock Converse API accepts this profile ID for this account and region. No AWS call was made; that is first observable in Phase 14.
- In AWS mode query-api requires `SCHOLARCHAIN_ANTHROPIC_API_KEY` only when the provider is `anthropic` (`Composition.scala` `awsQueryApi`), so removing that injection (MERGE-2) is consistent with provider `bedrock`.
- Embedder selection (H2): `Composition.scala` retrieval and worker choose Voyage only when `SCHOLARCHAIN_VOYAGE_API_KEY` is present and non-empty, else `EmbedderFactory.bedrock`. With MERGE-2 the Bedrock (Titan) path is taken.

## Progress checkpoint — repair F-SPARK-1 (spark-engineer)

- STATUS: COMPLETE (agent report). Modified `modules/spark/src/main/scala/scholarchain/spark/KCore.scala` (initial arcs `select(window,src,dst).where(src =!= dst).distinct()`; doc note) and `modules/spark/SPARK-CONTRACTS.md` (K-core bullet: dedupe and drop self arcs, for robustness only). Tests unchanged.
- Agent-observed targeted runs: AdversarialGraphSuite `Passed: Total 19, Failed 0`; GraphAlgorithmsSuite `Passed: Total 25, Failed 0`; FixtureMetricsSuite `Passed: Total 15, Failed 0`; DeterminismSuite `Passed: Total 6, Failed 0`.
- The agent checked Components (min-label, unaffected; the existing self/duplicate-arc test passes) and BFS (by code reading, unaffected; no separate run). Degree was not reviewed. Pipeline outputs are expected to be identical, because the pipeline never emits duplicate or self arcs.

## Progress checkpoint — repair F-SVC-1 (service-engineer, docs only)

- STATUS: COMPLETE (agent report). Modified `modules/services/API.md`: line 162 now refers to the new subsection; lines 166-181 add "Model and embedder selection (Bedrock-only course deployment)". It documents that Voyage is chosen only when `SCHOLARCHAIN_VOYAGE_API_KEY` is present and non-empty (Composition.scala 412-414, 455-457), that `SCHOLARCHAIN_ANTHROPIC_API_KEY` is required only for provider `anthropic` (347-349), the course values for `SCHOLARCHAIN_RAG_MODEL_PROVIDER` and `SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID`, and that `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` is not read (the agent's grep of core, rag and services `src/main` found nothing). No sbt run (docs only).

## Progress checkpoint — repair F-TEST-1 (test-engineer)

- STATUS: COMPLETE (agent report). 23 new tests, test-only files: `entity-resolution/.../er/MissingMetadataSuite.scala` (5), `hadoop/.../hadoop/MissingMetadataNormalizerSuite.scala` (6), `storage/.../storage/MissingMetadataVectorSuite.scala` (3), `rag/.../rag/runtime/MissingMetadataAnswerSuite.scala` (3), `services/.../services/MissingMetadataApiSuite.scala` (3), `spark/.../spark/MissingMetadataSummariesSuite.scala` (3).
- Agent-observed targeted runs: 5/5, 6/6, 3/3, 3/3, 3/3, 3/3 passed, 0 failed. One invocation was rejected for a wrong project id (`entity-resolution`) and rerun as `entityResolution`. The services suite was run 3 times, the last after tightening the assertion to HTTP 200. One BSD `sed -i` failed and changed nothing.
- Existing-coverage audit found coverage for topic (rag renderer, hadoop normalizer, spark fixture metrics), affiliation (er FeaturesSuite and PolicySuite), abstract (rag renderer) and profile/ORCID (er, storage resolver). Gaps filled: ER one-sided or blank affiliation; hadoop blank topics, institution, country, abstract not carried; storage title-only vector round trip; rag institution-filtered question reports missing evidence with no affiliation claim; services node without affiliation, abstract or profile, and an institutions query that returns 200 without invention; spark profiles without affiliation. No production defect found.
- Agent-disclosed limits: the rag and services tests allow an abstention (they guard against fabrication, not wording); no dedicated author-profile entity exists in the model.

## Progress checkpoint — CHECK 2 (after repair-01)

- Command (orchestrator, repository root, exactly): `sbt check; echo "exit=$?" > .claude/.last-test-run`. Before running it, the only sbt process was this repository's server.
- Observed: marker `exit=1`; `[error] elapsed time: 0 s`. **STATUS: FAILED.** No test ran. Failing tasks: `hadoop / Test / scalafmtCheck`, `storage / Test / scalafmtCheck`, `rag / Test / scalafmtCheck`, `entityResolution / Test / scalafmtCheck` (each "1 files must be formatted"), and `sparkAnalytics / Compile / scalafmtCheck` ("1 files must be formatted").
- Attribution (inferred from the failing module and config, not printed by sbt): spark Compile → `modules/spark/src/main/scala/scholarchain/spark/KCore.scala` (spark-engineer edited it with a python snippet, so the PostToolUse scalafmt hook did not run; owner spark-engineer). The four Test failures → the new MissingMetadata suites in hadoop, storage, rag and entity-resolution (owner test-engineer). The services suite was reformatted by the hook and passed. The Phase 11 T suites passed format in CHECK 1.
- §6 STOP: "CHECK fails after the repair for a non-resource reason". The §0.3 unchanged rerun covers only a timeout or OOM, so it does not apply. One CHECK remains of the three allowed (only that rerun kind was left). Waiting for the human.

## Progress checkpoint — reviewer reread of repair-01

- Verdict on the repair: **REQUEST_CHANGES** (formatting plus a recorded passing CHECK only; the logic of F-SPARK-1, F-SVC-1 and F-TEST-1 is acceptable). Details in `review-report.md`, section "Reread of repair-01".
- H1 source evidence VERIFIED by the reviewer. Ownership PASS.
- MEDIUM 1 (test-engineer) is the formatting failure that CHECK 2 confirmed. LOW 2 (service-engineer, `API.md:181` wording) and LOW 3-6 (test-engineer, weak assertions) are new.
- The reviewer read before the CHECK 2 checkpoint was written, so its report says no post-repair CHECK was recorded.

## STOP — waiting for the human (§6)

Phase 11 is not complete. CHECK 2 failed for a non-resource reason (scalafmtCheck). Not staged or committed.

## Progress checkpoint — human approval of a formatting pass (2026-11-24)

Human decision (summary): one formatting-only pass, plus the reread's LOW 2-6 in the same pass (the `API.md:181` wording and the weak test assertions). Run scalafmt on every file touched in this pass, not only the five. No other changes. Then CHECK 3 (the last allowed run), record the totals, and STOP for the gate. If CHECK 3 fails for any reason, STOP and report without fixing.

## Progress checkpoint — FMT-SPARK (spark-engineer)

- STATUS: COMPLETE (agent report). `KCore.scala` was reformatted by `sbt "sparkAnalytics/scalafmtOnly <absolute path>"` → `Reformatted 1 Scala sources`. The first attempt with a relative path failed and changed nothing.
- `git diff --stat -- modules/spark`: KCore.scala went from 7 to 11 changed lines; SPARK-CONTRACTS.md was untouched; the whole-repo diff otherwise did not change.
- `sbt "sparkAnalytics/scalafmtCheck; sparkAnalytics/Test/scalafmtCheck"` → `[success]`. Two earlier invocations failed with an sbt command-parse error, and no check ran in them. No tests were run.

## Progress checkpoint — LOW-2 (service-engineer, docs only)

- STATUS: COMPLETE (agent report). `modules/services/API.md:181` changed from "is not read by the services (no reference in `modules/*/src/main`)" to "is defined in core `ModelConfig` (`models.generation-model-id`, `Config.scala:36` and `:80`) but is not consumed by rag or services". The agent's grep found the key only in core (and a comment in `scholarchain-config.v1.conf:12`). No sbt run.

## Progress checkpoint — FMT-TEST (test-engineer)

- STATUS: COMPLETE (agent report). Five test-only files were reformatted with `scalafmtOnly` (absolute paths; `Reformatted 1 Scala sources` each): the rag, storage, ER and hadoop MissingMetadata suites and `MissingMetadataApiSuite`.
- LOW 3: `assert(items.nonEmpty)`.
- LOW 4: the institution query asserts empty `answer.researchers`, no `Verified` claim, no "affiliated with example" in the summary, and a non-empty `abstainReason` if it abstains. The REST API does not expose `missingEvidence`; that is asserted in the rag suite.
- LOW 5: the ER test now compares a one-sided affiliation against both-absent, with a two-sided MIT vs Oxford control that scores differently; the empty-map 0.5 check is its own test. The rag `"mit"` check is now token-based; the venue test asserts the exact "cannot filter by topic, venue or institution" entry.
- LOW 6: the hadoop test was retitled "bad country code", and a new test "institution with no country_code keeps the row with no country" was added.
- Agent-observed: `Test/scalafmtCheck` `[success]` for rag, storage, entityResolution, hadoop and services. Targeted runs: rag 3/3, storage 3/3, ER 6/6, hadoop 7/7, services 3/3, all passed. The first services run failed to compile on the agent's own field-name error (`status` instead of `verificationStatus`); after a `sed` fix and re-format it passed.
- New tests in the phase so far: 82 (T) + 25 (F-TEST-1 23, plus 2 in this pass) = 107; expected CHECK total 2,555 + 107 = 2,662 (expectation, not observed).

## Progress checkpoint — CHECK 3 (last allowed run, after the formatting pass)

- Command (orchestrator, repository root, exactly): `sbt check; echo "exit=$?" > .claude/.last-test-run`. Before running it, the only sbt process was this repository's server.
- Observed: marker **`exit=0`**; `[success] elapsed time: 1553 s (0:25:53.0)`; `markTestRun: wrote .../.claude/.last-test-run`. The scalafmt checks passed. No `==> X` lines and no `[error]` lines.
- Per-module test totals (the final summary block): `Passed: Total 199`, `299`, `368`, `326`, `955`, `290`, `225`, all `Failed 0, Errors 0`. Sum: **2,662 passed, 0 failed**. Phase 10 had 2,555, so **+107**, matching the 107 new tests.
- Reported separately and not added to the total (same convention as the Phase 07 correction): the contract-test block `Passed: Total 23`, `926`, `10` (all `Failed 0`), and several `Total 0` / "No tests to run" lines for testOnly steps.
- `AdversarialGraphSuite` `0 failed, 19 total`: the two KCore tests now pass. `MissingMetadataApiSuite` `0 failed, 3 total`.

## Final checkpoint — Phase 11 complete; waiting for human gate

- P1 (pre-deploy fixes 1 and 3) and the dry start: done and tested (`P1FixesSuite`, 37 tests).
- P2 (pre-deploy fix 2): images `scholarchain/{query-api,retrieval,embedding-worker}:932fd2f-dirty` were built locally with the real mains and `prompts/`. Never pushed. Local `/healthz` and `/readyz` returned 200 for query-api and retrieval (with a bind-mounted fixture dir, see the P2 checkpoint); the worker has no local mode. The images hold uncommitted code: rebuild from a clean commit before any deploy.
- T: 82 adversarial tests. F-TEST-1 and FMT-TEST: 25 more. Total +107.
- R: REQUEST_CHANGES (H1, H2 HIGH). The human accepted both as routed to the lane2→main Terraform merge (MERGE-1, MERGE-2), so they are not open in Phase 11. M1 and all LOWs from the full review are deferred with disclosure (listed in `review-report.md` and `repair-01.md`).
- Repair reread: REQUEST_CHANGES for formatting and a passing CHECK only. The formatting and LOW 2-6 pass was human-approved, and CHECK 3 then passed. **The reviewer has not reread the formatting and LOW 2-6 pass**; the human's instruction was CHECK 3, then STOP.
- Canonical CHECK runs: 3 of 3 used (CHECK 1 exit=1, the 2 KCore tests; CHECK 2 exit=1, scalafmt; CHECK 3 exit=0).
- Not run: any AWS, model or embedding call; image push; Bedrock acceptance of the Haiku 4.5 inference profile ID (first observable in Phase 14).
- Nothing staged or committed.
