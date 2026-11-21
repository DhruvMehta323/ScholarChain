#!/usr/bin/env python3
"""Per-table content equality of two curated output roots (sorted lines, SHA-256).

Usage: python3 -I modules/hadoop/tools/compare_roots.py <rootA> <rootB> [--ignore-run-fields]
Read-only. `--ignore-run-fields` (for roots built by DIFFERENT code versions, for example the F7 proof
against a Phase 04 root) drops the top-level `provenance` and `runId` of every row before hashing and
compares only the curated tables both roots have; every other field must be equal. Exit 0 when every table has the same row count and the same hash, else 1.
"""
import gzip
import hashlib
import json
import os
import sys


# Entity-resolution tables (Phase 05) live under work/<job>/<table>; the name shown is the path.
ER_TABLES = [
    "work/hj6a/entity-views",
    "work/hj6b/block-sizes",
    "work/hj6b/oversize-keys",
    "work/hj6c/oversize-blocks",
    "work/hj6d/match-candidates",
    "work/hj7/match-decisions",
]


def table_lines(root, table):
    base = os.path.join(root, table) if table.startswith("work/") else os.path.join(root, "curated", table)
    out = []
    for dp, _, fs in os.walk(base):
        for f in fs:
            if not f.startswith("part-"):
                continue
            p = os.path.join(dp, f)
            op = gzip.open if f.endswith(".gz") else open
            with op(p, "rt", encoding="utf-8") as h:
                out.extend(l.rstrip("\n") for l in h if l.strip())
    return sorted(out)


def strip(lines):
    out = []
    for l in lines:
        try:
            o = json.loads(l)
        except ValueError:
            out.append(l)
            continue
        if isinstance(o, dict):
            o.pop("provenance", None)
            o.pop("runId", None)
        out.append(json.dumps(o, sort_keys=True, ensure_ascii=False, separators=(",", ":")))
    return sorted(out)


def digest(lines):
    h = hashlib.sha256()
    for l in lines:
        h.update((l + "\n").encode("utf-8"))
    return h.hexdigest()


def tables(root):
    d = os.path.join(root, "curated")
    cur = sorted(t for t in os.listdir(d) if os.path.isdir(os.path.join(d, t)))
    er = [t for t in ER_TABLES if os.path.isdir(os.path.join(root, t))]
    return cur + er


def main(a, b, ignore=False):
    if ignore:
        ts = sorted(set(tables(a)) & set(tables(b)) - set(ER_TABLES))
    else:
        ts = sorted(set(tables(a)) | set(tables(b)))
    ok = True
    for t in ts:
        la, lb = table_lines(a, t), table_lines(b, t)
        if ignore:
            la, lb = strip(la), strip(lb)
        ha, hb = digest(la), digest(lb)
        same = ha == hb and len(la) == len(lb)
        ok = ok and same
        print(f"{t:34s} rows A={len(la):6d} B={len(lb):6d} sha256(A)={ha[:16]} sha256(B)={hb[:16]} {'EQUAL' if same else 'DIFFERENT'}")
    print("RESULT:", "ALL TABLES EQUAL" if ok else "DIFFERENCES FOUND")
    return 0 if ok else 1


if __name__ == "__main__":
    args = [x for x in sys.argv[1:] if x != "--ignore-run-fields"]
    if len(args) != 2:
        sys.exit("usage: compare_roots.py <rootA> <rootB> [--ignore-run-fields]")
    sys.exit(main(args[0], args[1], "--ignore-run-fields" in sys.argv[1:]))
