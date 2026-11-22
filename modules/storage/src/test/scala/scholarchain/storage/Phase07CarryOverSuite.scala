package scholarchain.storage

import java.util.concurrent.atomic.AtomicInteger

import scholarchain.core.*

import StorageFx.*

/** Phase 07 carry-overs fixed in Phase 08: the Neptune load format, the vector test index, and the
  * new-run reload of an identical vector.
  */
class Phase07CarryOverSuite extends munit.FunSuite:

  private val Idx = VectorIndexes.ResearcherProfile

  private def storageCfg: StorageModuleConfig =
    StorageConfigLoader.load(Map.empty, Map.empty).fold(e => fail(e.toString), _.config)

  private def embeddingSettings: EmbeddingSettings =
    EmbeddingConfigLoader.load(Map.empty).fold(e => fail(e.toString), _.settings)

  // ---- (1) Neptune load format --------------------------------------------------------------------

  test("Neptune: the openCypher format is accepted by checkConfig") {
    val n = storageCfg.neptune
    assertEquals(n.format, "opencypher")
    assertEquals(NeptuneBulkLoad.checkConfig(n), Right(n))
    assertEquals(NeptuneBulkLoad.RequiredFormat, "opencypher")
  }

  test("Neptune: csv and every other format are rejected with a clear error") {
    List("csv", "CSV", "gremlin", "OpenCypher", "", "opencypher ", "ntriples").foreach { f =>
      val n = storageCfg.neptune.copy(format = f)
      NeptuneBulkLoad.checkConfig(n) match
        case Left(StorageError.InvalidRequest(msg)) =>
          assert(msg.contains("opencypher"), s"error for '$f' must name the required format: $msg")
          assert(msg.contains(s"'$f'"), s"error must show the rejected value: $msg")
        case other => fail(s"format '$f' must be rejected, got $other")
    }
  }

  test("Neptune: buildRequest refuses a csv configuration before building a loader request") {
    val n   = storageCfg.neptune
    val req = BulkLoadRequest(n.sourceS3Uri + "run1/", "csv", run1)
    val r   = NeptuneBulkLoad.buildRequest(n.copy(format = "csv"), req, "us-east-1")
    assertEquals(r.left.map(_.code), Left("invalid-request"))
  }

  test("Neptune: buildRequest still builds an openCypher request for a valid source") {
    val n   = storageCfg.neptune
    val req = BulkLoadRequest(n.sourceS3Uri + "run1/", "opencypher", run1)
    val r = NeptuneBulkLoad.buildRequest(n, req, "us-east-1").fold(e => fail(e.toString), identity)
    assertEquals(r.format, "opencypher")
    assertEquals(r.updateSingleCardinalityProperties, false)
  }

  test("Neptune: AwsClients.graphStore checks the load format first, with no credential call") {
    final class Tripping extends software.amazon.awssdk.auth.credentials.AwsCredentialsProvider:
      val calls                = AtomicInteger(0)
      def resolveCredentials() =
        calls.incrementAndGet()
        throw IllegalStateException("credentials must not be resolved")
    val p   = Tripping()
    val c   = AwsClients(p, software.amazon.awssdk.regions.Region.US_EAST_1)
    val bad = storageCfg.copy(neptune = storageCfg.neptune.copy(format = "csv"))
    c.graphStore(bad) match
      case Left(StorageError.InvalidRequest(m)) => assert(m.contains("opencypher"), m)
      case other                                => fail(s"expected the format error, got $other")
    assertEquals(p.calls.get, 0)
  }

  // ---- (2) vector test index -----------------------------------------------------------------------

  test(
    "VectorTestIndex.resolve: the shipped test index is accepted and is not a production index"
  ) {
    val r = VectorTestIndex.resolve(embeddingSettings, storageCfg)
    assertEquals(r, Right("scholarchain-it-embedding-test"))
    assert(!VectorIndexes.all.contains(r.toOption.get))
  }

  test(
    "VectorTestIndex.resolve: a production index name or a configured storage index is refused"
  ) {
    VectorIndexes.all.foreach { i =>
      assert(
        VectorTestIndex.resolve(embeddingSettings.copy(testVectorIndex = i), storageCfg).isLeft,
        i,
      )
    }
    val customStorage = storageCfg.copy(vectors =
      storageCfg.vectors.copy(researcherProfileIndex = "custom-prod-index")
    )
    assert(
      VectorTestIndex
        .resolve(embeddingSettings.copy(testVectorIndex = "custom-prod-index"), customStorage)
        .isLeft
    )
  }

  private final class Counting(val inner: InMemoryVectorStore) extends VectorStore:
    val calls                                     = AtomicInteger(0)
    def putBatch(i: String, v: List[VectorEntry]) = {
      calls.incrementAndGet(); inner.putBatch(i, v)
    }
    def query(i: String, q: VectorQuery) = { calls.incrementAndGet(); inner.query(i, q) }
    def get(i: String, k: String)        = { calls.incrementAndGet(); inner.get(i, k) }

  test("VectorTestIndex.guard: a live suite can write, query and get only in the test index") {
    val test = "scholarchain-it-embedding-test"
    val raw  = Counting(InMemoryVectorStore(Map(test -> 3, Idx -> 3)))
    val g    = VectorTestIndex.guard(raw, test)
    val e    = entry("k1", "t1", index = test)
    assertEquals(
      g.putBatch(test, List(e)).map(_.map(_.outcome)),
      Right(List(VectorPutOutcome.Accepted)),
    )
    assert(g.get(test, "k1").exists(_.isDefined))
    assert(g.query(test, VectorQuery(Vector(1f, 0f, 0f), 1)).exists(_.nonEmpty))
    assertEquals(raw.calls.get, 3)
  }

  test(
    "VectorTestIndex.guard: every call on a production index is refused and never reaches the store"
  ) {
    val test = "scholarchain-it-embedding-test"
    val raw  = Counting(InMemoryVectorStore(VectorIndexes.all.map(_ -> 3).toMap + (test -> 3)))
    val g    = VectorTestIndex.guard(raw, test)
    VectorIndexes.all.foreach { idx =>
      val e = entry("k-prod", "t", index = idx)
      assertEquals(g.putBatch(idx, List(e)).left.map(_.code), Left("invalid-request"), idx)
      assertEquals(g.get(idx, "k-prod").left.map(_.code), Left("invalid-request"), idx)
      assertEquals(
        g.query(idx, VectorQuery(Vector(1f, 0f, 0f), 1)).left.map(_.code),
        Left("invalid-request"),
        idx,
      )
    }
    assertEquals(raw.calls.get, 0, "no refused call may reach the delegate")
    assertEquals(raw.inner.get(Idx, "k-prod"), Right(None))
  }

  test(
    "live suites: both vector-writing suites use the validated test index and the guard, never a production index name"
  ) {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val dir  = root.resolve("modules/storage/src/it/scala/scholarchain/storage")
    assume(java.nio.file.Files.isDirectory(dir), "src/it is not present")
    List("S3VectorsAwsItSuite.scala", "EmbeddingQueueAwsItSuite.scala").foreach { f =>
      val text = new String(java.nio.file.Files.readAllBytes(dir.resolve(f)), "UTF-8")
      assert(text.contains("VectorTestIndex.resolve"), s"$f must resolve the validated test index")
      assert(text.contains("VectorTestIndex.guard"), s"$f must wrap its store with the guard")
      val prod = List(
        "VectorIndexes.ResearcherProfile",
        "VectorIndexes.CollaborationCommunity",
        "VectorIndexes.PaperAbstract",
        "VectorIndexes.CollaborationPath",
        "VectorIndexes.TopicInstitutionSummary",
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1",
      ).filter(text.contains)
      assertEquals(prod, Nil, s"$f names a production index")
    }
  }

  // ---- (3) identical reload from a new run; different text hash still rejected ----------------------

  private def freshStore() = InMemoryVectorStore(Map(Idx -> 3))

  private def outcome(s: InMemoryVectorStore, e: VectorEntry) =
    s.putBatch(Idx, List(e)).fold(x => fail(x.toString), _.head.outcome)

  test("identical vector reloaded from a NEW run is an accepted no-op and keeps the first run ID") {
    val s     = freshStore()
    val first = entry("k1", "text one")
    assertEquals(outcome(s, first), VectorPutOutcome.Accepted)
    val second = first.copy(record = first.record.copy(runId = runIdFor("spark-graph", 2)))
    assertNotEquals(second.record.runId, first.record.runId)
    assertEquals(outcome(s, second), VectorPutOutcome.Accepted)
    val kept = s.get(Idx, "k1").fold(x => fail(x.toString), identity).get
    assertEquals(kept.record.runId, first.record.runId, "the stored vector must keep its first run")
    assertEquals(kept, first)
  }

  test(
    "a different text hash under the same key is still rejected as a key collision, from any run"
  ) {
    val s     = freshStore()
    val first = entry("k1", "text one")
    outcome(s, first)
    val other  = entry("k1", "completely different text")
    val newRun = other.copy(record = other.record.copy(runId = runIdFor("spark-graph", 2)))
    assertEquals(outcome(s, other), VectorPutOutcome.Rejected(VectorRejection.KeyCollision))
    assertEquals(outcome(s, newRun), VectorPutOutcome.Rejected(VectorRejection.KeyCollision))
    assertEquals(
      s.get(Idx, "k1").toOption.flatten.map(_.record.textHash),
      Some(first.record.textHash),
    )
  }

  test("the same text under a new model version is an embedding mismatch, not a silent replace") {
    val s     = freshStore()
    val first = entry("k1", "text one")
    outcome(s, first)
    val v2 = first.copy(record =
      first.record.copy(embeddingModelVersion = "v2", runId = runIdFor("spark-graph", 2))
    )
    assertEquals(outcome(s, v2), VectorPutOutcome.Rejected(VectorRejection.EmbeddingMismatch))
    assertEquals(s.get(Idx, "k1").toOption.flatten.map(_.record.embeddingModelVersion), Some("v1"))
  }

  test("the same text with a different embedding is an embedding mismatch") {
    val s     = freshStore()
    val first = entry("k1", "text one", Vector(1f, 0f, 0f))
    outcome(s, first)
    val changed = entry("k1", "text one", Vector(0f, 1f, 0f))
    assertEquals(outcome(s, changed), VectorPutOutcome.Rejected(VectorRejection.EmbeddingMismatch))
  }

  test(
    "the same text with other record metadata is an embedding mismatch (not ignored like the run ID)"
  ) {
    val s     = freshStore()
    val first = entry("k1", "text one")
    outcome(s, first)
    val other = entry("k1", "text one", md = Map("window" -> "2005-2009"))
    assertEquals(outcome(s, other), VectorPutOutcome.Rejected(VectorRejection.EmbeddingMismatch))
  }

  test("VectorLoader: reloading the same vectors from a new run reports no rejection") {
    val s    = freshStore()
    val sink = InMemoryRejectionSink()
    val l    = VectorLoader(s, StorageRetry(2, 1L, 2L), (_: Long) => (), sink)
    val es   = List(entry("k1", "t1"), entry("k2", "t2"))
    assert(l.load(Idx, es).isRight)
    val again = es.map(e => e.copy(record = e.record.copy(runId = runIdFor("spark-graph", 3))))
    assert(l.load(Idx, again).isRight)
    assertEquals(sink.all, Nil)
  }
