"""Shared helpers for the bounded, DOI-driven enrichment fetchers (stdlib only, except the optional certifi TLS fallback).

TLS: certificate verification is never disabled. If the interpreter has no CA store, the
stdlib-only route is to set SSL_CERT_FILE to a system CA bundle; otherwise the optional
certifi package is used when installed.

Secrets and personal data rules enforced here:
  * SCHOLARCHAIN_CONTACT_EMAIL and OPENALEX_API_KEY are read from os.environ only.
  * The contact email is sent only in the User-Agent header. It is never placed in a URL,
    never printed, and never written to disk. Stored request URLs pass through redact_url().
  * An API key is sent only in an Authorization header, never in a URL.
"""
from __future__ import annotations

import hashlib
import json
import os
import random
import re
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl, quote, urlencode, urlsplit, urlunsplit

from doi_canonical import doi_path_segment, normalize_doi  # noqa: F401  canonical DOI rule (re-exported)

REPO = Path(__file__).resolve().parents[2]
DEFAULT_LIMIT = 100
SECRET_QUERY_PARAMS = {"mailto", "api_key", "apikey", "key", "token", "email"}
RETRY_STATUS = {429, 500, 502, 503, 504}
ORCID_RE = re.compile(r"^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$")
DEFAULT_SELECTION = REPO / "datasets" / "samples" / "dblp" / "dblp_selection_1000.tsv"
ENRICH_SEED = 441


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def redact_url(url: str) -> str:
    """Replace the value of any secret-bearing query parameter with <redacted>."""
    parts = urlsplit(url)
    pairs = [(k, "<redacted>" if k.lower() in SECRET_QUERY_PARAMS else v)
             for k, v in parse_qsl(parts.query, keep_blank_values=True)]
    query = urlencode(pairs, safe="<>")
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


def safe_path_segment(value: str) -> str:
    """Percent-encode every reserved character, including '/', for use in a URL path."""
    return quote(value, safe="")


def user_agent(tool: str) -> str:
    email = os.environ.get("SCHOLARCHAIN_CONTACT_EMAIL", "").strip()
    base = f"ScholarChain-dataset-fetch/1.0 ({tool}; CS441 course project)"
    return f"{base}; mailto:{email}" if email else base


def credentials_status() -> str:
    """Presence only. Values are never returned."""
    email = "set" if os.environ.get("SCHOLARCHAIN_CONTACT_EMAIL", "").strip() else "NOT set"
    key = "set" if os.environ.get("OPENALEX_API_KEY", "").strip() else "NOT set"
    return f"SCHOLARCHAIN_CONTACT_EMAIL={email}; OPENALEX_API_KEY={key}"


def code_version() -> str:
    try:
        head = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, timeout=10).stdout.strip() or "unknown"
        dirty = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain", "--",
                                "datasets/scripts"], capture_output=True, text=True,
                               timeout=10).stdout.strip()
        return head + ("+uncommitted-scripts" if dirty else "")
    except Exception:
        return "unknown"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_selection(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        return [dict(zip(header, line.rstrip("\n").split("\t"))) for line in f]


def enrichment_subset(selection: Path, limit: int, seed: int = ENRICH_SEED) -> list[dict]:
    """Reproducible subset: rank the selection by sha256(seed|enrichment|key), take `limit`."""
    rows = read_selection(selection)
    rows.sort(key=lambda r: hashlib.sha256(
        f"{seed}|enrichment|{r['key']}".encode("utf-8")).hexdigest())
    return rows[:limit]


def enforce_limit(count: int, allow_more: bool, what: str) -> None:
    if count > DEFAULT_LIMIT and not allow_more:
        sys.exit(f"REFUSED: {count} {what} requested; the default maximum is {DEFAULT_LIMIT}. "
                 f"Re-run with --allow-more to override (not permitted in Phase 03).")


def key_for(identifier: str) -> str:
    return hashlib.sha256(identifier.encode("utf-8")).hexdigest()


def tls_context() -> ssl.SSLContext:
    """Verified TLS context. Certificate verification is never disabled.

    The python.org macOS build ships without a CA bundle, so the default context fails with
    CERTIFICATE_VERIFY_FAILED. If the default store is empty, use SSL_CERT_FILE when set,
    otherwise the public CA bundle of the certifi package (when installed).
    """
    default = ssl.create_default_context()
    if default.cert_store_stats().get("x509_ca", 0) > 0:
        return default
    env_bundle = os.environ.get("SSL_CERT_FILE", "")
    if env_bundle and Path(env_bundle).is_file():
        return ssl.create_default_context(cafile=env_bundle)
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return default


class FatalTransportError(RuntimeError):
    """Not retryable (for example TLS verification failure). Aborts the run at once."""


def log_flush(*args):
    print(*args, flush=True)


MAX_REQUEST_SECONDS = 150.0  # total budget for one identifier, retries and sleeps included


def http_get(url: str, headers: dict, timeout: float = 20.0, retries: int = 5,
             base_delay: float = 2.0, log=log_flush):
    """GET with retry and exponential backoff on timeouts, 429 and 5xx.

    Returns (status, body_bytes, response_headers). 404 is returned, not raised.
    Raises RuntimeError after the retries are exhausted. The URL is logged only after
    redaction, so a log line can never contain the contact email or a key.
    """
    last = "unknown"
    started = time.monotonic()
    for attempt in range(retries + 1):
        if time.monotonic() - started > MAX_REQUEST_SECONDS:
            last += " (per-request time budget exceeded)"
            break
        req = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=timeout, context=tls_context()) as resp:
                return resp.status, resp.read(), dict(resp.headers.items())
        except urllib.error.HTTPError as e:
            body = e.read()
            if e.code == 404:
                return 404, body, dict(e.headers.items())
            if e.code not in RETRY_STATUS:
                return e.code, body, dict(e.headers.items())
            last = f"HTTP {e.code}"
            retry_after = e.headers.get("Retry-After")
            delay = float(retry_after) if retry_after and retry_after.isdigit() else None
        except urllib.error.URLError as e:
            if isinstance(getattr(e, "reason", None), ssl.SSLCertVerificationError):
                raise FatalTransportError(f"TLS certificate verification failed ({redact_url(url)})")
            last = type(e).__name__
            delay = None
        except (socket.timeout, TimeoutError, ConnectionError) as e:
            last = type(e).__name__
            delay = None
        if attempt == retries:
            break
        wait = delay if delay is not None else base_delay * (2 ** attempt) + random.uniform(0, 0.5)
        log(f"  retry {attempt + 1}/{retries} after {last}; sleeping {wait:.1f}s "
            f"({redact_url(url)})")
        time.sleep(wait)
    raise RuntimeError(f"gave up after {retries + 1} attempts: {last} ({redact_url(url)})")


def store_response(directory: Path, identifier: str, request_url: str, status: int,
                   body: bytes, headers: dict, extra: dict | None = None) -> Path:
    """Write one raw-response envelope. Never overwrites an existing file."""
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{key_for(identifier)}.json"
    if path.exists():
        return path
    text = body.decode("utf-8", errors="replace")
    try:
        parsed = json.loads(text)
        body_field, parse_ok = parsed, True
    except ValueError:
        body_field, parse_ok = text, False
    env = {
        "identifier": identifier,
        "request_url": redact_url(request_url),
        "retrieved_at": utc_now(),
        "http_status": status,
        "missing": status == 404,
        "response_content_type": headers.get("Content-Type") or headers.get("content-type"),
        "body_is_json": parse_ok,
        "body": body_field,
        "fetcher_code_version": code_version(),
    }
    if extra:
        env.update(extra)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(env, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(path)
    return path


MAX_CONSECUTIVE_TRANSPORT_FAILURES = 3
MAX_RUN_SECONDS = 1800.0


def run_fetch(*, source: str, identifiers: list[str], out_dir: Path, build_url, headers: dict,
              pause: float, extra_for=None, log=log_flush,
              max_consecutive_failures: int = MAX_CONSECUTIVE_TRANSPORT_FAILURES,
              max_run_seconds: float = MAX_RUN_SECONDS) -> dict:
    """Fetch each identifier once. Existing envelopes are skipped (resume).

    Transport failures (timeouts, DNS, connection errors, retries exhausted) never create an
    envelope, so they are not counted as 404/missing and are retried on the next run. They are
    appended to errors.jsonl with kind=transport and counted under "errors" only. The run aborts
    after max_consecutive_failures failures in a row or after max_run_seconds of wall clock.
    """
    counts = {"planned": len(identifiers), "already_present": 0, "retrieved_ok": 0,
              "missing_404": 0, "errors": 0, "other_status": 0, "not_attempted": 0,
              "aborted": False, "abort_reason": None}
    run_start = time.monotonic()
    consecutive = 0
    err_log = out_dir / "errors.jsonl"
    out_dir.mkdir(parents=True, exist_ok=True)
    for i, ident in enumerate(identifiers, 1):
        path = out_dir / f"{key_for(ident)}.json"
        if path.exists():
            counts["already_present"] += 1
            continue
        if counts["aborted"]:
            counts["not_attempted"] += 1
            continue
        if time.monotonic() - run_start > max_run_seconds:
            counts["aborted"], counts["abort_reason"] = True, f"run exceeded {max_run_seconds:.0f}s"
            counts["not_attempted"] += 1
            log(f"[{source}] ABORT: {counts['abort_reason']}")
            continue
        url = build_url(ident)
        try:
            status, body, resp_headers = http_get(url, headers, log=log)
        except FatalTransportError as e:
            counts["errors"] += 1
            counts["aborted"], counts["abort_reason"] = True, str(e)
            with open(err_log, "a", encoding="utf-8") as f:
                f.write(json.dumps({"identifier": ident, "at": utc_now(), "kind": "fatal_tls",
                                    "error": str(e)}) + "\n")
            log(f"[{source}] ABORT: {e}")
            continue
        except RuntimeError as e:
            counts["errors"] += 1
            consecutive += 1
            with open(err_log, "a", encoding="utf-8") as f:
                f.write(json.dumps({"identifier": ident, "at": utc_now(), "kind": "transport",
                                    "error": str(e)}) + "\n")
            log(f"[{source} {i}/{len(identifiers)}] TRANSPORT ERROR {ident}")
            if consecutive >= max_consecutive_failures:
                counts["aborted"] = True
                counts["abort_reason"] = (f"{consecutive} consecutive transport failures "
                                          f"(network or API unreachable)")
                log(f"[{source}] ABORT: {counts['abort_reason']}")
            time.sleep(pause)
            continue
        consecutive = 0
        if status == 200:
            store_response(out_dir, ident, url, status, body, resp_headers,
                           extra_for(ident) if extra_for else None)
            counts["retrieved_ok"] += 1
            log(f"[{source} {i}/{len(identifiers)}] ok")
        elif status == 404:
            store_response(out_dir, ident, url, status, body, resp_headers,
                           extra_for(ident) if extra_for else None)
            counts["missing_404"] += 1
            log(f"[{source} {i}/{len(identifiers)}] 404 missing")
        else:
            counts["other_status"] += 1
            with open(err_log, "a", encoding="utf-8") as f:
                f.write(json.dumps({"identifier": ident, "at": utc_now(),
                                    "error": f"HTTP {status}"}) + "\n")
            log(f"[{source} {i}/{len(identifiers)}] HTTP {status} {ident}")
        time.sleep(pause)
    return counts
