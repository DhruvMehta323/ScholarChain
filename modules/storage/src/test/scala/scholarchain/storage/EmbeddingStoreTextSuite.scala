package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.core.*

import EmbeddingFx.*
import StorageFx.*

/** H1: `EmbeddingProducer` writes the text first (`putIfAbsent`) and only calls `head` once the
  * object already exists, because under the ops role a HEAD of a missing key is a 403, not a 404.
  */
class EmbeddingStoreTextSuite extends munit.FunSuite:

  /** Delegating store that counts calls and injects failures. `head` of a missing key can model the
    * S3 403 (a non-retryable `Failed`).
    */
  private final class RecStore(
      val inner: MemObjectStore,
      headOfMissingFails: Boolean = false,
      headError: Option[StorageError] = None,
      putIfAbsentError: Option[StorageError] = None,
  ) extends ObjectStore:
    val heads: AtomicInteger        = AtomicInteger(0)
    val putIfAbsents: AtomicInteger = AtomicInteger(0)

    def put(l: ObjectLocation, b: Array[Byte], ct: Option[String], h: Option[String]) =
      inner.put(l, b, ct, h)
    def putIfAbsent(l: ObjectLocation, b: Array[Byte], ct: Option[String], h: Option[String]) =
      putIfAbsents.incrementAndGet()
      putIfAbsentError.map(Left(_)).getOrElse(inner.putIfAbsent(l, b, ct, h))
    def get(l: ObjectLocation)  = inner.get(l)
    def head(l: ObjectLocation) =
      heads.incrementAndGet()
      headError match
        case Some(e) => Left(e)
        case None    =>
          inner.head(l) match
            case Right(None) if headOfMissingFails => Left(StorageError.Failed("403 Forbidden"))
            case other                             => other
    def list(bucket: String, prefix: String, limit: Int, token: Option[String]) =
      inner.list(bucket, prefix, limit, token)

  private def loc(s: EmbeddingSource): ObjectLocation =
    ObjectLocation.parse(s.document.textS3Uri).fold(e => fail(e.message), identity)

  private def many(n: Int) =
    (1 to n).toList.map(i => source(s"store text number $i", s"Stored $i"))

  private def producer(rig: Rig, store: ObjectStore): EmbeddingProducer =
    EmbeddingProducer(rig.producerConfig, rig.queue, store, Some(rig.ledger))

  private def preseed(store: MemObjectStore, s: EmbeddingSource, content: String): Unit =
    store
      .put(loc(s), content.getBytes(StandardCharsets.UTF_8), None, None)
      .fold(e => fail(e.toString), identity)

  test(
    "fresh keys: every text is written with 0 head calls, even if HEAD of a missing key is 403"
  ) {
    val rig   = Rig()
    val store = RecStore(MemObjectStore(), headOfMissingFails = true)
    val xs    = many(5)
    val rep   = producer(rig, store).enqueue(runFor("a"), xs).fold(e => fail(e.toString), identity)
    assertEquals(rep.textsWritten, 5)
    assertEquals(rep.textsUnchanged, 0)
    assertEquals(rep.rejected, Nil)
    assertEquals(store.heads.get, 0, "a fresh key must never be HEADed")
    assertEquals(store.putIfAbsents.get, 5)
    xs.foreach(x => assert(store.inner.head(loc(x)).exists(_.isDefined)))
  }

  test("existing text with the same content: unchanged, exactly one head call, nothing rewritten") {
    val rig   = Rig()
    val store = RecStore(MemObjectStore(), headOfMissingFails = true)
    val s     = source("already stored text")
    preseed(store.inner, s, s.text)
    val rep =
      producer(rig, store).enqueue(runFor("a"), List(s)).fold(e => fail(e.toString), identity)
    assertEquals(rep.textsWritten, 0)
    assertEquals(rep.textsUnchanged, 1)
    assertEquals(store.heads.get, 1)
    assertEquals(store.putIfAbsents.get, 1)
  }

  test("existing text with different content: text-conflict, never overwritten") {
    val rig   = Rig()
    val store = RecStore(MemObjectStore())
    val s     = source("the real text")
    preseed(store.inner, s, "someone else's bytes")
    val rep = producer(rig, store)
      .enqueue(runFor("a"), List(s, source("other text", "Other")))
      .fold(e => fail(e.toString), identity)
    assertEquals(rep.rejectedByReason, Map("text-conflict" -> 1))
    assertEquals(store.heads.get, 1)
    val kept = store.inner.get(loc(s)).fold(e => fail(e.toString), identity)
    assertEquals(new String(kept.bytes, StandardCharsets.UTF_8), "someone else's bytes")
  }

  test("after AlreadyExists a retryable head error aborts the run") {
    val rig   = Rig()
    val store = RecStore(MemObjectStore(), headError = Some(StorageError.Throttled("429")))
    val s     = source("present text")
    preseed(store.inner, s, s.text)
    val r = producer(rig, store).enqueue(runFor("a"), List(s))
    assertEquals(r.left.map(_.code), Left("throttled"))
    assertEquals(rig.bodies("jobs"), Nil)
  }

  test("after AlreadyExists a non-retryable head error is text-store-failed") {
    val rig   = Rig()
    val store = RecStore(MemObjectStore(), headError = Some(StorageError.Failed("denied")))
    val s     = source("present text")
    preseed(store.inner, s, s.text)
    val rep = producer(rig, store)
      .enqueue(runFor("a"), List(s, source("fine text", "Fine")))
      .fold(e => fail(e.toString), identity)
    assertEquals(rep.rejectedByReason, Map("text-store-failed" -> 1))
    assertEquals(rep.textsWritten, 1)
  }

  test("putIfAbsent: a non-retryable failure is text-store-failed, with no head call") {
    val rig   = Rig()
    val store = RecStore(MemObjectStore(), putIfAbsentError = Some(StorageError.Failed("denied")))
    val rep   = producer(rig, store)
      .enqueue(runFor("a"), many(2))
      .fold(e => fail(e.toString), identity)
    assertEquals(rep.rejectedByReason, Map("text-store-failed" -> 2))
    assertEquals(rep.textsWritten, 0)
    assertEquals(store.heads.get, 0)
  }

  test("putIfAbsent: a retryable failure aborts the run before any job is sent") {
    val rig   = Rig()
    val store = RecStore(MemObjectStore(), putIfAbsentError = Some(StorageError.Unavailable("503")))
    val r     = producer(rig, store).enqueue(runFor("a"), many(2))
    assertEquals(r.left.map(_.code), Left("unavailable"))
    assertEquals(store.heads.get, 0)
    assertEquals(rig.bodies("jobs"), Nil)
  }
end EmbeddingStoreTextSuite
