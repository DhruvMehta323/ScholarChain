#!/usr/bin/env python3
"""ECS task counts over time with synchronized timestamps, plus scale-out and scale-in events.

Usage: python3 -B experiments/analysis/scale_timeline.py SAMPLES.jsonl [--loadgen RAW.csv ...] [--out-dir DIR] [--force] [--no-plot]
Input: JSONL from experiments/collect/sample_resources.py (field "ecs": {service: {desired, running, pending}}). All series share the sample
clock t_epoch (UTC seconds), so services and the load window line up; --loadgen adds the first request start and last request end of the given
raw CSVs on the same clock (columns t_start_epoch_ms, latency_ms) so scale events can be read against load.
Event rule (metrics.detect_scale_events): one event for every change of the RUNNING count between consecutive samples; direction out when it
rises, in when it falls. Timing resolution is the sampling interval: an event is dated at the first sample that shows the new value.
Output: task-counts-timeline.csv, scale-events.csv, scale-summary.csv (+ task-counts-timeline.png). Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import sys

import an_common as ac
import sc_common
from metrics import detect_scale_events


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.add_argument("samples")
    ap.add_argument("--loadgen", nargs="*", default=[])
    a = ap.parse_args(argv)
    ac.begin(__file__, argv, [a.samples] + list(a.loadgen), extra=vars(a))
    rows = sc_common.read_jsonl(a.samples)
    synthetic = ac.input_is_synthetic(a.samples) or any(r.get("synthetic") for r in rows[:3])
    if not rows:
        print("SC-REFUSED no samples", file=sys.stderr)
        return 2
    t0 = rows[0]["t_epoch"]
    services = sorted({s for r in rows for s in (r.get("ecs") or {})})
    timeline, series, events, summary = [], {}, [], []
    for s in services:
        pts = [(r["t_epoch"], (r["ecs"].get(s) or {}).get("running")) for r in rows if (r.get("ecs") or {}).get(s)]
        pts = [(t, c) for t, c in pts if c is not None]
        series[s] = ([t - t0 for t, _ in pts], [c for _, c in pts])
        for r in rows:
            e = (r.get("ecs") or {}).get(s)
            if e:
                timeline.append([r["t_epoch"], r["t_utc"], "%.1f" % (r["t_epoch"] - t0), s, e.get("desired"), e.get("running"), e.get("pending")])
        ev = detect_scale_events(pts)
        for x in ev:
            events.append([s, sc_common.utc_iso(x["t"]), "%.1f" % (x["t"] - t0), x["from"], x["to"], x["direction"]])
        counts = [c for _, c in pts]
        summary.append([s, len(pts), min(counts) if counts else "", max(counts) if counts else "", sum(1 for x in ev if x["direction"] == "out"),
                        sum(1 for x in ev if x["direction"] == "in")])
    lg = []
    for p in a.loadgen:
        lg += ac.read_csv_rows(p)
    if lg:
        starts = [int(r["t_start_epoch_ms"]) / 1000.0 for r in lg]
        ends = [int(r["t_start_epoch_ms"]) / 1000.0 + float(r["latency_ms"]) / 1000.0 for r in lg]
        summary.append(["(load window)", len(lg), "", "", "start_rel_s=%.1f" % (min(starts) - t0), "end_rel_s=%.1f" % (max(ends) - t0)])
    try:
        ac.write_table(a.out_dir, "task-counts-timeline.csv", ["t_epoch", "t_utc", "t_rel_s", "service", "desired", "running", "pending"], timeline, synthetic, a.force)
        ac.write_table(a.out_dir, "scale-events.csv", ["service", "t_utc", "t_rel_s", "from", "to", "direction"], events, synthetic, a.force)
        ac.write_table(a.out_dir, "scale-summary.csv", ["service", "samples", "min_running", "max_running", "scale_out_events", "scale_in_events"], summary, synthetic, a.force)
        for e in events:
            print("SC-EVENT service=%s t_rel_s=%s %s->%s (%s)" % (e[0], e[2], e[3], e[4], e[5]))
        for s in summary:
            print("SC-SUMMARY " + " ".join(str(x) for x in s))
        if not a.no_plot:
            ac.plot_lines(a.out_dir, "task-counts-timeline.png", "ECS running task count over time", "seconds since first sample", "running tasks", series, synthetic, a.force, step=True)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
