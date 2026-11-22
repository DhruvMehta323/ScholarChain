package scholarchain.core

import WirePickle.{*, given}
import ValidationError.*

/** Phase 06 graph-analytics contracts (all new, schema v1; nothing frozen is changed).
  *
  * Windows: every record carries an explicit `GraphWindow`. The all-time window is
  * `GraphWindows.AllTime` (`1000-9999`); there is no `Option` window and no null window.
  *
  * Identifier forms (validated as typed references, derivation is the producer's contract):
  *   - `componentId`: `component:<non-whitespace>`. Spark derives `component:<window
  *     label>:<smallest member researcher ID>` (the window is embedded so IDs are unique across
  *     windows).
  *   - `communityId`: `community:<non-whitespace>`. Spark derives `community:<window
  *     label>:<smallest member researcher ID>` of the label-propagation community.
  *
  * Phase 06 repair 01 adds `CoauthorEdgeV2` and `ResearcherMetricsV2` (`AnalyticsRecordsV2.scala`).
  */
private[core] object AnalyticsChecks:
  import V.*

  def window(w: GraphWindow): R = unit(GraphWindow.validate(w))

  def parameters(field: String, p: Map[String, String]): R =
    each(p.keys)(k => nonBlank(s"$field.key", k))

  def researchers(field: String, ids: Seq[String]): R =
    each(ids)(i => unit(Ids.checkResearcherId(i)))

/** Closed set of bridge measures. Wire names are frozen. */
enum BridgeMeasure(val wire: String):
  /** Number of edges from the researcher to members of other communities. */
  case CrossCommunityDegree extends BridgeMeasure("cross_community_degree")

  /** Participation coefficient, 1 - sum_c (k_ic / k_i)^2, in [0, 1). */
  case ParticipationCoefficient extends BridgeMeasure("participation_coefficient")

  /** Number of distinct communities the researcher touches (own included). */
  case DistinctCommunities extends BridgeMeasure("distinct_communities")

  /** Optional sampled-source approximate betweenness. */
  case ApproxBetweenness extends BridgeMeasure("approx_betweenness")

object BridgeMeasure:
  given WirePickle.ReadWriter[BridgeMeasure] =
    Wire.enumRw("BridgeMeasure", values.toSeq, _.wire)

/** Whether the M5 cannot-link rule blocked a merge of this identity. */
enum CannotLinkStatus(val wire: String):
  /** No cannot-link constraint applies (singleton or a legitimate merge). */
  case Clear extends CannotLinkStatus("clear")

  /** The accepted-link component contained a REJECTED or CONFLICT pair, or two different ORCIDs, so
    * it was not merged and this identity is a base identity.
    */
  case Blocked extends CannotLinkStatus("blocked")

object CannotLinkStatus:
  given WirePickle.ReadWriter[CannotLinkStatus] =
    Wire.enumRw("CannotLinkStatus", values.toSeq, _.wire)

/** One connected component of the coauthorship graph in one window. `size >= 1`. */
final case class ComponentRecord(
    schemaVersion: Int,
    componentId: String,
    size: Long,
    window: GraphWindow,
    provenance: Provenance,
) derives ReadWriter

object ComponentRecord:
  given Contract[ComponentRecord] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkEntityRef("component", c.componentId)),
      if c.size >= 1L then V.ok else Left(InvalidField("size", "must be >= 1")),
      AnalyticsChecks.window(c.window),
      V.prov(c.provenance),
    )
  )

/** One community in one window. `method` is a token naming the algorithm and its version (for
  * example `label_propagation.v1`); `parameters` records its settings (iteration cap, ...).
  */
final case class CommunityRecord(
    schemaVersion: Int,
    communityId: String,
    size: Long,
    method: String,
    parameters: Map[String, String],
    window: GraphWindow,
    provenance: Provenance,
) derives ReadWriter

object CommunityRecord:
  given Contract[CommunityRecord] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkEntityRef("community", c.communityId)),
      if c.size >= 1L then V.ok else Left(InvalidField("size", "must be >= 1")),
      V.unit(Ids.token("method", c.method)),
      AnalyticsChecks.parameters("parameters", c.parameters),
      AnalyticsChecks.window(c.window),
      V.prov(c.provenance),
    )
  )

/** One bridge measure of one researcher in one window. `value` is finite and >= 0. */
final case class BridgeRecord(
    schemaVersion: Int,
    researcherId: String,
    measure: BridgeMeasure,
    value: Double,
    parameters: Map[String, String],
    window: GraphWindow,
    provenance: Provenance,
) derives ReadWriter

object BridgeRecord:
  given Contract[BridgeRecord] = V.contract(b =>
    V.all(
      V.schema(b.schemaVersion),
      V.unit(Ids.checkResearcherId(b.researcherId)),
      V.finite("value", b.value),
      if b.value >= 0.0 then V.ok else Left(InvalidField("value", "must be >= 0")),
      AnalyticsChecks.parameters("parameters", b.parameters),
      AnalyticsChecks.window(b.window),
      V.prov(b.provenance),
    )
  )

/** A shortest collaboration path. `hops` are researcher IDs from `start` to `end` (distinct, so a
  * simple path); `length = hops.size - 1 >= 1`. `edgeIds(i)` is the coauthor edge
  * (`edge:coauthor:`) between `hops(i)` and `hops(i+1)` and `supportingWorkIds(i)` the (non-empty,
  * distinct) works supporting that edge, so both lists have `length` entries.
  */
final case class PathEvidence(
    schemaVersion: Int,
    start: String,
    end: String,
    hops: List[String],
    edgeIds: List[String],
    supportingWorkIds: List[List[String]],
    length: Int,
    window: GraphWindow,
    provenance: Provenance,
) derives ReadWriter

object PathEvidence:
  given Contract[PathEvidence] = V.contract(p =>
    V.all(
      V.schema(p.schemaVersion),
      V.unit(Ids.checkResearcherId(p.start)),
      V.unit(Ids.checkResearcherId(p.end)),
      AnalyticsChecks.researchers("hops", p.hops),
      V.distinct("hops", p.hops),
      if p.hops.headOption.contains(p.start) then V.ok
      else Left(InvalidField("hops", "must begin with start")),
      if p.hops.lastOption.contains(p.end) then V.ok
      else Left(InvalidField("hops", "must end with end")),
      if p.length == p.hops.size - 1 then V.ok
      else Left(InvalidField("length", "must equal hops.size - 1")),
      V.atLeast("length", p.length, 1),
      if p.edgeIds.size == p.length then V.ok
      else Left(InvalidField("edgeIds", "must have one entry per hop (length)")),
      V.each(p.edgeIds)(e => V.unit(Ids.checkCoauthorEdgeId(e))),
      if p.supportingWorkIds.size == p.length then V.ok
      else Left(InvalidField("supportingWorkIds", "must have one entry per hop (length)")),
      V.each(p.supportingWorkIds)(ws =>
        V.all(
          V.nonEmpty("supportingWorkIds", ws),
          V.each(ws)(w => V.unit(Ids.checkWorkId(w))),
          V.distinct("supportingWorkIds", ws),
        )
      ),
      AnalyticsChecks.window(p.window),
      V.prov(p.provenance),
    )
  )

/** A deterministic, template-rendered text for one entity (researcher, community, path, topic).
  * `textHash` must equal `Hashing.textHash(text)`; `runId` is a run ID (`run:<64 hex>`).
  */
final case class SemanticSummary(
    schemaVersion: Int,
    entityId: String,
    documentType: String,
    textHash: String,
    text: String,
    window: GraphWindow,
    runId: String,
    provenance: Provenance,
) derives ReadWriter

object SemanticSummary:
  given Contract[SemanticSummary] = V.contract(s =>
    V.all(
      V.schema(s.schemaVersion),
      V.unit(Ids.checkAnyRef(s.entityId)),
      V.unit(Ids.token("documentType", s.documentType)),
      V.nonBlank("text", s.text),
      if Hashing.textHash(s.text) == s.textHash then V.ok
      else Left(InvalidField("textHash", "must equal the SHA-256 of text")),
      AnalyticsChecks.window(s.window),
      V.unit(Ids.checkRunId(s.runId)),
      V.prov(s.provenance),
    )
  )

/** A canonical researcher identity and the author mentions it covers.
  *   - `researcherId`: see `Ids.researcherIdDblpPerson`, `Ids.researcherIdMergedCluster`,
  *     `Ids.researcherIdForCluster`. The last two are superseded for Phase 06 output by
  *     `Ids.researcherIdForClusterV2`: canonical ID = DBLP base ID of the smallest member DBLP
  *     person key; an ORCID is a property plus a `RESOLVES_TO` edge (`GraphEdgeTypes.ResolvesTo`).
  *   - `memberMentionIds`: non-empty, strictly ascending (hence distinct) mention IDs.
  *   - `ruleFired`: token naming the rule that formed the cluster (for example `dblp_person_exact`,
  *     `accepted_link_component`, `cannot_link_fallback`).
  *   - `cannotLinkStatus`: `blocked` only when M5 refused a merge and this is the fallback base
  *     identity.
  *   - `policyVersion`: the match policy the decisions came from.
  */
final case class IdentityCluster(
    schemaVersion: Int,
    researcherId: String,
    memberMentionIds: List[String],
    ruleFired: String,
    cannotLinkStatus: CannotLinkStatus,
    policyVersion: String,
    provenance: Provenance,
) derives ReadWriter

object IdentityCluster:
  given Contract[IdentityCluster] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkResearcherId(c.researcherId)),
      V.nonEmpty("memberMentionIds", c.memberMentionIds),
      V.each(c.memberMentionIds)(m => V.unit(Ids.checkMentionId(m))),
      if c.memberMentionIds.zip(c.memberMentionIds.drop(1)).forall((a, b) => a.compareTo(b) < 0)
      then V.ok
      else Left(InvalidField("memberMentionIds", "must be strictly ascending")),
      V.unit(Ids.token("ruleFired", c.ruleFired)),
      V.unit(Ids.token("policyVersion", c.policyVersion)),
      V.prov(c.provenance),
    )
  )
