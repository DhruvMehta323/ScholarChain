#!/usr/bin/env python3
"""Latency percentiles, throughput and error rate per concurrency level from load generator raw CSVs.

Usage: python3 -B experiments/analysis/latency_percentiles.py RAW.csv [RAW.csv ...] [--out-dir DIR] [--force] [--no-plot]
Input: files written by load-tests/loadgen.py (columns in load-tests/README.md). Rows from several files are grouped by (mode, concurrency).
Method (fixed, hand-checkable): percentiles use the NEAREST-RANK rule on the latency_ms of SUCCESSFUL requests (outcome ok or async);
error_rate = failed rows / all rows, where failed is any row whose outcome is not ok or async (timeouts, connection errors and every non-2xx
status are failures, 429 and 422 included); throughput_rps = successful rows / (last request end - first request start), the window being
reconstructed as max(t_start + latency) - min(t_start). Failed rows are COUNTED, never dropped. With n below 100 the p99 equals the maximum:
the table carries n so a reader sees it.
Output: latency-by-concurrency.csv (+ latency-by-concurrency.png). Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import sys
from collections import defaultdict

import an_common as ac
import sc_common
from metrics import mean, percentile

HEADER = ["mode", "concurrency", "n_total", "n_success", "n_failed", "error_rate", "p50_ms", "p95_ms", "p99_ms", "min_ms", "max_ms", "mean_ms",
          "wall_seconds", "throughput_rps", "status_counts", "runs"]


def analyze(rows):
    groups = defaultdict(list)
    for r in rows:
        groups[(r["mode"], int(r["concurrency"]))].append(r)
    out = []
    for (mode, conc), rs in sorted(groups.items()):
        ok = [float(r["latency_ms"]) for r in rs if r["outcome"] in ("ok", "async")]
        starts = [int(r["t_start_epoch_ms"]) for r in rs]
        ends = [int(r["t_start_epoch_ms"]) + float(r["latency_ms"]) for r in rs]
        wall = (max(ends) - min(starts)) / 1000.0
        sc = defaultdict(int)
        for r in rs:
            sc[r["http_status"] if r["http_status"] not in ("", "0") else r["error_class"]] += 1
        out.append({"mode": mode, "concurrency": conc, "n_total": len(rs), "n_success": len(ok), "n_failed": len(rs) - len(ok),
                    "error_rate": (len(rs) - len(ok)) / float(len(rs)), "p50": percentile(ok, 50), "p95": percentile(ok, 95), "p99": percentile(ok, 99),
                    "min": min(ok) if ok else None, "max": max(ok) if ok else None, "mean": mean(ok), "wall": wall,
                    "rps": (len(ok) / wall) if wall > 0 else None, "statuses": ";".join("%s:%d" % kv for kv in sorted(sc.items())),
                    "runs": ";".join(sorted(set(r["run_id"] for r in rs)))})
    return out


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.add_argument("inputs", nargs="+")
    a = ap.parse_args(argv)
    ac.begin(__file__, argv, a.inputs, extra=vars(a))
    rows, synthetic = [], False
    for p in a.inputs:
        rs = ac.read_csv_rows(p)
        synthetic = synthetic or ac.input_is_synthetic(p, rs)
        rows += rs
    if not rows:
        print("SC-REFUSED no rows", file=sys.stderr)
        return 2
    res = analyze(rows)
    table = [[r["mode"], r["concurrency"], r["n_total"], r["n_success"], r["n_failed"], ac.fmt(r["error_rate"], 4), ac.fmt(r["p50"]), ac.fmt(r["p95"]),
              ac.fmt(r["p99"]), ac.fmt(r["min"]), ac.fmt(r["max"]), ac.fmt(r["mean"]), ac.fmt(r["wall"]), ac.fmt(r["rps"]), r["statuses"], r["runs"]] for r in res]
    try:
        ac.write_table(a.out_dir, "latency-by-concurrency.csv", HEADER, table, synthetic, a.force)
        for r in res:
            print("SC-ROW mode=%s c=%d n=%d ok=%d failed=%d p50=%s p95=%s p99=%s rps=%s statuses=%s" % (r["mode"], r["concurrency"], r["n_total"], r["n_success"],
                  r["n_failed"], ac.fmt(r["p50"]), ac.fmt(r["p95"]), ac.fmt(r["p99"]), ac.fmt(r["rps"]), r["statuses"]))
        if not a.no_plot:
            first = res[0]["mode"]
            fused = [r for r in res if r["p50"] is not None and r["mode"] == first]
            xs = [r["concurrency"] for r in fused]
            ac.plot_lines(a.out_dir, "latency-by-concurrency.png", "Latency percentiles by concurrency level", "concurrency", "latency (ms)",
                          {"p50": (xs, [r["p50"] for r in fused]), "p95": (xs, [r["p95"] for r in fused]), "p99": (xs, [r["p99"] for r in fused])}, synthetic, a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
