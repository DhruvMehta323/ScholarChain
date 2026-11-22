package scholarchain.storage

import scholarchain.core.*

/** Source text of a vector, fetched from the object store through its `textS3Uri` pointer. */
final case class ResolvedText(textS3Uri: String, textHash: String, text: String)

/** Resolves a stored vector (a `VectorRecord`, a `VectorEntry` or a `VectorHit`) back to its
  * authoritative text. Vectors carry only the S3 pointer (`textS3Uri`) and the SHA-256
  * (`textHash`); the text lives in the object store. The fetched bytes are hashed and compared with
  * `textHash`; a mismatch is `IntegrityMismatch` and no text is returned.
  *
  * A `VectorHit` carries the system metadata the adapter stored (`textS3Uri`, `textHash`), so a
  * query result can be resolved without a second vector lookup. A hit without them is `NotFound`.
  */
final class VectorTextResolver(objects: ObjectStore):
  def resolve(record: VectorRecord): StorageResult[ResolvedText] =
    fetch(record.textS3Uri, record.textHash)

  def resolve(entry: VectorEntry): StorageResult[ResolvedText] = resolve(entry.record)

  def resolve(hit: VectorHit): StorageResult[ResolvedText] =
    (hit.metadata.get(VectorMetadata.TextS3Uri), hit.metadata.get(VectorMetadata.TextHash)) match
      case (Some(uri), Some(h)) => fetch(uri, h)
      case _                    => Left(StorageError.NotFound("text pointer in hit metadata"))

  private def fetch(uri: String, expectedHash: String): StorageResult[ResolvedText] =
    for
      loc <- ObjectLocation.parse(uri).left.map(e => StorageError.InvalidRequest(e.message))
      d   <- objects.get(loc)
      _   <-
        if Hashing.sha256Hex(d.bytes) == expectedHash then Right(())
        else Left(StorageError.IntegrityMismatch("text differs from the vector's text hash"))
      t <- EmbeddingWorker
        .decodeUtf8(d.bytes)
        .left
        .map(_ => StorageError.Failed("stored text is not valid UTF-8"))
    yield ResolvedText(uri, expectedHash, t)

/** The dedicated vector index for live suites (`embedding.test.vector-index-name`). */
object VectorTestIndex:
  /** The test index name, validated: it is never one of the five production index names and never a
    * name configured for storage.
    */
  def resolve(
      embedding: EmbeddingSettings,
      storage: StorageModuleConfig,
  ): Either[ValidationError, String] =
    EmbeddingSettings.checkTestIndex(embedding.testVectorIndex, storage.vectors)

  /** A store that only serves `testIndex`: every call on another index is `InvalidRequest` and
    * never reaches `delegate`. Live suites that write vectors wrap their store with it.
    */
  def guard(delegate: VectorStore, testIndex: String): VectorStore = new VectorStore:
    private def only[A](index: String)(op: => StorageResult[A]): StorageResult[A] =
      if index == testIndex then op
      else Left(StorageError.InvalidRequest("only the configured test index is allowed"))

    def putBatch(indexName: String, vectors: List[VectorEntry]) =
      only(indexName)(delegate.putBatch(indexName, vectors))

    def query(indexName: String, q: VectorQuery) = only(indexName)(delegate.query(indexName, q))

    def get(indexName: String, vectorKey: String) =
      only(indexName)(delegate.get(indexName, vectorKey))
