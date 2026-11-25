"""Phase 16a local_eval.py (groups g1..g7): hand-computed examples, offline, every output under pytest tmp_path.

g1 is cross-checked against the committed Phase 05 files read independently of the tool; g2 and g3 run on tiny hand-built fake "main checkout" trees;
g6 runs in-process on a synthetic docs/agent-runs tree (module ROOT monkeypatched to tmp_path). Nothing is written under experiments/results/.
"""
import csv
import gzip
import hashlib
import importlib.util
import json
import math
import os
import random

import pytest

from conftest import REPO, clean_env, py

SCRIPT = os.path.join(REPO, "experiments", "evaluation", "local_eval.py")
EV = os.path.join(REPO, "modules", "entity-resolution", "evaluation", "er-v1-heldout-eval.json")
RS = os.path.join(REPO, "modules", "entity-resolution", "evaluation", "er-v1-results.json")
RESULTS_LOCAL = os.path.join(REPO, "experiments", "results", "local")
NM = "not measurable"  # Phase 05 null cells (repair g1)


def load_module():
    spec = importlib.util.spec_from_file_location("local_eval_under_test", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="module")
def le():
    return load_module()


def run_eval(group, out_root, extra=()):
    return py(SCRIPT, [group, "--out-root", str(out_root)] + list(extra), env=clean_env())


def rows_of(path):
    with open(str(path), newline="") as f:
        return list(csv.DictReader(f))


def jload(path):
    with open(str(path)) as f:
        return json.load(f)


def sha(path):
    return hashlib.sha256(open(str(path), "rb").read()).hexdigest()


def listing(path):
    return sorted(os.listdir(path)) if os.path.isdir(path) else None


# ------------------------------------------------------------------------------------------------------------------ g1 flatten
def test_flatten_hand_computed(le):
    out = {}
    le.flatten("", {"b": {"x": 1, "y": [10, {"z": None}]}, "a": "s"}, out)
    assert out == {"a": "s", "b.x": 1, "b.y[0]": 10, "b.y[1].z": None}
    assert list(out) == ["a", "b.x", "b.y[0]", "b.y[1].z"]  # dict keys visited in sorted order


def test_flatten_scalar_and_empty_containers(le):
    out = {}
    le.flatten("k", 5, out)
    le.flatten("e", {}, out)
    le.flatten("l", [], out)
    assert out == {"k": 5}  # empty dict/list contribute no keys (a missing-vs-empty difference is invisible to the cross-check)


def test_frac_and_f4(le):
    assert le.frac(1, 3) == pytest.approx(1 / 3.0)
    assert le.frac(0, 0) is None and le.frac(5, 0) is None
    assert le.f4(None) == "" and le.f4(0.5) == "0.5000" and le.f4(2 / 3.0) == "0.6667"


# ------------------------------------------------------------------------------------------------------------------ g1 vs committed
@pytest.fixture(scope="module")
def g1_out(tmp_path_factory):
    out_root = tmp_path_factory.mktemp("g1root")
    before = listing(RESULTS_LOCAL)
    p = run_eval("g1", out_root, ["--run-id", "t1"])
    assert p.returncode == 0, p.stderr
    assert listing(RESULTS_LOCAL) == before  # never writes into experiments/results/local
    return out_root / "g1-entity-resolution" / "t1", p


def test_g1_files_and_meta(g1_out):
    d, p = g1_out
    names = sorted(os.listdir(str(d)))
    assert names == ["crosscheck-eval-vs-results-json.csv", "crosscheck-eval-vs-results-md.csv", "er-by-path.csv", "er-gold-split-counts.csv",
                     "er-heldout-metrics.csv", "er-summary.json", "meta.json"]
    meta = jload(d / "meta.json")
    assert meta["group"] == "g1" and meta["evidence_class"] == "R" and meta["synthetic"] is False
    by_path = {i["path"]: i for i in meta["inputs"]}
    assert by_path["modules/entity-resolution/evaluation/er-v1-heldout-eval.json"]["sha256"] == sha(EV)
    assert meta["not_available_inputs"] == []


def test_g1_metrics_equal_committed_eval_json(g1_out):
    d, _ = g1_out
    ev = jload(EV)
    rows = {r["kind"]: r for r in rows_of(d / "er-heldout-metrics.csv")}
    assert set(rows) == {"researcher", "work"}
    rs = jload(RS)["heldout"]
    for kind in ("researcher", "work"):
        cf, v, r = ev["perKind"][kind]["confusion"], ev["perKind"][kind], rows[kind]
        assert [int(r[k]) for k in ("n", "tp", "fp", "fn", "tn")] == [cf["n"], cf["tp"], cf["fp"], cf["fn"], cf["tn"]]
        # Repair g1: precision/recall/F1 come from er-v1-results.json heldout; a null there prints as "not measurable", every other cell equals the eval json
        for col in ("precision", "recall", "f1"):
            rv = rs[kind]["confusion"][col]
            if rv is None:
                assert r[col] == NM, (kind, col)
            else:
                assert float(r[col]) == pytest.approx(rv) and float(r[col]) == pytest.approx(cf[col]), (kind, col)
        assert float(r["coverage"]) == pytest.approx(v["coverage"]) and float(r["unresolved_rate"]) == pytest.approx(v["unresolvedRate"])
        assert float(r["conflict_rate"]) == pytest.approx(v["conflictRate"])
        # derived specificity = tn / (tn + fp), hand formula, 4 dp string
        den = cf["tn"] + cf["fp"]
        assert r["specificity_derived"] == ("%.4f" % (cf["tn"] / float(den)) if den else NM)
        # confusion matrix is internally consistent
        assert cf["tp"] + cf["fp"] + cf["fn"] + cf["tn"] == cf["n"]


def test_g1_work_not_measurable_cells(g1_out):
    """Repair g1: held-out work has 0 NON_MATCH (tn+fp = 0). Cells Phase 05 marks null print 'not measurable' (not blank, 0 or 1); the raw cross-check still lists the difference."""
    d, p = g1_out
    ev, rs = jload(EV), jload(RS)
    wcf = ev["perKind"]["work"]["confusion"]
    assert wcf["tn"] + wcf["fp"] == 0
    work = [r for r in rows_of(d / "er-heldout-metrics.csv") if r["kind"] == "work"][0]
    assert work["specificity_derived"] == NM and work["precision"] == NM and work["f1"] == NM
    assert float(work["recall"]) == pytest.approx(0.6)  # recall is measurable (5 MATCH) and is not null in Phase 05
    assert rs["workMetricsNote"].startswith("DESCRIPTIVE only")
    bp = {(r["kind"], r["path"]): r for r in rows_of(d / "er-by-path.csv")}
    assert bp[("work", "exact_id")]["precision"] == NM and bp[("work", "exact_id")]["f1"] == NM and bp[("work", "exact_id")]["recall"] == "0.6"
    for path in ("deterministic", "probabilistic", "llm"):  # empty work paths: every Phase 05 cell is null
        r = bp[("work", path)]
        assert (r["precision"], r["recall"], r["f1"]) == (NM, NM, NM), path
    diffs = {r["json_path"]: r for r in rows_of(d / "crosscheck-eval-vs-results-json.csv")}
    # the raw cross-check is unchanged: results.json null vs the tool copy's 1.0 / 0.75 is listed, not hidden
    for key in ("work.byPath.exact_id.confusion.precision", "work.byPath.exact_id.confusion.f1"):
        assert key in diffs and diffs[key]["results_json_heldout"] == "None", key
    assert diffs["work.byPath.exact_id.confusion.precisionNote"]["heldout_eval_json"] == "ABSENT"
    assert diffs["work.byPath.exact_id.confusion.precisionNote"]["results_json_heldout"] == "'not measurable: no held-out NON_MATCH'"
    assert any("NOTE: held-out work has 5 MATCH and 0 NON_MATCH" in ln and "'not measurable'" in ln for ln in p.stdout.splitlines())


def test_g1_crosscheck_diff_equals_independent_computation(g1_out):
    d, p = g1_out

    def leaves(prefix, obj):
        if isinstance(obj, dict):
            res = {}
            for k, v in obj.items():
                res.update(leaves(prefix + "." + k if prefix else k, v))
            return res
        if isinstance(obj, list):
            res = {}
            for i, v in enumerate(obj):
                res.update(leaves("%s[%d]" % (prefix, i), v))
            return res
        return {prefix: obj}

    a, b = leaves("", jload(EV)["perKind"]), leaves("", jload(RS)["heldout"])
    expected = sorted(k for k in set(a) | set(b) if k not in a or k not in b or a[k] != b[k])
    got = [r["json_path"] for r in rows_of(d / "crosscheck-eval-vs-results-json.csv")]
    assert got == expected and len(expected) > 0
    summary = jload(d / "er-summary.json")
    assert summary["crosscheck_json_differences"] == len(expected)
    assert "json_differences=%d" % len(expected) in p.stdout


def test_g1_md_crosscheck_has_no_differences_and_checks_nine_items(g1_out):
    d, _ = g1_out
    s = jload(d / "er-summary.json")
    assert s["crosscheck_md_items_checked"] == 9  # per kind: 1 confusion + 3 paths = 4, x2 kinds = 8, plus researcher precision/recall/F1 = 1
    assert s["crosscheck_md_differences"] == 0
    assert rows_of(d / "crosscheck-eval-vs-results-md.csv") == []


def test_g1_summary_values_are_copied_not_recomputed(g1_out):
    d, _ = g1_out
    s, ev = jload(d / "er-summary.json"), jload(EV)
    assert s["per_kind"]["researcher"]["tp"] == ev["perKind"]["researcher"]["confusion"]["tp"]
    assert s["per_kind"]["work"]["precision"] == NM and s["per_kind"]["work"]["f1"] == NM  # repair g1: Phase 05 null -> "not measurable"
    assert s["per_kind"]["work"]["recall"] == jload(RS)["heldout"]["work"]["confusion"]["recall"]
    assert s["per_kind"]["researcher"]["precision"] == jload(RS)["heldout"]["researcher"]["confusion"]["precision"]
    assert s["policy"] == ev["policyVersion"] and s["held_out_rows"] == ev["heldOutRows"] and s["llm_used"] == ev["llmUsed"]
    paths = rows_of(d / "er-by-path.csv")
    assert len(paths) == 8
    llm = [r for r in paths if r["path"] == "llm"]
    assert len(llm) == 2 and all(r["phase05_note_or_status"] == "NOT RUN" and r["n"] == "0" and r["precision"] == NM for r in llm)
    split_findings = [f for f in s["findings"] if f.startswith("COMPARISON with requirement")]
    assert len(split_findings) == 1 and "instructor-approvals.md:" in split_findings[0] and "NOT FOUND" not in split_findings[0]  # repair g1: reworded as a comparison


def test_g1_split_counts_match_committed_summary(g1_out):
    d, _ = g1_out
    sp = jload(os.path.join(REPO, "modules", "entity-resolution", "gold", "gold-split-v1.summary.json"))
    rows = rows_of(d / "er-gold-split-counts.csv")
    assert [(r["kind"], r["label"], r["split"], int(r["count"])) for r in rows] == [(x["kind"], x["label"], x["split"], x["count"]) for x in sp["counts"]]
    assert sum(int(r["count"]) for r in rows if r["split"] == "calibration") == sp["calibration"]
    assert sum(int(r["count"]) for r in rows if r["split"] == "heldout") == sp["heldout"]


# ------------------------------------------------------------------------------------------------------------------ g2 fake load
def write_csv_file(path, header, rows):
    os.makedirs(os.path.dirname(str(path)), exist_ok=True)
    with open(str(path), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def write_jsonl(path, objs):
    os.makedirs(os.path.dirname(str(path)), exist_ok=True)
    with open(str(path), "w") as f:
        for o in objs:
            f.write(json.dumps(o) + "\n")


LABELS = ["Researcher", "Work", "Venue", "Topic", "Institution", "Community", "Component", "ExternalIdentity"]
ETYPES = ["AUTHORED", "COAUTHORED_WITH", "COMMUNITY_LINK", "HAS_TOPIC", "IN_COMPONENT", "MEMBER_OF", "PUBLISHED_IN", "RESOLVES_TO"]
ALL = "1000-9999"
W = "work:dblp:"  # Work ids carry the DBLP prefix so the provenance audit can derive the XML record key
EH = [":ID", ":START_ID", ":END_ID"]
COA_H = EH + ["window:String", "jointPublicationCount:Int", "supportingWorksTruncated:Bool"]


def evidence(edge, work, window=ALL, lo=1000, hi=9999):
    return {"pk": "edge:" + edge, "sk": "work:" + work, "attributes": {"edgeId": edge, "workId": work, "windowStart": lo, "windowEnd": hi, "window": window}}


def build_fake_main(root):
    """Hand-built graph.
    Researchers r1..r4; Works w1 (2001), w2 (2002), w3 (2003); Venue v1.
    AUTHORED: r1,r2 -> w1; r1,r2 -> w2; r3 -> w9 (w9 is NOT a Work vertex: one dangling END_ID).  r4 has no AUTHORED (orphan researcher); w3 has none (orphan work).
    COAUTHORED_WITH (window all-time): c1 r1-r2 count 2 evidence w1,w2 (supported); c2 r1-r3 count 1 evidence w1 but r3 did not author w1 (unsupported);
    c3 r2-r3 no evidence (unsupported); c4 r2-r1 = reverse duplicate of c1, no evidence (unsupported, duplicate pair).
    """
    load = os.path.join(str(root), "modules", "storage", "target", "loads", "L5")
    nep, ddb = os.path.join(load, "neptune"), os.path.join(load, "dynamodb", "items")
    verts = {"Researcher": [["r1"], ["r2"], ["r3"], ["r4"]], "Venue": [["v1"]]}
    for lab in LABELS:
        if lab == "Work":
            write_csv_file(os.path.join(nep, "vertices", "Work.csv"), [":ID", "year:Int", "title:String"], [[W + "w1", "2001", "T1"], [W + "w2", "2002", "T2"], [W + "w3", "2003", "T3"]])
        else:
            write_csv_file(os.path.join(nep, "vertices", lab + ".csv"), [":ID"], verts.get(lab, []))
    edges = {"AUTHORED": [["a1", "r1", W + "w1"], ["a2", "r2", W + "w1"], ["a3", "r1", W + "w2"], ["a4", "r2", W + "w2"], ["a5", "r3", W + "w9"]]}
    for et in ETYPES:
        if et == "COAUTHORED_WITH":
            rows = [["c1", "r1", "r2", ALL, "2", "false"], ["c2", "r1", "r3", ALL, "1", "false"], ["c3", "r2", "r3", ALL, "1", "false"], ["c4", "r2", "r1", ALL, "1", "false"]]
            write_csv_file(os.path.join(nep, "edges", et + ".csv"), COA_H, rows)
        else:
            write_csv_file(os.path.join(nep, "edges", et + ".csv"), EH, edges.get(et, []))
    write_jsonl(os.path.join(ddb, "coauthor-edge-works.jsonl"), [evidence("c1", W + "w1"), evidence("c1", W + "w2"), evidence("c2", W + "w1")])
    for n in ("researcher-metrics", "paths", "mention-researcher"):
        write_jsonl(os.path.join(ddb, n + ".jsonl"), [])
    counts = {"Researcher": 4, "Work": 3, "Venue": 1, "AUTHORED": 5, "COAUTHORED_WITH": 4}
    files = [{"label": k, "rows": counts.get(k, 0)} for k in LABELS + ETYPES]
    files = [f for f in files if f["label"] != "Work" or True]
    os.makedirs(load, exist_ok=True)
    with open(os.path.join(load, "load-manifest.json"), "w") as f:
        json.dump({"loadRunId": "LOAD-T", "codeVersion": "code-T", "input": {"sparkRunId": "SPARK-T", "sparkCodeVersion": "spark-code-T"}, "neptune": {"files": files}}, f)  # repair g2: the script now copies these fields into graph-summary.json
    return str(root)


@pytest.fixture
def g2_run(tmp_path):
    main = build_fake_main(tmp_path / "fake-main")
    out_root = tmp_path / "out"
    p = run_eval("g2", out_root, ["--run-id", "t1", "--main-checkout", main, "--sample", "2", "--seed", "7"])
    assert p.returncode == 0, p.stderr
    return out_root / "g2-graph-correctness" / "t1", p


def test_g2_dangling_endpoint(g2_run):
    d, _ = g2_run
    ri = {r["edge_type"]: r for r in rows_of(d / "referential-integrity.csv")}
    assert ri["AUTHORED"]["edges"] == "5" and ri["AUTHORED"]["dangling_endpoint"] == "1" and ri["AUTHORED"]["dangling_rate"] == "0.2000"
    assert ri["COAUTHORED_WITH"]["dangling_endpoint"] == "0" and ri["COAUTHORED_WITH"]["dangling_rate"] == "0.0000"
    assert ri["HAS_TOPIC"]["edges"] == "0" and ri["HAS_TOPIC"]["dangling_rate"] == ""  # 0 of 0 edges: rate undefined, not 0
    s = jload(d / "graph-summary.json")["referential_integrity"]
    assert s == {"edges_total": 9, "dangling_total": 1, "dangling_rate": pytest.approx(1 / 9.0)}


def test_g2_orphans(g2_run):
    d, _ = g2_run
    o = jload(d / "graph-summary.json")["orphans"]
    assert o["researchers_without_AUTHORED"] == 1 and o["researchers"] == 4 and o["researcher_orphan_rate"] == pytest.approx(0.25)  # r4
    assert o["works_without_AUTHORED"] == 1 and o["works"] == 3 and o["work_orphan_rate"] == pytest.approx(1 / 3.0)  # w3
    # no incident edge at all: r4, w3, v1
    assert o["vertices_with_no_incident_edge_by_label"]["Researcher"] == 1
    assert o["vertices_with_no_incident_edge_by_label"]["Work"] == 1
    assert o["vertices_with_no_incident_edge_by_label"]["Venue"] == 1
    assert o["overall_no_incident_edge_rate"] == pytest.approx(3 / 8.0)  # 8 vertex rows (4+3+1)


def test_g2_coauthor_edge_support(g2_run):
    d, _ = g2_run
    sup = jload(d / "graph-summary.json")["coauthor_edge_support"]
    assert sup["edges"] == 4 and sup["with_evidence_rows"] == 2 and sup["fully_supported"] == 1
    assert sup["support_rate"] == pytest.approx(0.25)
    assert sup["failure_reasons"]["endpoint_not_author_of_supporting_work"] == 1  # c2: r3 never authored w1
    assert sup["failure_reasons"]["no_evidence_rows"] == 2  # c3, c4
    assert sup["jointPublicationCount_equals_evidence_rows_when_not_truncated"] == 2  # c1 (2 == 2 rows) and c2 (1 == 1 row)
    # independent all-time pairs from AUTHORED: only {r1,r2} (w1, w2); r3 is alone on w9. Loaded: (r1,r2), (r1,r3), (r2,r3)
    assert sup["independent_alltime_pairs_from_AUTHORED"] == 1 and sup["loaded_alltime_pairs"] == 3
    assert sup["expected_missing_from_load"] == 0 and sup["loaded_not_expected"] == 2


def test_g2_duplicates_and_check_verdicts(g2_run):
    d, p = g2_run
    dup = jload(d / "graph-summary.json")["duplicates"]
    assert dup["dup_unordered_pair_window_rows"] == 1 and dup["ordered_duplicates"] == 1  # c4 reverses c1
    assert dup["vertex_ids_in_more_than_one_label"] == 0 and dup["vertex_rows"] == 8 and dup["edge_rows"] == 9
    checks = {r["check"].split(" ")[0]: r for r in rows_of(d / "graph-correctness-checks.csv")}
    assert checks["GC-01"]["verdict"] == "PASS" and checks["GC-01"]["observed"] == "0 of 4"
    assert checks["GC-02"]["verdict"] == "FAIL" and checks["GC-02"]["observed"] == "1 / 1"
    assert checks["GC-06"]["verdict"] == "PASS"  # fake manifest row counts equal the rows written
    assert "support_rate=0.2500 fully=1/4" in p.stdout


def test_g2_refuses_when_inputs_missing(tmp_path):
    p = run_eval("g2", tmp_path / "out", ["--run-id", "t1", "--main-checkout", str(tmp_path / "does-not-exist")])
    assert p.returncode == 2 and "SC-REFUSED input(s) missing" in p.stderr
    assert not os.path.exists(str(tmp_path / "out" / "g2-graph-correctness" / "t1" / "meta.json"))


def test_g2_load_manifest_fields_copied(g2_run):
    d, _ = g2_run
    assert jload(d / "graph-summary.json")["load_manifest_fields"] == {"loadRunId": "LOAD-T", "codeVersion": "code-T", "input.sparkRunId": "SPARK-T", "input.sparkCodeVersion": "spark-code-T"}


# ---- g2 seeded provenance audit (code-reviewer M5), hand-built Hadoop mentions and DBLP xml
XML_REL = os.path.join("datasets", "raw", "dblp", "derived", "dblp_selection_1000.xml")
XML_TEXT = ('<dblp>\n<article key="w1"><title>T1</title></article>\n<article key="w2"><title>T2</title></article>\n'
            '<article key="w9"><title>T9</title></article>\n</dblp>\n')


def xml_offset(key):
    return XML_TEXT.encode().index(('<article key="%s"' % key).encode())


def mention(mid, work, pos, name, offset, checksum, input_id=XML_REL, version="dblp-2026-v1", run="H1"):
    return {"mentionId": mid, "workId": work, "position": pos, "rawName": name,
            "provenance": {"inputs": [{"inputId": input_id, "version": version, "checksum": checksum}], "parameters": {"inputByteOffset": str(offset)}, "runId": run}}


def build_prov(main, recorded_checksum=None, dblp_input=True, crossref_extra=False):
    """Adds the audit inputs to a fake main checkout.
    Evidence (overwritten): c1:w1 ; c2:w1 (r3 never authored w1) ; c3: none ; c4:w2 (r2 has no mention for w2).
    Mentions (mention-researcher maps m1,m3 -> r1 and m2 -> r2): w1 r1 m1 and r2 m2 point at the w1 record; w2 r1 m3 points at the w2 record.
    Expected audit over all four edges: c1 pass; c2 fail (b not author); c3 fail (no evidence); c4 fail (b untraced) -> 1 of 4 edges traced, 4 rows, 3 failed."""
    main = str(main)
    xml_path = os.path.join(main, XML_REL)
    os.makedirs(os.path.dirname(xml_path))
    with open(xml_path, "w") as f:
        f.write(XML_TEXT)
    xsha = hashlib.sha256(XML_TEXT.encode()).hexdigest()
    cs = recorded_checksum if recorded_checksum is not None else xsha
    ddb = os.path.join(main, "modules", "storage", "target", "loads", "L5", "dynamodb", "items")
    write_jsonl(os.path.join(ddb, "coauthor-edge-works.jsonl"), [evidence("c1", W + "w1"), evidence("c2", W + "w1"), evidence("c4", W + "w2")])
    ments = []
    if dblp_input:
        ments = [mention("m1", W + "w1", 1, "R One", xml_offset("w1"), cs), mention("m2", W + "w1", 2, "R Two", xml_offset("w1"), cs),
                 mention("m3", W + "w2", 1, "R One", xml_offset("w2"), cs)]
    if crossref_extra:
        ments.append(mention("m9", W + "w9", 1, "R Nine", 0, "deadbeef-not-the-xml-sha", input_id="datasets/raw/crossref/sample.json", version="crossref-v0"))
    part = os.path.join(main, "modules", "hadoop", "target", "runs", "rootI", "curated", "author-mentions", "part-00000.gz")
    os.makedirs(os.path.dirname(part))
    with gzip.open(part, "wt", encoding="utf-8") as f:
        for m in ments:
            f.write(json.dumps(m) + "\n")
    write_jsonl(os.path.join(ddb, "mention-researcher.jsonl"), [{"pk": "m1", "attributes": {"researcherId": "r1"}}, {"pk": "m2", "attributes": {"researcherId": "r2"}},
                                                                {"pk": "m3", "attributes": {"researcherId": "r1"}}, {"pk": "m9", "attributes": {"researcherId": "r4"}}])
    return xsha


def run_g2(tmp_path, main, run_id="t1", sample=4, seed=7):
    out_root = tmp_path / "out"
    p = run_eval("g2", out_root, ["--run-id", run_id, "--main-checkout", str(main), "--sample", str(sample), "--seed", str(seed)])
    assert p.returncode == 0, p.stderr
    return out_root / "g2-graph-correctness" / run_id, p


def test_g2_audit_trace_pass_and_failures_hand_computed(tmp_path):
    main = build_fake_main(tmp_path / "fake-main")
    xsha = build_prov(main)
    d, p = run_g2(tmp_path, main)
    a = jload(d / "provenance-audit.json")
    assert a["seed"] == 7 and a["sample_size"] == 4 and a["population_size"] == 4 and a["edges_sampled"] == 4
    assert a["edges_fully_traced"] == 1 and a["evidence_rows_audited"] == 4 and a["evidence_rows_failed"] == 3
    assert a["xml_sha256_observed"] == xsha
    rows = {r["edge_id"]: r for r in a["rows"]}
    c1 = rows["c1"]
    assert c1["pass"] is True and c1["traced_to_source_record"] is True and c1["work_in_Work_csv"] is True
    ta = c1["source_trace_a"][0]
    assert ta["xml_record_key_at_offset"] == "w1" and ta["record_key_matches_work_id"] is True and ta["byte_offset"] == xml_offset("w1")
    assert ta["source_checksum"] == xsha and ta["source_file"] == XML_REL and ta["hadoop_run_id"] == "H1" and ta["mention_id"] == "m1"
    assert c1["source_trace_b"][0]["mention_id"] == "m2"
    assert rows["c2"]["pass"] is False and rows["c2"]["a_authored_work"] is True and rows["c2"]["b_authored_work"] is False  # r3 never authored w1
    assert rows["c3"] == {"edge_id": "c3", "pass": False, "reason": "no evidence rows"}
    assert rows["c4"]["pass"] is False and rows["c4"]["a_authored_work"] and rows["c4"]["b_authored_work"] and rows["c4"]["traced_to_source_record"] is False
    assert rows["c4"]["source_trace_a"] == [] and len(rows["c4"]["source_trace_b"]) == 1  # c4 is r2 -> r1: side a (r2) has no mention for w2, side b (r1) has m3
    csv_rows_ = {r["edge_id"]: r for r in rows_of(d / "provenance-audit.csv")}
    assert [csv_rows_[k]["pass"] for k in ("c1", "c2", "c3", "c4")] == ["True", "False", "False", "False"]
    s = jload(d / "graph-summary.json")["provenance_audit"]
    assert s["edges_fully_traced"] == 1 and s["evidence_rows_audited"] == 4 and s["evidence_rows_failed"] == 3
    assert "audit_edges_traced=1/4 rows_failed=3" in p.stdout


def test_g2_audit_trace_fails_when_offset_points_at_other_record(tmp_path):
    main = build_fake_main(tmp_path / "fake-main")
    build_prov(main)
    # rewrite m2 so that the byte offset lands on the w2 record: r2's trace for w1 now reads key w2 -> c1 can no longer be traced
    part = os.path.join(str(main), "modules", "hadoop", "target", "runs", "rootI", "curated", "author-mentions", "part-00000.gz")
    with gzip.open(part, "rt", encoding="utf-8") as f:
        ms = [json.loads(x) for x in f]
    for m in ms:
        if m["mentionId"] == "m2":
            m["provenance"]["parameters"]["inputByteOffset"] = str(xml_offset("w2"))
    with gzip.open(part, "wt", encoding="utf-8") as f:
        for m in ms:
            f.write(json.dumps(m) + "\n")
    d, _ = run_g2(tmp_path, main)
    a = jload(d / "provenance-audit.json")
    c1 = [r for r in a["rows"] if r["edge_id"] == "c1"][0]
    assert c1["source_trace_b"][0]["xml_record_key_at_offset"] == "w2" and c1["source_trace_b"][0]["record_key_matches_work_id"] is False
    assert c1["traced_to_source_record"] is False and c1["pass"] is False
    assert a["edges_fully_traced"] == 0 and a["evidence_rows_failed"] == 4


def test_g2_audit_sample_is_seeded_and_sized(tmp_path):
    main = build_fake_main(tmp_path / "fake-main")
    build_prov(main)
    pop = ["c1", "c2", "c3", "c4"]

    def sampled(run_id, seed):
        d, _ = run_g2(tmp_path, main, run_id=run_id, sample=2, seed=seed)
        a = jload(d / "provenance-audit.json")
        assert a["sample_size"] == 2 and a["edges_sampled"] == 2 and a["population_size"] == 4
        ids = []
        for r in a["rows"]:
            if r["edge_id"] not in ids:
                ids.append(r["edge_id"])
        return ids
    exp7 = random.Random(7).sample(pop, 2)  # documented rule: sorted ids, random.Random(seed).sample
    assert sampled("a", 7) == exp7 and sampled("b", 7) == exp7  # deterministic across runs
    other = next(sd for sd in range(100) if set(random.Random(sd).sample(pop, 2)) != set(exp7))
    assert set(sampled("c", other)) == set(random.Random(other).sample(pop, 2)) != set(exp7)  # the seed changes the sample


def test_g2_audit_checksum_comparison_restricted_to_dblp_selection_input(tmp_path):
    # repair g2: a non-DBLP input recorded with a different checksum must not flip the flag
    main = build_fake_main(tmp_path / "m1")
    build_prov(main, crossref_extra=True)
    d, _ = run_g2(tmp_path / "r1", main)
    a = jload(d / "provenance-audit.json")
    assert a["xml_sha256_matches_recorded_for_dblp_selection_input"] is True
    recorded = {r["inputId"]: r for r in a["provenance_inputs0_recorded_in_hadoop_author_mentions"]}
    assert set(recorded) == {XML_REL, "datasets/raw/crossref/sample.json"} and recorded[XML_REL]["mention_rows"] == 3
    assert recorded["datasets/raw/crossref/sample.json"]["checksum"] == "deadbeef-not-the-xml-sha"
    # DBLP input recorded with a wrong checksum: False
    main2 = build_fake_main(tmp_path / "m2")
    build_prov(main2, recorded_checksum="0" * 64)
    d2, _ = run_g2(tmp_path / "r2", main2)
    assert jload(d2 / "provenance-audit.json")["xml_sha256_matches_recorded_for_dblp_selection_input"] is False
    # no DBLP input recorded at all (only crossref): False, not vacuously True
    main3 = build_fake_main(tmp_path / "m3")
    build_prov(main3, dblp_input=False, crossref_extra=True)
    d3, _ = run_g2(tmp_path / "r3", main3)
    a3 = jload(d3 / "provenance-audit.json")
    assert a3["xml_sha256_matches_recorded_for_dblp_selection_input"] is False and a3["edges_fully_traced"] == 0


def test_g2_audit_without_hadoop_or_xml_fails_traces_and_lists_unavailable(g2_run):
    d, _ = g2_run  # fake main has neither the Hadoop run nor the xml
    a = jload(d / "provenance-audit.json")
    assert a["xml_sha256_observed"] is None and a["xml_sha256_matches_recorded_for_dblp_selection_input"] is False
    assert a["edges_fully_traced"] == 0 and a["population_size"] == 4 and a["edges_sampled"] == 2
    na = jload(d / "meta.json")["not_available_inputs"]
    assert any(x.endswith("dblp_selection_1000.xml") for x in na) and any("curated author-mentions" in x for x in na)


# ------------------------------------------------------------------------------------------------------------------ g3 retrieval math
def smoke(queries):
    return {"queries": queries}


def q(expected, top, typ, rank):
    return {"expectedKey": expected, "topKeys": top, "documentType": typ, "expectedRank": rank}


def test_g3_recall_mrr_ndcg_hand_computed(tmp_path):
    main = tmp_path / "main"
    run_dir = main / "modules" / "services" / "target" / "embedding-runs" / "L1"
    os.makedirs(str(run_dir))
    queries = [q("A", ["B", "A", "C", "D", "E"], "work", 2),      # rank 2: recall@1 0, @3 1, @5 1; RR .5; nDCG@5 = 1/log2(3)
               q("X", ["X", "P", "Q", "R", "S"], "work", 1),      # rank 1: all 1
               q("Y", ["P", "Q", "R", "S", "T"], "researcher", None)]  # absent: all 0
    (run_dir / "smoke.json").write_text(json.dumps(smoke(queries)))
    p = run_eval("g3", tmp_path / "out", ["--run-id", "t1", "--main-checkout", str(main)])
    assert p.returncode == 0, p.stderr
    d = tmp_path / "out" / "g3-retrieval" / "t1"
    rows = {(r["embedding_run"], r["document_type"]): r for r in rows_of(d / "retrieval-vector-only-local.csv")}
    assert set(rows) == {("L1", "ALL"), ("L1", "work"), ("L1", "researcher")}  # L2..L4 absent -> skipped, listed as not available in meta
    n1 = 1 / math.log2(3)
    allr, work, res = rows[("L1", "ALL")], rows[("L1", "work")], rows[("L1", "researcher")]
    assert allr["n_queries"] == "3"
    assert allr["recall@1"] == "%.4f" % (1 / 3.0) and allr["recall@3"] == "%.4f" % (2 / 3.0) and allr["recall@5"] == "%.4f" % (2 / 3.0)
    assert allr["mrr_top5"] == "%.4f" % ((0.5 + 1.0 + 0.0) / 3) and allr["ndcg@5"] == "%.4f" % ((n1 + 1.0 + 0.0) / 3)
    assert work["n_queries"] == "2" and work["recall@1"] == "0.5000" and work["recall@3"] == "1.0000" and work["mrr_top5"] == "0.7500"
    assert work["ndcg@5"] == "%.4f" % ((n1 + 1.0) / 2)
    assert res["n_queries"] == "1" and res["recall@5"] == "0.0000" and res["mrr_top5"] == "0.0000" and res["ndcg@5"] == "0.0000"
    meta = jload(d / "meta.json")
    assert len(meta["not_available_inputs"]) == 3  # L2, L3, L4 smoke.json
    pend = rows_of(d / "retrieval-modes-pending.csv")
    assert len(pend) == 4 and all(r["recall@K"] == "[[PENDING-AWS]]" for r in pend)


def test_g3_refuses_without_any_smoke(tmp_path):
    p = run_eval("g3", tmp_path / "out", ["--run-id", "t1", "--main-checkout", str(tmp_path / "nothing")])
    assert p.returncode == 2 and "SC-REFUSED no smoke.json" in p.stderr


# ------------------------------------------------------------------------------------------------------------------ g4 answer quality (hand-computed, in-process, benchmark ROOT monkeypatched)
def _claim(status, *codes):
    return {"status": status, "reasonCodes": list(codes)}


def build_g4(tmp_path):
    """Four answered questions. Hand counts (see the asserts in test_g4_*):
    q1 identity   : claims verified[ok] x2, partially_supported[thin]; identity fact Ada/Lovelace ok (in text, resolved); numeric metrics fact 5; forbidden ['Bob',''] no hit; checks fact ok, assert FAILED; not abstained, abstain not expected
    q2 ambiguity  : claim unsupported[no_evidence, low_score]; abstained and expected; candidates c1,c2: text lists only c2 -> 1 of 2; forbidden ['Ada'] HIT; check fact FAILED; harness pass False
    q3 identity   : claim verified[ok]; identity fact Bob/Builder: not in resolvedIds -> not ok; abstained but NOT expected (false positive); check assert ok
    q4 metrics    : no claims; abstain expected but not abstained (false negative); non-numeric metrics fact (not counted)
    """
    fake_root, main = tmp_path / "fake-repo", tmp_path / "main"
    bdir = fake_root / "modules" / "rag" / "src" / "main" / "resources" / "benchmarks"
    bdir.mkdir(parents=True)
    bench = {"questions": [
        {"id": "q1", "expected": {"facts": [{"kind": "identity", "subject": "Ada", "value": "Lovelace"}, {"kind": "metrics", "value": 5}], "forbiddenInAnswer": ["Bob", ""]}},
        {"id": "q2", "expected": {"abstain": True, "candidateIds": ["c1", "c2"], "forbiddenInAnswer": ["Ada"]}},
        {"id": "q3", "expected": {"facts": [{"kind": "identity", "subject": "Bob", "value": "Builder"}]}},
        {"id": "q4", "expected": {"abstain": True, "facts": [{"kind": "metrics", "value": "high"}]}}]}
    (bdir / "benchmark-v1.json").write_text(json.dumps(bench))
    qd = main / "modules" / "services" / "target" / "phase09-benchmark" / "R2" / "questions"
    qd.mkdir(parents=True)
    ans = [
        {"id": "q1", "category": "identity", "answerText": "Ada Lovelace wrote it", "resolvedIds": ["Ada"], "candidateIds": [], "abstained": False, "pass": True,
         "claims": [_claim("verified", "ok"), _claim("verified", "ok"), _claim("partially_supported", "thin")], "checks": [{"name": "fact:a", "ok": True}, {"name": "assert:b", "ok": False}]},
        {"id": "q2", "category": "ambiguity", "answerText": "Ada candidate", "resolvedIds": [], "candidateIds": ["c2"], "abstained": True, "abstainReason": "AMBIG", "pass": False,
         "claims": [_claim("unsupported", "no_evidence", "low_score")], "checks": [{"name": "fact:x", "ok": False}]},
        {"id": "q3", "category": "identity", "answerText": "Bob the Builder", "resolvedIds": [], "candidateIds": [], "abstained": True, "pass": True,
         "claims": [_claim("verified", "ok")], "checks": [{"name": "assert:y:z", "ok": True}]},
        {"id": "q4", "category": "metrics", "answerText": "none", "resolvedIds": [], "candidateIds": [], "abstained": False, "pass": True, "claims": [], "checks": []}]
    for a in ans:
        (qd / (a["id"] + ".json")).write_text(json.dumps(a))
    return str(fake_root), str(main)


@pytest.fixture
def g4_out(le, tmp_path, monkeypatch):
    fake_root, main = build_g4(tmp_path)
    monkeypatch.setattr(le, "ROOT", fake_root)
    out_root = tmp_path / "out"
    assert le.main(["g4", "--out-root", str(out_root), "--run-id", "t1", "--main-checkout", main]) == 0
    return out_root / "g4-answer-quality" / "t1"


def test_g4_claim_support_and_status_counts(g4_out):
    s = jload(g4_out / "answer-quality-summary.json")
    assert s["questions"] == 4 and s["harness_all_checks_passed"] == 3
    assert s["claims_total"] == 5 and s["claims_verified"] == 3 and s["claim_support_rate"] == pytest.approx(0.6)
    assert s["claim_status_counts"] == {"verified": 3, "partially_supported": 1, "unsupported": 1}
    assert s["reason_code_counts"] == {"ok": 3, "thin": 1, "no_evidence": 1, "low_score": 1}
    assert s["contradiction"]["claims_with_status_other_than_verified_or_partially_supported"] == 1 and s["contradiction"]["rate"] == pytest.approx(0.2)
    assert s["evidence_class"].startswith("F")


def test_g4_abstention_confusion_matrix(g4_out):
    ab = jload(g4_out / "answer-quality-summary.json")["abstention"]
    # q1 not expected/not abstained = tn ; q2 expected+abstained = tp ; q3 not expected but abstained = fp ; q4 expected but not abstained = fn
    assert (ab["tp"], ab["fp"], ab["fn"], ab["tn"]) == (1, 1, 1, 1)
    assert ab["precision"] == pytest.approx(0.5) and ab["recall"] == pytest.approx(0.5)
    assert ab["ambiguous_candidate_ids_expected"] == 2 and ab["ambiguous_candidate_ids_listed"] == 1  # c2 listed, c1 neither listed nor in the text


def test_g4_identifier_forbidden_numeric_and_harness(g4_out):
    s = jload(g4_out / "answer-quality-summary.json")
    assert s["identifier_accuracy"] == {"identity_facts": 2, "identity_facts_with_id_and_name_in_answer_and_resolved": 1, "rate": pytest.approx(0.5)}  # q3: Bob not in resolvedIds
    assert s["forbidden_in_answer"] == {"strings_checked": 3, "violations": 1}  # q1 ['Bob',''] no hit (empty string never a hit); q2 'Ada' hit
    n = s["numerical_accuracy"]
    assert n["numeric_metric_facts_in_benchmark"] == 1  # q1 value 5; q4 'high' is not numeric
    assert (n["harness_fact_checks_ok"], n["harness_fact_checks_failed"], n["harness_assert_checks_ok"], n["harness_assert_checks_failed"]) == (1, 1, 1, 1)


def test_g4_category_status_breakdown_new_in_repair_g4(g4_out):
    rows = rows_of(g4_out / "claims-by-category-status.csv")
    assert [(r["category"], r["claim_status"], r["reason_code"], r["claims"]) for r in rows] == [
        ("ambiguity", "unsupported", "low_score", "1"), ("ambiguity", "unsupported", "no_evidence", "1"),
        ("identity", "partially_supported", "thin", "1"), ("identity", "verified", "ok", "3")]
    s = jload(g4_out / "answer-quality-summary.json")
    assert s["claims_not_verified_by_category_and_status"] == {"ambiguity": {"unsupported": 1}, "identity": {"partially_supported": 1}}
    assert s["questions_with_unverified_claims_by_category"] == {"identity": ["q1 (1 of 3 claims not verified)"], "ambiguity": ["q2 (1 of 1 claims not verified)"]}
    # one row per (category, status, reason code): a claim carrying two reason codes (q2) appears in two rows, so the sum is the reason-code total (6), not the claim total (5)
    assert sum(int(r["claims"]) for r in rows) == sum(s["reason_code_counts"].values()) == 6 and s["claims_total"] == 5
    assert {(r["category"], r["claim_status"], r["reason_code"]): r["claims"] for r in
            [{"category": x["category"], "claim_status": x["status"], "reason_code": x["reason_code"], "claims": str(x["claims"])} for x in s["reason_codes_by_category_status"]]} == \
        {(r["category"], r["claim_status"], r["reason_code"]): r["claims"] for r in rows}  # summary and csv agree
    pq = {r["question_id"]: r for r in rows_of(g4_out / "per-question.csv")}
    assert (pq["q1"]["claims"], pq["q1"]["claims_verified"], pq["q4"]["claims"]) == ("3", "2", "0")
    assert pq["q2"]["abstain_reason"] == "AMBIG" and pq["q2"]["harness_pass"] == "False"
    sheet = rows_of(g4_out / "human-rating-sheet-EMPTY.csv")
    assert len(sheet) == 4 and all(r["correctness_1_to_5"] == "" and r["rater"] == "" for r in sheet)


def test_g4_refuses_without_questions(le, tmp_path, monkeypatch):
    fake_root, main = build_g4(tmp_path)
    for f in os.listdir(os.path.join(main, "modules", "services", "target", "phase09-benchmark", "R2", "questions")):
        os.remove(os.path.join(main, "modules", "services", "target", "phase09-benchmark", "R2", "questions", f))
    monkeypatch.setattr(le, "ROOT", fake_root)
    assert le.main(["g4", "--out-root", str(tmp_path / "out"), "--run-id", "t1", "--main-checkout", main]) == 2


# ------------------------------------------------------------------------------------------------------------------ g6 regexes
def test_g6_status_regex_blocked_on_extraction(le):
    m = le.STATUS_RE.match
    assert m("STATUS: BLOCKED-ON").group(1) == "BLOCKED-ON"
    assert m("**STATUS:** COMPLETE").group(1) == "COMPLETE"
    assert m("  STATUS: FAILED").group(1) == "FAILED"
    assert m("STATUS COMPLETE").group(1) == "COMPLETE"
    assert m("STATUS: BLOCKED-ON | COMPLETE").group(1) == "BLOCKED-ON"
    assert m("The STATUS: BLOCKED-ON line quoted mid-sentence") is None  # mid-line quotes are not events
    assert m("STATUS: PENDING") is None and m("- STATUS: COMPLETE") is None


def test_g6_verdict_regex(le):
    def v(line):
        mv = le.VERDICT_RE.search(line)
        return mv.group(1).upper().replace(" ", "_") if mv else None
    assert v("Verdict: APPROVE WITH NOTES") == "APPROVE_WITH_NOTES"
    assert v("**Verdict: APPROVE**") == "APPROVE"
    assert v("Verdict: REQUEST CHANGES") == "REQUEST_CHANGES"
    assert v("verdict (round 2): REQUEST_CHANGES") == "REQUEST_CHANGES"
    assert v("The verdict on the repair: APPROVE") == "APPROVE"
    assert v("VERDICT is now APPROVE-RECOMMENDED") == "APPROVE-RECOMMENDED"
    assert v("no judgement here") is None
    assert v("verdict pending, will APPROVE later") is None  # more than 12 non-letters / words between: not a verdict
    assert le.VERDICT_SKIP.match("NEXT GATE: verdict APPROVE") and le.VERDICT_SKIP.match("  validation results: verdict APPROVE")
    assert not le.VERDICT_SKIP.match("Verdict: APPROVE")


def test_g6_verdict_changes_required_and_normalisation(le):
    # repair g6: CHANGES-REQUIRED wording is recognised and normalised to REQUEST_CHANGES
    for line in ("Verdict: CHANGES REQUIRED", "verdict: changes-required", "Verdict: CHANGES_REQUIRED"):
        assert le.norm_verdict(le.VERDICT_RE.search(line).group(1)) == "REQUEST_CHANGES", line
    assert le.norm_verdict("APPROVE WITH NOTES") == "APPROVE_WITH_NOTES" and le.norm_verdict("approve") == "APPROVE"


def test_g6_enumerated_finding_lines_regex(le):
    """Repair g6: the severity-tagged enumeration regexes (ENUM_RES) replace the removed SEV_RE; they only feed the non-summed per-phase column."""
    def sev(line):
        for rx in le.ENUM_RES:
            m = rx.match(line)
            if m:
                return m.group(1).upper()
        return None
    for line, want in (("- **F1** (HIGH) ownership gap", "HIGH"), ("* B2 (MEDIUM) note", "MEDIUM"), ("1. S3 (LOW)", "LOW"), ("| N-4 (INFO) row", "INFO"), ("- F1 HIGH ownership", "HIGH")):
        assert sev(line) == want, line
    assert sev("Plain text (HIGH) not a finding") is None
    assert not hasattr(le, "SEV_RE")  # the cross-phase tagged-severity tally is gone


def test_g6_stated_counts_hand_computed(le):
    assert le.stated_counts("Findings: 1 HIGH, 2 MEDIUM, 3 LOW") == [("HIGH", 1), ("MEDIUM", 2), ("LOW", 3)]
    assert le.stated_counts("1 HIGH") == [("HIGH", 1)]
    assert le.stated_counts("no HIGH, Nine MEDIUM") == [("HIGH", 0), ("MEDIUM", 9)]
    assert le.stated_counts("HIGH: 3") == [("HIGH", 3)]
    assert le.stated_counts("LOW 2-6 are finding ids") == []  # an id range is not a count
    assert le.stated_counts("no counts here") == []


def test_g6_stated_counts_severity_first_pair_is_not_double_parsed(le):
    """Adjacent pairs are parsed by both PAIR_NUM_FIRST and PAIR_SEV_FIRST. 'HIGH 0 MEDIUM 2' should give [HIGH 0, MEDIUM 2]; observed an extra ('MEDIUM', 0).
    '0 HIGH 4 MEDIUM' should give [HIGH 0, MEDIUM 4]; observed an extra ('HIGH', 4). Script defect, owner experiment-engineer; stated counts feed severity-by-phase.csv."""
    assert le.stated_counts("HIGH 0 MEDIUM 2") == [("HIGH", 0), ("MEDIUM", 2)]
    assert le.stated_counts("0 HIGH 4 MEDIUM") == [("HIGH", 0), ("MEDIUM", 4)]  # observed: [HIGH 0, HIGH 4, MEDIUM 4] ('HIGH 4' is read severity-first)


def build_agent_runs(root):
    """Synthetic docs/agent-runs tree. Line numbers are asserted below, so keep this text exactly as is."""
    base = os.path.join(str(root), "docs", "agent-runs")
    p4 = os.path.join(base, "phase-04-alpha")
    p5 = os.path.join(base, "phase-05-beta")
    p6 = os.path.join(base, "phase-06-gamma")
    p16a = os.path.join(base, "phase-16a-local-evaluation")
    for d in (p4, p5, p6, p16a):
        os.makedirs(d)
    open(os.path.join(p4, "prompt.md"), "w").write("STATUS: BLOCKED-ON\n")  # prompt.md must be ignored
    open(os.path.join(p4, "agent-report.md"), "w").write("\n".join([
        "STATUS: BLOCKED-ON",                                   # 1 counted blocked-on
        "OWNER: build-engineer",                                # 2
        "",                                                     # 3
        "**STATUS:** COMPLETE",                                 # 4
        "OWNER: test-engineer",                                 # 5
        "The orchestrator quoted: STATUS: BLOCKED-ON from DS",  # 6 midline candidate, not counted
        "The write was blocked by the guard.",                  # 7 guard_hook_block
        "The stop-hook demanded sbt.",                          # 8 stop_hook
    ]) + "\n")
    open(os.path.join(p4, "review-report.md"), "w").write("\n".join([
        "NEXT GATE: verdict APPROVE",                           # 1 skipped by VERDICT_SKIP
        "Verdict: APPROVE WITH NOTES",                          # 2 first verdict
        "- **F1** (HIGH) something",                            # 3
        "- **F2** (LOW) something",                             # 4
        "Review findings: 1 HIGH, 0 MEDIUM, 1 LOW",             # 5 stated counts (line must mention review or verdict to be read)
    ]) + "\n")
    open(os.path.join(p4, "human-gate.md"), "w").write("accepted\n")
    open(os.path.join(p5, "agent-report.md"), "w").write("STATUS: FAILED\nOWNER: rag-engineer\n")
    open(os.path.join(p5, "review-report.md"), "w").write("Verdict: REQUEST CHANGES\n")
    open(os.path.join(p5, "repair-01.md"), "w").write("rag-engineer fixed it\n")
    open(os.path.join(p6, "agent-report.md"), "w").write("STATUS: COMPLETE\nOWNER: spark-engineer\n")  # no review report
    open(os.path.join(p16a, "agent-report.md"), "w").write("STATUS: BLOCKED-ON\nOWNER: experiment-engineer\n")  # excluded phase
    return str(root)


def run_g6(le, tmp_path, monkeypatch, fake_root):
    monkeypatch.setattr(le, "ROOT", fake_root)
    out_root = tmp_path / "out"
    assert le.main(["g6", "--out-root", str(out_root), "--run-id", "t1"]) == 0
    return out_root / "g6-agentic-factory" / "t1"


def test_g6_in_process_on_synthetic_agent_runs(le, tmp_path, monkeypatch):
    d = run_g6(le, tmp_path, monkeypatch, build_agent_runs(tmp_path / "fake-repo"))
    s = jload(d / "factory-summary.json")
    assert s["evidence_class"].startswith("C-DOC")  # repair g6
    assert s["phases_scanned"] == 3  # phase-16a excluded
    assert s["blocked_on_status_lines"] == 1  # the midline quote, prompt.md and the 16a file are not counted
    assert s["status_lines_total"] == 4  # BLOCKED-ON, COMPLETE (alpha); FAILED (beta); COMPLETE (gamma)
    assert s["guard_hook_block_matching_lines"] == 1 and s["stop_hook_matching_lines"] == 1
    assert "review_findings_tagged_by_severity" not in s  # repair g6: the cross-phase tagged total is removed
    assert s["severity_cross_phase_total"].startswith("not given")
    assert s["severity_phases_without_stated_count"] == ["phase-05-beta", "phase-06-gamma"]
    assert s["human_gate_files"] == 1 and s["repair_files_total"] == 1 and s["repair_files_by_phase"]["phase-05-beta"] == 1
    assert s["phases_with_stored_review_verdict"] == 2
    assert s["first_pass_by_phase"] == {"yes": 1, "no": 1, "ambiguous_not_scored": 0, "n_a_or_unknown": 1}
    assert s["first_pass_rate_over_yes_and_no_only"] == pytest.approx(0.5) and s["first_pass_rate_bounds_if_ambiguous_all_no_or_all_yes"] == [0.5, 0.5]
    ev = [(r["category"], r["file"], r["line"]) for r in rows_of(d / "events.csv")]
    assert ("blocked_on_event", "docs/agent-runs/phase-04-alpha/agent-report.md", "1") in ev
    assert ("blocked_on_midline_candidate", "docs/agent-runs/phase-04-alpha/agent-report.md", "6") in ev
    assert ("guard_hook_block", "docs/agent-runs/phase-04-alpha/agent-report.md", "7") in ev
    assert not [e for e in ev if e[0] == "blocked_on_event" and ("prompt.md" in e[1] or "phase-16a" in e[1])]
    pt = {r["phase"]: r for r in rows_of(d / "phase-table.csv")}
    a, b_, g = pt["phase-04-alpha"], pt["phase-05-beta"], pt["phase-06-gamma"]
    assert a["review_verdict_sequence_in_review_report"] == "APPROVE_WITH_NOTES@L2"  # the skipped NEXT GATE line did not become a verdict
    assert a["first_verdict_used"] == "APPROVE_WITH_NOTES" and "review-report.md first stored verdict line" in a["first_verdict_source"]
    assert a["blocked_on_status_lines"] == "1" and a["first_pass_accepted_by_reviewer"] == "yes" and a["human_gate_md_present"] == "True"
    assert b_["first_verdict_used"] == "REQUEST_CHANGES" and b_["first_pass_accepted_by_reviewer"] == "no" and b_["repair_files"] == "1"
    assert g["first_pass_accepted_by_reviewer"].startswith("n/a")
    sev = {r[0]: r for r in csv.reader(open(str(d / "severity-by-phase.csv")))}
    assert sev["phase-04-alpha"][1] == "docs/agent-runs/phase-04-alpha/review-report.md:5" and sev["phase-04-alpha"][2] == "HIGH=1;MEDIUM=0;LOW=1"
    assert "HIGH lines=1 (first at review-report.md:3)" in sev["phase-04-alpha"][4] and "LOW lines=1 (first at review-report.md:4)" in sev["phase-04-alpha"][4]
    assert sev["phase-05-beta"][2] == "NO STATED COUNT" and sev["phase-06-gamma"][2] == "NO STATED COUNT"
    stated = rows_of(d / "severity-stated-lines.csv")
    assert [(r["phase"], r["line"], r["parsed_counts"]) for r in stated] == [("phase-04-alpha", "5", "HIGH=1;MEDIUM=0;LOW=1")]
    ag = {r["agent"]: r for r in rows_of(d / "by-agent.csv")}
    assert ag["build-engineer"]["report_STATUS_BLOCKED-ON_with_OWNER_line"] == "1"
    assert ag["test-engineer"]["report_STATUS_COMPLETE_with_OWNER_line"] == "1"
    assert ag["rag-engineer"]["report_STATUS_FAILED_with_OWNER_line"] == "1" and ag["rag-engineer"]["of_which_agent_named_in_a_repair_file"] == "1"
    share = "share_of_named_phases_with_agent_not_named_in_any_repair_file (NOT a first-pass rate)"  # repair g6: renamed column
    assert share in ag["rag-engineer"] and ag["rag-engineer"][share] == "0.0000" and ag["build-engineer"][share] == "1.0000"
    assert "first_pass" not in "".join(k for k in ag["rag-engineer"] if k != share)
    assert ag["experiment-engineer"]["report_STATUS_BLOCKED-ON_with_OWNER_line"] == "0"  # phase-16a excluded
    amb = open(str(d / "ambiguous.md")).read()
    assert "phase-06-gamma: no reviewer verdict derivable" in amb and "SEVERITY: no cross-phase total is given" in amb
    assert "phase-05-beta: first verdict is REQUEST_CHANGES but there is no repair-NN.md" not in amb  # it has repair-01.md
    assert "PHASES WITH NO RECORDED REVIEW SEVERITY COUNT" in amb and "phase-05-beta, phase-06-gamma" in amb
    assert "NOT FOUND" in amb  # manual citations are looked up at run time; the synthetic tree has none of the cited files


def test_g6_first_pass_resolution_gate_statement_and_ambiguity(le, tmp_path, monkeypatch):
    """Repair g6: an explicit first-review statement outranks the first stored verdict; a stored APPROVE with repair files and no statement is 'ambiguous' (not scored)."""
    base = tmp_path / "fake-repo" / "docs" / "agent-runs"
    cases = {
        "phase-04-gate": ("Verdict: APPROVE\n", "first review: REQUEST CHANGES\nthen repaired\n", ["repair-01.md"]),    # gate says first was REQUEST CHANGES -> no, with a note
        "phase-05-amb": ("Verdict: APPROVE\n", "ok\n", ["repair-01.md"]),                                                  # stored APPROVE, repair exists, no statement -> ambiguous
        "phase-06-stated": ("The first review returned CHANGES REQUIRED\nVerdict: APPROVE\n", "ok\n", []),                  # statement inside the review report -> no
        "phase-07-clean": ("Verdict: APPROVE\n", "ok\n", []),                                                              # plain -> yes
    }
    for name, (review, gate, repairs) in cases.items():
        d_ = base / name
        d_.mkdir(parents=True)
        (d_ / "review-report.md").write_text(review)
        (d_ / "human-gate.md").write_text(gate)
        for r in repairs:
            (d_ / r).write_text("fixed\n")
    d = run_g6(le, tmp_path, monkeypatch, str(tmp_path / "fake-repo"))
    pt = {r["phase"]: r for r in rows_of(d / "phase-table.csv")}
    assert pt["phase-04-gate"]["first_verdict_used"] == "REQUEST_CHANGES" and pt["phase-04-gate"]["first_pass_accepted_by_reviewer"] == "no"
    assert pt["phase-04-gate"]["first_verdict_source"] == "docs/agent-runs/phase-04-gate/human-gate.md:1 (human-gate.md)"
    assert pt["phase-05-amb"]["first_verdict_used"] == "APPROVE" and pt["phase-05-amb"]["first_pass_accepted_by_reviewer"] == "ambiguous"
    assert pt["phase-06-stated"]["first_verdict_used"] == "REQUEST_CHANGES" and pt["phase-06-stated"]["first_pass_accepted_by_reviewer"] == "no"
    assert pt["phase-07-clean"]["first_pass_accepted_by_reviewer"] == "yes"
    s = jload(d / "factory-summary.json")
    assert s["first_pass_by_phase"] == {"yes": 1, "no": 2, "ambiguous_not_scored": 1, "n_a_or_unknown": 0}
    assert s["first_pass_rate_over_yes_and_no_only"] == pytest.approx(1 / 3.0)
    assert s["first_pass_rate_bounds_if_ambiguous_all_no_or_all_yes"] == [pytest.approx(0.25), pytest.approx(0.5)]
    amb = open(str(d / "ambiguous.md")).read()
    assert "phase-04-gate: first stored verdict line review-report.md:1 is APPROVE but the human gate states the first review was REQUEST_CHANGES (human-gate.md:1)" in amb
    assert "phase-05-amb: first stored verdict is APPROVE" in amb and "NOT scored (ambiguous)" in amb


def test_g7_not_unit_tested_by_design():
    """g7 runs the test suites (tests/experiments, tests/datasets) in a copy, so exercising it from inside pytest would recurse into these tests. It stays untested here on purpose (state in the Phase 16a report)."""
    assert True


# ------------------------------------------------------------------------------------------------------------------ write-once
def test_write_once_ctx_in_process(le, tmp_path):
    class A(object):
        group, run_id, seed, sample, main_checkout, out_root = "g5", "t1", 1, 1, str(tmp_path), str(tmp_path / "o")
    c = le.Ctx(A())
    c.write_text("x.txt", "first")
    with pytest.raises(le.sc_common.RawFileExists):
        c.write_text("x.txt", "second")
    with pytest.raises(le.sc_common.RawFileExists):
        c.write_json("x.txt", {"a": 1})
    with pytest.raises(le.sc_common.RawFileExists):
        c.write_csv("x.txt", ["h"], [])
    assert open(c.path("x.txt")).read() == "first"


def test_write_once_cli_second_run_refused_exit_3(tmp_path):
    out_root = tmp_path / "out"
    p1 = run_eval("g5", out_root, ["--run-id", "t1"])
    assert p1.returncode == 0, p1.stderr
    d = out_root / "g5-performance-elasticity" / "t1"
    before = {n: sha(d / n) for n in os.listdir(str(d))}
    p2 = run_eval("g5", out_root, ["--run-id", "t1"])
    assert p2.returncode == 3 and "SC-REFUSED refusing to overwrite existing file" in p2.stderr
    assert {n: sha(d / n) for n in os.listdir(str(d))} == before  # nothing truncated or changed
    p3 = run_eval("g5", out_root, ["--run-id", "t2"])  # a new run id works
    assert p3.returncode == 0, p3.stderr


def test_write_once_preexisting_file_is_never_overwritten(tmp_path):
    d = tmp_path / "out" / "g1-entity-resolution" / "t1"
    os.makedirs(str(d))
    (d / "er-heldout-metrics.csv").write_text("SENTINEL\n")
    p = run_eval("g1", tmp_path / "out", ["--run-id", "t1"])
    assert p.returncode == 3 and "refusing to overwrite" in p.stderr
    assert (d / "er-heldout-metrics.csv").read_text() == "SENTINEL\n"
    assert not (d / "meta.json").exists()  # a refused run does not claim completion


def test_bad_run_id_and_group_rejected(tmp_path):
    p = run_eval("g5", tmp_path / "out", ["--run-id", "../escape"])
    assert p.returncode == 2 and "SC-REFUSED bad run id" in p.stderr
    assert not os.path.exists(str(tmp_path / "escape"))
    p = run_eval("g9", tmp_path / "out")
    assert p.returncode == 2  # argparse usage error


def test_g5_every_value_pending_and_meta_not_synthetic(tmp_path):
    p = run_eval("g5", tmp_path / "out", ["--run-id", "t1"])
    assert p.returncode == 0, p.stderr
    d = tmp_path / "out" / "g5-performance-elasticity" / "t1"
    rows = rows_of(d / "performance-elasticity-PENDING.csv")
    assert rows and all(r["value"] == "[[PENDING-AWS]]" for r in rows)
    meta = jload(d / "meta.json")
    assert meta["synthetic"] is False and meta["evidence_class"] == "none (skeleton)"
    assert len(meta["script_sha256"]) == 64 and meta["script_sha256"] == sha(SCRIPT)
