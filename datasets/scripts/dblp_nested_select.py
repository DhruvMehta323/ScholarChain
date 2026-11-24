#!/usr/bin/env python3
"""Nested, seeded DBLP selection: dblp-50k contains all of dblp-10k (stdlib only).

Phase 15a. Scales for the Phase 15 experiments:

  dblp-10k  the Phase 03 10,000-work selection, reused unchanged (dblp_select.py 1.0.0, seed 441).
  dblp-50k  all 10,000 dblp-10k works, plus FILL_COUNT works drawn from the Phase 03 100,000-work
            selection minus dblp-10k. Nesting (10k subset of 50k subset of 100k) is verified, not
            assumed.

Rule for the fill (deterministic, independent of input order):
  candidates = keys of the 100K selection that are not in the 10K selection (90,000 expected)
  rank       = sha256("<seed>|nested-50k|<key>") ascending, take the first FILL_COUNT
  output     = the 10K rows plus the filled rows, sorted by (year, venue, key), the order the
               Phase 03 selections already use. Rows are copied byte-for-byte from the source TSVs.
The 100K selection is itself the Phase 03 stratified sample (year, then sqrt-weighted venue), so a
uniform seeded subsample of it keeps its year and venue mix up to sampling noise. This is a design
choice for a clean scale comparison; it is not a re-stratification at 50K.

Parsing note: the selection TSVs have titles that begin with a double quote. They must be read
with plain str.split("\\t") (as every ScholarChain script does) or csv.QUOTE_NONE. Reading them
with the default csv dialect merges rows and under-counts distinct keys (99,992 instead of 100,000).

Never overwrites an existing output whose content differs (exit 2); an identical rerun reports
"up to date". Provenance JSON is written next to the output, and a commit-safe stats file can be
written with --stats-out.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

SCRIPT_VERSION = "1.0.0"
METHOD_ID = "nested-50k"
DEFAULT_SEED = 441
TARGET = 50000
REPO = Path(__file__).resolve().parents[2]
DERIVED = REPO / "datasets" / "raw" / "dblp" / "derived"
FIELDS = ["key", "type", "year", "venue", "doi", "n_authors", "title"]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def code_version() -> str:
    try:
        head = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, timeout=10).stdout.strip() or "unknown"
        dirty = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain", "--",
                                "datasets/scripts"], capture_output=True, text=True,
                               timeout=10).stdout.strip()
        return head + ("+uncommitted-scripts" if dirty else "")
    except Exception:
        return "unknown"


def rel(p: Path) -> str:
    """Repo-relative path WITHOUT following symlinks (datasets/raw is a symlink to another checkout).

    os.path.abspath normalizes ".." and makes the path absolute but never resolves links, so
    datasets/raw/... stays datasets/raw/... Paths outside the repo are returned unchanged.
    """
    try:
        return Path(os.path.abspath(p)).relative_to(REPO).as_posix()
    except ValueError:
        return str(p)


def read_lines(path: Path) -> tuple[str, list[tuple[str, str]]]:
    """(header line, [(key, full line without newline)]) using plain tab splitting."""
    with open(path, encoding="utf-8", newline="") as f:
        header = f.readline().rstrip("\n")
        rows = []
        for line in f:
            line = line.rstrip("\n")
            if line:
                rows.append((line.split("\t", 1)[0], line))
    return header, rows


def rank(seed: int, key: str) -> str:
    return hashlib.sha256(f"{seed}|{METHOD_ID}|{key}".encode("utf-8")).hexdigest()


def sort_key(line: str):
    v = line.split("\t")
    return (v[2], v[3], v[0])  # year, venue, key


def build(rows10, rows100, seed: int, target: int = TARGET):
    """Return (selected lines, stats). Raises ValueError if nesting or sizes are impossible."""
    k10 = [k for k, _ in rows10]
    k100 = [k for k, _ in rows100]
    if len(set(k10)) != len(k10):
        raise ValueError("duplicate keys in the 10K selection")
    if len(set(k100)) != len(k100):
        raise ValueError("duplicate keys in the 100K selection")
    missing = set(k10) - set(k100)
    if missing:
        raise ValueError(f"10K is not a subset of 100K: {len(missing)} keys missing")
    line100 = dict(rows100)
    for k, line in rows10:
        if line100[k] != line:
            raise ValueError(f"row for {k} differs between the 10K and 100K selections")
    fill = target - len(rows10)
    if fill < 0:
        raise ValueError("target smaller than the 10K selection")
    s10 = set(k10)
    candidates = [k for k in k100 if k not in s10]
    if fill > len(candidates):
        raise ValueError(f"need {fill} fill works but only {len(candidates)} candidates")
    chosen = sorted(candidates, key=lambda k: rank(seed, k))[:fill]
    lines = [line for _, line in rows10] + [line100[k] for k in chosen]
    lines.sort(key=sort_key)
    return lines, {"candidates": len(candidates), "fill": fill, "chosen_fill_keys": chosen}


def mix(lines):
    t = Counter(l.split("\t")[1] for l in lines)
    y = Counter(l.split("\t")[2] for l in lines)
    v = {l.split("\t")[3] for l in lines}
    return {"by_type": dict(sorted(t.items())), "distinct_years": len(y), "distinct_venues": len(v),
            "min_year": min(y), "max_year": max(y)}


def atomic_write(path: Path, data: bytes):
    tmp = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    try:
        tmp.write_bytes(data)
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--sel-10k", type=Path, default=DERIVED / "dblp_selection_10000.tsv")
    ap.add_argument("--sel-100k", type=Path, default=DERIVED / "dblp_selection_100000.tsv")
    ap.add_argument("--out", type=Path, default=DERIVED / "dblp_selection_50000.tsv")
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--target", type=int, default=TARGET)
    ap.add_argument("--stats-out", type=Path, default=None,
                    help="optional commit-safe JSON with counts and checksums")
    a = ap.parse_args(argv)
    for p in (a.sel_10k, a.sel_100k):
        if not p.is_file():
            print(f"ERROR: input not found: {p}", file=sys.stderr)
            return 2
    h10, rows10 = read_lines(a.sel_10k)
    h100, rows100 = read_lines(a.sel_100k)
    if h10 != h100 or h10.split("\t") != FIELDS:
        print("ERROR: unexpected TSV header", file=sys.stderr)
        return 2
    try:
        lines, info = build(rows10, rows100, a.seed, a.target)
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    data = ("\n".join([h10] + lines) + "\n").encode("utf-8")
    out_sha = hashlib.sha256(data).hexdigest()
    if a.out.exists() and sha256_file(a.out) != out_sha:
        print(f"ERROR: {a.out} exists with different content; not overwriting", file=sys.stderr)
        return 2
    a.out.parent.mkdir(parents=True, exist_ok=True)
    state = "up to date" if a.out.exists() else "written"
    if state == "written":
        atomic_write(a.out, data)
    keys50 = {l.split("\t", 1)[0] for l in lines}
    k10 = {k for k, _ in rows10}
    k100 = {k for k, _ in rows100}
    prov = {
        "artifact": rel(a.out), "script": "datasets/scripts/dblp_nested_select.py",
        "script_version": SCRIPT_VERSION, "code_version": code_version(),
        "method": (f"all of the 10K selection plus the {info['fill']} works of (100K minus 10K) with the "
                   f"smallest sha256('<seed>|{METHOD_ID}|<key>'); output sorted by (year, venue, key)"),
        "seed": a.seed, "target": a.target,
        "inputs": {"selection_10k": {"path": rel(a.sel_10k), "sha256": sha256_file(a.sel_10k),
                                     "rows": len(rows10)},
                   "selection_100k": {"path": rel(a.sel_100k), "sha256": sha256_file(a.sel_100k),
                                      "rows": len(rows100)}},
        "counts": {"selected": len(lines), "distinct_keys": len(keys50),
                   "from_10k": len(rows10), "fill_from_100k_minus_10k": info["fill"],
                   "fill_candidates": info["candidates"]},
        "nesting": {"10k_subset_of_50k": k10 <= keys50, "50k_subset_of_100k": keys50 <= k100,
                    "10k_subset_of_100k": k10 <= k100},
        "mix": {"10k": mix([l for _, l in rows10]), "50k": mix(lines), "100k": mix([l for _, l in rows100])},
        "output_bytes": len(data), "output_sha256": out_sha,
    }
    prov_path = a.out.with_suffix(".provenance.json")
    atomic_write(prov_path, (json.dumps(prov, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    print(f"{state}: {rel(a.out)} rows={len(lines)} sha256={out_sha}")
    print(json.dumps({"counts": prov["counts"], "nesting": prov["nesting"], "mix_50k": prov["mix"]["50k"],
                      "mix_10k": prov["mix"]["10k"]}, sort_keys=True))
    if a.stats_out:
        a.stats_out.parent.mkdir(parents=True, exist_ok=True)
        stats = {k: prov[k] for k in ("script", "script_version", "code_version", "method", "seed",
                                      "target", "inputs", "counts", "nesting", "mix")}
        stats["output"] = {"path": prov["artifact"], "bytes": len(data), "sha256": out_sha}
        stats["selection_10k_reused"] = {"path": rel(a.sel_10k), "sha256": prov["inputs"]["selection_10k"]["sha256"],
                                         "rows": len(rows10)}
        atomic_write(a.stats_out, (json.dumps(stats, indent=2, sort_keys=True) + "\n").encode("utf-8"))
        print(f"stats {rel(a.stats_out)}")
    return 0 if prov["nesting"]["10k_subset_of_50k"] and prov["nesting"]["50k_subset_of_100k"] else 1


if __name__ == "__main__":
    sys.exit(main())
