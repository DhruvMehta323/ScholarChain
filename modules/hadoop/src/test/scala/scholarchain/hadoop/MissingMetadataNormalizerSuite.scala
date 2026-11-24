package scholarchain.hadoop

import scholarchain.core.*
import UnitSupport.*

/** Phase 11 repair-01 F-TEST-1: records with no topic, institution/affiliation or abstract. The
  * normalizers keep the work and its mentions, emit no institution or topic rows, and never invent
  * a value. WorkRecord has no abstract field: an abstract in the source is not carried or faked.
  */
class MissingMetadataNormalizerSuite extends munit.FunSuite:

  private def env(body: String, id: String): Array[Byte] =
    s"""{"_synthetic": true, "identifier": "$id", "retrieved_at": "2026-01-01T00:00:00Z", "http_status": 200, "missing": false, "body_is_json": true, "body": $body}"""
      .getBytes(Utf8)

  private def oa(body: String) =
    normalized(Normalizers.openalex("f.json", env(body, "W9"), runCtx))
  private def cr(body: String) =
    normalized(Normalizers.crossref("f.json", env(body, "10.1000/m"), runCtx))

  test("OpenAlex: authors with empty institutions and no topics keep work and mention only") {
    val n = oa(
      """{"id":"https://openalex.org/W9","title":"T","publication_year":2020,
        |"authorships":[{"author":{"id":"https://openalex.org/A1","display_name":"Ann Lee"},"institutions":[]}]}""".stripMargin
    )
    assertEquals(rows(n, "institutions"), Nil)
    assertEquals(rows(n, "topics"), Nil)
    assertEquals(rows(n, "author-mentions").map(_("rawName").str), List("Ann Lee"))
    assertEquals(rows(n, "works").head("topicIds").arr.toList, Nil)
  }

  test("OpenAlex: institution without a display name and topic without a name are dropped") {
    val n = oa(
      """{"id":"https://openalex.org/W9","title":"T","publication_year":2020,
        |"topics":[{"id":"https://openalex.org/T5"},{"id":"https://openalex.org/T6","display_name":"  "}],
        |"authorships":[{"author":{"display_name":"Ann Lee"},
        |"institutions":[{"id":"https://openalex.org/I7","country_code":"US"}]}]}""".stripMargin
    )
    assertEquals(rows(n, "institutions"), Nil)
    assertEquals(rows(n, "topics"), Nil)
    assertEquals(rows(n, "works").head("topicIds").arr.toList, Nil)
  }

  test("OpenAlex: an institution with a bad country code keeps the row with no country") {
    val n = oa(
      """{"id":"https://openalex.org/W9","title":"T","publication_year":2020,
        |"authorships":[{"author":{"display_name":"Ann Lee"},
        |"institutions":[{"id":"https://openalex.org/I7","display_name":"Some Lab","country_code":"usa"}]}]}""".stripMargin
    )
    val i = rows(n, "institutions")
    assertEquals(i.map(_("name").str), List("Some Lab"))
    assert(i.head.obj.get("country").forall(_.isNull), i.head.render())
  }

  test("OpenAlex: an institution with no country_code keeps the row with no country") {
    val n = oa(
      """{"id":"https://openalex.org/W9","title":"T","publication_year":2020,
        |"authorships":[{"author":{"display_name":"Ann Lee"},
        |"institutions":[{"id":"https://openalex.org/I7","display_name":"Some Lab"}]}]}""".stripMargin
    )
    val i = rows(n, "institutions")
    assertEquals(i.map(_("name").str), List("Some Lab"))
    assert(i.head.obj.get("country").forall(_.isNull), i.head.render())
  }

  test("OpenAlex: an abstract in the source is not carried into the work record") {
    val n = oa(
      """{"id":"https://openalex.org/W9","title":"T","publication_year":2020,
        |"abstract_inverted_index":{"Secret":[0],"abstract":[1]}}""".stripMargin
    )
    val w = rows(n, "works").head
    assert(!w.obj.keys.exists(_.toLowerCase.contains("abstract")), w.render())
    assert(!w.render().contains("Secret"), w.render())
  }

  test("Crossref: empty affiliation list and blank affiliation names give no institution rows") {
    val n = cr(
      """{"status":"ok","message":{"DOI":"10.1000/M","title":["T"],"issued":{"date-parts":[[2019]]},
        |"abstract":"<jats:p>An abstract</jats:p>",
        |"author":[{"given":"Ann","family":"Lee","affiliation":[]},
        |{"given":"Bo","family":"Chen","affiliation":[{"name":"  "},{}]}]}}""".stripMargin
    )
    assertEquals(rows(n, "institutions"), Nil)
    assertEquals(
      rows(n, "author-mentions").map(_("rawName").str).sorted,
      List("Ann Lee", "Bo Chen"),
    )
    val w = rows(n, "works").head
    assert(!w.render().contains("An abstract"), w.render())
  }

  test("Crossref: blank subject labels give no topics and an empty topicIds list") {
    val n = cr(
      """{"status":"ok","message":{"DOI":"10.1000/M","title":["T"],"issued":{"date-parts":[[2019]]},
        |"subject":["", "   "]}}""".stripMargin
    )
    assertEquals(rows(n, "topics"), Nil)
    assertEquals(rows(n, "works").head("topicIds").arr.toList, Nil)
  }
