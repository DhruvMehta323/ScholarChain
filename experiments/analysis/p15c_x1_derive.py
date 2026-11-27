#!/usr/bin/env python3
"""Phase 15c work order X1: derive tables for experiments A, B and C from the raw files already on disk.

Reads only files under experiments/results/{exp-a-baseline-10k/p15c-a1, exp-b-burst-50k/p15c-b1,
exp-c-embedding-backlog/p15c-c1}. Writes NEW derived-*.json files next to them (exclusive create, never
overwrites). Standard library only. No AWS call. Run: python3 -B experiments/analysis/p15c_x1_derive.py

Definitions
  step duration     EMR step Timeline StartDateTime to EndDateTime (list-steps).
  job duration      the .jhist file name in the EMR logs listing carries
                    job_<id>-<submit ms>-<user>-<name>-<finish ms>-<maps>-<reduces>-<STATUS>-<queue>-<launch ms>.
                    duration = finish - submit (ms). maps/reduces are the task counts in that name.
  node-hours        sum over EC2 instances of (EndDateTime - CreationDateTime) of list-instances, in hours.
  cost estimate     node-hours x USD 0.2537671233 per node-hour (infra/terraform/INFRA-CONTRACTS.md section 10:
                    EC2 m5.xlarge 0.192 + EMR 0.048 + EBS, table row "Phase 15 10K pipeline"). An ESTIMATE: it is
                    not read from the bill.
  worker count      from application-autoscaling scaling activities (desired count) and ECS service events.
"""
import json
import os
import re
import sys
import urllib.parse
from datetime import datetime, timezone

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "results")
A = os.path.join(ROOT, "exp-a-baseline-10k", "p15c-a1")
B = os.path.join(ROOT, "exp-b-burst-50k", "p15c-b1")
C = os.path.join(ROOT, "exp-c-embedding-backlog", "p15c-c1")
NODE_HOUR_USD = 0.2537671233


def utc(s):
    return datetime.fromisoformat(s).astimezone(timezone.utc)


def iso(d):
    return d.strftime("%Y-%m-%dT%H:%M:%SZ")


def load(p):
    with open(p) as f:
        return json.load(f)


def emit(path, obj):
    with open(path, "x") as f:  # exclusive create: refuses to overwrite
        json.dump(obj, f, indent=2, sort_keys=True)
        f.write("\n")
    os.chmod(path, 0o444)
    print("WROTE", os.path.relpath(path))


def node_hours(instances):
    rows, tot = [], 0.0
    for i in instances:
        t = i["Status"]["Timeline"]
        s, e = utc(t["CreationDateTime"]), utc(t["EndDateTime"])
        h = (e - s).total_seconds() / 3600
        tot += h
        rows.append({"ec2": i["Ec2InstanceId"], "group": i["InstanceGroupId"], "created": iso(s),
                     "ready": iso(utc(t["ReadyDateTime"])), "ended": iso(e), "hours": round(h, 4)})
    return rows, tot


def exp_a():
    steps = load(f"{A}/collect_emr-p15c-a1-steps.json")["Steps"]
    cluster = load(f"{A}/collect_emr-p15c-a1-cluster.json")["Cluster"]
    inst = load(f"{A}/collect_emr-p15c-a1-instances.json")["Instances"]
    step_rows = []
    for s in steps:
        t = s["Status"]["Timeline"]
        st, en = t.get("StartDateTime"), t.get("EndDateTime")
        step_rows.append({"name": s["Name"], "state": s["Status"]["State"], "start": iso(utc(st)) if st else None,
                          "end": iso(utc(en)) if en else None,
                          "seconds": round((utc(en) - utc(st)).total_seconds(), 1) if st and en else None,
                          "failure": s["Status"].get("FailureDetails", {}).get("Reason")})
    jobs = []
    for line in open(f"{A}/emr-logs-listing.txt"):
        m = re.search(r"/(job_\d+_\d+)-(\d+)-hadoop-(.+?)-(\d+)-(\d+)-(\d+)-(\w+)-root\.default-(\d+)\.jhist\.gz", line)
        if m:
            name = urllib.parse.unquote(m.group(3))
            sub, fin = int(m.group(2)), int(m.group(4))
            jobs.append({"job": m.group(1), "name": name, "submit": iso(datetime.fromtimestamp(sub / 1000, timezone.utc)),
                         "finish": iso(datetime.fromtimestamp(fin / 1000, timezone.utc)),
                         "seconds": round((fin - sub) / 1000, 1), "maps": int(m.group(5)),
                         "reduces": int(m.group(6)), "status": m.group(7)})
    jobs.sort(key=lambda j: j["submit"])
    counters = load(f"{A}/counters.json")
    rows, tot = node_hours(inst)
    ng = {}
    for g in cluster["InstanceGroups"]:
        ng[g["Name"]] = {"type": g["InstanceType"], "requested": g["RequestedInstanceCount"]}
    task_total_maps = sum(j["maps"] for j in jobs)
    task_total_reds = sum(j["reduces"] for j in jobs)
    out = {
        "kind": "DERIVED (not raw); sources are the raw files in this folder",
        "label": "REAL", "cluster_id": cluster["Id"], "state": cluster["Status"]["State"],
        "state_reason": cluster["Status"]["StateChangeReason"],
        "cluster_created_utc": iso(utc(cluster["Status"]["Timeline"]["CreationDateTime"])),
        "cluster_ready_utc": iso(utc(cluster["Status"]["Timeline"]["ReadyDateTime"])),
        "cluster_ended_utc": iso(utc(cluster["Status"]["Timeline"]["EndDateTime"])),
        "normalized_instance_hours_reported_by_emr": cluster["NormalizedInstanceHours"],
        "emr_steps": step_rows,
        "mapreduce_jobs_from_jhist_filenames": jobs,
        "mapreduce_jobs_found": len(jobs),
        "tasks_total_from_listed_jhist": {"maps": task_total_maps, "reduces": task_total_reds},
        "hadoop_counters_source": "counters.json (the pipeline's own run counters, per job)",
        "hadoop_job_counters": {k: v["counters"] for k, v in counters["jobs"].items()},
        "hadoop_job_rows": {k: v["rows"] for k, v in counters["jobs"].items()},
        "hadoop_job_rejects": {k: v["rejects"] for k, v in counters["jobs"].items()},
        "failures": [s for s in step_rows if s["state"] != "COMPLETED"],
        "instance_groups_requested": ng,
        "instances": rows,
        "instance_hours": round(tot, 4),
        "cost_estimate": {
            "label": "ESTIMATE, not read from the bill",
            "formula": "instance_hours x node_hour_usd",
            "node_hour_usd": NODE_HOUR_USD,
            "price_source": "infra/terraform/INFRA-CONTRACTS.md section 10 (EC2 m5.xlarge 0.192 + EMR 0.048 per hour + EBS; "
                            "price table marked verified AmazonEC2 2026-11-23, ElasticMapReduce 2026-11-17)",
            "usd": round(tot * NODE_HOUR_USD, 4),
            "note": "includes the Spark step that failed after 38 s and the idle tail until shutdown"},
        "notes": [
            "HJ5 has no _SUCCESS marker of its own under work/; it writes curated/ (success-times.txt).",
            "The jhist listing is what the EMR log pusher had uploaded; jobs missing from it are not counted here.",
            "run.json for this run is stale at status running (Phase 14 F14-8); the EMR step records are the authority.",
        ],
    }
    emit(f"{A}/derived-exp-a-summary.json", out)


def exp_b():
    cl = load(f"{B}/collect_emr-p15c-b1-cluster.json")["Cluster"]
    inst = load(f"{B}/collect_emr-p15c-b1-instances.json")["Instances"]
    pol = load(f"{B}/managed-scaling-policy.json")["ManagedScalingPolicy"]["ComputeLimits"]
    cw = load(f"{B}/collect_cloudwatch-p15c-b1-emr.json")["MetricDataResults"]
    series = {}
    for r in cw:
        for t, v in zip(r["Timestamps"], r["Values"]):
            series.setdefault(t, {})[r["Label"] if r.get("Label") else r["Id"]] = round(v, 3)
    tl = [{"t": t, **series[t]} for t in sorted(series)]
    events = []
    for i in inst:
        t = i["Status"]["Timeline"]
        events.append({"t": iso(utc(t["CreationDateTime"])), "event": "instance requested/created", "ec2": i["Ec2InstanceId"], "group": i["InstanceGroupId"]})
        events.append({"t": iso(utc(t["ReadyDateTime"])), "event": "instance ready", "ec2": i["Ec2InstanceId"], "group": i["InstanceGroupId"]})
        events.append({"t": iso(utc(t["EndDateTime"])), "event": "instance ended", "ec2": i["Ec2InstanceId"], "group": i["InstanceGroupId"]})
    events.sort(key=lambda e: e["t"])
    running = 0
    for e in events:
        if e["event"] == "instance ready":
            running += 1
        elif e["event"] == "instance ended":
            running -= 1
        e["running_after"] = running
    rows, tot = node_hours(inst)
    out = {
        "kind": "DERIVED (not raw)", "label": "REAL",
        "disclosures": [
            "There was no separate burst run and no 50K run. This is the capacity timeline of the single 10K cluster.",
            "Spark did NOT run on EMR for this run: the EMR Spark step failed after 38 s (Java 8, F14-8); Spark ran locally "
            "(Phase 14 agent-report.md). No Spark-elasticity claim can be made.",
            "EMR does not keep a cluster event log through the API after termination; the timeline is rebuilt from "
            "list-instances timestamps, describe-cluster instance groups and 5-minute CloudWatch EMR metrics.",
            "aws emr list-instance-groups is not a valid subcommand in the installed CLI (collector bug, rc=252, file kept)."],
        "managed_scaling_policy_requested": pol,
        "instance_groups_requested_at_creation": [{"name": g["Name"], "requested": g["RequestedInstanceCount"], "running_at_end": g["RunningInstanceCount"]} for g in cl["InstanceGroups"]],
        "capacity_events_utc": events,
        "peak_running_instances": max(e["running_after"] for e in events),
        "instances": rows, "instance_hours": round(tot, 4),
        "released": "all 3 instances ended at the same instant as the cluster (EndDateTime), reason: cluster shut down after step failure",
        "cloudwatch_emr_5min_points": tl,
    }
    emit(f"{B}/derived-exp-b-capacity-timeline.json", out)


def exp_c():
    act = load(f"{C}/appautoscaling-scaling-activities-embedding-worker.json")["ScalingActivities"]
    act = sorted(act, key=lambda a: a["StartTime"])
    acts = [{"start_utc": iso(utc(a["StartTime"])), "end_utc": iso(utc(a["EndTime"])), "status": a["StatusCode"],
             "description": a["Description"], "cause": re.sub(r"TargetTracking-[^ ]*?-(AlarmHigh|AlarmLow)-[0-9a-f-]+", r"\1", a["Cause"])} for a in act]
    sqs = load(f"{C}/collect_cloudwatch-p15c-c1-sqs.json")["MetricDataResults"]
    ecs = load(f"{C}/collect_cloudwatch-p15c-c1-ecs.json")["MetricDataResults"]
    pts = {}
    for r in sqs:
        for t, v in zip(r["Timestamps"], r["Values"]):
            pts.setdefault(t, {})[r["Label"]] = v
    for r in ecs:
        if r["Label"].startswith("embedding-worker"):
            for t, v in zip(r["Timestamps"], r["Values"]):
                pts.setdefault(t, {})[r["Label"]] = round(v, 2)
    tl = [{"t": t, **pts[t]} for t in sorted(pts)]
    vis = [(p["t"], p["queue ApproximateNumberOfMessagesVisible"], p["queue ApproximateNumberOfMessagesNotVisible"]) for p in tl]
    first_msgs = next(t for t, v, n in vis if v > 0)
    drained = next(t for t, v, n in vis if t > first_msgs and v == 0 and n == 0)
    peak_vis = max(vis, key=lambda x: x[1])
    sent = sum(p.get("queue NumberOfMessagesSent", 0) for p in tl)
    recv = sum(p.get("queue NumberOfMessagesReceived", 0) for p in tl)
    dele = sum(p.get("queue NumberOfMessagesDeleted", 0) for p in tl)
    dlq_sent = sum(p.get("dlq NumberOfMessagesSent", 0) for p in tl)
    emb = load(f"{C}/source-pipeline-s4-embeddings.json")
    # desired-count timeline from the scaling activities (initial 1 from 2026-11-26T21:29Z)
    desired = [(a["start_utc"], int(re.search(r"to (\d+)", a["description"]).group(1))) for a in acts]
    out_events = [a for a in desired if a[0] >= "2026-11-26T22:05:00Z"]
    max_desired = max(d for t, d in out_events)
    out = {
        "kind": "DERIVED (not raw)", "label": "REAL",
        "window_utc": ["2026-11-26T22:05:00Z", "2026-11-26T23:05:00Z"],
        "scaling_activities_embedding_worker": acts,
        "desired_count_changes_in_window": [{"t": t, "desired": d} for t, d in out_events],
        "scaled_above_1": max_desired > 1, "max_desired_count": max_desired,
        "running_count_evidence": "ECS describe-services events: 4 tasks started (1 by the 21:29Z min=1 change that remained, "
                                  "1 at 22:15:55Z, 2 at 22:17:57Z); 'reached a steady state' 22:18:25Z (local 16:18:25-06:00). "
                                  "Container Insights is off, so RunningTaskCount is NOT a CloudWatch metric here.",
        "queue_first_nonzero_visible_utc": first_msgs, "queue_peak_visible": {"t": peak_vis[0], "visible": peak_vis[1]},
        "queue_drained_utc_cloudwatch_first_zero": drained,
        "wait_script_record": {"waited_seconds": emb["waited_seconds"], "finished_at": emb["finished_at"], "dlq_messages": emb["dlq_messages"], "status": emb["status"]},
        "messages_sent_sum": sent, "messages_received_sum": recv, "messages_deleted_sum": dele, "dlq_sent_sum": dlq_sent,
        "retries_estimate_received_minus_deleted": recv - dele,
        "retry_note": "received minus deleted counts receives that were not acknowledged (failed batches, visibility timeouts, DLQ moves). "
                      "Phase 14 worker logs say 54 retried, 2 dead-lettered, 258 batches, 9,968 vectors written; not re-derived here.",
        "throughput_jobs_per_s_over_wait": round(204 / emb["waited_seconds"], 3),
        "throughput_vectors_per_s_over_wait": round(9968 / emb["waited_seconds"], 2),
        "throughput_note": "204 jobs enqueued (ops-manifest jobsEnqueued); 9,968 vectors written (Phase 14 agent-report.md, S3 Vectors list-vectors). "
                           "Divided by the wait-embeddings waited_seconds.",
        "cloudwatch_1min_points": tl,
    }
    emit(f"{C}/derived-exp-c-backlog.json", out)


if __name__ == "__main__":
    for fn in (exp_a, exp_b, exp_c):
        try:
            fn()
        except FileExistsError as e:
            print("REFUSED overwrite:", e)
            sys.exit(3)
