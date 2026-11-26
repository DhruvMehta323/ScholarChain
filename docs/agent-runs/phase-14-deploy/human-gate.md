# Phase 14 — Human Gate

## Pre-deploy approval

Reviewer: Dhruv Mehta (dmeht35) — 2026-11-24 (CST)

- Plan P1 reviewed and applied by me: plan_sha256=4bd8164d14fd305b62b4e6b4155013c06303250a747116fbf5b9353f83bd33f5
- Plan commit a556430 (Part A, clean tree); account_check=match; region us-east-2; environment course.
- 177 to add, 0 change, 0 destroy, 0 replace; 24 IAM creates (including the ops role and the Neptune loader role, approved by me as security changes); no NAT gateway, no interface endpoints; Neptune and ALB on for the Phase 14/15c window.
- SNS subscription confirmed; services and internal API keys set by me (values not recorded); smoke key file at ~/.scholarchain/ (mode 600, outside the repo).
- Deployment approved for the exact commit that contains this note (C2), following the two-apply order in agent-report.md.

## Final gate

(appended after Part C)

## Phase 14 final gate
Decision: ACCEPT WITH NOTES

Recorded authority (widened in session by me, Dhruv; confirmed):
- Orchestrator commits C5–C11 under the two pre-commit checks (0 account-ID hits; reports/ never staged).
- Orchestrator real runs of run-bounded-pipeline.sh, S3 uploads of the Spark-local output, the Spark-local fallback.
- Applies P3–P5 by my pre-stated rules. P5 (C11): add 1 (Neptune SG egress, tcp 443, S3 prefix list), change 4 (3 ECS services + worker min 0→1), destroy 0, replace 4, iam 0.
- Security change F14-14 (Neptune→S3 egress) approved by me; code-review S-1 acceptable; trivy: nothing new.
- Worker autoscaling minimum 1 (M1) approved by me.
- skopeo push of embedding-worker :38f2160a6e95 directed by me (Docker push kept failing on this network).
- Second-failure stop waived (one-loop fix rounds); cost stop raised from $12 to $15.
- Stale Neptune loader jobs not listed (would need ListLoaderJobs, an IAM change). Accepted.

Gate lines:
- Applied plans match reviewed or rule-approved SHAs (table in agent-report.md); no NAT: YES
- Services on clean-commit images (38f2160a6e95); smoke passed on attempt 2 (attempt 1 failed /readyz with a 503 right after the load; both kept): YES
- 10K on EMR (Hadoop AND Spark) with recorded timings: NOT MET. Hadoop HJ1–HJ7 ran on EMR; Spark ran locally on my Mac on the EMR Hadoop outputs (pre-authorised fallback); run.json for r20261125T214348Z-63958 is stale ("running"). Decision: ACCEPTED AS DEVIATION, disclosed in the final report.
- Storage load and ≤10K vectors: Neptune bulk load complete; DynamoDB 687,359 items, 0 conflicts; 9,968 of 10,000 vectors (2 jobs / 32 docs in the DLQ, F14-16). Decision: ACCEPTED WITH NOTE.
- Cost recorded before credits: $8.94 (Cost Explorer lags; actual higher). Decision: RECORDED; stop rule $15 respected; no new EMR clusters after the 63958 run.
- Reviewer verdicts: C11 APPROVE-RECOMMENDED; Part D CHANGES-REQUIRED, addressed in the report, not re-reviewed (disclosed).
- Timing: Phase 14 completed after the Nov 25 11 PM deadline; commit timestamps show it.

Findings: F14-1 … F14-16 (agent-report.md). Deferred to 17b: C10 M1/M2, C11 L-1–L-7, unowned reports/pipeline/** and reports/release/** (factory-engineer), F14-3 guard false positive.

Human actions (open):
[ ] rotate the services API key (sent over plain HTTP)
[ ] restrict or expire the CloudWatch stream named in F14-13 (token)
[ ] DLQ: redrive or discard the 2 messages before retention expires
[ ] graph verification: smoke query passed; stale s3 loader jobs possible, bulk-load IDs are idempotent; checked again in 16b
[ ] orphaned S3 data (partial 63958 neptune-load prefix, s2/s3 outputs, Spark copies): delete at cleanup
[ ] after Phase 15c: worker min/max 0 (INFRA-CONTRACTS §9b), env-window down after the video
Signed: Dhruv Mehta   Date: 2026-11-26
