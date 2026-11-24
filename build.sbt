import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.time.Instant

// ScholarChain multi-module build (Scala 3).
//
// Environment input surface (the only environment variables this build reads):
//   SCHOLARCHAIN_JAVA_HOME  Optional. JDK home used to FORK test JVMs (for example a JDK 17).
//                           Unset: forked tests use the JVM that runs sbt.
//   SCHOLARCHAIN_AWS_IT     Optional. Set to 1 to run live-AWS suites named *AwsItSuite.
//                           Unset or any other value: those suites are skipped, so `sbt check`
//                           never touches AWS.
//   SCHOLARCHAIN_GIT_SHA    Required ONLY by `services/servicesDockerBuild` (alias dockerLocal): the Git commit
//                           SHA (7 to 40 hex characters, optionally followed by "-dirty") used as the image tag. Unset or not hex: the
//                           image build fails; there is no "latest". `sbt check` never reads it.
//   SCHOLARCHAIN_ALLOW_MISSING_MAINS
//                           Optional. 1 lets the image build proceed while a service main class is not yet
//                           compiled (bootstrap only; such an image cannot start). Default: build fails.
// All versions come from project/Versions.scala (the ledger). No secrets, cloud endpoints or
// deployment steps are defined here.
//
// Module graph (an arrow means "depends on"; core depends on nothing; nothing depends on services):
//   hadoop, entityResolution, sparkAnalytics, storage, rag -> core
//   services -> rag, storage (and core transitively); rag does NOT depend on storage
//   rag holds the Bedrock runtime SDK (Titan embedder); services has Spark SQL in Test scope only

ThisBuild / scalaVersion := Versions.scala3
ThisBuild / organization := "io.scholarchain"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Scala 3.3.x LTS brings scala-library 2.13.18 transitively; pin to the stdlib that the EMR
// Spark runtime supplies so that tests and packaged jobs agree with the cluster.
ThisBuild / dependencyOverrides += Dependencies.scalaLibraryPin

// Bytecode and API level 17 whichever JDK runs sbt (EMR 8.1.0 default runtime is Java 17).
ThisBuild / scalacOptions ++= Seq("-release", "17", "-deprecation", "-feature", "-unchecked")
ThisBuild / javacOptions ++= Seq("--release", "17")

// Forked test groups run one at a time across ALL modules, so Spark, Hadoop and the other test
// JVMs never overlap (parallel module test JVMs exhausted memory and tripped timeouts).
// sbt already limits ForkedTestGroup to 1 by default, which serializes groups, not separate modules'
// testFull tasks; Tags.Test (carried by test, testFull and testOnly of every module) closes that gap.
Global / concurrentRestrictions ++= Seq(
  Tags.limit(Tags.ForkedTestGroup, 1),
  Tags.limit(Tags.Test, 1),
)

val markTestRun = taskKey[Unit]("Write .claude/.last-test-run after all tests have passed")
val jdkInfo     = taskKey[Unit]("Print the JDKs used by sbt and by forked tests")

// Service images: ONE sbt module (services), three independently deployable images.
val serviceImages = Seq(
  ServiceImage("query-api", "scholarchain.services.QueryApiMain", Some(8080)),
  ServiceImage("retrieval", "scholarchain.services.RetrievalMain", Some(8080)),
  ServiceImage("embedding-worker", "scholarchain.services.EmbeddingWorkerMain", None),
)
val servicesClasspathGuard =
  taskKey[Unit]("Fail if a Spark or Hadoop jar is on the services runtime classpath")
val servicesDockerStage =
  taskKey[File]("Render the Docker build context for the three service images")
val servicesDockerBuild =
  taskKey[Unit]("Build the three linux/amd64 service images tagged with SCHOLARCHAIN_GIT_SHA")

// Flags that Spark and Hadoop need to reflect into the JDK 17 module system.
val jdk17Opens = Seq(
  "java.base/java.lang",
  "java.base/java.lang.invoke",
  "java.base/java.lang.reflect",
  "java.base/java.io",
  "java.base/java.net",
  "java.base/java.nio",
  "java.base/java.util",
  "java.base/java.util.concurrent",
  "java.base/java.util.concurrent.atomic",
  "java.base/jdk.internal.ref",
  "java.base/sun.nio.ch",
  "java.base/sun.nio.cs",
  "java.base/sun.security.action",
  "java.base/sun.util.calendar",
).flatMap(m => Seq("--add-opens", s"$m=ALL-UNNAMED"))

lazy val commonSettings = Seq(
  testFrameworks += new TestFramework("munit.Framework"),
  Test / fork     := true,
  Test / javaHome := sys.env.get("SCHOLARCHAIN_JAVA_HOME").filter(_.nonEmpty).map(file),
  Test / javaOptions ++= jdk17Opens,
  // Explicit heap for every forked test JVM (Spark local[2] needs headroom; one JVM runs at a time).
  Test / javaOptions += "-Xmx2g",
  // Tests that need AWS or other live services are named *IntegrationSuite and never run here.
  Test / testOptions += Tests.Filter(name => !name.endsWith("IntegrationSuite")),
  // Live AWS suites (*AwsItSuite) run only when SCHOLARCHAIN_AWS_IT=1.
  Test / testOptions += Tests.Filter(name =>
    !name.endsWith("AwsItSuite") || sys.env.get("SCHOLARCHAIN_AWS_IT").contains("1")
  ),
  libraryDependencies += Dependencies.munit,
)

// Fat jar settings for the Hadoop and Spark job modules. Provided dependencies are excluded
// automatically by sbt-assembly.
def jobAssemblySettings(dropScalaLibrary: Boolean) = Seq(
  assembly / assemblyJarName       := s"${name.value}-${version.value}-assembly.jar",
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "services", _*) => MergeStrategy.concat
    case PathList("META-INF", _*)             => MergeStrategy.discard
    case "reference.conf"                     => MergeStrategy.concat
    case "module-info.class"                  => MergeStrategy.discard
    case _                                    => MergeStrategy.first
  },
  // The EMR Spark runtime supplies scala-library 2.13.17; Hadoop MapReduce does not.
  assembly / assemblyExcludedJars := {
    val cp   = (assembly / fullClasspath).value
    val conv = fileConverter.value
    if (dropScalaLibrary) {
      cp.filter(a => conv.toPath(a.data).getFileName.toString.startsWith("scala-library"))
    } else Seq.empty
  },
)

lazy val core = (project in file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "scholarchain-core",
    libraryDependencies ++= Seq(
      Dependencies.upickle,
      Dependencies.slf4jApi,
      Dependencies.typesafeConfig,
    ),
  )

lazy val hadoop = (project in file("modules/hadoop"))
  .dependsOn(core, entityResolution)
  .settings(commonSettings, jobAssemblySettings(dropScalaLibrary = false))
  .settings(
    name := "scholarchain-hadoop",
    libraryDependencies ++= Seq(Dependencies.hadoopClientApi, Dependencies.hadoopClientRuntime),
    // sbt 2 has no IntegrationTest configuration: src/it (hadoop-engineer) is compiled and run
    // with Test. Suites there MUST be named *ItSuite (for example WordCountLocalItSuite).
    // Never use the *IntegrationSuite suffix: commonSettings filters it out (live-service tests).
    Test / unmanagedSourceDirectories += baseDirectory.value / "src" / "it" / "scala",
    Test / unmanagedResourceDirectories += baseDirectory.value / "src" / "it" / "resources",
    // hadoop suites, including local MapReduce *ItSuites, must not run concurrently inside one test JVM; see Phase 08 repair 03
    Test / parallelExecution := false,
  )

lazy val entityResolution = (project in file("modules/entity-resolution"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "scholarchain-entity-resolution")

lazy val sparkAnalytics = (project in file("modules/spark"))
  .dependsOn(core)
  .settings(commonSettings, jobAssemblySettings(dropScalaLibrary = true))
  .settings(
    name := "scholarchain-spark",
    libraryDependencies ++= Seq(Dependencies.sparkSql, Dependencies.sparkGraphx),
    // Spark suites share one local SparkSession per forked JVM, so they must not run in parallel.
    Test / parallelExecution := false,
  )

lazy val storage = (project in file("modules/storage"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-storage",
    libraryDependencies ++= Seq(
      Dependencies.awsS3,
      Dependencies.awsDynamodb,
      Dependencies.awsS3vectors,
      Dependencies.awsNeptunedata,
      Dependencies.awsSqs,
      Dependencies.awsSts,
      Dependencies.awsUrlConnectionClient,
      // Reads Phase 06 Parquet output as files; no dependency on modules/spark code.
      Dependencies.sparkSql,
    ),
    // Spark suites share one local SparkSession per forked JVM, so they must not run in parallel.
    Test / parallelExecution := false,
    // src/it (storage-engineer) is compiled and run with Test. Suites there that touch AWS MUST be
    // named *AwsItSuite: commonSettings filters them out unless SCHOLARCHAIN_AWS_IT=1.
    Test / unmanagedSourceDirectories += baseDirectory.value / "src" / "it" / "scala",
    Test / unmanagedResourceDirectories += baseDirectory.value / "src" / "it" / "resources",
  )

lazy val rag = (project in file("modules/rag"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-rag",
    libraryDependencies ++= Seq(
      Dependencies.awsBedrockRuntime,
      Dependencies.awsUrlConnectionClient,
    ),
  )

lazy val services = (project in file("modules/services"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(rag, storage)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-services",
    // Spark SQL is Test scope only: the local-run test harness reads Phase 06 Parquet via storage.
    libraryDependencies ++= Seq(Dependencies.logback, Dependencies.sparkSqlTest),
    // Spark suites share one local SparkSession per forked JVM, so they must not run in parallel.
    Test / parallelExecution := false,
    // Three images from this one module; see packaging/README.md. The runtime classpath is guarded.
    servicesClasspathGuard := Def.uncached {
      val conv  = fileConverter.value
      val names = (Runtime / fullClasspath).value.map(a => conv.toPath(a.data).getFileName.toString)
      val bad   = names.filter(n => """(^|[-.])(spark|hadoop)-""".r.findFirstIn(n).isDefined)
      if (bad.nonEmpty)
        sys.error(s"Spark/Hadoop on services Runtime classpath (forbidden): ${bad.mkString(", ")}")
      streams.value.log.info(
        s"servicesClasspathGuard: OK, ${names.size} runtime classpath entries, none match spark-* or hadoop-*"
      )
    },
    servicesDockerStage := Def.uncached {
      servicesClasspathGuard.value
      (Universal / stage).value
      val log    = streams.value.log
      val root   = (ThisBuild / baseDirectory).value
      val libDir =
        (Universal / com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.stagingDirectory).value / "lib"
      val classDir = (Compile / classDirectory).value
      val ctx      = target.value / "docker-services"
      IO.delete(ctx)
      IO.createDirectory(ctx)
      IO.copyDirectory(libDir, ctx / "lib")
      // Runtime prompts and output schemas only (not prompts/README.md); read at /opt/scholarchain/prompts.
      Seq("runtime", "schemas").foreach { d =>
        IO.copyDirectory(root / "prompts" / d, ctx / "prompts" / d)
      }
      IO.copyFile(root / "packaging" / "entrypoint.sh", ctx / "entrypoint.sh")
      IO.copyFile(root / "packaging" / ".dockerignore", ctx / ".dockerignore")
      (ctx / "entrypoint.sh").setExecutable(true)
      val template = IO.read(root / "packaging" / "Dockerfile.template")
      serviceImages.foreach { svc =>
        val (expose, check) = svc.port match {
          case Some(p) =>
            (
              s"EXPOSE $p",
              s"HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 " +
                s"CMD curl --fail --silent --max-time 3 http://127.0.0.1:$p/healthz > /dev/null || exit 1",
            )
          case None =>
            (
              "# no port: this service accepts no inbound traffic",
              "HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 " +
                s"CMD grep -qa ${svc.mainClass.split('.').last} /proc/1/cmdline || exit 1",
            )
        }
        val rendered = template
          .replace("@BASE_IMAGE@", s"eclipse-temurin:${Versions.temurinJreImage}")
          .replace("@SERVICE@", svc.name)
          .replace("@SERVICE_MAIN@", svc.mainClass)
          .replace("@EXPOSE@", expose)
          .replace("@HEALTHCHECK@", check)
        IO.write(ctx / s"Dockerfile.${svc.name}", rendered)
        val classFile = classDir / (svc.mainClass.replace('.', '/') + ".class")
        if (!classFile.exists)
          log.warn(s"main class ${svc.mainClass} is not compiled yet (${svc.name})")
      }
      log.info(s"servicesDockerStage: context at ${ctx.getPath}")
      ctx
    },
    servicesDockerBuild := Def.uncached {
      val ctx      = servicesDockerStage.value
      val log      = streams.value.log
      val classDir = (Compile / classDirectory).value
      val sha      = sys.env.get("SCHOLARCHAIN_GIT_SHA").map(_.trim.toLowerCase).getOrElse("")
      if (!"""[0-9a-f]{7,40}(-dirty)?""".r.matches(sha))
        sys.error(
          "SCHOLARCHAIN_GIT_SHA must be set to the Git commit SHA (7 to 40 hex characters, optional -dirty suffix), " +
            "for example: SCHOLARCHAIN_GIT_SHA=$(git rev-parse HEAD) sbt dockerLocal (run `sbt shutdown` first: a running sbt server keeps its own environment)"
        )
      val missing =
        serviceImages.filterNot(s => (classDir / (s.mainClass.replace('.', '/') + ".class")).exists)
      if (missing.nonEmpty && !sys.env.get("SCHOLARCHAIN_ALLOW_MISSING_MAINS").contains("1"))
        sys.error(
          s"main class not compiled: ${missing.map(_.mainClass).mkString(", ")}. " +
            "Set SCHOLARCHAIN_ALLOW_MISSING_MAINS=1 only for a bootstrap build that cannot start."
        )
      serviceImages.foreach { svc =>
        val tag = s"scholarchain/${svc.name}:$sha"
        val cmd = Seq(
          "docker",
          "build",
          "--platform",
          "linux/amd64",
          "--label",
          s"org.opencontainers.image.revision=$sha",
          "--label",
          s"org.opencontainers.image.title=scholarchain-${svc.name}",
          "--tag",
          tag,
          "--file",
          (ctx / s"Dockerfile.${svc.name}").getPath,
          ctx.getPath,
        )
        log.info(s"building $tag")
        val code = scala.sys.process.Process(cmd).!
        if (code != 0) sys.error(s"docker build failed for $tag (exit $code)")
      }
    },
  )

lazy val root = (project in file("."))
  .aggregate(core, hadoop, entityResolution, sparkAnalytics, storage, rag, services)
  .settings(
    name           := "scholarchain",
    publish / skip := true,
    jdkInfo        := Def.uncached {
      val log = streams.value.log
      log.info(s"sbt JVM: java.version=${sys.props("java.version")} home=${sys.props("java.home")}")
      val forked  = sys.env.get("SCHOLARCHAIN_JAVA_HOME").filter(_.nonEmpty)
      val javaBin = forked.map(h => s"$h/bin/java").getOrElse(s"${sys.props("java.home")}/bin/java")
      val cmd     = Seq("sh", "-c", s"'$javaBin' -version 2>&1")
      val out     = scala.sys.process.Process(cmd).!!.linesIterator.mkString(" | ")
      log.info(s"forked test JVM (${forked.getOrElse("same as sbt")}): $out")
    },
    // Runs only after every module's tests passed. A failing test fails this task first,
    // and the check alias stops at the first failing step, so the marker is never written
    // on failure.
    markTestRun := Def.uncached {
      val _      = (Test / testFull).all(ScopeFilter(inAnyProject)).value
      val marker = (ThisBuild / baseDirectory).value / ".claude" / ".last-test-run"
      Files.createDirectories(marker.toPath.getParent)
      val line = s"exit=0 markTestRun ${Instant.now()}\n"
      Files.writeString(
        marker.toPath,
        line,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
      )
      streams.value.log.info(s"markTestRun: wrote ${marker.getPath}")
    },
  )

// fmt: format everything. check: the repository definition of done.
addCommandAlias("fmt", "scalafmtAll; scalafmtSbt")
addCommandAlias("contractTest", "Test/testOnly *ContractSuite")
addCommandAlias(
  "check",
  "scalafmtCheckAll; scalafmtSbtCheck; Test/compile; Test/testFull; contractTest; markTestRun",
)
addCommandAlias("packageJobs", "hadoop/assembly; sparkAnalytics/assembly")
addCommandAlias("dockerLocal", "services/servicesDockerBuild")
addCommandAlias("dockerStage", "services/servicesDockerStage")
addCommandAlias("servicesGuard", "services/servicesClasspathGuard")
