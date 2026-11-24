# Phase 13 — code-reviewer report (work order D, round 1)

Stored by the orchestrator from the code-reviewer's returned message. Note: the reviewer read agent-report.md before the orchestrator had appended the work-order-A summary, so it states that infra-engineer's claims were unavailable to it; it reviewed the code directly.

**Verdict (round 1): REQUEST CHANGES** — 0 HIGH, 8 MEDIUM, 9 LOW, 3 INFO.

## MEDIUM

- **M1** deploy.sh:80-94 — plan-checksum gate passable without an approved plan: `--plan-record` accepts any path; the .tfplan is hashed only if present; plans/ is git-ignored. Fix: require the .tfplan to exist and match; restrict the record to infra/terraform/plans/; require the approved SHA to appear in the phase human-gate.md.
- **M2** package.sh:44-64, 82-89; images.sh:13-33 — `--skip-build` / `SC_LOCAL_IMAGE` / `SC_IMAGE_SOURCE_*` can re-tag a dirty-built image as the clean commit SHA (`dirty:false`); jars likewise taken from target/. Fix: refuse those on the release path or verify a commit label.
- **M3** run-bounded-pipeline.sh:220-221 — default `--hadoop-mode tools` calls `hadoop jar … scholarchain.hadoop.<X>Tool`, but those tools have no `main` (PipelineDriver.scala:361-407); HJ1 fails at once. Fix: default to `driver` (JOB-CONTRACTS.md:64).
- **M4** run-bounded-pipeline.sh:312-313 — step watcher normalises only `+00:00`; on a non-UTC host jq fails, run.json stays `running`, no 10K record. Fix: `TZ=UTC` or parse the offset (CLI behaviour unverified).
- **M5** run-bounded-pipeline.sh:130-132 — `SC_EMR_INSTANCE_TYPE` and `SC_EMR_IDLE_TIMEOUT` unbounded; larger instance classes need human approval (docs/agents.md §4). Fix: allow-list and cap idle at 900.
- **M6** deploy.sh:183, 217 — no deployment record on failure after services were updated, yet the operator is told to roll back from one. Fix: write an `in-progress` record (previous + new task definitions) before waiting.
- **M7** package.sh:59-64 — `SC_JOBS_BUILD_CMD` / `SC_IMAGE_BUILD_CMD` execute arbitrary commands the guard never inspects, through a deploy-engineer-approved script. Fix: accept only the sbt aliases.
- **M8** smoke-test.sh:65-71 — sends the real API key over plain HTTP (the default query_api_url is http) and to any `SC_SMOKE_BASE_URL` host. Fix: refuse http unless an explicit flag; bind the host to the Terraform output.

## LOW (deferred unless cheap)

- L1 common.sh:324-330 newest plan record sorted by commit hash, not time.
- L2 cleanup.sh:45 `--gate-file`, rollback.sh:50 `--approval-ref` accept any non-empty file; the stated reason cleanup.sh is guard-blocked is inaccurate (`sc_aws` wrapper); still likely blocked by other lines (unverified).
- L3 hard-coded `scholarchain/<svc>` and `scholarchain-<svc>` names instead of deriving from outputs.
- L4 reused `--run-id` overwrites earlier raw inputs and results.
- L5 50K gate accepts a hadoop-only 10K record (weaker than Rule E).
- L6 smoke-test key-file "outside the repo" check bypassable with a relative path.
- L7 run-experiment.sh: external manifest dir, hard-coded recorded path, no manifest checksum, unguarded `command`, results outside experiments/**, no account/region guard.
- L8 tests that would pass if broken (worker desired-count, exit-3 without message, no missing .tfplan case, ECS stub always stable, EMR stub never RUNNING, no tests for `--plan-record`, `--skip-build`, in-repo key file).
- L9 `--allow-dirty` overwrites the clean artifacts.json (fails closed).

## INFO

- I1 settings.json ask rules are prefix matches; `VAR=… bash scripts/deploy.sh` may not match (owner factory-engineer).
- I2 reports/{release,pipeline,cleanup,evidence,experiments,env-window,smoke} are unowned paths (route to factory-engineer); .gitignore decision (M13-3, build-engineer).
- I3 deploy.sh and env-window.sh change desired counts through the ECS API, contrary to INFRA-CONTRACTS §5/§9b "never by hand", but required by the work order; drift disclosed (M13-6).

## Checks that passed (by reading)

No apply/destroy/state mutation executed; retries only on reads; timeouts on cloud calls; every EMR step TERMINATE_CLUSTER + ≤3600 s `timeout`; auto-termination, ≤2 core nodes, no terminate-clusters; 50K refused before any AWS call; clean-tree enforcement in deploy/rollback/pipeline/package/run-experiment; secrets never on argv or printed; account IDs never printed; Terraform output names exist; pipeline flags match code; changed paths owned by infra-engineer.

## Smoke-test key-file deviation (reviewer judgement, round 1)

Mechanically safe (not on argv, not printed, not recorded, mode checked), and the reason is accurate (the guard blocks the secret-store read in script text). It deviates from the work order and the CLAUDE.md secrets rule, so it is acceptable only as an explicit human security decision recorded in human-gate.md, together with fixes for M8 and L6.

---

# Round 2 — verification of repair round 1 (final review)

**Verdict: APPROVE WITH NOTES.** All 8 MEDIUM findings are fixed. The reviewer found no obvious bypass, and each fix has a test that would fail if the fix were reverted, judged by reading the tests. L4, L6 and L8 are fixed. L1 is fixed but untested. No new HIGH or MEDIUM issue was found. The deferrals (L2, L3, L5, L7, I1–I3, R1-1 to R1-3) are disclosed in INFRA-CONTRACTS §14.5.

Per finding (by reading):
- **M1:** deploy.sh:82-118 and common.sh:361-382. The record must resolve into plans/; the .tfplan must exist and match; the gate file must resolve to `docs/agent-runs/<x>/human-gate.md`, contain the hash, and is read only. Tests: test-scripts.sh:330-353.
- **M2/L9:** package.sh:50-55, :73; deploy.sh:137-138. Tests: :295-313, :360-367.
- **M3:** only `driver` is accepted; PipelineDriver has `main` and the flags match. Tests: :493-496, :522-523.
- **M4:** `TZ=UTC`; the ±HH:MM epoch parser handles the sign correctly; a parse failure falls back to the in-step timeout. Tests: :537-546.
- **M5:** m5.xlarge only without approval and a gate file; idle 60-900 s; step ≤3600 s. Tests: :498-510.
- **M6:** in-progress record before the first update-service; the EXIT trap marks failed. The stub enforces the ordering. Tests: :393-409.
- **M7:** fixed in code. The test is weak (N3).
- **M8/L6:** strict URL regex; http refused without the flag; host bound to `query_api_url`; no `-L`; the key path is resolved. Tests: :427-469.

Notes (none blocking):
- **N1 (LOW)** common.sh:378: the gate check is a case-insensitive substring match, so "plan <sha> REJECTED" also passes. Suggested fix: a structured line such as `APPROVED-PLAN-SHA256: <sha>`.
- **N2 (LOW)** images.sh:37: the default release image is the newest-created `scholarchain-services` tag, and a layer-cached fresh build could lose to a newer stale tag.
- **N3 (LOW)** test-scripts.sh:304-306: the M7 test covers only `SC_JOBS_BUILD_CMD`, and its "no build command ran" assertion cannot fail.
- **N4 (LOW)** run-bounded-pipeline.sh:327-332: epoch-number timestamps disable the external cancel watcher. This fails safe, since the in-step timeout, TERMINATE_CLUSTER and idle termination remain.
- **N5 (LOW)** run-bounded-pipeline.sh:164: the run-id reuse check is local only.
- **N6 (INFO)** smoke-test.sh:133: curl honours proxy environment variables, so under `--allow-insecure-http` a proxy sees the key.
- **N7 (INFO)** run-bounded-pipeline.sh:247-258: `SC_LOADER_STEP_ARGS` / `SC_ENQUEUE_STEP_ARGS` run arbitrary commands on EMR, bounded by the timeout and TERMINATE_CLUSTER. Predates the repair.

Not verified: R1-1 (whether the guard accepts deploy-engineer running scripts that mention human-gate.md; by reading, there is no write verb), and all real-AWS behaviour (CLI timestamp format, `timeout` on PATH, pi jar path).
