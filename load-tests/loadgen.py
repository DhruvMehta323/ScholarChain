#!/usr/bin/env python3
"""ScholarChain query load generator (Python standard library only; threaded).

Sends POST /v1/query (the route shape of modules/services/API.md on main) at one concurrency level, with hard caps, and writes
one immutable raw CSV per level plus a .meta.json. Nothing is retried, nothing is dropped: failed requests are rows too.

Usage (one concurrency level per invocation; load-tests/run_suite.py runs the levels of a manifest):
  python3 -B load-tests/loadgen.py --manifest experiments/manifests/exp-d-query-load.json --run-id RUN \
      --concurrency 4 --max-requests 9 --duration 300 --ramp 5 --benchmark /path/to/benchmark-v1.json \
      [--base-url URL] [--expect-host HOST] [--allow-insecure-http] [--mode fused|graph-only|vector-only]
      [--fetch-explain] [--out-dir DIR] [--question-limit N] [--seed N] [--dry-run]

Environment (names only):
  SC_LOADTEST_API_KEY_FILE   path of the mode-600 (or 400) file that holds the API key; outside the repository (fallback name:
                             SC_SMOKE_API_KEY_FILE). The key is read into memory, sent only in the X-API-Key header (--auth-header), and is
                             never printed, logged or written. Not read at all with --dry-run.
  SC_LOADTEST_BASE_URL       base URL when --base-url is not given (Terraform output query_api_url)
  SC_LOADTEST_BENCHMARK      benchmark file when --benchmark is not given
  SC_RUN_ID                  run id when --run-id is not given (scripts/run-experiment.sh does not export its own run id: the human sets this
                             variable to the same token and passes it to run-experiment.sh --run-id)
  SC_RESULTS_ROOT            raw results root (default experiments/results); the SYNTHETIC ledger of loopback runs follows it. The REAL ledger
                             never moves (see Caps), so the cumulative cap cannot be bypassed by pointing this variable elsewhere.
  SC_LOADTEST_EXPECT_HOST    the host of the Terraform output query_api_url. A non-loopback base URL whose host differs (or when this is
                             unset) is refused: the key is never sent to another host. Same rule as scripts/smoke-test.sh.

Caps (all from the manifest's "loadgen" block; the command line may only LOWER them, never raise them):
  max_requests_per_run, max_model_calls_per_run, max_duration_seconds, max_concurrency, model_cost_cap_usd.
  Target rules. For a NON-loopback base URL (anything except 127.0.0.1, localhost, ::1) the generator refuses (exit 3, before any request and
  before the key file is read) unless ALL of these hold: the manifest passes experiments/validate_manifests.py (schema and cross-field rules),
  has "approved": true and a non-empty "approved_by"; --ledger and --budget-file are NOT given (the defaults are used: the in-repository
  experiments/results/model-spend-ledger.jsonl and experiments/manifests/model-budget.json); the benchmark does not declare "synthetic": true;
  and the benchmark SHA-256 equals loadgen.benchmark.sha256 of the manifest (required, never skipped). Loopback runs (tests, dummy server)
  may use --ledger and --budget-file and are always labeled SYNTHETIC.
  Model calls and cost are reserved BEFORE each request from the assumptions in experiments/manifests/model-budget.json (3 calls and
  about 0.021 USD per request); with --fetch-explain the observed counters.modelCalls replace the assumption for the books afterwards.
  A global ledger (experiments/results/model-spend-ledger.jsonl; SYNTHETIC- prefixed under $SC_RESULTS_ROOT for loopback runs) keeps the total of all runs under
  loadgen_ledger_limit_usd; a run that would exceed it is refused (exit 3).

Immutability: output files are created with O_EXCL; an existing file means exit 3 and nothing is overwritten.
Synthetic labeling: a loopback base URL (127.0.0.1, localhost, ::1) marks the whole run SYNTHETIC (file names, a synthetic column in every row,
meta.json); so does a benchmark file with "synthetic": true, but only on a loopback target (to a non-loopback host it is refused, never relabeled).
Exit codes: 0 level completed (rows may still hold errors), 1 internal failure, 2 usage, 3 refused (http without flag, host, caps, ledger,
existing output), 4 aborted by the error-rate guard (rows kept), 6 key file unusable.
"""
import argparse
import csv
import hashlib
import http.client
import json
import os
import random
import re
import socket
import ssl
import stat
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from urllib.parse import urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.realpath(os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(ROOT, "experiments", "common"))
sys.path.insert(0, os.path.join(ROOT, "experiments"))
import sc_common  # noqa: E402
import validate_manifests  # noqa: E402

DEFAULT_BUDGET = os.path.join(ROOT, "experiments", "manifests", "model-budget.json")
DEFAULT_SCHEMA = os.path.join(ROOT, "experiments", "manifests", "manifest.schema.json")
REAL_LEDGER = os.path.join(ROOT, "experiments", "results", "model-spend-ledger.jsonl")
TOOL_VERSION = "loadgen.v1"
SYNTHETIC_PREFIX = "SYNTHETIC-"
LOOPBACK = ("127.0.0.1", "localhost", "::1")
TOKEN_RE = re.compile(r"^[A-Za-z0-9._-]+$")
ID_RE = re.compile(r"^[A-Za-z0-9:._-]{1,200}$")
CSV_COLUMNS = ["run_id", "experiment_id", "synthetic", "concurrency", "mode", "seq", "question_id", "category",
               "t_start_epoch_ms", "t_start_utc", "latency_ms", "http_status", "outcome", "error_class", "trace_id",
               "query_id", "response_mode", "abstained", "claims_count", "claims_verified", "returned_researcher_ids",
               "model_calls_assumed", "model_calls_observed", "explain_http_status", "stage_latency_ms_json", "bytes"]
MAX_BODY = 1 << 20


class Refused(Exception):
    def __init__(self, msg, code=3):
        Exception.__init__(self, msg)
        self.code = code


def utc_iso(epoch):
    dt = datetime.fromtimestamp(epoch, timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (dt.microsecond // 1000)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def open_new(path, mode="w"):
    """Exclusive create: never truncates or overwrites."""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    except FileExistsError:
        raise Refused("refusing to overwrite an existing raw file: %s" % path)
    return os.fdopen(fd, mode, encoding="utf-8", newline="")


def load_key(env=os.environ):
    """Return (key, env_var_name). Rules of scripts/smoke-test.sh: file, outside the repository, mode 600 or 400, non-empty."""
    name = None
    for cand in ("SC_LOADTEST_API_KEY_FILE", "SC_SMOKE_API_KEY_FILE"):
        if env.get(cand):
            name = cand
            break
    if not name:
        raise Refused("SC_LOADTEST_API_KEY_FILE (or SC_SMOKE_API_KEY_FILE) must name the key file (see scripts/smoke-test.sh --print-key-help)", 6)
    path = env[name]
    if not os.path.isfile(path):
        raise Refused("the key file does not exist or is not a file", 6)
    real = os.path.realpath(path)
    if real == ROOT or real.startswith(ROOT + os.sep):
        raise Refused("the key file must be outside the repository", 6)
    mode = stat.S_IMODE(os.stat(real).st_mode)
    if mode not in (0o600, 0o400):
        raise Refused("the key file must be mode 600 or 400 (got %o)" % mode, 6)
    with open(real, "r", encoding="utf-8") as f:
        key = f.read().replace("\r", "").replace("\n", "")
    if not key:
        raise Refused("the key file is empty", 6)
    return key, name


def git_state():
    try:
        sha = subprocess.run(["git", "-C", ROOT, "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10).stdout.strip()
        dirty = bool(subprocess.run(["git", "-C", ROOT, "status", "--porcelain", "--", "experiments", "load-tests", "scripts", "modules"],
                                    capture_output=True, text=True, timeout=10).stdout.strip())
        return sha or "unknown", dirty
    except (OSError, subprocess.SubprocessError):
        return "unknown", None


def load_questions(path, seed, limit, categories):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    qs = data.get("questions") if isinstance(data, dict) else None
    if not isinstance(qs, list) or not qs:
        raise Refused("the benchmark file has no questions list", 2)
    out = []
    for q in qs:
        if isinstance(q, dict) and isinstance(q.get("question"), str) and q.get("id"):
            if categories and q.get("category") not in categories:
                continue
            out.append({"id": str(q["id"]), "category": str(q.get("category", "")), "question": q["question"]})
    if not out:
        raise Refused("no usable questions after filtering", 2)
    random.Random(seed).shuffle(out)  # deterministic: same seed, same order, so modes see the same questions
    return out[:limit] if limit else out, bool(isinstance(data, dict) and data.get("synthetic") is True)


def request_body(question, mode):
    body = {"question": question}
    if mode == "graph-only":
        body["retrieval"] = {"graphLimit": 10, "vectorLimit": 0}
    elif mode == "vector-only":
        body["retrieval"] = {"graphLimit": 0, "vectorLimit": 30}
    return json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


class Ledger(object):
    """Append-only JSONL of model-spend reservations and finals."""

    def __init__(self, path):
        self.path = path

    def spent(self):
        total, per = 0.0, {}
        if os.path.exists(self.path):
            with open(self.path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    e = json.loads(line)
                    k = e["key"]
                    if e["event"] == "reserve":
                        per.setdefault(k, {})["reserve"] = e["usd"]
                    elif e["event"] == "final":
                        per.setdefault(k, {})["final"] = e["usd"]
        for v in per.values():
            total += v.get("final", v.get("reserve", 0.0))
        return total

    def append(self, entry):
        os.makedirs(os.path.dirname(os.path.abspath(self.path)), exist_ok=True)
        data = (json.dumps(entry, sort_keys=True) + "\n").encode("utf-8")
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
        try:
            os.write(fd, data)
        finally:
            os.close(fd)


class Runner(object):
    def __init__(self, a, caps, questions, key, synthetic, parts, writer, fh):
        self.a, self.caps, self.questions, self.key, self.synthetic = a, caps, questions, key, synthetic
        self.parts, self.writer, self.fh = parts, writer, fh
        self.lock = threading.Lock()
        self.started = 0
        self.finished = 0
        self.model_calls_reserved = 0
        self.cost_reserved = 0.0
        self.observed_calls = 0
        self.recent = []
        self.stop_reason = None
        self.deadline = None
        self.status_counts = {}

    def reserve(self):
        """Return the sequence number to run, or None when a cap says stop (the reason is recorded once)."""
        c = self.caps
        with self.lock:
            if self.stop_reason:
                return None
            if time.monotonic() >= self.deadline:
                self.stop_reason = "duration_cap"
            elif self.started >= c["requests"]:
                self.stop_reason = "request_cap"
            elif self.model_calls_reserved + c["calls_per_request"] > c["model_calls"]:
                self.stop_reason = "model_call_cap"
            elif self.cost_reserved + c["cost_per_request"] > c["model_cost"] + 1e-12:
                self.stop_reason = "model_cost_cap"
            if self.stop_reason:
                return None
            self.started += 1
            self.model_calls_reserved += c["calls_per_request"]
            self.cost_reserved += c["cost_per_request"]
            return self.started

    def call(self, method, path, body, headers):
        p = self.parts
        timeout = self.caps["timeout"]
        if p.scheme == "https":
            conn = http.client.HTTPSConnection(p.hostname, p.port or 443, timeout=timeout, context=ssl.create_default_context())
        else:
            conn = http.client.HTTPConnection(p.hostname, p.port or 80, timeout=timeout)
        try:
            conn.request(method, path, body=body, headers=headers)
            resp = conn.getresponse()
            data = resp.read(MAX_BODY)
            return resp.status, resp.getheader("X-Trace-Id"), data
        finally:
            conn.close()

    def one(self, seq):
        a = self.a
        q = self.questions[(seq - 1) % len(self.questions)]
        sent_trace = ("lt-%s-%d" % (a.run_id, seq))[:64]
        headers = {"Content-Type": "application/json", "Accept": "application/json", "X-Trace-Id": sent_trace, a.auth_header: self.key}
        t_epoch = time.time()
        t0 = time.perf_counter()
        status, trace, data, err = 0, None, b"", ""
        try:
            status, trace, data = self.call("POST", "/v1/query", request_body(q["question"], a.mode), headers)
        except (socket.timeout, TimeoutError):
            err = "timeout"
        except (OSError, http.client.HTTPException) as e:  # class name only: never echo exception text
            err = "connection:" + type(e).__name__
        latency_ms = (time.perf_counter() - t0) * 1000.0
        row = dict.fromkeys(CSV_COLUMNS, "")
        row.update(run_id=a.run_id, experiment_id=self.caps["experiment_id"], synthetic="true" if self.synthetic else "false",
                   concurrency=a.concurrency, mode=a.mode, seq=seq, question_id=q["id"], category=q["category"],
                   t_start_epoch_ms=int(t_epoch * 1000), t_start_utc=utc_iso(t_epoch), latency_ms="%.3f" % latency_ms,
                   http_status=status, trace_id=trace or sent_trace, bytes=len(data), model_calls_assumed=self.caps["calls_per_request"])
        if err:
            row["outcome"] = "timeout" if err == "timeout" else "conn_error"
            row["error_class"] = err
        elif status == 200:
            row["outcome"] = "ok"
        elif status == 202:
            row["outcome"] = "async"
        else:
            row["outcome"] = "http_error"
            row["error_class"] = "http_%d" % status
        query_id = ""
        if status in (200, 202) and data:
            try:
                j = json.loads(data.decode("utf-8", "replace"))
                if isinstance(j, dict):
                    qid = j.get("queryId") or ""
                    query_id = qid if isinstance(qid, str) and ID_RE.match(qid) else ""
                    row["query_id"] = query_id
                    row["response_mode"] = j.get("mode", "") if isinstance(j.get("mode", ""), str) else ""
                    if "abstained" in j:
                        row["abstained"] = "true" if j.get("abstained") else "false"
                    if isinstance(j.get("claims"), list):
                        row["claims_count"] = len(j["claims"])
                        row["claims_verified"] = sum(1 for c in j["claims"] if isinstance(c, dict) and c.get("verificationStatus") == "VERIFIED")
                    ans = j.get("answer")
                    if isinstance(ans, dict) and isinstance(ans.get("researchers"), list):
                        ids = [r.get("researcherId") for r in ans["researchers"] if isinstance(r, dict) and isinstance(r.get("researcherId"), str)]
                        row["returned_researcher_ids"] = ";".join(ids)
            except ValueError:
                row["error_class"] = "bad_json"
        if a.fetch_explain and query_id and status == 200:
            try:
                st2, _, d2 = self.call("GET", "/v1/explain/" + query_id, None, {"Accept": "application/json", a.auth_header: self.key, "X-Trace-Id": sent_trace})
                row["explain_http_status"] = st2
                if st2 == 200:
                    j2 = json.loads(d2.decode("utf-8", "replace"))
                    mc = (j2.get("counters") or {}).get("modelCalls")
                    if isinstance(mc, int):
                        row["model_calls_observed"] = mc
                    stages = {s.get("stage"): s.get("latencyMillis") for s in (j2.get("stagesExecuted") or []) if isinstance(s, dict)}
                    row["stage_latency_ms_json"] = json.dumps(stages, sort_keys=True, separators=(",", ":"))
            except (OSError, http.client.HTTPException, ValueError):
                row["explain_http_status"] = "error"
        ok = row["outcome"] in ("ok", "async")
        with self.lock:
            self.writer.writerow(row)
            self.fh.flush()
            self.finished += 1
            key = str(status) if status else row["error_class"]
            self.status_counts[key] = self.status_counts.get(key, 0) + 1
            if isinstance(row["model_calls_observed"], int):
                self.observed_calls += row["model_calls_observed"]
                extra = row["model_calls_observed"] - self.caps["calls_per_request"]
                if extra > 0:  # observed above assumption: charge the difference so the caps still hold
                    self.model_calls_reserved += extra
                    self.cost_reserved += extra * self.caps["cost_per_call"]
            self.recent = (self.recent + [ok])[-self.a.abort_window:]
            if len(self.recent) >= self.a.abort_window and (self.recent.count(False) / float(len(self.recent))) > self.a.abort_error_rate and not self.stop_reason:
                self.stop_reason = "error_rate_abort"

    def worker(self, idx):
        delay = self.a.ramp * idx / float(max(1, self.a.concurrency))
        if delay:
            time.sleep(delay)
        while True:
            seq = self.reserve()
            if seq is None:
                return
            self.one(seq)


def parse_args(argv):
    ap = argparse.ArgumentParser(description="ScholarChain query load generator (see module docstring)", formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", required=True, help="experiment manifest (JSON) with a loadgen block")
    ap.add_argument("--run-id", default=os.environ.get("SC_RUN_ID", ""), help="token: letters, digits, . _ - (default: $SC_RUN_ID)")
    ap.add_argument("--concurrency", type=int, required=True)
    ap.add_argument("--max-requests", type=int, help="lower than or equal to the manifest cap")
    ap.add_argument("--duration", type=float, help="seconds; lower than or equal to the manifest cap")
    ap.add_argument("--ramp", type=float, default=0.0, help="seconds over which the workers are started")
    ap.add_argument("--benchmark", default=os.environ.get("SC_LOADTEST_BENCHMARK", ""), help="benchmark-v1.json (schema: questions[].id/category/question); default $SC_LOADTEST_BENCHMARK")
    ap.add_argument("--base-url", default=os.environ.get("SC_LOADTEST_BASE_URL", ""))
    ap.add_argument("--expect-host", default=os.environ.get("SC_LOADTEST_EXPECT_HOST", ""))
    ap.add_argument("--allow-insecure-http", action="store_true")
    ap.add_argument("--mode", choices=["fused", "graph-only", "vector-only"], default="fused")
    ap.add_argument("--fetch-explain", action="store_true", help="GET /v1/explain/{queryId} after each 200 (no model call) to record observed model calls and stage latencies")
    ap.add_argument("--auth-header", default="X-API-Key")
    ap.add_argument("--out-dir", help="default <results root>/<experiment id>/<run id>/ (results root: $SC_RESULTS_ROOT or experiments/results)")
    ap.add_argument("--ledger", help="LOOPBACK ONLY: model-spend ledger (default: the repository ledger; refused for a non-loopback target)")
    ap.add_argument("--budget-file", help="LOOPBACK ONLY: budget file (default experiments/manifests/model-budget.json; refused for a non-loopback target)")
    ap.add_argument("--question-limit", type=int)
    ap.add_argument("--categories", default="", help="comma list of benchmark categories (default all)")
    ap.add_argument("--seed", type=int)
    ap.add_argument("--abort-error-rate", type=float, default=0.5)
    ap.add_argument("--abort-window", type=int, default=10)
    ap.add_argument("--dry-run", action="store_true", help="print the plan and the effective caps; make no request, read no key, write nothing")
    return ap.parse_args(argv)


def main(argv=None):
    a = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        return run(a)
    except Refused as e:
        print("SC-REFUSED %s" % e)
        return e.code


def is_loopback_url(base_url):
    try:
        p = urlsplit((base_url or "").rstrip("/"))
        return (p.hostname or "").lower() in LOOPBACK
    except ValueError:
        return False


def enforce_target_rules(a, manifest, manifest_path, bench_synth=None, bench_sha=None, want_sha=None):
    """Rules for a NON-loopback target (M1). Called twice: early (flags and manifest) and after the benchmark is read. Raises Refused(3)."""
    if is_loopback_url(a.base_url):
        return
    if a.ledger or a.budget_file:
        raise Refused("--ledger and --budget-file are only allowed for a loopback target: a non-loopback run uses the default ledger and budget file")
    errs = validate_manifests.check_manifest(manifest_path, manifest, validate_manifests.load(DEFAULT_SCHEMA), validate_manifests.load(DEFAULT_BUDGET))
    if errs:
        raise Refused("the manifest does not pass experiments/validate_manifests.py: %s" % "; ".join(errs[:3]))
    if manifest.get("approved") is not True or not str(manifest.get("approved_by") or "").strip():
        raise Refused("the manifest is not approved (approved true and a non-empty approved_by are set by the human): a non-loopback run is refused")
    if bench_synth:
        raise Refused("the benchmark declares synthetic=true: a SYNTHETIC benchmark is never sent to a non-loopback host")
    if bench_sha is not None and (not want_sha or bench_sha != want_sha):
        raise Refused("benchmark file SHA-256 %s does not equal the manifest loadgen.benchmark.sha256 (required for a non-loopback run)" % bench_sha)


def run(a):
    if not a.run_id:
        raise Refused("--run-id (or SC_RUN_ID) is required", 2)
    if not a.benchmark:
        raise Refused("--benchmark (or SC_LOADTEST_BENCHMARK) is required", 2)
    if not TOKEN_RE.match(a.run_id):
        raise Refused("--run-id must contain only letters, digits, . _ -", 2)
    if not re.match(r"^[A-Za-z0-9_-]+$", a.auth_header):
        raise Refused("--auth-header must be a header name", 2)
    with open(a.manifest, "r", encoding="utf-8") as f:
        m = json.load(f)
    lg = m.get("loadgen")
    if not isinstance(lg, dict):
        raise Refused("the manifest has no loadgen block (this experiment does not use the load generator)", 2)
    budget_path = a.budget_file or DEFAULT_BUDGET
    with open(budget_path, "r", encoding="utf-8") as f:
        budget = json.load(f)
    pra = budget["per_request_assumptions"]
    calls_pr = pra["model_calls_per_request"]
    cost_pr = pra["estimated_cost_usd_per_request"]

    def lower(name, cli, cap):
        if cli is None:
            return cap
        if cli > cap:
            raise Refused("%s %s exceeds the manifest cap %s: the command line may only lower caps" % (name, cli, cap), 3)
        return cli
    if a.concurrency < 1:
        raise Refused("--concurrency must be at least 1", 2)
    lower("--concurrency", a.concurrency, lg["max_concurrency"])
    max_req = lower("--max-requests", a.max_requests, lg["max_requests_per_run"])
    duration = lower("--duration", a.duration, lg["max_duration_seconds"])
    if max_req < 1 or duration <= 0:
        raise Refused("caps must be positive", 2)
    caps = {"experiment_id": m["id"], "requests": max_req, "model_calls": lg["max_model_calls_per_run"], "model_cost": lg["model_cost_cap_usd"],
            "calls_per_request": calls_pr, "cost_per_request": cost_pr, "cost_per_call": cost_pr / float(calls_pr),
            "timeout": lg.get("request_timeout_seconds", 60), "duration": duration, "concurrency": a.concurrency}
    if max_req * cost_pr > caps["model_cost"] + 1e-9:
        raise Refused("max requests %d x %.5f USD exceeds the manifest model cost cap %.2f" % (max_req, cost_pr, caps["model_cost"]), 3)

    base = a.base_url.rstrip("/")
    parts = urlsplit(base)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password or parts.path not in ("", "/") or parts.query:
        raise Refused("--base-url (or SC_LOADTEST_BASE_URL) must be scheme://host[:port] with no credentials or path", 2)
    host = parts.hostname.lower()
    loopback = host in LOOPBACK
    if parts.scheme == "http" and not a.allow_insecure_http:
        raise Refused("the base URL is http:// so the API key would travel in clear text; pass --allow-insecure-http knowingly (Phase 13 human-gate decision 2)")
    if not loopback and (not a.expect_host or host != a.expect_host.lower()):
        raise Refused("host %s is not the expected Terraform query_api_url host (set SC_LOADTEST_EXPECT_HOST/--expect-host): the key is never sent to another host" % host)
    enforce_target_rules(a, m, a.manifest)

    bench_sha = sha256_file(a.benchmark)
    cats = [c for c in a.categories.split(",") if c]
    seed = a.seed if a.seed is not None else m.get("seed", 441)
    questions, bench_synth = load_questions(a.benchmark, seed, a.question_limit or lg.get("question_limit"), cats)
    want = (lg.get("benchmark") or {}).get("sha256")
    enforce_target_rules(a, m, a.manifest, bench_synth, bench_sha, want)
    synthetic = loopback or bench_synth
    if not bench_synth and want and bench_sha != want:
        raise Refused("benchmark file SHA-256 %s differs from the manifest loadgen.benchmark.sha256" % bench_sha)

    exp = m["id"]
    out_dir = a.out_dir or os.path.join(sc_common.results_root(), exp, a.run_id)
    stem = "loadgen-%s-c%d-%s" % (a.run_id, a.concurrency, a.mode)
    if synthetic:
        stem = SYNTHETIC_PREFIX + stem
    csv_path = os.path.join(out_dir, stem + ".csv")
    meta_path = os.path.join(out_dir, stem + ".meta.json")
    # REAL runs always use the repository ledger (not moved by SC_RESULTS_ROOT: the cumulative cap must not be bypassable); SYNTHETIC runs follow it.
    ledger_path = a.ledger or (os.path.join(sc_common.results_root(), "SYNTHETIC-model-spend-ledger.jsonl") if synthetic else REAL_LEDGER)
    ledger = Ledger(ledger_path)
    ledger_key = "%s/%s/c%d/%s" % (exp, a.run_id, a.concurrency, a.mode)
    reserve_usd = max_req * cost_pr
    plan = {"experiment": exp, "run_id": a.run_id, "synthetic": synthetic, "base_url": "%s://%s%s" % (parts.scheme, host, (":%d" % parts.port) if parts.port else ""),
            "mode": a.mode, "concurrency": a.concurrency, "caps": {k: caps[k] for k in ("requests", "model_calls", "model_cost", "duration", "timeout")},
            "assumed_model_calls_per_request": calls_pr, "assumed_usd_per_request": cost_pr, "max_reserved_usd": round(reserve_usd, 5),
            "questions": len(questions), "benchmark_sha256": bench_sha, "csv": csv_path}
    print("SC-PLAN " + json.dumps(plan, sort_keys=True))
    if a.dry_run:
        print("SC-DRYRUN no request made, no key read, nothing written")
        return 0
    spent = ledger.spent()
    if spent + reserve_usd > budget["loadgen_ledger_limit_usd"] + 1e-9:
        raise Refused("model-spend ledger: %.4f USD already booked + %.4f reserved exceeds the limit %.2f USD" % (spent, reserve_usd, budget["loadgen_ledger_limit_usd"]))
    if os.path.exists(csv_path) or os.path.exists(meta_path):
        raise Refused("refusing to overwrite an existing raw file for this run id and level: %s" % csv_path)
    key, key_env = load_key()
    commit, dirty = git_state()

    started_at = time.time()
    fh = open_new(csv_path)
    writer = csv.DictWriter(fh, fieldnames=CSV_COLUMNS, lineterminator="\n")
    writer.writeheader()
    fh.flush()
    ledger.append({"event": "reserve", "key": ledger_key, "usd": round(reserve_usd, 6), "at": utc_iso(started_at), "synthetic": synthetic})
    r = Runner(a, caps, questions, key, synthetic, parts, writer, fh)
    r.deadline = time.monotonic() + duration
    status, exc = "completed", None
    try:
        threads = [threading.Thread(target=r.worker, args=(i,), daemon=True) for i in range(a.concurrency)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        if r.stop_reason == "error_rate_abort":
            status = "aborted_error_rate"
    except BaseException as e:  # keep the partial run and report it, never drop it
        status, exc = "failed", type(e).__name__
    finally:
        fh.close()
        ended_at = time.time()
        est = round(r.cost_reserved, 6)
        ledger.append({"event": "final", "key": ledger_key, "usd": est, "at": utc_iso(ended_at), "synthetic": synthetic, "status": status})
        meta = {"tool": TOOL_VERSION, "label": "SYNTHETIC" if synthetic else "REAL", "synthetic": synthetic, "status": status, "exception_class": exc,
                "stop_reason": r.stop_reason or "workers_drained", "experiment_id": exp, "run_id": a.run_id,
                "manifest": os.path.relpath(os.path.abspath(a.manifest), ROOT), "manifest_sha256": sha256_file(a.manifest),
                "commit": commit, "tree_dirty_in_code_paths": dirty, "python": sys.version.split()[0], "platform": sys.platform,
                "base_url": plan["base_url"], "mode": a.mode, "concurrency": a.concurrency, "ramp_seconds": a.ramp, "seed": seed,
                "effective_caps": plan["caps"], "key_source_env_var": key_env, "fetch_explain": a.fetch_explain,
                "benchmark": {"file_name": os.path.basename(a.benchmark), "sha256": bench_sha, "synthetic": bench_synth, "questions_used": len(questions),
                              "manifest_source": (lg.get("benchmark") or {}).get("source_commit_at_authoring")},
                "started_at": utc_iso(started_at), "ended_at": utc_iso(ended_at), "wall_seconds": round(ended_at - started_at, 3),
                "requests_started": r.started, "requests_finished": r.finished, "status_counts": r.status_counts,
                "model_calls_reserved": r.model_calls_reserved, "model_calls_observed_sum": r.observed_calls, "model_cost_estimated_usd": est,
                "assumptions": {"model_calls_per_request": calls_pr, "usd_per_request": cost_pr, "source": "experiments/manifests/model-budget.json (estimates, not measurements)"},
                "csv": os.path.basename(csv_path), "csv_sha256": sha256_file(csv_path), "csv_columns": CSV_COLUMNS}
        try:
            with open_new(meta_path) as mf:
                json.dump(meta, mf, indent=2, sort_keys=True)
                mf.write("\n")
        except Refused as e:
            print("SC-WARN could not write meta: %s" % e)
    print("SC-RESULT status=%s stop_reason=%s started=%d finished=%d statuses=%s wall_s=%.2f model_cost_est_usd=%.4f label=%s" %
          (status, meta["stop_reason"], r.started, r.finished, json.dumps(r.status_counts, sort_keys=True), ended_at - started_at, est, meta["label"]))
    print("SC-RAW %s" % csv_path)
    return {"completed": 0, "aborted_error_rate": 4, "failed": 1}[status]


if __name__ == "__main__":
    sys.exit(main())
