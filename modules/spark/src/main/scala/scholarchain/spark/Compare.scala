package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions as F

import scala.jdk.CollectionConverters.*

/** Compares two output roots table by table and prints `ALL TABLES EQUAL` or the differences.
  *
  * {{{
  * Compare <rootA> <rootB> [--master local[*]]
  * }}}
  * For every table directory under `<root>/scholarchain-graph/` of either root: both must exist,
  * have the same schema (column names and types), the same row count, and the same rows as
  * multisets (`exceptAll` both ways, nothing collected).
  *
  * THE IGNORED SET (run identity only; everything else, including the input checksums, the
  * algorithm parameters and every data column, is compared):
  *   - columns `runId` and `codeVersion` (`IdentityColumns`; `semantic-summaries.runId` is the only
  *     one today, and it is the only column derived from the run ID);
  *   - inside every row-level `provenance` string column (the compact JSON of core `Provenance`):
  *     the keys `runId` and `codeVersion` (`ProvenanceRunKeys`), and any parameter named in
  *     `RunIdentityParameterKeys` (a parameter that carries only a label or a path; none exists
  *     today, since paths are not parameters). Its `inputs` (with checksums) and the other
  *     `parameters` stay;
  *   - in the JSON companions (`run-summary.json`, `run-manifest.json`, the four load manifests and
  *     the per-table `_provenance.json`): the keys `runId`, `codeVersion`, `outputRoot` and
  *     `configOrigins`, plus `RunIdentityParameterKeys` (`IdentityJsonKeys`), at any depth.
  *
  * `run-metrics.json` holds wall clock measurements and is not compared. Exit code 0 when equal, 1
  * otherwise, 2 on bad usage.
  */
object Compare:
  val IdentityColumns: Set[String]   = Set("runId", "codeVersion")
  val ProvenanceRunKeys: Set[String] = Set("runId", "codeVersion")

  /** Parameter keys that carry only a label or a path (none are produced today). */
  val RunIdentityParameterKeys: Set[String] =
    Set("spark.code-version", "spark.output-root", "spark.checkpoint-dir")
  val IdentityJsonKeys: Set[String] =
    Set("runId", "codeVersion", "outputRoot", "configOrigins") ++ RunIdentityParameterKeys
  val ProvenanceColumn         = "provenance"
  val ProvenanceUdf            = "sc_compare_strip_provenance_run"
  val Companions: List[String] = List(
    "run-summary.json",
    "run-manifest.json",
    "load-manifests/neptune.json",
    "load-manifests/dynamodb.json",
    "load-manifests/s3-vectors.json",
    "load-manifests/s3.json",
  )

  def main(args: Array[String]): Unit =
    val (flags, pos) = args.toList.partition(_.startsWith("--"))
    val master       = args.toList.sliding(2).collectFirst { case List("--master", m) => m }
    val roots        = args.toList.filterNot(a => a.startsWith("--") || master.contains(a))
    if roots.size != 2 then
      System.err.println("usage: Compare <rootA> <rootB> [--master local[*]]")
      sys.exit(2)
    val _     = (flags, pos)
    val spark = SparkSession
      .builder()
      .appName("scholarchain-spark-compare")
      .master(master.getOrElse("local[*]"))
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.sql.adaptive.enabled", "false")
      .config("spark.sql.sources.partitionColumnTypeInference.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    registerUdfs(spark)
    try
      val diffs = compare(spark, Paths.get(roots(0)), Paths.get(roots(1)), println)
      if diffs.isEmpty then
        println("ALL TABLES EQUAL")
        sys.exit(0)
      else
        println("DIFFERENCES")
        diffs.foreach(d => println(s"  $d"))
        sys.exit(1)
    finally spark.stop()

  def registerUdfs(spark: SparkSession): Unit =
    val _ = spark.udf.register(
      ProvenanceUdf,
      new StripProvenanceRunUdf,
      org.apache.spark.sql.types.DataTypes.StringType,
    )

  private def tableNames(root: Path): Set[String] =
    val dir = root.resolve(Export.GraphDir)
    if !Files.isDirectory(dir) then Set.empty
    else
      val s = Files.list(dir)
      try s.iterator().asScala.filter(Files.isDirectory(_)).map(_.getFileName.toString).toSet
      finally s.close()

  /** Drops run-identity columns and turns map columns into sorted entry arrays (Spark cannot run
    * set operations on MAP types).
    */
  private def stripIdentity(df0: DataFrame): DataFrame =
    val df = df0.drop(df0.columns.filter(IdentityColumns.contains)*)
    df.schema.fields.foldLeft(df) { (d, f) =>
      f.dataType match
        case _: org.apache.spark.sql.types.MapType =>
          d.withColumn(f.name, F.array_sort(F.map_entries(F.col(f.name))))
        case _: org.apache.spark.sql.types.StringType if f.name == ProvenanceColumn =>
          d.withColumn(f.name, F.call_udf(ProvenanceUdf, F.col(f.name)))
        case _ => d
    }

  /** Removes the run-identity fields from a provenance JSON text (see the ignored set). Text that
    * is not JSON is returned unchanged, so a real difference still shows.
    */
  def stripProvenanceRun(text: String): String =
    if text == null then null
    else
      try
        val v = ujson.read(text)
        v match
          case o: ujson.Obj =>
            ProvenanceRunKeys.foreach(k => o.value.remove(k))
            o.value.get("parameters").foreach {
              case p: ujson.Obj => RunIdentityParameterKeys.foreach(k => p.value.remove(k))
              case _            => ()
            }
            ujson.write(o)
          case _ => text
      catch case scala.util.control.NonFatal(_) => text

  private def stripJson(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj =>
      ujson.Obj.from(
        o.value.toList
          .filterNot((k, _) => IdentityJsonKeys.contains(k))
          .map((k, x) => k -> stripJson(x))
      )
    case a: ujson.Arr => ujson.Arr.from(a.value.toList.map(stripJson))
    case other        => other

  /** Returns the list of differences (empty when the roots are equal); `log` gets one line per
    * table.
    */
  def compare(spark: SparkSession, a: Path, b: Path, log: String => Unit): List[String] =
    registerUdfs(spark)
    val namesA     = tableNames(a)
    val namesB     = tableNames(b)
    val tableDiffs = (namesA ++ namesB).toList.sorted.flatMap { name =>
      if !namesA.contains(name) then
        log(s"$name: missing in A"); List(s"table $name missing in $a")
      else if !namesB.contains(name) then
        log(s"$name: missing in B"); List(s"table $name missing in $b")
      else
        val da = stripIdentity(Export.read(spark, a, name))
        val db = stripIdentity(Export.read(spark, b, name))
        val sa = da.schema.fields.toList.map(f => f.name -> f.dataType.catalogString)
        val sb = db.schema.fields.toList.map(f => f.name -> f.dataType.catalogString)
        if sa != sb then
          log(s"$name: SCHEMA DIFFERS")
          List(s"table $name schema differs: $sa vs $sb")
        else
          val ca    = DriverCollect.count(da)
          val cb    = DriverCollect.count(db)
          val onlyA = DriverCollect.count(da.exceptAll(db))
          val onlyB = DriverCollect.count(db.exceptAll(da))
          if ca == cb && onlyA == 0L && onlyB == 0L then
            log(s"$name: rows=$ca EQUAL")
            Nil
          else
            log(s"$name: DIFFERS rows A=$ca B=$cb onlyInA=$onlyA onlyInB=$onlyB")
            List(s"table $name differs: rows A=$ca B=$cb, rows only in A=$onlyA, only in B=$onlyB")
    }
    val jsonFiles =
      (Companions ++ (namesA ++ namesB).toList.sorted.map(n =>
        s"${Export.GraphDir}/$n/_provenance.json"
      ))
    val jsonDiffs = jsonFiles.flatMap { rel =>
      val pa = a.resolve(rel)
      val pb = b.resolve(rel)
      (Files.exists(pa), Files.exists(pb)) match
        case (false, false) => Nil
        case (true, true)   =>
          val ja = stripJson(ujson.read(Files.readString(pa, StandardCharsets.UTF_8)))
          val jb = stripJson(ujson.read(Files.readString(pb, StandardCharsets.UTF_8)))
          if ja == jb then
            log(s"$rel: EQUAL")
            Nil
          else
            log(s"$rel: DIFFERS")
            List(s"file $rel differs after removing run-identity keys")
        case _ =>
          log(s"$rel: present in only one root")
          List(s"file $rel present in only one root")
    }
    tableDiffs ++ jsonDiffs
end Compare

/** Serializable Spark UDF that removes the run-identity fields from a provenance JSON string. */
final class StripProvenanceRunUdf extends org.apache.spark.sql.api.java.UDF1[String, String]:
  override def call(text: String): String = Compare.stripProvenanceRun(text)
