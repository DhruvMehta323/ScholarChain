package scholarchain.rag.runtime

import scholarchain.core.*

/** Phase 09 repair 01, finding M4: the adversarial inputs of review findings H1, H2 and H3 against
  * the deterministic claim verifier, and the new assertions of the template synthesizer (M1). Each
  * test states the status the verifier rules require (never VERIFIED for a fabricated or unbound
  * claim). A failure here is a production defect in `ClaimVerificationAgent` or
  * `TemplateSynthesizer` (owner rag-engineer), not a test defect.
  */
class VerifierRepairSuite extends munit.FunSuite:
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

  private lazy val pathFinder = new PathFinderAgent(World.graph, reader0, settings)

  private lazy val communityAgent =
    new CommunityAnalystAgent(World.graph, reader0, metricsAgent, settings)

  /** Identity facts, metrics of the four researchers, a path, a neighborhood, a ranking, a temporal
    * change, a community and a component: every deterministic kind the verifier can be asked about.
    */
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

  private def evOf(kind: String, subject: String, window: Option[String]): String =
    facts
      .find((_, f) => f.kind == kind && f.subject == subject && f.window == window)
      .map(_._1.evidenceId)
      .getOrElse(throw new AssertionError(s"no $kind fact for $subject in $window"))

  private lazy val adaId      = evOf(Fact.Identity, ada, None)
  private lazy val alanId     = evOf(Fact.Identity, alan, None)
  private lazy val graceId    = evOf(Fact.Identity, grace, None)
  private lazy val hedyId     = evOf(Fact.Identity, hedy, None)
  private lazy val adaM       = evOf(Fact.Metrics, ada, Some(W))
  private lazy val alanM      = evOf(Fact.Metrics, alan, Some(W))
  private lazy val pathKey    = s"path:$W:$ada:$grace"
  private lazy val pathEv     = evOf(Fact.Path, pathKey, Some(W))
  private lazy val temporalEv = evOf(Fact.TemporalChange, ada, Some(W2))

  private def verifier(llm: Option[FakeModelClient] = None) =
    new ClaimVerificationAgent(llm.map(caller(_)), prompts, llm.isDefined)

  private def det(c: ProposedClaim): DeterministicVerdict = verifier().deterministic(set, c)

  private def a(
      kind: String,
      subject: String,
      window: Option[String],
      field: String,
      value: ujson.Value,
  ) = ProposedAssertion(kind, subject, window, field, value)

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

  private def stored(kind: String, subject: String, window: Option[String], field: String) =
    facts
      .find((_, f) => f.kind == kind && f.subject == subject && f.window == window)
      .flatMap(_._2.fields.value.get(field))
      .getOrElse(throw new AssertionError(s"no stored $field for $subject"))

  private def storedInt(kind: String, subject: String, window: Option[String], field: String) =
    stored(kind, subject, window, field).num.toInt

  test("fixture sanity: the stored values the tests lean on") {
    assertEquals(storedInt(Fact.Metrics, ada, Some(W), "coauthorCount"), 1)
    assertEquals(storedInt(Fact.Metrics, ada, Some(W), "publicationCount"), 7)
    assertEquals(storedInt(Fact.Metrics, ada, Some(W), "maxTeamSize"), 6)
    assertEquals(storedInt(Fact.Metrics, alan, Some(W), "coauthorCount"), 2)
    // control: a text that states exactly stored values, name and ID, verifies
    expect(
      claim(
        s"In window $W, Ada Lovelace ($ada) has 1 distinct coauthors.",
        List(adaM, adaId),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Verified,
      "deterministic_checks_passed",
    )
  }

  // ---- H2: claims with entity references but no (or too few) assertions -----------------------

  test("H2: 'A and B are direct coauthors' citing A's metrics and B's identity is not verified") {
    val c = claim(s"$ada and $alan are direct coauthors.", List(adaM, alanId))
    val r = expect(c, Unsupported, "reference_without_assertion")
    assertNotEquals(r.status, Verified)
    // end to end the claim is removed from the answer
    val v = verifier().verify("query:" + Hashing.textHash("h2a"), set, List(c))
    assertEquals(v.all.head.verification.status, Unsupported)
    assertEquals(v.kept, Nil)
    assertEquals(v.removed.size, 1)
  }

  test("H2: a pair relation with an assertion about only one of the two is capped") {
    val c = claim(
      s"$ada and $alan are direct coauthors.",
      List(adaM, alanId),
      metricAssert(ada, "coauthorCount", 1),
    )
    expect(c, Unsupported, "relation_without_assertion")
  }

  test("H2: a pair relation over two researchers needs an assertion covering both") {
    val edge = facts
      .find((i, f) =>
        f.kind == Fact.CoauthorEdge && f.fields.value
          .get("researcherA")
          .toList
          .flatMap(_.strOpt)
          .toSet
          .union(f.fields.value.get("researcherB").toList.flatMap(_.strOpt).toSet) == Set(ada, alan)
      )
      .getOrElse(fail("no ada-alan edge fact"))
    val f                                = edge._2
    val n                                = f.fields.value("jointPublicationCount").num.toInt
    def e(field: String, v: ujson.Value) = a(Fact.CoauthorEdge, f.subject, f.window, field, v)
    val edgeAsserts                      = List(
      e("researcherA", f.fields.value("researcherA")),
      e("researcherB", f.fields.value("researcherB")),
      e("jointPublicationCount", ujson.Num(n.toDouble)),
    )
    // true pair claim verifies
    expect(
      ProposedClaim(
        s"$ada and $alan coauthored $n works in window $W.",
        List(edge._1.evidenceId, adaId, alanId),
        edgeAsserts,
      ),
      Verified,
    )
    // a third researcher woven into the pair relation is not covered by the edge assertions
    expect(
      ProposedClaim(
        s"$ada and $hedy coauthored $n works in window $W.",
        List(edge._1.evidenceId, adaId, hedyId),
        edgeAsserts,
      ),
      Unsupported,
      "relation_without_assertion",
    )
    // a wrong joint publication count in text and assertion is a conflict with the stored edge
    expect(
      ProposedClaim(
        s"$ada and $alan coauthored ${n + 1} works in window $W.",
        List(edge._1.evidenceId, adaId, alanId),
        edgeAsserts.dropRight(1) :+ e("jointPublicationCount", ujson.Num((n + 1).toDouble)),
      ),
      Conflicting,
      "assertion_value_mismatch",
    )
  }

  test("H2: 'A has twelve coauthors' is unsupported, however the number is written") {
    expect(
      claim(s"In window $W, $ada has twelve distinct coauthors.", List(adaM)),
      Unsupported,
      "number_without_matching_assertion",
    )
    expect(
      claim(
        s"In window $W, $ada has twelve distinct coauthors.",
        List(adaM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    expect(
      claim(s"In window $W, $ada has a dozen distinct coauthors.", List(adaM)),
      Unsupported,
      "number_without_matching_assertion",
    )
    // the false number is also asserted: text and assertion agree, the stored value does not
    expect(
      claim(
        s"In window $W, $ada has twelve distinct coauthors.",
        List(adaM),
        metricAssert(ada, "coauthorCount", 12),
      ),
      Conflicting,
      "assertion_value_mismatch",
    )
    // number words other than twelve, and large words
    for w <- List("two", "nine", "ninety", "a hundred", "a thousand", "a million") do
      expect(
        claim(s"In window $W, $ada has $w distinct coauthors.", List(adaM)),
        Unsupported,
        "number_without_matching_assertion",
      )
  }

  test("H2: a true number written as a word is bound to the matching assertion") {
    expect(
      claim(
        s"In window $W, $ada has one distinct coauthors.",
        List(adaM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Verified,
    )
  }

  test("H2: 'the path from A to B passes through Hedy Lamarr' is not verified") {
    val text = s"The path from $ada to $grace passes through Hedy Lamarr."
    // as in the review: no assertion at all
    val none = expect(claim(text, List(pathEv)), Unsupported)
    assertNotEquals(none.status, Verified)
    val pathAsserts = List(
      a(Fact.Path, pathKey, Some(W), "from", ujson.Str(ada)),
      a(Fact.Path, pathKey, Some(W), "to", ujson.Str(grace)),
      a(Fact.Path, pathKey, Some(W), "hops", ujson.Arr(ada, alan, grace)),
    )
    // an unnamed intermediate: Hedy is not a stored name of any cited identity
    expect(
      claim(text, List(pathEv), pathAsserts*),
      PartiallySupported,
      "unverified_proper_noun",
    )
    // Hedy's identity is cited so her name resolves to her ID, but she is not on the asserted hops
    val stored = expect(claim(text, List(pathEv, hedyId), pathAsserts*), Unsupported)
    assert(
      stored.reasonCodes.exists(Set("reference_without_assertion", "unverified_proper_noun")),
      stored.reasonCodes.toString,
    )
  }

  test("H2: a display name beside an ID must be that ID's stored name") {
    val asserts = metricAssert(ada, "coauthorCount", 1)
    // the wrong name, Grace's identity not cited (an unknown proper noun beside the ID) ...
    expect(
      claim(
        s"In window $W, Grace Hopper ($ada) has 1 distinct coauthors.",
        List(adaM, adaId),
        asserts,
      ),
      Conflicting,
      "display_name_mismatch",
    )
    // ... and with Grace's identity cited the name resolves to Grace, who is not this ID
    expect(
      claim(
        s"In window $W, Grace Hopper ($ada) has 1 distinct coauthors.",
        List(adaM, adaId, graceId),
        asserts,
      ),
      Conflicting,
      "display_name_mismatch",
    )
    // the review input without any assertion
    val none = det(claim(s"Grace Hopper ($ada) has one coauthor.", List(adaM, adaId)))
    assertNotEquals(none.status, Verified)
  }

  test("H2: an unverified capitalized name inside a sentence caps the claim") {
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

  test("H2: a semantic excerpt never supplies an entity reference or a number") {
    val o      = new MemObjects
    val v      = new MemVectors
    val entity = "topic:openalex:TX"
    val text   = s"Topic note: $hedy has 9 coauthors in window $W."
    v.add(
      VectorIndexes.TopicInstitutionSummary,
      vectorEntry(
        VectorIndexes.TopicInstitutionSummary,
        EmbeddingDocumentTypes.TopicSummary,
        entity,
        allTime,
        text,
        Some(o),
      ),
    )
    val out = new SemanticContextAgent(embedder, v, o)
      .search("topic note", List(EmbeddingDocumentTypes.TopicSummary), None, 3)
    assertEquals(out.items.size, 1)
    val withSem = evidence(
      metricsAgent.metrics(List(ada), W).drafts,
      out.stage.drafts,
    )
    val semId               = withSem.semanticEvidenceIds.head
    val m                   = withSem.graphEvidenceIds.head
    def d(c: ProposedClaim) = verifier().deterministic(withSem, c)
    // the reference occurs only in the semantic excerpt
    val ref =
      d(claim(s"In window $W, $ada has 1 distinct coauthors, as does $hedy.", List(m, semId)))
    assertEquals(ref.status, Unsupported, ref.reasonCodes.toString)
    assert(ref.reasonCodes.contains("reference_not_in_cited_evidence"), ref.reasonCodes.toString)
    // a number only a semantic excerpt states
    val num = d(claim(s"$entity reports 9 coauthors.", List(semId)))
    assertEquals(num.status, Unsupported, num.reasonCodes.toString)
    // a semantic-only qualitative claim is at best partial
    val q = d(claim("This topic is related to the question.", List(semId)))
    assertEquals(q.status, PartiallySupported)
    assertEquals(q.reasonCodes, List("semantic_context_only"))
    // a semantic-only claim with a window or an assertion is not partial but unsupported
    val w = d(claim(s"The note covers window $W.", List(semId)))
    assertEquals(w.status, Unsupported, w.reasonCodes.toString)
    val asr = d(
      claim("Hedy has coauthors.", List(semId), metricAssert(hedy, "coauthorCount", 9))
    )
    assertEquals(asr.status, Unsupported, asr.reasonCodes.toString)
  }

  // ---- H3: assertions bound to the text's field, subject and window ---------------------------

  test("H3: wrong field - the text says coauthors, the assertion states another field's value") {
    // maxTeamSize is stored as 6, publicationCount as 7: true values, but not the field the text names
    expect(
      claim(
        s"In window $W, $ada has 6 distinct coauthors.",
        List(adaM),
        metricAssert(ada, "maxTeamSize", 6),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    expect(
      claim(
        s"In window $W, $ada has 7 distinct coauthors.",
        List(adaM),
        metricAssert(ada, "publicationCount", 7),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    // and the other way round: the text names publications, the assertion is the coauthor count
    expect(
      claim(
        s"In window $W, $ada has 1 publications.",
        List(adaM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
  }

  test("H3: wrong researcher - the text names B, the assertion is for A with the same value") {
    // Alan's stored count is 2 and Ada's is 1: the text gives Alan the value of Ada's assertion
    expect(
      claim(
        s"In window $W, $alan has 1 distinct coauthors.",
        List(adaM, alanM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    // the mirror: the text gives Ada the value that Alan's true assertion states
    expect(
      claim(
        s"In window $W, $ada has 2 distinct coauthors.",
        List(adaM, alanM),
        metricAssert(alan, "coauthorCount", 2),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
    // the nearest preceding researcher owns the number, not the first one named
    expect(
      claim(
        s"$ada and, in window $W, $alan have 1 distinct coauthors.",
        List(adaM, alanM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Unsupported,
    )
  }

  test("H3: wrong window - 2015-2019 in the text, the assertion is for another window") {
    // a window that no cited fact carries
    expect(
      claim(
        "In 2015-2019, " + s"$ada has 1 distinct coauthors.",
        List(adaM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Unsupported,
      "window_not_in_cited_evidence",
    )
    // the review's input: the cited fact (temporal change, window W2) states W as its prevWindow,
    // so W occurs in cited evidence, but the assertion window is W2 and W is only a stored value
    // the claim does not assert
    val tc = expect(
      claim(
        s"In window $W, $ada has 5 distinct coauthors.",
        List(temporalEv),
        a(Fact.TemporalChange, ada, Some(W2), "coauthorCount", ujson.Num(5)),
      ),
      Unsupported,
    )
    assert(
      tc.reasonCodes.exists(
        Set("window_not_bound_to_assertion", "number_without_matching_assertion")
      ),
      tc.reasonCodes.toString,
    )
    // an assertion in one real window, text in another real window (both cited)
    expect(
      claim(
        s"In window $W2, $ada has 1 distinct coauthors.",
        List(adaM, evOf(Fact.Metrics, ada, Some(W2))),
        metricAssert(ada, "coauthorCount", 1, W),
      ),
      Unsupported,
    )
  }

  test("H3: a window-less assertion matches only a window-less fact") {
    // metrics are windowed: a window-less assertion finds no matching fact
    expect(
      claim(
        s"In window $W, $ada has 1 distinct coauthors.",
        List(adaM),
        a(Fact.Metrics, ada, None, "coauthorCount", ujson.Num(1)),
      ),
      Unsupported,
      "assertion_without_evidence",
    )
    // identity is window-less: a windowed assertion finds no matching fact
    expect(
      claim(
        s"The researcher is named Ada Lovelace ($ada).",
        List(adaId),
        a(Fact.Identity, ada, Some(W), "displayName", ujson.Str("Ada Lovelace")),
      ),
      Unsupported,
      "assertion_without_evidence",
    )
    // a window in the text of a claim over a window-less fact is not in the cited evidence
    expect(
      claim(
        s"In window $W, the researcher is named Ada Lovelace ($ada).",
        List(adaId),
        a(Fact.Identity, ada, None, "displayName", ujson.Str("Ada Lovelace")),
      ),
      Unsupported,
      "window_not_in_cited_evidence",
    )
  }

  private def pathText(chain: List[String]) =
    s"The shortest collaboration path in window $W has length 2: " +
      chain.map(id => s"$id").mkString(" -> ") + "."

  private def pathAsserts(hops: List[String] = List(ada, alan, grace), length: Int = 2) = List(
    a(Fact.Path, pathKey, Some(W), "found", ujson.Bool(true)),
    a(Fact.Path, pathKey, Some(W), "length", ujson.Num(length.toDouble)),
    a(Fact.Path, pathKey, Some(W), "hops", ujson.Arr.from(hops.map(ujson.Str(_)))),
  )

  test("H3: the hop order written with arrows must equal the asserted hops") {
    val cites = List(pathEv, adaId, alanId, graceId)
    // control: the true order verifies
    expect(claim(pathText(List(ada, alan, grace)), cites, pathAsserts()*), Verified)
    // reversed in the text, true assertions: a conflict
    expect(
      claim(pathText(List(grace, alan, ada)), cites, pathAsserts()*),
      Conflicting,
      "hop_order_mismatch",
    )
    // swapped middle and end
    expect(
      claim(pathText(List(ada, grace, alan)), cites, pathAsserts()*),
      Conflicting,
      "hop_order_mismatch",
    )
    // a different arrow glyph is the same chain
    expect(
      claim(
        pathText(List(grace, alan, ada)).replace("->", "→"),
        cites,
        pathAsserts()*
      ),
      Conflicting,
      "hop_order_mismatch",
    )
    // with display names beside the IDs, as the template writes it
    val named = s"The shortest collaboration path in window $W has length 2: " +
      s"Grace Hopper ($grace) -> Alan Turing ($alan) -> Ada Lovelace ($ada)."
    expect(claim(named, cites, pathAsserts()*), Conflicting, "hop_order_mismatch")
  }

  // ---- H1: digit runs inside identifiers and labels never cover a number ----------------------

  test("H1: a wrong number equal to a window year is unsupported") {
    for year <- List("2000", "2004", "20", "4", "0") do
      expect(
        claim(s"In window $W, $ada has $year distinct coauthors.", List(adaM)),
        Unsupported,
        "number_without_matching_assertion",
      )
    // with the true assertion present the text number is still not that assertion's value
    expect(
      claim(
        s"In window $W, $ada has 2004 distinct coauthors.",
        List(adaM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      Unsupported,
      "number_without_matching_assertion",
    )
  }

  test("H1: a wrong number equal to any digit run inside a stored hex ID is unsupported") {
    val runs = Fact.parse(set.itemsById(adaM).text.get).toList.flatMap { f =>
      (ClaimVerificationAgent.stringsIn(f.fields) :+ f.subject).flatMap("[0-9]+".r.findAllIn(_))
    }
    assert(runs.nonEmpty)
    // every single digit, as in "has 7 coauthors" for an ID containing 'a7f' (coauthorCount is 1)
    for d <- (0 to 9).filterNot(_ == 1) do
      val r = expect(
        claim(s"In window $W, $ada has $d distinct coauthors.", List(adaM)),
        Unsupported,
        "number_without_matching_assertion",
      )
      assertNotEquals(r.status, Verified)
    // and every whole digit run of the IDs of at most four digits that is not a stored value
    val storedValues = Fact
      .parse(set.itemsById(adaM).text.get)
      .toList
      .flatMap(f => f.fields.value.values.toList.flatMap(ClaimVerificationAgent.flattenNumbers))
    runs
      .filter(_.length <= 4)
      .distinct
      .map(BigDecimal(_))
      .filterNot(n => storedValues.exists(Jnum.sameNumber(_, n)))
      .foreach { n =>
        expect(
          claim(s"In window $W, $ada has $n distinct coauthors.", List(adaM)),
          Unsupported,
          "number_without_matching_assertion",
        )
      }
  }

  test("H1: a digit run in the window label of an ID-like token does not cover a number") {
    // the community and component IDs hold the window label; a number equal to a part of it
    val community = storedStr(Fact.Metrics, ada, Some(W), "communityId")
    assert(community.contains("2000-2004"), community)
    expect(
      claim(s"In window $W, $ada has 2000 distinct coauthors.", List(adaM)),
      Unsupported,
      "number_without_matching_assertion",
    )
  }

  private def storedStr(kind: String, subject: String, window: Option[String], field: String) =
    stored(kind, subject, window, field).str

  // ---- verify(): none of the bad claims reaches the answer as supported -----------------------

  test("verify: every H1-H3 input is removed or capped, none is supported") {
    val bad = List(
      claim(s"$ada and $alan are direct coauthors.", List(adaM, alanId)),
      claim(s"In window $W, $ada has twelve distinct coauthors.", List(adaM)),
      claim(s"The path from $ada to $grace passes through Hedy Lamarr.", List(pathEv)),
      claim(
        s"In window $W, Grace Hopper ($ada) has 1 distinct coauthors.",
        List(adaM, adaId),
        metricAssert(ada, "coauthorCount", 1),
      ),
      claim(
        s"In window $W, $ada has 6 distinct coauthors.",
        List(adaM),
        metricAssert(ada, "maxTeamSize", 6),
      ),
      claim(
        s"In window $W, $alan has 1 distinct coauthors.",
        List(adaM, alanM),
        metricAssert(ada, "coauthorCount", 1),
      ),
      claim(
        s"In window $W, $ada has 2004 distinct coauthors.",
        List(adaM),
      ),
      claim(
        pathText(List(grace, alan, ada)),
        List(pathEv, adaId, alanId, graceId),
        pathAsserts()*
      ),
    )
    val r = verifier().verify("query:" + Hashing.textHash("bad"), set, bad)
    assertEquals(
      r.all.map(_.verification.status),
      List(
        Unsupported,
        Unsupported,
        Unsupported,
        Conflicting,
        Unsupported,
        Unsupported,
        Unsupported,
        Conflicting,
      ),
    )
    assert(r.kept.forall(_.status != VerificationStatus.Supported))
    assertEquals(r.kept.size, 0)
    assertEquals(r.removed.size, 8)
  }

  test("an LLM verifier cannot upgrade a deterministic PartiallySupported or Unsupported result") {
    def reply(id: String) =
      s"""{"verdicts":[{"claimId":"$id","status":"verified","reasonCodes":[]}]}"""
    val partial = claim(
      s"In window $W, $ada has 1 distinct coauthors, including Marie Curie.",
      List(adaM),
      metricAssert(ada, "coauthorCount", 1),
    )
    val unsup = claim(s"In window $W, $ada has twelve distinct coauthors.", List(adaM))
    val q     = "query:" + Hashing.textHash("llm-up")
    val llm   = new FakeModelClient(byPrompt =
      Map(
        "claim-verification" -> List(
          FakeReply.Json(reply(ClaimVerificationAgent.claimIdOf(q, 0, partial)))
        )
      )
    )
    val r = verifier(Some(llm)).verify(q, set, List(partial, unsup))
    // only the partial claim is eligible for the check; the unsupported one is never sent
    assertEquals(llm.callCount, 1)
    assertEquals(r.all.map(_.verification.status), List(PartiallySupported, Unsupported))
    assertEquals(r.all.map(_.deterministic.status), List(PartiallySupported, Unsupported))
    assert(!r.all.exists(_.verification.status == Verified))
    val sent = llm.requests.head.user.map(_.text).mkString("\n")
    assert(sent.contains("Marie Curie"), sent.take(300))
    assert(!sent.contains("twelve"), "an unsupported claim was sent to the model")
  }

  // ---- M1: the template synthesizer asserts what it states ------------------------------------

  // The synthesizer states at most 20 claims, so each family of facts gets its own bundle; the
  // identity facts of the four researchers are in every one.
  private lazy val groups: List[(String, EvidenceSet)] = List(
    "metrics" -> evidence(
      identities ++ metricsAgent.metrics(List(ada, alan, grace, hedy), W).drafts
    ),
    "path" -> evidence(
      identities ++ metricsAgent.metrics(List(ada, alan, grace), W).drafts ++
        pathFinder.shortestPath(ada, grace, W, 4).stage.drafts
    ),
    "neighborhood" -> evidence(
      identities ++ pathFinder.directCollaborators(alan, W, 10).stage.drafts
    ),
    "temporal" -> evidence(identities ++ metricsAgent.temporal(List(ada), W2, W).drafts),
    "ranking"  -> evidence(
      identities ++ metricsAgent.ranking(List(ada, alan, grace, hedy), W, "coauthorCount").drafts
    ),
    "community" -> evidence(
      identities ++ communityAgent.community(ada, W).drafts ++
        communityAgent.component(ada, W).drafts ++ communityAgent.degreeVsBridge(grace, W).drafts
    ),
  )

  private def groupSet(name: String): EvidenceSet = groups.find(_._1 == name).get._2

  private def templateOf(name: String): List[ProposedClaim] =
    TemplateSynthesizer.synthesize(groupSet(name), Nil).claims

  private def assertedIn(name: String, kind: String, field: String) =
    templateOf(name).flatMap(_.assertions).filter(x => x.kind == kind && x.field == field)

  private def storedIn(name: String, kind: String, subject: String, field: String) =
    groupSet(name).bundle.items
      .flatMap(_.text.flatMap(Fact.parse))
      .find(f => f.kind == kind && f.subject == subject)
      .flatMap(_.fields.value.get(field))
      .getOrElse(throw new AssertionError(s"no stored $field for $subject in $name"))

  test("M1: every claim of the template synthesizer verifies, for every family of facts") {
    groups.foreach { (name, gs) =>
      val claims = templateOf(name)
      assert(claims.nonEmpty, name)
      assert(claims.size < 20, s"$name hit the claim cap")
      val r = verifier().verify("query:" + Hashing.textHash("tmpl-" + name), gs, claims)
      r.all.foreach(v =>
        assertEquals(
          v.verification.status,
          Verified,
          s"[$name] ${v.claim.text} ${v.verification.reasonCodes}",
        )
      )
    }
  }

  test("M1: the neighborhood claim asserts jointPublicationCounts exactly as stored") {
    val got = assertedIn("neighborhood", Fact.Neighborhood, "jointPublicationCounts")
    assertEquals(got.size, 1)
    assertEquals(got.head.subject, alan)
    assertEquals(
      got.head.value,
      storedIn("neighborhood", Fact.Neighborhood, alan, "jointPublicationCounts"),
    )
    assertEquals(got.head.value.arr.map(_.num.toInt).toList, List(3, 2))
  }

  test("M1: the path claim asserts edgeIds, from and to, and its text is one arrow chain") {
    val ed = assertedIn("path", Fact.Path, "edgeIds")
    assertEquals(ed.size, 1)
    assertEquals(ed.head.value, storedIn("path", Fact.Path, pathKey, "edgeIds"))
    assertEquals(ed.head.value.arr.map(_.str).toList, List(eAdaAlan.id, eAlanGrace.id))
    assertEquals(assertedIn("path", Fact.Path, "from").map(_.value), List(ujson.Str(ada)))
    assertEquals(assertedIn("path", Fact.Path, "to").map(_.value), List(ujson.Str(grace)))
    val pc = templateOf("path")
      .find(_.assertions.exists(x => x.kind == Fact.Path && x.field == "hops"))
      .get
    assertEquals(pc.text.split("->").length, 3, pc.text)
    assert(
      pc.text.indexOf(ada) < pc.text.indexOf(alan) && pc.text.indexOf(alan) < pc.text.indexOf(
        grace
      ),
      pc.text,
    )
    assert(!pc.text.contains(" to "), pc.text)
  }

  test("M1: the temporal claim asserts prevWindow") {
    val pw = assertedIn("temporal", Fact.TemporalChange, "prevWindow")
    assertEquals(pw.size, 1)
    assertEquals(pw.head.value, ujson.Str(W))
    assertEquals(pw.head.window, Some(W2))
  }

  test("M1: a wrong prevWindow, edge ID, endpoint or joint count in a template claim is caught") {
    def mutate(group: String, kind: String, field: String, v: ujson.Value) =
      val base = templateOf(group)
        .find(_.assertions.exists(x => x.kind == kind && x.field == field))
        .getOrElse(fail(s"no $kind.$field assertion in the $group claims"))
      base.copy(assertions =
        base.assertions.map(x =>
          if x.kind == kind && x.field == field then x.copy(value = v) else x
        )
      )
    for (group, kind, field, v) <- List(
        ("temporal", Fact.TemporalChange, "prevWindow", ujson.Str("1990-1994")),
        ("path", Fact.Path, "edgeIds", ujson.Arr(eAlanGrace.id, eAdaAlan.id)),
        ("path", Fact.Path, "from", ujson.Str(hedy)),
        ("neighborhood", Fact.Neighborhood, "jointPublicationCounts", ujson.Arr(2, 3)),
      )
    do
      val r = verifier().deterministic(groupSet(group), mutate(group, kind, field, v))
      assertNotEquals(r.status, Verified, s"$kind.$field tampered was verified")
      assert(r.status == Conflicting || r.status == Unsupported, s"$kind.$field: ${r.status}")
  }
