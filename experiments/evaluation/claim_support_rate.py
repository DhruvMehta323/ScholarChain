#!/usr/bin/env python3
"""Evaluation group 4 (answer quality), automatic part: claim support rate, abstention rate and failure rate from load generator raw CSVs.

Usage: python3 -B experiments/evaluation/claim_support_rate.py RAW.csv [RAW.csv ...] [--out-dir DIR] [--force]
Definitions (counts, no model judgment):
  answered           rows with outcome ok (HTTP 200 synchronous answers)
  claims_total       sum of claims_count over answered rows; claims_verified = sum of claims_verified (the service's own verificationStatus VERIFIED)
  claim_support_rate claims_verified / claims_total (empty when claims_total is 0). It measures the SERVICE's verifier verdicts, not truth:
                     human correctness and groundedness ratings come from the rating sheet (make_rating_sheet.py), never from this script.
  abstain_rate       answered rows with abstained=true / answered rows
  failure_rate       rows whose outcome is not ok or async / all rows (failed requests are counted, never dropped)
Rows from older raw files that lack the claims_verified column leave claim_support_rate empty (reported as "column absent").
Output: claim-support.csv (per mode and ALL). SYNTHETIC input gives a SYNTHETIC- output. Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "analysis"))
import an_common as ac  # noqa: E402
import sc_common  # noqa: E402


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.set_defaults(out_dir=sc_common.derived_root("evaluation") or os.path.join(HERE, "output"))
    ap.add_argument("inputs", nargs="+")
    a = ap.parse_args(argv)
    ac.begin(__file__, argv, a.inputs, extra=vars(a))
    rows, synthetic, has_col = [], False, True
    for p in a.inputs:
        rs = ac.read_csv_rows(p)
        synthetic = synthetic or ac.input_is_synthetic(p, rs)
        has_col = has_col and bool(rs) and "claims_verified" in rs[0]
        rows += rs
    if not rows:
        print("SC-REFUSED no rows", file=sys.stderr)
        return 2
    groups = defaultdict(list)
    for r in rows:
        groups[r["mode"]].append(r)
        groups["ALL"].append(r)
    out = []
    for mode, rs in sorted(groups.items()):
        ans = [r for r in rs if r["outcome"] == "ok"]
        claims = sum(int(r["claims_count"]) for r in ans if r.get("claims_count", "") != "")
        ver = sum(int(r["claims_verified"]) for r in ans if r.get("claims_verified", "") != "") if has_col else None
        absn = sum(1 for r in ans if r.get("abstained") == "true")
        failed = sum(1 for r in rs if r["outcome"] not in ("ok", "async"))
        rate = (ver / float(claims)) if ver is not None and claims else None
        out.append([mode, len(rs), len(ans), failed, ac.fmt(failed / float(len(rs)), 4), claims, ver if ver is not None else "column absent",
                    ac.fmt(rate, 4), ac.fmt(absn / float(len(ans)), 4) if ans else ""])
        print("SC-ROW mode=%s rows=%d answered=%d failed=%d claims=%d verified=%s support_rate=%s abstain_rate=%s" %
              (mode, len(rs), len(ans), failed, claims, out[-1][6], out[-1][7], out[-1][8]))
    try:
        ac.write_table(a.out_dir, "claim-support.csv", ["mode", "rows", "answered", "failed", "failure_rate", "claims_total", "claims_verified", "claim_support_rate", "abstain_rate"],
                       out, synthetic, a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
