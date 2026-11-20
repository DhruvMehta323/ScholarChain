# Phase 03 human gate — contracts, configuration and dataset manifests

## What was reviewed
- agent-report.md: the final reports of dataset-engineer, domain-engineer, test-engineer, dependency-updater and build-engineer, the dataset-engineer repair 01 report, and the Repair 02 reports.
- review-report.md: code-reviewer's first verdict (REQUEST_CHANGES, B1–B3), its second verdict (APPROVE WITH NOTES), and its Repair 02 verdict.
- datasets/dataset-manifest.yaml, the scripts under datasets/scripts, the schema notes and the committed samples.
- The core contracts in modules/core (records, enums, stable IDs, DOI handling, configuration loader) and the eight contract test suites.

## My own checks
1. DBLP license: I opened https://dblp.org/xml/README.txt. Metadata is CC0 1.0, with ODC-BY 1.0 named as a secondary license. Matches the manifest.
2. OpenAlex license: I opened the OpenAlex pricing/access page. Records are CC0; API access has a separate usage budget. Matches the manifest.
3. Crossref terms: I opened the Crossref REST API documentation. Metadata is publicly reusable, but some abstracts may be copyrighted, and the page does not assign one blanket license to every field. Decision: accepted with qualification. The manifest keeps the license identifier as UNVERIFIED with the quoted reuse terms, and abstracts are excluded from committed samples.
4. DOI spot-check: I checked 10.18653/v1/2022.acl-long.387 against the official ACL metadata ("RoMe: A Robust Metric for Evaluating Natural Language Generation", 2022, ACL Long Papers, five authors) and against the committed selection (grep for conf/acl/RonyKCU022 in datasets/samples/dblp/dblp_selection_1000.tsv shows the same title, year 2022, DOI and 5 authors). Match.
5. Repository hygiene: dblp.xml and everything under datasets/raw/ are untracked (checked with git). The committed samples under datasets/samples/ are not gitignored (git check-ignore printed nothing).
6. Manifest validation: I ran python3 datasets/scripts/validate_manifest.py myself: "checks passed: 115; failed: 0".

## Decision
ACCEPTED (ratified) after two repairs. Final code-reviewer verdict on Repair 02: APPROVE WITH NOTES (verdict 3), with nothing blocking.

- Repair 01 (reviewer-routed, see repair-01.md): B1 untraceable consistency figures, B2 unsupported DBLP secondary-license claim, B3 undeclared certifi. Fixed by dataset-engineer. Second review: APPROVE WITH NOTES.
- Repair 02 (human work order, see repair-02.md): one canonical DOI rule across Scala and Python (note N1), DBLP index cache validation (note N2), .gitignore for Python bytecode (N7), and Crossref records without a year (N15).
  - Results (observed by the orchestrator on the final files):
    - Scala (Identifiers.scala) and Python (datasets/scripts/doi_canonical.py) both pass every row of the shared 121-row vector file modules/core/src/test/resources/doi-normalization-vectors.tsv.
    - No frozen ID formula, hash tag or JSON wire shape changed. Fetchers encode each DOI exactly once.
    - Offline re-check of the committed samples and the 1K selection: 2,298 DOIs, 0 changed or rejected.
    - Offline comparison over dblp.xml (renormalize_check.py --dblp-xml, using the exact pre-repair rule): 3 newly rejected DOIs (10.1109/SWAT%20.1970, 10.37789/icusi%202026.15, 10.17706/jsw.16.4.%20182-199), 0 newly accepted, 0 changed values. None of the 3 is in the 1K, 10K or 100K selections.
    - sbt check: 307 of 307 passed, marker exit=0; sbt jdkInfo: Temurin 17.0.21.
    - Python tests: 60 of 60 passed (DBLP cache validation, adopt refusal, N15 missing year, vector-file reader), with mutation proofs.
    - validate_manifest.py: 238 passed, 0 failed; check_no_secrets.py: 0 matches. Nothing under datasets/raw/ is tracked, and there are no __pycache__ folders.
    - Review history: verdict 1 APPROVE WITH NOTES (notes R1–R8 routed back to owners anyway), verdict 2 REQUEST_CHANGES (see disclosures), verdict 3 APPROVE WITH NOTES.

## Human decisions
1. Stable ID formulas, hash tags and JSON wire shapes are frozen as the Phase 03 contracts.
2. Configuration: environment variables first, then one versioned HOCON file, blank values treated as unset, the 21-key set, graph.bridge.parameters as a JSON string. HOCON include is untested and recorded as a known gap.
3. RunManifest without a Provenance record, with attempt distinguishing reruns: accepted.
4. Canonical DOI rule (my design, refined through the Repair 02 design review):
   - trim the shared Unicode whitespace list from both ends;
   - remove one prefix, case-insensitively: http(s)://doi.org/, http(s)://dx.doi.org/, or doi: followed only by ordinary spaces;
   - percent-decode exactly once, strictly (two hex digits, valid UTF-8, + stays literal), and only for the four URL prefixes;
   - lowercase A–Z only;
   - validate the whole result against ^10\.[0-9]{4,9}/\S+$ with shared whitespace and control-character lists.
   A URL-form %2F and a bare %2F intentionally give different keys. Fetchers call the canonical normalizer and encode the DOI once.
5. Three DBLP DOIs (for example 10.1109/SWAT%20.1970) become ineligible under the new rule. Decision: raise the selection-rules version and defer the reindex. The 10K and 100K selections are not regenerated now; they will be regenerated when the approved 10K and 50K experiment selections are produced.
6. The adopt step and the full 5.4 GB hash were skipped; adopt refuses a selection-rules version mismatch.
7. certifi is approved as an optional TLS fallback for the python.org macOS build; TLS verification is never disabled.
8. ORCID: larger fetches must use registered Public API credentials, and record-level ORCID data is never committed (aggregate counts only).
9. com.typesafe:config 1.4.10 is accepted. The EMR classpath clash risk is to be verified in Phase 12.
10. The one-work-per-year floor in the DBLP selection is kept, as a documented design effect.
11. Deferred from my original N2 instructions: multi-generation atomic publication with a metadata pointer, and interruption-recovery tests.
12. Remaining Repair 02 review notes V3-1 (wrong line number in a citation), V3-2 (the pre-repair rule's source survives only in the orchestrator's session record), V3-3 (superseded process note), V3-4 (dblp.xml figures counted on raw text) and V3-5 (no test for the --dblp-xml mode), and earlier informational notes N12 and N13: accepted as notes, not routed. V3-5 may be added in a later test-engineer task.
13. 100,000 stays an allowed selection size because the existing 100K selection is recorded in the manifest. The approved experiment scales are 10K and 50K.
14. The DBLP reindex (with the full 5.4 GB hash) and the 10K and 50K selection runs are deferred until they are needed for the experiments; every select stops with an error until then.
15. Correction: the manifest originally said that the instructor had approved bounded DOI-driven enrichment subsets. My written approval does not cover that, so the claim was replaced. The enrichment subsets are a design choice under the specification's bounded-retrieval requirement. The deviations actually approved are in docs/agent-runs/instructor-approvals.md.

## Manual changes
- repair-01.md, repair-02.md, instructor-approvals.md and this file were written by me.
- The Phase 03 prompt was adapted by me from the course work order: I added the contract-test inventory, the mutation-proof requirement, the honest three-DOI spot-check rule and the human-gate report format.
- No agent-generated source, script or data file was edited by hand.

## Disclosures
- Claude Code ran in accept-edits mode: file writes were automatic and every shell command needed my approval.
- Phase 03 ran in the same orchestrator session as Phase 02 instead of a fresh one, contrary to control rule 9. Repair 02 ran in a fresh session. The Repair 02 session was interrupted by the Claude Pro usage limit and resumed in a new session, which rebuilt its state from the repository before continuing.
- The first Crossref fetch made no successful requests and was stopped after about 7.5 hours. The orchestrator first blamed a network outage and later corrected this: python.org's macOS Python had no CA certificate store.
- During Repair 02, dataset-engineer reported two manifest fixes as done (the blocked markers for the select commands, and the newly_rejected_count_check block) that were not in the file. code-reviewer caught this in verdict 2, the orchestrator confirmed it in the files, and dataset-engineer fixed them; the correction is recorded in agent-report.md. The orchestrator also made one evidence-file fix itself (V2-4) and recorded it.
- When asked to ratify the six agreed DOI points, I first answered "Ratify with changes" without naming changes; I then clarified that all six were ratified as written, with no changes.
- For Repair 02, I used three read-only OpenAI Codex audits as additional input. I reviewed their findings and wrote the work order myself; Codex changed no files.

## Remaining risk
- The DBLP snapshot date (2026-11-14) is a lower bound taken from record mdates, not an official release date.
- ORCID's anonymous API access worked, but its documentation says a token is required, so this may stop working.
- config 1.4.10 is bundled into the Spark and Hadoop jars; a clash with EMR's own copy is unverified (Phase 12).
- The 10K and 100K selections and the DBLP index cache are stale under selection-rules version 2 until the approved reindex.
- Open reviewer notes deferred: N4 (non-200/404 responses not stored), N5 (Retry-After uncapped), N9 (config comment), N10 (core validation gaps; the U+00A0 part is now resolved by the canonical DOI rule), N11 (two real ORCID iDs from ORCID's documentation in test fixtures), N14 (stale TLS sentence).

## Tools
Claude Code 2.1.311 (orchestrator claude-opus-5-5; sonnet subagents; opus code-reviewer), Claude Pro login, sbt 2.0.12, Scala 3.3.9, Temurin 17.0.21 (sbt and forked test JVM, confirmed by sbt jdkInfo), python3 with lxml, PyYAML and certifi, macOS arm64.

## Accepted commit
See commit.txt.