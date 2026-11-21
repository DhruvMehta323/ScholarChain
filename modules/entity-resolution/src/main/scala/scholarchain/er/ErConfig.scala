package scholarchain.er

import java.time.Instant

import scala.util.Try

import scholarchain.core.*
import scholarchain.core.ValidationError.InvalidConfig
import scholarchain.core.WirePickle.{*, given}

/** Thresholds of one entity kind. Invariant:
  * `0 <= reject <= llmBandLow <= llmBandHigh <= accept <= 1`.
  */
final case class Thresholds(accept: Double, reject: Double, llmBandLow: Double, llmBandHigh: Double)
    derives ReadWriter

object Thresholds:
  def validate(name: String, t: Thresholds): Either[ValidationError, Thresholds] =
    val ok = t.reject >= 0.0 && t.reject <= t.llmBandLow && t.llmBandLow <= t.llmBandHigh &&
      t.llmBandHigh <= t.accept && t.accept <= 1.0
    Either.cond(
      ok,
      t,
      InvalidConfig(name, "must satisfy 0 <= reject <= band-low <= band-high <= accept <= 1"),
    )

final case class LlmConfig(
    provider: String,
    model: String,
    priceInputUsdPerMTok: Double,
    priceOutputUsdPerMTok: Double,
    maxCalls: Int,
    maxAttempts: Int,
    maxOutputTokens: Int,
    maxCostUsd: Double,
    timeoutSeconds: Int,
    minConfidence: Double,
    cachePath: String,
)

final case class ErConfig(
    policyVersion: String,
    featureSetVersion: String,
    decisionEpoch: String,
    maxBlockSize: Int,
    researcher: Thresholds,
    work: Thresholds,
    llm: LlmConfig,
):
  def thresholds(kind: EntityKind): Thresholds = kind match
    case EntityKind.Researcher => researcher
    case EntityKind.Work       => work

  def withThresholds(r: Thresholds, w: Thresholds): ErConfig = copy(researcher = r, work = w)

object ErConfigKeys:
  val Resource = "scholarchain-er.v1.conf"

  val SchemaVersionKey = ConfigKey("er.schema-version")
  val PolicyVersion    = ConfigKey("er.policy-version")
  val FeatureSet       = ConfigKey("er.feature-set-version")
  val DecisionEpoch    = ConfigKey("er.decision-epoch")
  val MaxBlockSize     = ConfigKey("er.max-block-size")

  def thresholdKeys(kind: String): List[ConfigKey] =
    List("accept", "reject", "llm-band-low", "llm-band-high").map(n =>
      ConfigKey(s"er.$kind.threshold.$n")
    )

  val Provider        = ConfigKey("er.llm.provider")
  val Model           = ConfigKey("er.llm.model")
  val PriceIn         = ConfigKey("er.llm.price-input-usd-per-mtok")
  val PriceOut        = ConfigKey("er.llm.price-output-usd-per-mtok")
  val MaxCalls        = ConfigKey("er.llm.max-calls")
  val MaxAttempts     = ConfigKey("er.llm.max-attempts")
  val MaxOutputTokens = ConfigKey("er.llm.max-output-tokens")
  val MaxCostUsd      = ConfigKey("er.llm.max-cost-usd")
  val TimeoutSeconds  = ConfigKey("er.llm.timeout-seconds")
  val MinConfidence   = ConfigKey("er.llm.min-confidence")
  val CachePath       = ConfigKey("er.llm.cache-path")

  val all: List[ConfigKey] =
    List(SchemaVersionKey, PolicyVersion, FeatureSet, DecisionEpoch, MaxBlockSize) ++
      thresholdKeys("researcher") ++ thresholdKeys("work") ++
      List(
        Provider,
        Model,
        PriceIn,
        PriceOut,
        MaxCalls,
        MaxAttempts,
        MaxOutputTokens,
        MaxCostUsd,
        TimeoutSeconds,
        MinConfidence,
        CachePath,
      )

object ErConfigLoader:
  import ErConfigKeys.*

  /** Loads the versioned defaults from the classpath and applies env and overrides. */
  def load(
      env: Map[String, String] = sys.env,
      overrides: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ErConfig] =
    ModuleConfig.load(all, Resource, env, overrides).flatMap(build)

  /** Pure resolution over explicit layers (no classpath access). */
  def resolve(
      env: Map[String, String],
      overrides: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], ErConfig] =
    ModuleConfig.resolve(all, env, overrides, file).flatMap(build)

  private def build(raw: ModuleConfig.Raw): Either[List[ValidationError], ErConfig] =
    def v(k: ConfigKey): String = raw.values(k.key)
    def dbl(k: ConfigKey, lo: Double, hi: Double): Either[ValidationError, Double] =
      v(k).toDoubleOption
        .filter(d => !d.isNaN && d >= lo && d <= hi)
        .toRight(InvalidConfig(k.key, s"must be a number in [$lo, $hi]"))
    def int(k: ConfigKey, min: Int): Either[ValidationError, Int] =
      v(k).toIntOption.filter(_ >= min).toRight(InvalidConfig(k.key, s"must be an integer >= $min"))
    def thr(kind: String): Either[ValidationError, Thresholds] =
      val List(a, r, lo, hi) = thresholdKeys(kind): @unchecked
      for
        accept <- dbl(a, 0, 1)
        reject <- dbl(r, 0, 1)
        low    <- dbl(lo, 0, 1)
        high   <- dbl(hi, 0, 1)
        t      <- Thresholds.validate(s"er.$kind.threshold", Thresholds(accept, reject, low, high))
      yield t
    def token(k: ConfigKey): Either[ValidationError, String] =
      Either.cond(
        v(k).forall(c => !c.isWhitespace),
        v(k),
        InvalidConfig(k.key, "must contain no whitespace"),
      )

    val parts = for
      sv <- int(SchemaVersionKey, 1).flatMap(i =>
        SchemaVersion
          .check(i)
          .left
          .map(_ => InvalidConfig(SchemaVersionKey.key, "unsupported schema version"))
      )
      pv    <- token(PolicyVersion)
      fs    <- token(FeatureSet)
      epoch <- Either.cond(
        Try(Instant.parse(v(DecisionEpoch))).isSuccess,
        v(DecisionEpoch),
        InvalidConfig(DecisionEpoch.key, "must be an ISO-8601 instant"),
      )
      mbs  <- int(MaxBlockSize, 2)
      rt   <- thr("researcher")
      wt   <- thr("work")
      prov <- Either.cond(
        Set("anthropic", "bedrock").contains(v(Provider)),
        v(Provider),
        InvalidConfig(Provider.key, "must be one of anthropic, bedrock"),
      )
      model <- token(Model)
      pin   <- dbl(PriceIn, 0, 1e6)
      pout  <- dbl(PriceOut, 0, 1e6)
      mc    <- int(MaxCalls, 0)
      ma    <- int(MaxAttempts, 1)
      mo    <- int(MaxOutputTokens, 16)
      mcost <- dbl(MaxCostUsd, 0, 1e6)
      to    <- int(TimeoutSeconds, 1)
      minc  <- dbl(MinConfidence, 0, 1)
    yield ErConfig(
      pv,
      fs,
      epoch,
      mbs,
      rt,
      wt,
      LlmConfig(prov, model, pin, pout, mc, ma, mo, mcost, to, minc, v(CachePath)),
    )
    parts.left.map(List(_))

/** The calibrated policy written by `Calibrate` (`evaluation/er-v1-frozen-policy.json`). It carries
  * the thresholds, the `llm.min-confidence` in force and the `SCHOLARCHAIN_ER_*` environment at
  * calibration time; the scoring function and weights are part of the policy version.
  */
final case class FrozenPolicy(
    schemaVersion: Int,
    policyVersion: String,
    featureSetVersion: String,
    thresholdsOrigin: String,
    researcher: Thresholds,
    work: Thresholds,
    objective: String,
    grid: String,
    calibrationMetrics: Map[String, Map[String, Double]],
    inputs: List[InputRef],
    codeVersion: String,
    parameters: Map[String, String],
    llmMinConfidence: Option[Double] = None,
    erEnv: Option[Map[String, String]] = None,
    llmModel: Option[String] = None,
) derives ReadWriter

object FrozenPolicy:
  /** Environment variables that can change the configuration (`SCHOLARCHAIN_ER_*`). */
  val EnvPrefix = "SCHOLARCHAIN_ER_"

  def erEnv(env: Map[String, String]): Map[String, String] =
    env.filter((k, _) => k.startsWith(EnvPrefix))

  /** The checksum of the gold split the policy was calibrated on (input `er-gold-split`). */
  def splitChecksum(p: FrozenPolicy): Option[String] =
    p.inputs.find(_.inputId == "er-gold-split").flatMap(_.checksum)

  /** Names (never values) of the `SCHOLARCHAIN_ER_*` variables that differ from the frozen set.
    * Empty when the policy froze no environment (an older file).
    */
  def envDrift(p: FrozenPolicy, env: Map[String, String]): List[String] =
    p.erEnv.fold(List.empty[String]) { frozen =>
      val now = erEnv(env)
      (frozen.keySet ++ now.keySet).toList.filter(k => frozen.get(k) != now.get(k)).sorted
    }

  /** Config key of the LLM model, as written in `--override`. */
  val ModelOverrideKey = "scholarchain.er.llm.model"

  /** True when an `--override` sets the LLM model to something other than the frozen model. A
    * policy that froze no model (an older file) never differs. Callers report the key only.
    */
  def modelOverrideDiffers(p: FrozenPolicy, overrides: Map[String, String]): Boolean =
    (for
      frozen <- p.llmModel
      given_ <- overrides.get(ModelOverrideKey)
    yield frozen != given_).getOrElse(false)

  def read(json: String): Either[ValidationError, FrozenPolicy] =
    Try(WirePickle.read[FrozenPolicy](json)).toEither.left
      .map(_ => ValidationError.Malformed("frozen policy is not valid JSON"))
      .flatMap(p =>
        for
          _ <- SchemaVersion.check(p.schemaVersion)
          _ <- Thresholds.validate("researcher", p.researcher)
          _ <- Thresholds.validate("work", p.work)
        yield p
      )

  /** The configuration with the frozen thresholds applied. The policy version must match. */
  def applyTo(cfg: ErConfig, p: FrozenPolicy): Either[ValidationError, ErConfig] =
    if p.policyVersion != cfg.policyVersion then
      Left(InvalidConfig("er.policy-version", "does not match the frozen policy"))
    else
      val withThr = cfg.withThresholds(p.researcher, p.work)
      Right(
        p.llmMinConfidence.fold(withThr)(m =>
          withThr.copy(llm = withThr.llm.copy(minConfidence = m))
        )
      )
