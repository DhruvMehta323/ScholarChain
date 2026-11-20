package scholarchain.core

import java.util.Locale

import ValidationError.*

/** Configuration contract (schema version 1). It holds no values: every key has no default, and no
  * bucket name, endpoint, threshold, model ID, account ID, graph window or bridge parameter is
  * written in code. Values arrive from two layers only.
  *
  * Precedence, highest first:
  *   1. environment variable `SCHOLARCHAIN_<KEY>` where `<KEY>` is the key upper-cased with `.` and
  *      `-` replaced by `_` (for example `graph.window.start-year` becomes
  *      `SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR`). A blank environment value counts as unset.
  *   2. the versioned configuration file layer (flat dotted keys, see `ConfigKeys`), supplied as a
  *      `Map` by the file loader.
  * A required key found in neither layer is an error. Secrets are never configuration values: they
  * come from AWS Secrets Manager at runtime.
  */
final case class ConfigKey(key: String, required: Boolean = true):
  def envName: String =
    ConfigResolver.EnvPrefix + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_')

object ConfigKeys:
  val SchemaVersionKey      = ConfigKey("schema-version")
  val Environment           = ConfigKey("environment")
  val AwsRegion             = ConfigKey("aws.region")
  val RawBucket             = ConfigKey("storage.raw-bucket")
  val CuratedBucket         = ConfigKey("storage.curated-bucket")
  val VectorBucket          = ConfigKey("storage.vector-bucket")
  val VectorIndex           = ConfigKey("storage.vector-index")
  val DynamoTablePrefix     = ConfigKey("storage.dynamo-table-prefix")
  val NeptuneEndpoint       = ConfigKey("storage.neptune-endpoint")
  val OpenSearchEndpoint    = ConfigKey("storage.opensearch-endpoint", required = false)
  val EmbeddingModelId      = ConfigKey("models.embedding-model-id")
  val GenerationModelId     = ConfigKey("models.generation-model-id")
  val WindowStartYear       = ConfigKey("graph.window.start-year")
  val WindowEndYear         = ConfigKey("graph.window.end-year")
  val CoauthorPolicyVersion = ConfigKey("graph.coauthor-policy-version")
  val BridgeAlgorithm       = ConfigKey("graph.bridge.algorithm")
  val BridgeParameters      = ConfigKey("graph.bridge.parameters")
  val ErPolicyVersion       = ConfigKey("entity-resolution.policy-version")
  val ErMatchThreshold      = ConfigKey("entity-resolution.match-threshold")
  val ErReviewThreshold     = ConfigKey("entity-resolution.review-threshold")
  val RetrievalTopK         = ConfigKey("retrieval.top-k")

  val all: List[ConfigKey] = List(
    SchemaVersionKey,
    Environment,
    AwsRegion,
    RawBucket,
    CuratedBucket,
    VectorBucket,
    VectorIndex,
    DynamoTablePrefix,
    NeptuneEndpoint,
    OpenSearchEndpoint,
    EmbeddingModelId,
    GenerationModelId,
    WindowStartYear,
    WindowEndYear,
    CoauthorPolicyVersion,
    BridgeAlgorithm,
    BridgeParameters,
    ErPolicyVersion,
    ErMatchThreshold,
    ErReviewThreshold,
    RetrievalTopK,
  )

final case class StorageConfig(
    rawBucket: String,
    curatedBucket: String,
    vectorBucket: String,
    vectorIndex: String,
    dynamoTablePrefix: String,
    neptuneEndpoint: String,
    openSearchEndpoint: Option[String],
)
final case class ModelConfig(embeddingModelId: String, generationModelId: String)
final case class BridgeConfig(algorithm: String, parameters: Map[String, String])
final case class GraphConfig(
    window: GraphWindow,
    coauthorPolicyVersion: String,
    bridge: BridgeConfig,
)
final case class EntityResolutionConfig(
    policyVersion: String,
    matchThreshold: Double,
    reviewThreshold: Double,
)
final case class RetrievalConfig(topK: Int)

final case class ScholarChainConfig(
    schemaVersion: Int,
    environment: String,
    awsRegion: String,
    storage: StorageConfig,
    models: ModelConfig,
    graph: GraphConfig,
    entityResolution: EntityResolutionConfig,
    retrieval: RetrievalConfig,
)

/** The resolved configuration plus the origin of each key (`env:<NAME>` or `file`), never the
  * values, so a resolution can be recorded as provenance without leaking configuration.
  */
final case class ResolvedConfig(config: ScholarChainConfig, origins: Map[String, String])

object ConfigResolver:
  val EnvPrefix: String = "SCHOLARCHAIN_"

  /** Resolves and validates. All missing required keys are reported together; beyond that, the
    * first error of each section is reported. Pure and total: the caller supplies both layers.
    */
  def resolve(
      env: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], ResolvedConfig] =
    val found: Map[String, (String, String)] = ConfigKeys.all.flatMap { k =>
      env
        .get(k.envName)
        .filter(_.trim.nonEmpty)
        .map(v => k.key -> (v.trim, s"env:${k.envName}"))
        .orElse(file.get(k.key).filter(_.trim.nonEmpty).map(v => k.key -> (v.trim, "file")))
    }.toMap

    val missing = ConfigKeys.all.filter(k => k.required && !found.contains(k.key))
    if missing.nonEmpty then
      Left(
        missing.map(k =>
          InvalidConfig(k.key, s"required value missing (set ${k.envName} or the file key)")
        )
      )
    else
      def get(k: ConfigKey): String = found(k.key)._1
      val results                   = List(
        schemaVersion(get(ConfigKeys.SchemaVersionKey)).map(_ => ()),
        token(ConfigKeys.Environment, get(ConfigKeys.Environment)).map(_ => ()),
        token(ConfigKeys.AwsRegion, get(ConfigKeys.AwsRegion)).map(_ => ()),
        storage(found).map(_ => ()),
        models(found).map(_ => ()),
        graph(found).map(_ => ()),
        er(found).map(_ => ()),
        retrieval(found).map(_ => ()),
      )
      val errors = results.collect { case Left(e) => e }
      if errors.nonEmpty then Left(errors)
      else
        (for
          sv <- schemaVersion(get(ConfigKeys.SchemaVersionKey))
          st <- storage(found)
          mo <- models(found)
          gr <- graph(found)
          e  <- er(found)
          re <- retrieval(found)
        yield ResolvedConfig(
          ScholarChainConfig(
            sv,
            get(ConfigKeys.Environment),
            get(ConfigKeys.AwsRegion),
            st,
            mo,
            gr,
            e,
            re,
          ),
          found.map((k, v) => k -> v._2),
        )).left.map(List(_))

  private type Found = Map[String, (String, String)]

  private def raw(f: Found, k: ConfigKey): String = f(k.key)._1

  private def token(k: ConfigKey, v: String): Either[ValidationError, String] =
    if v.nonEmpty && !v.exists(_.isWhitespace) then Right(v)
    else Left(InvalidConfig(k.key, "must be non-blank and contain no whitespace"))

  private def schemaVersion(v: String): Either[ValidationError, Int] =
    v.toIntOption
      .toRight(InvalidConfig(ConfigKeys.SchemaVersionKey.key, "must be an integer"))
      .flatMap(SchemaVersion.check)

  private def bucket(k: ConfigKey, v: String): Either[ValidationError, String] =
    token(k, v).flatMap(b =>
      if b.contains("/") || b.toLowerCase(Locale.ROOT).startsWith("s3:") then
        Left(InvalidConfig(k.key, "must be a bare bucket name, not a path or URI"))
      else Right(b)
    )

  private def int(k: ConfigKey, v: String): Either[ValidationError, Int] =
    v.toIntOption.toRight(InvalidConfig(k.key, "must be an integer"))

  private def unitInterval(k: ConfigKey, v: String): Either[ValidationError, Double] =
    v.toDoubleOption
      .filter(d => !d.isNaN && d >= 0.0 && d <= 1.0)
      .toRight(InvalidConfig(k.key, "must be a number in [0,1]"))

  private def storage(f: Found): Either[ValidationError, StorageConfig] =
    for
      raw0 <- bucket(ConfigKeys.RawBucket, raw(f, ConfigKeys.RawBucket))
      cur  <- bucket(ConfigKeys.CuratedBucket, raw(f, ConfigKeys.CuratedBucket))
      vb   <- bucket(ConfigKeys.VectorBucket, raw(f, ConfigKeys.VectorBucket))
      vi   <- token(ConfigKeys.VectorIndex, raw(f, ConfigKeys.VectorIndex))
      dp   <- token(ConfigKeys.DynamoTablePrefix, raw(f, ConfigKeys.DynamoTablePrefix))
      ne   <- token(ConfigKeys.NeptuneEndpoint, raw(f, ConfigKeys.NeptuneEndpoint))
      os   <- f.get(ConfigKeys.OpenSearchEndpoint.key) match
        case None         => Right(None)
        case Some((v, _)) => token(ConfigKeys.OpenSearchEndpoint, v).map(Some(_))
    yield StorageConfig(raw0, cur, vb, vi, dp, ne, os)

  private def models(f: Found): Either[ValidationError, ModelConfig] =
    for
      e <- token(ConfigKeys.EmbeddingModelId, raw(f, ConfigKeys.EmbeddingModelId))
      g <- token(ConfigKeys.GenerationModelId, raw(f, ConfigKeys.GenerationModelId))
    yield ModelConfig(e, g)

  /** `graph.bridge.parameters` is a JSON object whose values are strings, numbers or booleans. */
  private def bridgeParams(v: String): Either[ValidationError, Map[String, String]] =
    val bad = InvalidConfig(
      ConfigKeys.BridgeParameters.key,
      "must be a JSON object with string, number or boolean values",
    )
    scala.util.Try(ujson.read(v)).toOption.flatMap(_.objOpt) match
      case None      => Left(bad)
      case Some(obj) =>
        val entries = obj.value.toList.map { (k, j) =>
          j match
            case ujson.Str(s)  => Some(k -> s)
            case ujson.Num(_)  => Some(k -> ujson.write(j))
            case ujson.Bool(b) => Some(k -> b.toString)
            case _             => None
        }
        if entries.forall(_.isDefined) then Right(entries.flatten.toMap) else Left(bad)

  private def graph(f: Found): Either[ValidationError, GraphConfig] =
    for
      s <- int(ConfigKeys.WindowStartYear, raw(f, ConfigKeys.WindowStartYear))
      e <- int(ConfigKeys.WindowEndYear, raw(f, ConfigKeys.WindowEndYear))
      w <- GraphWindow.validate(GraphWindow(s, e))
      p <- token(ConfigKeys.CoauthorPolicyVersion, raw(f, ConfigKeys.CoauthorPolicyVersion))
      a <- token(ConfigKeys.BridgeAlgorithm, raw(f, ConfigKeys.BridgeAlgorithm))
      b <- bridgeParams(raw(f, ConfigKeys.BridgeParameters))
    yield GraphConfig(w, p, BridgeConfig(a, b))

  private def er(f: Found): Either[ValidationError, EntityResolutionConfig] =
    for
      p <- token(ConfigKeys.ErPolicyVersion, raw(f, ConfigKeys.ErPolicyVersion))
      m <- unitInterval(ConfigKeys.ErMatchThreshold, raw(f, ConfigKeys.ErMatchThreshold))
      r <- unitInterval(ConfigKeys.ErReviewThreshold, raw(f, ConfigKeys.ErReviewThreshold))
      _ <-
        if r <= m then Right(())
        else
          Left(
            InvalidConfig(ConfigKeys.ErReviewThreshold.key, "must not exceed the match threshold")
          )
    yield EntityResolutionConfig(p, m, r)

  private def retrieval(f: Found): Either[ValidationError, RetrievalConfig] =
    int(ConfigKeys.RetrievalTopK, raw(f, ConfigKeys.RetrievalTopK)).flatMap(k =>
      if k >= 1 then Right(RetrievalConfig(k))
      else Left(InvalidConfig(ConfigKeys.RetrievalTopK.key, "must be >= 1"))
    )
