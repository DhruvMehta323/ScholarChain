# INC-002: Experiment F deliberate failure drill, one stopped retrieval task (run p15c-f1)

Status: closed. This is a planned drill, not an outage. Record author: incident-responder. No AWS call was made for this record. The account ID is redacted as `<acct>`.

Citation keys (paths relative to the repository root):
- F/ = `experiments/results/exp-f-failure-recovery/p15c-f1/`
- SUM = `experiments/results/aws/SUMMARY-v2.md`
- MAN = `experiments/manifests/exp-f-failure-recovery.json`
- AR = `docs/agent-runs/phase-15c-aws-experiments/agent-report.md`

Provenance note: F/, SUM and `reports/experiments/**` were untracked (uncommitted) when this record was written. They are cited as local files. `derived-exp-f-summary.json` is labelled DERIVED by its own `kind` field (F/derived-exp-f-summary.json:14). The raw files are the primary evidence, and the derived numbers below were re-derived from the raw files where possible.

## 1. Spec fields

| Field | Value | Citation |
|---|---|---|
| Incident ID | INC-002 (experiment F, work order X5) | F/derived-exp-f-summary.json:34; AR:133 |
| Start time | 2026-11-27T02:58:51.764Z (stop-task issued). The drill record began 02:58:51.186Z. | F/failure_drill-p15c-f1-record.jsonl:1,3 |
| End time | 2026-11-27T02:59:31.822Z (ECS "has reached a steady state" event). The old task was STOPPED at 02:59:38.405Z. The sampler ran to 03:04:00Z. | F/ecs-describe-services-retrieval-final.json (events); F/describe-tasks.json (`stoppedAt` 2026-11-26T20:59:38.405-06:00); F/sample_resources-p15c-f1-samples.jsonl:23 |
| Detection source | Planned drill: the operator issued the stop, so nothing had to detect it. In operation, the evidence shows only an ECS service event ("has started 1 tasks" at 20:58:54.348-06:00, "has reached a steady state" at 20:59:31.822-06:00). No alarm fired or was checked in this run, and no alarm is evidenced for a retrieval task stop. Committed Terraform contracts list alarms on query-api target 5xx, DLQ depth above 0 and embedding queue age, none on retrieval task count (`infra/terraform/INFRA-CONTRACTS.md:237`). Whether any alarm would have fired for this event: [[UNKNOWN]]. | F/ecs-describe-services-retrieval-final.json; `infra/terraform/INFRA-CONTRACTS.md:237` |
| User-visible impact | NOT MEASURED. No `/v1/query` request was sent during the gap, because the Bedrock budget was used up. `/healthz` returned 200 before, during and after, but it is served by query-api through the ALB and does not test retrieval. Query-path availability during the 32.9 s gap is [[UNKNOWN]]. | F/derived-exp-f-summary.json:13,12; F/healthz-probes.txt:1-5; SUM:30; AR:149 |
| Trace and run IDs | Run `p15c-f1`; experiment `exp-f-failure-recovery`; work order X5; commit `312c90d46d20b86cf95f156d578ccd708e70ddcf`; manifest sha256 `29d626c69c337bf1a992a390a2c8738763746c690ecbf8832c0c9c71bfc2edd9`; cluster `scholarchain-cluster`, region us-east-2. Stopped task id `48763108e0e54673a8a9f32e10f13f6f` (ends 10f13f6f). Replacement task id `13296170a64e4a65b907ff166ffbb9cc` (ends 6ffbb9cc). Task definition `scholarchain-retrieval:15`, image `scholarchain/retrieval:38f2160a6e95`, digest `sha256:1f9bce2794e5734d76282190870fd34d1834e1af9c600d9336e18457b617aac3`. Record file `failure_drill-p15c-f1-record.jsonl`. ARNs carry `<acct>` in place of the account ID. | F/failure_drill-p15c-f1-record.jsonl:1,2,4; F/list-tasks-running.json:3; F/describe-tasks.json; F/collect_cloudwatch-p15c-f1.meta.json:16; AR:135 |
| Root cause | A deliberate, single `ecs stop-task` on the only running retrieval task, `stopCode UserInitiated`, `stoppedReason "experiment F drill"`. Container exit code 143. | F/describe-tasks.json (`stopCode`, `stoppedReason`, `exitCode`); F/failure_drill-p15c-f1-record.jsonl:3-4 |
| Recovery action | None by an operator. The ECS service scheduler started a replacement task 2.5 s after the stop (PROVISIONING 02:58:54.308Z; event 02:58:54.348Z). No retry and no rollback script was used. | F/ecs-describe-services-retrieval-final.json; F/derived-exp-f-summary.json:19,27; F/run-experiment-console.log:3 |
| Data-integrity assessment | See section 5. | n/a |
| Follow-up owner | `infra-engineer` for the proposed retrieval health control (Terraform and `scripts/**`). See section 7 and section 8. | docs/agents.md section 1 |
| Preventive test or control | None exists in the evidence. Controls are PROPOSED in section 7. | n/a |

## 2. Timeline (UTC; ECS timestamps in the raw files are -06:00 and were converted)

| Time (2026-11-27 unless noted) | Event | Citation |
|---|---|---|
| 2026-11-26T21:30:11Z | The stopped task entered RUNNING (`startedAt` 15:30:11-06:00). | F/describe-tasks.json |
| 02:58:30.682Z | First sampler sample: query-api 1/1 running, retrieval desired 1 running 1 pending 0. | F/sample_resources-p15c-f1-samples.jsonl:1 |
| about 02:58:35Z | Pre-drill `/healthz` probe, HTTP 200 (shell clock; approximate). | F/healthz-probes.txt:2 |
| 02:58:51.186Z | Drill record created; plan is list-worker-tasks then stop-one-worker-task. | F/failure_drill-p15c-f1-record.jsonl:1 |
| 02:58:51.187Z to 02:58:51.764Z | list-tasks returns exactly one task, `48763108...13f6f` (rc 0). | F/failure_drill-p15c-f1-record.jsonl:2 |
| 02:58:51.764Z | Stop issued (`action_begin`). | F/failure_drill-p15c-f1-record.jsonl:3 |
| 02:58:52.222Z | Task `stoppingAt`. | F/failure_drill-p15c-f1-record.jsonl:4 |
| 02:58:52.287Z / 02:58:52.288Z | Stop call returned (rc 0); drill record ends with exit_code 0. | F/failure_drill-p15c-f1-record.jsonl:4,5 |
| 02:58:54.308Z | Replacement task PROVISIONING; ECS event "has started 1 tasks" 02:58:54.348Z. | F/derived-exp-f-summary.json:27; F/ecs-describe-services-retrieval-final.json |
| 02:58:54Z | During-gap `/healthz` probe, HTTP 200. This reaches query-api, not retrieval. | F/healthz-probes.txt:3 |
| 02:59:00.692Z and 02:59:15.697Z | Samples: retrieval desired 1, running 0, pending 1. query-api 1/1. | F/sample_resources-p15c-f1-samples.jsonl:3,4 |
| 02:59:24.690Z | Replacement RUNNING (`startedAt`, derived summary). | F/derived-exp-f-summary.json:25 |
| 02:59:30.702Z | Sample: retrieval running 1, pending 0. | F/sample_resources-p15c-f1-samples.jsonl:5 |
| 02:59:31.822Z | ECS event "has reached a steady state". | F/ecs-describe-services-retrieval-final.json |
| 02:59:35Z | After `/healthz` probe, HTTP 200 (approximate shell clock). | F/healthz-probes.txt:4 |
| 02:59:38.405Z | Old task STOPPED (`stoppedAt`). | F/describe-tasks.json |
| 03:04:00.771Z | Last of 23 samples. The sampler ended there, short of the planned 1200 s. Collector window 02:50:00Z to 03:05:00Z. | F/sample_resources-p15c-f1-samples.jsonl:23; F/collect_cloudwatch-p15c-f1.meta.json:38-42; F/derived-exp-f-summary.json:8 |

Recovery intervals, from the timestamps above:
- 32.9 s from stop issued (02:58:51.764Z) to replacement RUNNING (02:59:24.690Z).
- 40.1 s from stop issued to the ECS steady-state event (02:59:31.822Z).
- 2.5 s from stop issued to the replacement being created.
- Matches F/derived-exp-f-summary.json:16-21.

The manifest stop condition "worker not recovered 15 minutes after the failure" was not approached: recovery took under a minute. (MAN:85)

## 3. Evidence

- Exactly one stop action was issued and none repeated: the record has one `action_begin` for stop-one-worker-task, both actions rc 0. (F/failure_drill-p15c-f1-record.jsonl:3-4)
- The run was started through `run-experiment.sh` with `failure_drill.py --action stop-task --cluster-name scholarchain-cluster --service retrieval --execute --yes`, and the script reported success. (F/run-experiment-console.log:3-5)
- Final service state: retrieval ACTIVE, desired 1, running 1, task definition `:15`, no load balancer attached, healthCheckGracePeriodSeconds 0. (F/ecs-describe-services-retrieval-final.json)
- The replacement task is the sole running task: `list-tasks-running` returns only `13296170...ffbb9cc`, and `list-tasks-stopped` returns only `48763108...13f6f`. (F/list-tasks-running.json; F/list-tasks-stopped.json)
- CloudWatch: retrieval CPU average max 12.0 percent, maximum 70.2 percent in the window; ALB RequestCount 2 (the two `/healthz` probes counted by the collector), no 5xx. This is quoted from the derived summary. The responder did not recompute it. (F/derived-exp-f-summary.json:2; F/collect_cloudwatch-p15c-f1-ecs.json, -alb.json)
- The two files `ecs-describe-services-retrieval.json` and `ecs-describe-services-retrieval-final.json` have equal size (23503 bytes) but `cmp` reports they differ at line 47. The timeline above uses the `-final` file. The difference was not analysed: [[UNKNOWN]].

## 4. Root cause

A deliberate `stop-task` for experiment F, run by the approved manifest. The manifest was approved by the human before the run (`approved: true`, `approved_by` the human). (MAN:7-8; AR:99) The target was the retrieval service. The manifest text still describes the embedding worker (see section 6).

## 5. Data-integrity assessment

- No data store was read or written by the drill. The only mutation was one ECS task stop. (F/failure_drill-p15c-f1-record.jsonl:3)
- The 2 messages already in the dead-letter queue (from Phase 14 finding F14-16) were not touched. (F/derived-exp-f-summary.json:5)
- The sampler recorded `"sqs": {}` and `"emr": {}` in every sample, so no queue or DLQ depth was measured during this run. (F/sample_resources-p15c-f1-samples.jsonl:1-23) DLQ depth before and after the drill: [[UNKNOWN]].
- Whether the retrieval task was handling an in-flight request when it was stopped: [[UNKNOWN]]. No `/v1/query` was sent by the experiment, and traffic from other sources was not checked.
- Exit code 143 (SIGTERM) shows the container was stopped by signal, not that it crashed. Whether it shut down cleanly: [[UNKNOWN]]. (F/describe-tasks.json)
- On this evidence no data loss or corruption is indicated, but the assessment is inferential. No repair or destructive action was performed or needed.

## 6. Disclosures

1. **DLQ stop condition waived.** The manifest stop condition "DLQ depth above 1 before the drill: stop (a prior failure exists)" was not applied. The human waived it for a stop-task on retrieval, on the grounds that it applies to the poison-message drill. The 2 DLQ messages from F14-16 were left untouched. Since the DLQ held messages before the drill and was not sampled, the condition was neither satisfied nor checked. (MAN:84; AR:103; F/derived-exp-f-summary.json:5; SUM:53)
2. **Title mismatch.** The manifest title "Failure and recovery of the embedding worker" and its purpose text still say embedding worker, but the action stopped a retrieval task. The human decided to leave the title. (MAN:5-6; F/derived-exp-f-summary.json:4; AR:157-158; SUM:52)
3. **Raw record redacted in place.** The write-once drill record held the account ID in task ARNs and was redacted to `<acct>` before any commit. This is an exception to write-once raw files. The redaction note originally also held a full hash of the unredacted record. Under repair R1 that hash was removed by human decision (see section 10), so the note now says `removed: hash of unredacted original`. The note still holds the hash of the stored redacted file, so the stored file can be verified. (F/redaction-note-failure-drill-record.json:3-5; `experiments/results/aws/REDACTION-HASH-REPAIR.json`; SUM:47; AR:153)
4. **Sampler shortened.** The sampler stopped after 23 samples at 03:04:00Z, short of the planned 1200 s. (F/derived-exp-f-summary.json:8; SUM:49)
5. **Single failure, single run.** One failure, no repetition. A recovery time of 32.9 s is one observation, not a distribution. (F/derived-exp-f-summary.json:7; SUM:44)
6. **Time box overrun.** The phase 75-minute time box had passed when F ran, by human decision. (AR:102)

## 7. Preventive tests and controls

No test or control was added for this event, and none is evidenced as in place for retrieval health.

Gap evidenced: retrieval has no ALB target group and no container health check, so ECS reports its `healthStatus` as UNKNOWN. The "healthy" criterion in this drill was therefore only RUNNING plus the ECS steady-state event. (F/derived-exp-f-summary.json:12; F/describe-tasks.json `healthStatus`; F/ecs-describe-services-retrieval-final.json `loadBalancers: []`; SUM:30)

| Control | State |
|---|---|
| Add a container health check (for example a readiness probe of the retrieval process) to the retrieval task definition, so ECS reports HEALTHY or UNHEALTHY and a recovery can be shown as healthy, not just RUNNING. | PROPOSED |
| Add an alarm on retrieval running task count below desired for more than a set period (needs Container Insights, which is off by default per `infra/terraform/INFRA-CONTRACTS.md:239`, or an ECS service event rule on stopped tasks). | PROPOSED |
| Repeat the drill with a `/v1/query` probe loop through query-api to the retrieval path during the gap, using a budgeted model-free or stubbed endpoint if one exists, so user impact is measured. | PROPOSED |
| Sample queue and DLQ depth in any future stop-task drill (the sampler recorded empty `sqs`), and record the DLQ state before and after. | PROPOSED |
| Settle the stop-condition wording in the manifest so the DLQ condition is scoped to the poison-message action, and fix the title and purpose to name the actual target. | PROPOSED |

## 8. Follow-up owners (docs/agents.md section 1)

- `infra-engineer`: the container health check and alarm (Terraform under `infra/terraform/**`).
- `experiment-engineer`: the repeat drill with user-impact measurement and the manifest wording (`experiments/**`).
- `deploy-engineer`: any release record if the task definition changes (`reports/deployments/**`).

## 9. Escalations

None required. No schema change, data repair, secret rotation, security change or destructive action was involved or recommended.

## 10. Corrections

- **Correction 1 (earlier wording was wrong).** An earlier version of disclosure 3 said the original sha256 in the redaction note was "partially masked". That was wrong. The note held a full hash of the unredacted record. I misread it because the redaction command had also altered digits inside the value when I viewed it with the account-ID filter applied. Review finding M2 corrected this.
- **Correction 2 (hash removed, repair R1).** A hash of a record that contained the account ID lets someone test guesses of the ID offline (a hash-oracle risk). Dhruv ordered such hashes removed. In repair R1 the field `sha256_original_unredacted` in `experiments/results/exp-f-failure-recovery/p15c-f1/redaction-note-failure-drill-record.json` was replaced with the text `removed: hash of unredacted original`. The repair list, which holds no old hash values, is `experiments/results/aws/REDACTION-HASH-REPAIR.json` (6 changes, "decided by Dhruv (Repair R1)"). This record contains no hash of an unredacted original and no account ID.
- **Open point on collector hashes.** Disclosure 3 earlier said the sha256 values in the collector `collect_cloudwatch-p15c-f1.meta.json` are of unredacted originals, following SUM:47. REDACTION-HASH-REPAIR.json lists no change to that F meta file, so whether those hashes ever covered unredacted files is [[UNKNOWN]]. This record no longer asserts it.
