package scholarchain.core

import ValidationError.*
import Fx.*

class IdentifierContractSuite extends ContractSupport:

  private val frozenFallback =
    "work:sc1:e47f9aa91a38400533429727023433e563ea9fe297bcedd411a5da8cda030a61"
  private val frozenCoauthorEdge =
    "edge:coauthor:59eee2d5d118695a56734a85351ac03b0022d0fad894cc3f58163bda7648110b"
  private val frozenChunk =
    "chunk:3a026d3b88b1c71406df0ed7822d09922e544291e42ed5bd2d64bc0b7f9306af"
  private val frozenEvidence =
    "evidence:0fddb1806749fab8279de83503ae573847830ccb0a6caa2d9bc560fbaf70b3cc"
  private val frozenGraphEdge =
    "gedge:9fefcef1ff5f1944eaa0c205a2e7527192d3e00d6b90ed0411d6f809c63ce954"
  private val frozenRun =
    "run:32cbb7f70869f964fe3034a349bdbe0acc44e09cc6990dd2cae2d3694a3cda8b"
  private val frozenScId = scId

  // ---- work ids ----------------------------------------------------------------------------

  test("work id: dblp, openalex and crossref sources pass through under their namespace") {
    assertEquals(
      Ids.workIdFromSource("dblp", "conf/sigmod/Foo01"),
      Right("work:dblp:conf/sigmod/Foo01"),
    )
    assertEquals(Ids.workIdFromSource("openalex", "W123"), Right("work:openalex:W123"))
    assertEquals(Ids.workIdFromSource("crossref", "10.1000/x"), Right("work:crossref:10.1000/x"))
  }

  test("work id: doi namespace uses the normalized DOI") {
    assertEquals(
      Ids.workIdFromSource("doi", "https://doi.org/10.1000/ABC.1"),
      Right("work:doi:10.1000/abc.1"),
    )
  }

  test("work id: unknown namespace, empty or whitespace source, and bad doi are rejected") {
    assertEquals(Ids.workIdFromSource("arxiv", "1"), Left(MalformedId("work-namespace", "arxiv")))
    expectErr(Ids.workIdFromSource("dblp", "")) { case MalformedId("work", _) => () }
    expectErr(Ids.workIdFromSource("dblp", "a b")) { case MalformedId("work", _) => () }
    expectErr(Ids.workIdFromSource("doi", "notadoi")) { case InvalidDoi("notadoi", _) => () }
  }

  test("work id fallback: frozen value, normalization of the title, year sensitivity") {
    assertEquals(Ids.workIdFallback("  Graph  Mining ", 2001, "a"), Right(frozenFallback))
    assertEquals(Ids.workIdFallback("graph mining", 2001, "a"), Right(frozenFallback))
    assertNotEquals(Ids.workIdFallback("graph mining", 2002, "a"), Right(frozenFallback))
    assertNotEquals(Ids.workIdFallback("graph mining", 2001, "b"), Right(frozenFallback))
  }

  test("work id fallback: blank title is InvalidField, year 99 is InvalidYear(99)") {
    expectErr(Ids.workIdFallback("   ", 2001, "a")) { case InvalidField("work", _) => () }
    assertEquals(Ids.workIdFallback("t", 99, "a"), Left(InvalidYear(99)))
  }

  // ---- researcher ids ----------------------------------------------------------------------

  test("researcher id: orcid url form becomes the canonical orcid id") {
    assertEquals(
      Ids.researcherIdFromOrcid("https://orcid.org/0000-0002-1825-0097"),
      Right("researcher:orcid:0000-0002-1825-0097"),
    )
  }

  test("researcher id: orcid with a bad check digit is InvalidOrcid") {
    expectErr(Ids.researcherIdFromOrcid("0000-0002-1825-0098")) { case InvalidOrcid(_, _) => () }
  }

  test("researcher id: scholarchain id is frozen, deterministic and key-sensitive") {
    assertEquals(Ids.researcherIdScholarChain("cluster-1"), Right(frozenScId))
    assertEquals(
      Ids.researcherIdScholarChain("cluster-1"),
      Ids.researcherIdScholarChain("cluster-1"),
    )
    assertNotEquals(Ids.researcherIdScholarChain("cluster-2"), Right(frozenScId))
    expectErr(Ids.researcherIdScholarChain("  ")) { case InvalidField("identityKey", _) => () }
  }

  test("checkResearcherId accepts both forms and rejects malformed ones") {
    assertEquals(Ids.checkResearcherId(orcidId), Right(orcidId))
    assertEquals(Ids.checkResearcherId(scId), Right(scId))
    List("researcher:orcid:0000-0002-1825-0098", "researcher:sc1:XYZ", "person:1").foreach { bad =>
      assertEquals(Ids.checkResearcherId(bad), Left(MalformedId("researcher", bad)))
    }
  }

  // ---- coauthor edge -----------------------------------------------------------------------

  test("coauthor edge id: frozen value, independent of argument order") {
    assertEquals(Ids.coauthorEdgeId(orcidId, scId, window, "p1"), Right(frozenCoauthorEdge))
    assertEquals(Ids.coauthorEdgeId(scId, orcidId, window, "p1"), Right(frozenCoauthorEdge))
  }

  test("coauthor edge id: window and policy version change the id") {
    assertNotEquals(
      Ids.coauthorEdgeId(orcidId, scId, GraphWindow(2000, 2005), "p1"),
      Right(frozenCoauthorEdge),
    )
    assertNotEquals(Ids.coauthorEdgeId(orcidId, scId, window, "p2"), Right(frozenCoauthorEdge))
  }

  test("coauthor edge id: blank policy, self pair and inverted window are rejected") {
    expectErr(Ids.coauthorEdgeId(orcidId, scId, window, " ")) {
      case InvalidField("policyVersion", _) => ()
    }
    expectErr(Ids.coauthorEdgeId(orcidId, orcidId, window, "p1")) {
      case InvalidPair(_, _, "self-pair") => ()
    }
    assertEquals(
      Ids.coauthorEdgeId(orcidId, scId, GraphWindow(2005, 2000), "p1"),
      Left(InvalidWindow(2005, 2000)),
    )
  }

  // ---- chunk, evidence, mention ------------------------------------------------------------

  test("chunk id: frozen value and sensitivity to ordinal and text hash") {
    assertEquals(Ids.chunkId("work:dblp:x", "v1", "abstract", Some(0), hello), Right(frozenChunk))
    assertNotEquals(Ids.chunkId("work:dblp:x", "v1", "abstract", None, hello), Right(frozenChunk))
    assertNotEquals(
      Ids.chunkId("work:dblp:x", "v1", "abstract", Some(0), Hashing.textHash("hellp")),
      Right(frozenChunk),
    )
  }

  test("chunk id: non-hex text hash is MalformedId, negative ordinal is InvalidField") {
    expectErr(Ids.chunkId("work:dblp:x", "v1", "abstract", Some(0), "nothex")) {
      case MalformedId("text-hash", "nothex") => ()
    }
    expectErr(Ids.chunkId("work:dblp:x", "v1", "abstract", Some(-1), hello)) {
      case InvalidField("ordinal", _) => ()
    }
  }

  test("evidence id: frozen value, and the evidence type changes it") {
    assertEquals(
      Ids.evidenceId(EvidenceType.TextChunk, "work:dblp:x", "v1", hello),
      Right(frozenEvidence),
    )
    assertNotEquals(
      Ids.evidenceId(EvidenceType.Metric, "work:dblp:x", "v1", hello),
      Right(frozenEvidence),
    )
  }

  test("mention id: deterministic, position-sensitive, rejects negative position and bad work id") {
    assertEquals(Ids.mentionId(workId, 0), Ids.mentionId(workId, 0))
    assertNotEquals(Ids.mentionId(workId, 0), Ids.mentionId(workId, 1))
    expectErr(Ids.mentionId(workId, -1)) { case InvalidField("position", _) => () }
    expectErr(Ids.mentionId("work:arxiv:1", 0)) { case MalformedId("work", _) => () }
  }

  // ---- graph edge --------------------------------------------------------------------------

  private val a1 = "researcher:sc1:" + "1" * 64
  private val a2 = "researcher:sc1:" + "2" * 64
  private val e2 = right(Ids.evidenceId(EvidenceType.Metric, "work:dblp:x", "v1", hello))

  test("graph edge id: frozen value") {
    assertEquals(
      Ids.graphEdgeId("coauthor", a1, a2, false, "p1", List(frozenEvidence)),
      Right(frozenGraphEdge),
    )
  }

  test("graph edge id: endpoint order is irrelevant when undirected, relevant when directed") {
    assertEquals(
      Ids.graphEdgeId("coauthor", a2, a1, false, "p1", List(frozenEvidence)),
      Right(frozenGraphEdge),
    )
    assertNotEquals(
      Ids.graphEdgeId("coauthor", a1, a2, true, "p1", List(frozenEvidence)),
      Ids.graphEdgeId("coauthor", a2, a1, true, "p1", List(frozenEvidence)),
    )
  }

  test("graph edge id: evidence order and duplicates do not matter") {
    assertEquals(
      Ids.graphEdgeId("coauthor", a1, a2, false, "p1", List(frozenEvidence, e2)),
      Ids.graphEdgeId("coauthor", a1, a2, false, "p1", List(e2, frozenEvidence, frozenEvidence)),
    )
    assertNotEquals(
      Ids.graphEdgeId("coauthor", a1, a2, false, "p1", List(frozenEvidence, e2)),
      Ids.graphEdgeId("coauthor", a1, a2, false, "p1", List(frozenEvidence)),
    )
  }

  test("graph edge id: self loop, empty type and non-evidence ids are rejected") {
    expectErr(Ids.graphEdgeId("coauthor", a1, a1, false, "p1", List(frozenEvidence))) {
      case InvalidPair(_, _, _) => ()
    }
    expectErr(Ids.graphEdgeId("", a1, a2, false, "p1", List(frozenEvidence))) {
      case InvalidField("edgeType", _) => ()
    }
    expectErr(Ids.graphEdgeId("coauthor", a1, a2, false, "p1", List("work:dblp:x"))) {
      case MalformedId("evidence", "work:dblp:x") => ()
    }
  }

  // ---- hashing -----------------------------------------------------------------------------

  test("hash: field boundaries cannot collide") {
    assertNotEquals(Hashing.digest("t", Seq("ab", "c")), Hashing.digest("t", Seq("a", "bc")))
    assertNotEquals(Hashing.digest("t", Seq("a", "")), Hashing.digest("t", Seq("a")))
  }

  test("hash: textHash is frozen lowercase sha-256 hex") {
    assertEquals(
      Hashing.textHash("hello"),
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
    )
    assert(Hashing.isSha256Hex(Hashing.textHash("anything")))
    assert(!Hashing.isSha256Hex(Hashing.textHash("anything").toUpperCase))
  }

  // ---- run ids -----------------------------------------------------------------------------

  private val in1 = InputRef("dblp.xml", "2026-11-01", Some(hex64))
  private def build(
      stage: String = "parse",
      code: String = "0.1.0",
      attempt: Int = 1,
      params: Map[String, String] = Map("n" -> "1000"),
      inputs: List[InputRef] = List(in1),
      createdAt: Option[String] = None,
  ) = RunManifest.build(stage, code, attempt, params, inputs, createdAt)

  test("run id: frozen value") {
    assertEquals(build().map(_.runId), Right(frozenRun))
  }

  test("run id: independent of createdAt, parameter order and input order") {
    assertEquals(build(createdAt = Some("2026-01-01T00:00:00Z")).map(_.runId), Right(frozenRun))
    val in2 = InputRef("crossref.json", "v2", None)
    assertEquals(
      build(params = Map("a" -> "1", "b" -> "2"), inputs = List(in1, in2)).map(_.runId),
      build(params = Map("b" -> "2", "a" -> "1"), inputs = List(in2, in1)).map(_.runId),
    )
  }

  test("run id: attempt, code version, parameters and input checksum each change it") {
    assertNotEquals(build(attempt = 2).map(_.runId), Right(frozenRun))
    assertNotEquals(build(code = "0.1.1").map(_.runId), Right(frozenRun))
    assertNotEquals(build(params = Map("n" -> "1001")).map(_.runId), Right(frozenRun))
    assertNotEquals(
      build(inputs = List(in1.copy(checksum = Some("b" * 64)))).map(_.runId),
      Right(frozenRun),
    )
  }

  test("run id: attempt 0 is InvalidField") {
    expectErr(build(attempt = 0)) { case InvalidField("attempt", _) => () }
  }

  test("run manifest: a tampered runId fails validation") {
    val tampered = manifest.copy(runId = "run:" + "0" * 64)
    expectErr(validate(tampered)) { case InvalidField("runId", _) => () }
    assertEquals(validate(manifest), Right(manifest))
  }
