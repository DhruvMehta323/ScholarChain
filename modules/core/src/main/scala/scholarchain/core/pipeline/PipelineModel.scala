package scholarchain.core.pipeline

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}

/** Pipeline records moved from `scholarchain.hadoop.Model` (Phase 05 F7). They live in the
  * subpackage `scholarchain.core.pipeline` so that they do not clash with the old hadoop
  * definitions (which `import scholarchain.core.*`) until Hadoop switches over. Wire format is
  * identical to the Phase 04 output: same field names and order, `schemaVersion` 1. Validation adds
  * only: `PersonAlias.aliasKey` is a non-blank token without whitespace and `names` are non-empty;
  * counts are non-negative.
  */
final case class PersonAlias(
    schemaVersion: Int,
    aliasKey: String,
    names: List[String],
    orcids: List[String],
    urls: List[String],
    provenance: Provenance,
) derives ReadWriter

object PersonAlias:
  given Contract[PersonAlias] = V.contract(a =>
    V.all(
      V.schema(a.schemaVersion),
      V.unit(Ids.token("aliasKey", a.aliasKey)),
      V.nonEmpty("names", a.names),
      V.each(a.names)(n => V.nonBlank("names", n)),
      V.each(a.orcids)(o => V.unit(Orcid.parse(o))),
      V.prov(a.provenance),
    )
  )

/** A losing duplicate of a source record. `winner` and `duplicate` name the inputs compared (a byte
  * offset for DBLP, a file name for the enrichment sources).
  */
final case class DuplicateRecord(
    schemaVersion: Int,
    source: SourceType,
    sourceRecordId: String,
    winner: String,
    duplicate: String,
    provenance: Provenance,
) derives ReadWriter

object DuplicateRecord:
  given Contract[DuplicateRecord] = V.contract(d =>
    V.all(
      V.schema(d.schemaVersion),
      V.nonBlank("sourceRecordId", d.sourceRecordId),
      V.nonBlank("winner", d.winner),
      V.nonBlank("duplicate", d.duplicate),
      V.prov(d.provenance),
    )
  )

/** A team above `largeTeamThreshold` authors. A flag only: its pairs are still emitted. */
final case class LargeTeam(
    schemaVersion: Int,
    workId: String,
    teamSize: Int,
    pairCount: Long,
    threshold: Int,
    provenance: Provenance,
) derives ReadWriter

object LargeTeam:
  given Contract[LargeTeam] = V.contract(t =>
    V.all(
      V.schema(t.schemaVersion),
      V.unit(Ids.checkWorkId(t.workId)),
      V.atLeast("teamSize", t.teamSize, 1),
      if t.pairCount >= 0 then V.ok
      else Left(ValidationError.InvalidField("pairCount", "must be >= 0")),
      V.atLeast("threshold", t.threshold, 1),
      V.prov(t.provenance),
    )
  )

/** A team whose pair count exceeds `pairExpansionHardCap`. It is routed whole: none of its pairs is
  * emitted. Its team and authorship records are still written.
  */
final case class OversizeTeam(
    schemaVersion: Int,
    workId: String,
    teamSize: Int,
    pairCount: Long,
    hardCap: Int,
    provenance: Provenance,
) derives ReadWriter

object OversizeTeam:
  given Contract[OversizeTeam] = V.contract(t =>
    V.all(
      V.schema(t.schemaVersion),
      V.unit(Ids.checkWorkId(t.workId)),
      V.atLeast("teamSize", t.teamSize, 1),
      if t.pairCount >= 0 then V.ok
      else Left(ValidationError.InvalidField("pairCount", "must be >= 0")),
      V.atLeast("hardCap", t.hardCap, 1),
      V.prov(t.provenance),
    )
  )
