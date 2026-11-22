package scholarchain.storage

import scholarchain.core.*

/** Outcome of claiming a vector key. */
enum ClaimOutcome:
  /** This caller wrote the claim: it may write the vector. */
  case Won

  /** An identical claim exists (same text hash, model, version, dimension, pointer, index; the
    * claiming run is ignored): proceed. The vector may or may not exist yet.
    */
  case Identical

  /** A different text hash is claimed: the key may never refer to different text. */
  case KeyCollision

  /** The same text hash is claimed with another model, version, dimension, pointer or index. */
  case EmbeddingMismatch

  /** The claim store failed or answered ambiguously. Retryable; never write. */
  case StoreFailed(code: String)

/** Claims of vector keys, written with a conditional put (`PutCondition.NotExists`) through the
  * core `KeyValueStore` port. S3 Vectors has no conditional put, so this table is the authority
  * that a vector key refers to exactly one text.
  *
  * Table (`embedding.claims.table-name`, Terraform in Phase 12): partition key `pk` = vector key
  * (string, at most 512 characters), NO sort key, on-demand capacity, no TTL (claims are
  * permanent). Item attributes (strings): identity `textHash`, `embeddingModelId`,
  * `embeddingModelVersion`, `dimension`, `textS3Uri`, `indexName`; provenance (never part of
  * identity) `runId`, `jobId`, `claimVersion`.
  */
final class EmbeddingKeyClaims(kv: KeyValueStore, table: String):
  import EmbeddingKeyClaims.*

  def claim(record: VectorRecord, jobId: String): ClaimOutcome =
    val item = KvItem(
      KvKey(record.vectorKey),
      identity(record) ++ Map(RunId -> record.runId, JobId -> jobId, ClaimVersion -> "1"),
    )
    kv.put(table, item, PutCondition.NotExists) match
      case Left(e)                   => ClaimOutcome.StoreFailed(s"claim-store-failed:${e.code}")
      case Right(PutOutcome.Written) => ClaimOutcome.Won
      case Right(PutOutcome.ConditionFailed(None)) =>
        ClaimOutcome.StoreFailed("claim-store-failed:no-existing")
      case Right(PutOutcome.ConditionFailed(Some(found))) =>
        val mine = identity(record)
        if found.attributes.get(TextHash) != mine.get(TextHash) then ClaimOutcome.KeyCollision
        else if IdentityKeys.forall(k => found.attributes.get(k) == mine.get(k)) then
          ClaimOutcome.Identical
        else ClaimOutcome.EmbeddingMismatch

object EmbeddingKeyClaims:
  val TextHash: String     = "textHash"
  val ModelId: String      = "embeddingModelId"
  val ModelVersion: String = "embeddingModelVersion"
  val Dimension: String    = "dimension"
  val TextS3Uri: String    = "textS3Uri"
  val IndexName: String    = "indexName"
  val RunId: String        = "runId"
  val JobId: String        = "jobId"
  val ClaimVersion: String = "claimVersion"

  val IdentityKeys: List[String] =
    List(TextHash, ModelId, ModelVersion, Dimension, TextS3Uri, IndexName)

  /** Table schema of the claims table for `InMemoryKeyValueStore` (no sort key). */
  val Schema: KvTableSchema = KvTableSchema(hasSortKey = false)

  def identity(r: VectorRecord): Map[String, String] = Map(
    TextHash     -> r.textHash,
    ModelId      -> r.embeddingModelId,
    ModelVersion -> r.embeddingModelVersion,
    Dimension    -> r.dimension.toString,
    TextS3Uri    -> r.textS3Uri,
    IndexName    -> r.indexName,
  )
