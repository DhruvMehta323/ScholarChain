package scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.TaskAttemptID
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

import Hj2RunSupport.*
import ItSupport.{fixture, newRoot}

/** HJ2 packed-input (`*.envelopes.jsonl`) tests. Synthetic data only. The golden files under
  * `src/test/resources/hj2-golden` are the output of HJ2 at commit f5cc5f133415 (before the packed
  * input change); see the report of the run that created them for how.
  */
class Hj2PackedInputSuite extends munit.FunSuite:
  private val Utf8      = StandardCharsets.UTF_8
  private val CrossrefN =
    List("c1-refetch.json", "c1.json", "c2.json", "c3-bad.json", "c4-missing.json")
  private val OpenAlexN = List("o1.json", "o2-notitle.json")
  private val OrcidN    = List("p1.json", "p2.json", "p3-bad.json", "p4-shared-doi.json")
  private val Tables    = List("works", "author-mentions", "institutions", "topics", "id-refs")

  private def gunzip(b: Array[Byte]): Array[Byte] =
    val in = new GZIPInputStream(new ByteArrayInputStream(b))
    try in.readAllBytes()
    finally in.close()

  private def rows(cap: Map[String, Array[Byte]], table: String): List[(String, ujson.Value)] =
    cap.get(s"tables/$table/part-r-00000.gz").toList.flatMap { b =>
      new String(gunzip(b), Utf8).split("\n").toList.filter(_.nonEmpty).map { l =>
        val i = l.indexOf('\t')
        (l.substring(0, i), ujson.read(l.substring(i + 1)))
      }
    }

  private def noProv(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj =>
      ujson.Obj.from(o.value.toList.filter(_._1 != "provenance").map((k, x) => k -> noProv(x)))
    case a: ujson.Arr => ujson.Arr.from(a.value.map(noProv))
    case other        => other

  private def provNames(v: ujson.Value): List[String] =
    v("provenance")("inputs").arr.map(_("inputId").str).toList

  /** A minimal valid Crossref envelope, one line. */
  private def env(
      doi: String,
      at: String = "2026-01-01T00:00:00Z",
      title: String = "Packed T",
  ): String =
    ujson.write(
      ujson.Obj(
        "identifier"   -> doi,
        "request_url"  -> "https://example.invalid/x",
        "retrieved_at" -> at,
        "http_status"  -> 200,
        "missing"      -> false,
        "body_is_json" -> true,
        "body"         -> ujson.Obj(
          "status"  -> "ok",
          "message" -> ujson.Obj(
            "DOI"    -> doi,
            "title"  -> ujson.Arr(title),
            "issued" -> ujson.Obj("date-parts" -> ujson.Arr(ujson.Arr(2020, 1, 1))),
            "author" -> ujson.Arr(ujson.Obj("given" -> "Ann", "family" -> "Tester")),
          ),
        ),
      )
    )

  private def packedDir(label: String, files: (String, String)*): JPath =
    val d = newRoot(label)
    files.foreach((n, t) => write(d.resolve(n), t))
    d

  // 1. golden, byte-identical to the pre-change output ------------------------------------------

  test(
    "golden: single-file envelopes give byte-identical HJ2 output, counters and input checksums"
  ) {
    val golden = UnitSupport.repoRoot.resolve("modules/hadoop/src/test/resources/hj2-golden")
    val files  = Files.walk(golden).iterator().asScala.filter(Files.isRegularFile(_)).toList
    val expect = files.map(p => golden.relativize(p).toString -> Files.readAllBytes(p)).toMap
    val actual = capture(fixtureRun("golden"))
    assertEquals(
      actual.keySet,
      expect.keySet,
      "the set of output files differs from the golden set",
    )
    expect.keys.toList.sorted.foreach { n =>
      if n.endsWith(".gz") then
        assertEquals(
          new String(gunzip(actual(n)), Utf8),
          new String(gunzip(expect(n)), Utf8),
          s"$n (decompressed text)",
        )
      else assertEquals(new String(actual(n), Utf8), new String(expect(n), Utf8), n)
      assertEquals(sha256Hex(actual(n)), sha256Hex(expect(n)), s"$n (exact bytes)")
    }
  }

  // 2. packed equivalence -----------------------------------------------------------------------

  private lazy val single    = capture(fixtureRun("eq-single"))
  private lazy val packedRun =
    val dir = (src: String, names: List[String]) =>
      packedDir(
        s"eq-$src",
        s"$src.envelopes.jsonl" -> packLines(fixture(src), names).mkString("", "\n", "\n"),
      )
    run(
      "eq-packed",
      Some(dir("crossref", CrossrefN)),
      Some(dir("openalex", OpenAlexN)),
      Some(dir("orcid", OrcidN)),
    )
  private lazy val packed = capture(packedRun)

  test("packed: the same envelopes one per line give the same rows (provenance aside)") {
    Tables.foreach { t =>
      val a = rows(single, t).map((k, v) => (k, noProv(v)))
      val b = rows(packed, t).map((k, v) => (k, noProv(v)))
      assert(a.nonEmpty, s"fixture run has no $t rows")
      assertEquals(b, a, t)
    }
    assertEquals(packedRun.outcome.rows.getOrElse("rejects", 0L), 3L)
    assertEquals(packedRun.outcome.c(PipelineCounter.ACCEPTED), 6L)
    assertEquals(packedRun.outcome.c(PipelineCounter.INPUT_RECORDS), 11L)
  }

  test("packed: provenance carries `<file>#<line>` and the SHA-256 of the line bytes") {
    val lines = packLines(fixture("crossref"), CrossrefN)
    val n     = CrossrefN.sorted.indexOf("c1-refetch.json") + 1 // the winner of synth.alpha
    val w     = rows(packed, "works").find(_._1 == "work:crossref:10.1000/synth.alpha").get._2
    val inp   = w("provenance")("inputs").arr.find(_("inputId").str.contains("#")).get
    assertEquals(inp("inputId").str, s"crossref.envelopes.jsonl#$n")
    assertEquals(inp("checksum").str, sha256Hex(lines(n - 1).getBytes(Utf8)))
    rows(packed, "works").foreach((_, v) =>
      assert(provNames(v).exists(_.matches(""".+\.envelopes\.jsonl#\d+""")))
    )
  }

  // 3. reader -----------------------------------------------------------------------------------

  private def readAll(p: JPath): List[(String, String)] =
    val path  = new Path(p.toUri)
    val split = new FileSplit(path, 0, Files.size(p), Array.empty[String])
    val ctx   = new TaskAttemptContextImpl(new Configuration(), new TaskAttemptID())
    val rr    = new EnvelopeInputFormat().createRecordReader(split, ctx)
    rr.initialize(split, ctx)
    val out = scala.collection.mutable.ListBuffer.empty[(String, String)]
    try
      while rr.nextKeyValue() do
        out += ((rr.getCurrentKey.toString, new String(rr.getCurrentValue.copyBytes(), Utf8)))
    finally rr.close()
    out.toList

  test(
    "reader: key is `<path>#<n>`; empty lines are skipped but counted; no trailing newline is read"
  ) {
    val p =
      write(newRoot("rd").resolve("a.envelopes.jsonl"), "{\"a\":1}\n\n{\"b\":2}\n\n\n{\"c\":3}")
    val k = new Path(p.toUri).toString
    assertEquals(
      readAll(p),
      List(s"$k#1" -> "{\"a\":1}", s"$k#3" -> "{\"b\":2}", s"$k#6" -> "{\"c\":3}"),
    )
  }

  test("reader: an empty file gives no record; a blank-lines-only file gives none") {
    assertEquals(readAll(write(newRoot("rd0").resolve("e.envelopes.jsonl"), "")), Nil)
    assertEquals(readAll(write(newRoot("rd1").resolve("n.envelopes.jsonl"), "\n\n\n")), Nil)
  }

  test("reader: a single-file envelope is still read whole (key = path, value = file bytes)") {
    val p = write(newRoot("rd2").resolve("w.json"), "{\n \"a\": 1\n}\n")
    assertEquals(readAll(p), List(new Path(p.toUri).toString -> "{\n \"a\": 1\n}\n"))
  }

  test("reader: the input format never splits a file") {
    val p = write(newRoot("rd3").resolve("s.envelopes.jsonl"), "{}\n")
    val c = new Configuration()
    assert(
      !new EnvelopeInputFormat().isSplitable(
        new org.apache.hadoop.mapreduce.task.JobContextImpl(
          c,
          new org.apache.hadoop.mapreduce.JobID(),
        ),
        new Path(p.toUri),
      )
    )
  }

  // 4. bad lines --------------------------------------------------------------------------------

  test(
    "bad lines: a non-JSON line and a bad retrieved_at are rejected alone, the good lines pass"
  ) {
    val text = List(
      env("10.1000/pk.1"),
      "this is not json",
      env("10.1000/pk.2", at = "yesterday"),
      env("10.1000/pk.3"),
    ).mkString("", "\n", "\n")
    val r   = run("bad", Some(packedDir("bad-in", "f.envelopes.jsonl" -> text)))
    val cap = capture(r)
    assertEquals(r.outcome.c(PipelineCounter.INPUT_RECORDS), 4L)
    assertEquals(r.outcome.c(PipelineCounter.REJECTED), 2L)
    assertEquals(r.outcome.c(PipelineCounter.ACCEPTED), 2L)
    assertEquals(r.outcome.rejects, Map("malformed_record" -> 1L, "schema_violation" -> 1L))
    assertEquals(
      rows(cap, "works").map(_._1),
      List("work:crossref:10.1000/pk.1", "work:crossref:10.1000/pk.3"),
    )
    val rej = rows(cap, "rejects")
    assertEquals(
      rej.map(_._1),
      List("reject|crossref|f.envelopes.jsonl#2", "reject|crossref|f.envelopes.jsonl#3"),
    )
    assertEquals(rej.map(_._2("reasonCode").str), List("malformed_record", "schema_violation"))
  }

  // 5. mixed directory --------------------------------------------------------------------------

  test(
    "mixed directory: single .json files and a packed file are both processed; errors.jsonl is skipped"
  ) {
    val dir = packedDir(
      "mixed",
      "s1.json"           -> env("10.1000/mx.single"),
      "p.envelopes.jsonl" -> (env("10.1000/mx.p1") + "\n" + env("10.1000/mx.p2") + "\n"),
      "errors.jsonl"      -> "{\"identifier\":\"x\",\"error\":\"boom\"}\n",
    )
    val r   = run("mixed-run", Some(dir))
    val cap = capture(r)
    assertEquals(r.outcome.c(PipelineCounter.INPUT_RECORDS), 3L)
    assertEquals(r.outcome.c(PipelineCounter.REJECTED), 0L)
    assertEquals(
      rows(cap, "works").map(_._1),
      List(
        "work:crossref:10.1000/mx.p1",
        "work:crossref:10.1000/mx.p2",
        "work:crossref:10.1000/mx.single",
      ),
    )
    val names = rows(cap, "works").map(r => provNames(r._2).last).toSet
    assertEquals(names, Set("s1.json", "p.envelopes.jsonl#1", "p.envelopes.jsonl#2"))
  }

  // 6. dedup across packed lines ----------------------------------------------------------------

  test(
    "dedup: the same DOI on two packed lines gives one winner by retrieved_at and one duplicates row"
  ) {
    val text = env("10.1000/dd.x", at = "2026-01-01T00:00:00Z", title = "Old") + "\n\n" +
      env("10.1000/dd.x", at = "2026-03-01T00:00:00Z", title = "New") + "\n"
    val r   = run("dd", Some(packedDir("dd-in", "d.envelopes.jsonl" -> text)))
    val cap = capture(r)
    val w   = rows(cap, "works")
    assertEquals(w.map(_._2("title").str), List("New"))
    assertEquals(provNames(w.head._2).last, "d.envelopes.jsonl#3")
    val d = rows(cap, "duplicates")
    assertEquals(d.map(_._1), List("crossref|10.1000/dd.x|d.envelopes.jsonl#1"))
    assertEquals(d.head._2("winner").str, "d.envelopes.jsonl#3")
    assertEquals(d.head._2("duplicate").str, "d.envelopes.jsonl#1")
    assertEquals(r.outcome.c(PipelineCounter.DUPLICATE), 1L)
    assertEquals(r.outcome.c(PipelineCounter.ACCEPTED), 1L)
  }

  // 7. driver input checksum --------------------------------------------------------------------

  private def checksum(dir: JPath): (String, String) =
    val s = prepare(newRoot("ck"), Some(dir), None, None)
    val i = s.run.inputs("crossref").head
    (i.version, i.checksum.get)

  test(
    "driver input checksum: changes with a packed file's content, unchanged without a packed file"
  ) {
    val base     = copyInto(newRoot("ck-base"), fixture("crossref"), CrossrefN :+ "errors.jsonl")
    val (v0, c0) = checksum(base)
    assertEquals(c0, checksum(fixture("crossref"))._2, "same directory content, same checksum")
    // a file the filter skips (errors.jsonl content) does not matter
    write(base.resolve("errors.jsonl"), "changed\n")
    assertEquals(checksum(base), (v0, c0))
    // a packed file joins the checksum and the file count
    val pk       = write(base.resolve("z.envelopes.jsonl"), env("10.1000/ck.1") + "\n")
    val (v1, c1) = checksum(base)
    assertNotEquals(c1, c0)
    assertEquals(v1, v0.replace("files=5", "files=6"))
    // changing the packed content changes it again; restoring it restores it
    write(pk, env("10.1000/ck.2") + "\n")
    val (_, c2) = checksum(base)
    assertNotEquals(c2, c1)
    write(pk, env("10.1000/ck.1") + "\n")
    assertEquals(checksum(base)._2, c1)
    // and the run id follows the checksum
    assertNotEquals(
      prepare(newRoot("ck-a"), Some(base), None, None).run.runId,
      prepare(newRoot("ck-b"), Some(fixture("crossref")), None, None).run.runId,
    )
  }
end Hj2PackedInputSuite
