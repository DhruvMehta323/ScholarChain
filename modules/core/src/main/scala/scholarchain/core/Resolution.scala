package scholarchain.core

import java.time.Instant

import scala.util.Try

import WirePickle.{*, given}
import ValidationError.*

/** Entity-resolution contracts (Phase 05). All records are immutable, carry `schemaVersion` and
  * `provenance`, and are written only through `Wire.write`.
  *
  * Versioning: `MatchCandidate`, `AdjudicationRecord` and `ResolvedMention` are v1. `MatchDecision`
  * (Phase 03) is frozen as v1; `MatchDecisionV2` is the entity-resolution decision record. The two
  * are told apart by their field sets (v2 has `matchPath`, `entityKind`, `decisionTimestamp`).
  */
private[core] object ResolutionChecks:
  import V.*

  /** Record IDs a candidate of this kind may link. Researcher candidates link mentions or
    * researchers (`mention:`, `researcher:`); work candidates link `work:` IDs.
    */
  def idForKind(kind: EntityKind, field: String, id: String): R =
    val okPrefix = kind match
      case EntityKind.Researcher => id.startsWith("mention:") || id.startsWith("researcher:")
      case EntityKind.Work       => id.startsWith("work:")
    unit(Ids.checkAnyRef(id)).flatMap(_ =>
      if okPrefix then ok else Left(InvalidField(field, s"is not a ${kind.wire} reference"))
    )

  def canonicalPair(kind: EntityKind, left: String, right: String): R =
    all(
      idForKind(kind, "leftId", left),
      idForKind(kind, "rightId", right),
      if left == right then Left(InvalidPair(left, right, "self-pair"))
      else if left.compareTo(right) > 0 then
        Left(InvalidPair(left, right, "not in canonical order"))
      else ok,
    )

  def instant(field: String, t: String): R =
    if Try(Instant.parse(t)).isSuccess then ok
    else Left(InvalidField(field, "must be an ISO-8601 instant"))

  def tokens(field: String, xs: Seq[String]): R =
    each(xs)(x => unit(Ids.token(field, x)))

/** A candidate pair produced by blocking and feature extraction. It carries raw features only: no
  * score and no decision. `leftId` < `rightId` by `String.compareTo` (canonical order, no
  * self-pair). `blockKey` is a non-blank token; `featureSetVersion` names the feature definitions.
  */
final case class MatchCandidate(
    schemaVersion: Int,
    leftId: String,
    rightId: String,
    entityKind: EntityKind,
    blockKey: String,
    blockKeyType: BlockKeyType,
    features: Map[String, Double],
    featureSetVersion: String,
    provenance: Provenance,
) derives ReadWriter

object MatchCandidate:
  given Contract[MatchCandidate] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      ResolutionChecks.canonicalPair(c.entityKind, c.leftId, c.rightId),
      V.unit(Ids.token("blockKey", c.blockKey)),
      V.unit(Ids.token("featureSetVersion", c.featureSetVersion)),
      V.each(c.features)((name, v) =>
        V.all(V.nonBlank("features.name", name), V.finite(s"features.$name", v))
      ),
      V.prov(c.provenance),
    )
  )

  /** Canonicalizes the two IDs regardless of argument order, then validates. */
  def build(
      idA: String,
      idB: String,
      entityKind: EntityKind,
      blockKey: String,
      blockKeyType: BlockKeyType,
      features: Map[String, Double],
      featureSetVersion: String,
      provenance: Provenance,
  ): Either[ValidationError, MatchCandidate] =
    val (l, r) = if idA.compareTo(idB) <= 0 then (idA, idB) else (idB, idA)
    summon[Contract[MatchCandidate]].validate(
      MatchCandidate(
        SchemaVersion.Current,
        l,
        r,
        entityKind,
        blockKey,
        blockKeyType,
        features,
        featureSetVersion,
        provenance,
      )
    )

/** Entity-resolution decision (v2; `MatchDecision` v1 stays frozen). `decisionTimestamp` is an
  * ISO-8601 instant supplied by the caller from the run inputs (for example the latest
  * `retrievedAt` among the inputs or the policy's effective instant). Code must never read the wall
  * clock to fill it, so identical inputs give identical bytes.
  *
  * Rules: IDs canonical; `rulesFired` and `reasonCodes` non-empty tokens; `confidence` in [0,1];
  * `modelVersion` (model id and prompt version, e.g. `claude-x|er-adjudication-v1`) is required
  * exactly when `matchPath` is `llm`; `runId` is a valid run ID.
  */
final case class MatchDecisionV2(
    schemaVersion: Int,
    leftId: String,
    rightId: String,
    entityKind: EntityKind,
    outcome: ResolutionOutcome,
    matchPath: MatchPath,
    confidence: Double,
    rulesFired: List[String],
    reasonCodes: List[String],
    modelVersion: Option[String] = None,
    policyVersion: String,
    decisionTimestamp: String,
    runId: String,
    features: Map[String, Double],
    provenance: Provenance,
) derives ReadWriter

object MatchDecisionV2:
  given Contract[MatchDecisionV2] = V.contract(d =>
    V.all(
      V.schema(d.schemaVersion),
      ResolutionChecks.canonicalPair(d.entityKind, d.leftId, d.rightId),
      V.confidence("confidence", d.confidence),
      V.nonEmpty("rulesFired", d.rulesFired),
      ResolutionChecks.tokens("rulesFired", d.rulesFired),
      V.nonEmpty("reasonCodes", d.reasonCodes),
      ResolutionChecks.tokens("reasonCodes", d.reasonCodes),
      (d.matchPath, d.modelVersion) match
        case (MatchPath.Llm, None) =>
          Left(InvalidField("modelVersion", "is required when matchPath is llm"))
        case (p, Some(_)) if p != MatchPath.Llm =>
          Left(InvalidField("modelVersion", "is only allowed when matchPath is llm"))
        case (_, Some(m)) => V.unit(Ids.token("modelVersion", m))
        case _            => V.ok,
      V.unit(Ids.token("policyVersion", d.policyVersion)),
      ResolutionChecks.instant("decisionTimestamp", d.decisionTimestamp),
      V.unit(Ids.checkRunId(d.runId)),
      V.each(d.features)((k, v) => V.finite(s"features.$k", v)),
      V.prov(d.provenance),
    )
  )

/** The answer of an LLM adjudicator for one candidate. `abstain` is valid. IDs are in canonical
  * order. `citedFields` names the input fields the answer rests on; it must be non-empty for accept
  * and reject (an answer with no cited evidence is unsupported) and may be empty for abstain.
  * `inputHash` is the SHA-256 (64 lowercase hex) of the exact prompt input.
  *
  * Use `validateAgainst` to check a record against its input candidate: its IDs and kind must equal
  * the candidate's, so any ID not present in the candidate is rejected.
  */
final case class AdjudicationRecord(
    schemaVersion: Int,
    leftId: String,
    rightId: String,
    entityKind: EntityKind,
    outcome: AdjudicationOutcome,
    confidence: Double,
    citedFields: List[String],
    reasonCodes: List[String],
    promptVersion: String,
    modelId: String,
    policyVersion: String,
    inputHash: String,
    provenance: Provenance,
) derives ReadWriter

object AdjudicationRecord:
  given Contract[AdjudicationRecord] = V.contract(a =>
    V.all(
      V.schema(a.schemaVersion),
      ResolutionChecks.canonicalPair(a.entityKind, a.leftId, a.rightId),
      V.confidence("confidence", a.confidence),
      ResolutionChecks.tokens("citedFields", a.citedFields),
      if a.outcome != AdjudicationOutcome.Abstain && a.citedFields.isEmpty then
        Left(InvalidField("citedFields", "must not be empty for accept or reject"))
      else V.ok,
      V.nonEmpty("reasonCodes", a.reasonCodes),
      ResolutionChecks.tokens("reasonCodes", a.reasonCodes),
      V.unit(Ids.token("promptVersion", a.promptVersion)),
      V.unit(Ids.token("modelId", a.modelId)),
      V.unit(Ids.token("policyVersion", a.policyVersion)),
      if Hashing.isSha256Hex(a.inputHash) then V.ok
      else Left(InvalidField("inputHash", "must be 64 lowercase hex characters (SHA-256)")),
      V.prov(a.provenance),
    )
  )

  /** Validates the record, then requires its IDs and kind to equal those of `candidate`. */
  def validateAgainst(
      a: AdjudicationRecord,
      candidate: MatchCandidate,
  ): Either[ValidationError, AdjudicationRecord] =
    summon[Contract[AdjudicationRecord]].validate(a).flatMap { v =>
      val ids = Set(candidate.leftId, candidate.rightId)
      V.all(
        if ids.contains(v.leftId) then V.ok
        else Left(InvalidField("leftId", "is not present in the candidate")),
        if ids.contains(v.rightId) then V.ok
        else Left(InvalidField("rightId", "is not present in the candidate")),
        if v.leftId == candidate.leftId && v.rightId == candidate.rightId then V.ok
        else Left(InvalidPair(v.leftId, v.rightId, "must equal the candidate's pair")),
        if v.entityKind == candidate.entityKind then V.ok
        else Left(InvalidField("entityKind", "must equal the candidate's entityKind")),
      ).map(_ => v)
    }

/** Links one author mention to a researcher. Phase 06 turns `MentionPairCandidate` into
  * researcher-keyed edges through this record.
  *
  * Never force-merge: only `accepted` may map a mention to a shared researcher. For `rejected`,
  * `unresolved` and `conflict`, `researcherId` must equal `Ids.researcherIdUnresolved(mentionId)`,
  * the mention's own singleton identity (stable across policy versions; the `unresolved.v1` scheme
  * tag versions its derivation). `candidateResearcherId` optionally names the researcher that was
  * considered (required to differ from `researcherId`; not allowed when accepted).
  */
final case class ResolvedMention(
    schemaVersion: Int,
    mentionId: String,
    researcherId: String,
    outcome: ResolutionOutcome,
    confidence: Double,
    policyVersion: String,
    candidateResearcherId: Option[String] = None,
    provenance: Provenance,
) derives ReadWriter

object ResolvedMention:
  given Contract[ResolvedMention] = V.contract(r =>
    V.all(
      V.schema(r.schemaVersion),
      V.unit(Ids.checkMentionId(r.mentionId)),
      V.unit(Ids.checkResearcherId(r.researcherId)),
      V.confidence("confidence", r.confidence),
      V.unit(Ids.token("policyVersion", r.policyVersion)),
      r.outcome match
        case ResolutionOutcome.Accepted =>
          if r.candidateResearcherId.isDefined then
            Left(InvalidField("candidateResearcherId", "must be absent when accepted"))
          else V.ok
        case _ =>
          V.all(
            V.matches("researcherId", Ids.researcherIdUnresolved(r.mentionId), r.researcherId),
            r.candidateResearcherId.fold(V.ok)(c =>
              V.all(
                V.unit(Ids.checkResearcherId(c)),
                if c == r.researcherId then
                  Left(InvalidField("candidateResearcherId", "must differ from researcherId"))
                else V.ok,
              )
            ),
          ),
      V.prov(r.provenance),
    )
  )

  /** The singleton (never merged) record for a mention. */
  def unresolved(
      mentionId: String,
      outcome: ResolutionOutcome,
      confidence: Double,
      policyVersion: String,
      candidateResearcherId: Option[String],
      provenance: Provenance,
  ): Either[ValidationError, ResolvedMention] =
    for
      _ <- Either.cond(
        outcome != ResolutionOutcome.Accepted,
        (),
        ValidationError.InvalidField(
          "outcome",
          "ResolvedMention.unresolved builds a singleton record and cannot use 'accepted'",
        ),
      )
      id <- Ids.researcherIdUnresolved(mentionId)
      r  <- summon[Contract[ResolvedMention]].validate(
        ResolvedMention(
          SchemaVersion.Current,
          mentionId,
          id,
          outcome,
          confidence,
          policyVersion,
          candidateResearcherId,
          provenance,
        )
      )
    yield r
