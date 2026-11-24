package scholarchain.core

/** Phase 09 runtime enums (additive; `QueryOperation` v1 and `VerificationStatus` v1 are frozen and
  * untouched). Wire names are lowercase snake_case like every other enum in `core`. Reading an
  * unknown wire name is rejected.
  */

/** The allowlist of operations a `QueryPlanV2` may contain. Anything else is rejected at the
  * contract boundary, so a model-proposed or user-supplied operation name can never reach a graph
  * template or a store. Each operation maps onto bounded, parameterized templates only.
  */
enum QueryOperationV2(val wire: String):
  case RankByMetric        extends QueryOperationV2("rank_researchers_by_metric")
  case MaxTeamSize         extends QueryOperationV2("max_team_size")
  case DirectCoauthors     extends QueryOperationV2("direct_coauthors")
  case ShortestPath        extends QueryOperationV2("shortest_path")
  case ComponentLookup     extends QueryOperationV2("component_lookup")
  case CommunityComparison extends QueryOperationV2("community_comparison")
  case BridgeRanking       extends QueryOperationV2("bridge_ranking")
  case TemporalComparison  extends QueryOperationV2("temporal_comparison")
  case EntityFilter        extends QueryOperationV2("entity_filter")
  case SemanticExplanation extends QueryOperationV2("semantic_explanation")

  /** The one operation beyond the ten named by the work order: the stored-metrics lookup. */
  case ResearcherMetricsLookup extends QueryOperationV2("researcher_metrics_lookup")

object QueryOperationV2:
  given WirePickle.ReadWriter[QueryOperationV2] =
    Wire.enumRw("QueryOperationV2", values.toSeq, _.wire)

/** Result of checking one answer claim against the evidence bundle. Wire names are lowercase
  * snake_case (`verified`, `partially_supported`, `unsupported`, `conflicting`), consistent with
  * the rest of `core`; the logical names VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING
  * map one-to-one. Distinct from the frozen `VerificationStatus` v1.
  */
enum ClaimVerificationStatus(val wire: String):
  case Verified           extends ClaimVerificationStatus("verified")
  case PartiallySupported extends ClaimVerificationStatus("partially_supported")
  case Unsupported        extends ClaimVerificationStatus("unsupported")
  case Conflicting        extends ClaimVerificationStatus("conflicting")

object ClaimVerificationStatus:
  given WirePickle.ReadWriter[ClaimVerificationStatus] =
    Wire.enumRw("ClaimVerificationStatus", values.toSeq, _.wire)

/** The runtime stage that made a model call. */
enum ModelStage(val wire: String):
  case QueryPlanner      extends ModelStage("query_planner")
  case AnswerSynthesis   extends ModelStage("answer_synthesis")
  case ClaimVerification extends ModelStage("claim_verification")

object ModelStage:
  given WirePickle.ReadWriter[ModelStage] = Wire.enumRw("ModelStage", values.toSeq, _.wire)

/** Typed outcome of one model call (see `ModelError.outcome`). */
enum ModelCallOutcome(val wire: String):
  case Succeeded       extends ModelCallOutcome("succeeded")
  case MalformedOutput extends ModelCallOutcome("malformed_output")
  case Throttled       extends ModelCallOutcome("throttled")
  case Unavailable     extends ModelCallOutcome("unavailable")
  case InvalidRequest  extends ModelCallOutcome("invalid_request")
  case BudgetExceeded  extends ModelCallOutcome("budget_exceeded")
  case Failed          extends ModelCallOutcome("failed")

object ModelCallOutcome:
  given WirePickle.ReadWriter[ModelCallOutcome] =
    Wire.enumRw("ModelCallOutcome", values.toSeq, _.wire)

/** Model provider selected by configuration. */
enum ModelProvider(val wire: String):
  case Bedrock   extends ModelProvider("bedrock")
  case Anthropic extends ModelProvider("anthropic")
  case Fake      extends ModelProvider("fake")

object ModelProvider:
  def parse(s: String): Option[ModelProvider] = values.find(_.wire == s)
