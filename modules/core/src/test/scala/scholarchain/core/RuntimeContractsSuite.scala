package scholarchain.core

import java.nio.charset.StandardCharsets

import scala.util.Try

import ValidationError.*

/** Phase 09 runtime contracts (A1): frozen examples, every validation rule, enum wire values and
  * the `ModelClient` port records.
  */
class RuntimeContractsSuite extends ContractSupport:

  private def resource(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(name)
    assert(in != null, s"classpath resource $name is missing")
    try String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private def assertFrozen[A: Contract: WirePickle.Reader: WirePickle.Writer](name: String)(using
      munit.Location
  ): A =
    val json = resource(s"contracts/$name.example.json")
    assert(!json.endsWith("\n"), s"$name example is expected to have no trailing newline")
    val a = right(Wire.read[A](json))
    assertEquals(right(Wire.write(a)), json, s"$name example is not byte-identical after a rewrite")
    assertEquals(Wire.read[A](right(Wire.write(a))), Right(a))
    a

  private val plan: QueryPlanV2                 = assertFrozen[QueryPlanV2]("query-plan-v2")
  private val cand: ResolvedResearcherCandidate =
    assertFrozen[ResolvedResearcherCandidate]("resolved-researcher-candidate")
  private val claim: ClaimVerification = assertFrozen[ClaimVerification]("claim-verification")
  private val call: ModelCall          = assertFrozen[ModelCall]("model-call")

  private val idA = plan.subjectIds(0)
  private val idB = plan.subjectIds(1)
  private val hex = "39cab9a924e0e595e23e2c3e01bbe05575d3715af280555f15b0d5d498bacc0c"

  private def invalidField(e: Either[ValidationError, ?], name: String)(using
      munit.Location
  ): Unit =
    e match
      case Left(InvalidField(f, _)) => assertEquals(f, name)
      case other                    => fail(s"expected InvalidField($name) but got $other")

  private def missingField(e: Either[ValidationError, ?], name: String)(using
      munit.Location
  ): Unit =
    e match
      case Left(MissingField(f)) => assertEquals(f, name)
      case other                 => fail(s"expected MissingField($name) but got $other")

  private def planWith(f: QueryPlanV2 => QueryPlanV2): Either[ValidationError, QueryPlanV2] =
    validate(f(plan))

  // ---- frozen examples ------------------------------------------------------------------------

  test("QueryPlanV2: the frozen example holds the documented values") {
    assertEquals(
      plan.operations,
      List(QueryOperationV2.ShortestPath, QueryOperationV2.RankByMetric),
    )
    assertEquals(plan.metric, Some(CollaborationMetric.WeightedDegree))
    assertEquals(plan.window, Some(GraphWindow(2000, 2004)))
    assertEquals(plan.filters, QueryFilters(topic = Some("graph-mining")))
    assertEquals(plan.limits, QueryLimits(20, 4))
    assertEquals(plan.subjectCount, 2)
  }

  test("ResolvedResearcherCandidate, ClaimVerification and ModelCall: frozen values") {
    assertEquals(cand.confidence, 0.75)
    assertEquals(cand.evidenceUsed, List("mention-table", "identity-table"))
    assertEquals(claim.status, ClaimVerificationStatus.PartiallySupported)
    assertEquals(claim.reasonCodes, List("number_mismatch"))
    assertEquals(call.stage, ModelStage.QueryPlanner)
    assertEquals(call.retries, 1)
    assertEquals(call.outcome, ModelCallOutcome.Succeeded)
  }

  // ---- enum wire values -----------------------------------------------------------------------

  test("QueryOperationV2: eleven distinct snake_case wire values") {
    val wires = QueryOperationV2.values.toList.map(_.wire)
    assertEquals(wires.size, 11)
    assertEquals(wires.distinct.size, 11)
    assertEquals(
      wires.toSet,
      Set(
        "rank_researchers_by_metric",
        "max_team_size",
        "direct_coauthors",
        "shortest_path",
        "component_lookup",
        "community_comparison",
        "bridge_ranking",
        "temporal_comparison",
        "entity_filter",
        "semantic_explanation",
        "researcher_metrics_lookup",
      ),
    )
  }

  test("QueryOperationV2: every value round-trips; v1-only and unknown values are rejected") {
    QueryOperationV2.values.foreach { o =>
      assertEquals(WirePickle.read[QueryOperationV2](WirePickle.write(o)), o)
    }
    for bad <- List("search_works", "lookup_researcher", "list_coauthors", "run_cypher", "", "null")
    do
      assert(
        Try(WirePickle.read[QueryOperationV2](ujson.write(ujson.Str(bad)))).isFailure,
        s"'$bad' must be rejected",
      )
    assert(Try(WirePickle.read[QueryOperationV2]("null")).isFailure)
  }

  test("ClaimVerificationStatus: four lowercase wire values; uppercase and v1 names are rejected") {
    assertEquals(
      ClaimVerificationStatus.values.toList.map(_.wire),
      List("verified", "partially_supported", "unsupported", "conflicting"),
    )
    for bad <- List("VERIFIED", "PARTIALLY_SUPPORTED", "Unsupported", "supported", "contradicted")
    do
      assert(
        Try(WirePickle.read[ClaimVerificationStatus](ujson.write(ujson.Str(bad)))).isFailure,
        s"'$bad' must be rejected",
      )
  }

  test("ModelStage, ModelCallOutcome and ModelProvider wire values") {
    assertEquals(
      ModelStage.values.toList.map(_.wire),
      List("query_planner", "answer_synthesis", "claim_verification"),
    )
    assertEquals(
      ModelCallOutcome.values.toList.map(_.wire),
      List(
        "succeeded",
        "malformed_output",
        "throttled",
        "unavailable",
        "invalid_request",
        "budget_exceeded",
        "failed",
      ),
    )
    assertEquals(ModelProvider.values.toList.map(_.wire), List("bedrock", "anthropic", "fake"))
    assertEquals(ModelProvider.parse("fake"), Some(ModelProvider.Fake))
    assertEquals(ModelProvider.parse("FAKE"), None)
    assertEquals(ModelProvider.parse("openai"), None)
  }

  // ---- QueryPlanV2 rules ----------------------------------------------------------------------

  test("QueryPlanV2: queryId, question and schema version are checked") {
    assert(planWith(_.copy(queryId = "q-1")).isLeft)
    assert(planWith(_.copy(question = "   ")).isLeft)
    invalidField(planWith(_.copy(question = "a" * 2001)), "question")
    invalidField(planWith(_.copy(question = "a\u0000b")), "question")
    assert(planWith(_.copy(schemaVersion = 2)).isLeft)
  }

  test("QueryPlanV2: operations are 1..6 and distinct") {
    assert(planWith(_.copy(operations = Nil)).isLeft)
    invalidField(
      planWith(
        _.copy(operations = List(QueryOperationV2.MaxTeamSize, QueryOperationV2.MaxTeamSize))
      ),
      "operations",
    )
    val seven = List(
      QueryOperationV2.MaxTeamSize,
      QueryOperationV2.BridgeRanking,
      QueryOperationV2.SemanticExplanation,
      QueryOperationV2.ShortestPath,
      QueryOperationV2.RankByMetric,
      QueryOperationV2.ResearcherMetricsLookup,
      QueryOperationV2.CommunityComparison,
    )
    invalidField(planWith(_.copy(operations = seven)), "operations")
  }

  test("QueryPlanV2: names and subject IDs are bounded, distinct and the same size") {
    invalidField(
      planWith(_.copy(researcherNames = List("A", "A"), subjectIds = Nil)),
      "researcherNames",
    )
    invalidField(planWith(_.copy(researcherNames = List("A", "B", "C"))), "subjectIds")
    invalidField(planWith(_.copy(researcherNames = List("x" * 201, "B"))), "researcherNames")
    assert(planWith(_.copy(subjectIds = List("researcher:nope", idB))).isLeft)
    assert(planWith(_.copy(subjectIds = List(idA, idA), researcherNames = Nil)).isLeft)
    val eleven = (1 to 11).map(i => s"Name $i").toList
    invalidField(planWith(_.copy(researcherNames = eleven, subjectIds = Nil)), "researcherNames")
  }

  test("QueryPlanV2: windows, comparison window and limits") {
    assert(planWith(_.copy(window = Some(GraphWindow(2004, 2000)))).isLeft)
    invalidField(
      planWith(_.copy(comparisonWindow = Some(GraphWindow(2005, 2009)))),
      "comparisonWindow",
    )
    invalidField(planWith(_.copy(limits = QueryLimits(0, 4))), "limits.resultCount")
    invalidField(planWith(_.copy(limits = QueryLimits(101, 4))), "limits.resultCount")
    invalidField(planWith(_.copy(limits = QueryLimits(10, 7))), "limits.pathDepth")
    assert(planWith(_.copy(limits = QueryLimits(100, 6))).isRight)
  }

  test("QueryFilters: single bounded tokens only, never query text") {
    for bad <- List("graph mining", "a;b", "' OR 1=1", "", "x" * 65, "-lead", "a\nb") do
      invalidField(planWith(_.copy(filters = QueryFilters(topic = Some(bad)))), "filters.topic")
    invalidField(planWith(_.copy(filters = QueryFilters(venue = Some("a b")))), "filters.venue")
    invalidField(
      planWith(_.copy(filters = QueryFilters(institution = Some("MATCH (n) RETURN n")))),
      "filters.institution",
    )
    assert(planWith(_.copy(filters = QueryFilters(topic = Some("graph-mining.v2:x")))).isRight)
    assert(QueryFilters.none.isEmpty)
  }

  test("QueryPlanV2: per-operation subject rules") {
    def only(op: QueryOperationV2, names: List[String], ids: List[String] = Nil) =
      planWith(
        _.copy(
          operations = List(op),
          researcherNames = names,
          subjectIds = ids,
          metric = Some(CollaborationMetric.Degree),
          comparisonWindow = None,
        )
      )
    // exactly one subject
    assert(only(QueryOperationV2.DirectCoauthors, List("A")).isRight)
    assert(only(QueryOperationV2.DirectCoauthors, List("A", "B")).isLeft)
    assert(only(QueryOperationV2.DirectCoauthors, Nil).isLeft)
    assert(only(QueryOperationV2.ComponentLookup, List("A", "B")).isLeft)
    // exactly two subjects
    assert(only(QueryOperationV2.ShortestPath, List("A", "B")).isRight)
    assert(only(QueryOperationV2.ShortestPath, List("A")).isLeft)
    assert(only(QueryOperationV2.ShortestPath, Nil, List(idA, idB)).isRight)
    // at least two subjects
    assert(only(QueryOperationV2.CommunityComparison, List("A")).isLeft)
    assert(only(QueryOperationV2.CommunityComparison, List("A", "B", "C")).isRight)
    // at least one subject
    assert(only(QueryOperationV2.ResearcherMetricsLookup, Nil).isLeft)
    assert(only(QueryOperationV2.ResearcherMetricsLookup, List("A")).isRight)
    // no subject needed
    for op <- List(
        QueryOperationV2.MaxTeamSize,
        QueryOperationV2.BridgeRanking,
        QueryOperationV2.SemanticExplanation,
      )
    do assert(only(op, Nil).isRight, op.wire)
  }

  test("QueryPlanV2: rank_researchers_by_metric needs a rankable metric") {
    def rank(m: Option[CollaborationMetric]) =
      planWith(_.copy(operations = List(QueryOperationV2.RankByMetric), metric = m))
    missingField(rank(None), "metric")
    invalidField(rank(Some(CollaborationMetric.CommunityId)), "metric")
    assert(rank(Some(CollaborationMetric.BridgeScore)).isRight)
  }

  test(
    "QueryPlanV2: temporal_comparison needs two different windows; comparisonWindow only there"
  ) {
    def temporal(w: Option[GraphWindow], cw: Option[GraphWindow]) =
      planWith(
        _.copy(
          operations = List(QueryOperationV2.TemporalComparison),
          researcherNames = List("A"),
          subjectIds = Nil,
          window = w,
          comparisonWindow = cw,
        )
      )
    assert(temporal(Some(GraphWindow(2000, 2004)), Some(GraphWindow(2005, 2009))).isRight)
    missingField(temporal(None, Some(GraphWindow(2005, 2009))), "window")
    missingField(temporal(Some(GraphWindow(2000, 2004)), None), "comparisonWindow")
    invalidField(
      temporal(Some(GraphWindow(2000, 2004)), Some(GraphWindow(2000, 2004))),
      "comparisonWindow",
    )
    // comparison window on a non-temporal plan
    invalidField(
      planWith(
        _.copy(
          operations = List(QueryOperationV2.MaxTeamSize),
          researcherNames = Nil,
          subjectIds = Nil,
          comparisonWindow = Some(GraphWindow(2005, 2009)),
        )
      ),
      "comparisonWindow",
    )
  }

  test("QueryPlanV2: entity_filter needs a filter") {
    def filt(f: QueryFilters) =
      planWith(
        _.copy(
          operations = List(QueryOperationV2.EntityFilter),
          researcherNames = Nil,
          subjectIds = Nil,
          filters = f,
        )
      )
    invalidField(filt(QueryFilters.none), "filters")
    assert(filt(QueryFilters(venue = Some("aaai"))).isRight)
  }

  test("QueryPlanV2: an unknown operation name in JSON is rejected by the reader") {
    val bad = resource("contracts/query-plan-v2.example.json")
      .replace("\"shortest_path\"", "\"delete_everything\"")
    assert(Wire.read[QueryPlanV2](bad).isLeft)
    val v1 = resource("contracts/query-plan-v2.example.json")
      .replace("\"shortest_path\"", "\"search_works\"")
    assert(Wire.read[QueryPlanV2](v1).isLeft)
  }

  // ---- ResolvedResearcherCandidate ------------------------------------------------------------

  test("ResolvedResearcherCandidate: confidence is in [0,1]; names and lists are bounded") {
    assert(validate(cand.copy(confidence = 1.0)).isRight)
    assert(validate(cand.copy(confidence = 0.0)).isRight)
    assert(validate(cand.copy(confidence = 1.01)).isLeft)
    assert(validate(cand.copy(confidence = -0.1)).isLeft)
    assert(validate(cand.copy(confidence = Double.NaN)).isLeft)
    assert(validate(cand.copy(displayName = " ")).isLeft)
    invalidField(validate(cand.copy(displayName = "n" * 201)), "displayName")
    assert(validate(cand.copy(researcherId = "researcher:bad")).isLeft)
  }

  test("ResolvedResearcherCandidate: evidenceUsed 1..20 distinct tokens; sourceIds distinct refs") {
    assert(validate(cand.copy(evidenceUsed = Nil)).isLeft)
    assert(validate(cand.copy(evidenceUsed = List("a", "a"))).isLeft)
    assert(validate(cand.copy(evidenceUsed = List("has space"))).isLeft)
    assert(validate(cand.copy(evidenceUsed = (1 to 21).map(i => s"e$i").toList)).isLeft)
    assert(validate(cand.copy(evidenceUsed = (1 to 20).map(i => s"e$i").toList)).isRight)
    assert(validate(cand.copy(sourceIds = Nil)).isRight)
    assert(validate(cand.copy(sourceIds = List(s"mention:$hex", s"mention:$hex"))).isLeft)
    assert(validate(cand.copy(sourceIds = (1 to 21).map(i => s"mention:m$i").toList)).isLeft)
  }

  // ---- ClaimVerification ----------------------------------------------------------------------

  test("ClaimVerification: verified needs checked evidence IDs, the others need reason codes") {
    val ev = claim.checkedEvidenceIds
    invalidField(
      validate(claim.copy(status = ClaimVerificationStatus.Verified, checkedEvidenceIds = Nil)),
      "checkedEvidenceIds",
    )
    assert(
      validate(claim.copy(status = ClaimVerificationStatus.Verified, reasonCodes = Nil)).isRight
    )
    for s <- List(
        ClaimVerificationStatus.PartiallySupported,
        ClaimVerificationStatus.Unsupported,
        ClaimVerificationStatus.Conflicting,
      )
    do
      invalidField(validate(claim.copy(status = s, reasonCodes = Nil)), "reasonCodes")
      assert(validate(claim.copy(status = s, checkedEvidenceIds = Nil)).isRight, s.wire)
    assertEquals(ev.size, 1)
  }

  test("ClaimVerification: IDs and reason codes are well-formed, distinct and bounded") {
    assert(validate(claim.copy(claimId = "c-1")).isLeft)
    assert(validate(claim.copy(checkedEvidenceIds = List("evidence:nothex"))).isLeft)
    val e = claim.checkedEvidenceIds.head
    assert(validate(claim.copy(checkedEvidenceIds = List(e, e))).isLeft)
    for bad <- List("Number Mismatch", "1abc", "NUMBER", "a-b", "") do
      assert(validate(claim.copy(reasonCodes = List(bad))).isLeft, s"'$bad'")
    assert(validate(claim.copy(reasonCodes = List("a", "a"))).isLeft)
    assert(validate(claim.copy(reasonCodes = (1 to 21).map(i => s"r$i").toList)).isLeft)
    assert(validate(claim.copy(reasonCodes = (1 to 20).map(i => s"r$i").toList)).isRight)
  }

  test("ClaimVerification: uppercase wire status in JSON is rejected") {
    val up = resource("contracts/claim-verification.example.json")
      .replace("\"partially_supported\"", "\"PARTIALLY_SUPPORTED\"")
    assert(Wire.read[ClaimVerification](up).isLeft)
  }

  // ---- ModelCall ------------------------------------------------------------------------------

  test("ModelCall: retries are 0..2") {
    assert(validate(call.copy(retries = 0)).isRight)
    assert(validate(call.copy(retries = 2)).isRight)
    invalidField(validate(call.copy(retries = 3)), "retries")
    invalidField(validate(call.copy(retries = -1)), "retries")
    assertEquals(ModelCall.MaxRetries, 2)
  }

  test("ModelCall: succeeded requires an output hash; failures may omit it") {
    invalidField(validate(call.copy(outputHash = None)), "outputHash")
    assert(
      validate(
        call.copy(outputHash = None, outcome = ModelCallOutcome.MalformedOutput)
      ).isRight
    )
    assert(validate(call.copy(outputHash = Some("xyz"))).isLeft)
    assert(validate(call.copy(inputHash = "short")).isLeft)
  }

  test("ModelCall: counts and latency are non-negative; tokens are optional; IDs are tokens") {
    assert(validate(call.copy(inputTokens = Some(-1))).isLeft)
    assert(validate(call.copy(outputTokens = Some(-1))).isLeft)
    assert(validate(call.copy(inputTokens = None, outputTokens = None)).isRight)
    invalidField(validate(call.copy(latencyMillis = -1L)), "latencyMillis")
    assert(validate(call.copy(promptId = "has space")).isLeft)
    assert(validate(call.copy(modelId = "m" * 129)).isLeft)
    assert(validate(call.copy(queryId = "x")).isLeft)
  }

  // ---- ModelPorts -----------------------------------------------------------------------------

  private def seg(k: ModelSegmentKind, label: String = "task", text: String = "text") =
    ModelSegment(k, label, text)

  private val req = ModelRequest(
    "query-planner",
    "v1",
    "query-planner",
    "v1",
    """{"type":"object"}""",
    List(seg(ModelSegmentKind.Instruction, "system_instructions")),
    List(seg(ModelSegmentKind.Data, "user_question", "who?")),
    512,
  )

  test("ModelRequest.validate: a well-formed request passes") {
    assertEquals(ModelRequest.validate(req), Right(req))
  }

  test("ModelRequest.validate: Data segments are rejected in system, allowed in user") {
    val bad = req.copy(system = List(seg(ModelSegmentKind.Data, "retrieved_evidence", "abstract")))
    invalidField(ModelRequest.validate(bad), "system")
    assert(ModelRequest.validate(req.copy(user = List(seg(ModelSegmentKind.Data)))).isRight)
  }

  test("ModelRequest.validate: empty lists, bad labels, blank text, schema and token cap") {
    assert(ModelRequest.validate(req.copy(system = Nil)).isLeft)
    assert(ModelRequest.validate(req.copy(user = Nil)).isLeft)
    assert(
      ModelRequest.validate(req.copy(user = List(seg(ModelSegmentKind.Data, "bad label")))).isLeft
    )
    assert(
      ModelRequest.validate(req.copy(user = List(seg(ModelSegmentKind.Data, "x", "  ")))).isLeft
    )
    assert(ModelRequest.validate(req.copy(jsonSchema = "[1]")).isLeft)
    assert(ModelRequest.validate(req.copy(jsonSchema = "not json")).isLeft)
    invalidField(ModelRequest.validate(req.copy(maxOutputTokens = 0)), "maxOutputTokens")
    invalidField(ModelRequest.validate(req.copy(maxOutputTokens = 8193)), "maxOutputTokens")
    assert(ModelRequest.validate(req.copy(maxOutputTokens = 8192)).isRight)
    assert(ModelRequest.validate(req.copy(promptId = "bad id")).isLeft)
    val many = List.fill(65)(seg(ModelSegmentKind.Data))
    assert(ModelRequest.validate(req.copy(user = many)).isLeft)
  }

  test("ModelError: retryable only for Throttled, Unavailable and MalformedOutput") {
    assert(ModelError.Throttled("x").retryable)
    assert(ModelError.Unavailable("x").retryable)
    assert(ModelError.MalformedOutput("x").retryable)
    assert(!ModelError.InvalidRequest("x").retryable)
    assert(!ModelError.BudgetExceeded("x").retryable)
    assert(!ModelError.Failed("x").retryable)
  }

  test("ModelError: outcome, code and message carry only the fixed reason") {
    assertEquals(ModelError.MalformedOutput("r").outcome, ModelCallOutcome.MalformedOutput)
    assertEquals(ModelError.BudgetExceeded("r").outcome, ModelCallOutcome.BudgetExceeded)
    assertEquals(ModelError.InvalidRequest("r").code, "invalid-request")
    assertEquals(ModelError.Throttled("slow down").message, "model error (throttled): slow down")
  }
