#!/usr/bin/env python3
"""Evaluation group 7 (reproducibility) and the immutability gate: recompute SHA-256 of every raw file listed in the run metadata and compare.

Usage: python3 -B experiments/evaluation/verify_raw_integrity.py [--results experiments/results] [--out-dir DIR] [--force]
Scans <results>/**/*.meta.json. Load generator meta files list csv + csv_sha256; collector meta files list files[{file, sha256}]. For each listed file
the script prints OK (hash equal), MISMATCH (the file changed after it was written: an immutability violation, exit 1) or MISSING (listed file gone: exit 1).
Files that no meta file lists are reported as UNLISTED (informational: samples written by an interrupted run have no meta). It also prints, per meta file, the
fields a rerun needs: commit, manifest, manifest_sha256, seed, benchmark sha256 (load generator) so two runs can be compared by eye or by the template
experiments/evaluation/reproducibility_template.csv. It never writes under <results>.
Output: raw-integrity.csv (one row per listed file). Exit codes: 0 all OK, 1 mismatch or missing, 2 nothing found, 3 refused to overwrite.
"""
import argparse
import csv
import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "common"))
import sc_common  # noqa: E402

ROOT = sc_common.repo_root()


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--results", default=sc_common.results_root(), help="raw results root (default: $SC_RESULTS_ROOT or experiments/results)")
    ap.add_argument("--out-dir", default=sc_common.derived_root("evaluation") or os.path.join(HERE, "output"))
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args(argv)
    metas = sorted(glob.glob(os.path.join(a.results, "**", "*.meta.json"), recursive=True))
    if not metas:
        print("SC-REFUSED no .meta.json files under %s" % a.results, file=sys.stderr)
        return 2
    rows, listed, bad = [], set(), 0
    for m in metas:
        j = sc_common.read_json(m)
        d = os.path.dirname(m)
        items = []
        if "csv" in j and "csv_sha256" in j:
            items.append((j["csv"], j["csv_sha256"]))
        items += [(x["file"], x["sha256"]) for x in j.get("files", [])]
        for name, want in items:
            p = os.path.join(d, name)
            listed.add(os.path.realpath(p))
            if not os.path.exists(p):
                status, got, bad = "MISSING", "", bad + 1
            else:
                got = sc_common.sha256_file(p)
                status = "OK" if got == want else "MISMATCH"
                bad += status != "OK"
            rows.append([os.path.relpath(m, a.results), name, status, want, got, j.get("label", ""), j.get("commit", ""), j.get("manifest_sha256", ""), j.get("seed", "")])
            print("SC-%s %s (%s)" % (status, os.path.relpath(p, a.results), j.get("label", "")))
    for p in sorted(glob.glob(os.path.join(a.results, "**", "*"), recursive=True)):
        if os.path.isfile(p) and not p.endswith(".meta.json") and os.path.realpath(p) not in listed and "ledger" not in os.path.basename(p):
            print("SC-UNLISTED %s (no meta file lists it)" % os.path.relpath(p, a.results))
    try:
        path = os.path.join(a.out_dir, "raw-integrity.csv")
        sc_common.check_free(path, a.force)
        with sc_common.open_output(path, force=a.force, newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["meta_file", "file", "status", "sha256_recorded", "sha256_now", "label", "commit", "manifest_sha256", "seed"])
            w.writerows(rows)
        sc_common.write_provenance(path, __file__, argv, metas + sorted(listed), False, extra=vars(a), force=a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    print("SC-RESULT files=%d problems=%d" % (len(rows), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
