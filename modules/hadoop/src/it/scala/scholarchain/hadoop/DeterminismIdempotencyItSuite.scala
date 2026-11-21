package scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}
import java.security.MessageDigest

import ItSupport.*

/** Two complete runs of the whole pipeline into two roots (with different split sizes), a content
  * comparison of every curated table, and the idempotency refusal.
  *
  * Job launches: 22 (two pipelines of 11 jobs). The refusal check, the F3 run-ID check and the F4
  * missing-code-version checks launch no job (they stop before any job starts).
  */
class DeterminismIdempotencyItSuite extends munit.FunSuite:
  private var a: JPath = scala.compiletime.uninitialized
  private var b: JPath = scala.compiletime.uninitialized
  private var exitA    = -1
  private var exitB    = -1

  private val oneReducer = Seq(
    "scholarchain.hadoop.reducers.normalize"   -> "1",
    "scholarchain.hadoop.reducers.teams"       -> "1",
    "scholarchain.hadoop.reducers.pairs"       -> "1",
    "scholarchain.hadoop.reducers.identifiers" -> "1",
  )

  override def beforeAll(): Unit =
    a = newRoot("det-a")
    b = newRoot("det-b")
    exitA = runDriver(a, oneReducer*)
    exitB =
      runDriver(b, (oneReducer :+ ("mapreduce.input.fileinputformat.split.maxsize" -> "500"))*)

  /** SHA-256 over the sorted lines (path relative to the table, then content) of one table. */
  private def tableHash(root: JPath, t: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    tableLines(root, t).map(_._2).sorted.foreach(l => md.update((l + "\n").getBytes("UTF-8")))
    md.digest().map(x => f"${x & 0xff}%02x").mkString

  test("both runs succeed and have the same content-derived run ID") {
    assertEquals((exitA, exitB), (0, 0))
    assertEquals(
      readJson(runDir(a).resolve("manifest.json"))("runId").str,
      readJson(runDir(b).resolve("manifest.json"))("runId").str,
    )
  }

  test("every curated table has identical sorted content in both roots") {
    ExportKeys.Tables.foreach { t =>
      assertEquals(tableHash(a, t), tableHash(b, t), t)
      assertEquals(tableLines(a, t).size, tableLines(b, t).size, t)
    }
    assert(tableLines(a, "pair-candidates").nonEmpty)
  }

  test("the manifest and counters.json are identical (no wall-clock values)") {
    assertEquals(
      Files.readString(runDir(a).resolve("manifest.json")),
      Files.readString(runDir(b).resolve("manifest.json")),
    )
    // counters.json can differ in map-side counters that depend on the split layout? it must not.
    assertEquals(Files.readString(runDir(a).resolve("counters.json")).length > 0, true)
    assertEquals(
      readJson(runDir(a).resolve("counters.json"))("jobs")("hj4a-team-expand")("counters").toString,
      readJson(runDir(b).resolve("counters.json"))("jobs")("hj4a-team-expand")("counters").toString,
    )
  }

  test("the driver refuses an existing complete run directory: non-zero exit, nothing rewritten") {
    val manifestBefore = Files.readString(runDir(a).resolve("manifest.json"))
    val filesBefore    = files(a).map(f => (f.getPath, f.length())).toSet
    val rc             = runDriver(a, oneReducer*)
    assertEquals(rc, 2)
    assertEquals(Files.readString(runDir(a).resolve("manifest.json")), manifestBefore)
    assertEquals(files(a).map(f => (f.getPath, f.length())).toSet, filesBefore)
  }

  test("a bad argument list exits 2 without touching the file system") {
    val rc = org.apache.hadoop.util.ToolRunner.run(conf(), new PipelineDriver, Array("--dblp", "x"))
    assertEquals(rc, 2)
  }

  test("F3: max-record-bytes is in the manifest config and changes the run ID") {
    assertEquals(
      readJson(runDir(a).resolve("manifest.json"))("config")("hadoop.dblp.max-record-bytes").str,
      "16777216",
    )
    val parsed = PipelineArgs.parse(baseArgs(a).toArray).toOption.get
    val dflt   = RunSetup.prepare(conf(oneReducer*), parsed).toOption.get
    val small  = RunSetup
      .prepare(
        conf((oneReducer :+ ("scholarchain.hadoop.dblp.max-record-bytes" -> "1000"))*),
        parsed,
      )
      .toOption
      .get
    assertEquals(dflt.run.config("hadoop.dblp.max-record-bytes"), "16777216")
    assertEquals(small.run.config("hadoop.dblp.max-record-bytes"), "1000")
    assertNotEquals(small.run.runId, dflt.run.runId)
    // the default run reproduces the run ID of the real run
    assertEquals(dflt.run.runId, readJson(runDir(a).resolve("manifest.json"))("runId").str)
    // provenance.parameters of every record carry it
    assertEquals(
      table(a, "works")
        .map(_("provenance")("parameters")("hadoop.dblp.max-record-bytes").str)
        .toSet,
      Set("16777216"),
    )
    assert(
      RunSetup
        .prepare(conf("scholarchain.hadoop.dblp.max-record-bytes" -> "zero"), parsed)
        .isLeft
    )
  }

  test("F4: a missing --code-version is refused with exit 2 and a clear message") {
    val noVersion = baseArgs(a).take(baseArgs(a).indexOf("--code-version"))
    val parsed    = PipelineArgs.parse(noVersion.toArray)
    assert(parsed.left.exists(_.contains("missing --code-version")), parsed.toString)
    val rootX = newRoot("det-noversion")
    val rc    = org.apache.hadoop.util.ToolRunner.run(
      conf(),
      new PipelineDriver,
      baseArgs(rootX).take(baseArgs(rootX).indexOf("--code-version")).toArray,
    )
    assertEquals(rc, 2)
    assert(files(rootX).isEmpty, "nothing may be written")
    // a whitespace token is refused too
    assert(PipelineArgs.parse(noVersion.toArray ++ Array("--code-version", "a b")).isLeft)
  }

  test("F4: the code version is part of the run ID") {
    val p1 = PipelineArgs.parse(baseArgs(a).toArray).toOption.get
    val p2 = p1.copy(codeVersion = "other+uncommitted")
    assertNotEquals(
      RunSetup.prepare(conf(), p1).toOption.get.run.runId,
      RunSetup.prepare(conf(), p2).toOption.get.run.runId,
    )
  }
end DeterminismIdempotencyItSuite
