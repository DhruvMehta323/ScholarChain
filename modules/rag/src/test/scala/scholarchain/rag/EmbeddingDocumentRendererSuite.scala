package scholarchain.rag

import scholarchain.core.*

import RagFx.*

/** Rendering: determinism, ordering, pointers, paper text, skips, numbers. */
class EmbeddingDocumentRendererSuite extends munit.FunSuite:

  private val ada   = researcher("Ada Lovelace")
  private val alan  = researcher("Alan Turing")
  private val grace = researcher("Grace Hopper")

  private val summaries: List[SemanticSummary] = List(
    summary("Ada Lovelace published 12 papers with 3 coauthors between 2000 and 2004.", ada),
    summary(
      "Alan Turing published 7 papers with 2 coauthors.",
      alan,
      window = GraphWindow(2005, 2009),
    ),
    summary("Alan Turing published 9 papers with 4 coauthors.", alan),
    summary(
      "Community of 5 researchers around Grace Hopper.",
      "community:2000-2004:" + grace,
      EmbeddingDocumentTypes.CommunityProfile,
    ),
    summary(
      "Path of length 2 from Ada to Grace via Alan.",
      "path:" + ada,
      EmbeddingDocumentTypes.PathSummary,
    ),
    summary(
      "Topic compilers linked to 14 works.",
      "topic:dblp:compilers",
      EmbeddingDocumentTypes.TopicSummary,
      GraphWindows.AllTime,
    ),
  )

  private val venues = List(venue("venue:dblp:conf/pldi", "PLDI 2020"))
  private val topics =
    List(topic("topic:dblp:compilers", "Compilers"), topic("topic:dblp:graphs", "Graph Theory"))
  private val works = List(
    work(
      "work:dblp:conf/pldi/Lov01",
      "On Analytical Engines",
      2001,
      Some("10.1000/abc123"),
      Some("venue:dblp:conf/pldi"),
      List("topic:dblp:graphs", "topic:dblp:compilers"),
    ),
    work("work:dblp:conf/pldi/Tur02", "Computable Numbers", 2002),
  )

  private def pairs(r: RenderResult) = r.pairs

  test(
    "rendering is deterministic: the same input in any order gives the identical documents and texts"
  ) {
    val base = pairs(render(summaries, works, venues, topics))
    assert(base.nonEmpty)
    assertEquals(
      pairs(render(summaries.reverse, works.reverse, venues.reverse, topics.reverse)),
      base,
    )
    assertEquals(
      pairs(render(scala.util.Random(7).shuffle(summaries), works, venues, topics)),
      base,
    )
    assertEquals(pairs(render(summaries, works, venues, topics)), base, "second run")
  }

  test("output is sorted by document type order, entity, window and chunk ordinal") {
    val docs      = render(summaries, works, venues, topics).documents.map(_.document)
    val typeOrder = EmbeddingDocumentTypes.all.zipWithIndex.toMap
    val keys      = docs.map(d =>
      (typeOrder(d.documentType), d.entityId, d.window.startYear, d.window.endYear, d.chunkOrdinal)
    )
    assertEquals(keys, keys.sorted)
    assertEquals(docs.map(_.documentType).distinct, EmbeddingDocumentTypes.all)
  }

  test(
    "every document is valid, hashed from its text, and points at the content-addressed S3 text"
  ) {
    val r = render(summaries, works, venues, topics)
    assertEquals(r.skipped, Nil)
    r.documents.foreach { d =>
      assert(
        summon[Contract[EmbeddingDocument]].validate(d.document).isRight,
        d.document.documentId,
      )
      assertEquals(d.document.textHash, Hashing.textHash(d.text))
      assertEquals(d.document.textS3Uri, s"s3://$bucket/rag/text/${Hashing.textHash(d.text)}.txt")
      assertEquals(d.document.sourceRunId, run1)
    }
  }

  test("the four summary types use the Phase 06 text verbatim") {
    val r = render(summaries)
    assertEquals(r.documents.map(_.text).toSet, summaries.map(_.text).toSet)
    assertEquals(r.documents.size, summaries.size)
  }

  test("a summary keeps its Phase 06 window; a topic summary keeps the all-time window") {
    val r        = render(summaries)
    val byEntity = r.documents.map(_.document).map(d => d.documentType -> d.window).toSet
    assert(byEntity.contains(EmbeddingDocumentTypes.TopicSummary -> GraphWindows.AllTime))
    assert(byEntity.contains(EmbeddingDocumentTypes.ResearcherProfile -> GraphWindow(2005, 2009)))
  }

  test("document IDs equal the derived <type>:<entity>:<window>:<ordinal>") {
    render(summaries, works, venues, topics).documents.foreach { d =>
      val x = d.document
      assertEquals(
        x.documentId,
        s"${x.documentType}:${x.entityId}:${GraphWindow.label(x.window)}:${x.chunkOrdinal}",
      )
    }
  }

  test("a duplicate (type, entity, window) with identical text is dropped") {
    val s = summary("Same text with 5 numbers 1 2 3 4 5.", ada)
    val r = render(List(s, s))
    assertEquals(r.documents.size, 1)
    assertEquals(r.skipped, Nil)
  }

  test(
    "a duplicate (type, entity, window) with different text is skipped with a fixed reason, not merged"
  ) {
    val a = summary("First text for the entity, 1 paper.", ada)
    val b = summary("Second text for the entity, 2 papers.", ada)
    val r = render(List(a, b))
    assertEquals(r.documents.size, 1)
    assertEquals(
      r.skipped,
      List(
        RenderSkip(EmbeddingDocumentTypes.ResearcherProfile, ada, "duplicate-key-different-text")
      ),
    )
  }

  test("which duplicate wins does not depend on the input order (determinism)") {
    val a        = summary("First text for the entity, 1 paper.", ada)
    val b        = summary("Second text for the entity, 2 papers.", ada)
    val forward  = render(List(a, b)).pairs
    val backward = render(List(b, a)).pairs
    assertEquals(
      backward,
      forward,
      "the kept document must not depend on the order of the input rows",
    )
  }

  test("skips carry only a type, an entity ID and a fixed reason, never row content") {
    val blank   = summary("   ", ada)
    val unknown = summary("Some text 42.", alan, "abstract_text")
    val r       = render(List(blank, unknown))
    assertEquals(r.documents, Nil)
    assertEquals(
      r.skipped.map(_.reason).toSet,
      Set("blank-text", "unknown-document-type"),
    )
    r.skipped.foreach(s => assert(!s.toString.contains("Some text")))
  }

  test("a bucket name that is not a valid S3 bucket makes every document fail the core contract") {
    val r = EmbeddingDocumentRenderer.renderDetailed(
      summaries,
      Nil,
      Nil,
      Nil,
      run1,
      TextUris.contentAddressed("ab"),
    )
    assertEquals(r.documents, Nil)
    assertEquals(r.skipped.map(_.reason).toSet, Set("invalid-document"))
    assertEquals(r.skipped.size, summaries.size)
  }

  test("TextUris.contentAddressed: default and custom prefixes, slashes normalised") {
    assertEquals(TextUris.contentAddressed("my-bucket")("ff"), "s3://my-bucket/rag/text/ff.txt")
    assertEquals(TextUris.contentAddressed("my-bucket", "/a/b/")("ff"), "s3://my-bucket/a/b/ff.txt")
  }

  test("documentsByType and entitiesByType count chunk-level documents and distinct entities") {
    val r = render(summaries)
    assertEquals(r.documentsByType(EmbeddingDocumentTypes.ResearcherProfile), 3)
    assertEquals(r.entitiesByType(EmbeddingDocumentTypes.ResearcherProfile), 2)
    assertEquals(r.documentsByType.get(EmbeddingDocumentTypes.PaperAbstract), None)
  }

  // ---- paper text ---------------------------------------------------------------------------------

  private def paper(r: RenderResult, id: String) =
    r.documents.find(_.document.entityId == id).getOrElse(fail(s"no paper $id")).text

  test("paper text has the documented lines, topics sorted, and no abstract line") {
    val r = render(Nil, works, venues, topics)
    assertEquals(
      paper(r, "work:dblp:conf/pldi/Lov01"),
      List(
        "Paper: work:dblp:conf/pldi/Lov01",
        "Title: On Analytical Engines",
        "Year: 2001",
        "Venue: PLDI 2020",
        "Topics: Compilers; Graph Theory",
        "DOI: 10.1000/abc123",
      ).mkString("\n"),
    )
    assert(!paper(r, "work:dblp:conf/pldi/Lov01").toLowerCase.contains("abstract"))
  }

  test("a paper without venue, topics or DOI omits those lines") {
    val r = render(Nil, works, venues, topics)
    assertEquals(
      paper(r, "work:dblp:conf/pldi/Tur02"),
      "Paper: work:dblp:conf/pldi/Tur02\nTitle: Computable Numbers\nYear: 2002",
    )
  }

  test("a paper whose venue or topic ID is unknown omits the line (nothing is invented)") {
    val w = work(
      "work:dblp:conf/x/Z03",
      "Lost Links",
      2003,
      None,
      Some("venue:dblp:missing"),
      List("topic:dblp:missing"),
    )
    assertEquals(
      paper(render(Nil, List(w), venues, topics), w.workId),
      "Paper: work:dblp:conf/x/Z03\nTitle: Lost Links\nYear: 2003",
    )
  }

  test(
    "paper documents use the all-time window, document type paper_abstract and the work ID as entity"
  ) {
    val d = render(Nil, works, venues, topics).documents.map(_.document)
    assert(d.forall(_.documentType == EmbeddingDocumentTypes.PaperAbstract))
    assert(d.forall(_.window == GraphWindows.AllTime))
    assertEquals(d.map(_.entityId), List("work:dblp:conf/pldi/Lov01", "work:dblp:conf/pldi/Tur02"))
  }

  test("paperLimit takes the first N works by work ID") {
    val r = render(Nil, works.reverse, venues, topics, paperLimit = Some(1))
    assertEquals(r.documents.map(_.document.entityId), List("work:dblp:conf/pldi/Lov01"))
  }

  // ---- numbers ---------------------------------------------------------------------------------------

  test("every number in every rendered text exists in its source row (NumberCheck is Right)") {
    val r = render(summaries, works, venues, topics)
    assert(r.documents.nonEmpty)
    r.documents.foreach(d => assertEquals(NumberCheck.check(d), Right(()), d.document.documentId))
  }

  test("NumberCheck: a doctored text with an extra number is rejected and names that number") {
    val d        = render(summaries).documents.head
    val doctored = d.copy(text = d.text + " It also has 987654 awards.")
    assertEquals(NumberCheck.check(doctored), Left(List("987654")))
  }

  test("NumberCheck: decimals are one number; a number cut in two is reported") {
    assertEquals(NumberCheck.numbers("a 3.5 b 12 c 4.0.1"), List("3.5", "12", "4.0", "1"))
    assertEquals(NumberCheck.unsupportedNumbers("score 3.5", "score 3.5"), Nil)
    assertEquals(NumberCheck.unsupportedNumbers("score 3.5", "score 35"), List("3.5"))
    assertEquals(NumberCheck.unsupportedNumbers("x 12", "x 123"), List("12"))
  }

  test("NumberCheck: unsupported numbers are distinct and sorted") {
    assertEquals(NumberCheck.unsupportedNumbers("9 7 9 8", "nothing"), List("7", "8", "9"))
  }

  test("paper source rows hold the year, venue digits and the DOI so their numbers are supported") {
    val r = render(Nil, works, venues, topics)
    val d = r.documents.find(_.document.entityId == "work:dblp:conf/pldi/Lov01").get
    assert(
      d.sourceRow.contains("2001") && d.sourceRow.contains("PLDI 2020") && d.sourceRow.contains(
        "10.1000/abc123"
      )
    )
    assertEquals(NumberCheck.check(d), Right(()))
  }

  // ---- chunking through the renderer ---------------------------------------------------------------------

  test(
    "long summary text becomes chunks with ordinals 0..n-1, a shared chunkCount and distinct IDs"
  ) {
    val text = words(900) // 6 * 900 - 1 = 5399 characters
    val r    = render(List(summary(text, ada)))
    val ds   = r.documents.map(_.document)
    assert(ds.size >= 3, ds.size.toString)
    assertEquals(ds.map(_.chunkOrdinal), (0 until ds.size).toList)
    assert(ds.forall(_.chunkCount == ds.size))
    assertEquals(ds.map(_.documentId).distinct.size, ds.size)
    assert(r.documents.forall(d => d.text.length <= 2000))
    assertEquals(r.documents.map(_.document.textHash).distinct.size, ds.size)
  }

  test("a text of exactly the limit is one chunk (0 of 1); one character more is two") {
    val atLimit = ("a" * 1999) + " "
    assertEquals(render(List(summary(atLimit.trim + "b", ada))).documents.size, 1)
    val exact = words(334).take(2000)
    assertEquals(exact.length, 2000)
    val one = render(List(summary(exact, ada))).documents
    assertEquals(one.map(d => d.document.chunkOrdinal -> d.document.chunkCount), List(0 -> 1))
    val two = render(List(summary(exact + "x", ada))).documents
    assertEquals(two.size, 2)
  }

  test("a text that needs more than 1000 chunks is skipped as chunking-failed") {
    val huge = words(40000) // about 240,000 characters
    val r    = render(List(summary(huge, ada)), policy = ChunkPolicy(200, 0))
    assertEquals(r.documents, Nil)
    assertEquals(r.skipped.map(_.reason), List("chunking-failed"))
  }

  test("an invalid chunk policy skips every document as chunking-failed") {
    val r = render(summaries, policy = ChunkPolicy(10, 0))
    assertEquals(r.documents, Nil)
    assertEquals(r.skipped.map(_.reason).toSet, Set("chunking-failed"))
  }

  test("the numbers of a chunked document are still supported by its source row") {
    val text = (1 to 400).map(i => s"paper $i has ${i * 3} citations").mkString(". ")
    val r    = render(List(summary(text, ada)))
    assert(r.documents.size > 1)
    r.documents.foreach(d => assertEquals(NumberCheck.check(d), Right(()), d.document.documentId))
  }
