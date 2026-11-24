"""Phase 15a tests: validate_manifest.py 1.2.0 and the scale_datasets section.

The validator is loaded fresh for every run (it keeps module-level counters) and pointed at a
temporary copy of the manifest (module attribute MANIFEST). The real manifest is only read.
Run: PYTHONDONTWRITEBYTECODE=1 python3 -m pytest -p no:cacheprovider -q tests/datasets
"""
import contextlib
import copy
import importlib.util
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "datasets" / "scripts"))
REAL = REPO / "datasets" / "dataset-manifest.yaml"
VALIDATOR = REPO / "datasets" / "scripts" / "validate_manifest.py"


def run_validator(manifest_path):
    """(outcome, exit code or exception, stdout). outcome is 'exit' or 'raised'."""
    spec = importlib.util.spec_from_file_location("validate_manifest_under_test", VALIDATOR)
    vm = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vm)
    vm.MANIFEST = Path(manifest_path)
    so = io.StringIO()
    argv = sys.argv
    sys.argv = ["validate_manifest.py"]
    try:
        with contextlib.redirect_stdout(so):
            try:
                return "exit", vm.main(), so.getvalue()
            except Exception as e:  # an uncontrolled crash is still a rejection, reported as such
                return "raised", e, so.getvalue()
    finally:
        sys.argv = argv


class ScaleManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="manifest-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.m = yaml.safe_load(REAL.read_text(encoding="utf-8"))

    def validate(self, m):
        p = self.tmp / "manifest.yaml"
        p.write_text(yaml.safe_dump(m, sort_keys=False, allow_unicode=True), encoding="utf-8")
        return run_validator(p)

    def assert_rejected(self, m, expect):
        """A rejection is a clean exit 1 with a FAIL line holding `expect`. A raised exception fails."""
        outcome, res, out = self.validate(m)
        if outcome == "raised":
            self.fail(f"validator crashed instead of rejecting: {type(res).__name__}: {res}")
        self.assertEqual(res, 1, out)
        self.assertTrue(expect, "an expected FAIL text is required")
        self.assertIn("FAIL:", out)
        self.assertTrue(any(l.startswith("FAIL:") and expect in l for l in out.splitlines()),
                        f"no FAIL line holding {expect!r}:\n{out}")
        self.assertNotIn("Traceback", out)

    def test_real_manifest_passes_and_covers_both_scales(self):
        outcome, code, out = run_validator(REAL)
        self.assertEqual((outcome, code), ("exit", 0), out)
        self.assertIn("failed: 0", out)
        self.assertIn("validate_manifest 1.2.0", out)
        sd = self.m["scale_datasets"]
        self.assertTrue({"dblp-10k", "dblp-50k", "stats_file"} <= set(sd))

    def test_copy_of_manifest_passes_so_the_mutation_tests_start_from_green(self):
        outcome, code, out = self.validate(self.m)
        self.assertEqual((outcome, code), ("exit", 0), out)

    def test_missing_scale_datasets_section_is_rejected(self):
        m = copy.deepcopy(self.m)
        del m["scale_datasets"]
        self.assert_rejected(m, "scale_datasets")

    def test_missing_dblp_50k_entry_is_rejected(self):
        m = copy.deepcopy(self.m)
        del m["scale_datasets"]["dblp-50k"]
        self.assert_rejected(m, "scale_datasets must hold dblp-10k, dblp-50k, stats_file")
        outcome, res, out = self.validate(m)
        self.assertIn("dblp-50k: missing", out)
        self.assertIn("nesting statements missing", out)

    def test_empty_file_checksum_is_rejected_for_each_scale(self):
        for name in ("dblp-10k", "dblp-50k"):
            for idx in range(len(self.m["scale_datasets"][name]["files"])):
                m = copy.deepcopy(self.m)
                m["scale_datasets"][name]["files"][idx]["sha256"] = ""
                outcome, res, out = self.validate(m)
                self.assertEqual((outcome, res), ("exit", 1), f"{name} files[{idx}]: {out}")
                self.assertIn("bad sha256 syntax", out)

    def test_removed_file_checksum_key_is_rejected(self):
        m = copy.deepcopy(self.m)
        del m["scale_datasets"]["dblp-50k"]["files"][0]["sha256"]
        path = m["scale_datasets"]["dblp-50k"]["files"][0]["path"]
        self.assert_rejected(m, f"dblp-50k: bad sha256 syntax {path}")

    def test_structure_error_backstop_gives_a_fail_line_and_exit_1(self):
        m = copy.deepcopy(self.m)
        del m["datasets"]["dblp"]["snapshot"]
        self.assert_rejected(m, "manifest structure error in datasets and general checks: KeyError")

    def test_scale_entry_without_the_policy_1_limitation_is_rejected(self):
        for name in ("dblp-10k", "dblp-50k"):
            m = copy.deepcopy(self.m)
            m["scale_datasets"][name]["limitations"] = [
                l for l in m["scale_datasets"][name]["limitations"] if "policy-1 eligible index" not in l.lower()]
            self.assert_rejected(
                m, f"{name}: limitations must state the dependence on the policy-1 eligible index")

    def _with_stats(self, mutate):
        """Point the manifest at a mutated copy of the stats file (absolute path, correct sha256), so
        only the content rule under test can fail."""
        import hashlib
        import json
        m = copy.deepcopy(self.m)
        sf = m["scale_datasets"]["stats_file"]
        doc = json.loads((REPO / sf["path"]).read_text(encoding="utf-8"))
        mutate(doc)
        p = self.tmp / "stats.json"
        p.write_text(json.dumps(doc), encoding="utf-8")
        sf["path"] = str(p)
        sf["sha256"] = hashlib.sha256(p.read_bytes()).hexdigest()
        return m

    def test_per_id_hash_key_in_the_stats_file_is_rejected(self):
        def add(doc):
            doc["scales"]["dblp-10k"]["orcid"]["missing_envelope_sha256_of_id"] = ["0" * 64]
        self.assert_rejected(self._with_stats(add), "stats file must not hold per-iD hashes")

    def test_orcid_id_shaped_string_in_the_stats_file_is_rejected(self):
        def add(doc):
            doc["note"] = "leak 0000-0002-1825-0097"
        self.assert_rejected(self._with_stats(add), "stats file contains an ORCID iD-shaped string")

    def test_unmodified_stats_copy_passes_so_the_stats_mutations_start_from_green(self):
        outcome, code, out = self.validate(self._with_stats(lambda d: None))
        self.assertEqual((outcome, code), ("exit", 0), out)

    def test_absolute_local_path_in_the_selection_stats_is_rejected(self):
        import hashlib
        import json
        m = copy.deepcopy(self.m)
        sf = m["scale_datasets"]["stats_file"]
        doc = json.loads((REPO / sf["selection_stats_path"]).read_text(encoding="utf-8"))
        doc["inputs"]["selection_10k"]["path"] = "/Users/someone/ScholarChain/x.tsv"
        p = self.tmp / "sel.json"
        p.write_text(json.dumps(doc), encoding="utf-8")
        sf["selection_stats_path"] = str(p)
        sf["selection_stats_sha256"] = hashlib.sha256(p.read_bytes()).hexdigest()
        self.assert_rejected(m, "selection stats file holds an absolute local path")

    def test_malformed_checksum_is_rejected(self):
        m = copy.deepcopy(self.m)
        m["scale_datasets"]["dblp-10k"]["files"][0]["sha256"] = "abc123"
        path = m["scale_datasets"]["dblp-10k"]["files"][0]["path"]
        self.assert_rejected(m, f"dblp-10k: bad sha256 syntax {path}")

    def test_wrong_stats_file_checksum_is_rejected(self):
        m = copy.deepcopy(self.m)
        m["scale_datasets"]["stats_file"]["sha256"] = "0" * 64
        self.assert_rejected(m, "sha256 mismatch")

    def test_missing_stats_file_checksum_is_rejected(self):
        m = copy.deepcopy(self.m)
        m["scale_datasets"]["stats_file"]["sha256"] = ""
        self.assert_rejected(m, "stats_file.sha256 syntax")

    def test_missing_cache_digest_syntax_is_rejected(self):
        m = copy.deepcopy(self.m)
        m["scale_datasets"]["dblp-50k"]["enrichment_cache_digests"]["crossref"] = ""
        self.assert_rejected(m, "cache digest crossref syntax")

    def test_coverage_that_does_not_add_up_is_rejected(self):
        m = copy.deepcopy(self.m)
        m["scale_datasets"]["dblp-10k"]["enrichment_coverage"]["crossref"]["missing_404"] += 1
        self.assert_rejected(m, "coverage does not add up")

    def test_dropping_the_not_an_approved_deviation_statement_is_rejected(self):
        m = copy.deepcopy(self.m)
        m["scale_datasets"]["dblp-50k"]["limitations"] = ["Bounded enrichment is fine."]
        self.assert_rejected(m, "approved deviation")

    def test_an_orcid_id_in_the_manifest_is_rejected(self):
        m = copy.deepcopy(self.m)
        m["scale_datasets"]["dblp-10k"]["privacy_notes"] += " example 0000-0002-1825-0097"
        self.assert_rejected(m, "ORCID")


if __name__ == "__main__":
    unittest.main()
