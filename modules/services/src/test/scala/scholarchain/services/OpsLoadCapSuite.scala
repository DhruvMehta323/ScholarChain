package scholarchain.services

import scala.concurrent.duration.*

import scholarchain.core.*

import OpsFx.*

/** The render-and-enqueue cap of `OpsLoadMain` (human condition of Phase 14 Part A): at most
  * `maxVectors` (hard 10000) documents. Summaries first, then `paperLimit = cap - summaryDocs`
  * papers by ascending work id; still over, or summaries alone over, is a refusal (exit 3) before
  * any write.
  */
class OpsLoadCapSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 10.minutes

  private def manifest(o: OpsOutcome): ujson.Value =
    ujson.read(o.manifestJson.getOrElse(fail(s"no manifest json: ${o.status} ${o.detail}")))

  private def assertNothingHappened(r: Rig): Unit =
    assertEquals(r.effects, (0, 0, 0, 0, 0))
    assertEquals(r.manifestStored, None)

  // ---- at the cap ------------------------------------------------------------------------------

  test("exactly 10000 documents: all enqueued, paperLimit null, papersDroppedByCap 0") {
    val r   = rigWith(sized(100, 9900))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.status, "completed")
    val d = manifest(out)("documents")
    assertEquals(d("cap").num.toInt, 10000)
    assertEquals(d("total").num.toInt, 10000)
    assertEquals(d("uncappedTotal").num.toInt, 10000)
    assertEquals(d("summaryDocuments").num.toInt, 100)
    assertEquals(d("paperLimit"), ujson.Null)
    assertEquals(d("papersDroppedByCap").num.toInt, 0)
    assertEquals(d("byType")("paper_abstract").num.toInt, 9900)
    assertEquals(d("byType")("researcher_profile").num.toInt, 100)
    val p = manifest(out)("producer")
    assertEquals(p("documentsSeen").num.toInt, 10000)
    assertEquals(p("textsWritten").num.toInt, 10000)
    assertEquals(r.queue.documents.size, 10000)
    assertEquals(r.queue.documents.map(_.documentId).distinct.size, 10000)
    assertEquals(r.store.textObjects.size, 10000)
    assertEquals(out.manifestStatus, "written")
  }

  test("10001 documents: paperLimit = 10000 - summaryDocs, total <= 10000, papers dropped") {
    val r   = rigWith(sized(100, 9901))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("uncappedTotal").num.toInt, 10001)
    assertEquals(d("summaryDocuments").num.toInt, 100)
    assertEquals(d("paperLimit").num.toInt, 9900)
    assertEquals(d("total").num.toInt, 10000)
    assert(d("total").num <= 10000)
    assertEquals(d("papersDroppedByCap").num.toInt, 1)
    assert(d("papersDroppedByCap").num > 0)
    val enqueued = r.queue.documents
    assertEquals(enqueued.size, 10000)
    // papers are taken by ascending work id: the last one is the one dropped
    val papers = enqueued.filter(_.documentType == EmbeddingDocumentTypes.PaperAbstract)
    assertEquals(papers.size, 9900)
    assert(papers.exists(_.entityId == workId(9900)))
    assert(!papers.exists(_.entityId == workId(9901)))
    assertEquals(r.store.textObjects.size, 10000)
  }

  test("a cap of 1: one summary fits, everything else is dropped or refused as the rules say") {
    val r   = rigWith(sized(1, 5))
    val out = r.run(args(maxVectors = 1))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("paperLimit").num.toInt, 0)
    assertEquals(d("total").num.toInt, 1)
    assertEquals(d("papersDroppedByCap").num.toInt, 5)
  }

  test("the cap is the --max-vectors value, not only the hard maximum") {
    val r   = rigWith(sized(3, 40))
    val out = r.run(args(maxVectors = 20))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("cap").num.toInt, 20)
    assertEquals(d("paperLimit").num.toInt, 17)
    assertEquals(d("total").num.toInt, 20)
    assertEquals(d("papersDroppedByCap").num.toInt, 23)
    assertEquals(r.queue.documents.size, 20)
  }

  test("under the cap nothing is limited") {
    val r   = rigWith(sized(3, 40))
    val out = r.run(args(maxVectors = 43))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("paperLimit"), ujson.Null)
    assertEquals(d("total").num.toInt, 43)
  }

  // ---- summaries alone over the cap ----------------------------------------------------------------

  test("summaries alone over the cap (10001 of them): exit 3 cap-exceeded, nothing enqueued") {
    val r   = rigWith(sized(10001, 0))
    val out = r.run(args())
    assertEquals(out.exitCode, 3)
    assertEquals(out.status, "cap-exceeded")
    assert(out.detail.contains("summary documents"), out.detail)
    assertNothingHappened(r)
    assertEquals(out.manifestStatus, "not-written")
    assertEquals(out.manifestJson, None)
    assertEquals(out.producer, None)
  }

  test("summaries over the cap with papers present: exit 3, no text object, no queue send") {
    val r   = rigWith(sized(6, 4))
    val out = r.run(args(maxVectors = 5))
    assertEquals(out.exitCode, 3)
    assertEquals(out.status, "cap-exceeded")
    assertNothingHappened(r)
    assertEquals(r.queue.sends, 0)
    assertEquals(r.store.textObjects, Nil)
  }

  test("summaries over the cap in dry-run are refused too") {
    val r   = rigWith(sized(6, 4))
    val out = r.run(args(maxVectors = 5, dry = true))
    assertEquals(out.exitCode, 3)
    assertEquals(out.status, "cap-exceeded")
    assertNothingHappened(r)
  }

  test("summaries exactly at the cap with papers present: paperLimit 0, no paper enqueued") {
    val r   = rigWith(sized(10000, 3))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("paperLimit").num.toInt, 0)
    assertEquals(d("summaryDocuments").num.toInt, 10000)
    assertEquals(d("total").num.toInt, 10000)
    assertEquals(d("papersDroppedByCap").num.toInt, 3)
    assertEquals(r.queue.documents.size, 10000)
    assert(!r.queue.documents.exists(_.documentType == EmbeddingDocumentTypes.PaperAbstract))
  }

  test("summaries exactly at a small cap: paperLimit 0") {
    val r   = rigWith(sized(5, 3))
    val out = r.run(args(maxVectors = 5))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("paperLimit").num.toInt, 0)
    assertEquals(d("total").num.toInt, 5)
    assertEquals(d("papersDroppedByCap").num.toInt, 3)
  }

  // ---- multi-chunk papers --------------------------------------------------------------------------

  private val LongTitle: String = (1 to 900).map(i => s"alpha$i").mkString(" ")

  private def longPapers(n: Int): List[ujson.Obj] =
    (1 to n).toList.map(i => workRow(i, Some(LongTitle)))

  test("multi-chunk papers still over the cap after limiting: exit 3, nothing enqueued") {
    val src = Src(summaries = (1 to 2).toList.map(summaryN), works = longPapers(20))
    val r   = rigWith(src)
    val out = r.run(args(maxVectors = 10))
    assertEquals(out.exitCode, 3, out.detail)
    assertEquals(out.status, "cap-exceeded")
    assert(out.detail.contains("limiting papers"), out.detail)
    assertNothingHappened(r)
  }

  test("multi-chunk papers are counted per chunk: the same input fits a larger cap") {
    val src = Src(summaries = (1 to 2).toList.map(summaryN), works = longPapers(4))
    val r   = rigWith(src)
    val out = r.run(args(maxVectors = 10000))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("entitiesByType")("paper_abstract").num.toInt, 4)
    assert(d("byType")("paper_abstract").num.toInt > 4, d.toString)
    assertEquals(d("paperLimit"), ujson.Null)
    assertEquals(r.queue.documents.size, d("total").num.toInt)
  }

  test("multi-chunk papers over a cap that is met exactly after limiting are accepted") {
    val one = rigWith(Src(summaries = List(summaryN(1)), works = longPapers(1)))
    val n   = manifest(one.run(args()))("documents")("byType")("paper_abstract").num.toInt
    assert(n > 1, s"fixture paper must have several chunks, got $n")
    // 1 summary + 3 papers of n chunks; cap = 1 + n -> limit = n papers... would exceed; cap = 1 + 3n fits
    val r   = rigWith(Src(summaries = List(summaryN(1)), works = longPapers(3)))
    val out = r.run(args(maxVectors = 1 + 3 * n))
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(manifest(out)("documents")("total").num.toInt, 1 + 3 * n)
    // one document short of the need: papers are limited by count, the chunks still exceed the cap
    val r2   = rigWith(Src(summaries = List(summaryN(1)), works = longPapers(3)))
    val out2 = r2.run(args(maxVectors = 3 * n))
    assertEquals(out2.exitCode, 3, out2.detail)
    assertEquals(out2.status, "cap-exceeded")
    assertEquals(r2.effects, (0, 0, 0, 0, 0))
  }

  // ---- determinism -----------------------------------------------------------------------------------

  private def shuffled[A](xs: List[A], seed: Long): List[A] =
    new scala.util.Random(seed).shuffle(xs)

  private def runOf(src: Src, cap: Int): (Rig, OpsOutcome) =
    val r = rigWith(src)
    (r, r.run(args(maxVectors = cap)))

  test("paperLimit is deterministic: shuffled works and summaries select the same documents") {
    val summaries = (1 to 10).toList.map(summaryN)
    val works     = (1 to 200).toList.map(i => workRow(i))
    val (r1, o1)  = runOf(Src(summaries = summaries, works = works), 50)
    val (r2, o2)  = runOf(
      Src(summaries = shuffled(summaries, 7L), works = shuffled(works, 11L)),
      50,
    )
    val (r3, o3) = runOf(Src(summaries = summaries.reverse, works = works.reverse), 50)
    List(o1, o2, o3).foreach(o => assertEquals(o.exitCode, 0, o.detail))
    val d1 = manifest(o1)("documents")
    assertEquals(d1("paperLimit").num.toInt, 40)
    assertEquals(d1("total").num.toInt, 50)

    def selected(r: Rig) =
      r.queue.documents.filter(_.documentType == "paper_abstract").map(_.entityId).sorted
    assertEquals(selected(r1), (1 to 40).toList.map(workId))
    assertEquals(selected(r2), selected(r1))
    assertEquals(selected(r3), selected(r1))

    def docIds(r: Rig)        = r.queue.documents.map(_.documentId).sorted
    def jobIds(o: OpsOutcome) = o.producer.map(_.jobIds).getOrElse(Nil)
    assertEquals(docIds(r2), docIds(r1))
    assertEquals(docIds(r3), docIds(r1))
    assertEquals(jobIds(o2), jobIds(o1))
    assertEquals(jobIds(o3), jobIds(o1))
    assert(jobIds(o1).nonEmpty)
    assertEquals(r2.store.textObjects, r1.store.textObjects)
    assertEquals(
      stripInputs(manifest(o2)),
      stripInputs(manifest(o1)),
    )
    assertEquals(
      stripInputs(manifest(o3)),
      stripInputs(manifest(o1)),
    )
  }

  test("the same input twice (fresh stores) gives the same job ids and an identical manifest") {
    val src      = sized(5, 30)
    val (r1, o1) = runOf(src, 20)
    val (r2, o2) = runOf(src, 20)
    assertEquals(o1.producer.map(_.jobIds), o2.producer.map(_.jobIds))
    assertEquals(manifest(o1), manifest(o2))
    assertEquals(
      r1.manifestStored.map(new String(_, "UTF-8")),
      r2.manifestStored.map(new String(_, "UTF-8")),
    )
  }

  test("a different cap selects a prefix of the same ordered papers") {
    val works    = (1 to 60).toList.map(i => workRow(i))
    val (ra, oa) = runOf(Src(works = works), 10)
    val (rb, ob) = runOf(Src(works = works), 30)
    List(oa, ob).foreach(o => assertEquals(o.exitCode, 0, o.detail))
    def papers(r: Rig) =
      r.queue.documents.filter(_.documentType == "paper_abstract").map(_.entityId).toSet
    assert(papers(ra).subsetOf(papers(rb)))
    assert(papers(ra).size < papers(rb).size)
  }
