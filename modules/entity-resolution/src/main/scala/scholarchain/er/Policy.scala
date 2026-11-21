package scholarchain.er

import scholarchain.core.*

/** The outcome of the policy function for one candidate, before it is turned into a
  * `MatchDecisionV2`. `confidence` is the probability-like confidence in the OUTCOME: the score for
  * an accepted pair, 1 - score for a rejected pair, 0.5 for an unresolved pair and 1.0 for rules
  * that rest on authoritative identifiers.
  */
final case class PolicyResult(
    outcome: ResolutionOutcome,
    path: MatchPath,
    score: Double,
    confidence: Double,
    rulesFired: List[String],
    reasonCodes: List[String],
    llmEligible: Boolean,
    modelVersion: Option[String] = None,
)

/** Scoring policy `er-v1`.
  *
  * Score: a weighted mean of transformed features in [0,1]; an absent feature counts 0.5 (neutral).
  * `year_distance` is transformed to `max(0, 1 - d/3)`. Weights sum to 1 per kind and are part of
  * the policy version: changing one is a new policy version, not a config change.
  *
  * Decision order (first rule that fires wins): 0. REJECTED (path `exact_id`, reason
  * `orcid_disagree`): the ORCIDs disagree and nothing else does (no `exact_id_conflict`, no other
  * id disagreement) and `position_agreement` is not 1 (the pair is not the same author position of
  * the same DOI).
  *   1. CONFLICT (path `exact_id`): `exact_id_conflict` = 1, or any authoritative id agreement
  *      feature (`orcid_agreement`, `doi_agreement`, `arxiv_agreement`, `openalex_agreement`) is 0
  *      (two authoritative identifiers disagree), including an ORCID disagreement beside the same
  *      DOI and position. Reason `doi_disagree_possible_version` replaces `doi_disagree` when the
  *      DOI is the only disagreement and `doi_preprint_version_pair` = 1. Never merged, never
  *      overridden by the LLM. 1b. CONFLICT (path `exact_id`, reason `orcid_name_mismatch`): the
  *      only agreeing identifier is the ORCID and the names do not fit (`family_exact` not 1 and
  *      `family_jw` absent or below `OrcidFamilyJwFloor` = 0.92).
  *   2. ACCEPTED (path `exact_id`): an authoritative id agreement feature is 1 (and none is 0).
  *   3. Deterministic rules (path `deterministic`): conservative accept and reject rules, listed in
  *      `Policy.DeterministicRules`.
  *   4. Probabilistic (path `probabilistic`): `score >= accept` accepted; `score < reject`
  *      rejected; otherwise UNRESOLVED, which is LLM-eligible when
  *      `llmBandLow <= score <= llmBandHigh`.
  */
object Policy:
  val Version = "er-v1"

  /** Floor on the family-name Jaro-Winkler (`family_jw`) for an ORCID-only accept when
    * `family_exact` is not 1. Hand-set, NOT tuned on any labels: a typo-level floor. Different
    * common family names stay below it (Wang / Yang about 0.83, Zhang / Zhao about 0.85), a single
    * typo or transliteration slip in a longer name passes. It replaces the repair-03
    * `OrcidNameFloor` (0.85 on `name_sim`), which is retired.
    */
  val OrcidFamilyJwFloor = 0.92

  /** RETIRED (repair-04 F2): no rule reads it any more. Kept for one round only so that the
    * existing test sources still compile until test-engineer removes the test that pins it
    * (`PolicySuite.scala:279`); delete this constant afterwards.
    */
  val OrcidNameFloor = 0.85

  val AgreementFeatures: List[String] =
    List("orcid_agreement", "doi_agreement", "arxiv_agreement", "openalex_agreement")

  val ResearcherWeights: Map[String, Double] = Map(
    "name_sim"           -> 0.28,
    "initials_compat"    -> 0.10,
    "position_agreement" -> 0.14,
    "coauthor_overlap"   -> 0.20,
    "affiliation_compat" -> 0.04,
    "work_title_sim"     -> 0.10,
    "year_distance"      -> 0.05,
    "venue_compat"       -> 0.04,
    "source_reliability" -> 0.05,
  )

  val WorkWeights: Map[String, Double] = Map(
    "title_sim"          -> 0.35,
    "title_exact"        -> 0.10,
    "year_distance"      -> 0.10,
    "author_overlap"     -> 0.20,
    "first_author_agree" -> 0.10,
    "venue_compat"       -> 0.10,
    "source_reliability" -> 0.05,
  )

  val DeterministicRules: List[String] = List(
    "researcher_same_work_same_position_same_name (accept)",
    "researcher_initial_conflict (reject)",
    "researcher_family_mismatch (reject)",
    "work_title_year_authors_exact (accept)",
    "work_title_dissimilar (reject)",
  )

  def weights(kind: EntityKind): Map[String, Double] = kind match
    case EntityKind.Researcher => ResearcherWeights
    case EntityKind.Work       => WorkWeights

  private def transform(name: String, v: Double): Double =
    val t = if name == "year_distance" then 1.0 - v / 3.0 else v
    math.max(0.0, math.min(1.0, t))

  /** Weighted score in [0,1]. */
  def score(kind: EntityKind, features: Map[String, Double]): Double =
    weights(kind).map((n, w) => w * features.get(n).map(transform(n, _)).getOrElse(0.5)).sum

  private def f(features: Map[String, Double], name: String): Option[Double] = features.get(name)

  /** The decision for one candidate under the given thresholds. Pure and total. */
  def decide(kind: EntityKind, features: Map[String, Double], t: Thresholds): PolicyResult =
    val s        = score(kind, features)
    val agreeing = AgreementFeatures.filter(n => f(features, n).contains(1.0))
    val disagree = AgreementFeatures.filter(n => f(features, n).contains(0.0))
    val hj3      = f(features, "exact_id_conflict").contains(1.0)
    val mixed    = f(features, "name_mixed_script").contains(1.0) ||
      f(features, "author_mixed_script").contains(1.0)
    def res(
        o: ResolutionOutcome,
        p: MatchPath,
        conf: Double,
        rules: List[String],
        reasons: List[String],
        llm: Boolean = false,
    ) = PolicyResult(o, p, s, conf, rules, reasons, llm)

    // researcher ORCID disagreement on its own is strong evidence of two people
    val orcidOnly            = !hj3 && disagree == List("orcid_agreement")
    val sameWorkSamePosition = f(features, "position_agreement").contains(1.0)
    val namesFit             = f(features, "family_exact").contains(1.0) ||
      f(features, "family_jw").exists(_ >= Policy.OrcidFamilyJwFloor)
    val onlyOrcidAgrees = agreeing == List("orcid_agreement")
    val versionPair     = f(features, "doi_preprint_version_pair").contains(1.0) &&
      disagree == List("doi_agreement")

    if orcidOnly && !sameWorkSamePosition then
      res(
        ResolutionOutcome.Rejected,
        MatchPath.ExactId,
        1.0,
        List("orcid_disagreement"),
        List("orcid_disagree"),
      )
    else if hj3 || disagree.nonEmpty then
      res(
        ResolutionOutcome.Conflict,
        MatchPath.ExactId,
        1.0,
        List("exact_id_disagreement"),
        (if hj3 then List("hj3_exact_id_conflict") else Nil) ++
          disagree.map(n =>
            if n == "doi_agreement" && versionPair then "doi_disagree_possible_version"
            else n.replace("_agreement", "") + "_disagree"
          ),
      )
    else if onlyOrcidAgrees && !namesFit then
      res(
        ResolutionOutcome.Conflict,
        MatchPath.ExactId,
        1.0,
        List("orcid_name_mismatch"),
        List("orcid_name_mismatch"),
      )
    else if agreeing.nonEmpty then
      res(
        ResolutionOutcome.Accepted,
        MatchPath.ExactId,
        1.0,
        List("authoritative_id_agree"),
        agreeing.map(_.replace("_agreement", "") + "_equal"),
      )
    else
      deterministic(kind, features, mixed) match
        case Some((o, rule, reason)) =>
          res(
            o,
            MatchPath.Deterministic,
            if o == ResolutionOutcome.Accepted then 0.95 else 0.90,
            List(rule),
            List(reason),
          )
        case None =>
          if s >= t.accept then
            res(
              ResolutionOutcome.Accepted,
              MatchPath.Probabilistic,
              s,
              List("score_ge_accept"),
              List("score_ge_accept"),
            )
          else if s < t.reject then
            res(
              ResolutionOutcome.Rejected,
              MatchPath.Probabilistic,
              1.0 - s,
              List("score_lt_reject"),
              List("score_lt_reject"),
            )
          else
            val inBand = s >= t.llmBandLow && s <= t.llmBandHigh
            res(
              ResolutionOutcome.Unresolved,
              MatchPath.Probabilistic,
              0.5,
              List("score_between_thresholds"),
              List(if inBand then "llm_band" else "outside_llm_band"),
              inBand,
            )

  private def deterministic(
      kind: EntityKind,
      x: Map[String, Double],
      mixed: Boolean,
  ): Option[(ResolutionOutcome, String, String)] =
    def ge(n: String, v: Double) = x.get(n).exists(_ >= v)
    def eq(n: String, v: Double) = x.get(n).contains(v)
    kind match
      case EntityKind.Researcher =>
        if eq("position_agreement", 1.0) && ge("name_sim", 0.92) && eq("family_exact", 1.0) &&
          ge("work_title_sim", 0.9) && !mixed && !eq("homonym_suffix_conflict", 1.0)
        then
          Some(
            (
              ResolutionOutcome.Accepted,
              "researcher_same_work_same_position_same_name",
              "same_work_same_position",
            )
          )
        else if eq("family_exact", 1.0) && eq("initials_compat", 0.0) then
          Some((ResolutionOutcome.Rejected, "researcher_initial_conflict", "initial_conflict"))
        else if eq("family_exact", 0.0) && x.get("name_sim").exists(_ < 0.6) then
          Some((ResolutionOutcome.Rejected, "researcher_family_mismatch", "family_mismatch"))
        else None
      case EntityKind.Work =>
        if eq("title_exact", 1.0) && eq("year_distance", 0.0) && ge("author_overlap", 0.8) && !mixed
        then
          Some(
            (
              ResolutionOutcome.Accepted,
              "work_title_year_authors_exact",
              "title_year_authors_exact",
            )
          )
        else if x.get("title_sim").exists(_ < 0.40) then
          Some((ResolutionOutcome.Rejected, "work_title_dissimilar", "title_dissimilar"))
        else None

  /** Applies a validated LLM adjudication to an UNRESOLVED, LLM-eligible policy result.
    *   - CONFLICT, accepted, rejected and non-eligible results are returned unchanged: the LLM
    *     never overrides them.
    *   - abstain keeps UNRESOLVED (reason `llm_abstain` added).
    *   - accept or reject with confidence below `minConfidence` keeps UNRESOLVED
    *     (`llm_low_confidence`).
    *   - otherwise the outcome follows the LLM, path `llm`, `modelVersion` = `<model>|<prompt>`.
    */
  def applyAdjudication(
      r: PolicyResult,
      adj: Option[AdjudicationRecord],
      minConfidence: Double,
      candidate: MatchCandidate,
  ): PolicyResult =
    applyAdjudication(
      r,
      adj.filter(a => AdjudicationRecord.validateAgainst(a, candidate).isRight),
      minConfidence,
    )

  /** As above, for an adjudication already known to belong to the candidate. Prefer the overload
    * that takes the candidate: it drops a foreign or tampered record before it can change anything.
    */
  def applyAdjudication(
      r: PolicyResult,
      adj: Option[AdjudicationRecord],
      minConfidence: Double,
  ): PolicyResult =
    adj match
      case Some(a) if r.outcome == ResolutionOutcome.Unresolved && r.llmEligible =>
        a.outcome match
          case AdjudicationOutcome.Abstain =>
            r.copy(reasonCodes = r.reasonCodes :+ "llm_abstain")
          case _ if a.confidence < minConfidence =>
            r.copy(reasonCodes = r.reasonCodes :+ "llm_low_confidence")
          case o =>
            val out =
              if o == AdjudicationOutcome.Accept then ResolutionOutcome.Accepted
              else ResolutionOutcome.Rejected
            r.copy(
              outcome = out,
              path = MatchPath.Llm,
              confidence = a.confidence,
              rulesFired = List("llm_adjudication"),
              reasonCodes = a.reasonCodes,
              llmEligible = false,
              modelVersion = Some(s"${a.modelId}|${a.promptVersion}"),
            )
      case _ => r

object Decisions:
  /** The decision timestamp: the latest `retrievedAt` in the candidate's provenance, else the
    * policy's `decision-epoch`. Never the wall clock.
    */
  def timestamp(c: MatchCandidate, cfg: ErConfig): String =
    c.provenance.retrievedAt.getOrElse(cfg.decisionEpoch)

  /** Builds the audit record. `features` holds the candidate features plus `policy.score` and the
    * thresholds in force (`policy.threshold.*`), so score, threshold and policy version are on
    * every decision.
    */
  def build(
      c: MatchCandidate,
      r: PolicyResult,
      cfg: ErConfig,
      runId: String,
      provenance: Provenance,
  ): Either[ValidationError, MatchDecisionV2] =
    val t = cfg.thresholds(c.entityKind)
    val d = MatchDecisionV2(
      SchemaVersion.Current,
      c.leftId,
      c.rightId,
      c.entityKind,
      r.outcome,
      r.path,
      r.confidence,
      r.rulesFired,
      r.reasonCodes,
      r.modelVersion,
      cfg.policyVersion,
      timestamp(c, cfg),
      runId,
      c.features ++ Map(
        "policy.score"               -> r.score,
        "policy.threshold.accept"    -> t.accept,
        "policy.threshold.reject"    -> t.reject,
        "policy.threshold.band-low"  -> t.llmBandLow,
        "policy.threshold.band-high" -> t.llmBandHigh,
      ),
      provenance,
    )
    summon[Contract[MatchDecisionV2]].validate(d)
