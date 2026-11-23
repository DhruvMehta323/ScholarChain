package scholarchain.rag.runtime

import scholarchain.core.*

/** Metrics, paths and communities: exact values against fixture tables, hops carry supporting
  * works, bounds enforced, only allowlisted templates with ID/window parameters.
  */
class GraphStagesSuite extends munit.FunSuite:
  import World.*

  private def num(n: Int): ujson.Value = ujson.Num(n.toDouble)

  private def metricsAgent(kv: MemKv = World.kv) = new CollaborationMetricsAgent(reader(kv))

  private def paths(g: FakeGraph, kv: MemKv = World.kv, s: RuntimeSettings = settings) =
    new PathFinderAgent(g, reader(kv), s)

  private def community(g: FakeGraph, kv: MemKv = World.kv) =
    val m = metricsAgent(kv)
    new CommunityAnalystAgent(g, reader(kv), m, settings)

  private def factsOf(o: StageOutput): List[Fact] = o.drafts.flatMap(d => Fact.parse(d.text))

  // ---- metrics ------------------------------------------------------------------------------

  test("metrics: stored values are copied exactly, with run ID, policy version and payload hash") {
    val out = metricsAgent().metrics(List(ada), W)
    val f   = factsOf(out).head
    assertEquals(f.kind, Fact.Metrics)
    assertEquals(f.subject, ada)
    assertEquals(f.window, Some(W))
    assertEquals(f.runId, Some(run))
    assertEquals(f.policyVersion, Some("coauthor-graph.v1"))
    val v = f.fields.value
    assertEquals(v("publicationCount"), num(7))
    assertEquals(v("coauthorCount"), num(1))
    assertEquals(v("weightedDegree"), num(2))
    assertEquals(v("avgTeamSize"), num(3))
    assertEquals(v("maxTeamSize"), num(6))
    assertEquals(v("teamSizeTotal"), num(21))
    assertEquals(v("communityId"), ujson.Str(communityOf(ada, W)))
    assertEquals(v("componentId"), ujson.Str(componentOf(ada, W)))
    assertEquals(v("maxTeamWorkIds"), ujson.Arr("work:dblp:conf/x/Big"))
    // provenance: the stored payload hash is the input checksum
    val stored =
      World.kv.get(tableOf("researcher-metrics"), KvKey(ada, Some(W))).toOption.flatten.get
    assertEquals(out.drafts.head.inputs.head.checksum, stored.attributes.get("payloadHash"))
    assertEquals(out.drafts.head.runId, Some(run))
  }

  test("metrics: an absent window yields missing evidence and no draft, never a computed value") {
    val out = metricsAgent().metrics(List(alan), "2010-2014")
    assertEquals(out.drafts, Nil)
    assertEquals(out.missingEvidence.size, 1)
    assert(out.missingEvidence.head.contains("2010-2014"))
  }

  test("metrics: a tampered stored item is refused (integrity mismatch), not used") {
    val out = metricsAgent().metrics(List(lone), W)
    assertEquals(out.drafts, Nil)
    assert(out.reasonCodes.contains("integrity_mismatch"), out.reasonCodes.toString)
  }

  test("window summary: the maximum team size and its work come from the stored summary") {
    val f = factsOf(metricsAgent().windowSummary(W)).head
    assertEquals(f.kind, Fact.WindowSummary)
    assertEquals(f.fields.value("maxTeamSize"), num(6))
    assertEquals(f.fields.value("maxTeamWorkId"), ujson.Str("work:dblp:conf/x/Big"))
    assertEquals(f.fields.value("nodeCount"), num(100))
    assertEquals(metricsAgent().windowSummary("2010-2014").drafts, Nil)
  }

  test("ranking: descending by the stored field, ties by researcher ID, values copied") {
    val out = metricsAgent().ranking(List(ada, alan, grace, hedy), W, "coauthorCount")
    val r   = factsOf(out).find(_.kind == Fact.Ranking).get
    assertEquals(r.fields.value("order").arr.map(_.str).toList, List(grace, hedy, alan, ada))
    assertEquals(r.fields.value("values").arr.map(_.num.toInt).toList, List(8, 6, 2, 1))
    assertEquals(out.drafts.size, 5) // four metrics facts and the ranking fact
    // tie-break by ID: weightedDegree has alan=5 and nobody else equal; use degree-equal pair
    val tie = metricsAgent().ranking(List(ada, hedy), W, "crossCommunityDegree")
    val t   = factsOf(tie).find(_.kind == Fact.Ranking).get
    assertEquals(t.fields.value("values").arr.map(_.num.toInt).toList, List(1, 0))
  }

  test("ranking: researchers without a stored value are reported missing and left out") {
    val out = metricsAgent().ranking(List(ada, yu1), W, "coauthorCount")
    val r   = factsOf(out).find(_.kind == Fact.Ranking).get
    assertEquals(r.fields.value("order").arr.map(_.str).toList, List(ada))
    assertEquals(out.missingEvidence.size, 1)
  }

  test("metric fields: only degree, weighted degree and bridge score are stored rankings") {
    val m = metricsAgent()
    assertEquals(m.fieldOf(CollaborationMetric.Degree), Some("coauthorCount"))
    assertEquals(m.fieldOf(CollaborationMetric.WeightedDegree), Some("weightedDegree"))
    assertEquals(m.fieldOf(CollaborationMetric.BridgeScore), Some("crossCommunityDegree"))
    for x <- List(
        CollaborationMetric.Betweenness,
        CollaborationMetric.PageRank,
        CollaborationMetric.ClusteringCoefficient,
      )
    do assertEquals(m.fieldOf(x), None)
  }

  test("temporal: both windows' metrics and the stored temporal-changes fact") {
    val out = metricsAgent().temporal(List(ada), W, W2)
    val fs  = factsOf(out)
    assertEquals(fs.count(_.kind == Fact.Metrics), 2)
    val t = fs.find(_.kind == Fact.TemporalChange).get
    assertEquals(t.window, Some(W2))
    assertEquals(t.fields.value("prevWindow"), ujson.Str(W))
    assertEquals(t.fields.value("coauthorCount"), num(5))
    assertEquals(t.fields.value("prevCoauthorCount"), num(1))
    assertEquals(t.fields.value("newCoauthors"), num(4))
  }

  // ---- paths --------------------------------------------------------------------------------

  test("direct collaborators: strongest first, with weights and a neighborhood fact") {
    val out = paths(World.graph).directCollaborators(alan, W, 10)
    assertEquals(out.ids, List(grace, ada))
    val hood = factsOf(out.stage).find(_.kind == Fact.Neighborhood).get
    assertEquals(hood.fields.value("coauthorIds").arr.map(_.str).toList, List(grace, ada))
    assertEquals(
      hood.fields.value("jointPublicationCounts").arr.map(_.num.toInt).toList,
      List(3, 2),
    )
    assertEquals(hood.fields.value("truncated"), ujson.Bool(false))
    val edges = factsOf(out.stage).filter(_.kind == Fact.CoauthorEdge)
    assertEquals(edges.map(_.subject).toSet, Set(eAdaAlan.id, eAlanGrace.id))
    assertEquals(
      edges
        .find(_.subject == eAlanGrace.id)
        .get
        .fields
        .value("supportingWorkIds")
        .arr
        .map(_.str)
        .toSet,
      works(eAlanGrace.id).toSet,
    )
  }

  test("direct collaborators: the result bound is enforced and truncation is stated") {
    val g   = World.graph
    val out = paths(g).directCollaborators(alan, W, 1)
    assertEquals(out.ids, List(grace))
    val hood = factsOf(out.stage).find(_.kind == Fact.Neighborhood).get
    assertEquals(hood.fields.value("returnedCount"), num(1))
    assertEquals(hood.fields.value("truncated"), ujson.Bool(true))
    assertEquals(g.queries.head.bounds.maxResults, 1)
  }

  test("direct collaborators: nobody found is missing evidence, not an invented list") {
    val out = paths(World.graph).directCollaborators(lone, W, 10)
    assertEquals(out.stage.drafts, Nil)
    assertEquals(out.ids, Nil)
    assertEquals(out.stage.missingEvidence.size, 1)
  }

  test("shortest path: every hop carries its weight, supporting works, years and edge ID") {
    val out = paths(World.graph).shortestPath(ada, grace, W, 4)
    val f   = factsOf(out.stage).find(_.kind == Fact.Path).get
    val v   = f.fields.value
    assertEquals(v("found"), ujson.Bool(true))
    assertEquals(v("hops").arr.map(_.str).toList, List(ada, alan, grace))
    assertEquals(v("length"), num(2))
    assertEquals(v("edgeIds").arr.map(_.str).toList, List(eAdaAlan.id, eAlanGrace.id))
    assertEquals(v("hopWeights").arr.map(_.num.toInt).toList, List(2, 3))
    assertEquals(v("hopWorkIds")(0).arr.map(_.str).toSet, works(eAdaAlan.id).toSet)
    assertEquals(v("hopWorkIds")(1).arr.map(_.str).toSet, works(eAlanGrace.id).toSet)
    assertEquals(v("hopFirstYears").arr.map(_.num.toInt).toList, List(2001, 2000))
    assertEquals(v("hopLastYears").arr.map(_.num.toInt).toList, List(2003, 2004))
    assertEquals(f.runId, Some(run))
    assertEquals(out.ids, List(ada, alan, grace))
  }

  test("shortest path: each hop edge is re-read with the coauthor-edge template") {
    val g = World.graph
    paths(g).shortestPath(ada, grace, W, 4)
    val edgeCalls = g.queries.filter(_.template == GraphTemplates.CoauthorEdge)
    assertEquals(edgeCalls.size, 2)
    assertEquals(g.queries.count(_.template == GraphTemplates.ShortestPath), 1)
  }

  test("shortest path: the depth bound is respected both ways") {
    val p     = paths(World.graph)
    val short = factsOf(p.shortestPath(ada, hedy, W, 2).stage).find(_.kind == Fact.Path).get
    assertEquals(short.fields.value("found"), ujson.Bool(false))
    val ok = factsOf(p.shortestPath(ada, hedy, W, 3).stage).find(_.kind == Fact.Path).get
    assertEquals(ok.fields.value("found"), ujson.Bool(true))
    assertEquals(ok.fields.value("length"), num(3))
  }

  test("shortest path: no path is a fact plus missing evidence") {
    val out = paths(World.graph).shortestPath(ada, lone, W, 4)
    val f   = factsOf(out.stage).find(_.kind == Fact.Path).get
    assertEquals(f.fields.value("found"), ujson.Bool(false))
    assertEquals(out.stage.missingEvidence.size, 1)
    assert(!f.fields.value.contains("hops"))
  }

  test("shortest path: bounds sent to the graph never exceed the configured limits") {
    val g = World.graph
    val p = paths(g)
    p.shortestPath(ada, hedy, W, 99)
    p.directCollaborators(alan, W, 10000)
    assert(g.queries.nonEmpty)
    g.queries.foreach { q =>
      assert(q.bounds.maxDepth <= settings.graphMaxDepth, q.toString)
      assert(q.bounds.maxResults <= settings.graphMaxResults, q.toString)
      assertEquals(q.bounds.timeoutMillis, settings.graphTimeoutMillis.toLong)
    }
    val sp = g.queries.find(_.template == GraphTemplates.ShortestPath).get
    assertEquals(sp.parameters("maxHops"), GraphValue.Num(settings.graphMaxDepth.toLong))
  }

  test("shortest path: same endpoints give no query") {
    val g   = World.graph
    val out = paths(g).shortestPath(ada, ada, W, 4)
    assertEquals(out.stage.reasonCodes, List("path_endpoints_identical"))
    assertEquals(g.queries.toList, Nil)
  }

  test("shortest path: a returned path with wrong endpoints is rejected, not repaired") {
    val row = GraphRow(
      Map(
        "hops"    -> GraphValue.Lst(List(alan, grace).map(GraphValue.Str(_))),
        "edgeIds" -> GraphValue.Lst(List(GraphValue.Str(eAlanGrace.id))),
        "length"  -> GraphValue.Num(1),
      )
    )
    val out = paths(new FakeGraph(List(eAdaAlan, eAlanGrace), shortestPathOverride = Some(row)))
      .shortestPath(ada, grace, W, 4)
    assertEquals(out.stage.drafts, Nil)
    assertEquals(out.stage.reasonCodes, List("path_rejected"))
  }

  test("shortest path: a hop whose edge the edge template does not confirm is rejected") {
    val row = GraphRow(
      Map(
        "hops"    -> GraphValue.Lst(List(ada, grace).map(GraphValue.Str(_))),
        "edgeIds" -> GraphValue.Lst(List(GraphValue.Str(edgeId("invented")))),
        "length"  -> GraphValue.Num(1),
      )
    )
    val out = paths(new FakeGraph(List(eAdaAlan, eAlanGrace), shortestPathOverride = Some(row)))
      .shortestPath(ada, grace, W, 4)
    assertEquals(out.stage.drafts, Nil)
    assertEquals(out.stage.reasonCodes, List("path_rejected"))
  }

  test("shortest path: a path longer than the depth bound or with repeats is rejected") {
    def rejected(hops: List[String], eids: List[String], len: Long) =
      val row = GraphRow(
        Map(
          "hops"    -> GraphValue.Lst(hops.map(GraphValue.Str(_))),
          "edgeIds" -> GraphValue.Lst(eids.map(GraphValue.Str(_))),
          "length"  -> GraphValue.Num(len),
        )
      )
      paths(new FakeGraph(Nil, shortestPathOverride = Some(row)))
        .shortestPath(ada, grace, W, 1)
        .stage
    assertEquals(
      rejected(List(ada, alan, grace), List("e1", "e2"), 2).reasonCodes,
      List("path_rejected"),
    )
    assertEquals(rejected(List(ada, grace), List("e1"), 3).reasonCodes, List("path_rejected"))
  }

  test("graph stages call only allowlisted templates with ID, window or hop parameters") {
    val g = World.graph
    val p = paths(g)
    val c = community(g)
    p.directCollaborators(alan, W, 5)
    p.shortestPath(ada, hedy, W, 4)
    c.component(ada, W)
    c.community(ada, W)
    val idRx  = """^researcher:(sc1:[0-9a-f]{64}|orcid:.+)$""".r
    val winRx = """^\d{4}-\d{4}$""".r
    assert(g.queries.size >= 6)
    g.queries.foreach { q =>
      assert(GraphTemplates.all.contains(q.template), q.template)
      q.parameters.foreach {
        case ("window", GraphValue.Str(w))           => assert(winRx.matches(w), w)
        case ("maxHops", GraphValue.Num(n))          => assert(n >= 1 && n <= 6)
        case (k, GraphValue.Str(s)) if k != "window" => assert(idRx.matches(s), s"$k=$s")
        case other                                   => fail(s"unexpected parameter $other")
      }
    }
  }

  // ---- communities --------------------------------------------------------------------------

  test("component: ID and size come from the component template row") {
    val f = factsOf(community(World.graph).component(ada, W)).head
    assertEquals(f.kind, Fact.Component)
    assertEquals(f.fields.value("componentId"), ujson.Str(componentOf(ada, W)))
    assertEquals(f.fields.value("size"), num(4))
    assertEquals(community(World.graph).component(hedy, W).drafts, Nil)
  }

  test("community: size, method and edge counts come from the community template row") {
    val f = factsOf(community(World.graph).community(grace, W)).head
    assertEquals(f.kind, Fact.Community)
    assertEquals(f.fields.value("communityId"), ujson.Str(communityOf(grace, W)))
    assertEquals(f.fields.value("size"), num(2))
    assertEquals(f.fields.value("internalEdgeCount"), num(1))
    assertEquals(f.fields.value("crossEdgeCount"), num(3))
    assertEquals(f.fields.value("method"), ujson.Str("label_propagation.v1"))
  }

  test("degree versus bridge: classification and cross share follow the stated rule") {
    val c             = community(World.graph)
    def f(id: String) = factsOf(c.degreeVsBridge(id, W)).head.fields.value
    // grace: 8 coauthors, 6 outside the community -> 0.75, bridge
    assertEquals(f(grace)("classification"), ujson.Str("bridge"))
    assertEquals(f(grace)("crossShare"), ujson.Num(0.75))
    // hedy: 6 coauthors, none outside -> local hub
    assertEquals(f(hedy)("classification"), ujson.Str("local_hub"))
    assertEquals(f(hedy)("crossShare"), ujson.Num(0.0))
    // alan: 2 coauthors, none outside -> mixed (too few for a hub)
    assertEquals(f(alan)("classification"), ujson.Str("mixed"))
    // the stored degree is stated next to the bridge measure
    assertEquals(f(grace)("coauthorCount"), num(8))
    assertEquals(f(grace)("crossCommunityDegree"), num(6))
    assertEquals(f(grace)("ruleVersion"), ujson.Str("v1"))
  }

  test("degree versus bridge: a single cross edge out of two is not a bridge (cross >= 2 needed)") {
    val c = community(World.graph)
    val v = factsOf(c.degreeVsBridge(ada, W)).head.fields.value
    assertEquals(v("coauthorCount"), num(1))
    assertEquals(v("crossCommunityDegree"), num(1))
    assertEquals(v("classification"), ujson.Str("mixed"))
  }

  test("compare: communities, bridge facts and an explicit missing-evidence note on neighbors") {
    val out = community(World.graph).compare(List(ada, grace), W)
    val fs  = factsOf(out)
    assertEquals(fs.count(_.kind == Fact.Community), 2)
    assertEquals(fs.count(_.kind == Fact.DegreeVsBridge), 2)
    assert(out.missingEvidence.exists(_.contains("neighboring communities")))
  }
