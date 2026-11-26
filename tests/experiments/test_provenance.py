"""M6: every analysis output has a `<output>.provenance.json` sidecar (input paths + sha256, argv/parameters, git head, synthetic flag), covered by
write-once. L9: everything under output/SYNTHETIC-examples is SYNTHETIC- prefixed and synthetic=true.
"""
import csv
import hashlib
import json
import os
import subprocess

import pytest

from conftest import ANALYSIS, REPO, clean_env, py

FIX = os.path.join(ANALYSIS, "fixtures")
EXAMPLES = os.path.join(ANALYSIS, "output", "SYNTHETIC-examples")
SCRIPTS = os.path.join(ANALYSIS)


def f(name):
    return os.path.join(FIX, name)


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        h.update(fh.read())
    return h.hexdigest()


def git_head():
    return subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()


# name -> (script, argv builder, expected input fixture files, expected argv fragment, expected data outputs)
CASES = {
    "latency": ("latency_percentiles.py", lambda out: [f("SYNTHETIC-latency-handcheck.csv"), "--out-dir", out],
                [f("SYNTHETIC-latency-handcheck.csv")], "SYNTHETIC-latency-handcheck.csv", ["SYNTHETIC-latency-by-concurrency.csv"]),
    "retrieval": ("retrieval_metrics.py", lambda out: [f("SYNTHETIC-retrieval-handcheck.csv"), "--benchmark", f("SYNTHETIC-retrieval-handcheck-benchmark.json"),
                                                      "--k", "1,3", "--out-dir", out],
                  [f("SYNTHETIC-retrieval-handcheck.csv")], "--k", ["SYNTHETIC-retrieval-metrics.csv"]),
    "queue": ("queue_drain.py", lambda out: [f("SYNTHETIC-samples-scale.jsonl"), "--out-dir", out],
              [f("SYNTHETIC-samples-scale.jsonl")], "SYNTHETIC-samples-scale.jsonl", ["SYNTHETIC-queue-summary.csv", "SYNTHETIC-queue-timeline.csv"]),
    "scale-timeline": ("scale_timeline.py", lambda out: [f("SYNTHETIC-samples-scale.jsonl"), "--out-dir", out],
                       [f("SYNTHETIC-samples-scale.jsonl")], "SYNTHETIC-samples-scale.jsonl",
                       ["SYNTHETIC-scale-events.csv", "SYNTHETIC-scale-summary.csv", "SYNTHETIC-task-counts-timeline.csv"]),
    "scale-comparison": ("scale_comparison.py", lambda out: ["--a-run", f("SYNTHETIC-pipeline-10k-run.json"), "--b-run", f("SYNTHETIC-pipeline-50k-run.json"),
                                                            "--a-cost", f("SYNTHETIC-cost-10k.json"), "--b-cost", f("SYNTHETIC-cost-50k.json"), "--out-dir", out],
                         [f("SYNTHETIC-pipeline-10k-run.json"), f("SYNTHETIC-pipeline-50k-run.json"), f("SYNTHETIC-cost-10k.json"), f("SYNTHETIC-cost-50k.json")],
                         "--a-run", ["SYNTHETIC-scale-comparison.csv"]),
    "emr": ("emr_capacity.py", lambda out: [f("SYNTHETIC-samples-scale.jsonl"), "--out-dir", out],
            [f("SYNTHETIC-samples-scale.jsonl")], "SYNTHETIC-samples-scale.jsonl",
            ["SYNTHETIC-emr-capacity-timeline.csv", "SYNTHETIC-emr-capacity-events.csv", "SYNTHETIC-emr-capacity-summary.csv"]),
    "cost": ("cost_report.py", lambda out: ["--cost", "exp-a-baseline-10k=" + f("SYNTHETIC-cost-10k.json"), "--out-dir", out],
             [f("SYNTHETIC-cost-10k.json")], "--cost", ["SYNTHETIC-cost-per-experiment.csv"]),
}


def run_case(tmp_path, key, out_name="out", extra=()):
    script, build, *_ = CASES[key]
    out = str(tmp_path / out_name)
    env = clean_env({"SC_RESULTS_ROOT": str(tmp_path / "res"), "MPLCONFIGDIR": str(tmp_path / "mpl")})
    p = py(os.path.join(SCRIPTS, script), build(out) + ["--no-plot"] + list(extra), env=env)
    return p, out


def abs_input(path):
    return path if os.path.isabs(path) else os.path.join(REPO, path)


@pytest.mark.parametrize("key", sorted(CASES))
def test_every_analysis_script_writes_a_provenance_sidecar_with_matching_hashes(tmp_path, key):
    script, build, inputs, argv_fragment, outputs = CASES[key]
    p, out = run_case(tmp_path, key)
    assert p.returncode == 0, p.stdout + p.stderr
    for name in outputs:
        assert os.path.exists(os.path.join(out, name)), (name, os.listdir(out))
        side = os.path.join(out, name + ".provenance.json")
        assert os.path.exists(side), "no sidecar for " + name
        prov = json.load(open(side))
        assert prov["output"] == name and prov["output_sha256"] == sha(os.path.join(out, name))  # describes this very file
        assert prov["script"] == "experiments/analysis/" + script
        recorded = {abs_input(i["path"]): i["sha256"] for i in prov["inputs"]}
        for src in inputs:  # every input is recorded and its sha256 equals the real file's
            assert recorded.get(src) == sha(src), (src, recorded)
        assert all(i["sha256"] == sha(abs_input(i["path"])) for i in prov["inputs"])
        assert isinstance(prov["argv"], list) and any(argv_fragment in a for a in prov["argv"]), prov["argv"]
        assert prov["git_head"] == git_head() and len(prov["git_head"]) == 40
        assert prov["synthetic"] is True  # SYNTHETIC- fixtures in
        assert "parameters" in prov and prov["parameters"].get("out_dir") == out
    # every file the script wrote is either a sidecar or has one
    names = os.listdir(out)
    for n in names:
        if not n.endswith(".provenance.json"):
            assert n + ".provenance.json" in names, n


def test_retrieval_sidecar_records_k_and_benchmark_hash(tmp_path):
    p, out = run_case(tmp_path, "retrieval")
    assert p.returncode == 0, p.stdout + p.stderr
    prov = json.load(open(os.path.join(out, "SYNTHETIC-retrieval-metrics.csv.provenance.json")))
    assert prov["parameters"]["k"] == "1,3"
    assert prov["argv"][prov["argv"].index("--k") + 1] == "1,3"
    assert prov["benchmark_sha256"] == sha(f("SYNTHETIC-retrieval-handcheck-benchmark.json"))


def test_real_inputs_give_unlabeled_sidecar_with_synthetic_false(tmp_path):
    from test_analysis import run_latency  # real-looking raw.csv
    p, out = run_latency(tmp_path, name="raw.csv")
    assert p.returncode == 0, p.stdout + p.stderr
    prov = json.load(open(str(out / "latency-by-concurrency.csv.provenance.json")))
    assert prov["synthetic"] is False
    assert prov["inputs"][0]["sha256"] == sha(str(tmp_path / "raw.csv"))
    assert prov["inputs"][0]["path"] == str(tmp_path / "raw.csv") or os.path.realpath(prov["inputs"][0]["path"]) == os.path.realpath(str(tmp_path / "raw.csv"))


@pytest.mark.parametrize("key", sorted(CASES))
def test_sidecar_is_write_once_and_force_replaces_both(tmp_path, key):
    script, build, inputs, argv_fragment, outputs = CASES[key]
    p, out = run_case(tmp_path, key)
    assert p.returncode == 0, p.stdout + p.stderr
    first = {n: open(os.path.join(out, n), "rb").read() for n in os.listdir(out)}
    p2, _ = run_case(tmp_path, key)
    assert p2.returncode == 3 and "refusing to overwrite" in p2.stderr, p2.stdout + p2.stderr
    assert {n: open(os.path.join(out, n), "rb").read() for n in os.listdir(out)} == first  # neither data file nor sidecar touched
    p3, _ = run_case(tmp_path, key, extra=["--force"])
    assert p3.returncode == 0, p3.stdout + p3.stderr
    side = os.path.join(out, outputs[0] + ".provenance.json")
    new = json.load(open(side))
    assert "--force" in new["argv"]  # the sidecar was replaced together with the output, not left stale
    assert json.loads(first[outputs[0] + ".provenance.json"].decode())["argv"] != new["argv"]
    assert new["output_sha256"] == sha(os.path.join(out, outputs[0]))


def test_orphan_sidecar_alone_blocks_a_rewrite_and_no_half_pair_is_created(tmp_path):
    p, out = run_case(tmp_path, "latency")
    assert p.returncode == 0
    os.remove(os.path.join(out, "SYNTHETIC-latency-by-concurrency.csv"))  # the sidecar remains
    before = open(os.path.join(out, "SYNTHETIC-latency-by-concurrency.csv.provenance.json"), "rb").read()
    p2, _ = run_case(tmp_path, "latency")
    assert p2.returncode == 3 and "provenance.json" in p2.stderr
    assert not os.path.exists(os.path.join(out, "SYNTHETIC-latency-by-concurrency.csv"))
    assert open(os.path.join(out, "SYNTHETIC-latency-by-concurrency.csv.provenance.json"), "rb").read() == before


def test_plots_get_sidecars_when_matplotlib_is_available(tmp_path):
    script, build, *_ = CASES["latency"]
    out = str(tmp_path / "plots")
    env = clean_env({"SC_RESULTS_ROOT": str(tmp_path / "res"), "MPLCONFIGDIR": str(tmp_path / "mpl")})
    p = py(os.path.join(SCRIPTS, script), build(out), env=env)
    assert p.returncode == 0, p.stdout + p.stderr
    names = os.listdir(out)
    assert "SYNTHETIC-latency-by-concurrency.csv.provenance.json" in names
    for n in names:
        if n.endswith(".png"):  # produced only when matplotlib imports; then it must carry provenance too
            assert n + ".provenance.json" in names
    if "SC-INFO plot skipped" in p.stdout:
        assert not [n for n in names if n.endswith(".png")]


# ---------------------------------------------------------------- L9: the committed examples directory

def test_every_file_and_directory_under_synthetic_examples_has_the_synthetic_prefix():
    seen = 0
    for root, dirs, files in os.walk(EXAMPLES):
        for n in dirs + files:
            seen += 1
            assert n.startswith("SYNTHETIC-"), "unlabeled entry in the synthetic examples: %s" % os.path.join(root, n)
    assert seen > 20


def test_synthetic_example_tables_have_only_synthetic_true_rows_and_sidecars_say_synthetic():
    csvs = 0
    for root, _dirs, files in os.walk(EXAMPLES):
        for n in files:
            full = os.path.join(root, n)
            if n.endswith(".csv"):
                csvs += 1
                with open(full, newline="") as fh:
                    rows = list(csv.DictReader(fh))
                assert rows, full
                if "synthetic" in rows[0]:
                    assert all(r["synthetic"] == "true" for r in rows), full
                assert os.path.exists(full + ".provenance.json"), "no sidecar for " + full
            elif n.endswith(".provenance.json"):
                assert json.load(open(full))["synthetic"] is True, full
    assert csvs >= 10


def test_every_synthetic_example_csv_has_a_synthetic_column():
    # a table without the column could not be checked above: require the column on every example table
    for root, _dirs, files in os.walk(EXAMPLES):
        for n in files:
            if n.endswith(".csv"):
                with open(os.path.join(root, n), newline="") as fh:
                    assert "synthetic" in next(csv.reader(fh)), n


def test_example_sidecar_hashes_describe_the_committed_files():
    for root, _dirs, files in os.walk(EXAMPLES):
        for n in files:
            if n.endswith(".provenance.json"):
                prov = json.load(open(os.path.join(root, n)))
                target = os.path.join(root, prov["output"])
                assert os.path.exists(target), (n, prov["output"])
                assert prov["output_sha256"] == sha(target), n
