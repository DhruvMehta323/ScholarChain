package scholarchain.er

import java.nio.file.Files
import java.util.regex.Pattern

import scholarchain.core.*

import ErFx.*

/** Adversarial and robustness tests. Tests that fail here expose defects in production code and are
  * deliberately left failing (see the Phase 05 test report for the owner of each).
  */
class AdversarialSuite extends munit.FunSuite:
  private val R      = EntityKind.Researcher
  private val prompt = PromptTemplate.load().fold(e => throw new AssertionError(e), identity)

  private val Mid = Map("name_sim" -> 1.0, "initials_compat" -> 1.0, "coauthor_overlap" -> 1.0)

  private def viewsOf(l: String, r: String) =
    (
      person(l, SourceType.Dblp, "Wei Wang", authors = List("Li Zhang")),
      person(r, SourceType.Crossref, "W. Wang", authors = List("Zhang, Li")),
    )

  private def acceptFor(l: String, r: String, c: MatchCandidate, ih: String): AdjudicationRecord =
    AdjudicationRecord(
      SchemaVersion.Current,
      l,
      r,
      EntityKind.Researcher,
      AdjudicationOutcome.Accept,
      0.99,
      List("left.name"),
      List("same_name"),
      "er-adjudication-v1",
      "claude-haiku-4-5",
      "er-v1",
      ih,
      prov(),
    )

  // ---- name and unicode edge cases ------------------------------------------------------------

  test("a hyphenated family name blocks identically in 'First Last' and 'Last, First' form") {
    val a = Blocking.researcherPrimary("Anna Smith-Jones")
    val b = Blocking.researcherPrimary("Smith-Jones, Anna")
    assertEquals(a, b, "the same person written two ways must share a block")
  }

  test("a supplementary-plane letter (CJK Extension B, U+20BB7) is kept, not dropped") {
    val ideograph = "𠮷"
    assertEquals(Strings.tokens(ideograph), List(ideograph))
    assert(Names.parse(ideograph).isDefined, "a name of one supplementary-plane character parses")
    assert(
      Blocking.researcherPrimary(s"$ideograph Wang").exists(_.endsWith("|" + ideograph.take(2))),
      "the given-name initial must be the whole code point",
    )
  }

  test("a block key is always well-formed UTF-16 (no lone surrogate)") {
    val names = List("𠮷 Wang", "Wang 𠮷", "𠮷野 家")
    names.flatMap(Blocking.researcherPrimary).foreach { k =>
      val round = new String(k.getBytes("UTF-8"), "UTF-8")
      assertEquals(round, k, s"key '$k' is not valid UTF-16")
    }
  }

  test("the homonym suffix is recognised in the 'Last, First' form too") {
    val n = Names.parse("Wang, Wei 0001").get
    assertEquals((n.family, n.givens, n.suffix), ("wang", List("wei"), Some("0001")))
  }

  test("an ORCID check digit written in lower case equals the upper-case form") {
    val a = person("mention:a", SourceType.Dblp, "Wei Wang", orcid = Some("0000-0002-1694-233x"))
    val b = person(
      "mention:b",
      SourceType.Crossref,
      "Wei Wang",
      orcid = Some("https://orcid.org/0000-0002-1694-233X"),
    )
    assertEquals(Features.researcher(a, b).get("orcid_agreement"), Some(1.0))
  }

  test("a syntactically invalid ORCID yields no agreement feature (never a fabricated conflict)") {
    val a = person(
      "mention:a",
      SourceType.Dblp,
      "Wei Wang",
      orcid = Some("0000-0002-1825-0098"),
    ) // bad check digit
    val b =
      person("mention:b", SourceType.Crossref, "Wei Wang", orcid = Some("0000-0002-1825-0097"))
    assert(!Features.researcher(a, b).contains("orcid_agreement"))
  }

  test("candidate for a view paired with itself is refused") {
    val v = person("mention:a", SourceType.Dblp, "Wei Wang")
    val r = Blocking.candidate(v, v, BlockKey("r1|wang|w", BlockKeyType.Primary), prov())
    assert(r.isLeft, r.toString)
  }

  test("ErIds.pairId is order-independent, kind-sensitive and shaped P + 12 hex") {
    val a = ErIds.pairId(R, "mention:a", "mention:b")
    assertEquals(a, ErIds.pairId(R, "mention:b", "mention:a"))
    assertNotEquals(a, ErIds.pairId(EntityKind.Work, "mention:a", "mention:b"))
    assertNotEquals(a, ErIds.pairId(R, "mention:a", "mention:c"))
    assert(a.matches("P[0-9a-f]{12}"), a)
  }

  // ---- prompt integrity ----------------------------------------------------------------------------

  test(
    "a record that contains the template placeholder {{RIGHT_RECORD}} does not pull in the other record"
  ) {
    val (l, r)      = viewsOf("mention:a", "mention:b")
    val hostile     = l.copy(title = Some("{{RIGHT_RECORD}}"))
    val c           = cand("mention:a", "mention:b", R, Mid)
    val (_, user)   = AdjudicationInput.render(prompt, c, hostile, r)
    val rightJson   = AdjudicationInput.recordJson(r)
    val occurrences = user.split(Pattern.quote(rightJson), -1).length - 1
    assertEquals(occurrences, 1, "the right record must appear once, in its own <record> block")
  }

  test("a record that contains {{LEFT_ID}} or {{ENTITY_KIND}} is shown literally") {
    val (l, r)    = viewsOf("mention:a", "mention:b")
    val hostile   = l.copy(title = Some("{{LEFT_ID}} {{ENTITY_KIND}}"))
    val c         = cand("mention:a", "mention:b", R, Mid)
    val (_, user) = AdjudicationInput.render(prompt, c, hostile, r)
    assert(user.contains("{{LEFT_ID}} {{ENTITY_KIND}}"), user)
  }

  // ---- a cache or adjudication for ANOTHER pair must never be applied --------------------------------------

  test("SAFETY a cache entry whose record is for a different pair is not served as a hit") {
    val dir      = Files.createTempDirectory("er-adv-test-")
    val cache    = AdjudicationCache.open(dir.resolve("c.jsonl"))._1
    val (la, ra) = viewsOf("mention:a", "mention:b")
    val a        = cand("mention:a", "mention:b", R, Mid)
    val b        = cand("mention:c", "mention:d", R, Mid)
    val ihA      = AdjudicationInput.inputHash(a, la, ra)
    val keyA     =
      AdjudicationCache.key(ihA, prompt.version, ErFx.cfg.llm.model, ErFx.cfg.policyVersion)
    // a tampered or mixed-up cache line: the key of pair A, a record that says "accept" for pair B
    cache.put(CacheEntry(keyA, acceptFor("mention:c", "mention:d", b, ihA), 1, 1))
    val client = new FakeLlmClient(
      ErFx.cfg.llm.model,
      (_, _) => Left(LlmError("transport_error")),
    )
    val adj = new Adjudicator(client, cache, ErFx.cfg, prompt, "unit", 5)
    val res = adj.adjudicate(a, la, ra)
    assert(
      res.answer.forall(r => (r.leftId, r.rightId) == (a.leftId, a.rightId)),
      s"served a record for another pair: $res",
    )
  }

  test("SAFETY Resolver.decide ignores an adjudication that is not about this candidate") {
    val a       = cand("mention:a", "mention:b", R, Mid) // score .79: UNRESOLVED, LLM-eligible
    val foreign = acceptFor("mention:c", "mention:d", a, Hex)
    val d       = Resolver.decide(a, ErFx.cfg, Some(foreign))
    assertEquals(
      d.outcome,
      ResolutionOutcome.Unresolved,
      "a foreign accept must not merge this pair",
    )
  }

  test("Resolver.decide applies an adjudication for the same pair (control for the test above)") {
    val a    = cand("mention:a", "mention:b", R, Mid)
    val same = acceptFor("mention:a", "mention:b", a, Hex)
    assertEquals(Resolver.decide(a, ErFx.cfg, Some(same)).outcome, ResolutionOutcome.Accepted)
  }

  // ---- configuration --------------------------------------------------------------------------------------------

  test("config defaults of the versioned file") {
    val c = cfg
    assertEquals((c.policyVersion, c.featureSetVersion), ("er-v1", "er-features-v2"))
    assertEquals(c.decisionEpoch, "2026-01-01T00:00:00Z")
    assertEquals(c.maxBlockSize, 500)
    assertEquals(c.llm.provider, "anthropic")
    assertEquals((c.llm.maxCalls, c.llm.maxAttempts, c.llm.maxCostUsd), (60, 2, 2.0))
    assertEquals(c.llm.minConfidence, 0.7)
    assertEquals(ErConfigKeys.all.size, 24) // 5 + 2 x 4 thresholds + 11 llm keys
  }

  test("config precedence: environment beats override beats the file") {
    val file = ErConfigLoader.load(Map.empty, Map.empty).toOption.get
    assertEquals(file.maxBlockSize, 500)
    val over =
      ErConfigLoader.load(Map.empty, Map("scholarchain.er.max-block-size" -> "7")).toOption.get
    assertEquals(over.maxBlockSize, 7)
    val env = ErConfigLoader
      .load(
        Map("SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "9"),
        Map("scholarchain.er.max-block-size" -> "7"),
      )
      .toOption
      .get
    assertEquals(env.maxBlockSize, 9)
  }

  test("config: invalid values are refused, not defaulted") {
    List(
      "scholarchain.er.max-block-size"              -> "1",
      "scholarchain.er.max-block-size"              -> "many",
      "scholarchain.er.decision-epoch"              -> "yesterday",
      "scholarchain.er.llm.provider"                -> "gpt",
      "scholarchain.er.llm.min-confidence"          -> "1.5",
      "scholarchain.er.llm.max-calls"               -> "-1",
      "scholarchain.er.llm.max-attempts"            -> "0",
      "scholarchain.er.schema-version"              -> "2",
      "scholarchain.er.researcher.threshold.accept" -> "NaN",
      "scholarchain.er.researcher.threshold.accept" -> "1.1",
    ).foreach((k, v) =>
      assert(ErConfigLoader.load(Map.empty, Map(k -> v)).isLeft, s"$k=$v must be refused")
    )
  }

  test("config: a policy version with whitespace is refused") {
    assert(ErConfigLoader.load(Map.empty, Map("scholarchain.er.policy-version" -> "er v1")).isLeft)
  }

  // ---- frozen policy -----------------------------------------------------------------------------------------------

  private def frozen(
      policyVersion: String = "er-v1",
      r: Thresholds = Thresholds(0.9, 0.5, 0.6, 0.8),
  ) =
    FrozenPolicy(
      SchemaVersion.Current,
      policyVersion,
      "er-features-v2",
      "calibrated-on-calibration-split-only",
      r,
      Thresholds(0.7, 0.3, 0.4, 0.6),
      "objective",
      "grid",
      Map("researcher" -> Map("f1" -> 1.0)),
      List(InputRef("er-gold-split", "gold-split-v1", Some(Hex))),
      "unit-code",
      Map("k" -> "v"),
    )

  test("FrozenPolicy: round trip, and applyTo replaces only the thresholds") {
    val f    = frozen()
    val back =
      FrozenPolicy.read(scholarchain.core.WirePickle.write(f)).fold(e => fail(e.toString), identity)
    assertEquals(back, f)
    val applied = FrozenPolicy.applyTo(cfg, f).fold(e => fail(e.toString), identity)
    assertEquals(applied.researcher, Thresholds(0.9, 0.5, 0.6, 0.8))
    assertEquals(applied.work, Thresholds(0.7, 0.3, 0.4, 0.6))
    assertEquals(applied.llm, cfg.llm)
    assertEquals(applied.maxBlockSize, cfg.maxBlockSize)
  }

  test("FrozenPolicy: a different policy version, bad thresholds or non-JSON are refused") {
    assert(FrozenPolicy.applyTo(cfg, frozen(policyVersion = "er-v2")).isLeft)
    val bad = scholarchain.core.WirePickle.write(frozen(r = Thresholds(0.5, 0.9, 0.6, 0.8)))
    assert(FrozenPolicy.read(bad).isLeft, "reject above band-low violates the ordering")
    assert(FrozenPolicy.read("not json").isLeft)
  }

  test("Thresholds.validate enforces 0 <= reject <= low <= high <= accept <= 1") {
    assert(Thresholds.validate("t", Thresholds(0.9, 0.1, 0.2, 0.8)).isRight)
    assert(Thresholds.validate("t", Thresholds(1.0, 0.0, 0.0, 1.0)).isRight)
    assert(Thresholds.validate("t", Thresholds(0.9, -0.1, 0.2, 0.8)).isLeft)
    assert(Thresholds.validate("t", Thresholds(0.9, 0.3, 0.2, 0.8)).isLeft)
    assert(Thresholds.validate("t", Thresholds(0.9, 0.1, 0.85, 0.8)).isLeft)
    assert(Thresholds.validate("t", Thresholds(0.7, 0.1, 0.2, 0.8)).isLeft)
    assert(Thresholds.validate("t", Thresholds(1.1, 0.1, 0.2, 0.8)).isLeft)
  }
