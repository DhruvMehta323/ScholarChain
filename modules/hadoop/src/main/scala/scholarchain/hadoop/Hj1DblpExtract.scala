package scholarchain.hadoop

import java.nio.charset.Charset

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{BytesWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import scholarchain.core.*
import scholarchain.core.pipeline.{DuplicateRecord, PersonAlias}

object DblpKeys:
  val DtdPath: String      = "scholarchain.hadoop.dblp.dtd-path"
  val PersonPrefix: String = "!person|"
  val JobId: String        = "hj1-dblp-extract"

object DblpMapper:
  private val KeyAttr = """key="([^"]+)"""".r

/** HJ1 map: one DBLP record in, one parsed work (keyed by DBLP key), person or reject out. */
class DblpMapper extends Mapper[LongWritable, BytesWritable, Text, Text]:
  import DblpMapper.KeyAttr
  private type Ctx = Mapper[LongWritable, BytesWritable, Text, Text]#Context
  private var run: RunContext    = scala.compiletime.uninitialized
  private var parser: DblpParser = scala.compiletime.uninitialized
  private var cs: Charset        = scala.compiletime.uninitialized
  private var maxBytes           = 0

  override def setup(ctx: Ctx): Unit =
    val conf = ctx.getConfiguration
    run = RunContext.fromConf(conf)
    cs = Charset.forName(run.dblpCharset)
    maxBytes =
      conf.getInt(DblpRecordReader.MaxRecordBytesKey, DblpRecordReader.DefaultMaxRecordBytes)
    val dtd = new Path(
      Option(conf.get(DblpKeys.DtdPath)).getOrElse(sys.error("dblp dtd path is not set"))
    )
    val in    = dtd.getFileSystem(conf).open(dtd)
    val bytes =
      try in.readAllBytes()
      finally in.close()
    parser = new DblpParser(DblpEntities.fromDtd(bytes), cs)

  private def reject(ctx: Ctx, offset: Long, f: ParseFailure, bytes: Array[Byte]): Unit =
    val r = Rejects.make(
      run,
      DblpKeys.JobId,
      SourceType.Dblp,
      KeyAttr.findFirstMatchIn(Rejects.lossyText(bytes, cs)).map(_.group(1)),
      Some(offset),
      f,
      Rejects.lossyText(bytes, cs),
    )
    ctx.write(new Text(f"${JobSupport.RejectPrefix}$offset%020d"), new Text(Rejects.json(r)))
    JobSupport.countReject(ctx, f.reason)

  override def map(offset: LongWritable, value: BytesWritable, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    JobSupport.count(ctx, PipelineCounter.INPUT_DBLP)
    val bytes = value.copyBytes()
    val off   = offset.get
    if bytes.length >= maxBytes then
      reject(
        ctx,
        off,
        ParseFailure(RejectReason.OversizedRecord, s"record exceeds $maxBytes bytes"),
        bytes,
      )
    else
      parser.parse(bytes) match
        case Left(f)                        => reject(ctx, off, f, bytes)
        case Right(ParsedWork(w, warnings)) =>
          warnings
            .filter(x => x == "invalid_orcid" || x == "invalid_identifier")
            .foreach(_ => JobSupport.count(ctx, PipelineCounter.INVALID_IDENTIFIER_SKIPPED))
          ctx.write(new Text(w.key), new Text(WirePickle.write(ParsedDblp(off, w))))
        case Right(ParsedPersonRecord(p, warnings)) =>
          JobSupport.count(ctx, PipelineCounter.WWW_RECORDS)
          warnings.foreach(_ => JobSupport.count(ctx, PipelineCounter.INVALID_IDENTIFIER_SKIPPED))
          ctx.write(
            new Text(DblpKeys.PersonPrefix + p.key),
            new Text(WirePickle.write(ParsedPerson(off, p))),
          )

/** HJ1 reduce: duplicate detection by DBLP key (winner = lowest byte offset), then WorkRecords,
  * AuthorMentions, identifier references, person aliases, duplicates and rejects.
  */
class DblpReducer extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  private var run: RunContext = scala.compiletime.uninitialized
  private var tables: Tables  = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    tables = new Tables(ctx)

  override def cleanup(ctx: Ctx): Unit = tables.close()

  private def schemaFailure(ctx: Ctx, workKey: String, offset: Long, msg: String): Unit =
    JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
    val f = ParseFailure(RejectReason.SchemaViolation, msg, Some(workKey))
    val r = Rejects.make(run, DblpKeys.JobId, SourceType.Dblp, Some(workKey), Some(offset), f, "")
    tables.put("rejects", f"schema|$workKey|$offset%020d", Rejects.json(r))
    JobSupport.countReject(ctx, f.reason)

  private def provAt(offset: Long): Provenance =
    run.prov(
      SourceType.Dblp,
      Seq("dblp", "dtd"),
      DblpKeys.JobId,
      extraParams = Map("inputByteOffset" -> offset.toString),
    )

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val k  = key.toString
    val vs = values.asScala.map(_.toString).toList.sorted
    if k.startsWith(JobSupport.RejectPrefix) then
      vs.foreach(v => tables.put("rejects", k.drop(1), v))
    else if k.startsWith(DblpKeys.PersonPrefix) then
      person(ctx, k.stripPrefix(DblpKeys.PersonPrefix), vs)
    else work(ctx, k, vs)

  private def person(ctx: Ctx, aliasKey: String, vs: List[String]): Unit =
    val ps    = vs.map(v => WirePickle.read[ParsedPerson](v)).sortBy(_.offset)
    val win   = ps.head
    val alias = PersonAlias(
      SchemaVersion.Current,
      aliasKey,
      win.person.names,
      win.person.orcids,
      win.person.urls,
      provAt(win.offset),
    )
    Wire.write(alias) match
      case Right(j) =>
        tables.put("person-aliases", aliasKey, j)
        JobSupport.count(ctx, PipelineCounter.PERSON_ALIASES)
      case Left(e) => schemaFailure(ctx, aliasKey, win.offset, e.message)
    ps.tail.foreach(d => duplicate(ctx, aliasKey, win.offset, d.offset))

  private def duplicate(ctx: Ctx, recordKey: String, winner: Long, dup: Long): Unit =
    JobSupport.count(ctx, PipelineCounter.DUPLICATE)
    val d = DuplicateRecord(
      SchemaVersion.Current,
      SourceType.Dblp,
      recordKey,
      s"offset:$winner",
      s"offset:$dup",
      provAt(dup),
    )
    Wire.write(d).foreach(j => tables.put("duplicates", f"$recordKey|$dup%020d", j))

  private def work(ctx: Ctx, recordKey: String, vs: List[String]): Unit =
    val ps  = vs.map(v => WirePickle.read[ParsedDblp](v)).sortBy(_.offset)
    val win = ps.head
    ps.tail.foreach(d => duplicate(ctx, recordKey, win.offset, d.offset))
    val w        = win.work
    val prov     = provAt(win.offset)
    val venue    = "venue:dblp:" + w.key.split('/').take(2).mkString("/")
    val workJson = Wire.write(
      WorkRecord(SchemaVersion.Current, w.workId, w.title, w.year, w.doi, Some(venue), Nil, prov)
    )
    val mentions = w.authors.zipWithIndex.map { case (a, i) =>
      Ids
        .mentionId(w.workId, i)
        .flatMap(mid =>
          Wire
            .write(AuthorMention(SchemaVersion.Current, mid, w.workId, i, a.name, a.orcid, prov))
            .map(j => (mid, a, j))
        )
    }
    val firstError = (workJson :: mentions.map(_.map(_._3))).collectFirst { case Left(e) => e }
    firstError match
      case Some(e) => schemaFailure(ctx, recordKey, win.offset, e.message)
      case None    =>
        tables.put("works", w.workId, workJson.toOption.get)
        val ms = mentions.flatMap(_.toOption)
        ms.foreach((mid, _, j) => tables.put("author-mentions", mid, j))
        val own  = (w.doi.map("doi" -> _) ++ w.arxiv.map("arxiv" -> _)).toMap
        val refs = w.doi.toList.map(d => IdRef("doi", d, w.workId, SourceType.Dblp, own)) ++
          w.arxiv.toList.map(a => IdRef("arxiv", a, w.workId, SourceType.Dblp, own)) ++
          (if own.isEmpty then List(IdRef("none", "-", w.workId, SourceType.Dblp, Map.empty))
           else Nil) ++
          ms.flatMap((mid, a, _) =>
            a.orcid.map(o => IdRef("orcid", o, mid, SourceType.Dblp, Map.empty))
          )
        refs.foreach(r =>
          tables.put("id-refs", s"${r.idType}|${r.value}|${r.ref}", WirePickle.write(r))
        )
        JobSupport.count(ctx, PipelineCounter.ACCEPTED)
        JobSupport.count(ctx, PipelineCounter.SELF_PAIR_SKIPPED, w.droppedDuplicateAuthors.toLong)

object DblpExtractJob:
  def submit(conf: Configuration, s: Setup): JobOutcome =
    val job = JobSupport.newJob(conf, DblpKeys.JobId, s, s.hadoop.reducers.normalize)
    job.getConfiguration.set(DblpKeys.DtdPath, s.args.dtd.toString)
    job.setInputFormatClass(classOf[DblpRecordInputFormat])
    FileInputFormat.addInputPath(job, s.args.dblp)
    job.setMapperClass(classOf[DblpMapper])
    job.setReducerClass(classOf[DblpReducer])
    job.setPartitionerClass(classOf[KeyHashPartitioner])
    FileOutputFormat.setOutputPath(job, s.layout.work("hj1"))
    JobSupport.run(job, DblpKeys.JobId)
