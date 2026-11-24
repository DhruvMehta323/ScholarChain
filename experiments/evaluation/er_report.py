#!/usr/bin/env python3
"""Evaluation group 1 (entity resolution): re-tabulate the COMMITTED Phase 05 held-out results. Reads only; computes nothing new.

Usage: python3 -B experiments/evaluation/er_report.py [--eval modules/entity-resolution/evaluation/er-v1-heldout-eval.json]
           [--split modules/entity-resolution/gold/gold-split-v1.summary.json] [--out-dir experiments/evaluation/output] [--force]
Every number in the output is copied from the two committed files (confusion matrix per kind, precision, recall, F1, coverage, unresolved and
conflict rates; gold split counts). The output records the SHA-256 of both inputs, the policy version and the code version they carry. It does not
re-run entity resolution and does not tune anything. The instructor's gold-set requirement (at least 170 reviewed pairs, 60/40 into 102
calibration and 68 held-out) is printed NEXT TO the observed split as a reading aid; the comparison is for the human and report author to judge.
Exit codes: 0 ok, 2 input missing, 3 refused to overwrite.
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "common"))
import sc_common  # noqa: E402

ROOT = sc_common.repo_root()


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--eval", default=os.path.join(ROOT, "modules", "entity-resolution", "evaluation", "er-v1-heldout-eval.json"))
    ap.add_argument("--split", default=os.path.join(ROOT, "modules", "entity-resolution", "gold", "gold-split-v1.summary.json"))
    ap.add_argument("--out-dir", default=sc_common.derived_root("evaluation") or os.path.join(HERE, "output"))
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args(argv)
    for p in (a.eval, a.split):
        if not os.path.isfile(p):
            print("SC-REFUSED input missing: %s" % p, file=sys.stderr)
            return 2
    ev, sp = sc_common.read_json(a.eval), sc_common.read_json(a.split)
    rows = []
    for kind, v in sorted(ev["perKind"].items()):
        c, k = v["confusion"], v["counts"]
        rows.append([kind, c["n"], c["tp"], c["fp"], c["fn"], c["tn"], c["precision"], c["recall"], c["f1"], v["coverage"], v["unresolvedRate"], v["conflictRate"],
                     k.get("accepted"), k.get("rejected"), k.get("unresolved"), k.get("conflict")])
    split_rows = [[x["kind"], x["label"], x["split"], x["count"]] for x in sp["counts"]]
    prov = {"eval_file": os.path.relpath(a.eval, ROOT), "eval_sha256": sc_common.sha256_file(a.eval), "split_file": os.path.relpath(a.split, ROOT),
            "split_sha256": sc_common.sha256_file(a.split), "policy": ev["policyVersion"], "feature_set": ev["featureSetVersion"], "code_version": ev["codeVersion"],
            "thresholds_origin": ev["thresholdsOrigin"], "llm_used": ev["llmUsed"], "held_out_rows": ev["heldOutRows"], "split_rows_total": sp["rows"],
            "split_calibration": sp["calibration"], "split_heldout": sp["heldout"], "split_excluded_unsure": sp["excludedUnsure"], "split_seed": sp["seed"],
            "instructor_requirement": "at least 170 reviewed pairs; 60/40 into 102 calibration and 68 held-out; both with positives and hard negatives (docs/agent-runs/instructor-approvals.md item 3)",
            "source": "committed Phase 05 results, copied unchanged", "synthetic": False}
    try:
        import csv
        p1 = os.path.join(a.out_dir, "er-heldout-confusion.csv")
        p2 = os.path.join(a.out_dir, "er-gold-split-counts.csv")
        p3 = os.path.join(a.out_dir, "er-report-provenance.json")
        for p in (p1, p2, p3):
            sc_common.check_free(p, a.force)
        with sc_common.open_output(p1, force=a.force, newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["kind", "n", "tp", "fp", "fn", "tn", "precision", "recall", "f1", "coverage", "unresolved_rate", "conflict_rate", "accepted", "rejected", "unresolved", "conflict"])
            w.writerows(rows)
        with sc_common.open_output(p2, force=a.force, newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["kind", "label", "split", "count"])
            w.writerows(split_rows)
        if a.force and os.path.exists(p3):
            os.remove(p3)
        sc_common.write_json_new(p3, prov)
        for out in (p1, p2):  # p3 is itself the provenance record of the copied numbers
            sc_common.write_provenance(out, __file__, argv, [a.eval, a.split], False, extra=vars(a), force=a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    for r in rows:
        print("SC-ROW kind=%s n=%s tp=%s fp=%s fn=%s tn=%s precision=%.4f recall=%.4f f1=%.4f" % tuple(r[:9]))
    print("SC-SPLIT total=%s calibration=%s heldout=%s excluded_unsure=%s (observed, from %s)" % (sp["rows"], sp["calibration"], sp["heldout"], sp["excludedUnsure"], prov["split_file"]))
    print("SC-REQ  %s" % prov["instructor_requirement"])
    print("SC-WROTE %s %s %s" % (p1, p2, p3))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
