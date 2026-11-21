package scholarchain.hadoop

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{DataInputBuffer, RawComparator, Text, WritableComparator}
import org.apache.hadoop.mapred.RawKeyValueIterator
import org.apache.hadoop.mapreduce.{
  Counter,
  Counters as HCounters,
  Job,
  OutputCommitter,
  RecordWriter,
  Reducer,
  StatusReporter,
  TaskAttemptID,
  TaskType,
}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputCommitter,
  MultipleOutputs,
  TextOutputFormat,
}
import org.apache.hadoop.mapreduce.lib.reduce.WrappedReducer
import org.apache.hadoop.mapreduce.task.ReduceContextImpl
import org.apache.hadoop.util.Progress
import scholarchain.core.*

/** Helpers for the unit and adversarial suites. Everything here is SYNTHETIC test support: no job
  * is launched (no LocalJobRunner); reducers are driven in-process through a hand-built context.
  */
object UnitSupport:
  val Utf8 = StandardCharsets.UTF_8
  val Hex  = "a" * 64

  lazy val repoRoot: JPath =
    var p = Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath
    while p != null && !Files.exists(p.resolve("build.sbt")) do p = p.getParent
    require(p != null, "repository root not found")
    p

  def resource(path: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/unit-fixtures/$path")
    require(in != null, s"missing unit fixture $path")
    try in.readAllBytes()
    finally in.close()

  def resourceText(path: String): String = new String(resource(path), Utf8)

  lazy val entities: DblpEntities = DblpEntities.fromDtd(resource("dblp-unit.dtd"))
  lazy val parser: DblpParser     = new DblpParser(entities, Utf8)

  def parse(xml: String): Either[ParseFailure, ParsedRecord] = parser.parse(xml.getBytes(Utf8))

  def rec(
      body: String,
      tag: String = "article",
      key: String = "journals/unit/x1",
  ): String = s"""<$tag key="$key">$body</$tag>"""

  def okRec(authors: String = "<author>Ann One</author>", extra: String = ""): String =
    rec(s"$authors<title>T</title><year>2020</year>$extra")

  def work(r: Either[ParseFailure, ParsedRecord]): DblpWork = r match
    case Right(ParsedWork(w, _)) => w
    case other                   => throw new AssertionError(s"expected a work, got $other")

  def warnings(r: Either[ParseFailure, ParsedRecord]): List[String] = r match
    case Right(ParsedWork(_, w))         => w
    case Right(ParsedPersonRecord(_, w)) => w
    case _                               => Nil

  def reason(r: Either[ParseFailure, ?]): Option[RejectReason] = r.left.toOption.map(_.reason)

  val runCtx: RunContext = RunContext(
    "run:" + Hex,
    "unit-test",
    "unit",
    1,
    Map("hadoop.large-team-threshold" -> "50", "hadoop.pair-expansion-hard-cap" -> "499500"),
    List("dblp", "dtd", "crossref", "openalex", "orcid")
      .map(k => k -> List(InputRef(s"unit:$k", "v1", Some(Hex))))
      .toMap,
    "UTF-8",
  )

  def rows(n: Normalized, table: String): List[ujson.Value] =
    n.rows.filter(_.table == table).map(r => ujson.read(r.json))

  def normalized(r: Either[ParseFailure, Normalized]): Normalized = r match
    case Right(n) => n
    case Left(f)  => throw new AssertionError(s"expected a normalized record, got $f")

  def prov: Provenance = runCtx.prov(SourceType.Dblp, Seq("dblp", "dtd"), "unit")

  def idRef(
      t: String,
      v: String,
      ref: String,
      src: SourceType,
      ids: Map[String, String] = Map.empty,
  ): String =
    WirePickle.write(IdRef(t, v, ref, src, if ids.isEmpty then Map(t -> v) else ids))

  /** Output of one in-process reducer run. */
  final case class Reduced(
      tables: Map[String, List[(String, String)]],
      counters: Map[String, Long],
      rejects: Map[String, Long],
      decisions: Map[String, Long] = Map.empty,
      emitted: List[(String, String)] = Nil,
  ):
    def count(c: PipelineCounter): Long          = counters.getOrElse(c.toString, 0L)
    def table(t: String): List[(String, String)] = tables.getOrElse(t, Nil)

  /** Runs `reducer` over `input` (key, value) pairs, sorted by key as the shuffle would, and
    * returns the rows it wrote through MultipleOutputs plus its counters.
    */
  def reduce(
      reducer: Reducer[Text, Text, ?, ?],
      input: List[(String, String)],
      run: RunContext = runCtx,
      extraConf: Map[String, String] = Map.empty,
  ): Reduced =
    val out  = Files.createTempDirectory(repoRoot.resolve("modules/hadoop/target"), "unit-reduce-")
    val conf = new Configuration()
    conf.set("fs.defaultFS", "file:///")
    conf.set("mapreduce.output.fileoutputformat.outputdir", out.toString)
    RunContext.toConf(conf, run)
    extraConf.foreach((k, v) => conf.set(k, v))
    val job = Job.getInstance(conf, "unit")
    MultipleOutputs.addNamedOutput(
      job,
      JobSupport.Named,
      classOf[TextOutputFormat[?, ?]],
      classOf[Text],
      classOf[Text],
    )
    val jc       = job.getConfiguration
    val attempt  = new TaskAttemptID("unit", 1, TaskType.REDUCE, 0, 0)
    val counters = new HCounters()
    val reporter = new StatusReporter:
      override def getCounter(name: Enum[?]): Counter               = counters.findCounter(name)
      override def getCounter(group: String, name: String): Counter =
        counters.findCounter(group, name)
      override def progress(): Unit           = ()
      override def getProgress(): Float       = 0f
      override def setStatus(s: String): Unit = ()
    val sorted                      = input.sortBy(_._1)
    def ser(s: String): Array[Byte] =
      val bo = new ByteArrayOutputStream()
      new Text(s).write(new DataOutputStream(bo))
      bo.toByteArray
    val it   = sorted.iterator
    val iter = new RawKeyValueIterator:
      private val k                          = new DataInputBuffer()
      private val v                          = new DataInputBuffer()
      override def getKey: DataInputBuffer   = k
      override def getValue: DataInputBuffer = v
      override def next(): Boolean           =
        if it.hasNext then
          val (a, b) = it.next()
          val ka     = ser(a)
          val vb     = ser(b)
          k.reset(ka, ka.length)
          v.reset(vb, vb.length)
          true
        else false
      override def close(): Unit         = ()
      override def getProgress: Progress = new Progress()
    val cmp = new RawComparator[Text]:
      override def compare(
          a: Array[Byte],
          s1: Int,
          l1: Int,
          b: Array[Byte],
          s2: Int,
          l2: Int,
      ): Int =
        WritableComparator.compareBytes(a, s1, l1, b, s2, l2)
      override def compare(a: Text, b: Text): Int = a.compareTo(b)
    val direct = scala.collection.mutable.ListBuffer.empty[(String, String)]
    val writer: RecordWriter[Text, Text] = new RecordWriter[Text, Text]:
      override def write(k: Text, v: Text): Unit = direct += ((k.toString, v.toString))
      override def close(c: org.apache.hadoop.mapreduce.TaskAttemptContext): Unit = ()
    val committer: OutputCommitter = new FileOutputCommitter(
      new org.apache.hadoop.fs.Path(out.toString),
      new org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl(jc, attempt),
    )
    val rc = new ReduceContextImpl[Text, Text, Text, Text](
      jc,
      attempt,
      iter,
      counters.findCounter("in", "keys"),
      counters.findCounter("in", "values"),
      writer,
      committer,
      reporter,
      cmp,
      classOf[Text],
      classOf[Text],
    )
    val ctx = new WrappedReducer[Text, Text, Text, Text]().getReducerContext(rc)
    reducer.asInstanceOf[Reducer[Text, Text, Text, Text]].run(ctx)
    val tables = Files
      .walk(out)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part-"))
      .toList
      .groupBy(_.getParent.getFileName.toString)
      .map { (t, ps) =>
        t -> ps.sorted.flatMap { p =>
          new String(Files.readAllBytes(p), Utf8).split("\n").toList.filter(_.nonEmpty).map { l =>
            val i = l.indexOf('\t')
            if i < 0 then ("", l) else (l.substring(0, i), l.substring(i + 1))
          }
        }.sorted
      }
    def group(g: String): Map[String, Long] =
      counters.getGroup(g).iterator().asScala.map(c => c.getName -> c.getValue).toMap
    Reduced(
      tables,
      group(Counters.EnumGroup),
      group(Counters.RejectsGroup),
      group(Counters.DecisionsGroup),
      direct.toList,
    )

  /** Runs an HJ3 style mapper (text lines in) in-process; returns the emitted pairs and counters.
    */
  def mapLines(
      mapper: org.apache.hadoop.mapreduce.Mapper[
        org.apache.hadoop.io.LongWritable,
        Text,
        Text,
        Text,
      ],
      lines: List[String],
      run: RunContext = runCtx,
      extraConf: Map[String, String] = Map.empty,
      split: org.apache.hadoop.mapreduce.InputSplit = null,
  ): (List[(String, String)], Map[String, Long]) =
    import org.apache.hadoop.io.LongWritable
    import org.apache.hadoop.mapreduce.{InputSplit, RecordReader, TaskAttemptContext}
    import org.apache.hadoop.mapreduce.lib.map.WrappedMapper
    import org.apache.hadoop.mapreduce.task.MapContextImpl
    val conf = new Configuration()
    RunContext.toConf(conf, run)
    extraConf.foreach((k, v) => conf.set(k, v))
    val attempt  = new TaskAttemptID("unit", 1, TaskType.MAP, 0, 0)
    val counters = new HCounters()
    val reporter = new StatusReporter:
      override def getCounter(name: Enum[?]): Counter               = counters.findCounter(name)
      override def getCounter(group: String, name: String): Counter =
        counters.findCounter(group, name)
      override def progress(): Unit           = ()
      override def getProgress(): Float       = 0f
      override def setStatus(s: String): Unit = ()
    val it     = lines.iterator.zipWithIndex
    var cur    = (new LongWritable(0), new Text())
    val reader = new RecordReader[LongWritable, Text]:
      override def initialize(s: InputSplit, c: TaskAttemptContext): Unit = ()
      override def nextKeyValue(): Boolean                                =
        if it.hasNext then
          val (l, i) = it.next()
          cur = (new LongWritable(i.toLong), new Text(l))
          true
        else false
      override def getCurrentKey: LongWritable = cur._1
      override def getCurrentValue: Text       = cur._2
      override def getProgress: Float          = 0f
      override def close(): Unit               = ()
    val emitted = scala.collection.mutable.ListBuffer.empty[(String, String)]
    val writer  = new RecordWriter[Text, Text]:
      override def write(k: Text, v: Text): Unit      = emitted += ((k.toString, v.toString))
      override def close(c: TaskAttemptContext): Unit = ()
    val committer = new OutputCommitter:
      override def setupJob(c: org.apache.hadoop.mapreduce.JobContext): Unit = ()
      override def setupTask(c: TaskAttemptContext): Unit                    = ()
      override def needsTaskCommit(c: TaskAttemptContext): Boolean           = false
      override def commitTask(c: TaskAttemptContext): Unit                   = ()
      override def abortTask(c: TaskAttemptContext): Unit                    = ()
    val mc = new MapContextImpl[LongWritable, Text, Text, Text](
      conf,
      attempt,
      reader,
      writer,
      committer,
      reporter,
      split,
    )
    val ctx = new WrappedMapper[LongWritable, Text, Text, Text]().getMapContext(mc)
    mapper.run(ctx)
    (
      emitted.toList,
      counters
        .getGroup(Counters.EnumGroup)
        .iterator()
        .asScala
        .map(c => c.getName -> c.getValue)
        .toMap,
    )
