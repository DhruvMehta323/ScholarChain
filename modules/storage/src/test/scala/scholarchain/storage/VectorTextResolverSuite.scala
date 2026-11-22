package scholarchain.storage

import scholarchain.core.*

import EmbeddingFx.*

/** A vector result resolves to its authoritative S3 text through the pointer. */
class VectorTextResolverSuite extends munit.FunSuite:

  private val Idx = VectorIndexes.ResearcherProfile

  private def loc(s: EmbeddingSource) = ObjectLocation.parse(s.document.textS3Uri).toOption.get

  /** A rig where one document was produced and embedded. */
  private def done(text: String = "Researcher profile text for resolution") =
    val rig = Rig()
    val s   = source(text)
    rig.enqueue(runFor("a"), s)
    rig.drain()
    (rig, s)

  test("a query hit resolves to the exact rendered text through its S3 pointer") {
    val (rig, s) = done()
    val hits = rig.vectorsRaw.query(Idx, VectorQuery(rig.embedder.vecOf(s.text), 3)).toOption.get
    assertEquals(hits.head.vectorKey, s.document.documentId)
    val r = VectorTextResolver(rig.objects).resolve(hits.head).fold(e => fail(e.toString), identity)
    assertEquals(r.text, s.text)
    assertEquals(r.textHash, s.document.textHash)
    assertEquals(r.textS3Uri, s.document.textS3Uri)
  }

  test("the hit metadata carries the pointer and the hash (system metadata), no text") {
    val (rig, s) = done("text that must not be in hit metadata")
    val hit      =
      rig.vectorsRaw.query(Idx, VectorQuery(rig.embedder.vecOf(s.text), 1)).toOption.get.head
    assertEquals(hit.metadata(VectorMetadata.TextS3Uri), s.document.textS3Uri)
    assertEquals(hit.metadata(VectorMetadata.TextHash), s.document.textHash)
    assert(hit.metadata.values.forall(!_.contains("must not be in hit metadata")))
  }

  test("a stored VectorEntry and VectorRecord resolve to the same text") {
    val (rig, s) = done()
    val e        = rig.stored(Idx, s.document.documentId).get
    val res      = VectorTextResolver(rig.objects)
    assertEquals(res.resolve(e).map(_.text), Right(s.text))
    assertEquals(res.resolve(e.record).map(_.text), Right(s.text))
  }

  test(
    "the pointer is derived from the text hash (content-addressed fixture) and equals the stored pointer"
  ) {
    val (rig, s) = done()
    assertEquals(
      rig.stored(Idx, s.document.documentId).get.record.textS3Uri,
      s"s3://$bucket/rag/text/${Hashing.textHash(s.text)}.txt",
    )
  }

  test("a text changed at rest is refused with IntegrityMismatch and no text is returned") {
    val (rig, s) = done()
    rig.objects.tamper(loc(s), "tampered".getBytes("UTF-8"))
    val e = rig.stored(Idx, s.document.documentId).get
    assertEquals(
      VectorTextResolver(rig.objects).resolve(e).left.map(_.code),
      Left("integrity-mismatch"),
    )
  }

  test("a missing text object is NotFound") {
    val rig = Rig()
    val s   = source("never stored")
    val rec = VectorRecord(
      1,
      s.document.documentId,
      Idx,
      4,
      "m",
      "v",
      s.document.textHash,
      s.document.textS3Uri,
      Map.empty,
      runFor("a"),
    )
    assertEquals(VectorTextResolver(rig.objects).resolve(rec).left.map(_.code), Left("not-found"))
  }

  test("a hit without the pointer metadata is NotFound, not a guess") {
    val rig = Rig()
    val hit = VectorHit("k", 0.5, Map("documentType" -> "researcher_profile"))
    assertEquals(VectorTextResolver(rig.objects).resolve(hit).left.map(_.code), Left("not-found"))
    assertEquals(
      VectorTextResolver(rig.objects)
        .resolve(VectorHit("k", 0.5, Map(VectorMetadata.TextS3Uri -> "s3://b/k")))
        .left
        .map(_.code),
      Left("not-found"),
    )
  }

  test("a malformed pointer is InvalidRequest") {
    val rig = Rig()
    val hit = VectorHit(
      "k",
      0.5,
      Map(VectorMetadata.TextS3Uri -> "https://example.com/x", VectorMetadata.TextHash -> "a" * 64),
    )
    assertEquals(
      VectorTextResolver(rig.objects).resolve(hit).left.map(_.code),
      Left("invalid-request"),
    )
  }

  test("non UTF-8 bytes with the right hash are refused as a failure, not decoded lossily") {
    val rig   = Rig()
    val bytes = Array[Byte](0xc3.toByte, 0x28)
    val h     = Hashing.sha256Hex(bytes)
    val l     = ObjectLocation(bucket, s"rag/text/$h.txt")
    rig.objects.put(l, bytes)
    val hit =
      VectorHit("k", 1.0, Map(VectorMetadata.TextS3Uri -> l.uri, VectorMetadata.TextHash -> h))
    assertEquals(VectorTextResolver(rig.objects).resolve(hit).left.map(_.code), Left("failed"))
  }
