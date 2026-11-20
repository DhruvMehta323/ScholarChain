#!/usr/bin/env python3
"""Offline check (no network, writes nothing): re-normalise every DOI in the committed samples
under the canonical rule (doi_canonical.py) and count how many change or are rejected.

Sources: datasets/samples/crossref/*.jsonl (doi, request_url), openalex/*.jsonl (doi, request_url),
doi_spot_check.json (every "doi" key), dblp/dblp_selection_1000.tsv (doi column, the 1,000-work
selection), dblp_1000.csv (doi column, legacy raw-case values compared with their lower-case form), dblp_100.csv (ee column, URL form; compared with the legacy
rule: strip prefix, percent-decode, lower-case). Stored values are already canonical forms, so any
difference between stored and re-normalised value is a change. Exit 0 only when 0 change.

Option --dblp-xml PATH: instead, stream the raw dblp.xml read-only (bytes, no hash, no parse, no
writes), extract doi.org <ee> URLs and compare the recorded policy-1 rule with the canonical rule.
Report-only (always exit 0, whatever it finds). Matches line by line: an <ee> element split over
several lines is not seen, and XML entities are not expanded.
"""
import csv
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote

sys.path.insert(0, str(Path(__file__).resolve().parent))
from doi_canonical import doi_path_segment, normalize_doi, normalize_doi_url_form  # noqa: E402

_EE = re.compile(rb"<ee(?:\s[^>]*)?>([^<]*)</ee>")
_URL = re.compile(r"(?:https?://(?:dx\.)?doi\.org/)", re.IGNORECASE)
# Recorded policy-1 rule, as in the pre-repair dblp_select.py (lines 87-91, cited in review note N1).
DOI_URL_RE = re.compile(r"^https?://(?:dx\.)?doi\.org/(10\.[0-9]{4,9}/\S+)$", re.IGNORECASE)


def _old_rule(url):
    """Recorded policy-1 rule: match DOI_URL_RE on raw.strip() (still percent-encoded); no match
    gives None; else unquote(group(1)) (default errors="replace"), strip, lower-case."""
    m = DOI_URL_RE.match(url.strip())
    if not m:
        return None
    return unquote(m.group(1)).strip().lower()


def dblp_xml_mode(xml_path):
    """Read-only, offline: byte-stream dblp.xml, compare old and canonical rule on doi.org <ee>
    URLs. No hashing, no XML parse, no writes. Entities are not expanded (a literal &amp; stays
    as written), so counts are on raw ee text."""
    n_ee = n_doi = old_ok = new_ok = 0
    newly_rejected, newly_accepted, differ = [], [], []
    with open(xml_path, "rb") as f:
        for line in f:
            if b"<ee" not in line:
                continue
            for m in _EE.finditer(line):
                n_ee += 1
                url = m.group(1).decode("iso-8859-1").strip()
                if not _URL.match(url):
                    continue
                n_doi += 1
                old, new = _old_rule(url), normalize_doi_url_form(url)
                old_ok += old is not None
                new_ok += new is not None
                if old is not None and new is None:
                    newly_rejected.append((url, old))
                elif old is None and new is not None:
                    newly_accepted.append((url, new))
                elif old is not None and old != new:
                    differ.append((url, old, new))
    print(f"ee elements: {n_ee}; doi.org ee URLs: {n_doi}")
    print(f"accepted by old rule: {old_ok}; accepted by canonical rule: {new_ok}")
    print(f"newly rejected ee URLs: {len(newly_rejected)}")
    for u, o in newly_rejected:
        print("  NEWLY REJECTED:", u, "(old rule gave", o + ")")
    print(f"newly accepted ee URLs: {len(newly_accepted)}; accepted with a different value: {len(differ)}")
    for t in newly_accepted + differ:
        print("  OTHER:", t)
    return 0


if len(sys.argv) > 1:
    if len(sys.argv) == 3 and sys.argv[1] == "--dblp-xml":
        sys.exit(dblp_xml_mode(sys.argv[2]))
    sys.exit("usage: renormalize_check.py [--dblp-xml PATH]")

S = Path(__file__).resolve().parents[1] / "samples"
total, changes, raw_case = 0, [], 0


def chk(src, stored, expected=None):
    global total
    total += 1
    got = normalize_doi(stored)
    want = stored if expected is None else expected
    if got != want:
        changes.append((src, stored, got))


def walk(o, src):
    if isinstance(o, dict):
        for k, v in o.items():
            if k == "doi" and isinstance(v, str):
                chk(src, v)
            else:
                walk(v, src)
    elif isinstance(o, list):
        for v in o:
            walk(v, src)


for sub, prefix in (("crossref/crossref_works_sample.jsonl", "https://api.crossref.org/works/"),
                    ("openalex/openalex_works_sample.jsonl", "https://api.openalex.org/works/doi:")):
    n_req = 0
    for i, line in enumerate((S / sub).read_text(encoding="utf-8").split("\n"), 1):
        if not line:
            continue
        r = json.loads(line)
        chk(f"{sub}:{i}", r["doi"])
        if r.get("request_url"):
            n_req += 1
            if r["request_url"] != prefix + doi_path_segment(r["doi"]):
                changes.append((f"{sub}:{i} request_url", r["request_url"], prefix + doi_path_segment(r["doi"])))
    print(f"{sub}: request_url values checked {n_req}")
walk(json.loads((S / "doi_spot_check.json").read_text(encoding="utf-8")), "doi_spot_check.json")
with open(S / "dblp/dblp_selection_1000.tsv", encoding="utf-8", newline="") as f:
    for i, row in enumerate(csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE), 2):
        chk(f"dblp_selection_1000.tsv:{i}", row["doi"])
with open(S / "dblp_1000.csv", encoding="utf-8", newline="") as f:
    for i, row in enumerate(csv.DictReader(f), 2):
        # legacy exploratory file stores the DOI as DBLP wrote it (case not normalised); compare
        # with the lower-cased value, and count raw-case values separately
        raw_case += row["doi"] != row["doi"].lower()
        chk(f"dblp_1000.csv:{i}", row["doi"], row["doi"].lower())
with open(S / "dblp_100.csv", encoding="utf-8", newline="") as f:
    for i, row in enumerate(csv.DictReader(f), 2):
        ee = row["ee"].strip()
        legacy = unquote(ee.split("doi.org/", 1)[1]).lower() if "doi.org/" in ee else None
        if legacy is not None:
            chk(f"dblp_100.csv:{i}", ee, legacy)
for c in changes:
    print("CHANGED:", c)
print(f"dblp_1000.csv (legacy raw-case values, not canonical forms, expected equal to lower-case): {raw_case} rows have upper-case letters")
print(f"dois checked: {total}; changed or rejected: {len(changes)}")
sys.exit(1 if changes else 0)
