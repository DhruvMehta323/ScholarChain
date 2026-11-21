package scholarchain.hadoop

import java.nio.charset.StandardCharsets

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import scholarchain.core.*
import scholarchain.er.*

/** Entity-resolution settings of a run (`er.*`), resolved once by the driver. */
object ErSettings:
  /** Environment `SCHOLARCHAIN_ER_*`, then `-D scholarchain.er.<key>`, then the versioned file
    * (`scholarchain-er.v1.conf`). An invalid value refuses the run.
    */
  def resolve(overrides: Map[String, String]): Either[String, Map[String, String]] =
    ErConfigLoader
      .load(sys.env, overrides.filter(_._1.startsWith(ModuleConfig.OverridePrefix + "er.")))
      .left
      .map(es => es.map(_.message).mkString("; "))
      .map(RunContext.erParams)

/** Reads small side files (a table directory of `part-*` files, plain or compressed) through the
  * Hadoop FileSystem, so a local path, an `s3://` path and a distributed-cache path work alike.
  */
object SideFiles:
  def lines(conf: Configuration, dir: Path): List[String] =
    val fs = dir.getFileSystem(conf)
    if !fs.exists(dir) then Nil
    else
      val codecs = new CompressionCodecFactory(conf)
      val it     = fs.listFiles(dir, true)
      val files  = Iterator
        .continually(it)
        .takeWhile(_.hasNext)
        .map(_.next().getPath)
        .filter(p => !p.getName.startsWith("_") && !p.getName.startsWith("."))
        .toList
        .sortBy(_.toString)
      files.flatMap { p =>
        val raw   = fs.open(p)
        val codec = codecs.getCodec(p)
        val in    = if codec == null then raw else codec.createInputStream(raw)
        try
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
            .split("\n")
            .toList
            .filter(_.nonEmpty)
        finally in.close()
      }

/** The HJ3 CONFLICT evidence as a lookup: two records conflict when both are linked by the same
  * CONFLICT evidence row. Rows are indexed after sorting, so the index is deterministic.
  */
final class ConflictIndex(groups: Map[String, Set[Int]]):
  def conflicts(a: String, b: String): Boolean =
    (for x <- groups.get(a); y <- groups.get(b) yield x.exists(y.contains)).getOrElse(false)
  def size: Int = groups.size

object ConflictIndex:
  val Empty: ConflictIndex = new ConflictIndex(Map.empty)

  def load(conf: Configuration, evidenceDir: Path): ConflictIndex =
    val conflicts = SideFiles
      .lines(conf, evidenceDir)
      .flatMap(l => Lines.split(l).map(_._2))
      .flatMap(j => Wire.read[ExactIdentifierEvidence](j).toOption)
      .filter(_.outcome == ExactIdentifierOutcome.Conflict)
      .map(_.linkedRecordIds.sorted)
      .distinct
      .sorted(using Ordering.by[List[String], String](_.mkString("\u0001")))
    val groups = conflicts.zipWithIndex
      .flatMap((ids, i) => ids.map(_ -> i))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
    new ConflictIndex(groups)

object ErKeys:
  val ViewsJobId: String      = "hj6a-entity-views"
  val CountJobId: String      = "hj6b-block-count"
  val CandidateJobId: String  = "hj6c-candidates"
  val DedupeJobId: String     = "hj6d-candidate-dedupe"
  val DecisionJobId: String   = "hj7-match-decision"
  val OversizeKeysDir: String = "scholarchain.hj6.oversize-keys-dir"
  val EvidenceDir: String     = "scholarchain.hj6.evidence-dir"

  /** Exact-identifier block keys: `x1|<type>|<normalized value>`. */
  def exactIdKeys(v: EntityView): List[String] =
    val ids = v.kind match
      case EntityKind.Work =>
        v.doi.flatMap(ErIds.doi).map("doi" -> _).toList ++
          v.arxiv.flatMap(ErIds.arxiv).map("arxiv" -> _).toList ++
          v.openAlexId.flatMap(ErIds.openAlex).map("openalex" -> _).toList
      case EntityKind.Researcher =>
        v.orcid.flatMap(ErIds.orcid).map("orcid" -> _).toList
    ids.map((t, x) => s"x1|$t|$x").filter(k => !k.exists(_.isWhitespace)).distinct.sorted

  /** A reject-v1 row for an ER job (source `scholarchain`). */
  def reject(run: RunContext, jobId: String, id: String, message: String): String =
    val f = ParseFailure(RejectReason.SchemaViolation, message, Some(id))
    Rejects.json(Rejects.make(run, jobId, SourceType.ScholarChain, Some(id), None, f, ""))
