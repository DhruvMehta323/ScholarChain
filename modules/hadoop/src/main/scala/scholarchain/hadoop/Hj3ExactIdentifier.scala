package scholarchain.hadoop

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import scholarchain.core.*

object IdentifierKeys:
  val JobId: String = "hj3-exact-identifier"

/** Splits an intermediate table line `sortKey TAB json` and re-keys it. Shared by HJ3 and HJ4b. */
object Lines:
  def split(line: String): Option[(String, String)] =
    val i = line.indexOf('\t')
    if i < 0 then None else Some((line.substring(0, i), line.substring(i + 1)))

/** HJ3 map: identifier reference lines in, `idType|normalizedValue -> reference` out. */
class IdentifierMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context

  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Lines.split(line.toString) match
      case None =>
        JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
      case Some((_, json)) =>
        val r = WirePickle.read[IdRef](json)
        if r.idType == "none" then JobSupport.count(ctx, PipelineCounter.MISSING_KEY)
        else ctx.write(new Text(s"${r.idType}|${r.value}"), new Text(json))

/** HJ3 reduce: classify each identifier key. Priority CONFLICT, then EXACT_LINK, then DUPLICATE.
  *   - CONFLICT: the identifier of a work-level type (doi, arxiv, openalex) sits on several
  *     distinct DBLP works, or the linked records carry disagreeing values for another
  *     authoritative identifier.
  *   - EXACT_LINK: a DBLP record and at least one external-source record share the identifier.
  *   - DUPLICATE: several records of one source share it.
  * A key with fewer than two distinct records is not evidence and is not written.
  */
class IdentifierReducer extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  private var run: RunContext = scala.compiletime.uninitialized
  private var tables: Tables  = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    tables = new Tables(ctx)

  override def cleanup(ctx: Ctx): Unit = tables.close()

  /** `provenance.inputs` in a fixed order (inputId, version, checksum). */
  private def sortedInputs(p: Provenance): Provenance =
    p.copy(inputs = p.inputs.sortBy(i => (i.inputId, i.version, i.checksum.getOrElse(""))))

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val k      = key.toString
    val sep    = k.indexOf('|')
    val idType = k.substring(0, sep)
    val value  = k.substring(sep + 1)
    // Values arrive in an arbitrary (merge) order on a cluster: sort the JSON strings first.
    val refs =
      values.asScala.map(_.toString).toList.sorted.map(v => WirePickle.read[IdRef](v)).distinct
    val linked = refs.map(_.ref).distinct.sorted
    if linked.size >= 2 then
      val workLevel = idType != "orcid"
      val sources   = refs.map(_.source).distinct.sortBy(_.wire)
      val dblpRefs  = refs.filter(_.source == SourceType.Dblp).map(_.ref).distinct
      val disagree  =
        workLevel && refs.flatMap(_.ids).groupBy(_._1).exists(_._2.map(_._2).distinct.size > 1)
      val conflict = workLevel && (dblpRefs.size >= 2 || disagree)
      val outcome  =
        if conflict then ExactIdentifierOutcome.Conflict
        else if sources.contains(SourceType.Dblp) && sources.size >= 2 then
          ExactIdentifierOutcome.ExactLink
        else ExactIdentifierOutcome.Duplicate
      val tpe = IdentifierType.values.find(_.wire == idType).get
      val ev  = ExactIdentifierEvidence(
        SchemaVersion.Current,
        tpe,
        value,
        linked,
        outcome,
        sortedInputs(
          run.prov(
            SourceType.ScholarChain,
            (sources.map(_.wire).filter(run.inputs.contains) ++
              (if run.inputs.contains("dtd") && sources.contains(SourceType.Dblp) then Seq("dtd")
               else Nil)).sorted,
            IdentifierKeys.JobId,
          )
        ),
      )
      Wire.write(ev) match
        case Right(j) =>
          tables.put("exact-identifier-evidence", k, j)
          JobSupport.count(ctx, PipelineCounter.ACCEPTED)
          outcome match
            case ExactIdentifierOutcome.Conflict  => JobSupport.count(ctx, PipelineCounter.CONFLICT)
            case ExactIdentifierOutcome.ExactLink =>
              JobSupport.count(ctx, PipelineCounter.EXACT_LINK)
            case ExactIdentifierOutcome.Duplicate =>
              JobSupport.count(ctx, PipelineCounter.DUPLICATE)
        case Left(e) =>
          JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
          val f = ParseFailure(RejectReason.SchemaViolation, e.message, Some(k))
          val r =
            Rejects.make(run, IdentifierKeys.JobId, SourceType.ScholarChain, Some(k), None, f, "")
          tables.put("rejects", s"schema|$k", Rejects.json(r))
          JobSupport.countReject(ctx, f.reason)

object ExactIdentifierJob:
  def submit(conf: Configuration, s: Setup, inputs: List[Path]): JobOutcome =
    val job = JobSupport.newJob(conf, IdentifierKeys.JobId, s, s.hadoop.reducers.identifiers)
    job.setInputFormatClass(classOf[TextInputFormat])
    inputs.foreach(p => FileInputFormat.addInputPath(job, p))
    job.setMapperClass(classOf[IdentifierMapper])
    job.setReducerClass(classOf[IdentifierReducer])
    job.setPartitionerClass(classOf[KeyHashPartitioner])
    FileOutputFormat.setOutputPath(job, s.layout.work("hj3"))
    JobSupport.run(job, IdentifierKeys.JobId)
