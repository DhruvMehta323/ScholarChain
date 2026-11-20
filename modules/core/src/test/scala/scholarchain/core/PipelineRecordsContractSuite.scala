package scholarchain.core

import ValidationError.*
import PipelineFx.*

class PipelineRecordsContractSuite extends ContractSupport:

  // ---- frozen wire examples (domain-engineer A1 / A1r, full values substituted) ---------------

  private def sub(s: String): String =
    s.replace("@P@", provJson).replace("@M0@", M0).replace("@M1@", M1)

  private val jAuthorship =
    """{"schemaVersion":1,"mentionId":"@M0@","workId":"work:dblp:journals/x/A1","position":0,"year":2020,"provenance":@P@}"""
  private val jTeam =
    """{"schemaVersion":1,"workId":"work:dblp:journals/x/A1","year":2020,"teamSize":2,"mentionIds":["@M0@","@M1@"],"provenance":@P@}"""
  private val jPair =
    """{"schemaVersion":1,"workId":"work:dblp:journals/x/A1","year":2020,"pair":{"first":"@M0@","second":"@M1@"},"firstPosition":0,"secondPosition":1,"provenance":@P@}"""
  private val jDoiEvidence =
    """{"schemaVersion":1,"identifierType":"doi","normalizedIdentifier":"10.1000/abc","linkedRecordIds":["@M0@","@M1@"],"outcome":"exact_link","provenance":@P@}"""
  // Captured from a real Wire.write round trip (test-engineer, Phase 04 A2); A1r gave none.
  private val jOpenAlexEvidence =
    """{"schemaVersion":1,"identifierType":"openalex","normalizedIdentifier":"W2718513939","linkedRecordIds":["@M0@","@M1@"],"outcome":"exact_link","provenance":@P@}"""
  private val jReject =
    """{"schemaVersion":1,"schema":"reject-v1","runId":"run:e18d3d9837bbd1d123d801ab9ecafc37c13fc0f316329b629c9426bc467b20da","jobId":"parse-dblp","source":"dblp","sourceRecordId":"journals/x/A1","inputByteOffset":1024,"reasonCode":"missing_year","message":"no year element","rawExcerpt":"<article>..."}"""
  private val jManifest =
    """{"schemaVersion":1,"runId":"run:8ecfd4548c5f4331f23466bd0bfe9baf65b2ddee2cacffb661e96751255adb48","stage":"hadoop-pipeline","attempt":1,"snapshot":"dblp-sample-2026-01","codeVersion":"0.1.0","inputs":[{"inputId":"s3://b/raw/dblp.xml","version":"2026-01","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"config":{"hadoop.large-team-threshold":"50"},"jobs":[{"jobId":"parse-dblp","counters":{"records.in":10,"records.rejected":1},"outputs":[{"path":"s3://b/out/works","rowCount":9,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}]}"""

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

  test("fixture ids equal the values stated in the A1 report") {
    assertEquals(Ids.mentionId(W, 0), Right(M0))
    assertEquals(Ids.mentionId(W, 1), Right(M1))
    assert(M0.compareTo(M1) < 0)
  }

  frozen("MentionAuthorship", authorship, jAuthorship)
  frozen("MentionTeam", team, jTeam)
  frozen("MentionPairCandidate", pairCandidate, jPair)
  frozen("ExactIdentifierEvidence doi", doiEvidence, jDoiEvidence)
  frozen("ExactIdentifierEvidence openalex", openAlexEvidence, jOpenAlexEvidence)
  frozen("RejectRecord", reject, jReject)
  frozen("PipelineRunManifest", manifest, jManifest)

  test("PipelineRunManifest.build yields the frozen runId of the A1 example") {
    assertEquals(manifest.runId, RunManifestId)
  }

  test("MentionPairCandidate.build(W, 2020, 1, 0) is the frozen pair example") {
    assertEquals(MentionPairCandidate.build(W, 2020, 1, 0, prov), Right(pairCandidate))
  }

  // ---- round trips ----------------------------------------------------------------------------

  roundTrip("MentionAuthorship", authorship)
  roundTrip(
    "MentionAuthorship position 7",
    MentionAuthorship(1, Fx.get(Ids.mentionId(W, 7)), W, 7, 1999, prov),
  )
  roundTrip("MentionTeam", team)
  roundTrip("MentionTeam single author", MentionTeam(1, W, 2020, 1, List(M0), prov))
  roundTrip("MentionPairCandidate", pairCandidate)
  roundTrip("ExactIdentifierEvidence doi", doiEvidence)
  roundTrip("ExactIdentifierEvidence arxiv", evidence(IdentifierType.Arxiv, "2101.00001"))
  roundTrip(
    "ExactIdentifierEvidence arxiv old style",
    evidence(IdentifierType.Arxiv, "hep-th/9901001"),
  )
  roundTrip("ExactIdentifierEvidence orcid", evidence(IdentifierType.Orcid, "0000-0002-1825-0097"))
  roundTrip("ExactIdentifierEvidence openalex", openAlexEvidence)
  roundTrip(
    "ExactIdentifierEvidence conflict",
    doiEvidence.copy(outcome = ExactIdentifierOutcome.Conflict),
  )
  roundTrip(
    "ExactIdentifierEvidence duplicate",
    doiEvidence.copy(outcome = ExactIdentifierOutcome.Duplicate),
  )
  roundTrip("RejectRecord", reject)
  roundTrip("RejectRecord offset only", reject.copy(sourceRecordId = None))
  roundTrip("RejectRecord id only", reject.copy(inputByteOffset = None))
  roundTrip(
    "OutputRef-less job manifest",
    Fx.get(
      PipelineRunManifest.build(
        "hadoop-pipeline",
        2,
        "snap",
        "0.1.0",
        List(input),
        Map.empty,
        List(JobSummary("j1", Map.empty, Nil)),
        Some("2026-11-02T10:00:00Z"),
      )
    ),
  )
  roundTrip("PipelineRunManifest", manifest)

  test("None fields are omitted on the wire (RejectRecord, OutputRef)") {
    val j = right(Wire.write(reject.copy(sourceRecordId = None)))
    assert(!j.contains("sourceRecordId"), j)
    val m = right(
      Wire.write(
        Fx.get(
          PipelineRunManifest.build(
            "s",
            1,
            "snap",
            "0.1.0",
            List(input),
            Map.empty,
            List(JobSummary("j", Map.empty, List(OutputRef("p", 0L, None)))),
          )
        )
      )
    )
    assert(!m.contains("checksum\":null") && !m.contains("createdAt"), m)
  }

  test("every RejectReason and ExactIdentifierOutcome wire name round trips") {
    RejectReason.values.foreach { r =>
      val rec = reject.copy(reasonCode = r)
      assert(right(Wire.write(rec)).contains(s""""reasonCode":"${r.wire}""""))
      assertEquals(Wire.read[RejectRecord](right(Wire.write(rec))), Right(rec))
    }
    assertEquals(
      RejectReason.values.map(_.wire).toList,
      List(
        "malformed_record",
        "encoding_error",
        "missing_title",
        "missing_year",
        "invalid_year",
        "missing_authors",
        "invalid_identifier",
        "unsupported_type",
        "oversized_record",
        "pair_cap_exceeded",
        "duplicate_record",
        "schema_violation",
      ),
    )
    assertEquals(
      ExactIdentifierOutcome.values.map(_.wire).toList,
      List("exact_link", "duplicate", "conflict"),
    )
    assertEquals(
      IdentifierType.values.map(_.wire).toList,
      List("doi", "arxiv", "orcid", "openalex"),
    )
  }

  // ---- MentionAuthorship rejections -----------------------------------------------------------

  test("MentionAuthorship: wrong mentionId") {
    expectErr(validate(authorship.copy(mentionId = M1))) { case InvalidField("mentionId", _) => () }
  }
  test("MentionAuthorship: position -1") {
    expectErr(validate(authorship.copy(position = -1))) { case InvalidField("position", _) => () }
  }
  test("MentionAuthorship: year 999") {
    assertEquals(validate(authorship.copy(year = 999)), Left(InvalidYear(999)))
  }
  test("MentionAuthorship: provenance with empty inputs") {
    expectErr(validate(authorship.copy(provenance = prov.copy(inputs = Nil)))) {
      case InvalidField("provenance.inputs", _) => ()
    }
  }
  test("MentionAuthorship: provenance with blank code version") {
    expectErr(validate(authorship.copy(provenance = prov.copy(codeVersion = " ")))) {
      case InvalidField("provenance.codeVersion", _) => ()
    }
  }
  test("MentionAuthorship: schema 2") {
    assertEquals(
      validate(authorship.copy(schemaVersion = 2)),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
    assertEquals(
      Wire.read[MentionAuthorship](
        sub(jAuthorship)
          .replace("\"schemaVersion\":1,\"mentionId", "\"schemaVersion\":2,\"mentionId")
      ),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
  }
  test("MentionAuthorship: malformed work id") {
    expectErr(validate(authorship.copy(workId = "bad"))) { case MalformedId("work", "bad") => () }
  }
  test("MentionAuthorship: an invalid record is never written") {
    assert(Wire.write(authorship.copy(position = -1)).isLeft)
  }

  // ---- MentionTeam rejections -----------------------------------------------------------------

  test("MentionTeam: empty list") {
    expectErr(validate(team.copy(teamSize = 0, mentionIds = Nil))) {
      case InvalidField("mentionIds.size", _) => ()
    }
  }
  test("MentionTeam: teamSize 3 with 2 ids") {
    expectErr(validate(team.copy(teamSize = 3))) { case InvalidField("teamSize", _) => () }
  }
  test("MentionTeam: ids swapped") {
    expectErr(validate(team.copy(mentionIds = List(M1, M0)))) {
      case InvalidField("mentionIds", _) => ()
    }
  }
  test("MentionTeam: gap in positions") {
    val gap = Fx.get(Ids.mentionId(W, 2))
    expectErr(validate(team.copy(mentionIds = List(M0, gap)))) {
      case InvalidField("mentionIds", _) => ()
    }
  }
  test("MentionTeam: ids from another work") {
    val other = Fx.get(Ids.mentionId("work:dblp:journals/x/A2", 0))
    expectErr(validate(team.copy(mentionIds = List(other, M1)))) {
      case InvalidField("mentionIds", _) => ()
    }
  }
  test("MentionTeam: year 999 and schema 2") {
    assertEquals(validate(team.copy(year = 999)), Left(InvalidYear(999)))
    assertEquals(validate(team.copy(schemaVersion = 2)), Left(UnsupportedSchemaVersion(2, Set(1))))
  }
  test("MentionTeam: a single-author team is valid") {
    assertEquals(validate(MentionTeam(1, W, 2020, 1, List(M0), prov)).map(_.teamSize), Right(1))
  }

  // ---- MentionPairCandidate rejections --------------------------------------------------------

  test("MentionPairCandidate: reversed pair") {
    val c = pairCandidate.copy(pair = OrderedPair(M1, M0), firstPosition = 1, secondPosition = 0)
    expectErr(validate(c)) { case InvalidPair(M1, M0, "not in canonical order") => () }
  }
  test("MentionPairCandidate: self-pair (direct)") {
    expectErr(validate(pairCandidate.copy(pair = OrderedPair(M0, M0), secondPosition = 0))) {
      case InvalidPair(M0, M0, "self-pair") => ()
    }
  }
  test("MentionPairCandidate.build with equal positions is a self-pair") {
    expectErr(MentionPairCandidate.build(W, 2020, 1, 1, prov)) {
      case InvalidPair(_, _, "self-pair") => ()
    }
  }
  test("MentionPairCandidate: second position does not match the id") {
    expectErr(validate(pairCandidate.copy(secondPosition = 5))) {
      case InvalidField("pair.second", _) => ()
    }
  }
  test("MentionPairCandidate: first position does not match the id") {
    expectErr(validate(pairCandidate.copy(firstPosition = 5))) {
      case InvalidField("pair.first", _) => ()
    }
  }
  test("MentionPairCandidate: researcher id in place of a mention id") {
    expectErr(validate(pairCandidate.copy(pair = Fx.pair))) {
      case MalformedId("mention", id) if id == Fx.orcidId => ()
    }
  }
  test("MentionPairCandidate: negative position") {
    expectErr(validate(pairCandidate.copy(firstPosition = -1))) {
      case InvalidField("firstPosition", _) => ()
    }
    expectErr(validate(pairCandidate.copy(secondPosition = -1))) {
      case InvalidField("secondPosition", _) => ()
    }
    expectErr(MentionPairCandidate.build(W, 2020, -1, 0, prov)) {
      case InvalidField("position", _) => ()
    }
  }
  test("MentionPairCandidate: year 999, schema 2, bad provenance") {
    assertEquals(validate(pairCandidate.copy(year = 999)), Left(InvalidYear(999)))
    assertEquals(
      validate(pairCandidate.copy(schemaVersion = 2)),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
    expectErr(validate(pairCandidate.copy(provenance = prov.copy(inputs = Nil)))) {
      case InvalidField("provenance.inputs", _) => ()
    }
  }
  test("MentionPairCandidate.build is argument-order independent and positions follow the ids") {
    val results = for
      i <- 0 until 10
      j <- (i + 1) until 10
    yield (
      i,
      j,
      MentionPairCandidate.build(W, 2020, i, j, prov),
      MentionPairCandidate.build(W, 2020, j, i, prov),
    )
    results.foreach { (i, j, a, b) =>
      assertEquals(a, b, s"positions $i,$j")
      val c = right(a)
      assert(c.pair.first.compareTo(c.pair.second) < 0)
      assertEquals(Set(c.firstPosition, c.secondPosition), Set(i, j))
      assertEquals(Ids.mentionId(W, c.firstPosition), Right(c.pair.first))
    }
    assert(results.exists((_, _, a, _) => right(a).firstPosition > right(a).secondPosition))
  }

  // ---- ExactIdentifierEvidence rejections -----------------------------------------------------

  test("ExactIdentifierEvidence: non-canonical DOI DOI:10.1000/ABC") {
    expectErr(validate(evidence(IdentifierType.Doi, "DOI:10.1000/ABC"))) {
      case InvalidField("normalizedIdentifier", _) => ()
    }
  }
  test("ExactIdentifierEvidence: arXiv with a version") {
    expectErr(validate(evidence(IdentifierType.Arxiv, "2101.00001v2"))) {
      case InvalidField("normalizedIdentifier", _) => ()
    }
  }
  test("ExactIdentifierEvidence: bad ORCID checksum") {
    expectErr(validate(evidence(IdentifierType.Orcid, "0000-0002-1825-0098"))) {
      case InvalidOrcid("0000-0002-1825-0098", _) => ()
    }
  }
  test("ExactIdentifierEvidence: a single linked id") {
    expectErr(validate(doiEvidence.copy(linkedRecordIds = List(M0)))) {
      case InvalidField("linkedRecordIds.size", _) => ()
    }
  }
  test("ExactIdentifierEvidence: unsorted linked ids") {
    expectErr(validate(doiEvidence.copy(linkedRecordIds = List(M1, M0)))) {
      case InvalidField("linkedRecordIds", _) => ()
    }
  }
  test("ExactIdentifierEvidence: duplicate linked ids") {
    expectErr(validate(doiEvidence.copy(linkedRecordIds = List(M0, M0)))) {
      case InvalidField("linkedRecordIds", _) => ()
    }
  }
  test("ExactIdentifierEvidence: malformed linked reference") {
    expectErr(validate(doiEvidence.copy(linkedRecordIds = List("a b", M1)))) {
      case MalformedId("entity", "a b") => ()
    }
  }
  test("ExactIdentifierEvidence: unknown outcome string is rejected on read") {
    val bad = sub(jDoiEvidence).replace("exact_link", "maybe")
    expectErr(Wire.read[ExactIdentifierEvidence](bad)) { case Malformed(_) => () }
  }
  test("ExactIdentifierEvidence: unknown identifierType string is rejected on read") {
    val bad = sub(jDoiEvidence).replace("\"doi\"", "\"isbn\"")
    expectErr(Wire.read[ExactIdentifierEvidence](bad)) { case Malformed(_) => () }
  }
  test("ExactIdentifierEvidence: openalex lowercase and URL forms are not canonical") {
    expectErr(validate(evidence(IdentifierType.OpenAlex, "w2718513939"))) {
      case InvalidField("normalizedIdentifier", _) => ()
    }
    expectErr(validate(evidence(IdentifierType.OpenAlex, "https://openalex.org/W2718513939"))) {
      case InvalidField("normalizedIdentifier", _) => ()
    }
  }
  test("ExactIdentifierEvidence: invalid openalex id") {
    expectErr(validate(evidence(IdentifierType.OpenAlex, "C123"))) {
      case InvalidOpenAlex("C123", _) => ()
    }
  }
  test("ExactIdentifierEvidence: schema 2 and bad provenance") {
    assertEquals(
      validate(doiEvidence.copy(schemaVersion = 2)),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
    expectErr(validate(doiEvidence.copy(provenance = prov.copy(codeVersion = "")))) {
      case InvalidField("provenance.codeVersion", _) => ()
    }
  }
  test("ExactIdentifierEvidence carries no confidence, score or threshold on the wire") {
    val j = right(Wire.write(doiEvidence)).toLowerCase
    List("confidence", "score", "threshold").foreach(w => assert(!j.contains(w), w))
  }

  // ---- RejectRecord rejections ----------------------------------------------------------------

  test("RejectRecord: schema string reject-v2") {
    expectErr(validate(reject.copy(schema = "reject-v2"))) { case InvalidField("schema", _) => () }
  }
  test("RejectRecord: 512 code points accepted, 513 rejected") {
    assert(validate(reject.copy(rawExcerpt = "x" * 512)).isRight)
    expectErr(validate(reject.copy(rawExcerpt = "x" * 513))) { case InvalidField("rawExcerpt", _) =>
      ()
    }
  }
  test("RejectRecord: excerpt is counted in code points, not UTF-16 units") {
    val emoji = new String(Character.toChars(0x1f600))
    assert(validate(reject.copy(rawExcerpt = emoji * 512)).isRight)
    expectErr(validate(reject.copy(rawExcerpt = emoji * 513))) {
      case InvalidField("rawExcerpt", _) => ()
    }
  }
  test("RejectRecord.boundExcerpt truncates to 512 code points without splitting a pair") {
    val emoji = new String(Character.toChars(0x1f600))
    assertEquals(RejectRecord.MaxExcerpt, 512)
    assertEquals(RejectRecord.boundExcerpt("short"), "short")
    assertEquals(RejectRecord.boundExcerpt("x" * 512), "x" * 512)
    val cut = RejectRecord.boundExcerpt("x" * 511 + emoji + "tail")
    assertEquals(cut, "x" * 511 + emoji)
    val cut2 = RejectRecord.boundExcerpt("x" * 512 + emoji)
    assertEquals(cut2, "x" * 512)
    assertEquals(RejectRecord.boundExcerpt(emoji * 600), emoji * 512)
    assert(validate(reject.copy(rawExcerpt = cut)).isRight)
  }
  test("RejectRecord: neither locator") {
    expectErr(validate(reject.copy(sourceRecordId = None, inputByteOffset = None))) {
      case MissingField("sourceRecordId or inputByteOffset") => ()
    }
  }
  test("RejectRecord: offset -1") {
    expectErr(validate(reject.copy(inputByteOffset = Some(-1L)))) {
      case InvalidField("inputByteOffset", _) => ()
    }
    assert(validate(reject.copy(inputByteOffset = Some(0L))).isRight)
  }
  test("RejectRecord: invalid runId") {
    expectErr(validate(reject.copy(runId = "run:xyz"))) { case MalformedId("run", "run:xyz") => () }
  }
  test("RejectRecord: blank message") {
    expectErr(validate(reject.copy(message = "  "))) { case InvalidField("message", _) => () }
  }
  test("RejectRecord: unknown reason is rejected on read") {
    val bad = jReject.replace("missing_year", "no_such_reason")
    expectErr(Wire.read[RejectRecord](bad)) { case Malformed(_) => () }
  }
  test("RejectRecord: jobId blank or containing whitespace") {
    expectErr(validate(reject.copy(jobId = ""))) { case InvalidField("jobId", _) => () }
    expectErr(validate(reject.copy(jobId = "parse dblp"))) { case InvalidField("jobId", _) => () }
  }
  test("RejectRecord: blank sourceRecordId") {
    expectErr(validate(reject.copy(sourceRecordId = Some(" ")))) {
      case InvalidField("sourceRecordId", _) => ()
    }
  }
  test("RejectRecord: schema 2") {
    assertEquals(
      validate(reject.copy(schemaVersion = 2)),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
  }

  // ---- PipelineRunManifest rejections ---------------------------------------------------------

  test("PipelineRunManifest: tampered runId") {
    expectErr(validate(manifest.copy(runId = RunReject))) { case InvalidField("runId", _) => () }
  }
  test("PipelineRunManifest: tampered config invalidates the runId") {
    expectErr(validate(manifest.copy(config = Map("hadoop.large-team-threshold" -> "51")))) {
      case InvalidField("runId", _) => ()
    }
  }
  test("PipelineRunManifest: snapshot is descriptive and not hashed") {
    val other = Fx.get(validate(manifest.copy(snapshot = "another-snapshot")))
    assertEquals(other.runId, manifest.runId)
  }
  test("PipelineRunManifest: snapshot with whitespace") {
    expectErr(validate(manifest.copy(snapshot = "a b"))) { case InvalidField("snapshot", _) => () }
  }
  test("PipelineRunManifest: input without a checksum") {
    val ins = List(input.copy(checksum = None))
    val id  = Fx.get(
      Ids.runId(manifest.stage, manifest.codeVersion, manifest.attempt, manifest.config, ins)
    )
    expectErr(validate(manifest.copy(inputs = ins, runId = id))) {
      case InvalidField("inputs.checksum", _) => ()
    }
    expectErr(PipelineRunManifest.build("s", 1, "snap", "0.1.0", ins, Map.empty, Nil)) {
      case InvalidField("inputs.checksum", _) => ()
    }
  }
  test("PipelineRunManifest: malformed input checksum") {
    val ins = List(input.copy(checksum = Some("ABC")))
    val id  = Fx.get(
      Ids.runId(manifest.stage, manifest.codeVersion, manifest.attempt, manifest.config, ins)
    )
    expectErr(validate(manifest.copy(inputs = ins, runId = id))) {
      case InvalidField("inputs.checksum", _) => ()
    }
  }
  test("PipelineRunManifest: empty inputs") {
    expectErr(validate(manifest.copy(inputs = Nil))) { case InvalidField("inputs", _) => () }
  }
  test("PipelineRunManifest: duplicate jobId") {
    val m = manifest.copy(jobs = List(job, job))
    expectErr(validate(m)) { case InvalidField("jobs.jobId", _) => () }
  }
  test("PipelineRunManifest: negative counter") {
    val m = manifest.copy(jobs = List(job.copy(counters = Map("records.in" -> -1L))))
    expectErr(validate(m)) { case InvalidField("jobs.counters.records.in", _) => () }
  }
  test("PipelineRunManifest: counter name with whitespace") {
    val m = manifest.copy(jobs = List(job.copy(counters = Map("records in" -> 1L))))
    expectErr(validate(m)) { case InvalidField("jobs.counters.name", _) => () }
  }
  test("PipelineRunManifest: negative row count") {
    val m = manifest.copy(jobs = List(job.copy(outputs = List(output.copy(rowCount = -1L)))))
    expectErr(validate(m)) { case InvalidField("jobs.outputs.rowCount", _) => () }
  }
  test("PipelineRunManifest: blank output path") {
    val m = manifest.copy(jobs = List(job.copy(outputs = List(output.copy(path = " ")))))
    expectErr(validate(m)) { case InvalidField("jobs.outputs.path", _) => () }
  }
  test("PipelineRunManifest: malformed output checksum") {
    val m = manifest.copy(jobs = List(job.copy(outputs = List(output.copy(checksum = Some("zz"))))))
    expectErr(validate(m)) { case InvalidField("jobs.outputs.checksum", _) => () }
  }
  test("PipelineRunManifest: bad createdAt and schema 2") {
    expectErr(validate(manifest.copy(createdAt = Some("yesterday")))) {
      case InvalidField("createdAt", _) => ()
    }
    assertEquals(
      validate(manifest.copy(schemaVersion = 2)),
      Left(UnsupportedSchemaVersion(2, Set(1))),
    )
  }
  test("PipelineRunManifest.build rejects attempt 0 and a blank stage") {
    expectErr(PipelineRunManifest.build("s", 0, "snap", "0.1.0", List(input), Map.empty, Nil)) {
      case InvalidField("attempt", _) => ()
    }
    expectErr(PipelineRunManifest.build("", 1, "snap", "0.1.0", List(input), Map.empty, Nil)) {
      case InvalidField("stage", _) => ()
    }
  }
