package scholarchain.er

import scholarchain.core.*

/** Identifier normalization. Only delegates to core's normalizers: there is no second rule. */
object ErIds:
  def doi(s: String): Option[String]      = Doi.normalize(s).toOption
  def arxiv(s: String): Option[String]    = ArxivId.normalize(s).toOption
  def orcid(s: String): Option[String]    = Orcid.parse(s).toOption
  def openAlex(s: String): Option[String] = OpenAlexId.normalize(s).toOption

  /** Stable, order-independent pair identifier used by the gold tools: `P` + 12 hex characters. */
  def pairId(kind: EntityKind, leftId: String, rightId: String): String =
    val (l, r) = if leftId.compareTo(rightId) <= 0 then (leftId, rightId) else (rightId, leftId)
    "P" + Hashing.digest("scholarchain.er.pair.v1", Seq(kind.wire, l, r)).take(12)
