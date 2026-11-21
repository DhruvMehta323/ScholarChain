package scholarchain.er

import scholarchain.core.*

/** Feature set `er-features-v2`: raw, unscaled features of a candidate pair. A feature that cannot
  * be computed (a missing field) is ABSENT from the map; it is never faked. Scoring treats an
  * absent feature as neutral (0.5).
  *
  * Citation-neighbourhood evidence is not available in this corpus (no citation edges), so there is
  * no such feature.
  *
  * Researcher features (a pair of author mentions): `name_sim`, `family_exact`, `family_jw`
  * (Jaro-Winkler of the two family names; not a score weight, used by the ORCID name rule),
  * `initials_compat`, `homonym_suffix_present`, `homonym_suffix_conflict`, `name_mixed_script`,
  * `position_agreement` (only when both mentions sit on works with the same DOI),
  * `coauthor_overlap`, `affiliation_compat`, `work_title_sim`, `year_distance`, `venue_compat`,
  * `orcid_agreement`, `source_reliability`, `exact_id_conflict`.
  *
  * Work features: `title_sim`, `title_exact`, `year_distance`, `author_overlap`,
  * `first_author_agree`, `venue_compat`, `doi_agreement`, `doi_preprint_version_pair` (present,
  * 1.0, only for a possible preprint and published version, see `preprintVersionPair`),
  * `arxiv_agreement`, `openalex_agreement`, `source_reliability`, `exact_id_conflict`,
  * `author_mixed_script`.
  *
  * Agreement features are 1.0 (both sides carry the identifier and it is equal after core's
  * normalizer) or 0.0 (both carry it and it differs); absent otherwise.
  */
object Features:
  val Version = "er-features-v2"

  private val Reliability: Map[SourceType, Double] = Map(
    SourceType.Orcid        -> 0.95,
    SourceType.Dblp         -> 0.90,
    SourceType.Crossref     -> 0.85,
    SourceType.OpenAlex     -> 0.75,
    SourceType.ScholarChain -> 0.50,
  )

  def sourceReliability(a: SourceType, b: SourceType): Double =
    math.min(Reliability(a), Reliability(b))

  private def b2d(b: Boolean): Double = if b then 1.0 else 0.0

  private def agreement(
      x: Option[String],
      y: Option[String],
      norm: String => Option[String],
  ): Option[Double] =
    for
      a <- x.flatMap(norm)
      b <- y.flatMap(norm)
    yield b2d(a == b)

  private def keys(names: List[String]): Set[String] = names.flatMap(Names.personKey).toSet

  private def venueCompat(a: Option[String], b: Option[String]): Option[Double] =
    for
      x <- a.map(Strings.textNorm).filter(_.nonEmpty)
      y <- b.map(Strings.textNorm).filter(_.nonEmpty)
    yield if x == y then 1.0 else Metrics.jaccard(x.split(' ').toSet, y.split(' ').toSet)

  private def titleSim(a: Option[String], b: Option[String]): Option[Double] =
    for
      x <- a.map(Strings.titleNorm).filter(_.nonEmpty)
      y <- b.map(Strings.titleNorm).filter(_.nonEmpty)
    yield Metrics.trigramDice(x, y)

  private def yearDistance(a: Option[Int], b: Option[Int]): Option[Double] =
    for
      x <- a
      y <- b
    yield math.abs(x - y).toDouble

  /** Features of two researcher (author-mention) views. `hj3Conflict` is the exact-identifier
    * CONFLICT evidence from Phase 04 HJ3 for either mention.
    */
  def researcher(a: EntityView, b: EntityView, hj3Conflict: Boolean = false): Map[String, Double] =
    val pa = a.name.flatMap(Names.parse)
    val pb = b.name.flatMap(Names.parse)
    val m  = Map.newBuilder[String, Double]
    for (x, y) <- pa.zip(pb) do
      m += "name_sim"                -> Names.similarity(x, y)
      m += "family_exact"            -> b2d(x.family == y.family)
      m += "family_jw"               -> Metrics.jaroWinkler(x.family, y.family)
      m += "homonym_suffix_present"  -> b2d(x.suffix.isDefined || y.suffix.isDefined)
      m += "homonym_suffix_conflict" -> b2d(
        x.suffix.isDefined && y.suffix.isDefined && x.suffix != y.suffix
      )
      m += "name_mixed_script" -> b2d(x.mixedScript || y.mixedScript)
      Names.initialsCompat(x, y).foreach(v => m += "initials_compat" -> v)
    for
      d  <- a.doi.flatMap(ErIds.doi)
      d2 <- b.doi.flatMap(ErIds.doi)
      p  <- a.position
      p2 <- b.position
      if d == d2
    do m += "position_agreement" -> b2d(p == p2)
    val ca = keys(a.authors)
    val cb = keys(b.authors)
    if ca.nonEmpty && cb.nonEmpty then m += "coauthor_overlap" -> Metrics.overlapCoefficient(ca, cb)
    for
      x <- a.affiliation.map(Strings.textNorm).filter(_.nonEmpty)
      y <- b.affiliation.map(Strings.textNorm).filter(_.nonEmpty)
    do m += "affiliation_compat" -> Metrics.jaccard(x.split(' ').toSet, y.split(' ').toSet)
    titleSim(a.title, b.title).foreach(v => m += "work_title_sim" -> v)
    yearDistance(a.year, b.year).foreach(v => m += "year_distance" -> v)
    venueCompat(a.venue, b.venue).foreach(v => m += "venue_compat" -> v)
    agreement(a.orcid, b.orcid, ErIds.orcid).foreach(v => m += "orcid_agreement" -> v)
    m += "source_reliability"                    -> sourceReliability(a.source, b.source)
    if hj3Conflict then m += "exact_id_conflict" -> 1.0
    m.result()

  /** DOI prefixes (lower case, normalized form) of preprint servers: arXiv, OSF Preprints,
    * Preprints.org, SSRN, Research Square, Authorea, TechRxiv. `10.1101/` (Cold Spring Harbor
    * Laboratory) is deliberately NOT listed: it is shared by bioRxiv and medRxiv and by journals
    * (Genome Research `10.1101/gr.*`, Genes and Development `10.1101/gad.*`), and a date-shaped
    * suffix rule would still miss the old numeric bioRxiv DOIs. The smaller, safer change is to
    * leave bioRxiv and medRxiv out (a missed version hint, never a wrong one).
    */
  private val PreprintPrefixes = List(
    "10.48550/",
    "10.31219/",
    "10.20944/",
    "10.2139/",
    "10.21203/",
    "10.22541/",
    "10.36227/",
  )

  /** Titles that count as the same for a preprint and its published version (hand-set). */
  val VersionTitleSim = 0.9

  /** Possible preprint and published version of one work: both DOIs present and different, exactly
    * one is a preprint-server DOI, and the titles match (`title_sim` >= `VersionTitleSim`).
    */
  def preprintVersionPair(a: EntityView, b: EntityView): Boolean =
    (for
      x <- a.doi.flatMap(ErIds.doi)
      y <- b.doi.flatMap(ErIds.doi)
      s <- titleSim(a.title, b.title)
    yield x != y && s >= VersionTitleSim &&
      PreprintPrefixes.exists(x.startsWith) != PreprintPrefixes.exists(y.startsWith))
      .getOrElse(false)

  /** Features of two work views. */
  def work(a: EntityView, b: EntityView, hj3Conflict: Boolean = false): Map[String, Double] =
    val m = Map.newBuilder[String, Double]
    titleSim(a.title, b.title).foreach { v =>
      m += "title_sim"   -> v
      m += "title_exact" -> b2d(
        Strings.titleNorm(a.title.getOrElse("")) == Strings.titleNorm(b.title.getOrElse(""))
      )
    }
    yearDistance(a.year, b.year).foreach(v => m += "year_distance" -> v)
    val ka = keys(a.authors)
    val kb = keys(b.authors)
    if ka.nonEmpty && kb.nonEmpty then m += "author_overlap" -> Metrics.overlapCoefficient(ka, kb)
    for
      x <- a.authors.headOption.flatMap(Names.parse)
      y <- b.authors.headOption.flatMap(Names.parse)
    do m += "first_author_agree" -> b2d(x.family == y.family)
    if (a.authors ++ b.authors).exists(Names.hasMixedScript) then m += "author_mixed_script" -> 1.0
    venueCompat(a.venue, b.venue).foreach(v => m += "venue_compat" -> v)
    agreement(a.doi, b.doi, ErIds.doi).foreach(v => m += "doi_agreement" -> v)
    if preprintVersionPair(a, b) then m += "doi_preprint_version_pair" -> 1.0
    agreement(a.arxiv, b.arxiv, ErIds.arxiv).foreach(v => m += "arxiv_agreement" -> v)
    agreement(a.openAlexId, b.openAlexId, ErIds.openAlex).foreach(v =>
      m += "openalex_agreement" -> v
    )
    m += "source_reliability"                    -> sourceReliability(a.source, b.source)
    if hj3Conflict then m += "exact_id_conflict" -> 1.0
    m.result()

  /** Features of a pair of views of the same kind. */
  def compute(
      a: EntityView,
      b: EntityView,
      hj3Conflict: Boolean = false,
  ): Either[ValidationError, Map[String, Double]] =
    if a.kind != b.kind then
      Left(ValidationError.InvalidField("kind", "both views must have the same entity kind"))
    else
      Right(a.kind match
        case EntityKind.Researcher => researcher(a, b, hj3Conflict)
        case EntityKind.Work       => work(a, b, hj3Conflict))
