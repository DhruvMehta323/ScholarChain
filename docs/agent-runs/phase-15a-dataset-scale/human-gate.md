# Phase 15a — Human gate

Phase: 15a, dataset scale (~10K and ~50K DBLP subsets with enrichment)
Date: 2026-11-22
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with the decisions, disclosures and remaining risks below.

## 1. What I reviewed

- `prompt.md` (written by me; saved verbatim), `agent-report.md` (preconditions, A, H, B, C, the repair round and the final checkpoint), `review-report.md` (APPROVE WITH NOTES after the one allowed repair round).
- `datasets/dataset-manifest.yaml` entries `dblp-10k` and `dblp-50k`, `datasets/scripts/FETCH-SCALE.md`, and `datasets/samples/dblp/scale_selection_stats.json`.
- My own checks (section 6).

## 2. What was accepted

- **Selections (dataset-engineer):**
  - `dblp-10k`: the Phase 03 10K selection reused unchanged (sha256 `851c2065…bbca`, seed 441, `dblp_select.py` 1.0.0; article 5,132, inproceedings 4,868).
  - `dblp-50k`: all 10K rows plus the 40,000 rows of (100K minus 10K) with the smallest `sha256("441|nested-50k|key")` (`dblp_nested_select.py` 1.0.0, seed 441; 50,000 rows; article 23,649, inproceedings 26,351; 5,483 venues; 86 years; sha256 `5ffdc0e1…d500`).
  - Nesting verified: 10K ⊂ 50K ⊂ 100K.
- **Extraction:** one read-only streaming pass over the local `dblp.xml` (source sha256 matched the manifest; nothing re-downloaded). `dblp_selection_10000.xml` (5,778,232 B, sha256 `af3e079f…f5a3`) and `dblp_selection_50000.xml` (28,530,861 B, sha256 `f8578b70…9e69`); every selected record found, well-formed, DTD reference present. Both are gitignored.
- **Fetch scripts (2.0.0):** batched Crossref (50 DOIs per request) and OpenAlex (100 per request) lookups, singleton re-checks for DOIs absent from a batch (alias DOIs), cache-first, resumable, a hard request cap counting retries, backoff on 429/5xx, contact email from the environment only, redacted logs, a no-network `--plan` mode.
- **Enrichment coverage at 50K (observed):**

| Source | Found | Missed or failed |
|---|---|---|
| Crossref | 48,842 (97.7%) | 1,158 not found (404) |
| OpenAlex | 49,911 (99.8%) | 89 not found (404) |
| ORCID (capped at 5,000 iDs) | 5,130 records | 3 HTTP 409 |

- **Manifest:** validator 355 checks passed, 0 failed; secrets scan 0 matches.
- **Tests:** 149 Python dataset tests pass. Latest `sbt check` passed (`exit=0`).

## 3. Decisions

1. **ORCID cap: 5,000 hash-ordered iDs.** 113,697 distinct iDs were found in the fetched records; fetching all was estimated at about 58 hours and about 35 GB. The capped sample is hash-ordered, so it is not biased toward any source or field.
2. **Selection index and eligibility policy 2.** The manifest notes that the 10K and 50K selections come from an index built under the older eligibility policy. I had every selected record re-checked against the current policy 2 rules (read-only): **0 of 10,000 and 0 of 50,000 are ineligible**, and none keeps a different DOI. **Decision: keep both selections.** Disclosed limit: a full policy 2 reindex has 3 fewer eligible records across all of DBLP, which could shift year and venue quotas slightly; that reindex is not done.
3. **ORCID HTTP 409s: not retried.** They indicate records the public API will not serve (locked or deactivated); retrying would not change that. The iDs are kept only in the gitignored fetch log.
4. **Bounded enrichment is a design choice** under the spec's bounded-retrieval rule, not an instructor-approved deviation.

## 4. Human step H (my full fetch)

I ran `caffeinate -i bash datasets/scripts/fetch_scale.sh` in my own terminal, twice. Run 1 stopped at Crossref's hard request cap of 2,000 (exit 3); run 2 resumed from cache and finished all three sources (exit 0, 6,865 s). The full summary is under "H (human)" in agent-report.md. Most Crossref 404s are DOIs registered with DataCite, not Crossref (arXiv 10.48550, Dagstuhl 10.4230, Eurographics 10.2312, Zenodo 10.5281); OpenAlex covers most of them.

## 5. Deferred notes

The reviewer's LOW notes are deferred: the privacy test and validator catch per-iD hashes only under one key name; `dblp_nested_select.py` kept its version number after a path fix; the validator's absolute-path check only looks for `/Users/`. Also F9: the `.gitignore` pattern does not match the `datasets/raw` symlink (handled locally with `.git/info/exclude`; verified that nothing under `datasets/raw` is visible to git).

## 6. My own checks

- [x] No ORCID iD from my fetch appears in tracked files (the only matches are ORCID documentation examples in older phase evidence).
- [x] `git status` shows nothing under `datasets/raw`.
- [ ] The manifest entries `dblp-10k` and `dblp-50k` show counts, checksums, seed and coverage.
- [ ] `datasets/samples/dblp/scale_selection_stats.json` contains counts and checksums only.

## 7. Disclosures

- This phase ran in parallel with lane 1 (Phases 06–08), in a separate git worktree (`ScholarChain-lane2`, branch `lane2`), on a third Claude Pro account (Claude Code 2.1.323, orchestrator Opus). `datasets/raw` in the worktree is a symlink to the main repository's raw folder.
- Part of the session ran in Claude Code **auto mode** (commands approved automatically) rather than accept-edits.
- My original H section listed the three 409 ORCID iDs. At my instruction, the orchestrator replaced them with a reference to the gitignored log before anything was committed.
- The orchestrator's first precondition check wrongly reported that the 100K selection did not contain the 10K one; that was a CSV-quoting artifact on its side, corrected in agent-report.md.
- One OpenAlex envelope from the first smoke run was a false miss (alias DOI); it was renamed `.superseded-…`, not deleted.
- The Stop hook asked for the canonical `sbt check` several times because Python bytecode under `tests/` changed; one earlier run had a Hadoop AdversarialSuite timeout that did not repeat (cause not found; the machine was under load).
- Network use: bounded, read-only public API calls. OpenAlex cost about $0.05 against its free daily allowance. No AWS. No LLM calls.

## 8. Manual changes

None to repository source. I authored the H section and the decisions in this file.

## 9. Remaining risk

Crossref coverage is lower for DataCite-registered DOIs; ORCID is a 5,000-iD sample; the unperformed policy 2 reindex; ORCID's unauthenticated public API may change without notice.

Accepted commit: see `commit.txt`.