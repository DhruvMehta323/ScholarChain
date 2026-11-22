package scholarchain.storage

import scholarchain.core.*

import EmbeddingFx.*
import StorageFx.*

/** Phase 08 repair 01, M3: the same vector key can never silently refer to different text, even
  * with concurrent workers. The vector key is claimed with a conditional put before the vector is
  * written; only the claim winner (or an identical claim) writes. In memory only: no AWS client, no
  * network, no credential.
  */
class EmbeddingClaimsSuite extends munit.FunSuite:

  private val Idx = VectorIndexes.ResearcherProfile

  private val textA = "the first rendering of this researcher profile"
  private val textB = "a second, different rendering of the same researcher profile"

  /** The same document (same key) with two different texts. */
  private def docA: EmbeddingDocument = doc(textA, "Ada Lovelace")
  private def docB: EmbeddingDocument = doc(textB, "Ada Lovelace")

  private def putText(rig: Rig, text: String): Unit =
    rig.objects
      .put(ObjectLocation.parse(uriFor(text)).toOption.get, text.getBytes("UTF-8"))
      .fold(e => fail(e.toString), _ => ())

  private def jobFor(d: EmbeddingDocument, id: String, run: String): EmbeddingJob =
    jobOf(List(d), runFor(run)).copy(jobId = id)

  private def send(rig: Rig, job: EmbeddingJob): Unit =
    rig.queue.send("jobs", encode(job), Some(job.jobId)).fold(e => fail(e.toString), _ => ())

  private def claimOf(rig: Rig, key: String): Option[Map[String, String]] =
    rig.claimsRaw
      .get(ClaimsTable, KvKey(key))
      .fold(e => fail(e.toString), identity)
      .map(_.attributes)

  private def recordOf(
      d: EmbeddingDocument,
      version: String = "v1",
      model: String = "test-embedder",
      dim: Int = 4,
      uri: Option[String] = None,
      index: String = Idx,
      run: String = runFor("rec"),
  ): VectorRecord =
    VectorRecord(
      1,
      d.documentId,
      index,
      dim,
      model,
      version,
      d.textHash,
      uri.getOrElse(d.textS3Uri),
      Map("documentType" -> d.documentType),
      run,
    )

  /** A vector store whose `get` always answers "absent" (a stale read before another worker wrote).
    */
  private final class StaleGets(delegate: VectorStore) extends VectorStore:
    def putBatch(i: String, vs: List[VectorEntry]) = delegate.putBatch(i, vs)
    def query(i: String, q: VectorQuery)           = delegate.query(i, q)
    def get(i: String, k: String)                  = Right(None)

  /** Runs `hook` once, inside the first embed call (the worker has passed its idempotency lookup).
    */
  private final class HookEmbedder(inner: FakeEmbedder, hook: () => Unit) extends Embedder:
    private var fired              = false
    def modelId: String            = inner.modelId
    def modelVersion: String       = inner.modelVersion
    def dimension: Int             = inner.dimension
    def maxBatchSize: Int          = inner.maxBatchSize
    def embed(texts: List[String]) =
      if !fired then
        fired = true
        hook()
      inner.embed(texts)

  // ---- the claim itself --------------------------------------------------------------------------------

  test("claim: the first claim of a key wins and records identity plus provenance") {
    val rig    = Rig()
    val claims = EmbeddingKeyClaims(rig.claimsRaw, ClaimsTable)
    val rec    = recordOf(docA, run = runFor("one"))
    assertEquals(claims.claim(rec, "job-1"), ClaimOutcome.Won)
    val stored = claimOf(rig, rec.vectorKey).get
    assertEquals(stored(EmbeddingKeyClaims.TextHash), docA.textHash)
    assertEquals(stored(EmbeddingKeyClaims.ModelId), "test-embedder")
    assertEquals(stored(EmbeddingKeyClaims.ModelVersion), "v1")
    assertEquals(stored(EmbeddingKeyClaims.Dimension), "4")
    assertEquals(stored(EmbeddingKeyClaims.TextS3Uri), docA.textS3Uri)
    assertEquals(stored(EmbeddingKeyClaims.IndexName), Idx)
    assertEquals(stored(EmbeddingKeyClaims.RunId), runFor("one"))
    assertEquals(stored(EmbeddingKeyClaims.JobId), "job-1")
  }

  test("claim: an identical claim from a NEW run and job is Identical and changes nothing") {
    val rig    = Rig()
    val claims = EmbeddingKeyClaims(rig.claimsRaw, ClaimsTable)
    assertEquals(claims.claim(recordOf(docA, run = runFor("one")), "job-1"), ClaimOutcome.Won)
    val before = claimOf(rig, docA.documentId)
    assertEquals(claims.claim(recordOf(docA, run = runFor("two")), "job-2"), ClaimOutcome.Identical)
    assertEquals(claimOf(rig, docA.documentId), before, "the first claim stays as it was written")
    assertEquals(claimOf(rig, docA.documentId).get(EmbeddingKeyClaims.RunId), runFor("one"))
  }

  test("claim: a different text hash is KeyCollision and the claim is not replaced") {
    val rig    = Rig()
    val claims = EmbeddingKeyClaims(rig.claimsRaw, ClaimsTable)
    claims.claim(recordOf(docA), "job-1")
    assertEquals(claims.claim(recordOf(docB), "job-2"), ClaimOutcome.KeyCollision)
    assertEquals(claimOf(rig, docA.documentId).get(EmbeddingKeyClaims.TextHash), docA.textHash)
  }

  test(
    "claim: the same hash with another model version, model, dimension, pointer or index mismatches"
  ) {
    val rig    = Rig()
    val claims = EmbeddingKeyClaims(rig.claimsRaw, ClaimsTable)
    claims.claim(recordOf(docA), "job-1")
    val variants = List(
      "version"   -> recordOf(docA, version = "v2"),
      "model"     -> recordOf(docA, model = "other-embedder"),
      "dimension" -> recordOf(docA, dim = 8),
      "pointer" -> recordOf(docA, uri = Some(s"s3://${EmbeddingFx.bucket}/rag/text/elsewhere.txt")),
      "index"   -> recordOf(docA, index = VectorIndexes.CollaborationCommunity),
    )
    variants.foreach((name, rec) =>
      assertEquals(claims.claim(rec, "job-x"), ClaimOutcome.EmbeddingMismatch, name)
    )
  }

  test(
    "claim: a store failure is StoreFailed with a claim-store-failed code, and writes no claim"
  ) {
    val rig = Rig()
    rig.claimsKv.failure = Some(StorageError.Unavailable("down"))
    val out = EmbeddingKeyClaims(rig.claimsKv, ClaimsTable).claim(recordOf(docA), "job-1")
    out match
      case ClaimOutcome.StoreFailed(code) => assert(code.startsWith("claim-store-failed"), code)
      case other                          => fail(s"expected StoreFailed, got $other")
    assertEquals(claimOf(rig, docA.documentId), None)
  }

  test("claim: a missing claims table is StoreFailed, never Won") {
    val kv  = InMemoryKeyValueStore(Map("some-other-table" -> EmbeddingKeyClaims.Schema))
    val out = EmbeddingKeyClaims(kv, ClaimsTable).claim(recordOf(docA), "job-1")
    assert(out.isInstanceOf[ClaimOutcome.StoreFailed], out.toString)
  }

  test("claim: a store that reports a failed condition with no existing item is StoreFailed") {
    val ambiguous = new KeyValueStore:
      def put(t: String, i: KvItem, c: PutCondition) = Right(PutOutcome.ConditionFailed(None))
      def get(t: String, k: KvKey)                   = Right(None)
      def query(
          t: String,
          p: String,
          s: Option[SortCondition],
          l: Int,
          a: Boolean,
          c: Option[String],
      ) = Right(KvPage(Nil, None))
    val out = EmbeddingKeyClaims(ambiguous, ClaimsTable).claim(recordOf(docA), "job-1")
    assert(out.isInstanceOf[ClaimOutcome.StoreFailed], out.toString)
  }

  // ---- two workers, same key, different hashes ---------------------------------------------------------

  test("two workers: job A wins and writes; job B (stale lookup) is dead-lettered key-collision") {
    val rig = Rig()
    putText(rig, textA)
    putText(rig, textB)
    val second = rig.secondWorker(v = StaleGets(rig.vectors))

    send(rig, jobFor(docA, "embjob-a", "a"))
    val a = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(a.vectorsWritten, 1)

    send(rig, jobFor(docB, "embjob-b", "b"))
    val b = second.runOnce().fold(e => fail(e.toString), identity)

    assertEquals(b.vectorsWritten, 0)
    assertEquals(b.rejectedByReason, Map("key-collision" -> 1))
    assertEquals(b.documentsDeadLettered, 1)
    assertEquals(rig.vectors.puts.get, 1, "exactly one putBatch for the key, by the winner")
    assertEquals(rig.stored(Idx, docA.documentId).get.record.textHash, docA.textHash)
    assertEquals(claimOf(rig, docA.documentId).get(EmbeddingKeyClaims.TextHash), docA.textHash)
    val dead = rig.jobsIn("dlq")
    assertEquals(dead.map(_.jobId), List("embjob-b"))
    assert(dead.head.failureReason.exists(_.contains("key-collision=1")))
  }

  test(
    "interleaved: B finishes inside A's embed call; B wins, A is dead-lettered, stored text is B's"
  ) {
    val rig = Rig()
    putText(rig, textA)
    putText(rig, textB)
    val workerB = rig.secondWorker()
    var bCounts = Option.empty[EmbeddingCounts]
    val hook    = () =>
      // A has passed its lookup (no vector) and is about to claim; B now runs a whole job.
      send(rig, jobFor(docB, "embjob-b", "b"))
      bCounts = Some(workerB.runOnce().fold(e => fail(e.toString), identity))
    val workerA = rig.secondWorker(e = HookEmbedder(rig.embedder, hook))

    send(rig, jobFor(docA, "embjob-a", "a"))
    val a = workerA.runOnce().fold(e => fail(e.toString), identity)

    assertEquals(bCounts.map(_.vectorsWritten), Some(1), "B ran to completion inside A's embed")
    assertEquals(a.vectorsWritten, 0)
    assertEquals(a.rejectedByReason, Map("key-collision" -> 1))
    assertEquals(rig.vectors.puts.get, 1, "exactly one vector write for the key")
    val stored = rig.stored(Idx, docA.documentId).get.record
    assertEquals(stored.textHash, docB.textHash, "the stored vector is the claim winner's")
    assertEquals(stored.textS3Uri, docB.textS3Uri)
    assertEquals(claimOf(rig, docA.documentId).get(EmbeddingKeyClaims.TextHash), stored.textHash)
    assertEquals(rig.jobsIn("dlq").map(_.jobId), List("embjob-a"))
  }

  test("interleaved: the loser's ledger entry records the collision by code, never the text") {
    val rig = Rig()
    putText(rig, textA)
    putText(rig, textB)
    val workerB = rig.secondWorker()
    val hook    = () =>
      send(rig, jobFor(docB, "embjob-b", "b"))
      workerB.runOnce().fold(e => fail(e.toString), identity)
      ()
    val workerA = rig.secondWorker(e = HookEmbedder(rig.embedder, hook))
    send(rig, jobFor(docA, "embjob-a", "a"))
    workerA.runOnce()
    val failed = rig.ledgerEntries(EmbeddingLedger.JobStage).filter(_.status == RunStatus.Failed)
    assertEquals(failed.size, 1)
    val why = failed.head.failureReason.getOrElse("")
    assert(why.contains("key-collision=1"), why)
    assert(!why.contains(textA) && !why.contains(textB))
  }

  test("a pre-existing claim for other text blocks the write even though no vector exists yet") {
    val rig = Rig()
    putText(rig, textB)
    assertEquals(
      EmbeddingKeyClaims(rig.claimsRaw, ClaimsTable).claim(recordOf(docA), "other-worker"),
      ClaimOutcome.Won,
    )
    send(rig, jobFor(docB, "embjob-b", "b"))
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.rejectedByReason, Map("key-collision" -> 1))
    assertEquals(rig.vectors.puts.get, 0, "putBatch must never be called without the claim")
    assertEquals(rig.stored(Idx, docA.documentId), None)
  }

  // ---- identical claims and mismatches -----------------------------------------------------------------

  test("identical claim, no vector yet: the write completes and the claim is unchanged") {
    val rig = Rig()
    putText(rig, textA)
    val before = EmbeddingKeyClaims(rig.claimsRaw, ClaimsTable)
    assertEquals(before.claim(recordOf(docA, run = runFor("older")), "older-job"), ClaimOutcome.Won)
    send(rig, jobFor(docA, "embjob-a", "a"))
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.vectorsWritten, 1)
    assertEquals(c.rejectedByReason, Map.empty[String, Int])
    assertEquals(rig.stored(Idx, docA.documentId).get.record.textHash, docA.textHash)
    assertEquals(claimOf(rig, docA.documentId).get(EmbeddingKeyClaims.JobId), "older-job")
  }

  test("a new run of the same text after success is a no-op: no claim write, no embed, no put") {
    val rig = Rig()
    val s   = source(textA)
    rig.enqueue(runFor("first"), s)
    assertEquals(rig.drain().counts.vectorsWritten, 1)
    val putsBefore   = rig.vectors.puts.get
    val claimsBefore = rig.claimsKv.puts.get
    val callsBefore  = rig.embedder.calls.get
    rig.enqueue(runFor("second"), s)
    val c = rig.drain().counts
    assertEquals(c.skippedUnchanged, 1)
    assertEquals(c.vectorsWritten, 0)
    assertEquals(rig.vectors.puts.get, putsBefore)
    assertEquals(rig.claimsKv.puts.get, claimsBefore)
    assertEquals(rig.embedder.calls.get, callsBefore)
    assertEquals(claimOf(rig, s.document.documentId).get(EmbeddingKeyClaims.RunId), runFor("first"))
  }

  test("a new run through a stale lookup meets an identical claim and rewrites idempotently") {
    val rig = Rig()
    putText(rig, textA)
    send(rig, jobFor(docA, "embjob-a", "first"))
    assertEquals(rig.worker.runOnce().fold(e => fail(e.toString), identity).vectorsWritten, 1)
    send(rig, jobFor(docA, "embjob-a2", "second"))
    val c =
      rig.secondWorker(v = StaleGets(rig.vectors)).runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.rejectedByReason, Map.empty[String, Int])
    assertEquals(c.jobsDeadLettered, 0)
    assertEquals(rig.stored(Idx, docA.documentId).get.record.textHash, docA.textHash)
    assertEquals(rig.jobsIn("dlq"), Nil)
  }

  test("same hash, different model version: embedding-mismatch, no write, the first vector stays") {
    val rig = Rig()
    putText(rig, textA)
    send(rig, jobFor(docA, "embjob-a", "a"))
    assertEquals(rig.worker.runOnce().fold(e => fail(e.toString), identity).vectorsWritten, 1)

    val v2     = FakeEmbedder(modelVersion = "v2")
    val worker = rig.secondWorker(e = v2, v = StaleGets(rig.vectors))
    send(rig, jobOf(List(docA), runFor("b"), version = "v2").copy(jobId = "embjob-b"))
    val c = worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.rejectedByReason, Map("embedding-mismatch" -> 1))
    assertEquals(c.vectorsWritten, 0)
    assertEquals(rig.vectors.puts.get, 1)
    assertEquals(rig.stored(Idx, docA.documentId).get.record.embeddingModelVersion, "v1")
    assertEquals(claimOf(rig, docA.documentId).get(EmbeddingKeyClaims.ModelVersion), "v1")
  }

  // ---- claim store failures ----------------------------------------------------------------------------

  test(
    "claim store failure: the document is retried as claim-store-failed and putBatch is never called"
  ) {
    val rig = Rig()
    val s   = source(textA)
    rig.enqueue(runFor("a"), s)
    rig.claimsKv.failure = Some(StorageError.Unavailable("claims down"))
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.documentsRetried, 1)
    assertEquals(c.vectorsWritten, 0)
    assertEquals(c.documentsDeadLettered, 0)
    assertEquals(rig.vectors.puts.get, 0, "no unclaimed write")
    assertEquals(rig.stored(Idx, s.document.documentId), None)
    val failed = rig.ledgerEntries(EmbeddingLedger.JobStage).filter(_.status == RunStatus.Failed)
    assert(
      failed.headOption.flatMap(_.failureReason).exists(_.contains("claim-store-failed=1")),
      failed.map(_.failureReason).toString,
    )
    val retry = rig.jobsIn("jobs")
    assertEquals(retry.map(_.attempt), List(2))
  }

  test("claim store failure: once the store recovers the retry claims and writes exactly once") {
    val rig = Rig()
    val s   = source(textA)
    rig.enqueue(runFor("a"), s)
    rig.claimsKv.failure = Some(StorageError.Unavailable("claims down"))
    rig.worker.runOnce()
    rig.claimsKv.failure = None
    val c = rig.drain().counts
    assertEquals(c.vectorsWritten, 1)
    assertEquals(rig.vectors.puts.get, 1)
    assertEquals(
      claimOf(rig, s.document.documentId).get(EmbeddingKeyClaims.TextHash),
      s.document.textHash,
    )
  }

  test("claim store failure for good: retries-exhausted at the attempt bound, never a write") {
    val rig = Rig(maxAttempts = 3)
    val s   = source(textA)
    rig.enqueue(runFor("a"), s)
    rig.claimsKv.failure = Some(StorageError.Unavailable("claims down"))
    val r = rig.drain()
    assertEquals(r.stoppedBy, "empty")
    assertEquals(r.counts.jobsProcessed, 3)
    assertEquals(r.counts.jobsRetried, 2)
    assertEquals(r.counts.rejectedByReason, Map("retries-exhausted" -> 1))
    assertEquals(r.counts.vectorsWritten, 0)
    assertEquals(
      rig.vectors.puts.get,
      0,
      "putBatch must never be called while the claim store fails",
    )
    assertEquals(rig.stored(Idx, s.document.documentId), None)
    val dead = rig.jobsIn("dlq")
    assertEquals(dead.map(_.documents.map(_.documentId)), List(List(s.document.documentId)))
    assert(dead.head.failureReason.exists(_.contains("retries-exhausted=1")))
  }

  test(
    "claim store failure for one key does not stop a sibling key from being claimed and written"
  ) {
    val rig = Rig()
    val a   = source("text of researcher number one", "Person 1")
    val b   = source("text of researcher number two", "Person 2")
    rig.enqueue(runFor("a"), a, b)
    // fail only puts for the first key
    val selective = new KeyValueStore:
      def put(t: String, i: KvItem, c: PutCondition) =
        if i.key.partition == a.document.documentId then Left(StorageError.Unavailable("x"))
        else rig.claimsRaw.put(t, i, c)
      def get(t: String, k: KvKey) = rig.claimsRaw.get(t, k)
      def query(
          t: String,
          p: String,
          s: Option[SortCondition],
          l: Int,
          asc: Boolean,
          c: Option[String],
      ) = rig.claimsRaw.query(t, p, s, l, asc, c)
    val worker = EmbeddingWorker(
      rig.workerConfig,
      rig.queue,
      rig.objects,
      rig.embedder,
      rig.vectors,
      selective,
      Some(rig.ledger),
    )
    val c = worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.vectorsWritten, 1)
    assertEquals(c.documentsRetried, 1)
    assert(rig.stored(Idx, b.document.documentId).isDefined)
    assertEquals(rig.stored(Idx, a.document.documentId), None)
  }

  test(
    "claim won, then the vector write fails: the retry finds its own identical claim and writes"
  ) {
    val rig = Rig()
    val s   = source(textA)
    rig.enqueue(runFor("a"), s)
    rig.vectors.putFailure = Some(StorageError.Throttled("429"))
    val c1 = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c1.documentsRetried, 1)
    assert(claimOf(rig, s.document.documentId).isDefined, "the claim stays after a failed write")
    rig.vectors.putFailure = None
    val c2 = rig.drain().counts
    assertEquals(c2.vectorsWritten, 1)
    assertEquals(c2.rejectedByReason, Map.empty[String, Int])
    assertEquals(rig.stored(Idx, s.document.documentId).get.record.textHash, s.document.textHash)
  }

  // ---- configuration wiring ----------------------------------------------------------------------------

  test("the worker config defaults the claims table to the core default and follows the settings") {
    assertEquals(
      Rig().workerConfig.claimsTable,
      "scholarchain-embedding-key-claims",
    )
    val s = EmbeddingConfigLoader.load(Map.empty).fold(e => fail(e.toString), _.settings)
    assertEquals(EmbeddingWorkerConfig.fromSettings(s).claimsTable, s.claimsTableName)
    val custom = EmbeddingConfigLoader
      .load(Map("SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME" -> "my-claims-table"))
      .fold(e => fail(e.toString), _.settings)
    assertEquals(EmbeddingWorkerConfig.fromSettings(custom).claimsTable, "my-claims-table")
  }

  test("claims are written to the configured table, not to another one") {
    val rig    = Rig()
    val other  = InMemoryKeyValueStore(Map("my-claims-table" -> EmbeddingKeyClaims.Schema))
    val worker = EmbeddingWorker(
      rig.workerConfig.copy(claimsTable = "my-claims-table"),
      rig.queue,
      rig.objects,
      rig.embedder,
      rig.vectors,
      other,
      Some(rig.ledger),
    )
    val s = source(textA)
    rig.enqueue(runFor("a"), s)
    assertEquals(worker.drain(5).counts.vectorsWritten, 1)
    assert(other.get("my-claims-table", KvKey(s.document.documentId)).toOption.flatten.isDefined)
    assertEquals(claimOf(rig, s.document.documentId), None)
  }
end EmbeddingClaimsSuite
