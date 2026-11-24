package scholarchain.services

import scholarchain.core.rest.*

import ApiHarness.*

/** Phase 10 C category 1: the spec-required API behavior over HTTP on loopback, with local fixtures
  * and fake ports. A failure is a production defect in `modules/services` (owner service-engineer)
  * unless the message says otherwise.
  */
class QueryApiSpecSuite extends munit.FunSuite:

  private val syncServer  = new Server(base)
  private val asyncServer = new Server(base, deadlineMillis = 0L)

  override def afterAll(): Unit =
    syncServer.stop()
    asyncServer.stop()

  private def errorOf(r: Reply): String = r.json("error").str

  private val missingEvidence = "evidence:" + "e" * 64
  private val missingQuery    = "query:" + "f" * 64
  private val missingJob      = "job:" + "0" * 32
  private val missingNode     = "researcher:sc1:" + "0" * 64

  // ---- success paths -------------------------------------------------------------------------

  test("successful synchronous query returns 200 with mode synchronous and links") {
    val r = syncServer.post("/v1/query", query(Q1))
    assertEquals(r.status, 200, r.body)
    val j = r.json
    assertEquals(j("mode").str, "synchronous")
    assert(j("claims").arr.nonEmpty, r.body)
    assertEquals(j("links")("evidence").str, s"/v1/queries/${j("queryId").str}/evidence")
    assertEquals(r.headers.get("x-scholarchain-contract"), Some(RestContracts.QueryResponse))
  }

  test("the synchronous body is a valid SyncQueryResponse contract") {
    val r = syncServer.post("/v1/query", query(Q1))
    assert(RestJson.read[SyncQueryResponse](r.body).isRight, r.body)
  }

  test("asynchronous path: 202 with a job, then SUCCEEDED, then the result is served") {
    val r = asyncServer.post("/v1/query", query(Q2), idem = Some("async-spec-1"))
    assertEquals(r.status, 202, r.body)
    assertEquals(r.json("mode").str, "asynchronous")
    assert(RestJson.read[AsyncQueryResponse](r.body).isRight, r.body)
    val jobId = r.json("jobId").str
    assertEquals(r.json("statusUrl").str, s"/v1/jobs/$jobId")
    val done = awaitJob(asyncServer, jobId)
    assertEquals(done("state").str, "SUCCEEDED", done.toString)
    val res = asyncServer.get(s"/v1/jobs/${RestPaths.encode(jobId)}/result")
    assertEquals(res.status, 200, res.body)
    assertEquals(res.json("mode").str, "synchronous")
    assertEquals(res.json("queryId").str, r.json("queryId").str)
  }

  test("a job status body is a valid JobStatus contract") {
    val r     = asyncServer.post("/v1/query", query("What is the largest team size?"))
    val jobId = r.json("jobId").str
    awaitJob(asyncServer, jobId)
    val s = asyncServer.get(s"/v1/jobs/${RestPaths.encode(jobId)}")
    assertEquals(s.status, 200)
    assert(RestJson.read[JobStatus](s.body).isRight, s.body)
  }

  // ---- 4xx -------------------------------------------------------------------------------------

  test("invalid JSON is 400 with the error body") {
    val r = syncServer.post("/v1/query", "{not json")
    assertEquals(r.status, 400, r.body)
    assert(Set("MALFORMED_REQUEST", "INVALID_FIELD").contains(errorOf(r)), r.body)
  }

  test("an unknown top-level field is 400") {
    val r = syncServer.post("/v1/query", """{"question":"q","bogus":1}""")
    assertEquals(r.status, 400, r.body)
  }

  test("a missing question is 400") {
    assertEquals(syncServer.post("/v1/query", "{}").status, 400)
  }

  test("an unsupported ranking metric is 422") {
    val body = ujson.write(
      ujson.Obj("question" -> "Who?", "analysis" -> ujson.Obj("rankingMetric" -> "CITATIONS"))
    )
    val r = syncServer.post("/v1/query", body)
    assertEquals(r.status, 422, r.body)
  }

  test("yearFrom without yearTo is 422") {
    val body = ujson.write(
      ujson.Obj("question" -> "Who?", "constraints" -> ujson.Obj("yearFrom" -> 2015))
    )
    assertEquals(syncServer.post("/v1/query", body).status, 422)
  }

  test("two topics are 422 (unsupported constraint)") {
    val body = ujson.write(
      ujson.Obj("question" -> "Who?", "constraints" -> ujson.Obj("topics" -> ujson.Arr("a", "b")))
    )
    assertEquals(syncServer.post("/v1/query", body).status, 422)
  }

  test("missing evidence is 404 EVIDENCE_NOT_FOUND") {
    val r = syncServer.get(s"/v1/evidence/$missingEvidence")
    assertEquals(r.status, 404, r.body)
    assertEquals(errorOf(r), "EVIDENCE_NOT_FOUND")
  }

  test("unknown query, job, node and explain are 404 with their codes") {
    val q = syncServer.get(s"/v1/queries/$missingQuery/evidence")
    assertEquals((q.status, errorOf(q)), (404, "QUERY_NOT_FOUND"))
    val j = syncServer.get(s"/v1/jobs/$missingJob")
    assertEquals((j.status, errorOf(j)), (404, "JOB_NOT_FOUND"))
    val n = syncServer.get(s"/v1/graph/nodes/$missingNode")
    assertEquals((n.status, errorOf(n)), (404, "NODE_NOT_FOUND"))
    assertEquals(syncServer.get(s"/v1/explain/$missingQuery").status, 404)
  }

  test("a malformed job id is 400") {
    assertEquals(syncServer.get("/v1/jobs/x").status, 400)
  }

  test("an unknown route is 404 and carries the error body") {
    val r = syncServer.get("/v1/nothing")
    assertEquals(r.status, 404)
    assertEquals(errorOf(r), "NOT_FOUND")
  }

  // ---- idempotency ---------------------------------------------------------------------------

  test("idempotent repeat returns the stored result unchanged") {
    val first  = syncServer.post("/v1/query", query(Q1), idem = Some("idem-repeat-1"))
    val second = syncServer.post("/v1/query", query(Q1), idem = Some("idem-repeat-1"))
    assertEquals(first.status, 200, first.body)
    assertEquals(second.status, 200, second.body)
    assertEquals(second.body, first.body)
  }

  test("idempotency conflict: same key with a different body is 409 IDEMPOTENCY_CONFLICT") {
    syncServer.post("/v1/query", query(Q1), idem = Some("idem-conflict-1"))
    val r = syncServer.post("/v1/query", query(Q2), idem = Some("idem-conflict-1"))
    assertEquals(r.status, 409, r.body)
    assertEquals(errorOf(r), "IDEMPOTENCY_CONFLICT")
    assertEquals(r.json("retryable").bool, false)
  }

  test("the same Idempotency-Key from another caller key does not collide") {
    val other = new Server(base, key = Some("dummy-other-key-0003"))
    try
      val a = syncServer.post("/v1/query", query(Q1), idem = Some("idem-scope-1"))
      val b = call(
        other.port,
        "POST",
        "/v1/query",
        Some(query(Q2)),
        Map("X-API-Key" -> "dummy-other-key-0003", "Idempotency-Key" -> "idem-scope-1"),
      )
      assertEquals(a.status, 200)
      assertEquals(b.status, 200, b.body)
    finally other.stop()
  }

  test("a malformed Idempotency-Key is 400") {
    val r = syncServer.post("/v1/query", query(Q1), idem = Some("bad key with spaces"))
    assertEquals(r.status, 400, r.body)
  }

  // ---- authentication ------------------------------------------------------------------------

  test("missing API key is 401 UNAUTHENTICATED on every /v1 route") {
    val reqs = List(
      ("POST", "/v1/query"),
      ("GET", s"/v1/jobs/$missingJob"),
      ("GET", s"/v1/evidence/$missingEvidence"),
      ("GET", s"/v1/queries/$missingQuery/evidence"),
      ("GET", s"/v1/graph/nodes/$missingNode"),
      ("GET", s"/v1/explain/$missingQuery"),
    )
    reqs.foreach { (m, p) =>
      val r =
        if m == "POST" then syncServer.post(p, query(Q1), auth = false)
        else syncServer.get(p, auth = false)
      assertEquals(r.status, 401, s"$m $p => ${r.body}")
      assertEquals(errorOf(r), "UNAUTHENTICATED")
    }
  }

  test("a wrong API key is 401") {
    val r =
      syncServer.get(s"/v1/evidence/$missingEvidence", auth = false, Map("X-API-Key" -> "wrong"))
    assertEquals(r.status, 401)
  }

  // ---- health and readiness ------------------------------------------------------------------

  test("healthz and health are 200 without a key, with no dependency state") {
    val h = syncServer.get("/healthz", auth = false)
    assertEquals(h.status, 200)
    assertEquals(h.json("status").str, "ok")
    assertEquals(syncServer.get("/health", auth = false).status, 200)
    assert(!h.json.obj.contains("checks"))
  }

  test("readyz is 200 ready when every dependency answers") {
    val r = syncServer.get("/readyz", auth = false)
    assertEquals(r.status, 200, r.body)
    assertEquals(r.json("status").str, "ready")
  }

  test("health versus readiness: a failing graph makes readyz 503 while healthz stays 200") {
    val s = new Server(withPorts(graph = new FailingGraph(base.graph)))
    try
      assertEquals(s.get("/healthz", auth = false).status, 200)
      val r = s.get("/readyz", auth = false)
      assertEquals(r.status, 503, r.body)
      assertEquals(r.json("status").str, "not_ready")
      val down = r.json("checks").arr.filter(_("state").str == "down").map(_("name").str)
      assertEquals(down.toList, List("graph"))
    finally s.stop()
  }

  // ---- downstream failure mapping --------------------------------------------------------------

  test("downstream failure is 502 DOWNSTREAM_FAILURE") {
    val s = new Server(withPorts(kv = new FailingKv))
    try
      val r = s.post("/v1/query", query(Q1), idem = Some("fail-502"))
      assertEquals(r.status, 502, r.body)
      assertEquals(errorOf(r), "DOWNSTREAM_FAILURE")
      assertEquals(r.json("retryable").bool, true)
    finally s.stop()
  }

  test("downstream timeout is 504 TIMEOUT") {
    val s = new Server(withPorts(kv = guardKv(new SlowKv(base.kv, 3000L), 150L, 5)))
    try
      val r = s.post("/v1/query", query(Q1), idem = Some("fail-504"))
      assertEquals(r.status, 504, r.body)
      assertEquals(errorOf(r), "TIMEOUT")
    finally s.stop()
  }

  test("an open circuit breaker is 503 UNAVAILABLE after the first failure opens it") {
    val s = new Server(withPorts(kv = guardKv(new FailingKv, 1000L, 1)))
    try
      val first = s.post("/v1/query", query(Q1), idem = Some("fail-503-a"))
      assertEquals(first.status, 502, first.body)
      val second = s.post("/v1/query", query(Q1), idem = Some("fail-503-b"))
      assertEquals(second.status, 503, second.body)
      assertEquals(errorOf(second), "UNAVAILABLE")
      assertEquals(second.json("retryable").bool, true)
    finally s.stop()
  }

  // ---- lifecycle -----------------------------------------------------------------------------

  test("full lifecycle: query, evidence list, evidence by id, explain, node") {
    val q = syncServer.post("/v1/query", query(Q1), idem = Some("lifecycle-1"))
    assertEquals(q.status, 200, q.body)
    val queryId = q.json("queryId").str
    val list    = syncServer.get(s"/v1/queries/${RestPaths.encode(queryId)}/evidence")
    assertEquals(list.status, 200, list.body)
    val items = list.json("items").arr.toList
    assert(items.nonEmpty)
    items.take(3).foreach { it =>
      val ev = syncServer.get(s"/v1/evidence/${RestPaths.encode(it("evidenceId").str)}")
      assertEquals(ev.status, 200, ev.body)
      assert(RestJson.read[EvidenceView](ev.body).isRight, ev.body)
    }
    val ex = syncServer.get(s"/v1/explain/${RestPaths.encode(queryId)}")
    assertEquals(ex.status, 200, ex.body)
    assert(RestJson.read[ExplainView](ex.body).isRight, ex.body)
  }
