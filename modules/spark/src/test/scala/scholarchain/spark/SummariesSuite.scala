package scholarchain.spark

import org.apache.spark.sql.Row

import scholarchain.core.*

/** Semantic summaries: template text over computed tables only, no invented numbers. */
class SummariesSuite extends munit.FunSuite:

  import Fixture.{rid, nameOf, workId, All}
  import FixtureRun.rows

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val summaries                    = () => rows("semantic-summaries")
  private def ofType(t: String): List[Row] = summaries().filter(_.s("documentType") == t)

  /** Identifiers are removed before numbers are extracted (they embed digits). */
  private def stripIds(text: String): String =
    text
      .replaceAll(
        """\b(researcher|community|component|work|topic|path|venue|institution|edge):\S+""",
        " ",
      )
      .replaceAll("""\b\d{4}-\d{4}\b""", " ")

  private def numbersIn(text: String): List[BigDecimal] =
    """-?\d+(\.\d+)?""".r.findAllIn(stripIds(text)).toList.map(BigDecimal(_))

  /** Every numeric value of the metric tables the summaries are rendered from. */
  private lazy val allowed: List[Double] =
    List(
      "researcher-metrics",
      "window-summary",
      "coreness",
      "components",
      "communities",
      "bridges",
      "paths",
    ).flatMap { t =>
      val df      = FixtureRun.computed.tables(t)
      val numeric = df.schema.fields
        .filter(f => Set("int", "bigint", "double").contains(f.dataType.catalogString))
        .map(_.name)
      rows(t).flatMap(r =>
        numeric
          .filterNot(_.startsWith("window"))
          .flatMap(c =>
            if r.isNull(c) then None else Some(r.get(r.fieldIndex(c)).toString.toDouble)
          )
      )
    }.distinct

  /** A number is supported when it equals an integer value, or a decimal within the 4-digit
    * rounding.
    */
  private def supported(n: BigDecimal): Boolean =
    if n.isWhole then allowed.exists(a => a == n.toDouble)
    else allowed.exists(a => math.abs(a - n.toDouble) <= 5.0e-5)

  private def unsupported(text: String): List[BigDecimal] = numbersIn(text).filterNot(supported)

  // ---- structure ------------------------------------------------------------------------------

  test("one researcher profile per (window, researcher), one summary per path and topic") {
    // nodes: 9 + 7 + 13 = 29 researcher profiles; 10 paths; 2 topics (all-time)
    assertEquals(ofType("researcher_profile").size, 29)
    assertEquals(ofType("path_summary").size, 10)
    assertEquals(ofType("topic_summary").size, 2)
    assertEquals(ofType("community_profile").size, rows("communities").size)
    assertEquals(summaries().size, 29 + 10 + 2 + rows("communities").size)
    assertEquals(
      summaries().map(r => (r.s("entityId"), r.s("window"), r.s("documentType"))).distinct.size,
      summaries().size,
    )
  }

  test("every summary has a matching text hash, the run id and a valid SemanticSummary record") {
    val prov = Provenance(
      1,
      SourceType.Dblp,
      List(InputRef("x", "1", Some("a" * 64))),
      "0.1.0",
      Map.empty,
      None,
      None,
    )
    summaries().foreach { r =>
      assertEquals(r.s("textHash"), Hashing.textHash(r.s("text")))
      assertEquals(r.s("runId"), FixtureRun.runId)
      val Array(a, b) = r.s("window").split("-")
      val rec         = SemanticSummary(
        1,
        r.s("entityId"),
        r.s("documentType"),
        r.s("textHash"),
        r.s("text"),
        GraphWindow(a.toInt, b.toInt),
        r.s("runId"),
        prov,
      )
      assertEquals(summon[Contract[SemanticSummary]].validate(rec), Right(rec), r.s("entityId"))
    }
  }

  // ---- no invented numbers --------------------------------------------------------------------

  test("every number in a researcher, community or path summary is a value of a metric table") {
    val texts =
      (ofType("researcher_profile") ++ ofType("community_profile") ++ ofType("path_summary"))
        .map(_.s("text"))
    assert(texts.nonEmpty && allowed.nonEmpty)
    texts.foreach(t => assertEquals(unsupported(t), Nil, t))
  }

  test("the number check is not vacuous: an invented number is flagged, real ones are not") {
    assertEquals(
      unsupported("Researcher X has 12345 works and a 0.9999 score"),
      List(BigDecimal(12345), BigDecimal("0.9999")),
    )
    assertEquals(unsupported("3 works and average team size 3.5 and 2.3333"), Nil)
    // identifiers embed digits but are not numbers
    assertEquals(unsupported(s"Researcher Ada (${rid("Ada")}) in window 2000-2004: 2 works"), Nil)
  }

  test("a researcher profile is the exact template over the tables (Lu, all-time)") {
    val lu   = rid("Lu")
    val text = ofType("researcher_profile")
      .find(r => r.s("entityId") == lu && r.s("window") == All)
      .get
      .s("text")
    assertEquals(
      text,
      s"Researcher Lu ($lu) in window 1000-9999: 1 works, 0 distinct coauthors, average team size 1.0, " +
        s"maximum team size 1 (work ${workId("W09")}), weighted degree 0, coreness 0. Component of 1 researchers. " +
        s"Community community:1000-9999:$lu of 1 researchers; cross-community degree 0, participation coefficient 0.0.",
    )
  }

  test("a researcher profile quotes the metric tables (Ed, all-time)") {
    val ed   = rid("Ed")
    val text = ofType("researcher_profile")
      .find(r => r.s("entityId") == ed && r.s("window") == All)
      .get
      .s("text")
    // P 2, C 5, A 3.5, M 5 on W01, W 5, coreness 4, component of 10
    assert(
      text.startsWith(
        s"Researcher Ed ($ed) in window 1000-9999: 2 works, 5 distinct coauthors, average team size 3.5, " +
          s"maximum team size 5 (work ${workId("W01")}), weighted degree 5, coreness 4. Component of 10 researchers. Community "
      ),
      text,
    )
  }

  test("community profiles quote size, edge counts and the most connected member") {
    val metrics = rows("researcher-metrics")
      .map(r => (r.s("window"), r.s("researcherId")) -> r.l("coauthorCount"))
      .toMap
    val members = rows("community-membership").groupBy(r => (r.s("window"), r.s("communityId")))
    rows("communities").foreach { c =>
      val key = (c.s("window"), c.s("communityId"))
      // most connected member: most distinct coauthors, ties to the smallest researcher id
      val top =
        members(key).map(_.s("researcherId")).sortBy(id => (-metrics((c.s("window"), id)), id)).head
      val expected =
        s"Community ${c.s("communityId")} in window ${c.s("window")} has ${c.l("size")} researchers, " +
          s"${c.l("internalEdgeCount")} internal edges and ${c.l("crossEdgeCount")} cross-community edges. " +
          s"Most connected member: ${nameOf(top)} (${metrics((c.s("window"), top))} distinct coauthors)."
      val got = ofType("community_profile")
        .find(r => r.s("entityId") == c.s("communityId") && r.s("window") == c.s("window"))
        .get
      assertEquals(got.s("text"), expected)
    }
  }

  test("path summaries name every hop in order and the supporting works of each hop") {
    rows("paths").foreach { p =>
      val id    = s"path:${p.s("window")}:${p.s("startId")}:${p.s("endId")}"
      val text  = ofType("path_summary").find(_.s("entityId") == id).get.s("text")
      val route = p.strs("hops").map(nameOf).mkString(" -> ")
      assert(text.contains(s"has length ${p.i("length")}: $route."), text)
      assert(
        text.startsWith(
          s"Shortest collaboration path in window ${p.s("window")} from ${nameOf(p.s("startId"))} to ${nameOf(p.s("endId"))} "
        ),
        text,
      )
      val works = p.strss("supportingWorkIds").map(_.mkString(",")).mkString("; ")
      assert(text.endsWith(s"Supporting works per hop: $works."), text)
    }
  }

  test("topic summaries are rendered from the works of the topic, in the all-time window") {
    // graph-mining: W01 (5 authors, 2001) and W02 (3 authors, 2006) -> 8 distinct researchers
    // databases: only W01 (through the accepted work decision) -> 5 researchers, 2001 to 2001
    val t = ofType("topic_summary").map(r => r.s("entityId") -> r).toMap
    assertEquals(t.keySet, Set(Fixture.topicGraph, Fixture.topicDatabase))
    assertEquals(
      t(Fixture.topicGraph).s("text"),
      "Topic Graph Mining (topic:graph-mining) is linked to 2 works by 8 distinct researchers, published from 2001 to 2006.",
    )
    assertEquals(
      t(Fixture.topicDatabase).s("text"),
      "Topic Databases (topic:databases) is linked to 1 works by 5 distinct researchers, published from 2001 to 2001.",
    )
    assert(
      t.values.forall(r =>
        r.s("window") == All && r.i("windowStart") == 1000 && r.i("windowEnd") == 9999
      )
    )
  }
