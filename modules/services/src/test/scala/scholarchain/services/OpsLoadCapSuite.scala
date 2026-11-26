package scholarchain.services

import scala.concurrent.duration.*

import scholarchain.core.*

import OpsFx.*

/** The render-and-enqueue cap of `OpsLoadMain` (F14-12): every document is rendered, then a
  * deterministic stratified round-robin selection by vector index takes exactly
  * `min(maxVectors, total)` (hard 10000). There is no refusal for a type over the cap.
  */
class OpsLoadCapSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 10.minutes

  private def manifest(o: OpsOutcome): ujson.Value =
    ujson.read(o.manifestJson.getOrElse(fail(s"no manifest json: ${o.status} ${o.detail}")))

  private def assertNothingHappened(r: Rig): Unit =
    assertEquals(r.effects, (0, 0, 0, 0, 0))
    assertEquals(r.manifestStored, None)

  private val Researcher = VectorIndexes.ResearcherProfile
  private val Paper      = VectorIndexes.PaperAbstract

  private def selectedBy(d: ujson.Value, idx: String): Int =
    d("selection")("selectedByType")(idx).num.toInt

  private def availableBy(d: ujson.Value, idx: String): Int =
    d("selection")("availableByType")(idx).num.toInt

  // ---- at the cap ------------------------------------------------------------------------------

  test("exactly 10000 documents: all enqueued, selection selects all, no old keys") {
    val r   = rigWith(sized(100, 9900))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.status, "completed")
    val d = manifest(out)("documents")
    assertEquals(d("cap").num.toInt, 10000)
    assertEquals(d("total").num.toInt, 10000)
    assertEquals(d("uncappedTotal").num.toInt, 10000)
    assertEquals(d("selection")("rule").str, "stratified-round-robin-by-index-v1")
    assertEquals(d("selection")("available").num.toInt, 10000)
    assertEquals(d("selection")("selected").num.toInt, 10000)
    assertEquals(d("selection")("cap").num.toInt, 10000)
    assertEquals(selectedBy(d, Paper), 9900)
    assertEquals(selectedBy(d, Researcher), 100)
    List("summaryDocuments", "paperLimit", "papersDroppedByCap").foreach(k =>
      assert(!d.obj.contains(k), s"removed key still present: $k")
    )
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

  test("10001 documents: exactly 10000 selected, the largest paper id is the one left out") {
    val r   = rigWith(sized(100, 9901))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("uncappedTotal").num.toInt, 10001)
    assertEquals(d("total").num.toInt, 10000)
    assertEquals(d("selection")("available").num.toInt, 10001)
    assertEquals(d("selection")("selected").num.toInt, 10000)
    assertEquals(availableBy(d, Paper), 9901)
    assertEquals(selectedBy(d, Paper), 9900)
    assertEquals(selectedBy(d, Researcher), 100)
    val enqueued = r.queue.documents
    assertEquals(enqueued.size, 10000)
    val papers = enqueued.filter(_.documentType == EmbeddingDocumentTypes.PaperAbstract)
    assertEquals(papers.size, 9900)
    assert(papers.exists(_.entityId == workId(9900)))
    assert(!papers.exists(_.entityId == workId(9901)))
    assertEquals(r.store.textObjects.size, 10000)
  }

  test("a cap of 1: exactly one document, the first non-empty index (researcher)") {
    val r   = rigWith(sized(1, 5))
    val out = r.run(args(maxVectors = 1))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("total").num.toInt, 1)
    assertEquals(d("selection")("selected").num.toInt, 1)
    assertEquals(selectedBy(d, Researcher), 1)
    assertEquals(selectedBy(d, Paper), 0)
    assertEquals(r.queue.documents.size, 1)
  }

  test("the cap is the --max-vectors value, not only the hard maximum") {
    val r   = rigWith(sized(3, 40))
    val out = r.run(args(maxVectors = 20))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("cap").num.toInt, 20)
    assertEquals(d("total").num.toInt, 20)
    assertEquals(d("selection")("cap").num.toInt, 20)
    assertEquals(selectedBy(d, Researcher), 3)
    assertEquals(selectedBy(d, Paper), 17)
    assertEquals(r.queue.documents.size, 20)
  }

  test("under the cap everything is selected") {
    val r   = rigWith(sized(3, 40))
    val out = r.run(args(maxVectors = 43))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("total").num.toInt, 43)
    assertEquals(d("selection")("selected").num.toInt, 43)
    assertEquals(d("selection")("available").num.toInt, 43)
  }

  test("the selection block lists all five indexes, zeros where empty, in the fixed order") {
    val r   = rigWith(sized(3, 4))
    val out = r.run(args(maxVectors = 5))
    assertEquals(out.exitCode, 0, out.detail)
    val s = manifest(out)("documents")("selection")
    assertEquals(s("indexOrder").arr.toList.map(_.str), VectorIndexes.all)
    assert(s("description").str.nonEmpty)
    assertEquals(s("availableByType").obj.keySet.toSet, VectorIndexes.all.toSet)
    assertEquals(s("selectedByType").obj.keySet.toSet, VectorIndexes.all.toSet)
    assertEquals(s("availableByType")(VectorIndexes.CollaborationPath).num.toInt, 0)
    assertEquals(s("selectedByType")(VectorIndexes.TopicInstitutionSummary).num.toInt, 0)
  }

  // ---- a type over the cap no longer refuses -------------------------------------------------------

  test("summaries alone over the cap (10001 of them): selects exactly the cap, no refusal") {
    val r   = rigWith(sized(10001, 0))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.status, "completed")
    val d = manifest(out)("documents")
    assertEquals(d("uncappedTotal").num.toInt, 10001)
    assertEquals(d("total").num.toInt, 10000)
    assertEquals(d("selection")("available").num.toInt, 10001)
    assertEquals(d("selection")("selected").num.toInt, 10000)
    assertEquals(r.queue.documents.size, 10000)
  }

  test("summaries over the cap with papers present: selects exactly cap, papers get a share") {
    val r   = rigWith(sized(6, 4))
    val out = r.run(args(maxVectors = 5))
    assertEquals(out.exitCode, 0, out.detail)
    assertNotEquals(out.status, "cap-exceeded")
    val d = manifest(out)("documents")
    assertEquals(d("total").num.toInt, 5)
    assertEquals(d("selection")("selected").num.toInt, 5)
    assertEquals(selectedBy(d, Researcher), 3)
    assertEquals(selectedBy(d, Paper), 2)
    assertEquals(r.queue.documents.size, 5)
    assertEquals(r.store.textObjects.size, 5)
  }

  test("summaries over the cap in dry-run: verified, manifest has the selection, nothing written") {
    val r   = rigWith(sized(6, 4))
    val out = r.run(args(maxVectors = 5, dry = true))
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.status, "dry-run-verified")
    assertEquals(out.manifestStatus, "dry-run-not-persisted")
    val d = manifest(out)("documents")
    assertEquals(d("selection")("selected").num.toInt, 5)
    assertEquals(d("selection")("available").num.toInt, 10)
    assertEquals(r.queue.sends, 0)
    assertEquals(r.store.textObjects, Nil)
  }

  test("10000 summaries and 3 papers at the hard cap: the papers still get vectors") {
    val r   = rigWith(sized(10000, 3))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("total").num.toInt, 10000)
    assertEquals(selectedBy(d, Paper), 3)
    assertEquals(selectedBy(d, Researcher), 9997)
    assertEquals(r.queue.documents.size, 10000)
    assertEquals(r.queue.documents.count(_.documentType == EmbeddingDocumentTypes.PaperAbstract), 3)
  }

  test("5 summaries and 3 papers at a cap of 5: 3 researchers and 2 papers") {
    val r   = rigWith(sized(5, 3))
    val out = r.run(args(maxVectors = 5))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("total").num.toInt, 5)
    assertEquals(selectedBy(d, Researcher), 3)
    assertEquals(selectedBy(d, Paper), 2)
  }

  // ---- multi-chunk papers --------------------------------------------------------------------------

  private val LongTitle: String = (1 to 900).map(i => s"alpha$i").mkString(" ")

  private def longPapers(n: Int): List[ujson.Obj] =
    (1 to n).toList.map(i => workRow(i, Some(LongTitle)))

  test("multi-chunk papers over the cap: exactly cap chunk documents selected, no refusal") {
    val src = Src(summaries = (1 to 2).toList.map(summaryN), works = longPapers(20))
    val r   = rigWith(src)
    val out = r.run(args(maxVectors = 10))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("total").num.toInt, 10)
    assertEquals(d("selection")("selected").num.toInt, 10)
    assert(d("selection")("available").num.toInt > 10)
    assertEquals(selectedBy(d, Researcher), 2)
    assertEquals(selectedBy(d, Paper), 8)
    assertEquals(r.queue.documents.size, 10)
  }

  test("multi-chunk papers are counted per chunk: the same input fits a larger cap") {
    val src = Src(summaries = (1 to 2).toList.map(summaryN), works = longPapers(4))
    val r   = rigWith(src)
    val out = r.run(args(maxVectors = 10000))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifest(out)("documents")
    assertEquals(d("entitiesByType")("paper_abstract").num.toInt, 4)
    assert(d("byType")("paper_abstract").num.toInt > 4, d.toString)
    assertEquals(
      d("selection")("selected").num.toInt,
      d("selection")("available").num.toInt,
    )
    assertEquals(r.queue.documents.size, d("total").num.toInt)
  }

  test("multi-chunk papers: a cap of 3n over 1 summary and 3n paper chunks selects exactly 3n") {
    val one = rigWith(Src(summaries = List(summaryN(1)), works = longPapers(1)))
    val n   = manifest(one.run(args()))("documents")("byType")("paper_abstract").num.toInt
    assert(n > 1, s"fixture paper must have several chunks, got $n")
    val r   = rigWith(Src(summaries = List(summaryN(1)), works = longPapers(3)))
    val out = r.run(args(maxVectors = 1 + 3 * n))
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(manifest(out)("documents")("total").num.toInt, 1 + 3 * n)
    val r2   = rigWith(Src(summaries = List(summaryN(1)), works = longPapers(3)))
    val out2 = r2.run(args(maxVectors = 3 * n))
    assertEquals(out2.exitCode, 0, out2.detail)
    assertEquals(manifest(out2)("documents")("total").num.toInt, 3 * n)
    assertEquals(r2.queue.documents.size, 3 * n)
  }

  // ---- determinism -----------------------------------------------------------------------------------

  // ---- determinism -----------------------------------------------------------------------------------

  private def shuffled[A](xs: List[A], seed: Long): List[A] =
    new scala.util.Random(seed).shuffle(xs)

  private def runOf(src: Src, cap: Int): (Rig, OpsOutcome) =
    val r = rigWith(src)
    (r, r.run(args(maxVectors = cap)))

  test("selection is deterministic: shuffled works and summaries select the same documents") {
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
    assertEquals(selectedBy(d1, Paper), 40)
    assertEquals(selectedBy(d1, Researcher), 10)
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
