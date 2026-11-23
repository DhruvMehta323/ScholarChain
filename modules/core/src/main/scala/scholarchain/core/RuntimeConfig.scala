package scholarchain.core

import ValidationError.*

/** RAG runtime configuration keys, all under `rag.` (additive; frozen `ConfigKeys` and the other
  * module keys are untouched). Defaults live in `scholarchain-rag.v1.conf`. Environment variable:
  * `SCHOLARCHAIN_RAG_<KEY>`; override name: `scholarchain.rag.<key>`.
  *
  * No secret is ever a config value. `rag.model.anthropic-api-key-env-var` holds only the NAME of
  * the environment variable the Anthropic adapter reads its credential from (default
  * `SCHOLARCHAIN_ANTHROPIC_API_KEY`); the credential is read by the adapter at runtime and never
  * logged. Bedrock uses the default AWS credential chain, not a configured value.
  */
object RuntimeConfigKeys:
  val Resource = "scholarchain-rag.v1.conf"

  val SchemaVersionKey = ConfigKey("rag.schema-version")

  val Provider              = ConfigKey("rag.model.provider")
  val BedrockModelId        = ConfigKey("rag.model.bedrock-model-id")
  val AnthropicModelId      = ConfigKey("rag.model.anthropic-model-id")
  val AnthropicEndpoint     = ConfigKey("rag.model.anthropic-endpoint")
  val AnthropicApiKeyEnvVar = ConfigKey("rag.model.anthropic-api-key-env-var")
  val MaxMalformedRetries   = ConfigKey("rag.model.max-malformed-retries")

  val ContextCharacters = ConfigKey("rag.budget.context-characters")
  val InputTokens       = ConfigKey("rag.budget.input-tokens")
  val OutputTokens      = ConfigKey("rag.budget.output-tokens")

  val ModelTimeoutMillis   = ConfigKey("rag.timeout.model-millis")
  val RequestTimeoutMillis = ConfigKey("rag.timeout.request-millis")

  val GraphMaxDepth      = ConfigKey("rag.graph.max-depth")
  val GraphMaxResults    = ConfigKey("rag.graph.max-results")
  val GraphTimeoutMillis = ConfigKey("rag.graph.timeout-millis")

  val EvidenceMaxItems = ConfigKey("rag.evidence.max-items")

  val all: List[ConfigKey] = List(
    SchemaVersionKey,
    Provider,
    BedrockModelId,
    AnthropicModelId,
    AnthropicEndpoint,
    AnthropicApiKeyEnvVar,
    MaxMalformedRetries,
    ContextCharacters,
    InputTokens,
    OutputTokens,
    ModelTimeoutMillis,
    RequestTimeoutMillis,
    GraphMaxDepth,
    GraphMaxResults,
    GraphTimeoutMillis,
    EvidenceMaxItems,
  )

/** Validated runtime settings.
  *   - `provider`: `bedrock`, `anthropic` or `fake`.
  *   - `bedrockModelId`, `anthropicModelId`: tokens (non-blank, no whitespace, at most 200).
  *   - `anthropicEndpoint`: an `https://` URL of at most 200 characters, no whitespace, no `@` (no
  *     embedded credentials), no `?` or `#`.
  *   - `anthropicApiKeyEnvVar`: an environment-variable NAME (`[A-Z][A-Z0-9_]*`), never a value.
  *   - `maxMalformedRetries` in 0..`ModelCall.MaxRetries` (2).
  *   - `contextCharacters` in 1..`MaxContextCharacters` (400,000); `inputTokens` in
  *     1..`MaxInputTokens` (200,000); `outputTokens` in 1..`ModelRequest.MaxOutputTokensCap`.
  *   - `modelTimeoutMillis`, `requestTimeoutMillis` in 1..`MaxTimeoutMillis` (600,000);
  *     `graphTimeoutMillis` in the same range; the model timeout may not exceed the request
  *     timeout.
  *   - `graphMaxDepth` in 1..`QueryLimits.MaxPathDepth`; `graphMaxResults` in
  *     1..`QueryLimits.MaxResultCount`.
  *   - `evidenceMaxItems` in 1..`MaxEvidenceItems` (500).
  */
final case class RuntimeSettings(
    schemaVersion: Int,
    provider: ModelProvider,
    bedrockModelId: String,
    anthropicModelId: String,
    anthropicEndpoint: String,
    anthropicApiKeyEnvVar: String,
    maxMalformedRetries: Int,
    contextCharacters: Int,
    inputTokens: Int,
    outputTokens: Int,
    modelTimeoutMillis: Int,
    requestTimeoutMillis: Int,
    graphMaxDepth: Int,
    graphMaxResults: Int,
    graphTimeoutMillis: Int,
    evidenceMaxItems: Int,
):
  /** The graph bounds every RAG graph query carries. */
  def graphBounds: QueryBounds =
    QueryBounds(graphMaxDepth, graphMaxResults, graphTimeoutMillis.toLong)

object RuntimeSettings:
  val MaxContextCharacters: Int = 400000
  val MaxInputTokens: Int       = 200000
  val MaxTimeoutMillis: Int     = 600000
  val MaxEvidenceItems: Int     = 500

/** The resolved settings plus the origin of each key (never the values). */
final case class ResolvedRuntimeSettings(settings: RuntimeSettings, origins: Map[String, String])

object RuntimeConfigLoader:
  import RuntimeConfigKeys.*

  private val EnvName = """^[A-Z][A-Z0-9_]{0,127}$""".r

  def load(
      env: Map[String, String] = sys.env,
      overrides: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedRuntimeSettings] =
    ModuleConfig.load(all, Resource, env, overrides).flatMap(build)

  /** Pure resolution over explicit layers (no classpath access). */
  def resolve(
      env: Map[String, String],
      overrides: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], ResolvedRuntimeSettings] =
    ModuleConfig.resolve(all, env, overrides, file).flatMap(build)

  private def bad(k: ConfigKey, why: String) = Left(InvalidConfig(k.key, why))

  private def token(k: ConfigKey, v: String): Either[ValidationError, String] =
    if v.nonEmpty && v.length <= 200 && !v.exists(c => c.isWhitespace || Character.isISOControl(c))
    then Right(v)
    else bad(k, "must be a token of at most 200 characters without whitespace")

  private def bounded(k: ConfigKey, v: String, min: Int, max: Int): Either[ValidationError, Int] =
    ModuleConfig
      .int(k, v, min)
      .flatMap(n => if n <= max then Right(n) else bad(k, s"must be <= $max"))

  private def endpoint(k: ConfigKey, v: String): Either[ValidationError, String] =
    if v.startsWith("https://") && v.length > "https://".length && v.length <= 200 &&
      !v.exists(c => c.isWhitespace || Character.isISOControl(c)) &&
      !v.contains('@') && !v.contains('?') && !v.contains('#')
    then Right(v)
    else bad(k, "must be an https URL without credentials, query or fragment")

  private def build(raw: ModuleConfig.Raw): Either[List[ValidationError], ResolvedRuntimeSettings] =
    def v(k: ConfigKey): String = raw.values(k.key)

    val sv = v(SchemaVersionKey).toIntOption
      .toRight(InvalidConfig(SchemaVersionKey.key, "must be an integer"))
      .flatMap(
        SchemaVersion
          .check(_)
          .left
          .map(_ => InvalidConfig(SchemaVersionKey.key, "unsupported schema version"))
      )
    val prov = ModelProvider
      .parse(v(Provider))
      .toRight(InvalidConfig(Provider.key, "must be one of bedrock, anthropic, fake"))
    val bm     = token(BedrockModelId, v(BedrockModelId))
    val am     = token(AnthropicModelId, v(AnthropicModelId))
    val ep     = endpoint(AnthropicEndpoint, v(AnthropicEndpoint))
    val keyVar =
      if EnvName.matches(v(AnthropicApiKeyEnvVar)) then Right(v(AnthropicApiKeyEnvVar))
      else bad(AnthropicApiKeyEnvVar, "must be an environment variable name, not a value")
    val mr = bounded(MaxMalformedRetries, v(MaxMalformedRetries), 0, ModelCall.MaxRetries)
    val cc =
      bounded(ContextCharacters, v(ContextCharacters), 1, RuntimeSettings.MaxContextCharacters)
    val it = bounded(InputTokens, v(InputTokens), 1, RuntimeSettings.MaxInputTokens)
    val ot = bounded(OutputTokens, v(OutputTokens), 1, ModelRequest.MaxOutputTokensCap)
    val mt =
      bounded(ModelTimeoutMillis, v(ModelTimeoutMillis), 1, RuntimeSettings.MaxTimeoutMillis)
    val rt =
      bounded(RequestTimeoutMillis, v(RequestTimeoutMillis), 1, RuntimeSettings.MaxTimeoutMillis)
    val order = (mt, rt) match
      case (Right(m), Right(r)) if m > r =>
        bad(ModelTimeoutMillis, s"must not exceed ${RequestTimeoutMillis.key}")
      case _ => Right(())
    val gd = bounded(GraphMaxDepth, v(GraphMaxDepth), 1, QueryLimits.MaxPathDepth)
    val gr = bounded(GraphMaxResults, v(GraphMaxResults), 1, QueryLimits.MaxResultCount)
    val gt =
      bounded(GraphTimeoutMillis, v(GraphTimeoutMillis), 1, RuntimeSettings.MaxTimeoutMillis)
    val ei = bounded(EvidenceMaxItems, v(EvidenceMaxItems), 1, RuntimeSettings.MaxEvidenceItems)

    val errors =
      List(sv, prov, bm, am, ep, keyVar, mr, cc, it, ot, mt, rt, order, gd, gr, gt, ei).collect {
        case Left(e) => e
      }
    if errors.nonEmpty then Left(errors.distinct)
    else
      (for
        a <- sv
        b <- prov
        c <- bm
        d <- am
        e <- ep
        f <- keyVar
        g <- mr
        h <- cc
        i <- it
        j <- ot
        k <- mt
        l <- rt
        m <- gd
        n <- gr
        o <- gt
        p <- ei
      yield ResolvedRuntimeSettings(
        RuntimeSettings(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p),
        raw.origins,
      )).left.map(List(_))
