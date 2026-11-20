#!/usr/bin/env python3
"""Fetch OpenAlex work records for the reproducible DBLP-derived DOI subset (stdlib only; optional certifi TLS fallback via fetch_common).

API: https://api.openalex.org/works/doi:{doi}  (official REST API).
Authentication: optional OPENALEX_API_KEY (environment only, sent as an Authorization
bearer header, never in the URL). Without a key OpenAlex allows basic keyless queries
(see datasets/dataset-manifest.yaml for the quoted documentation). The contact email goes
only in the User-Agent header. Raw envelopes go to datasets/raw/openalex/works/.
Defaults to 100 DOIs; more requires --allow-more. Existing envelopes are never re-fetched.
"""
import argparse
import json
import os
import sys
from pathlib import Path

import fetch_common as fc

BASE = "https://api.openalex.org/works/doi:"
OUT = fc.REPO / "datasets" / "raw" / "openalex" / "works"


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
    print(f"openalex: planned {len(dois)} DOIs from {a.selection.name}; {fc.credentials_status()}")
    if a.dry_run:
        return 0
    headers = {"User-Agent": fc.user_agent("openalex"), "Accept": "application/json"}
    key = os.environ.get("OPENALEX_API_KEY", "").strip()
    if key:
        headers["Authorization"] = f"Bearer {key}"
    counts = fc.run_fetch(source="openalex", identifiers=dois, out_dir=a.out,
                          build_url=lambda d: BASE + fc.doi_path_segment(d),
                          headers=headers, pause=a.pause)
    print(json.dumps(counts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
