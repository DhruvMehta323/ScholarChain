#!/usr/bin/env python3
"""Seeded, stratified, reproducible DBLP work selection (stdlib + lxml only).

Two stages, both idempotent:

  index   One streaming lxml pass over datasets/raw/dblp/dblp.xml. Writes a cache of
          eligible article/inproceedings records, a JSON summary of observed counts and a
          cache metadata file under datasets/raw/dblp/derived/ (gitignored). The XML and DTD
          are fully hashed first. The cache is reused only when its metadata, fingerprint,
          file checksums and inputs all verify; otherwise the index is rebuilt (or, if
          --xml-sha256 disagrees with the actual file, the run fails and nothing is written).
  select  Verifies the cache the same way (full XML and DTD hash by default;
          --fast-xml-identity trades the XML hash for a size+mtime check), then reads the cache
          (never the XML contents) and writes a deterministic selection of N works
          (N in {1000, 10000, 50000, 100000}) to a TSV, plus a JSON provenance file next to it.
          An invalid cache is a clear failure and no output is written.
  adopt   One-off: writes cache metadata for a pre-existing cache built before metadata
          existed, after fully hashing the XML and DTD and proving that they, and the cache,
          match what the legacy summary recorded. Never rebuilds or modifies the cache.
          Refuses (exit 2, nothing written) when the legacy cache was built under an
          eligibility policy other than the current one (the 1.0.0 cache is policy "1").

Cache fingerprint (eligible_index.meta.json): sha256 of canonical JSON of
{index_format_version, eligibility_policy_version, xml_sha256, dtd_sha256}. The sampling
seed and selection size are NOT part of it (they live in selection provenance only).
Bump ELIGIBILITY_POLICY_VERSION whenever the eligibility rules or the DOI normalisation
used by the index change, and INDEX_FORMAT_VERSION whenever the cache layout changes.

DOI rule: the canonical rule in datasets/scripts/doi_canonical.py (policy version 2).

Eligibility (all required):
  tag is article or inproceedings; key is well formed and does not start with "dblpnote/";
  title is non-empty after joining inline markup; year is exactly four digits;
  at least two <author> elements; at least one <ee> that is a doi.org URL;
  the key occurs exactly once among all article/inproceedings records.

Selection (see datasets/dataset-manifest.yaml for the prose version):
  strata   = publication year, then venue within year (venue = first two key segments,
             for example journals/tods or conf/icse).
  years    = every year with eligible records gets 1 work when N >= number of years, the
             remainder is allocated proportionally to the eligible count (largest
             remainder, capped by availability).
  venues   = inside a year, the year quota is allocated across venues proportionally to
             sqrt(eligible count), largest remainder, capped by availability; remainder
             ties are broken by sha256(seed|year|venue).
  records  = inside a (year, venue) cell the k works with the smallest
             sha256(seed|key) are taken. The result does not depend on input order.
Never "the first N eligible records".
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
from array import array
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I does not add the script dir
from doi_canonical import normalize_doi_url_form  # noqa: E402  canonical DOI rule

SCRIPT_VERSION = "1.2.0"
INDEX_FORMAT_VERSION = 1         # layout of eligible_index.tsv.gz (TSV_FIELDS, gzip, LF)
ELIGIBILITY_POLICY_VERSION = "2"  # eligibility rules plus DOI normalisation used by the index
# policy history: "1" = loose unquote-based DOI normalisation; "2" = canonical DOI rule (doi_canonical.py)
LEGACY_POLICY_VERSION = "1"        # policy of caches written before metadata existed (script 1.0.0)
META_FORMAT_VERSION = 1          # layout of eligible_index.meta.json
REPO = Path(__file__).resolve().parents[2]
RAW_DIR = REPO / "datasets" / "raw" / "dblp"
XML = RAW_DIR / "dblp.xml"
DTD = RAW_DIR / "dblp.dtd"
DERIVED = RAW_DIR / "derived"
CACHE_NAME = "eligible_index.tsv.gz"
SUMMARY_NAME = "eligible_index.summary.json"
META_NAME = "eligible_index.meta.json"
DEFAULT_SEED = 441
ALLOWED_SIZES = (1000, 10000, 50000, 100000)

RECORD_TAGS = ("article", "inproceedings", "proceedings", "book", "incollection",
               "phdthesis", "mastersthesis", "www", "data")
KEY_RE = re.compile(r"^[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-.:+&'()~]+)+$")
YEAR_RE = re.compile(r"^[0-9]{4}$")
TSV_FIELDS = ["key", "type", "year", "venue", "doi", "n_authors", "title"]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def code_version() -> str:
    try:
        out = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True, timeout=10)
        head = out.stdout.strip() or "unknown"
        dirty = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain", "--",
                                "datasets/scripts"], capture_output=True, text=True, timeout=10)
        return head + ("+uncommitted-scripts" if dirty.stdout.strip() else "")
    except Exception:
        return "unknown"


def normalize_doi(raw: str) -> str | None:
    """Canonical DOI of a doi.org URL (rule in doi_canonical.py); None if rejected or not a URL."""
    return normalize_doi_url_form(raw)


def rel(p: Path) -> str:
    try:
        return str(p.resolve().relative_to(REPO))
    except ValueError:
        return str(p)


def hash64(s: str) -> int:
    return int.from_bytes(hashlib.blake2b(s.encode("utf-8"), digest_size=8).digest(), "big")


def venue_of(key: str) -> str:
    return "/".join(key.split("/")[:2])


def clean(s: str) -> str:
    return " ".join(s.replace("\t", " ").replace("\n", " ").replace("\r", " ").split())


# --------------------------------------------------------------------------- cache
class CacheInvalid(Exception):
    """The cached index cannot be proven to belong to the given inputs."""

    def __init__(self, reasons):
        self.reasons = list(reasons)
        super().__init__("; ".join(self.reasons))


class InputMismatch(Exception):
    """--xml-sha256 does not match the actual XML file."""


def cache_paths(cache_dir: Path) -> tuple[Path, Path, Path]:
    return cache_dir / CACHE_NAME, cache_dir / SUMMARY_NAME, cache_dir / META_NAME


def compute_fingerprint(xml_sha256: str, dtd_sha256: str,
                        index_format_version: int | None = None,
                        policy_version: str | None = None) -> str:
    """Versioned cache fingerprint. Seed and selection size are deliberately absent."""
    payload = {
        "index_format_version": INDEX_FORMAT_VERSION if index_format_version is None
        else index_format_version,
        "eligibility_policy_version": ELIGIBILITY_POLICY_VERSION if policy_version is None
        else policy_version,
        "xml_sha256": xml_sha256,
        "dtd_sha256": dtd_sha256,
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":"))
                          .encode("utf-8")).hexdigest()


def atomic_write_text(path: Path, text: str) -> None:
    tmp = path.with_name(f"{path.name}.tmp-{os.getpid()}")
    try:
        tmp.write_text(text, encoding="utf-8", newline="\n")
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def file_identity(path: Path) -> dict:
    st = path.stat()
    return {"bytes": st.st_size, "mtime_ns": st.st_mtime_ns}


def hash_with_identity(path: Path) -> tuple[str, dict]:
    """Full SHA-256 plus the stat identity; fails if the file changed while it was read."""
    before = file_identity(path)
    digest = sha256_file(path)
    if file_identity(path) != before:
        raise InputMismatch(f"{path} changed while it was being hashed")
    return digest, before


def write_metadata(cache_dir: Path, xml: Path, dtd: Path, xml_sha256: str, xml_ident: dict,
                   dtd_sha256: str) -> dict:
    cache, summary, meta_path = cache_paths(cache_dir)
    meta = {
        "metadata_format_version": META_FORMAT_VERSION,
        "index_format_version": INDEX_FORMAT_VERSION,
        "eligibility_policy_version": ELIGIBILITY_POLICY_VERSION,
        "script_version": SCRIPT_VERSION,
        "code_version": code_version(),
        "xml": {"path": rel(xml), "sha256": xml_sha256, **xml_ident},
        "dtd": {"path": rel(dtd), "sha256": dtd_sha256, "bytes": dtd.stat().st_size},
        "fingerprint": compute_fingerprint(xml_sha256, dtd_sha256),
        "files": {
            CACHE_NAME: {"sha256": sha256_file(cache), "bytes": cache.stat().st_size},
            SUMMARY_NAME: {"sha256": sha256_file(summary), "bytes": summary.stat().st_size},
        },
    }
    atomic_write_text(meta_path, json.dumps(meta, indent=2, sort_keys=True) + "\n")
    return meta


def verify_cache(xml: Path, dtd: Path, cache_dir: Path, *, xml_sha256: str | None = None,
                 need_inputs: bool = True) -> dict:
    """Prove that the cache in cache_dir belongs to xml and dtd. Returns the metadata.

    Raises CacheInvalid (listing every reason found) for: missing or unreadable metadata, an
    unsupported metadata, index-format or policy version, a fingerprint that does not match
    the metadata fields, a missing or damaged cache or summary file, or an input whose
    SHA-256 differs from the one recorded.

    xml_sha256 -- if given, the caller's already computed actual digest of xml.
                  If None and xml exists, identity is checked by size and mtime_ns, falling
                  back to a full hash only when that identity differs (select with
                  --fast-xml-identity only; this cannot see a same-size edit that kept mtime).
    need_inputs -- when False (select stage) a missing XML is tolerated (its contents are
                  never read there); the DTD, which is small, is always checked if present.
    """
    cache, summary, meta_path = cache_paths(cache_dir)
    if not meta_path.exists():
        raise CacheInvalid([f"missing metadata file {rel(meta_path)}"])
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        rec_xml, rec_dtd = meta["xml"], meta["dtd"]
        rec_xml_sha, rec_dtd_sha = rec_xml["sha256"], rec_dtd["sha256"]
        rec_files = meta["files"]
        rec_fp = meta["fingerprint"]
    except (OSError, ValueError, KeyError, TypeError) as e:
        raise CacheInvalid([f"unreadable or malformed metadata: {type(e).__name__}: {e}"])
    bad = []
    if meta.get("metadata_format_version") != META_FORMAT_VERSION:
        bad.append(f"unsupported metadata format version {meta.get('metadata_format_version')!r}"
                   f" (supported: {META_FORMAT_VERSION})")
    if meta.get("index_format_version") != INDEX_FORMAT_VERSION:
        bad.append(f"unsupported index format version {meta.get('index_format_version')!r}"
                   f" (supported: {INDEX_FORMAT_VERSION})")
    if meta.get("eligibility_policy_version") != ELIGIBILITY_POLICY_VERSION:
        bad.append(f"eligibility policy version {meta.get('eligibility_policy_version')!r}"
                   f" differs from current {ELIGIBILITY_POLICY_VERSION!r}")
    try:
        expect_fp = compute_fingerprint(rec_xml_sha, rec_dtd_sha,
                                        meta.get("index_format_version"),
                                        meta.get("eligibility_policy_version"))
    except Exception:
        expect_fp = None
    if rec_fp != expect_fp:
        bad.append("fingerprint does not match the metadata fields")
    for name, path in ((CACHE_NAME, cache), (SUMMARY_NAME, summary)):
        rec = rec_files.get(name) if isinstance(rec_files, dict) else None
        if not isinstance(rec, dict) or "sha256" not in rec:
            bad.append(f"metadata has no checksum for {name}")
        elif not path.exists():
            bad.append(f"cached file missing: {name}")
        elif path.stat().st_size != rec.get("bytes"):
            bad.append(f"cached file size differs from metadata: {name}")
        elif sha256_file(path) != rec["sha256"]:
            bad.append(f"cached file checksum differs from metadata: {name}")
    if dtd.exists():
        if sha256_file(dtd) != rec_dtd_sha:
            bad.append("DTD SHA-256 differs from the one the cache was built with")
    elif need_inputs:
        bad.append(f"DTD not found: {dtd}")
    if xml_sha256 is not None:
        if xml_sha256 != rec_xml_sha:
            bad.append("XML SHA-256 differs from the one the cache was built with")
    elif xml.exists():
        if file_identity(xml) != {"bytes": rec_xml.get("bytes"), "mtime_ns": rec_xml.get("mtime_ns")}:
            actual, _ = hash_with_identity(xml)   # size or mtime moved: decide by content
            if actual != rec_xml_sha:
                bad.append("XML SHA-256 differs from the one the cache was built with")
    elif need_inputs:
        bad.append(f"XML not found: {xml}")
    if bad:
        raise CacheInvalid(bad)
    return meta


def adopt_legacy_cache(xml: Path, dtd: Path, cache_dir: Path, expected_xml_sha256: str | None) -> dict:
    """Write metadata for a cache built before metadata existed, after proving it matches.

    Fully hashes xml and dtd. Requires: the legacy summary exists and records the same XML
    SHA-256, XML size, DTD SHA-256 and cache SHA-256 as the actual files, and (if given)
    --xml-sha256 equals the actual XML digest. Never modifies the cache or the summary.
    """
    cache, summary_path, meta_path = cache_paths(cache_dir)
    if meta_path.exists():
        raise CacheInvalid([f"metadata already exists: {rel(meta_path)} (adopt is for legacy caches only)"])
    if not (cache.exists() and summary_path.exists()):
        raise CacheInvalid(["legacy cache or summary file missing"])
    legacy = json.loads(summary_path.read_text(encoding="utf-8"))
    # A legacy summary without the field was written by script 1.0.0, i.e. under policy "1".
    legacy_policy = str(legacy.get("eligibility_policy_version", LEGACY_POLICY_VERSION))
    if legacy_policy != ELIGIBILITY_POLICY_VERSION:
        raise CacheInvalid([f"legacy cache was built under eligibility policy {legacy_policy!r}, "
                            f"current is {ELIGIBILITY_POLICY_VERSION!r}; adopt refused, "
                            f"nothing written (rebuild with the index stage after human approval)"])
    xml_sha, xml_ident = hash_with_identity(xml)
    dtd_sha = sha256_file(dtd)
    if expected_xml_sha256 and expected_xml_sha256 != xml_sha:
        raise InputMismatch(f"--xml-sha256 {expected_xml_sha256} != actual {xml_sha}")
    bad = []
    if legacy.get("input_xml_sha256") != xml_sha:
        bad.append("legacy summary input_xml_sha256 differs from the actual XML")
    if legacy.get("input_xml_bytes") != xml_ident["bytes"]:
        bad.append("legacy summary input_xml_bytes differs from the actual XML size")
    if legacy.get("input_dtd_sha256") != dtd_sha:
        bad.append("legacy summary input_dtd_sha256 differs from the actual DTD")
    if legacy.get("cache_sha256") != sha256_file(cache):
        bad.append("legacy summary cache_sha256 differs from the actual cache file")
    if bad:
        raise CacheInvalid(bad)
    return write_metadata(cache_dir, xml, dtd, xml_sha, xml_ident, dtd_sha)


# --------------------------------------------------------------------------- index
def build_index(xml: Path, dtd: Path, cache_dir: Path, xml_sha256: str, xml_ident: dict,
                dtd_sha256: str) -> dict:
    """Build the cache. xml_sha256/xml_ident/dtd_sha256 are the verified actual digests.

    Every file is written to a temporary name in cache_dir and renamed into place (os.replace,
    same filesystem) only when complete; metadata is renamed last. No file is deleted except
    this run's own temporary files.
    """
    from lxml import etree  # local import: only needed for the index stage

    cache_dir.mkdir(parents=True, exist_ok=True)
    cache, summary_path, _ = cache_paths(cache_dir)
    pid = os.getpid()
    tmp1 = cache.with_name(f"{cache.name}.tmp1-{pid}")
    tmp2 = cache.with_name(f"{cache.name}.tmp2-{pid}")
    try:
        return _build_index(etree, xml, dtd, cache_dir, cache, summary_path, tmp1, tmp2,
                            xml_sha256, xml_ident, dtd_sha256)
    finally:
        for t in (tmp1, tmp2):
            if t.exists():
                t.unlink()


def _build_index(etree, xml, dtd, cache_dir, cache, summary_path, tmp, tmp2,
                 xml_sha256, xml_ident, dtd_sha256) -> dict:
    started = time.time()
    # lxml resolves the relative SYSTEM "dblp.dtd" against the XML file's directory.
    context = etree.iterparse(
        str(xml), events=("end",), tag=RECORD_TAGS,
        load_dtd=True, resolve_entities=True, no_network=True, huge_tree=True,
    )
    totals: Counter = Counter()
    excl: Counter = Counter()
    all_key_hashes = array("Q")
    max_mdate = ""
    with gzip.open(tmp, "wt", encoding="utf-8", newline="\n") as out:
        out.write("\t".join(TSV_FIELDS) + "\n")
        for _, el in context:
            tag = el.tag
            totals[tag] += 1
            md = el.get("mdate") or ""
            if md > max_mdate:
                max_mdate = md
            if tag in ("article", "inproceedings"):
                key = el.get("key") or ""
                all_key_hashes.append(hash64(key))
                reason = None
                if key.startswith("dblpnote/"):
                    reason = "dblpnote"
                elif not KEY_RE.match(key):
                    reason = "invalid_key"
                title_el = el.find("title")
                title = clean("".join(title_el.itertext())) if title_el is not None else ""
                year_el = el.find("year")
                year = clean(year_el.text or "") if year_el is not None else ""
                authors = el.findall("author")
                doi = None
                for ee in el.findall("ee"):
                    doi = normalize_doi(ee.text or "")
                    if doi:
                        break
                if reason is None and not title:
                    reason = "empty_title"
                if reason is None and not YEAR_RE.match(year):
                    reason = "non_numeric_year"
                if reason is None and len(authors) < 2:
                    reason = "fewer_than_two_authors"
                if reason is None and not doi:
                    reason = "no_doi"
                if reason:
                    excl[reason] += 1
                else:
                    out.write("\t".join([key, tag, year, venue_of(key), doi,
                                         str(len(authors)), title]) + "\n")
            # bounded memory: drop this element and everything already processed
            el.clear()
            parent = el.getparent()
            if parent is not None:
                while el.getprevious() is not None:
                    del parent[0]

    # duplicate keys among all article/inproceedings records (sort-based, 8 bytes per key)
    sorted_hashes = sorted(all_key_hashes)
    dup_hashes = set()
    for a, b in zip(sorted_hashes, sorted_hashes[1:]):
        if a == b:
            dup_hashes.add(a)
    ap_total = len(all_key_hashes)
    del sorted_hashes

    # second, cache-only pass: drop eligible rows whose key is duplicated
    kept = 0
    dup_removed = 0
    years: Counter = Counter()
    with gzip.open(tmp, "rt", encoding="utf-8") as src, \
            gzip.open(tmp2, "wt", encoding="utf-8", newline="\n") as dst:
        header = src.readline()
        dst.write(header)
        for line in src:
            key = line.split("\t", 1)[0]
            if hash64(key) in dup_hashes:
                dup_removed += 1
                continue
            dst.write(line)
            kept += 1
            years[line.split("\t")[2]] += 1
    excl["duplicate_key"] = dup_removed

    summary = {
        "script": "datasets/scripts/dblp_select.py",
        "script_version": SCRIPT_VERSION,
        "code_version": code_version(),
        "input_xml": rel(xml),
        "input_xml_bytes": xml_ident["bytes"],
        "input_xml_sha256": xml_sha256,
        "input_dtd_sha256": dtd_sha256,
        "index_format_version": INDEX_FORMAT_VERSION,
        "eligibility_policy_version": ELIGIBILITY_POLICY_VERSION,
        "fingerprint": compute_fingerprint(xml_sha256, dtd_sha256),
        "parser": {"library": "lxml.etree.iterparse", "load_dtd": True,
                   "resolve_entities": True, "no_network": True, "huge_tree": True},
        "snapshot_date_method": "newest mdate attribute over all top-level records",
        "newest_mdate": max_mdate,
        "top_level_record_counts": dict(sorted(totals.items())),
        "article_inproceedings_total": ap_total,
        "duplicate_key_values": len(dup_hashes),
        "excluded_counts_first_failing_reason": dict(sorted(excl.items())),
        "eligible_records": kept,
        "eligible_years": len(years),
        "eligible_by_year": dict(sorted(years.items())),
        "cache_file": rel(cache),
        "cache_sha256": sha256_file(tmp2),
        "elapsed_seconds": round(time.time() - started, 1),
    }
    # publish: cache, then summary, then metadata last (metadata is the validity record)
    os.replace(tmp2, cache)
    atomic_write_text(summary_path, json.dumps(summary, indent=2, sort_keys=True) + "\n")
    summary["_meta"] = write_metadata(cache_dir, xml, dtd, xml_sha256, xml_ident, dtd_sha256)
    return summary


# ------------------------------------------------------------------------ selection
def seeded(seed: int, *parts: str) -> str:
    return hashlib.sha256("|".join([str(seed), *parts]).encode("utf-8")).hexdigest()


def largest_remainder(weights: dict, caps: dict, total: int, tiebreak) -> dict:
    """Allocate `total` integer units proportionally to weights, never above caps.

    Deterministic: ties on the fractional remainder are broken by tiebreak(item) ascending.
    Units that cannot be placed because of caps are redistributed to uncapped items.
    """
    alloc = {k: 0 for k in weights}
    remaining = min(total, sum(caps.values()))
    active = {k for k in weights if caps[k] > 0}
    while remaining > 0 and active:
        wsum = sum(weights[k] for k in active)
        shares = {k: remaining * weights[k] / wsum for k in active}
        floors = {k: min(int(math.floor(shares[k])), caps[k] - alloc[k]) for k in active}
        placed = sum(floors.values())
        for k in active:
            alloc[k] += floors[k]
        remaining -= placed
        if remaining <= 0:
            break
        order = sorted((k for k in active if alloc[k] < caps[k]),
                       key=lambda k: (-(shares[k] - math.floor(shares[k])), tiebreak(k)))
        if not order:
            break
        extra = min(remaining, len(order))
        for k in order[:extra]:
            alloc[k] += 1
        remaining -= extra
        active = {k for k in active if alloc[k] < caps[k]}
    return alloc


def load_cache(path: Path) -> dict:
    """Load only what the allocation needs: year -> venue -> [key, ...] (memory-light).

    Full rows for the chosen keys are re-read afterwards by fetch_rows().
    """
    by_year: dict = defaultdict(lambda: defaultdict(list))
    with gzip.open(path, "rt", encoding="utf-8") as f:
        f.readline()
        for line in f:
            vals = line.split("\t", 4)  # key, type, year, venue, rest
            by_year[vals[2]][vals[3]].append(vals[0])
    return by_year


def fetch_rows(path: Path, keys: set) -> list[dict]:
    rows = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            if line.split("\t", 1)[0] in keys:
                rows.append(dict(zip(header, line.rstrip("\n").split("\t"))))
    return rows


def select(by_year: dict, n: int, seed: int):
    year_counts = {y: sum(len(v) for v in vs.values()) for y, vs in by_year.items()}

    # --- year allocation: floor of 1 per year when affordable, rest proportional
    years = sorted(year_counts)
    if n >= len(years):
        base = {y: 1 for y in years}
        rest_caps = {y: year_counts[y] - 1 for y in years}
        extra = largest_remainder({y: float(year_counts[y]) for y in years}, rest_caps,
                                  n - len(years), lambda y: seeded(seed, "year", y))
        year_alloc = {y: base[y] + extra[y] for y in years}
    else:
        year_alloc = largest_remainder({y: float(year_counts[y]) for y in years},
                                       dict(year_counts), n, lambda y: seeded(seed, "year", y))

    chosen_keys: list[tuple] = []
    strata = {}
    for y in years:
        q = year_alloc[y]
        if q == 0:
            continue
        venues = by_year[y]
        v_alloc = largest_remainder({v: math.sqrt(len(rs)) for v, rs in venues.items()},
                                    {v: len(rs) for v, rs in venues.items()}, q,
                                    lambda v, y=y: seeded(seed, "venue", y, v))
        for v, k in v_alloc.items():
            if k == 0:
                continue
            ranked = sorted(venues[v], key=lambda key: seeded(seed, "key", key))
            chosen_keys.extend(ranked[:k])
            strata[f"{y}|{v}"] = k
    return chosen_keys, year_alloc, strata


def finish(chosen_keys, year_alloc, strata, rows_path: Path, n: int):
    chosen = fetch_rows(rows_path, set(chosen_keys))
    chosen.sort(key=lambda r: (r["year"], r["venue"], r["key"]))
    years = sorted(year_alloc)
    stats = {
        "target": n,
        "selected": len(chosen),
        "years_with_quota": sum(1 for y in years if year_alloc[y] > 0),
        "year_quota": {y: year_alloc[y] for y in years},
        "year_venue_cells_used": len(strata),
        "distinct_venues_selected": len({r["venue"] for r in chosen}),
        "selected_by_type": dict(Counter(r["type"] for r in chosen)),
    }
    return chosen, stats


def write_selection(chosen, out: Path, stats, seed, summary, size, meta):
    out.parent.mkdir(parents=True, exist_ok=True)
    body = "\t".join(TSV_FIELDS) + "\n" + "".join(
        "\t".join(r[k] for k in TSV_FIELDS) + "\n" for r in chosen)
    tmp = out.with_name(f"{out.name}.tmp-{os.getpid()}")
    try:
        tmp.write_text(body, encoding="utf-8", newline="\n")
        os.replace(tmp, out)
    finally:
        if tmp.exists():
            tmp.unlink()
    prov = {
        "script": "datasets/scripts/dblp_select.py",
        "script_version": SCRIPT_VERSION,
        "code_version": code_version(),
        "method": "seeded stratified selection, year then sqrt-weighted venue, hash-ranked records",
        "seed": seed,
        "target_size": size,
        "input_xml_sha256": summary["input_xml_sha256"],
        "input_xml_bytes": summary["input_xml_bytes"],
        "input_dtd_sha256": meta["dtd"]["sha256"],
        "cache_fingerprint": meta["fingerprint"],
        "index_format_version": meta["index_format_version"],
        "eligibility_policy_version": meta["eligibility_policy_version"],
        "eligible_cache_sha256": summary["cache_sha256"],
        "eligible_records": summary["eligible_records"],
        "output_file": rel(out),
        "output_sha256": sha256_file(out),
        "stats": stats,
    }
    prov_path = out.with_suffix(".provenance.json")
    atomic_write_text(prov_path, json.dumps(prov, indent=2, sort_keys=True) + "\n")
    return prov_path, prov


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["index", "select", "all", "adopt"])
    ap.add_argument("--size", type=int, choices=ALLOWED_SIZES, default=1000)
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--out", type=Path, default=None, help="selection TSV path")
    ap.add_argument("--xml", type=Path, default=XML, help="DBLP XML (default datasets/raw/dblp/dblp.xml)")
    ap.add_argument("--dtd", type=Path, default=DTD, help="DBLP DTD (default datasets/raw/dblp/dblp.dtd)")
    ap.add_argument("--cache-dir", type=Path, default=DERIVED,
                    help="directory of the eligible index cache (default datasets/raw/dblp/derived)")
    ap.add_argument("--force-index", action="store_true", help="rebuild the eligible cache")
    ap.add_argument("--xml-sha256", default=None,
                    help="EXPECTED SHA-256 of the XML, verified against the actual file "
                         "(index/all/adopt hash it in full) or against the cache metadata (select); "
                         "a mismatch is a failure")
    ap.add_argument("--fast-xml-identity", action="store_true",
                    help="select only: skip the full XML hash and accept the XML when its size and "
                         "mtime equal those recorded at hashing time (does NOT detect a same-size "
                         "edit that preserved mtime; full hash is the default)")
    ap.add_argument("--hash", action="store_true", help="deprecated no-op (index always hashes the XML)")
    args = ap.parse_args(argv)

    xml, dtd, cache_dir = args.xml, args.dtd, args.cache_dir
    cache, summary_path, _ = cache_paths(cache_dir)
    if args.xml_sha256 and not re.fullmatch(r"[0-9a-f]{64}", args.xml_sha256):
        print("ERROR: --xml-sha256 must be 64 lower-case hex digits", file=sys.stderr)
        return 2
    try:
        if args.stage == "adopt":
            meta = adopt_legacy_cache(xml, dtd, cache_dir, args.xml_sha256)
            print(f"adopted legacy cache, wrote {rel(cache_paths(cache_dir)[2])} "
                  f"fingerprint={meta['fingerprint']}")
            return 0
        if args.stage in ("index", "all"):
            for label, path in (("XML", xml), ("DTD", dtd)):
                if not path.exists():
                    print(f"ERROR: {label} not found: {path}", file=sys.stderr)
                    return 2
            xml_sha, xml_ident = hash_with_identity(xml)
            if args.xml_sha256 and args.xml_sha256 != xml_sha:
                raise InputMismatch(f"--xml-sha256 {args.xml_sha256} != actual {xml_sha} of {xml}")
            dtd_sha = sha256_file(dtd)
            reasons = None
            if args.force_index:
                reasons = ["--force-index"]
            else:
                try:
                    verify_cache(xml, dtd, cache_dir, xml_sha256=xml_sha, need_inputs=True)
                    print(f"index cache verified, skipping pass: {rel(cache)}")
                except CacheInvalid as e:
                    reasons = e.reasons
            if reasons is not None:
                print("rebuilding index; cache not reusable: " + "; ".join(reasons))
                s = build_index(xml, dtd, cache_dir, xml_sha, xml_ident, dtd_sha)
                print(json.dumps({k: s[k] for k in ("eligible_records", "newest_mdate",
                                                    "article_inproceedings_total",
                                                    "excluded_counts_first_failing_reason",
                                                    "elapsed_seconds")}, indent=2))
        if args.stage in ("select", "all"):
            if args.stage == "select":
                # cheap checks first (metadata, versions, cache checksums, DTD, XML identity), so
                # an unusable cache fails before the multi-GB XML hash starts
                meta = verify_cache(xml, dtd, cache_dir, need_inputs=False)
                if xml.exists() and not args.fast_xml_identity:
                    actual, _ = hash_with_identity(xml)
                    meta = verify_cache(xml, dtd, cache_dir, xml_sha256=actual, need_inputs=False)
                if args.xml_sha256 and args.xml_sha256 != meta["xml"]["sha256"]:
                    raise InputMismatch(
                        f"--xml-sha256 {args.xml_sha256} != XML sha256 {meta['xml']['sha256']} "
                        f"recorded in the cache metadata")
            else:
                meta = verify_cache(xml, dtd, cache_dir, xml_sha256=xml_sha, need_inputs=True)
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
            by_year = load_cache(cache)
            chosen_keys, year_alloc, strata = select(by_year, args.size, args.seed)
            del by_year
            chosen, stats = finish(chosen_keys, year_alloc, strata, cache, args.size)
            out = args.out or (REPO / "datasets" / "samples" / "dblp" / f"dblp_selection_{args.size}.tsv"
                               if args.size == 1000 else
                               cache_dir / f"dblp_selection_{args.size}.tsv")
            prov_path, prov = write_selection(chosen, out, stats, args.seed, summary, args.size, meta)
            print(f"selected {stats['selected']} of target {args.size}; years={stats['years_with_quota']}, "
                  f"cells={stats['year_venue_cells_used']}, venues={stats['distinct_venues_selected']}")
            print(f"wrote {rel(out)} sha256={prov['output_sha256']}")
            print(f"wrote {rel(prov_path)}")
    except CacheInvalid as e:
        print("ERROR: DBLP index cache is invalid, nothing written for this stage: "
              + "; ".join(e.reasons)
              + "  (run the 'index' stage to rebuild)",
              file=sys.stderr)
        return 2
    except InputMismatch as e:
        print(f"ERROR: input mismatch: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
