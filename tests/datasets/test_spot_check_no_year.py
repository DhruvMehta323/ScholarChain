"""N15: spot_check.py must tolerate a Crossref record with no year.

The record must not crash the run, must be left out of the year comparison and histogram
(year_equal is null) and must be counted in crossref_matches_without_year. spot_check reads its
envelopes from the module-level RAW directory, so the test patches spot_check.RAW to a temporary
tree (a test-side patch, no production edit). Output goes to a temporary --out, never to
datasets/samples. stdlib unittest only.
"""
import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "datasets" / "scripts"))
import spot_check  # noqa: E402

FIELDS = ["key", "type", "year", "venue", "doi", "n_authors", "title"]


def envelope(doi, message):
    return {"identifier": doi, "http_status": 200, "body_is_json": True, "missing": False,
            "body": {"message": message}}


def crossref_message(year, title, authors=2):
    msg = {"title": [title], "author": [{"family": f"A{i}"} for i in range(authors)]}
    if year is not None:
        msg["issued"] = {"date-parts": [[year]]}
    return msg


class SpotCheckNoYearTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="spot-check-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.raw = self.tmp / "raw"
        (self.raw / "crossref" / "works").mkdir(parents=True)
        (self.raw / "openalex" / "works").mkdir(parents=True)
        self.out = self.tmp / "out" / "spot.json"
        self.out.parent.mkdir()
        self.selection = self.tmp / "sel.tsv"

    def write(self, rows, envs):
        lines = ["\t".join(FIELDS)] + ["\t".join(r) for r in rows]
        self.selection.write_text("\n".join(lines) + "\n", encoding="utf-8")
        for i, e in enumerate(envs):
            (self.raw / "crossref" / "works" / f"{i}.json").write_text(json.dumps(e), encoding="utf-8")

    def run_main(self):
        so, se = io.StringIO(), io.StringIO()
        with mock.patch.object(spot_check, "RAW", self.raw), \
                contextlib.redirect_stdout(so), contextlib.redirect_stderr(se):
            code = spot_check.main(["--selection", str(self.selection), "--out", str(self.out)])
        return code, so.getvalue(), se.getvalue(), json.loads(self.out.read_text(encoding="utf-8"))

    def fixture(self):
        # d1 and d2 have years (2020 vs 2019 -> diff 1; 2021 vs 2021 -> diff 0); d3 has no year.
        rows = [["k/1", "article", "2020", "k", "10.1/d1", "2", "Title One"],
                ["k/2", "article", "2021", "k", "10.1/d2", "2", "Title Two"],
                ["k/3", "article", "2022", "k", "10.1/d3", "2", "Title Three"]]
        envs = [envelope("10.1/d1", crossref_message(2019, "Title One")),
                envelope("10.1/d2", crossref_message(2021, "Title Two")),
                envelope("10.1/d3", crossref_message(None, "Title Three"))]
        self.write(rows, envs)

    def test_missing_year_does_not_crash_and_is_counted(self):
        self.fixture()
        code, _, se, out = self.run_main()
        self.assertEqual(code, 0)
        self.assertEqual(out["crossref_matches_without_year"], 1)
        self.assertIn("without a year", se)
        self.assertIn(": 1", se)

    def test_missing_year_excluded_from_histogram_and_year_equal_is_null(self):
        self.fixture()
        _, _, _, out = self.run_main()
        self.assertEqual(out["distinct_matches_available"], 3)
        self.assertEqual(out["crossref_year_difference_histogram_dblp_minus_crossref"], {"0": 1, "1": 1})
        by_doi = {r["doi"]: r for r in out["inspected"]}
        self.assertEqual(set(by_doi), {"10.1/d1", "10.1/d2", "10.1/d3"})
        self.assertIsNone(by_doi["10.1/d3"]["year_equal"])
        self.assertIsNone(by_doi["10.1/d3"]["crossref_year"])
        self.assertIs(by_doi["10.1/d1"]["year_equal"], False)
        self.assertIs(by_doi["10.1/d2"]["year_equal"], True)
        # the null year is not counted as an equal year in the aggregate
        self.assertEqual(out["all_matches_aggregate"]["year_equal"], 1)
        self.assertEqual(out["all_matches_aggregate"]["n"], 3)

    def test_all_years_present_gives_zero_without_year(self):
        rows = [["k/1", "article", "2020", "k", "10.1/d1", "2", "Title One"]]
        self.write(rows, [envelope("10.1/d1", crossref_message(2020, "Title One"))])
        _, _, _, out = self.run_main()
        self.assertEqual(out["crossref_matches_without_year"], 0)
        self.assertEqual(out["crossref_year_difference_histogram_dblp_minus_crossref"], {"0": 1})

    def test_only_missing_year_gives_empty_histogram(self):
        rows = [["k/3", "article", "2022", "k", "10.1/d3", "2", "Title Three"]]
        self.write(rows, [envelope("10.1/d3", crossref_message(None, "Title Three"))])
        code, _, _, out = self.run_main()
        self.assertEqual(code, 0)
        self.assertEqual(out["crossref_matches_without_year"], 1)
        self.assertEqual(out["crossref_year_difference_histogram_dblp_minus_crossref"], {})
        self.assertEqual(out["all_matches_aggregate"]["year_equal"], 0)


if __name__ == "__main__":
    unittest.main()
