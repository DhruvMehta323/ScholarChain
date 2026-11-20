package scholarchain.core

import ValidationError.*
import Fx.*

class WindowAndPairContractSuite extends ContractSupport:

  test("publication year: four-digit bounds are inclusive") {
    List(1000, 2001, 9999).foreach(y => assertEquals(PublicationYear.validate(y), Right(y)))
    List(999, 10000, 0).foreach(y =>
      assertEquals(PublicationYear.validate(y), Left(InvalidYear(y)))
    )
  }

  test("window: valid, including a single-year window") {
    assertEquals(GraphWindow.validate(GraphWindow(2000, 2004)), Right(GraphWindow(2000, 2004)))
    assertEquals(GraphWindow.validate(GraphWindow(2004, 2004)), Right(GraphWindow(2004, 2004)))
  }

  test("window: start after end is InvalidWindow") {
    assertEquals(GraphWindow.validate(GraphWindow(2005, 2000)), Left(InvalidWindow(2005, 2000)))
  }

  test("window: out-of-range years are InvalidYear naming the offending year") {
    assertEquals(GraphWindow.validate(GraphWindow(999, 2000)), Left(InvalidYear(999)))
    assertEquals(GraphWindow.validate(GraphWindow(2000, 10000)), Left(InvalidYear(10000)))
  }

  test("window label") {
    assertEquals(GraphWindow.label(GraphWindow(2000, 2004)), "2000-2004")
  }

  test("pair: canonical is order independent and ascending") {
    val p1 = right(OrderedPair.canonical(orcidId, scId))
    val p2 = right(OrderedPair.canonical(scId, orcidId))
    assertEquals(p1, p2)
    assert(p1.first.compareTo(p1.second) < 0)
    assertEquals(p1.first, orcidId)
    assertEquals(p1.second, scId)
  }

  test("pair: self-pair and malformed ids are rejected") {
    expectErr(OrderedPair.canonical(orcidId, orcidId)) { case InvalidPair(_, _, "self-pair") => () }
    assertEquals(OrderedPair.canonical(orcidId, "junk"), Left(MalformedId("researcher", "junk")))
  }

  test("pair: validate enforces canonical order and rejects self-pairs") {
    expectErr(OrderedPair.validate(OrderedPair(scId, orcidId))) {
      case InvalidPair(_, _, "not in canonical order") => ()
    }
    expectErr(OrderedPair.validate(OrderedPair(orcidId, orcidId))) {
      case InvalidPair(_, _, "self-pair") => ()
    }
    assertEquals(
      OrderedPair.validate(OrderedPair(orcidId, scId)),
      Right(OrderedPair(orcidId, scId)),
    )
  }

  test("team: ascending distinct researchers of at least two are valid") {
    assertEquals(validate(team), Right(team))
  }

  test("team: a single researcher, unsorted ids and duplicates are rejected") {
    expectErr(validate(team.copy(researcherIds = List(orcidId)))) {
      case InvalidField("researcherIds.size", _) => ()
    }
    expectErr(validate(team.copy(researcherIds = List(scId, orcidId)))) {
      case InvalidField("researcherIds", _) => ()
    }
    expectErr(validate(team.copy(researcherIds = List(orcidId, orcidId)))) {
      case InvalidField("researcherIds", _) => ()
    }
  }

  test("candidate and coauthor edge: a reversed pair is rejected") {
    val rev = OrderedPair(scId, orcidId)
    expectErr(validate(candidate.copy(pair = rev))) {
      case InvalidPair(_, _, "not in canonical order") => ()
    }
    expectErr(validate(coauthorEdge.copy(pair = rev))) {
      case InvalidPair(_, _, "not in canonical order") => ()
    }
    assertEquals(validate(candidate), Right(candidate))
  }
