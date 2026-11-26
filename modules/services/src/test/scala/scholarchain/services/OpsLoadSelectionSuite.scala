package scholarchain.services

import scholarchain.core.*
import scholarchain.rag.RenderedDocument

/** `OpsLoad.selectStratified` (F14-12): the pure, deterministic stratified round-robin selection by
  * vector index. Feature tests on synthetic rendered documents.
  */
class OpsLoadSelectionSuite extends munit.FunSuite:
  import EmbeddingDocumentTypes as T

  private val Window = GraphWindow(2000, 2004)

  private def doc(typ: String, id: String): RenderedDocument =
    RenderedDocument(
      EmbeddingDocument(
        1,
        id,
        typ,
        s"entity:$id",
        Window,
        "s3://b/rag/text/x.txt",
        "h",
        0,
        1,
        "run:" + "0" * 64,
      ),
      s"text $id",
      "",
    )

  private def many(typ: String, prefix: String, n: Int): List[RenderedDocument] =
    (0 until n).toList.map(i => doc(typ, f"$prefix%s$i%03d"))

  private def ids(xs: List[RenderedDocument]): List[String] = xs.map(_.document.documentId)

  // index order: researcher, community, paper, path, topic
  private def world(r: Int, c: Int, p: Int, path: Int, t: Int): List[RenderedDocument] =
    many(T.ResearcherProfile, "r", r) ++ many(T.CommunityProfile, "c", c) ++
      many(T.PaperAbstract, "p", p) ++ many(T.PathSummary, "x", path) ++ many(
        T.TopicSummary,
        "t",
        t,
      )

  private def sel(
      docs: List[RenderedDocument],
      cap: Int,
  ): (List[RenderedDocument], OpsLoad.SelectionStats) =
    OpsLoad.selectStratified(docs, cap)

  test("rule name and index order are the documented constants") {
    val (_, s) = sel(world(1, 1, 1, 1, 1), 5)
    assertEquals(s.rule, "stratified-round-robin-by-index-v1")
    assertEquals(OpsLoad.SelectionRule, "stratified-round-robin-by-index-v1")
    assertEquals(
      s.indexOrder,
      List(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1",
      ),
    )
    assertEquals(s.indexOrder, VectorIndexes.all)
  }

  test("determinism: shuffled input gives the same documents in the same order and stats") {
    val docs       = world(30, 4, 200, 7, 12)
    val (base, bs) = sel(docs, 60)
    List(1L, 2L, 3L, 99L).foreach { seed =>
      val shuffled  = new scala.util.Random(seed).shuffle(docs)
      val (got, gs) = sel(shuffled, 60)
      assertEquals(ids(got), ids(base))
      assertEquals(gs, bs)
    }
    val (rev, rs) = sel(docs.reverse, 60)
    assertEquals(ids(rev), ids(base))
    assertEquals(rs, bs)
  }

  test("fairness: five types of very different sizes, cap 10, every non-empty type gets >= 1") {
    val docs     = world(500, 2, 40, 9, 1)
    val (out, s) = sel(docs, 10)
    assertEquals(out.size, 10)
    assertEquals(s.selected, 10)
    assertEquals(s.available, 552)
    assert(s.selectedByIndex.values.forall(_ >= 1), s.selectedByIndex.toString)
    // sizes 500,2,40,9,1 and cap 10: round 0 (5 docs), round 1 (4 docs: topic exhausted) = 9, then 1 researcher
    assertEquals(s.selectedByIndex(VectorIndexes.ResearcherProfile), 3)
    assertEquals(s.selectedByIndex(VectorIndexes.CollaborationCommunity), 2)
    assertEquals(s.selectedByIndex(VectorIndexes.PaperAbstract), 2)
    assertEquals(s.selectedByIndex(VectorIndexes.CollaborationPath), 2)
    assertEquals(s.selectedByIndex(VectorIndexes.TopicInstitutionSummary), 1)
  }

  test("fairness: counts differ by at most 1 among types that are not exhausted") {
    val sizes = List(500, 2, 40, 9, 30)
    val docs  = world(sizes(0), sizes(1), sizes(2), sizes(3), sizes(4))
    val order = VectorIndexes.all
    (1 to 120).foreach { cap =>
      val (_, s) = sel(docs, cap)
      val live   = order.zip(List(sizes(0), sizes(1), sizes(2), sizes(3), sizes(4))).collect {
        case (idx, size) if s.selectedByIndex(idx) < size => s.selectedByIndex(idx)
      }
      if live.nonEmpty then assert(live.max - live.min <= 1, s"cap $cap: ${s.selectedByIndex}")
      // and every exhausted type was taken in full
      order
        .zip(sizes)
        .foreach((idx, size) => assert(s.selectedByIndex(idx) <= size, s"cap $cap $idx"))
    }
  }

  test("exact example: sizes (7,1,3,0,2), cap 8 gives the expected ids in order") {
    val (out, s) = sel(world(7, 1, 3, 0, 2), 8)
    assertEquals(
      ids(out),
      List("r000", "c000", "p000", "t000", "r001", "p001", "t001", "r002"),
    )
    assertEquals(s.available, 13)
    assertEquals(s.selected, 8)
    assertEquals(s.cap, 8)
    assertEquals(
      s.availableByIndex,
      Map(
        VectorIndexes.ResearcherProfile       -> 7,
        VectorIndexes.CollaborationCommunity  -> 1,
        VectorIndexes.PaperAbstract           -> 3,
        VectorIndexes.CollaborationPath       -> 0,
        VectorIndexes.TopicInstitutionSummary -> 2,
      ),
    )
    assertEquals(
      s.selectedByIndex,
      Map(
        VectorIndexes.ResearcherProfile       -> 3,
        VectorIndexes.CollaborationCommunity  -> 1,
        VectorIndexes.PaperAbstract           -> 2,
        VectorIndexes.CollaborationPath       -> 0,
        VectorIndexes.TopicInstitutionSummary -> 2,
      ),
    )
  }

  test("cap >= total returns every document; cap 0 returns none") {
    val docs      = world(7, 1, 3, 0, 2)
    val (all, sa) = sel(docs, 13)
    assertEquals(ids(all).sorted, ids(docs).sorted)
    assertEquals(sa.selected, 13)
    val (more, sm) = sel(docs, 1000)
    assertEquals(ids(more).sorted, ids(docs).sorted)
    assertEquals(sm.selected, 13)
    assertEquals(sm.selectedByIndex, sm.availableByIndex)
    val (none, sn) = sel(docs, 0)
    assertEquals(none, Nil)
    assertEquals(sn.selected, 0)
    assertEquals(sn.available, 13)
    assert(sn.selectedByIndex.values.forall(_ == 0))
  }

  test("empty input selects nothing and does not fail") {
    val (out, s) = sel(Nil, 5)
    assertEquals(out, Nil)
    assertEquals(s.available, 0)
    assertEquals(s.selected, 0)
    assertEquals(s.availableByIndex.keySet, VectorIndexes.all.toSet)
  }

  test("within a type the smallest document ids are chosen") {
    val docs     = world(10, 10, 10, 10, 10)
    val (out, _) = sel(docs, 15) // 3 rounds of 5
    val byType   = out.groupBy(_.document.documentType).view.mapValues(ids).toMap
    assertEquals(byType(T.ResearcherProfile).sorted, List("r000", "r001", "r002"))
    assertEquals(byType(T.PaperAbstract).sorted, List("p000", "p001", "p002"))
    assertEquals(byType(T.TopicSummary).sorted, List("t000", "t001", "t002"))
    // an unsorted, shuffled input still yields the smallest
    val (out2, _) = sel(new scala.util.Random(5L).shuffle(docs), 15)
    assertEquals(ids(out2), ids(out))
  }

  test("stats maps include all five indexes, zeros where empty") {
    val (_, s) = sel(world(4, 0, 0, 0, 2), 3)
    assertEquals(s.availableByIndex.keySet, VectorIndexes.all.toSet)
    assertEquals(s.selectedByIndex.keySet, VectorIndexes.all.toSet)
    assertEquals(s.availableByIndex(VectorIndexes.CollaborationCommunity), 0)
    assertEquals(s.availableByIndex(VectorIndexes.PaperAbstract), 0)
    assertEquals(s.selectedByIndex(VectorIndexes.CollaborationPath), 0)
    assertEquals(s.availableByIndex(VectorIndexes.ResearcherProfile), 4)
    assertEquals(s.selectedByIndex(VectorIndexes.ResearcherProfile), 2)
    assertEquals(s.selectedByIndex(VectorIndexes.TopicInstitutionSummary), 1)
    assertEquals(s.selectedByIndex.values.sum, s.selected)
    assertEquals(s.availableByIndex.values.sum, s.available)
  }

  test("a negative cap selects nothing") {
    val (out, s) = sel(world(2, 2, 2, 2, 2), -3)
    assertEquals(out, Nil)
    assertEquals(s.selected, 0)
  }

  test("an unknown document type goes last, by name, and is reported in the stats") {
    val docs     = world(1, 0, 1, 0, 0) ++ List(doc("zeta_type", "z000"), doc("alpha_type", "a000"))
    val (out, s) = sel(docs, 10)
    assertEquals(ids(out), List("r000", "p000", "a000", "z000"))
    assertEquals(s.indexOrder.take(5), VectorIndexes.all)
    assertEquals(s.indexOrder.drop(5), List("alpha_type", "zeta_type"))
    assertEquals(s.available, 4)
    assertEquals(s.selected, 4)
  }
