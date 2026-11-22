#!/usr/bin/env python3
"""Fetch OpenAlex work records for a reproducible DBLP-derived DOI subset (stdlib only; optional certifi TLS fallback via fetch_common).

SCRIPT_VERSION 2.0.0 (Phase 15a). One envelope per DOI in datasets/raw/openalex/works/, named
sha256(doi).json, so the Phase 03 (1K slice) cache is reused: a cached DOI is never requested.

API (official REST API; documentation https://help.openalex.org/):
  singleton  https://api.openalex.org/works/doi:{doi}                  (--batch-size 1; free)
  batched    https://api.openalex.org/works?filter=doi:u1|u2|...&per_page=100   (default 100 DOIs)
Per the filtering page, up to 100 values can be OR-ed with `|` in one filter and per_page=100
returns them all. Each DOI is sent in doi.org URL form, as in the documented example. DOIs that
contain `,`, `|` or `"` cannot be put in an OR filter and are fetched as singletons.
Cost (example-costs page): a list+filter call is $0.10 per 1,000 calls (1 credit); singleton
lookups are free. Keyless budget is $0.10/day (about 1,000 list calls), a free key $1/day. --plan
prints the cost of the planned calls.

Authentication: optional OPENALEX_API_KEY (environment only, sent as an Authorization bearer
header, never in a URL). The contact email goes only in the User-Agent header. Rate limit quoted in
the manifest: 10 requests/s without a key; this run is sequential with --pause 0.5 s.
The list response items are stored per DOI, same shape as a singleton response. A DOI absent
from a successful batch response is NOT recorded as missing: OpenAlex resolves some alias DOIs to
a work whose primary DOI differs (seen in the Phase 15a smoke run: the list returned that work
under another DOI), so it is looked up again as a singleton (free) and that answer is stored.
A hard --max-requests cap counts every attempt, retries included. Defaults are bounded: 100
works, 200 requests; more needs --allow-more and --max-requests. Use --plan first.
"""
import argparse
import json
import os
import sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I does not add the script dir
import fetch_common as fc  # noqa: E402

SCRIPT_VERSION = "2.0.0"
BASE = "https://api.openalex.org/works/doi:"
LIST = "https://api.openalex.org/works"
OUT = fc.REPO / "datasets" / "raw" / "openalex" / "works"
DEFAULT_BATCH = 100
MAX_BATCH = 100          # documented OR limit
FALLBACK_BYTES = 25000   # about 2.4 MB per 100 DOIs observed in Phase 03
CREDIT_USD = 0.0001      # list+filter, per call
KEYLESS_DAILY_USD = 0.10
KEY_DAILY_USD = 1.00


def batchable(doi: str) -> bool:
    return not any(c in doi for c in ',|"')


def batch_url(chunk) -> str:
    flt = "|".join("https://doi.org/" + d for d in chunk)
    return f"{LIST}?filter=doi:{quote(flt, safe=':/')}&per_page={min(100, len(chunk))}"


def _strip_doi(raw: str) -> str:
    low = raw.strip().lower()
    for p in ("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/"):
        if low.startswith(p):
            return low[len(p):]
    return low


def parse_batch(chunk, body: bytes) -> dict:
    try:
        data = json.loads(body.decode("utf-8"))
        results = data["results"]
    except (ValueError, KeyError, TypeError, UnicodeDecodeError) as e:
        raise ValueError(f"not an OpenAlex list response: {type(e).__name__}")
    wanted = set(chunk)
    found = {}
    for w in results:
        raw = w.get("doi") if isinstance(w, dict) else None
        if not isinstance(raw, str):
            continue
        for cand in (fc.normalize_doi(raw), _strip_doi(raw)):
            if cand in wanted:
                found.setdefault(cand, w)
                break
    return found


def main(argv=None, *, transport=None, sleep=None, log=fc.log_flush) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    fc.add_common_args(ap, default_batch=DEFAULT_BATCH, out=OUT)
    a = ap.parse_args(argv)
    fc.enforce_limit(a.limit, a.allow_more, "works")
    if not 1 <= a.batch_size <= MAX_BATCH or a.max_requests < 1:
        sys.exit(f"REFUSED: --batch-size must be 1..{MAX_BATCH} (documented OR limit) and --max-requests >= 1")
    info = fc.load_dois(a.selection, a.limit)
    print(f"openalex {SCRIPT_VERSION}: {info['distinct_dois']} distinct DOIs from {a.selection.name}; "
          f"{fc.credentials_status()}", flush=True)
    if a.plan:
        res = fc.plan_summary(source="openalex", info=info, out_dir=a.out, batch_size=a.batch_size,
                              max_requests=a.max_requests, pause=a.pause, latency=a.assumed_latency,
                              fallback_bytes=FALLBACK_BYTES)
        keyed = bool(os.environ.get("OPENALEX_API_KEY", "").strip())
        daily = KEY_DAILY_USD if keyed else KEYLESS_DAILY_USD
        cost = 0.0 if a.batch_size == 1 else res["requests"] * CREDIT_USD
        print(f"  usage: {'singleton lookups are free' if a.batch_size == 1 else 'list+filter calls at $0.0001 each'}; "
              f"estimated cost ${cost:.4f} vs daily budget ${daily:.2f} "
              f"({'key set' if keyed else 'keyless'}): {'within budget' if cost <= daily else 'EXCEEDS the daily budget'}",
              flush=True)
        print("  rate: 10 requests/s without a key (manifest quote); this run is sequential", flush=True)
        return 0
    headers = {"User-Agent": fc.user_agent("openalex"), "Accept": "application/json"}
    key = os.environ.get("OPENALEX_API_KEY", "").strip()
    if key:
        headers["Authorization"] = f"Bearer {key}"
    counts = fc.run_batched(
        source="openalex", identifiers=info["dois"], out_dir=a.out, headers=headers, pause=a.pause,
        budget=fc.RequestBudget(a.max_requests), single_url=lambda d: BASE + fc.doi_path_segment(d),
        batch_url=batch_url, parse_batch=parse_batch, batch_size=a.batch_size, batchable=batchable,
        log=log, progress_every=a.progress_every, max_run_seconds=a.max_run_seconds,
        transport=transport, sleep=sleep)
    print(json.dumps(counts), flush=True)
    return 3 if counts["aborted"] else 0


if __name__ == "__main__":
    sys.exit(main())
