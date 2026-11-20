# Phase 03 repair 02: canonical DOI rule, DBLP cache validation (N2), and notes N7 and N15

Background: code-reviewer note N1 found that the dataset scripts percent-decode DOIs while the Scala core normalizer does not, so the same DOI can produce different join keys. Note N2 found that datasets/scripts/dblp_select.py reuses the DBLP index whenever its files exist, without checking that they match the current input. The human architect reviewed both notes and wrote this work order.

## Proposed canonical DOI normalization rule (human-proposed; verify before implementing)

1. Trim leading and trailing whitespace.
2. Remove one leading prefix, case-insensitively, if present: "https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/", or "doi:" (optionally followed by spaces).
3. If the input arrived in URL form (one of the URL prefixes in step 2 was removed), percent-decode the remainder exactly once. Do not percent-decode inputs that did not arrive in URL form, and never decode twice.
4. Lowercase ASCII letters (DOIs are case-insensitive).
5. Validate that the result matches ^10\.[0-9]{4,9}/\S+$. Anything else is rejected with a typed validation error (Scala) or a clear error or None (Python). Never guess a repair.

## A. DOI normalization design verification (before implementation)

domain-engineer and dataset-engineer must first assess the proposed URL-only percent-decoding rule. Specifically examine:

- Whether a bare percent-encoded DOI and an equivalent DOI resolver URL can produce different canonical join keys.
- Whether the canonical rule should distinguish literal percent characters from percent-encoded bytes.
- Whether percent-decoding exactly once preserves deterministic, idempotent normalization.
- Whether malformed percent escapes, invalid UTF-8, embedded whitespace, or control characters are correctly rejected.
- Whether outgoing Crossref and OpenAlex API requests encode the DOI correctly without accidental double-encoding.
- Whether changing the rule affects previously frozen work IDs, serialization fixtures, saved API envelopes, or downstream references.

Do not silently change the human-proposed DOI rule. If it cannot satisfy canonical identity consistency or existing contract compatibility, return a specific BLOCKED-ON human decision with examples and recommended alternatives, and stop the DOI work until the human decides.

## DOI implementation (only after A confirms the rule, or after a human decision)

1. domain-engineer
- Implement the rule in the core DOI normalizer in modules/core/src/main/** and document it in the code as the canonical rule.
- Do not change any stable ID formula, hash tag or JSON wire shape. If the change would alter a frozen value, stop and report BLOCKED-ON.
- Specify the test vectors for test-engineer (input, expected output or REJECT, and the reason for each).

2. test-engineer
- Create one shared test-vector file, modules/core/src/test/resources/doi-normalization-vectors.tsv, with columns input, expected (or REJECT) and note. Cover at least: a plain DOI, mixed case, each accepted prefix, surrounding whitespace, a URL-form DOI with percent-encoding (for example %2F, %3C, %3E), a plain-form DOI containing a literal % sequence, a double-encoded URL DOI decoded only once, malformed percent escapes, invalid UTF-8 after decoding, control characters, an empty string, a missing "10." prefix, a too-short registrant, and an internal space.
- Add a Scala suite that runs every row of that file against the core normalizer.
- Show a mutation proof (break one expected value, show the failure, restore it byte for byte).

3. dataset-engineer
- Align the Python DOI normalizer in datasets/scripts to the same rule, and make sure outgoing API requests encode the canonical DOI exactly once.
- Add a check to python3 datasets/scripts/validate_manifest.py (or a command it calls) that reads modules/core/src/test/resources/doi-normalization-vectors.tsv read-only and asserts that the Python normalizer produces the expected result for every row. That file is the single source of truth for both languages.
- Re-normalize the DOIs in the committed samples and the 1,000-work selection offline, with no new API fetches, and report how many DOIs change (expected: 0). If any change, report them and stop for a human decision before regenerating anything.
- Note N15: make spot_check.py handle a Crossref record that has no year without crashing (count it separately and report the count).

## B. Repair N2: DBLP index cache validation (scoped by the human architect)

dataset-engineer implements, under datasets/**:
1. A versioned cache fingerprint containing the DBLP XML SHA-256, the DTD SHA-256, the eligibility-policy version and the index-format version, stored in a metadata file next to the cached index.
2. --xml-sha256 is an expected digest that is checked against the actual file, not an unverified assertion. A mismatch is an explicit failure.
3. Before any reuse, index, select and all verify the metadata, the fingerprint and the cached files' checksums. Missing metadata, mismatched input, a damaged cache or an unsupported cache version is treated as invalid: index rebuilds, and select fails clearly without writing outputs.
4. Sampling seed and selection size stay in the selection provenance and do not invalidate the eligibility index.
5. Write new cache files to temporary names and rename them into place only when complete.
6. Preserve the existing DBLP XML and DTD and the current 1K, 10K and 100K selections. Do not run a production reindex or delete derived files.
7. Hashing the full 5.4 GB dblp.xml for the fingerprint is acceptable only if the already recorded SHA-256 cannot be proven to belong to the current file. If a full hash is needed, report the expected operation and wait for human approval before running it.
8. Update the manifest and the DBLP schema notes to describe the actual behavior.

test-engineer writes fixture-based regression tests with small XML and DTD fixtures, in its own test paths, covering: a verified cache hit, modified XML with the same size, a modified DTD or changed policy version, and a corrupted cache file.

Deferred by human decision (record, do not implement): multi-generation atomic publication with a metadata pointer, and interruption-recovery tests.

## N7

build-engineer adds __pycache__/ and *.pyc to .gitignore. Nothing else.

## C. Verification

After all repairs:
- Confirm that Python and Scala consume the same DOI test-vector file and both pass it.
- Confirm DOI normalization compatibility for the existing committed examples and frozen IDs.
- Run the fixture-based DBLP cache tests.
- Run python3 datasets/scripts/validate_manifest.py and check_no_secrets.py.
- Run the canonical sbt check; echo "exit=$?" > .claude/.last-test-run and sbt jdkInfo.
- Run repository hygiene checks: git status --short, no files under datasets/raw/ tracked, no __pycache__, no secrets.
- Obtain code-reviewer's final independent read-only verdict. Route REQUEST_CHANGES findings to the owning agents and re-review.

## Evidence

- Preserve the original Phase 03 reports. Append each agent's final report verbatim to docs/agent-runs/phase-03-contracts-datasets/agent-report.md, and code-reviewer's verdict verbatim to docs/agent-runs/phase-03-contracts-datasets/review-report.md, each under the heading "Repair 02 (canonical DOI rule, DBLP cache N2, N7, N15)".
- No Phase 04 work, AWS deployment, API fetches, constitutional changes or git commit.
- Stop for human ratification.
