package scholarchain.core

import WirePickle.{*, given}
import ValidationError.*

/** Phase 06 repair 01 contracts: `CoauthorEdgeV2` and `ResearcherMetricsV2` (additive; the frozen
  * Phase 03 `CoauthorEdge`, `ResearcherMetrics`, `MetricValue` and `CollaborationMetric` are
  * unchanged and stay valid).
  *
  * Naming: the Scala type carries `V2`; the wire `schemaVersion` of these records is still `1`
  * because `SchemaVersion.Supported` is unchanged (`V2` names a new record family, not a new
  * version of the schema-version integer).
  *
  * Field names equal the column names of the Spark tables `coauthor-edges` and `researcher-metrics`
  * (see `modules/spark/SPARK-CONTRACTS.md`), so an exported row maps one-to-one. The window is
  * explicit as `windowStart` and `windowEnd` (inclusive years); all-time is `GraphWindows.AllTime`
  * (1000-9999). Rows carry no per-row provenance in Parquet: the table's `_provenance.json`
  * supplies the `provenance` field when a row is lifted into the record.
  *
  * Frozen JSON examples: classpath resources `contracts/coauthor-edge-v2.example.json` and
  * `contracts/researcher-metrics-v2.example.json`. Each validates under its contract.
  */
private[core] object V2Checks:
  import V.*

  /** Ascending by Unicode code point, which equals UTF-8 byte order (Spark's string order). */
  val codePointOrdering: Ordering[String] = (a: String, b: String) =>
    val x = a.codePoints.toArray
    val y = b.codePoints.toArray
    val n = math.min(x.length, y.length)
    var i = 0
    while i < n && x(i) == y(i) do i += 1
    if i < n then Integer.compare(x(i), y(i)) else Integer.compare(x.length, y.length)

  def window(start: Int, end: Int): R = unit(GraphWindow.validate(GraphWindow(start, end)))

  def nonNegative(field: String, v: Long): R =
    if v >= 0L then ok else Left(InvalidField(field, "must be >= 0"))

  def nonNegative(field: String, v: Double): R =
    all(finite(field, v), if v >= 0.0 then ok else Left(InvalidField(field, "must be >= 0")))

/** One undirected coauthor edge in one window, with the full evidence held elsewhere.
  *
  *   - `edgeId`: must equal `Ids.coauthorEdgeId(researcherA, researcherB, GraphWindow(windowStart,
  *     windowEnd), policyVersion)`.
  *   - `researcherA` < `researcherB` (`String.compareTo`), no self-pair, both valid researcher IDs.
  *   - `jointPublicationCount` (>= 1) is the edge weight: the number of distinct joint works in the
  *     window. It may exceed the number of representative IDs (unlike frozen `CoauthorEdge`).
  *   - `firstCollaborationYear` <= `lastCollaborationYear`, both inside the window.
  *   - `representativeWorkIds`: non-empty, distinct valid work IDs, at most `jointPublicationCount`
  *     of them (the producer's bound is a Spark config value, not a core rule).
  *   - `supportingWorksDigest`: `Hashing.digest(CoauthorEdgeV2.WorksDigestTag, all supporting work
  *     IDs sorted by Unicode code point)`, 64 lowercase hex.
  *   - `supportingWorksTruncated`: must equal `representativeWorkIds.size < jointPublicationCount`.
  *     When false the representatives ARE the full set, so the digest is recomputed and checked.
  *   - Evidence pointer (checkable, not free text): `evidenceTable` must equal
  *     `CoauthorEdgeV2.EvidenceTable`; `evidenceKey` must equal `edgeId` (the row key, column
  *     `edgeId`); `evidencePartition` must equal the window label (`2000-2004`, Parquet partition
  *     `window=<label>`). The table holds one row per (edgeId, workId) and its row count for the
  *     key must equal `jointPublicationCount`, which a consumer can verify.
  */
final case class CoauthorEdgeV2(
    schemaVersion: Int,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    windowStart: Int,
    windowEnd: Int,
    policyVersion: String,
    jointPublicationCount: Int,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: List[String],
    supportingWorksDigest: String,
    supportingWorksTruncated: Boolean,
    evidenceTable: String,
    evidenceKey: String,
    evidencePartition: String,
    provenance: Provenance,
) derives ReadWriter

object CoauthorEdgeV2:
  /** Digest tag of the supporting-work set; equals the Spark `supportingWorksDigest` tag. */
  val WorksDigestTag: String = "scholarchain.coauthor-works.v1"

  /** Table (Spark output table name) holding the full (edgeId, workId) evidence. */
  val EvidenceTable: String = "coauthor-edge-works"

  def expectedDigest(workIds: Seq[String]): String =
    Hashing.digest(WorksDigestTag, workIds.sorted(using V2Checks.codePointOrdering))

  given Contract[CoauthorEdgeV2] = V.contract(e =>
    val reps = e.representativeWorkIds
    V.all(
      V.schema(e.schemaVersion),
      V.unit(OrderedPair.validate(OrderedPair(e.researcherA, e.researcherB))),
      V2Checks.window(e.windowStart, e.windowEnd),
      V.unit(Ids.token("policyVersion", e.policyVersion)),
      V.matches(
        "edgeId",
        Ids.coauthorEdgeId(
          e.researcherA,
          e.researcherB,
          GraphWindow(e.windowStart, e.windowEnd),
          e.policyVersion,
        ),
        e.edgeId,
      ),
      V.atLeast("jointPublicationCount", e.jointPublicationCount, 1),
      V.year(e.firstCollaborationYear),
      V.year(e.lastCollaborationYear),
      if e.firstCollaborationYear <= e.lastCollaborationYear then V.ok
      else Left(InvalidField("firstCollaborationYear", "must be <= lastCollaborationYear")),
      if e.firstCollaborationYear >= e.windowStart && e.lastCollaborationYear <= e.windowEnd
      then V.ok
      else Left(InvalidField("firstCollaborationYear", "collaboration years must lie in window")),
      V.nonEmpty("representativeWorkIds", reps),
      V.each(reps)(w => V.unit(Ids.checkWorkId(w))),
      V.distinct("representativeWorkIds", reps),
      if reps.size <= e.jointPublicationCount then V.ok
      else Left(InvalidField("representativeWorkIds", "must not exceed jointPublicationCount")),
      if Hashing.isSha256Hex(e.supportingWorksDigest) then V.ok
      else Left(InvalidField("supportingWorksDigest", "must be 64 lowercase hex characters")),
      if e.supportingWorksTruncated == (reps.size < e.jointPublicationCount) then V.ok
      else
        Left(
          InvalidField(
            "supportingWorksTruncated",
            "must be true exactly when representativeWorkIds.size < jointPublicationCount",
          )
        )
      ,
      if e.supportingWorksTruncated || e.supportingWorksDigest == expectedDigest(reps) then V.ok
      else
        Left(
          InvalidField(
            "supportingWorksDigest",
            "must equal the digest of representativeWorkIds when not truncated",
          )
        )
      ,
      if e.evidenceTable == EvidenceTable then V.ok
      else Left(InvalidField("evidenceTable", s"must equal $EvidenceTable")),
      if e.evidenceKey == e.edgeId then V.ok
      else Left(InvalidField("evidenceKey", "must equal edgeId")),
      if e.evidencePartition == GraphWindow.label(GraphWindow(e.windowStart, e.windowEnd)) then V.ok
      else Left(InvalidField("evidencePartition", "must equal the window label")),
      V.prov(e.provenance),
    )
  )
end CoauthorEdgeV2

/** Phase 06 collaboration metrics of one researcher in one window, as NAMED fields (not the frozen
  * `CollaborationMetric` enum list). Definitions (`SPARK-CONTRACTS.md` section 5):
  *   - `publicationCount` P_r: works of r in the window.
  *   - `coauthorCount`: distinct coauthors (unweighted degree), C_r.
  *   - `teamSizeTotal` T_r: sum of the team sizes of r's works (r counted in each team).
  *   - `avgTeamSize` A_r = T_r / P_r (0 when P_r = 0); `maxTeamSize` M_r.
  *   - `weightedDegree` W_r: sum of `jointPublicationCount` over incident edges (not a tie-strength
  *     weight; that is the optional extra `tieStrengthWeightedDegree`).
  *   - `maxTeamWorkCount`, `maxTeamWorkIds`: works whose team size equals M_r (ids distinct valid
  *     work IDs, at most `maxTeamWorkCount`, which is at most P_r; the list may be bounded).
  *   - `communityId`, `componentId`: `community:...`, `component:...` references.
  *   - `coreness`: k-core number when the Spark output has it (<= `coauthorCount`).
  *   - Bridge measures replacing the single bridge score: `crossCommunityDegree` (coauthors outside
  *     r's community, <= `coauthorCount`), `participationCoefficient` in [0, 1], and
  *     `communitiesTouched` (distinct communities among coauthors plus r's own, >= 1).
  *
  * Invariants checked: M_r <= T_r; T_r >= P_r; M_r >= 1 and A_r <= M_r when P_r >= 1; |A_r - T_r /
  * P_r| <= 1e-6; W_r >= C_r; `crossCommunityDegree == 0` exactly when `communitiesTouched == 1`;
  * `communitiesTouched <= crossCommunityDegree + 1`; `participationCoefficient == 0` when
  * `communitiesTouched == 1`.
  */
final case class ResearcherMetricsV2(
    schemaVersion: Int,
    researcherId: String,
    windowStart: Int,
    windowEnd: Int,
    policyVersion: String,
    publicationCount: Long,
    coauthorCount: Long,
    teamSizeTotal: Long,
    avgTeamSize: Double,
    maxTeamSize: Int,
    weightedDegree: Long,
    maxTeamWorkCount: Int,
    maxTeamWorkIds: List[String],
    communityId: String,
    componentId: String,
    coreness: Option[Int],
    crossCommunityDegree: Long,
    participationCoefficient: Double,
    communitiesTouched: Int,
    tieStrengthWeightedDegree: Option[Double],
    provenance: Provenance,
) derives ReadWriter

object ResearcherMetricsV2:
  /** Tolerance for A_r against T_r / P_r. */
  val AvgTolerance: Double = 1e-6

  given Contract[ResearcherMetricsV2] = V.contract(m =>
    val p                                                 = m.publicationCount
    def req(ok: Boolean, field: String, msg: String): V.R =
      if ok then V.ok else Left(InvalidField(field, msg))
    V.all(
      V.schema(m.schemaVersion),
      V.unit(Ids.checkResearcherId(m.researcherId)),
      V2Checks.window(m.windowStart, m.windowEnd),
      V.unit(Ids.token("policyVersion", m.policyVersion)),
      V2Checks.nonNegative("publicationCount", p),
      V2Checks.nonNegative("coauthorCount", m.coauthorCount),
      V2Checks.nonNegative("teamSizeTotal", m.teamSizeTotal),
      V2Checks.nonNegative("avgTeamSize", m.avgTeamSize),
      V2Checks.nonNegative("maxTeamSize", m.maxTeamSize.toLong),
      V2Checks.nonNegative("weightedDegree", m.weightedDegree),
      V2Checks.nonNegative("maxTeamWorkCount", m.maxTeamWorkCount.toLong),
      V2Checks.nonNegative("crossCommunityDegree", m.crossCommunityDegree),
      V.finite("participationCoefficient", m.participationCoefficient),
      req(
        m.participationCoefficient >= 0.0 && m.participationCoefficient <= 1.0,
        "participationCoefficient",
        "must be in [0, 1]",
      ),
      req(m.communitiesTouched >= 1, "communitiesTouched", "must be >= 1"),
      req(m.maxTeamSize.toLong <= m.teamSizeTotal, "maxTeamSize", "must be <= teamSizeTotal"),
      req(
        p == 0L || m.teamSizeTotal >= p,
        "teamSizeTotal",
        "must be >= publicationCount (every team includes the researcher)",
      ),
      req(
        p == 0L || (m.maxTeamSize >= 1 && m.avgTeamSize <= m.maxTeamSize.toDouble + AvgTolerance),
        "avgTeamSize",
        "must be <= maxTeamSize, and maxTeamSize >= 1, when publicationCount >= 1",
      ),
      req(
        math.abs(m.avgTeamSize - (if p == 0L then 0.0 else m.teamSizeTotal.toDouble / p)) <=
          AvgTolerance,
        "avgTeamSize",
        "must equal teamSizeTotal / publicationCount (0 when no publications)",
      ),
      req(m.weightedDegree >= m.coauthorCount, "weightedDegree", "must be >= coauthorCount"),
      req(
        m.crossCommunityDegree <= m.coauthorCount,
        "crossCommunityDegree",
        "must be <= coauthorCount",
      ),
      req(
        (m.crossCommunityDegree == 0L) == (m.communitiesTouched == 1),
        "communitiesTouched",
        "must be 1 exactly when crossCommunityDegree is 0",
      ),
      req(
        m.communitiesTouched.toLong <= m.crossCommunityDegree + 1L,
        "communitiesTouched",
        "must be <= crossCommunityDegree + 1",
      ),
      req(
        m.communitiesTouched != 1 || m.participationCoefficient == 0.0,
        "participationCoefficient",
        "must be 0 when communitiesTouched is 1",
      ),
      req(m.maxTeamWorkCount.toLong <= p, "maxTeamWorkCount", "must be <= publicationCount"),
      req(
        m.maxTeamWorkIds.size <= m.maxTeamWorkCount,
        "maxTeamWorkIds",
        "must not exceed maxTeamWorkCount",
      ),
      V.each(m.maxTeamWorkIds)(w => V.unit(Ids.checkWorkId(w))),
      V.distinct("maxTeamWorkIds", m.maxTeamWorkIds),
      V.unit(Ids.checkEntityRef("community", m.communityId)),
      V.unit(Ids.checkEntityRef("component", m.componentId)),
      m.coreness.fold(V.ok)(c =>
        req(
          c >= 0 && c.toLong <= m.coauthorCount,
          "coreness",
          "must be in [0, coauthorCount]",
        )
      ),
      m.tieStrengthWeightedDegree.fold(V.ok)(t =>
        V2Checks.nonNegative("tieStrengthWeightedDegree", t)
      ),
      V.prov(m.provenance),
    )
  )
end ResearcherMetricsV2

/** Graph edge type names used by the Phase 06 export (`GraphEdge.edgeType` is a free string; these
  * are the values Phase 06 relies on).
  *
  * `RESOLVES_TO`: directed edge from the canonical researcher (`researcher:sc1:` DBLP-based ID, see
  * `Ids.researcherIdForClusterV2`) to the ORCID reference `researcher:orcid:<orcid>`
  * (`Ids.researcherIdFromOrcid`). The ORCID is never the canonical ID of a cluster; it is also kept
  * as a property of the researcher.
  */
object GraphEdgeTypes:
  val ResolvesTo: String = "RESOLVES_TO"
