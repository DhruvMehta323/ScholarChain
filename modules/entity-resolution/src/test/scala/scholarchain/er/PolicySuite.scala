package scholarchain.er

import scholarchain.core.*

import ErFx.*

class PolicySuite extends munit.FunSuite:
  private val R = EntityKind.Researcher
  private val W = EntityKind.Work

  // Hand-computed: name_sim 1, initials_compat 1, coauthor_overlap 1 -> .28 + .10 + .20 = .58,
  // the other six researcher weights (.14 .04 .10 .05 .04 .05 = .42) are absent -> .42 * .5 = .21.
  private val Mid = Map("name_sim" -> 1.0, "initials_compat" -> 1.0, "coauthor_overlap" -> 1.0)

  // ---- score ----------------------------------------------------------------------------

  test("score: every feature absent is neutral 0.5 for both kinds (weights sum to 1)") {
    assert(close(Policy.score(R, Map.empty), 0.5))
    assert(close(Policy.score(W, Map.empty), 0.5))
    assert(close(Policy.ResearcherWeights.values.sum, 1.0))
    assert(close(Policy.WorkWeights.values.sum, 1.0))
  }

  test("score: researcher name_sim + initials_compat + coauthor_overlap = 0.79") {
    assert(close(Policy.score(R, Mid), 0.79))
  }

  test("score: work title_sim 1.0 and title_exact 1.0 = .35 + .10 + .55 * .5 = 0.725") {
    assert(close(Policy.score(W, Map("title_sim" -> 1.0, "title_exact" -> 1.0)), 0.725))
  }

  test("score: year_distance is transformed to max(0, 1 - d/3)") {
    // other weights (.95) absent -> .475
    assert(close(Policy.score(R, Map("year_distance" -> 6.0)), 0.475))
    assert(close(Policy.score(R, Map("year_distance" -> 0.0)), 0.525))
    assert(close(Policy.score(R, Map("year_distance" -> 1.5)), 0.5))
    assert(close(Policy.score(R, Map("year_distance" -> 3.0)), 0.475))
  }

  test("score: features without a weight (family_exact) do not change the score") {
    assert(close(Policy.score(R, Mid + ("family_exact" -> 1.0)), 0.79))
  }

  // ---- band routing and separate thresholds --------------------------------------------

  test("probabilistic: score 0.79 is UNRESOLVED and LLM-eligible inside the band [.55, .80]") {
    val r = Policy.decide(R, Mid, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Unresolved)
    assertEquals(r.path, MatchPath.Probabilistic)
    assert(r.llmEligible)
    assertEquals(r.reasonCodes, List("llm_band"))
    assertEquals(r.rulesFired, List("score_between_thresholds"))
    assertEquals(r.confidence, 0.5)
    assert(close(r.score, 0.79))
  }

  test("probabilistic: score at or above accept is ACCEPTED with the score as confidence") {
    val r = Policy.decide(R, Mid, Thresholds(0.75, 0.40, 0.50, 0.70))
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.path, MatchPath.Probabilistic)
    assertEquals(r.rulesFired, List("score_ge_accept"))
    assert(close(r.confidence, 0.79))
    assert(!r.llmEligible)
  }

  test("probabilistic: score below reject is REJECTED with 1 - score as confidence") {
    val r = Policy.decide(R, Mid, Thresholds(0.90, 0.80, 0.80, 0.85))
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.rulesFired, List("score_lt_reject"))
    assert(close(r.confidence, 0.21))
    assert(!r.llmEligible)
  }

  test("probabilistic: between thresholds but outside the LLM band is UNRESOLVED, not eligible") {
    val r = Policy.decide(R, Mid, Thresholds(0.95, 0.30, 0.85, 0.90))
    assertEquals(r.outcome, ResolutionOutcome.Unresolved)
    assertEquals(r.reasonCodes, List("outside_llm_band"))
    assert(!r.llmEligible)
    val low = Policy.decide(R, Mid, Thresholds(0.95, 0.30, 0.20, 0.70))
    assertEquals(low.reasonCodes, List("outside_llm_band"))
    assert(!low.llmEligible)
  }

  test("boundaries: accept is >=, reject is strict <, both band edges are inclusive") {
    val s = Policy.score(R, Mid)
    assertEquals(
      Policy.decide(R, Mid, Thresholds(s, 0.1, 0.1, s)).outcome,
      ResolutionOutcome.Accepted,
    )
    val atReject = Policy.decide(R, Mid, Thresholds(0.95, s, s, 0.9))
    assertEquals(atReject.outcome, ResolutionOutcome.Unresolved)
    assert(atReject.llmEligible, "score == band-low is inside the band")
    val atHigh = Policy.decide(R, Mid, Thresholds(0.95, 0.1, 0.1, s))
    assert(atHigh.llmEligible, "score == band-high is inside the band")
  }

  test("separate thresholds per entity kind: the same score routes differently") {
    val rt = Thresholds(0.90, 0.60, 0.60, 0.80)
    val wt = Thresholds(0.90, 0.40, 0.40, 0.80)
    // all features absent: both kinds score exactly 0.5
    val r = Policy.decide(R, Map.empty, rt)
    val w = Policy.decide(W, Map.empty, wt)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(w.outcome, ResolutionOutcome.Unresolved)
    assert(w.llmEligible)
  }

  test("config: researcher and work thresholds are independent keys with their own defaults") {
    val base = cfg
    assertEquals(base.researcher, Thresholds(0.85, 0.45, 0.55, 0.80))
    assertEquals(base.work, Thresholds(0.85, 0.45, 0.55, 0.80))
    val c = ErConfigLoader
      .load(Map.empty, Map("scholarchain.er.researcher.threshold.accept" -> "0.90"))
      .fold(es => fail(es.toString), identity)
    assertEquals(c.thresholds(R).accept, 0.90)
    assertEquals(c.thresholds(W).accept, 0.85)
  }

  test("config: threshold ordering reject <= low <= high <= accept is enforced") {
    val bad = ErConfigLoader.load(Map.empty, Map("scholarchain.er.work.threshold.accept" -> "0.70"))
    assert(bad.isLeft, "accept below band-high must be refused")
    val bad2 =
      ErConfigLoader.load(Map.empty, Map("scholarchain.er.researcher.threshold.reject" -> "0.60"))
    assert(bad2.isLeft, "reject above band-low must be refused")
  }

  // ---- exact identifiers and CONFLICT -----------------------------------------------------

  test("REJECTED: plain ORCID disagreement, even when every name feature is perfect (M1)") {
    val f = Mid ++ Map("orcid_agreement" -> 0.0, "name_sim" -> 1.0, "family_exact" -> 1.0)
    val r = Policy.decide(R, f, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.rulesFired, List("orcid_disagreement"))
    assertEquals(r.reasonCodes, List("orcid_disagree"))
    assertEquals(r.confidence, 1.0)
    assert(!r.llmEligible)
  }

  test("REJECTED: the bare feature map Map(orcid_agreement -> 0) is REJECTED, not CONFLICT (M1)") {
    val r = Policy.decide(R, Map("orcid_agreement" -> 0.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.rulesFired, List("orcid_disagreement"))
    assertEquals(r.reasonCodes, List("orcid_disagree"))
    assertEquals(r.confidence, 1.0)
  }

  test("REJECTED: a moved author position (position_agreement 0) is not the same-position case") {
    val r = Policy.decide(R, Map("orcid_agreement" -> 0.0, "position_agreement" -> 0.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.reasonCodes, List("orcid_disagree"))
  }

  test(
    "CONFLICT: ORCID disagreement beside the same DOI at the same position (M1 keeps CONFLICT)"
  ) {
    val f = Mid ++ Map(
      "orcid_agreement"    -> 0.0,
      "name_sim"           -> 1.0,
      "family_exact"       -> 1.0,
      "position_agreement" -> 1.0,
    )
    val r = Policy.decide(R, f, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.rulesFired, List("exact_id_disagreement"))
    assertEquals(r.reasonCodes, List("orcid_disagree"))
    assertEquals(r.confidence, 1.0)
    assert(!r.llmEligible)
  }

  test("CONFLICT: ORCID disagreement beside HJ3 evidence stays CONFLICT with both reasons (M1)") {
    val r = Policy.decide(R, Map("exact_id_conflict" -> 1.0, "orcid_agreement" -> 0.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.rulesFired, List("exact_id_disagreement"))
    assertEquals(r.reasonCodes, List("hj3_exact_id_conflict", "orcid_disagree"))
  }

  test("CONFLICT: ORCID disagreement beside another id disagreement stays CONFLICT (M1)") {
    val r = Policy.decide(R, Map("orcid_agreement" -> 0.0, "doi_agreement" -> 0.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.reasonCodes, List("orcid_disagree", "doi_disagree"))
  }

  test("CONFLICT: a DOI disagreement between works") {
    val r = Policy.decide(W, Map("title_sim" -> 1.0, "doi_agreement" -> 0.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.reasonCodes, List("doi_disagree"))
  }

  test("CONFLICT outranks an agreeing identifier: doi equal, same position, orcid different") {
    val r = Policy.decide(
      R,
      Map("doi_agreement" -> 1.0, "orcid_agreement" -> 0.0, "position_agreement" -> 1.0),
      Thr,
    )
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.reasonCodes, List("orcid_disagree"))
  }

  test("an agreeing identifier does not stop the plain ORCID rejection (M1): never ACCEPTED") {
    val r = Policy.decide(R, Map("doi_agreement" -> 1.0, "orcid_agreement" -> 0.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.reasonCodes, List("orcid_disagree"))
    assertNotEquals(r.outcome, ResolutionOutcome.Accepted)
  }

  test(
    "CONFLICT: the Phase 04 HJ3 evidence flag alone forces CONFLICT, even with an agreeing DOI"
  ) {
    val r = Policy.decide(W, Map("doi_agreement" -> 1.0, "exact_id_conflict" -> 1.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.reasonCodes, List("hj3_exact_id_conflict"))
  }

  test("CONFLICT: both an HJ3 flag and a disagreeing id list both reason codes") {
    val r = Policy.decide(R, Map("exact_id_conflict" -> 1.0, "orcid_agreement" -> 0.0), Thr)
    assertEquals(r.reasonCodes, List("hj3_exact_id_conflict", "orcid_disagree"))
  }

  test("exact id: an agreeing identifier ACCEPTS on path exact_id with confidence 1.0") {
    val r = Policy.decide(W, Map("doi_agreement" -> 1.0, "title_sim" -> 0.1), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.confidence, 1.0)
    assertEquals(r.reasonCodes, List("doi_equal"))
  }

  test("exact id rules run before the deterministic rules") {
    // same family but initials conflict would reject deterministically; an equal ORCID still accepts
    val r = Policy.decide(
      R,
      Map("family_exact" -> 1.0, "initials_compat" -> 0.0, "orcid_agreement" -> 1.0),
      Thr,
    )
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.path, MatchPath.ExactId)
  }

  // ---- H1: an ORCID match needs names that fit ---------------------------------------------------

  private val Orc = "0000-0002-1825-0097"

  private def orcidPair(a: String, b: String): Map[String, Double] =
    Features
      .researcher(
        person("mention:a", SourceType.Dblp, a, orcid = Some(Orc)),
        person("mention:b", SourceType.Crossref, b, orcid = Some(Orc)),
      )

  test(
    "H1: Wei Wang and Li Zhang sharing an ORCID are CONFLICT orcid_name_mismatch, not ACCEPTED"
  ) {
    val f = orcidPair("Wei Wang", "Li Zhang")
    assertEquals(f("orcid_agreement"), 1.0)
    assertEquals(f("family_exact"), 0.0)
    val r = Policy.decide(R, f, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.rulesFired, List("orcid_name_mismatch"))
    assertEquals(r.reasonCodes, List("orcid_name_mismatch"))
    assertEquals(r.confidence, 1.0)
    assert(!r.llmEligible)
  }

  test("H1 control: the same ORCID with the same family name (Wei Wang / W. Wang) is ACCEPTED") {
    val f = orcidPair("Wei Wang", "W. Wang")
    assertEquals(f("family_exact"), 1.0)
    val r = Policy.decide(R, f, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.reasonCodes, List("orcid_equal"))
  }

  test("F2 boundary: family_jw exactly 0.92 accepts, just below does not (family_exact 0)") {
    def at(jw: Double) =
      Policy.decide(
        R,
        Map("orcid_agreement" -> 1.0, "family_exact" -> 0.0, "family_jw" -> jw),
        Thr,
      )
    assertEquals(Policy.OrcidFamilyJwFloor, 0.92)
    val ok = at(0.92)
    assertEquals(ok.outcome, ResolutionOutcome.Accepted)
    assertEquals(ok.reasonCodes, List("orcid_equal"))
    List(0.9199, 0.85, 0.5, 0.0).foreach { jw =>
      val r = at(jw)
      assertEquals(r.outcome, ResolutionOutcome.Conflict, s"family_jw $jw")
      assertEquals(r.rulesFired, List("orcid_name_mismatch"), s"family_jw $jw")
      assertEquals(r.reasonCodes, List("orcid_name_mismatch"), s"family_jw $jw")
    }
    assertEquals(at(1.0).outcome, ResolutionOutcome.Accepted)
    // family_jw absent (family_exact 0): conflict
    val absent = Policy.decide(R, Map("orcid_agreement" -> 1.0, "family_exact" -> 0.0), Thr)
    assertEquals(absent.outcome, ResolutionOutcome.Conflict)
    assertEquals(absent.reasonCodes, List("orcid_name_mismatch"))
    // a high name_sim alone no longer accepts
    val sim = Policy.decide(
      R,
      Map("orcid_agreement" -> 1.0, "family_exact" -> 0.0, "name_sim" -> 0.99),
      Thr,
    )
    assertEquals(sim.outcome, ResolutionOutcome.Conflict)
    // family_exact = 1 accepts whatever family_jw is
    List(0.0, 0.5, 0.9199).foreach { jw =>
      val r = Policy.decide(
        R,
        Map("orcid_agreement" -> 1.0, "family_exact" -> 1.0, "family_jw" -> jw),
        Thr,
      )
      assertEquals(r.outcome, ResolutionOutcome.Accepted, s"family_jw $jw")
    }
  }

  test(
    "H1: family_exact = 1 accepts whatever name_sim is, and absent name_sim does not matter then"
  ) {
    val low = Policy.decide(
      R,
      Map("orcid_agreement" -> 1.0, "family_exact" -> 1.0, "name_sim" -> 0.1),
      Thr,
    )
    assertEquals(low.outcome, ResolutionOutcome.Accepted)
    val noSim = Policy.decide(R, Map("orcid_agreement" -> 1.0, "family_exact" -> 1.0), Thr)
    assertEquals(noSim.outcome, ResolutionOutcome.Accepted)
  }

  test("H1: absent name features give CONFLICT; a bare Map(orcid_agreement -> 1) gives CONFLICT") {
    val bare = Policy.decide(R, Map("orcid_agreement" -> 1.0), Thr)
    assertEquals(bare.outcome, ResolutionOutcome.Conflict)
    assertEquals(bare.rulesFired, List("orcid_name_mismatch"))
    assertEquals(bare.reasonCodes, List("orcid_name_mismatch"))
    val noNames = Policy.decide(
      R,
      Map("orcid_agreement" -> 1.0, "coauthor_overlap" -> 1.0, "work_title_sim" -> 1.0),
      Thr,
    )
    assertEquals(noNames.outcome, ResolutionOutcome.Conflict)
    // unparsable names: Features omits every name feature, so the pair is CONFLICT too
    val unparsed = Features.researcher(
      EntityView("mention:a", EntityKind.Researcher, SourceType.Dblp, orcid = Some(Orc)),
      EntityView("mention:b", EntityKind.Researcher, SourceType.Crossref, orcid = Some(Orc)),
    )
    assertEquals(unparsed("orcid_agreement"), 1.0)
    assert(!unparsed.contains("name_sim") && !unparsed.contains("family_exact"))
    assertEquals(Policy.decide(R, unparsed, Thr).outcome, ResolutionOutcome.Conflict)
  }

  test(
    "H1: a name mismatch does not touch a work DOI accept (only the ORCID rule has the name check)"
  ) {
    val r = Policy.decide(W, Map("doi_agreement" -> 1.0, "title_sim" -> 0.1), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
  }

  // ---- L2: a preprint / published DOI pair ----------------------------------------------------------

  test("L2: a DOI disagreement that looks like a preprint/published pair gets its own reason") {
    val r = Policy.decide(
      W,
      Map("title_sim" -> 1.0, "doi_agreement" -> 0.0, "doi_preprint_version_pair" -> 1.0),
      Thr,
    )
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.rulesFired, List("exact_id_disagreement"))
    assertEquals(r.reasonCodes, List("doi_disagree_possible_version"))
    assertEquals(r.confidence, 1.0)
    assert(!r.llmEligible)
    // without the feature the reason is the plain one
    val plain = Policy.decide(W, Map("title_sim" -> 1.0, "doi_agreement" -> 0.0), Thr)
    assertEquals(plain.reasonCodes, List("doi_disagree"))
  }

  test("L2: the version reason is used only when the DOI is the only disagreement") {
    val r = Policy.decide(
      W,
      Map("doi_agreement" -> 0.0, "arxiv_agreement" -> 0.0, "doi_preprint_version_pair" -> 1.0),
      Thr,
    )
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.reasonCodes, List("doi_disagree", "arxiv_disagree"))
  }

  // ---- deterministic rules ----------------------------------------------------------------

  private val SameWork = Map(
    "position_agreement" -> 1.0,
    "name_sim"           -> 0.95,
    "family_exact"       -> 1.0,
    "work_title_sim"     -> 0.95,
  )

  test("deterministic accept: same work, same position, same family, name_sim >= .92") {
    val r = Policy.decide(R, SameWork, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.path, MatchPath.Deterministic)
    assertEquals(r.rulesFired, List("researcher_same_work_same_position_same_name"))
    assertEquals(r.confidence, 0.95)
  }

  test(
    "deterministic accept is blocked by a mixed-script name; the pair falls to the score 0.741"
  ) {
    val r = Policy.decide(R, SameWork + ("name_mixed_script" -> 1.0), Thr)
    assertEquals(r.path, MatchPath.Probabilistic)
    assertEquals(r.outcome, ResolutionOutcome.Unresolved)
    assert(close(r.score, 0.741))
    assert(r.llmEligible, "a mixed-script pair may still go to the LLM band")
  }

  test("deterministic accept is blocked by a homonym-suffix conflict") {
    val r = Policy.decide(R, SameWork + ("homonym_suffix_conflict" -> 1.0), Thr)
    assertEquals(r.path, MatchPath.Probabilistic)
    assertNotEquals(r.outcome, ResolutionOutcome.Accepted)
  }

  test(
    "deterministic accept thresholds: name_sim .91, title .89 and a moved position all fall through"
  ) {
    List(
      SameWork + ("name_sim"           -> 0.91),
      SameWork + ("work_title_sim"     -> 0.89),
      SameWork + ("position_agreement" -> 0.0),
      SameWork + ("family_exact"       -> 0.0),
    ).foreach { f =>
      val r = Policy.decide(R, f, Thr)
      assertEquals(r.path, MatchPath.Probabilistic, s"features $f")
    }
  }

  test("deterministic reject: same family but conflicting initials") {
    val r = Policy.decide(R, Map("family_exact" -> 1.0, "initials_compat" -> 0.0), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.path, MatchPath.Deterministic)
    assertEquals(r.rulesFired, List("researcher_initial_conflict"))
    assertEquals(r.confidence, 0.90)
  }

  test("deterministic reject: different family and name_sim < .6; exactly .6 falls through") {
    val r = Policy.decide(R, Map("family_exact" -> 0.0, "name_sim" -> 0.59), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.rulesFired, List("researcher_family_mismatch"))
    val edge = Policy.decide(R, Map("family_exact" -> 0.0, "name_sim" -> 0.6), Thr)
    assertEquals(edge.path, MatchPath.Probabilistic)
    // .28 * .6 + .72 * .5 = .528
    assert(close(edge.score, 0.528))
  }

  test("work deterministic accept: exact title, same year, author overlap >= .8, no mixed script") {
    val ok = Map("title_exact" -> 1.0, "year_distance" -> 0.0, "author_overlap" -> 0.8)
    val r  = Policy.decide(W, ok, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.rulesFired, List("work_title_year_authors_exact"))
    List(
      ok + ("author_overlap"      -> 0.79),
      ok + ("year_distance"       -> 1.0),
      ok + ("title_exact"         -> 0.0),
      ok + ("author_mixed_script" -> 1.0),
    ).foreach(f => assertEquals(Policy.decide(W, f, Thr).path, MatchPath.Probabilistic, s"$f"))
  }

  test("work deterministic reject: title_sim < .40; exactly .40 falls through") {
    val r = Policy.decide(W, Map("title_sim" -> 0.39), Thr)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.rulesFired, List("work_title_dissimilar"))
    assertEquals(Policy.decide(W, Map("title_sim" -> 0.40), Thr).path, MatchPath.Probabilistic)
  }

  // ---- adjudication override rules ----------------------------------------------------------

  private val Unres = PolicyResult(
    ResolutionOutcome.Unresolved,
    MatchPath.Probabilistic,
    0.7,
    0.5,
    List("score_between_thresholds"),
    List("llm_band"),
    llmEligible = true,
  )

  private def adj(
      o: AdjudicationOutcome,
      conf: Double,
      codes: List[String] = List("same_orcid"),
  ): AdjudicationRecord =
    AdjudicationRecord(
      SchemaVersion.Current,
      "mention:a",
      "mention:b",
      EntityKind.Researcher,
      o,
      conf,
      if o == AdjudicationOutcome.Abstain then Nil else List("left.name"),
      codes,
      "er-adjudication-v1",
      "claude-test",
      "er-v1",
      Hex,
      prov(),
    )

  test("applyAdjudication: a confident accept turns UNRESOLVED into ACCEPTED on path llm") {
    val r = Policy.applyAdjudication(Unres, Some(adj(AdjudicationOutcome.Accept, 0.9)), 0.7)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.path, MatchPath.Llm)
    assertEquals(r.confidence, 0.9)
    assertEquals(r.rulesFired, List("llm_adjudication"))
    assertEquals(r.reasonCodes, List("same_orcid"))
    assertEquals(r.modelVersion, Some("claude-test|er-adjudication-v1"))
    assert(!r.llmEligible)
  }

  test("applyAdjudication: a confident reject turns UNRESOLVED into REJECTED on path llm") {
    val r = Policy.applyAdjudication(Unres, Some(adj(AdjudicationOutcome.Reject, 0.8)), 0.7)
    assertEquals(r.outcome, ResolutionOutcome.Rejected)
    assertEquals(r.path, MatchPath.Llm)
  }

  test("applyAdjudication: confidence == min-confidence applies, just below stays UNRESOLVED") {
    val at = Policy.applyAdjudication(Unres, Some(adj(AdjudicationOutcome.Accept, 0.7)), 0.7)
    assertEquals(at.outcome, ResolutionOutcome.Accepted)
    val below = Policy.applyAdjudication(Unres, Some(adj(AdjudicationOutcome.Accept, 0.69)), 0.7)
    assertEquals(below.outcome, ResolutionOutcome.Unresolved)
    assertEquals(below.path, MatchPath.Probabilistic)
    assertEquals(below.reasonCodes, List("llm_band", "llm_low_confidence"))
  }

  test("applyAdjudication: abstain keeps UNRESOLVED and records llm_abstain") {
    val r = Policy.applyAdjudication(Unres, Some(adj(AdjudicationOutcome.Abstain, 0.99)), 0.7)
    assertEquals(r.outcome, ResolutionOutcome.Unresolved)
    assertEquals(r.reasonCodes, List("llm_band", "llm_abstain"))
    assertEquals(r.modelVersion, None)
  }

  test("SAFETY: CONFLICT is never overridden by an adjudication accept") {
    // HJ3 evidence makes a Mid pair CONFLICT; remove the guard and the LLM accept would win
    val conflict = Policy.decide(R, Mid + ("exact_id_conflict" -> 1.0), Thr)
    assertEquals(conflict.outcome, ResolutionOutcome.Conflict)
    val after = Policy.applyAdjudication(conflict, Some(adj(AdjudicationOutcome.Accept, 1.0)), 0.0)
    assertEquals(after, conflict)
    assertEquals(after.outcome, ResolutionOutcome.Conflict)
    // even if a caller wrongly marks the conflict LLM-eligible, the outcome guard holds
    val forced = conflict.copy(llmEligible = true)
    val again  = Policy.applyAdjudication(forced, Some(adj(AdjudicationOutcome.Accept, 1.0)), 0.0)
    assertEquals(again.outcome, ResolutionOutcome.Conflict)
    assertEquals(again.path, MatchPath.ExactId)
    // the ORCID-plus-same-position CONFLICT and the H1 name-mismatch CONFLICT are protected alike
    val orcidPos =
      Policy.decide(R, Mid ++ Map("orcid_agreement" -> 0.0, "position_agreement" -> 1.0), Thr)
    val mismatch = Policy.decide(
      R,
      Mid ++ Map("orcid_agreement" -> 1.0, "family_exact" -> 0.0, "name_sim" -> 0.5),
      Thr,
    )
    List(orcidPos, mismatch).foreach { c =>
      assertEquals(c.outcome, ResolutionOutcome.Conflict)
      val forcedC = c.copy(llmEligible = true)
      val out = Policy.applyAdjudication(forcedC, Some(adj(AdjudicationOutcome.Accept, 1.0)), 0.0)
      assertEquals(out.outcome, ResolutionOutcome.Conflict)
      assertEquals(out.path, MatchPath.ExactId)
    }
  }

  test("SAFETY: an ORCID-disagreement REJECTED is not overridden by an adjudication accept (M1)") {
    val rej = Policy.decide(R, Mid + ("orcid_agreement" -> 0.0), Thr)
    assertEquals(rej.outcome, ResolutionOutcome.Rejected)
    val forced = rej.copy(llmEligible = true)
    val out    = Policy.applyAdjudication(forced, Some(adj(AdjudicationOutcome.Accept, 1.0)), 0.0)
    assertEquals(out.outcome, ResolutionOutcome.Rejected)
    assertEquals(out.path, MatchPath.ExactId)
    val c = cand("mention:a", "mention:b", R, Mid + ("orcid_agreement" -> 0.0))
    val d = Resolver.decide(c, cfg, Some(adj(AdjudicationOutcome.Accept, 1.0)))
    assertEquals(d.outcome, ResolutionOutcome.Rejected)
  }

  test("SAFETY: CONFLICT stays CONFLICT end to end through Resolver.decide with a cached accept") {
    val c = cand(
      "mention:a",
      "mention:b",
      R,
      Mid + ("exact_id_conflict" -> 1.0),
    )
    val d = Resolver.decide(c, cfg, Some(adj(AdjudicationOutcome.Accept, 1.0)))
    assertEquals(d.outcome, ResolutionOutcome.Conflict)
    // the same features without the HJ3 evidence DO accept through the LLM
    val ok = cand("mention:a", "mention:b", R, Mid)
    val e  = Resolver.decide(ok, cfg, Some(adj(AdjudicationOutcome.Accept, 1.0)))
    assertEquals(e.outcome, ResolutionOutcome.Accepted)
    assertEquals(e.path, MatchPath.Llm)
  }

  test(
    "applyAdjudication: decided results, non-eligible results and no adjudication are unchanged"
  ) {
    val accepted = Policy.decide(W, Map("doi_agreement" -> 1.0), Thr)
    val rejected = Policy.decide(R, Map("family_exact" -> 1.0, "initials_compat" -> 0.0), Thr)
    val notElig  = Unres.copy(llmEligible = false)
    val a        = Some(adj(AdjudicationOutcome.Reject, 1.0))
    assertEquals(Policy.applyAdjudication(accepted, a, 0.0), accepted)
    assertEquals(
      Policy.applyAdjudication(rejected, Some(adj(AdjudicationOutcome.Accept, 1.0)), 0.0),
      rejected,
    )
    assertEquals(Policy.applyAdjudication(notElig, a, 0.0), notElig)
    assertEquals(Policy.applyAdjudication(Unres, None, 0.7), Unres)
  }

  // ---- Decisions.build ------------------------------------------------------------------------

  test("Decisions.build records score, thresholds, policy version and a deterministic timestamp") {
    val c = cand("mention:a", "mention:b", R, Mid)
    val r = Policy.decide(R, c.features, cfg.thresholds(R))
    val d = Decisions.build(c, r, cfg, "run:" + Hex, prov()).fold(e => fail(e.toString), identity)
    assertEquals(d.outcome, ResolutionOutcome.Unresolved)
    assertEquals(d.policyVersion, "er-v1")
    assertEquals(d.decisionTimestamp, "2026-01-01T00:00:00Z")
    assert(close(d.features("policy.score"), 0.79))
    assertEquals(d.features("policy.threshold.accept"), 0.85)
    assertEquals(d.features("policy.threshold.reject"), 0.45)
    assertEquals(d.features("policy.threshold.band-low"), 0.55)
    assertEquals(d.features("policy.threshold.band-high"), 0.80)
    assertEquals(d.features("name_sim"), 1.0)
  }

  test("Decisions.build uses the candidate's retrievedAt when present, never the wall clock") {
    val c = cand("mention:a", "mention:b", R, Mid, Some("2026-03-04T05:06:07Z"))
    val r = Policy.decide(R, c.features, cfg.thresholds(R))
    val d = Decisions.build(c, r, cfg, "run:" + Hex, prov()).fold(e => fail(e.toString), identity)
    assertEquals(d.decisionTimestamp, "2026-03-04T05:06:07Z")
  }

  test("Decisions.build: an llm-path decision carries the modelVersion, a non-llm one cannot") {
    val c = cand("mention:a", "mention:b", R, Mid)
    val r = Resolver.decide(c, cfg, Some(adj(AdjudicationOutcome.Accept, 0.95)))
    val d = Decisions.build(c, r, cfg, "run:" + Hex, prov()).fold(e => fail(e.toString), identity)
    assertEquals(d.matchPath, MatchPath.Llm)
    assertEquals(d.modelVersion, Some("claude-test|er-adjudication-v1"))
    val bad = Decisions.build(c, r.copy(path = MatchPath.Probabilistic), cfg, "run:" + Hex, prov())
    assert(bad.isLeft, "modelVersion on a non-llm path must be rejected by the contract")
  }
