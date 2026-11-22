package scholarchain.storage

import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.collection.mutable

import scholarchain.core.*

import StorageFx.*

/** Phase 08 fixtures: an in-memory object store, a scripted embedder, failure-injecting wrappers
  * and a `Rig` that wires the producer, the worker and the ledger on the in-memory fakes. Synthetic
  * values only; no AWS client, no network, no credential.
  */
final class MemObjectStore extends ObjectStore:
  private val m = mutable.LinkedHashMap.empty[(String, String), (Array[Byte], Option[String])]
  val getCalls: AtomicInteger                    = AtomicInteger(0)
  @volatile var putFailure: Option[StorageError] = None

  private def meta(l: ObjectLocation, b: Array[Byte], ct: Option[String]) =
    ObjectMeta(l, b.length.toLong, Hashing.sha256Hex(b), ct, None, None)

  def put(
      l: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] = synchronized {
    putFailure match
      case Some(e) => Left(e)
      case None    =>
        if expectedHash.exists(_ != Hashing.sha256Hex(bytes)) then
          Left(StorageError.IntegrityMismatch("hash"))
        else
          m((l.bucket, l.objectKey)) = (bytes, contentType)
          Right(meta(l, bytes, contentType))
  }

  def putIfAbsent(
      l: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] = synchronized {
    if m.contains((l.bucket, l.objectKey)) then Left(StorageError.AlreadyExists("object"))
    else put(l, bytes, contentType, expectedHash)
  }

  def get(l: ObjectLocation): StorageResult[ObjectData] = synchronized {
    getCalls.incrementAndGet()
    m.get((l.bucket, l.objectKey))
      .toRight(StorageError.NotFound("object"))
      .map((b, ct) => ObjectData(meta(l, b, ct), b))
  }

  def head(l: ObjectLocation): StorageResult[Option[ObjectMeta]] = synchronized {
    Right(m.get((l.bucket, l.objectKey)).map((b, ct) => meta(l, b, ct)))
  }

  def list(
      bucket: String,
      prefix: String,
      limit: Int,
      continuationToken: Option[String],
  ): StorageResult[ObjectPage] = synchronized {
    val keys = m.keys.filter(k => k._1 == bucket && k._2.startsWith(prefix)).map(_._2).toList.sorted
    Right(
      ObjectPage(
        keys.take(limit).map(k => meta(ObjectLocation(bucket, k), m((bucket, k))._1, None)),
        None,
      )
    )
  }

  /** Test helper: replaces the stored bytes without any check (models corruption at rest). */
  def tamper(l: ObjectLocation, bytes: Array[Byte]): Unit = synchronized {
    m((l.bucket, l.objectKey)) = (bytes, None)
  }

  def size: Int = synchronized(m.size)
end MemObjectStore

/** Deterministic embedder with a script of failures by call number (1-based). */
final class FakeEmbedder(
    val dimension: Int = 4,
    val modelId: String = "test-embedder",
    val modelVersion: String = "v1",
    val maxBatchSize: Int = 2,
    script: Int => Option[EmbeddingError] = _ => None,
    shortBy: Int = 0,
) extends Embedder:
  val calls: AtomicInteger = AtomicInteger(0)

  def vecOf(text: String): Vector[Float] =
    val h = Hashing.textHash(text)
    Vector.tabulate(dimension - shortBy)(i => Character.digit(h.charAt(i % 64), 16).toFloat + 1f)

  def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]] =
    val n = calls.incrementAndGet()
    script(n) match
      case Some(e) => Left(e)
      case None    => Right(texts.map(vecOf))

/** Vector store wrapper: counts calls, optionally fails `get` or `putBatch`. */
final class FlakyVectorStore(val delegate: VectorStore) extends VectorStore:
  val puts: AtomicInteger                        = AtomicInteger(0)
  val gets: AtomicInteger                        = AtomicInteger(0)
  @volatile var putFailure: Option[StorageError] = None
  @volatile var getFailure: Option[StorageError] = None

  def putBatch(i: String, vs: List[VectorEntry]) =
    puts.incrementAndGet()
    putFailure.fold(delegate.putBatch(i, vs))(Left(_))

  def query(i: String, q: VectorQuery) = delegate.query(i, q)

  def get(i: String, k: String) =
    gets.incrementAndGet()
    getFailure.fold(delegate.get(i, k))(Left(_))

/** Claims-store wrapper: counts calls, optionally fails every put or get (a claim store outage). */
final class FlakyClaimStore(val delegate: KeyValueStore) extends KeyValueStore:
  val puts: AtomicInteger                     = AtomicInteger(0)
  @volatile var failure: Option[StorageError] = None

  def put(table: String, item: KvItem, condition: PutCondition) =
    puts.incrementAndGet()
    failure.fold(delegate.put(table, item, condition))(Left(_))

  def get(table: String, key: KvKey) =
    failure.fold(delegate.get(table, key))(Left(_))

  def query(
      table: String,
      partition: String,
      sort: Option[SortCondition],
      limit: Int,
      ascending: Boolean,
      continuationToken: Option[String],
  ) =
    failure.fold(delegate.query(table, partition, sort, limit, ascending, continuationToken))(
      Left(_)
    )

/** Queue wrapper: optionally fails receive, delete, or sends to chosen queues. */
final class FlakyQueue(val delegate: WorkQueue) extends WorkQueue:
  @volatile var failReceive: Boolean    = false
  @volatile var failDelete: Boolean     = false
  @volatile var failSendTo: Set[String] = Set.empty
  val deletes: AtomicInteger            = AtomicInteger(0)

  def send(queue: String, body: String, deduplicationId: Option[String]) =
    if failSendTo.contains(queue) then Left(StorageError.Unavailable("send"))
    else delegate.send(queue, body, deduplicationId)

  def receive(queue: String, max: Int, visibility: Int, wait: Int) =
    if failReceive then Left(StorageError.Unavailable("receive"))
    else delegate.receive(queue, max, visibility, wait)

  def delete(queue: String, receiptHandle: String) =
    deletes.incrementAndGet()
    if failDelete then Left(StorageError.Unavailable("delete"))
    else delegate.delete(queue, receiptHandle)

object EmbeddingFx:
  val bucket: String      = "embedding-test-bucket"
  val ClaimsTable: String = EmbeddingSettings.DefaultClaimsTableName
  val codeVersion: String = "test-code-1"
  val t0: Instant         = Instant.parse("2026-11-21T10:00:00Z")

  def runFor(label: String): String = runIdFor(s"embed-$label")

  def uriFor(text: String): String = s"s3://$bucket/rag/text/${Hashing.textHash(text)}.txt"

  /** A valid document reference for `text` (entity derived from `name`). */
  def doc(
      text: String,
      name: String = "Ada Lovelace",
      documentType: String = EmbeddingDocumentTypes.ResearcherProfile,
      window: GraphWindow = GraphWindow(2000, 2004),
      ordinal: Int = 0,
      count: Int = 1,
  ): EmbeddingDocument =
    val entity = researcher(name)
    EmbeddingDocument(
      schemaVersion = 1,
      documentId = EmbeddingDocument.idFor(documentType, entity, window, ordinal),
      documentType = documentType,
      entityId = entity,
      window = window,
      textS3Uri = uriFor(text),
      textHash = Hashing.textHash(text),
      chunkOrdinal = ordinal,
      chunkCount = count,
      sourceRunId = run1,
    )

  def source(
      text: String,
      name: String = "Ada Lovelace",
      tpe: String = EmbeddingDocumentTypes.ResearcherProfile,
  ): EmbeddingSource =
    EmbeddingSource(doc(text, name, tpe), text)

  def encode(job: EmbeddingJob): String =
    EmbeddingJobMessage.encode(job).fold(e => throw AssertionError(e.toString), identity)

  def jobOf(
      docs: List[EmbeddingDocument],
      runId: String,
      attempt: Int = 1,
      model: String = "test-embedder",
      version: String = "v1",
  ): EmbeddingJob =
    EmbeddingJob(
      1,
      "embjob-fixed-0001",
      runId,
      docs,
      model,
      version,
      attempt,
      EmbeddingJobStatus.Pending,
    )

  /** Producer + worker + ledger on the fakes. `now` is the queue clock; `ticks` drives the ledger
    * clock (one second per call).
    */
  final class Rig(
      val maxAttempts: Int = 3,
      val embedder: FakeEmbedder = FakeEmbedder(),
      val maxDocsPerJob: Int = 2,
      val indexOverride: Option[String] = None,
      val vectorIndexes: Map[String, Int] = Map.empty,
  ):
    val now: AtomicLong = AtomicLong(1000L)
    val queueRaw        = InMemoryWorkQueue(() => now.get)
    val queue           = FlakyQueue(queueRaw)
    val objects         = MemObjectStore()
    private val dims    =
      if vectorIndexes.nonEmpty then vectorIndexes
      else
        (VectorIndexes.all.map(_ -> embedder.dimension) ++ indexOverride.map(
          _ -> embedder.dimension
        )).toMap
    val vectorsRaw          = InMemoryVectorStore(dims)
    val vectors             = FlakyVectorStore(vectorsRaw)
    val kv                  = InMemoryKeyValueStore(Map("ledger" -> KvTableSchema(true)))
    val repo                = RunLedgerRepository(kv, "ledger")
    val ticks               = AtomicInteger(0)
    val clock: () => String = () => t0.plusSeconds(ticks.getAndIncrement().toLong).toString
    val ledger              = EmbeddingLedger(repo, clock, codeVersion)

    val claimsRaw = InMemoryKeyValueStore(
      Map(ClaimsTable -> EmbeddingKeyClaims.Schema)
    )
    val claimsKv = FlakyClaimStore(claimsRaw)

    val producerConfig: EmbeddingProducerConfig =
      EmbeddingProducerConfig("jobs", maxDocsPerJob, 1000, embedder.modelId, embedder.modelVersion)
    val workerConfig: EmbeddingWorkerConfig =
      EmbeddingWorkerConfig(
        "jobs",
        "dlq",
        maxAttempts,
        30,
        1,
        indexOverride,
        Some(embedder.dimension),
      )
    val producer = EmbeddingProducer(producerConfig, queue, objects, Some(ledger))
    val worker   =
      EmbeddingWorker(workerConfig, queue, objects, embedder, vectors, claimsKv, Some(ledger))

    /** Another worker on the SAME queue, object store, vector store and claims store (a second
      * process of the same deployment).
      */
    def secondWorker(e: Embedder = embedder, v: VectorStore = vectors): EmbeddingWorker =
      EmbeddingWorker(workerConfig, queue, objects, e, v, claimsKv, Some(ledger))

    def enqueue(runId: String, sources: EmbeddingSource*): ProducerReport =
      producer.enqueue(runId, sources.toList).fold(e => throw AssertionError(e.toString), identity)

    def drain(max: Int = 50): EmbeddingDrainResult = worker.drain(max)

    /** Makes every invisible message visible again (an unacknowledged message would reappear). */
    def advanceClock(): Unit = now.addAndGet(100000L)

    /** Bodies of the messages currently in `name` (after the visibility timeout has passed). */
    def bodies(name: String): List[String] =
      advanceClock()
      Iterator
        .continually(
          queueRaw.receive(name, 10, 30, 0).fold(e => throw AssertionError(e.toString), identity)
        )
        .takeWhile(_.nonEmpty)
        .flatten
        .map(_.body)
        .toList

    def jobsIn(name: String): List[EmbeddingJob] =
      bodies(name).map(b =>
        EmbeddingJobMessage.decode(b).fold(e => throw AssertionError(e), identity)
      )

    def ledgerEntries(stage: String): List[RunLedgerEntry] =
      repo
        .latestForStage(stage, 1000)
        .fold(e => throw AssertionError(e.toString), identity)
        .flatMap(id => repo.get(id).fold(e => throw AssertionError(e.toString), identity))
        .sortBy(e => (e.attempt, e.startedAt, e.runId))

    def stored(index: String, key: String): Option[VectorEntry] =
      vectorsRaw.get(index, key).fold(e => throw AssertionError(e.toString), identity)
  end Rig
end EmbeddingFx
