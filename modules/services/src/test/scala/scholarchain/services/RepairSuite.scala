package scholarchain.services

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.{CompletableFuture, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scholarchain.core.*
import scholarchain.core.rest.*

import ApiHarness.*
import RepairHarness.*

/** Phase 10 repair R4: F7 (failures through the workflow with no idempotency key), F2 (idempotency
  * leases, expiry, retryable failures, async deadline), F3 (bounded queues, health not queued), F5
  * (abstention is visible) and F6 (unsupported options are 422). A failure is a production defect
  * in `modules/services` (owner service-engineer) unless the message says otherwise.
  */
class RepairSuite extends munit.FunSuite:

  private def errorOf(r: Reply): String = r.json("error").str

  private val GraphQ =
    "Who are the direct coauthors of Antonina Laposhina, and how many joint publications do they share?"
  private val PathQ =
    "What is the shortest collaboration path from Huan Tian to Zheng Wang 0045, and which works support each hop?"
  private val ComponentQ = "Which connected component is Ben Jeurissen in, and how large is it?"

  private val Abstain = "How many coauthors does Bo Zhang have?"

  // ---- F7: failure mapping through the workflow, no Idempotency-Key ----------------------------

  test("F7: a guarded failing graph is 502 DOWNSTREAM_FAILURE, not a 200 abstention") {
    val s =
      new Tuned(withPorts(graph = guardGraph(new FailingGraph(base.graph), 2000L, 1, 1000)), cfg())
    try
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 502, r.body)
      assertEquals(errorOf(r), "DOWNSTREAM_FAILURE")
      assertEquals(r.json("retryable").bool, true)
    finally s.stop()
  }

  test("F7: a guarded slow graph is 504 TIMEOUT") {
    val s = new Tuned(
      withPorts(graph = guardGraph(new SlowGraph(base.graph, 3000L), 150L, 1, 1000)),
      cfg(),
    )
    try
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 504, r.body)
      assertEquals(errorOf(r), "TIMEOUT")
    finally s.stop()
  }

  test("F7: an open breaker on the graph is 503 UNAVAILABLE") {
    val guard = new DownstreamGuard("test-graph", 1000L, 1, new CircuitBreaker(1, 60000L))
    guard.breaker.failure() // opens the breaker (threshold 1)
    val s = new Tuned(withPorts(graph = new GuardedGraph(base.graph, guard)), cfg())
    try
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 503, r.body)
      assertEquals(errorOf(r), "UNAVAILABLE")
      assertEquals(r.json("retryable").bool, true)
    finally s.stop()
  }

  test("F7: an asynchronous job whose workflow fails ends FAILED with the downstream error") {
    val rt = withPorts(graph = guardGraph(new FailingGraph(base.graph), 2000L, 1, 1000))
    val s  = new Tuned(rt, cfg(0L))
    try
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 202, r.body)
      val done = waitJobState(s, r.json("jobId").str)
      assertEquals(done("state").str, "FAILED", done.toString)
      assertEquals(done("error")("error").str, "DOWNSTREAM_FAILURE", done.toString)
    finally s.stop()
  }

  // ---- F2: idempotency records ------------------------------------------------------------------

  private val Auth = new ApiKeyAuth(Some(PublicKey))

  private def pkFor(key: String): String =
    RestKeys.idempotencyPartition(Auth.scopeOf(Some(PublicKey)), key)

  private def hashOf(body: String): String =
    QueryRequest.requestHash(QueryRequest.parse(body).fold(e => fail(s"$e"), identity))

  private def iso(ms: Long): String =
    Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString

  private def record(
      pk: String,
      body: String,
      state: IdempotencyState,
      createdMs: Long,
      expiresSec: Long,
      error: Option[ApiError] = None,
  ): IdempotencyRecord =
    IdempotencyRecord(
      SchemaVersion.Current,
      pk.stripPrefix(RestKeys.IdempotencyPrefix),
      hashOf(body),
      "query:" + "a" * 64,
      state,
      None,
      None,
      error,
      iso(createdMs),
      expiresSec,
    )

  private def seed(pk: String, rec: IdempotencyRecord): Unit =
    val json = Wire.write(rec).fold(e => fail(s"seed record invalid: $e"), identity)
    val out  = base.kv.put(
      base.tables.querySession,
      KvItem(
        KvKey(pk),
        Map("payload" -> json, "payloadHash" -> Hashing.textHash(json)),
        Some(rec.expiresAtEpochSeconds),
      ),
      PutCondition.Always,
    )
    assert(out.isRight, out.toString)

  private def stored(pk: String): IdempotencyRecord =
    val item = base.kv
      .get(base.tables.querySession, KvKey(pk))
      .fold(e => fail(s"read failed: $e"), identity)
      .getOrElse(fail("no stored record"))
    Wire.read[IdempotencyRecord](item.attributes("payload")).fold(e => fail(s"$e"), identity)

  private val nowSec = FixedNow / 1000

  test("F2: a recent in_progress record (lease running) is 409 INCOMPATIBLE_STATE, retryable") {
    val pk   = pkFor("f2-recent-lease")
    val body = query(Q1)
    seed(pk, record(pk, body, IdempotencyState.InProgress, FixedNow - 1000L, nowSec + 3600))
    val s = new Tuned(base, cfg())
    try
      val r = s.post("/v1/query", body, idem = Some("f2-recent-lease"))
      assertEquals(r.status, 409, r.body)
      assertEquals(errorOf(r), "INCOMPATIBLE_STATE")
      assertEquals(r.json("retryable").bool, true)
    finally s.stop()
  }

  test("F2: an in_progress record whose lease expired is reclaimed and the query runs") {
    val pk   = pkFor("f2-expired-lease")
    val body = query(Q1)
    // lease = sync deadline (60 s) + 10 s; created 200 s ago, record itself valid for an hour
    seed(pk, record(pk, body, IdempotencyState.InProgress, FixedNow - 200000L, nowSec + 3600))
    val s = new Tuned(base, cfg())
    try
      val r = s.post("/v1/query", body, idem = Some("f2-expired-lease"))
      assertEquals(r.status, 200, r.body)
      assertEquals(r.json("mode").str, "synchronous")
      assertEquals(stored(pk).state, IdempotencyState.Completed)
    finally s.stop()
  }

  test("F2: a record past expiresAtEpochSeconds is ignored (lease still running, runs anyway)") {
    val pk   = pkFor("f2-expired-record")
    val body = query(Q1)
    // lease 130 s (deadline 120 s + 10 s) still running 65 s after creation, but the record expired
    seed(pk, record(pk, body, IdempotencyState.InProgress, FixedNow - 65000L, nowSec - 5))
    val s = new Tuned(base, cfg(120000L))
    try
      val r = s.post("/v1/query", body, idem = Some("f2-expired-record"))
      assertEquals(r.status, 200, r.body)
      assertEquals(stored(pk).state, IdempotencyState.Completed)
    finally s.stop()
  }

  test("F2: a failed run with a key stores a retryable error and a repeat runs again") {
    val failing = new AtomicBoolean(true)
    val rt = withPorts(graph = guardGraph(new ToggleGraph(base.graph, failing), 2000L, 1, 1000))
    val s  = new Tuned(rt, cfg())
    val pk = pkFor("f2-retry-after-failure")
    try
      val first = s.post("/v1/query", query(GraphQ), idem = Some("f2-retry-after-failure"))
      assertEquals(first.status, 502, first.body)
      val rec = stored(pk)
      assertEquals(rec.state, IdempotencyState.Failed)
      assertEquals(rec.error.map(_.retryable), Some(true), rec.toString)
      failing.set(false)
      val second = s.post("/v1/query", query(GraphQ), idem = Some("f2-retry-after-failure"))
      assertEquals(second.status, 200, second.body)
      assertEquals(stored(pk).state, IdempotencyState.Completed)
    finally s.stop()
  }

  test("F2: an async run past its deadline ends FAILED with error TIMEOUT") {
    val rt = withPorts(graph = guardGraph(new SlowGraph(base.graph, 4000L), 10000L, 1, 1000))
    val s  = new Tuned(rt, cfg(0L).copy(asyncDeadlineMillis = 300L))
    try
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 202, r.body)
      val jobId = r.json("jobId").str
      val done  = waitJobState(s, jobId)
      assertEquals(done("state").str, "FAILED", done.toString)
      assertEquals(done("error")("error").str, "TIMEOUT", done.toString)
    finally s.stop()
  }

  private def waitJobState(s: Tuned, jobId: String): ujson.Value =
    var last = s.get(s"/v1/jobs/${RestPaths.encode(jobId)}").json
    var n    = 0
    while !Set("SUCCEEDED", "FAILED").contains(last("state").str) && n < 200 do
      Thread.sleep(50)
      last = s.get(s"/v1/jobs/${RestPaths.encode(jobId)}").json
      n += 1
    last

  // ---- F3: bounded queues, health not queued ---------------------------------------------------

  test("F3: with 1 running and 1 queued, a third concurrent query is 429 RATE_LIMITED") {
    val bg = new BlockingGraph(base.graph)
    val s  =
      new Tuned(withPorts(graph = bg), cfg().copy(maxConcurrentQueries = 1, maxQueuedQueries = 1))
    val ex = Executors.newFixedThreadPool(2)
    try
      val f1 = CompletableFuture.supplyAsync(() => s.post("/v1/query", query(GraphQ)), ex)
      assert(waitUntil(10000L)(bg.entered.get >= 1), "first query never reached the graph")
      val f2 = CompletableFuture.supplyAsync(() => s.post("/v1/query", query(PathQ)), ex)
      Thread.sleep(500L) // let the second query reach the queue
      val third = s.post("/v1/query", query(ComponentQ))
      assertEquals(third.status, 429, third.body)
      assertEquals(errorOf(third), "RATE_LIMITED")
      assertEquals(third.json("retryable").bool, true)
      bg.gate.countDown()
      assertEquals(f1.get(60, TimeUnit.SECONDS).status, 200)
      assertEquals(f2.get(60, TimeUnit.SECONDS).status, 200)
    finally
      bg.gate.countDown()
      ex.shutdownNow()
      s.stop()
  }

  test("F3: /healthz answers 200 promptly while every HTTP worker is blocked on a query") {
    val bg = new BlockingGraph(base.graph)
    val s  = new Tuned(
      withPorts(graph = bg),
      cfg().copy(maxConcurrentQueries = 2, maxQueuedQueries = 2),
      threads = 2,
      queueCapacity = 1,
      healthThreads = 2,
    )
    val ex = Executors.newFixedThreadPool(2)
    try
      val f1 = CompletableFuture.supplyAsync(() => s.post("/v1/query", query(GraphQ)), ex)
      val f2 = CompletableFuture.supplyAsync(() => s.post("/v1/query", query(PathQ)), ex)
      assert(waitUntil(10000L)(bg.entered.get >= 2), "queries never reached the graph")
      val t0 = System.nanoTime()
      val h  = s.get("/healthz", auth = false)
      val ms = (System.nanoTime() - t0) / 1000000L
      assertEquals(h.status, 200, h.body)
      assert(ms < 2000L, s"healthz took $ms ms while queries were blocked")
      bg.gate.countDown()
      assertEquals(f1.get(60, TimeUnit.SECONDS).status, 200)
      assertEquals(f2.get(60, TimeUnit.SECONDS).status, 200)
    finally
      bg.gate.countDown()
      ex.shutdownNow()
      s.stop()
  }

  // ---- F5: abstention is visible ---------------------------------------------------------------

  test("F5: an abstained query is 200 with abstained=true, a reason token and no claims") {
    val s = new Tuned(base, cfg())
    try
      val r = s.post("/v1/query", query(Abstain))
      assertEquals(r.status, 200, r.body)
      assertEquals(r.json("abstained").bool, true, r.body)
      val reason = r.json("abstainReason").str
      assert(reason.nonEmpty, r.body)
      assertEquals(r.json("claims").arr.size, 0, r.body)
      assert(RestJson.read[SyncQueryResponse](r.body).isRight, r.body)
      val line = s.logs
        .map(ujson.read(_))
        .find(j => j.obj.get("event").exists(_.str == "query.completed"))
        .getOrElse(fail("no query.completed log line"))
      assertEquals(line("abstained").toString.replace("\"", ""), "true", line.toString)
      assertEquals(line("abstainReason").str, reason)
    finally s.stop()
  }

  test("F5: a verified answer has abstained=false, no abstainReason, and logs abstained=false") {
    val s = new Tuned(base, cfg())
    try
      val r = s.post("/v1/query", query(Q1))
      assertEquals(r.status, 200, r.body)
      assertEquals(r.json("abstained").bool, false, r.body)
      assert(!r.json.obj.contains("abstainReason"), r.body)
      assert(r.json("claims").arr.nonEmpty, r.body)
      val line = s.logs
        .map(ujson.read(_))
        .find(j => j.obj.get("event").exists(_.str == "query.completed"))
        .getOrElse(fail("no query.completed log line"))
      assertEquals(line("abstained").toString.replace("\"", ""), "false", line.toString)
    finally s.stop()
  }

  // ---- F6: unsupported options are refused with 422 ---------------------------------------------

  private def withOption(section: String, field: String, v: ujson.Value): String =
    ujson.write(ujson.Obj("question" -> Q1, section -> ujson.Obj(field -> v)))

  private val refused = List(
    ("graphLimit=0", withOption("retrieval", "graphLimit", 0), "UNSUPPORTED_RETRIEVAL_MODE"),
    ("vectorLimit=5", withOption("retrieval", "vectorLimit", 5), "UNSUPPORTED_RETRIEVAL_MODE"),
    (
      "includeEvidence=false",
      withOption("output", "includeEvidence", false),
      "UNSUPPORTED_CONSTRAINT",
    ),
    (
      "includeTeamSize=true",
      withOption("analysis", "includeTeamSize", true),
      "UNSUPPORTED_CONSTRAINT",
    ),
    ("includePaths=true", withOption("analysis", "includePaths", true), "UNSUPPORTED_CONSTRAINT"),
  )

  refused.foreach { (name, body, code) =>
    test(s"F6: $name is 422 $code") {
      val s = new Tuned(base, cfg())
      try
        val r = s.post("/v1/query", body)
        assertEquals(r.status, 422, r.body)
        assertEquals(errorOf(r), code, r.body)
        assertEquals(r.json("retryable").bool, false)
      finally s.stop()
    }
  }
