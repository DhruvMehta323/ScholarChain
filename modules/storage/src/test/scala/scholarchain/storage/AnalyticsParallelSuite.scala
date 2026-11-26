package scholarchain.storage

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scholarchain.core.*

import AnalyticsItems.AnalyticsItem
import LiveLoadFx.*

/** H3: `AnalyticsRepository.putAllParallel` and `LiveLoadParams.itemParallelism`. */
class AnalyticsParallelSuite extends munit.FunSuite:

  private val Table = "identity-clusters"

  private def item(i: Int, variant: String = ""): AnalyticsItem =
    val json = s"""{"size":$i$variant}"""
    AnalyticsItem(Table, f"c-$i%05d", None, json, Hashing.sha256Hex(json.getBytes("UTF-8")))

  private def repoOver(kv: KeyValueStore) = AnalyticsRepository.fromConfig(kv, cfg.tables)

  private def freshInner() = InMemoryKeyValueStore(AnalyticsRepository.schemas(cfg.tables))

  private val direct: (=> StorageResult[WriteResult]) => StorageResult[WriteResult] = op => op

  private val N                   = 1000
  private def conflictAt(i: Int)  = i % 25 == 7
  private def unchangedAt(i: Int) = i % 10 == 3 && !conflictAt(i)

  /** A store pre-seeded with unchanged and conflicting items (the same for every call). */
  private def seeded(): InMemoryKeyValueStore =
    val kv = freshInner()
    val r  = repoOver(kv)
    (0 until N).foreach { i =>
      if conflictAt(i) then r.put(item(i, ",\"other\":1")).fold(e => fail(e.toString), identity)
      else if unchangedAt(i) then r.put(item(i)).fold(e => fail(e.toString), identity)
    }
    kv

  private val items: Vector[AnalyticsItem] = (0 until N).toVector.map(item(_))

  private val expectedConflicts = (0 until N).count(conflictAt).toLong
  private val expectedUnchanged = (0 until N).count(unchangedAt).toLong

  private def ok[A](r: StorageResult[A]): A = r.fold(e => fail(e.toString), identity)

  /** Fails the n-th put (1-based, counted over all threads) with `err`; others pass through. */
  private final class FailingKv(inner: KeyValueStore, n: Int, err: StorageError)
      extends KeyValueStore:
    val puts      = AtomicInteger(0)
    val succeeded = AtomicInteger(0)
    def put(table: String, item: KvItem, c: PutCondition): StorageResult[PutOutcome] =
      val k = puts.incrementAndGet()
      if k == n then Left(err)
      else
        // a put that starts after the failing one waits, so the failing thread can publish its
        // error before the others claim more items (otherwise the bound depends on scheduling)
        if k > n then Thread.sleep(200)
        val r = inner.put(table, item, c)
        if r.isRight then succeeded.incrementAndGet()
        r
    def get(table: String, key: KvKey) = inner.get(table, key)
    def query(
        table: String,
        partition: String,
        sort: Option[SortCondition],
        limit: Int,
        ascending: Boolean,
        token: Option[String],
    ) = inner.query(table, partition, sort, limit, ascending, token)

  // ---- repository ---------------------------------------------------------------------------

  test("parallelism 1 and 16 give the same report as sequential putAll (counts and examples)") {
    val seq = ok(repoOver(seeded()).putAll(items))
    assertEquals(seq.conflicts, expectedConflicts)
    assertEquals(seq.unchanged, expectedUnchanged)
    assertEquals(seq.written, N.toLong - expectedConflicts - expectedUnchanged)
    val p1  = ok(repoOver(seeded()).putAllParallel(items, 1, direct))
    val p16 = ok(repoOver(seeded()).putAllParallel(items, 16, direct))
    assertEquals(p1, seq)
    assertEquals(p16, seq)
    assertEquals(p16.conflictExamples.size, 5)
  }

  test("conflict examples are those of the five lowest item positions, whatever the thread order") {
    // conflicts at positions 7, 32, 57, 82, 107, ...: the example strings differ per item
    val kv   = seeded()
    val r    = repoOver(kv)
    val want = (0 until N).filter(conflictAt).take(5).toList.map { i =>
      r.put(item(i)) match
        case Right(WriteResult.Conflict(reason)) => s"$Table: $reason"
        case other                               => fail(s"item $i should conflict, got $other")
    }
    (1 to 5).foreach { _ =>
      assertEquals(ok(repoOver(kv).putAllParallel(items, 16, direct)).conflictExamples, want)
    }
  }

  test("attempt wraps every single put exactly once") {
    val calls                                                               = AtomicInteger(0)
    val wrap: (=> StorageResult[WriteResult]) => StorageResult[WriteResult] = op =>
      calls.incrementAndGet()
      op
    ok(repoOver(freshInner()).putAllParallel(items, 8, wrap))
    assertEquals(calls.get, N)
  }

  test("abort on the Nth non-retryable error: Left(err), at most N + parallelism puts begin") {
    val par = 16
    val nth = 100
    val err = StorageError.Failed("denied")
    val kv  = FailingKv(freshInner(), nth, err)
    val r   = repoOver(kv).putAllParallel(items, par, direct)
    assertEquals(r, Left(err))
    assert(kv.puts.get >= nth, s"the failing put must have run, puts=${kv.puts.get}")
    assert(kv.puts.get <= nth + par, s"puts=${kv.puts.get} exceeds N + parallelism")
    assert(kv.succeeded.get <= nth + par - 1)
  }

  test("abort on the first item with parallelism 1 stops immediately") {
    val kv = FailingKv(freshInner(), 1, StorageError.Failed("denied"))
    assertEquals(repoOver(kv).putAllParallel(items, 1, direct).left.map(_.code), Left("failed"))
    assertEquals(kv.puts.get, 1)
  }

  test("real concurrency: a latch of 4 inside put completes with parallelism 4") {
    val latch   = CountDownLatch(4)
    val allSeen = AtomicInteger(0)
    val inner   = freshInner()
    val kv      = new KeyValueStore:
      def put(table: String, it: KvItem, c: PutCondition): StorageResult[PutOutcome] =
        latch.countDown()
        if latch.await(10, TimeUnit.SECONDS) then allSeen.incrementAndGet()
        inner.put(table, it, c)
      def get(table: String, key: KvKey) = inner.get(table, key)
      def query(
          table: String,
          partition: String,
          sort: Option[SortCondition],
          limit: Int,
          ascending: Boolean,
          token: Option[String],
      ) = inner.query(table, partition, sort, limit, ascending, token)
    val r = ok(repoOver(kv).putAllParallel(items.take(4), 4, direct))
    assertEquals(r.written, 4L)
    assertEquals(allSeen.get, 4, "all four puts must have been in flight at the same time")
  }

  test("empty input gives an empty report and parallelism is clamped to at least 1") {
    val empty = AnalyticsLoadReport(0L, 0L, 0L, Nil)
    assertEquals(ok(repoOver(freshInner()).putAllParallel(Vector.empty, 16, direct)), empty)
    assertEquals(ok(repoOver(freshInner()).putAllParallel(Vector.empty, 0, direct)), empty)
    assertEquals(ok(repoOver(freshInner()).putAllParallel(items.take(5), 0, direct)).written, 5L)
    assertEquals(ok(repoOver(freshInner()).putAllParallel(items.take(5), -3, direct)).written, 5L)
  }

  test("parallelism greater than the item count writes each item once") {
    val kv = freshInner()
    val r  = ok(repoOver(kv).putAllParallel(items.take(3), 64, direct))
    assertEquals(r, AnalyticsLoadReport(3L, 0L, 0L, Nil))
    assertEquals(ok(repoOver(kv).putAllParallel(items.take(3), 64, direct)).unchanged, 3L)
  }

  // ---- crashes, interrupts and the accounting invariant (M-1) ------------------------------

  /** `attempt` that throws `t` on its k-th call (1-based, over all threads) and counts calls. */
  private def throwingAt(
      k: Int,
      calls: AtomicInteger,
      t: => Throwable,
  ): (=> StorageResult[WriteResult]) => StorageResult[WriteResult] =
    op =>
      val c = calls.incrementAndGet()
      if c == k then throw t
      // a call after the crash waits, so the crashing thread can publish before more items start
      if c > k then Thread.sleep(200)
      op

  private def crashTest(name: String, mk: => Throwable): Unit =
    test(s"a $name thrown by attempt is rethrown as is, no Right, bounded puts") {
      val par    = 16
      val k      = 100
      val calls  = AtomicInteger(0)
      val thrown = mk
      val got    =
        try Right(repoOver(freshInner()).putAllParallel(items, par, throwingAt(k, calls, thrown)))
        catch case t: Throwable => Left(t)
      got match
        case Left(t)  => assert(t eq thrown, s"expected the exact $thrown, got $t")
        case Right(r) => fail(s"a crash must not produce a result, got $r")
      assert(calls.get >= k && calls.get <= k + par, s"attempts=${calls.get}")
    }

  crashTest("LinkageError", new LinkageError("boom"))
  crashTest("OutOfMemoryError", new OutOfMemoryError("boom"))

  test("an InterruptedException is rethrown and the caller's interrupt flag is set") {
    val calls  = AtomicInteger(0)
    val thrown = new InterruptedException("stop")
    try
      val got =
        try Right(repoOver(freshInner()).putAllParallel(items, 8, throwingAt(10, calls, thrown)))
        catch case t: Throwable => Left(t)
      got match
        case Left(t)  => assert(t eq thrown, s"got $t")
        case Right(r) => fail(s"no result expected, got $r")
      assert(Thread.currentThread().isInterrupted, "the interrupt flag must be re-set")
    finally Thread.interrupted() // clear the flag so it cannot leak into other tests
  }

  test("a RuntimeException is still Left(Failed(\"item write: RuntimeException\"))") {
    val calls = AtomicInteger(0)
    val r     = repoOver(freshInner())
      .putAllParallel(items, 16, throwingAt(50, calls, new RuntimeException("x")))
    assertEquals(r, Left(StorageError.Failed("item write: RuntimeException")))
  }

  test("count invariant: written + unchanged + conflicts == items for 0, 1 and 1000 items") {
    List(0, 1, N).foreach { size =>
      List(1, 16).foreach { par =>
        val r = ok(repoOver(seeded()).putAllParallel(items.take(size), par, direct))
        assertEquals(r.written + r.unchanged + r.conflicts, size.toLong, s"size=$size par=$par")
      }
    }
  }

  test("a store error wins over the accounted-for error") {
    val err = StorageError.Failed("denied")
    List(1, 16).foreach { par =>
      val kv = FailingKv(freshInner(), 5, err)
      assertEquals(repoOver(kv).putAllParallel(items, par, direct), Left(err), s"par=$par")
    }
  }

  // ---- LiveLoad.run -------------------------------------------------------------------------

  private def p(par: Int): LiveLoadParams = params().copy(itemParallelism = par)

  private def portsFor(kv: KeyValueStore): (Rig, LiveLoadPorts) =
    val r = rig()
    upload(r.store, Fx().render)
    (r, LiveLoadPorts(r.store, r.loader, kv, r.sleeper, r.fakeClock.clock))

  private def writtenTotal(rep: LiveLoadReport): Long =
    prov(rep)("items")("written").obj.values.map(_("written").num.toLong).sum

  test("LiveLoad.run: the itemParallelism default is 16") {
    assertEquals(params().itemParallelism, 16)
  }

  test("LiveLoad.run: one Throttled put is retried, Completed, provenance written == item count") {
    val r0    = rig()
    val fired = AtomicBoolean(false)
    val inner = r0.kvInner
    val kv    = new KeyValueStore:
      def put(table: String, it: KvItem, c: PutCondition): StorageResult[PutOutcome] =
        if fired.compareAndSet(false, true) then Left(StorageError.Throttled("slow down"))
        else inner.put(table, it, c)
      def get(table: String, key: KvKey) = inner.get(table, key)
      def query(
          table: String,
          partition: String,
          sort: Option[SortCondition],
          limit: Int,
          ascending: Boolean,
          token: Option[String],
      ) = inner.query(table, partition, sort, limit, ascending, token)
    upload(r0.store, Fx().render)
    val ports = LiveLoadPorts(r0.store, r0.loader, kv, r0.sleeper, r0.fakeClock.clock)
    val rep   = LiveLoad.run(ports, cfg, Region, p(16))
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    assertEquals(writtenTotal(rep), ItemTotal)
    assertEquals(prov(rep)("items")("total").num.toLong, ItemTotal)
    assert(fired.get)
  }

  test("LiveLoad.run: provenance records the clamped parameters.itemParallelism") {
    def recorded(par: Option[Int]): Long =
      val r = rig()
      upload(r.store, Fx().render)
      val rep = r.run(par.fold(params())(x => p(x)))
      assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
      prov(rep)("parameters")("itemParallelism").num.toLong
    assertEquals(recorded(None), 16L)
    assertEquals(recorded(Some(8)), 8L)
    assertEquals(recorded(Some(0)), 1L)
    assertEquals(recorded(Some(-5)), 1L)
    assertEquals(recorded(Some(1000)), 64L)
  }

  test(
    "LiveLoad.run: provenance of parallelism 1 and 16 differs only in itemParallelism and durations"
  ) {
    def run(par: Int) =
      val r = rig()
      upload(r.store, Fx().render)
      val rep = r.run(p(par))
      assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
      val j = prov(rep)
      j.obj.remove("durationsMillis")
      j("parameters").obj.remove("itemParallelism")
      (j, r.kv.puts)
    val (a, pa) = run(1)
    val (b, pb) = run(16)
    assertEquals(a, b)
    assertEquals(pa.toLong, ItemTotal)
    assertEquals(pb.toLong, ItemTotal)
  }

  test("LiveLoad.run: out-of-range parallelism (0, -5, 1000) is clamped and does not hang") {
    List(0, -5, 1000).foreach { par =>
      val r = rig()
      upload(r.store, Fx().render)
      val rep = r.run(p(par))
      assertEquals(rep.status, LiveLoadStatus.Completed, s"par=$par: ${rep.detail}")
      assertEquals(writtenTotal(rep), ItemTotal, s"par=$par")
    }
  }

  test("LiveLoad.run: a non-retryable put error is StoreError under parallelism 16") {
    val r = rig(putError = Some(StorageError.Failed("denied")))
    upload(r.store, Fx().render)
    val rep = r.run(p(16))
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assertEquals(rep.exitCode, 1)
  }
  private def throwingKv(inner: KeyValueStore, t: => Throwable): KeyValueStore =
    new KeyValueStore:
      def put(table: String, it: KvItem, c: PutCondition): StorageResult[PutOutcome] = throw t
      def get(table: String, key: KvKey) = inner.get(table, key)
      def query(
          table: String,
          partition: String,
          sort: Option[SortCondition],
          limit: Int,
          ascending: Boolean,
          token: Option[String],
      ) = inner.query(table, partition, sort, limit, ascending, token)

  test("LiveLoad.run: a LinkageError thrown by a put is not reported as Completed") {
    val r = rig()
    upload(r.store, Fx().render)
    val ports = LiveLoadPorts(
      r.store,
      r.loader,
      throwingKv(r.kvInner, new LinkageError("boom")),
      r.sleeper,
      r.fakeClock.clock,
    )
    val got =
      try Right(LiveLoad.run(ports, cfg, Region, p(16)))
      catch case t: Throwable => Left(t)
    got match
      case Left(_: LinkageError) => ()
      case Right(rep)  => assertNotEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
      case Left(other) => fail(s"unexpected throwable $other")
  }

  test("LiveLoad.run: a RuntimeException thrown by a put is StoreError, not Completed") {
    val r = rig()
    upload(r.store, Fx().render)
    val ports = LiveLoadPorts(
      r.store,
      r.loader,
      throwingKv(r.kvInner, new RuntimeException("boom")),
      r.sleeper,
      r.fakeClock.clock,
    )
    val rep = LiveLoad.run(ports, cfg, Region, p(16))
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
  }
end AnalyticsParallelSuite
