package scholarchain.er

import scholarchain.core.*

import ErFx.*

/** Phase 11 repair-01 F-TEST-1: missing institution (affiliation) and venue metadata. An absent or
  * blank value is an ABSENT feature (never faked, never a penalty); the policy treats it as
  * neutral.
  */
class MissingMetadataSuite extends munit.FunSuite:
  private val A = person("mention:a", SourceType.Dblp, "Ann Lee", authors = List("Bo Chen"))
  private val B = person("mention:b", SourceType.Crossref, "Ann Lee", authors = List("Bo Chen"))

  test("affiliation present on one side only: affiliation_compat is absent") {
    val x = Features.researcher(A.copy(affiliation = Some("University of Oxford")), B)
    assert(!x.contains("affiliation_compat"), x.toString)
    val y = Features.researcher(A, B.copy(affiliation = Some("University of Oxford")))
    assert(!y.contains("affiliation_compat"), y.toString)
  }

  test("blank or whitespace-only affiliation on either side is treated as missing") {
    List(Some(""), Some("   "), Some("\t\n")).foreach { blank =>
      val x = Features.researcher(
        A.copy(affiliation = blank),
        B.copy(affiliation = Some("University of Oxford")),
      )
      assert(!x.contains("affiliation_compat"), s"$blank: $x")
      val both = Features.researcher(A.copy(affiliation = blank), B.copy(affiliation = blank))
      assert(!both.contains("affiliation_compat"), s"$blank: $both")
    }
  }

  test("blank venue on one side is absent, never a zero-similarity penalty") {
    val x = Features.researcher(A.copy(venue = Some("")), B.copy(venue = Some("VLDB")))
    assert(!x.contains("venue_compat"), x.toString)
    val y = Features.researcher(A, B.copy(venue = Some("VLDB")))
    assert(!y.contains("venue_compat"), y.toString)
  }

  test(
    "one-sided affiliation scores exactly as absent on both sides; two-sided affiliation does not"
  ) {
    val absent   = Features.researcher(A, B)
    val oneSided = Features.researcher(A.copy(affiliation = Some("MIT")), B)
    assertEquals(oneSided, absent)
    val scoreAbsent = Policy.score(EntityKind.Researcher, absent)
    assert(close(Policy.score(EntityKind.Researcher, oneSided), scoreAbsent))
    // control: a real two-sided mismatch yields the feature and moves the score
    val twoSided =
      Features.researcher(
        A.copy(affiliation = Some("MIT")),
        B.copy(affiliation = Some("University of Oxford")),
      )
    assert(twoSided.contains("affiliation_compat"), twoSided.toString)
    assert(!close(Policy.score(EntityKind.Researcher, twoSided), scoreAbsent), twoSided.toString)
  }

  test("an empty feature map scores 0.5") {
    assert(close(Policy.score(EntityKind.Researcher, Map.empty), 0.5))
  }

  test(
    "a work pair with no authors, DOI or venue still yields features without venue or author ones"
  ) {
    val x = Features.work(
      paper("work:a", SourceType.Dblp, "Deep Learning"),
      paper("work:b", SourceType.Crossref, "Deep Learning"),
    )
    assertEquals(x("title_exact"), 1.0)
    assert(!x.contains("venue_compat"), x.toString)
    assert(!x.contains("author_overlap"), x.toString)
  }
