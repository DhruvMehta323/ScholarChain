package scholarchain.services

import java.net.URI
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.ListBuffer

import software.amazon.awssdk.auth.credentials.{AwsCredentials, AwsCredentialsProvider}

import scholarchain.core.*
import scholarchain.rag.runtime.*

import ApiHarness.*

/** Phase 11 T: the P1 fixes (audit location, prompts and config readiness, AWS dry start). Dummy
  * values only. The credentials provider is a tripwire: it records and throws if anything asks for
  * credentials, so a pass proves that composition never tried to sign a request.
  */
class P1FixesSuite extends munit.FunSuite:

  // ---- helpers ---------------------------------------------------------------------------------

  private val repoRoot: Path = LocalFixturePaths.repoRoot

  final class Tripwire extends AwsCredentialsProvider:
    val calls                                = new AtomicInteger(0)
    def resolveCredentials(): AwsCredentials =
      calls.incrementAndGet()
      throw new IllegalStateException("tripwire: credentials were requested")

  private val loopback = URI.create("http://127.0.0.1:1")

  private val awsEnv: Map[String, String] = Map(
    "SCHOLARCHAIN_ENVIRONMENT"                  -> "aws",
    "SCHOLARCHAIN_AWS_REGION"                   -> "us-east-1",
    "SCHOLARCHAIN_STORAGE_CURATED_BUCKET"       -> "dummy-curated-bucket",
    "SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT"     -> "http://127.0.0.1:1",
    "SCHOLARCHAIN_SERVICES_RETRIEVAL_URL"       -> "http://127.0.0.1:1",
    "SCHOLARCHAIN_SERVICES_API_KEY"             -> "dummy-public-key-for-tests-0001",
    "SCHOLARCHAIN_INTERNAL_API_KEY"             -> "dummy-internal-key-for-tests-0002",
    "SCHOLARCHAIN_ANTHROPIC_API_KEY"            -> "dummy-anthropic-key-not-real",
    "SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL" -> "http://127.0.0.1:1/000000000000/dummy-queue",
    "SCHOLARCHAIN_SERVICES_EMBEDDING_DLQ_URL"   -> "http://127.0.0.1:1/000000000000/dummy-dlq",
    "SCHOLARCHAIN_VOYAGE_API_KEY"               -> "dummy-voyage-key-not-real",
  )

  private def seams(t: Tripwire) = AwsSeams(Some(t), Some(loopback))

  private def settingsOf(env: Map[String, String]): ServiceSettings =
    ServiceSettings.fromEnv(env).fold(m => throw new IllegalStateException(m), identity)

  private def copyDir(from: Path, to: Path): Unit =
    val s = Files.walk(from)
    try
      s.forEach { p =>
        val t = to.resolve(from.relativize(p).toString)
        if Files.isDirectory(p) then Files.createDirectories(t)
        else Files.copy(p, t)
      }
    finally s.close()

  private def promptsCopy(): Path =
    val tmp = Files.createTempDirectory("sc-prompts-copy")
    copyDir(repoRoot.resolve("prompts"), tmp.resolve("prompts"))
    tmp

  // ---- 1. audit location -----------------------------------------------------------------------

  test("auditLocation joins the curated bucket with the rag prefix and /audit") {
    assertEquals(
      Composition.auditLocation("my-curated", "rag/"),
      Right(("my-curated", "rag/audit")),
    )
  }

  test("auditLocation accepts a prefix without a trailing slash") {
    assertEquals(
      Composition.auditLocation("my-curated", "rag"),
      Right(("my-curated", "rag/audit")),
    )
  }

  test("auditLocation refuses an empty, slashed or s3: bucket") {
    List("", "   ", "a/b", "s3://b", "S3:b", "bucket/").foreach { b =>
      assert(Composition.auditLocation(b, "rag/").isLeft, s"bucket '$b'")
    }
  }

  test("auditLocation refuses an empty, absolute, s3: or parent-escaping prefix") {
    List("", "  ", "/rag/", "s3://x/rag/", "S3:rag/", "../rag/", "rag/../x/", "a/..").foreach { p =>
      assert(Composition.auditLocation("my-curated", p).isLeft, s"prefix '$p'")
    }
  }

  test("auditLocation never yields the rag prefix as the bucket") {
    val (b, p) = Composition.auditLocation("my-curated", "rag/").toOption.get
    assertNotEquals(b, "rag")
    assert(p.startsWith("rag/"), p)
  }

  test("an audit document written through the located tables lands in the curated bucket") {
    val (b, p) = Composition.auditLocation("dummy-curated-bucket", "rag/").toOption.get
    val puts   = ListBuffer.empty[ObjectLocation]
    val spy    = new ObjectStore:
      def put(l: ObjectLocation, by: Array[Byte], c: Option[String], h: Option[String]) =
        base.objects.put(l, by, c, h)
      def putIfAbsent(l: ObjectLocation, by: Array[Byte], c: Option[String], h: Option[String]) =
        puts.synchronized { puts += l }
        base.objects.putIfAbsent(l, by, c, h)
      def get(l: ObjectLocation)  = base.objects.get(l)
      def head(l: ObjectLocation) = base.objects.head(l)
      def list(bucket: String, prefix: String, limit: Int, token: Option[String]) =
        base.objects.list(bucket, prefix, limit, token)
    val rt = new ServiceRuntime(
      base.settings,
      base.tables.copy(auditBucket = b, auditPrefix = p),
      base.kv,
      base.graph,
      base.vectors,
      spy,
      base.embedder,
      base.model,
      base.mode,
    )
    val server = new Server(rt)
    try
      val r = server.post("/v1/query", query(Q1))
      assertEquals(r.status, 200, r.body)
    finally server.stop()
    assert(puts.nonEmpty, "no audit object was written")
    puts.foreach { l =>
      assertEquals(l.bucket, "dummy-curated-bucket")
      assert(l.objectKey.startsWith("rag/audit/"), l.objectKey)
    }
  }

  test("awsQueryApi without the curated bucket is a Left naming the variable") {
    val env = awsEnv - "SCHOLARCHAIN_STORAGE_CURATED_BUCKET"
    val r   = Composition.awsQueryApi(settingsOf(env), env, seams(new Tripwire))
    assert(r.left.exists(_.contains("SCHOLARCHAIN_STORAGE_CURATED_BUCKET")), r.toString)
  }

  test("awsRetrieval without the curated bucket is a Left naming the variable") {
    val env = awsEnv - "SCHOLARCHAIN_STORAGE_CURATED_BUCKET"
    val r   = Composition.awsRetrieval(settingsOf(env), env, seams(new Tripwire))
    assert(r.left.exists(_.contains("SCHOLARCHAIN_STORAGE_CURATED_BUCKET")), r.toString)
  }

  test("awsQueryApi with a slashed curated bucket is refused") {
    val env = awsEnv.updated("SCHOLARCHAIN_STORAGE_CURATED_BUCKET", "rag/audit")
    assert(Composition.awsQueryApi(settingsOf(env), env, seams(new Tripwire)).isLeft)
  }

  test("awsQueryApi tables: audit bucket is the curated bucket, prefix is rag/audit") {
    val t  = new Tripwire
    val rt =
      Composition.awsQueryApi(settingsOf(awsEnv), awsEnv, seams(t)).fold(m => fail(m), identity)
    assertEquals(rt.tables.auditBucket, "dummy-curated-bucket")
    assertEquals(rt.tables.auditPrefix, "rag/audit")
    assertNotEquals(rt.tables.auditBucket, "rag")
    assertEquals(t.calls.get, 0)
  }

  test("awsRetrieval tables: audit bucket is the curated bucket, prefix is rag/audit") {
    val t  = new Tripwire
    val rt =
      Composition.awsRetrieval(settingsOf(awsEnv), awsEnv, seams(t)).fold(m => fail(m), identity)
    assertEquals(rt.tables.auditBucket, "dummy-curated-bucket")
    assertEquals(rt.tables.auditPrefix, "rag/audit")
    assertEquals(t.calls.get, 0)
  }

  // ---- 2. readiness ----------------------------------------------------------------------------

  test("promptsCheck is Right for the repository prompts") {
    val src = () => PromptRepository.locate(repoRoot, None)
    assertEquals(Readiness.promptsCheck(src), Right(()))
  }

  test("PromptRepository.locate on a directory without prompts is Left") {
    val tmp = Files.createTempDirectory("sc-no-prompts")
    assert(PromptRepository.locate(start = tmp, explicit = None).isLeft)
  }

  test("promptsCheck reports prompts_dir_missing when no prompts folder exists") {
    val tmp = Files.createTempDirectory("sc-no-prompts")
    assertEquals(
      Readiness.promptsCheck(() => PromptRepository.locate(tmp, None)),
      Left("prompts_dir_missing"),
    )
  }

  test("promptsCheck reports prompt_version_missing when a required prompt file is deleted") {
    val tmp = promptsCopy()
    Files.delete(tmp.resolve("prompts/runtime/answer-synthesis-v1.txt"))
    assertEquals(
      Readiness.promptsCheck(() => PromptRepository.locate(tmp, None)),
      Left("prompt_version_missing"),
    )
  }

  test("promptsCheck reports prompt_invalid when a required schema is not valid JSON") {
    val tmp = promptsCopy()
    Files.writeString(
      tmp.resolve("prompts/schemas/answer-synthesis-v1.schema.json"),
      "{ this is not json",
    )
    assertEquals(
      Readiness.promptsCheck(() => PromptRepository.locate(tmp, None)),
      Left("prompt_invalid"),
    )
  }

  test("promptsCheck honours a custom required list (unknown version is missing)") {
    val src = () => PromptRepository.locate(repoRoot, None)
    assertEquals(
      Readiness.promptsCheck(src, List("answer-synthesis" -> "v999")),
      Left("prompt_version_missing"),
    )
  }

  private def runtimeWith(
      prompts: () => Either[RuntimeError, PromptRepository],
      missing: () => List[String] = () => Nil,
  ) = new ServiceRuntime(
    base.settings,
    base.tables,
    base.kv,
    base.graph,
    base.vectors,
    base.objects,
    base.embedder,
    base.model,
    "aws",
    Set("prompts", "config"),
    prompts,
    missing,
  )

  test("/readyz is 503 with prompts Down and code prompt_version_missing, without a path") {
    val tmp = promptsCopy()
    Files.delete(tmp.resolve("prompts/runtime/answer-synthesis-v1.txt"))
    val rt   = runtimeWith(() => PromptRepository.locate(tmp, None))
    val resp = Readiness.respond("query-api", rt).fold(f => fail(f.toString), identity)
    assertEquals(resp.status, 503)
    assert(!resp.body.contains(tmp.toString), resp.body)
    assert(!resp.body.contains("answer-synthesis-v1.txt"), resp.body)
    assert(resp.body.contains("prompts"), resp.body)
    assert(resp.body.contains("prompt_version_missing"), resp.body)
  }

  test("/readyz is 503 with prompts_dir_missing when the folder is absent") {
    val tmp  = Files.createTempDirectory("sc-no-prompts")
    val rt   = runtimeWith(() => PromptRepository.locate(tmp, None))
    val resp = Readiness.respond("query-api", rt).fold(f => fail(f.toString), identity)
    assertEquals(resp.status, 503)
    assert(resp.body.contains("prompts_dir_missing"), resp.body)
    assert(!resp.body.contains(tmp.toString), resp.body)
  }

  test("/readyz is 200 when prompts are present and config is complete") {
    val rt   = runtimeWith(() => PromptRepository.locate(repoRoot, None))
    val resp = Readiness.respond("query-api", rt).fold(f => fail(f.toString), identity)
    assertEquals(resp.status, 200, resp.body)
  }

  test("/readyz is 503 config_missing when a required value is missing") {
    val rt = runtimeWith(
      () => PromptRepository.locate(repoRoot, None),
      () => List("SCHOLARCHAIN_INTERNAL_API_KEY"),
    )
    val resp = Readiness.respond("query-api", rt).fold(f => fail(f.toString), identity)
    assertEquals(resp.status, 503)
    assert(resp.body.contains("config_missing"), resp.body)
  }

  test("Composition.missingConfig returns names only, never values") {
    val env   = awsEnv - "SCHOLARCHAIN_STORAGE_CURATED_BUCKET" - "SCHOLARCHAIN_INTERNAL_API_KEY"
    val names = Composition.missingConfig("query-api", settingsOf(env), env)
    assert(names.contains("SCHOLARCHAIN_STORAGE_CURATED_BUCKET"), names.toString)
    assert(names.contains("SCHOLARCHAIN_INTERNAL_API_KEY"), names.toString)
    names.foreach(n => assert(n.matches("[A-Z0-9_]+"), n))
  }

  test(
    "Composition.missingConfig for retrieval needs the Neptune endpoint, not the retrieval URL"
  ) {
    val env =
      awsEnv - "SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT" - "SCHOLARCHAIN_SERVICES_RETRIEVAL_URL"
    val names = Composition.missingConfig("retrieval", settingsOf(env), env)
    assertEquals(names, List("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT"))
  }

  test("Composition.missingConfig is empty for a complete environment") {
    assertEquals(Composition.missingConfig("query-api", settingsOf(awsEnv), awsEnv), Nil)
    assertEquals(Composition.missingConfig("retrieval", settingsOf(awsEnv), awsEnv), Nil)
  }

  test("Composition.missingConfig flags a malformed curated bucket by name only") {
    val env   = awsEnv.updated("SCHOLARCHAIN_STORAGE_CURATED_BUCKET", "s3://x/y")
    val names = Composition.missingConfig("query-api", settingsOf(env), env)
    assert(names.contains("SCHOLARCHAIN_STORAGE_CURATED_BUCKET"))
    assert(!names.exists(_.contains("s3://")))
  }

  // ---- 3. dry start ----------------------------------------------------------------------------

  test("QueryApiMain.assemble in aws mode is Right and never asks for credentials") {
    val t = new Tripwire
    val r = QueryApiMain.assemble(awsEnv, seams(t), "127.0.0.1")
    assert(r.isRight, r.toString)
    assertEquals(t.calls.get, 0)
    assertEquals(r.toOption.get.runtime.mode, "aws")
  }

  test("RetrievalMain.assemble in aws mode is Right and never asks for credentials") {
    val t = new Tripwire
    val r = RetrievalMain.assemble(awsEnv, seams(t), "127.0.0.1")
    assert(r.isRight, r.toString)
    assertEquals(t.calls.get, 0)
  }

  test("EmbeddingWorkerMain.assemble in aws mode is Right and never asks for credentials") {
    val t = new Tripwire
    val r = EmbeddingWorkerMain.assemble(awsEnv, seams(t))
    assert(r.isRight, r.toString)
    assertEquals(t.calls.get, 0)
  }

  test("EmbeddingWorkerMain.assemble with the Bedrock embedder never asks for credentials") {
    val t   = new Tripwire
    val env = awsEnv - "SCHOLARCHAIN_VOYAGE_API_KEY"
    val r   = EmbeddingWorkerMain.assemble(env, seams(t))
    assert(r.isRight, r.toString)
    assertEquals(t.calls.get, 0)
  }

  test("QueryApiMain.assemble without the public API key is Left naming the variable") {
    val env = awsEnv - "SCHOLARCHAIN_SERVICES_API_KEY"
    val r   = QueryApiMain.assemble(env, seams(new Tripwire), "127.0.0.1")
    assert(r.left.exists(_.contains("SCHOLARCHAIN_SERVICES_API_KEY")), r.toString)
  }

  test("RetrievalMain.assemble without the internal API key is Left naming the variable") {
    val env = awsEnv - "SCHOLARCHAIN_INTERNAL_API_KEY"
    val r   = RetrievalMain.assemble(env, seams(new Tripwire), "127.0.0.1")
    assert(r.left.exists(_.contains("SCHOLARCHAIN_INTERNAL_API_KEY")), r.toString)
  }

  test("QueryApiMain.assemble without the retrieval URL is Left naming it") {
    val env = awsEnv - "SCHOLARCHAIN_SERVICES_RETRIEVAL_URL"
    val r   = QueryApiMain.assemble(env, seams(new Tripwire), "127.0.0.1")
    assert(r.left.exists(_.contains("SCHOLARCHAIN_SERVICES_RETRIEVAL_URL")), r.toString)
  }

  test("RetrievalMain.assemble without the Neptune endpoint is Left naming it") {
    val env = awsEnv - "SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT"
    val r   = RetrievalMain.assemble(env, seams(new Tripwire), "127.0.0.1")
    assert(r.left.exists(_.contains("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT")), r.toString)
  }

  test("EmbeddingWorkerMain.assemble without the queue URL is Left naming it") {
    val env = awsEnv - "SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL"
    val r   = EmbeddingWorkerMain.assemble(env, seams(new Tripwire))
    assert(r.left.exists(_.contains("SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL")), r.toString)
  }

  test("EmbeddingWorkerMain.assemble in local mode is refused") {
    val env = awsEnv.updated("SCHOLARCHAIN_ENVIRONMENT", "local")
    assert(EmbeddingWorkerMain.assemble(env, seams(new Tripwire)).isLeft)
  }

  test("QueryApiMain.assemble without a region is Left naming it") {
    val env = awsEnv - "SCHOLARCHAIN_AWS_REGION"
    val r   = QueryApiMain.assemble(env, seams(new Tripwire), "127.0.0.1")
    assert(r.left.exists(_.contains("SCHOLARCHAIN_AWS_REGION")), r.toString)
  }

  test("a Left from assemble never contains a secret value from the environment") {
    val env = awsEnv - "SCHOLARCHAIN_AWS_REGION"
    val msg = QueryApiMain.assemble(env, seams(new Tripwire), "127.0.0.1").left.getOrElse("")
    List(
      "dummy-public-key-for-tests-0001",
      "dummy-internal-key-for-tests-0002",
      "dummy-anthropic-key-not-real",
    ).foreach(s => assert(!msg.contains(s), msg))
  }
