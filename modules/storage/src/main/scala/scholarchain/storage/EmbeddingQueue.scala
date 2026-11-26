package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.*

/** Phase 08 embedding ingestion path, producer side. Everything here is deterministic: no clock, no
  * random ID, no unordered iteration in an output. IDs are derived from content; a clock is only
  * used by the ledger, and it is injected there.
  */

/** A document reference together with its authoritative text. The text is written to the object
  * store at `document.textS3Uri`; it never travels on the queue and never goes into vector
  * metadata.
  */
final case class EmbeddingSource(document: EmbeddingDocument, text: String)

/** The wire form of a queue message: the validated `EmbeddingJob` JSON (`Wire.write`). Reading is
  * total: a body that is not a valid job is a `Left`, never an exception.
  */
object EmbeddingJobMessage:
  def encode(job: EmbeddingJob): StorageResult[String] =
    Wire.write(job).left.map(e => StorageError.InvalidRequest(e.message))

  def decode(body: String): Either[String, EmbeddingJob] =
    Wire.read[EmbeddingJob](body).left.map(e => s"invalid job message (${e.code})")

  /** Deduplication ID of a message: SHA-256 hex (64 characters, within the SQS limit of 128) of the
    * job ID, the attempt and a purpose tag, so a resend of the same step is idempotent where the
    * queue deduplicates.
    */
  def dedupId(jobId: String, attempt: Int, purpose: String): String =
    Hashing.digest("scholarchain.embedding-dedup.v1", Seq(jobId, attempt.toString, purpose))

/** Producer bounds. `maxDocumentsPerJob` comes from `embedding.queue.max-documents-per-job` (at
  * most `EmbeddingJob.MaxDocuments`). `maxDocumentsPerRun` bounds one `enqueue` call.
  */
final case class EmbeddingProducerConfig(
    queueName: String,
    maxDocumentsPerJob: Int,
    maxDocumentsPerRun: Int,
    embeddingModelId: String,
    embeddingModelVersion: String,
)

object EmbeddingProducerConfig:
  def fromSettings(s: EmbeddingSettings, maxDocumentsPerRun: Int): EmbeddingProducerConfig =
    EmbeddingProducerConfig(
      s.queueName,
      s.maxDocumentsPerJob,
      maxDocumentsPerRun,
      s.modelId,
      s.modelVersion,
    )

/** Why the producer did not accept a source document. Stable codes, used as report keys. */
object ProducerRejection:
  val InvalidDocument: String   = "invalid-document"
  val TextHashMismatch: String  = "text-hash-mismatch"
  val TextEncoding: String      = "text-not-encodable"
  val TextConflict: String      = "text-conflict"
  val DuplicateConflict: String = "duplicate-id-different-text"
  val StoreFailed: String       = "text-store-failed"

final case class RejectedSource(documentId: String, reason: String)

/** Outcome of `EmbeddingProducer.enqueue`. All lists are in deterministic order (documents by ID).
  * `textsWritten` + `textsUnchanged` + rejected = distinct documents seen.
  */
final case class ProducerReport(
    runId: String,
    documentsSeen: Int,
    textsWritten: Int,
    textsUnchanged: Int,
    rejected: List[RejectedSource],
    jobIds: List[String],
    jobsEnqueued: Int,
    ledgerRunId: Option[String],
):
  def rejectedByReason: Map[String, Int] =
    scala.collection.immutable.TreeMap.from(
      rejected.groupBy(_.reason).map((k, v) => k -> v.size)
    )

/** Writes the authoritative text of each document to the object store and enqueues `EmbeddingJob`s.
  *
  * For each source (sorted by document type, then document ID):
  *   1. the `EmbeddingDocument` must satisfy its contract;
  *   2. `Hashing.textHash(text)` must equal `document.textHash` (else `text-hash-mismatch`);
  *   3. the text is written, UTF-8, to `document.textS3Uri` with `ObjectStores.putIdempotent`:
  *      identical content already there is a no-op, different content is `text-conflict` and is
  *      never overwritten.
  *
  * Documents whose text is stored are grouped by document type (so a job targets one index) and cut
  * into jobs of at most `maxDocumentsPerJob` documents and at most
  * `EmbeddingJob.MaxSerializedBytes` bytes. A job ID is derived from the run, the model and the
  * document IDs; its deduplication ID from the job ID and attempt 1. Re-running the producer on the
  * same input rewrites nothing and resends the same jobs (the queue deduplicates where it can; the
  * worker is idempotent anyway).
  *
  * The producer records one ledger entry (stage `embedding-enqueue`) when a ledger is given.
  */
final class EmbeddingProducer(
    config: EmbeddingProducerConfig,
    queue: WorkQueue,
    objects: ObjectStore,
    ledger: Option[EmbeddingLedger] = None,
):
  private val EnvelopeBytes = 2048

  def enqueue(runId: String, sources: List[EmbeddingSource]): StorageResult[ProducerReport] =
    if Ids.checkRunId(runId).isLeft then Left(StorageError.InvalidRequest("invalid run ID"))
    else if sources.isEmpty then Left(StorageError.InvalidRequest("nothing to enqueue"))
    else if config.maxDocumentsPerJob < 1 || config.maxDocumentsPerJob > EmbeddingJob.MaxDocuments
    then Left(StorageError.InvalidRequest("max documents per job is out of range"))
    else if sources.size > config.maxDocumentsPerRun then
      Left(StorageError.InvalidRequest("more documents than the run bound"))
    else
      val sorted = sources.sortBy(s => (s.document.documentType, s.document.documentId))
      val seen   = scala.collection.mutable.LinkedHashMap.empty[String, EmbeddingSource]
      val bad    = scala.collection.mutable.ListBuffer.empty[RejectedSource]
      var wrote  = 0
      var same   = 0
      var failure: Option[StorageError] = None

      sorted.foreach { s =>
        val d = s.document
        seen.get(d.documentId) match
          case Some(first) =>
            if first.document.textHash != d.textHash then
              bad += RejectedSource(d.documentId, ProducerRejection.DuplicateConflict)
          case None =>
            storeText(s) match
              case Right(Some(true))  => wrote += 1; seen.update(d.documentId, s)
              case Right(Some(false)) => same += 1; seen.update(d.documentId, s)
              case Right(None)        => ()
              case Left(Left(why))    => bad += RejectedSource(d.documentId, why)
              case Left(Right(err))   => if failure.isEmpty then failure = Some(err)
      }

      failure match
        case Some(err) => Left(err)
        case None      =>
          val accepted = seen.values.toList.map(_.document)
          val jobs     = cutJobs(runId, accepted)
          val started  = ledger.map(_.beginProducer(runId, config, jobs, accepted))
          val sent     = jobs.foldLeft[StorageResult[Unit]](Right(())) { (acc, job) =>
            acc.flatMap(_ =>
              EmbeddingJobMessage.encode(job).flatMap { body =>
                queue
                  .send(
                    config.queueName,
                    body,
                    Some(EmbeddingJobMessage.dedupId(job.jobId, job.attempt, "enqueue")),
                  )
                  .map(_ => ())
              }
            )
          }
          val ledgerRun = started.flatMap(_.toOption)
          sent match
            case Left(err) =>
              ledger.foreach(l => ledgerRun.foreach(l.endProducer(_, Some(err.code))))
              Left(err)
            case Right(_) =>
              ledger.foreach(l => ledgerRun.foreach(l.endProducer(_, None)))
              Right(
                ProducerReport(
                  runId,
                  seen.size + bad.count(_.reason == ProducerRejection.DuplicateConflict),
                  wrote,
                  same,
                  bad.toList.sortBy(r => (r.documentId, r.reason)),
                  jobs.map(_.jobId),
                  jobs.size,
                  ledgerRun.map(_.runId),
                )
              )

  /** `Right(Some(true))` written, `Right(Some(false))` already there, `Right(None)` never (kept for
    * symmetry); `Left(Left(code))` a rejected document, `Left(Right(err))` a retryable or fatal
    * store failure that aborts the run (nothing is half-enqueued: no job is sent yet).
    */
  private def storeText(
      s: EmbeddingSource
  ): Either[Either[String, StorageError], Option[Boolean]] =
    val d = s.document
    if summon[Contract[EmbeddingDocument]].validate(d).isLeft then
      Left(Left(ProducerRejection.InvalidDocument))
    else
      val bytes = s.text.getBytes(StandardCharsets.UTF_8)
      if s.text.isEmpty || new String(bytes, StandardCharsets.UTF_8) != s.text then
        Left(Left(ProducerRejection.TextEncoding))
      else if Hashing.sha256Hex(bytes) != d.textHash then
        Left(Left(ProducerRejection.TextHashMismatch))
      else
        ObjectLocation.parse(d.textS3Uri) match
          case Left(_)    => Left(Left(ProducerRejection.InvalidDocument))
          case Right(loc) =>
            // Write first (If-None-Match): a HEAD of a missing key returns 403, not 404, under a
            // role without a bucket-wide s3:ListBucket. HEAD only runs once the object exists.
            objects.putIfAbsent(
              loc,
              bytes,
              Some("text/plain; charset=utf-8"),
              Some(d.textHash),
            ) match
              case Right(_)                            => Right(Some(true))
              case Left(StorageError.AlreadyExists(_)) =>
                // already there: same content is fine, different content is a conflict
                objects.head(loc) match
                  case Right(Some(m)) if m.contentHash == d.textHash => Right(Some(false))
                  case Right(_)               => Left(Left(ProducerRejection.TextConflict))
                  case Left(e) if e.retryable => Left(Right(e))
                  case Left(_)                => Left(Left(ProducerRejection.StoreFailed))
              case Left(e) if e.retryable => Left(Right(e))
              case Left(_)                => Left(Left(ProducerRejection.StoreFailed))

  private def docBytes(d: EmbeddingDocument): Int =
    WirePickle.write(d).getBytes(StandardCharsets.UTF_8).length + 1

  /** Groups by document type, then cuts by count and serialized size. */
  private def cutJobs(runId: String, docs: List[EmbeddingDocument]): List[EmbeddingJob] =
    val byType = docs.groupBy(_.documentType).toList.sortBy(_._1)
    byType.flatMap { (_, ofType) =>
      val groups = scala.collection.mutable.ListBuffer.empty[List[EmbeddingDocument]]
      var cur    = Vector.empty[EmbeddingDocument]
      var bytes  = EnvelopeBytes
      ofType.sortBy(_.documentId).foreach { d =>
        val b = docBytes(d)
        if cur.nonEmpty && (cur.size >= config.maxDocumentsPerJob ||
            bytes + b > EmbeddingJob.MaxSerializedBytes)
        then
          groups += cur.toList
          cur = Vector.empty
          bytes = EnvelopeBytes
        cur = cur :+ d
        bytes += b
      }
      if cur.nonEmpty then groups += cur.toList
      groups.toList.map { g =>
        EmbeddingJob(
          1,
          EmbeddingProducer.jobId(runId, config, g),
          runId,
          g,
          config.embeddingModelId,
          config.embeddingModelVersion,
          1,
          EmbeddingJobStatus.Pending,
        )
      }
    }

object EmbeddingProducer:
  /** `embjob-` plus 32 hex characters of a digest of the run, the model and the document IDs. */
  def jobId(runId: String, c: EmbeddingProducerConfig, docs: List[EmbeddingDocument]): String =
    val h = Hashing.digest(
      "scholarchain.embedding-job.v1",
      Seq(runId, c.embeddingModelId, c.embeddingModelVersion) ++ docs.map(_.documentId),
    )
    s"embjob-${h.take(32)}"
