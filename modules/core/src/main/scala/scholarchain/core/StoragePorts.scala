package scholarchain.core

/** Phase 07 storage ports. Plain Scala types only: no AWS SDK type, no exception as control flow.
  * Every operation returns `Either[StorageError, A]`; adapters (AWS or fake) translate their own
  * failures into `StorageError`. Messages in `StorageError` must never contain secrets or
  * configuration values.
  */
enum StorageError:
  /** The named object, item or vector does not exist (only where absence is an error). */
  case NotFound(what: String)

  /** A conditional write found the object already present (`ObjectStore.putIfAbsent`). */
  case AlreadyExists(what: String)

  /** A content hash did not match the expected one. */
  case IntegrityMismatch(what: String)

  /** The request itself is invalid (bad key, bound exceeded, unknown template). Not retryable. */
  case InvalidRequest(reason: String)

  /** The service rejected the call for rate or capacity reasons. Retryable with backoff. */
  case Throttled(reason: String)

  /** The service or network was unavailable or timed out. Retryable with backoff. */
  case Unavailable(reason: String)

  /** Any other failure. Not retryable. */
  case Failed(reason: String)

  def retryable: Boolean = this match
    case _: Throttled | _: Unavailable => true
    case _                             => false

  def code: String = this match
    case _: NotFound          => "not-found"
    case _: AlreadyExists     => "already-exists"
    case _: IntegrityMismatch => "integrity-mismatch"
    case _: InvalidRequest    => "invalid-request"
    case _: Throttled         => "throttled"
    case _: Unavailable       => "unavailable"
    case _: Failed            => "failed"

type StorageResult[A] = Either[StorageError, A]

// ---- object store ------------------------------------------------------------------------

/** An object address. `uri` is `s3://<bucket>/<object>`. */
final case class ObjectLocation(bucket: String, objectKey: String):
  def uri: String = s"s3://$bucket/$objectKey"

object ObjectLocation:
  /** Parses and validates an S3 URI (same syntax as the storage records). */
  def parse(uri: String): Either[ValidationError, ObjectLocation] =
    if StorageChecks.isS3Uri(uri) then
      val rest = uri.stripPrefix("s3://")
      val i    = rest.indexOf('/')
      Right(ObjectLocation(rest.substring(0, i), rest.substring(i + 1)))
    else Left(ValidationError.InvalidField("uri", "must be an s3://<bucket>/<object> URI"))

/** Metadata of a stored object. `contentHash` is the SHA-256 hex of the bytes. */
final case class ObjectMeta(
    location: ObjectLocation,
    sizeBytes: Long,
    contentHash: String,
    contentType: Option[String],
    versionId: Option[String],
    lastModified: Option[String],
)

/** Object bytes with their metadata (compare by `meta.contentHash`, not by array identity). */
final case class ObjectData(meta: ObjectMeta, bytes: Array[Byte])

/** One page of a prefix listing; `nextToken` is `None` on the last page. */
final case class ObjectPage(objects: List[ObjectMeta], nextToken: Option[String])

trait ObjectStore:
  /** Stores bytes and records their SHA-256. When `expectedHash` is given and differs from the
    * SHA-256 of `bytes`, returns `IntegrityMismatch` and stores nothing.
    */
  def put(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String] = None,
      expectedHash: Option[String] = None,
  ): StorageResult[ObjectMeta]

  /** Like `put`, but returns `AlreadyExists` when the object is already present (atomic). */
  def putIfAbsent(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String] = None,
      expectedHash: Option[String] = None,
  ): StorageResult[ObjectMeta]

  /** `NotFound` when absent. */
  def get(location: ObjectLocation): StorageResult[ObjectData]

  /** `Right(None)` when absent (absence is not an error for `head`). */
  def head(location: ObjectLocation): StorageResult[Option[ObjectMeta]]

  /** Lists objects whose name starts with `prefix`, in ascending key order, at most `limit` (>= 1)
    * per page.
    */
  def list(
      bucket: String,
      prefix: String,
      limit: Int,
      continuationToken: Option[String] = None,
  ): StorageResult[ObjectPage]

// ---- graph store -------------------------------------------------------------------------

/** A typed graph parameter or result value. */
enum GraphValue:
  case Str(value: String)
  case Num(value: Long)
  case Dbl(value: Double)
  case Bool(value: Boolean)
  case Null
  case Lst(values: List[GraphValue])
  case Obj(fields: Map[String, GraphValue])

/** Hard bounds every graph query carries: depth (hops), result rows, and time. */
final case class QueryBounds(maxDepth: Int, maxResults: Int, timeoutMillis: Long)

object QueryBounds:
  def validate(b: QueryBounds): Either[ValidationError, QueryBounds] =
    V.all(
      V.atLeast("maxDepth", b.maxDepth, 1),
      V.atLeast("maxResults", b.maxResults, 1),
      if b.timeoutMillis >= 1L then V.ok
      else Left(ValidationError.InvalidField("timeoutMillis", "must be >= 1")),
    ).map(_ => b)

/** The allowlisted read-only query templates. A `GraphQuery` names one; free-form query text is
  * never part of the port.
  */
object GraphTemplates:
  val Neighbors: String         = "neighbors"
  val CoauthorEdge: String      = "coauthor-edge"
  val ShortestPath: String      = "shortest-path"
  val Component: String         = "component"
  val Community: String         = "community"
  val ResearcherMetrics: String = "researcher-metrics"

  val all: List[String] =
    List(Neighbors, CoauthorEdge, ShortestPath, Component, Community, ResearcherMetrics)

/** A parameterized read query: a template name, typed parameters, and bounds. */
final case class GraphQuery(
    template: String,
    parameters: Map[String, GraphValue],
    bounds: QueryBounds,
)

final case class GraphRow(columns: Map[String, GraphValue])

/** Query result; `truncated` is true when more rows existed than `bounds.maxResults`. */
final case class GraphResult(rows: List[GraphRow], truncated: Boolean)

/** Bulk-load request: S3 source prefix and format (the role comes from configuration). */
final case class BulkLoadRequest(sourceUri: String, format: String, runId: String)

final case class BulkLoadHandle(loadId: String)

enum BulkLoadStatus:
  case Submitted
  case InProgress(processedRecords: Option[Long])
  case Completed(processedRecords: Option[Long])
  case LoadFailed(reason: String)
  case Cancelled

trait GraphStore:
  /** Read-only. `InvalidRequest` for an unknown template, a missing or mistyped parameter, or a
    * bound that is invalid.
    */
  def query(q: GraphQuery): StorageResult[GraphResult]

  def submitBulkLoad(request: BulkLoadRequest): StorageResult[BulkLoadHandle]

  def bulkLoadStatus(handle: BulkLoadHandle): StorageResult[BulkLoadStatus]

// ---- key-value store ---------------------------------------------------------------------

/** Item key: a partition key and an optional sort key (tables without a sort key use `None`). */
final case class KvKey(partition: String, sort: Option[String] = None)

/** One item. Attributes are strings; `expiresAtEpochSeconds` is the TTL attribute, set only for
  * disposable data.
  */
final case class KvItem(
    key: KvKey,
    attributes: Map[String, String],
    expiresAtEpochSeconds: Option[Long] = None,
)

/** Condition of a write. */
enum PutCondition:
  case Always
  case NotExists
  case AttributeEquals(name: String, value: String)

/** Typed outcome of a conditional put. A failed condition is a normal outcome, not an error;
  * `existing` is the stored item when the store reports it.
  */
enum PutOutcome:
  case Written
  case ConditionFailed(existing: Option[KvItem])

/** Sort-key condition of a query. */
enum SortCondition:
  case Equals(value: String)
  case BeginsWith(prefix: String)
  case Between(low: String, high: String)
  case LessThan(value: String)
  case LessOrEqual(value: String)
  case GreaterThan(value: String)
  case GreaterOrEqual(value: String)

final case class KvPage(items: List[KvItem], nextToken: Option[String])

trait KeyValueStore:
  def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome]

  /** `Right(None)` when absent. */
  def get(table: String, key: KvKey): StorageResult[Option[KvItem]]

  /** Items of one partition, ordered by sort key (ascending unless `ascending` is false), at most
    * `limit` (>= 1) per page.
    */
  def query(
      table: String,
      partition: String,
      sort: Option[SortCondition],
      limit: Int,
      ascending: Boolean = true,
      continuationToken: Option[String] = None,
  ): StorageResult[KvPage]

// ---- vector store ------------------------------------------------------------------------

/** A vector to store: its record and the embedding (length must equal `record.dimension`). */
final case class VectorEntry(record: VectorRecord, embedding: Vector[Float])

/** Why one vector of a batch was not accepted. */
enum VectorRejection:
  /** The embedding length differs from the record dimension or the index dimension. */
  case DimensionMismatch

  /** The key exists with a different text hash. */
  case KeyCollision

  /** The key and text hash match an existing vector but the embedding or the embedding model
    * version differs (an idempotent re-put is not possible; `KeyCollision` is reserved for a
    * different text hash).
    */
  case EmbeddingMismatch

  /** The record failed its contract or names a different index. */
  case InvalidRecord

  /** The service refused it for a reason not listed above. */
  case ServiceRejected

enum VectorPutOutcome:
  case Accepted
  case Rejected(reason: VectorRejection)

final case class VectorPutResult(vectorKey: String, outcome: VectorPutOutcome)

/** Metadata filter over the compact metadata of a `VectorRecord`. */
enum MetadataFilter:
  case Equals(name: String, value: String)
  case In(name: String, values: List[String])
  case And(filters: List[MetadataFilter])

final case class VectorQuery(
    embedding: Vector[Float],
    topK: Int,
    filter: Option[MetadataFilter] = None,
)

/** A hit: higher `score` is more similar (cosine similarity in the fake). */
final case class VectorHit(vectorKey: String, score: Double, metadata: Map[String, String])

trait VectorStore:
  /** Stores a batch. The `Right` list has exactly one result per input vector, in input order.
    * `Left` only when the whole call failed (for example the index does not exist).
    */
  def putBatch(indexName: String, vectors: List[VectorEntry]): StorageResult[List[VectorPutResult]]

  /** Top-k by similarity, descending score, at most `topK` (>= 1) hits. */
  def query(indexName: String, q: VectorQuery): StorageResult[List[VectorHit]]

  /** `Right(None)` when the key is absent. */
  def get(indexName: String, vectorKey: String): StorageResult[Option[VectorEntry]]

// ---- work queue --------------------------------------------------------------------------

/** A received message. `receiptHandle` is the token for `delete`; `receiveCount` counts deliveries.
  */
final case class QueueMessage(
    messageId: String,
    receiptHandle: String,
    body: String,
    receiveCount: Int,
)

trait WorkQueue:
  /** Sends a message; returns its message ID. `deduplicationId` makes a resend idempotent where the
    * queue supports it.
    */
  def send(
      queue: String,
      body: String,
      deduplicationId: Option[String] = None,
  ): StorageResult[String]

  /** Receives up to `maxMessages` (>= 1); each is invisible to other consumers for
    * `visibilityTimeoutSeconds` unless deleted. `waitSeconds` is the long-poll time (0 = none).
    */
  def receive(
      queue: String,
      maxMessages: Int,
      visibilityTimeoutSeconds: Int,
      waitSeconds: Int = 0,
  ): StorageResult[List[QueueMessage]]

  /** Acknowledges (deletes) a received message. */
  def delete(queue: String, receiptHandle: String): StorageResult[Unit]
