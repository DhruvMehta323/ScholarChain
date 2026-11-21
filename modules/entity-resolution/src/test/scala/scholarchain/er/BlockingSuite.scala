package scholarchain.er

import scholarchain.core.*

import ErFx.*

class BlockingSuite extends munit.FunSuite:
  private def keysOf(bs: List[BlockKey]): List[String] = bs.map(_.key)

  test("researcher primary key: family without spaces plus first initial, accents folded") {
    assertEquals(Blocking.researcherPrimary("Wei Wang 0001"), Some("r1|wang|w"))
    assertEquals(Blocking.researcherPrimary("Müller, Hans"), Some("r1|muller|h"))
    assertEquals(Blocking.researcherPrimary("Müller, Hans"), Some("r1|muller|h"))
    assertEquals(Blocking.researcherPrimary("Ludwig van Beethoven"), Some("r1|vanbeethoven|l"))
    assertEquals(Blocking.researcherPrimary("Plato"), Some("r1|plato|_"))
    assertEquals(Blocking.researcherPrimary("---"), None)
  }

  test("researcher fallbacks: year bucket (year/5) then sorted coauthor families") {
    val fb =
      Blocking.researcherFallbacks("Wei Wang", Some(2021), List("Li Zhang", "Jun Chen", "Ana Diaz"))
    assertEquals(
      keysOf(fb),
      List("r1|wang|w|y404", "r1|wang|w|cchen", "r1|wang|w|cdiaz", "r1|wang|w|czhang"),
    )
    assert(fb.forall(_.keyType == BlockKeyType.Fallback))
  }

  test("researcher fallbacks: no year gives no year key; at most 8 coauthor keys, alphabetical") {
    val fams = List(
      "alpha",
      "bravo",
      "charlie",
      "delta",
      "echo",
      "foxtrot",
      "golf",
      "hotel",
      "india",
      "juliet",
    )
    val co = fams.reverse.map(f => s"Xx ${f.capitalize}")
    val fb = Blocking.researcherFallbacks("Wei Wang", None, co)
    assertEquals(fb.size, 8)
    assertEquals(keysOf(fb), fams.take(8).map(f => s"r1|wang|w|c$f"))
  }

  test("researcher fallbacks: duplicate coauthor families collapse to one key") {
    val fb = Blocking.researcherFallbacks("Wei Wang", None, List("A Lee", "B Lee", "C Lee"))
    assertEquals(keysOf(fb), List("r1|wang|w|clee"))
  }

  test("work primary key: four longest tokens (len >= 4, no stop words), sorted, year/3") {
    // tokens: efficient(9) graph(5) neural(6) network(7) training(8) sampling(8); 'with' is a stop word.
    // by (-length, token): efficient, sampling, training, network -> sorted alphabetically.
    assertEquals(
      Blocking.workPrimary("Efficient Graph Neural Network Training with Sampling", Some(2021)),
      Some("w1|efficient-network-sampling-training|y673"),
    )
  }

  test("work primary key: a long token is cut to 12; no year gives 'ynone'") {
    assertEquals(Blocking.workPrimary("Internationalization", None), Some("w1|internationa|ynone"))
  }

  test("work primary key: a title with no usable token has no key") {
    assertEquals(Blocking.workPrimary("A Big Cat", Some(2020)), None)
    assertEquals(Blocking.workPrimary("Using the study", Some(2020)), None)
  }

  test("work fallback: appends the whitespace-free venue (16 chars max) or 'vnone'") {
    assertEquals(
      keysOf(Blocking.workFallbacks("Graph Networks", Some(2021), Some("VLDB Journal"))),
      List("w1|graph-networks|y673|vvldbjournal"),
    )
    assertEquals(
      keysOf(Blocking.workFallbacks("Graph Networks", Some(2021), None)),
      List("w1|graph-networks|y673|vnone"),
    )
    assertEquals(
      keysOf(
        Blocking.workFallbacks(
          "Graph Networks",
          Some(2021),
          Some("The International Conference on Data"),
        )
      ),
      List("w1|graph-networks|y673|vtheinternational"),
    )
  }

  test("primary and keys: a view that lacks its field has no primary key") {
    val v = EntityView("mention:a", EntityKind.Researcher, SourceType.Dblp)
    assertEquals(Blocking.primary(v), None)
    assertEquals(Blocking.keys(v), Nil)
    val named = v.copy(name = Some("Wei Wang"), year = Some(2020))
    assertEquals(keysOf(Blocking.keys(named)), List("r1|wang|w", "r1|wang|w|y404"))
    assertEquals(Blocking.keys(named).head.keyType, BlockKeyType.Primary)
  }

  test("eligibility is cross-source only: exactly one side must be DBLP") {
    val d  = person("mention:d", SourceType.Dblp, "Wei Wang")
    val d2 = person("mention:d2", SourceType.Dblp, "Wei Wang")
    val c  = person("mention:c", SourceType.Crossref, "Wei Wang")
    val o  = person("mention:o", SourceType.OpenAlex, "Wei Wang")
    assert(Blocking.eligible(d, c))
    assert(Blocking.eligible(c, d))
    assert(Blocking.eligible(d, o))
    assert(!Blocking.eligible(c, o), "two non-DBLP sources")
    assert(!Blocking.eligible(d, d2), "two DBLP researchers without alias evidence")
    assert(!Blocking.eligible(c, c.copy(id = "mention:c2")), "same source")
    assert(!Blocking.eligible(d, d), "self pair")
  }

  test("eligibility: DBLP-DBLP needs alias evidence and only for researchers") {
    val d  = person("mention:d", SourceType.Dblp, "Wei Wang")
    val d2 = person("mention:d2", SourceType.Dblp, "Wei Wang")
    assert(Blocking.eligible(d, d2, aliasLinked = true))
    val w1 = paper("work:d1", SourceType.Dblp, "T")
    val w2 = paper("work:d2", SourceType.Dblp, "T")
    assert(!Blocking.eligible(w1, w2, aliasLinked = true))
    assert(!Blocking.eligible(d, w1))
  }

  test("candidate: canonical id order, source pair and feature version in the provenance") {
    val d = person("mention:b2", SourceType.Dblp, "Wei Wang")
    val c = person("mention:a1", SourceType.Crossref, "Wei Wang")
    val k = BlockKey("r1|wang|w", BlockKeyType.Primary)
    val r = Blocking.candidate(d, c, k, prov()).fold(e => fail(e.toString), identity)
    assertEquals((r.leftId, r.rightId), ("mention:a1", "mention:b2"))
    assertEquals(r.provenance.parameters("leftSource"), "crossref")
    assertEquals(r.provenance.parameters("rightSource"), "dblp")
    assertEquals(r.provenance.parameters("featureSetVersion"), "er-features-v2")
    assertEquals(r.blockKey, "r1|wang|w")
    assertEquals(r.featureSetVersion, "er-features-v2")
    val swapped = Blocking.candidate(c, d, k, prov()).fold(e => fail(e.toString), identity)
    assertEquals(swapped, r)
  }

  test("preferred: primary beats fallback, then the smaller key wins, either argument order") {
    val f                                = Map("name_sim" -> 1.0)
    def mk(key: String, t: BlockKeyType) =
      MatchCandidate
        .build("mention:a", "mention:b", EntityKind.Researcher, key, t, f, Features.Version, prov())
        .fold(e => fail(e.toString), identity)
    val prim = mk("r1|wang|w", BlockKeyType.Primary)
    val fb   = mk("r1|wang|w|y404", BlockKeyType.Fallback)
    val fb2  = mk("r1|wang|w|cchen", BlockKeyType.Fallback)
    assertEquals(Blocking.preferred(prim, fb), prim)
    assertEquals(Blocking.preferred(fb, prim), prim)
    assertEquals(Blocking.preferred(fb, fb2), fb2) // "...|cchen" < "...|y404"
    assertEquals(Blocking.preferred(fb2, fb), fb2)
  }

  private def four = List(
    person("mention:d1", SourceType.Dblp, "Wei Wang", year = 2020),
    person("mention:d2", SourceType.Dblp, "Wei Wang", year = 2000),
    person("mention:c1", SourceType.Crossref, "Wei Wang", year = 2021),
    person("mention:c2", SourceType.Crossref, "Wei Wang", year = 2001),
  )

  test("generate: one primary block pairs every DBLP x non-DBLP couple and nothing else") {
    val g = Blocking.generate(four, 4, prov())
    assertEquals(
      g.candidates.map(c => (c.leftId, c.rightId)),
      List(
        ("mention:c1", "mention:d1"),
        ("mention:c1", "mention:d2"),
        ("mention:c2", "mention:d1"),
        ("mention:c2", "mention:d2"),
      ),
    )
    assert(g.candidates.forall(_.blockKeyType == BlockKeyType.Primary))
    assertEquals(g.oversizeBlocks, Nil)
  }

  test("generate: a primary block over the cap is split by its fallback (year) keys") {
    val g = Blocking.generate(four, 3, prov())
    assertEquals(
      g.candidates.map(c => (c.leftId, c.rightId, c.blockKey)),
      List(
        ("mention:c1", "mention:d1", "r1|wang|w|y404"),
        ("mention:c2", "mention:d2", "r1|wang|w|y400"),
      ),
    )
    assert(g.candidates.forall(_.blockKeyType == BlockKeyType.Fallback))
  }

  test("generate: a block still over the cap after fallback produces NO pair and is listed") {
    val g = Blocking.generate(four, 1, prov())
    assertEquals(g.candidates, Nil)
    assertEquals(g.oversizeBlocks, List(("r1|wang|w|y400", 2), ("r1|wang|w|y404", 2)))
  }

  test("generate: a pair found under two fallback keys is emitted once, under the smaller key") {
    val vs = List(
      person("mention:d1", SourceType.Dblp, "Wei Wang", year = 2020, authors = List("Li Zhang")),
      person(
        "mention:c1",
        SourceType.Crossref,
        "Wei Wang",
        year = 2020,
        authors = List("Li Zhang"),
      ),
      person("mention:d2", SourceType.Dblp, "Wei Wang", year = 2000),
    )
    // primary block has 3 members > cap 2: fallback groups czhang {d1,c1}, y404 {d1,c1}, y400 {d2}
    val g = Blocking.generate(vs, 2, prov())
    assertEquals(
      g.candidates.map(c => (c.leftId, c.rightId, c.blockKey)),
      List(("mention:c1", "mention:d1", "r1|wang|w|czhang")),
    )
  }

  test("generate: output is independent of the input order") {
    val a = Blocking.generate(four, 4, prov())
    val b = Blocking.generate(four.reverse, 4, prov())
    assertEquals(a.candidates, b.candidates)
  }
