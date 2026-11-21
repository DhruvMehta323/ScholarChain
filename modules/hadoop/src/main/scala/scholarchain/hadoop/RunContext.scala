package scholarchain.hadoop

import java.nio.charset.StandardCharsets

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}

/** Everything a task needs to stamp provenance. The driver builds it once and ships it as JSON in
  * the Hadoop Configuration, so every task sees exactly the run's inputs, code version and
  * parameters. `inputs` is keyed by logical source: dblp, dtd, crossref, openalex, orcid.
  */
final case class RunContext(
    runId: String,
    codeVersion: String,
    snapshot: String,
    attempt: Int,
    config: Map[String, String],
    inputs: Map[String, List[InputRef]],
    dblpCharset: String,
    er: Map[String, String] = Map.empty,
) derives ReadWriter:
  def largeTeamThreshold: Int   = config("hadoop.large-team-threshold").toInt
  def pairExpansionHardCap: Int = config("hadoop.pair-expansion-hard-cap").toInt
  def maxRecordBytes: Int       = config("hadoop.dblp.max-record-bytes").toInt

  /** The configuration recorded in the manifest and hashed into the run ID: the Hadoop settings
    * plus the resolved entity-resolution settings (`er.*`, used only by HJ6 and HJ7).
    */
  def allConfig: Map[String, String] = config ++ er

  /** The entity-resolution configuration of this run: the versioned defaults with the values the
    * driver resolved (`er`) applied as overrides. The task environment is never consulted.
    */
  def erConfig: scholarchain.er.ErConfig =
    scholarchain.er.ErConfigLoader
      .load(Map.empty, er.map((k, v) => ModuleConfig.OverridePrefix + k -> v))
      .fold(es => throw new IllegalStateException(es.map(_.message).mkString("; ")), identity)

  /** Provenance: the named inputs (plus `extraInputs`, for example the single envelope file), the
    * code version, the run parameters plus the job id, and the run ID.
    */
  def prov(
      source: SourceType,
      inputKeys: Seq[String],
      jobId: String,
      extraInputs: List[InputRef] = Nil,
      extraParams: Map[String, String] = Map.empty,
      retrievedAt: Option[String] = None,
      withEr: Boolean = false,
  ): Provenance =
    Provenance(
      SchemaVersion.Current,
      source,
      inputKeys.toList.flatMap(k => inputs.getOrElse(k, Nil)) ++ extraInputs,
      codeVersion,
      (if withEr then allConfig else config) ++ extraParams + ("job" -> jobId),
      Some(runId),
      retrievedAt,
    )

  /** Provenance over every input of the run (for cross-source derived records). */
  def provAll(jobId: String, withEr: Boolean = false): Provenance =
    prov(SourceType.ScholarChain, inputs.keys.toList.sorted, jobId, withEr = withEr)

object RunContext:
  /** The `er.*` parameters of a resolved ER configuration that decide HJ6 and HJ7 output (the LLM
    * settings are not used in MapReduce).
    */
  def erParams(c: scholarchain.er.ErConfig): Map[String, String] =
    def t(kind: String, x: scholarchain.er.Thresholds): Map[String, String] = Map(
      s"er.$kind.threshold.accept"        -> x.accept.toString,
      s"er.$kind.threshold.reject"        -> x.reject.toString,
      s"er.$kind.threshold.llm-band-low"  -> x.llmBandLow.toString,
      s"er.$kind.threshold.llm-band-high" -> x.llmBandHigh.toString,
    )
    Map(
      "er.policy-version"      -> c.policyVersion,
      "er.feature-set-version" -> c.featureSetVersion,
      "er.decision-epoch"      -> c.decisionEpoch,
      "er.max-block-size"      -> c.maxBlockSize.toString,
    ) ++ t("researcher", c.researcher) ++ t("work", c.work)

  val ConfKey: String = "scholarchain.run.context"

  def toConf(conf: Configuration, c: RunContext): Unit = conf.set(ConfKey, WirePickle.write(c))
  def fromConf(conf: Configuration): RunContext        =
    WirePickle.read[RunContext](
      Option(conf.get(ConfKey)).getOrElse(sys.error(s"$ConfKey is not set"))
    )

/** Output directory layout of one run (never overwritten; a rerun uses a new root). */
final case class Layout(root: Path, snapshot: String, runIdHex: String):
  def work(job: String): Path                 = new Path(root, s"work/$job")
  def table(job: String, table: String): Path = new Path(work(job), table)
  def curated: Path                           = new Path(root, "curated")
  def runDir: Path = new Path(root, s"run/snapshot=$snapshot/run_id=$runIdHex")

object Layout:
  def apply(root: Path, ctx: RunContext): Layout =
    Layout(root, ctx.snapshot, ctx.runId.stripPrefix("run:"))

/** Reject-v1 construction. */
object Rejects:
  def make(
      run: RunContext,
      jobId: String,
      source: SourceType,
      sourceRecordId: Option[String],
      offset: Option[Long],
      failure: ParseFailure,
      raw: String,
  ): RejectRecord =
    RejectRecord(
      SchemaVersion.Current,
      RejectRecord.SchemaName,
      run.runId,
      jobId,
      source,
      sourceRecordId.orElse(failure.sourceRecordId),
      offset,
      failure.reason,
      failure.message,
      RejectRecord.boundExcerpt(raw),
    )

  /** The validated reject-v1 row. A reject that fails validation is a programming error (every
    * field is built from validated parts), so it fails the task loudly instead of writing an
    * unvalidated row.
    */
  def json(r: RejectRecord): String =
    Wire
      .write(r)
      .fold(e => throw new IllegalStateException(s"invalid reject-v1 row: ${e.message}"), identity)

  def lossyText(bytes: Array[Byte], cs: java.nio.charset.Charset): String =
    new String(bytes, 0, math.min(bytes.length, 4096), cs)

  def utf8(bytes: Array[Byte]): String = lossyText(bytes, StandardCharsets.UTF_8)
