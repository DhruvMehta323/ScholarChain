# Phase 08 — repair 02 (build-only)

Trigger: CHECK after repair 01 failed twice with resource failures in untouched modules (run 1: two 30 s timeouts in `spark.IdentitySuite`; run 2: `hadoop.AdversarialSuite` 30 s timeout and a `SparkOutOfMemoryError` in `spark.IdentitySuite`). See `repair-01.md`.

## Human authorization (recorded by the orchestrator from the human's answer)

Repair round 2 is BUILD-ONLY (build-engineer, `build.sbt` only; no test or source changes):
1. Run forked test groups one at a time across modules, for example `Global / concurrentRestrictions += Tags.limit(Tags.ForkedTestGroup, 1)` (or the sbt 2 equivalent), so Spark, Hadoop and the other test JVMs never overlap.
2. Give forked test JVMs an explicit heap (for example `-Xmx2g`) if not already set, and keep Spark suites at `local[2]`.
3. Do not change any timeout or test code. The AdversarialSuite 30 s limit stays as-is.

Then, with nothing else running, the orchestrator runs CHECK once and records all three runs honestly with elapsed times. D rereads only the build change. If CHECK passes: final checkpoint and STOP for the human gate. If it fails again: STOP and report; no retry.

Orchestrator note: code-reviewer has not yet reread repair 01 (work order §7 requires a reread of the repair). The orchestrator will ask D to reread repair 01 together with this build change, and nothing else.

## Outcome
(Filled in below.)

### R2 — build-engineer: COMPLETE
- Modified `build.sbt` only (repair-02 hunks): `Global / concurrentRestrictions ++= Seq(Tags.limit(Tags.ForkedTestGroup, 1), Tags.limit(Tags.Test, 1))` (the agent observed that sbt 2.0.12 already limits ForkedTestGroup to 1 by default, which serializes groups within a module, so `Tags.Test` was added to serialize the test tasks of separate modules); `Test / javaOptions += "-Xmx2g"` in `commonSettings` (no heap was set anywhere before). No timeout or test code changed.
- Spark test master (read-only): `modules/spark/src/test/scala/scholarchain/spark/TestSupport.scala:20` `local[2]`; `InputsRunSuite.scala:364,381` `local[2]`; storage `ParquetTables.scala:22` `local[2]`.
- Agent-observed: `sbt "scalafmtSbtCheck; show sparkAnalytics/Test/javaOptions; show Global/concurrentRestrictions; core/Test/compile"` `[success]` (twice); `show` lists forked-test-group 1 and test 1; sparkAnalytics Test/javaOptions ends with `-Xmx2g`. Whether this actually serializes module test runs is unproven until CHECK.

### CHECK after repair 02 (run 3 after repair 01; canonical command, nothing else running)
- Before the run the orchestrator stopped the local sbt server; no Java process remained; load average 2.23 / 4.69 / 3.95.
- testFull: core 874, entityResolution 293, sparkAnalytics 268 (`IdentitySuite` passed; no OOM), storage 323, rag 140, services 28 passed; **hadoop `Failed: Total 192, Failed 1`** — `scholarchain.hadoop.AdversarialSuite` "a giant team of 1,000 gives exactly 499,500 valid distinct pairs, none truncated" `TimeoutException: test timed out after 30 seconds` (34.756 s; suite 41.333 s).
- **2,117 passed, 1 failed. STATUS: FAILED.** `[error] elapsed time: 916 s`; marker `exit=1`. 0 `AwsItSuite`. Load average after the run 4.44 / 5.51 / 5.23.
- Observations (not a diagnosis):
  - In the output, module test runs appear one after another (er, core, …, hadoop, services, storage, …), so the cross-module serialization appears to have taken effect; the Spark OOM did not occur.
  - `hadoop` does not set `Test / parallelExecution := false` (only sparkAnalytics, storage and services do; build.sbt lines 136, 156, 183), so hadoop suites, including the local MapReduce `*ItSuite`s (BlockingCandidateItSuite 28.7 s, MatchDecisionItSuite 29.0 s, BlockCombinerItSuite 26.9 s, ErDeterminismItSuite 55.2 s, PipelineItSuite 61.0 s, DeterminismIdempotencyItSuite 118.0 s), may run concurrently with `AdversarialSuite` inside the one hadoop test JVM. Whether that causes the timeout is **not established**.
  - The human's limits forbid changing timeouts or tests; the remaining build-only lever would be `hadoop / Test / parallelExecution := false`, which was not authorized and was not made.

## All three CHECK runs after repair 01 (honest record)

| Run | Change before run | Result | Failures | Elapsed | Marker |
|---|---|---|---|---|---|
| 1 | repair 01 (R1a–R1d) | 2,116 passed, 2 failed | spark `IdentitySuite` M1, M2: 30 s timeouts | 1,651 s | exit=1 |
| 2 | none (human-authorized rerun; sbt servers stopped) | 2,116 passed, 2 failed | hadoop `AdversarialSuite` 30 s timeout; spark `IdentitySuite` `SparkOutOfMemoryError` | 1,030 s | exit=1 |
| 3 | repair 02 (build-only: serialize test tasks, `-Xmx2g`) | 2,117 passed, 1 failed | hadoop `AdversarialSuite` 30 s timeout | 916 s | exit=1 |

## STOP (human instruction: "If it fails again, STOP and report; do not retry")
- Not rerun. Code-reviewer has NOT reread repair 01 or repair 02 (the human's sequence put the reread after a passing CHECK). Nothing staged or committed.
