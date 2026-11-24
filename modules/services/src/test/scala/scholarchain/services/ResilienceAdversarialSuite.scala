package scholarchain.services

import java.util.concurrent.atomic.AtomicInteger

import scholarchain.core.*
import scholarchain.core.rest.*

/** Phase 11 T: failure injection through the downstream guards with fakes: Neptune timeout,
  * DynamoDB conditional-write conflict, S3 Vectors throttling and transient failure, replay.
  * Nothing here leaves the machine.
  */
class ResilienceAdversarialSuite extends munit.FunSuite:

  private def guard(
      timeout: Long = 300L,
      attempts: Int = 3,
      threshold: Int = 5,
      openMillis: Long = 60000L,
      now: () => Long = () => System.currentTimeMillis(),
  ) = new DownstreamGuard(
    "adv",
    timeout,
    attempts,
    new CircuitBreaker(threshold, openMillis, now),
    backoffMillis = 1L,
  )

  private val gq = GraphQuery(GraphTemplates.Neighbors, Map.empty, QueryBounds(1, 1, 100L))
  private val vq = VectorQuery(Vector(1.0f), 1)

  private final class CountingGraph(f: Int => StorageResult[GraphResult]) extends GraphStore:
    val calls                                            = new AtomicInteger(0)
    def query(q: GraphQuery): StorageResult[GraphResult] = f(calls.incrementAndGet())
    def submitBulkLoad(r: BulkLoadRequest): StorageResult[BulkLoadHandle] =
      Right(BulkLoadHandle("x"))
    def bulkLoadStatus(h: BulkLoadHandle): StorageResult[BulkLoadStatus] =
      Left(StorageError.Failed("n/a"))

  private final class CountingVectors(f: Int => StorageResult[Option[VectorEntry]])
      extends VectorStore:
    val calls = new AtomicInteger(0)
    def putBatch(i: String, v: List[VectorEntry]): StorageResult[List[VectorPutResult]] =
      Right(Nil)
    def query(i: String, q: VectorQuery): StorageResult[List[VectorHit]] = Right(Nil)
    def get(i: String, k: String): StorageResult[Option[VectorEntry]] = f(calls.incrementAndGet())

  private final class CountingKv(put: Int => StorageResult[PutOutcome]) extends KeyValueStore:
    val puts                                                                  = new AtomicInteger(0)
    def put(t: String, i: KvItem, c: PutCondition): StorageResult[PutOutcome] =
      put(puts.incrementAndGet())
    def get(t: String, k: KvKey): StorageResult[Option[KvItem]] = Right(None)
    def query(
        t: String,
        p: String,
        s: Option[SortCondition],
        l: Int,
        a: Boolean,
        c: Option[String],
    ): StorageResult[KvPage] = Left(StorageError.Failed("n/a"))

  private val item = KvItem(KvKey("p", Some("s")), Map("a" -> "b"))

  // ---- Neptune timeout --------------------------------------------------------------------------

  test("a graph query slower than the guard timeout is Unavailable(timeout) and maps to TIMEOUT") {
    val g = new GuardedGraph(
      new CountingGraph(_ => { Thread.sleep(2000); Right(GraphResult(Nil, false)) }),
      guard(timeout = 100L, attempts = 1),
    )
    val r = g.query(gq)
    assertEquals(r, Left(StorageError.Unavailable(Downstream.TimeoutReason)))
    assertEquals(Downstream.toFailure(r.left.toOption.get).code, ApiErrorCode.Timeout)
  }

  test("a timed-out graph read is retried up to maxAttempts, then reported once as a timeout") {
    val inner = new CountingGraph(_ => { Thread.sleep(2000); Right(GraphResult(Nil, false)) })
    val p     = Probe.begin()
    try
      new GuardedGraph(inner, guard(timeout = 80L, attempts = 2)).query(gq)
      assertEquals(inner.calls.get, 2)
      assertEquals(p.timeouts, 1)
      assertEquals(p.retries, 1)
      assertEquals(p.asFailure.map(_.code), Some(ApiErrorCode.Timeout))
    finally Probe.end()
  }

  test("a timeout is never reported with the inner exception text or a path") {
    val inner = new CountingGraph(_ => throw new RuntimeException("jdbc:secret-host:/etc/hidden"))
    val r     = new GuardedGraph(inner, guard(attempts = 1)).query(gq)
    assertEquals(r, Left(StorageError.Failed("RuntimeException")))
    assert(!Downstream.toFailure(r.left.toOption.get).message.contains("secret"))
  }

  test("a graph that recovers on the second attempt after a transient Unavailable returns Right") {
    val inner = new CountingGraph(n =>
      if n == 1 then Left(StorageError.Unavailable("neptune-restarting"))
      else Right(GraphResult(Nil, false))
    )
    val r = new GuardedGraph(inner, guard()).query(gq)
    assertEquals(r, Right(GraphResult(Nil, false)))
    assertEquals(inner.calls.get, 2)
  }

  test("the guarded graph is read-only: a bulk load through it is refused without a call") {
    val inner = new CountingGraph(_ => Right(GraphResult(Nil, false)))
    val r     =
      new GuardedGraph(inner, guard()).submitBulkLoad(BulkLoadRequest("s3://x/y/", "csv", "r"))
    assertEquals(r, Left(StorageError.InvalidRequest("read-only")))
  }

  // ---- DynamoDB conditional-write conflict -------------------------------------------------------

  test("a conditional-write conflict is an answer: it does not count toward the breaker") {
    val kv = new CountingKv(_ => Right(PutOutcome.ConditionFailed(Some(item))))
    val g  = guard(threshold = 2)
    val gk = new GuardedKv(kv, g)
    (1 to 5).foreach(_ =>
      assertEquals(
        gk.put("t", item, PutCondition.NotExists),
        Right(PutOutcome.ConditionFailed(Some(item))),
      )
    )
    assert(!g.breaker.isOpen)
    assertEquals(kv.puts.get, 5)
  }

  test("a throttled conditional write is never retried (a retry could double-apply)") {
    val kv = new CountingKv(_ => Left(StorageError.Throttled("provisioned throughput")))
    val r  = new GuardedKv(kv, guard()).put("t", item, PutCondition.NotExists)
    assertEquals(r, Left(StorageError.Throttled("provisioned throughput")))
    assertEquals(kv.puts.get, 1)
  }

  test(
    "replaying the same conditional write: first Written, second ConditionFailed with the existing item"
  ) {
    val store = scala.collection.concurrent.TrieMap.empty[KvKey, KvItem]
    val kv    = new KeyValueStore:
      def put(t: String, i: KvItem, c: PutCondition) =
        store.putIfAbsent(i.key, i) match
          case None    => Right(PutOutcome.Written)
          case Some(e) => Right(PutOutcome.ConditionFailed(Some(e)))
      def get(t: String, k: KvKey) = Right(store.get(k))
      def query(
          t: String,
          p: String,
          s: Option[SortCondition],
          l: Int,
          a: Boolean,
          c: Option[String],
      ) = Right(KvPage(Nil, None))
    val gk = new GuardedKv(kv, guard())
    assertEquals(gk.put("t", item, PutCondition.NotExists), Right(PutOutcome.Written))
    assertEquals(
      gk.put("t", item, PutCondition.NotExists),
      Right(PutOutcome.ConditionFailed(Some(item))),
    )
  }

  // ---- S3 Vectors throttling and transient failure ----------------------------------------------

  test("a throttled vector get is retried and succeeds on the third attempt") {
    val inner = new CountingVectors(n =>
      if n < 3 then Left(StorageError.Throttled("slow down")) else Right(None)
    )
    val r = new GuardedVectors(inner, guard(attempts = 3)).get("idx", "k")
    assertEquals(r, Right(None))
    assertEquals(inner.calls.get, 3)
  }

  test("a permanently throttled vector get stops at maxAttempts and maps to DOWNSTREAM_FAILURE") {
    val inner = new CountingVectors(_ => Left(StorageError.Throttled("slow down")))
    val r     = new GuardedVectors(inner, guard(attempts = 2)).get("idx", "k")
    assertEquals(inner.calls.get, 2)
    assertEquals(Downstream.toFailure(r.left.toOption.get).code, ApiErrorCode.DownstreamFailure)
  }

  test("a vector NotFound is an answer: no retry, no breaker failure") {
    val inner = new CountingVectors(_ => Left(StorageError.NotFound("index")))
    val g     = guard(threshold = 1)
    val r     = new GuardedVectors(inner, g).get("idx", "k")
    assertEquals(inner.calls.get, 1)
    assertEquals(Downstream.toFailure(r.left.toOption.get).code, ApiErrorCode.NotFound)
    assert(!g.breaker.isOpen)
  }

  test("the guarded vector store is read-only: putBatch is refused and never reaches the store") {
    val inner = new CountingVectors(_ => Right(None))
    val r     = new GuardedVectors(inner, guard()).putBatch("idx", Nil)
    assertEquals(r, Left(StorageError.InvalidRequest("read-only")))
  }

  // ---- circuit breaker -----------------------------------------------------------------------------

  test("the breaker opens after the threshold and then no call reaches the store") {
    val inner = new CountingGraph(_ => Left(StorageError.Failed("boom")))
    val g     = guard(attempts = 1, threshold = 2)
    val gg    = new GuardedGraph(inner, g)
    gg.query(gq); gg.query(gq)
    val before = inner.calls.get
    val r      = gg.query(gq)
    assertEquals(r, Left(StorageError.Unavailable(Downstream.OpenReason)))
    assertEquals(inner.calls.get, before)
    assertEquals(Downstream.toFailure(r.left.toOption.get).code, ApiErrorCode.Unavailable)
  }

  test("after the open interval exactly one probe is let through; success closes the breaker") {
    var t     = 1000L
    val inner = new CountingGraph(n =>
      if n <= 1 then Left(StorageError.Failed("boom")) else Right(GraphResult(Nil, false))
    )
    val g  = guard(attempts = 1, threshold = 1, openMillis = 500L, now = () => t)
    val gg = new GuardedGraph(inner, g)
    gg.query(gq)
    assert(g.breaker.isOpen)
    t += 600L
    assertEquals(gg.query(gq), Right(GraphResult(Nil, false)))
    assert(!g.breaker.isOpen)
  }

  test("a failed half-open probe re-opens the breaker") {
    var t     = 1000L
    val inner = new CountingGraph(_ => Left(StorageError.Failed("boom")))
    val g     = guard(attempts = 1, threshold = 1, openMillis = 500L, now = () => t)
    val gg    = new GuardedGraph(inner, g)
    gg.query(gq)
    t += 600L
    gg.query(gq) // probe fails
    assert(g.breaker.isOpen)
    assertEquals(gg.query(gq), Left(StorageError.Unavailable(Downstream.OpenReason)))
  }

  // ---- reason codes ---------------------------------------------------------------------------------

  test("readiness reason codes carry no downstream text") {
    assertEquals(
      Downstream.reasonCode(StorageError.Unavailable(Downstream.TimeoutReason)),
      "timeout",
    )
    assertEquals(
      Downstream.reasonCode(StorageError.Unavailable(Downstream.OpenReason)),
      "circuit_open",
    )
    assertEquals(Downstream.reasonCode(StorageError.Failed("host=10.0.0.5 secret")), "unavailable")
    assertEquals(Downstream.reasonCode(StorageError.Throttled("x")), "unavailable")
  }
