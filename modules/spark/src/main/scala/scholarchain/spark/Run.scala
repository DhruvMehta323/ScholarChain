package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.apache.spark.sql.SparkSession

import scholarchain.core.{RunManifest, SparkConfig, SparkConfigLoader, ValidationError, WirePickle}

/** Command line of the analytics job.
  *
  * {{{
  * Run [--master <spark master>] [--output-root <dir>] [--code-version <token>]
  *     [--checkpoint-dir <dir>] [--set <key>=<value>]...
  * }}}
  * `--set` takes any `scholarchain.spark.*` key without its prefix (for example
  * `--set bfs-max-depth=5`). Precedence of settings: `SCHOLARCHAIN_SPARK_*` environment variables,
  * then these arguments, then the versioned defaults in `scholarchain-spark.v1.conf`. Exit codes: 0
  * success, 1 job or invariant failure, 2 bad arguments or refused run (existing output root).
  */
object Run:
  final case class Args(master: Option[String], overrides: Map[String, String])

  def parseArgs(args: List[String]): Either[String, Args] =
    def loop(rest: List[String], a: Args): Either[String, Args] = rest match
      case Nil                                    => Right(a)
      case "--master" :: v :: t                   => loop(t, a.copy(master = Some(v)))
      case "--output-root" :: v :: t              => loop(t, put(a, "output-root", v))
      case "--code-version" :: v :: t             => loop(t, put(a, "code-version", v))
      case "--checkpoint-dir" :: v :: t           => loop(t, put(a, "checkpoint-dir", v))
      case "--set" :: kv :: t if kv.contains("=") =>
        val i = kv.indexOf('=')
        loop(t, put(a, kv.substring(0, i), kv.substring(i + 1)))
      case other :: _ => Left(s"unknown or incomplete argument: $other")
    def put(a: Args, k: String, v: String) =
      a.copy(overrides = a.overrides + (s"scholarchain.spark.$k" -> v))
    loop(args, Args(None, Map.empty))

  def session(cfg: SparkConfig, master: Option[String]): SparkSession =
    val b = SparkSession
      .builder()
      .appName("scholarchain-spark-analytics")
      .config("spark.sql.shuffle.partitions", cfg.shufflePartitions.toString)
      .config("spark.sql.adaptive.enabled", "false")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.sql.sources.partitionColumnTypeInference.enabled", "false")
      .config("spark.ui.enabled", "false")
    master.foreach(m => b.master(m))
    if master.exists(_.startsWith("local")) then
      b.config("spark.driver.host", "127.0.0.1").config("spark.driver.bindAddress", "127.0.0.1")
    b.getOrCreate()

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList))

  def run(args: List[String]): Int =
    val parsed = parseArgs(args)
    parsed match
      case Left(msg) =>
        System.err.println(s"error: $msg")
        2
      case Right(a) =>
        SparkConfigLoader.load(sys.env, a.overrides) match
          case Left(errs) =>
            errs.foreach(e => System.err.println(s"config error: $e"))
            2
          case Right(resolved) => execute(resolved.config, a.master, resolved.origins)

  private def execute(cfg: SparkConfig, master: Option[String], origins: Map[String, String]): Int =
    val root = Paths.get(cfg.outputRoot)
    if Files.exists(root) then
      System.err.println(
        s"error: output root already exists (a run never overwrites; use a new root): $root"
      )
      return 2
    val spark = session(cfg, master)
    spark.sparkContext.setLogLevel("WARN")
    val listener = RunMetrics.attach(spark)
    try
      val loaded = Inputs.load(spark, cfg) match
        case Left(msg) =>
          System.err.println(s"error: $msg")
          return 1
        case Right(l) => l
      val manifest = RunManifest.build(
        "spark-analytics",
        cfg.codeVersion,
        1,
        Export.parameters(cfg),
        loaded.refs,
      ) match
        case Left(e: ValidationError) =>
          System.err.println(s"error: cannot build the run manifest: $e")
          return 1
        case Right(m) => m
      val run = RunInfo(manifest.runId, cfg.codeVersion, loaded.refs, Export.parameters(cfg))
      println(s"run ${run.runId} code=${cfg.codeVersion}")

      val computed = Pipeline.compute(spark, cfg, loaded, run.runId)
      Files.createDirectories(root)
      val written    = Tables.all.map(s => Export.write(computed.tables(s.name), s, root, cfg, run))
      val invariants = Invariants.check(computed, cfg, loaded)
      invariants.foreach(i =>
        println(s"invariant ${if i.ok then "OK  " else "FAIL"} ${i.name}: ${i.detail}")
      )

      val summary = Report.build(computed, cfg, loaded, invariants, written)
      write(root.resolve("run-summary.json"), summary)
      val runManifestJson = ujson.Obj(
        "manifestVersion" -> 1,
        "runManifest"     -> ujson.read(WirePickle.write(manifest)),
        "outputRoot"      -> cfg.outputRoot,
        "configOrigins"   -> ujson.Obj.from(
          origins.toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v))
        ),
        "absentOrEmptyInputTables" -> ujson.Arr.from(loaded.absent.map(ujson.Str(_))),
        "inputRowCounts"           -> ujson.Obj.from(
          loaded.rowCounts.toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))
        ),
        "tables" -> ujson.Arr.from(
          written.map(w =>
            ujson.Obj(
              "table"            -> w.spec.name,
              "rowCount"         -> ujson.Num(w.rowCount.toDouble),
              "partFiles"        -> w.partFiles,
              "partitionColumns" -> ujson.Arr.from(w.partitionColumns.map(ujson.Str(_))),
            )
          )
        ),
        "algorithms" -> ujson.Arr.from(
          computed.convergences.map(v =>
            ujson.Obj(
              "algorithm"    -> v.algorithm,
              "iterations"   -> v.iterations,
              "converged"    -> v.converged,
              "iterationCap" -> v.cap,
            )
          )
        ),
        "invariants" -> ujson.Arr.from(
          invariants.map(i => ujson.Obj("name" -> i.name, "ok" -> i.ok, "detail" -> i.detail))
        ),
        "observations" -> ujson.Obj.from(
          Invariants.observations(computed).map((k, v) => k -> ujson.Num(v.toDouble))
        ),
      )
      write(root.resolve("run-manifest.json"), runManifestJson)
      LoadManifests.write(LoadManifests.build(written, run), root)
      val metrics = RunMetrics.write(listener, root.resolve("run-metrics.json"))
      println(
        s"metrics stages=${metrics("stageCount").num.toLong} tasks=${metrics("taskCount").num.toLong} " +
          s"shuffleRead=${metrics("shuffleReadBytes").num.toLong} shuffleWrite=${metrics("shuffleWriteBytes").num.toLong}"
      )
      val failed = invariants.filterNot(_.ok)
      if failed.nonEmpty then
        System.err.println(s"FAILED invariants: ${failed.map(_.name).mkString(", ")}")
        1
      else
        println(s"OK ${written.size} tables written to $root")
        0
    finally spark.stop()

  private def write(p: Path, json: ujson.Value): Unit =
    Files.writeString(p, ujson.write(json, indent = 2) + "\n", StandardCharsets.UTF_8)
end Run
