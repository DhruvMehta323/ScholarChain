package scholarchain.spark

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.types.StructType

import scala.jdk.CollectionConverters.*

import scholarchain.core.{Hashing, InputRef, SparkConfig}

/** The thin file edge: reads the Hadoop curated tables, the HJ6a entity views and the final match
  * decisions as gzipped or plain JSON Lines with EXPLICIT schemas (FAILFAST), flattens the nested
  * Hadoop rows into the flat `InputSchemas`, and computes input checksums from the files.
  *
  * Layout read: `<root-i-dir>/<table>/snapshot=<s>/run_id=<hex>/[year=<y>/]part-*`. Hidden files
  * (`.crc`) and `_SUCCESS` are ignored. More than one `run_id=` directory under one table is
  * refused (it would double-count rows). `person-aliases` and the other optional tables are treated
  * as EMPTY when their directory is absent, and that is recorded in `LoadedInputs.absent`.
  */
object Inputs:
  private val Required = List("authorship", "author-mentions", "works", "pair-candidates", "teams")

  final case class TableFiles(table: String, files: List[Path], ref: Option[InputRef])

  final case class LoadedInputs(
      mentions: DataFrame,
      authorship: DataFrame,
      pairs: DataFrame,
      works: DataFrame,
      teams: DataFrame,
      topics: DataFrame,
      institutions: DataFrame,
      aliases: DataFrame,
      decisions: DataFrame,
      entityViews: DataFrame,
      refs: List[InputRef],
      absent: List[String],
      rowCounts: Map[String, Long],
  )

  /** Part files below `dir`, sorted by path. Empty when `dir` does not exist. */
  def partFiles(dir: Path): List[Path] =
    if !Files.exists(dir) then Nil
    else if Files.isRegularFile(dir) then List(dir)
    else
      val s = Files.walk(dir)
      try
        s.iterator()
          .asScala
          .filter(p =>
            Files.isRegularFile(p) && {
              val n = p.getFileName.toString
              n.startsWith("part-") && !n.endsWith(".crc")
            }
          )
          .toList
          .sortBy(_.toString)
      finally s.close()

  private def sha256(p: Path): String =
    val md              = MessageDigest.getInstance("SHA-256")
    val in: InputStream = Files.newInputStream(p)
    try
      val buf = new Array[Byte](1 << 16)
      var n   = in.read(buf)
      while n >= 0 do
        md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  /** SHA-256 over the sorted `<relative path>:<file sha256>` lines (same recipe as the Hadoop tree
    * checksum).
    */
  private[spark] def treeChecksum(base: Path, files: List[Path]): String =
    val lines = files.map(f => s"${base.relativize(f)}:${sha256(f)}").sorted
    Hashing.sha256Hex(lines.mkString("\n").getBytes(StandardCharsets.UTF_8))

  /** `snapshot=<s>;run_id=<hex>` taken from the path, or `unversioned`. At most one run_id. */
  private[spark] def versionOf(base: Path, files: List[Path]): Either[String, String] =
    val segs = files.map(f => base.relativize(f).iterator().asScala.map(_.toString).toList)
    def pick(prefix: String): List[String] =
      segs.flatMap(_.find(_.startsWith(prefix))).distinct.sorted
    val runIds = pick("run_id=")
    if runIds.size > 1 then
      Left(s"more than one run_id directory under $base (${runIds.size}); refusing to double-count")
    else
      val v = (pick("snapshot=") ++ runIds).mkString(";")
      Right(if v.isEmpty then "unversioned" else v)

  private def readJson(spark: SparkSession, files: List[Path], schema: StructType): DataFrame =
    if files.isEmpty then
      spark.createDataFrame(java.util.Collections.emptyList[org.apache.spark.sql.Row](), schema)
    else
      spark.read
        .schema(schema)
        .option("mode", "FAILFAST")
        .json(files.map(_.toString)*)

  private def curatedTable(
      spark: SparkSession,
      root: Path,
      table: String,
      rawSchema: StructType,
  ): Either[String, (DataFrame, List[Path], Option[InputRef], Boolean)] =
    val dir   = root.resolve(table)
    val files = partFiles(dir)
    if files.isEmpty then
      if Required.contains(table) then Left(s"required input table '$table' is missing under $dir")
      else Right((readJson(spark, Nil, rawSchema), Nil, None, true))
    else
      versionOf(dir, files).map { v =>
        val ref = InputRef(s"curated/$table", v, Some(treeChecksum(dir, files)))
        (readJson(spark, files, rawSchema), files, Some(ref), false)
      }

  def load(spark: SparkSession, cfg: SparkConfig): Either[String, LoadedInputs] =
    val root                        = Paths.get(cfg.inputs.rootICuratedDir)
    def tbl(t: String, ddl: String) = curatedTable(spark, root, t, StructType.fromDDL(ddl))
    for
      mRaw <- tbl(
        "author-mentions",
        "mentionId string, workId string, position int, rawName string, orcid string, provenance struct<sourceType:string>",
      )
      au   <- tbl("authorship", "mentionId string, workId string, position int, year int")
      pRaw <- tbl(
        "pair-candidates",
        "workId string, year int, pair struct<first:string, second:string>",
      )
      wk <- tbl(
        "works",
        "workId string, title string, year int, doi string, venueId string, topicIds array<string>",
      )
      tm <- tbl("teams", "workId string, year int, teamSize int, mentionIds array<string>")
      tp <- tbl("topics", "topicId string, label string")
      in <- tbl("institutions", "institutionId string, name string, country string")
      al <- tbl("person-aliases", "aliasKey string, names array<string>, orcids array<string>")
      viewsDir   = Paths.get(cfg.inputs.hj6aEntityViewsDir)
      viewsFiles = partFiles(viewsDir)
      _ <- Either.cond(viewsFiles.nonEmpty, (), s"entity views not found under $viewsDir")
      decPath = Paths.get(cfg.inputs.matchDecisionsFinalPath)
      _ <- Either.cond(Files.isRegularFile(decPath), (), s"match decisions not found at $decPath")
    yield
      val views    = readJson(spark, viewsFiles, InputSchemas.EntityViews)
      val dec      = readJson(spark, List(decPath), InputSchemas.Decisions)
      val viewsRef = InputRef(
        "hj6a/entity-views",
        "unversioned",
        Some(treeChecksum(viewsDir, viewsFiles)),
      )
      val decRef = InputRef(
        "er/match-decisions-final",
        "unversioned",
        Some(Hashing.sha256Hex(Files.readAllBytes(decPath))),
      )
      val (mDf, aDf, pDf, wDf, tDf, topDf, inDf, alDf) =
        (mRaw._1, au._1, pRaw._1, wk._1, tm._1, tp._1, in._1, al._1)
      val mentions = mDf.selectExpr(
        "mentionId",
        "workId",
        "position",
        "rawName",
        "orcid",
        "provenance.sourceType as sourceType",
      )
      val pairs = pDf.selectExpr("workId", "year", "pair.first as first", "pair.second as second")
      val refs  = (List(mRaw, au, pRaw, wk, tm, tp, in, al).flatMap(_._3) ++ List(viewsRef, decRef))
        .sortBy(_.inputId)
      val absent = List(
        "author-mentions" -> mRaw,
        "authorship"      -> au,
        "pair-candidates" -> pRaw,
        "works"           -> wk,
        "teams"           -> tm,
        "topics"          -> tp,
        "institutions"    -> in,
        "person-aliases"  -> al,
      ).filter(_._2._4).map(_._1)
      val counts = List(
        "author-mentions"       -> mentions,
        "authorship"            -> aDf,
        "pair-candidates"       -> pairs,
        "works"                 -> wDf,
        "teams"                 -> tDf,
        "topics"                -> topDf,
        "institutions"          -> inDf,
        "person-aliases"        -> alDf,
        "hj6a-entity-views"     -> views,
        "match-decisions-final" -> dec,
      ).map((n, df) => n -> DriverCollect.count(df)).toMap
      LoadedInputs(
        mentions,
        aDf,
        pairs,
        wDf,
        tDf,
        topDf,
        inDf,
        alDf,
        dec,
        views,
        refs,
        absent,
        counts,
      )
end Inputs
