#!/usr/bin/env python3
"""Pull daily cost by service from Cost Explorer for a date window, BEFORE credits (read-only; Python standard library only).

Usage:
  python3 -B experiments/collect/collect_cost.py --experiment-id exp-a-baseline-10k --run-id exp-a-r1 --start 2026-11-29 --end 2026-12-01 [--dry-run]
Call: aws ce get-cost-and-usage --granularity DAILY --metrics UnblendedCost --group-by Type=DIMENSION,Key=SERVICE
      --filter {"Not":{"Dimensions":{"Key":"RECORD_TYPE","Values":["Credit","Refund"]}}}
Credits and refunds are excluded by the filter (the budget in Terraform also excludes credits). --end is exclusive (Cost Explorer rule).
Cost Explorer is served from us-east-1 whatever the workload Region; --region defaults to us-east-1 for this collector. Each API request
costs about 0.01 USD (AWS Cost Explorer pricing, recollection, NOT VERIFIED here): this collector makes exactly one.
Cost data lags real spend by up to a day: collect it the day AFTER the window and say so in the report.
Exit codes: 0 ok, 1 call failed (kept), 2 usage, 3 refused to overwrite.
"""
import json
import re
import sys

import collect_common as cc
from awscli import Aws


def main(argv):
    ap = cc.base_parser(__doc__.split("\n")[0])
    ap.add_argument("--start", required=True, help="YYYY-MM-DD (inclusive)")
    ap.add_argument("--end", required=True, help="YYYY-MM-DD (exclusive)")
    a = ap.parse_args(argv)
    if not (re.match(r"^\d{4}-\d{2}-\d{2}$", a.start) and re.match(r"^\d{4}-\d{2}-\d{2}$", a.end)) or a.end <= a.start:
        print("SC-REFUSED --start/--end must be YYYY-MM-DD with end after start", file=sys.stderr)
        return 2
    aws = Aws(a.region or "us-east-1", a.dry_run)
    sv = cc.Saver(a, "collect_cost", aws)
    flt = {"Not": {"Dimensions": {"Key": "RECORD_TYPE", "Values": ["Credit", "Refund"]}}}
    ok, rc, out, err = aws.run("ce", "get-cost-and-usage",
                               ["--time-period", "Start=%s,End=%s" % (a.start, a.end), "--granularity", "DAILY", "--metrics", "UnblendedCost",
                                "--group-by", "Type=DIMENSION,Key=SERVICE", "--filter", json.dumps(flt)])
    if ok:
        if rc == 0:
            sv.save("daily-by-service.json", out)
        else:
            sv.error("daily-by-service", rc, err)
    return sv.finish({"window": {"start": a.start, "end_exclusive": a.end}, "filter": flt, "metric": "UnblendedCost", "credits_excluded": True})


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
