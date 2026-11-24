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
- [x] No API key or account ID in the new files: `grep -rn "477139661165" experiments load-tests tests/experiments` prints nothing.

Accepted commit: see `commit.txt`.

Signed: Dhruv Mehta — 2026-11-24