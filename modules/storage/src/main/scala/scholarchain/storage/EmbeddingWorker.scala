package scholarchain.storage

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import scala.collection.immutable.TreeMap

import scholarchain.core.*

/** Worker bounds and targets. `maxAttempts` is `embedding.queue.max-attempts`. `indexOverride`
  * sends every vector to one index (the live test index); `None` routes by document type
  * (`EmbeddingDocumentTypes.indexByType`). `expectedDimension`, when set, must equal the embedder's
  * dimension (a mismatch rejects every job).
  */
final case class EmbeddingWorkerConfig(
    queueName: String,
    deadLetterName: String,
    maxAttempts: Int,
    visibilityTimeoutSeconds: Int,
    receiveBatchSize: Int,
    indexOverride: Option[String],
    expectedDimension: Option[Int],
    claimsTable: String = EmbeddingSettings.DefaultClaimsTableName,
)

object EmbeddingWorkerConfig:
  def fromSettings(
      s: EmbeddingSettings,
      visibilityTimeoutSeconds: Int = 300,
      receiveBatchSize: Int = 1,
      indexOverride: Option[String] = None,
  ): EmbeddingWorkerConfig =
    EmbeddingWorkerConfig(
      s.queueName,
      s.deadLetterName,
      s.maxAttempts,
      visibilityTimeoutSeconds,
      receiveBatchSize,
      indexOverride,
      Some(s.dimension),
      s.claimsTableName,
    )

/** Stable codes of a per-document outcome that is not a success. They are the keys of
  * `EmbeddingCounts.rejectedByReason` and appear in failure reasons.
  */
object EmbeddingRejection:
  val InvalidJobMessage: String       = "invalid-job-message"
  val ModelMismatch: String           = "model-mismatch"
  val DimensionMismatch: String       = "dimension-mismatch"
  val KeyCollision: String            = "key-collision"
  val EmbeddingMismatch: String       = "embedding-mismatch"
  val InvalidRecord: String           = "invalid-record"
  val TextMissing: String             = "text-missing"
  val TextHashMismatch: String        = "text-hash-mismatch"
  val TextNotDecodable: String        = "text-not-decodable"
  val TextStoreFailed: String         = "text-store-failed"
  val VectorStoreFailed: String       = "vector-store-failed"
  val EmbedInvalidRequest: String     = "embed-invalid-request"
  val EmbedFailed: String             = "embed-failed"
  val EmbedCountMismatch: String      = "embed-count-mismatch"
  val UnknownIndex: String            = "unknown-index"
  val AttemptBoundExceeded: String    = "attempt-bound-exceeded"
  val RedeliveryBoundExceeded: String = "redelivery-bound-exceeded"
  val RetriesExhausted: String        = "retries-exhausted"

  /** Retry code (never a final rejection on its own): the claim store failed. */
  val ClaimStoreFailed: String = "claim-store-failed"

/** Counts of one worker run. Reason keys are sorted. Document counts are per document; job counts
  * per delivered message.
  *   - `jobsProcessed`: messages handled (received and finished or dead-lettered).
  *   - `documentsSeen`: documents in those jobs.
  *   - `documentsEmbedded`: documents for which the embedder returned a valid vector.
  *   - `vectorsWritten`: vectors the vector store accepted.
  *   - `skippedUnchanged`: documents whose vector already existed with the same key, text hash,
  *     model and pointer: no text fetch, no embed call, no write.
  *   - `jobsRetried` / `documentsRetried`: jobs (documents) re-enqueued with attempt + 1.
  *   - `jobsDeadLettered` / `documentsDeadLettered`: messages (documents) sent to the DLQ.
  *   - `rejectedByReason`: documents that were not stored and not retried, by code (also the
  *     retry-exhausted ones, under `retries-exhausted`).
  *   - `embedCalls`: calls to the embedder.
  *   - `ledgerFailures`, `queueFailures`: ledger writes or queue operations that failed (the work
  *     itself is not undone; a failed queue step leaves the message to be redelivered).
  */
final case class EmbeddingCounts(
    jobsProcessed: Int = 0,
    documentsSeen: Int = 0,
    documentsEmbedded: Int = 0,
    vectorsWritten: Int = 0,
    skippedUnchanged: Int = 0,
    jobsRetried: Int = 0,
    documentsRetried: Int = 0,
    jobsDeadLettered: Int = 0,
    documentsDeadLettered: Int = 0,
    embedCalls: Int = 0,
    ledgerFailures: Int = 0,
    queueFailures: Int = 0,
    rejectedByReason: Map[String, Int] = TreeMap.empty,
):
  def +(o: EmbeddingCounts): EmbeddingCounts =
    EmbeddingCounts(
      jobsProcessed + o.jobsProcessed,
      documentsSeen + o.documentsSeen,
      documentsEmbedded + o.documentsEmbedded,
      vectorsWritten + o.vectorsWritten,
      skippedUnchanged + o.skippedUnchanged,
      jobsRetried + o.jobsRetried,
      documentsRetried + o.documentsRetried,
      jobsDeadLettered + o.jobsDeadLettered,
      documentsDeadLettered + o.documentsDeadLettered,
      embedCalls + o.embedCalls,
      ledgerFailures + o.ledgerFailures,
      queueFailures + o.queueFailures,
      TreeMap.from(
        (rejectedByReason.toList ++ o.rejectedByReason.toList)
          .groupBy(_._1)
          .map((k, v) => k -> v.map(_._2).sum)
      ),
    )

  def rejectedTotal: Int = rejectedByReason.values.sum

object EmbeddingCounts:
  val zero: EmbeddingCounts = EmbeddingCounts()

  def rejected(reason: String, n: Int): EmbeddingCounts =
    EmbeddingCounts(rejectedByReason = TreeMap(reason -> n))

/** Result of `EmbeddingWorker.drain`: the counts and why the loop stopped (`empty`, `bound`, or
  * `receive-error:<code>`).
  */
final case class EmbeddingDrainResult(counts: EmbeddingCounts, stoppedBy: String)

/** The embedding worker: receives `EmbeddingJob` messages and turns documents into vectors.
  *
  * Per document, in this order:
  *   1. IDEMPOTENCY FIRST. `VectorStore.get(index, documentId)`. Found with the same text hash,
  *      model ID, model version, dimension and text pointer: skipped (`skippedUnchanged`; no text
  *      fetch, no embed call, no write). Found with a different text hash: rejected `key-collision`
  *      (the key may never silently refer to different text). Found with the same text hash but
  *      another model, version, dimension or pointer: rejected `embedding-mismatch` (a vector is
  *      never overwritten).
  *   2. Fetch the text from the object store at `textS3Uri` and verify SHA-256 = `textHash`.
  *   3. Embed through the `Embedder` port in batches of `maxBatchSize`. The job's model ID and
  *      version must equal the embedder's, and the returned length its `dimension`.
  *   4. CLAIM the vector key (`EmbeddingKeyClaims`, conditional put in the claims table), then
  *      write through the `VectorStore` port: `VectorRecord` with the model ID, version and
  *      dimension, the `textS3Uri` pointer and compact metadata only (document type, entity,
  *      window, chunk ordinal and count; never text).
  *
  * Failure semantics (see STORAGE-CONTRACTS.md): a retryable failure (embedder `Throttled` or
  * `Unavailable`, a retryable storage error, a vector-store `ServiceRejected`) re-enqueues the
  * affected documents as the same job with `attempt + 1` (explicit re-enqueue, deterministic, no
  * wait), until `attempt = maxAttempts`, when they are dead-lettered (`retries-exhausted`). A
  * non-retryable failure (key collision, dimension mismatch, invalid record, missing or corrupt
  * text, model mismatch, invalid message) is NOT retried: the affected documents are dead-lettered
  * at once, so none is silently dropped. The original message is deleted only after every follow-up
  * message (retry job, DLQ job) was sent; if a send fails, the message is left to be redelivered
  * (the work is idempotent) and a redelivery bound (`receiveCount > maxAttempts`) dead-letters a
  * message that keeps coming back.
  *
  * Determinism: no clock and no random ID is used here; ledger timestamps come from the ledger's
  * injected clock.
  */
final class EmbeddingWorker(
    config: EmbeddingWorkerConfig,
    queue: WorkQueue,
    objects: ObjectStore,
    embedder: Embedder,
    vectors: VectorStore,
    claims: KeyValueStore,
    ledger: Option[EmbeddingLedger] = None,
):
  private val keyClaims = new EmbeddingKeyClaims(claims, config.claimsTable)

  import EmbeddingRejection as R

  private enum Fate:
    case Skipped
    case Written
    case Retry(code: String)
    case Reject(code: String)

  /** Receives up to `receiveBatchSize` messages once and handles each. `Left` when the receive
    * itself failed.
    */
  def runOnce(): StorageResult[EmbeddingCounts] =
    queue
      .receive(
        config.queueName,
        math.max(1, math.min(config.receiveBatchSize, WorkQueueLimits.MaxReceive)),
        config.visibilityTimeoutSeconds,
      )
      .map(_.foldLeft(EmbeddingCounts.zero)((acc, m) => acc + handle(m)))

  /** Calls `runOnce` until the queue yields nothing or `maxMessages` messages were handled. */
  def drain(maxMessages: Int): EmbeddingDrainResult =
    @scala.annotation.tailrec
    def go(acc: EmbeddingCounts): EmbeddingDrainResult =
      if acc.jobsProcessed >= maxMessages then EmbeddingDrainResult(acc, "bound")
      else
        runOnce() match
          case Left(e) => EmbeddingDrainResult(acc, s"receive-error:${e.code}")
          case Right(c) if c.jobsProcessed == 0 => EmbeddingDrainResult(acc, "empty")
          case Right(c)                         => go(acc + c)
    go(EmbeddingCounts.zero)

  // ---- one message -------------------------------------------------------------------------

  private def handle(m: QueueMessage): EmbeddingCounts =
    EmbeddingJobMessage.decode(m.body) match
      case Left(_) =>
        val envelope = ujson.write(
          ujson.Obj(
            "status"        -> EmbeddingJobStatus.DeadLettered.wire,
            "failureReason" -> R.InvalidJobMessage,
            "messageId"     -> m.messageId,
            "bodySha256"    -> Hashing.textHash(m.body),
          )
        )
        val ok = send(config.deadLetterName, envelope, s"invalid-${Hashing.textHash(m.body)}")
        val q  = if ok && deleteMsg(m) then 0 else 1
        EmbeddingCounts(
          jobsProcessed = 1,
          jobsDeadLettered = if ok then 1 else 0,
          queueFailures = q,
          rejectedByReason = TreeMap(R.InvalidJobMessage -> 1),
        )
      case Right(job) =>
        if m.receiveCount > config.maxAttempts then
          deadLetterWhole(m, job, R.RedeliveryBoundExceeded)
        else if job.attempt > config.maxAttempts then
          deadLetterWhole(m, job, R.AttemptBoundExceeded)
        else if job.embeddingModelId != embedder.modelId ||
          job.embeddingModelVersion != embedder.modelVersion
        then deadLetterWhole(m, job, R.ModelMismatch)
        else if config.expectedDimension.exists(_ != embedder.dimension) then
          deadLetterWhole(m, job, R.DimensionMismatch)
        else process(m, job)

  private def deadLetterWhole(m: QueueMessage, job: EmbeddingJob, code: String): EmbeddingCounts =
    val dead = job.documents.map(d => d -> code)
    finish(m, job, Map.empty, dead, Nil, EmbeddingCounts.zero, None)

  private def process(m: QueueMessage, job: EmbeddingJob): EmbeddingCounts =
    val entry =
      ledger.map(_.beginJob(job, embedder.dimension, config.maxAttempts, config.indexOverride))
    val (fates, counts) = embedAll(job)
    val retry           = job.documents.filter(d => fates(d.documentId).isInstanceOf[Fate.Retry])
    val reject          = job.documents.flatMap { d =>
      fates(d.documentId) match
        case Fate.Reject(c) => List(d -> c)
        case _              => Nil
    }
    val retryCodes = retry.map(d => d -> fates(d.documentId).asInstanceOf[Fate.Retry].code)
    finish(m, job, fates, reject, retryCodes, counts, entry)

  /** Sends follow-ups, deletes the original, updates the ledger and builds the counts. */
  private def finish(
      m: QueueMessage,
      job: EmbeddingJob,
      fates: Map[String, Fate],
      rejected: List[(EmbeddingDocument, String)],
      retryable: List[(EmbeddingDocument, String)],
      work: EmbeddingCounts,
      entry: Option[StorageResult[RunLedgerEntry]],
  ): EmbeddingCounts =
    val exhausted = retryable.nonEmpty && job.attempt >= config.maxAttempts
    val toRetry   = if exhausted then Nil else retryable
    val dead      =
      rejected ++ (if exhausted then retryable.map((d, _) => d -> R.RetriesExhausted) else Nil)
    val deadSorted = dead.sortBy(_._1.documentId)

    val retrySent =
      if toRetry.isEmpty then true
      else
        val next = job.copy(
          documents = toRetry.map(_._1),
          attempt = job.attempt + 1,
          status = EmbeddingJobStatus.Pending,
          failureReason = None,
        )
        EmbeddingJobMessage
          .encode(next)
          .toOption
          .exists(b =>
            send(
              config.queueName,
              b,
              EmbeddingJobMessage.dedupId(job.jobId, next.attempt, "retry"),
            )
          )
    val deadSent =
      if deadSorted.isEmpty then true
      else
        val reasonCounts = TreeMap.from(deadSorted.groupBy(_._2).map((k, v) => k -> v.size))
        val reason       = EmbeddingLedger.safeReason(
          "dead_lettered: " + reasonCounts.map((k, v) => s"$k=$v").mkString(",")
        )
        val dl = job.copy(
          documents = deadSorted.map(_._1),
          status = EmbeddingJobStatus.DeadLettered,
          failureReason = Some(reason),
        )
        EmbeddingJobMessage
          .encode(dl)
          .toOption
          .exists(b =>
            send(
              config.deadLetterName,
              b,
              EmbeddingJobMessage.dedupId(job.jobId, job.attempt, s"dlq-${m.receiveCount}"),
            )
          )
    val followUpsOk = retrySent && deadSent
    val deleted     = followUpsOk && deleteMsg(m)

    val outcomeFailure: Option[String] =
      if toRetry.isEmpty && deadSorted.isEmpty then None
      else
        val parts = List(
          if toRetry.nonEmpty then
            Some(
              s"retry ${toRetry.size} doc(s) as attempt ${job.attempt + 1} of ${config.maxAttempts}: " +
                TreeMap
                  .from(toRetry.groupBy(_._2).map((k, v) => k -> v.size))
                  .map((k, v) => s"$k=$v")
                  .mkString(",")
            )
          else None,
          if deadSorted.nonEmpty then
            Some(
              "dead_lettered " + deadSorted.size + " doc(s): " +
                TreeMap
                  .from(deadSorted.groupBy(_._2).map((k, v) => k -> v.size))
                  .map((k, v) => s"$k=$v")
                  .mkString(",")
            )
          else None,
        ).flatten
        Some(parts.mkString("; "))

    val ledgerFail = (entry, ledger) match
      case (Some(Right(e)), Some(l)) => if l.endJob(e, outcomeFailure).isLeft then 1 else 0
      case (Some(Left(_)), _)        => 1
      case _                         => 0

    val queueFail = (if followUpsOk then 0 else 1) + (if followUpsOk && !deleted then 1 else 0)

    work + EmbeddingCounts(
      jobsProcessed = 1,
      documentsSeen = job.documents.size,
      jobsRetried = if toRetry.nonEmpty && retrySent then 1 else 0,
      documentsRetried = if retrySent then toRetry.size else 0,
      jobsDeadLettered = if deadSorted.nonEmpty && deadSent then 1 else 0,
      documentsDeadLettered = if deadSent then deadSorted.size else 0,
      ledgerFailures = ledgerFail,
      queueFailures = queueFail,
      rejectedByReason = TreeMap.from(deadSorted.groupBy(_._2).map((k, v) => k -> v.size)),
    )

  private def send(queueName: String, body: String, dedup: String): Boolean =
    queue.send(queueName, body, Some(dedup)).isRight

  private def deleteMsg(m: QueueMessage): Boolean =
    queue.delete(config.queueName, m.receiptHandle).isRight

  // ---- documents ---------------------------------------------------------------------------

  private def indexFor(d: EmbeddingDocument): Option[String] =
    config.indexOverride.orElse(EmbeddingDocumentTypes.indexFor(d.documentType))

  /** Runs the four steps for every document. The map has one fate per document ID. */
  private def embedAll(job: EmbeddingJob): (Map[String, Fate], EmbeddingCounts) =
    var counts = EmbeddingCounts(embedCalls = 0)
    val fates  = scala.collection.mutable.HashMap.empty[String, Fate]

    // Step 1: idempotency first.
    val pending = job.documents.flatMap { d =>
      indexFor(d) match
        case None =>
          fates(d.documentId) = Fate.Reject(R.UnknownIndex)
          None
        case Some(index) =>
          vectors.get(index, d.documentId) match
            case Left(e) =>
              fates(d.documentId) =
                if e.retryable then Fate.Retry(e.code) else Fate.Reject(R.VectorStoreFailed)
              None
            case Right(Some(old)) =>
              val r = old.record
              if r.textHash != d.textHash then fates(d.documentId) = Fate.Reject(R.KeyCollision)
              else if r.embeddingModelId == job.embeddingModelId &&
                r.embeddingModelVersion == job.embeddingModelVersion &&
                r.dimension == embedder.dimension && r.textS3Uri == d.textS3Uri
              then
                fates(d.documentId) = Fate.Skipped
                counts = counts + EmbeddingCounts(skippedUnchanged = 1)
              else fates(d.documentId) = Fate.Reject(R.EmbeddingMismatch)
              None
            case Right(None) => Some(d -> index)
    }

    // Step 2: text.
    val withText = pending.flatMap { (d, index) =>
      fetchText(d) match
        case Right(t) => Some((d, index, t))
        case Left(f)  =>
          fates(d.documentId) = f
          None
    }

    // Step 3: embed in batches.
    val embedded = withText.grouped(math.max(1, embedder.maxBatchSize)).toList.flatMap { chunk =>
      counts = counts + EmbeddingCounts(embedCalls = 1)
      embedder.embed(chunk.map(_._3)) match
        case Left(err) =>
          val f: Fate = err match
            case e if e.retryable                    => Fate.Retry(e.code)
            case _: EmbeddingError.DimensionMismatch => Fate.Reject(R.DimensionMismatch)
            case _: EmbeddingError.InvalidRequest    => Fate.Reject(R.EmbedInvalidRequest)
            case _                                   => Fate.Reject(R.EmbedFailed)
          chunk.foreach((d, _, _) => fates(d.documentId) = f)
          Nil
        case Right(vs) if vs.size != chunk.size =>
          chunk.foreach((d, _, _) => fates(d.documentId) = Fate.Reject(R.EmbedCountMismatch))
          Nil
        case Right(vs) =>
          chunk.zip(vs).flatMap { case ((d, index, _), v) =>
            if v.length != embedder.dimension then
              fates(d.documentId) = Fate.Reject(R.DimensionMismatch)
              None
            else
              counts = counts + EmbeddingCounts(documentsEmbedded = 1)
              Some((d, index, v))
          }
    }

    // Step 4a: claim every key. Only a claim won or an identical claim leads to a write; a claim
    // store failure is a retry and never falls through to an unclaimed write.
    val claimed = embedded.flatMap { (d, index, v) =>
      keyClaims.claim(record(job, d, index), job.jobId) match
        case ClaimOutcome.Won | ClaimOutcome.Identical => Some((d, index, v))
        case ClaimOutcome.KeyCollision                 =>
          fates(d.documentId) = Fate.Reject(R.KeyCollision)
          None
        case ClaimOutcome.EmbeddingMismatch =>
          fates(d.documentId) = Fate.Reject(R.EmbeddingMismatch)
          None
        case ClaimOutcome.StoreFailed(_) =>
          fates(d.documentId) = Fate.Retry(R.ClaimStoreFailed)
          None
    }

    // Step 4b: write, one putBatch per index (indexes in sorted order, documents in job order).
    claimed.groupBy(_._2).toList.sortBy(_._1).foreach { (index, items) =>
      val entries = items.map((d, _, v) => VectorEntry(record(job, d, index), v))
      vectors.putBatch(index, entries) match
        case Left(e) =>
          val f: Fate = if e.retryable then Fate.Retry(e.code) else Fate.Reject(R.VectorStoreFailed)
          items.foreach((d, _, _) => fates(d.documentId) = f)
        case Right(results) if results.size != items.size =>
          items.foreach((d, _, _) => fates(d.documentId) = Fate.Reject(R.VectorStoreFailed))
        case Right(results) =>
          items.zip(results).foreach { case ((d, _, _), res) =>
            res.outcome match
              case VectorPutOutcome.Accepted =>
                fates(d.documentId) = Fate.Written
                counts = counts + EmbeddingCounts(vectorsWritten = 1)
              case VectorPutOutcome.Rejected(why) =>
                fates(d.documentId) = why match
                  case VectorRejection.KeyCollision      => Fate.Reject(R.KeyCollision)
                  case VectorRejection.DimensionMismatch => Fate.Reject(R.DimensionMismatch)
                  case VectorRejection.EmbeddingMismatch => Fate.Reject(R.EmbeddingMismatch)
                  case VectorRejection.InvalidRecord     => Fate.Reject(R.InvalidRecord)
                  case VectorRejection.ServiceRejected   => Fate.Retry("service-rejected")
          }
    }
    (fates.toMap, counts)

  private def fetchText(d: EmbeddingDocument): Either[Fate, String] =
    ObjectLocation.parse(d.textS3Uri) match
      case Left(_)    => Left(Fate.Reject(R.TextStoreFailed))
      case Right(loc) =>
        objects.get(loc) match
          case Left(StorageError.NotFound(_))          => Left(Fate.Reject(R.TextMissing))
          case Left(StorageError.IntegrityMismatch(_)) => Left(Fate.Reject(R.TextHashMismatch))
          case Left(e) if e.retryable                  => Left(Fate.Retry(e.code))
          case Left(_)                                 => Left(Fate.Reject(R.TextStoreFailed))
          case Right(data)                             =>
            if Hashing.sha256Hex(data.bytes) != d.textHash then
              Left(Fate.Reject(R.TextHashMismatch))
            else
              EmbeddingWorker.decodeUtf8(data.bytes).left.map(_ => Fate.Reject(R.TextNotDecodable))

  /** The vector record of a document: model ID, version and dimension, the text pointer, the
    * document's text hash, and compact metadata only.
    */
  private def record(job: EmbeddingJob, d: EmbeddingDocument, index: String): VectorRecord =
    VectorRecord(
      schemaVersion = 1,
      vectorKey = d.documentId,
      indexName = index,
      dimension = embedder.dimension,
      embeddingModelId = job.embeddingModelId,
      embeddingModelVersion = job.embeddingModelVersion,
      textHash = d.textHash,
      textS3Uri = d.textS3Uri,
      metadata = EmbeddingWorker.metadataFor(d),
      runId = job.runId,
    )

object EmbeddingWorker:
  val MetaDocumentType: String = "documentType"
  val MetaEntityId: String     = "entityId"
  val MetaEntityIdHash: String = "entityIdHash"
  val MetaWindow: String       = "window"
  val MetaChunkOrdinal: String = "chunkOrdinal"
  val MetaChunkCount: String   = "chunkCount"

  /** Compact metadata: type, entity, window, chunk position. An entity ID longer than the metadata
    * value limit (128) is stored as its SHA-256 under `entityIdHash` instead (never truncated). No
    * text, no excerpt.
    */
  def metadataFor(d: EmbeddingDocument): Map[String, String] =
    val entity =
      if d.entityId.length <= VectorRecord.MaxMetadataValueLength then MetaEntityId -> d.entityId
      else MetaEntityIdHash -> Hashing.textHash(d.entityId)
    Map(
      MetaDocumentType -> d.documentType,
      entity,
      MetaWindow       -> GraphWindow.label(d.window),
      MetaChunkOrdinal -> d.chunkOrdinal.toString,
      MetaChunkCount   -> d.chunkCount.toString,
    )

  def decodeUtf8(bytes: Array[Byte]): Either[String, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch case _: CharacterCodingException => Left("not valid UTF-8")
