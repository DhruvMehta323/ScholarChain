STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 02 build skeleton, consolidated final report, including repair 01 (reviewer blocking finding B1). B1: build.sbt had the literal Docker base image tag `eclipse-temurin:17.0.21_9-jre-jammy`, a pinned version outside the ledger. dependency-updater added `temurinJreImage` to project/Versions.scala. I replaced the literal with a reference to it. I made no other changes and did not act on the reviewer's non-blocking notes.

FILES CREATED (by build-engineer across Phase 02; created in my earlier run, listed from the git status I observed in this run):
- /Users/dhruv_mehta/Desktop/ScholarChain/build.sbt: multi-module build, `check` alias, packaging settings.
- /Users/dhruv_mehta/Desktop/ScholarChain/project/Dependencies.scala: dependency definitions. Every version is a `Versions.*` reference.
- /Users/dhruv_mehta/Desktop/ScholarChain/project/plugins.sbt: sbt-scalafmt, sbt-native-packager and sbt-assembly. Versions are read from the ledger.
- /Users/dhruv_mehta/Desktop/ScholarChain/project/build.properties: `sbt.version=2.0.12`.
- /Users/dhruv_mehta/Desktop/ScholarChain/.scalafmt.conf: `version = 3.11.5`.
Created by other agents (not by me):
- /Users/dhruv_mehta/Desktop/ScholarChain/project/Versions.scala: dependency-updater. It holds the ledger, including the new `temurinJreImage`.
- /Users/dhruv_mehta/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/ScholarChain.scala: domain-engineer.
- /Users/dhruv_mehta/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/CoreContractSuite.scala: test-engineer.

FILES MODIFIED:
- /Users/dhruv_mehta/Desktop/ScholarChain/.gitignore: modified in my earlier run. It shows as ` M` in git status. It should now ignore `.claude/.last-test-run`, but I did not re-read the file in this run.
- /Users/dhruv_mehta/Desktop/ScholarChain/build.sbt: in this run (repair 01), line 127 is now `dockerBaseImage := s"eclipse-temurin:${Versions.temurinJreImage}"`.

COMMANDS RUN (this run):
- Read of build.sbt and Versions.scala: ok.
- Edit of build.sbt (B1 fix): ok.
- Grep for version-like literals in build.sbt, project/*.sbt, project/*.scala, project/build.properties and .scalafmt.conf, excluding Versions.scala: exit 0.
- `sbt shutdown`: exit=0, "[info] disconnected". The exit code printed there came from `tail`, so it is not a reliable sbt exit status.
- `sbt services/Docker/stage`: `[success] elapsed time: 1 s`, exit 0. It printed a warning that docker is unavailable for API version detection, which fits the daemon being down.
- `grep` of FROM and USER lines in the generated Dockerfile: exit 0.
- `sbt check; echo "exit=$?" > .claude/.last-test-run` (alone, canonical): ended with `[success] elapsed time: 2 s`.
- `cat .claude/.last-test-run` (separate call): `exit=0`.
- `sbt jdkInfo`: `[success] elapsed time: 0 s`.
- `git status --short` and `find modules -type f`: ok.

TEST RESULTS:
- `sbt check` succeeded. The marker reads `exit=0`.
- Passed tests: CoreContractSuite, 2 of 2, 0 failed. It ran several times, as `Test/testFull` and `contractTest`, once per aggregate.
- The other modules (storage, entityResolution, rag, hadoop, services, sparkAnalytics) reported "No tests to run" with 0 failed, because they have no test sources yet.
- scalafmt checked 2 Scala sources (the check output printed this line twice) and passed.
- The log ended with "markTestRun: wrote .../.claude/.last-test-run". The shell `echo` then overwrote the marker with `exit=0`.

VALIDATION RESULTS:
- Version literals. The only matches outside Versions.scala were:
  - `ThisBuild / version := "0.1.0-SNAPSHOT"`: the project's own version, not a dependency.
  - `-XX:MaxRAMPercentage=75.0` and `-XX:InitialRAMPercentage=50.0`: JVM flag values.
  - Comments that mention versions.
  - `project/build.properties` `sbt.version=2.0.12` and `.scalafmt.conf` `version = 3.11.5`: unavoidable, as agreed.
  - No Docker image tag literal remains anywhere in build files.
- Generated Dockerfile at /Users/dhruv_mehta/Desktop/ScholarChain/target/out/jvm/scala-3.3.9/scholarchain-services/docker/stage/Dockerfile (sbt 2 output layout, not modules/services/target):
  - `1:FROM eclipse-temurin:17.0.21_9-jre-jammy AS stage0`
  - `13:FROM eclipse-temurin:17.0.21_9-jre-jammy AS mainstage`
  - `20:USER 10001:10001`
  - Lines 8 and 14 are `USER root`, used during staging and file setup. The final user is the non-root 10001:10001.
- Complete version ledger, read from Versions.scala in this run:
  - Languages and runtimes: scala3 3.3.9, scala213 2.13.17.
  - Engines: spark 4.1.1, hadoop 3.4.2.
  - Libraries: upickle 4.4.3, munit 1.3.6, logback 1.5.38, slf4j 2.0.17.
  - Formatting: scalafmt 3.11.5.
  - sbt plugins: sbtScalafmt 2.6.2, sbtNativePackager 1.13.0, sbtAssembly 2.5.0.
  - Container images: temurinJreImage 17.0.21_9-jre-jammy.
  - Outside the ledger: sbt 2.0.12 (project/build.properties) and scalafmt 3.11.5 (.scalafmt.conf).
- Module dependency graph, from build.sbt in this run:
  - core depends on nothing.
  - hadoop, entityResolution (modules/entity-resolution), sparkAnalytics (modules/spark), storage and rag each depend on core.
  - services depends on rag and storage, and on core transitively.
  - Nothing depends on services. There are no cycles.
  - root only aggregates the seven modules.
- Environment input surface:
  - build.sbt reads one environment variable, `SCHOLARCHAIN_JAVA_HOME`. It is optional and selects the JDK that forks test JVMs. It was unset in this run.
  - No secrets, cloud endpoints or deployment steps are in the build.
  - RISK (undocumented sbt-server caveat): sbt 2 may keep a long-lived server. A server started earlier can hold a stale environment, so a change to `SCHOLARCHAIN_JAVA_HOME` may not take effect until `sbt shutdown`. I did not verify this. It is the reviewer's note N2, pending a human decision.
  - RISK (working directory): plugins.sbt reads `project/Versions.scala` through a relative path, `new File("project/Versions.scala")`. It only works when sbt starts from the repository root. It also parses the ledger with a regex that is not anchored. This is note N3, pending a human decision.
- Packaging choices:
  - services uses JavaAppPackaging and DockerPlugin. The base image comes from the ledger. The runtime user is a non-root `scholarchain` account, uid and gid 10001.
  - JVM options are MaxRAMPercentage=75, InitialRAMPercentage=50 and ExitOnOutOfMemoryError. `dockerRepository` is None and `dockerUpdateLatest` is false.
  - hadoop and sparkAnalytics build fat jars with sbt-assembly (`packageJobs` alias). The merge strategy concatenates `META-INF/services` and `reference.conf`, discards other `META-INF` entries and `module-info.class`, and keeps the first of any other duplicate.
  - The Spark jar excludes scala-library because EMR supplies it. The Hadoop jar keeps it, because MapReduce does not supply it.
  - Spark and Hadoop dependencies are `Provided`.
  - Digest pinning of the base image is a later human decision. I did not add it.
- Scala 3, Hadoop and Spark compatibility risks:
  - Spark is a Scala 2.13 library. It is consumed through `CrossVersion.for3Use2_13`, restricted to the DataFrame/Row API, with no Scala 2 implicit encoder derivation.
  - The alternative of building the Spark module on Scala 2.13 was rejected, to keep one Scala line across the repository.
  - Residual risks: macro or implicit-heavy Spark APIs may not work from Scala 3, and Scala 3.3.x LTS brings scala-library 2.13.18 transitively. `dependencyOverrides` pins scala-library to 2.13.17 to match EMR.
  - Compilation against real Spark and Hadoop sources has not happened yet, because those modules are empty.
  - Hadoop client jars are `Provided`, so a local MapReduce run needs them supplied.
- EMR reasoning:
  - The target is emr-spark-8.1.0. The orchestrator verified Spark 4.1.1-amzn-0, Hadoop 3.4.2-amzn-3, Scala 2.13.17 and Java 17 at https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html. The ledger matches these (spark 4.1.1, hadoop 3.4.2, scala213 2.13.17), and `-release 17` matches Java 17.
  - RISK: MapReduce on the 8.x EMR train is unverified until Phase 12. Fallback: EMR 7.13.0, which would require a human decision and a ledger update by dependency-updater.
- markTestRun versus CLAUDE.md section 7:
  - The `check` alias ends with a `markTestRun` task that writes `.claude/.last-test-run` from inside sbt, only after all tests pass.
  - CLAUDE.md section 7 says the canonical shell command is the only accepted way to write the marker.
  - RISK: this is a conflict for a human to decide. A factory-engineer amendment will follow.
  - In this run the shell echo overwrote the marker afterwards, so the final content is the canonical `exit=0`.
- JDK situation:
  - `sbt jdkInfo` printed: "sbt JVM: java.version=27.0.1.0.3 home=/opt/homebrew/Cellar/openjdk/27.0.1.0.3/libexec/openjdk.jdk/Contents/Home" and "forked test JVM (same as sbt): openjdk version "27.0.1.0.3" 2026-10-24 ...".
  - Tests were observed on JDK 27, not the target JDK 17. Compilation uses `-release 17` and `--release 17`.
  - The JDK 17 `--add-opens` flags are applied. One JVM warning, "package sun.security.action not in java.base", appears on JDK 27.
  - JDK 17 test runs need `SCHOLARCHAIN_JAVA_HOME` pointing at a JDK 17. That has not been exercised.
- Empty module source roots and missing top-level directories:
  - Only `modules/core` has sources: ScholarChain.scala (domain-engineer) and CoreContractSuite.scala (test-engineer).
  - The other modules have no source roots, and I did not create them. Source roots belong to their module owners, and test roots to test-engineer.
  - I did not create the top-level directories `datasets/`, `scripts/`, `infra/`, `tests/` or `docs/` content, because they belong to other owners.
  - sbt tolerates the missing directories.
- Verification limits:
  - Everything was verified in the working tree, which has uncommitted changes, not in a fresh clone.
  - `dockerLocal` (`services/Docker/publishLocal`) was NOT run, because the Docker daemon is down. Only `Docker/stage` was verified.
  - `packageJobs` was not re-run in this run.

ASSUMPTIONS:
- The reviewer's note about `Docker/stage` output location is covered: sbt 2 writes under the root `target/out/jvm/...`.
- The temurinJreImage tag is valid. The orchestrator confirmed it via the Docker Hub API. I did not check it myself.
- The JDK 17 flag list is sufficient for Spark. This is unverified until Spark code exists.

RISKS:
- sbt-server stale environment (N2), plugins.sbt working-directory dependency and unanchored regex (N3), markTestRun versus section 7, tests on JDK 27, MapReduce on EMR 8.x unverified, Spark through for3Use2_13, no fresh-clone verification, and the Docker image not built (only staged, and not digest-pinned).
- The build files contain no unreviewed content from this repair beyond the one-line change.

MANUAL ACTION REQUIRED:
- Human decisions: the markTestRun versus section 7 question (a factory-engineer amendment will follow), the non-blocking notes N2 and N3 and the rest, and digest pinning.
- Human acceptance and commit. I did not commit, push or self-approve.
- Start Docker and run `sbt dockerLocal` when an image is wanted.
- Optionally set `SCHOLARCHAIN_JAVA_HOME` to a JDK 17 and re-run `sbt check` for JDK 17 evidence.

BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: code-reviewer re-review of repair 01 (B1), then human acceptance.
