package scholarchain.core

/** Enums with frozen wire names. Reading an unknown wire name is rejected. */
enum SourceType(val wire: String):
  case Dblp         extends SourceType("dblp")
  case OpenAlex     extends SourceType("openalex")
  case Crossref     extends SourceType("crossref")
  case Orcid        extends SourceType("orcid")
  case ScholarChain extends SourceType("scholarchain")

object SourceType:
  given WirePickle.ReadWriter[SourceType] = Wire.enumRw("SourceType", values.toSeq, _.wire)

enum MatchOutcome(val wire: String):
  case Match     extends MatchOutcome("match")
  case NonMatch  extends MatchOutcome("non_match")
  case Uncertain extends MatchOutcome("uncertain")

object MatchOutcome:
  given WirePickle.ReadWriter[MatchOutcome] = Wire.enumRw("MatchOutcome", values.toSeq, _.wire)

enum EvidenceType(val wire: String):
  case WorkRecord        extends EvidenceType("work_record")
  case Authorship        extends EvidenceType("authorship")
  case CoauthorEdge      extends EvidenceType("coauthor_edge")
  case GraphPath         extends EvidenceType("graph_path")
  case Metric            extends EvidenceType("metric")
  case TextChunk         extends EvidenceType("text_chunk")
  case ResearcherProfile extends EvidenceType("researcher_profile")

object EvidenceType:
  given WirePickle.ReadWriter[EvidenceType] = Wire.enumRw("EvidenceType", values.toSeq, _.wire)

enum CollaborationMetric(val wire: String):
  case Degree                extends CollaborationMetric("degree")
  case WeightedDegree        extends CollaborationMetric("weighted_degree")
  case Betweenness           extends CollaborationMetric("betweenness")
  case PageRank              extends CollaborationMetric("pagerank")
  case ClusteringCoefficient extends CollaborationMetric("clustering_coefficient")
  case BridgeScore           extends CollaborationMetric("bridge_score")
  case CommunityId           extends CollaborationMetric("community_id")

object CollaborationMetric:
  given WirePickle.ReadWriter[CollaborationMetric] =
    Wire.enumRw("CollaborationMetric", values.toSeq, _.wire)

enum QueryOperation(val wire: String):
  case LookupResearcher    extends QueryOperation("lookup_researcher")
  case ListCoauthors       extends QueryOperation("list_coauthors")
  case CollaborationPath   extends QueryOperation("collaboration_path")
  case TopBridgeResearcher extends QueryOperation("top_bridge_researchers")
  case CollaborationTrend  extends QueryOperation("collaboration_trend")
  case SearchWorks         extends QueryOperation("search_works")

object QueryOperation:
  given WirePickle.ReadWriter[QueryOperation] =
    Wire.enumRw("QueryOperation", values.toSeq, _.wire)

enum VerificationStatus(val wire: String):
  case Supported          extends VerificationStatus("supported")
  case PartiallySupported extends VerificationStatus("partially_supported")
  case Unsupported        extends VerificationStatus("unsupported")
  case Contradicted       extends VerificationStatus("contradicted")
  case Unverified         extends VerificationStatus("unverified")

object VerificationStatus:
  given WirePickle.ReadWriter[VerificationStatus] =
    Wire.enumRw("VerificationStatus", values.toSeq, _.wire)

/** Kind of external identifier used for exact-identifier evidence. */
enum IdentifierType(val wire: String):
  case Doi      extends IdentifierType("doi")
  case Arxiv    extends IdentifierType("arxiv")
  case Orcid    extends IdentifierType("orcid")
  case OpenAlex extends IdentifierType("openalex")

object IdentifierType:
  given WirePickle.ReadWriter[IdentifierType] =
    Wire.enumRw("IdentifierType", values.toSeq, _.wire)

/** Outcome of an exact-identifier comparison. This is evidence, not a match decision. */
enum ExactIdentifierOutcome(val wire: String):
  case ExactLink extends ExactIdentifierOutcome("exact_link")
  case Duplicate extends ExactIdentifierOutcome("duplicate")
  case Conflict  extends ExactIdentifierOutcome("conflict")

object ExactIdentifierOutcome:
  given WirePickle.ReadWriter[ExactIdentifierOutcome] =
    Wire.enumRw("ExactIdentifierOutcome", values.toSeq, _.wire)

/** Why an input record was rejected (reject-v1). Wire names are frozen. */
enum RejectReason(val wire: String):
  case MalformedRecord   extends RejectReason("malformed_record")
  case EncodingError     extends RejectReason("encoding_error")
  case MissingTitle      extends RejectReason("missing_title")
  case MissingYear       extends RejectReason("missing_year")
  case InvalidYear       extends RejectReason("invalid_year")
  case MissingAuthors    extends RejectReason("missing_authors")
  case InvalidIdentifier extends RejectReason("invalid_identifier")
  case UnsupportedType   extends RejectReason("unsupported_type")
  case OversizedRecord   extends RejectReason("oversized_record")
  case PairCapExceeded   extends RejectReason("pair_cap_exceeded")
  case DuplicateRecord   extends RejectReason("duplicate_record")
  case SchemaViolation   extends RejectReason("schema_violation")

object RejectReason:
  given WirePickle.ReadWriter[RejectReason] = Wire.enumRw("RejectReason", values.toSeq, _.wire)

/** Kind of entity an entity-resolution candidate or decision is about. Wire names are frozen. */
enum EntityKind(val wire: String):
  case Researcher extends EntityKind("researcher")
  case Work       extends EntityKind("work")

object EntityKind:
  given WirePickle.ReadWriter[EntityKind] = Wire.enumRw("EntityKind", values.toSeq, _.wire)

/** Whether a block key is the primary key or a fallback (finer) key. */
enum BlockKeyType(val wire: String):
  case Primary  extends BlockKeyType("primary")
  case Fallback extends BlockKeyType("fallback")

object BlockKeyType:
  given WirePickle.ReadWriter[BlockKeyType] = Wire.enumRw("BlockKeyType", values.toSeq, _.wire)

/** How a decision was reached. */
enum MatchPath(val wire: String):
  case ExactId       extends MatchPath("exact_id")
  case Deterministic extends MatchPath("deterministic")
  case Probabilistic extends MatchPath("probabilistic")
  case Llm           extends MatchPath("llm")

object MatchPath:
  given WirePickle.ReadWriter[MatchPath] = Wire.enumRw("MatchPath", values.toSeq, _.wire)

/** Entity-resolution decision outcome (MatchDecisionV2, ResolvedMention). */
enum ResolutionOutcome(val wire: String):
  case Accepted   extends ResolutionOutcome("accepted")
  case Rejected   extends ResolutionOutcome("rejected")
  case Unresolved extends ResolutionOutcome("unresolved")
  case Conflict   extends ResolutionOutcome("conflict")

object ResolutionOutcome:
  given WirePickle.ReadWriter[ResolutionOutcome] =
    Wire.enumRw("ResolutionOutcome", values.toSeq, _.wire)

/** Outcome of an LLM adjudication. `Abstain` is a valid, first-class answer. */
enum AdjudicationOutcome(val wire: String):
  case Accept  extends AdjudicationOutcome("accept")
  case Reject  extends AdjudicationOutcome("reject")
  case Abstain extends AdjudicationOutcome("abstain")

object AdjudicationOutcome:
  given WirePickle.ReadWriter[AdjudicationOutcome] =
    Wire.enumRw("AdjudicationOutcome", values.toSeq, _.wire)
