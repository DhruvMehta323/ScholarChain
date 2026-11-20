"""Regression tests for the DBLP index cache validation in datasets/scripts/dblp_select.py.

stdlib unittest only. All I/O happens in temporary directories seeded from small fixtures.
Run from the repo root: python3 -I -m unittest discover -s tests/datasets -p 'test_*.py'
(or python3 -I tests/datasets/test_dblp_cache.py). Requires lxml for the index stage.
"""
import contextlib
import hashlib
import importlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
FIXTURES = HERE / "fixtures" / "dblp"
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "datasets" / "scripts"))
dblp_select = importlib.import_module("dblp_select")


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class CacheTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dblp-cache-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.xml = self.tmp / "dblp.xml"
        self.dtd = self.tmp / "dblp.dtd"
        shutil.copy(FIXTURES / "dblp.xml", self.xml)
        shutil.copy(FIXTURES / "dblp.dtd", self.dtd)
        self.cache = self.tmp / "derived"
        self.out = self.tmp / "out" / "sel.tsv"
        self.prov = self.out.with_suffix(".provenance.json")

    def run_cli(self, stage, *extra, out=True):
        argv = [stage, "--xml", str(self.xml), "--dtd", str(self.dtd),
                "--cache-dir", str(self.cache)]
        if out and stage in ("select", "all"):
            argv += ["--out", str(self.out)]
        argv += list(extra)
        so, se = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(so), contextlib.redirect_stderr(se):
            code = dblp_select.main(argv)
        return code, so.getvalue(), se.getvalue()

    def build(self):
        code, so, se = self.run_cli("index")
        self.assertEqual(code, 0, se)
        self.assertIn("rebuilding index", so)

    def assert_no_outputs(self):
        self.assertFalse(self.out.exists(), "selection TSV must not be written")
        self.assertFalse(self.prov.exists(), "provenance must not be written")

    def assert_select_refused(self, *extra):
        code, so, se = self.run_cli("select", *extra)
        self.assertEqual(code, 2, (so, se))
        self.assertIn("ERROR:", se)
        self.assert_no_outputs()
        return se

    def assert_index_rebuilds(self):
        code, so, se = self.run_cli("index")
        self.assertEqual(code, 0, se)
        self.assertIn("rebuilding index", so)
        self.assertNotIn("skipping pass", so)
        # after the rebuild the cache verifies again
        dblp_select.verify_cache(self.xml, self.dtd, self.cache)
        code, so, se = self.run_cli("select")
        self.assertEqual(code, 0, se)

    def meta_path(self):
        return self.cache / dblp_select.META_NAME


class VerifiedCacheHit(CacheTestCase):
    def test_second_index_skips_pass_and_select_succeeds(self):
        self.build()
        for name in (dblp_select.CACHE_NAME, dblp_select.SUMMARY_NAME, dblp_select.META_NAME):
            self.assertTrue((self.cache / name).exists(), name)
        before = {p.name: sha(p) for p in self.cache.iterdir()}
        code, so, se = self.run_cli("index")
        self.assertEqual(code, 0, se)
        self.assertIn("skipping pass", so)
        self.assertNotIn("rebuilding index", so)
        self.assertEqual(before, {p.name: sha(p) for p in self.cache.iterdir()})
        code, so, se = self.run_cli("select")
        self.assertEqual(code, 0, se)
        self.assertTrue(self.out.exists())
        self.assertTrue(self.prov.exists())
        rows = self.out.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(rows) - 1, 5)  # six records, one has a single author

    def test_verify_cache_returns_metadata_with_fingerprint(self):
        self.build()
        meta = dblp_select.verify_cache(self.xml, self.dtd, self.cache)
        self.assertEqual(meta["xml"]["sha256"], sha(self.xml))
        self.assertEqual(meta["dtd"]["sha256"], sha(self.dtd))
        self.assertEqual(meta["fingerprint"],
                         dblp_select.compute_fingerprint(sha(self.xml), sha(self.dtd)))

    def test_fast_identity_select_succeeds_on_unchanged_inputs(self):
        self.build()
        code, so, se = self.run_cli("select", "--fast-xml-identity")
        self.assertEqual(code, 0, se)

    def test_matching_xml_sha256_is_accepted(self):
        self.build()
        code, so, se = self.run_cli("index", "--xml-sha256", sha(self.xml))
        self.assertEqual(code, 0, se)
        self.assertIn("skipping pass", so)
        code, so, se = self.run_cli("select", "--xml-sha256", sha(self.xml))
        self.assertEqual(code, 0, se)


class ModifiedXmlSameSize(CacheTestCase):
    def test_same_size_edit_invalidates_in_default_mode(self):
        self.build()
        st = self.xml.stat()
        data = self.xml.read_bytes()
        edited = data.replace(b"Alpha graphs", b"Alpha grapht")
        self.assertEqual(len(edited), len(data))
        self.assertNotEqual(edited, data)
        self.xml.write_bytes(edited)
        os.utime(self.xml, ns=(st.st_atime_ns, st.st_mtime_ns))  # identical size and mtime
        self.assertEqual(self.xml.stat().st_size, st.st_size)
        self.assertEqual(self.xml.stat().st_mtime_ns, st.st_mtime_ns)
        se = self.assert_select_refused()
        self.assertIn("XML SHA-256 differs", se)
        with self.assertRaises(dblp_select.CacheInvalid):
            dblp_select.verify_cache(self.xml, self.dtd, self.cache, xml_sha256=sha(self.xml))
        self.assert_index_rebuilds()
        self.assertIn("Alpha grapht", self._cache_text())

    def _cache_text(self):
        import gzip
        with gzip.open(self.cache / dblp_select.CACHE_NAME, "rt", encoding="utf-8") as f:
            return f.read()


class ModifiedDtdOrPolicy(CacheTestCase):
    def test_modified_dtd_invalidates(self):
        self.build()
        self.dtd.write_text(self.dtd.read_text() + "<!-- changed -->\n")
        se = self.assert_select_refused()
        self.assertIn("DTD SHA-256 differs", se)
        self.assert_index_rebuilds()

    def test_changed_policy_version_invalidates(self):
        self.build()
        original = dblp_select.ELIGIBILITY_POLICY_VERSION
        self.addCleanup(setattr, dblp_select, "ELIGIBILITY_POLICY_VERSION", original)
        dblp_select.ELIGIBILITY_POLICY_VERSION = original + "-test-bump"
        se = self.assert_select_refused()
        self.assertIn("eligibility policy version", se)
        with self.assertRaises(dblp_select.CacheInvalid) as cm:
            dblp_select.verify_cache(self.xml, self.dtd, self.cache)
        self.assertTrue(any("eligibility policy version" in r for r in cm.exception.reasons))
        self.assert_index_rebuilds()  # rebuilt under the bumped version, then verifies
        meta = json.loads(self.meta_path().read_text())
        self.assertEqual(meta["eligibility_policy_version"], original + "-test-bump")

    def test_fingerprint_depends_on_policy_and_format_versions(self):
        a = dblp_select.compute_fingerprint("a" * 64, "b" * 64, 1, "p1")
        self.assertNotEqual(a, dblp_select.compute_fingerprint("a" * 64, "b" * 64, 1, "p2"))
        self.assertNotEqual(a, dblp_select.compute_fingerprint("a" * 64, "b" * 64, 2, "p1"))
        self.assertNotEqual(a, dblp_select.compute_fingerprint("c" * 64, "b" * 64, 1, "p1"))
        self.assertNotEqual(a, dblp_select.compute_fingerprint("a" * 64, "c" * 64, 1, "p1"))
        self.assertEqual(a, dblp_select.compute_fingerprint("a" * 64, "b" * 64, 1, "p1"))


class CorruptedOrMissingCache(CacheTestCase):
    def test_corrupted_cache_file_invalidates(self):
        self.build()
        cache_file = self.cache / dblp_select.CACHE_NAME
        data = bytearray(cache_file.read_bytes())
        data[len(data) // 2] ^= 0xFF  # same size, different content
        cache_file.write_bytes(bytes(data))
        se = self.assert_select_refused()
        self.assertIn("checksum differs", se)
        self.assert_index_rebuilds()

    def test_truncated_cache_file_invalidates(self):
        self.build()
        cache_file = self.cache / dblp_select.CACHE_NAME
        cache_file.write_bytes(cache_file.read_bytes()[:-8])
        se = self.assert_select_refused()
        self.assertIn("size differs", se)
        self.assert_index_rebuilds()

    def test_corrupted_summary_invalidates(self):
        self.build()
        (self.cache / dblp_select.SUMMARY_NAME).write_text("{}\n")
        self.assert_select_refused()
        self.assert_index_rebuilds()

    def test_deleted_cache_file_invalidates(self):
        self.build()
        (self.cache / dblp_select.CACHE_NAME).unlink()
        se = self.assert_select_refused()
        self.assertIn("missing", se)
        self.assert_index_rebuilds()

    def test_malformed_metadata_invalidates(self):
        self.build()
        self.meta_path().write_text("{not json")
        se = self.assert_select_refused()
        self.assertIn("malformed metadata", se)
        self.assert_index_rebuilds()

    def test_unsupported_index_format_version_invalidates(self):
        self.build()
        meta = json.loads(self.meta_path().read_text())
        meta["index_format_version"] = 999
        self.meta_path().write_text(json.dumps(meta))
        se = self.assert_select_refused()
        self.assertIn("unsupported index format version", se)
        self.assert_index_rebuilds()

    def test_tampered_fingerprint_invalidates(self):
        self.build()
        meta = json.loads(self.meta_path().read_text())
        meta["fingerprint"] = "0" * 64
        self.meta_path().write_text(json.dumps(meta))
        se = self.assert_select_refused()
        self.assertIn("fingerprint", se)
        self.assert_index_rebuilds()


class MissingMetadata(CacheTestCase):
    def test_select_fails_without_metadata_writing_nothing(self):
        self.build()
        self.meta_path().unlink()
        se = self.assert_select_refused()
        self.assertIn("missing metadata", se)

    def test_index_rebuilds_without_metadata(self):
        self.build()
        self.meta_path().unlink()
        self.assert_index_rebuilds()

    def test_select_without_any_cache_fails_writing_nothing(self):
        self.assert_select_refused()
        self.assertFalse(self.cache.exists())


class XmlSha256Expectation(CacheTestCase):
    WRONG = "0" * 64

    def test_index_mismatch_fails_and_writes_nothing(self):
        code, so, se = self.run_cli("index", "--xml-sha256", self.WRONG)
        self.assertEqual(code, 2)
        self.assertIn("ERROR:", se)
        self.assertIn("mismatch", se)
        self.assertFalse(self.cache.exists())

    def test_index_mismatch_does_not_touch_existing_cache(self):
        self.build()
        before = {p.name: sha(p) for p in self.cache.iterdir()}
        code, so, se = self.run_cli("index", "--xml-sha256", self.WRONG, "--force-index")
        self.assertEqual(code, 2)
        self.assertIn("ERROR:", se)
        self.assertEqual(before, {p.name: sha(p) for p in self.cache.iterdir()})

    def test_select_mismatch_fails_writing_nothing(self):
        self.build()
        se = self.assert_select_refused("--xml-sha256", self.WRONG)
        self.assertIn("mismatch", se)

    def test_malformed_digest_is_rejected(self):
        self.build()
        code, so, se = self.run_cli("select", "--xml-sha256", "XYZ")
        self.assertEqual(code, 2)
        self.assertIn("ERROR:", se)
        self.assert_no_outputs()

    def test_verify_cache_with_wrong_actual_digest_raises(self):
        self.build()
        with self.assertRaises(dblp_select.CacheInvalid):
            dblp_select.verify_cache(self.xml, self.dtd, self.cache, xml_sha256=self.WRONG)


class SeedAndSizeDoNotInvalidate(CacheTestCase):
    def test_seed_and_size_changes_keep_cache_valid(self):
        self.build()
        fingerprint = dblp_select.verify_cache(self.xml, self.dtd, self.cache)["fingerprint"]
        before = {p.name: sha(p) for p in self.cache.iterdir()}
        sizes = sorted(dblp_select.ALLOWED_SIZES)
        for seed, size in ((1, sizes[0]), (99999, sizes[0]), (7, sizes[-1])):
            code, so, se = self.run_cli("select", "--seed", str(seed), "--size", str(size))
            self.assertEqual(code, 0, se)
            prov = json.loads(self.prov.read_text())
            self.assertEqual(prov["seed"], seed)
            self.assertEqual(prov["target_size"], size)
            self.assertEqual(prov["cache_fingerprint"], fingerprint)
        code, so, se = self.run_cli("index")
        self.assertIn("skipping pass", so)
        self.assertEqual(before, {p.name: sha(p) for p in self.cache.iterdir()})


if __name__ == "__main__":
    unittest.main()
