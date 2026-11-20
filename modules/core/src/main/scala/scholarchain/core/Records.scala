package scholarchain.core

import java.time.Instant

import scala.util.Try

import WirePickle.{*, given}
import ValidationError.*

/** Internal validation combinators. Total: every check returns an Either, nothing throws. */
private[core] object V:
  type R = Either[ValidationError, Unit]
  val ok: R = Right(())

  /** First failure wins, in argument order. */
  def all(rs: R*): R = rs.collectFirst { case Left(e) => Left(e) }.getOrElse(ok)

  def unit[A](e: Either[ValidationError, A]): R = e.map(_ => ())

  def each[A](xs: Iterable[A])(f: A => R): R =
    xs.foldLeft(ok)((acc, x) => acc.flatMap(_ => f(x)))

  def schema(v: Int): R = unit(SchemaVersion.check(v))

  def confidence(field: String, d: Double): R =
    if !d.isNaN && d >= 0.0 && d <= 1.0 then ok else Left(InvalidConfidence(field, d))

  def finite(field: String, d: Double): R =
    if d.isNaN || d.isInfinite then Left(InvalidField(field, "must be a finite number")) else ok

  def nonBlank(field: String, s: String): R =
    if s.trim.nonEmpty then ok else Left(InvalidField(field, "must be non-blank"))

  def atLeast(field: String, v: Int, min: Int): R =
    if v >= min then ok else Left(InvalidField(field, s"must be >= $min"))

  def year(y: Int): R = unit(PublicationYear.validate(y))

  def prov(p: Provenance): R = unit(summon[Contract[Provenance]].validate(p))

  def distinct(field: String, xs: Seq[String]): R =
    if xs.distinct.size == xs.size then ok
    else Left(InvalidField(field, "must not contain duplicates"))

  def nonEmpty(field: String, xs: Seq[?]): R =
    if xs.nonEmpty then ok else Left(InvalidField(field, "must not be empty"))

  def orcidStored(field: String, o: Option[String]): R = o match
    case None    => ok
    case Some(v) =>
      Orcid.parse(v) match
        case Right(n) if n == v => ok
        case Right(_)           => Left(InvalidField(field, "must be stored in canonical form"))
        case Left(e)            => Left(e)

  def matches(field: String, actual: Either[ValidationError, String], stored: String): R =
    actual.flatMap(a =>
      if a == stored then ok
      else Left(InvalidField(field, "does not match the value derived from its inputs"))
    )

  def contract[A](f: A => R): Contract[A] = a => f(a).map(_ => a)

/** One input of a data transformation: identifier, version and (when known) SHA-256 checksum. */
final case class InputRef(inputId: String, version: String, checksum: Option[String] = None)
    derives ReadWriter

object InputRef:
  def validate(i: InputRef): Either[ValidationError, InputRef] =
    V.all(
      V.nonBlank("inputs.inputId", i.inputId),
      V.nonBlank("inputs.version", i.version),
      i.checksum match
        case Some(c) if !Hashing.isSha256Hex(c) =>
          Left(InvalidField("inputs.checksum", "must be 64 lowercase hex characters (SHA-256)"))
        case _ => V.ok,
    ).map(_ => i)

/** Provenance: what the record was derived from (inputs with versions and checksums), the code
  * version, the parameters, and optionally the run and retrieval time (ISO-8601 instant).
  */
final case class Provenance(
    schemaVersion: Int,
    sourceType: SourceType,
    inputs: List[InputRef],
    codeVersion: String,
    parameters: Map[String, String],
    runId: Option[String] = None,
    retrievedAt: Option[String] = None,
) derives ReadWriter

object Provenance:
  given Contract[Provenance] = V.contract(p =>
    V.all(
      V.schema(p.schemaVersion),
      V.nonEmpty("provenance.inputs", p.inputs),
      V.each(p.inputs)(i => V.unit(InputRef.validate(i))),
      V.nonBlank("provenance.codeVersion", p.codeVersion),
      V.each(p.parameters.keys)(k => V.nonBlank("provenance.parameters.key", k)),
      p.runId.fold(V.ok)(r => V.unit(Ids.checkRunId(r))),
      p.retrievedAt.fold(V.ok)(t =>
        if Try(Instant.parse(t)).isSuccess then V.ok
        else Left(InvalidField("provenance.retrievedAt", "must be an ISO-8601 instant"))
      ),
    )
  )

/** Immutable run manifest. `runId` is derived from the manifest content (see `Ids.runId`), not from
  * a clock; `createdAt` is informational and excluded from the ID.
  */
final case class RunManifest(
    schemaVersion: Int,
    runId: String,
    stage: String,
    codeVersion: String,
    attempt: Int,
    parameters: Map[String, String],
    inputs: List[InputRef],
    createdAt: Option[String] = None,
) derives ReadWriter

object RunManifest:
  def build(
      stage: String,
      codeVersion: String,
      attempt: Int,
      parameters: Map[String, String],
      inputs: List[InputRef],
      createdAt: Option[String] = None,
  ): Either[ValidationError, RunManifest] =
    Ids
      .runId(stage, codeVersion, attempt, parameters, inputs)
      .flatMap(id =>
        summon[Contract[RunManifest]].validate(
          RunManifest(
            SchemaVersion.Current,
            id,
            stage,
            codeVersion,
            attempt,
            parameters,
            inputs,
            createdAt,
          )
        )
      )

  given Contract[RunManifest] = V.contract(m =>
    V.all(
      V.schema(m.schemaVersion),
      V.nonEmpty("inputs", m.inputs),
      V.each(m.inputs)(i => V.unit(InputRef.validate(i))),
      V.matches(
        "runId",
        Ids.runId(m.stage, m.codeVersion, m.attempt, m.parameters, m.inputs),
        m.runId,
      ),
      m.createdAt.fold(V.ok)(t =>
        if Try(Instant.parse(t)).isSuccess then V.ok
        else Left(InvalidField("createdAt", "must be an ISO-8601 instant"))
      ),
    )
  )

final case class Venue(
    schemaVersion: Int,
    venueId: String,
    name: String,
    venueType: Option[String] = None,
    provenance: Provenance,
) derives ReadWriter

object Venue:
  given Contract[Venue] = V.contract(v =>
    V.all(
      V.schema(v.schemaVersion),
      V.unit(Ids.checkEntityRef("venue", v.venueId)),
      V.nonBlank("name", v.name),
      V.prov(v.provenance),
    )
  )

final case class Institution(
    schemaVersion: Int,
    institutionId: String,
    name: String,
    country: Option[String] = None,
    provenance: Provenance,
) derives ReadWriter

object Institution:
  given Contract[Institution] = V.contract(i =>
    V.all(
      V.schema(i.schemaVersion),
      V.unit(Ids.checkEntityRef("institution", i.institutionId)),
      V.nonBlank("name", i.name),
      i.country.fold(V.ok)(c =>
        if c.matches("^[A-Z]{2}$") then V.ok
        else Left(InvalidField("country", "must be an ISO 3166-1 alpha-2 code in uppercase"))
      ),
      V.prov(i.provenance),
    )
  )

final case class Topic(
    schemaVersion: Int,
    topicId: String,
    label: String,
    provenance: Provenance,
) derives ReadWriter

object Topic:
  given Contract[Topic] = V.contract(t =>
    V.all(
      V.schema(t.schemaVersion),
      V.unit(Ids.checkEntityRef("topic", t.topicId)),
      V.nonBlank("label", t.label),
      V.prov(t.provenance),
    )
  )

final case class WorkRecord(
    schemaVersion: Int,
    workId: String,
    title: String,
    year: Int,
    doi: Option[String] = None,
    venueId: Option[String] = None,
    topicIds: List[String],
    provenance: Provenance,
) derives ReadWriter

object WorkRecord:
  given Contract[WorkRecord] = V.contract(w =>
    V.all(
      V.schema(w.schemaVersion),
      V.unit(Ids.checkWorkId(w.workId)),
      V.nonBlank("title", w.title),
      V.year(w.year),
      w.doi.fold(V.ok)(d =>
        Doi.normalize(d) match
          case Right(n) if n == d => V.ok
          case Right(_)           => Left(InvalidField("doi", "must be stored in normalized form"))
          case Left(e)            => Left(e)
      ),
      w.venueId.fold(V.ok)(v => V.unit(Ids.checkEntityRef("venue", v))),
      V.each(w.topicIds)(t => V.unit(Ids.checkEntityRef("topic", t))),
      V.prov(w.provenance),
    )
  )

final case class AuthorMention(
    schemaVersion: Int,
    mentionId: String,
    workId: String,
    position: Int,
    rawName: String,
    orcid: Option[String] = None,
    provenance: Provenance,
) derives ReadWriter

object AuthorMention:
  given Contract[AuthorMention] = V.contract(m =>
    V.all(
      V.schema(m.schemaVersion),
      V.unit(Ids.checkWorkId(m.workId)),
      V.atLeast("position", m.position, 0),
      V.matches("mentionId", Ids.mentionId(m.workId, m.position), m.mentionId),
      V.nonBlank("rawName", m.rawName),
      V.orcidStored("orcid", m.orcid),
      V.prov(m.provenance),
    )
  )

/** A canonical researcher. Identity uncertainty is explicit: `matchConfidence` in [0,1] and
  * `matchOutcome`. An ORCID-namespaced ID must agree with the `orcid` field.
  */
final case class CanonicalResearcher(
    schemaVersion: Int,
    researcherId: String,
    displayName: String,
    orcid: Option[String] = None,
    matchConfidence: Double,
    matchOutcome: MatchOutcome,
    provenance: Provenance,
) derives ReadWriter

object CanonicalResearcher:
  given Contract[CanonicalResearcher] = V.contract(r =>
    V.all(
      V.schema(r.schemaVersion),
      V.unit(Ids.checkResearcherId(r.researcherId)),
      V.nonBlank("displayName", r.displayName),
      V.orcidStored("orcid", r.orcid),
      if r.researcherId.startsWith("researcher:orcid:") &&
        !r.orcid.contains(r.researcherId.stripPrefix("researcher:orcid:"))
      then
        Left(InvalidField("orcid", "must equal the ORCID inside an ORCID-namespaced researcherId"))
      else V.ok,
      V.confidence("matchConfidence", r.matchConfidence),
      V.prov(r.provenance),
    )
  )

final case class AuthorshipRecord(
    schemaVersion: Int,
    workId: String,
    researcherId: String,
    mentionId: String,
    position: Int,
    identityConfidence: Double,
    identityOutcome: MatchOutcome,
    provenance: Provenance,
) derives ReadWriter

object AuthorshipRecord:
  given Contract[AuthorshipRecord] = V.contract(a =>
    V.all(
      V.schema(a.schemaVersion),
      V.unit(Ids.checkWorkId(a.workId)),
      V.unit(Ids.checkResearcherId(a.researcherId)),
      V.unit(Ids.checkMentionId(a.mentionId)),
      V.atLeast("position", a.position, 0),
      V.confidence("identityConfidence", a.identityConfidence),
      V.prov(a.provenance),
    )
  )

/** The researchers of one work: at least two, strictly ascending (hence distinct). */
final case class PublicationTeam(
    schemaVersion: Int,
    workId: String,
    year: Int,
    researcherIds: List[String],
    provenance: Provenance,
) derives ReadWriter

object PublicationTeam:
  given Contract[PublicationTeam] = V.contract(t =>
    V.all(
      V.schema(t.schemaVersion),
      V.unit(Ids.checkWorkId(t.workId)),
      V.year(t.year),
      V.atLeast("researcherIds.size", t.researcherIds.size, 2),
      V.each(t.researcherIds)(r => V.unit(Ids.checkResearcherId(r))),
      if t.researcherIds.zip(t.researcherIds.drop(1)).forall((a, b) => a.compareTo(b) < 0) then V.ok
      else Left(InvalidField("researcherIds", "must be strictly ascending")),
      V.prov(t.provenance),
    )
  )

final case class CoauthorPairCandidate(
    schemaVersion: Int,
    workId: String,
    year: Int,
    pair: OrderedPair,
    provenance: Provenance,
) derives ReadWriter

object CoauthorPairCandidate:
  given Contract[CoauthorPairCandidate] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkWorkId(c.workId)),
      V.year(c.year),
      V.unit(OrderedPair.validate(c.pair)),
      V.prov(c.provenance),
    )
  )

final case class CoauthorEdge(
    schemaVersion: Int,
    edgeId: String,
    pair: OrderedPair,
    window: GraphWindow,
    policyVersion: String,
    workCount: Int,
    supportingWorkIds: List[String],
    provenance: Provenance,
) derives ReadWriter

object CoauthorEdge:
  given Contract[CoauthorEdge] = V.contract(e =>
    V.all(
      V.schema(e.schemaVersion),
      V.unit(OrderedPair.validate(e.pair)),
      V.unit(GraphWindow.validate(e.window)),
      V.unit(Ids.token("policyVersion", e.policyVersion)),
      V.matches(
        "edgeId",
        Ids.coauthorEdgeId(e.pair.first, e.pair.second, e.window, e.policyVersion),
        e.edgeId,
      ),
      V.nonEmpty("supportingWorkIds", e.supportingWorkIds),
      V.each(e.supportingWorkIds)(w => V.unit(Ids.checkWorkId(w))),
      V.distinct("supportingWorkIds", e.supportingWorkIds),
      if e.workCount == e.supportingWorkIds.size then V.ok
      else Left(InvalidField("workCount", "must equal the number of supportingWorkIds")),
      V.prov(e.provenance),
    )
  )

final case class MetricValue(metric: CollaborationMetric, value: Double) derives ReadWriter

final case class ResearcherMetrics(
    schemaVersion: Int,
    researcherId: String,
    window: GraphWindow,
    policyVersion: String,
    metrics: List[MetricValue],
    provenance: Provenance,
) derives ReadWriter

object ResearcherMetrics:
  given Contract[ResearcherMetrics] = V.contract(m =>
    V.all(
      V.schema(m.schemaVersion),
      V.unit(Ids.checkResearcherId(m.researcherId)),
      V.unit(GraphWindow.validate(m.window)),
      V.unit(Ids.token("policyVersion", m.policyVersion)),
      V.nonEmpty("metrics", m.metrics),
      V.each(m.metrics)(mv => V.finite("metrics.value", mv.value)),
      V.distinct("metrics", m.metrics.map(_.metric.wire)),
      V.prov(m.provenance),
    )
  )

final case class MatchDecision(
    schemaVersion: Int,
    leftId: String,
    rightId: String,
    outcome: MatchOutcome,
    confidence: Double,
    policyVersion: String,
    features: Map[String, Double],
    provenance: Provenance,
) derives ReadWriter

object MatchDecision:
  given Contract[MatchDecision] = V.contract(d =>
    V.all(
      V.schema(d.schemaVersion),
      V.unit(Ids.checkAnyRef(d.leftId)),
      V.unit(Ids.checkAnyRef(d.rightId)),
      if d.leftId == d.rightId then Left(InvalidPair(d.leftId, d.rightId, "self-pair")) else V.ok,
      V.confidence("confidence", d.confidence),
      V.unit(Ids.token("policyVersion", d.policyVersion)),
      V.each(d.features)((k, v) => V.finite(s"features.$k", v)),
      V.prov(d.provenance),
    )
  )

final case class GraphNode(
    schemaVersion: Int,
    nodeId: String,
    nodeType: String,
    label: String,
    properties: Map[String, String],
    provenance: Provenance,
) derives ReadWriter

object GraphNode:
  given Contract[GraphNode] = V.contract(n =>
    V.all(
      V.schema(n.schemaVersion),
      V.unit(Ids.checkAnyRef(n.nodeId)),
      V.unit(Ids.token("nodeType", n.nodeType)),
      V.nonBlank("label", n.label),
      V.prov(n.provenance),
    )
  )

/** A graph edge. Undirected edges must have `sourceId` < `targetId`; self-loops are rejected. */
final case class GraphEdge(
    schemaVersion: Int,
    edgeId: String,
    edgeType: String,
    sourceId: String,
    targetId: String,
    directed: Boolean,
    policyVersion: String,
    evidenceIds: List[String],
    provenance: Provenance,
) derives ReadWriter

object GraphEdge:
  given Contract[GraphEdge] = V.contract(e =>
    V.all(
      V.schema(e.schemaVersion),
      V.unit(Ids.checkAnyRef(e.sourceId)),
      V.unit(Ids.checkAnyRef(e.targetId)),
      if e.sourceId == e.targetId then Left(InvalidPair(e.sourceId, e.targetId, "self-pair"))
      else if !e.directed && e.sourceId.compareTo(e.targetId) > 0 then
        Left(InvalidPair(e.sourceId, e.targetId, "undirected endpoints not in canonical order"))
      else V.ok,
      V.nonEmpty("evidenceIds", e.evidenceIds),
      V.matches(
        "edgeId",
        Ids.graphEdgeId(
          e.edgeType,
          e.sourceId,
          e.targetId,
          e.directed,
          e.policyVersion,
          e.evidenceIds,
        ),
        e.edgeId,
      ),
      V.prov(e.provenance),
    )
  )

final case class EvidenceItem(
    schemaVersion: Int,
    evidenceId: String,
    evidenceType: EvidenceType,
    sourceEntityId: String,
    sourceVersion: String,
    text: Option[String] = None,
    contentHash: String,
    score: Option[Double] = None,
    provenance: Provenance,
) derives ReadWriter

object EvidenceItem:
  given Contract[EvidenceItem] = V.contract(i =>
    V.all(
      V.schema(i.schemaVersion),
      V.matches(
        "evidenceId",
        Ids.evidenceId(i.evidenceType, i.sourceEntityId, i.sourceVersion, i.contentHash),
        i.evidenceId,
      ),
      i.text.fold(V.ok)(t =>
        if Hashing.textHash(t) == i.contentHash then V.ok
        else Left(InvalidField("contentHash", "must equal the SHA-256 of text"))
      ),
      i.score.fold(V.ok)(s => V.finite("score", s)),
      V.prov(i.provenance),
    )
  )

final case class EvidenceBundle(
    schemaVersion: Int,
    queryId: String,
    items: List[EvidenceItem],
    provenance: Provenance,
) derives ReadWriter

object EvidenceBundle:
  given Contract[EvidenceBundle] = V.contract(b =>
    V.all(
      V.schema(b.schemaVersion),
      V.unit(Ids.checkEntityRef("query", b.queryId)),
      V.each(b.items)(i => V.unit(summon[Contract[EvidenceItem]].validate(i))),
      V.distinct("items.evidenceId", b.items.map(_.evidenceId)),
      V.prov(b.provenance),
    )
  )

final case class QueryPlan(
    schemaVersion: Int,
    queryId: String,
    operation: QueryOperation,
    question: String,
    subjectIds: List[String],
    window: Option[GraphWindow] = None,
    metric: Option[CollaborationMetric] = None,
    limit: Int,
    provenance: Provenance,
) derives ReadWriter

object QueryPlan:
  given Contract[QueryPlan] = V.contract(q =>
    V.all(
      V.schema(q.schemaVersion),
      V.unit(Ids.checkEntityRef("query", q.queryId)),
      V.nonBlank("question", q.question),
      V.each(q.subjectIds)(s => V.unit(Ids.checkAnyRef(s))),
      q.window.fold(V.ok)(w => V.unit(GraphWindow.validate(w))),
      V.atLeast("limit", q.limit, 1),
      subjects(q),
      V.prov(q.provenance),
    )
  )

  private def subjects(q: QueryPlan): V.R =
    def need(n: Int) =
      if q.subjectIds.size == n then V.ok
      else Left(InvalidField("subjectIds", s"${q.operation.wire} needs exactly $n subject(s)"))
    q.operation match
      case QueryOperation.LookupResearcher | QueryOperation.ListCoauthors => need(1)
      case QueryOperation.CollaborationPath                               =>
        V.all(need(2), V.distinct("subjectIds", q.subjectIds))
      case QueryOperation.CollaborationTrend =>
        V.all(
          need(1),
          if q.window.isDefined then V.ok else Left(MissingField("window")),
        )
      case _ => V.ok

final case class AnswerClaim(
    schemaVersion: Int,
    claimId: String,
    text: String,
    evidenceIds: List[String],
    status: VerificationStatus,
    confidence: Double,
    provenance: Provenance,
) derives ReadWriter

object AnswerClaim:
  given Contract[AnswerClaim] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkEntityRef("claim", c.claimId)),
      V.nonBlank("text", c.text),
      V.each(c.evidenceIds)(e => V.unit(Ids.checkEvidenceId(e))),
      c.status match
        case VerificationStatus.Supported | VerificationStatus.PartiallySupported =>
          V.nonEmpty("evidenceIds", c.evidenceIds)
        case _ => V.ok,
      V.confidence("confidence", c.confidence),
      V.prov(c.provenance),
    )
  )

final case class AnswerResponse(
    schemaVersion: Int,
    queryId: String,
    answer: String,
    claims: List[AnswerClaim],
    bundle: EvidenceBundle,
    provenance: Provenance,
) derives ReadWriter

object AnswerResponse:
  given Contract[AnswerResponse] = V.contract(r =>
    V.all(
      V.schema(r.schemaVersion),
      V.unit(Ids.checkEntityRef("query", r.queryId)),
      V.nonBlank("answer", r.answer),
      V.unit(summon[Contract[EvidenceBundle]].validate(r.bundle)),
      if r.bundle.queryId == r.queryId then V.ok
      else Left(InvalidField("bundle.queryId", "must equal queryId")),
      V.each(r.claims)(c => V.unit(summon[Contract[AnswerClaim]].validate(c))),
      V.distinct("claims.claimId", r.claims.map(_.claimId)),
      V.each(r.claims.flatMap(_.evidenceIds)) { e =>
        if r.bundle.items.exists(_.evidenceId == e) then V.ok
        else
          Left(InvalidField("claims.evidenceIds", "claim cites evidence that is not in the bundle"))
      },
      V.prov(r.provenance),
    )
  )
