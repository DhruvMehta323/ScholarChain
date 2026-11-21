package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.apache.spark.scheduler.{
  SparkListener,
  SparkListenerJobStart,
  SparkListenerStageCompleted,
  SparkListenerTaskEnd,
}
import org.apache.spark.sql.SparkSession

/** A SparkListener that records stage count, task count, shuffle read and write bytes and stage
  * durations, and writes them to `run-metrics.json`. Durations are wall-clock by nature, so this
  * file is a measurement, NOT output data: it is excluded from the compare tool and from the run
  * ID. Stage durations are `completionTime - submissionTime` of Spark's own stage info.
  */
final class RunMetricsListener extends SparkListener:
  final case class StageRow(
      stageId: Int,
      attempt: Int,
      name: String,
      numTasks: Int,
      durationMs: Long,
      shuffleReadBytes: Long,
      shuffleWriteBytes: Long,
      failed: Boolean,
  )

  private val stages = scala.collection.mutable.ArrayBuffer.empty[StageRow]
  private var tasks  = 0L
  private var jobs   = 0L

  override def onJobStart(e: SparkListenerJobStart): Unit = synchronized { jobs += 1 }

  override def onTaskEnd(e: SparkListenerTaskEnd): Unit = synchronized { tasks += 1 }

  override def onStageCompleted(e: SparkListenerStageCompleted): Unit = synchronized {
    val si  = e.stageInfo
    val tm  = Option(si.taskMetrics)
    val dur = (for s <- si.submissionTime; c <- si.completionTime yield c - s).getOrElse(0L)
    stages += StageRow(
      si.stageId,
      si.attemptNumber(),
      si.name,
      si.numTasks,
      dur,
      tm.map(_.shuffleReadMetrics.totalBytesRead).getOrElse(0L),
      tm.map(_.shuffleWriteMetrics.bytesWritten).getOrElse(0L),
      si.failureReason.isDefined,
    )
  }

  /** Total event count, used to wait until the asynchronous listener bus went quiet. */
  def eventCount: Long = synchronized { stages.size.toLong * 1000003L + tasks + jobs }

  def toJson: ujson.Obj = synchronized {
    ujson.Obj(
      "jobCount"             -> ujson.Num(jobs.toDouble),
      "stageCount"           -> stages.size,
      "taskCount"            -> ujson.Num(tasks.toDouble),
      "shuffleReadBytes"     -> ujson.Num(stages.map(_.shuffleReadBytes).sum.toDouble),
      "shuffleWriteBytes"    -> ujson.Num(stages.map(_.shuffleWriteBytes).sum.toDouble),
      "stageDurationMsTotal" -> ujson.Num(stages.map(_.durationMs).sum.toDouble),
      "note" -> ("Wall-clock measurements (durations) of one execution; not part of the compared output. " +
        "Shuffle read counts local and remote bytes; the stage count includes every executed stage, " +
        "checkpoint and write stages included."),
      "stages" -> ujson.Arr.from(
        stages.toList
          .sortBy(s => (s.stageId, s.attempt))
          .map(s =>
            ujson.Obj(
              "stageId"           -> s.stageId,
              "attempt"           -> s.attempt,
              "name"              -> s.name,
              "numTasks"          -> s.numTasks,
              "durationMs"        -> ujson.Num(s.durationMs.toDouble),
              "shuffleReadBytes"  -> ujson.Num(s.shuffleReadBytes.toDouble),
              "shuffleWriteBytes" -> ujson.Num(s.shuffleWriteBytes.toDouble),
              "failed"            -> s.failed,
            )
          )
      ),
    )
  }
end RunMetricsListener

object RunMetrics:
  def attach(spark: SparkSession): RunMetricsListener =
    val l = new RunMetricsListener
    spark.sparkContext.addSparkListener(l)
    l

  /** Waits (bounded, 10 s) until no new listener events arrive for 600 ms, then writes the file. */
  def write(listener: RunMetricsListener, path: Path): ujson.Obj =
    var last   = -1L
    var stable = 0
    var waited = 0
    while stable < 3 && waited < 50 do
      Thread.sleep(200)
      waited += 1
      val now = listener.eventCount
      if now == last then stable += 1 else { stable = 0; last = now }
    val json = listener.toJson
    Files.createDirectories(path.getParent)
    Files.writeString(path, ujson.write(json, indent = 2) + "\n", StandardCharsets.UTF_8)
    json
end RunMetrics
