package scholarchain.core

import ValidationError.*
import Fx.*

class RecordValidationContractSuite extends ContractSupport:

  private val otherEv = right(Ids.evidenceId(EvidenceType.Metric, "work:dblp:x", "v1", hello))

  // ---- confidence ------------------------------------------------------------------------------

  test("confidence: bounds 0.0 and 1.0 are valid on CanonicalResearcher") {
    List(0.0, 1.0).foreach(c =>
      assert(validate(researcher.copy(matchConfidence = c)).isRight, s"$c")
    )
  }

  test("confidence: 1.01, -0.01 and NaN are rejected on every confidence-carrying record") {
    List(1.01, -0.01, Double.NaN).foreach { c =>
      expectErr(validate(researcher.copy(matchConfidence = c))) {
        case InvalidConfidence("matchConfidence", _) => ()
      }
      expectErr(validate(authorship.copy(identityConfidence = c))) {
        case InvalidConfidence("identityConfidence", _) => ()
      }
      expectErr(validate(decision.copy(confidence = c))) {
        case InvalidConfidence("confidence", _) => ()
      }
      expectErr(validate(claim.copy(confidence = c))) { case InvalidConfidence("confidence", _) =>
        ()
      }
    }
  }

  // ---- identity uncertainty --------------------------------------------------------------------

  test("uncertain identity stays uncertain through validation and a wire round trip") {
    val back = right(Wire.read[CanonicalResearcher](right(Wire.write(researcher))))
    assertEquals(back.matchOutcome, MatchOutcome.Uncertain)
    assertEquals(back.matchConfidence, 0.62)
  }

  test("orcid-namespaced researcher id must agree with the orcid field") {
    expectErr(validate(researcherOrcid.copy(orcid = None))) { case InvalidField("orcid", _) => () }
    expectErr(validate(researcherOrcid.copy(orcid = Some("0000-0001-5109-3700")))) {
      case InvalidField("orcid", _) => ()
    }
    assert(validate(researcherOrcid).isRight)
  }

  test("a stored orcid must be canonical") {
    expectErr(validate(researcher.copy(orcid = Some("0000-0002-1694-233x")))) {
      case InvalidField("orcid", _) => ()
    }
    expectErr(validate(researcher.copy(orcid = Some("0000-0002-1825-0098")))) {
      case InvalidOrcid(_, _) => ()
    }
  }

  // ---- works, mentions ---------------------------------------------------------------------------

  test("work: doi must be stored normalized and well-formed") {
    expectErr(validate(work.copy(doi = Some("10.1000/ABC.1")))) { case InvalidField("doi", _) =>
      ()
    }
    expectErr(validate(work.copy(doi = Some("bad")))) { case InvalidDoi("bad", _) => () }
  }

  test("work: year, title, work id and venue id are checked") {
    assertEquals(validate(work.copy(year = 99)), Left(InvalidYear(99)))
    expectErr(validate(work.copy(title = "  "))) { case InvalidField("title", _) => () }
    expectErr(validate(work.copy(workId = "work:arxiv:1"))) { case MalformedId("work", _) => () }
    expectErr(validate(work.copy(venueId = Some("venue-1")))) { case MalformedId("venue", _) => () }
  }

  test("author mention: mentionId must be derived from work and position; position >= 0") {
    expectErr(validate(mention.copy(position = 1))) { case InvalidField("mentionId", _) => () }
    expectErr(validate(mention.copy(mentionId = "mention:" + "0" * 64))) {
      case InvalidField("mentionId", _) => ()
    }
    expectErr(validate(mention.copy(position = -1))) { case InvalidField("position", _) => () }
  }

  // ---- coauthor edge ------------------------------------------------------------------------------

  test("coauthor edge: wrong edge id, work count mismatch, empty and duplicate supporting works") {
    expectErr(validate(coauthorEdge.copy(edgeId = "edge:coauthor:" + "0" * 64))) {
      case InvalidField("edgeId", _) => ()
    }
    expectErr(validate(coauthorEdge.copy(workCount = 2))) { case InvalidField("workCount", _) =>
      ()
    }
    expectErr(validate(coauthorEdge.copy(supportingWorkIds = Nil, workCount = 0))) {
      case InvalidField("supportingWorkIds", _) => ()
    }
    expectErr(
      validate(coauthorEdge.copy(supportingWorkIds = List(workId, workId), workCount = 2))
    ) { case InvalidField("supportingWorkIds", _) =>
      ()
    }
  }

  test("coauthor edge: an inverted window is InvalidWindow") {
    assertEquals(
      validate(coauthorEdge.copy(window = GraphWindow(2005, 2000))),
      Left(InvalidWindow(2005, 2000)),
    )
  }

  // ---- metrics, matches, graph, evidence -----------------------------------------------------------

  test("metrics: empty, NaN and duplicate metrics are rejected") {
    expectErr(validate(metrics.copy(metrics = Nil))) { case InvalidField("metrics", _) => () }
    expectErr(
      validate(metrics.copy(metrics = List(MetricValue(CollaborationMetric.Degree, Double.NaN))))
    ) { case InvalidField("metrics.value", _) =>
      ()
    }
    expectErr(
      validate(
        metrics.copy(metrics =
          List(
            MetricValue(CollaborationMetric.Degree, 1),
            MetricValue(CollaborationMetric.Degree, 2),
          )
        )
      )
    ) { case InvalidField("metrics", _) => () }
  }

  test("match decision: identical sides are a self-pair") {
    expectErr(validate(decision.copy(rightId = decision.leftId))) {
      case InvalidPair(_, _, "self-pair") => ()
    }
  }

  test("graph edge: undirected endpoints must be ordered, directed may be swapped") {
    val swapped = gedge.copy(sourceId = scId, targetId = orcidId)
    expectErr(validate(swapped)) { case InvalidPair(_, _, _) => () }
    val directedId = right(Ids.graphEdgeId("coauthor", scId, orcidId, true, "p1", List(evId)))
    val directed   = swapped.copy(directed = true, edgeId = directedId)
    assertEquals(validate(directed), Right(directed))
  }

  test("graph edge: empty evidence and a wrong edge id are rejected") {
    expectErr(validate(gedge.copy(evidenceIds = Nil))) { case InvalidField("evidenceIds", _) => () }
    expectErr(validate(gedge.copy(edgeId = "gedge:" + "0" * 64))) {
      case InvalidField("edgeId", _) => ()
    }
  }

  test("evidence item: text must hash to contentHash, id must be derived") {
    expectErr(validate(evidence.copy(text = Some("world")))) {
      case InvalidField("contentHash", _) => ()
    }
    expectErr(validate(evidence.copy(evidenceId = otherEv))) { case InvalidField("evidenceId", _) =>
      ()
    }
  }

  test("evidence bundle: duplicate evidence ids are rejected") {
    expectErr(validate(bundle.copy(items = List(evidence, evidence)))) {
      case InvalidField("items.evidenceId", _) => ()
    }
  }

  // ---- venue, institution, topic ---------------------------------------------------------------------

  test("institution country must be uppercase alpha-2") {
    expectErr(validate(institution.copy(country = Some("us")))) { case InvalidField("country", _) =>
      ()
    }
    assert(validate(institution.copy(country = Some("US"))).isRight)
  }

  test("blank names are rejected for venue, institution and topic") {
    expectErr(validate(venue.copy(name = " "))) { case InvalidField("name", _) => () }
    expectErr(validate(institution.copy(name = ""))) { case InvalidField("name", _) => () }
    expectErr(validate(topic.copy(label = " "))) { case InvalidField("label", _) => () }
  }

  // ---- query plans -------------------------------------------------------------------------------------

  test("query plan: subject cardinality per operation") {
    val two =
      plan.copy(operation = QueryOperation.CollaborationPath, subjectIds = List(scId, orcidId))
    assertEquals(validate(two), Right(two))
    expectErr(validate(two.copy(subjectIds = List(scId)))) { case InvalidField("subjectIds", _) =>
      ()
    }
    expectErr(validate(two.copy(subjectIds = List(scId, scId)))) {
      case InvalidField("subjectIds", _) => ()
    }
    expectErr(validate(plan.copy(subjectIds = List(scId, orcidId)))) {
      case InvalidField("subjectIds", _) => ()
    }
    expectErr(validate(plan.copy(operation = QueryOperation.LookupResearcher, subjectIds = Nil))) {
      case InvalidField("subjectIds", _) => ()
    }
  }

  test("query plan: trend needs a window, limit must be positive") {
    assertEquals(validate(planTrend), Right(planTrend))
    assertEquals(validate(planTrend.copy(window = None)), Left(MissingField("window")))
    expectErr(validate(plan.copy(limit = 0))) { case InvalidField("limit", _) => () }
  }

  // ---- answers --------------------------------------------------------------------------------------------

  test("claims: supported and partially supported need evidence, unsupported does not") {
    expectErr(validate(claim.copy(evidenceIds = Nil))) { case InvalidField("evidenceIds", _) => () }
    expectErr(
      validate(claim.copy(evidenceIds = Nil, status = VerificationStatus.PartiallySupported))
    ) { case InvalidField("evidenceIds", _) =>
      ()
    }
    val unsupported = claim.copy(evidenceIds = Nil, status = VerificationStatus.Unsupported)
    assertEquals(validate(unsupported), Right(unsupported))
  }

  test("answer: claims may only cite evidence in the bundle") {
    assertEquals(validate(response), Right(response))
    val bad = response.copy(claims = List(claim.copy(evidenceIds = List(otherEv))))
    expectErr(validate(bad)) { case InvalidField("claims.evidenceIds", _) => () }
  }

  test("answer: bundle query id must match, claim ids must be unique") {
    expectErr(validate(response.copy(bundle = bundle.copy(queryId = "query:other")))) {
      case InvalidField("bundle.queryId", _) => ()
    }
    expectErr(validate(response.copy(claims = List(claim, claim)))) {
      case InvalidField("claims.claimId", _) => ()
    }
  }
