"""Tests for the `adopt` refusal (human decision B7) in datasets/scripts/dblp_select.py.

adopt must refuse (exit 2, ERROR line naming the policy mismatch, nothing written) when the
legacy cache was built under an eligibility policy other than the current one. The current
policy value is read from the module, never hard-coded. Temporary paths only; datasets/raw is
never touched. stdlib unittest only.
"""
import contextlib
import gzip
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
REPO = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "datasets" / "scripts"))
dblp_select = importlib.import_module("dblp_select")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def snapshot(root: Path) -> dict:
    """Relative path -> bytes (None for a directory) for everything under root."""
    return {str(p.relative_to(root)): (None if p.is_dir() else p.read_bytes())
            for p in sorted(root.rglob("*"))}


class AdoptRefusalTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dblp-adopt-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.xml = self.tmp / "dblp.xml"
        self.dtd = self.tmp / "dblp.dtd"
        shutil.copy(HERE / "fixtures" / "dblp" / "dblp.xml", self.xml)
        shutil.copy(HERE / "fixtures" / "dblp" / "dblp.dtd", self.dtd)
        self.cache_dir = self.tmp / "derived"
        self.cache_dir.mkdir()
        self.cache = self.cache_dir / dblp_select.CACHE_NAME
        self.summary = self.cache_dir / dblp_select.SUMMARY_NAME
        self.meta = self.cache_dir / dblp_select.META_NAME
        with gzip.open(self.cache, "wt", encoding="utf-8", newline="\n") as f:
            f.write("\t".join(dblp_select.TSV_FIELDS) + "\n")
            f.write("journals/x/A1\tarticle\t2020\tjournals/x\t10.1000/a\t2\tTitle\n")

    def write_summary(self, **extra):
        """A legacy summary whose recorded hashes all match the real fixture files."""
        xml_bytes = self.xml.read_bytes()
        body = {
            "script_version": "1.0.0",
            "input_xml_bytes": len(xml_bytes),
            "input_xml_sha256": sha(xml_bytes),
            "input_dtd_sha256": sha(self.dtd.read_bytes()),
            "cache_sha256": sha(self.cache.read_bytes()),
        }
        body.update(extra)
        self.summary.write_text(json.dumps(body, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    def run_adopt(self, *extra):
        argv = ["adopt", "--xml", str(self.xml), "--dtd", str(self.dtd),
                "--cache-dir", str(self.cache_dir), *extra]
        so, se = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(so), contextlib.redirect_stderr(se):
            code = dblp_select.main(argv)
        return code, so.getvalue(), se.getvalue()

    def assert_refused_untouched(self, expect_policy):
        before_cache, before_all = snapshot(self.cache_dir), snapshot(self.tmp)
        code, so, se = self.run_adopt()
        self.assertEqual(code, 2, f"stdout={so!r} stderr={se!r}")
        error_lines = [l for l in se.splitlines() if l.startswith("ERROR")]
        self.assertTrue(error_lines, se)
        line = error_lines[0]
        self.assertIn("eligibility policy", line)
        self.assertIn(repr(expect_policy), line)
        self.assertIn(repr(dblp_select.ELIGIBILITY_POLICY_VERSION), line)
        self.assertIn("adopt refused", line)
        self.assertIn("nothing written", line)
        self.assertFalse(self.meta.exists(), "eligible_index.meta.json must not be written")
        self.assertEqual(snapshot(self.cache_dir), before_cache,
                         "cache directory must be byte-identical (names and contents)")
        self.assertEqual(snapshot(self.tmp), before_all, "nothing anywhere under the temp tree may change")

    def test_legacy_policy_constant_differs_from_current(self):
        self.assertNotEqual(dblp_select.LEGACY_POLICY_VERSION, dblp_select.ELIGIBILITY_POLICY_VERSION,
                            "the field-less 1.0.0 test is only meaningful while the policies differ")

    def test_summary_without_policy_field_is_refused(self):
        self.write_summary()
        self.assertNotIn("eligibility_policy_version", json.loads(self.summary.read_text()))
        self.assert_refused_untouched(dblp_select.LEGACY_POLICY_VERSION)

    def test_summary_with_explicit_mismatching_policy_is_refused(self):
        other = dblp_select.ELIGIBILITY_POLICY_VERSION + ".mismatch"
        self.write_summary(eligibility_policy_version=other)
        self.assert_refused_untouched(other)

    def test_refusal_happens_before_xml_is_read(self):
        """The policy check precedes any hash: refused even when the XML is absent."""
        self.write_summary()
        self.xml.unlink()
        before = snapshot(self.cache_dir)
        code, _, se = self.run_adopt()
        self.assertEqual(code, 2)
        self.assertIn("adopt refused", se)
        self.assertNotIn("not found", se.lower())
        self.assertEqual(snapshot(self.cache_dir), before)

    def test_matching_policy_is_adopted_control(self):
        """Control: the same fixture with the current policy and correct hashes is adopted,
        so the refusals above are caused by the policy and not by a broken fixture."""
        self.write_summary(eligibility_policy_version=dblp_select.ELIGIBILITY_POLICY_VERSION)
        cache_before, summary_before = self.cache.read_bytes(), self.summary.read_bytes()
        code, so, se = self.run_adopt()
        self.assertEqual(code, 0, f"stdout={so!r} stderr={se!r}")
        self.assertTrue(self.meta.exists())
        meta = json.loads(self.meta.read_text())
        self.assertEqual(meta["eligibility_policy_version"], dblp_select.ELIGIBILITY_POLICY_VERSION)
        self.assertEqual(self.cache.read_bytes(), cache_before, "adopt must never modify the cache")
        self.assertEqual(self.summary.read_bytes(), summary_before, "adopt must never modify the summary")


if __name__ == "__main__":
    unittest.main()
