package scholarchain.rag.runtime

import java.text.Normalizer
import java.util.Locale

import scholarchain.core.*

/** Whether a name or ID was resolved to exactly one researcher. */
enum ResolutionStatus(val wire: String):
  case Resolved   extends ResolutionStatus("resolved")
  case Ambiguous  extends ResolutionStatus("ambiguous")
  case Unresolved extends ResolutionStatus("unresolved")

/** The outcome for one subject position of the plan. `chosen` is set only for `Resolved`. For
  * `Ambiguous` and `Unresolved` the candidates (possibly none) are listed with their confidence;
  * the workflow abstains and the answer lists them. `reasonCodes` use `[a-z][a-z0-9_]*`.
  */
final case class NameResolution(
    position: Int,
    queriedName: Option[String],
    queriedId: Option[String],
    status: ResolutionStatus,
    chosen: Option[ResolvedResearcherCandidate],
    candidates: List[ResolvedResearcherCandidate],
    reasonCodes: List[String],
)

final case class ResolutionResult(entries: List[NameResolution], evidence: StageOutput):
  def allResolved: Boolean =
    entries.nonEmpty && entries.forall(_.status == ResolutionStatus.Resolved)
  def subjectIds: List[String]             = entries.flatMap(_.chosen.map(_.researcherId))
  def abstainEntries: List[NameResolution] = entries.filter(_.status != ResolutionStatus.Resolved)

/** Name normalization used to compare a user's name with stored names: NFKC, lower case, blanks
  * collapsed. A DBLP homonym suffix is a trailing four-digit number (`Yu Liu 0004`).
  */
object NameKey:
  private val Suffix = """ \d{4}$""".r

  def normalize(s: String): String =
    Normalizer
      .normalize(s, Normalizer.Form.NFKC)
      .toLowerCase(Locale.ROOT)
      .replaceAll("\\s+", " ")
      .trim

  def hasSuffix(normalized: String): Boolean = Suffix.findFirstIn(normalized).isDefined

  def stripSuffix(normalized: String): String = Suffix.replaceFirstIn(normalized, "")

/** Stage 2: resolves researcher names (and IDs) to canonical researcher IDs.
  *
  * Name lookup has no name-keyed table, so candidates come from the `researcher-profile-v1` vector
  * index (the name is embedded and the top hits for the all-time window are taken), and every
  * candidate is then VERIFIED deterministically against the stored tables: the `identity-clusters`
  * item (display name, DBLP person keys, rule fired, cannot-link status), the `mention-researcher`
  * items of the cluster's mentions (the mention must map back to the cluster) and the
  * `researcher-metrics` all-time item (is the researcher in the graph at all). A vector hit that
  * does not match the name in the identity table is dropped; similarity never decides identity.
  *
  * Rules (deterministic, `ResolveThreshold` = 0.8):
  *   - a plain name matches clusters whose display name or DBLP key equals it (exact) and clusters
  *     whose key equals it once a homonym suffix is removed (suffix-only); a suffixed name matches
  *     exact keys only (homonym suffixes are respected);
  *   - more than one matching cluster is ambiguous: every candidate is returned and the stage does
  *     not choose;
  *   - a single exact match is resolved only when its confidence is at least the threshold and it
  *     is in the graph; a single suffix-only match (the user omitted a homonym suffix that DBLP
  *     uses) is never resolved: it is returned as a candidate and the stage abstains;
  *   - confidence: base 0.95 (`dblp_person_exact`), 0.85 (`accepted_link_component`), 0.40
  *     (`unresolved_singleton`), 0.50 (other); +0.04 when every checked mention maps back,
  *     otherwise -0.20; suffix-only x0.6; cannot-link status other than `clear` -0.30; at most 0.40
  *     when not in the graph; clamped to [0, 1].
  *   - a supplied researcher ID is verified the same way; a name and an ID that disagree are
  *     unresolved.
  *
  * Recall limit: only the top `VectorTopK` (100) hits are examined, so a name that more than 100
  * profile documents outrank may miss a candidate; that limit is reported in `RUNTIME-AGENTS.md`.
  */
final class ResearcherResolverAgent(
    reader: AnalyticsReader,
    vectors: VectorStore,
    embedder: Embedder,
):
  import ResearcherResolverAgent.*

  def resolve(plan: QueryPlanV2): ResolutionResult =
    val n       = plan.subjectCount
    val entries = (0 until n).toList.map { i =>
      resolveOne(i, plan.researcherNames.lift(i), plan.subjectIds.lift(i))
    }
    val drafts = entries.flatMap { e =>
      e.candidates.flatMap(c => identityDraft(c, e.status, e.candidates.size))
    }
    ResolutionResult(entries, StageOutput(drafts.distinct))

  private val items = scala.collection.mutable.Map.empty[String, Option[StoredItem]]

  private def cluster(id: String): Either[RuntimeError, Option[StoredItem]] =
    items.get(id) match
      case Some(v) => Right(v)
      case None    =>
        reader.get("identity-clusters", id).map { v =>
          items(id) = v
          v
        }

  private def identityDraft(
      c: ResolvedResearcherCandidate,
      status: ResolutionStatus,
      count: Int,
  ): Option[EvidenceDraft] =
    cluster(c.researcherId).toOption.flatten.map { item =>
      val a      = item.obj.value
      val fields = ujson.Obj(
        "displayName"      -> a.getOrElse("displayName", ujson.Null),
        "ruleFired"        -> a.getOrElse("ruleFired", ujson.Null),
        "memberCount"      -> a.getOrElse("memberCount", ujson.Null),
        "cannotLinkStatus" -> a.getOrElse("cannotLinkStatus", ujson.Null),
        "orcids"           -> a.getOrElse("orcids", ujson.Arr()),
        "dblpPersonKeys"   -> a.getOrElse("dblpPersonKeys", ujson.Arr()),
        "confidence"       -> ujson.Num(c.confidence),
        "resolutionStatus" -> ujson.Str(status.wire),
        "candidateCount"   -> ujson.Num(count.toDouble),
      )
      val policy = a.get("policyVersion").flatMap(_.strOpt)
      val fact   = Fact(Fact.Identity, c.researcherId, None, None, policy, fields)
      EvidenceDraft.fromFact(
        EvidenceType.ResearcherProfile,
        c.researcherId,
        policy.getOrElse("identity-clusters"),
        fact,
        List(EvidenceSupport.inputRef(item, policy)),
        Map("table" -> "identity-clusters", "evidenceUsed" -> c.evidenceUsed.mkString(",")),
      )
    }

  private def resolveOne(i: Int, name: Option[String], id: Option[String]): NameResolution =
    def entry(
        status: ResolutionStatus,
        chosen: Option[ResolvedResearcherCandidate],
        cands: List[ResolvedResearcherCandidate],
        codes: List[String],
    ) = NameResolution(i, name, id, status, chosen, cands, codes)

    id match
      case Some(rid) =>
        cluster(rid) match
          case Left(e)     => entry(ResolutionStatus.Unresolved, None, Nil, List(code(e)))
          case Right(None) => entry(ResolutionStatus.Unresolved, None, Nil, List("id_not_found"))
          case Right(Some(item)) =>
            val kind = name.map(nm => matchKind(nm, item.obj)).getOrElse(Some(MatchKind.Exact))
            kind match
              case None =>
                entry(
                  ResolutionStatus.Unresolved,
                  None,
                  candidate(item, MatchKind.Exact).toList,
                  List("name_id_mismatch"),
                )
              case Some(k) =>
                candidate(item, k) match
                  case None =>
                    entry(ResolutionStatus.Unresolved, None, Nil, List("candidate_invalid"))
                  case Some(c) => decide(entry, List(c), List("id_supplied"))
      case None =>
        name match
          case None     => entry(ResolutionStatus.Unresolved, None, Nil, List("no_name_or_id"))
          case Some(nm) =>
            nameHits(nm) match
              case Left(e) => entry(ResolutionStatus.Unresolved, None, Nil, List(code(e)))
              case Right((ids, saturated0, tail)) =>
                val found = ids.flatMap { rid =>
                  cluster(rid).toOption.flatten.flatMap(item =>
                    matchKind(nm, item.obj).flatMap(k => candidate(item, k))
                  )
                }
                // The query is truncated for this name when it returned a full top-K AND its
                // lowest-ranked hit still matches the name: more matches may rank beyond it. When
                // the lowest hit is not a match, the ranking has already left the name behind.
                val saturated = saturated0 && tail.exists(t => found.exists(_.researcherId == t))
                val sorted    =
                  found.distinctBy(_.researcherId).sortBy(c => (-c.confidence, c.researcherId))
                if sorted.isEmpty then
                  entry(ResolutionStatus.Unresolved, None, Nil, List("no_matching_researcher"))
                else if saturated && sorted.size == 1 then
                  // the vector query returned a full top-K: a homonym ranked past it may exist
                  entry(
                    ResolutionStatus.Ambiguous,
                    None,
                    sorted,
                    List("candidates_truncated"),
                  )
                else
                  decide(
                    entry,
                    sorted.take(MaxCandidates),
                    if sorted.size > MaxCandidates || saturated then List("candidates_truncated")
                    else Nil,
                  )

  private def decide(
      entry: (
          ResolutionStatus,
          Option[ResolvedResearcherCandidate],
          List[ResolvedResearcherCandidate],
          List[String],
      ) => NameResolution,
      cands: List[ResolvedResearcherCandidate],
      extra: List[String],
  ): NameResolution =
    cands match
      case one :: Nil =>
        val suffixOnly = one.evidenceUsed.contains("name-suffix-only")
        if suffixOnly then
          entry(ResolutionStatus.Ambiguous, None, cands, "homonym_suffix_required" :: extra)
        else if one.confidence >= ResolveThreshold && !one.evidenceUsed.contains("not-in-graph")
        then entry(ResolutionStatus.Resolved, Some(one), cands, extra)
        else entry(ResolutionStatus.Unresolved, None, cands, "low_confidence" :: extra)
      case _ => entry(ResolutionStatus.Ambiguous, None, cands, "multiple_candidates" :: extra)

  private def code(e: RuntimeError): String = e.code.replace('-', '_')

  /** Researcher IDs of the top vector hits for a name (all-time researcher profiles), and whether
    * the query returned a full top-K, and the entity of the lowest-ranked hit.
    */
  private def nameHits(
      name: String
  ): Either[RuntimeError, (List[String], Boolean, Option[String])] =
    embedder.embed(List(name)) match
      case Left(e)   => Left(RuntimeError("name-not-embeddable", e.code))
      case Right(vs) =>
        val q = VectorQuery(
          vs.head,
          VectorTopK,
          Some(
            MetadataFilter.And(
              List(
                MetadataFilter.Equals("documentType", EmbeddingDocumentTypes.ResearcherProfile),
                MetadataFilter.Equals("window", RuntimeCode.AllTimeLabel),
              )
            )
          ),
        )
        vectors.query(VectorIndexes.ResearcherProfile, q) match
          case Left(e)     => Left(RuntimeError.fromStorage("name search", e))
          case Right(hits) =>
            Right(
              (
                hits.flatMap(h => entityOf(h)).distinct,
                hits.size >= VectorTopK,
                hits.lastOption.flatMap(entityOf),
              )
            )

  private def entityOf(h: VectorHit): Option[String] =
    scholarchain.rag.SemanticContext.entityIdOf(h).toOption

  private def matchKind(name: String, a: ujson.Obj): Option[MatchKind] =
    val n    = NameKey.normalize(name)
    val keys = (a.value.get("displayName").flatMap(_.strOpt).toList ++
      a.value.get("dblpPersonKeys").flatMap(_.arrOpt).toList.flatMap(_.toList.flatMap(_.strOpt)))
      .map(NameKey.normalize)
      .distinct
    if keys.contains(n) then Some(MatchKind.Exact)
    else if !NameKey.hasSuffix(n) && keys.exists(k => NameKey.stripSuffix(k) == n) then
      Some(MatchKind.SuffixOnly)
    else None

  private def candidate(item: StoredItem, kind: MatchKind): Option[ResolvedResearcherCandidate] =
    val a    = item.obj.value
    val id   = item.pk
    val rule = a.get("ruleFired").flatMap(_.strOpt).getOrElse("")
    val base = rule match
      case "dblp_person_exact"       => 0.95
      case "accepted_link_component" => 0.85
      case "unresolved_singleton"    => 0.40
      case _                         => 0.50
    val mentions =
      a.get("memberMentionIds").flatMap(_.arrOpt).toList.flatMap(_.toList.flatMap(_.strOpt))
    val bases =
      a.get("baseIdentityIds").flatMap(_.arrOpt).toList.flatMap(_.toList.flatMap(_.strOpt)).toSet
    val checked = mentions.take(MentionChecks).map { m =>
      reader
        .get("mention-researcher", m)
        .toOption
        .flatten
        .flatMap(_.obj.value.get("baseIdentityId").flatMap(_.strOpt))
        .exists(bases.contains)
    }
    val consistent = checked.nonEmpty && checked.forall(identity)
    val inGraph    = reader
      .get("researcher-metrics", id, List(RuntimeCode.AllTimeLabel))
      .toOption
      .flatten
      .isDefined
    val cannotLink = a.get("cannotLinkStatus").flatMap(_.strOpt).getOrElse("clear")
    var conf       = base + (if consistent then 0.04 else -0.20)
    if kind == MatchKind.SuffixOnly then conf *= 0.6
    if cannotLink != "clear" then conf -= 0.30
    if !inGraph then conf = math.min(conf, 0.40)
    conf = math.max(0.0, math.min(1.0, math.round(conf * 1000.0) / 1000.0))
    val used = List("identity-table") ++ Option.when(checked.nonEmpty)("mention-table") ++
      List("metric-table") ++
      Option.when(kind == MatchKind.SuffixOnly)("name-suffix-only") ++
      Option.when(!inGraph)("not-in-graph") ++ List("vector-name-search")
    val display = a.get("displayName").flatMap(_.strOpt).filter(_.trim.nonEmpty).getOrElse(id)
    val c       = ResolvedResearcherCandidate(
      SchemaVersion.Current,
      id,
      display,
      conf,
      used.distinct,
      mentions.take(MentionChecks).distinct,
    )
    summon[Contract[ResolvedResearcherCandidate]].validate(c).toOption

object ResearcherResolverAgent:
  val ResolveThreshold: Double = 0.8
  val VectorTopK: Int          = 100
  val MaxCandidates: Int       = 10
  val MentionChecks: Int       = 3

  enum MatchKind:
    case Exact, SuffixOnly

/** Shared helpers for evidence provenance. */
object EvidenceSupport:
  /** The input reference of a stored item: table, key, policy version and the stored payload hash.
    */
  def inputRef(item: StoredItem, policy: Option[String]): InputRef =
    InputRef(
      s"dynamodb:${item.table}#${item.pk}${item.sk.map("|" + _).getOrElse("")}",
      policy.filter(_.trim.nonEmpty).getOrElse("unversioned"),
      Some(item.payloadHash),
    )
