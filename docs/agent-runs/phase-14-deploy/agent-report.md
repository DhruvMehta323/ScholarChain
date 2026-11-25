# Phase 14: agent report (assembled by the orchestrator)

## Preconditions (read-only, 2026-11-24)

| Check | Result |
|---|---|
| HEAD on `main` contains the lane2 merge | PASS: HEAD `a375c00 Merge lane2: Phases 12, 13, 15a` |
| `git status --short` clean except this folder | PASS: only `?? docs/agent-runs/phase-14-deploy/` |
| Contract check (`INFRA-CONTRACTS.md` §Services vs `6b17edab…e2114`) | **FAIL: STOP** |

### Contract check: observed values

- Recorded value (Phase 10 `agent-report.md` line 10): `6b17edab36be9ce1ed5174447c28bfe2d0f07e336ebcfc739d16f0faec8e2114`. That report labels it **"SHA-256 (whole file)"** of the then-uncommitted lane2 copy, not a hash of §Services alone.
- Current whole file (`shasum -a 256 infra/terraform/INFRA-CONTRACTS.md`): `b4fa16aa130f3106ad6190088497374022cc4a0b2ff150448f232849d7a4d061` (same in lane2 working copy and in commits `3115aa3`, `a375c00`).
- Current §5 only (from `## 5. Services` up to, not including, `## 5b.`): `b25aaec6e5ceb508ecaa662b408395120baa83677c449a50f074012bd186b3c0`.
- Phase 12 commit `5edd031` whole file: `f591d362edd7327f01255d79fcb844585a5b1448823e1b7890ba2ce796c31eb2`.
- No committed version hashes to `6b17edab…`. The provisional text was never committed, so a byte-level diff is impossible. The differences below compare the Phase 10 summary table (agent-report.md lines 12-18) with the current §5.

### Differences (Phase 10 provisional summary → current §5)

1. **query-api health path:** `GET /health` (200) → the ALB target health check is `GET /healthz`. Every service exposes `/healthz`, `/readyz`, and `/health` as an alias. §5 marks this paragraph "NOT VERIFIED against code". (Phase 10 human decision 2.)
2. **query-api model secret:** `ANTHROPIC_API_KEY` → `SCHOLARCHAIN_ANTHROPIC_API_KEY`. (Decision 1.)
3. **query-api new secrets:** `SCHOLARCHAIN_SERVICES_API_KEY` and `SCHOLARCHAIN_INTERNAL_API_KEY`. (Decisions 3, 4.)
4. **retrieval new secrets:** `SCHOLARCHAIN_SERVICES_API_KEY` and `SCHOLARCHAIN_INTERNAL_API_KEY` in addition to `SCHOLARCHAIN_VOYAGE_API_KEY`. (Decision 4.)
5. **retrieval health:** "none stated" → `/healthz`, `/readyz`, `/health` (decision 2). §5 also adds a paragraph: the only retrieval ingress is from the query-api security group, and egress is 443 plus 8182 to Neptune.
6. **Text that may be new (cannot be confirmed without the provisional bytes):** Fargate size 0.25 vCPU / 512 MiB; autoscaling min 0 / max 4; scale-up through `ecs_min_tasks` in a gitignored tfvars; SQS parameters and the maxReceiveCount = 4 rationale; the live-suite environment output; the list of variables Terraform does not set.
7. **Unchanged:** ports, exposure, the embedding-worker variables, and the shared environment variable names, including `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` (which MERGE-1 is to remove) and the Neptune omission rule (decision 6).

Items 1-5 match the Phase 10 human decisions 1-4, and G12-1 in the Phase 12 gate routed this reconciliation to "before Phase 14". The work order still says "If different, list every difference and STOP", so the orchestrator stopped.

## Progress checkpoint: preconditions, STOP

```
STATUS: BLOCKED-ON
OWNER: orchestrator
WORK ORDER: Phase 14, section 1 preconditions
FILES CREATED: docs/agent-runs/phase-14-deploy/agent-report.md
FILES MODIFIED: none
COMMANDS RUN: git log/status; shasum -a 256 on INFRA-CONTRACTS.md (working tree, 5edd031, 3115aa3, a375c00, lane2) and on its §5 extract; grep of phase 10/11/12/13 evidence
TEST RESULTS: NOT RUN (no part reached CHECK)
VALIDATION RESULTS: contract check FAILED (hash differs; differences listed above)
ASSUMPTIONS: the §5 extract runs from "## 5. Services" up to, not including, "## 5b."
RISKS: Part A (MERGE-1, MERGE-2) will change §5 again, so any new baseline hash should be recorded after Part A
MANUAL ACTION REQUIRED: human decides whether the listed differences are accepted (for example, as the ratified Phase 10 decisions 1-4) and which hash becomes the baseline (whole file b4fa16aa…d061 or §5 b25aaec6…b3c0), then writes the instruction in the session
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: prompt.md §1 "If different, list every difference and STOP"; docs/agent-runs/phase-10-services/agent-report.md:10
NEXT GATE: human decision on the contract check; then Part A
```

No agent dispatched. No AWS call made. Nothing staged or committed.

## Baseline CHECK (forced by the Stop hook, before Part A)

- Trigger: the Stop hook flagged `tests/datasets/scale_helpers.py` (mtime 10:36:02) as newer than the marker (03:03:13). `git status` shows the file clean at HEAD, so only its mtime changed. The orchestrator did not edit it.
- Rule 8: lane 2 was running `sbt check` (pid 31174, started 10:26:18). The orchestrator waited until it exited (10:51:47; lane 2 marker `exit=0` at 10:51:36).
- Command: the canonical CHECK command from the repository root. Marker: `exit=0`. sbt: `[success] elapsed time: 1570 s`.
- `Passed: Total` lines, in output order: 955, 299, 326, 368, 225, 199, 290, 0, 926, 0, 23, 0, 0, 0, 10, then 290, 299, 955, 368, 326, 225, 199. The second group repeats seven earlier values, and the output does not say which module each line belongs to. Every line shows Failed 0, Errors 0.
- Whether this run counts toward the Part B CHECK limit: human decision.

## Human decisions on the precondition STOP (2026-11-24, in session)

1. Accepted: the contract differences above match the Phase 10 decisions 1-4.
2. Baseline: the §5-only hash `b25aaec6e5ceb508ecaa662b408395120baa83677c449a50f074012bd186b3c0` is the pre-Part-A baseline. After Part A, the new §5 hash is recorded here and goes into the Phase 14 gate. The whole-file hash is informational only.
3. The baseline CHECK does not count against Part B, which keeps its two CHECKs.
4. Start Part A.

## Part A dispatch

Three agents were dispatched in parallel: infra-engineer (MERGE-1, MERGE-2, M13-1), build-engineer (M13-3) and storage-engineer (M13-2 assessment). Orchestrator finding before dispatch: no main in `src/main` performs a live Neptune, DynamoDB or SQS load or enqueue. `scholarchain.storage.LocalLoad` writes local files only and makes no AWS call (`LocalLoad.scala:11-33`).

## Progress checkpoint — M13-3 (build-engineer)

- STATUS: COMPLETE (agent report). `.gitignore` gets 24 appended lines; no existing line changed (orchestrator `git diff --stat`: 24 insertions).
- Ignored: `reports/release-local/`, `reports/evidence/`, `reports/cleanup/`, `reports/env-window/`; everything under `reports/release/` except `<sha>/artifacts.json`; everything under `reports/pipeline/` except `<scale>/<run>/run.json` and `steps.json`; everything under `reports/experiments/` except `<id>/<run>/run.json`; and `reports/**/*.log`.
- Committable: `reports/deployments/**` (JSON), `reports/smoke/*.json`, `reports/final-report.md` and `reports/dependency-updates/`. `.claude/.last-test-run` was already ignored (line 34).
- Agent deviation: `reports/pipeline/*/*/config.json` is ignored.
- Clean-tree logic (`scripts/lib/common.sh:310-316`) is unchanged in behavior.
- Orchestrator spot check, `git check-ignore --no-index -v`: pipeline `run.json` is negated (committable, line 63) and the evidence `MANIFEST.json` is ignored (line 51).
- Agent-raised risk, routed to infra-engineer and the reviewer: if `reports/release/<sha>/artifacts.json` is committed, the new HEAD no longer equals `<sha>`, so `deploy.sh`'s commit match would fail. Keep it uncommitted until the deploy.

## Progress checkpoint — M13-2 (storage-engineer): BLOCKED-ON, STOP for a human decision

- STATUS: BLOCKED-ON (agent report). Read-only work; nothing written, no sbt, no AWS.
- Finding: no existing entry point does the live load or enqueue. The only storage main is `LocalLoad` (`LocalLoad.scala:55`). It is local-path only and runs on the Test classpath (`:25-31`, `:39-53`). The only render-plus-enqueue composition is test-only (`modules/services/src/test/.../LocalEmbeddingPipeline.scala`).
- The agent found three reasons a "thin operator-machine wrapper" is not trivial. The orchestrator verified 1 and 2:
  1. Neptune sits in private subnets with only the local route. Bulk-load start and poll (`neptunedata` on 8182) are reachable only from inside the VPC (ECS task or EMR node), not from the operator machine (INFRA-CONTRACTS.md §2, verified).
  2. The enqueue needs `EmbeddingDocumentRenderer` from rag. storage depends only on core (`build.sbt:160-161`, verified), and only services may compose rag and storage (CLAUDE.md). So the enqueue main belongs to service-engineer, not storage.
  3. A live load needs S3 input I/O, CSV upload, loader polling with timeouts, a ledger entry and a manifest. Storage has no assembly to run on EMR (`build.sbt:346` packs only hadoop and spark). That makes it new orchestration plus a build-structure change.
- Options from the agent:
  - **A:** EMR steps on the same bounded cluster. build-engineer adds an assembly (boundary change, needs review), storage-engineer adds `LiveLoad`, service-engineer adds a capped render-and-enqueue main, test-engineer writes the tests listed in the agent report.
  - **B:** DynamoDB and S3 staging from the laptop. The Neptune load still needs an in-VPC runner that Terraform does not provide.
  - **C:** drop load and enqueue for Phase 14.
- Orchestrator assessment:
  - The work order allows an EMR entry point "only if one already exists or is a trivial wrapper". Option A is neither, and it crosses a build boundary, which is a §7 STOP condition.
  - Option C fails the Phase 14 gate (storage load and ≤10K vectors done; evidence-backed real-data answer).
  - B does not complete the Neptune load.
- State: infra-engineer (MERGE-1, MERGE-2, M13-1) is still running. Nothing was staged or committed, and no AWS call was made.
- MANUAL ACTION REQUIRED: human chooses A, B, C, or another path, and approves any build-structure change.

## Progress checkpoint — MERGE-1, MERGE-2, M13-1 (infra-engineer)

- STATUS: COMPLETE (agent report). Files modified: `environments/course/{main.tf,variables.tf,terraform.tfvars.example}`, `modules/compute/{main.tf,variables.tf}`, `modules/iam/main.tf`, `scripts/{package.sh,deploy.sh,lib/images.sh,tests/test-scripts.sh}` and `INFRA-CONTRACTS.md`.
- MERGE-1: `SCHOLARCHAIN_RAG_MODEL_PROVIDER=bedrock` and `SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID=var.generation_inference_profile_id` are set on query-api only (`main.tf:50-51`). The agent cites `Composition.scala:351-363`: only `awsQueryApi` builds a model client. `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` is removed (orchestrator grep: no `.tf` reference left).
- Bedrock grants:
  - query-api gets the us-east-2 inference profile `us.anthropic.claude-haiku-4-5-20251001-v1:0`, with the account taken from the caller-identity data source.
  - It also gets foundation model `anthropic.claude-haiku-4-5-20251001-v1:0` in us-east-1, us-east-2 and us-west-2. That region list is a **HUMAN CHECK**: the repo cites no source and no AWS or web call was made. The variable is `generation_profile_regions`.
  - Titan Embed v2 (us-east-2) stays granted to retrieval and embedding-worker only. **Deviation:** Titan is not in `bedrock_model_arns`, which avoids widening query-api's IAM. `bedrock_model_arns` now means "extra ARNs" and defaults to empty.
- MERGE-2: no task definition injects `SCHOLARCHAIN_VOYAGE_API_KEY` or `SCHOLARCHAIN_ANTHROPIC_API_KEY`. The secret resources remain (`variables.tf:173-174`). The execution-role secret grant is now dynamic (embedding-worker has none). `deploy.sh` checks only the services and internal keys.
- M13-1: `package.sh` runs `SCHOLARCHAIN_GIT_SHA=<tag> sbt dockerLocal`, inspects the three `scholarchain/<svc>:<tag>` images, checks each image's revision label and records each `image_id`. No re-tagging. `deploy.sh` pushes three images, registers three task definitions and updates three services.
- Agent-observed validation:
  - `terraform fmt -check` and `terraform validate` (`-backend=false`): pass.
  - `bash -n`: pass. shellcheck: 0 findings.
  - Offline harness: 272 passed, 0 failed.
  - trivy: 0 HIGH or CRITICAL; 4 LOW, previously accepted.
  - `terraform plan`: NOT RUN (needs AWS).
- Orchestrator rerun of `bash scripts/tests/test-scripts.sh`: `RESULT passed=272 failed=0`.
- Orchestrator note for the reviewer: `variables.tf:154` (`embedding_provider` description) still says "voyage: ... key injected as SCHOLARCHAIN_VOYAGE_API_KEY". With MERGE-2 that may be stale.
- §5 hash after these edits (informational; Part A is not complete while M13-2 is blocked): `91a37f1f7050c9ca16b652dba5a51c43369c2b5e55dd7f021f2709ee55ed479d`.
- Human items raised by the agent: approval of the IAM changes (security-change rule); the region HUMAN CHECK; run `sbt shutdown` before `package.sh` (a running sbt server keeps its own environment and may ignore `SCHOLARCHAIN_GIT_SHA`); a local gitignored tfvars that still sets `generation_model_id` will now fail.

## Human decisions (2026-11-24, in session, second round)

1. M13-2: option D. A one-off ECS Fargate ops task inside the VPC, using an existing service image with a command override. No new assembly and no `build.sbt` change.
   - storage-engineer: a live loader.
   - service-engineer: an ops entry point (loader, then a render-and-enqueue step capped at 10K), with the image recorded.
   - infra-engineer: the ops task definition, a least-privilege task role, a security group allowed to Neptune on 8182, the bulk-load S3 role if missing, and a `run-bounded-pipeline.sh` step (`aws ecs run-task`, wait, record the exit code and logs).
   - test-engineer: tests.
   - STOP if a build-structure change or an ownership crossing is needed.
2. IAM changes from MERGE-1, MERGE-2 and M13-1 approved, including the narrower Titan scope.
3. The stale comment at `variables.tf:154` goes to infra-engineer in the same pass.
4. The human runs `sbt shutdown` before `package.sh`.

**Region HUMAN CHECK (human-observed, pasted in session):** `aws bedrock get-inference-profile` for `us.anthropic.claude-haiku-4-5-20251001-v1:0` returns foundation-model ARNs in us-east-1, us-east-2 and us-west-2 only. The `generation_profile_regions` default is confirmed.

## Orchestrator pre-dispatch check for option D: STOP (one conflict)

- Spark writes its tables as Parquet only (`modules/spark/src/main/scala/scholarchain/spark/Export.scala:67-68`). The only Parquet reader in storage is `ParquetTables`, which needs Spark. It is the only storage file importing `org.apache.spark`.
- The service images may not contain Spark or Hadoop. `servicesClasspathGuard` (`build.sbt:206-214`) fails the build if `spark-*` or `hadoop-*` is on the services Runtime classpath, and Spark SQL is Test scope only (`build.sbt:201-202`).
- Consequence: a loader running inside an existing service image cannot read the Spark Parquet output. Adding a Parquet reader (parquet-hadoop pulls Hadoop jars) is a `build.sbt` change that the guard forbids, which is the human's STOP condition.
- The row logic (`NeptuneCsv`, `AnalyticsItems`, `Cells`) does not depend on Spark, so only the Parquet read is blocked.
- No agent was dispatched for option D.

## Human decision (AskUserQuestion): M13-2 = option D with local conversion

Orchestrator-fixed interface contract for the routed runs. Owners may refine names and must report any deviation.

1. **Operator conversion step** (in `run-bounded-pipeline.sh`, after Spark):
   - Sync `s3://<curated>/graph/runs/<scale>/<run>/spark/` to a local temporary directory.
   - Run the existing, unchanged `sbt "storage/Test/runMain scholarchain.storage.LocalLoad --run-root <abs> --out-dir <abs> --code-version <sha12>"`.
   - Upload `neptune/vertices/*.csv` and `neptune/edges/*.csv` to `s3://<curated>/graph/neptune-load/<scale>/<run>/`.
   - Upload `dynamodb/items/*.jsonl`, `load-manifest.json` and `checksums.sha256` to `s3://<curated>/curated/loads/<scale>/<run>/`.
   - Never overwrite.
2. **Ops task** (ECS Fargate, inside the VPC, no Spark):
   - Image `embedding-worker` (the `sbt dockerLocal` tag of the release commit), command override running `scholarchain.services.OpsLoadMain`.
   - Arguments: `--neptune-source s3://…/graph/neptune-load/<scale>/<run>/`, `--items-source s3://…/curated/loads/<scale>/<run>/`, `--run-id <run>`, `--code-version <sha12>`, `--max-vectors 10000` (hard cap 10000), `--neptune-timeout-seconds N`, `--dry-run`.
   - Steps: verify the checksums, start the Neptune openCypher bulk load and poll it, put the DynamoDB items, render and enqueue at most 10K embedding jobs, and write a run manifest (provenance) to `s3://<curated>/curated/loads/<scale>/<run>/ops-manifest.json`.
   - Exit codes: 0 ok, 1 failure, 2 usage, 3 cap or invariant refusal.
3. **storage-engineer:** a Spark-free `LiveLoad` library (plus a thin main if useful) that reads that layout from S3.
4. **service-engineer:** `OpsLoadMain` composing `LiveLoad` with the rag renderer and `EmbeddingProducer`.
5. **infra-engineer:** the task definition, role, security group, bulk-load role check and script steps.
6. **test-engineer:** the tests.

## Human conditions on the variant (2026-11-24, in session)

- If the embedding render inputs also need Spark to read, the documents are rendered locally in the same operator step and uploaded as JSONL, and the ECS ops task only enqueues them (cap 10K). service-engineer confirms which path applies.
- The local conversion step is a `run-bounded-pipeline.sh` step run by the operator (infra-engineer). It records the S3 source run ID, the checksums of the uploaded CSV and JSONL files, and the LocalLoad manifest, so the chain from Spark output to Neptune is traceable.
- **Disclosure:** the Parquet-to-load-file conversion runs on the operator machine (sbt Test classpath), because the service images exclude Spark by design (`build.sbt:201-214`). It does not run in AWS.

## Progress checkpoint — service-engineer assessment (read-only): human decision needed

- STATUS: COMPLETE (assessment; nothing written).
- Render inputs:
  - `EmbeddingDocumentRenderer.renderDetailed` (`EmbeddingDocumentRenderer.scala:66-95`) needs `SemanticSummary`, `WorkRecord`, `Venue` and `Topic`.
  - `SemanticSummary` exists only as the Spark Parquet table `semantic-summaries`. It is not in the DynamoDB items, the Neptune CSVs or the Hadoop output (`LoadManifests.scala:198-213`).
  - `WorkRecord.topicIds` comes from the Spark works table.
  - So the render inputs need Spark to read. That is path (b) under the human's condition.
- Path (b) as worded hits the STOP. A Parquet-reading render main cannot be in `modules/services/src/main` (`build.sbt:201-214`). It could only live in `modules/services/src/test` (test-engineer's tree, a production-use tool in a test tree) or come with a build change.
- Agent-recommended compliant variant **(c)**:
  - storage-engineer extends `LocalLoad` (storage-owned, no build change, already on the Test classpath) to also emit `embedding-inputs/{semantic-summaries,works,venues,topics}.jsonl`, listed in `checksums.sha256` and `load-manifest.json`.
  - The operator step uploads them to `curated/loads/<scale>/<run>/embedding-inputs/`.
  - `OpsLoadMain` (Spark-free) reads them, converts the cells to core records (no new algorithm), renders, and enqueues capped at 10K. Rendering then stays in ECS.
  - This departs from the contract line "LocalLoad unchanged".
- Orchestrator-verified fact: `packaging/entrypoint.sh` does not forward `"$@"`, so an ECS command override is discarded. The ops task must select its main and arguments through environment overrides (`SERVICE_MAIN=scholarchain.services.OpsLoadMain`, `SCHOLARCHAIN_OPS_*`). infra-engineer was told (correction message sent mid-run).
- Enqueue idempotency (agent, from code):
  - Text objects are content-addressed with `putIfAbsent`, and job and dedup IDs are deterministic (`EmbeddingQueue.scala:29-33, :98-100, :261`).
  - The cap refuses rather than truncates (`:117-118`).
  - Planned cap: hard 10000; render summaries first, then papers up to the remainder via `paperLimit`; refuse with exit 3 if still over.

## Human decision (AskUserQuestion): render path (c)

- `LocalLoad` is extended additively by storage-engineer to emit `embedding-inputs/*.jsonl`, with checksums. The operator step uploads them.
- `OpsLoadMain` renders and enqueues inside the ECS task, capped at 10K.
- The "LocalLoad unchanged" line of the contract is superseded for this addition only. The existing outputs must stay byte-identical.
- storage-engineer was told mid-run; infra-engineer had already been told about the extra prefix and the env-override invocation.
- Human conditions on (c), in session:
  - The LocalLoad extension is backward compatible: existing outputs and tests are unchanged.
  - The new JSONL files and their checksums are in the LocalLoad manifest.
  - test-engineer adds tests for the new outputs and for OpsLoadMain's render-and-enqueue cap.

## Progress checkpoint — M13-2 storage (storage-engineer run 2)

- STATUS: COMPLETE (implementation; agent-observed `sbt storage/compile` success, run 3 times). No test was run and no behavior test exists yet.
- Created `modules/storage/src/main/scala/scholarchain/storage/LiveLoad.scala`. It is Spark-free and uses fakeable ports.
  - `run` order: validate parameters, verify the checksum chain (manifest, CSVs, items, embedding inputs), apply the item cap, start the Neptune bulk load once (no retry), poll it with a timeout, then do the DynamoDB conditional puts (rerun reports unchanged; a conflict is never overwritten).
  - It returns a provenance JSON (`scholarchain.ops-load-provenance.v1`) with no role ARN, secrets or host paths. `writeProvenance` uses putIfAbsent.
  - Exit codes: 0 ok or dry-run; 1 Neptune, items or store failure; 2 usage; 3 checksum, source or cap.
- Modified `LocalLoad.scala`: it additively writes `embedding-inputs/{semantic-summaries,works,venues,topics}.jsonl` and the manifest key `embeddingInputs`, only when `load-manifests/s3-vectors.json` exists. The agent says the existing outputs are byte-identical by design. **Not verified by a run**; test 14 covers it.
- Layout adaptations, sent to infra-engineer:
  - The Neptune source holds `vertices/` and `edges/` with the `neptune/` prefix stripped, and nothing else may sit under it.
  - The items source keeps `dynamodb/items/` and `embedding-inputs/`, with `checksums.sha256` unmodified.
  - The last segment of both URIs must equal the run ID.
- IAM for the ops task role (agent list, sent to infra-engineer):
  - S3 Get and List on `graph/neptune-load/*` and `curated/loads/*`, plus Put on `curated/loads/*`.
  - `neptune-db:StartLoaderJob` and `GetLoaderJobStatus`.
  - DynamoDB PutItem and GetItem on the ten analytics tables.
  - KMS only if a CMK is used.
- Agent risks:
  - The payloadHash re-serialization could falsely refuse.
  - A Neptune timeout leaves the job running (no cancel).
  - A mid-load Neptune failure leaves a partial graph; recovery is a human decision.
- Dispatched next: service-engineer for `OpsLoadMain`, then test-engineer.

## Progress checkpoint — M13-2 infra (infra-engineer run 2)

- STATUS: COMPLETE (offline). `terraform plan` was NOT run.
- Terraform:
  - Ops task definition `scholarchain-ops` (`modules/compute/main.tf:116`): Fargate 512 CPU / 1024 MiB, embedding-worker image at `image_tag`, no command or healthcheck. `SERVICE_MAIN` is set by env override. Safe defaults: `SCHOLARCHAIN_OPS_DRY_RUN=true`, `MAX_VECTORS=0`.
  - Role `scholarchain-task-ops` (`modules/iam/main.tf:249-302`):
    - S3 Get on `graph/neptune-load/*`, `curated/loads/*` and `rag/text/*`.
    - S3 List on the bucket, with a prefix condition.
    - S3 Put on `curated/loads/*` and `rag/text/*`. No Delete.
    - DynamoDB PutItem and GetItem on the ten analytics tables.
    - `sqs:SendMessage` and `GetQueueUrl` on the embedding queue.
    - `neptune-db:StartLoaderJob` and `GetLoaderJobStatus`, only if `create_neptune`.
    - No KMS (the bucket is SSE-S3), Bedrock or Secrets Manager.
  - Ops security group (`modules/network/main.tf:303-332`): no inbound; egress 443, plus 8182 to Neptune and the matching Neptune ingress, only if `create_neptune`.
  - The Neptune bulk-load role already existed and is unchanged.
  - New outputs: ops family, ARN, security group, role, log group.
  - `variables.tf` `embedding_provider` description fixed. Region confirmation recorded.
- `run-bounded-pipeline.sh`: default stages are now `hadoop,spark,convert,load`. The EMR placeholders (`SC_LOADER_STEP_ARGS`, `SC_ENQUEUE_STEP_ARGS`, exit 7) are removed.
  - `convert` refuses while an `sbt check` runs (exit 6) and refuses non-empty targets (exit 3). It syncs the Spark output, runs `LocalLoad` on the sbt Test classpath, verifies `checksums.sha256` locally, uploads per the LiveLoad layout, and records `convert.json` (S3 source run ID, Spark run ID, the SHA-256 and size of every uploaded file, copies of `load-manifest.json` and `checksums.sha256`).
  - `load` refuses if Neptune is off (exit 6) or the cap is above 10000 (exit 3). It refuses (exit 6) unless the ACTIVE ops task definition's image tag equals the commit. It runs `aws ecs run-task` once with env overrides, polls with a timeout (`stop-task` then exit 5), records `load.json` and `steps.json` (task ARN, exit code, stop reason, log stream), and maps the ops exit 0→0, 3→3, other→1.
- Agent-observed validation:
  - `fmt -check`, `validate`, `bash -n` and shellcheck: pass.
  - Offline harness: 379 passed, 0 failed (was 272).
  - trivy: 0 HIGH or CRITICAL.
- Agent risks and human items:
  - The ops image comes from Terraform `image_tag`, not `deploy.sh`. The load stage needs `image_tag` set to the release commit and applied, which also moves the three services (M13-6).
  - The operator profile needs `ecs:RunTask`, `DescribeTasks`, `StopTask`, `DescribeTaskDefinition`, and `iam:PassRole` on the ops and execution roles.
  - Fargate price NOT checked. Nothing verified on real AWS.
- Orchestrator note for the reviewer: the ops role's `s3:PutObject` on `curated/loads/*` (iam `main.tf`, statement `WriteManifestAndTexts`) also allows overwriting the uploaded load files. `OpsLoadMain` writes only `<items-source>ops-manifest.json`, so narrowing it to `curated/loads/*/ops-manifest.json` looks possible. service-engineer's report assumed the narrow form.

## Progress checkpoint — M13-2 services (service-engineer run 2)

- STATUS: COMPLETE (implementation). Created `modules/services/src/main/scala/scholarchain/services/OpsLoad.scala` and added an "ops task" section to `modules/services/API.md`.
  - Agent-observed: `sbt services/compile` success; `servicesClasspathGuard: OK, 58 runtime classpath entries, none match spark-* or hadoop-*`.
- Flow:
  1. A read-only plan step: verify the embedding inputs, convert the JSONL (Spark-free `EmbeddingJsonl`, mirroring `ParquetInput`), render, apply the cap, and run the number check.
  2. `LiveLoad.run`.
  3. `EmbeddingProducer.enqueue`, with `maxDocumentsPerRun` = cap.
  4. Write `<items-source>ops-manifest.json` with putIfAbsent.
- Deviations disclosed by the agent:
  - The plan step runs before `LiveLoad`, so refusals happen before any write.
  - It adds a `number-check-failed` refusal (exit 3).
  - The dry-run manifest goes to stdout only (not S3).
  - The render run ID is `embeddingInputs.sparkRunId`.
  - No ledger is written.
- Cap: render uncapped. If over 10000, render the summaries alone. If those alone exceed the cap, refuse. Otherwise `paperLimit = cap - summaryDocs` (ascending workId), re-render, and refuse with exit 3 if still over.
- Exit codes: 0 ok or dry-run; 1 runtime failure; 2 usage or config; 3 refusal.
- Gap noted by the agent: no Terraform sets `SCHOLARCHAIN_IMAGE_TAG`, so the manifest's `imageTag` will be null.

## Progress checkpoint — storage tests (test-engineer run 1)

- STATUS: FAILED, as reported by the agent, solely because `sbt storage/scalafmtCheck` fails on two production files (`LiveLoad.scala`, `LocalLoad.scala`; for example `LiveLoad.scala:461`). Routed to storage-engineer for a formatting-only fix.
- New test files, all under `modules/storage/src/test/.../storage/`:
  - `LiveLoadFx.scala` (fixtures)
  - `LiveLoadSuite.scala` (86 tests)
  - `LiveLoadAuxSuite.scala` (13 tests)
  - `LocalLoadEmbeddingInputsSuite.scala` (20 tests)
  - `LiveLoadEndToEndSuite.scala` (5 tests)
  - `LocalLoadBaseline.scala`: a frozen copy of HEAD `LocalLoad.scala`, the byte-identity reference.
  - `LocalLoadFx.scala` (fixtures)
- Agent-observed `sbt storage/testFull`: 450 passed, 0 failed, 0 errors. That is 124 new tests plus 326 pre-existing tests, which pass unmodified (the human's backward-compatibility condition).
- Byte-identity (test 14):
  - Old and new LocalLoad were run on the same fixture.
  - `neptune/**` and `dynamodb/**` are identical, and `load-manifest.json` without `embeddingInputs` is identical.
  - `checksums.sha256` differs only on the manifest and object-store lines, plus the 4 added lines.
- No behavioral defect found. One mid-run failure was the agent's own wrong expectation, fixed in the test.
- Disclosed: the Spark-based suites skip (via `assume`) if the generated `sparkS1r2` fixture is absent. 0 were skipped in this run.

## Progress checkpoint — services tests (test-engineer run 2)

- STATUS: FAILED (agent report). Two production defects were exposed; no test was weakened.
- New suites under `modules/services/src/test/.../services/`:
  - `OpsLoadFx.scala` (fixtures)
  - `OpsArgsSuite.scala` (13)
  - `EmbeddingJsonlParitySuite.scala` (29)
  - `OpsLoadCapSuite.scala` (16)
  - `OpsLoadRunSuite.scala` (57)
  - `OpsLoadMainSuite.scala` (23)
  - `OpsLoadEndToEndSuite.scala` (8; real LocalLoad over `sparkS1r2`, fails if the fixture is absent)
- Agent-observed `sbt services/testFull`: `Failed: Total 371, Failed 2, Errors 0, Passed 369`. 146 of those tests are new.
- The cap tests pass: exactly 10000 is enqueued; 10001 gives paperLimit 9900 with total 10000; summaries over the cap give exit 3 with no effect; multi-chunk papers over the cap give exit 3; the selection is deterministic.
- Defect 1 (storage-engineer, `Cells.scala:45`): `Cell.toJson` uses `v.abs < 2^53`, which overflows for `Long.MinValue`, so the value is written as a lossy number. The agent rates it LOW impact. Routed.
- Defect 2 (service-engineer, `OpsLoad.scala:763-764, :789, :824`): a Neptune endpoint without a scheme gives an NPE that escapes `execute` (stack trace, no structured exit 2). Routed.
- Process note: the agent wrote a log to `/tmp` by mistake and then deleted it.
- These fixes complete Part A. They are not the post-CHECK repair round.

## Progress checkpoint — defect fixes (Part A completion)

- storage-engineer, `Cells.scala:45`: the predicate is now `v > -(1L << 53) && v < (1L << 53)`. Agent-observed: `storage/scalafmtCheck` success; `storage/testFull` `Passed: Total 450, Failed 0, Errors 0`.
- service-engineer, `OpsLoad.scala`:
  - The Neptune endpoint must be an https URI with a host (exit 2, value never echoed).
  - `awsPorts` is wrapped in `Try` (exit 2).
  - `build(env)` is inside `Try` (exit 1, class name only).
  - Agent-observed: `services/scalafmtCheck` success after formatting; classpath guard OK; `services/testFull` `Passed: Total 371, Failed 0, Errors 0`.

## Part A complete: §5 hash after Part A (goes into the Phase 14 gate)

- §5 only: `91a37f1f7050c9ca16b652dba5a51c43369c2b5e55dd7f021f2709ee55ed479d`. Unchanged since infra-engineer run 1; run 2 did not touch §5.
- Whole file (informational): `c8fcb2a0aeecfe3de3969868de25f62cb4974f808f8c16b0b6d0584282a27333`.
- **Disclosure (human condition):** the Parquet-to-load-file conversion (LocalLoad) runs on the operator machine through the sbt Test classpath, because the service images exclude Spark by design (`build.sbt:201-214`). It does not run in AWS.

## Part B — CHECK 1 (of 2): FAILED

- Command: the canonical CHECK command from the repository root. Marker `exit=1` (17:38:07).
- Failure at the first step, `[error] (services / Test / scalafmtCheck) scalafmt: 2 files must be formatted`, `elapsed time: 0 s`. No test suite ran.
  - `modules/services/src/test/scala/scholarchain/services/OpsLoadRunSuite.scala isn't formatted properly!`
  - `modules/services/src/test/scala/scholarchain/services/EmbeddingJsonlParitySuite.scala isn't formatted properly!`
- Repair round 1, the only one allowed: test-engineer formats those two test files (formatting only), then CHECK 2.
- Repair (test-engineer, formatting only): reformatted `OpsLoadRunSuite.scala` and `EmbeddingJsonlParitySuite.scala`. Agent-observed: `services/Test/scalafmtCheck` and `storage/Test/scalafmtCheck` success; `OpsLoadRunSuite` 57/57 and `EmbeddingJsonlParitySuite` 29/29 (the Long.MinValue test now passes after the `Cells.scala` fix).

## Human speed amendment (2026-11-24, in session)

1. Part D is a single review pass. A reread happens only if that pass finds a HIGH. MEDIUMs go into the single repair round, together with any CHECK failure.
2. The human is present for H1 and Part C. Don't wait for confirmations beyond the work order's STOP points.
3. In Part C, record the EMR managed-scaling capacity timeline (requested, provisioned, released) for the 10K run. Phase 15c uses that run as experiments A and B, with no separate 50K run.

Orchestrator application: the review runs now, before CHECK 2. Its MEDIUMs join the CHECK 1 formatting repair in the one repair round, and CHECK 2 then covers both. The H1 STOP follows CHECK 2.
- Orchestrator pre-check before CHECK 2 (not a CHECK run): `sbt scalafmtCheckAll`: `[success]`; `sbt scalafmtSbtCheck`: `[success]`. The CHECK alias is `scalafmtCheckAll; scalafmtSbtCheck; Test/compile; Test/testFull; contractTest; markTestRun` (`build.sbt:341-344`).
- Part D review dispatched (single pass).

## Progress checkpoint — Part D review (single pass)

- Verdict: REQUEST CHANGES, with no HIGH, so no reread under the amendment. Stored in `review-report.md`.
- MEDIUMs in the single repair round:
  - M1: narrow the ops role's `s3:PutObject` to `curated/loads/*/ops-manifest.json` (infra-engineer).
  - M2: make `convert.json` and `load.json` committable (build-engineer, `.gitignore`) and archived (infra-engineer, `archive-evidence.sh`).
  - M3: document setting `image_tag` to the release commit before the H1 plan (infra-engineer).
- LOWs L1-L9 are deferred.
- Repair round 1 = CHECK 1 formatting fix (already applied) + M1, M2, M3. None of M1-M3 touches `modules/`, `tests/`, `project/` or `build.sbt`, so CHECK 2 was started in parallel with them.

## Part B — CHECK 2 (of 2): PASSED

- Command: the canonical CHECK command from the repository root, started 18:02, after the formatting repair. Marker: `exit=0` (18:30:11). sbt: `[success] elapsed time: 1694 s (0:28:14.0)`.
- First group of `Passed: Total` lines, in output order: 450, 290, 199, 368, 371, 299, 955, 0, 0, 926, 23, 0, 10, 0, 0. Sum 3,891. Every line shows Failed 0, Errors 0.
- A second group repeats seven of those values (299, 290, 199, 368, 450, 371, 955), as in the baseline. The output does not label the lines by module.
- Against the baseline group sum of 3,621: +270. The two changed values match storage 326 → 450 (+124 new tests) and services 225 → 371 (+146 new tests), as reported by test-engineer.
- Skip check (review L7): `LocalLoadEmbeddingInputsSuite finished: 0 failed, 0 ignored, 20 total`; `OpsLoadEndToEndSuite ... 8 total`; `LiveLoadEndToEndSuite ... 5 total`. The fixture-dependent suites ran.
- A logged stack trace inside `OpsLoadEndToEndSuite` is Hadoop's DEBUG message `java.io.FileNotFoundException: HADOOP_HOME and hadoop.home.dir are unset` ("Failed to detect a valid hadoop home directory"), not a test failure.
- Formatting repair covered. The M1-M3 repairs touch only `infra/terraform/**`, `scripts/**` and `.gitignore`, none of which this CHECK covers.
- M2 (build-engineer half), COMPLETE: `.gitignore:65-66` un-ignore `reports/pipeline/*/*/convert.json` and `load.json`. Agent-observed `git check-ignore -v`: both committable; `config.json`, `load-manifest.json` and `checksums.sha256` stay ignored.

## Progress checkpoint — repair round 1 closed (infra-engineer M1, M2 half, M3)

- M1: `modules/iam/main.tf:277` (`WriteManifestAndTexts`, `s3:PutObject`) is now `curated/loads/*/ops-manifest.json` plus `rag/text/*`. `ReadLoadFiles` (`:256`) keeps Get on `curated/loads/*`.
  - The agent's first edit narrowed the wrong statement (the Get). The orchestrator caught it by reading the file, and the agent reverted it. Orchestrator re-read: `:256` `curated/loads/*`, `:277` `curated/loads/*/ops-manifest.json`.
  - INFRA-CONTRACTS §14.6 corrected and marked "Security change: human approval required at H1".
- M2 (infra half): `archive-evidence.sh` now archives `convert.json`, `load.json`, `load-manifest.json` and `checksums.sha256` (harness T10).
- M3: INFRA-CONTRACTS §14.4 has a new step 6: commit Part A, set `image_tag` to the 12-hex commit `package.sh` will build, run `plan.sh`, and the human applies. Otherwise the load stage exits 6. `terraform.tfvars.example` has a commented `image_tag` entry. `deploy.sh` is unchanged.
- Agent-observed: fmt, validate, `bash -n`, shellcheck (`-S warning`), harness 387/0, trivy HIGH/CRITICAL clean.
- Orchestrator-observed after the repair:
  - `bash scripts/tests/test-scripts.sh`: `RESULT passed=387 failed=0`.
  - `terraform validate`: `Success! The configuration is valid.`
  - §5 hash unchanged at `91a37f1f…479d`.
  - No file under `modules/`, `tests/`, `project/` or `build.sbt` is newer than the CHECK 2 marker, so CHECK 2 still covers all sbt-relevant sources.
- Under the speed amendment there is no review reread (no HIGH). Deferred LOWs: L1-L9 (`review-report.md`).

## Checkpoint: Part A complete; waiting for H1

```
STATUS: COMPLETE (Parts A, B, D-for-Part-A); waiting for H1
OWNER: orchestrator
WORK ORDER: Phase 14 §2 (A), §3 (B), §6 (D, Part A scope)
FILES CREATED/MODIFIED: 23 modified tracked files and 17 untracked paths (git status --short). Main items: infra/terraform/** (MERGE-1, MERGE-2, ops task, role, security group, outputs, INFRA-CONTRACTS §5/§14), scripts/** (package, deploy, run-bounded-pipeline convert/load, archive-evidence, harness), .gitignore, modules/storage/src/main (LiveLoad.scala new, LocalLoad.scala, Cells.scala), modules/services/src/main (OpsLoad.scala new) and API.md, new test suites under modules/storage/src/test and modules/services/src/test; this phase folder.
TEST RESULTS:
  - CHECK 1: exit=1 (scalafmt, 2 test files).
  - CHECK 2: exit=0, 3,891 passed, 0 failed (first group), 1694 s.
  - Script harness: 387/0 (orchestrator-observed).
VALIDATION RESULTS: terraform validate success (orchestrator); fmt, shellcheck, trivy (agent-reported); terraform plan NOT RUN (H1).
CONTRACT: §5 hash after Part A = 91a37f1f7050c9ca16b652dba5a51c43369c2b5e55dd7f021f2709ee55ed479d (for the gate).
REVIEW: REQUEST CHANGES, no HIGH; M1-M3 fixed in the single repair round; LOW L1-L9 deferred.
DISCLOSURE: the Parquet-to-load-file conversion (LocalLoad) runs on the operator machine via the sbt Test classpath, not in AWS (service images exclude Spark by design).
MANUAL ACTION REQUIRED (H1, human, in order; INFRA-CONTRACTS §14.4):
  1. Approve the security changes:
     - query-api Bedrock grants (approved earlier);
     - the new scholarchain-task-ops role, with M1 narrowed;
     - the ops security group and Neptune 8182 rules.
  2. Commit the Part A changes. Keep reports/release/<sha>/artifacts.json uncommitted until after deploy (L9).
  3. Set image_tag = <sha12 of that commit> in the gitignored tfvars (M3), with create_neptune=true and create_alb=true.
  4. Run `sbt shutdown`. Run scripts/plan.sh, review it, and apply the saved plan yourself.
  5. Confirm the SNS email subscription (G12-6).
  6. Set SCHOLARCHAIN_SERVICES_API_KEY and SCHOLARCHAIN_INTERNAL_API_KEY. Write the services key to ~/.scholarchain/services-api-key (mode 600).
  7. Record the applied plan SHA-256 in a committed gate file. CONFLICT (orchestrator-verified): the work order names human-gate-predeploy.md, but scripts/lib/common.sh:370 accepts only `<phase>/human-gate.md` and refuses any other name with "REFUSED: the gate file must resolve to docs/agent-runs/<phase>/human-gate.md". Either write the pre-deploy note in docs/agent-runs/phase-14-deploy/human-gate.md (the final gate can append to it later) or decide otherwise. Agents do not edit scripts for this without a routed change.
  8. The operator profile needs ecs:RunTask, DescribeTasks, StopTask, DescribeTaskDefinition, and iam:PassRole on the ops and embedding-worker execution roles.
  9. Write "H1 done" in the session, and say whether Phase 15c follows immediately (Part C step 6).
RISKS: never verified on real AWS: Bedrock Converse with the profile ID, Neptune loader IAM and rerun idempotency (L3), 1 GiB ops task size, Fargate price, the convert stage against real Spark output.
NEXT GATE: H1 (human); then Part C (deploy-engineer); then the Part C evidence honesty review.
```

No AWS call was made. Nothing was staged or committed.
