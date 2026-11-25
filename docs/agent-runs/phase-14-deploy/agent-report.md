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

---

# Phase 14 Part C checkpoints (held in the scratchpad while the tree must stay clean; to be appended to agent-report.md after the pipeline)

## Human authorization (in session)
- Option 2: the orchestrator may run exactly `aws ce get-cost-and-usage --profile scholarchain --region us-east-1 --time-period Start=2026-11-01,End=2026-11-26 --granularity MONTHLY --metrics UnblendedCost --filter '{"Not":{"Dimensions":{"Key":"RECORD_TYPE","Values":["Credit","Refund"]}}}' --query 'ResultsByTime[0].Total.UnblendedCost' --output text`, before and after each Part C step. This is a documented exception to rule 3; no other direct AWS call is allowed. STOP if a reading exceeds $12.
- H1 (human): P1 applied, plan_sha256 4bd8164d14fd305b62b4e6b4155013c06303250a747116fbf5b9353f83bd33f5. SNS confirmed. Both API keys set. Key file at ~/.scholarchain/services-api-key. C2 = a84a12ea0f13.
- Orchestrator preconditions: HEAD a84a12ea0f13; tree clean; human-gate.md contains the P1 sha; the plan record and the .tfplan both hash to the P1 sha; the summary shows add 177 / change 0 / destroy 0 / nat_gateways_in_plan=0; `sbt shutdown` returned "no sbt server is running".

## Cost readings (MTD UnblendedCost, excluding Credit and Refund)
| When (UTC) | Before/after | Reading |
|---|---|---|
| 2026-11-25T01:31:41Z | before package.sh | 0.00011 USD |
| 2026-11-25T01:34:09Z | after package.sh (FAILED) | 0.00011 USD |

## Part C step 1, package.sh: FAILED, STOP
- deploy-engineer ran `bash scripts/package.sh` at HEAD a84a12ea0f13 (clean tree). Exit 1. The log is `reports/deployments/2026-11-24-package-a84a12ea0f13.log`; stderr was not captured, because the guard blocks `2>&1`.
- Orchestrator-verified cause (script and log):
  - `package.sh:83-84` runs `sbt packageJobs` without SCHOLARCHAIN_GIT_SHA. The thin client starts a background sbt server (log line 5: "starting sbt server in the background").
  - `package.sh:88` then runs `env SCHOLARCHAIN_GIT_SHA=a84a12ea0f13 sbt dockerLocal`, which attaches to that server. The server's environment lacks the variable, so `services / servicesDockerBuild` fails (log lines 29 and 51: "SCHOLARCHAIN_GIT_SHA must be set ... a running sbt server keeps its own environment").
  - build-jobs succeeded. No image was built, no artifacts.json was written, and no AWS call was made by the script.
- Not caught earlier: the offline harness stubs sbt, so the stub cannot model the sbt server's environment.
- The orchestrator ran `sbt shutdown` afterwards. The tree is still clean (`git status --short` = 0 lines).
- Also reported by deploy-engineer: the guard blocks `pgrep`, `ps`, `docker image inspect`, `2>&1` and compound `cd` for deploy-engineer, so the orchestrator must do the process and image checks.

## Human decision: option A (in session)
- Run `sbt shutdown`, then `SCHOLARCHAIN_GIT_SHA=a84a12ea0f13 bash scripts/package.sh`. If dockerLocal refuses again for the same reason: STOP (the human then approves B).
- **Gate finding F14-1 (owner infra-engineer, fix in Phase 17b):** `scripts/package.sh:84` runs `sbt packageJobs` without SCHOLARCHAIN_GIT_SHA. The sbt thin client starts a background server whose environment lacks the variable, so `package.sh:88` `env SCHOLARCHAIN_GIT_SHA=<tag> sbt dockerLocal` fails on that server. Workaround used in Phase 14: set SCHOLARCHAIN_GIT_SHA in the caller's environment.
| 2026-11-25T01:35:27Z | before package.sh rerun (option A) | 0.00011 USD |
| 2026-11-25T01:36:38Z | after package.sh rerun (FAILED) | 0.00011 USD |

## Part C step 1 rerun (option A): FAILED at record-artifacts, STOP
- deploy-engineer ran `SCHOLARCHAIN_GIT_SHA=a84a12ea0f13 bash scripts/package.sh`. Exit 1. Log: `reports/deployments/2026-11-24-package-a84a12ea0f13-rerun.log`; its STEP markers are at lines 1, 3, 17 and 29.
- Option A worked for F14-1. build-images succeeded (no "SCHOLARCHAIN_GIT_SHA must" line). Orchestrator `docker images`: `scholarchain/query-api:a84a12ea0f13` 7e509136833b, `retrieval` cd779e53ee56, `embedding-worker` d350873ad8f9, all created 2026-11-24 19:35:59 CST.
- New failure: `SC-ERROR expected exactly one assembly jar under modules/hadoop/target, found 0`.
- **Gate finding F14-2 (owner infra-engineer), orchestrator-verified:** `scripts/package.sh:100-103` searches `modules/<mod>/target` (maxdepth 3) for `*-assembly.jar`. sbt 2 writes the jars to `target/out/jvm/scala-3.3.9/scholarchain-{hadoop,spark}/scholarchain-<mod>-0.1.0-SNAPSHOT-assembly.jar` under the repository root (13,724,145 and 6,784,120 bytes). The `find` returns 0. Not caught earlier: the harness stubs sbt and fakes jar locations.
- `run-bounded-pipeline.sh:232-235` takes the jar paths from `artifacts.json`, so it is fine once package.sh records the correct paths.
- No artifacts.json was written, so deploy.sh cannot run. Tree clean. No AWS change.

## Option B (human): F14-1 and F14-2 fixed in package.sh; C3
- infra-engineer:
  - export SCHOLARCHAIN_GIT_SHA before any sbt call;
  - `sbt shutdown` first, and a failure is fatal (the review MEDIUM, human-approved);
  - jars are found in target/out/jvm/scala-*/scholarchain-<mod>/, exactly one each;
  - harness cases added; the INFRA-CONTRACTS row at line 538 corrected.
- Orchestrator harness rerun after the first fix: 407/0. Agent-observed after the shutdown fix: 407/0; bash -n and shellcheck exit 0.
- Quick review of the diff: APPROVE WITH NOTES. One MEDIUM (a failed shutdown should be fatal), fixed. LOWs: no harness case for a failed shutdown (deferred), and the row-538 text (fixed).
- Commits (human): cd1b415 (F14 fixes), then C3 = f5cc5f133415 (shutdown fatal + row 538). Orchestrator check: tree clean; HEAD f5cc5f133415; human-gate.md still contains the P1 sha; `sbt shutdown` exit 0; no sbt process running.
| 2026-11-25T02:23:04Z | before package.sh (C3) | 0.00011 USD |
| 2026-11-25T02:24:17Z | after package.sh (C3, OK) | 0.00011 USD |

## Part C step 1: package.sh at C3, SUCCEEDED
- deploy-engineer: `bash scripts/package.sh > reports/deployments/2026-11-24-package-f5cc5f133415.log`, exit 0. STEP markers: preflight, build-jobs, build-images, record-artifacts. No SC-ERROR.
- artifacts.json `reports/release/f5cc5f133415904e20dcb3ad86515d49b87f4493/artifacts.json`: release:true, dirty:false, commit = HEAD, image_tag f5cc5f133415, packaged_at 2026-11-25T02:23:43Z.
  - query-api sha256:78f45013339c…5cb1
  - retrieval sha256:1c23fb14fc24…205a
  - embedding-worker sha256:bc1afcae408d…eec
  - hadoop jar a7fe336d…46a0 (13,724,145 bytes); spark jar 6f4b12d7…59bf4 (6,784,120 bytes)
  - The jars were "up to date" from the 19:32 build at the same sources.
- Orchestrator verification: for all three images, the local `docker image inspect` Id equals artifacts.json, and the revision label is f5cc5f133415. Both jar SHA-256 values recomputed and equal. With the generated-report exclusions, the tree is clean (0 lines).
| 2026-11-25T02:25:39Z | after deploy.sh (BLOCKED, not run) | 0.00011 USD |

## Part C step 2: deploy.sh BLOCKED by guard-dangerous.sh (rule 7 STOP)
- deploy-engineer's exact command was rejected before execution with the guard's private-key-file rule ("found inside scripts/deploy.sh"). Nothing ran; no AWS change; deployment.json not written; the log is 0 bytes.
- Orchestrator finding (Read tool): the trigger is `scripts/deploy.sh:132`, the jq filter that selects the two secret entries by comparing the entry's key field with "services-api-key" and "internal-api-key". It was added by Part A MERGE-2. The guard's script-text scan reads the jq key-field accessor as a key-file reference (docs/agents.md §5 accepts such false positives). The offline harness never goes through the guard.
- **Gate finding F14-3 (owner infra-engineer):** deploy.sh's jq key-field text trips the guard.
- The orchestrator's attempt to scan the other Part C scripts for the same text patterns was denied by the session's permission classifier ("Undermining Oversight"). Not pursued. Whether smoke-test.sh, run-bounded-pipeline.sh or archive-evidence.sh also trip the guard is NOT VERIFIED. Writing this note through Bash was also blocked by the guard, because the note quoted the jq text; it was written with the Edit tool instead.

## Human decision: option 2 for all of Part C (in session, 2026-11-25)
- Every Part C script is EXECUTED BY THE HUMAN in their own terminal because of the guard false positive F14-3 (gate finding, owner infra-engineer, fix in Phase 17b). The orchestrator/deploy-engineer prepare the exact commands, verify every record and output afterwards, and take the cost readings. C3 = f5cc5f133415 stays the release; images :f5cc5f133415 already built.
- Orchestrator notes on the prepared commands:
  - The work order's `--scale 10k --smoke` is refused by the script (`run-bounded-pipeline.sh:93`, "--smoke runs alone", exit 2). The smoke is `--smoke` alone.
  - The raw enrichment caches have about 50K files per source, so `run-bounded-pipeline.sh:269` refuses them (Rule F, at most 200 files). No packed 10K enrichment archive exists, so the 10K run is DBLP-only unless the human decides otherwise.
  - `--plan-record` is pinned to the P1 record so that later deploy.sh runs (the worker drain) keep using the gate-approved P1 sha after P2's record becomes the newest.
  - `compute/main.tf:194` ignores only desired_count, so the P2 apply replaces the services' task definitions with the Terraform-registered revision (same image tag).
| 2026-11-25T05:01:06Z | before deploy.sh (human-run) | 0.00011 USD |
| 2026-11-25T08:10:27Z | after deploy.sh (3 attempts) and the --smoke run | 0.00011 USD (Cost Explorer lags; EMR, ECS and Neptune have run since P1) |

## Part C step 2: deploy.sh at C3, human-run, three attempts (none hidden)
- The 0-byte `reports/deployments/2026-11-24-deploy-f5cc5f133415.log` is the deploy-engineer attempt the guard blocked (F14-3).
- Attempt 1, `reports/deployments/2026-11-25-deploy-f5cc5f133415.log`: preflight passed (clean tree, plan checksum and gate, both secrets stored, local images match artifacts.json). The ECR push of query-api failed (log line 69: "write tcp 192.168.65.3:60814->192.168.65.1:3128: use of closed network connection", the local Docker proxy). The human stopped it with Ctrl+C; exit=1 (human-reported).
- Attempt 2, `-retry1.log`: the same preflight; query-api and retrieval pushed; the embedding-worker push failed the same way (line 149). Human-reported exit 1.
- Attempt 3, `-retry2.log`: reused the two immutable tags already in ECR (lines 12-13), pushed embedding-worker, wrote the in-progress record (line 65), all three services stable (67-69), task definitions and digests verified (71-73), "deploy complete (dry_run=0)" (76).
- No attempt reached the record stage before attempt 3, so attempts 1 and 2 changed only ECR (pushed layers and tags), not ECS (by the script's order; NOT independently verified against ECS).
- **Deviation, disclosed:** work-order rule "Do not retry a failed AWS step more than once" — the push step was retried twice (three attempts), by the human. The cause was a local network proxy, not AWS or the script.
- Orchestrator-verified `reports/deployments/f5cc5f133415904e20dcb3ad86515d49b87f4493/deployment.json`:
  - status succeeded, failure_reason null, commit f5cc5f133415…, approved_plan_sha256 4bd8164d…33f5 (P1), account_check match, us-east-2, cluster scholarchain-cluster;
  - desired query-api 1, retrieval 1, embedding-worker 0;
  - task definitions query-api:2, retrieval:2, embedding-worker:2 (previous :1);
  - digests equal artifacts.json (78f45013…5cb1, 1c23fb14…205a, bc1afcae…eec); running query-api and retrieval tasks report the same digests; the worker has no running task (desired 0).

## Part C step 3a: run-bounded-pipeline.sh --smoke at C3, human-run, SUCCEEDED
- Human-reported exit=0. Orchestrator-verified `reports/pipeline/smoke/r20261125T073350Z-74706/run.json`:
  - status succeeded, run_id r20261125T073350Z-74706, cluster j-04639153VIV3O5MM3GL9, commit f5cc5f133415…, release emr-spark-8.1.0, m5.xlarge on-demand, 1 primary + 1 core, managed scaling max core/task 2, idle timeout 900 s, step timeout 3600 s, TERMINATE_CLUSTER;
  - one step smoke-mapreduce-pi (s-0433498MGT8V9GLP700) COMPLETED, 07:38:13Z to 07:38:42Z; run 07:33:56Z to 07:44:33Z;
  - jars uploaded to s3://…-artifacts/jars/f5cc5f133415/ with SHA-256 equal to artifacts.json (a7fe336d…46a0, 6f4b12d7…59bf4).
- Cluster termination after idle is NOT VERIFIED (no read call made; auto-termination is configured).

## Human decision: C4 (enrichment in the 10K run)
- The 10K AWS run must include Crossref, OpenAlex and ORCID. HJ2 reads one envelope per file (WholeFileInputFormat) and the upload refuses more than 200 files per source (Rule F), so the 10K set (10,000 / 10,000 / 1,384 files) cannot be uploaded as is.
- Approved C4: hadoop-engineer (packed one-record-per-line input per source, single-file behaviour unchanged), dataset-engineer (pack script, output to the gitignored datasets/raw/dblp/derived/enrichment-10k/ with checksums), test-engineer (tests including a byte-identical golden test of single-file HJ2 output). One CHECK, diff-only review, then the human commits C4. P2 is NOT applied; it will use image_tag = C4.

Note: every Part C script from step 2 on was executed by the HUMAN in their own terminal (human decision, option 2), because the guard false positive F14-3 blocks deploy-engineer. The orchestrator prepared the commands and verified the records.

## C4 checkpoint (packed enrichment input), uncommitted, waiting for the human C4 commit

- **hadoop-engineer** (agent-reported, orchestrator read the diff stat):
  - `InputFormats.scala`: adds EnvelopeInputFormat (non-splittable), EnvelopeRecordReader and EnvelopeFiles. JsonFileFilter also accepts `*.envelopes.jsonl`.
  - `Hj2SourceNormalize.scala`: submit uses EnvelopeInputFormat; the mapper takes the record name from the key.
  - `JOB-CONTRACTS.md`: HJ2 section only.
  - Packed record identity: key `<path>#<1-based physical line>`, name `<file>#<line>`, checksum SHA-256 of the line bytes. Bad lines are rejected one by one. PipelineDriver is unchanged.
  - `sbt hadoop/Test/compile` exit 0 (agent-reported).
- **dataset-engineer**:
  - New `datasets/scripts/pack_enrichment.py` 1.0.0 and the manifest entry `scale_datasets.dblp-10k.packed_enrichment`. Output goes to the gitignored `datasets/raw/dblp/derived/enrichment-10k/`.
  - First run exit 1 (ORCID membership error). The agent removed the partial output of its own failed run and added staging (`.partial` then rename). Second run exit 0. Re-run refused (exit 2). `--verify` byte-identical. `validate_manifest.py` 355 passed / 0 failed. (All agent-reported.)
  - Orchestrator-verified:
    - crossref 2 files / 10,000 lines; openalex 3 / 10,000; orcid 4 / 1,384;
    - top level holds only `checksums.sha256` and `provenance.json`;
    - `shasum -a 256 -c checksums.sha256`: 0 non-OK lines; `checksums.sha256` sha256 8d74a80ccd6c77f1bffa2f5e261c943d201afeb1a6a8e53f372e7f6a6038e9fe;
    - no `.partial` directory left.
  - The reviewer confirmed that the three cache digests in provenance.json equal `scale_enrichment_stats.json`.
- **test-engineer**: `Hj2RunSupport.scala`, `Hj2PackedInputSuite.scala` (11 tests) and `src/test/resources/hj2-golden/` (9 files).
  - Golden provenance (agent-reported):
    1. `sbt shutdown`.
    2. `git worktree add --detach <session scratchpad>/wt-head f5cc5f133415`, verified clean at HEAD and without EnvelopeInputFormat.
    3. Hj2RunSupport plus a scratch-only dump test were copied in. `sbt "hadoop/testOnly scholarchain.hadoop.Hj2GoldenDumpSuite"` was run twice; `diff -r` showed the two dumps identical.
    4. The dump was copied to hj2-golden/ and the worktree removed with `git worktree remove --force`.
  - Orchestrator-verified: `git worktree list` shows only main and lane2. The on-disk SHA-256 of all 9 golden files equals the agent-reported values:
    - counters.txt 8877b511…9720
    - input-checksums.txt 33bb23d4…b0dc
    - author-mentions 36eda1ea…aebe
    - duplicates 7a603591…f180
    - id-refs 76d85b0b…a378add
    - institutions d5075ced…789313
    - rejects e82f2c1a…f3ae
    - topics 136c7603…7b682
    - works bbd96bc3…a0ee1
- **CHECK** (canonical command), four runs in all, all disclosed:
  - test-engineer runs 1 to 3: exit=1, all at scalafmtCheck. Run 1: two test files and two production files unformatted. Runs 2 and 3: only the production files Hj2SourceNormalize.scala and InputFormats.scala. test-engineer's targeted `hadoop/testOnly Hj2PackedInputSuite`: 11/11 passed (agent-reported).
  - Repair round: hadoop-engineer ran `sbt hadoop/Compile/scalafmt` (formatting only, 2 files), then the canonical CHECK once: 1970 s, `[success]`. Agent-reported per-module "Passed" lines: 10, 299, 368, 955, 210, 371, 290, 450, plus three modules with 0; failed 0. Hj2PackedInputSuite 11/11 in both configurations.
  - Orchestrator-observed: marker `exit=0`, mtime 2026-11-25 03:05:42 CST, with no source file newer.
  - The agent-reported per-module totals (sum 2,953) are NOT reconciled with CHECK 2's 3,891 tests; the log was not kept.
- **Review** (code-reviewer, diff only, single pass): APPROVE WITH NOTES, no HIGH. Details in review-report.md (C4 section).

## F14-4: C4 deploy failed, automatic ECS rollback (incident)

All Part C commands here were run by the human in their own terminal (F14-3). The evidence was gathered read-only by the human and checked by the orchestrator where noted.
- **What happened:** the human ran `deploy.sh` for release `c334a9adc55d` with the P1 plan record pinned, and it exited 5.
  - The record `reports/deployments/c334a9adc55da950fcdbd2aa6cfa53d6fa3563c7/deployment.json` has `"status":"failed"` (orchestrator-observed).
  - Deploy log `reports/deployments/2026-11-25-deploy-c334a9adc55d.log:50` reads: "SC-WARN deployment FAILED after the services were changed" (orchestrator-observed).
- **Symptom:** the C4 query-api tasks crashed with "Could not find or load main class scholarchain.services.QueryApiMain". Source: CloudWatch `/scholarchain/query-api`, 15:58 to 16:08 UTC (human-reported).
- **Automatic recovery** (human-reported):
  - The ECS deployment circuit breaker rolled query-api back to task definition `:2` (C3), and it is healthy.
  - retrieval stayed on `:2` (C3) and was never updated.
  - embedding-worker is on `:3` (C4) with desired count 0, so it runs no tasks.
  - `rollback.sh` was not run, because the services were already back on C3. P2 was not planned, and `image_tag` was not added to tfvars.
- **Root cause** (human-reported):
  - In the C4 images, every scholarchain jar in `/opt/scholarchain/lib` has mode 0600 root:root. In the C3 images they were 0644.
  - The container runs as uid 10001, so it cannot read the jars. The jar contents are identical to C3 (same size, and QueryApiMain is present).
  - The staged Docker context `docker-services/lib/` also holds 0600 files dated 03:57.
  - `packaging/Dockerfile.template` applies `chmod -R a-w,a+rX` to `prompts/` but not to `lib/`, so the image inherits the host file modes.
- **Impact:** no C4 service ever served traffic. query-api and retrieval kept serving C3.
- **Fix:** C5, a new commit, because ECR tags are IMMUTABLE (`modules/platform/main.tf:7`) and `c334a9adc55d` cannot be re-pushed.
  - build-engineer changes the Dockerfile template and the staging mode check.
  - Then one CHECK and a diff-only review. The human commits.
- **Follow-up:** incident-responder writes the incident record under `docs/incidents/` later. This is the phase's real incident.
- **C5 agent work** (build-engineer, agent-reported unless marked):
  - Changed files: `packaging/Dockerfile.template`, where the RUN now applies `chmod -R a-w,a+rX` to both `lib` and `prompts`.
  - Changed files: `build.sbt` `servicesDockerStage`, which normalises the staged lib to 0644 files and 0755 dirs and fails the stage if any staged lib file is not world-readable.
  - Cause found:
    - The source jars in `universal/stage/lib` are 0644 with their original mtimes. The `docker-services/lib` copies were 0600 with a fresh mtime of 03:57.
    - So `IO.copyDirectory` did not preserve modes. The agent inferred a copy through a temp file, but did not confirm it in the sbt-io source.
  - After the fix, `sbt dockerStage` produced 58 staged lib files, all `-rw-r--r--`. No docker build was run.
- **C5 CHECK:**
  - Run 1 failed in 1 s at scalafmtSbtCheck (build.sbt unformatted). The agent then ran `sbt scalafmtSbt`.
  - Run 2 was canonical: `[success]` in 1788 s (agent-reported).
  - Orchestrator-observed: marker `exit=0`, mtime 2026-11-25 11:02:30. No file under modules/, tests/, project/ or build.sbt is newer.

## C5 release, P2, and F14-5: the 10K run failed at the first EMR step

All commands in this section were run by the human in their own terminal (F14-3).
- **C5 `dc20d0fa23df`** was packaged and deployed with exit 0 (human-reported).
- **P2:**
  - plan_sha256 `9312a25e4704d7060626914e569ae0041b722ccb4a3cd4a860ce2b49c9280447`, with image_tag set to C5. Summary: add 0, change 3, destroy 0, replace 4, iam 0, no stateful destroy or replace.
  - The only change beyond the expected table was the removal of hostPort 8080 drift (human-reported).
  - The human applied P2.
- **10K run** `r20261125T183800Z-79065`, cluster `j-000270910KZDB65AYWRX`. The cluster has auto-terminated (human-reported).
  - Records: `reports/pipeline/10k/r20261125T183800Z-79065/{run.json,steps.json}`. They are kept as evidence of the failure.
  - Orchestrator-observed in run.json: `status` failed, `commit` dc20d0fa23df…, started 18:39:07Z, updated 18:45:27Z.
  - Step `hadoop-pipeline-HJ1-HJ7` failed, ret 255, with stderr (human-reported): "JAR does not exist or is not a normal file: /mnt/var/lib/hadoop/steps/s-09742161FYVRNKKDIME8/s3:/…/jars/dc20d0fa23df/scholarchain-hadoop-0.1.0-SNAPSHOT-assembly.jar".
  - `spark-graph-analytics` was CANCELLED. No data was processed, and nothing was written to Neptune, DynamoDB or SQS.
  - Orchestrator-observed: the steps.json step 1 Args are `timeout --kill-after=60 3600 hadoop jar s3://…/scholarchain-hadoop-0.1.0-SNAPSHOT-assembly.jar scholarchain.hadoop.PipelineDriver …` run through `command-runner.jar`.
- **Root cause:** `hadoop jar` needs a local file path, and the script gave it an S3 URI (`scripts/run-bounded-pipeline.sh:294`). The smoke run used the local built-in pi jar (`:291`), so this path was never exercised on EMR. The script harness stubs EMR, so it could not catch the problem either.
- **LOW, observed:** run.json records `failed_step` as `spark-graph-analytics`, but the step that failed was the Hadoop step. Spark was cancelled. This is deferred (not part of the C6 scope).
- **Cost** (orchestrator, Cost Explorer, before credits): $0.4520255489 month-to-date at 19:04:33Z, under the $12 stop. Billing lags, so this may not include the EMR minutes yet.
- **Fix:** C6, an infra-engineer change limited to `scripts/run-bounded-pipeline.sh` and its harness `scripts/tests/test-scripts.sh`. It is gated by `bash -n`, the harness and the dry-run step definitions.
  - `sbt check` is NOT run for C6. The `check` alias (`build.sbt:370-373`) covers formatting, compile and tests of the sbt modules, and runs no `scripts/` tests. C6 changes no sbt input.
  - The final clean `sbt check` in Phase 17b covers it. CLAUDE.md's definition of done has no explicit exemption for script-only changes, and this choice to skip is recorded here for the human gate.

## Human decision: Phase 14 finish delegated to the orchestrator (recorded in substance)

The human handed the rest of Phase 14 to the orchestrator to run autonomously. The authority granted for this session:
- **Commits:** the orchestrator may commit. This is a human-owned decision, delegated, and every delegated commit is disclosed.
  - Before every commit, `git diff --cached | grep -c <account id>` must return 0.
  - `reports/` is never staged.
- **The orchestrator may run:** `package.sh`, `plan.sh`, `run-bounded-pipeline.sh --dry-run`, smoke checks, and read-only AWS calls.
- **The orchestrator may not run:** `deploy.sh` or the Terraform apply (guard and spec). These go into ONE runner script, `/tmp/finish-phase14.sh`, which the human executes.
- **Apply approval is a pre-stated rule.** The runner applies the new plan only if its summary shows all of the following. Any mismatch prints the mismatch and exits without applying.
  - resources_to_add=0, resources_to_change=3, resources_to_destroy=0, resources_to_replace=4, iam_changes=0;
  - both stateful lists empty;
  - every image at the C6 tag.

  The human stated this rule as their approval for the apply.
- **Failure handling:** on a runner failure, diagnose read-only, fix once (same gates, commit, new runner from the failed step), and stop after a second failure.
- **The human retains:** signing `human-gate.md`, which the orchestrator only drafts, never writes. Lane 2 is not merged.

## C6 checkpoint (F14-5 fix and 10K-path audit)
- **Agent work** (infra-engineer; files `scripts/run-bounded-pipeline.sh`, `scripts/tests/test-scripts.sh`):
  - The Hadoop step copies its jar from S3 to local disk and runs it, inside the same `timeout --kill-after=60 3600` with TERMINATE_CLUSTER.
- **Audit blocker b4, orchestrator-confirmed:**
  - The Spark job uses java.nio local paths only (`modules/spark/.../Inputs.scala:122-158`, `Run.scala:70-100`).
  - It needs a single `match-decisions-final.jsonl`, which no EMR stage produced (HJ7 writes partitioned decisions; locally the file came from `scholarchain.er.tools.FinalDecisions`).
- **Orchestrator decision, under the human's delegated authority:** a script-only workaround. The proper Hadoop-FS Spark I/O fix is deferred to 17b (spark-engineer, with entity-resolution-engineer).
  - One Spark step on the EMR primary copies to local disk: HJ curated, `work/hj6a/entity-views`, `work/hj6d/match-candidates`, and the frozen policy (`modules/entity-resolution/evaluation/er-v1-frozen-policy.json`, uploaded with its SHA-256).
  - It runs FinalDecisions from the hadoop assembly with no LLM cache, which gives deterministic policy-only decisions.
  - It runs `spark-submit --master local[*]` with `fs.defaultFS=file:///` and the event log off.
  - It copies the output and the decisions provenance back to SPARK_OUT.
  - **DISCLOSURE:** Spark runs in local mode on the EMR primary node, not distributed. It is not a valid basis for a Spark elasticity claim.
- **Audit results** (infra-engineer, agent-reported):
  - PASS: jar names vs artifacts.json; the Spark main class and flags; the HJ2 enrichment upload, flags and file filter; PipelineDriver args.
  - PASS: convert stage (LocalLoad invocation, sync, exit-6 guard).
  - PASS: load stage (OpsLoadMain and entrypoint, env names, ops role S3, DynamoDB, SQS and Neptune loader, ops SG to Neptune, image-tag check).
  - PASS: EMR EC2 role on artifacts, raw and curated.
  - Residual notes (not fixed): a `*.json` inside an enrichment source dir would be parsed as an envelope; a missing source dir is still passed as a flag.
- **Gate:**
  - `bash -n` OK and shellcheck info-only (SC1091), agent-reported.
  - Harness `RESULT passed=414 failed=0`, observed by the orchestrator twice, after the last change.
  - Dry-run step Args shown by the agent.
  - `sbt check` skipped (reason recorded in the F14-5 section).
- **Review:** see review-report.md (C6). Two HIGHs (H1, N1) were found and fixed in rereads. No HIGH is open.

## C6 runner execution and F14-6 (orchestrator-observed unless marked)
The runner `/tmp/finish-phase14.sh` was prepared by the orchestrator and executed by the human.

**Runner attempts:**
- **Attempt 1:** the orchestrator wrote zsh code and told the human to run it with `bash`, so `setopt` and `print` were not found and pipefail was off. This is an orchestrator error, disclosed here.
  - `deploy.sh` failed at push-images, on the Docker Desktop proxy "broken pipe" via 192.168.65.1:3128.
  - No C6 deployment record was written and no service changed. Read-only check: all three services on `:5`, C5 image; no `a1af7b048639` tag in ECR.
- **Attempt 2:** the runner was rewritten in bash (`set -Eeuo pipefail`, an ERR trap, deploy retry on push-images, resume).
  - query-api and retrieval were pushed. The embedding-worker push hung after a broken pipe; the human pressed Ctrl+C.
  - Read-only check: no deployment record, services unchanged, no leftover `docker push`.
- **Attempt 3:**
  - The human set Docker Engine `max-concurrent-uploads: 1`.
  - The runner gained a deploy watchdog: its own process group, killed after 20 min, or 60 s after "failed to copy".
  - Results:
    - **Deploy:** `reports/deployments/a1af7b0486392569f7919183a454581cb24331c9/deployment.json` status `succeeded`, approved plan P1 `4bd8164d…`.
    - **P3 plan:** `tfplan-course-a1af7b048639-20261125T201750Z`, sha256 `a2bf56da5687bf272675835ff31eac83e9d7b1f5dd64585980da08da33fa09c9`. add 0 / change 3 / destroy 0 / replace 4, iam 0, both stateful lists empty, 4/4 task-definition images at `:a1af7b048639`.
    - **Apply:** applied under the human's pre-stated rule (record `reports/deployments/2026-11-25-P3-approved-by-rule-a1af7b048639.txt`). The apply log shows "Apply complete! Resources: 4 added, 3 changed, 4 destroyed."
    - **Services after the apply:** query-api, retrieval and embedding-worker all on task definition `:7`, image `:a1af7b048639` (describe-services and describe-task-definition, read-only).

**10K run** `r20261125T202037Z-43602`, cluster `j-04871873UKQURPR2CNIQ`:
- run.json `status` failed. Started 20:21:43Z, updated 20:28:04Z.
- The cluster is `TERMINATED_WITH_ERRORS` ("Shut down as step failed").
- Step `hadoop-pipeline-HJ1-HJ7` (`s-028544213QCSBPGH1I5K`) FAILED after 10 s, 20:26:32Z to 20:26:42Z. `spark-graph-analytics` was CANCELLED.
- The C6 jar copy worked: the jar ran.

**F14-6 root cause:**
- stderr (`s3://<logs>/emr/j-04871873UKQURPR2CNIQ/steps/s-028544213QCSBPGH1I5K/stderr.gz`): "odd number of arguments. usage: --dblp <xml> --dtd …", ret 2.
- The hadoop assembly MANIFEST has `Main-Class: scholarchain.hadoop.PipelineDriver`. So `hadoop jar <jar> scholarchain.hadoop.PipelineDriver --dblp …` passes the class name as args[0].
- Never exercised before: the local and harness runs never went through `hadoop jar`.
- Again run.json labels `failed_step` as spark-graph-analytics. This is the LOW recorded under F14-5.

**Cost:** $0.4520255489 month-to-date before credits at 20:29:02Z. Billing lags.

**Fix (fix-once rule):** C7, an infra-engineer change. It drops the class argument from the `hadoop jar` invocation and updates the harness. It is followed by a new resumable runner from the deploy step, because the load stage requires the ops image tag to equal HEAD.
