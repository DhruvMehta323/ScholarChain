package scholarchain.storage

import java.time.Instant

import scholarchain.core.*

import EmbeddingFx.*
import StorageFx.*

/** Phase 08 queue worker on the fakes: delete on success, retry, dead letter, idempotency, key
  * collisions, ledger.
  */
class EmbeddingWorkerSuite extends munit.FunSuite:

  private val Idx = VectorIndexes.ResearcherProfile

  private def srcs(n: Int): List[EmbeddingSource] =
    (1 to n).toList.map(i => source(s"text of researcher number $i", s"Person $i"))

  // ---- success: delete, route, metadata ---------------------------------------------------------

  test("success: every job is embedded, stored and its message deleted") {
    val rig = Rig()
    rig.enqueue(runFor("a"), srcs(3)*)
    val r = rig.drain()
    assertEquals(r.stoppedBy, "empty")
    val c = r.counts
    assertEquals(c.jobsProcessed, 2)
    assertEquals(c.documentsSeen, 3)
    assertEquals(c.documentsEmbedded, 3)
    assertEquals(c.vectorsWritten, 3)
    assertEquals(c.skippedUnchanged, 0)
    assertEquals(c.jobsRetried, 0)
    assertEquals(c.jobsDeadLettered, 0)
    assertEquals(c.queueFailures, 0)
    assertEquals(c.ledgerFailures, 0)
    assertEquals(c.rejectedByReason, Map.empty[String, Int])
    assertEquals(rig.queue.deletes.get, 2)
    // an undeleted message would reappear once the visibility timeout has passed
    assertEquals(rig.bodies("jobs"), Nil)
    assertEquals(rig.bodies("dlq"), Nil)
  }

  test("success: the vector carries model ID, version, dimension, pointer, hash and run") {
    val rig = Rig()
    val s   = source("alpha beta gamma")
    rig.enqueue(runFor("a"), s)
    rig.drain()
    val e = rig.stored(Idx, s.document.documentId).getOrElse(fail("vector not stored"))
    val r = e.record
    assertEquals(r.vectorKey, s.document.documentId)
    assertEquals(r.embeddingModelId, "test-embedder")
    assertEquals(r.embeddingModelVersion, "v1")
    assertEquals(r.dimension, 4)
    assertEquals(r.textHash, s.document.textHash)
    assertEquals(r.textS3Uri, s.document.textS3Uri)
    assertEquals(r.runId, runFor("a"))
    assertEquals(e.embedding, rig.embedder.vecOf("alpha beta gamma"))
  }

  test("success: metadata is compact and never holds the text") {
    val rig  = Rig()
    val text = "a distinctive sentence that must never appear in vector metadata"
    val s    = source(text)
    rig.enqueue(runFor("a"), s)
    rig.drain()
    val md = rig.stored(Idx, s.document.documentId).get.record.metadata
    assertEquals(
      md.keySet,
      Set("documentType", "entityId", "window", "chunkOrdinal", "chunkCount"),
    )
    assertEquals(md("documentType"), "researcher_profile")
    assertEquals(md("window"), "2000-2004")
    assertEquals(md("chunkOrdinal"), "0")
    assertEquals(md("chunkCount"), "1")
    assert(md.values.forall(v => !text.contains(v) || v.length < 20), md.toString)
    assert(md.values.forall(_.length <= VectorRecord.MaxMetadataValueLength))
  }

  test("routing: each document type goes to its own index") {
    val rig = Rig()
    val a   = source("researcher text one", "Ada", EmbeddingDocumentTypes.ResearcherProfile)
    val b   = source("topic text two", "Topic X", EmbeddingDocumentTypes.TopicSummary)
    rig.enqueue(runFor("a"), a, b)
    rig.drain()
    assert(rig.stored(VectorIndexes.ResearcherProfile, a.document.documentId).isDefined)
    assert(rig.stored(VectorIndexes.TopicInstitutionSummary, b.document.documentId).isDefined)
    assert(rig.stored(VectorIndexes.ResearcherProfile, b.document.documentId).isEmpty)
  }

  test("routing: an index override sends every vector to the override index only") {
    val test = "scholarchain-it-embedding-test"
    val rig  = Rig(indexOverride = Some(test))
    val a    = source("researcher text one")
    rig.enqueue(runFor("a"), a)
    rig.drain()
    assert(rig.stored(test, a.document.documentId).isDefined)
    assert(rig.stored(Idx, a.document.documentId).isEmpty)
  }

  test("drain: stops at the message bound and leaves the rest queued") {
    val rig = Rig()
    rig.enqueue(runFor("a"), srcs(4)*)
    val r = rig.drain(1)
    assertEquals(r.stoppedBy, "bound")
    assertEquals(r.counts.jobsProcessed, 1)
    assertEquals(rig.bodies("jobs").size, 1)
  }

  test("drain: a failing receive is reported by code and nothing is processed") {
    val rig = Rig()
    rig.enqueue(runFor("a"), srcs(2)*)
    rig.queue.failReceive = true
    val r = rig.drain()
    assertEquals(r.stoppedBy, "receive-error:unavailable")
    assertEquals(r.counts, EmbeddingCounts.zero)
  }

  // ---- retry and dead letter ----------------------------------------------------------------------

  test(
    "retry: a throttled embedder re-enqueues the document as attempt 2 and the original is deleted"
  ) {
    val emb =
      FakeEmbedder(script = n => if n == 1 then Some(EmbeddingError.Throttled("429")) else None)
    val rig = Rig(embedder = emb)
    rig.enqueue(runFor("a"), srcs(1)*)
    val c1 = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c1.jobsRetried, 1)
    assertEquals(c1.documentsRetried, 1)
    assertEquals(c1.vectorsWritten, 0)
    assertEquals(c1.jobsDeadLettered, 0)
    val queued = rig.jobsIn("jobs")
    assertEquals(queued.size, 1)
    assertEquals(queued.head.attempt, 2)
    assertEquals(queued.head.status, EmbeddingJobStatus.Pending)
    assertEquals(queued.head.documents.size, 1)
    rig.advanceClock() // the peek above made the message invisible; let it reappear
    val c2 = rig.drain().counts
    assertEquals(c2.vectorsWritten, 1)
    assertEquals(emb.calls.get, 2)
  }

  test("retry: only the failed documents are re-enqueued; documents already stored stay stored") {
    // one job of 2 documents, embed batch size 1: the first batch succeeds, the second is throttled
    val emb = FakeEmbedder(
      maxBatchSize = 1,
      script = n => if n == 2 then Some(EmbeddingError.Unavailable("5xx")) else None,
    )
    val rig = Rig(embedder = emb)
    val all = srcs(2)
    rig.enqueue(runFor("a"), all*)
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.vectorsWritten, 1)
    assertEquals(c.documentsRetried, 1)
    val queued = rig.jobsIn("jobs")
    assertEquals(queued.size, 1)
    assertEquals(queued.head.documents.size, 1)
    val retriedId = queued.head.documents.head.documentId
    val storedIds = all.map(_.document.documentId).filter(id => rig.stored(Idx, id).isDefined)
    assertEquals(storedIds.size, 1)
    assert(!storedIds.contains(retriedId))
  }

  test("dead letter: a message that keeps failing is dead-lettered after the attempt bound") {
    val emb = FakeEmbedder(script = _ => Some(EmbeddingError.Throttled("429")))
    val rig = Rig(maxAttempts = 3, embedder = emb)
    val s   = srcs(1).head
    rig.enqueue(runFor("a"), s)
    val r = rig.drain()
    assertEquals(r.stoppedBy, "empty")
    assertEquals(r.counts.jobsProcessed, 3)
    assertEquals(r.counts.jobsRetried, 2)
    assertEquals(r.counts.documentsRetried, 2)
    assertEquals(r.counts.jobsDeadLettered, 1)
    assertEquals(r.counts.documentsDeadLettered, 1)
    assertEquals(r.counts.rejectedByReason, Map("retries-exhausted" -> 1))
    assertEquals(r.counts.vectorsWritten, 0)
    assertEquals(emb.calls.get, 3)
    val dead = rig.jobsIn("dlq")
    assertEquals(dead.size, 1)
    assertEquals(dead.head.status, EmbeddingJobStatus.DeadLettered)
    assertEquals(dead.head.documents.map(_.documentId), List(s.document.documentId))
    assert(
      dead.head.failureReason.exists(_.contains("retries-exhausted=1")),
      dead.head.failureReason.toString,
    )
    assertEquals(rig.bodies("jobs"), Nil)
    assertEquals(rig.stored(Idx, s.document.documentId), None)
  }

  test("dead letter: the attempt bound follows the configured maximum (2 attempts)") {
    val emb = FakeEmbedder(script = _ => Some(EmbeddingError.Unavailable("5xx")))
    val rig = Rig(maxAttempts = 2, embedder = emb)
    rig.enqueue(runFor("a"), srcs(1)*)
    val c = rig.drain().counts
    assertEquals(c.jobsProcessed, 2)
    assertEquals(c.jobsRetried, 1)
    assertEquals(c.jobsDeadLettered, 1)
    assertEquals(emb.calls.get, 2)
  }

  test("poison: a non-retryable embedder failure is dead-lettered at once, never retried") {
    val emb = FakeEmbedder(script = _ => Some(EmbeddingError.Failed("boom")))
    val rig = Rig(embedder = emb)
    rig.enqueue(runFor("a"), srcs(1)*)
    val c = rig.drain().counts
    assertEquals(c.jobsProcessed, 1)
    assertEquals(c.jobsRetried, 0)
    assertEquals(c.jobsDeadLettered, 1)
    assertEquals(c.rejectedByReason, Map("embed-failed" -> 1))
    assertEquals(emb.calls.get, 1)
    assertEquals(rig.jobsIn("dlq").size, 1)
    assertEquals(rig.bodies("jobs"), Nil)
  }

  test(
    "poison: an unparseable message goes to the DLQ as an envelope with its body hash, not its body"
  ) {
    val rig  = Rig()
    val body = "this is { not a job SECRET-LOOKING-PAYLOAD"
    rig.queue.send("jobs", body)
    val c = rig.drain().counts
    assertEquals(c.jobsProcessed, 1)
    assertEquals(c.jobsDeadLettered, 1)
    assertEquals(c.rejectedByReason, Map("invalid-job-message" -> 1))
    assertEquals(rig.embedder.calls.get, 0)
    val dead = rig.bodies("dlq")
    assertEquals(dead.size, 1)
    assert(!dead.head.contains("SECRET-LOOKING-PAYLOAD"), "the body must not be copied")
    val j = ujson.read(dead.head)
    assertEquals(j("status").str, "dead_lettered")
    assertEquals(j("failureReason").str, "invalid-job-message")
    assertEquals(j("bodySha256").str, Hashing.textHash(body))
    assertEquals(rig.bodies("jobs"), Nil)
  }

  test("poison: a job whose attempt exceeds the bound is dead-lettered whole, no embed call") {
    val rig = Rig(maxAttempts = 3)
    val s   = srcs(1).head
    rig.objects.put(
      ObjectLocation.parse(s.document.textS3Uri).toOption.get,
      s.text.getBytes("UTF-8"),
    )
    rig.queue.send("jobs", encode(jobOf(List(s.document), runFor("a"), attempt = 4)))
    val c = rig.drain().counts
    assertEquals(c.rejectedByReason, Map("attempt-bound-exceeded" -> 1))
    assertEquals(c.documentsDeadLettered, 1)
    assertEquals(rig.embedder.calls.get, 0)
  }

  test("poison: a message redelivered more often than the bound is dead-lettered") {
    val rig = Rig(maxAttempts = 3)
    val s   = srcs(1).head
    rig.enqueue(runFor("a"), s)
    // three deliveries that are never acknowledged (a crashing consumer)
    (1 to 3).foreach { _ =>
      rig.advanceClock()
      assertEquals(rig.queueRaw.receive("jobs", 1, 30).map(_.size), Right(1))
    }
    rig.advanceClock()
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.rejectedByReason, Map("redelivery-bound-exceeded" -> 1))
    assertEquals(rig.embedder.calls.get, 0)
    assertEquals(rig.jobsIn("dlq").size, 1)
  }

  test("poison: a job built for another model is dead-lettered whole with no embed call") {
    val rig = Rig()
    val s   = srcs(1).head
    rig.queue.send("jobs", encode(jobOf(List(s.document), runFor("a"), model = "other-model")))
    val c = rig.drain().counts
    assertEquals(c.rejectedByReason, Map("model-mismatch" -> 1))
    assertEquals(rig.embedder.calls.get, 0)
    assertEquals(rig.vectors.puts.get, 0)
  }

  test("poison: a job built for another model version is dead-lettered whole") {
    val rig = Rig()
    val s   = srcs(1).head
    rig.queue.send("jobs", encode(jobOf(List(s.document), runFor("a"), version = "v9")))
    assertEquals(rig.drain().counts.rejectedByReason, Map("model-mismatch" -> 1))
  }

  test(
    "poison: a worker whose embedder dimension differs from the expected one dead-letters every job"
  ) {
    val rig    = Rig()
    val cfg    = rig.workerConfig.copy(expectedDimension = Some(99))
    val worker =
      EmbeddingWorker(cfg, rig.queue, rig.objects, rig.embedder, rig.vectors, rig.claimsKv)
    rig.enqueue(runFor("a"), srcs(1)*)
    val c = worker.drain(5).counts
    assertEquals(c.rejectedByReason, Map("dimension-mismatch" -> 1))
    assertEquals(rig.embedder.calls.get, 0)
  }

  test("poison: an embedder that returns a short vector is rejected as dimension-mismatch") {
    val rig = Rig(embedder = FakeEmbedder(shortBy = 1))
    rig.enqueue(runFor("a"), srcs(1)*)
    val c = rig.drain().counts
    assertEquals(c.vectorsWritten, 0)
    assertEquals(c.rejectedByReason, Map("dimension-mismatch" -> 1))
    assertEquals(rig.vectors.puts.get, 0)
  }

  test("text: a missing text object is dead-lettered as text-missing") {
    val rig = Rig()
    val s   = srcs(1).head
    rig.queue.send("jobs", encode(jobOf(List(s.document), runFor("a"))))
    assertEquals(rig.drain().counts.rejectedByReason, Map("text-missing" -> 1))
    assertEquals(rig.embedder.calls.get, 0)
  }

  test("text: a corrupted text object is dead-lettered as text-hash-mismatch") {
    val rig = Rig()
    val s   = srcs(1).head
    rig.enqueue(runFor("a"), s)
    rig.objects.tamper(
      ObjectLocation.parse(s.document.textS3Uri).toOption.get,
      "tampered".getBytes("UTF-8"),
    )
    assertEquals(rig.drain().counts.rejectedByReason, Map("text-hash-mismatch" -> 1))
    assertEquals(rig.embedder.calls.get, 0)
    assertEquals(rig.stored(Idx, s.document.documentId), None)
  }

  test("retry: a throttled vector store is retried and then the vector is written") {
    val rig = Rig()
    val s   = srcs(1).head
    rig.enqueue(runFor("a"), s)
    rig.vectors.putFailure = Some(StorageError.Throttled("429"))
    val c1 = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c1.documentsRetried, 1)
    assertEquals(c1.vectorsWritten, 0)
    rig.vectors.putFailure = None
    val c2 = rig.drain().counts
    assertEquals(c2.vectorsWritten, 1)
    assert(rig.stored(Idx, s.document.documentId).isDefined)
  }

  // ---- idempotency ---------------------------------------------------------------------------------

  test(
    "idempotent: unchanged text from a NEW run is skipped; no embed call, no text fetch, no write"
  ) {
    val rig = Rig()
    val all = srcs(3)
    rig.enqueue(runFor("first"), all*)
    val first = rig.drain().counts
    assertEquals(first.vectorsWritten, 3)
    val fetchesBefore = rig.objects.getCalls.get
    val callsBefore   = rig.embedder.calls.get
    val putsBefore    = rig.vectors.puts.get
    val rep2          = rig.enqueue(runFor("second"), all*)
    assertEquals(rep2.textsUnchanged, 3)
    assertEquals(rep2.textsWritten, 0)
    val second = rig.drain().counts
    assertEquals(second.skippedUnchanged, 3)
    assertEquals(second.embedCalls, 0)
    assertEquals(second.vectorsWritten, 0)
    assertEquals(second.documentsEmbedded, 0)
    assertEquals(second.jobsDeadLettered, 0)
    assertEquals(rig.embedder.calls.get, callsBefore)
    assertEquals(
      rig.objects.getCalls.get,
      fetchesBefore,
      "text must not be fetched for a skipped document",
    )
    assertEquals(rig.vectors.puts.get, putsBefore)
    // the stored vector keeps the FIRST run
    assertEquals(rig.stored(Idx, all.head.document.documentId).get.record.runId, runFor("first"))
  }

  test("idempotent: redelivering the same job after success is a no-op") {
    val rig = Rig()
    val s   = srcs(1).head
    rig.enqueue(runFor("a"), s)
    rig.drain()
    rig.queue.send("jobs", encode(jobOf(List(s.document), runFor("a"))), Some("again"))
    val c = rig.drain().counts
    assertEquals(c.skippedUnchanged, 1)
    assertEquals(c.embedCalls, 0)
  }

  test(
    "key collision: the same key with a different text hash is rejected, dead-lettered and never overwritten"
  ) {
    val rig = Rig()
    val old = source("the original text of this document")
    rig.enqueue(runFor("a"), old)
    rig.drain()
    val newText = "a different rendering of the same document"
    val changed = EmbeddingSource(
      doc(newText, "Ada Lovelace").copy(documentId = old.document.documentId),
      newText,
    )
    assertEquals(changed.document.documentId, old.document.documentId)
    assertNotEquals(changed.document.textHash, old.document.textHash)
    rig.enqueue(runFor("b"), changed)
    val callsBefore = rig.embedder.calls.get
    val c           = rig.drain().counts
    assertEquals(c.rejectedByReason, Map("key-collision" -> 1))
    assertEquals(c.documentsDeadLettered, 1)
    assertEquals(c.vectorsWritten, 0)
    assertEquals(rig.embedder.calls.get, callsBefore, "no embed call for a colliding key")
    val kept = rig.stored(Idx, old.document.documentId).get.record
    assertEquals(kept.textHash, old.document.textHash)
    assertEquals(kept.textS3Uri, old.document.textS3Uri)
    val dead = rig.jobsIn("dlq")
    assertEquals(dead.size, 1)
    assert(dead.head.failureReason.exists(_.contains("key-collision=1")))
    assert(!dead.head.failureReason.exists(_.contains(newText)), "the reason must be code-only")
  }

  test("embedding mismatch: the same text under another model version is rejected, not replaced") {
    val rig = Rig()
    val s   = srcs(1).head
    val old = VectorEntry(
      VectorRecord(
        1,
        s.document.documentId,
        Idx,
        4,
        "test-embedder",
        "v0",
        s.document.textHash,
        s.document.textS3Uri,
        Map("documentType" -> "researcher_profile"),
        runFor("older"),
      ),
      Vector(1f, 2f, 3f, 4f),
    )
    assertEquals(
      rig.vectorsRaw.putBatch(Idx, List(old)).map(_.map(_.outcome)),
      Right(List(VectorPutOutcome.Accepted)),
    )
    rig.enqueue(runFor("a"), s)
    val c = rig.drain().counts
    assertEquals(c.rejectedByReason, Map("embedding-mismatch" -> 1))
    assertEquals(rig.stored(Idx, s.document.documentId).get.record.embeddingModelVersion, "v0")
    assertEquals(rig.embedder.calls.get, 0)
  }

  test("a retryable failure of the idempotency lookup is retried, not treated as absent") {
    val rig = Rig()
    rig.enqueue(runFor("a"), srcs(1)*)
    rig.vectors.getFailure = Some(StorageError.Unavailable("get"))
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.documentsRetried, 1)
    assertEquals(rig.embedder.calls.get, 0)
    assertEquals(rig.vectors.puts.get, 0)
  }

  // ---- queue failures --------------------------------------------------------------------------------

  test("the original message is NOT deleted when the dead-letter send fails (it is redelivered)") {
    val emb = FakeEmbedder(script = _ => Some(EmbeddingError.Failed("boom")))
    val rig = Rig(embedder = emb)
    rig.enqueue(runFor("a"), srcs(1)*)
    rig.queue.failSendTo = Set("dlq")
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.jobsDeadLettered, 0)
    assert(c.queueFailures >= 1, c.toString)
    assertEquals(rig.queue.deletes.get, 0)
    assertEquals(rig.bodies("jobs").size, 1, "the unacknowledged message must reappear")
  }

  test("a failed delete after good sends is counted and leaves a harmless redelivery") {
    val rig = Rig()
    val s   = srcs(1).head
    rig.enqueue(runFor("a"), s)
    rig.queue.failDelete = true
    val c = rig.worker.runOnce().fold(e => fail(e.toString), identity)
    assertEquals(c.vectorsWritten, 1)
    assertEquals(c.queueFailures, 1, "a failed delete after good sends must be counted")
    rig.queue.failDelete = false
    rig.advanceClock()
    val again = rig.drain().counts
    assertEquals(again.skippedUnchanged, 1)
    assertEquals(again.embedCalls, 0)
  }

  // ---- ledger ------------------------------------------------------------------------------------------

  test("ledger: producer and job entries are written with the injected clock") {
    val rig = Rig()
    rig.enqueue(runFor("a"), srcs(1)*)
    rig.drain()
    val prod = rig.ledgerEntries(EmbeddingLedger.ProducerStage)
    assertEquals(prod.size, 1)
    assertEquals(prod.head.status, RunStatus.Succeeded)
    assertEquals(prod.head.startedAt, t0.toString)
    assertEquals(prod.head.finishedAt, Some(t0.plusSeconds(1).toString))
    assertEquals(prod.head.codeVersion, codeVersion)
    val jobs = rig.ledgerEntries(EmbeddingLedger.JobStage)
    assertEquals(jobs.size, 1)
    val j = jobs.head
    assertEquals(j.status, RunStatus.Succeeded)
    assertEquals(j.attempt, 1)
    assertEquals(j.startedAt, t0.plusSeconds(2).toString)
    assertEquals(j.finishedAt, Some(t0.plusSeconds(3).toString))
    assertEquals(j.failureReason, None)
    assertEquals(j.parameters("embeddingModelId"), "test-embedder")
    assertEquals(j.parameters("embeddingModelVersion"), "v1")
    assertEquals(j.parameters("dimension"), "4")
    assertEquals(j.parameters("documentCount"), "1")
    assert(j.inputs.exists(i => i.checksum.isDefined && i.version == "text"), j.inputs.toString)
    val fin = Instant.parse(j.finishedAt.get)
    assert(!fin.isBefore(Instant.parse(j.startedAt)))
  }

  test(
    "ledger: a retry shows as a failed attempt 1 and a succeeded attempt 2 (retry count = attempt - 1)"
  ) {
    val emb =
      FakeEmbedder(script = n => if n == 1 then Some(EmbeddingError.Throttled("429")) else None)
    val rig = Rig(embedder = emb)
    rig.enqueue(runFor("a"), srcs(1)*)
    rig.drain()
    val jobs = rig.ledgerEntries(EmbeddingLedger.JobStage)
    assertEquals(
      jobs.map(e => e.attempt -> e.status),
      List(1 -> RunStatus.Failed, 2 -> RunStatus.Succeeded),
    )
    assert(
      jobs.head.failureReason.exists(_.contains("retry 1 doc(s) as attempt 2 of 3: throttled=1")),
      jobs.head.failureReason.toString,
    )
  }

  test("ledger: a dead-lettered document is recorded with code-only reasons, never the text") {
    val rig = Rig()
    val old = source("the original text of this document")
    rig.enqueue(runFor("a"), old)
    rig.drain()
    val newText = "UNIQUE-REPLACEMENT-TEXT-MARKER"
    rig.enqueue(
      runFor("b"),
      EmbeddingSource(doc(newText).copy(documentId = old.document.documentId), newText),
    )
    rig.drain()
    val failed = rig.ledgerEntries(EmbeddingLedger.JobStage).filter(_.status == RunStatus.Failed)
    assertEquals(failed.size, 1)
    val why = failed.head.failureReason.getOrElse("")
    assert(why.contains("dead_lettered 1 doc(s): key-collision=1"), why)
    assert(!why.contains(newText))
  }

  test("ledger: an entry is recorded per delivery attempt and the producer run is one entry") {
    val rig = Rig()
    val rep = rig.enqueue(runFor("a"), srcs(3)*)
    rig.drain()
    assertEquals(rep.jobsEnqueued, 2)
    assertEquals(rig.ledgerEntries(EmbeddingLedger.ProducerStage).size, 1)
    assertEquals(rig.ledgerEntries(EmbeddingLedger.JobStage).size, 2)
    assertEquals(
      rep.ledgerRunId,
      rig.ledgerEntries(EmbeddingLedger.ProducerStage).headOption.map(_.runId),
    )
  }

  test("ledger: a failing ledger store is counted and never undoes the work") {
    val rig    = Rig()
    val broken = InMemoryKeyValueStore(Map("other" -> KvTableSchema(true)))
    val bad    = EmbeddingLedger(RunLedgerRepository(broken, "ledger"), rig.clock, codeVersion)
    val worker = EmbeddingWorker(
      rig.workerConfig,
      rig.queue,
      rig.objects,
      rig.embedder,
      rig.vectors,
      rig.claimsKv,
      Some(bad),
    )
    rig.queue.send("jobs", encode(jobOf(List(srcs(1).head.document), runFor("a"))))
    rig.objects.put(
      ObjectLocation.parse(srcs(1).head.document.textS3Uri).toOption.get,
      srcs(1).head.text.getBytes("UTF-8"),
    )
    val c = worker.drain(5).counts
    assertEquals(c.vectorsWritten, 1)
    assert(c.ledgerFailures >= 1, c.toString)
  }

  test("counts: + adds every field and merges the reason maps") {
    val a = EmbeddingCounts(jobsProcessed = 1, rejectedByReason = Map("x" -> 1))
    val b =
      EmbeddingCounts(jobsProcessed = 2, embedCalls = 3, rejectedByReason = Map("x" -> 2, "y" -> 1))
    val s = a + b
    assertEquals(s.jobsProcessed, 3)
    assertEquals(s.embedCalls, 3)
    assertEquals(s.rejectedByReason, Map("x" -> 3, "y" -> 1))
    assertEquals(s.rejectedTotal, 4)
  }
