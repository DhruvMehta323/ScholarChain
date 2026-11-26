#!/usr/bin/env python3
"""SQS depth over time, peak, drain time and dead-letter depth for the embedding queue (experiments C and F).

Usage: python3 -B experiments/analysis/queue_drain.py SAMPLES.jsonl [--cloudwatch collect_cloudwatch-...-sqs.json] [--out-dir DIR] [--force] [--no-plot]
Input: sample_resources.py JSONL (field "sqs": {"queue": {visible, not_visible, delayed}, "dlq": {...}}). Backlog = visible + not_visible + delayed
(messages not yet deleted). The optional CloudWatch file (collect_cloudwatch.py, group sqs) adds sums of NumberOfMessagesSent, Received and
Deleted: Received minus Deleted counts deliveries that were not completed (retries or still in flight), a bound on retries, NOT an exact count.
Drain rule (metrics.drain_summary): peak = maximum backlog; drained = first sample AFTER the peak with backlog 0; drain_seconds = difference;
mean_drain_rate = peak / drain_seconds assumes the peak is the enqueued total (stated in the output as an assumption, not measured throughput).
A run that never drains is reported with drained=false, not dropped.
Output: queue-timeline.csv, queue-summary.csv (+ queue-depth.png). Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import sys

import an_common as ac
import sc_common
from metrics import drain_summary


def backlog(d):
    return sum(v for v in (d.get("visible"), d.get("not_visible"), d.get("delayed")) if isinstance(v, int)) if d else None


def cw_sums(path):
    j = sc_common.read_json(path)
    sums = {}
    for r in j.get("MetricDataResults", []):
        sums[r.get("Label")] = sum(r.get("Values", []))
    return sums


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.add_argument("samples")
    ap.add_argument("--cloudwatch")
    a = ap.parse_args(argv)
    ac.begin(__file__, argv, [a.samples, a.cloudwatch], extra=vars(a))
    rows = sc_common.read_jsonl(a.samples)
    synthetic = ac.input_is_synthetic(a.samples) or any(r.get("synthetic") for r in rows[:3])
    rows = [r for r in rows if (r.get("sqs") or {}).get("queue")]
    if not rows:
        print("SC-REFUSED no queue samples", file=sys.stderr)
        return 2
    t0 = rows[0]["t_epoch"]
    q = [(r["t_epoch"], backlog(r["sqs"]["queue"])) for r in rows]
    dl = [(r["t_epoch"], backlog(r["sqs"].get("dlq"))) for r in rows if r["sqs"].get("dlq")]
    ds = drain_summary(q)
    dlq_max = max([d for _, d in dl if d is not None], default=None)
    timeline = [[r["t_epoch"], r["t_utc"], "%.1f" % (r["t_epoch"] - t0), backlog(r["sqs"]["queue"]), backlog(r["sqs"].get("dlq")) if r["sqs"].get("dlq") else ""] for r in rows]
    drained = ds["drain_seconds"] is not None
    rate = (ds["peak"] / ds["drain_seconds"]) if drained and ds["drain_seconds"] > 0 and ds["peak"] else None
    summary = [["peak_backlog", ds["peak"]], ["t_peak_rel_s", "%.1f" % (ds["t_peak"] - t0)], ["drained", str(drained).lower()],
               ["drain_seconds", ac.fmt(ds["drain_seconds"], 1)], ["mean_drain_rate_msgs_per_s_ASSUMES_peak_equals_enqueued", ac.fmt(rate)],
               ["dlq_max_depth", dlq_max if dlq_max is not None else ""], ["samples", len(rows)]]
    if a.cloudwatch:
        s = cw_sums(a.cloudwatch)
        sent, rec, dele = s.get("queue NumberOfMessagesSent"), s.get("queue NumberOfMessagesReceived"), s.get("queue NumberOfMessagesDeleted")
        summary += [["cw_sent", ac.fmt(sent, 0)], ["cw_received", ac.fmt(rec, 0)], ["cw_deleted", ac.fmt(dele, 0)],
                    ["cw_received_minus_deleted_UPPER_BOUND_ON_RETRIES", ac.fmt((rec - dele) if rec is not None and dele is not None else None, 0)]]
    try:
        ac.write_table(a.out_dir, "queue-timeline.csv", ["t_epoch", "t_utc", "t_rel_s", "queue_backlog", "dlq_backlog"], timeline, synthetic, a.force)
        ac.write_table(a.out_dir, "queue-summary.csv", ["metric", "value"], summary, synthetic, a.force)
        for k, v in summary:
            print("SC-SUMMARY %s=%s" % (k, v))
        if not a.no_plot:
            series = {"queue backlog": ([t - t0 for t, _ in q], [d for _, d in q])}
            if dl:
                series["DLQ backlog"] = ([t - t0 for t, _ in dl], [d for _, d in dl])
            ac.plot_lines(a.out_dir, "queue-depth.png", "Embedding queue backlog and drain", "seconds since first sample", "messages", series, synthetic, a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
