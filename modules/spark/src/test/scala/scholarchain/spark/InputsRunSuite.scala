package scholarchain.spark

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions as F
import org.apache.spark.sql.types.MapType

import scholarchain.core.*

/** The thin file edge (`Inputs`) and the command line (`Run`). The fixture is written to a
  * temporary tree in the Hadoop layout (one table as gzip) and loaded back through `Inputs.load`.
  */
class InputsRunSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  private val spark = TestSpark.spark

  private def write(p: Path, lines: List[ujson.Value], gzip: Boolean = false): Unit =
    Files.createDirectories(p.getParent)
    val text = lines.map(ujson.write(_)).mkString("", "\n", "\n")
    if gzip then
      val out = new OutputStreamWriter(
        new GZIPOutputStream(Files.newOutputStream(p)),
        StandardCharsets.UTF_8,
      )
      try out.write(text)
      finally out.close()
    else Files.writeString(p, text, StandardCharsets.UTF_8)

  private def nz(s: String): ujson.Value = if s == null then ujson.Null else ujson.Str(s)

  private final case class Tree(root: Path, views: Path, decisions: Path)

  /** The fixture as files. `skip` leaves a table out; `extraRun` adds a second run_id directory. */
  private def tree(skip: Set[String] = Set.empty, extraRun: Option[String] = None): Tree =
    val base = TestSpark.freshDir("inputs-tree")
    val root = base.resolve("curated")
    def part(t: String, run: String = "r1", ext: String = "json") =
      root.resolve(t).resolve("snapshot=t").resolve(s"run_id=$run").resolve(s"part-00000.$ext")
    def put(t: String, lines: List[ujson.Value], gzip: Boolean = false) =
      if !skip.contains(t) then
        write(part(t, ext = if gzip then "json.gz" else "json"), lines, gzip)
    put(
      "author-mentions",
      Fixture.mentionRowsForFiles.map(r =>
        ujson.Obj(
          "mentionId"  -> r.s("mentionId"),
          "workId"     -> r.s("workId"),
          "position"   -> r.i("position"),
          "rawName"    -> r.s("rawName"),
          "orcid"      -> ujson.Null,
          "provenance" -> ujson.Obj("sourceType" -> "dblp"),
        )
      ),
    )
    put(
      "authorship",
      Fixture.authorshipRowsForFiles.map(r =>
        ujson.Obj(
          "mentionId" -> r.s("mentionId"),
          "workId"    -> r.s("workId"),
          "position"  -> r.i("position"),
          "year"      -> r.i("year"),
        )
      ),
      gzip = true,
    )
    put(
      "pair-candidates",
      Fixture.pairRowsForFiles.map(r =>
        ujson.Obj(
          "workId" -> r.s("workId"),
          "year"   -> r.i("year"),
          "pair"   -> ujson.Obj("first" -> r.s("first"), "second" -> r.s("second")),
        )
      ),
    )
    put(
      "works",
      Fixture.workRowsForFiles.map(r =>
        ujson.Obj(
          "workId"   -> r.s("workId"),
          "title"    -> r.s("title"),
          "year"     -> r.i("year"),
          "doi"      -> ujson.Null,
          "venueId"  -> nz(r.getString(r.fieldIndex("venueId"))),
          "topicIds" -> ujson.Arr.from(r.strs("topicIds").map(ujson.Str(_))),
        )
      ),
    )
    put(
      "teams",
      Fixture.teamRowsForFiles.map(r =>
        ujson.Obj(
          "workId"     -> r.s("workId"),
          "year"       -> r.i("year"),
          "teamSize"   -> r.i("teamSize"),
          "mentionIds" -> ujson.Arr.from(r.strs("mentionIds").map(ujson.Str(_))),
        )
      ),
    )
    put(
      "topics",
      List(
        ujson.Obj("topicId" -> Fixture.topicGraph, "label"    -> "Graph Mining"),
        ujson.Obj("topicId" -> Fixture.topicDatabase, "label" -> "Databases"),
      ),
    )
    extraRun.foreach(r =>
      write(
        part("works", r),
        List(
          ujson.Obj(
            "workId"   -> "work:dblp:t/X",
            "title"    -> "x",
            "year"     -> 2000,
            "doi"      -> ujson.Null,
            "venueId"  -> ujson.Null,
            "topicIds" -> ujson.Arr(),
          )
        ),
      )
    )
    val views = base.resolve("views")
    write(
      views.resolve("part-00000.json"),
      List(
        ujson.Obj("id" -> Fixture.workId("W01"), "kind" -> "work", "source" -> "dblp"),
        ujson.Obj("id" -> Fixture.extWork, "kind"       -> "work", "source" -> "openalex"),
      ),
    )
    val dec = base.resolve("match-decisions-final.jsonl")
    write(
      dec,
      Fixture.decisionRowsForFiles.map(r =>
        ujson.Obj(
          "leftId"        -> r.s("leftId"),
          "rightId"       -> r.s("rightId"),
          "entityKind"    -> r.s("entityKind"),
          "outcome"       -> r.s("outcome"),
          "policyVersion" -> r.s("policyVersion"),
          "extra"         -> "ignored",
        )
      ),
    )
    Tree(root, views, dec)

  private def cfgFor(t: Tree): SparkConfig =
    TestSpark.config(
      TestSpark.useCheckpointFor("inputs"),
      Map(
        "input.root-i-dir"                 -> t.root.toString,
        "input.hj6a-entity-views-dir"      -> t.views.toString,
        "input.match-decisions-final-path" -> t.decisions.toString,
      ),
    )

  private def loaded(t: Tree): Inputs.LoadedInputs =
    Inputs.load(spark, cfgFor(t)) match
      case Right(l)  => l
      case Left(msg) => fail(s"load failed: $msg")

  private lazy val t0  = tree()
  private lazy val in0 = loaded(t0)

  private def comparable(df: DataFrame): DataFrame =
    df.schema.fields.foldLeft(df) { (d, f) =>
      f.dataType match
        case _: MapType => d.withColumn(f.name, F.to_json(F.col(f.name)))
        case _          => d
    }

  // ---- Inputs.load ----------------------------------------------------------------------------

  test("Inputs.load flattens the nested Hadoop rows and reads plain and gzip JSON Lines") {
    assertEquals(in0.rowCounts("author-mentions"), 26L)
    assertEquals(in0.rowCounts("authorship"), 26L) // the gzip table
    assertEquals(in0.rowCounts("pair-candidates"), 23L)
    assertEquals(in0.rowCounts("works"), 12L)
    assertEquals(in0.rowCounts("teams"), 11L)
    assertEquals(in0.rowCounts("topics"), 2L)
    assertEquals(in0.rowCounts("match-decisions-final"), 1L)
    assertEquals(in0.rowCounts("hj6a-entity-views"), 2L)
    assertEquals(in0.mentions.columns.toList, InputSchemas.Mentions.fieldNames.toList)
    assertEquals(in0.pairs.columns.toList, InputSchemas.PairCandidates.fieldNames.toList)
    assertEquals(in0.mentions.where("sourceType = 'dblp'").count(), 26L)
    assertEquals(in0.pairs.where(F.col("first") >= F.col("second")).count(), 0L)
  }

  test("absent optional tables are empty and listed; the required ones are present") {
    assertEquals(in0.absent.toSet, Set("institutions", "person-aliases"))
    assertEquals(in0.rowCounts("institutions"), 0L)
    assertEquals(in0.rowCounts("person-aliases"), 0L)
    assertEquals(in0.aliases.schema, InputSchemas.Aliases)
  }

  test(
    "every input is recorded with a version and a checksum; decisions by the hash of their bytes"
  ) {
    val refs = in0.refs.map(r => r.inputId -> r).toMap
    assertEquals(
      refs.keySet,
      Set(
        "curated/author-mentions",
        "curated/authorship",
        "curated/pair-candidates",
        "curated/works",
        "curated/teams",
        "curated/topics",
        "hj6a/entity-views",
        "er/match-decisions-final",
      ),
    )
    assertEquals(refs("curated/authorship").version, "snapshot=t;run_id=r1")
    assertEquals(refs("hj6a/entity-views").version, "unversioned")
    assert(in0.refs.forall(_.checksum.exists(Hashing.isSha256Hex)), in0.refs.toString)
    assertEquals(
      refs("er/match-decisions-final").checksum,
      Some(Hashing.sha256Hex(Files.readAllBytes(t0.decisions))),
    )
    assertEquals(in0.refs.map(_.inputId), in0.refs.map(_.inputId).sorted)
  }

  test("checksums are stable across loads and change when a file changes") {
    assertEquals(loaded(t0).refs, in0.refs)
    val t1 = tree()
    assertEquals(loaded(t1).refs, in0.refs) // the same content in another directory
    val part = t1.root
      .resolve("topics")
      .resolve("snapshot=t")
      .resolve("run_id=r1")
      .resolve("part-00000.json")
    Files.writeString(
      part,
      Files.readString(part) + ujson.write(
        ujson.Obj("topicId" -> "topic:new", "label" -> "New")
      ) + "\n",
    )
    val changed = loaded(t1).refs.map(r => r.inputId -> r.checksum).toMap
    assertNotEquals(
      changed("curated/topics"),
      in0.refs.find(_.inputId == "curated/topics").get.checksum,
    )
    assertEquals(changed("curated/works"), in0.refs.find(_.inputId == "curated/works").get.checksum)
  }

  test("a missing required table, entity views or decisions file is refused with a clear message") {
    val noAuthorship = Inputs.load(spark, cfgFor(tree(skip = Set("authorship"))))
    assert(
      noAuthorship.left.exists(_.contains("required input table 'authorship' is missing")),
      noAuthorship.toString,
    )
    val t       = tree()
    val c       = cfgFor(t)
    val noViews = Inputs.load(
      spark,
      TestSpark.config(
        c.checkpointDir,
        Map(
          "input.root-i-dir"                 -> t.root.toString,
          "input.hj6a-entity-views-dir"      -> t.views.resolve("nope").toString,
          "input.match-decisions-final-path" -> t.decisions.toString,
        ),
      ),
    )
    assert(noViews.left.exists(_.contains("entity views not found")), noViews.toString)
    val noDec = Inputs.load(
      spark,
      TestSpark.config(
        c.checkpointDir,
        Map(
          "input.root-i-dir"                 -> t.root.toString,
          "input.hj6a-entity-views-dir"      -> t.views.toString,
          "input.match-decisions-final-path" -> t.decisions.resolveSibling("none.jsonl").toString,
        ),
      ),
    )
    assert(noDec.left.exists(_.contains("match decisions not found")), noDec.toString)
  }

  test("a table with two run_id directories is refused (it would double-count rows)") {
    val r = Inputs.load(spark, cfgFor(tree(extraRun = Some("r2"))))
    assert(r.left.exists(_.contains("more than one run_id")), r.toString)
  }

  test("versionOf and treeChecksum: version from the path, checksum over sorted path:hash lines") {
    val base = TestSpark.freshDir("version")
    val f1   = base.resolve("snapshot=s1/run_id=ab/part-00000.json")
    val f2   = base.resolve("snapshot=s1/run_id=ab/part-00001.json")
    write(f1, List(ujson.Obj("a" -> 1))); write(f2, List(ujson.Obj("a" -> 2)))
    assertEquals(Inputs.versionOf(base, List(f1, f2)), Right("snapshot=s1;run_id=ab"))
    assertEquals(Inputs.versionOf(base, Nil), Right("unversioned"))
    val f3 = base.resolve("snapshot=s1/run_id=cd/part-00000.json");
    write(f3, List(ujson.Obj("a" -> 3)))
    assert(Inputs.versionOf(base, List(f1, f3)).isLeft)
    val expected = Hashing.sha256Hex(
      List(
        s"snapshot=s1/run_id=ab/part-00000.json:${Hashing.sha256Hex(Files.readAllBytes(f1))}",
        s"snapshot=s1/run_id=ab/part-00001.json:${Hashing.sha256Hex(Files.readAllBytes(f2))}",
      ).sorted.mkString("\n").getBytes(StandardCharsets.UTF_8)
    )
    assertEquals(Inputs.treeChecksum(base, List(f2, f1)), expected)
  }

  test(
    "the fixture loaded through the file edge gives the same output tables as the frame fixture (row provenance aside)"
  ) {
    val c      = Pipeline.compute(spark, cfgFor(t0), in0, FixtureRun.runId)
    val direct = FixtureRun.computed
    assertEquals(c.tables.keySet, direct.tables.keySet)
    // The row `provenance` string records the INPUT refs (checksums of the files vs the in-memory
    // fixture), which legitimately differ between the two loads; every other column must be equal.
    def noProv(df: DataFrame): DataFrame =
      if df.columns.contains("provenance") then df.drop("provenance") else df
    // the files carry no institutions table, so that one table is legitimately empty
    Tables.all.map(_.name).filterNot(_ == "institutions").foreach { n =>
      val x = comparable(noProv(c.tables(n))); val y = comparable(noProv(direct.tables(n)))
      assertEquals(x.count(), y.count(), n)
      assertEquals(x.exceptAll(y).count(), 0L, n)
      assertEquals(y.exceptAll(x).count(), 0L, n)
    }
    assertEquals(c.tables("institutions").count(), 0L)
    assertEquals(c.identityCounts, direct.identityCounts)
    // provenance is present and parseable on every row of every table that has the column, and
    // it names the run of ITS run (one value per run)
    val withProv = Tables.all.filter(_.columns.contains("provenance")).map(_.name)
    assertEquals(withProv.toSet, Set("coauthor-edges", "researcher-metrics", "resolves-to"))
    withProv.foreach { n =>
      List(c, direct).foreach { run =>
        val vals =
          run.tables(n).select("provenance").distinct().collect().toList.map(_.getString(0))
        // the fixture has no ORCIDs, so its resolves-to table is empty; the others have one value
        assertEquals(
          vals.size,
          if n == "resolves-to" then 0 else 1,
          s"$n: provenance values per run",
        )
        vals.foreach { v =>
          assert(v != null, n)
          val p = provOf(v)
          assertEquals(p.sourceType, SourceType.ScholarChain, n)
          assertEquals(p.runId, Some(FixtureRun.runId), n)
          assert(p.inputs.nonEmpty, n)
        }
      }
    }
  }

  private def provOf(json: String): Provenance = Wire.read[Provenance](json) match
    case Right(a)  => a
    case Left(err) => fail(s"provenance does not parse: $err")

  // ---- Run (command line) ---------------------------------------------------------------------

  test("Run.parseArgs maps flags to scholarchain.spark overrides") {
    val r = Run.parseArgs(
      List(
        "--master",
        "local[2]",
        "--output-root",
        "/o",
        "--code-version",
        "abc+phase06",
        "--checkpoint-dir",
        "/c",
        "--set",
        "bfs-max-depth=5",
        "--set",
        "windows=2000-2004",
      )
    )
    assertEquals(
      r,
      Right(
        Run.Args(
          Some("local[2]"),
          Map(
            "scholarchain.spark.output-root"    -> "/o",
            "scholarchain.spark.code-version"   -> "abc+phase06",
            "scholarchain.spark.checkpoint-dir" -> "/c",
            "scholarchain.spark.bfs-max-depth"  -> "5",
            "scholarchain.spark.windows"        -> "2000-2004",
          ),
        )
      ),
    )
    assertEquals(Run.parseArgs(Nil), Right(Run.Args(None, Map.empty)))
    assertEquals(
      Run.parseArgs(List("--set", "a=b=c")).map(_.overrides),
      Right(Map("scholarchain.spark.a" -> "b=c")),
    )
  }

  test("Run.parseArgs rejects unknown, incomplete and malformed arguments") {
    assert(Run.parseArgs(List("--bogus")).isLeft)
    assert(Run.parseArgs(List("--output-root")).isLeft)
    assert(Run.parseArgs(List("--set", "no-equals-sign")).isLeft)
    assert(Run.parseArgs(List("positional")).isLeft)
  }

  test(
    "Run.run exits 2 on bad arguments, on invalid configuration and on an existing output root"
  ) {
    assertEquals(Run.run(List("--nope")), 2)
    assertEquals(Run.run(List("--set", "bfs-max-depth=0")), 2)   // depth must be > 0
    assertEquals(Run.run(List("--set", "windows=2005-2000")), 2) // invalid window
    val existing = TestSpark.freshDir("run-existing")
    val code     = Run.run(List("--output-root", existing.toString))
    assertEquals(code, 2) // a run never overwrites
    assert(Files.exists(existing))
    assert(!spark.sparkContext.isStopped, "the refused run must not have touched the session")
  }
