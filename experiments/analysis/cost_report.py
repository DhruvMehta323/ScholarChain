#!/usr/bin/env python3
"""Cost per experiment: measured window cost (Cost Explorer), model-spend estimate (load generator meta files) versus the manifest caps.

Usage: python3 -B experiments/analysis/cost_report.py [--manifests DIR] [--results DIR] [--cost EXPERIMENT_ID=COST.json ...] [--out-dir DIR] [--force]
Per experiment row: cost_cap_usd and model_cost_cap_usd from the manifest; measured_window_usd = sum of UnblendedCost over the cost file given
with --cost (credits excluded by the collector's filter; this is the WINDOW cost, everything the account spent on those days, not only this
experiment); model_estimate_usd = sum of model_cost_estimated_usd over loadgen .meta.json files under <results>/<experiment>/ (an ESTIMATE from
assumed tokens and prices, see manifests/model-budget.json); over_cap flags measured above the cost cap or the estimate above the model cap.
A meta labeled synthetic whose base_url is NOT loopback is never ignored: it is counted as real and flagged in over_cap (a label cannot hide spend).
SYNTHETIC meta files are IGNORED unless --include-synthetic is given; then they are summed into their own synthetic_model_estimate_usd column and the whole output is labeled SYNTHETIC.
Output: cost-per-experiment.csv. Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import glob
import json
import os
import sys
from urllib.parse import urlsplit

import an_common as ac
import sc_common

HERE = os.path.dirname(os.path.abspath(__file__))


def window_cost(path):
    j = sc_common.read_json(path)
    return sum(float(g["Metrics"]["UnblendedCost"]["Amount"]) for day in j.get("ResultsByTime", []) for g in day.get("Groups", []))


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.add_argument("--manifests", default=os.path.join(HERE, "..", "manifests"))
    ap.add_argument("--results", default=sc_common.results_root(), help="raw results root (default: $SC_RESULTS_ROOT or experiments/results)")
    ap.add_argument("--include-synthetic", action="store_true", help="also count SYNTHETIC loadgen runs; the whole output is then labeled SYNTHETIC")
    ap.add_argument("--cost", action="append", default=[], help="EXPERIMENT_ID=path of a collect_cost file (repeatable)")
    a = ap.parse_args(argv)
    costs = {}
    synthetic = False
    for item in a.cost:
        k, _, v = item.partition("=")
        costs[k] = v
        synthetic = synthetic or ac.input_is_synthetic(v)
    rows = []
    prov_inputs = list(costs.values())
    for mp in sorted(glob.glob(os.path.join(a.manifests, "exp-*.json"))):
        m = sc_common.read_json(mp)
        prov_inputs.append(mp)
        real, synth, n_real, n_synth = 0.0, 0.0, 0, 0
        over_extra = []
        for meta in sorted(glob.glob(os.path.join(a.results, m["id"], "**", "*loadgen-*.meta.json"), recursive=True)):
            prov_inputs.append(meta)
            j = sc_common.read_json(meta)
            host = (urlsplit(j.get("base_url") or "").hostname or "").lower()
            mislabeled = bool(j.get("synthetic")) and host not in ("127.0.0.1", "localhost", "::1")
            if mislabeled:
                over_extra.append("non_loopback_meta_labeled_synthetic:" + os.path.basename(meta))
            if j.get("synthetic") and not mislabeled:
                if a.include_synthetic:
                    synth += j.get("model_cost_estimated_usd", 0.0)
                    n_synth += 1
            else:
                real += j.get("model_cost_estimated_usd", 0.0)
                n_real += 1
        measured = window_cost(costs[m["id"]]) if m["id"] in costs else None
        over = list(over_extra)
        if measured is not None and measured > m["cost_cap_usd"]:
            over.append("measured>cost_cap")
        if m.get("model_cost_cap_usd") is not None and real > m["model_cost_cap_usd"]:
            over.append("model_estimate>model_cap")
        rows.append([m["id"], m["letter"], m["cost_cap_usd"], m.get("model_cost_cap_usd", ""), ac.fmt(measured, 4) if measured is not None else "NOT RUN / NO COST FILE",
                     ac.fmt(real, 4), n_real, ac.fmt(synth, 4), n_synth, ";".join(over) or "no"])
    synth_row = synthetic or a.include_synthetic
    ac.begin(__file__, argv, prov_inputs, extra=vars(a))
    try:
        ac.write_table(a.out_dir, "cost-per-experiment.csv", ["experiment_id", "letter", "cost_cap_usd", "model_cost_cap_usd", "measured_window_usd", "model_estimate_usd",
                                                              "n_real_loadgen_runs", "synthetic_model_estimate_usd", "n_synthetic_loadgen_runs", "over_cap"], rows, synth_row, a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    for r in rows:
        print("SC-ROW " + " | ".join(str(x) for x in r))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
