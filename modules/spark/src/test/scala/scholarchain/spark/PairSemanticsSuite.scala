package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructType

import scholarchain.core.*

/** Pair semantics and the temporal weighted coauthorship graph (Graph.*), on small frames. */
class PairSemanticsSuite extends munit.FunSuite:

  import Fixture.{rid, nameOf, workId}

  private val spark    = TestSpark.spark
  private val MrSchema = StructType.fromDDL("mentionId string, researcherId string")

  private lazy val cfg     = TestSpark.config(TestSpark.useCheckpointFor("pairs"))
  private lazy val windows = Graph.windowsFrame(spark, cfg)

  /** mention -> researcher map of the fixture: every author is their own researcher. */
  private lazy val fixtureMr = TestSpark.frame(
    MrSchema,
    for
      w         <- Fixture.works
      (name, p) <- w.authors.zipWithIndex
    yield Row(Fixture.mention(w, p), rid(name)),
  )

  private lazy val inputs = Fixture.loaded(spark)

  private lazy val authored = Graph.authored(inputs.authorship, fixtureMr)
  private lazy val mapped   = Graph.mappedPairs(inputs.pairs, fixtureMr)
  private lazy val canon    = Graph.canonicalPairs(mapped)
  private lazy val edges    = Graph.edges(canon, windows, cfg)

  private def edgeRows(window: String): Map[(String, String), Row] =
    edges
      .collect()
      .toList
      .filter(_.s("window") == window)
      .map(r => (nameOf(r.s("researcherA")), nameOf(r.s("researcherB"))) -> r)
      .toMap

  private def key(a: String, b: String): (String, String) =
    if rid(a) < rid(b) then (a, b) else (b, a)

  // ---- pair semantics -------------------------------------------------------------------------

  test("windowsFrame holds the configured windows plus all-time, with inclusive year bounds") {
    val rows =
      windows.collect().toList.map(r => (r.s("window"), r.i("windowStart"), r.i("windowEnd")))
    assertEquals(
      rows.sortBy(_._2),
      List(("1000-9999", 1000, 9999), ("2000-2004", 2000, 2004), ("2005-2009", 2005, 2009)),
    )
  }

  test("a 5-author paper gives n(n-1)/2 = 10 pair rows before consolidation; 23 in the fixture") {
    // by hand: W01 C(5,2)=10, W02 3, W03 3, W04 1, W05 1, W06 1, W07 1, W08 1, W09 0, W10 1, W11 1
    val perWork = mapped
      .groupBy("workId")
      .count()
      .collect()
      .toList
      .map(r => r.s("workId") -> r.l("count"))
      .toMap
    assertEquals(perWork(workId("W01")), 10L)
    assertEquals(perWork(workId("W02")), 3L)
    assertEquals(mapped.count(), 23L)
    assertEquals(inputs.pairs.count(), 23L)
  }

  test("a 1-author paper gives no pair and no edge, but its author is a node") {
    assert(!perWorkIds(mapped).contains(workId("W09")))
    val lu = rid("Lu")
    assertEquals(edges.where(s"researcherA = '$lu' or researcherB = '$lu'").count(), 0L)
    val nodes = Graph.nodes(authored, windows).where(s"researcherId = '$lu'").collect().toList
    assertEquals(nodes.map(_.s("window")).sorted, List("1000-9999", "2000-2004"))
  }

  private def perWorkIds(df: org.apache.spark.sql.DataFrame): Set[String] =
    df.select("workId").distinct().collect().toList.map(_.s("workId")).toSet

  test("pair order is canonical: researcherA < researcherB whatever the mention order") {
    val r = mapped.collect().toList
    assert(r.forall(x => x.s("researcherA") <= x.s("researcherB")))
    // Ed and Mo appear as (first, second) in both orders depending on mention-id order
    assertEquals(
      canon.where("researcherA >= researcherB").count(),
      0L,
    )
  }

  test("duplicate and reversed pair rows aggregate to one edge; two works make weight 2") {
    val (ra, rb) = if rid("Ada") < rid("Bob") then ("Ada", "Bob") else ("Bob", "Ada")
    val mrs      = TestSpark.frame(
      MrSchema,
      List(Row("m:a1", rid(ra)), Row("m:b1", rid(rb)), Row("m:a2", rid(ra)), Row("m:b2", rid(rb))),
    )
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(
        Row(workId("W01"), 2001, "m:a1", "m:b1"),
        Row(workId("W01"), 2001, "m:a1", "m:b1"), // exact duplicate
        Row(workId("W01"), 2001, "m:b1", "m:a1"), // reversed
        Row(workId("W10"), 2012, "m:a2", "m:b2"),
      ),
    )
    val c = Graph.canonicalPairs(Graph.mappedPairs(pairs, mrs))
    assertEquals(c.count(), 2L) // (A,B,W01) and (A,B,W10)
    val e = Graph.edges(c, windows, cfg).collect().toList.map(r => r.s("window") -> r).toMap
    assertEquals(e.keySet, Set("1000-9999", "2000-2004"))
    assertEquals(e("1000-9999").i("jointPublicationCount"), 2)
    assertEquals(e("1000-9999").i("firstCollaborationYear"), 2001)
    assertEquals(e("1000-9999").i("lastCollaborationYear"), 2012)
    assertEquals(e("2000-2004").i("jointPublicationCount"), 1)
  }

  test(
    "a pair that became a self-pair after a merge is kept by mappedPairs and dropped by canonicalPairs"
  ) {
    val merged = rid("Ada")
    val mrs    = TestSpark.frame(
      MrSchema,
      List(Row("m:1", merged), Row("m:2", merged), Row("m:3", rid("Bob"))),
    )
    val pairs = TestSpark.frame(
      InputSchemas.PairCandidates,
      List(
        Row(workId("W01"), 2001, "m:1", "m:2"), // both mentions are now the same researcher
        Row(workId("W01"), 2001, "m:1", "m:3"),
      ),
    )
    val m = Graph.mappedPairs(pairs, mrs)
    assertEquals(m.count(), 2L)
    assertEquals(m.where("researcherA = researcherB").count(), 1L)
    val c = Graph.canonicalPairs(m)
    assertEquals(c.count(), 1L)
    assertEquals(c.where("researcherA = researcherB").count(), 0L)
  }

  // ---- AUTHORED and teams ---------------------------------------------------------------------

  test("authored has one row per (researcher, work); a repeated mention counts in mentionCount") {
    val auth = TestSpark.frame(
      InputSchemas.Authorship,
      List(
        Row("m:1", workId("W01"), 3, 2001),
        Row("m:2", workId("W01"), 1, 2001), // same researcher again, smaller position
        Row("m:3", workId("W01"), 2, 2001),
      ),
    )
    val mrs = TestSpark.frame(
      MrSchema,
      List(Row("m:1", rid("Ada")), Row("m:2", rid("Ada")), Row("m:3", rid("Bob"))),
    )
    val a   = Graph.authored(auth, mrs)
    val ada = a.where(s"researcherId = '${rid("Ada")}'").collect().toList
    assertEquals(ada.size, 1)
    assertEquals(
      (ada.head.i("mentionCount"), ada.head.i("position"), ada.head.i("year")),
      (2, 1, 2001),
    )
    // team size = distinct researchers (2), not mentions (3)
    val t = Graph.workTeams(a).collect().toList
    assertEquals(
      t.map(r => (r.s("workId"), r.i("teamSize"), r.i("year"))),
      List((workId("W01"), 2, 2001)),
    )
  }

  test("nodes per window: a researcher is a node iff they authored a work in the window") {
    // by hand: 2000-2004 {Ada Bob Cy Di Ed Mo Jo Kit Lu}=9; 2005-2009 {Fay Gus Hal Ivy Mo Jo Kit}=7; all 13
    val byWindow = Graph
      .nodes(authored, windows)
      .collect()
      .toList
      .groupBy(_.s("window"))
      .view
      .mapValues(_.map(r => nameOf(r.s("researcherId"))).toSet)
      .toMap
    assertEquals(
      byWindow("2000-2004"),
      Set("Ada", "Bob", "Cy", "Di", "Ed", "Mo", "Jo", "Kit", "Lu"),
    )
    assertEquals(byWindow("2005-2009"), Set("Fay", "Gus", "Hal", "Ivy", "Mo", "Jo", "Kit"))
    assertEquals(byWindow("1000-9999"), Fixture.names.toSet)
  }

  // ---- edges ----------------------------------------------------------------------------------

  test("edge counts per window are 12, 8 and 19 (hand count)") {
    // 2000-2004: K5 (10) + Mo-Ed + Jo-Kit = 12; 2005-2009: K4 (6) + Mo-Fay + Jo-Kit = 8
    // all-time: K5 10 + K4 6 + Mo-Ed + Mo-Fay + Jo-Kit = 19 (Ada-Bob and Jo-Kit repeat but stay one edge)
    assertEquals(edgeRows("2000-2004").size, 12)
    assertEquals(edgeRows("2005-2009").size, 8)
    assertEquals(edgeRows("1000-9999").size, 19)
    assertEquals(edges.count(), 39L)
  }

  test("repeated collaborations: weights, first and last years and supporting works") {
    val all = edgeRows("1000-9999")
    val ab  = all(key("Ada", "Bob"))
    assertEquals(
      (
        ab.i("jointPublicationCount"),
        ab.i("firstCollaborationYear"),
        ab.i("lastCollaborationYear"),
      ),
      (2, 2001, 2012),
    )
    assertEquals(ab.strs("representativeWorkIds"), List(workId("W01"), workId("W10")))
    val gh = all(key("Gus", "Hal"))
    assertEquals(
      (
        gh.i("jointPublicationCount"),
        gh.i("firstCollaborationYear"),
        gh.i("lastCollaborationYear"),
      ),
      (2, 2006, 2007),
    )
    val jk = all(key("Jo", "Kit"))
    assertEquals(
      (
        jk.i("jointPublicationCount"),
        jk.i("firstCollaborationYear"),
        jk.i("lastCollaborationYear"),
      ),
      (3, 2002, 2006),
    )
    assertEquals(
      jk.strs("representativeWorkIds"),
      List(workId("W07"), workId("W08"), workId("W11")),
    )
    // per window: Jo-Kit is 2 works in the first window (2002, 2003) and 1 in the second (2006)
    assertEquals(edgeRows("2000-2004")(key("Jo", "Kit")).i("jointPublicationCount"), 2)
    assertEquals(edgeRows("2005-2009")(key("Jo", "Kit")).i("jointPublicationCount"), 1)
    // Ada-Bob: W10 (2012) is outside every explicit window
    assertEquals(edgeRows("2000-2004")(key("Ada", "Bob")).i("jointPublicationCount"), 1)
    assert(!edgeRows("2005-2009").contains(key("Ada", "Bob")))
  }

  test("window inclusion: 2004 belongs to 2000-2004 and 2005 to 2005-2009, never both") {
    assert(edgeRows("2000-2004").contains(key("Mo", "Ed")))
    assert(!edgeRows("2005-2009").contains(key("Mo", "Ed")))
    assert(edgeRows("2005-2009").contains(key("Mo", "Fay")))
    assert(!edgeRows("2000-2004").contains(key("Mo", "Fay")))
    assertEquals(edgeRows("2000-2004")(key("Mo", "Ed")).i("lastCollaborationYear"), 2004)
    assertEquals(edgeRows("2005-2009")(key("Mo", "Fay")).i("firstCollaborationYear"), 2005)
  }

  test("edge ids are the core coauthor edge ids of the window; policy version is recorded") {
    val e = edgeRows("2005-2009")(key("Mo", "Fay"))
    assertEquals(e.s("edgeId"), Fixture.edgeId("Mo", "Fay", GraphWindow(2005, 2009)))
    assertEquals(e.s("policyVersion"), "coauthor-graph.v1")
    assertEquals((e.i("windowStart"), e.i("windowEnd")), (2005, 2009))
    assertNotEquals(e.s("edgeId"), edgeRows("1000-9999")(key("Mo", "Fay")).s("edgeId"))
  }

  test("representative work ids are bounded and sorted; the digest points at the full list") {
    val small = TestSpark.config(cfg.checkpointDir, Map("representative-work-ids-bound" -> "2"))
    val e     = Graph.edges(canon, windows, small).collect().toList
    val jk    = e
      .find(r =>
        r.s("window") == "1000-9999" && Set(r.s("researcherA"), r.s("researcherB")) == Set(
          rid("Jo"),
          rid("Kit"),
        )
      )
      .get
    assertEquals(jk.strs("representativeWorkIds"), List(workId("W07"), workId("W08")))
    assert(jk.b("supportingWorksTruncated"))
    val full = List("W07", "W08", "W11").map(workId)
    assertEquals(
      jk.s("supportingWorksDigest"),
      Hashing.digest("scholarchain.coauthor-works.v1", full),
    )
    val ab = e
      .find(r =>
        r.s("window") == "1000-9999" && Set(r.s("researcherA"), r.s("researcherB")) == Set(
          rid("Ada"),
          rid("Bob"),
        )
      )
      .get
    assert(!ab.b("supportingWorksTruncated")) // exactly 2 works, bound 2
  }

  test("arcs hold both directions of every edge with the joint publication count as weight") {
    val arcs = Graph.arcs(edges).collect().toList
    assertEquals(arcs.size, 78)
    val ab = arcs.filter(r =>
      r.s("window") == "1000-9999" && Set(r.s("src"), r.s("dst")) == Set(rid("Ada"), rid("Bob"))
    )
    assertEquals(ab.size, 2)
    assert(ab.forall(_.i("weight") == 2))
    assertEquals(ab.map(_.s("src")).toSet, Set(rid("Ada"), rid("Bob")))
  }
