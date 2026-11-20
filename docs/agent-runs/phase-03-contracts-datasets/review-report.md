I re-reviewed repair 01 and my verdict is now **APPROVE WITH NOTES**. B1, B2 and B3 are resolved, the repair introduced no regressions, secrets or new third-party dependency, and N3 and N6 are fixed. I recomputed the year histograms myself from the committed samples and they match.

# Phase 03 code review: data contracts and dataset manifest (final, after dataset-engineer repair 01)

**Reviewer:** code-reviewer. I could only read files (Read, Grep, Glob); I had no shell and no network.
**Baseline:** HEAD 04a455d. Everything reviewed is the uncommitted Phase 03 diff.
**Out of scope:** `docs/agent-runs/phase-02-build-skeleton/human-gate.md`, which is the human's own edit.

## Verdict: APPROVE WITH NOTES

The first review returned REQUEST_CHANGES with three blocking findings (B1-B3). dataset-engineer's repair 01 fixes all three, plus notes N3 and N6. All repair edits are inside `datasets/**`; the only network use was one reported curl of the DBLP README.

- **Recomputed figures:** I recomputed the Crossref and OpenAlex year-difference histograms from the committed samples and the committed DBLP selection, and they match the stored artifact exactly.
- **Invented facts:** none. No license, identifier, snapshot date, coverage count or source is invented. The items I cannot trace to a file in the repository are marked UNVERIFIED below.
- **Unchanged parts:** the core contracts, the contract tests and the build/ledger edits are unchanged since the first review and meet the work order.
- **Open items:** the remaining notes do not block approval. They are routed below, along with the decisions that need the human.

---

## Blocking findings and resolution status

**B1. Consistency numbers had no producing script or stored artifact. Owner: dataset-engineer. RESOLVED.**
- **Script:** `datasets/scripts/spot_check.py` now computes both comparisons offline from the stored envelopes and writes them to `doi_spot_check.json`.
  - Crossref year-difference histogram: lines 56-59 and 94.
  - OpenAlex vs DBLP comparison (title, year, author count, histogram): lines 60-83 and 95.
- **Artifact:** `datasets/samples/doi_spot_check.json:56-82` now holds:
  - `crossref_year_difference_histogram_dblp_minus_crossref` = {-29:1, -21:1, -11:1, -5:1, -2:1, -1:1, 0:90, 1:3}
  - `openalex_vs_dblp` = compared 100, title equal 100, year equal 78, year differs 22, author count equal 100, histogram {-29:1, -21:1, -11:1, -5:1, -2:1, -1:1, 0:78, 1:16}
- **My independent recomputation:**
  - I took DOI and year from `crossref_works_sample.jsonl` and `openalex_works_sample.jsonl`, and DBLP year from `dblp_selection_1000.tsv`.
  - Crossref: 90 at zero.
    - +1 for 10.1007/s10586-018-1936-2, 10.1007/s10639-023-12315-1 and 10.1007/s11042-019-08157-0.
    - −29 for icassp.1976.1170152, −21 for icassp.1984.1172301, −5 for icassp.1997.596053, −2 for iscas.2000.858832, −11 for robot.1992.220134, and −1 for robot.2002.1014419.
  - OpenAlex: the same six negative differences, plus 16 at +1. These are the 3 above, 12 Elsevier/IEEE early-access cases, and the arXiv DOI (DBLP 2006, OpenAlex 2005). That leaves 78 at zero.
  - **Both histograms match exactly.**
  - I did not recompute OpenAlex title equality (100/100) or author-count equality (100/100). I reviewed the script logic (`spot_check.py:68-72`), and both figures are stored in the artifact.
- **Manifest citations:** the manifest now cites the artifact at `dataset-manifest.yaml:197`, `:220`, `:268` and `:280`. The schema notes cite it at `crossref.md:27` and `openalex.md:20`.
- **Drift check:** `validate_manifest.py:99-109` now cross-checks the manifest figures against `doi_spot_check.json`. The manifest's `spot_check.sha256` is `37a56c00…ed42` (`:359`). I could not compute this hash; the validator's reported pass (115/0) is UNVERIFIED by me.

**B2. The DBLP secondary license was unsupported. Owner: dataset-engineer. RESOLVED (quote UNVERIFIED by reviewer).**
- **Identifier:** `dataset-manifest.yaml:60` now reads "CC0-1.0 (the README also names ODC-By 1.0 as a secondary license; see secondary_license_quote)".
- **Quote:** `:65` records it verbatim, word for word identical to the repair report (`agent-report.md:639`): "To ensure compatibility with existing integrations of our data, all metadata is also released under Open Data Commons ODC-BY 1.0 license as a secondary license. If you are currently using our data under ODC-by 1.0, please update to CC0 accordingly."
- **Verification:** I cannot fetch https://dblp.org/xml/README.txt, so the quote is UNVERIFIED by me. It is recorded verbatim with its URL, and the license claim is now supported by quoted text.

**B3. certifi was used but not declared. Owner: dataset-engineer. RESOLVED (approval is a human decision).**
- **Declaration:** `dataset-manifest.yaml:13` declares certifi as OPTIONAL and runtime-only. `:15-16` documents the stdlib-only route (`SSL_CERT_FILE` pointing at a system CA bundle) and correctly says "Install Certificates.command" is not stdlib-only.
- **Manual action:** `:370` adds the MANUAL ACTION item.
- **Docstrings:** corrected at `fetch_common.py:1-5`, `fetch_crossref.py:2`, `fetch_openalex.py:2` and `fetch_orcid.py:2`.
- **TLS code:** unchanged (`fetch_common.py:116-133`). Verification is never disabled.
  - A grep for `CERT_NONE`, `check_hostname`, `_create_unverified` and `verify=False` found nothing.
  - A verification failure raises `FatalTransportError` and aborts the run (`:175-176`).
- **Dependencies:** an import scan of all scripts shows no new third-party import. The only ones are `yaml` (validator only, declared), `lxml` (local import in the index stage) and the lazy `certifi` (`:130`).

---

## Notes (non-blocking), carried forward and updated

| # | Sev | Owner | Status | Finding and evidence |
|---|---|---|---|---|
| N1 | Med | dataset-engineer + domain-engineer (human decides the rule) | OPEN | **The two DOI normalisation rules differ.** `dblp_select.py:87-91` percent-decodes (`unquote`); the manifest (`:98`) and `dblp.md:24` say the same. Core `Doi.normalize` deliberately does not decode (`Identifiers.scala:46`, tested in `DoiContractSuite.scala:31-33`). Join keys diverge for DOIs with `%xx`. The committed 1,000 selection has none (grep of the DOI column for `%<>#[]();` returned 0 rows). Align the two rules before Phase 04. |
| N2 | Med | dataset-engineer | OPEN | **The DBLP index can silently reuse a stale cache.** The docstring says the index is skipped only when "its recorded inputs still match" (`dblp_select.py:8-9`), but the code checks only that the files exist (`:382`). `--xml-sha256` is recorded as given and never checked (`:376-377`, `:385`). The XML and DTD paths are hardcoded (`:51-52`), so the manifest's update method (a new `<snapshot>/` directory, `dataset-manifest.yaml:146`) cannot be run as written. |
| N3 | Low | dataset-engineer | **RESOLVED** | The false docstring claims are fixed. `validate_manifest.py:8-9` now describes the cross-check that is actually implemented (`:99-109`). `make_samples.py:6-7` no longer names an output that is never written. |
| N4 | Low | dataset-engineer | OPEN | **Other HTTP responses are not stored.** Responses other than 200 and 404 (for example ORCID 401/403) get no envelope, and their `errors.jsonl` line has no request URL (`fetch_common.py`, the `other_status` branch of `run_fetch`). The work order says "store every raw response". None occurred: `other_status` is 0 in all three run logs. |
| N5 | Low | dataset-engineer | OPEN | **Retry edge cases.** `Retry-After` is obeyed without a cap and can exceed the 150 s per-DOI budget (`fetch_common.py:172-173`, `:184`). `http.client.IncompleteRead` and non-verification `ssl.SSLError` during a read are not caught; the run would crash, but writes are atomic, so nothing is corrupted. |
| N6 | Low | dataset-engineer | **RESOLVED** | The S3 partition dates now use UTC 2026-11-20 (`dataset-manifest.yaml:181`, `:253`, `:310`), matching the UTC retrieval timestamps (`:176`, `:248`, `:305`). |
| N7 | Low | build-engineer | OPEN | **`__pycache__/` is not in `.gitignore`.** None exists now (Glob returned 0). |
| N8 | Low | build-engineer | OPEN | **The Hadoop assembly also bundles config 1.4.10.** `jobAssemblySettings(dropScalaLibrary = false)` excludes nothing (`build.sbt:72-78`, `:92-94`). build-engineer's risk note names only Spark. |
| N9 | Low | domain-engineer | OPEN | **Config file comment describes a layering that does not exist.** `scholarchain-config.v1.conf:5-6` mentions "an environment-specific file that overrides this one", but `ConfigLoader` loads exactly one file with no merge (`ConfigLoader.scala:47-66`). `parseResources` (`:58`) merges every classpath resource with the same name. HOCON `include` (including `include url(...)`, a network fetch) is not blocked; this is already raised for a human decision. |
| N10 | Low | domain-engineer | OPEN | **Three validation gaps in core.** `AuthorshipRecord` does not check that `mentionId` derives from (`workId`, `position`) (`Records.scala:316-327`), unlike `AuthorMention` (`:268`). `GraphEdge` accepts duplicate `evidenceIds` even though the ID deduplicates them (`:496-507`). `Doi.normalize` uses `String.strip()`, which does not remove U+00A0 (`Identifiers.scala:58`). |
| N11 | Low | test-engineer | OPEN | **Test fixtures use real ORCID iDs.** Besides the fictitious 0000-0002-1825-0097, they use 0000-0001-5109-3700 and 0000-0002-1694-233X (`DoiContractSuite.scala:53-58`, `RecordValidationContractSuite.scala:45`, `:52`). Both appear in ORCID's documentation, but prefer clearly fictitious values. |
| N12 | Info | dataset-engineer | OPEN (disclosed) | **Crossref was run three times, not once.** Run 1 hung on TLS, run 2 was killed, run 3 succeeded. Run 1's 11 transport failures are kept in `errors.jsonl:1-11` and are not counted as coverage. Run 3 reports `already_present: 0` (`fetch_crossref_run3.log:102`), which confirms no response was stored before it. |
| N13 | Info | dataset-engineer | OPEN | **Duplicate-key detection uses 64-bit hashes** (`dblp_select.py:101-102`, `:177-182`). A collision would wrongly exclude a record. 0 duplicates were observed, so there was no effect. |
| N14 | Low | dataset-engineer | NEW | **One stale sentence after the B3 repair.** `dataset-manifest.yaml:206` says "fetch_common.py now verifies TLS against the certifi bundle". certifi is now only the last fallback, after the default store and `SSL_CERT_FILE`. |
| N15 | Low | dataset-engineer | NEW | **`spot_check.py` would crash on a missing Crossref year.** `spot_check.py:58` computes `dblp_year - crossref_year` without a guard, so a Crossref record with no date would raise `TypeError`. Every current record has a year. The OpenAlex branch is guarded (`:73`). `validate_manifest.py:105-109` cross-checks the year and author-count figures but not the OpenAlex title figure. |

---

## Verification of facts (updated)

| Claim | Evidence read | Result |
|---|---|---|
| dblp.xml SHA-256 `f9f1f1e1…2ef3` | `datasets/raw/dblp/sha256_dblp.xml.log:1`; summary `input_xml_sha256` | VERIFIED (matches the manifest `:79`) |
| dblp.xml size 5,405,851,335 bytes | summary `input_xml_bytes` (from `stat`, `dblp_select.py:211`) | VERIFIED |
| dblp.dtd SHA-256 `2d864498…1ef6`, 13,134 bytes | `sha256_dblp.dtd.log:1`; summary | SHA VERIFIED; size UNVERIFIED (no stored stat output) |
| Snapshot 2026-11-14 = newest `mdate`, stated as a lower bound | summary `newest_mdate`; code at `dblp_select.py:135-137`; manifest `:67-76` | VERIFIED; the method is stated honestly |
| File mtime 2026-11-15, server listing "1.0G", release DOI `10.4230/dblp.xml` | no artifact | UNVERIFIED |
| 13,108,714 records; 8,518,730 article plus inproceedings; exclusions 3 / 1,025,794 / 622,385 / 0; 6,870,548 eligible; 86 years | `eligible_index.summary.json`; `index_run.log` | VERIFIED. The counts sum correctly. The zeros for `invalid_key`, `empty_title` and `non_numeric_year` follow from `Counter` semantics (`dblp_select.py:125`, `:165`). |
| 1,000 selected; 430/570; 86 years; 155 venues; 999 cells; seed 441; output SHA `52bbb09f…` | provenance JSON; TSV has 1,001 lines (header + 1,000); type greps 430/570; year quotas sum to 1,000 over 86 years; no `n_authors` below 2 | VERIFIED. I did not replay the validator's distinct-key check. |
| Not "first N" | `dblp_select.py:293-324` | VERIFIED |
| lxml flags (`load_dtd`, `resolve_entities`, `no_network`, `huge_tree`) | `dblp_select.py:120-123`; summary `parser` | VERIFIED |
| Crossref 100/99/1/0 | run 3 log; 99 envelopes with status 200; one 404 envelope (DOI `10.48550/arxiv.cs/0608118`) | VERIFIED. The DataCite attribution is labelled an inference. |
| OpenAlex 100/100/0/0 | run 1 log; 100 envelopes with status 200 | VERIFIED |
| ORCID 294 found (89 from Crossref, 294 from OpenAlex) / 100 retrieved / 0 / 0 | ORCID run 1 log; `orcid_ids.json`; 100 envelopes with status 200 | VERIFIED |
| ORCID: 24 with zero public works | grep `"group": []` = 24 files | VERIFIED |
| 32 Crossref records with an ORCID, 89 entries | sample: 67 rows with count 0 out of 99; the non-zero counts sum to 89 | VERIFIED |
| Spot-check: 3 DOIs, aggregate 99/99, 90/99, 99/99 | `doi_spot_check.json:6-55`; TSV lines 14, 155, 688; selection method in `spot_check.py:35-38` | VERIFIED |
| Crossref year histogram {−29, −21, −11, −5, −2, −1: 1 each; 0: 90; +1: 3} | `doi_spot_check.json:56-65`; my recomputation from the samples and TSV | **VERIFIED (independently recomputed)** |
| OpenAlex year 78 equal / 22 differ; histogram {6 × −n: 1 each; 0: 78; +1: 16} | `doi_spot_check.json:66-81`; my recomputation | **VERIFIED (independently recomputed)** |
| OpenAlex title equal 100/100, author count equal 100/100 | `doi_spot_check.json:68`, `:71`; script logic at `spot_check.py:68-72` | Traceable to the script and artifact; not recomputed by me |
| DBLP license: CC0 primary, ODC-By secondary | manifest `:59-66`, both quotes recorded verbatim | Quotes UNVERIFIED by me (no network); now internally consistent |
| Crossref license: UNVERIFIED, reuse terms quoted; OpenAlex CC0; ORCID terms (CC0 only for the Public Data File) | manifest `:164-170`, `:235-240`, `:293-299` | UNVERIFIED by me (no network); none fabricated on its face |
| Access requirements (Crossref pools, OpenAlex keyless use, ORCID token) | manifest `:171-175`, `:241-247`, `:300-304` | UNVERIFIED by me. ORCID is recorded honestly: the documented token requirement, the observed anonymous 200s, and a manual action. |
| Instructor approval | manifest `:18-31` | VERIFIED (matches `prompt.md:34`) |
| Exploratory files recorded as non-graded and unmodified | manifest `:37-47`; session-start git status shows them neither modified nor untracked | VERIFIED; checksums not recomputed by me |
| `doi_spot_check.json` SHA `37a56c00…`; validator results 115/0 and 117/0; secrets scan 345 files, 0 matches | reported only | UNVERIFIED (I cannot run commands) |

## Secrets and personal data

- **Contact email:** it is sent only in the User-Agent header and never stored; envelopes keep only `Content-Type`. `redact_url` covers `mailto`, `api_key`, `apikey`, `key`, `token` and `email` (`fetch_common.py:33`).
- **Post-repair grep:** I re-grepped `datasets/` outside `raw/` for email patterns, ORCID iD patterns, `api_key=` and `Bearer <token>`. All returned 0 matches. The earlier greps of the raw crossref/openalex/orcid trees and logs for email patterns and `mailto` also returned 0.
- **API keys:** none hardcoded. The OpenAlex key is read from the environment and sent only in a header.
- **Committed samples:** a grep for name, ORCID, affiliation and abstract keys returned 0. The ORCID output is aggregate counts only.
- **Config files:** the shipped `.conf` holds `schema-version` only, and the test resources are synthetic.
- **Leftovers:** no `__pycache__` remains.

## Scripts

All of the following pass:
- **Bounded:** default ≤100, with refusal above that (`enforce_limit`). `dblp_select` sizes are limited to 1,000, 10,000 and 100,000. No script downloads a corpus.
- **Resume:** existing envelopes are skipped, and transport failures write no envelope, so they are retried on the next run.
- **Retry and backoff:** on timeouts, 429 and 5xx.
- **404s:** stored as missing.
- **Envelopes:** each records `retrieved_at` and the redacted URL.
- **DOI encoding:** `quote(safe="")`.
- **Pacing:** 0.5 s between requests.
- **Spot check:** offline and deterministic.

Notes N2, N4, N5 and N15 apply. The repair changed docstrings, `spot_check.py` and `validate_manifest.py` only. Fetch behaviour and the TLS logic are unchanged.

## Contracts (unchanged since the first review)

- **Records and enums:** all 20 records, each except `Provenance` carrying `schemaVersion` and a validated `provenance`. The 6 enums have frozen wire names.
- **Stable ID principles** (`Identifiers.scala`):
  - work: namespace plus source ID, with a fallback (`:221-244`)
  - researcher: checksum-validated ORCID, otherwise versioned `sc1` (`:246-252`)
  - coauthor edge: hash of the canonical pair, window and policy version (`:254-264`)
  - chunk or profile (`:266-282`)
  - graph edge (`:284-304`)
  - run: content hash with no clock, plus `attempt` (`:326-345`)
- **Pair canonicalization:** deterministic `compareTo` ordering, with self-pairs rejected.
- **DOI prefixes:** all the listed prefixes are covered.
- **Identity uncertainty:** carried as confidence plus outcome.
- **Validation:** rejects every listed case: missing provenance, out-of-range confidence, unsupported schema version, malformed IDs, reversed pairs, and start year > end year.
- **No hardcoded values:** a grep of the main sources for buckets, endpoints, models, account IDs, `ConfigFactory.load`, `getProperty` and `getenv` found only one hit, a doc comment.
- **Config precedence:** the environment, then one explicit file. `setUseSystemEnvironment(false)` is set, and `ConfigLoader.scala` is the only importer of typesafe config.

## Tests (unchanged)

- **Real behaviour:** the tests assert actual behaviour, not constants.
- **Coverage:** every area in the work order's "Contract tests" list is covered.
- **Test count:** statically 2+24+11+27+18+12+23+66 = 183 cases, matching the reported total.
- **Marker:** `.claude/.last-test-run` reads `exit=0`.
- **Not verified by me:** the pass result and both mutation proofs are UNVERIFIED (no shell). No Scala source changed in repair 01, so no new sbt run is required.

## Ownership and boundaries

- **Path owners:**
  - `build.sbt` and `project/Dependencies.scala`: build-engineer
  - `project/Versions.scala`: dependency-updater
  - `datasets/**`, including every repair-01 edit: dataset-engineer
  - `modules/core/src/main/**`: domain-engineer
  - `modules/core/src/test/**`: test-engineer
  - `docs/agent-runs/**`: assembled by the orchestrator
- **Boundaries:** no constitutional file changed. Only `core` has main sources, so there is no Hadoop, Spark, storage, service, Terraform or deployment code.
- **Who wrote which file:** I can see only the reports and the hook design, not authorship.
- **Build:**
  - `Versions.typesafeConfig = "1.4.10"` is in the ledger only.
  - The reference uses a single `%`.
  - Scope is core Compile, and `reference.conf` is concatenated in assemblies.
  - Note N8 applies.

## Items needing a human decision

1. Approve `certifi` as an optional runtime dependency, or require the stdlib `SSL_CERT_FILE` route.
2. ORCID: whether larger fetches must use registered Public API credentials, given the documented token requirement and the observed anonymous access.
3. Crossref's formal license (recorded as UNVERIFIED), and whether record-level ORCID samples may ever be committed.
4. Which DOI normalisation rule applies across the pipeline (N1).
5. The domain-engineer items:
   - freeze the ID formulas, hash tags and wire shapes
   - environment-over-file precedence, with blank values counted as unset
   - the config key set and the string form of `graph.bridge.parameters`
   - forbidding HOCON `include`
   - `RunManifest` without a `Provenance`
   - `attempt` as the way to separate reruns
6. Accept typesafe config 1.4.10, released 13 days ago, and the EMR classpath conflict risk, which is unverified.
7. Whether the one-work-per-year floor is wanted at larger selection sizes.
8. Visit the license and access URLs to confirm the quotes, including the DBLP README's ODC-BY sentence; I had no network.
9. Phase 03 acceptance and the human gate. The routing of notes N1-N15 is at the human's discretion.

---

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only verified re-review of the full Phase 03 diff after dataset-engineer repair 01. Confirm B1-B3 are resolved, recompute the consistency histograms from the committed samples, confirm the B2 quote is recorded verbatim, and check for regressions, secrets and new dependencies.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no shell). Read, Grep and Glob calls only, all of which returned results.
TEST RESULTS: none run. The reported sbt check results (Total 183, Failed 0) and the two mutation proofs are UNVERIFIED from files. Supporting evidence: the marker reads "exit=0", the static count of test cases is 183, and no Scala source changed in repair 01. The repair-01 validator runs (115/0, --full 117/0) and the secrets scan (345 files, 0 matches) are UNVERIFIED.
VALIDATION RESULTS: Verdict APPROVE WITH NOTES. B1 RESOLVED: the Crossref and OpenAlex year histograms were independently recomputed from crossref_works_sample.jsonl, openalex_works_sample.jsonl and dblp_selection_1000.tsv, and match doi_spot_check.json exactly. B2 RESOLVED: the secondary-license quote is recorded verbatim at dataset-manifest.yaml:65 (content UNVERIFIED by reviewer, no network). B3 RESOLVED: certifi is declared optional, the stdlib route is documented, a MANUAL ACTION item exists, and the TLS code is unchanged with verification never disabled. N3 and N6 are resolved. No new third-party import, no secrets or ORCID iDs in committed paths, no __pycache__. Open notes: N1, N2, N4, N5, N7-N13, plus new N14 and N15.
ASSUMPTIONS: The coordinator's statement that the core, test and build files are unchanged since the first review is consistent with what I read. The session-start git status is accurate for the exploratory files and for the absence of constitutional changes. Sample years equal the values the script uses (Crossref issued-first fallback; OpenAlex publication_year), per make_samples.py and spot_check.py.
RISKS: License and access quotes are unverified without network access, and no page captures are stored. The DOI normalisation differs between datasets and core (N1). A stale DBLP cache could be reused silently (N2). The EMR classpath conflict for config 1.4.10 is unverified. ORCID anonymous access may stop working. spot_check.py would crash on a Crossref record with no date (N15).
MANUAL ACTION REQUIRED: The human decides the 9 items listed under "Items needing a human decision" and writes docs/agent-runs/phase-03-contracts-datasets/human-gate.md. Optionally, the orchestrator routes the open notes to dataset-engineer (N2, N4, N5, N12-N15), domain-engineer (N1, N9, N10), build-engineer (N7, N8) and test-engineer (N11).
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Human decision on this verdict and the Phase 03 human gate.
```

# Repair 02 (canonical DOI rule, DBLP cache N2, N7, N15)

Orchestrator note: code-reviewer verdict 1 for repair 02 follows, verbatim as delivered. The reviewer had no SubagentHandback tool and returned it as its final message. Before the review the orchestrator observed `git diff -- .gitignore`. It shows only the added three-line block (a comment, `__pycache__/`, `*.pyc`) after `*.log`, which answers R10. R9: the orchestrator's review order wrongly said that the Phase 03 human-gate.md was not yet written. It exists as a human-written draft, and no agent edited it. The repair 02 agent reports, including both mutation proofs, have since been appended to agent-report.md. The orchestrator routed R1-R4 and R8 to dataset-engineer and R5-R7 to test-engineer, and requested a re-review. The outcome is recorded below as verdict 2.

## code-reviewer verdict 1

## Repair 02 (canonical DOI rule, DBLP cache N2, N7, N15)

**Reviewer:** code-reviewer. I could only read files (Read, Grep, Glob); I had no shell and no network.
**Baseline:** HEAD 04a455d. The working tree is uncommitted and most Phase 03 files are untracked, so I read them directly.
**Delivery:** no SubagentHandback tool was available in this session, so this verdict is returned as my final message for the orchestrator to store.

### Verdict: APPROVE WITH NOTES

N1, N2, N7 and N15 are resolved. I read both DOI normalisers line by line. Both match the ratified rule and agree with each other on every row I traced by hand. The two vector-file readers are equivalent. No frozen ID formula, hash tag or JSON wire shape changed. The `--xml-sha256` check, verification before reuse, refusing without writing outputs, temp-then-rename writes and the `adopt` policy refusal are all implemented as ordered. No finding blocks approval. Seven low-severity documentation and test gaps and three informational items are listed below.

---

### Findings

| # | Sev | Owner | Location | Finding and evidence |
|---|---|---|---|---|
| R1 | Low | dataset-engineer | `datasets/schemas/dblp.md:37` | **Stale policy version.** The schema note says the metadata holds eligibility policy `"1"`. The code says `"2"` (`dblp_select.py:71`), and so does the manifest (`dataset-manifest.yaml:162`). The work order's item B8 requires the notes to describe actual behaviour. |
| R2 | Low | dataset-engineer | `dblp.md:51-53`; `dataset-manifest.yaml:155-157` | **Unsupported predictions about the deferred reindex.** The docs say the reindex "may differ from the existing 10K and 100K selections by up to 3 records" and that "the 1K selection is unaffected". (a) I grepped the DOI column of all three selection TSVs for whitespace: 0 matches in the 1K, 10K and 100K files (the control pattern matched all 100,000 rows of the 100K file). None of the three rejected DOIs is selected anywhere, so the direct effect is 0. (b) Removing eligible records changes the per-year counts and per-venue square-root weights used by largest-remainder allocation (`dblp_select.py:530-561`). The indirect effect is therefore neither bounded by 3 nor excluded for 1K. (c) The 3-DOI count comes from a full-file old-rule versus new-rule comparison, but no command or output for it is stored in the repo, so the count is UNVERIFIED. Fix: reword to "differences unquantified until the approved reindex", and record the comparison command and output in the agent report. |
| R3 | Low | dataset-engineer | `dataset-manifest.yaml:145-148` | **Commands shown as runnable.** `select_1000`, `select_10000` and `select_100000` are listed as runnable. Only `select_50000` is marked blocked. With no `eligible_index.meta.json` present (confirmed by Glob of `derived/`), every `select` call now exits 2. Line 150 says so, but the commands block does not. |
| R4 | Low | dataset-engineer | `dblp.md:41-46`; `dataset-manifest.yaml:165` | **Docs miss select's behaviour without inputs.** `select` runs with `need_inputs=False` (`dblp_select.py:228-229`, `:270-284`, `:677`). When the XML or DTD is absent, it proceeds on the verified cache alone. Neither document says this. `dblp.md:45-46` also says `--xml-sha256` is "checked against the actual file". In `select` it is compared with the metadata (`:681-684`), and reaches the file only indirectly, through the default full hash. The manifest's wording at `:167` is accurate. |
| R5 | Low | test-engineer | `tests/datasets/test_dblp_cache.py` (no `adopt` test) | **The `adopt` refusal is untested.** It is human decision B7 (`dblp_select.py:303-308`). I confirmed by reading the code that it refuses before any hash. For the real legacy summary, which has no policy field (`eligible_index.summary.json` shows only `script_version: "1.0.0"`), the default policy `"1"` differs from `"2"`, so it refuses. No test covers either the refusal or the "nothing written" property. |
| R6 | Low | test-engineer | `datasets/scripts/spot_check.py:61-65`, `:54` | **The N15 change has no test.** The fix is correct: a missing year is counted and skipped, `year_equal` is null, and the count is cross-checked against the manifest (`validate_manifest.py:243`). But the project rule requires a test for every behaviour change, and none exercises a Crossref record without a year. |
| R7 | Low | test-engineer | `validate_manifest.py:85-162` | **The Python reader has no error-case tests.** The Scala reader is tested on 17 bad cells (`DoiVectorsContractSuite.scala:36-62`); the Python mirror has none. I checked parity by reading both: same raw-character rejection, same `\\`, `\uXXXX`, `\UXXXXXXXX` and `\e` rules, same rejection of an adjacent high and low surrogate, same hex set, BOM, CR, header, 3-cell and empty-note checks. Scala's Int overflow for `\U` values ≥ 0x80000000 wraps only to negative numbers, which are rejected, so the two readers behave the same. Parity currently rests only on reading the code. |
| R8 | Info | dataset-engineer | `doi_canonical.py:24-26` | **Docstring caveat is too narrow.** It says the normaliser is idempotent only for DOIs without `%`. In fact `normalize_doi(normalize_doi(x)) == normalize_doi(x)` for every accepted input: the output starts with `10.`, has no prefix and no edge whitespace, so the second pass treats it as bare. The caveat applies only if the output is wrapped back into a URL. Harmless. |
| R9 | Info | orchestrator / human | `human-gate.md:18,22,60`; `agent-report.md` | **Evidence state.** A Phase 03 `human-gate.md` already exists as a human-written draft with placeholders (it says at `:45` that the human wrote it), contrary to the review order's statement that it is not yet written. `:60` lists N10 as open, but this repair fixes its U+00A0 part (`Identifiers.scala:71-74`, `:151-152`). The Repair 02 agent reports are not yet appended to `agent-report.md` (grep for "Repair 02" found 0 matches), so the required mutation proof is UNVERIFIED. |
| R10 | Info | build-engineer | `.gitignore:29-31` | **N7 present; "nothing else" unverified.** `__pycache__/` and `*.pyc` are present, and Glob finds no `__pycache__`. I cannot diff against HEAD with read tools, so I cannot confirm that nothing else changed. |

---

### Check results

**DOI rule, Scala** (`Identifiers.scala:62-170`): PASS. I confirmed the literal whitespace characters with exact code-point greps (lines 72-74 contain U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F and U+3000).
- **Lone surrogates:** rejected on the raw input (`:90-100`, `:154`).
- **Prefixes:** the four URL prefixes and `doi:` are matched ASCII-case-insensitively (`:80-82`). Only U+0020 is skipped after `doi:` (`:161`).
- **Percent-decoding:** URL form only, strict escapes, strict UTF-8 via `CodingErrorAction.REPORT`, so overlong forms, encoded surrogates and truncated sequences are rejected (`:103-133`).
- **Case:** ASCII-only lowering (`:78`, `:167`).
- **Validation:** full-shape check with whitespace and control characters excluded, code point by code point (`:135-145`).

**DOI rule, Python** (`doi_canonical.py:54-76`): PASS, and equivalent to Scala. I confirmed the literal characters in `_WS` by grep (`:35-36`).
- **Prefixes:** `re.ASCII | re.IGNORECASE`, which avoids the Kelvin-sign and long-s case-folding traps.
- **Percent-decoding:** `_BAD_PERCENT` runs before `unquote_to_bytes`, then a strict UTF-8 decode.
- **Case:** `translate` lowers A–Z only.
- **Validation:** `_CANON.fullmatch` with explicit ASCII `[0-9]`.

**Tricky rows I traced by hand in both languages** (all agree with `doi-normalization-vectors.tsv`):
- `:26/:77/:81`: U+0085 is trimmed at the ends but rejected inside, whether raw or decoded.
- `:110-112`: lone surrogates are rejected.
- `:107-109`: an astral character via raw text, `\U` escape, or `%F0…` is accepted.
- `:113`: `doi:` followed by a tab is rejected.
- `:61`: `%252F` becomes `%2f`, decoded once only.
- `:51`: `10%2E1000` is decoded in URL form.
- `:53-60`: `%` stays literal in bare and `doi:` input.
- `:62-70`: malformed escapes and invalid UTF-8 are rejected.
- `:115-118`: stacked prefixes are rejected.
- `:119`: a space after the URL prefix is rejected.
- `:96`: Arabic-Indic digits are rejected.
- `:104-106`: non-ASCII case is kept.

The 121 data rows (file lines 6-126) cover every case required by the work order's item 2.

**Frozen contracts:** PASS.
- All hash tags are still `.v1`, and the ID formulas are unchanged (`Identifiers.scala:247-449`).
- `work:doi:<d>` validation depends on `Doi.normalize` being idempotent, which holds (see R8).
- The DOIs in fixtures (`10.1000/abc.1` in `SerializationContractSuite.scala:17-19`, `Fx.scala:36` and `IdentifierContractSuite.scala:35-36`) normalise to the same value under both rules.
- `DoiContractSuite` still asserts that bare `%2F` is kept (`:31-33`).

**Encoding outgoing requests exactly once:** PASS.
- The fetchers normalise the DOI, then build the URL as `BASE + doi_path_segment(d)`, which is `quote(d, safe="")` after a canonical-form guard (`fetch_crossref.py:32,38`; `fetch_openalex.py:35,44`; `doi_canonical.py:94-98`).
- `urllib.request` does not re-encode.
- `renormalize_check.py:55` compares every stored `request_url` with `BASE + quote(doi)`.

**N2 cache checks:** PASS.

| Item | Status | Evidence |
|---|---|---|
| 1. Fingerprint | PASS | SHA-256 of canonical JSON of the index-format version, policy version, XML SHA-256 and DTD SHA-256 (`dblp_select.py:154-167`). The metadata also records file checksums and sizes (`:194-212`). |
| 2. `--xml-sha256` | PASS | Compared with the actual digest in `index`/`all` (`:653-655`) and `adopt` (`:309-312`). In `select` it is compared with the metadata after the actual file hash is verified (`:677-684`; wording in R4). The argument must be 64 lower-case hex digits (`:639-641`). |
| 3. Verify before reuse | PASS | Metadata, versions, fingerprint, cache and summary size and SHA, DTD, then XML (`:215-287`). `index` rebuilds and `select` exits 2 before any write (`:660-668`, `:700-705`). |
| 4. Seed and size | PASS | Not part of the fingerprint; recorded in provenance (`:596-603`). Tested at `test_dblp_cache.py:291-306`. |
| 5. Temp then rename | PASS | Temp files plus `os.replace`, metadata written last (`:170-177`, `:341-349`, `:463-466`, `:584-590`). |
| 6. Preserve existing data | PASS | `derived/` has no `meta.json` and no temp files. The legacy summary is untouched (`script_version` 1.0.0). |
| 7. Full XML hash | PASS | Skipped by human decision. `adopt` refuses on the policy check before hashing. |
| 8. Docs | PASS except R1, R3, R4 | |

**DBLP cache tests:** PASS for the required cases. The 24 tests are: verified cache hit (4); same-size XML edit with mtime preserved (1); modified DTD (1); policy bump (1); fingerprint inputs (1); corrupted, truncated or deleted cache, corrupted summary, malformed metadata, unsupported version and tampered fingerprint (7); missing metadata (3); `--xml-sha256` cases (5); seed and size do not invalidate (1). Gap: R5.

**N15:** PASS for the code; no test (R6).

**Instructor-approval correction:** PASS. `dataset-manifest.yaml:18-35` no longer claims instructor approval for the enrichment subsets, cites `docs/agent-runs/instructor-approvals.md`, and lists its four items exactly as they appear in `instructor-approvals.md:5-8,13`. 50000 is in `ALLOWED_SIZES` (`dblp_select.py:84`) and the manifest (`:123-124`), and 100000 is kept.

**Ownership by path** (per `docs/agents.md` section 1): PASS.
- `.gitignore`: build-engineer.
- `datasets/**`: dataset-engineer.
- `Identifiers.scala`: domain-engineer.
- The TSV and Scala suites: test-engineer.
- `tests/**`: test-engineer.
- `instructor-approvals.md` and `human-gate.md`: human, per `human-gate.md:45`.

I cannot see who actually wrote each file. No constitutional file is in the change set.

**Secrets:** PASS for the code I read. Contact email and keys are read from the environment and sent only in headers, and `redact_url` is unchanged. The scan result (347 files, 0 matches) is UNVERIFIED.

**Network:** PASS. `renormalize_check.py`, `spot_check.py` and `validate_manifest.py` make no network calls. The only fetch code paths are the unchanged fetchers, and nothing here shows that they ran.

**Deferred items:** PASS. Multi-generation publication and interruption-recovery tests are recorded at `dataset-manifest.yaml:175` and `dblp.md:54-55`.

**Your question about `dblp_1000.csv` in `renormalize_check.py`:** the reading is acceptable. That file stores bare DBLP DOIs in their original case, so comparing the canonical value with `str.lower()` checks that the rule changes nothing except case. Using Unicode lowering is the stricter choice here: any non-ASCII capital would show up as a change, not hide one. A Grep for `%` in `datasets/samples` matched only `request_url` values in the two JSONL samples (100 lines each), so no bare DOI there depends on percent-decoding. The total of 2298 is consistent with 100 + 100 + 3 + 1000 + 1000 + about 95 `ee` values from `dblp_100.csv`, but I could not run it.

---

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Final independent read-only review of Phase 03 repair 02: the canonical DOI rule in Scala and Python with a shared vector file, DBLP index cache validation (N2), .gitignore (N7), the Crossref no-year case (N15), the instructor-approval correction and size 50000.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no shell). Read, Grep and Glob calls only, all of which returned results.
TEST RESULTS: none run. Reported by the orchestrator and UNVERIFIED by me: sbt check Total 307 / Failed 0; validate_manifest 238/0 with TSV sha256 4b669451…; unittest Ran 24 OK; renormalize_check 2298 checked / 0 changed; check_no_secrets 347 files / 0 matches; sbt jdkInfo Temurin 17.0.21. Supporting evidence from files: .claude/.last-test-run reads "exit=0"; a static count of 183 prior tests + 121 vector rows + 3 meta tests = 307; tests/datasets/test_dblp_cache.py has 24 tests; DoiContractSuite has 24. The mutation proof is UNVERIFIED because the repair 02 reports are not yet in agent-report.md.
VALIDATION RESULTS: Verdict APPROVE WITH NOTES. N1, N2, N7 and N15 resolved. Scala and Python normalisers match the ratified rule and each other (whitespace literals confirmed by code-point grep; hand-traced rows for surrogates, U+0085, doi: plus tab, %252F, 10%2E1000, stacked prefixes, malformed escapes and invalid UTF-8). Vector-reader parity confirmed by reading both. Frozen IDs, hash tags and wire shapes unchanged. N2 items 1-8 implemented (docs gaps R1, R3, R4). Outgoing DOI encoded exactly once. Ownership consistent by path. Findings: R1-R7 Low, R8-R10 Info.
ASSUMPTIONS: The session-start git status accurately lists the changed files. The files changed in repair 02 are the ones the orchestrator listed. The derived files were not modified (consistent with no meta.json, no temp files, and a legacy summary still at script 1.0.0).
RISKS: The deferred reindex may change selections beyond the documented "up to 3 records", including the 1K selection, and the 3-DOI comparison has no stored evidence (R2). select proceeds on the cache alone when XML or DTD are absent (R4). adopt refusal, the N15 path and the Python reader's error cases are untested (R5-R7). By default every select hashes the full 5.4 GB XML. The .gitignore "nothing else" claim is unverified (R10).
MANUAL ACTION REQUIRED: The human decides on this verdict and completes docs/agent-runs/phase-03-contracts-datasets/human-gate.md (draft placeholders at lines 18 and 22; line 60 should note that N10's U+00A0 part is resolved). The orchestrator appends the repair 02 agent reports to agent-report.md and this verdict to review-report.md. Optionally, route R1-R4 and R8 to dataset-engineer and R5-R7 to test-engineer. Human approval is still required for the deferred reindex and the full XML hash.
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Human decision on this verdict and the Phase 03 human gate.
```

## code-reviewer verdict 2

Orchestrator note: verdict 2 follows verbatim. The reviewer had no SubagentHandback tool and returned it as its final message. The reviewer's one-line preface, outside the verdict, read: "I could not approve this repair yet: some of the routed fixes are not in the files. My verdict is **REQUEST_CHANGES**, limited to the manifest and the stored evidence. Nothing I read shows a code or test regression." The orchestrator verified V2-1 and V2-2 against the file before acting. It routed V2-1, V2-2 and V2-3 (and optionally V2-5) to dataset-engineer. It closed V2-4 itself by appending both routed-fix reports, with a correction note, to agent-report.md.

**Reviewer:** code-reviewer. I only read files (Read, Grep, Glob), with no shell and no network. **Delivery:** no SubagentHandback tool was available, so this is my final message.

### Verdict: REQUEST_CHANGES

The routed fixes for R1, R4, R5, R6, R7 and R8 are in the files. R2 is partly fixed and R3 is still open. Some changes the review order describes are not in the files: R3's "blocked" markers and the new `newly_rejected_count_check` block. The agent reports for these fixes are also not stored. Three things must be done before I can recommend approval:
- Mark the three remaining select commands as blocked.
- Add the missing manifest block, or remove the reference to it.
- Store the fix reports in agent-report.md.

### R1-R8 re-check

| # | Status | Evidence |
|---|---|---|
| R1 | Resolved | `datasets/schemas/dblp.md:37` now says policy `"2"`. This matches `dblp_select.py:71` and `dataset-manifest.yaml:163`. |
| R2 | Partly resolved | **Fixed:** the wording. `dblp.md:59-62` and `dataset-manifest.yaml:155-158` now say direct effect 0 and indirect effect "unquantified until the human-approved reindex" for the 1K, 10K and 100K selections. The command to reproduce the count is at `dblp.md:56`. **Not fixed:** the comparison output is in no file. A Grep for `10405443` and `7932` matches nothing outside the samples, and the R-fix agent report is missing (finding V2-4). The basis of the count also differs from the recorded old rule (finding V2-3). |
| R3 | Open | `dataset-manifest.yaml:145`, `:146` and `:148` (`select_1000`, `select_10000`, `select_100000`) are still plain commands. Only `:147` (`select_50000`) says "blocked". `:150` still says select refuses to run. |
| R4 | Resolved | `dblp.md:45-49` describes `need_inputs=False` and the two meanings of `--xml-sha256`. `dataset-manifest.yaml:168-169` matches the code at `dblp_select.py:653-655` and `:677-684`. |
| R5 | Resolved | `tests/datasets/test_dblp_adopt.py` has 5 tests (details below). |
| R6 | Resolved | `tests/datasets/test_spot_check_no_year.py` has 4 tests (details below). |
| R7 | Resolved | `tests/datasets/test_vector_reader.py` has 27 tests (details below). |
| R8 | Resolved | `doi_canonical.py:24-28` now says the normaliser is idempotent for every accepted input, with the caveat limited to re-wrapping the output in a URL. The code body is unchanged apart from a two-line shift (`_WS` was at `:35-36` in verdict 1 and is at `:37-38` now). |

R9 and R10 were closed by the orchestrator's note at `review-report.md:211`. Its observed `git diff` of `.gitignore` is UNVERIFIED by me, because I have no shell.

**What the new tests prove**

- **R5, adopt refusal:**
  - `:96-99` refuses a legacy summary that has no policy field.
  - `:101-104` refuses an explicit mismatching policy. The expected value is read from the module, not hard-coded.
  - "Nothing written" is checked with byte snapshots of the cache directory and the whole temp tree, plus the absence of `meta.json` (`:75-90`).
  - "Refused before hashing" is a real check. `:106-115` deletes the XML first. If hashing ran before the policy check, `hash_with_identity` would raise an uncaught error, because `main` does no existence check for `adopt` (`dblp_select.py:643-647`).
  - A control test (`:117-128`) shows the same fixture is adopted when the policy matches.
- **R6, record with no year:**
  - The run does not crash and the record is counted (`:74-80`).
  - The record is left out of the histogram, its `year_equal` and `crossref_year` are null, and the aggregate does not count it as an equal year (`:82-95`).
  - Edge cases with zero and only no-year records are covered (`:97-111`).
  - The test patches `spot_check.RAW`, which `spot_check.py:40` reads as a module global, so the patch takes effect. Output goes to a temp `--out` file.
  - The mutation proof is UNVERIFIED (V2-4).
- **R7, vector-reader error cases:** every one of the 17 bad cells in the Scala suite (`DoiVectorsContractSuite.scala:38-54`) has a matching Python case:
  - `\x`, `\`, `a\`, `\u12`, `\u12G4` and `\U0001F60` (`:31-45`)
  - `\U0000D800` and `\U00110000` (`:49-53`)
  - `a\e` and `\ea` (`:55-57`)
  - the adjacent surrogate pair (`:59-61`)
  - the raw space, NBSP, U+200B, U+0001 and U+007F cases (`:63-67`)
  - `\uFF1G` (`:45`)
  - File-level checks are added: BOM, CR, invalid UTF-8, header, cell count, empty note, missing file, the line number in errors, and `REJECT` never being unescaped (`:109-163`).
  - The reader functions under test exist at `validate_manifest.py:63`, `:85` and `:127`. The module runs nothing on import (`:252`).
- **Test count:** 24 + 5 + 4 + 27 = 60, which matches the reported "Ran 60 tests".

### Findings

| # | Sev | Owner | Location | Finding and evidence |
|---|---|---|---|---|
| V2-1 | Low | dataset-engineer | `dataset-manifest.yaml:145,146,148` | **R3 is still open.** The three select commands are still shown as runnable, although the review order says they were marked blocked. |
| V2-2 | Low (new) | dataset-engineer | `dataset-manifest.yaml:155` | **Broken reference.** `current_state` says "see newly_rejected_count_check", but no such key exists anywhere under `datasets/`; the only Grep match is `:155` itself. The count, its basis and its observed output are not in the manifest. `validate_manifest.py` does not check for the key, so the reported 238 passes cannot catch this. Fix: add the block (command, observed output, the caveat about the old-rule basis, and the fact that the run was read-only), or remove the reference. |
| V2-3 | Low | dataset-engineer | `renormalize_check.py:25-35`; `dblp.md:57-59` | **The reconstructed old rule is not the recorded one.** Details below. |
| V2-4 | Info | orchestrator | `agent-report.md` (Repair 02 section, ends at `:1641`) | **The R-fix reports are not stored.** The section ends with the orchestrator's section C verification, which still says "Ran 24 tests". A Grep for `--dblp-xml`, `test_dblp_adopt`, `test_vector_reader` and `test_spot_check_no_year` under `docs/` finds nothing. The `--dblp-xml` output, the spot_check mutation proof and the 60-test run are therefore UNVERIFIED by me. R2's request to record the comparison output in the agent report is not met. |
| V2-5 | Info | dataset-engineer | `renormalize_check.py:24,44-50,71,77`; `dblp.md:57` | **Minor limits of `--dblp-xml`.** It always exits 0, so it is report-only. It matches line by line, so an `<ee>` element split across lines would be missed. Entities are not expanded; the docstring says so. The "about 75 s" runtime is unverified. None of this changes the default mode, which still exits 0 or 1 (`:137`). |

**V2-3 in detail.** The recorded pre-repair rule (orchestrator grep; also verdict 1, N1) is:
```
DOI_URL_RE = re.compile(r"^https?://(?:dx\.)?doi\.org/(10\.[0-9]{4,9}/\S+)$", re.IGNORECASE)
m = DOI_URL_RE.match(raw.strip()); return unquote(m.group(1)).strip().lower()
```
The old rule checked the DOI's shape on the raw text, before percent-decoding. The reconstruction (`_OLD_OK`, `10\.[0-9]{4,9}/.+` with DOTALL) checks the decoded text instead. Calling `.strip()` before or after `.lower()` makes no difference.
- **The reported 3 newly rejected URLs are consistent with the recorded rule.** All three are `%20` forms. Raw `%20` matches `\S`, so the old rule accepted them, and it kept the interior space after decoding.
- **The reported "newly accepted: 0" and "different value: 0" are not established against the recorded rule.**
  - Some URLs have `.`, digits or `/` percent-encoded inside the `10.NNNN/` prefix, for example `10%2E1234/x`. The real old rule rejected these, while both the reconstruction and the canonical rule accept them. Such URLs would be newly accepted, but the script cannot show them.
  - In the other direction, URLs with raw interior whitespace would be falsely reported as newly rejected. None appears in the reported list.
- `dblp.md:57-58` correctly calls the baseline an assumption, but it describes the reconstruction rather than the recorded rule.
- Fix: use the exact recorded regex and steps in `_old_rule`, rerun the comparison, and cite the recorded source line in `dblp.md` and the manifest.

### Regression checks (on the final files)

| Check | Result | Evidence |
|---|---|---|
| DOI behaviour | PASS | `doi_canonical.py:45-106` matches the verdict-1 description with only a +2 line shift. `normalize_doi_url_form` is unchanged (`:87-93`). |
| Cache-validation interface and exit codes | PASS | The `dblp_select.py` lines cited in verdict 1 are unchanged: `:71`, `:303-308`, `:639-641`, `:653-655`, `:677-684`, `:700-708`. |
| Frozen IDs, hash tags, wire shapes | PASS by scope | No Scala source is in this round's change list. |
| Ownership by path | PASS | Changed files are under `datasets/**` (dataset-engineer) and `tests/datasets/**` (test-engineer), per `docs/agents.md` section 1. Who actually wrote each file is not visible to me. |
| No network | PASS | `renormalize_check.py` imports only `csv`, `json`, `re`, `sys`, `pathlib`, `urllib.parse.unquote` and `doi_canonical` (`:14-22`). The tests make no network calls. |
| No writes to `datasets/raw/` or `datasets/samples/` | PASS | `--dblp-xml` only opens the file `"rb"` (`:44`). All three test files use `tempfile.mkdtemp` with cleanup, and spot_check writes to a temp `--out`. `derived/` has no `meta.json` and no temp files (Glob). |
| Hygiene | PASS | No `__pycache__` (Glob). `.claude/.last-test-run` reads `exit=0`. |

---

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only re-review (verdict 2) of Phase 03 repair 02 after routed fixes for verdict-1 findings R1-R4 and R8 (dataset-engineer: dblp.md, dataset-manifest.yaml, renormalize_check.py --dblp-xml mode, doi_canonical.py docstring) and R5-R7 (test-engineer: test_dblp_adopt.py, test_spot_check_no_year.py, test_vector_reader.py), including checks for regressions, ownership, network use and writes.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no shell). Read, Grep and Glob calls only, all of which returned results.
TEST RESULTS: none run. Reported by the orchestrator and UNVERIFIED by me: unittest "Ran 60 tests ... OK"; validate_manifest 238 passed / 0 failed with TSV sha256 4b669451...370d; check_no_secrets 347 files / 0 matches; renormalize_check 2298 checked / 0 changed; sbt check Total 307 / Failed 0 with marker exit=0; --dblp-xml output (3 newly rejected, 0 newly accepted). Supporting evidence from files: the static test count 24+5+4+27=60 matches; .claude/.last-test-run reads "exit=0"; no __pycache__ found. The spot_check mutation proof and the R-fix agent reports are not stored in agent-report.md, so both are UNVERIFIED.
VALIDATION RESULTS: Verdict REQUEST_CHANGES. R1, R4, R5, R6, R7 and R8 resolved. R2 partly resolved (wording fixed; output not recorded; the reconstructed old rule differs from the recorded one, V2-3). R3 open (manifest:145,146,148). New: broken newly_rejected_count_check reference at manifest:155 (V2-2). The new tests prove adopt refuses before hashing with nothing written, the no-year record is excluded and counted, and the Python reader covers all 17 Scala malformed-cell cases plus file-level cases. No regression found in DOI behaviour, the cache interface or exit codes; ownership by path, no-network and no-write checks pass.
ASSUMPTIONS: The orchestrator's grep of the pre-repair DOI_URL_RE (dblp_select.py lines 63, 88, 91) is accurate. The files I read are the final state the orchestrator tested. No Scala source changed in this round. The derived/ files were not modified (only file names checked, by Glob).
RISKS: The manifest still shows select_1000, select_10000 and select_100000 as runnable while every select exits 2. The newly-accepted count of 0 rests on a reconstructed old rule that misses URLs with a percent-encoded DOI prefix, so the eligibility change from policy 1 to 2 may be under-reported in that direction. The --dblp-xml figures and the spot_check mutation proof have no stored evidence. The 238-check validator does not catch broken manifest references.
MANUAL ACTION REQUIRED: The human decides on this verdict. The orchestrator routes V2-1, V2-2 and V2-3 to dataset-engineer (then reruns validate_manifest and renormalize_check --dblp-xml and records the observed output), and appends the R-fix agent reports (dataset-engineer and test-engineer, including the spot_check mutation proof and the --dblp-xml output) to agent-report.md (V2-4). The deferred reindex and full XML hash still need human approval.
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Human decision on verdict 2; if the fixes are routed, a re-review of the manifest, renormalize_check.py and the stored reports.
```

## code-reviewer verdict 3

Orchestrator note: verdict 3 follows verbatim. The reviewer had no SubagentHandback tool and returned it as its final message. The delivery escaped some characters as HTML entities, and the orchestrator restored them: `<br>` list separators in the V2-4 row, plus `<ee>` and `<confirm ...>`. No other change was made.

I recommend **APPROVE WITH NOTES** for verdict 3. All five verdict-2 findings are fixed in the files, I found no regressions, and no finding blocks the human gate. This is verdict 3 of the repair 02 review, ready to store verbatim. I had no SubagentHandback tool, so it is my final message. The human decides.

### Verdict-2 findings

| # | Status | Evidence |
|---|---|---|
| V2-1 | Resolved | `dataset-manifest.yaml:145`, `:146` and `:148` now say "not run; blocked until the human-approved reindex …". `:147` was already marked blocked. This matches `:150` and the exit-2 paths in `dblp_select.py:700-708`. |
| V2-2 | Resolved | `newly_rejected_count_check` now exists at `dataset-manifest.yaml:159-176`. It holds the command (`:160`), the run (`:161`), the old-rule basis (`:162`), the observed figures and all 3 DOIs (`:163-173`), the direct effect (`:174`), `read_only` (`:175`) and `known_limits` (`:176`). The reference at `:155` now points to a real key. |
| V2-3 | Resolved | See the next section. |
| V2-4 | Resolved | `agent-report.md:1643-1877` now stores four things:<br>- the orchestrator's correction note (`:1647`)<br>- the dataset-engineer R-fix report, including the first `--dblp-xml` output (`:1689-1698`) and the orchestrator's quote of the recorded rule (`:1730`)<br>- the test-engineer R5-R7 report, including the spot_check mutation proof (`:1788-1791`) and "Ran 60" (`:1760`)<br>- the V2 fix report, including the full rerun output (`:1833-1843`) |
| V2-5 | Resolved | The docstring now says the mode is report-only, always exits 0, matches line by line and does not expand entities (`renormalize_check.py:11-14`, `:42-44`, `:74`). The manifest says the same (`:176`). The default mode still exits 0 or 1 (`:140`). |

### Does the old-rule check now match the recorded rule? (V2-3)

**The regex is copied exactly.** `renormalize_check.py:29` is character-for-character the regex quoted at `review-report.md:407` and `agent-report.md:1730`.

**The steps match.** `_old_rule` (`:32-38`) runs `DOI_URL_RE.match(url.strip())`, returns None on no match, and otherwise returns `unquote(m.group(1)).strip().lower()`. That matches the recorded line 88 and line 91. `_OLD_OK` is gone (Grep: 0 matches).

**The earlier strip at `:53` changes neither result.**
- The input is decoded as ISO-8859-1. In that range, Python `str.strip()` removes exactly the canonical WS set (U+0009-000D, U+001C-001F, U+0020, U+0085, U+00A0).
- The old rule strips again anyway, which does nothing the second time.
- The `_URL` filter at `:27` accepts the same four prefixes as both rules (`doi_canonical.py:45-46`).

**Your question about percent-encoded characters in the `10.NNNN/` prefix:** yes, the script now counts these as newly accepted.
- Take `https://doi.org/10%2E1234/x`, or `10.1234%2Fx`. `DOI_URL_RE` needs a literal `10\.` and a literal `/` after the digits, so the old rule gives None.
- `normalize_doi_url_form` decodes it and accepts it.
- That hits the branch `old is None and new is not None` at `:62-63`, which adds it to `newly_accepted` and prints it at `:72-73`.

**Every possible outcome is counted.**
- Rejected by the canonical rule only → newly rejected (`:60`).
- Accepted by the canonical rule only → newly accepted (`:62`).
- Accepted by both with different values → different value (`:64`). This covers the old rule's `errors="replace"` (U+FFFD), its Unicode `.lower()`, and edge whitespace that appears only after decoding.

**Is "0 newly accepted / 0 different value" established?** Yes, against the recorded rule, within the documented limits below. The figures are internally consistent: old accepts 7,932,867, minus 3 newly rejected, plus 0 newly accepted, equals 7,932,864 accepted by the canonical rule. The pre-fix run reported the same figures (`agent-report.md:1691-1697`). The run itself is UNVERIFIED by me, because I have no shell.

**Manifest and dblp.md wording:** accurate.
- The figures, the basis and the limits match the script (`manifest:162-176`, `dblp.md:54-68`).
- The direct-effect claim (0 in the 1K, 10K and 100K selections) uses an exact match on column 5 (`agent-report.md:1844-1848`). It agrees with my verdict-2 grep.
- The indirect effect is correctly stated as unquantified (`manifest:156-158`, `dblp.md:66-68`).

### Regression checks

| Check | Result | Evidence |
|---|---|---|
| DOI behaviour | PASS | `doi_canonical.py:45-106` is unchanged, including `normalize_doi_url_form` at `:87-93`. |
| `dblp_select.py`, cache interface, exit codes | PASS | `:71` (policy "2"), `:303-308` (adopt refuses before hashing at `:309`), `:639-641`, `:653-655`, `:677-684` and `:700-708` (exit 2) are unchanged. |
| Ownership by path | PASS | Changes are only under `datasets/**` (dataset-engineer) and `docs/agent-runs/**` (orchestrator assembly; the human-gate file was not touched by agents). |
| No network | PASS | `renormalize_check.py:16-24` imports only csv, json, re, sys, pathlib, `urllib.parse.unquote` and `doi_canonical`. |
| No writes to `datasets/raw/` or `datasets/samples/` | PASS | `--dblp-xml` only opens the file `"rb"` (`:47`). `derived/` still holds the same 6 files, with no `meta.json` and no temp files (Glob). |
| Hygiene | PASS | 0 `__pycache__` (Glob). `.claude/.last-test-run` reads `exit=0`. |

### Findings (none blocking)

| # | Sev | Owner | Finding |
|---|---|---|---|
| V3-1 | Info | dataset-engineer | **Wrong line citation.** `renormalize_check.py:28`, `manifest:162` and `dblp.md:60` say the regex came from "lines 87-91" of the old `dblp_select.py`. The orchestrator's record (`agent-report.md:1730`) puts the regex at line 63, and lines 87-91 held the function body. Cosmetic. |
| V3-2 | Info | orchestrator / human | **The old rule's source is not in the repository.** The pre-repair `dblp_select.py` was untracked and has been overwritten. The recorded rule therefore rests on the orchestrator's session-start grep (`agent-report.md:1730`). dataset-engineer did not check it against history (`:1864`). It agrees with N1 (`review-report.md:60`) and with the old `ee` logic still at `renormalize_check.py:133`. I accept it as the record. |
| V3-3 | Info | none | **Process point.** In the R-fix run, dataset-engineer loosened its first baseline until it reproduced the documented count of 3 (`agent-report.md:1678`). That concern is now superseded: the baseline is the recorded rule, chosen independently of the result, and it gives the same figures. |
| V3-4 | Info | deferred to the reindex | **Raw text, not exactly what the index sees.** The figures are on raw `<ee>` text: entities are not expanded and matching is line by line. Both rules see the same text, so the comparison between them is fair. It does not prove the counts equal what the lxml-based index would see. This is documented (`manifest:176`, `renormalize_check.py:13-14`). |
| V3-5 | Low | test-engineer (optional) | **The new `--dblp-xml` mode has no test.** It is a new script mode, and dataset-engineer itself noted that no test covers it (`agent-report.md:1722`). It is a report-only diagnostic and changes no pipeline output. The human should either accept it as is or route a small fixture test. |

### Are all findings from verdicts 1 and 2 resolved?

- **Resolved:** R1, R2 (wording, stored output and exact basis), R3 (as V2-1), R4, R5, R6, R7 and R8, plus V2-1 to V2-5.
- **Closed by the orchestrator's note:** R9 and R10 (`review-report.md:211`). The `.gitignore` diff behind R10 is UNVERIFIED by me.
- **Deferred by human decision:**
  - the reindex, the 10K and 50K selections, adopt and the full XML hash (`human-gate.md:35`, `manifest:150-158`)
  - multi-generation publication and interruption-recovery tests (`human-gate.md:41`, `manifest:194`)
  - the earlier notes N4, N5, N9, N10, N11 and N14 (`human-gate.md:60`)
  - N8 (`human-gate.md:39`)
- **Not listed in human-gate.md:** N12 and N13 (both Info) and the new V3-1 to V3-5. They need either a disposition there or explicit acceptance by the human.
- **Still a placeholder:** `human-gate.md:18` reads "<confirm after Repair 02 finishes>".

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only re-review (verdict 3) of Phase 03 repair 02, scoped to verdict-2 findings V2-1 to V2-5 (manifest select markers, newly_rejected_count_check block, renormalize_check.py old rule, stored agent reports, --dblp-xml limits), plus a regression check and the resolution status of all verdict 1 and 2 findings.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no shell). Read, Grep and Glob calls only, all of which returned results.
TEST RESULTS: none run. Reported by the orchestrator and agents, UNVERIFIED by me: validate_manifest "checks passed: 238; failed: 0"; unittest discover tests/datasets "Ran 60 tests ... OK"; renormalize_check default mode 2298 checked / 0 changed; --dblp-xml rerun 10405443 ee / 7932877 doi.org / old 7932867 / canonical 7932864 / newly rejected 3 / newly accepted 0 / different value 0; check_no_secrets 347 files / 0 matches; spot_check mutation proof (agent-report.md:1788-1791). Supporting evidence from files: the --dblp-xml figures are internally consistent (7932867 - 3 + 0 = 7932864); .claude/.last-test-run reads exit=0; 0 __pycache__ (Glob); no Scala or test source changed in this round.
VALIDATION RESULTS: Verdict APPROVE WITH NOTES. V2-1 resolved (manifest:145,146,148). V2-2 resolved (manifest:159-176). V2-3 resolved: renormalize_check.py:29,32-38 exactly match the recorded policy-1 rule; URLs with a percent-encoded 10.NNNN/ prefix or slash would be counted as newly accepted (:62-63), so "0 newly accepted / 0 different value" is established against the recorded rule, within the documented line and entity limits. V2-4 resolved (agent-report.md:1643-1877). V2-5 resolved (renormalize_check.py:11-14; manifest:176). No regression in DOI behaviour, dblp_select.py, the cache interface, exit codes, ownership by path, network use, or writes to raw/ and samples/. New findings: V3-1 to V3-4 Info, V3-5 Low.
ASSUMPTIONS: The recorded policy-1 rule quoted at agent-report.md:1730 and review-report.md:407 is the true pre-repair code; it is no longer in any file (V3-2). The files I read are the final state the orchestrator tested. derived/ was checked by file name only.
RISKS: The newly-accepted and different-value counts are on raw ee text (entities not expanded, line-by-line matching) and may differ slightly from what the lxml index sees. The indirect effect of the 3 newly rejected URLs on the 1K, 10K and 100K selections is unquantified until the reindex. The --dblp-xml mode has no test (V3-5). The full dblp.xml hash is still unverified by human decision. The .gitignore diff (R10) is unverified by me.
MANUAL ACTION REQUIRED: The human decides on this verdict and completes docs/agent-runs/phase-03-contracts-datasets/human-gate.md (placeholder at :18). The human gives a disposition for N12, N13 and V3-1 to V3-5 (accept, or route V3-1 to dataset-engineer and V3-5 to test-engineer). Human approval is still required for the deferred reindex, the full XML hash, and the 10K and 50K selections. The orchestrator stores this verdict as "code-reviewer verdict 3" in review-report.md.
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Human decision on verdict 3 and the Phase 03 human gate.
```
