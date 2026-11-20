package scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce.{Reducer, TaskAttemptID, TaskType}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import scholarchain.core.*
import UnitSupport.*

/** Shared helpers: SYNTHETIC files under modules/hadoop/target, no job launched. */
private object Repair02Support:
  def tmpDir(prefix: String): JPath =
    Files.createTempDirectory(repoRoot.resolve("modules/hadoop/target"), prefix)

  /** Reads every record of a file through the real reader: (key offset, record key, bytes). */
  def readAll(
      f: JPath,
      start: Long,
      length: Long,
  ): List[(Long, String, Array[Byte])] =
    val conf  = new Configuration()
    val ctx   = new TaskAttemptContextImpl(conf, new TaskAttemptID("unit", 1, TaskType.MAP, 0, 0))
    val split = new FileSplit(new Path(f.toUri), start, length, Array.empty[String])
    val r     = new DblpRecordInputFormat().createRecordReader(split, ctx)
    r.initialize(split, ctx)
    val KeyAttr = """key="([^"]+)"""".r
    val out     = scala.collection.mutable.ListBuffer.empty[(Long, String, Array[Byte])]
    try
      while r.nextKeyValue() do
        val bytes = r.getCurrentValue.copyBytes()
        val key   = KeyAttr.findFirstMatchIn(new String(bytes, StandardCharsets.UTF_8)).get.group(1)
        out += ((r.getCurrentKey.get, key, bytes))
    finally r.close()
    out.toList

/** F2: HJ3 output must not depend on the order of the values reaching the reducer. */
class Hj3ValueOrderUnitSuite extends munit.FunSuite:
  private val Doi  = "10.1000/order.x"
  private val Key  = s"doi|$Doi"
  private val refs = List(
    idRef("doi", Doi, "work:dblp:journals/unit/a", SourceType.Dblp),
    idRef("doi", Doi, s"work:crossref:$Doi", SourceType.Crossref),
    idRef("doi", Doi, s"work:openalex:W1", SourceType.OpenAlex),
    idRef("doi", Doi, s"work:orcid:O1", SourceType.Orcid),
  )

  private def run(order: List[String]): Reduced =
    reduce(new IdentifierReducer, order.map(r => (Key, r)))

  test("F2: unit: HJ3 rows are byte-identical for forward, reversed and rotated value order") {
    val forward = run(refs)
    val orders  =
      List(refs.reverse, refs.drop(2) ++ refs.take(2), List(refs(1), refs(3), refs(0), refs(2)))
    val rows = forward.table("exact-identifier-evidence")
    assertEquals(rows.size, 1)
    orders.foreach { o =>
      val r = run(o)
      assertEquals(r.table("exact-identifier-evidence"), rows)
      assertEquals(r.table("rejects"), forward.table("rejects"))
      assertEquals(r.count(PipelineCounter.EXACT_LINK), 1L)
    }
  }

  test("F2: unit: provenance.inputs are sorted by (inputId, version, checksum) in every order") {
    List(refs, refs.reverse, refs.drop(1) ++ refs.take(1)).foreach { o =>
      val ev     = ujson.read(run(o).table("exact-identifier-evidence").head._2)
      val inputs =
        ev("provenance")("inputs").arr.map(i => (i("inputId").str, i("version").str)).toList
      assertEquals(
        inputs.map(_._1),
        List("unit:crossref", "unit:dblp", "unit:dtd", "unit:openalex", "unit:orcid"),
      )
      assertEquals(inputs, inputs.sorted)
      assertEquals(
        ev("linkedRecordIds").arr.map(_.str).toList,
        List(
          "work:crossref:" + Doi,
          "work:dblp:journals/unit/a",
          "work:openalex:W1",
          "work:orcid:O1",
        ),
      )
    }
  }

/** F1: compressed DBLP input is one split read whole; keys are decompressed offsets. */
class CompressedDblpReaderUnitSuite extends munit.FunSuite:
  import Repair02Support.*

  private def gz(n: Int): (JPath, Long) =
    val f  = tmpDir("unit-gz-").resolve("dblp-synth.xml.gz")
    val sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n")
    (0 until n).foreach { i =>
      sb.append(
        f"""<article key="gz/R$i%04d"><author>Gz $i%04d</author><title>${"padding " * 12}</title><year>2020</year></article>""" + "\n"
      )
    }
    sb.append("</dblp>\n")
    val raw = sb.toString.getBytes(StandardCharsets.UTF_8)
    val out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(f))
    try out.write(raw)
    finally out.close()
    (f, raw.length.toLong)

  test(
    "F1: unit: every key of a gzipped file is read exactly once, including past the compressed length"
  ) {
    val (f, plain) = gz(500)
    val packed     = Files.size(f)
    assert(
      packed * 5 < plain,
      s"compressed $packed vs decompressed $plain is not compressible enough",
    )
    val recs = readAll(f, 0L, packed)
    assertEquals(recs.size, 500)
    assertEquals(recs.map(_._2), (0 until 500).map(i => f"gz/R$i%04d").toList)
    assertEquals(recs.map(_._2).distinct.size, 500)
    val beyond = recs.count(_._1 >= packed)
    assert(beyond > 400, s"only $beyond records start beyond the compressed length $packed")
    assertEquals(recs.map(_._1), recs.map(_._1).sorted)
    assertEquals(recs.map(_._1).distinct.size, 500)
  }

  test("F1: unit: a compressed file is not splitable") {
    val (f, _) = gz(5)
    val ctx    = new TaskAttemptContextImpl(
      new Configuration(),
      new TaskAttemptID("unit", 1, TaskType.MAP, 0, 0),
    )
    assertEquals(new DblpRecordInputFormat().isSplitable(ctx, new Path(f.toUri)), false)
  }

/** F8 at unit level: an unclosed record is cut at the next top-level start tag. */
class UnclosedRecordUnitSuite extends munit.FunSuite:
  import Repair02Support.*

  test("F8: unit: an unclosed record is cut before the next start tag; the next is read intact") {
    val text =
      "<dblp>\n<article key=\"u/A\"><author>Ann</author><title>never closed\n" +
        "<article key=\"u/B\"><author>Bob</author><title>T</title><year>2020</year></article>\n" +
        "<inproceedings key=\"u/C\"><author>Cy</author><title>T</title><year>2021</year></inproceedings>\n</dblp>\n"
    val f = tmpDir("unit-unclosed-").resolve("x.xml")
    Files.write(f, text.getBytes(StandardCharsets.UTF_8))
    val recs = readAll(f, 0L, Files.size(f))
    assertEquals(recs.map(_._2), List("u/A", "u/B", "u/C"))
    assertEquals(
      recs.map(_._1),
      List("<article key=\"u/A\"", "<article key=\"u/B\"", "<inproceedings key=\"u/C\"").map(s =>
        text.indexOf(s).toLong
      ),
    )
    val a = new String(recs(0)._3, StandardCharsets.UTF_8)
    assert(a.startsWith("<article key=\"u/A\""), a)
    assert(!a.contains("u/B") && !a.contains("</article>"), a)
    assertEquals(
      new String(recs(1)._3, StandardCharsets.UTF_8),
      "<article key=\"u/B\"><author>Bob</author><title>T</title><year>2020</year></article>",
    )
    assertEquals(
      new String(recs(2)._3, StandardCharsets.UTF_8),
      "<inproceedings key=\"u/C\"><author>Cy</author><title>T</title><year>2021</year></inproceedings>",
    )
    assertEquals(reason(parser.parse(recs(0)._3)), Some(RejectReason.MalformedRecord))
    assert(parser.parse(recs(1)._3).isRight)
  }

/** Drives `Dedupe.rows` with the values in the exact order given (no pre-sorting). */
class DedupeProbe(table: String) extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  var result: List[String]                                                         = Nil
  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    import scala.jdk.CollectionConverters.*
    result = Dedupe.rows(table, values.asScala.map(_.toString).toList, ctx)

/** F5: the HJ5 merge rule of JOB-CONTRACTS.md. */
class DedupeUnitSuite extends munit.FunSuite:
  private def in(id: String, v: String, cs: Option[String] = Some(Hex))       = InputRef(id, v, cs)
  private def prov(at: Option[String], inputs: List[InputRef], param: String) =
    Provenance(
      SchemaVersion.Current,
      SourceType.Orcid,
      inputs,
      "unit-test",
      Map("job" -> "unit", "tag" -> param),
      Some(runCtx.runId),
      at,
    )
  private def work(title: String, p: Provenance, id: String = "work:doi:10.1000/dd.x"): String =
    Wire
      .write(WorkRecord(SchemaVersion.Current, id, title, 2020, Some("10.1000/dd.x"), None, Nil, p))
      .toOption
      .get

  private def dedupe(
      order: List[String],
      table: String = "works",
  ): (List[String], Reduced, DedupeProbe) =
    val probe = new DedupeProbe(table)
    val r     = reduce(probe, order.map(j => ("k", j)))
    (probe.result, r, probe)

  private val early = work(
    "Early",
    prov(Some("2025-01-01T00:00:00Z"), List(in("orcid:b", "v1"), in("orcid:a", "v2")), "early"),
  )
  private val late = work(
    "Late",
    prov(Some("2026-01-01T00:00:00Z"), List(in("orcid:c", "v1"), in("orcid:a", "v2")), "late"),
  )
  private val none = work("NoTime", prov(None, List(in("orcid:z", "v9")), "none"))

  test(
    "F5: unit: the latest provenance.retrievedAt wins; inputs are the sorted union; order-independent"
  ) {
    val orders = List(
      List(early, late, none),
      List(late, early, none),
      List(none, late, early),
      List(early, none, late),
    )
    val outs = orders.map(dedupe(_))
    outs.foreach { (rows, r, _) =>
      assertEquals(rows.size, 1)
      val w = ujson.read(rows.head)
      assertEquals(w("title").str, "Late")
      assertEquals(w("provenance")("parameters")("tag").str, "late")
      assertEquals(w("provenance")("retrievedAt").str, "2026-01-01T00:00:00Z")
      assertEquals(
        w("provenance")("inputs").arr.map(i => (i("inputId").str, i("version").str)).toList,
        List(("orcid:a", "v2"), ("orcid:b", "v1"), ("orcid:c", "v1"), ("orcid:z", "v9")),
      )
      assertEquals(r.count(PipelineCounter.WORK_MERGED), 2L)
    }
    assertEquals(outs.map(_._1).distinct.size, 1)
  }

  test("F5: unit: equal retrievedAt: the larger JSON text wins, in either order") {
    val t           = Some("2026-02-02T00:00:00Z")
    val a           = work("Alpha", prov(t, List(in("orcid:a", "v1")), "x"))
    val b           = work("Bravo", prov(t, List(in("orcid:b", "v1")), "x"))
    val big         = if a > b then a else b
    val expectTitle = ujson.read(big)("title").str
    val r1          = dedupe(List(a, b))._1
    val r2          = dedupe(List(b, a))._1
    assertEquals(r1, r2)
    assertEquals(ujson.read(r1.head)("title").str, expectTitle)
    assertEquals(expectTitle, "Bravo")
    assertEquals(
      ujson.read(r1.head)("provenance")("inputs").arr.map(_("inputId").str).toList,
      List("orcid:a", "orcid:b"),
    )
  }

  test("F5: unit: a single row is passed through unchanged and nothing is counted") {
    val (rows, r, _) = dedupe(List(early))
    assertEquals(rows, List(early))
    assertEquals(r.count(PipelineCounter.WORK_MERGED), 0L)
  }

  test("F5: unit: institutions are merged by the same rule and counted as ENTITY_MERGED") {
    def inst(name: String, at: String, input: String) =
      Wire
        .write(
          Institution(
            SchemaVersion.Current,
            "institution:ror:x1",
            name,
            None,
            prov(Some(at), List(in(input, "v1")), name),
          )
        )
        .toOption
        .get
    val old = inst("Old Name", "2025-05-05T00:00:00Z", "orcid:i1")
    val nw  = inst("New Name", "2026-05-05T00:00:00Z", "orcid:i2")
    val a   = dedupe(List(old, nw), "institutions")
    val b   = dedupe(List(nw, old), "institutions")
    assertEquals(a._1, b._1)
    val j = ujson.read(a._1.head)
    assertEquals(j("name").str, "New Name")
    assertEquals(
      j("provenance")("inputs").arr.map(_("inputId").str).toList,
      List("orcid:i1", "orcid:i2"),
    )
    assertEquals(a._2.count(PipelineCounter.ENTITY_MERGED), 1L)
    assertEquals(a._2.count(PipelineCounter.WORK_MERGED), 0L)
  }

/** F9 and F4. */
class RejectsAndArgsUnitSuite extends munit.FunSuite:
  private val good = Array(
    "--dblp",
    "d.xml",
    "--dtd",
    "d.dtd",
    "--output",
    "out",
    "--snapshot",
    "s1",
  )

  test("F9: unit: Rejects.json throws on an invalid reject-v1 row and returns no text") {
    val bad = RejectRecord(
      SchemaVersion.Current,
      RejectRecord.SchemaName,
      "not-a-run-id",
      "unit",
      SourceType.Dblp,
      None,
      None,
      RejectReason.MalformedRecord,
      "m",
      "raw",
    )
    val e = intercept[IllegalStateException](Rejects.json(bad))
    assert(e.getMessage.startsWith("invalid reject-v1 row"), e.getMessage)
  }

  test("F9: unit: Rejects.json writes a valid row") {
    val ok = Rejects.make(
      runCtx,
      "unit",
      SourceType.Dblp,
      Some("k"),
      Some(7L),
      ParseFailure(RejectReason.MalformedRecord, "m", Some("k")),
      "raw",
    )
    val j = ujson.read(Rejects.json(ok))
    assertEquals(j("schema").str, "reject-v1")
    assertEquals(j("inputByteOffset").num.toLong, 7L)
  }

  test("F4: unit: a missing --code-version is refused") {
    PipelineArgs.parse(good) match
      case Left(m)  => assert(m.contains("missing --code-version"), m)
      case Right(a) => fail(s"accepted without --code-version: $a")
  }

  test("F4: unit: whitespace-only and embedded-whitespace code versions are refused") {
    List(" ", "   ", "\t", "a b", "").foreach { v =>
      assert(PipelineArgs.parse(good ++ Array("--code-version", v)).isLeft, s"accepted '$v'")
    }
  }

  test("F4: unit: a token code version is accepted and kept verbatim") {
    PipelineArgs.parse(good ++ Array("--code-version", "abc1234+uncommitted")) match
      case Right(a) => assertEquals(a.codeVersion, "abc1234+uncommitted")
      case Left(m)  => fail(m)
  }
