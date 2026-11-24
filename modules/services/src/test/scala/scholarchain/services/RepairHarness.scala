package scholarchain.services

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scholarchain.core.*
import scholarchain.core.rest.*

import ApiHarness.*

/** Phase 10 repair R4 fakes: a tunable query-api server and graph ports that fail, stall or block.
  * Localhost only, dummy keys, no AWS client.
  */
object RepairHarness:

  /** A query-api on an ephemeral port with explicit settings, clock and HTTP pool sizes. */
  final class Tuned(
      rt: ServiceRuntime,
      cfg: ServiceSettings,
      clock: () => Long = () => FixedNow,
      threads: Int = 16,
      queueCapacity: Int = 64,
      healthThreads: Int = 2,
  ):
    private val sink = scala.collection.mutable.ListBuffer.empty[String]
    val log          = new JsonLog("query-api", l => sink.synchronized { sink += l; () })
    val ids          = new IdGen(true)
    private val svc  = new QueryService(rt, cfg, ids, log, clock)
    private val app  = new HttpApp(
      "query-api",
      "127.0.0.1",
      0,
      log,
      ids,
      RestLimits.MaxBodyBytes,
      new QueryApiRoutes(rt, svc, new ApiKeyAuth(Some(PublicKey))).handle,
      threads,
      queueCapacity,
      healthThreads,
    )
    val port: Int = app.start()

    def logs: List[String] = sink.synchronized(sink.toList)
    def stop(): Unit       = app.stop()

    def get(path: String, auth: Boolean = true): Reply =
      call(port, "GET", path, None, if auth then Map("X-API-Key" -> PublicKey) else Map.empty)

    def post(path: String, body: String, idem: Option[String] = None): Reply =
      call(
        port,
        "POST",
        path,
        Some(body),
        Map("X-API-Key" -> PublicKey) ++ idem.map("Idempotency-Key" -> _),
      )

  def cfg(deadlineMillis: Long = 60000L): ServiceSettings = settings(deadlineMillis)

  final class SlowGraph(inner: GraphStore, millis: Long) extends GraphStore:
    def query(q: GraphQuery): StorageResult[GraphResult] =
      Thread.sleep(millis)
      inner.query(q)
    def submitBulkLoad(r: BulkLoadRequest): StorageResult[BulkLoadHandle] = inner.submitBulkLoad(r)
    def bulkLoadStatus(h: BulkLoadHandle): StorageResult[BulkLoadStatus]  = inner.bulkLoadStatus(h)

  /** Fails while `failing` is set, delegates otherwise. */
  final class ToggleGraph(inner: GraphStore, val failing: AtomicBoolean) extends GraphStore:
    def query(q: GraphQuery): StorageResult[GraphResult] =
      if failing.get then Left(StorageError.Failed("fake-failure")) else inner.query(q)
    def submitBulkLoad(r: BulkLoadRequest): StorageResult[BulkLoadHandle] = inner.submitBulkLoad(r)
    def bulkLoadStatus(h: BulkLoadHandle): StorageResult[BulkLoadStatus]  = inner.bulkLoadStatus(h)

  /** Blocks every graph query until `gate` opens. */
  final class BlockingGraph(inner: GraphStore) extends GraphStore:
    val gate                                             = new CountDownLatch(1)
    val entered                                          = new AtomicInteger(0)
    def query(q: GraphQuery): StorageResult[GraphResult] =
      entered.incrementAndGet()
      gate.await()
      inner.query(q)
    def submitBulkLoad(r: BulkLoadRequest): StorageResult[BulkLoadHandle] = inner.submitBulkLoad(r)
    def bulkLoadStatus(h: BulkLoadHandle): StorageResult[BulkLoadStatus]  = inner.bulkLoadStatus(h)

  def guardGraph(g: GraphStore, timeoutMillis: Long, attempts: Int, threshold: Int): GraphStore =
    new GuardedGraph(
      g,
      new DownstreamGuard(
        "test-graph",
        timeoutMillis,
        attempts,
        new CircuitBreaker(threshold, 60000L),
      ),
    )

  def waitUntil(maxMillis: Long)(cond: => Boolean): Boolean =
    val end = System.currentTimeMillis() + maxMillis
    while !cond && System.currentTimeMillis() < end do Thread.sleep(20)
    cond
