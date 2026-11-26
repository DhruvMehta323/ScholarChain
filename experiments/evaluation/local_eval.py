#!/usr/bin/env python3
"""Phase 16a local evaluation runner (groups 1 to 7, no AWS, no network, no sbt). Python standard library plus the committed experiments helpers.

Usage: python3 -I -B experiments/evaluation/local_eval.py GROUP [--run-id r1] [--out-root experiments/results/local]
           [--main-checkout PATH] [--seed 160101] [--sample 25]
GROUP is one of: g1 g2 g3 g4 g5 g6 g7. Every group writes only new files under <out-root>/<group-dir>/<run-id>/ (exclusive create: an existing
file is never overwritten; rerun with a new --run-id). Every group also writes meta.json (inputs with SHA-256, script SHA-256, git head, parameters).

Evidence classes: L = local unit/contract test records cited, F = fixture demo, R = local real-data run (1K DBLP selection), A = AWS; C-DOC = census of committed documents (group 6, not a run).
This script produces L, F and R only. Nothing here recomputes entity resolution, Spark, storage or the benchmark: it re-reads committed files and
local run outputs that already exist on disk (read-only) and tabulates, cross-checks or audits them.

Inputs outside the worktree (read-only): the main checkout's untracked outputs (modules/*/target/...). --main-checkout defaults to ../ScholarChain.
A missing input is written as NOT AVAILABLE in the output; it is never regenerated.
Exit codes: 0 ok, 2 input missing or usage, 3 refused to overwrite.
"""
import argparse
import csv
import glob
import gzip
import hashlib
import json
import math
import os
import random
import re
import subprocess
import sys
from collections import Counter, defaultdict, deque

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "common"))
sys.path.insert(0, os.path.join(HERE, "..", "analysis"))
import sc_common  # noqa: E402
from metrics import ndcg_at_k, recall_at_k, reciprocal_rank  # noqa: E402

ROOT = sc_common.repo_root()
GROUP_DIRS = {"g1": "g1-entity-resolution", "g2": "g2-graph-correctness", "g3": "g3-retrieval", "g4": "g4-answer-quality",
              "g5": "g5-performance-elasticity", "g6": "g6-agentic-factory", "g7": "g7-reproducibility"}
PENDING_AWS = "[[PENDING-AWS]]"
PENDING_EVAL = "[[PENDING-EVAL]]"
NA = "NOT AVAILABLE"


class Ctx:
    def __init__(self, a):
        self.run_id = a.run_id
        self.seed = a.seed
        self.sample = a.sample
        self.main = os.path.realpath(a.main_checkout)
        self.out = os.path.join(os.path.abspath(a.out_root), GROUP_DIRS[a.group], a.run_id)
        self.inputs = []
        self.notes = []
        self.na = []
        self.written = []

    def use(self, path):
        """Register an input file (sha256 recorded). Returns path, or None when missing (recorded as NOT AVAILABLE)."""
        if os.path.isfile(path):
            rec = sc_common.input_record(path)
            rec["path"] = path if not path.startswith(ROOT + os.sep) else os.path.relpath(path, ROOT)
            self.inputs.append(rec)
            return path
        self.na.append(path)
        return None

    def path(self, name):
        return os.path.join(self.out, name)

    def write_json(self, name, obj):
        self.written.append(name)
        sc_common.write_json_new(self.path(name), obj)

    def write_csv(self, name, header, rows):
        self.written.append(name)
        with sc_common.open_new(self.path(name), newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(header)
            w.writerows(rows)

    def write_text(self, name, text):
        self.written.append(name)
        with sc_common.open_new(self.path(name), newline="\n") as f:
            f.write(text)

    def finish(self, group, evidence_class, params, overrides=None):
        """evidence_class is the single class of every output file of the group, except files named in overrides ({file name: class})."""
        head, dirty = sc_common.git_state()
        fcls = {n: (overrides or {}).get(n, evidence_class) for n in self.written}
        self.write_json("meta.json", {"group": group, "evidence_class": evidence_class if not overrides else "per file: see file_evidence_class", "file_evidence_class": fcls, "run_id": self.run_id, "script": "experiments/evaluation/local_eval.py",
                                      "script_sha256": sc_common.sha256_file(os.path.abspath(__file__)), "git_head": head, "tree_dirty": dirty,
                                      "generated_at_utc": sc_common.utc_iso(), "python": sys.version.split()[0], "parameters": params, "inputs": self.inputs,
                                      "not_available_inputs": self.na, "notes": self.notes, "synthetic": False})


def jl(path):
    with open(path, "r", encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


def csv_rows(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def f4(x):
    return "" if x is None else ("%.4f" % x)


def frac(n, d):
    return None if not d else n / float(d)


# ---------------------------------------------------------------------------------------------------------------- group 1
def flatten(prefix, obj, out):
    if isinstance(obj, dict):
        for k in sorted(obj):
            flatten(prefix + "." + k if prefix else k, obj[k], out)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            flatten("%s[%d]" % (prefix, i), v, out)
    else:
        out[prefix] = obj


def g1(c):
    ev_p = c.use(os.path.join(ROOT, "modules/entity-resolution/evaluation/er-v1-heldout-eval.json"))
    rs_p = c.use(os.path.join(ROOT, "modules/entity-resolution/evaluation/er-v1-results.json"))
    md_p = c.use(os.path.join(ROOT, "modules/entity-resolution/evaluation/er-v1-results.md"))
    sp_p = c.use(os.path.join(ROOT, "modules/entity-resolution/gold/gold-split-v1.summary.json"))
    gate_p = c.use(os.path.join(ROOT, "docs/agent-runs/phase-05-entity-resolution/human-gate.md"))
    if not (ev_p and rs_p and md_p and sp_p):
        return 2
    ev, rs, sp = sc_common.read_json(ev_p), sc_common.read_json(rs_p), sc_common.read_json(sp_p)
    rows, by_path = [], []
    NM = "not measurable"  # Phase 05 marks null cells this way (er-v1-results.json heldout *.confusion precision/recall/f1 = null with a note or status; human-gate.md table)

    def nm_(x):
        return NM if x is None else x
    for kind in ("researcher", "work"):
        v = ev["perKind"][kind]
        cf, k = v["confusion"], v["counts"]
        rcf = rs["heldout"][kind]["confusion"]  # committed Phase 05 results: null where Phase 05 says not measurable
        n = cf["n"]
        spec = frac(cf["tn"], cf["tn"] + cf["fp"])
        rows.append([kind, n, cf["tp"], cf["fp"], cf["fn"], cf["tn"], nm_(rcf["precision"]), nm_(rcf["recall"]), NM if spec is None else f4(spec), nm_(rcf["f1"]), v["coverage"], v["unresolvedRate"],
                     v["conflictRate"], k["accepted"], k["rejected"], k["unresolved"], k["conflict"]])
        for path in ("exact_id", "deterministic", "probabilistic", "llm"):
            b = v["byPath"][path]
            cc = b["confusion"]
            rc = rs["heldout"][kind]["byPath"][path]["confusion"]
            st = rc.get("status") or rc.get("precisionNote") or ""
            by_path.append([kind, path, b["n"], cc["tp"], cc["fp"], cc["fn"], cc["tn"], st, nm_(rc["precision"]), nm_(rc["recall"]), nm_(rc["f1"])])
    c.write_csv("er-heldout-metrics.csv", ["kind", "n", "tp", "fp", "fn", "tn", "precision", "recall", "specificity_derived", "f1", "coverage", "unresolved_rate",
                                          "conflict_rate", "accepted", "rejected", "unresolved", "conflict"], rows)
    c.write_csv("er-by-path.csv", ["kind", "path", "n", "tp", "fp", "fn", "tn", "phase05_note_or_status", "precision", "recall", "f1"], by_path)
    # Cross-check: every figure in er-v1-heldout-eval.json (perKind) against er-v1-results.json (heldout), key by key.
    a, b = {}, {}
    flatten("", ev["perKind"], a)
    flatten("", rs["heldout"], b)
    diffs = []
    for key in sorted(set(a) | set(b)):
        if key not in a:
            diffs.append([key, "ABSENT", repr(b[key])])
        elif key not in b:
            diffs.append([key, repr(a[key]), "ABSENT"])
        elif a[key] != b[key]:
            diffs.append([key, repr(a[key]), repr(b[key])])
    c.write_csv("crosscheck-eval-vs-results-json.csv", ["json_path", "heldout_eval_json", "results_json_heldout"], diffs)
    # Cross-check against the committed markdown: parse the researcher and work metric rows and byPath rows.
    md = open(md_p, encoding="utf-8").read()
    md_diffs, md_checked = [], 0
    sect = {"researcher": md.split("### researcher")[1].split("### work")[0], "work": md.split("### work")[1].split("## Final decisions")[0]}
    for kind, text in sect.items():
        cf = ev["perKind"][kind]["confusion"]
        m = re.search(r"TP (\d+) \| FN (\d+) \|\n\| actual NON_MATCH \| FP (\d+) \| TN (\d+)", text)
        got = tuple(int(x) for x in m.groups()) if m else None
        want = (cf["tp"], cf["fn"], cf["fp"], cf["tn"])
        md_checked += 1
        if got != want:
            md_diffs.append([kind, "confusion TP,FN,FP,TN", str(want), str(got)])
        for path in ("exact_id", "deterministic", "probabilistic"):
            m = re.search(r"\| %s \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \|" % path, text)
            bp = ev["perKind"][kind]["byPath"][path]["confusion"]
            want = (ev["perKind"][kind]["byPath"][path]["n"], bp["tp"], bp["fp"], bp["fn"], bp["tn"])
            got = tuple(int(x) for x in m.groups()) if m else None
            md_checked += 1
            if got != want:
                md_diffs.append([kind, "path " + path + " n,TP,FP,FN,TN", str(want), str(got)])
        if kind == "researcher":
            cf2 = ev["perKind"]["researcher"]["confusion"]
            m = re.search(r"Precision (\d\.\d+), recall (\d\.\d+), F1 (\d\.\d+)", text)
            md_checked += 1
            if not m or (round(cf2["precision"], 4), round(cf2["recall"], 4), round(cf2["f1"], 4)) != tuple(round(float(x), 4) for x in m.groups()):
                md_diffs.append(["researcher", "precision,recall,F1 (4 dp)", "from json", str(m.groups() if m else None)])
    c.write_csv("crosscheck-eval-vs-results-md.csv", ["kind", "item", "heldout_eval_json", "results_md"], md_diffs)
    # Cross-check against the Phase 05 gate table row for the researcher figures.
    gate_line = None
    if gate_p:
        for i, line in enumerate(open(gate_p, encoding="utf-8"), 1):
            if line.startswith("| researcher | 90 |"):
                gate_line = [i, line.strip()]
    # split counts (the er_report.py figures) and the instructor requirement
    split_rows = [[x["kind"], x["label"], x["split"], x["count"]] for x in sp["counts"]]
    c.write_csv("er-gold-split-counts.csv", ["kind", "label", "split", "count"], split_rows)
    findings = []
    if diffs:
        findings.append("FINDING: %d key(s) differ between er-v1-heldout-eval.json perKind and er-v1-results.json heldout (see crosscheck-eval-vs-results-json.csv); not corrected." % len(diffs))
    if md_diffs:
        findings.append("FINDING: %d figure(s) differ between er-v1-heldout-eval.json and er-v1-results.md (see crosscheck-eval-vs-results-md.csv); not corrected." % len(md_diffs))
    # Factual comparison against the cited requirement (docs/agent-runs/instructor-approvals.md, gold-set item): minimum 170 pairs, 60/40 split (102 / 68), positives and hard negatives in both.
    req_p = c.use(os.path.join(ROOT, "docs/agent-runs/instructor-approvals.md"))
    req_cite = None
    if req_p:
        for i, line in enumerate(open(req_p, encoding="utf-8"), 1):
            if "Entity-resolution gold set" in line:
                req_cite = "docs/agent-runs/instructor-approvals.md:%d" % i
    labelled = sp["calibration"] + sp["heldout"]
    cnt = {(x["kind"], x["label"], x["split"]): x["count"] for x in sp["counts"]}
    findings.append("COMPARISON with requirement (%s: at least 170 pairs, 60/40 into 102 calibration and 68 held-out, both with positives and hard negatives): labelled pairs in the split %d (%s 170); calibration %d (%.1f%%), held-out %d (%.1f%%) vs required 102/68 (60.0%%/40.0%%); %d rows read, %d UNSURE excluded. Reported, not changed." %
                    (req_cite or "requirement line NOT FOUND", labelled, "meets" if labelled >= 170 else "below", sp["calibration"], 100.0 * sp["calibration"] / labelled, sp["heldout"], 100.0 * sp["heldout"] / labelled, sp["rows"], sp["excludedUnsure"]))
    for split in ("calibration", "heldout"):
        for kind in ("researcher", "work"):
            pos, neg = cnt.get((kind, "MATCH", split), 0), cnt.get((kind, "NON_MATCH", split), 0)
            findings.append("SPLIT COMPOSITION %s %s: %d MATCH, %d NON_MATCH%s (gold-split-v1.summary.json counts). Whether a NON_MATCH is a 'hard' negative is not recorded in that file." %
                            (split, kind, pos, neg, "; no negative of any kind in this set" if neg == 0 else ""))
    wcf = rs["heldout"]["work"]["confusion"]
    findings.append("NOTE: held-out work has %d MATCH and %d NON_MATCH; Phase 05 marks work precision and F1 'not measurable' (er-v1-results.json heldout.work.confusion precisionNote/f1Note; human-gate.md:32); the tables here print 'not measurable' for them. The Phase 05 tool copy (er_report_py/) keeps its raw values and must not be read as a precision result." %
                    (cnt.get(("work", "MATCH", "heldout"), 0), cnt.get(("work", "NON_MATCH", "heldout"), 0)))
    llm = rs["heldout"]["researcher"]["byPath"]["llm"]["confusion"]
    findings.append("NOTE: llm path has n=%d held-out rows, status '%s' (er-v1-results.json heldout.researcher.byPath.llm.confusion.status); its precision, recall and F1 print 'not measurable'. Unresolved rate counts the probabilistic band, which would go to the LLM." %
                    (ev["perKind"]["researcher"]["byPath"]["llm"]["n"], llm.get("status")))
    summary = {"evidence_class": "R", "source": "committed Phase 05 files, no recomputation of the policy; null (not measurable) cells printed as 'not measurable' as in er-v1-results.json", "policy": ev["policyVersion"],
               "code_version": ev["codeVersion"], "held_out_rows": ev["heldOutRows"], "llm_used": ev["llmUsed"],
               "per_kind": {k: {"n": ev["perKind"][k]["confusion"]["n"], "tp": ev["perKind"][k]["confusion"]["tp"], "fp": ev["perKind"][k]["confusion"]["fp"],
                                "fn": ev["perKind"][k]["confusion"]["fn"], "tn": ev["perKind"][k]["confusion"]["tn"],
                                "precision": nm_(rs["heldout"][k]["confusion"]["precision"]), "recall": nm_(rs["heldout"][k]["confusion"]["recall"]), "f1": nm_(rs["heldout"][k]["confusion"]["f1"]),
                                "coverage": ev["perKind"][k]["coverage"], "unresolved_rate": ev["perKind"][k]["unresolvedRate"], "conflict_rate": ev["perKind"][k]["conflictRate"]} for k in ("researcher", "work")},
               "crosscheck_json_differences": len(diffs), "crosscheck_md_items_checked": md_checked, "crosscheck_md_differences": len(md_diffs),
               "gate_table_row": gate_line, "findings": findings, "split": {"rows": sp["rows"], "calibration": sp["calibration"], "heldout": sp["heldout"],
                                                                         "excluded_unsure": sp["excludedUnsure"], "seed": sp["seed"]}}
    c.write_json("er-summary.json", summary)
    if diffs:
        c.notes.append("crosscheck-eval-vs-results-json.csv lists every differing key between the two committed files")
    c.finish("g1", "R", {})
    for line in findings:
        print("SC-G1 " + line)
    print("SC-G1 json_differences=%d md_items_checked=%d md_differences=%d" % (len(diffs), md_checked, len(md_diffs)))
    return 0


# ---------------------------------------------------------------------------------------------------------------- group 2
def read_hadoop_gz(root, table):
    out = []
    for p in sorted(glob.glob(os.path.join(root, "curated", table, "**", "part-*.gz"), recursive=True)):
        with gzip.open(p, "rt", encoding="utf-8") as f:
            out += [json.loads(x) for x in f if x.strip()]
    return out


def g2(c):
    load = os.path.join(c.main, "modules/storage/target/loads/L5")
    nep = os.path.join(load, "neptune")
    ddb = os.path.join(load, "dynamodb/items")
    labels = ["Researcher", "Work", "Venue", "Topic", "Institution", "Community", "Component", "ExternalIdentity"]
    etypes = ["AUTHORED", "COAUTHORED_WITH", "COMMUNITY_LINK", "HAS_TOPIC", "IN_COMPONENT", "MEMBER_OF", "PUBLISHED_IN", "RESOLVES_TO"]
    need = [os.path.join(nep, "vertices", l + ".csv") for l in labels] + [os.path.join(nep, "edges", e + ".csv") for e in etypes]
    need += [os.path.join(ddb, "coauthor-edge-works.jsonl"), os.path.join(ddb, "researcher-metrics.jsonl"), os.path.join(ddb, "paths.jsonl"),
             os.path.join(ddb, "mention-researcher.jsonl"), os.path.join(load, "load-manifest.json")]
    for p in need:
        c.use(p)
    if c.na:
        print("SC-REFUSED input(s) missing: %s" % c.na, file=sys.stderr)
        return 2
    V = {l: csv_rows(os.path.join(nep, "vertices", l + ".csv")) for l in labels}
    E = {e: csv_rows(os.path.join(nep, "edges", e + ".csv")) for e in etypes}
    vid_label = {}
    dup_v = {}
    for l in labels:
        ids = [r[":ID"] for r in V[l]]
        dup_v[l] = len(ids) - len(set(ids))
        for i in ids:
            vid_label.setdefault(i, l)
    all_vertex_rows = sum(len(V[l]) for l in labels)
    cross_label_dups = all_vertex_rows - len(vid_label) - sum(dup_v.values())
    # 1. referential integrity
    ri = []
    dangling_total = edges_total = 0
    for e in etypes:
        d = 0
        for r in E[e]:
            if r[":START_ID"] not in vid_label or r[":END_ID"] not in vid_label:
                d += 1
        ri.append([e, len(E[e]), d, f4(frac(d, len(E[e])))])
        dangling_total += d
        edges_total += len(E[e])
    # 2. duplicates
    dup_e = {e: len(E[e]) - len({r[":ID"] for r in E[e]}) for e in etypes}
    coa = E["COAUTHORED_WITH"]
    self_loops = sum(1 for r in coa if r[":START_ID"] == r[":END_ID"])
    pairs = Counter((tuple(sorted((r[":START_ID"], r[":END_ID"]))), r["window:String"]) for r in coa)
    dup_pairs = sum(v - 1 for v in pairs.values())
    ordered = Counter((r[":START_ID"], r[":END_ID"], r["window:String"]) for r in coa)
    reversed_dups = len(ordered) - len(pairs)  # ordered tuples collapsing to one unordered pair
    ew = jl(os.path.join(ddb, "coauthor-edge-works.jsonl"))
    ew_keys = Counter((r["pk"], r["sk"]) for r in ew)
    dup_ew = sum(v - 1 for v in ew_keys.values())
    # 3. orphans
    authored = E["AUTHORED"]
    auth_by_work, auth_by_res = defaultdict(set), defaultdict(set)
    for r in authored:
        auth_by_work[r[":END_ID"]].add(r[":START_ID"])
        auth_by_res[r[":START_ID"]].add(r[":END_ID"])
    incident = Counter()
    for e in etypes:
        for r in E[e]:
            incident[r[":START_ID"]] += 1
            incident[r[":END_ID"]] += 1
    orphan_res = sum(1 for r in V["Researcher"] if r[":ID"] not in auth_by_res)
    orphan_work = sum(1 for r in V["Work"] if r[":ID"] not in auth_by_work)
    orphan_any = {l: sum(1 for r in V[l] if incident[r[":ID"]] == 0) for l in labels}
    # 4. support rate
    works_year = {r[":ID"]: int(r["year:Int"]) if r["year:Int"] else None for r in V["Work"]}
    ev_by_edge = defaultdict(list)
    for r in ew:
        a = r["attributes"]
        ev_by_edge[a["edgeId"]].append(a)
    n_edges = len(coa)
    with_ev = fully = count_match = window_ok = 0
    reasons = Counter()
    for r in coa:
        ev = ev_by_edge.get(r[":ID"], [])
        if ev:
            with_ev += 1
        a, b = r[":START_ID"], r[":END_ID"]
        ok = bool(ev)
        if not ev:
            reasons["no_evidence_rows"] += 1
        for x in ev:
            w = x["workId"]
            if w not in works_year:
                ok = False
                reasons["evidence_work_not_in_Work.csv"] += 1
            elif a not in auth_by_work.get(w, ()) or b not in auth_by_work.get(w, ()):
                ok = False
                reasons["endpoint_not_author_of_supporting_work"] += 1
        if ev and r["supportingWorksTruncated:Bool"].lower() != "true" and int(r["jointPublicationCount:Int"]) == len(ev):
            count_match += 1
        elif ev and r["supportingWorksTruncated:Bool"].lower() != "true":
            reasons["jointPublicationCount_neq_evidence_rows"] += 1
        wok = bool(ev)
        for x in ev:
            y = works_year.get(x["workId"])
            if y is None or not (x["windowStart"] <= y <= x["windowEnd"]) or x["window"] != r["window:String"]:
                wok = False
        if wok:
            window_ok += 1
        else:
            reasons["evidence_work_year_outside_window_or_window_mismatch"] += 1
        if ok:
            fully += 1
    # independent derivation of expected all-time coauthor pairs from AUTHORED
    expected = set()
    for w, rs in auth_by_work.items():
        rs = sorted(rs)
        for i in range(len(rs)):
            for j in range(i + 1, len(rs)):
                expected.add((rs[i], rs[j]))
    alltime = {k[0] for k in pairs if k[1] == "1000-9999"}
    missing, extra = len(expected - alltime), len(alltime - expected)
    # GC-03: coauthorCount vs degree, per researcher-window
    deg = defaultdict(set)
    for r in coa:
        w = r["window:String"]
        deg[(r[":START_ID"], w)].add(r[":END_ID"])
        deg[(r[":END_ID"], w)].add(r[":START_ID"])
    rm = jl(os.path.join(ddb, "researcher-metrics.jsonl"))
    gc3_bad = sum(1 for r in rm if r["attributes"]["coauthorCount"] != len(deg.get((r["attributes"]["researcherId"], r["attributes"]["window"]), ())))
    # GC-04/05: components and communities membership
    def once_per_window(etype):
        cnt = Counter((r[":START_ID"], r["window:String"]) for r in E[etype])
        return sum(1 for v in cnt.values() if v != 1), len(cnt)
    comp_bad, comp_n = once_per_window("IN_COMPONENT")
    comm_bad, comm_n = once_per_window("MEMBER_OF")
    res_ids = {r[":ID"] for r in V["Researcher"]}
    comp_cov = len({k for k, _ in Counter((r[":START_ID"], r["window:String"]) for r in E["IN_COMPONENT"]) if k in res_ids})
    comm_cov = len({k for k, _ in Counter((r[":START_ID"], r["window:String"]) for r in E["MEMBER_OF"]) if k in res_ids})
    # GC-06: load manifest counts vs rows read
    lm = sc_common.read_json(os.path.join(load, "load-manifest.json"))
    mf = {f["label"]: f["rows"] for f in lm["neptune"]["files"]}
    actual = {**{l: len(V[l]) for l in labels}, **{e: len(E[e]) for e in etypes}}
    gc6_bad = sorted(k for k in actual if mf.get(k) != actual[k])
    # GC-08: stored shortest paths vs independent BFS per window
    adj = defaultdict(lambda: defaultdict(set))
    for r in coa:
        adj[r["window:String"]][r[":START_ID"]].add(r[":END_ID"])
        adj[r["window:String"]][r[":END_ID"]].add(r[":START_ID"])
    paths = jl(os.path.join(ddb, "paths.jsonl"))
    gc8_bad = []
    for p in paths:
        a = p["attributes"]
        g = adj[a["window"]]
        dist = {a["startId"]: 0}
        dq = deque([a["startId"]])
        while dq:
            u = dq.popleft()
            for v in g[u]:
                if v not in dist:
                    dist[v] = dist[u] + 1
                    dq.append(v)
        valid_edges = all(h2 in g[h1] for h1, h2 in zip(a["hops"], a["hops"][1:]))
        if dist.get(a["endId"]) != a["length"] or len(a["hops"]) != a["length"] + 1 or not valid_edges:
            gc8_bad.append(p["sk"])
    # GC-01 / GC-02
    gc2_bad = dup_pairs + reversed_dups
    # ---------------- provenance audit (seeded)
    rng = random.Random(c.seed)
    edge_ids = sorted(r[":ID"] for r in coa)
    sample = rng.sample(edge_ids, c.sample)
    hroot = os.path.join(c.main, "modules/hadoop/target/runs/rootI")
    xml_p = os.path.join(c.main, "datasets/raw/dblp/derived/dblp_selection_1000.xml")
    c.use(xml_p)
    mentions = read_hadoop_gz(hroot, "author-mentions") if os.path.isdir(hroot) else []
    if not mentions:
        c.na.append(hroot + " (curated author-mentions)")
    ment_by_work = defaultdict(list)
    for m in mentions:
        ment_by_work[m["workId"]].append(m)
    recorded = Counter((m["provenance"]["inputs"][0]["inputId"], m["provenance"]["inputs"][0].get("version"), m["provenance"]["inputs"][0]["checksum"]) for m in mentions)
    mres = {r["pk"]: r["attributes"]["researcherId"] for r in jl(os.path.join(ddb, "mention-researcher.jsonl"))}
    xml = open(xml_p, "rb").read() if os.path.isfile(xml_p) else b""
    xml_sha = hashlib.sha256(xml).hexdigest() if xml else None
    by_id = {r[":ID"]: r for r in coa}
    wtitle = {r[":ID"]: r["title:String"] for r in V["Work"]}
    audit_rows, audit_fail = [], 0
    for eid in sample:
        r = by_id[eid]
        a, b = r[":START_ID"], r[":END_ID"]
        evs = ev_by_edge.get(eid, [])
        for x in evs:
            w = x["workId"]
            row = {"edge_id": eid, "window": r["window:String"], "researcher_a": a, "researcher_b": b, "work_id": w, "work_in_Work_csv": w in works_year,
                   "a_authored_work": a in auth_by_work.get(w, ()), "b_authored_work": b in auth_by_work.get(w, ()), "title": wtitle.get(w, "")}
            sides = {}
            for side, rid in (("a", a), ("b", b)):
                ms = [m for m in ment_by_work.get(w, []) if mres.get(m["mentionId"]) == rid]
                trace = []
                for m in ms:
                    pr = m["provenance"]
                    off = int(pr["parameters"].get("inputByteOffset", "-1"))
                    snippet = xml[off:off + 400].decode("utf-8", "replace") if xml and off >= 0 else ""
                    key = w[len("work:dblp:"):] if w.startswith("work:dblp:") else None
                    km = re.match(r'<[a-z]+[^>]*\bkey="([^"]+)"', snippet)
                    trace.append({"mention_id": m["mentionId"], "position": m["position"], "raw_name": m["rawName"], "source_file": pr["inputs"][0]["inputId"],
                                  "source_checksum": pr["inputs"][0]["checksum"], "byte_offset": off, "xml_record_key_at_offset": km.group(1) if km else None,
                                  "record_key_matches_work_id": bool(km and key and km.group(1) == key), "hadoop_run_id": pr["runId"]})
                sides[side] = trace
            traced = all(any(t["record_key_matches_work_id"] for t in sides[s]) for s in ("a", "b"))
            row.update({"source_trace_a": sides["a"], "source_trace_b": sides["b"], "traced_to_source_record": traced})
            row["pass"] = bool(row["work_in_Work_csv"] and row["a_authored_work"] and row["b_authored_work"] and traced)
            if not row["pass"]:
                audit_fail += 1
            audit_rows.append(row)
        if not evs:
            audit_fail += 1
            audit_rows.append({"edge_id": eid, "pass": False, "reason": "no evidence rows"})
    sample_pass_edges = sum(1 for eid in sample if all(x["pass"] for x in audit_rows if x["edge_id"] == eid) and any(x["edge_id"] == eid for x in audit_rows))
    c.write_json("provenance-audit.json", {"seed": c.seed, "sample_size": c.sample, "population": "all COAUTHORED_WITH edge ids (all windows), sorted, random.Random(seed).sample",
                                           "population_size": len(edge_ids), "xml_sha256_observed": xml_sha,
                                           "evidence_class": "R",
                                           "provenance_inputs0_recorded_in_hadoop_author_mentions": [{"inputId": k[0], "version": k[1], "checksum": k[2], "mention_rows": v} for k, v in sorted(recorded.items())],
                                           "xml_sha256_matches_recorded_for_dblp_selection_input": bool(xml_sha) and bool([k for k in recorded if k[0].endswith("dblp_selection_1000.xml")]) and all(k[2] == xml_sha for k in recorded if k[0].endswith("dblp_selection_1000.xml")),
                                           "edges_sampled": len(sample), "edges_fully_traced": sample_pass_edges, "evidence_rows_audited": len(audit_rows), "evidence_rows_failed": audit_fail,
                                           "rows": audit_rows})
    c.write_csv("provenance-audit.csv", ["edge_id", "window", "work_id", "work_in_Work_csv", "a_authored_work", "b_authored_work", "traced_to_source_record", "pass"],
                [[x["edge_id"], x.get("window", ""), x.get("work_id", ""), x.get("work_in_Work_csv", ""), x.get("a_authored_work", ""), x.get("b_authored_work", ""),
                  x.get("traced_to_source_record", ""), x["pass"]] for x in audit_rows])
    # ---------------- outputs
    c.write_csv("referential-integrity.csv", ["edge_type", "edges", "dangling_endpoint", "dangling_rate"], ri)
    gc = lambda cid, obs, ok: [cid, obs, "PASS" if ok else "FAIL"]
    checks = [
        gc("GC-01 self loops in COAUTHORED_WITH", "%d of %d" % (self_loops, n_edges), self_loops == 0),
        gc("GC-02 duplicate unordered (pair, window) rows / ordered duplicates", "%d / %d" % (dup_pairs, reversed_dups), gc2_bad == 0),
        gc("GC-03 researcher-metrics coauthorCount != degree in loaded edges (rows)", "%d of %d" % (gc3_bad, len(rm)), gc3_bad == 0),
        gc("GC-04 IN_COMPONENT: (researcher, window) with count != 1 / distinct researchers covered", "%d of %d / %d of %d" % (comp_bad, comp_n, comp_cov, len(res_ids)), comp_bad == 0 and comp_cov == len(res_ids)),
        gc("GC-05 MEMBER_OF: (researcher, window) with count != 1 / distinct researchers covered", "%d of %d / %d of %d" % (comm_bad, comm_n, comm_cov, len(res_ids)), comm_bad == 0 and comm_cov == len(res_ids)),
        gc("GC-06 load-manifest row counts differ from rows read (labels)", "%s" % (gc6_bad or "none"), not gc6_bad),
        gc("GC-07 edges whose evidence works lie outside the edge window (or window mismatch)", "%d of %d" % (n_edges - window_ok, n_edges), window_ok == n_edges),
        gc("GC-08 stored shortest paths disagreeing with independent BFS", "%d of %d" % (len(gc8_bad), len(paths)), not gc8_bad),
    ]
    c.write_csv("graph-correctness-checks.csv", ["check", "observed", "verdict"], checks)
    summary = {
        "evidence_class": "R", "input_load": "modules/storage/target/loads/L5 (main checkout, untracked), neptune CSV and dynamodb jsonl",
        "load_manifest_fields": {"loadRunId": lm["loadRunId"], "codeVersion": lm["codeVersion"], "input.sparkRunId": lm["input"]["sparkRunId"], "input.sparkCodeVersion": lm["input"]["sparkCodeVersion"]},
        "dataset_versions_in_hadoop_provenance": sorted({(k[1]) for k in recorded if k[1]}),
        "referential_integrity": {"edges_total": edges_total, "dangling_total": dangling_total, "dangling_rate": frac(dangling_total, edges_total)},
        "duplicates": {"vertex_dup_ids_by_label": dup_v, "vertex_ids_in_more_than_one_label": cross_label_dups, "edge_dup_ids_by_type": dup_e, "dup_unordered_pair_window_rows": dup_pairs,
                       "ordered_duplicates": reversed_dups, "dup_evidence_pk_sk": dup_ew, "vertex_rows": all_vertex_rows, "edge_rows": edges_total,
                       "duplicate_rate": frac(sum(dup_v.values()) + sum(dup_e.values()) + dup_pairs + dup_ew, all_vertex_rows + edges_total + len(ew))},
        "orphans": {"researchers_without_AUTHORED": orphan_res, "researchers": len(V["Researcher"]), "researcher_orphan_rate": frac(orphan_res, len(V["Researcher"])),
                    "works_without_AUTHORED": orphan_work, "works": len(V["Work"]), "work_orphan_rate": frac(orphan_work, len(V["Work"])),
                    "vertices_with_no_incident_edge_by_label": orphan_any, "vertex_count_by_label": {l: len(V[l]) for l in labels},
                    "overall_no_incident_edge_rate": frac(sum(orphan_any.values()), all_vertex_rows)},
        "coauthor_edge_support": {"edges": n_edges, "with_evidence_rows": with_ev, "fully_supported": fully, "support_rate": frac(fully, n_edges), "failure_reasons": dict(reasons),
                                  "jointPublicationCount_equals_evidence_rows_when_not_truncated": count_match, "evidence_in_window": window_ok,
                                  "independent_alltime_pairs_from_AUTHORED": len(expected), "loaded_alltime_pairs": len(alltime), "expected_missing_from_load": missing,
                                  "loaded_not_expected": extra},
        "provenance_audit": {"seed": c.seed, "sample": c.sample, "edges_fully_traced": sample_pass_edges, "evidence_rows_audited": len(audit_rows), "evidence_rows_failed": audit_fail,
                             "xml_sha256_observed": xml_sha},
        "checks": {x[0]: [x[1], x[2]] for x in checks}}
    c.write_json("graph-summary.json", summary)
    c.write_text("known-small-graph-citations.md", known_graph_citations())
    c.finish("g2", "R", {"seed": c.seed, "sample": c.sample, "load": "L5"}, overrides={"known-small-graph-citations.md": "L"})
    print("SC-G2 " + json.dumps(summary["referential_integrity"]) + " " + json.dumps(summary["orphans"]["researcher_orphan_rate"]))
    print("SC-G2 support_rate=%s fully=%d/%d audit_edges_traced=%d/%d rows_failed=%d" % (f4(frac(fully, n_edges)), fully, n_edges, sample_pass_edges, len(sample), audit_fail))
    for x in checks:
        print("SC-G2 %s -> %s %s" % (x[0], x[1], x[2]))
    return 0


def known_graph_citations():
    """Cite (do not re-run) the committed hand-computed known-graph tests and the gate lines that record their pass counts. Line numbers are looked up now."""
    topics = [("degree and weighted degree, per window, by hand", "FixtureMetricsSuite.scala", 'metrics 2000-2004: P, C, T, A, M, W'),
              ("sum of degrees = 2 x edges", "FixtureMetricsSuite.scala", "metric sums: sum of C_r"),
              ("connected components (sizes, ids, membership)", "FixtureMetricsSuite.scala", "components: sizes and ids per window"),
              ("components algorithm, convergence", "GraphAlgorithmsSuite.scala", "components: every node gets the smallest id"),
              ("k-core (coreness), by hand peeling", "FixtureMetricsSuite.scala", "coreness per window, by hand peeling"),
              ("k-core, K4 with tail", "GraphAlgorithmsSuite.scala", "k-core: K4 with a two-node tail"),
              ("shortest paths from a source, by hand", "FixtureGraphSuite.scala", "shortest-path lengths from Ada"),
              ("BFS shortest path tree", "GraphAlgorithmsSuite.scala", "bfs: distances and the smallest-parent rule"),
              ("path evidence vs independent BFS", "FixtureGraphSuite.scala", "path evidence matches an independent BFS"),
              ("communities (cohesive groups, accounting)", "FixtureGraphSuite.scala", "communities: edge accounting"),
              ("communities, two cliques", "GraphAlgorithmsSuite.scala", "communities: two disjoint cliques and an isolated node"),
              ("bridge person links two dense groups", "FixtureGraphSuite.scala", "bridges: the bridge person Mo links"),
              ("bridge measures kept separate", "FixtureMetricsSuite.scala", "metrics keep every measure separate")]
    base = os.path.join(ROOT, "modules/spark/src/test/scala/scholarchain/spark")
    out = ["# Known small-graph results: cited, not re-run (evidence class L)", "",
           "Generated by `experiments/evaluation/local_eval.py g2`. Each row is a hand-computed test in the committed Spark test tree; the line number was looked up when this file was written. These tests were NOT re-run in Phase 16a (no sbt).", "",
           "| Topic | Test file:line | Test name |", "|---|---|---|"]
    for topic, fn, needle in topics:
        p = os.path.join(base, fn)
        line = None
        if os.path.isfile(p):
            for i, l in enumerate(open(p, encoding="utf-8"), 1):
                if needle in l:
                    line = i
                    break
        out.append("| %s | `modules/spark/src/test/scala/scholarchain/spark/%s:%s` | %s |" % (topic, fn, line if line else NA, needle))
    gates = [("docs/agent-runs/phase-06-spark-analytics/agent-report.md", "sparkAnalytics 160"), ("docs/agent-runs/phase-09-runtime-agents/agent-report.md", "sparkAnalytics 268")]
    out += ["", "Recorded pass counts (as written in the committed agent reports, not re-observed here):", ""]
    for rel, needle in gates:
        p = os.path.join(ROOT, rel)
        line = None
        if os.path.isfile(p):
            for i, l in enumerate(open(p, encoding="utf-8"), 1):
                if needle in l:
                    line = i
                    break
        out.append("- `%s:%s` contains `%s`" % (rel, line if line else NA, needle))
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------------------------------------------- group 3
def g3(c):
    base = os.path.join(c.main, "modules/services/target/embedding-runs")
    rows, per_run, shas = [], {}, {}
    ks = (1, 3, 5)
    for run in ("L1", "L2", "L3", "L4"):
        p = c.use(os.path.join(base, run, "smoke.json"))
        if not p:
            continue
        s = sc_common.read_json(p)
        shas[run] = sc_common.sha256_file(p)
        acc = defaultdict(lambda: defaultdict(list))
        for q in s["queries"]:
            rel = [q["expectedKey"]]
            ranked = q["topKeys"]
            for cat in ("ALL", q["documentType"]):
                acc[cat]["mrr"].append(reciprocal_rank(ranked, rel))
                for k in ks:
                    acc[cat]["recall@%d" % k].append(recall_at_k(ranked, rel, k))
                    acc[cat]["ndcg@%d" % k].append(ndcg_at_k(ranked, rel, k))
                acc[cat]["expected_rank_ok"].append(1.0 if (q["expectedRank"] in (None, 0) or (q["expectedKey"] in ranked and ranked.index(q["expectedKey"]) + 1 <= q["expectedRank"])) else 0.0)
        per_run[run] = len(s["queries"])
        for cat in sorted(acc):
            m = acc[cat]
            n = len(m["mrr"])
            rows.append([run, "vector-only (local-hash-bow, dim 1024)", cat, n] + [f4(sum(m["recall@%d" % k]) / n) for k in ks] + [f4(sum(m["mrr"]) / n), f4(sum(m["ndcg@5"]) / n)])
    if not rows:
        print("SC-REFUSED no smoke.json", file=sys.stderr)
        return 2
    distinct = sorted(set(shas.values()))
    for r in rows:
        r += [shas[r[0]], distinct.index(shas[r[0]]) + 1]
    c.write_csv("retrieval-vector-only-local.csv", ["embedding_run", "mode", "document_type", "n_queries", "recall@1", "recall@3", "recall@5", "mrr_top5", "ndcg@5", "smoke_json_sha256", "distinct_measurement_no"], rows)
    ident = ("The smoke.json files of %s are byte-identical (sha256 %s): the %d rows per document type are ONE measurement repeated in the table, not %d independent runs or a replication." % (
        "/".join(sorted(shas)), distinct[0], len(shas), len(shas))) if len(distinct) == 1 else ("The smoke.json files differ: %d distinct sha256 values across %s (see distinct_measurement_no)." % (len(distinct), "/".join(sorted(shas))))
    c.notes.append(ident)
    skeleton = [[m, "ALL", PENDING_AWS, PENDING_AWS, PENDING_AWS, PENDING_AWS, PENDING_AWS, "Phase 15c/16: service must accept the mode; AWS S3 Vectors and Neptune/DynamoDB needed"]
                for m in ("graph-only", "fused (graph + vector)", "vector-only (real embedder, S3 Vectors)", "optional OpenSearch comparison")]
    c.write_csv("retrieval-modes-pending.csv", ["mode", "category", "recall@K", "precision@K", "mrr", "ndcg", "n_scored", "note"], skeleton)
    c.write_text("README.md", "# Group 3 retrieval (local only)\n\nEvidence class R, restricted: the only label-backed local retrieval run on disk is the Phase 08 smoke set (`modules/services/target/embedding-runs/L1..L4/smoke.json`, 25 known-item queries each, embedder `local-hash-bow`, dimension 1024, only the top 5 keys stored). K is capped at 5. Queries are built from tokens that occur in the target document and in no other document of its type (the `why` field of each smoke entry), so these are lexical known-item lookups, not relevance-judged retrieval. They say nothing about semantic quality, graph-only or fused retrieval. Graph-only, real-embedder vector-only, fused and OpenSearch comparisons are `%s` (Phase 15c/16).\n\nPhase 09 benchmark identity resolution is reported in group 4, not here.\n\n%s\n" % (PENDING_AWS, ident))
    c.finish("g3", "R (restricted: local hash embedder, known-item smoke set)", {"ks": list(ks)})
    for r in rows:
        if r[2] == "ALL":
            print("SC-G3 " + " ".join(str(x) for x in r))
    return 0


# ---------------------------------------------------------------------------------------------------------------- group 4
def g4(c):
    base = os.path.join(c.main, "modules/services/target/phase09-benchmark")
    bench_p = c.use(os.path.join(ROOT, "modules/rag/src/main/resources/benchmarks/benchmark-v1.json"))
    c.use(os.path.join(base, "R2/summary.json"))
    c.use(os.path.join(base, "R1/summary.json"))
    qfiles = sorted(glob.glob(os.path.join(base, "R2/questions/*.json")))
    if not (bench_p and qfiles):
        print("SC-REFUSED benchmark or R2 questions missing", file=sys.stderr)
        return 2
    for p in qfiles:
        c.use(p)
    bench = {q["id"]: q for q in sc_common.read_json(bench_p)["questions"]}
    status, reason = Counter(), Counter()
    exp_abs = act_abs = 0
    abst = Counter()
    id_facts = id_ok = 0
    num_facts = 0
    harness = Counter()
    forb_total = forb_viol = 0
    sem_claims = 0
    per_q = []
    cand_total = cand_hit = 0
    by_cat_status = Counter()
    by_cat_reason = Counter()
    for p in qfiles:
        d = sc_common.read_json(p)
        q = bench[d["id"]]
        e = q["expected"]
        text = d["answerText"]
        for cl in d["claims"]:
            status[cl["status"]] += 1
            reason.update(cl["reasonCodes"])
            by_cat_status[(d["category"], cl["status"])] += 1
            for rc_ in cl["reasonCodes"]:
                by_cat_reason[(d["category"], cl["status"], rc_)] += 1
        pa, ea = bool(d["abstained"]), bool(e.get("abstain"))
        abst[("expected_abstain=%s" % ea, "actual_abstain=%s" % pa)] += 1
        for f in e.get("facts", []):
            if f["kind"] == "identity":
                id_facts += 1
                if f["subject"] in text and f["value"] in text and f["subject"] in d["resolvedIds"]:
                    id_ok += 1
            elif f["kind"] == "metrics" and isinstance(f["value"], (int, float)):
                num_facts += 1
        for s in e.get("forbiddenInAnswer", []):
            forb_total += 1
            if s and s in text:
                forb_viol += 1
        if e.get("candidateIds"):
            for cid in e["candidateIds"]:
                cand_total += 1
                if cid in d["candidateIds"] or cid in text:
                    cand_hit += 1
        for ck in d["checks"]:
            harness[(ck["name"].split(":")[0], ck["ok"])] += 1
        per_q.append([d["id"], d["category"], len(d["claims"]), sum(1 for x in d["claims"] if x["status"] == "verified"), pa, ea, d["pass"], d.get("abstainReason") or ""])
    nq = len(qfiles)
    claims_total = sum(status.values())
    verified = status.get("verified", 0)
    nonver = {k: v for k, v in status.items() if k != "verified"}
    tp = abst[("expected_abstain=True", "actual_abstain=True")]
    fp = abst[("expected_abstain=False", "actual_abstain=True")]
    fn = abst[("expected_abstain=True", "actual_abstain=False")]
    tn = abst[("expected_abstain=False", "actual_abstain=False")]
    c.write_csv("claims-by-category-status.csv", ["category", "claim_status", "reason_code", "claims"], [[k[0], k[1], k[2], v] for k, v in sorted(by_cat_reason.items())])
    nonver_by_cat = {}
    nonver_q = defaultdict(list)
    for (cat, st), v in sorted(by_cat_status.items()):
        if st != "verified":
            nonver_by_cat.setdefault(cat, {})[st] = v
    for r_ in per_q:
        if r_[2] - r_[3] > 0:
            nonver_q[r_[1]].append("%s (%d of %d claims not verified)" % (r_[0], r_[2] - r_[3], r_[2]))
    c.write_csv("per-question.csv", ["question_id", "category", "claims", "claims_verified", "abstained", "expected_abstain", "harness_pass", "abstain_reason"], per_q)
    summ = {"evidence_class": "F (fixture model: BenchmarkResponders with fake-model-v1; no real LLM, no real embedder)", "run": "modules/services/target/phase09-benchmark/R2 (R1 identical per Phase 09 report)",
            "questions": nq, "harness_all_checks_passed": sum(1 for r in per_q if r[6]), "claims_total": claims_total, "claims_verified": verified,
            "claim_support_rate": frac(verified, claims_total), "claim_status_counts": dict(status), "reason_code_counts": dict(reason),
            "claims_not_verified_by_category_and_status": nonver_by_cat, "questions_with_unverified_claims_by_category": dict(nonver_q),
            "reason_codes_by_category_status": [{"category": k[0], "status": k[1], "reason_code": k[2], "claims": v} for k, v in sorted(by_cat_reason.items())],
            "contradiction": {"claims_with_status_other_than_verified_or_partially_supported": sum(v for k, v in nonver.items() if k != "partially_supported"),
                              "rate": frac(sum(v for k, v in nonver.items() if k != "partially_supported"), claims_total),
                              "caveat": "zero is an observation over these %d fixture-model answers (all claims are verified or partially_supported); it is not a measured detection rate for contradictions" % nq},
            "identifier_accuracy": {"identity_facts": id_facts, "identity_facts_with_id_and_name_in_answer_and_resolved": id_ok, "rate": frac(id_ok, id_facts)},
            "numerical_accuracy": {"numeric_metric_facts_in_benchmark": num_facts, "harness_fact_checks_ok": harness[("fact", True)], "harness_fact_checks_failed": harness[("fact", False)],
                                   "harness_assert_checks_ok": harness[("assert", True)], "harness_assert_checks_failed": harness[("assert", False)],
                                   "note": "fact:/assert: checks are the committed benchmark harness verdicts stored in the raw question files; the number of fact checks differs from numeric facts because path/component facts are non-numeric or composite"},
            "abstention": {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "precision": frac(tp, tp + fp), "recall": frac(tp, tp + fn), "ambiguous_candidate_ids_expected": cand_total,
                           "ambiguous_candidate_ids_listed": cand_hit},
            "forbidden_in_answer": {"strings_checked": forb_total, "violations": forb_viol},
            "human_rating_sheet": "EMPTY (left for the human; see human-rating-sheet-EMPTY.csv)"}
    c.write_json("answer-quality-summary.json", summ)
    c.write_csv("human-rating-sheet-EMPTY.csv", ["question_id", "category", "mode", "correctness_1_to_5", "groundedness_1_to_5", "completeness_1_to_5", "unsupported_claims_count", "notes", "rater", "rated_at_utc"],
                [[r[0], r[1], "phase09-fixture-model-R2", "", "", "", "", "", "", ""] for r in per_q])
    c.write_text("README.md", "# Group 4 answer quality (fixture model)\n\nEvidence class F: the answers come from the Phase 09 composed benchmark with the scripted `fake-model-v1` and `BenchmarkResponders` (Phase 09 agent report). They show that the pipeline wiring, verifier and abstention logic behave as tested on 44 data-derived questions; they say nothing about answer quality with a real model. The expected values were generated from the same L4 tables the answers read (`BenchmarkGenerator`, no model), so correctness checks are data-consistency checks, not independent judgments. The human rating sheet is intentionally empty.\n")
    c.finish("g4", "F", {})
    print("SC-G4 unverified by category/status: %s" % json.dumps(nonver_by_cat, sort_keys=True))
    print("SC-G4 questions=%d harness_pass=%d claims=%d verified=%d support=%s abstain tp/fp/fn/tn=%d/%d/%d/%d identifier=%d/%d forbidden_violations=%d/%d" %
          (nq, summ["harness_all_checks_passed"], claims_total, verified, f4(frac(verified, claims_total)), tp, fp, fn, tn, id_ok, id_facts, forb_viol, forb_total))
    return 0


# ---------------------------------------------------------------------------------------------------------------- group 5
def g5(c):
    p = c.use(os.path.join(ROOT, "experiments/evaluation/performance_elasticity_template.csv"))
    rows = csv_rows(p)
    out = [[r["item"], r["experiment_id"], r["source_script"], r["unit"], PENDING_AWS, "no AWS run exists; Phase 15c/16"] for r in rows]
    c.write_csv("performance-elasticity-PENDING.csv", ["item", "experiment_id", "source_script", "unit", "value", "note"], out)
    c.write_text("README.md", "# Group 5 performance and elasticity: skeleton only\n\nEvery value is `%s`. No measurement was made in Phase 16a. The files under `experiments/results/SYNTHETIC-*` and `experiments/results/exp-*/synth-*` are Phase 15b SYNTHETIC verification fixtures and are not results.\n" % PENDING_AWS)
    c.finish("g5", "none (skeleton)", {})
    print("SC-G5 skeleton rows=%d" % len(out))
    return 0


# ---------------------------------------------------------------------------------------------------------------- group 6
AGENTS = ["factory-engineer", "build-engineer", "domain-engineer", "dataset-engineer", "hadoop-engineer", "entity-resolution-engineer", "spark-engineer", "storage-engineer",
          "rag-engineer", "service-engineer", "test-engineer", "code-reviewer", "infra-engineer", "deploy-engineer", "experiment-engineer", "documentation-engineer",
          "incident-responder", "dependency-updater"]
VERDICT_TOKEN = r"(APPROVE WITH NOTES|APPROVE-RECOMMENDED|REQUEST[_ -]CHANGES|CHANGES[_ -]REQUIRED|APPROVE)"
VERDICT_RE = re.compile(r"verdict(?:\s+on the repair|\s+\(round \d+\)|\s+is now)?[^A-Za-z]{0,12}" + VERDICT_TOKEN, re.I)
FIRST_STATED_RE = re.compile(r"first review (?:returned|was|gave)\s+" + VERDICT_TOKEN, re.I)  # inside review-report.md
GATE_FIRST_RE = re.compile(r"(?:review 1|first review|review pass 1)\s*[:(]?\s*" + VERDICT_TOKEN, re.I)  # inside human-gate.md
VERDICT_SKIP = re.compile(r"^\s*(VALIDATION RESULTS|NEXT GATE|WORK ORDER|MANUAL ACTION|BLOCKED-ON|STATUS|Orchestrator note|TEST RESULTS|COMMANDS RUN|ASSUMPTIONS|RISKS)\b", re.I)
STATUS_RE = re.compile(r"^\s*(?:\*\*)?STATUS:?(?:\*\*)?:?\s*(COMPLETE|BLOCKED-ON|FAILED)\b")
NUMW = {"zero": 0, "no": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12}
PAIR_NUM_FIRST = re.compile(r"\b(\d+|zero|no|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+(HIGH|MEDIUM|LOW|INFO)\b", re.I)
PAIR_SEV_FIRST = re.compile(r"\b(HIGH|MEDIUM|LOW|INFO)\s*[:=]?\s*(\d+)\b(?!\s*[\u2013\-]\s*\d)", re.I)  # 'LOW 2-6' is a finding-id range, not a count
ENUM_RES = [re.compile(r"^\s*(?:[-*]|\d+\.|\|)?\s*\*{0,2}\(?[A-Z]{1,3}-?\d+\*{0,2}\)?\s+\*{0,2}(HIGH|MEDIUM|LOW|INFO)\b"),
            re.compile(r"^\s*(?:[-*]|\d+\.|\|)\s*\*{0,2}[A-Za-z][A-Za-z0-9.\-]*\s*\*{0,2}\s*\((HIGH|MEDIUM|LOW|INFO)\b")]
REPAIR_REF = re.compile(r"repair-(\d+)\.md")
CATS = {
    "guard_hook_block": re.compile(r"blocked by (the )?guard|guard blocked|blocked .{0,60}by (the )?guard|guard false positive|BLOCKED by \.claude/hooks|guard-dangerous\.sh refus|Blocked by hook|was blocked|were blocked|blocked for containing|was denied by the permission|Denied by permission|denied by your permission", re.I),
    "stop_hook": re.compile(r"stop[- ]hook|SubagentStop|verify-tests-ran", re.I),
    "ownership_guard": re.compile(r"guard-writes[^.]{0,80}refus|cross-owner write|ownership deviation|ownership violation", re.I),
    "human_rewrite_or_correction": re.compile(r"human correction|human rewrote|rewritten by the human|correction by the orchestrator|human-edited|substantial rewrite", re.I),
    "manual_intervention": re.compile(r"human decision|human authoriz|human-authoriz|human decided|Human chose|human gate H|decisions of 20\d\d", re.I),
}


def norm_verdict(t):
    t = re.sub(r"[ -]", "_", t.upper())
    return "REQUEST_CHANGES" if t in ("REQUEST_CHANGES", "CHANGES_REQUIRED") else t


def stated_counts(line):
    """Ordered (severity, n) pairs written on one line ('8 MEDIUM', 'HIGH 0', 'no HIGH', 'Nine MEDIUM'). Parsed text only; the line itself is recorded next to it."""
    found = []
    pos = 0
    while pos <= len(line):  # left to right, non-overlapping: a number is claimed by the first pair that reaches it
        mn = PAIR_NUM_FIRST.search(line, pos)
        ms = PAIR_SEV_FIRST.search(line, pos)
        if mn is None and ms is None:
            break
        if ms is None or (mn is not None and mn.start() < ms.start()):
            v = mn.group(1).lower()
            found.append((mn.group(2).upper(), int(v) if v.isdigit() else NUMW[v]))
            pos = mn.end()
        else:
            found.append((ms.group(1).upper(), int(ms.group(2))))
            pos = ms.end()
    return found


def lines_of(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def cite(c, rel, needle):
    """file:line of the first line containing needle, looked up now (never typed). Registers the file as an input."""
    p = os.path.join(ROOT, rel)
    if os.path.isfile(p):
        c.use(p)
        for i, l in enumerate(lines_of(p), 1):
            if needle in l:
                return "%s:%d" % (rel, i)
    return "%s:NOT FOUND (searched for %r)" % (rel, needle)


def g6(c):
    rd = os.path.join(ROOT, "docs/agent-runs")
    phases = sorted(d for d in glob.glob(os.path.join(rd, "phase-*")) if os.path.isdir(d) and "phase-16a" not in d)
    events = []  # category, file, line, text
    prow, sevrows, stated_lines = [], [], []
    by_agent_status = defaultdict(Counter)
    agent_parts = defaultdict(set)
    agent_repaired = defaultdict(set)
    ambiguous = []
    no_counts = []
    rel = lambda p: os.path.relpath(p, ROOT)
    for d in phases:
        name = os.path.basename(d)
        files = sorted(glob.glob(os.path.join(d, "*.md")))
        for p in files:
            c.use(p)
        repairs = sorted(glob.glob(os.path.join(d, "repair-*.md")))
        blocked = 0
        verdicts = []
        first_stated = None
        enum = {}
        first_review_file = os.path.join(d, "review-report.md")
        hg = os.path.join(d, "human-gate.md")
        max_ref = None
        for p in files:
            base = os.path.basename(p)
            if base == "prompt.md" or base == "spec-excerpt.md":
                continue
            L = lines_of(p)
            for i, line in enumerate(L, 1):
                m = STATUS_RE.match(line)
                if m:
                    owner = None
                    for j in range(i, min(i + 4, len(L))):
                        mo = re.match(r"^\s*(?:\*\*)?OWNER:?(?:\*\*)?:?\s*([A-Za-z0-9\- ]+)", L[j])
                        if mo:
                            owner = mo.group(1).strip().lower()
                            break
                    events.append(["status_line", rel(p), i, line.strip()[:160] + (" | OWNER=%s" % owner if owner else "")])
                    if m.group(1) == "BLOCKED-ON":
                        blocked += 1
                        events.append(["blocked_on_event", rel(p), i, line.strip()[:160]])
                    if owner:
                        by_agent_status[owner][m.group(1)] += 1
                if not m and re.search(r"STATUS:?\**\s*BLOCKED-ON", line):
                    events.append(["blocked_on_midline_candidate", rel(p), i, line.strip()[:200]])
                for cat, rx in CATS.items():
                    if rx.search(line):
                        events.append([cat, rel(p), i, line.strip()[:200]])
                if not base.startswith("repair-"):
                    for mr in REPAIR_REF.finditer(line):
                        n_ = int(mr.group(1))
                        if max_ref is None or n_ > max_ref[0]:
                            max_ref = (n_, rel(p), i)
                if base == "review-report.md":
                    if not VERDICT_SKIP.match(line):
                        mv = VERDICT_RE.search(line)
                        if mv:
                            verdicts.append((i, norm_verdict(mv.group(1))))
                    mf = FIRST_STATED_RE.search(line)
                    if mf and first_stated is None:
                        first_stated = (i, norm_verdict(mf.group(1)), "review-report.md")
                    for rx in ENUM_RES:
                        me = rx.match(line)
                        if me:
                            e = enum.setdefault(me.group(1).upper(), [0, i])
                            e[0] += 1
                            break
                if base in ("review-report.md", "human-gate.md") and re.search(r"verdict|review", line, re.I):
                    sc = stated_counts(line)
                    if sc:
                        stated_lines.append([name, rel(p), i, ";".join("%s=%d" % x for x in sc), line.strip()[:260]])
        # gate-stated first verdict
        gate_first = None
        if os.path.isfile(hg):
            for i, line in enumerate(lines_of(hg), 1):
                mg = GATE_FIRST_RE.search(line)
                if mg:
                    gate_first = (i, norm_verdict(mg.group(1)), "human-gate.md")
                    break
        for p in repairs:
            if name.startswith("phase-01-") or name.startswith("phase-00-"):
                continue  # repairs of the roster text itself: every agent is named, none was repaired
            for i, line in enumerate(lines_of(p), 1):
                for ag in AGENTS:
                    if ag in line:
                        agent_repaired[ag].add(name)
                        events.append(["agent_named_in_repair_file", rel(p), i, ag])
        for base in ("agent-report.md", "agent_report.md", "test-report.md"):
            p = os.path.join(d, base)
            if os.path.isfile(p):
                txt = "\n".join(lines_of(p))
                for ag in AGENTS:
                    if ag in txt:
                        agent_parts[ag].add(name)
        # repair-file reference range versus repair files present
        if max_ref and max_ref[0] > len(repairs):
            ambiguous.append("%s: %s:%d refers to repair-%02d.md but %d repair-NN.md file(s) exist (%s); the repair count from files and the count in prose disagree. Both are listed, neither is guessed." %
                             (name, max_ref[1], max_ref[2], max_ref[0], len(repairs), ";".join(os.path.basename(x) for x in repairs) or "none"))
        # first verdict resolution (explicit statements outrank the first stored verdict line)
        first_stored = verdicts[0] if verdicts else None
        if first_stated:
            res = first_stated
        elif gate_first:
            res = gate_first
        elif first_stored:
            res = (first_stored[0], first_stored[1], "review-report.md first stored verdict line")
        else:
            res = None
        first_v = res[1] if res else None
        src = ("%s:%d (%s)" % (("docs/agent-runs/%s/" % name) + res[2].split(" ")[0], res[0], res[2])) if res else ""
        if gate_first and first_stored and not first_stated and gate_first[1] != first_stored[1]:
            ambiguous.append("%s: first stored verdict line review-report.md:%d is %s but the human gate states the first review was %s (human-gate.md:%d); the gate statement is used." % (name, first_stored[0], first_stored[1], gate_first[1], gate_first[0]))
        seq = " > ".join("%s@L%d" % (v[1], v[0]) for v in verdicts)
        if not first_v:
            ambiguous.append("%s: no reviewer verdict derivable (%s)" % (name, "review-report.md absent and no first-review statement in human-gate.md" if not os.path.isfile(first_review_file) else "review-report.md present but no verdict line matched"))
            fp = "n/a (no stored review verdict)" if not os.path.isfile(first_review_file) else "unknown"
        else:
            fp = "no" if first_v == "REQUEST_CHANGES" else "yes"
            if res[2].startswith("review-report.md first stored") and first_v.startswith("APPROVE"):
                head = [l for l in lines_of(first_review_file)[:8] if re.search(r"re-review|reread|re-read|after (?:review )?repair|repair-\d+ review", l, re.I)]
                if repairs or head:
                    fp = "ambiguous"
                    ambiguous.append("%s: first stored verdict is %s (review-report.md:%d) with no explicit first-review statement, and %s; the stored text may be a re-review, so first-pass acceptance is NOT scored (ambiguous)." %
                                     (name, first_v, res[0], ("%d repair file(s) exist" % len(repairs)) if repairs else ("the report head says it is a re-review: review-report.md:%d" % (1 + lines_of(first_review_file).index(head[0])))))
        # severity counts per phase: stated lines only; first stated line of review-report.md, else of human-gate.md
        mine = [x for x in stated_lines if x[0] == name]
        pick = next((x for x in mine if x[1].endswith("review-report.md")), None) or next((x for x in mine if x[1].endswith("human-gate.md")), None)
        enum_txt = "; ".join("%s lines=%d (first at review-report.md:%d)" % (k, v[0], v[1]) for k, v in sorted(enum.items()))
        if pick:
            sevrows.append([name, "%s:%d" % (pick[1], pick[2]), pick[3], pick[4], enum_txt])
        else:
            sevrows.append([name, "", "NO STATED COUNT", "", enum_txt])
            no_counts.append(name)
        prow.append([name, len(repairs), ";".join(os.path.basename(x) for x in repairs), blocked, seq, first_v or "", src, fp, os.path.isfile(hg), os.path.isfile(os.path.join(d, "commit.txt"))])
    for r in prow:
        if r[5] == "REQUEST_CHANGES" and r[1] == 0:
            ambiguous.append("%s: first verdict is REQUEST_CHANGES but there is no repair-NN.md file; the repair is recorded inside agent-report.md or human-gate.md, so repair iterations from files undercount here" % r[0])
    yes = sum(1 for r in prow if r[7] == "yes")
    no = sum(1 for r in prow if r[7] == "no")
    amb = sum(1 for r in prow if r[7] == "ambiguous")
    scored = yes + no + amb
    c.write_csv("phase-table.csv", ["phase", "repair_files", "repair_file_names", "blocked_on_status_lines", "review_verdict_sequence_in_review_report", "first_verdict_used", "first_verdict_source",
                                  "first_pass_accepted_by_reviewer", "human_gate_md_present", "commit_txt_present"], prow)
    c.write_csv("severity-by-phase.csv", ["phase", "stated_counts_source_file:line", "stated_counts_on_first_line_that_states_counts (round not identified: some reports store the newest round first; severities not listed are not stated on that line)", "that_line_text",
                                         "enumerated_finding_lines_all_rounds_not_deduplicated"], sevrows)
    c.write_csv("severity-stated-lines.csv", ["phase", "file", "line", "parsed_counts", "text"], stated_lines)
    c.write_csv("events.csv", ["category", "file", "line", "text"], events)
    agent_rows = []
    for ag in AGENTS:
        sc = by_agent_status.get(ag, Counter())
        part = sorted(agent_parts.get(ag, ()))
        rep = sorted(set(agent_repaired.get(ag, set())) & set(part))
        agent_rows.append([ag, sc.get("COMPLETE", 0), sc.get("BLOCKED-ON", 0), sc.get("FAILED", 0), len(part), len(rep), f4(frac(len(part) - len(rep), len(part))) if part else "", ";".join(rep)])
    c.write_csv("by-agent.csv", ["agent", "report_STATUS_COMPLETE_with_OWNER_line", "report_STATUS_BLOCKED-ON_with_OWNER_line", "report_STATUS_FAILED_with_OWNER_line", "phases_named_in_agent_or_test_report",
                                "of_which_agent_named_in_a_repair_file", "share_of_named_phases_with_agent_not_named_in_any_repair_file (NOT a first-pass rate)", "phases_named_in_repair"], agent_rows)
    cnt = Counter(e[0] for e in events)
    ambiguous.append("PHASES WITH NO RECORDED REVIEW SEVERITY COUNT (no 'N HIGH / N MEDIUM' style line in review-report.md or human-gate.md): %s. Where enumerated finding lines exist they are shown in severity-by-phase.csv (all rounds, not deduplicated); they are not counts of distinct findings." % (", ".join(no_counts) or "none"))
    ambiguous.append("SEVERITY: no cross-phase total is given. Stated counts exist only for some phases, they describe different review rounds and rubrics, and enumerated lines repeat findings across rounds; a sum would not be a count of anything. Read severity-by-phase.csv phase by phase.")
    ambiguous.append("FIRST-PASS: scored per phase from (1) an explicit 'first review returned X' line in review-report.md, else (2) 'review 1 / first review X' in human-gate.md, else (3) the first stored verdict line of review-report.md (which in some phases is the newest review). Case (3) with repair files or a re-review header is 'ambiguous' and not scored as yes or no. The phase-table column first_verdict_source names the line used.")
    manual = [
        "BLOCKED-ON: events.csv category blocked_on_midline_candidate lists lines where STATUS: BLOCKED-ON appears mid-line (for example %s, a dataset-engineer report quoted by the orchestrator); they are NOT in the count." % cite(c, "docs/agent-runs/phase-04-hadoop-mapreduce/agent-report.md", "Stop hook kept the agent's turn open"),
        "BLOCKED-ON counts: STATUS lines of the form 'STATUS: BLOCKED-ON' in any non-prompt .md file; a checkpoint or re-stated report may repeat one event. Each line is in events.csv (blocked_on_event); per-phase counts are in phase-table.csv. The author's reading of what the phase-01 and phase-12 lines are (probe refusals, human cost or gate stops) is a manual interpretation, not derived.",
        "Ownership violations caught by a hook: confirmed = 0 (manual reading of the cited lines). The only line claiming one is %s (second-hand: 'DS records that guard-writes refused one cross-owner write'; the DS report text is not stored), so it is a candidate, not a count. %s states the guard-writes.sh ownership fence was not exercised by any probe. %s records 'No ownership deviation' (the conditional sentence at %s says it would be a deviation if build-engineer wrote them; it records none). Agent self-refusals (not hook catches): %s, %s, %s (probe agents returned STATUS: BLOCKED-ON for out-of-ownership requests)." %
        (cite(c, "docs/agent-runs/phase-04-hadoop-mapreduce/review-report.md", "guard-writes refused one cross-owner write"), cite(c, "docs/agent-runs/phase-01-agent-factory/probe-report.md", "Not exercised by any probe"),
         cite(c, "docs/agent-runs/phase-02-build-skeleton/human-gate.md", "No ownership deviation"), cite(c, "docs/agent-runs/phase-02-build-skeleton/review-report.md", "that is an ownership deviation the human should record"),
         cite(c, "docs/agent-runs/phase-01-agent-factory/probe-report.md", 'domain-engineer: "Add a comment line'), cite(c, "docs/agent-runs/phase-01-agent-factory/probe-report.md", 'code-reviewer: "Fix any defect'),
         cite(c, "docs/agent-runs/phase-01-agent-factory/probe-report.md", 'test-engineer: "Create README.md')),
        "Guard, hook and stop-hook counts are MATCHING LINES, not distinct events: one block can be described in several files, and the regex also matches permission denials and unrelated uses of the word blocked. Confirmed preventions of an unsafe action (manual reading) are the post-ratification probes: %s (a forbidden phrase blocked by guard-dangerous.sh) and %s (a read of a secret file denied by a permission rule), restated at %s and %s. Most other guard lines in events.csv are false positives or compound-command blocks that the reporting agent says ran nothing, so they prevented no unsafe action; whether any would have been unsafe is a reader judgement and is not counted. Stop-hook lines include loops forced by merge timestamps or Python bytecode, not only real stale tests." %
        (cite(c, "docs/agent-runs/phase-01-agent-factory/probe-report.md", "| 1 | Bash"), cite(c, "docs/agent-runs/phase-01-agent-factory/probe-report.md", "| 2 | Read tool on"), cite(c, "docs/agent-runs/phase-01-agent-factory/human-gate.md", "| 3 | echo"), cite(c, "docs/agent-runs/phase-01-agent-factory/human-gate.md", "| 4 | Read")),
        "Reviewer findings by severity: see severity-by-phase.csv (stated counts, per phase, with file:line) and severity-stated-lines.csv (every line that states counts, all rounds). Phases without a stated count are listed above.",
        "by-agent.csv column 'share_of_named_phases_with_agent_not_named_in_any_repair_file' is a co-occurrence share: phases in which the agent is named in an agent or test report and NOT named in any repair file of that phase, divided by phases in which it is named. A repair file naming an agent does not show that the agent's work was rejected, and an agent absent from a repair file may still have been wrong. It is not a first-pass rate and is not evidence of one. First-pass acceptance is scored per phase by the reviewer (phase-table.csv), not per agent.",
        "Repair iterations per phase = number of repair-NN.md files; repair files may cover orchestrator-routed build or test fixes, not only agent defects. Phases whose prose names more repair files than exist are listed above.",
        "Artifacts that needed substantial human rewrite: no stored line states one. Candidate lines (human correction / rewrite wording) are in events.csv under human_rewrite_or_correction; %s says 'No human edits to code, tests, contracts or agent reports'. Count of confirmed substantial rewrites: not derivable." % cite(c, "docs/agent-runs/phase-04-hadoop-mapreduce/human-gate.md", "No human edits to code, tests, contracts or agent reports"),
        "Manual interventions: human-gate.md files are written by the human at every accepted phase (human_gate_md_present); category manual_intervention lists lines naming human decisions or authorizations inside agent and repair files.",
        "phase-00-seed-agent: its report file is named agent_report.md (underscore), included by this script; it holds %d STATUS line(s) (see events.csv status_line)." % sum(1 for e in events if e[0] == "status_line" and e[1].endswith("phase-00-seed-agent/agent_report.md")),
    ]
    c.write_text("ambiguous.md", "# Group 6 ambiguous or non-derivable items (listed, not guessed)\n\n" + "\n".join("- " + a for a in ambiguous) +
                 "\n\n## Manual citations (file:line resolved at run time by searching the quoted text; the interpretation is the author's reading, not machine-derived)\n\n" + "\n".join("- " + a for a in manual) + "\n")
    summ = {"evidence_class": "C-DOC (census of committed docs/agent-runs text; no run was executed; not L, F, R or A)", "phases_scanned": len(phases),
            "phases_with_stored_review_verdict": sum(1 for r in prow if r[5]),
            "first_pass_by_phase": {"yes": yes, "no": no, "ambiguous_not_scored": amb, "n_a_or_unknown": len(prow) - scored},
            "first_pass_rate_over_yes_and_no_only": frac(yes, yes + no), "first_pass_rate_bounds_if_ambiguous_all_no_or_all_yes": [frac(yes, scored), frac(yes + amb, scored)],
            "repair_files_total": sum(r[1] for r in prow), "repair_files_by_phase": {r[0]: r[1] for r in prow}, "blocked_on_status_lines": cnt.get("blocked_on_event", 0),
            "ownership_guard_matching_lines": cnt.get("ownership_guard", 0), "guard_hook_block_matching_lines": cnt.get("guard_hook_block", 0),
            "stop_hook_matching_lines": cnt.get("stop_hook", 0), "manual_intervention_matching_lines": cnt.get("manual_intervention", 0),
            "human_rewrite_candidate_lines": cnt.get("human_rewrite_or_correction", 0), "human_gate_files": sum(1 for r in prow if r[8]),
            "severity_by_phase": {r[0]: {"source": r[1], "stated_counts_on_that_line": r[2]} for r in sevrows},
            "severity_phases_without_stated_count": no_counts, "severity_cross_phase_total": "not given: stated counts cover only some phases and rounds; see ambiguous.md",
            "status_lines_total": cnt.get("status_line", 0)}
    c.write_json("factory-summary.json", summ)
    c.finish("g6", "C-DOC (census of committed documents; not a run; outside L/F/R/A)", {})
    print("SC-G6 " + json.dumps({k: v for k, v in summ.items() if k not in ("repair_files_by_phase", "severity_by_phase")}))
    return 0


# ---------------------------------------------------------------------------------------------------------------- group 7
def g7(c):
    import shutil
    import tempfile
    head, dirty = sc_common.git_state()
    tmp = tempfile.mkdtemp(prefix="sc16a-clean-")
    tree = os.path.join(tmp, "checkout")
    os.makedirs(tree)
    cl = subprocess.run(["git", "clone", "-q", "--local", "--no-hardlinks", ROOT, tree], capture_output=True, text=True, timeout=600)
    if cl.returncode != 0:
        print("SC-REFUSED local git clone failed: %s" % cl.stderr[:200], file=sys.stderr)
        return 2
    clone_head = subprocess.run(["git", "-C", tree, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    clone_dirty = bool(subprocess.run(["git", "-C", tree, "status", "--porcelain"], capture_output=True, text=True).stdout.strip())
    env = {k: v for k, v in os.environ.items() if k not in ("CLAUDE_PROJECT_DIR", "SC_RESULTS_ROOT")}
    env.update({"PYTHONDONTWRITEBYTECODE": "1", "PYTHONHASHSEED": "0"})
    cmds = [("validate-agent-files", ["bash", ".claude/hooks/validate-agent-files.sh"]),
            ("dataset-manifest-validator", ["python3", "-B", "datasets/scripts/validate_manifest.py"]),
            ("experiment-manifest-validator", ["python3", "-B", "experiments/validate_manifests.py"]),
            ("pytest-tests-datasets", ["python3", "-B", "-m", "pytest", "-p", "no:cacheprovider", "-q", "tests/datasets"]),
            ("pytest-tests-experiments", ["python3", "-B", "-m", "pytest", "-p", "no:cacheprovider", "-q", "tests/experiments"]),
            ("script-harness", ["bash", "scripts/tests/test-scripts.sh"])]
    rows = []
    cmds += [("INWORKTREE-dataset-manifest-validator", ["python3", "-B", "datasets/scripts/validate_manifest.py"]),
             ("INWORKTREE-experiment-manifest-validator", ["python3", "-B", "experiments/validate_manifests.py"])]
    for name, cmd in cmds:
        try:
            r = subprocess.run(cmd, cwd=(ROOT if name.startswith("INWORKTREE") else tree), env=env, capture_output=True, text=True, timeout=1500)
            out, err, code = r.stdout, r.stderr, r.returncode
        except subprocess.TimeoutExpired as e:
            out, err, code = (e.stdout or b"").decode("utf-8", "replace") if isinstance(e.stdout, bytes) else (e.stdout or ""), "TIMEOUT 1500 s", -9
        c.write_text("raw-%s.stdout.txt" % name, out)
        c.write_text("raw-%s.stderr.txt" % name, err)
        tail = [x for x in (out.strip().splitlines() or [""]) if x.strip()][-1:][0][:200]
        rows.append([name, " ".join(cmd), code, hashlib.sha256(out.encode()).hexdigest(), hashlib.sha256(err.encode()).hexdigest(), tail])
        print("SC-G7 %s exit=%s last_line=%s" % (name, code, tail))
    c.write_csv("clean-checkout-results.csv", ["check", "command (cwd = fresh local git clone of HEAD, or the worktree for INWORKTREE rows)", "exit", "stdout_sha256", "stderr_sha256", "last_stdout_line"], rows)
    pend = [["sbt clean check from a clean checkout", PENDING_EVAL, "needs sbt; run by the human (canonical CHECK)"],
            ["bounded pipeline replay", PENDING_AWS, "needs AWS (Phase 14/15c)"],
            ["deployment from the approved plan", PENDING_AWS, "needs AWS (Phase 14)"],
            ["regenerate reported tables and plots (Phase 15c/16 raw results)", PENDING_AWS, "no AWS raw results yet"]]
    c.write_csv("reproducibility-pending.csv", ["item", "status", "note"], pend)
    c.write_json("clean-checkout-info.json", {"source": "git clone --local --no-hardlinks of the worktree into a scratch directory outside the repository (no network)", "worktree_git_head": head,
                                               "clone_git_head": clone_head, "clone_dirty": clone_dirty, "worktree_dirty_at_run": dirty,
                                               "note": "untracked and uncommitted files are not in the clone; gitignored data under datasets/raw is absent, so checks that need it differ from the worktree",
                                               "env_removed": ["CLAUDE_PROJECT_DIR", "SC_RESULTS_ROOT"], "python": sys.version.split()[0]})
    shutil.rmtree(tmp, ignore_errors=True)
    c.finish("g7", "L", {"checks": [r[0] for r in rows]})
    return 0


FUNCS = {"g1": g1, "g2": g2, "g3": g3, "g4": g4, "g5": g5, "g6": g6, "g7": g7}


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("group", choices=sorted(FUNCS))
    ap.add_argument("--run-id", default="r1")
    ap.add_argument("--out-root", default=os.path.join(ROOT, "experiments", "results", "local"))
    ap.add_argument("--main-checkout", default=os.path.join(ROOT, "..", "ScholarChain"))
    ap.add_argument("--seed", type=int, default=160101)
    ap.add_argument("--sample", type=int, default=25)
    a = ap.parse_args(argv)
    if not sc_common.valid_token(a.run_id):
        print("SC-REFUSED bad run id", file=sys.stderr)
        return 2
    c = Ctx(a)
    try:
        return FUNCS[a.group](c)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
