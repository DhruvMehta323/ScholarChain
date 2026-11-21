package scholarchain.hadoop

import java.nio.charset.{Charset, StandardCharsets}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.{Configurable, Configuration, Configured}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scholarchain.core.*

/** Arguments of the driver and of every per-job tool. Paths are Hadoop URIs: `file://` locally,
  * `s3://` on EMR. `--output` is a NEW root: a rerun never writes into an existing run.
  */
final case class PipelineArgs(
    dblp: Path,
    dtd: Path,
    crossref: Option[Path],
    openalex: Option[Path],
    orcid: Option[Path],
    output: Path,
    snapshot: String,
    attempt: Int,
    codeVersion: String,
)

object PipelineArgs:
  val Usage: String =
    "usage: --dblp <xml> --dtd <dblp.dtd> --output <new root> --snapshot <name> " +
      "[--crossref <dir>] [--openalex <dir>] [--orcid <dir>] [--attempt <n>] " +
      "--code-version <token>  (required, e.g. \"$(git rev-parse --short HEAD)+uncommitted\")"

  def parse(args: Array[String]): Either[String, PipelineArgs] =
    if args.length % 2 != 0 then Left(s"odd number of arguments. $Usage")
    else
      val m     = args.grouped(2).map(a => a(0) -> a(1)).toMap
      val known = Set(
        "--dblp",
        "--dtd",
        "--output",
        "--snapshot",
        "--crossref",
        "--openalex",
        "--orcid",
        "--attempt",
        "--code-version",
      )
      val unknown                                 = m.keySet -- known
      def need(k: String): Either[String, String] = m.get(k).toRight(s"missing $k. $Usage")
      for
        _ <-
          if unknown.isEmpty then Right(())
          else Left(s"unknown arguments: ${unknown.toList.sorted.mkString(",")}")
        dblp <- need("--dblp")
        dtd  <- need("--dtd")
        out  <- need("--output")
        snap <- need("--snapshot")
        _    <-
          if snap.matches("[A-Za-z0-9._-]+") then Right(())
          else Left("--snapshot must match [A-Za-z0-9._-]+")
        attempt <- m
          .getOrElse("--attempt", "1")
          .toIntOption
          .filter(_ >= 1)
          .toRight("--attempt must be an integer >= 1")
        code <- need("--code-version")
        _    <-
          if code.nonEmpty && !code.exists(_.isWhitespace) then Right(())
          else Left("--code-version must be a token without whitespace")
      yield PipelineArgs(
        new Path(dblp),
        new Path(dtd),
        m.get("--crossref").map(new Path(_)),
        m.get("--openalex").map(new Path(_)),
        m.get("--orcid").map(new Path(_)),
        new Path(out),
        snap,
        attempt,
        code,
      )

object RunSetup:
  val Stage: String = "hadoop-pipeline"

  /** The very filter HJ2 applies to its input directories (F10: the checksum covers exactly the
    * files the job processes).
    */
  val JsonFilter: org.apache.hadoop.fs.PathFilter = new JsonFileFilter

  private def sha256(conf: Configuration, p: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = p.getFileSystem(conf).open(p)
    try
      val buf = new Array[Byte](64 * 1024)
      var n   = in.read(buf)
      while n >= 0 do
        md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  private def tree(conf: Configuration, dir: Path): (String, Int) =
    val fs    = dir.getFileSystem(conf)
    val files = fs
      .listStatus(dir)
      .toList
      .filter(f => f.isFile && RunSetup.JsonFilter.accept(f.getPath))
      .map(_.getPath)
      .sortBy(_.getName)
    val text = files.map(f => s"${f.getName}:${sha256(conf, f)}").mkString("\n")
    (Hashing.textHash(text), files.size)

  /** The encoding named in the XML declaration (UTF-8 when there is none). */
  def sniffCharset(conf: Configuration, p: Path): Either[String, Charset] =
    val codec = new org.apache.hadoop.io.compress.CompressionCodecFactory(conf).getCodec(p)
    val raw   = p.getFileSystem(conf).open(p)
    val head  =
      try
        // a compressed file is sniffed on its decompressed bytes
        val in = if codec == null then raw else codec.createInputStream(raw)
        val b  = new Array[Byte](512)
        val n  = in.read(b)
        new String(b, 0, math.max(n, 0), StandardCharsets.ISO_8859_1)
      finally raw.close()
    val enc = """<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""".r
      .findFirstMatchIn(head)
      .map(_.group(1))
    enc match
      case None                              => Right(StandardCharsets.UTF_8)
      case Some(e) if Charset.isSupported(e) => Right(Charset.forName(e))
      case Some(e)                           => Left(s"unsupported XML encoding '$e'")

  /** Resolves the settings (environment, then `-D` overrides, then the versioned file), hashes the
    * inputs, and derives the run ID with the Phase 03 formula (content-derived, no clock).
    */
  def prepare(conf: Configuration, a: PipelineArgs): Either[String, Setup] =
    val overrides = conf
      .iterator()
      .asScala
      .map(e => e.getKey -> e.getValue)
      .filter(_._1.startsWith("scholarchain.hadoop."))
      .toMap
    for
      resolved <- HadoopConfigLoader
        .load(sys.env, overrides)
        .left
        .map(es => es.map(_.message).mkString("; "))
      cs        <- sniffCharset(conf, a.dblp)
      maxRecord <- overrides
        .get(DblpRecordReader.MaxRecordBytesKey)
        .map(v =>
          v.toIntOption
            .filter(_ >= 1)
            .toRight(s"${DblpRecordReader.MaxRecordBytesKey} must be an integer >= 1")
        )
        .getOrElse(Right(DblpRecordReader.DefaultMaxRecordBytes))
      exportReducers <- overrides
        .get(ExportKeys.ReducersKey)
        .map(v =>
          v.trim.toIntOption
            .filter(_ >= 1)
            .toRight(s"${ExportKeys.ReducersKey} must be an integer >= 1 (got '$v')")
        )
        .getOrElse(Right(1))
      er <- ErSettings.resolve(
        conf.iterator().asScala.map(e => e.getKey -> e.getValue).toMap
      )
      h      = resolved.config
      config = Map(
        "hadoop.schema-version"          -> h.schemaVersion.toString,
        "hadoop.large-team-threshold"    -> h.largeTeamThreshold.toString,
        "hadoop.pair-expansion-hard-cap" -> h.pairExpansionHardCap.toString,
        "hadoop.reducers.normalize"      -> h.reducers.normalize.toString,
        "hadoop.reducers.teams"          -> h.reducers.teams.toString,
        "hadoop.reducers.pairs"          -> h.reducers.pairs.toString,
        "hadoop.reducers.identifiers"    -> h.reducers.identifiers.toString,
        "hadoop.output-codec"            -> h.outputCodec.wire,
        "hadoop.export-reducers"         -> exportReducers.toString,
        "hadoop.dblp.max-record-bytes"   -> maxRecord.toString,
      )
    yield
      val dblpIn = InputRef(s"dblp/${a.dblp.getName}", a.snapshot, Some(sha256(conf, a.dblp)))
      val dtdIn  = InputRef("dblp/dblp.dtd", "dtd", Some(sha256(conf, a.dtd)))
      def src(name: String, p: Option[Path]): Option[(String, List[InputRef])] = p.map { d =>
        val (cs, n) = tree(conf, d)
        name -> List(InputRef(s"$name/works", s"${a.snapshot};files=$n", Some(cs)))
      }
      val inputs: Map[String, List[InputRef]] =
        Map("dblp" -> List(dblpIn), "dtd" -> List(dtdIn)) ++
          List(
            src("crossref", a.crossref),
            src("openalex", a.openalex),
            src("orcid", a.orcid),
          ).flatten
      val all   = inputs.toList.sortBy(_._1).flatMap(_._2)
      val runId = Ids
        .runId(Stage, a.codeVersion, a.attempt, config ++ er, all)
        .fold(e => sys.error(e.message), identity)
      val run =
        RunContext(runId, a.codeVersion, a.snapshot, a.attempt, config, inputs, cs.name(), er)
      Setup(run, Layout(a.output, run), h, a)

/** Runs HJ1 to HJ5 in order, checks the pair invariant, then writes the manifest, `counters.json`
  * and the `_SUCCESS` marker of the run. Exit codes: 0 success, 1 failure, 2 refused (the run
  * directory already holds `_SUCCESS` and a manifest) or bad arguments.
  */
class PipelineDriver extends Configured with Tool:
  override def run(args: Array[String]): Int =
    val conf = getConf
    PipelineArgs.parse(args) match
      case Left(msg) => System.err.println(msg); 2
      case Right(a)  =>
        RunSetup.prepare(conf, a) match
          case Left(msg) => System.err.println(s"configuration error: $msg"); 2
          case Right(s)  => execute(conf, s)

  private def execute(conf0: Configuration, s: Setup): Int =
    val conf = new Configuration(conf0)
    val fs   = s.layout.root.getFileSystem(conf)
    val dir  = s.layout.runDir
    if fs.exists(new Path(dir, "_SUCCESS")) && fs.exists(new Path(dir, "manifest.json")) then
      System.err.println(
        s"refusing to write: run directory $dir already holds _SUCCESS and manifest.json. " +
          "There is no overwrite; use a new --output root."
      )
      return 2
    PipelineDriver.localScratch(conf, s)
    val outcomes                             = scala.collection.mutable.ListBuffer.empty[JobOutcome]
    def step(o: Option[JobOutcome]): Boolean =
      o.foreach { j =>
        outcomes += j
        println(s"job ${j.jobId}: success=${j.success}")
      }
      o.forall(_.success)
    val existing: List[Path] => List[Path] = ps => ps.filter(p => fs.exists(p))
    val ok                                 =
      step(Some(DblpExtractJob.submit(conf, s))) &&
        step(SourceNormalizeJob.submit(conf, s)) &&
        step(
          Some(
            ExactIdentifierJob.submit(
              conf,
              s,
              existing(List(s.layout.table("hj1", "id-refs"), s.layout.table("hj2", "id-refs"))),
            )
          )
        ) &&
        step(Some(TeamPairJob.submitTeams(conf, s))) &&
        step(TeamPairJob.submitPairs(conf, s)) && {
          val inputs = existing(ExportKeys.Sources.map((j, t) => s.layout.table(j, t)))
          step(Some(GraphCandidateExportJob.submit(conf, s, inputs)))
        } &&
        EntityResolutionJobs.runAll(conf, s, step)
    if !ok then
      System.err.println("pipeline failed; outputs under the root are left in place for inspection")
      return 1
    PipelineDriver.invariantError(outcomes.toList) match
      case Some(msg) => System.err.println(s"INVARIANT VIOLATED: $msg"); 1
      case None      => PipelineDriver.finish(conf, fs, s, outcomes.toList)

object PipelineDriver:
  /** The pair invariant: EMITTED_PAIRS equals the sum of n(n-1)/2 over non-oversize teams
    * (EXPECTED_PAIRS, computed independently in HJ4a), the dedupe stage accounts for every pair,
    * and the export wrote exactly those rows.
    */
  def invariantError(os: List[JobOutcome]): Option[String] =
    def of(id: String) = os.find(_.jobId == id)
    val t              = of(TeamKeys.TeamJobId)
    val p              = of(TeamKeys.PairJobId)
    val e              = of(ExportKeys.JobId)
    val emitted        = t.map(_.c(PipelineCounter.EMITTED_PAIRS)).getOrElse(0L)
    val expected       = t.map(_.c(PipelineCounter.EXPECTED_PAIRS)).getOrElse(0L)
    val accepted       = p.map(_.c(PipelineCounter.ACCEPTED)).getOrElse(0L)
    val dup            = p.map(_.c(PipelineCounter.DUPLICATE_PAIR)).getOrElse(0L)
    val exported       = e.flatMap(_.rows.get("pair-candidates")).getOrElse(0L)
    if emitted != expected then
      Some(s"EMITTED_PAIRS=$emitted but expected sum n(n-1)/2 = $expected")
    else if accepted + dup != emitted then
      Some(s"pair stage: ACCEPTED($accepted)+DUPLICATE_PAIR($dup) != EMITTED_PAIRS($emitted)")
    else if exported != accepted then
      Some(s"export wrote $exported pair rows, pair stage accepted $accepted")
    else EntityResolutionJobs.invariantError(os)

  /** On a local file system, keep Hadoop's scratch and staging inside the output root. */
  def localScratch(conf: Configuration, s: Setup): Unit =
    if s.layout.root.toUri.getScheme == null || s.layout.root.toUri.getScheme == "file" then
      conf.set("hadoop.tmp.dir", new Path(s.layout.root, "_tmp").toUri.getPath)

  private def countersJson(s: Setup, os: List[JobOutcome]): String =
    def obj(m: Map[String, Long]) =
      ujson.Obj.from(m.toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble)))
    def job(o: JobOutcome): ujson.Obj =
      val base: List[(String, ujson.Value)] = List(
        "success"  -> ujson.Bool(o.success),
        "counters" -> obj(o.counters),
        "rows"     -> obj(o.rows),
        "rejects"  -> obj(o.rejects),
      )
      val dec: List[(String, ujson.Value)] =
        if o.decisions.isEmpty then Nil else List("decisions" -> obj(o.decisions))
      val fw: List[(String, ujson.Value)] =
        if o.framework.isEmpty then Nil else List("framework" -> obj(o.framework))
      ujson.Obj.from(base ++ dec ++ fw)
    val jobs = ujson.Obj.from(os.map(o => o.jobId -> job(o)))
    ujson.write(
      ujson.Obj(
        "runId"       -> s.run.runId,
        "snapshot"    -> s.run.snapshot,
        "codeVersion" -> s.run.codeVersion,
        "jobs"        -> jobs,
      ),
      indent = 2,
    )

  private def summary(s: Setup, o: JobOutcome): JobSummary =
    val rejectCounters = o.rejects.map((k, v) => s"REJECT_$k" -> v)
    val rowCounters    = o.rows.map((k, v) => s"ROWS_$k" -> v)
    val decCounters    = o.decisions.map((k, v) => s"DECISIONS_${k.replace('.', '_')}" -> v)
    val base           =
      if o.jobId == ExportKeys.JobId then s"curated" else s"work/${o.jobId.takeWhile(_ != '-')}"
    val outputs = o.rows.toList.sortBy(_._1).map { (t, n) =>
      val path =
        if o.jobId == ExportKeys.JobId then
          s"curated/$t/snapshot=${s.layout.snapshot}/run_id=${s.layout.runIdHex}"
        else s"$base/$t"
      OutputRef(path, n)
    }
    JobSummary(o.jobId, o.counters ++ rejectCounters ++ rowCounters ++ decCounters, outputs)

  private def write(fs: FileSystem, p: Path, text: String): Unit =
    val out = fs.create(p, false) // never overwrite
    try out.write(text.getBytes(StandardCharsets.UTF_8))
    finally out.close()

  def finish(conf: Configuration, fs: FileSystem, s: Setup, os: List[JobOutcome]): Int =
    val inputs = s.run.inputs.toList.sortBy(_._1).flatMap(_._2)
    PipelineRunManifest.build(
      RunSetup.Stage,
      s.run.attempt,
      s.run.snapshot,
      s.run.codeVersion,
      inputs,
      s.run.allConfig,
      os.map(summary(s, _)),
    ) match
      case Left(e)  => System.err.println(s"manifest error: ${e.message}"); 1
      case Right(m) =>
        require(m.runId == s.run.runId, "manifest run ID differs from the run context")
        Wire.write(m) match
          case Left(e)     => System.err.println(s"manifest error: ${e.message}"); 1
          case Right(json) =>
            val dir = s.layout.runDir
            fs.mkdirs(dir)
            write(fs, new Path(dir, "counters.json"), countersJson(s, os) + "\n")
            write(fs, new Path(dir, "manifest.json"), json + "\n")
            write(fs, new Path(dir, "_SUCCESS"), "")
            println(s"run ${s.run.runId} complete: $dir")
            0

  def main(args: Array[String]): Unit =
    System.exit(ToolRunner.run(new Configuration(), new PipelineDriver, args))

/** Shared body of the per-job tools: parse, prepare, run one job, print its counters. */
abstract class JobTool extends Configured with Tool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome]

  override def run(args: Array[String]): Int =
    PipelineArgs.parse(args).flatMap(RunSetup.prepare(getConf, _)) match
      case Left(msg) => System.err.println(msg); 2
      case Right(s)  =>
        val conf = new Configuration(getConf)
        PipelineDriver.localScratch(conf, s)
        runJob(conf, s) match
          case None    => println("nothing to do"); 0
          case Some(o) =>
            println(s"job ${o.jobId}: success=${o.success} counters=${o.counters} rows=${o.rows}")
            if o.success then 0 else 1

class DblpExtractTool extends JobTool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome] = Some(
    DblpExtractJob.submit(conf, s)
  )
class SourceNormalizeTool extends JobTool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome] =
    SourceNormalizeJob.submit(conf, s)
class ExactIdentifierTool extends JobTool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome] =
    val fs = s.layout.root.getFileSystem(conf)
    Some(
      ExactIdentifierJob.submit(
        conf,
        s,
        List(s.layout.table("hj1", "id-refs"), s.layout.table("hj2", "id-refs")).filter(fs.exists),
      )
    )
class PublicationTeamPairTool extends JobTool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome] =
    val t = TeamPairJob.submitTeams(conf, s)
    if t.success then TeamPairJob.submitPairs(conf, s).orElse(Some(t)) else Some(t)
class BlockingCandidateTool extends JobTool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome] =
    EntityResolutionJobs.blocking(conf, s).lastOption
class MatchDecisionTool extends JobTool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome] =
    MatchDecisionJob.submit(conf, s)
class GraphCandidateExportTool extends JobTool:
  protected def runJob(conf: Configuration, s: Setup): Option[JobOutcome] =
    val fs = s.layout.root.getFileSystem(conf)
    Some(
      GraphCandidateExportJob.submit(
        conf,
        s,
        ExportKeys.Sources.map((j, t) => s.layout.table(j, t)).filter(fs.exists),
      )
    )
