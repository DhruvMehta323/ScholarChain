package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

import software.amazon.awssdk.auth.credentials.{AwsCredentials, AwsCredentialsProvider}
import software.amazon.awssdk.regions.Region

import EmbeddingFx.*
import StorageFx.*

/** Profile gate for the Phase 08 path: with SCHOLARCHAIN_AWS_IT unset no Bedrock, SQS, S3 or S3
  * Vectors client is constructed and no credential is resolved. Extends AwsOffByDefaultSuite.
  */
class EmbeddingAwsOffSuite extends munit.FunSuite:

  private final class TrippingProvider extends AwsCredentialsProvider:
    val calls                                = AtomicInteger(0)
    def resolveCredentials(): AwsCredentials =
      calls.incrementAndGet()
      throw IllegalStateException("credentials must not be resolved in an offline test")

  private val embeddingSources = List(
    "EmbeddingQueue.scala",
    "EmbeddingWorker.scala",
    "EmbeddingLedger.scala",
    "VectorText.scala",
  )

  private def mainText(file: String): String =
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val p    = root.resolve(s"modules/storage/src/main/scala/scholarchain/storage/$file")
    assume(Files.isRegularFile(p), s"$file is not present")
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  test("the whole producer, worker, ledger and resolver path runs with no credential call") {
    val p   = TrippingProvider()
    val _   = AwsClients(p, Region.US_EAST_1) // exists, but nothing in the path uses it
    val rig = Rig()
    val s   = source("offline end to end text")
    rig.enqueue(runFor("a"), s)
    val r = rig.drain()
    assertEquals(r.counts.vectorsWritten, 1)
    val hit = rig.vectorsRaw
      .query(VectorIndexes.ResearcherProfile, VectorQuery(rig.embedder.vecOf(s.text), 1))
      .toOption
      .get
      .head
    assertEquals(VectorTextResolver(rig.objects).resolve(hit).map(_.text), Right(s.text))
    assertEquals(p.calls.get, 0)
  }

  test("the embedding source files build no SDK client and import no AWS SDK class") {
    embeddingSources.foreach { f =>
      val code = mainText(f).linesIterator
        .map(_.trim)
        .filterNot(l => l.startsWith("*") || l.startsWith("//") || l.startsWith("/**"))
        .toList
      assert(!code.exists(_.contains("software.amazon")), s"$f imports the AWS SDK")
      assert(
        !code.exists(l => """Client\s*\.builder\(\)""".r.findFirstIn(l).isDefined),
        s"$f builds a client",
      )
      assert(
        !code.exists(_.contains("System.getenv")) && !code.exists(_.contains("sys.env")),
        s"$f reads the environment",
      )
    }
  }

  test("storage has no Bedrock runtime on its classpath, so it cannot construct a Bedrock client") {
    val r = scala.util.Try(
      Class.forName("software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient")
    )
    assert(r.isFailure, "the storage module must not depend on the Bedrock SDK")
  }

  test("the storage build file lists no Bedrock module for storage") {
    val root  = repoRoot.getOrElse(fail("repository root not found"))
    val build = new String(Files.readAllBytes(root.resolve("build.sbt")), StandardCharsets.UTF_8)
    val i     = build.indexOf("lazy val storage")
    val j     = build.indexOf("lazy val rag")
    assert(i >= 0 && j > i, "storage and rag blocks not found in build.sbt")
    assert(
      !build.substring(i, j).contains("BedrockRuntime"),
      "storage must not depend on bedrockruntime",
    )
  }

  test(
    "the embedding live suite is named *AwsItSuite, ignores itself unless enabled, and builds clients only in its test body"
  ) {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val p    = root.resolve(
      "modules/storage/src/it/scala/scholarchain/storage/EmbeddingQueueAwsItSuite.scala"
    )
    assume(Files.isRegularFile(p), "EmbeddingQueueAwsItSuite.scala is not present")
    val text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    assert(text.contains("class EmbeddingQueueAwsItSuite"))
    assert(text.contains("munitIgnore") && text.contains("AwsItSupport.enabled"))
    val firstTest = text.indexOf("test(")
    assert(firstTest > 0, "no test found")
    val before = text.substring(0, firstTest)
    assert(!before.contains("AwsItSupport.clients()"), "a client is built at class construction")
    assert(!before.contains("SqsClient") && !before.contains("S3VectorsClient"))
  }

  test("the live embedding suite's drain and job bounds are at most 5") {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val p    = root.resolve(
      "modules/storage/src/it/scala/scholarchain/storage/EmbeddingQueueAwsItSuite.scala"
    )
    assume(Files.isRegularFile(p), "EmbeddingQueueAwsItSuite.scala is not present")
    val text   = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    val drains = """drain\((\d+)\)""".r.findAllMatchIn(text).map(_.group(1).toInt).toList
    assert(drains.nonEmpty && drains.forall(_ <= 5), s"drain bounds: $drains")
  }

  test(
    "with SCHOLARCHAIN_AWS_IT unset the live gate is closed and the suite filter excludes the embedding live suite"
  ) {
    assume(
      !sys.env.get("SCHOLARCHAIN_AWS_IT").contains("1"),
      "the live gate is open in this environment",
    )
    assertEquals(AwsItSupport.enabled, false)
    def runs(name: String, env: Map[String, String]): Boolean =
      !name.endsWith("AwsItSuite") || env.get("SCHOLARCHAIN_AWS_IT").contains("1")
    assert(!runs("scholarchain.storage.EmbeddingQueueAwsItSuite", Map.empty))
    assert(runs("scholarchain.storage.EmbeddingQueueAwsItSuite", Map("SCHOLARCHAIN_AWS_IT" -> "1")))
  }

  test("every file under src/it is still named *AwsItSuite or is the shared support object") {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val dir  = root.resolve("modules/storage/src/it/scala/scholarchain/storage")
    assume(Files.isDirectory(dir), "src/it is not present")
    val s     = Files.list(dir)
    val names =
      try s.iterator.asScala.toList.map(_.getFileName.toString).filter(_.endsWith(".scala"))
      finally s.close()
    assert(names.contains("EmbeddingQueueAwsItSuite.scala"))
    names.foreach(n => assert(n.endsWith("AwsItSuite.scala") || n == "AwsItSupport.scala", n))
  }
