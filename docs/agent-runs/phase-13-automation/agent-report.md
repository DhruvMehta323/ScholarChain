# Phase 13 — Build, deployment and experiment automation: agent report

Assembled by the orchestrator. Work orders: A (infra-engineer), D (code-reviewer).

## 1. Preconditions (orchestrator, read-only)

| Check | Observed |
|---|---|
| Branch | `lane2` (`git rev-parse --abbrev-ref HEAD`) |
| HEAD | `5edd031` (Phase 12 accepted); `git merge-base --is-ancestor 5edd031 HEAD` exit 0 |
| `git status --short` | only `?? docs/agent-runs/phase-13-automation/` |
| Phase 12 gate | read; G12-4, G12-5 routed to cleanup.sh, G12-6 to the Phase 14 apply checklist |
| Phase 12 scripts | `scripts/plan.sh` (155 lines), `scripts/cleanup.sh` (92 lines), `scripts/lib/common.sh` present |
| Tools | `shellcheck` (/opt/homebrew/bin), `jq`, `python3` present |

### Build commands recorded from `build.sbt` (lane2, unchanged)

- Canonical: `sbt check` = `scalafmtCheckAll; scalafmtSbtCheck; Test/compile; Test/testFull; contractTest; markTestRun` (build.sbt:238-241).
- Assemblies: `sbt packageJobs` = `hadoop/assembly; sparkAnalytics/assembly` (build.sbt:242); jar name `<name>-<version>-assembly.jar` (build.sbt:82).
- Image: `sbt dockerLocal` = `services/Docker/publishLocal` (build.sbt:243); `Docker / packageName := "scholarchain-services"` (build.sbt:190), `dockerUpdateLatest := false`.

### Merge items recorded (not crossed)

- **M13-1:** lane2 `build.sbt` defines ONE service image (`scholarchain-services`), while Terraform defines three ECR repos / ECS services (`scholarchain/query-api`, `scholarchain/retrieval`, `scholarchain/embedding-worker`). The Phase 10/11 per-service image definitions live on main. Scripts must accept the image build command / per-service image mapping as configuration until the merge reconciles it.

## 2. Work order A — infra-engineer (report summary)

STATUS: COMPLETE (agent report). Files created: scripts/{check,package,deploy,rollback,smoke-test,run-bounded-pipeline,run-experiment,archive-evidence,env-window}.sh, scripts/lib/{images,ecs}.sh, scripts/tests/test-scripts.sh. Modified: scripts/cleanup.sh, scripts/lib/common.sh, infra/terraform/INFRA-CONTRACTS.md (new section 14 only, +57 lines). sbt check: NOT RUN (forbidden this phase; no Scala/build source changed).

Agent-reported deviations and risks (disclosed, for review):
- smoke-test.sh does NOT read Secrets Manager: the guard blocks scripts containing a secret-store read; the human supplies the key in a mode-600 file via `SC_SMOKE_API_KEY_FILE` (M13-7). Deviation from work order item 5.
- cleanup.sh contains the ECR image batch-delete call and is therefore guard-blocked for agents (human-executed, accepted per docs/agents.md §5); the real file was only syntax/shellcheck-checked, its logic tested on a sanitized copy.
- Load and enqueue stages of run-bounded-pipeline.sh exit 7 until `SC_LOADER_STEP_ARGS` / `SC_ENQUEUE_STEP_ARGS` are given: no EMR entry point exists on lane2 (M13-2).
- deploy.sh registers task definitions outside Terraform → plan drift (M13-6). The Terraform state machine still uses CANCEL_AND_WAIT / a 3 h step timeout; the pipeline script bypasses it.
- EMR step `timeout` wrapper, pi jar path, auto-termination flags: NOT VERIFIED on a real cluster.
- Merge/follow-up items outside lane-2 paths: M13-1 (one image vs three services), M13-3 (reports/** not git-ignored; build-engineer), M13-4 (EMR SG id / tag outputs; infra later round).

### Orchestrator verification (observed)

| Command | Result |
|---|---|
| `git status --short` | 3 modified (INFRA-CONTRACTS.md, cleanup.sh, lib/common.sh) + untracked new scripts and this folder; nothing outside allowed paths |
| `shellcheck -x -P SCRIPTDIR scripts/*.sh scripts/lib/*.sh scripts/tests/*.sh` | exit 0 |
| `bash -n` on every script, lib and harness | no failures |
| `SC_TEST_SCRATCH=<scratchpad>/phase13-orch bash scripts/tests/test-scripts.sh` | `RESULT passed=148 failed=0` |

## Progress checkpoint

Work order A complete and verified locally (no AWS, no sbt). Next: work order D (code-reviewer).

## 3. Work order D — code-reviewer, round 1

Verdict: **REQUEST CHANGES** (0 HIGH, 8 MEDIUM, 9 LOW, 3 INFO). Full text: `review-report.md`.

## Progress checkpoint

Review round 1 stored. Repair round 1 (the only one allowed) routed to infra-engineer: all 8 MEDIUM (M1–M8), plus L6 (tied to M8), the L2 doc correction, and the cheap LOWs L1, L4 and L8. Deferred with disclosure: L2 (gate-file strictness for cleanup/rollback), L3, L5, L7, and I1–I3 (I1/I2 → factory-engineer, M13-3 → build-engineer).

## 4. Repair round 1 — infra-engineer (report summary)

STATUS: COMPLETE (agent report). Modified only scripts/** and INFRA-CONTRACTS.md §14 (new §14.5 lists the fixes and deferrals).

| ID | Status (agent) | Fix (agent) |
|---|---|---|
| M1 | fixed | the record must resolve into infra/terraform/plans/ (symlinks resolved); the .tfplan must exist and match; APPROVED_PLAN_SHA256 must appear in `--gate-file docs/agent-runs/<phase>/human-gate.md` (read only) |
| M2 / L9 | fixed | `--skip-build` and image overrides refused without `--allow-dirty`; local runs go to `reports/release-local` with `release:false`; deploy requires `release:true, dirty:false` |
| M3 | fixed | `--hadoop-mode driver` (PipelineDriver) is the default; `tools` removed |
| M4 | fixed | `TZ=UTC`; parser accepts Z and any ±HH:MM; stub returns RUNNING steps with non-UTC offsets |
| M5 | fixed | only m5.xlarge without `--approve-instance-class` plus a gate file naming the type; idle timeout 60–900 |
| M6 | fixed | `in-progress` deployment record written before any update-service; EXIT trap marks failed/succeeded |
| M7 | fixed | free-form build commands refused; only `sbt packageJobs` / `sbt dockerLocal` |
| M8 / L6 | fixed | http refused without `--allow-insecure-http` (loud warning); host bound to the Terraform `query_api_url` (loopback only otherwise); key path resolved before the in-repo check |
| L1, L4, L8 | fixed | sort by UTC stamp (untested); reused run id refused; about 60 new assertions |
| L2 | partly | doc correction only (cleanup.sh is guard-blocked by its printed Terraform command text, not the ECR call); gate-file strictness deferred |
| L3, L5, L7, I1–I3 | deferred | INFRA-CONTRACTS §14.5 |

Agent mutation checks: M1 (4 variants), M2, M5 (2), M8 (2) and L6 each made the harness fail. M3, M4, M6 and M7 were NOT mutation-checked.

New risk disclosed: with the default http `query_api_url`, a real smoke test needs `--allow-insecure-http`, so the API key crosses the network in clear text and should be rotated afterwards (human security decision).

### Orchestrator verification (observed)

| Command | Result |
|---|---|
| `git status --short` | same path set as before; nothing outside scripts/**, INFRA-CONTRACTS.md and this folder |
| `shellcheck -x -P SCRIPTDIR scripts/*.sh scripts/lib/*.sh scripts/tests/*.sh` | exit 0 |
| `SC_TEST_SCRATCH=<scratchpad>/phase13-orch-r1 bash scripts/tests/test-scripts.sh` | `RESULT passed=253 failed=0` |

## Progress checkpoint

Repair round 1 complete and verified locally. Next: code-reviewer verifies the repair (no further repair round is allowed).

## 5. Work order D — code-reviewer, round 2 (final)

Verdict: **APPROVE WITH NOTES**. M1–M8 are fixed; L4, L6 and L8 are fixed; L1 is fixed but untested; no new HIGH or MEDIUM. Notes N1–N5 (LOW) and N6–N7 (INFO) are listed in `review-report.md` round 2. Per the repair policy (one round), they are deferred with disclosure and would need a new work order.

### Items for the human gate

1. Verdict decision: APPROVE WITH NOTES. Decide whether to carry forward N1 (structured gate line), N2 (explicit release image tag) and N3 (strengthen the M7 test).
2. Security decision (docs/agents.md §4): the smoke test reads the services API key from a human-created mode-600 file (`SC_SMOKE_API_KEY_FILE`) instead of Secrets Manager, because the guard blocks a secret-store read inside script text. This deviates from work order item 5 and from the CLAUDE.md secrets rule.
3. Security decision: with the default http `query_api_url`, a real smoke test needs `--allow-insecure-http`, so the key crosses the network in clear text; rotate the key afterwards.
4. For a deploy, the human-gate.md of the deploy phase must contain the approved plan SHA-256 and be committed (deploy.sh reads it via `--gate-file`).
5. Routing outside lane 2: I1 (settings.json ask-rule prefixes) and I2 (unowned `reports/*` paths) → factory-engineer; M13-3 (.gitignore for reports/*) → build-engineer; M13-1 (one image vs three services) and M13-2 (EMR loader/enqueue entry points) → lane2→main merge.
6. `scripts/cleanup.sh` is guard-blocked for agents (by its printed Terraform command text) and is human-executed only.
7. Not run this phase (by work order): sbt check, any AWS call, Terraform init/plan/validate, docker, and a security scanner.

## Progress checkpoint

Phase 13 complete; waiting for human gate. Nothing staged or committed.
