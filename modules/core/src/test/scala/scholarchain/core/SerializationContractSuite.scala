package scholarchain.core

import ValidationError.*
import Fx.*

class SerializationContractSuite extends ContractSupport:

  /** Frozen wire examples. `@P@` stands for the Provenance JSON. Every string was produced once by
    * running the codec (Wire.write) and then pinned; the work, researcher, coauthor edge and run
    * manifest strings equal those supplied in the domain-engineer specification.
    */
  private def sub(s: String): String = s.replace("@P@", provJson)

  private val jProvenance =
    """{"schemaVersion":1,"sourceType":"dblp","inputs":[{"inputId":"dblp.xml","version":"2026-11-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"codeVersion":"0.1.0","parameters":{"sample":"1000"},"retrievedAt":"2026-11-02T10:00:00Z"}"""
  private val jWorkRecord =
    """{"schemaVersion":1,"workId":"work:dblp:conf/sigmod/Foo01","title":"Graph Mining","year":2001,"doi":"10.1000/abc.1","topicIds":[],"provenance":@P@}"""
  private val jWorkFull =
    """{"schemaVersion":1,"workId":"work:dblp:conf/sigmod/Foo01","title":"Graph Mining","year":2001,"doi":"10.1000/abc.1","venueId":"venue:sigmod","topicIds":["topic:graph-mining"],"provenance":@P@}"""
  private val jResearcher =
    """{"schemaVersion":1,"researcherId":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5","displayName":"Ada Lovelace","matchConfidence":0.62,"matchOutcome":"uncertain","provenance":@P@}"""
  private val jResearcherOrcid =
    """{"schemaVersion":1,"researcherId":"researcher:orcid:0000-0002-1825-0097","displayName":"Ada Lovelace","orcid":"0000-0002-1825-0097","matchConfidence":1,"matchOutcome":"match","provenance":@P@}"""
  private val jCoauthorEdge =
    """{"schemaVersion":1,"edgeId":"edge:coauthor:59eee2d5d118695a56734a85351ac03b0022d0fad894cc3f58163bda7648110b","pair":{"first":"researcher:orcid:0000-0002-1825-0097","second":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5"},"window":{"startYear":2000,"endYear":2004},"policyVersion":"p1","workCount":1,"supportingWorkIds":["work:dblp:conf/sigmod/Foo01"],"provenance":@P@}"""
  private val jAuthorMention =
    """{"schemaVersion":1,"mentionId":"mention:9c94f43be1b3666192f3a2e24921493c6c7ed3dc01bad8cd906b97d2c98e1c3f","workId":"work:dblp:conf/sigmod/Foo01","position":0,"rawName":"A. Lovelace","orcid":"0000-0002-1825-0097","provenance":@P@}"""
  private val jAuthorshipRecord =
    """{"schemaVersion":1,"workId":"work:dblp:conf/sigmod/Foo01","researcherId":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5","mentionId":"mention:9c94f43be1b3666192f3a2e24921493c6c7ed3dc01bad8cd906b97d2c98e1c3f","position":0,"identityConfidence":0.62,"identityOutcome":"uncertain","provenance":@P@}"""
  private val jPublicationTeam =
    """{"schemaVersion":1,"workId":"work:dblp:conf/sigmod/Foo01","year":2001,"researcherIds":["researcher:orcid:0000-0002-1825-0097","researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5"],"provenance":@P@}"""
  private val jCoauthorPairCandidate =
    """{"schemaVersion":1,"workId":"work:dblp:conf/sigmod/Foo01","year":2001,"pair":{"first":"researcher:orcid:0000-0002-1825-0097","second":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5"},"provenance":@P@}"""
  private val jResearcherMetrics =
    """{"schemaVersion":1,"researcherId":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5","window":{"startYear":2000,"endYear":2004},"policyVersion":"p1","metrics":[{"metric":"degree","value":3},{"metric":"pagerank","value":0.15}],"provenance":@P@}"""
  private val jVenue =
    """{"schemaVersion":1,"venueId":"venue:sigmod","name":"SIGMOD","venueType":"conference","provenance":@P@}"""
  private val jInstitution =
    """{"schemaVersion":1,"institutionId":"institution:test-univ","name":"Test University","country":"US","provenance":@P@}"""
  private val jTopic =
    """{"schemaVersion":1,"topicId":"topic:graph-mining","label":"Graph Mining","provenance":@P@}"""
  private val jMatchDecision =
    """{"schemaVersion":1,"leftId":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5","rightId":"researcher:sc1:2222222222222222222222222222222222222222222222222222222222222222","outcome":"match","confidence":0.95,"policyVersion":"p1","features":{"nameSim":0.5},"provenance":@P@}"""
  private val jGraphNode =
    """{"schemaVersion":1,"nodeId":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5","nodeType":"researcher","label":"Ada Lovelace","properties":{"k":"v"},"provenance":@P@}"""
  private val jGraphEdge =
    """{"schemaVersion":1,"edgeId":"gedge:b1ba1c8a843656a4510545b98966251a8a6e7f87873438dd354ab5a2b6b91526","edgeType":"coauthor","sourceId":"researcher:orcid:0000-0002-1825-0097","targetId":"researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5","directed":false,"policyVersion":"p1","evidenceIds":["evidence:0fddb1806749fab8279de83503ae573847830ccb0a6caa2d9bc560fbaf70b3cc"],"provenance":@P@}"""
  private val jEvidenceItem =
    """{"schemaVersion":1,"evidenceId":"evidence:0fddb1806749fab8279de83503ae573847830ccb0a6caa2d9bc560fbaf70b3cc","evidenceType":"text_chunk","sourceEntityId":"work:dblp:x","sourceVersion":"v1","text":"hello","contentHash":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","score":0.5,"provenance":@P@}"""
  private val jEvidenceBundle =
    """{"schemaVersion":1,"queryId":"query:q1","items":[{"schemaVersion":1,"evidenceId":"evidence:0fddb1806749fab8279de83503ae573847830ccb0a6caa2d9bc560fbaf70b3cc","evidenceType":"text_chunk","sourceEntityId":"work:dblp:x","sourceVersion":"v1","text":"hello","contentHash":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","score":0.5,"provenance":@P@}],"provenance":@P@}"""
  private val jQueryPlan =
    """{"schemaVersion":1,"queryId":"query:q1","operation":"list_coauthors","question":"Who are the coauthors of Ada?","subjectIds":["researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5"],"limit":10,"provenance":@P@}"""
  private val jQueryPlanTrend =
    """{"schemaVersion":1,"queryId":"query:q1","operation":"collaboration_trend","question":"Who are the coauthors of Ada?","subjectIds":["researcher:sc1:d8348f7b791823c31da6d9e387925313709daeb9e680278a5f449bba7bd66ee5"],"window":{"startYear":2000,"endYear":2004},"metric":"degree","limit":10,"provenance":@P@}"""
  private val jAnswerClaim =
    """{"schemaVersion":1,"claimId":"claim:c1","text":"Ada has one coauthored work","evidenceIds":["evidence:0fddb1806749fab8279de83503ae573847830ccb0a6caa2d9bc560fbaf70b3cc"],"status":"supported","confidence":0.9,"provenance":@P@}"""
  private val jAnswerResponse =
    """{"schemaVersion":1,"queryId":"query:q1","answer":"One coauthored work found.","claims":[{"schemaVersion":1,"claimId":"claim:c1","text":"Ada has one coauthored work","evidenceIds":["evidence:0fddb1806749fab8279de83503ae573847830ccb0a6caa2d9bc560fbaf70b3cc"],"status":"supported","confidence":0.9,"provenance":@P@}],"bundle":{"schemaVersion":1,"queryId":"query:q1","items":[{"schemaVersion":1,"evidenceId":"evidence:0fddb1806749fab8279de83503ae573847830ccb0a6caa2d9bc560fbaf70b3cc","evidenceType":"text_chunk","sourceEntityId":"work:dblp:x","sourceVersion":"v1","text":"hello","contentHash":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","score":0.5,"provenance":@P@}],"provenance":@P@},"provenance":@P@}"""
  private val jRunManifest =
    """{"schemaVersion":1,"runId":"run:32cbb7f70869f964fe3034a349bdbe0acc44e09cc6990dd2cae2d3694a3cda8b","stage":"parse","codeVersion":"0.1.0","attempt":1,"parameters":{"n":"1000"},"inputs":[{"inputId":"dblp.xml","version":"2026-11-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}"""

  private def frozen[A](name: String, a: A, json: String)(using
      Contract[A],
      WirePickle.ReadWriter[A],
  ): Unit =
    test(s"frozen json: $name writes byte for byte and reads back") {
      assertEquals(Wire.write(a), Right(sub(json)))
      assertEquals(Wire.read[A](sub(json)), Right(a))
    }

  private def roundTrip[A](name: String, a: A)(using Contract[A], WirePickle.ReadWriter[A]): Unit =
    test(s"round trip: $name") {
      val json = right(Wire.write(a))
      assertEquals(Wire.read[A](json), Right(a))
    }

  frozen("Provenance", prov, jProvenance)
  frozen("WorkRecord", work, jWorkRecord)
  frozen("WorkFull", workFull, jWorkFull)
  frozen("Researcher", researcher, jResearcher)
  frozen("ResearcherOrcid", researcherOrcid, jResearcherOrcid)
  frozen("CoauthorEdge", coauthorEdge, jCoauthorEdge)
  frozen("AuthorMention", mention, jAuthorMention)
  frozen("AuthorshipRecord", authorship, jAuthorshipRecord)
  frozen("PublicationTeam", team, jPublicationTeam)
  frozen("CoauthorPairCandidate", candidate, jCoauthorPairCandidate)
  frozen("ResearcherMetrics", metrics, jResearcherMetrics)
  frozen("Venue", venue, jVenue)
  frozen("Institution", institution, jInstitution)
  frozen("Topic", topic, jTopic)
  frozen("MatchDecision", decision, jMatchDecision)
  frozen("GraphNode", node, jGraphNode)
  frozen("GraphEdge", gedge, jGraphEdge)
  frozen("EvidenceItem", evidence, jEvidenceItem)
  frozen("EvidenceBundle", bundle, jEvidenceBundle)
  frozen("QueryPlan", plan, jQueryPlan)
  frozen("QueryPlanTrend", planTrend, jQueryPlanTrend)
  frozen("AnswerClaim", claim, jAnswerClaim)
  frozen("AnswerResponse", response, jAnswerResponse)
  frozen("RunManifest", manifest, jRunManifest)

  roundTrip("Provenance", prov)
  roundTrip("Provenance with runId", prov.copy(runId = Some(manifest.runId)))
  roundTrip("WorkRecord None optionals", workBare)
  roundTrip("WorkRecord Some optionals", workFull)
  roundTrip("AuthorMention Some", mention)
  roundTrip("AuthorMention None", mentionBare)
  roundTrip("CanonicalResearcher uncertain", researcher)
  roundTrip("CanonicalResearcher orcid", researcherOrcid)
  roundTrip("AuthorshipRecord", authorship)
  roundTrip("PublicationTeam", team)
  roundTrip("CoauthorPairCandidate", candidate)
  roundTrip("CoauthorEdge", coauthorEdge)
  roundTrip("ResearcherMetrics", metrics)
  roundTrip("Venue Some", venue)
  roundTrip("Venue None", venueBare)
  roundTrip("Institution", institution)
  roundTrip("Topic", topic)
  roundTrip("MatchDecision", decision)
  roundTrip("GraphNode", node)
  roundTrip("GraphEdge", gedge)
  roundTrip("EvidenceItem Some", evidence)
  roundTrip("EvidenceItem None", evidenceBare)
  roundTrip("EvidenceBundle", bundle)
  roundTrip("QueryPlan None", plan)
  roundTrip("QueryPlan Some", planTrend)
  roundTrip("AnswerClaim", claim)
  roundTrip("AnswerResponse", response)
  roundTrip("RunManifest", manifest)
  roundTrip("RunManifest with createdAt", manifest.copy(createdAt = Some("2026-01-01T00:00:00Z")))

  test("enum wire names are frozen") {
    assertEquals(
      SourceType.values.toList.map(_.wire),
      List("dblp", "openalex", "crossref", "orcid", "scholarchain"),
    )
    assertEquals(MatchOutcome.values.toList.map(_.wire), List("match", "non_match", "uncertain"))
    assertEquals(
      EvidenceType.values.toList.map(_.wire),
      List(
        "work_record",
        "authorship",
        "coauthor_edge",
        "graph_path",
        "metric",
        "text_chunk",
        "researcher_profile",
      ),
    )
    assertEquals(
      CollaborationMetric.values.toList.map(_.wire),
      List(
        "degree",
        "weighted_degree",
        "betweenness",
        "pagerank",
        "clustering_coefficient",
        "bridge_score",
        "community_id",
      ),
    )
    assertEquals(
      QueryOperation.values.toList.map(_.wire),
      List(
        "lookup_researcher",
        "list_coauthors",
        "collaboration_path",
        "top_bridge_researchers",
        "collaboration_trend",
        "search_works",
      ),
    )
    assertEquals(
      VerificationStatus.values.toList.map(_.wire),
      List("supported", "partially_supported", "unsupported", "contradicted", "unverified"),
    )
  }

  private def workJson = sub(jWorkRecord)

  test("an unknown enum wire name is Malformed") {
    val bad = workJson.replace("\"sourceType\":\"dblp\"", "\"sourceType\":\"mongo\"")
    assert(bad != workJson)
    expectErr(Wire.read[WorkRecord](bad)) { case Malformed(_) => () }
  }

  test("optional: null and an absent key both read as None") {
    val withNull = workJson.replace("\"doi\":\"10.1000/abc.1\"", "\"doi\":null")
    val absent   = workJson.replace("\"doi\":\"10.1000/abc.1\",", "")
    assert(withNull != workJson && absent != workJson)
    assertEquals(Wire.read[WorkRecord](withNull), Right(workBare))
    assertEquals(Wire.read[WorkRecord](absent), Right(workBare))
  }

  test("optional: None is never written, Some is a plain value") {
    val none = right(Wire.write(workBare))
    assert(!none.contains("\"doi\""), none)
    val some = right(Wire.write(work))
    assert(some.contains("\"doi\":\"10.1000/abc.1\""), some)
    assert(!some.contains("\"doi\":["), some)
  }

  test("schema version: 2 is UnsupportedSchemaVersion(2, Set(1))") {
    assertEquals(
      Wire.read[WorkRecord](workJson.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
  }

  test("schema version: 0 and -1 are rejected") {
    List(0, -1).foreach { v =>
      assertEquals(
        Wire.read[WorkRecord](
          workJson.replaceFirst("\"schemaVersion\":1", s"\"schemaVersion\":$v")
        ),
        Left(UnsupportedSchemaVersion(v, Set(1))),
      )
    }
  }

  test("schema version: missing is MissingField, non-integers and arrays are Malformed") {
    assertEquals(
      Wire.read[WorkRecord](workJson.replaceFirst("\"schemaVersion\":1,", "")),
      Left(MissingField("schemaVersion")),
    )
    expectErr(
      Wire.read[WorkRecord](workJson.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":\"1\""))
    ) { case Malformed(_) =>
      ()
    }
    expectErr(
      Wire.read[WorkRecord](workJson.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1.5"))
    ) { case Malformed(_) =>
      ()
    }
    expectErr(Wire.read[WorkRecord]("[1]")) { case Malformed(_) => () }
  }

  test("schema version: a nested provenance.schemaVersion of 2 is rejected") {
    val bad = workJson.replace(
      provJson,
      provJson.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2"),
    )
    assert(bad != workJson)
    assertEquals(Wire.read[WorkRecord](bad), Left(UnsupportedSchemaVersion(2, Set(1))))
  }

  test("schema version: writing a record with schemaVersion 2 is refused") {
    assertEquals(
      Wire.write(work.copy(schemaVersion = 2)),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
  }

  test("missing provenance key is MissingField(provenance)") {
    val noProv = workJson.replace(",\"provenance\":" + provJson, "")
    assert(!noProv.contains("provenance"))
    assertEquals(Wire.read[WorkRecord](noProv), Left(MissingField("provenance")))
  }

  test("provenance contract: inputs, code version, checksum, retrievedAt and runId") {
    expectErr(validate(prov.copy(inputs = Nil))) { case InvalidField("provenance.inputs", _) => () }
    expectErr(validate(prov.copy(codeVersion = " "))) {
      case InvalidField("provenance.codeVersion", _) => ()
    }
    expectErr(validate(prov.copy(inputs = List(InputRef("x", "v", Some("ABC")))))) {
      case InvalidField("inputs.checksum", _) => ()
    }
    expectErr(validate(prov.copy(retrievedAt = Some("yesterday")))) {
      case InvalidField("provenance.retrievedAt", _) => ()
    }
    assertEquals(validate(prov.copy(runId = Some("run:xyz"))), Left(MalformedId("run", "run:xyz")))
  }

  test("a record with an invalid provenance is never written") {
    expectErr(Wire.write(work.copy(provenance = prov.copy(inputs = Nil)))) {
      case InvalidField("provenance.inputs", _) => ()
    }
  }

  test("invalid json text is Malformed and never throws") {
    expectErr(Wire.read[WorkRecord]("not json")) { case Malformed(_) => () }
  }
