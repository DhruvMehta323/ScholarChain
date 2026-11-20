#!/usr/bin/env python3
"""Generate the small committed enrichment samples from the raw envelopes (stdlib only, offline).

Inputs : datasets/raw/{crossref,openalex,orcid}/ (gitignored raw envelopes) and
         datasets/raw/orcid/orcid_ids.json.
Outputs: datasets/samples/{crossref,openalex,orcid}/ (the three sample files). Coverage and
         consistency figures are written by spot_check.py to datasets/samples/doi_spot_check.json.
Privacy minimisation (see datasets/dataset-manifest.yaml, privacy section):
  * no author names, no abstracts, no affiliations and no ORCID iDs are written;
  * only counts of authors and of ORCID-bearing authors are kept;
  * the ORCID output is aggregate counts only, with no per-person record.
Each sample row carries the envelope's retrieval timestamp and redacted request URL.
The output is deterministic for a given set of raw envelopes (sorted by DOI).
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

import fetch_common as fc

RAW = fc.REPO / "datasets" / "raw"
SAMPLES = fc.REPO / "datasets" / "samples"


def envelopes(directory: Path):
    out = []
    for p in sorted(directory.glob("*.json")):
        env = json.loads(p.read_text(encoding="utf-8"))
        env["_file"] = p.name
        out.append(env)
    return sorted(out, key=lambda e: e["identifier"])


def first(v):
    return v[0] if isinstance(v, list) and v else (v if isinstance(v, str) else None)


def crossref_year(msg):
    for field in ("issued", "published", "published-print", "published-online", "created"):
        parts = (msg.get(field) or {}).get("date-parts") or [[None]]
        if parts and parts[0] and parts[0][0]:
            return parts[0][0], field
    return None, None


def crossref_row(env):
    row = {"doi": env["identifier"], "retrieved_at": env["retrieved_at"],
           "request_url": env["request_url"], "http_status": env["http_status"],
           "missing": env["missing"]}
    if env["missing"] or not env.get("body_is_json"):
        return row
    m = env["body"]["message"]
    authors = m.get("author") or []
    year, year_field = crossref_year(m)
    row.update({
        "title": first(m.get("title")), "year": year, "year_field": year_field,
        "type": m.get("type"), "container_title": first(m.get("container-title")),
        "publisher": m.get("publisher"),
        "author_count": len(authors),
        "authors_with_orcid_count": sum(1 for a in authors if a.get("ORCID")),
        "reference_count": m.get("reference-count"),
        "is_referenced_by_count": m.get("is-referenced-by-count"),
    })
    return row


def openalex_row(env):
    row = {"doi": env["identifier"], "retrieved_at": env["retrieved_at"],
           "request_url": env["request_url"], "http_status": env["http_status"],
           "missing": env["missing"]}
    if env["missing"] or not env.get("body_is_json"):
        return row
    b = env["body"]
    auths = b.get("authorships") or []
    row.update({
        "openalex_id": b.get("id"), "title": b.get("title") or b.get("display_name"),
        "year": b.get("publication_year"), "type": b.get("type"),
        "author_count": len(auths),
        "authors_with_orcid_count": sum(1 for a in auths if (a.get("author") or {}).get("orcid")),
        "authors_with_institution_count": sum(1 for a in auths if a.get("institutions")),
        "referenced_works_count": len(b.get("referenced_works") or []),
        "cited_by_count": b.get("cited_by_count"),
    })
    return row


def write_jsonl(path: Path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")


def orcid_summary():
    ids_file = RAW / "orcid" / "orcid_ids.json"
    derived = json.loads(ids_file.read_text(encoding="utf-8")) if ids_file.exists() else {}
    envs = envelopes(RAW / "orcid" / "works") if (RAW / "orcid" / "works").exists() else []
    errors = RAW / "orcid" / "works" / "errors.jsonl"
    n_err = sum(1 for _ in open(errors, encoding="utf-8")) if errors.exists() else 0
    ok = [e for e in envs if e["http_status"] == 200]
    return {
        "note": "aggregate counts only; no ORCID iD or personal record is committed",
        "ids_found_distinct": derived.get("ids_found_distinct"),
        "ids_found_in_crossref": derived.get("ids_found_in_crossref"),
        "ids_found_in_openalex": derived.get("ids_found_in_openalex"),
        "ids_planned_for_retrieval": derived.get("ids_planned_for_retrieval"),
        "retrieved_200": len(ok),
        "missing_404": sum(1 for e in envs if e["http_status"] == 404),
        "error_log_lines": n_err,
        "retrieved_with_zero_public_works": sum(
            1 for e in ok if e.get("body_is_json") and not (e["body"].get("group") or [])),
        "request_url_pattern": "https://pub.orcid.org/v3.0/<orcid-id>/works",
        "retrieval_timestamps_utc": sorted({e["retrieved_at"] for e in envs})[:1]
                                    + sorted({e["retrieved_at"] for e in envs})[-1:],
    }


def main(argv=None) -> int:
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    cr = [crossref_row(e) for e in envelopes(RAW / "crossref" / "works")]
    oa = [openalex_row(e) for e in envelopes(RAW / "openalex" / "works")]
    write_jsonl(SAMPLES / "crossref" / "crossref_works_sample.jsonl", cr)
    write_jsonl(SAMPLES / "openalex" / "openalex_works_sample.jsonl", oa)
    orc = orcid_summary()
    (SAMPLES / "orcid").mkdir(parents=True, exist_ok=True)
    (SAMPLES / "orcid" / "orcid_coverage_summary.json").write_text(
        json.dumps(orc, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"crossref rows={len(cr)} ok={sum(1 for r in cr if r['http_status']==200)} "
          f"missing={sum(1 for r in cr if r['missing'])}")
    print(f"openalex rows={len(oa)} ok={sum(1 for r in oa if r['http_status']==200)} "
          f"missing={sum(1 for r in oa if r['missing'])}")
    print(json.dumps(orc, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
