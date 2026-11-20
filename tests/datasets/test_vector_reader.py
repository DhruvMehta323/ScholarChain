"""Error and positive cases of the Python vector-file reader in datasets/scripts/validate_manifest.py.

Mirrors the malformed-cell tests of the Scala reader (DoiVectorsContractSuite, "escape reader
rejects malformed cells loudly") and adds file-level cases (BOM, CR, header, cell count, empty
note). Temporary files only; the shared vector file is read by validate_manifest, not here.
stdlib unittest only (validate_manifest itself imports PyYAML).
"""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "datasets" / "scripts"))
import validate_manifest as vm  # noqa: E402

Err = vm.VectorReaderError
HEADER = "input\texpected\tnote\n"


class UnescapeTest(unittest.TestCase):
    def assert_rejects(self, cell, fragment=None):
        with self.assertRaises(Err, msg=f"cell {cell!r} must be rejected") as cm:
            vm.vector_unescape(cell)
        if fragment:
            self.assertIn(fragment, str(cm.exception))

    def test_unknown_escape(self):
        self.assert_rejects("\\x", "unknown escape")
        self.assert_rejects("a\\n", "unknown escape")

    def test_dangling_backslash(self):
        self.assert_rejects("\\", "dangling")
        self.assert_rejects("a\\", "dangling")

    def test_truncated_u_and_U(self):
        self.assert_rejects("\\u12", "truncated")
        self.assert_rejects("\\U0001F60", "truncated")

    def test_bad_hex_digit(self):
        self.assert_rejects("\\u12G4", "bad hex")
        self.assert_rejects("\\uFF1G", "bad hex")
        self.assert_rejects("\\u+123", "bad hex")
        self.assert_rejects("\\u\u0661\u0662\u0663\u0664", "bad hex")  # Arabic-Indic digits

    def test_U_out_of_range_and_surrogate(self):
        self.assert_rejects("\\U00110000", "invalid code point")
        self.assert_rejects("\\UFFFFFFFF", "invalid code point")
        self.assert_rejects("\\U0000D800", "invalid code point")
        self.assert_rejects("\\U0000DFFF", "invalid code point")

    def test_e_only_as_whole_cell(self):
        self.assert_rejects("a\\e", "unknown escape")
        self.assert_rejects("\\ea", "unknown escape")

    def test_adjacent_high_low_surrogate_u_escapes(self):
        self.assert_rejects("\\uD83D\\uDE00", "adjacent")
        self.assert_rejects("x\\uD800\\uDC00y", "adjacent")

    def test_raw_whitespace_control_and_zero_width(self):
        for ch in [" ", "\t", "\n", "\r", "\u00a0", "\u0085", "\u1680", "\u2000", "\u200a",
                   "\u2028", "\u2029", "\u202f", "\u205f", "\u3000", "\u001c", "\u0001",
                   "\u007f", "\u009f", "\u200b"]:
            self.assert_rejects(f"a{ch}b", "raw character")

    def test_raw_char_checked_in_escaped_cell_too(self):
        self.assert_rejects("\\u0041 ", "raw character")

    def test_positive_cases(self):
        self.assertEqual(vm.vector_unescape("\\e"), "")
        self.assertEqual(vm.vector_unescape("\\\\"), "\\")
        self.assertEqual(vm.vector_unescape("\\u0041"), "A")
        self.assertEqual(vm.vector_unescape("\\u00e9\\u00E9"), "\u00e9\u00e9")
        self.assertEqual(vm.vector_unescape("\\uD800"), "\ud800")
        self.assertEqual(vm.vector_unescape("\\uDC00"), "\udc00")
        self.assertEqual(vm.vector_unescape("\\U0001F600"), "\U0001F600")
        self.assertEqual(vm.vector_unescape("\\U0010FFFF"), "\U0010FFFF")
        self.assertEqual(vm.vector_unescape("10.1000/abc"), "10.1000/abc")

    def test_high_surrogate_followed_by_non_low_is_allowed(self):
        self.assertEqual(vm.vector_unescape("\\uD83D\\u0041"), "\ud83dA")
        self.assertEqual(vm.vector_unescape("\\uDE00\\uD83D"), "\ude00\ud83d")  # low then high

    def test_escaped_backslash_then_u_is_literal(self):
        self.assertEqual(vm.vector_unescape("\\\\u0041"), "\\u0041")

    def test_escaped_space_is_legal(self):
        self.assertEqual(vm.vector_unescape("\\u0020"), " ")  # escaped space is legal


class LoadVectorsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="vector-reader-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def load(self, data):
        p = self.tmp / "v.tsv"
        p.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))
        return vm.load_vectors(p)

    def assert_rejects(self, data, fragment):
        with self.assertRaises(Err) as cm:
            self.load(data)
        self.assertIn(fragment, str(cm.exception))

    def test_valid_file(self):
        rows = self.load("# comment\n\n" + HEADER + "10.1/a\t10.1/a\tplain\nbad\tREJECT\tno doi\n")
        self.assertEqual([(r[3], r[4], r[5]) for r in rows],
                         [("10.1/a", "10.1/a", "plain"), ("bad", None, "no doi")])
        self.assertEqual([r[0] for r in rows], [4, 5])  # 1-based file line numbers

    def test_escapes_decoded_in_input_and_expected(self):
        rows = self.load(HEADER + "\\u0020x\t\\e\tn\n")
        self.assertEqual((rows[0][3], rows[0][4]), (" x", ""))

    def test_bom(self):
        self.assert_rejects(b"\xef\xbb\xbf" + HEADER.encode(), "BOM")

    def test_cr(self):
        self.assert_rejects(HEADER.replace("\n", "\r\n") + "a\tb\tc\r\n", "CR")
        self.assert_rejects(HEADER + "a\tb\tc\rd\n", "CR")

    def test_invalid_utf8(self):
        self.assert_rejects(HEADER.encode() + b"\xff\tb\tc\n", "UTF-8")

    def test_wrong_header(self):
        self.assert_rejects("input\texpect\tnote\na\tb\tc\n", "bad header")
        self.assert_rejects("Input\texpected\tnote\na\tb\tc\n", "bad header")

    def test_wrong_cell_count(self):
        self.assert_rejects(HEADER + "a\tb\n", "got 2")
        self.assert_rejects(HEADER + "a\tb\tc\td\n", "got 4")
        self.assert_rejects("input\texpected\n", "got 2")

    def test_empty_note(self):
        self.assert_rejects(HEADER + "a\tb\t\n", "empty note")

    def test_no_header(self):
        self.assert_rejects("# only a comment\n\n", "no header")

    def test_missing_file(self):
        with self.assertRaises(Err):
            vm.load_vectors(self.tmp / "nope.tsv")

    def test_bad_cell_error_names_line(self):
        with self.assertRaises(Err) as cm:
            self.load(HEADER + "ok\tok\tn\n\\x\tREJECT\tn\n")
        self.assertIn("line 3", str(cm.exception))

    def test_bad_input_cell_and_bad_expected_cell(self):
        self.assert_rejects(HEADER + "a b\tREJECT\tn\n", "raw character")
        self.assert_rejects(HEADER + "a\ta\u200bb\tn\n", "raw character")
        self.assert_rejects(HEADER + "a\t\\uD83D\\uDE00\tn\n", "adjacent")

    def test_reject_literal_is_not_unescaped(self):
        self.assertIsNone(self.load(HEADER + "a\tREJECT\tn\n")[0][4])

    def test_nel_and_ls_do_not_split_lines(self):
        """Lines split on LF only: a raw U+2028 in a cell is a reader error, not a line break."""
        self.assert_rejects(HEADER + "a\u2028b\tREJECT\tn\n", "raw character")


if __name__ == "__main__":
    unittest.main()
