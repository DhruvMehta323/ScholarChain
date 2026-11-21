#!/usr/bin/env python3
"""Read-only summary of one pipeline output root: counters.json, per-table row counts, rejects by
reason, largest teams, and one sample record per table (long strings truncated).

Usage: python3 -I modules/hadoop/tools/summarize_run.py <root> [--counters]
"""
import collections
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_roots import table_lines, tables  # noqa: E402


def trunc(o, n=160):
    if isinstance(o, str):
        return o if len(o) <= n else o[:n] + "..."
    if isinstance(o, list):
        return [trunc(x, n) for x in o[:6]] + (["..."] if len(o) > 6 else [])
    if isinstance(o, dict):
        return {k: trunc(v, n) for k, v in o.items()}
    return o


def main(root, show_counters):
    if show_counters:
        for p in glob.glob(os.path.join(root, "run", "*", "*", "counters.json")):
            print(open(p).read())
        return
    print("== per-table row counts")
    data = {}
    for t in tables(root):
        if t.startswith("work/hj6b") or t.startswith("work/hj6c"):
            continue  # sortKey TAB json tables; the ER tables below are pure JSON
        data[t] = [json.loads(l) for l in table_lines(root, t)]
        print(f"{t:28s} {len(data[t])}")
    print("== rejects by reason code (source, reason)")
    c = collections.Counter((r["source"], r["reasonCode"]) for r in data.get("rejects", []))
    for k, v in sorted(c.items()):
        print(f"{k[0]:10s} {k[1]:20s} {v}")
    teams = sorted(data.get("teams", []), key=lambda t: (-t["teamSize"], t["workId"]))
    print("== 3 largest teams (workId, n, pairs)")
    for t in teams[:3]:
        n = t["teamSize"]
        print(t["workId"], n, n * (n - 1) // 2)
    print("== max team size:", teams[0]["teamSize"] if teams else None)
    print("== largest pair expansion:", max((n * (n - 1) // 2 for n in (t["teamSize"] for t in teams)), default=0))
    print("== one sample record per table (truncated)")
    for t, rows in data.items():
        if rows:
            print(f"-- {t}")
            print(json.dumps(trunc(rows[0]), ensure_ascii=False))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: summarize_run.py <root> [--counters]")
    main(sys.argv[1], "--counters" in sys.argv)
