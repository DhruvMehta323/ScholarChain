package scholarchain.storage

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetVectorsRequest,
  PutInputVector,
  PutVectorsRequest,
  QueryVectorsRequest,
  VectorData,
}

/** Amazon S3 Vectors service limits used by the adapter and its fake. Every value has its source
  * and access date; none is assumed. The same table is in STORAGE-CONTRACTS.md.
  *
  * Sources (all accessed 2026-11-21):
  *   - L = https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html
  *   - P = https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_PutInputVector.html
  *     (vector key length)
  *   - Q = https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_QueryVectors.html
  *
  * The filterable and total metadata sizes depend on the index mode: `ENHANCED` allows more, a
  * `CLASSIC` index (all indexes of vector buckets created before 2026-09-30, see
  * https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-metadata-filtering.html) the
  * smaller values. The adapter enforces the CLASSIC (smaller) values so it is correct for both.
  */
object S3VectorsLimits:
  val MaxVectorsPerPut: Int           = 500               // L
  val MaxVectorsPerGet: Int           = 100               // L
  val MaxPayloadBytes: Long           = 20L * 1024 * 1024 // L: request payload up to 20 MiB
  val MinDimension: Int               = 1                 // L
  val MaxDimension: Int               = 4096              // L
  val MaxTopK: Int                    = 10000             // L
  val MaxVectorKeyLength: Int         = 1024              // P (core policy is stricter: 512)
  val MaxMetadataKeys: Int            = 50                // L
  val MaxFilterableMetadataBytes: Int = 2048              // L, CLASSIC index (ENHANCED: 40 KB)
  val MaxTotalMetadataBytes: Int      = 40 * 1024         // L, CLASSIC index (ENHANCED: 100 KB)
  val MaxFilterableStringBytes: Int   = 2048              // L
  val MaxQueryResultsPerPage: Int     = 100               // L: results per page in QueryVectors

  /** Upper bound of the JSON size of one float in a PutVectors body (a decimal float32 with sign,
    * digits, point and exponent, plus the separator). Used only to split batches conservatively.
    */
  val BytesPerFloatUpperBound: Int = 16

/** Names of the system metadata keys the adapter adds from the record fields. They are filterable
  * (compact scalars). A record's own metadata must not use them.
  */
object VectorMetadata:
  val TextHash            = "textHash"
  val TextS3Uri           = "textS3Uri"
  val ModelId             = "embeddingModelId"
  val ModelVer            = "embeddingModelVersion"
  val RunId               = "runId"
  val System: Set[String] = Set(TextHash, TextS3Uri, ModelId, ModelVer, RunId)

  def encode(r: VectorRecord): Map[String, String] =
    r.metadata ++ Map(
      TextHash  -> r.textHash,
      TextS3Uri -> r.textS3Uri,
      ModelId   -> r.embeddingModelId,
      ModelVer  -> r.embeddingModelVersion,
      RunId     -> r.runId,
    )

  /** The metadata without the `runId` system key. A vector's identity for idempotency is (key, text
    * hash, model ID and version, text pointer, metadata, embedding): the run that wrote it is
    * provenance, not identity, so a reload from a new run is an idempotent no-op (carry-over Phase
    * 07 N2). The stored vector keeps the run ID of its first writer.
    */
  def withoutRun(m: Map[String, String]): Map[String, String] = m - RunId

  /** Compact JSON of the metadata, keys sorted: what is sent to the service and measured. */
  def json(m: Map[String, String]): String =
    ujson.write(ujson.Obj.from(m.toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v))))

  def decode(
      key: String,
      indexName: String,
      dimension: Int,
      md: Map[String, String],
  ): Either[String, VectorRecord] =
    for
      th  <- md.get(TextHash).toRight("metadata has no textHash")
      uri <- md.get(TextS3Uri).toRight("metadata has no textS3Uri")
      mid <- md.get(ModelId).toRight("metadata has no embeddingModelId")
      mv  <- md.get(ModelVer).toRight("metadata has no embeddingModelVersion")
      run <- md.get(RunId).toRight("metadata has no runId")
    yield VectorRecord(1, key, indexName, dimension, mid, mv, th, uri, md -- System, run)

/** Shared validation of a vector before it is stored (used by the fake and the AWS adapter). */
object VectorChecks:
  /** Why a vector entry is not storable, or `None`. Order matters: dimension first. */
  def reject(
      indexName: String,
      indexDimension: Int,
      e: VectorEntry,
  ): Option[(VectorRejection, String)] =
    val r = e.record
    if r.indexName != indexName then
      Some((VectorRejection.InvalidRecord, "record names another index"))
    else if e.embedding.length != r.dimension || r.dimension != indexDimension then
      Some(
        (
          VectorRejection.DimensionMismatch,
          "embedding length differs from the record or index dimension",
        )
      )
    else
      summon[Contract[VectorRecord]].validate(r) match
        case Left(err) => Some((VectorRejection.InvalidRecord, s"record invalid (${err.code})"))
        case Right(_)  =>
          if r.vectorKey
              .getBytes(StandardCharsets.UTF_8)
              .length > S3VectorsLimits.MaxVectorKeyLength
          then Some((VectorRejection.InvalidRecord, "vector key too long"))
          else if r.metadata.keys.exists(VectorMetadata.System.contains) then
            Some((VectorRejection.InvalidRecord, "metadata uses a reserved name"))
          else if e.embedding.exists(f => f.isNaN || f.isInfinity) then
            Some((VectorRejection.InvalidRecord, "embedding holds NaN or Infinity"))
          else if e.embedding.forall(_ == 0f) then
            Some((VectorRejection.InvalidRecord, "zero vector is not allowed for cosine"))
          else
            val md   = VectorMetadata.encode(r)
            val size = VectorMetadata.json(md).getBytes(StandardCharsets.UTF_8).length
            if md.size > S3VectorsLimits.MaxMetadataKeys then
              Some((VectorRejection.InvalidRecord, "too many metadata keys"))
            else if size > S3VectorsLimits.MaxFilterableMetadataBytes then
              Some((VectorRejection.InvalidRecord, "filterable metadata exceeds the size limit"))
            else if md.values.exists(
                _.getBytes(StandardCharsets.UTF_8).length > S3VectorsLimits.MaxFilterableStringBytes
              )
            then Some((VectorRejection.InvalidRecord, "metadata value too long"))
            else None

  /** Idempotency check against an already stored vector (M3). `None` = the stored vector is
    * identical (same text hash, model ID and version, text pointer, metadata and embedding; the
    * `runId` is ignored, so a reload from a new run is a no-op reported as `Accepted` without a
    * write): an idempotent no-op. A different text hash is `KeyCollision`. The same text hash with
    * any other difference (embedding, model ID or version, metadata) is `EmbeddingMismatch`: it is
    * rejected and recorded, never silently accepted and never overwritten (S3 Vectors has no
    * conditional put, and the loader never replaces a vector).
    */
  def againstStored(
      storedMetadata: Map[String, String],
      storedEmbedding: Vector[Float],
      e: VectorEntry,
  ): Option[(VectorRejection, String)] =
    if !storedMetadata.get(VectorMetadata.TextHash).contains(e.record.textHash) then
      Some((VectorRejection.KeyCollision, "key exists with a different text hash"))
    else if VectorMetadata.withoutRun(storedMetadata) ==
        VectorMetadata.withoutRun(VectorMetadata.encode(e.record)) &&
        storedEmbedding == e.embedding
    then None
    else
      Some(
        (
          VectorRejection.EmbeddingMismatch,
          "key and text hash exist with a different embedding, model or metadata",
        )
      )

  /** Collects the hits of a paged query (M4): `fetch(token)` returns one page (at most
    * `MaxQueryResultsPerPage` hits) and the next token; pages are requested until `topK` hits are
    * collected or there is no next token. The loop is bounded: at most `ceil(topK / 100) + 1`
    * pages, and a repeated token stops it. The result is sorted by descending score and cut to
    * `topK`.
    */
  def collectPages(
      topK: Int,
      fetch: Option[String] => StorageResult[(List[VectorHit], Option[String])],
  ): StorageResult[List[VectorHit]] =
    val maxPages = (topK + S3VectorsLimits.MaxQueryResultsPerPage - 1) /
      S3VectorsLimits.MaxQueryResultsPerPage + 1
    @scala.annotation.tailrec
    def go(
        token: Option[String],
        acc: Vector[VectorHit],
        pages: Int,
        seen: Set[String],
    ): StorageResult[List[VectorHit]] =
      fetch(token) match
        case Left(e)                => Left(e)
        case Right((hits, nextTok)) =>
          val all = acc ++ hits
          nextTok match
            case Some(t) if all.size < topK && pages + 1 < maxPages && !seen.contains(t) =>
              go(Some(t), all, pages + 1, seen + t)
            case _ => Right(all.toList.sortBy(h => (-h.score, h.vectorKey)).take(topK))
    go(None, Vector.empty, 0, Set.empty)

  /** Conservative size of one entry in a PutVectors body. */
  def estimatedBytes(e: VectorEntry): Long =
    e.record.vectorKey.getBytes(StandardCharsets.UTF_8).length.toLong +
      e.embedding.length.toLong * S3VectorsLimits.BytesPerFloatUpperBound +
      VectorMetadata.json(VectorMetadata.encode(e.record)).getBytes(StandardCharsets.UTF_8).length +
      64L

  /** Splits into batches of at most `maxCount` vectors and `maxBytes` estimated payload. */
  def batches(
      entries: List[VectorEntry],
      maxCount: Int = S3VectorsLimits.MaxVectorsPerPut,
      maxBytes: Long = S3VectorsLimits.MaxPayloadBytes,
  ): List[List[VectorEntry]] =
    val out   = List.newBuilder[List[VectorEntry]]
    var cur   = Vector.empty[VectorEntry]
    var bytes = 0L
    entries.foreach { e =>
      val b = estimatedBytes(e)
      if cur.nonEmpty && (cur.size >= maxCount || bytes + b > maxBytes) then
        out += cur.toList
        cur = Vector.empty
        bytes = 0L
      cur = cur :+ e
      bytes += b
    }
    if cur.nonEmpty then out += cur.toList
    out.result()

  def cosine(a: Vector[Float], b: Vector[Float]): Double =
    var dot, na, nb = 0.0
    var i           = 0
    while i < a.length do
      dot += a(i).toDouble * b(i).toDouble
      na += a(i).toDouble * a(i).toDouble
      nb += b(i).toDouble * b(i).toDouble
      i += 1
    dot / (math.sqrt(na) * math.sqrt(nb))

  def matches(f: MetadataFilter, md: Map[String, String]): Boolean = f match
    case MetadataFilter.Equals(n, v) => md.get(n).contains(v)
    case MetadataFilter.In(n, vs)    => md.get(n).exists(vs.contains)
    case MetadataFilter.And(fs)      => fs.forall(matches(_, md))

  /** Filter in the S3 Vectors metadata filter syntax (`$eq`, `$in`, `$and`; see
    * https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-metadata-filtering.html,
    * accessed 2026-11-21).
    */
  def filterDocument(f: MetadataFilter): Document = f match
    case MetadataFilter.Equals(n, v) =>
      Document
        .mapBuilder()
        .putDocument(n, Document.mapBuilder().putString("$eq", v).build())
        .build()
    case MetadataFilter.In(n, vs) =>
      Document
        .mapBuilder()
        .putDocument(
          n,
          Document
            .mapBuilder()
            .putDocument("$in", Document.fromList(vs.map(Document.fromString).asJava))
            .build(),
        )
        .build()
    case MetadataFilter.And(fs) =>
      Document
        .mapBuilder()
        .putDocument("$and", Document.fromList(fs.map(filterDocument).asJava))
        .build()

  /** Filters are bounded so a request cannot grow without limit. */
  def filterProblem(f: MetadataFilter, depth: Int = 0): Option[String] = f match
    case _ if depth > 4 => Some("filter nested too deeply")
    case MetadataFilter.Equals(n, v) if n.isEmpty || v.length > 2048 => Some("bad filter term")
    case MetadataFilter.In(n, vs) if n.isEmpty || vs.isEmpty || vs.size > 100 =>
      Some("bad filter list")
    case MetadataFilter.And(fs) if fs.isEmpty || fs.size > 20 => Some("bad filter conjunction")
    case MetadataFilter.And(fs) => fs.flatMap(filterProblem(_, depth + 1)).headOption
    case _                      => None

/** A vector that was not stored, recorded for the run report and the log. Holds no embedding and no
  * metadata value: the key, the reason and a short fixed text.
  */
final case class RejectedVector(
    indexName: String,
    vectorKey: String,
    reason: VectorRejection,
    detail: String,
)

/** Where rejected vectors go (the caller logs or stores them). */
trait RejectionSink:
  def record(r: RejectedVector): Unit

final class InMemoryRejectionSink extends RejectionSink:
  private val buf = scala.collection.mutable.ListBuffer.empty[RejectedVector]

  def record(r: RejectedVector): Unit = synchronized(buf += r)

  def all: List[RejectedVector] = synchronized(buf.toList)

/** In-memory S3 Vectors fake: the five indexes (or any named set) with fixed dimensions, cosine
  * top-k with metadata filters, and the same per-vector validation as the adapter. A key that
  * exists with a different `textHash` is `Rejected(KeyCollision)` (and recorded); the same key with
  * the same hash is idempotent (`Accepted`, nothing changes).
  */
final class InMemoryVectorStore(
    indexDimensions: Map[String, Int],
    sink: RejectionSink = new InMemoryRejectionSink,
) extends VectorStore:
  private val data: Map[String, scala.collection.mutable.LinkedHashMap[String, VectorEntry]] =
    indexDimensions.keys
      .map(_ -> scala.collection.mutable.LinkedHashMap.empty[String, VectorEntry])
      .toMap

  def putBatch(
      indexName: String,
      vectors: List[VectorEntry],
  ): StorageResult[List[VectorPutResult]] =
    synchronized {
      for
        dim <- indexDimensions.get(indexName).toRight(StorageError.NotFound("index"))
        _   <-
          if vectors.nonEmpty && vectors.size <= S3VectorsLimits.MaxVectorsPerPut then Right(())
          else Left(StorageError.InvalidRequest("a batch holds 1 to 500 vectors"))
        _ <-
          if vectors.map(VectorChecks.estimatedBytes).sum <= S3VectorsLimits.MaxPayloadBytes then
            Right(())
          else Left(StorageError.InvalidRequest("batch payload exceeds the request size limit"))
      yield
        val idx = data(indexName)
        vectors.map { e =>
          val key = e.record.vectorKey
          val out = VectorChecks.reject(indexName, dim, e) match
            case Some((why, detail)) =>
              sink.record(RejectedVector(indexName, key, why, detail))
              VectorPutOutcome.Rejected(why)
            case None =>
              idx.get(key) match
                case Some(old) =>
                  VectorChecks.againstStored(
                    VectorMetadata.encode(old.record),
                    old.embedding,
                    e,
                  ) match
                    case None                => VectorPutOutcome.Accepted
                    case Some((why, detail)) =>
                      sink.record(RejectedVector(indexName, key, why, detail))
                      VectorPutOutcome.Rejected(why)
                case None =>
                  idx.update(key, e)
                  VectorPutOutcome.Accepted
          VectorPutResult(key, out)
        }
    }

  /** One page of a query, like one QueryVectors call: at most `MaxQueryResultsPerPage` hits and a
    * token (the next offset) when more hits remain within `topK`.
    */
  def queryPage(
      indexName: String,
      q: VectorQuery,
      nextToken: Option[String],
  ): StorageResult[(List[VectorHit], Option[String])] =
    synchronized {
      for
        dim <- indexDimensions.get(indexName).toRight(StorageError.NotFound("index"))
        _   <-
          if q.topK >= 1 && q.topK <= S3VectorsLimits.MaxTopK then Right(())
          else Left(StorageError.InvalidRequest("topK must be 1 to 10000"))
        _ <-
          if q.embedding.length == dim then Right(())
          else Left(StorageError.InvalidRequest("query dimension differs from the index"))
        _ <-
          if q.embedding.exists(f => f.isNaN || f.isInfinity) || q.embedding.forall(_ == 0f) then
            Left(StorageError.InvalidRequest("query vector is zero or not finite"))
          else Right(())
        _ <- q.filter
          .flatMap(VectorChecks.filterProblem(_))
          .toLeft(())
          .left
          .map(StorageError.InvalidRequest(_))
        offset <- nextToken match
          case None    => Right(0)
          case Some(t) =>
            t.toIntOption.filter(_ >= 0).toRight(StorageError.InvalidRequest("bad nextToken"))
      yield
        val ranked = data(indexName).values.toList
          .map(e => (e, VectorMetadata.encode(e.record)))
          .filter((_, md) => q.filter.forall(VectorChecks.matches(_, md)))
          .map((e, md) =>
            VectorHit(e.record.vectorKey, VectorChecks.cosine(q.embedding, e.embedding), md)
          )
          .sortBy(h => (-h.score, h.vectorKey))
          .take(q.topK)
        val page = ranked.slice(offset, offset + S3VectorsLimits.MaxQueryResultsPerPage)
        val next = offset + page.size
        (page, if next < ranked.size then Some(next.toString) else None)
    }

  /** Top `topK` hits, collected page by page (at most 100 per page) as the adapter does. */
  def query(indexName: String, q: VectorQuery): StorageResult[List[VectorHit]] =
    VectorChecks.collectPages(q.topK, queryPage(indexName, q, _))

  def get(indexName: String, vectorKey: String): StorageResult[Option[VectorEntry]] =
    synchronized(
      indexDimensions
        .get(indexName)
        .toRight(StorageError.NotFound("index"))
        .map(_ => data(indexName).get(vectorKey))
    )

  def size(indexName: String): Int = synchronized(data.get(indexName).map(_.size).getOrElse(0))

/** S3 Vectors adapter (sync client). For each batch it first reads the existing metadata of the
  * batch keys (GetVectors, at most 100 keys per call) to find key collisions, skips vectors whose
  * key and text hash already exist, and sends the rest in ONE PutVectors call (the caller keeps
  * batches within the limits; see `VectorLoader`). S3 Vectors has no conditional put, so two
  * concurrent writers of the same new key can both pass the check; the loader is expected to be the
  * only writer of a run (documented risk).
  */
final class S3VectorsStore(
    client: S3VectorsClient,
    vectorBucketName: String,
    indexDimensions: Map[String, Int],
    sink: RejectionSink = new InMemoryRejectionSink,
) extends VectorStore:

  private def floats(v: Vector[Float]): VectorData =
    VectorData.fromFloat32(v.map(Float.box).asJava)

  private def mdDoc(m: Map[String, String]): Document =
    val b = Document.mapBuilder()
    m.toList.sortBy(_._1).foreach((k, v) => b.putString(k, v))
    b.build()

  private def mdMap(d: Document): Map[String, String] =
    if d == null || !d.isMap then Map.empty
    else d.asMap().asScala.toMap.collect { case (k, v) if v.isString => k -> v.asString() }

  private def existing(
      indexName: String,
      keys: List[String],
  ): StorageResult[Map[String, (Map[String, String], Vector[Float])]] =
    keys.distinct
      .grouped(S3VectorsLimits.MaxVectorsPerGet)
      .foldLeft[StorageResult[Map[String, (Map[String, String], Vector[Float])]]](
        Right(Map.empty)
      ) { (acc, chunk) =>
        for
          m <- acc
          r <- AwsErrors.guard("s3vectors get")(
            client.getVectors(
              GetVectorsRequest
                .builder()
                .vectorBucketName(vectorBucketName)
                .indexName(indexName)
                .keys(chunk.asJava)
                .returnData(true)
                .returnMetadata(true)
                .build()
            )
          )
        yield m ++ r.vectors().asScala.map { v =>
          val emb = Option(v.data())
            .map(_.float32().asScala.toVector.map(_.floatValue))
            .getOrElse(Vector.empty)
          v.key() -> (mdMap(v.metadata()), emb)
        }
      }

  def putBatch(
      indexName: String,
      vectors: List[VectorEntry],
  ): StorageResult[List[VectorPutResult]] =
    for
      dim <- indexDimensions.get(indexName).toRight(StorageError.NotFound("index"))
      _   <-
        if vectors.nonEmpty && vectors.size <= S3VectorsLimits.MaxVectorsPerPut then Right(())
        else Left(StorageError.InvalidRequest("a batch holds 1 to 500 vectors"))
      _ <-
        if vectors.map(VectorChecks.estimatedBytes).sum <= S3VectorsLimits.MaxPayloadBytes then
          Right(())
        else Left(StorageError.InvalidRequest("batch payload exceeds the request size limit"))
      valid = vectors.map(e => e -> VectorChecks.reject(indexName, dim, e))
      have <- existing(indexName, valid.collect { case (e, None) => e.record.vectorKey })
      seen    = scala.collection.mutable.HashMap.empty[String, VectorEntry]
      decided = valid.map { (e, bad) =>
        val key = e.record.vectorKey
        bad match
          case Some((why, detail)) =>
            sink.record(RejectedVector(indexName, key, why, detail))
            (e, Some(VectorPutOutcome.Rejected(why)), false)
          case None =>
            val stored: Option[(Map[String, String], Vector[Float])] =
              have
                .get(key)
                .orElse(seen.get(key).map(x => (VectorMetadata.encode(x.record), x.embedding)))
            stored match
              case Some((md, emb)) =>
                VectorChecks.againstStored(md, emb, e) match
                  case None                => (e, Some(VectorPutOutcome.Accepted), false)
                  case Some((why, detail)) =>
                    sink.record(RejectedVector(indexName, key, why, detail))
                    (e, Some(VectorPutOutcome.Rejected(why)), false)
              case None =>
                seen.update(key, e)
                (e, None, true)
      }
      toPut = decided
        .collect { case (e, _, true) => e }
        .groupBy(_.record.vectorKey)
        .values
        .map(_.head)
        .toList
      _ <-
        if toPut.isEmpty then Right(())
        else
          val inputs = toPut.sortBy(_.record.vectorKey).map { e =>
            PutInputVector
              .builder()
              .key(e.record.vectorKey)
              .data(floats(e.embedding))
              .metadata(mdDoc(VectorMetadata.encode(e.record)))
              .build()
          }
          AwsErrors
            .guard("s3vectors put")(
              client.putVectors(
                PutVectorsRequest
                  .builder()
                  .vectorBucketName(vectorBucketName)
                  .indexName(indexName)
                  .vectors(inputs.asJava)
                  .build()
              )
            )
            .map(_ => ())
    yield decided.map((e, out, _) =>
      VectorPutResult(e.record.vectorKey, out.getOrElse(VectorPutOutcome.Accepted))
    )

  def query(indexName: String, q: VectorQuery): StorageResult[List[VectorHit]] =
    for
      dim <- indexDimensions.get(indexName).toRight(StorageError.NotFound("index"))
      _   <-
        if q.topK >= 1 && q.topK <= S3VectorsLimits.MaxTopK then Right(())
        else Left(StorageError.InvalidRequest("topK must be 1 to 10000"))
      _ <-
        if q.embedding.length == dim && !q.embedding
            .exists(f => f.isNaN || f.isInfinity) && !q.embedding.forall(_ == 0f)
        then Right(())
        else
          Left(
            StorageError.InvalidRequest(
              "query vector has the wrong dimension, is zero or not finite"
            )
          )
      _ <- q.filter
        .flatMap(VectorChecks.filterProblem(_))
        .toLeft(())
        .left
        .map(StorageError.InvalidRequest(_))
      hits <- VectorChecks.collectPages(
        q.topK,
        token =>
          AwsErrors
            .guard("s3vectors query") {
              val b = QueryVectorsRequest
                .builder()
                .vectorBucketName(vectorBucketName)
                .indexName(indexName)
                .topK(q.topK)
                .queryVector(floats(q.embedding))
                .returnMetadata(true)
                .returnDistance(true)
              q.filter.foreach(f => b.filter(VectorChecks.filterDocument(f)))
              token.foreach(b.nextToken)
              client.queryVectors(b.build())
            }
            .map { r =>
              val page = r
                .vectors()
                .asScala
                .toList
                .map(v => VectorHit(v.key(), 1.0 - v.distance().toDouble, mdMap(v.metadata())))
              (page, Option(r.nextToken()).filter(_.nonEmpty))
            },
      )
    yield hits

  def get(indexName: String, vectorKey: String): StorageResult[Option[VectorEntry]] =
    for
      dim <- indexDimensions.get(indexName).toRight(StorageError.NotFound("index"))
      r   <- AwsErrors.guard("s3vectors get")(
        client.getVectors(
          GetVectorsRequest
            .builder()
            .vectorBucketName(vectorBucketName)
            .indexName(indexName)
            .keys(List(vectorKey).asJava)
            .returnData(true)
            .returnMetadata(true)
            .build()
        )
      )
      out <- r.vectors().asScala.headOption match
        case None    => Right(None)
        case Some(v) =>
          val emb = v.data().float32().asScala.toVector.map(_.floatValue)
          VectorMetadata
            .decode(v.key(), indexName, dim, mdMap(v.metadata()))
            .left
            .map(m => StorageError.Failed(s"stored vector metadata: $m"))
            .map(rec => Some(VectorEntry(rec, emb)))
    yield out

/** Outcome of `VectorLoader.load`. `results` has one entry per input vector, in input order. */
final case class VectorLoadReport(
    results: List[VectorPutResult],
    batches: Int,
    retries: Int,
    failedBatches: Int,
):
  def accepted: Int = results.count(_.outcome == VectorPutOutcome.Accepted)

  def rejected: Map[VectorRejection, Int] =
    results
      .collect { case VectorPutResult(_, VectorPutOutcome.Rejected(r)) => r }
      .groupBy(identity)
      .map((k, v) => k -> v.size)

/** Bounded, batched, retrying loader over any `VectorStore`.
  *   - Batches hold at most 500 vectors and an estimated 20 MiB (`VectorChecks.batches`).
  *   - A batch that fails with a retryable error is retried with bounded exponential backoff
  *     (`Retry`, caps from `StorageRetry`, injectable `Sleeper`).
  *   - A batch that still fails after the attempts (or fails with a non-retryable error) marks each
  *     of its vectors `Rejected(ServiceRejected)`, records them in the sink, and the loader goes on
  *     with the next batch; the load as a whole is not retried forever.
  *   - `Left` is returned only when the index does not exist (`NotFound`) or the arguments are
  *     invalid before any call.
  */
final class VectorLoader(
    store: VectorStore,
    retry: StorageRetry,
    sleeper: Sleeper,
    sink: RejectionSink,
):
  def load(indexName: String, entries: List[VectorEntry]): StorageResult[VectorLoadReport] =
    if entries.isEmpty then Left(StorageError.InvalidRequest("nothing to load"))
    else
      var retries = 0
      var failed  = 0
      val groups  = VectorChecks.batches(entries)
      val results = scala.collection.mutable.ListBuffer.empty[VectorPutResult]
      var fatal   = Option.empty[StorageError]
      val it      = groups.iterator
      while fatal.isEmpty && it.hasNext do
        val batch = it.next()
        Retry.run(retry, sleeper, (_, _, _) => retries += 1)(store.putBatch(indexName, batch)) match
          case Right(rs)                      => results ++= rs
          case Left(e: StorageError.NotFound) => fatal = Some(e)
          case Left(e: StorageError.InvalidRequest)
              if results.isEmpty && batch.size == entries.size =>
            fatal = Some(e)
          case Left(_) =>
            failed += 1
            batch.foreach { v =>
              sink.record(
                RejectedVector(
                  indexName,
                  v.record.vectorKey,
                  VectorRejection.ServiceRejected,
                  "batch failed after retries",
                )
              )
              results += VectorPutResult(
                v.record.vectorKey,
                VectorPutOutcome.Rejected(VectorRejection.ServiceRejected),
              )
            }
      fatal.toLeft(VectorLoadReport(results.toList, groups.size, retries, failed))
