package scholarchain.er

import scholarchain.core.*

final case class BlockKey(key: String, keyType: BlockKeyType)

/** Blocking keys and candidate generation.
  *
  * Researcher (author-mention) keys:
  *   - primary: `r1|<family without spaces>|<first initial>` (accent-folded, lower case; `_` when
  *     the name has no given name);
  *   - fallbacks (finer, used only when the primary block is oversize): `<primary>|y<year/5>`
  *     (five-year bucket) and `<primary>|c<coauthor family>` for each coauthor (at most 8).
  * Work keys:
  *   - primary: `w1|<title tokens>|y<year/3>`, where the tokens are the (up to) four longest
  *     alphanumeric title tokens of length >= 4 (stop words removed, each cut to 12 characters),
  *     sorted and joined by `-`;
  *   - fallback: `<primary>|v<venue key>` (`vnone` when the venue is absent).
  *
  * `generate` yields ONLY the keys above. It does not include the exact-identifier keys
  * `x1|<type>|<id>` that HJ6 adds on top (one per ORCID, DOI, arXiv id or OpenAlex id); those live
  * in the HJ6 job, not here.
  *
  * A year bucket loses a pair whose years fall in neighbouring buckets (a recall cost, documented
  * in ER-CONTRACTS.md).
  *
  * Candidates are cross-source only: exactly one side is DBLP. A DBLP-DBLP researcher pair is
  * allowed only when person-alias evidence links the two names (`aliasLinked`). The source pair is
  * recorded in the candidate provenance parameters `leftSource` and `rightSource`.
  */
object Blocking:
  private val StopWords = Set(
    "with",
    "from",
    "that",
    "this",
    "using",
    "based",
    "their",
    "into",
    "over",
    "under",
    "about",
    "towards",
    "toward",
    "between",
    "through",
    "approach",
    "analysis",
    "study",
  )

  private def clean(s: String): String = s.filter(c => !c.isWhitespace)

  def researcherPrimary(name: String): Option[String] =
    Names.parse(name).map(p => s"r1|${p.familyKey}|${p.firstInitial.getOrElse("_")}")

  def researcherFallbacks(
      name: String,
      year: Option[Int],
      coauthors: List[String],
  ): List[BlockKey] =
    researcherPrimary(name).toList.flatMap { p =>
      val y  = year.map(v => BlockKey(s"$p|y${v / 5}", BlockKeyType.Fallback)).toList
      val cs = coauthors
        .flatMap(Names.parse)
        .map(_.familyKey)
        .filter(_.nonEmpty)
        .distinct
        .sorted
        .take(8)
        .map(c => BlockKey(s"$p|c$c", BlockKeyType.Fallback))
      y ++ cs
    }

  def workPrimary(title: String, year: Option[Int]): Option[String] =
    val toks = Strings
      .titleNorm(title)
      .split(' ')
      .toList
      .filter(t => t.length >= 4 && !StopWords.contains(t))
      .distinct
      .sortBy(t => (-t.length, t))
      .take(4)
      .map(_.take(12))
      .sorted
    if toks.isEmpty then None
    else Some(s"w1|${toks.mkString("-")}|y${year.map(y => (y / 3).toString).getOrElse("none")}")

  def workFallbacks(title: String, year: Option[Int], venue: Option[String]): List[BlockKey] =
    workPrimary(title, year).toList.map { p =>
      val v = venue.map(Strings.textNorm).filter(_.nonEmpty).map(s => clean(s).take(16))
      BlockKey(s"$p|v${v.getOrElse("none")}", BlockKeyType.Fallback)
    }

  /** The primary key of a view, or `None` when the view lacks the fields it needs. */
  def primary(v: EntityView): Option[BlockKey] =
    (v.kind match
      case EntityKind.Researcher => v.name.flatMap(researcherPrimary)
      case EntityKind.Work       => v.title.flatMap(workPrimary(_, v.year))
    ).map(BlockKey(_, BlockKeyType.Primary))

  /** Fallback keys of a view, in routing order. */
  def fallbacks(v: EntityView): List[BlockKey] = v.kind match
    case EntityKind.Researcher => v.name.toList.flatMap(researcherFallbacks(_, v.year, v.authors))
    case EntityKind.Work       => v.title.toList.flatMap(workFallbacks(_, v.year, v.venue))

  /** All keys of a view: the primary first, then the fallbacks. */
  def keys(v: EntityView): List[BlockKey] = primary(v).toList ++ fallbacks(v)

  /** Cross-source rule: exactly one side is DBLP, or both are DBLP with alias evidence. */
  def eligible(a: EntityView, b: EntityView, aliasLinked: Boolean = false): Boolean =
    a.id != b.id && a.kind == b.kind && {
      val (da, db) = (a.source == SourceType.Dblp, b.source == SourceType.Dblp)
      (da != db) || (da && db && a.kind == EntityKind.Researcher && aliasLinked)
    }

  /** Builds the candidate of two views under a block key. `base` supplies the provenance (inputs,
    * code version, run ID); the source pair and feature-set version are added to its parameters.
    */
  def candidate(
      a: EntityView,
      b: EntityView,
      key: BlockKey,
      base: Provenance,
      hj3Conflict: Boolean = false,
  ): Either[ValidationError, MatchCandidate] =
    Features.compute(a, b, hj3Conflict).flatMap { feats =>
      val (l, r) = if a.id.compareTo(b.id) <= 0 then (a, b) else (b, a)
      val prov   = base.copy(parameters =
        base.parameters ++ Map(
          "leftSource"        -> l.source.wire,
          "rightSource"       -> r.source.wire,
          "featureSetVersion" -> Features.Version,
        )
      )
      MatchCandidate.build(
        a.id,
        b.id,
        a.kind,
        key.key,
        key.keyType,
        feats,
        Features.Version,
        prov,
      )
    }

  /** Between two candidates for the same pair, the one with the smaller (type, key) wins: primary
    * before fallback, then the lexicographically smaller key.
    */
  def preferred(x: MatchCandidate, y: MatchCandidate): MatchCandidate =
    def rank(c: MatchCandidate) =
      (if c.blockKeyType == BlockKeyType.Primary then 0 else 1, c.blockKey)
    if Ordering[(Int, String)].lteq(rank(x), rank(y)) then x else y

  final case class Generation(
      candidates: List[MatchCandidate],
      oversizeBlocks: List[(String, Int)],
  )

  /** Reference single-JVM candidate generation, with the policy HJ6 follows: group by primary key;
    * a primary block over `maxBlockSize` is regrouped by the fallback keys of its members; a block
    * still over the cap is skipped and listed. No block with more than `maxBlockSize` members ever
    * produces a pair. Output is sorted by (leftId, rightId), one candidate per pair.
    */
  def generate(
      views: Seq[EntityView],
      maxBlockSize: Int,
      base: Provenance,
  ): Generation =
    val byPrimary = views.flatMap(v => primary(v).map(k => k.key -> v)).groupBy(_._1)
    val oversize  = List.newBuilder[(String, Int)]
    val blocks    = List.newBuilder[(BlockKey, Seq[EntityView])]
    byPrimary.toList.sortBy(_._1).foreach { (k, members) =>
      val vs = members.map(_._2)
      if vs.size <= maxBlockSize then blocks += (BlockKey(k, BlockKeyType.Primary) -> vs)
      else
        val fb = vs.flatMap(v => fallbacks(v).map(f => f.key -> v)).groupBy(_._1)
        fb.toList.sortBy(_._1).foreach { (fk, fm) =>
          val fv = fm.map(_._2).distinct
          if fv.size <= maxBlockSize then blocks += (BlockKey(fk, BlockKeyType.Fallback) -> fv)
          else oversize += (fk                                                           -> fv.size)
        }
    }
    val out = scala.collection.mutable.LinkedHashMap.empty[(String, String), MatchCandidate]
    blocks.result().foreach { (key, members) =>
      val sorted = members.sortBy(_.id)
      for
        i <- sorted.indices
        j <- (i + 1) until sorted.size
        if eligible(sorted(i), sorted(j))
        c <- candidate(sorted(i), sorted(j), key, base).toOption
      do
        val pk = (c.leftId, c.rightId)
        out.update(pk, out.get(pk).map(preferred(_, c)).getOrElse(c))
    }
    Generation(out.values.toList.sortBy(c => (c.leftId, c.rightId)), oversize.result().sorted)
