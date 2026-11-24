package scholarchain.rag.runtime

import scholarchain.core.*

/** Phase 10 C: the Phase 09 carry-overs N5, N6, N7 and L2, with the VerifierRound2Suite fixtures. A
  * failure is a production defect in `ClaimVerificationAgent` or `AnthropicModelClient` (owner
  * rag-engineer).
  */
class Phase10CarryOverSuite extends munit.FunSuite:
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

  // ---- Phase 10 C: carry-overs N5, N6, N7, L2 (rag-engineer's observed verdicts) ------------

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

  private lazy val graceM    = evOf(Fact.Metrics, grace, Some(W))
  private lazy val edgeCites = List(edgeItem._1.evidenceId, adaId, alanId)

  test("fixture values: ada 1 coauthor 7 publications, alan 2 and 5, joint 2, W2 1 to 5") {
    assertEquals(fld(Fact.Metrics, ada, Some(W), "coauthorCount").num.toInt, 1)
    assertEquals(fld(Fact.Metrics, ada, Some(W), "publicationCount").num.toInt, 7)
    assertEquals(fld(Fact.Metrics, alan, Some(W), "coauthorCount").num.toInt, 2)
    assertEquals(fld(Fact.Metrics, alan, Some(W), "publicationCount").num.toInt, 5)
    assertEquals(fld(Fact.Metrics, grace, Some(W), "coauthorCount").num.toInt, 8)
    assertEquals(edgeN, 2)
    assertEquals((prev, cur), (1, 5))
  }

  /** F8: a rejected claim is Unsupported or Conflicting (never Verified or partially supported),
    * the verifier reports the expected reason code, and the claim is not kept in the answer.
    */
  private def expectRejected(c: ProposedClaim, codes: String*): Unit =
    val r = det(c)
    assert(
      r.status == Unsupported || r.status == Conflicting,
      s"expected Unsupported or Conflicting, got ${r.status}: ${r.reasonCodes}",
    )
    assert(
      codes.exists(r.reasonCodes.contains),
      s"expected one of ${codes.mkString(",")} in ${r.reasonCodes}",
    )
    val v = new ClaimVerificationAgent(None, prompts, false)
      .verify("query:" + Hashing.textHash("f8"), set, List(c))
    assertEquals(v.kept, Nil, s"claim kept: ${c.text}")
    assertEquals(v.removed.size, 1)
    assertEquals(v.removed.head.verification.status, r.status)
    assert(v.removed.head.verification.reasonCodes.nonEmpty)

  test("N5: a joint count stated as one researcher's total publications is not verified") {
    expectRejected(
      claim(
        s"In window $W, $ada, who coauthored with $alan, has 2 publications.",
        edgeCites,
        edgeAsserts*
      ),
      "number_without_matching_assertion",
    )
  }

  test(
    "N5: the reverse, a total stated as a joint count with metrics assertions, is not verified"
  ) {
    expectRejected(
      claim(
        s"$ada and $alan coauthored 7 works.",
        List(adaM, adaId, alanId),
        metricAssert(ada, "publicationCount", 7),
      ),
      "number_without_matching_assertion",
    )
  }

  test("N5: control, the joint count stated as joint publications with edge assertions verifies") {
    expect(
      claim(s"In window $W, $ada and $alan have 2 joint publications.", edgeCites, edgeAsserts*),
      Verified,
    )
  }

  test("N6: a third researcher's coauthor count attached to the first is not verified") {
    expectRejected(
      claim(
        s"$grace is among the 8 coauthors of $ada.",
        List(graceM, graceId, adaId),
        metricAssert(grace, "coauthorCount", 8),
      ),
      "number_without_matching_assertion",
    )
  }

  test("N6: control, 'a coauthor of ada has 2 distinct coauthors' with edge and metrics verifies") {
    expect(
      claim(
        s"In window $W, $alan, a coauthor of $ada, has 2 distinct coauthors.",
        alanM :: edgeCites,
        (edgeAsserts :+ metricAssert(alan, "coauthorCount", 2))*
      ),
      Verified,
    )
  }

  private lazy val cmpCites = List(adaM, alanM, adaId, alanId)

  private lazy val cmpAsserts =
    List(metricAssert(ada, "coauthorCount", 1), metricAssert(alan, "coauthorCount", 2))

  test("N7: 'ada has 1 distinct coauthors, more than alan' is conflicting") {
    expect(
      claim(
        s"In window $W, $ada has 1 distinct coauthors, more than $alan.",
        cmpCites,
        cmpAsserts*
      ),
      Conflicting,
    )
  }

  test("N7: 'alan has twice as many coauthors as ada' is verified") {
    expect(
      claim(
        s"In window $W, $alan has twice as many coauthors as $ada.",
        cmpCites,
        cmpAsserts*
      ),
      Verified,
    )
  }

  test("N7: 'ada fell from 1 to 5 distinct coauthors' is conflicting") {
    expect(
      temporalClaim(s"In window $W2, $ada fell from $prev to $cur distinct coauthors."),
      Conflicting,
    )
  }

  test("N7: 'ada increased from 1 to 5 distinct coauthors' is verified") {
    expect(
      temporalClaim(s"In window $W2, $ada increased from $prev to $cur distinct coauthors."),
      Verified,
    )
  }

  // ---- L2 ----------------------------------------------------------------------------------

  private def anthropic(keyVar: String) =
    new AnthropicModelClient("claude-test", "https://api.example.invalid/v1/messages", keyVar)

  test("L2: ANTHROPIC_API_KEY is rejected and the message names neither variable") {
    val e = intercept[IllegalArgumentException](anthropic("ANTHROPIC_API_KEY"))
    assert(!e.getMessage.contains("ANTHROPIC_API_KEY"), e.getMessage)
    assert(!e.getMessage.contains("X_KEY"), e.getMessage)
  }

  test("L2: X_KEY is rejected and the message names neither variable") {
    val e = intercept[IllegalArgumentException](anthropic("X_KEY"))
    assert(!e.getMessage.contains("ANTHROPIC_API_KEY"), e.getMessage)
    assert(!e.getMessage.contains("X_KEY"), e.getMessage)
  }

  test("L2: SCHOLARCHAIN_ANTHROPIC_API_KEY constructs") {
    assertEquals(anthropic("SCHOLARCHAIN_ANTHROPIC_API_KEY").provider, ModelProvider.Anthropic)
  }
