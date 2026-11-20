package scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{BytesWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.{Job, JobCounter, Mapper}
import org.apache.hadoop.mapreduce.{TaskAttemptID, TaskType}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import scholarchain.core.RejectReason
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, TextOutputFormat}
import ItSupport.*

/** Test-only mapper: emits `<offset> TAB <DBLP key attribute>` for every record the reader yields.
  */
class KeyEchoMapper extends Mapper[LongWritable, BytesWritable, Text, Text]:
  private val KeyAttr = """key="([^"]+)"""".r
  override def setup(ctx: Mapper[LongWritable, BytesWritable, Text, Text]#Context): Unit =
    ctx.getCounter("test", "splits").increment(1)

  override def map(
      k: LongWritable,
      v: BytesWritable,
      ctx: Mapper[LongWritable, BytesWritable, Text, Text]#Context,
  ): Unit =
    val text = new String(v.copyBytes(), StandardCharsets.ISO_8859_1)
    val key  = KeyAttr.findFirstMatchIn(text).map(_.group(1)).getOrElse("?")
    ctx.write(new Text(k.get.toString), new Text(key))

/** The record reader with a forced tiny split size, over a SYNTHETIC multi-record file and a
  * SYNTHETIC edge file (first record at byte 0, last record ending at end of file).
  *
  * Job launches: 1 (map-only, many splits). The HJ1 tool is run twice more (1 launch of one job
  * each: the plain fixtures, then the gzipped DBLP file of F1). Total 3. The F8 and F1 reader
  * checks drive the reader directly and launch no job.
  */
class RecordReaderItSuite extends munit.FunSuite:
  private val Rec =
    """<(article|inproceedings|proceedings|book|incollection|phdthesis|mastersthesis|www|data)\s[^>]*key="([^"]+)"""".r
  private def expected(f: JPath): List[(Long, String)] =
    Rec
      .findAllMatchIn(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1))
      .map(m => (m.start.toLong, m.group(2)))
      .toList

  private var got: List[(Long, String)] = Nil
  private var splits                    = 0L

  override def beforeAll(): Unit =
    val out = newRoot("reader")
    val job =
      Job.getInstance(conf("mapreduce.input.fileinputformat.split.maxsize" -> "150"), "reader-test")
    job.setInputFormatClass(classOf[DblpRecordInputFormat])
    FileInputFormat.addInputPath(job, new Path(fixture("dblp-synth.xml").toString))
    FileInputFormat.addInputPath(job, new Path(fixture("dblp-edge-synth.xml").toString))
    job.setMapperClass(classOf[KeyEchoMapper])
    job.setNumReduceTasks(0)
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[Text])
    job.setOutputFormatClass(classOf[TextOutputFormat[?, ?]])
    FileOutputFormat.setOutputPath(job, new Path(out.toString))
    assert(job.waitForCompletion(false))
    splits = job.getCounters.findCounter("test", "splits").getValue
    got = partFiles(out).flatMap(lines).map(_.split("\t")).map(a => (a(0).toLong, a(1)))

  test("with a tiny split size every record is read exactly once, by offset and key") {
    // offsets are per file; compare the multiset of keys with the expected keys of both files
    val want = (expected(fixture("dblp-synth.xml")) ++ expected(fixture("dblp-edge-synth.xml")))
      .map(_._2)
      .sorted
    assertEquals(got.map(_._2).sorted, want)
    assertEquals(want.size, 15 + 4)
    assert(splits >= 20, s"expected many splits, got $splits")
  }

  test("records at file start (byte 0) and at file end are read, with their exact offsets") {
    val edge = expected(fixture("dblp-edge-synth.xml"))
    assertEquals(edge.head, (0L, "edge/First"))
    assertEquals(edge.last._2, "edge/Last")
    edge.foreach(e => assert(got.contains(e), s"missing $e"))
    assertEquals(got.count(_._2 == "edge/Last"), 1)
    assertEquals(got.count(_._2 == "edge/First"), 1)
  }

  test("the HJ1 tool runs on its own (one job) and writes works and rejects tables") {
    val root = newRoot("hj1tool")
    val rc   =
      org.apache.hadoop.util.ToolRunner.run(
        conf("scholarchain.hadoop.reducers.normalize" -> "1"),
        new DblpExtractTool,
        baseArgs(root).toArray,
      )
    assertEquals(rc, 0)
    val works = partFiles(root.resolve("work/hj1/works")).flatMap(lines)
    assertEquals(works.size, 8)
    assert(partFiles(root.resolve("work/hj1/rejects")).flatMap(lines).size == 5)
  }

  /** Drives the reader directly (no job) over one split and returns (offset, key, bytes). */
  private def readSplit(
      f: JPath,
      start: Long,
      length: Long,
      c: Configuration = conf(),
  ): List[(Long, String, Array[Byte])] =
    val ctx   = new TaskAttemptContextImpl(c, new TaskAttemptID("it", 1, TaskType.MAP, 0, 0))
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

  /** A SYNTHETIC gzipped DBLP file: `n` valid, padded records. */
  private def gzFile(n: Int): (JPath, Long) =
    val dir = newRoot("gz")
    Files.createDirectories(dir)
    val f  = dir.resolve("dblp-synth.xml.gz")
    val sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n")
    (0 until n).foreach { i =>
      sb.append(
        f"<article key=\"gz/R$i%04d\"><author>Gz Author $i%04d</author>" +
          f"<title>Gz title $i%04d ${"padding " * 12}.</title><year>2020</year></article>\n"
      )
    }
    sb.append("</dblp>\n")
    val raw = sb.toString.getBytes(StandardCharsets.UTF_8)
    val out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(f))
    try out.write(raw)
    finally out.close()
    (f, raw.length.toLong)

  test("F1: a gzipped file much larger decompressed is read whole: every record, none lost") {
    val (f, plain) = gzFile(400)
    val packed     = Files.size(f)
    assert(packed * 5 < plain, s"compressed $packed vs plain $plain: not compressible enough")
    // Hadoop hands the reader one split whose length is the COMPRESSED size
    val recs = readSplit(f, 0L, packed)
    assertEquals(recs.size, 400)
    assertEquals(recs.map(_._2), (0 until 400).map(i => f"gz/R$i%04d").toList)
    assert(recs.last._1 > packed, "the last record starts beyond the compressed length")
    assertEquals(recs.map(_._1), recs.map(_._1).sorted)
    val fmt = new DblpRecordInputFormat
    val ctx = new TaskAttemptContextImpl(conf(), new TaskAttemptID("it", 1, TaskType.MAP, 0, 0))
    assert(!fmt.isSplitable(ctx, new Path(f.toUri)))
  }

  test("F1: HJ1 over the gzipped file writes one works row per record (400) and no reject") {
    val (f, _) = gzFile(400)
    val root   = newRoot("gzhj1")
    val args   = baseArgs(root).toArray
    args(args.indexOf("--dblp") + 1) = f.toString
    val rc = org.apache.hadoop.util.ToolRunner.run(
      conf("scholarchain.hadoop.reducers.normalize" -> "1"),
      new DblpExtractTool,
      args,
    )
    assertEquals(rc, 0)
    assertEquals(partFiles(root.resolve("work/hj1/works")).flatMap(lines).size, 400)
    assertEquals(partFiles(root.resolve("work/hj1/rejects")).flatMap(lines).size, 0)
  }

  test("F8: an unclosed record does not swallow the next one; it is cut and rejected") {
    val f    = fixture("dblp-unclosed-synth.xml")
    val recs = readSplit(f, 0L, Files.size(f))
    assertEquals(recs.map(_._2), List("un/A", "un/B", "un/C"))
    val text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
    assertEquals(
      recs.map(_._1),
      List(
        text.indexOf("<article key=\"un/A\""),
        text.indexOf("<article key=\"un/B\""),
        text.indexOf("<inproceedings key=\"un/C\""),
      ).map(_.toLong),
    )
    // the broken record ends before the next start tag and the parser rejects it
    val a = new String(recs.head._3, StandardCharsets.UTF_8)
    assert(!a.contains("un/B") && !a.contains("</article>"), a)
    val dtd    = Files.readAllBytes(fixture("dblp-synth.dtd"))
    val parser = new DblpParser(DblpEntities.fromDtd(dtd), StandardCharsets.UTF_8)
    parser.parse(recs.head._3) match
      case Left(failure) => assertEquals(failure.reason, RejectReason.MalformedRecord)
      case Right(_)      => fail("the unclosed record must be rejected")
    assert(parser.parse(recs(1)._3).isRight)
    assert(parser.parse(recs(2)._3).isRight)
  }

  test("F8: the same holds when a tiny split cuts the unclosed record") {
    val f    = fixture("dblp-unclosed-synth.xml")
    val size = Files.size(f)
    val all  = (0L until size by 40L).toList.flatMap(s => readSplit(f, s, math.min(40L, size - s)))
    assertEquals(all.map(_._2).sorted, List("un/A", "un/B", "un/C"))
  }
end RecordReaderItSuite
