# Phase 14: review report (code-reviewer, Part D, single pass over Part A)

Stored by the orchestrator. Read-only review of the uncommitted Part A changes on main (HEAD a375c00). Part C had not run, so no Part C evidence was reviewed in this pass.

## Verdict: REQUEST CHANGES

No HIGH. Three MEDIUMs go into the single repair round, so under the speed amendment no reread is needed. The reviewer says the remaining findings support APPROVE WITH NOTES once the MEDIUMs are fixed.

## MEDIUM

- **M1. The ops role's S3 write is broader than the code needs, and the docs say the opposite.**
  - `infra/terraform/modules/iam/main.tf:272-279` (`WriteManifestAndTexts`) grants `s3:PutObject` on `curated/loads/*`, which allows overwriting the uploaded load files.
  - The code writes only `<items-source>ops-manifest.json` with putIfAbsent (`OpsLoad.scala:450-459`). `OpsLoad.scala:281-284` and `modules/services/API.md:289-290` claim the narrow scope.
  - `INFRA-CONTRACTS.md:658` gives a wrong justification.
  - Fix (infra-engineer): narrow the resource to `curated/loads/*/ops-manifest.json` and correct the text. This is a security change, so it needs human approval.
- **M2. The traceability records are neither committable nor archived.**
  - `convert.json`, `load.json` and the copied `load-manifest.json` and `checksums.sha256` are written under `reports/pipeline/<scale>/<run>/` (`run-bounded-pipeline.sh:401-411, 474-478`).
  - `.gitignore:60-64` ignores everything there except `run.json` and `steps.json`, and `scripts/archive-evidence.sh:11,70` archives only those two.
  - Fix:
    - build-engineer: un-ignore `convert.json` and `load.json`.
    - infra-engineer: archive them in `archive-evidence.sh`.
- **M3. The load stage will refuse in Part C as H1 is written.**
  - The ops task image comes from Terraform `var.image_tag` (`modules/compute/main.tf:132`; default `bootstrap`, `variables.tf:288-292`).
  - `deploy.sh` does not re-register the ops task (`:208-226`), and the load stage exits 6 unless the tag equals the release commit (`run-bounded-pipeline.sh:438-441`).
  - Neither the apply checklist (`INFRA-CONTRACTS.md:594-605`) nor `terraform.tfvars.example` says to set `image_tag`.
  - Fix (infra-engineer): document an explicit step: commit, then set `image_tag=<sha12>`, then plan and apply.

## LOW (deferred per the repair policy)

- **L1. Stale text:**
  - `INFRA-CONTRACTS.md:568`: OpsLoadMain and LiveLoad "do not exist".
  - `INFRA-CONTRACTS.md:667` item (2): the loadId is in ops-manifest. On a LiveLoad failure no manifest is written.
  - `INFRA-CONTRACTS.md:510`: still calls the Voyage key the "ECS secret injection name".
  - `API.md:265`: garbled phrase.
- **L2.** No Terraform sets `SCHOLARCHAIN_IMAGE_TAG`, so the manifest's `imageTag` is null (`OpsLoad.scala:719, 844`).
- **L3.** An OpsLoad rerun starts a new Neptune bulk load before it checks for an existing `ops-manifest.json` (`OpsLoad.scala:355-365`). Only the script's `load.json` check prevents it. Neptune re-load idempotency is NOT VERIFIED.
- **L4.** `SC_LOAD_TIMEOUT` (3600 s) equals the maximum Neptune timeout, so `stop-task` could interrupt the DynamoDB puts (`run-bounded-pipeline.sh:116, 119`).
- **L5.** A partial convert upload leaves the prefixes non-empty, and the same run ID is then refused (`:334, :352-353`). Recovery needs a human deletion or a new Spark run. Document it.
- **L6.** The query-api foundation-model grants allow direct Haiku invocation in 3 Regions as well as through the profile (`iam/main.tf:129-137`). A `bedrock:InferenceProfileArn` condition could tighten this (condition key not verified).
- **L7.** The byte-identity suites skip through `assume` if `sparkS1r2` is absent (`LocalLoadEmbeddingInputsSuite.scala:24-27`). The CHECK reader should confirm no skips.
- **L8.** The script header says records are "never overwritten", but the post stages append to `steps.json` (`:320-325`).
- **L9.** Committing `reports/release/<sha>/artifacts.json` moves HEAD and breaks `deploy.sh:136-139`. Keep it uncommitted until after the deploy. The clean-tree check excludes it (`scripts/lib/common.sh:312`).

## Passed checks (reviewer evidence, summarized)

1. **Bedrock IAM and model variables:**
   - query-api gets the profile ARN plus Haiku 4.5 foundation models in us-east-1, us-east-2 and us-west-2 (`environments/course/main.tf:57-61`, `variables.tf:147-151`). Titan stays on retrieval and the worker only (`iam/main.tf:166-173, 219-226`).
   - The env names match core naming (`Config.scala:21-22`, `RuntimeConfig.scala:20-21`). Only `awsQueryApi` builds a model client (`Composition.scala:342-363`).
   - Removing `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID` is safe.
2. **Unused secrets:** not injected (`main.tf:37-43`, `compute/main.tf:89`). The execution-role grant is dynamic (`iam/main.tf:61-69`). `deploy.sh:129-133` checks only the two used keys.
3. **Three-image packaging:** `package.sh:88, 107-115` match `build.sbt:269-298`. `deploy.sh:141-173` pushes all three. No re-tag path is left.
4. **Loader path:**
   - The neptune-source prefix is satisfied by the ops env (`main.tf:105`, `compute/main.tf:107`).
   - LiveLoad: checksum chain and prefix exactness before any write; one start with no retry; conditional puts (`LiveLoad.scala:289-421, 604-608, 645-672`).
   - LocalLoad extension: gated and listed in the manifest and checksums (`LocalLoad.scala:215, 441, 544-557`).
   - OpsLoad: hard cap 10000; plan before write; putIfAbsent manifest; exit codes; no Spark; no values in errors (`OpsLoad.scala:31, 91, 355-365, 455, 520-545, 803-895`).
   - Ops task definition, security group and outputs correct (`compute/main.tf:106-144`, `network/main.tf:302-340`, `outputs.tf:171-192`).
   - Script stages correct (`run-bounded-pipeline.sh:344-353, 382-389, 418-428, 453, 463-467, 481-486`).
5. **.gitignore:** correct apart from M2 and L9.
6. **Ownership:** every changed path is within its owner's row. No changes to `CLAUDE.md`, `docs/agents.md`, `.claude/**`, `build.sbt`, `project/**` or `packaging/**`. Module boundaries kept (`OpsLoad.scala:10-11`, `LiveLoad.scala:7`). The reviewer had no shell or git diff, and the untracked list was truncated and filled in with Glob.
7. **Tests exercise real behavior** (spot-checked): `OpsLoadCapSuite.scala:26-262`, `OpsLoadRunSuite.scala:252-288, 437-478`, `LocalLoadEmbeddingInputsSuite.scala:53-152`, `test-scripts.sh:616-773`.
8. **Honesty:** results are labelled agent-reported or orchestrator-observed. No terraform plan or AWS run is claimed, and CHECK 1's failure is recorded.

## Reviewer risks and manual items

- Not verified anywhere:
  - Bedrock Converse with the profile ID
  - Neptune IAM and loader behavior, including rerun idempotency
  - the 1 GiB ops task size
  - Fargate pricing
- Human approval needed for the ops-role IAM (with the narrowed M1) and the ops security-group rules (security-change rule).
- Human decision needed on the H1 ordering for `image_tag` (M3).
- Keep `artifacts.json` uncommitted until after the deploy (L9).

# C4 review (code-reviewer, diff only, single pass; stored by the orchestrator)

## Verdict: APPROVE WITH NOTES. No HIGH, so no reread.

## MEDIUM
- **M1. The golden's pre-change origin cannot be verified from the repository.** The test points to "the report of the run that created them".
  - Mitigation in place: the single-file path delegates to the unchanged WholeFileRecordReader (`InputFormats.scala:287-347`), the mapper name is unchanged (`Hj2SourceNormalize.scala:41`), and the golden contents fit the fixtures.
  - Orchestrator action: the generation commands and all 9 golden SHA-256 values are now recorded in agent-report.md (C4 checkpoint), and the on-disk hashes were verified.
- **M2. `datasets/scripts/pack_enrichment.py` has no automated test.** Owner test-engineer (`tests/datasets/test_pack_enrichment.py`). Open, for a human decision.

## LOW (deferred)
- L1. The DOI de-duplication loop is re-implemented (`pack_enrichment.py:38-53`, compare `scale_enrichment_stats.py:77-84`). It agrees today (digests equal).
- L2. Packed lines are re-serialized, so the line checksum is not the original envelope checksum. There is no per-line map back to the source file. `JOB-CONTRACTS.md:180` wording.
- L3. A lone surrogate would crash the script and leave `.partial` behind (`pack_enrichment.py:74`).
- L4. The packed reader has no line-length cap (`InputFormats.scala:311-327`).
- L5. CRLF is not normalized; a blank `\r` line is rejected (as documented).
- L6. Packed rejects record offset 0 (`Hj2SourceNormalize.scala:50`).
- L7. The `JOB-CONTRACTS.md` example numbers files from 00000 but the script from 00001. provenance.json has `code_version` `f5cc5f1+uncommitted-scripts`.
- L8. The exact .gz SHA-256 assertion may be non-portable across deflate implementations (`Hj2PackedInputSuite.scala:106`).
- Ownership gap: `reports/pipeline/` and `reports/release/` match no row in docs/agents.md section 1 (factory-engineer).

## Passed
- Single-file behaviour is unchanged.
- Reader correctness: line numbers, last line, empty file, UTF-8 safety, about 156 KB lines, non-splittable, 9 mappers.
- Reduce determinism, rejects and counters.
- Pack script: deterministic, refuses overwrite, staging, only packed files in the source folders, digests equal.
- Ownership.
- AWS path: `run-bounded-pipeline.sh:265-289` uploads 2, 3 and 4 files and passes `--crossref/--openalex/--orcid`.

# C5 review (code-reviewer, diff only, single pass; stored by the orchestrator)

## Verdict: APPROVE WITH NOTES. No HIGH or MEDIUM.
- **Passed:**
  - Staging sets 0755 on dirs and 0644 on files, and fails if a file lacks OTHERS_READ (`build.sbt:232-256`). The stage runs before every image build (`build.sbt:295`).
  - The image `chmod -R a-w,a+rX` runs as root before `USER 10001` (`Dockerfile.template:16, 22`), so jars end up 0444 and dirs 0555, with no jar made executable.
  - Both `Files.walk` streams are closed.
  - Ownership is build-engineer.
- **LOW (deferred):**
  - L1: the check runs right after the modes are set, so it is close to a tautology, and it does not assert that dirs have OTHERS_EXECUTE.
  - L2: on a non-POSIX filesystem the stage throws UnsupportedOperationException rather than a clear error.
  - L3: the separate `chmod -R` layer roughly doubles the lib bytes in the image. `COPY --chmod` would avoid it.
  - L4: setting the permissions follows symlinks; there are none today.
- **Not verified:** that the image and a container started as uid 10001 load QueryApiMain. This is verified at the next deploy, through the circuit breaker and the smoke run.

# C6 review (code-reviewer, diff only, single pass with HIGH rereads; stored by the orchestrator)

## Verdict
- **Pass 1:** REQUEST CHANGES. H1: on EMR, bare local paths resolve to HDFS through the cluster's defaultFS, so the Spark step would fail.
- **Reread 1:** H1 resolved by `spark.hadoop.fs.defaultFS=file:///`. New HIGH N1: EMR's default Spark event log dir (`hdfs:///…`) has no authority under that override, so SparkContext init would fail.
- **Fix N1:** `spark.eventLog.enabled=false`, plus dynamicAllocation and the shuffle service off as local-mode hardening.
- **Final check:** orchestrator-verified at `scripts/run-bounded-pipeline.sh:331-334`. Harness 414/0, observed.
- No HIGH open.

## MEDIUM (open, noted)
- M1: FinalDecisions builds its output in O(n²) (`GoldTools.scala:551-563`, owner entity-resolution-engineer). The 10K candidate count is unknown; the 1K run gave 2,191 decisions. It shares the 3600 s step budget with Spark.
- M2: driver memory reduced to 8g on the 16 GiB primary. Addressed.
- M3: no test runs the inner step script with stub binaries; the harness checks text only. Real behaviour is first verified by the 10K run.

## LOW
- L1 addressed (staging echo).
- L2: run-manifest `outputRoot` records the ephemeral primary path.
- L3 addressed (`-d`).
- L4: `SC_SPARK_SET` glob expansion (pre-existing).

## Passed
- Positional args and quoting.
- Copy semantics, and the SPARK_OUT layout matching the convert stage and LocalLoad (`LocalLoad.scala:102`).
- HJ input paths match `RunContext.scala:95-97`.
- FinalDecisions flags; directory and `.gz` input.
- Spark `--set` keys.
- Exit-code propagation, timeout and TERMINATE_CLUSTER kept.
- No silent-success path.
- EMR EC2 role covers the jars, raw, and curated Get/Put/List (`iam/main.tf:380-423`, infra-engineer audit).
- Ownership: scripts/** belongs to infra-engineer.

# C7 review (code-reviewer, diff only, single pass; stored by the orchestrator)

## Verdict: APPROVE (recommended), no HIGH.
- **Passed:**
  - Without the class argument, the step args start with `--dblp`, which the parser takes as the first flag.
  - PipelineDriver sees an even arg count, and every flag is known (`PipelineDriver.scala:35-69`).
  - All required flags are present and no value can be empty.
  - No startup refusal before HJ1 for a new run root (`:137-227`).
  - The harness asserts the class is absent and that `.[12]=="--dblp"`.
  - Orchestrator-observed:
    - the assembly MANIFEST has `Main-Class: scholarchain.hadoop.PipelineDriver`;
    - the harness gives `RESULT passed=415 failed=0`;
    - `bash -n` OK.
- **MEDIUM (pre-existing, not triggered):** a missing enrichment source folder still makes the script pass its `--<src>` flag. PipelineDriver's `listStatus` then throws before HJ1 (`run-bounded-pipeline.sh:274, 303`; `PipelineDriver.scala:105-106`). Not triggered here: crossref, openalex and orcid all exist under enrichment-10k.
- **LOW, deferred:**
  - L1: `modules/hadoop/JOB-CONTRACTS.md:64` still shows the class argument (hadoop-engineer).
  - L2: `infra/terraform/INFRA-CONTRACTS.md:555` the same (infra-engineer).
  - L3: pin `assembly / mainClass` in build.sbt (build-engineer).

# C8 review (diff only): APPROVE-RECOMMENDED
- **Change:** verified single-PUT uploads (`sc_put_verified`), a per-source S3 count check, and flags passed only for present sources.
- **M1:** `s3_count` failure hidden in `[[ ]]` (`run-bounded-pipeline.sh:327`). Fixed in C9.
- **LOW:** L1 empty key (fixed in C9); L2 convert failure message omits tmpd; L3 head-object stderr discarded.

# C9 review (diff only): APPROVE-RECOMMENDED, no HIGH or MEDIUM
- **Change:** linear-time streaming `FinalDecisions` (`Io.writeLinesNew`), Java 17 or newer selection, a 9g FinalDecisions heap, and the M1/L1 fixes.
- **LOW:**
  - L-a: the output file is mode 0600.
  - L-b: the no-replace move is not atomic.
  - L-c: an orphan temp file on a fatal error.
  - L-d: no direct `writeLinesNew` test (test-engineer, 17b).
  - L-e: `SC_FD_HEAP` is not passed into the EMR step.
  - L-f: no Java 21 path candidate.

# C10 review (diff only): APPROVE-RECOMMENDED, no HIGH
- **Change:**
  - stratified 10K embedding selection (`OpsLoad.selectStratified`, rule `stratified-round-robin-by-index-v1`);
  - manifest `selection` block;
  - INFO `logback.xml` (F14-13);
  - ops task 1024 CPU / 4096 MiB.
- **Passed:** determinism; partial chunks are safe downstream (`EmbeddingQueue.scala:111-139`, `EmbeddingWorker.scala:534-535`, `SemanticContext.scala:85-112`); safety semantics unchanged; removed manifest keys read nowhere; readMeta rewrite equivalent; AWS SDK at WARN silences the SigV4 lines.
- **MEDIUM (deferred to 17b):**
  - M1: `ops_memory` validation is not tied to `ops_cpu` (`variables.tf:202-203`; infra-engineer).
  - M2: `modules/services/API.md:279` still describes the removed rule (service-engineer).
- **LOW:**
  - L1: chunk ids sort as text.
  - L2: `...ByType` keys hold index names.
  - L3: the number check covers selected docs only.
  - L4: stale sizing text (`variables.tf:180, 197`; `INFRA-CONTRACTS.md:646, 668`).
  - L5: duplicate comment.
  - L6: one end-to-end test uses the function under test.
  - L7: `reports/pipeline/**` and `reports/release/**` have no owner (factory-engineer).
- **CHECK (orchestrator-run canonical command):** `[success]` in 2208 s, marker `exit=0` at 2026-11-26 05:27:22 CST. Every module: Failed 0, Errors 0. services: 926 passed.
