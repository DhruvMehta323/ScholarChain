package scholarchain.storage

import scholarchain.core.*

import StorageFx.*

/** S3 Vectors rules against the in-memory fake: batching, metadata policy, collisions, cosine top-k
  * with filters.
  */
class VectorStoreSuite extends munit.FunSuite:

  private val Idx = VectorIndexes.ResearcherProfile

  private def freshStore(): (InMemoryVectorStore, InMemoryRejectionSink) =
    val sink = InMemoryRejectionSink()
    (InMemoryVectorStore(Map(Idx -> 3, VectorIndexes.PaperAbstract -> 3), sink), sink)

  private def put(s: InMemoryVectorStore, es: VectorEntry*): List[VectorPutOutcome] =
    s.putBatch(Idx, es.toList).fold(e => fail(e.toString), _.map(_.outcome))

  private val accepted                = VectorPutOutcome.Accepted
  private def rej(r: VectorRejection) = VectorPutOutcome.Rejected(r)

  // ---- batching -------------------------------------------------------------------------------

  test("batching: at most 500 vectors per call, order preserved, nothing lost") {
    val es      = (1 to 1200).toList.map(i => entry(s"k$i", s"t$i"))
    val batches = VectorChecks.batches(es)
    assertEquals(batches.map(_.size), List(500, 500, 200))
    assertEquals(batches.flatten, es)
    assertEquals(S3VectorsLimits.MaxVectorsPerPut, 500)
  }

  test("batching: an estimated 20 MiB payload bound splits large-dimension vectors") {
    val shared = Vector.fill(4096)(0.5f)
    val es     = (1 to 1000).toList.map(i => entry(s"k$i", s"t$i", shared))
    val bs     = VectorChecks.batches(es)
    assert(bs.size > 2, bs.map(_.size).toString)
    bs.foreach { b =>
      assert(b.size <= 500)
      assert(
        b.map(VectorChecks.estimatedBytes).sum <= S3VectorsLimits.MaxPayloadBytes,
        "batch above 20 MiB",
      )
    }
    assertEquals(bs.flatten, es)
    assertEquals(S3VectorsLimits.MaxPayloadBytes, 20L * 1024 * 1024)
  }

  test(
    "batching: custom count and byte limits are honoured, one oversized vector still gets its own batch"
  ) {
    val es = (1 to 5).toList.map(i => entry(s"k$i", s"t$i"))
    assertEquals(VectorChecks.batches(es, maxCount = 2).map(_.size), List(2, 2, 1))
    assertEquals(VectorChecks.batches(es, maxBytes = 1L).map(_.size), List(1, 1, 1, 1, 1))
    assertEquals(VectorChecks.batches(Nil), Nil)
  }

  test("putBatch refuses an empty batch, more than 500 vectors, and an unknown index") {
    val (s, _) = freshStore()
    assert(s.putBatch(Idx, Nil).left.exists(_.isInstanceOf[StorageError.InvalidRequest]))
    val big = (1 to 501).toList.map(i => entry(s"k$i", s"t$i"))
    assert(s.putBatch(Idx, big).left.exists(_.isInstanceOf[StorageError.InvalidRequest]))
    assertEquals(s.size(Idx), 0)
    assertEquals(s.putBatch("nope-v1", List(entry("a", "a"))), Left(StorageError.NotFound("index")))
    assertEquals(s.putBatch(Idx, big.take(500)).map(_.size), Right(500))
  }

  // ---- metadata policy ------------------------------------------------------------------------

  test("long text in metadata is rejected, recorded, and not stored") {
    val (s, sink) = freshStore()
    val longText  = "lorem ipsum dolor sit amet " * 40
    val bad       = entry("k1", "t1", md = Map("abstract" -> longText))
    assertEquals(put(s, bad), List(rej(VectorRejection.InvalidRecord)))
    assertEquals(s.size(Idx), 0)
    assertEquals(
      sink.all.map(r => (r.vectorKey, r.reason)),
      List(("k1", VectorRejection.InvalidRecord)),
    )
    assert(
      sink.all.forall(r => !r.detail.contains("lorem") && !r.toString.contains("lorem")),
      "sink must not copy values",
    )
  }

  test("metadata at the limits is stored (128-char value, 16 entries) and one over is rejected") {
    val (s, _) = freshStore()
    val ok16   = (1 to 16).map(i => s"k$i" -> "v").toMap
    assertEquals(put(s, entry("a", "a", md = Map("m" -> ("x" * 128)))), List(accepted))
    assertEquals(put(s, entry("b", "b", md = ok16)), List(accepted))
    assertEquals(
      put(s, entry("c", "c", md = ok16 + ("k17" -> "v"))),
      List(rej(VectorRejection.InvalidRecord)),
    )
    assertEquals(
      put(s, entry("d", "d", md = Map("m" -> ("x" * 129)))),
      List(rej(VectorRejection.InvalidRecord)),
    )
  }

  test("a record may not reuse a system metadata name (textHash, textS3Uri, runId, model fields)") {
    val (s, _) = freshStore()
    VectorMetadata.System.toList.foreach { n =>
      assertEquals(
        put(s, entry(s"k-$n", s"t-$n", md = Map(n -> "x"))),
        List(rej(VectorRejection.InvalidRecord)),
        n,
      )
    }
    assertEquals(s.size(Idx), 0)
  }

  test("malformed records are rejected: blank metadata name, bad hash, bad URI, bad run ID") {
    val (s, _) = freshStore()
    val e      = entry("k1", "t1")
    val bads   = List(
      e.copy(record = e.record.copy(metadata = Map(" " -> "v"))),
      e.copy(record = e.record.copy(textHash = "xyz")),
      e.copy(record = e.record.copy(textS3Uri = "not-a-uri")),
      e.copy(record = e.record.copy(runId = "run:bad")),
      e.copy(record = e.record.copy(vectorKey = "has space")),
    )
    bads.foreach(b =>
      assertEquals(put(s, b), List(rej(VectorRejection.InvalidRecord)), b.record.toString)
    )
    assertEquals(s.size(Idx), 0)
  }

  test("embedding problems: dimension mismatch, wrong index, NaN, Infinity and the zero vector") {
    val (s, _) = freshStore()
    val e      = entry("k1", "t1")
    assertEquals(
      put(s, e.copy(embedding = Vector(1f, 0f))),
      List(rej(VectorRejection.DimensionMismatch)),
    )
    assertEquals(
      put(s, e.copy(record = e.record.copy(dimension = 4), embedding = Vector(1f, 0f, 0f, 0f))),
      List(rej(VectorRejection.DimensionMismatch)),
    )
    assertEquals(
      put(s, entry("k2", "t2", index = VectorIndexes.PaperAbstract)),
      List(rej(VectorRejection.InvalidRecord)),
    )
    assertEquals(
      put(s, entry("k3", "t3", Vector(Float.NaN, 0f, 0f))),
      List(rej(VectorRejection.InvalidRecord)),
    )
    assertEquals(
      put(s, entry("k4", "t4", Vector(Float.PositiveInfinity, 0f, 0f))),
      List(rej(VectorRejection.InvalidRecord)),
    )
    assertEquals(
      put(s, entry("k5", "t5", Vector(0f, 0f, 0f))),
      List(rej(VectorRejection.InvalidRecord)),
    )
    assertEquals(s.size(Idx), 0)
  }

  test("results are one per input vector and in input order, mixed outcomes included") {
    val (s, _) = freshStore()
    val rs     = s
      .putBatch(
        Idx,
        List(
          entry("a", "a"),
          entry("b", "b", Vector(1f)),
          entry("c", "c"),
          entry("d", "d", Vector(0f, 0f, 0f)),
        ),
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(rs.map(_.vectorKey), List("a", "b", "c", "d"))
    assertEquals(
      rs.map(_.outcome),
      List(
        accepted,
        rej(VectorRejection.DimensionMismatch),
        accepted,
        rej(VectorRejection.InvalidRecord),
      ),
    )
    assertEquals(s.size(Idx), 2)
  }

  test("metadata encode/decode keeps every system field, and decode fails when any is missing") {
    val r  = vector("k1", "t1", md = Map("window" -> windowLabel, "source" -> "dblp"))
    val md = VectorMetadata.encode(r)
    assertEquals(md.keySet, Set("window", "source") ++ VectorMetadata.System)
    assertEquals(VectorMetadata.decode("k1", Idx, 3, md), Right(r))
    VectorMetadata.System.foreach { n =>
      assert(VectorMetadata.decode("k1", Idx, 3, md - n).isLeft, s"missing $n must fail")
    }
    assert(VectorMetadata.decode("k1", Idx, 3, Map.empty).isLeft)
    assertEquals(VectorMetadata.json(Map("b" -> "2", "a" -> "1")), """{"a":"1","b":"2"}""")
  }

  // ---- collisions -----------------------------------------------------------------------------

  test(
    "a key that collides with a different text hash is rejected, recorded, and the original is kept"
  ) {
    val (s, sink) = freshStore()
    assertEquals(put(s, entry("k1", "first text", Vector(1f, 0f, 0f))), List(accepted))
    assertEquals(
      put(s, entry("k1", "second text", Vector(0f, 1f, 0f))),
      List(rej(VectorRejection.KeyCollision)),
    )
    val stored = s.get(Idx, "k1").fold(e => fail(e.toString), identity).get
    assertEquals(stored.record.textHash, Hashing.textHash("first text"))
    assertEquals(stored.embedding, Vector(1f, 0f, 0f))
    assertEquals(s.size(Idx), 1)
    assertEquals(
      sink.all.map(r => (r.indexName, r.vectorKey, r.reason)),
      List((Idx, "k1", VectorRejection.KeyCollision)),
    )
  }

  test(
    "an identical re-put (same text hash, model, metadata and embedding) is Accepted with nothing recorded"
  ) {
    val (s, sink) = freshStore()
    val first     = entry("k1", "same text", Vector(1f, 0f, 0f))
    assertEquals(put(s, first), List(accepted))
    assertEquals(put(s, first), List(accepted))
    assertEquals(put(s, first.copy()), List(accepted))
    assertEquals(s.size(Idx), 1)
    assertEquals(s.get(Idx, "k1"), Right(Some(first)))
    assertEquals(sink.all, Nil)
  }

  // ---- M3: same key and text hash, anything else different, is a recorded rejection ------------

  test(
    "same key and text hash with a different embedding is Rejected(EmbeddingMismatch), recorded, stored vector unchanged"
  ) {
    val (s, sink) = freshStore()
    val first     = entry("k1", "same text", Vector(1f, 0f, 0f))
    assertEquals(put(s, first), List(accepted))
    assertEquals(
      put(s, entry("k1", "same text", Vector(0f, 0f, 1f))),
      List(rej(VectorRejection.EmbeddingMismatch)),
    )
    assertEquals(s.get(Idx, "k1"), Right(Some(first)))
    assertEquals(s.get(Idx, "k1").map(_.get.embedding), Right(Vector(1f, 0f, 0f)))
    assertEquals(s.size(Idx), 1)
    assertEquals(
      sink.all.map(r => (r.indexName, r.vectorKey, r.reason)),
      List((Idx, "k1", VectorRejection.EmbeddingMismatch)),
    )
  }

  test(
    "same key and text hash with a different embedding model version is Rejected(EmbeddingMismatch)"
  ) {
    val (s, sink) = freshStore()
    val first     = entry("k1", "t")
    assertEquals(put(s, first), List(accepted))
    val newer = first.copy(record = first.record.copy(embeddingModelVersion = "v2"))
    assertEquals(put(s, newer), List(rej(VectorRejection.EmbeddingMismatch)))
    assertEquals(s.get(Idx, "k1").map(_.get.record.embeddingModelVersion), Right("v1"))
    assertEquals(sink.all.map(_.reason), List(VectorRejection.EmbeddingMismatch))
  }

  test(
    "same key and text hash with a different embedding model ID is Rejected(EmbeddingMismatch)"
  ) {
    val (s, _) = freshStore()
    val first  = entry("k1", "t")
    put(s, first)
    val other = first.copy(record = first.record.copy(embeddingModelId = "other-model"))
    assertEquals(put(s, other), List(rej(VectorRejection.EmbeddingMismatch)))
    assertEquals(s.get(Idx, "k1").map(_.get.record.embeddingModelId), Right("example-model"))
  }

  test("same key and text hash with different metadata is Rejected(EmbeddingMismatch)") {
    val (s, _) = freshStore()
    put(s, entry("k1", "t", md = Map("window" -> "2000-2004")))
    assertEquals(
      put(s, entry("k1", "t", md = Map("window" -> "2005-2009"))),
      List(rej(VectorRejection.EmbeddingMismatch)),
    )
    assertEquals(
      s.get(Idx, "k1").map(_.get.record.metadata),
      Right(Map("window" -> "2000-2004")),
    )
  }

  test("a different text hash is KeyCollision even when the embedding also differs") {
    val (s, sink) = freshStore()
    put(s, entry("k1", "first"))
    assertEquals(
      put(s, entry("k1", "second", Vector(0f, 1f, 0f))),
      List(rej(VectorRejection.KeyCollision)),
    )
    assertEquals(sink.all.map(_.reason), List(VectorRejection.KeyCollision))
  }

  test(
    "in one batch: an identical duplicate is Accepted, a conflicting duplicate is EmbeddingMismatch"
  ) {
    val (s, sink) = freshStore()
    assertEquals(
      put(
        s,
        entry("k1", "a"),
        entry("k1", "a"),
        entry("k2", "b"),
        entry("k2", "b", Vector(0f, 1f, 0f)),
      ),
      List(accepted, accepted, accepted, rej(VectorRejection.EmbeddingMismatch)),
    )
    assertEquals(
      sink.all.map(r => (r.vectorKey, r.reason)),
      List(("k2", VectorRejection.EmbeddingMismatch)),
    )
    assertEquals(s.get(Idx, "k2").map(_.get.embedding), Right(Vector(1f, 0f, 0f)))
    assertEquals(s.size(Idx), 2)
  }

  test(
    "the EmbeddingMismatch record holds the key and a fixed text, never an embedding or metadata value"
  ) {
    val (s, sink) = freshStore()
    put(s, entry("k1", "t", md = Map("note" -> "sensitive-value-123")))
    put(s, entry("k1", "t", Vector(0f, 0f, 1f), md = Map("note" -> "sensitive-value-123")))
    assertEquals(sink.all.size, 1)
    assert(!sink.all.head.toString.contains("sensitive-value-123"), sink.all.head.toString)
    assert(!sink.all.head.detail.contains("1.0"), sink.all.head.detail)
  }

  test(
    "VectorChecks.againstStored: identical is None, other text hash is KeyCollision, else EmbeddingMismatch"
  ) {
    val e      = entry("k1", "t")
    val stored = VectorMetadata.encode(e.record)
    assertEquals(VectorChecks.againstStored(stored, e.embedding, e), None)
    assertEquals(
      VectorChecks.againstStored(stored, Vector(0f, 1f, 0f), e).map(_._1),
      Some(VectorRejection.EmbeddingMismatch),
    )
    assertEquals(
      VectorChecks
        .againstStored(stored + (VectorMetadata.ModelVer -> "v9"), e.embedding, e)
        .map(_._1),
      Some(VectorRejection.EmbeddingMismatch),
    )
    assertEquals(
      VectorChecks
        .againstStored(stored + (VectorMetadata.TextHash -> "f" * 64), e.embedding, e)
        .map(_._1),
      Some(VectorRejection.KeyCollision),
    )
    assertEquals(
      VectorChecks.againstStored(stored - VectorMetadata.TextHash, e.embedding, e).map(_._1),
      Some(VectorRejection.KeyCollision),
    )
    assertEquals(
      VectorChecks.againstStored(stored, Vector.empty, e).map(_._1),
      Some(VectorRejection.EmbeddingMismatch),
    )
  }

  test(
    "the loader reports an EmbeddingMismatch in its rejected counts and leaves the store unchanged"
  ) {
    val sink  = InMemoryRejectionSink()
    val store = InMemoryVectorStore(Map(Idx -> 3), sink)
    val ld    = VectorLoader(store, StorageRetry(2, 1L, 2L), (_: Long) => (), sink)
    val first = entry("k1", "t")
    assertEquals(ld.load(Idx, List(first)).map(_.accepted), Right(1))
    val rep = ld
      .load(Idx, List(entry("k1", "t", Vector(0f, 0f, 1f))))
      .fold(e => fail(e.toString), identity)
    assertEquals(rep.rejected, Map(VectorRejection.EmbeddingMismatch -> 1))
    assertEquals(rep.accepted, 0)
    assertEquals(store.get(Idx, "k1"), Right(Some(first)))
  }

  test("a collision inside one batch is caught for the second vector only") {
    val (s, sink) = freshStore()
    assertEquals(
      put(s, entry("k1", "a"), entry("k1", "b"), entry("k2", "c")),
      List(accepted, rej(VectorRejection.KeyCollision), accepted),
    )
    assertEquals(sink.all.size, 1)
  }

  test("get returns None for an absent key and NotFound for an unknown index") {
    val (s, _) = freshStore()
    assertEquals(s.get(Idx, "nope"), Right(None))
    assertEquals(s.get("nope-v1", "k"), Left(StorageError.NotFound("index")))
  }

  // ---- cosine top-k with filters --------------------------------------------------------------

  private def seeded(): InMemoryVectorStore =
    val (s, _) = freshStore()
    put(
      s,
      entry(
        "A",
        "a",
        Vector(1f, 0f, 0f),
        Map("researcher" -> "r1", "window" -> "2000-2004", "topic" -> "graphs"),
      ),
      entry(
        "B",
        "b",
        Vector(0.9f, 0.1f, 0f),
        Map("researcher" -> "r2", "window" -> "2000-2004", "topic" -> "ml"),
      ),
      entry(
        "C",
        "c",
        Vector(0f, 1f, 0f),
        Map("researcher" -> "r3", "window" -> "2005-2009", "topic" -> "graphs"),
      ),
      entry(
        "D",
        "d",
        Vector(-1f, 0f, 0f),
        Map("researcher" -> "r4", "window" -> "2005-2009", "topic" -> "db"),
      ),
    ).foreach(o => assertEquals(o, accepted))
    s

  private def q(
      s: InMemoryVectorStore,
      v: Vector[Float],
      k: Int,
      f: Option[MetadataFilter] = None,
  ) =
    s.query(Idx, VectorQuery(v, k, f)).fold(e => fail(e.toString), identity)

  test("cosine top-k: descending similarity, at most k hits, exact scores") {
    val s    = seeded()
    val hits = q(s, Vector(1f, 0f, 0f), 3)
    assertEquals(hits.map(_.vectorKey), List("A", "B", "C"))
    assert(math.abs(hits(0).score - 1.0) < 1e-9)
    assert(hits(0).score > hits(1).score && hits(1).score > hits(2).score)
    assert(math.abs(hits(2).score - 0.0) < 1e-9)
    assertEquals(q(s, Vector(1f, 0f, 0f), 1).map(_.vectorKey), List("A"))
    assertEquals(q(s, Vector(1f, 0f, 0f), 50).size, 4)
    assertEquals(q(s, Vector(1f, 0f, 0f), 4).last.vectorKey, "D")
    assert(math.abs(q(s, Vector(1f, 0f, 0f), 4).last.score + 1.0) < 1e-9)
  }

  test("cosine is scale-invariant and symmetric") {
    assert(math.abs(VectorChecks.cosine(Vector(1f, 2f, 3f), Vector(2f, 4f, 6f)) - 1.0) < 1e-9)
    assert(math.abs(VectorChecks.cosine(Vector(1f, 0f), Vector(0f, 5f))) < 1e-9)
    assertEquals(
      VectorChecks.cosine(Vector(1f, 2f), Vector(3f, 1f)),
      VectorChecks.cosine(Vector(3f, 1f), Vector(1f, 2f)),
    )
  }

  test("filters: Equals, In and And restrict hits before top-k") {
    val s = seeded()
    val v = Vector(1f, 0f, 0f)
    assertEquals(
      q(s, v, 10, Some(MetadataFilter.Equals("window", "2005-2009"))).map(_.vectorKey),
      List("C", "D"),
    )
    assertEquals(
      q(s, v, 10, Some(MetadataFilter.In("researcher", List("r2", "r4")))).map(_.vectorKey),
      List("B", "D"),
    )
    assertEquals(
      q(
        s,
        v,
        10,
        Some(
          MetadataFilter.And(
            List(
              MetadataFilter.Equals("topic", "graphs"),
              MetadataFilter.Equals("window", "2005-2009"),
            )
          )
        ),
      ).map(_.vectorKey),
      List("C"),
    )
    assertEquals(
      q(s, v, 1, Some(MetadataFilter.Equals("topic", "graphs"))).map(_.vectorKey),
      List("A"),
    )
    assertEquals(q(s, v, 10, Some(MetadataFilter.Equals("topic", "none"))), Nil)
    assertEquals(q(s, v, 10, Some(MetadataFilter.Equals("missing-name", "x"))), Nil)
  }

  test("filters can use system metadata and hits expose the stored metadata") {
    val s    = seeded()
    val hits =
      q(s, Vector(1f, 0f, 0f), 10, Some(MetadataFilter.Equals("embeddingModelId", "example-model")))
    assertEquals(hits.size, 4)
    assertEquals(hits.head.metadata("researcher"), "r1")
    assertEquals(hits.head.metadata(VectorMetadata.TextHash), Hashing.textHash("a"))
  }

  test("equal scores are ordered by vector key") {
    val (s, _) = freshStore()
    put(s, entry("b", "b"), entry("a", "a"), entry("c", "c"))
    assertEquals(q(s, Vector(1f, 0f, 0f), 3).map(_.vectorKey), List("a", "b", "c"))
  }

  test(
    "invalid queries are refused: topK bounds, dimension, zero or non-finite vector, bad filters, unknown index"
  ) {
    val s                   = seeded()
    def bad(v: VectorQuery) =
      assert(s.query(Idx, v).left.exists(_.isInstanceOf[StorageError.InvalidRequest]), v.toString)
    bad(VectorQuery(Vector(1f, 0f, 0f), 0))
    bad(VectorQuery(Vector(1f, 0f, 0f), -1))
    bad(VectorQuery(Vector(1f, 0f, 0f), 10001))
    bad(VectorQuery(Vector(1f, 0f), 3))
    bad(VectorQuery(Vector(0f, 0f, 0f), 3))
    bad(VectorQuery(Vector(Float.NaN, 0f, 0f), 3))
    bad(VectorQuery(Vector(1f, 0f, 0f), 3, Some(MetadataFilter.In("topic", Nil))))
    bad(VectorQuery(Vector(1f, 0f, 0f), 3, Some(MetadataFilter.And(Nil))))
    bad(VectorQuery(Vector(1f, 0f, 0f), 3, Some(MetadataFilter.Equals("", "x"))))
    assertEquals(
      s.query("nope-v1", VectorQuery(Vector(1f, 0f, 0f), 3)),
      Left(StorageError.NotFound("index")),
    )
    assert(s.query(Idx, VectorQuery(Vector(1f, 0f, 0f), 10000)).isRight)
  }

  // ---- M4: paged queries (100 hits per page, up to topK) -----------------------------------------

  /** 250 vectors with strictly decreasing cosine similarity to (1,0,0): v_i = (1, i/100, 0). */
  private def bigStore(n: Int = 250): InMemoryVectorStore =
    val (s, _) = freshStore()
    val es     = (0 until n).toList.map(i => entry(f"k$i%04d", s"t$i", Vector(1f, i / 100f, 0f)))
    assertEquals(
      s.putBatch(Idx, es).map(_.map(_.outcome).distinct),
      Right(List(accepted)),
    )
    s

  private val probe = Vector(1f, 0f, 0f)

  test("topK 250 over 250 vectors returns 250 distinct hits in descending score order") {
    val s    = bigStore()
    val hits = s.query(Idx, VectorQuery(probe, 250)).fold(e => fail(e.toString), identity)
    assertEquals(hits.size, 250)
    assertEquals(hits.map(_.vectorKey).distinct.size, 250)
    assertEquals(hits.map(_.vectorKey), (0 until 250).toList.map(i => f"k$i%04d"))
    assert(hits.sliding(2).forall(p => p(0).score >= p(1).score))
  }

  test("queryPage: pages hold 100, 100 and 50 hits and the last page has no next token") {
    val s  = bigStore()
    val q  = VectorQuery(probe, 250)
    val p1 = s.queryPage(Idx, q, None).fold(e => fail(e.toString), identity)
    val p2 = s.queryPage(Idx, q, p1._2).fold(e => fail(e.toString), identity)
    val p3 = s.queryPage(Idx, q, p2._2).fold(e => fail(e.toString), identity)
    assertEquals((p1._1.size, p2._1.size, p3._1.size), (100, 100, 50))
    assert(p1._2.isDefined && p2._2.isDefined)
    assertEquals(p3._2, None)
    assertEquals((p1._1 ++ p2._1 ++ p3._1).map(_.vectorKey).distinct.size, 250)
    assertEquals(S3VectorsLimits.MaxQueryResultsPerPage, 100)
  }

  test("queryPage: topK 100 is one page without a token, topK 101 needs a second page of 1") {
    val s   = bigStore()
    val one = s.queryPage(Idx, VectorQuery(probe, 100), None).fold(e => fail(e.toString), identity)
    assertEquals((one._1.size, one._2), (100, None))
    val q = VectorQuery(probe, 101)
    val a = s.queryPage(Idx, q, None).fold(e => fail(e.toString), identity)
    val b = s.queryPage(Idx, q, a._2).fold(e => fail(e.toString), identity)
    assertEquals((a._1.size, b._1.size, b._2), (100, 1, None))
  }

  test("queryPage: pages never exceed topK in total and honour a filter") {
    val s = bigStore()
    val q = VectorQuery(probe, 150)
    val a = s.queryPage(Idx, q, None).fold(e => fail(e.toString), identity)
    val b = s.queryPage(Idx, q, a._2).fold(e => fail(e.toString), identity)
    assertEquals((a._1.size, b._1.size, b._2), (100, 50, None))
    val f = VectorQuery(probe, 250, Some(MetadataFilter.Equals("window", "no-such-window")))
    assertEquals(s.queryPage(Idx, f, None), Right((Nil, None)))
  }

  test("queryPage: a bad nextToken, topK over 10000 and topK under 1 are InvalidRequest") {
    val s                        = bigStore(5)
    def inv(r: StorageResult[?]) = r.left.exists(_.isInstanceOf[StorageError.InvalidRequest])
    assert(inv(s.queryPage(Idx, VectorQuery(probe, 5), Some("abc"))))
    assert(inv(s.queryPage(Idx, VectorQuery(probe, 5), Some("-1"))))
    assert(inv(s.queryPage(Idx, VectorQuery(probe, 10001), None)))
    assert(inv(s.queryPage(Idx, VectorQuery(probe, 0), None)))
    assert(inv(s.query(Idx, VectorQuery(probe, 10001))))
    assertEquals(S3VectorsLimits.MaxTopK, 10000)
  }

  private def hit(i: Int, score: Double) = VectorHit(f"k$i%04d", score, Map.empty)

  test("collectPages follows the tokens page by page until topK hits are collected") {
    var calls = List.empty[Option[String]]
    val out   = VectorChecks.collectPages(
      250,
      tok =>
        calls = calls :+ tok
        val page = tok.map(_.toInt).getOrElse(0)
        val hits =
          (0 until 100).toList.map(i => hit(page * 100 + i, 1.0 - (page * 100 + i) / 1000.0))
        Right((hits, Some((page + 1).toString))),
    )
    assertEquals(calls, List(None, Some("1"), Some("2")))
    assertEquals(out.map(_.size), Right(250))
    assertEquals(out.map(_.head.vectorKey), Right("k0000"))
  }

  test("collectPages is bounded: at most ceil(topK/100)+1 pages even if the service never ends") {
    var n   = 0
    val out = VectorChecks.collectPages(
      250,
      _ =>
        n += 1
        Right((Nil, Some(s"t$n"))), // empty pages with an always-new token
    )
    assertEquals(out, Right(Nil))
    assert(n <= 4, s"fetched $n pages for topK 250")
    var m = 0
    val _ = VectorChecks.collectPages(
      1,
      _ =>
        m += 1
        Right((Nil, Some(s"u$m"))),
    )
    assert(m <= 2, s"fetched $m pages for topK 1")
  }

  test("collectPages stops on a repeated token") {
    var n   = 0
    val out = VectorChecks.collectPages(
      10000,
      _ =>
        n += 1
        Right((List(hit(n, 0.5), hit(n + 100, 0.4)), Some("same"))),
    )
    assertEquals(n, 2)
    assertEquals(out.map(_.size), Right(4))
  }

  test("collectPages stops without a next token, sorts by score and cuts to topK") {
    val out = VectorChecks.collectPages(
      3,
      _ => Right((List(hit(1, 0.1), hit(2, 0.9), hit(3, 0.5), hit(4, 0.7)), None)),
    )
    assertEquals(out.map(_.map(_.vectorKey)), Right(List("k0002", "k0004", "k0003")))
  }

  test("collectPages passes a fetch error through and stops") {
    var n   = 0
    val err = StorageError.Unavailable("down")
    val out = VectorChecks.collectPages(
      500,
      _ =>
        n += 1
        if n == 2 then Left(err) else Right((List(hit(n, 0.5)), Some(s"t$n"))),
    )
    assertEquals(out, Left(err))
    assertEquals(n, 2)
  }

  test("filter documents use the S3 Vectors operator names ($eq, $in, $and)") {
    val d = VectorChecks.filterDocument(
      MetadataFilter.And(
        List(MetadataFilter.Equals("a", "1"), MetadataFilter.In("b", List("x", "y")))
      )
    )
    val text = d.toString
    assert(text.contains("$and") && text.contains("$eq") && text.contains("$in"), text)
  }
