package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions as F
import org.apache.spark.sql.types.{IntegerType, LongType, StringType}

import scholarchain.core.*

/** Phase 06 repair 02 (R-V1): the `external-identities` vertex table, the three
  * `external_identities_*` invariants and the `edge_endpoints_exist_<table>` invariants (one
  * negative control per edge table and end), on the extended fixture. Expected values are written
  * out by hand (see `ExtFixture`: four distinct ORCIDs, one of them shared by Rob and Sam).
  */
class ExternalIdentitiesSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")

  import ExtFixture.{OrcA, OrcB, OrcC, OrcS}

  private def target(o: String): String = "researcher:orcid:" + o

  private def check(c: Computed): Map[String, Invariant] =
    Invariants.check(c, ExtRun.cfg, ExtRun.inputs).map(i => i.name -> i).toMap

  private lazy val baseline: Map[String, Invariant] = check(ExtRun.computed)

  private def replaced(c: Computed, table: String, df: DataFrame): Computed =
    c.copy(tables = c.tables.updated(table, df))

  private val EndpointInvariants: List[String] =
    EdgeLinks.all.map(l => s"edge_endpoints_exist_${l.table.replace('-', '_')}")

  // ---- the vertex table ---------------------------------------------------------------------

  test("externalIdentities: one row per distinct target, kind orcid, value matches the ID") {
    val rows = ExtRun.rows("external-identities")
    assertEquals(rows.size, 4)
    assertEquals(
      rows.map(r => (r.s("externalId"), r.s("kind"), r.s("value"))).toSet,
      Set(OrcA, OrcB, OrcC, OrcS).map(o => (target(o), "orcid", o)),
    )
    rows.foreach { r =>
      assertEquals(r.s("externalId"), "researcher:orcid:" + r.s("value"))
      assertEquals(Ids.researcherIdFromOrcid(r.s("value")), Right(r.s("externalId")))
      assertEquals(Ids.checkResearcherId(r.s("externalId")), Right(r.s("externalId")))
    }
    // every resolves-to target is a vertex, and every vertex is a target
    assertEquals(
      ExtRun.rows("resolves-to").map(_.s("targetId")).toSet,
      rows.map(_.s("externalId")).toSet,
    )
  }

  test("a shared ORCID gives ONE vertex and TWO edges (Rob and Sam share the same ORCID)") {
    val vertices = ExtRun.rows("external-identities").filter(_.s("value") == OrcS)
    val edges    = ExtRun.rows("resolves-to").filter(_.s("targetId") == target(OrcS))
    assertEquals(vertices.size, 1)
    assertEquals(edges.size, 2)
    assertEquals(
      edges.map(_.s("sourceId")).toSet,
      Set(Fixture.rid("Rob"), Fixture.rid("Sam")),
    )
    assertEquals(ExtRun.rows("resolves-to").size, 5) // five edges, four vertices
  }

  test("Enrichment.externalIdentities: distinct targets only, from the edges, in declared shape") {
    val e = TestSpark.frame(
      Tables.ResolvesTo.schema,
      List(
        Row(GraphEdgeTypes.ResolvesTo, "r1", target(OrcS), OrcS, Seq("m1"), "{}"),
        Row(GraphEdgeTypes.ResolvesTo, "r2", target(OrcS), OrcS, Seq("m2"), "{}"),
        Row(GraphEdgeTypes.ResolvesTo, "r3", target(OrcA), OrcA, Seq.empty[String], "{}"),
      ),
    )
    val out = Enrichment.externalIdentities(e)
    assertEquals(Export.schemaDifferences(out, Tables.ExternalIdentities), Nil)
    assertEquals(
      out.collect().toList.map(r => (r.s("externalId"), r.s("kind"), r.s("value"))).sorted,
      List((target(OrcA), "orcid", OrcA), (target(OrcS), "orcid", OrcS)).sorted,
    )
    assertEquals(Enrichment.externalIdentities(e.limit(0)).count(), 0L)
  }

  // ---- external_identities_* invariants -----------------------------------------------------

  test("the untampered extended fixture passes every endpoint and external-identity invariant") {
    val names = List(
      "external_identities_one_per_resolves_to_target",
      "external_identities_ids_unique",
      "external_identities_well_formed",
    ) ++ EndpointInvariants
    assertEquals(EndpointInvariants.size, 8)
    names.foreach(n => assert(baseline.get(n).exists(_.ok), s"$n: ${baseline.get(n)}"))
    assertEquals(baseline.values.filterNot(_.ok).map(_.name).toList, Nil)
  }

  test("external_identities_*: a MISSING row fails the count invariant and the resolves-to end") {
    val ei       = ExtRun.computed.tables("external-identities")
    val tampered = ei.where(F.col("value") =!= OrcA)
    val inv      = check(replaced(ExtRun.computed, "external-identities", tampered))
    assert(!inv("external_identities_one_per_resolves_to_target").ok, inv.toString)
    assert(!inv("edge_endpoints_exist_resolves_to").ok)
    assert(inv("external_identities_ids_unique").ok)
    assert(inv("external_identities_well_formed").ok)
  }

  test("external_identities_*: a DUPLICATED row fails the uniqueness invariant") {
    val ei       = ExtRun.computed.tables("external-identities")
    val tampered = ei.union(ei.where(F.col("value") === OrcS))
    val inv      = check(replaced(ExtRun.computed, "external-identities", tampered))
    assert(!inv("external_identities_ids_unique").ok, inv("external_identities_ids_unique").detail)
    assert(inv("external_identities_well_formed").ok)
    assert(inv("edge_endpoints_exist_resolves_to").ok) // nothing dangles, it is only duplicated
  }

  test("external_identities_*: MALFORMED rows (wrong kind, wrong value, bad ID) fail well-formed") {
    val ei                                  = ExtRun.computed.tables("external-identities")
    val variants: List[(String, DataFrame)] = List(
      "kind" -> ei
        .withColumn("kind", F.when(F.col("value") === OrcA, "doi").otherwise(F.col("kind"))),
      "value" -> ei
        .withColumn("value", F.when(F.col("value") === OrcA, OrcB).otherwise(F.col("value"))),
      "id" -> ei.withColumn(
        "externalId",
        F.when(F.col("value") === OrcA, "researcher:orcid:not-an-orcid")
          .otherwise(F.col("externalId")),
      ),
    )
    variants.foreach { (what, df) =>
      val inv = check(replaced(ExtRun.computed, "external-identities", df))
      assert(!inv("external_identities_well_formed").ok, s"malformed $what was not caught")
    }
  }

  // ---- edge_endpoints_exist_<table>: one negative control per edge table and end -------------

  /** A bogus ID that is no vertex of any table. */
  private val Bogus = "no-such-vertex"

  /** A row of `df` (a copy of its first row, or a synthetic one when `df` is empty) with `column`
    * set to `value`, appended to `df`.
    */
  private def withDanglingRow(df: DataFrame, column: String, value: String): DataFrame =
    val template =
      if df.isEmpty then
        TestSpark.frame(
          df.schema,
          List(
            Row.fromSeq(df.schema.fields.toList.map { f =>
              f.name match
                case "window"      => Fixture.W1
                case "windowStart" => 2000
                case "windowEnd"   => 2004
                case _             =>
                  f.dataType match
                    case StringType  => "x"
                    case IntegerType => 0
                    case LongType    => 0L
                    case other       => fail(s"no synthetic value for ${other.catalogString}")
            })
          ),
        )
      else df.limit(1)
    df.union(template.withColumn(column, F.lit(value)).select(df.columns.map(F.col)*))

  private def endpointFailures(inv: Map[String, Invariant]): Set[String] =
    EndpointInvariants.filterNot(n => inv(n).ok).toSet

  EdgeLinks.all.foreach { l =>
    List(("from", l.from), ("to", l.to)).foreach { (side, end) =>
      test(
        s"negative control: a dangling ${l.table}.${end.column} ($side) fails exactly edge_endpoints_exist_${l.table.replace('-', '_')}"
      ) {
        val c        = ExtRun.computed
        val tampered = withDanglingRow(c.tables(l.table), end.column, Bogus)
        val inv      = check(replaced(c, l.table, tampered))
        val name     = s"edge_endpoints_exist_${l.table.replace('-', '_')}"
        assertEquals(endpointFailures(inv), Set(name), inv(name).detail)
        assert(
          inv(name).detail.contains(s"rows whose ${end.column} is not in ${end.vertexTable} = 1"),
          inv(name).detail,
        )
      }
    }
  }

  test("negative control: a dangling resolves-to TARGET names the external-identities table") {
    val c        = ExtRun.computed
    val tampered =
      withDanglingRow(c.tables("resolves-to"), "targetId", target("0000-0000-0000-0000"))
    val inv = check(replaced(c, "resolves-to", tampered))
    val d   = inv("edge_endpoints_exist_resolves_to")
    assert(!d.ok, d.detail)
    assert(d.detail.contains("external-identities"), d.detail)
    assert(d.detail.contains("targetId"), d.detail)
  }

  /** An ID that is a vertex of `vertexTable` in some OTHER window than `window`, not in `window`.
    */
  private def idOnlyInAnotherWindow(vertexTable: String, idColumn: String, window: String): String =
    val rows = ExtRun.rows(vertexTable)
    val here = rows.filter(_.s("window") == window).map(_.s(idColumn)).toSet
    rows
      .filter(_.s("window") != window)
      .map(_.s(idColumn))
      .find(!here.contains(_))
      .getOrElse(fail(s"every $vertexTable ID of other windows also exists in window $window"))

  test(
    "windowed vertices: an ID that exists only in ANOTHER window is dangling (match on window)"
  ) {
    val c = ExtRun.computed
    List(
      ("community-membership", "communityId", "communities", "communityId"),
      ("component-membership", "componentId", "components", "componentId"),
      ("community-cross-edges", "communityA", "communities", "communityId"),
      ("community-cross-edges", "communityB", "communities", "communityId"),
    ).foreach { (edgeTable, col, vertexTable, idCol) =>
      val w       = Fixture.W1
      val foreign = idOnlyInAnotherWindow(vertexTable, idCol, w)
      val edge    = c.tables(edgeTable)
      // a copy of a W1 row of the edge table (or a synthetic W1 row) pointing at the foreign ID
      val inW1     = edge.where(F.col("window") === w)
      val tampered = edge.union(withDanglingRow(inW1, col, foreign).except(inW1))
      val name     = s"edge_endpoints_exist_${edgeTable.replace('-', '_')}"
      val inv      = check(replaced(c, edgeTable, tampered))
      assert(!inv(name).ok, s"$edgeTable.$col -> $foreign (another window) was not caught")
      assertEquals(endpointFailures(inv), Set(name))
      // control: the SAME ID in its own window is not dangling (ID exists as a vertex there)
      assert(
        ExtRun.rows(vertexTable).exists(r => r.s("window") != w && r.s(idCol) == foreign),
        "the foreign ID must exist in another window",
      )
    }
  }

  // ---- EdgeLinks agrees with the Neptune manifest -------------------------------------------

  test(
    "EdgeLinks agrees with the Neptune manifest: fromColumn, toColumn and the vertex id columns"
  ) {
    val manifest = LoadManifests.build(FixtureExports.a._2, FixtureExports.run)("neptune")
    val edges    = manifest("edges").arr.map(_.obj).toList.map(e => e("table").str -> e).toMap
    val vertices = manifest("vertices").arr.map(_.obj).toList.map(v => v("table").str -> v).toMap
    assertEquals(EdgeLinks.all.map(_.table).toSet, edges.keySet)
    assertEquals(EdgeLinks.all.size, edges.size)
    EdgeLinks.all.foreach { l =>
      val e = edges(l.table)
      assertEquals(l.from.column, e("fromColumn").str, l.table)
      assertEquals(l.to.column, e("toColumn").str, l.table)
      assertEquals(l.from.edgeTable, l.table)
      assertEquals(l.to.edgeTable, l.table)
      List(l.from, l.to).foreach { end =>
        val v =
          vertices.getOrElse(end.vertexTable, fail(s"${end.vertexTable} is no Neptune vertex set"))
        assertEquals(end.vertexIdColumn, v("idColumn").str, end.vertexTable)
        assertEquals(end.windowedVertex, v("windowed").bool, end.vertexTable)
        // the edge column and the vertex id column are declared columns of their tables
        assert(Tables.byName(l.table).get.columns.contains(end.column), s"${l.table}.${end.column}")
        assert(
          Tables.byName(end.vertexTable).get.columns.contains(end.vertexIdColumn),
          s"${end.vertexTable}.${end.vertexIdColumn}",
        )
      }
    }
  }

  test("the Neptune manifest lists the ExternalIdentity vertex set (id externalId, not windowed)") {
    val manifest = LoadManifests.build(FixtureExports.a._2, FixtureExports.run)("neptune")
    val v = manifest("vertices").arr.map(_.obj).find(_("table").str == "external-identities").get
    assertEquals(v("label").str, "ExternalIdentity")
    assertEquals(v("idColumn").str, "externalId")
    assertEquals(v("windowed").bool, false)
    assertEquals(v("propertyColumns").arr.map(_.str).toList, List("kind", "value"))
  }
end ExternalIdentitiesSuite
