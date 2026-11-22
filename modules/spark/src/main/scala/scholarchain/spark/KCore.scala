package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions as F

/** K-core decomposition by iterative degree pruning (batch peeling), DataFrame only.
  *
  * Frames as in `Components` (`nodes: window,id`; `arcs: window,src,dst`, both directions).
  *
  * Per window keep a level `k` (start 0). Each round: compute the degree of every remaining node in
  * the remaining graph; raise `k` to `max(k, minimum remaining degree)`; remove EVERY node whose
  * degree is <= `k` and give it `coreness = k`; drop the removed nodes and their arcs. This is the
  * Batagelj-Zaversnik peeling applied to all minimum-degree nodes at once; it yields the standard
  * core number (largest k such that the node lies in a subgraph of minimum degree k). Isolated
  * nodes get coreness 0. Every round removes at least one node of every non-empty window, so the
  * loop ends; it also stops at `maxRounds` (then the still remaining nodes get the current level
  * `k` of their window, a lower bound, and `Convergence.converged` is false). Every round is
  * checkpointed.
  */
object KCore:
  val AlgorithmName = "k_core_peeling"

  def coreness(
      nodes: DataFrame,
      arcs: DataFrame,
      maxRounds: Int,
      ck: Checkpointer,
  ): (DataFrame, Convergence) =
    var remaining = ck(nodes.select("window", "id"))
    var live      = ck(arcs.select("window", "src", "dst"))
    var levels    = ck(remaining.select("window").distinct().withColumn("k", F.lit(0)))
    var result    =
      ck(remaining.limit(0).withColumn("coreness", F.lit(0)))
    var rounds = 0
    while !DriverCollect.isEmpty(remaining) && rounds < maxRounds do
      val degOf = live.groupBy("window", "src").agg(F.count(F.lit(1)).as("deg"))
      val deg   = remaining
        .join(
          degOf.select(F.col("window").as("dw"), F.col("src"), F.col("deg")),
          F.col("window") === F.col("dw") && F.col("id") === F.col("src"),
          "left",
        )
        .select(F.col("window"), F.col("id"), F.coalesce(F.col("deg"), F.lit(0L)).as("deg"))
      val minDeg = deg.groupBy("window").agg(F.min("deg").as("minDeg"))
      levels = ck(
        levels
          .join(minDeg, Seq("window"), "left")
          .select(
            F.col("window"),
            F.greatest(F.col("k"), F.col("minDeg").cast("int")).as("k"),
          )
      )
      val removed = ck(
        deg
          .join(levels, Seq("window"))
          .where(F.col("deg") <= F.col("k"))
          .select(F.col("window"), F.col("id"), F.col("k").as("coreness"))
      )
      result = ck(result.union(removed))
      remaining = ck(
        remaining.join(removed.select("window", "id"), Seq("window", "id"), "left_anti")
      )
      live = ck(
        live
          .join(
            removed.select(F.col("window"), F.col("id").as("src")),
            Seq("window", "src"),
            "left_anti",
          )
          .join(
            removed.select(F.col("window"), F.col("id").as("dst")),
            Seq("window", "dst"),
            "left_anti",
          )
      )
      rounds += 1
    val done = DriverCollect.isEmpty(remaining)
    val full =
      if done then result
      else
        result.union(
          remaining
            .join(levels, Seq("window"))
            .select(F.col("window"), F.col("id"), F.col("k").as("coreness"))
        )
    (full, Convergence(AlgorithmName, rounds, done, maxRounds))
end KCore
