# Phase 02 human gate — build and repository skeleton

## What was reviewed
- build-engineer's consolidated report (agent-report.md) and both code-reviewer verdicts (review-report.md holds the final one).
- The build files: build.sbt, project/build.properties, project/plugins.sbt, project/Dependencies.scala, project/Versions.scala, .scalafmt.conf, .gitignore.
- The smoke-test pair: modules/core/src/main/scala/scholarchain/core/ScholarChain.scala (domain-engineer) and modules/core/src/test/scala/scholarchain/core/CoreContractSuite.scala (test-engineer).

## Decision
ACCEPTED (ratified).

First review: REQUEST_CHANGES, with one blocking finding (B1: the Docker base-image tag was a literal outside the version ledger). Repair: dependency-updater added `temurinJreImage` to project/Versions.scala, and build-engineer referenced it from build.sbt. Second review: APPROVE WITH NOTES, with no blocking findings.

## Human decisions made during the phase
1. EMR target: emr-spark-8.1.0. The ledger is pinned to Spark 4.1.1, Hadoop 3.4.2, Scala 2.13.17 for the Spark interop (CrossVersion.for3Use2_13), and Java 17 bytecode (-release 17).
2. Scala 3.3.9 LTS was accepted instead of 3.10.1, because Scala 3.8+ ships a Scala 3 standard library that conflicts with the scala-library 2.13.17 that EMR's Spark provides. scala-library is pinned build-wide to 2.13.17.
3. markTestRun writes .claude/.last-test-run after all tests pass, as the course specification requires. This conflicts with CLAUDE.md §7; the conflict is recorded as a risk.
4. The smoke test asserts a trivial core constant (ScholarChain.Name), written by domain-engineer and tested by test-engineer, to keep one owner per artifact.

## Answers to the reviewer's human-decision items
- H1 (tests not run on JDK 17): accepted for Phase 02, which has only a string-constant test. Action taken: SCHOLARCHAIN_JAVA_HOME and JAVA_HOME now point at Temurin 17 in ~/.zshenv. The JDK will be verified with `sbt jdkInfo` in Phase 03, before any Spark or Hadoop code exists.
- H2 (empty module and top-level folders not created): accepted. Creating them would break the one-writer ownership rule; each owning agent creates its folders in its own phase.
- H3 (verification in the working tree, not a clean clone): accepted for now. A clean-checkout `sbt clean check` is required at the Phase 17 final gate.
- H4 (authorship of the two core files): confirmed from the agents' own reports. domain-engineer wrote ScholarChain.scala and test-engineer wrote CoreContractSuite.scala. No ownership deviation.
- H5 (N2 sbt-server environment documentation, N3 plugins.sbt regex hardening): deferred to a later build-engineer task.
- H6 (digest-pinning the base image): deferred to Phase 12, before any deployment. The digest recorded by dependency-updater is sha256:aa6381384f8db99762fa7d0d3c2b5de58874e5f12c96df4c13a1455376ae0ed1.

## CLAUDE.md §7 amendment
factory-engineer delivered a proposal only (factory-proposal-s7.md); no constitutional file was changed in this phase. Decision: ACCEPTED in principle. It will be applied, with code review, in a later combined factory session started with SCHOLARCHAIN_FACTORY_SESSION=1, followed by its six probes. Until then the ratified Phase 01 system (mtime-only Stop hook, §7 as written) stays in force.

## Probe evidence carried over from Phase 01
The test-stop hook (verify-tests-ran.sh) blocked the orchestrator from ending a turn four times in this phase, after project/Versions.scala and ScholarChain.scala changed. Each time it forced the canonical `sbt check; echo "exit=$?" > .claude/.last-test-run`. This completes the Phase 01 probe "modify source and attempt to stop without tests".

## Manual changes
None. All Phase 02 files are agent-generated. The human made the decisions listed above and edited ~/.zshenv (outside the repository).

## Disclosures
- The prompt is the course Phase 02 work order plus human-architect additions: the local toolchain, the EMR compatibility requirement, keeping the .gitignore entries, the ledger-ownership routing to dependency-updater, the minimal smoke test, and the orchestration of code review and report saving.
- The phase was executed by instructing the orchestrator to run the work order saved in prompt.md, rather than pasting it.

## Remaining risk
- Tests have not yet been observed on JDK 17 (to be verified in Phase 03).
- sbt dockerLocal was not run (the Docker daemon was down); the image was only staged, never built.
- Running Hadoop MapReduce on the emr-spark-8.x release train is unverified until Phase 12. The fallback is EMR 7.13.0 for the Hadoop stage.
- scala-library is forced to 2.13.17 under libraries built against 2.13.18 (N6).
- The Hadoop fat jar bundles scala-library (N7); the assembly merge strategy may drop META-INF entries (N8).
- No CVE review of the dependency ledger has been done yet.

## Tools
Claude Code 2.1.311 (orchestrator claude-opus-5-5; sonnet subagents; opus code-reviewer), Claude Pro login, sbt 2.0.12, Scala 3.3.9, macOS arm64. Default JDK during the phase: 27.0.1 (Homebrew); target JDK: Temurin 17.0.21.

## Accepted commit
See commit.txt.
