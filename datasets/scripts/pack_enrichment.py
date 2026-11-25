#!/usr/bin/env python3
"""Pack the dblp-10k enrichment envelopes (Crossref, OpenAlex, ORCID) into a few large JSONL files.

SCRIPT_VERSION 1.0.0 (Phase 14, C4). Offline and read-only on datasets/raw/{crossref,openalex,orcid}.
Membership is the scale's own definition from scale_enrichment_stats.py (selection TSV DOIs, then
fo.derive_ids for ORCID), reused by import, not redefined. A member envelope that is missing or not
valid JSON is an error (exit 1).

Output (default datasets/raw/dblp/derived/enrichment-10k/):
  {crossref,openalex,orcid}/<source>-10k-NNNNN.envelopes.jsonl   one compact JSON object per line,
      envelope key order kept, lines sorted by the original envelope file name, roll at ~64 MB
  checksums.sha256, provenance.json                              top level only
Overwrite rule: if the output directory exists and is non-empty the script exits 2 and writes nothing,
unless --verify is given: then it repacks into a temporary directory and exits 0 only when every packed
file, checksums.sha256 and the cache digests are byte-identical to the existing ones.

Usage: python3 -I -B datasets/scripts/pack_enrichment.py [--out-dir DIR] [--verify]
"""
import argparse
import hashlib
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I does not add the script dir
import fetch_common as fc  # noqa: E402
import fetch_orcid as fo  # noqa: E402
import scale_enrichment_stats as sse  # noqa: E402

SCRIPT_VERSION = "1.0.0"
SCALE = 10000
ROLL_BYTES = 64 * 1024 * 1024
SOURCES = ("crossref", "openalex", "orcid")
DEFAULT_OUT = sse.DER / "enrichment-10k"


def members():
    """{source: [(envelope path, identifier)]} for scale dblp-10k, using sse's definitions."""
    tsv = sse.DER / f"dblp_selection_{SCALE}.tsv"
    dois, seen = [], set()
    for r in sse.read_tsv(tsv):
        d = fc.normalize_doi(r["doi"]) if r["doi"] else None
        if d and d not in seen:
            seen.add(d)
            dois.append(d)
    out = {}
    for src in ("crossref", "openalex"):
        out[src] = [(fc.envelope_path(sse.RAW / src / "works", d), d) for d in dois]
    ids = fo.derive_ids(sse.RAW / "crossref" / "works", sse.RAW / "openalex" / "works", dois)
    # ORCID is capped retrieval: members are the derived iDs that have a cached envelope (as in sse.analyze)
    out["orcid"] = [(p, i) for i in sorted(ids) for p in [fc.envelope_path(sse.RAW / "orcid" / "works", i)] if p.exists()]
    return tsv, out


def pack(out_dir):
    tsv, mem = members()
    files, counts, digests = [], {}, {}
    for src in SOURCES:
        entries = []
        for p, ident in mem[src]:
            if not p.exists():
                raise SystemExit(f"FAIL: missing {src} envelope for member {ident!r} ({p.name})")
            entries.append(p)
        entries.sort(key=lambda p: p.name)
        pairs, n, idx, fh, size = [], 0, 0, None, 0
        (out_dir / src).mkdir(parents=True)
        for p in entries:
            raw = p.read_bytes()
            try:
                obj = json.loads(raw.decode("utf-8"))
            except ValueError as e:
                raise SystemExit(f"FAIL: {src} envelope {p.name} is not valid JSON: {e}")
            line = (json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
            if fh is None or (size and size + len(line) > ROLL_BYTES):
                if fh:
                    fh.close()
                idx += 1
                rel = f"{src}/{src}-10k-{idx:05d}.envelopes.jsonl"
                fh, size = open(out_dir / rel, "wb"), 0
                files.append(rel)
            fh.write(line)
            size += len(line)
            n += 1
            pairs.append((p.name, hashlib.sha256(raw).hexdigest()))
        if fh:
            fh.close()
        counts[src], digests[src] = n, sse.digest(pairs)
    info = [{"path": f, "sha256": fc.sha256_file(out_dir / f), "bytes": (out_dir / f).stat().st_size} for f in files]
    (out_dir / "checksums.sha256").write_text("".join(f"{i['sha256']}  {i['path']}\n" for i in info), encoding="utf-8")
    prov = {"script": "datasets/scripts/pack_enrichment.py", "script_version": SCRIPT_VERSION,
            "code_version": fc.code_version(), "scale": "dblp-10k", "network": "none",
            "input_selection_tsv": {"path": str(tsv.relative_to(fc.REPO)), "sha256": fc.sha256_file(tsv)},
            "counts": counts, "cache_digests_sha256": digests,
            "digest_definition": "scale_enrichment_stats.py digest()",
            "format": "UTF-8 JSONL, compact separators, ensure_ascii=False, envelope key order, sorted by envelope file name, roll at 64 MiB",
            "files": info}
    (out_dir / "provenance.json").write_text(json.dumps(prov, indent=1) + "\n", encoding="utf-8")
    return prov


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--verify", action="store_true")
    a = ap.parse_args(argv)
    exists = a.out_dir.exists() and any(a.out_dir.iterdir())
    if exists and not a.verify:
        print(f"REFUSED: {a.out_dir} exists and is non-empty (use --verify to compare)", file=sys.stderr)
        return 2
    if a.verify and not exists:
        print("FAIL: nothing to verify", file=sys.stderr)
        return 1
    if not a.verify:
        stage = a.out_dir.with_name(a.out_dir.name + ".partial")  # never leaves a half-written output dir
        if stage.exists():
            print(f"REFUSED: stale {stage}; a human must remove it", file=sys.stderr)
            return 2
        stage.mkdir(parents=True)
        prov = pack(stage)
        if a.out_dir.exists():
            a.out_dir.rmdir()  # only an empty directory can be here
        stage.rename(a.out_dir)
    else:
        with tempfile.TemporaryDirectory() as t:
            tmp = Path(t) / "e"
            tmp.mkdir()
            prov = pack(tmp)
            names = ["checksums.sha256"] + [f["path"] for f in prov["files"]]
            have = sorted(str(p.relative_to(a.out_dir)) for p in a.out_dir.rglob("*") if p.is_file())
            if have != sorted(names + ["provenance.json"]):
                print("VERIFY FAIL: file set differs", file=sys.stderr)
                return 1
            for n in names:
                if (tmp / n).read_bytes() != (a.out_dir / n).read_bytes():
                    print(f"VERIFY FAIL: {n} differs", file=sys.stderr)
                    return 1
        print("VERIFY OK: repack is byte-identical (provenance.json compared by content below)")
    for s in SOURCES:
        print(s, "count", prov["counts"][s], "digest", prov["cache_digests_sha256"][s])
    for f in prov["files"]:
        print(f["path"], f["bytes"], f["sha256"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
