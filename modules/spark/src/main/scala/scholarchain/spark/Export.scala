package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.functions as F

import scala.jdk.CollectionConverters.*

import scholarchain.core.{GraphWindow, InputRef, SparkConfig}

/** Result of writing one table. */
final case class TableWritten(
    spec: Tables.Spec,
    rowCount: Long,
    partFiles: Int,
    partitionColumns: List[String],
)

/** Everything a run knows about itself and its inputs, for provenance and manifests. */
final case class RunInfo(
    runId: String,
    codeVersion: String,
    inputs: List[InputRef],
    parameters: Map[String, String],
)

/** Partitioned Parquet export in the `scholarchain-graph/` layout and per-table provenance.
  *
  * Layout: `<output-root>/scholarchain-graph/<table>/` holds the Parquet part files; windowed
  * tables are partitioned by `window` (`window=2000-2004/`, `window=1000-9999/` for all-time) and
  * carry `window, windowStart, windowEnd` as their first columns. Every table directory also holds
  * `_provenance.json` (ignored by Parquet readers). The output root must not exist yet: a run never
  * overwrites.
  */
object Export:
  val GraphDir = "scholarchain-graph"

  /** Compares a frame with the declared table schema by column name and type (nullability ignored).
    * Returns the differences (empty = match).
    */
  def schemaDifferences(df: DataFrame, spec: Tables.Spec): List[String] =
    val actual   = df.schema.fields.toList.map(f => f.name -> f.dataType.catalogString)
    val expected = spec.schema.fields.toList.map(f => f.name -> f.dataType.catalogString)
    if actual == expected then Nil
    else
      List(
        s"${spec.name}: expected ${expected.map((n, t) => s"$n:$t").mkString(",")} " +
          s"but got ${actual.map((n, t) => s"$n:$t").mkString(",")}"
      )

  def tableDir(root: Path, table: String): Path = root.resolve(GraphDir).resolve(table)

  def write(
      df: DataFrame,
      spec: Tables.Spec,
      root: Path,
      cfg: SparkConfig,
      run: RunInfo,
  ): TableWritten =
    val diffs = schemaDifferences(df, spec)
    if diffs.nonEmpty then throw new IllegalStateException(diffs.mkString("; "))
    val out    = tableDir(root, spec.name)
    val sorted = df.repartition(cfg.outputPartitions)
    val writer = sorted.write.mode(SaveMode.ErrorIfExists)
    if spec.windowed then writer.partitionBy(Cols.Window).parquet(out.toString)
    else writer.parquet(out.toString)
    val files = listParquetFiles(out)
    val back  = DriverCollect.count(read(df.sparkSession, root, spec.name))
    val part  = if spec.windowed then List(Cols.Window) else Nil
    val w     = TableWritten(spec, back, files.size, part)
    writeProvenance(out, w, run)
    w

  /** Reads a written table back (partition column type inference off, so `window` stays a string).
    */
  def read(spark: SparkSession, root: Path, table: String): DataFrame =
    spark.conf.set("spark.sql.sources.partitionColumnTypeInference.enabled", "false")
    spark.read.parquet(tableDir(root, table).toString)

  private def inputsJson(inputs: List[InputRef]): ujson.Arr =
    ujson.Arr.from(
      inputs.map(i =>
        ujson.Obj(
          "inputId"  -> i.inputId,
          "version"  -> i.version,
          "checksum" -> i.checksum.map(ujson.Str(_)).getOrElse(ujson.Null),
        )
      )
    )

  private def paramsJson(p: Map[String, String]): ujson.Obj =
    ujson.Obj.from(p.toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v)))

  private def writeProvenance(dir: Path, w: TableWritten, run: RunInfo): Unit =
    val json = ujson.Obj(
      "table"            -> w.spec.name,
      "schema"           -> w.spec.schema.catalogString,
      "rowCount"         -> ujson.Num(w.rowCount.toDouble),
      "partitionColumns" -> ujson.Arr.from(w.partitionColumns.map(ujson.Str(_))),
      "runId"            -> run.runId,
      "codeVersion"      -> run.codeVersion,
      "inputs"           -> inputsJson(run.inputs),
      "parameters"       -> paramsJson(run.parameters),
    )
    Files.writeString(
      dir.resolve("_provenance.json"),
      ujson.write(json, indent = 2) + "\n",
      StandardCharsets.UTF_8,
    )

  /** Parameters recorded in provenance and in the run ID: every setting that can change output.
    * Paths (input, output, checkpoint) are NOT parameters: inputs are identified by checksum.
    */
  def parameters(cfg: SparkConfig): Map[String, String] =
    Map(
      "spark.schema-version"     -> cfg.schemaVersion.toString,
      "spark.windows"            -> cfg.explicitWindows.map(GraphWindow.label).mkString(","),
      "spark.include-all-time"   -> cfg.includeAllTime.toString,
      "spark.bfs-max-depth"      -> cfg.bfsMaxDepth.toString,
      "spark.path-sample-pairs"  -> cfg.pathSamplePairs.toString,
      "spark.path-sample-seed"   -> cfg.pathSampleSeed.toString,
      "spark.avg-path-sources-k" -> cfg.avgPathSourcesK.toString,
      "spark.avg-path-seed"      -> cfg.avgPathSeed.toString,
      "spark.label-propagation-max-iterations"    -> cfg.labelPropagationMaxIterations.toString,
      "spark.connected-components-max-iterations" -> cfg.connectedComponentsMaxIterations.toString,
      "spark.k-core-max-iterations"               -> cfg.kCoreMaxIterations.toString,
      "spark.representative-work-ids-bound"       -> cfg.representativeWorkIdsBound.toString,
      "spark.shuffle-partitions"                  -> cfg.shufflePartitions.toString,
      "spark.output-partitions"                   -> cfg.outputPartitions.toString,
      "spark.policy-version"                      -> cfg.policyVersion,
    )

  /** Parquet part files below `dir` (hidden files excluded), sorted. */
  def listParquetFiles(dir: Path): List[Path] =
    if !Files.exists(dir) then Nil
    else
      val s = Files.walk(dir)
      try
        s.iterator()
          .asScala
          .filter(p =>
            Files.isRegularFile(p) && p.getFileName.toString.endsWith(".parquet") &&
              !p.getFileName.toString.startsWith(".")
          )
          .toList
          .sortBy(_.toString)
      finally s.close()
end Export
