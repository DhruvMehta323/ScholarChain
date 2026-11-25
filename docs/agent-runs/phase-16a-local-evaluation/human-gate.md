# Phase 16a — Local evaluation groups: Human Gate

Reviewer and ratifier: Dhruv Mehta (dmeht35)
Date: 2026-11-24 (CST)
Branch: lane2 (worktree ScholarChain-lane2), base f680ca5 (Phase 17a)
Decision: **ACCEPTED WITH NOTES** (Phase 16a is combined with Phase 16b into one Phase 16 at the end)

## 1. What I reviewed

- `prompt.md` (my work order), the orchestrator checkpoints, the experiment-engineer and test-engineer reports, the code-reviewer review 1 (REQUEST_CHANGES: H1 missing SUMMARY.md, M1–M5, L1–L7) and its final reread (APPROVE_WITH_NOTES, one MEDIUM in the summary text), and `summary-text.md`.

## 2. Decisions taken during the phase

1. **Repair round 1** approved (M1–M4 and L1–L7 to experiment-engineer, M5 to test-engineer).
2. **Second narrow repair** approved by me (beyond the work order's single round): test-engineer found a defect in `stated_counts` (adjacent severity counts parsed twice); experiment-engineer fixed only that function and reran g6 under a new run ID (f3); the failing test was kept unchanged and now passes.
3. **All runs kept**: raw outputs are write-once; aborted, partial and superseded runs are listed in the run index and marked as such.
4. **SUMMARY.md**: the Claude Code tool refused the subagent's write of a "report file" ("Subagents should return findings as text, not write report files"); no workaround was attempted. The orchestrator saved experiment-engineer's summary verbatim as `docs/agent-runs/phase-16a-local-evaluation/summary-text.md`, and **I copied it unchanged** to `experiments/results/local/SUMMARY.md` (manual action). Corrections are recorded in section 5, not edited into the copy.
5. **Evidence class "C-DOC"** accepted: a count derived from committed documents, not a run.

## 3. What was accepted

- Script `experiments/evaluation/local_eval.py` (sha256 9624849f…); final runs: g1–g5 and g7 = f2, g6 = f3; all earlier runs kept.
- **Group 1 (R):** every measurable figure equals the committed Phase 05 results; work precision and F1 read "not measurable", as in Phase 05.
- **Group 2 (L/R):** referential integrity, duplicate and orphan rates, edge support rate, a seeded 25-edge provenance audit; known small-graph results cited from committed tests.
- **Group 3:** local vector-only scores are perfect by construction and are not a quality result; graph-only/vector-only/fused comparison pending AWS.
- **Group 4 (F):** fixture-model answer checks, labeled as such; human rating sheet left empty.
- **Group 5:** skeleton, pending AWS.
- **Group 6 (C-DOC):** first-pass acceptance 0 of 15 phases (bounds 0.0–0.0625); per-phase reviewer severities with file:line citations; no ownership violation was ever caught by a hook (the ownership guard was never exercised).
- **Group 7 (L, partial):** non-sbt clean-checkout checks with raw outputs; a clean clone runs 346 manifest checks against 355 here because gitignored `datasets/raw` is absent.
- Tests (test-engineer observed): `tests/experiments` 173 passed, 0 failed; `tests/datasets` 149 passed (plus 4 subtests), 0 failed.

## 4. Test evidence

- No Scala source changed. The canonical CHECK is deferred to the Phase 16b/17b final CHECK, because lane 1 is using sbt for Phase 14 Part C.

## 5. Corrections to `SUMMARY.md` (recorded here; the copied file is verbatim)

| Line | As copied | Correct |
|---|---|---|
| 144 | phase-03 "INFO 4, LOW 5" | INFO 4, LOW 1 (the parser read finding ID "V3-5" as a count; the same wrong value is in g6 f3 `severity-by-phase.csv` and `factory-summary.json`) |
| 152 | phase-17a "MEDIUM 3, LOW 11, LOW 3" | review 1: MEDIUM 3, LOW 11; final reread: LOW 3 |
| 62 | g1/f2/er_report_py origin unclear | produced by `experiments/evaluation/er_report.py`, run separately (per its provenance files) |
| 19, 24 | g1 r1, g6 r1 | PARTIAL (no meta.json, so no evidence class or provenance) |
| 143 | phase-01 "HIGH 0" | "No High/Medium findings; Low findings L1–L5" |
| 127 | "phases with a stored review verdict: 16" | phases with a derivable first verdict (phase-17a has no review report) |

Raw-file notes (write-once, left as is): g3 f2/meta.json:5 labels a pending-AWS skeleton as class R; g6 f3/ambiguous.md:8 lists a false ambiguity.

## 6. Disclosures and residual risk

- The phase used two repair rounds; the second (narrow) round was approved by me.
- Unverified: that the second repair changed only `stated_counts` and that regenerating the other groups would give identical results (the old script version and the scratch regeneration were not kept).
- The g6 severity parser still misreads "finding ID followed by a severity word" lines (the phase-03 case above); the human-readable source line is stored beside each parsed cell.
- g7 has no unit test by design (it would run the same suites inside themselves).
- The stop hook blocked repeatedly because of merge timestamps; no agent ran sbt or changed timestamps.

## 7. My own checks

- [ ] `git status --short` shows only `experiments/**`, `tests/experiments/test_local_eval.py`, this phase folder, `experiments/results/local/SUMMARY.md` and `docs/agent-runs/phase-02-build-skeleton/commit.txt`.
- [ ] `diff docs/agent-runs/phase-16a-local-evaluation/summary-text.md experiments/results/local/SUMMARY.md` prints nothing.
- [ ] No AWS account ID in the new files (checked with a local grep; the value is not written here).

Accepted commit: see `commit.txt`.

Signed: Dhruv Mehta — 2026-11-24