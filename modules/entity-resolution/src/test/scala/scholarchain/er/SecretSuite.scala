package scholarchain.er

import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.file.Files
import java.time.Duration

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import scholarchain.core.*

import ErFx.*

object SecretSuite:
  /** A recognizable DUMMY value. It is not, and never was, a real credential. */
  val DummyKey: String = "sk-ant-DUMMY-NOT-A-REAL-KEY-0123456789"

/** The API key never leaves the request header. The HTTP client is pointed at a LOCAL fake server
  * on the loopback interface, so no real endpoint is contacted and no LLM is called.
  */
class SecretSuite extends munit.FunSuite:
  import SecretSuite.DummyKey

  private def key: ApiKey =
    ApiKey.fromEnv(Map(ApiKey.EnvName -> DummyKey)).fold(e => throw new AssertionError(e), identity)

  private def noKey(s: String, what: String): Unit =
    assert(!s.contains(DummyKey), s"$what leaked the key: $s")
    assert(!s.contains("DUMMY-NOT-A-REAL"), s"$what leaked part of the key: $s")

  private final class Fake(status: Int, body: String):
    @volatile var seenKey: Option[String] = None
    @volatile var seenBody: String        = ""
    @volatile var hits: Int               = 0
    val server: HttpServer                =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      (ex: HttpExchange) =>
        hits += 1
        seenKey = Option(ex.getRequestHeaders.getFirst("x-api-key"))
        seenBody = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
        val bytes = body.getBytes("UTF-8")
        ex.sendResponseHeaders(status, bytes.length.toLong)
        ex.getResponseBody.write(bytes)
        ex.close(),
    )
    server.start()
    def uri: URI     = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/v1/messages")
    def stop(): Unit = server.stop(0)

  private def withFake[A](status: Int, body: String)(f: Fake => A): A =
    val s = new Fake(status, body)
    try f(s)
    finally s.stop()

  private def client(uri: URI) =
    new AnthropicClient(key, "claude-test", 300, Duration.ofSeconds(5), uri)

  private val Ok =
    """{"content":[{"type":"text","text":"hello "},{"type":"text","text":"world"}],"usage":{"input_tokens":12,"output_tokens":3}}"""

  // ---- the key object ---------------------------------------------------------------------------

  test("ApiKey.toString is masked") {
    assertEquals(key.toString, "ApiKey(***)")
    noKey(key.toString, "toString")
    noKey(s"$key", "string interpolation")
    noKey(List(key).toString, "collection toString")
    noKey(Some(key).toString, "Option toString")
  }

  test(
    "ApiKey.fromEnv reads SCHOLARCHAIN_ANTHROPIC_API_KEY only; the error names the variable, never a value"
  ) {
    assertEquals(ApiKey.EnvName, "SCHOLARCHAIN_ANTHROPIC_API_KEY")
    val wrong = ApiKey.fromEnv(Map("ANTHROPIC_API_KEY" -> DummyKey))
    assertEquals(
      wrong.left.toOption,
      Some("environment variable SCHOLARCHAIN_ANTHROPIC_API_KEY is not set"),
    )
    noKey(wrong.left.getOrElse(""), "error message")
    assert(ApiKey.fromEnv(Map.empty).isLeft)
    assert(ApiKey.fromEnv(Map(ApiKey.EnvName -> "   ")).isLeft, "a blank value is not a key")
    assert(ApiKey.fromEnv(Map(ApiKey.EnvName -> "")).isLeft)
  }

  // ---- M3: a key that cannot be an HTTP header value is refused, naming the variable only -----------------

  private def rejected(value: String): String =
    val r = ApiKey.fromEnv(Map(ApiKey.EnvName -> value))
    assert(
      r.isLeft,
      s"a key with a non-visible-ASCII character must be refused: ${value.length} chars",
    )
    val msg = r.left.getOrElse("")
    assert(msg.contains(ApiKey.EnvName), msg)
    assert(!msg.contains("\r") && !msg.contains("\n"), "the message holds no line break")
    msg

  test(
    "M3 ApiKey.fromEnv: CR/LF inside the key is refused; the message names the variable, not the value"
  ) {
    val msg = rejected(DummyKey + "\r\nX-Injected: 1")
    noKey(msg, "error message")
    assert(!msg.contains("X-Injected"), msg)
    noKey(rejected(DummyKey.take(10) + "\nrest"), "error message")
    noKey(rejected(DummyKey.take(10) + "\rrest"), "error message")
  }

  test("M3 ApiKey.fromEnv: non-ASCII, space, tab, NUL and DEL inside the key are refused") {
    List(
      DummyKey.take(10) + "é" + DummyKey.drop(10),
      DummyKey.take(10) + "中" + DummyKey.drop(10),
      DummyKey.take(10) + " " + DummyKey.drop(10),
      DummyKey.take(10) + "\t" + DummyKey.drop(10),
      DummyKey.take(10) + "\u0000" + DummyKey.drop(10),
      DummyKey.take(10) + "\u007f" + DummyKey.drop(10),
    ).foreach(v => noKey(rejected(v), "error message"))
  }

  test(
    "M3 ApiKey.fromEnv: the visible ASCII range ! to ~ is accepted, and the value stays masked"
  ) {
    val all = ('!' to '~').mkString
    val r   = ApiKey.fromEnv(Map(ApiKey.EnvName -> all))
    assert(r.isRight, r.toString)
    assertEquals(r.toOption.get.toString, "ApiKey(***)")
    assert(ApiKey.fromEnv(Map(ApiKey.EnvName -> DummyKey)).isRight)
  }

  /** The only way to hold a key that `fromEnv` refuses, to prove the client itself never throws. */
  private def forcedKey(value: String): ApiKey =
    val ctor = classOf[ApiKey].getDeclaredConstructors.head
    ctor.setAccessible(true)
    ctor.newInstance(value).asInstanceOf[ApiKey]

  test(
    "M3 AnthropicClient.complete: a header value with CR/LF is bad_request, no throw, no request sent"
  ) {
    withFake(200, Ok) { f =>
      val bad = new AnthropicClient(
        forcedKey("sk-ant-DUMMY\r\nX-Injected: 1"),
        "claude-test",
        300,
        Duration.ofSeconds(5),
        f.uri,
      )
      val r = bad.complete("s", "u")
      assertEquals(r, Left(LlmError("bad_request")))
      assertEquals(f.hits, 0, "nothing reached the server")
      assert(!r.toString.contains("X-Injected"), r.toString)
    }
  }

  test(
    "M3 AnthropicClient.complete: any request-construction failure (bad endpoint scheme) is bad_request"
  ) {
    val c = new AnthropicClient(
      key,
      "claude-test",
      300,
      Duration.ofSeconds(5),
      URI.create("ftp://127.0.0.1/v1/messages"),
    )
    val r = c.complete("s", "u")
    assertEquals(r, Left(LlmError("bad_request")))
    noKey(r.toString, "LlmError")
  }

  test("LlmClients.create: provider errors are fixed strings and never carry the key") {
    val base    = ErFx.cfg.llm
    val bedrock =
      LlmClients.create(base.copy(provider = "bedrock"), Map(ApiKey.EnvName -> DummyKey))
    assertEquals(
      bedrock.left.toOption,
      Some("LLM provider 'bedrock' is not implemented in Phase 05"),
    )
    val other = LlmClients.create(base.copy(provider = "openai"), Map(ApiKey.EnvName -> DummyKey))
    assertEquals(other.left.toOption, Some("unknown LLM provider 'openai'"))
    val missing = LlmClients.create(base, Map.empty)
    noKey(missing.left.getOrElse(""), "missing-key error")
    val ok = LlmClients.create(base, Map(ApiKey.EnvName -> DummyKey))
    assert(ok.isRight)
    noKey(ok.toString, "client toString")
    assertEquals(ok.map(_.model), Right("claude-haiku-4-5"))
  }

  // ---- the key on the wire and in errors -------------------------------------------------------------

  test("the key goes into the x-api-key header only; the request body does not carry it") {
    withFake(200, Ok) { f =>
      val r = client(f.uri).complete("SYSTEM TEXT", "USER TEXT")
      assertEquals(r, Right(LlmReply("hello world", LlmUsage(12, 3))))
      assertEquals(f.hits, 1)
      assertEquals(f.seenKey, Some(DummyKey))
      noKey(f.seenBody, "request body")
      val b = ujson.read(f.seenBody)
      assertEquals(b("model").str, "claude-test")
      assertEquals(b("max_tokens").num.toInt, 300)
      assertEquals(b("temperature").num, 0.0)
      assertEquals(b("system").str, "SYSTEM TEXT")
      assertEquals(b("messages")(0)("content").str, "USER TEXT")
    }
  }

  test(
    "SAFETY key leak: an HTTP error whose BODY echoes the key yields an error with the status only"
  ) {
    val echo = s"""{"error":{"message":"invalid x-api-key $DummyKey"}}"""
    withFake(401, echo) { f =>
      val r = client(f.uri).complete("s", "u")
      assertEquals(r, Left(LlmError("http_status", Some(401))))
      noKey(r.toString, "LlmError")
      noKey(r.left.toOption.get.message, "LlmError.message")
      assertEquals(r.left.toOption.get.message, "http_status (http 401)")
      assertEquals(f.seenKey, Some(DummyKey), "the key was sent, but never comes back")
    }
  }

  test("SAFETY key leak: a 200 body that is not the expected shape is bad_response, not echoed") {
    withFake(200, s"""{"oops":"$DummyKey"}""") { f =>
      val r = client(f.uri).complete("s", "u")
      assertEquals(r, Left(LlmError("bad_response")))
      noKey(r.toString, "LlmError")
    }
  }

  test("a transport failure (connection refused) is transport_error with no detail") {
    val f   = new Fake(200, Ok)
    val uri = f.uri
    f.stop()
    val r = client(uri).complete("s", "u")
    assertEquals(r, Left(LlmError("transport_error")))
    noKey(r.toString, "LlmError")
  }

  test("SAFETY key leak: end to end through the Adjudicator against a server that echoes the key") {
    val c1    = cand("mention:a", "mention:b", EntityKind.Researcher, Map("name_sim" -> 1.0))
    val views = (
      person("mention:a", SourceType.Dblp, "Wei Wang"),
      person("mention:b", SourceType.Crossref, "W. Wang"),
    )
    val prompt = PromptTemplate.load().fold(e => throw new AssertionError(e), identity)
    val dir    = Files.createTempDirectory("er-secret-test-")
    val echo   = s"""{"error":"bad key $DummyKey"}"""
    withFake(500, echo) { f =>
      val cache = AdjudicationCache.open(dir.resolve("c.jsonl"))._1
      val adj   = new Adjudicator(client(f.uri), cache, ErFx.cfg, prompt, "unit", 10)
      val res   = adj.adjudicate(c1, views._1, views._2)
      assertEquals(res, AdjudicationResult.Skipped("http_status (http 500)"))
      noKey(res.toString, "AdjudicationResult")
      assertEquals(cache.size, 0)
      assert(!Files.exists(dir.resolve("c.jsonl")), "nothing was written")
    }
    // a valid answer: neither the cached record nor the cache file holds the key
    val valid =
      """{"content":[{"type":"text","text":"{\"leftId\":\"mention:a\",\"rightId\":\"mention:b\",\"outcome\":\"accept\",\"confidence\":0.9,\"citedFields\":[\"left.name\"],\"reasonCodes\":[\"same_name\"]}"}],"usage":{"input_tokens":50,"output_tokens":20}}"""
    withFake(200, valid) { f =>
      val path  = dir.resolve("ok.jsonl")
      val cache = AdjudicationCache.open(path)._1
      val adj   = new Adjudicator(client(f.uri), cache, ErFx.cfg, prompt, "unit", 10)
      val res   = adj.adjudicate(c1, views._1, views._2)
      assert(res.isInstanceOf[AdjudicationResult.Fresh], res.toString)
      noKey(res.toString, "AdjudicationResult")
      noKey(Files.readString(path), "cache file")
      assertEquals(f.seenKey, Some(DummyKey))
      assertEquals(adj.usage, LlmUsage(50, 20))
    }
  }

  test("the prompt sent to the model never contains the key") {
    val prompt      = PromptTemplate.load().fold(e => throw new AssertionError(e), identity)
    val c1          = cand("mention:a", "mention:b", EntityKind.Researcher, Map("name_sim" -> 1.0))
    val (sys, user) = AdjudicationInput.render(
      prompt,
      c1,
      person("mention:a", SourceType.Dblp, "Wei Wang"),
      person("mention:b", SourceType.Crossref, "W. Wang"),
    )
    noKey(sys, "system prompt")
    noKey(user, "user prompt")
  }
