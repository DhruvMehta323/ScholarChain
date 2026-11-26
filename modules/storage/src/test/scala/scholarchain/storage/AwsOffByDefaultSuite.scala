package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import scholarchain.core.*

import software.amazon.awssdk.auth.credentials.{AwsCredentials, AwsCredentialsProvider}
import software.amazon.awssdk.regions.Region

import StorageFx.*

/** Profile gate: with SCHOLARCHAIN_AWS_IT unset no AWS suite runs and no AWS credential is
  * resolved. What can and cannot be observed from inside a test is stated in each test.
  */
class AwsOffByDefaultSuite extends munit.FunSuite:

  /** A provider that records every call and then fails. */
  private final class TrippingProvider extends AwsCredentialsProvider:
    val calls                                = AtomicInteger(0)
    def resolveCredentials(): AwsCredentials =
      calls.incrementAndGet()
      throw IllegalStateException("credentials must not be resolved in an offline test")

  private def awsItSet: Boolean = sys.env.get("SCHOLARCHAIN_AWS_IT").contains("1")

  private def buildSbt: String =
    val root = repoRoot.getOrElse(fail("repository root (build.sbt) not found"))
    new String(Files.readAllBytes(root.resolve("build.sbt")), StandardCharsets.UTF_8)

  // ---- the suite filter -----------------------------------------------------------------------

  test(
    "the environment of this run does not enable live AWS suites (SCHOLARCHAIN_AWS_IT is not 1)"
  ) {
    assume(
      !awsItSet,
      "SCHOLARCHAIN_AWS_IT=1 is set in this environment: the offline gate does not apply",
    )
    assertEquals(sys.env.get("SCHOLARCHAIN_AWS_IT").contains("1"), false)
    assertEquals(AwsItSupport.enabled, false)
  }

  test("build.sbt filters every *AwsItSuite unless SCHOLARCHAIN_AWS_IT is exactly 1") {
    val b = buildSbt
    assert(b.contains("""name.endsWith("AwsItSuite")"""), "no AwsItSuite filter in build.sbt")
    assert(
      b.contains("""sys.env.get("SCHOLARCHAIN_AWS_IT").contains("1")"""),
      "filter is not tied to =1",
    )
    assert(b.contains("Tests.Filter"), "the filter must be a test filter")
    // the filter sits in commonSettings, so the storage module inherits it
    val common = b.indexOf("val commonSettings")
    assert(common >= 0, "commonSettings definition not found")
    assert(
      b.indexOf("""name.endsWith("AwsItSuite")""") > common,
      "filter is not inside commonSettings",
    )
  }

  test(
    "the filter predicate excludes every *AwsItSuite when unset, and only those (re-evaluated copy of the build predicate)"
  ) {
    // NOTE: this re-evaluates the SAME expression text as build.sbt on an explicit env, because the
    // sbt Test/testOptions setting itself cannot be read from inside a running test.
    def runs(name: String, env: Map[String, String]): Boolean =
      !name.endsWith("AwsItSuite") || env.get("SCHOLARCHAIN_AWS_IT").contains("1")
    val live = List(
      "S3ObjectStoreAwsItSuite",
      "DynamoConditionalWriteAwsItSuite",
      "S3VectorsAwsItSuite",
      "NeptuneReadAwsItSuite",
    )
    live.foreach { n =>
      assert(!runs(s"scholarchain.storage.$n", Map.empty), n)
      assert(!runs(n, Map("SCHOLARCHAIN_AWS_IT" -> "0")), n)
      assert(!runs(n, Map("SCHOLARCHAIN_AWS_IT" -> "true")), n)
      assert(!runs(n, Map("SCHOLARCHAIN_AWS_IT" -> "")), n)
      assert(runs(n, Map("SCHOLARCHAIN_AWS_IT" -> "1")), n)
    }
    assert(runs("scholarchain.storage.VectorStoreSuite", Map.empty))
  }

  test(
    "every live-suite source under src/it is named *AwsItSuite and ignores itself unless enabled"
  ) {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val dir  = root.resolve("modules/storage/src/it/scala/scholarchain/storage")
    assume(Files.isDirectory(dir), "modules/storage/src/it is not present")
    val s       = Files.list(dir)
    val sources =
      try s.iterator.asScala.toList.filter(_.toString.endsWith(".scala"))
      finally s.close()
    val suites = sources.filter(p =>
      new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("extends munit")
    )
    assert(suites.nonEmpty)
    suites.foreach { p =>
      val name = p.getFileName.toString
      assert(name.endsWith("AwsItSuite.scala"), s"$name must be named *AwsItSuite")
      val text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
      assert(
        text.contains("munitIgnore") && text.contains("AwsItSupport.enabled"),
        s"$name does not ignore itself",
      )
    }
  }

  // ---- no client, no credential call ----------------------------------------------------------

  test("constructing AwsClients builds no client and never calls the credentials provider") {
    val p = TrippingProvider()
    val c = AwsClients(p, Region.US_EAST_1)
    assertEquals(p.calls.get, 0)
    assert(
      c.graphStore(StorageConfigLoader.load().fold(e => fail(e.toString), _.config)).isLeft
    ) // no endpoint
    assertEquals(p.calls.get, 0)
    assertEquals(c.neptuneData.left.map(_.code), Left("invalid-request"))
    assertEquals(p.calls.get, 0)
  }

  test(
    "fakes, repositories, loaders and the template graph store work without any AWS client or credential"
  ) {
    val p   = TrippingProvider()
    val _   = AwsClients(p, Region.US_EAST_1) // exists, but nothing uses it
    val dir = newTempDir("sc-off")
    try
      val objects = FileObjectStore(dir)
      val kv = InMemoryKeyValueStore(Map("t" -> KvTableSchema(true), "s" -> KvTableSchema(false)))
      assert(RunLedgerRepository(kv, "t").record(ledger()).isRight)
      assert(QuerySessionRepository(kv, "s", () => createdEpoch).record(session()).isRight)
      assert(objects.put(ObjectLocation(bucket, "a/b"), Array[Byte](1)).isRight)
      val vs  = InMemoryVectorStore(Map(VectorIndexes.ResearcherProfile -> 3))
      val rep = VectorLoader(vs, StorageRetry(2, 1L, 2L), (_: Long) => (), InMemoryRejectionSink())
        .load(VectorIndexes.ResearcherProfile, List(entry("k", "t")))
      assert(rep.isRight)
      val q = InMemoryWorkQueue()
      assert(q.send("q", "body").isRight)
      assertEquals(q.receive("q", 1, 30).map(_.map(_.body)), Right(List("body")))
      val g = TemplateGraphStore(
        QueryBounds(6, 100, 5000L),
        StorageConfigLoader.load().fold(e => fail(e.toString), _.config.neptune),
        "us-east-1",
        RecordingCypherExecutor(),
        FakeBulkLoader(),
      )
      assert(g.query(GraphQuery(GraphTemplates.Neighbors, Map.empty, QueryBounds(1, 1, 1L))).isLeft)
      assert(NeptuneFx.fixture.build.isRight)
      assert(StorageConfigLoader.load().isRight)
    finally deleteTree(dir)
    assertEquals(p.calls.get, 0)
  }

  // ---- hermetic configuration, checked without building a client ------------------------------
  // NOTE (repair 01, M5): no SDK client is built and no request is signed by this suite. What the
  // builders do with the configuration below is therefore NOT observed here (see the report).

  test("AwsClients.hermetic: fixed profile name scholarchain-hermetic and an empty profile file") {
    assertEquals(AwsClients.HermeticProfileName, "scholarchain-hermetic")
    val cfg = AwsClients.hermetic // a configuration object only: no client, no network, no file
    assertEquals(cfg.defaultProfileName().toScala, Some("scholarchain-hermetic"))
    val file = cfg.defaultProfileFile().toScala.getOrElse(fail("no default profile file is set"))
    assertEquals(file.profiles().size, 0, "the supplied profile file must be empty")
    assertEquals(file.profile("scholarchain-hermetic").isPresent, false)
    assertEquals(file.profile("default").isPresent, false)
    assertEquals(AwsClients.emptyProfileFile.profiles().size, 0)
  }

  test("AwsClients.hermetic: two calls give independent, equal configurations") {
    val a = AwsClients.hermetic
    val b = AwsClients.hermetic
    assertEquals(a.defaultProfileName().toScala, b.defaultProfileName().toScala)
    assertEquals(b.defaultProfileFile().toScala.map(_.profiles().size), Some(0))
  }

  test(
    "AwsClients.scala applies the hermetic configuration to every client builder (text check, no client built)"
  ) {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val src  = root.resolve("modules/storage/src/main/scala/scholarchain/storage/AwsClients.scala")
    assume(Files.isRegularFile(src), "AwsClients.scala is not present")
    val text     = new String(Files.readAllBytes(src), StandardCharsets.UTF_8)
    val builders =
      """(S3|DynamoDb|S3Vectors|Sqs|Neptunedata)Client\s*\.builder\(\)""".r.findAllIn(text).size
    // five hermetic clients plus the dedicated no-retry Neptune bulk-load start client
    assertEquals(builders, 6, "expected the six client builders")
    assertEquals(
      "overrideConfiguration\\(hermetic\\)".r.findAllIn(text).size,
      5,
      "every client builder except the loader-start client must use overrideConfiguration(hermetic)",
    )
    assertEquals(
      "overrideConfiguration\\(AwsClients\\.loaderStartOverride\\)".r.findAllIn(text).size,
      1,
      "the loader-start client must use AwsClients.loaderStartOverride (hermetic plus no retry)",
    )
    assertEquals("credentialsProvider\\(credentials\\)".r.findAllIn(text).size, builders)
    assertEquals("\\.region\\(region\\)".r.findAllIn(text).size, builders)
  }

  test("no other main source builds an SDK client (AwsClients is the only place)") {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val dir  = root.resolve("modules/storage/src/main")
    assume(Files.isDirectory(dir), "modules/storage/src/main is not present")
    val s     = Files.walk(dir)
    val files =
      try s.iterator.asScala.toList.filter(p => p.toString.endsWith(".scala"))
      finally s.close()
    val offenders = files
      .filterNot(_.getFileName.toString == "AwsClients.scala")
      .filter { p =>
        val t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
        """Client\s*\.builder\(\)""".r.findFirstIn(t).isDefined
      }
      .map(_.getFileName.toString)
    assertEquals(offenders, Nil)
  }

  test(
    "main sources never create a default credentials chain or read AWS environment, profiles or metadata"
  ) {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val dir  = root.resolve("modules/storage/src/main")
    assume(Files.isDirectory(dir), "modules/storage/src/main is not present")
    val s     = Files.walk(dir)
    val files =
      try s.iterator.asScala.toList.filter(_.toString.endsWith(".scala"))
      finally s.close()
    assert(files.nonEmpty)
    val banned = List(
      "DefaultCredentialsProvider",
      "ProfileCredentialsProvider",
      "InstanceProfileCredentialsProvider",
      "EnvironmentVariableCredentialsProvider",
      "StaticCredentialsProvider",
      "AwsBasicCredentials",
      "System.getenv",
      "sys.env",
    )
    val offenders = for
      f    <- files
      line <- Files.readAllLines(f).asScala.toList
      code = line.trim
      if !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/**")
      b <- banned if code.contains(b)
    yield s"${f.getFileName}: $b"
    assertEquals(offenders, Nil)
  }
