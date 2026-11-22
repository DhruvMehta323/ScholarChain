package scholarchain.core

import ValidationError.*

/** Phase 06 repair 01: `Ids.researcherIdForClusterV2` (canonical ID = DBLP base ID of the smallest
  * member key by Unicode code point).
  */
class ResearcherIdClusterV2ContractSuite extends ContractSupport:

  private def base(k: String): String = right(Ids.researcherIdDblpPerson(k))
  private def v2(keys: String*): Either[ValidationError, String] =
    Ids.researcherIdForClusterV2(keys)

  test("a single key gives exactly researcherIdDblpPerson of that key") {
    List("Ada Lovelace", "Zhen Wang 0008", "Z", "A. Lee").foreach { k =>
      assertEquals(v2(k), Ids.researcherIdDblpPerson(k))
    }
  }

  test("the smallest key wins and its base ID is the canonical ID") {
    assertEquals(v2("Bea", "Ann", "Cat"), Right(base("Ann")))
    assertEquals(v2("Ann Lee", "A. Lee"), Right(base("A. Lee"))) // '.' (46) < 'n' (110)
    assertNotEquals(v2("Ann Lee", "A. Lee"), Right(base("Ann Lee")))
  }

  test("uppercase sorts before lowercase (code point order, not locale order)") {
    assertEquals(v2("alice", "Zed"), Right(base("Zed")))
  }

  test("homonym suffixes: the unsuffixed key is smaller, then the smaller suffix") {
    assertEquals(v2("Zhen Wang 0008", "Zhen Wang"), Right(base("Zhen Wang")))
    assertEquals(v2("Zhen Wang 0009", "Zhen Wang 0008"), Right(base("Zhen Wang 0008")))
    assertEquals(
      v2("Zhen Wang 0009", "Zhen Wang 0008", "Zhen Wang 0010"),
      Right(base("Zhen Wang 0008")),
    )
  }

  test("order independence: every permutation of the keys gives the same ID") {
    val keys = List("Cat", "Ann 0002", "Ann", "Bea")
    val ids  = keys.permutations.map(p => Ids.researcherIdForClusterV2(p)).toSet
    assertEquals(ids, Set[Either[ValidationError, String]](Right(base("Ann"))))
  }

  test("duplicate keys are harmless") {
    assertEquals(v2("Ann", "Ann", "Bea", "Bea"), Right(base("Ann")))
    assertEquals(v2("Ann", "Ann"), Right(base("Ann")))
  }

  test("code point order differs from UTF-16 order: a supplementary char is greater than U+FF5E") {
    val bmp  = "A～"  // U+FF5E, one UTF-16 unit 0xFF5E
    val supp = "A😀" // U+1F600, UTF-16 units 0xD83D 0xDE00
    // the premise: String.compareTo (UTF-16) orders them the other way round
    assert(supp.compareTo(bmp) < 0, "test premise: UTF-16 order puts the supplementary char first")
    assert(bmp.codePointAt(1) < supp.codePointAt(1), "test premise: code point order is reversed")
    // code point order picks the BMP key; UTF-16 order would pick the supplementary key
    assertEquals(v2(bmp, supp), Right(base(bmp)))
    assertEquals(v2(supp, bmp), Right(base(bmp)))
    assertNotEquals(v2(bmp, supp), Right(base(supp)))
  }

  test("exact strings: no trimming, case folding or normalization of keys") {
    assertNotEquals(v2("Ann"), v2("Ann "))
    assertNotEquals(v2("Ann"), v2("ann"))
    assertEquals(v2("Ann ", "Ann"), Right(base("Ann"))) // "Ann" is a prefix, so smaller
  }

  test("an empty key list is a Left naming dblpPersonKeys") {
    expectErr(Ids.researcherIdForClusterV2(Nil)) { case InvalidField(f, _) =>
      assertEquals(f, "dblpPersonKeys")
    }
  }

  test("a blank key anywhere is a Left") {
    expectErr(v2("   ")) { case InvalidField(f, _) => assertEquals(f, "dblpName") }
    expectErr(v2("")) { case InvalidField(f, _) => assertEquals(f, "dblpName") }
    expectErr(v2("Ann", "")) { case InvalidField(f, _) => assertEquals(f, "dblpName") }
    expectErr(v2("", "Ann")) { case InvalidField(f, _) => assertEquals(f, "dblpName") }
    expectErr(v2("Ann", " \t ")) { case InvalidField(f, _) => assertEquals(f, "dblpName") }
  }

  test("the canonical ID is a valid researcher ID and never an ORCID reference") {
    val id = right(v2("Ann", "Bea"))
    right(Ids.checkResearcherId(id))
    assert(id.startsWith("researcher:sc1:"), id)
    assert(!id.startsWith("researcher:orcid:"))
  }

  test("the merged cluster ID is not the v1 merged-cluster ID of its members") {
    val ann = base("Ann"); val bea = base("Bea")
    assertNotEquals(v2("Ann", "Bea"), Ids.researcherIdMergedCluster(List(ann, bea)))
  }
