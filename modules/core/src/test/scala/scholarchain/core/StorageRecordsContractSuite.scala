package scholarchain.core

import java.nio.charset.StandardCharsets

import ValidationError.*

/** Phase 07 storage records: frozen examples, round trips and negative cases. */
class StorageRecordsContractSuite extends ContractSupport:

  private def resource(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(name)
    assert(in != null, s"classpath resource $name is missing")
    try String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private def readEx[A: Contract: WirePickle.Reader](name: String): A =
    right(Wire.read[A](resource(s"contracts/$name.example.json")))

  private def assertFrozen[A: Contract: WirePickle.Reader: WirePickle.Writer](name: String)(using
      munit.Location
  ): Unit =
    val json = resource(s"contracts/$name.example.json")
    assert(!json.endsWith("\n"), s"$name example is expected to have no trailing newline")
    val a = right(Wire.read[A](json))
    assertEquals(right(Wire.write(a)), json)
    assertEquals(Wire.read[A](right(Wire.write(a))), Right(a))

  private def field(e: Either[ValidationError, ?], name: String)(using munit.Location): Unit =
    e match
      case Left(InvalidField(f, _)) => assertEquals(f, name)
      case other                    => fail(s"expected InvalidField($name) but got $other")

  // ---- frozen examples ------------------------------------------------------------------------

  test("VectorRecord: the frozen example parses, validates and re-serializes byte-identically") {
    assertFrozen[VectorRecord]("vector-record")
  }
  test("RunLedgerEntry: the frozen example parses, validates and re-serializes byte-identically") {
    assertFrozen[RunLedgerEntry]("run-ledger-entry")
  }
  test("ArtifactRegistryEntry: frozen example round trips byte-identically") {
    assertFrozen[ArtifactRegistryEntry]("artifact-registry-entry")
  }
  test("QuerySessionRecord: the frozen example round trips byte-identically") {
    assertFrozen[QuerySessionRecord]("query-session-record")
  }
  test("EntityLinkCacheEntry: the frozen example round trips byte-identically") {
    assertFrozen[EntityLinkCacheEntry]("entity-link-cache-entry")
  }

  // ---- VectorRecord ---------------------------------------------------------------------------

  private val vr: VectorRecord = readEx[VectorRecord]("vector-record")

  test("VectorRecord: names the five index names of the contract") {
    assertEquals(
      VectorIndexes.all,
      List(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1",
      ),
    )
  }

  test("VectorRecord: 16 metadata entries pass, 17 are rejected") {
    val ok = (1 to 16).map(i => s"k$i" -> "v").toMap
    assert(validate(vr.copy(metadata = ok)).isRight)
    field(validate(vr.copy(metadata = ok + ("k17" -> "v"))), "metadata")
  }

  test("VectorRecord: metadata value of 128 chars passes, 129 is rejected") {
    assert(validate(vr.copy(metadata = Map("a" -> "x" * 128))).isRight)
    field(validate(vr.copy(metadata = Map("a" -> "x" * 129))), "metadata.value")
  }

  test("VectorRecord: metadata name of 64 chars passes, 65 and blank are rejected") {
    assert(validate(vr.copy(metadata = Map("n" * 64 -> "v"))).isRight)
    field(validate(vr.copy(metadata = Map("n" * 65 -> "v"))), "metadata.name")
    field(validate(vr.copy(metadata = Map(" " -> "v"))), "metadata.name")
  }

  test("VectorRecord: long text in metadata is rejected") {
    field(validate(vr.copy(metadata = Map("abstract" -> "lorem ipsum " * 200))), "metadata.value")
  }

  test("VectorRecord: dimension must be in 1..4096") {
    assert(validate(vr.copy(dimension = 1)).isRight)
    assert(validate(vr.copy(dimension = 4096)).isRight)
    field(validate(vr.copy(dimension = 0)), "dimension")
    field(validate(vr.copy(dimension = 4097)), "dimension")
    field(validate(vr.copy(dimension = -5)), "dimension")
  }

  test("VectorRecord: textHash must be 64 lowercase hex") {
    field(validate(vr.copy(textHash = "abc")), "textHash")
    field(validate(vr.copy(textHash = "A" * 64)), "textHash")
    field(validate(vr.copy(textHash = "g" * 64)), "textHash")
  }

  test("VectorRecord: textS3Uri must be a syntactically valid s3 URI") {
    List(
      "http://b/x",
      "s3://b/x",
      "s3://example-bucket/",
      "s3://example-bucket",
      "s3://example-bucket/a b",
      "s3://Example-Bucket/x",
      "s3://example-bucket/a\nb",
    ).foreach(u => field(validate(vr.copy(textS3Uri = u)), "textS3Uri"))
  }

  test("VectorRecord: runId must be a valid run ID") {
    field(validate(vr.copy(runId = "run:xyz")), "runId")
    field(validate(vr.copy(runId = "")), "runId")
  }

  test("VectorRecord: blank or whitespace key, index and model are rejected") {
    assert(validate(vr.copy(vectorKey = "")).isLeft)
    assert(validate(vr.copy(vectorKey = "a b")).isLeft)
    assert(validate(vr.copy(vectorKey = "k" * 513)).isLeft)
    assert(validate(vr.copy(indexName = "a b")).isLeft)
    assert(validate(vr.copy(embeddingModelId = "")).isLeft)
    assert(validate(vr.copy(embeddingModelVersion = " ")).isLeft)
  }

  test("VectorRecord: an unsupported schema version is rejected on read") {
    val bad = resource("contracts/vector-record.example.json").replace(
      """"schemaVersion":1""",
      """"schemaVersion":99""",
    )
    assert(Wire.read[VectorRecord](bad).isLeft)
  }

  // ---- RunLedgerEntry -------------------------------------------------------------------------

  private val rl: RunLedgerEntry = readEx[RunLedgerEntry]("run-ledger-entry")

  private def rebuilt(e: RunLedgerEntry): RunLedgerEntry =
    e.copy(runId = right(Ids.runId(e.stage, e.codeVersion, e.attempt, e.parameters, e.inputs)))

  test("RunLedgerEntry: runId must equal the derived run ID") {
    field(validate(rl.copy(runId = "run:" + "0" * 64)), "runId")
    field(validate(rl.copy(attempt = 2)), "runId")
  }

  test("RunLedgerEntry: started needs no finishedAt or failureReason") {
    val s = rebuilt(rl.copy(status = RunStatus.Started, finishedAt = None))
    assert(validate(s).isRight)
    field(validate(s.copy(finishedAt = rl.finishedAt)), "status")
    field(validate(s.copy(failureReason = Some("x"))), "status")
  }

  test("RunLedgerEntry: succeeded needs finishedAt and no failureReason") {
    field(validate(rl.copy(finishedAt = None)), "status")
    field(validate(rl.copy(failureReason = Some("boom"))), "status")
  }

  test("RunLedgerEntry: failed needs finishedAt and failureReason") {
    val f = rl.copy(status = RunStatus.Failed, failureReason = Some("disk full"))
    assert(validate(f).isRight)
    field(validate(f.copy(failureReason = None)), "status")
    field(validate(f.copy(finishedAt = None)), "status")
  }

  test("RunLedgerEntry: finishedAt before startedAt, bad instants and bad manifestUri") {
    field(validate(rl.copy(finishedAt = Some("2026-11-21T09:00:00Z"))), "finishedAt")
    field(validate(rl.copy(startedAt = "yesterday")), "startedAt")
    field(validate(rl.copy(manifestUri = Some("file:///x"))), "manifestUri")
  }

  test("RunLedgerEntry: empty inputs and a malformed input checksum are rejected") {
    assert(validate(rl.copy(inputs = Nil)).isLeft)
    val bad = rl.copy(inputs = List(InputRef("a", "1", Some("zz"))))
    assert(validate(bad).isLeft)
  }

  test("RunLedgerEntry: failureReason longer than the bound or blank is rejected") {
    val f = rl.copy(status = RunStatus.Failed, failureReason = Some("x" * 500))
    assert(validate(f).isRight)
    field(validate(f.copy(failureReason = Some("x" * 501))), "failureReason")
    field(validate(f.copy(failureReason = Some("  "))), "failureReason")
  }

  // ---- ArtifactRegistryEntry ------------------------------------------------------------------

  private val ar: ArtifactRegistryEntry = readEx[ArtifactRegistryEntry]("artifact-registry-entry")

  test("ArtifactRegistryEntry: hash, URI, size, instant and runId shape") {
    field(validate(ar.copy(contentHash = "abc")), "contentHash")
    field(validate(ar.copy(artifactUri = "s3://x")), "artifactUri")
    field(validate(ar.copy(sizeBytes = -1L)), "sizeBytes")
    assert(validate(ar.copy(sizeBytes = 0L)).isRight)
    field(validate(ar.copy(createdAt = "nope")), "createdAt")
    field(validate(ar.copy(runId = "run:bad")), "runId")
    assert(validate(ar.copy(artifactType = "has space")).isLeft)
  }

  test("ArtifactRegistryEntry: provenance.runId must equal runId when present") {
    val other = ar.provenance.copy(runId = Some("run:" + "1" * 64))
    field(validate(ar.copy(provenance = other)), "provenance.runId")
    assert(validate(ar.copy(provenance = ar.provenance.copy(runId = None))).isRight)
  }

  // ---- QuerySessionRecord ---------------------------------------------------------------------

  private val qs: QuerySessionRecord = readEx[QuerySessionRecord]("query-session-record")
  private val qsCreated: Long        = java.time.Instant.parse(qs.createdAt).getEpochSecond

  test("QuerySessionRecord: expiry must be after createdAt and at most 30 days later") {
    assert(validate(qs.copy(expiresAtEpochSeconds = qsCreated + 1)).isRight)
    assert(
      validate(
        qs.copy(expiresAtEpochSeconds = qsCreated + QuerySessionRecord.MaxRetentionSeconds)
      ).isRight
    )
    field(validate(qs.copy(expiresAtEpochSeconds = qsCreated)), "expiresAtEpochSeconds")
    field(validate(qs.copy(expiresAtEpochSeconds = qsCreated - 10)), "expiresAtEpochSeconds")
    field(
      validate(
        qs.copy(expiresAtEpochSeconds = qsCreated + QuerySessionRecord.MaxRetentionSeconds + 1)
      ),
      "expiresAtEpochSeconds",
    )
  }

  test("QuerySessionRecord: an answered session needs an evidence bundle hash") {
    field(validate(qs.copy(evidenceBundleHash = None)), "evidenceBundleHash")
    assert(
      validate(qs.copy(status = QuerySessionStatus.Rejected, evidenceBundleHash = None)).isRight
    )
  }

  test("QuerySessionRecord: queryId, hashes and answer URI shapes") {
    assert(validate(qs.copy(queryId = "q-1")).isLeft)
    field(validate(qs.copy(questionHash = "short")), "questionHash")
    field(validate(qs.copy(evidenceBundleHash = Some("short"))), "evidenceBundleHash")
    field(validate(qs.copy(answerUri = Some("https://x/y"))), "answerUri")
  }

  test("QuerySessionRecord: the record holds no question text field") {
    val keys = ujson.read(right[String](Wire.write(qs))).obj.keySet
    assert(keys.contains("questionHash"))
    assert(!keys.contains("question"))
  }

  // ---- EntityLinkCacheEntry -------------------------------------------------------------------

  private val el: EntityLinkCacheEntry = readEx[EntityLinkCacheEntry]("entity-link-cache-entry")

  test("EntityLinkCacheEntry: a match without researcherId is rejected") {
    field(validate(el.copy(researcherId = None)), "researcherId")
    assert(validate(el.copy(researcherId = None, outcome = MatchOutcome.NonMatch)).isRight)
    assert(validate(el.copy(researcherId = None, outcome = MatchOutcome.Uncertain)).isRight)
  }

  test("EntityLinkCacheEntry: confidence outside [0,1] and NaN are rejected") {
    assert(validate(el.copy(confidence = 0.0)).isRight)
    assert(validate(el.copy(confidence = 1.0)).isRight)
    assert(validate(el.copy(confidence = 1.01)).isLeft)
    assert(validate(el.copy(confidence = -0.01)).isLeft)
    assert(validate(el.copy(confidence = Double.NaN)).isLeft)
  }

  test("EntityLinkCacheEntry: expiry must be after createdAt; absent is allowed") {
    val created = java.time.Instant.parse(el.createdAt).getEpochSecond
    assert(validate(el.copy(expiresAtEpochSeconds = None)).isRight)
    field(validate(el.copy(expiresAtEpochSeconds = Some(created))), "expiresAtEpochSeconds")
    field(validate(el.copy(expiresAtEpochSeconds = Some(created - 1))), "expiresAtEpochSeconds")
  }

  test("EntityLinkCacheEntry: provenance.runId mismatch, bad runId, bad mention and cache key") {
    val other = el.provenance.copy(runId = Some("run:" + "2" * 64))
    field(validate(el.copy(provenance = other)), "provenance.runId")
    field(validate(el.copy(runId = "run:bad")), "runId")
    assert(validate(el.copy(mentionId = "mention:short")).isLeft)
    assert(validate(el.copy(cacheKey = "a b")).isLeft)
    assert(validate(el.copy(cacheKey = "k" * 129)).isLeft)
    assert(validate(el.copy(policyVersion = "")).isLeft)
  }

  test("storage records: validation error messages never echo the offending value") {
    val secretish = "SUPER-SECRET-VALUE-123"
    val e         = validate(vr.copy(embeddingModelId = secretish + " x"))
    e match
      case Left(err) => assert(!err.message.contains(secretish), err.message)
      case Right(_)  => fail("expected a rejection")
    val e2 = validate(ar.copy(artifactUri = secretish))
    e2 match
      case Left(err) => assert(!err.message.contains(secretish), err.message)
      case Right(_)  => fail("expected a rejection")
  }
