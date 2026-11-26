#!/usr/bin/env python3
"""Writes SYNTHETIC fixture files for exercising the analysis scripts offline. Every number is INVENTED (seeded), none is a result.

Usage: python3 -B experiments/analysis/make_synthetic_fixtures.py [--out-dir experiments/analysis/fixtures] [--seed 441]
Files (all named SYNTHETIC-*, each carries synthetic=true in its rows or header; existing files are never overwritten):
  SYNTHETIC-latency-handcheck.csv            10 rows, latencies 10..100 ms: nearest-rank p50=50, p95=100, p99=100 by hand
  SYNTHETIC-loadgen-c{1,4,8,16}-fused.csv    loadgen-format rows, seeded latencies growing with concurrency, a few 429/502 rows
  SYNTHETIC-retrieval-handcheck.csv          3 questions with hand-computable recall@K, MRR, nDCG (labels in the companion json)
  SYNTHETIC-retrieval-handcheck-benchmark.json
  SYNTHETIC-modes-{fused,graph-only,vector-only}.csv   rows for the 20 SYNTHETIC-benchmark questions (load-tests/testing)
  SYNTHETIC-samples-scale.jsonl              sample_resources-format samples: ECS scale out and in, queue drain, EMR requested vs running
  SYNTHETIC-pipeline-{10k,50k}-run.json      run.json-format records
  SYNTHETIC-cost-{10k,50k}.json              collect_cost-format cost files
"""
import argparse
import csv
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "common"))
import sc_common  # noqa: E402

COLS = ["run_id", "experiment_id", "synthetic", "concurrency", "mode", "seq", "question_id", "category", "t_start_epoch_ms", "t_start_utc", "latency_ms",
        "http_status", "outcome", "error_class", "trace_id", "query_id", "response_mode", "abstained", "claims_count", "claims_verified", "returned_researcher_ids",
        "model_calls_assumed", "model_calls_observed", "explain_http_status", "stage_latency_ms_json", "bytes"]
T0 = 1795600000.0  # fixed invented clock (2026-11-25), not a measurement


def row(run, exp, conc, mode, seq, qid, cat, t, lat, status, ids=""):
    ok = status == 200
    r = dict.fromkeys(COLS, "")
    r.update(run_id=run, experiment_id=exp, synthetic="true", concurrency=conc, mode=mode, seq=seq, question_id=qid, category=cat,
             t_start_epoch_ms=int(t * 1000), t_start_utc=sc_common.utc_iso(t), latency_ms="%.3f" % lat, http_status=status,
             outcome="ok" if ok else "http_error", error_class="" if ok else "http_%d" % status, trace_id="SYNTHETIC-%s-%d" % (run, seq),
             returned_researcher_ids=ids, model_calls_assumed=3, bytes=0)
    return r


def write_csv(path, rows):
    with sc_common.open_new(path, newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    print("SC-FIXTURE %s" % path)


def write_json(path, obj):
    sc_common.write_json_new(path, obj)
    print("SC-FIXTURE %s" % path)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out-dir", default=os.path.join(HERE, "fixtures"))
    ap.add_argument("--seed", type=int, default=441)
    a = ap.parse_args(argv)
    rng = random.Random(a.seed)
    o = a.out_dir
    try:
        write_csv(os.path.join(o, "SYNTHETIC-latency-handcheck.csv"),
                  [row("synth-hand", "SYNTHETIC", 1, "fused", i + 1, "H%d" % i, "x", T0 + i, 10.0 * (i + 1), 200) for i in range(10)])
        for conc, n in ((1, 20), (4, 24), (8, 24), (16, 32)):
            rows = []
            for i in range(n):
                lat = rng.uniform(80, 140) * (1 + 0.15 * conc) + (400 if i % 11 == 10 else 0)
                status = 429 if (conc >= 8 and i % 9 == 8) else (502 if i % 17 == 16 else 200)
                rows.append(row("synth-d", "SYNTHETIC-exp-d", conc, "fused", i + 1, "SYNTH-Q%02d" % (i % 20 + 1), "topic", T0 + i * (1.0 / conc) * 0.9, lat, status))
            write_csv(os.path.join(o, "SYNTHETIC-loadgen-c%d-fused.csv" % conc), rows)
        hb = {"synthetic": True, "label": "SYNTHETIC hand-check labels", "questions": [
            {"id": "HQ1", "category": "t", "question": "q", "expected": {"facts": [{"kind": "identity", "subject": "a"}]}},
            {"id": "HQ2", "category": "t", "question": "q", "expected": {"facts": [{"kind": "identity", "subject": "b"}, {"kind": "identity", "subject": "c"}, {"kind": "metrics", "subject": "zzz"}]}},
            {"id": "HQ3", "category": "t", "question": "q", "expected": {"facts": [{"kind": "identity", "subject": "d"}]}},
            {"id": "HQ4", "category": "t", "question": "q", "expected": {"abstain": True, "facts": []}}]}
        write_json(os.path.join(o, "SYNTHETIC-retrieval-handcheck-benchmark.json"), hb)
        hr = [row("synth-hand", "SYNTHETIC", 1, "fused", 1, "HQ1", "t", T0, 50, 200, "x;a;y"), row("synth-hand", "SYNTHETIC", 1, "fused", 2, "HQ2", "t", T0 + 1, 50, 200, "b;x;c"),
              row("synth-hand", "SYNTHETIC", 1, "fused", 3, "HQ3", "t", T0 + 2, 50, 503, ""), row("synth-hand", "SYNTHETIC", 1, "fused", 4, "HQ4", "t", T0 + 3, 50, 200, "x")]
        write_csv(os.path.join(o, "SYNTHETIC-retrieval-handcheck.csv"), hr)
        for mode, p_first, p_in in (("fused", 0.7, 0.9), ("graph-only", 0.5, 0.7), ("vector-only", 0.3, 0.5)):
            rows = []
            for i in range(20):
                rel = "researcher:sc1:SYNTH%02d" % (i + 1)
                ranked = ["researcher:sc1:NOISE%d" % j for j in range(4)]
                u = rng.random()
                if u < p_first:
                    ranked.insert(0, rel)
                elif u < p_in:
                    ranked.insert(rng.randint(1, 3), rel)
                rows.append(row("synth-e", "SYNTHETIC-exp-e", 2, mode, i + 1, "SYNTH-Q%02d" % (i + 1), ["coauthor_breadth", "shortest_path", "topic", "community", "prompt_injection"][(i + 1) % 5],
                                T0 + i, rng.uniform(100, 300), 200, ";".join(ranked)))
            write_csv(os.path.join(o, "SYNTHETIC-modes-%s.csv" % mode), rows)
        # samples every 15 s for 12.5 minutes (51 samples): ECS scale out/in, queue drain, EMR requested vs running
        path = os.path.join(o, "SYNTHETIC-samples-scale.jsonl")
        with sc_common.open_new(path, newline="\n") as f:
            for i in range(51):
                t = T0 + 15 * i
                qa = [0, 1, 1, 2, 3, 4, 4, 4, 3, 2, 1, 1, 0]
                worker = [0, 0, 1, 2, 2, 3, 4, 4, 4, 3, 2, 1, 0]
                backlog = max(0, 1000 - 60 * max(0, i - 3) ** 1.0 * (1 + 0.2 * min(i, 10))) if i >= 3 else (1000 if i >= 1 else 0)
                req = 1 + (1 if 10 <= i < 34 else 0)
                run = 1 + (1 if 14 <= i < 38 else 0)
                f.write(json.dumps({"synthetic": True, "t_epoch": t, "t_utc": sc_common.utc_iso(t),
                                    "ecs": {"query-api": {"desired": qa[min(i // 4, 12)], "running": qa[min(i // 4, 12)], "pending": 0},
                                            "embedding-worker": {"desired": worker[min(i // 4, 12)], "running": worker[min(i // 4, 12)], "pending": 0}},
                                    "sqs": {"queue": {"visible": int(backlog), "not_visible": 0, "delayed": 0}, "dlq": {"visible": 1 if i > 30 else 0, "not_visible": 0, "delayed": 0}},
                                    "emr": {"cluster_state": "RUNNING", "groups": {"MASTER": {"requested": 1, "running": 1, "state": "RUNNING"},
                                                                                    "CORE": {"requested": req, "running": run, "state": "RUNNING"}}},
                                    "errors": []}, sort_keys=True) + "\n")
        print("SC-FIXTURE %s" % path)
        for scale, k in (("10k", 1), ("50k", 4)):
            steps = [("hadoop", 600 * k), ("spark", 420 * k), ("load", 0), ("enqueue", 0)]
            timings, cur = [], T0
            for name, d in steps:
                if d:
                    timings.append({"id": "s-SYNTH-%s" % name, "name": name, "state": "COMPLETED", "start": sc_common.utc_iso(cur), "end": sc_common.utc_iso(cur + d)})
                    cur += d + 30
            write_json(os.path.join(o, "SYNTHETIC-pipeline-%s-run.json" % scale),
                       {"synthetic": True, "scale": scale, "run_id": "SYNTHETIC-%s" % scale, "status": "succeeded", "cluster_id": "j-SYNTHETIC", "started_at": sc_common.utc_iso(T0 - 120),
                        "updated_at": sc_common.utc_iso(cur + 60), "step_timings": timings})
            write_json(os.path.join(o, "SYNTHETIC-cost-%s.json" % scale),
                       {"SYNTHETIC": True, "ResultsByTime": [{"TimePeriod": {"Start": "2026-11-25", "End": "2026-11-26"},
                                                              "Groups": [{"Keys": ["Amazon Elastic MapReduce"], "Metrics": {"UnblendedCost": {"Amount": "%.4f" % (0.5 * k), "Unit": "USD"}}},
                                                                         {"Keys": ["Amazon Simple Storage Service"], "Metrics": {"UnblendedCost": {"Amount": "%.4f" % (0.01 * k), "Unit": "USD"}}}]}]})
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
