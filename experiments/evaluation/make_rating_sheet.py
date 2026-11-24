#!/usr/bin/env python3
"""Evaluation group 4 (answer quality), human part: create an EMPTY human rating sheet, one row per answered query in the raw CSVs.

Usage: python3 -B experiments/evaluation/make_rating_sheet.py RAW.csv [RAW.csv ...] [--out-dir DIR] [--sample N] [--seed 441] [--force]
The sheet lists question_id, category, query_id, mode and trace_id (taken from the raw rows) and leaves EVERY rating column empty for a human:
correctness_1_to_5, groundedness_1_to_5, completeness_1_to_5, unsupported_claims_count, notes, rater, rated_at_utc. The script never writes a rating.
--sample N takes a seeded random sample of N answered queries (default all). A SYNTHETIC input produces a SYNTHETIC- sheet.
The rubric the human applies is in experiments/evaluation/README.md (group 4); it must be agreed before rating.
Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import csv
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "analysis"))
import an_common as ac  # noqa: E402
import sc_common  # noqa: E402

COLS = ["question_id", "category", "query_id", "mode", "trace_id", "correctness_1_to_5", "groundedness_1_to_5", "completeness_1_to_5",
        "unsupported_claims_count", "notes", "rater", "rated_at_utc"]


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.set_defaults(out_dir=sc_common.derived_root("evaluation") or os.path.join(HERE, "output"))
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--sample", type=int)
    ap.add_argument("--seed", type=int, default=441)
    a = ap.parse_args(argv)
    rows, synthetic = [], False
    for p in a.inputs:
        rs = ac.read_csv_rows(p)
        synthetic = synthetic or ac.input_is_synthetic(p, rs)
        rows += [r for r in rs if r["outcome"] == "ok" and r.get("query_id")]
    if a.sample is not None and a.sample < len(rows):
        rows = random.Random(a.seed).sample(rows, a.sample)
    if not rows:
        print("SC-REFUSED no answered rows", file=sys.stderr)
        return 2
    path = os.path.join(a.out_dir, sc_common.out_name("human-rating-sheet.csv", synthetic))
    try:
        sc_common.check_free(path, a.force)
        with sc_common.open_output(path, force=a.force, newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(COLS)
            for r in rows:
                w.writerow([r["question_id"], r["category"], r["query_id"], r["mode"], r["trace_id"]] + [""] * 7)
        sc_common.write_provenance(path, __file__, argv, a.inputs, synthetic, extra=vars(a), force=a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    print("SC-SHEET %s rows=%d (all rating columns empty) synthetic=%s" % (path, len(rows), str(synthetic).lower()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
