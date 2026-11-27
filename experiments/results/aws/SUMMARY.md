# Phase 15c AWS experiments: SUMMARY (DRAFT, written by experiment-engineer, work orders X1 to X3)

Paths are relative to `experiments/results/`. A = `exp-a-baseline-10k/p15c-a1/`, B = `exp-b-burst-50k/p15c-b1/`, C = `exp-c-embedding-backlog/p15c-c1/`. Times are UTC (raw files carry -06:00; derived files convert). Everything here is REAL AWS data from the Phase 14 10K run `r20261125T214348Z-63958`; nothing is synthetic. "Derived" means computed by `experiments/analysis/p15c_x1_derive.py` from the raw files.

## A. Hadoop baseline, 10K (collect only)
- Cluster j-0072517WQADTWOIR2LO (1 primary + core group, m5.xlarge, On-Demand), created 21:45:31Z, ready 21:49:45Z, ended 22:07:07Z, state TERMINATED_WITH_ERRORS "Shut down as step failed" (A/collect_emr-p15c-a1-cluster.json).
- Step hadoop-pipeline-HJ1-HJ7: COMPLETED, 21:49:46Z to 22:05:24Z = 938.2 s. Step spark-graph-analytics: FAILED after 38.1 s, "Unknown Error" (Java 8; Phase 14 F14-8) (A/collect_emr-p15c-a1-steps.json, A/derived-exp-a-summary.json).
- Per-job seconds (from .jhist file names in A/emr-logs-listing.txt; derived): HJ1 39.4, HJ2 120.0, HJ3 44.7, HJ4a 62.2, HJ4b 33.2, HJ5 110.9, HJ6a 36.7, HJ6b 26.3, HJ6c 140.1, HJ6d 118.5, HJ7 126.6. Total 858.6 s, 91.5 percent of the step (A/derived-exp-a-correction2.json).
- Task counts: 53 maps and 21 reduces over the 11 jobs (maps/reduces per job in A/derived-exp-a-summary.json). Failures: none among the 11 jobs.
- Hadoop counters: A/counters.json, for example HJ1 ACCEPTED 10,000; HJ4a EMITTED_PAIRS 82,862; HJ5 ACCEPTED 401,492. HJ6d output 920,620 candidates is a Phase 14 finding (F14-9), not a counter here.
- Instance hours 0.9535 (three EC2: 0.3577, 0.3577, 0.2381 h; A/collect_emr-p15c-a1-instances.json). EMR-reported NormalizedInstanceHours 24 (cluster.json).
- Cost ESTIMATE, not read from the bill: 0.9535 h x $0.2537671233 per node-hour = $0.242 (per-second basis). Price source: infra/terraform/INFRA-CONTRACTS.md section 10. If billed per started hour: 3 x 0.2537671233 = $0.761 upper bound (A/derived-exp-a-summary.json, derived-exp-a-correction1.json).
- CloudWatch EMR metrics for 21:40Z to 22:15Z: A/collect_cloudwatch-p15c-a1-emr.json.
- Failed earlier 10K attempts (kept; causes from docs/agent-runs/phase-14-deploy/agent-report.md; records A/source-pipeline-<run>-run.json and steps.json):
  | Run | Cluster | Code | Failure |
  |---|---|---|---|
  | r20261125T183800Z-79065 | j-000270910KZDB65AYWRX | C5 dc20d0fa23df | HJ step failed, ret 255 after 3 s: `hadoop jar` given an s3:// URI (F14-5) |
  | r20261125T202037Z-43602 | j-04871873UKQURPR2CNIQ | C6 a1af7b048639 | HJ step failed after 10 s, ret 2: class name passed twice, "odd number of arguments" (F14-6); also 5 of 9 enrichment objects present (F14-7) |
  | r20261125T212220Z-3950 | j-01130268UIH8G6H5ZEA | C7 8ce398029bf7 | HJ step failed: FileNotFoundException on enrichment/crossref, 2 of 9 objects present (F14-7 silent S3 upload loss) |
  In each, Spark was CANCELLED; run.json mislabels failed_step as spark-graph-analytics. "C7 attempt" is read as `…3950`.
- run.json of the successful run is stale at status "running" (F14-8); EMR step records are used instead.

## B. EMR capacity timeline (collect only)
- Disclosure: there was NO separate burst run and NO 50K run. The folder name `exp-b-burst-50k` is the manifest id and is misleading. This is the timeline of the single 10K cluster. Spark did not complete on EMR (failed after 38 s) and ran locally, so no Spark-elasticity claim is made.
- Requested: managed scaling policy min 1, max 2 instances, max core 2, On-Demand (B/managed-scaling-policy.json). CloudWatch TotalNodesRequested 1.0, then 1.5 at 21:52Z, 2.0 at 21:53Z (B/collect_cloudwatch-p15c-b1-emr.json).
- Provisioned: primary ready 21:49:10Z, first core 21:49:20Z, second core created 21:52:50Z and ready 21:54:54Z; peak 3 EC2 running (B/derived-exp-b-capacity-timeline.json, from B/collect_emr-p15c-b1-instances.json). CoreNodesRunning 2.0 from 21:56Z; ContainerPending peaked at 19 at 21:56Z. CloudWatch node counts exclude the primary.
- Released: all three instances ended together at 22:07:07Z at cluster shutdown after the failed Spark step. No managed-scaling scale-in happened before termination (B/derived-exp-b-correction1.json).
- No EMR cluster event log is available through the API after termination; the timeline is rebuilt from instance timestamps and 5-minute metrics. 15b `emr_capacity.py` was not applicable (needs sampler JSONL), see analysis/ANALYSIS-NOTES.json.

## C. Embedding backlog (CloudWatch part only)
- Window 22:05Z to 23:05Z on 2026-11-26. Backlog first visible 22:11:00Z, peak 203 visible, empty (visible 0, in-flight 0) from 22:31:00Z, about 19.3 min after the backlog appeared. wait-embeddings recorded drained at 22:35:40Z, waited 1,435 s including a 300 s quiet period (C/collect_cloudwatch-p15c-c1-sqs.json, C/source-pipeline-s4-embeddings.json, C/derived-exp-c-backlog-correction1.json).
- In-flight peaked at 4. DLQ: 2 messages (DLQ NumberOfMessagesSent sums to 2; F14-16, cause unknown).
- Throughput over the 1,435 s wait: 0.142 jobs/s (204 jobs, C/source-pipeline-s4-ops-manifest.json) and 6.95 vectors/s (9,968 vectors from Phase 14 report, not re-derived) (C/derived-exp-c-backlog.json).
- Retries: SQS NumberOfMessagesSent sums to 258 against 204 enqueued, 54 extra sends; this matches the "54 retried" in the Phase 14 worker logs (inference). Received 258, deleted 258.
- Scale-out above 1 task: YES. Scaling activities (C/appautoscaling-scaling-activities-embedding-worker.json): desired 1 to 2 at 22:15:51Z, 2 to 4 at 22:17:51Z (AlarmHigh of policy scholarchain-embedding-worker-backlog). ECS events: tasks started 22:15:55Z (1) and 22:17:57Z (2), steady state 22:18:25Z (C/ecs-describe-services-embedding-worker.json). First scale-out came about 4 min after the backlog appeared.
- Scale-in: 4 to 3 at 22:43:33Z, 3 to 2 at 22:49:33Z, 2 to 1 at 22:55:33Z (AlarmLow). Final desired 1, running 1 (min 1 from M1).
- Worker CPU average peaked at 40.0 percent and maximum 98.9 percent; memory average max 36.7 percent (C/collect_cloudwatch-p15c-c1-ecs.json).
- Running task count is not a CloudWatch metric (Container Insights off); counts come from scaling activities and ECS events. 15b `queue_drain.py` and `scale_timeline.py` were not applicable (need sampler JSONL).
- No fresh enqueue was done and none is needed.

## D. Query load
PENDING (manifest approval is a human edit; see agent-report)

## F. Failure and recovery
PENDING (manifest approval is a human edit; see agent-report)

## Cost
- Month-to-date Cost Explorer, before credits, 2026-11-01 to 11-27: $10.5971 at 00:11Z (cost/p15c-start/collect_cost-p15c-start-daily-by-service.json) and the same $10.5971 at 00:15Z (cost/p15c-x1-end/...; the two files are byte-identical). Under the $15 stop. Cost Explorer lags, so this understates spend; the Phase 14 report quotes about $9 actual.
- By day: 2026-11-25 $6.15, 2026-11-26 $4.45 (same file). By service month to date: Neptune $6.76, ELB $0.94, ECS $0.90, VPC $0.76, Route 53 $0.50, EC2 compute $0.36, Cost Explorer $0.17, Secrets Manager $0.09, EMR $0.05, S3 $0.03.
- Per-experiment cost is not measured: Cost Explorer daily data cannot isolate a run. The only per-experiment figure is the EMR estimate in section A ($0.242, up to $0.761). `analysis/cost-per-experiment.csv` shows NOT RUN / NO COST FILE for every row because the ids passed did not match the manifests (analysis/ANALYSIS-NOTES.json). Model spend: none in A, B or C; D has not run.

## Limitations
- No 50K run and no burst run; B is the capacity timeline of one 10K cluster.
- Spark ran locally, not on EMR (the EMR Spark step failed); nothing here supports a Spark-elasticity claim.
- Short durations: one 16-minute Hadoop step, a 20-minute drain; single runs, no repetition, no confidence intervals.
- Small corpus: 10K DBLP records, 9,968 of 10,000 vectors.
- Redaction: the 12-digit account ID appeared in ARNs, bucket names and LogUri, so files under A, B and C that contained it were written with `<acct>` replacing it. The sha256 values inside the collector `*.meta.json` files are of the original, unredacted outputs and do not match the stored files.
- The collector `collect_emr.py` calls `aws emr list-instance-groups`, which the installed CLI rejects (rc=252); the error file is kept (`*-instance-groups.ERROR`) and instance-group data comes from describe-cluster.
- Some A and C inputs (emr-logs-listing.txt, success-times.txt, counters.json, source-pipeline-*, ECS and autoscaling JSON) came from plain read-only AWS calls or local records, not from the 15b collectors.
- Job-level counts rest on .jhist file names the EMR log pusher had uploaded; the retry count in C is an inference.
- 15b analysis scripts for sampler data (emr_capacity, queue_drain, scale_timeline) were not run: no sampler data exists for A, B or C.
- D and F are not executed in this draft.
