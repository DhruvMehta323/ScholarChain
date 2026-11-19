STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only re-review of Phase 02 (build and repository skeleton) after review repair 01. The work order is docs/agent-runs/phase-02-build-skeleton/prompt.md. The job was to verify the fix for blocking finding B1 (a container image version literal outside the ledger), confirm the repair introduced nothing else, re-verify notes N1 to N13, and return a standalone verdict.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have no shell. I used only Read, Grep and Glob.
TEST RESULTS: none run. The reported test evidence is that the canonical `sbt check; echo "exit=$?" > .claude/.last-test-run` ended `[success]` with CoreContractSuite at 2/2, both in build-engineer's repair run and in the orchestrator's earlier run. What I could check in files: /Users/dhruv_mehta/Desktop/ScholarChain/.claude/.last-test-run contains `exit=0`. That matches the canonical command running last. It shows a zero exit code, not which tests ran or passed. The `[success]` lines, the 2/2 counts and the jdkInfo output (JDK 27.0.1.0.3) cannot be checked from files and are UNVERIFIED.
VALIDATION RESULTS:

## Verdict: APPROVE WITH NOTES

B1 is fixed. No blocking finding remains. Several notes need a human decision before Phase 02 is accepted (listed under MANUAL ACTION REQUIRED).

## Blocking findings

None.

### B1 (from the first review): RESOLVED
- The finding was a pinned image literal in build.sbt:127. That line now reads `dockerBaseImage := s"eclipse-temurin:${Versions.temurinJreImage}"`.
- /Users/dhruv_mehta/Desktop/ScholarChain/project/Versions.scala:29-30 adds the `// Container images` section with `val temurinJreImage = "17.0.21_9-jre-jammy"`. The file still holds only version constants (lines 1-31).
- The generated Dockerfile exists at /Users/dhruv_mehta/Desktop/ScholarChain/target/out/jvm/scala-3.3.9/scholarchain-services/docker/stage/Dockerfile:
  - line 1: `FROM eclipse-temurin:17.0.21_9-jre-jammy AS stage0`
  - line 13: `... AS mainstage`
  - line 20: `USER 10001:10001`

  This matches what build-engineer reported.
- Ledger completeness: I searched for `\d+\.\d+\.\d+` in build.sbt, project/*.scala, project/*.sbt, project/build.properties and .scalafmt.conf.
  - Outside Versions.scala, the only matches are:
    - `project/build.properties:1 sbt.version=2.0.12` (unavoidable)
    - `.scalafmt.conf:1 version = 3.11.5` (unavoidable)
    - `build.sbt:19` project version `0.1.0-SNAPSHOT` (not a dependency)
    - version numbers in comments at build.sbt:21, 25 and 71
  - Every dependency in project/Dependencies.scala:10-31 uses `Versions.*`.
  - Every plugin in project/plugins.sbt:18-20 uses `ledger(...)`.
  - No floating or latest versions anywhere.
- The docker tag is a mutable tag, not a digest. Digest pinning is deferred to a human decision (see H6).

## Repair scope check
I re-read every Phase 02 file in full:
- .gitignore
- .scalafmt.conf
- build.sbt
- project/build.properties
- project/plugins.sbt
- project/Dependencies.scala
- project/Versions.scala
- modules/core/src/main/scala/scholarchain/core/ScholarChain.scala
- modules/core/src/test/scala/scholarchain/core/CoreContractSuite.scala

Each file's content matches what the first review described, except the two repair changes. I found no other change.

Limit: I cannot diff against the state at the first review, because the files are untracked and I have no shell. "No other change" is therefore based on reading the content, not on a diff.

## Checks
- **Ownership: PASS for the build files.** Changed paths and their owners under docs/agents.md section 1:
  - build-engineer: build.sbt, project/build.properties, project/plugins.sbt, project/Dependencies.scala, .gitignore, .scalafmt.conf
  - dependency-updater: project/Versions.scala (section 1.1 item 1). Repair 01 kept to these owners.
  - ScholarChain.scala is owned by domain-engineer (`modules/core/**`). CoreContractSuite.scala is owned by test-engineer (`modules/*/src/test/**`). See N14 for who wrote them.
- **Module boundaries: PASS.**
  - build.sbt:81-121: core has no `dependsOn`.
  - hadoop, entityResolution, sparkAnalytics, storage and rag depend only on core.
  - services depends on rag and storage.
  - Nothing depends on services. No cycles.
- **Feature test: PASS (the skeleton has no behaviour change).** CoreContractSuite.scala:7-14 asserts the core constant. That follows the human decision for the smoke test.
- **Scopes: PASS.**
  - Hadoop and Spark are `Provided` (Dependencies.scala:20-28).
  - munit is `Test` (line 17).
  - logback is `Runtime` (line 14).
  - Spark uses `CrossVersion.for3Use2_13` (lines 26, 28).
  - uPickle is the single JSON library and MUnit the single test framework.
- **Secrets, endpoints and deployment: PASS.**
  - build.sbt has no secrets, cloud endpoints or deployment steps.
  - `dockerRepository := None` (line 128).
  - The only environment input is SCHOLARCHAIN_JAVA_HOME, documented at lines 7-9.
- **Terraform-only infrastructure: PASS (none present).**
- **Hidden reasoning in artifacts: PASS (none found).**
- **Provenance: N/A for build wiring.**
- **Aliases: PASS.**
  - `fmt`, `check`, `packageJobs` and `dockerLocal` are defined at build.sbt:176-183.
  - `check` runs `scalafmtCheckAll; scalafmtSbtCheck; Test/compile; Test/testFull; contractTest; markTestRun`.
- **Container requirements: PASS by configuration.**
  - Pinned JRE tag through the ledger.
  - Non-root UID/GID 10001 (build.sbt:131-134; Dockerfile:20).
  - Percentage memory options at build.sbt:135-139, staged into /Users/dhruv_mehta/Desktop/ScholarChain/target/out/jvm/scala-3.3.9/scholarchain-services/docker/stage/1/opt/docker/conf/application.ini lines 2-4.
- **.gitignore: PASS.**
  - All required existing entries are kept (lines 1-19).
  - Added: `.claude/.last-test-run` (30), `.bsp/`, `.idea/`, `.vscode/` (21-23), and Terraform state and plan files (33-42).

## Notes carried forward and re-verified (ranked by severity)

- **N1 (high, human decision): STILL HOLDS.**
  - build.sbt:26-27 sets `-release 17` and `--release 17`.
  - build.sbt:53 forks tests on SCHOLARCHAIN_JAVA_HOME, or otherwise on the JVM running sbt.
  - The repair evidence again reports JDK 27.0.1.0.3 for both sbt and forked tests (UNVERIFIED from files). Tests have still never been observed on JDK 17, the EMR runtime. The `--add-opens` set (lines 33-48) is also unexercised on 17.
- **N4 (medium, human decision): STILL HOLDS.**
  - Glob shows only two module files, both under modules/core.
  - The source roots for the other six modules and the top-level owned directories from work-order lines 39-40 do not exist.
  - The ownership behaviour is correct. The literal work order is not met.
- **N5 (medium, human decision): STILL HOLDS.**
  - All verification ran in the working tree. git status shows the Phase 02 files untracked, and a populated target/ exists.
  - Work-order line 42 asks for a clean repository state. Not done.
- **N2 (medium): STILL HOLDS.**
  - Nothing in build.sbt or the repository documents that the sbt 2 server keeps the environment it started with. A changed SCHOLARCHAIN_JAVA_HOME needs `sbt shutdown`.
  - build-engineer's own repair run needed `sbt shutdown` first, which supports this note.
- **N3 (medium): STILL HOLDS.**
  - project/plugins.sbt:5 uses the relative `new java.io.File("project/Versions.scala")`.
  - The regex at line 9 is not anchored to the start of a line or to non-comment text, so `// val sbtAssembly = "x"` would match first.
  - Constants with similar names do not collide, because `\s+` must come directly before the name.
- **N6 (medium, risk): STILL HOLDS.** build.sbt:21-23 and Dependencies.scala:31 force scala-library 2.13.17, while Scala 3.3.9 artifacts expect 2.13.18 (comment at build.sbt:21).
- **N7 (low-medium, risk): STILL HOLDS.** build.sbt:90 passes `dropScalaLibrary = false` for hadoop, so its assembly bundles scala-library. Recheck in Phases 04 and 12.
- **N8 (low): STILL HOLDS.** build.sbt:64-70 discards `META-INF/**` except `services`, and uses `MergeStrategy.first` as the fallback. This can drop signature, license or `versions/` multi-release entries.
- **N9 (low): STILL HOLDS.** Versions.scala:22 `scalafmt = "3.11.5"` is not referenced anywhere in the build and duplicates .scalafmt.conf:1.
- **N10 (low): STILL HOLDS.** Dependencies.scala:27-28 includes spark-graphx, an RDD API that leans heavily on Scala 2. spark-engineer should confirm the need in Phase 06.
- **N11 (low): STILL HOLDS.** build.sbt:160: markTestRun depends on `Test/testFull` again, so `check` runs the suites three times. The orchestrator's report of 2/2 "in testFull, contractTest and markTestRun" is consistent with this.
- **N12 (low): PARTLY RESOLVED.**
  - The `-J` options are now confirmed staged into application.ini.
  - Their effect at run time inside the container is still UNVERIFIED.
  - No `mainClass` is set (build.sbt:119-140), although the Dockerfile ENTRYPOINT refers to bin/scholarchain-services.
- **N13 (info): STILL HOLDS.** build.sbt:150-153 builds an `sh -c` string from SCHOLARCHAIN_JAVA_HOME. The risk is negligible (an operator-set local variable).

## New findings

- **N14 (medium, UNVERIFIED, human/orchestrator confirmation): authorship of the two core files.**
  - modules/core/src/main/scala/scholarchain/core/ScholarChain.scala is owned by domain-engineer.
  - modules/core/src/test/scala/scholarchain/core/CoreContractSuite.scala is owned by test-engineer.
  - Work-order line 49 asks build-engineer to add the test, which conflicts with section 1. The phase directory contains only prompt.md, and I could not read any agent report, so I cannot confirm which agent wrote these files.
  - If build-engineer wrote them, that is an ownership deviation the human should record. guard-writes.sh would normally block it when `agent_type` is present.
- **N15 (low, risk): mutable image tag.** `17.0.21_9-jre-jammy` is a tag, not a digest. dependency-updater reported the index digest sha256:aa6381384f8db99762fa7d0d3c2b5de58874e5f12c96df4c13a1455376ae0ed1 and a tag_status of active (UNVERIFIED by me). Digest pinning is deferred to a human decision.
- **N16 (info): stale ledger comment.** Versions.scala:4 says the constants are referenced from build.sbt and project/Dependencies.scala. plugins.sbt also reads them textually. dependency-updater should update the comment at its next edit.

## Human decisions already recorded (given)
- EMR target emr-spark-8.1.0 (Spark 4.1.1, Hadoop 3.4.2, Scala 2.13.17, Java 17). The ledger matches: Versions.scala:9, 12, 13.
- markTestRun writes the marker (build.sbt:159-172). The conflict with CLAUDE.md section 7 is a recorded RISK, with a factory-engineer amendment to follow.
- The smoke test asserts a trivial core constant.

ASSUMPTIONS:
- The files I read are the complete Phase 02 change set: git status plus the Glob of modules/** and project/*. Empty directories would not appear in that Glob.
- The generated Dockerfile and application.ini come from build-engineer's repair-01 `services/Docker/stage` run. Their content matches the current build.sbt.
- The prior review's descriptions of unchanged files are accurate. I had no diff baseline.

RISKS:
- Never tested on JDK 17 (N1).
- scala-library is downgraded from 2.13.18 to 2.13.17 (N6).
- The Hadoop fat jar bundles scala-library (N7).
- Lossy META-INF merge (N8).
- Ledger read depends on the working directory and can match a comment (N3).
- Stale environment in the sbt server (N2).
- Mutable image tag (N15).
- markTestRun conflicts with CLAUDE.md section 7 (accepted risk).
- Authorship of the core files is unconfirmed (N14).
- Every `[success]`, test-count and JDK claim is UNVERIFIED from files.

MANUAL ACTION REQUIRED:
- H1 (N1): accept or reject Phase 02 without a test run on JDK 17. The alternative is to require one run with SCHOLARCHAIN_JAVA_HOME pointing at Temurin 17.0.21, after `sbt shutdown`.
- H2 (N4): accept the deviation from work-order lines 39-40, or route source-root and directory creation to each owner.
- H3 (N5): accept working-tree verification, or require `sbt Test/compile` and `sbt check` on a clean clone.
- H4 (N14): the orchestrator confirms which agents wrote ScholarChain.scala and CoreContractSuite.scala, and records any ownership deviation.
- H5 (N2, N3): decide whether build-engineer documents the sbt server environment and hardens the ledger reader now or in a later phase.
- H6 (N15): decide on digest pinning of the base image. That is a dependency-updater ledger value plus a build-engineer reference.
- H7: final ratification of Phase 02, written by the human in docs/agent-runs/phase-02-build-skeleton/human-gate.md.

BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Human decision on this verdict (APPROVE WITH NOTES) and on H1-H7, then Phase 02 ratification in docs/agent-runs/phase-02-build-skeleton/human-gate.md.
