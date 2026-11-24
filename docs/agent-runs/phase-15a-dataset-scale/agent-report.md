# Phase 15a — Dataset scale: agent report (assembled by orchestrator)

## Preconditions

Observed by the orchestrator (read-only), 2026-11-21.

- Working directory: `/Users/dhruv_mehta/Desktop/ScholarChain-lane2`, branch `lane2` (`git branch --show-current`).
- `git status --short`: `?? docs/agent-runs/phase-15a-dataset-scale/`. The only untracked entry is this phase folder (the work order itself). No tracked changes. Treated as clean.
- `datasets/raw` is a symlink to `/Users/dhruv_mehta/Desktop/ScholarChain/datasets/raw` (main repo). Through it the following are visible:
  - `dblp/dblp.xml` 5,405,851,335 bytes; `dblp/dblp.dtd` 13,134 bytes
  - `crossref/` 102 files, `openalex/` 100 files, `orcid/` 101 files (the 1K-slice enrichment caches, 100-DOI subset), `logs/` 6 files
  - `dblp/derived/`: `dblp_selection_1000.xml` (575,598 B), `dblp_selection_10000.tsv` (1,662,301 B), `dblp_selection_100000.tsv` (16,352,211 B), their provenance JSON, `eligible_index.tsv.gz` (300,523,936 B) and `eligible_index.summary.json`
- Existing assets:
  - `datasets/dataset-manifest.yaml` (44,531 B)
  - `datasets/scripts/dblp_extract_subset.py` SCRIPT_VERSION 1.1.0 (13,988 B)
  - `datasets/scripts/dblp_select.py` SCRIPT_VERSION 1.2.0 (34,089 B). The 10K/100K selections were produced by version 1.0.0, seed 441 (per their provenance JSON).
  - fetch scripts: `fetch_common.py` (13,057 B), `fetch_crossref.py` (2,030 B), `fetch_openalex.py` (2,318 B), `fetch_orcid.py` (4,821 B)
  - `validate_manifest.py` (11,551 B), `check_no_secrets.py` (1,322 B)
- Selections (sha256 observed with `shasum -a 256`, matching their provenance files):
  - 10K: `851c20657c370ba1cb9ed2f4d3017252825514cc5dde31be82f82fd365a0bbca`; provenance: selected 10,000 (article 5,132, inproceedings 4,868)
  - 100K: `2a00e090923919ce94888eea0630d82c924c0e0f32c172bc09517628cfd0fdae`; provenance: selected 100,000 (article 46,552, inproceedings 53,448)
  - **Nesting check (orchestrator, Python set comparison on column `key`):** 10K distinct keys 10,000; 100K distinct keys **99,992**; intersection **9,999**; 10K ⊂ 100K = **False**. Under work order A.1 this means a new nested rule is needed for `dblp-50k`.
  - **CORRECTION (after A):** the check above used `csv.DictReader` with default quoting. Titles beginning with `"` caused rows to merge. Re-checked by the orchestrator with plain `split("\t")`: 10K distinct keys 10,000, 100K distinct keys 100,000, 10K ⊂ 100K = True. There are no duplicate keys. The non-nesting finding was a parsing artifact, not a data defect.
- Access and budgets (as stated in the manifest and scripts):
  - Crossref: no key; polite pool via `SCHOLARCHAIN_CONTACT_EMAIL` in the User-Agent (manifest quotes polite limit 10 req/s, concurrency 3).
  - OpenAlex: keyless use allowed; manifest quotes "No API key 10" req/s and "Every account gets $1 of API usage per day for free"; a free key is optional (`OPENALEX_API_KEY`, env only). No paid key required.
  - ORCID: public API, no token sent; 401/403 recorded as errors.
  - Conclusion: no paid key is required. Precondition passes.

## Progress checkpoint
- Preconditions recorded. Next: work order A (dataset-engineer).

## Progress checkpoint — canonical test run (Stop hook request)
- The Stop hook asked for the canonical command because the test marker was missing in this worktree. Ran it once from the repo root, exactly as rule 0.4 specifies.
- Observed: marker content `exit=0`; `[info] Passed: Total 640, Failed 0, Errors 0, Passed 640`; `[info] Passed: Total 293, Failed 0, Errors 0, Passed 293`; `[info] Passed: Total 192, Failed 0, Errors 0, Passed 192`; `[success] elapsed time: 241 s`.
- Work order A (dataset-engineer) is running in the background.

## A — dataset-engineer (report summary; STATUS: COMPLETE)

**Files.**
- Created:
  - `datasets/scripts/dblp_nested_select.py` (1.0.0)
  - `datasets/scripts/fetch_scale.sh`
  - `datasets/scripts/FETCH-SCALE.md`
  - `datasets/samples/dblp/scale_selection_stats.json`
- Modified (to 2.0.0):
  - `datasets/scripts/fetch_common.py`
  - `datasets/scripts/fetch_crossref.py`
  - `datasets/scripts/fetch_openalex.py`
  - `datasets/scripts/fetch_orcid.py`
- Gitignored outputs under `datasets/raw/`:
  - 50K TSV and its provenance
  - both XML files and their provenance
  - smoke envelopes and logs
- `dblp_extract_subset.py` is unchanged at 1.1.0.

**Selections.**
- `dblp-10k`: the Phase 03 10K selection reused as is.
  - sha256 `851c2065…bbca`, seed 441, `dblp_select.py` 1.0.0
  - article 5,132, inproceedings 4,868
- `dblp-50k`: all 10K rows plus the 40,000 rows of (100K minus 10K) with the smallest `sha256("441|nested-50k|key")`.
  - Rule from `dblp_nested_select.py` 1.0.0, seed 441
  - 50,000 rows: article 23,649, inproceedings 26,351
  - 5,483 venues, 86 years
  - sha256 `5ffdc0e1ef6c124c365f7dfd9e3f2d5c529a286dd8b3919baf8ff594d244d500`
  - Nesting: 10K ⊂ 50K ⊂ 100K

**Extraction.** Single streaming pass over the local `dblp.xml`; the source sha256 matched the manifest.

| | 10K | 50K |
|---|---|---|
| File | `dblp_selection_10000.xml` | `dblp_selection_50000.xml` |
| Size | 5,778,232 B | 28,530,861 B |
| sha256 | `af3e079f8d69ea2406ec5542f65378c022e1b01b3a43ab92a7d6f1ee7ec5f5a3` | `f8578b70d6161fbe9d4fef3b4780d4ac86dbeb2e6631c745b41ae28906819e69` |
| Records found vs selection | 10,000 of 10,000, 0 missing | 50,000 of 50,000, 0 missing |

- Both files are well-formed (lxml), with `<!DOCTYPE dblp SYSTEM "dblp.dtd">`.
- The record key sets equal the TSVs.

**Fetch design.**
- Crossref: batched `filter=doi:…`, 50 DOIs per request.
- OpenAlex: batched `filter=doi:a|b|…`, 100 DOIs per request. A list call costs $0.0001; the keyless budget is $0.10/day. The 50K run is about $0.05, so no paid key is needed.
- A DOI absent from a batch is re-checked as a singleton. This handles alias DOIs, one of which appeared in the smoke run. The one false-miss envelope was renamed `*.superseded-20261121T223438Z`, not deleted.
- Behavior:
  - cache-first and resumable
  - a hard `--max-requests` cap that counts retries
  - backoff on 429/5xx with Retry-After honoured
  - contact email from env only, in the User-Agent
  - redacted logs and progress every N requests
  - bounded defaults: 100 works and 200 requests, with `--allow-more` required above 100
  - `--plan` makes no network call

**`--plan` (observed, after smoke).**

| Source | 10K: to fetch / requests / est. | 50K: to fetch / requests / est. |
|---|---|---|
| Crossref | 9,718 / 195 (+≤98 singletons) / 7 min 20 s | 49,718 / 995 (+≤498) / 37 min 20 s, ~646 MB |
| OpenAlex | 9,718 / 98 (+≤98) / 4 min 54 s | 49,718 / 498 (+≤498) / 24 min 54 s, ~1.1 GB, $0.0498 |
| ORCID (capped 5,000) | expected ~4,840 to fetch, ~2 h 1 min, ~1.21 GB | same |

- Cached counts in the plans: 282 for Crossref and OpenAlex, 160 for ORCID.
- ORCID uncapped at 50K is an estimate only (not observed): about 138,298 iDs, about 57 h 37 min, about 34.7 GB. That is over the 12 h STOP threshold, so the command caps ORCID at 5,000 hash-ordered iDs.
- Total capped effort is about 3–4 h, under 12 h.

**Smoke fetch (real network, 10K selection, limit 200).**
- Crossref: 18 cached, 182 retrieved, 0 errors, 4 requests.
- OpenAlex: 18 cached, 181 retrieved plus 1 alias DOI retrieved by singleton in a repair run, 0 errors.
- Reruns: 0 requests for both.
- ORCID: 16 cached, 60 retrieved, 0 errors. It stopped at the deliberately low hard cap of 60 (exit 3, 74 not attempted, resumable).

**Validation (agent-observed).**
- `validate_manifest.py`: checks passed 244, failed 0.
- `check_no_secrets.py`: exit 0, files scanned 35, matches 0. It does not follow the `datasets/raw` symlink; the agent ran an ad-hoc scan of the raw caches and logs, 755 files, 0 email matches.
- `pytest tests/datasets`: 83 passed (existing suite).

**Orchestrator verification.**
- Plain-split key check: 10K/100K/50K = 10,000/100,000/50,000, nested True/True/True.
- `shasum -a 256` of the 50K TSV and both XMLs matches the values above.
- XML sizes match.

**Human command** (from `datasets/scripts/FETCH-SCALE.md`; run from the lane 2 repo root after `export SCHOLARCHAIN_CONTACT_EMAIL=…`):
```
caffeinate -i bash datasets/scripts/fetch_scale.sh 2>&1 | tee -a datasets/raw/logs/fetch_scale_50k.log
```
Progress check:
```
for s in crossref openalex orcid; do printf '%s=%s ' $s "$(find datasets/raw/$s/works -name '*.json' | wc -l | tr -d ' ')"; done; echo; tail -n 2 datasets/raw/logs/fetch_scale_50k.log
```

**Decision for the human before H:** confirm the ORCID cap of 5,000 iDs (about 2 h) or set another one. Uncapped is about 58 h, which exceeds the 12 h STOP threshold.

## Progress checkpoint — after A
- Work order A is complete and verified. **STOPPED for the human (H).**
- Run the command above. Paste the tool's final summary under "## H (human)" below. Then tell the orchestrator to continue with B, C and D.

## Progress checkpoint — second canonical test run (Stop hook request)
- Trigger: dataset-engineer's `pytest tests/datasets` wrote `tests/datasets/__pycache__/*.pyc`, which made the Stop hook fire. The canonical command was run again from the repo root.
- Observed: marker `exit=0`; `[info] Passed: Total 640, Failed 0, Errors 0, Passed 640`, `Total 293, Failed 0`, `Total 192, Failed 0` (repeated per aggregate), `[success] elapsed time: 245 s`.
- Still STOPPED for the human (H).

## H (human)
Full fetch run by the human, in my own terminal, with SCHOLARCHAIN_CONTACT_EMAIL exported for the command only.
Command: `caffeinate -i bash datasets/scripts/fetch_scale.sh 2>&1 | tee -a datasets/raw/logs/fetch_scale_50k.log` (run twice).

- Run 1 (2026-11-21T23:08:50Z to 2026-11-22T00:17:02Z, exit 3): Crossref stopped at its hard request cap of 2,000 (995 batch + 1,005 singleton re-checks). retrieved_ok 48,548; missing_404 950; absent_from_batch 1,225; errors 0; not_attempted 220.
- Run 2 (started 2026-11-22T01:02:19Z, ended 02:56:44Z, exit 0, 6,865 s), resumed from cache:
  - Crossref: already_present 49,780; retrieved_ok 13; missing_404 207; errors 0; 227 requests. A few HTTP 429s were retried by the backoff.
  - OpenAlex: already_present 282; retrieved_ok 49,629; missing_404 89; absent_from_batch 949 (each re-checked as a singleton); errors 0; 1,447 requests (498 batch + 949 singleton).
  - ORCID: 113,697 distinct iDs found in the fetched records (Crossref 30,019, OpenAlex 113,243); capped at 5,000 as planned. already_present 27; retrieved_ok 4,970; other_status 3 (HTTP 409; the iDs are recorded only in the gitignored fetch log); errors 0; 4,973 requests.
- Combined Crossref result over both runs: 48,561 retrieved, 1,157 HTTP 404. The 404s are mostly DOIs registered with DataCite rather than Crossref (prefixes 10.48550 arXiv, 10.4230 Dagstuhl/LIPIcs, 10.2312 Eurographics, 10.5281 Zenodo); to be confirmed by B's coverage analysis.
- I decided to keep the ORCID cap at 5,000 hash-ordered iDs (the uncapped estimate was about 58 h).
## Progress checkpoint — after H
- The human recorded the H summary above (two runs; run 2 exit 0) and told the orchestrator to continue with B, then C and D.
- B gets three extra items from the human: Crossref 404s by DOI prefix; OpenAlex coverage of the DOIs Crossref missed; the 3 ORCID HTTP 409 iDs and the documented meaning of 409.

## B — dataset-engineer (report summary; STATUS: COMPLETE)

**Files.**
- Created:
  - `datasets/scripts/scale_enrichment_stats.py` (1.0.0, offline, read-only)
  - `datasets/samples/dblp/scale_enrichment_stats.json` (sha256 `0930b504…8d61`; no names, iDs or DOI lists)
- Modified:
  - `datasets/dataset-manifest.yaml`: new top-level `scale_datasets` with `dblp-10k`, `dblp-50k` and `stats_file`. No existing entry changed.
  - `datasets/scripts/validate_manifest.py`: 1.0.0 → 1.1.0, with new checks for `scale_datasets`.

**Coverage (computed from cache files).**

| | dblp-10k | dblp-50k |
|---|---|---|
| Works with DOI | 10,000 | 50,000 |
| Crossref 200 / 404 | 9,865 (98.65%) / 135 | 48,842 (97.684%) / 1,158 |
| OpenAlex 200 / 404 | 9,990 (99.90%) / 10 | 49,911 (99.822%) / 89 |
| Neither source | 10 | 89 |
| OpenAlex hit where Crossref missed | 125 | 1,069 |
| Distinct ORCID iDs in records | 27,637 | 113,697 |
| ORCID envelopes (all 200) | 1,384 | 5,130 (4,997 in cap plan + 133 earlier) |

- Schema: every envelope is valid. Crossref 404 bodies are the plain text "Resource not found.".
- OpenAlex alias DOIs: 92 (10K) and 574 (50K). The manifest says to join on the envelope `identifier`.
- Differences from H: Crossref 48,842 vs 48,561 and 1,158 vs 1,157. Explained by the 282 envelopes cached before the run (281 × 200 + 1 × 404). The 282 split was not verified from the logs.

**Extra item 1: Crossref 404 by prefix (50K).**
- 1,158 misses across 59 prefixes. Top: 10.48550 431, 10.4230 282, 10.2312 141, 10.18420 70, 10.5281 40, 10.11896 15, 10.3217 12.
- The 4 prefixes the human named account for 894 of 1,158 (77.2%).
- The registration agency is **not verified**: the cache has no agency field, and no lookup was made. "DataCite" is consistent with the data but remains inference.
- Crossref-member prefixes also appear among the misses (10.1145 8, 10.1109 5, 10.1007 2, 10.1016 1).

**Extra item 2: OpenAlex coverage of Crossref misses.**
- 50K: 1,069 of 1,158. 10K: 125 of 135.
- Every OpenAlex 404 is also a Crossref 404.
- Per prefix (50K): 10.48550 430/431, 10.4230 282/282, 10.2312 140/141, 10.5281 40/40, 10.11896 1/15.

**Extra item 3: ORCID HTTP 409.**
- Three iDs (redacted by the orchestrator under review finding F2; they are listed in gitignored `datasets/raw/orcid/works/errors.jsonl`).
- No envelope is stored; the fetcher stores only 200 and 404 responses. They are recorded in `orcid/works/errors.jsonl` (`error: "HTTP 409"`, 2026-11-22T01:58:02Z, 02:07:02Z, 02:16:31Z) and in `fetch_scale_50k.log`. No response body is stored.
- Docs: ORCID-Source `orcid-api-web/tutorial/api_errors.md` (https://github.com/ORCID/ORCID-Source/blob/main/orcid-api-web/tutorial/api_errors.md). It quotes "Conflict: The ORCID record is locked and cannot be edited." with the explanation "This record was flagged as violating ORCID's Terms of Use and has been hidden from public view."
- The same table also lists a write-side 409, "You have already added this activity". Applying the locked-record meaning to these reads is inference.
- The manifest and stats hold only the `sha256` of each iD.

**Validation.** Re-run by the orchestrator, observed:
- `validate_manifest.py`: "validate_manifest 1.1.0 / checks passed: 341; failed: 0", exit 0.
- `check_no_secrets.py`: "files scanned=39, matches=0".
- The agent's negative run (corrupted digest and count) gave 337 passed, 4 failed.

**Open items for D and the human.**
- `datasets/samples/dblp/scale_selection_stats.json` (from A) has 4 absolute local paths containing the OS username (the orchestrator observed 4 lines with `/Users/`).
- `scale_datasets` placement is the agent's choice.
- Whether to rerun to retry the three 409 iDs is a human decision.

## Progress checkpoint — after B
- Work order B is complete. Next: C (test-engineer), then D (code-reviewer).

## C — test-engineer (report summary; STATUS: COMPLETE)
- Created under `tests/datasets/`:
  - `scale_helpers.py` (fake transport and fixtures)
  - `test_scale_selection.py` (9 tests)
  - `test_scale_fetch.py` (28 tests)
  - `test_scale_manifest.py` (13 tests)
- 50 new tests in total. No production edits; no network (urlopen is patched to raise).
- Coverage of every section 5 bullet:
  - determinism and 10K ⊂ 50K
  - quote-led TSV titles parsed with plain split
  - extractor count
  - batch sizes 50 and 100
  - cache-first and resume after an interrupt
  - 429 Retry-After and 5xx exponential backoff
  - hard cap, with retries counted
  - `--plan` with no call for all three sources
  - alias-DOI singleton re-check
  - no email or key in logs or envelopes
  - validator passes on the real manifest and rejects missing, empty or malformed checksums, bad coverage arithmetic, a dropped "not an approved deviation" statement, and an ORCID iD in the manifest
- Agent-observed: `PYTHONDONTWRITEBYTECODE=1 python3 -m pytest -p no:cacheprovider -q tests/datasets` → `133 passed, 4 subtests passed` (83 existing + 50 new; 0 failed, 0 errors, 0 skipped).
- Orchestrator re-run of the same command: `133 passed, 4 subtests passed in 26.29s`.
- Finding (low, owner dataset-engineer): `validate_manifest.py` 1.1.0 raises an uncontrolled `KeyError('sha256')` when a scale file entry lacks the `sha256` key; it should give a clean FAIL. The exit is still non-zero, so the entry is rejected. `test_removed_file_checksum_key_is_rejected` accepts either outcome.
- Not mutation-tested (no production edits allowed).

## Progress checkpoint — after C
- Work order C is complete. Next: D (code-reviewer).

## Progress checkpoint — third canonical test run (Stop hook request): STATUS: FAILED
- Trigger: C's new files under `tests/datasets/` made the Stop hook fire. The canonical command was run from the repo root.
- Observed: marker `exit=1`.
  - `[info] Passed: Total 293, Failed 0`
  - `[info] Passed: Total 640, Failed 0`
  - `[error] Failed: Total 192, Failed 1, Errors 0, Passed 191`
- Failing test: `scholarchain.hadoop.AdversarialSuite` — "adversarial: a giant team of 1,000 gives exactly 499,500 valid distinct pairs, none truncated 31.921s java.util.concurrent.TimeoutException: test timed out after 30 seconds".
- Context (observed facts only):
  - `git status --short modules project build.sbt` prints nothing, so this phase changed no Scala, build or module file.
  - The same suite passed in the two earlier runs this session (exit=0, 192/192).
  - The code-reviewer (D) was running at the same time.
  - Cause not established. It is consistent with a wall-clock timeout under load, but that is not verified.
- The orchestrator did not rerun it, because rule 0.4 allows one run per hook request and lane 2 may not edit `modules/**`. The test is owned by test-engineer (`modules/hadoop/src/test/**`) and its timeout budget is outside lane 2. **Human decision:** rerun `sbt check` when the machine is idle, or route it to lane 1 / test-engineer.

## D — code-reviewer (round 1): REQUEST_CHANGES
Full report stored in `review-report.md`. Findings:
- **F1 (MEDIUM, dataset-engineer):** absolute local paths containing the OS username in committed files: `scale_selection_stats.json` and `FETCH-SCALE.md:10`. Root cause: `Path.resolve()` follows the `datasets/raw` symlink (`dblp_nested_select.py` `rel()`, `fetch_common.rel_to_repo`).
- **F2 (MEDIUM, human/orchestrator):** three real ORCID iDs in phase evidence. The unsalted `sha256(iD)` values in the stats file can be reversed by dictionary.
- **F3 (MEDIUM, dataset-engineer):** the scale entries do not state that they rest on the policy-1 eligible index (the manifest marks that index INVALID under policy 2 and defers the 10K/50K selections). `datasets.dblp.current_state`, `selections` and `select_50000` are stale.
- **F4 (LOW, dataset-engineer + test-engineer):** the validator raises KeyError at lines 229 and 260 instead of a clean FAIL. `assert_rejected` accepts any exception.
- **F5 (LOW, dataset-engineer):** the plan assumed 1% singleton re-checks; observed 1,225 (Crossref) and 949 (OpenAlex).
- **F6 (LOW, dataset-engineer):** the `cmp` determinism commands break once `code_version` changes at commit.
- **F7 (LOW, dataset-engineer):** Retry-After sleep has no ceiling.
- **F8 (LOW, test-engineer):** no golden value pins the rank formula; no repo-relative-path test; no large-Retry-After test.
- **F9 (LOW note, build-engineer):** `.gitignore` `datasets/raw/` (trailing slash) does not match a symlink. Lane 2 does not edit `.gitignore` in this phase, so this is left for the human.

## Repair round 01 (orchestrator routing)
- F2, orchestrator part: redacted the iDs from the orchestrator's B summary. The iDs in the human-written "## H (human)" section (line 149) were **not** edited, because agents do not rewrite human evidence. **Human action:** redact them before commit.
- dataset-engineer: F1, F3, F4 (validator), F5, F6, F7, and F2 (remove the reversible iD hashes from the committed stats and manifest).
- test-engineer (after dataset-engineer): F4 (tests), F8.
- Then code-reviewer re-reads.

### Repair 01 — dataset-engineer (STATUS: COMPLETE)
- **F1:** `rel()` and `rel_to_repo` use `os.path.abspath`, not `resolve()`. `scale_selection_stats.json` was regenerated (the path strings changed; the 50K TSV is still `5ffdc0e1…d500`). FETCH-SCALE.md has no absolute path. The raw provenance JSONs (gitignored) still hold absolute paths; they were not overwritten, by rule.
- **F2:** the per-iD `sha256` list was removed from the stats (`scale_enrichment_stats.py` 1.1.0). The manifest privacy wording is corrected. The validator FAILs on per-iD hashes and on iD-shaped strings.
- **F3:** a policy-1 limitation was added to both scale entries ("PENDING the human gate"). `current_state`, `select_50000` and the `selections` cross-reference were updated.
- **F4:** validator 1.2.0 gives clean FAILs, with a backstop that turns structure errors into a FAIL and exit 1. Negative checks (missing sha256 key, missing dblp-50k, missing stats path, missing dblp.snapshot) printed FAIL lines with no traceback.
- **F5:** `SINGLETON_ALLOWANCE_PCT = 3`, labelled an assumption, citing the observed Crossref 1,225 and OpenAlex 949 re-checks.
- **F6:** the determinism commands compare with `code_version` excluded.
- **F7:** `MAX_RETRY_AFTER_SECONDS = 60.0`; `fetch_common` 2.1.0.
- Orchestrator re-check:
  - `validate_manifest.py` → "checks passed: 355; failed: 0"
  - `check_no_secrets.py` → "files scanned=39, matches=0"
  - `grep -rIl "/Users/" datasets/ --exclude-dir=raw` → only `validate_manifest.py` (its detection pattern)
  - 50K TSV sha256 unchanged
  - pytest → `2 failed, 131 passed, 4 subtests passed` (expected: `test_plan_arithmetic_uses_the_batch_size` and `test_real_manifest_passes_and_covers_both_scales` assert old values; routed to test-engineer)

### Repair 01 — test-engineer (STATUS: COMPLETE)
- Fixed the 2 failing tests by deriving expected values from `SINGLETON_ALLOWANCE_PCT` and `VALIDATOR_VERSION`.
- **F4:** `assert_rejected` now requires exit 1, a `FAIL:` line containing the expected text, and no traceback; an exception fails the test. A backstop test was added.
- **F8:**
  - a golden sha256 test for the rank formula
  - an independently computed fill set
  - repo-relative paths through a symlink
  - `--stats-out` with no absolute path
  - Retry-After capped at 60.0 and small values passed through
  - the validator rejects a per-iD hash, an iD-shaped string, a missing policy-1 limitation and an absolute path
  - new `test_scale_enrichment_stats.py` (synthetic raw tree; no `sha256_of_id`, no iD)
- Agent-observed: 149 passed, 0 failed, 0 skipped, 4 subtests passed.

## D — code-reviewer (round 2, re-read): APPROVE WITH NOTES
See `review-report.md`, round 2. F1–F8 are fixed. The F2 residue is in the human-written H section only. F9 is open for the human. LOW notes: N1, N2 and the F8 key-name weak spot.

## Progress checkpoint — fourth canonical test run (Stop hook request, after the test repair)
- Ran the canonical command from the repo root while the reviewer was finishing (read-only).
- Observed: marker `exit=0`.
  - `[info] Passed: Total 640, Failed 0, Errors 0, Passed 640`
  - `[info] Passed: Total 293, Failed 0, Errors 0, Passed 293`
  - `[info] Passed: Total 192, Failed 0, Errors 0, Passed 192`
  - `[success] elapsed time: 238 s`
- `scholarchain.hadoop.AdversarialSuite` passed in this run (192/192). The earlier failure (run 3) was not reproduced; its cause was not established.

## Final state (orchestrator-observed)
- Python dataset suite: `149 passed, 4 subtests passed`.
- `validate_manifest.py` 1.2.0: `checks passed: 355; failed: 0`.
- `check_no_secrets.py`: `files scanned=39, matches=0`.
- `sbt check`: exit=0 (run 4).
- Nothing staged or committed.

**Human actions before commit:**
1. **Redact the three ORCID iDs in "## H (human)" above** (required; agents may not edit it).
2. Record the decision on the policy-1 limitation (manifest marks it PENDING the human gate).
3. Check `git status` so the `datasets/raw` symlink is not staged (F9).
4. Optional: retry the three HTTP 409 iDs.
5. Write `human-gate.md`.

## Progress checkpoint — final
Phase 15a complete; waiting for human gate.
