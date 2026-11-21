package scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import org.apache.hadoop.conf.Configuration
import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}
import scholarchain.er.*

import ItSupport.*

/** Helpers for the entity-resolution (HJ6, HJ7) integration suites. The data is SYNTHETIC: records
  * are written straight into the curated layout the jobs read, so a suite launches only the ER jobs
  * (HJ6a to HJ6d and HJ7), not the whole pipeline.
  */
object ErItSupport:
  /** One synthetic work with its authors. `doi` is also the Crossref work ID suffix. */
  final case class Rec(
      workId: String,
      title: String,
      year: Int,
      source: SourceType,
      authors: List[String],
      doi: Option[String] = None,
  )

  def dblp(
      key: String,
      title: String,
      year: Int,
      authors: List[String],
      doi: Option[String] = None,
  ) =
    Rec(s"work:dblp:conf/it/$key", title, year, SourceType.Dblp, authors, doi)

  def crossref(
      key: String,
      title: String,
      year: Int,
      authors: List[String],
      doi: Option[String] = None,
  ) =
    Rec(s"work:crossref:10.9999/it.$key", title, year, SourceType.Crossref, authors, doi)

  /** All reducers 1 (one LocalJobRunner run per suite); `overrides` are Hadoop or ER settings. */
  def conf(overrides: (String, String)*): Configuration =
    val all = List(
      "scholarchain.hadoop.reducers.normalize"   -> "1",
      "scholarchain.hadoop.reducers.teams"       -> "1",
      "scholarchain.hadoop.reducers.pairs"       -> "1",
      "scholarchain.hadoop.reducers.identifiers" -> "1",
    ) ++ overrides
    ItSupport.conf(all*)

  /** A Setup over a NEW root, with the fixture inputs for its provenance. */
  def setup(root: JPath, c: Configuration): Setup =
    val args = PipelineArgs.parse(baseArgs(root).toArray).toOption.get
    RunSetup.prepare(c, args).fold(m => throw new AssertionError(m), identity)

  def build(s: Setup, recs: List[Rec]): (List[WorkRecord], List[AuthorMention]) =
    def prov(src: SourceType) = src match
      case SourceType.Dblp => s.run.prov(SourceType.Dblp, Seq("dblp", "dtd"), "it")
      case other           => s.run.prov(other, Seq(other.wire), "it")
    val works = recs.map(r =>
      WorkRecord(SchemaVersion.Current, r.workId, r.title, r.year, r.doi, None, Nil, prov(r.source))
    )
    val ments = recs.flatMap(r =>
      r.authors.zipWithIndex.map((a, i) =>
        AuthorMention(
          SchemaVersion.Current,
          Ids.mentionId(r.workId, i).toOption.get,
          r.workId,
          i,
          a,
          None,
          prov(r.source),
        )
      )
    )
    (works, ments)

  private def write(p: JPath, lines: Seq[String]): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))

  private def local(p: org.apache.hadoop.fs.Path): JPath = java.nio.file.Paths.get(p.toUri.getPath)

  /** Writes the curated `works` and `author-mentions` tables (what HJ5 would have written). */
  def writeCurated(s: Setup, works: List[WorkRecord], ments: List[AuthorMention]): Unit =
    def dir(t: String) =
      local(s.layout.curated)
        .resolve(s"$t/snapshot=${s.layout.snapshot}/run_id=${s.layout.runIdHex}")
    write(dir("works").resolve("part-r-00000"), works.map(w => Wire.write(w).toOption.get))
    write(
      dir("author-mentions").resolve("part-r-00000"),
      ments.map(m => Wire.write(m).toOption.get),
    )

  /** Writes HJ3 CONFLICT evidence linking `ids` under a DOI. */
  def writeConflict(s: Setup, doi: String, ids: List[String]): Unit =
    val ev = ExactIdentifierEvidence(
      SchemaVersion.Current,
      IdentifierType.Doi,
      doi,
      ids.sorted,
      ExactIdentifierOutcome.Conflict,
      s.run.prov(SourceType.ScholarChain, Seq("dblp", "dtd"), IdentifierKeys.JobId),
    )
    write(
      local(s.layout.table("hj3", "exact-identifier-evidence")).resolve("part-r-00000"),
      List(s"doi|$doi\t${Wire.write(ev).toOption.get}"),
    )

  /** HJ6a to HJ6d, then HJ7. */
  def runEr(s: Setup, c: Configuration): List[JobOutcome] =
    EntityResolutionJobs.blocking(c, s) ++ MatchDecisionJob.submit(c, s).toList

  def jsonLines(dir: org.apache.hadoop.fs.Path): List[String] =
    partFiles(local(dir)).flatMap(lines).sorted

  def candidates(s: Setup): List[MatchCandidate] =
    jsonLines(s.layout.table("hj6d", "match-candidates")).map(l =>
      Wire.read[MatchCandidate](l).fold(e => throw new AssertionError(e.message), identity)
    )

  def decisions(s: Setup): List[(String, MatchDecisionV2)] =
    val base = local(s.layout.table("hj7", "match-decisions"))
    partFiles(base).flatMap(f =>
      lines(f).map(l =>
        base.relativize(f.toPath).getParent.toString ->
          Wire.read[MatchDecisionV2](l).fold(e => throw new AssertionError(e.message), identity)
      )
    )

  def views(s: Setup): List[EntityView] =
    jsonLines(s.layout.table("hj6a", "entity-views")).map(l => WirePickle.read[EntityView](l))

  /** Rows of a `sortKey TAB json` work table as (sortKey, json). */
  def keyed(s: Setup, job: String, t: String): List[(String, ujson.Value)] =
    jsonLines(s.layout.table(job, t)).map { l =>
      val i = l.indexOf('\t')
      l.substring(0, i) -> ujson.read(l.substring(i + 1))
    }

  def tableLines(s: Setup, job: String, t: String): List[String] =
    jsonLines(s.layout.table(job, t))

  def sortedAll(s: Setup): Map[String, List[String]] =
    List(
      "hj6a" -> "entity-views",
      "hj6b" -> "block-sizes",
      "hj6b" -> "oversize-keys",
      "hj6c" -> "oversize-blocks",
      "hj6c" -> "candidate-stage",
      "hj6d" -> "match-candidates",
    ).map((j, t) => s"$j/$t" -> tableLines(s, j, t)).toMap ++
      decisions(s).groupMap(_._1)(d => Wire.write(d._2).toOption.get).map((k, v) => k -> v.sorted)
end ErItSupport
