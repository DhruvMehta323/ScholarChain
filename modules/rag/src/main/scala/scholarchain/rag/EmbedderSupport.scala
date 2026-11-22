package scholarchain.rag

import scholarchain.core.*

/** Wraps any `Embedder` and rejects what the port forbids: an empty batch, a batch above
  * `maxBatchSize` or a blank text (`InvalidRequest`); a result whose count differs from the input
  * count (`Failed`); a vector whose length differs from `dimension` (`DimensionMismatch(expected,
  * actual)`); a non-finite component (`Failed`). Identity (model ID, version, dimension, batch
  * size) is the inner embedder's.
  */
final class ValidatingEmbedder(inner: Embedder) extends Embedder:
  def modelId: String      = inner.modelId
  def modelVersion: String = inner.modelVersion
  def dimension: Int       = inner.dimension
  def maxBatchSize: Int    = inner.maxBatchSize

  def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]] =
    if texts.isEmpty then Left(EmbeddingError.InvalidRequest("empty batch"))
    else if texts.size > maxBatchSize then
      Left(EmbeddingError.InvalidRequest(s"batch larger than $maxBatchSize"))
    else if texts.exists(_.isBlank) then Left(EmbeddingError.InvalidRequest("blank text"))
    else inner.embed(texts).flatMap(check(texts.size, _))

  private def check(n: Int, vs: List[Vector[Float]]): EmbeddingResult[List[Vector[Float]]] =
    if vs.size != n then Left(EmbeddingError.Failed("vector count differs from input count"))
    else
      vs.find(_.length != dimension) match
        case Some(bad) => Left(EmbeddingError.DimensionMismatch(dimension, bad.length))
        case None      =>
          if vs.exists(_.exists(f => f.isNaN || f.isInfinite)) then
            Left(EmbeddingError.Failed("non-finite vector component"))
          else Right(vs)

/** The identity every vector carries. */
final case class ModelStamp(modelId: String, modelVersion: String, dimension: Int)

object ModelVersioning:
  def stampOf(e: Embedder): ModelStamp = ModelStamp(e.modelId, e.modelVersion, e.dimension)

  /** Fails unless the entry was produced by `e`: record model ID, version and dimension equal the
    * embedder's, and the embedding length equals the dimension.
    */
  def checkEntry(e: Embedder, entry: VectorEntry): Either[EmbeddingError, VectorEntry] =
    val r = entry.record
    if r.embeddingModelId != e.modelId || r.embeddingModelVersion != e.modelVersion then
      Left(EmbeddingError.Failed("vector was produced by a different model or model version"))
    else if r.dimension != e.dimension then
      Left(EmbeddingError.DimensionMismatch(e.dimension, r.dimension))
    else if entry.embedding.length != e.dimension then
      Left(EmbeddingError.DimensionMismatch(e.dimension, entry.embedding.length))
    else Right(entry)

  /** Builds a `VectorEntry` that stamps the embedder's identity onto the record. Rejects a vector
    * of the wrong length.
    */
  def entryFor(
      e: Embedder,
      document: EmbeddingDocument,
      indexName: String,
      metadata: Map[String, String],
      runId: String,
      embedding: Vector[Float],
  ): Either[EmbeddingError, VectorEntry] =
    checkEntry(
      e,
      VectorEntry(
        VectorRecord(
          schemaVersion = SchemaVersion.Current,
          vectorKey = document.documentId,
          indexName = indexName,
          dimension = e.dimension,
          embeddingModelId = e.modelId,
          embeddingModelVersion = e.modelVersion,
          textHash = document.textHash,
          textS3Uri = document.textS3Uri,
          metadata = metadata,
          runId = runId,
        ),
        embedding,
      ),
    )
