package scholarchain.services

import java.util.concurrent.{
  Callable,
  ExecutionException,
  Executors,
  ThreadFactory,
  TimeUnit,
  TimeoutException,
}
import java.util.concurrent.atomic.AtomicLong

import scholarchain.core.*
import scholarchain.core.rest.*

/** Fixed reasons carried by the `StorageError` a guard returns, and their HTTP mapping. */
object Downstream:
  val TimeoutReason: String = "downstream-timeout"
  val OpenReason: String    = "circuit-open"

  def toFailure(e: StorageError): ApiFailure = e match
    case StorageError.Unavailable(TimeoutReason) =>
      ApiFailure(ApiErrorCode.Timeout, "a downstream call timed out")
    case StorageError.Unavailable(OpenReason) =>
      ApiFailure(ApiErrorCode.Unavailable, "a downstream dependency is temporarily unavailable")
    case StorageError.NotFound(_)       => ApiFailure(ApiErrorCode.NotFound, "not found")
    case StorageError.InvalidRequest(_) =>
      ApiFailure(ApiErrorCode.InvalidField, "the request is not valid for the store")
    case _ => ApiFailure(ApiErrorCode.DownstreamFailure, "a downstream dependency failed")

  /** Reason token for a readiness check. */
  def reasonCode(e: StorageError): String = e match
    case StorageError.Unavailable(TimeoutReason) => "timeout"
    case StorageError.Unavailable(OpenReason)    => "circuit_open"
    case _                                       => "unavailable"

/** What the guards saw during one workflow run (per thread), so a run that abstained only because a
  * dependency was down is reported as a downstream failure rather than as "no answer".
  */
final class Probe:
  @volatile var timeouts: Int       = 0
  @volatile var opens: Int          = 0
  @volatile var failures: Int       = 0
  @volatile var retries: Int        = 0
  def anyFailure: Boolean           = timeouts + opens + failures > 0
  def asFailure: Option[ApiFailure] =
    if timeouts > 0 then Some(ApiFailure(ApiErrorCode.Timeout, "a downstream call timed out"))
    else if opens > 0 then
      Some(
        ApiFailure(ApiErrorCode.Unavailable, "a downstream dependency is temporarily unavailable")
      )
    else if failures > 0 then
      Some(ApiFailure(ApiErrorCode.DownstreamFailure, "a downstream dependency failed"))
    else None

object Probe:
  private val tl     = new ThreadLocal[Probe]
  def begin(): Probe =
    val p = new Probe
    tl.set(p)
    p
  def end(): Unit            = tl.remove()
  def current: Option[Probe] = Option(tl.get())

/** Simple circuit breaker: opens after `threshold` consecutive failures, lets one probe call
  * through after `openMillis`, closes on the first success.
  */
final class CircuitBreaker(
    threshold: Int,
    openMillis: Long,
    now: () => Long = () => System.currentTimeMillis(),
):
  private var failures = 0
  private var openedAt = 0L
  private var probing  = false

  def isOpen: Boolean = synchronized(openedAt != 0L && now() - openedAt < openMillis)

  def allow(): Boolean = synchronized {
    if openedAt == 0L then true
    else if now() - openedAt >= openMillis && !probing then
      probing = true
      true
    else false
  }

  def success(): Unit = synchronized {
    failures = 0
    openedAt = 0L
    probing = false
  }

  def failure(): Unit = synchronized {
    failures += 1
    probing = false
    if failures >= threshold then openedAt = now()
  }

/** Timeout, bounded retries (reads only) and a circuit breaker around one downstream port. A
  * `NotFound`, `AlreadyExists`, `InvalidRequest` or `IntegrityMismatch` is an answer, not a
  * failure.
  */
final class DownstreamGuard(
    val name: String,
    timeoutMillis: Long,
    maxAttempts: Int,
    val breaker: CircuitBreaker,
    backoffMillis: Long = 50L,
):
  private val counter = new AtomicLong(0)
  private val pool    = Executors.newCachedThreadPool(new ThreadFactory:
    def newThread(r: Runnable): Thread =
      val t = new Thread(r, s"guard-$name-${counter.incrementAndGet()}")
      t.setDaemon(true)
      t)

  private def isFailure(e: StorageError): Boolean = e match
    case _: StorageError.Unavailable | _: StorageError.Throttled | _: StorageError.Failed => true
    case _                                                                                => false

  private def once[A](f: => StorageResult[A]): StorageResult[A] =
    val fut = pool.submit(new Callable[StorageResult[A]]:
      def call(): StorageResult[A] = f)
    try fut.get(timeoutMillis, TimeUnit.MILLISECONDS)
    catch
      case _: TimeoutException =>
        fut.cancel(true)
        Left(StorageError.Unavailable(Downstream.TimeoutReason))
      case e: ExecutionException   => Left(StorageError.Failed(e.getCause.getClass.getSimpleName))
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(StorageError.Failed("interrupted"))

  def call[A](retry: Boolean)(f: => StorageResult[A]): StorageResult[A] =
    val attempts                     = if retry then math.max(1, maxAttempts) else 1
    def go(n: Int): StorageResult[A] =
      if !breaker.allow() then
        Probe.current.foreach(p => p.opens += 1)
        Left(StorageError.Unavailable(Downstream.OpenReason))
      else
        once(f) match
          case Right(a) =>
            breaker.success()
            Right(a)
          case Left(e) if isFailure(e) =>
            breaker.failure()
            val timedOut = e == StorageError.Unavailable(Downstream.TimeoutReason)
            if n < attempts && (e.retryable || timedOut) then
              Probe.current.foreach(p => p.retries += 1)
              Thread.sleep(backoffMillis * n)
              go(n + 1)
            else
              Probe.current.foreach(p => if timedOut then p.timeouts += 1 else p.failures += 1)
              Left(e)
          case Left(e) =>
            breaker.success()
            Left(e)
    go(1)

final class GuardedKv(inner: KeyValueStore, g: DownstreamGuard) extends KeyValueStore:
  def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
    g.call(false)(inner.put(table, item, condition))
  def get(table: String, key: KvKey): StorageResult[Option[KvItem]] =
    g.call(true)(inner.get(table, key))
  def query(
      table: String,
      partition: String,
      sort: Option[SortCondition],
      limit: Int,
      ascending: Boolean,
      continuationToken: Option[String],
  ): StorageResult[KvPage] =
    g.call(true)(inner.query(table, partition, sort, limit, ascending, continuationToken))

final class GuardedGraph(inner: GraphStore, g: DownstreamGuard) extends GraphStore:
  def query(q: GraphQuery): StorageResult[GraphResult] = g.call(true)(inner.query(q))
  def submitBulkLoad(request: BulkLoadRequest): StorageResult[BulkLoadHandle] =
    Left(StorageError.InvalidRequest("read-only"))
  def bulkLoadStatus(handle: BulkLoadHandle): StorageResult[BulkLoadStatus] =
    Left(StorageError.InvalidRequest("read-only"))

final class GuardedVectors(inner: VectorStore, g: DownstreamGuard) extends VectorStore:
  def putBatch(
      indexName: String,
      vectors: List[VectorEntry],
  ): StorageResult[List[VectorPutResult]] =
    Left(StorageError.InvalidRequest("read-only"))
  def query(indexName: String, q: VectorQuery): StorageResult[List[VectorHit]] =
    g.call(true)(inner.query(indexName, q))
  def get(indexName: String, vectorKey: String): StorageResult[Option[VectorEntry]] =
    g.call(true)(inner.get(indexName, vectorKey))

final class GuardedObjects(inner: ObjectStore, g: DownstreamGuard) extends ObjectStore:
  def put(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      checksum: Option[String],
  ): StorageResult[ObjectMeta] = g.call(false)(inner.put(location, bytes, contentType, checksum))
  def putIfAbsent(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      checksum: Option[String],
  ): StorageResult[ObjectMeta] =
    g.call(false)(inner.putIfAbsent(location, bytes, contentType, checksum))
  def get(location: ObjectLocation): StorageResult[ObjectData] = g.call(true)(inner.get(location))
  def head(location: ObjectLocation): StorageResult[Option[ObjectMeta]] =
    g.call(true)(inner.head(location))
  def list(
      bucket: String,
      prefix: String,
      maxKeys: Int,
      token: Option[String],
  ): StorageResult[ObjectPage] = g.call(true)(inner.list(bucket, prefix, maxKeys, token))
