package scholarchain.core

import munit.FunSuite

class CoreContractSuite extends FunSuite:

  test("ScholarChain.Name is the stable identifier 'scholarchain'") {
    assertEquals(ScholarChain.Name, "scholarchain")
  }

  test("ScholarChain.Name is non-empty and lowercase") {
    assert(ScholarChain.Name.nonEmpty)
    assertEquals(ScholarChain.Name, ScholarChain.Name.toLowerCase)
  }
