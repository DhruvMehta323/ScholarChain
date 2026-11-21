package scholarchain.core

import scholarchain.core.pipeline.*
import ValidationError.*
import PipelineFx.{M0, M1, W}

/** Phase 05 A2: contract tests for the entity-resolution records, the F7 records and the enums. */
class ResolutionContractSuite extends ContractSupport:

  private val P: Provenance = Fx.prov
  private val PJ: String    = Fx.provJson
  private val W2: String    = "work:dblp:journals/x/B2"
  private val Run: String   = PipelineFx.RunReject
  private val Hash: String  = "b" * 64

  private val candidate: MatchCandidate = MatchCandidate(
    1,
    M0,
    M1,
    EntityKind.Researcher,
    "smith-j",
    BlockKeyType.Primary,
    Map("nameSimilarity" -> 0.5, "yearDistance" -> 2.0),
    "er-features-v1",
    P,
  )

  private val decision: MatchDecisionV2 = MatchDecisionV2(
    1,
    M0,
    M1,
    EntityKind.Researcher,
    ResolutionOutcome.Accepted,
    MatchPath.Deterministic,
    0.75,
    List("rule.name"),
    List("name_match"),
    None,
    "er-v1",
    "2026-11-02T10:00:00Z",
    Run,
    Map("nameSimilarity" -> 0.5),
    P,
  )

  private val llmDecision: MatchDecisionV2 =
    decision.copy(matchPath = MatchPath.Llm, modelVersion = Some("model-x|er-adjudication-v1"))

  private val adjudication: AdjudicationRecord = AdjudicationRecord(
    1,
    M0,
    M1,
    EntityKind.Researcher,
    AdjudicationOutcome.Accept,
    0.9,
    List("name", "coauthors"),
    List("same_coauthors"),
    "er-adjudication-v1",
    "model-x",
    "er-v1",
    Hash,
    P,
  )

  private val abstain: AdjudicationRecord =
    adjudication.copy(
      outcome = AdjudicationOutcome.Abstain,
      confidence = 0.0,
      citedFields = Nil,
      reasonCodes = List("insufficient_evidence"),
    )

  private val unresolvedId: String = Fx.get(Ids.researcherIdUnresolved(M0))

  private val resolvedAccepted: ResolvedMention =
    ResolvedMention(1, M0, Fx.scId, ResolutionOutcome.Accepted, 0.95, "er-v1", None, P)

  private val resolvedSingleton: ResolvedMention =
    Fx.get(
      ResolvedMention
        .unresolved(M0, ResolutionOutcome.Unresolved, 0.4, "er-v1", Some(Fx.scId), P)
    )

  private val alias: PersonAlias =
    PersonAlias(1, "homepages/s/smith", List("J. Smith"), List("0000-0002-1825-0097"), Nil, P)
  private val dup: DuplicateRecord =
    DuplicateRecord(1, SourceType.Dblp, "journals/x/A1", "1024", "2048", P)
  private val large: LargeTeam       = LargeTeam(1, W, 60, 1770L, 50, P)
  private val oversize: OversizeTeam = OversizeTeam(1, W, 5000, 12497500L, 1000000, P)

  // ---- helpers --------------------------------------------------------------------------------

  private def roundTrip[A](name: String, a: A)(using Contract[A], WirePickle.ReadWriter[A]): Unit =
    test(s"round trip: $name") {
      assertEquals(Wire.read[A](right(Wire.write(a))), Right(a))
    }

  /** Frozen wire: exact text, and it reads back to the same value. */
  private def frozen[A](name: String, a: A, json: String)(using
      Contract[A],
      WirePickle.ReadWriter[A],
  ): Unit =
    test(s"frozen json: $name") {
      assertEquals(Wire.write(a), Right(json))
      assertEquals(Wire.read[A](json), Right(a))
    }

  // ---- frozen wire ----------------------------------------------------------------------------

  frozen(
    "MatchCandidate",
    candidate,
    s"""{"schemaVersion":1,"leftId":"$M0","rightId":"$M1","entityKind":"researcher","blockKey":"smith-j","blockKeyType":"primary","features":{"nameSimilarity":0.5,"yearDistance":2},"featureSetVersion":"er-features-v1","provenance":$PJ}""",
  )
  frozen(
    "MatchDecisionV2",
    decision,
    s"""{"schemaVersion":1,"leftId":"$M0","rightId":"$M1","entityKind":"researcher","outcome":"accepted","matchPath":"deterministic","confidence":0.75,"rulesFired":["rule.name"],"reasonCodes":["name_match"],"policyVersion":"er-v1","decisionTimestamp":"2026-11-02T10:00:00Z","runId":"$Run","features":{"nameSimilarity":0.5},"provenance":$PJ}""",
  )
  frozen(
    "MatchDecisionV2 llm with modelVersion",
    llmDecision,
    s"""{"schemaVersion":1,"leftId":"$M0","rightId":"$M1","entityKind":"researcher","outcome":"accepted","matchPath":"llm","confidence":0.75,"rulesFired":["rule.name"],"reasonCodes":["name_match"],"modelVersion":"model-x|er-adjudication-v1","policyVersion":"er-v1","decisionTimestamp":"2026-11-02T10:00:00Z","runId":"$Run","features":{"nameSimilarity":0.5},"provenance":$PJ}""",
  )
  frozen(
    "AdjudicationRecord accept",
    adjudication,
    s"""{"schemaVersion":1,"leftId":"$M0","rightId":"$M1","entityKind":"researcher","outcome":"accept","confidence":0.9,"citedFields":["name","coauthors"],"reasonCodes":["same_coauthors"],"promptVersion":"er-adjudication-v1","modelId":"model-x","policyVersion":"er-v1","inputHash":"$Hash","provenance":$PJ}""",
  )
  frozen(
    "AdjudicationRecord abstain",
    abstain,
    s"""{"schemaVersion":1,"leftId":"$M0","rightId":"$M1","entityKind":"researcher","outcome":"abstain","confidence":0,"citedFields":[],"reasonCodes":["insufficient_evidence"],"promptVersion":"er-adjudication-v1","modelId":"model-x","policyVersion":"er-v1","inputHash":"$Hash","provenance":$PJ}""",
  )
  frozen(
    "ResolvedMention accepted",
    resolvedAccepted,
    s"""{"schemaVersion":1,"mentionId":"$M0","researcherId":"${Fx.scId}","outcome":"accepted","confidence":0.95,"policyVersion":"er-v1","provenance":$PJ}""",
  )
  frozen(
    "ResolvedMention unresolved with candidate",
    resolvedSingleton,
    s"""{"schemaVersion":1,"mentionId":"$M0","researcherId":"$unresolvedId","outcome":"unresolved","confidence":0.4,"policyVersion":"er-v1","candidateResearcherId":"${Fx.scId}","provenance":$PJ}""",
  )
  frozen(
    "PersonAlias",
    alias,
    s"""{"schemaVersion":1,"aliasKey":"homepages/s/smith","names":["J. Smith"],"orcids":["0000-0002-1825-0097"],"urls":[],"provenance":$PJ}""",
  )
  frozen(
    "DuplicateRecord",
    dup,
    s"""{"schemaVersion":1,"source":"dblp","sourceRecordId":"journals/x/A1","winner":"1024","duplicate":"2048","provenance":$PJ}""",
  )
  frozen(
    "LargeTeam",
    large,
    s"""{"schemaVersion":1,"workId":"$W","teamSize":60,"pairCount":1770,"threshold":50,"provenance":$PJ}""",
  )
  frozen(
    "OversizeTeam",
    oversize,
    s"""{"schemaVersion":1,"workId":"$W","teamSize":5000,"pairCount":12497500,"hardCap":1000000,"provenance":$PJ}""",
  )

  // ---- round trips ----------------------------------------------------------------------------

  roundTrip("MatchCandidate", candidate)
  roundTrip(
    "MatchCandidate work fallback",
    candidate.copy(
      leftId = W,
      rightId = W2,
      entityKind = EntityKind.Work,
      blockKeyType = BlockKeyType.Fallback,
      features = Map.empty,
    ),
  )
  roundTrip(
    "MatchCandidate researcher ids",
    candidate.copy(leftId = Fx.scId2, rightId = "researcher:sc1:" + "3" * 64),
  )
  roundTrip("MatchDecisionV2", decision)
  roundTrip("MatchDecisionV2 llm", llmDecision)
  for o <- ResolutionOutcome.values; p <- MatchPath.values if p != MatchPath.Llm do
    roundTrip(s"MatchDecisionV2 ${o.wire}/${p.wire}", decision.copy(outcome = o, matchPath = p))
  roundTrip("AdjudicationRecord", adjudication)
  roundTrip("AdjudicationRecord reject", adjudication.copy(outcome = AdjudicationOutcome.Reject))
  roundTrip("AdjudicationRecord abstain", abstain)
  roundTrip(
    "AdjudicationRecord abstain with cited fields",
    abstain.copy(citedFields = List("name")),
  )
  roundTrip("ResolvedMention accepted", resolvedAccepted)
  roundTrip("ResolvedMention unresolved", resolvedSingleton)
  for o <- List(
      ResolutionOutcome.Rejected,
      ResolutionOutcome.Conflict,
      ResolutionOutcome.Unresolved,
    )
  do
    roundTrip(
      s"ResolvedMention ${o.wire} without candidate",
      Fx.get(ResolvedMention.unresolved(M1, o, 0.1, "er-v1", None, P)),
    )
  roundTrip("PersonAlias", alias)
  roundTrip("PersonAlias no orcids", alias.copy(orcids = Nil))
  roundTrip("DuplicateRecord", dup)
  roundTrip("LargeTeam", large)
  roundTrip("OversizeTeam", oversize)

  test("None modelVersion and candidateResearcherId are omitted on the wire") {
    assert(!right(Wire.write(decision)).contains("modelVersion"))
    assert(!right(Wire.write(resolvedAccepted)).contains("candidateResearcherId"))
  }

  // ---- MatchCandidate -------------------------------------------------------------------------

  test("MatchCandidate: reversed order is rejected on direct construction") {
    expectErr(validate(candidate.copy(leftId = M1, rightId = M0))) { case InvalidPair(_, _, _) => }
  }
  test("MatchCandidate: self-pair is rejected") {
    expectErr(validate(candidate.copy(rightId = M0))) { case InvalidPair(_, _, _) => }
  }
  test("MatchCandidate: reversed candidate is not writable") {
    assert(Wire.write(candidate.copy(leftId = M1, rightId = M0)).isLeft)
  }
  test("MatchCandidate.build canonicalizes reversed input to the same record") {
    val feats = candidate.features
    val a     = MatchCandidate.build(
      M0,
      M1,
      EntityKind.Researcher,
      "smith-j",
      BlockKeyType.Primary,
      feats,
      "er-features-v1",
      P,
    )
    val b = MatchCandidate.build(
      M1,
      M0,
      EntityKind.Researcher,
      "smith-j",
      BlockKeyType.Primary,
      feats,
      "er-features-v1",
      P,
    )
    assertEquals(a, Right(candidate))
    assertEquals(b, Right(candidate))
  }
  test("MatchCandidate.build still rejects a self-pair and bad kind") {
    assert(
      MatchCandidate
        .build(M0, M0, EntityKind.Researcher, "k", BlockKeyType.Primary, Map.empty, "v1", P)
        .isLeft
    )
    assert(
      MatchCandidate
        .build(M0, M1, EntityKind.Work, "k", BlockKeyType.Primary, Map.empty, "v1", P)
        .isLeft
    )
  }
  test("MatchCandidate: kind and id prefix must agree") {
    expectErr(validate(candidate.copy(entityKind = EntityKind.Work))) {
      case InvalidField("leftId", _) =>
    }
    expectErr(validate(candidate.copy(leftId = W, rightId = W2))) {
      case InvalidField("leftId", _) =>
    }
    assert(
      validate(
        candidate.copy(
          leftId = "work:dblp:a",
          rightId = "work:dblp:b",
          entityKind = EntityKind.Work,
        )
      ).isRight
    )
  }
  test("MatchCandidate: researcher kind accepts mention and researcher ids") {
    assert(
      validate(candidate.copy(leftId = M0, rightId = Fx.scId)).isRight ||
        validate(candidate.copy(leftId = Fx.scId, rightId = M0)).isRight
    )
  }
  test("MatchCandidate: blockKey and featureSetVersion must be tokens") {
    expectErr(validate(candidate.copy(blockKey = ""))) { case InvalidField("blockKey", _) => }
    expectErr(validate(candidate.copy(blockKey = "a b"))) { case InvalidField("blockKey", _) => }
    expectErr(validate(candidate.copy(featureSetVersion = " "))) {
      case InvalidField("featureSetVersion", _) =>
    }
  }
  test("MatchCandidate: non-finite features and blank feature names are rejected") {
    assert(validate(candidate.copy(features = Map("f" -> Double.NaN))).isLeft)
    assert(validate(candidate.copy(features = Map("f" -> Double.PositiveInfinity))).isLeft)
    assert(validate(candidate.copy(features = Map("f" -> Double.NegativeInfinity))).isLeft)
    assert(validate(candidate.copy(features = Map(" " -> 1.0))).isLeft)
  }
  test("MatchCandidate: schemaVersion and provenance are checked") {
    assert(validate(candidate.copy(schemaVersion = 2)).isLeft)
    assert(validate(candidate.copy(schemaVersion = 0)).isLeft)
    assert(validate(candidate.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("MatchCandidate: malformed id is rejected") {
    assert(validate(candidate.copy(leftId = "mention:")).isLeft)
    assert(validate(candidate.copy(leftId = "")).isLeft)
  }

  // ---- MatchDecisionV2 ------------------------------------------------------------------------

  test("MatchDecisionV2: confidence outside [0,1] is rejected") {
    for c <- List(-0.0001, 1.0001, 2.0, -1.0, Double.NaN, Double.PositiveInfinity) do
      assert(validate(decision.copy(confidence = c)).isLeft, s"confidence $c")
  }
  test("MatchDecisionV2: confidence 0 and 1 are valid") {
    assert(validate(decision.copy(confidence = 0.0)).isRight)
    assert(validate(decision.copy(confidence = 1.0)).isRight)
  }
  test("MatchDecisionV2: llm path without modelVersion is rejected") {
    expectErr(validate(decision.copy(matchPath = MatchPath.Llm))) {
      case InvalidField("modelVersion", _) =>
    }
  }
  test("MatchDecisionV2: modelVersion on a non-llm path is rejected") {
    for p <- List(MatchPath.ExactId, MatchPath.Deterministic, MatchPath.Probabilistic) do
      expectErr(validate(decision.copy(matchPath = p, modelVersion = Some("m|p")))) {
        case InvalidField("modelVersion", _) =>
      }
  }
  test("MatchDecisionV2: blank modelVersion on llm path is rejected") {
    assert(validate(llmDecision.copy(modelVersion = Some(""))).isLeft)
    assert(validate(llmDecision.copy(modelVersion = Some("a b"))).isLeft)
  }
  test("MatchDecisionV2: reversed and self pairs are rejected") {
    expectErr(validate(decision.copy(leftId = M1, rightId = M0))) { case InvalidPair(_, _, _) => }
    expectErr(validate(decision.copy(rightId = M0))) { case InvalidPair(_, _, _) => }
  }
  test("MatchDecisionV2: kind and id prefix must agree") {
    assert(validate(decision.copy(entityKind = EntityKind.Work)).isLeft)
  }
  test("MatchDecisionV2: rulesFired and reasonCodes must be non-empty tokens") {
    expectErr(validate(decision.copy(rulesFired = Nil))) { case InvalidField("rulesFired", _) => }
    expectErr(validate(decision.copy(reasonCodes = Nil))) { case InvalidField("reasonCodes", _) => }
    assert(validate(decision.copy(rulesFired = List("a b"))).isLeft)
    assert(validate(decision.copy(reasonCodes = List(""))).isLeft)
  }
  test("MatchDecisionV2: decisionTimestamp must be an ISO-8601 instant") {
    for t <- List("", "yesterday", "2026-11-02", "2026-11-02 10:00:00", "2026-13-40T00:00:00Z") do
      expectErr(validate(decision.copy(decisionTimestamp = t))) {
        case InvalidField("decisionTimestamp", _) =>
      }
    assert(validate(decision.copy(decisionTimestamp = "2026-11-02T10:00:00.123Z")).isRight)
  }
  test("MatchDecisionV2: runId, policyVersion, features, schemaVersion, provenance") {
    assert(validate(decision.copy(runId = "run:xyz")).isLeft)
    assert(validate(decision.copy(runId = "")).isLeft)
    assert(validate(decision.copy(policyVersion = "")).isLeft)
    assert(validate(decision.copy(features = Map("f" -> Double.NaN))).isLeft)
    assert(validate(decision.copy(schemaVersion = 2)).isLeft)
    assert(validate(decision.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("MatchDecisionV2: an invalid record is never written") {
    assert(Wire.write(decision.copy(confidence = 1.5)).isLeft)
  }
  test("MatchDecisionV2: unknown outcome or path on the wire is rejected") {
    val good = right(Wire.write(decision))
    assert(Wire.read[MatchDecisionV2](good.replace("\"accepted\"", "\"ACCEPTED\"")).isLeft)
    assert(Wire.read[MatchDecisionV2](good.replace("\"deterministic\"", "\"magic\"")).isLeft)
  }

  test("MatchDecisionV2: a JSON null enum on the wire is rejected, not read as null") {
    val good = right(Wire.write(decision))
    val r    = scala.util.Try(Wire.read[MatchDecisionV2](good.replace("\"accepted\"", "null")))
    assert(r.isSuccess, s"Wire.read threw: $r")
    assert(r.get.isLeft, s"null outcome was accepted: ${r.get}")
  }

  // ---- AdjudicationRecord ---------------------------------------------------------------------

  test("AdjudicationRecord: valid accept validates against its candidate") {
    assertEquals(AdjudicationRecord.validateAgainst(adjudication, candidate), Right(adjudication))
  }
  test("AdjudicationRecord: fabricated rightId is rejected by validateAgainst") {
    val fabricated = adjudication.copy(rightId = "mention:" + "f" * 64)
    assertEquals(
      AdjudicationRecord.validateAgainst(fabricated, candidate),
      Left(InvalidField("rightId", "is not present in the candidate")),
    )
  }
  test("AdjudicationRecord: fabricated leftId is rejected by validateAgainst") {
    val fabricated = adjudication.copy(leftId = "mention:" + "0" * 64)
    expectErr(AdjudicationRecord.validateAgainst(fabricated, candidate)) {
      case InvalidField("leftId", _) =>
    }
  }
  test("AdjudicationRecord: both ids fabricated is rejected") {
    val fabricated =
      adjudication.copy(leftId = "mention:" + "0" * 64, rightId = "mention:" + "f" * 64)
    assert(AdjudicationRecord.validateAgainst(fabricated, candidate).isLeft)
  }
  test("AdjudicationRecord: ids from a different pair are rejected, even if each is canonical") {
    val other   = "mention:" + "e" * 64
    val swapped = adjudication.copy(leftId = M1, rightId = other)
    assert(AdjudicationRecord.validateAgainst(swapped, candidate).isLeft)
  }
  test("AdjudicationRecord: entityKind must equal the candidate's") {
    val wk = adjudication.copy(leftId = W, rightId = W2, entityKind = EntityKind.Work)
    assert(AdjudicationRecord.validateAgainst(wk, candidate).isLeft)
  }
  test("AdjudicationRecord: validateAgainst also runs the contract") {
    assert(
      AdjudicationRecord.validateAgainst(adjudication.copy(confidence = 2.0), candidate).isLeft
    )
  }
  test("AdjudicationRecord: abstain is valid, with empty citedFields") {
    assert(abstain.citedFields.isEmpty)
    assertEquals(validate(abstain), Right(abstain))
    assertEquals(AdjudicationRecord.validateAgainst(abstain, candidate), Right(abstain))
  }
  test("AdjudicationRecord: accept and reject require citedFields") {
    for o <- List(AdjudicationOutcome.Accept, AdjudicationOutcome.Reject) do
      expectErr(validate(adjudication.copy(outcome = o, citedFields = Nil))) {
        case InvalidField("citedFields", _) =>
      }
  }
  test("AdjudicationRecord: confidence outside [0,1] is rejected") {
    for c <- List(-0.01, 1.01, 5.0, Double.NaN, Double.NegativeInfinity) do
      assert(validate(adjudication.copy(confidence = c)).isLeft, s"confidence $c")
      assert(validate(abstain.copy(confidence = c)).isLeft, s"abstain confidence $c")
  }
  test("AdjudicationRecord: reasonCodes must be non-empty tokens, citedFields tokens") {
    expectErr(validate(adjudication.copy(reasonCodes = Nil))) {
      case InvalidField("reasonCodes", _) =>
    }
    expectErr(validate(abstain.copy(reasonCodes = Nil))) { case InvalidField("reasonCodes", _) => }
    assert(validate(adjudication.copy(reasonCodes = List("a b"))).isLeft)
    assert(validate(adjudication.copy(citedFields = List(""))).isLeft)
  }
  test("AdjudicationRecord: inputHash must be SHA-256 lowercase hex") {
    for h <- List("", "abc", "B" * 64, "g" * 64, "b" * 63, "b" * 65) do
      expectErr(validate(adjudication.copy(inputHash = h))) { case InvalidField("inputHash", _) => }
  }
  test("AdjudicationRecord: prompt, model and policy versions are tokens") {
    assert(validate(adjudication.copy(promptVersion = "")).isLeft)
    assert(validate(adjudication.copy(modelId = "a b")).isLeft)
    assert(validate(adjudication.copy(policyVersion = " ")).isLeft)
  }
  test("AdjudicationRecord: reversed pair, schemaVersion and provenance") {
    expectErr(validate(adjudication.copy(leftId = M1, rightId = M0))) { case InvalidPair(_, _, _) =>
    }
    assert(validate(adjudication.copy(schemaVersion = 2)).isLeft)
    assert(validate(adjudication.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("AdjudicationRecord: unknown outcome on the wire is rejected") {
    val good = right(Wire.write(adjudication))
    assert(Wire.read[AdjudicationRecord](good.replace("\"accept\"", "\"maybe\"")).isLeft)
    assert(Wire.read[AdjudicationRecord](good.replace("\"accept\"", "\"ACCEPT\"")).isLeft)
  }

  // ---- unresolved id --------------------------------------------------------------------------

  test("researcherIdUnresolved is deterministic and has the documented shape") {
    assertEquals(Ids.researcherIdUnresolved(M0), Ids.researcherIdUnresolved(M0))
    val expected =
      "researcher:sc1:" + Hashing.digest("scholarchain.researcher.v1", Seq("unresolved.v1:" + M0))
    assertEquals(Ids.researcherIdUnresolved(M0), Right(expected))
    assertEquals(
      Ids.researcherIdUnresolved(M0),
      Ids.researcherIdScholarChain("unresolved.v1:" + M0),
    )
    assert(Ids.checkResearcherId(unresolvedId).isRight)
  }
  test("researcherIdUnresolved differs per mention and from the plain identity key") {
    assertNotEquals(Ids.researcherIdUnresolved(M0), Ids.researcherIdUnresolved(M1))
    assertNotEquals(Ids.researcherIdUnresolved(M0), Ids.researcherIdScholarChain(M0))
  }
  test("researcherIdUnresolved takes no policy version: it cannot depend on one") {
    val a =
      Fx.get(ResolvedMention.unresolved(M0, ResolutionOutcome.Unresolved, 0.1, "er-v1", None, P))
    val b =
      Fx.get(ResolvedMention.unresolved(M0, ResolutionOutcome.Unresolved, 0.1, "er-v2", None, P))
    assertEquals(a.researcherId, b.researcherId)
    assertEquals(a.researcherId, unresolvedId)
  }
  test("researcherIdUnresolved rejects a non-mention id") {
    assert(Ids.researcherIdUnresolved("").isLeft)
    assert(Ids.researcherIdUnresolved("mention:xyz").isLeft)
    assert(Ids.researcherIdUnresolved(Fx.scId).isLeft)
    assert(Ids.researcherIdUnresolved(W).isLeft)
  }

  // ---- ResolvedMention ------------------------------------------------------------------------

  test("ResolvedMention: a non-accepted outcome with a shared researcher id is rejected") {
    for o <- List(
        ResolutionOutcome.Rejected,
        ResolutionOutcome.Unresolved,
        ResolutionOutcome.Conflict,
      )
    do
      expectErr(validate(resolvedAccepted.copy(outcome = o))) {
        case InvalidField("researcherId", _) =>
      }
  }
  test("ResolvedMention: the singleton id of another mention is rejected") {
    val other = Fx.get(Ids.researcherIdUnresolved(M1))
    expectErr(validate(resolvedSingleton.copy(researcherId = other))) {
      case InvalidField("researcherId", _) =>
    }
  }
  test("ResolvedMention: accepted may map to a shared researcher (orcid or sc1)") {
    assert(validate(resolvedAccepted).isRight)
    assert(validate(resolvedAccepted.copy(researcherId = Fx.orcidId)).isRight)
  }
  test("ResolvedMention: candidateResearcherId must be absent when accepted") {
    expectErr(validate(resolvedAccepted.copy(candidateResearcherId = Some(Fx.scId2)))) {
      case InvalidField("candidateResearcherId", _) =>
    }
  }
  test("ResolvedMention: candidateResearcherId must differ from researcherId and be valid") {
    expectErr(validate(resolvedSingleton.copy(candidateResearcherId = Some(unresolvedId)))) {
      case InvalidField("candidateResearcherId", _) =>
    }
    assert(validate(resolvedSingleton.copy(candidateResearcherId = Some("junk"))).isLeft)
  }
  test("ResolvedMention: bad mention id, confidence, policyVersion, schema, provenance") {
    assert(validate(resolvedAccepted.copy(mentionId = "mention:x")).isLeft)
    assert(validate(resolvedAccepted.copy(researcherId = "researcher:")).isLeft)
    assert(validate(resolvedAccepted.copy(confidence = 1.1)).isLeft)
    assert(validate(resolvedAccepted.copy(confidence = -0.1)).isLeft)
    assert(validate(resolvedAccepted.copy(policyVersion = "")).isLeft)
    assert(validate(resolvedAccepted.copy(schemaVersion = 2)).isLeft)
    assert(validate(resolvedAccepted.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("ResolvedMention.unresolved builds the singleton for each non-accepted outcome") {
    for o <- List(
        ResolutionOutcome.Rejected,
        ResolutionOutcome.Unresolved,
        ResolutionOutcome.Conflict,
      )
    do
      val r = right(ResolvedMention.unresolved(M0, o, 0.3, "er-v1", None, P))
      assertEquals(r.researcherId, unresolvedId)
      assertEquals(r.outcome, o)
  }
  test("ResolvedMention.unresolved with outcome accepted is rejected (no shared id to give)") {
    assert(ResolvedMention.unresolved(M0, ResolutionOutcome.Accepted, 0.9, "er-v1", None, P).isLeft)
  }
  test(
    "ResolvedMention.unresolved rejects a bad mention, confidence and a candidate equal to itself"
  ) {
    assert(
      ResolvedMention.unresolved("bad", ResolutionOutcome.Unresolved, 0.1, "er-v1", None, P).isLeft
    )
    assert(
      ResolvedMention.unresolved(M0, ResolutionOutcome.Unresolved, 1.5, "er-v1", None, P).isLeft
    )
    assert(
      ResolvedMention
        .unresolved(M0, ResolutionOutcome.Unresolved, 0.1, "er-v1", Some(unresolvedId), P)
        .isLeft
    )
  }
  test("ResolvedMention: unknown outcome on the wire is rejected") {
    val good = right(Wire.write(resolvedAccepted))
    assert(Wire.read[ResolvedMention](good.replace("\"accepted\"", "\"merged\"")).isLeft)
  }

  // ---- F7 records -----------------------------------------------------------------------------

  test("PersonAlias: aliasKey must be a non-blank token without whitespace") {
    for k <- List("", " ", "a b", "a\tb", "a\nb") do
      expectErr(validate(alias.copy(aliasKey = k))) { case InvalidField("aliasKey", _) => }
  }
  test("PersonAlias: names must be non-empty and each non-blank") {
    expectErr(validate(alias.copy(names = Nil))) { case InvalidField("names", _) => }
    assert(validate(alias.copy(names = List("A", " "))).isLeft)
    assert(validate(alias.copy(names = List(""))).isLeft)
  }
  test("PersonAlias: invalid orcid, schemaVersion and provenance are rejected") {
    assert(validate(alias.copy(orcids = List("0000-0002-1825-0098"))).isLeft)
    assert(validate(alias.copy(orcids = List("nope"))).isLeft)
    assert(validate(alias.copy(schemaVersion = 2)).isLeft)
    assert(validate(alias.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("PersonAlias: an invalid record is not written") {
    assert(Wire.write(alias.copy(names = Nil)).isLeft)
  }
  test("DuplicateRecord: blank sourceRecordId, winner, duplicate are rejected") {
    assert(validate(dup.copy(sourceRecordId = "")).isLeft)
    assert(validate(dup.copy(sourceRecordId = "  ")).isLeft)
    assert(validate(dup.copy(winner = "")).isLeft)
    assert(validate(dup.copy(duplicate = " ")).isLeft)
    assert(validate(dup.copy(schemaVersion = 2)).isLeft)
    assert(validate(dup.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("DuplicateRecord: every source type is accepted") {
    for s <- SourceType.values do assert(validate(dup.copy(source = s)).isRight, s.toString)
  }
  test("LargeTeam: invalid work id, teamSize, pairCount and threshold are rejected") {
    assert(validate(large.copy(workId = "work:")).isLeft)
    assert(validate(large.copy(workId = M0)).isLeft)
    assert(validate(large.copy(teamSize = 0)).isLeft)
    assert(validate(large.copy(pairCount = -1L)).isLeft)
    assert(validate(large.copy(threshold = 0)).isLeft)
    assert(validate(large.copy(schemaVersion = 2)).isLeft)
    assert(validate(large.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("LargeTeam: boundary values are valid") {
    assert(validate(large.copy(teamSize = 1, pairCount = 0L, threshold = 1)).isRight)
  }
  test("OversizeTeam: invalid work id, teamSize, pairCount and hardCap are rejected") {
    assert(validate(oversize.copy(workId = "work:")).isLeft)
    assert(validate(oversize.copy(teamSize = 0)).isLeft)
    assert(validate(oversize.copy(pairCount = -1L)).isLeft)
    assert(validate(oversize.copy(hardCap = 0)).isLeft)
    assert(validate(oversize.copy(schemaVersion = 2)).isLeft)
    assert(validate(oversize.copy(provenance = P.copy(codeVersion = ""))).isLeft)
  }
  test("OversizeTeam: boundary values are valid") {
    assert(validate(oversize.copy(teamSize = 1, pairCount = 0L, hardCap = 1)).isRight)
  }

  // ---- enums ----------------------------------------------------------------------------------

  private def enumWire[E](name: String, all: Seq[E], wire: E => String, expected: List[String])(
      using rw: WirePickle.ReadWriter[E]
  ): Unit =
    test(s"enum $name: wire names are $expected") {
      assertEquals(all.map(wire).toList, expected)
      all.foreach { e =>
        val w = WirePickle.write(e)
        assertEquals(w, "\"" + wire(e) + "\"")
        assertEquals(WirePickle.read[E](w), e)
        assertEquals(wire(e), wire(e).toLowerCase)
      }
    }
    test(s"enum $name: unknown, differently cased and empty values are rejected") {
      for bad <- List(
          "\"bogus\"",
          "\"\"",
          "\"" + expected.head.toUpperCase + "\"",
          "\" " + expected.head + "\"",
          "1",
        )
      do assert(scala.util.Try(WirePickle.read[E](bad)).isFailure, s"accepted $bad")
    }

  enumWire("EntityKind", EntityKind.values.toSeq, _.wire, List("researcher", "work"))
  enumWire("BlockKeyType", BlockKeyType.values.toSeq, _.wire, List("primary", "fallback"))
  enumWire(
    "MatchPath",
    MatchPath.values.toSeq,
    _.wire,
    List("exact_id", "deterministic", "probabilistic", "llm"),
  )
  enumWire(
    "ResolutionOutcome",
    ResolutionOutcome.values.toSeq,
    _.wire,
    List("accepted", "rejected", "unresolved", "conflict"),
  )
  enumWire(
    "AdjudicationOutcome",
    AdjudicationOutcome.values.toSeq,
    _.wire,
    List("accept", "reject", "abstain"),
  )
