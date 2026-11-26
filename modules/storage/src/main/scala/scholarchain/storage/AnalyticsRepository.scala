package scholarchain.storage

import scholarchain.core.*

import AnalyticsItems.AnalyticsItem

/** Counts of an analytics load, per outcome. */
final case class AnalyticsLoadReport(
    written: Long,
    unchanged: Long,
    conflicts: Long,
    conflictExamples: List[String],
):
  def ok: Boolean = conflicts == 0L

/** Repository of the ten Phase 06 analytics item tables, written through `KeyValueStore`.
  *
  * Item layout (identical in the fake and in DynamoDB), per table:
  *   - key: `pk` = the manifest partition key value, `sk` = the sort key components joined with `|`
  *     (absent for tables without a sort key), both encoded by `AnalyticsItems.encodeComponent`;
  *   - `payload`: the canonical compact JSON of the typed item attributes as ONE string. The
  *     `KeyValueStore` port holds strings only, so the typed JSON (ints, doubles, nulls, arrays,
  *     objects) is stored whole, and decoded back to the same JSON, nothing is lost;
  *   - `payloadHash`: SHA-256 of `payload`.
  *
  * Writes are conditional and idempotent (`ItemWrites.write`): a new key is `Written`; the same key
  * with the same `payloadHash` is `Unchanged` (a reload is a no-op); the same key with another
  * `payloadHash` is `Conflict` and nothing is overwritten.
  *
  * `tableNames` maps the logical analytics table name (`StorageConfigKeys.AnalyticsLogicalNames`)
  * to the configured physical DynamoDB table name (`cfg.tables.analytics`).
  */
final class AnalyticsRepository(kv: KeyValueStore, tableNames: Map[String, String]):

  private def physical(logical: String): StorageResult[String] =
    tableNames.get(logical).toRight(StorageError.InvalidRequest("unknown analytics table"))

  private def kvKey(item: AnalyticsItem) =
    KvKey(item.pk, item.sk)

  def put(item: AnalyticsItem): StorageResult[WriteResult] =
    physical(item.table).flatMap { t =>
      ItemWrites.write(
        kv,
        t,
        KvItem(
          // `pk` and `sk` are already component-encoded by `AnalyticsItems.build`.
          kvKey(item),
          Map(ItemWrites.Payload -> item.attributesJson, ItemWrites.PayloadHash -> item.payloadHash),
        ),
      )
    }

  /** Writes every item, bounded by the number of items given; never stops early on a conflict (it
    * is counted and the first few are described), never overwrites. A store error aborts.
    */
  def putAll(items: Iterable[AnalyticsItem]): StorageResult[AnalyticsLoadReport] =
    var w, u, c = 0L
    var ex      = List.empty[String]
    val it      = items.iterator
    var failure = Option.empty[StorageError]
    while failure.isEmpty && it.hasNext do
      val i = it.next()
      put(i) match
        case Left(e)                        => failure = Some(e)
        case Right(WriteResult.Written)     => w += 1
        case Right(WriteResult.Unchanged)   => u += 1
        case Right(WriteResult.Conflict(r)) =>
          c += 1
          if ex.size < 5 then ex = ex :+ s"${i.table}: $r"
    failure.toLeft(AnalyticsLoadReport(w, u, c, ex))

  /** Same semantics as `putAll` (one conditional `put` per item, never an overwrite, conflicts
    * counted, the first store error aborts) with a fixed pool of `parallelism` threads. `attempt`
    * wraps each single `put` (the caller passes its bounded retry); every item is therefore counted
    * once, by its final outcome, so the totals are exact. The counts are independent of thread
    * order; the conflict examples are the five lowest item positions. Which store error is reported
    * when several threads fail at once is not deterministic.
    */
  def putAllParallel(
      items: IndexedSeq[AnalyticsItem],
      parallelism: Int,
      attempt: (=> StorageResult[WriteResult]) => StorageResult[WriteResult],
  ): StorageResult[AnalyticsLoadReport] =
    val n              = math.max(1, math.min(parallelism, items.size))
    val next           = new java.util.concurrent.atomic.AtomicInteger(0)
    val w, u, c        = new java.util.concurrent.atomic.AtomicLong(0L)
    val failure        = new java.util.concurrent.atomic.AtomicReference[Option[StorageError]](None)
    val crash          = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
    val lock           = new Object
    var ex             = List.empty[(Int, String)]
    def worker(): Unit =
      var go = true
      while go && failure.get().isEmpty && crash.get() == null do
        val idx = next.getAndIncrement()
        if idx >= items.size then go = false
        else
          val i = items(idx)
          try
            attempt(put(i)) match
              case Left(e)                        => failure.compareAndSet(None, Some(e))
              case Right(WriteResult.Written)     => w.incrementAndGet()
              case Right(WriteResult.Unchanged)   => u.incrementAndGet()
              case Right(WriteResult.Conflict(r)) =>
                c.incrementAndGet()
                lock.synchronized {
                  ex = ((idx, s"${i.table}: $r") :: ex).sortBy(_._1).take(5)
                }
          catch
            case scala.util.control.NonFatal(e) =>
              failure.compareAndSet(
                None,
                Some(StorageError.Failed(s"item write: ${e.getClass.getSimpleName}")),
              )
              go = false
            // Fatal errors and interrupts: stop every worker, rethrown after the join.
            case t: Throwable =>
              crash.compareAndSet(null, t)
              go = false
    val threads = (0 until n).map { k =>
      val t = new Thread(() => worker(), s"analytics-put-$k")
      t.setDaemon(true)
      t.start()
      t
    }
    threads.foreach(_.join())
    Option(crash.get()).foreach {
      case ie: InterruptedException =>
        Thread.currentThread().interrupt()
        throw ie
      case t => throw t
    }
    failure.get() match
      case Some(e) => Left(e)
      case None    =>
        val done = w.get + u.get + c.get
        // Every item must be accounted for: a short count is never reported as success.
        if done != items.size.toLong then
          Left(StorageError.Failed(s"item write: ${done} of ${items.size} items accounted for"))
        else Right(AnalyticsLoadReport(w.get, u.get, c.get, ex.sortBy(_._1).map(_._2)))

  private def decode(i: KvItem): StorageResult[ujson.Value] =
    i.attributes
      .get(ItemWrites.Payload)
      .toRight(StorageError.Failed("stored item has no payload"))
      .flatMap { json =>
        if !ItemWrites.hashOf(i).contains(Cells.sha256(json)) then
          Left(StorageError.IntegrityMismatch("stored payload does not match its hash"))
        else
          scala.util
            .Try(ujson.read(json))
            .toEither
            .left
            .map(_ => StorageError.Failed("stored payload is not JSON"))
      }

  /** One item by its raw partition key and its sort key components. */
  def get(
      logical: String,
      pk: String,
      sortComponents: List[String] = Nil,
  ): StorageResult[Option[ujson.Value]] =
    physical(logical).flatMap { t =>
      val sk =
        if sortComponents.isEmpty then None
        else Some(sortComponents.map(AnalyticsItems.encodeComponent).mkString("|"))
      kv.get(t, KvKey(AnalyticsItems.encodeComponent(pk), sk)).flatMap {
        case None    => Right(None)
        case Some(i) => decode(i).map(Some(_))
      }
    }

  /** Access pattern "metrics of one researcher in one window": one `GetItem` on the
    * `researcher-metrics` table (pk researcher ID, sk window).
    */
  def researcherMetrics(researcherId: String, window: String): StorageResult[Option[ujson.Value]] =
    get("researcher-metrics", researcherId, List(window))

  /** All windows of one researcher, ascending by window label, at most `limit` (one `Query`). */
  def researcherMetricsAllWindows(
      researcherId: String,
      limit: Int,
  ): StorageResult[List[ujson.Value]] =
    physical("researcher-metrics").flatMap { t =>
      kv.query(t, AnalyticsItems.encodeComponent(researcherId), None, limit)
        .flatMap(p => sequenceResults(p.items.map(decode)))
    }

object AnalyticsRepository:
  /** Whether each analytics table has a sort key (from the Phase 06 `dynamodb.json` manifest). */
  val HasSortKey: Map[String, Boolean] = Map(
    "researcher-metrics"  -> true,
    "coauthor-edge-works" -> true,
    "bridges"             -> true,
    "coreness"            -> true,
    "temporal-changes"    -> true,
    "paths"               -> true,
    "path-estimates"      -> false,
    "window-summary"      -> false,
    "identity-clusters"   -> false,
    "mention-researcher"  -> false,
  )

  /** Repository over the tables named in the configuration. */
  def fromConfig(kv: KeyValueStore, tables: StorageDynamoTables): AnalyticsRepository =
    new AnalyticsRepository(kv, tables.analytics)

  /** Table schemas of the analytics tables for the in-memory store, keyed by physical name. */
  def schemas(tables: StorageDynamoTables): Map[String, KvTableSchema] =
    tables.analytics.flatMap((logical, phys) =>
      HasSortKey.get(logical).map(h => phys -> KvTableSchema(h))
    )
