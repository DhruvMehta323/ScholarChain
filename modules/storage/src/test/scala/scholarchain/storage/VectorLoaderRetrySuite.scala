package scholarchain.storage

import scholarchain.core.*

import StorageFx.*

/** Retry, backoff cap, and non-retryable handling of the vector loader. No real sleeping: every
  * wait goes through a recording `Sleeper`.
  */
class VectorLoaderRetrySuite extends munit.FunSuite:

  private val Idx = VectorIndexes.ResearcherProfile

  private final class RecordingSleeper extends Sleeper:
    val waits                     = scala.collection.mutable.ListBuffer.empty[Long]
    def sleep(millis: Long): Unit = waits += millis

  /** Delegates to a real fake; before delegating, fails according to `plan`. */
  private final class FlakyStore(
      inner: VectorStore,
      plan: (Int, List[VectorEntry]) => Option[StorageError],
  ) extends VectorStore:
    var calls                                                                            = 0
    def putBatch(i: String, vs: List[VectorEntry]): StorageResult[List[VectorPutResult]] =
      calls += 1
      plan(calls, vs) match
        case Some(e) => Left(e)
        case None    => inner.putBatch(i, vs)
    def query(i: String, q: VectorQuery): StorageResult[List[VectorHit]] = inner.query(i, q)
    def get(i: String, k: String): StorageResult[Option[VectorEntry]]    = inner.get(i, k)

  private def fake()          = InMemoryVectorStore(Map(Idx -> 3))
  private def entries(n: Int) = (1 to n).toList.map(i => entry(s"k$i", s"t$i"))
  private val cfg             = StorageRetry(5, 100L, 5000L)

  test("backoff schedule is base * 2^(n-1), capped at maxDelay, and never overflows") {
    val c = StorageRetry(10, 100L, 500L)
    assertEquals(
      (1 to 6).map(Retry.delayMillis(c, _)).toList,
      List(100L, 200L, 400L, 500L, 500L, 500L),
    )
    assertEquals(Retry.delayMillis(c, 100), 500L)
    assertEquals(Retry.delayMillis(StorageRetry(10, 100L, 60000L), 40), 60000L)
    assertEquals(Retry.delayMillis(c, 0), 100L)
  }

  test("a transient failure is retried with growing waits, then succeeds") {
    val sl    = RecordingSleeper()
    val store = FlakyStore(
      fake(),
      (n, _) => if n <= 2 then Some(StorageError.Throttled("slow down")) else None,
    )
    val sink   = InMemoryRejectionSink()
    val report =
      VectorLoader(store, cfg, sl, sink).load(Idx, entries(3)).fold(e => fail(e.toString), identity)
    assertEquals(store.calls, 3)
    assertEquals(sl.waits.toList, List(100L, 200L))
    assertEquals(
      (report.accepted, report.retries, report.failedBatches, report.batches),
      (3, 2, 0, 1),
    )
    assertEquals(sink.all, Nil)
  }

  test("retries are capped at maxAttempts: a permanent outage makes exactly maxAttempts calls") {
    val sl     = RecordingSleeper()
    val store  = FlakyStore(fake(), (_, _) => Some(StorageError.Unavailable("down")))
    val sink   = InMemoryRejectionSink()
    val c      = StorageRetry(3, 100L, 1000L)
    val report =
      VectorLoader(store, c, sl, sink).load(Idx, entries(4)).fold(e => fail(e.toString), identity)
    assertEquals(store.calls, 3)
    assertEquals(sl.waits.toList, List(100L, 200L)) // one fewer wait than attempts
    assertEquals(report.failedBatches, 1)
    assertEquals(report.accepted, 0)
    assertEquals(report.rejected, Map(VectorRejection.ServiceRejected -> 4))
    assertEquals(sink.all.map(_.vectorKey), List("k1", "k2", "k3", "k4"))
    assert(sink.all.forall(_.reason == VectorRejection.ServiceRejected))
  }

  test("every wait of a long outage stays within the configured maximum delay") {
    val sl    = RecordingSleeper()
    val store = FlakyStore(fake(), (_, _) => Some(StorageError.Throttled("x")))
    val c     = StorageRetry(8, 100L, 300L)
    VectorLoader(store, c, sl, InMemoryRejectionSink()).load(Idx, entries(1))
    assertEquals(store.calls, 8)
    assertEquals(sl.waits.toList, List(100L, 200L, 300L, 300L, 300L, 300L, 300L))
    assert(sl.waits.forall(_ <= 300L))
  }

  test("a non-retryable failure is not retried and does not sleep") {
    List[StorageError](
      StorageError.Failed("boom"),
      StorageError.InvalidRequest("bad"),
      StorageError.IntegrityMismatch("x"),
      StorageError.AlreadyExists("x"),
    ).foreach { err =>
      val sl    = RecordingSleeper()
      val store = FlakyStore(fake(), (_, _) => Some(err))
      val sink  = InMemoryRejectionSink()
      // a second batch is needed so that InvalidRequest is not treated as a whole-call argument error
      val es  = entries(501)
      val rep = VectorLoader(store, cfg, sl, sink).load(Idx, es)
      assertEquals(sl.waits.toList, Nil, err.toString)
      assertEquals(store.calls, 2, s"$err: each of the two batches is tried exactly once")
      assertEquals(rep.map(_.failedBatches), Right(2), err.toString)
      assertEquals(sink.all.size, 501, err.toString)
    }
  }

  test("a non-retryable failure of one batch does not stop the next batch") {
    val sl    = RecordingSleeper()
    val store = FlakyStore(
      fake(),
      (_, vs) => if vs.size == 500 then Some(StorageError.Failed("boom")) else None,
    )
    val sink = InMemoryRejectionSink()
    val rep  = VectorLoader(store, cfg, sl, sink)
      .load(Idx, entries(600))
      .fold(e => fail(e.toString), identity)
    assertEquals(store.calls, 2)
    assertEquals(sl.waits.toList, Nil)
    assertEquals(rep.batches, 2)
    assertEquals(rep.failedBatches, 1)
    assertEquals(rep.results.size, 600)
    assertEquals(
      rep.results.take(500).map(_.outcome).distinct,
      List(VectorPutOutcome.Rejected(VectorRejection.ServiceRejected)),
    )
    assertEquals(rep.results.drop(500).map(_.outcome).distinct, List(VectorPutOutcome.Accepted))
    assertEquals(sink.all.size, 500)
  }

  test("an unknown index stops the load immediately with NotFound and no retry") {
    val sl    = RecordingSleeper()
    val store = InMemoryVectorStore(Map("other-v1" -> 3))
    val out   = VectorLoader(store, cfg, sl, InMemoryRejectionSink()).load(Idx, entries(2))
    assertEquals(out, Left(StorageError.NotFound("index")))
    assertEquals(sl.waits.toList, Nil)
  }

  test("an empty load is an invalid request") {
    val out = VectorLoader(fake(), cfg, RecordingSleeper(), InMemoryRejectionSink()).load(Idx, Nil)
    assert(out.left.exists(_.isInstanceOf[StorageError.InvalidRequest]))
  }

  test("loading 1200 vectors makes three batches of 500, 500 and 200 and stores all of them") {
    val store = fake()
    val rep   = VectorLoader(store, cfg, RecordingSleeper(), InMemoryRejectionSink())
      .load(Idx, entries(1200))
      .fold(e => fail(e.toString), identity)
    assertEquals(rep.batches, 3)
    assertEquals(rep.accepted, 1200)
    assertEquals(store.size(Idx), 1200)
  }

  test(
    "reloading the same vectors is idempotent; a changed text under an existing key is a recorded collision"
  ) {
    val sink  = InMemoryRejectionSink()
    val store = InMemoryVectorStore(Map(Idx -> 3), sink)
    val ld    = VectorLoader(store, cfg, RecordingSleeper(), sink)
    assertEquals(ld.load(Idx, entries(5)).map(_.accepted), Right(5))
    assertEquals(ld.load(Idx, entries(5)).map(_.accepted), Right(5))
    assertEquals(store.size(Idx), 5)
    assertEquals(sink.all, Nil)
    val changed = entries(5).updated(2, entry("k3", "different text"))
    val rep     = ld.load(Idx, changed).fold(e => fail(e.toString), identity)
    assertEquals(rep.rejected, Map(VectorRejection.KeyCollision -> 1))
    assertEquals(sink.all.map(_.vectorKey), List("k3"))
    assertEquals(store.size(Idx), 5)
  }

  test(
    "Retry.run: returns the first success without waiting, reports each retry, and honours maxAttempts = 1"
  ) {
    val sl    = RecordingSleeper()
    var calls = 0
    assertEquals(Retry.run(cfg, sl) { calls += 1; Right(7) }, Right(7))
    assertEquals((calls, sl.waits.toList), (1, Nil))

    calls = 0
    val seen = scala.collection.mutable.ListBuffer.empty[(Int, Long)]
    val out  = Retry.run(StorageRetry(1, 100L, 100L), sl, (a, _, d) => seen += ((a, d))) {
      calls += 1
      Left(StorageError.Throttled("t"))
    }
    assertEquals((calls, seen.toList, out.isLeft), (1, Nil, true))

    calls = 0
    Retry.run(StorageRetry(4, 10L, 1000L), sl, (a, _, d) => seen += ((a, d))) {
      calls += 1
      Left(StorageError.Unavailable("u"))
    }
    assertEquals(calls, 4)
    assertEquals(seen.toList, List((1, 10L), (2, 20L), (3, 40L)))
  }
