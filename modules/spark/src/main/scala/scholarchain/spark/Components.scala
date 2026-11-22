package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions as F

/** Connected components by iterative min-label propagation, DataFrame only.
  *
  * Generic over a `window` key so one loop serves all graph windows at once (and, with a constant
  * window, the identity graph). Frames:
  *   - `nodes`: `window string, id string`
  *   - `arcs`: `window string, src string, dst string`, BOTH directions of every edge
  *
  * Every node starts with its own ID as label. In each round a node takes the minimum of its own
  * label and its neighbours' labels (one join and one min-aggregation: two shuffles). The fixed
  * point labels every node with the smallest node ID of its component. Rounds are bounded by the
  * component diameter + 1; the loop stops when no label changed or after `maxIterations` (then
  * `Convergence.converged` is false). Each round is checkpointed.
  */
object Components:
  val AlgorithmName = "connected_components_min_label"

  def labels(
      nodes: DataFrame,
      arcs: DataFrame,
      maxIterations: Int,
      ck: Checkpointer,
  ): (DataFrame, Convergence) =
    val a = ck(
      arcs.select(
        F.col("window").as("aw"),
        F.col("src").as("asrc"),
        F.col("dst").as("adst"),
      )
    )
    var cur      = ck(nodes.select(F.col("window"), F.col("id"), F.col("id").as("label")))
    var iter     = 0
    var finished = false
    while !finished && iter < maxIterations do
      val proposals = cur
        .select(
          F.col("window").as("cw"),
          F.col("id").as("cid"),
          F.col("label").as("clabel"),
        )
        .join(a, F.col("cw") === F.col("aw") && F.col("cid") === F.col("asrc"))
        .select(
          F.col("aw").as("window"),
          F.col("adst").as("id"),
          F.col("clabel").as("label"),
        )
      val next = ck(
        cur
          .select("window", "id", "label")
          .union(proposals)
          .groupBy("window", "id")
          .agg(F.min("label").as("label"))
      )
      val changed = DriverCollect.count(
        next
          .join(
            cur.select(F.col("window"), F.col("id"), F.col("label").as("old")),
            Seq("window", "id"),
          )
          .where(F.col("label") =!= F.col("old"))
      )
      cur = next
      iter += 1
      finished = changed == 0L
    (cur, Convergence(AlgorithmName, iter, finished, maxIterations))

  /** `component:<window label>:<smallest member ID>` (see `AnalyticsRecords.scala`). */
  def componentId(window: Column, label: Column): Column =
    F.concat(F.lit("component:"), window, F.lit(":"), label)

  /** Per-window component records from converged labels (`window, id, label`). */
  def membership(labels: DataFrame, windows: DataFrame): DataFrame =
    labels
      .join(F.broadcast(windows), Seq(Cols.Window))
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        componentId(F.col(Cols.Window), F.col("label")).as("componentId"),
        F.col("id").as("researcherId"),
      )

  def sizes(membership: DataFrame): DataFrame =
    membership
      .groupBy(Cols.Window, Cols.WindowStart, Cols.WindowEnd, "componentId")
      .agg(F.count(F.lit(1)).as("size"))
end Components
