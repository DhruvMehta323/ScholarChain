package scholarchain.hadoop

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import scholarchain.core.*

/** Pure pair expander. A team of `n` distinct mentions has exactly `n*(n-1)/2` unordered pairs. */
object PairExpander:
  /** Number of pairs of a team of `n` authors (n >= 0). */
  def pairCount(n: Int): Long = if n < 2 then 0L else n.toLong * (n - 1) / 2

  /** All pairs of the team of `teamSize` mentions of `workId`, positions i < j in the order (0,1),
    * (0,2) ... (n-2,n-1). Each candidate is built by `MentionPairCandidate.build`, hence in
    * canonical order; the iterator is lazy so a large team is never held in memory.
    */
  def expand(
      workId: String,
      year: Int,
      teamSize: Int,
      prov: Provenance,
  ): Iterator[Either[ValidationError, MentionPairCandidate]] =
    for
      i <- Iterator.range(0, math.max(teamSize, 0))
      j <- Iterator.range(i + 1, math.max(teamSize, 0))
    yield MentionPairCandidate.build(workId, year, i, j, prov)

object TeamKeys:
  val TeamJobId: String  = "hj4a-team-expand"
  val PairJobId: String  = "hj4b-pair-dedupe"
  val StageTable: String = "pair-stage"

/** HJ4a map: `works` and `author-mentions` lines of HJ1, re-keyed by work ID and tagged `W` / `M`.
  */
class TeamMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var tag: String = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    val table = ctx.getInputSplit.asInstanceOf[FileSplit].getPath.getParent.getName
    tag = if table == "works" then "W" else "M"

  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Lines.split(line.toString) match
      case None            => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
      case Some((_, json)) =>
        val workId = ujson.read(json)("workId").str
        ctx.write(new Text(workId), new Text(s"$tag\t$json"))

/** HJ4a reduce, keyed by work ID: join the work with its mentions, write the team, one authorship
  * per mention, large/oversize flags, and (below the hard cap) the pair candidates.
  *
  * Team size = the number of distinct mentions of the work, i.e. distinct author strings after
  * HJ1's duplicate-name handling (homonym suffixes distinct, repeats dropped). A team of one has no
  * pairs. A team whose pair count exceeds the hard cap is routed whole to `oversize-teams`.
  */
class TeamReducer extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  private var run: RunContext = scala.compiletime.uninitialized
  private var tables: Tables  = scala.compiletime.uninitialized
  private var threshold       = 0
  private var cap             = 0

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    threshold = run.largeTeamThreshold
    cap = run.pairExpansionHardCap
    tables = new Tables(ctx)

  override def cleanup(ctx: Ctx): Unit = tables.close()

  private def failed(ctx: Ctx, workId: String, msg: String): Unit =
    JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
    val f = ParseFailure(RejectReason.SchemaViolation, msg, Some(workId))
    val r = Rejects.make(run, TeamKeys.TeamJobId, SourceType.Dblp, Some(workId), None, f, "")
    tables.put("rejects", s"schema|$workId", Rejects.json(r))
    JobSupport.countReject(ctx, f.reason)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val workId     = key.toString
    val vs         = values.asScala.map(_.toString).toList.sorted
    val works      = vs.filter(_.startsWith("W\t")).map(_.drop(2))
    val ments      = vs.filter(_.startsWith("M\t")).map(_.drop(2))
    val parsedWork = works.headOption.map(Wire.read[WorkRecord](_))
    val parsedMs   = ments.map(Wire.read[AuthorMention](_))
    (parsedWork, parsedMs.collectFirst { case Left(e) => e }) match
      case (None, _)              => failed(ctx, workId, "author mentions without a work record")
      case (Some(Left(e)), _)     => failed(ctx, workId, s"work record: ${e.code}")
      case (_, Some(e))           => failed(ctx, workId, s"mention record: ${e.code}")
      case (Some(Right(w)), None) =>
        val ms = parsedMs.flatMap(_.toOption).sortBy(_.position)
        if ms.isEmpty || ms.map(_.position) != ms.indices.toList then
          failed(ctx, workId, "mention positions are not 0..n-1")
        else team(ctx, w, ms)

  private def team(ctx: Ctx, w: WorkRecord, ms: List[AuthorMention]): Unit =
    val n        = ms.size
    val pairs    = PairExpander.pairCount(n)
    val prov     = run.prov(SourceType.Dblp, Seq("dblp", "dtd"), TeamKeys.TeamJobId)
    val teamJson =
      Wire.write(MentionTeam(SchemaVersion.Current, w.workId, w.year, n, ms.map(_.mentionId), prov))
    val auth = ms.map(m =>
      Wire
        .write(
          MentionAuthorship(SchemaVersion.Current, m.mentionId, w.workId, m.position, w.year, prov)
        )
        .map(m -> _)
    )
    (teamJson :: auth.map(_.map(_._2))).collectFirst { case Left(e) => e } match
      case Some(e) => failed(ctx, w.workId, e.message)
      case None    =>
        tables.put("teams", w.workId, teamJson.toOption.get)
        auth.flatMap(_.toOption).foreach((m, j) => tables.put("authorship", m.mentionId, j))
        JobSupport.count(ctx, PipelineCounter.ACCEPTED)
        if n > threshold then
          JobSupport.count(ctx, PipelineCounter.LARGE_AUTHOR_TEAM)
          Wire
            .write(LargeTeam(SchemaVersion.Current, w.workId, n, pairs, threshold, prov))
            .foreach(j => tables.put("large-teams", w.workId, j))
        if n >= 2 then
          if pairs > cap.toLong then
            JobSupport.count(ctx, PipelineCounter.OVERSIZE_AUTHOR_TEAM)
            Wire
              .write(OversizeTeam(SchemaVersion.Current, w.workId, n, pairs, cap, prov))
              .foreach(j => tables.put("oversize-teams", w.workId, j))
          else
            JobSupport.count(ctx, PipelineCounter.EXPECTED_PAIRS, pairs)
            PairExpander.expand(w.workId, w.year, n, prov).foreach {
              case Right(p) =>
                Wire.write(p) match
                  case Right(j) =>
                    tables.put(TeamKeys.StageTable, s"${p.pair.first}|${p.pair.second}", j)
                    JobSupport.count(ctx, PipelineCounter.EMITTED_PAIRS)
                  case Left(e) => failed(ctx, w.workId, s"pair: ${e.code}")
              case Left(e) => failed(ctx, w.workId, s"pair: ${e.code}")
            }

/** HJ4b map: `pairKey TAB json` lines re-keyed by the canonical pair key. */
class PairLineMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Lines.split(line.toString) match
      case Some((k, json)) => ctx.write(new Text(k), new Text(json))
      case None            => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)

/** HJ4b reduce, partitioned by the canonical pair key: one candidate per (pair, work). A repeat of
  * the same work for the same pair is dropped and counted as DUPLICATE_PAIR. Weights are NOT
  * aggregated here (Spark does that in Phase 06).
  */
class PairReducer extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  private var tables: Tables = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit   = tables = new Tables(ctx)
  override def cleanup(ctx: Ctx): Unit = tables.close()

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val k    = key.toString
    val recs = values.asScala.map(_.toString).toList.sorted.flatMap { v =>
      Wire.read[MentionPairCandidate](v) match
        case Right(c) => List((c.workId, v))
        case Left(_)  => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE); Nil
    }
    val byWork = recs.groupBy(_._1).toList.sortBy(_._1)
    byWork.foreach { (workId, rs) =>
      JobSupport.count(ctx, PipelineCounter.DUPLICATE_PAIR, (rs.size - 1).toLong)
      tables.put("pair-candidates", s"$k|$workId", rs.head._2)
      JobSupport.count(ctx, PipelineCounter.ACCEPTED)
    }

object TeamPairJob:
  def submitTeams(conf: Configuration, s: Setup): JobOutcome =
    val job = JobSupport.newJob(conf, TeamKeys.TeamJobId, s, s.hadoop.reducers.teams)
    job.setInputFormatClass(classOf[TextInputFormat])
    FileInputFormat.addInputPath(job, s.layout.table("hj1", "works"))
    FileInputFormat.addInputPath(job, s.layout.table("hj1", "author-mentions"))
    job.setMapperClass(classOf[TeamMapper])
    job.setReducerClass(classOf[TeamReducer])
    job.setPartitionerClass(classOf[KeyHashPartitioner])
    FileOutputFormat.setOutputPath(job, s.layout.work("hj4a"))
    JobSupport.run(job, TeamKeys.TeamJobId)

  /** Returns None when HJ4a staged no pairs (no pair-stage directory). */
  def submitPairs(conf: Configuration, s: Setup): Option[JobOutcome] =
    val in = s.layout.table("hj4a", TeamKeys.StageTable)
    if !in.getFileSystem(conf).exists(in) then None
    else
      val job = JobSupport.newJob(conf, TeamKeys.PairJobId, s, s.hadoop.reducers.pairs)
      job.setInputFormatClass(classOf[TextInputFormat])
      FileInputFormat.addInputPath(job, in)
      job.setMapperClass(classOf[PairLineMapper])
      job.setReducerClass(classOf[PairReducer])
      job.setPartitionerClass(classOf[KeyHashPartitioner])
      FileOutputFormat.setOutputPath(job, s.layout.work("hj4b"))
      Some(JobSupport.run(job, TeamKeys.PairJobId))
