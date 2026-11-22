package scholarchain.core

import java.nio.charset.StandardCharsets

import ValidationError.*

/** Phase 08 embedding contracts (A1): frozen examples, round trips, key rejections. */
class EmbeddingRecordsContractSuite extends ContractSupport:

  private def resource(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(name)
    assert(in != null, s"classpath resource $name is missing")
    try String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private def assertFrozen[A: Contract: WirePickle.Reader: WirePickle.Writer](name: String)(using
      munit.Location
  ): A =
    val json = resource(s"contracts/$name.example.json")
    assert(!json.endsWith("\n"), s"$name example is expected to have no trailing newline")
    val a = right(Wire.read[A](json))
    assertEquals(right(Wire.write(a)), json)
    assertEquals(Wire.read[A](right(Wire.write(a))), Right(a))
    a

  private val doc: EmbeddingDocument    = assertFrozen[EmbeddingDocument]("embedding-document")
  private val job: EmbeddingJob         = assertFrozen[EmbeddingJob]("embedding-job")
  private val item: SemanticContextItem =
    assertFrozen[SemanticContextItem]("semantic-context-item")

  private def invalidField(e: Either[ValidationError, ?], name: String)(using
      munit.Location
  ): Unit =
    e match
      case Left(InvalidField(f, _)) => assertEquals(f, name)
      case other                    => fail(s"expected InvalidField($name) but got $other")

  private def docAt(i: Int): EmbeddingDocument =
    val w   = GraphWindow(2000, 2004)
    val ent = right(Ids.researcherIdDblpPerson(s"Embedding Contract Person $i"))
    val id  = EmbeddingDocument.idFor("researcher_profile", ent, w, 0)
    doc.copy(documentId = id, entityId = ent)

  // ---- frozen examples ------------------------------------------------------------------------

  test("EmbeddingDocument: the frozen example parses, validates and re-serializes identically") {
    right(validate(doc))
  }

  test("EmbeddingJob: the frozen example parses, validates and embeds a valid document") {
    right(validate(job))
    assertEquals(job.documents, List(doc))
    assertEquals(job.status, EmbeddingJobStatus.Pending)
    assertEquals(job.attempt, 1)
  }

  test("SemanticContextItem: the frozen example parses and validates; its key is the document ID") {
    right(validate(item))
    assertEquals(item.vectorKey, doc.documentId)
  }

  // ---- document types ---------------------------------------------------------------------------

  test("EmbeddingDocumentTypes: five types, each mapped to exactly one distinct production index") {
    assertEquals(
      EmbeddingDocumentTypes.all,
      List(
        "researcher_profile",
        "community_profile",
        "path_summary",
        "topic_summary",
        "paper_abstract",
      ),
    )
    val idx = EmbeddingDocumentTypes.all.flatMap(EmbeddingDocumentTypes.indexFor)
    assertEquals(idx.size, 5)
    assertEquals(idx.toSet, VectorIndexes.all.toSet)
    assertEquals(EmbeddingDocumentTypes.indexFor("unknown"), None)
  }

  // ---- EmbeddingDocument rejections ---------------------------------------------------------------

  test("EmbeddingDocument: idFor is <type>:<entity>:<window>:<ordinal>") {
    assertEquals(
      EmbeddingDocument.idFor("topic_summary", "topic:x", GraphWindow(2000, 2004), 3),
      "topic_summary:topic:x:2000-2004:3",
    )
  }

  test("EmbeddingDocument: a documentId that is not the derived value is rejected") {
    invalidField(validate(doc.copy(documentId = doc.documentId + "x")), "documentId")
  }

  test("EmbeddingDocument: an unknown document type is rejected") {
    val bad = doc.copy(
      documentType = "abstract_text",
      documentId = EmbeddingDocument.idFor("abstract_text", doc.entityId, doc.window, 0),
    )
    invalidField(validate(bad), "documentType")
  }

  test("EmbeddingDocument: a textHash that is not 64 lowercase hex is rejected") {
    invalidField(validate(doc.copy(textHash = "ABC")), "textHash")
    invalidField(validate(doc.copy(textHash = doc.textHash.toUpperCase)), "textHash")
  }

  test("EmbeddingDocument: a textS3Uri that is not an s3 URI is rejected") {
    invalidField(validate(doc.copy(textS3Uri = "https://example.com/x.txt")), "textS3Uri")
    invalidField(validate(doc.copy(textS3Uri = "s3://bucket-only")), "textS3Uri")
  }

  test("EmbeddingDocument: chunk ordinal and count bounds") {
    invalidField(validate(doc.copy(chunkCount = 0)), "chunkCount")
    invalidField(validate(doc.copy(chunkCount = EmbeddingDocument.MaxChunkCount + 1)), "chunkCount")
    // ordinal 1 with count 1 and a document ID for ordinal 0: the ID no longer matches
    invalidField(validate(doc.copy(chunkOrdinal = 1, chunkCount = 1)), "documentId")
    val second = doc.copy(
      chunkOrdinal = 1,
      chunkCount = 2,
      documentId = EmbeddingDocument.idFor(doc.documentType, doc.entityId, doc.window, 1),
    )
    right(validate(second))
    val outOfRange = doc.copy(
      chunkOrdinal = 2,
      chunkCount = 2,
      documentId = EmbeddingDocument.idFor(doc.documentType, doc.entityId, doc.window, 2),
    )
    invalidField(validate(outOfRange), "chunkOrdinal")
    val negative = doc.copy(
      chunkOrdinal = -1,
      documentId = EmbeddingDocument.idFor(doc.documentType, doc.entityId, doc.window, -1),
    )
    invalidField(validate(negative), "chunkOrdinal")
  }

  test("EmbeddingDocument: a sourceRunId that is not run:<64 hex> is rejected") {
    assert(validate(doc.copy(sourceRunId = "run:abc")).isLeft)
    assert(validate(doc.copy(sourceRunId = "")).isLeft)
  }

  test("EmbeddingDocument: an invalid window and a wrong schema version are rejected") {
    assert(validate(doc.copy(window = GraphWindow(2005, 2000))).isLeft)
    assert(validate(doc.copy(schemaVersion = 99)).isLeft)
  }

  test("EmbeddingDocument: an entityId over 400 characters is rejected") {
    val longId = "researcher:" + ("a" * 400)
    val bad    = doc.copy(
      entityId = longId,
      documentId = EmbeddingDocument.idFor(doc.documentType, longId, doc.window, 0),
    )
    assert(validate(bad).isLeft)
  }

  // ---- EmbeddingJob rejections -----------------------------------------------------------------------

  test("EmbeddingJob: no documents is rejected") {
    invalidField(validate(job.copy(documents = Nil)), "documents")
  }

  test("EmbeddingJob: 50 distinct documents are valid, 51 are rejected") {
    val fifty = (1 to 50).toList.map(docAt)
    right(validate(job.copy(documents = fifty)))
    invalidField(validate(job.copy(documents = (1 to 51).toList.map(docAt))), "documents")
    assertEquals(EmbeddingJob.MaxDocuments, 50)
  }

  test("EmbeddingJob: a duplicate documentId is rejected") {
    assert(validate(job.copy(documents = List(doc, doc))).isLeft)
  }

  test("EmbeddingJob: attempt must be at least 1 (1-based)") {
    invalidField(validate(job.copy(attempt = 0)), "attempt")
    right(validate(job.copy(attempt = 3)))
  }

  test("EmbeddingJob: failureReason is required for failed and dead_lettered only") {
    invalidField(validate(job.copy(status = EmbeddingJobStatus.Failed)), "failureReason")
    invalidField(validate(job.copy(status = EmbeddingJobStatus.DeadLettered)), "failureReason")
    invalidField(validate(job.copy(failureReason = Some("late"))), "failureReason")
    invalidField(
      validate(job.copy(status = EmbeddingJobStatus.Succeeded, failureReason = Some("x"))),
      "failureReason",
    )
    right(
      validate(
        job.copy(status = EmbeddingJobStatus.DeadLettered, failureReason = Some("key-collision=1"))
      )
    )
  }

  test("EmbeddingJob: a failureReason longer than the ledger limit is rejected") {
    val long = "x" * (RunLedgerEntry.MaxFailureReasonLength + 1)
    assert(
      validate(job.copy(status = EmbeddingJobStatus.Failed, failureReason = Some(long))).isLeft
    )
  }

  test("EmbeddingJob: status wire names are frozen") {
    assertEquals(
      EmbeddingJobStatus.values.toList.map(_.wire),
      List("pending", "succeeded", "failed", "dead_lettered"),
    )
  }

  test("EmbeddingJob: model ID and version must be non-blank tokens") {
    assert(validate(job.copy(embeddingModelId = "has space")).isLeft)
    assert(validate(job.copy(embeddingModelVersion = "")).isLeft)
  }

  test("EmbeddingJob: a run ID that is not run:<64 hex> is rejected") {
    assert(validate(job.copy(runId = "run:short")).isLeft)
  }

  // ---- SemanticContextItem --------------------------------------------------------------------------

  test("SemanticContextItem: the excerpt is at most 300 characters and non-blank") {
    right(validate(item.copy(excerpt = "a" * 300)))
    invalidField(validate(item.copy(excerpt = "a" * 301)), "excerpt")
    invalidField(validate(item.copy(excerpt = "   ")), "excerpt")
    assertEquals(SemanticContextItem.MaxExcerptLength, 300)
  }

  test("SemanticContextItem: a non-finite score and an unknown document type are rejected") {
    assert(validate(item.copy(score = Double.NaN)).isLeft)
    assert(validate(item.copy(score = Double.PositiveInfinity)).isLeft)
    invalidField(validate(item.copy(documentType = "graph_fact")), "documentType")
  }

  test("SemanticContextItem: the text pointer must be an s3 URI") {
    invalidField(validate(item.copy(textS3Uri = "file:///tmp/x")), "textS3Uri")
  }

  // ---- Embedder port -------------------------------------------------------------------------------

  test("EmbeddingError: only Throttled and Unavailable are retryable; codes are stable") {
    import EmbeddingError.*
    assertEquals(
      List(
        InvalidRequest("x"),
        Throttled("x"),
        Unavailable("x"),
        DimensionMismatch(2, 3),
        Failed("x"),
      ).map(e => e.code -> e.retryable),
      List(
        "invalid-request"    -> false,
        "throttled"          -> true,
        "unavailable"        -> true,
        "dimension-mismatch" -> false,
        "failed"             -> false,
      ),
    )
  }
