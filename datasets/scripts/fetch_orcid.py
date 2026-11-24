#!/usr/bin/env python3
"""Fetch public ORCID works summaries (stdlib only; optional certifi TLS fallback via fetch_common) for iDs found in the retrieved Crossref/OpenAlex data.

ORCID is DOI-driven here: iDs are derived only from the stored Crossref (author[].ORCID) and
OpenAlex (authorships[].author.orcid) envelopes of the selected DOIs. Nothing is searched by
name. Endpoint: https://pub.orcid.org/v3.0/{orcid-id}/works (public data only, JSON).

Credentials: ORCID documents OAuth client credentials plus a /read-public token for the
Public API. This script sends no token. If the API refuses the unauthenticated request
(HTTP 401/403) the response is recorded as an error and the run reports it; it never tries
to obtain or fabricate credentials. See datasets/dataset-manifest.yaml.

Privacy: only the /works summary is requested (no name, email, employment or address
sections). Raw envelopes stay in gitignored datasets/raw/orcid/works/. The default maximum
is 100 iDs; more requires --allow-more. Existing envelopes are never re-fetched.

SCRIPT_VERSION 2.0.0 (Phase 15a). New: --selection restricts the iD derivation to the DOIs of
that selection (so a scale run only fetches iDs seen in records of those works); the iD cap
(--limit) takes the first N iDs in a fixed hash order, so a larger cap extends a smaller one;
cache-first and resumable; --max-requests is a HARD cap counting retries; progress log every
--progress-every requests; --plan makes no network request and reports counts, requests,
duration and disk, including an estimate of the iD count for a not-yet-fetched selection.
The iD list is written to --ids-file (raw data, never overwritten: if the file exists a
timestamped sibling is written instead).
"""
import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I does not add the script dir
import fetch_common as fc  # noqa: E402

SCRIPT_VERSION = "2.0.0"
BASE = "https://pub.orcid.org/v3.0/"
RAW = fc.REPO / "datasets" / "raw"
OUT = RAW / "orcid" / "works"
IDS_FILE = RAW / "orcid" / "orcid_ids.json"
FALLBACK_BYTES = 250000        # about 247 KB per iD observed in Phase 03 (24.7 MB for 100 iDs)
FALLBACK_IDS_PER_DOI = 2.94    # Phase 03: 294 distinct iDs found in the records of 100 DOIs


def normalise_orcid(value):
    if not value or not isinstance(value, str):
        return None
    v = value.strip().rsplit("/", 1)[-1].upper()
    return v if fc.ORCID_RE.match(v) else None


def derive_ids(crossref_dir: Path, openalex_dir: Path, dois=None) -> dict:
    """Return {orcid: {"crossref": n, "openalex": n, "dois": [...]}} from stored envelopes.

    dois: optional iterable of canonical DOIs; when given only the envelopes of those DOIs are read
    (envelope file name is sha256(doi)), otherwise every envelope in the directories is read.
    """
    found: dict = {}
    for src, directory in (("crossref", crossref_dir), ("openalex", openalex_dir)):
        if dois is None:
            paths = sorted(directory.glob("*.json"))
        else:
            paths = [fc.envelope_path(directory, d) for d in dois]
        for p in paths:
            if p.name == "errors.jsonl" or not p.exists():
                continue
            env = json.loads(p.read_text(encoding="utf-8"))
            if env.get("missing") or not env.get("body_is_json") or not isinstance(env.get("body"), dict):
                continue
            body = env["body"]
            if src == "crossref":
                ids = [normalise_orcid(a.get("ORCID")) for a in (body.get("message") or {}).get("author", [])]
            else:
                ids = [normalise_orcid((a.get("author") or {}).get("orcid"))
                       for a in body.get("authorships", [])]
            for oid in filter(None, ids):
                rec = found.setdefault(oid, {"crossref": 0, "openalex": 0, "dois": set()})
                rec[src] += 1
                rec["dois"].add(env["identifier"])
    return {k: {**v, "dois": sorted(v["dois"])} for k, v in found.items()}


def order_ids(found) -> list:
    return sorted(found, key=lambda o: hashlib.sha256(f"{fc.ENRICH_SEED}|orcid|{o}".encode()).hexdigest())


def write_ids_file(path: Path, summary: dict) -> Path:
    """Raw data is never overwritten: an existing file is kept and a timestamped sibling written."""
    path.parent.mkdir(parents=True, exist_ok=True)
    target = path
    if path.exists():
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        target = path.with_name(f"{path.stem}.{stamp}{path.suffix}")
        if target.exists():
            return target
    target.write_text(json.dumps(summary, indent=1), encoding="utf-8")
    return target


def main(argv=None, *, transport=None, sleep=None, log=fc.log_flush) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--crossref-dir", type=Path, default=RAW / "crossref" / "works")
    ap.add_argument("--openalex-dir", type=Path, default=RAW / "openalex" / "works")
    ap.add_argument("--selection", type=Path, default=None,
                    help="only derive iDs from the records of this selection's DOIs (default: all stored records)")
    ap.add_argument("--selection-limit", type=int, default=10**9,
                    help="works of the selection considered, as in the Crossref/OpenAlex runs (default: all)")
    ap.add_argument("--limit", type=int, default=fc.DEFAULT_LIMIT, help="cap on ORCID iDs (default %(default)s)")
    ap.add_argument("--allow-more", action="store_true", help=f"allow more than {fc.DEFAULT_LIMIT} ORCID iDs")
    ap.add_argument("--pause", type=float, default=0.5)
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--ids-file", type=Path, default=IDS_FILE)
    ap.add_argument("--max-requests", type=int, default=fc.DEFAULT_MAX_REQUESTS,
                    help="HARD cap on HTTP requests incl. retries (default %(default)s)")
    ap.add_argument("--max-run-seconds", type=float, default=fc.MAX_RUN_SECONDS)
    ap.add_argument("--progress-every", type=int, default=fc.DEFAULT_PROGRESS_EVERY)
    ap.add_argument("--assumed-latency", type=float, default=1.0)
    ap.add_argument("--plan", "--dry-run", dest="plan", action="store_true",
                    help="print counts, requests, duration, disk; make no network request")
    a = ap.parse_args(argv)
    fc.enforce_limit(a.limit, a.allow_more, "ORCID iDs")
    if a.max_requests < 1:
        sys.exit("REFUSED: --max-requests must be at least 1")

    dois = None
    info = None
    if a.selection is not None:
        info = fc.load_dois(a.selection, a.selection_limit)
        dois = info["dois"]
    found = derive_ids(a.crossref_dir, a.openalex_dir, dois)
    ordered = order_ids(found)
    planned = ordered[: a.limit]
    summary = {
        "derived_at": fc.utc_now(), "script_version": SCRIPT_VERSION,
        "selection": a.selection.name if a.selection else None,
        "ids_found_distinct": len(found),
        "ids_found_in_crossref": sum(1 for v in found.values() if v["crossref"]),
        "ids_found_in_openalex": sum(1 for v in found.values() if v["openalex"]),
        "ids_planned_for_retrieval": len(planned), "limit": a.limit,
        "ids": {o: found[o] for o in ordered},
    }
    print(f"orcid {SCRIPT_VERSION}: found {len(found)} distinct iDs (crossref {summary['ids_found_in_crossref']}, "
          f"openalex {summary['ids_found_in_openalex']}); planned {len(planned)}; {fc.credentials_status()}", flush=True)
    if a.plan:
        cached = sum(1 for o in planned if fc.is_cached(a.out, o))
        to_fetch = len(planned) - cached
        avg, how = fc.mean_envelope_bytes(a.out, FALLBACK_BYTES)
        secs = to_fetch * (a.pause + a.assumed_latency)
        print("[orcid] PLAN (no network request is made)", flush=True)
        print(f"  iDs derivable now from stored records: {len(found)}; cap (--limit): {a.limit}; planned: {len(planned)}", flush=True)
        print(f"  cached: {cached}; to fetch: {to_fetch}; requests (one per iD): {to_fetch} "
              f"(hard cap {a.max_requests}: {'OK' if to_fetch <= a.max_requests else 'TOO LOW, run would stop with ids left'})", flush=True)
        print(f"  expected duration: {fc.human_duration(secs)} at pause {a.pause}s + assumed latency {a.assumed_latency}s per request", flush=True)
        print(f"  disk estimate: {avg * to_fetch / 1e6:.1f} MB ({avg} bytes per envelope, {how})", flush=True)
        if info is not None:
            with_env = sum(1 for d in dois if fc.envelope_path(a.crossref_dir, d).exists()
                           or fc.envelope_path(a.openalex_dir, d).exists())
            if with_env >= 50:
                ratio, basis = len(found) / with_env, f"{with_env} DOIs with stored records in this selection"
            else:
                ratio, basis = FALLBACK_IDS_PER_DOI, "Phase 03 ratio (294 distinct iDs for 100 DOIs)"
            est = int(round(ratio * len(dois)))
            est_secs = est * (a.pause + a.assumed_latency)
            print(f"  ESTIMATE for the whole selection (not observed): about {est} distinct iDs "
                  f"({ratio:.2f} per DOI, {basis}); fetching all would take about "
                  f"{fc.human_duration(est_secs)} and about {est * avg / 1e9:.1f} GB. "
                  f"The cap --limit {a.limit} bounds the real run.", flush=True)
            after = min(a.limit, est)
            after_fetch = max(0, after - cached)
            print(f"  EXPECTED REAL RUN once Crossref/OpenAlex are fetched (estimate): planned {after} iDs, "
                  f"about {after_fetch} to fetch, about {fc.human_duration(after_fetch * (a.pause + a.assumed_latency))} "
                  f"and about {after_fetch * avg / 1e9:.2f} GB; needs --max-requests >= {after_fetch}"
                  f" (this run: {a.max_requests}).", flush=True)
        return 0
    ids_written = write_ids_file(a.ids_file, summary)
    headers = {"User-Agent": fc.user_agent("orcid"), "Accept": "application/json"}
    counts = fc.run_batched(
        source="orcid", identifiers=planned, out_dir=a.out, headers=headers, pause=a.pause,
        budget=fc.RequestBudget(a.max_requests),
        single_url=lambda o: BASE + fc.safe_path_segment(o) + "/works",
        log=log, progress_every=a.progress_every, max_run_seconds=a.max_run_seconds,
        transport=transport, sleep=sleep)
    counts["ids_found_distinct"] = len(found)
    print(f"ids file: {fc.rel_to_repo(ids_written)}", flush=True)
    print(json.dumps(counts), flush=True)
    return 3 if counts["aborted"] else 0


if __name__ == "__main__":
    sys.exit(main())
