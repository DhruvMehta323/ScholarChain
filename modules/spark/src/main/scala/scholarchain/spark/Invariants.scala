package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions as F

import scholarchain.core.{BridgeMeasure, SparkConfig}

/** One checked output invariant: its name, whether it held, and the observed numbers. */
final case class Invariant(name: String, ok: Boolean, detail: String)

/** Output invariants, checked on every run (counts only, nothing is collected). A failing invariant
  * fails the run (exit 1). All of them are recomputed from the output tables, not from the
  * intermediate state that produced them.
  */
object Invariants:
  private def n(df: DataFrame): Long = DriverCollect.count(df)

  private def zero(name: String, observed: Long, what: String): Invariant =
    Invariant(name, observed == 0L, s"$what = $observed (expected 0)")

  private def equal(name: String, a: Long, b: Long, what: String): Invariant =
    Invariant(name, a == b, s"$what: $a vs $b")

  def check(
      c: Computed,
      cfg: SparkConfig,
      inputs: Inputs.LoadedInputs,
  ): List[Invariant] =
    val t        = c.tables
    val authored = t(Tables.Authored.name)
    val resolves = t(Tables.ResolvesTo.name)
    val works    = t(Tables.Works.name)
    val clusters = t(Tables.IdentityClusters.name)
    val mr       = t(Tables.MentionResearcher.name)
    val edges    = t(Tables.CoauthorEdges.name)
    val metrics  = t(Tables.ResearcherMetrics.name)
    val compMem  = t(Tables.ComponentMembership.name)
    val commMem  = t(Tables.CommunityMembership.name)
    val core     = t(Tables.Coreness.name)
    val paths    = t(Tables.Paths.name)
    val bridges  = t(Tables.Bridges.name)
    val nodes    = metrics.select(Cols.Window, "researcherId")

    val nodeCount      = n(nodes)
    val edgeCount      = n(edges)
    val authorshipRows = inputs.rowCounts("authorship")

    val mentionsInClusters = DriverCollect.longValue(
      clusters.agg(F.sum("memberCount").cast("long"))
    )
    val dangling = n(
      authored
        .select("researcherId")
        .distinct()
        .join(clusters.select("researcherId"), Seq("researcherId"), "left_anti")
    )
    val danglingWorks = n(
      authored
        .select("workId")
        .distinct()
        .join(works.select("workId"), Seq("workId"), "left_anti")
    )
    val endpointsA = n(
      edges
        .select(F.col(Cols.Window), F.col("researcherA").as("researcherId"))
        .join(nodes, Seq(Cols.Window, "researcherId"), "left_anti")
    )
    val endpointsB = n(
      edges
        .select(F.col(Cols.Window), F.col("researcherB").as("researcherId"))
        .join(nodes, Seq(Cols.Window, "researcherId"), "left_anti")
    )
    val sumCoauthors = DriverCollect.longValue(metrics.agg(F.sum("coauthorCount").cast("long")))
    val sumWeighted  = DriverCollect.longValue(metrics.agg(F.sum("weightedDegree").cast("long")))
    val sumJoint = DriverCollect.longValue(edges.agg(F.sum("jointPublicationCount").cast("long")))
    val allTimeJoint = DriverCollect.rows(
      edges
        .where(F.col(Cols.Window) === GraphWindowLabels.allTime)
        .agg(F.sum("jointPublicationCount").cast("long")),
      1,
    )
    val allTimeJointSum =
      allTimeJoint.headOption.flatMap(r => if r.isNullAt(0) then None else Some(r.getLong(0)))
    val maxCoreness = DriverCollect.rows(core.agg(F.max("coreness")), 1)
    val maxDeg      = DriverCollect.rows(metrics.agg(F.max("coauthorCount")), 1)
    // M5, recomputed from the output. Pairs inside ONE base identity are excluded (repair M2: rule
    // 4.1.1, a base identity is never split; such decisions are ignored, counted and listed).
    val cannotMixed = n(
      inputs.decisions
        .where(
          F.col("entityKind") === "researcher" && F.col("outcome").isin("rejected", "conflict")
        )
        .join(
          mr.select(
            F.col("mentionId").as("lid"),
            F.col("researcherId").as("lr"),
            F.col("baseIdentityId").as("lb"),
          ),
          F.col("leftId") === F.col("lid"),
        )
        .join(
          mr.select(
            F.col("mentionId").as("rid"),
            F.col("researcherId").as("rr"),
            F.col("baseIdentityId").as("rb"),
          ),
          F.col("rightId") === F.col("rid"),
        )
        .where(F.col("lr") === F.col("rr") && F.col("lb") =!= F.col("rb"))
    )
    val endpoints = inputs.decisions
      .select(F.col("leftId").as("id"))
      .union(inputs.decisions.select(F.col("rightId").as("id")))
      .distinct()
    val notInViews = n(endpoints.join(inputs.entityViews.select("id"), Seq("id"), "left_anti"))
    val validIds   = n(
      clusters.where(!F.call_udf(Udfs.ValidResearcher, F.col("researcherId")))
    )
    val validEdgeIds = n(edges.where(!F.call_udf(Udfs.ValidEdge, F.col("edgeId"))))
    val badPaths     = n(
      paths.where(
        F.size(F.col("hops")) =!= F.col("length") + 1 ||
          F.size(F.array_distinct(F.col("hops"))) =!= F.size(F.col("hops")) ||
          F.size(F.col("edgeIds")) =!= F.col("length") ||
          F.size(F.col("supportingWorkIds")) =!= F.col("length") ||
          F.col("length") > cfg.bfsMaxDepth ||
          F.col("length") < 1 ||
          F.element_at(F.col("hops"), 1) =!= F.col("startId") ||
          F.element_at(F.col("hops"), -1) =!= F.col("endId")
      )
    )
    val canonicalMismatch = n(
      clusters.where(
        F.size(F.col("dblpPersonKeys")) > 0 &&
          F.col("researcherId") =!= F.call_udf(Udfs.ClusterV2, F.col("dblpPersonKeys"))
      )
    )
    val externalIdMismatch = n(
      clusters.where(
        F.size(F.col("dblpPersonKeys")) === 0 && F.size(F.col("baseIdentityIds")) === 1 &&
          F.col("researcherId") =!= F.element_at(F.col("baseIdentityIds"), 1)
      )
    )
    val orcidCanonical = n(clusters.where(F.col("researcherId").startsWith("researcher:orcid:")))
    // M1 (repair 02, all names): recomputed from the inputs and the output mapping. Every DBLP name
    // of a base identity (mention names, base name, alias names) is attached to the researcher that
    // holds the base identity; a researcher holding two DIFFERENT names with one stem that belong to
    // DIFFERENT base identities is a violation.
    val baseToResearcher = mr
      .where(F.col("sourceType") === "dblp")
      .select(F.col("baseIdentityId").as("baseId"), F.col("researcherId"))
      .distinct()
    val homonymClusters = n(
      Identity.homonymGroups(
        Identity
          .dblpNames(inputs.mentions, inputs.aliases)
          .join(baseToResearcher, "baseId")
          .select(F.col("researcherId"), F.col("baseId"), F.col("name")),
        "researcherId",
      )
    )

    // R-V1: no dangling endpoint in ANY edge table (distributed anti-joins, counts only).
    val dangling2 = EdgeLinks.all.map { l =>
      val (fromRows, toRows) = (n(danglingRows(t, l.from)), n(danglingRows(t, l.to)))
      (l, fromRows, toRows)
    }
    val externalIds       = t(Tables.ExternalIdentities.name)
    val resolvesTargets   = n(resolves.select("targetId").distinct())
    val externalRows      = n(externalIds)
    val externalDistinct  = n(externalIds.select("externalId").distinct())
    val externalMalformed = n(
      externalIds.where(
        F.col("kind") =!= "orcid" ||
          F.col("externalId") =!= F.concat(F.lit("researcher:orcid:"), F.col("value")) ||
          !F.call_udf(Udfs.ValidResearcher, F.col("externalId"))
      )
    )

    val invalidEdges                  = V2Export.invalidRows(edges, Tables.CoauthorEdges)
    val invalidMetrics                = V2Export.invalidRows(metrics, Tables.ResearcherMetrics)
    def sample(df: DataFrame): String =
      DriverCollect.rows(df.limit(3), 3).map(_.getString(0)).mkString(" | ")
    val invalidEdgeCount   = n(invalidEdges)
    val invalidMetricCount = n(invalidMetrics)

    val edgeWorks        = t(Tables.CoauthorEdgeWorks.name)
    val edgeWorkRows     = n(edgeWorks)
    val edgeWorkDistinct = n(edgeWorks.select("edgeId", "workId").distinct())
    val perEdge          = edgeWorks
      .groupBy("edgeId")
      .agg(
        F.count(F.lit(1)).as("workRows"),
        F.call_udf(Udfs.WorksDigest, F.sort_array(F.collect_list("workId"))).as("digest"),
      )
    val edgeEvidenceMismatch = n(
      edges
        .select("edgeId", "jointPublicationCount", "supportingWorksDigest")
        .join(perEdge, Seq("edgeId"), "left")
        .where(
          F.col("workRows").isNull || F.col("workRows") =!= F.col("jointPublicationCount") ||
            F.col("digest") =!= F.col("supportingWorksDigest")
        )
    )
    val metricNulls = n(
      metrics.where(
        F.col("communityId").isNull || F.col("componentId").isNull ||
          F.col("crossCommunityDegree").isNull || F.col("participationCoefficient").isNull ||
          F.col("communitiesTouched").isNull
      )
    )

    val researchersT = t(Tables.Researchers.name)
    val orcidCells   = DriverCollect.rows(
      researchersT.agg(F.sum(F.size(F.col("orcids"))).cast("long")),
      1,
    )
    val expectedResolves = orcidCells.headOption
      .flatMap(r => if r.isNullAt(0) then None else Some(r.getLong(0)))
      .getOrElse(0L)
    val resolvesDangling = n(
      resolves
        .select("sourceId")
        .distinct()
        .join(
          researchersT.select(F.col("researcherId").as("sourceId")),
          Seq("sourceId"),
          "left_anti",
        )
    )
    val resolvesBadTarget = n(resolves.where(!F.call_udf(Udfs.ValidResearcher, F.col("targetId"))))
    val bridgeRows        = n(bridges)
    val converged         = c.convergences.filter(x =>
      x.algorithm.startsWith("connected") || x.algorithm.startsWith("identity") || x.algorithm
        .startsWith("k_core")
    )
    val unconverged = converged.filter(!_.converged).map(_.algorithm)

    List(
      zero(
        "authorship_mentions_all_mapped",
        authorshipRows - n(mr.where(F.col("sourceType") === "dblp")),
        "DBLP authorship rows without a mapped mention",
      ),
      equal(
        "clusters_cover_all_mentions",
        mentionsInClusters,
        inputs.rowCounts("author-mentions"),
        "mentions in clusters vs author-mentions rows",
      ),
      equal(
        "researcher_ids_unique",
        n(clusters.select("researcherId").distinct()),
        n(clusters),
        "distinct researcherId vs clusters",
      ),
      zero("researcher_ids_valid", validIds, "clusters with an invalid researcher ID"),
      zero("authored_researchers_exist", dangling, "AUTHORED rows pointing at no researcher"),
      zero("authored_works_exist", danglingWorks, "AUTHORED rows pointing at no DBLP work"),
      zero("edge_endpoint_a_is_node", endpointsA, "edge endpoints A that are not window nodes"),
      zero("edge_endpoint_b_is_node", endpointsB, "edge endpoints B that are not window nodes"),
      zero(
        "edges_canonical_order_no_self_loop",
        n(edges.where(F.col("researcherA") >= F.col("researcherB"))),
        "edges with A >= B",
      ),
      zero("edge_ids_valid", validEdgeIds, "edges with an invalid edge ID"),
      equal(
        "edge_ids_unique_per_window",
        n(edges.select("edgeId").distinct()),
        edgeCount,
        "distinct edgeId vs edges (all windows)",
      ),
      equal(
        "degree_sum_is_twice_edges",
        sumCoauthors,
        2L * edgeCount,
        "sum of coauthorCount vs 2 x edges",
      ),
      equal(
        "weighted_degree_sum_is_twice_joint",
        sumWeighted,
        2L * sumJoint,
        "sum of weightedDegree vs 2 x sum of jointPublicationCount",
      ),
      equal(
        "all_time_joint_equals_distinct_pairs",
        allTimeJointSum.getOrElse(c.distinctPairs),
        c.distinctPairs,
        "all-time sum of jointPublicationCount vs distinct (pair, work) rows",
      ),
      equal(
        "pair_candidates_all_mapped",
        c.inputPairs,
        inputs.rowCounts("pair-candidates"),
        "pair candidates mapped to two researchers vs pair-candidates rows",
      ),
      equal("components_cover_nodes", n(compMem), nodeCount, "component membership rows vs nodes"),
      equal(
        "components_cover_nodes_once",
        n(compMem.select(Cols.Window, "researcherId").distinct()),
        nodeCount,
        "distinct (window, researcher) in components vs nodes",
      ),
      equal(
        "communities_cover_nodes",
        n(commMem.select(Cols.Window, "researcherId").distinct()),
        nodeCount,
        "distinct (window, researcher) in communities vs nodes",
      ),
      equal(
        "communities_one_per_node",
        n(commMem),
        nodeCount,
        "community membership rows vs nodes",
      ),
      equal("coreness_covers_nodes", n(core), nodeCount, "coreness rows vs nodes"),
      Invariant(
        "coreness_at_most_max_degree",
        (for a <- maxCoreness.headOption; b <- maxDeg.headOption
        yield a.isNullAt(0) || a.getInt(0).toLong <= b.getLong(0)).getOrElse(true),
        s"max coreness ${maxCoreness.headOption.map(_.get(0))} vs max degree ${maxDeg.headOption.map(_.get(0))}",
      ),
      equal(
        "bridges_three_measures_per_node",
        bridgeRows,
        3L * nodeCount,
        "bridge rows vs 3 x nodes",
      ),
      zero("paths_valid", badPaths, "paths with an inconsistent hop, edge or length list"),
      zero(
        "m5_no_rejected_or_conflict_inside_a_cluster",
        cannotMixed,
        "rejected or conflict researcher decisions whose two mentions share a researcher ID",
      ),
      zero(
        "identity_canonical_id_is_smallest_dblp_key",
        canonicalMismatch,
        "clusters with a DBLP person whose ID is not researcherIdForClusterV2(its keys)",
      ),
      zero(
        "identity_external_singleton_keeps_unresolved_id",
        externalIdMismatch,
        "single-mention clusters without a DBLP person whose ID is not their base ID",
      ),
      zero(
        "identity_no_orcid_canonical_ids",
        orcidCanonical,
        "clusters whose canonical ID is a researcher:orcid: ID",
      ),
      zero(
        "identity_no_distinct_dblp_homonyms_in_cluster",
        homonymClusters,
        "researchers holding two different DBLP names of different base identities with one homonym stem (M1)",
      ),
      zero(
        "coauthor_edges_validate_as_core_v2",
        invalidEdgeCount,
        s"exported coauthor-edge rows that do not validate as CoauthorEdgeV2 ${sample(invalidEdges)}",
      ),
      zero(
        "researcher_metrics_validate_as_core_v2",
        invalidMetricCount,
        s"exported researcher-metric rows that do not validate as ResearcherMetricsV2 ${sample(invalidMetrics)}",
      ),
      zero(
        "researcher_metrics_joined_columns_present",
        metricNulls,
        "metric rows without community, component or bridge measures",
      ),
      equal(
        "coauthor_edge_works_rows_equal_joint_publications",
        edgeWorkRows,
        sumJoint,
        "coauthor-edge-works rows vs sum of jointPublicationCount",
      ),
      equal(
        "coauthor_edge_works_rows_unique",
        edgeWorkDistinct,
        edgeWorkRows,
        "distinct (edgeId, workId) vs coauthor-edge-works rows",
      ),
      zero(
        "coauthor_edge_works_match_edges",
        edgeEvidenceMismatch,
        "edges whose evidence rows differ from jointPublicationCount or supportingWorksDigest",
      ),
      equal(
        "resolves_to_rows_equal_researcher_orcids",
        n(resolves),
        expectedResolves,
        "resolves-to rows vs sum of researchers.orcids sizes",
      ),
      zero(
        "resolves_to_sources_are_researchers",
        resolvesDangling,
        "resolves-to sources that are not researchers",
      ),
      zero(
        "resolves_to_targets_valid",
        resolvesBadTarget,
        "resolves-to targets that are not valid researcher:orcid: references",
      ),
      equal(
        "external_identities_one_per_resolves_to_target",
        externalRows,
        resolvesTargets,
        "external-identities rows vs distinct resolves-to targets",
      ),
      equal(
        "external_identities_ids_unique",
        externalDistinct,
        externalRows,
        "distinct externalId vs external-identities rows",
      ),
      zero(
        "external_identities_well_formed",
        externalMalformed,
        "external-identities rows whose kind is not orcid or whose ID is not researcher:orcid:<value>",
      ),
      zero(
        "decision_endpoints_in_entity_views",
        notInViews,
        "match-decision endpoints that are not HJ6a entity views",
      ),
      zero(
        "decision_endpoints_resolvable",
        c.identityCounts.unmappedDecisions,
        "researcher decisions with an unknown mention",
      ),
    ) ++ dangling2.map { (l, f, to) =>
      Invariant(
        s"edge_endpoints_exist_${l.table.replace('-', '_')}",
        f == 0L && to == 0L,
        s"${l.table}: rows whose ${l.from.column} is not in ${l.from.vertexTable} = $f, " +
          s"rows whose ${l.to.column} is not in ${l.to.vertexTable} = $to (expected 0 and 0)",
      )
    } ++ List(
      Invariant(
        "components_and_kcore_converged",
        unconverged.isEmpty,
        s"not converged: ${unconverged.mkString(",")}",
      )
    )

  /** Edge rows (of the edge table of `e`) whose endpoint is not in the vertex table: a distributed
    * anti-join. Windowed vertex tables (communities, components) are matched on (window, ID).
    */
  private def danglingRows(t: Map[String, DataFrame], e: EdgeEnd): DataFrame =
    val edge = t(e.edgeTable)
    val keys = if e.windowedVertex then Seq(Cols.Window, e.column) else Seq(e.column)
    val ids  =
      val v    = t(e.vertexTable)
      val cols =
        (if e.windowedVertex then Seq(F.col(Cols.Window)) else Nil) :+
          F.col(e.vertexIdColumn).as(e.column)
      v.select(cols*).distinct()
    edge.join(ids, keys, "left_anti")

  /** Informational numbers recorded next to the invariants (not pass/fail). */
  def observations(c: Computed): List[(String, Long)] =
    List(
      "mappedPairs"                    -> c.inputPairs,
      "selfPairsDroppedAfterMerge"     -> c.selfPairsDropped,
      "withinBaseRejectionsIgnored"    -> c.identityCounts.withinBaseRejectionsIgnored,
      "orcidsSharedByMultipleClusters" -> c.identityCounts.orcidsSharedByClusters,
      "clustersWithTwoOrMoreOrcids"    -> c.identityCounts.clustersWithTwoOrMoreOrcids,
      "distinctPairWorkRows"           -> c.distinctPairs,
      "worksWhereCanonicalTeamSizeDiffersFromHadoopTeams" -> c.teamSizeMismatches,
    )
end Invariants

/** One endpoint of an edge table: `column` of `edgeTable` must be an ID of `vertexTable`. */
private[spark] final case class EdgeEnd(
    edgeTable: String,
    column: String,
    vertexTable: String,
    vertexIdColumn: String,
    windowedVertex: Boolean,
):
  def table: String = edgeTable

/** Both endpoints of every exported edge table, with the vertex table each one must be in (the
  * Neptune vertex sets of `neptune.json`).
  */
private[spark] final case class EdgeLink(table: String, from: EdgeEnd, to: EdgeEnd)

private[spark] object EdgeLinks:
  private def link(
      table: String,
      fromCol: String,
      fromVertex: String,
      fromId: String,
      toCol: String,
      toVertex: String,
      toId: String,
      windowedTo: Boolean = false,
  ) =
    EdgeLink(
      table,
      EdgeEnd(table, fromCol, fromVertex, fromId, windowedVertex = false),
      EdgeEnd(table, toCol, toVertex, toId, windowedTo),
    )

  val all: List[EdgeLink] = List(
    link("authored", "researcherId", "researchers", "researcherId", "workId", "works", "workId"),
    link(
      "coauthor-edges",
      "researcherA",
      "researchers",
      "researcherId",
      "researcherB",
      "researchers",
      "researcherId",
    ),
    link(
      "resolves-to",
      "sourceId",
      "researchers",
      "researcherId",
      "targetId",
      "external-identities",
      "externalId",
    ),
    link("has-topic", "workId", "works", "workId", "topicId", "topics", "topicId"),
    link("published-in", "workId", "works", "workId", "venueId", "venues", "venueId"),
    link(
      "community-membership",
      "researcherId",
      "researchers",
      "researcherId",
      "communityId",
      "communities",
      "communityId",
      windowedTo = true,
    ),
    link(
      "component-membership",
      "researcherId",
      "researchers",
      "researcherId",
      "componentId",
      "components",
      "componentId",
      windowedTo = true,
    ),
    EdgeLink(
      "community-cross-edges",
      EdgeEnd("community-cross-edges", "communityA", "communities", "communityId", true),
      EdgeEnd("community-cross-edges", "communityB", "communities", "communityId", true),
    ),
  )
end EdgeLinks

private[spark] object GraphWindowLabels:
  val allTime: String = scholarchain.core.GraphWindow.label(scholarchain.core.GraphWindows.AllTime)
