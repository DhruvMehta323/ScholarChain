package scholarchain.er

import scholarchain.core.*

import ErFx.*

/** Repair-04 behavior: F1 (Unicode dash mapping), F2 (ORCID family-name rule), F5 (feature set
  * version). All values are hand-computed from the documented rules, on SYNTHETIC names.
  */
class Repair04Suite extends munit.FunSuite:
  private val R = EntityKind.Researcher

  /** U+2010, 2011, 2012, 2013, 2014, 2015, 2212, FE58, FE63, FF0D. */
  private val Dashes: List[(String, String)] = List(
    "U+2010" -> "‐",
    "U+2011" -> "‑",
    "U+2012" -> "‒",
    "U+2013" -> "–",
    "U+2014" -> "—",
    "U+2015" -> "―",
    "U+2212" -> "−",
    "U+FE58" -> "﹘",
    "U+FE63" -> "﹣",
    "U+FF0D" -> "－",
  )

  private val Ascii                = "Ángela Hernández-Solana" // Ángela Hernández-Solana
  private def withDash(d: String)  = s"Ángela Hernández${d}Solana"
  private def commaForm(d: String) = s"Hernández${d}Solana, Ángela"

  private val Orc = "0000-0002-1825-0097"

  private def orcidFeatures(a: String, b: String): Map[String, Double] =
    Features.researcher(
      person("mention:a", SourceType.Dblp, a, orcid = Some(Orc)),
      person("mention:b", SourceType.Crossref, b, orcid = Some(Orc)),
    )

  // ---- F1 -------------------------------------------------------------------------------------

  test("F1 Strings.canon: every listed dash becomes ASCII '-', U+00AD is dropped") {
    Dashes.foreach((n, d) => assertEquals(Strings.canon(s"a${d}b"), "a-b", n))
    assertEquals(Strings.canon("a­b"), "ab")
    assertEquals(Strings.canon("plain-ascii"), "plain-ascii")
  }

  test("F1 Names.parse: each dash variant gives family 'hernandez solana', givens [angela]") {
    (("ASCII" -> "-") :: Dashes).foreach { (n, d) =>
      val p = Names.parse(withDash(d)).getOrElse(fail(s"$n: no parse"))
      assertEquals(p.family, "hernandez solana", n)
      assertEquals(p.givens, List("angela"), n)
      assertEquals(p.familyKey, "hernandezsolana", n)
      val c = Names.parse(commaForm(d)).getOrElse(fail(s"$n comma: no parse"))
      assertEquals(c.family, "hernandez solana", s"$n comma")
      assertEquals(c.givens, List("angela"), s"$n comma")
    }
  }

  test("F1 block key: every dash variant (and the comma form) is r1|hernandezsolana|a") {
    Dashes.foreach { (n, d) =>
      assertEquals(Blocking.researcherPrimary(withDash(d)), Some("r1|hernandezsolana|a"), n)
      assertEquals(Blocking.researcherPrimary(commaForm(d)), Some("r1|hernandezsolana|a"), n)
    }
    assertEquals(Blocking.researcherPrimary(Ascii), Some("r1|hernandezsolana|a"))
  }

  test("F1 family_exact against the ASCII form is 1 for every dash variant, in both directions") {
    Dashes.foreach { (n, d) =>
      val fa = Features.researcher(
        person("mention:a", SourceType.Dblp, withDash(d)),
        person("mention:b", SourceType.Crossref, Ascii),
      )
      assertEquals(fa("family_exact"), 1.0, n)
      assertEquals(fa("family_jw"), 1.0, n)
      val fb = Features.researcher(
        person("mention:a", SourceType.Dblp, Ascii),
        person("mention:b", SourceType.Crossref, commaForm(d)),
      )
      assertEquals(fb("family_exact"), 1.0, s"$n comma")
    }
  }

  test("F1 soft hyphen U+00AD joins the parts: same block key, family_exact 0 against hyphenated") {
    val soft = "Ángela Hernández­Solana"
    val p    = Names.parse(soft).getOrElse(fail("no parse"))
    assertEquals(p.family, "hernandezsolana")
    assertEquals(p.givens, List("angela"))
    assertEquals(Blocking.researcherPrimary(soft), Some("r1|hernandezsolana|a"))
    val f = Features.researcher(
      person("mention:a", SourceType.Dblp, soft),
      person("mention:b", SourceType.Crossref, Ascii),
    )
    // "hernandezsolana" vs "hernandez solana" differ by one space: documented, exact is 0
    assertEquals(f("family_exact"), 0.0)
    // hand-computed Jaro: m = 15, t = 0, (15/15 + 15/16 + 1)/3 = 0.97917; prefix 4: 0.9875
    assert(math.abs(f("family_jw") - 0.9875) < 0.001, f("family_jw").toString)
  }

  test("F1 'J.<en dash>P. Dupont': a hyphen next to a period is a separator, givens [j, p]") {
    List("–", "‐", "-").foreach { d =>
      val p = Names.parse(s"J.${d}P. Dupont").getOrElse(fail("no parse"))
      assertEquals(p.family, "dupont", d)
      assertEquals(p.givens, List("j", "p"), d)
      assertEquals(Blocking.researcherPrimary(s"J.${d}P. Dupont"), Some("r1|dupont|j"), d)
    }
  }

  test("F1 'Ann Lee<minus>Smith' (U+2212) gives family 'lee smith' and given [ann]") {
    val p = Names.parse("Ann Lee−Smith").getOrElse(fail("no parse"))
    assertEquals(p.family, "lee smith")
    assertEquals(p.givens, List("ann"))
  }

  test("F1 Strings.tokens, nameTokens and textNorm all see the mapped dash") {
    assertEquals(Strings.nameTokens("Lee‐Smith"), List("lee-smith"))
    assertEquals(Strings.nameTokens("Lee­Smith"), List("leesmith"))
    assertEquals(Strings.tokens("Lee–Smith"), List("lee", "smith"))
    assertEquals(Strings.textNorm("Lee—Smith Lab"), "lee smith lab")
  }

  test("F1 personKey, coauthor_overlap and author_overlap agree between the dash variants") {
    val key = "hernandezsolana|a"
    Dashes.foreach { (n, d) =>
      assertEquals(Names.personKey(withDash(d)), Some(key), n)
      assertEquals(Names.personKey(commaForm(d)), Some(key), n)
      val a = person("mention:a", SourceType.Dblp, "Wei Wang", authors = List(withDash(d)))
      val b = person("mention:b", SourceType.Crossref, "Wei Wang", authors = List(Ascii))
      assertEquals(Features.researcher(a, b)("coauthor_overlap"), 1.0, n)
      val wa =
        paper("work:a", SourceType.Dblp, "Graph Neural Networks", authors = List(withDash(d)))
      val wb = paper("work:b", SourceType.Crossref, "Graph Neural Networks", authors = List(Ascii))
      assertEquals(Features.work(wa, wb)("author_overlap"), 1.0, n)
      assertEquals(Features.work(wa, wb)("first_author_agree"), 1.0, n)
    }
  }

  test("F1 titleNorm is unchanged by the dash mapping (regression on one title)") {
    assertEquals(
      Strings.titleNorm("Graph-Based Learning: A Survey"),
      "graph based learning a survey",
    )
    assertEquals(
      Strings.titleNorm("Graph–Based Learning: A Survey"),
      "graph based learning a survey",
    )
    assertEquals(
      Strings.titleNorm("Graph‐Based Learning: A Survey"),
      "graph based learning a survey",
    )
  }

  test("F1 rootG pair: 'Angela Hernandez<U+2010>Solana' with a shared ORCID is now ACCEPTED") {
    val f = orcidFeatures(withDash("‐"), withDash("-"))
    assertEquals(f("orcid_agreement"), 1.0)
    // before the fix the families were "hernandez-solana"-style vs "hernandez solana": exact 0
    assertEquals(f("family_exact"), 1.0)
    val r = Policy.decide(R, f, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.path, MatchPath.ExactId)
    assertEquals(r.reasonCodes, List("orcid_equal"))
  }

  // ---- F2 -------------------------------------------------------------------------------------

  private def assertOrcidMismatch(a: String, b: String, jw: Double): Unit =
    val f = orcidFeatures(a, b)
    assertEquals(f("orcid_agreement"), 1.0, s"$a / $b")
    assertEquals(f("family_exact"), 0.0, s"$a / $b")
    assert(math.abs(f("family_jw") - jw) < 0.001, s"$a / $b: family_jw ${f("family_jw")}")
    assert(f("family_jw") < Policy.OrcidFamilyJwFloor, s"$a / $b")
    val r = Policy.decide(R, f, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict, s"$a / $b")
    assertEquals(r.path, MatchPath.ExactId, s"$a / $b")
    assertEquals(r.rulesFired, List("orcid_name_mismatch"), s"$a / $b")
    assertEquals(r.reasonCodes, List("orcid_name_mismatch"), s"$a / $b")
    assert(!r.llmEligible)

  test("F2 Wei Wang / Wei Yang sharing an ORCID: CONFLICT, family_jw 0.8333 (hand-computed)") {
    // wang/yang: m = 3 (a, n, g), t = 0, Jaro = (3/4 + 3/4 + 1)/3 = 0.8333, prefix 0
    assertOrcidMismatch("Wei Wang", "Wei Yang", 0.8333)
  }

  test("F2 Li Zhang / Li Zhao sharing an ORCID: CONFLICT, family_jw 0.8483 (hand-computed)") {
    // zhang/zhao: m = 3 (z, h, a), Jaro = (3/5 + 3/4 + 1)/3 = 0.7833, prefix 3: + 0.3 * 0.2167
    assertOrcidMismatch("Li Zhang", "Li Zhao", 0.8483)
  }

  test("F2 W. Zhang / Wei Wang sharing an ORCID: CONFLICT, family_jw 0.7833 (hand-computed)") {
    // zhang/wang: m = 3 (a, n, g), Jaro = (3/5 + 3/4 + 1)/3 = 0.7833, prefix 0
    assertOrcidMismatch("W. Zhang", "Wei Wang", 0.7833)
  }

  test("F2 typo-level control: Hernandez / Hernandes with a shared ORCID is ACCEPTED") {
    // 9 chars, m = 8 (z unmatched), Jaro = (8/9 + 8/9 + 1)/3 = 0.9259, prefix 4: 0.9556
    val f = orcidFeatures("Ana Hernandez", "Ana Hernandes")
    assertEquals(f("family_exact"), 0.0)
    assert(math.abs(f("family_jw") - 0.9556) < 0.001, f("family_jw").toString)
    val r = Policy.decide(R, f, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Accepted)
    assertEquals(r.reasonCodes, List("orcid_equal"))
  }

  test("F2 the same family name with different initials is still ACCEPTED on an ORCID match") {
    val f = orcidFeatures("Wei Wang", "W. Wang")
    assertEquals(f("family_exact"), 1.0)
    assertEquals(Policy.decide(R, f, Thr).outcome, ResolutionOutcome.Accepted)
  }

  test("F2 boundary 0.92 / 0.9199 on the policy, and the floor constant") {
    assertEquals(Policy.OrcidFamilyJwFloor, 0.92)
    def at(jw: Double) =
      Policy.decide(
        R,
        Map("orcid_agreement" -> 1.0, "family_exact" -> 0.0, "family_jw" -> jw),
        Thr,
      )
    assertEquals(at(0.92).outcome, ResolutionOutcome.Accepted)
    assertEquals(at(0.9199).outcome, ResolutionOutcome.Conflict)
    assertEquals(at(0.9199).reasonCodes, List("orcid_name_mismatch"))
  }

  test("F2 family_jw is a feature but not a score weight") {
    assert(!Policy.ResearcherWeights.contains("family_jw"))
    assert(
      close(
        Policy.score(R, Map("name_sim" -> 1.0) + ("family_jw" -> 0.0)),
        Policy.score(R, Map("name_sim" -> 1.0)),
      )
    )
  }

  // ---- F5 -------------------------------------------------------------------------------------

  test("F5 the feature set is er-features-v2 and the policy is still er-v1") {
    assertEquals(Features.Version, "er-features-v2")
    assertEquals(Policy.Version, "er-v1")
    assertEquals(cfg.featureSetVersion, "er-features-v2")
    assertEquals(cfg.policyVersion, "er-v1")
  }

  test("F5 a candidate built through Blocking.candidate carries er-features-v2") {
    val d = person("mention:b2", SourceType.Dblp, "Wei Wang")
    val c = person("mention:a1", SourceType.Crossref, "Wei Wang")
    val k = BlockKey("r1|wang|w", BlockKeyType.Primary)
    val r = Blocking.candidate(d, c, k, prov()).fold(e => fail(e.toString), identity)
    assertEquals(r.featureSetVersion, "er-features-v2")
    assertEquals(r.provenance.parameters("featureSetVersion"), "er-features-v2")
    assert(r.features.contains("family_jw"), r.features.keySet.toString)
  }
