package scholarchain.core

/** Phase 08 embedder port. Plain Scala types only: no AWS SDK type, no HTTP type, no exception as
  * control flow. Adapters (Bedrock, Voyage, the deterministic local test embedder) translate their
  * own failures into `EmbeddingError`.
  *
  * An `EmbeddingError` must NEVER carry a request body, the input text, a header, a URL with
  * credentials, an API key or any configuration value: `reason` is a short fixed or
  * adapter-composed description (for example an HTTP status class), nothing more.
  */
enum EmbeddingError:
  /** The request itself is invalid (empty batch, batch too large, blank text). Not retryable. */
  case InvalidRequest(reason: String)

  /** The service rejected the call for rate or capacity reasons. Retryable with backoff. */
  case Throttled(reason: String)

  /** The service or network was unavailable or timed out. Retryable with backoff. */
  case Unavailable(reason: String)

  /** The returned vector length differs from `Embedder.dimension`. Not retryable. */
  case DimensionMismatch(expected: Int, actual: Int)

  /** Any other failure. Not retryable. */
  case Failed(reason: String)

  def retryable: Boolean = this match
    case _: Throttled | _: Unavailable => true
    case _                             => false

  def code: String = this match
    case _: InvalidRequest    => "invalid-request"
    case _: Throttled         => "throttled"
    case _: Unavailable       => "unavailable"
    case _: DimensionMismatch => "dimension-mismatch"
    case _: Failed            => "failed"

type EmbeddingResult[A] = Either[EmbeddingError, A]

/** Turns texts into embeddings. `modelId`, `modelVersion` and `dimension` travel with every vector
  * written (`VectorRecord.embeddingModelId`, `embeddingModelVersion`, `dimension`).
  *
  * `embed` returns exactly one vector per input text, in input order, each of length `dimension`;
  * an adapter that receives a different length returns `DimensionMismatch`. At most `maxBatchSize`
  * texts per call (more is `InvalidRequest`); an empty list is `InvalidRequest`.
  */
trait Embedder:
  def modelId: String
  def modelVersion: String
  def dimension: Int
  def maxBatchSize: Int
  def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]]
