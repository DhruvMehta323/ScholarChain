package scholarchain.rag.runtime

import java.nio.charset.StandardCharsets
import java.time.Instant

import scholarchain.core.*
import WirePickle.{*, given}

/** A prompt (and its schema) a query used, by version and file hash. */
final case class PromptUse(
    promptId: String,
    version: String,
    promptSha256: String,
    schemaSha256: String,
) derives ReadWriter

/** What the resolver decided at one subject position (IDs and codes only, no user text). */
final case class ResolutionAudit(
    position: Int,
    status: String,
    resolvedId: Option[String],
    candidateIds: List[String],
    reasonCodes: List[String],
) derives ReadWriter

/** The audit record of one query (`schemaVersion` 1). It holds IDs, hashes, versions, counts,
  * latencies and typed verdicts; it never holds prompt text, model output text, a key, a
  * configuration value or model reasoning. The question is represented by its SHA-256.
  *
  * Fields: the `QueryPlanV2`; whether the planner fell back; the resolution per subject and the
  * resolved IDs; the graph and semantic evidence IDs (kept apart) and the bundle hash; the metric
  * (policy) versions and Spark run IDs of the evidence; the prompt versions and hashes; every
  * `ModelCall`; latencies; every `ClaimVerification`; the IDs of removed claims; whether synthesis
  * fell back to the template; whether and why the answer abstained; and the answer hash.
  */
final case class QueryAudit(
    schemaVersion: Int,
    queryId: String,
    sessionId: String,
    questionHash: String,
    plan: Option[QueryPlanV2],
    plannerFallback: Boolean,
    plannerReasonCodes: List[String],
    resolutions: List[ResolutionAudit],
    resolvedIds: List[String],
    graphEvidenceIds: List[String],
    semanticEvidenceIds: List[String],
    evidenceBundleHash: Option[String],
    metricVersions: List[String],
    graphRunIds: List[String],
    prompts: List[PromptUse],
    modelId: String,
    modelCalls: List[ModelCall],
    stageLatenciesMillis: Map[String, Long],
    totalLatencyMillis: Long,
    verifications: List[ClaimVerification],
    removedClaimIds: List[String],
    synthesisFallback: Boolean,
    abstained: Boolean,
    abstainReason: Option[String],
    answerHash: Option[String],
    codeVersion: String,
) derives ReadWriter

/** What persisting a query's audit did. Each part is `written`, `unchanged` (an identical record
  * was already stored: a re-run), `conflict` (a different record exists; nothing is overwritten) or
  * `failed`. `errors` holds fixed codes only.
  */
final case class AuditResult(
    session: String,
    bundle: String,
    auditObject: String,
    auditUri: Option[String],
    bundleHash: Option[String],
    errors: List[String],
):
  def ok: Boolean = errors.isEmpty

/** Stage 10: persists the audit of a query through the core ports, in the layouts of the storage
  * repositories (no dependency on `modules/storage`):
  *
  *   - QuerySession table: `pk = sessionId`; item `payload` (the `QuerySessionRecord` JSON),
  *     `payloadHash`, `status`, `expiry` (the TTL as text) and the TTL attribute. A session is
  *     written `received` when the query starts and moves to `answered`, `rejected` (an abstention
  *     or an invalid plan) or `failed` with the condition `status = received`; an identical record
  *     is `unchanged`, a different one is a `conflict` and is never overwritten. Retention is 7
  *     days (the contract allows at most 30).
  *   - EvidenceBundle table: `pk = queryId`, `sk = bundleHash` (SHA-256 of the bundle JSON), item
  *     `payload` + `payloadHash`; a bundle above 300,000 bytes is spilled to the object store and
  *     the item holds `payloadUri` + hash. Content-addressed, written with `NotExists`.
  *   - the `QueryAudit` JSON as an object `<prefix>/<sha256(queryId)>/<sha256(audit)>.json` in the
  *     audit bucket (`putIfAbsent`), whose URI is the session's `answerUri`.
  */
final class AuditAgent(
    kv: KeyValueStore,
    objects: ObjectStore,
    tables: RuntimeTables,
    clock: RuntimeClock,
):
  import AuditAgent.*

  private def provenance(questionHash: String): Provenance =
    Provenance(
      SchemaVersion.Current,
      SourceType.ScholarChain,
      List(InputRef("question", "sha256", Some(questionHash))),
      RuntimeCode.Version,
      Map("stage" -> "rag-query"),
    )

  private def record(
      queryId: String,
      sessionId: String,
      questionHash: String,
      status: QuerySessionStatus,
      createdAtMillis: Long,
      bundleHash: Option[String],
      answerUri: Option[String],
  ): QuerySessionRecord =
    val created = Instant.ofEpochMilli(createdAtMillis)
    QuerySessionRecord(
      SchemaVersion.Current,
      sessionId,
      queryId,
      questionHash,
      status,
      created.toString,
      created.getEpochSecond + RetentionSeconds,
      bundleHash,
      answerUri,
      provenance(questionHash),
    )

  private def sessionItem(rec: QuerySessionRecord): Either[String, KvItem] =
    Wire.write(rec).left.map(e => s"session_invalid_${e.code}").map { json =>
      KvItem(
        KvKey(rec.sessionId),
        Map(
          "payload"     -> json,
          "payloadHash" -> Hashing.textHash(json),
          "status"      -> rec.status.wire,
          "expiry"      -> rec.expiresAtEpochSeconds.toString,
        ),
        Some(rec.expiresAtEpochSeconds),
      )
    }

  /** Writes the `received` session. A session that already exists is left as it is. */
  def begin(
      queryId: String,
      sessionId: String,
      questionHash: String,
      createdAtMillis: Long,
  ): String =
    sessionItem(
      record(
        queryId,
        sessionId,
        questionHash,
        QuerySessionStatus.Received,
        createdAtMillis,
        None,
        None,
      )
    ) match
      case Left(e)     => "failed"
      case Right(item) =>
        kv.put(tables.querySession, item, PutCondition.NotExists) match
          case Right(PutOutcome.Written)            => "written"
          case Right(PutOutcome.ConditionFailed(_)) => "unchanged"
          case Left(_)                              => "failed"

  /** Persists the bundle, the audit object and the final session. */
  def finish(
      status: QuerySessionStatus,
      createdAtMillis: Long,
      bundle: Option[EvidenceBundle],
      audit: QueryAudit,
  ): (AuditResult, QueryAudit) =
    val errors                    = scala.collection.mutable.ListBuffer.empty[String]
    val (bundleState, bundleHash) = bundle match
      case None    => ("none", None)
      case Some(b) =>
        Wire.write(b) match
          case Left(e) =>
            errors += s"bundle_invalid_${e.code}"
            ("failed", None)
          case Right(json) =>
            val hash = Hashing.textHash(json)
            putBundle(b.queryId, json, hash) match
              case Right(s) => (s, Some(hash))
              case Left(e)  =>
                errors += e
                ("failed", Some(hash))
    val withHash  = audit.copy(evidenceBundleHash = bundleHash)
    val auditJson = WirePickle.write(withHash)
    val auditHash = Hashing.textHash(auditJson)
    val loc       = ObjectLocation(
      tables.auditBucket,
      s"${tables.auditPrefix}/${Hashing.textHash(audit.queryId)}/$auditHash.json",
    )
    val (objState, uri) =
      objects.putIfAbsent(
        loc,
        auditJson.getBytes(StandardCharsets.UTF_8),
        Some("application/json"),
        Some(auditHash),
      ) match
        case Right(_)                            => ("written", Some(loc.uri))
        case Left(StorageError.AlreadyExists(_)) => ("unchanged", Some(loc.uri))
        case Left(e)                             =>
          errors += s"audit_object_${e.code.replace('-', '_')}"
          ("failed", None)
    val rec = record(
      audit.queryId,
      audit.sessionId,
      audit.questionHash,
      status,
      createdAtMillis,
      bundleHash,
      uri,
    )
    val sessionState = sessionItem(rec) match
      case Left(e) =>
        errors += e
        "failed"
      case Right(item) => transition(item)
    if sessionState == "conflict" then errors += "session_conflict"
    if sessionState == "failed" then errors += "session_write_failed"
    (
      AuditResult(sessionState, bundleState, objState, uri, bundleHash, errors.toList.distinct),
      withHash,
    )

  private def transition(item: KvItem): String =
    kv.put(
      tables.querySession,
      item,
      PutCondition.AttributeEquals("status", QuerySessionStatus.Received.wire),
    ) match
      case Right(PutOutcome.Written)            => "written"
      case Right(PutOutcome.ConditionFailed(_)) =>
        kv.get(tables.querySession, item.key) match
          case Right(Some(existing))
              if existing.attributes.get("payloadHash") == item.attributes.get("payloadHash") =>
            "unchanged"
          case Right(_) => "conflict"
          case Left(_)  => "failed"
      case Left(_) => "failed"

  private def putBundle(queryId: String, json: String, hash: String): Either[String, String] =
    val bytes                             = json.getBytes(StandardCharsets.UTF_8)
    val key                               = KvKey(queryId, Some(hash))
    val itemOrErr: Either[String, KvItem] =
      if bytes.length <= MaxInlineBytes then
        Right(KvItem(key, Map("payload" -> json, "payloadHash" -> hash)))
      else
        val loc = ObjectLocation(tables.auditBucket, s"${tables.auditPrefix}/bundles/$hash.json")
        objects.putIfAbsent(loc, bytes, Some("application/json"), Some(hash)) match
          case Right(_) | Left(StorageError.AlreadyExists(_)) =>
            Right(KvItem(key, Map("payloadHash" -> hash, "payloadUri" -> loc.uri)))
          case Left(e) => Left(s"bundle_spill_${e.code.replace('-', '_')}")
    itemOrErr.flatMap { item =>
      kv.put(tables.evidenceBundle, item, PutCondition.NotExists) match
        case Right(PutOutcome.Written)                   => Right("written")
        case Right(PutOutcome.ConditionFailed(existing)) =>
          val seen = existing.orElse(kv.get(tables.evidenceBundle, key).toOption.flatten)
          if seen.exists(_.attributes.get("payloadHash").contains(hash)) then Right("unchanged")
          else Left("bundle_conflict")
        case Left(e) => Left(s"bundle_write_${e.code.replace('-', '_')}")
    }

object AuditAgent:
  val RetentionSeconds: Long = 7L * 24 * 3600
  val MaxInlineBytes: Int    = 300000
