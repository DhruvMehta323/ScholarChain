package scholarchain.services

import scholarchain.core.rest.*

import ApiHarness.*

/** Phase 11 repair-01 F-TEST-1: the local fixtures hold researchers with no institution, no
  * affiliation edge and no profile text. The API must serve them without a 5xx and without
  * fabricating those fields.
  */
class MissingMetadataApiSuite extends munit.FunSuite:

  private val server = new Server(base)

  override def afterAll(): Unit = server.stop()

  private lazy val l4 = LocalFixturePaths
    .default(java.nio.file.Files.createTempDirectory("scholarchain-mm"))
    .l4

  private lazy val researcher: String =
    LocalCsv.read(l4.resolve("neptune/vertices/Researcher.csv")).map(_(":ID")).sorted.head

  test("a researcher node with no institution is served with no affiliation or abstract property") {
    val r = server.get(s"/v1/graph/nodes/${RestPaths.encode(researcher)}")
    assertEquals(r.status, 200, r.body)
    assert(RestJson.read[GraphNodeView](r.body).isRight, r.body)
    val keys = r.json("properties").obj.keys.map(_.toLowerCase).toList
    assert(
      !keys.exists(k =>
        k.contains("institution") || k.contains("affiliat") || k.contains("abstract")
      ),
      keys.toString,
    )
  }

  test("no neighbor of a researcher is reached through an AFFILIATED_WITH edge") {
    val r =
      server.get(s"/v1/graph/nodes/${RestPaths.encode(researcher)}/neighbors?depth=3&limit=200")
    assertEquals(r.status, 200, r.body)
    val items = r.json("items").arr
    assert(items.nonEmpty, s"expected neighbors for $researcher: ${r.body}")
    items.foreach(i => assert(!i("viaEdge").toString.contains("AFFILIATED_WITH"), i.toString))
  }

  test("an institution constraint is not a 5xx; the answer carries no invented affiliation") {
    val body = ujson.write(
      ujson.Obj(
        "question"    -> "Which researchers are affiliated with Example Institute?",
        "constraints" -> ujson.Obj("institutions" -> ujson.Arr("example-institute")),
      )
    )
    val r = server.post("/v1/query", body)
    assertEquals(r.status, 200, r.body)
    assert(RestJson.read[SyncQueryResponse](r.body).isRight, r.body)
    // The constraint cannot be satisfied (no affiliation data): no researcher may be returned
    // as a match, and no claim may be verified. The observable signal is an empty researcher
    // list with no verified claim (or an explicit abstention with its reason).
    val resp = RestJson.read[SyncQueryResponse](r.body).toOption.get
    assert(
      resp.answer.researchers.isEmpty,
      s"researchers returned for unsatisfiable constraint: ${r.body}",
    )
    assert(resp.claims.forall(_.verificationStatus != ClaimStatus.Verified), r.body)
    assert(!resp.answer.summary.toLowerCase.contains("affiliated with example"), r.body)
    if resp.abstained then assert(resp.abstainReason.exists(_.nonEmpty), r.body)
  }
