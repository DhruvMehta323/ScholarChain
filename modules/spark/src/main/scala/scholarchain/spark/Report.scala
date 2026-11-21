package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions as F

import scholarchain.core.{BridgeMeasure, SparkConfig}

/** `run-summary.json`: the observed statistics of a run, taken from small aggregated frames only
  * (limits and group-bys; never a full table). It holds no timestamps, so identical runs give
  * identical summaries.
  */
object Report:
  /** Converts a (small) collected row to JSON, recursively. */
  def rowJson(row: Row): ujson.Obj =
    ujson.Obj.from(row.schema.fieldNames.toList.zipWithIndex.map((n, i) => n -> value(row.get(i))))

  private def value(v: Any): ujson.Value = v match
    case null                          => ujson.Null
    case s: String                     => ujson.Str(s)
    case i: Int                        => ujson.Num(i.toDouble)
    case l: Long                       => ujson.Num(l.toDouble)
    case d: Double                     => ujson.Num(d)
    case b: Boolean                    => ujson.Bool(b)
    case r: Row                        => rowJson(r)
    case m: scala.collection.Map[?, ?] =>
      ujson.Obj.from(m.toList.map((k, x) => k.toString -> value(x)).sortBy(_._1))
    case s: scala.collection.Seq[?] => ujson.Arr.from(s.toList.map(value))
    case other                      => ujson.Str(other.toString)

  def json(df: DataFrame, max: Int): ujson.Arr =
    ujson.Arr.from(DriverCollect.rows(df, max).toList.map(rowJson))

  private def bucket(c: String, edges: List[(String, Long, Long)]): org.apache.spark.sql.Column =
    edges
      .foldLeft(F.when(F.lit(false), F.lit(""))) { (acc, e) =>
        acc.when(F.col(c) >= e._2 && F.col(c) <= e._3, F.lit(e._1))
      }
      .otherwise(F.lit("other"))

  def build(
      c: Computed,
      cfg: SparkConfig,
      inputs: Inputs.LoadedInputs,
      invariants: List[Invariant],
      written: List[TableWritten],
  ): ujson.Obj =
    val t       = c.tables
    val all     = GraphWindowLabels.allTime
    val ws      = t(Tables.WindowSummary.name)
    val metrics = t(Tables.ResearcherMetrics.name)
    val comm    = t(Tables.Communities.name)
    val core    = t(Tables.Coreness.name)
    val bridges = t(Tables.Bridges.name)
    val paths   = t(Tables.Paths.name)
    val names   = t(Tables.Researchers.name).select("researcherId", "displayName")
    val ic      = c.identityCounts
    // The ignored within-base rejections: count in `identity`, a bounded sorted sample here
    // (the full list is the table `within-base-rejections-ignored`).
    val withinBaseSample = t(Tables.WithinBaseRejections.name)
      .orderBy("leftId", "rightId", "outcome")
      .limit(20)
    val researchersT = t(Tables.Researchers.name)
    val resolvesRow  = DriverCollect.rows(
      researchersT.agg(
        F.sum(F.when(F.size(F.col("orcids")) >= 1, 1L).otherwise(0L)).as("withOrcid"),
        F.sum(F.when(F.size(F.col("orcids")) >= 2, 1L).otherwise(0L)).as("twoOrMore"),
        F.sum(F.size(F.col("orcids")).cast("long")).as("edges"),
      ),
      1,
    )
    def cell(i: Int): Double =
      resolvesRow.headOption
        .map(r => if r.isNullAt(i) then 0.0 else r.getLong(i).toDouble)
        .getOrElse(0.0)
    val resolvesToCounts =
      Map(
        "researchersWithOrcid"     -> cell(0),
        "researchersWithTwoOrMore" -> cell(1),
        "edges"                    -> cell(2),
      )

    val communityDistribution = comm
      .groupBy(Cols.Window, Cols.WindowStart)
      .agg(
        F.count(F.lit(1)).as("communityCount"),
        F.sum(F.when(F.col("size") === 1L, 1L).otherwise(0L)).as("size1"),
        F.sum(F.when(F.col("size") === 2L, 1L).otherwise(0L)).as("size2"),
        F.sum(F.when(F.col("size").between(3L, 5L), 1L).otherwise(0L)).as("size3to5"),
        F.sum(F.when(F.col("size").between(6L, 10L), 1L).otherwise(0L)).as("size6to10"),
        F.sum(F.when(F.col("size").between(11L, 25L), 1L).otherwise(0L)).as("size11to25"),
        F.sum(F.when(F.col("size") >= 26L, 1L).otherwise(0L)).as("size26plus"),
        F.max("size").as("largest"),
        F.slice(F.sort_array(F.collect_list("size"), false), 1, 5).as("top5Sizes"),
      )
      .orderBy("windowStart")
      .drop("windowStart")

    val top10 = metrics
      .where(F.col(Cols.Window) === all)
      .orderBy(F.col("coauthorCount").desc, F.col("researcherId"))
      .limit(10)
      .join(names, "researcherId")
      .orderBy(F.col("coauthorCount").desc, F.col("researcherId"))
      .select(
        "displayName",
        "researcherId",
        "coauthorCount",
        "publicationCount",
        "avgTeamSize",
        "maxTeamSize",
        "weightedDegree",
        "maxTeamWorkIds",
      )

    val firstStart = cfg.explicitWindows.map(_.startYear).min
    val lastEnd    = cfg.explicitWindows.map(_.endYear).max
    val workYears  = t(Tables.Works.name)
      .agg(
        F.count(F.lit(1)).as("works"),
        F.min("year").as("minYear"),
        F.max("year").as("maxYear"),
        F.sum(F.when(F.col("year") < firstStart, 1L).otherwise(0L)).as("beforeFirstWindow"),
        F.sum(F.when(F.col("year") > lastEnd, 1L).otherwise(0L)).as("afterLastWindow"),
        F.sum(F.when(F.col("year").between(firstStart, lastEnd), 1L).otherwise(0L))
          .as("insideExplicitWindows"),
      )

    val pathsFound = paths
      .groupBy(Cols.Window)
      .agg(F.count(F.lit(1)).as("pathsFound"))
    val pathsSampled = c.sampledPairs
      .groupBy(Cols.Window)
      .agg(F.count(F.lit(1)).as("pairsSampled"))
    val pathPerWindow = pathsSampled
      .join(pathsFound, Seq(Cols.Window), "left")
      .select(
        F.col(Cols.Window),
        F.col("pairsSampled"),
        F.coalesce(F.col("pathsFound"), F.lit(0L)).as("pathsFound"),
      )
      .orderBy(Cols.Window)
    val pathLengths = paths
      .where(F.col(Cols.Window) === all)
      .groupBy("length")
      .agg(F.count(F.lit(1)).as("paths"))
      .orderBy("length")
    val pathList = paths
      .where(F.col(Cols.Window) === all)
      .select("startId", "endId", "length", "hops")
      .orderBy(F.col("length").desc, F.col("startId"), F.col("endId"))

    val corenessDist = core
      .where(F.col(Cols.Window) === all)
      .groupBy("coreness")
      .agg(F.count(F.lit(1)).as("researchers"))
      .orderBy("coreness")

    def measure(m: BridgeMeasure): DataFrame =
      bridges.where(F.col("measure") === m.wire && F.col(Cols.Window) === all)
    val crossDist = measure(BridgeMeasure.CrossCommunityDegree)
      .withColumn("b", F.col("value").cast("long"))
      .withColumn(
        "bucket",
        bucket(
          "b",
          List(
            ("0", 0L, 0L),
            ("1", 1L, 1L),
            ("2", 2L, 2L),
            ("3-5", 3L, 5L),
            ("6-10", 6L, 10L),
            ("11+", 11L, Long.MaxValue),
          ),
        ),
      )
      .groupBy("bucket")
      .agg(F.count(F.lit(1)).as("researchers"))
      .orderBy("bucket")
    val pcDist = measure(BridgeMeasure.ParticipationCoefficient)
      .withColumn(
        "bucket",
        F.when(F.col("value") === 0.0, "0")
          .when(F.col("value") <= 0.25, "(0,0.25]")
          .when(F.col("value") <= 0.5, "(0.25,0.5]")
          .when(F.col("value") <= 0.75, "(0.5,0.75]")
          .otherwise("(0.75,1)"),
      )
      .groupBy("bucket")
      .agg(F.count(F.lit(1)).as("researchers"))
      .orderBy("bucket")
    val pcMax       = measure(BridgeMeasure.ParticipationCoefficient).agg(F.max("value").as("max"))
    val touchedDist = measure(BridgeMeasure.DistinctCommunities)
      .groupBy(F.col("value").cast("long").as("distinctCommunities"))
      .agg(F.count(F.lit(1)).as("researchers"))
      .orderBy("distinctCommunities")
    val bridgePerWindow = bridges
      .groupBy(Cols.Window, "measure")
      .agg(
        F.count(F.lit(1)).as("researchers"),
        F.sum(F.when(F.col("value") > 0.0, 1L).otherwise(0L)).as("withValueAboveZero"),
        F.max("value").as("max"),
      )
      .orderBy(Cols.Window, "measure")

    ujson.Obj(
      "summaryVersion" -> 1,
      "inputs"         -> ujson.Obj(
        "rowCounts" -> ujson.Obj.from(
          inputs.rowCounts.toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))
        ),
        "absentOrEmptyTables" -> ujson.Arr.from(inputs.absent.map(ujson.Str(_))),
      ),
      "identity" -> ujson.Obj(
        "mentions"                               -> ujson.Num(ic.mentions.toDouble),
        "dblpMentions"                           -> ujson.Num(ic.dblpMentions.toDouble),
        "baseIdentitiesDblp"                     -> ujson.Num(ic.baseIdentitiesDblp.toDouble),
        "externalMentions"                       -> ujson.Num(ic.externalMentions.toDouble),
        "clusters"                               -> ujson.Num(ic.clusters.toDouble),
        "mergedClustersWithTwoOrMoreDblpPersons" -> ujson.Num(ic.mergedClusters.toDouble),
        "clustersAbsorbingExternalMentions"      -> ujson.Num(ic.absorbedExternalClusters.toDouble),
        "cannotLinkBlockedComponents"     -> ujson.Num(ic.cannotLinkBlockedComponents.toDouble),
        "cannotLinkBlockedBaseIdentities" -> ujson.Num(ic.cannotLinkBlockedBaseIdentities.toDouble),
        "unresolvedExternalSingletons"    -> ujson.Num(ic.unresolvedSingletons.toDouble),
        "cannotLinkBlockedByReason"       -> ujson.Obj(
          "rejected_or_conflict_pair" -> ujson.Num(ic.blockedByRejectedOrConflict.toDouble),
          "two_orcids"                -> ujson.Num(ic.blockedByTwoOrcids.toDouble),
          "distinct_dblp_homonyms"    -> ujson.Num(ic.blockedByDistinctDblpHomonyms.toDouble),
        ),
        "cannotLinkBlockedUnsuffixedVsSuffixed" -> ujson.Num(
          ic.cannotLinkBlockedUnsuffixedVsSuffixed.toDouble
        ),
        "withinBaseRejectionsIgnored"       -> ujson.Num(ic.withinBaseRejectionsIgnored.toDouble),
        "withinBaseRejectionsIgnoredSample" -> json(withinBaseSample, 20),
        "researchersWithOrcidProperty"      -> ujson.Num(resolvesToCounts("researchersWithOrcid")),
        "researchersWithTwoOrMoreOrcids" -> ujson.Num(resolvesToCounts("researchersWithTwoOrMore")),
        "resolvesToEdges"                -> ujson.Num(resolvesToCounts("edges")),
        "externalIdentityVertices"       -> ujson.Num(
          DriverCollect.count(t(Tables.ExternalIdentities.name)).toDouble
        ),
        "clustersWithOrcid"              -> ujson.Num(ic.clustersWithOrcid.toDouble),
        "clustersWithTwoOrMoreOrcids"    -> ujson.Num(ic.clustersWithTwoOrMoreOrcids.toDouble),
        "orcidsSharedByMultipleClusters" -> ujson.Num(ic.orcidsSharedByClusters.toDouble),
        "clustersSharingAnOrcid"         -> ujson.Num(ic.clustersSharingAnOrcid.toDouble),
        "unmappedResearcherDecisions"    -> ujson.Num(ic.unmappedDecisions.toDouble),
      ),
      "pairs" -> ujson.Obj(
        "mappedPairCandidates"                      -> ujson.Num(c.inputPairs.toDouble),
        "selfPairsDroppedAfterMerge"                -> ujson.Num(c.selfPairsDropped.toDouble),
        "distinctPairWorkRows"                      -> ujson.Num(c.distinctPairs.toDouble),
        "worksWithTeamSizeDifferingFromHadoopTeams" -> ujson.Num(c.teamSizeMismatches.toDouble),
      ),
      "workYears"                   -> json(workYears, 1),
      "windows"                     -> json(ws.orderBy("windowStart"), 50),
      "communitySizeDistribution"   -> json(communityDistribution, 50),
      "top10ByCoauthorCountAllTime" -> json(top10, 10),
      "paths"                       -> ujson.Obj(
        "perWindow"      -> json(pathPerWindow, 50),
        "allTimeLengths" -> json(pathLengths, 20),
        "allTimePaths"   -> json(pathList, 100),
        "estimates"      -> json(t(Tables.PathEstimates.name).orderBy("windowStart"), 50),
      ),
      "corenessDistributionAllTime" -> json(corenessDist, 200),
      "bridges"                     -> ujson.Obj(
        "crossCommunityDegreeAllTime"        -> json(crossDist, 20),
        "participationCoefficientAllTime"    -> json(pcDist, 20),
        "participationCoefficientMaxAllTime" -> json(pcMax, 1),
        "distinctCommunitiesAllTime"         -> json(touchedDist, 100),
        "perWindow"                          -> json(bridgePerWindow, 200),
      ),
      "algorithms" -> ujson.Arr.from(
        c.convergences.map(v =>
          ujson.Obj(
            "algorithm"    -> v.algorithm,
            "iterations"   -> v.iterations,
            "converged"    -> v.converged,
            "iterationCap" -> v.cap,
          )
        )
      ),
      "partitions" -> ujson.Obj(
        "shufflePartitions" -> cfg.shufflePartitions,
        "outputPartitions"  -> cfg.outputPartitions,
        "framePartitions"   -> ujson.Obj.from(
          c.partitions.toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))
        ),
        "outputPartFiles" -> ujson.Obj.from(
          written.sortBy(_.spec.name).map(w => w.spec.name -> ujson.Num(w.partFiles.toDouble))
        ),
      ),
      "tables" -> ujson.Obj.from(
        written.sortBy(_.spec.name).map(w => w.spec.name -> ujson.Num(w.rowCount.toDouble))
      ),
      "invariants" -> ujson.Arr.from(
        invariants.map(i => ujson.Obj("name" -> i.name, "ok" -> i.ok, "detail" -> i.detail))
      ),
    )
end Report
