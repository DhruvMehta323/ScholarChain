package scholarchain.spark

import org.apache.spark.sql.Row

import scholarchain.core.*

/** Paths, path estimates, communities, bridges and temporal changes of the fixture, through
  * `Pipeline.compute`. Exact values are hand-written; where the community heuristic makes labels
  * arbitrary, invariants and independent oracles (written in the test) are used instead.
  */
class FixtureGraphSuite extends munit.FunSuite:

  import Fixture.{rid, nameOf, workId, W1, W2, All}
  import FixtureRun.{byName, rows}

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val prov = Provenance(
    1,
    SourceType.Dblp,
    List(InputRef("dblp.xml", "2026-11-01", Some("a" * 64))),
    "0.1.0",
    Map.empty,
    None,
    None,
  )

  private def window(label: String): GraphWindow =
    val Array(a, b) = label.split("-"); GraphWindow(a.toInt, b.toInt)

  /** Undirected adjacency of a window, from the coauthor-edges table. */
  private def adjacency(w: String): Map[String, Set[String]] =
    val es = rows("coauthor-edges", w).map(r => r.s("researcherA") -> r.s("researcherB"))
    (es ++ es.map(_.swap)).groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap

  /** Oracle BFS distance written in the test, not taken from the code under test. */
  private def distance(adj: Map[String, Set[String]], from: String, to: String): Option[Int] =
    var seen     = Set(from)
    var frontier = Set(from)
    var d        = 0
    while frontier.nonEmpty && !frontier.contains(to) do
      frontier = frontier.flatMap(adj.getOrElse(_, Set.empty)) -- seen
      seen ++= frontier
      d += 1
    if frontier.contains(to) then Some(d) else None

  // ---- paths ----------------------------------------------------------------------------------

  test("path evidence: 3, 2 and 5 sampled pairs inside the largest component, all with a path") {
    // the largest components have 6, 5 and 10 members: pairs = members / 2 (members <= 2 x 20)
    def count(w: String) = rows("paths", w).size
    assertEquals((count(W1), count(W2), count(All)), (3, 2, 5))
    val comp =
      rows("component-membership", All).map(r => r.s("researcherId") -> r.s("componentId")).toMap
    val big = comp(rid("Ada"))
    rows("paths", All).foreach { p =>
      assertEquals(comp(p.s("startId")), big)
      assertEquals(comp(p.s("endId")), big)
      assertNotEquals(p.s("startId"), p.s("endId"))
    }
  }

  test("path evidence matches an independent BFS, the edge table and the PathEvidence contract") {
    List(W1, W2, All).foreach { w =>
      val adj   = adjacency(w)
      val edges =
        rows("coauthor-edges", w).map(r => Set(r.s("researcherA"), r.s("researcherB")) -> r).toMap
      rows("paths", w).foreach { p =>
        val hops = p.strs("hops")
        assertEquals(hops.head, p.s("startId"))
        assertEquals(hops.last, p.s("endId"))
        assertEquals(p.i("length"), hops.size - 1)
        assertEquals(
          Some(p.i("length")),
          distance(adj, p.s("startId"), p.s("endId")),
          s"$w ${hops.map(nameOf)}",
        )
        assertEquals(hops.distinct.size, hops.size)
        val works = p.strss("supportingWorkIds")
        hops.zip(hops.tail).zipWithIndex.foreach { case ((a, b), i) =>
          val e = edges(Set(a, b))
          assertEquals(p.strs("edgeIds")(i), e.s("edgeId"))
          assertEquals(works(i), e.strs("representativeWorkIds"))
        }
        val rec = PathEvidence(
          1,
          p.s("startId"),
          p.s("endId"),
          hops,
          p.strs("edgeIds"),
          works,
          p.i("length"),
          window(w),
          prov,
        )
        assertEquals(summon[Contract[PathEvidence]].validate(rec), Right(rec))
      }
    }
  }

  test("path estimates: sources, reached pairs, average length and effective diameter, by hand") {
    // 2000-2004: largest component {Ada Bob Cy Di Ed Mo}: 15 pairs, 11 at distance 1 and 4 (Mo-ABCD) at 2:
    //   sum = 11 + 8 = 19 -> ordered 38 over 30 reached pairs; 11/15 < 90% so d90 = 2
    // 2005-2009: {Fay Gus Hal Ivy Mo}: 10 pairs, 7 at 1 and 3 at 2 -> ordered 26 over 20; d90 = 2
    // all-time: 10 members, unordered distances d1 18, d2 8, d3 7, d4 12 -> sum 103, ordered 206 over 90;
    //   33/45 < 90% at d3, 45/45 at d4 -> d90 = 4
    val est = rows("path-estimates").map(r => r.s("window") -> r).toMap
    def chk(w: String, used: Long, reached: Long, avg: Double, d90: Int, maxD: Int) =
      val e = est(w)
      assertEquals(
        (e.i("sourcesRequested"), e.l("sourcesUsed"), e.l("seed"), e.i("maxDepth")),
        (50, used, 441L, 6),
        w,
      )
      assertEquals(e.l("reachedPairs"), reached, w)
      assertEquals(e.l("unreachedWithinDepthPairs"), 0L, w)
      assertEqualsDouble(e.d("avgPathLength"), avg, 1e-12, w)
      assertEquals((e.i("effectiveDiameter90"), e.i("maxObservedDistance")), (d90, maxD), w)
    chk(W1, 6, 30, 38.0 / 30.0, 2, 2)
    chk(W2, 5, 20, 26.0 / 20.0, 2, 2)
    chk(All, 10, 90, 206.0 / 90.0, 4, 4)
    assertEquals(est.keySet, Set(W1, W2, All))
  }

  // ---- communities ----------------------------------------------------------------------------

  private def communityOf(w: String): Map[String, String] =
    rows("community-membership", w)
      .map(r => nameOf(r.s("researcherId")) -> r.s("communityId"))
      .toMap

  test("communities: one per node, inside one component, named window + smallest member id") {
    List(W1, W2, All).foreach { w =>
      val mem   = rows("community-membership", w)
      val nodes = rows("component-membership", w)
      assertEquals(mem.size, nodes.size, w)
      assertEquals(mem.map(_.s("researcherId")).toSet, nodes.map(_.s("researcherId")).toSet, w)
      val comp = nodes.map(r => r.s("researcherId") -> r.s("componentId")).toMap
      mem.groupBy(_.s("communityId")).foreach { (cid, ms) =>
        assertEquals(
          ms.map(r => comp(r.s("researcherId"))).toSet.size,
          1,
          s"$w $cid spans components",
        )
        assertEquals(cid, s"community:$w:${ms.map(_.s("researcherId")).min}")
      }
      val sizes = rows("communities", w).map(r => r.s("communityId") -> r.l("size")).toMap
      assertEquals(sizes, mem.groupBy(_.s("communityId")).view.mapValues(_.size.toLong).toMap, w)
      assert(rows("communities", w).forall(r => r.s("method") == "label_propagation.v1"), w)
    }
  }

  test("communities: the isolated researcher and the Jo-Kit pair, and cohesive dense groups") {
    val a = communityOf(All)
    assertEquals(rows("communities", All).find(_.s("communityId") == a("Lu")).get.l("size"), 1L)
    assertEquals(a("Jo"), a("Kit"))
    assertNotEquals(a("Jo"), a("Ada"))
    // observed behaviour of the deterministic heuristic on this fixture: each dense group stays whole
    assertEquals(List("Ada", "Bob", "Cy", "Di").map(a).toSet.size, 1)
    assertEquals(List("Fay", "Gus", "Hal", "Ivy").map(a).toSet.size, 1)
    assertNotEquals(a("Ada"), a("Fay"))
    assert(Set(a("Ada"), a("Fay")).contains(a("Mo")))
    assert(Set(a("Ada"), a("Fay")).contains(a("Ed")))
  }

  test("communities: edge accounting - internal edges + half the cross-edge ends = all edges") {
    List(W1, W2, All).foreach { w =>
      val cs    = rows("communities", w)
      val edges = rows("coauthor-edges", w).size.toLong
      assertEquals(
        cs.map(_.l("internalEdgeCount")).sum + cs.map(_.l("crossEdgeCount")).sum / 2,
        edges,
        w,
      )
      assertEquals(cs.map(_.l("crossEdgeCount")).sum % 2, 0L, w)
      val cross = rows("community-cross-edges", w)
      assertEquals(cross.map(_.l("edgeCount")).sum, cs.map(_.l("crossEdgeCount")).sum / 2, w)
      assert(cross.forall(r => r.s("communityA") < r.s("communityB")), w)
    }
  }

  // ---- bridges --------------------------------------------------------------------------------

  test(
    "bridges: three measures per node, recomputed from edges and communities by the documented formulas"
  ) {
    List(W1, W2, All).foreach { w =>
      val adj  = adjacency(w)
      val comm =
        rows("community-membership", w).map(r => r.s("researcherId") -> r.s("communityId")).toMap
      val got = rows("bridges", w)
        .groupBy(_.s("researcherId"))
        .view
        .mapValues(_.map(r => r.s("measure") -> r.d("value")).toMap)
        .toMap
      assertEquals(got.keySet, comm.keySet, w)
      comm.foreach { (id, own) =>
        val nb    = adj.getOrElse(id, Set.empty)
        val k     = nb.size.toDouble
        val perC  = nb.toList.groupBy(comm).view.mapValues(_.size).toMap
        val cross = nb.count(comm(_) != own).toDouble
        val pc    = if k == 0 then 0.0 else 1.0 - perC.values.map(x => x.toDouble * x).sum / (k * k)
        val touched = (perC.keySet + own).size.toDouble
        val g       = got(id)
        assertEquals(
          g.keySet,
          Set("cross_community_degree", "participation_coefficient", "distinct_communities"),
        )
        assertEqualsDouble(g("cross_community_degree"), cross, 1e-12, s"$w ${nameOf(id)}")
        assertEqualsDouble(g("participation_coefficient"), pc, 1e-12, s"$w ${nameOf(id)}")
        assertEqualsDouble(g("distinct_communities"), touched, 1e-12, s"$w ${nameOf(id)}")
        assert(pc >= 0.0 && pc < 1.0)
      }
    }
  }

  test("bridges: the bridge person Mo links the two dense groups in the all-time graph") {
    // Mo has two coauthors, Ed (K5 group) and Fay (K4 group), which sit in different communities:
    // whichever community Mo joins, cross = 1, P = 1 - (1 + 1) / 4 = 0.5 and Mo touches 2 communities.
    val b = rows("bridges", All)
      .groupBy(r => nameOf(r.s("researcherId")))
      .view
      .mapValues(_.map(r => r.s("measure") -> r.d("value")).toMap)
      .toMap
    assertEquals(b("Mo")("cross_community_degree"), 1.0)
    assertEqualsDouble(b("Mo")("participation_coefficient"), 0.5, 1e-12)
    assertEquals(b("Mo")("distinct_communities"), 2.0)
    // the isolated researcher and the Jo-Kit pair are not bridges
    List("Lu", "Jo", "Kit").foreach { n =>
      assertEquals(b(n)("cross_community_degree"), 0.0, n)
      assertEquals(b(n)("participation_coefficient"), 0.0, n)
      assertEquals(b(n)("distinct_communities"), 1.0, n)
    }
    // exactly one of Ed and Fay is on the far side of Mo's two links
    assertEquals(b("Ed")("cross_community_degree") + b("Fay")("cross_community_degree"), 1.0)
  }

  // ---- temporal -------------------------------------------------------------------------------

  test("temporal: only consecutive explicit windows are compared; the all-time window never is") {
    val t = rows("temporal-changes")
    assertEquals(t.map(_.s("window")).toSet, Set(W2))
    assertEquals(t.map(_.s("prevWindow")).toSet, Set(W1))
    assertEquals(t.size, 13)
    // by hand: both {Mo Jo Kit}, dropped {Ada Bob Cy Di Ed Lu}, new {Fay Gus Hal Ivy}
    val st =
      t.groupBy(_.s("status")).view.mapValues(_.map(r => nameOf(r.s("researcherId"))).toSet).toMap
    assertEquals(st("both"), Set("Mo", "Jo", "Kit"))
    assertEquals(st("dropped"), Set("Ada", "Bob", "Cy", "Di", "Ed", "Lu"))
    assertEquals(st("new"), Set("Fay", "Gus", "Hal", "Ivy"))
  }

  test("temporal: coauthor continuity (retained, new, lost) and per-window sizes, by hand") {
    val t             = byName("temporal-changes", W2)
    def cc(n: String) = (
      t(n).l("coauthorCount"),
      t(n).l("prevCoauthorCount"),
      t(n).l("retainedCoauthors"),
      t(n).l("newCoauthors"),
      t(n).l("lostCoauthors"),
    )
    // Mo: coauthor Fay now, Ed before -> nothing retained, 1 new, 1 lost
    assertEquals(cc("Mo"), (1L, 1L, 0L, 1L, 1L))
    // Jo and Kit keep each other
    assertEquals(cc("Jo"), (1L, 1L, 1L, 0L, 0L))
    assertEquals(cc("Kit"), (1L, 1L, 1L, 0L, 0L))
    // Ed was in the K5 plus Mo (5 coauthors) and is absent now: all 5 lost
    assertEquals(cc("Ed"), (0L, 5L, 0L, 0L, 5L))
    assertEquals(cc("Ada"), (0L, 4L, 0L, 0L, 4L))
    assertEquals(cc("Lu"), (0L, 0L, 0L, 0L, 0L))
    // Fay is new with 4 coauthors (Gus Hal Ivy Mo); Gus 3
    assertEquals(cc("Fay"), (4L, 0L, 0L, 4L, 0L))
    assertEquals(cc("Gus"), (3L, 0L, 0L, 3L, 0L))
    // team-size and component-size context, null on the absent side
    assertEqualsDouble(t("Mo").d("avgTeamSize"), 2.0, 1e-12)
    assertEqualsDouble(t("Mo").d("prevAvgTeamSize"), 2.0, 1e-12)
    assertEquals((t("Mo").l("componentSize"), t("Mo").l("prevComponentSize")), (5L, 6L))
    assertEquals((t("Jo").l("componentSize"), t("Jo").l("prevComponentSize")), (2L, 2L))
    assert(
      t("Ed").isNull("avgTeamSize") && t("Ed").isNull("componentSize") && t("Ed").isNull(
        "maxTeamSize"
      )
    )
    assertEqualsDouble(t("Ed").d("prevAvgTeamSize"), 3.5, 1e-12)
    assertEquals((t("Ed").i("prevMaxTeamSize"), t("Ed").l("prevComponentSize")), (5, 6L))
    assert(t("Fay").isNull("prevAvgTeamSize") && t("Fay").isNull("prevComponentSize"))
    assertEqualsDouble(t("Fay").d("avgTeamSize"), 7.0 / 3.0, 1e-12)
    assertEquals((t("Fay").i("maxTeamSize"), t("Fay").l("componentSize")), (3, 5L))
  }

  // ---- contracts ------------------------------------------------------------------------------

  private def ok[A](a: A)(using c: Contract[A])(using munit.Location): Unit =
    assertEquals(c.validate(a), Right(a))

  test("table rows convert to valid core records: components, communities, bridges, clusters") {
    rows("components").foreach(r =>
      ok(ComponentRecord(1, r.s("componentId"), r.l("size"), window(r.s("window")), prov))
    )
    rows("communities").foreach { r =>
      val params = r.getMap[String, String](r.fieldIndex("parameters")).toMap
      ok(
        CommunityRecord(
          1,
          r.s("communityId"),
          r.l("size"),
          r.s("method"),
          params,
          window(r.s("window")),
          prov,
        )
      )
    }
    val measures = BridgeMeasure.values.map(m => m.wire -> m).toMap
    rows("bridges").foreach { r =>
      val params = r.getMap[String, String](r.fieldIndex("parameters")).toMap
      ok(
        BridgeRecord(
          1,
          r.s("researcherId"),
          measures(r.s("measure")),
          r.d("value"),
          params,
          window(r.s("window")),
          prov,
        )
      )
    }
    rows("identity-clusters").foreach { r =>
      val status = if r.s("cannotLinkStatus") == "blocked" then CannotLinkStatus.Blocked
      else CannotLinkStatus.Clear
      ok(
        IdentityCluster(
          1,
          r.s("researcherId"),
          r.strs("memberMentionIds"),
          r.s("ruleFired"),
          status,
          r.s("policyVersion"),
          prov,
        )
      )
    }
  }

  test("coauthor edges convert to valid core CoauthorEdge records") {
    rows("coauthor-edges").foreach { r =>
      val pair = OrderedPair(r.s("researcherA"), r.s("researcherB"))
      val rec  = CoauthorEdge(
        1,
        r.s("edgeId"),
        pair,
        window(r.s("window")),
        r.s("policyVersion"),
        r.i("jointPublicationCount"),
        r.strs("representativeWorkIds"),
        prov,
      )
      ok(rec)
    }
  }

  // ---- shortest paths on the fixture, by hand -------------------------------------------------

  private lazy val allArcs = Graph.arcs(FixtureRun.computed.tables("coauthor-edges"))
  private lazy val ckBfs   = Checkpointer(TestSpark.useCheckpointFor("fixture-bfs"))

  private def fromAda(depth: Int) =
    val src = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, source string"),
      List(Row(All, rid("Ada"))),
    )
    ShortestPaths.bfs(allArcs, src, depth, ckBfs)

  test("shortest-path lengths from Ada in the all-time graph, by hand") {
    // Ada -1- Bob Cy Di Ed; Ed -1- Mo (so Mo is 2); Mo -1- Fay (3); Fay -1- Gus Hal Ivy (4).
    // Jo, Kit and Lu are in other components and are never reached.
    val (visited, conv) = fromAda(6)
    val d = visited.collect().toList.map(r => nameOf(r.s("node")) -> r.i("dist")).toMap
    assertEquals(
      d,
      Map(
        "Ada" -> 0,
        "Bob" -> 1,
        "Cy"  -> 1,
        "Di"  -> 1,
        "Ed"  -> 1,
        "Mo"  -> 2,
        "Fay" -> 3,
        "Gus" -> 4,
        "Hal" -> 4,
        "Ivy" -> 4,
      ),
    )
    assert(conv.converged)
  }

  test("the depth cap stops the search: depth 2 reaches Mo but not Fay") {
    val (visited, conv) = fromAda(2)
    val names           = visited.collect().toList.map(r => nameOf(r.s("node"))).toSet
    assertEquals(names, Set("Ada", "Bob", "Cy", "Di", "Ed", "Mo"))
    assert(!conv.converged)
  }

  test(
    "path evidence Ada to Ivy: five hops through Ed, Mo and Fay with each hop's edge and works"
  ) {
    val (visited, _) = fromAda(6)
    val pairs        = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, startId string, endId string"),
      List(Row(All, rid("Ada"), rid("Ivy")), Row(All, rid("Ada"), rid("Lu"))),
    )
    val ev = ShortestPaths
      .evidence(visited, pairs, FixtureRun.computed.tables("coauthor-edges"), 6)
      .collect()
      .toList
    assertEquals(ev.size, 1) // Lu is unreachable: no row
    val p = ev.head
    assertEquals(p.strs("hops").map(nameOf), List("Ada", "Ed", "Mo", "Fay", "Ivy"))
    assertEquals(p.i("length"), 4)
    val all = GraphWindows.AllTime
    assertEquals(
      p.strs("edgeIds"),
      List(("Ada", "Ed"), ("Ed", "Mo"), ("Mo", "Fay"), ("Fay", "Ivy")).map((a, b) =>
        Fixture.edgeId(a, b, all)
      ),
    )
    assertEquals(
      p.strss("supportingWorkIds"),
      List(List("W01"), List("W05"), List("W06"), List("W04")).map(_.map(workId)),
    )
  }
