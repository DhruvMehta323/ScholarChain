# Phase 08 — repair 03 (final)

Trigger: CHECK run 3 after repair 01 (after repair 02) failed with one hadoop `AdversarialSuite` 30 s timeout. See `repair-02.md`.

## Human authorization (recorded by the orchestrator from the human's message)

Repair round 3 (final), in this order:
1. build-engineer, build-only: add `hadoop / Test / parallelExecution := false`. Nothing else.
2. The orchestrator runs CHECK once, with nothing else running.
3. ONLY if AdversarialSuite "a giant team of 1,000 gives exactly 499,500 valid distinct pairs" times out again: test-engineer raises that ONE test's timeout from 30 s to 120 s, with a code comment giving the reason (499,500 pairs under a loaded laptop; the assertion is unchanged). Not a weaker assertion, not any other test. Then CHECK once more.
4. D rereads repair 01, repair 02 and repair 03 together.
5. Record every CHECK run in the table honestly. If CHECK passes and D returns APPROVE or APPROVE WITH NOTES, write the final checkpoint and STOP for the human gate. Anything else: STOP and report.

## Outcome
(Filled in below.)

### Step 1 — build-engineer: COMPLETE
- `build.sbt` only, inside `lazy val hadoop`: comment plus `Test / parallelExecution := false`. Orchestrator `git diff build.sbt` confirms that hunk. Agent-observed: `sbt "scalafmtSbtCheck; show hadoop/Test/parallelExecution"` printed `false`, `[success] elapsed time: 14 s`.

### Step 2 — CHECK run 4 after repair 01 (canonical command, nothing else running)
- Before the run the orchestrator stopped the local sbt server; no Java process remained; load average 4.08 / 3.85 / 3.30.
- testFull: core 874, entityResolution 293, sparkAnalytics 268, storage 323, rag 140, services 28 passed; **hadoop `Failed: Total 192, Failed 1`** — `AdversarialSuite` "a giant team of 1,000 gives exactly 499,500 valid distinct pairs, none truncated" `TimeoutException: test timed out after 30 seconds` (32.683 s).
- **2,117 passed, 1 failed. STATUS: FAILED.** `[error] elapsed time: 1082 s`; marker `exit=1`. 0 `AwsItSuite`.
- The named test timed out again, so step 3 applies.

### Step 3 — test-engineer: COMPLETE
- Modified only `modules/hadoop/src/test/scala/scholarchain/hadoop/AdversarialSuite.scala` (orchestrator `git status --short modules/hadoop` shows only this file). munit 1.3.6 has only a suite-wide `munitTimeout` (agent confirmed via javap), so the agent added an `AtomicBoolean` set only while the one named test body runs (via a `TestTransform` in `munitTestTransforms`, cleared in `finally`); `munitTimeout` returns 120 s while it is set and `super.munitTimeout` (30 s) otherwise. Comment: "Timeout raised from 30 s to 120 s for this one test only … Reason: 499,500 pairs under a loaded laptop exceeded 30 s (32.683 s observed). The assertion and the input size are unchanged. Phase 08 repair 03." Assertions unchanged: `assertEquals(PairExpander.pairCount(1000), 499500L)`, `assertEquals(exhaust(1000), (499500L, 0L))`.
- Agent-observed: first attempt compile error (`munitTestTransform` is final; switched to `munitTestTransforms`); then `sbt "hadoop/Test/scalafmt; hadoop/testOnly scholarchain.hadoop.AdversarialSuite"` → `Passed: Total 19, Failed 0`, suite 34.923 s (single-test duration not measured; a filter attempt errored without running the test).

### Step 3 — CHECK run 5 after repair 01 (canonical command, nothing else running)
- Before the run the orchestrator stopped the local sbt server; no Java process remained; load average 2.93 / 4.24 / 4.37.
- testFull: core 874, entityResolution 293, hadoop 192, sparkAnalytics 268, storage 323, rag 140, services 28; Failed 0. **2,118 passed, 0 failed** (+327 over the 1,791 baseline). contractTest passed. `[success] elapsed time: 2269 s`; marker `exit=0`. 0 `AwsItSuite`. `hadoop.AdversarialSuite` passed (19 tests, suite 36.095 s).

## Every CHECK run after repair 01 (honest record)

| Run | Change before run | Result | Failures | Elapsed | Marker |
|---|---|---|---|---|---|
| 1 | repair 01 (R1a–R1d) | 2,116 passed, 2 failed | spark `IdentitySuite` M1, M2: 30 s timeouts | 1,651 s | exit=1 |
| 2 | none (human-authorized rerun; sbt servers stopped) | 2,116 passed, 2 failed | hadoop `AdversarialSuite` 30 s timeout; spark `IdentitySuite` `SparkOutOfMemoryError` | 1,030 s | exit=1 |
| 3 | repair 02 (build-only: serialize test tasks, `-Xmx2g`) | 2,117 passed, 1 failed | hadoop `AdversarialSuite` 30 s timeout | 916 s | exit=1 |
| 4 | repair 03 step 1 (build-only: `hadoop / Test / parallelExecution := false`) | 2,117 passed, 1 failed | hadoop `AdversarialSuite` 30 s timeout (32.683 s) | 1,082 s | exit=1 |
| 5 | repair 03 step 3 (that one test's timeout 30 s → 120 s; assertion unchanged) | **2,118 passed, 0 failed** | none | 2,269 s | exit=0 |

Earlier Phase 08 CHECK runs (before repair 01) are in `agent-report.md`: baseline 1,791; after A0, A1 1,791; after B1 run 1 1,790 + 1 failed (hadoop `AdversarialSuite` timeout), run 2 1,791; after B2 1,791; after C + rag fixes 2,059 + 1 failed (services CLI default); after C follow-up 2,060.

Note: the local run was not repeated after repairs 02 and 03 (build and hadoop-test changes only; no production source under `modules/*/src/main` changed). L3 = L4 remains the post-repair-01 evidence.

### Step 4 — code-reviewer reread of repairs 01, 02 and 03
- Verdict **APPROVE WITH NOTES**: M1, M2, M3 RESOLVED; 0 HIGH, 0 MEDIUM new; LOW N1–N4 (see `review-report.md`, review 2). Repair 02 and 03 confirmed within the authorized scope.

### Step 5
- CHECK passed (run 5, exit=0) and D returned APPROVE WITH NOTES → final checkpoint written in `agent-report.md`; STOP for the human gate.
