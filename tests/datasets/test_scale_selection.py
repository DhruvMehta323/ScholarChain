"""Phase 15a tests: nested selections, seeded determinism, extractor/selection record counts.

Synthetic fixtures only (tmp dirs). Run from the repo root:
    PYTHONDONTWRITEBYTECODE=1 python3 -m pytest -p no:cacheprovider -q tests/datasets
"""
import contextlib
import hashlib
import importlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scale_helpers as H  # noqa: E402

nested = importlib.import_module("dblp_nested_select")
dblp_select = importlib.import_module("dblp_select")
extract = importlib.import_module("dblp_extract_subset")

N100, N10, TARGET = 200, 20, 60


def run_nested(argv):
    so, se = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(so), contextlib.redirect_stderr(se):
        code = nested.main(argv)
    return code, so.getvalue(), se.getvalue()


class NestedSelectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="nested-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        # Row 3 and row 57 have titles that start with a double quote: csv would merge rows.
        self.titles = {3: '"Quoted" start of a title', 57: '"Another quoted title'}
        H.write_selection(self.tmp / "s100.tsv", range(N100), self.titles)
        # The 10K stand-in is a subset of the 100K one, rows byte-identical.
        self.ten = list(range(0, N100, N100 // N10))[:N10]
        H.write_selection(self.tmp / "s10.tsv", self.ten, self.titles)

    def argv(self, out, seed=441, **kw):
        a = ["--sel-10k", str(self.tmp / "s10.tsv"), "--sel-100k", str(self.tmp / "s100.tsv"),
             "--out", str(out), "--seed", str(seed), "--target", str(TARGET)]
        return a

    def keys(self, path):
        return [l.split("\t", 1)[0] for l in Path(path).read_text(encoding="utf-8").splitlines()[1:]]

    def test_same_seed_gives_byte_identical_selection_and_other_seed_differs(self):
        a, b, c = (self.tmp / n for n in ("a.tsv", "b.tsv", "c.tsv"))
        self.assertEqual(run_nested(self.argv(a))[0], 0)
        self.assertEqual(run_nested(self.argv(b))[0], 0)
        self.assertEqual(run_nested(self.argv(c, seed=7))[0], 0)
        self.assertEqual(a.read_bytes(), b.read_bytes())
        self.assertNotEqual(a.read_bytes(), c.read_bytes())
        self.assertEqual(len(self.keys(a)), TARGET)

    def test_ten_k_is_a_subset_of_fifty_k_for_every_seed_and_fifty_k_within_hundred_k(self):
        k10 = set(self.keys(self.tmp / "s10.tsv"))
        k100 = set(self.keys(self.tmp / "s100.tsv"))
        for seed in (441, 1, 2, 99):
            out = self.tmp / f"o{seed}.tsv"
            self.assertEqual(run_nested(self.argv(out, seed=seed))[0], 0, seed)
            k50 = self.keys(out)
            self.assertEqual(len(k50), len(set(k50)), "duplicate keys")
            self.assertLessEqual(k10, set(k50))
            self.assertLessEqual(set(k50), k100)
            prov = json.loads(out.with_suffix(".provenance.json").read_text())
            self.assertTrue(prov["nesting"]["10k_subset_of_50k"])
            self.assertEqual(prov["counts"]["fill_from_100k_minus_10k"], TARGET - N10)

    def test_rank_formula_golden_value(self):
        # pinned literal: sha256("441|nested-50k|conf/syn0/K00000")
        self.assertEqual(nested.rank(441, "conf/syn0/K00000"),
                         "4eea6d4f897835f5f14d77dc11fd30eba5fe8ea5a26e0d64c1f7a8420f63465c")
        self.assertEqual(nested.rank(441, "conf/syn0/K00000"),
                         hashlib.sha256(b"441|nested-50k|conf/syn0/K00000").hexdigest())

    def test_fill_is_the_independently_ranked_first_candidates(self):
        out = self.tmp / "o.tsv"
        self.assertEqual(run_nested(self.argv(out))[0], 0)
        all_keys = [l.split("\t", 1)[0] for l in (self.tmp / "s100.tsv").read_text(
            encoding="utf-8").splitlines()[1:]]
        ten_keys = {l.split("\t", 1)[0] for l in (self.tmp / "s10.tsv").read_text(
            encoding="utf-8").splitlines()[1:]}
        cands = [k for k in all_keys if k not in ten_keys]
        expected_fill = sorted(cands, key=lambda k: hashlib.sha256(
            f"441|nested-50k|{k}".encode("utf-8")).hexdigest())[:TARGET - N10]
        self.assertEqual(set(self.keys(out)), ten_keys | set(expected_fill))
        # the first-ranked candidate is in, the last-ranked is out (a flipped sort would swap them)
        ranked = sorted(cands, key=lambda k: hashlib.sha256(f"441|nested-50k|{k}".encode()).hexdigest())
        self.assertIn(ranked[0], self.keys(out))
        self.assertNotIn(ranked[-1], self.keys(out))
        # output order is (year, venue, key)
        rows = [l.split("\t") for l in out.read_text(encoding="utf-8").splitlines()[1:]]
        self.assertEqual([(r[2], r[3], r[0]) for r in rows], sorted((r[2], r[3], r[0]) for r in rows))

    def test_selection_does_not_depend_on_input_row_order(self):
        # Rewrite the 100K input in reverse order: the chosen set must be the same.
        lines = (self.tmp / "s100.tsv").read_text(encoding="utf-8").splitlines()
        rev = self.tmp / "s100rev.tsv"
        rev.write_text("\n".join([lines[0]] + lines[1:][::-1]) + "\n", encoding="utf-8")
        a, b = self.tmp / "a.tsv", self.tmp / "b.tsv"
        run_nested(self.argv(a))
        argv = self.argv(b)
        argv[argv.index("--sel-100k") + 1] = str(rev)
        self.assertEqual(run_nested(argv)[0], 0)
        self.assertEqual(a.read_bytes(), b.read_bytes())

    def test_quote_leading_titles_are_parsed_with_plain_split_not_csv(self):
        out = self.tmp / "o.tsv"
        self.assertEqual(run_nested(self.argv(out))[0], 0)
        _, rows = nested.read_lines(self.tmp / "s100.tsv")
        import csv
        with open(self.tmp / "s100.tsv", encoding="utf-8", newline="") as f:
            csv_rows = list(csv.reader(f, delimiter="\t"))
        self.assertLess(len(csv_rows) - 1, N100, "fixture must break the default csv dialect")
        self.assertEqual(len(rows), N100)  # a csv-dialect read would merge quote-led rows
        self.assertEqual(len({k for k, _ in rows}), N100)
        # the quote-led 10K-independent rows are carried byte-for-byte when chosen
        out_lines = set(out.read_text(encoding="utf-8").splitlines())
        src = dict(rows)
        for k in self.keys(out):
            self.assertIn(src[k], out_lines)
        self.assertTrue(any('\t"' in l for l in src.values()))

    def test_build_rejects_ten_k_that_is_not_a_subset_of_hundred_k(self):
        rows100 = [(f"k{i}", f"k{i}\tx") for i in range(10)]
        rows10 = [("zzz", "zzz\tx")]
        with self.assertRaises(ValueError):
            nested.build(rows10, rows100, 441, 5)

    def test_existing_different_output_is_never_overwritten(self):
        out = self.tmp / "o.tsv"
        self.assertEqual(run_nested(self.argv(out))[0], 0)
        out.write_text("different\n", encoding="utf-8")
        code, _, err = run_nested(self.argv(out))
        self.assertEqual(code, 2, err)
        self.assertEqual(out.read_text(), "different\n")

    def test_identical_rerun_reports_up_to_date(self):
        out = self.tmp / "o.tsv"
        run_nested(self.argv(out))
        code, so, _ = run_nested(self.argv(out))
        self.assertEqual(code, 0)
        self.assertIn("up to date", so)


class StratifiedSelectDeterminismTests(unittest.TestCase):
    """dblp_select.select(): seeded, independent of the order of keys inside a venue."""

    def by_year(self, reverse=False):
        data = {}
        for y in range(2000, 2006):
            data[str(y)] = {}
            for v in ("conf/a", "conf/b", "journals/c"):
                ks = [f"{v}/{y}-{i:03d}" for i in range(12 + (y % 3))]
                data[str(y)][v] = ks[::-1] if reverse else ks
        return data

    def test_select_is_deterministic_for_a_seed_and_order_independent(self):
        a = dblp_select.select(self.by_year(), 30, 441)
        b = dblp_select.select(self.by_year(), 30, 441)
        r = dblp_select.select(self.by_year(reverse=True), 30, 441)
        self.assertEqual(a, b)
        self.assertEqual(sorted(a[0]), sorted(r[0]))
        self.assertEqual(len(a[0]), 30)
        other = dblp_select.select(self.by_year(), 30, 442)
        self.assertNotEqual(sorted(a[0]), sorted(other[0]))


class ExtractorCountMatchesSelectionTests(unittest.TestCase):
    def test_extractor_record_count_equals_selection_row_count(self):
        tmp = Path(tempfile.mkdtemp(prefix="extract-count-"))
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        n = 12
        recs = []
        for i in range(n + 5):  # 5 unselected records in the source
            key = f"conf/syn{i % 3}/K{i:05d}"
            recs.append(f'<inproceedings key="{key}"><author>A {i}</author>'
                        f'<title>T {i}</title><year>{1990 + i % 7}</year></inproceedings>'.encode())
        xml = (b'<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n'
               + b"\n".join(recs) + b"\n</dblp>\n")
        (tmp / "dblp.xml").write_bytes(xml)
        (tmp / "dblp.dtd").write_bytes(b"<!-- syn -->\n")
        sel = tmp / "sel.tsv"
        H.write_selection(sel, range(n), {2: '"quoted title'})
        out = tmp / "d" / "sub.xml"
        so, se = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(se):
            code = extract.main(["--xml", str(tmp / "dblp.xml"), "--dtd", str(tmp / "dblp.dtd"),
                                 "--selection", str(sel), "--out", str(out),
                                 "--expected-xml-sha256", hashlib.sha256(xml).hexdigest()], stdout=so)
        self.assertEqual(code, 0, so.getvalue() + se.getvalue())
        prov = json.loads(out.with_name(out.name + ".provenance.json").read_text())
        sel_rows = len(sel.read_text(encoding="utf-8").splitlines()) - 1
        self.assertEqual(prov["record_count"], sel_rows)
        self.assertEqual(prov["records_expected"], sel_rows)
        self.assertEqual(prov["missing_keys"], [])
        self.assertEqual(out.read_bytes().count(b"<inproceedings "), sel_rows)


if __name__ == "__main__":
    unittest.main()


class RepoRelativePathTests(unittest.TestCase):
    """rel() and fetch_common.rel_to_repo must not follow symlinks inside REPO (finding F1)."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="relpath-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.repo = self.tmp / "repo"
        self.external = self.tmp / "elsewhere" / "rawdata"
        (self.external / "derived").mkdir(parents=True)
        self.repo.mkdir()
        (self.repo / "datasets").mkdir()
        (self.repo / "datasets" / "raw").symlink_to(self.external, target_is_directory=True)

    def patched(self):
        import fetch_common
        from unittest import mock
        return mock.patch.object(nested, "REPO", self.repo), mock.patch.object(fetch_common, "REPO", self.repo)

    def test_rel_and_rel_to_repo_stay_repo_relative_through_a_symlink(self):
        import fetch_common
        a, b = self.patched()
        with a, b:
            via_link = self.repo / "datasets" / "raw" / "derived" / "x.tsv"
            self.assertEqual(nested.rel(via_link), "datasets/raw/derived/x.tsv")
            self.assertEqual(fetch_common.rel_to_repo(via_link), "datasets/raw/derived/x.tsv")
            # a ".." detour is normalised without resolving the link
            detour = self.repo / "datasets" / "raw" / "derived" / ".." / "y.tsv"
            self.assertEqual(nested.rel(detour), "datasets/raw/y.tsv")
            outside = self.tmp / "other" / "z.tsv"
            self.assertEqual(nested.rel(outside), str(outside))
            self.assertEqual(fetch_common.rel_to_repo(outside), str(outside))

    def test_stats_out_json_holds_no_absolute_path(self):
        derived = self.repo / "datasets" / "raw" / "derived"  # reached through the symlink
        H.write_selection(derived / "s100.tsv", range(40))
        H.write_selection(derived / "s10.tsv", range(0, 40, 4))
        stats = self.repo / "datasets" / "samples" / "stats.json"
        a, b = self.patched()
        with a, b:
            code, _, err = run_nested(["--sel-10k", str(derived / "s10.tsv"), "--sel-100k", str(derived / "s100.tsv"),
                                       "--out", str(derived / "o.tsv"), "--target", "20",
                                       "--stats-out", str(stats)])
        self.assertEqual(code, 0, err)
        text = stats.read_text(encoding="utf-8")
        self.assertNotIn("/Users/", text)
        self.assertNotIn(str(self.tmp), text)
        self.assertNotIn(str(self.tmp.resolve()), text)
        doc = json.loads(text)
        self.assertEqual(doc["output"]["path"], "datasets/raw/derived/o.tsv")
        self.assertEqual(doc["inputs"]["selection_10k"]["path"], "datasets/raw/derived/s10.tsv")
        self.assertEqual(doc["selection_10k_reused"]["path"], "datasets/raw/derived/s10.tsv")
