"""M1: rules for a NON-loopback target. Every refusal must happen before any request, before any file is written and before the API key is read.

No server is needed: the host is example.invalid (never resolves) and --expect-host matches it, so the host check passes and only the
target rules under test can refuse. The key file env var points at a missing path or a mode-644 file: if the key were read the exit code would
be 6, so exit 3 proves it was not read. Plus the cost_report rule that a synthetic label cannot hide a non-loopback run.
"""
import json
import os
import shutil

import pytest

from conftest import LOADGEN, MANIFEST_DIR, REPO, SYN_BENCH, ANALYSIS, clean_env, py

RUN_SUITE = os.path.join(REPO, "load-tests", "run_suite.py")
BUDGET = os.path.join(MANIFEST_DIR, "model-budget.json")
COMMITTED_D = os.path.join(MANIFEST_DIR, "exp-d-query-load.json")
URL = "https://example.invalid"
HOST = "example.invalid"


def d_manifest(tmp_path, approved=True, mutate=None, sub="m"):
    with open(COMMITTED_D) as f:
        m = json.load(f)
    if approved:
        m["approved"], m["approved_by"] = True, "test-human"
    if mutate:
        mutate(m)
    d = tmp_path / sub
    d.mkdir(exist_ok=True)
    p = d / "exp-d-query-load.json"  # validate_manifests requires the file name to be <id>.json
    p.write_text(json.dumps(m))
    return str(p)


def real_looking_benchmark(tmp_path):
    p = tmp_path / "bench-real-looking.json"
    p.write_text(json.dumps({"questions": [{"id": "Q1", "category": "c", "question": "who?"}]}))  # not synthetic, SHA differs from the manifest
    return str(p)


@pytest.fixture(params=["missing-key-file", "mode-644-key-file"])
def key_env(request, tmp_path):
    if request.param == "missing-key-file":
        return {"SC_LOADTEST_API_KEY_FILE": str(tmp_path / "no-such-dir" / "no-such-key")}
    d = tmp_path / "keydir"
    d.mkdir()
    k = d / "k.txt"
    k.write_text("SCTEST-DUMMY-NOT-A-KEY\n")
    os.chmod(str(k), 0o644)
    return {"SC_LOADTEST_API_KEY_FILE": str(k)}


def assert_clean_refusal(p, tmp_path, results, out_dir, ledger, needle):
    assert p.returncode == 3, "expected exit 3 (refused before the key), got %s: %s%s" % (p.returncode, p.stdout, p.stderr)
    assert "SC-REFUSED" in p.stdout and needle in p.stdout, p.stdout
    assert "key file" not in p.stdout  # the key rule (exit 6) was never reached
    assert not os.path.exists(out_dir), "refusal must write no raw files"
    assert not os.path.exists(ledger), "refusal must not create or touch a ledger"
    assert os.listdir(results) == [], os.listdir(results)  # SC_RESULTS_ROOT stayed empty


def loadgen_run(tmp_path, key_env, manifest, bench=SYN_BENCH, extra=()):
    results = tmp_path / "results"
    results.mkdir(exist_ok=True)
    out_dir, ledger = str(tmp_path / "out"), str(tmp_path / "ledger.jsonl")
    args = ["--manifest", manifest, "--run-id", "m1", "--concurrency", "1", "--base-url", URL, "--expect-host", HOST, "--benchmark", bench]
    args += list(extra)
    env = clean_env(dict(key_env, SC_RESULTS_ROOT=str(results)))
    p = py(LOADGEN, args, env=env)
    return p, str(results), out_dir, ledger


def test_loadgen_refuses_ledger_for_non_loopback(tmp_path, key_env):
    ledger = str(tmp_path / "ledger.jsonl")
    p, results, out_dir, _ = loadgen_run(tmp_path, key_env, d_manifest(tmp_path), extra=["--ledger", ledger, "--out-dir", str(tmp_path / "out")])
    assert_clean_refusal(p, tmp_path, results, out_dir, ledger, "--ledger and --budget-file are only allowed for a loopback target")


def test_loadgen_refuses_budget_file_for_non_loopback(tmp_path, key_env):
    bf = str(tmp_path / "budget-copy.json")
    shutil.copy(BUDGET, bf)
    p, results, out_dir, ledger = loadgen_run(tmp_path, key_env, d_manifest(tmp_path), extra=["--budget-file", bf, "--out-dir", str(tmp_path / "out")])
    assert_clean_refusal(p, tmp_path, results, out_dir, ledger, "--ledger and --budget-file are only allowed for a loopback target")


def test_loadgen_refuses_unapproved_committed_manifest(tmp_path, key_env):
    p, results, out_dir, ledger = loadgen_run(tmp_path, key_env, COMMITTED_D, extra=["--out-dir", str(tmp_path / "out")])
    assert_clean_refusal(p, tmp_path, results, out_dir, ledger, "the manifest is not approved")


def test_loadgen_refuses_approved_without_approver(tmp_path, key_env):
    m = d_manifest(tmp_path, approved=False, mutate=lambda m: m.update({"approved": True, "approved_by": ""}))
    p, results, out_dir, ledger = loadgen_run(tmp_path, key_env, m, extra=["--out-dir", str(tmp_path / "out")])
    assert_clean_refusal(p, tmp_path, results, out_dir, ledger, "validate_manifests")  # approved with an empty approver fails the validator first


def test_loadgen_refuses_approved_copy_missing_cost_cap_schema(tmp_path, key_env):
    m = d_manifest(tmp_path, mutate=lambda m: m.pop("cost_cap_usd"))
    p, results, out_dir, ledger = loadgen_run(tmp_path, key_env, m, extra=["--out-dir", str(tmp_path / "out")])
    assert_clean_refusal(p, tmp_path, results, out_dir, ledger, "does not pass experiments/validate_manifests.py")
    assert "cost_cap_usd" in p.stdout


def test_loadgen_refuses_synthetic_benchmark(tmp_path, key_env):
    p, results, out_dir, ledger = loadgen_run(tmp_path, key_env, d_manifest(tmp_path), bench=SYN_BENCH, extra=["--out-dir", str(tmp_path / "out")])
    assert_clean_refusal(p, tmp_path, results, out_dir, ledger, "a SYNTHETIC benchmark is never sent to a non-loopback host")


def test_loadgen_refuses_benchmark_sha_mismatch(tmp_path, key_env):
    p, results, out_dir, ledger = loadgen_run(tmp_path, key_env, d_manifest(tmp_path), bench=real_looking_benchmark(tmp_path),
                                              extra=["--out-dir", str(tmp_path / "out")])
    assert_clean_refusal(p, tmp_path, results, out_dir, ledger, "does not equal the manifest loadgen.benchmark.sha256")


def test_loadgen_loopback_control_still_accepts_ledger_flag(tmp_path, service, keyfile):
    # control: the same --ledger flag is fine for 127.0.0.1, so the refusals above are caused by the non-loopback target alone
    svc = service(latency=0.01)
    out = tmp_path / "out"
    p = py(LOADGEN, ["--manifest", COMMITTED_D, "--run-id", "ctl", "--concurrency", "1", "--max-requests", "1", "--base-url", svc.url,
                     "--allow-insecure-http", "--benchmark", SYN_BENCH, "--out-dir", str(out), "--ledger", str(tmp_path / "l.jsonl")],
           env=clean_env({"SC_LOADTEST_API_KEY_FILE": keyfile, "SC_RESULTS_ROOT": str(tmp_path / "res")}))
    assert p.returncode == 0, p.stdout + p.stderr
    assert svc.post_count == 1


# ---------------------------------------------------------------- run_suite.py: the same rules, checked once BEFORE level 1

def suite_run(tmp_path, key_env, manifest, bench=SYN_BENCH, extra=()):
    results = tmp_path / "results"
    results.mkdir(exist_ok=True)
    args = ["--manifest", manifest, "--run-id", "m1s", "--benchmark", bench, "--base-url", URL, "--expect-host", HOST]
    args += list(extra)
    p = py(RUN_SUITE, args, env=clean_env(dict(key_env, SC_RESULTS_ROOT=str(results))))
    return p, str(results), str(tmp_path / "out"), str(tmp_path / "ledger.jsonl")


def assert_suite_refusal(p, results, out_dir, ledger, needle):
    assert p.returncode == 3, "expected exit 3, got %s: %s%s" % (p.returncode, p.stdout, p.stderr)
    assert "SC-REFUSED" in p.stdout and needle in p.stdout, p.stdout
    assert "SC-SUITE level" not in p.stdout, "no level may start"
    assert not os.path.exists(out_dir) and not os.path.exists(ledger) and os.listdir(results) == []


def test_run_suite_refuses_ledger_for_non_loopback(tmp_path, key_env):
    ledger = str(tmp_path / "ledger.jsonl")
    p, results, out_dir, _ = suite_run(tmp_path, key_env, d_manifest(tmp_path), extra=["--ledger", ledger, "--out-dir", str(tmp_path / "out")])
    assert_suite_refusal(p, results, out_dir, ledger, "--ledger and --budget-file are only allowed for a loopback target")


def test_run_suite_refuses_budget_file_for_non_loopback(tmp_path, key_env):
    p, results, out_dir, ledger = suite_run(tmp_path, key_env, d_manifest(tmp_path), extra=["--budget-file", BUDGET, "--out-dir", str(tmp_path / "out")])
    assert_suite_refusal(p, results, out_dir, ledger, "--ledger and --budget-file are only allowed for a loopback target")


def test_run_suite_refuses_synthetic_benchmark(tmp_path, key_env):
    p, results, out_dir, ledger = suite_run(tmp_path, key_env, d_manifest(tmp_path), bench=SYN_BENCH, extra=["--out-dir", str(tmp_path / "out")])
    assert_suite_refusal(p, results, out_dir, ledger, "a SYNTHETIC benchmark is never sent to a non-loopback host")


def test_run_suite_refuses_benchmark_sha_mismatch(tmp_path, key_env):
    p, results, out_dir, ledger = suite_run(tmp_path, key_env, d_manifest(tmp_path), bench=real_looking_benchmark(tmp_path),
                                            extra=["--out-dir", str(tmp_path / "out")])
    assert_suite_refusal(p, results, out_dir, ledger, "does not equal the manifest loadgen.benchmark.sha256")


def test_run_suite_refuses_unapproved_committed_manifest(tmp_path, key_env):
    p, results, out_dir, ledger = suite_run(tmp_path, key_env, COMMITTED_D, extra=["--out-dir", str(tmp_path / "out")])
    assert_suite_refusal(p, results, out_dir, ledger, "the manifest is not approved")


def test_run_suite_refuses_manifest_missing_cost_cap(tmp_path, key_env):
    p, results, out_dir, ledger = suite_run(tmp_path, key_env, d_manifest(tmp_path, mutate=lambda m: m.pop("cost_cap_usd")),
                                            extra=["--out-dir", str(tmp_path / "out")])
    assert_suite_refusal(p, results, out_dir, ledger, "does not pass experiments/validate_manifests.py")


# ---------------------------------------------------------------- cost_report: a synthetic label cannot hide a non-loopback run

def write_meta(root, exp, run, name, base_url, usd, synthetic=True):
    d = os.path.join(str(root), exp, run)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, name), "w") as f:
        json.dump({"synthetic": synthetic, "base_url": base_url, "model_cost_estimated_usd": usd}, f)


def run_cost_report(tmp_path, results, extra=(), out="out"):
    p = py(os.path.join(ANALYSIS, "cost_report.py"), ["--results", str(results), "--out-dir", str(tmp_path / out), "--no-plot"] + list(extra),
           env=clean_env({"SC_RESULTS_ROOT": str(tmp_path / "unused-root")}))
    import csv
    rows = []
    for n in os.listdir(str(tmp_path / out)) if os.path.isdir(str(tmp_path / out)) else []:
        if n.endswith(".csv"):
            with open(str(tmp_path / out / n), newline="") as f:
                rows = list(csv.DictReader(f))
    return p, {r["experiment_id"]: r for r in rows}


def test_cost_report_counts_synthetic_labeled_non_loopback_meta_as_real_and_flags_it(tmp_path):
    res = tmp_path / "res"
    write_meta(res, "exp-d-query-load", "r1", "SYNTHETIC-loadgen-r1-c1-fused.meta.json", "https://example.invalid", 0.5, synthetic=True)  # mislabeled
    write_meta(res, "exp-d-query-load", "r2", "SYNTHETIC-loadgen-r2-c1-fused.meta.json", "http://127.0.0.1:9", 0.25, synthetic=True)  # honest loopback
    p, rows = run_cost_report(tmp_path, res)
    assert p.returncode == 0, p.stdout + p.stderr
    d = rows["exp-d-query-load"]
    assert d["n_real_loadgen_runs"] == "1" and float(d["model_estimate_usd"]) == pytest.approx(0.5)  # counted as real
    assert "non_loopback_meta_labeled_synthetic:SYNTHETIC-loadgen-r1-c1-fused.meta.json" in d["over_cap"]  # flagged
    assert d["n_synthetic_loadgen_runs"] == "0" and float(d["synthetic_model_estimate_usd"]) == 0.0  # the loopback one stays ignored


def test_cost_report_mislabeled_stays_real_even_with_include_synthetic(tmp_path):
    res = tmp_path / "res"
    write_meta(res, "exp-d-query-load", "r1", "SYNTHETIC-loadgen-r1-c1-fused.meta.json", "https://example.invalid", 0.5, synthetic=True)
    write_meta(res, "exp-d-query-load", "r2", "SYNTHETIC-loadgen-r2-c1-fused.meta.json", "http://127.0.0.1:9", 0.25, synthetic=True)
    p, rows = run_cost_report(tmp_path, res, extra=["--include-synthetic"])
    assert p.returncode == 0, p.stdout + p.stderr
    d = rows["exp-d-query-load"]
    assert d["n_real_loadgen_runs"] == "1" and float(d["model_estimate_usd"]) == pytest.approx(0.5)
    assert d["n_synthetic_loadgen_runs"] == "1" and float(d["synthetic_model_estimate_usd"]) == pytest.approx(0.25)
    assert "non_loopback_meta_labeled_synthetic" in d["over_cap"]


def test_cost_report_control_honest_real_meta_is_not_flagged(tmp_path):
    res = tmp_path / "res"
    write_meta(res, "exp-d-query-load", "r1", "loadgen-r1-c1-fused.meta.json", "https://example.invalid", 0.5, synthetic=False)
    p, rows = run_cost_report(tmp_path, res)
    assert p.returncode == 0, p.stdout + p.stderr
    d = rows["exp-d-query-load"]
    assert d["n_real_loadgen_runs"] == "1" and d["over_cap"] == "no"
