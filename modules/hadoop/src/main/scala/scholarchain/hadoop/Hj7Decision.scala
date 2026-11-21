package scholarchain.hadoop

import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import scholarchain.core.*
import scholarchain.er.*

/** HJ7 MatchDecisionJob: applies the `er-v1` policy (`Policy.decide`, `Decisions.build`) to every
  * `MatchCandidate` of HJ6d and writes `match-decisions/outcome=<accepted|rejected|unresolved|
  * conflict>` (`MatchDecisionV2` JSON Lines).
  *
  * Exact-identifier evidence reaches the policy through the candidate: HJ6 sets the feature
  * `exact_id_conflict` from the HJ3 CONFLICT evidence, and the id-agreement features come from the
  * views. The policy turns them into path `exact_id`. No LLM is called in MapReduce: an unresolved
  * candidate inside the LLM band is counted (LLM_ELIGIBLE) and left unresolved. A decision for a
  * candidate flagged `exact_id_conflict` that is not CONFLICT fails the task (it cannot happen with
  * `er-v1`; the check guards the invariant).
  */
class CandidateKeyMapper extends Mapper[LongWritable, Text, Text, Text]:
  private type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    JobSupport.count(ctx, PipelineCounter.INPUT_RECORDS)
    Try {
      val j = ujson.read(line.toString)
      s"${j("leftId").str}|${j("rightId").str}"
    }.toOption match
      case Some(k) => ctx.write(new Text(k), line)
      case None    => JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)

class DecisionReducer extends Reducer[Text, Text, NullWritable, Text]:
  private type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var run: RunContext    = scala.compiletime.uninitialized
  private var cfg: ErConfig      = scala.compiletime.uninitialized
  private var prov: Provenance   = scala.compiletime.uninitialized
  private var tables: JsonTables = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    run = RunContext.fromConf(ctx.getConfiguration)
    cfg = run.erConfig
    prov = run.provAll(ErKeys.DecisionJobId, withEr = true)
    tables = new JsonTables(ctx)
  override def cleanup(ctx: Ctx): Unit = tables.close()

  private def failed(ctx: Ctx, id: String, msg: String): Unit =
    JobSupport.count(ctx, PipelineCounter.SCHEMA_FAILURE)
    tables.put("rejects", ErKeys.reject(run, ErKeys.DecisionJobId, id, msg))
    JobSupport.countReject(ctx, RejectReason.SchemaViolation)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    values.asScala.map(_.toString).toList.sorted.foreach { v =>
      Wire.read[MatchCandidate](v) match
        case Left(e)  => failed(ctx, key.toString, s"candidate: ${e.code}")
        case Right(c) =>
          val r = Policy.decide(c.entityKind, c.features, cfg.thresholds(c.entityKind))
          if c.features
              .get("exact_id_conflict")
              .contains(1.0) && r.outcome != ResolutionOutcome.Conflict
          then
            throw new IllegalStateException(
              s"invariant: ${c.leftId}|${c.rightId} carries HJ3 CONFLICT evidence but was ${r.outcome.wire}"
            )
          Decisions.build(c, r, cfg, run.runId, prov).flatMap(Wire.write(_)) match
            case Left(e)  => failed(ctx, key.toString, s"decision: ${e.code}")
            case Right(j) =>
              tables.put(s"match-decisions/outcome=${r.outcome.wire}", j)
              JobSupport.count(ctx, PipelineCounter.DECISIONS)
              JobSupport.count(ctx, PipelineCounter.ACCEPTED)
              if r.llmEligible then JobSupport.count(ctx, PipelineCounter.LLM_ELIGIBLE)
              if r.outcome == ResolutionOutcome.Conflict then
                JobSupport.count(ctx, PipelineCounter.CONFLICT)
              ctx
                .getCounter(Counters.DecisionsGroup, s"${r.outcome.wire}.${r.path.wire}")
                .increment(1)
    }

object MatchDecisionJob:
  /** None when HJ6 produced no candidate. */
  def submit(conf: Configuration, s: Setup): Option[JobOutcome] =
    val in = s.layout.table("hj6d", "match-candidates")
    if !in.getFileSystem(conf).exists(in) then None
    else
      val job =
        JobSupport.newJob(
          conf,
          ErKeys.DecisionJobId,
          s,
          s.hadoop.reducers.pairs,
          classOf[NullWritable],
        )
      job.setInputFormatClass(classOf[TextInputFormat])
      FileInputFormat.addInputPath(job, in)
      job.setMapperClass(classOf[CandidateKeyMapper])
      job.setReducerClass(classOf[DecisionReducer])
      job.setPartitionerClass(classOf[KeyHashPartitioner])
      FileOutputFormat.setOutputPath(job, s.layout.work("hj7"))
      Some(JobSupport.run(job, ErKeys.DecisionJobId))
