"""Analysis scripts on hand-computed fixtures: percentiles, scale events, retrieval metrics, write-once and synthetic labeling."""
import csv
import json
import math
import os

import pytest

from conftest import ANALYSIS, clean_env, py
import metrics  # experiments/analysis/metrics.py (pure functions), path set in conftest

LAT_HEADER = ["run_id", "experiment_id", "synthetic", "concurrency", "mode", "seq", "t_start_epoch_ms", "latency_ms", "http_status", "outcome",
              "error_class"]
BASE = 1795600000000


def write_latency_csv(path, rows, synthetic="false"):
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(LAT_HEADER)
        for i, (conc, lat, status, outcome, start) in enumerate(rows, 1):
            w.writerow(["r1", "e", synthetic, conc, "fused", i, start, lat, status, outcome, "" if outcome == "ok" else "http_%s" % status])


def read_table(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def latency_fixture():
    # Concurrency 4: 20 successes with latencies 10,20,...,200 (listed shuffled) all started at BASE, plus one 429 (500 ms) started at BASE+2000.
    lats = [130, 10, 200, 60, 170, 40, 90, 20, 150, 110, 80, 190, 30, 160, 50, 120, 70, 180, 100, 140]
    rows = [(4, l, 200, "ok", BASE) for l in lats]
    rows.append((4, 500, 429, "http_error", BASE + 2000))
    # Concurrency 2: four successes 5, 15, 25, 35 spread over 3 s.
    rows += [(2, 25, 200, "ok", BASE), (2, 5, 200, "ok", BASE + 1000), (2, 35, 200, "ok", BASE + 2000), (2, 15, 200, "ok", BASE + 3000)]
    return rows


def run_latency(tmp_path, name="raw.csv", extra=()):
    raw = tmp_path / name
    write_latency_csv(str(raw), latency_fixture(), synthetic="true" if name.startswith("SYNTHETIC-") else "false")
    out = tmp_path / "out"
    p = py(os.path.join(ANALYSIS, "latency_percentiles.py"), [str(raw), "--out-dir", str(out), "--no-plot"] + list(extra))
    return p, out


def test_latency_percentiles_match_hand_computation(tmp_path):
    # Hand computation (nearest rank = ceil(p/100 * n)), c=4, n_success=20 sorted 10..200 step 10:
    #   p50 rank 10 -> 100; p95 rank 19 -> 190; p99 rank ceil(19.8)=20 -> 200; mean = (10+...+200)/20 = 2100/20 = 105
    #   error_rate = 1 failed / 21 total = 0.0476; wall = max(end) - min(start) = (BASE+2000+500) - BASE = 2.5 s; rps = 20 / 2.5 = 8.0
    # c=2, sorted 5,15,25,35 (n=4): p50 rank 2 -> 15; p95 rank ceil(3.8)=4 -> 35; p99 rank 4 -> 35; ends: BASE+3000+15 -> wall 3.015 s.
    p, out = run_latency(tmp_path)
    assert p.returncode == 0, p.stdout + p.stderr
    rows = {int(r["concurrency"]): r for r in read_table(str(out / "latency-by-concurrency.csv"))}
    c4 = rows[4]
    assert (c4["n_total"], c4["n_success"], c4["n_failed"]) == ("21", "20", "1")
    assert float(c4["p50_ms"]) == 100.0 and float(c4["p95_ms"]) == 190.0 and float(c4["p99_ms"]) == 200.0
    assert float(c4["min_ms"]) == 10.0 and float(c4["max_ms"]) == 200.0 and float(c4["mean_ms"]) == 105.0
    assert float(c4["error_rate"]) == pytest.approx(1 / 21.0, abs=1e-4)
    assert float(c4["wall_seconds"]) == pytest.approx(2.5, abs=1e-3)
    assert float(c4["throughput_rps"]) == pytest.approx(8.0, abs=1e-3)
    assert c4["status_counts"] == "200:20;429:1"
    c2 = rows[2]
    assert (float(c2["p50_ms"]), float(c2["p95_ms"]), float(c2["p99_ms"])) == (15.0, 35.0, 35.0)
    assert float(c2["error_rate"]) == 0.0


def test_failed_requests_are_counted_not_dropped(tmp_path):
    # the 429 row (latency 500) must not enter the percentiles (max success is 200) but must be counted in n_total and the error rate
    p, out = run_latency(tmp_path)
    c4 = [r for r in read_table(str(out / "latency-by-concurrency.csv")) if r["concurrency"] == "4"][0]
    assert float(c4["max_ms"]) == 200.0 and c4["n_failed"] == "1" and float(c4["error_rate"]) > 0


def test_percentile_function_hand_cases():
    vals = list(range(1, 101))  # 1..100: nearest rank p -> value p
    assert metrics.percentile(vals, 50) == 50 and metrics.percentile(vals, 95) == 95 and metrics.percentile(vals, 99) == 99
    assert metrics.percentile(vals, 100) == 100
    assert metrics.percentile([7, 3, 9], 99) == 9  # n < 100: p99 is the maximum
    assert metrics.percentile([7, 3, 9], 50) == 7  # rank ceil(1.5)=2 of [3,7,9]
    assert metrics.percentile([], 50) is None
    with pytest.raises(ValueError):
        metrics.percentile([1], 0)


def test_scale_event_detection_function_on_unordered_timeline():
    # counts 1,1,2,3,3,2,1 at t=0,15,...,90 (shuffled on input): out at 30 (1->2), out at 45 (2->3), in at 75 (3->2), in at 90 (2->1)
    samples = [(75, 2), (0, 1), (45, 3), (15, 1), (90, 1), (30, 2), (60, 3)]
    ev = metrics.detect_scale_events(samples)
    assert [(e["t"], e["from"], e["to"], e["direction"]) for e in ev] == [(30, 1, 2, "out"), (45, 2, 3, "out"), (75, 3, 2, "in"), (90, 2, 1, "in")]
    assert metrics.detect_scale_events([(0, 2), (15, 2), (30, 2)]) == []
    assert metrics.detect_scale_events([]) == []


def sample(t, query_running, worker_running=0):
    return {"ecs": {"query-api": {"desired": query_running, "pending": 0, "running": query_running},
                    "embedding-worker": {"desired": worker_running, "pending": 0, "running": worker_running}},
            "t_epoch": t, "t_utc": "2026-01-01T00:00:00.000Z", "errors": []}


def test_scale_timeline_script_detects_events(tmp_path):
    # query-api running counts at t0+0,15,...,105: 1,1,2,3,3,2,1,1 -> events at rel 30 (1->2 out), 45 (2->3 out), 75 (3->2 in), 90 (2->1 in)
    t0 = 1795600000.0
    counts = [1, 1, 2, 3, 3, 2, 1, 1]
    src = tmp_path / "samples.jsonl"
    src.write_text("\n".join(json.dumps(sample(t0 + 15 * i, c)) for i, c in enumerate(counts)) + "\n")
    out = tmp_path / "out"
    p = py(os.path.join(ANALYSIS, "scale_timeline.py"), [str(src), "--out-dir", str(out), "--no-plot"])
    assert p.returncode == 0, p.stdout + p.stderr
    ev = [r for r in read_table(str(out / "scale-events.csv"))]
    assert [(r["service"], r["t_rel_s"], r["from"], r["to"], r["direction"]) for r in ev] == [
        ("query-api", "30.0", "1", "2", "out"), ("query-api", "45.0", "2", "3", "out"),
        ("query-api", "75.0", "3", "2", "in"), ("query-api", "90.0", "2", "1", "in")]  # embedding-worker stayed 0: no events
    summ = {r["service"]: r for r in read_table(str(out / "scale-summary.csv"))}
    assert (summ["query-api"]["min_running"], summ["query-api"]["max_running"]) == ("1", "3")
    assert (summ["query-api"]["scale_out_events"], summ["query-api"]["scale_in_events"]) == ("2", "2")
    assert (summ["embedding-worker"]["scale_out_events"], summ["embedding-worker"]["scale_in_events"]) == ("0", "0")
    assert len(read_table(str(out / "task-counts-timeline.csv"))) == 16  # 8 samples x 2 services


def test_hand_computed_retrieval_metrics_functions():
    # Q1 relevant {a}, ranked [x, a, y]:      recall@1 = 0, recall@3 = 1, RR = 1/2,
    #     nDCG@3 = DCG/IDCG = (1/log2(3)) / (1/log2(2)) = 0.6309298 / 1 = 0.6309298
    # Q2 relevant {b, c}, ranked [b, x, c]:   recall@1 = 1/2, recall@3 = 2/2, RR = 1,
    #     DCG = 1/log2(2) + 1/log2(4) = 1 + 0.5 = 1.5; IDCG = 1 + 1/log2(3) = 1.6309298; nDCG@3 = 0.9197208
    # Q3 relevant {d}, ranked [x, y, z]:      everything 0
    assert metrics.recall_at_k(["x", "a", "y"], ["a"], 1) == 0.0
    assert metrics.recall_at_k(["x", "a", "y"], ["a"], 3) == 1.0
    assert metrics.reciprocal_rank(["x", "a", "y"], ["a"]) == 0.5
    assert metrics.ndcg_at_k(["x", "a", "y"], ["a"], 3) == pytest.approx(0.6309298, abs=1e-6)
    assert metrics.recall_at_k(["b", "x", "c"], ["b", "c"], 1) == 0.5
    assert metrics.recall_at_k(["b", "x", "c"], ["b", "c"], 3) == 1.0
    assert metrics.reciprocal_rank(["b", "x", "c"], ["b", "c"]) == 1.0
    assert metrics.ndcg_at_k(["b", "x", "c"], ["b", "c"], 3) == pytest.approx(0.9197208, abs=1e-6)
    assert metrics.recall_at_k(["x", "y", "z"], ["d"], 3) == 0.0
    assert metrics.reciprocal_rank(["x", "y", "z"], ["d"]) == 0.0
    assert metrics.ndcg_at_k(["x", "y", "z"], ["d"], 3) == 0.0
    # a perfect list has nDCG exactly 1; a duplicate must not be counted twice: ranked [a, a], relevant {a, b}, k=2
    #   DCG = 1 (second a ignored); IDCG = 1 + 1/log2(3) = 1.6309298; nDCG = 0.6131472
    assert metrics.ndcg_at_k(["a", "b"], ["a", "b"], 2) == pytest.approx(1.0)
    assert metrics.ndcg_at_k(["a", "a"], ["a", "b"], 2) == pytest.approx(1 / (1 + 1 / math.log2(3)), abs=1e-9)
    # empty label set means "not scorable", never a fake zero or one
    assert metrics.recall_at_k(["a"], [], 3) is None and metrics.reciprocal_rank(["a"], []) is None and metrics.ndcg_at_k(["a"], [], 3) is None


def bench(synthetic=False):
    def q(i, subs, cat="t"):
        return {"id": i, "category": cat, "question": "q",
                "expected": {"facts": [{"kind": "identity", "subject": s} for s in subs] + [{"kind": "metrics", "subject": "zzz"}]}}
    b = {"questions": [q("Q1", ["a"]), q("Q2", ["b", "c"]), q("Q3", ["d"]), q("Q4", ["e"]), {"id": "Q5", "category": "t", "question": "q", "expected": {"abstain": True}}]}
    if synthetic:
        b["synthetic"] = True
    return b


def retrieval_rows():
    hdr = ["run_id", "synthetic", "mode", "category", "question_id", "outcome", "returned_researcher_ids", "http_status", "latency_ms", "t_start_epoch_ms"]
    rows = [["r", "false", "fused", "t", "Q1", "ok", "x;a;y", 200, 1, 1], ["r", "false", "fused", "t", "Q2", "ok", "b;x;c", 200, 1, 1],
            ["r", "false", "fused", "t", "Q3", "ok", "x;y;z", 200, 1, 1], ["r", "false", "fused", "t", "Q4", "http_error", "", 500, 1, 1],
            ["r", "false", "fused", "t", "Q5", "ok", "a", 200, 1, 1]]
    return hdr, rows


def run_retrieval(tmp_path, csv_name="raw.csv", synthetic_bench=False, extra=()):
    hdr, rows = retrieval_rows()
    raw = tmp_path / csv_name
    with open(str(raw), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(hdr)
        w.writerows(rows)
    bp = tmp_path / "bench.json"
    bp.write_text(json.dumps(bench(synthetic_bench)))
    out = tmp_path / "out"
    p = py(os.path.join(ANALYSIS, "retrieval_metrics.py"), [str(raw), "--benchmark", str(bp), "--k", "1,3", "--out-dir", str(out)] + list(extra))
    return p, out


def test_retrieval_metrics_script_matches_hand_computation(tmp_path):
    # Scored: Q1, Q2, Q3 answered; Q4 (relevant {e}) failed with HTTP 500; Q5 has no identity subject (not scored).
    # ANSWERED (3 scored): MRR = (0.5 + 1 + 0)/3 = 0.5; recall@1 = (0 + 0.5 + 0)/3 = 0.1667; recall@3 = (1 + 1 + 0)/3 = 0.6667;
    #                      nDCG@3 = (0.6309298 + 0.9197208 + 0)/3 = 1.5506506/3 = 0.5168835
    # STRICT (4 scored, the failed request counts 0): MRR = 1.5/4 = 0.375; recall@1 = 0.5/4 = 0.125; recall@3 = 2/4 = 0.5;
    #                      nDCG@3 = 1.5506506/4 = 0.3876627
    p, out = run_retrieval(tmp_path)
    assert p.returncode == 0, p.stdout + p.stderr
    allrow = [r for r in read_table(str(out / "retrieval-metrics.csv")) if r["category"] == "ALL"][0]
    assert (allrow["n_rows"], allrow["n_unlabeled"], allrow["n_scored_strict"], allrow["n_failed"], allrow["n_scored_answered"]) == ("5", "1", "4", "1", "3")
    exp = {"answered_mrr": 0.5, "answered_recall@1": 0.1667, "answered_recall@3": 0.6667, "answered_ndcg@3": 0.5169,
           "strict_mrr": 0.375, "strict_recall@1": 0.125, "strict_recall@3": 0.5, "strict_ndcg@3": 0.3877}
    for k, v in exp.items():
        assert float(allrow[k]) == pytest.approx(v, abs=1e-4), k


def test_analysis_outputs_are_write_once_and_force_replaces_derived_only(tmp_path):
    p, out = run_retrieval(tmp_path)
    assert p.returncode == 0
    target = out / "retrieval-metrics.csv"
    before = target.read_bytes()
    p2, _ = run_retrieval(tmp_path)  # same output dir again, no --force
    assert p2.returncode == 3 and "overwrite" in p2.stderr
    assert target.read_bytes() == before
    p3, _ = run_retrieval(tmp_path, extra=["--force"])
    assert p3.returncode == 0


def test_latency_output_refuses_overwrite(tmp_path):
    p, out = run_latency(tmp_path)
    assert p.returncode == 0
    f = out / "latency-by-concurrency.csv"
    before = f.read_bytes()
    p2, _ = run_latency(tmp_path)
    assert p2.returncode == 3
    assert f.read_bytes() == before


def test_synthetic_input_gives_synthetic_labeled_outputs(tmp_path):
    p, out = run_latency(tmp_path, name="SYNTHETIC-raw.csv")
    assert p.returncode == 0, p.stdout + p.stderr
    files = os.listdir(str(out))
    assert sorted(files) == ["SYNTHETIC-latency-by-concurrency.csv", "SYNTHETIC-latency-by-concurrency.csv.provenance.json"]
    rows = read_table(str(out / "SYNTHETIC-latency-by-concurrency.csv"))
    assert rows and all(r["synthetic"] == "true" for r in rows)


def test_real_looking_input_is_not_labeled_synthetic(tmp_path):
    # guards against the label being applied unconditionally: a non-synthetic raw file must give unlabeled names and synthetic=false
    p, out = run_latency(tmp_path, name="raw.csv")
    assert p.returncode == 0
    # M6: the data file plus exactly its provenance sidecar, and neither carries the SYNTHETIC label
    assert sorted(os.listdir(str(out))) == ["latency-by-concurrency.csv", "latency-by-concurrency.csv.provenance.json"]
    assert all(r["synthetic"] == "false" for r in read_table(str(out / "latency-by-concurrency.csv")))
    side = json.load(open(str(out / "latency-by-concurrency.csv.provenance.json")))
    assert side["synthetic"] is False and side["output"] == "latency-by-concurrency.csv"


def test_synthetic_benchmark_labels_retrieval_outputs(tmp_path):
    p, out = run_retrieval(tmp_path, synthetic_bench=True)
    assert p.returncode == 0, p.stdout + p.stderr
    # M6: the data file plus exactly its provenance sidecar, both SYNTHETIC-labeled
    assert sorted(os.listdir(str(out))) == ["SYNTHETIC-retrieval-metrics.csv", "SYNTHETIC-retrieval-metrics.csv.provenance.json"]
    assert all(r["synthetic"] == "true" for r in read_table(str(out / "SYNTHETIC-retrieval-metrics.csv")))
    side = json.load(open(str(out / "SYNTHETIC-retrieval-metrics.csv.provenance.json")))
    assert side["synthetic"] is True and side["output"] == "SYNTHETIC-retrieval-metrics.csv"


def test_committed_synthetic_fixtures_and_examples_are_labeled():
    root = os.path.join(ANALYSIS)
    for sub in ("fixtures", os.path.join("output", "SYNTHETIC-examples")):
        d = os.path.join(root, sub)
        for n in sorted(os.listdir(d)):
            full = os.path.join(d, n)
            if os.path.isdir(full):
                continue
            if sub == "fixtures":
                assert n.startswith("SYNTHETIC-"), "unlabeled fixture: " + n
            elif n.endswith(".csv") and n.startswith("SYNTHETIC-"):
                rows = read_table(full)
                assert rows and all(r["synthetic"] == "true" for r in rows), n
