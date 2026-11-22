package scholarchain.rag

import scholarchain.core.*

import RagFx.*

/** The local test embedder, ValidatingEmbedder and model versioning (dimension mismatch rejected).
  */
class EmbedderContractSuite extends munit.FunSuite:

  private def norm(v: Vector[Float]): Double = math.sqrt(v.map(x => x.toDouble * x).sum)

  // ---- LocalHashEmbedder ------------------------------------------------------------------------------

  test("LocalHashEmbedder: default identity is local-hash-bow / v1 / 1024 / batch 64") {
    val e = new LocalHashEmbedder()
    assertEquals(
      (e.modelId, e.modelVersion, e.dimension, e.maxBatchSize),
      ("local-hash-bow", "v1", 1024, 64),
    )
  }

  test("LocalHashEmbedder: the same text gives the same vector, across instances") {
    val a = new LocalHashEmbedder(256).embed(List("graph mining of coauthors"))
    val b = new LocalHashEmbedder(256).embed(List("graph mining of coauthors"))
    assertEquals(a, b)
    assertEquals(a.toOption.get.head.length, 256)
  }

  test("LocalHashEmbedder: vectors have unit norm") {
    val vs = new LocalHashEmbedder(128).embed(List("one two three", "a a a b", "x")).toOption.get
    vs.foreach(v => assertEqualsDouble(norm(v), 1.0, 1e-5))
  }

  test("LocalHashEmbedder: a text without a token is InvalidRequest") {
    val e = new LocalHashEmbedder(64)
    assertEquals(e.embed(List("   ")).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List("!!! ---")).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List("fine", "")).left.map(_.code), Left("invalid-request"))
  }

  test("LocalHashEmbedder: an empty batch and a batch above maxBatchSize are InvalidRequest") {
    val e = new LocalHashEmbedder(64, maxBatchSize = 2)
    assertEquals(e.embed(Nil).left.map(_.code), Left("invalid-request"))
    assertEquals(e.embed(List("a", "b", "c")).left.map(_.code), Left("invalid-request"))
    assert(e.embed(List("a", "b")).isRight)
  }

  test("LocalHashEmbedder: a dimension outside 1..4096 is a failure, not an exception") {
    assertEquals(new LocalHashEmbedder(0).embed(List("a")).left.map(_.code), Left("failed"))
    assertEquals(new LocalHashEmbedder(4097).embed(List("a")).left.map(_.code), Left("failed"))
  }

  test("LocalHashEmbedder: FNV-1a 64 matches the published test vectors (stable across JVMs)") {
    assertEquals(LocalHashEmbedder.fnv1a64(""), 0xcbf29ce484222325L)
    assertEquals(LocalHashEmbedder.fnv1a64("a"), 0xaf63dc4c8601ec8cL)
    assertEquals(LocalHashEmbedder.fnv1a64("foobar"), 0x85944171f73967e8L)
  }

  test("LocalHashEmbedder: bucket values are fixed (expected values computed independently)") {
    assertEquals(LocalHashEmbedder.bucket("a", 1024), 140)
    assertEquals(LocalHashEmbedder.bucket("graph", 1024), 647)
    assertEquals(LocalHashEmbedder.bucket("foobar", 1024), 1000)
    assertEquals(LocalHashEmbedder.bucket("graph", 256), 135)
    val v = new LocalHashEmbedder(1024).embed(List("graph")).toOption.get.head
    assertEquals(v.zipWithIndex.collect { case (x, i) if x != 0f => i }, Vector(647))
    assertEquals(v(647), 1f)
  }

  test("LocalHashEmbedder: tokens are lower-cased letter and digit runs") {
    assertEquals(
      LocalHashEmbedder.tokens("Graph-Mining, 2004; A1b"),
      List("graph", "mining", "2004", "a1b"),
    )
    assertEquals(LocalHashEmbedder.tokens("École"), List("école"))
  }

  test("LocalHashEmbedder: a bag of words ignores order and case, and different words differ") {
    val e = new LocalHashEmbedder(512)
    assertEquals(e.embed(List("alpha beta gamma")), e.embed(List("Gamma, ALPHA beta")))
    assertNotEquals(e.embed(List("alpha beta")), e.embed(List("alpha delta")))
  }

  test("LocalHashEmbedder: the embeddings of a batch equal the embeddings one by one") {
    val e = new LocalHashEmbedder(128)
    val t = List("first text", "second text here")
    assertEquals(e.embed(t).toOption.get, t.map(x => e.embed(List(x)).toOption.get.head))
  }

  test("LocalHashEmbedder makes no call: constructing and using it never builds a Bedrock client") {
    new LocalHashEmbedder(32).embed(List("x"))
    EmbedderFactory.localTest(32).embed(List("x"))
    assertEquals(SdkBedrockInvoker.clientsConstructed, 0)
  }

  // ---- ValidatingEmbedder -------------------------------------------------------------------------------

  private final class Stub(
      val dimension: Int = 4,
      val maxBatchSize: Int = 2,
      reply: List[String] => EmbeddingResult[List[Vector[Float]]],
  ) extends Embedder:
    val modelId                = "stub"
    val modelVersion           = "s1"
    def embed(t: List[String]) = reply(t)

  private def ones(n: Int, len: Int) = List.fill(n)(Vector.fill(len)(1f))

  test(
    "ValidatingEmbedder: a vector shorter than the dimension is DimensionMismatch(expected, actual)"
  ) {
    val v = ValidatingEmbedder(Stub(reply = t => Right(ones(t.size, 3))))
    assertEquals(v.embed(List("a")), Left(EmbeddingError.DimensionMismatch(4, 3)))
  }

  test("ValidatingEmbedder: a vector longer than the dimension is DimensionMismatch too") {
    val v = ValidatingEmbedder(Stub(reply = t => Right(ones(t.size, 5))))
    assertEquals(v.embed(List("a")), Left(EmbeddingError.DimensionMismatch(4, 5)))
  }

  test("ValidatingEmbedder: a wrong vector count and a non-finite component are failures") {
    assertEquals(
      ValidatingEmbedder(Stub(reply = _ => Right(ones(1, 4))))
        .embed(List("a", "b"))
        .left
        .map(_.code),
      Left("failed"),
    )
    assertEquals(
      ValidatingEmbedder(Stub(reply = t => Right(List(Vector(Float.NaN, 1f, 1f, 1f)))))
        .embed(List("a"))
        .left
        .map(_.code),
      Left("failed"),
    )
    assertEquals(
      ValidatingEmbedder(Stub(reply = t => Right(List(Vector(Float.PositiveInfinity, 1f, 1f, 1f)))))
        .embed(List("a"))
        .left
        .map(_.code),
      Left("failed"),
    )
  }

  test(
    "ValidatingEmbedder: empty batch, oversized batch and blank text never reach the inner embedder"
  ) {
    var calls = 0
    val v     = ValidatingEmbedder(
      Stub(maxBatchSize = 2, reply = t => { calls += 1; Right(ones(t.size, 4)) })
    )
    assertEquals(v.embed(Nil).left.map(_.code), Left("invalid-request"))
    assertEquals(v.embed(List("a", "b", "c")).left.map(_.code), Left("invalid-request"))
    assertEquals(v.embed(List("a", "  ")).left.map(_.code), Left("invalid-request"))
    assertEquals(calls, 0)
    assert(v.embed(List("a", "b")).isRight)
    assertEquals(calls, 1)
  }

  test(
    "ValidatingEmbedder: identity is the inner embedder's and inner errors pass through unchanged"
  ) {
    val v = ValidatingEmbedder(Stub(reply = _ => Left(EmbeddingError.Throttled("429"))))
    assertEquals((v.modelId, v.modelVersion, v.dimension, v.maxBatchSize), ("stub", "s1", 4, 2))
    assertEquals(v.embed(List("a")), Left(EmbeddingError.Throttled("429")))
  }

  // ---- ModelVersioning ----------------------------------------------------------------------------------

  private val e4  = new LocalHashEmbedder(4)
  private val doc = RenderedFx.document
  private def entry(
      model: String = "local-hash-bow",
      version: String = "v1",
      dim: Int = 4,
      len: Int = 4,
  ) =
    VectorEntry(
      VectorRecord(
        1,
        doc.documentId,
        VectorIndexes.ResearcherProfile,
        dim,
        model,
        version,
        doc.textHash,
        doc.textS3Uri,
        Map.empty,
        run1,
      ),
      Vector.fill(len)(1f),
    )

  test("ModelVersioning.checkEntry: an entry produced by the embedder passes") {
    assertEquals(ModelVersioning.checkEntry(e4, entry()).map(_ => ()), Right(()))
  }

  test("ModelVersioning.checkEntry: a different model ID or version is rejected") {
    assert(ModelVersioning.checkEntry(e4, entry(model = "other")).isLeft)
    assert(ModelVersioning.checkEntry(e4, entry(version = "v2")).isLeft)
  }

  test(
    "ModelVersioning.checkEntry: a record dimension or vector length that differs is DimensionMismatch"
  ) {
    assertEquals(
      ModelVersioning.checkEntry(e4, entry(dim = 8, len = 8)).left.map(identity),
      Left(EmbeddingError.DimensionMismatch(4, 8)),
    )
    assertEquals(
      ModelVersioning.checkEntry(e4, entry(dim = 4, len = 3)),
      Left(EmbeddingError.DimensionMismatch(4, 3)),
    )
  }

  test(
    "ModelVersioning.entryFor: stamps model ID, version and dimension; rejects a wrong-length vector"
  ) {
    val ok = ModelVersioning.entryFor(
      e4,
      doc,
      VectorIndexes.ResearcherProfile,
      Map("k" -> "v"),
      run1,
      Vector.fill(4)(1f),
    )
    val r = ok.toOption.get.record
    assertEquals(
      (r.embeddingModelId, r.embeddingModelVersion, r.dimension),
      ("local-hash-bow", "v1", 4),
    )
    assertEquals(r.vectorKey, doc.documentId)
    assertEquals(r.textS3Uri, doc.textS3Uri)
    assertEquals(r.textHash, doc.textHash)
    assertEquals(
      ModelVersioning
        .entryFor(e4, doc, VectorIndexes.ResearcherProfile, Map.empty, run1, Vector.fill(5)(1f)),
      Left(EmbeddingError.DimensionMismatch(4, 5)),
    )
  }

  test("ModelVersioning.stampOf mirrors the embedder identity") {
    assertEquals(ModelVersioning.stampOf(e4), ModelStamp("local-hash-bow", "v1", 4))
  }

  test("every path through the model stamp keeps the dimension of the embedder (local factory)") {
    val v = EmbedderFactory.localTest(16).embed(List("alpha beta")).toOption.get.head
    assertEquals(v.length, 16)
  }

/** A small valid document for the versioning tests. */
private object RenderedFx:
  val document: EmbeddingDocument =
    EmbeddingDocumentRenderer
      .renderDetailed(
        List(summary("Ada Lovelace has 3 papers.")),
        Nil,
        Nil,
        Nil,
        run1,
        textUri,
      )
      .documents
      .head
      .document
