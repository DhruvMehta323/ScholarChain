package scholarchain.rag

import software.amazon.awssdk.auth.credentials.{AwsCredentials, AwsCredentialsProvider}
import scholarchain.core.*

import RagFx.*

/** Bedrock Titan adapter over a fake invoker, and the profile gate: with SCHOLARCHAIN_AWS_IT unset
  * no Bedrock client is constructed.
  */
class BedrockEmbedderSuite extends munit.FunSuite:

  private final class FakeInvoker(reply: String => Either[EmbeddingError, String])
      extends BedrockInvoker:
    val calls = scala.collection.mutable.ListBuffer.empty[(String, String)]
    def invoke(modelId: String, body: String) =
      calls += modelId -> body
      reply(body)

  private def vecJson(n: Int): String =
    ujson.Obj("embedding" -> ujson.Arr.from(List.fill(n)(ujson.Num(0.5)))).render()

  /** A provider that must never be asked: any resolution is a test failure. No real credential. */
  private object ThrowingProvider extends AwsCredentialsProvider:
    @volatile var resolved                            = 0
    override def resolveCredentials(): AwsCredentials =
      resolved += 1
      throw new IllegalStateException("test provider must not be resolved")

  private def settings: EmbeddingSettings =
    EmbeddingConfigLoader.load(Map.empty).fold(e => fail(e.toString), _.settings)

  private def embedder(inv: FakeInvoker, dim: Int = 256) =
    new BedrockEmbedder(inv, "amazon.titan-embed-text-v2:0", "v2", dim)

  // ---- profile gate --------------------------------------------------------------------------------------

  test(
    "EmbedderFactory.bedrock with liveProfile = false is Left and never builds an invoker or a client"
  ) {
    var invokerBuilt = 0
    val r            = EmbedderFactory.bedrock(
      settings,
      "us-east-1",
      liveProfile = false,
      credentials = ThrowingProvider,
      invokerFor = (_, _) => { invokerBuilt += 1; FakeInvoker(_ => Right(vecJson(1024))) },
    )
    assertEquals(r.left.map(_.code), Left("failed"))
    assert(r.left.toOption.get.toString.contains("live profile"))
    assertEquals(invokerBuilt, 0)
    assertEquals(SdkBedrockInvoker.clientsConstructed, 0)
  }

  test("SdkBedrockInvoker.clientsConstructed is 0: constructing the SDK invoker builds no client") {
    new SdkBedrockInvoker("us-east-1", ThrowingProvider)
    assertEquals(SdkBedrockInvoker.clientsConstructed, 0)
    assertEquals(ThrowingProvider.resolved, 0)
  }

  test("the default invoker of the factory is not even created while liveProfile is false") {
    EmbedderFactory.bedrock(settings, "us-east-1", liveProfile = false, ThrowingProvider)
    assertEquals(SdkBedrockInvoker.clientsConstructed, 0)
  }

  test(
    "a live profile builds an embedder over the injected invoker, still with no SDK client and no call"
  ) {
    val inv = FakeInvoker(_ => Right(vecJson(1024)))
    val r   = EmbedderFactory.bedrock(
      settings,
      "us-east-1",
      liveProfile = true,
      ThrowingProvider,
      (_, _) => inv,
    )
    assert(r.isRight)
    assert(r.toOption.get.isInstanceOf[ValidatingEmbedder])
    assertEquals(inv.calls.size, 0, "building the embedder must not call the model")
    assertEquals(SdkBedrockInvoker.clientsConstructed, 0)
  }

  test("a blank region is refused even with a live profile") {
    assertEquals(
      EmbedderFactory
        .bedrock(
          settings,
          "  ",
          liveProfile = true,
          ThrowingProvider,
          (_, _) => FakeInvoker(_ => Right("{}")),
        )
        .left
        .map(_.code),
      Left("invalid-request"),
    )
  }

  test("the Bedrock sources are the only place that touches the SDK, and only inside the invoker") {
    val root = Iterator
      .iterate(Option(java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath))(
        _.flatMap(p => Option(p.getParent))
      )
      .takeWhile(_.isDefined)
      .flatten
      .find(p => java.nio.file.Files.isRegularFile(p.resolve("build.sbt")))
    assume(root.isDefined, "repository root not found")
    val dir = root.get.resolve("modules/rag/src/main/scala/scholarchain/rag")
    // The one allowed exception: the factory names the injected credentials provider TYPE (repair 01,
    // M2). Any other SDK reference in it, or any SDK reference in another file, is an offender.
    val allowedFactoryLine = "import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider"
    val offenders          = java.nio.file.Files
      .list(dir)
      .toArray
      .toList
      .map(_.asInstanceOf[java.nio.file.Path])
      .filter(_.toString.endsWith(".scala"))
      .filter(_.getFileName.toString != "BedrockEmbedder.scala")
      .filter { p =>
        val sdkLines = new String(java.nio.file.Files.readAllBytes(p), "UTF-8").linesIterator
          .filter(_.contains("software.amazon"))
          .map(_.trim)
          .toList
        if p.getFileName.toString == "EmbedderFactory.scala" then
          sdkLines.exists(_ != allowedFactoryLine)
        else sdkLines.nonEmpty
      }
      .map(_.getFileName.toString)
    assertEquals(offenders, Nil)
  }

  // ---- hermetic client (Phase 08 repair 01, M2) -------------------------------------------------------

  test("the factory hands the injected provider and the region to the invoker, building nothing") {
    var seen: Option[(String, AwsCredentialsProvider)] = None
    val r                                              = EmbedderFactory.bedrock(
      settings,
      "eu-west-1",
      liveProfile = true,
      ThrowingProvider,
      (region, c) => { seen = Some(region -> c); FakeInvoker(_ => Right(vecJson(1024))) },
    )
    assert(r.isRight)
    assertEquals(seen.map(_._1), Some("eu-west-1"))
    assert(seen.exists(_._2 eq ThrowingProvider), "the caller's provider is passed through as is")
    assertEquals(ThrowingProvider.resolved, 0)
    assertEquals(SdkBedrockInvoker.clientsConstructed, 0)
  }

  test("SdkBedrockInvoker.hermetic carries an EMPTY profile file and the fixed profile name") {
    val cfg = SdkBedrockInvoker.hermetic
    assertEquals(SdkBedrockInvoker.HermeticProfileName, "scholarchain-hermetic")
    assertEquals(cfg.defaultProfileName().orElse(""), SdkBedrockInvoker.HermeticProfileName)
    val file = cfg.defaultProfileFile().orElse(null)
    assert(file != null, "an explicit profile file must be supplied")
    assertEquals(file.profiles().size(), 0, "the profile file must hold no profile")
    assertEquals(file.profile(SdkBedrockInvoker.HermeticProfileName).isPresent, false)
    assertEquals(file.profile("default").isPresent, false)
  }

  test("building the hermetic configuration makes no client and resolves no credentials") {
    SdkBedrockInvoker.hermetic
    assertEquals(SdkBedrockInvoker.clientsConstructed, 0)
    assertEquals(ThrowingProvider.resolved, 0)
  }

  test("rag main sources never use the default credentials chain or a default profile file") {
    val root = Iterator
      .iterate(Option(java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath))(
        _.flatMap(p => Option(p.getParent))
      )
      .takeWhile(_.isDefined)
      .flatten
      .find(p => java.nio.file.Files.isRegularFile(p.resolve("build.sbt")))
    assert(root.isDefined, "repository root not found: the hermetic source check cannot run")
    val dir   = root.get.resolve("modules/rag/src/main")
    val files = java.nio.file.Files
      .walk(dir)
      .toArray
      .toList
      .map(_.asInstanceOf[java.nio.file.Path])
      .filter(_.toString.endsWith(".scala"))
    assert(files.nonEmpty, "no rag main sources found")
    def text(f: java.nio.file.Path) = new String(java.nio.file.Files.readAllBytes(f), "UTF-8")
    val forbidden                   = List(
      "DefaultCredentialsProvider",
      "ProfileCredentialsProvider",
      "InstanceProfileCredentialsProvider",
      "EnvironmentVariableCredentialsProvider",
      "SystemPropertyCredentialsProvider",
      "ProfileFile.defaultProfileFile",
      "ProfileFile.instance",
      "ProfileFileSupplier.defaultSupplier",
      "ProfileFile.Type.CREDENTIALS",
    )
    val hits = for
      f <- files
      txt = text(f)
      bad <- forbidden if txt.contains(bad)
    yield s"${f.getFileName}: $bad"
    assertEquals(hits, Nil)
    val invoker = files.find(_.getFileName.toString == "BedrockEmbedder.scala").map(text)
    assert(
      invoker.exists(_.contains(".credentialsProvider(credentials)")),
      "the injected provider must be set on the client builder",
    )
    assert(invoker.exists(_.contains(".overrideConfiguration(SdkBedrockInvoker.hermetic)")))
  }

  // ---- adapter behaviour over a fake invoker --------------------------------------------------------------

  test("the request sends inputText, dimensions and normalize = true, one call per text") {
    val inv = FakeInvoker(_ => Right(vecJson(256)))
    val r   = embedder(inv).embed(List("first", "second"))
    assertEquals(r.map(_.size), Right(2))
    assertEquals(inv.calls.size, 2)
    val j = ujson.read(inv.calls.head._2)
    assertEquals(j("inputText").str, "first")
    assertEquals(j("dimensions").num.toInt, 256)
    assertEquals(j("normalize").bool, true)
    assertEquals(inv.calls.head._1, "amazon.titan-embed-text-v2:0")
  }

  test("throttling maps to Throttled and a 5xx to Unavailable, both retryable") {
    val t = embedder(FakeInvoker(_ => Left(EmbeddingError.Throttled("http 429")))).embed(List("a"))
    val u =
      embedder(FakeInvoker(_ => Left(EmbeddingError.Unavailable("http 5xx")))).embed(List("a"))
    assertEquals(t.left.map(_.code), Left("throttled"))
    assertEquals(u.left.map(_.code), Left("unavailable"))
    assertEquals(t.left.map(_.retryable), Left(true))
    assertEquals(u.left.map(_.retryable), Left(true))
  }

  test("a reply vector of the wrong length is DimensionMismatch") {
    assertEquals(
      embedder(FakeInvoker(_ => Right(vecJson(100)))).embed(List("a")),
      Left(EmbeddingError.DimensionMismatch(256, 100)),
    )
  }

  test("a malformed reply is Failed with a fixed phrase") {
    assertEquals(
      embedder(FakeInvoker(_ => Right("not json"))).embed(List("a")),
      Left(EmbeddingError.Failed("malformed response")),
    )
    assertEquals(
      embedder(FakeInvoker(_ => Right("{}"))).embed(List("a")),
      Left(EmbeddingError.Failed("malformed response")),
    )
  }

  test("a failure on the second text fails the whole batch and does not call for the third") {
    var n   = 0
    val inv = FakeInvoker(_ => {
      n += 1; if n == 2 then Left(EmbeddingError.Throttled("http 429")) else Right(vecJson(256))
    })
    val r = embedder(inv).embed(List("a", "b", "c"))
    assertEquals(r.left.map(_.code), Left("throttled"))
    assertEquals(inv.calls.size, 2)
  }

  test("Titan accepts only 256, 512 or 1024 dimensions; nothing is sent otherwise") {
    val inv = FakeInvoker(_ => Right(vecJson(300)))
    assertEquals(
      embedder(inv, dim = 300).embed(List("a")).left.map(_.code),
      Left("invalid-request"),
    )
    assertEquals(inv.calls.size, 0)
    List(256, 512, 1024).foreach(d =>
      assert(embedder(FakeInvoker(_ => Right(vecJson(d))), d).embed(List("a")).isRight)
    )
  }

  test(
    "empty batch, batch above 16, blank text and text above 50,000 characters are InvalidRequest, no call"
  ) {
    val inv = FakeInvoker(_ => Right(vecJson(256)))
    val e   = embedder(inv)
    assertEquals(e.embed(Nil).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List.fill(17)("a")).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List(" ")).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List("x" * 50001)).left.map(_.code), Left("invalid-request"))
    assertEquals(inv.calls.size, 0)
    assert(e.embed(List("x" * 50000)).isRight)
  }

  test("model identity comes from the settings (Titan V2, v2, 1024 by default)") {
    val e = BedrockEmbedder.fromSettings(settings, FakeInvoker(_ => Right(vecJson(1024))))
    assertEquals(
      (e.modelId, e.modelVersion, e.dimension, e.maxBatchSize),
      ("amazon.titan-embed-text-v2:0", "v2", 1024, 16),
    )
  }
