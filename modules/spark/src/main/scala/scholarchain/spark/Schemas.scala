package scholarchain.spark

import org.apache.spark.sql.types.StructType

/** Column names shared by every windowed table. */
object Cols:
  val Window                  = "window"
  val WindowStart             = "windowStart"
  val WindowEnd               = "windowEnd"
  val windowCols: Seq[String] = Seq(Window, WindowStart, WindowEnd)

/** Explicit schemas of the job INPUT frames (flat; the file edge flattens the nested Hadoop rows).
  * Scala 3 cannot derive Spark encoders, so every frame is a DataFrame with one of these schemas.
  */
object InputSchemas:
  def ddl(s: String): StructType = StructType.fromDDL(s)

  /** One row per author mention of any source; `sourceType` is dblp, crossref or openalex. */
  val Mentions: StructType =
    ddl(
      "mentionId string, workId string, position int, rawName string, orcid string, sourceType string"
    )

  /** DBLP authorship (mention level). */
  val Authorship: StructType = ddl("mentionId string, workId string, position int, year int")

  /** Hadoop pair candidates (mention level, canonical order `first` < `second`). */
  val PairCandidates: StructType = ddl("workId string, year int, first string, second string")

  val Works: StructType =
    ddl("workId string, title string, year int, doi string, venueId string, topicIds array<string>")

  val Teams: StructType  = ddl("workId string, year int, teamSize int, mentionIds array<string>")
  val Topics: StructType = ddl("topicId string, label string")
  val Institutions: StructType = ddl("institutionId string, name string, country string")

  /** HJ1 person aliases (www records). Empty or absent in the 1K selection. */
  val Aliases: StructType = ddl("aliasKey string, names array<string>, orcids array<string>")

  /** Final match decisions (wire names: accepted, rejected, unresolved, conflict). */
  val Decisions: StructType =
    ddl("leftId string, rightId string, entityKind string, outcome string, policyVersion string")

  /** HJ6a entity views: id, kind (researcher, work) and source (dblp, crossref, openalex, ...). */
  val EntityViews: StructType = ddl("id string, kind string, source string")
end InputSchemas

/** Declared output tables. `windowed` tables carry `window, windowStart, windowEnd` first and are
  * partitioned by `window` on disk. The pipeline refuses to write a frame whose schema differs from
  * the declared one (names and types, nullability ignored).
  */
object Tables:
  /** Type of the row-level `provenance` column: the compact JSON text of core `Provenance`. */
  object Provenance:
    val ddl: String = "string"

  final case class Spec(name: String, windowed: Boolean, ddl: String):
    private val W = "window string, windowStart int, windowEnd int"

    def schema: StructType = StructType.fromDDL(if windowed then s"$W, $ddl" else ddl)

    /** Column names in order. */
    def columns: List[String] = schema.fieldNames.toList

  private def t(name: String, ddl: String) = Spec(name, windowed = false, ddl)
  private def w(name: String, ddl: String) = Spec(name, windowed = true, ddl)

  val IdentityClusters: Spec = t(
    "identity-clusters",
    "researcherId string, memberMentionIds array<string>, memberCount int, " +
      "baseIdentityIds array<string>, dblpPersonKeys array<string>, displayName string, " +
      "orcid string, orcids array<string>, ruleFired string, cannotLinkStatus string, " +
      "policyVersion string",
  )
  val CannotLinkBlocked: Spec = t(
    "cannot-link-blocked",
    "componentKey string, memberBaseIds array<string>, memberCount int, reason string, " +
      "rejectedOrConflictPairs long, distinctOrcids int, distinctDblpHomonymGroups int, unsuffixedVsSuffixedGroups int",
  )
  val WithinBaseRejections: Spec = t(
    "within-base-rejections-ignored",
    "leftId string, rightId string, outcome string, baseIdentityId string, baseName string",
  )
  val MentionResearcher: Spec =
    t(
      "mention-researcher",
      "mentionId string, researcherId string, sourceType string, baseIdentityId string",
    )
  val Researchers: Spec = t(
    "researchers",
    "researcherId string, displayName string, orcid string, orcids array<string>, " +
      "identityRule string, cannotLinkStatus string, memberMentionCount int, firstYear int, " +
      "lastYear int",
  )
  val ResolvesTo: Spec = t(
    "resolves-to",
    "edgeType string, sourceId string, targetId string, orcid string, " +
      "evidenceMentionIds array<string>, provenance " + Provenance.ddl,
  )
  // Repair 02 (R-V1): one vertex per distinct RESOLVES_TO target, so no edge targets a missing vertex.
  val ExternalIdentities: Spec = t(
    "external-identities",
    "externalId string, kind string, value string",
  )
  val Works: Spec = t(
    "works",
    "workId string, title string, year int, doi string, venueId string, " +
      "topicIds array<string>, teamSize int, externalWorkIds array<string>",
  )
  val Authored: Spec =
    t("authored", "researcherId string, workId string, year int, position int, mentionCount int")
  val Topics: Spec       = t("topics", "topicId string, label string, linkedWorkCount long")
  val Institutions: Spec = t("institutions", "institutionId string, name string, country string")
  val Venues: Spec       = t("venues", "venueId string, name string, workCount long")
  val HasTopic: Spec     = t("has-topic", "workId string, topicId string, viaWorkId string")
  val PublishedIn: Spec  = t("published-in", "workId string, venueId string")
  // Exactly the fields of core `CoauthorEdgeV2` (windowStart/windowEnd are the window columns).
  val CoauthorEdges: Spec = w(
    "coauthor-edges",
    "schemaVersion int, edgeId string, researcherA string, researcherB string, " +
      "policyVersion string, jointPublicationCount int, firstCollaborationYear int, " +
      "lastCollaborationYear int, representativeWorkIds array<string>, " +
      "supportingWorksDigest string, supportingWorksTruncated boolean, evidenceTable string, " +
      "evidenceKey string, evidencePartition string, provenance " + Provenance.ddl,
  )
  // The full edge-to-work evidence the edge's evidence pointer references (one row per work).
  val CoauthorEdgeWorks: Spec = w("coauthor-edge-works", "edgeId string, workId string")
  // Exactly the fields of core `ResearcherMetricsV2` (windowStart/windowEnd are window columns).
  val ResearcherMetrics: Spec = w(
    "researcher-metrics",
    "schemaVersion int, researcherId string, policyVersion string, publicationCount long, " +
      "coauthorCount long, teamSizeTotal long, avgTeamSize double, maxTeamSize int, " +
      "weightedDegree long, maxTeamWorkCount int, maxTeamWorkIds array<string>, " +
      "communityId string, componentId string, coreness int, crossCommunityDegree long, " +
      "participationCoefficient double, communitiesTouched int, " +
      "tieStrengthWeightedDegree double, provenance " + Provenance.ddl,
  )
  val WindowSummary: Spec = w(
    "window-summary",
    "nodeCount long, edgeCount long, workCount long, componentCount long, singletonCount long, " +
      "largestComponentSize long, largestComponentFraction double, communityCount long, " +
      "maxCoreness int, maxTeamSize int, maxTeamWorkId string",
  )
  val Components: Spec          = w("components", "componentId string, size long")
  val ComponentMembership: Spec =
    w("component-membership", "componentId string, researcherId string")
  val Communities: Spec = w(
    "communities",
    "communityId string, size long, method string, parameters map<string,string>, " +
      "internalEdgeCount long, crossEdgeCount long",
  )
  val CommunityMembership: Spec =
    w("community-membership", "communityId string, researcherId string")
  val CommunityCrossEdges: Spec =
    w("community-cross-edges", "communityA string, communityB string, edgeCount long")
  val Bridges: Spec = w(
    "bridges",
    "researcherId string, measure string, value double, parameters map<string,string>",
  )
  val Coreness: Spec = w("coreness", "researcherId string, coreness int")
  val Paths: Spec    = w(
    "paths",
    "startId string, endId string, hops array<string>, edgeIds array<string>, " +
      "supportingWorkIds array<array<string>>, length int",
  )
  val PathEstimates: Spec = w(
    "path-estimates",
    "sourcesRequested int, sourcesUsed long, seed long, maxDepth int, reachedPairs long, " +
      "unreachedWithinDepthPairs long, avgPathLength double, effectiveDiameter90 int, " +
      "maxObservedDistance int",
  )
  val TemporalChanges: Spec = w(
    "temporal-changes",
    "researcherId string, prevWindow string, status string, coauthorCount long, " +
      "prevCoauthorCount long, retainedCoauthors long, newCoauthors long, lostCoauthors long, " +
      "avgTeamSize double, prevAvgTeamSize double, maxTeamSize int, prevMaxTeamSize int, " +
      "componentSize long, prevComponentSize long, communitySize long, prevCommunitySize long, " +
      "crossCommunityDegree long, prevCrossCommunityDegree long",
  )
  val SemanticSummaries: Spec = w(
    "semantic-summaries",
    "entityId string, documentType string, textHash string, text string, runId string",
  )
  val AlgorithmStatus: Spec = t(
    "algorithm-status",
    "algorithm string, scope string, iterations int, converged boolean, iterationCap int",
  )

  val all: List[Spec] = List(
    IdentityClusters,
    CannotLinkBlocked,
    WithinBaseRejections,
    MentionResearcher,
    Researchers,
    ResolvesTo,
    ExternalIdentities,
    Works,
    Authored,
    Topics,
    Institutions,
    Venues,
    HasTopic,
    PublishedIn,
    CoauthorEdges,
    CoauthorEdgeWorks,
    ResearcherMetrics,
    WindowSummary,
    Components,
    ComponentMembership,
    Communities,
    CommunityMembership,
    CommunityCrossEdges,
    Bridges,
    Coreness,
    Paths,
    PathEstimates,
    TemporalChanges,
    SemanticSummaries,
    AlgorithmStatus,
  )

  def byName(name: String): Option[Spec] = all.find(_.name == name)
end Tables
