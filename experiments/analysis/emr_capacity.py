#!/usr/bin/env python3
"""EMR capacity over time: requested, provisioned (running) and released nodes, with node-hours (experiments A and B).

Usage: python3 -B experiments/analysis/emr_capacity.py SAMPLES.jsonl [--node-hour-usd 0.2533] [--out-dir DIR] [--force] [--no-plot]
Input: sample_resources.py JSONL (field "emr": {cluster_state, groups: {MASTER|CORE|TASK: {requested, running, state}}}).
Definitions: requested = sum of RequestedInstanceCount over all groups; provisioned = sum of RunningInstanceCount; a release is a drop of the
provisioned total between consecutive samples (events use metrics.detect_scale_events on the provisioned and on the requested totals).
provision_lag_seconds = time from the first sample where requested exceeds the earlier requested total until provisioned reaches it.
node_hours = trapezoid integral of provisioned over the sample clock. cost_estimate = node_hours x --node-hour-usd; the default 0.2533 is
INFRA-CONTRACTS section 10 (about 0.76 USD per hour for 3 m5.xlarge nodes, public IPv4 included) divided by 3: an ESTIMATE, not a measurement,
and it ignores EBS and EMR uplift details; the Cost Explorer file is the measurement (collect_cost.py).
Output: emr-capacity-timeline.csv, emr-capacity-events.csv, emr-capacity-summary.csv (+ emr-capacity.png). Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import sys

import an_common as ac
import sc_common
from metrics import detect_scale_events


def totals(r):
    g = (r.get("emr") or {}).get("groups") or {}
    req = sum(v.get("requested") or 0 for v in g.values())
    run = sum(v.get("running") or 0 for v in g.values())
    return req, run


def capacity_summary(rows):
    """Return dict with series, events, node_hours, peaks, provision_lag (used by tests and by scale_comparison.py)."""
    pts = [(r["t_epoch"],) + totals(r) for r in rows if (r.get("emr") or {}).get("groups")]
    req = [(t, q) for t, q, _ in pts]
    run = [(t, n) for t, _, n in pts]
    node_h = 0.0
    for (t0, _, n0), (t1, _, n1) in zip(pts, pts[1:]):
        node_h += (n0 + n1) / 2.0 * (t1 - t0) / 3600.0
    lag = None
    for i in range(1, len(pts)):
        if pts[i][1] > pts[i - 1][1]:
            target, t_req = pts[i][1], pts[i][0]
            for t, _, n in pts[i:]:
                if n >= target:
                    lag = t - t_req
                    break
            break
    return {"pts": pts, "events_requested": detect_scale_events(req), "events_provisioned": detect_scale_events(run), "node_hours": node_h,
            "peak_requested": max((q for _, q, _ in pts), default=None), "peak_provisioned": max((n for _, _, n in pts), default=None), "provision_lag_seconds": lag,
            "duration_seconds": (pts[-1][0] - pts[0][0]) if len(pts) > 1 else 0.0}


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.add_argument("samples")
    ap.add_argument("--node-hour-usd", type=float, default=0.2533)
    a = ap.parse_args(argv)
    ac.begin(__file__, argv, [a.samples], extra=vars(a))
    rows = sc_common.read_jsonl(a.samples)
    synthetic = ac.input_is_synthetic(a.samples) or any(r.get("synthetic") for r in rows[:3])
    s = capacity_summary(rows)
    if not s["pts"]:
        print("SC-REFUSED no EMR samples", file=sys.stderr)
        return 2
    t0 = s["pts"][0][0]
    timeline = [["%.3f" % t, sc_common.utc_iso(t), "%.1f" % (t - t0), q, n] for t, q, n in s["pts"]]
    events = [["requested", sc_common.utc_iso(e["t"]), "%.1f" % (e["t"] - t0), e["from"], e["to"], "increase" if e["direction"] == "out" else "decrease"] for e in s["events_requested"]]
    events += [["provisioned", sc_common.utc_iso(e["t"]), "%.1f" % (e["t"] - t0), e["from"], e["to"], "provisioned" if e["direction"] == "out" else "released"] for e in s["events_provisioned"]]
    summary = [["peak_requested_nodes", s["peak_requested"]], ["peak_provisioned_nodes", s["peak_provisioned"]], ["provision_lag_seconds", ac.fmt(s["provision_lag_seconds"], 1)],
               ["released_events", sum(1 for e in s["events_provisioned"] if e["direction"] == "in")], ["node_hours", ac.fmt(s["node_hours"], 4)],
               ["cost_estimate_usd_ESTIMATE_not_measured", ac.fmt(s["node_hours"] * a.node_hour_usd, 4)], ["observed_seconds", ac.fmt(s["duration_seconds"], 1)]]
    try:
        ac.write_table(a.out_dir, "emr-capacity-timeline.csv", ["t_epoch", "t_utc", "t_rel_s", "requested_nodes", "provisioned_nodes"], timeline, synthetic, a.force)
        ac.write_table(a.out_dir, "emr-capacity-events.csv", ["series", "t_utc", "t_rel_s", "from", "to", "meaning"], events, synthetic, a.force)
        ac.write_table(a.out_dir, "emr-capacity-summary.csv", ["metric", "value"], summary, synthetic, a.force)
        for k, v in summary:
            print("SC-SUMMARY %s=%s" % (k, v))
        for e in events:
            print("SC-EVENT %s t_rel_s=%s %s->%s (%s)" % tuple(e[0:1] + e[2:6]))
        if not a.no_plot:
            xs = [t - t0 for t, _, _ in s["pts"]]
            ac.plot_lines(a.out_dir, "emr-capacity.png", "EMR requested vs provisioned nodes", "seconds since first sample", "nodes",
                          {"requested": (xs, [q for _, q, _ in s["pts"]]), "provisioned": (xs, [n for _, _, n in s["pts"]])}, synthetic, a.force, step=True)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
