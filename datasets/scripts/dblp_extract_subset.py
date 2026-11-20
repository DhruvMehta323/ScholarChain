#!/usr/bin/env python3
"""Extract the selected DBLP records from dblp.xml into a small DBLP-shaped XML file.

One read-only, sequential, streaming pass over dblp.xml (never loaded whole; the SHA-256 of the
source is computed in the same pass). Each selected record is sliced from the source by record
boundaries (start tag with the exact key attribute up to its matching end tag), so its original
bytes are preserved, including unresolved DBLP named entities (for example &uuml;) and inline
markup. Records are written in selection-TSV order.

Output layout (deterministic, so a rerun is byte-identical):
    <?xml version="1.0" encoding="ISO-8859-1"?>\\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\\n<dblp>\\n
    record\\n record\\n ... </dblp>\\n

DTD resolution: the doctype names "dblp.dtd" with a relative system id, which resolves against the
directory of the output file. dblp.dtd is therefore copied next to the output (byte-identical copy
of --dtd; created only if absent, and only after every input check has passed; a different
existing file is an error, never overwritten).

Idempotent and non-destructive: output and provenance are written to temporary names in the same
directory and moved with os.replace. If the output and its provenance sidecar exist, and the
recorded output sha256, selection sha256, dtd sha256, source size and script version all match,
the run reports "up to date" and rewrites nothing. No file is deleted except this run's own
temporary files after a failure.

XML awareness: comments, CDATA sections and processing instructions are skipped as whole tokens, so
a start tag inside one is never a record. A token cut by a chunk edge is carried to the next chunk
(an unterminated token larger than a chunk is held in memory until its closer arrives).

Standard library plus lxml (validation only). Exit status: 0 success, 1 keys missing from the
source (output is still written), 2 usage or input error (nothing written:
no directory, dtd copy, output or sidecar is created before all input checks pass).
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

SCRIPT_VERSION = "1.1.0"
REPO = Path(__file__).resolve().parents[2]
EXPECTED_XML_SHA256 = "f9f1f1e19621f7b03edbe18f8e50e36af24d557a626177145965b974f1e22ef3"
DEFAULT_XML = REPO / "datasets/raw/dblp/dblp.xml"
DEFAULT_DTD = REPO / "datasets/raw/dblp/dblp.dtd"
DEFAULT_SELECTION = REPO / "datasets/samples/dblp/dblp_selection_1000.tsv"
DEFAULT_OUT = REPO / "datasets/raw/dblp/derived/dblp_selection_1000.xml"

HEADER = (b'<?xml version="1.0" encoding="ISO-8859-1"?>\n'
          b'<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n')
FOOTER = b"</dblp>\n"
CHUNK = 64 * 1024 * 1024
TAIL = 16384  # bytes kept between chunks so a start tag split by a chunk edge is still matched
# One tokenizer: comments, CDATA sections and processing instructions are consumed whole (leftmost
# match wins), so a start tag inside them is never taken as a record. An unterminated opener runs
# to the end of the buffer (\Z) and is detected in scan() so it can be carried to the next chunk.
TOKEN_RE = re.compile(
    rb"(?P<comment><!--.*?(?:-->|\Z))"
    rb"|(?P<cdata><!\[CDATA\[.*?(?:\]\]>|\Z))"
    rb"|(?P<pi><\?.*?(?:\?>|\Z))"
    rb"|<(?P<tag>article|inproceedings|proceedings|book|incollection|phdthesis|mastersthesis|www|data)"
    rb"(?=[ \t\r\n])[^>]*?[ \t\r\n]key=\"(?P<key>[^\"]*)\"[^>]*>", re.DOTALL)
CLOSERS = {"comment": b"-->", "cdata": b"]]>", "pi": b"?>"}
MIN_LEN = {"comment": 7, "cdata": 12, "pi": 4}  # opener + closer, so "<!-->" is not terminated


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def code_version():
    try:
        head = subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True,
                              text=True, timeout=10).stdout.strip() or "unknown"
        dirty = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain", "--",
                                "datasets/scripts/dblp_extract_subset.py"],
                               capture_output=True, text=True, timeout=10).stdout.strip()
        return head + ("+uncommitted" if dirty else "")
    except Exception:
        return "unknown"


def rel(p):
    try:
        return str(Path(p).resolve().relative_to(REPO))
    except ValueError:
        return str(p)


def read_selection(path):
    """Rows of the selection TSV in file order, as dicts keyed by the header columns."""
    with open(path, encoding="utf-8", newline="") as f:
        header = f.readline().rstrip("\n").split("\t")
        rows = [dict(zip(header, line.rstrip("\n").split("\t"))) for line in f if line.strip()]
    return rows


def scan(xml_path, wanted):
    """Single pass. Returns (records {key: bytes}, duplicates [key], sha256 hex, total bytes)."""
    found, dups = {}, []
    h = hashlib.sha256()
    total = 0
    carry = b""
    with open(xml_path, "rb") as f:
        eof = False
        while not eof:
            chunk = f.read(CHUNK)
            if chunk:
                h.update(chunk)
                total += len(chunk)
            else:
                eof = True
            buf = carry + chunk
            limit = len(buf) if eof else max(0, len(buf) - TAIL)
            cut = limit
            for m in TOKEN_RE.finditer(buf):
                if m.start() >= limit:
                    break
                kind = m.lastgroup if m.lastgroup in CLOSERS else "tag"
                if kind != "tag":
                    if not m.group(0).endswith(CLOSERS[kind]) or len(m.group(0)) < MIN_LEN[kind]:
                        # unterminated here: carry from the opener so the next chunk sees it whole
                        if not eof:
                            cut = m.start()
                            break
                        continue
                    cut = max(cut, m.end())  # a token straddling the limit is consumed whole
                    continue
                key = m.group("key").decode("latin-1")
                if key not in wanted:
                    continue
                end_tag = b"</" + m.group("tag") + b">"
                e = buf.find(end_tag, m.end())
                if e < 0:
                    if eof:
                        raise ValueError(f"record {key} has no end tag")
                    cut = m.start()
                    break
                rec = buf[m.start():e + len(end_tag)]
                if key in found:
                    dups.append(key)
                else:
                    found[key] = rec
            carry = buf[cut:]
    return found, dups, h.hexdigest(), total


def build_output(rows, found):
    parts = [HEADER]
    for r in rows:
        rec = found.get(r["key"])
        if rec is not None:
            parts.append(rec + b"\n")
    parts.append(FOOTER)
    return b"".join(parts)


def validate_output(out_path, rows):
    """Parse the output with lxml against the local DTD and compare with the TSV columns."""
    from lxml import etree
    parser = etree.XMLParser(load_dtd=True, resolve_entities=True, no_network=True, huge_tree=True)
    root = etree.parse(str(out_path), parser).getroot()
    by_key = {}
    for el in root:
        by_key.setdefault(el.get("key"), el)
    res = {"records_in_output": len(root), "records_expected": len(rows), "missing_keys": [],
           "type": {"agree": 0, "disagree": 0}, "year": {"agree": 0, "disagree": 0},
           "title": {"agree": 0, "disagree": 0}, "n_authors": {"agree": 0, "disagree": 0},
           "disagreement_examples": []}

    def clean(s):
        return " ".join(s.replace("\t", " ").replace("\n", " ").replace("\r", " ").split())

    for r in rows:
        el = by_key.get(r["key"])
        if el is None:
            res["missing_keys"].append(r["key"])
            continue
        t = el.find("title")
        y = el.find("year")
        got = {"type": el.tag, "year": (y.text or "") if y is not None else "",
               "title": clean("".join(t.itertext())) if t is not None else "",
               "n_authors": str(len(el.findall("author")))}
        for col in ("type", "year", "title", "n_authors"):
            if got[col] == r[col]:
                res[col]["agree"] += 1
            else:
                res[col]["disagree"] += 1
                if len(res["disagreement_examples"]) < 10:
                    res["disagreement_examples"].append(
                        {"key": r["key"], "column": col, "tsv": r[col], "xml": got[col]})
    return res


def ensure_dtd(dtd, out_dir):
    dest = out_dir / "dblp.dtd"
    if dest.exists():
        if sha256_file(dest) != sha256_file(dtd):
            raise ValueError(f"{dest} exists and differs from {dtd}; not overwriting")
        return dest, False
    tmp = out_dir / ".dblp.dtd.tmp"
    tmp.write_bytes(Path(dtd).read_bytes())
    os.replace(tmp, dest)
    return dest, True


def write_atomic(path, data):
    tmp = path.with_name("." + path.name + ".tmp")
    try:
        with open(tmp, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def up_to_date(out, prov_path, sel_sha, dtd_sha, xml_bytes):
    if not (out.exists() and prov_path.exists()):
        return False
    try:
        p = json.loads(prov_path.read_text())
    except ValueError:
        return False
    return (p.get("output_sha256") == sha256_file(out) and p.get("selection_sha256") == sel_sha
            and p.get("dtd_sha256") == dtd_sha and p.get("source_xml_bytes") == xml_bytes
            and p.get("script_version") == SCRIPT_VERSION)


def main(argv=None, stdout=sys.stdout):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--xml", default=str(DEFAULT_XML))
    ap.add_argument("--dtd", default=str(DEFAULT_DTD))
    ap.add_argument("--selection", default=str(DEFAULT_SELECTION))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--expected-xml-sha256", default=EXPECTED_XML_SHA256,
                    help="manifest value compared with the observed source hash (default: manifest)")
    a = ap.parse_args(argv)

    def say(*x):
        print(*x, file=stdout)

    xml, dtd, sel, out = (Path(a.xml), Path(a.dtd), Path(a.selection), Path(a.out))
    for p in (xml, dtd, sel):
        if not p.is_file():
            print(f"ERROR: input not found: {p}", file=sys.stderr)
            return 2
    prov_path = out.with_name(out.name + ".provenance.json")
    rows = read_selection(sel)
    keys = [r["key"] for r in rows]
    if len(set(keys)) != len(keys):
        print("ERROR: duplicate keys in selection", file=sys.stderr)
        return 2
    sel_sha, dtd_sha = sha256_file(sel), sha256_file(dtd)
    xml_bytes = xml.stat().st_size
    if up_to_date(out, prov_path, sel_sha, dtd_sha, xml_bytes):
        say(f"up to date: {rel(out)} (sha256 {sha256_file(out)}); nothing rewritten")
        return 0

    dtd_dest = out.parent / "dblp.dtd"
    if dtd_dest.exists() and sha256_file(dtd_dest) != dtd_sha:
        print(f"ERROR: {dtd_dest} exists and differs from {dtd}; not overwriting", file=sys.stderr)
        return 2
    t0 = time.time()
    found, dups, xml_sha, xml_total = scan(xml, set(keys))
    scan_s = time.time() - t0
    match = xml_sha == a.expected_xml_sha256
    say(f"source sha256 {xml_sha} ({'MATCHES' if match else 'MISMATCH with'} expected "
        f"{a.expected_xml_sha256}); bytes {xml_total}; scan {scan_s:.1f} s")
    if xml_total != xml_bytes:
        print("ERROR: source size changed during the scan; nothing written", file=sys.stderr)
        return 2
    if not match:
        print("ERROR: source hash differs from the manifest; nothing written", file=sys.stderr)
        return 2
    # every input check has passed; the first write happens here
    out.parent.mkdir(parents=True, exist_ok=True)
    dtd_copy, _ = ensure_dtd(dtd, out.parent)
    data = build_output(rows, found)
    write_atomic(out, data)
    out_sha = hashlib.sha256(data).hexdigest()
    val = validate_output(out, rows)
    missing = [k for k in keys if k not in found]
    prov = {
        "artifact": rel(out),
        "script": "datasets/scripts/dblp_extract_subset.py",
        "script_version": SCRIPT_VERSION,
        "code_version": code_version(),
        "parameters": {"xml": rel(xml), "dtd": rel(dtd), "selection": rel(sel), "out": rel(out),
                       "expected_xml_sha256": a.expected_xml_sha256,
                       "match": "exact key attribute on a top-level publication element",
                       "order": "selection TSV order", "chunk_bytes": CHUNK},
        "source_xml_path": rel(xml),
        "source_xml_bytes": xml_total,
        "source_xml_sha256": xml_sha,
        "source_xml_sha256_matches_manifest": match,
        "source_xml_sha256_computed": "in the same single sequential pass as the extraction",
        "dtd_path": rel(dtd),
        "dtd_sha256": dtd_sha,
        "dtd_copy_next_to_output": rel(dtd_copy),
        "selection_path": rel(sel),
        "selection_sha256": sel_sha,
        "output_bytes": len(data),
        "output_sha256": out_sha,
        "record_count": len(found),
        "records_expected": len(keys),
        "missing_keys": missing,
        "duplicate_keys_in_source": dups,
        "validation": val,
    }
    write_atomic(prov_path, (json.dumps(prov, indent=2, sort_keys=True) + "\n").encode())
    say(f"output {rel(out)} bytes {len(data)} sha256 {out_sha}")
    say(f"records found {len(found)} of {len(keys)}; missing {len(missing)}: {missing}")
    say(f"duplicate keys in source: {dups}")
    say("validation: " + json.dumps(val, sort_keys=True))
    say(f"provenance {rel(prov_path)}")
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main())
