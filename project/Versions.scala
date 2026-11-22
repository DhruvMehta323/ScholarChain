// Dependency ledger for ScholarChain.
// Owned by dependency-updater. This file holds version values only:
// no logic, no dependency definitions, no ModuleIDs.
// build-engineer references these constants from build.sbt and project/Dependencies.scala.
// project/plugins.sbt also reads these constants textually (it cannot reference the object).

object Versions {
  // Languages and runtimes
  val scala3   = "3.3.9"
  val scala213 = "2.13.17"

  // Engines
  val spark  = "4.1.1"
  val hadoop = "3.4.2"

  // Libraries
  val upickle = "4.4.3"
  val munit   = "1.3.6"
  val logback = "1.5.38"
  val slf4j   = "2.0.17"

  val typesafeConfig = "1.4.10"

  // AWS SDK for Java v2 (one version for bom, s3, dynamodb, s3vectors, neptunedata, sqs, sts,
  // url-connection-client, apache-client). Source: https://repo1.maven.org/maven2/software/amazon/awssdk/bom/maven-metadata.xml
  // Checked 2026-11-21. See reports/dependency-updates/phase-07-aws-sdk-v2.md.
  val awsSdkV2 = "2.59.7"

  // Formatting
  val scalafmt = "3.11.5"

  // sbt plugins
  val sbtScalafmt       = "2.6.2"
  val sbtNativePackager = "1.13.0"
  val sbtAssembly       = "2.5.0"

  // Container images
  val temurinJreImage = "17.0.21_9-jre-jammy"
}
