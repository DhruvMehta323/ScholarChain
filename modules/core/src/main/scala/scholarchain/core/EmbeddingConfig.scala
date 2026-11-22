package scholarchain.core

import ValidationError.*

/** Embedding module configuration keys, all under `embedding.` (additive; the frozen `ConfigKeys`
  * and `StorageConfigKeys` are untouched). Defaults live in `scholarchain-embedding.v1.conf`.
  * Environment variable: `SCHOLARCHAIN_EMBEDDING_<KEY>`; override name:
  * `scholarchain.embedding.<key>`.
  *
  * No secret is ever a config value. `voyage.api-key-env-var` holds only the NAME of the
  * environment variable the Voyage adapter reads its credential from (default
  * `SCHOLARCHAIN_VOYAGE_API_KEY`); the credential itself is read by the adapter at runtime and
  * never logged.
  */
object EmbeddingConfigKeys:
  val Resource = "scholarchain-embedding.v1.conf"

  val SchemaVersionKey = ConfigKey("embedding.schema-version")

  val ModelId        = ConfigKey("embedding.model.id")
  val ModelVersion   = ConfigKey("embedding.model.version")
  val ModelDimension = ConfigKey("embedding.model.dimension")

  val VoyageApiKeyEnvVar = ConfigKey("embedding.voyage.api-key-env-var")

  val QueueName          = ConfigKey("embedding.queue.name")
  val DeadLetterName     = ConfigKey("embedding.queue.dead-letter-name")
  val MaxAttempts        = ConfigKey("embedding.queue.max-attempts")
  val MaxDocumentsPerJob = ConfigKey("embedding.queue.max-documents-per-job")

  val TestVectorIndex = ConfigKey("embedding.test.vector-index-name")

  /** Vector-key claim table (additive, Phase 08 repair 01). Env:
    * `SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME`.
    */
  val ClaimsTableName = ConfigKey("embedding.claims.table-name")

  val all: List[ConfigKey] = List(
    SchemaVersionKey,
    ModelId,
    ModelVersion,
    ModelDimension,
    VoyageApiKeyEnvVar,
    QueueName,
    DeadLetterName,
    MaxAttempts,
    MaxDocumentsPerJob,
    TestVectorIndex,
    ClaimsTableName,
  )

/** Validated embedding settings.
  *   - `modelId`, `modelVersion`: tokens (non-blank, no whitespace). `dimension` in
  *     `1..VectorRecord.MaxDimension`.
  *   - `voyageApiKeyEnvVar`: an environment-variable NAME (`[A-Z][A-Z0-9_]*`), never a credential.
  *   - `queueName`, `deadLetterName`: SQS-style names (1 to 80 of letters, digits, `_`, `-`),
  *     distinct. `maxAttempts` in 1..`EmbeddingSettings.MaxAttemptsCap` (10): deliveries before a
  *     job is dead-lettered. `maxDocumentsPerJob` in 1..`EmbeddingJob.MaxDocuments`.
  *   - `testVectorIndex`: 3 to 63 lowercase letters, digits or `-`; never one of the five
  *     production index names (`VectorIndexes.all`). Use `EmbeddingSettings.checkTestIndex` to also
  *     check it against the configured storage index names.
  *   - `claimsTableName`: DynamoDB table name (3 to 255 of letters, digits, `_`, `.`, `-`; the rule
  *     `StorageConfigLoader` applies to its tables) holding vector-key claims written with
  *     conditional puts. Default `scholarchain-embedding-key-claims`.
  */
final case class EmbeddingSettings(
    schemaVersion: Int,
    modelId: String,
    modelVersion: String,
    dimension: Int,
    voyageApiKeyEnvVar: String,
    queueName: String,
    deadLetterName: String,
    maxAttempts: Int,
    maxDocumentsPerJob: Int,
    testVectorIndex: String,
    claimsTableName: String = EmbeddingSettings.DefaultClaimsTableName,
)

object EmbeddingSettings:
  val MaxAttemptsCap: Int = 10

  val DefaultClaimsTableName: String = "scholarchain-embedding-key-claims"

  /** Fails when the test index equals any index name configured for storage (or a production name).
    */
  def checkTestIndex(
      testIndex: String,
      vectors: StorageVectors,
  ): Either[ValidationError, String] =
    if (VectorIndexes.all ++ vectors.indexes).contains(testIndex) then
      Left(InvalidConfig(EmbeddingConfigKeys.TestVectorIndex.key, "must not be a production index"))
    else Right(testIndex)

/** The resolved embedding settings plus the origin of each key (never the values). */
final case class ResolvedEmbeddingSettings(
    settings: EmbeddingSettings,
    origins: Map[String, String],
)

object EmbeddingConfigLoader:
  import EmbeddingConfigKeys.*

  private val EnvName   = """^[A-Z][A-Z0-9_]{0,127}$""".r
  private val QueueRe   = """^[A-Za-z0-9_\-]{1,80}$""".r
  private val IndexName = """^[a-z0-9\-]{3,63}$""".r
  // Same rule as StorageConfigLoader's DynamoDB table-name check.
  private val TableName = """^[A-Za-z0-9_.\-]{3,255}$""".r

  def load(
      env: Map[String, String] = sys.env,
      overrides: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedEmbeddingSettings] =
    ModuleConfig.load(all, Resource, env, overrides).flatMap(build)

  /** Pure resolution over explicit layers (no classpath access). */
  def resolve(
      env: Map[String, String],
      overrides: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], ResolvedEmbeddingSettings] =
    // The claims key is additive: an explicit file layer that predates it gets the default.
    val withDefault =
      if file.contains(ClaimsTableName.key) then file
      else file.updated(ClaimsTableName.key, EmbeddingSettings.DefaultClaimsTableName)
    ModuleConfig.resolve(all, env, overrides, withDefault).flatMap(build)

  private def bad(k: ConfigKey, why: String) = Left(InvalidConfig(k.key, why))

  private def token(k: ConfigKey, v: String): Either[ValidationError, String] =
    if v.nonEmpty && !v.exists(_.isWhitespace) then Right(v)
    else bad(k, "must be non-blank and contain no whitespace")

  private def bounded(k: ConfigKey, v: String, min: Int, max: Int): Either[ValidationError, Int] =
    ModuleConfig
      .int(k, v, min)
      .flatMap(n => if n <= max then Right(n) else bad(k, s"must be <= $max"))

  private def build(
      raw: ModuleConfig.Raw
  ): Either[List[ValidationError], ResolvedEmbeddingSettings] =
    def v(k: ConfigKey): String = raw.values(k.key)

    val sv = v(SchemaVersionKey).toIntOption
      .toRight(InvalidConfig(SchemaVersionKey.key, "must be an integer"))
      .flatMap(
        SchemaVersion
          .check(_)
          .left
          .map(_ => InvalidConfig(SchemaVersionKey.key, "unsupported schema version"))
      )
    val mid    = token(ModelId, v(ModelId))
    val mv     = token(ModelVersion, v(ModelVersion))
    val dim    = bounded(ModelDimension, v(ModelDimension), 1, VectorRecord.MaxDimension)
    val envVar =
      if EnvName.matches(v(VoyageApiKeyEnvVar)) then Right(v(VoyageApiKeyEnvVar))
      else bad(VoyageApiKeyEnvVar, "must be an environment variable name, not a value")
    val q =
      if QueueRe.matches(v(QueueName)) then Right(v(QueueName))
      else bad(QueueName, "must be 1 to 80 letters, digits, '_' or '-'")
    val dl =
      if QueueRe.matches(v(DeadLetterName)) then Right(v(DeadLetterName))
      else bad(DeadLetterName, "must be 1 to 80 letters, digits, '_' or '-'")
    val distinctQ =
      if v(QueueName) != v(DeadLetterName) then Right(())
      else bad(DeadLetterName, "must differ from embedding.queue.name")
    val att  = bounded(MaxAttempts, v(MaxAttempts), 1, EmbeddingSettings.MaxAttemptsCap)
    val docs = bounded(MaxDocumentsPerJob, v(MaxDocumentsPerJob), 1, EmbeddingJob.MaxDocuments)
    val ti   =
      if !IndexName.matches(v(TestVectorIndex)) then
        bad(TestVectorIndex, "must be 3 to 63 lowercase letters, digits or '-'")
      else if VectorIndexes.all.contains(v(TestVectorIndex)) then
        bad(TestVectorIndex, "must not be a production index")
      else Right(v(TestVectorIndex))
    val ct =
      if TableName.matches(v(ClaimsTableName)) then Right(v(ClaimsTableName))
      else bad(ClaimsTableName, "must be a valid DynamoDB table name")

    val errors =
      List(sv, mid, mv, dim, envVar, q, dl, distinctQ, att, docs, ti, ct).collect { case Left(e) =>
        e
      }
    if errors.nonEmpty then Left(errors.distinct)
    else
      (for
        a <- sv
        b <- mid
        c <- mv
        d <- dim
        e <- envVar
        f <- q
        g <- dl
        h <- att
        i <- docs
        j <- ti
        k <- ct
      yield ResolvedEmbeddingSettings(
        EmbeddingSettings(a, b, c, d, e, f, g, h, i, j, k),
        raw.origins,
      )).left.map(List(_))
