# INC-001: C4 service images unreadable by the container user (Phase 14 finding F14-4)

Status: closed by fix C5. Record author: incident-responder. Sourced only from committed Phase 14 evidence and git history. Local untracked files under `reports/deployments/**` are cited as "local, uncommitted". No AWS call was made for this record. The account ID is redacted as `<acct>`.

Citation keys: AR = `docs/agent-runs/phase-14-deploy/agent-report.md`, RR = `docs/agent-runs/phase-14-deploy/review-report.md`. "Human-reported" and "orchestrator-observed" are the evidence labels used in AR. The responder did not re-observe any AWS state.

## 1. Summary

The C4 release (`c334a9adc55d`) was deployed. The query-api tasks crashed with "Could not find or load main class scholarchain.services.QueryApiMain", because every scholarchain jar in the image had mode 0600 root:root while the container runs as uid 10001. The ECS deployment circuit breaker rolled query-api back to the C3 task definition automatically. No C4 service served traffic. The fix was commit C5 `dc20d0f`, which makes the staged jars world-readable and fails the stage if they are not. (AR:557-575; commit `dc20d0f`)

## 2. Spec fields

| Field | Value | Citation |
|---|---|---|
| Incident ID | INC-001 (Phase 14 finding F14-4) | AR:557; work order `docs/agent-runs/phase-15c-aws-experiments/prompt.md:43` |
| Environment | `course`, region us-east-2, ECS cluster `scholarchain-cluster` | local, uncommitted: `reports/deployments/c334a9adc55da950fcdbd2aa6cfa53d6fa3563c7/deployment.json` |
| Start time | CloudWatch error window begins 2026-11-25 15:58 UTC. The window is human-reported, and its date is given by the work order and not by AR:563 (which gives only the clock times). The deploy record's `updated_at` of 2026-11-25T16:12:40Z is consistent with it. Exact first task crash time: [[UNKNOWN]] | AR:563; prompt.md:43; local, uncommitted deployment.json |
| End time | CloudWatch error window ends 16:08 UTC (human-reported). Time the circuit breaker completed the rollback: [[UNKNOWN]]. Time the services were confirmed healthy on C3: [[UNKNOWN]] | AR:563, AR:565 |
| Detection source | The deploy script itself. `deploy.sh` exited 5 and logged "query-api did not stabilise in 900s" and "deployment FAILED after the services were changed". The symptom text came from the CloudWatch log group `/scholarchain/query-api` (human-reported). | AR:560-563; local, uncommitted `reports/deployments/2026-11-25-deploy-c334a9adc55d.log:49-50` |
| User-visible impact | None. No C4 service ever served traffic. query-api and retrieval kept serving C3 (human-reported). Caveat: the local deployment record lists retrieval `task_definition` `:3` (new) with `previous_task_definition` `:2`, while AR:566 says retrieval was never updated. The two sources do not agree on whether retrieval was touched. Whether any retrieval task ran from `:3`: [[UNKNOWN]]. | AR:564, AR:566-567; local, uncommitted deployment.json |
| Severity | [[UNKNOWN]] (no severity scale is recorded in the evidence) | n/a |

## 3. Trace and run IDs

| Item | Value | Citation |
|---|---|---|
| Failed release (C4) | release id `c334a9adc55d`, commit `c334a9adc55da950fcdbd2aa6cfa53d6fa3563c7`, authored 2026-11-25 03:56:42 -0600 | `git show --stat c334a9a`; AR:560 |
| Deployment record (C4) | `reports/deployments/c334a9adc55da950fcdbd2aa6cfa53d6fa3563c7/deployment.json`, status `failed`, failure_reason "query-api did not stabilise within 900s", image_tag `c334a9adc55d`, approved plan sha256 `4bd8164d14fd305b62b4e6b4155013c06303250a747116fbf5b9353f83bd33f5` (P1). Local, uncommitted file. | AR:561; local, uncommitted |
| Failed task definitions | query-api `:3`, retrieval `:3`, embedding-worker `:3` (from `:2`, C3) | local, uncommitted deployment.json |
| C4 image digests | query-api `sha256:d9e0b465e4f47f662691b0dacc989ca7e32784dfa9e760aa6e060a3172991bef`; retrieval `sha256:ea932c2e274d362bfa6424c2f71671ac53119f8fb0ba1b22411f755ee5a817b7`; embedding-worker `sha256:5b5076d0f43cb00f77cf597ee953df7719b255bc1444aec2c310f22427a7d494` | local, uncommitted deployment.json; deploy log:45 |
| Deploy log | `reports/deployments/2026-11-25-deploy-c334a9adc55d.log` (local, uncommitted; the log contains the raw account ID and must not be copied into committed files without redaction) | AR:562 |
| Rollback target (C3) | release `f5cc5f133415`, commit `f5cc5f133415904e20dcb3ad86515d49b87f4493`, task definition `:2` | AR:565; `git log` |
| Fix release (C5) | release `dc20d0fa23df`, commit `dc20d0fa23df346bddf93c1a2a07195a07566a9e`, authored 2026-11-25 11:07:14 -0600 | `git show --stat dc20d0f`; AR:594 |
| C5 plan P2 | plan_sha256 `9312a25e4704d7060626914e569ae0041b722ccb4a3cd4a860ce2b49c9280447` | AR:596, AR:965 |
| CloudWatch log group | `/scholarchain/query-api` | AR:563 |
| ECS deployment ID, ECS task IDs | [[UNKNOWN]] (not in committed evidence) | n/a |

## 4. Timeline (all times UTC on 2026-11-25 unless stated)

| Time | Event | Citation |
|---|---|---|
| 09:56:42 (03:56 -0600) | C4 `c334a9a` committed (packed enrichment input for HJ2). The staged Docker context `docker-services/lib/` held 0600 files dated 03:57 local time. | `git show --stat c334a9a`; AR:572 |
| [[UNKNOWN]] | C4 images packaged. The deploy log shows the three images already existed locally and match `artifacts.json`. | local, uncommitted deploy log:10 |
| [[UNKNOWN]] | Human runs `deploy.sh` for `c334a9adc55d` with the P1 plan pinned. Preflight, plan checksum, identity, secrets and release-artifact steps pass. query-api and retrieval tags were already in ECR (immutable) and reused. embedding-worker pushed. | local, uncommitted deploy log:1-45 |
| [[UNKNOWN]] | `register-and-update` writes the in-progress record. | local, uncommitted deploy log:46-47 |
| 15:58 | Start of the CloudWatch error window: query-api C4 tasks crash with "Could not find or load main class scholarchain.services.QueryApiMain" (human-reported). | AR:563 |
| 15:58 to 16:08 | ECS deployment circuit breaker rolls query-api back to task definition `:2` (C3). Automatic, no operator action (human-reported). | AR:565 |
| 16:08 | End of the CloudWatch error window. | AR:563 |
| [[UNKNOWN]] | `deploy.sh` `wait-stable` gives up after 900 s with "query-api did not stabilise", logs "SC-WARN deployment FAILED after the services were changed", exits 5. | AR:560, AR:562; local, uncommitted deploy log:49-50 |
| 16:12:40 | Deployment record last updated, status `failed`. | local, uncommitted deployment.json |
| [[UNKNOWN]] | State after rollback (human-reported): query-api on `:2` and healthy; retrieval on `:2`; embedding-worker on `:3` (C4) with desired count 0 so no tasks run. `rollback.sh` was not run. P2 was not planned and `image_tag` was not added to tfvars. | AR:565-568 |
| [[UNKNOWN]] | Root cause established by inspecting the C4 images (human-reported) and the staged context. | AR:569-574 |
| [[UNKNOWN]] (before 11:07 -0600, which is 17:07) | build-engineer fixes the Dockerfile template and `servicesDockerStage`. CHECK run 1 failed in 1 s at scalafmtSbtCheck. Run 2 `[success]`, marker `exit=0` at 11:02:30 local (orchestrator-observed). | AR:579-589 |
| 17:07:14 (11:07:14 -0600) | C5 `dc20d0f` committed after a diff-only review (APPROVE WITH NOTES, no HIGH or MEDIUM). | `git show --stat dc20d0f`; RR:104-117 |
| [[UNKNOWN]] (before 18:26:56Z) | C5 `dc20d0fa23df` packaged and deployed, exit 0 (human-reported). | AR:594 |
| 18:26:56 | Plan P2 `dc20d0fa23df` created (image_tag set to C5), later applied by the human. | AR:965 |
| [[UNKNOWN]] | Later read-only check: all three services on `:5`, the C5 image. | AR:660 |
| [[UNKNOWN]] | Later read-only check on the C8 image: 58/58 jars world-readable, uid 10001. | AR:750 |

## 5. Evidence

- C4 images: every scholarchain jar in `/opt/scholarchain/lib` has mode 0600 root:root. The C3 images had 0644. The container runs as uid 10001 and cannot read the jars. Jar contents match C3 (same size, QueryApiMain present). (human-reported; AR:570-571)
- The staged context `docker-services/lib/` held 0600 files dated 03:57, while the source jars in `universal/stage/lib` were 0644 with their original mtimes. (AR:572, AR:583)
- `packaging/Dockerfile.template` applied `chmod -R a-w,a+rX` to `prompts/` only, so `lib/` kept the host modes. (AR:573; diff in `dc20d0f`)
- The deploy script failed with exit 5. (AR:560; local, uncommitted deploy log:49-50)
- ECR tags are IMMUTABLE (`modules/platform/main.tf:7`), so `c334a9adc55d` could not be re-pushed and a new commit was required. (AR:575)

## 6. Root cause

The Docker staging step (`servicesDockerStage` in `build.sbt`) copied the jars into the Docker context with mode 0600. The image template did not normalise `lib/` permissions, so the image kept 0600 root:root jars. The container runs as uid 10001 and could not read them, and the JVM reported the main class as not found. (AR:569-574; `git show dc20d0f`)

Not established: the exact mechanism that produced 0600 in the copy. The build-engineer inferred a copy through a temp file (`IO.copyDirectory` did not preserve modes) but did not confirm it in the sbt-io source. (AR:583-584) The root cause statement is human-reported in AR:1028. Why C3 produced 0644 and C4 produced 0600 from the same staging code: [[UNKNOWN]].

## 7. Recovery action

- Automatic: the ECS deployment circuit breaker rolled query-api back to task definition `:2` (C3). (human-reported; AR:565)
- Manual rollback: none. `scripts/rollback.sh` was not run because the services were already on C3. (AR:568) No human approval for a rollback was therefore needed or recorded for this incident.
- Permanent: fix C5 `dc20d0f` (see section 9), then C5 packaged and deployed with exit 0 (human-reported; AR:594) and planned and applied as P2 (AR:595-598).
- embedding-worker stayed on `:3` (C4) at desired count 0 until the later C5 deploy (AR:567, AR:660).

## 8. Data-integrity assessment

No data was read, written, or modified by the failed C4 tasks. The failure happened at JVM start, before any application code ran, so no service loaded or wrote data. embedding-worker had desired count 0. (AR:563, AR:567, AR:572) Evidence is inferential, from the symptom and the desired counts. No check of Neptune, DynamoDB, S3 or vector contents was recorded for this incident: [[UNKNOWN]]. No data repair was performed or needed on the evidence available. No schema, secret or destructive action was involved.

## 9. Preventive controls

| Control | State | Citation |
|---|---|---|
| Stage-time check in `servicesDockerStage`: sets 0755 on dirs and 0644 on files in the staged lib, and fails the stage if any file lacks OTHERS_READ. It runs before every image build. This is a build check, not a named test. | Added in C5 | `git show dc20d0f -- build.sbt`; RR:107-108 |
| Image template: `chmod -R a-w,a+rX` now covers `/opt/scholarchain/lib` as well as prompts, run as root before `USER 10001`. Jars end 0444, dirs 0555. | Added in C5 | `git show dc20d0f -- packaging/Dockerfile.template`; RR:109 |
| Gaps accepted by the reviewer (LOW, deferred): the stage check runs right after setting the modes so it is close to a tautology (L1); no assertion that dirs have OTHERS_EXECUTE (L1); non-POSIX filesystem throws (L2); the extra chmod layer roughly doubles lib bytes (L3); symlinks followed (L4). | Open | RR:111-115 |
| Not verified by the reviewer: that a container started as uid 10001 loads QueryApiMain. | Open at C5 review. Later partial evidence: all three services ran on `:5` (C5 image) and 58/58 jars were world-readable for uid 10001 on the C8 image (read-only checks by the orchestrator). | RR:117; AR:660, AR:750 |
| PROPOSED (not implemented): a post-build image test that starts each image (or runs `docker run --user 10001 ... java -cp 'lib/*' <main class> --help` or an equivalent class-load probe) and fails the build if the main class cannot be loaded, plus an assertion that every file under `/opt/scholarchain/lib` is world-readable and every directory world-executable. This would catch a regression independent of the host staging step. | PROPOSED | n/a |
| PROPOSED: run the same class-load probe as a `deploy.sh` preflight against the pushed tag, so an unreadable image fails before ECS is updated. | PROPOSED | n/a |

No named test was added in C5 (`git show --stat dc20d0f` lists only `build.sbt`, `packaging/Dockerfile.template`, and the two Phase 14 report files).

## 10. Follow-up owner

- Permanent control (image-level test and the deferred L1 to L4 notes): `build-engineer`, owner of `build.sbt` and `packaging/**` (docs/agents.md section 1). The test itself would be encoded by `test-engineer` (`tests/**`). A preflight probe in `deploy.sh` would belong to `infra-engineer` (`scripts/**`).
- Release record and rollback decisions: `deploy-engineer` (`reports/deployments/**`).

## 11. Open items and discrepancies

- Retrieval task definition: AR:566 says retrieval was never updated, but the local, uncommitted deployment.json lists retrieval `:3`. Neither source says whether a retrieval task started from `:3`. [[UNKNOWN]]
- Whether the root-cause facts about image modes (AR:570-573) were independently re-observed: they are marked human-reported, not orchestrator-observed.
- Time of the circuit-breaker rollback completion and ECS deployment/task IDs: [[UNKNOWN]].
- Work-order facts checked against evidence: the 15:58 to 16:08 UTC window (AR:563, human-reported; the date 2026-11-25 is from the work order and is consistent with the deployment record), automatic circuit-breaker rollback (AR:565), no user impact (AR:564, AR:572), 0600 jars vs uid 10001 (AR:570-571), fix C5 (AR:575, `dc20d0f`). All confirmed in the committed report, all as human-reported.

## 12. Escalations

None required. No schema change, data repair, secret rotation or destructive action was involved or recommended.
