package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions as F
import org.apache.spark.sql.types.MapType

/** The same inputs and configuration give identical outputs. */
class DeterminismSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  /** Set operations are undefined on MAP columns: compare their JSON form. */
  private def comparable(df: DataFrame): DataFrame =
    df.schema.fields.foldLeft(df) { (d, f) =>
      f.dataType match
        case _: MapType => d.withColumn(f.name, F.to_json(F.col(f.name)))
        case _          => d
    }

  test("two runs over the fixture give the same rows in every table (exceptAll both ways)") {
    val a = FixtureRun.computed.tables
    val b = FixtureRun.computedAgain.tables
    assertEquals(a.keySet, b.keySet)
    Tables.all.foreach { t =>
      val x = comparable(a(t.name)); val y = comparable(b(t.name))
      assertEquals(x.schema, y.schema, t.name)
      assertEquals(x.count(), y.count(), t.name)
      assertEquals(x.exceptAll(y).count(), 0L, s"${t.name}: rows only in run 1")
      assertEquals(y.exceptAll(x).count(), 0L, s"${t.name}: rows only in run 2")
    }
  }

  test("the comparison is not vacuous: a changed value is detected") {
    val x = comparable(FixtureRun.computed.tables("coreness"))
    val y = x.withColumn("coreness", F.col("coreness") + 1)
    assert(x.exceptAll(y).count() > 0L)
    assertEquals(x.exceptAll(x).count(), 0L)
  }

  test("two runs report the same iteration counts and convergence for every algorithm") {
    assertEquals(FixtureRun.computed.convergences, FixtureRun.computedAgain.convergences)
    assert(FixtureRun.computed.convergences.nonEmpty)
  }

  test("two runs report the same identity counts, pair counts and partition counts") {
    val a = FixtureRun.computed; val b = FixtureRun.computedAgain
    assertEquals(a.identityCounts, b.identityCounts)
    assertEquals(
      (a.inputPairs, a.selfPairsDropped, a.distinctPairs),
      (b.inputPairs, b.selfPairsDropped, b.distinctPairs),
    )
    assertEquals(a.partitions, b.partitions)
    assertEquals(
      a.sampledPairs.collect().toList.map(_.toString).sorted,
      b.sampledPairs.collect().toList.map(_.toString).sorted,
    )
  }

  test("two exported roots of the same inputs are equal table by table and file by file") {
    val diffs = Compare.compare(TestSpark.spark, FixtureExports.a._1, FixtureExports.b._1, _ => ())
    assertEquals(diffs, Nil)
  }

  test(
    "the sampling seeds are inputs: another seed changes the sampled pairs, the same seed does not"
  ) {
    val ck      = TestSpark.useCheckpointFor("determinism")
    val cfg1    = TestSpark.config(ck)
    val members = TestSpark.frame(
      org.apache.spark.sql.types.StructType
        .fromDDL("window string, id string, componentId string, size long"),
      (1 to 30).toList.map(i => org.apache.spark.sql.Row("w", f"m$i%02d", "c", 30L)),
    )
    def pairs(seed: Long) =
      Sampling
        .samplePairs(members, List("w"), seed, 5)
        .collect()
        .toList
        .map(r => (r.getString(1), r.getString(2)))
        .sorted
    assertEquals(pairs(cfg1.pathSampleSeed), pairs(cfg1.pathSampleSeed))
    assertNotEquals(pairs(cfg1.pathSampleSeed), pairs(7L))
  }
