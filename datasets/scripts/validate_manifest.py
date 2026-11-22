#!/usr/bin/env python3
"""Validate datasets/dataset-manifest.yaml against the repository.

Declared requirement: PyYAML (used only here). Checks: required fields per dataset, license
URL and status present, SHA-256 syntax, committed sample and exploratory file checksums and
sizes match the files on disk, raw DBLP size and SHA-256 match when the file is present,
coverage arithmetic (planned = retrieved + missing + errors), S3 prefixes are placeholders,
referenced schema notes exist, and the consistency figures in the manifest equal those in
datasets/samples/doi_spot_check.json.
DOI check: every row of modules/core/src/test/resources/doi-normalization-vectors.tsv (read-only,
shared with Scala) must give the expected result under doi_canonical.normalize_doi, accepted rows
must be idempotent, and at least 80 rows must exist.
Version 1.2.0 (Phase 15a repair 01): a missing or malformed manifest field is a FAIL line and exit 1, never a\ntraceback; limitations must state the policy-1 index dependency; the stats file must hold no per-iD hash.
Scale entries (Phase 15a): the top-level key scale_datasets must hold dblp-10k and
dblp-50k with every required field, SHA-256 syntax on every file and digest, the stats file checksum, and
counts that equal the committed stats file (datasets/samples/dblp/scale_enrichment_stats.json) and the
selection stats file; coverage arithmetic (requested = 200 + 404 + errors), nesting stated, record counts
equal article + inproceedings, the limitations must state that bounded enrichment and the ORCID cap are not an
approved deviation, no ORCID iD may appear in the manifest, and gitignored raw files are checked against
their recorded checksum and size when present.
Exit status 0 only when every check passes.
"""
import hashlib
import re
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import doi_canonical  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "datasets" / "dataset-manifest.yaml"
SHA = re.compile(r"^[0-9a-f]{64}$")
REQUIRED = ["source", "license", "snapshot", "format", "size_estimate", "s3_prefixes", "subset_rule",
            "linkage_keys", "update_method", "limitations", "privacy_notes", "validation_commands"]
VALIDATOR_VERSION = "1.2.0"
ORCID_ID = re.compile(r"\b\d{4}-\d{4}-\d{4}-\d{3}[\dX]\b")
SCALE_REQUIRED = ["purpose", "nesting", "selection_rule", "seed", "record_counts", "files", "enrichment_cache_digests",
                  "enrichment_coverage", "retrieval_dates_utc", "staleness", "limitations", "privacy_notes",
                  "validation_commands"]
failures, passes = [], 0


def check(cond, msg):
    global passes
    if cond:
        passes += 1
    else:
        failures.append(msg)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def file_entry(path_str, sha, size=None, optional_if_missing=False):
    p = REPO / path_str
    if not p.exists():
        check(optional_if_missing, f"missing file {path_str}")
        return
    check(sha256(p) == sha, f"sha256 mismatch {path_str}")
    if size is not None:
        check(p.stat().st_size == size, f"size mismatch {path_str}")


VECTORS = REPO / "modules" / "core" / "src" / "test" / "resources" / "doi-normalization-vectors.tsv"
VECTOR_MIN_ROWS = 80


class VectorReaderError(Exception):
    pass


def _is_ws(cp):
    return (0x9 <= cp <= 0xD or 0x1C <= cp <= 0x20 or cp in (0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
            or 0x2000 <= cp <= 0x200A)


def _is_ctrl(cp):
    return cp <= 0x1F or 0x7F <= cp <= 0x9F


def _hex(cell, frm, n):
    digits = cell[frm:frm + n]
    if len(digits) < n:
        raise VectorReaderError(f"truncated escape in {cell!r}")
    if any(c not in "0123456789abcdefABCDEF" for c in digits):
        raise VectorReaderError(f"bad hex digit in {cell!r}")
    return int(digits, 16)


def vector_unescape(cell):
    """Mirror of DoiVectorsContractSuite.unescape (Scala). Raises VectorReaderError."""
    for ch in cell:
        cp = ord(ch)
        if _is_ws(cp) or _is_ctrl(cp) or cp == 0x200B:
            raise VectorReaderError(f"raw character U+{cp:04X} must be escaped in {cell!r}")
    if cell == "\\e":
        return ""
    out, i, prev_high = [], 0, False
    while i < len(cell):
        c = cell[i]
        if c != "\\":
            out.append(c)
            prev_high = False
            i += 1
            continue
        if i + 1 >= len(cell):
            raise VectorReaderError(f"dangling backslash in {cell!r}")
        k = cell[i + 1]
        if k == "\\":
            out.append("\\")
            prev_high = False
            i += 2
        elif k == "u":
            unit = _hex(cell, i + 2, 4)
            if prev_high and 0xDC00 <= unit <= 0xDFFF:
                raise VectorReaderError(f"adjacent high and low surrogate \\u escapes in {cell!r}; use \\U")
            out.append(chr(unit))
            prev_high = 0xD800 <= unit <= 0xDBFF
            i += 6
        elif k == "U":
            cp = _hex(cell, i + 2, 8)
            if cp > 0x10FFFF or 0xD800 <= cp <= 0xDFFF:
                raise VectorReaderError(f"invalid code point {cp:X} in {cell!r}")
            out.append(chr(cp))
            prev_high = False
            i += 10
        else:
            raise VectorReaderError(f"unknown escape \\{k} in {cell!r}")
    return "".join(out)


def load_vectors(path):
    """Read the shared DOI vector file read-only; returns [(line, raw_in, raw_exp, input, expected|None, note)]."""
    if not path.is_file():
        raise VectorReaderError(f"missing vector file {path}")
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        raise VectorReaderError("vector file has a UTF-8 BOM")
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError as e:
        raise VectorReaderError(f"vector file is not valid UTF-8: {e}")
    if "\r" in text:
        raise VectorReaderError("vector file contains CR")
    header, rows = False, []
    for n, line in enumerate(text.split("\n"), 1):  # LF only; never splitlines()
        if line == "" or line.startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) != 3:
            raise VectorReaderError(f"line {n}: expected 3 tab-separated cells, got {len(cells)}")
        if not header:
            if cells != ["input", "expected", "note"]:
                raise VectorReaderError(f"line {n}: bad header")
            header = True
            continue
        try:
            inp = vector_unescape(cells[0])
            exp = None if cells[1] == "REJECT" else vector_unescape(cells[1])
        except VectorReaderError as e:
            raise VectorReaderError(f"line {n}: {e}")
        if not cells[2]:
            raise VectorReaderError(f"line {n}: empty note")
        rows.append((n, cells[0], cells[1], inp, exp, cells[2]))
    if not header:
        raise VectorReaderError("vector file has no header")
    return rows


def check_doi_vectors():
    try:
        rows = load_vectors(VECTORS)
    except VectorReaderError as e:
        check(False, f"doi vectors reader error: {e}")
        return
    check(len(rows) >= VECTOR_MIN_ROWS, f"doi vectors: only {len(rows)} rows (< {VECTOR_MIN_ROWS})")
    bad = 0
    for n, raw_in, raw_exp, inp, exp, note in rows:
        got = doi_canonical.normalize_doi(inp)
        ok = got == exp
        if ok and exp is not None:
            ok = doi_canonical.normalize_doi(exp) == exp
        if not ok:
            bad += 1
        check(ok, f"doi vector line {n} ({note}): input={raw_in!r} expected={raw_exp!r} got={got!r}")
    if not bad and len(rows) >= VECTOR_MIN_ROWS:
        print(f"doi vectors: {len(rows)} rows pass (sha256 {sha256(VECTORS)})")


def _d(x):
    return x if isinstance(x, dict) else {}


def _load_json(path_str, label):
    """Read a committed JSON file; a missing path, file or bad JSON is a FAIL, never an exception."""
    import json
    if not isinstance(path_str, str) or not path_str:
        check(False, f"{label}: path missing")
        return None
    try:
        return json.loads((REPO / path_str).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        check(False, f"{label}: cannot read {path_str} ({type(e).__name__})")
        return None


def _no_per_id_hash(obj, path=""):
    """True when no key anywhere in the stats JSON names a per-iD hash list (repair 01, F2)."""
    if isinstance(obj, dict):
        return all("sha256_of_id" not in k and _no_per_id_hash(v, path + "/" + k) for k, v in obj.items())
    if isinstance(obj, list):
        return all(_no_per_id_hash(v, path) for v in obj)
    return True


def check_scale_datasets(m):
    sd = m.get("scale_datasets")
    check(isinstance(sd, dict), "top-level key missing: scale_datasets")
    if not isinstance(sd, dict):
        return
    check(all(k in sd for k in ("dblp-10k", "dblp-50k", "stats_file")), "scale_datasets must hold dblp-10k, dblp-50k, stats_file")
    sf = _d(sd.get("stats_file"))
    check(bool(SHA.match(str(sf.get("sha256", "")))), "scale_datasets.stats_file.sha256 syntax")
    check(bool(SHA.match(str(sf.get("selection_stats_sha256", "")))), "scale_datasets.stats_file.selection_stats_sha256 syntax")
    stats, sel = None, None
    if sf.get("path") and sf.get("selection_stats_path"):
        file_entry(sf["path"], sf.get("sha256", ""))
        file_entry(sf["selection_stats_path"], sf.get("selection_stats_sha256", ""))
        doc = _load_json(sf["path"], "scale_datasets.stats_file")
        sel = _load_json(sf["selection_stats_path"], "scale_datasets.stats_file.selection_stats")
        stats = _d(doc).get("scales") if doc is not None else None
        if doc is not None:
            check(isinstance(stats, dict), "scale_datasets.stats_file: scales missing")
            if not isinstance(stats, dict):
                stats = None
            check(_no_per_id_hash(doc), "stats file must not hold per-iD hashes (unsalted sha256 of an ORCID iD is reversible)")
            check(ORCID_ID.search(json_dumps(doc)) is None, "stats file contains an ORCID iD-shaped string")
    else:
        check(False, "scale_datasets.stats_file: path and selection_stats_path required")
    text = MANIFEST.read_text(encoding="utf-8")
    check(ORCID_ID.search(text) is None, "manifest contains an ORCID iD-shaped string (personal identifiers are not committed)")
    for name in ("dblp-10k", "dblp-50k"):
        d = sd.get(name)
        check(isinstance(d, dict), f"{name}: missing")
        if not isinstance(d, dict):
            continue
        for f in SCALE_REQUIRED:
            check(f in d, f"{name}: missing field {f}")
        lim = " ".join(str(x) for x in (d.get("limitations") or [])).lower()
        check("not an instructor-approved deviation" in lim, f"{name}: limitations must say bounded enrichment is not an approved deviation")
        check("orcid cap" in lim and "5,000" in lim, f"{name}: limitations must state the ORCID cap of 5,000")
        check("policy-1 eligible index" in lim and "invalid under policy 2" in lim and "reindex has not happened" in lim,
              f"{name}: limitations must state the dependence on the policy-1 eligible index (invalid under policy 2, reindex has not happened)")
        check(bool(d.get("privacy_notes")) and bool(d.get("validation_commands")), f"{name}: privacy notes and validation commands required")
        files = [fe for fe in (d.get("files") or []) if isinstance(fe, dict)]
        check(len(files) >= 1 and len(files) == len(d.get("files") or []), f"{name}: files must be a non-empty list of mappings")
        for fe in files:
            fp = fe.get("path")
            check(isinstance(fp, str) and bool(fp), f"{name}: file entry without path")
            check(bool(SHA.match(str(fe.get("sha256", "")))), f"{name}: bad sha256 syntax {fp}")
            check(isinstance(fe.get("bytes"), int), f"{name}: bytes missing {fp}")
            if isinstance(fp, str) and SHA.match(str(fe.get("sha256", ""))):
                file_entry(fp, fe["sha256"], fe.get("bytes"), optional_if_missing=fp.startswith("datasets/raw/"))
        digests = _d(d.get("enrichment_cache_digests"))
        for src, dg in digests.items():
            if src != "digest_definition":
                check(bool(SHA.match(str(dg))), f"{name}: cache digest {src} syntax")
        cov = _d(d.get("enrichment_coverage"))
        for src in ("crossref", "openalex"):
            c = _d(cov.get(src))
            check(c.get("retrieved_200", -1) + c.get("missing_404", -1) + c.get("errors", -1) == c.get("requested"),
                  f"{name}: {src} coverage does not add up")
        rc = _d(d.get("record_counts"))
        check(rc.get("article", 0) + rc.get("inproceedings", 0) == rc.get("works"), f"{name}: article + inproceedings != works")
        check(cov.get("works_with_doi") == rc.get("works") == rc.get("distinct_dois"), f"{name}: DOI counts differ from works")
        o = _d(cov.get("orcid"))
        check(o.get("retrieved_200", -1) + o.get("errors", -1) == o.get("ids_with_envelope"), f"{name}: orcid envelope counts do not add up")
        if stats is not None and sel is not None:
            s = _d(stats.get(name))
            check(bool(s), f"{name}: missing from stats file")
            key = name.split("-")[1]
            mix = _d(_d(sel.get("mix")).get(key))
            check(s.get("works") == rc.get("works"), f"{name}: works differs from stats file")
            check(mix.get("by_type") == {"article": rc.get("article"), "inproceedings": rc.get("inproceedings")}, f"{name}: type mix differs from selection stats")
            check(mix.get("distinct_venues") == rc.get("distinct_venues"), f"{name}: venues differ from selection stats")
            for src in ("crossref", "openalex"):
                ss, cc = _d(s.get(src)), _d(cov.get(src))
                check(ss.get("hit_200") == cc.get("retrieved_200") and ss.get("missing_404") == cc.get("missing_404")
                      and ss.get("requested") == cc.get("requested"), f"{name}: {src} coverage differs from stats file")
                check(ss.get("cache_digest_sha256") == digests.get(src), f"{name}: {src} cache digest differs from stats file")
            so = _d(s.get("orcid"))
            check(so.get("ids_with_envelope") == o.get("ids_with_envelope") and so.get("distinct_ids_in_records") == o.get("distinct_ids_in_records"),
                  f"{name}: orcid counts differ from stats file")
            check(so.get("cache_digest_sha256") == digests.get("orcid"), f"{name}: orcid cache digest differs from stats file")
            check(_d(s.get("cross_source")).get("neither") == cov.get("neither"), f"{name}: neither count differs from stats file")
            check(bool(files) and s.get("selection_tsv_sha256") == files[0].get("sha256"), f"{name}: selection sha256 differs from stats file")
    if sel is not None:
        check(_d(sel.get("nesting")).get("10k_subset_of_50k") is True, "selection stats must record 10k subset of 50k")
        n10 = str(_d(sd.get("dblp-10k")).get("nesting", ""))
        n50 = str(_d(sd.get("dblp-50k")).get("nesting", ""))
        check("subset of dblp-50k" in n10 and "dblp-10k is a subset" in n50, "nesting statements missing")
    # the committed selection stats must stay repo-relative (repair 01, F1)
    check("/Users/" not in json_dumps(sel), "selection stats file holds an absolute local path")


def json_dumps(o):
    import json
    return json.dumps(o)


def _run_checks(m):
    for k in ("manifest_version", "instructor_approval", "datasets", "privacy", "s3_prefix_convention",
              "exploratory_non_graded_files", "spot_check", "validation"):
        check(k in m, f"top-level key missing: {k}")
    check(set(m["datasets"]) == {"dblp", "crossref", "openalex", "orcid"}, "datasets must be exactly dblp, crossref, openalex, orcid")
    check("bounded" in m["instructor_approval"]["statement"].lower(), "instructor approval statement must mention bounded subsets")
    for name, d in m["datasets"].items():
        for f in REQUIRED:
            check(f in d, f"{name}: missing field {f}")
        lic = d.get("license", {})
        check(bool(lic.get("url")) and bool(lic.get("status")), f"{name}: license needs url and status")
        check(str(lic.get("url", "")).startswith("https://"), f"{name}: license url must be https")
        for k, v in d.get("s3_prefixes", {}).items():
            check(v.startswith("s3://${RAW_BUCKET}/"), f"{name}: s3 prefix {k} must use ${{RAW_BUCKET}} placeholder")
        sn = d.get("schema_notes")
        if sn:
            check((REPO / sn).exists(), f"{name}: schema note missing {sn}")
        smp = d.get("sample")
        if smp:
            file_entry(smp["path"], smp["sha256"], smp.get("bytes"))
        cov = d.get("coverage")
        if cov and name != "dblp":
            parts = cov["retrieved_200"] + cov["missing_404"] + cov["errors"]
            planned = cov.get("ids_planned_for_retrieval", cov.get("planned"))
            check(parts == planned, f"{name}: coverage does not add up ({parts} != {planned})")
    db = m["datasets"]["dblp"]
    check(re.match(r"^\d{4}-\d{2}-\d{2}$", db["snapshot"]["date"]) is not None, "dblp snapshot date format")
    check(db["snapshot"]["date"] == db["snapshot"]["observed_newest_mdate"], "dblp snapshot date must equal observed newest mdate")
    for f in db["files"]:
        check(bool(SHA.match(f["sha256"])), f"bad sha256 syntax {f['path']}")
        p = REPO / f["path"]
        if p.exists():
            check(p.stat().st_size == f["bytes"], f"size mismatch {f['path']}")
    # the 5.4 GB hash is recomputed only on request
    if "--full" in sys.argv:
        file_entry(db["files"][0]["path"], db["files"][0]["sha256"], db["files"][0]["bytes"])
    file_entry(db["files"][1]["path"], db["files"][1]["sha256"], db["files"][1]["bytes"], optional_if_missing=True)
    for s in db["selections"]:
        if s["committed"]:
            file_entry(s["path"], s["sha256"], s.get("bytes"))
            rows = (REPO / s["path"]).read_text(encoding="utf-8").splitlines()[1:]
            check(len(rows) == s["size"], f"selection row count {len(rows)} != {s['size']}")
            check(len({r.split('\t')[0] for r in rows}) == s["size"], "selection keys not unique")
    for d_art in db.get("derived_artifacts", []):
        check(bool(SHA.match(d_art["sha256"])), f"bad sha256 syntax {d_art['path']}")
        file_entry(d_art["path"], d_art["sha256"], d_art["bytes"], optional_if_missing=True)
        for src in d_art["derived_from"]:
            check(bool(SHA.match(src["sha256"])), f"bad derived_from sha256 {src['path']}")
    for e in m["exploratory_non_graded_files"]["files"]:
        file_entry(e["path"], e["sha256"], e["bytes"], optional_if_missing=e["path"].startswith("datasets/raw/"))
    sc = m["spot_check"]
    file_entry(sc["file"], sc["sha256"])
    import json
    sj = json.loads((REPO / sc["file"]).read_text(encoding="utf-8"))
    oa = m["datasets"]["openalex"]["coverage"]["consistency_vs_dblp"]
    cr = m["datasets"]["crossref"]["coverage"]["consistency_vs_dblp"]
    check(oa["year_equal"] == sj["openalex_vs_dblp"]["year_equal"] and oa["year_differs"] == sj["openalex_vs_dblp"]["year_differs"]
          and oa["author_count_equal"] == sj["openalex_vs_dblp"]["author_count_equal"], "openalex consistency figures differ from doi_spot_check.json")
    check(oa["year_difference_dblp_minus_openalex"] == sj["openalex_vs_dblp"]["year_difference_histogram_dblp_minus_openalex"], "openalex year histogram differs")
    check(cr["year_difference_dblp_minus_crossref"] == sj["crossref_year_difference_histogram_dblp_minus_crossref"], "crossref year histogram differs")
    check(cr["year_equal"] == sj["all_matches_aggregate"]["year_equal"], "crossref year_equal differs")
    check(sc["inspected"] >= 3, "spot check must inspect three DOIs")
    check(sj.get("crossref_matches_without_year") == sc.get("crossref_matches_without_year"), "crossref_matches_without_year differs from doi_spot_check.json")
    check(bool(m["privacy"]["rules"]), "privacy rules empty")
    check_doi_vectors()


def main():
    try:
        m = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as e:
        check(False, f"manifest unreadable or not valid YAML: {type(e).__name__}")
        m = None
    if not isinstance(m, dict):
        check(False, "manifest is not a mapping")
        m = {}
    # Backstop: any structural surprise the explicit checks missed is a FAIL line, never a traceback.
    for label, fn in (("datasets and general checks", _run_checks), ("scale_datasets", check_scale_datasets)):
        try:
            fn(m)
        except (KeyError, TypeError, IndexError, AttributeError, ValueError, OSError) as e:
            check(False, f"manifest structure error in {label}: {type(e).__name__}: {e}")
    for f in failures:
        print("FAIL:", f)
    print(f"validate_manifest {VALIDATOR_VERSION}")
    print(f"checks passed: {passes}; failed: {len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
