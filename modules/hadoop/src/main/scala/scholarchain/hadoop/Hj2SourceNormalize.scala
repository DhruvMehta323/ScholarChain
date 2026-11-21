package scholarchain.hadoop

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{BytesWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import scholarchain.core.*
import scholarchain.core.pipeline.DuplicateRecord

object NormalizeKeys:
  val JobId: String = Normalizers.JobId

/** Shared HJ2 map: one envelope file in, one bundle (keyed by source and source record ID) out. A
  * Crossref, OpenAlex or ORCID mapper is a subclass that only picks the normalizer.
  */
abstract class NormalizeMapper(
    source: SourceType,
    inputCounter: PipelineCounter,
    normalize: (String, Array[Byte], RunContext) => Either[ParseFailure, Normalized],
) extends Mapper[Text, BytesWritable, Text, Text]:
  private type Ctx = Mapper[Text, BytesWritable, Text, Text]#Context
  private var run: RunContext = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit = run = RunContext.fromConf(ctx.getConfiguration)

  override def map(path: Text, value: BytesWritable, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    JobSupport.count(ctx, inputCounter)
    val name  = new Path(path.toString).getName
    val bytes = value.copyBytes()
    normalize(name, bytes, run) match
      case Left(f) =>
        val r = Rejects.make(
          run,
          NormalizeKeys.JobId,
          source,
          Some(name),
          Some(0L),
          f,
          Rejects.utf8(bytes),
        )
        ctx.write(
          new Text(s"${JobSupport.RejectPrefix}${source.wire}|$name"),
          new Text(Rejects.json(r)),
        )
        JobSupport.countReject(ctx, f.reason)
      case Right(n) if n.upstreamMissing => JobSupport.count(ctx, PipelineCounter.UPSTREAM_MISSING)
      case Right(n)                      =>
        n.warnings.foreach {
          case "missing_year" => JobSupport.count(ctx, PipelineCounter.WORK_SKIPPED)
          case _              => JobSupport.count(ctx, PipelineCounter.INVALID_IDENTIFIER_SKIPPED)
        }
        if n.yearMissing then JobSupport.count(ctx, PipelineCounter.MISSING_YEAR_KEPT)
        ctx.write(
          new Text(s"${source.wire}|${n.sourceRecordId}"),
          new Text(WirePickle.write(n.bundle)),
        )

class CrossrefNormalizeMapper
    extends NormalizeMapper(
      SourceType.Crossref,
      PipelineCounter.INPUT_CROSSREF,
      Normalizers.crossref,
    )
class OpenAlexNormalizeMapper
    extends NormalizeMapper(
      SourceType.OpenAlex,
      PipelineCounter.INPUT_OPENALEX,
      Normalizers.openalex,
    )
class OrcidNormalizeMapper
    extends NormalizeMapper(SourceType.Orcid, PipelineCounter.INPUT_ORCID, Normalizers.orcid)

/** HJ2 reduce: dedupe by source record ID (winner = latest `retrieved_at`, ties broken by file
  * checksum), write the winner's rows and the losers to `duplicates`.
  */
class NormalizeReducer extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  private var run: RunContext = scala.compiletime.uninitialized
  private var tables: Tables  = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    tables = new Tables(ctx)

  override def cleanup(ctx: Ctx): Unit = tables.close()

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val k  = key.toString
    val vs = values.asScala.map(_.toString).toList.sorted
    if k.startsWith(JobSupport.RejectPrefix) then
      vs.foreach(v => tables.put("rejects", k.drop(1), v))
    else
      // N4: latest retrieved_at AS AN INSTANT, then file checksum, then the JSON text.
      val bundles = vs
        .map(v => (WirePickle.read[Bundle](v), v))
        .sortBy((b, v) => (Dedupe.instantKey(Some(b.retrievedAt)), b.fileSha256, v))
        .map(_._1)
      val win = bundles.last
      bundles.init.foreach { d =>
        JobSupport.count(ctx, PipelineCounter.DUPLICATE)
        val prov = run.prov(
          d.source,
          Seq(d.source.wire),
          NormalizeKeys.JobId,
          List(InputRef(d.fileName, d.retrievedAt, Some(d.fileSha256))),
        )
        val rec = DuplicateRecord(
          SchemaVersion.Current,
          d.source,
          d.sourceRecordId,
          win.fileName,
          d.fileName,
          prov,
        )
        Wire
          .write(rec)
          .foreach(j =>
            tables.put("duplicates", s"${d.source.wire}|${d.sourceRecordId}|${d.fileName}", j)
          )
      }
      win.rows.foreach(r => tables.put(r.table, r.sortKey, r.json))
      JobSupport.count(ctx, PipelineCounter.ACCEPTED)

object SourceNormalizeJob:
  /** Returns None when the run has no enrichment source. */
  def submit(conf: Configuration, s: Setup): Option[JobOutcome] =
    val sources = List(
      s.args.crossref.map(p => (p, classOf[CrossrefNormalizeMapper])),
      s.args.openalex.map(p => (p, classOf[OpenAlexNormalizeMapper])),
      s.args.orcid.map(p => (p, classOf[OrcidNormalizeMapper])),
    ).flatten
    if sources.isEmpty then None
    else
      val job = JobSupport.newJob(conf, NormalizeKeys.JobId, s, s.hadoop.reducers.normalize)
      sources.foreach((p, m) =>
        org.apache.hadoop.mapreduce.lib.input.MultipleInputs
          .addInputPath(job, p, classOf[WholeFileInputFormat], m)
      )
      FileInputFormat.setInputPathFilter(job, classOf[JsonFileFilter])
      job.setReducerClass(classOf[NormalizeReducer])
      job.setPartitionerClass(classOf[KeyHashPartitioner])
      FileOutputFormat.setOutputPath(job, s.layout.work("hj2"))
      Some(JobSupport.run(job, NormalizeKeys.JobId))
