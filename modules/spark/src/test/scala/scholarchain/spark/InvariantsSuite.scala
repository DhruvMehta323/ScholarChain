package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions as F

/** Output invariants: all hold on the fixture, and each one fails when its property is broken. */
class InvariantsSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  private def check(
      c: Computed,
      in: Inputs.LoadedInputs = FixtureRun.inputs,
  ): Map[String, Invariant] =
    Invariants.check(c, FixtureRun.cfg, in).map(i => i.name -> i).toMap

  private lazy val baseline = check(FixtureRun.computed)

  private def tampered(table: String)(
      f: org.apache.spark.sql.DataFrame => org.apache.spark.sql.DataFrame
  ): Computed =
    val c = FixtureRun.computed
    c.copy(tables = c.tables.updated(table, f(c.tables(table))))

  test("every invariant holds on the fixture (and there are at least 25)") {
    assert(baseline.size >= 25, baseline.keySet.toString)
    val failing = baseline.values.filterNot(_.ok).map(i => s"${i.name}: ${i.detail}").toList
    assertEquals(failing, Nil)
  }

  test(
    "informational observations of the fixture: 23 pairs, no self-pair, 23 distinct (pair, work) rows, no rejections ignored, no ORCIDs"
  ) {
    assertEquals(
      Invariants
        .observations(FixtureRun.computed)
        .toMap
        .view
        .filterKeys(_ != "worksWhereCanonicalTeamSizeDiffersFromHadoopTeams")
        .toMap,
      Map(
        "mappedPairs"                    -> 23L,
        "selfPairsDroppedAfterMerge"     -> 0L,
        "withinBaseRejectionsIgnored"    -> 0L,
        "orcidsSharedByMultipleClusters" -> 0L,
        "clustersWithTwoOrMoreOrcids"    -> 0L,
        "distinctPairWorkRows"           -> 23L,
      ),
    )
    // the fixture teams table is written from the same authors, so no work differs
    assertEquals(FixtureRun.computed.teamSizeMismatches, 0L)
  }

  test("coreness_covers_nodes fails when a coreness row is missing") {
    val r = check(tampered("coreness")(_.limit(5)))
    assert(!r("coreness_covers_nodes").ok, r("coreness_covers_nodes").toString)
  }

  test("edges_canonical_order_no_self_loop fails when an edge has A >= B") {
    val r = check(
      tampered("coauthor-edges")(df =>
        df.union(df.limit(1).withColumn("researcherB", F.col("researcherA")))
      )
    )
    assert(!r("edges_canonical_order_no_self_loop").ok)
  }

  test("degree_sum_is_twice_edges fails when an edge is removed from the edge table only") {
    val r = check(tampered("coauthor-edges")(_.limit(30)))
    assert(!r("degree_sum_is_twice_edges").ok)
    assert(!r("weighted_degree_sum_is_twice_joint").ok)
  }

  test("researcher_ids_unique and clusters_cover_all_mentions fail on a duplicated cluster") {
    val r = check(tampered("identity-clusters")(df => df.union(df.limit(1))))
    assert(!r("researcher_ids_unique").ok)
    assert(!r("clusters_cover_all_mentions").ok)
  }

  test("researcher_ids_valid fails on a malformed researcher id") {
    val r =
      check(tampered("identity-clusters")(_.withColumn("researcherId", F.lit("researcher:nope"))))
    assert(!r("researcher_ids_valid").ok)
  }

  test("bridges_three_measures_per_node fails when a bridge row is dropped") {
    val r = check(tampered("bridges")(df => df.where(F.col("measure") =!= "distinct_communities")))
    assert(!r("bridges_three_measures_per_node").ok)
  }

  test("paths_valid fails on an inconsistent path") {
    val r = check(tampered("paths")(_.withColumn("length", F.col("length") + 1)))
    assert(!r("paths_valid").ok)
  }

  private def withDecisions(rows: List[Row]): Inputs.LoadedInputs =
    FixtureRun.inputs.copy(decisions =
      FixtureRun.inputs.decisions.union(TestSpark.frame(InputSchemas.Decisions, rows))
    )

  private def fixtureMention(w: String, pos: Int): String =
    Fixture.mention(Fixture.works.find(x => x.key == w).get, pos)

  test(
    "M5 invariant ignores a REJECTED or CONFLICT decision inside one base identity (repair M2)"
  ) {
    // Ada has two mentions (W01 position 0 and W10 position 0), Bob two (position 1 of the same
    // works): a rejection between two mentions of one person is ignored, never a cannot-link pair
    val dec = withDecisions(
      List(
        Row(
          fixtureMention("W01", 0),
          fixtureMention("W10", 0),
          "researcher",
          "rejected",
          Fixture.Policy,
        ),
        Row(
          fixtureMention("W01", 1),
          fixtureMention("W10", 1),
          "researcher",
          "conflict",
          Fixture.Policy,
        ),
      )
    )
    val r = check(FixtureRun.computed, dec)
    assert(
      r("m5_no_rejected_or_conflict_inside_a_cluster").ok,
      r("m5_no_rejected_or_conflict_inside_a_cluster").toString,
    )
    // the person is not split: one researcher for both mentions of Ada
    val mr = FixtureRun.computed.tables("mention-researcher")
    assertEquals(
      mr.where(F.col("mentionId").isin(fixtureMention("W01", 0), fixtureMention("W10", 0)))
        .select("researcherId")
        .distinct()
        .count(),
      1L,
    )
    assert(baseline("m5_no_rejected_or_conflict_inside_a_cluster").ok)
  }

  test(
    "M5 invariant still fails on a real violation: a REJECTED pair of two base identities in one researcher"
  ) {
    // negative control: Ada (W01 pos 0) and Bob (W01 pos 1) are different base identities.
    // Rejected between them is a real cannot-link pair; if the output puts them in one researcher
    // (tampered here), the invariant must fail.
    val dec = withDecisions(
      List(
        Row(
          fixtureMention("W01", 0),
          fixtureMention("W01", 1),
          "researcher",
          "rejected",
          Fixture.Policy,
        )
      )
    )
    // untampered output: Ada and Bob are different researchers, the invariant holds
    assert(check(FixtureRun.computed, dec)("m5_no_rejected_or_conflict_inside_a_cluster").ok)
    // tampered output: Bob's mentions are mapped to Ada's researcher ID
    val tamperedC = tampered("mention-researcher")(df =>
      df.withColumn(
        "researcherId",
        F.when(F.col("baseIdentityId") === Fixture.rid("Bob"), Fixture.rid("Ada"))
          .otherwise(F.col("researcherId")),
      )
    )
    val r = check(tamperedC, dec)
    assert(
      !r("m5_no_rejected_or_conflict_inside_a_cluster").ok,
      r("m5_no_rejected_or_conflict_inside_a_cluster").toString,
    )
    // same for a CONFLICT decision
    val conflict = withDecisions(
      List(
        Row(
          fixtureMention("W01", 0),
          fixtureMention("W01", 1),
          "researcher",
          "conflict",
          Fixture.Policy,
        )
      )
    )
    assert(!check(tamperedC, conflict)("m5_no_rejected_or_conflict_inside_a_cluster").ok)
  }

  test(
    "decision_endpoints_in_entity_views fails for a decision endpoint that is not an entity view"
  ) {
    val ghost =
      Row(Fixture.workId("W02"), Fixture.workId("W03"), "work", "rejected", Fixture.Policy)
    val dec =
      FixtureRun.inputs.decisions.union(TestSpark.frame(InputSchemas.Decisions, List(ghost)))
    val r = check(FixtureRun.computed, FixtureRun.inputs.copy(decisions = dec))
    assert(!r("decision_endpoints_in_entity_views").ok)
  }

  test("components_and_kcore_converged fails when an algorithm hit its cap") {
    val c     = FixtureRun.computed
    val stuck = c.convergences.map(v =>
      if v.algorithm == "k_core_peeling" then v.copy(converged = false) else v
    )
    val r = check(c.copy(convergences = stuck))
    assert(!r("components_and_kcore_converged").ok)
    assert(r("components_and_kcore_converged").detail.contains("k_core_peeling"))
  }

  test("authorship_mentions_all_mapped fails when authorship has a row without a mapped mention") {
    val in = FixtureRun.inputs
    val r  = check(
      FixtureRun.computed,
      in.copy(rowCounts = in.rowCounts.updated("authorship", in.rowCounts("authorship") + 1)),
    )
    assert(!r("authorship_mentions_all_mapped").ok)
  }
