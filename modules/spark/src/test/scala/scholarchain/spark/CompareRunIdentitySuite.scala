package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions as F

import scholarchain.core.*

/** Two full exports of the extended fixture under DIFFERENT run identities (code-version label,
  * therefore run ID, output root, config origins), written like `Run` writes them: every table with
  * its `_provenance.json`, `run-summary.json`, `run-manifest.json` and the four load manifests.
  */
object CompareRuns:
  import TestSpark.spark

  final case class Export1(
      root: Path,
      cfg: SparkConfig,
      runId: String,
      run: RunInfo,
      computed: Computed,
  )

  private def manifestOf(cfg: SparkConfig, params: Map[String, String], refs: List[InputRef]) =
    RunManifest.build("spark-analytics", cfg.codeVersion, 1, params, refs) match
      case Right(m)  => m
      case Left(err) => throw new AssertionError(s"run manifest: $err")

  private def write(p: Path, json: ujson.Value): Unit =
    Files.writeString(p, ujson.write(json, indent = 2) + "\n", StandardCharsets.UTF_8)

  private def exportRun(
      name: String,
      cfg: SparkConfig,
      computed: Computed,
      runId: String,
      origin: String,
  ): Export1 =
    val inputs = ExtRun.inputs
    val params = Export.parameters(cfg)
    val run    = RunInfo(runId, cfg.codeVersion, inputs.refs, params)
    val root   = TestSpark.freshDir(name).resolve("out")
    Files.createDirectories(root)
    val written    = Tables.all.map(s => Export.write(computed.tables(s.name), s, root, cfg, run))
    val invariants = Invariants.check(computed, cfg, inputs)
    write(
      root.resolve("run-summary.json"),
      Report.build(computed, cfg, inputs, invariants, written),
    )
    write(
      root.resolve("run-manifest.json"),
      ujson.Obj(
        "manifestVersion" -> 1,
        "runManifest"     -> ujson.read(WirePickle.write(manifestOf(cfg, params, inputs.refs))),
        "outputRoot"      -> root.toString,
        "configOrigins"   -> ujson.Obj("spark.code-version" -> origin),
        "tables"          -> ujson.Arr.from(written.map(w => ujson.Str(w.spec.name))),
      ),
    )
    LoadManifests.write(LoadManifests.build(written, run), root)
    Export1(root, cfg, runId, run, computed)

  /** Run A: the shared extended run (default code-version label). */
  lazy val a: Export1 = exportRun("cmp-run-a", ExtRun.cfg, ExtRun.computed, ExtRun.runId, "default")

  /** Run B: the same inputs and parameters under another code-version label and run ID. */
  lazy val cfgB: SparkConfig =
    TestSpark.config(ExtRun.cfg.checkpointDir, Map("code-version" -> "label-b"))
  lazy val runIdB: String =
    manifestOf(cfgB, Export.parameters(cfgB), ExtRun.inputs.refs).runId
  lazy val b: Export1 =
    val c = Pipeline.compute(spark, cfgB, ExtFixture.loaded(spark), runIdB)
    exportRun("cmp-run-b", cfgB, c, runIdB, "override")
end CompareRuns

/** The Compare tool ignores the run identity (human decision, L-P1 fix) and still reports real
  * differences.
  */
class CompareRunIdentitySuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")

  private def spark = TestSpark.spark

  private def read(root: Path, rel: String): String =
    Files.readString(root.resolve(rel), StandardCharsets.UTF_8)

  // ---- two runs with different labels --------------------------------------------------------

  test(
    "the two fixture runs really carry different run identities (the comparison is not vacuous)"
  ) {
    val (x, y) = (CompareRuns.a, CompareRuns.b)
    assertNotEquals(x.run.codeVersion, y.run.codeVersion)
    assertNotEquals(x.run.runId, y.run.runId)
    // row-level provenance strings differ (run ID and code version), their data does not
    val pa = x.computed.tables("coauthor-edges").select("provenance").head().getString(0)
    val pb = y.computed.tables("coauthor-edges").select("provenance").head().getString(0)
    assertNotEquals(pa, pb)
    assert(pa.contains(x.runId) && pb.contains(y.runId))
    assert(pb.contains("label-b"), pb)
    // semantic-summaries.runId differs
    val ra = x.computed
      .tables("semantic-summaries")
      .select("runId")
      .distinct()
      .collect()
      .map(_.getString(0))
      .toList
    val rb = y.computed
      .tables("semantic-summaries")
      .select("runId")
      .distinct()
      .collect()
      .map(_.getString(0))
      .toList
    assertEquals(ra, List(x.runId))
    assertEquals(rb, List(y.runId))
    // the JSON companions differ as text
    // (run-summary.json carries no run identity at all, so it is equal as text and not listed)
    List("run-manifest.json", "scholarchain-graph/coreness/_provenance.json")
      .foreach(rel => assertNotEquals(read(x.root, rel), read(y.root, rel), rel))
  }

  test("two runs with different code-version labels compare with NO differences (all 30 tables)") {
    val log   = scala.collection.mutable.ListBuffer.empty[String]
    val diffs = Compare.compare(spark, CompareRuns.a.root, CompareRuns.b.root, log += _)
    assertEquals(diffs, Nil)
    // 30 tables, 30 _provenance.json, run-summary, run-manifest and the four load manifests
    assertEquals(log.count(_.endsWith("EQUAL")), 30 + 30 + 6, log.toString)
    List(
      "semantic-summaries: rows=",
      "run-manifest.json: EQUAL",
      "scholarchain-graph/coauthor-edges/_provenance.json: EQUAL",
    ).foreach(p => assert(log.exists(_.contains(p)), s"$p not in the log"))
    assert(log.exists(l => l.startsWith("coauthor-edges: rows=") && l.endsWith("EQUAL")))
    assert(log.exists(l => l.startsWith("semantic-summaries: rows=") && l.endsWith("EQUAL")))
  }

  // ---- a real difference is still reported ---------------------------------------------------

  private def one(df: DataFrame, spec: Tables.Spec, run: RunInfo, name: String): Path =
    val dir = TestSpark.freshDir(name).resolve("out")
    Export.write(df, spec, dir, ExtRun.cfg, run)
    dir

  private def provenanceOf(df: DataFrame): String = df.select("provenance").head().getString(0)

  private def edit(json: String)(f: ujson.Obj => Unit): String =
    val o = ujson.read(json).obj
    f(o)
    ujson.write(o)

  private val table = "resolves-to"
  private def dfA   = CompareRuns.a.computed.tables(table)
  private def dfB   = CompareRuns.b.computed.tables(table)

  test("control: single-table roots under different labels are equal") {
    val d1 = one(dfA, Tables.ResolvesTo, CompareRuns.a.run, "cmp1-a")
    val d2 = one(dfB, Tables.ResolvesTo, CompareRuns.b.run, "cmp1-b")
    assertEquals(Compare.compare(spark, d1, d2, _ => ()), Nil)
  }

  test("a changed INPUT CHECKSUM inside the row provenance is still reported") {
    val changed = edit(provenanceOf(dfB)) { o =>
      o("inputs")(0)("checksum") = "f" * 64
    }
    val d1 = one(dfA, Tables.ResolvesTo, CompareRuns.a.run, "chk-a")
    val d2 = one(
      dfB.withColumn("provenance", F.lit(changed)),
      Tables.ResolvesTo,
      CompareRuns.b.run,
      "chk-b",
    )
    val diffs = Compare.compare(spark, d1, d2, _ => ())
    assert(diffs.exists(_.contains(s"table $table differs")), diffs.toString)
    assertEquals(diffs.size, 1, diffs.toString) // only the table: the labels stay ignored
  }

  test("a changed INPUT CHECKSUM in _provenance.json is still reported") {
    val inputs = CompareRuns.b.run.inputs.map(i => i.copy(checksum = Some("e" * 64)))
    val d1     = one(dfA, Tables.ResolvesTo, CompareRuns.a.run, "chkj-a")
    val d2     = one(dfB, Tables.ResolvesTo, CompareRuns.b.run.copy(inputs = inputs), "chkj-b")
    val diffs  = Compare.compare(spark, d1, d2, _ => ())
    assertEquals(diffs.size, 1, diffs.toString)
    assert(
      diffs.head.contains("_provenance.json") && diffs.head.contains("differs"),
      diffs.toString,
    )
  }

  test("a changed ALGORITHM PARAMETER inside the row provenance is still reported") {
    val changed = edit(provenanceOf(dfB)) { o =>
      o("parameters")("spark.bfs-max-depth") = "99"
    }
    val d1 = one(dfA, Tables.ResolvesTo, CompareRuns.a.run, "par-a")
    val d2 = one(
      dfB.withColumn("provenance", F.lit(changed)),
      Tables.ResolvesTo,
      CompareRuns.b.run,
      "par-b",
    )
    val diffs = Compare.compare(spark, d1, d2, _ => ())
    assert(diffs.exists(_.contains(s"table $table differs")), diffs.toString)
    assertEquals(diffs.size, 1, diffs.toString)
  }

  test("a changed ALGORITHM PARAMETER in _provenance.json is still reported") {
    val params = CompareRuns.b.run.parameters.updated("spark.bfs-max-depth", "99")
    val d1     = one(dfA, Tables.ResolvesTo, CompareRuns.a.run, "parj-a")
    val d2     = one(dfB, Tables.ResolvesTo, CompareRuns.b.run.copy(parameters = params), "parj-b")
    val diffs  = Compare.compare(spark, d1, d2, _ => ())
    assertEquals(diffs.size, 1, diffs.toString)
    assert(diffs.head.contains("_provenance.json"), diffs.toString)
  }

  test("a changed DATA ROW is still reported") {
    val d1 = one(dfA, Tables.ResolvesTo, CompareRuns.a.run, "row-a")
    val d2 = one(
      dfB.withColumn(
        "orcid",
        F.when(F.col("orcid") === ExtFixture.OrcA, ExtFixture.OrcB).otherwise(F.col("orcid")),
      ),
      Tables.ResolvesTo,
      CompareRuns.b.run,
      "row-b",
    )
    val diffs = Compare.compare(spark, d1, d2, _ => ())
    assert(diffs.exists(_.contains(s"table $table differs")), diffs.toString)
  }

  test("a changed run-identity-free JSON field in a companion is still reported") {
    val d1  = one(dfA, Tables.ResolvesTo, CompareRuns.a.run, "rowc-a")
    val d2  = one(dfB, Tables.ResolvesTo, CompareRuns.b.run, "rowc-b")
    val rel = "scholarchain-graph/resolves-to/_provenance.json"
    val o   = ujson.read(read(d2, rel)).obj
    o("rowCount") = o("rowCount").num + 1
    Files.writeString(d2.resolve(rel), ujson.write(o, indent = 2), StandardCharsets.UTF_8)
    val diffs = Compare.compare(spark, d1, d2, _ => ())
    assertEquals(diffs.size, 1, diffs.toString)
  }

  // ---- stripProvenanceRun --------------------------------------------------------------------

  private val refs = List(InputRef("curated/authorship", "snapshot=t", Some("a" * 64)))
  private def provJson(codeVersion: String, runId: String, extra: Map[String, String]): String =
    V2Export.provenanceJson(
      refs,
      codeVersion,
      Map("spark.bfs-max-depth" -> "6", "spark.windows" -> "2000-2004") ++ extra,
      runId,
    )

  test("stripProvenanceRun removes runId and codeVersion and the run-identity parameters") {
    val extra = Map("spark.code-version" -> "label-x", "spark.output-root" -> "/tmp/x")
    val raw   = provJson("code-1", Ids.runId("s", "code-1", 1, Map.empty, Nil).toOption.get, extra)
    val o     = ujson.read(raw).obj
    assert(o.contains("runId") && o.contains("codeVersion"), raw)
    val out = ujson.read(Compare.stripProvenanceRun(raw)).obj
    assert(!out.contains("runId"), out.toString)
    assert(!out.contains("codeVersion"), out.toString)
    val params = out("parameters").obj
    assert(!params.contains("spark.code-version") && !params.contains("spark.output-root"))
    // the rest stays: inputs with checksums, source type, algorithm parameters
    assertEquals(out("inputs"), o("inputs"))
    assertEquals(out("inputs")(0)("checksum").str, "a" * 64)
    assertEquals(params("spark.bfs-max-depth").str, "6")
    assertEquals(params("spark.windows").str, "2000-2004")
    assertEquals(out("sourceType"), o("sourceType"))
  }

  test("stripProvenanceRun: strings that differ only in run fields become equal; others do not") {
    val x = provJson("code-1", Ids.runId("s", "code-1", 1, Map.empty, Nil).toOption.get, Map.empty)
    val y = provJson("code-2", Ids.runId("s", "code-2", 1, Map.empty, Nil).toOption.get, Map.empty)
    assertNotEquals(x, y)
    assertEquals(Compare.stripProvenanceRun(x), Compare.stripProvenanceRun(y))
    val rid = Ids.runId("s", "code-1", 1, Map.empty, Nil).toOption.get
    val z   = provJson("code-1", rid, Map("spark.bfs-max-depth" -> "7")) // a real parameter
    assertNotEquals(Compare.stripProvenanceRun(x), Compare.stripProvenanceRun(z))
    val w = V2Export.provenanceJson(
      List(InputRef("curated/authorship", "snapshot=t", Some("b" * 64))),
      "code-1",
      Map("spark.bfs-max-depth" -> "6", "spark.windows" -> "2000-2004"),
      rid,
    )
    assertNotEquals(Compare.stripProvenanceRun(x), Compare.stripProvenanceRun(w)) // a checksum
  }

  // one test per input, so that each failing input is visible on its own
  List("not json", "{broken", "", "run-id=abc codeVersion=x", "[1,2]", "42", "\"runId\"", "nul")
    .foreach { t =>
      test(s"stripProvenanceRun leaves the text unchanged: '$t'") {
        assertEquals(Compare.stripProvenanceRun(t), t)
      }
    }

  test("stripProvenanceRun: null stays null") {
    assertEquals(Compare.stripProvenanceRun(null), null)
  }
end CompareRunIdentitySuite
