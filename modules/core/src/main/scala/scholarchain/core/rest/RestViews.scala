package scholarchain.core.rest

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}
import scholarchain.core.ValidationError.*

/** Phase 10 REST response models (additive). Field names follow the spec's examples. These bodies
  * carry no `schemaVersion` (see `REST-CONTRACTS.md`). Every record has a `Contract` that checks
  * its bounds; `RestJson.write` validates before serializing.
  *
  * Wire rule: an absent optional value is omitted, except the two fields of `JobStatus` that the
  * spec shows as explicit `null`. No case class here has a default value, because uPickle omits
  * fields equal to their default.
  */

/** Typed error body. `error` is the code, `message` a short fixed text that never contains request
  * values, secrets or stack traces, `traceId` the request's trace id, `retryable` whether the same
  * request may be retried unchanged.
  */
final case class ApiError(
    error: ApiErrorCode,
    message: String,
    traceId: String,
    retryable: Boolean,
) derives ReadWriter

object ApiError:
  /** An error with the code's default `retryable`. */
  def of(code: ApiErrorCode, message: String, traceId: String): ApiError =
    ApiError(code, message.take(RestLimits.MaxMessageLength), traceId, code.retryable)

  given Contract[ApiError] = V.contract(e =>
    V.all(
      RuntimeChecks.text("message", e.message, RestLimits.MaxMessageLength),
      RestChecks.token("traceId", e.traceId),
    )
  )

/** One researcher in an answer. Only `researcherId` is required: a value the pipeline did not
  * produce is absent, never invented. `maximumTeamWorkIds` at most 20 work ids, `claimIds` at most
  * 100.
  */
final case class ResearcherView(
    researcherId: String,
    displayName: Option[String] = None,
    publicationCount: Option[Long] = None,
    distinctCoauthorCount: Option[Long] = None,
    weightedDegree: Option[Double] = None,
    averageCoauthorsPerPaper: Option[Double] = None,
    maximumCoauthorsOnOnePaper: Option[Long] = None,
    maximumTeamWorkIds: Option[List[String]] = None,
    componentId: Option[String] = None,
    communityId: Option[String] = None,
    bridgeMetric: Option[BridgeMetricView] = None,
    claimIds: List[String],
) derives ReadWriter

final case class BridgeMetricView(name: String, value: Double) derives ReadWriter

object ResearcherView:
  def validate(r: ResearcherView): V.R =
    V.all(
      V.unit(Ids.checkResearcherId(r.researcherId)),
      RestChecks.optText("displayName", r.displayName, QueryPlanV2.MaxNameLength),
      r.publicationCount.fold(V.ok)(RestChecks.nonNegative("publicationCount", _)),
      r.distinctCoauthorCount.fold(V.ok)(RestChecks.nonNegative("distinctCoauthorCount", _)),
      r.weightedDegree.fold(V.ok)(d =>
        V.all(
          V.finite("weightedDegree", d),
          if d >= 0.0 then V.ok else Left(InvalidField("weightedDegree", "must be >= 0")),
        )
      ),
      r.averageCoauthorsPerPaper.fold(V.ok)(d => V.finite("averageCoauthorsPerPaper", d)),
      r.maximumCoauthorsOnOnePaper.fold(V.ok)(
        RestChecks.nonNegative("maximumCoauthorsOnOnePaper", _)
      ),
      r.maximumTeamWorkIds.fold(V.ok)(ids =>
        V.all(
          RuntimeChecks.listMax("maximumTeamWorkIds", ids, RestLimits.MaxTeamWorkIds),
          V.each(ids)(i => V.unit(Ids.checkWorkId(i))),
        )
      ),
      r.componentId.fold(V.ok)(c => V.unit(Ids.checkEntityRef("component", c))),
      r.communityId.fold(V.ok)(c => V.unit(Ids.checkEntityRef("community", c))),
      r.bridgeMetric.fold(V.ok)(b =>
        V.all(
          RestChecks.token("bridgeMetric.name", b.name, 64),
          V.finite("bridgeMetric.value", b.value),
        )
      ),
      RuntimeChecks.listMax("claimIds", r.claimIds, RestLimits.MaxClaims),
      V.each(r.claimIds)(c => V.unit(Ids.checkEntityRef("claim", c))),
    )

/** The answer: a bounded summary (at most 4000 characters) and at most 100 researchers. */
final case class AnswerView(summary: String, researchers: List[ResearcherView]) derives ReadWriter

object AnswerView:
  def validate(a: AnswerView): V.R =
    V.all(
      RuntimeChecks.text("answer.summary", a.summary, RestLimits.MaxSummaryLength),
      RuntimeChecks.listMax("answer.researchers", a.researchers, RestLimits.MaxResearchers),
      V.each(a.researchers)(ResearcherView.validate),
      V.distinct("answer.researchers", a.researchers.map(_.researcherId)),
    )

/** One verified-or-not claim. `evidenceIds` at most 100 valid evidence ids; `uncertainty` an
  * optional caveat (at most 1000 characters). A `VERIFIED` or `PARTIALLY_SUPPORTED` claim needs at
  * least one evidence id.
  */
final case class ClaimView(
    claimId: String,
    text: String,
    evidenceIds: List[String],
    verificationStatus: ClaimStatus,
    uncertainty: Option[String] = None,
) derives ReadWriter

object ClaimView:
  def validate(c: ClaimView): V.R =
    V.all(
      V.unit(Ids.checkEntityRef("claim", c.claimId)),
      RuntimeChecks.text("claims.text", c.text, RestLimits.MaxClaimTextLength),
      RuntimeChecks.listMax("claims.evidenceIds", c.evidenceIds, RestLimits.MaxEvidenceIdsPerClaim),
      V.each(c.evidenceIds)(e => V.unit(Ids.checkEvidenceId(e))),
      V.distinct("claims.evidenceIds", c.evidenceIds),
      RestChecks.optText("claims.uncertainty", c.uncertainty, RestLimits.MaxClaimTextLength),
      c.verificationStatus match
        case ClaimStatus.Verified | ClaimStatus.PartiallySupported =>
          V.nonEmpty("claims.evidenceIds", c.evidenceIds)
        case _ => V.ok,
    )

/** Relative links. Both are `/v1/...` paths built by `RestPaths`. */
final case class QueryLinks(evidence: String, explain: String) derives ReadWriter

object QueryLinks:
  def forQuery(queryId: String): QueryLinks =
    QueryLinks(RestPaths.queryEvidence(queryId), RestPaths.explain(queryId))

/** `POST /v1/query` result, discriminated by `mode`. */
sealed trait QueryResponse:
  def queryId: String
  def mode: QueryMode

/** Synchronous result: `mode` must be `synchronous`. At most 100 claims. `abstained` is always
  * written (no class default, so `false` is not dropped). `abstainReason` is a token of at most 64
  * characters: required when `abstained`, absent otherwise, omitted from JSON when `None`.
  */
final case class SyncQueryResponse(
    queryId: String,
    mode: QueryMode,
    answer: AnswerView,
    claims: List[ClaimView],
    abstained: Boolean,
    abstainReason: Option[String] = None,
    links: QueryLinks,
) extends QueryResponse

/** Asynchronous acceptance (HTTP 202): `mode` must be `asynchronous`, `status` `QUEUED` or
  * `RUNNING`, `statusUrl` the job path.
  */
final case class AsyncQueryResponse(
    queryId: String,
    mode: QueryMode,
    jobId: String,
    status: JobState,
    statusUrl: String,
) extends QueryResponse

object QueryResponse:
  def sync(
      queryId: String,
      answer: AnswerView,
      claims: List[ClaimView],
      abstained: Boolean = false,
      abstainReason: Option[String] = None,
  ): SyncQueryResponse =
    SyncQueryResponse(
      queryId,
      QueryMode.Synchronous,
      answer,
      claims,
      abstained,
      abstainReason,
      QueryLinks.forQuery(queryId),
    )

  def accepted(queryId: String, jobId: String): AsyncQueryResponse =
    AsyncQueryResponse(
      queryId,
      QueryMode.Asynchronous,
      jobId,
      JobState.Queued,
      RestPaths.job(jobId),
    )

  given Contract[SyncQueryResponse] = V.contract(r =>
    V.all(
      V.unit(RestIds.checkQueryId(r.queryId).left.map(e => InvalidField("queryId", e.reason))),
      if r.mode == QueryMode.Synchronous then V.ok
      else Left(InvalidField("mode", "must be synchronous")),
      AnswerView.validate(r.answer),
      RuntimeChecks.listMax("claims", r.claims, RestLimits.MaxClaims),
      V.each(r.claims)(ClaimView.validate),
      V.distinct("claims.claimId", r.claims.map(_.claimId)),
      (r.abstained, r.abstainReason) match
        case (true, None)     => Left(InvalidField("abstainReason", "required when abstained"))
        case (false, Some(_)) =>
          Left(InvalidField("abstainReason", "must be absent when not abstained"))
        case (_, Some(reason)) => RestChecks.token("abstainReason", reason, 64)
        case _                 => V.ok,
      RestChecks.relativePath("links.evidence", r.links.evidence),
      RestChecks.relativePath("links.explain", r.links.explain),
    )
  )

  given Contract[AsyncQueryResponse] = V.contract(r =>
    V.all(
      V.unit(RestIds.checkQueryId(r.queryId).left.map(e => InvalidField("queryId", e.reason))),
      if r.mode == QueryMode.Asynchronous then V.ok
      else Left(InvalidField("mode", "must be asynchronous")),
      V.unit(RestIds.checkJobId(r.jobId).left.map(e => InvalidField("jobId", e.reason))),
      if r.status == JobState.Queued || r.status == JobState.Running then V.ok
      else Left(InvalidField("status", "must be QUEUED or RUNNING")),
      RestChecks.relativePath("statusUrl", r.statusUrl),
    )
  )

  given Contract[QueryResponse] = V.contract {
    case s: SyncQueryResponse  => summon[Contract[SyncQueryResponse]].validate(s).map(_ => ())
    case a: AsyncQueryResponse => summon[Contract[AsyncQueryResponse]].validate(a).map(_ => ())
  }

  /** Raw uPickle instances. They tag the value with a `$type` key because the classes extend a
    * sealed trait; `untagged` removes the tag on write and supplies it on read, so no `$type` ever
    * appears on the wire.
    */
  private object Raw:
    val sync: ReadWriter[SyncQueryResponse]   = macroRW
    val async: ReadWriter[AsyncQueryResponse] = macroRW

  private def untagged[A](raw: ReadWriter[A], tag: String): ReadWriter[A] =
    readwriter[ujson.Value].bimap[A](
      a =>
        val v = writeJs(a)(using raw)
        v.objOpt.foreach(_.remove("$type"))
        v
      ,
      js =>
        val copy = ujson.read(ujson.write(js))
        copy.obj("$type") = tag
        read[A](copy)(using raw),
    )

  /** Reads and writes the response, choosing the form by `mode`. A missing or unknown `mode` is
    * rejected.
    */
  object Forms:
    val sync: ReadWriter[SyncQueryResponse]   = untagged(Raw.sync, "SyncQueryResponse")
    val async: ReadWriter[AsyncQueryResponse] = untagged(Raw.async, "AsyncQueryResponse")

  given ReadWriter[SyncQueryResponse]  = Forms.sync
  given ReadWriter[AsyncQueryResponse] = Forms.async

  given ReadWriter[QueryResponse] = readwriter[ujson.Value].bimap[QueryResponse](
    {
      case s: SyncQueryResponse  => writeJs(s)(using Forms.sync)
      case a: AsyncQueryResponse => writeJs(a)(using Forms.async)
    },
    js =>
      js.objOpt.flatMap(_.get("mode")).flatMap(_.strOpt) match
        case Some("synchronous")  => read[SyncQueryResponse](js)(using Forms.sync)
        case Some("asynchronous") => read[AsyncQueryResponse](js)(using Forms.async)
        case _ => throw upickle.core.Abort("mode must be synchronous or asynchronous"),
  )

/** `GET /v1/jobs/{jobId}`. `resultUrl` and `error` are always written (`null` when absent, as in
  * the spec's example). `queued` has no stage. `SUCCEEDED` needs `resultUrl` and no `error`;
  * `FAILED` needs `error` and no `resultUrl`; `QUEUED` and `RUNNING` have neither. `updatedAt` is
  * not before `createdAt`.
  */
final case class JobStatus(
    jobId: String,
    queryId: String,
    state: JobState,
    stage: Option[JobStage] = None,
    createdAt: String,
    updatedAt: String,
    resultUrl: Option[String] = None,
    error: Option[ApiError] = None,
)

object JobStatus:
  private object Plain:
    given ReadWriter[JobStatus] = macroRW

  given ReadWriter[JobStatus] = readwriter[ujson.Value].bimap[JobStatus](
    j =>
      val v = writeJs(j)(using Plain.given_ReadWriter_JobStatus)
      val o = v.obj
      if !o.contains("resultUrl") then o("resultUrl") = ujson.Null
      if !o.contains("error") then o("error") = ujson.Null
      v
    ,
    js => read[JobStatus](js)(using Plain.given_ReadWriter_JobStatus),
  )

  given Contract[JobStatus] = V.contract(j =>
    V.all(
      V.unit(RestIds.checkJobId(j.jobId).left.map(e => InvalidField("jobId", e.reason))),
      V.unit(RestIds.checkQueryId(j.queryId).left.map(e => InvalidField("queryId", e.reason))),
      StorageChecks.instant("createdAt", j.createdAt),
      StorageChecks.instant("updatedAt", j.updatedAt),
      (StorageChecks.epochOf(j.createdAt), StorageChecks.epochOf(j.updatedAt)) match
        case (Some(c), Some(u)) if u >= c => V.ok
        case _ => Left(InvalidField("updatedAt", "must not be before createdAt")),
      j.resultUrl.fold(V.ok)(RestChecks.relativePath("resultUrl", _)),
      j.error.fold(V.ok)(e => V.unit(summon[Contract[ApiError]].validate(e))),
      if j.state == JobState.Queued && j.stage.isDefined then
        Left(InvalidField("stage", "a queued job has no stage"))
      else V.ok,
      j.state match
        case JobState.Succeeded =>
          if j.resultUrl.isDefined && j.error.isEmpty then V.ok
          else Left(InvalidField("state", "SUCCEEDED needs resultUrl and no error"))
        case JobState.Failed =>
          if j.error.isDefined && j.resultUrl.isEmpty then V.ok
          else Left(InvalidField("state", "FAILED needs error and no resultUrl"))
        case _ =>
          if j.error.isEmpty && j.resultUrl.isEmpty then V.ok
          else Left(InvalidField("state", "a job in progress has no resultUrl or error")),
    )
  )

// ---- evidence ----------------------------------------------------------------------------

/** Provenance of an evidence item. `textHash` is `sha256:` followed by 64 lowercase hex characters
  * (the spec's form; core stores the bare hex). `schemaVersion` is the evidence view's own contract
  * label (`evidence-v1`). `inputs` at most 20 `InputRef`s. `runId` and `retrievedAt` are absent
  * when unknown.
  */
final case class EvidenceProvenanceView(
    runId: Option[String] = None,
    textHash: String,
    schemaVersion: String,
    codeVersion: Option[String] = None,
    inputs: List[InputRef],
    retrievedAt: Option[String] = None,
) derives ReadWriter

/** An ordered graph path: `nodeIds` (2 to 7 core references) joined by `edgeIds` (`nodeIds.size -
  * 1` graph edge ids).
  */
final case class GraphPathView(nodeIds: List[String], edgeIds: List[String]) derives ReadWriter

/** `GET /v1/evidence/{evidenceId}`. `sourceRecordId` is the core `sourceEntityId`, `snapshot` the
  * source version, `workId` is set when the source entity is a work, `sourceDataset` is the
  * provenance source type. `text` is compact (at most 1500 characters); `textTruncated` is true
  * when the stored text was longer. `textS3Uri` is the pointer to the full text, `graphPath` the
  * path for graph-path evidence. `score` is finite when present.
  */
final case class EvidenceView(
    evidenceId: String,
    evidenceType: EvidenceKind,
    workId: Option[String] = None,
    sourceDataset: Option[String] = None,
    sourceRecordId: String,
    snapshot: Option[String] = None,
    text: Option[String] = None,
    textTruncated: Boolean,
    textS3Uri: Option[String] = None,
    graphPath: Option[GraphPathView] = None,
    score: Option[Double] = None,
    provenance: EvidenceProvenanceView,
) derives ReadWriter

object EvidenceView:
  val SchemaLabel: String = "evidence-v1"
  val HashPrefix: String  = "sha256:"

  given Contract[EvidenceView] = V.contract(e =>
    V.all(
      V.unit(Ids.checkEvidenceId(e.evidenceId)),
      e.workId.fold(V.ok)(w => V.unit(Ids.checkWorkId(w))),
      RestChecks.optText("sourceDataset", e.sourceDataset, 64),
      V.unit(Ids.checkAnyRef(e.sourceRecordId)),
      if e.sourceRecordId.length <= RestLimits.MaxIdLength then V.ok
      else
        Left(
          InvalidField("sourceRecordId", s"must be at most ${RestLimits.MaxIdLength} characters")
        )
      ,
      RestChecks.optText("snapshot", e.snapshot, 128),
      e.text.fold(V.ok)(t =>
        V.all(
          if t.length <= RestLimits.MaxEvidenceTextChars && !RuntimeChecks.hasControl(
              t.filterNot(c => c == '\n' || c == '\t')
            )
          then V.ok
          else
            Left(
              InvalidField(
                "text",
                s"must be at most ${RestLimits.MaxEvidenceTextChars} characters without control characters",
              )
            )
        )
      ),
      if e.textTruncated && e.text.isEmpty then Left(InvalidField("textTruncated", "needs text"))
      else V.ok,
      e.textS3Uri.fold(V.ok)(StorageChecks.s3Uri("textS3Uri", _)),
      e.graphPath.fold(V.ok)(p =>
        V.all(
          if p.nodeIds.size >= 2 && p.nodeIds.size <= RestLimits.MaxPathNodes then V.ok
          else
            Left(
              InvalidField(
                "graphPath.nodeIds",
                s"must have 2 to ${RestLimits.MaxPathNodes} entries",
              )
            )
          ,
          if p.edgeIds.size == p.nodeIds.size - 1 then V.ok
          else Left(InvalidField("graphPath.edgeIds", "must have one entry fewer than nodeIds")),
          V.each(p.nodeIds)(n =>
            V.unit(
              RestIds
                .checkNodeId(n)
                .left
                .map(_ => InvalidField("graphPath.nodeIds", "invalid node id"))
            )
          ),
          V.each(p.edgeIds)(i => V.unit(Ids.checkGraphEdgeId(i))),
        )
      ),
      e.score.fold(V.ok)(V.finite("score", _)),
      e.provenance.runId.fold(V.ok)(r => V.unit(Ids.checkRunId(r))),
      if e.provenance.textHash.startsWith(HashPrefix) &&
        Hashing.isSha256Hex(e.provenance.textHash.stripPrefix(HashPrefix))
      then V.ok
      else Left(InvalidField("provenance.textHash", "must be sha256:<64 lowercase hex>")),
      if e.provenance.schemaVersion == SchemaLabel then V.ok
      else Left(InvalidField("provenance.schemaVersion", s"must be $SchemaLabel")),
      RestChecks.optText("provenance.codeVersion", e.provenance.codeVersion, 128),
      RuntimeChecks
        .listMax("provenance.inputs", e.provenance.inputs, RestLimits.MaxProvenanceInputs),
      V.each(e.provenance.inputs)(i =>
        V.all(
          V.unit(InputRef.validate(i)),
          RuntimeChecks.text("provenance.inputs.inputId", i.inputId, 200),
          RuntimeChecks.text("provenance.inputs.version", i.version, 200),
        )
      ),
      e.provenance.retrievedAt.fold(V.ok)(StorageChecks.instant("provenance.retrievedAt", _)),
    )
  )

  /** Cuts at most `max` characters without splitting a surrogate pair. */
  private def clip(s: String, max: Int): (String, Boolean) =
    if s.length <= max then (s, false)
    else
      val end = if Character.isHighSurrogate(s.charAt(max - 1)) then max - 1 else max
      (s.substring(0, end), true)

  /** The compact view of a stored evidence item. The text is clipped to the bound (and flagged);
    * control characters other than newline and tab are replaced by spaces. The result is validated:
    * it fails when, for example, the item carries more than 20 provenance inputs.
    */
  def from(
      item: EvidenceItem,
      textS3Uri: Option[String] = None,
      graphPath: Option[GraphPathView] = None,
  ): Either[ValidationError, EvidenceView] =
    val cleaned = item.text.map(
      _.map(c => if Character.isISOControl(c) && c != '\n' && c != '\t' then ' ' else c)
    )
    val clipped = cleaned.map(clip(_, RestLimits.MaxEvidenceTextChars))
    val view    = EvidenceView(
      evidenceId = item.evidenceId,
      evidenceType = EvidenceKind.from(item.evidenceType),
      workId = Some(item.sourceEntityId).filter(Ids.checkWorkId(_).isRight),
      sourceDataset = Some(item.provenance.sourceType.wire),
      sourceRecordId = item.sourceEntityId,
      snapshot = Some(item.sourceVersion),
      text = clipped.map(_._1),
      textTruncated = clipped.exists(_._2),
      textS3Uri = textS3Uri,
      graphPath = graphPath,
      score = item.score,
      provenance = EvidenceProvenanceView(
        runId = item.provenance.runId,
        textHash = HashPrefix + item.contentHash,
        schemaVersion = SchemaLabel,
        codeVersion = Some(item.provenance.codeVersion),
        inputs = item.provenance.inputs,
        retrievedAt = item.provenance.retrievedAt,
      ),
    )
    summon[Contract[EvidenceView]].validate(view)

// ---- graph -------------------------------------------------------------------------------

/** A graph node. `properties`: at most 16 entries, names 1..64 characters, values at most 256. */
final case class GraphNodeView(
    nodeId: String,
    nodeType: String,
    label: String,
    properties: Map[String, String],
) derives ReadWriter

object GraphNodeView:
  given Contract[GraphNodeView] = V.contract(n =>
    V.all(
      V.unit(
        RestIds.checkNodeId(n.nodeId).left.map(_ => InvalidField("nodeId", "invalid node id"))
      ),
      RestChecks.token("nodeType", n.nodeType, 64),
      RuntimeChecks.text("label", n.label, 256),
      if n.properties.size <= RestLimits.MaxNodeProperties then V.ok
      else
        Left(
          InvalidField("properties", s"must have at most ${RestLimits.MaxNodeProperties} entries")
        )
      ,
      V.each(n.properties.keys)(k => RuntimeChecks.text("properties.name", k, 64)),
      V.each(n.properties.values)(v =>
        if v.length <= RestLimits.MaxPropertyValueLength && !RuntimeChecks.hasControl(v) then V.ok
        else
          Left(
            InvalidField(
              "properties.value",
              s"must be at most ${RestLimits.MaxPropertyValueLength} characters without control characters",
            )
          )
      ),
    )
  )

  /** Compact view of a stored node: the first 16 property names in sorted order, values clipped to
    * 256 characters with control characters replaced by spaces. Provenance stays in the store.
    */
  def from(n: GraphNode): Either[ValidationError, GraphNodeView] =
    def clean(s: String) = s
      .map(c => if Character.isISOControl(c) then ' ' else c)
      .take(RestLimits.MaxPropertyValueLength)
    val props = n.properties.toList
      .sortBy(_._1)
      .take(RestLimits.MaxNodeProperties)
      .map((k, v) => k.take(64) -> clean(v))
      .toMap
    summon[Contract[GraphNodeView]].validate(
      GraphNodeView(n.nodeId, n.nodeType, clean(n.label).take(256), props)
    )

/** An edge as a neighbors page shows it. `evidenceIds` at most 20. */
final case class GraphEdgeView(
    edgeId: String,
    edgeType: EdgeType,
    sourceId: String,
    targetId: String,
    directed: Boolean,
    evidenceIds: List[String],
) derives ReadWriter

object GraphEdgeView:
  given Contract[GraphEdgeView] = V.contract(e =>
    V.all(
      V.unit(Ids.checkGraphEdgeId(e.edgeId)),
      V.unit(
        RestIds.checkNodeId(e.sourceId).left.map(_ => InvalidField("sourceId", "invalid node id"))
      ),
      V.unit(
        RestIds.checkNodeId(e.targetId).left.map(_ => InvalidField("targetId", "invalid node id"))
      ),
      RuntimeChecks.listMax("evidenceIds", e.evidenceIds, RestLimits.MaxEdgeEvidenceIds),
      V.each(e.evidenceIds)(i => V.unit(Ids.checkEvidenceId(i))),
    )
  )

  /** `None` when the stored edge type is not on the allowlist, or the edge has too many evidence
    * ids.
    */
  def from(e: GraphEdge): Option[GraphEdgeView] =
    EdgeType
      .fromGraphLabel(e.edgeType)
      .map(t =>
        GraphEdgeView(
          e.edgeId,
          t,
          e.sourceId,
          e.targetId,
          e.directed,
          e.evidenceIds.take(RestLimits.MaxEdgeEvidenceIds),
        )
      )

/** A neighbor: the node, the last edge on the way to it, and its distance (1 to 3). */
final case class NeighborView(node: GraphNodeView, viaEdge: GraphEdgeView, distance: Int)
    derives ReadWriter

/** One page of neighbors. `items` at most 200 and at most `limit`; `nextCursor` is opaque, present
  * only when more results exist, at most 512 characters.
  */
final case class NeighborsPage(
    nodeId: String,
    direction: NeighborDirection,
    depth: Int,
    limit: Int,
    items: List[NeighborView],
    nextCursor: Option[String] = None,
) derives ReadWriter

object NeighborsPage:
  given Contract[NeighborsPage] = V.contract(p =>
    V.all(
      V.unit(
        RestIds.checkNodeId(p.nodeId).left.map(_ => InvalidField("nodeId", "invalid node id"))
      ),
      if p.depth >= 1 && p.depth <= RestLimits.MaxNeighborDepth then V.ok
      else Left(InvalidField("depth", s"must be in 1..${RestLimits.MaxNeighborDepth}")),
      if p.limit >= 1 && p.limit <= RestLimits.MaxNeighborLimit then V.ok
      else Left(InvalidField("limit", s"must be in 1..${RestLimits.MaxNeighborLimit}")),
      if p.items.size <= p.limit then V.ok
      else Left(InvalidField("items", "must have at most limit entries")),
      V.each(p.items) { i =>
        V.all(
          V.unit(summon[Contract[GraphNodeView]].validate(i.node)),
          V.unit(summon[Contract[GraphEdgeView]].validate(i.viaEdge)),
          if i.distance >= 1 && i.distance <= p.depth then V.ok
          else Left(InvalidField("items.distance", "must be in 1..depth")),
        )
      },
      p.nextCursor.fold(V.ok)(c =>
        if NeighborCursor.isWellFormed(c) then V.ok
        else Left(InvalidField("nextCursor", "must be an opaque cursor of at most 512 characters"))
      ),
    )
  )

// ---- explain -----------------------------------------------------------------------------

final case class PlanWindowView(startYear: Int, endYear: Int) derives ReadWriter

/** The normalized plan as the explain view shows it (no provenance parameters). `question` is the
  * caller's own text (at most 2000 characters); the other fields are tokens, ids and enums.
  */
final case class PlanView(
    queryId: String,
    question: String,
    operations: List[String],
    researcherNames: List[String],
    subjectIds: List[String],
    window: Option[PlanWindowView] = None,
    comparisonWindow: Option[PlanWindowView] = None,
    filters: Map[String, String],
    metric: Option[String] = None,
    resultCount: Int,
    pathDepth: Int,
) derives ReadWriter

object PlanView:
  def from(p: QueryPlanV2): PlanView =
    val filters = List(
      "topic"       -> p.filters.topic,
      "venue"       -> p.filters.venue,
      "institution" -> p.filters.institution,
    ).collect { case (k, Some(v)) => k -> v }.toMap
    PlanView(
      p.queryId,
      p.question,
      p.operations.map(_.wire),
      p.researcherNames,
      p.subjectIds,
      p.window.map(w => PlanWindowView(w.startYear, w.endYear)),
      p.comparisonWindow.map(w => PlanWindowView(w.startYear, w.endYear)),
      filters,
      p.metric.map(_.wire),
      p.limits.resultCount,
      p.limits.pathDepth,
    )

/** A stage that ran, with its latency in milliseconds (>= 0). */
final case class StageRunView(stage: JobStage, latencyMillis: Long) derives ReadWriter

/** One score component and its weight (finite). */
final case class ScoreComponentView(name: String, weight: Double) derives ReadWriter

/** Prompt identity and version used by one model stage (ids only, never prompt text). */
final case class PromptVersionView(stage: String, promptId: String, promptVersion: String)
    derives ReadWriter

object PromptVersionView:
  def from(c: ModelCall): PromptVersionView =
    PromptVersionView(c.stage.wire, c.promptId, c.promptVersion)

/** Model identity used by one model stage. */
final case class ModelVersionView(stage: String, modelId: String) derives ReadWriter

object ModelVersionView:
  def from(c: ModelCall): ModelVersionView = ModelVersionView(c.stage.wire, c.modelId)

/** Verification outcome of one claim: status, reason codes and the evidence checked. */
final case class ClaimOutcomeView(
    claimId: String,
    status: ClaimStatus,
    reasonCodes: List[String],
    checkedEvidenceIds: List[String],
) derives ReadWriter

object ClaimOutcomeView:
  def from(c: ClaimVerification): ClaimOutcomeView =
    ClaimOutcomeView(c.claimId, ClaimStatus.from(c.status), c.reasonCodes, c.checkedEvidenceIds)

/** Hits and misses of one named cache. */
final case class CacheStatView(cache: String, hits: Long, misses: Long) derives ReadWriter

/** Latency and retry counters: total latency, model calls, model retries, downstream retries. */
final case class ExplainCounters(
    totalLatencyMillis: Long,
    modelCalls: Long,
    modelRetries: Long,
    downstreamRetries: Long,
) derives ReadWriter

/** `GET /v1/explain/{queryId}`: every bullet of the spec. No field can carry prompt text, a secret
  * or reasoning: all strings other than the plan's question are tokens (no whitespace) or reason
  * codes, and every list is bounded (at most 50 entries, 200 evidence ids).
  */
final case class ExplainView(
    queryId: String,
    queryPlan: PlanView,
    stagesExecuted: List[StageRunView],
    retrievalModes: List[RetrievalMode],
    queryTemplateIds: List[String],
    indexesQueried: List[String],
    metadataFilters: Map[String, String],
    scoreComponents: List[ScoreComponentView],
    promptVersions: List[PromptVersionView],
    modelVersions: List[ModelVersionView],
    evidenceIds: List[String],
    claimOutcomes: List[ClaimOutcomeView],
    counters: ExplainCounters,
    cacheHits: List[CacheStatView],
) derives ReadWriter

object ExplainView:
  import RestLimits.MaxExplainEntries as Max

  given Contract[ExplainView] = V.contract(x =>
    def bounded(field: String, xs: Seq[?], max: Int = Max) = RuntimeChecks.listMax(field, xs, max)
    def tok(field: String)(s: String)                      = RestChecks.token(field, s, 128)
    val p                                                  = x.queryPlan
    V.all(
      V.unit(RestIds.checkQueryId(x.queryId).left.map(e => InvalidField("queryId", e.reason))),
      if p.queryId == x.queryId then V.ok
      else Left(InvalidField("queryPlan.queryId", "must equal queryId")),
      RuntimeChecks.text("queryPlan.question", p.question, RestLimits.MaxQuestionLength),
      bounded("queryPlan.operations", p.operations),
      V.each(p.operations)(tok("queryPlan.operations")),
      bounded("queryPlan.researcherNames", p.researcherNames, QueryPlanV2.MaxResearchers),
      V.each(p.researcherNames)(n =>
        RuntimeChecks.text("queryPlan.researcherNames", n, QueryPlanV2.MaxNameLength)
      ),
      bounded("queryPlan.subjectIds", p.subjectIds, QueryPlanV2.MaxResearchers),
      V.each(p.subjectIds)(s => V.unit(Ids.checkResearcherId(s))),
      p.window.fold(V.ok)(w => V.unit(GraphWindow.validate(GraphWindow(w.startYear, w.endYear)))),
      p.comparisonWindow.fold(V.ok)(w =>
        V.unit(GraphWindow.validate(GraphWindow(w.startYear, w.endYear)))
      ),
      mapOk("queryPlan.filters", p.filters),
      p.metric.fold(V.ok)(tok("queryPlan.metric")),
      V.each(Seq(p.resultCount, p.pathDepth))(n =>
        RestChecks.nonNegative("queryPlan.limits", n.toLong)
      ),
      bounded("stagesExecuted", x.stagesExecuted),
      V.each(x.stagesExecuted)(s =>
        RestChecks.nonNegative("stagesExecuted.latencyMillis", s.latencyMillis)
      ),
      bounded("retrievalModes", x.retrievalModes),
      bounded("queryTemplateIds", x.queryTemplateIds),
      V.each(x.queryTemplateIds)(tok("queryTemplateIds")),
      bounded("indexesQueried", x.indexesQueried),
      V.each(x.indexesQueried)(tok("indexesQueried")),
      mapOk("metadataFilters", x.metadataFilters),
      bounded("scoreComponents", x.scoreComponents),
      V.each(x.scoreComponents)(c =>
        V.all(tok("scoreComponents.name")(c.name), V.finite("scoreComponents.weight", c.weight))
      ),
      bounded("promptVersions", x.promptVersions),
      V.each(x.promptVersions)(v =>
        V.all(
          tok("promptVersions.stage")(v.stage),
          tok("promptVersions.promptId")(v.promptId),
          tok("promptVersions.promptVersion")(v.promptVersion),
        )
      ),
      bounded("modelVersions", x.modelVersions),
      V.each(x.modelVersions)(v =>
        V.all(tok("modelVersions.stage")(v.stage), tok("modelVersions.modelId")(v.modelId))
      ),
      bounded("evidenceIds", x.evidenceIds, RestLimits.MaxExplainEvidenceIds),
      V.each(x.evidenceIds)(e => V.unit(Ids.checkEvidenceId(e))),
      bounded("claimOutcomes", x.claimOutcomes, RestLimits.MaxClaims),
      V.each(x.claimOutcomes) { c =>
        V.all(
          V.unit(Ids.checkEntityRef("claim", c.claimId)),
          bounded("claimOutcomes.reasonCodes", c.reasonCodes, ClaimVerification.MaxReasonCodes),
          V.each(c.reasonCodes)(r => RestChecks.reasonCode("claimOutcomes.reasonCodes", r)),
          bounded(
            "claimOutcomes.checkedEvidenceIds",
            c.checkedEvidenceIds,
            ClaimVerification.MaxCheckedEvidence,
          ),
          V.each(c.checkedEvidenceIds)(e => V.unit(Ids.checkEvidenceId(e))),
        )
      },
      V.each(
        Seq(
          x.counters.totalLatencyMillis,
          x.counters.modelCalls,
          x.counters.modelRetries,
          x.counters.downstreamRetries,
        )
      )(n => RestChecks.nonNegative("counters", n)),
      bounded("cacheHits", x.cacheHits),
      V.each(x.cacheHits)(c =>
        V.all(
          tok("cacheHits.cache")(c.cache),
          RestChecks.nonNegative("cacheHits.hits", c.hits),
          RestChecks.nonNegative("cacheHits.misses", c.misses),
        )
      ),
    )
  )

  private def mapOk(field: String, m: Map[String, String]): V.R =
    V.all(
      if m.size <= RestLimits.MaxNodeProperties then V.ok
      else Left(InvalidField(field, s"must have at most ${RestLimits.MaxNodeProperties} entries")),
      V.each(m.keys)(k => RestChecks.token(field, k, 64)),
      V.each(m.values)(v => RuntimeChecks.text(field, v, 128)),
    )

// ---- health ------------------------------------------------------------------------------

/** `GET /healthz`: the process is alive. `status` is always `ok`; no dependency state. */
final case class HealthView(status: String, service: String, version: Option[String] = None)
    derives ReadWriter

object HealthView:
  def alive(service: String, version: Option[String]): HealthView =
    HealthView("ok", service, version)

  given Contract[HealthView] = V.contract(h =>
    V.all(
      if h.status == "ok" then V.ok else Left(InvalidField("status", "must be ok")),
      RestChecks.token("service", h.service, 64),
      h.version.fold(V.ok)(RestChecks.token("version", _, 64)),
    )
  )

/** One dependency check: a name token, up or down, and for `down` an optional reason code
  * (`[a-z][a-z0-9_]{0,63}`; never an exception text or an endpoint).
  */
final case class DependencyCheck(
    name: String,
    state: DependencyState,
    reason: Option[String] = None,
) derives ReadWriter

/** `GET /readyz`: ready only when every check is up (at most 20 checks). */
final case class ReadinessView(
    status: ReadinessState,
    service: String,
    checks: List[DependencyCheck],
) derives ReadWriter

object ReadinessView:
  def of(service: String, checks: List[DependencyCheck]): ReadinessView =
    val ready = checks.forall(_.state == DependencyState.Up)
    ReadinessView(if ready then ReadinessState.Ready else ReadinessState.NotReady, service, checks)

  given Contract[ReadinessView] = V.contract(r =>
    V.all(
      RestChecks.token("service", r.service, 64),
      RuntimeChecks.listMax("checks", r.checks, RestLimits.MaxHealthChecks),
      V.each(r.checks)(c =>
        V.all(
          RestChecks.token("checks.name", c.name, 64),
          c.reason.fold(V.ok)(RestChecks.reasonCode("checks.reason", _)),
          if c.state == DependencyState.Up && c.reason.isDefined then
            Left(InvalidField("checks.reason", "only a down check has a reason"))
          else V.ok,
        )
      ),
      if (r.status == ReadinessState.Ready) == r.checks.forall(_.state == DependencyState.Up) then
        V.ok
      else Left(InvalidField("status", "must be ready exactly when every check is up")),
    )
  )
