package scholarchain.spark

import java.nio.file.{Files, Path}

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.StructType

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

/** One lazily created local SparkSession per forked test JVM, plus per-suite scratch directories.
  * `Test / parallelExecution := false` keeps suites sequential.
  */
object TestSpark:
  lazy val spark: SparkSession =
    val s = SparkSession
      .builder()
      .appName("scholarchain-spark-tests")
      .master("local[2]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.sql.adaptive.enabled", "false")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.sql.sources.partitionColumnTypeInference.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
    s.sparkContext.setLogLevel("ERROR")
    s

  private lazy val scratch: Path = Files.createTempDirectory("scholarchain-spark-test-")

  /** A fresh, empty directory below the scratch root (the name need not be unique). */
  def freshDir(name: String): Path =
    Files.createTempDirectory(scratch, name + "-")

  /** Gives the suite its own checkpoint directory (the SparkContext keeps one at a time). */
  def useCheckpointFor(suite: String): String =
    val d = freshDir(s"ck-$suite").toString
    spark.sparkContext.setCheckpointDir(d)
    d

  /** The default test configuration: two explicit windows plus all-time, small partition counts.
    * `extra` entries are `scholarchain.spark.<key>` overrides without the prefix.
    */
  def config(ckDir: String, extra: Map[String, String] = Map.empty): SparkConfig =
    val base = Map(
      "checkpoint-dir"     -> ckDir,
      "windows"            -> "2000-2004,2005-2009",
      "shuffle-partitions" -> "4",
      "output-partitions"  -> "2",
    )
    val over = (base ++ extra).map((k, v) => s"scholarchain.spark.$k" -> v)
    SparkConfigLoader.load(Map.empty, over) match
      case Right(r) => r.config
      case Left(es) => throw new AssertionError(s"test config invalid: ${es.map(_.message)}")

  def frame(schema: StructType, rows: Seq[Row]): DataFrame =
    spark.createDataFrame(rows.asJava, schema)

  def empty(schema: StructType): DataFrame = frame(schema, Nil)

/** Small accessors for rows, to keep expected-value tests readable. */
extension (r: Row)
  def s(c: String): String                 = r.getString(r.fieldIndex(c))
  def l(c: String): Long                   = r.getLong(r.fieldIndex(c))
  def i(c: String): Int                    = r.getInt(r.fieldIndex(c))
  def d(c: String): Double                 = r.getDouble(r.fieldIndex(c))
  def b(c: String): Boolean                = r.getBoolean(r.fieldIndex(c))
  def strs(c: String): List[String]        = r.getSeq[String](r.fieldIndex(c)).toList
  def strss(c: String): List[List[String]] =
    r.getSeq[scala.collection.Seq[String]](r.fieldIndex(c)).map(_.toList).toList
  def isNull(c: String): Boolean = r.isNullAt(r.fieldIndex(c))

object Ok:
  def get[A](e: Either[ValidationError, A]): A = e match
    case Right(a)  => a
    case Left(err) => throw new AssertionError(s"fixture construction failed: ${err.message}")

/** The hand-checkable fixture graph (all expected values are written out by hand in the suites).
  *
  * Researchers (DBLP person keys are the names): Ada Bob Cy Di Ed (a 5-author paper), Fay Gus Hal
  * Ivy (a 4-clique built from three papers), Mo (bridge person: Mo-Ed in the first window, Mo-Fay
  * in the second), Jo Kit (a repeated collaboration), Lu (a single-author paper: isolated).
  *
  * Works (key, year, authors); team size is the author count:
  * {{{
  * W01 2001 Ada Bob Cy Di Ed   W02 2006 Fay Gus Hal    W03 2007 Gus Hal Ivy   W04 2008 Fay Ivy
  * W05 2004 Mo Ed              W06 2005 Mo Fay         W07 2002 Jo Kit        W08 2003 Jo Kit
  * W09 2003 Lu                 W10 2012 Ada Bob        W11 2006 Jo Kit
  * }}}
  * Windows used by the suites: 2000-2004, 2005-2009 and all-time (1000-9999). W10 (2012) is in the
  * all-time graph only.
  */
object Fixture:
  val Policy      = "match-policy.v1"
  val W1          = "2000-2004"
  val W2          = "2005-2009"
  val All         = "1000-9999"
  val GraphPolicy = "coauthor-graph.v1"

  final case class Work(key: String, year: Int, authors: List[String])

  def workId(key: String): String = s"work:dblp:t/$key"

  val works: List[Work] = List(
    Work("W01", 2001, List("Ada", "Bob", "Cy", "Di", "Ed")),
    Work("W02", 2006, List("Fay", "Gus", "Hal")),
    Work("W03", 2007, List("Gus", "Hal", "Ivy")),
    Work("W04", 2008, List("Fay", "Ivy")),
    Work("W05", 2004, List("Mo", "Ed")),
    Work("W06", 2005, List("Mo", "Fay")),
    Work("W07", 2002, List("Jo", "Kit")),
    Work("W08", 2003, List("Jo", "Kit")),
    Work("W09", 2003, List("Lu")),
    Work("W10", 2012, List("Ada", "Bob")),
    Work("W11", 2006, List("Jo", "Kit")),
  )

  val names: List[String] =
    List("Ada", "Bob", "Cy", "Di", "Ed", "Fay", "Gus", "Hal", "Ivy", "Mo", "Jo", "Kit", "Lu")

  def rid(name: String): String = Ok.get(Ids.researcherIdDblpPerson(name))

  val nameOf: Map[String, String] = names.map(n => rid(n) -> n).toMap

  def mention(w: Work, pos: Int): String = Ok.get(Ids.mentionId(workId(w.key), pos))

  def edgeId(a: String, b: String, window: GraphWindow): String =
    Ok.get(Ids.coauthorEdgeId(rid(a), rid(b), window, GraphPolicy))

  val extWork       = "work:openalex:W9"
  val topicGraph    = "topic:graph-mining"
  val topicDatabase = "topic:databases"

  private val mentionRows: List[Row] = for
    w         <- works
    (name, p) <- w.authors.zipWithIndex
  yield Row(mention(w, p), workId(w.key), p, name, null, "dblp")

  private val authorshipRows: List[Row] = for
    w      <- works
    (_, p) <- w.authors.zipWithIndex
  yield Row(mention(w, p), workId(w.key), p, w.year)

  /** Mention-level pair candidates as Hadoop writes them: every pair of one work, first < second.
    */
  private val pairRows: List[Row] = for
    w    <- works
    pair <- w.authors.indices.map(mention(w, _)).sorted.combinations(2).toList
  yield Row(workId(w.key), w.year, pair(0), pair(1))

  private val workRows: List[Row] =
    works.map { w =>
      val topics = if w.key == "W01" || w.key == "W02" then Seq(topicGraph) else Seq.empty[String]
      Row(workId(w.key), s"Title ${w.key}", w.year, null, "venue:dblp:conf/t", topics)
    } :+ Row(extWork, "External W9", 2001, null, null, Seq(topicDatabase))

  private val teamRows: List[Row] = works.map(w =>
    Row(workId(w.key), w.year, w.authors.size, w.authors.indices.map(mention(w, _)).sorted.toSeq)
  )

  val decisionRows: List[Row] =
    List(Row(workId("W01"), extWork, "work", "accepted", Policy))

  private val viewRows: List[Row] =
    List(Row(workId("W01"), "work", "dblp"), Row(extWork, "work", "openalex"))

  /** The pipeline inputs built directly from frames (no files). */
  def loaded(spark: SparkSession): Inputs.LoadedInputs =
    import TestSpark.{empty, frame}
    Inputs.LoadedInputs(
      mentions = frame(InputSchemas.Mentions, mentionRows),
      authorship = frame(InputSchemas.Authorship, authorshipRows),
      pairs = frame(InputSchemas.PairCandidates, pairRows),
      works = frame(InputSchemas.Works, workRows),
      teams = frame(InputSchemas.Teams, teamRows),
      topics = frame(
        InputSchemas.Topics,
        List(Row(topicGraph, "Graph Mining"), Row(topicDatabase, "Databases")),
      ),
      institutions = frame(InputSchemas.Institutions, List(Row("institution:t/u", "Test U", "US"))),
      aliases = empty(InputSchemas.Aliases),
      decisions = frame(InputSchemas.Decisions, decisionRows),
      entityViews = frame(InputSchemas.EntityViews, viewRows),
      refs = List(InputRef("curated/authorship", "snapshot=t", Some("a" * 64))),
      absent = List("person-aliases"),
      rowCounts = Map(
        "author-mentions"       -> mentionRows.size.toLong,
        "authorship"            -> authorshipRows.size.toLong,
        "pair-candidates"       -> pairRows.size.toLong,
        "works"                 -> workRows.size.toLong,
        "teams"                 -> teamRows.size.toLong,
        "match-decisions-final" -> decisionRows.size.toLong,
      ),
    )

  def mentionRowCount: Int = mentionRows.size
  def pairRowCount: Int    = pairRows.size
  // Rows with a schema attached (round trip through a frame), so that fields can be read by name.
  private def withSchema(schema: StructType, rows: List[Row]): List[Row] =
    TestSpark.frame(schema, rows).collect().toList
  def mentionRowsForFiles: List[Row]    = withSchema(InputSchemas.Mentions, mentionRows)
  def authorshipRowsForFiles: List[Row] = withSchema(InputSchemas.Authorship, authorshipRows)
  def pairRowsForFiles: List[Row]       = withSchema(InputSchemas.PairCandidates, pairRows)
  def workRowsForFiles: List[Row]       = withSchema(InputSchemas.Works, workRows)
  def teamRowsForFiles: List[Row]       = withSchema(InputSchemas.Teams, teamRows)
  def decisionRowsForFiles: List[Row]   = withSchema(InputSchemas.Decisions, decisionRows)

/** The shared pipeline runs over the fixture (computed once per JVM, lazily). */
object FixtureRun:
  import TestSpark.spark

  lazy val ckDir: String    = TestSpark.useCheckpointFor("fixture")
  lazy val cfg: SparkConfig = TestSpark.config(ckDir)
  lazy val runId: String    =
    Ok.get(Ids.runId("spark-analytics", "test", 1, Map("fixture" -> "1"), Nil))
  lazy val inputs: Inputs.LoadedInputs = Fixture.loaded(spark)

  lazy val computed: Computed =
    ckDir
    Pipeline.compute(spark, cfg, inputs, runId)

  /** A second, independent run over freshly built inputs (for the determinism checks). */
  lazy val computedAgain: Computed =
    computed
    Pipeline.compute(spark, cfg, Fixture.loaded(spark), runId)

  private lazy val cache = scala.collection.concurrent.TrieMap.empty[String, List[Row]]

  /** All rows of a table of the first run, collected once (tests only; the tables are tiny). */
  def rows(table: String): List[Row] =
    cache.getOrElseUpdate(table, computed.tables(table).collect().toList)

  /** Rows of a windowed table of one window label. */
  def rows(table: String, window: String): List[Row] = rows(table).filter(_.s("window") == window)

  def byName(table: String, window: String, idCol: String = "researcherId"): Map[String, Row] =
    rows(table, window).map(r => Fixture.nameOf(r.s(idCol)) -> r).toMap
