#!/usr/bin/env python3
"""Retrieval-mode comparison: recall@K, MRR and nDCG@K per mode (and per category) from load generator raw CSVs and the benchmark labels.

Usage: python3 -B experiments/analysis/retrieval_metrics.py RAW.csv [RAW.csv ...] --benchmark FILE [--k 1,3,5,10] [--out-dir DIR] [--force]
Labels: for each benchmark question the RELEVANT set is the set of `subject` values of expected.facts entries whose kind is "identity" (these
are researcher ids in benchmark-v1). A question with no such subject (for example an abstention or a path-only question) is NOT SCORED and is
counted in n_unlabeled. Ranked list: the returned_researcher_ids column (";" separated, answer order) of the question's rows. Metric
definitions are in metrics.py (binary gain, nDCG with log2(rank+1) discount, ideal list of min(|relevant|, K) hits).
Two scores per mode: STRICT counts a request without a usable answer (non-2xx, timeout) as 0 for every metric; ANSWERED averages only
the requests that were answered (2xx). The difference is the failure effect; both n values are printed. When a question appears in several
rows of a mode, every row is scored (repeats are separate observations).
CAVEAT carried into the output: returned_researcher_ids are the researchers of the final ANSWER, not the raw retrieval list, so these are
answer-level retrieval scores for the subject set; a graph-only or vector-only comparison needs those modes to be accepted by the service
(modules/services/API.md on main refuses them with 422 today: those rows are counted as failures here).
Output: retrieval-metrics.csv (rows: mode, category ALL and per category). Exit codes: 0 ok, 2 usage, 3 refused to overwrite.
"""
import argparse
import sys
from collections import defaultdict

import an_common as ac
import sc_common
from metrics import mean, ndcg_at_k, recall_at_k, reciprocal_rank


def relevant_sets(bench):
    out = {}
    for q in bench["questions"]:
        subs = [f["subject"] for f in (q.get("expected") or {}).get("facts", []) if f.get("kind") == "identity" and isinstance(f.get("subject"), str)]
        out[str(q["id"])] = sorted(set(subs))
    return out


def score(rows, rel, ks):
    """Return {(mode, category): metrics dict}. category 'ALL' aggregates."""
    acc = defaultdict(lambda: {"strict": defaultdict(list), "answered": defaultdict(list), "n_rows": 0, "n_unlabeled": 0, "n_failed": 0})
    for r in rows:
        relevant = rel.get(r["question_id"], [])
        for cat in ("ALL", r["category"]):
            a = acc[(r["mode"], cat)]
            a["n_rows"] += 1
            if not relevant:
                a["n_unlabeled"] += 1
                continue
            answered = r["outcome"] in ("ok", "async")
            ranked = [x for x in r["returned_researcher_ids"].split(";") if x] if answered else []
            vals = {"mrr": reciprocal_rank(ranked, relevant)}
            for k in ks:
                vals["recall@%d" % k] = recall_at_k(ranked, relevant, k)
                vals["ndcg@%d" % k] = ndcg_at_k(ranked, relevant, k)
            for name, v in vals.items():
                a["strict"][name].append(v)
                if answered:
                    a["answered"][name].append(v)
            if not answered:
                a["n_failed"] += 1
    return acc


def main(argv):
    ap = ac.common_args(argparse.ArgumentParser(description=__doc__.split("\n")[0]))
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--k", default="1,3,5,10")
    a = ap.parse_args(argv)
    ac.begin(__file__, argv, a.inputs, benchmark=a.benchmark, extra=vars(a))
    ks = [int(x) for x in a.k.split(",")]
    bench = sc_common.read_json(a.benchmark)
    rows, synthetic = [], bool(bench.get("synthetic"))
    for p in a.inputs:
        rs = ac.read_csv_rows(p)
        synthetic = synthetic or ac.input_is_synthetic(p, rs)
        rows += rs
    if not rows:
        print("SC-REFUSED no rows", file=sys.stderr)
        return 2
    acc = score(rows, relevant_sets(bench), ks)
    names = ["mrr"] + ["recall@%d" % k for k in ks] + ["ndcg@%d" % k for k in ks]
    header = ["mode", "category", "n_rows", "n_unlabeled", "n_scored_strict", "n_failed", "n_scored_answered"]
    header += ["strict_" + n for n in names] + ["answered_" + n for n in names]
    out = []
    for (mode, cat), v in sorted(acc.items()):
        row = [mode, cat, v["n_rows"], v["n_unlabeled"], len(v["strict"]["mrr"]), v["n_failed"], len(v["answered"]["mrr"])]
        row += [ac.fmt(mean(v["strict"][n]), 4) for n in names] + [ac.fmt(mean(v["answered"][n]), 4) for n in names]
        out.append(row)
        if cat == "ALL":
            print("SC-ROW mode=%s n_scored=%d failed=%d strict_mrr=%s answered_mrr=%s strict_recall@%d=%s" %
                  (mode, len(v["strict"]["mrr"]), v["n_failed"], ac.fmt(mean(v["strict"]["mrr"]), 4), ac.fmt(mean(v["answered"]["mrr"]), 4), ks[0],
                   ac.fmt(mean(v["strict"]["recall@%d" % ks[0]]), 4)))
    try:
        ac.write_table(a.out_dir, "retrieval-metrics.csv", header, out, synthetic, a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
