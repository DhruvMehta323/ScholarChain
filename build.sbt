import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.time.Instant

// ScholarChain multi-module build (Scala 3).
//
// Environment input surface (the only environment variables this build reads):
//   SCHOLARCHAIN_JAVA_HOME  Optional. JDK home used to FORK test JVMs (for example a JDK 17).
//                           Unset: forked tests use the JVM that runs sbt.
// All versions come from project/Versions.scala (the ledger). No secrets, cloud endpoints or
// deployment steps are defined here.
//
// Module graph (an arrow means "depends on"; core depends on nothing; nothing depends on services):
//   hadoop, entityResolution, sparkAnalytics, storage, rag -> core
//   services -> rag, storage (and core transitively)

ThisBuild / scalaVersion := Versions.scala3
ThisBuild / organization := "io.scholarchain"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Scala 3.3.x LTS brings scala-library 2.13.18 transitively; pin to the stdlib that the EMR
// Spark runtime supplies so that tests and packaged jobs agree with the cluster.
ThisBuild / dependencyOverrides += Dependencies.scalaLibraryPin

// Bytecode and API level 17 whichever JDK runs sbt (EMR 8.1.0 default runtime is Java 17).
ThisBuild / scalacOptions ++= Seq("-release", "17", "-deprecation", "-feature", "-unchecked")
ThisBuild / javacOptions ++= Seq("--release", "17")

val markTestRun = taskKey[Unit]("Write .claude/.last-test-run after all tests have passed")
val jdkInfo     = taskKey[Unit]("Print the JDKs used by sbt and by forked tests")

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
  // Tests that need AWS or other live services are named *IntegrationSuite and never run here.
  Test / testOptions += Tests.Filter(name => !name.endsWith("IntegrationSuite")),
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
  .settings(name := "scholarchain-storage")

lazy val rag = (project in file("modules/rag"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "scholarchain-rag")

lazy val services = (project in file("modules/services"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(rag, storage)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-services",
    libraryDependencies += Dependencies.logback,
    // Container image: pinned Java runtime, non-root user, percentage-based memory limits.
    dockerBaseImage         := s"eclipse-temurin:${Versions.temurinJreImage}",
    dockerRepository        := None,
    dockerUpdateLatest      := false,
    Docker / packageName    := "scholarchain-services",
    Docker / daemonUser     := "scholarchain",
    Docker / daemonUserUid  := Some("10001"),
    Docker / daemonGroup    := "scholarchain",
    Docker / daemonGroupGid := Some("10001"),
    Universal / javaOptions ++= Seq(
      "-J-XX:MaxRAMPercentage=75.0",
      "-J-XX:InitialRAMPercentage=50.0",
      "-J-XX:+ExitOnOutOfMemoryError",
    ),
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
addCommandAlias("dockerLocal", "services/Docker/publishLocal")
