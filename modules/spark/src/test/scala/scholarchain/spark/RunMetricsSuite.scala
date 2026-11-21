package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The SparkListener that writes run-metrics.json. */
class RunMetricsSuite extends munit.FunSuite:

  private val spark = TestSpark.spark

  test("the listener counts jobs, stages, tasks and shuffle bytes of a real shuffle job") {
    val listener = RunMetrics.attach(spark)
    try
      val n =
        spark.range(0L, 20000L, 1L, 4).selectExpr("id % 10 as k", "id").groupBy("k").count().count()
      assertEquals(n, 10L)
      val path = TestSpark.freshDir("metrics").resolve("run-metrics.json")
      val json = RunMetrics.write(listener, path)
      assert(json("jobCount").num >= 1, json.toString)
      assert(json("stageCount").num >= 2, "a group-by runs at least a map and a reduce stage")
      assert(json("taskCount").num >= 5)
      assert(json("shuffleWriteBytes").num > 0 && json("shuffleReadBytes").num > 0, json.toString)
      assertEquals(json("stages").arr.size, json("stageCount").num.toInt)
      assert(json("stages").arr.forall(s => !s("failed").bool))
      val back = ujson.read(Files.readString(path, StandardCharsets.UTF_8))
      assertEquals(back("stageCount").num, json("stageCount").num)
    finally spark.sparkContext.removeSparkListener(listener)
  }

  test("a listener that saw no job reports zeros") {
    val listener = new RunMetricsListener
    val json     = listener.toJson
    assertEquals(
      (
        json("jobCount").num,
        json("stageCount").num,
        json("taskCount").num,
        json("shuffleReadBytes").num,
      ),
      (0.0, 0.0, 0.0, 0.0),
    )
  }
