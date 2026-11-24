package scholarchain.rag.runtime

import scholarchain.core.*

/** Phase 11 repair-01 F-TEST-1: no institution/affiliation data exists in the graph (Phase 06
  * produced no AFFILIATED_WITH edges). A question that filters by institution, topic or venue must
  * state the gap as missing evidence, issue no graph query, and never fabricate an affiliation.
  */
class MissingMetadataAnswerSuite extends munit.FunSuite:
  import World.*

  private def run(filters: QueryFilters, q: String) =
    val env = World.env()
    val out = env.workflow.run(
      QueryRequest(
        q,
        StructuredConstraints(operations = List(QueryOperationV2.EntityFilter), filters = filters),
      )
    )
    (env, out)

  test("an institution filter reports the gap as missing evidence and runs no graph query") {
    val (env, out) = run(QueryFilters(institution = Some("mit")), "Who is affiliated with mit?")
    assertEquals(out.error, None)
    assert(
      out.missingEvidence.exists(_.contains("cannot filter by topic, venue or institution")),
      out.missingEvidence.toString,
    )
    assert(env.graph.queries.isEmpty)
  }

  test("an institution filter abstains or answers without any affiliation claim") {
    val (_, out) = run(QueryFilters(institution = Some("mit")), "Who is affiliated with mit?")
    val text     = out.answerText.toLowerCase
    assert(out.abstained || !text.contains("affiliated with"), out.answerText)
    assert(!text.contains("works at") && !text.contains("employed"), out.answerText)
    out.verification.foreach(
      _.kept.foreach(c => assert(!c.text.toLowerCase.split("[^a-z0-9]+").contains("mit"), c.text))
    )
  }

  test("a venue filter with no semantic context is a recorded gap, not a crash") {
    val (_, out) = run(QueryFilters(venue = Some("vldb")), "Which papers appear at vldb?")
    assertEquals(out.error, None)
    assert(
      out.missingEvidence.exists(_.contains("cannot filter by topic, venue or institution")),
      out.missingEvidence.toString,
    )
  }
