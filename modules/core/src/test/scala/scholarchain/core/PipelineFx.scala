package scholarchain.core

/** Synthetic fixtures for the Phase 04 pipeline records. Values mirror the domain-engineer A1
  * report (work, provenance and mention ids), so the frozen examples can be checked verbatim.
  */
object PipelineFx:
  val W: String         = "work:dblp:journals/x/A1"
  val M0: String        = "mention:bbab2007a569a5629af0b15b0e6ad83d704fc65e67f952bdb489bcd531dd1728"
  val M1: String        = "mention:e3125a2371b81d5c1f529ae9addc2da90f66f9555897db9513bc2a5582db75e1"
  val RunReject: String =
    "run:e18d3d9837bbd1d123d801ab9ecafc37c13fc0f316329b629c9426bc467b20da"
  val RunManifestId: String =
    "run:8ecfd4548c5f4331f23466bd0bfe9baf65b2ddee2cacffb661e96751255adb48"
  val Hex: String = "a" * 64

  val provJson: String =
    """{"schemaVersion":1,"sourceType":"dblp","inputs":[{"inputId":"dblp-sample","version":"2026-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"codeVersion":"0.1.0","parameters":{"k":"v"}}"""

  val prov: Provenance = Provenance(
    1,
    SourceType.Dblp,
    List(InputRef("dblp-sample", "2026-01", Some(Hex))),
    "0.1.0",
    Map("k" -> "v"),
  )

  val authorship: MentionAuthorship       = MentionAuthorship(1, M0, W, 0, 2020, prov)
  val team: MentionTeam                   = MentionTeam(1, W, 2020, 2, List(M0, M1), prov)
  val pairCandidate: MentionPairCandidate =
    MentionPairCandidate(1, W, 2020, OrderedPair(M0, M1), 0, 1, prov)

  def evidence(t: IdentifierType, id: String): ExactIdentifierEvidence =
    ExactIdentifierEvidence(1, t, id, List(M0, M1), ExactIdentifierOutcome.ExactLink, prov)

  val doiEvidence: ExactIdentifierEvidence      = evidence(IdentifierType.Doi, "10.1000/abc")
  val openAlexEvidence: ExactIdentifierEvidence =
    evidence(IdentifierType.OpenAlex, "W2718513939")

  val reject: RejectRecord = RejectRecord(
    1,
    "reject-v1",
    RunReject,
    "parse-dblp",
    SourceType.Dblp,
    Some("journals/x/A1"),
    Some(1024L),
    RejectReason.MissingYear,
    "no year element",
    "<article>...",
  )

  val input: InputRef   = InputRef("s3://b/raw/dblp.xml", "2026-01", Some(Hex))
  val output: OutputRef = OutputRef("s3://b/out/works", 9L, Some(Hex))
  val job: JobSummary   =
    JobSummary("parse-dblp", Map("records.in" -> 10L, "records.rejected" -> 1L), List(output))
  val manifestConfig: Map[String, String] = Map("hadoop.large-team-threshold" -> "50")

  val manifest: PipelineRunManifest = Fx.get(
    PipelineRunManifest.build(
      "hadoop-pipeline",
      1,
      "dblp-sample-2026-01",
      "0.1.0",
      List(input),
      manifestConfig,
      List(job),
    )
  )
