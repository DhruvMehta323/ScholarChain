package scholarchain.hadoop

import scholarchain.core.*
import scholarchain.er.*

import ErItSupport.*
import ItSupport.newRoot

/** HJ6b block-size counting: the combiner is configured, it runs, and the counts it feeds the
  * reducer are exactly the independent per-key counts.
  *
  * Job launches: 5 (hj6a, hj6b, hj6c, hj6d, hj7), one LocalJobRunner run in `beforeAll`.
  */
class BlockCombinerItSuite extends munit.FunSuite:
  private var s: Setup                   = scala.compiletime.uninitialized
  private var outcomes: List[JobOutcome] = Nil
  private var works: List[WorkRecord]    = Nil
  private var ments: List[AuthorMention] = Nil

  private val recs: List[Rec] =
    (1 to 4).toList.flatMap { i =>
      List(
        dblp(
          s"d$i",
          s"Topological insulator edge transport study number$i",
          2000 + i,
          List("Cara Delmonte"),
        ),
        crossref(
          s"c$i",
          s"Plasmonic nanoantenna fabrication protocol number$i",
          2000 + i,
          List("Cara Delmonte"),
        ),
      )
    } ++ List(
      dblp("s1", "Singular value decomposition acceleration", 2012, List("Dmitri Volkov")),
      crossref("s2", "Quasar luminosity function evolution", 2013, List("Dmitri Volkov")),
    )

  override def beforeAll(): Unit =
    val c = conf()
    s = setup(newRoot("er-combiner"), c)
    val (w, m) = build(s, recs)
    works = w
    ments = m
    writeCurated(s, w, m)
    outcomes = runEr(s, c)

  private def count = outcomes.find(_.jobId == ErKeys.CountJobId).get

  test("the combiner is configured on the counting job and the job succeeds") {
    assert(outcomes.forall(_.success))
    assert(count.framework.contains("COMBINE_INPUT_RECORDS"), count.framework.toString)
  }

  test(
    "the combiner ran: fewer records leave it than enter it, and not fewer than the distinct keys"
  ) {
    val in        = count.framework("COMBINE_INPUT_RECORDS")
    val out       = count.framework("COMBINE_OUTPUT_RECORDS")
    val vs        = views(s)
    val primaries = vs.flatMap(Blocking.primary).map(_.key)
    assertEquals(in, primaries.size.toLong) // every mapper output passes through the combiner
    assert(out < in, s"combiner did not reduce: in=$in out=$out")
    assert(out >= primaries.distinct.size, s"out=$out distinct=${primaries.distinct.size}")
  }

  test("the counts equal an independent per-key count of the primary block keys") {
    val expected = views(s)
      .flatMap(Blocking.primary)
      .map(_.key)
      .groupBy(identity)
      .map((k, v) => k -> v.size.toLong)
    val got = keyed(s, "hj6b", "block-sizes").map((k, j) => k -> j("members").num.toLong).toMap
    assertEquals(got, expected)
    assertEquals(count.c(PipelineCounter.BLOCK_KEYS), expected.size.toLong)
    // the largest block is the six-work author (4 dblp + 4 crossref mentions of one name)
    assertEquals(got.values.max, 8L)
    assertEquals(got("r1|delmonte|c"), 8L)
  }

  test("a view without a primary key is counted, not blocked") {
    val unblocked = count.c(PipelineCounter.UNBLOCKED_VIEW)
    assertEquals(unblocked, views(s).count(v => Blocking.primary(v).isEmpty).toLong)
    assertEquals(
      count.c(PipelineCounter.INPUT_RECORDS),
      views(s).size.toLong,
    )
  }
end BlockCombinerItSuite
