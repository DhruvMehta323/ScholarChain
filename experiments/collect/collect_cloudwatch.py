#!/usr/bin/env python3
"""Pull CloudWatch metrics for a time window with `aws cloudwatch get-metric-data` (read-only; Python standard library only).

Usage:
  python3 -B experiments/collect/collect_cloudwatch.py --experiment-id exp-d-query-load --run-id exp-d-r1 \
      --start 2026-11-30T14:00:00Z --end 2026-11-30T15:00:00Z --region us-east-2 \
      --groups ecs,sqs,alb,neptune,dynamodb,emr [--period 60] [--dry-run]
      --cluster-name C --services query-api,retrieval,embedding-worker         (group ecs; Terraform output ecs_cluster_name / ecs_service_names)
      --queue-name scholarchain-embedding-jobs --dlq-name scholarchain-embedding-jobs-dlq   (group sqs)
      --alb-arn-suffix app/.../... --target-group-arn-suffix targetgroup/.../...            (group alb)
      --neptune-cluster-id ID                                                               (group neptune)
      --dynamodb-tables QuerySession,EvidenceBundle,researcher-metrics (physical table names) (group dynamodb)
      --emr-cluster-id j-XXXX                                                                (group emr)
Metric names, namespaces and dimensions are the ones charted by infra/terraform/modules/observability/main.tf (dashboard and alarms), plus:
  AWS/ElasticMapReduce managed-scaling metrics (TotalNodesRequested, TotalNodesRunning, ...) whose names are NOT VERIFIED here (no network);
  a metric the account does not publish simply returns no datapoints, which the raw file shows.
  ECS desired/running task counts exist as metrics only with Container Insights (off by default): use sample_resources.py for them.
  There is NO custom CloudWatch namespace for API metrics in Terraform; API latency per request comes from collect_logs.py and the load
  generator CSV. Neptune query latency has no CloudWatch metric here: CPU and capacity are collected, per-stage latency comes from explain.
One raw JSON file per group, write-once, named <SYNTHETIC->collect_cloudwatch-<run>-<group>.json; a .meta.json lists every command.
Exit codes: 0 ok, 1 at least one call failed (kept as .ERROR file), 2 usage, 3 refused to overwrite.
"""
import json
import sys

import collect_common as cc
from awscli import Aws, require_region
from sc_common import parse_iso

DYNAMO_METRICS = ["ReadThrottleEvents", "WriteThrottleEvents", "UserErrors", "SystemErrors", "ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits"]


def q(idx, label, ns, name, dims, stat, period):
    return {"Id": "m%d" % idx, "Label": label,
            "MetricStat": {"Metric": {"Namespace": ns, "MetricName": name, "Dimensions": [{"Name": k, "Value": v} for k, v in dims]},
                           "Period": period, "Stat": stat}, "ReturnData": True}


def build(group, a):
    qs, p, i = [], a.period, 0

    def add(label, ns, name, dims, stat):
        nonlocal i
        i += 1
        qs.append(q(i, label, ns, name, dims, stat, p))
    if group == "ecs":
        if not (a.cluster_name and a.services):
            return None, "needs --cluster-name and --services"
        for s in a.services.split(","):
            for m in ("CPUUtilization", "MemoryUtilization"):
                for st in ("Average", "Maximum"):
                    add("%s %s %s" % (s, m, st), "AWS/ECS", m, [("ClusterName", a.cluster_name), ("ServiceName", s)], st)
            if a.container_insights:
                for m in ("DesiredTaskCount", "RunningTaskCount"):
                    add("%s %s" % (s, m), "ECS/ContainerInsights", m, [("ClusterName", a.cluster_name), ("ServiceName", s)], "Average")
    elif group == "sqs":
        if not (a.queue_name and a.dlq_name):
            return None, "needs --queue-name and --dlq-name"
        for label, qn in (("queue", a.queue_name), ("dlq", a.dlq_name)):
            for m, st in (("ApproximateNumberOfMessagesVisible", "Maximum"), ("ApproximateNumberOfMessagesNotVisible", "Maximum"),
                          ("ApproximateAgeOfOldestMessage", "Maximum"), ("NumberOfMessagesSent", "Sum"), ("NumberOfMessagesReceived", "Sum"),
                          ("NumberOfMessagesDeleted", "Sum")):
                add("%s %s" % (label, m), "AWS/SQS", m, [("QueueName", qn)], st)
    elif group == "alb":
        if not (a.alb_arn_suffix and a.target_group_arn_suffix):
            return None, "needs --alb-arn-suffix and --target-group-arn-suffix"
        lb = [("LoadBalancer", a.alb_arn_suffix)]
        tg = lb + [("TargetGroup", a.target_group_arn_suffix)]
        for st in ("p50", "p95", "p99", "Average"):
            add("TargetResponseTime %s" % st, "AWS/ApplicationELB", "TargetResponseTime", lb, st)
        for m in ("RequestCount", "HTTPCode_Target_2XX_Count", "HTTPCode_Target_4XX_Count", "HTTPCode_Target_5XX_Count", "HTTPCode_ELB_5XX_Count"):
            add(m, "AWS/ApplicationELB", m, lb, "Sum")
        for m in ("HealthyHostCount", "UnHealthyHostCount"):
            add(m, "AWS/ApplicationELB", m, tg, "Average")
    elif group == "neptune":
        if not a.neptune_cluster_id:
            return None, "needs --neptune-cluster-id"
        d = [("DBClusterIdentifier", a.neptune_cluster_id)]
        for m, st in (("ServerlessDatabaseCapacity", "Average"), ("CPUUtilization", "Average"), ("CPUUtilization", "Maximum"), ("NumTxRolledBack", "Sum")):
            add("%s %s" % (m, st), "AWS/Neptune", m, d, st)
    elif group == "dynamodb":
        if not a.dynamodb_tables:
            return None, "needs --dynamodb-tables"
        for t in a.dynamodb_tables.split(","):
            for m in DYNAMO_METRICS:
                add("%s %s" % (t, m), "AWS/DynamoDB", m, [("TableName", t)], "Sum")
            for op in ("GetItem", "Query", "PutItem"):
                add("%s SuccessfulRequestLatency %s" % (t, op), "AWS/DynamoDB", "SuccessfulRequestLatency", [("TableName", t), ("Operation", op)], "Average")
    elif group == "emr":
        if not a.emr_cluster_id:
            return None, "needs --emr-cluster-id"
        d = [("JobFlowId", a.emr_cluster_id)]
        for m in ("CoreNodesRunning", "CoreNodesPending", "YARNMemoryAvailablePercentage", "ContainerPending", "AppsRunning", "IsIdle",
                  "TotalNodesRequested", "TotalNodesRunning", "TotalUnitsRequested", "TotalUnitsRunning"):
            add(m, "AWS/ElasticMapReduce", m, d, "Average")
    else:
        return None, "unknown group"
    return qs, None


def main(argv):
    ap = cc.base_parser(__doc__.split("\n")[0])
    ap.add_argument("--start", required=True, help="ISO-8601 UTC, for example 2026-11-30T14:00:00Z")
    ap.add_argument("--end", required=True)
    ap.add_argument("--groups", default="ecs,sqs,alb,neptune,dynamodb,emr")
    ap.add_argument("--period", type=int, default=60)
    ap.add_argument("--cluster-name")
    ap.add_argument("--services")
    ap.add_argument("--container-insights", action="store_true", help="also request ECS/ContainerInsights task counts (the Terraform default is OFF)")
    ap.add_argument("--queue-name", default="scholarchain-embedding-jobs")
    ap.add_argument("--dlq-name", default="scholarchain-embedding-jobs-dlq")
    ap.add_argument("--alb-arn-suffix")
    ap.add_argument("--target-group-arn-suffix")
    ap.add_argument("--neptune-cluster-id")
    ap.add_argument("--dynamodb-tables")
    ap.add_argument("--emr-cluster-id")
    a = ap.parse_args(argv)
    try:
        t0, t1 = parse_iso(a.start), parse_iso(a.end)
    except ValueError:
        print("SC-REFUSED --start/--end must be ISO-8601", file=sys.stderr)
        return 2
    if t1 <= t0 or t1 - t0 > 6 * 3600:
        print("SC-REFUSED the window must be positive and at most 6 hours", file=sys.stderr)
        return 2
    if a.period < 60 or a.period % 60:
        print("SC-REFUSED --period must be a multiple of 60", file=sys.stderr)
        return 2
    aws = Aws(require_region(a.region), a.dry_run)
    sv = cc.Saver(a, "collect_cloudwatch", aws)
    skipped = []
    for g in [x for x in a.groups.split(",") if x]:
        qs, why = build(g, a)
        if qs is None:
            skipped.append({"group": g, "reason": why})
            print("SC-WARN group %s skipped: %s" % (g, why), file=sys.stderr)
            continue
        for n in range(0, len(qs), 100):  # get-metric-data accepts up to 500 queries; 100 keeps responses small
            chunk = qs[n:n + 100]
            ok, rc, out, err = aws.run("cloudwatch", "get-metric-data",
                                       ["--metric-data-queries", json.dumps(chunk), "--start-time", a.start, "--end-time", a.end])
            if not ok:
                continue
            name = "%s-%d" % (g, n // 100 + 1) if len(qs) > 100 else g
            if rc == 0:
                sv.save(name + ".json", out)
            else:
                sv.error(name, rc, err)
    return sv.finish({"window": {"start": a.start, "end": a.end, "period": a.period}, "skipped_groups": skipped})


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
