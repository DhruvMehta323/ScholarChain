# Phase 15c AWS experiments: SUMMARY v2 (supersedes the draft `aws/SUMMARY.md`, which stays as written)

Written by experiment-engineer (work orders X1 to X5). Paths are relative to `experiments/results/`. A = `exp-a-baseline-10k/p15c-a1/`, B = `exp-b-burst-50k/p15c-b1/`, C = `exp-c-embedding-backlog/p15c-c1/`, D = `exp-d-query-load/p15c-d1/`, F = `exp-f-failure-recovery/p15c-f1/`. Times are UTC (raw files carry -06:00; derived files convert). All data are REAL AWS data from the Phase 14 stack and the 10K run `r20261125T214348Z-63958`. Retained details for A, B and C (per-job tables, failed-run table) are in `aws/SUMMARY.md` and the derived files named below; the figures here are repeated with their sources.

## A. Hadoop baseline, 10K (collect only)
- Cluster j-0072517WQADTWOIR2LO: created 21:45:31Z, ended 22:07:07Z, TERMINATED_WITH_ERRORS ("Shut down as step failed"). Step HJ1-HJ7 COMPLETED 21:49:46Z to 22:05:24Z = 938.2 s; Spark step FAILED after 38.1 s (Java 8, F14-8) (A/collect_emr-p15c-a1-steps.json, A/derived-exp-a-summary.json).
- 11 MapReduce jobs SUCCEEDED, 858.6 s total (91.5 percent of the step), 53 maps and 21 reduces (A/emr-logs-listing.txt, A/derived-exp-a-correction2.json). Counters: A/counters.json.
- Instance hours 0.9535 (A/collect_emr-p15c-a1-instances.json). Cost ESTIMATE: 0.9535 h x $0.2537671233 per node-hour = $0.242 (upper bound $0.761 if billed per started hour); price source infra/terraform/INFRA-CONTRACTS.md section 10 (A/derived-exp-a-summary.json, derived-exp-a-correction1.json). Not read from the bill.
- Failed earlier 10K attempts kept: `…79065` (C5, hadoop jar given an s3:// URI, F14-5), `…43602` (C6, class name passed twice, F14-6, plus 5 of 9 enrichment objects, F14-7), `…3950` (C7, enrichment/crossref missing, F14-7); records A/source-pipeline-<run>-run.json.

## B. EMR capacity timeline (collect only)
- No separate burst run and no 50K run: the folder name is the manifest id and is misleading. Spark did not complete on EMR (it ran locally).
- Requested: managed scaling min 1, max 2 instances, max core 2 (B/managed-scaling-policy.json); CloudWatch TotalNodesRequested 1.0, 1.5 at 21:52Z, 2.0 at 21:53Z (B/collect_cloudwatch-p15c-b1-emr.json). Provisioned: third EC2 created 21:52:50Z, ready 21:54:54Z, peak 3 EC2 (B/derived-exp-b-capacity-timeline.json). Released: all 3 instances ended at 22:07:07Z at cluster shutdown; no managed-scaling scale-in before termination (B/derived-exp-b-correction1.json).

## C. Embedding backlog (CloudWatch part only)
- Window 22:05Z to 23:05Z, 2026-11-26. Backlog first visible 22:11:00Z, peak 203, empty from 22:31:00Z (about 19.3 min from the wait start at 22:11:45Z to empty; first nonzero 22:11:00Z to first zero 22:31:00Z is 20.0 min, see C/derived-exp-c-backlog-correction2.json); wait-embeddings drained 22:35:40Z, waited 1,435 s (C/collect_cloudwatch-p15c-c1-sqs.json, C/source-pipeline-s4-embeddings.json, C/derived-exp-c-backlog-correction1.json). DLQ 2 messages (F14-16). 258 sends against 204 enqueued, matching the 54 retried in the Phase 14 worker logs (inference).
- Throughput 0.142 jobs/s (204 jobs) and 6.95 vectors/s (9,968 vectors) over the 1,435 s wait (C/derived-exp-c-backlog.json).
- Scale-out above 1 task: YES, embedding-worker desired 1 to 2 at 22:15:51Z and 2 to 4 at 22:17:51Z; scale-in 4 to 3 at 22:43:33Z, 3 to 2 at 22:49:33Z, 2 to 1 at 22:55:33Z (C/appautoscaling-scaling-activities-embedding-worker.json, C/ecs-describe-services-embedding-worker.json).
- No fresh enqueue was done.

## D. Query load (run p15c-d1, manifest approved by Dhruv, commit 36b19d1)
- 40 requests (16 at c5, 24 at c20), all HTTP 200, error rate 0. c5: p50 4,905.5 ms, p95 10,277.1, p99 10,277.1, 0.802 req/s. c20: p50 7,884.3 ms, p95 15,085.6, p99 15,137.0, 1.241 req/s (aws/analysis/d1/latency-by-concurrency.csv, from D/loadgen-p15c-d1-c5-fused.csv and -c20-fused.csv). Both levels ended on the request cap; the load took about 40 s (D/run-experiment-console.log).
- The phase cap of "40 query calls" was applied as 40 /v1/query requests, per the human-approved D manifest (max_requests 40, max_model_calls 120); 75 model calls were observed (explain counters in the D loadgen CSVs).
- Observed model calls 75 against 120 assumed (D/loadgen CSVs). Mean explain stage latency: RETRIEVAL 2,711 ms (c5) and 1,997 ms (c20), ANSWER_SYNTHESIS 312 and 262, PLANNING 187 and 186 (aws/analysis/d1/derived-exp-d-summary.json).
- No scale-out and no scale-in for query-api or retrieval: desired and running stayed 1 in all 43 sampler samples (aws/analysis/d1/scale-summary.csv, D/sample_resources-p15c-d1-samples.jsonl); no scaling activities (D/appautoscaling-or-ecs-scaling-activities-*.json).
- Observed against the targets (D/appautoscaling-or-ecs-scaling-policies-*.json: query-api CPU 60 and 200 ALBRequestCountPerTarget; retrieval CPU 60; cooldowns 60 s out, 120 s in): ALB RequestCount peaked at 58 in one minute; query-api CPU average peaked at 33.8 percent (maximum 100 percent for one minute); retrieval CPU average peaked at 19.8 percent (D/collect_cloudwatch-p15c-d1-alb.json, -ecs.json). ALB 5xx zero, Neptune CPU max 30.8 percent, no DynamoDB throttles (D/collect_cloudwatch-p15c-d1-neptune.json, -dynamodb.json). The load was too short for target-tracking alarms; no traffic was added beyond the manifest.

## F. Failure and recovery (run p15c-f1, one stop-task on retrieval)
- One running retrieval task (id ending 10f13f6f) was stopped once; ECS replaced it. Stop issued 02:58:51.764Z; replacement created (PROVISIONING) 02:58:54.308Z; replacement RUNNING 02:59:24.690Z; service "reached a steady state" 02:59:31.822Z; old task STOPPED 02:59:38.405Z (F/failure_drill-p15c-f1-record.jsonl, F/describe-tasks.json, F/ecs-describe-services-retrieval.json).
- Recovery: 32.9 s from stop to replacement RUNNING, 40.1 s to the ECS steady-state event (F/derived-exp-f-summary.json). Sampler: retrieval running 0 / pending 1 at 02:59:00Z and 02:59:15Z, running 1 from 02:59:30Z (F/sample_resources-p15c-f1-samples.jsonl).
- "Healthy in the target group" cannot be shown: retrieval has no ALB target group and reports healthStatus UNKNOWN. /healthz probes (before, during, after) returned 200 but go to query-api, so they do not test retrieval (F/healthz-probes.txt, operator notes with approximate times; see F/healthz-probes-NOTE.json). User impact during the gap is not measured: no /v1/query was sent (Bedrock budget used up).
- Not collected, although listed in the F manifest metrics: SQS main-queue and DLQ depth (the sampler records "sqs": {} because no queue URL was passed) and the embedding-worker logs. The F evidence is ECS and CloudWatch only.
- The real earlier failure of this phase, the Phase 14 C4 image-permission rollback, is covered by the incident report INC-001 under docs/incidents/.

## Scale-out and scale-in statement
- Shown in C, with timestamps: embedding-worker 1 to 2 at 22:15:51Z, 2 to 4 at 22:17:51Z, 4 to 3 at 22:43:33Z, 3 to 2 at 22:49:33Z, 2 to 1 at 22:55:33Z (C/appautoscaling-scaling-activities-embedding-worker.json). Also EMR core nodes 1 to 2 in B (no scale-in before shutdown).
- Not triggered in D: the values observed against the policy targets are given in section D.

## Cost
- Month-to-date Cost Explorer, before credits, 2026-11-01 to 11-27: $10.5971 at the start (cost/p15c-start/...), after X1 (cost/p15c-x1-end/...), after D (cost/p15c-after-d/...) and after F (cost/p15c-after-f/...): all four raw files give the same total (Cost Explorer lags by many hours, so these readings do not include the D and F activity). Under the $15 stop. The Phase 14 report quotes about $9 actual spend. By day: 2026-11-25 $6.15, 2026-11-26 $4.45 (cost/p15c-x1-end/collect_cost-p15c-x1-end-daily-by-service.json).
- Per-experiment cost cannot be measured: daily Cost Explorer data cannot isolate runs and has not caught up. Estimates only: A $0.242 (up to $0.761), computed above. D model spend $0.8404 estimate at the assumed 3 calls per request (loadgen meta, D/loadgen-p15c-d1-c5-fused.meta.json and -c20-fused.meta.json; ledger model-spend-ledger.jsonl); with the 75 observed calls instead of 120 it would be lower. B, C and F have no measured cost; no model spend in A, B, C or F. `aws/analysis/cost-per-experiment.csv` shows no measured cost per experiment (aws/analysis/ANALYSIS-NOTES.json).

## Limitations
- No 50K run and no burst run; B is the capacity timeline of one 10K cluster.
- Spark ran locally, not on EMR (the EMR Spark step failed); no Spark-elasticity claim.
- Short durations: D load about 40 s, F recovery under a minute, one 16-minute Hadoop step, a 20-minute drain; single runs, no repetition.
- Small corpus: 10K DBLP records, 9,968 of 10,000 vectors.
- Weak percentiles: n=16 and n=24, p95 and p99 sit at or near the maximum.
- Redaction: the account ID was replaced by `<acct>` in several raw files (and the D log '@ptr' fields were removed), which are disclosed exceptions to write-once (D/redaction-note-collect-logs-ptr.json, F/redaction-note-failure-drill-record.json, aws/REDACTION-HASH-REPAIR.json). After Repair R1 the collector meta sha256 values match the stored files; the earlier draft aws/SUMMARY.md still states the old mismatch and is superseded on this point.
- The collect_emr.py bug: it calls `aws emr list-instance-groups`, rejected by the installed CLI (rc=252); `*-instance-groups.ERROR` files are kept.
- The D sampler was stopped early (43 samples, not the full 1,500 s) after the idle period; the F sampler stopped after 23 samples at 03:04Z.
- No sampler data exist for the historical A, B and C windows; the 15b emr_capacity, queue_drain and scale_timeline scripts were not applicable there (aws/analysis/ANALYSIS-NOTES.json) and derived helper output was used.
- The phase time box overran by Dhruv's decision, who also kept the $15 cost stop.
- F title mismatch: the F manifest title and purpose notes still say "embedding worker" in places, while the action stopped a retrieval task (Dhruv decided to leave the title).
- The F stop condition "DLQ depth above 1 before the drill" was waived by Dhruv for stop-task on retrieval; the 2 DLQ messages (F14-16) were not touched.
- No retrieval health or user-impact measurement in F.

## Repair R1 (single repair round, decided by Dhruv)
- H1: the 183 '@ptr' fields were removed from D/collect_logs-p15c-d1-scholarchain_query-api-results.json (each decoded to text containing the account ID); nothing else changed in that file. Note: D/redaction-note-collect-logs-ptr.json. Disclosed write-once exception.
- M1: every sha256 that hashed an unredacted original was replaced by the sha256 of the stored redacted file, or removed (including `sha256_original_unredacted` in F/redaction-note-failure-drill-record.json); list in aws/REDACTION-HASH-REPAIR.json. Disclosed write-once exception. This also corrects the Limitations statement above: the collector meta sha256 values for the redacted files now match the stored files.
- L1: drain time relabelled (19.3 min from wait start, 20.0 min first nonzero to first zero); C/derived-exp-c-backlog-correction2.json.
- L3: F/healthz-probes.txt labelled as operator notes with approximate times; its "running 0 pending 1 at 02:58:54Z" claim is unsupported by any sample; F/healthz-probes-NOTE.json.
- L4: aws/analysis/ANALYSIS-NOTES-addendum.json records that cost-per-experiment.csv is stale (made before D, ids not manifest ids) and superseded by the Cost section above.
- L8: F section states that SQS and DLQ depth and embedding-worker logs were not collected.
- L9: D section states the 40-request application of the "40 query calls" cap and the 75 observed model calls.
