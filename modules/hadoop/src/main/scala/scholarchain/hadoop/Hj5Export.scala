package scholarchain.hadoop

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper, Reducer, TaskInputOutputContext}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, MultipleOutputs}
import scholarchain.core.*

object ExportKeys:
  val JobId: String = "hj5-graph-candidate-export"

  /** Curated tables, in documented order. */
  val Tables: List[String] = List(
    "works",
    "author-mentions",
    "authorship",
    "teams",
    "pair-candidates",
    "exact-identifier-evidence",
    "institutions",
    "topics",
    "person-aliases",
    "rejects",
    "duplicates",
    "large-teams",
    "oversize-teams",
  )

  /** Tables partitioned by `year=YYYY` (`year=unknown` when a record has no valid year). */
  val YearPartitioned: Set[String] = Set("authorship", "pair-candidates")

  /** Intermediate table directories read by HJ5: (job directory, table). */
  val Sources: List[(String, String)] = List(
    "hj1"  -> "works",
    "hj1"  -> "author-mentions",
    "hj1"  -> "person-aliases",
    "hj1"  -> "duplicates",
    "hj1"  -> "rejects",
    "hj2"  -> "works",
    "hj2"  -> "author-mentions",
    "hj2"  -> "institutions",
    "hj2"  -> "topics",
    "hj2"  -> "duplicates",
    "hj2"  -> "rejects",
    "hj3"  -> "exact-identifier-evidence",
    "hj3"  -> "rejects",
    "hj4a" -> "teams",
    "hj4a" -> "authorship",
    "hj4a" -> "large-teams",
    "hj4a" -> "oversize-teams",
    "hj4a" -> "rejects",
    "hj4b" -> "pair-candidates",
    "hj4b" -> "rejects",
  )

  /** Curated tables that are unique by an entity ID, and the JSON field that holds it. Rows of the
    * same ID (the same DOI listed by several ORCID records; the same institution or topic seen in
    * several works) are merged in the export reducer, see `Dedupe`.
    */
  val EntityIdField: Map[String, String] = Map(
    "works"        -> "workId",
    "institutions" -> "institutionId",
    "topics"       -> "topicId",
  )

  private val Sep                                                     = '\u0001'
  def composite(table: String, part: String, sortKey: String): String =
    s"$table$Sep$part$Sep$sortKey"
  def split(k: String): (String, String, String) =
    val a = k.indexOf(Sep)
    val b = k.indexOf(Sep, a + 1)
    (k.substring(0, a), k.substring(a + 1, b), k.substring(b + 1))

/** HJ5 map: re-keys every intermediate line by `table, partition, sortKey`. */
class ExportMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var table: String = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    table = ctx.getInputSplit.asInstanceOf[FileSplit].getPath.getParent.getName

  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Lines.split(line.toString) match
      case None                  => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
      case Some((sortKey, json)) =>
        val part =
          if ExportKeys.YearPartitioned(table) then
            ujson
              .read(json)
              .objOpt
              .flatMap(_.get("year"))
              .collect { case ujson.Num(d) if d.isWhole => d.toInt }
              .filter(y => y >= 1000 && y <= 9999)
              .map(y => s"year=$y")
              .getOrElse("year=unknown")
          else ""
        // Entity tables are keyed by the entity ID so that every row of one ID meets in one reduce.
        val key = ExportKeys.EntityIdField
          .get(table)
          .flatMap(f => scala.util.Try(ujson.read(json)(f).str).toOption)
          .getOrElse(sortKey)
        ctx.write(new Text(ExportKeys.composite(table, part, key)), new Text(json))

/** HJ5 reduce: writes `curated/<table>/snapshot=<s>/run_id=<r>/[year=<y>/]part-r-NNNNN[.gz]`, one
  * JSON record per line, sorted by key and then by content.
  */
class ExportReducer extends Reducer[Text, Text, NullWritable, Text]:
  private type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  private var run: RunContext                          = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    mos = new MultipleOutputs[NullWritable, Text](ctx)

  override def cleanup(ctx: Ctx): Unit = mos.close()

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val (table, part, _) = ExportKeys.split(key.toString)
    val dir              =
      s"$table/snapshot=${run.snapshot}/run_id=${run.runId.stripPrefix("run:")}" +
        (if part.isEmpty then "" else s"/$part")
    val rows = Dedupe.rows(table, values.asScala.map(_.toString).toList.sorted, ctx)
    rows.foreach { v =>
      mos.write(JobSupport.Named, NullWritable.get(), new Text(v), s"$dir/part")
      ctx.getCounter(Counters.RowsGroup, table).increment(1)
      JobSupport.count(ctx, PipelineCounter.ACCEPTED)
    }

/** One row per entity ID for `works`, `institutions` and `topics`.
  *
  * Rule (deterministic, independent of value order): the winner is the row with the latest
  * `provenance.retrievedAt` (absent sorts first), ties broken by the larger JSON text. The winner
  * keeps its fields and its `provenance.parameters`; its `provenance.inputs` become the sorted
  * union of the `inputs` of every merged row, so no provenance input is lost. Merged-away rows are
  * counted: WORK_MERGED for works, ENTITY_MERGED for institutions and topics.
  */
object Dedupe:
  private def byInput(i: InputRef) = (i.inputId, i.version, i.checksum.getOrElse(""))

  private def merge[A](
      jsons: List[String],
      retrievedAt: A => Option[String],
      prov: A => Provenance,
      withProv: (A, Provenance) => A,
  )(using Contract[A], WirePickle.ReadWriter[A]): (String, Int) =
    val parsed = jsons.map { j =>
      Wire
        .read[A](j)
        .fold(e => throw new IllegalStateException(s"unreadable row: ${e.message}"), a => (j, a))
    }
    val (_, win) = parsed.maxBy((j, a) => (retrievedAt(a).getOrElse(""), j))
    val inputs   = parsed.flatMap((_, a) => prov(a).inputs).distinct.sortBy(byInput)
    val out      = withProv(win, prov(win).copy(inputs = inputs))
    (
      Wire
        .write(out)
        .fold(e => throw new IllegalStateException(s"merged row invalid: ${e.message}"), identity),
      parsed.size - 1,
    )

  def rows(
      table: String,
      sorted: List[String],
      ctx: TaskInputOutputContext[?, ?, ?, ?],
  ): List[String] =
    if sorted.size <= 1 || !ExportKeys.EntityIdField.contains(table) then sorted
    else
      val (json, dropped) = table match
        case "works" =>
          merge[WorkRecord](
            sorted,
            _.provenance.retrievedAt,
            _.provenance,
            (a, p) => a.copy(provenance = p),
          )
        case "institutions" =>
          merge[Institution](
            sorted,
            _.provenance.retrievedAt,
            _.provenance,
            (a, p) => a.copy(provenance = p),
          )
        case _ =>
          merge[Topic](
            sorted,
            _.provenance.retrievedAt,
            _.provenance,
            (a, p) => a.copy(provenance = p),
          )
      JobSupport.count(
        ctx,
        if table == "works" then PipelineCounter.WORK_MERGED else PipelineCounter.ENTITY_MERGED,
        dropped.toLong,
      )
      List(json)

object GraphCandidateExportJob:
  def submit(conf: Configuration, s: Setup, inputs: List[Path]): JobOutcome =
    val reducers = conf.getInt("scholarchain.hadoop.export-reducers", 1)
    val job      = JobSupport.newJob(conf, ExportKeys.JobId, s, reducers, classOf[NullWritable])
    job.setInputFormatClass(classOf[TextInputFormat])
    inputs.foreach(p => FileInputFormat.addInputPath(job, p))
    job.setMapperClass(classOf[ExportMapper])
    job.setReducerClass(classOf[ExportReducer])
    job.setPartitionerClass(classOf[KeyHashPartitioner])
    FileOutputFormat.setOutputPath(job, s.layout.curated)
    JobSupport.run(job, ExportKeys.JobId)
