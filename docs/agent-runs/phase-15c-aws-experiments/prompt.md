# Phase 15c — AWS data and elasticity experiments (final work order)

Phase folder: `docs/agent-runs/phase-15c-aws-experiments/` (this file is its `prompt.md`).
Lane: main (`~/Desktop/ScholarChain`), fresh orchestrator session, same AWS window as Phase 14.
Spec: `COURSE-SPEC.local.md` (read-only), "Phase 15" and "Required scaling rules".
Phases 15a, 15b and 15c are combined into one Phase 15 at the end (17b).
**Time box: 75 minutes.** At the box, keep partial raw data, mark the rest `NOT EXECUTED (time)`, write the summary, commit.

## 0. State at start (verified facts, do not re-derive)

- HEAD clean on main, including Phase 14 (`d494931`, commit.txt `b648dc4`) and the merged lane2 (15b harness, 16a, 17a).
- Live release `38f2160a6e95` on query-api, retrieval, embedding-worker and the ops task. Embedding worker autoscaling min 1 / max 4 (target tracking on SQS backlog). query-api and retrieval autoscale on CPU / requests (Phase 12 policies).
- 10K graph loaded by run `r20261125T214348Z-63958-s4`: Neptune bulk load complete, DynamoDB 687,359 items, 9,968 of 10,000 vectors (2 jobs in the DLQ, F14-16). Hadoop HJ1–HJ7 ran on EMR (run `r20261125T214348Z-63958`); **Spark ran locally on Dhruv's Mac**, so no Spark-elasticity claim may be made.
- Runtime model Haiku 4.5 on Bedrock (`us.` inference profile); embeddings Titan Embed Text v2. Query API through the ALB on plain HTTP with the smoke key file `~/.scholarchain/services-api-key` (15b rules).

## 1. Delegated authority and limits (Dhruv's decision; record in agent-report.md)

- You route; owners run approved scripts and manifests only (`scripts/run-experiment.sh`, the 15b collectors and analysis scripts, read-only AWS calls). No production-code, Terraform, script or rule-file edits. **No `deploy.sh`, no `terraform apply/destroy`, no `cleanup.sh`, no `env-window.sh down`.** If a step truly needs `deploy.sh`, message Dhruv that one line and continue with other experiments meanwhile.
- You may commit this phase yourself after the diff-only review: `git diff --cached | grep -c <account id>` must be 0 (use a local grep, never write the ID anywhere); never stage `reports/`. If a script needs a clean tree before you start, first commit this `prompt.md` alone as `phase-15c: work order`.
- Cost: read month-to-date cost before credits at the start and after each experiment; **STOP if it exceeds $15.** Actual spend is ≈ $9 (Cost Explorer lags). Bedrock: at most 40 query calls in this phase. **No new EMR clusters.**
- Start `caffeinate -dimsu &` first. Honesty floor: raw outputs write-once, failed or partial runs kept, nothing tuned mid-run, every number cites its raw file.
- `## Progress checkpoint` in agent-report.md after each experiment. One repair round at most (analysis only).

| Work order | Agent | Writes only to |
|---|---|---|
| X | experiment-engineer | `experiments/**`, `reports/experiments/**`, `reports/evidence/**` |
| I | incident-responder | `docs/incidents/**` |
| D | code-reviewer | nothing |

## 2. X — experiments, in this order

1. **A, Hadoop baseline (collect only, ≈ 10 min):** from the `…63958` run records and EMR/CloudWatch for that cluster window: HJ1–HJ7 step durations, Hadoop counters, task counts, failures, instance hours, cost estimate. Also list every earlier 10K attempt (`…79065`, `…43602`, the C7 attempt) as failed runs with their cause (F14-5/6/7).
2. **B, EMR capacity timeline (collect only, ≈ 5 min):** requested / provisioned / released capacity over time for the `…63958` cluster. Disclose: no separate burst or 50K run; Spark was not on EMR for this run.
3. **C, embedding backlog (≈ 15 min):** first use the CloudWatch window of the Phase 14 s4 drain (SQS visible/in-flight messages, worker desired/running tasks, scaling activities, throughput, retries, DLQ count, drain time). Only if that window shows no scale-out above 1 task, enqueue one fresh capped batch per the 15b manifest (≤ 2,000 chunks, fresh text so the hash skip does not apply) and observe up to 15 min.
4. **D, query load (≈ 20 min):** two concurrency levels per the manifest (e.g. 5 then 20 clients, 4 min each), within the Bedrock cap, then up to 8 min idle observation. Record p50/p95/p99, throughput, error rate, ECS desired/running for query-api and retrieval, CPU, Neptune latency, DynamoDB throttles, vector-query latency, LLM latency, synchronized timestamps. Must show ≥1 scale-out and ≥1 scale-in; if scale-in does not happen in the window, record that and the policy cooldown honestly.
5. **F, failure and recovery (≈ 5 min):** stop one running retrieval task (ECS replaces it) OR send one poison message to the embedding queue (retries → DLQ). Record timestamps and recovery. Also reference the real Phase 14 incident (F14-4, the C4 image-permission rollback).
6. **E, retrieval modes:** NOT here; it runs in Phase 16b group 3.
7. Run the analysis scripts on the raw data: latency percentiles per level, task counts over time, queue depth and drain, EMR timeline, cost per experiment. Write `experiments/results/aws/SUMMARY.md` with a limitations section (no 50K run, Spark local, short durations, small corpus, single runs).

## 3. I — incident-responder

1. `docs/incidents/` report for experiment F with every spec field: incident ID, start and end time, detection source, user-visible impact, trace and run IDs, timeline, root cause, recovery action, data-integrity assessment, follow-up owner, preventive test or control.
2. `docs/incidents/INC-001-c4-image-file-modes.md` for the real Phase 14 incident F14-4, from committed Phase 14 evidence only (CloudWatch error window 15:58–16:08 UTC on 2026-11-25, automatic ECS circuit-breaker rollback, no user impact, root cause 0600 jars vs uid 10001, fix C5). Unknowns are `[[UNKNOWN]]`.

## 4. D — code-reviewer

Diff-only pass: every number traces to raw files; scale-out and scale-in visible with timestamps; failed runs kept; limitations honest; incident reports complete; no account ID or secrets. Verdict.

## 5. Finish

1. Commit: `phase-15c experiment-engineer, incident-responder: AWS experiments and incidents`.
2. Then STOP with:
   - "Phase 15c committed: <sha>"
   - results table (A, B, C, D, F: one line each, with raw paths)
   - cost reading, anything NOT EXECUTED
   - a **draft `human-gate.md`** in your message (Decision line left for Dhruv, authority recorded, gate lines from the spec's Phase 15 gate: scale-out and scale-in shown, synchronized timestamps, raw data preserved, costs and limits stated, failures reported). Do not write `human-gate.md` yourself.

STOP early on cost over $15, a failed AWS step that one retry does not fix, or a boundary.
