package scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}
import java.text.Normalizer
import java.util.BitSet

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.{TaskAttemptID, TaskType}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import scholarchain.core.*
import UnitSupport.*

/** ADVERSARIAL tests on SYNTHETIC input: broken XML, Unicode traps, missing fields, duplicate ids,
  * common names and giant teams. No job is launched (no LocalJobRunner).
  */
class AdversarialSuite extends munit.FunSuite:

  // Per-test timeout override (Phase 08 repair 03). munit 1.3.6 has only a suite-wide
  // `munitTimeout`, so the longer limit is applied through a flag that is set only while the one
  // named test below runs. Every other test keeps the default 30 s limit.
  private val giantTeamTestName =
    "adversarial: a giant team of 1,000 gives exactly 499,500 valid distinct pairs, none truncated"
  private val giantTeamTimeoutActive = new java.util.concurrent.atomic.AtomicBoolean(false)

  override def munitTimeout: scala.concurrent.duration.Duration =
    if giantTeamTimeoutActive.get() then scala.concurrent.duration.Duration(120, "s")
    else super.munitTimeout

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "giant-team-timeout",
      { t =>
        if t.name == giantTeamTestName then
          t.withBody { () =>
            giantTeamTimeoutActive.set(true)
            try t.body()
            finally giantTeamTimeoutActive.set(false)
          }
        else t
      },
    )

  // ---- helpers ------------------------------------------------------------------------------

  private def tempFile(bytes: Array[Byte]): JPath =
    val f = Files.createTempFile(repoRoot.resolve("modules/hadoop/target"), "adv-", ".xml")
    Files.write(f, bytes)
    f

  /** (offset, DBLP key) of every record the real record reader yields for one split. */
  private def readSplit(
      f: JPath,
      start: Long,
      len: Long,
      maxBytes: Option[Int] = None,
  ): List[(Long, String, Array[Byte])] =
    val conf = new Configuration()
    maxBytes.foreach(m => conf.setInt(DblpRecordReader.MaxRecordBytesKey, m))
    val ctx = new TaskAttemptContextImpl(conf, new TaskAttemptID("adv", 1, TaskType.MAP, 0, 0))
    val rr  = new DblpRecordReader
    rr.initialize(new FileSplit(new Path(f.toUri), start, len, Array.empty[String]), ctx)
    val out = scala.collection.mutable.ListBuffer.empty[(Long, String, Array[Byte])]
    while rr.nextKeyValue() do
      val bytes = rr.getCurrentValue.copyBytes()
      val k     = """key="([^"]+)"""".r
        .findFirstMatchIn(new String(bytes, "ISO-8859-1"))
        .map(_.group(1))
        .getOrElse("?")
      out += ((rr.getCurrentKey.get, k, bytes))
    rr.close()
    out.toList

  private val Prefix = """<?xml version="1.0" encoding="UTF-8"?><dblp>"""

  // ---- XML ----------------------------------------------------------------------------------

  test("adversarial: an unclosed tag in the middle of a record is malformed_record, not a crash") {
    val r = parse(rec("<author>Ann<title>T</title><year>2020</year>"))
    assertEquals(reason(r), Some(RejectReason.MalformedRecord))
    assertEquals(
      reason(parse("""<article key="k/1"><author>A</author><title>T</title><year>2020</year>""")),
      Some(RejectReason.MalformedRecord),
    )
  }

  test("adversarial: CDATA in a title is taken literally (markup characters preserved)") {
    val w = work(
      parse(rec("<author>A</author><title><![CDATA[a <b> & c]]></title><year>2020</year>"))
    )
    assertEquals(w.title, "a <b> & c")
  }

  test("adversarial: a literal &lt;article inside a title is text, and is not a record start") {
    val xml =
      s"""$Prefix<article key="k/1"><author>A</author><title>see &lt;article key="fake/2"&gt; here</title><year>2020</year></article>
         |<article key="k/3"><author>B</author><title><![CDATA[<article key="fake/4">]]></title><year>2021</year></article></dblp>""".stripMargin
    val f    = tempFile(xml.getBytes(Utf8))
    val recs = readSplit(f, 0, Files.size(f))
    assertEquals(recs.map(_._2), List("k/1", "k/3"))
    val w = work(parser.parse(recs.head._3))
    assertEquals(w.title, """see <article key="fake/2"> here""")
    assertEquals(work(parser.parse(recs(1)._3)).title, """<article key="fake/4">""")
  }

  test("adversarial: a UTF-8 BOM before the document does not shift or lose records") {
    val body =
      s"""$Prefix<article key="k/1"><author>A</author><title>T</title><year>2020</year></article></dblp>"""
    val f    = tempFile(Array(0xef, 0xbb, 0xbf).map(_.toByte) ++ body.getBytes(Utf8))
    val recs = readSplit(f, 0, Files.size(f))
    assertEquals(recs.map(r => (r._1, r._2)), List((3L + Prefix.length, "k/1")))
    assertEquals(work(parser.parse(recs.head._3)).key, "k/1")
    // a BOM glued to the front of one record's bytes
    val withBom = Array(0xef, 0xbb, 0xbf).map(_.toByte) ++ okRec().getBytes(Utf8)
    assertEquals(parser.parse(withBom).isRight, true)
  }

  test("adversarial: an extremely long title (1,000,000 characters) is kept whole") {
    val long = "x" * 1000000
    val w    = work(parse(rec(s"<author>A</author><title>$long</title><year>2020</year>")))
    assertEquals(w.title.length, 1000000)
  }

  test(
    "adversarial: a record start tag exactly at a split boundary is read once, by the later split"
  ) {
    val r1 = """<article key="k/1"><author>A</author><title>T1</title><year>2020</year></article>"""
    val r2 = """<article key="k/2"><author>B</author><title>T2</title><year>2021</year></article>"""
    val r3 = """<article key="k/3"><author>C</author><title>T3</title><year>2022</year></article>"""
    val xml  = s"$Prefix$r1\n$r2\n$r3</dblp>"
    val f    = tempFile(xml.getBytes(Utf8))
    val size = Files.size(f)
    val b2   = xml.indexOf(r2).toLong // the '<' of record 2 is exactly the split boundary
    val b3   = xml.indexOf(r3).toLong
    val s1   = readSplit(f, 0, b2)
    val s2   = readSplit(f, b2, b3 - b2)
    val s3   = readSplit(f, b3, size - b3)
    assertEquals(s1.map(r => (r._1, r._2)), List((xml.indexOf(r1).toLong, "k/1")))
    assertEquals(s2.map(r => (r._1, r._2)), List((b2, "k/2")))
    assertEquals(s3.map(r => (r._1, r._2)), List((b3, "k/3")))
    // one byte earlier: the boundary falls inside record 1, which then is read past the split end
    val inside = readSplit(f, 0, b2 - 1)
    assertEquals(inside.map(_._2), List("k/1"))
    assertEquals(readSplit(f, b2 - 1, size - (b2 - 1)).map(_._2), List("k/2", "k/3"))
  }

  test(
    "adversarial: invalid UTF-8 bytes are encoding_error; declarations, entity bombs and deep nesting are malformed"
  ) {
    val bad =
      Array[Byte]('<', 'a', 'r', 't', 'i', 'c', 'l', 'e', ' ') ++ Array(0xff, 0xfe).map(_.toByte)
    assertEquals(reason(parser.parse(bad)), Some(RejectReason.EncodingError))
    val bomb =
      """<!DOCTYPE article [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;&a;">]><article key="k/b"><author>A</author><title>&b;</title><year>2020</year></article>"""
    assertEquals(reason(parse(bomb)), Some(RejectReason.MalformedRecord))
    val deep = "<x>" * 200 + "</x>" * 200
    assertEquals(
      reason(parse(rec(s"<author>A</author><title>T</title><year>2020</year>$deep"))),
      Some(RejectReason.MalformedRecord),
    )
  }

  // ---- Unicode ------------------------------------------------------------------------------

  private val Nfc = Normalizer.normalize("Müller", Normalizer.Form.NFC)
  private val Nfd = Normalizer.normalize("Müller", Normalizer.Form.NFD)

  test("adversarial: NFC and NFD forms of one name are NOT folded (observed, undocumented)") {
    assertNotEquals(Nfc, Nfd)
    val w = work(parse(okRec(s"<author>$Nfc</author><author>$Nfd</author>")))
    // OBSERVED, UNDOCUMENTED: JOB-CONTRACTS.md defines team size as distinct author strings and says nothing
    // about Unicode normalization, so the two forms stay two mentions and one pair is emitted.
    assertEquals(w.authors.map(_.name), List(Nfc, Nfd))
    assertEquals(w.droppedDuplicateAuthors, 0)
    assertEquals(PairExpander.pairCount(w.authors.size), 1L)
    // names are stored code-point exact, not normalized
    assertEquals(work(parse(okRec(s"<author>$Nfd</author>"))).authors.head.name, Nfd)
  }

  test(
    "adversarial: a Cyrillic confusable inside a Latin name stays a distinct author (observed, undocumented)"
  ) {
    val latin = "Wei Wang"
    val conf  = "Wei Wаng" // U+0430 CYRILLIC SMALL LETTER A
    assertNotEquals(latin, conf)
    val w = work(parse(okRec(s"<author>$latin</author><author>$conf</author>")))
    // OBSERVED, UNDOCUMENTED: no confusable folding; this is entity-resolution policy (Phase 05).
    assertEquals(w.authors.map(_.name), List(latin, conf))
    assertEquals(w.droppedDuplicateAuthors, 0)
    assertEquals(
      work(parse(okRec(s"<author>$conf</author>"))).authors.head.name.codePointAt(5),
      0x430,
    )
  }

  test(
    "adversarial: combining diacritics in titles survive code-point exact and whitespace-collapse"
  ) {
    val title = "Café   résumé Å"
    val w     = work(parse(rec(s"<author>A</author><title>$title</title><year>2020</year>")))
    assertEquals(w.title, "Café résumé Å")
    assertEquals(w.title.codePointCount(0, w.title.length), 17)
  }

  // ---- Missing fields -----------------------------------------------------------------------

  test("adversarial: no authors routes to missing_authors") {
    val r = parse(rec("<title>T</title><year>2020</year>"))
    assertEquals(reason(r), Some(RejectReason.MissingAuthors))
    assertEquals(
      reason(parse(rec("<author>  </author><title>T</title><year>2020</year>"))),
      Some(RejectReason.MissingAuthors),
    )
  }

  test("adversarial: no year routes to missing_year; an out-of-range year to invalid_year") {
    assertEquals(
      reason(parse(rec("<author>A</author><title>T</title>"))),
      Some(RejectReason.MissingYear),
    )
    assertEquals(
      reason(parse(rec("<author>A</author><title>T</title><year>99999</year>"))),
      Some(RejectReason.InvalidYear),
    )
  }

  test("adversarial: no venue is accepted with the venue fields empty") {
    val w = work(parse(okRec()))
    assertEquals((w.journal, w.booktitle, w.volume, w.pages), (None, None, None, None))
    assertEquals(w.year, 2020)
  }

  // ---- Duplicate ids ------------------------------------------------------------------------

  test(
    "adversarial: the same DBLP key twice with different content has a deterministic winner (lowest offset)"
  ) {
    val a = work(
      parse(
        rec(
          "<author>Ann One</author><title>First</title><year>2020</year>",
          key = "journals/unit/dup",
        )
      )
    )
    val b = work(
      parse(
        rec(
          "<author>Bob Two</author><title>Second</title><year>2021</year>",
          key = "journals/unit/dup",
        )
      )
    )
    val inA = (a.key, WirePickle.write(ParsedDblp(900L, a)))
    val inB = (b.key, WirePickle.write(ParsedDblp(100L, b)))
    val x   = reduce(new DblpReducer, List(inA, inB))
    val y   = reduce(new DblpReducer, List(inB, inA))
    assertEquals(x.tables, y.tables, "output must not depend on input order")
    assertEquals(x.count(PipelineCounter.DUPLICATE), 1L)
    assertEquals(ujson.read(x.table("works").head._2)("title").str, "Second")
    assertEquals(x.table("duplicates").size, 1)
    val d = ujson.read(x.table("duplicates").head._2)
    assertEquals((d("winner").str, d("duplicate").str), ("offset:100", "offset:900"))
    assertEquals(
      x.table("author-mentions").map(m => ujson.read(m._2)("rawName").str),
      List("Bob Two"),
    )
  }

  test(
    "adversarial: the same DOI on two DBLP works is CONFLICT end to end (HJ1 id-refs into HJ3)"
  ) {
    def one(key: String, off: Long) =
      val w = work(
        parse(
          rec(
            "<author>A</author><title>T</title><year>2020</year><ee>https://doi.org/10.1000/Shared.1</ee>",
            key = key,
          )
        )
      )
      reduce(new DblpReducer, List((key, WirePickle.write(ParsedDblp(off, w)))))
        .table("id-refs")
        .map(_._2)
    val refs = one("journals/unit/a", 10L) ++ one("journals/unit/b", 20L)
    assertEquals(refs.size, 2)
    val out = reduce(new IdentifierReducer, refs.map(r => ("doi|10.1000/shared.1", r)))
    assertEquals(
      out.table("exact-identifier-evidence").map(e => ujson.read(e._2)("outcome").str),
      List("conflict"),
    )
    assertEquals(out.count(PipelineCounter.CONFLICT), 1L)
    assertEquals(
      ujson
        .read(out.table("exact-identifier-evidence").head._2)("linkedRecordIds")
        .arr
        .map(_.str)
        .toList,
      List("work:dblp:journals/unit/a", "work:dblp:journals/unit/b"),
    )
  }

  // ---- Common names -------------------------------------------------------------------------

  test(
    "adversarial: DBLP homonyms 'Wei Wang 0001' and 'Wei Wang 0002' stay distinct: one pair, no self-pair"
  ) {
    val w = work(parse(okRec("<author>Wei Wang 0001</author><author>Wei Wang 0002</author>")))
    assertEquals(w.authors.map(_.name), List("Wei Wang 0001", "Wei Wang 0002"))
    assertEquals(w.droppedDuplicateAuthors, 0)
    val out = reduce(new DblpReducer, List((w.key, WirePickle.write(ParsedDblp(0L, w)))))
    assertEquals(out.count(PipelineCounter.SELF_PAIR_SKIPPED), 0L)
    assertEquals(out.table("author-mentions").size, 2)
    val pairs =
      PairExpander.expand(w.workId, w.year, w.authors.size, prov).toList.map(_.toOption.get)
    assertEquals(pairs.size, 1)
    assertNotEquals(pairs.head.pair.first, pairs.head.pair.second)
  }

  // ---- Giant teams --------------------------------------------------------------------------

  private def exhaust(n: Int): (Long, Long) =
    val seen  = new BitSet(n * n)
    var count = 0L
    var bad   = 0L
    PairExpander.expand("work:dblp:journals/unit/giant", 2020, n, prov).foreach {
      case Right(c) =>
        val lo = math.min(c.firstPosition, c.secondPosition)
        val hi = math.max(c.firstPosition, c.secondPosition)
        if lo >= hi || c.pair.first >= c.pair.second || seen.get(lo * n + hi) then bad += 1
        seen.set(lo * n + hi)
        count += 1
      case Left(_) => bad += 1
    }
    (count, bad)

  test(
    "adversarial: a giant team of 300 gives exactly 44,850 valid distinct pairs (pure expander)"
  ) {
    assertEquals(PairExpander.pairCount(300), 44850L)
    assertEquals(exhaust(300), (44850L, 0L))
  }

  // Timeout raised from 30 s to 120 s for this one test only (see munitTimeout override above).
  // Reason: 499,500 pairs under a loaded laptop exceeded 30 s (32.683 s observed). The assertion
  // and the input size are unchanged. Phase 08 repair 03.
  test(giantTeamTestName) {
    assertEquals(PairExpander.pairCount(1000), 499500L)
    assertEquals(exhaust(1000), (499500L, 0L))
  }

  // ---- Reject excerpt -----------------------------------------------------------------------

  test(
    "adversarial: RejectRecord.rawExcerpt is bounded to 512 characters, never splitting a surrogate pair"
  ) {
    val f    = ParseFailure(RejectReason.MalformedRecord, "bad", Some("k/1"))
    val long = Rejects.make(runCtx, "hj1", SourceType.Dblp, Some("k/1"), Some(0L), f, "x" * 100000)
    assertEquals(long.rawExcerpt.length, 512)
    val emoji = Rejects.make(runCtx, "hj1", SourceType.Dblp, Some("k/1"), Some(0L), f, "😀" * 600)
    assertEquals(emoji.rawExcerpt.codePointCount(0, emoji.rawExcerpt.length), 512)
    assert(!Character.isHighSurrogate(emoji.rawExcerpt.last), "split a surrogate pair")
    val exact = Rejects.make(runCtx, "hj1", SourceType.Dblp, Some("k/1"), Some(0L), f, "y" * 512)
    assertEquals(exact.rawExcerpt.length, 512)
    assert(summon[Contract[RejectRecord]].validate(long).isRight)
  }
