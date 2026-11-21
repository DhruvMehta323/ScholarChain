package scholarchain.core

import ValidationError.*
import Fx.{get, prov, scId, scId2, window, workId}

/** Phase 06 analytics records: validation rejections and JSON round trips. */
class AnalyticsRecordsContractSuite extends ContractSupport:

  private val scId3: String = "researcher:sc1:" + "3" * 64
  private val m1: String    = get(Ids.mentionId(workId, 1))
  private val m2: String    = get(Ids.mentionId(workId, 2))
  private val (mLo, mHi)    = if m1 < m2 then (m1, m2) else (m2, m1)
  private val runId: String = get(Ids.runId("spark-analytics", "dev", 1, Map.empty, Nil))
  private val e12: String   = get(Ids.coauthorEdgeId(scId, scId2, window, "p1"))
  private val e23: String   = get(Ids.coauthorEdgeId(scId2, scId3, window, "p1"))

  private val component = ComponentRecord(1, "component:2000-2004:" + scId, 3L, window, prov)
  private val community = CommunityRecord(
    1,
    "community:2000-2004:" + scId,
    4L,
    "label_propagation.v1",
    Map("maxIterations" -> "20"),
    window,
    prov,
  )
  private val bridge = BridgeRecord(
    1,
    scId,
    BridgeMeasure.ParticipationCoefficient,
    0.5,
    Map("communityMethod" -> "label_propagation.v1"),
    window,
    prov,
  )
  private val path = PathEvidence(
    1,
    scId,
    scId3,
    List(scId, scId2, scId3),
    List(e12, e23),
    List(List(workId), List(workId, "work:dblp:conf/x/Y01")),
    2,
    window,
    prov,
  )
  private val text    = "Researcher in window 2000-2004: 3 works."
  private val summary =
    SemanticSummary(
      1,
      scId,
      "researcher_profile",
      Hashing.textHash(text),
      text,
      window,
      runId,
      prov,
    )
  private val cluster = IdentityCluster(
    1,
    scId,
    List(mLo, mHi),
    "dblp_person_exact",
    CannotLinkStatus.Clear,
    "match-policy.v1",
    prov,
  )

  private def roundTrip[A](a: A)(using Contract[A], WirePickle.ReadWriter[A]): Unit =
    val json = right(Wire.write(a))
    assertEquals(Wire.read[A](json), Right(a))

  // ---- valid records and round trips ----------------------------------------------------------

  test("valid fixtures of all six records pass their contracts") {
    right(validate(component)); right(validate(community)); right(validate(bridge))
    right(validate(path)); right(validate(summary)); right(validate(cluster))
  }

  test("JSON round trip: ComponentRecord, CommunityRecord, BridgeRecord") {
    roundTrip(component); roundTrip(community); roundTrip(bridge)
  }

  test("JSON round trip: PathEvidence, SemanticSummary, IdentityCluster") {
    roundTrip(path); roundTrip(summary); roundTrip(cluster)
  }

  test("wire names of the enums are lowercase and frozen") {
    assertEquals(
      BridgeMeasure.values.toList.map(_.wire),
      List(
        "cross_community_degree",
        "participation_coefficient",
        "distinct_communities",
        "approx_betweenness",
      ),
    )
    assertEquals(CannotLinkStatus.values.toList.map(_.wire), List("clear", "blocked"))
    assert(right(Wire.write(bridge)).contains("\"measure\":\"participation_coefficient\""))
    assert(right(Wire.write(cluster)).contains("\"cannotLinkStatus\":\"clear\""))
  }

  test("the all-time window is the explicit 1000-9999 window and validates") {
    assertEquals(GraphWindows.AllTime, GraphWindow(1000, 9999))
    assertEquals(GraphWindow.label(GraphWindows.AllTime), "1000-9999")
    right(validate(component.copy(window = GraphWindows.AllTime)))
  }

  // ---- ComponentRecord / CommunityRecord ------------------------------------------------------

  test("ComponentRecord rejects size 0 and a wrong id kind") {
    expectErr(validate(component.copy(size = 0L))) { case InvalidField("size", _) => () }
    expectErr(validate(component.copy(componentId = "community:x"))) { case MalformedId(_, _) =>
      ()
    }
  }

  test("ComponentRecord and CommunityRecord reject an invalid window and schema version") {
    expectErr(validate(component.copy(window = GraphWindow(2005, 2000)))) {
      case InvalidWindow(2005, 2000) => ()
    }
    expectErr(validate(community.copy(window = GraphWindow(2005, 2000)))) {
      case InvalidWindow(_, _) => ()
    }
    expectErr(validate(component.copy(schemaVersion = 99))) {
      case UnsupportedSchemaVersion(99, _) => ()
    }
  }

  test("CommunityRecord rejects size 0, a method with whitespace and a blank parameter key") {
    expectErr(validate(community.copy(size = 0L))) { case InvalidField("size", _) => () }
    expectErr(validate(community.copy(method = "label propagation"))) {
      case InvalidField("method", _) => ()
    }
    expectErr(validate(community.copy(parameters = Map(" " -> "1")))) { case _: ValidationError =>
      ()
    }
  }

  // ---- BridgeRecord ---------------------------------------------------------------------------

  test("BridgeRecord rejects negative, NaN and infinite values") {
    expectErr(validate(bridge.copy(value = -0.1))) { case InvalidField("value", _) => () }
    expectErr(validate(bridge.copy(value = Double.NaN))) { case _: ValidationError => () }
    expectErr(validate(bridge.copy(value = Double.PositiveInfinity))) { case _: ValidationError =>
      ()
    }
    right(validate(bridge.copy(value = 0.0)))
  }

  test("BridgeRecord rejects an invalid researcher id") {
    expectErr(validate(bridge.copy(researcherId = "researcher:nope"))) {
      case MalformedId("researcher", _) => ()
    }
  }

  // ---- PathEvidence ---------------------------------------------------------------------------

  test("PathEvidence rejects a length that is not hops - 1") {
    expectErr(validate(path.copy(length = 3))) { case InvalidField("length", _) => () }
  }

  test("PathEvidence rejects misaligned edgeIds and supportingWorkIds") {
    expectErr(validate(path.copy(edgeIds = List(e12)))) { case InvalidField("edgeIds", _) => () }
    expectErr(validate(path.copy(supportingWorkIds = List(List(workId))))) {
      case InvalidField("supportingWorkIds", _) => ()
    }
  }

  test("PathEvidence rejects an empty or duplicated work list for a hop") {
    expectErr(validate(path.copy(supportingWorkIds = List(List(workId), Nil)))) {
      case InvalidField("supportingWorkIds", _) => ()
    }
    expectErr(validate(path.copy(supportingWorkIds = List(List(workId, workId), List(workId))))) {
      case InvalidField("supportingWorkIds", _) => ()
    }
  }

  test("PathEvidence rejects hops not starting at start or not ending at end") {
    expectErr(validate(path.copy(start = scId2))) { case InvalidField("hops", _) => () }
    expectErr(validate(path.copy(end = scId2))) { case InvalidField("hops", _) => () }
  }

  test("PathEvidence rejects a repeated hop and a single-hop-list (length 0)") {
    val loop = path.copy(
      hops = List(scId, scId2, scId),
      end = scId,
      edgeIds = List(e12, e12),
    )
    expectErr(validate(loop)) { case _: ValidationError => () }
    val zero = PathEvidence(1, scId, scId, List(scId), Nil, Nil, 0, window, prov)
    expectErr(validate(zero)) { case _: ValidationError => () }
  }

  test("PathEvidence rejects a malformed edge id and a malformed work id") {
    expectErr(validate(path.copy(edgeIds = List(e12, "edge:bad")))) {
      case MalformedId("coauthor-edge", _) => ()
    }
    expectErr(validate(path.copy(supportingWorkIds = List(List(workId), List("nonsense"))))) {
      case MalformedId("work", _) => ()
    }
  }

  // ---- SemanticSummary ------------------------------------------------------------------------

  test("SemanticSummary rejects a textHash that does not match the text") {
    expectErr(validate(summary.copy(textHash = Hashing.textHash("other")))) {
      case InvalidField("textHash", _) => ()
    }
    expectErr(validate(summary.copy(text = text + "!"))) { case InvalidField("textHash", _) =>
      ()
    }
  }

  test("SemanticSummary rejects blank text, a bad document type and a bad run id") {
    val blank = summary.copy(text = " ", textHash = Hashing.textHash(" "))
    expectErr(validate(blank)) { case _: ValidationError => () }
    expectErr(validate(summary.copy(documentType = "researcher profile"))) {
      case InvalidField("documentType", _) => ()
    }
    expectErr(validate(summary.copy(runId = "run:xyz"))) { case MalformedId("run", _) => () }
  }

  // ---- IdentityCluster ------------------------------------------------------------------------

  test("IdentityCluster rejects empty, unsorted, duplicated and malformed member mentions") {
    expectErr(validate(cluster.copy(memberMentionIds = Nil))) { case _: ValidationError => () }
    expectErr(validate(cluster.copy(memberMentionIds = List(mHi, mLo)))) {
      case InvalidField("memberMentionIds", _) => ()
    }
    expectErr(validate(cluster.copy(memberMentionIds = List(mLo, mLo)))) {
      case InvalidField("memberMentionIds", _) => ()
    }
    expectErr(validate(cluster.copy(memberMentionIds = List("mention:short")))) {
      case MalformedId("mention", _) => ()
    }
  }

  test("IdentityCluster rejects a bad researcher id and a rule token with whitespace") {
    expectErr(validate(cluster.copy(researcherId = "x"))) { case MalformedId("researcher", _) =>
      ()
    }
    expectErr(validate(cluster.copy(ruleFired = "two words"))) {
      case InvalidField("ruleFired", _) => ()
    }
  }

  test("IdentityCluster accepts the blocked status") {
    right(validate(cluster.copy(cannotLinkStatus = CannotLinkStatus.Blocked)))
  }
