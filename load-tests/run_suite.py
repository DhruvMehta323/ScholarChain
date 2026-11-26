#!/usr/bin/env python3
"""Run every level of a manifest's loadgen block, one loadgen.py invocation per level, in order.

Usage: python3 -B load-tests/run_suite.py --manifest experiments/manifests/exp-d-query-load.json [--run-id RUN] [--benchmark FILE]
           [--base-url URL] [--expect-host HOST] [--allow-insecure-http] [--fetch-explain] [--out-dir DIR] [--dry-run]
Environment: the same names as loadgen.py (SC_LOADTEST_API_KEY_FILE, SC_LOADTEST_BASE_URL, SC_LOADTEST_EXPECT_HOST, SC_RESULTS_ROOT).
  Because scripts/run-experiment.sh runs the manifest command verbatim (no extra arguments) and does not export its run id, --run-id and --benchmark
  fall back to SC_RUN_ID and SC_LOADTEST_BENCHMARK. The human sets SC_RUN_ID to the same token passed to run-experiment.sh --run-id.
Target rules are those of loadgen.py: for a non-loopback target --ledger and --budget-file are refused, the manifest must validate and be approved,
and a synthetic benchmark is refused. They are checked once here BEFORE level 1 (and again by every loadgen invocation).
Stops at the first level that does not exit 0 (kept and reported); levels already written are never touched.
Exit code: 0 all levels completed, otherwise the exit code of the first failing level.
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import loadgen  # noqa: E402  (shared target rules)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--run-id", default=os.environ.get("SC_RUN_ID", ""))
    ap.add_argument("--benchmark", default=os.environ.get("SC_LOADTEST_BENCHMARK", ""))
    ap.add_argument("--base-url")
    ap.add_argument("--expect-host")
    ap.add_argument("--allow-insecure-http", action="store_true")
    ap.add_argument("--fetch-explain", action="store_true")
    ap.add_argument("--out-dir")
    ap.add_argument("--ledger", help="LOOPBACK ONLY")
    ap.add_argument("--budget-file", help="LOOPBACK ONLY")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    if not a.run_id or not a.benchmark:
        print("SC-REFUSED --run-id (or SC_RUN_ID) and --benchmark (or SC_LOADTEST_BENCHMARK) are required")
        return 2
    with open(a.manifest, "r", encoding="utf-8") as f:
        m = json.load(f)
    lg = m["loadgen"]
    base = a.base_url or os.environ.get("SC_LOADTEST_BASE_URL", "")
    try:
        probe = argparse.Namespace(base_url=base, ledger=a.ledger, budget_file=a.budget_file)
        bench_sha = bench_synth = None
        if not loadgen.is_loopback_url(base):
            with open(a.benchmark, "r", encoding="utf-8") as f:
                bj = json.load(f)
            bench_synth = bool(isinstance(bj, dict) and bj.get("synthetic") is True)
            bench_sha = loadgen.sha256_file(a.benchmark)
        loadgen.enforce_target_rules(probe, m, a.manifest, bench_synth, bench_sha, (lg.get("benchmark") or {}).get("sha256"))
    except loadgen.Refused as e:
        print("SC-REFUSED %s" % e)
        return e.code
    for i, lv in enumerate(lg["levels"], start=1):
        mode = lv.get("mode", lg.get("mode", "fused"))
        cmd = [sys.executable, "-B", os.path.join(HERE, "loadgen.py"), "--manifest", a.manifest, "--run-id", a.run_id,
               "--concurrency", str(lv["concurrency"]), "--max-requests", str(lv["requests"]), "--benchmark", a.benchmark,
               "--mode", mode, "--ramp", str(lg.get("ramp_seconds", 0))]
        if "duration_seconds" in lv:
            cmd += ["--duration", str(lv["duration_seconds"])]
        for flag, val in (("--base-url", a.base_url), ("--expect-host", a.expect_host), ("--out-dir", a.out_dir), ("--ledger", a.ledger), ("--budget-file", a.budget_file)):
            if val:
                cmd += [flag, val]
        for flag, on in (("--allow-insecure-http", a.allow_insecure_http), ("--fetch-explain", a.fetch_explain), ("--dry-run", a.dry_run)):
            if on:
                cmd.append(flag)
        print("SC-SUITE level %d/%d concurrency=%d requests=%d mode=%s" % (i, len(lg["levels"]), lv["concurrency"], lv["requests"], mode))
        sys.stdout.flush()
        rc = subprocess.call(cmd)
        if rc != 0:
            print("SC-SUITE stopped: level %d exited %d (rows written so far are kept)" % (i, rc))
            return rc
    print("SC-SUITE all %d levels completed" % len(lg["levels"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
