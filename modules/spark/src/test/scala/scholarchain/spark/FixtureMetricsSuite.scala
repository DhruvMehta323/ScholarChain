package scholarchain.spark

import org.apache.spark.sql.Row

import scholarchain.core.*

/** Breadth, team-size and weighted-degree metrics, window summaries, components, k-core, identity
  * and enrichment tables of the hand-checkable fixture, through `Pipeline.compute`.
  */
class FixtureMetricsSuite extends munit.FunSuite:

  import Fixture.{rid, workId, W1, W2, All}
  import FixtureRun.{byName, rows}

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  /** Expected metrics by hand: (P_r, C_r, T_r, A_r, M_r, W_r, work keys of maxTeamWorkIds). */
  private type Exp = (Long, Long, Long, Double, Int, Long, List[String])

  private def check(window: String, expected: Map[String, Exp])(using munit.Location): Unit =
    val got = byName("researcher-metrics", window)
    assertEquals(got.keySet, expected.keySet)
    expected.foreach { (name, e) =>
      val r                      = got(name)
      val (p, c, t, a, m, w, mw) = e
      assertEquals(r.l("publicationCount"), p, s"$window $name P_r")
      assertEquals(r.l("coauthorCount"), c, s"$window $name C_r")
      assertEquals(r.l("teamSizeTotal"), t, s"$window $name T_r")
      assertEqualsDouble(r.d("avgTeamSize"), a, 1e-9, s"$window $name A_r")
      assertEquals(r.i("maxTeamSize"), m, s"$window $name M_r")
      assertEquals(r.l("weightedDegree"), w, s"$window $name W_r")
      assertEquals(r.strs("maxTeamWorkIds"), mw.map(workId), s"$window $name maxTeamWorkIds")
      assertEquals(r.i("maxTeamWorkCount"), mw.size, s"$window $name maxTeamWorkCount")
    }

  // ---- researcher metrics ---------------------------------------------------------------------

  test("metrics 2000-2004: P, C, T, A, M, W and the max-team works of every researcher") {
    // K5 (W01, team 5): Ada Bob Cy Di have 4 coauthors, T = 5, A = 5/1.
    // Ed: W01 (5) + W05 (2): P 2, C = 4 + Mo = 5, T 7, A 3.5, W 5. Mo: W05 only. Jo/Kit: W07+W08.
    check(
      W1,
      Map(
        "Ada" -> (1L, 4L, 5L, 5.0, 5, 4L, List("W01")),
        "Bob" -> (1L, 4L, 5L, 5.0, 5, 4L, List("W01")),
        "Cy"  -> (1L, 4L, 5L, 5.0, 5, 4L, List("W01")),
        "Di"  -> (1L, 4L, 5L, 5.0, 5, 4L, List("W01")),
        "Ed"  -> (2L, 5L, 7L, 3.5, 5, 5L, List("W01")),
        "Mo"  -> (1L, 1L, 2L, 2.0, 2, 1L, List("W05")),
        "Jo"  -> (2L, 1L, 4L, 2.0, 2, 2L, List("W07", "W08")),
        "Kit" -> (2L, 1L, 4L, 2.0, 2, 2L, List("W07", "W08")),
        "Lu"  -> (1L, 0L, 1L, 1.0, 1, 0L, List("W09")),
      ),
    )
  }

  test("metrics 2005-2009: Fay 7/3, Gus and Hal weight-2 edge, Mo and the Jo-Kit repeat") {
    // Fay: W02 (3) W04 (2) W06 (2) -> P 3, T 7, A 7/3; coauthors Gus Hal Ivy Mo -> C 4, W 4.
    // Gus: W02 W03 (3, 3) -> T 6, A 3.0; coauthors Fay, Hal (2 works), Ivy -> C 3, W 1 + 2 + 1 = 4.
    // Ivy: W03 (3) W04 (2) -> T 5, A 2.5; coauthors Gus, Hal, Fay -> C 3, W 3.
    check(
      W2,
      Map(
        "Fay" -> (3L, 4L, 7L, 7.0 / 3.0, 3, 4L, List("W02")),
        "Gus" -> (2L, 3L, 6L, 3.0, 3, 4L, List("W02", "W03")),
        "Hal" -> (2L, 3L, 6L, 3.0, 3, 4L, List("W02", "W03")),
        "Ivy" -> (2L, 3L, 5L, 2.5, 3, 3L, List("W03")),
        "Mo"  -> (1L, 1L, 2L, 2.0, 2, 1L, List("W06")),
        "Jo"  -> (1L, 1L, 2L, 2.0, 2, 1L, List("W11")),
        "Kit" -> (1L, 1L, 2L, 2.0, 2, 1L, List("W11")),
      ),
    )
  }

  test("metrics all-time: W10 (2012) counts only here; Ada-Bob weight 2 gives W_r 5") {
    // Ada: W01 (5) + W10 (2): P 2, T 7, A 3.5; coauthors Bob Cy Di Ed -> C 4; W = 2 + 1 + 1 + 1 = 5.
    // Mo: W05 + W06 -> P 2, C 2 (Ed, Fay), T 4. Jo/Kit: W07 W08 W11 -> P 3, C 1, T 6, W 3.
    check(
      All,
      Map(
        "Ada" -> (2L, 4L, 7L, 3.5, 5, 5L, List("W01")),
        "Bob" -> (2L, 4L, 7L, 3.5, 5, 5L, List("W01")),
        "Cy"  -> (1L, 4L, 5L, 5.0, 5, 4L, List("W01")),
        "Di"  -> (1L, 4L, 5L, 5.0, 5, 4L, List("W01")),
        "Ed"  -> (2L, 5L, 7L, 3.5, 5, 5L, List("W01")),
        "Fay" -> (3L, 4L, 7L, 7.0 / 3.0, 3, 4L, List("W02")),
        "Gus" -> (2L, 3L, 6L, 3.0, 3, 4L, List("W02", "W03")),
        "Hal" -> (2L, 3L, 6L, 3.0, 3, 4L, List("W02", "W03")),
        "Ivy" -> (2L, 3L, 5L, 2.5, 3, 3L, List("W03")),
        "Mo"  -> (2L, 2L, 4L, 2.0, 2, 2L, List("W05", "W06")),
        "Jo"  -> (3L, 1L, 6L, 2.0, 2, 3L, List("W07", "W08", "W11")),
        "Kit" -> (3L, 1L, 6L, 2.0, 2, 3L, List("W07", "W08", "W11")),
        "Lu"  -> (1L, 0L, 1L, 1.0, 1, 0L, List("W09")),
      ),
    )
  }

  test("metrics keep every measure separate: named bridge measures, no single bridgeScore") {
    val cols = FixtureRun.computed.tables("researcher-metrics").columns.toList
    // v2: exactly the fields of core ResearcherMetricsV2 after the three window columns
    assertEquals(
      cols,
      List(
        "window",
        "windowStart",
        "windowEnd",
        "schemaVersion",
        "researcherId",
        "policyVersion",
        "publicationCount",
        "coauthorCount",
        "teamSizeTotal",
        "avgTeamSize",
        "maxTeamSize",
        "weightedDegree",
        "maxTeamWorkCount",
        "maxTeamWorkIds",
        "communityId",
        "componentId",
        "coreness",
        "crossCommunityDegree",
        "participationCoefficient",
        "communitiesTouched",
        "tieStrengthWeightedDegree",
        "provenance",
      ),
    )
    assert(!cols.contains("bridgeScore"))
    assert(!cols.exists(c => c.toLowerCase.contains("score") || c.toLowerCase.contains("rank")))
    // the three named bridge measures take the place of one composite
    assert(
      Set("crossCommunityDegree", "participationCoefficient", "communitiesTouched").subsetOf(
        cols.toSet
      )
    )
  }

  test("metric sums: sum of C_r = 2 x edges and sum of W_r = 2 x joint publications, per window") {
    def sum(w: String, c: String) = rows("researcher-metrics", w).map(_.l(c)).sum
    assertEquals(
      (sum(W1, "coauthorCount"), sum(W2, "coauthorCount"), sum(All, "coauthorCount")),
      (24L, 16L, 38L),
    )
    // joint publications by hand: 2000-2004 = K5 10 + Mo-Ed 1 + Jo-Kit 2 = 13;
    // 2005-2009 = K4 (5 edges of weight 1 + Gus-Hal 2) 7 + Mo-Fay 1 + Jo-Kit 1 = 9; all-time = 23
    assertEquals(sum(W1, "weightedDegree"), 2L * 13L)
    assertEquals(sum(W2, "weightedDegree"), 2L * 9L)
    assertEquals(sum(All, "weightedDegree"), 2L * 23L)
  }

  // ---- window summary -------------------------------------------------------------------------

  test("window summary rows, by hand") {
    val s = rows("window-summary").map(r => r.s("window") -> r).toMap
    assertEquals(s.keySet, Set(W1, W2, All))
    def chk(
        w: String,
        n: Long,
        e: Long,
        works: Long,
        comps: Long,
        singles: Long,
        largest: Long,
        maxCore: Int,
        maxTeam: Int,
        maxWork: String,
    ) =
      val r = s(w)
      assertEquals(
        (
          r.l("nodeCount"),
          r.l("edgeCount"),
          r.l("workCount"),
          r.l("componentCount"),
          r.l("singletonCount"),
          r.l("largestComponentSize"),
          r.i("maxCoreness"),
          r.i("maxTeamSize"),
          r.s("maxTeamWorkId"),
        ),
        (n, e, works, comps, singles, largest, maxCore, maxTeam, workId(maxWork)),
        w,
      )
      assertEqualsDouble(r.d("largestComponentFraction"), largest.toDouble / n.toDouble, 1e-12, w)
    // 2000-2004: 9 nodes, 12 edges, works W01 W05 W07 W08 W09, comps {6},{2},{1}
    chk(W1, 9, 12, 5, 3, 1, 6, 4, 5, "W01")
    // 2005-2009: 7 nodes, 8 edges, works W02 W03 W04 W06 W11, comps {5},{2}; max team 3 on W02 and W03: smallest id
    chk(W2, 7, 8, 5, 2, 0, 5, 3, 3, "W02")
    // all-time: 13 nodes, 19 edges, 11 works, comps {10},{2},{1}
    chk(All, 13, 19, 11, 3, 1, 10, 4, 5, "W01")
    assert(s.values.forall(r => r.i("windowStart") > 0 && r.i("windowEnd") >= r.i("windowStart")))
    assertEquals(
      (
        s(W1).i("windowStart"),
        s(W1).i("windowEnd"),
        s(All).i("windowStart"),
        s(All).i("windowEnd"),
      ),
      (2000, 2004, 1000, 9999),
    )
  }

  test("every output row of every windowed table carries an explicit window") {
    Tables.all.filter(_.windowed).foreach { t =>
      val rs = rows(t.name)
      assert(
        rs.forall(r => r.s("window") != null && r.i("windowStart") <= r.i("windowEnd")),
        t.name,
      )
      assert(rs.map(_.s("window")).toSet.subsetOf(Set(W1, W2, All)), t.name)
    }
  }

  // ---- components -----------------------------------------------------------------------------

  private def cid(window: String, members: List[String]): String =
    s"component:$window:${members.map(rid).min}"

  test("components: sizes and ids per window (id = window + smallest member researcher id)") {
    val g1               = List("Ada", "Bob", "Cy", "Di", "Ed", "Mo")
    val g2               = List("Fay", "Gus", "Hal", "Ivy", "Mo")
    val all              = g1 ++ List("Fay", "Gus", "Hal", "Ivy")
    def sizes(w: String) = rows("components", w).map(r => r.s("componentId") -> r.l("size")).toMap
    assertEquals(
      sizes(W1),
      Map(cid(W1, g1) -> 6L, cid(W1, List("Jo", "Kit")) -> 2L, cid(W1, List("Lu")) -> 1L),
    )
    assertEquals(sizes(W2), Map(cid(W2, g2) -> 5L, cid(W2, List("Jo", "Kit")) -> 2L))
    assertEquals(
      sizes(All),
      Map(cid(All, all) -> 10L, cid(All, List("Jo", "Kit")) -> 2L, cid(All, List("Lu")) -> 1L),
    )
  }

  test("component membership: every node once, in the component of its hand-found group") {
    def mem(w: String) = rows("component-membership", w)
      .map(r => Fixture.nameOf(r.s("researcherId")) -> r.s("componentId"))
      .toMap
    val m = mem(All)
    assertEquals(m.size, 13)
    val big = cid(All, List("Ada", "Bob", "Cy", "Di", "Ed", "Mo", "Fay", "Gus", "Hal", "Ivy"))
    List("Ada", "Bob", "Cy", "Di", "Ed", "Mo", "Fay", "Gus", "Hal", "Ivy").foreach(n =>
      assertEquals(m(n), big, n)
    )
    assertEquals(m("Jo"), m("Kit"))
    assertNotEquals(m("Jo"), big)
    assertNotEquals(m("Lu"), m("Jo"))
    // Mo bridges the two dense groups in the all-time graph only
    val m1 = mem(W1); val m2 = mem(W2)
    assertEquals(m1("Mo"), m1("Ed"))
    assertEquals(m2("Mo"), m2("Fay"))
    assertNotEquals(m1("Mo"), m1("Jo"))
    assertEquals(m1.size, 9)
    assertEquals(m2.size, 7)
  }

  // ---- k-core ---------------------------------------------------------------------------------

  test("coreness per window, by hand peeling") {
    // all-time: Lu 0; Jo Kit 1; Mo 2; K4 {Fay Gus Hal Ivy} 3; K5 {Ada Bob Cy Di Ed} 4
    val all = byName("coreness", All).view.mapValues(_.i("coreness")).toMap
    assertEquals(
      all,
      Map(
        "Lu"  -> 0,
        "Jo"  -> 1,
        "Kit" -> 1,
        "Mo"  -> 2,
        "Fay" -> 3,
        "Gus" -> 3,
        "Hal" -> 3,
        "Ivy" -> 3,
        "Ada" -> 4,
        "Bob" -> 4,
        "Cy"  -> 4,
        "Di"  -> 4,
        "Ed"  -> 4,
      ),
    )
    // 2000-2004: Mo hangs off the K5 by one edge: coreness 1
    val w1 = byName("coreness", W1).view.mapValues(_.i("coreness")).toMap
    assertEquals(
      w1,
      Map(
        "Lu"  -> 0,
        "Jo"  -> 1,
        "Kit" -> 1,
        "Mo"  -> 1,
        "Ada" -> 4,
        "Bob" -> 4,
        "Cy"  -> 4,
        "Di"  -> 4,
        "Ed"  -> 4,
      ),
    )
    // 2005-2009: K4 is a 3-core, Mo a pendant, Jo-Kit a 1-core
    val w2 = byName("coreness", W2).view.mapValues(_.i("coreness")).toMap
    assertEquals(
      w2,
      Map("Fay" -> 3, "Gus" -> 3, "Hal" -> 3, "Ivy" -> 3, "Mo" -> 1, "Jo" -> 1, "Kit" -> 1),
    )
  }

  // ---- identity and enrichment tables ---------------------------------------------------------

  test("identity tables: 13 DBLP-person clusters, 26 mentions, no merges and nothing blocked") {
    val cl = rows("identity-clusters")
    assertEquals(cl.size, 13)
    assertEquals(cl.map(_.s("researcherId")).toSet, Fixture.names.map(rid).toSet)
    assert(
      cl.forall(r => r.s("ruleFired") == "dblp_person_exact" && r.s("cannotLinkStatus") == "clear")
    )
    assertEquals(cl.map(_.i("memberCount")).sum, 26)
    assertEquals(rows("mention-researcher").size, 26)
    assertEquals(rows("cannot-link-blocked").size, 0)
    val n = FixtureRun.computed.identityCounts
    assertEquals(
      (
        n.mentions,
        n.clusters,
        n.mergedClusters,
        n.cannotLinkBlockedComponents,
        n.unresolvedSingletons,
      ),
      (26L, 13L, 0L, 0L, 0L),
    )
  }

  test("researchers table: nodes with first and last year of their works") {
    val r = rows("researchers")
      .map(x =>
        Fixture.nameOf(x.s("researcherId")) -> (
          x.i("firstYear"),
          x.i("lastYear"),
          x.i("memberMentionCount"),
        )
      )
      .toMap
    assertEquals(r.size, 13)
    assertEquals(r("Ada"), (2001, 2012, 2))
    assertEquals(r("Jo"), (2002, 2006, 3))
    assertEquals(r("Lu"), (2003, 2003, 1))
    assertEquals(r("Fay"), (2005, 2008, 3))
  }

  test("works, topics and venue tables: an accepted work decision only enriches the DBLP work") {
    val w = rows("works").map(r => r.s("workId") -> r).toMap
    assertEquals(w.size, 11) // the external openalex work is not a canonical work
    assert(!w.contains(Fixture.extWork))
    assertEquals(w(workId("W01")).i("teamSize"), 5)
    assertEquals(w(workId("W01")).strs("externalWorkIds"), List(Fixture.extWork))
    assertEquals(w(workId("W01")).strs("topicIds"), List(Fixture.topicDatabase, Fixture.topicGraph))
    assertEquals(w(workId("W02")).strs("topicIds"), List(Fixture.topicGraph))
    assertEquals(w(workId("W03")).strs("topicIds"), Nil)
    assertEquals(w(workId("W03")).strs("externalWorkIds"), Nil)
    val ht = rows("has-topic").map(r => (r.s("workId"), r.s("topicId"), r.s("viaWorkId"))).toSet
    assertEquals(
      ht,
      Set(
        (workId("W01"), Fixture.topicGraph, workId("W01")),
        (workId("W01"), Fixture.topicDatabase, Fixture.extWork),
        (workId("W02"), Fixture.topicGraph, workId("W02")),
      ),
    )
    val t = rows("topics").map(r => r.s("topicId") -> r.l("linkedWorkCount")).toMap
    assertEquals(t, Map(Fixture.topicGraph -> 2L, Fixture.topicDatabase -> 1L))
    val v = rows("venues")
    assertEquals(
      v.map(r => (r.s("venueId"), r.s("name"), r.l("workCount"))),
      List(("venue:dblp:conf/t", "conf/t", 11L)),
    )
    assertEquals(rows("published-in").size, 11)
    assertEquals(rows("institutions").size, 1)
  }

  test("authored: one row per (researcher, work) and 26 rows for the fixture") {
    val a = rows("authored")
    assertEquals(a.size, 26)
    val ada = a
      .filter(_.s("researcherId") == rid("Ada"))
      .map(r => (r.s("workId"), r.i("year"), r.i("position")))
      .toSet
    assertEquals(ada, Set((workId("W01"), 2001, 0), (workId("W10"), 2012, 0)))
  }

  test("algorithm status: every iterative algorithm converged within its cap") {
    val st = rows("algorithm-status").map(r => r.s("algorithm") -> r).toMap
    assertEquals(
      st.keySet,
      Set(
        "identity_connected_components_min_label",
        "connected_components_min_label",
        "label_propagation.v1",
        "k_core_peeling",
        "bfs_bounded",
      ),
    )
    assert(
      st.values.forall(r => r.b("converged") && r.i("iterations") <= r.i("iterationCap")),
      st.toString,
    )
    assertEquals(st("label_propagation.v1").i("iterationCap"), 20)
    assertEquals(st("bfs_bounded").i("iterationCap"), 6)
  }
