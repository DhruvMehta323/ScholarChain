Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch.

Required projects:
- core
- hadoop
- entityResolution
- sparkAnalytics
- storage
- rag
- services

Map them to the module directories in docs/agents.md (modules/core, modules/hadoop, modules/entity-resolution, modules/spark, modules/storage, modules/rag, modules/services).

Required principles:
- Scala 3 everywhere.
- Use Hadoop and Spark Java or DataFrame APIs where that avoids unsupported Scala binary assumptions.
- Pin every dependency and plugin version in a single version ledger.
- No floating or latest versions.
- Select one JSON library for the entire Scala codebase. Prefer uPickle unless a documented compatibility problem requires another choice.
- Select ScalaTest or MUnit and use it consistently.
- Keep Hadoop and Spark dependencies Provided when the runtime supplies them.
- Keep test-only libraries in Test scope.
- Keep logging implementation in Runtime scope.
- Use scalafmt with the Scala 3 dialect.
- Create a markTestRun task that updates .claude/.last-test-run only after tests pass.
- Define aliases: fmt, check, packageJobs, and dockerLocal.
- check must run format checking, compilation, tests, contract tests that do not need AWS, and markTestRun.
- Container images must use a pinned Java runtime, a non-root user, and percentage-based memory limits.
- Build behavior may depend only on explicitly documented environment variables.
- No secrets, cloud endpoints, or deployment procedures in the build definition.

Create:
- build.sbt
- project/build.properties
- project/plugins.sbt
- .scalafmt.conf
- .gitignore
- local compose file for emulators or test dependencies only when justified
- empty module source roots
- empty required top-level directories from the approved ownership map

Verify on a clean repository state with sbt Test/compile and then sbt check once the marker test exists.
Report the complete version ledger, module dependency graph, environment input surface, packaging choices, and any compatibility risk involving Scala 3, Hadoop, or Spark.

ScholarChain-specific constraints (added by the human architect):
- Local toolchain: macOS arm64, JDK 17 (Temurin 17.0.21) as the default java, sbt runner 2.0.12, locally installed Apache Spark 4.3.0 and Hadoop 3.5.0. Choose Scala, Spark, and Hadoop library versions that resolve from Maven Central, run on JDK 17, and are compatible with the Spark and Hadoop runtimes that Amazon EMR provides. Record the reasoning and the Scala 3 to Scala 2.13 interop decision for Spark (for example CrossVersion.for3Use2_13) in the report.
- .gitignore: keep every existing entry (datasets/raw/, datasets/processed/, .env, .env.*, !.env.example, *.pem, *.key, target/, **/target/, .metals/, .bloop/, .DS_Store) and add .claude/.last-test-run, .bsp/, .idea/, .vscode/ build caches, and Terraform state and plan files.
- Version ledger ownership: docs/agents.md section 1.1 assigns version values to dependency-updater. If build-engineer may not create the ledger file, return BLOCKED-ON and the orchestrator will dispatch dependency-updater in a separate work order to create only the initial version values, after which build-engineer completes the build wiring.
- The empty module source roots must still let sbt check pass. Add one minimal, meaningful test (for example, that the build's module list or a trivial core value is as expected) so that markTestRun has a passing test run to mark.
- Do not create application logic, Terraform, or deployment scripts.

After build-engineer reports COMPLETE with observed sbt Test/compile and sbt check output, dispatch code-reviewer for a read-only review of the full Phase 02 diff. Route any REQUEST_CHANGES findings to build-engineer (or dependency-updater for version values), then re-review. Stop for human ratification when code-reviewer returns APPROVE or APPROVE WITH NOTES. Save code-reviewer's final verdict verbatim as docs/agent-runs/phase-02-build-skeleton/review-report.md and build-engineer's final report verbatim as docs/agent-runs/phase-02-build-skeleton/agent-report.md.
