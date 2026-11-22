package scholarchain.core

import java.time.Instant

import scala.util.Try

import WirePickle.{*, given}
import ValidationError.*

/** Phase 07 storage contracts (additive; no existing record, wire form or ID function changes).
  *
  * Record roles:
  *   - `VectorRecord`: the compact, filterable description of one stored embedding. The embedding
  *     itself and the full text are NOT in the record: the text lives in S3 (`textS3Uri`, checked
  *     by `textHash`).
  *   - `RunLedgerEntry`, `ArtifactRegistryEntry`, `QuerySessionRecord`, `EntityLinkCacheEntry`: the
  *     item payloads of the DynamoDB tables RunLedger, ArtifactRegistry, QuerySession and
  *     EntityLinkCache. Partition and sort keys are chosen by storage (`STORAGE-CONTRACTS.md`).
  *   - `EvidenceBundle` (Records.scala) is already the storage form of the EvidenceBundle table: it
  *     is a versioned record with its own contract and provenance, keyed by `queryId`. No second
  *     form is added. Because a bundle may carry item text, an adapter must keep the serialized
  *     JSON within the item size of its store (spilling to S3 is an adapter decision).
  *
  * Time conventions, consistent with `Provenance` and `RunManifest`: timestamps are ISO-8601
  * instants as strings. The only epoch value is `expiresAtEpochSeconds` (a DynamoDB TTL attribute
  * is epoch seconds), and it exists only where data is genuinely disposable.
  *
  * Frozen JSON examples: classpath resources `contracts/vector-record.example.json`,
  * `contracts/run-ledger-entry.example.json`, `contracts/artifact-registry-entry.example.json`,
  * `contracts/query-session-record.example.json`, `contracts/entity-link-cache-entry.example.json`.
  * Each validates under its contract.
  */
private[core] object StorageChecks:
  import V.*

  private val S3UriPattern = """^s3://[a-z0-9][a-z0-9.\-]{1,61}[a-z0-9]/[^\s\p{Cntrl}]+$""".r

  /** S3 URI `s3://<bucket>/<object>` with a syntactically valid bucket and a non-empty object name
    * without whitespace or control characters, at most 1100 characters in all.
    */
  def isS3Uri(s: String): Boolean = s.length <= 1100 && S3UriPattern.matches(s)

  def s3Uri(field: String, s: String): R =
    if isS3Uri(s) then ok else Left(InvalidField(field, "must be an s3://<bucket>/<object> URI"))

  def sha256(field: String, s: String): R =
    if Hashing.isSha256Hex(s) then ok
    else Left(InvalidField(field, "must be 64 lowercase hex characters (SHA-256)"))

  def instant(field: String, t: String): R =
    if Try(Instant.parse(t)).isSuccess then ok
    else Left(InvalidField(field, "must be an ISO-8601 instant"))

  def epochOf(t: String): Option[Long] = Try(Instant.parse(t).getEpochSecond).toOption

  def boundedToken(field: String, s: String, max: Int): R =
    all(
      unit(Ids.token(field, s)),
      if s.length <= max then ok else Left(InvalidField(field, s"must be at most $max characters")),
    )

  def boundedText(field: String, s: String, max: Int): R =
    all(
      nonBlank(field, s),
      if s.length <= max then ok else Left(InvalidField(field, s"must be at most $max characters")),
    )

  def runId(field: String, s: String): R =
    unit(Ids.checkRunId(s)).left.map(_ => InvalidField(field, "must be a valid run ID"))

  def provRun(runId: String, p: Provenance): R =
    p.runId match
      case Some(r) if r != runId =>
        Left(InvalidField("provenance.runId", "must equal runId when present"))
      case _ => ok

/** Run state of a stage in the RunLedger. Wire names are frozen. */
enum RunStatus(val wire: String):
  case Started   extends RunStatus("started")
  case Succeeded extends RunStatus("succeeded")
  case Failed    extends RunStatus("failed")

object RunStatus:
  given WirePickle.ReadWriter[RunStatus] = Wire.enumRw("RunStatus", values.toSeq, _.wire)

/** Lifecycle of one query session. Wire names are frozen. */
enum QuerySessionStatus(val wire: String):
  case Received extends QuerySessionStatus("received")
  case Answered extends QuerySessionStatus("answered")
  case Rejected extends QuerySessionStatus("rejected")
  case Failed   extends QuerySessionStatus("failed")

object QuerySessionStatus:
  given WirePickle.ReadWriter[QuerySessionStatus] =
    Wire.enumRw("QuerySessionStatus", values.toSeq, _.wire)

/** The five S3 Vectors index names of ScholarChain (names are part of the contract). */
object VectorIndexes:
  val ResearcherProfile: String       = "researcher-profile-v1"
  val CollaborationCommunity: String  = "collaboration-community-v1"
  val PaperAbstract: String           = "paper-abstract-v1"
  val CollaborationPath: String       = "collaboration-path-v1"
  val TopicInstitutionSummary: String = "topic-institution-summary-v1"

  val all: List[String] = List(
    ResearcherProfile,
    CollaborationCommunity,
    PaperAbstract,
    CollaborationPath,
    TopicInstitutionSummary,
  )

/** Descriptor of one stored embedding (the vector payload is not part of the record).
  *
  * ScholarChain policy limits (NOT AWS limits; the AWS service limits are recorded separately by
  * storage-engineer): at most `MaxMetadataKeys` metadata entries, entry name length at most
  * `MaxMetadataNameLength`, value length at most `MaxMetadataValueLength` characters,
  * `MaxVectorKeyLength` for the vector key, dimension in `1..MaxDimension`. Metadata holds small
  * scalar values only (researcher, window, source, topic, institution, community, document type,
  * ...); every value is a string. Long text never goes into metadata: it lives in S3 at
  * `textS3Uri`, and `textHash` is the SHA-256 of that text.
  */
final case class VectorRecord(
    schemaVersion: Int,
    vectorKey: String,
    indexName: String,
    dimension: Int,
    embeddingModelId: String,
    embeddingModelVersion: String,
    textHash: String,
    textS3Uri: String,
    metadata: Map[String, String],
    runId: String,
) derives ReadWriter

object VectorRecord:
  val MaxMetadataKeys: Int        = 16
  val MaxMetadataNameLength: Int  = 64
  val MaxMetadataValueLength: Int = 128
  val MaxVectorKeyLength: Int     = 512
  val MaxDimension: Int           = 4096

  given Contract[VectorRecord] = V.contract(r =>
    V.all(
      V.schema(r.schemaVersion),
      StorageChecks.boundedToken("vectorKey", r.vectorKey, MaxVectorKeyLength),
      StorageChecks.boundedToken("indexName", r.indexName, 63),
      V.atLeast("dimension", r.dimension, 1),
      if r.dimension <= MaxDimension then V.ok
      else Left(InvalidField("dimension", s"must be <= $MaxDimension")),
      V.unit(Ids.token("embeddingModelId", r.embeddingModelId)),
      V.unit(Ids.token("embeddingModelVersion", r.embeddingModelVersion)),
      StorageChecks.sha256("textHash", r.textHash),
      StorageChecks.s3Uri("textS3Uri", r.textS3Uri),
      if r.metadata.size <= MaxMetadataKeys then V.ok
      else Left(InvalidField("metadata", s"must have at most $MaxMetadataKeys entries")),
      V.each(r.metadata.keys) { k =>
        V.all(
          V.nonBlank("metadata.name", k),
          if k.length <= MaxMetadataNameLength then V.ok
          else
            Left(
              InvalidField("metadata.name", s"must be at most $MaxMetadataNameLength characters")
            ),
        )
      },
      V.each(r.metadata.values) { v =>
        if v.length <= MaxMetadataValueLength then V.ok
        else
          Left(
            InvalidField("metadata.value", s"must be at most $MaxMetadataValueLength characters")
          )
      },
      StorageChecks.runId("runId", r.runId),
    )
  )

/** One row of the RunLedger: a run of a stage, derived from the same inputs as `RunManifest`.
  *   - `runId` must equal `Ids.runId(stage, codeVersion, attempt, parameters, inputs)`.
  *   - `inputs` non-empty with valid `InputRef`s (checksums are SHA-256).
  *   - `Started`: `finishedAt` absent, `failureReason` absent. `Succeeded`: `finishedAt` present,
  *     `failureReason` absent. `Failed`: `finishedAt` and `failureReason` present.
  *   - `finishedAt` is not before `startedAt`. `manifestUri`, when present, is an S3 URI.
  *   - `failureReason` is a short non-blank text (at most `MaxFailureReasonLength`); it must never
  *     contain secrets or configuration values.
  */
final case class RunLedgerEntry(
    schemaVersion: Int,
    runId: String,
    stage: String,
    attempt: Int,
    status: RunStatus,
    codeVersion: String,
    parameters: Map[String, String],
    inputs: List[InputRef],
    startedAt: String,
    finishedAt: Option[String] = None,
    manifestUri: Option[String] = None,
    failureReason: Option[String] = None,
) derives ReadWriter

object RunLedgerEntry:
  val MaxFailureReasonLength: Int = 500

  given Contract[RunLedgerEntry] = V.contract(e =>
    V.all(
      V.schema(e.schemaVersion),
      V.nonEmpty("inputs", e.inputs),
      V.each(e.inputs)(i => V.unit(InputRef.validate(i))),
      V.matches(
        "runId",
        Ids.runId(e.stage, e.codeVersion, e.attempt, e.parameters, e.inputs),
        e.runId,
      ),
      StorageChecks.instant("startedAt", e.startedAt),
      e.finishedAt.fold(V.ok)(t =>
        V.all(
          StorageChecks.instant("finishedAt", t),
          (Try(Instant.parse(e.startedAt)), Try(Instant.parse(t))) match
            case (scala.util.Success(s), scala.util.Success(f)) if !f.isBefore(s) => V.ok
            case _ => Left(InvalidField("finishedAt", "must not be before startedAt")),
        )
      ),
      e.manifestUri.fold(V.ok)(StorageChecks.s3Uri("manifestUri", _)),
      e.failureReason.fold(V.ok)(
        StorageChecks.boundedText("failureReason", _, MaxFailureReasonLength)
      ),
      e.status match
        case RunStatus.Started =>
          if e.finishedAt.isEmpty && e.failureReason.isEmpty then V.ok
          else Left(InvalidField("status", "started run must have no finishedAt or failureReason"))
        case RunStatus.Succeeded =>
          if e.finishedAt.isDefined && e.failureReason.isEmpty then V.ok
          else Left(InvalidField("status", "succeeded run needs finishedAt and no failureReason"))
        case RunStatus.Failed =>
          if e.finishedAt.isDefined && e.failureReason.isDefined then V.ok
          else Left(InvalidField("status", "failed run needs finishedAt and failureReason")),
    )
  )

/** One registered output artifact of a run (table, CSV set, manifest, ...).
  *   - `artifactUri`: S3 URI. `contentHash`: SHA-256 hex of the artifact bytes (for a multi-file
  *     artifact, of its manifest). `sizeBytes` >= 0.
  *   - `artifactType`: token naming the kind (for example `parquet-table`, `neptune-csv`).
  *   - `provenance.runId`, when present, must equal `runId`.
  */
final case class ArtifactRegistryEntry(
    schemaVersion: Int,
    runId: String,
    artifactUri: String,
    artifactType: String,
    contentHash: String,
    sizeBytes: Long,
    createdAt: String,
    provenance: Provenance,
) derives ReadWriter

object ArtifactRegistryEntry:
  given Contract[ArtifactRegistryEntry] = V.contract(a =>
    V.all(
      V.schema(a.schemaVersion),
      StorageChecks.runId("runId", a.runId),
      StorageChecks.s3Uri("artifactUri", a.artifactUri),
      StorageChecks.boundedToken("artifactType", a.artifactType, 64),
      StorageChecks.sha256("contentHash", a.contentHash),
      V2Checks.nonNegative("sizeBytes", a.sizeBytes),
      StorageChecks.instant("createdAt", a.createdAt),
      V.prov(a.provenance),
      StorageChecks.provRun(a.runId, a.provenance),
    )
  )

/** One answered (or refused) query. Disposable: `expiresAtEpochSeconds` is required, must be after
  * `createdAt` and at most `MaxRetentionSeconds` (30 days, ScholarChain policy) after it.
  *   - `queryId`: `query:` reference (as `QueryPlan`). `questionHash`: SHA-256 of the question
  *     text; the question itself is not stored here.
  *   - `evidenceBundleHash`: SHA-256 of the serialized `EvidenceBundle`; required when `Answered`.
  *   - `answerUri`: optional S3 URI of the stored answer.
  */
final case class QuerySessionRecord(
    schemaVersion: Int,
    sessionId: String,
    queryId: String,
    questionHash: String,
    status: QuerySessionStatus,
    createdAt: String,
    expiresAtEpochSeconds: Long,
    evidenceBundleHash: Option[String] = None,
    answerUri: Option[String] = None,
    provenance: Provenance,
) derives ReadWriter

object QuerySessionRecord:
  val MaxRetentionSeconds: Long = 30L * 24 * 3600

  given Contract[QuerySessionRecord] = V.contract(s =>
    V.all(
      V.schema(s.schemaVersion),
      StorageChecks.boundedToken("sessionId", s.sessionId, 128),
      V.unit(Ids.checkEntityRef("query", s.queryId)),
      StorageChecks.sha256("questionHash", s.questionHash),
      StorageChecks.instant("createdAt", s.createdAt),
      StorageChecks.epochOf(s.createdAt) match
        case Some(c)
            if s.expiresAtEpochSeconds > c && s.expiresAtEpochSeconds <= c + MaxRetentionSeconds =>
          V.ok
        case _ =>
          Left(
            InvalidField(
              "expiresAtEpochSeconds",
              "must be after createdAt and within the 30-day retention policy",
            )
          ),
      s.evidenceBundleHash.fold(V.ok)(StorageChecks.sha256("evidenceBundleHash", _)),
      s.answerUri.fold(V.ok)(StorageChecks.s3Uri("answerUri", _)),
      if s.status == QuerySessionStatus.Answered && s.evidenceBundleHash.isEmpty then
        Left(InvalidField("evidenceBundleHash", "required when the session is answered"))
      else V.ok,
      V.prov(s.provenance),
    )
  )

/** Cached entity-link decision for one mention.
  *   - `cacheKey`: token (non-blank, no whitespace, at most 128 chars) chosen by the producer.
  *   - `mentionId`: `mention:` reference. `researcherId`: canonical researcher ID, required when
  *     `outcome` is `Match`.
  *   - `confidence` in [0, 1]. `policyVersion`: token of the match policy that decided.
  *   - `expiresAtEpochSeconds`, when present, must be after `createdAt`. Absent means the entry
  *     lives as long as its policy version.
  *   - `provenance.runId`, when present, must equal `runId`.
  */
final case class EntityLinkCacheEntry(
    schemaVersion: Int,
    cacheKey: String,
    mentionId: String,
    researcherId: Option[String] = None,
    outcome: MatchOutcome,
    confidence: Double,
    policyVersion: String,
    runId: String,
    createdAt: String,
    expiresAtEpochSeconds: Option[Long] = None,
    provenance: Provenance,
) derives ReadWriter

object EntityLinkCacheEntry:
  given Contract[EntityLinkCacheEntry] = V.contract(e =>
    V.all(
      V.schema(e.schemaVersion),
      StorageChecks.boundedToken("cacheKey", e.cacheKey, 128),
      V.unit(Ids.checkMentionId(e.mentionId)),
      e.researcherId.fold(V.ok)(r => V.unit(Ids.checkResearcherId(r))),
      if e.outcome == MatchOutcome.Match && e.researcherId.isEmpty then
        Left(InvalidField("researcherId", "required when outcome is match"))
      else V.ok,
      V.confidence("confidence", e.confidence),
      V.unit(Ids.token("policyVersion", e.policyVersion)),
      StorageChecks.runId("runId", e.runId),
      StorageChecks.instant("createdAt", e.createdAt),
      e.expiresAtEpochSeconds.fold(V.ok)(x =>
        StorageChecks.epochOf(e.createdAt) match
          case Some(c) if x > c => V.ok
          case _ => Left(InvalidField("expiresAtEpochSeconds", "must be after createdAt"))
      ),
      V.prov(e.provenance),
      StorageChecks.provRun(e.runId, e.provenance),
    )
  )
