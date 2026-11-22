package scholarchain.rag

import java.nio.charset.StandardCharsets

import scholarchain.core.*

/** Deterministic local test embedder: hashed bag of words, L2-normalized. NOT a semantic model; it
  * exists so tests and the local run need no network. It makes no call of any kind.
  *
  *   - Tokens: lower-cased (`Locale.ROOT`) maximal runs of letters or digits (`\p{L}\p{N}`).
  *   - Each token is hashed with 64-bit FNV-1a over its UTF-8 bytes (stable across JVMs and runs);
  *     its bucket is `floorMod(hash, dimension)`; the bucket count increases by 1.
  *   - The count vector is divided by its Euclidean norm. A text with no token is `InvalidRequest`.
  *
  * Default identity: model ID `local-hash-bow`, version `v1`, dimension 1024.
  */
final class LocalHashEmbedder(
    val dimension: Int = LocalHashEmbedder.DefaultDimension,
    val modelId: String = LocalHashEmbedder.ModelId,
    val modelVersion: String = LocalHashEmbedder.ModelVersion,
    val maxBatchSize: Int = LocalHashEmbedder.DefaultMaxBatchSize,
) extends Embedder:

  def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]] =
    if dimension < 1 || dimension > VectorRecord.MaxDimension then
      Left(EmbeddingError.Failed("embedder dimension is out of range"))
    else if texts.isEmpty then Left(EmbeddingError.InvalidRequest("empty batch"))
    else if texts.size > maxBatchSize then
      Left(EmbeddingError.InvalidRequest(s"batch larger than $maxBatchSize"))
    else
      val vectors = texts.map(embedOne)
      if vectors.exists(_.isEmpty) then Left(EmbeddingError.InvalidRequest("text has no token"))
      else Right(vectors.flatten)

  private def embedOne(text: String): Option[Vector[Float]] =
    val toks = LocalHashEmbedder.tokens(text)
    if toks.isEmpty then None
    else
      val counts = new Array[Double](dimension)
      toks.foreach(t => counts(LocalHashEmbedder.bucket(t, dimension)) += 1.0)
      val norm = math.sqrt(counts.map(c => c * c).sum)
      Some(counts.iterator.map(c => (c / norm).toFloat).toVector)

object LocalHashEmbedder:
  val ModelId: String          = "local-hash-bow"
  val ModelVersion: String     = "v1"
  val DefaultDimension: Int    = 1024
  val DefaultMaxBatchSize: Int = 64

  private val TokenRun = """[\p{L}\p{N}]+""".r

  /** Lower-cased letter/digit runs, in order of appearance (with repeats). */
  def tokens(text: String): List[String] =
    TokenRun.findAllIn(text.toLowerCase(java.util.Locale.ROOT)).toList

  /** 64-bit FNV-1a over the UTF-8 bytes. */
  def fnv1a64(s: String): Long =
    var h = 0xcbf29ce484222325L
    s.getBytes(StandardCharsets.UTF_8).foreach { b =>
      h ^= (b & 0xffL)
      h *= 0x100000001b3L
    }
    h

  def bucket(token: String, dimension: Int): Int =
    java.lang.Math.floorMod(fnv1a64(token), dimension.toLong).toInt
