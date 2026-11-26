"""Shared helpers for the experiment-harness tests (Phase 15b, work order C).

Everything runs offline: local HTTP servers bind 127.0.0.1 only, every output goes to pytest tmp_path, the credential files are dummy
values in a temp dir outside the repository with mode 600. No AWS, no sbt, no network.
"""
import csv
import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

REPO = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
LOADGEN = os.path.join(REPO, "load-tests", "loadgen.py")
SYN_BENCH = os.path.join(REPO, "load-tests", "testing", "SYNTHETIC-benchmark.json")
MANIFEST_DIR = os.path.join(REPO, "experiments", "manifests")
ANALYSIS = os.path.join(REPO, "experiments", "analysis")
COLLECT = os.path.join(REPO, "experiments", "collect")
VALIDATOR = os.path.join(REPO, "experiments", "validate_manifests.py")
STUB_BIN = os.path.join(REPO, "experiments", "collect", "testing", "stub-bin")
DUMMY_KEY = "SCTEST-DUMMY-CRED-9f3a1c7e5b"

sys.path.insert(0, os.path.join(REPO, "experiments", "analysis"))
sys.path.insert(0, os.path.join(REPO, "experiments", "common"))


def clean_env(extra=None, path=None):
    env = {"PATH": path or "/usr/bin:/bin", "PYTHONDONTWRITEBYTECODE": "1", "HOME": os.environ.get("TMPDIR", "/tmp")}
    env.update(extra or {})
    return env


def py(script, args, env=None, timeout=120):
    return subprocess.run([sys.executable, "-B", script] + list(args), capture_output=True, text=True, env=env or clean_env(), timeout=timeout)


class FakeService(object):
    """In-test stdlib stand-in for the query API: records hits, headers and the peak number of requests in flight."""

    def __init__(self, expected_key, latency):
        self.expected_key, self.latency = expected_key, latency
        self.lock = threading.Lock()
        self.inflight = 0
        self.max_inflight = 0
        self.post_count = 0
        self.keys_seen = []
        svc = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"

            def log_message(self, *a):
                pass

            def _send(self, code, obj):
                b = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def do_GET(self):
                self._send(404, {"error": "NOT_FOUND"})

            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                self.rfile.read(n)
                with svc.lock:
                    svc.post_count += 1
                    svc.keys_seen.append(self.headers.get("X-API-Key"))
                    svc.inflight += 1
                    svc.max_inflight = max(svc.max_inflight, svc.inflight)
                try:
                    time.sleep(svc.latency)
                finally:
                    with svc.lock:
                        svc.inflight -= 1
                if self.headers.get("X-API-Key") != svc.expected_key:
                    return self._send(401, {"error": "UNAUTHORIZED"})
                self._send(200, {"queryId": "query:t%d" % svc.post_count, "mode": "synchronous", "abstained": False,
                                 "answer": {"researchers": [{"researcherId": "r:1"}]}, "claims": []})

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.srv.server_address[1]
        self.url = "http://127.0.0.1:%d" % self.port
        self.thread = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.srv.shutdown()
        self.srv.server_close()


@pytest.fixture
def service():
    made = []

    def make(latency=0.05, expected_key=DUMMY_KEY):
        s = FakeService(expected_key, latency)
        made.append(s)
        return s
    yield make
    for s in made:
        s.close()


@pytest.fixture
def keyfile(tmp_path):
    d = tmp_path / "credential-dir"
    d.mkdir()
    p = d / "credential.txt"
    p.write_text(DUMMY_KEY + "\n")
    os.chmod(str(p), 0o600)
    return str(p)


@pytest.fixture
def make_manifest(tmp_path):
    """Copy the committed exp-d manifest into tmp_path with a modified loadgen block."""
    def make(**loadgen_overrides):
        with open(os.path.join(MANIFEST_DIR, "exp-d-query-load.json")) as f:
            m = json.load(f)
        m["loadgen"].update(loadgen_overrides)
        p = tmp_path / "exp-d-query-load.json"
        p.write_text(json.dumps(m))
        return str(p)
    return make


class Run(object):
    def __init__(self, proc, out_dir, ledger):
        self.proc, self.out_dir, self.ledger = proc, out_dir, ledger
        self.rc, self.stdout, self.stderr = proc.returncode, proc.stdout, proc.stderr

    def files(self):
        return sorted(os.listdir(self.out_dir)) if os.path.isdir(self.out_dir) else []

    def csv_rows(self):
        names = [n for n in self.files() if n.endswith(".csv")]
        assert len(names) == 1, names
        with open(os.path.join(self.out_dir, names[0]), newline="") as f:
            return list(csv.DictReader(f))

    def meta(self):
        names = [n for n in self.files() if n.endswith(".meta.json")]
        assert len(names) == 1, names
        with open(os.path.join(self.out_dir, names[0])) as f:
            return json.load(f)


@pytest.fixture
def loadgen(tmp_path, keyfile):
    def run(manifest, base_url, concurrency, run_id="t1", extra=(), env_key=keyfile, out_name="out", ledger_name="ledger.jsonl",
            insecure=True, seed=1):
        out_dir = str(tmp_path / out_name)
        ledger = str(tmp_path / ledger_name)
        args = ["--manifest", manifest, "--run-id", run_id, "--concurrency", str(concurrency), "--base-url", base_url,
                "--benchmark", SYN_BENCH, "--out-dir", out_dir, "--ledger", ledger, "--seed", str(seed)]
        if insecure:
            args.append("--allow-insecure-http")
        args += list(extra)
        env = clean_env({"SC_LOADTEST_API_KEY_FILE": env_key} if env_key else {})
        return Run(py(LOADGEN, args, env=env), out_dir, ledger)
    return run
