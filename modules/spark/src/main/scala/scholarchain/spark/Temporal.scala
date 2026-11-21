package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions as F
import org.apache.spark.sql.types.StructType

import scala.jdk.CollectionConverters.*

import scholarchain.core.{BridgeMeasure, GraphWindow, SparkConfig}

/** Per-window changes between consecutive EXPLICIT windows (ordered by start year; the all-time
  * window is never compared).
  *
  * One row per researcher active in the window or in the previous one: `status` is `both`, `new`
  * (only in this window) or `dropped` (only in the previous one). Coauthor continuity is exact set
  * arithmetic on the two windows' edge sets: `retainedCoauthors` = coauthors present in both,
  * `newCoauthors` = coauthorCount - retained, `lostCoauthors` = prevCoauthorCount - retained.
  *
  * Limits when windows are compared (also in SPARK-CONTRACTS.md): windows are independent graphs
  * (no edge links two windows); a window with fewer works has smaller degrees for that reason alone
  * (counts are not normalized); a researcher absent from a window may simply have published nothing
  * there; component and community IDs embed the window, so they are compared by SIZE, not by
  * identity; windows outside the configured range (works before the first or after the last window)
  * appear only in the all-time graph; identity (merging) is global, so a merge can add history to a
  * researcher in every window.
  */
object Temporal:
  private val PairSchema: StructType = StructType.fromDDL("window string, prevWindow string")

  def windowPairs(spark: SparkSession, cfg: SparkConfig): DataFrame =
    val ws   = cfg.explicitWindows.sortBy(_.startYear)
    val rows = ws.zip(ws.drop(1)).map((p, c) => Row(GraphWindow.label(c), GraphWindow.label(p)))
    spark.createDataFrame(rows.asJava, PairSchema)

  /** @param metrics
    *   `researcher-metrics`
    * @param componentMembership
    *   `component-membership`
    * @param components
    *   `components` (sizes)
    * @param communityMembership
    *   `community-membership`
    * @param communities
    *   `communities` (sizes)
    * @param bridges
    *   `bridges` (long form)
    * @param arcs
    *   `window, src, dst, weight` (both directions)
    * @param windows
    *   window frame (labels with start and end years)
    */
  def changes(
      metrics: DataFrame,
      componentMembership: DataFrame,
      components: DataFrame,
      communityMembership: DataFrame,
      communities: DataFrame,
      bridges: DataFrame,
      arcs: DataFrame,
      windows: DataFrame,
      cfg: SparkConfig,
  ): DataFrame =
    val pairs    = windowPairs(metrics.sparkSession, cfg)
    val compSize = componentMembership
      .join(
        components.select(F.col("window"), F.col("componentId"), F.col("size").as("componentSize")),
        Seq("window", "componentId"),
      )
      .select("window", "researcherId", "componentSize")
    val commSize = communityMembership
      .join(
        communities
          .select(F.col("window"), F.col("communityId"), F.col("size").as("communitySize")),
        Seq("window", "communityId"),
      )
      .select("window", "researcherId", "communitySize")
    val cross = bridges
      .where(F.col("measure") === BridgeMeasure.CrossCommunityDegree.wire)
      .select(
        F.col("window"),
        F.col("researcherId"),
        F.col("value").cast("long").as("crossCommunityDegree"),
      )
    val snap = metrics
      .select("window", "researcherId", "coauthorCount", "avgTeamSize", "maxTeamSize")
      .join(compSize, Seq("window", "researcherId"), "left")
      .join(commSize, Seq("window", "researcherId"), "left")
      .join(cross, Seq("window", "researcherId"), "left")

    val cur = snap
      .join(pairs, "window")
      .select(
        F.col("window"),
        F.col("prevWindow"),
        F.col("researcherId"),
        F.col("coauthorCount"),
        F.col("avgTeamSize"),
        F.col("maxTeamSize"),
        F.col("componentSize"),
        F.col("communitySize"),
        F.col("crossCommunityDegree"),
      )
    val pairsR = pairs.select(
      F.col("window").as("nwindow"),
      F.col("prevWindow").as("nprevWindow"),
    )
    val prev = snap
      .join(pairsR, F.col("window") === F.col("nprevWindow"))
      .select(
        F.col("nwindow").as("pwindow"),
        F.col("nprevWindow").as("pprevWindow"),
        F.col("researcherId").as("presearcherId"),
        F.col("coauthorCount").as("prevCoauthorCount"),
        F.col("avgTeamSize").as("prevAvgTeamSize"),
        F.col("maxTeamSize").as("prevMaxTeamSize"),
        F.col("componentSize").as("prevComponentSize"),
        F.col("communitySize").as("prevCommunitySize"),
        F.col("crossCommunityDegree").as("prevCrossCommunityDegree"),
      )
    val joined = cur.join(
      prev,
      F.col("window") === F.col("pwindow") && F.col("prevWindow") === F.col("pprevWindow") &&
        F.col("researcherId") === F.col("presearcherId"),
      "full_outer",
    )

    val a1 = arcs
      .select(F.col("window"), F.col("src"), F.col("dst"))
      .join(pairs, "window")
      .select(
        F.col("window").as("rw"),
        F.col("prevWindow").as("rprev"),
        F.col("src").as("rsrc"),
        F.col("dst").as("rdst"),
      )
    val a0 = arcs.select(
      F.col("window").as("pw"),
      F.col("src").as("psrc"),
      F.col("dst").as("pdst"),
    )
    val retained = a1
      .join(
        a0,
        F.col("rprev") === F.col("pw") && F.col("rsrc") === F.col("psrc") &&
          F.col("rdst") === F.col("pdst"),
      )
      .groupBy("rw", "rprev", "rsrc")
      .agg(F.count(F.lit(1)).as("retainedCoauthors"))

    val rid = F.coalesce(F.col("researcherId"), F.col("presearcherId"))
    val win = F.coalesce(F.col("window"), F.col("pwindow"))
    val pw  = F.coalesce(F.col("prevWindow"), F.col("pprevWindow"))
    joined
      .select(
        win.as("window"),
        pw.as("prevWindow"),
        rid.as("researcherId"),
        F.when(F.col("researcherId").isNull, "dropped")
          .when(F.col("presearcherId").isNull, "new")
          .otherwise("both")
          .as("status"),
        F.coalesce(F.col("coauthorCount"), F.lit(0L)).as("coauthorCount"),
        F.coalesce(F.col("prevCoauthorCount"), F.lit(0L)).as("prevCoauthorCount"),
        F.col("avgTeamSize"),
        F.col("prevAvgTeamSize"),
        F.col("maxTeamSize"),
        F.col("prevMaxTeamSize"),
        F.col("componentSize").cast("long").as("componentSize"),
        F.col("prevComponentSize").cast("long").as("prevComponentSize"),
        F.col("communitySize").cast("long").as("communitySize"),
        F.col("prevCommunitySize").cast("long").as("prevCommunitySize"),
        F.col("crossCommunityDegree").cast("long").as("crossCommunityDegree"),
        F.col("prevCrossCommunityDegree").cast("long").as("prevCrossCommunityDegree"),
      )
      .join(
        retained,
        F.col("window") === F.col("rw") && F.col("prevWindow") === F.col("rprev") &&
          F.col("researcherId") === F.col("rsrc"),
        "left",
      )
      .join(F.broadcast(windows), Seq("window"))
      .select(
        F.col("window"),
        F.col("windowStart"),
        F.col("windowEnd"),
        F.col("researcherId"),
        F.col("prevWindow"),
        F.col("status"),
        F.col("coauthorCount"),
        F.col("prevCoauthorCount"),
        F.coalesce(F.col("retainedCoauthors"), F.lit(0L)).as("retainedCoauthors"),
        (F.col("coauthorCount") - F.coalesce(F.col("retainedCoauthors"), F.lit(0L)))
          .as("newCoauthors"),
        (F.col("prevCoauthorCount") - F.coalesce(F.col("retainedCoauthors"), F.lit(0L)))
          .as("lostCoauthors"),
        F.col("avgTeamSize"),
        F.col("prevAvgTeamSize"),
        F.col("maxTeamSize"),
        F.col("prevMaxTeamSize"),
        F.col("componentSize"),
        F.col("prevComponentSize"),
        F.col("communitySize"),
        F.col("prevCommunitySize"),
        F.col("crossCommunityDegree"),
        F.col("prevCrossCommunityDegree"),
      )
end Temporal
