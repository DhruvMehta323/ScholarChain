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
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import fetch_common as fc

BASE = "https://pub.orcid.org/v3.0/"
RAW = fc.REPO / "datasets" / "raw"
OUT = RAW / "orcid" / "works"
IDS_FILE = RAW / "orcid" / "orcid_ids.json"


def normalise_orcid(value):
    if not value or not isinstance(value, str):
        return None
    v = value.strip().rsplit("/", 1)[-1].upper()
    return v if fc.ORCID_RE.match(v) else None


def derive_ids(crossref_dir: Path, openalex_dir: Path) -> dict:
    """Return {orcid: {"crossref": n, "openalex": n, "dois": [...]}} from stored envelopes."""
    found: dict = {}
    for src, directory in (("crossref", crossref_dir), ("openalex", openalex_dir)):
        for p in sorted(directory.glob("*.json")):
            if p.name == "errors.jsonl":
                continue
            env = json.loads(p.read_text(encoding="utf-8"))
            if env.get("missing") or not env.get("body_is_json"):
                continue
            body = env["body"]
            if src == "crossref":
                ids = [normalise_orcid(a.get("ORCID")) for a in body.get("message", {}).get("author", [])]
            else:
                ids = [normalise_orcid((a.get("author") or {}).get("orcid"))
                       for a in body.get("authorships", [])]
            for oid in filter(None, ids):
                rec = found.setdefault(oid, {"crossref": 0, "openalex": 0, "dois": set()})
                rec[src] += 1
                rec["dois"].add(env["identifier"])
    return {k: {**v, "dois": sorted(v["dois"])} for k, v in found.items()}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--crossref-dir", type=Path, default=RAW / "crossref" / "works")
    ap.add_argument("--openalex-dir", type=Path, default=RAW / "openalex" / "works")
    ap.add_argument("--limit", type=int, default=fc.DEFAULT_LIMIT)
    ap.add_argument("--allow-more", action="store_true",
                    help=f"allow more than {fc.DEFAULT_LIMIT} ORCID iDs")
    ap.add_argument("--pause", type=float, default=0.5)
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    fc.enforce_limit(a.limit, a.allow_more, "ORCID iDs")

    found = derive_ids(a.crossref_dir, a.openalex_dir)
    ordered = sorted(found, key=lambda o: hashlib.sha256(f"{fc.ENRICH_SEED}|orcid|{o}".encode()).hexdigest())
    planned = ordered[: a.limit]
    summary = {
        "derived_at": fc.utc_now(),
        "ids_found_distinct": len(found),
        "ids_found_in_crossref": sum(1 for v in found.values() if v["crossref"]),
        "ids_found_in_openalex": sum(1 for v in found.values() if v["openalex"]),
        "ids_planned_for_retrieval": len(planned),
        "limit": a.limit,
        "ids": {o: found[o] for o in ordered},
    }
    IDS_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not IDS_FILE.exists():  # raw data: never overwritten
        IDS_FILE.write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(f"orcid: found {len(found)} distinct iDs (crossref {summary['ids_found_in_crossref']}, "
          f"openalex {summary['ids_found_in_openalex']}); planned {len(planned)}; {fc.credentials_status()}")
    if a.dry_run:
        return 0
    headers = {"User-Agent": fc.user_agent("orcid"), "Accept": "application/json"}
    counts = fc.run_fetch(source="orcid", identifiers=planned, out_dir=a.out,
                          build_url=lambda o: BASE + fc.safe_path_segment(o) + "/works",
                          headers=headers, pause=a.pause)
    counts["ids_found_distinct"] = len(found)
    print(json.dumps(counts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
