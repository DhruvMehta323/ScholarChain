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
