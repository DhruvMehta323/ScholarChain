package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions as F

import scholarchain.core.*

/** Components, communities, k-core, bridges, BFS, path evidence and sampling on tiny hand graphs.
  * Node ids are plain strings: these functions do not validate identifiers.
  */
class GraphAlgorithmsSuite extends munit.FunSuite:

  private val spark        = TestSpark.spark
  private lazy val ck      = Checkpointer(TestSpark.useCheckpointFor("algorithms"))
  private lazy val cfg     = TestSpark.config(TestSpark.useCheckpointFor("algorithms-cfg"))
  private lazy val windows = Graph.windowsFrame(spark, cfg)
  private val W            = "2000-2004"

  private def nodes(ids: (String, String)*): DataFrame =
    TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, id string"),
      ids.map((w, i) => Row(w, i)),
    )

  /** Both directions of every undirected edge (window, a, b). */
  private def arcs(es: (String, String, String)*): DataFrame =
    TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, src string, dst string"),
      es.flatMap((w, a, b) => List(Row(w, a, b), Row(w, b, a))),
    )

  private def labelMap(df: DataFrame): Map[(String, String), String] =
    df.collect().toList.map(r => (r.s("window"), r.s("id")) -> r.s("label")).toMap

  // ---- connected components -------------------------------------------------------------------

  test("components: every node gets the smallest id of its component; converges") {
    val ns = nodes(List("c1", "c2", "c3", "c4", "c5", "d1", "d2", "e1").map(W -> _)*)
    val as = arcs(
      (W, "c1", "c2"),
      (W, "c2", "c3"),
      (W, "c3", "c4"),
      (W, "c4", "c5"),
      (W, "d1", "d2"),
    )
    val (labels, conv) = Components.labels(ns, as, 50, ck)
    val m              = labelMap(labels)
    List("c1", "c2", "c3", "c4", "c5").foreach(n => assertEquals(m((W, n)), "c1", n))
    assertEquals((m((W, "d1")), m((W, "d2"))), ("d1", "d1"))
    assertEquals(m((W, "e1")), "e1")
    assert(conv.converged)
    // a chain of 5 needs 4 propagation rounds plus one round that finds no change
    assert(conv.iterations <= 5, conv.toString)
    assertEquals(conv.algorithm, "connected_components_min_label")
    assertEquals(conv.cap, 50)
  }

  test("components: the iteration cap stops the loop and reports converged = false") {
    val ns             = nodes(List("c1", "c2", "c3", "c4", "c5").map(W -> _)*)
    val as             = arcs((W, "c1", "c2"), (W, "c2", "c3"), (W, "c3", "c4"), (W, "c4", "c5"))
    val (labels, conv) = Components.labels(ns, as, 2, ck)
    assert(!conv.converged)
    assertEquals(conv.iterations, 2)
    // after two rounds the minimum label has travelled two hops: c5 still carries c3
    assertEquals(labelMap(labels)((W, "c5")), "c3")
  }

  test(
    "components: one loop serves several windows; a node in two windows is labelled per window"
  ) {
    val W2 = "2005-2009"
    val ns = nodes(List("a", "b", "c").map(W -> _) ++ List("a", "b", "c").map(W2 -> _)*)
    val as = arcs((W, "a", "b"), (W2, "b", "c"))
    val m  = labelMap(Components.labels(ns, as, 50, ck)._1)
    assertEquals(m((W, "a")), "a"); assertEquals(m((W, "b")), "a"); assertEquals(m((W, "c")), "c")
    assertEquals(m((W2, "a")), "a"); assertEquals(m((W2, "b")), "b");
    assertEquals(m((W2, "c")), "b")
  }

  test("components: membership ids embed window and smallest member; sizes count members") {
    val ns          = nodes(List("p", "q", "r", "s").map(W -> _)*)
    val as          = arcs((W, "p", "q"), (W, "q", "r"))
    val (labels, _) = Components.labels(ns, as, 50, ck)
    val mem         = Components.membership(labels, windows)
    val ids         = mem.collect().toList.map(r => r.s("researcherId") -> r.s("componentId")).toMap
    assertEquals(
      ids,
      Map(
        "p" -> s"component:$W:p",
        "q" -> s"component:$W:p",
        "r" -> s"component:$W:p",
        "s" -> s"component:$W:s",
      ),
    )
    val sizes =
      Components.sizes(mem).collect().toList.map(r => r.s("componentId") -> r.l("size")).toMap
    assertEquals(sizes, Map(s"component:$W:p" -> 3L, s"component:$W:s" -> 1L))
  }

  // ---- k-core ---------------------------------------------------------------------------------

  test("k-core: K4 with a two-node tail and an isolated node, by hand") {
    // K4 a-b-c-d (3-core), tail d-e, e-f (1-core), z isolated (0)
    val ns = nodes(List("a", "b", "c", "d", "e", "f", "z").map(W -> _)*)
    val as = arcs(
      (W, "a", "b"),
      (W, "a", "c"),
      (W, "a", "d"),
      (W, "b", "c"),
      (W, "b", "d"),
      (W, "c", "d"),
      (W, "d", "e"),
      (W, "e", "f"),
    )
    val (core, conv) = KCore.coreness(ns, as, 50, ck)
    val m            = core.collect().toList.map(r => r.s("id") -> r.i("coreness")).toMap
    assertEquals(m, Map("a" -> 3, "b" -> 3, "c" -> 3, "d" -> 3, "e" -> 1, "f" -> 1, "z" -> 0))
    assert(conv.converged)
    assertEquals(conv.algorithm, "k_core_peeling")
  }

  test("k-core: windows are peeled independently") {
    val W2 = "2005-2009"
    val ns = nodes(List("a", "b", "c").map(W -> _) ++ List("a", "b", "c").map(W2 -> _)*)
    val as = arcs((W, "a", "b"), (W, "b", "c"), (W, "a", "c"), (W2, "a", "b"))
    val m  = KCore
      .coreness(ns, as, 50, ck)
      ._1
      .collect()
      .toList
      .map(r => (r.s("window"), r.s("id")) -> r.i("coreness"))
      .toMap
    assertEquals(m((W, "a")), 2); assertEquals(m((W, "c")), 2)
    assertEquals(m((W2, "a")), 1); assertEquals(m((W2, "b")), 1); assertEquals(m((W2, "c")), 0)
  }

  test("k-core: the round cap reports converged = false and returns a lower bound for everyone") {
    val ns = nodes(List("a", "b", "c", "d", "z").map(W -> _)*)
    val as =
      arcs((W, "a", "b"), (W, "a", "c"), (W, "a", "d"), (W, "b", "c"), (W, "b", "d"), (W, "c", "d"))
    val (core, conv) = KCore.coreness(ns, as, 1, ck)
    assert(!conv.converged)
    val m = core.collect().toList.map(r => r.s("id") -> r.i("coreness")).toMap
    assertEquals(m.keySet, Set("a", "b", "c", "d", "z")) // every node still gets a value
    assert(m.forall((n, c) => c <= (if n == "z" then 0 else 3)), m.toString)
  }

  // ---- bridge measures ------------------------------------------------------------------------

  private def bridgeFixture: (DataFrame, DataFrame) =
    val comm = Map(
      "X"  -> "CA",
      "a1" -> "CA",
      "a2" -> "CA",
      "a3" -> "CA",
      "Y"  -> "CA",
      "b1" -> "CB",
      "b2" -> "CB",
      "c1" -> "CC",
      "z"  -> "CZ",
    )
    val mem = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL(
        "window string, windowStart int, windowEnd int, researcherId string, communityId string"
      ),
      comm.toList.map((r, c) => Row(W, 2000, 2004, r, c)),
    )
    val as = arcs(
      (W, "X", "a1"),
      (W, "X", "a2"),
      (W, "X", "a3"),
      (W, "X", "b1"),
      (W, "X", "c1"),
      (W, "Y", "a1"),
      (W, "Y", "a2"),
      (W, "Y", "b1"),
      (W, "Y", "b2"),
    )
    (mem, as)

  test(
    "bridges: cross-community degree, participation coefficient and communities touched, exact"
  ) {
    val (mem, as) = bridgeFixture
    val out       = Bridges.measures(mem, as, Map("communityMethod" -> "label_propagation.v1"))
    val m         =
      out.collect().toList.map(r => (r.s("researcherId"), r.s("measure")) -> r.d("value")).toMap
    def chk(id: String, cross: Double, pc: Double, distinct: Double) =
      assertEqualsDouble(m((id, "cross_community_degree")), cross, 1e-12, s"$id cross")
      assertEqualsDouble(m((id, "participation_coefficient")), pc, 1e-12, s"$id pc")
      assertEqualsDouble(m((id, "distinct_communities")), distinct, 1e-12, s"$id distinct")
    // X: coauthors a1 a2 a3 (CA) b1 (CB) c1 (CC): k = 5, sum k_c^2 = 9 + 1 + 1 = 11, P = 1 - 11/25 = 0.56
    chk("X", 2, 0.56, 3)
    // Y: a1 a2 (CA) b1 b2 (CB): k = 4, sum = 4 + 4 = 8, P = 1 - 8/16 = 0.5
    chk("Y", 2, 0.5, 2)
    // a1: coauthors X, Y, both CA: P = 1 - 4/4 = 0
    chk("a1", 0, 0.0, 1)
    chk("a2", 0, 0.0, 1)
    // a3: coauthor X only (CA)
    chk("a3", 0, 0.0, 1)
    // b1: coauthors X, Y (both CA), own CB: cross 2, P = 0 (one foreign community), touches CA and CB
    chk("b1", 2, 0.0, 2)
    // b2: coauthor Y (CA): cross 1; c1: coauthor X (CA): cross 1
    chk("b2", 1, 0.0, 2)
    chk("c1", 1, 0.0, 2)
    // z: isolated: no coauthors -> 0, 0 by definition, touches only its own community
    chk("z", 0, 0.0, 1)
  }

  test("bridges: long form with exactly three measures per node, window columns and parameters") {
    val (mem, as) = bridgeFixture
    val out       = Bridges.measures(mem, as, Map("communityMethod" -> "label_propagation.v1"))
    val rows      = out.collect().toList
    assertEquals(rows.size, 27)
    assertEquals(rows.groupBy(_.s("researcherId")).values.map(_.size).toSet, Set(3))
    assertEquals(
      rows.map(_.s("measure")).toSet,
      Set("cross_community_degree", "participation_coefficient", "distinct_communities"),
    )
    assert(rows.forall(r => r.s("window") == W && r.i("windowStart") == 2000))
    assertEquals(
      rows.head.getMap[String, String](rows.head.fieldIndex("parameters")).toMap,
      Map("communityMethod" -> "label_propagation.v1"),
    )
    assert(
      rows
        .filter(_.s("measure") == "participation_coefficient")
        .forall(r => r.d("value") >= 0.0 && r.d("value") < 1.0)
    )
  }

  // ---- label propagation ----------------------------------------------------------------------

  test("communities: two disjoint cliques and an isolated node give three communities") {
    val ns = nodes(List("a", "b", "c", "d", "e", "f", "z").map(W -> _)*)
    val as =
      arcs((W, "a", "b"), (W, "a", "c"), (W, "b", "c"), (W, "d", "e"), (W, "d", "f"), (W, "e", "f"))
    val (labels, conv) = Communities.labelPropagation(ns, as, 20, ck)
    val m              = Communities
      .membership(labels, windows)
      .collect()
      .toList
      .map(r => r.s("researcherId") -> r.s("communityId"))
      .toMap
    assertEquals(Set(m("a"), m("b"), m("c")).size, 1)
    assertEquals(Set(m("d"), m("e"), m("f")).size, 1)
    assertNotEquals(m("a"), m("d"))
    assertNotEquals(m("z"), m("a"))
    // community id = window + smallest member id, whatever label survived
    assertEquals(m("a"), s"community:$W:a")
    assertEquals(m("d"), s"community:$W:d")
    assertEquals(m("z"), s"community:$W:z")
    assert(conv.converged)
    assertEquals(conv.algorithm, "label_propagation.v1")
  }

  test("communities: two adjacent nodes agree, and the result is deterministic") {
    val ns = nodes(List("p", "q").map(W -> _)*)
    val as = arcs((W, "p", "q"))
    val l1 = labelMap(Communities.labelPropagation(ns, as, 20, ck)._1)
    val l2 = labelMap(Communities.labelPropagation(ns, as, 20, ck)._1)
    assertEquals(l1((W, "p")), l1((W, "q")))
    assertEquals(l1, l2)
  }

  test("communities: a community never spans two components and every node has one label") {
    // two dense groups joined by one bridge node m, and a separate pair
    val g1     = List("a1", "a2", "a3", "a4"); val g2 = List("b1", "b2", "b3", "b4")
    val ids    = g1 ++ g2 ++ List("m", "s1", "s2")
    val clique = (g: List[String]) => g.combinations(2).map(p => (W, p(0), p(1))).toList
    val es     = clique(g1) ++ clique(g2) ++ List((W, "a1", "m"), (W, "m", "b1"), (W, "s1", "s2"))
    val ns     = nodes(ids.map(W -> _)*)
    val as     = arcs(es*)
    val (lp, conv) = Communities.labelPropagation(ns, as, 20, ck)
    val comp       = labelMap(Components.labels(ns, as, 50, ck)._1)
    val lab        = labelMap(lp)
    assertEquals(lab.keySet, ids.map(W -> _).toSet)
    assert(conv.converged, conv.toString)
    // community label classes are inside one component
    lab.groupBy(_._2).values.foreach { grp =>
      assertEquals(grp.keys.map(k => comp(k)).toSet.size, 1, grp.toString)
    }
    // the dense groups are internally agreed, the separate pair is its own community
    assertEquals(g1.map(n => lab((W, n))).toSet.size, 1)
    assertEquals(g2.map(n => lab((W, n))).toSet.size, 1)
    assertNotEquals(lab((W, "a1")), lab((W, "b1")))
    assertEquals(lab((W, "s1")), lab((W, "s2")))
    // the bridge node joins one of the two groups (a tie, so either is correct)
    assert(Set(lab((W, "a1")), lab((W, "b1"))).contains(lab((W, "m"))))
  }

  test("communities: the iteration cap reports converged = false") {
    val ns        = nodes(List("a", "b", "c", "d").map(W -> _)*)
    val as        = arcs((W, "a", "b"), (W, "b", "c"), (W, "c", "d"))
    val (_, conv) = Communities.labelPropagation(ns, as, 1, ck)
    assert(!conv.converged)
    assertEquals(conv.iterations, 1)
  }

  test("communities: records, internal and cross-community edge counts, and cross edges") {
    val (mem0, as0) = bridgeFixture
    val edges       = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL(
        "window string, researcherA string, researcherB string"
      ),
      List(
        ("X", "a1"),
        ("X", "a2"),
        ("X", "a3"),
        ("X", "b1"),
        ("X", "c1"),
        ("Y", "a1"),
        ("Y", "a2"),
        ("Y", "b1"),
        ("Y", "b2"),
      ).map((a, b) => Row(W, a, b)),
    )
    val recs = Communities
      .records(mem0, edges, Map("maxIterations" -> "20"))
      .collect()
      .toList
      .map(r => r.s("communityId") -> r)
      .toMap
    // CA = {X a1 a2 a3 Y}: internal edges X-a1 X-a2 X-a3 Y-a1 Y-a2 = 5; cross: X-b1 X-c1 Y-b1 Y-b2 = 4
    assertEquals(
      (recs("CA").l("size"), recs("CA").l("internalEdgeCount"), recs("CA").l("crossEdgeCount")),
      (5L, 5L, 4L),
    )
    // CB = {b1 b2}: internal 0; cross: X-b1 Y-b1 Y-b2 = 3. CC = {c1}: cross 1. CZ: size 1, no edges.
    assertEquals(
      (recs("CB").l("size"), recs("CB").l("internalEdgeCount"), recs("CB").l("crossEdgeCount")),
      (2L, 0L, 3L),
    )
    assertEquals(
      (
        recs("CC").l("crossEdgeCount"),
        recs("CZ").l("crossEdgeCount"),
        recs("CZ").l("internalEdgeCount"),
      ),
      (1L, 0L, 0L),
    )
    assertEquals(recs("CA").s("method"), "label_propagation.v1")
    assertEquals(
      recs("CA").getMap[String, String](recs("CA").fieldIndex("parameters")).toMap,
      Map("maxIterations" -> "20"),
    )
    val cross = Communities
      .crossEdges(mem0, edges, windows)
      .collect()
      .toList
      .map(r => (r.s("communityA"), r.s("communityB")) -> r.l("edgeCount"))
      .toMap
    assertEquals(cross, Map(("CA", "CB") -> 3L, ("CA", "CC") -> 1L))
  }

  // ---- BFS and path evidence ------------------------------------------------------------------

  /** Diamond s-a-t, s-b-t with a tail t-u; edge ids are "e:<a><b>" and works "w:<a><b>" (a < b). */
  private def diamond: (DataFrame, DataFrame) =
    val es    = List(("s", "a"), ("s", "b"), ("a", "t"), ("b", "t"), ("t", "u"))
    val as    = arcs(es.map((a, b) => (W, a, b))*)
    val edges = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL(
        "window string, researcherA string, researcherB string, edgeId string, representativeWorkIds array<string>"
      ),
      es.map { (x, y) =>
        val (a, b) = if x < y then (x, y) else (y, x) // edges are canonical: A < B
        Row(W, a, b, s"e:$a$b", Seq(s"w:$a$b", s"w2:$a$b"))
      },
    )
    (as, edges)

  private def sources(ids: String*): DataFrame =
    TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, source string"),
      ids.map(i => Row(W, i)),
    )

  test("bfs: distances and the smallest-parent rule give a deterministic shortest path tree") {
    val (as, _)         = diamond
    val (visited, conv) = ShortestPaths.bfs(as, sources("s"), 6, ck)
    val m               = visited
      .collect()
      .toList
      .map(r => r.s("node") -> (r.i("dist"), if r.isNull("parent") then null else r.s("parent")))
      .toMap
    assertEquals(
      m,
      Map("s" -> (0, null), "a" -> (1, "s"), "b" -> (1, "s"), "t" -> (2, "a"), "u" -> (3, "t")),
    )
    assert(conv.converged)
    assertEquals(conv.algorithm, "bfs_bounded")
  }

  test("bfs: the depth cap leaves farther nodes unvisited and reports converged = false") {
    val (as, _)         = diamond
    val (visited, conv) = ShortestPaths.bfs(as, sources("s"), 2, ck)
    assertEquals(visited.collect().toList.map(_.s("node")).toSet, Set("s", "a", "b", "t"))
    assert(!conv.converged)
    assertEquals(conv.iterations, 2)
  }

  test("bfs: runs from several selected sources at once, never all pairs") {
    val (as, _) = diamond
    val visited = ShortestPaths.bfs(as, sources("s", "u"), 6, ck)._1.collect().toList
    assertEquals(visited.size, 10) // 5 nodes reached from each of the 2 sources
    assertEquals(visited.map(_.s("source")).toSet, Set("s", "u"))
    val fromU = visited.filter(_.s("source") == "u").map(r => r.s("node") -> r.i("dist")).toMap
    assertEquals(fromU, Map("u" -> 0, "t" -> 1, "a" -> 2, "b" -> 2, "s" -> 3))
  }

  test("path evidence: hops, per-hop edge ids and supporting works, by hand") {
    val (as, edges)  = diamond
    val (visited, _) = ShortestPaths.bfs(as, sources("s"), 6, ck)
    val pairs        = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, startId string, endId string"),
      List(Row(W, "s", "u"), Row(W, "s", "t"), Row(W, "s", "s"), Row(W, "s", "nowhere")),
    )
    val ev = ShortestPaths
      .evidence(visited, pairs, edges, 6)
      .collect()
      .toList
      .map(r => r.s("endId") -> r)
      .toMap
    assertEquals(ev.keySet, Set("u", "t")) // start == end and an unreachable end yield no row
    val u = ev("u")
    assertEquals(u.strs("hops"), List("s", "a", "t", "u"))
    assertEquals(u.i("length"), 3)
    assertEquals(u.strs("edgeIds"), List("e:as", "e:at", "e:tu"))
    assertEquals(
      u.strss("supportingWorkIds"),
      List(List("w:as", "w2:as"), List("w:at", "w2:at"), List("w:tu", "w2:tu")),
    )
    assertEquals(ev("t").strs("hops"), List("s", "a", "t"))
    assertEquals(ev("t").i("length"), 2)
  }

  test("path evidence: a pair farther than the depth cap yields no row") {
    val (as, edges)  = diamond
    val (visited, _) = ShortestPaths.bfs(as, sources("s"), 2, ck)
    val pairs        = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, startId string, endId string"),
      List(Row(W, "s", "u"), Row(W, "s", "t")),
    )
    val ev = ShortestPaths.evidence(visited, pairs, edges, 2).collect().toList
    assertEquals(ev.map(_.s("endId")), List("t"))
  }

  test("estimate: average path length and effective diameter from sampled BFS runs, by hand") {
    // path graph p1-p2-p3-p4 (all four are sources): distances 1,2,3,1,2,1 (x2 ordered)
    // sum = 2 * 10 = 20 over 12 reached pairs -> avg 20/12; histogram d1 6, d2 4, d3 2:
    // cumulative 6, 10, 12 of 12; 90% needs 10.8 -> d = 3
    val ids          = List("p1", "p2", "p3", "p4")
    val as           = arcs((W, "p1", "p2"), (W, "p2", "p3"), (W, "p3", "p4"))
    val (visited, _) = ShortestPaths.bfs(as, sources(ids*), 6, ck)
    val largest      = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, size long"),
      List(Row(W, 4L)),
    )
    val est = ShortestPaths
      .estimate(visited, largest, windows, 50, 441L, 6)
      .collect()
      .toList
      .map(r => r.s("window") -> r)
      .toMap
    val e = est(W)
    assertEquals(
      (e.i("sourcesRequested"), e.l("sourcesUsed"), e.l("seed"), e.i("maxDepth")),
      (50, 4L, 441L, 6),
    )
    assertEquals(e.l("reachedPairs"), 12L)
    assertEqualsDouble(e.d("avgPathLength"), 20.0 / 12.0, 1e-12)
    assertEquals(e.i("effectiveDiameter90"), 3)
    assertEquals(e.i("maxObservedDistance"), 3)
    assertEquals(e.l("unreachedWithinDepthPairs"), 0L)
    // a window with no pair has null estimates and zero counts
    val empty = est("2005-2009")
    assertEquals((empty.l("sourcesUsed"), empty.l("reachedPairs")), (0L, 0L))
    assert(empty.isNull("avgPathLength") && empty.isNull("effectiveDiameter90"))
  }

  test("estimate: pairs missed because of the depth cap are counted (a downward bias)") {
    val as           = arcs((W, "p1", "p2"), (W, "p2", "p3"), (W, "p3", "p4"))
    val (visited, _) = ShortestPaths.bfs(as, sources("p1"), 2, ck)
    val largest      = TestSpark.frame(
      org.apache.spark.sql.types.StructType.fromDDL("window string, size long"),
      List(Row(W, 4L)),
    )
    val e = ShortestPaths
      .estimate(visited, largest, windows, 1, 1L, 2)
      .collect()
      .toList
      .find(_.s("window") == W)
      .get
    // from p1 within 2 hops: p2 (1), p3 (2); p4 is 3 hops away -> 1 unreached pair
    assertEquals((e.l("reachedPairs"), e.l("unreachedWithinDepthPairs")), (2L, 1L))
    assertEqualsDouble(e.d("avgPathLength"), 1.5, 1e-12)
  }

  // ---- sampling -------------------------------------------------------------------------------

  private def members(w: String, ids: List[String]): DataFrame = nodes(ids.map(w -> _)*)

  test("sampling: smallestHash returns the n smallest seeded hashes, deterministically") {
    val ids  = (1 to 20).map(i => f"n$i%02d").toList
    val df   = members(W, ids)
    val pick = Sampling.smallestHash(df, List(W), 441L, 5).collect().toList.map(_.s("id"))
    assertEquals(pick.size, 5)
    // oracle: sort all hashes in the test
    val hashed = df
      .select(F.col("id"), F.xxhash64(F.lit(441L), F.col("id")).as("h"))
      .collect()
      .toList
      .map(r => (r.getLong(1), r.s("id")))
    assertEquals(pick.toSet, hashed.sorted.take(5).map(_._2).toSet)
    assertEquals(
      Sampling.smallestHash(df, List(W), 441L, 5).collect().toList.map(_.s("id")).sorted,
      pick.sorted,
    )
    val other = Sampling.smallestHash(df, List(W), 7L, 5).collect().toList.map(_.s("id"))
    assertNotEquals(other.sorted, pick.sorted)
  }

  test("sampling: n applies per window and a smaller population is returned whole") {
    val df = members("w1", List("a", "b", "c", "d")).union(members("w2", List("x", "y")))
    val r  = Sampling
      .smallestHash(df, List("w1", "w2"), 1L, 3)
      .collect()
      .toList
      .groupBy(_.s("window"))
      .view
      .mapValues(_.size)
      .toMap
    assertEquals(r, Map("w1" -> 3, "w2" -> 2))
  }

  test("sampling: the largest component per window, ties broken by the smallest component id") {
    val mem = TestSpark.frame(
      org.apache.spark.sql.types.StructType
        .fromDDL("window string, componentId string, researcherId string"),
      List(
        Row("w1", "c:a", "a1"),
        Row("w1", "c:a", "a2"),
        Row("w1", "c:a", "a3"),
        Row("w1", "c:b", "b1"),
        Row("w1", "c:b", "b2"),
        Row("w2", "c:q", "q1"),
        Row("w2", "c:q", "q2"),
        Row("w2", "c:p", "p1"),
        Row("w2", "c:p", "p2"),
      ),
    )
    val r = Sampling
      .largestComponentMembers(mem)
      .collect()
      .toList
      .map(x => (x.s("window"), x.s("id"), x.s("componentId"), x.l("size")))
      .toSet
    assertEquals(
      r,
      Set(
        ("w1", "a1", "c:a", 3L),
        ("w1", "a2", "c:a", 3L),
        ("w1", "a3", "c:a", 3L),
        ("w2", "p1", "c:p", 2L),
        ("w2", "p2", "c:p", 2L),
      ),
    )
  }

  test(
    "sampling: samplePairs pairs distinct members consecutively and is bounded by the population"
  ) {
    val large = members(W, (1 to 7).map(i => s"m$i").toList)
      .withColumn("componentId", F.lit("c"))
      .withColumn("size", F.lit(7L))
    val two = Sampling.samplePairs(large, List(W), 441L, 2).collect().toList
    assertEquals(two.size, 2)
    val used = two.flatMap(r => List(r.s("startId"), r.s("endId")))
    assertEquals(used.distinct.size, 4)
    // 7 members can form at most 3 pairs, however many are requested
    assertEquals(Sampling.samplePairs(large, List(W), 441L, 10).count(), 3L)
    assertEquals(Sampling.samplePairs(large, List(W), 441L, 0).count(), 0L)
    assertEquals(
      Sampling
        .samplePairs(large, List(W), 441L, 2)
        .collect()
        .toList
        .map(r => (r.s("startId"), r.s("endId")))
        .sorted,
      two.map(r => (r.s("startId"), r.s("endId"))).sorted,
    )
  }
