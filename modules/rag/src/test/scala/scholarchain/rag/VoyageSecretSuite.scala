package scholarchain.rag

import scholarchain.core.*

import RagFx.*

/** The Voyage API key never leaks, and Voyage failures map to the right error classes. Uses an
  * injected transport and environment: no network, no real key.
  */
class VoyageSecretSuite extends munit.FunSuite:

  private val Key                           = "pa-FAKE-KEY-7f3c9a1e5d2b-DO-NOT-LEAK"
  private val EnvName                       = "SCHOLARCHAIN_VOYAGE_API_KEY"
  private val env: String => Option[String] = n => if n == EnvName then Some(Key) else None

  final case class Call(url: String, headers: Map[String, String], body: String)

  private final class FakeTransport(reply: Either[String, HttpReply]) extends VoyageTransport:
    val calls = scala.collection.mutable.ListBuffer.empty[Call]
    def post(url: String, headers: Map[String, String], body: String) =
      calls += Call(url, headers, body)
      reply

  private def okBody(vs: List[List[Double]]): String =
    ujson
      .Obj(
        "data" -> ujson.Arr.from(
          vs.zipWithIndex.map((v, i) =>
            ujson.Obj("index" -> i, "embedding" -> ujson.Arr.from(v.map(ujson.Num(_))))
          )
        )
      )
      .render()

  private def make(
      t: FakeTransport,
      dim: Int = 3,
      inputType: String = VoyageEmbedder.DefaultInputType,
  ): VoyageEmbedder =
    VoyageEmbedder
      .create("voyage-3", "v1", dim, EnvName, env, t, inputType = inputType)
      .fold(e => fail(e.toString), identity)

  private def leaks(s: String): Boolean = s.contains(Key) || s.contains("FAKE-KEY")

  test("a good reply returns the vectors in input order, sorted by the reply's index") {
    val reply = ujson
      .Obj(
        "data" -> ujson.Arr(
          ujson.Obj("index" -> 1, "embedding" -> ujson.Arr(4.0, 5.0, 6.0)),
          ujson.Obj("index" -> 0, "embedding" -> ujson.Arr(1.0, 2.0, 3.0)),
        )
      )
      .render()
    val e = make(FakeTransport(Right(HttpReply(200, reply))))
    assertEquals(e.embed(List("a", "b")), Right(List(Vector(1f, 2f, 3f), Vector(4f, 5f, 6f))))
  }

  test("the key reaches the transport only in the Authorization header") {
    val t = FakeTransport(Right(HttpReply(200, okBody(List(List(1, 2, 3))))))
    make(t).embed(List("some text"))
    val c = t.calls.head
    assertEquals(c.headers("Authorization"), s"Bearer $Key")
    assert(!leaks(c.url), "key in the URL")
    assert(!leaks(c.body), "key in the request body")
    assert(
      c.headers.filter((k, _) => k != "Authorization").values.forall(!leaks(_)),
      "key in another header",
    )
    assertEquals(c.url, VoyageEmbedder.DefaultEndpoint)
  }

  test("the request body names the model, the input and the input type") {
    val t = FakeTransport(Right(HttpReply(200, okBody(List(List(1, 2, 3))))))
    make(t).embed(List("hello"))
    val j = ujson.read(t.calls.head.body)
    assertEquals(j("model").str, "voyage-3")
    assertEquals(j("input_type").str, "document")
    assertEquals(j("input").arr.map(_.str).toList, List("hello"))
    val q = FakeTransport(Right(HttpReply(200, okBody(List(List(1, 2, 3))))))
    make(q, inputType = VoyageEmbedder.QueryInputType).embed(List("hello"))
    assertEquals(ujson.read(q.calls.head.body)("input_type").str, "query")
  }

  test("HTTP 429 is Throttled; 408 and 5xx are Unavailable; other 4xx is Failed") {
    def err(status: Int) = make(FakeTransport(Right(HttpReply(status, "{}")))).embed(List("a"))
    assertEquals(err(429).left.map(_.code), Left("throttled"))
    assertEquals(err(408).left.map(_.code), Left("unavailable"))
    assertEquals(err(500).left.map(_.code), Left("unavailable"))
    assertEquals(err(503).left.map(_.code), Left("unavailable"))
    assertEquals(err(401).left.map(_.code), Left("failed"))
    assertEquals(err(400).left.map(_.code), Left("failed"))
    assertEquals(err(429).left.map(_.retryable), Left(true))
    assertEquals(err(401).left.map(_.retryable), Left(false))
  }

  test("a transport failure is Unavailable (retryable) and names only a fixed kind") {
    List("timeout", "io", "interrupted", "error").foreach { kind =>
      val r = make(FakeTransport(Left(kind))).embed(List("a"))
      assertEquals(r.left.map(_.code), Left("unavailable"))
      assertEquals(r.left.map(_.retryable), Left(true))
    }
  }

  test("no error string, toString or output line contains the key, for every reply class") {
    val replies: List[Either[String, HttpReply]] = List(
      Right(HttpReply(200, okBody(List(List(1, 2, 3))))),
      Right(HttpReply(200, "not json at all")),
      Right(HttpReply(200, """{"data":[{"index":0,"embedding":[1,2]}]}""")), // short vector
      Right(HttpReply(200, """{"data":[]}""")),
      Right(HttpReply(429, s"""{"detail":"rate limited for $Key"}""")),
      Right(HttpReply(500, s"server echoes the Authorization: Bearer $Key")),
      Right(HttpReply(401, s"""{"detail":"invalid key $Key"}""")),
      Right(HttpReply(404, Key)),
      Left("timeout"),
      Left("io"),
    )
    val (strings, out) = capturedOutput {
      replies.flatMap { r =>
        val e   = make(FakeTransport(r))
        val res = e.embed(List("a"))
        List(res.toString, e.toString) ++ res.left.toOption.map(_.toString).toList
      }
    }
    strings.foreach(s => assert(!leaks(s), s"leak in: $s"))
    assert(!leaks(out), "the key was printed or logged")
  }

  test("a server that echoes the key in its body does not get that body into the error") {
    val r =
      make(FakeTransport(Right(HttpReply(401, s"""{"error":"bad key $Key"}""")))).embed(List("a"))
    assertEquals(r, Left(EmbeddingError.Failed("http 4xx")))
  }

  test("toString redacts the key and shows the identity") {
    val s = make(FakeTransport(Right(HttpReply(200, "{}")))).toString
    assert(!leaks(s), s)
    assert(s.contains("<redacted>") && s.contains("voyage-3"), s)
  }

  test("an unset, blank or malformed variable is Left and names only the variable") {
    def create(value: Option[String]) =
      VoyageEmbedder.create("m", "v", 3, EnvName, _ => value, FakeTransport(Left("x")))
    val unset     = create(None)
    val blank     = create(Some("   "))
    val withSpace = create(Some(s"$Key plus-more"))
    val withCtl   = create(Some(s"pa-FAKE\u0007-rest"))
    List(unset, blank, withSpace, withCtl).foreach { r =>
      assert(r.isLeft)
      val msg = r.left.toOption.get.toString
      assert(!leaks(msg), msg)
    }
    assert(unset.left.toOption.get.toString.contains(EnvName))
    assert(blank.left.toOption.get.toString.contains(EnvName))
    assert(withSpace.left.toOption.get.toString.contains(EnvName))
    assertEquals(withCtl.left.toOption.map(_.code), Some("failed"))
  }

  test("surrounding whitespace around the key is trimmed, not rejected") {
    val t = FakeTransport(Right(HttpReply(200, okBody(List(List(1, 2, 3))))))
    val e = VoyageEmbedder.create("m", "v", 3, EnvName, _ => Some(s"  $Key\n"), t).toOption.get
    e.embed(List("a"))
    assertEquals(t.calls.head.headers("Authorization"), s"Bearer $Key")
  }

  test("the key is read from the variable NAMED in config, not from a hard-coded one") {
    val t = FakeTransport(Right(HttpReply(200, okBody(List(List(1, 2, 3))))))
    val r = VoyageEmbedder.create(
      "m",
      "v",
      3,
      "MY_OTHER_VAR",
      n => if n == "MY_OTHER_VAR" then Some(Key) else None,
      t,
    )
    assert(r.isRight)
    assert(VoyageEmbedder.create("m", "v", 3, "MY_OTHER_VAR", env, t).isLeft)
  }

  test("fromSettings uses the configured variable name (a name, never a value)") {
    val s = EmbeddingConfigLoader.load(Map.empty).fold(e => fail(e.toString), _.settings)
    assertEquals(s.voyageApiKeyEnvVar, EnvName)
    val t = FakeTransport(Right(HttpReply(200, okBody(List(List(1, 2, 3))))))
    assert(VoyageEmbedder.fromSettings(s.copy(dimension = 3), env, t).isRight)
    assert(VoyageEmbedder.fromSettings(s, _ => None, t).isLeft)
  }

  test("a reply vector of the wrong length is DimensionMismatch; a wrong count is Failed") {
    val short = """{"data":[{"index":0,"embedding":[1,2]}]}"""
    assertEquals(
      make(FakeTransport(Right(HttpReply(200, short)))).embed(List("a")),
      Left(EmbeddingError.DimensionMismatch(3, 2)),
    )
    val one = okBody(List(List(1, 2, 3)))
    assertEquals(
      make(FakeTransport(Right(HttpReply(200, one)))).embed(List("a", "b")).left.map(_.code),
      Left("failed"),
    )
  }

  test("input validation happens before any transport call") {
    val t = FakeTransport(Right(HttpReply(200, "{}")))
    val e = VoyageEmbedder.create("m", "v", 3, EnvName, env, t, maxBatchSize = 2).toOption.get
    assertEquals(e.embed(Nil).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List("a", "b", "c")).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List("  ")).left.map(_.code), Left("invalid-request"))
    assertEquals(t.calls.size, 0)
  }

  test("an http endpoint, a bad dimension and an unknown input type are refused at construction") {
    val t = FakeTransport(Left("x"))
    assert(
      VoyageEmbedder.create("m", "v", 3, EnvName, env, t, endpoint = "http://example.com/x").isLeft
    )
    assert(VoyageEmbedder.create("m", "v", 0, EnvName, env, t).isLeft)
    assert(VoyageEmbedder.create("m", "v", 5000, EnvName, env, t).isLeft)
    assert(VoyageEmbedder.create("m", "v", 3, EnvName, env, t, inputType = "passage").isLeft)
  }

  test("the factory wraps Voyage in a ValidatingEmbedder (dimension is enforced twice)") {
    val s = EmbeddingConfigLoader
      .load(Map.empty)
      .fold(e => fail(e.toString), _.settings)
      .copy(dimension = 3)
    val t = FakeTransport(Right(HttpReply(200, okBody(List(List(1, 2, 3))))))
    val e = EmbedderFactory.voyage(s, env, t).fold(x => fail(x.toString), identity)
    assert(e.isInstanceOf[ValidatingEmbedder])
    assertEquals(e.embed(List("a")), Right(List(Vector(1f, 2f, 3f))))
  }

  test("the Voyage source never logs: no println, logger or exception built from the key") {
    val root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath
    val src  = Iterator
      .iterate(Option(root))(_.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .map(_.resolve("modules/rag/src/main/scala/scholarchain/rag/VoyageEmbedder.scala"))
      .find(java.nio.file.Files.isRegularFile(_))
    assume(src.isDefined, "VoyageEmbedder.scala not found from the working directory")
    val code = new String(java.nio.file.Files.readAllBytes(src.get), "UTF-8").linesIterator
      .map(_.trim)
      .filterNot(l => l.startsWith("*") || l.startsWith("//") || l.startsWith("/**"))
      .mkString("\n")
    List("println", "System.out", "System.err", "logger", "LoggerFactory", "printStackTrace")
      .foreach { w =>
        assert(!code.contains(w), s"VoyageEmbedder.scala contains $w")
      }
    assert(!code.contains("getMessage"), "an exception message could carry the key")
  }
