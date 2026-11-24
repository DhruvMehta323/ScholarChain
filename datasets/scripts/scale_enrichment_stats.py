#!/usr/bin/env python3
"""Compute enrichment coverage for the dblp-10k and dblp-50k scales from the cached envelopes.

SCRIPT_VERSION 1.0.0 (Phase 15a, work order B). Read-only and offline: it opens the selection
TSVs and the raw envelope caches under datasets/raw/ and writes one commit-safe JSON stats file
(counts, checksums, coverage). It makes no network request, never writes under datasets/raw/, and
the output holds no personal names, no ORCID iD and no DOI list (only counts, prefixes and aggregate cache digests).
Version 1.1.0 (repair 01) drops the per-iD sha256 list: unsalted hashes of ORCID iDs are dictionary-reversible,
so the missing-envelope iDs are reported as a count only.

Usage:  python3 -I -B datasets/scripts/scale_enrichment_stats.py [--out PATH]
Default output: datasets/samples/dblp/scale_enrichment_stats.json
Inputs recorded in the output: sha256 of each selection TSV, per-source cache digest (sha256 over
sorted "<envelope name> <envelope sha256>" lines of the scale's envelopes), seed and script versions.
"""
import argparse
import collections
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I does not add the script dir
import fetch_common as fc  # noqa: E402
import fetch_orcid as fo  # noqa: E402

SCRIPT_VERSION = "1.1.0"
REPO = fc.REPO
RAW = REPO / "datasets" / "raw"
DER = RAW / "dblp" / "derived"
ORCID_CAP = 5000
ENVELOPE_FIELDS = ["identifier", "request_url", "retrieved_at", "http_status", "missing",
                   "body_is_json", "body", "fetcher_code_version"]


def sha256_file(p):
    return fc.sha256_file(p)


def read_tsv(path):
    # plain split on tab: csv quoting would merge rows whose title starts with a quote character
    lines = path.read_text(encoding="utf-8").split("\n")
    head = lines[0].split("\t")
    return [dict(zip(head, ln.split("\t"))) for ln in lines[1:] if ln]


def env_of(src, ident):
    p = fc.envelope_path(RAW / src / "works", ident)
    return (p, json.loads(p.read_text(encoding="utf-8"))) if p.exists() else (None, None)


def prefix(d):
    return d.split("/")[0]


def sorted_counts(c):
    return dict(sorted(c.items(), key=lambda kv: (-kv[1], kv[0])))


def digest(pairs):
    h = hashlib.sha256()
    for name, sha in sorted(pairs):
        h.update(f"{name} {sha}\n".encode())
    return h.hexdigest()


def envelope_ok(e, ident, src):
    return (all(f in e for f in ENVELOPE_FIELDS) and e["identifier"] == ident
            and e["http_status"] in (200, 404) and e["missing"] == (e["http_status"] == 404)
            and isinstance(e["retrieved_at"], str)
            and (e["body_is_json"] is True or e["http_status"] == 404))  # Crossref 404 body is plain text


def analyze(n, orcid_plan):
    tsv = DER / f"dblp_selection_{n}.tsv"
    rows = read_tsv(tsv)
    dois, seen, invalid = [], set(), 0
    for r in rows:
        d = fc.normalize_doi(r["doi"]) if r["doi"] else None
        if not d:
            invalid += 1
        elif d not in seen:
            seen.add(d)
            dois.append(d)
    out = {"selection_tsv_sha256": sha256_file(tsv), "works": len(rows),
           "works_with_valid_doi": len(rows) - invalid, "distinct_dois": len(dois)}
    res, ok_sets = {}, {}
    for src in ("crossref", "openalex"):
        st, via, schema = collections.Counter(), collections.Counter(), collections.Counter()
        pairs, times = [], []
        miss, okset, alias, nbytes = [], set(), 0, 0
        for d in dois:
            p, e = env_of(src, d)
            if e is None:
                st["no_envelope"] += 1
                continue
            pairs.append((p.name, sha256_file(p)))
            nbytes += p.stat().st_size
            times.append(e["retrieved_at"])
            st[f"http_{e['http_status']}"] += 1
            via[e.get("fetched_via", "absent_field(phase03_or_singleton)")] += 1
            schema["envelope_valid" if envelope_ok(e, d, src) else "envelope_invalid"] += 1
            if e["http_status"] == 404:
                miss.append(d)
                continue
            b = e["body"]
            if src == "crossref":
                m = b.get("message") if isinstance(b, dict) else None
                good = isinstance(m, dict) and isinstance(m.get("DOI"), str)
                schema["record_valid" if good else "record_invalid"] += 1
                bd = fc.normalize_doi(m["DOI"]) if good else None
                if good and "author" in m:
                    schema["has_author_list"] += 1
            else:
                good = isinstance(b, dict) and isinstance(b.get("id"), str) and b["id"].startswith("https://openalex.org/W")
                schema["record_valid" if good else "record_invalid"] += 1
                bd = fc.normalize_doi(b.get("doi") or "") if good else None
                if good and "authorships" in b:
                    schema["has_authorships"] += 1
            if good:
                okset.add(d)
                if bd is None:
                    schema["body_doi_missing"] += 1
                elif bd != d:
                    alias += 1
        ok_sets[src] = (okset, set(miss))
        res[src] = {"requested": len(dois), "envelopes_present": len(pairs), "http_status": dict(sorted(st.items())),
                    "fetched_via": dict(sorted(via.items())), "schema": dict(sorted(schema.items())),
                    "hit_200": len(okset), "hit_rate": round(len(okset) / len(dois), 6),
                    "missing_404": len(miss), "body_doi_differs_from_requested_alias": alias,
                    "retrieved_at_min_utc": min(times), "retrieved_at_max_utc": max(times),
                    "envelope_bytes": nbytes, "cache_digest_sha256": digest(pairs)}
        res[src]["_miss"] = miss
    cr_ok, cr_miss = ok_sets["crossref"]
    oa_ok, oa_miss = ok_sets["openalex"]
    miss = res["crossref"].pop("_miss")
    res["openalex"].pop("_miss")
    out["crossref"], out["openalex"] = res["crossref"], res["openalex"]
    out["crossref_404_by_prefix"] = sorted_counts(collections.Counter(prefix(d) for d in miss))
    out["crossref_404_openalex_coverage"] = {
        "crossref_404": len(miss), "openalex_200": sum(1 for d in miss if d in oa_ok),
        "openalex_404": sum(1 for d in miss if d in oa_miss),
        "openalex_200_by_prefix": sorted_counts(collections.Counter(prefix(d) for d in miss if d in oa_ok)),
        "openalex_404_by_prefix": sorted_counts(collections.Counter(prefix(d) for d in miss if d in oa_miss))}
    out["cross_source"] = {"both_200": len(cr_ok & oa_ok), "crossref_only": len(cr_ok - oa_ok),
                           "openalex_only": len(oa_ok - cr_ok), "neither": len(set(dois) - cr_ok - oa_ok)}
    # ORCID
    found = fo.derive_ids(RAW / "crossref" / "works", RAW / "openalex" / "works", dois)
    ids = set(found)
    plan = set(orcid_plan)
    st, pairs, bad_schema, redirected, times = collections.Counter(), [], 0, 0, []
    planned_here = ids & plan
    have = set()
    for i in ids:
        p, e = env_of("orcid", i)
        if e is None:
            continue
        have.add(i)
        pairs.append((p.name, sha256_file(p)))
        times.append(e["retrieved_at"])
        st[f"http_{e['http_status']}"] += 1
        b = e["body"]
        if not (envelope_ok(e, i, "orcid") and isinstance(b, dict) and isinstance(b.get("group"), list)
                and isinstance(b.get("path"), str)):
            bad_schema += 1
        elif b["path"].strip("/").split("/")[0] != i:
            redirected += 1
    planned_no_env = sorted(planned_here - have)
    out["orcid"] = {
        "distinct_ids_in_records": len(ids),
        "from_crossref_records": sum(1 for v in found.values() if v["crossref"]),
        "from_openalex_records": sum(1 for v in found.values() if v["openalex"]),
        "cap_plan_ids_total": len(plan), "cap_plan_ids_within_this_scale": len(planned_here),
        "ids_with_envelope": len(have), "ids_with_envelope_in_cap_plan": len(planned_here & have),
        "ids_with_envelope_outside_cap_plan": len(have - plan),
        "coverage_of_distinct_ids": round(len(have) / len(ids), 6),
        "http_status": dict(sorted(st.items())),
        "cap_plan_ids_without_envelope": len(planned_no_env),
        "schema_invalid": bad_schema, "body_path_differs_from_requested_id": redirected,
        "retrieved_at_min_utc": min(times), "retrieved_at_max_utc": max(times),
        "cache_digest_sha256": digest(pairs)}
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=REPO / "datasets" / "samples" / "dblp" / "scale_enrichment_stats.json")
    a = ap.parse_args(argv)
    plan_file = RAW / "orcid" / "orcid_ids_50k.json"
    plan_doc = json.loads(plan_file.read_text(encoding="utf-8"))
    plan = fo.order_ids(plan_doc["ids"])[:ORCID_CAP]
    res = {"script": "datasets/scripts/scale_enrichment_stats.py", "script_version": SCRIPT_VERSION,
           "code_version": fc.code_version(), "network": "none (offline, reads cached envelopes only)",
           "seed": fc.ENRICH_SEED, "orcid_cap": ORCID_CAP,
           "orcid_plan_file_sha256": sha256_file(plan_file),
           "orcid_ids_found_distinct_50k": plan_doc["ids_found_distinct"],
           "orcid_order_rule": "sha256('<seed>|orcid|<id>') ascending, first 5000",
           "scales": {"dblp-10k": analyze(10000, plan), "dblp-50k": analyze(50000, plan)}}
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(res, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {a.out} sha256 {sha256_file(a.out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
