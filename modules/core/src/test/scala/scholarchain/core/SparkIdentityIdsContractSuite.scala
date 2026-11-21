package scholarchain.core

import ValidationError.*
import Fx.{get, scId, scId2}

/** Phase 06 researcher identity functions: DBLP person key, merged cluster, cluster rule. */
class SparkIdentityIdsContractSuite extends ContractSupport:

  private val OrcA  = "0000-0002-1825-0097"
  private val OrcB  = "0000-0001-5109-3700"
  private val scId3 = "researcher:sc1:" + "3" * 64

  // ---- researcherIdDblpPerson -----------------------------------------------------------------

  test("researcherIdDblpPerson is deterministic and a valid researcher id") {
    val a = right(Ids.researcherIdDblpPerson("Zhen Wang"))
    assertEquals(Ids.researcherIdDblpPerson("Zhen Wang"), Right(a))
    right(Ids.checkResearcherId(a))
    assert(a.startsWith("researcher:sc1:"), a)
  }

  test("researcherIdDblpPerson separates homonym suffixes") {
    val ids = List("Zhen Wang", "Zhen Wang 0001", "Zhen Wang 0008").map(n =>
      right(Ids.researcherIdDblpPerson(n))
    )
    assertEquals(ids.distinct.size, 3)
  }

  test("researcherIdDblpPerson is exact: no trimming, case folding or normalization") {
    val base = right(Ids.researcherIdDblpPerson("Zhen Wang"))
    assertNotEquals(right(Ids.researcherIdDblpPerson("zhen wang")), base)
    assertNotEquals(right(Ids.researcherIdDblpPerson(" Zhen Wang")), base)
    assertNotEquals(right(Ids.researcherIdDblpPerson("Zhen  Wang")), base)
  }

  test("researcherIdDblpPerson rejects a blank name") {
    expectErr(Ids.researcherIdDblpPerson("")) { case InvalidField("dblpName", _) => () }
    expectErr(Ids.researcherIdDblpPerson("   ")) { case InvalidField("dblpName", _) => () }
  }

  test("researcherIdDblpPerson never collides with the unresolved scheme") {
    val m       = get(Ids.mentionId("work:dblp:conf/x/A01", 0))
    val unres   = right(Ids.researcherIdUnresolved(m))
    val person  = right(Ids.researcherIdDblpPerson(m))
    val generic = right(Ids.researcherIdScholarChain(s"unresolved.v1:$m"))
    assertNotEquals(person, unres)
    assertEquals(unres, generic)
    assertEquals(person, right(Ids.researcherIdScholarChain(s"dblp-person.v1:$m")))
  }

  // ---- researcherIdMergedCluster --------------------------------------------------------------

  test("researcherIdMergedCluster is order independent and ignores duplicates") {
    val a = right(Ids.researcherIdMergedCluster(List(scId, scId2, scId3)))
    assertEquals(Ids.researcherIdMergedCluster(List(scId3, scId, scId2)), Right(a))
    assertEquals(Ids.researcherIdMergedCluster(List(scId2, scId3, scId, scId, scId2)), Right(a))
    right(Ids.checkResearcherId(a))
  }

  test("researcherIdMergedCluster rejects fewer than two distinct base ids") {
    expectErr(Ids.researcherIdMergedCluster(Nil)) { case InvalidField("baseIds", _) => () }
    expectErr(Ids.researcherIdMergedCluster(List(scId))) { case InvalidField("baseIds", _) => () }
    expectErr(Ids.researcherIdMergedCluster(List(scId, scId))) { case InvalidField("baseIds", _) =>
      ()
    }
  }

  test("researcherIdMergedCluster rejects a malformed base id and differs per member set") {
    expectErr(Ids.researcherIdMergedCluster(List(scId, "researcher:nope"))) {
      case MalformedId("researcher", _) => ()
    }
    assertNotEquals(
      right(Ids.researcherIdMergedCluster(List(scId, scId2))),
      right(Ids.researcherIdMergedCluster(List(scId, scId3))),
    )
  }

  // ---- researcherIdForCluster -----------------------------------------------------------------

  test("researcherIdForCluster: exactly one ORCID gives the ORCID id") {
    assertEquals(
      Ids.researcherIdForCluster(List(scId, scId2), List(OrcA)),
      Right("researcher:orcid:" + OrcA),
    )
    // the same ORCID repeated (or in URL form) is still one distinct ORCID
    assertEquals(
      Ids.researcherIdForCluster(List(scId), List(OrcA, OrcA)),
      Right("researcher:orcid:" + OrcA),
    )
    assertEquals(
      Ids.researcherIdForCluster(List(scId), List(OrcA, "https://orcid.org/" + OrcA)),
      Right("researcher:orcid:" + OrcA),
    )
  }

  test("researcherIdForCluster: two different ORCIDs is a Left (M5)") {
    expectErr(Ids.researcherIdForCluster(List(scId, scId2), List(OrcA, OrcB))) {
      case InvalidField("orcids", _) => ()
    }
  }

  test("researcherIdForCluster: no ORCID gives the single base id or the merged id") {
    assertEquals(Ids.researcherIdForCluster(List(scId), Nil), Right(scId))
    assertEquals(
      Ids.researcherIdForCluster(List(scId, scId2), Nil),
      Ids.researcherIdMergedCluster(List(scId, scId2)),
    )
    assertEquals(
      Ids.researcherIdForCluster(List(scId2, scId), Nil),
      Ids.researcherIdForCluster(List(scId, scId2), Nil),
    )
  }

  test("researcherIdForCluster rejects empty base ids and an invalid ORCID") {
    expectErr(Ids.researcherIdForCluster(Nil, List(OrcA))) { case InvalidField("baseIds", _) =>
      ()
    }
    expectErr(Ids.researcherIdForCluster(List(scId), List("0000-0002-1825-0098"))) {
      case InvalidOrcid(_, _) => ()
    }
    expectErr(Ids.researcherIdForCluster(List(scId), List("not-an-orcid"))) {
      case InvalidOrcid(_, _) => ()
    }
  }
