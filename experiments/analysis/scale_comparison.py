#!/usr/bin/env python3
"""10K versus 50K comparison of time, utilization and cost (instructor approval item 1).

Usage: python3 -B experiments/analysis/scale_comparison.py --a-run reports/pipeline/10k/<run>/run.json --b-run reports/pipeline/50k/<run>/run.json \
           [--a-samples SAMPLES.jsonl --b-samples SAMPLES.jsonl] [--a-cost COST.json --b-cost COST.json] [--out-dir DIR] [--force]
Inputs: run.json files written by scripts/run-bounded-pipeline.sh (fields scale, run_id, status, cluster_id, started_at, updated_at,
step_timings[{name,state,start,end}]); sample_resources.py JSONL for node-hours and peak nodes; collect_cost.py files for measured cost.
Rows: total wall seconds (started_at to updated_at), seconds per step name (end minus start, from the EMR timeline), node-hours and peak nodes
(emr_capacity.py definitions), measured cost (sum of UnblendedCost over the file's window: the WINDOW cost, which includes anything else the
account did on those days; label it as such), and the ratio B/A for each numeric row. A run whose status is not "succeeded" is shown with its
status and its numbers: failed runs are compared, never dropped. Missing inputs leave the cell empty.
Output: scale-comparison.csv. Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import sys

import an_common as ac
import emr_capacity
import sc_common


def step_seconds(run):
    out = {}
    for st in run.get("step_timings") or []:
        if st.get("start") and st.get("end"):
            out[st["name"]] = sc_common.parse_iso(st["end"]) - sc_common.parse_iso(st["start"])
        else:
            out.setdefault(st.get("name", "?"), None)
    return out


def cost_total(path):
    j = sc_common.read_json(path)
    total, by = 0.0, {}
    for day in j.get("ResultsByTime", []):
        for g in day.get("Groups", []):
            amt = float(g["Metrics"]["UnblendedCost"]["Amount"])
            total += amt
            by[g["Keys"][0]] = by.get(g["Keys"][0], 0.0) + amt
    return total, by


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.add_argument("--a-run", required=True)
    ap.add_argument("--b-run", required=True)
    ap.add_argument("--a-samples")
    ap.add_argument("--b-samples")
    ap.add_argument("--a-cost")
    ap.add_argument("--b-cost")
    a = ap.parse_args(argv)
    ac.begin(__file__, argv, [a.a_run, a.b_run, a.a_samples, a.b_samples, a.a_cost, a.b_cost], extra=vars(a))
    runs = {"A": sc_common.read_json(a.a_run), "B": sc_common.read_json(a.b_run)}
    synthetic = ac.input_is_synthetic(a.a_run) or ac.input_is_synthetic(a.b_run) or any(r.get("synthetic") for r in runs.values())
    rows = []

    def add(metric, va, vb, nd=1):
        ratio = (vb / va) if isinstance(va, (int, float)) and isinstance(vb, (int, float)) and va else None
        rows.append([metric, ac.fmt(va, nd) if va is not None else "", ac.fmt(vb, nd) if vb is not None else "", ac.fmt(ratio, 2) if ratio is not None else ""])
    rows.append(["scale", runs["A"].get("scale", ""), runs["B"].get("scale", ""), ""])
    rows.append(["run_id", runs["A"].get("run_id", ""), runs["B"].get("run_id", ""), ""])
    rows.append(["status", runs["A"].get("status", ""), runs["B"].get("status", ""), ""])
    w = {}
    for k, r in runs.items():
        try:
            w[k] = sc_common.parse_iso(r["updated_at"]) - sc_common.parse_iso(r["started_at"])
        except (KeyError, ValueError):
            w[k] = None
    add("total_wall_seconds", w["A"], w["B"])
    sa, sb = step_seconds(runs["A"]), step_seconds(runs["B"])
    for name in sorted(set(sa) | set(sb)):
        add("step_seconds:" + name, sa.get(name), sb.get(name))
    for k, sp in (("A", a.a_samples), ("B", a.b_samples)):
        runs[k]["_cap"] = emr_capacity.capacity_summary(sc_common.read_jsonl(sp)) if sp else None
    ca, cb = runs["A"]["_cap"], runs["B"]["_cap"]
    add("node_hours", ca["node_hours"] if ca else None, cb["node_hours"] if cb else None, 4)
    add("peak_provisioned_nodes", ca["peak_provisioned"] if ca else None, cb["peak_provisioned"] if cb else None, 0)
    add("peak_requested_nodes", ca["peak_requested"] if ca else None, cb["peak_requested"] if cb else None, 0)
    ta = tb = None
    if a.a_cost:
        ta, by_a = cost_total(a.a_cost)
    if a.b_cost:
        tb, by_b = cost_total(a.b_cost)
    add("measured_window_cost_usd_all_services_credits_excluded", ta, tb, 4)
    rows.append(["note", "ratio is B divided by A", "cost is WINDOW cost (all services on those days)", ""])
    try:
        ac.write_table(a.out_dir, "scale-comparison.csv", ["metric", "A_10k", "B_50k", "ratio_B_over_A"], rows, synthetic, a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    for r in rows:
        print("SC-ROW " + " | ".join(r))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
