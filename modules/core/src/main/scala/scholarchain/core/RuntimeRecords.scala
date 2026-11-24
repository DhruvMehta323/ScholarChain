package scholarchain.core

import WirePickle.{*, given}
import ValidationError.*

/** Phase 09 runtime contracts (additive; no existing record, enum or wire form changes).
  *
  * Naming follows `AnalyticsRecordsV2`: the Scala type carries `V2` for a new record family and the
  * wire `schemaVersion` is still `1`.
  *
  * Frozen JSON examples: `contracts/query-plan-v2.example.json`,
  * `contracts/resolved-researcher-candidate.example.json`,
  * `contracts/claim-verification.example.json`, `contracts/model-call.example.json`.
  */
private[core] object RuntimeChecks:
  import V.*

  /** Bounded token: no whitespace, so never free query text. */
  val FilterToken = """^[A-Za-z0-9][A-Za-z0-9._:\-]{0,63}$""".r
  val ReasonCode  = """^[a-z][a-z0-9_]{0,63}$""".r

  def hasControl(s: String): Boolean = s.exists(c => Character.isISOControl(c))

  def text(field: String, s: String, max: Int): R =
    all(
      nonBlank(field, s),
      if s.length <= max then ok else Left(InvalidField(field, s"must be at most $max characters")),
      if hasControl(s) then Left(InvalidField(field, "must not contain control characters"))
      else ok,
    )

  def listMax(field: String, xs: Seq[?], max: Int): R =
    if xs.size <= max then ok else Left(InvalidField(field, s"must have at most $max entries"))

  def token(field: String, s: String, max: Int): R = StorageChecks.boundedToken(field, s, max)

/** Typed, bounded entity filters. Each is a single token (no whitespace), at most 64 characters: a
  * filter selects by value, it is never query text.
  */
final case class QueryFilters(
    topic: Option[String] = None,
    venue: Option[String] = None,
    institution: Option[String] = None,
) derives ReadWriter:
  def isEmpty: Boolean = topic.isEmpty && venue.isEmpty && institution.isEmpty

object QueryFilters:
  val none: QueryFilters = QueryFilters(None, None, None)

  def validate(f: QueryFilters): Either[ValidationError, QueryFilters] =
    def one(field: String, v: Option[String]): V.R = v match
      case None    => V.ok
      case Some(s) =>
        if RuntimeChecks.FilterToken.matches(s) then V.ok
        else
          Left(
            InvalidField(
              field,
              "must be a token of 1 to 64 characters: letters, digits, '.', '_', ':' or '-'",
            )
          )
    V.all(
      one("filters.topic", f.topic),
      one("filters.venue", f.venue),
      one("filters.institution", f.institution),
    ).map(_ => f)

/** Result-count and path-depth limits with hard caps. */
final case class QueryLimits(resultCount: Int, pathDepth: Int) derives ReadWriter

object QueryLimits:
  val MaxResultCount: Int = 100
  val MaxPathDepth: Int   = 6

  def validate(l: QueryLimits): Either[ValidationError, QueryLimits] =
    def within(field: String, v: Int, max: Int): V.R =
      if v >= 1 && v <= max then V.ok else Left(InvalidField(field, s"must be in 1..$max"))
    V.all(
      within("limits.resultCount", l.resultCount, MaxResultCount),
      within("limits.pathDepth", l.pathDepth, MaxPathDepth),
    ).map(_ => l)

/** QueryPlan v2: a typed plan over the `QueryOperationV2` allowlist.
  *
  *   - `queryId`: `query:<non-whitespace>`. `question`: non-blank, at most `MaxQuestionLength`
  *     (2000), no control characters. It is kept as data and is never interpolated into a query.
  *   - `operations`: 1 to `MaxOperations` (6) distinct allowlisted operations.
  *   - `researcherNames`: names exactly as the user gave them, at most `MaxResearchers` (10),
  *     distinct, each non-blank, at most `MaxNameLength` (200), no control characters. Data only.
  *   - `subjectIds`: resolved researcher IDs (`Ids.checkResearcherId`), distinct, at most 10. When
  *     both lists are non-empty they must have the same size (index i resolves name i). The subject
  *     count used by the operation rules is `max(names, ids)`.
  *   - `window` (and, for temporal comparison, `comparisonWindow`): valid `GraphWindow`s.
  *   - `filters`: `QueryFilters`. `metric`: `CollaborationMetric`. `limits`: `QueryLimits`.
  *
  * Per-operation rules (every operation in the plan must be satisfied):
  *   - rank_researchers_by_metric: `metric` required and not `community_id`.
  *   - max_team_size: none.
  *   - direct_coauthors: exactly 1 subject.
  *   - shortest_path: exactly 2 subjects; `subjectIds`, when present, distinct.
  *   - component_lookup: exactly 1 subject.
  *   - community_comparison: at least 2 subjects.
  *   - bridge_ranking: none.
  *   - temporal_comparison: at least 1 subject, `window` and `comparisonWindow` both required and
  *     different.
  *   - entity_filter: at least one filter set.
  *   - semantic_explanation: none.
  *   - researcher_metrics_lookup: at least 1 subject.
  * `comparisonWindow` is only allowed with temporal_comparison.
  */
final case class QueryPlanV2(
    schemaVersion: Int,
    queryId: String,
    question: String,
    operations: List[QueryOperationV2],
    researcherNames: List[String],
    subjectIds: List[String],
    window: Option[GraphWindow] = None,
    comparisonWindow: Option[GraphWindow] = None,
    filters: QueryFilters,
    metric: Option[CollaborationMetric] = None,
    limits: QueryLimits,
    provenance: Provenance,
) derives ReadWriter:
  def subjectCount: Int = math.max(researcherNames.size, subjectIds.size)

object QueryPlanV2:
  val MaxQuestionLength: Int = 2000
  val MaxOperations: Int     = 6
  val MaxResearchers: Int    = 10
  val MaxNameLength: Int     = 200

  given Contract[QueryPlanV2] = V.contract(q =>
    V.all(
      V.schema(q.schemaVersion),
      V.unit(Ids.checkEntityRef("query", q.queryId)),
      RuntimeChecks.text("question", q.question, MaxQuestionLength),
      V.nonEmpty("operations", q.operations),
      RuntimeChecks.listMax("operations", q.operations, MaxOperations),
      V.distinct("operations", q.operations.map(_.wire)),
      RuntimeChecks.listMax("researcherNames", q.researcherNames, MaxResearchers),
      V.each(q.researcherNames)(n => RuntimeChecks.text("researcherNames", n, MaxNameLength)),
      V.distinct("researcherNames", q.researcherNames),
      RuntimeChecks.listMax("subjectIds", q.subjectIds, MaxResearchers),
      V.each(q.subjectIds)(s => V.unit(Ids.checkResearcherId(s))),
      V.distinct("subjectIds", q.subjectIds),
      if q.researcherNames.nonEmpty && q.subjectIds.nonEmpty &&
        q.researcherNames.size != q.subjectIds.size
      then Left(InvalidField("subjectIds", "must have the same size as researcherNames"))
      else V.ok,
      q.window.fold(V.ok)(w => V.unit(GraphWindow.validate(w))),
      q.comparisonWindow.fold(V.ok)(w => V.unit(GraphWindow.validate(w))),
      V.unit(QueryFilters.validate(q.filters)),
      V.unit(QueryLimits.validate(q.limits)),
      if q.comparisonWindow.isDefined && !q.operations.contains(QueryOperationV2.TemporalComparison)
      then Left(InvalidField("comparisonWindow", "is only allowed with temporal_comparison"))
      else V.ok,
      V.each(q.operations)(op => operationRules(q, op)),
      V.prov(q.provenance),
    )
  )

  private def operationRules(q: QueryPlanV2, op: QueryOperationV2): V.R =
    def exactly(n: Int): V.R =
      if q.subjectCount == n then V.ok
      else Left(InvalidField("subjects", s"${op.wire} needs exactly $n subject(s)"))
    def atLeast(n: Int): V.R =
      if q.subjectCount >= n then V.ok
      else Left(InvalidField("subjects", s"${op.wire} needs at least $n subject(s)"))
    op match
      case QueryOperationV2.RankByMetric =>
        q.metric match
          case None                                  => Left(MissingField("metric"))
          case Some(CollaborationMetric.CommunityId) =>
            Left(InvalidField("metric", "community_id cannot be ranked"))
          case Some(_) => V.ok
      case QueryOperationV2.DirectCoauthors         => exactly(1)
      case QueryOperationV2.ComponentLookup         => exactly(1)
      case QueryOperationV2.ShortestPath            => exactly(2)
      case QueryOperationV2.CommunityComparison     => atLeast(2)
      case QueryOperationV2.ResearcherMetricsLookup => atLeast(1)
      case QueryOperationV2.TemporalComparison      =>
        V.all(
          atLeast(1),
          if q.window.isDefined then V.ok else Left(MissingField("window")),
          if q.comparisonWindow.isDefined then V.ok else Left(MissingField("comparisonWindow")),
          if q.window.isDefined && q.window == q.comparisonWindow then
            Left(InvalidField("comparisonWindow", "must differ from window"))
          else V.ok,
        )
      case QueryOperationV2.EntityFilter =>
        if q.filters.isEmpty then Left(InvalidField("filters", "entity_filter needs a filter"))
        else V.ok
      case QueryOperationV2.MaxTeamSize | QueryOperationV2.BridgeRanking |
          QueryOperationV2.SemanticExplanation =>
        V.ok

/** One candidate identity for a researcher name, produced by the resolver.
  *
  *   - `researcherId`: valid researcher ID. `displayName`: non-blank, at most 200, no control
  *     characters. `confidence` in [0,1].
  *   - `evidenceUsed`: 1 to 20 distinct tokens (each at most 128 characters, no whitespace) naming
  *     the evidence the resolver relied on (for example `mention-table`, `identity-table`).
  *   - `sourceIds`: 0 to 20 distinct entity references (`Ids.checkAnyRef`, at most 200) the
  *     candidate came from (mention IDs, source record IDs).
  */
final case class ResolvedResearcherCandidate(
    schemaVersion: Int,
    researcherId: String,
    displayName: String,
    confidence: Double,
    evidenceUsed: List[String],
    sourceIds: List[String],
) derives ReadWriter

object ResolvedResearcherCandidate:
  val MaxEntries: Int = 20

  given Contract[ResolvedResearcherCandidate] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkResearcherId(c.researcherId)),
      RuntimeChecks.text("displayName", c.displayName, QueryPlanV2.MaxNameLength),
      V.confidence("confidence", c.confidence),
      V.nonEmpty("evidenceUsed", c.evidenceUsed),
      RuntimeChecks.listMax("evidenceUsed", c.evidenceUsed, MaxEntries),
      V.each(c.evidenceUsed)(e => RuntimeChecks.token("evidenceUsed", e, 128)),
      V.distinct("evidenceUsed", c.evidenceUsed),
      RuntimeChecks.listMax("sourceIds", c.sourceIds, MaxEntries),
      V.each(c.sourceIds) { s =>
        V.all(
          V.unit(Ids.checkAnyRef(s)),
          if s.length <= 200 then V.ok
          else Left(InvalidField("sourceIds", "must be at most 200 characters")),
        )
      },
      V.distinct("sourceIds", c.sourceIds),
    )
  )

/** Verification result for one answer claim.
  *
  *   - `claimId`: `claim:<non-whitespace>`. `checkedEvidenceIds`: distinct valid evidence IDs, at
  *     most 100. `reasonCodes`: distinct codes matching `[a-z][a-z0-9_]{0,63}`, at most 20.
  *   - `verified` needs at least one checked evidence ID. `partially_supported`, `unsupported` and
  *     `conflicting` need at least one reason code.
  */
final case class ClaimVerification(
    schemaVersion: Int,
    claimId: String,
    status: ClaimVerificationStatus,
    checkedEvidenceIds: List[String],
    reasonCodes: List[String],
    provenance: Provenance,
) derives ReadWriter

object ClaimVerification:
  val MaxCheckedEvidence: Int = 100
  val MaxReasonCodes: Int     = 20

  given Contract[ClaimVerification] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkEntityRef("claim", c.claimId)),
      RuntimeChecks.listMax("checkedEvidenceIds", c.checkedEvidenceIds, MaxCheckedEvidence),
      V.each(c.checkedEvidenceIds)(e => V.unit(Ids.checkEvidenceId(e))),
      V.distinct("checkedEvidenceIds", c.checkedEvidenceIds),
      RuntimeChecks.listMax("reasonCodes", c.reasonCodes, MaxReasonCodes),
      V.each(c.reasonCodes) { r =>
        if RuntimeChecks.ReasonCode.matches(r) then V.ok
        else Left(InvalidField("reasonCodes", "must match [a-z][a-z0-9_]{0,63}"))
      },
      V.distinct("reasonCodes", c.reasonCodes),
      c.status match
        case ClaimVerificationStatus.Verified =>
          V.nonEmpty("checkedEvidenceIds", c.checkedEvidenceIds)
        case _ => V.nonEmpty("reasonCodes", c.reasonCodes),
      V.prov(c.provenance),
    )
  )

/** Audit record of one model call. Carries hashes and counts only: never prompt text, output text,
  * a key or a configuration value.
  *
  *   - `queryId`: `query:<..>`. `stage`: `ModelStage`.
  *   - `promptId`, `promptVersion`, `modelId`, `outputSchemaId`, `outputSchemaVersion` (of the
  *     output JSON schema): tokens, at most 128 characters.
  *   - `inputHash`: SHA-256 hex of the request. `outputHash`: SHA-256 hex of the raw output, absent
  *     when there was no output; required when `outcome` is `succeeded`.
  *   - `inputTokens`, `outputTokens`: absent when unknown, otherwise >= 0.
  *   - `latencyMillis` >= 0. `retries` in 0..`MaxRetries` (2).
  */
final case class ModelCall(
    schemaVersion: Int,
    queryId: String,
    stage: ModelStage,
    promptId: String,
    promptVersion: String,
    modelId: String,
    outputSchemaId: String,
    outputSchemaVersion: String,
    inputHash: String,
    outputHash: Option[String] = None,
    inputTokens: Option[Int] = None,
    outputTokens: Option[Int] = None,
    latencyMillis: Long,
    retries: Int,
    outcome: ModelCallOutcome,
) derives ReadWriter

object ModelCall:
  val MaxRetries: Int = 2

  given Contract[ModelCall] = V.contract(m =>
    V.all(
      V.schema(m.schemaVersion),
      V.unit(Ids.checkEntityRef("query", m.queryId)),
      RuntimeChecks.token("promptId", m.promptId, 128),
      RuntimeChecks.token("promptVersion", m.promptVersion, 128),
      RuntimeChecks.token("modelId", m.modelId, 128),
      RuntimeChecks.token("outputSchemaId", m.outputSchemaId, 128),
      RuntimeChecks.token("outputSchemaVersion", m.outputSchemaVersion, 128),
      StorageChecks.sha256("inputHash", m.inputHash),
      m.outputHash.fold(V.ok)(h => StorageChecks.sha256("outputHash", h)),
      if m.outcome == ModelCallOutcome.Succeeded && m.outputHash.isEmpty then
        Left(InvalidField("outputHash", "is required when the outcome is succeeded"))
      else V.ok,
      m.inputTokens.fold(V.ok)(t => V.atLeast("inputTokens", t, 0)),
      m.outputTokens.fold(V.ok)(t => V.atLeast("outputTokens", t, 0)),
      if m.latencyMillis >= 0L then V.ok
      else Left(InvalidField("latencyMillis", "must be >= 0")),
      if m.retries >= 0 && m.retries <= MaxRetries then V.ok
      else Left(InvalidField("retries", s"must be in 0..$MaxRetries")),
    )
  )
