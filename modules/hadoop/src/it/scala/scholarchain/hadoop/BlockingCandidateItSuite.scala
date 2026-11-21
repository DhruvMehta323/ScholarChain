package scholarchain.hadoop

import java.nio.file.Path as JPath

import scholarchain.core.*
import scholarchain.er.*

import ErItSupport.*
import ItSupport.newRoot

/** HJ6 on SYNTHETIC data with `max-block-size` = 3: oversize routing to the fallback key, a block
  * that stays oversize and is skipped, the cap on every reducer group, and equality with the
  * single-JVM reference `Blocking.generate`.
  *
  * Job launches: 5 (hj6a, hj6b, hj6c, hj6d, hj7), one LocalJobRunner run in `beforeAll`.
  */
class BlockingCandidateItSuite extends munit.FunSuite:
  private val Max                        = 3
  private var s: Setup                   = scala.compiletime.uninitialized
  private var outcomes: List[JobOutcome] = Nil
  private var works: List[WorkRecord]    = Nil
  private var ments: List[AuthorMention] = Nil

  private val name1 = "Alice Quuxford"
  private val name2 = "Boris Bargleton"

  /** Block r1|quuxford|a has 5 members in two five-year buckets (fallback splits it into 2 and 3).
    * Block r1|bargleton|b has 5 members in ONE bucket and no coauthors (fallback is still 5 > 3).
    */
  private val recs: List[Rec] = List(
    dblp("q1", "Zebrafish chromatin accessibility landscapes", 2001, List(name1)),
    dblp("q2", "Ferroelectric domain switching kinetics", 2010, List(name1)),
    crossref("q3", "Zebrafish chromatin accessibility landscapes", 2001, List(name1)),
    crossref("q4", "Ferroelectric domain switching kinetics", 2010, List(name1)),
    crossref("q5", "Hydrothermal vent microbial consortia", 2010, List(name1)),
    dblp("b1", "Basalt weathering geochemical budgets", 2005, List(name2)),
    dblp("b2", "Mangrove sediment carbon burial", 2005, List(name2)),
    crossref("b3", "Orbital resonance exoplanet dynamics", 2005, List(name2)),
    crossref("b4", "Glacial isostatic adjustment rebound", 2005, List(name2)),
    crossref("b5", "Peatland methane flux seasonality", 2005, List(name2)),
  )

  override def beforeAll(): Unit =
    val c = conf("scholarchain.er.max-block-size" -> Max.toString)
    s = setup(newRoot("er-blocking"), c)
    val (w, m) = build(s, recs)
    works = w
    ments = m
    writeCurated(s, w, m)
    outcomes = runEr(s, c)

  private def reference: Blocking.Generation =
    Blocking.generate(EntityViews.build(works, ments), Max, s.run.provAll("reference"))

  test("all five ER jobs succeed") {
    assertEquals(outcomes.size, 5)
    assert(outcomes.forall(_.success), outcomes.map(o => o.jobId -> o.success).toString)
  }

  test("an oversize primary block is routed to its fallback key and finds the pairs there") {
    val keys = candidates(s).map(c => (c.leftId, c.rightId, c.blockKey, c.blockKeyType)).toSet
    val fb   = candidates(s).filter(_.blockKeyType == BlockKeyType.Fallback)
    assert(fb.nonEmpty, "no candidate came from a fallback key")
    assert(fb.forall(_.blockKey.startsWith("r1|quuxford|a|y")), fb.map(_.blockKey).toString)
    // D1-C1 share bucket y400; D2-C2 and D2-C3 share y402: three researcher pairs
    assertEquals(fb.size, 3)
    assertEquals(keys.size, candidates(s).size)
  }

  test(
    "a block that is still oversize after its fallback is skipped and listed in oversize-blocks"
  ) {
    val over = keyed(s, "hj6c", "oversize-blocks").map(_._2)
    assertEquals(over.map(_("blockKey").str), List("r1|bargleton|b|y401"))
    assertEquals(over.head("members").num.toInt, 5)
    assertEquals(over.head("maxBlockSize").num.toInt, Max)
    assertEquals(over.head("blockKeyType").str, "fallback")
    val skipped = ments.filter(_.rawName == name2).map(_.mentionId).toSet
    assert(
      !candidates(s).exists(c => skipped(c.leftId) || skipped(c.rightId)),
      "a skipped block produced a candidate",
    )
    val hj6c = outcomes.find(_.jobId == ErKeys.CandidateJobId).get
    assertEquals(hj6c.c(PipelineCounter.OVERSIZE_BLOCK), 1L)
    val hj6b = outcomes.find(_.jobId == ErKeys.CountJobId).get
    assertEquals(hj6b.c(PipelineCounter.OVERSIZE_KEY), 2L) // both primary researcher blocks
  }

  test("no reducer group produces candidates from more than max-block-size members") {
    val vs                 = views(s)
    def members(k: String) = vs.count(v => Blocking.keys(v).exists(_.key == k))
    candidates(s).foreach(c =>
      assert(members(c.blockKey) <= Max, s"${c.blockKey} has ${members(c.blockKey)}")
    )
    // and the skipped block really is larger than the cap
    keyed(s, "hj6c", "oversize-blocks").foreach((k, _) => assert(members(k) > Max, k))
  }

  test(
    "the candidates equal the single-JVM reference (same pairs, same block keys, same features)"
  ) {
    def sig(c: MatchCandidate) = (c.leftId, c.rightId, c.blockKey, c.blockKeyType, c.features)
    assertEquals(
      candidates(s).map(sig).sortBy(x => x._1 + x._2),
      reference.candidates.map(sig).sortBy(x => x._1 + x._2),
    )
    assertEquals(
      keyed(s, "hj6c", "oversize-blocks").map(k => k._1 -> k._2("members").num.toInt).sorted,
      reference.oversizeBlocks.filter(_._2 > Max).sorted,
    )
  }

  test("every candidate is cross-source, canonical and carries run provenance") {
    candidates(s).foreach { c =>
      assert(c.leftId < c.rightId)
      assertEquals(
        Set(c.provenance.parameters("leftSource"), c.provenance.parameters("rightSource")),
        Set("dblp", "crossref"),
      )
      assertEquals(c.provenance.runId, Some(s.run.runId))
      assertEquals(c.provenance.parameters("er.max-block-size"), Max.toString)
      assert(c.provenance.inputs.nonEmpty && c.provenance.inputs.forall(_.checksum.isDefined))
    }
  }

  test("the entity views equal EntityViews.build over the same records") {
    assertEquals(views(s).sortBy(_.id), EntityViews.build(works, ments).sortBy(_.id))
  }
end BlockingCandidateItSuite
