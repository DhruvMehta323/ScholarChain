"""Phase 15a repair 01 tests: scale_enrichment_stats 1.1.0 holds no per-iD hash and no ORCID iD.

Runs against a synthetic raw tree in a tmp dir (module attributes RAW and DER are patched), so the
real datasets/raw is never read or written. No network.
Run: PYTHONDONTWRITEBYTECODE=1 python3 -m pytest -p no:cacheprovider -q tests/datasets
"""
import importlib
import json
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scale_helpers as H  # noqa: E402

ses = importlib.import_module("scale_enrichment_stats")
fc = importlib.import_module("fetch_common")
fo = importlib.import_module("fetch_orcid")

FAKE_ORCID = "0000-0002-1825-0097"  # the public ORCID documentation example
ORCID_SHAPE = re.compile(r"\b\d{4}-\d{4}-\d{4}-\d{3}[\dX]\b")


def envelope(ident, status, body, **extra):
    e = {"identifier": ident, "request_url": "https://example.invalid/x", "retrieved_at": "2026-01-01T00:00:00Z",
         "http_status": status, "missing": status == 404, "body_is_json": True, "body": body,
         "fetcher_code_version": "test"}
    e.update(extra)
    return e


class EnrichmentStatsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="enrich-stats-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        raw = self.tmp / "raw"
        der = raw / "dblp" / "derived"
        der.mkdir(parents=True)
        for n in (10000, 50000):
            H.write_selection(der / f"dblp_selection_{n}.tsv", range(3))
        for src in ("crossref", "openalex", "orcid"):
            (raw / src / "works").mkdir(parents=True)
        for i in range(3):
            d = H.doi_of(i)
            cr = {"message": {"DOI": d, "author": [{"ORCID": f"https://orcid.org/{FAKE_ORCID}"}] if i == 0 else []}}
            oa = {"id": f"https://openalex.org/W{i}", "doi": f"https://doi.org/{d}"}
            for src, body in (("crossref", cr), ("openalex", oa)):
                (raw / src / "works" / f"{fc.key_for(d)}.json").write_text(
                    json.dumps(envelope(d, 200, body)), encoding="utf-8")
        ob = {"group": [], "path": f"/{FAKE_ORCID}"}
        (raw / "orcid" / "works" / f"{fc.key_for(FAKE_ORCID)}.json").write_text(
            json.dumps(envelope(FAKE_ORCID, 200, ob)), encoding="utf-8")
        (raw / "orcid" / "orcid_ids_50k.json").write_text(
            json.dumps({"ids": [FAKE_ORCID, "0000-0001-0000-0001"], "ids_found_distinct": 2}), encoding="utf-8")
        self.raw, self.der = raw, der

    def run_stats(self):
        out = self.tmp / "out" / "stats.json"
        with mock.patch.object(ses, "RAW", self.raw), mock.patch.object(ses, "DER", self.der):
            self.assertEqual(ses.main(["--out", str(out)]) or 0, 0)
        return out.read_text(encoding="utf-8")

    def test_script_version_is_1_1_0(self):
        self.assertEqual(ses.SCRIPT_VERSION, "1.1.0")

    def test_output_has_no_sha256_of_id_key_and_no_orcid_id(self):
        text = self.run_stats()
        doc = json.loads(text)
        self.assertEqual(doc["script_version"], "1.1.0")

        def keys(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    yield k
                    yield from keys(v)
            elif isinstance(o, list):
                for v in o:
                    yield from keys(v)

        self.assertFalse([k for k in keys(doc) if "sha256_of_id" in k])
        self.assertNotIn("sha256_of_id", text)
        self.assertIsNone(ORCID_SHAPE.search(text))
        self.assertNotIn(FAKE_ORCID, text)
        # the fixture really exercised the ORCID branch
        self.assertEqual(doc["scales"]["dblp-10k"]["orcid"]["ids_with_envelope"], 1)


if __name__ == "__main__":
    unittest.main()
