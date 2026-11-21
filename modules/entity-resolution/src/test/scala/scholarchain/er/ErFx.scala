package scholarchain.er

import scholarchain.core.*

/** Shared SYNTHETIC fixtures for the entity-resolution unit tests. No real data, no credentials. */
object ErFx:
  val Hex: String = "a" * 64

  def prov(retrievedAt: Option[String] = None): Provenance =
    Provenance(
      SchemaVersion.Current,
      SourceType.ScholarChain,
      List(InputRef("unit:views", "v1", Some(Hex))),
      "unit-test",
      Map("job" -> "unit"),
      Some("run:" + Hex),
      retrievedAt,
    )

  def cfg: ErConfig =
    ErConfigLoader
      .load(Map.empty, Map.empty)
      .fold(es => throw new AssertionError(es.toString), identity)

  def person(
      id: String,
      source: SourceType,
      name: String,
      position: Int = 0,
      year: Int = 2020,
      title: String = "Graph Neural Networks",
      authors: List[String] = Nil,
      doi: Option[String] = None,
      orcid: Option[String] = None,
  ): EntityView =
    EntityView(
      id,
      EntityKind.Researcher,
      source,
      name = Some(name),
      position = Some(position),
      orcid = orcid,
      title = Some(title),
      year = Some(year),
      doi = doi,
      authors = authors,
    )

  def paper(
      id: String,
      source: SourceType,
      title: String,
      year: Int = 2020,
      authors: List[String] = Nil,
      doi: Option[String] = None,
  ): EntityView =
    EntityView(
      id,
      EntityKind.Work,
      source,
      title = Some(title),
      year = Some(year),
      doi = doi,
      authors = authors,
    )

  def cand(
      left: String,
      right: String,
      kind: EntityKind,
      features: Map[String, Double],
      retrievedAt: Option[String] = None,
  ): MatchCandidate =
    MatchCandidate
      .build(
        left,
        right,
        kind,
        if kind == EntityKind.Researcher then "r1|wang|w" else "w1|graph|y673",
        BlockKeyType.Primary,
        features,
        Features.Version,
        prov(retrievedAt),
      )
      .fold(e => throw new AssertionError(e.toString), identity)

  val Thr: Thresholds = Thresholds(0.85, 0.45, 0.55, 0.80)

  def close(a: Double, b: Double, eps: Double = 1e-9): Boolean = math.abs(a - b) < eps
