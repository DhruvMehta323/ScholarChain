package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions as F

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

/** Two exports of the fixture (first run and second run), written once per JVM and shared by the
  * export and determinism suites.
  */
object FixtureExports:
  import FixtureRun.{cfg, computed, computedAgain, runId}

  lazy val run: RunInfo =
    RunInfo(
      runId,
      "test",
      List(InputRef("curated/authorship", "snapshot=t", Some("a" * 64))),
      Export.parameters(cfg),
    )

  private def exportAll(c: Computed, name: String): (Path, List[TableWritten]) =
    val root = TestSpark.freshDir(name)
    val dir  = root.resolve("out") // the output root must not exist yet
    Files.createDirectories(dir.getParent)
    (dir, Tables.all.map(s => Export.write(c.tables(s.name), s, dir, cfg, run)))

  lazy val a: (Path, List[TableWritten]) = exportAll(computed, "export-a")
  lazy val b: (Path, List[TableWritten]) = exportAll(computedAgain, "export-b")

class ExportSuite extends munit.FunSuite:

  import FixtureExports.*

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  private def rootA: Path                 = FixtureExports.a._1
  private def written: List[TableWritten] = FixtureExports.a._2
  private def spark                       = TestSpark.spark

  private def types(df: DataFrame): List[(String, String)] =
    df.schema.fields.toList.map(f => f.name -> f.dataType.catalogString)

  // ---- parquet layout and schemas -------------------------------------------------------------

  test("every declared table is written, in the scholarchain-graph layout") {
    assertEquals(written.map(_.spec.name), Tables.all.map(_.name))
    assertEquals(Tables.all.size, 30)
    Tables.all.foreach { t =>
      assert(Files.isDirectory(rootA.resolve("scholarchain-graph").resolve(t.name)), t.name)
      assert(
        Files.isRegularFile(
          rootA.resolve("scholarchain-graph").resolve(t.name).resolve("_provenance.json")
        ),
        t.name,
      )
    }
  }

  test("parquet schemas read back equal the declared contracts (window partition column last)") {
    Tables.all.foreach { t =>
      val back     = types(Export.read(spark, rootA, t.name))
      val declared = types(
        spark.createDataFrame(java.util.Collections.emptyList[org.apache.spark.sql.Row](), t.schema)
      )
      if t.windowed then
        // Spark moves the partition column `window` to the end of the schema
        val (w, rest) = declared.partition(_._1 == "window")
        assertEquals(back, rest ++ w, t.name)
        assertEquals(declared.take(3).map(_._1), List("window", "windowStart", "windowEnd"), t.name)
      else assertEquals(back, declared, t.name)
    }
  }

  test("row counts read back equal the computed tables") {
    written.foreach { w =>
      assertEquals(w.rowCount, FixtureRun.computed.tables(w.spec.name).count(), w.spec.name)
    }
  }

  test("windowed tables are partitioned by window; at most output-partitions files per window") {
    written.filter(_.spec.windowed).foreach { w =>
      assertEquals(w.partitionColumns, List("window"))
      val dir  = rootA.resolve("scholarchain-graph").resolve(w.spec.name)
      val subs = Files.list(dir).iterator().asScala.filter(Files.isDirectory(_)).toList
      assert(subs.nonEmpty, w.spec.name)
      assert(
        subs.forall(_.getFileName.toString.startsWith("window=")),
        s"${w.spec.name}: ${subs.map(_.getFileName)}",
      )
      subs.foreach { s =>
        assert(Export.listParquetFiles(s).size <= FixtureRun.cfg.outputPartitions, s.toString)
      }
      assertEquals(
        subs.map(_.getFileName.toString.stripPrefix("window=")).toSet,
        FixtureRun.rows(w.spec.name).map(_.s("window")).toSet,
        w.spec.name,
      )
    }
    written
      .filterNot(_.spec.windowed)
      .foreach(w => assertEquals(w.partitionColumns, Nil, w.spec.name))
  }

  test("the window column stays a string when read back (no partition type inference)") {
    val df = Export.read(spark, rootA, "coreness")
    assertEquals(df.schema("window").dataType.catalogString, "string")
    assert(df.collect().exists(_.s("window") == "2000-2004"))
  }

  test("export refuses a frame whose schema differs from the declared one") {
    val bad = FixtureRun.computed.tables("coreness").drop("coreness")
    val dir = TestSpark.freshDir("export-bad").resolve("out")
    val e   =
      intercept[IllegalStateException](Export.write(bad, Tables.Coreness, dir, FixtureRun.cfg, run))
    assert(e.getMessage.contains("coreness"), e.getMessage)
    assert(!Files.exists(dir.resolve("scholarchain-graph").resolve("coreness")))
    val wrongType = FixtureRun.computed
      .tables("coreness")
      .withColumn("coreness", F.col("coreness").cast("string"))
    intercept[IllegalStateException](
      Export.write(wrongType, Tables.Coreness, dir, FixtureRun.cfg, run)
    )
  }

  test("export never overwrites an existing table directory") {
    val again = intercept[Exception](
      Export.write(
        FixtureRun.computed.tables("coreness"),
        Tables.Coreness,
        rootA,
        FixtureRun.cfg,
        run,
      )
    )
    assert(again.getMessage != null && again.getMessage.nonEmpty)
  }

  // ---- provenance -----------------------------------------------------------------------------

  test(
    "every table has a provenance file: schema, row count, run id, code version, inputs, parameters"
  ) {
    written.foreach { w =>
      val j = ujson.read(
        Files.readString(
          rootA.resolve("scholarchain-graph").resolve(w.spec.name).resolve("_provenance.json"),
          StandardCharsets.UTF_8,
        )
      )
      assertEquals(j("table").str, w.spec.name)
      assertEquals(j("rowCount").num.toLong, w.rowCount)
      assertEquals(j("runId").str, run.runId)
      assertEquals(j("codeVersion").str, "test")
      assertEquals(j("schema").str, w.spec.schema.catalogString)
      assertEquals(j("partitionColumns").arr.map(_.str).toList, w.partitionColumns)
      assertEquals(j("inputs").arr.head("checksum").str, "a" * 64)
      assertEquals(j("parameters")("spark.windows").str, "2000-2004,2005-2009")
      assertEquals(j("parameters")("spark.policy-version").str, "coauthor-graph.v1")
      assert(!j.obj.contains("createdAt") && !j.obj.contains("timestamp"))
    }
  }

  test("provenance parameters are the algorithm settings: no paths, no secrets") {
    val p = Export.parameters(FixtureRun.cfg)
    assert(
      p.keys.forall(k =>
        !k.endsWith("-dir") && !k.endsWith("-root") && !k.endsWith("-path") && !k.contains("input.")
      ),
      p.keys.toString,
    )
    assertEquals(p("spark.bfs-max-depth"), "6")
    assertEquals(p("spark.path-sample-seed"), "441")
    assertEquals(p("spark.windows"), "2000-2004,2005-2009")
  }

  // ---- load manifests -------------------------------------------------------------------------

  private lazy val manifests: Map[String, ujson.Obj] = LoadManifests.build(written, run)

  private def specOf(t: String): Tables.Spec = Tables.byName(t).getOrElse(fail(s"unknown table $t"))

  test("four load manifests with run id, code version, root and manifest version") {
    assertEquals(manifests.keySet, Set("neptune", "dynamodb", "s3-vectors", "s3"))
    manifests.foreach { (target, m) =>
      assertEquals(m("manifestVersion").num.toInt, 1)
      assertEquals(m("target").str, target)
      assertEquals(m("runId").str, run.runId)
      assertEquals(m("codeVersion").str, "test")
      assertEquals(m("root").str, "scholarchain-graph")
    }
  }

  test("the s3 manifest lists every table, with row counts, part files and provenance keys") {
    val tables = manifests("s3")("tables").arr.map(_.obj).toList
    assertEquals(tables.map(_("table").str), Tables.all.map(_.name))
    tables.foreach { t =>
      val w = written.find(_.spec.name == t("table").str).get
      assertEquals(t("rowCount").num.toLong, w.rowCount)
      assertEquals(t("partFiles").num.toInt, w.partFiles)
      assert(w.partFiles >= 1)
      assertEquals(t("path").str, s"scholarchain-graph/${w.spec.name}")
      assertEquals(t("keyPrefix").str, s"scholarchain-graph/${w.spec.name}/")
      assertEquals(t("provenance").str, s"scholarchain-graph/${w.spec.name}/_provenance.json")
      assertEquals(t("format").str, "parquet")
      assertEquals(t("partitionColumns").arr.map(_.str).toList, w.partitionColumns)
    }
    assertEquals(
      manifests("s3")("companionFiles").arr.map(_.str).toList,
      List("run-manifest.json", "run-summary.json", "run-metrics.json"),
    )
  }

  test("neptune, dynamodb and s3-vectors manifests reference only declared tables and columns") {
    def tableCols(t: String) = specOf(t).columns.toSet
    val vertices             = manifests("neptune")("vertices").arr.map(_.obj).toList
    val edges                = manifests("neptune")("edges").arr.map(_.obj).toList
    assertEquals(
      vertices.map(_("table").str).toSet,
      Set(
        "researchers",
        "external-identities",
        "works",
        "topics",
        "institutions",
        "venues",
        "communities",
        "components",
      ),
    )
    vertices.foreach { v =>
      val cols = tableCols(v("table").str)
      assert(cols.contains(v("idColumn").str), v("table").str)
      assert(v("propertyColumns").arr.map(_.str).forall(cols.contains), v("table").str)
      assertEquals(v("windowed").bool, specOf(v("table").str).windowed)
    }
    assertEquals(
      edges.map(_("label").str).toSet,
      Set(
        "AUTHORED",
        "COAUTHORED_WITH",
        "RESOLVES_TO",
        "HAS_TOPIC",
        "PUBLISHED_IN",
        "MEMBER_OF",
        "IN_COMPONENT",
        "COMMUNITY_LINK",
      ),
    )
    edges.foreach { e =>
      val cols = tableCols(e("table").str)
      assert(cols.contains(e("fromColumn").str) && cols.contains(e("toColumn").str), e("table").str)
      assert(e("keyColumns").arr.map(_.str).forall(cols.contains), e("table").str)
      assert(e("propertyColumns").arr.map(_.str).forall(cols.contains), e("table").str)
      if !e("idColumn").isNull then assert(cols.contains(e("idColumn").str))
    }
    assertEquals(edges.find(_("label").str == "COAUTHORED_WITH").get("directed").bool, false)
    manifests("dynamodb")("tables").arr.map(_.obj).foreach { t =>
      val cols = tableCols(t("table").str)
      assert(cols.contains(t("partitionKey").str), t("table").str)
      assert(t("sortKey").arr.map(_.str).forall(cols.contains), t("table").str)
    }
    val doc = manifests("s3-vectors")("documents").arr.head.obj
    assertEquals(doc("table").str, "semantic-summaries")
    assert(
      Set(doc("textColumn").str, doc("textHashColumn").str)
        .subsetOf(tableCols("semantic-summaries"))
    )
    assert(doc("keyColumns").arr.map(_.str).forall(tableCols("semantic-summaries").contains))
    assert(doc("metadataColumns").arr.map(_.str).forall(tableCols("semantic-summaries").contains))
  }

  test("manifests hold row counts equal to the data, relative paths only, and no timestamps") {
    val json = manifests.values.map(m => ujson.write(m)).mkString("\n")
    assert(!json.contains(rootA.toString), "an absolute output path leaked into a manifest")
    assert(!json.contains("\"/"), "a manifest string starts with an absolute path")
    assert(!json.toLowerCase.contains("createdat") && !json.toLowerCase.contains("timestamp"))
    for
      key <- List("tables")
      t   <- manifests("dynamodb")(key).arr.map(_.obj)
    do
      assertEquals(
        t("rowCount").num.toLong,
        written.find(_.spec.name == t("table").str).get.rowCount,
      )
  }

  test("load manifests are written under load-manifests/ and read back identical") {
    val dir   = TestSpark.freshDir("manifests-out")
    val paths = LoadManifests.write(manifests, dir)
    assertEquals(
      paths.map(_.getFileName.toString).sorted,
      List("dynamodb.json", "neptune.json", "s3-vectors.json", "s3.json"),
    )
    paths.foreach { p =>
      val name = p.getFileName.toString.stripSuffix(".json")
      assertEquals(ujson.read(Files.readString(p, StandardCharsets.UTF_8)), manifests(name), name)
      assertEquals(p.getParent.getFileName.toString, "load-manifests")
    }
  }

  // ---- compare tool ---------------------------------------------------------------------------

  test("Compare reports no difference between the two exports of the same inputs") {
    val log   = scala.collection.mutable.ListBuffer.empty[String]
    val diffs = Compare.compare(spark, FixtureExports.a._1, FixtureExports.b._1, log += _)
    assertEquals(diffs, Nil)
    assertEquals(log.count(_.endsWith("EQUAL")), 30 + 30) // 30 tables and 30 provenance files
  }

  test(
    "Compare is not vacuous: a missing row, a missing table and a different value are reported"
  ) {
    val full                                   = FixtureRun.computed.tables("coreness")
    def one(df: DataFrame, name: String): Path =
      val dir = TestSpark.freshDir(name).resolve("out")
      Export.write(df, Tables.Coreness, dir, FixtureRun.cfg, run)
      dir
    val rootFull    = one(full, "cmp-full")
    val rootDropped = one(full.limit(full.count().toInt - 1), "cmp-dropped")
    val rootChanged = one(
      full.withColumn("coreness", F.when(F.col("coreness") === 4, 5).otherwise(F.col("coreness"))),
      "cmp-changed",
    )
    assertEquals(Compare.compare(spark, rootFull, rootFull, _ => ()), Nil)
    val d1 = Compare.compare(spark, rootFull, rootDropped, _ => ())
    assert(d1.exists(x => x.contains("coreness") && x.contains("rows A=")), d1.toString)
    val d2 = Compare.compare(spark, rootFull, rootChanged, _ => ())
    assert(d2.exists(_.contains("coreness differs")), d2.toString)
    val d3 = Compare.compare(spark, rootFull, FixtureExports.a._1, _ => ())
    assert(d3.exists(_.contains("missing in")), d3.toString)
  }

  test(
    "Compare ignores the run identity: the same data under another run id and code version is equal"
  ) {
    val full  = FixtureRun.computed.tables("coreness")
    val other = run.copy(
      runId = Ids.runId("spark-analytics", "other", 1, Map.empty, Nil).toOption.get,
      codeVersion = "other",
    )
    val d1 = TestSpark.freshDir("cmp-id-1").resolve("out");
    val d2 = TestSpark.freshDir("cmp-id-2").resolve("out")
    Export.write(full, Tables.Coreness, d1, FixtureRun.cfg, run)
    Export.write(full, Tables.Coreness, d2, FixtureRun.cfg, other)
    assertEquals(Compare.compare(spark, d1, d2, _ => ()), Nil)
  }
