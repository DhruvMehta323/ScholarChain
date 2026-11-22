package scholarchain.storage

import java.time.Instant

import scala.util.Try

import scholarchain.core.*

/** Records embedding runs in the RunLedger (`RunLedgerRepository`). Each ledger entry is one unit
  * of work: the producer run (stage `embedding-enqueue`) or one delivery attempt of one job (stage
  * `embedding-job`, `attempt` = the job's attempt, so retries are visible as entries with attempt
  * 2, 3, ...). An entry lists its inputs (documents with their text hashes), the code version, and
  * the parameters (model ID, version, dimension, bounds), and its `runId` is derived from them
  * (`Ids.runId`). It starts as `started` and ends `succeeded` or `failed`.
  *
  * Timestamps come only from the injected `clock` (an ISO-8601 instant string), never from the wall
  * clock, so a run with a fixed or counting clock is byte-reproducible. A failure reason is built
  * from stable codes only: never a text, a request body or a credential.
  */
final class EmbeddingLedger(
    repo: RunLedgerRepository,
    clock: () => String,
    codeVersion: String,
):
  import EmbeddingLedger.*

  private def build(
      stage: String,
      attempt: Int,
      params: Map[String, String],
      inputs: List[InputRef],
  ): StorageResult[RunLedgerEntry] =
    for id <- Ids
        .runId(stage, codeVersion, attempt, params, inputs)
        .left
        .map(e => StorageError.InvalidRequest(e.message))
    yield RunLedgerEntry(
      schemaVersion = 1,
      runId = id,
      stage = stage,
      attempt = attempt,
      status = RunStatus.Started,
      codeVersion = codeVersion,
      parameters = params,
      inputs = inputs,
      startedAt = clock(),
    )

  private def begin(e: RunLedgerEntry): StorageResult[RunLedgerEntry] =
    repo.record(e).flatMap {
      case WriteResult.Conflict(_) =>
        // The same unit of work is already recorded in a later state (a redelivery after a
        // completed attempt). Keep working; the caller counts this as a ledger conflict.
        Left(StorageError.AlreadyExists("ledger entry"))
      case _ => Right(e)
    }

  private def end(e: RunLedgerEntry, failure: Option[String]): StorageResult[WriteResult] =
    val now      = clock()
    val finished =
      (Try(Instant.parse(now)).toOption, Try(Instant.parse(e.startedAt)).toOption) match
        case (Some(n), Some(s)) if n.isBefore(s) => e.startedAt
        case _                                   => now
    val done = failure match
      case None =>
        e.copy(status = RunStatus.Succeeded, finishedAt = Some(finished), failureReason = None)
      case Some(r) =>
        e.copy(
          status = RunStatus.Failed,
          finishedAt = Some(finished),
          failureReason = Some(safeReason(r)),
        )
    repo.record(done)

  // ---- producer ----------------------------------------------------------------------------

  def beginProducer(
      runId: String,
      config: EmbeddingProducerConfig,
      jobs: List[EmbeddingJob],
      docs: List[EmbeddingDocument],
  ): StorageResult[RunLedgerEntry] =
    val manifest = Hashing.digest(
      "scholarchain.embedding-manifest.v1",
      docs.sortBy(_.documentId).flatMap(d => List(d.documentId, d.textHash)),
    )
    val inputs = List(
      InputRef("source-run", runId, None),
      InputRef("document-manifest", "v1", Some(manifest)),
    )
    val params = Map(
      "embeddingModelId"      -> config.embeddingModelId,
      "embeddingModelVersion" -> config.embeddingModelVersion,
      "maxDocumentsPerJob"    -> config.maxDocumentsPerJob.toString,
      "documentCount"         -> docs.size.toString,
      "jobCount"              -> jobs.size.toString,
      "queue"                 -> config.queueName,
    )
    build(ProducerStage, 1, params, inputs).flatMap(begin)

  def endProducer(e: RunLedgerEntry, failureCode: Option[String]): StorageResult[WriteResult] =
    end(e, failureCode.map(c => s"enqueue failed ($c)"))

  // ---- worker ------------------------------------------------------------------------------

  def beginJob(
      job: EmbeddingJob,
      embedderDimension: Int,
      maxAttempts: Int,
      indexOverride: Option[String],
  ): StorageResult[RunLedgerEntry] =
    val inputs = InputRef("job-run", job.runId, None) ::
      job.documents.map(d => InputRef(d.documentId, "text", Some(d.textHash)))
    val params = Map(
      "jobId"                 -> job.jobId,
      "embeddingModelId"      -> job.embeddingModelId,
      "embeddingModelVersion" -> job.embeddingModelVersion,
      "dimension"             -> embedderDimension.toString,
      "documentCount"         -> job.documents.size.toString,
      "maxAttempts"           -> maxAttempts.toString,
      "index"                 -> indexOverride.getOrElse("by-document-type"),
    )
    build(JobStage, job.attempt, params, inputs).flatMap(begin)

  /** `failure = None` ends the entry `succeeded`; `Some(reason)` ends it `failed`. */
  def endJob(e: RunLedgerEntry, failure: Option[String]): StorageResult[WriteResult] =
    end(e, failure)

object EmbeddingLedger:
  val ProducerStage: String = "embedding-enqueue"
  val JobStage: String      = "embedding-job"

  /** Bounds a failure reason to the ledger limit. Callers pass stable codes only. */
  def safeReason(r: String): String =
    val t = r.trim.take(RunLedgerEntry.MaxFailureReasonLength)
    if t.isEmpty then "failed" else t
