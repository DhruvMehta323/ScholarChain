"""M4: scripts/run-experiment.sh runs a manifest's `command` verbatim and does not pass its run id, so the commands must work from environment
fallbacks (SC_RUN_ID, SC_LOADTEST_BENCHMARK) and must not hardcode a run id.

Commands are run exactly as committed (program swapped for this interpreter), from the repository root, with outputs under a tmp SC_RESULTS_ROOT,
against a 127.0.0.1 fake service (D, E) or the repository stub aws (F). A, B, C run bash pipeline scripts that call AWS: they are only checked statically.
"""
import glob
import json
import os
import sys

import pytest

from conftest import DUMMY_KEY, MANIFEST_DIR, REPO, STUB_BIN, SYN_BENCH, clean_env, py
import subprocess

RUN_SUITE = os.path.join(REPO, "load-tests", "run_suite.py")


def committed(letter):
    path = glob.glob(os.path.join(MANIFEST_DIR, "exp-%s-*.json" % letter.lower()))[0]
    with open(path) as f:
        return json.load(f)


def run_command(cmd, env, extra=()):
    assert cmd[0] == "python3"
    return subprocess.run([sys.executable] + cmd[1:] + list(extra), capture_output=True, text=True, env=env, cwd=REPO, timeout=180)


def test_no_committed_manifest_command_hardcodes_a_run_id():
    paths = sorted(glob.glob(os.path.join(MANIFEST_DIR, "exp-*.json")))
    assert len(paths) == 6
    for path in paths:
        with open(path) as f:
            cmd = json.load(f)["command"]
        assert not [c for c in cmd if c == "--run-id" or c.startswith("--run-id=")], (path, cmd)


@pytest.mark.parametrize("letter,levels", [("d", 4), ("e", 3)])
def test_load_manifest_command_completes_with_run_id_and_benchmark_from_environment_only(tmp_path, service, keyfile, letter, levels):
    svc = service(latency=0.01)
    results = tmp_path / "results"
    env = clean_env({"SC_RUN_ID": "m4env", "SC_LOADTEST_BENCHMARK": SYN_BENCH, "SC_LOADTEST_BASE_URL": svc.url, "SC_LOADTEST_API_KEY_FILE": keyfile,
                     "SC_RESULTS_ROOT": str(results)})
    cmd = committed(letter)["command"]
    assert "--run-id" not in cmd and "--benchmark" not in cmd  # so everything below comes from the environment
    p = run_command(cmd, env, extra=["--allow-insecure-http"])  # the one flag with no environment fallback (a deliberate per-run decision)
    assert p.returncode == 0, p.stdout + p.stderr
    assert "SC-SUITE all %d levels completed" % levels in p.stdout
    exp = committed(letter)["id"]
    metas = glob.glob(os.path.join(str(results), exp, "m4env", "SYNTHETIC-loadgen-m4env-*.meta.json"))
    assert len(metas) == levels, metas
    assert svc.post_count == sum(l["requests"] for l in committed(letter)["loadgen"]["levels"])
    assert DUMMY_KEY not in p.stdout + p.stderr


@pytest.mark.parametrize("have_run_id,have_bench", [(False, False), (True, False), (False, True)])
def test_run_suite_without_run_id_or_benchmark_environment_exits_2(tmp_path, service, keyfile, have_run_id, have_bench):
    svc = service(latency=0.01)
    extra = {"SC_LOADTEST_BASE_URL": svc.url, "SC_LOADTEST_API_KEY_FILE": keyfile, "SC_RESULTS_ROOT": str(tmp_path / "results")}
    if have_run_id:
        extra["SC_RUN_ID"] = "m4x"
    if have_bench:
        extra["SC_LOADTEST_BENCHMARK"] = SYN_BENCH
    p = run_command(committed("d")["command"], clean_env(extra), extra=["--allow-insecure-http"])
    assert p.returncode == 2 and "SC-REFUSED" in p.stdout and "SC_RUN_ID" in p.stdout, p.stdout + p.stderr
    assert svc.post_count == 0 and not (tmp_path / "results").exists()


def test_failure_drill_manifest_command_uses_sc_run_id_from_environment(tmp_path):
    cmd = committed("f")["command"]
    assert "--run-id" not in cmd
    stub_env = {"SC_STUB_STATE": str(tmp_path / "stub-state"), "SC_RESULTS_ROOT": str(tmp_path / "results"), "AWS_REGION": "us-east-2"}
    p = run_command(cmd, clean_env(dict(stub_env, SC_RUN_ID="m4f"), path=STUB_BIN + ":/usr/bin:/bin"))
    assert p.returncode == 0, p.stdout + p.stderr
    recs = glob.glob(os.path.join(str(tmp_path / "results"), "exp-f-failure-recovery", "m4f", "SYNTHETIC-failure_drill-m4f-record.jsonl"))
    assert len(recs) == 1, p.stdout
    # and without SC_RUN_ID the same command is refused (exit 2), making no aws call
    p2 = run_command(cmd, clean_env(dict(stub_env, SC_STUB_STATE=str(tmp_path / "stub-state2")), path=STUB_BIN + ":/usr/bin:/bin"))
    assert p2.returncode == 2 and "--run-id (or SC_RUN_ID) is required" in p2.stderr
    assert not (tmp_path / "stub-state2").exists()


def test_failure_drill_manifest_command_is_a_plan_not_a_live_run():
    cmd = committed("f")["command"]
    assert "--dry-run" in cmd and "--execute" not in cmd and "--yes" not in cmd  # a live drill is a separate, human-typed command
