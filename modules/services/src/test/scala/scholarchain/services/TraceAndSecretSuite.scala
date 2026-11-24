package scholarchain.services

import scholarchain.core.rest.*

import ApiHarness.*

/** Phase 10 C category 2: trace ids in every error body and log line, and no secret in any response
  * or log. Dummy key values only.
  */
class TraceAndSecretSuite extends munit.FunSuite:

  private val server = new Server(base)

  override def afterAll(): Unit = server.stop()

  private val missingEvidence = "evidence:" + "e" * 64

  private def errorReplies(): List[Reply] = List(
    server.post("/v1/query", "{bad"),
    server.post("/v1/query", query(Q1), auth = false),
    server.get(s"/v1/evidence/$missingEvidence"),
    server.post(
      "/v1/query",
      ujson.write(
        ujson.Obj("question" -> "Who?", "analysis" -> ujson.Obj("rankingMetric" -> "CITATIONS"))
      ),
    ),
    server.get("/v1/nothing"),
  )

  test("every error body carries a traceId equal to the X-Trace-Id header") {
    errorReplies().foreach { r =>
      assert(r.status >= 400, r.body)
      assertEquals(r.json("traceId").str, r.traceHeader, r.body)
      assert(r.traceHeader.nonEmpty)
      assert(RestJson.read[ApiError](r.body).isRight, r.body)
    }
  }

  test("a valid caller X-Trace-Id is propagated into the error body and the response header") {
    val r =
      server.get(s"/v1/evidence/$missingEvidence", extra = Map("X-Trace-Id" -> "trace-caller-7"))
    assertEquals(r.traceHeader, "trace-caller-7")
    assertEquals(r.json("traceId").str, "trace-caller-7")
  }

  test("an invalid caller X-Trace-Id is replaced and never echoed") {
    val bad = "bad trace id with spaces <script>"
    val r   = server.get(s"/v1/evidence/$missingEvidence", extra = Map("X-Trace-Id" -> bad))
    assert(!r.body.contains("script"), r.body)
    assert(!r.traceHeader.contains("script"))
    assertNotEquals(r.traceHeader, bad)
    assertEquals(r.json("traceId").str, r.traceHeader)
  }

  test("X-Request-Id is returned on every response") {
    val ok  = server.get("/healthz", auth = false)
    val err = server.get(s"/v1/evidence/$missingEvidence")
    assert(ok.headers.get("x-request-id").exists(_.nonEmpty))
    assert(err.headers.get("x-request-id").exists(_.nonEmpty))
  }

  test("every request produces a log line with the request's trace id") {
    val r = server.get(s"/v1/evidence/$missingEvidence", extra = Map("X-Trace-Id" -> "trace-log-1"))
    val line = server.logs.map(ujson.read(_)).filter(_("traceId").str == "trace-log-1")
    assert(line.nonEmpty, server.logs.toString)
    assertEquals(line.head("status").str, r.status.toString)
  }

  test("every log line is JSON with ts, level, service, event, traceId and requestId") {
    errorReplies()
    assert(server.logs.nonEmpty)
    server.logs.foreach { l =>
      val j = ujson.read(l)
      List("ts", "level", "service", "event", "traceId", "requestId").foreach(k =>
        assert(j.obj.get(k).exists(_.str.nonEmpty), s"log line lacks $k: $l")
      )
    }
  }

  test("the API key value appears in no response body or header") {
    val replies = errorReplies() :+ server.post("/v1/query", query(Q1)) :+
      server.get("/healthz", auth = false) :+ server.get("/readyz", auth = false)
    replies.foreach { r =>
      assert(!r.body.contains(PublicKey), r.body)
      assert(!r.headers.values.exists(_.contains(PublicKey)))
    }
  }

  test("the API key value and a wrong presented key appear in no log line") {
    server.get(
      s"/v1/evidence/$missingEvidence",
      extra = Map("X-API-Key" -> "presented-wrong-key-xyz"),
    )
    errorReplies()
    server.logs.foreach { l =>
      assert(!l.contains(PublicKey), l)
      assert(!l.contains("presented-wrong-key-xyz"), l)
    }
  }

  test("the internal key appears in no query-api response or log") {
    errorReplies()
    server.get("/readyz", auth = false)
    server.logs.foreach(l => assert(!l.contains(InternalKey), l))
  }

  test("a failing dependency does not leak its message in the 502 body or the log") {
    val s = new Server(withPorts(kv = new FailingKv))
    try
      val r = s.post("/v1/query", query(Q1), idem = Some("leak-1"))
      assertEquals(r.status, 502)
      assert(!r.body.contains("fake-failure"), r.body)
      s.logs.foreach(l => assert(!l.contains("fake-failure"), l))
      assertEquals(r.json("traceId").str, r.traceHeader)
    finally s.stop()
  }

  test("the readiness body of a failing dependency exposes only a reason code") {
    val s = new Server(withPorts(graph = new FailingGraph(base.graph)))
    try
      val r = s.get("/readyz", auth = false)
      assertEquals(r.status, 503)
      assert(!r.body.contains("fake-failure"), r.body)
      val reasons = r.json("checks").arr.flatMap(_.obj.get("reason")).map(_.str).toList
      assertEquals(reasons, List("unavailable"))
    finally s.stop()
  }

  test("the 401 body does not echo the presented key") {
    val r = server.get(
      s"/v1/evidence/$missingEvidence",
      auth = false,
      Map("X-API-Key" -> "echo-me-please"),
    )
    assertEquals(r.status, 401)
    assert(!r.body.contains("echo-me-please"), r.body)
  }

  test(
    "retrieval internal routes: no internal key is 401 with a trace id; the public key is refused"
  ) {
    val rt  = withPorts(needs = Set("graph", "vectors"))
    val ids = new IdGen(true)
    val log = new JsonLog("retrieval", _ => ())
    val app = new HttpApp(
      "retrieval",
      "127.0.0.1",
      0,
      log,
      ids,
      RestLimits.MaxBodyBytes,
      new RetrievalRoutes(rt, new ApiKeyAuth(Some(InternalKey))).handle,
    )
    val port = app.start()
    try
      val none = call(port, "POST", "/internal/v1/vector-get", Some("{}"))
      assertEquals(none.status, 401, none.body)
      assertEquals(none.json("traceId").str, none.traceHeader)
      val pub = call(
        port,
        "POST",
        "/internal/v1/vector-get",
        Some("{}"),
        Map("X-Internal-Api-Key" -> PublicKey),
      )
      assertEquals(pub.status, 401)
      assert(!pub.body.contains(PublicKey))
      assertEquals(call(port, "GET", "/healthz").status, 200)
    finally app.stop()
  }
