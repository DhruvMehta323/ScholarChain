package scholarchain.core.rest

import scholarchain.core.{
  CollaborationMetric,
  ClaimVerificationStatus,
  EvidenceType,
  Wire,
  WirePickle,
}

/** Phase 10 REST enums (additive). Unlike the lowercase snake_case enums elsewhere in `core`, the
  * wire names here follow the course specification's REST examples verbatim (`RUNNING`,
  * `TEXT_CHUNK`, `COAUTHORED_WITH`, `in|out|both`, `synchronous|asynchronous`). Reading an unknown
  * wire name is rejected. See `REST-CONTRACTS.md`.
  */

/** Discriminator of `QueryResponse`. */
enum QueryMode(val wire: String):
  case Synchronous  extends QueryMode("synchronous")
  case Asynchronous extends QueryMode("asynchronous")

object QueryMode:
  given WirePickle.ReadWriter[QueryMode] = Wire.enumRw("QueryMode", values.toSeq, _.wire)

/** Closed set of ranking metrics a request may name. `DISTINCT_COAUTHOR_COUNT` is the degree of the
  * coauthorship graph. Community ids are categorical and cannot be ranked.
  */
enum RankingMetric(val wire: String, val metric: CollaborationMetric):
  case DistinctCoauthorCount
      extends RankingMetric("DISTINCT_COAUTHOR_COUNT", CollaborationMetric.Degree)
  case WeightedDegree extends RankingMetric("WEIGHTED_DEGREE", CollaborationMetric.WeightedDegree)
  case Betweenness    extends RankingMetric("BETWEENNESS", CollaborationMetric.Betweenness)
  case PageRank       extends RankingMetric("PAGERANK", CollaborationMetric.PageRank)
  case ClusteringCoefficient
      extends RankingMetric("CLUSTERING_COEFFICIENT", CollaborationMetric.ClusteringCoefficient)
  case BridgeScore extends RankingMetric("BRIDGE_SCORE", CollaborationMetric.BridgeScore)

object RankingMetric:
  given WirePickle.ReadWriter[RankingMetric] =
    Wire.enumRw("RankingMetric", values.toSeq, _.wire)
  def parse(s: String): Option[RankingMetric] = values.find(_.wire == s)

/** Lifecycle of an asynchronous query job. `Succeeded` and `Failed` are terminal. */
enum JobState(val wire: String):
  case Queued    extends JobState("QUEUED")
  case Running   extends JobState("RUNNING")
  case Succeeded extends JobState("SUCCEEDED")
  case Failed    extends JobState("FAILED")

  def isTerminal: Boolean = this == Succeeded || this == Failed

object JobState:
  given WirePickle.ReadWriter[JobState] = Wire.enumRw("JobState", values.toSeq, _.wire)

/** The workflow stage a job is in (or reached last). Mirrors the Phase 09 workflow stages. */
enum JobStage(val wire: String):
  case Planning          extends JobStage("PLANNING")
  case EntityResolution  extends JobStage("ENTITY_RESOLUTION")
  case Retrieval         extends JobStage("RETRIEVAL")
  case EvidenceAssembly  extends JobStage("EVIDENCE_ASSEMBLY")
  case AnswerSynthesis   extends JobStage("ANSWER_SYNTHESIS")
  case ClaimVerification extends JobStage("CLAIM_VERIFICATION")
  case Audit             extends JobStage("AUDIT")

object JobStage:
  given WirePickle.ReadWriter[JobStage] = Wire.enumRw("JobStage", values.toSeq, _.wire)

/** Verification status of a claim as the API names it (`VERIFIED`). One-to-one with
  * `ClaimVerificationStatus`.
  */
enum ClaimStatus(val wire: String):
  case Verified           extends ClaimStatus("VERIFIED")
  case PartiallySupported extends ClaimStatus("PARTIALLY_SUPPORTED")
  case Unsupported        extends ClaimStatus("UNSUPPORTED")
  case Conflicting        extends ClaimStatus("CONFLICTING")

object ClaimStatus:
  given WirePickle.ReadWriter[ClaimStatus] = Wire.enumRw("ClaimStatus", values.toSeq, _.wire)

  def from(s: ClaimVerificationStatus): ClaimStatus = s match
    case ClaimVerificationStatus.Verified           => Verified
    case ClaimVerificationStatus.PartiallySupported => PartiallySupported
    case ClaimVerificationStatus.Unsupported        => Unsupported
    case ClaimVerificationStatus.Conflicting        => Conflicting

/** Evidence type as the API names it (`TEXT_CHUNK`). One-to-one with `EvidenceType`. */
enum EvidenceKind(val wire: String):
  case WorkRecord        extends EvidenceKind("WORK_RECORD")
  case Authorship        extends EvidenceKind("AUTHORSHIP")
  case CoauthorEdge      extends EvidenceKind("COAUTHOR_EDGE")
  case GraphPath         extends EvidenceKind("GRAPH_PATH")
  case Metric            extends EvidenceKind("METRIC")
  case TextChunk         extends EvidenceKind("TEXT_CHUNK")
  case ResearcherProfile extends EvidenceKind("RESEARCHER_PROFILE")

object EvidenceKind:
  given WirePickle.ReadWriter[EvidenceKind] = Wire.enumRw("EvidenceKind", values.toSeq, _.wire)

  def from(t: EvidenceType): EvidenceKind = t match
    case EvidenceType.WorkRecord        => WorkRecord
    case EvidenceType.Authorship        => Authorship
    case EvidenceType.CoauthorEdge      => CoauthorEdge
    case EvidenceType.GraphPath         => GraphPath
    case EvidenceType.Metric            => Metric
    case EvidenceType.TextChunk         => TextChunk
    case EvidenceType.ResearcherProfile => ResearcherProfile

/** Edge direction of a neighbors request. */
enum NeighborDirection(val wire: String):
  case In   extends NeighborDirection("in")
  case Out  extends NeighborDirection("out")
  case Both extends NeighborDirection("both")

object NeighborDirection:
  given WirePickle.ReadWriter[NeighborDirection] =
    Wire.enumRw("NeighborDirection", values.toSeq, _.wire)
  def parse(s: String): Option[NeighborDirection] = values.find(_.wire == s)

/** The closed allowlist of edge types a neighbors request may name (the spec's names). */
enum EdgeType(val wire: String):
  case CoauthoredWith extends EdgeType("COAUTHORED_WITH")
  case Authored       extends EdgeType("AUTHORED")
  case HasTopic       extends EdgeType("HAS_TOPIC")
  case AffiliatedWith extends EdgeType("AFFILIATED_WITH")
  case PublishedIn    extends EdgeType("PUBLISHED_IN")

object EdgeType:
  given WirePickle.ReadWriter[EdgeType]  = Wire.enumRw("EdgeType", values.toSeq, _.wire)
  def parse(s: String): Option[EdgeType] = values.find(_.wire == s)

  /** Maps a stored `GraphEdge.edgeType` label (any case) onto the allowlist. */
  def fromGraphLabel(label: String): Option[EdgeType] = parse(
    label.toUpperCase(java.util.Locale.ROOT)
  )

/** Retrieval modes reported by the explain view. */
enum RetrievalMode(val wire: String):
  case Graph   extends RetrievalMode("GRAPH")
  case Vector  extends RetrievalMode("VECTOR")
  case Metrics extends RetrievalMode("METRICS")

object RetrievalMode:
  given WirePickle.ReadWriter[RetrievalMode] = Wire.enumRw("RetrievalMode", values.toSeq, _.wire)

/** Overall readiness. */
enum ReadinessState(val wire: String):
  case Ready    extends ReadinessState("ready")
  case NotReady extends ReadinessState("not_ready")

object ReadinessState:
  given WirePickle.ReadWriter[ReadinessState] =
    Wire.enumRw("ReadinessState", values.toSeq, _.wire)

/** State of one dependency check. */
enum DependencyState(val wire: String):
  case Up   extends DependencyState("up")
  case Down extends DependencyState("down")

object DependencyState:
  given WirePickle.ReadWriter[DependencyState] =
    Wire.enumRw("DependencyState", values.toSeq, _.wire)

/** State of an idempotency record. */
enum IdempotencyState(val wire: String):
  case InProgress extends IdempotencyState("in_progress")
  case Completed  extends IdempotencyState("completed")
  case Failed     extends IdempotencyState("failed")

object IdempotencyState:
  given WirePickle.ReadWriter[IdempotencyState] =
    Wire.enumRw("IdempotencyState", values.toSeq, _.wire)

/** Closed error-code set. Each code fixes its HTTP status and whether a client may retry the same
  * request unchanged (`retryable` default). The wire name is the enum name in UPPER_SNAKE_CASE.
  * Every status of the spec's error model (400, 401, 403, 404, 409, 422, 429, 502, 503, 504, 500)
  * has at least one code.
  */
enum ApiErrorCode(val wire: String, val httpStatus: Int, val retryable: Boolean):
  case MalformedRequest         extends ApiErrorCode("MALFORMED_REQUEST", 400, false)
  case InvalidField             extends ApiErrorCode("INVALID_FIELD", 400, false)
  case UnknownField             extends ApiErrorCode("UNKNOWN_FIELD", 400, false)
  case Unauthenticated          extends ApiErrorCode("UNAUTHENTICATED", 401, false)
  case Forbidden                extends ApiErrorCode("FORBIDDEN", 403, false)
  case NotFound                 extends ApiErrorCode("NOT_FOUND", 404, false)
  case QueryNotFound            extends ApiErrorCode("QUERY_NOT_FOUND", 404, false)
  case JobNotFound              extends ApiErrorCode("JOB_NOT_FOUND", 404, false)
  case NodeNotFound             extends ApiErrorCode("NODE_NOT_FOUND", 404, false)
  case EvidenceNotFound         extends ApiErrorCode("EVIDENCE_NOT_FOUND", 404, false)
  case IdempotencyConflict      extends ApiErrorCode("IDEMPOTENCY_CONFLICT", 409, false)
  case IncompatibleState        extends ApiErrorCode("INCOMPATIBLE_STATE", 409, true)
  case UnsupportedConstraint    extends ApiErrorCode("UNSUPPORTED_CONSTRAINT", 422, false)
  case UnsupportedRetrievalMode extends ApiErrorCode("UNSUPPORTED_RETRIEVAL_MODE", 422, false)
  case UnsupportedRetrievalPlan extends ApiErrorCode("UNSUPPORTED_RETRIEVAL_PLAN", 422, false)
  case RateLimited              extends ApiErrorCode("RATE_LIMITED", 429, true)
  case BudgetExceeded           extends ApiErrorCode("BUDGET_EXCEEDED", 429, false)
  case DownstreamFailure        extends ApiErrorCode("DOWNSTREAM_FAILURE", 502, true)
  case NotReady                 extends ApiErrorCode("NOT_READY", 503, true)
  case Unavailable              extends ApiErrorCode("UNAVAILABLE", 503, true)
  case Timeout                  extends ApiErrorCode("TIMEOUT", 504, true)
  case Internal                 extends ApiErrorCode("INTERNAL", 500, false)

object ApiErrorCode:
  given WirePickle.ReadWriter[ApiErrorCode] = Wire.enumRw("ApiErrorCode", values.toSeq, _.wire)

  /** The eleven statuses of the error model. */
  val Statuses: Set[Int] = Set(400, 401, 403, 404, 409, 422, 429, 502, 503, 504, 500)

  def parse(s: String): Option[ApiErrorCode] = values.find(_.wire == s)

  /** A generic code for a status (the first code declared for it). */
  def forStatus(status: Int): Option[ApiErrorCode] = values.find(_.httpStatus == status)
