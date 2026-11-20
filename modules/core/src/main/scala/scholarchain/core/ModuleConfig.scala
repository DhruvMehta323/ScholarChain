package scholarchain.core

import java.util.Locale

import ValidationError.*

/** Module-scoped configuration (new contract, schema 1). A module declares its own keys, under its
  * own prefix, with defaults in its own versioned HOCON resource. The 21 keys of `ConfigKeys` and
  * `ConfigResolver` are untouched.
  *
  * Naming for a key `hadoop.large-team-threshold`:
  *   - file key: `hadoop.large-team-threshold` (nested HOCON
  *     `hadoop { large-team-threshold = .. }`)
  *   - environment variable: `SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD` (same rule as `ConfigKey`)
  *   - override name (a Hadoop `-D` key or any caller-supplied map): `scholarchain.` + the key, for
  *     example `scholarchain.hadoop.large-team-threshold`.
  *
  * Precedence, highest first (the Phase 03 rule, with one explicit extra layer):
  *   1. environment variable (a blank value counts as unset)
  *   2. caller-supplied overrides, keyed by override name (for `-D` values); never read from the
  *      JVM system properties implicitly, because the Phase 03 loader ignores them
  *   3. the module's versioned file layer (the defaults)
  * A required key found nowhere is an error. Values are trimmed. Errors name the key, never a
  * value.
  */
object ModuleConfig:
  val OverridePrefix: String = "scholarchain."

  def overrideName(k: ConfigKey): String = OverridePrefix + k.key

  /** Resolved raw values and, per key, the origin (`env:<NAME>`, `override:<name>` or `file`). */
  final case class Raw(values: Map[String, String], origins: Map[String, String])

  def resolve(
      keys: List[ConfigKey],
      env: Map[String, String],
      overrides: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], Raw] =
    def present(m: Map[String, String], name: String): Option[String] =
      m.get(name).map(_.trim).filter(_.nonEmpty)
    val found: Map[String, (String, String)] = keys.flatMap { k =>
      present(env, k.envName)
        .map(v => k.key -> (v, s"env:${k.envName}"))
        .orElse(
          present(overrides, overrideName(k)).map(v => k.key -> (v, s"override:${overrideName(k)}"))
        )
        .orElse(present(file, k.key).map(v => k.key -> (v, "file")))
    }.toMap
    val missing = keys.filter(k => k.required && !found.contains(k.key))
    if missing.nonEmpty then
      Left(
        missing.map(k =>
          InvalidConfig(
            k.key,
            s"required value missing (set ${k.envName}, ${overrideName(k)} or the file key)",
          )
        )
      )
    else Right(Raw(found.map((k, v) => k -> v._1), found.map((k, v) => k -> v._2)))

  /** Loads the module's file layer from its versioned classpath resource and resolves it. */
  def load(
      keys: List[ConfigKey],
      resource: String,
      env: Map[String, String],
      overrides: Map[String, String],
  ): Either[List[ValidationError], Raw] =
    ConfigLoader.moduleFileLayer(resource, keys).flatMap(resolve(keys, env, overrides, _))

  private[core] def int(k: ConfigKey, v: String, min: Int): Either[ValidationError, Int] =
    v.toIntOption
      .toRight(InvalidConfig(k.key, "must be an integer"))
      .flatMap(i => if i >= min then Right(i) else Left(InvalidConfig(k.key, s"must be >= $min")))

/** Output compression codec of Hadoop jobs. Wire names are the config values. */
enum OutputCodec(val wire: String):
  case Uncompressed extends OutputCodec("none")
  case Gzip         extends OutputCodec("gzip")
  case Snappy       extends OutputCodec("snappy")

object OutputCodec:
  def parse(s: String): Option[OutputCodec] =
    values.find(_.wire == s.toLowerCase(Locale.ROOT))

/** Hadoop module configuration keys, all under `hadoop.`. Defaults live in
  * `scholarchain-hadoop.v1.conf`, not in code.
  */
object HadoopConfigKeys:
  val Resource = "scholarchain-hadoop.v1.conf"

  val SchemaVersionKey     = ConfigKey("hadoop.schema-version")
  val LargeTeamThreshold   = ConfigKey("hadoop.large-team-threshold")
  val PairExpansionHardCap = ConfigKey("hadoop.pair-expansion-hard-cap")
  val ReducersNormalize    = ConfigKey("hadoop.reducers.normalize")
  val ReducersTeams        = ConfigKey("hadoop.reducers.teams")
  val ReducersPairs        = ConfigKey("hadoop.reducers.pairs")
  val ReducersIdentifiers  = ConfigKey("hadoop.reducers.identifiers")
  val OutputCodecKey       = ConfigKey("hadoop.output-codec")

  val all: List[ConfigKey] = List(
    SchemaVersionKey,
    LargeTeamThreshold,
    PairExpansionHardCap,
    ReducersNormalize,
    ReducersTeams,
    ReducersPairs,
    ReducersIdentifiers,
    OutputCodecKey,
  )

final case class HadoopReducers(normalize: Int, teams: Int, pairs: Int, identifiers: Int)

/** Validated Hadoop settings.
  *   - `largeTeamThreshold` (>= 2, unit: AUTHORS): a team with more authors than this is flagged as
  *     a large team. A flag only; nothing is dropped.
  *   - `pairExpansionHardCap` (>= 1, unit: PAIRS, i.e. n*(n-1)/2 for n authors, NOT authors): the
  *     maximum number of pairs emitted for one work. Default 499500 = C(1000,2).
  */
final case class HadoopConfig(
    schemaVersion: Int,
    largeTeamThreshold: Int,
    pairExpansionHardCap: Int,
    reducers: HadoopReducers,
    outputCodec: OutputCodec,
)

/** The resolved Hadoop settings plus the origin of each key (never the values). */
final case class ResolvedHadoopConfig(config: HadoopConfig, origins: Map[String, String])

object HadoopConfigLoader:
  import HadoopConfigKeys.*

  /** Loads `scholarchain-hadoop.v1.conf` from the classpath and applies env and overrides. */
  def load(
      env: Map[String, String] = sys.env,
      overrides: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedHadoopConfig] =
    ModuleConfig.load(all, Resource, env, overrides).flatMap(build)

  /** Pure resolution over explicit layers (no classpath access). */
  def resolve(
      env: Map[String, String],
      overrides: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], ResolvedHadoopConfig] =
    ModuleConfig.resolve(all, env, overrides, file).flatMap(build)

  private def build(raw: ModuleConfig.Raw): Either[List[ValidationError], ResolvedHadoopConfig] =
    def v(k: ConfigKey): String = raw.values(k.key)
    val sv                      = v(SchemaVersionKey).toIntOption
      .toRight(InvalidConfig(SchemaVersionKey.key, "must be an integer"))
      .flatMap(
        SchemaVersion
          .check(_)
          .left
          .map(_ => InvalidConfig(SchemaVersionKey.key, "unsupported schema version"))
      )
    val thr   = ModuleConfig.int(LargeTeamThreshold, v(LargeTeamThreshold), 2)
    val cap   = ModuleConfig.int(PairExpansionHardCap, v(PairExpansionHardCap), 1)
    val rn    = ModuleConfig.int(ReducersNormalize, v(ReducersNormalize), 1)
    val rt    = ModuleConfig.int(ReducersTeams, v(ReducersTeams), 1)
    val rp    = ModuleConfig.int(ReducersPairs, v(ReducersPairs), 1)
    val ri    = ModuleConfig.int(ReducersIdentifiers, v(ReducersIdentifiers), 1)
    val codec = OutputCodec
      .parse(v(OutputCodecKey))
      .toRight(InvalidConfig(OutputCodecKey.key, "must be one of none, gzip, snappy"))
    val errors = List(sv, thr, cap, rn, rt, rp, ri, codec).collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors)
    else
      (for
        s <- sv
        t <- thr
        c <- cap
        a <- rn
        b <- rt
        d <- rp
        e <- ri
        o <- codec
      yield ResolvedHadoopConfig(
        HadoopConfig(s, t, c, HadoopReducers(a, b, d, e), o),
        raw.origins,
      )).left.map(List(_))
