package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions as F
import org.apache.spark.sql.types.StructType

import scala.jdk.CollectionConverters.*

import scholarchain.core.{GraphWindow, GraphWindows, SparkConfig}

/** Everything the pipeline computed, as frames keyed by `Tables.Spec.name`, plus scalars needed for
  * invariants and the report. Frames are checkpointed (materialized), so reading them again does
  * not recompute the plan.
  */
final case class Computed(
    tables: Map[String, DataFrame],
    convergences: List[Convergence],
    identityCounts: Identity.Counts,
    inputPairs: Long,
    selfPairsDropped: Long,
    distinctPairs: Long,
    teamSizeMismatches: Long,
    sampledPairs: DataFrame,
    partitions: Map[String, Int],
)

/** The stages of the job, in order. Every stage is a pure function of frames and config; this
  * object only wires them. Shuffle boundaries and checkpoints are documented in SPARK-CONTRACTS.md.
  */
object Pipeline:
  def compute(
      spark: SparkSession,
      cfg: SparkConfig,
      in: Inputs.LoadedInputs,
      runId: String,
  ): Computed =
    Udfs.register(spark)
    val ck      = Checkpointer(cfg.checkpointDir)
    val windows = Graph.windowsFrame(spark, cfg)
    val labels  = cfg.allWindows.map(GraphWindow.label)
    val prov    = V2Export.provenance(in.refs, cfg.codeVersion, Export.parameters(cfg), runId)

    // ---- 1. identity -----------------------------------------------------------------------
    val policyRow = DriverCollect.rows(in.decisions.agg(F.min("policyVersion")), 1)
    val policy    =
      policyRow.headOption.flatMap(r => Option(r.getString(0))).getOrElse(cfg.policyVersion)
    val identity       = Identity.build(in.mentions, in.aliases, in.decisions, policy, cfg)
    val clusters       = ck(identity.clusters)
    val mr             = ck(identity.mentionResearcher)
    val identityCounts = Identity.counts(identity.copy(clusters = clusters, mentionResearcher = mr))

    // ---- 2. authorship and teams -----------------------------------------------------------
    val authored = ck(Graph.authored(in.authorship, mr))
    val teams    = ck(Graph.workTeams(authored))

    // ---- 3. pairs and the windowed graph ---------------------------------------------------
    val mapped     = ck(Graph.mappedPairs(in.pairs, mr))
    val pairsCanon = ck(Graph.canonicalPairs(mapped))
    val inputPairs = DriverCollect.count(mapped)
    val distinct   = DriverCollect.count(pairsCanon)
    val selfPairs = DriverCollect.count(mapped.where(F.col("researcherA") === F.col("researcherB")))
    val edges     = ck(Graph.edges(pairsCanon, windows, cfg))
    val arcs      = ck(Graph.arcs(edges))
    val nodes     = ck(Graph.nodes(authored, windows))
    val nodeIds   = nodes.select(F.col("window"), F.col("researcherId").as("id"))
    val metrics   = ck(Metrics.researcherMetrics(authored, teams, edges, windows, cfg))

    // ---- 4. components ---------------------------------------------------------------------
    val (cLabels, cConv) =
      Components.labels(nodeIds, arcs, cfg.connectedComponentsMaxIterations, ck)
    val compMem   = ck(Components.membership(cLabels, windows))
    val compSizes = ck(Components.sizes(compMem))

    // ---- 5. communities and bridges --------------------------------------------------------
    val (lLabels, lConv) =
      Communities.labelPropagation(nodeIds, arcs, cfg.labelPropagationMaxIterations, ck)
    val commMem    = ck(Communities.membership(lLabels, windows))
    val commParams = Map(
      "maxIterations" -> cfg.labelPropagationMaxIterations.toString,
      "tieBreak"      -> "smallest_label",
      "schedule"      -> "alternating_parity",
    )
    val communities  = ck(Communities.records(commMem, edges, commParams))
    val crossEdges   = Communities.crossEdges(commMem, edges, windows)
    val bridgeParams = Map(
      "communityMethod" -> Communities.Method,
      "maxIterations"   -> cfg.labelPropagationMaxIterations.toString,
    )
    val bridges = ck(Bridges.measures(commMem, arcs, bridgeParams))

    // ---- 6. k-core -------------------------------------------------------------------------
    val (kc, kConv) = KCore.coreness(nodeIds, arcs, cfg.kCoreMaxIterations, ck)
    val coreness    = ck(
      kc.join(F.broadcast(windows), Seq(Cols.Window))
        .select(
          F.col(Cols.Window),
          F.col(Cols.WindowStart),
          F.col(Cols.WindowEnd),
          F.col("id").as("researcherId"),
          F.col("coreness").cast("int").as("coreness"),
        )
    )

    // ---- 7. paths --------------------------------------------------------------------------
    val largest = ck(
      Sampling.largestComponentMembers(
        compMem.select(F.col(Cols.Window), F.col("componentId"), F.col("researcherId"))
      )
    )
    val pairSample = ck(
      Sampling.samplePairs(largest, labels, cfg.pathSampleSeed, cfg.pathSamplePairs)
    )
    val avgSources = ck(
      Sampling
        .smallestHash(largest.select("window", "id"), labels, cfg.avgPathSeed, cfg.avgPathSourcesK)
        .select(F.col("window"), F.col("id").as("source"))
    )
    val pairSources      = pairSample.select(F.col("window"), F.col("startId").as("source"))
    val sources          = pairSources.union(avgSources).distinct()
    val (visited, bConv) = ShortestPaths.bfs(arcs, sources, cfg.bfsMaxDepth, ck)
    val pathsRaw         = ShortestPaths.evidence(visited, pairSample, edges, cfg.bfsMaxDepth)
    val paths            = ck(
      pathsRaw
        .join(F.broadcast(windows), Seq(Cols.Window))
        .select(
          F.col(Cols.Window),
          F.col(Cols.WindowStart),
          F.col(Cols.WindowEnd),
          F.col("startId"),
          F.col("endId"),
          F.col("hops"),
          F.col("edgeIds"),
          F.col("supportingWorkIds"),
          F.col("length"),
        )
    )
    val avgVisited = visited.join(
      avgSources.select(F.col("window").as("aw"), F.col("source").as("asrc")),
      F.col("window") === F.col("aw") && F.col("source") === F.col("asrc"),
      "left_semi",
    )
    val largestSize   = largest.select("window", "size").distinct()
    val pathEstimates = ck(
      ShortestPaths.estimate(
        avgVisited,
        largestSize,
        windows,
        cfg.avgPathSourcesK,
        cfg.avgPathSeed,
        cfg.bfsMaxDepth,
      )
    )

    // ---- 8. enrichment tables --------------------------------------------------------------
    val links       = ck(Enrichment.workLinks(in.decisions))
    val hasTopic    = ck(Enrichment.hasTopic(in.works, links))
    val worksTbl    = Enrichment.worksTable(in.works, teams, links, hasTopic)
    val researchers = ck(Enrichment.researchers(clusters, authored))
    val resolvesTo  = ck(Enrichment.resolvesTo(researchers, ck(identity.orcidEvidence), prov))

    // ---- 9. window summary -----------------------------------------------------------------
    val nodeCount = nodes.groupBy(Cols.Window).agg(F.count(F.lit(1)).as("nodeCount"))
    val edgeCount = edges.groupBy(Cols.Window).agg(F.count(F.lit(1)).as("edgeCount"))
    val teamsW    = Graph.expandWindows(teams.select("workId", "teamSize", "year"), windows)
    val workStats = teamsW
      .groupBy(Cols.Window)
      .agg(
        F.count(F.lit(1)).as("workCount"),
        F.max("teamSize").as("maxTeamSize"),
        F.min_by(
          F.col("workId"),
          F.struct((F.lit(0) - F.col("teamSize")).as("neg"), F.col("workId").as("w")),
        ).as("maxTeamWorkId"),
      )
    val compStats = compSizes
      .groupBy(Cols.Window)
      .agg(
        F.count(F.lit(1)).as("componentCount"),
        F.sum(F.when(F.col("size") === 1L, 1L).otherwise(0L)).as("singletonCount"),
        F.max("size").as("largestComponentSize"),
      )
    val commCount     = communities.groupBy(Cols.Window).agg(F.count(F.lit(1)).as("communityCount"))
    val maxCore       = coreness.groupBy(Cols.Window).agg(F.max("coreness").as("maxCoreness"))
    val windowSummary = windows
      .join(nodeCount, Seq(Cols.Window), "left")
      .join(edgeCount, Seq(Cols.Window), "left")
      .join(workStats, Seq(Cols.Window), "left")
      .join(compStats, Seq(Cols.Window), "left")
      .join(commCount, Seq(Cols.Window), "left")
      .join(maxCore, Seq(Cols.Window), "left")
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.coalesce(F.col("nodeCount"), F.lit(0L)).as("nodeCount"),
        F.coalesce(F.col("edgeCount"), F.lit(0L)).as("edgeCount"),
        F.coalesce(F.col("workCount"), F.lit(0L)).as("workCount"),
        F.coalesce(F.col("componentCount"), F.lit(0L)).as("componentCount"),
        F.coalesce(F.col("singletonCount"), F.lit(0L)).as("singletonCount"),
        F.coalesce(F.col("largestComponentSize"), F.lit(0L)).as("largestComponentSize"),
        F.when(
          F.coalesce(F.col("nodeCount"), F.lit(0L)) > 0L,
          F.col("largestComponentSize").cast("double") / F.col("nodeCount").cast("double"),
        ).otherwise(F.lit(0.0))
          .as("largestComponentFraction"),
        F.coalesce(F.col("communityCount"), F.lit(0L)).as("communityCount"),
        F.coalesce(F.col("maxCoreness"), F.lit(0)).as("maxCoreness"),
        F.coalesce(F.col("maxTeamSize"), F.lit(0)).as("maxTeamSize"),
        F.col("maxTeamWorkId"),
      )

    // ---- 10. temporal ----------------------------------------------------------------------
    val temporal = Temporal.changes(
      metrics,
      compMem,
      compSizes,
      commMem,
      communities,
      bridges,
      arcs,
      windows,
      cfg,
    )

    // ---- 11. summaries ---------------------------------------------------------------------
    val topicsTbl = Enrichment.topics(in.topics, hasTopic)
    val summaries = Summaries
      .researcherProfiles(
        researchers,
        metrics,
        coreness,
        compMem,
        compSizes,
        commMem,
        communities,
        bridges,
        runId,
      )
      .union(Summaries.communityProfiles(communities, commMem, metrics, researchers, runId))
      .union(Summaries.pathSummaries(paths, researchers, runId))
      .union(
        Summaries.topicSummaries(topicsTbl, hasTopic, in.works, authored, runId)
      )

    // ---- 12. algorithm status --------------------------------------------------------------
    val convs = List(
      identity.convergence.copy(algorithm = "identity_" + identity.convergence.algorithm),
      cConv,
      lConv,
      kConv,
      bConv,
    )
    val scope      = List("identity", "windows", "windows", "windows", "windows")
    val statusRows = convs
      .zip(scope)
      .map((c, s) => Row(c.algorithm, s, c.iterations, c.converged, c.cap))
    val status = spark.createDataFrame(
      statusRows.asJava,
      Tables.AlgorithmStatus.schema,
    )

    // ---- 13. cross-check against the Hadoop teams table (informational) --------------------
    val teamMismatch = DriverCollect.count(
      in.teams
        .select(F.col("workId"), F.col("teamSize").as("hadoopTeamSize"))
        .join(teams.select("workId", "teamSize"), "workId")
        .where(F.col("hadoopTeamSize") =!= F.col("teamSize"))
    )

    // ---- 13b. v2 export frames (exactly the core CoauthorEdgeV2 / ResearcherMetricsV2) -----------
    val edgesV2   = ck(V2Export.coauthorEdges(edges, prov))
    val edgeWorks = ck(V2Export.edgeWorks(pairsCanon, windows, cfg))
    val metricsV2 = ck(
      V2Export.researcherMetrics(metrics, coreness, compMem, commMem, bridges, cfg, prov)
    )

    def spec(s: Tables.Spec, df: DataFrame): (String, DataFrame) =
      s.name -> df.select(s.columns.map(F.col)*)
    def win(df: DataFrame): DataFrame =
      df.join(F.broadcast(windows), Seq(Cols.Window))

    val tables = Map(
      spec(Tables.IdentityClusters, clusters),
      spec(Tables.CannotLinkBlocked, identity.blocked),
      spec(Tables.WithinBaseRejections, identity.withinBaseRejections),
      spec(Tables.MentionResearcher, mr),
      spec(Tables.Researchers, researchers),
      spec(Tables.ResolvesTo, resolvesTo),
      spec(Tables.ExternalIdentities, Enrichment.externalIdentities(resolvesTo)),
      spec(Tables.Works, worksTbl),
      spec(
        Tables.Authored,
        authored.select("researcherId", "workId", "year", "position", "mentionCount"),
      ),
      spec(Tables.Topics, topicsTbl),
      spec(Tables.Institutions, Enrichment.institutions(in.institutions)),
      spec(Tables.Venues, Enrichment.venues(in.works)),
      spec(Tables.HasTopic, hasTopic),
      spec(Tables.PublishedIn, Enrichment.publishedIn(in.works)),
      spec(Tables.CoauthorEdges, edgesV2),
      spec(Tables.CoauthorEdgeWorks, edgeWorks),
      spec(Tables.ResearcherMetrics, metricsV2),
      spec(Tables.WindowSummary, windowSummary),
      spec(Tables.Components, compSizes),
      spec(Tables.ComponentMembership, compMem),
      spec(Tables.Communities, communities),
      spec(Tables.CommunityMembership, commMem),
      spec(Tables.CommunityCrossEdges, crossEdges),
      spec(Tables.Bridges, bridges),
      spec(Tables.Coreness, coreness),
      spec(Tables.Paths, paths),
      spec(Tables.PathEstimates, pathEstimates),
      spec(Tables.TemporalChanges, temporal),
      spec(Tables.SemanticSummaries, summaries),
      spec(Tables.AlgorithmStatus, status),
    )
    val partitions = List(
      "authored"             -> authored,
      "coauthor-edges"       -> edges,
      "arcs"                 -> arcs,
      "researcher-metrics"   -> metrics,
      "component-membership" -> compMem,
      "community-membership" -> commMem,
      "bridges"              -> bridges,
      "bfs-visited"          -> visited,
    ).map((n, df) => n -> df.rdd.getNumPartitions).toMap

    Computed(
      tables,
      convs,
      identityCounts,
      inputPairs,
      selfPairs,
      distinct,
      teamMismatch,
      pairSample,
      partitions,
    )
  end compute
end Pipeline
