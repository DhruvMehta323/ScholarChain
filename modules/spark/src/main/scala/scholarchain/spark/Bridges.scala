package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions as F

import scholarchain.core.BridgeMeasure

/** Bridge measures per researcher and window, computed from community membership and the arcs.
  *
  * For researcher i with degree k_i (distinct coauthors) and k_ic coauthors in community c:
  *   - `cross_community_degree` = number of coauthors of i that are in a community other than i's
  *     own = k_i - k_i,own.
  *   - `participation_coefficient` P_i = 1 - sum_c (k_ic / k_i)^2, in [0, 1). It is 0 when all
  *     coauthors are in one community and when k_i = 0 (by definition here; isolated nodes are not
  *     bridges). The sum of squares is computed in exact integer arithmetic, then divided once.
  *   - `distinct_communities` = number of distinct communities among i's coauthors plus i's own
  *     community (an isolated researcher touches 1).
  * Approximate betweenness is NOT computed (the optional measure was not done).
  *
  * Output is long form: one row per (researcher, window, measure).
  */
object Bridges:
  def measures(
      membership: DataFrame,
      arcs: DataFrame,
      parameters: Map[String, String],
  ): DataFrame =
    val own = membership.select(
      F.col(Cols.Window),
      F.col(Cols.WindowStart),
      F.col(Cols.WindowEnd),
      F.col("researcherId").as("src"),
      F.col("communityId").as("ownComm"),
    )
    val dstComm = membership.select(
      F.col(Cols.Window),
      F.col("researcherId").as("dst"),
      F.col("communityId").as("dstComm"),
    )
    val perComm = arcs
      .select(Cols.Window, "src", "dst")
      .join(dstComm, Seq(Cols.Window, "dst"))
      .groupBy(Cols.Window, "src", "dstComm")
      .agg(F.count(F.lit(1)).as("k"))
    val agg = perComm
      .join(own.select(F.col(Cols.Window), F.col("src"), F.col("ownComm")), Seq(Cols.Window, "src"))
      .groupBy(Cols.Window, "src")
      .agg(
        F.sum("k").as("deg"),
        F.sum(F.col("k") * F.col("k")).as("sumSq"),
        F.sum(F.when(F.col("dstComm") =!= F.col("ownComm"), F.col("k")).otherwise(0L)).as("cross"),
        F.size(F.array_union(F.collect_set("dstComm"), F.array(F.max("ownComm")))).as("touched"),
      )
    val wide = own
      .join(agg, Seq(Cols.Window, "src"), "left")
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.col("src").as("researcherId"),
        F.coalesce(F.col("deg"), F.lit(0L)).as("deg"),
        F.coalesce(F.col("sumSq"), F.lit(0L)).as("sumSq"),
        F.coalesce(F.col("cross"), F.lit(0L)).as("cross"),
        F.coalesce(F.col("touched"), F.lit(1)).as("touched"),
      )
      .withColumn(
        "pc",
        F.when(F.col("deg") === 0L, F.lit(0.0))
          .otherwise(
            F.lit(1.0) - F.col("sumSq").cast("double") /
              (F.col("deg").cast("double") * F.col("deg").cast("double"))
          ),
      )
    val params                                       = Params.mapColumn(parameters)
    def long(m: BridgeMeasure, c: String): DataFrame =
      wide.select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.col("researcherId"),
        F.lit(m.wire).as("measure"),
        F.col(c).cast("double").as("value"),
        params.as("parameters"),
      )
    long(BridgeMeasure.CrossCommunityDegree, "cross")
      .union(long(BridgeMeasure.ParticipationCoefficient, "pc"))
      .union(long(BridgeMeasure.DistinctCommunities, "touched"))
end Bridges
