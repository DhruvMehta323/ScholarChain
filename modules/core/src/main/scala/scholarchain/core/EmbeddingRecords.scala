package scholarchain.core

import WirePickle.{*, given}
import ValidationError.*

/** Phase 08 embedding contracts (additive; no existing record, wire form or ID function changes).
  *
  * Document types: the vocabulary is the one the Phase 06 `SemanticSummary.documentType` already
  * uses (`researcher_profile`, `community_profile`, `path_summary`, `topic_summary`) plus
  * `paper_abstract` for the fifth index. Each type maps to exactly one of the five production
  * vector indexes (`VectorIndexes`).
  *
  * Frozen JSON examples: classpath resources `contracts/embedding-document.example.json`,
  * `contracts/embedding-job.example.json`, `contracts/semantic-context-item.example.json`. Each
  * validates under its contract.
  */
object EmbeddingDocumentTypes:
  val ResearcherProfile: String = "researcher_profile"
  val CommunityProfile: String  = "community_profile"
  val PathSummary: String       = "path_summary"
  val TopicSummary: String      = "topic_summary"
  val PaperAbstract: String     = "paper_abstract"

  val all: List[String] =
    List(ResearcherProfile, CommunityProfile, PathSummary, TopicSummary, PaperAbstract)

  /** Document type to its production vector index name. */
  val indexByType: Map[String, String] = Map(
    ResearcherProfile -> VectorIndexes.ResearcherProfile,
    CommunityProfile  -> VectorIndexes.CollaborationCommunity,
    PathSummary       -> VectorIndexes.CollaborationPath,
    TopicSummary      -> VectorIndexes.TopicInstitutionSummary,
    PaperAbstract     -> VectorIndexes.PaperAbstract,
  )

  def indexFor(documentType: String): Option[String] = indexByType.get(documentType)

/** A reference to the text of one embeddable document (or one chunk of it). The text itself is NOT
  * in the record: it lives in S3 at `textS3Uri` and `textHash` is its SHA-256 (`Hashing.textHash`).
  *   - `documentId` must equal `EmbeddingDocument.idFor(documentType, entityId, window,
  *     chunkOrdinal)`; it is the intended vector key (at most `VectorRecord.MaxVectorKeyLength`).
  *   - `documentType`: one of `EmbeddingDocumentTypes.all`. `entityId`: a `<kind>:<...>` reference
  *     of at most `MaxEntityIdLength` characters.
  *   - `chunkOrdinal` is 0-based; `chunkCount` >= 1 and `chunkOrdinal < chunkCount`. An unchunked
  *     document has ordinal 0 and count 1.
  *   - `sourceRunId`: the run (`run:<64 hex>`) that produced the source summary or record.
  *
  * Example: `contracts/embedding-document.example.json`.
  */
final case class EmbeddingDocument(
    schemaVersion: Int,
    documentId: String,
    documentType: String,
    entityId: String,
    window: GraphWindow,
    textS3Uri: String,
    textHash: String,
    chunkOrdinal: Int,
    chunkCount: Int,
    sourceRunId: String,
) derives ReadWriter

object EmbeddingDocument:
  val MaxEntityIdLength: Int = 400
  val MaxChunkCount: Int     = 1000

  /** Deterministic document ID: `<documentType>:<entityId>:<window label>:<chunkOrdinal>`. */
  def idFor(
      documentType: String,
      entityId: String,
      window: GraphWindow,
      chunkOrdinal: Int,
  ): String =
    s"$documentType:$entityId:${GraphWindow.label(window)}:$chunkOrdinal"

  given Contract[EmbeddingDocument] = V.contract(d =>
    V.all(
      V.schema(d.schemaVersion),
      if EmbeddingDocumentTypes.all.contains(d.documentType) then V.ok
      else Left(InvalidField("documentType", "must be one of the embedding document types")),
      V.unit(Ids.checkAnyRef(d.entityId)),
      if d.entityId.length <= MaxEntityIdLength then V.ok
      else Left(InvalidField("entityId", s"must be at most $MaxEntityIdLength characters")),
      V.unit(GraphWindow.validate(d.window)),
      StorageChecks.boundedToken("documentId", d.documentId, VectorRecord.MaxVectorKeyLength),
      if d.documentId == idFor(d.documentType, d.entityId, d.window, d.chunkOrdinal) then V.ok
      else Left(InvalidField("documentId", "does not match the value derived from its fields")),
      StorageChecks.s3Uri("textS3Uri", d.textS3Uri),
      StorageChecks.sha256("textHash", d.textHash),
      V.atLeast("chunkOrdinal", d.chunkOrdinal, 0),
      V.atLeast("chunkCount", d.chunkCount, 1),
      if d.chunkCount <= MaxChunkCount then V.ok
      else Left(InvalidField("chunkCount", s"must be <= $MaxChunkCount")),
      if d.chunkOrdinal < d.chunkCount then V.ok
      else Left(InvalidField("chunkOrdinal", "must be < chunkCount")),
      StorageChecks.runId("sourceRunId", d.sourceRunId),
    )
  )

/** Lifecycle of an embedding job. Wire names are frozen (lowercase, `_` separated). */
enum EmbeddingJobStatus(val wire: String):
  case Pending      extends EmbeddingJobStatus("pending")
  case Succeeded    extends EmbeddingJobStatus("succeeded")
  case Failed       extends EmbeddingJobStatus("failed")
  case DeadLettered extends EmbeddingJobStatus("dead_lettered")

object EmbeddingJobStatus:
  given WirePickle.ReadWriter[EmbeddingJobStatus] =
    Wire.enumRw("EmbeddingJobStatus", values.toSeq, _.wire)

/** One unit of work on the embedding queue: embed a bounded batch of documents with one model.
  *   - `documents`: 1..`MaxDocuments` (50) references with distinct `documentId`s. The serialized
  *     job must be at most `MaxSerializedBytes` (200 KiB UTF-8) so it fits an SQS message (256 KiB)
  *     with headroom; both bounds are enforced.
  *   - `attempt` >= 1: the 1-based delivery attempt (a fresh job starts at 1; a retry increments).
  *   - `status`: `Pending` and `Succeeded` carry no `failureReason`; `Failed` and `DeadLettered`
  *     need one (short, at most `RunLedgerEntry.MaxFailureReasonLength`, never a secret, request
  *     body or API key).
  *   - `runId`: the run (`run:<64 hex>`) the job belongs to, for the RunLedger.
  *
  * Example: `contracts/embedding-job.example.json`.
  */
final case class EmbeddingJob(
    schemaVersion: Int,
    jobId: String,
    runId: String,
    documents: List[EmbeddingDocument],
    embeddingModelId: String,
    embeddingModelVersion: String,
    attempt: Int,
    status: EmbeddingJobStatus,
    failureReason: Option[String] = None,
) derives ReadWriter

object EmbeddingJob:
  val MaxDocuments: Int       = 50
  val MaxSerializedBytes: Int = 200 * 1024

  given Contract[EmbeddingJob] = V.contract(j =>
    V.all(
      V.schema(j.schemaVersion),
      StorageChecks.boundedToken("jobId", j.jobId, 128),
      StorageChecks.runId("runId", j.runId),
      V.nonEmpty("documents", j.documents),
      if j.documents.size <= MaxDocuments then V.ok
      else Left(InvalidField("documents", s"must have at most $MaxDocuments entries")),
      V.each(j.documents)(d => V.unit(summon[Contract[EmbeddingDocument]].validate(d))),
      V.distinct("documents", j.documents.map(_.documentId)),
      V.unit(Ids.token("embeddingModelId", j.embeddingModelId)),
      V.unit(Ids.token("embeddingModelVersion", j.embeddingModelVersion)),
      V.atLeast("attempt", j.attempt, 1),
      j.failureReason.fold(V.ok)(
        StorageChecks.boundedText("failureReason", _, RunLedgerEntry.MaxFailureReasonLength)
      ),
      j.status match
        case EmbeddingJobStatus.Pending | EmbeddingJobStatus.Succeeded =>
          if j.failureReason.isEmpty then V.ok
          else Left(InvalidField("failureReason", "must be absent unless failed or dead_lettered"))
        case EmbeddingJobStatus.Failed | EmbeddingJobStatus.DeadLettered =>
          if j.failureReason.isDefined then V.ok
          else Left(InvalidField("failureReason", "required when failed or dead_lettered")),
      if WirePickle.write(j).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <=
          MaxSerializedBytes
      then V.ok
      else Left(InvalidField("documents", s"serialized job must be <= $MaxSerializedBytes bytes")),
    )
  )

/** One semantic-retrieval hit handed to the SemanticContextAgent.
  *
  * SEMANTIC CONTEXT ONLY. A vector hit is similarity evidence: it may suggest which researcher,
  * community, path or topic text is relevant, and it carries a short excerpt of the S3 text. Exact
  * counts, paths, components and bridges NEVER come from a `SemanticContextItem`; they come from
  * the graph store or the metric stores. Nothing here may be cited as a graph fact.
  *   - `vectorKey` (at most `VectorRecord.MaxVectorKeyLength`), `score` (finite; higher is more
  *     similar), `documentType` (one of `EmbeddingDocumentTypes.all`), `entityId`, `window`,
  *     `textS3Uri` (pointer to the authoritative text).
  *   - `excerpt`: non-blank, at most `MaxExcerptLength` (300) characters; a prefix or passage of
  *     the text, never the full document.
  *
  * Example: `contracts/semantic-context-item.example.json`.
  */
final case class SemanticContextItem(
    schemaVersion: Int,
    vectorKey: String,
    score: Double,
    documentType: String,
    entityId: String,
    window: GraphWindow,
    textS3Uri: String,
    excerpt: String,
) derives ReadWriter

object SemanticContextItem:
  val MaxExcerptLength: Int = 300

  given Contract[SemanticContextItem] = V.contract(i =>
    V.all(
      V.schema(i.schemaVersion),
      StorageChecks.boundedToken("vectorKey", i.vectorKey, VectorRecord.MaxVectorKeyLength),
      V.finite("score", i.score),
      if EmbeddingDocumentTypes.all.contains(i.documentType) then V.ok
      else Left(InvalidField("documentType", "must be one of the embedding document types")),
      V.unit(Ids.checkAnyRef(i.entityId)),
      if i.entityId.length <= EmbeddingDocument.MaxEntityIdLength then V.ok
      else
        Left(
          InvalidField(
            "entityId",
            s"must be at most ${EmbeddingDocument.MaxEntityIdLength} characters",
          )
        )
      ,
      V.unit(GraphWindow.validate(i.window)),
      StorageChecks.s3Uri("textS3Uri", i.textS3Uri),
      StorageChecks.boundedText("excerpt", i.excerpt, MaxExcerptLength),
    )
  )
