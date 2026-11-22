package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.*

import EmbeddingFx.*
import StorageFx.*

/** Phase 08 producer: text storage, job cutting, determinism, rejections, message codec. */
class EmbeddingProducerSuite extends munit.FunSuite:

  private def loc(s: EmbeddingSource): ObjectLocation =
    ObjectLocation.parse(s.document.textS3Uri).fold(e => fail(e.message), identity)

  private def many(n: Int, tpe: String = EmbeddingDocumentTypes.ResearcherProfile) =
    (1 to n).toList.map(i => source(s"producer text number $i", s"Person $i", tpe))

  test("the text is written to its pointer as UTF-8 and never goes on the queue") {
    val rig = Rig()
    val s   = source("café über text")
    val rep = rig.enqueue(runFor("a"), s)
    assertEquals(rep.textsWritten, 1)
    assertEquals(rep.textsUnchanged, 0)
    val d = rig.objects.get(loc(s)).fold(e => fail(e.toString), identity)
    assertEquals(new String(d.bytes, StandardCharsets.UTF_8), s.text)
    assertEquals(d.meta.contentHash, s.document.textHash)
    assertEquals(d.meta.contentType, Some("text/plain; charset=utf-8"))
    val bodies = rig.bodies("jobs")
    assertEquals(bodies.size, 1)
    assert(!bodies.head.contains("caf"), "the text must not be in the queue message")
  }

  test("jobs hold one document type each and at most maxDocumentsPerJob documents") {
    val rig = Rig(maxDocsPerJob = 2)
    val rep = rig.enqueue(
      runFor("a"),
      many(3)*
    )
    val topic = source("topic text only", "Topic A", EmbeddingDocumentTypes.TopicSummary)
    rig.enqueue(runFor("a"), topic)
    assertEquals(rep.jobsEnqueued, 2)
    val jobs = rig.jobsIn("jobs")
    assertEquals(jobs.size, 3)
    assert(jobs.forall(_.documents.size <= 2))
    assert(jobs.forall(_.documents.map(_.documentType).distinct.size == 1))
    assert(jobs.forall(j => summon[Contract[EmbeddingJob]].validate(j).isRight))
    assert(jobs.forall(j => j.status == EmbeddingJobStatus.Pending && j.attempt == 1))
  }

  test("the report counts documents seen, written and enqueued jobs, with job IDs") {
    val rig = Rig()
    val rep = rig.enqueue(runFor("a"), many(5)*)
    assertEquals(rep.documentsSeen, 5)
    assertEquals(rep.textsWritten, 5)
    assertEquals(rep.rejected, Nil)
    assertEquals(rep.jobsEnqueued, 3)
    assertEquals(rep.jobIds.size, 3)
    assert(rep.jobIds.forall(_.startsWith("embjob-")))
    assertEquals(rep.runId, runFor("a"))
  }

  test("deterministic: input order does not change job IDs, job contents or queue order") {
    val all = many(5)
    val a   = Rig()
    val b   = Rig()
    val ra  = a.enqueue(runFor("a"), all*)
    val rb  = b.enqueue(runFor("a"), all.reverse*)
    assertEquals(ra.jobIds, rb.jobIds)
    assertEquals(a.bodies("jobs"), b.bodies("jobs"))
  }

  test("deterministic: the same input gives byte-identical queue bodies on two rigs") {
    val all = many(4)
    val a   = Rig(); val b = Rig()
    a.enqueue(runFor("x"), all*)
    b.enqueue(runFor("x"), all*)
    assertEquals(a.bodies("jobs"), b.bodies("jobs"))
  }

  test("a different run ID gives different job IDs for the same documents") {
    val all = many(2)
    val a   = Rig().enqueue(runFor("one"), all*)
    val b   = Rig().enqueue(runFor("two"), all*)
    assertNotEquals(a.jobIds, b.jobIds)
  }

  test("re-running on the same input rewrites no text and the queue deduplicates the jobs") {
    val rig = Rig()
    val all = many(3)
    val r1  = rig.enqueue(runFor("a"), all*)
    val r2  = rig.enqueue(runFor("a"), all*)
    assertEquals(r2.textsWritten, 0)
    assertEquals(r2.textsUnchanged, 3)
    assertEquals(r2.jobIds, r1.jobIds)
    assertEquals(rig.bodies("jobs").size, r1.jobsEnqueued)
  }

  test("a text whose hash is not the document's textHash is rejected and nothing is written") {
    val rig = Rig()
    val s   = source("genuine text")
    val bad = EmbeddingSource(s.document, "some other text")
    val ok  = source("another genuine text", "Someone Else")
    val rep = rig.enqueue(runFor("a"), bad, ok)
    assertEquals(rep.rejectedByReason, Map("text-hash-mismatch" -> 1))
    assertEquals(rep.textsWritten, 1)
    assertEquals(rig.objects.head(loc(bad)), Right(None))
  }

  test("an invalid document reference is rejected") {
    val rig = Rig()
    val s   = source("some text")
    val bad = EmbeddingSource(s.document.copy(documentId = "not-the-derived-id"), s.text)
    val rep = rig.enqueue(runFor("a"), bad, source("good text", "Good"))
    assertEquals(rep.rejectedByReason, Map("invalid-document" -> 1))
  }

  test("empty text is rejected as not encodable") {
    val rig = Rig()
    val rep = rig.enqueue(
      runFor("a"),
      EmbeddingSource(doc("").copy(textHash = Hashing.textHash("")), ""),
      source("fine", "Fine"),
    )
    assertEquals(rep.rejectedByReason, Map("text-not-encodable" -> 1))
  }

  test("different content already at the pointer is a text-conflict and is never overwritten") {
    val rig = Rig()
    val s   = source("the real text")
    rig.objects.put(loc(s), "someone else's bytes".getBytes(StandardCharsets.UTF_8))
    val rep = rig.enqueue(runFor("a"), s, source("other", "Other"))
    assertEquals(rep.rejectedByReason, Map("text-conflict" -> 1))
    val kept = rig.objects.get(loc(s)).fold(e => fail(e.toString), identity)
    assertEquals(new String(kept.bytes, StandardCharsets.UTF_8), "someone else's bytes")
  }

  test("the same document ID with two different texts is rejected, not silently merged") {
    val rig = Rig()
    val a   = source("first version of the text")
    val b   = EmbeddingSource(
      doc("second version of the text").copy(documentId = a.document.documentId),
      "second version of the text",
    )
    val rep = rig.enqueue(runFor("a"), a, b)
    assertEquals(rep.rejectedByReason, Map("duplicate-id-different-text" -> 1))
    assertEquals(rep.jobsEnqueued, 1)
    val jobs = rig.jobsIn("jobs")
    assertEquals(jobs.flatMap(_.documents).size, 1)
  }

  test("the same document twice with the same text is accepted once") {
    val rig = Rig()
    val a   = source("same text")
    val rep = rig.enqueue(runFor("a"), a, a)
    assertEquals(rep.rejected, Nil)
    assertEquals(rig.jobsIn("jobs").flatMap(_.documents).size, 1)
  }

  test("invalid run ID, empty input and more than the run bound are InvalidRequest") {
    val rig = Rig()
    assertEquals(
      rig.producer.enqueue("run:short", many(1)).left.map(_.code),
      Left("invalid-request"),
    )
    assertEquals(rig.producer.enqueue(runFor("a"), Nil).left.map(_.code), Left("invalid-request"))
    val tight = EmbeddingProducer(
      rig.producerConfig.copy(maxDocumentsPerRun = 2),
      rig.queue,
      rig.objects,
    )
    assertEquals(tight.enqueue(runFor("a"), many(3)).left.map(_.code), Left("invalid-request"))
    assertEquals(rig.objects.size, 0)
  }

  test("a retryable object-store failure aborts the run before any job is sent") {
    val rig = Rig()
    rig.objects.putFailure = Some(StorageError.Throttled("429"))
    val r = rig.producer.enqueue(runFor("a"), many(2))
    assertEquals(r.left.map(_.code), Left("throttled"))
    assertEquals(rig.bodies("jobs"), Nil)
  }

  test("a failing queue is reported and the producer ledger entry ends failed") {
    val rig = Rig()
    rig.queue.failSendTo = Set("jobs")
    val r = rig.producer.enqueue(runFor("a"), many(1))
    assert(r.isLeft)
    val prod = rig.ledgerEntries(EmbeddingLedger.ProducerStage)
    assertEquals(prod.map(_.status), List(RunStatus.Failed))
    assert(prod.head.failureReason.exists(_.startsWith("enqueue failed")))
  }

  test("the producer ledger entry lists the source run, the manifest digest and the parameters") {
    val rig = Rig()
    val rep = rig.enqueue(runFor("a"), many(3)*)
    val e   = rig.ledgerEntries(EmbeddingLedger.ProducerStage).head
    assertEquals(e.runId, rep.ledgerRunId.get)
    assertEquals(e.parameters("documentCount"), "3")
    assertEquals(e.parameters("jobCount"), "2")
    assertEquals(e.parameters("embeddingModelId"), "test-embedder")
    assertEquals(e.inputs.map(_.inputId), List("source-run", "document-manifest"))
    assert(e.inputs(1).checksum.exists(Hashing.isSha256Hex))
  }

  test("EmbeddingProducerConfig.fromSettings takes queue, model and bounds from the settings") {
    val s = EmbeddingConfigLoader.load(Map.empty).fold(e => fail(e.toString), _.settings)
    val c = EmbeddingProducerConfig.fromSettings(s, 123)
    assertEquals(c.queueName, "scholarchain-embedding-jobs")
    assertEquals(c.maxDocumentsPerJob, 50)
    assertEquals(c.maxDocumentsPerRun, 123)
    assertEquals(c.embeddingModelId, "amazon.titan-embed-text-v2:0")
    assertEquals(c.embeddingModelVersion, "v2")
  }

  test("EmbeddingJobMessage: encode and decode round trip; garbage is a Left, never an exception") {
    val job = jobOf(List(source("x y z").document), runFor("a"))
    val enc = EmbeddingJobMessage.encode(job).fold(e => fail(e.toString), identity)
    assertEquals(EmbeddingJobMessage.decode(enc), Right(job))
    assert(EmbeddingJobMessage.decode("not json").isLeft)
    assert(EmbeddingJobMessage.decode("{}").isLeft)
    assert(EmbeddingJobMessage.decode("").isLeft)
    // a syntactically valid job that breaks the contract (attempt 0) is also rejected
    assert(EmbeddingJobMessage.encode(job.copy(attempt = 0)).isLeft)
  }

  test("EmbeddingJobMessage.dedupId: 64 hex, deterministic, and distinct per attempt and purpose") {
    val a = EmbeddingJobMessage.dedupId("job-1", 1, "enqueue")
    assert(Hashing.isSha256Hex(a))
    assertEquals(a, EmbeddingJobMessage.dedupId("job-1", 1, "enqueue"))
    assertNotEquals(a, EmbeddingJobMessage.dedupId("job-1", 2, "enqueue"))
    assertNotEquals(a, EmbeddingJobMessage.dedupId("job-1", 1, "retry"))
    assertNotEquals(a, EmbeddingJobMessage.dedupId("job-2", 1, "enqueue"))
  }
