package scholarchain.hadoop

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.apache.hadoop.io.compress.{CompressionCodec, DefaultCodec, GzipCodec, SnappyCodec}
import org.apache.hadoop.mapreduce.{Job, Partitioner, TaskCounter, TaskInputOutputContext}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat,
}
import scholarchain.core.*

/** Result of one finished job: its counters by group. */
final case class JobOutcome(
    jobId: String,
    success: Boolean,
    counters: Map[String, Long],
    rows: Map[String, Long],
    rejects: Map[String, Long],
    decisions: Map[String, Long] = Map.empty,
    framework: Map[String, Long] = Map.empty,
):
  def c(n: PipelineCounter): Long = counters.getOrElse(n.toString, 0L)

/** Everything a job needs: the run context, the layout and the resolved Hadoop settings. */
final case class Setup(run: RunContext, layout: Layout, hadoop: HadoopConfig, args: PipelineArgs)

/** Writes `sortKey <TAB> json` lines into per-table directories of the job output. */
final class Tables(ctx: TaskInputOutputContext[?, ?, Text, Text]):
  private val mos                                             = new MultipleOutputs[Text, Text](ctx)
  def put(table: String, sortKey: String, json: String): Unit =
    mos.write(JobSupport.Named, new Text(sortKey), new Text(json), s"$table/part")
    ctx.getCounter(Counters.RowsGroup, table).increment(1)
  def close(): Unit = mos.close()

/** Writes pure JSON lines (no key) into per-table directories of the job output. Used by the jobs
  * whose tables are consumed by the entity-resolution tools. The job must be created with
  * `keyOut = NullWritable`.
  */
final class JsonTables(ctx: TaskInputOutputContext[?, ?, org.apache.hadoop.io.NullWritable, Text]):
  private val mos =
    new MultipleOutputs[org.apache.hadoop.io.NullWritable, Text](ctx)
  def put(table: String, json: String): Unit =
    mos.write(
      JobSupport.Named,
      org.apache.hadoop.io.NullWritable.get(),
      new Text(json),
      s"$table/part",
    )
    ctx.getCounter(Counters.RowsGroup, table.takeWhile(_ != '/')).increment(1)
  def close(): Unit = mos.close()

object JobSupport:
  /** The one registered named output; tables are chosen through the base output path. */
  val Named: String        = "out"
  val RejectPrefix: String = "!reject|"

  def count(ctx: TaskInputOutputContext[?, ?, ?, ?], c: PipelineCounter, n: Long = 1L): Unit =
    Counters.inc(ctx, c, n)

  /** Counts a reject (REJECTED and the per-reason counter). */
  def countReject(ctx: TaskInputOutputContext[?, ?, ?, ?], reason: RejectReason): Unit =
    Counters.inc(ctx, PipelineCounter.REJECTED)
    ctx.getCounter(Counters.RejectsGroup, reason.wire).increment(1)

  def codecClass(c: OutputCodec): Class[? <: CompressionCodec] = c match
    case OutputCodec.Uncompressed => classOf[DefaultCodec]
    case OutputCodec.Gzip         => classOf[GzipCodec]
    case OutputCodec.Snappy       => classOf[SnappyCodec]

  /** A new job carrying the run context, with map-output compression on and the configured output
    * codec. Output is text; tables are written through MultipleOutputs (no empty default files).
    */
  def newJob(
      conf: Configuration,
      name: String,
      s: Setup,
      reducers: Int,
      keyOut: Class[?] = classOf[Text],
  ): Job =
    val c = new Configuration(conf)
    RunContext.toConf(c, s.run)
    // The reader and mapper take the resolved value (the one in the manifest config and run ID).
    c.setInt(DblpRecordReader.MaxRecordBytesKey, s.run.maxRecordBytes)
    c.setBoolean("mapreduce.map.output.compress", true)
    c.setClass(
      "mapreduce.map.output.compress.codec",
      codecClass(s.hadoop.outputCodec),
      classOf[CompressionCodec],
    )
    val job = Job.getInstance(c, name)
    job.setJarByClass(classOf[PipelineDriver])
    job.setNumReduceTasks(reducers)
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(keyOut)
    job.setOutputValueClass(classOf[Text])
    LazyOutputFormat.setOutputFormatClass(job, classOf[TextOutputFormat[?, ?]])
    MultipleOutputs.addNamedOutput(
      job,
      Named,
      classOf[TextOutputFormat[?, ?]],
      keyOut,
      classOf[Text],
    )
    s.hadoop.outputCodec match
      case OutputCodec.Uncompressed => FileOutputFormat.setCompressOutput(job, false)
      case other                    =>
        FileOutputFormat.setCompressOutput(job, true)
        FileOutputFormat.setOutputCompressorClass(job, codecClass(other))
    job

  def outcome(jobId: String, job: Job, ok: Boolean): JobOutcome =
    def group(g: String): Map[String, Long] =
      job.getCounters.getGroup(g).iterator().asScala.map(k => k.getName -> k.getValue).toMap
    // Framework counters are split-dependent (they depend on spills), so they are kept apart from
    // the deterministic counters: shown in counters.json, never in the manifest.
    val framework =
      if job.getConfiguration.get("mapreduce.job.combine.class") == null then
        Map.empty[String, Long]
      else
        List(TaskCounter.COMBINE_INPUT_RECORDS, TaskCounter.COMBINE_OUTPUT_RECORDS)
          .map(c => c.name -> job.getCounters.findCounter(c).getValue)
          .toMap
    JobOutcome(
      jobId,
      ok,
      group(Counters.EnumGroup),
      group(Counters.RowsGroup),
      group(Counters.RejectsGroup),
      group(Counters.DecisionsGroup),
      framework,
    )

  def run(job: Job, jobId: String): JobOutcome =
    val ok = job.waitForCompletion(false)
    outcome(jobId, job, ok)

/** Partitions by the hash of the key bytes: deterministic, and the same key always meets the same
  * reducer. Used by every job (identifier key, work ID, canonical pair key).
  */
class KeyHashPartitioner extends Partitioner[Text, Text]:
  override def getPartition(key: Text, value: Text, numPartitions: Int): Int =
    val b = key.copyBytes()
    var h = 17
    var i = 0
    while i < b.length do
      h = 31 * h + b(i)
      i += 1
    (h & Integer.MAX_VALUE) % numPartitions
