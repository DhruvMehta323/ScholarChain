package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions as F

import scholarchain.core.{BridgeMeasure, GraphWindows}

/** Deterministic semantic summaries rendered from templates over computed tables and source
  * records. No language model, no invented numbers: every number in a text is a value of a metric
  * table of the same run (integers as they are; decimals as `round(value, 4)`), and every name is
  * the DBLP name of a researcher. Document types: `researcher_profile`, `community_profile`,
  * `path_summary`, `topic_summary`. `textHash` is `Hashing.textHash(text)`.
  */
object Summaries:
  val ResearcherProfile = "researcher_profile"
  val CommunityProfile  = "community_profile"
  val PathSummary       = "path_summary"
  val TopicSummary      = "topic_summary"

  private def r4(c: Column): Column = F.round(c, 4).cast("string")

  private def finish(df: DataFrame, runId: String): DataFrame =
    Udfs.register(df.sparkSession)
    df.select(
      F.col(Cols.Window),
      F.col(Cols.WindowStart),
      F.col(Cols.WindowEnd),
      F.col("entityId"),
      F.col("documentType"),
      F.call_udf(Udfs.TextHash, F.col("text")).as("textHash"),
      F.col("text"),
      F.lit(runId).as("runId"),
    )

  private def bridgeValue(bridges: DataFrame, m: BridgeMeasure, as: String): DataFrame =
    bridges
      .where(F.col("measure") === m.wire)
      .select(F.col(Cols.Window), F.col("researcherId"), F.col("value").as(as))

  /** One profile per researcher and window. */
  def researcherProfiles(
      researchers: DataFrame,
      metrics: DataFrame,
      coreness: DataFrame,
      componentMembership: DataFrame,
      components: DataFrame,
      communityMembership: DataFrame,
      communities: DataFrame,
      bridges: DataFrame,
      runId: String,
  ): DataFrame =
    val names = researchers.select("researcherId", "displayName")
    val comp  = componentMembership
      .join(
        components.select(F.col(Cols.Window), F.col("componentId"), F.col("size").as("compSize")),
        Seq(Cols.Window, "componentId"),
      )
      .select(Cols.Window, "researcherId", "compSize")
    val comm = communityMembership
      .join(
        communities.select(F.col(Cols.Window), F.col("communityId"), F.col("size").as("commSize")),
        Seq(Cols.Window, "communityId"),
      )
      .select(Cols.Window, "researcherId", "communityId", "commSize")
    val k    = Seq(Cols.Window, "researcherId")
    val text = F.format_string(
      "Researcher %s (%s) in window %s: %d works, %d distinct coauthors, average team size %s, " +
        "maximum team size %d (work %s), weighted degree %d, coreness %d. Component of %d " +
        "researchers. Community %s of %d researchers; cross-community degree %d, " +
        "participation coefficient %s.",
      F.col("displayName"),
      F.col("researcherId"),
      F.col(Cols.Window),
      F.col("publicationCount"),
      F.col("coauthorCount"),
      r4(F.col("avgTeamSize")),
      F.col("maxTeamSize"),
      F.element_at(F.col("maxTeamWorkIds"), 1),
      F.col("weightedDegree"),
      F.col("coreness"),
      F.col("compSize"),
      F.col("communityId"),
      F.col("commSize"),
      F.col("cross").cast("long"),
      r4(F.col("pc")),
    )
    val base = metrics
      .join(names, "researcherId")
      .join(coreness.select(F.col(Cols.Window), F.col("researcherId"), F.col("coreness")), k)
      .join(comp, k)
      .join(comm, k)
      .join(bridgeValue(bridges, BridgeMeasure.CrossCommunityDegree, "cross"), k)
      .join(bridgeValue(bridges, BridgeMeasure.ParticipationCoefficient, "pc"), k)
    finish(
      base.select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.col("researcherId").as("entityId"),
        F.lit(ResearcherProfile).as("documentType"),
        text.as("text"),
      ),
      runId,
    )

  /** One profile per community and window. */
  def communityProfiles(
      communities: DataFrame,
      communityMembership: DataFrame,
      metrics: DataFrame,
      researchers: DataFrame,
      runId: String,
  ): DataFrame =
    val names = researchers.select("researcherId", "displayName")
    val top   = communityMembership
      .join(
        metrics.select(F.col(Cols.Window), F.col("researcherId"), F.col("coauthorCount")),
        Seq(Cols.Window, "researcherId"),
      )
      .groupBy(Cols.Window, "communityId")
      .agg(
        F.min_by(
          F.col("researcherId"),
          F.struct((F.lit(0L) - F.col("coauthorCount")).as("neg"), F.col("researcherId").as("id")),
        ).as("topId"),
        F.max("coauthorCount").as("topCoauthors"),
      )
      .join(names, F.col("topId") === F.col("researcherId"))
      .select(
        F.col(Cols.Window),
        F.col("communityId"),
        F.col("displayName").as("topName"),
        F.col("topCoauthors"),
      )
    val text = F.format_string(
      "Community %s in window %s has %d researchers, %d internal edges and %d cross-community " +
        "edges. Most connected member: %s (%d distinct coauthors).",
      F.col("communityId"),
      F.col(Cols.Window),
      F.col("size"),
      F.col("internalEdgeCount"),
      F.col("crossEdgeCount"),
      F.col("topName"),
      F.col("topCoauthors"),
    )
    finish(
      communities
        .join(top, Seq(Cols.Window, "communityId"))
        .select(
          F.col(Cols.Window),
          F.col(Cols.WindowStart),
          F.col(Cols.WindowEnd),
          F.col("communityId").as("entityId"),
          F.lit(CommunityProfile).as("documentType"),
          text.as("text"),
        ),
      runId,
    )

  /** One summary per path: names along the hops and the supporting works of each hop. */
  def pathSummaries(paths: DataFrame, researchers: DataFrame, runId: String): DataFrame =
    val names = researchers.select(
      F.col("researcherId").as("hopId"),
      F.col("displayName").as("hopName"),
    )
    val hopNames = paths
      .select(
        F.col(Cols.Window).as("window"),
        F.col("startId"),
        F.col("endId"),
        F.posexplode(F.col("hops")).as(Seq("pos", "hopId")),
      )
      .join(names, "hopId")
      .groupBy("window", "startId", "endId")
      .agg(
        F.concat_ws(
          " -> ",
          F.expr("transform(sort_array(collect_list(struct(pos, hopName))), x -> x.hopName)"),
        ).as("route")
      )
    val text = F.format_string(
      "Shortest collaboration path in window %s from %s to %s has length %d: %s. Supporting " +
        "works per hop: %s.",
      F.col(Cols.Window),
      F.col("startName"),
      F.col("endName"),
      F.col("length"),
      F.col("route"),
      F.col("works"),
    )
    val s = names.select(F.col("hopId").as("sid"), F.col("hopName").as("startName"))
    val e = names.select(F.col("hopId").as("eid"), F.col("hopName").as("endName"))
    finish(
      paths
        .join(hopNames, Seq("window", "startId", "endId"))
        .join(s, F.col("startId") === F.col("sid"))
        .join(e, F.col("endId") === F.col("eid"))
        .withColumn(
          "works",
          F.expr("array_join(transform(supportingWorkIds, x -> array_join(x, ',')), '; ')"),
        )
        .select(
          F.col(Cols.Window),
          F.col(Cols.WindowStart),
          F.col(Cols.WindowEnd),
          F.concat(
            F.lit("path:"),
            F.col(Cols.Window),
            F.lit(":"),
            F.col("startId"),
            F.lit(":"),
            F.col("endId"),
          ).as("entityId"),
          F.lit(PathSummary).as("documentType"),
          text.as("text"),
        ),
      runId,
    )

  /** One summary per topic, in the all-time window (a topic summary is not windowed by nature). */
  def topicSummaries(
      topics: DataFrame,
      hasTopic: DataFrame,
      works: DataFrame,
      authored: DataFrame,
      runId: String,
  ): DataFrame =
    val stats = hasTopic
      .select("topicId", "workId")
      .distinct()
      .join(works.select("workId", "year"), "workId")
      .join(authored.select("workId", "researcherId"), "workId")
      .groupBy("topicId")
      .agg(
        F.countDistinct("workId").as("works"),
        F.countDistinct("researcherId").as("researchers"),
        F.min("year").as("firstYear"),
        F.max("year").as("lastYear"),
      )
    val text = F.format_string(
      "Topic %s (%s) is linked to %d works by %d distinct researchers, published from %d to %d.",
      F.col("label"),
      F.col("topicId"),
      F.col("works"),
      F.col("researchers"),
      F.col("firstYear"),
      F.col("lastYear"),
    )
    val all = GraphWindows.AllTime
    finish(
      topics
        .join(stats, "topicId")
        .select(
          F.lit(scholarchain.core.GraphWindow.label(all)).as(Cols.Window),
          F.lit(all.startYear).as(Cols.WindowStart),
          F.lit(all.endYear).as(Cols.WindowEnd),
          F.col("topicId").as("entityId"),
          F.lit(TopicSummary).as("documentType"),
          text.as("text"),
        ),
      runId,
    )
end Summaries
