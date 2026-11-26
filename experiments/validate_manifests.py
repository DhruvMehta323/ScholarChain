#!/usr/bin/env python3
"""Validate experiment manifests against manifests/manifest.schema.json plus cross-field rules.

Usage: python3 -B experiments/validate_manifests.py [--dir experiments/manifests] [--budget FILE] [manifest.json ...]
Exit codes: 0 all valid, 1 at least one problem, 2 usage.
Cross-field rules: file name equals id + .json; duration_cap_seconds equals max_runtime_seconds; the loadgen block respects its own caps
(requests, model calls, model cost, duration, concurrency); manifest B requires the A run record and explicit human approval;
all six experiments A to F exist (directory mode); sums of model caps stay within the global model cap and of cost caps within the project budget.
"""
import argparse
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "common"))
import jsonschema_lite  # noqa: E402


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def check_manifest(path, m, schema, budget):
    errs = jsonschema_lite.validate(m, schema)
    if errs:
        return errs
    if os.path.basename(path) != m["id"] + ".json":
        errs.append("file name must be <id>.json (scripts/run-experiment.sh looks the manifest up by id)")
    if not m["id"].startswith("exp-%s-" % m["letter"].lower()):
        errs.append("id must start with exp-%s-" % m["letter"].lower())
    if m["duration_cap_seconds"] != m["max_runtime_seconds"]:
        errs.append("duration_cap_seconds must equal max_runtime_seconds (the value scripts/run-experiment.sh enforces)")
    if m.get("model_cost_cap_usd", 0) > m["cost_cap_usd"]:
        errs.append("model_cost_cap_usd exceeds cost_cap_usd")
    if m["approved"] and not m["approved_by"]:
        errs.append("approved is true but approved_by is empty")
    lg = m.get("loadgen")
    pra = budget["per_request_assumptions"]
    if lg:
        total = sum(l["requests"] for l in lg["levels"])
        if total > lg["max_requests_per_run"]:
            errs.append("sum of level requests %d exceeds max_requests_per_run %d" % (total, lg["max_requests_per_run"]))
        if lg["max_requests_per_run"] * pra["model_calls_per_request"] > lg["max_model_calls_per_run"]:
            errs.append("max_model_calls_per_run is below max_requests_per_run x model_calls_per_request")
        est = lg["max_requests_per_run"] * pra["estimated_cost_usd_per_request"]
        if est > lg["model_cost_cap_usd"] + 1e-9:
            errs.append("max_requests_per_run x estimated cost per request = %.4f exceeds loadgen.model_cost_cap_usd %.4f" % (est, lg["model_cost_cap_usd"]))
        if lg["model_cost_cap_usd"] > m.get("model_cost_cap_usd", 0) + 1e-9:
            errs.append("loadgen.model_cost_cap_usd exceeds the manifest model_cost_cap_usd")
        for l in lg["levels"]:
            if l["concurrency"] > lg["max_concurrency"]:
                errs.append("level concurrency %d above max_concurrency %d" % (l["concurrency"], lg["max_concurrency"]))
            if l["requests"] < l["concurrency"]:
                errs.append("level with concurrency %d has only %d requests" % (l["concurrency"], l["requests"]))
            if l.get("duration_seconds", 0) > lg["max_duration_seconds"]:
                errs.append("level duration above max_duration_seconds")
        if lg["max_duration_seconds"] > m["duration_cap_seconds"]:
            errs.append("loadgen.max_duration_seconds above duration_cap_seconds")
        if "benchmark" in lg and not lg["benchmark"].get("sha256"):
            errs.append("loadgen.benchmark.sha256 is required (source commit and checksum)")
    if m["letter"] == "B":
        req = m["requires"]
        if not any(r["experiment_id"].startswith("exp-a-") for r in req["prior_runs"]):
            errs.append("manifest B must require the experiment A run record")
        if "--approve-50k" not in req["human_approval_flags"] or not req.get("explicit_human_approval_required"):
            errs.append("manifest B must require explicit human approval (--approve-50k)")
        if m["scale"].get("dblp_works") != 50000:
            errs.append("manifest B scale must be 50000 works")
    if m["letter"] == "A" and m["scale"].get("dblp_works") != 10000:
        errs.append("manifest A scale must be 10000 works")
    return errs


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("files", nargs="*")
    ap.add_argument("--dir", default=os.path.join(HERE, "manifests"))
    ap.add_argument("--budget", default=os.path.join(HERE, "manifests", "model-budget.json"))
    ap.add_argument("--schema", default=os.path.join(HERE, "manifests", "manifest.schema.json"))
    a = ap.parse_args(argv)
    schema, budget = load(a.schema), load(a.budget)
    files = a.files or sorted(p for p in glob.glob(os.path.join(a.dir, "exp-*.json")))
    if not files:
        print("no manifests found", file=sys.stderr)
        return 2
    bad, manifests = 0, []
    for p in files:
        try:
            m = load(p)
        except (OSError, ValueError) as e:
            print("INVALID %s: cannot read (%s)" % (p, e))
            bad += 1
            continue
        errs = check_manifest(p, m, schema, budget)
        if errs:
            bad += 1
            print("INVALID %s" % p)
            for e in errs:
                print("  - " + e)
        else:
            manifests.append(m)
            print("OK %s (letter %s, cost cap %.2f USD, duration cap %d s, approved=%s)" % (p, m["letter"], m["cost_cap_usd"], m["duration_cap_seconds"], m["approved"]))
    if not a.files:
        letters = sorted(m["letter"] for m in manifests)
        if letters != list("ABCDEF"):
            bad += 1
            print("INVALID set: expected letters A-F, got %s" % letters)
    if manifests:
        s_model = sum(m.get("model_cost_cap_usd", 0) for m in manifests)
        s_cost = sum(m["cost_cap_usd"] for m in manifests)
        print("SUM model caps %.2f USD (limit %.2f), cost caps %.2f USD (project budget %.2f)" % (s_model, budget["global_model_cap_usd"], s_cost, budget["project_budget_usd"]))
        if s_model > budget["global_model_cap_usd"] or s_cost > budget["project_budget_usd"]:
            bad += 1
            print("INVALID: caps exceed the global model cap or the project budget")
    print("RESULT %s (%d problem(s))" % ("FAILED" if bad else "PASSED", bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
