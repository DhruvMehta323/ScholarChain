package scholarchain.core.rest

import scala.io.Source

import scholarchain.core.*

/** Phase 10 repair R4: the `abstained` / `abstainReason` rules of `SyncQueryResponse`. A failure
  * here is a production defect in the core REST contracts (owner domain-engineer).
  */
class AbstainedContractSuite extends munit.FunSuite:

  private def example: ujson.Value =
    val in =
      getClass.getClassLoader.getResourceAsStream("contracts/rest-query-response-sync.example.json")
    assert(in != null, "missing sync example")
    val s = Source.fromInputStream(in, "UTF-8")
    try ujson.read(s.mkString.trim)
    finally s.close()

  private def withFields(abstained: Boolean, reason: Option[String]): String =
    val j = example
    j("abstained") = abstained
    j.obj.remove("abstainReason")
    reason.foreach(r => j("abstainReason") = r)
    ujson.write(j)

  test("abstained=true without a reason is rejected") {
    val r = RestJson.read[SyncQueryResponse](withFields(true, None))
    assert(r.isLeft, r.toString)
  }

  test("abstained=false with a reason is rejected") {
    val r = RestJson.read[SyncQueryResponse](withFields(false, Some("no_evidence")))
    assert(r.isLeft, r.toString)
  }

  test("abstained=true with a non-token reason is rejected") {
    List("has spaces", "", "UPPER CASE!", "a" * 65, "semi;colon").foreach { bad =>
      val r = RestJson.read[SyncQueryResponse](withFields(true, Some(bad)))
      assert(r.isLeft, s"reason '$bad' was accepted")
    }
  }

  test("abstained=true with a token reason is accepted and round-trips") {
    val json = withFields(true, Some("no_verified_claims"))
    val r    = RestJson.read[SyncQueryResponse](json).fold(e => fail(s"rejected: $e"), identity)
    assertEquals(r.abstained, true)
    assertEquals(r.abstainReason, Some("no_verified_claims"))
    val back = RestJson.write(r).fold(e => fail(s"write failed: $e"), identity)
    assertEquals(ujson.read(back)("abstainReason").str, "no_verified_claims")
  }

  test("abstained=false is always on the wire and abstainReason is omitted") {
    val resp = QueryResponse.sync(
      "query:" + "b" * 64,
      AnswerView("summary", Nil),
      Nil,
    )
    assertEquals(resp.abstained, false)
    val json = RestJson.write(resp).fold(e => fail(s"write failed: $e"), identity)
    val t    = ujson.read(json)
    assert(t.obj.contains("abstained"), json)
    assertEquals(t("abstained").bool, false)
    assert(!t.obj.contains("abstainReason"), json)
  }

  test("the factory with abstained=true and no reason fails contract validation on write") {
    val resp = QueryResponse.sync("query:" + "b" * 64, AnswerView("summary", Nil), Nil, true, None)
    assert(RestJson.write(resp).isLeft)
  }
