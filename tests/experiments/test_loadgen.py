"""Load generator: concurrency, duration, request and model-call caps; secrecy of the API key; write-once raw files; synthetic labeling."""
import hashlib
import json
import os
import socket

from conftest import DUMMY_KEY, MANIFEST_DIR, SYN_BENCH


def all_text(run):
    """stdout + stderr + every byte of every file the run created + the ledger."""
    blobs = [run.stdout, run.stderr]
    for n in run.files():
        with open(os.path.join(run.out_dir, n), "rb") as f:
            blobs.append(f.read().decode("utf-8", "replace"))
    if os.path.exists(run.ledger):
        with open(run.ledger, "rb") as f:
            blobs.append(f.read().decode("utf-8", "replace"))
    return "\n".join(blobs)


def test_request_cap_exact_count(service, make_manifest, loadgen):
    # concurrency 4, CLI cap 7: exactly 7 requests must reach the server and 7 rows (seq 1..7) must be written.
    svc = service(latency=0.05)
    r = loadgen(make_manifest(), svc.url, 4, extra=["--max-requests", "7"])
    assert r.rc == 0, r.stdout + r.stderr
    assert svc.post_count == 7
    assert sorted(int(x["seq"]) for x in r.csv_rows()) == list(range(1, 8))
    meta = r.meta()
    assert meta["stop_reason"] == "request_cap"
    assert meta["requests_started"] == 7 and meta["requests_finished"] == 7


def test_concurrency_is_honored_at_three(service, make_manifest, loadgen):
    # 12 requests of 0.15 s at concurrency 3: the peak in flight seen by the server is exactly 3.
    svc = service(latency=0.15)
    r = loadgen(make_manifest(), svc.url, 3, extra=["--max-requests", "12"])
    assert r.rc == 0, r.stdout + r.stderr
    assert svc.post_count == 12
    assert svc.max_inflight == 3


def test_concurrency_one_is_serial(service, make_manifest, loadgen):
    svc = service(latency=0.05)
    r = loadgen(make_manifest(), svc.url, 1, extra=["--max-requests", "5"])
    assert r.rc == 0, r.stdout + r.stderr
    assert svc.post_count == 5
    assert svc.max_inflight == 1


def test_duration_cap_stops_the_run(service, make_manifest, loadgen):
    # 0.25 s per request, 2 workers, 1 s duration, request cap 40: the duration, not the request cap, must stop it.
    svc = service(latency=0.25)
    r = loadgen(make_manifest(max_requests_per_run=50, max_model_calls_per_run=150, model_cost_cap_usd=2.0), svc.url, 2,
                extra=["--max-requests", "40", "--duration", "1.0"])
    assert r.rc == 0, r.stdout + r.stderr
    meta = r.meta()
    assert meta["stop_reason"] == "duration_cap"
    assert 4 <= svc.post_count < 20
    assert meta["wall_seconds"] < 5.0
    assert len(r.csv_rows()) == svc.post_count


def test_model_call_cap_stops_the_run(service, make_manifest, loadgen):
    # 3 model calls per request are assumed (model-budget.json); a cap of 7 calls allows floor(7/3) = 2 requests.
    svc = service(latency=0.02)
    r = loadgen(make_manifest(max_requests_per_run=10, max_model_calls_per_run=7), svc.url, 2)
    assert r.rc == 0, r.stdout + r.stderr
    assert svc.post_count == 2
    meta = r.meta()
    assert meta["stop_reason"] == "model_call_cap"
    assert meta["model_calls_reserved"] == 6
    assert len(r.csv_rows()) == 2


def test_model_cost_cap_is_enforced(service, make_manifest, loadgen):
    # per request 0.02101 USD assumed: 2 requests = 0.04202 fit under 0.05, 3 requests = 0.06303 do not (refused up front).
    svc = service(latency=0.02)
    r = loadgen(make_manifest(max_requests_per_run=2, max_model_calls_per_run=100, model_cost_cap_usd=0.05), svc.url, 1)
    assert r.rc == 0, r.stdout + r.stderr
    assert svc.post_count == 2
    r2 = loadgen(make_manifest(max_requests_per_run=3, max_model_calls_per_run=100, model_cost_cap_usd=0.05), svc.url, 1, run_id="t2",
                 out_name="out2")
    assert r2.rc == 3
    assert svc.post_count == 2


def test_command_line_cannot_raise_caps(service, make_manifest, loadgen):
    svc = service()
    m = make_manifest()
    r1 = loadgen(m, svc.url, 1, extra=["--max-requests", "43"])  # manifest cap is 42
    assert r1.rc == 3 and "may only lower" in r1.stdout
    r2 = loadgen(m, svc.url, 17, run_id="t2")  # manifest max_concurrency is 16
    assert r2.rc == 3 and "SC-REFUSED" in r2.stdout
    assert "--concurrency 17 exceeds the manifest cap 16" in r2.stdout and "may only lower" in r2.stdout
    r3 = loadgen(m, svc.url, 1, run_id="t3", extra=["--duration", "99999"])
    assert r3.rc == 3 and "SC-REFUSED" in r3.stdout
    assert "--duration 99999.0 exceeds the manifest cap 1500" in r3.stdout and "may only lower" in r3.stdout
    assert svc.post_count == 0


def test_ledger_over_limit_refuses_to_start(service, make_manifest, loadgen, tmp_path):
    svc = service()
    (tmp_path / "ledger.jsonl").write_text(json.dumps({"event": "reserve", "key": "old", "usd": 1.79}) + "\n")
    r = loadgen(make_manifest(), svc.url, 1, extra=["--max-requests", "5"])
    assert r.rc == 3 and "ledger" in r.stdout
    assert svc.post_count == 0 and r.files() == []


def test_plain_http_needs_explicit_flag(service, make_manifest, loadgen):
    svc = service()
    r = loadgen(make_manifest(), svc.url, 1, extra=["--max-requests", "1"], insecure=False)
    assert r.rc == 3 and "allow-insecure-http" in r.stdout
    assert svc.post_count == 0


def test_non_loopback_host_is_refused_without_matching_expect_host(make_manifest, loadgen):
    # the key must never be sent to a host that is not the expected Terraform host; no connection is attempted
    r = loadgen(make_manifest(), "https://example.invalid", 1, extra=["--max-requests", "1"])
    assert r.rc == 3 and "never sent to another host" in r.stdout
    assert r.files() == []


def test_key_never_in_output_or_raw_files(service, make_manifest, loadgen):
    svc = service(latency=0.02)
    r = loadgen(make_manifest(), svc.url, 3, extra=["--max-requests", "6"])
    assert r.rc == 0, r.stdout + r.stderr
    assert len(svc.keys_seen) == 6 and set(svc.keys_seen) == {DUMMY_KEY}  # non-vacuous: the key really was sent in the header
    text = all_text(r)
    assert DUMMY_KEY not in text
    assert "SCTEST-DUMMY" not in text
    assert r.meta()["key_source_env_var"] == "SC_LOADTEST_API_KEY_FILE"  # the variable NAME is recorded, never the value


def test_key_not_leaked_when_server_rejects_it(service, make_manifest, loadgen):
    svc = service(latency=0.01, expected_key="a-different-server-side-value")
    r = loadgen(make_manifest(), svc.url, 2, extra=["--max-requests", "5"])
    assert r.rc == 0, r.stdout + r.stderr
    rows = r.csv_rows()
    assert [x["http_status"] for x in rows] == ["401"] * 5
    assert all(x["outcome"] == "http_error" for x in rows)  # failed requests are rows, never dropped
    assert DUMMY_KEY not in all_text(r)


def test_key_not_leaked_when_connection_fails(make_manifest, loadgen):
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()  # nothing listens here now
    r = loadgen(make_manifest(), "http://127.0.0.1:%d" % port, 2, extra=["--max-requests", "6"])
    rows = r.csv_rows()
    assert len(rows) >= 1 and all(x["outcome"] == "conn_error" for x in rows)
    assert DUMMY_KEY not in all_text(r)


def test_credential_file_with_loose_mode_is_refused(service, make_manifest, loadgen, keyfile):
    os.chmod(keyfile, 0o644)
    svc = service()
    r = loadgen(make_manifest(), svc.url, 1, extra=["--max-requests", "2"])
    assert r.rc == 6
    assert svc.post_count == 0
    assert DUMMY_KEY not in all_text(r)


def test_missing_credential_env_is_refused(service, make_manifest, loadgen):
    svc = service()
    r = loadgen(make_manifest(), svc.url, 1, extra=["--max-requests", "2"], env_key=None)
    assert r.rc == 6 and svc.post_count == 0


def test_dry_run_reads_no_key_and_writes_nothing(service, make_manifest, loadgen):
    svc = service()
    r = loadgen(make_manifest(), svc.url, 2, extra=["--max-requests", "4", "--dry-run"], env_key=None)
    assert r.rc == 0 and "SC-DRYRUN" in r.stdout
    assert svc.post_count == 0 and r.files() == []
    assert not os.path.exists(r.ledger)


def test_raw_csv_is_write_once(service, make_manifest, loadgen):
    svc = service(latency=0.01)
    m = make_manifest()
    r1 = loadgen(m, svc.url, 2, extra=["--max-requests", "4"])
    assert r1.rc == 0
    csv_name = [n for n in r1.files() if n.endswith(".csv")][0]
    path = os.path.join(r1.out_dir, csv_name)
    before = hashlib.sha256(open(path, "rb").read()).hexdigest()
    hits = svc.post_count
    r2 = loadgen(m, svc.url, 2, extra=["--max-requests", "4"])  # same run id, same level, same out dir
    assert r2.rc == 3 and "overwrite" in r2.stdout
    assert hashlib.sha256(open(path, "rb").read()).hexdigest() == before
    assert svc.post_count == hits  # refused before any request
    assert r1.meta()["csv_sha256"] == before  # the recorded checksum matches the file


def test_loopback_run_is_labeled_synthetic_everywhere(service, make_manifest, loadgen):
    svc = service(latency=0.01)
    r = loadgen(make_manifest(), svc.url, 2, extra=["--max-requests", "4"])
    assert r.rc == 0
    assert r.files() and all(n.startswith("SYNTHETIC-") for n in r.files())
    assert all(x["synthetic"] == "true" for x in r.csv_rows())
    meta = r.meta()
    assert meta["label"] == "SYNTHETIC" and meta["synthetic"] is True
    assert "label=SYNTHETIC" in r.stdout
    entries = [json.loads(l) for l in open(r.ledger) if l.strip()]
    assert [e["event"] for e in entries] == ["reserve", "final"] and all(e["synthetic"] is True for e in entries)


def test_committed_synthetic_benchmark_declares_itself_synthetic():
    with open(SYN_BENCH) as f:
        b = json.load(f)
    assert b["synthetic"] is True and b["benchmarkVersion"].startswith("SYNTHETIC")
    assert os.path.basename(SYN_BENCH).startswith("SYNTHETIC-")


def test_repo_manifest_d_caps_are_consistent_with_budget_assumptions():
    # independent arithmetic from the committed JSON: requests x 3 calls <= call cap, requests x 0.02101 <= model cost cap
    with open(os.path.join(MANIFEST_DIR, "exp-d-query-load.json")) as f:
        lg = json.load(f)["loadgen"]
    assert lg["max_requests_per_run"] * 3 <= lg["max_model_calls_per_run"]
    assert lg["max_requests_per_run"] * 0.02101 <= lg["model_cost_cap_usd"] + 1e-9
    assert sum(l["requests"] for l in lg["levels"]) <= lg["max_requests_per_run"]
    assert lg["max_concurrency"] <= 20
