"""Shared helpers for the bounded, DOI-driven enrichment fetchers (stdlib only, except the optional certifi TLS fallback).

TLS: certificate verification is never disabled. If the interpreter has no CA store, the
stdlib-only route is to set SSL_CERT_FILE to a system CA bundle; otherwise the optional
certifi package is used when installed.

Secrets and personal data rules enforced here:
  * SCHOLARCHAIN_CONTACT_EMAIL and OPENALEX_API_KEY are read from os.environ only.
  * The contact email is sent only in the User-Agent header. It is never placed in a URL,
    never printed, and never written to disk. Stored request URLs pass through redact_url().
  * An API key is sent only in an Authorization header, never in a URL.

Version 2.1.0 (Phase 15a repair 01): Retry-After sleeps are capped at MAX_RETRY_AFTER_SECONDS (60 s);
rel_to_repo no longer follows the datasets/raw symlink; the --plan singleton allowance is 3% and labelled an assumption.
Version 2.0.0 (Phase 15a, scale fetching) adds, without changing the envelope format:
  * an injectable transport and sleep (tests use a fake transport, no network);
  * a hard per-source request budget that counts every HTTP attempt, retries included;
  * batched list lookups with per-DOI envelopes (same file names as singleton lookups, so the
    1K caches are reused), bisecting on 4xx so one bad DOI cannot poison a batch;
  * a progress log every N requests, a configurable run-time limit, and --plan support
    (no network) via plan_summary().
"""
from __future__ import annotations

import collections
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

FETCH_COMMON_VERSION = "2.1.0"
REPO = Path(__file__).resolve().parents[2]
DEFAULT_LIMIT = 100
DEFAULT_MAX_REQUESTS = 200  # hard per-source request cap unless --max-requests says otherwise
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
                 f"Re-run with --allow-more to override (explicit flag required; bounded by --max-requests).")


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




MAX_RETRY_AFTER_SECONDS = 60.0  # ceiling on one Retry-After sleep (server hints above this are capped)
SINGLETON_ALLOWANCE_PCT = 3  # ASSUMPTION: share of batch DOIs re-checked singly; observed 2.5% / 1.9% (see plan_summary)
MAX_REQUEST_SECONDS = 150.0  # total budget for one request, retries and sleeps included


class RequestCapReached(Exception):
    """The hard per-source request budget is exhausted. Stops the run; never retried."""


class RequestBudget:
    """Hard cap on HTTP attempts (retries count). consume() raises once the cap is reached."""

    def __init__(self, cap: int):
        self.cap = int(cap)
        self.used = 0

    def consume(self) -> None:
        if self.used >= self.cap:
            raise RequestCapReached(f"hard request cap of {self.cap} reached")
        self.used += 1

    @property
    def remaining(self) -> int:
        return max(0, self.cap - self.used)


def urllib_transport(url: str, headers: dict, timeout: float):
    """One HTTP GET attempt. Returns (status, body, headers) for ANY HTTP status.

    Raises FatalTransportError for a TLS verification failure and OSError subclasses
    (URLError, timeout, connection errors) for network failures. A transport used by tests has
    the same contract: callable(url, headers, timeout) -> (status, bytes, dict).
    """
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=tls_context()) as resp:
            return resp.status, resp.read(), dict(resp.headers.items())
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers.items())
    except urllib.error.URLError as e:
        if isinstance(getattr(e, "reason", None), ssl.SSLCertVerificationError):
            raise FatalTransportError(f"TLS certificate verification failed ({redact_url(url)})")
        raise


def http_get(url: str, headers: dict, timeout: float = 20.0, retries: int = 5,
             base_delay: float = 2.0, log=log_flush, *, transport=None, sleep=None,
             budget: RequestBudget | None = None, monotonic=None):
    """GET with retry and exponential backoff on timeouts, 429 and 5xx.

    Returns (status, body_bytes, response_headers). 404 and other non-retryable statuses are
    returned, not raised. Raises RuntimeError after the retries are exhausted and
    RequestCapReached when the budget is spent (every attempt, retries included, consumes one
    request). The URL is logged only after redaction, so a log line can never contain the
    contact email or a key. transport/sleep/monotonic are injectable for tests.
    """
    transport = transport or urllib_transport
    sleep = sleep or time.sleep
    monotonic = monotonic or time.monotonic
    last = "unknown"
    started = monotonic()
    for attempt in range(retries + 1):
        if monotonic() - started > MAX_REQUEST_SECONDS:
            last += " (per-request time budget exceeded)"
            break
        if budget is not None:
            budget.consume()
        delay = None
        try:
            status, body, resp_headers = transport(url, headers, timeout)
        except FatalTransportError:
            raise
        except (OSError, socket.timeout, TimeoutError, ConnectionError) as e:
            last = type(e).__name__
        else:
            if status not in RETRY_STATUS:
                return status, body, resp_headers
            last = f"HTTP {status}"
            ra = {k.lower(): v for k, v in resp_headers.items()}.get("retry-after")
            delay = min(float(ra), MAX_RETRY_AFTER_SECONDS) if ra and ra.isdigit() else None
        if attempt == retries:
            break
        wait = delay if delay is not None else base_delay * (2 ** attempt) + random.uniform(0, 0.5)
        log(f"  retry {attempt + 1}/{retries} after {last}; sleeping {wait:.1f}s ({redact_url(url)})")
        sleep(wait)
    raise RuntimeError(f"gave up after {retries + 1} attempts: {last} ({redact_url(url)})")


def envelope_path(directory: Path, identifier: str) -> Path:
    return directory / f"{key_for(identifier)}.json"


UNVERIFIED_MISS = "absent_from_batch_response"


def is_cached(directory: Path, identifier: str) -> bool:
    """True when a usable envelope exists. An envelope written by the first 2.0.0 smoke run that
    only says "absent from the batch response" is NOT usable: such a miss is unverified (an alias
    DOI can resolve to a work with another primary DOI), so the identifier is looked up again."""
    path = envelope_path(directory, identifier)
    if not path.exists():
        return False
    try:
        with open(path, encoding="utf-8") as f:
            head = f.read(4096)
        if UNVERIFIED_MISS in head:
            return json.loads(path.read_text(encoding="utf-8")).get("missing_reason") != UNVERIFIED_MISS
    except (OSError, ValueError):
        return True
    return True


def supersede_unverified(directory: Path, identifier: str) -> None:
    """Keep (never delete) an unverified-miss envelope under a non-.json name before it is replaced."""
    path = envelope_path(directory, identifier)
    if path.exists() and not is_cached(directory, identifier):
        path.rename(path.with_name(path.name + ".superseded-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")))


def store_response(directory: Path, identifier: str, request_url: str, status: int,
                   body: bytes, headers: dict, extra: dict | None = None) -> Path:
    """Write one raw-response envelope. Never overwrites an existing file."""
    directory.mkdir(parents=True, exist_ok=True)
    path = envelope_path(directory, identifier)
    supersede_unverified(directory, identifier)
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
DEFAULT_PROGRESS_EVERY = 50


def _append_error(err_log: Path, record: dict) -> None:
    with open(err_log, "a", encoding="utf-8") as f:
        f.write(json.dumps(record) + "\n")


def new_counts(planned: int) -> dict:
    return {"planned": planned, "already_present": 0, "retrieved_ok": 0, "missing_404": 0,
            "absent_from_batch": 0, "errors": 0, "other_status": 0, "not_attempted": 0,
            "requests_made": 0, "batch_requests": 0, "single_requests": 0, "bisections": 0,
            "aborted": False, "abort_reason": None}


def run_batched(*, source: str, identifiers: list[str], out_dir: Path, headers: dict, pause: float,
                budget: RequestBudget, single_url, batch_url=None, parse_batch=None,
                batch_size: int = 1, batchable=None, single_request_url=None, extra_for=None,
                log=log_flush, progress_every: int = DEFAULT_PROGRESS_EVERY,
                max_consecutive_failures: int = MAX_CONSECUTIVE_TRANSPORT_FAILURES,
                max_run_seconds: float = MAX_RUN_SECONDS, transport=None, sleep=None,
                monotonic=None) -> dict:
    """Cache-first, resumable, capped fetch. One envelope per identifier.

    Cache-first/resume: an identifier whose envelope file exists is never requested; envelopes
    are written as soon as their response arrives, so an interrupted run loses at most the
    request in flight. Identifiers are de-duplicated, order preserved.

    Modes: identifiers for which batchable(ident) is true (and batch_size > 1 with batch_url and
    parse_batch given) are grouped in chunks of batch_size. batch_url(chunk) builds the list URL.
    parse_batch(chunk, body_bytes) -> {ident: body_obj} for the identifiers found (ValueError on
    an unusable body). An identifier of a successful batch that is absent from the response gets
    no envelope from the batch; it is re-queued as a singleton lookup (an alias DOI can resolve to a
    work with a different primary DOI), and the singleton answer is stored. A 4xx answer to a
    batch of more than one splits it in two and retries the halves (bisection), each request
    counted against the budget; a single identifier that still fails is retried as a singleton
    lookup via single_url(ident) (404 stored as a missing envelope, as before).

    Never stored as responses (so they are retried on the next run): transport failures,
    exhausted retries, non-404 error statuses. They go to errors.jsonl and counts["errors"] /
    counts["other_status"]. Stops (aborted) on: hard request cap, fatal TLS error,
    max_consecutive_failures failures in a row, or max_run_seconds of wall clock.
    """
    monotonic = monotonic or time.monotonic
    sleep = sleep or time.sleep
    seen, ids = set(), []
    for i in identifiers:
        if i not in seen:
            seen.add(i)
            ids.append(i)
    counts = new_counts(len(ids))
    out_dir.mkdir(parents=True, exist_ok=True)
    err_log = out_dir / "errors.jsonl"
    run_start = monotonic()
    state = {"consecutive": 0, "bucket": 0}
    verify: set = set()
    use_batch = bool(batch_url and parse_batch and batch_size > 1)
    can_batch = batchable or (lambda i: True)

    pending = []
    for ident in ids:
        if is_cached(out_dir, ident):
            counts["already_present"] += 1
        else:
            pending.append(ident)
    log(f"[{source}] start: planned={len(ids)} cached={counts['already_present']} "
        f"to_fetch={len(pending)} batch_size={batch_size if use_batch else 1} "
        f"request_cap={budget.cap} pause={pause}s")

    queue = collections.deque()
    if use_batch:
        batchables = [i for i in pending if can_batch(i)]
        singles = [i for i in pending if not can_batch(i)]
        for n in range(0, len(batchables), batch_size):
            queue.append(("batch", batchables[n:n + batch_size]))
        for i in singles:
            queue.append(("single", [i]))
    else:
        for i in pending:
            queue.append(("single", [i]))

    def abort(reason: str) -> None:
        counts["aborted"], counts["abort_reason"] = True, reason
        log(f"[{source}] ABORT: {reason}")

    def progress(force: bool = False) -> None:
        bucket = (budget.used // progress_every) if progress_every else 0
        if force or (progress_every and bucket != state["bucket"]):
            state["bucket"] = bucket
            log(f"[{source}] progress requests={budget.used}/{budget.cap} ok={counts['retrieved_ok']} "
                f"missing={counts['missing_404']} absent_from_batch={counts['absent_from_batch']} errors={counts['errors']} "
                f"other={counts['other_status']} cached={counts['already_present']} "
                f"elapsed={monotonic() - run_start:.0f}s")

    def req_url(ident: str) -> str:
        return single_request_url(ident) if single_request_url else single_url(ident)

    def store(ident, url, status, body, hdrs, extra=None):
        merged = dict(extra_for(ident)) if extra_for else {}
        if extra:
            merged.update(extra)
        store_response(out_dir, ident, url, status, body, hdrs, merged or None)

    while queue:
        mode, chunk = queue.popleft()
        chunk = [i for i in chunk if not is_cached(out_dir, i)]
        if not chunk:
            continue
        if counts["aborted"]:
            counts["not_attempted"] += len(chunk)
            continue
        if monotonic() - run_start > max_run_seconds:
            abort(f"run exceeded {max_run_seconds:.0f}s")
            counts["not_attempted"] += len(chunk)
            continue
        url = batch_url(chunk) if mode == "batch" else single_url(chunk[0])
        try:
            status, body, resp_headers = http_get(url, headers, log=log, transport=transport,
                                                  sleep=sleep, budget=budget, monotonic=monotonic)
        except RequestCapReached as e:
            counts["requests_made"] = budget.used
            abort(str(e))
            counts["not_attempted"] += len(chunk)
            continue
        except FatalTransportError as e:
            counts["errors"] += len(chunk)
            _append_error(err_log, {"identifiers": len(chunk), "at": utc_now(), "kind": "fatal_tls",
                                    "error": str(e)})
            abort(str(e))
            continue
        except RuntimeError as e:
            counts["errors"] += len(chunk)
            state["consecutive"] += 1
            _append_error(err_log, {"identifiers": chunk if len(chunk) <= 5 else len(chunk),
                                    "at": utc_now(), "kind": "transport", "error": str(e)})
            log(f"[{source}] TRANSPORT ERROR ({len(chunk)} ids): {e}")
            if state["consecutive"] >= max_consecutive_failures:
                abort(f"{state['consecutive']} consecutive transport failures "
                      f"(network or API unreachable)")
            progress()
            sleep(pause)
            continue
        counts["requests_made"] = budget.used
        counts["batch_requests" if mode == "batch" else "single_requests"] += 1
        state["consecutive"] = 0
        if mode == "single":
            ident = chunk[0]
            vx = {"fetched_via": "single_after_batch_miss"} if ident in verify else None
            if status == 200:
                store(ident, req_url(ident), status, body, resp_headers, vx)
                counts["retrieved_ok"] += 1
            elif status == 404:
                store(ident, req_url(ident), status, body, resp_headers, vx)
                counts["missing_404"] += 1
            else:
                counts["other_status"] += 1
                _append_error(err_log, {"identifier": ident, "at": utc_now(), "error": f"HTTP {status}"})
                log(f"[{source}] HTTP {status} {ident}")
        else:
            if status == 200:
                try:
                    found = parse_batch(chunk, body)
                except ValueError as e:
                    counts["errors"] += len(chunk)
                    _append_error(err_log, {"identifiers": len(chunk), "at": utc_now(),
                                            "kind": "unparsable_batch", "error": str(e)})
                    log(f"[{source}] unparsable batch response ({len(chunk)} ids): {e}")
                else:
                    for ident in chunk:
                        if ident in found:
                            store(ident, req_url(ident), 200,
                                  json.dumps(found[ident], ensure_ascii=False).encode("utf-8"),
                                  resp_headers, {"fetched_via": "batch", "batch_size": len(chunk)})
                            counts["retrieved_ok"] += 1
                        else:
                            # Not stored as a miss: an alias DOI can resolve to a work whose primary
                            # DOI differs, so the singleton lookup decides (404 there = missing).
                            verify.add(ident)
                            counts["absent_from_batch"] += 1
                            queue.append(("single", [ident]))
            elif len(chunk) > 1:
                counts["bisections"] += 1
                half = len(chunk) // 2
                queue.appendleft(("batch", chunk[half:]))
                queue.appendleft(("batch", chunk[:half]))
                log(f"[{source}] HTTP {status} for a batch of {len(chunk)}; splitting")
            else:
                queue.appendleft(("single", chunk))  # last resort: singleton lookup
        progress()
        sleep(pause)
    counts["requests_made"] = budget.used
    progress(force=True)
    return counts


def run_fetch(*, source: str, identifiers: list[str], out_dir: Path, build_url, headers: dict,
              pause: float, extra_for=None, log=log_flush,
              max_consecutive_failures: int = MAX_CONSECUTIVE_TRANSPORT_FAILURES,
              max_run_seconds: float = MAX_RUN_SECONDS, budget: RequestBudget | None = None,
              progress_every: int = DEFAULT_PROGRESS_EVERY, transport=None, sleep=None,
              monotonic=None) -> dict:
    """Singleton, cache-first, capped fetch (legacy entry point; see run_batched)."""
    return run_batched(source=source, identifiers=identifiers, out_dir=out_dir, headers=headers,
                       pause=pause, budget=budget or RequestBudget(max(1, len(identifiers)) * 6),
                       single_url=build_url, extra_for=extra_for, log=log,
                       progress_every=progress_every,
                       max_consecutive_failures=max_consecutive_failures,
                       max_run_seconds=max_run_seconds, transport=transport, sleep=sleep,
                       monotonic=monotonic)


# ----------------------------------------------------------------------------- planning
def load_dois(selection: Path, limit: int, seed: int = ENRICH_SEED) -> dict:
    """Reproducible DOI list for a selection: rank rows by sha256(seed|enrichment|key), take
    `limit` rows, canonicalise and de-duplicate their DOIs (order kept). Counts are reported."""
    rows = read_selection(selection)
    subset = enrichment_subset(selection, limit, seed)
    dois, seen, invalid = [], set(), 0
    for r in subset:
        d = normalize_doi(r.get("doi", ""))
        if not d:
            invalid += 1
        elif d not in seen:
            seen.add(d)
            dois.append(d)
    return {"works_in_selection": len(rows), "works_considered": len(subset),
            "works_with_valid_doi": len(subset) - invalid, "invalid_doi": invalid,
            "distinct_dois": len(dois), "dois": dois}


def mean_envelope_bytes(directory: Path, fallback: int, sample: int = 200) -> tuple[int, str]:
    """Mean size of up to `sample` cached envelopes (sorted by name, deterministic)."""
    files = sorted(p for p in directory.glob("*.json") if p.is_file())[:sample] if directory.is_dir() else []
    if len(files) >= 10:
        return int(sum(p.stat().st_size for p in files) / len(files)), f"measured over {len(files)} cached files"
    return fallback, "fallback constant (cache too small to measure)"


def human_duration(seconds: float) -> str:
    s = int(round(seconds))
    return f"{s // 3600} h {s % 3600 // 60} min" if s >= 3600 else f"{s // 60} min {s % 60} s"


def plan_summary(*, source: str, info: dict, out_dir: Path, batch_size: int, max_requests: int,
                 pause: float, latency: float, fallback_bytes: int, extra_lines=()) -> dict:
    """Print the --plan report (no network) and return the numbers. Works on canonical DOIs."""
    dois = info["dois"]
    cached = sum(1 for d in dois if is_cached(out_dir, d))
    to_fetch = len(dois) - cached
    requests = -(-to_fetch // batch_size) if to_fetch else 0
    # ASSUMPTION, not a guarantee: SINGLETON_ALLOWANCE_PCT of batch DOIs need a singleton re-check.
    # Observed in the Phase 15a 50K run (about 49.7K DOIs): Crossref 1,225 (2.5%), OpenAlex 949 (1.9%).
    allowance = -(-to_fetch * SINGLETON_ALLOWANCE_PCT // 100) if batch_size > 1 else 0
    secs = (requests + allowance) * (pause + latency)
    avg, how = mean_envelope_bytes(out_dir, fallback_bytes)
    disk = avg * to_fetch
    fits = requests + allowance <= max_requests
    lines = [
        f"[{source}] PLAN (no network request is made)",
        f"  selection works: {info['works_in_selection']}; considered (limit): {info['works_considered']}; "
        f"with valid DOI: {info['works_with_valid_doi']}; invalid DOI: {info['invalid_doi']}; "
        f"distinct DOIs: {info['distinct_dois']}",
        f"  cached: {cached}; to fetch: {to_fetch}",
        f"  batch size: {batch_size}; requests after batching: {requests}"
        + (f"; plus up to {allowance} singleton re-checks of DOIs absent from batch responses "
           f"({SINGLETON_ALLOWANCE_PCT}% allowance, an ASSUMPTION sized above the observed rates: "
           f"Crossref 1,225 and OpenAlex 949 re-checks over about 49.7K DOIs in the Phase 15a 50K run)" if allowance else "")
        + f"; total up to {requests + allowance} (hard cap {max_requests}: "
        f"{'OK' if fits else 'TOO LOW, run would stop with ids left'})",
        f"  expected duration: {human_duration(secs)} at pause {pause}s + assumed latency {latency}s per request",
        f"  disk estimate: {disk / 1e6:.1f} MB ({avg} bytes per envelope, {how})",
    ]
    lines.extend(extra_lines)
    for ln in lines:
        print(ln, flush=True)
    return {"cached": cached, "to_fetch": to_fetch, "requests": requests, "allowance": allowance, "seconds": secs,
            "disk_bytes": disk, "fits_cap": fits}


def add_common_args(ap, *, default_batch: int, out: Path) -> None:
    ap.add_argument("--selection", type=Path, default=DEFAULT_SELECTION)
    ap.add_argument("--limit", type=int, default=DEFAULT_LIMIT,
                    help="works considered, ranked by sha256(441|enrichment|key) (default %(default)s)")
    ap.add_argument("--allow-more", action="store_true", help=f"allow more than {DEFAULT_LIMIT}")
    ap.add_argument("--pause", type=float, default=0.5, help="seconds between requests")
    ap.add_argument("--out", type=Path, default=out)
    ap.add_argument("--batch-size", type=int, default=default_batch,
                    help="DOIs per list request; 1 = singleton lookups (default %(default)s)")
    ap.add_argument("--max-requests", type=int, default=DEFAULT_MAX_REQUESTS,
                    help="HARD cap on HTTP requests incl. retries (default %(default)s)")
    ap.add_argument("--max-run-seconds", type=float, default=MAX_RUN_SECONDS)
    ap.add_argument("--progress-every", type=int, default=DEFAULT_PROGRESS_EVERY,
                    help="log a progress line every N requests (default %(default)s)")
    ap.add_argument("--assumed-latency", type=float, default=1.0,
                    help="seconds per request assumed by --plan (default %(default)s)")
    ap.add_argument("--plan", "--dry-run", dest="plan", action="store_true",
                    help="print counts, requests, duration, disk; make no network request")


def rel_to_repo(p: Path) -> str:
    try:
        # abspath, not resolve(): never follow the datasets/raw symlink (keeps paths repo-relative)
        return Path(os.path.abspath(p)).relative_to(REPO).as_posix()
    except ValueError:
        return str(p)
