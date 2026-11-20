package scholarchain.core

/** Shared synthetic fixtures for the contract suites. Synthetic values only. */
object Fx:
  def get[A](e: Either[ValidationError, A]): A = e match
    case Right(a)  => a
    case Left(err) => throw new AssertionError(s"fixture construction failed: ${err.message}")

  val hex64: String = "a" * 64
  val hello: String = Hashing.textHash("hello")

  val prov: Provenance = Provenance(
    1,
    SourceType.Dblp,
    List(InputRef("dblp.xml", "2026-11-01", Some(hex64))),
    "0.1.0",
    Map("sample" -> "1000"),
    None,
    Some("2026-11-02T10:00:00Z"),
  )

  val provJson: String =
    """{"schemaVersion":1,"sourceType":"dblp","inputs":[{"inputId":"dblp.xml","version":"2026-11-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"codeVersion":"0.1.0","parameters":{"sample":"1000"},"retrievedAt":"2026-11-02T10:00:00Z"}"""

  val orcidId: String = "researcher:orcid:0000-0002-1825-0097"
  val scId: String    =
    "researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5"
  val scId2: String       = "researcher:sc1:" + "2" * 64
  val workId: String      = "work:dblp:conf/sigmod/Foo01"
  val window: GraphWindow = GraphWindow(2000, 2004)
  val pair: OrderedPair   = OrderedPair(orcidId, scId)

  val evId: String = get(Ids.evidenceId(EvidenceType.TextChunk, "work:dblp:x", "v1", hello))

  val work: WorkRecord =
    WorkRecord(1, workId, "Graph Mining", 2001, Some("10.1000/abc.1"), None, Nil, prov)
  val workFull: WorkRecord =
    work.copy(venueId = Some("venue:sigmod"), topicIds = List("topic:graph-mining"))
  val workBare: WorkRecord = work.copy(doi = None)

  val researcher: CanonicalResearcher =
    CanonicalResearcher(1, scId, "Ada Lovelace", None, 0.62, MatchOutcome.Uncertain, prov)
  val researcherOrcid: CanonicalResearcher = CanonicalResearcher(
    1,
    orcidId,
    "Ada Lovelace",
    Some("0000-0002-1825-0097"),
    1.0,
    MatchOutcome.Match,
    prov,
  )

  val coauthorEdge: CoauthorEdge = CoauthorEdge(
    1,
    get(Ids.coauthorEdgeId(orcidId, scId, window, "p1")),
    pair,
    window,
    "p1",
    1,
    List(workId),
    prov,
  )

  val mention: AuthorMention = AuthorMention(
    1,
    get(Ids.mentionId(workId, 0)),
    workId,
    0,
    "A. Lovelace",
    Some("0000-0002-1825-0097"),
    prov,
  )
  val mentionBare: AuthorMention = mention.copy(orcid = None)

  val authorship: AuthorshipRecord = AuthorshipRecord(
    1,
    workId,
    scId,
    mention.mentionId,
    0,
    0.62,
    MatchOutcome.Uncertain,
    prov,
  )
  val team: PublicationTeam            = PublicationTeam(1, workId, 2001, List(orcidId, scId), prov)
  val candidate: CoauthorPairCandidate = CoauthorPairCandidate(1, workId, 2001, pair, prov)
  val metrics: ResearcherMetrics       = ResearcherMetrics(
    1,
    scId,
    window,
    "p1",
    List(
      MetricValue(CollaborationMetric.Degree, 3.0),
      MetricValue(CollaborationMetric.PageRank, 0.15),
    ),
    prov,
  )
  val venue: Venue             = Venue(1, "venue:sigmod", "SIGMOD", Some("conference"), prov)
  val venueBare: Venue         = venue.copy(venueType = None)
  val institution: Institution =
    Institution(1, "institution:test-univ", "Test University", Some("US"), prov)
  val topic: Topic            = Topic(1, "topic:graph-mining", "Graph Mining", prov)
  val decision: MatchDecision = MatchDecision(
    1,
    scId,
    scId2,
    MatchOutcome.Match,
    0.95,
    "p1",
    Map("nameSim" -> 0.5),
    prov,
  )
  val node: GraphNode =
    GraphNode(1, scId, "researcher", "Ada Lovelace", Map("k" -> "v"), prov)
  val gedge: GraphEdge = GraphEdge(
    1,
    get(Ids.graphEdgeId("coauthor", orcidId, scId, false, "p1", List(evId))),
    "coauthor",
    orcidId,
    scId,
    false,
    "p1",
    List(evId),
    prov,
  )
  val evidence: EvidenceItem =
    EvidenceItem(
      1,
      evId,
      EvidenceType.TextChunk,
      "work:dblp:x",
      "v1",
      Some("hello"),
      hello,
      Some(0.5),
      prov,
    )
  val evidenceBare: EvidenceItem = evidence.copy(text = None, score = None)
  val bundle: EvidenceBundle     = EvidenceBundle(1, "query:q1", List(evidence), prov)
  val plan: QueryPlan            = QueryPlan(
    1,
    "query:q1",
    QueryOperation.ListCoauthors,
    "Who are the coauthors of Ada?",
    List(scId),
    None,
    None,
    10,
    prov,
  )
  val planTrend: QueryPlan = plan.copy(
    operation = QueryOperation.CollaborationTrend,
    window = Some(window),
    metric = Some(CollaborationMetric.Degree),
  )
  val claim: AnswerClaim =
    AnswerClaim(
      1,
      "claim:c1",
      "Ada has one coauthored work",
      List(evId),
      VerificationStatus.Supported,
      0.9,
      prov,
    )
  val response: AnswerResponse =
    AnswerResponse(1, "query:q1", "One coauthored work found.", List(claim), bundle, prov)

  val manifest: RunManifest = get(
    RunManifest.build(
      "parse",
      "0.1.0",
      1,
      Map("n" -> "1000"),
      List(InputRef("dblp.xml", "2026-11-01", Some(hex64))),
    )
  )
