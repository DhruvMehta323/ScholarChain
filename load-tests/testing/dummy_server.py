#!/usr/bin/env python3
"""SYNTHETIC local dummy of the query-api (127.0.0.1 only). For verifying load-tests/loadgen.py. Not a model of real latency.

Usage: python3 -B load-tests/testing/dummy_server.py --secret-file FILE [--port 0] [--port-file FILE] [--latency-ms 40] [--seed 1]
Behaviors mirrored from modules/services/API.md (main): X-API-Key required on /v1 (401), POST /v1/query 200 SyncQueryResponse-like body,
422 for graphLimit 0 or a vectorLimit other than 30, 429 when more than 4 run + 16 wait, GET /v1/explain/{queryId}, GET /healthz.
Every response carries X-Synthetic: true. The expected credential is read from a file and never printed.
"""
import argparse
import hashlib
import json
import random
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class State(object):
    def __init__(self, credential, latency_ms, seed):
        self.credential, self.latency, self.rng = credential, latency_ms / 1000.0, random.Random(seed)
        self.lock = threading.Lock()
        self.running = 0
        self.waiting = 0
        self.slots = threading.Semaphore(4)


def err(code, retryable=False):
    return {"error": code, "message": "SYNTHETIC dummy", "traceId": "t", "retryable": retryable}


def make_handler(st):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"

        def log_message(self, fmt, *args):  # request line only, never headers
            print("DUMMY %s %s" % (self.command, self.path.split("?")[0]), flush=True)

        def send_json(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-Synthetic", "true")
            self.send_header("X-Trace-Id", self.headers.get("X-Trace-Id") or "dummy-trace")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def authed(self):
            if self.headers.get("X-API-Key") != st.credential:
                self.send_json(401, err("UNAUTHORIZED"))
                return False
            return True

        def do_GET(self):
            if self.path in ("/healthz", "/health"):
                return self.send_json(200, {"status": "ok", "service": "SYNTHETIC-dummy"})
            if not self.authed():
                return
            m = re.match(r"^/v1/explain/([A-Za-z0-9:._-]+)$", self.path)
            if m:
                return self.send_json(200, {"queryId": m.group(1),
                                            "stagesExecuted": [{"stage": "PLANNING", "latencyMillis": 5}, {"stage": "RETRIEVAL", "latencyMillis": 12},
                                                               {"stage": "SYNTHESIS", "latencyMillis": 20}],
                                            "counters": {"totalLatencyMillis": 37, "modelCalls": 2, "modelRetries": 0, "downstreamRetries": 0}})
            self.send_json(404, err("NOT_FOUND"))

        def do_POST(self):
            if self.path != "/v1/query":
                return self.send_json(404, err("NOT_FOUND"))
            if not self.authed():
                return
            n = int(self.headers.get("Content-Length") or 0)
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                return self.send_json(400, err("MALFORMED_REQUEST"))
            r = req.get("retrieval") or {}
            if r.get("graphLimit", 100) == 0 or r.get("vectorLimit", 30) != 30:
                return self.send_json(422, err("UNSUPPORTED_RETRIEVAL_MODE"))
            with st.lock:
                if st.running >= 4 and st.waiting >= 16:
                    return self.send_json(429, err("RATE_LIMITED", True))
                st.waiting += 1
            st.slots.acquire()
            with st.lock:
                st.waiting -= 1
                st.running += 1
                jitter = st.rng.random()
            try:
                time.sleep(st.latency * (1 + jitter))
            finally:
                with st.lock:
                    st.running -= 1
                st.slots.release()
            h = hashlib.sha256(str(req.get("question", "")).encode()).hexdigest()
            self.send_json(200, {"queryId": "query:" + h[:32], "mode": "synchronous",
                                 "answer": {"summary": "SYNTHETIC",
                                            "researchers": [{"researcherId": "researcher:sc1:" + h}, {"researcherId": "researcher:sc1:" + h[::-1]}]},
                                 "claims": [{"claimId": "claim:1", "evidenceIds": [], "verificationStatus": "VERIFIED"}], "abstained": False})
    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--secret-file", required=True)
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--port-file")
    ap.add_argument("--latency-ms", type=float, default=40.0)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    with open(a.secret_file, "r", encoding="utf-8") as f:
        credential = f.read().strip()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(State(credential, a.latency_ms, a.seed)))
    port = srv.server_address[1]
    if a.port_file:
        with open(a.port_file, "w") as f:
            f.write(str(port))
    print("DUMMY listening on 127.0.0.1:%d (SYNTHETIC)" % port, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
