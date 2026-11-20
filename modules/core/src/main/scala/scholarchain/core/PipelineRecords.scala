package scholarchain.core

import java.time.Instant

import scala.util.Try

import WirePickle.{*, given}
import ValidationError.*

/** Phase 04 pipeline records. They are keyed by author MENTION (`mention:<hex>`, see
  * `Ids.mentionId`), because they exist before entity resolution. The Phase 03 records
  * `PublicationTeam`, `AuthorshipRecord` and `CoauthorPairCandidate` are keyed by resolved
  * researcher ID, are frozen, and are not changed here. Every record below is a new type at
  * `schemaVersion` 1.
  */

/** One author mention of a work, as produced by the parser. */
final case class MentionAuthorship(
    schemaVersion: Int,
    mentionId: String,
    workId: String,
    position: Int,
    year: Int,
    provenance: Provenance,
) derives ReadWriter

object MentionAuthorship:
  given Contract[MentionAuthorship] = V.contract(a =>
    V.all(
      V.schema(a.schemaVersion),
      V.unit(Ids.checkWorkId(a.workId)),
      V.atLeast("position", a.position, 0),
      V.matches("mentionId", Ids.mentionId(a.workId, a.position), a.mentionId),
      V.year(a.year),
      V.prov(a.provenance),
    )
  )

/** The ordered author mentions of one work. `mentionIds(i)` is the mention at position i, so it
  * must equal `Ids.mentionId(workId, i)`. `teamSize` must equal `mentionIds.size` (at least 1).
  */
final case class MentionTeam(
    schemaVersion: Int,
    workId: String,
    year: Int,
    teamSize: Int,
    mentionIds: List[String],
    provenance: Provenance,
) derives ReadWriter

object MentionTeam:
  given Contract[MentionTeam] = V.contract(t =>
    V.all(
      V.schema(t.schemaVersion),
      V.unit(Ids.checkWorkId(t.workId)),
      V.year(t.year),
      V.atLeast("mentionIds.size", t.mentionIds.size, 1),
      if t.teamSize == t.mentionIds.size then V.ok
      else Left(InvalidField("teamSize", "must equal the number of mentionIds")),
      V.each(t.mentionIds.zipWithIndex)((m, i) =>
        V.matches("mentionIds", Ids.mentionId(t.workId, i), m)
      ),
      V.prov(t.provenance),
    )
  )

/** An unordered pair of mentions of one work, in canonical order (`pair.first` < `pair.second` by
  * `String.compareTo`, the `OrderedPair` rule). `firstPosition` and `secondPosition` are the author
  * positions of `pair.first` and `pair.second` (not necessarily ascending).
  */
final case class MentionPairCandidate(
    schemaVersion: Int,
    workId: String,
    year: Int,
    pair: OrderedPair,
    firstPosition: Int,
    secondPosition: Int,
    provenance: Provenance,
) derives ReadWriter

object MentionPairCandidate:
  /** Canonicalizes two positions of one work into a valid candidate, whatever the argument order.
    */
  def build(
      workId: String,
      year: Int,
      positionA: Int,
      positionB: Int,
      provenance: Provenance,
  ): Either[ValidationError, MentionPairCandidate] =
    for
      a <- Ids.mentionId(workId, positionA)
      b <- Ids.mentionId(workId, positionB)
      p <- OrderedPair.canonicalMentions(a, b)
      c <- summon[Contract[MentionPairCandidate]].validate(
        if p.first == a then
          MentionPairCandidate(
            SchemaVersion.Current,
            workId,
            year,
            p,
            positionA,
            positionB,
            provenance,
          )
        else
          MentionPairCandidate(
            SchemaVersion.Current,
            workId,
            year,
            p,
            positionB,
            positionA,
            provenance,
          )
      )
    yield c

  given Contract[MentionPairCandidate] = V.contract(c =>
    V.all(
      V.schema(c.schemaVersion),
      V.unit(Ids.checkWorkId(c.workId)),
      V.year(c.year),
      V.unit(OrderedPair.validateMentions(c.pair)),
      V.atLeast("firstPosition", c.firstPosition, 0),
      V.atLeast("secondPosition", c.secondPosition, 0),
      V.matches("pair.first", Ids.mentionId(c.workId, c.firstPosition), c.pair.first),
      V.matches("pair.second", Ids.mentionId(c.workId, c.secondPosition), c.pair.second),
      V.prov(c.provenance),
    )
  )

/** Evidence from an exact identifier comparison. It is NOT a match decision: it carries no
  * confidence, score or threshold. `linkedRecordIds` are at least two distinct record references
  * (`<kind>:<id>`), strictly ascending.
  */
final case class ExactIdentifierEvidence(
    schemaVersion: Int,
    identifierType: IdentifierType,
    normalizedIdentifier: String,
    linkedRecordIds: List[String],
    outcome: ExactIdentifierOutcome,
    provenance: Provenance,
) derives ReadWriter

object ExactIdentifierEvidence:
  private def canonical(t: IdentifierType, v: String): Either[ValidationError, String] =
    t match
      case IdentifierType.Doi      => Doi.normalize(v)
      case IdentifierType.Arxiv    => ArxivId.normalize(v)
      case IdentifierType.Orcid    => Orcid.parse(v)
      case IdentifierType.OpenAlex => OpenAlexId.normalize(v)

  given Contract[ExactIdentifierEvidence] = V.contract(e =>
    V.all(
      V.schema(e.schemaVersion),
      V.matches(
        "normalizedIdentifier",
        canonical(e.identifierType, e.normalizedIdentifier),
        e.normalizedIdentifier,
      ),
      V.atLeast("linkedRecordIds.size", e.linkedRecordIds.size, 2),
      V.each(e.linkedRecordIds)(r => V.unit(Ids.checkAnyRef(r))),
      if e.linkedRecordIds.zip(e.linkedRecordIds.drop(1)).forall((a, b) => a.compareTo(b) < 0)
      then V.ok
      else Left(InvalidField("linkedRecordIds", "must be strictly ascending")),
      V.prov(e.provenance),
    )
  )

/** A rejected input record (schema `reject-v1`). Exactly where it came from is given by
  * `sourceRecordId` and/or `inputByteOffset` (at least one). `rawExcerpt` is at most 512 Unicode
  * code points; use `RejectRecord.boundExcerpt` to truncate. Rejects carry the run ID, which links
  * to the run manifest for inputs, code version and parameters.
  */
final case class RejectRecord(
    schemaVersion: Int,
    schema: String,
    runId: String,
    jobId: String,
    source: SourceType,
    sourceRecordId: Option[String] = None,
    inputByteOffset: Option[Long] = None,
    reasonCode: RejectReason,
    message: String,
    rawExcerpt: String,
) derives ReadWriter

object RejectRecord:
  val SchemaName: String = "reject-v1"
  val MaxExcerpt: Int    = 512

  /** Truncates to at most `MaxExcerpt` code points (never splits a surrogate pair). */
  def boundExcerpt(raw: String): String =
    if raw.codePointCount(0, raw.length) <= MaxExcerpt then raw
    else raw.substring(0, raw.offsetByCodePoints(0, MaxExcerpt))

  given Contract[RejectRecord] = V.contract(r =>
    V.all(
      V.schema(r.schemaVersion),
      if r.schema == SchemaName then V.ok
      else Left(InvalidField("schema", s"must be '$SchemaName'")),
      V.unit(Ids.checkRunId(r.runId)),
      V.unit(Ids.token("jobId", r.jobId)),
      r.sourceRecordId.fold(V.ok)(id => V.nonBlank("sourceRecordId", id)),
      r.inputByteOffset.fold(V.ok)(o =>
        if o >= 0 then V.ok else Left(InvalidField("inputByteOffset", "must be >= 0"))
      ),
      if r.sourceRecordId.isDefined || r.inputByteOffset.isDefined then V.ok
      else Left(MissingField("sourceRecordId or inputByteOffset")),
      V.nonBlank("message", r.message),
      if r.rawExcerpt.codePointCount(0, r.rawExcerpt.length) <= MaxExcerpt then V.ok
      else Left(InvalidField("rawExcerpt", s"must be at most $MaxExcerpt characters")),
    )
  )

/** A produced file of one job. */
final case class OutputRef(path: String, rowCount: Long, checksum: Option[String] = None)
    derives ReadWriter

/** One job's counters and outputs. Counter names are free-form tokens; values are >= 0. */
final case class JobSummary(jobId: String, counters: Map[String, Long], outputs: List[OutputRef])
    derives ReadWriter

/** Manifest of one Hadoop pipeline run. A new type: `RunManifest` (Phase 03) is frozen.
  *
  * `runId` is `Ids.runId(stage, codeVersion, attempt, config, inputs)`, the existing formula, so it
  * is content-derived. `snapshot` names the dataset snapshot and is descriptive: the inputs'
  * checksums already pin its content. Every input must carry a SHA-256 checksum.
  */
final case class PipelineRunManifest(
    schemaVersion: Int,
    runId: String,
    stage: String,
    attempt: Int,
    snapshot: String,
    codeVersion: String,
    inputs: List[InputRef],
    config: Map[String, String],
    jobs: List[JobSummary],
    createdAt: Option[String] = None,
) derives ReadWriter

object PipelineRunManifest:
  def build(
      stage: String,
      attempt: Int,
      snapshot: String,
      codeVersion: String,
      inputs: List[InputRef],
      config: Map[String, String],
      jobs: List[JobSummary],
      createdAt: Option[String] = None,
  ): Either[ValidationError, PipelineRunManifest] =
    Ids
      .runId(stage, codeVersion, attempt, config, inputs)
      .flatMap(id =>
        summon[Contract[PipelineRunManifest]].validate(
          PipelineRunManifest(
            SchemaVersion.Current,
            id,
            stage,
            attempt,
            snapshot,
            codeVersion,
            inputs,
            config,
            jobs,
            createdAt,
          )
        )
      )

  private def job(j: JobSummary): V.R =
    V.all(
      V.unit(Ids.token("jobs.jobId", j.jobId)),
      V.each(j.counters)((k, v) =>
        V.all(
          V.unit(Ids.token("jobs.counters.name", k)),
          if v >= 0 then V.ok else Left(InvalidField(s"jobs.counters.$k", "must be >= 0")),
        )
      ),
      V.each(j.outputs)(o =>
        V.all(
          V.nonBlank("jobs.outputs.path", o.path),
          if o.rowCount >= 0 then V.ok
          else Left(InvalidField("jobs.outputs.rowCount", "must be >= 0")),
          o.checksum.fold(V.ok)(c =>
            if Hashing.isSha256Hex(c) then V.ok
            else Left(InvalidField("jobs.outputs.checksum", "must be 64 lowercase hex characters"))
          ),
        )
      ),
    )

  given Contract[PipelineRunManifest] = V.contract(m =>
    V.all(
      V.schema(m.schemaVersion),
      V.unit(Ids.token("snapshot", m.snapshot)),
      V.nonEmpty("inputs", m.inputs),
      V.each(m.inputs)(i =>
        V.all(
          V.unit(InputRef.validate(i)),
          if i.checksum.isDefined then V.ok
          else Left(InvalidField("inputs.checksum", "is required for pipeline inputs")),
        )
      ),
      V.matches(
        "runId",
        Ids.runId(m.stage, m.codeVersion, m.attempt, m.config, m.inputs),
        m.runId,
      ),
      V.each(m.config.keys)(k => V.nonBlank("config.key", k)),
      V.distinct("jobs.jobId", m.jobs.map(_.jobId)),
      V.each(m.jobs)(job),
      m.createdAt.fold(V.ok)(t =>
        if Try(Instant.parse(t)).isSuccess then V.ok
        else Left(InvalidField("createdAt", "must be an ISO-8601 instant"))
      ),
    )
  )
