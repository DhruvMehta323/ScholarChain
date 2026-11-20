package scholarchain.hadoop

import scholarchain.core.*
import UnitSupport.*

/** UNIT tests of the Crossref, OpenAlex and ORCID normalizers on SYNTHETIC envelopes. */
class NormalizerUnitSuite extends munit.FunSuite:

  private def env(body: String, id: String, extra: String = ""): Array[Byte] =
    s"""{"_synthetic": true, "identifier": "$id", "retrieved_at": "2026-01-01T00:00:00Z", "http_status": 200, "missing": false, "body_is_json": true$extra, "body": $body}"""
      .getBytes(Utf8)

  private def cr(f: String) = Normalizers.crossref(f, resource(s"crossref/$f"), runCtx)
  private def oa(f: String) = Normalizers.openalex(f, resource(s"openalex/$f"), runCtx)
  private def or(f: String) = Normalizers.orcid(f, resource(s"orcid/$f"), runCtx)

  test("unit: Crossref happy path yields work, mentions, institution, topic and id refs") {
    val n = normalized(cr("cr-happy-unit.json"))
    val w = rows(n, "works").head
    assertEquals(w("workId").str, "work:crossref:10.1000/unit.cr1")
    assertEquals(w("title").str, "Unit Title & More")
    assertEquals(w("year").num.toInt, 2021)
    assertEquals(w("doi").str, "10.1000/unit.cr1")
    assertEquals(
      rows(n, "author-mentions").map(_("rawName").str).sorted,
      List("Consortium Unit", "Una Test"),
    )
    assertEquals(rows(n, "institutions").map(_("name").str), List("Synthetic Institute"))
    assertEquals(rows(n, "topics").map(_("label").str), List("Unit Topic"))
    val refs = rows(n, "id-refs").map(r => (r("idType").str, r("value").str))
    assert(refs.contains(("doi", "10.1000/unit.cr1")), refs.toString)
    assert(refs.contains(("orcid", "0000-0002-1825-0097")), refs.toString)
  }

  test("unit: Crossref preserves the source id and the retrieved_at / file checksum") {
    val bytes = resource("crossref/cr-happy-unit.json")
    val n     = normalized(Normalizers.crossref("cr-happy-unit.json", bytes, runCtx))
    assertEquals(n.source, SourceType.Crossref)
    assertEquals(n.sourceRecordId, "10.1000/unit.cr1")
    assertEquals(n.retrievedAt, "2026-01-01T00:00:00Z")
    assertEquals(n.fileSha256, Hashing.sha256Hex(bytes))
    val p = Wire.read[WorkRecord](rows(n, "works").head.render()).toOption.get.provenance
    assertEquals(p.runId, Some(runCtx.runId))
    assertEquals(p.codeVersion, "unit-test")
    assert(p.inputs.exists(_.checksum.contains(Hashing.sha256Hex(bytes))), p.inputs.toString)
  }

  test("unit: Crossref with missing fields (no authors, no subject) still gives a work") {
    val n = normalized(
      Normalizers.crossref(
        "f.json",
        env(
          """{"status":"ok","message":{"DOI":"10.1000/MIN","title":["Min"],"issued":{"date-parts":[[2019]]}}}""",
          "10.1000/min",
        ),
        runCtx,
      )
    )
    assertEquals(rows(n, "works").map(_("year").num.toInt), List(2019))
    assertEquals(rows(n, "author-mentions"), Nil)
    assertEquals(rows(n, "topics"), Nil)
    assertEquals(n.yearMissing, false)
  }

  test(
    "unit: Crossref missing-year rule: kept for identifiers and mentions, flagged, no WorkRecord"
  ) {
    val n = normalized(cr("cr-noyear-unit.json"))
    assert(n.yearMissing)
    assertEquals(rows(n, "works"), Nil)
    assertEquals(rows(n, "author-mentions").map(_("rawName").str), List("Una Test"))
    assertEquals(rows(n, "id-refs").map(_("value").str), List("10.1000/unit.cr2"))
  }

  test("unit: Crossref malformed envelopes are rejected with the documented reasons") {
    def r(b: Array[Byte]) = reason(Normalizers.crossref("f.json", b, runCtx))
    assertEquals(r("not json at all".getBytes(Utf8)), Some(RejectReason.MalformedRecord))
    assertEquals(
      r("""{"retrieved_at":"yesterday","body":{}}""".getBytes(Utf8)),
      Some(RejectReason.SchemaViolation),
    )
    assertEquals(r(env("""{"status":"ok"}""", "x")), Some(RejectReason.MalformedRecord))
    assertEquals(
      r(env("""{"message":{"DOI":"not-a-doi","title":["T"]}}""", "x")),
      Some(RejectReason.InvalidIdentifier),
    )
    assertEquals(
      r(env("""{"message":{"DOI":"10.1000/x","title":[]}}""", "x")),
      Some(RejectReason.MissingTitle),
    )
  }

  test("unit: a missing:true envelope is an upstream-missing marker, not a record") {
    val bytes =
      """{"_synthetic": true, "identifier": "10.1000/gone", "retrieved_at": "2026-01-01T00:00:00Z", "http_status": 404, "missing": true, "body_is_json": false, "body": null}"""
    val n = normalized(Normalizers.crossref("f.json", bytes.getBytes(Utf8), runCtx))
    assert(n.upstreamMissing)
    assertEquals(n.rows, Nil)
  }

  test("unit: OpenAlex happy path yields work, mention with ORCID, institution and topic") {
    val n = normalized(oa("oa-happy-unit.json"))
    assertEquals(n.sourceRecordId, "W1000001")
    val w = rows(n, "works").head
    assertEquals(w("workId").str, "work:openalex:W1000001")
    assertEquals(w("year").num.toInt, 2022)
    assertEquals(w("doi").str, "10.1000/unit.oa1")
    val m = rows(n, "author-mentions").head
    assertEquals(m("rawName").str, "Olga Test")
    assertEquals(m("orcid").str, "0000-0002-1825-0097")
    assertEquals(
      rows(n, "institutions").map(i => (i("institutionId").str, i("country").str)),
      List(("institution:openalex:I100", "US")),
    )
    assertEquals(rows(n, "topics").map(_("topicId").str), List("topic:openalex:T10"))
    val refs = rows(n, "id-refs").map(r => (r("idType").str, r("value").str))
    assert(refs.contains(("openalex", "W1000001")), refs.toString)
    assert(refs.contains(("openalex", "A5000001")), "author id is kept as an id reference")
  }

  test("unit: OpenAlex missing fields: no authors, no year (kept, flagged, no WorkRecord)") {
    val n = normalized(
      Normalizers.openalex(
        "f.json",
        env("""{"id":"https://openalex.org/W7","title":"Only Title"}""", "W7"),
        runCtx,
      )
    )
    assert(n.yearMissing)
    assertEquals(rows(n, "works"), Nil)
    assertEquals(rows(n, "author-mentions"), Nil)
    assertEquals(rows(n, "id-refs").map(_("value").str), List("W7"))
  }

  test("unit: OpenAlex malformed envelopes are rejected with the documented reasons") {
    def r(b: Array[Byte]) = reason(Normalizers.openalex("f.json", b, runCtx))
    assertEquals(r("[1,2".getBytes(Utf8)), Some(RejectReason.MalformedRecord))
    assertEquals(r(env("""{"title":"T"}""", "x")), Some(RejectReason.InvalidIdentifier))
    assertEquals(
      r(env("""{"id":"https://openalex.org/W7"}""", "x")),
      Some(RejectReason.MissingTitle),
    )
    assertEquals(r(env("""{"id":"junk","title":"T"}""", "x")), Some(RejectReason.InvalidIdentifier))
  }

  test("unit: ORCID happy path: a work with DOI, plus the iD itself as an id reference") {
    val n = normalized(or("or-happy-unit.json"))
    assertEquals(n.sourceRecordId, "0000-0002-1825-0097")
    val w = rows(n, "works").head
    assertEquals(w("workId").str, "work:doi:10.1000/unit.or1")
    assertEquals(w("title").str, "ORCID Unit Work")
    assertEquals(w("year").num.toInt, 2020)
    val refs = rows(n, "id-refs").map(r => (r("idType").str, r("value").str))
    assert(refs.contains(("orcid", "0000-0002-1825-0097")), refs.toString)
    assert(refs.contains(("doi", "10.1000/unit.or1")), refs.toString)
  }

  test(
    "unit: ORCID with missing fields: an empty group list keeps only the iD; a year-less work is skipped"
  ) {
    val empty = normalized(
      Normalizers.orcid("f.json", env("""{"group":[]}""", "0000-0002-1825-0097"), runCtx)
    )
    assertEquals(rows(empty, "works"), Nil)
    assertEquals(rows(empty, "id-refs").map(_("idType").str), List("orcid"))
    val noYear = normalized(
      Normalizers.orcid(
        "f.json",
        env(
          """{"group":[{"work-summary":[{"title":{"title":{"value":"No Year"}}}]}]}""",
          "0000-0002-1825-0097",
        ),
        runCtx,
      )
    )
    assertEquals(rows(noYear, "works"), Nil)
  }

  test("unit: ORCID malformed envelopes are rejected (bad iD, missing iD, not JSON)") {
    def r(b: Array[Byte]) = reason(Normalizers.orcid("f.json", b, runCtx))
    assertEquals(
      r(env("""{"group":[]}""", "0000-0002-1825-0098")),
      Some(RejectReason.InvalidIdentifier),
    )
    assertEquals(
      r("""{"retrieved_at":"2026-01-01T00:00:00Z","body":{"group":[]}}""".getBytes(Utf8)),
      Some(RejectReason.InvalidIdentifier),
    )
    assertEquals(r("<html>".getBytes(Utf8)), Some(RejectReason.MalformedRecord))
  }
