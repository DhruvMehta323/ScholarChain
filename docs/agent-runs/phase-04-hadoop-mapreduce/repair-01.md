# Phase 04 repair 01 — dataset extractor (test-found defect)

Routed by the orchestrator to dataset-engineer after test-engineer's DS-T run (see test-report.md).

## Findings
1. **Comment leak (failing test).** `tests/datasets/test_dblp_extract_subset.py::ExtractionBytesTests::test_f_start_tag_inside_an_xml_comment_is_not_extracted` fails. `START_RE` in `datasets/scripts/dblp_extract_subset.py` is a byte regex with no XML awareness. A start tag inside an XML comment is extracted as the record, and the real record is reported as a duplicate. The real `dblp.xml` has no comments, so this is low severity for the planned run, but it contradicts the docstring.
2. **DTD copied before the hash check fails (observation, no test).** On a manifest-hash mismatch, the script exits 2 with no output or sidecar, but it has already copied `dblp.dtd` next to the output. The docstring says "(nothing written)".

## Requested outcome
Fix both in the script (comments, and for robustness CDATA sections and processing instructions, must not yield records; no file is written before the input checks pass), or document a limit and explain why. Rerun the extractor on the real input and confirm the output sha256 is unchanged (`3dfcab53…68db6`), or report the new hash and why it changed. Run the full `tests/datasets` suite.

## Result (dataset-engineer final report, verbatim)

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 04 repair 01. Make `dblp_extract_subset.py` ignore start tags inside comments, CDATA sections and processing instructions. Write no file before all input checks pass. Rerun the extractor and update the manifest.
FILES CREATED: none
FILES MODIFIED:
- `/Users/dhruv_mehta/Desktop/ScholarChain/datasets/scripts/dblp_extract_subset.py`: both fixes, version bumped to 1.1.0, docstring updated.
- `/Users/dhruv_mehta/Desktop/ScholarChain/datasets/dataset-manifest.yaml`: extractor entry now records script_version 1.1.0 and the new scan time.
- `/Users/dhruv_mehta/Desktop/ScholarChain/datasets/raw/dblp/derived/dblp_selection_1000.xml.provenance.json`: rewritten by the script, now script_version 1.1.0. The code_version in it was already `6335b5a...+uncommitted`, which matches the manifest.

The output XML was rewritten with identical bytes. This overwrote only our own derived output, not raw data.

What changed:
- **Comments, CDATA, processing instructions.** `START_RE` is replaced by a single tokenizer, `TOKEN_RE`. It matches comments, CDATA sections, processing instructions and record start tags in one regex, and the leftmost match wins. A start tag inside a comment, CDATA section or PI is consumed with that token and never becomes a record.
- **Chunk edges.** A token that straddles the scan limit is consumed whole, so the next chunk never starts inside it. An unterminated token that isn't at EOF is carried over from its opener. Record slicing is still byte-exact.
- **Memory limit.** An unterminated token larger than a chunk is held in memory until its closer arrives. This is noted in the docstring.
- **Nothing written early.** The directory creation, the `dblp.dtd` copy, the output and the sidecar now come after the source-hash and size checks. Before the scan, the script only checks whether an existing `dblp.dtd` differs, and it never writes. The docstring now states this.

TEST RESULTS:
- `python3 -B -I -m unittest discover -s tests/datasets -p 'test_*.py'` printed "Ran 83 tests in 2.627s" and "OK". That is 83 run, 0 failures, 0 errors. I ran it twice, before and after the manifest edit, with the same result.
- `sbt check`: none run. I changed nothing under modules/, tests/, project/ or build.sbt.

VALIDATION RESULTS:
- Extractor rerun command: `python3 -I -B datasets/scripts/dblp_extract_subset.py` (default arguments), exit 0.
- Wall time: 1:41.7 total (scan 101.5 s). It rewrote the output because of the version bump.
- Source sha256 `f9f1f1e19621f7b03edbe18f8e50e36af24d557a626177145965b974f1e22ef3` matched the expected value. Source size was 5405851335 bytes.
- Output `datasets/raw/dblp/derived/dblp_selection_1000.xml`: 575598 bytes, sha256 `3dfcab53e2764639f8777707fe55f2c3bc067e040282c63c24cdc72341868db6`. This equals your target hash.
- Records found: 1000 of 1000, with 0 missing and 0 duplicate keys in the source.
- lxml validation: 1000 of 1000 agree on type, year, title and author count.
- `python3 datasets/scripts/validate_manifest.py` summary line: `checks passed: 244; failed: 0`.

ASSUMPTIONS: The real dblp.xml has no comments, CDATA sections or processing instructions that affect selected records. The identical output hash supports this for the 1000 selected records only.
RISKS:
- A wanted record that contains a comment holding its own end tag would still be cut short. This is a pre-existing limitation, and neither the tests nor the real data hit it.
- Attribute values containing `>` are not handled. This is also pre-existing.
- The script is still uncommitted, so code_version carries `+uncommitted`.
- I made no objection to the test expectations. The failing test was correct.

MANUAL ACTION REQUIRED: Human review and acceptance. Commit the script, manifest and sidecar, then re-run if you want code_version to be clean. The extractor's sidecar and the manifest `tests` field still say the tests are pending. I did not change that field, so the manifest does not yet mention the 83 passing tests.
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: manifest review by code-reviewer, then human acceptance.

**Orchestrator note:** the manifest's and sidecar's extractor `tests` field still says the tests are pending (stale since DS-T plus repair 01 gave 83/83). Carried as an open note for code-reviewer and a later dataset-engineer repair round.
