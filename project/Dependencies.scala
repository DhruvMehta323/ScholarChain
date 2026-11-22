import sbt.*

/** Dependency definitions. Every version is a reference to `Versions.*` (the ledger). */
object Dependencies {
  // Spark is a Scala 2.13 library. Scala 3 consumes it through for3Use2_13 and the
  // DataFrame/Row API only (no Scala 2 implicit encoder derivation).
  private val sparkOrg = "org.apache.spark"

  // JSON: uPickle is the single JSON library for the whole codebase.
  val upickle = "com.lihaoyi" %% "upickle" % Versions.upickle

  // Configuration: HOCON parser (plain Java artifact, no Scala suffix, no transitive deps).
  val typesafeConfig = "com.typesafe" % "config" % Versions.typesafeConfig

  // Logging: API at compile time, implementation at runtime only.
  val slf4jApi = "org.slf4j"      % "slf4j-api"       % Versions.slf4j
  val logback  = "ch.qos.logback" % "logback-classic" % Versions.logback % Runtime

  // Test only: MUnit is the single test framework.
  val munit = "org.scalameta" %% "munit" % Versions.munit % Test

  // Hadoop client APIs, supplied by the EMR runtime.
  val hadoopClientApi     = "org.apache.hadoop" % "hadoop-client-api" % Versions.hadoop % Provided
  val hadoopClientRuntime =
    "org.apache.hadoop" % "hadoop-client-runtime" % Versions.hadoop % Provided

  // Spark SQL, supplied by the EMR runtime.
  val sparkSql =
    (sparkOrg % "spark-sql" % Versions.spark % Provided).cross(CrossVersion.for3Use2_13)
  val sparkGraphx =
    (sparkOrg % "spark-graphx" % Versions.spark % Provided).cross(CrossVersion.for3Use2_13)

  // AWS SDK for Java v2 (storage adapters). All modules are aligned on Versions.awsSdkV2.
  // The default Apache and Netty transports are excluded; exactly one HTTP client is pinned
  // (url-connection-client, resolved by SDK service-client builders through the classpath).
  private val awsOrg                      = "software.amazon.awssdk"
  private def awsModule(artifact: String) =
    (awsOrg % artifact % Versions.awsSdkV2)
      .exclude(awsOrg, "apache-client")
      .exclude(awsOrg, "netty-nio-client")
  val awsS3                  = awsModule("s3")
  val awsDynamodb            = awsModule("dynamodb")
  val awsS3vectors           = awsModule("s3vectors")
  val awsNeptunedata         = awsModule("neptunedata")
  val awsSqs                 = awsModule("sqs")
  val awsSts                 = awsModule("sts") // live profile identity check only
  val awsUrlConnectionClient = awsOrg % "url-connection-client" % Versions.awsSdkV2

  // Keeps the stdlib on the version the EMR Spark runtime supplies.
  val scalaLibraryPin = "org.scala-lang" % "scala-library" % Versions.scala213
}
