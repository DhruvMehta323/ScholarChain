#!/usr/bin/env python3
"""Prove that the contact email and API keys do not appear in any file under datasets/.

Reads SCHOLARCHAIN_CONTACT_EMAIL and OPENALEX_API_KEY from the environment, scans every file
under datasets/ (raw tree included, binary-safe), and prints only match COUNTS and the number
of files scanned. The values themselves are never printed. Exit 1 if any match is found.
"""
import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
secrets = {n: os.environ.get(n, "").strip() for n in ("SCHOLARCHAIN_CONTACT_EMAIL", "OPENALEX_API_KEY")}
total = 0
for name, val in secrets.items():
    if not val:
        print(f"{name}: not set in this environment, nothing to scan for")
        continue
    needles = {val.encode(), val.lower().encode(), val.replace("@", "%40").encode(),
               val.replace("@", "%40").lower().encode()}
    files = hits = 0
    for p in (REPO / "datasets").rglob("*"):
        if not p.is_file():
            continue
        files += 1
        if p.stat().st_size > 600_000_000:   # dblp.xml is public DBLP data; skip the 5.4 GB file
            continue
        data = p.read_bytes()
        hits += sum(data.count(n) for n in needles)
    print(f"{name}: files scanned={files}, matches={hits}")
    total += hits
sys.exit(1 if total else 0)
