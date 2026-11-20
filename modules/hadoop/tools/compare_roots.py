#!/usr/bin/env python3
"""Per-table content equality of two curated output roots (sorted lines, SHA-256).

Usage: python3 -I modules/hadoop/tools/compare_roots.py <rootA> <rootB>
Read-only. Exit 0 when every table has the same row count and the same hash, else 1.
"""
import gzip
import hashlib
import os
import sys


def table_lines(root, table):
    base = os.path.join(root, "curated", table)
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


def digest(lines):
    h = hashlib.sha256()
    for l in lines:
        h.update((l + "\n").encode("utf-8"))
    return h.hexdigest()


def tables(root):
    d = os.path.join(root, "curated")
    return sorted(t for t in os.listdir(d) if os.path.isdir(os.path.join(d, t)))


def main(a, b):
    ts = sorted(set(tables(a)) | set(tables(b)))
    ok = True
    for t in ts:
        la, lb = table_lines(a, t), table_lines(b, t)
        ha, hb = digest(la), digest(lb)
        same = ha == hb and len(la) == len(lb)
        ok = ok and same
        print(f"{t:28s} rows A={len(la):6d} B={len(lb):6d} sha256(A)={ha[:16]} sha256(B)={hb[:16]} {'EQUAL' if same else 'DIFFERENT'}")
    print("RESULT:", "ALL TABLES EQUAL" if ok else "DIFFERENCES FOUND")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: compare_roots.py <rootA> <rootB>")
    sys.exit(main(sys.argv[1], sys.argv[2]))
