#!/usr/bin/env python3
"""Pull application log events for a window with CloudWatch Logs Insights (read-only; Python standard library only).

Usage:
  python3 -B experiments/collect/collect_logs.py --experiment-id exp-d-query-load --run-id exp-d-r1 --log-group /scholarchain/query-api \
      --start 2026-11-30T14:00:00Z --end 2026-11-30T15:00:00Z [--filter-event query.completed] [--limit 5000] [--poll-seconds 2] [--dry-run]
Log groups (INFRA-CONTRACTS section 7): /scholarchain/query-api, /scholarchain/retrieval, /scholarchain/embedding-worker, /scholarchain/sfn-pipeline.
The services write one JSON line per request or event with fields ts, level, service, event, traceId, requestId, plus method, path, status and
latency (modules/services/API.md on main). The exact name of the latency field is NOT VERIFIED, so the raw @message is kept whole and the
analysis parses it. start-query and get-query-results are read-only. Logs Insights bills about 0.005 USD per GB scanned (recollection, NOT
VERIFIED); the window is bounded to 6 hours and --limit to 10000 rows.
Exit codes: 0 ok, 1 call failed or query did not complete (kept), 2 usage, 3 refused to overwrite.
"""
import re
import sys
import time

import collect_common as cc
from awscli import Aws, parse_json, require_region
from sc_common import parse_iso


def main(argv):
    ap = cc.base_parser(__doc__.split("\n")[0])
    ap.add_argument("--log-group", required=True)
    ap.add_argument("--start", required=True)
    ap.add_argument("--end", required=True)
    ap.add_argument("--filter-event", default="", help="only events whose event field equals this (default all)")
    ap.add_argument("--limit", type=int, default=5000)
    ap.add_argument("--poll-seconds", type=float, default=2.0)
    ap.add_argument("--max-polls", type=int, default=60)
    a = ap.parse_args(argv)
    if not re.match(r"^/scholarchain/[A-Za-z0-9._/-]+$", a.log_group):
        print("SC-REFUSED --log-group must be one of the /scholarchain/* groups", file=sys.stderr)
        return 2
    if a.filter_event and not re.match(r"^[A-Za-z0-9._-]+$", a.filter_event):
        print("SC-REFUSED --filter-event must be a token", file=sys.stderr)
        return 2
    try:
        t0, t1 = int(parse_iso(a.start)), int(parse_iso(a.end))
    except ValueError:
        print("SC-REFUSED --start/--end must be ISO-8601", file=sys.stderr)
        return 2
    if t1 <= t0 or t1 - t0 > 6 * 3600 or not (1 <= a.limit <= 10000):
        print("SC-REFUSED window must be positive, at most 6 hours, and --limit 1..10000", file=sys.stderr)
        return 2
    aws = Aws(require_region(a.region), a.dry_run)
    sv = cc.Saver(a, "collect_logs", aws)
    flt = ('| filter event = "%s" ' % a.filter_event) if a.filter_event else ""
    query = "fields @timestamp, @message %s| sort @timestamp asc | limit %d" % (flt, a.limit)
    tag = a.log_group.strip("/").replace("/", "_")
    ok, rc, out, err = aws.run("logs", "start-query", ["--log-group-name", a.log_group, "--start-time", str(t0), "--end-time", str(t1), "--query-string", query])
    if not ok:
        return sv.finish()
    if rc != 0:
        sv.error(tag + "-start", rc, err)
        return sv.finish()
    qid = (parse_json(out) or {}).get("queryId")
    if not qid:
        sv.error(tag + "-start", rc, "no queryId in the start-query output")
        return sv.finish()
    sv.save(tag + "-start.json", out)
    for _ in range(a.max_polls):
        ok, rc, out, err = aws.run("logs", "get-query-results", ["--query-id", qid])
        if rc != 0:
            sv.error(tag + "-results", rc, err)
            break
        status = (parse_json(out) or {}).get("status")
        if status in ("Complete", "Failed", "Cancelled", "Timeout"):
            sv.save(tag + "-results.json", out)
            if status != "Complete":
                sv.error(tag + "-results", 1, "query ended with status " + str(status))
            break
        time.sleep(a.poll_seconds)
    else:
        sv.error(tag + "-results", 1, "query did not complete within the poll limit")
    return sv.finish({"log_group": a.log_group, "query_string": query, "window_epoch": [t0, t1]})


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
