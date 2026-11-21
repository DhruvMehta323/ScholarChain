package scholarchain.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import ValidationError.*
import Fx.{get, prov, scId, scId2}

/** Phase 06 repair 01: `CoauthorEdgeV2`, `ResearcherMetricsV2`, the frozen JSON examples,
  * `GraphEdgeTypes`, and the frozen v1 records (which must behave as before).
  */
class AnalyticsRecordsV2ContractSuite extends ContractSupport:

  // ---- helpers --------------------------------------------------------------------------------

  /** Independent SHA-256 digest (4-byte big-endian length prefix per field, tag first). */
  private def sha(tag: String, fields: Seq[String]): String =
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    (tag +: fields).foreach { f =>
      val b = f.getBytes(StandardCharsets.UTF_8)
      dos.writeInt(b.length)
      dos.write(b)
    }
    dos.flush()
    MessageDigest
      .getInstance("SHA-256")
      .digest(out.toByteArray)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def resource(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(name)
    assert(in != null, s"classpath resource $name is missing")
    try String(in.readAllBytes(), StandardCharsets.UTF_8).stripLineEnd
    finally in.close()

  private val Policy = "coauthor.v1"
  private val a      = scId2 // researcher:sc1:222...  (smaller)
  private val b      = scId  // researcher:sc1:d83...
  private val w1     = "work:dblp:conf/sigmod/Foo01"
  private val w2     = "work:dblp:conf/sigmod/Foo02"
  private val w3     = "work:dblp:conf/sigmod/Foo03"
  private val win    = GraphWindow(2000, 2004)
  private val tag    = "scholarchain.coauthor-works.v1"

  private def edgeIdFor(x: String, y: String, w: GraphWindow, p: String = Policy): String =
    get(Ids.coauthorEdgeId(x, y, w, p))

  /** Non-truncated edge: two joint works, representatives are the full set. */
  private val edge: CoauthorEdgeV2 =
    val id = edgeIdFor(a, b, win)
    CoauthorEdgeV2(
      1,
      id,
      a,
      b,
      2000,
      2004,
      Policy,
      2,
      2001,
      2003,
      List(w1, w2),
      sha(tag, List(w1, w2)),
      false,
      "coauthor-edge-works",
      id,
      "2000-2004",
      prov,
    )

  /** Truncated edge: three joint works, only two representatives; digest covers all three. */
  private val truncated: CoauthorEdgeV2 =
    edge.copy(
      jointPublicationCount = 3,
      representativeWorkIds = List(w1, w2),
      supportingWorksDigest = sha(tag, List(w1, w2, w3)),
      supportingWorksTruncated = true,
    )

  private def rejectsField(e: CoauthorEdgeV2, field: String)(using munit.Location): Unit =
    expectErr(validate(e)) { case InvalidField(f, _) => assertEquals(f, field) }

  // ---- CoauthorEdgeV2 -------------------------------------------------------------------------

  test("CoauthorEdgeV2: valid non-truncated and truncated edges pass") {
    right(validate(edge)); right(validate(truncated))
  }

  test("CoauthorEdgeV2: JSON round trip, truncated and not") {
    List(edge, truncated).foreach { e =>
      val json = right(Wire.write(e))
      assertEquals(Wire.read[CoauthorEdgeV2](json), Right(e))
    }
  }

  test("CoauthorEdgeV2: field names on the wire equal the Spark table columns") {
    val keys = ujson.read(right[String](Wire.write(edge))).obj.keySet
    assertEquals(
      keys.toSet,
      Set(
        "schemaVersion",
        "edgeId",
        "researcherA",
        "researcherB",
        "windowStart",
        "windowEnd",
        "policyVersion",
        "jointPublicationCount",
        "firstCollaborationYear",
        "lastCollaborationYear",
        "representativeWorkIds",
        "supportingWorksDigest",
        "supportingWorksTruncated",
        "evidenceTable",
        "evidenceKey",
        "evidencePartition",
        "provenance",
      ),
    )
  }

  test("CoauthorEdgeV2: the frozen example reads, validates and re-serializes byte-identically") {
    val json = resource("contracts/coauthor-edge-v2.example.json")
    val e    = right(Wire.read[CoauthorEdgeV2](json))
    assertEquals(e.jointPublicationCount, 7)
    assertEquals(e.representativeWorkIds.size, 5)
    assertEquals(e.supportingWorksTruncated, true)
    assertEquals(e.evidenceKey, e.edgeId)
    assertEquals(right(Wire.write(e)), json)
  }

  test("CoauthorEdgeV2: boundary, one joint work, one representative, not truncated") {
    val id = edgeIdFor(a, b, win)
    val e  = edge.copy(
      jointPublicationCount = 1,
      representativeWorkIds = List(w1),
      supportingWorksDigest = sha(tag, List(w1)),
      firstCollaborationYear = 2000,
      lastCollaborationYear = 2000,
      edgeId = id,
    )
    right(validate(e))
  }

  test("CoauthorEdgeV2: boundary, collaboration years equal to the window ends pass") {
    right(validate(edge.copy(firstCollaborationYear = 2000, lastCollaborationYear = 2004)))
  }

  test("CoauthorEdgeV2: boundary, all-time window 1000-9999 with the all-time partition label") {
    val allTime = GraphWindows.AllTime
    val id      = edgeIdFor(a, b, allTime)
    val e       = edge.copy(
      edgeId = id,
      evidenceKey = id,
      windowStart = 1000,
      windowEnd = 9999,
      evidencePartition = "1000-9999",
    )
    right(validate(e))
  }

  test("CoauthorEdgeV2: truncated digest is NOT checked against the representatives") {
    // a digest of other works is fine when truncated (only the shape is checked)
    right(validate(truncated.copy(supportingWorksDigest = sha(tag, List("x")))))
  }

  test("CoauthorEdgeV2: non-truncated digest must equal the digest of the representatives") {
    rejectsField(edge.copy(supportingWorksDigest = sha(tag, List(w1))), "supportingWorksDigest")
    // order of the representatives does not matter: the digest is over the code-point sorted set
    right(validate(edge.copy(representativeWorkIds = List(w2, w1))))
  }

  test("CoauthorEdgeV2: expectedDigest sorts by code point and equals the independent digest") {
    assertEquals(CoauthorEdgeV2.expectedDigest(List(w2, w1)), sha(tag, List(w1, w2)))
    assertEquals(CoauthorEdgeV2.WorksDigestTag, tag)
    assertEquals(CoauthorEdgeV2.EvidenceTable, "coauthor-edge-works")
  }

  test("CoauthorEdgeV2 rejects an unsupported schemaVersion") {
    expectErr(validate(edge.copy(schemaVersion = 99))) { case _: UnsupportedSchemaVersion => () }
  }

  test("CoauthorEdgeV2 rejects researcherA >= researcherB, a self-pair and an invalid ID") {
    expectErr(validate(edge.copy(researcherA = b, researcherB = a))) { case _: InvalidPair => () }
    expectErr(validate(edge.copy(researcherB = a))) { case _: InvalidPair => () }
    expectErr(validate(edge.copy(researcherA = "researcher:nope"))) { case _: MalformedId => () }
  }

  test("CoauthorEdgeV2 rejects an inverted or out-of-range window") {
    expectErr(validate(edge.copy(windowStart = 2005, windowEnd = 2000))) { case _: InvalidWindow =>
      ()
    }
    expectErr(validate(edge.copy(windowStart = 999))) { case _: InvalidYear => () }
    expectErr(validate(edge.copy(windowEnd = 10000))) { case _: InvalidYear => () }
  }

  test("CoauthorEdgeV2 rejects a blank or whitespace policyVersion") {
    rejectsField(edge.copy(policyVersion = ""), "policyVersion")
    rejectsField(edge.copy(policyVersion = "a b"), "policyVersion")
  }

  test("CoauthorEdgeV2 rejects an edgeId that is not derived from its inputs") {
    rejectsField(edge.copy(edgeId = edgeIdFor(a, b, GraphWindow(2005, 2009))), "edgeId")
    // changing the policy without re-deriving the ID is also a mismatch
    rejectsField(edge.copy(policyVersion = "coauthor.v2"), "edgeId")
  }

  test("CoauthorEdgeV2 rejects jointPublicationCount below one") {
    rejectsField(edge.copy(jointPublicationCount = 0), "jointPublicationCount")
    rejectsField(edge.copy(jointPublicationCount = -3), "jointPublicationCount")
  }

  test("CoauthorEdgeV2 rejects collaboration years out of order, out of range or outside window") {
    rejectsField(
      edge.copy(firstCollaborationYear = 2004, lastCollaborationYear = 2001),
      "firstCollaborationYear",
    )
    expectErr(validate(edge.copy(firstCollaborationYear = 999))) { case _: InvalidYear => () }
    rejectsField(edge.copy(firstCollaborationYear = 1999), "firstCollaborationYear")
    rejectsField(edge.copy(lastCollaborationYear = 2005), "firstCollaborationYear")
  }

  test("CoauthorEdgeV2 rejects empty, invalid, duplicate and too many representative work IDs") {
    rejectsField(edge.copy(representativeWorkIds = Nil), "representativeWorkIds")
    expectErr(validate(edge.copy(representativeWorkIds = List(w1, "not-a-work")))) {
      case _: MalformedId => ()
    }
    rejectsField(edge.copy(representativeWorkIds = List(w1, w1)), "representativeWorkIds")
    rejectsField(
      edge.copy(representativeWorkIds = List(w1, w2, w3)),
      "representativeWorkIds",
    ) // 3 representatives for 2 joint works
  }

  test("CoauthorEdgeV2 rejects a digest that is not 64 lowercase hex") {
    rejectsField(truncated.copy(supportingWorksDigest = "abc"), "supportingWorksDigest")
    rejectsField(
      truncated.copy(supportingWorksDigest = truncated.supportingWorksDigest.toUpperCase),
      "supportingWorksDigest",
    )
  }

  test("CoauthorEdgeV2 rejects a wrong truncated flag in both directions") {
    // 3 joint works, 2 representatives, flagged complete
    rejectsField(truncated.copy(supportingWorksTruncated = false), "supportingWorksTruncated")
    // 2 joint works, 2 representatives, flagged truncated
    rejectsField(edge.copy(supportingWorksTruncated = true), "supportingWorksTruncated")
  }

  test("CoauthorEdgeV2 rejects a wrong evidence pointer (table, key, partition)") {
    rejectsField(edge.copy(evidenceTable = "coauthor-edges"), "evidenceTable")
    rejectsField(edge.copy(evidenceKey = edgeIdFor(a, b, GraphWindow(2005, 2009))), "evidenceKey")
    rejectsField(edge.copy(evidencePartition = "2005-2009"), "evidencePartition")
    rejectsField(edge.copy(evidencePartition = "window=2000-2004"), "evidencePartition")
  }

  test("CoauthorEdgeV2 rejects an invalid provenance") {
    rejectsField(edge.copy(provenance = prov.copy(codeVersion = " ")), "provenance.codeVersion")
    expectErr(validate(edge.copy(provenance = prov.copy(inputs = Nil)))) {
      case InvalidField(f, _) => assertEquals(f, "provenance.inputs")
    }
  }

  test("CoauthorEdgeV2: Wire.write refuses an invalid edge and Wire.read refuses invalid JSON") {
    expectErr(Wire.write(edge.copy(jointPublicationCount = 0))) { case _: InvalidField => () }
    val json = right(Wire.write(edge))
    val bad  =
      json.replace("\"supportingWorksTruncated\":false", "\"supportingWorksTruncated\":true")
    assertNotEquals(bad, json)
    expectErr(Wire.read[CoauthorEdgeV2](bad)) { case InvalidField(f, _) =>
      assertEquals(f, "supportingWorksTruncated")
    }
  }

  // ---- ResearcherMetricsV2 --------------------------------------------------------------------

  private val comm = "community:2000-2004:" + b
  private val comp = "component:2000-2004:" + b

  /** P=3, C=4, T=12, A=12/3=4, M=6, W=7, coreness 3, ccd 2, pc 0.5, ct 3. */
  private val m: ResearcherMetricsV2 = ResearcherMetricsV2(
    1,
    b,
    2000,
    2004,
    Policy,
    3L,
    4L,
    12L,
    4.0,
    6,
    7L,
    1,
    List(w1),
    comm,
    comp,
    Some(3),
    2L,
    0.5,
    3,
    None,
    prov,
  )

  private def rejectsM(x: ResearcherMetricsV2, field: String)(using munit.Location): Unit =
    expectErr(validate(x)) { case InvalidField(f, _) => assertEquals(f, field) }

  test("ResearcherMetricsV2: a valid record passes and round-trips") {
    right(validate(m))
    val json = right(Wire.write(m))
    assertEquals(Wire.read[ResearcherMetricsV2](json), Right(m))
  }

  test("ResearcherMetricsV2: the frozen example reads, validates and equals canonical Wire.write") {
    val json  = resource("contracts/researcher-metrics-v2.example.json")
    val r     = right(Wire.read[ResearcherMetricsV2](json))
    val canon = right(Wire.write(r))
    // same JSON value (the example writes 4, the canonical writer 4.0)
    assertEquals(ujson.read(canon), ujson.read(json))
    // the canonical form is a fixed point
    assertEquals(right(Wire.write(right(Wire.read[ResearcherMetricsV2](canon)))), canon)
    assertEquals((r.publicationCount, r.coauthorCount, r.weightedDegree), (3L, 4L, 7L))
    assertEquals(r.coreness, Some(3))
    assertEquals(r.tieStrengthWeightedDegree, None)
    assertEquals((r.crossCommunityDegree, r.communitiesTouched), (2L, 3))
    assertEquals(r.participationCoefficient, 0.5)
  }

  test("ResearcherMetricsV2: field names on the wire") {
    val keys = ujson.read(right[String](Wire.write(m))).obj.keySet.toSet
    assertEquals(
      keys,
      Set(
        "schemaVersion",
        "researcherId",
        "windowStart",
        "windowEnd",
        "policyVersion",
        "publicationCount",
        "coauthorCount",
        "teamSizeTotal",
        "avgTeamSize",
        "maxTeamSize",
        "weightedDegree",
        "maxTeamWorkCount",
        "maxTeamWorkIds",
        "communityId",
        "componentId",
        "coreness",
        "crossCommunityDegree",
        "participationCoefficient",
        "communitiesTouched",
        "tieStrengthWeightedDegree",
        "provenance",
      ),
    )
  }

  test("ResearcherMetricsV2: boundary P_r = 0 (no publications, no coauthors, one community)") {
    val zero = m.copy(
      publicationCount = 0L,
      coauthorCount = 0L,
      teamSizeTotal = 0L,
      avgTeamSize = 0.0,
      maxTeamSize = 0,
      weightedDegree = 0L,
      maxTeamWorkCount = 0,
      maxTeamWorkIds = Nil,
      coreness = Some(0),
      crossCommunityDegree = 0L,
      participationCoefficient = 0.0,
      communitiesTouched = 1,
    )
    right(validate(zero))
    assertEquals(Wire.read[ResearcherMetricsV2](right(Wire.write(zero))), Right(zero))
  }

  test("ResearcherMetricsV2: boundary, coreness absent and coreness at both ends") {
    right(validate(m.copy(coreness = None)))
    right(validate(m.copy(coreness = Some(0))))
    right(validate(m.copy(coreness = Some(4)))) // == coauthorCount
    val json = right(Wire.write(m.copy(coreness = None)))
    assertEquals(ujson.read(json)("coreness"), ujson.Null)
    assertEquals(Wire.read[ResearcherMetricsV2](json).map(_.coreness), Right(None))
  }

  test("ResearcherMetricsV2: tieStrengthWeightedDegree Some, None and JSON null") {
    val some = m.copy(tieStrengthWeightedDegree = Some(2.5))
    right(validate(some))
    assertEquals(Wire.read[ResearcherMetricsV2](right(Wire.write(some))), Right(some))
    right(validate(m.copy(tieStrengthWeightedDegree = Some(0.0))))
    val json = right(Wire.write(m))
    assertEquals(ujson.read(json)("tieStrengthWeightedDegree"), ujson.Null)
    assertEquals(Wire.read[ResearcherMetricsV2](json).map(_.tieStrengthWeightedDegree), Right(None))
    // an explicit null in hand-written JSON also reads as None
    val withNull = ujson.read(json)
    withNull("tieStrengthWeightedDegree") = ujson.Null
    assertEquals(
      Wire.read[ResearcherMetricsV2](ujson.write(withNull)).map(_.tieStrengthWeightedDegree),
      Right(None),
    )
  }

  test("ResearcherMetricsV2: boundary, participationCoefficient exactly 0 and 1") {
    right(validate(m.copy(participationCoefficient = 0.0)))
    right(validate(m.copy(participationCoefficient = 1.0)))
  }

  test("ResearcherMetricsV2: boundary, one community with zero cross degree") {
    right(
      validate(
        m.copy(crossCommunityDegree = 0L, communitiesTouched = 1, participationCoefficient = 0.0)
      )
    )
  }

  test("ResearcherMetricsV2 rejects an unsupported schemaVersion and an invalid researcher ID") {
    expectErr(validate(m.copy(schemaVersion = 2))) { case _: UnsupportedSchemaVersion => () }
    expectErr(validate(m.copy(researcherId = "researcher:nope"))) { case _: MalformedId => () }
  }

  test("ResearcherMetricsV2 rejects an invalid window and a bad policyVersion") {
    expectErr(validate(m.copy(windowStart = 2005, windowEnd = 2000))) { case _: InvalidWindow =>
      ()
    }
    expectErr(validate(m.copy(windowStart = 12))) { case _: InvalidYear => () }
    rejectsM(m.copy(policyVersion = " "), "policyVersion")
  }

  test("ResearcherMetricsV2 rejects negative counts") {
    rejectsM(m.copy(publicationCount = -1L), "publicationCount")
    rejectsM(m.copy(coauthorCount = -1L), "coauthorCount")
    rejectsM(m.copy(teamSizeTotal = -1L), "teamSizeTotal")
    rejectsM(m.copy(avgTeamSize = -1.0), "avgTeamSize")
    rejectsM(m.copy(maxTeamSize = -1), "maxTeamSize")
    rejectsM(m.copy(weightedDegree = -1L), "weightedDegree")
    rejectsM(m.copy(maxTeamWorkCount = -1), "maxTeamWorkCount")
    rejectsM(m.copy(crossCommunityDegree = -1L), "crossCommunityDegree")
  }

  test("ResearcherMetricsV2 rejects a non-finite avgTeamSize") {
    rejectsM(m.copy(avgTeamSize = Double.NaN), "avgTeamSize")
    rejectsM(m.copy(avgTeamSize = Double.PositiveInfinity), "avgTeamSize")
  }

  test("ResearcherMetricsV2 rejects participationCoefficient outside [0, 1] or not finite") {
    rejectsM(m.copy(participationCoefficient = 1.0001), "participationCoefficient")
    rejectsM(m.copy(participationCoefficient = -0.0001), "participationCoefficient")
    rejectsM(m.copy(participationCoefficient = Double.NaN), "participationCoefficient")
    rejectsM(m.copy(participationCoefficient = Double.PositiveInfinity), "participationCoefficient")
  }

  test("ResearcherMetricsV2 rejects communitiesTouched below one") {
    rejectsM(m.copy(communitiesTouched = 0), "communitiesTouched")
  }

  test("ResearcherMetricsV2 rejects maxTeamSize above teamSizeTotal") {
    rejectsM(m.copy(maxTeamSize = 13), "maxTeamSize")
  }

  test("ResearcherMetricsV2 rejects teamSizeTotal below publicationCount") {
    // P=3, T=2: M<=T and A=T/P hold, only T>=P is broken
    rejectsM(m.copy(teamSizeTotal = 2L, maxTeamSize = 2, avgTeamSize = 2.0 / 3.0), "teamSizeTotal")
  }

  test("ResearcherMetricsV2 rejects maxTeamSize 0 or avgTeamSize above maxTeamSize when P >= 1") {
    rejectsM(m.copy(maxTeamSize = 0), "avgTeamSize")
    rejectsM(m.copy(maxTeamSize = 3), "avgTeamSize") // A = 4 > M = 3
  }

  test("ResearcherMetricsV2 rejects avgTeamSize that is not teamSizeTotal / publicationCount") {
    rejectsM(m.copy(avgTeamSize = 3.5), "avgTeamSize")
    // tolerance 1e-6: a difference of 1e-9 passes
    right(validate(m.copy(avgTeamSize = 4.0 + 1e-9)))
  }

  test("ResearcherMetricsV2 rejects weightedDegree below coauthorCount") {
    rejectsM(m.copy(weightedDegree = 3L), "weightedDegree")
  }

  test("ResearcherMetricsV2 rejects crossCommunityDegree above coauthorCount") {
    rejectsM(m.copy(crossCommunityDegree = 5L, communitiesTouched = 3), "crossCommunityDegree")
  }

  test("ResearcherMetricsV2: communitiesTouched is 1 exactly when crossCommunityDegree is 0") {
    rejectsM(m.copy(crossCommunityDegree = 0L), "communitiesTouched") // ct = 3
    rejectsM(m.copy(communitiesTouched = 1, participationCoefficient = 0.0), "communitiesTouched")
  }

  test("ResearcherMetricsV2 rejects communitiesTouched above crossCommunityDegree + 1") {
    rejectsM(m.copy(communitiesTouched = 4), "communitiesTouched")
  }

  test("ResearcherMetricsV2 rejects a non-zero participationCoefficient with one community") {
    rejectsM(
      m.copy(crossCommunityDegree = 0L, communitiesTouched = 1, participationCoefficient = 0.3),
      "participationCoefficient",
    )
  }

  test("ResearcherMetricsV2 rejects maxTeamWorkCount above publicationCount") {
    rejectsM(m.copy(maxTeamWorkCount = 4), "maxTeamWorkCount")
  }

  test("ResearcherMetricsV2 rejects maxTeamWorkIds that are too many, invalid or duplicated") {
    rejectsM(m.copy(maxTeamWorkIds = List(w1, w2)), "maxTeamWorkIds") // 2 > count 1
    expectErr(validate(m.copy(maxTeamWorkIds = List("nope")))) { case _: MalformedId => () }
    rejectsM(m.copy(maxTeamWorkCount = 2, maxTeamWorkIds = List(w1, w1)), "maxTeamWorkIds")
    right(validate(m.copy(maxTeamWorkCount = 2, maxTeamWorkIds = List(w1)))) // bounded list is fine
  }

  test("ResearcherMetricsV2 rejects malformed community and component references") {
    expectErr(validate(m.copy(communityId = "nope"))) { case _: MalformedId => () }
    expectErr(validate(m.copy(communityId = "component:x"))) { case _: MalformedId => () }
    expectErr(validate(m.copy(componentId = "community:x"))) { case _: MalformedId => () }
    expectErr(validate(m.copy(componentId = ""))) { case _: MalformedId => () }
  }

  test("ResearcherMetricsV2 rejects coreness that is negative or above coauthorCount") {
    rejectsM(m.copy(coreness = Some(-1)), "coreness")
    rejectsM(m.copy(coreness = Some(5)), "coreness")
  }

  test("ResearcherMetricsV2 rejects a negative or non-finite tieStrengthWeightedDegree") {
    rejectsM(m.copy(tieStrengthWeightedDegree = Some(-0.5)), "tieStrengthWeightedDegree")
    rejectsM(m.copy(tieStrengthWeightedDegree = Some(Double.NaN)), "tieStrengthWeightedDegree")
  }

  test("ResearcherMetricsV2 rejects an invalid provenance") {
    rejectsM(m.copy(provenance = prov.copy(codeVersion = "")), "provenance.codeVersion")
  }

  // ---- GraphEdgeTypes -------------------------------------------------------------------------

  test("GraphEdgeTypes.ResolvesTo is RESOLVES_TO") {
    assertEquals(GraphEdgeTypes.ResolvesTo, "RESOLVES_TO")
  }

  // ---- the frozen v1 records are unchanged ----------------------------------------------------

  test("v1: CoauthorEdge still round-trips and a known v1 JSON still reads") {
    val v1   = Fx.coauthorEdge
    val json = right(Wire.write(v1))
    assertEquals(Wire.read[CoauthorEdge](json), Right(v1))
    val tree = ujson.read(json)
    assertEquals(
      tree.obj.keySet.toSet,
      Set(
        "schemaVersion",
        "edgeId",
        "pair",
        "window",
        "policyVersion",
        "workCount",
        "supportingWorkIds",
        "provenance",
      ),
    )
    assertEquals(tree("pair").obj.keySet.toSet, Set("first", "second"))
    assertEquals(tree("window").obj.keySet.toSet, Set("startYear", "endYear"))
    // hand-written v1 JSON
    val hand =
      s"""{"schemaVersion":1,"edgeId":"${v1.edgeId}","pair":{"first":"${v1.pair.first}","second":"${v1.pair.second}"},"window":{"startYear":2000,"endYear":2004},"policyVersion":"p1","workCount":1,"supportingWorkIds":["$w1"],"provenance":${Fx.provJson}}"""
    assertEquals(Wire.read[CoauthorEdge](hand), Right(v1))
  }

  test("v1: CoauthorEdge keeps its rule that workCount equals the number of supporting works") {
    expectErr(validate(Fx.coauthorEdge.copy(workCount = 5))) { case InvalidField(f, _) =>
      assertEquals(f, "workCount")
    }
  }

  test("v1: a v1 CoauthorEdge JSON is not a CoauthorEdgeV2 and vice versa") {
    val v1json = right(Wire.write(Fx.coauthorEdge))
    assert(Wire.read[CoauthorEdgeV2](v1json).isLeft)
    assert(Wire.read[CoauthorEdge](right(Wire.write(edge))).isLeft)
  }

  test("v1: ResearcherMetrics (named-metric list) still round-trips") {
    val v1 = ResearcherMetrics(
      1,
      b,
      win,
      Policy,
      List(MetricValue(CollaborationMetric.Degree, 3.0)),
      prov,
    )
    right(validate(v1))
    assertEquals(Wire.read[ResearcherMetrics](right(Wire.write(v1))), Right(v1))
    assert(Wire.read[ResearcherMetricsV2](right(Wire.write(v1))).isLeft)
  }

  test("v1: researcherIdForCluster and researcherIdMergedCluster behave as before") {
    val orcidA = "0000-0002-1825-0097"
    val orcidB = "0000-0001-5109-3700"
    // one ORCID: the ORCID identity (v1 rule; v2 never does this)
    assertEquals(
      Ids.researcherIdForCluster(List(a, b), List(orcidA)),
      Ids.researcherIdFromOrcid(orcidA),
    )
    // no ORCID and one base: the base itself
    assertEquals(Ids.researcherIdForCluster(List(a), Nil), Right(a))
    // no ORCID and several: the merged-cluster ID, order independent
    assertEquals(
      Ids.researcherIdForCluster(List(b, a), Nil),
      Ids.researcherIdMergedCluster(List(a, b)),
    )
    assertEquals(
      Ids.researcherIdMergedCluster(List(b, a)),
      Ids.researcherIdMergedCluster(List(a, b)),
    )
    // two ORCIDs: refused; empty bases: refused; one distinct base in a merged cluster: refused
    expectErr(Ids.researcherIdForCluster(List(a, b), List(orcidA, orcidB))) {
      case InvalidField(f, _) => assertEquals(f, "orcids")
    }
    expectErr(Ids.researcherIdForCluster(Nil, Nil)) { case InvalidField(f, _) =>
      assertEquals(f, "baseIds")
    }
    expectErr(Ids.researcherIdMergedCluster(List(a, a))) { case InvalidField(f, _) =>
      assertEquals(f, "baseIds")
    }
    // the v1 merged-cluster rule is NOT the v2 rule
    assertNotEquals(
      Ids.researcherIdForCluster(List(a, b), Nil),
      Ids.researcherIdForClusterV2(List("Ann", "Bea")),
    )
  }
