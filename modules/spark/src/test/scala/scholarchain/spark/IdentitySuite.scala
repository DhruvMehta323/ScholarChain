package scholarchain.spark

import org.apache.spark.sql.Row

import scholarchain.core.*

/** Researcher identity (Phase 06 section 4.1): base identities, accepted-link merging and M5. */
class IdentitySuite extends munit.FunSuite:

  private val spark    = TestSpark.spark
  private lazy val cfg =
    TestSpark.config(TestSpark.useCheckpointFor("identity"))

  private val OrcA = "0000-0002-1825-0097"
  private val OrcB = "0000-0001-5109-3700"

  /** A mention under test. `source` is dblp, crossref or openalex. */
  private final case class M(
      work: String,
      pos: Int,
      name: String,
      orcid: Option[String] = None,
      source: String = "dblp",
  ):
    val id: String = Ok.get(Ids.mentionId(work, pos))

  private def dm(work: String, name: String, pos: Int = 0): M = M(s"work:dblp:t/$work", pos, name)
  private def ext(work: String, name: String, orcid: Option[String], src: String = "crossref"): M =
    M(s"work:$src:$work", 0, name, orcid, src)

  private def run(
      ms: List[M],
      ds: List[(String, String, String, String)],
      aliases: List[Row] = Nil,
  ): Identity.Result =
    val mentions = TestSpark.frame(
      InputSchemas.Mentions,
      ms.map(m => Row(m.id, m.work, m.pos, m.name, m.orcid.orNull, m.source)),
    )
    val decisions = TestSpark.frame(
      InputSchemas.Decisions,
      ds.map((l, r, k, o) => Row(l, r, k, o, "match-policy.v1")),
    )
    Identity.build(
      mentions,
      TestSpark.frame(InputSchemas.Aliases, aliases),
      decisions,
      "match-policy.v1",
      cfg,
    )

  private def acc(l: M, r: M)  = (l.id, r.id, "researcher", "accepted")
  private def rej(l: M, r: M)  = (l.id, r.id, "researcher", "rejected")
  private def conf(l: M, r: M) = (l.id, r.id, "researcher", "conflict")
  private def unr(l: M, r: M)  = (l.id, r.id, "researcher", "unresolved")

  private def base(name: String): String = Ok.get(Ids.researcherIdDblpPerson(name))
  private def unresolved(m: M): String   = Ok.get(Ids.researcherIdUnresolved(m.id))

  private def clusters(r: Identity.Result): Map[String, Row] =
    r.clusters.collect().toList.map(c => c.s("researcherId") -> c).toMap

  private def ofMention(r: Identity.Result, m: M): String =
    r.mentionResearcher.collect().toList.find(_.s("mentionId") == m.id).get.s("researcherId")

  // ---- base identities ------------------------------------------------------------------------

  test("the same DBLP author string is one base identity") {
    val a1 = dm("A1", "Zhen Wang"); val a2 = dm("A2", "Zhen Wang")
    val r  = run(List(a1, a2), Nil)
    val cs = clusters(r)
    assertEquals(cs.keySet, Set(base("Zhen Wang")))
    val c = cs(base("Zhen Wang"))
    assertEquals(c.strs("memberMentionIds"), List(a1.id, a2.id).sorted)
    assertEquals(c.i("memberCount"), 2)
    assertEquals((c.s("ruleFired"), c.s("cannotLinkStatus")), ("dblp_person_exact", "clear"))
    assertEquals(c.s("displayName"), "Zhen Wang")
    assertEquals(c.s("policyVersion"), "match-policy.v1")
    assertEquals(ofMention(r, a1), ofMention(r, a2))
    assertEquals(r.blocked.count(), 0L)
  }

  test("homonym suffixes stay separate identities") {
    val a = dm("A1", "Zhen Wang"); val b = dm("A2", "Zhen Wang 0008")
    val r = run(List(a, b), Nil)
    assertEquals(clusters(r).keySet, Set(base("Zhen Wang"), base("Zhen Wang 0008")))
    assertNotEquals(ofMention(r, a), ofMention(r, b))
  }

  test("an HJ1 alias record joins several strings into one base identity named by the smallest") {
    val a     = dm("A1", "Z. Wang"); val b = dm("A2", "Zhen Wang")
    val alias = Row("key", Seq("Zhen Wang", "Z. Wang"), Seq.empty[String])
    val r     = run(List(a, b), Nil, List(alias))
    assertEquals(clusters(r).keySet, Set(base("Z. Wang")))
    assertEquals(ofMention(r, b), base("Z. Wang"))
  }

  test("a non-DBLP mention that is never accepted stays an unresolved singleton") {
    val a = dm("A1", "Ada"); val e = ext("X1", "A. Lovelace", None)
    val r = run(List(a, e), Nil)
    val c = clusters(r)(unresolved(e))
    assertEquals((c.s("ruleFired"), c.s("cannotLinkStatus")), ("unresolved_singleton", "clear"))
    assertEquals(c.strs("memberMentionIds"), List(e.id))
    assertEquals(clusters(r).size, 2)
  }

  // ---- accepted links -------------------------------------------------------------------------

  test("an ACCEPTED researcher decision merges two DBLP persons under the smallest key's base ID") {
    val a = dm("A1", "Ann Lee"); val b = dm("A2", "A. Lee")
    val r = run(List(a, b), List(acc(a, b)))
    // smallest key by code point: "A. Lee" ('.' = 46 < 'n' = 110); the ID is its DBLP base ID
    val expected = base("A. Lee")
    val cs       = clusters(r)
    assertEquals(cs.keySet, Set(expected))
    assertNotEquals(
      expected,
      Ok.get(Ids.researcherIdMergedCluster(List(base("Ann Lee"), base("A. Lee")))),
    )
    val c = cs(expected)
    assertEquals(c.strs("baseIdentityIds"), List(base("Ann Lee"), base("A. Lee")).sorted)
    assertEquals(c.strs("dblpPersonKeys"), List("A. Lee", "Ann Lee"))
    assertEquals(c.strs("memberMentionIds"), List(a.id, b.id).sorted)
    assertEquals((c.s("ruleFired"), c.s("cannotLinkStatus")), ("accepted_link_component", "clear"))
    assertEquals(ofMention(r, a), expected)
    assertEquals(ofMention(r, b), expected)
    assertEquals(r.blocked.count(), 0L)
    assert(r.convergence.converged)
  }

  test("an accepted chain A~B~C without any cannot-link evidence merges all three") {
    val a = dm("A1", "Ann"); val b = dm("A2", "Bea"); val c = dm("A3", "Cat")
    val r = run(List(a, b, c), List(acc(a, b), acc(b, c)))
    assertEquals(clusters(r).size, 1)
    assertEquals(ofMention(r, a), ofMention(r, c))
  }

  test("M5: accepted A~B and B~C with a REJECTED A-C stays unmerged and is listed") {
    val a  = dm("A1", "Ann"); val b = dm("A2", "Bea"); val c = dm("A3", "Cat")
    val r  = run(List(a, b, c), List(acc(a, b), acc(b, c), rej(a, c)))
    val cs = clusters(r)
    assertEquals(cs.keySet, Set(base("Ann"), base("Bea"), base("Cat")))
    assert(cs.values.forall(x => x.s("cannotLinkStatus") == "blocked"), cs.toString)
    assert(cs.values.forall(x => x.s("ruleFired") == "cannot_link_fallback"), cs.toString)
    assert(cs.values.forall(x => x.i("memberCount") == 1))
    val blocked = r.blocked.collect().toList
    assertEquals(blocked.size, 1)
    val bl = blocked.head
    assertEquals(bl.strs("memberBaseIds"), List(base("Ann"), base("Bea"), base("Cat")).sorted)
    assertEquals(bl.i("memberCount"), 3)
    assertEquals(bl.s("reason"), "rejected_or_conflict_pair")
    assertEquals(bl.l("rejectedOrConflictPairs"), 1L)
    assertEquals(bl.i("distinctOrcids"), 0)
    assertEquals(ofMention(r, a), base("Ann"))
    assertEquals(ofMention(r, c), base("Cat"))
  }

  test("M5: a CONFLICT between members of an accepted component also blocks the merge") {
    val a = dm("A1", "Ann"); val b = dm("A2", "Bea"); val c = dm("A3", "Cat")
    val r = run(List(a, b, c), List(acc(a, b), acc(b, c), conf(a, c)))
    assertEquals(clusters(r).keySet, Set(base("Ann"), base("Bea"), base("Cat")))
    assertEquals(r.blocked.count(), 1L)
    assertEquals(r.blocked.collect().head.s("reason"), "rejected_or_conflict_pair")
  }

  test("M5: a rejection against ANY mention of a person counts against the person") {
    val a1 = dm("A1", "Ann"); val a2 = dm("A2", "Ann"); val b = dm("A3", "Bea")
    val c  = dm("A4", "Cat")
    // accepted through mention a1, rejected through the other mention a2 of the same person
    val r = run(List(a1, a2, b, c), List(acc(a1, b), acc(b, c), rej(a2, c)))
    assertEquals(r.blocked.count(), 1L)
    assertEquals(clusters(r).keySet, Set(base("Ann"), base("Bea"), base("Cat")))
  }

  test("a rejected pair inside one base identity is ignored: a person is never split") {
    val a1 = dm("A1", "Ann"); val a2 = dm("A2", "Ann")
    val r  = run(List(a1, a2), List(rej(a1, a2)))
    assertEquals(clusters(r).keySet, Set(base("Ann")))
    assertEquals(clusters(r)(base("Ann")).i("memberCount"), 2)
    assertEquals(r.blocked.count(), 0L)
  }

  test("M5: two different validated ORCIDs in one accepted component block the merge") {
    val p  = dm("A1", "Pat Quinn")
    val x  = ext("X1", "P. Quinn", Some(OrcA))
    val y  = ext("X2", "Pat Q", Some(OrcB), "openalex")
    val r  = run(List(p, x, y), List(acc(p, x), acc(p, y)))
    val cs = clusters(r)
    assertEquals(cs.size, 3)
    assert(cs.values.forall(_.s("cannotLinkStatus") == "blocked"), cs.toString)
    assertEquals(ofMention(r, p), base("Pat Quinn"))
    assertEquals(ofMention(r, x), unresolved(x))
    assertEquals(ofMention(r, y), unresolved(y))
    val bl = r.blocked.collect().toList
    assertEquals(bl.size, 1)
    assertEquals(bl.head.s("reason"), "two_orcids")
    assertEquals(bl.head.i("distinctOrcids"), 2)
    assertEquals(bl.head.l("rejectedOrConflictPairs"), 0L)
  }

  test("exactly one validated ORCID is a property of the cluster, never its ID") {
    val p        = dm("A1", "Pat Quinn"); val x = ext("X1", "P. Quinn", Some(OrcA))
    val r        = run(List(p, x), List(acc(p, x)))
    val expected = base("Pat Quinn")
    assertEquals(clusters(r).keySet, Set(expected))
    val c = clusters(r)(expected)
    assertEquals(c.s("orcid"), OrcA)
    assertEquals(c.strs("orcids"), List(OrcA))
    assertEquals(c.strs("memberMentionIds"), List(p.id, x.id).sorted)
    assertEquals(c.s("ruleFired"), "accepted_link_component")
    assert(!clusters(r).keySet.exists(_.startsWith("researcher:orcid:")))
    // the mention that carried the ORCID is the evidence of the property
    val ev = r.orcidEvidence.collect().toList
    assertEquals(
      ev.map(e => (e.s("researcherId"), e.s("orcid"), e.strs("mentionIds"))),
      List((expected, OrcA, List(x.id))),
    )
  }

  test("an invalid ORCID string is ignored for the property and for the two-ORCID block") {
    val p = dm("A1", "Pat Quinn")
    val x = ext("X1", "P. Quinn", Some("0000-0002-1825-0098")) // bad checksum
    val y = ext("X2", "Pat Q", Some(OrcB), "openalex")
    val r = run(List(p, x, y), List(acc(p, x), acc(p, y)))
    assertEquals(r.blocked.count(), 0L)
    assertEquals(clusters(r).keySet, Set(base("Pat Quinn")))
    val c = clusters(r)(base("Pat Quinn"))
    assertEquals(c.s("orcid"), OrcB)
    assertEquals(c.strs("orcids"), List(OrcB))
    assertEquals(c.i("memberCount"), 3)
  }

  test(
    "a base identity with two validated ORCIDs: orcid is null, orcids holds both, and it is not split"
  ) {
    val p  = dm("A1", "Pat Quinn")
    val p2 = dm("A2", "Pat Quinn")
    val x  = ext("X1", "P. Quinn", Some(OrcA))
    val y  = ext("X2", "Pat Q", Some(OrcB), "openalex")
    // two ORCIDs on externals accepted into ONE person: blocked (M5), the person keeps its base ID
    val blockedRun = run(List(p, x, y), List(acc(p, x), acc(p, y)))
    assertEquals(blockedRun.blocked.count(), 1L)
    // two ORCIDs on the same DBLP person's alias record: the base identity itself holds two
    val alias = Row("key", Seq("Pat Quinn"), Seq(OrcA, OrcB))
    val r     = run(List(p, p2), Nil, List(alias))
    val c     = clusters(r)(base("Pat Quinn"))
    assert(c.isNull("orcid"), c.toString)
    assertEquals(c.strs("orcids"), List(OrcA, OrcB).sorted)
    assertEquals(c.i("memberCount"), 2)
    assertEquals(r.blocked.count(), 0L)
    assertEquals(Identity.counts(r).clustersWithTwoOrMoreOrcids, 1L)
  }

  test("two clusters sharing one ORCID both keep their DBLP IDs and both point at the ORCID") {
    val p  = dm("A1", "Pat Quinn"); val q             = dm("A2", "Quinn P")
    val x  = ext("X1", "P. Quinn", Some(OrcA)); val y = ext("X2", "Q. P.", Some(OrcA), "openalex")
    val r  = run(List(p, q, x, y), List(acc(p, x), acc(q, y)))
    val cs = clusters(r)
    assertEquals(cs.keySet, Set(base("Pat Quinn"), base("Quinn P")))
    assert(!cs.keySet.exists(_.startsWith("researcher:orcid:")), cs.keySet.toString)
    assertEquals(ofMention(r, p), base("Pat Quinn"))
    assertEquals(ofMention(r, x), base("Pat Quinn"))
    assertEquals(ofMention(r, q), base("Quinn P"))
    assertEquals(ofMention(r, y), base("Quinn P"))
    assert(cs.values.forall(c => c.s("orcid") == OrcA && c.strs("orcids") == List(OrcA)))
    assert(cs.values.forall(_.s("cannotLinkStatus") == "clear"))
    assertEquals(r.blocked.count(), 0L)
    // counters: one ORCID shared by two clusters
    val shared = r.sharedOrcids.collect().toList
    assertEquals(shared.size, 1)
    assertEquals(shared.head.s("orcid"), OrcA)
    assertEquals(shared.head.l("clusterCount"), 2L)
    assertEquals(shared.head.strs("researcherIds"), List(base("Pat Quinn"), base("Quinn P")).sorted)
    val n = Identity.counts(r)
    assertEquals(n.orcidsSharedByClusters, 1L)
    assertEquals(n.clustersSharingAnOrcid, 2L)
    assertEquals(n.orcidCollisionClusters, 2L) // deprecated alias
    assertEquals(n.clustersWithOrcid, 2L)
    // both clusters get a RESOLVES_TO edge, to the same target
    val edges = Enrichment
      .resolvesTo(r.clusters, r.orcidEvidence, org.apache.spark.sql.functions.lit("{}"))
      .collect()
      .toList
    assertEquals(edges.map(_.s("sourceId")).sorted, List(base("Pat Quinn"), base("Quinn P")).sorted)
    assertEquals(edges.map(_.s("targetId")).distinct, List("researcher:orcid:" + OrcA))
    assertEquals(edges.map(_.s("edgeType")).distinct, List("RESOLVES_TO"))
  }

  // ---- decisions that never merge -------------------------------------------------------------

  test("UNRESOLVED and CONFLICT decisions never merge anything") {
    val a = dm("A1", "Ann"); val b = dm("A2", "Bea"); val c = dm("A3", "Cat");
    val d = dm("A4", "Dee")
    val r = run(List(a, b, c, d), List(unr(a, b), conf(c, d)))
    assertEquals(clusters(r).keySet, Set("Ann", "Bea", "Cat", "Dee").map(base))
    assertEquals(r.blocked.count(), 0L)
    assert(clusters(r).values.forall(_.s("cannotLinkStatus") == "clear"))
  }

  test("only researcher decisions are used; unknown mentions are counted, never merged") {
    val a     = dm("A1", "Ann"); val b = dm("A2", "Bea")
    val ghost = "mention:" + "f" * 64
    val ds    = List(
      (a.id, b.id, "work", "accepted"),
      (a.id, ghost, "researcher", "accepted"),
    )
    val r = run(List(a, b), ds)
    assertEquals(clusters(r).keySet, Set(base("Ann"), base("Bea")))
    assertEquals(r.unmappedDecisions.count(), 1L)
    assertEquals(Identity.counts(r).unmappedDecisions, 1L)
  }

  // ---- counts and mention map -----------------------------------------------------------------

  test("counts: mentions, base identities, merged, absorbed, blocked and unresolved") {
    val a = dm("A1", "Ann"); val b = dm("A2", "Bea"); val e1 = ext("X1", "Ann L", None)
    val c = dm("A3", "Cat"); val d = dm("A4", "Dan"); val e2 = ext("X2", "Zed", None)
    val r = run(List(a, b, e1, c, d, e2), List(acc(a, b), acc(a, e1)))
    val n = Identity.counts(r)
    assertEquals(n.mentions, 6L)
    assertEquals(n.dblpMentions, 4L)
    assertEquals(n.baseIdentitiesDblp, 4L)
    assertEquals(n.externalMentions, 2L)
    assertEquals(n.clusters, 4L) // {Ann,Bea,Ann L}, Cat, Dan, Zed
    assertEquals(n.mergedClusters, 1L)
    assertEquals(n.absorbedExternalClusters, 1L)
    assertEquals(n.cannotLinkBlockedComponents, 0L)
    assertEquals(n.unresolvedSingletons, 1L)
  }

  test("every mention maps to exactly one researcher and its base identity") {
    val a  = dm("A1", "Ann"); val b = dm("A2", "Bea"); val e = ext("X1", "Ann L", None)
    val r  = run(List(a, b, e), List(acc(a, e)))
    val mr = r.mentionResearcher.collect().toList
    assertEquals(mr.map(_.s("mentionId")).sorted, List(a.id, b.id, e.id).sorted)
    val byId = mr.map(x => x.s("mentionId") -> x).toMap
    assertEquals(byId(a.id).s("baseIdentityId"), base("Ann"))
    assertEquals(byId(e.id).s("baseIdentityId"), unresolved(e))
    assertEquals(byId(e.id).s("sourceType"), "crossref")
    assertEquals(byId(a.id).s("researcherId"), byId(e.id).s("researcherId"))
  }

  test(
    "end to end: a pair made a self-pair by an accepted merge is dropped, the rest maps to the smallest key's base id"
  ) {
    val a     = dm("A1", "Ann Lee"); val b = dm("A2", "A. Lee"); val c = dm("A3", "Cat")
    val r     = run(List(a, b, c), List(acc(a, b)))
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(Row("work:dblp:t/A1", 2001, a.id, b.id), Row("work:dblp:t/A1", 2001, a.id, c.id)),
    )
    val mapped = Graph.mappedPairs(pairs, r.mentionResearcher)
    assertEquals(mapped.count(), 2L)
    assertEquals(
      mapped.where("researcherA = researcherB").count(),
      1L,
    ) // Ann Lee and A. Lee are one person now
    val canon = Graph.canonicalPairs(mapped).collect().toList
    assertEquals(canon.size, 1)
    val merged = base("A. Lee") // smallest key of the merged cluster
    assertEquals(
      Set(canon.head.s("researcherA"), canon.head.s("researcherB")),
      Set(merged, base("Cat")),
    )
  }

  // ---- repair M1: implicit cannot-link between DBLP homonyms -------------------------------------

  private def blockedOf(r: Identity.Result): List[Row] = r.blocked.collect().toList

  test("homonymStemOf: one trailing space plus exactly four digits is removed, nothing else") {
    assertEquals(Identity.homonymStemOf("Zhen Wang 0008"), "Zhen Wang")
    assertEquals(Identity.homonymStemOf("Zhen Wang"), "Zhen Wang")
    assertEquals(Identity.homonymStemOf("X 0008 0009"), "X 0008") // only ONE suffix
    assertEquals(Identity.homonymStemOf("X 12"), "X 12")
    assertEquals(Identity.homonymStemOf("X 123"), "X 123")
    assertEquals(Identity.homonymStemOf("X 00008"), "X 00008")
    assertEquals(Identity.homonymStemOf(" 0008"), " 0008")   // nothing before the space
    assertEquals(Identity.homonymStemOf("X0008"), "X0008")   // no space
    assertEquals(Identity.homonymStemOf("X 000a"), "X 000a") // not digits
  }

  test("M1: 'X 0008' + an external + 'X 0009' is blocked, members fall back to base IDs") {
    val a = dm("A1", "X 0008"); val e = ext("E1", "X.", None); val c = dm("A2", "X 0009")
    val r = run(List(a, e, c), List(acc(a, e), acc(e, c)))
    val b = blockedOf(r)
    assertEquals(b.size, 1)
    assertEquals(b.head.s("reason"), "distinct_dblp_homonyms")
    assertEquals(b.head.i("distinctDblpHomonymGroups"), 1)
    assertEquals(b.head.l("rejectedOrConflictPairs"), 0L)
    assertEquals(b.head.i("distinctOrcids"), 0)
    assertEquals(b.head.i("memberCount"), 3)
    assertEquals(
      b.head.strs("memberBaseIds"),
      List(base("X 0008"), base("X 0009"), unresolved(e)).sorted,
    )
    // fallback: nothing merged, nobody absorbed
    assertEquals(ofMention(r, a), base("X 0008"))
    assertEquals(ofMention(r, c), base("X 0009"))
    assertEquals(ofMention(r, e), unresolved(e))
    val cs = clusters(r)
    assertEquals(cs.size, 3)
    assert(cs.values.forall(x => x.s("cannotLinkStatus") == "blocked"), cs.toString)
    assert(cs.values.forall(x => x.s("ruleFired") == "cannot_link_fallback"), cs.toString)
    val n = Identity.counts(r)
    assertEquals(n.cannotLinkBlockedComponents, 1L)
    assertEquals(n.cannotLinkBlockedBaseIdentities, 3L)
    assertEquals(n.blockedByDistinctDblpHomonyms, 1L)
    assertEquals(n.blockedByRejectedOrConflict, 0L)
    assertEquals(n.blockedByTwoOrcids, 0L)
    assertEquals(n.mergedClusters, 0L)
  }

  test("M1: an unsuffixed key and a suffixed key with the same stem are homonyms: blocked") {
    val a = dm("A1", "X"); val e = ext("E1", "X.", None); val c = dm("A2", "X 0008")
    val r = run(List(a, e, c), List(acc(a, e), acc(e, c)))
    assertEquals(blockedOf(r).map(_.s("reason")), List("distinct_dblp_homonyms"))
    assertEquals(clusters(r).keySet, Set(base("X"), base("X 0008"), unresolved(e)))
  }

  test("M1: two DBLP homonyms accepted DIRECTLY (no external between them) are blocked") {
    val a = dm("A1", "Zhen Wang 0008"); val b = dm("A2", "Zhen Wang 0009")
    val r = run(List(a, b), List(acc(a, b)))
    assertEquals(blockedOf(r).map(_.s("reason")), List("distinct_dblp_homonyms"))
    assertEquals(clusters(r).keySet, Set(base("Zhen Wang 0008"), base("Zhen Wang 0009")))
  }

  test("M1: a 2-, 3- or 5-digit suffix is NOT a homonym suffix: the merge is allowed") {
    List(("X", "X 12"), ("X", "X 123"), ("X", "X 00008"), ("X 12", "X 00008")).foreach { (k1, k2) =>
      val a = dm("A1", k1); val b = dm("A2", k2)
      val r = run(List(a, b), List(acc(a, b)))
      assertEquals(r.blocked.count(), 0L, s"$k1 / $k2")
      assertEquals(clusters(r).size, 1, s"$k1 / $k2")
      // merged under the smallest key
      assertEquals(clusters(r).keySet, Set(base(List(k1, k2).min)), s"$k1 / $k2")
    }
  }

  test("M1: a single homonym person plus several externals is the usual absorption, not blocked") {
    val a  = dm("A1", "X 0008"); val e1 = ext("E1", "X. A", None)
    val e2 = ext("E2", "X. B", None, "openalex")
    val r  = run(List(a, e1, e2), List(acc(a, e1), acc(a, e2)))
    assertEquals(r.blocked.count(), 0L)
    assertEquals(clusters(r).keySet, Set(base("X 0008")))
    assertEquals(clusters(r)(base("X 0008")).i("memberCount"), 3)
    assertEquals(Identity.counts(r).absorbedExternalClusters, 1L)
  }

  test("M1: two homonyms in different components (never linked) are not blocked") {
    val a = dm("A1", "X 0008"); val b = dm("A2", "X 0009")
    val r = run(List(a, b), Nil)
    assertEquals(r.blocked.count(), 0L)
    assertEquals(clusters(r).keySet, Set(base("X 0008"), base("X 0009")))
  }

  test(
    "M1: the reason combines with rejected_or_conflict_pair and with two_orcids, in that order"
  ) {
    val a        = dm("A1", "X 0008"); val b = dm("A2", "X 0009"); val c = dm("A3", "Y")
    val withPair = run(List(a, b, c), List(acc(a, c), acc(c, b), rej(a, b)))
    assertEquals(
      blockedOf(withPair).map(_.s("reason")),
      List("rejected_or_conflict_pair+distinct_dblp_homonyms"),
    )
    assertEquals(blockedOf(withPair).head.l("rejectedOrConflictPairs"), 1L)
    assertEquals(blockedOf(withPair).head.i("distinctDblpHomonymGroups"), 1)
    val n1 = Identity.counts(withPair)
    // one component with two reasons is counted under both, and once as a component
    assertEquals(
      (
        n1.cannotLinkBlockedComponents,
        n1.blockedByRejectedOrConflict,
        n1.blockedByDistinctDblpHomonyms,
      ),
      (1L, 1L, 1L),
    )

    val e1   = ext("E1", "P. One", Some(OrcA)); val e2 = ext("E2", "P. Two", Some(OrcB), "openalex")
    val all3 = run(
      List(a, b, e1, e2),
      List(acc(a, e1), acc(a, b), acc(b, e2), rej(e1, e2)),
    )
    assertEquals(
      blockedOf(all3).map(_.s("reason")),
      List("rejected_or_conflict_pair+two_orcids+distinct_dblp_homonyms"),
    )
    val n3 = Identity.counts(all3)
    assertEquals(
      (n3.blockedByRejectedOrConflict, n3.blockedByTwoOrcids, n3.blockedByDistinctDblpHomonyms),
      (1L, 1L, 1L),
    )

    val twoOrcidsAndHomonyms = run(
      List(a, b, e1, e2),
      List(acc(a, e1), acc(a, b), acc(b, e2)),
    )
    assertEquals(
      blockedOf(twoOrcidsAndHomonyms).map(_.s("reason")),
      List("two_orcids+distinct_dblp_homonyms"),
    )
  }

  test("M1: distinctDblpHomonymGroups counts the stems that are shared") {
    val a  = dm("A1", "X 0008"); val b = dm("A2", "X"); val c = dm("A3", "Y 0001")
    val d  = dm("A4", "Y 0002"); val z = dm("A5", "Z 0001")
    val r  = run(List(a, b, c, d, z), List(acc(a, b), acc(b, c), acc(c, d), acc(d, z)))
    val bl = blockedOf(r)
    assertEquals(bl.size, 1)
    assertEquals(
      bl.head.i("distinctDblpHomonymGroups"),
      2,
    ) // stems X (2 keys) and Y (2 keys); Z has one
    assertEquals(bl.head.i("memberCount"), 5)
  }

  test(
    "M1 (R-M1, all names): an alias group's non-representative name conflicts with another base: BLOCKED"
  ) {
    // The alias record ["Zhen Wang 0008", "Z. Wang"] is ONE base identity keyed "Z. Wang" (smallest
    // string), but ALL its names count: its member name "Zhen Wang 0008" and the other base's
    // "Zhen Wang 0009" share the stem "Zhen Wang" and are different names of different bases.
    val a     = dm("A1", "Z. Wang"); val b = dm("A2", "Zhen Wang 0008");
    val c     = dm("A3", "Zhen Wang 0009")
    val alias = Row("key", Seq("Zhen Wang 0008", "Z. Wang"), Seq.empty[String])
    val r     = run(List(a, b, c), List(acc(a, c)), List(alias))
    val bl    = blockedOf(r)
    assertEquals(bl.size, 1)
    assertEquals(bl.head.s("reason"), "distinct_dblp_homonyms")
    assertEquals(bl.head.i("distinctDblpHomonymGroups"), 1)
    assertEquals(bl.head.i("unsuffixedVsSuffixedGroups"), 0)
    assertEquals(bl.head.i("memberCount"), 2)
    assertEquals(
      bl.head.strs("memberBaseIds"),
      List(base("Z. Wang"), base("Zhen Wang 0009")).sorted,
    )
    // members fall back to their base IDs; the alias group stays ONE base identity (both mentions)
    assertEquals(clusters(r).keySet, Set(base("Z. Wang"), base("Zhen Wang 0009")))
    assertEquals(ofMention(r, a), base("Z. Wang"))
    assertEquals(ofMention(r, b), base("Z. Wang"))
    assertEquals(ofMention(r, c), base("Zhen Wang 0009"))
    assert(
      clusters(r).values.forall(x => x.s("cannotLinkStatus") == "blocked"),
      clusters(r).toString,
    )
    assert(
      clusters(r).values.forall(x => x.s("ruleFired") == "cannot_link_fallback"),
      clusters(r).toString,
    )
    assertEquals(clusters(r)(base("Z. Wang")).i("memberCount"), 2)
  }

  test("M1 negative control: an alias group with NO conflicting suffix still merges") {
    // names: "Z. Wang" (stem itself), "Zhen Wang 0008" (alias member, stem "Zhen Wang") and
    // "Zhen Smith" (stem "Zhen Smith"): three different stems, no conflict
    val a     = dm("A1", "Z. Wang"); val b = dm("A2", "Zhen Wang 0008");
    val c     = dm("A3", "Zhen Smith")
    val alias = Row("key", Seq("Zhen Wang 0008", "Z. Wang"), Seq.empty[String])
    val r     = run(List(a, b, c), List(acc(a, c)), List(alias))
    assertEquals(r.blocked.count(), 0L)
    // "Z. Wang" < "Zhen Smith" ('.' < 'h'): the merged cluster is named by "Z. Wang"
    assertEquals(clusters(r).keySet, Set(base("Z. Wang")))
    val cl = clusters(r)(base("Z. Wang"))
    assertEquals(cl.i("memberCount"), 3)
    assertEquals(cl.strs("memberMentionIds"), List(a.id, b.id, c.id).sorted)
    assertEquals(
      (cl.s("ruleFired"), cl.s("cannotLinkStatus")),
      ("accepted_link_component", "clear"),
    )
    assertEquals(Identity.counts(r).cannotLinkBlockedComponents, 0L)
  }

  test("M1: 'Zhen Wang 0008' aliased with 'Zhen Wang' is one base identity and is not blocked") {
    val a     = dm("A1", "Zhen Wang"); val b = dm("A2", "Zhen Wang 0008"); val c = dm("A3", "Other")
    val alias = Row("key", Seq("Zhen Wang 0008", "Zhen Wang"), Seq.empty[String])
    val alone = run(List(a, b), Nil, List(alias))
    assertEquals(alone.blocked.count(), 0L)
    assertEquals(clusters(alone).keySet, Set(base("Zhen Wang"))) // smallest string: the prefix
    assertEquals(clusters(alone)(base("Zhen Wang")).i("memberCount"), 2)
    // linked to a third base identity: two names with one stem, but ONE base identity: still merges
    val linked = run(List(a, b, c), List(acc(b, c)), List(alias))
    assertEquals(linked.blocked.count(), 0L)
    assertEquals(clusters(linked).keySet, Set(base("Other")))
    assertEquals(clusters(linked)(base("Other")).i("memberCount"), 3)
  }

  test(
    "M1: 'X' vs 'X 0008' in different bases is blocked and counted as unsuffixed-vs-suffixed"
  ) {
    val a  = dm("A1", "X"); val b = dm("A2", "X 0008")
    val r  = run(List(a, b), List(acc(a, b)))
    val bl = blockedOf(r)
    assertEquals(bl.map(_.s("reason")), List("distinct_dblp_homonyms"))
    assertEquals(bl.head.i("distinctDblpHomonymGroups"), 1)
    assertEquals(bl.head.i("unsuffixedVsSuffixedGroups"), 1)
    assertEquals(clusters(r).keySet, Set(base("X"), base("X 0008")))
    val n = Identity.counts(r)
    assertEquals(n.cannotLinkBlockedUnsuffixedVsSuffixed, 1L)
    assertEquals(n.blockedByDistinctDblpHomonyms, 1L)
    assertEquals(n.cannotLinkBlockedComponents, 1L)
  }

  test("M1: 'X 0008' vs 'X 0009' is blocked but is NOT counted as unsuffixed-vs-suffixed") {
    val a  = dm("A1", "X 0008"); val b = dm("A2", "X 0009")
    val r  = run(List(a, b), List(acc(a, b)))
    val bl = blockedOf(r)
    assertEquals(bl.map(_.s("reason")), List("distinct_dblp_homonyms"))
    assertEquals(bl.head.i("unsuffixedVsSuffixedGroups"), 0)
    val n = Identity.counts(r)
    assertEquals(n.cannotLinkBlockedUnsuffixedVsSuffixed, 0L)
    assertEquals(n.blockedByDistinctDblpHomonyms, 1L)
  }

  test(
    "M1: the unsuffixed-vs-suffixed counter overlaps: X, X 0008 and X 0009 count the stem once"
  ) {
    val a  = dm("A1", "X"); val b = dm("A2", "X 0008"); val c = dm("A3", "X 0009")
    val r  = run(List(a, b, c), List(acc(a, b), acc(b, c)))
    val bl = blockedOf(r)
    assertEquals(bl.size, 1)
    assertEquals(bl.head.i("distinctDblpHomonymGroups"), 1) // one stem
    assertEquals(bl.head.i("unsuffixedVsSuffixedGroups"), 1)
    assertEquals(Identity.counts(r).cannotLinkBlockedUnsuffixedVsSuffixed, 1L)
  }

  test("M1: the same exact name in two bases is not blocked by the homonym rule alone") {
    // helper level: (base, name) rows; one name shared by two bases has ONE distinct name
    val names = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("comp string, baseId string, name string"),
      List(Row("c", "b1", "X 0008"), Row("c", "b2", "X 0008")),
    )
    assertEquals(Identity.homonymGroups(names, "comp").count(), 0L)
    // pipeline level: a DBLP "Ann" and a crossref "Ann" (two bases, one exact name) accepted: merges
    val a = dm("A1", "Ann"); val e = ext("E1", "Ann", None)
    val r = run(List(a, e), List(acc(a, e)))
    assertEquals(r.blocked.count(), 0L)
    assertEquals(clusters(r).keySet, Set(base("Ann")))
    assertEquals(clusters(r)(base("Ann")).i("memberCount"), 2)
  }

  test("M1: homonymGroups counts one group per stem, with its bases, names and flag") {
    val names = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("comp string, baseId string, name string"),
      List(
        Row("c", "b1", "X"),
        Row("c", "b2", "X 0008"),
        Row("c", "b3", "Y 0001"),
        Row("c", "b4", "Y 0002"),
        Row("c", "b5", "Z 0001"),
        Row("c", "b6", "W 0001"), // same stem W, one base: not a group
        Row("c", "b6", "W"),
      ),
    )
    val groups = Identity
      .homonymGroups(names, "comp")
      .collect()
      .toList
      .map(g => (g.s("stem"), g.l("bases"), g.l("names"), g.b("unsuffixedVsSuffixed")))
      .sorted
    assertEquals(groups, List(("X", 2L, 2L, true), ("Y", 2L, 2L, false)))
  }

  // ---- repair M2: rejections inside one base identity --------------------------------------------

  test(
    "M2: a REJECTED and a CONFLICT pair inside one base identity are ignored, listed and counted"
  ) {
    val a1 = dm("A1", "Ann"); val a2 = dm("A2", "Ann"); val a3 = dm("A3", "Ann")
    val r  = run(List(a1, a2, a3), List(rej(a1, a2), conf(a2, a3)))
    assertEquals(clusters(r).keySet, Set(base("Ann")))
    assertEquals(clusters(r)(base("Ann")).i("memberCount"), 3)
    assertEquals(clusters(r)(base("Ann")).s("cannotLinkStatus"), "clear")
    assertEquals(r.blocked.count(), 0L)
    val listed = r.withinBaseRejections.collect().toList
    assertEquals(
      listed
        .map(x =>
          (x.s("leftId"), x.s("rightId"), x.s("outcome"), x.s("baseIdentityId"), x.s("baseName"))
        )
        .toSet,
      Set(
        (a1.id, a2.id, "rejected", base("Ann"), "Ann"),
        (a2.id, a3.id, "conflict", base("Ann"), "Ann"),
      ),
    )
    assertEquals(Identity.counts(r).withinBaseRejectionsIgnored, 2L)
    assertEquals(Identity.counts(r).cannotLinkBlockedComponents, 0L)
  }

  test("M2: accepted and unresolved decisions inside one base identity are not listed") {
    val a1 = dm("A1", "Ann"); val a2 = dm("A2", "Ann")
    val r  = run(List(a1, a2), List(acc(a1, a2), unr(a1, a2)))
    assertEquals(r.withinBaseRejections.count(), 0L)
    assertEquals(Identity.counts(r).withinBaseRejectionsIgnored, 0L)
  }

  test("M2: a within-base rejection inside an accepted component does not block the merge") {
    val a1 = dm("A1", "Ann"); val a2 = dm("A2", "Ann"); val b = dm("A3", "Bea")
    val r  = run(List(a1, a2, b), List(acc(a1, b), rej(a1, a2)))
    assertEquals(r.blocked.count(), 0L)
    assertEquals(clusters(r).keySet, Set(base("Ann")))
    assertEquals(clusters(r)(base("Ann")).i("memberCount"), 3)
    assertEquals(Identity.counts(r).withinBaseRejectionsIgnored, 1L)
  }

  test(
    "M2: a rejection inside the base identity does not hide a REAL cannot-link in the same run"
  ) {
    val a1 = dm("A1", "Ann"); val a2 = dm("A2", "Ann"); val b = dm("A3", "Bea");
    val c  = dm("A4", "Cat")
    val r  = run(List(a1, a2, b, c), List(acc(a1, b), acc(b, c), rej(a1, a2), rej(a2, c)))
    // rej(a2, c): a2 is Ann, c is Cat: different base identities in one accepted component
    assertEquals(blockedOf(r).map(_.s("reason")), List("rejected_or_conflict_pair"))
    assertEquals(blockedOf(r).head.l("rejectedOrConflictPairs"), 1L)
    assertEquals(clusters(r).keySet, Set(base("Ann"), base("Bea"), base("Cat")))
    assertEquals(Identity.counts(r).withinBaseRejectionsIgnored, 1L)
  }

  test("M2: an ACCEPTED plus a REJECTED decision between two different base identities blocks") {
    val a = dm("A1", "Ann"); val b = dm("A2", "Bea")
    val r = run(List(a, b), List(acc(a, b), rej(a, b)))
    assertEquals(blockedOf(r).map(_.s("reason")), List("rejected_or_conflict_pair"))
    assertEquals(clusters(r).keySet, Set(base("Ann"), base("Bea")))
    assertEquals(Identity.counts(r).withinBaseRejectionsIgnored, 0L)
  }

  test("M2: a within-base rejection through an HJ1 alias group (two strings, one base identity)") {
    val a     = dm("A1", "Z. Wang"); val b = dm("A2", "Zhen Wang")
    val alias = Row("key", Seq("Zhen Wang", "Z. Wang"), Seq.empty[String])
    val r     = run(List(a, b), List(rej(a, b)), List(alias))
    assertEquals(clusters(r).keySet, Set(base("Z. Wang")))
    assertEquals(r.withinBaseRejections.count(), 1L)
    assertEquals(r.withinBaseRejections.collect().head.s("baseName"), "Z. Wang")
  }

  // ---- canonical ID rule -------------------------------------------------------------------------

  test(
    "ID rule: unmerged DBLP person -> researcherIdDblpPerson; merged -> smallest key; none is an ORCID ID"
  ) {
    val a  = dm("A1", "Ann Lee"); val b = dm("A2", "A. Lee"); val c = dm("A3", "Cat")
    val e  = ext("E1", "Cat C.", Some(OrcA))
    val ex = ext("E2", "Loner", Some(OrcB), "openalex")
    val r  = run(List(a, b, c, e, ex), List(acc(a, b), acc(c, e)))
    val cs = clusters(r)
    assertEquals(cs.keySet, Set(base("A. Lee"), base("Cat"), unresolved(ex)))
    assert(!cs.keySet.exists(_.startsWith("researcher:orcid:")), cs.keySet.toString)
    // unmerged person with an absorbed ORCID external keeps its own base ID
    assertEquals(ofMention(r, e), base("Cat"))
    // external singleton keeps researcherIdUnresolved, even though it carries a validated ORCID
    assertEquals(cs(unresolved(ex)).s("ruleFired"), "unresolved_singleton")
    assertEquals(cs(unresolved(ex)).s("orcid"), OrcB)
    assertEquals(cs(unresolved(ex)).strs("dblpPersonKeys"), Nil)
    // merged: smallest key
    assertEquals(cs(base("A. Lee")).strs("dblpPersonKeys"), List("A. Lee", "Ann Lee"))
  }

  test("ID rule: cluster IDs do not depend on the order of mentions or of decisions") {
    val a  = dm("A1", "Ann"); val b = dm("A2", "Bea"); val c = dm("A3", "Cat");
    val d  = dm("A4", "Dee")
    val e  = ext("E1", "A. N.", Some(OrcA))
    val ms = List(a, b, c, d, e)
    val ds = List(acc(a, b), acc(c, b), acc(d, e))
    def ids(m: List[M], dec: List[(String, String, String, String)]) =
      val r = run(m, dec)
      (clusters(r).keySet, ms.map(x => x.id -> ofMention(r, x)).toMap)
    val base0 = ids(ms, ds)
    assertEquals(ids(ms.reverse, ds.reverse), base0)
    assertEquals(ids(ms, ds.map((l, r, k, o) => (r, l, k, o))), base0) // left and right swapped
    assertEquals(ids(scala.util.Random(7).shuffle(ms), scala.util.Random(11).shuffle(ds)), base0)
    assertEquals(base0._1, Set(base("Ann"), base("Dee"))) // {Ann, Bea, Cat} and {Dee, e}
    assertEquals(base0._2(b.id), base("Ann"))
    assertEquals(base0._2(c.id), base("Ann"))
  }

  test(
    "ID rule: several external mentions joined by accepted decisions get the merged-cluster ID"
  ) {
    val e1       = ext("E1", "Anon One", None); val e2 = ext("E2", "Anon Two", None, "openalex")
    val r        = run(List(e1, e2), List(acc(e1, e2)))
    val expected = Ok.get(Ids.researcherIdMergedCluster(List(unresolved(e1), unresolved(e2))))
    assertEquals(clusters(r).keySet, Set(expected))
    assertEquals(clusters(r)(expected).strs("dblpPersonKeys"), Nil)
    assertEquals(clusters(r)(expected).s("ruleFired"), "accepted_link_component")
  }

  test(
    "ID rule: the canonical ID of every cluster with a DBLP person is recomputable from dblpPersonKeys"
  ) {
    val a = dm("A1", "Ann Lee"); val b = dm("A2", "A. Lee"); val c = dm("A3", "Cat")
    val r = run(List(a, b, c), List(acc(a, b)))
    clusters(r).foreach { (id, row) =>
      assertEquals(Ids.researcherIdForClusterV2(row.strs("dblpPersonKeys")), Right(id))
    }
  }
