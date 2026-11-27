

<!-- ===== phase-15a-dataset-scale/human-gate.md ===== -->

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

<!-- ===== phase-15b-experiment-harness/human-gate.md ===== -->

# Phase 15b — Experiment harness: Human Gate

Reviewer: Dhruv Mehta (dmeht35)
Date: 2026-11-24 (CST)
Branch: lane2 (worktree ScholarChain-lane2), base = Phase 13 accepted commit
Decision: **ACCEPTED WITH NOTES**

Note: Phases 15a, 15b and 15c are separate work orders now and are combined into one Phase 15 at the end.

## 1. What I reviewed

- `prompt.md` (my work order), `agent-report.md`, `repair-01.md`, `review-report.md` (first review REQUEST CHANGES, HIGH 0 / MEDIUM 6 / LOW 12; reread after one repair round: APPROVE WITH NOTES, LOW 5 new N1–N5).

## 2. What was accepted

- Manifests for experiments A–F (10K baseline, 50K burst gated on the 10K run and approval, embedding backlog ≤10K vectors, query load at four concurrency levels, retrieval modes, failure and recovery), each with cost and duration caps; manifest validator `RESULT PASSED (0 problem(s))`.
- Load generator (Python stdlib): concurrency, duration, ramp and request caps; model-call cap and a cost ledger keeping all Bedrock spend under $2; API key read from a mode-600 file outside the repo, never printed; plain HTTP only with an explicit flag.
- Collectors (CloudWatch, EMR timelines, Cost Explorer before credits), dry-run with a stub `aws`; raw outputs write-once (O_CREAT|O_EXCL), failed requests and partial runs kept.
- Analysis scripts (percentiles, scale events, queue drain, EMR capacity, 10K vs 50K, recall@K / MRR / nDCG, cost per experiment) run only on fixtures labeled `SYNTHETIC`.
- Phase 16 evaluation scaffolding for the seven groups, with no invented numbers; `experiments/README.md` with run order, approval points and caps.
- Repair 01: M1–M6 and L9 fixed (including the ledger and cap bypasses in M1); tests strengthened.

## 3. Test evidence

- `tests/experiments`: **131 passed** (orchestrator-observed, final run; 59 before the repair).
- Canonical `sbt check`, run by me in lane 2 after the agent stopped (required by the stop hook): `exit=0`, 0 failed, elapsed 1,515 s. Scala sources were not changed in this phase.

## 4. Disclosures

- The agent's mutation check ("14 failed, 45 passed" on five deliberate breakages, run on a scratch copy) was not re-run by the orchestrator or the reviewer.
- The reviewer had no shell; it verified test counts by reading, not running.
- The stop hook `verify-tests-ran.sh` blocked the end of the session 9 times, because the work order forbade sbt and only Python files had changed; Claude Code overrode it at its block cap. I then ran the canonical CHECK myself. The project rule files were not changed (by my decision); this is disclosed in the final report.
- No AWS call, no network beyond 127.0.0.1, no sbt by any agent, no edits outside `experiments/**`, `load-tests/**`, `tests/experiments/**` (reviewer ownership check passed).

## 5. Deferred (LOW)

- N1–N5 as listed in review-report.md, including N5: `failure_drill.py` with `--execute --yes` but `--dry-run` left in records a failed action that never ran (fails safe, misleading record). Mitigation in Phase 15c: never combine `--dry-run` with `--execute`.
- The remaining first-review LOW findings as listed in repair-01.md.

## 6. My own checks

- [x] `git status --short` shows only the phase folder, `experiments/`, `load-tests/`, `tests/experiments/` (and Phase 13 `commit.txt`).
- [x] No synthetic fixture file is presented as a result.
- [x] No API key or account ID in the new files: `grep -rn "<account-id>" experiments load-tests tests/experiments` prints nothing.

Accepted commit: see `commit.txt`.

Signed: Dhruv Mehta — 2026-11-24

<!-- ===== phase-15c-aws-experiments/human-gate.md ===== -->

# Phase 15c — AWS data and elasticity experiments: Human Gate

Reviewer: Dhruv Mehta (dmeht35@uic.edu)   Date: 2026-11-27 (after the Nov 25 deadline)
Decision: ACCEPT WITH NOTES

## What was reviewed
- Orchestrator final message, agent-report.md (all checkpoints), code-reviewer pass 1 (REQUEST CHANGES: 1 HIGH, 2 MEDIUM, 9 LOW) and pass 2 after repair R1 (APPROVE WITH NOTES, no HIGH).
- experiments/results/aws/SUMMARY-v2.md (supersedes the draft SUMMARY.md, which is kept), raw folders for A, B, C, D, F, cost readings, docs/incidents/INC-001 and INC-002.
- Phase content commit 124cfef (on top of work-order commit 82d21c7). sbt check: exit 0, 0 failures in every module.

## Authority recorded
- Delegated authority per prompt.md §1: orchestrator routes; approved scripts and manifests only; no deploy.sh, terraform apply/destroy, cleanup.sh, env-window.sh down; no EMR clusters; $15 stop; orchestrator may commit after the diff-only review.
- The permission system refused the agent's edit setting approved: true on the D and F manifests ("Undermining Oversight"). I made that edit myself by copying the orchestrator's validated scratch manifests (approved_by = me, approved_at 2026-11-26).
- My decisions: (1) time box overrun accepted (75 min planned; the phase ran about 18:10 to 21:24 CST on Nov 26); (2) F's "DLQ depth above 1" stop condition waived for stop-task on retrieval (it was written for the poison-message drill; the 2 DLQ messages are F14-16, untouched); (3) F title/purpose still say "embedding worker" while the drill targeted retrieval, left as is and disclosed; (4) the "40 query calls" Bedrock cap applied as 40 /v1/query requests (75 model calls observed, manifest model-call cap 120); (5) repair R1 with a rewrite of unpushed local history (soft reset to 82d21c7, recommit as 124cfef), no push before my checks; (6) reviewer pass-2 R1-M1 (provenance cites replaced commits) accepted as a disclosure, no further amend.

## Gate (spec Phase 15)
- [x] At least one scale-out and one scale-in shown: experiment C, embedding-worker desired 1→2 (2026-11-26T22:15:51Z) and 2→4 (22:17:51Z), scale-in 4→3→2→1 (22:43:33Z to 22:55:33Z), AlarmHigh/AlarmLow, raw appautoscaling-scaling-activities-embedding-worker.json. This is the Phase 14 s4 drain window collected in 15c, not an event caused in 15c.
  - NOT MET for D: the work order asked D itself to show scale-out and scale-in. It showed none (query-api CPU peak 33.8 % vs 60 % target, ALB 58 req/min vs 200 per target, load about 40 s).
- [x] Synchronized timestamps: UTC throughout; ECS -06:00 times converted; sampler at 15 s.
- [x] Raw data preserved: write-once, failed runs and partial samplers kept. Disclosed write-once exceptions: account-ID redaction to <acct> in raw files; removal of 183 @ptr fields (H1); replacement of hashes of unredacted originals (M1); in-place redaction of the F drill record.
- [x] Costs and limits stated: $10.5971 month to date before credits at every reading (Cost Explorer lag; real spend higher). Per-experiment costs are estimates only: A $0.242 (upper bound $0.761), D model spend at most $0.84. cost-per-experiment.csv is stale (superseded by ANALYSIS-NOTES-addendum.json).
- [x] Failures reported: three failed 10K attempts kept (…79065 F14-5, …43602 F14-6/F14-7, …3950 F14-7); Spark step failed on EMR after 38 s (F14-8); F drill (INC-002, recovery 32.9 s to RUNNING, 40.1 s to steady state); real incident INC-001 (F14-4).

## Spec items NOT MET (reported, not papered over)
- Experiment A/B: no Spark on EMR (Spark ran locally on my Mac); no burst run, no larger manifest, no 50K run; EMR released capacity only at cluster shutdown (no managed scale-in observed).
- Experiment D: 2 concurrency levels (5, 20) instead of the spec's at least four; 40 requests total; single run; percentiles weak at n=16/24.
- Experiment C: historical window, no fresh enqueue.
- A, B, C were collect-only from existing records; no committed experiment manifest was executed for them.
- F: user impact during the 32.9 s gap not measured; SQS/DLQ depth and embedding-worker logs listed in the F manifest were not collected; retrieval has no health check or target group.
- Experiment E moved to Phase 16b.

## Manual changes
- D and F manifest approvals copied in by me (above). No other manual file edits.

## Disclosures and remaining risk
- Secret-exposure near miss: the account ID was base64-encoded inside @ptr fields of a committed-but-unpushed D logs file (old commit 312c90d). The plain-text commit grep cannot see base64. Fixed in R1; my local check over origin/main..HEAD: raw ID 0, quoted @ptr keys 0. The orchestrator's base64-decoded scan: 0.
- Old commits 36b19d1 and 312c90d exist only in my local reflog and still contain @ptr values; never tag or push them. 17 result files cite them as code version; the orchestrator verified that git diff 82d21c7 124cfef over scripts, collectors, analysis, load-tests, modules, project and infra shows only the new helper experiments/analysis/p15c_x1_derive.py, so the recorded code matches 124cfef.
- Open: the exact pattern used for the in-place redaction of the F drill record was not stated (reviewer R1-L1).
- Hashes in earlier phases' evidence were not audited for the hash-oracle risk.
- The agent saved the account ID and pre-redaction copies in the session scratchpad (outside the repo); I deleted the scratchpad after review.
- INC-001 cites local, uncommitted reports/deployments files (labelled), against the work order's "committed evidence only".
- My university email is committed in approved_by (my edit).
- collect_emr.py calls a CLI command that does not exist (aws emr list-instance-groups); kept as .ERROR, not fixed.
- The Stop hook forced a canonical sbt check (exit 0) although no Scala changed.
- reports/ stays untracked and unstaged.

## My own checks
- [x] git log --oneline origin/main..HEAD shows only 124cfef and 82d21c7
- [x] Local account-ID grep over git log -p origin/main..HEAD: 0; quoted @ptr keys: 0
- [x] Scratchpad deleted

Accepted commit: see commit.txt (the gate commit on top of 124cfef)
Signed: Dhruv Mehta

## Correction (2026-11-27)
- The gate block was accidentally pasted twice. That appended a duplicate copy of this gate (commit d48b35f) and rewrote commit.txt (018b95e). Both are pushed and stay in history; no force-push. This commit restores the single copy from 5d96064, and commit.txt records this commit as the accepted one.
