#!/usr/bin/env python3
"""Fetch Crossref works records for a reproducible DBLP-derived DOI subset (stdlib only; optional certifi TLS fallback via fetch_common).

SCRIPT_VERSION 2.0.0 (Phase 15a). One envelope per DOI in datasets/raw/crossref/works/, named
sha256(doi).json, so the Phase 03 (1K slice) cache is reused: a cached DOI is never requested.

API (official public REST API, no signup needed):
  singleton  https://api.crossref.org/works/{doi}               (--batch-size 1)
  batched    https://api.crossref.org/works?filter=doi:a,doi:b&rows=N   (default 50 DOIs)
The batched form uses the documented `doi` filter; repeated values of one filter are OR-ed. DOIs
that contain a comma cannot be put in a filter and are fetched as singletons. The list response
items are stored per DOI, wrapped as {"message": item} so the stored shape equals a singleton
response (ORCID derivation reads message.author). A DOI absent from a successful batch response is
NOT recorded as missing: it is looked up again as a singleton (an alias DOI can resolve to a record
with another primary DOI) and that answer is stored (404 there = missing).

Rate limits (access-and-authentication page cited in the manifest): polite pool "Filters and
queries" 3 requests/s, concurrency 3; "DOI lookup" 10/s, concurrency 3. This script is
sequential with --pause 0.5 s (about 2 requests/s), under both. Backoff on timeouts, 429 and 5xx
(Retry-After honoured). A hard --max-requests cap counts every attempt, retries included.

Access pool: "polite" when SCHOLARCHAIN_CONTACT_EMAIL is set (read from the environment only, sent
in the User-Agent header only, never in a URL, a log line or a file).
Defaults are bounded: 100 works, 200 requests; more needs --allow-more and --max-requests.
Use --plan first: it makes no network request.
"""
import argparse
import json
import sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I does not add the script dir
import fetch_common as fc  # noqa: E402

SCRIPT_VERSION = "2.0.0"
BASE = "https://api.crossref.org/works/"
LIST = "https://api.crossref.org/works"
OUT = fc.REPO / "datasets" / "raw" / "crossref" / "works"
DEFAULT_BATCH = 50
FALLBACK_BYTES = 15000   # about 1.5 MB per 100 DOIs observed in Phase 03


def batchable(doi: str) -> bool:
    return "," not in doi


def batch_url(chunk) -> str:
    flt = ",".join("doi:" + d for d in chunk)
    return f"{LIST}?filter={quote(flt, safe=':,/')}&rows={len(chunk)}"


def parse_batch(chunk, body: bytes) -> dict:
    """{requested canonical DOI: {"status":"ok", ..., "message": item}} for DOIs in the response."""
    try:
        data = json.loads(body.decode("utf-8"))
        items = data["message"]["items"]
    except (ValueError, KeyError, TypeError, UnicodeDecodeError) as e:
        raise ValueError(f"not a Crossref list response: {type(e).__name__}")
    wanted = set(chunk)
    found = {}
    for it in items:
        raw = it.get("DOI") if isinstance(it, dict) else None
        if not isinstance(raw, str):
            continue
        d = fc.normalize_doi(raw) or raw.strip().lower()
        if d in wanted and d not in found:
            found[d] = {"status": "ok", "message-type": "work",
                        "message-version": data.get("message-version"), "message": it}
    return found


def main(argv=None, *, transport=None, sleep=None, log=fc.log_flush) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    fc.add_common_args(ap, default_batch=DEFAULT_BATCH, out=OUT)
    a = ap.parse_args(argv)
    fc.enforce_limit(a.limit, a.allow_more, "works")
    if a.batch_size < 1 or a.max_requests < 1:
        sys.exit("REFUSED: --batch-size and --max-requests must be at least 1")
    info = fc.load_dois(a.selection, a.limit)
    print(f"crossref {SCRIPT_VERSION}: {info['distinct_dois']} distinct DOIs from {a.selection.name}; "
          f"{fc.credentials_status()}", flush=True)
    if a.plan:
        fc.plan_summary(source="crossref", info=info, out_dir=a.out, batch_size=a.batch_size,
                        max_requests=a.max_requests, pause=a.pause, latency=a.assumed_latency,
                        fallback_bytes=FALLBACK_BYTES,
                        extra_lines=["  rate: polite pool list queries 3 req/s, concurrency 3 "
                                     "(manifest quote); this run is sequential"])
        return 0
    headers = {"User-Agent": fc.user_agent("crossref"), "Accept": "application/json"}
    counts = fc.run_batched(
        source="crossref", identifiers=info["dois"], out_dir=a.out, headers=headers, pause=a.pause,
        budget=fc.RequestBudget(a.max_requests), single_url=lambda d: BASE + fc.doi_path_segment(d),
        batch_url=batch_url, parse_batch=parse_batch, batch_size=a.batch_size, batchable=batchable,
        log=log, progress_every=a.progress_every, max_run_seconds=a.max_run_seconds,
        transport=transport, sleep=sleep)
    print(json.dumps(counts), flush=True)
    return 3 if counts["aborted"] else 0


if __name__ == "__main__":
    sys.exit(main())
