package scholarchain.storage

import scholarchain.core.*

import NeptuneFx.*
import StorageFx.*

/** Parameterized Neptune query templates: bounds, allowlist, types, and injection resistance. */
class NeptuneQueryTemplatesSuite extends munit.FunSuite:

  private val ceiling = QueryBounds(6, 100, 5000L)
  private val cfg     = StorageNeptuneLoad(
    "arn:aws:iam::000000000000:role/placeholder",
    "opencypher",
    "s3://placeholder-bucket/graph/neptune-load/",
  )

  private def store(exec: RecordingCypherExecutor): TemplateGraphStore =
    TemplateGraphStore(ceiling, cfg, "us-east-1", exec, FakeBulkLoader())

  private def str(s: String): GraphValue = GraphValue.Str(s)

  private def neighbors(
      rid: GraphValue = str(r1),
      window: GraphValue = str(win1),
      bounds: QueryBounds = QueryBounds(3, 50, 2000L),
      extra: Map[String, GraphValue] = Map.empty,
  ): GraphQuery =
    GraphQuery(
      GraphTemplates.Neighbors,
      Map("researcherId" -> rid, "window" -> window) ++ extra,
      bounds,
    )

  private def rejected(q: GraphQuery)(using munit.Location): Unit =
    val exec = RecordingCypherExecutor()
    val out  = store(exec).query(q)
    assert(out.left.exists(_.isInstanceOf[StorageError.InvalidRequest]), out.toString)
    assertEquals(exec.calls, Nil, "the executor must not be called for a rejected query")

  // ---- bounds ---------------------------------------------------------------------------------

  test("an in-bound query reaches the executor once, with the bounds applied") {
    val exec = RecordingCypherExecutor()
    val out  = store(exec).query(neighbors())
    assertEquals(out, Right(GraphResult(Nil, false)))
    assertEquals(exec.calls.size, 1)
    val c = exec.calls.head
    assert(c.cypher.contains("USING QUERY:TIMEOUTMILLISECONDS 2000"), c.cypher)
    assertEquals(c.timeoutMillis, 2000L)
    assertEquals(ujson.read(c.parametersJson)("limit").num, 51.0) // maxResults + 1
    assert(!c.cypher.contains("{{"), "no template hole may remain")
  }

  test("a depth above the ceiling, a zero depth and a negative depth are rejected") {
    rejected(neighbors(bounds = QueryBounds(7, 50, 2000L)))
    rejected(neighbors(bounds = QueryBounds(0, 50, 2000L)))
    rejected(neighbors(bounds = QueryBounds(-1, 50, 2000L)))
  }

  test("a result limit above the ceiling or below one is rejected") {
    rejected(neighbors(bounds = QueryBounds(3, 101, 2000L)))
    rejected(neighbors(bounds = QueryBounds(3, 0, 2000L)))
  }

  test("a timeout above the ceiling or below one is rejected") {
    rejected(neighbors(bounds = QueryBounds(3, 50, 5001L)))
    rejected(neighbors(bounds = QueryBounds(3, 50, 0L)))
    rejected(neighbors(bounds = QueryBounds(3, 50, Long.MaxValue)))
  }

  test("bounds exactly at the ceiling are accepted") {
    val exec = RecordingCypherExecutor()
    assert(store(exec).query(neighbors(bounds = ceiling)).isRight)
    assertEquals(exec.calls.size, 1)
  }

  test("a configured ceiling above the hard ceilings is refused") {
    assert(GraphTemplateCatalog.checkCeiling(QueryBounds(11, 100, 5000L)).isLeft)
    assert(GraphTemplateCatalog.checkCeiling(QueryBounds(6, 1001, 5000L)).isLeft)
    assert(GraphTemplateCatalog.checkCeiling(QueryBounds(6, 100, 60001L)).isLeft)
    assert(GraphTemplateCatalog.checkCeiling(QueryBounds(10, 1000, 60000L)).isRight)
    val exec = RecordingCypherExecutor()
    val s    =
      TemplateGraphStore(QueryBounds(50, 100, 5000L), cfg, "us-east-1", exec, FakeBulkLoader())
    assert(s.query(neighbors()).isLeft)
    assertEquals(exec.calls, Nil)
  }

  test("shortest-path: the depth in the pattern is the validated maxHops or the bound, as digits") {
    val exec = RecordingCypherExecutor()
    val q    = GraphQuery(
      GraphTemplates.ShortestPath,
      Map(
        "from"    -> str(r1),
        "to"      -> str(r2),
        "window"  -> str(win1),
        "maxHops" -> GraphValue.Num(2L),
      ),
      QueryBounds(4, 10, 1000L),
    )
    assert(store(exec).query(q).isRight)
    assert(exec.calls.head.cypher.contains("COAUTHORED_WITH*1..2]"), exec.calls.head.cypher)
    val q2 = q.copy(parameters = q.parameters - "maxHops")
    assert(store(exec).query(q2).isRight)
    assert(exec.calls(1).cypher.contains("COAUTHORED_WITH*1..4]"), exec.calls(1).cypher)
    assert(!ujson.read(exec.calls(1).parametersJson).obj.contains("maxHops"))
  }

  test("shortest-path: maxHops outside 1..maxDepth or of the wrong type is rejected") {
    def sp(h: GraphValue) = GraphQuery(
      GraphTemplates.ShortestPath,
      Map("from" -> str(r1), "to" -> str(r2), "window" -> str(win1), "maxHops" -> h),
      QueryBounds(4, 10, 1000L),
    )
    rejected(sp(GraphValue.Num(5L)))
    rejected(sp(GraphValue.Num(0L)))
    rejected(sp(GraphValue.Num(-3L)))
    rejected(sp(str("3")))
    rejected(sp(GraphValue.Dbl(2.0)))
  }

  // ---- allowlist and types --------------------------------------------------------------------

  test("unknown templates are rejected, including free-form text, and nothing is executed") {
    List("drop-all", "MATCH (n) DETACH DELETE n", "", "neighbors ", "Neighbors", "neighbors;")
      .foreach { t =>
        rejected(
          GraphQuery(
            t,
            Map("researcherId" -> str(r1), "window" -> str(win1)),
            QueryBounds(3, 5, 100L),
          )
        )
      }
  }

  test("unknown and missing parameters are rejected") {
    rejected(neighbors(extra = Map("cypher" -> str("MATCH (n) RETURN n"))))
    rejected(
      neighbors(extra = Map("limit" -> GraphValue.Num(10000L)))
    ) // limit is not caller-settable
    rejected(
      GraphQuery(GraphTemplates.Neighbors, Map("researcherId" -> str(r1)), QueryBounds(3, 5, 100L))
    )
    rejected(
      GraphQuery(GraphTemplates.Neighbors, Map("window" -> str(win1)), QueryBounds(3, 5, 100L))
    )
    rejected(GraphQuery(GraphTemplates.Neighbors, Map.empty, QueryBounds(3, 5, 100L)))
  }

  test("parameters of the wrong type are rejected") {
    rejected(neighbors(rid = GraphValue.Num(1L)))
    rejected(neighbors(rid = GraphValue.Null))
    rejected(neighbors(rid = GraphValue.Lst(List(str(r1)))))
    rejected(neighbors(rid = GraphValue.Obj(Map("a" -> str(r1)))))
    rejected(neighbors(window = GraphValue.Num(2000L)))
    rejected(neighbors(window = GraphValue.Bool(true)))
  }

  test("a malformed researcher ID or window label is rejected") {
    rejected(neighbors(rid = str("researcher:sc1:xyz")))
    rejected(neighbors(rid = str(r1 + "0")))
    rejected(neighbors(rid = str("")))
    rejected(neighbors(window = str("2000")))
    rejected(neighbors(window = str("2000-2004 ")))
    rejected(neighbors(window = str("20000-2004")))
    rejected(neighbors(window = str("abcd-efgh")))
  }

  // ---- injection ------------------------------------------------------------------------------

  private val injections = List(
    "x' OR 1=1 //",
    "}) DETACH DELETE n //",
    "`; MATCH (n) DETACH DELETE n; `",
    "researcher:sc1:" + "a" * 64 + "\n}) DETACH DELETE n //",
    "researcher:sc1:" + "a" * 64 + "' OR '1'='1",
    "$researcherId",
    "{{DEPTH}}",
    "{{TIMEOUT}}",
    "\" OR true //",
    "a\nb",
    "\u0000",
  )

  test("injection strings in any parameter never reach the executor") {
    injections.foreach { inj =>
      rejected(neighbors(rid = str(inj)))
      rejected(neighbors(window = str(inj)))
      rejected(
        GraphQuery(
          GraphTemplates.CoauthorEdge,
          Map("researcherA" -> str(r1), "researcherB" -> str(inj), "window" -> str(win1)),
          QueryBounds(2, 5, 100L),
        )
      )
      rejected(
        GraphQuery(
          GraphTemplates.ShortestPath,
          Map("from" -> str(inj), "to" -> str(r2), "window" -> str(win1)),
          QueryBounds(2, 5, 100L),
        )
      )
    }
  }

  test("an injection string used as a parameter NAME is rejected") {
    injections.foreach(inj => rejected(neighbors(extra = Map(inj -> str("x")))))
  }

  test(
    "for valid input the query text is the constant template and values appear only in the parameters JSON"
  ) {
    val exec = RecordingCypherExecutor()
    assert(store(exec).query(neighbors()).isRight)
    val c = exec.calls.head
    assert(!c.cypher.contains(r1), "researcher ID must not be inlined")
    assert(!c.cypher.contains(win1), "window must not be inlined")
    assert(
      c.cypher.contains("$researcherId") && c.cypher.contains("$window") && c.cypher.contains(
        "$limit"
      )
    )
    val params = ujson.read(c.parametersJson).obj
    assertEquals(params("researcherId").str, r1)
    assertEquals(params("window").str, win1)
    assertEquals(params.keySet.toSet, Set("researcherId", "window", "limit"))
    // the text equals the catalog constant after the two numeric holes are filled
    val expected =
      GraphTemplateCatalog.templates(GraphTemplates.Neighbors).cypher.replace("{{TIMEOUT}}", "2000")
    assertEquals(c.cypher, expected)
  }

  test("every template is read-only and holds only the two numeric holes") {
    val write = "(?i)\\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|DROP|LOAD|FOREACH)\\b".r
    GraphTemplateCatalog.templates.values.foreach { t =>
      assert(write.findFirstIn(t.cypher).isEmpty, s"${t.name} contains a write clause")
      val holes = "\\{\\{[A-Z]+\\}\\}".r.findAllIn(t.cypher).toSet
      assert(holes.subsetOf(Set("{{DEPTH}}", "{{TIMEOUT}}")), s"${t.name}: $holes")
      assert(t.cypher.contains("$limit"), s"${t.name} has no result limit")
      assert(t.cypher.contains(GraphTemplateCatalog.Hint.trim), s"${t.name} has no timeout hint")
    }
    assertEquals(GraphTemplateCatalog.templates.keySet, GraphTemplates.all.toSet)
  }

  test("results beyond maxResults are cut and reported as truncated") {
    val rows = (1 to 3).toList.map(i => GraphRow(Map("n" -> GraphValue.Num(i.toLong))))
    val exec = RecordingCypherExecutor(rows)
    val out  = store(exec).query(neighbors(bounds = QueryBounds(3, 2, 1000L)))
    assertEquals(out.map(r => (r.rows.size, r.truncated)), Right((2, true)))
    val exact = store(RecordingCypherExecutor(rows.take(2)))
      .query(neighbors(bounds = QueryBounds(3, 2, 1000L)))
    assertEquals(exact.map(r => (r.rows.size, r.truncated)), Right((2, false)))
  }
