package scholarchain.rag

import scholarchain.core.*

/** One fixed retrieval expectation: querying index `indexName` with `queryText` must return
  * `expectedVectorKey` (the document of `expectedEntityId`) as the top-1 hit. `why` states how the
  * query was derived and why the expectation holds.
  */
final case class SmokeQuery(
    name: String,
    documentType: String,
    indexName: String,
    expectedVectorKey: String,
    expectedEntityId: String,
    queryText: String,
    why: String,
)

final case class SmokeOutcome(
    query: SmokeQuery,
    passed: Boolean,
    topKeys: List[String],
    expectedRank: Option[Int],
    detail: String,
)

final case class SmokeReport(outcomes: List[SmokeOutcome]):
  def passedCount: Int   = outcomes.count(_.passed)
  def failedCount: Int   = outcomes.count(!_.passed)
  def allPassed: Boolean = outcomes.nonEmpty && failedCount == 0

/** Local retrieval smoke test over the vectors of the rendered documents.
  *
  * SELF-DERIVING queries (no hand-typed expectation that could drift from the data): for each
  * document type, `define` considers the documents of that type and, for each, builds a query from
  * up to `maxQueryTokens` tokens of that document's own rendered text that occur in NO other
  * document of the same type (document frequency 1; tokens as in `LocalHashEmbedder.tokens`), taken
  * from anywhere in the text. A document with fewer than `minDistinctive` such tokens is not used.
  * Candidates are tried in a fixed order: most distinctive tokens first (capped at
  * `maxQueryTokens`), ties by SHA-256 of the document ID; the first `perType` that pass the
  * collision guard are kept.
  *
  * Collision guard: a query is kept only if, under the hashed bag-of-words embedder of dimension
  * `bucketDimension`, the expected document's cosine similarity to the query beats that of every
  * other document of the type by at least the factor `1 + minRelativeMargin`. This is computed from
  * the data and the stated embedder, never typed by hand. It removes queries whose hashed buckets
  * would collide with another document's tokens. It does not weaken the pass criterion used by
  * `run` (expected document top-1 within topK); a collision that this guard cannot see (a different
  * embedder dimension) still fails and is reported.
  *
  * Entities, windows and counts in a result still come from the graph and metric stores. This test
  * only checks that similarity search finds the right document.
  */
object RetrievalSmoke:
  val DefaultPerType: Int              = 3
  val DefaultMaxQueryTokens: Int       = 8
  val DefaultMinDistinctive: Int       = 3
  val DefaultTopK: Int                 = 5
  val DefaultMinRelativeMargin: Double = 0.1

  def define(
      docs: List[(EmbeddingDocument, String)],
      perType: Int = DefaultPerType,
      maxQueryTokens: Int = DefaultMaxQueryTokens,
      minDistinctive: Int = DefaultMinDistinctive,
      bucketDimension: Int = LocalHashEmbedder.DefaultDimension,
      minRelativeMargin: Double = DefaultMinRelativeMargin,
  ): List[SmokeQuery] =
    EmbeddingDocumentTypes.all.flatMap { t =>
      val ofType = docs.filter(_._1.documentType == t)
      EmbeddingDocumentTypes.indexFor(t).toList.flatMap { index =>
        val tokenLists = ofType.map((d, text) => d -> LocalHashEmbedder.tokens(text)).toVector
        val df         = tokenLists
          .flatMap((_, toks) => toks.distinct)
          .groupBy(identity)
          .view
          .mapValues(_.size)
          .toMap
        val guard = CollisionGuard(tokenLists.map(_._2), bucketDimension)
        tokenLists.zipWithIndex
          .map { case ((d, toks), i) => (d, i, toks.distinct.filter(df(_) == 1)) }
          .filter(_._3.size >= minDistinctive)
          .sortBy((d, _, uniq) =>
            (-math.min(uniq.size, maxQueryTokens), Hashing.textHash(d.documentId))
          )
          .iterator
          .flatMap { (d, i, uniq) =>
            val used = uniq.take(maxQueryTokens)
            Option.when(guard.margin(used, i) >= 1.0 + minRelativeMargin) {
              SmokeQuery(
                name = s"$t/${d.documentId}",
                documentType = t,
                indexName = index,
                expectedVectorKey = d.documentId,
                expectedEntityId = d.entityId,
                queryText = used.mkString(" "),
                why = s"query = ${used.size} tokens that occur in the rendered text of " +
                  s"${d.documentId} and in no other $t document; that document is the only one " +
                  "containing any of them, and under the hashed embedder (dimension " +
                  s"$bucketDimension) its cosine beats every other $t document by a factor of " +
                  s"at least ${1.0 + minRelativeMargin}, so it must rank top-1",
              )
            }
          }
          .take(perType)
          .toList
      }
    }

  /** Hashed bag-of-words cosine of a query against every document of one type, via bucket postings
    * (cheap: only documents that share a bucket with the query are touched).
    */
  private final class CollisionGuard(tokenLists: Vector[List[String]], dimension: Int):
    private val counts: Vector[Map[Int, Int]] =
      tokenLists.map(_.groupBy(LocalHashEmbedder.bucket(_, dimension)).view.mapValues(_.size).toMap)
    private val norms: Vector[Double] =
      counts.map(m => math.sqrt(m.values.map(c => c.toDouble * c).sum))
    private val postings: Map[Int, List[Int]] =
      counts.zipWithIndex
        .flatMap((m, i) => m.keys.map(_ -> i))
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2).toList)
        .toMap

    /** Expected document cosine divided by the best other cosine (infinity when no other shares a
      * bucket with the query; 0 when the expected document shares none).
      */
    def margin(queryTokens: List[String], expected: Int): Double =
      val q =
        queryTokens.groupBy(LocalHashEmbedder.bucket(_, dimension)).view.mapValues(_.size).toMap
      val cos = scala.collection.mutable.Map.empty[Int, Double]
      q.foreach { (b, qc) =>
        postings.getOrElse(b, Nil).foreach { i =>
          cos.update(i, cos.getOrElse(i, 0.0) + qc.toDouble * counts(i)(b))
        }
      }
      val own    = cos.getOrElse(expected, 0.0) / norms(expected)
      val others = cos.iterator.collect { case (i, v) if i != expected => v / norms(i) }.maxOption
      if own <= 0.0 then 0.0
      else
        others.fold(Double.PositiveInfinity)(o =>
          if o <= 0.0 then Double.PositiveInfinity else own / o
        )

  def run(
      store: VectorStore,
      embedder: Embedder,
      queries: List[SmokeQuery],
      topK: Int = DefaultTopK,
  ): SmokeReport =
    SmokeReport(queries.map(runOne(store, embedder, _, topK)))

  private def runOne(
      store: VectorStore,
      embedder: Embedder,
      q: SmokeQuery,
      topK: Int,
  ): SmokeOutcome =
    def failed(detail: String) = SmokeOutcome(q, false, Nil, None, detail)
    embedder.embed(List(q.queryText)) match
      case Left(e)                       => failed(s"embed failed: ${e.code}")
      case Right(vecs) if vecs.size != 1 => failed("embed returned the wrong vector count")
      case Right(vec :: _)               =>
        store.query(q.indexName, VectorQuery(vec, topK)) match
          case Left(_)     => failed("store query failed")
          case Right(hits) =>
            val keys = hits.map(_.vectorKey)
            val rank = keys.indexOf(q.expectedVectorKey) match
              case -1 => None
              case i  => Some(i + 1)
            val top1 = hits.headOption.exists(h =>
              h.vectorKey == q.expectedVectorKey &&
                SemanticContext.entityIdOf(h).contains(q.expectedEntityId)
            )
            SmokeOutcome(
              q,
              top1,
              keys,
              rank,
              if top1 then "expected document is top-1"
              else if rank.contains(1) then "expected entity could not be confirmed for the top hit"
              else s"expected rank ${rank.fold("absent from top-" + topK)(_.toString)}",
            )
      case Right(Nil) => failed("embed returned no vector")
