package scholarchain.hadoop

import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}
import scholarchain.er.*

/** HJ6 BlockingCandidateJob (spec J05 + J06), four MapReduce passes, all calling the entity
  * resolution module's pure functions (`EntityViews`, `Blocking`, `Features`):
  *
  *   - HJ6a view build: curated `works` and `author-mentions`, joined by work ID, to
  *     `entity-views`.
  *   - HJ6b block-size count: primary block key to member count, with a COMBINER; keys over
  *     `er.max-block-size` go to the side file `oversize-keys`.
  *   - HJ6c candidate pass: a view goes to its primary key, or, when that key is in the side file,
  *     to each of its fallback keys. A view also goes to its exact-identifier keys (`x1|...`). A
  *     reducer group of more than `maxBlockSize` members produces NO candidate: it is listed in
  *     `oversize-blocks` (counter OVERSIZE_BLOCK), and the reducer never buffers more than
  *     `maxBlockSize` members.
  *   - HJ6d dedupe: a pair found under several keys keeps `Blocking.preferred`; output
  *     `match-candidates` (`MatchCandidate` JSON Lines).
  */

// ---------------------------------------------------------------- HJ6a

class ViewSourceMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var tag: String = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    val path = ctx.getInputSplit.asInstanceOf[FileSplit].getPath.toString
    tag = if path.contains("/author-mentions/") then "M" else "W"

  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Try(ujson.read(line.toString)("workId").str).toOption match
      case Some(workId) => ctx.write(new Text(workId), new Text(s"$tag\t${line.toString}"))
      case None         => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)

class ViewReducer extends Reducer[Text, Text, NullWritable, Text]:
  private type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var run: RunContext    = scala.compiletime.uninitialized
  private var tables: JsonTables = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    tables = new JsonTables(ctx)
  override def cleanup(ctx: Ctx): Unit = tables.close()

  private def failed(ctx: Ctx, id: String, msg: String): Unit =
    JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
    tables.put("rejects", ErKeys.reject(run, ErKeys.ViewsJobId, id, msg))
    JobSupport.countReject(ctx, RejectReason.SchemaViolation)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val workId = key.toString
    val vs     = values.asScala.map(_.toString).toList.sorted
    val works  = vs.filter(_.startsWith("W\t")).map(v => Wire.read[WorkRecord](v.drop(2)))
    val ments  = vs.filter(_.startsWith("M\t")).map(v => Wire.read[AuthorMention](v.drop(2)))
    (works ++ ments).collectFirst { case Left(e) => e } match
      case Some(e)                => failed(ctx, workId, s"unreadable record: ${e.code}")
      case None if works.size > 1 => failed(ctx, workId, "several work rows for one work ID")
      case None                   =>
        EntityViews.build(works.flatMap(_.toOption), ments.flatMap(_.toOption)).foreach { v =>
          tables.put("entity-views", WirePickle.write(v))
          JobSupport.count(ctx, PipelineCounter.ENTITY_VIEWS)
          JobSupport.count(ctx, PipelineCounter.ACCEPTED)
        }

// ---------------------------------------------------------------- HJ6b

/** Emits (primary block key, 1) for each view that has a primary key. */
class BlockCountMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private val one = new Text("1")

  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Try(WirePickle.read[EntityView](line.toString)).toOption match
      case None    => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
      case Some(v) =>
        Blocking.primary(v) match
          case Some(k) => ctx.write(new Text(k.key), one)
          case None    => JobSupport.count(ctx, PipelineCounter.UNBLOCKED_VIEW)

/** Partial sums: the combiner (a sum is associative and commutative, so it is safe). */
class BlockCountCombiner extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    var n = 0L
    values.asScala.foreach(v => n += v.toString.toLong)
    ctx.write(key, new Text(n.toString))

class BlockCountReducer extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  private var tables: Tables = scala.compiletime.uninitialized
  private var max            = 0

  override def setup(ctx: Ctx): Unit =
    max = RunContext.fromConf(ctx.getConfiguration).erConfig.maxBlockSize
    tables = new Tables(ctx)
  override def cleanup(ctx: Ctx): Unit = tables.close()

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    var n = 0L
    values.asScala.foreach(v => n += v.toString.toLong)
    val k = key.toString
    JobSupport.count(ctx, PipelineCounter.BLOCK_KEYS)
    tables.put("block-sizes", k, ujson.write(ujson.Obj("blockKey" -> k, "members" -> n.toDouble)))
    if n > max then
      JobSupport.count(ctx, PipelineCounter.OVERSIZE_KEY)
      tables.put(
        "oversize-keys",
        k,
        ujson.write(ujson.Obj("blockKey" -> k, "members" -> n.toDouble, "maxBlockSize" -> max)),
      )

// ---------------------------------------------------------------- HJ6c

object CandidateKeys:
  /** Type tags put in front of the shuffle key: P primary, F fallback, X exact identifier. */
  def tag(t: Char, blockKey: String): String = s"$t|$blockKey"

/** Routes each view to its block keys (see the HJ6 description). */
class CandidateMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var oversize: Set[String] = Set.empty

  override def setup(ctx: Ctx): Unit =
    val conf = ctx.getConfiguration
    oversize = SideFiles
      .lines(conf, new Path(conf.get(ErKeys.OversizeKeysDir)))
      .flatMap(l => Lines.split(l).map(_._1))
      .toSet

  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Try(WirePickle.read[EntityView](line.toString)).toOption match
      case None    => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
      case Some(v) =>
        val json    = new Text(line.toString)
        val primary = Blocking.primary(v).map(_.key)
        val routed  = primary match
          case Some(k) if oversize.contains(k) =>
            Blocking.fallbacks(v).map(_.key).distinct.map(CandidateKeys.tag('F', _))
          case Some(k) => List(CandidateKeys.tag('P', k))
          case None    => Nil
        (routed ++ ErKeys.exactIdKeys(v).map(CandidateKeys.tag('X', _))).foreach(k =>
          ctx.write(new Text(k), json)
        )

class CandidateReducer extends Reducer[Text, Text, Text, Text]:
  private type Ctx = Reducer[Text, Text, Text, Text]#Context
  private var run: RunContext          = scala.compiletime.uninitialized
  private var tables: Tables           = scala.compiletime.uninitialized
  private var max                      = 0
  private var base: Provenance         = scala.compiletime.uninitialized
  private var conflicts: ConflictIndex = ConflictIndex.Empty

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    max = run.erConfig.maxBlockSize
    base = run.provAll(ErKeys.CandidateJobId, withEr = true)
    conflicts = ConflictIndex.load(
      ctx.getConfiguration,
      new Path(ctx.getConfiguration.get(ErKeys.EvidenceDir)),
    )
    tables = new Tables(ctx)
  override def cleanup(ctx: Ctx): Unit = tables.close()

  private def blockKeyOf(shuffleKey: String): BlockKey =
    val keyType =
      if shuffleKey.startsWith("F|") then BlockKeyType.Fallback else BlockKeyType.Primary
    BlockKey(shuffleKey.drop(2), keyType)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val bk = blockKeyOf(key.toString)
    JobSupport.count(ctx, PipelineCounter.BLOCK_GROUPS)
    // Count every member, but never hold more than max of them.
    var n    = 0L
    val held = scala.collection.mutable.ListBuffer.empty[String]
    val it   = values.iterator()
    while it.hasNext do
      val v = it.next().toString
      n += 1
      if n <= max then held += v
    if n > max then
      JobSupport.count(ctx, PipelineCounter.OVERSIZE_BLOCK)
      tables.put(
        "oversize-blocks",
        bk.key,
        ujson.write(
          ujson.Obj(
            "blockKey"     -> bk.key,
            "blockKeyType" -> bk.keyType.wire,
            "members"      -> n.toDouble,
            "maxBlockSize" -> max,
          )
        ),
      )
    else
      val views = held.toList.sorted
        .flatMap(j => Try(WirePickle.read[EntityView](j)).toOption)
        .distinctBy(_.id)
        .sortBy(_.id)
      for
        i <- views.indices
        j <- (i + 1) until views.size
        if Blocking.eligible(views(i), views(j))
      do
        val flagged = conflicts.conflicts(views(i).id, views(j).id)
        if flagged then JobSupport.count(ctx, PipelineCounter.CANDIDATE_CONFLICT_FLAGGED)
        Blocking.candidate(views(i), views(j), bk, base, flagged) match
          case Right(c) =>
            Wire.write(c).foreach { json =>
              tables.put("candidate-stage", s"${c.leftId}|${c.rightId}", json)
              JobSupport.count(ctx, PipelineCounter.CANDIDATE_STAGED)
            }
          case Left(e) =>
            JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
            tables.put(
              "rejects",
              s"schema|${views(i).id}|${views(j).id}",
              ErKeys.reject(run, ErKeys.CandidateJobId, views(i).id, s"candidate: ${e.code}"),
            )
            JobSupport.countReject(ctx, RejectReason.SchemaViolation)

// ---------------------------------------------------------------- HJ6d

class CandidateDedupeReducer extends Reducer[Text, Text, NullWritable, Text]:
  private type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var tables: JsonTables = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit   = tables = new JsonTables(ctx)
  override def cleanup(ctx: Ctx): Unit = tables.close()

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val cs =
      values.asScala
        .map(_.toString)
        .toList
        .sorted
        .flatMap(v => Wire.read[MatchCandidate](v).toOption)
    if cs.nonEmpty then
      val best = cs.reduceLeft(Blocking.preferred)
      JobSupport.count(ctx, PipelineCounter.CANDIDATE_DUPLICATE, (cs.size - 1).toLong)
      Wire.write(best).foreach { j =>
        tables.put("match-candidates", j)
        JobSupport.count(ctx, PipelineCounter.ACCEPTED)
      }

// ---------------------------------------------------------------- drivers

object BlockingJobs:
  private def exists(conf: Configuration, p: Path): Boolean = p.getFileSystem(conf).exists(p)

  private def reducersOf(s: Setup, id: String): Int = id match
    case ErKeys.ViewsJobId => s.hadoop.reducers.teams
    case ErKeys.CountJobId => s.hadoop.reducers.identifiers
    case _                 => s.hadoop.reducers.pairs

  private def newJob(
      conf: Configuration,
      s: Setup,
      id: String,
      keyOut: Class[?] = classOf[Text],
  ): org.apache.hadoop.mapreduce.Job =
    val job = JobSupport.newJob(conf, id, s, reducersOf(s, id), keyOut)
    job.setPartitionerClass(classOf[KeyHashPartitioner])
    job

  /** HJ6a. None when the run has no curated works. */
  def submitViews(conf: Configuration, s: Setup): Option[JobOutcome] =
    val ins = List("works", "author-mentions")
      .map(t => new Path(s.layout.curated, t))
      .filter(exists(conf, _))
    if ins.isEmpty then None
    else
      val job = newJob(conf, s, ErKeys.ViewsJobId, classOf[NullWritable])
      job.setInputFormatClass(classOf[TextInputFormat])
      FileInputFormat.setInputDirRecursive(job, true)
      ins.foreach(FileInputFormat.addInputPath(job, _))
      job.setMapperClass(classOf[ViewSourceMapper])
      job.setReducerClass(classOf[ViewReducer])
      FileOutputFormat.setOutputPath(job, s.layout.work("hj6a"))
      Some(JobSupport.run(job, ErKeys.ViewsJobId))

  private def views(s: Setup): Path = s.layout.table("hj6a", "entity-views")

  /** HJ6b. None when there are no views. */
  def submitCount(conf: Configuration, s: Setup): Option[JobOutcome] =
    if !exists(conf, views(s)) then None
    else
      val job = newJob(conf, s, ErKeys.CountJobId)
      job.setInputFormatClass(classOf[TextInputFormat])
      FileInputFormat.addInputPath(job, views(s))
      job.setMapperClass(classOf[BlockCountMapper])
      job.setCombinerClass(classOf[BlockCountCombiner])
      job.setReducerClass(classOf[BlockCountReducer])
      FileOutputFormat.setOutputPath(job, s.layout.work("hj6b"))
      Some(JobSupport.run(job, ErKeys.CountJobId))

  /** HJ6c. None when there are no views. */
  def submitCandidates(conf: Configuration, s: Setup): Option[JobOutcome] =
    if !exists(conf, views(s)) then None
    else
      val job = newJob(conf, s, ErKeys.CandidateJobId)
      job.getConfiguration
        .set(ErKeys.OversizeKeysDir, s.layout.table("hj6b", "oversize-keys").toString)
      job.getConfiguration
        .set(ErKeys.EvidenceDir, s.layout.table("hj3", "exact-identifier-evidence").toString)
      job.setInputFormatClass(classOf[TextInputFormat])
      FileInputFormat.addInputPath(job, views(s))
      job.setMapperClass(classOf[CandidateMapper])
      job.setReducerClass(classOf[CandidateReducer])
      FileOutputFormat.setOutputPath(job, s.layout.work("hj6c"))
      Some(JobSupport.run(job, ErKeys.CandidateJobId))

  /** HJ6d. None when HJ6c staged no candidate. */
  def submitDedupe(conf: Configuration, s: Setup): Option[JobOutcome] =
    val in = s.layout.table("hj6c", "candidate-stage")
    if !exists(conf, in) then None
    else
      val job = newJob(conf, s, ErKeys.DedupeJobId, classOf[NullWritable])
      job.setInputFormatClass(classOf[TextInputFormat])
      FileInputFormat.addInputPath(job, in)
      job.setMapperClass(classOf[PairLineMapper])
      job.setReducerClass(classOf[CandidateDedupeReducer])
      FileOutputFormat.setOutputPath(job, s.layout.work("hj6d"))
      Some(JobSupport.run(job, ErKeys.DedupeJobId))

object EntityResolutionJobs:
  /** HJ6a to HJ6d in order, stopping at the first failure. */
  def blocking(conf: Configuration, s: Setup): List[JobOutcome] =
    val steps: List[() => Option[JobOutcome]] = List(
      () => BlockingJobs.submitViews(conf, s),
      () => BlockingJobs.submitCount(conf, s),
      () => BlockingJobs.submitCandidates(conf, s),
      () => BlockingJobs.submitDedupe(conf, s),
    )
    val out = scala.collection.mutable.ListBuffer.empty[JobOutcome]
    val it  = steps.iterator
    while it.hasNext && out.forall(_.success) do it.next()().foreach(out += _)
    out.toList

  /** HJ6 then HJ7, reporting each outcome through `step` (the driver's recorder). */
  def runAll(conf: Configuration, s: Setup, step: Option[JobOutcome] => Boolean): Boolean =
    step(BlockingJobs.submitViews(conf, s)) &&
      step(BlockingJobs.submitCount(conf, s)) &&
      step(BlockingJobs.submitCandidates(conf, s)) &&
      step(BlockingJobs.submitDedupe(conf, s)) &&
      step(MatchDecisionJob.submit(conf, s))

  /** Entity-resolution invariants over the job outcomes (None when the ER jobs did not run). */
  def invariantError(os: List[JobOutcome]): Option[String] =
    def of(id: String) = os.find(_.jobId == id)
    val staged         = of(ErKeys.CandidateJobId).map(_.c(PipelineCounter.CANDIDATE_STAGED))
    val dedupe         = of(ErKeys.DedupeJobId)
    val decided        = of(ErKeys.DecisionJobId)
    val dedupeIn       = dedupe.map(_.c(PipelineCounter.INPUT_RECORDS))
    val unique         = dedupe.map(_.c(PipelineCounter.ACCEPTED))
    val dup            = dedupe.map(_.c(PipelineCounter.CANDIDATE_DUPLICATE))
    val inDecisions    = decided.map(_.c(PipelineCounter.DECISIONS))
    val written        = decided.map(_.decisions.values.sum)
    if staged.exists(st => dedupeIn.exists(_ != st)) then
      Some(s"HJ6d read ${dedupeIn.get} rows but HJ6c staged ${staged.get}")
    else if unique.exists(u => dup.exists(d => staged.exists(_ != u + d))) then
      Some(s"HJ6d: unique(${unique.get}) + duplicates(${dup.get}) != staged(${staged.get})")
    else if unique.exists(u => inDecisions.exists(_ != u)) then
      Some(s"HJ7 decided ${inDecisions.get} candidates but HJ6d wrote ${unique.get}")
    else if inDecisions.exists(d => written.exists(_ != d)) then
      Some(s"HJ7 decision rows (${written.get}) != decided (${inDecisions.get})")
    else None
