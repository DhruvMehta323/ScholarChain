#!/usr/bin/env python3
"""Pull the EMR cluster, step and instance-group timelines of one cluster (read-only; Python standard library only).

Usage:
  python3 -B experiments/collect/collect_emr.py --experiment-id exp-a-baseline-10k --run-id exp-a-r1 --cluster-id j-XXXX --region us-east-2 [--dry-run]
Calls: emr describe-cluster, emr list-steps, emr list-instance-groups, emr list-instances (all read-only). The cluster id is in
reports/pipeline/<scale>/<run>/run.json (field cluster_id). Capacity over TIME is not available after the fact from these calls (they give
the current state and per-instance creation/ready/end times): list-instances timelines are what allow the managed-scaling reconstruction
(one row per instance with CreationDateTime, ReadyDateTime, EndDateTime), and sample_resources.py records requested versus running counts
live during the run. Hadoop/Spark counters (records, tasks, shuffle) live in the EMR logs in the logs bucket and are NOT fetched here
(no S3 access in the collectors): the human exports them in Phase 15 and they are referenced from the experiment record.
Exit codes: 0 ok, 1 a call failed (kept), 2 usage, 3 refused to overwrite.
"""
import re
import sys

import collect_common as cc
from awscli import Aws, require_region


def main(argv):
    ap = cc.base_parser(__doc__.split("\n")[0])
    ap.add_argument("--cluster-id", required=True)
    a = ap.parse_args(argv)
    if not re.match(r"^j-[A-Z0-9]+$", a.cluster_id):
        print("SC-REFUSED --cluster-id must look like j-XXXXXXXX", file=sys.stderr)
        return 2
    aws = Aws(require_region(a.region), a.dry_run)
    sv = cc.Saver(a, "collect_emr", aws)
    for name, op, extra in (("cluster", "describe-cluster", []), ("steps", "list-steps", []),
                            ("instance-groups", "list-instance-groups", []), ("instances", "list-instances", [])):
        ok, rc, out, err = aws.run("emr", op, ["--cluster-id", a.cluster_id] + extra)
        if not ok:
            continue
        if rc == 0:
            sv.save(name + ".json", out)
        else:
            sv.error(name, rc, err)
    return sv.finish({"cluster_id": a.cluster_id})


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
