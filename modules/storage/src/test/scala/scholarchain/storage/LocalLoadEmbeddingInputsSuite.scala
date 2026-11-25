package scholarchain.storage

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*

import scholarchain.core.*

import LocalLoadFx.*

/** Phase 14 Part A (M13-2): the additive `embedding-inputs/` output of `LocalLoad`, on the real
  * Phase 06 run `modules/spark/target/runs/sparkS1r2` (the same run the Phase 07 loader suites
  * use). Backward compatibility is checked against `LocalLoadBaseline`, the frozen source of
  * `LocalLoad` at git HEAD (a375c00) compiled under another name: the SAME run root goes through
  * both loaders and every pre-existing output is compared byte for byte. That is sound because the
  * baseline is the previous code itself, not a golden file that could be regenerated to match.
  *
  * These suites are skipped with an explicit message when the Phase 06 run is absent (it is
  * generated, not committed).
  */
class LocalLoadEmbeddingInputsSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 20.minutes

  private def src: Path =
    val s = sourceRun
    assume(s.isDefined, "modules/spark/target/runs/sparkS1r2 is not present: run Phase 06 first")
    s.get

  private def okOut(o: Out): Path =
    assert(o.result.isRight, o.result.toString)
    o.dir

  private val EmbedTables        = LocalLoad.EmbeddingTables
  private def embPath(t: String) = s"embedding-inputs/$t.jsonl"

  private def manifestOf(dir: Path): ujson.Obj =
    ujson.read(Files.readAllBytes(dir.resolve("load-manifest.json"))).obj match
      case o => ujson.Obj.from(o.toList)

  private def sums(dir: Path): Map[String, String] =
    text(Files.readAllBytes(dir.resolve("checksums.sha256")))
      .split("\n")
      .toList
      .filter(_.nonEmpty)
      .map { l =>
        val i = l.indexOf("  ")
        l.substring(i + 2) -> l.take(i)
      }
      .toMap

  // ---- 14. backward compatibility ----------------------------------------------------------------

  test("14. both loaders succeed on the same run root") {
    src
    okOut(new1)
    okOut(base)
  }

  test("14. the output tree gains exactly the four embedding-inputs files and nothing else") {
    src
    val n = shaTree(okOut(new1)).keySet
    val b = shaTree(okOut(base)).keySet
    assertEquals(n -- b, EmbedTables.map(embPath).toSet)
    assertEquals(b -- n, Set.empty[String], "a pre-existing output file disappeared")
  }

  test("14. neptune/** and dynamodb/** are byte-identical to the previous behavior") {
    src
    val n   = shaTree(okOut(new1))
    val b   = shaTree(okOut(base))
    val pre = b.keySet.filter(p => p.startsWith("neptune/") || p.startsWith("dynamodb/"))
    assert(pre.size > 10, s"suspiciously few files compared: ${pre.size}")
    pre.foreach(p => assertEquals(n.get(p), b.get(p), s"$p differs from the previous behavior"))
  }

  test("14. load-manifest.json differs only by the added embeddingInputs key (byte for byte)") {
    src
    val nd = okOut(new1)
    val bd = okOut(base)
    val n  = manifestOf(nd)
    val b  = manifestOf(bd)
    assertEquals(n.value.keySet.toSet -- b.value.keySet.toSet, Set("embeddingInputs"))
    assertEquals(b.value.keySet.toSet -- n.value.keySet.toSet, Set.empty[String])
    n.value.remove("embeddingInputs")
    val rebuilt = (ujson.write(n, indent = 2) + "\n").getBytes("UTF-8")
    assert(
      java.util.Arrays.equals(rebuilt, Files.readAllBytes(bd.resolve("load-manifest.json"))),
      "the manifest minus embeddingInputs is not byte-identical to the previous manifest",
    )
  }

  test("14. provenanceDocuments, lineage, input, parameters and loadRunId are unchanged") {
    src
    val n = manifestOf(okOut(new1))
    val b = manifestOf(okOut(base))
    List(
      "provenanceDocuments",
      "neptuneLineage",
      "input",
      "parameters",
      "loadRunId",
      "neptune",
      "dynamodb",
      "codeVersion",
      "loader",
    ).foreach { k =>
      assertEquals(ujson.write(n(k)), ujson.write(b(k)), s"manifest key $k changed")
    }
  }

  test(
    "14. checksums.sha256 differs only by the manifest line, the object-store lines and added lines"
  ) {
    src
    val n     = sums(okOut(new1))
    val b     = sums(okOut(base))
    val added = n.keySet -- b.keySet
    assertEquals(added, EmbedTables.map(embPath).toSet)
    assertEquals(b.keySet -- n.keySet, Set.empty[String])
    val changed = b.keySet.filter(p => n(p) != b(p))
    changed.foreach(p =>
      assert(
        p == "load-manifest.json" || p.startsWith("object-store/"),
        s"checksum of pre-existing file $p changed",
      )
    )
    assert(changed.contains("load-manifest.json"))
  }

  test(
    "14. object-store holds the same files; the published manifest is the new load-manifest.json"
  ) {
    src
    val nd = okOut(new1)
    val bd = okOut(base)
    val n  = tree(nd)
    val b  = tree(bd)
    assertEquals(
      n.keySet.filter(_.startsWith("object-store/")),
      b.keySet.filter(_.startsWith("object-store/")),
    )
    val published =
      n.keySet.filter(k => k.startsWith("object-store/data/") && k.endsWith("/load-manifest.json"))
    assertEquals(published.size, 1)
    assert(java.util.Arrays.equals(n(published.head), n("load-manifest.json")))
  }

  test("14. the printed summary is identical to the previous behavior") {
    src
    assertEquals(okOut(new1).toString.nonEmpty, true)
    assertEquals(new1.result, base.result)
  }

  // ---- 15. the four embedding-inputs files -------------------------------------------------------

  test("15. four files exist; sorted keys, LF only, no blank line, one JSON object per line") {
    src
    val d = okOut(new1)
    EmbedTables.foreach { t =>
      val p = d.resolve(embPath(t))
      assert(Files.isRegularFile(p), s"$t missing")
      val bytes = Files.readAllBytes(p)
      val s     = text(bytes)
      assert(s.nonEmpty && s.endsWith("\n"), s"$t does not end with LF")
      assert(!s.contains("\r"), s"$t contains CR")
      val lines = s.split("\n", -1).toList.dropRight(1)
      assert(lines.forall(_.nonEmpty), s"$t has a blank line")
      lines.foreach { l =>
        val o = ujson.read(l).obj
        assertEquals(o.keys.toList, o.keys.toList.sorted, s"$t: keys not ascending")
      }
    }
  }

  test("15. deterministic: two runs into two directories give a byte-identical tree") {
    src
    val a = shaTree(okOut(new1))
    val b = shaTree(okOut(new2))
    assertEquals(a, b)
  }

  test("15. no timestamps and no host paths in the files or in the manifest section") {
    src
    val d     = okOut(new1)
    val root  = src.toString
    val ts    = """\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}""".r
    val hosts =
      List(root, d.toString, "/Users/", "/tmp/", "/var/folders", System.getProperty("user.home"))
    val section = ujson.write(manifestOf(d)("embeddingInputs"))
    (EmbedTables.map(t =>
      t -> text(Files.readAllBytes(d.resolve(embPath(t))))
    ) :+ ("manifest" -> section)).foreach { (name, s) =>
      assertEquals(ts.findFirstIn(s), None, s"$name contains a timestamp")
      hosts.foreach(h => assert(!s.contains(h), s"$name contains the host path '$h'"))
    }
  }

  test("15. row counts equal the tables; every sha256 is in checksums.sha256 and in the manifest") {
    src
    val d   = okOut(new1)
    val r   = refs(src)
    val cs  = sums(d)
    val emb = manifestOf(d)("embeddingInputs")
    assertEquals(emb("files").arr.map(_("table").str).toList, EmbedTables)
    emb("files").arr.foreach { f =>
      val t     = f("table").str
      val bytes = Files.readAllBytes(d.resolve(embPath(t)))
      val lines = text(bytes).split("\n").count(_.nonEmpty).toLong
      assertEquals(lines, r(t).rowCount, s"$t: JSONL rows differ from the table row count")
      assertEquals(f("rows").num.toLong, r(t).rowCount, s"$t: manifest rows")
      assertEquals(f("path").str, embPath(t))
      assertEquals(f("sha256").str, Cells.sha256(bytes), s"$t: manifest sha256")
      assertEquals(cs.get(embPath(t)), Some(Cells.sha256(bytes)), s"$t: checksums.sha256")
    }
  }

  test("15. the manifest section carries the Phase 06 lineage of each table") {
    src
    val d   = okOut(new1)
    val r   = refs(src)
    val emb = manifestOf(d)("embeddingInputs")
    val vec = ujson.read(Files.readAllBytes(src.resolve("load-manifests/s3-vectors.json")))
    assertEquals(emb("sparkRunId").str, vec("runId").str)
    assertEquals(emb("sparkCodeVersion").str, vec("codeVersion").str)
    emb("files").arr.foreach { f =>
      val ref  = r(f("table").str)
      val prov = ParquetTables.provenancePath(src, ref)
      assertEquals(f("provenanceFileSha256").str, Cells.sha256(Files.readAllBytes(prov)))
      assertEquals(
        f("partFilesDigest").str,
        LocalLoad.tableChecksum(ParquetTables.tableDir(src, ref)).fold(e => fail(e), _._1),
      )
      assert(f("columns").arr.nonEmpty)
    }
  }

  // ---- 16. field mapping --------------------------------------------------------------------------

  private def lines(d: Path, t: String): List[ujson.Obj] =
    text(Files.readAllBytes(d.resolve(embPath(t))))
      .split("\n")
      .toList
      .filter(_.nonEmpty)
      .map(l => ujson.read(l).obj)
      .map(o => ujson.Obj.from(o.toList))

  test(
    "16. every JSONL line carries every table column; nulls are explicit, topicIds is an array, windows are integers"
  ) {
    src
    val d      = okOut(new1)
    val tables = parquetTables(src)
    EmbedTables.foreach { t =>
      val data = tables(t)
      val os   = lines(d, t)
      assertEquals(os.size, data.rows.size)
      os.zip(data.rows).foreach { (o, row) =>
        assertEquals(
          o.value.keySet.toSet,
          data.columns.map(_.name).toSet,
          s"$t: keys differ from the columns",
        )
        data.columns.zipWithIndex.foreach { (c, i) =>
          if row(i) == Cell.Null then
            assert(
              o.value.contains(c.name) && o(c.name).isNull,
              s"$t.${c.name}: a null must be an explicit null",
            )
        }
      }
    }
    val works = lines(d, "works")
    works.foreach { o =>
      assert(o.value.contains("doi") && (o("doi").isNull || o("doi").strOpt.isDefined), "doi")
      assert(
        o.value.contains("venueId") && (o("venueId").isNull || o("venueId").strOpt.isDefined),
        "venueId",
      )
      assert(
        o("topicIds").arrOpt.isDefined || o("topicIds").isNull,
        s"topicIds is not an array: ${o("topicIds")}",
      )
      o("topicIds").arrOpt.foreach(a => assert(a.forall(_.strOpt.isDefined)))
      assert(o("year").numOpt.exists(n => n == n.toInt.toDouble), "year is not an integer")
    }
    assert(
      works.exists(o => o("topicIds").arrOpt.exists(_.nonEmpty)),
      "no work with topics in the fixture",
    )
    val sums = lines(d, "semantic-summaries")
    sums.foreach { o =>
      List("windowStart", "windowEnd").foreach { k =>
        assert(o(k).numOpt.exists(n => n == n.toInt.toDouble), s"$k is not an integer")
      }
    }
    val raw = text(Files.readAllBytes(d.resolve(embPath("semantic-summaries"))))
    assert(
      "\"windowStart\":\\d{4}[,}]".r.findFirstIn(raw).isDefined,
      "windowStart is not written as an integer",
    )
    assertEquals("\"window(Start|End)\":\\d+\\.\\d".r.findFirstIn(raw), None)
    val rawWorks = text(Files.readAllBytes(d.resolve(embPath("works"))))
    assertEquals("\"year\":\\d+\\.\\d".r.findFirstIn(rawWorks), None)
  }

  // ---- 17. round trip -----------------------------------------------------------------------------

  private def prov = StorageFx.prov()

  private def sCell(t: TableData, r: Vector[Cell], c: String): String =
    t.cell(r, c).fold(e => fail(e), { case Cell.S(v) => v; case x => fail(s"$c: $x") })
  private def oCell(t: TableData, r: Vector[Cell], c: String): Option[String] =
    t.cell(r, c)
      .fold(
        e => fail(e),
        { case Cell.Null => None; case Cell.S(v) => Some(v); case x => fail(s"$c: $x") },
      )
  private def iCell(t: TableData, r: Vector[Cell], c: String): Int =
    t.cell(r, c)
      .fold(
        e => fail(e),
        { case Cell.I(v) => v; case Cell.L(v) => v.toInt; case x => fail(s"$c: $x") },
      )
  private def lCell(t: TableData, r: Vector[Cell], c: String): List[String] =
    t.cell(r, c)
      .fold(
        e => fail(e),
        {
          case Cell.Null    => Nil;
          case Cell.Arr(vs) => vs.map { case Cell.S(s) => s; case x => fail(s"$c: $x") };
          case x            => fail(s"$c: $x")
        },
      )

  private def jStr(o: ujson.Obj, k: String): String         = o(k).str
  private def jOpt(o: ujson.Obj, k: String): Option[String] =
    if o(k).isNull then None else Some(o(k).str)
  private def jInt(o: ujson.Obj, k: String): Int           = o(k).num.toInt
  private def jList(o: ujson.Obj, k: String): List[String] =
    if o(k).isNull then Nil else o(k).arr.toList.map(_.str)

  test(
    "17. the JSONL rebuilds the same records as the Parquet path (summaries, works, venues, topics)"
  ) {
    src
    val d      = okOut(new1)
    val tables = parquetTables(src)

    def sumsT                = tables("semantic-summaries")
    val fromParquetSummaries = sumsT.rows.toList.map(r =>
      SemanticSummary(
        1,
        sCell(sumsT, r, "entityId"),
        sCell(sumsT, r, "documentType"),
        sCell(sumsT, r, "textHash"),
        sCell(sumsT, r, "text"),
        GraphWindow(iCell(sumsT, r, "windowStart"), iCell(sumsT, r, "windowEnd")),
        sCell(sumsT, r, "runId"),
        prov,
      )
    )
    val fromJsonSummaries = lines(d, "semantic-summaries").map(o =>
      SemanticSummary(
        1,
        jStr(o, "entityId"),
        jStr(o, "documentType"),
        jStr(o, "textHash"),
        jStr(o, "text"),
        GraphWindow(jInt(o, "windowStart"), jInt(o, "windowEnd")),
        jStr(o, "runId"),
        prov,
      )
    )
    assert(fromParquetSummaries.nonEmpty)
    assertEquals(fromJsonSummaries, fromParquetSummaries)

    def wT               = tables("works")
    val fromParquetWorks = wT.rows.toList.map(r =>
      WorkRecord(
        1,
        sCell(wT, r, "workId"),
        sCell(wT, r, "title"),
        iCell(wT, r, "year"),
        oCell(wT, r, "doi"),
        oCell(wT, r, "venueId"),
        lCell(wT, r, "topicIds"),
        prov,
      )
    )
    val fromJsonWorks = lines(d, "works").map(o =>
      WorkRecord(
        1,
        jStr(o, "workId"),
        jStr(o, "title"),
        jInt(o, "year"),
        jOpt(o, "doi"),
        jOpt(o, "venueId"),
        jList(o, "topicIds"),
        prov,
      )
    )
    assert(fromParquetWorks.nonEmpty)
    assertEquals(fromJsonWorks, fromParquetWorks)

    def vT = tables("venues")
    assertEquals(
      lines(d, "venues").map(o => Venue(1, jStr(o, "venueId"), jStr(o, "name"), None, prov)),
      vT.rows.toList.map(r => Venue(1, sCell(vT, r, "venueId"), sCell(vT, r, "name"), None, prov)),
    )
    def tT = tables("topics")
    assertEquals(
      lines(d, "topics").map(o => Topic(1, jStr(o, "topicId"), jStr(o, "label"), prov)),
      tT.rows.toList.map(r => Topic(1, sCell(tT, r, "topicId"), sCell(tT, r, "label"), prov)),
    )
  }

  // ---- 18. without s3-vectors.json, and with a table missing --------------------------------------

  test(
    "18. without s3-vectors.json the output is byte-identical to the previous behavior, no embedding-inputs"
  ) {
    src
    val nd = okOut(newNoVec)
    val bd = okOut(baseNoVec)
    assertEquals(shaTree(nd), shaTree(bd))
    assert(!tree(nd).keySet.exists(_.startsWith("embedding-inputs")))
    assert(!manifestOf(nd).value.contains("embeddingInputs"))
    assertEquals(newNoVec.result, baseNoVec.result)
  }

  test(
    "18. with s3-vectors.json but a table missing from s3.json: fails with 'manifest has no table'"
  ) {
    val s    = src
    val root = variantRoot(
      "novenues",
      s,
      editS3 = j => j("tables").arr.filterInPlace(_("table").str != "venues"),
    )
    val out = runNew(root, "new-novenues")
    assertEquals(out.result, Left(List("manifest has no table venues")))
    assert(!Files.exists(out.dir), "a failed load must not leave an output directory")
  }

  test(
    "18. a table listed in neither s3-vectors documents nor s3.json tables: fails with 'manifest has no table'"
  ) {
    val s    = src
    val root = variantRoot(
      "nosummaries",
      s,
      editVectors = j => j("documents").arr.filterInPlace(_("table").str != "semantic-summaries"),
      editS3 = j => j("tables").arr.filterInPlace(_("table").str != "semantic-summaries"),
    )
    val out = runNew(root, "new-nosummaries")
    assertEquals(out.result, Left(List("manifest has no table semantic-summaries")))
    assert(!Files.exists(out.dir))
  }

  test("18. several missing tables are all reported") {
    val s    = src
    val root = variantRoot(
      "nomany",
      s,
      editS3 =
        j => j("tables").arr.filterInPlace(t => !Set("works", "topics").contains(t("table").str)),
    )
    val out = runNew(root, "new-nomany")
    assertEquals(
      out.result,
      Left(List("manifest has no table topics", "manifest has no table works")),
    )
    assert(!Files.exists(out.dir))
  }

  test("18. s3-vectors.json without s3.json fails and writes nothing") {
    val s    = src
    val root = variantRoot("nos3", s, dropS3 = true)
    val out  = runNew(root, "new-nos3")
    assert(out.result.isLeft, out.result.toString)
    assert(!Files.exists(out.dir))
  }
end LocalLoadEmbeddingInputsSuite
