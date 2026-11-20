#!/usr/bin/env python3
"""DOI spot-check: DBLP selection vs retrieved Crossref records (stdlib only, offline).

For DOIs present in both the DBLP selection and the retrieved Crossref envelopes, compares
title, year and author count. Reports every comparison; it never claims success itself.
The three inspected DOIs are the first three Crossref-retrieved DOIs in the reproducible
enrichment order (sha256(seed|enrichment|key)), so the choice is not hand-picked.
It also computes, offline from the stored envelopes, the Crossref year-difference histogram
(DBLP year minus Crossref year) and the OpenAlex-versus-DBLP comparison over all retrieved
OpenAlex records. Output: datasets/samples/doi_spot_check.json (source of every consistency
figure quoted in the manifest and schema notes).
A Crossref record with no year is counted in crossref_matches_without_year and left out of the
year comparison and histogram (its year_equal is null); it never aborts the run.
"""
import argparse
import html
import json
import re
import sys
from pathlib import Path

import fetch_common as fc
from make_samples import RAW, SAMPLES, crossref_year, envelopes, first


def norm_title(t):
    t = html.unescape(re.sub(r"<[^>]+>", "", t or ""))
    return re.sub(r"[^a-z0-9]+", " ", t.casefold()).strip()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--selection", type=Path, default=fc.DEFAULT_SELECTION)
    ap.add_argument("--n", type=int, default=3)
    ap.add_argument("--out", type=Path, default=SAMPLES / "doi_spot_check.json",
                    help="output JSON (default datasets/samples/doi_spot_check.json)")
    a = ap.parse_args(argv)
    sel = {r["doi"]: r for r in fc.read_selection(a.selection)}
    order = [r["doi"] for r in fc.enrichment_subset(a.selection, 1000)]
    cr = {e["identifier"]: e for e in envelopes(RAW / "crossref" / "works")
          if e["http_status"] == 200 and e.get("body_is_json")}
    both = [d for d in order if d in sel and d in cr]
    results = []
    for d in both:
        m = cr[d]["body"]["message"]
        year, year_field = crossref_year(m)
        dblp = sel[d]
        cr_title = first(m.get("title"))
        results.append({
            "doi": d, "dblp_key": dblp["key"],
            "dblp_title": dblp["title"], "crossref_title": cr_title,
            "title_equal_after_normalisation": norm_title(dblp["title"]) == norm_title(cr_title),
            "dblp_year": int(dblp["year"]), "crossref_year": year, "crossref_year_field": year_field,
            "year_equal": None if year is None else int(dblp["year"]) == year,
            "dblp_author_count": int(dblp["n_authors"]),
            "crossref_author_count": len(m.get("author") or []),
            "author_count_equal": int(dblp["n_authors"]) == len(m.get("author") or []),
        })
    inspected = results[: a.n]
    year_hist = {}
    no_year = 0  # Crossref records with no usable year: excluded from year figures, counted here
    for r in results:
        if r["crossref_year"] is None:
            no_year += 1
            continue
        k = str(r["dblp_year"] - r["crossref_year"])
        year_hist[k] = year_hist.get(k, 0) + 1
    oa = {e["identifier"]: e for e in envelopes(RAW / "openalex" / "works")
          if e["http_status"] == 200 and e.get("body_is_json")}
    oa_rows, oa_hist = [], {}
    for d, e in oa.items():
        if d not in sel:
            continue
        b = e["body"]
        yr = b.get("publication_year")
        oa_rows.append({
            "title": norm_title(sel[d]["title"]) == norm_title(b.get("title") or b.get("display_name")),
            "year": yr is not None and int(sel[d]["year"]) == yr,
            "authors": int(sel[d]["n_authors"]) == len(b.get("authorships") or []),
        })
        if yr is not None:
            k = str(int(sel[d]["year"]) - yr)
            oa_hist[k] = oa_hist.get(k, 0) + 1
    openalex_vs_dblp = {
        "compared": len(oa_rows),
        "title_equal_after_normalisation": sum(r["title"] for r in oa_rows),
        "year_equal": sum(r["year"] for r in oa_rows),
        "year_differs": sum(not r["year"] for r in oa_rows),
        "author_count_equal": sum(r["authors"] for r in oa_rows),
        "year_difference_histogram_dblp_minus_openalex": dict(sorted(oa_hist.items(), key=lambda kv: int(kv[0]))),
    }
    agg = {k: sum(1 for r in results if r[k]) for k in
           ("title_equal_after_normalisation", "year_equal", "author_count_equal")}
    out = {
        "method": "first N Crossref-retrieved DOIs in sha256(seed|enrichment|key) order; normalisation "
                  "= strip tags, unescape, casefold, non-alphanumerics to single space",
        "distinct_matches_available": len(results),
        "inspected_count": len(inspected),
        "three_distinct_matches_inspected": len(inspected) >= 3 and len({r["doi"] for r in inspected}) >= 3,
        "inspected": inspected,
        "all_matches_aggregate": {"n": len(results), **agg},
        "crossref_matches_without_year": no_year,
        "crossref_year_difference_histogram_dblp_minus_crossref": dict(sorted(year_hist.items(), key=lambda kv: int(kv[0]))),
        "openalex_vs_dblp": openalex_vs_dblp,
    }
    a.out.write_text(
        json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(out, indent=2, ensure_ascii=False))
    print(f"crossref matches without a year (excluded from year figures): {no_year}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
