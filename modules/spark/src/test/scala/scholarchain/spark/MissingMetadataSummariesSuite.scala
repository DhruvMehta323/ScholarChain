package scholarchain.spark

/** Phase 11 repair-01 F-TEST-1: the fixture has works with no topic (W03), no DOI, and no
  * affiliation data at all. Summaries must exist for every researcher, and none may mention
  * institutions, affiliations or abstracts (nothing is invented).
  */
class MissingMetadataSummariesSuite extends munit.FunSuite:
  import FixtureRun.rows

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  test("every researcher has a profile although no institution or affiliation data exists") {
    val profiles = rows("semantic-summaries").filter(_.s("documentType") == "researcher_profile")
    assertEquals(profiles.size, 29)
    assert(profiles.forall(_.s("text").nonEmpty))
  }

  test("no summary text mentions an institution, affiliation or abstract") {
    val bad = rows("semantic-summaries").filter { r =>
      val t = r.s("text").toLowerCase
      t.contains("institution") || t.contains("affiliat") || t.contains("abstract")
    }
    assertEquals(bad.map(_.s("entityId")), Nil)
  }

  test("works with no topic produce no topic row or topic summary") {
    val noTopic = rows("works").filter(_.strs("topicIds").isEmpty).map(_.s("workId")).toSet
    assert(noTopic.nonEmpty, "fixture must contain works without topics")
    val linked = rows("has-topic").map(_.s("workId")).toSet
    assert(noTopic.intersect(linked).isEmpty)
    val topicSummaries = rows("semantic-summaries").filter(_.s("documentType") == "topic_summary")
    assertEquals(topicSummaries.size, rows("topics").size)
  }
