package scholarchain.spark

import org.apache.spark.sql.DataFrame

/** Reliable (eager) checkpointing of iterative loops and of frames that are reused. A checkpoint
  * writes the frame to `dir` and truncates its lineage, so a loop of N iterations never builds an
  * N-deep plan. The directory comes from `scholarchain.spark.checkpoint-dir` (under `target/`).
  * Spark appends its own unique sub-directory per application, so concurrent runs do not collide.
  */
final class Checkpointer(dir: String):
  def apply(df: DataFrame): DataFrame =
    val sc = df.sparkSession.sparkContext
    if sc.getCheckpointDir.isEmpty then sc.setCheckpointDir(dir)
    df.checkpoint(true)

/** Outcome of an iterative algorithm: how many iterations ran, whether it reached its fixed point
  * (or its stopping rule), and the configured cap.
  */
final case class Convergence(algorithm: String, iterations: Int, converged: Boolean, cap: Int)
