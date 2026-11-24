package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types.StructType

import scholarchain.core.*

/** Phase 11 T: adversarial graph inputs: empty and disconnected graphs, unresolved and conflicting
  * pair endpoints, duplicated or self arcs, and known small graphs with hand-computed answers.
  */
class AdversarialGraphSuite extends munit.FunSuite:

  import Fixture.{rid, workId}

  private val spark        = TestSpark.spark
  private lazy val ck      = Checkpointer(TestSpark.useCheckpointFor("adv-algorithms"))
  private lazy val cfg     = TestSpark.config(TestSpark.useCheckpointFor("adv-cfg"))
  private lazy val windows = Graph.windowsFrame(spark, cfg)
  private val W            = "2000-2004"
  private val MrSchema     = StructType.fromDDL("mentionId string, researcherId string")
  private val NodeSchema   = StructType.fromDDL("window string, id string")
  private val ArcSchema    = StructType.fromDDL("window string, src string, dst string")

  private def nodes(ids: String*): DataFrame =
    TestSpark.frame(NodeSchema, ids.map(i => Row(W, i)))

  private def arcs(es: (String, String)*): DataFrame =
    TestSpark.frame(ArcSchema, es.flatMap((a, b) => List(Row(W, a, b), Row(W, b, a))))

  private def coreMap(ns: DataFrame, as: DataFrame): Map[String, Int] =
    KCore.coreness(ns, as, 50, ck)._1.collect().toList.map(r => r.s("id") -> r.i("coreness")).toMap

  private def labels(ns: DataFrame, as: DataFrame): Map[String, String] =
    Components.labels(ns, as, 50, ck)._1.collect().toList.map(r => r.s("id") -> r.s("label")).toMap

  // ---- empty graphs ---------------------------------------------------------------------------

  test("components: an empty graph gives no labels and converges") {
    val (l, conv) =
      Components.labels(TestSpark.empty(NodeSchema), TestSpark.empty(ArcSchema), 5, ck)
    assertEquals(l.count(), 0L)
    assert(conv.converged)
  }

  test("k-core: an empty graph gives no rows and converges") {
    val (c, conv) = KCore.coreness(TestSpark.empty(NodeSchema), TestSpark.empty(ArcSchema), 5, ck)
    assertEquals(c.count(), 0L)
    assert(conv.converged)
  }

  test("bfs: a source that has no arcs reaches only itself at distance 0") {
    val src = TestSpark.frame(
      StructType.fromDDL("window string, source string"),
      List(Row(W, "lonely")),
    )
    val (v, conv) = ShortestPaths.bfs(TestSpark.empty(ArcSchema), src, 4, ck)
    val rows      = v.collect().toList
    assertEquals(rows.map(r => r.s("node") -> r.i("dist")), List("lonely" -> 0))
    assert(conv.converged)
  }

  test("bfs: a source in a different component never reaches the other component") {
    val src = TestSpark.frame(
      StructType.fromDDL("window string, source string"),
      List(Row(W, "a")),
    )
    val (v, _) = ShortestPaths.bfs(arcs("a" -> "b", "x" -> "y"), src, 6, ck)
    assertEquals(v.collect().toList.map(_.s("node")).toSet, Set("a", "b"))
  }

  // ---- disconnected and known graphs ----------------------------------------------------------

  test("components: three disjoint components keep their own smallest ids") {
    val m = labels(
      nodes("a", "b", "c", "d", "e", "f", "g"),
      arcs("a" -> "b", "c" -> "d", "d" -> "e"),
    )
    assertEquals(
      m,
      Map("a" -> "a", "b" -> "a", "c" -> "c", "d" -> "c", "e" -> "c", "f" -> "f", "g" -> "g"),
    )
  }

  test("k-core: a star has coreness 1 for every node, centre included") {
    val m = coreMap(
      nodes("c", "l1", "l2", "l3", "l4"),
      arcs("c" -> "l1", "c" -> "l2", "c" -> "l3", "c" -> "l4"),
    )
    assertEquals(m.values.toSet, Set(1))
  }

  test("k-core: a path has coreness 1 and an isolated node has 0") {
    val m = coreMap(nodes("a", "b", "c", "d", "z"), arcs("a" -> "b", "b" -> "c", "c" -> "d"))
    assertEquals(m, Map("a" -> 1, "b" -> 1, "c" -> 1, "d" -> 1, "z" -> 0))
  }

  test("k-core: a triangle with a pendant node gives 2,2,2,1") {
    val m = coreMap(
      nodes("a", "b", "c", "p"),
      arcs("a" -> "b", "b" -> "c", "a" -> "c", "c" -> "p"),
    )
    assertEquals(m, Map("a" -> 2, "b" -> 2, "c" -> 2, "p" -> 1))
  }

  test("k-core: two triangles joined by a bridge edge are both 2-cores") {
    val m = coreMap(
      nodes("a", "b", "c", "x", "y", "z"),
      arcs("a" -> "b", "b" -> "c", "a" -> "c", "x" -> "y", "y" -> "z", "x" -> "z", "c" -> "x"),
    )
    assertEquals(m.values.toSet, Set(2))
  }

  test("k-core: an arc listed twice does not inflate coreness (a single edge is a 1-core)") {
    val dup = TestSpark.frame(
      ArcSchema,
      List(Row(W, "a", "b"), Row(W, "b", "a"), Row(W, "a", "b"), Row(W, "b", "a")),
    )
    assertEquals(coreMap(nodes("a", "b"), dup), Map("a" -> 1, "b" -> 1))
  }

  test("k-core: a self arc does not lift a node out of the 0-core") {
    val self = TestSpark.frame(ArcSchema, List(Row(W, "a", "a")))
    assertEquals(coreMap(nodes("a"), self), Map("a" -> 0))
  }

  test("components: a self arc and a duplicated arc change no label") {
    val as = TestSpark.frame(
      ArcSchema,
      List(Row(W, "a", "a"), Row(W, "a", "b"), Row(W, "b", "a"), Row(W, "a", "b")),
    )
    assertEquals(labels(nodes("a", "b", "c"), as), Map("a" -> "a", "b" -> "a", "c" -> "c"))
  }

  test("bfs: on a 5-cycle the farthest node is at distance 2 with a deterministic parent") {
    val src = TestSpark.frame(
      StructType.fromDDL("window string, source string"),
      List(Row(W, "a")),
    )
    val (v, _) =
      ShortestPaths.bfs(
        arcs("a" -> "b", "b" -> "c", "c" -> "d", "d" -> "e", "e" -> "a"),
        src,
        6,
        ck,
      )
    val d = v.collect().toList.map(r => r.s("node") -> r.i("dist")).toMap
    assertEquals(d, Map("a" -> 0, "b" -> 1, "e" -> 1, "c" -> 2, "d" -> 2))
  }

  // ---- unresolved and conflicting pair endpoints ----------------------------------------------

  private def edgesOf(pairs: DataFrame, mr: DataFrame): List[Row] =
    Graph.edges(Graph.canonicalPairs(Graph.mappedPairs(pairs, mr)), windows, cfg).collect().toList

  test("a pair with an unmapped mention yields no edge and no null endpoint") {
    val mr    = TestSpark.frame(MrSchema, List(Row("m:a", rid("Ada"))))
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(Row(workId("W01"), 2001, "m:a", "m:unknown")),
    )
    assertEquals(edgesOf(pairs, mr), Nil)
  }

  test(
    "a pair whose endpoints merged to one researcher is not an edge but is counted as a self pair"
  ) {
    val mr    = TestSpark.frame(MrSchema, List(Row("m:a1", rid("Ada")), Row("m:a2", rid("Ada"))))
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(Row(workId("W01"), 2001, "m:a1", "m:a2")),
    )
    val mapped = Graph.mappedPairs(pairs, mr)
    assertEquals(mapped.count(), 1L)
    assertEquals(Graph.canonicalPairs(mapped).count(), 0L)
    assertEquals(edgesOf(pairs, mr), Nil)
  }

  test("a mention mapped to two researchers never makes one work count twice for one pair") {
    val mr = TestSpark.frame(
      MrSchema,
      List(
        Row("m:a", rid("Ada")),
        Row("m:a", rid("Ada")), // exact duplicate mapping row
        Row("m:b", rid("Bob")),
      ),
    )
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(Row(workId("W01"), 2001, "m:a", "m:b")),
    )
    val es = edgesOf(pairs, mr).filter(_.s("window") == W)
    assertEquals(es.size, 1)
    assertEquals(es.head.i("jointPublicationCount"), 1)
  }

  test(
    "a conflicting mention mapping gives edges only to the mapped researchers, none to a null id"
  ) {
    val mr = TestSpark.frame(
      MrSchema,
      List(Row("m:a", rid("Ada")), Row("m:a", rid("Ada Two")), Row("m:b", rid("Bob"))),
    )
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(Row(workId("W01"), 2001, "m:a", "m:b")),
    )
    val es   = edgesOf(pairs, mr)
    val ends = es.flatMap(r => List(r.s("researcherA"), r.s("researcherB"))).toSet
    assertEquals(ends, Set(rid("Ada"), rid("Ada Two"), rid("Bob")))
    assert(es.forall(_.i("jointPublicationCount") == 1))
  }

  // ---- large-team pair expansion --------------------------------------------------------------

  test("a 30-author work expands to 435 pairs and 435 edges in its window, not more") {
    val names = (1 to 30).map(i => f"Author $i%02d")
    val mr    = TestSpark.frame(
      MrSchema,
      names.zipWithIndex.map((n, i) => Row(s"m:$i", rid(n))),
    )
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      (for i <- 0 until 30; j <- i + 1 until 30
      yield Row(workId("BIG"), 2002, s"m:$i", s"m:$j")).toList,
    )
    val es = edgesOf(pairs, mr)
    assertEquals(es.count(_.s("window") == W), 435)
    assertEquals(es.count(_.s("window") == "2005-2009"), 0)
    assert(es.forall(_.i("jointPublicationCount") == 1))
  }

  test("a work in the year after the last window bound lands in no explicit window") {
    val mr    = TestSpark.frame(MrSchema, List(Row("m:a", rid("Ada")), Row("m:b", rid("Bob"))))
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(Row(workId("W01"), 2010, "m:a", "m:b")),
    )
    assertEquals(edgesOf(pairs, mr).map(_.s("window")).toSet, Set("1000-9999"))
  }
