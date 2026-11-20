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
