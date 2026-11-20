#!/usr/bin/env python3
"""Fetch Crossref works records for the reproducible DBLP-derived DOI subset (stdlib only; optional certifi TLS fallback via fetch_common).

API: https://api.crossref.org/works/{doi}  (official public REST API, no signup needed).
Access pool: "polite" when SCHOLARCHAIN_CONTACT_EMAIL is set (sent in the User-Agent
header only, never in the URL). Raw envelopes go to datasets/raw/crossref/works/.
Defaults to 100 DOIs; more requires --allow-more. Existing envelopes are never re-fetched.
"""
import argparse
import json
import sys
from pathlib import Path

import fetch_common as fc

BASE = "https://api.crossref.org/works/"
OUT = fc.REPO / "datasets" / "raw" / "crossref" / "works"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--selection", type=Path, default=fc.DEFAULT_SELECTION)
    ap.add_argument("--limit", type=int, default=fc.DEFAULT_LIMIT)
    ap.add_argument("--allow-more", action="store_true",
                    help=f"allow more than {fc.DEFAULT_LIMIT} DOIs")
    ap.add_argument("--pause", type=float, default=0.5, help="seconds between requests")
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--dry-run", action="store_true", help="print the plan, fetch nothing")
    a = ap.parse_args(argv)
    fc.enforce_limit(a.limit, a.allow_more, "DOIs")
    subset = fc.enrichment_subset(a.selection, a.limit)
    dois = [d for d in (fc.normalize_doi(r["doi"]) for r in subset) if d]
    print(f"crossref: planned {len(dois)} DOIs from {a.selection.name}; {fc.credentials_status()}")
    if a.dry_run:
        return 0
    headers = {"User-Agent": fc.user_agent("crossref"), "Accept": "application/json"}
    counts = fc.run_fetch(source="crossref", identifiers=dois, out_dir=a.out,
                          build_url=lambda d: BASE + fc.doi_path_segment(d),
                          headers=headers, pause=a.pause)
    print(json.dumps(counts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
