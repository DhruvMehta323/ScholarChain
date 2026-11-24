package scholarchain.services

import scholarchain.core.rest.*

import ApiHarness.*

/** Phase 10 C category 6: one end-to-end API test over HTTP (query-api on port 0, local fixtures)
  * that reaches an answer with VERIFIED claims whose evidence ids resolve through the evidence
  * route.
  */
class EndToEndApiSuite extends munit.FunSuite:

  private val server = new Server(base)

  override def afterAll(): Unit = server.stop()

  test("end to end: a VERIFIED claim's evidence ids all resolve via GET /v1/evidence/{id}") {
    val r = server.post("/v1/query", query(Q1))
    assertEquals(r.status, 200, r.body)
    val resp     = RestJson.read[SyncQueryResponse](r.body).fold(e => fail(e.toString), identity)
    val verified = resp.claims.filter(_.verificationStatus == ClaimStatus.Verified)
    assert(verified.nonEmpty, s"no VERIFIED claim: ${r.body}")
    verified.foreach { c =>
      assert(c.evidenceIds.nonEmpty, s"VERIFIED claim ${c.claimId} cites no evidence")
      c.evidenceIds.foreach { id =>
        val ev = server.get(s"/v1/evidence/${RestPaths.encode(id)}")
        assertEquals(ev.status, 200, s"evidence $id: ${ev.body}")
        assertEquals(ev.json("evidenceId").str, id)
        assert(ev.json("provenance")("textHash").str.startsWith("sha256:"))
      }
    }
    assert(resp.answer.summary.nonEmpty)
  }
