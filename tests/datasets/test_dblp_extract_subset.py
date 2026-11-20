"""Tests for datasets/scripts/dblp_extract_subset.py (the selected-record extractor).

stdlib unittest only. Every input is a tiny SYNTHETIC XML fixture built in a temporary directory
(no real dblp.xml is read). Requires lxml, because the extractor validates its output with it.
Run from the repo root:
    python3 -B -I -m unittest discover -s tests/datasets -p 'test_*.py'
(or python3 -B -I tests/datasets/test_dblp_extract_subset.py).
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
from unittest import mock

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "datasets" / "scripts"))
extract = importlib.import_module("dblp_extract_subset")

# ---- synthetic source records (bytes exactly as they appear in the synthetic dblp.xml) ----------
REC_A1 = (b'<article mdate="2020-01-01" key="journals/syn/A1"><author>Ann M&uuml;ller</author>'
          b'<title>Graph <i>Mining</i> &amp; Beyond.</title><year>2001</year></article>')
REC_B2 = (b'<inproceedings mdate="2020-01-02" key="conf/syn/B2"><author>Bob Lee</author>'
          b'<author>Cy Wu</author><title>Fast <sub>k</sub>-cores</title><year>2005</year>'
          b'<booktitle>SYN</booktitle></inproceedings>')
# Multi-line record: CRLF inside, a raw Latin-1 byte (0xFC), two named entities, inline markup.
REC_C3 = (b'<article mdate="2020-01-03" key="journals/syn/C3">\r\n'
          b'<author>J\xfcrgen M&uuml;ller</author>\r\n'
          b'<title>Caf&eacute; <i>Graphs</i> &amp; <sup>2</sup></title>\r\n'
          b'<year>2010</year>\r\n</article>')
# Not selected.
REC_X9 = b'<article key="journals/syn/X9"><title>Not selected</title><year>1999</year></article>'
# Not selected; its text mentions selected keys in places that are not a record start tag.
REC_D4 = (b'<article key="journals/syn/D4"><title>Decoys</title><year>2002</year>'
          b'<crossref>journals/syn/A1</crossref>'
          b'<note key="journals/syn/A1">a nested element with a key attribute</note>'
          b'<note>&lt;article key="journals/syn/A1"&gt; escaped, so not a tag</note></article>')
# Not selected; has no key attribute, only xkey (must not match "key=").
REC_X5 = (b'<article mdate="2020-01-05" xkey="journals/syn/C3"><title>xkey</title>'
          b'<year>2003</year></article>')
# Not selected; its key merely starts with a selected key.
REC_A10 = b'<article key="journals/syn/A10"><title>Longer key</title><year>2004</year></article>'

SRC_HEAD = (b'<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n'
            b'<dblp>\n')
SRC_TAIL = b'</dblp>\n'
DTD = (b'<!-- synthetic DTD for tests -->\n'
       b'<!ENTITY uuml "&#252;">\n<!ENTITY eacute "&#233;">\n')

# Selection TSV rows (key, type, year, title, n_authors); the order differs from source order.
SEL_HEADER = "key\ttype\tyear\ttitle\tn_authors\n"
SEL_ROWS = [
    ("journals/syn/C3", "article", "2010", "Café Graphs & 2", "1"),
    ("journals/syn/A1", "article", "2001", "Graph Mining & Beyond.", "1"),
    ("conf/syn/B2", "inproceedings", "2005", "Fast k-cores", "2"),
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def sel_text(rows):
    return SEL_HEADER + "".join("\t".join(r) + "\n" for r in rows)


def default_source():
    # A1 and B2 share one line, with no separator between them.
    return (SRC_HEAD + REC_A1 + REC_B2 + b"\n" + REC_X9 + b"\n" + REC_C3 + b"\n" + REC_D4 + b"\n"
            + REC_X5 + b"\n" + REC_A10 + b"\n" + SRC_TAIL)


def expected_output(*records):
    return extract.HEADER + b"".join(r + b"\n" for r in records) + extract.FOOTER


class ExtractTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dblp-extract-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.xml = self.tmp / "dblp.xml"
        self.dtd = self.tmp / "dblp.dtd"
        self.sel = self.tmp / "selection.tsv"
        self.out = self.tmp / "derived" / "subset.xml"
        self.prov = self.out.with_name(self.out.name + ".provenance.json")
        self.xml_bytes = default_source()
        self.write_inputs()

    def write_inputs(self, source=None, rows=None):
        if source is not None:
            self.xml_bytes = source
        self.xml.write_bytes(self.xml_bytes)
        self.dtd.write_bytes(DTD)
        self.sel.write_text(sel_text(SEL_ROWS if rows is None else rows), encoding="utf-8")

    def run_cli(self, out=None, expected_sha=None, **kw):
        out = out or self.out
        argv = ["--xml", str(self.xml), "--dtd", str(self.dtd), "--selection", str(self.sel),
                "--out", str(out),
                "--expected-xml-sha256", expected_sha or sha(self.xml.read_bytes())]
        so, se = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(se):
            code = extract.main(argv, stdout=so)
        return code, so.getvalue(), se.getvalue()

    def sidecar(self, out=None):
        out = out or self.out
        return json.loads(out.with_name(out.name + ".provenance.json").read_text())


class ExtractionBytesTests(ExtractTestCase):
    def test_a_two_records_on_one_line_are_each_extracted_exactly(self):
        # Source: only A1 and B2, jammed on one line.
        self.write_inputs(source=SRC_HEAD + REC_A1 + REC_B2 + b"\n" + SRC_TAIL,
                          rows=[SEL_ROWS[1], SEL_ROWS[2]])
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertEqual(self.out.read_bytes(), expected_output(REC_A1, REC_B2))

    def test_b_entities_markup_crlf_and_latin1_bytes_are_preserved(self):
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        data = self.out.read_bytes()
        for fragment in (b"M&uuml;ller", b"Caf&eacute;", b"<i>Mining</i> &amp; Beyond.",
                         b"<sub>k</sub>-cores", b"<sup>2</sup>", b"J\xfcrgen", b"\r\n"):
            self.assertIn(fragment, data, fragment)
        # no entity was resolved, no markup stripped
        self.assertNotIn("ü".encode("latin-1"), data.replace(b"J\xfcrgen", b""))
        self.assertIn(REC_C3, data)

    def test_c_selected_key_missing_from_source_is_reported(self):
        rows = SEL_ROWS + [("journals/syn/GONE", "article", "2000", "Gone", "1")]
        self.write_inputs(rows=rows)
        code, so, se = self.run_cli()
        self.assertEqual(code, 1, se + so)
        self.assertIn("missing 1: ['journals/syn/GONE']", so)
        self.assertIn("records found 3 of 4", so)
        # the output is still written, with the three present records
        self.assertEqual(self.out.read_bytes(), expected_output(REC_C3, REC_A1, REC_B2))
        p = self.sidecar()
        self.assertEqual(p["missing_keys"], ["journals/syn/GONE"])
        self.assertEqual((p["record_count"], p["records_expected"]), (3, 4))
        self.assertEqual(p["validation"]["missing_keys"], ["journals/syn/GONE"])

    def test_d_output_is_in_selection_tsv_order(self):
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        # TSV order is C3, A1, B2 while the source order is A1, B2, C3
        self.assertEqual(self.out.read_bytes(), expected_output(REC_C3, REC_A1, REC_B2))

    def test_d_rerun_is_byte_identical(self):
        code, _, se = self.run_cli()
        self.assertEqual(code, 0, se)
        first = self.out.read_bytes()
        # a rerun that really regenerates: remove only this test's own sidecar
        self.prov.unlink()
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertNotIn("up to date", so)
        self.assertEqual(self.out.read_bytes(), first)
        # and a fresh run into another directory gives the same bytes
        other = self.tmp / "other" / "subset.xml"
        code, _, se = self.run_cli(out=other)
        self.assertEqual(code, 0, se)
        self.assertEqual(other.read_bytes(), first)

    def test_f_non_selected_records_and_non_start_tag_keys_are_not_extracted(self):
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        data = self.out.read_bytes()
        for key in (b"journals/syn/X9", b"journals/syn/D4", b"journals/syn/A10"):
            self.assertNotIn(b'key="' + key + b'"', data, key)
        self.assertNotIn(b"xkey", data)
        self.assertNotIn(b"Decoys", data)
        # exactly the three selected records, each once, A1 not duplicated by the decoys
        self.assertEqual(data.count(b'key="journals/syn/A1"'), 1)
        self.assertEqual(data.count(b"<article") + data.count(b"<inproceedings"), 3)
        p = self.sidecar()
        self.assertEqual(p["duplicate_keys_in_source"], [])
        self.assertEqual(p["record_count"], 3)

    def test_f_key_must_match_exactly_not_by_prefix(self):
        # only A10 selected: A1 (also in the source) must not be extracted
        self.write_inputs(rows=[("journals/syn/A10", "article", "2004", "Longer key", "0")])
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertEqual(self.out.read_bytes(), expected_output(REC_A10))

    def test_f_start_tag_inside_an_xml_comment_is_not_extracted(self):
        # A comment holding a fake record start tag for a selected key, placed BEFORE the real one.
        fake = b'<!-- <article key="journals/syn/A1"><title>FAKE</title></article> -->\n'
        self.write_inputs(source=SRC_HEAD + fake + REC_A1 + b"\n" + SRC_TAIL,
                          rows=[SEL_ROWS[1]])
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertEqual(self.out.read_bytes(), expected_output(REC_A1), "comment text leaked")

    def test_chunk_boundaries_do_not_change_the_result(self):
        code, _, se = self.run_cli()
        self.assertEqual(code, 0, se)
        baseline = self.out.read_bytes()
        for chunk in (7, 61, 97, 211):
            with self.subTest(chunk=chunk):
                other = self.tmp / f"c{chunk}" / "subset.xml"
                with mock.patch.object(extract, "CHUNK", chunk), \
                        mock.patch.object(extract, "TAIL", 400):
                    code, so, se = self.run_cli(out=other)
                self.assertEqual(code, 0, se + so)
                self.assertEqual(other.read_bytes(), baseline)

    def test_duplicate_record_in_source_is_reported_and_first_wins(self):
        dup = REC_A1.replace(b"Graph", b"Dup")
        self.write_inputs(source=SRC_HEAD + REC_A1 + b"\n" + dup + b"\n" + SRC_TAIL,
                          rows=[SEL_ROWS[1]])
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertEqual(self.out.read_bytes(), expected_output(REC_A1))
        self.assertEqual(self.sidecar()["duplicate_keys_in_source"], ["journals/syn/A1"])

    def test_output_parses_with_the_copied_dtd_and_entities_resolve(self):
        from lxml import etree
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertEqual((self.out.parent / "dblp.dtd").read_bytes(), DTD)
        parser = etree.XMLParser(load_dtd=True, resolve_entities=True, no_network=True)
        root = etree.parse(str(self.out), parser).getroot()
        self.assertEqual([e.get("key") for e in root],
                         ["journals/syn/C3", "journals/syn/A1", "conf/syn/B2"])
        self.assertIn("Müller", "".join(root[1].itertext()))


class IdempotenceTests(ExtractTestCase):
    def test_e_matching_recorded_sha256_is_skipped_not_rewritten(self):
        code, _, se = self.run_cli()
        self.assertEqual(code, 0, se)
        self.assertEqual(self.sidecar()["output_sha256"], sha(self.out.read_bytes()))
        old = 1_000_000_000
        for p in (self.out, self.prov):
            os.utime(p, (old, old))
        before = {p: (p.stat().st_ino, p.stat().st_mtime_ns, p.read_bytes())
                  for p in (self.out, self.prov)}
        with mock.patch.object(extract, "scan",
                               side_effect=AssertionError("source must not be rescanned")), \
                mock.patch.object(extract, "write_atomic",
                                  side_effect=AssertionError("nothing may be written")):
            code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertIn("up to date", so)
        self.assertIn("nothing rewritten", so)
        after = {p: (p.stat().st_ino, p.stat().st_mtime_ns, p.read_bytes()) for p in before}
        self.assertEqual(after, before)

    def test_e_changed_output_is_rewritten(self):
        self.run_cli()
        good = self.out.read_bytes()
        self.out.write_bytes(good + b"<!-- tampered -->\n")
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertNotIn("up to date", so)
        self.assertEqual(self.out.read_bytes(), good)

    def test_e_changed_selection_or_script_version_is_rewritten(self):
        self.run_cli()
        self.write_inputs(rows=[SEL_ROWS[1], SEL_ROWS[0], SEL_ROWS[2]])
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertNotIn("up to date", so)
        self.assertEqual(self.out.read_bytes(), expected_output(REC_A1, REC_C3, REC_B2))
        p = self.sidecar()
        p["script_version"] = "0.0.0-old"
        self.prov.write_text(json.dumps(p))
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertNotIn("up to date", so)
        self.assertEqual(self.sidecar()["script_version"], extract.SCRIPT_VERSION)

    def test_e_unparseable_sidecar_means_rewrite(self):
        self.run_cli()
        self.prov.write_text("{not json")
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        self.assertNotIn("up to date", so)
        self.assertEqual(self.sidecar()["record_count"], 3)


class ProvenanceTests(ExtractTestCase):
    def test_g_sidecar_counts_and_hashes(self):
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        p = self.sidecar()
        out = self.out.read_bytes()
        self.assertEqual(p["script_version"], extract.SCRIPT_VERSION)
        self.assertEqual(p["script"], "datasets/scripts/dblp_extract_subset.py")
        self.assertEqual(p["source_xml_bytes"], len(self.xml_bytes))
        self.assertEqual(p["source_xml_sha256"], sha(self.xml_bytes))
        self.assertTrue(p["source_xml_sha256_matches_manifest"])
        self.assertEqual(p["parameters"]["expected_xml_sha256"], sha(self.xml_bytes))
        self.assertEqual(p["selection_sha256"], sha(self.sel.read_bytes()))
        self.assertEqual(p["dtd_sha256"], sha(DTD))
        self.assertEqual(p["output_bytes"], len(out))
        self.assertEqual(p["output_sha256"], sha(out))
        self.assertEqual((p["record_count"], p["records_expected"]), (3, 3))
        self.assertEqual(p["missing_keys"], [])
        self.assertEqual(p["duplicate_keys_in_source"], [])
        self.assertEqual(p["parameters"]["order"], "selection TSV order")
        self.assertIsInstance(p["code_version"], str)
        self.assertTrue(p["code_version"])

    def test_g_sidecar_validation_block(self):
        self.run_cli()
        v = self.sidecar()["validation"]
        self.assertEqual((v["records_in_output"], v["records_expected"]), (3, 3))
        self.assertEqual(v["missing_keys"], [])
        for col in ("type", "year", "title", "n_authors"):
            self.assertEqual(v[col], {"agree": 3, "disagree": 0}, col)
        self.assertEqual(v["disagreement_examples"], [])

    def test_g_validation_reports_tsv_disagreements(self):
        rows = [SEL_ROWS[0], SEL_ROWS[1], ("conf/syn/B2", "inproceedings", "2006", "Fast k-cores", "9")]
        self.write_inputs(rows=rows)
        code, so, se = self.run_cli()
        self.assertEqual(code, 0, se + so)
        v = self.sidecar()["validation"]
        self.assertEqual(v["year"], {"agree": 2, "disagree": 1})
        self.assertEqual(v["n_authors"], {"agree": 2, "disagree": 1})
        self.assertEqual(v["type"], {"agree": 3, "disagree": 0})
        self.assertEqual(sorted(e["column"] for e in v["disagreement_examples"]),
                         ["n_authors", "year"])


class FailureTests(ExtractTestCase):
    def test_hash_mismatch_with_manifest_writes_no_output_and_exits_2(self):
        code, so, se = self.run_cli(expected_sha="0" * 64)
        self.assertEqual(code, 2, se + so)
        self.assertIn("MISMATCH", so)
        self.assertIn("source hash differs from the manifest", se)
        self.assertFalse(self.out.exists())
        self.assertFalse(self.prov.exists())

    def test_missing_input_exits_2_and_writes_nothing(self):
        self.xml.unlink()
        code, so, se = self.run_cli(expected_sha="0" * 64)
        self.assertEqual(code, 2)
        self.assertIn("input not found", se)
        self.assertFalse(self.out.exists())

    def test_duplicate_keys_in_selection_exit_2(self):
        self.write_inputs(rows=SEL_ROWS + [SEL_ROWS[0]])
        code, so, se = self.run_cli()
        self.assertEqual(code, 2)
        self.assertIn("duplicate keys in selection", se)
        self.assertFalse(self.out.exists())

    def test_existing_different_dtd_next_to_output_is_not_overwritten(self):
        self.out.parent.mkdir(parents=True)
        other = self.out.parent / "dblp.dtd"
        other.write_bytes(b"<!-- someone else's dtd -->\n")
        code, so, se = self.run_cli()
        self.assertEqual(code, 2)
        self.assertIn("not overwriting", se)
        self.assertEqual(other.read_bytes(), b"<!-- someone else's dtd -->\n")
        self.assertFalse(self.out.exists())

    def test_record_without_end_tag_is_an_error_not_a_silent_truncation(self):
        src = SRC_HEAD + REC_A1[:-len(b"</article>")] + b"\n" + SRC_TAIL
        self.write_inputs(source=src, rows=[SEL_ROWS[1]])
        with self.assertRaises(ValueError):
            self.run_cli()


if __name__ == "__main__":
    unittest.main()
