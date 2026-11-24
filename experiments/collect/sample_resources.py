#!/usr/bin/env python3
"""Poll live resource state during an experiment window and append it to one write-once JSONL file (read-only AWS calls only).

Why: ECS desired/running task counts, EMR requested/running instance counts and the exact SQS depth are not retained as CloudWatch metrics
at this granularity in the Terraform configuration (Container Insights is off), so they are sampled live. Start it before the run, stop it
(Ctrl-C or the caps) after. Analysis: scale_timeline.py, queue_drain.py, emr_capacity.py.

Usage:
  python3 -B experiments/collect/sample_resources.py --experiment-id exp-c-embedding-backlog --run-id exp-c-r1 --region us-east-2 \
      --interval 15 --duration 3600 --max-samples 400 \
      [--cluster-name C --services query-api,retrieval,embedding-worker] [--queue-url URL --dlq-url URL] [--emr-cluster-id j-XXXX] [--dry-run]
Hard caps: --duration <= 14400 s, --max-samples <= 2000, --interval >= 5 s (default 15). One line per sample:
  {"synthetic": bool, "t_epoch": float, "t_utc": str, "ecs": {svc: {"desired","running","pending"}},
   "sqs": {"queue": {"visible","not_visible","delayed"}, "dlq": {...}},
   "emr": {"cluster_state": str, "groups": {"MASTER": {"requested","running","state"}, "CORE": {...}, "TASK": {...}}}, "errors": [str]}
Failed calls are recorded in the sample's "errors" list (never dropped). Output: <SYNTHETIC->sample_resources-<run>.jsonl (exclusive create).
Dry run: prints the commands; executes them only through the repository stub aws (then the file is SYNTHETIC-labeled).
Exit codes: 0 ok (also on Ctrl-C), 2 usage, 3 refused to overwrite.
"""
import json
import os
import re
import sys
import time

import collect_common as cc
import sc_common
from awscli import Aws, parse_json, require_region


def num(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def sample(aws, a):
    s, errors = {"ecs": {}, "sqs": {}, "emr": {}}, []
    if a.cluster_name and a.services:
        ok, rc, out, err = aws.run("ecs", "describe-services", ["--cluster", a.cluster_name, "--services"] + a.services.split(","))
        j = parse_json(out) if ok and rc == 0 else None
        if j is None:
            errors.append("ecs describe-services failed rc=%s" % rc)
        else:
            for svc in j.get("services", []):
                s["ecs"][svc.get("serviceName")] = {"desired": svc.get("desiredCount"), "running": svc.get("runningCount"), "pending": svc.get("pendingCount")}
    for label, url in (("queue", a.queue_url), ("dlq", a.dlq_url)):
        if not url:
            continue
        ok, rc, out, err = aws.run("sqs", "get-queue-attributes", ["--queue-url", url, "--attribute-names", "ApproximateNumberOfMessages",
                                                                    "ApproximateNumberOfMessagesNotVisible", "ApproximateNumberOfMessagesDelayed"])
        j = parse_json(out) if ok and rc == 0 else None
        if j is None:
            errors.append("sqs %s failed rc=%s" % (label, rc))
        else:
            at = j.get("Attributes", {})
            s["sqs"][label] = {"visible": num(at.get("ApproximateNumberOfMessages")), "not_visible": num(at.get("ApproximateNumberOfMessagesNotVisible")),
                               "delayed": num(at.get("ApproximateNumberOfMessagesDelayed"))}
    if a.emr_cluster_id:
        groups = {}
        ok, rc, out, err = aws.run("emr", "list-instance-groups", ["--cluster-id", a.emr_cluster_id])
        j = parse_json(out) if ok and rc == 0 else None
        if j is None:
            errors.append("emr list-instance-groups failed rc=%s" % rc)
        else:
            for g in j.get("InstanceGroups", []):
                groups[g.get("InstanceGroupType")] = {"requested": g.get("RequestedInstanceCount"), "running": g.get("RunningInstanceCount"),
                                                       "state": (g.get("Status") or {}).get("State")}
        ok, rc, out, err = aws.run("emr", "describe-cluster", ["--cluster-id", a.emr_cluster_id])
        j = parse_json(out) if ok and rc == 0 else None
        state = ((j or {}).get("Cluster") or {}).get("Status", {}).get("State")
        if j is None:
            errors.append("emr describe-cluster failed rc=%s" % rc)
        s["emr"] = {"cluster_state": state, "groups": groups}
    return s, errors


def main(argv):
    ap = cc.base_parser(__doc__.split("\n")[0])
    ap.add_argument("--interval", type=float, default=15.0)
    ap.add_argument("--duration", type=float, default=3600.0)
    ap.add_argument("--max-samples", type=int, default=400)
    ap.add_argument("--cluster-name")
    ap.add_argument("--services")
    ap.add_argument("--queue-url")
    ap.add_argument("--dlq-url")
    ap.add_argument("--emr-cluster-id")
    a = ap.parse_args(argv)
    stub_ok = os.environ.get("SC_ALLOW_SHORT_INTERVAL_FOR_STUB") == "1"
    if a.interval < (0.05 if stub_ok else 5) or a.duration <= 0 or a.duration > 14400 or not (1 <= a.max_samples <= 2000):
        print("SC-REFUSED caps: --interval >= 5 s, 0 < --duration <= 14400, 1 <= --max-samples <= 2000", file=sys.stderr)
        return 2
    if a.emr_cluster_id and not re.match(r"^j-[A-Z0-9]+$", a.emr_cluster_id):
        print("SC-REFUSED --emr-cluster-id must look like j-XXXXXXXX", file=sys.stderr)
        return 2
    if not (a.services and a.cluster_name) and not a.queue_url and not a.emr_cluster_id:
        print("SC-REFUSED nothing to sample: give ECS (--cluster-name and --services), SQS (--queue-url) or EMR (--emr-cluster-id) targets", file=sys.stderr)
        return 2
    aws = Aws(require_region(a.region), a.dry_run)
    sv = cc.Saver(a, "sample_resources", aws)
    if a.dry_run and not aws.stub:
        sample(aws, a)
        return sv.finish()
    path = sv.path("samples.jsonl")
    try:
        fh = sc_common.open_new(path, newline="\n")
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    n = 0
    t_end = time.time() + a.duration
    try:
        while n < a.max_samples and time.time() < t_end:
            t = time.time()
            s, errors = sample(aws, a)
            row = {"synthetic": sv.synthetic, "t_epoch": round(t, 3), "t_utc": sc_common.utc_iso(t)}
            row.update(s)
            row["errors"] = errors
            fh.write(json.dumps(row, sort_keys=True) + "\n")
            fh.flush()
            n += 1
            time.sleep(max(0.0, a.interval - (time.time() - t)))
    except KeyboardInterrupt:
        print("SC-INFO interrupted: samples so far are kept")
    finally:
        fh.close()
    sv.files.append({"file": os.path.basename(path), "sha256": sc_common.sha256_file(path)})
    print("SC-RAW %s (%d samples)" % (path, n))
    aws.log = aws.log[:20]  # the meta file keeps the first commands only
    return sv.finish({"samples": n, "interval_seconds": a.interval, "caps": {"duration": a.duration, "max_samples": a.max_samples}})


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
