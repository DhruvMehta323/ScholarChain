package scholarchain.rag

import java.io.{ByteArrayOutputStream, PrintStream}

import scholarchain.core.*

/** Synthetic fixtures for the rag suites. No real identifier, key, account or endpoint. */
object RagFx:
  def ok[A](e: Either[ValidationError, A]): A = e match
    case Right(a)  => a
    case Left(err) => throw new AssertionError(s"fixture construction failed: ${err.message}")

  val hexA: String    = "a" * 64
  val input: InputRef = InputRef("scholarchain-graph/semantic-summaries", "example-1", Some(hexA))

  val run1: String =
    ok(Ids.runId("spark-graph", "0.1.0", 1, Map("k" -> "v"), List(input)))

  val bucket: String = "rag-test-bucket"

  def textUri: String => String = TextUris.contentAddressed(bucket)

  def prov: Provenance =
    Provenance(1, SourceType.ScholarChain, List(input), "0.1.0", Map("k" -> "v"), Some(run1), None)

  def researcher(name: String): String = ok(Ids.researcherIdDblpPerson(name))

  def summary(
      text: String,
      entity: String = researcher("Ada Lovelace"),
      tpe: String = EmbeddingDocumentTypes.ResearcherProfile,
      window: GraphWindow = GraphWindow(2000, 2004),
  ): SemanticSummary =
    SemanticSummary(1, entity, tpe, Hashing.textHash(text), text, window, run1, prov)

  def venue(id: String, name: String): Venue  = Venue(1, id, name, None, prov)
  def topic(id: String, label: String): Topic = Topic(1, id, label, prov)

  def work(
      id: String,
      title: String,
      year: Int = 2001,
      doi: Option[String] = None,
      venueId: Option[String] = None,
      topicIds: List[String] = Nil,
  ): WorkRecord = WorkRecord(1, id, title, year, doi, venueId, topicIds, prov)

  def render(
      summaries: List[SemanticSummary] = Nil,
      works: List[WorkRecord] = Nil,
      venues: List[Venue] = Nil,
      topics: List[Topic] = Nil,
      policy: ChunkPolicy = ChunkPolicy.Default,
      paperLimit: Option[Int] = None,
  ): RenderResult =
    EmbeddingDocumentRenderer.renderDetailed(
      summaries,
      works,
      venues,
      topics,
      run1,
      textUri,
      policy,
      paperLimit,
    )

  /** Distinct, numbered words: every chunk of such a text is unique and locatable. */
  def words(n: Int, prefix: String = "w"): String =
    (1 to n).map(i => f"$prefix$i%05d").mkString(" ")

  /** Captures what the body prints to System.out and System.err (a "log line" check). */
  def capturedOutput[A](body: => A): (A, String) =
    val oldOut = System.out
    val oldErr = System.err
    val buf    = new ByteArrayOutputStream()
    val ps     = new PrintStream(buf, true, "UTF-8")
    System.setOut(ps)
    System.setErr(ps)
    try
      val r = body
      ps.flush()
      (r, buf.toString("UTF-8"))
    finally
      System.setOut(oldOut)
      System.setErr(oldErr)

/** Exact in-memory cosine store behind the core `VectorStore` port (no storage-module type). */
final class CosineStore extends VectorStore:
  private val data = scala.collection.mutable.LinkedHashMap.empty[(String, String), VectorEntry]

  def putBatch(index: String, vs: List[VectorEntry]): StorageResult[List[VectorPutResult]] =
    synchronized {
      Right(vs.map { e =>
        data((index, e.record.vectorKey)) = e
        VectorPutResult(e.record.vectorKey, VectorPutOutcome.Accepted)
      })
    }

  private def cos(a: Vector[Float], b: Vector[Float]): Double =
    val dot = a.zip(b).map((x, y) => x.toDouble * y).sum
    val na  = math.sqrt(a.map(x => x.toDouble * x).sum)
    val nb  = math.sqrt(b.map(x => x.toDouble * x).sum)
    if na == 0 || nb == 0 then 0.0 else dot / (na * nb)

  def query(index: String, q: VectorQuery): StorageResult[List[VectorHit]] = synchronized {
    Right(
      data.iterator
        .collect { case ((i, _), e) if i == index => e }
        .map(e => VectorHit(e.record.vectorKey, cos(q.embedding, e.embedding), e.record.metadata))
        .toList
        .sortBy(h => (-h.score, h.vectorKey))
        .take(q.topK)
    )
  }

  def get(index: String, key: String): StorageResult[Option[VectorEntry]] = synchronized {
    Right(data.get((index, key)))
  }

  /** Stores the embedding of every (document, text) pair the way the ingestion path would. */
  def load(docs: List[(EmbeddingDocument, String)], e: Embedder): Unit =
    docs.foreach { (d, t) =>
      val index = EmbeddingDocumentTypes.indexFor(d.documentType).get
      val v     = e.embed(List(t)).fold(err => throw AssertionError(err.toString), _.head)
      val entry = ModelVersioning
        .entryFor(
          e,
          d,
          index,
          Map(
            "documentType" -> d.documentType,
            "entityId"     -> d.entityId,
            "window"       -> GraphWindow.label(d.window),
            "textS3Uri"    -> d.textS3Uri,
          ),
          RagFx.run1,
          v,
        )
        .fold(err => throw AssertionError(err.toString), identity)
      putBatch(index, List(entry))
    }

/** A store that always answers with the given hits (to model a retrieval that goes wrong). */
final class ConstStore(hits: List[VectorHit]) extends VectorStore:
  def putBatch(i: String, v: List[VectorEntry]) = Right(Nil)
  def query(i: String, q: VectorQuery)          = Right(hits)
  def get(i: String, k: String)                 = Right(None)

/** A store whose query fails. */
object FailingStore extends VectorStore:
  def putBatch(i: String, v: List[VectorEntry]) = Left(StorageError.Unavailable("x"))
  def query(i: String, q: VectorQuery)          = Left(StorageError.Unavailable("x"))
  def get(i: String, k: String)                 = Left(StorageError.Unavailable("x"))
