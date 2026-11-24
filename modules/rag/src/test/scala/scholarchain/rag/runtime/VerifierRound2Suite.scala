package scholarchain.rag.runtime

import scholarchain.core.*

/** Phase 09 repair 02, review 2 findings N1 to N4 and deviation (b): the adversarial inputs against
  * the deterministic claim verifier, one test per input with the exact status and reason code the
  * repaired rules require. A failure here is a production defect in `ClaimVerificationAgent` (owner
  * rag-engineer), not a test defect.
  */
class VerifierRound2Suite extends munit.FunSuite:
  import World.*
  import ClaimVerificationStatus.*

  private val names = List("Ada Lovelace", "Alan Turing", "Grace Hopper", "Hedy Lamarr")

  private lazy val reader0 = reader(World.kv)

  private lazy val identities: List[EvidenceDraft] =
    new ResearcherResolverAgent(reader0, vectors(new MemObjects), embedder)
      .resolve(planOf("who", names, List(QueryOperationV2.ResearcherMetricsLookup)))
      .evidence
      .drafts

  private lazy val metricsAgent = new CollaborationMetricsAgent(reader0)
  private lazy val pathFinder   = new PathFinderAgent(World.graph, reader0, settings)

  private lazy val communityAgent =
    new CommunityAnalystAgent(World.graph, reader0, metricsAgent, settings)

  private lazy val set: EvidenceSet = evidence(
    identities ++
      metricsAgent.metrics(List(ada, alan, grace, hedy), W).drafts ++
      metricsAgent.temporal(List(ada), W2, W).drafts ++
      metricsAgent.ranking(List(ada, alan, grace, hedy), W, "coauthorCount").drafts ++
      pathFinder.shortestPath(ada, grace, W, 4).stage.drafts ++
      pathFinder.directCollaborators(alan, W, 10).stage.drafts ++
      communityAgent.community(ada, W).drafts ++
      communityAgent.component(ada, W).drafts ++
      communityAgent.degreeVsBridge(grace, W).drafts
  )

  private def facts: List[(EvidenceItem, Fact)] =
    set.bundle.items.flatMap(i => i.text.flatMap(Fact.parse).map(i -> _))

  private def factOf(kind: String, subject: String, window: Option[String]) =
    facts
      .find((_, f) => f.kind == kind && f.subject == subject && f.window == window)
      .getOrElse(throw new AssertionError(s"no $kind fact for $subject in $window"))

  private def evOf(kind: String, subject: String, window: Option[String]): String =
    factOf(kind, subject, window)._1.evidenceId

  private def fld(kind: String, subject: String, window: Option[String], field: String) =
    factOf(kind, subject, window)._2.fields.value(field)

  private lazy val adaId   = evOf(Fact.Identity, ada, None)
  private lazy val alanId  = evOf(Fact.Identity, alan, None)
  private lazy val graceId = evOf(Fact.Identity, grace, None)
  private lazy val adaM    = evOf(Fact.Metrics, ada, Some(W))
  private lazy val alanM   = evOf(Fact.Metrics, alan, Some(W))
  private lazy val pathKey = s"path:$W:$ada:$grace"
  private lazy val pathEv  = evOf(Fact.Path, pathKey, Some(W))

  private def det(c: ProposedClaim): DeterministicVerdict =
    new ClaimVerificationAgent(None, prompts, false).deterministic(set, c)

  private def a(
      kind: String,
      subject: String,
      window: Option[String],
      field: String,
      v: ujson.Value,
  ) =
    ProposedAssertion(kind, subject, window, field, v)

  private def metricAssert(subject: String, field: String, value: Int, w: String = W) =
    a(Fact.Metrics, subject, Some(w), field, ujson.Num(value.toDouble))

  private def claim(text: String, cites: List[String], asserts: ProposedAssertion*) =
    ProposedClaim(text, cites, asserts.toList)

  private def expect(c: ProposedClaim, status: ClaimVerificationStatus, codes: String*) =
    val r = det(c)
    assertEquals(r.status, status, s"${c.text} => ${r.reasonCodes}")
    codes.foreach(code =>
      assert(r.reasonCodes.contains(code), s"missing reason $code in ${r.reasonCodes}")
    )
    r

  // ---- shared fixtures --------------------------------------------------------------------

  private lazy val edgeItem = facts
    .find((_, f) =>
      f.kind == Fact.CoauthorEdge &&
        Set("researcherA", "researcherB").flatMap(k => f.fields.value.get(k).flatMap(_.strOpt)) ==
        Set(ada, alan)
    )
    .getOrElse(fail("no ada-alan edge fact"))

  private lazy val edgeN = edgeItem._2.fields.value("jointPublicationCount").num.toInt

  private def edgeAssert(field: String, v: ujson.Value) =
    a(Fact.CoauthorEdge, edgeItem._2.subject, edgeItem._2.window, field, v)

  private lazy val edgeAsserts = List(
    edgeAssert("researcherA", edgeItem._2.fields.value("researcherA")),
    edgeAssert("researcherB", edgeItem._2.fields.value("researcherB")),
    edgeAssert("jointPublicationCount", ujson.Num(edgeN.toDouble)),
  )

  private def pathA(field: String, v: ujson.Value) = a(Fact.Path, pathKey, Some(W), field, v)

  private def storedArr(kind: String, subject: String, w: Option[String], field: String) =
    fld(kind, subject, w, field).arr.toList

  private lazy val pathEdges = storedArr(Fact.Path, pathKey, Some(W), "edgeIds").map(_.str)

  private lazy val pathWorks =
    storedArr(Fact.Path, pathKey, Some(W), "hopWorkIds").map(_.arr.toList.map(_.str))

  private lazy val pathAsserts = List(
    pathA("found", ujson.Bool(true)),
    pathA("length", ujson.Num(2)),
    pathA("hops", ujson.Arr(ada, alan, grace)),
    pathA("edgeIds", ujson.Arr.from(pathEdges.map(ujson.Str(_)))),
    pathA("hopWorkIds", ujson.Arr.from(pathWorks.map(ws => ujson.Arr.from(ws.map(ujson.Str(_)))))),
  )

  private lazy val pathCites = List(pathEv, adaId, alanId, graceId)

  private def pathLine(tail: String) =
    s"The shortest collaboration path in window $W has length 2: $ada -> $alan -> $grace; $tail"

  // ---- N1a: bridge / hub wording ----------------------------------------------------------

  private lazy val dvb = evOf(Fact.DegreeVsBridge, grace, Some(W))

  private lazy val cls = fld(Fact.DegreeVsBridge, grace, Some(W), "classification").str

  private def clsAssert = a(Fact.DegreeVsBridge, grace, Some(W), "classification", ujson.Str(cls))

  private def clsClaim(text: String) = claim(text, List(dvb, graceId), clsAssert)

  test("fixture sanity: the stored values the round-2 tests lean on") {
    assertEquals(cls, "bridge")
    assert(edgeN >= 1)
    assertEquals(pathEdges.size, 2)
    assertEquals(pathWorks.size, 2)
  }

  test("N1a: 'is not a bridge' against classification=bridge is conflicting") {
    expect(
      clsClaim(s"In window $W, $grace is not a bridge."),
      Conflicting,
      "classification_contradicts_assertion",
    )
  }

  test("N1a: 'is a hub' against classification=bridge is conflicting") {
    expect(
      clsClaim(s"In window $W, $grace is a hub."),
      Conflicting,
      "classification_contradicts_assertion",
    )
  }

  test("N1a: control 'is a bridge' verifies") {
    expect(clsClaim(s"In window $W, $grace is a bridge."), Verified)
  }

  test("N1a: control 'not a local hub' verifies") {
    expect(clsClaim(s"In window $W, $grace is not a local hub."), Verified)
  }

  // ---- N1b: path negation -----------------------------------------------------------------

  test("N1b: 'No collaboration path ... was found' against found=true is conflicting") {
    expect(
      claim(
        s"No collaboration path between $ada and $grace was found in window $W.",
        pathCites,
        pathA("found", ujson.Bool(true)),
        pathA("from", ujson.Str(ada)),
        pathA("to", ujson.Str(grace)),
      ),
      Conflicting,
      "negation_contradicts_assertion",
    )
  }

  // ---- N1c: edge negation -----------------------------------------------------------------

  test("N1c: 'never coauthored' against a true edge is conflicting; the true claim verifies") {
    val cites = List(edgeItem._1.evidenceId, adaId, alanId)
    expect(
      claim(s"$ada and $alan have never coauthored.", cites, edgeAsserts*),
      Conflicting,
      "negation_contradicts_assertion",
    )
    expect(
      claim(s"$ada and $alan coauthored $edgeN works in window $W.", cites, edgeAsserts*),
      Verified,
    )
  }

  // ---- N1d: ordinal wording ---------------------------------------------------------------

  private lazy val rankingFact = facts
    .find((_, f) => f.kind == Fact.Ranking)
    .getOrElse(fail("no ranking fact"))

  private lazy val order = rankingFact._2.fields.value("order").arr.toList.map(_.str)

  private def rankClaim(who: String) =
    claim(
      s"In window $W, $who is ranked first by coauthor count.",
      List(rankingFact._1.evidenceId, evOf(Fact.Identity, who, None)),
      a(
        Fact.Ranking,
        rankingFact._2.subject,
        rankingFact._2.window,
        "order",
        ujson.Arr.from(order.map(ujson.Str(_))),
      ),
    )

  test("N1d: the last researcher in `order` 'ranked first' is conflicting") {
    expect(rankClaim(order.last), Conflicting, "rank_wording_contradicts_assertion")
  }

  test("N1d: control, the first researcher in `order` 'ranked first' verifies") {
    expect(rankClaim(order.head), Verified)
  }

  // ---- N2: number ownership ---------------------------------------------------------------

  test("N2a: a joint publication count given to one researcher as 'publications' is unsupported") {
    val cites = List(edgeItem._1.evidenceId, adaId, alanId)
    expect(
      claim(s"In window $W, $ada has $edgeN publications.", cites, edgeAsserts*),
      Unsupported,
      "number_without_matching_assertion",
    )
  }

  test("N2b: two researchers' counts attached to the wrong owner are unsupported") {
    val cites   = List(adaM, alanM, adaId, alanId)
    val asserts =
      List(metricAssert(ada, "coauthorCount", 1), metricAssert(alan, "coauthorCount", 2))
    expect(
      claim(
        s"In window $W, 2 distinct coauthors: $ada; 1 distinct coauthors: $alan.",
        cites,
        asserts*
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    expect(
      claim(
        s"In window $W, $ada has 2 distinct coauthors; $alan has 1 distinct coauthors.",
        cites,
        asserts*
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    // control: each number follows its owner
    expect(
      claim(
        s"In window $W, $ada has 1 distinct coauthors; $alan has 2 distinct coauthors.",
        cites,
        asserts*
      ),
      Verified,
    )
  }

  // ---- N3: field disambiguation -----------------------------------------------------------

  private lazy val prev = fld(Fact.TemporalChange, ada, Some(W2), "prevCoauthorCount").num.toInt
  private lazy val cur  = fld(Fact.TemporalChange, ada, Some(W2), "coauthorCount").num.toInt

  private lazy val temporalEv = evOf(Fact.TemporalChange, ada, Some(W2))

  private def temporalClaim(text: String) =
    claim(
      text,
      List(temporalEv, adaId),
      a(Fact.TemporalChange, ada, Some(W2), "coauthorCount", ujson.Num(cur.toDouble)),
      a(Fact.TemporalChange, ada, Some(W2), "prevCoauthorCount", ujson.Num(prev.toDouble)),
    )

  test(
    "N3a: 'from X to Y coauthors' with the numbers swapped is unsupported; in order it verifies"
  ) {
    assertNotEquals(prev, cur)
    expect(
      temporalClaim(s"In window $W2, $ada went from $cur to $prev coauthors."),
      Unsupported,
      "number_without_matching_assertion",
    )
    expect(temporalClaim(s"In window $W2, $ada went from $prev to $cur coauthors."), Verified)
  }

  private lazy val avgT = fld(Fact.Metrics, ada, Some(W), "avgTeamSize").num.toInt
  private lazy val maxT = fld(Fact.Metrics, ada, Some(W), "maxTeamSize").num.toInt

  private def teamClaim(text: String) =
    claim(
      text,
      List(adaM),
      metricAssert(ada, "avgTeamSize", avgT),
      metricAssert(ada, "maxTeamSize", maxT),
    )

  test("N3b: swapped average and maximum team sizes are unsupported") {
    assertNotEquals(avgT, maxT)
    expect(
      teamClaim(
        s"In window $W, $ada has an average team size of $maxT and a maximum team size of $avgT."
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
  }

  test("N3b: 'average team' with the maximum value is unsupported; the true pair verifies") {
    expect(
      claim(
        s"In window $W, $ada's average team has $maxT members.",
        List(adaM),
        metricAssert(ada, "maxTeamSize", maxT),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    expect(
      teamClaim(
        s"In window $W, $ada has an average team size of $avgT and a maximum team size of $maxT."
      ),
      Verified,
    )
  }

  // ---- N4: hop checks ---------------------------------------------------------------------

  test("N4: 'its first hop is <edge of hop 2>' is conflicting") {
    expect(
      claim(pathLine(s"its first hop is ${pathEdges(1)}."), pathCites, pathAsserts*),
      Conflicting,
      "hop_evidence_mismatch",
    )
  }

  test("N4: 'its first hop is <hop-1 edge>' verifies") {
    expect(claim(pathLine(s"its first hop is ${pathEdges(0)}."), pathCites, pathAsserts*), Verified)
  }

  test("N4: 'hop 2 is <edge 2>' verifies") {
    expect(claim(pathLine(s"hop 2 is ${pathEdges(1)}."), pathCites, pathAsserts*), Verified)
  }

  test("N4: 'hop 1 is supported by <hop-2 work>' is conflicting; the hop-1 work verifies") {
    expect(
      claim(pathLine(s"hop 1 is supported by ${pathWorks(1).head}."), pathCites, pathAsserts*),
      Conflicting,
      "hop_evidence_mismatch",
    )
    expect(
      claim(pathLine(s"hop 1 is supported by ${pathWorks(0).head}."), pathCites, pathAsserts*),
      Verified,
    )
  }

  test(
    "N4: an edge ID adjacent to the wrong arrow endpoints is conflicting; the right one verifies"
  ) {
    def adjacent(e: String) =
      s"The shortest collaboration path in window $W has length 2: " +
        s"$ada -> $alan ($e) -> $grace."
    expect(
      claim(adjacent(pathEdges(1)), pathCites, pathAsserts*),
      Conflicting,
      "hop_evidence_mismatch",
    )
    expect(claim(adjacent(pathEdges(0)), pathCites, pathAsserts*), Verified)
  }

  test(
    "N4: a hop edge stated without asserted edgeIds is unsupported (hop_evidence_not_asserted)"
  ) {
    val noEdges = pathAsserts.filterNot(x => x.field == "edgeIds" || x.field == "hopWorkIds")
    expect(
      claim(pathLine(s"its first hop is ${pathEdges(0)}."), pathCites, noEdges*),
      Unsupported,
      "hop_evidence_not_asserted",
    )
  }

  // ---- deviation (b) ----------------------------------------------------------------------

  test("(b): a pair relation with no assertion is unsupported") {
    expect(
      claim(s"$ada and $alan are direct coauthors.", List(adaM, alanId)),
      Unsupported,
      "reference_without_assertion",
    )
  }

  test("(b): a pair relation with a one-sided assertion is unsupported") {
    expect(
      claim(
        s"$ada and $alan are direct coauthors.",
        List(adaM, alanId),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Unsupported,
      "relation_without_assertion",
    )
  }

  test("(b): a third researcher woven into a pair relation is unsupported") {
    expect(
      claim(
        s"$ada and ${hedy} coauthored $edgeN works in window $W.",
        List(edgeItem._1.evidenceId, adaId, evOf(Fact.Identity, hedy, None)),
        edgeAsserts*
      ),
      Unsupported,
      "relation_without_assertion",
    )
  }

  test("(b): a wrong display name beside an ID is conflicting") {
    expect(
      claim(
        s"In window $W, Grace Hopper ($ada) has 1 distinct coauthors.",
        List(adaM, adaId),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Conflicting,
      "display_name_mismatch",
    )
    expect(
      claim(
        s"In window $W, Grace Hopper ($ada) has 1 distinct coauthors.",
        List(adaM, adaId, graceId),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Conflicting,
      "display_name_mismatch",
    )
  }

  // ---- controls that stay partially supported ----------------------------------------------

  test("partial control: an unverified proper noun stays partially supported") {
    expect(
      claim(
        s"In window $W, $ada has 1 distinct coauthors, including Marie Curie.",
        List(adaM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      PartiallySupported,
      "unverified_proper_noun",
    )
  }

  test("partial control: no entity and no assertion stays partially supported") {
    expect(
      claim("The collaboration network is active.", List(adaM)),
      PartiallySupported,
      "qualitative_wording_unchecked",
    )
  }
