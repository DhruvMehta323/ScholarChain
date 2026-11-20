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


def main():
    m = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
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
    for f in failures:
        print("FAIL:", f)
    print(f"checks passed: {passes}; failed: {len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
