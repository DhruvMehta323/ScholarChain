package scholarchain.hadoop

import scholarchain.core.*
import UnitSupport.*

/** UNIT tests of the pure pair expander (hand-checkable sizes), plus the team-size definition. */
class PairExpanderUnitSuite extends munit.FunSuite:
  private val W = "work:dblp:journals/unit/x1"

  private def candidates(n: Int): List[MentionPairCandidate] =
    PairExpander
      .expand(W, 2020, n, prov)
      .map(_.fold(e => fail(s"invalid candidate: $e"), identity))
      .toList

  /** Unordered position pairs (low, high), in emission order. */
  private def positions(n: Int): List[(Int, Int)] =
    candidates(n).map(c =>
      (
        math.min(c.firstPosition, c.secondPosition),
        math.max(c.firstPosition, c.secondPosition),
      )
    )

  test("unit: n = 0 and n = 1 give no pairs") {
    assertEquals(positions(0), Nil)
    assertEquals(positions(1), Nil)
    assertEquals((PairExpander.pairCount(0), PairExpander.pairCount(1)), (0L, 0L))
    assertEquals(PairExpander.expand(W, 2020, -3, prov).toList, Nil)
  }

  test("unit: n = 2 gives exactly the pair (0,1)") {
    assertEquals(positions(2), List((0, 1)))
  }

  test("unit: n = 3 gives exactly (0,1) (0,2) (1,2)") {
    assertEquals(positions(3), List((0, 1), (0, 2), (1, 2)))
  }

  test("unit: n = 5 gives exactly the ten pairs, in order") {
    assertEquals(
      positions(5),
      List((0, 1), (0, 2), (0, 3), (0, 4), (1, 2), (1, 3), (1, 4), (2, 3), (2, 4), (3, 4)),
    )
    assertEquals(PairExpander.pairCount(5), 10L)
  }

  test("unit: pairCount equals n(n-1)/2 and the emitted count for n = 0..40") {
    (0 to 40).foreach { n =>
      assertEquals(PairExpander.pairCount(n), n.toLong * (n - 1).max(0) / 2, s"n=$n")
      assertEquals(
        PairExpander.expand(W, 2020, n, prov).size.toLong,
        PairExpander.pairCount(n),
        s"n=$n",
      )
    }
  }

  test("unit: every pair is canonical (first < second), valid, and carries the provenance") {
    candidates(6).foreach { c =>
      assert(c.pair.first < c.pair.second, s"not canonical: ${c.pair}")
      assertEquals(c.provenance, prov)
      assertEquals(c.workId, W)
      assertEquals(c.year, 2020)
      assertEquals(Ids.mentionId(W, c.firstPosition).toOption, Some(c.pair.first))
      assertEquals(Ids.mentionId(W, c.secondPosition).toOption, Some(c.pair.second))
      assert(summon[Contract[MentionPairCandidate]].validate(c).isRight)
    }
  }

  test("unit: a team of 120 gives 7,140 distinct pairs") {
    val ps = positions(120)
    assertEquals(ps.size, 7140)
    assertEquals(ps.distinct.size, 7140)
    assertEquals(PairExpander.pairCount(120), 7140L)
    assert(ps.forall((a, b) => a < b))
  }

  test(
    "unit: team size = distinct author strings: a repeat gives no self-pair, no duplicate pair"
  ) {
    val w = work(
      parse(okRec("<author>Ann One</author><author>Bob Two</author><author>Ann One</author>"))
    )
    assertEquals(w.authors.size, 2)
    assertEquals(w.droppedDuplicateAuthors, 1)
    val ps = positions(w.authors.size)
    assertEquals(ps, List((0, 1)))
    assert(ps.forall((a, b) => a != b))
  }

  test(
    "unit: the HJ1 reducer counts the repeat as SELF_PAIR_SKIPPED, one mention per distinct name"
  ) {
    val w = work(
      parse(okRec("<author>Ann One</author><author>Bob Two</author><author>Ann One</author>"))
    )
    val out = reduce(new DblpReducer, List((w.key, WirePickle.write(ParsedDblp(100L, w)))))
    assertEquals(out.count(PipelineCounter.SELF_PAIR_SKIPPED), 1L)
    assertEquals(out.table("author-mentions").size, 2)
    assertEquals(out.table("works").size, 1)
  }
