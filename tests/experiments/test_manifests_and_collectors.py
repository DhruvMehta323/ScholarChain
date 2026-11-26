"""Manifests: schema validation, required cost and duration caps, manifest B gating. Collectors: dry-run with the stub aws only."""
import copy
import glob
import json
import os
import shutil

import pytest

from conftest import COLLECT, MANIFEST_DIR, STUB_BIN, VALIDATOR, clean_env, py


def load(name):
    with open(os.path.join(MANIFEST_DIR, name + ".json")) as f:
        return json.load(f)


def validate_copy(tmp_path, manifest, name=None, sub="m"):
    """Write one (possibly mutated) manifest into a temp dir and run the validator on it only."""
    d = tmp_path / sub
    d.mkdir(exist_ok=True)
    p = d / ((name or manifest["id"]) + ".json")
    p.write_text(json.dumps(manifest))
    return py(VALIDATOR, [str(p)])


def test_all_committed_manifests_validate():
    p = py(VALIDATOR, [])
    assert p.returncode == 0, p.stdout + p.stderr
    assert "RESULT PASSED (0 problem(s))" in p.stdout
    assert p.stdout.count("\nOK ") + p.stdout.startswith("OK ") == 6


def test_every_committed_manifest_has_positive_cost_and_duration_caps():
    # read independently of the validator
    letters = []
    total = 0.0
    for path in sorted(glob.glob(os.path.join(MANIFEST_DIR, "exp-*.json"))):
        m = json.load(open(path))
        letters.append(m["letter"])
        assert m["cost_cap_usd"] > 0 and m["duration_cap_seconds"] > 0 and m["max_runtime_seconds"] > 0, path
        total += m["cost_cap_usd"]
    assert sorted(letters) == list("ABCDEF")
    assert total <= 25.0


@pytest.mark.parametrize("letter_id", ["exp-a-baseline-10k", "exp-b-burst-50k", "exp-c-embedding-backlog", "exp-d-query-load",
                                       "exp-e-retrieval-modes", "exp-f-failure-recovery"])
def test_validator_rejects_manifest_missing_cost_cap(tmp_path, letter_id):
    m = load(letter_id)
    del m["cost_cap_usd"]
    p = validate_copy(tmp_path, m)
    assert p.returncode == 1, p.stdout
    assert "INVALID" in p.stdout and "cost_cap_usd" in p.stdout


@pytest.mark.parametrize("letter_id", ["exp-a-baseline-10k", "exp-b-burst-50k", "exp-c-embedding-backlog", "exp-d-query-load",
                                       "exp-e-retrieval-modes", "exp-f-failure-recovery"])
def test_validator_rejects_manifest_missing_duration_cap(tmp_path, letter_id):
    m = load(letter_id)
    del m["duration_cap_seconds"]
    p = validate_copy(tmp_path, m)
    assert p.returncode == 1, p.stdout
    assert "INVALID" in p.stdout and "duration_cap_seconds" in p.stdout


def test_validator_accepts_unmutated_copy_so_rejections_are_attributable(tmp_path):
    # control: the same copy procedure without a mutation passes, so the rejections above are caused by the mutation alone
    p = validate_copy(tmp_path, load("exp-d-query-load"))
    assert p.returncode == 0, p.stdout


def test_validator_rejects_zero_and_excessive_caps(tmp_path):
    m = load("exp-a-baseline-10k")
    m["cost_cap_usd"] = 0
    assert validate_copy(tmp_path, m, sub="m1").returncode == 1
    m = load("exp-a-baseline-10k")
    m["cost_cap_usd"] = 25.01
    assert validate_copy(tmp_path, m, sub="m2").returncode == 1
    m = load("exp-a-baseline-10k")
    m["duration_cap_seconds"] = 14401
    m["max_runtime_seconds"] = 14401
    assert validate_copy(tmp_path, m, sub="m3").returncode == 1
    m = load("exp-a-baseline-10k")
    m["duration_cap_seconds"] = m["max_runtime_seconds"] - 1  # the two must agree (run-experiment.sh enforces max_runtime_seconds)
    assert validate_copy(tmp_path, m, sub="m4").returncode == 1


def test_validator_rejects_loadgen_over_its_own_caps(tmp_path):
    m = load("exp-d-query-load")
    m["loadgen"]["max_model_calls_per_run"] = m["loadgen"]["max_requests_per_run"] * 3 - 1
    p = validate_copy(tmp_path, m)
    assert p.returncode == 1 and "max_model_calls_per_run" in p.stdout


def test_manifest_b_requires_the_10k_run_and_approval_in_committed_file():
    b = load("exp-b-burst-50k")
    assert b["scale"]["dblp_works"] == 50000
    assert any(r["experiment_id"] == "exp-a-baseline-10k" and r["status"] == "succeeded" for r in b["requires"]["prior_runs"])
    assert "--approve-50k" in b["requires"]["human_approval_flags"]
    assert b["requires"]["explicit_human_approval_required"] is True
    assert b["approved"] is False  # nothing is pre-approved by the harness


def test_validator_rejects_manifest_b_without_10k_prerequisite(tmp_path):
    b = load("exp-b-burst-50k")
    b["requires"]["prior_runs"] = []
    p = validate_copy(tmp_path, b)
    assert p.returncode == 1 and "experiment A" in p.stdout


def test_validator_rejects_manifest_b_without_approval_flag(tmp_path):
    b = load("exp-b-burst-50k")
    b["requires"]["human_approval_flags"] = []
    p = validate_copy(tmp_path, b, sub="m1")
    assert p.returncode == 1 and "--approve-50k" in p.stdout
    b = load("exp-b-burst-50k")
    b["requires"]["explicit_human_approval_required"] = False
    p = validate_copy(tmp_path, b, sub="m2")
    assert p.returncode == 1 and "explicit human approval" in p.stdout


def test_validator_rejects_wrong_scales(tmp_path):
    b = load("exp-b-burst-50k")
    b["scale"]["dblp_works"] = 10000
    assert validate_copy(tmp_path, b, sub="m1").returncode == 1
    a = load("exp-a-baseline-10k")
    a["scale"]["dblp_works"] = 50000
    assert validate_copy(tmp_path, a, sub="m2").returncode == 1


def test_validator_rejects_approved_without_approver(tmp_path):
    m = load("exp-c-embedding-backlog")
    m["approved"] = True
    m["approved_by"] = ""
    assert validate_copy(tmp_path, m).returncode == 1


def test_validator_rejects_incomplete_set_in_directory_mode(tmp_path):
    d = tmp_path / "set"
    d.mkdir()
    for path in glob.glob(os.path.join(MANIFEST_DIR, "exp-*.json")):
        if "exp-f-" not in path:
            shutil.copy(path, str(d))
    p = py(VALIDATOR, ["--dir", str(d)])
    assert p.returncode == 1 and "expected letters A-F" in p.stdout


# ---------------------------------------------------------------- collectors (dry-run with the stub aws only)

def stub_env(tmp_path):
    return clean_env({"SC_STUB_STATE": str(tmp_path / "stub-state")}, path=STUB_BIN + ":/usr/bin:/bin")


def run_cost(tmp_path, env, out_name="cost-out"):
    return py(os.path.join(COLLECT, "collect_cost.py"),
              ["--experiment-id", "exp-a-baseline-10k", "--run-id", "t1", "--start", "2026-11-29", "--end", "2026-12-01", "--dry-run",
               "--out-dir", str(tmp_path / out_name)], env=env), tmp_path / out_name


def test_collector_dry_run_with_stub_writes_only_synthetic_files_and_is_write_once(tmp_path):
    p, out = run_cost(tmp_path, stub_env(tmp_path))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "SC-DRYRUN" in p.stdout and "stub aws" in p.stdout
    files = sorted(os.listdir(str(out)))
    assert len(files) == 2 and all(n.startswith("SYNTHETIC-") for n in files), files  # raw JSON + meta
    meta = json.load(open(str(out / [n for n in files if n.endswith(".meta.json")][0])))
    assert meta["synthetic"] is True and meta["label"] == "SYNTHETIC" and meta["aws_binary_is_stub"] is True and meta["credits_excluded"] is True
    raw = out / [n for n in files if not n.endswith(".meta.json")][0]
    before = raw.read_bytes()
    p2, _ = run_cost(tmp_path, stub_env(tmp_path))  # same ids and out dir: must refuse, not overwrite
    assert p2.returncode == 3 and "overwrite" in p2.stderr
    assert raw.read_bytes() == before


def test_collector_dry_run_never_executes_a_non_stub_aws(tmp_path):
    fake = tmp_path / "fakebin"
    fake.mkdir()
    marker = tmp_path / "REAL-AWS-WAS-EXECUTED"
    a = fake / "aws"
    a.write_text("#!/bin/sh\ntouch '%s'\necho '{}'\n" % marker)
    os.chmod(str(a), 0o755)
    p, out = run_cost(tmp_path, clean_env(path=str(fake) + ":/usr/bin:/bin"))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "[not executed]" in p.stdout
    assert not marker.exists()
    assert not out.exists() or os.listdir(str(out)) == []


def test_collector_cost_filter_excludes_credits_and_refunds(tmp_path):
    p, out = run_cost(tmp_path, stub_env(tmp_path))
    assert p.returncode == 0
    line = [l for l in p.stdout.splitlines() if l.startswith("SC-DRYRUN")][0]
    assert "Credit" in line and "Refund" in line and "UnblendedCost" in line
