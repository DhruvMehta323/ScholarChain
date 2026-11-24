package scholarchain.core.rest

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}
import scholarchain.core.ValidationError.*

/** Key layout of the internal REST payloads stored in the QuerySession table through
  * `KeyValueStore` (human decision 5). The frozen `QuerySessionRecord` is untouched: its partition
  * keys are `session-...`, these use disjoint prefixes, so the two kinds of item cannot collide.
  * Items are always disposable (TTL set).
  *
  *   - job item: `pk = job#<id>` where the job id is `job:<id>`; payload `JobRecord`.
  *   - idempotency item: `pk = idem#<sha256>`, the hash of caller scope and client key, so the raw
  *     key never appears in a key and two callers never share a record; payload
  *     `IdempotencyRecord`.
  */
object RestKeys:
  val JobPrefix: String         = "job#"
  val IdempotencyPrefix: String = "idem#"

  def jobPartition(jobId: String): String = JobPrefix + jobId.stripPrefix("job:")

  /** SHA-256 over (scope, key). `scope` identifies the caller (for example a hash of the API key
    * identity), never the secret itself.
    */
  def idempotencyKeyHash(scope: String, key: String): String =
    Hashing.digest("scholarchain.rest.idempotency.v1", List(scope, key))

  def idempotencyPartition(scope: String, key: String): String =
    IdempotencyPrefix + idempotencyKeyHash(scope, key)

  /** True for partition keys written by this contract. */
  def isRestPartition(pk: String): Boolean =
    pk.startsWith(JobPrefix) || pk.startsWith(IdempotencyPrefix)

private[core] object RestPersistedChecks:
  def epochWithin(field: String, createdAt: String, expires: Long, min: Long, max: Long): V.R =
    StorageChecks.epochOf(createdAt) match
      case Some(c) if expires >= c + min && expires <= c + max => V.ok
      case _ => Left(InvalidField(field, s"must be $min to $max seconds after createdAt"))

  def resultJson(field: String, json: String): V.R =
    if json
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        .length <= RestLimits.MaxResultJsonBytes
    then V.ok
    else Left(InvalidField(field, s"must be at most ${RestLimits.MaxResultJsonBytes} bytes"))

/** Persisted state of one asynchronous job (QuerySession table, `pk = job#<id>`).
  *
  *   - `requestHash`: `QueryRequest.requestHash` of the admitted request.
  *   - `expiresAtEpochSeconds`: 60 seconds to 7 days after `createdAt`.
  *   - `resultJson`: the JSON of the `SyncQueryResponse`, at most 300000 UTF-8 bytes; present
  *     exactly when `state` is `SUCCEEDED`, and it must read back as a valid response for
  *     `queryId`. `error` is present exactly when `state` is `FAILED`.
  *   - `queued` has no stage; `updatedAt` is not before `createdAt`.
  */
final case class JobRecord(
    schemaVersion: Int,
    jobId: String,
    queryId: String,
    requestHash: String,
    state: JobState,
    stage: Option[JobStage] = None,
    createdAt: String,
    updatedAt: String,
    expiresAtEpochSeconds: Long,
    resultJson: Option[String] = None,
    error: Option[ApiError] = None,
) derives ReadWriter:
  /** The public status body. `resultUrl` is the job-result path when the job succeeded. */
  def toStatus: JobStatus =
    JobStatus(
      jobId,
      queryId,
      state,
      stage,
      createdAt,
      updatedAt,
      Option.when(state == JobState.Succeeded)(RestPaths.jobResult(jobId)),
      error,
    )

object JobRecord:
  val MinRetentionSeconds: Long = 60L
  val MaxRetentionSeconds: Long = 7L * 24 * 3600

  given Contract[JobRecord] = V.contract(j =>
    V.all(
      V.schema(j.schemaVersion),
      V.unit(RestIds.checkJobId(j.jobId).left.map(e => InvalidField("jobId", e.reason))),
      V.unit(RestIds.checkQueryId(j.queryId).left.map(e => InvalidField("queryId", e.reason))),
      StorageChecks.sha256("requestHash", j.requestHash),
      StorageChecks.instant("createdAt", j.createdAt),
      StorageChecks.instant("updatedAt", j.updatedAt),
      (StorageChecks.epochOf(j.createdAt), StorageChecks.epochOf(j.updatedAt)) match
        case (Some(c), Some(u)) if u >= c => V.ok
        case _ => Left(InvalidField("updatedAt", "must not be before createdAt")),
      RestPersistedChecks.epochWithin(
        "expiresAtEpochSeconds",
        j.createdAt,
        j.expiresAtEpochSeconds,
        MinRetentionSeconds,
        MaxRetentionSeconds,
      ),
      if j.state == JobState.Queued && j.stage.isDefined then
        Left(InvalidField("stage", "a queued job has no stage"))
      else V.ok,
      j.error.fold(V.ok)(e => V.unit(summon[Contract[ApiError]].validate(e))),
      j.resultJson.fold(V.ok)(r =>
        V.all(
          RestPersistedChecks.resultJson("resultJson", r),
          RestJson.read[SyncQueryResponse](r) match
            case Right(resp) if resp.queryId == j.queryId => V.ok
            case _                                        =>
              Left(InvalidField("resultJson", "must be a valid synchronous response for queryId")),
        )
      ),
      j.state match
        case JobState.Succeeded =>
          if j.resultJson.isDefined && j.error.isEmpty then V.ok
          else Left(InvalidField("state", "SUCCEEDED needs resultJson and no error"))
        case JobState.Failed =>
          if j.error.isDefined && j.resultJson.isEmpty then V.ok
          else Left(InvalidField("state", "FAILED needs error and no resultJson"))
        case _ =>
          if j.error.isEmpty && j.resultJson.isEmpty then V.ok
          else Left(InvalidField("state", "a job in progress has no resultJson or error")),
    )
  )

/** Persisted idempotency record (QuerySession table, `pk = idem#<keyHash>`).
  *
  *   - `keyHash`: `RestKeys.idempotencyKeyHash`. `requestHash`: `QueryRequest.requestHash`. A
  *     repeat with the same key and the same `requestHash` replays the outcome; the same key with a
  *     different `requestHash` is 409 `IDEMPOTENCY_CONFLICT`.
  *   - `in_progress`: no outcome yet (claim taken before the run). `completed`: exactly one of
  *     `responseJson` (the synchronous response, at most 300000 bytes, must read back as a valid
  *     response for `queryId`) or `jobId` (the run went asynchronous). `failed`: `error` present.
  *   - `expiresAtEpochSeconds`: 60 seconds to 24 hours after `createdAt`.
  */
final case class IdempotencyRecord(
    schemaVersion: Int,
    keyHash: String,
    requestHash: String,
    queryId: String,
    state: IdempotencyState,
    jobId: Option[String] = None,
    responseJson: Option[String] = None,
    error: Option[ApiError] = None,
    createdAt: String,
    expiresAtEpochSeconds: Long,
) derives ReadWriter

object IdempotencyRecord:
  val MinRetentionSeconds: Long = 60L
  val MaxRetentionSeconds: Long = 24L * 3600

  given Contract[IdempotencyRecord] = V.contract(r =>
    V.all(
      V.schema(r.schemaVersion),
      StorageChecks.sha256("keyHash", r.keyHash),
      StorageChecks.sha256("requestHash", r.requestHash),
      V.unit(RestIds.checkQueryId(r.queryId).left.map(e => InvalidField("queryId", e.reason))),
      StorageChecks.instant("createdAt", r.createdAt),
      RestPersistedChecks.epochWithin(
        "expiresAtEpochSeconds",
        r.createdAt,
        r.expiresAtEpochSeconds,
        MinRetentionSeconds,
        MaxRetentionSeconds,
      ),
      r.jobId.fold(V.ok)(j =>
        V.unit(RestIds.checkJobId(j).left.map(e => InvalidField("jobId", e.reason)))
      ),
      r.error.fold(V.ok)(e => V.unit(summon[Contract[ApiError]].validate(e))),
      r.responseJson.fold(V.ok)(j =>
        V.all(
          RestPersistedChecks.resultJson("responseJson", j),
          RestJson.read[SyncQueryResponse](j) match
            case Right(resp) if resp.queryId == r.queryId => V.ok
            case _                                        =>
              Left(InvalidField("responseJson", "must be a valid synchronous response for queryId")),
        )
      ),
      r.state match
        case IdempotencyState.InProgress =>
          if r.jobId.isEmpty && r.responseJson.isEmpty && r.error.isEmpty then V.ok
          else Left(InvalidField("state", "an in_progress record has no outcome"))
        case IdempotencyState.Completed =>
          if r.error.isEmpty && (r.jobId.isDefined != r.responseJson.isDefined) then V.ok
          else
            Left(
              InvalidField(
                "state",
                "a completed record has exactly one of jobId and responseJson and no error",
              )
            )
        case IdempotencyState.Failed =>
          if r.error.isDefined && r.jobId.isEmpty && r.responseJson.isEmpty then V.ok
          else Left(InvalidField("state", "a failed record has error only")),
    )
  )
