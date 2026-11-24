package scholarchain.rag.runtime

import scholarchain.core.*

/** Adversarial grounding checks: a claim must not be verified on the strength of digits that merely
  * occur inside stored identifiers, a claim cannot borrow another kind of fact, and resolver
  * confidence drops on a cannot-link violation. These tests assert the intended behavior; a failure
  * is a production defect.
  */
class VerifierGroundingSuite extends munit.FunSuite:
  import World.*

  private lazy val set: EvidenceSet = evidence(
    new CollaborationMetricsAgent(reader(World.kv)).metrics(List(ada, alan), W).drafts ++
      new ResearcherResolverAgent(reader(World.kv), vectors(new MemObjects), embedder)
        .resolve(
          planOf("who", List("Ada Lovelace"), List(QueryOperationV2.ResearcherMetricsLookup))
        )
        .evidence
        .drafts
  )

  private def verifier = new ClaimVerificationAgent(None, prompts, false)

  private def adaMetrics = fact(set, Fact.Metrics, ada)._1.evidenceId

  test("a wrong number that equals a window year embedded in a stored ID is not verified") {
    // the stored community and component IDs contain '2000-2004', so 2004 occurs inside a label
    val c = ProposedClaim(s"In window $W, $ada has 2004 distinct coauthors.", List(adaMetrics), Nil)
    val r = verifier.deterministic(set, c)
    assertNotEquals(r.status, ClaimVerificationStatus.Verified, r.reasonCodes.toString)
    assertEquals(r.status, ClaimVerificationStatus.Unsupported)
  }

  test("a wrong number that equals a digit run inside a stored hash ID is not verified") {
    val (_, f) = fact(set, Fact.Metrics, ada)
    val stored = f.fields.value.values.toList.flatMap(ClaimVerificationAgent.flattenNumbers)
    val labels = (ClaimVerificationAgent.stringsIn(f.fields) :+ f.subject)
      .flatMap(ClaimVerificationAgent.numbersIn)
    val wrong = labels
      .filter(n => n.isWhole && n > 0 && n < 100000 && !stored.exists(Jnum.sameNumber(_, n)))
      .sorted
      .headOption
      .getOrElse(fail("fixture has no digit run inside its IDs"))
    val c =
      ProposedClaim(s"In window $W, $ada has $wrong distinct coauthors.", List(adaMetrics), Nil)
    val r = verifier.deterministic(set, c)
    assertNotEquals(r.status, ClaimVerificationStatus.Verified, s"wrong number $wrong was verified")
  }

  test("a text number that contradicts the claim's own true assertion is not verified") {
    val c = ProposedClaim(
      s"In window $W, $ada has 2004 distinct coauthors.",
      List(adaMetrics),
      List(ProposedAssertion(Fact.Metrics, ada, Some(W), "coauthorCount", ujson.Num(1))),
    )
    val r = verifier.deterministic(set, c)
    assertNotEquals(r.status, ClaimVerificationStatus.Verified, r.reasonCodes.toString)
  }

  test("a claim cannot assert a metric while citing only an identity fact") {
    val idEv = fact(set, Fact.Identity, ada)._1.evidenceId
    val c    = ProposedClaim(
      s"In window $W, $ada has 1 distinct coauthors.",
      List(idEv),
      List(ProposedAssertion(Fact.Metrics, ada, Some(W), "coauthorCount", ujson.Num(1))),
    )
    val r = verifier.deterministic(set, c)
    assertEquals(r.status, ClaimVerificationStatus.Unsupported)
    assert(r.reasonCodes.contains("assertion_without_evidence"))
  }

  test("the identity claim is verified only for the stored display name") {
    val idEv                = fact(set, Fact.Identity, ada)._1.evidenceId
    def claim(name: String) = ProposedClaim(
      s"The researcher is named $name ($ada).",
      List(idEv),
      List(ProposedAssertion(Fact.Identity, ada, None, "displayName", ujson.Str(name))),
    )
    assertEquals(
      verifier.deterministic(set, claim("Ada Lovelace")).status,
      ClaimVerificationStatus.Verified,
    )
    assertEquals(
      verifier.deterministic(set, claim("Alan Turing")).status,
      ClaimVerificationStatus.Conflicting,
    )
  }

  test("a cannot-link violation lowers identity confidence below the resolve threshold") {
    val r = new ResearcherResolverAgent(reader(World.kv), vectors(new MemObjects), embedder)
      .resolve(planOf("who", List("Cal Link"), List(QueryOperationV2.ResearcherMetricsLookup)))
    val e = r.entries.head
    assertEquals(e.status, ResolutionStatus.Unresolved)
    assertEquals(e.chosen, None)
    assert(e.candidates.head.confidence < ResearcherResolverAgent.ResolveThreshold)
    assert(e.reasonCodes.contains("low_confidence"))
  }

  test("a researcher ID that is not in the bundle cannot be introduced through the claim text") {
    val c = ProposedClaim(
      s"In window $W, $ada has 1 distinct coauthors, as does ${rid("Invented Person")}.",
      List(adaMetrics),
      List(ProposedAssertion(Fact.Metrics, ada, Some(W), "coauthorCount", ujson.Num(1))),
    )
    assertEquals(verifier.deterministic(set, c).status, ClaimVerificationStatus.Unsupported)
  }
