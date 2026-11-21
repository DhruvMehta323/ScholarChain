package scholarchain.hadoop

import scholarchain.core.*

import ErItSupport.*
import ItSupport.newRoot

/** HJ6 and HJ7 twice over the same curated records into two roots, the second run with a tiny split
  * size (many mappers), a `max-block-size` of 3 so that every branch (primary, fallback, skipped,
  * exact-id, conflict) is on the path. Every table must be line-for-line equal.
  *
  * Job launches: 10 (two runs of hj6a, hj6b, hj6c, hj6d, hj7).
  */
class ErDeterminismItSuite extends munit.FunSuite:
  private var a: Setup               = scala.compiletime.uninitialized
  private var b: Setup               = scala.compiletime.uninitialized
  private var outA: List[JobOutcome] = Nil
  private var outB: List[JobOutcome] = Nil

  private val recs: List[Rec] =
    (1 to 5).toList.flatMap { i =>
      List(
        dblp(
          s"d$i",
          s"Hydrodynamic instability of stratified shear layer number$i",
          2000 + i * 3,
          List("Mara Quillfeather", s"Coauthor Number$i"),
        ),
        crossref(
          s"c$i",
          s"Hydrodynamic instability of stratified shear layer number$i",
          2000 + i * 3,
          List("Mara Quillfeather", s"Coauthor Number$i"),
        ),
      )
    } ++ List(
      dblp(
        "x1",
        "Exact identifier carrying work alpha",
        2015,
        List("Ned Oakheart"),
        Some("10.9999/it.x"),
      ),
      crossref(
        "x1",
        "Different title for the same identifier",
        2016,
        List("Ned Oakheart"),
        Some("10.9999/it.x"),
      ),
    )

  private def run(label: String, overrides: (String, String)*): (Setup, List[JobOutcome]) =
    val c      = conf(("scholarchain.er.max-block-size" -> "3") +: overrides*)
    val s      = setup(newRoot(label), c)
    val (w, m) = build(s, recs)
    writeCurated(s, w, m)
    writeConflict(s, "10.9999/it.q", List("work:dblp:conf/it/q1", "work:dblp:conf/it/q2"))
    (s, runEr(s, c))

  override def beforeAll(): Unit =
    val ra = run("er-det-a")
    a = ra._1
    outA = ra._2
    val rb = run(
      "er-det-b",
      "mapreduce.input.fileinputformat.split.maxsize" -> "600",
    )
    b = rb._1
    outB = rb._2

  test("both runs succeed and carry the same content-derived run ID configuration") {
    assert((outA ++ outB).forall(_.success))
    assertEquals(a.run.er, b.run.er)
    assert(candidates(a).nonEmpty)
  }

  test("HJ6 and HJ7 tables are identical line for line in both roots (sorted content)") {
    val ta = sortedAll(a)
    val tb = sortedAll(b)
    assertEquals(ta.keySet, tb.keySet)
    ta.foreach((k, v) => assertEquals(v, tb(k), k))
    assert(ta("hj6d/match-candidates").nonEmpty && ta.keys.exists(_.startsWith("outcome=")))
  }

  test(
    "the deterministic counters are identical (framework counters are split-dependent, excluded)"
  ) {
    outA.zip(outB).foreach { (x, y) =>
      assertEquals(x.jobId, y.jobId)
      assertEquals(x.counters, y.counters, x.jobId)
      assertEquals(x.rows, y.rows, x.jobId)
      assertEquals(x.decisions, y.decisions, x.jobId)
    }
  }

  test("no wall-clock value: decision timestamps equal the configured decision epoch") {
    val epoch = a.run.erConfig.decisionEpoch
    decisions(a).foreach((_, d) => assertEquals(d.decisionTimestamp, epoch))
  }
end ErDeterminismItSuite
