package scholarchain.hadoop

import scholarchain.core.*
import UnitSupport.*

/** UNIT tests of HJ3 classification. The mapper and reducer are driven in-process (no job). */
class ExactIdentifierUnitSuite extends munit.FunSuite:
  private val Doi1 = "10.1000/unit.x"

  private def classify(key: String, refs: List[String]): Reduced =
    reduce(new IdentifierReducer, refs.map(r => (key, r)))

  private def outcome(r: Reduced): List[String] =
    r.table("exact-identifier-evidence").map((_, j) => ujson.read(j)("outcome").str)

  test("unit: EXACT_LINK: a DBLP work and a Crossref record share a DOI") {
    val r = classify(
      s"doi|$Doi1",
      List(
        idRef("doi", Doi1, "work:dblp:journals/unit/a", SourceType.Dblp),
        idRef("doi", Doi1, s"work:crossref:$Doi1", SourceType.Crossref),
      ),
    )
    assertEquals(outcome(r), List("exact_link"))
    assertEquals(r.count(PipelineCounter.EXACT_LINK), 1L)
    val ev = ujson.read(r.table("exact-identifier-evidence").head._2)
    assertEquals(ev("normalizedIdentifier").str, Doi1)
    assertEquals(
      ev("linkedRecordIds").arr.map(_.str).toList,
      List("work:crossref:" + Doi1, "work:dblp:journals/unit/a"),
    )
  }

  test("unit: DUPLICATE: two records of one source share a DOI") {
    val r = classify(
      s"doi|$Doi1",
      List(
        idRef("doi", Doi1, "work:crossref:a", SourceType.Crossref),
        idRef("doi", Doi1, "work:crossref:b", SourceType.Crossref),
      ),
    )
    assertEquals(outcome(r), List("duplicate"))
    assertEquals(r.count(PipelineCounter.DUPLICATE), 1L)
  }

  test("unit: CONFLICT: one DOI on two distinct DBLP works") {
    val r = classify(
      s"doi|$Doi1",
      List(
        idRef("doi", Doi1, "work:dblp:journals/unit/a", SourceType.Dblp),
        idRef("doi", Doi1, "work:dblp:journals/unit/b", SourceType.Dblp),
      ),
    )
    assertEquals(outcome(r), List("conflict"))
    assertEquals(r.count(PipelineCounter.CONFLICT), 1L)
  }

  test("unit: CONFLICT outranks EXACT_LINK when linked records disagree on another identifier") {
    val r = classify(
      s"doi|$Doi1",
      List(
        idRef(
          "doi",
          Doi1,
          "work:dblp:journals/unit/a",
          SourceType.Dblp,
          Map("doi" -> Doi1, "arxiv" -> "2101.00001"),
        ),
        idRef(
          "doi",
          Doi1,
          s"work:crossref:$Doi1",
          SourceType.Crossref,
          Map("doi" -> Doi1, "arxiv" -> "2101.99999"),
        ),
      ),
    )
    assertEquals(outcome(r), List("conflict"))
  }

  test("unit: a key with fewer than two distinct records writes no evidence") {
    val one = idRef("doi", Doi1, "work:dblp:journals/unit/a", SourceType.Dblp)
    val r   = classify(s"doi|$Doi1", List(one, one))
    assertEquals(r.table("exact-identifier-evidence"), Nil)
    assertEquals(r.count(PipelineCounter.ACCEPTED), 0L)
  }

  test("unit: MISSING_KEY: a work with no external id is counted and emits nothing") {
    val none =
      WirePickle.write(IdRef("none", "-", "work:dblp:journals/unit/a", SourceType.Dblp, Map.empty))
    val doi                 = idRef("doi", Doi1, "work:dblp:journals/unit/b", SourceType.Dblp)
    val (emitted, counters) = mapLines(
      new IdentifierMapper,
      List(s"none|-|work:dblp:journals/unit/a\t$none", s"doi|$Doi1|x\t$doi"),
    )
    assertEquals(counters.get("MISSING_KEY"), Some(1L))
    assertEquals(counters.get("INPUT_RECORDS"), Some(2L))
    assertEquals(emitted.map(_._1), List(s"doi|$Doi1"))
  }

  test("unit: ORCID key: one iD on several mentions of one source is DUPLICATE, not a conflict") {
    val o = "0000-0002-1825-0097"
    val r = classify(
      s"orcid|$o",
      List(
        idRef("orcid", o, "mention:" + "1" * 64, SourceType.Dblp),
        idRef("orcid", o, "mention:" + "2" * 64, SourceType.Dblp),
      ),
    )
    assertEquals(outcome(r), List("duplicate"))
  }
