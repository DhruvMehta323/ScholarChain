package scholarchain.core

import ValidationError.*

/** The all-time window convention (Phase 06). An all-time window is NOT a separate type or an
  * `Option`: it is the explicit `GraphWindow(PublicationYear.Min, PublicationYear.Max)`, that is
  * `1000-9999`, label `"1000-9999"`. It validates under `GraphWindow.validate`, it appears in every
  * record as an ordinary window, and no output ever has a missing or null window.
  */
object GraphWindows:
  val AllTime: GraphWindow = GraphWindow(PublicationYear.Min, PublicationYear.Max)

  def isAllTime(w: GraphWindow): Boolean = w == AllTime

  /** True when the inclusive year ranges share at least one year. */
  def overlaps(a: GraphWindow, b: GraphWindow): Boolean =
    a.startYear <= b.endYear && b.startYear <= a.endYear

/** Spark module configuration keys, all under `spark.`. Defaults live in
  * `scholarchain-spark.v1.conf`, not in code. Environment variable: `SCHOLARCHAIN_SPARK_<KEY>`;
  * override name: `scholarchain.spark.<key>`.
  */
object SparkConfigKeys:
  val Resource = "scholarchain-spark.v1.conf"

  val SchemaVersionKey      = ConfigKey("spark.schema-version")
  val Windows               = ConfigKey("spark.windows")
  val IncludeAllTime        = ConfigKey("spark.include-all-time")
  val BfsMaxDepth           = ConfigKey("spark.bfs-max-depth")
  val PathSamplePairs       = ConfigKey("spark.path-sample-pairs")
  val PathSampleSeed        = ConfigKey("spark.path-sample-seed")
  val AvgPathSourcesK       = ConfigKey("spark.avg-path-sources-k")
  val AvgPathSeed           = ConfigKey("spark.avg-path-seed")
  val LabelPropagationIters = ConfigKey("spark.label-propagation-max-iterations")
  val ComponentsIters       = ConfigKey("spark.connected-components-max-iterations")
  val KCoreIters            = ConfigKey("spark.k-core-max-iterations")
  val RepresentativeWorkIds = ConfigKey("spark.representative-work-ids-bound")
  val ShufflePartitions     = ConfigKey("spark.shuffle-partitions")
  val OutputPartitions      = ConfigKey("spark.output-partitions")
  val CheckpointDir         = ConfigKey("spark.checkpoint-dir")
  val OutputRoot            = ConfigKey("spark.output-root")
  val InputRootIDir         = ConfigKey("spark.input.root-i-dir")
  val InputEntityViewsDir   = ConfigKey("spark.input.hj6a-entity-views-dir")
  val InputMatchDecisions   = ConfigKey("spark.input.match-decisions-final-path")
  val PolicyVersion         = ConfigKey("spark.policy-version")
  val CodeVersion           = ConfigKey("spark.code-version")

  val all: List[ConfigKey] = List(
    SchemaVersionKey,
    Windows,
    IncludeAllTime,
    BfsMaxDepth,
    PathSamplePairs,
    PathSampleSeed,
    AvgPathSourcesK,
    AvgPathSeed,
    LabelPropagationIters,
    ComponentsIters,
    KCoreIters,
    RepresentativeWorkIds,
    ShufflePartitions,
    OutputPartitions,
    CheckpointDir,
    OutputRoot,
    InputRootIDir,
    InputEntityViewsDir,
    InputMatchDecisions,
    PolicyVersion,
    CodeVersion,
  )

/** Input locations (all non-blank). */
final case class SparkInputs(
    rootICuratedDir: String,
    hj6aEntityViewsDir: String,
    matchDecisionsFinalPath: String,
)

/** Validated Spark settings.
  *   - `explicitWindows`: the configured year windows, in configured order; non-empty, each valid,
  *     pairwise non-overlapping (duplicates are overlaps).
  *   - `includeAllTime`: when true `allWindows` also ends with `GraphWindows.AllTime`. An explicit
  *     window equal to `GraphWindows.AllTime` is rejected in that case (duplicate label).
  *   - `bfsMaxDepth`, `avgPathSourcesK`, the three iteration caps, `representativeWorkIdsBound`,
  *     `shufflePartitions`, `outputPartitions`: all > 0.
  *   - `pathSamplePairs` >= 0 (0 disables sampled path evidence); seeds are any Long.
  *   - `policyVersion` and `codeVersion` are tokens (non-blank, no whitespace).
  */
final case class SparkConfig(
    schemaVersion: Int,
    explicitWindows: List[GraphWindow],
    includeAllTime: Boolean,
    bfsMaxDepth: Int,
    pathSamplePairs: Int,
    pathSampleSeed: Long,
    avgPathSourcesK: Int,
    avgPathSeed: Long,
    labelPropagationMaxIterations: Int,
    connectedComponentsMaxIterations: Int,
    kCoreMaxIterations: Int,
    representativeWorkIdsBound: Int,
    shufflePartitions: Int,
    outputPartitions: Int,
    checkpointDir: String,
    outputRoot: String,
    inputs: SparkInputs,
    policyVersion: String,
    codeVersion: String,
):
  /** Explicit windows in configured order, then the all-time window when `includeAllTime`. */
  def allWindows: List[GraphWindow] =
    if includeAllTime then explicitWindows :+ GraphWindows.AllTime else explicitWindows

/** The resolved Spark settings plus the origin of each key (never the values). */
final case class ResolvedSparkConfig(config: SparkConfig, origins: Map[String, String])

object SparkConfigLoader:
  import SparkConfigKeys.*

  /** Loads `scholarchain-spark.v1.conf` from the classpath and applies env and overrides. */
  def load(
      env: Map[String, String] = sys.env,
      overrides: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedSparkConfig] =
    ModuleConfig.load(all, Resource, env, overrides).flatMap(build)

  /** Pure resolution over explicit layers (no classpath access). */
  def resolve(
      env: Map[String, String],
      overrides: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], ResolvedSparkConfig] =
    ModuleConfig.resolve(all, env, overrides, file).flatMap(build)

  /** Parses `"2000-2004,2005-2009"`: comma-separated `<start>-<end>`, whitespace around items
    * allowed. Errors name the key only, never the value.
    */
  def parseWindows(k: ConfigKey, s: String): Either[ValidationError, List[GraphWindow]] =
    val items = s.split(",", -1).toList.map(_.trim)
    if items.isEmpty || items.exists(_.isEmpty) then
      Left(InvalidConfig(k.key, "must be a non-empty comma-separated list of windows"))
    else
      items.foldLeft[Either[ValidationError, List[GraphWindow]]](Right(Nil)) { (acc, item) =>
        acc.flatMap { done =>
          item.split("-", -1).toList match
            case List(a, b) =>
              (a.toIntOption, b.toIntOption) match
                case (Some(x), Some(y)) =>
                  GraphWindow
                    .validate(GraphWindow(x, y))
                    .left
                    .map(_ =>
                      InvalidConfig(k.key, "contains an invalid window (start > end or year range)")
                    )
                    .map(done :+ _)
                case _ => Left(InvalidConfig(k.key, "windows must be <start>-<end> integers"))
            case _ => Left(InvalidConfig(k.key, "windows must be <start>-<end> integers"))
        }
      }

  private def windows(k: ConfigKey, s: String, allTime: Boolean) =
    parseWindows(k, s).flatMap { ws =>
      val labels = ws.map(GraphWindow.label)
      if labels.distinct.size != labels.size then
        Left(InvalidConfig(k.key, "must not contain duplicate windows"))
      else if ws.combinations(2).exists(p => GraphWindows.overlaps(p.head, p(1))) then
        Left(InvalidConfig(k.key, "must not contain overlapping windows"))
      else if allTime && ws.contains(GraphWindows.AllTime) then
        Left(InvalidConfig(k.key, "must not repeat the all-time window when include-all-time"))
      else Right(ws)
    }

  private def bool(k: ConfigKey, v: String): Either[ValidationError, Boolean] =
    v.toLowerCase(java.util.Locale.ROOT) match
      case "true"  => Right(true)
      case "false" => Right(false)
      case _       => Left(InvalidConfig(k.key, "must be true or false"))

  private def long(k: ConfigKey, v: String): Either[ValidationError, Long] =
    v.toLongOption.toRight(InvalidConfig(k.key, "must be an integer"))

  private def path(k: ConfigKey, v: String): Either[ValidationError, String] =
    if v.trim.nonEmpty then Right(v.trim) else Left(InvalidConfig(k.key, "must be non-blank"))

  private def tokenOf(k: ConfigKey, v: String): Either[ValidationError, String] =
    if v.nonEmpty && !v.exists(_.isWhitespace) then Right(v)
    else Left(InvalidConfig(k.key, "must be non-blank and contain no whitespace"))

  private def build(raw: ModuleConfig.Raw): Either[List[ValidationError], ResolvedSparkConfig] =
    def v(k: ConfigKey): String = raw.values(k.key)
    def posInt(k: ConfigKey)    = ModuleConfig.int(k, v(k), 1)

    val sv = v(SchemaVersionKey).toIntOption
      .toRight(InvalidConfig(SchemaVersionKey.key, "must be an integer"))
      .flatMap(
        SchemaVersion
          .check(_)
          .left
          .map(_ => InvalidConfig(SchemaVersionKey.key, "unsupported schema version"))
      )
    val allTime = bool(IncludeAllTime, v(IncludeAllTime))
    val ws      = windows(Windows, v(Windows), allTime.contains(true))
    val depth   = posInt(BfsMaxDepth)
    val pairs   = ModuleConfig.int(PathSamplePairs, v(PathSamplePairs), 0)
    val pseed   = long(PathSampleSeed, v(PathSampleSeed))
    val kSrc    = posInt(AvgPathSourcesK)
    val aseed   = long(AvgPathSeed, v(AvgPathSeed))
    val lpi     = posInt(LabelPropagationIters)
    val cci     = posInt(ComponentsIters)
    val kci     = posInt(KCoreIters)
    val rep     = posInt(RepresentativeWorkIds)
    val shuf    = posInt(ShufflePartitions)
    val outp    = posInt(OutputPartitions)
    val ckpt    = path(CheckpointDir, v(CheckpointDir))
    val root    = path(OutputRoot, v(OutputRoot))
    val inI     = path(InputRootIDir, v(InputRootIDir))
    val inV     = path(InputEntityViewsDir, v(InputEntityViewsDir))
    val inM     = path(InputMatchDecisions, v(InputMatchDecisions))
    val pol     = tokenOf(PolicyVersion, v(PolicyVersion))
    val code    = tokenOf(CodeVersion, v(CodeVersion))

    val errors = List(
      sv,
      allTime,
      ws,
      depth,
      pairs,
      pseed,
      kSrc,
      aseed,
      lpi,
      cci,
      kci,
      rep,
      shuf,
      outp,
      ckpt,
      root,
      inI,
      inV,
      inM,
      pol,
      code,
    ).collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors)
    else
      (for
        s1 <- sv
        at <- allTime
        w  <- ws
        d  <- depth
        pn <- pairs
        ps <- pseed
        k  <- kSrc
        as <- aseed
        l  <- lpi
        c  <- cci
        kk <- kci
        r  <- rep
        sh <- shuf
        op <- outp
        ck <- ckpt
        rt <- root
        i1 <- inI
        i2 <- inV
        i3 <- inM
        po <- pol
        cv <- code
      yield ResolvedSparkConfig(
        SparkConfig(
          s1,
          w,
          at,
          d,
          pn,
          ps,
          k,
          as,
          l,
          c,
          kk,
          r,
          sh,
          op,
          ck,
          rt,
          SparkInputs(i1, i2, i3),
          po,
          cv,
        ),
        raw.origins,
      )).left.map(List(_))
