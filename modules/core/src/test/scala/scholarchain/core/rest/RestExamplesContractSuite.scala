package scholarchain.core.rest

import scala.io.Source

import scholarchain.core.*

/** Phase 10 C: the frozen `contracts/rest-*.example.json` resources read, validate and re-serialize
  * to the same JSON value, and their field names follow the course specification's REST examples. A
  * failure here is a production defect in the core REST contracts (owner domain-engineer).
  */
class RestExamplesContractSuite extends munit.FunSuite:

  private def resource(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(s"contracts/$name")
    assert(in != null, s"missing resource contracts/$name")
    val s = Source.fromInputStream(in, "UTF-8")
    try s.mkString.trim
    finally s.close()

  private def tree(s: String): ujson.Value = ujson.read(s)

  private def ok[A](e: Either[ValidationError, A]): A =
    e.fold(err => fail(s"expected Right but got $err"), identity)

  private def viewRoundTrip[A](name: String)(using
      Contract[A],
      WirePickle.Reader[A],
      WirePickle.Writer[A],
  ): Unit =
    val json = resource(name)
    val a    = ok(RestJson.read[A](json))
    val back = ok(RestJson.write(a))
    assertEquals(tree(back), tree(json), s"$name did not re-serialize equal")

  test("rest-api-error example round-trips") {
    viewRoundTrip[ApiError]("rest-api-error.example.json")
  }
  test("rest-evidence example round-trips") {
    viewRoundTrip[EvidenceView]("rest-evidence.example.json")
  }
  test("rest-explain example round-trips") {
    viewRoundTrip[ExplainView]("rest-explain.example.json")
  }
  test("rest-graph-node example round-trips") {
    viewRoundTrip[GraphNodeView]("rest-graph-node.example.json")
  }
  test("rest-health example round-trips") {
    viewRoundTrip[HealthView]("rest-health.example.json")
  }
  test("rest-readiness example round-trips") {
    viewRoundTrip[ReadinessView]("rest-readiness.example.json")
  }
  test("rest-job-status example round-trips") {
    viewRoundTrip[JobStatus]("rest-job-status.example.json")
  }
  test("rest-neighbors-page example round-trips") {
    viewRoundTrip[NeighborsPage]("rest-neighbors-page.example.json")
  }
  test("rest-query-response-sync example round-trips as SyncQueryResponse") {
    viewRoundTrip[SyncQueryResponse]("rest-query-response-sync.example.json")
  }
  test("rest-query-response-async example round-trips as AsyncQueryResponse") {
    viewRoundTrip[AsyncQueryResponse]("rest-query-response-async.example.json")
  }

  test("sync and async examples read as QueryResponse and pick the right case") {
    val s = ok(RestJson.read[QueryResponse](resource("rest-query-response-sync.example.json")))
    val a = ok(RestJson.read[QueryResponse](resource("rest-query-response-async.example.json")))
    assert(s.isInstanceOf[SyncQueryResponse])
    assert(a.isInstanceOf[AsyncQueryResponse])
    assertEquals(s.mode, QueryMode.Synchronous)
    assertEquals(a.mode, QueryMode.Asynchronous)
  }

  test("writing a QueryResponse never adds a $type discriminator") {
    val s = ok(RestJson.read[QueryResponse](resource("rest-query-response-sync.example.json")))
    val j = ok(RestJson.write[QueryResponse](s))
    assert(!j.contains("$type"), j)
  }

  test("rest-job-record example round-trips through Wire") {
    val json = resource("rest-job-record.example.json")
    val r    = ok(Wire.read[JobRecord](json))
    assertEquals(tree(ok(Wire.write(r))), tree(json))
  }

  test("rest-idempotency-record example round-trips through Wire") {
    val json = resource("rest-idempotency-record.example.json")
    val r    = ok(Wire.read[IdempotencyRecord](json))
    assertEquals(tree(ok(Wire.write(r))), tree(json))
  }

  test("rest-query-request example parses, passes the capability check and hashes stably") {
    val json = resource("rest-query-request.example.json")
    val r    = QueryRequest.parse(json).fold(e => fail(s"rejected: $e"), identity)
    assertEquals(QueryRequest.requestHash(r), QueryRequest.requestHash(r))
    val again = QueryRequest.parse(WirePickle.write(r)).fold(e => fail(s"rejected: $e"), identity)
    assertEquals(again, r)
  }

  // ---- field names match the course specification's REST examples ----------------------------

  test("sync response example has the spec's field names") {
    val t = tree(resource("rest-query-response-sync.example.json"))
    assertEquals(t.obj.keySet, Set("queryId", "mode", "answer", "claims", "abstained", "links"))
    assertEquals(t("mode").str, "synchronous")
    assertEquals(t("links").obj.keySet, Set("evidence", "explain"))
    val claim = t("claims")(0)
    assertEquals(
      claim.obj.keySet,
      Set("claimId", "text", "evidenceIds", "verificationStatus", "uncertainty"),
    )
    val r = t("answer")("researchers")(0).obj.keySet
    Set(
      "researcherId",
      "displayName",
      "publicationCount",
      "distinctCoauthorCount",
      "weightedDegree",
      "averageCoauthorsPerPaper",
      "maximumCoauthorsOnOnePaper",
      "maximumTeamWorkIds",
      "componentId",
      "communityId",
      "bridgeMetric",
      "claimIds",
    ).foreach(k => assert(r.contains(k), s"researcher lacks spec field $k"))
  }

  test("async response example has the spec's field names") {
    val t = tree(resource("rest-query-response-async.example.json"))
    assertEquals(t.obj.keySet, Set("queryId", "mode", "jobId", "status", "statusUrl"))
    assertEquals(t("mode").str, "asynchronous")
    assertEquals(t("status").str, "QUEUED")
  }

  test("job status example has the spec's field names and null result and error") {
    val t = tree(resource("rest-job-status.example.json"))
    Set("jobId", "queryId", "state", "stage", "createdAt", "updatedAt", "resultUrl", "error")
      .foreach(k => assert(t.obj.contains(k), s"job status lacks spec field $k"))
  }

  test("evidence example has the spec's field names") {
    val t = tree(resource("rest-evidence.example.json"))
    Set(
      "evidenceId",
      "evidenceType",
      "sourceDataset",
      "sourceRecordId",
      "snapshot",
      "text",
      "score",
    )
      .foreach(k => assert(t.obj.contains(k), s"evidence lacks spec field $k"))
    assert(t("provenance").obj.contains("textHash"))
    assert(t("provenance")("textHash").str.startsWith("sha256:"))
  }

  test("error example has exactly the spec's four fields") {
    val t = tree(resource("rest-api-error.example.json"))
    assertEquals(t.obj.keySet, Set("error", "message", "traceId", "retryable"))
  }

  test("a request example field set matches the spec's request sections") {
    val t = tree(resource("rest-query-request.example.json"))
    assertEquals(t.obj.keySet, Set("question", "constraints", "analysis", "retrieval", "output"))
    assert(t("retrieval").obj.keySet == Set("graphLimit", "vectorLimit", "maxPathDepth"))
  }

  test("the sync example's claim evidence ids and link target the same query id") {
    val t = ok(RestJson.read[SyncQueryResponse](resource("rest-query-response-sync.example.json")))
    assertEquals(t.links.evidence, s"/v1/queries/${t.queryId}/evidence")
    assertEquals(t.links.explain, s"/v1/explain/${t.queryId}")
  }

  test("every rest example resource is valid JSON with no tab or trailing garbage") {
    val names = List(
      "api-error",
      "evidence",
      "explain",
      "graph-node",
      "health",
      "idempotency-record",
      "job-record",
      "job-status",
      "neighbors-page",
      "query-request",
      "query-response-async",
      "query-response-sync",
      "readiness",
    )
    names.foreach { n =>
      val s = resource(s"rest-$n.example.json")
      assert(!s.contains('\t'), n)
      assertEquals(ujson.write(tree(s)).isEmpty, false)
    }
  }
