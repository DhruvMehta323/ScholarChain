package scholarchain.er

import ErFx.close

class MetricsSuite extends munit.FunSuite:
  test("jaro: MARTHA / MARHTA = (1 + 1 + 5/6) / 3") {
    assert(close(Metrics.jaro("martha", "marhta"), (1.0 + 1.0 + 5.0 / 6.0) / 3.0))
  }

  test("jaro-winkler: MARTHA / MARHTA gets a prefix boost of 3 characters") {
    val j = (1.0 + 1.0 + 5.0 / 6.0) / 3.0
    assert(close(Metrics.jaroWinkler("martha", "marhta"), j + 3 * 0.1 * (1 - j)))
  }

  test("jaro: DIXON / DICKSONX = (4/5 + 4/8 + 1) / 3 and jaro-winkler prefix 2") {
    val j = (0.8 + 0.5 + 1.0) / 3.0
    assert(close(Metrics.jaro("dixon", "dicksonx"), j))
    assert(close(Metrics.jaroWinkler("dixon", "dicksonx"), j + 2 * 0.1 * (1 - j)))
  }

  test("jaro edge cases: equal, both empty, one empty, disjoint") {
    assertEquals(Metrics.jaro("abc", "abc"), 1.0)
    assertEquals(Metrics.jaro("", ""), 1.0)
    assertEquals(Metrics.jaro("", "a"), 0.0)
    assertEquals(Metrics.jaro("abc", "xyz"), 0.0)
  }

  test("jaro-winkler adds no boost when jaro is 0.7 or below") {
    assertEquals(Metrics.jaroWinkler("abc", "xyz"), 0.0)
  }

  test("trigramDice: night / nacht shares 2 of 6 + 6 trigrams = 1/3") {
    assert(close(Metrics.trigramDice("night", "nacht"), 2.0 * 2 / 12))
  }

  test("trigramDice: equal strings 1.0, an empty side 0.0") {
    assertEquals(Metrics.trigramDice("graph", "graph"), 1.0)
    assertEquals(Metrics.trigramDice("", "graph"), 0.0)
    assertEquals(Metrics.trigramDice("graph", ""), 0.0)
  }

  test("jaccard and overlap coefficient on {a,b,c} and {b,c,d}") {
    val x = Set("a", "b", "c")
    val y = Set("b", "c", "d")
    assertEquals(Metrics.jaccard(x, y), 0.5)
    assert(close(Metrics.overlapCoefficient(x, y), 2.0 / 3.0))
    assertEquals(Metrics.jaccard(Set.empty[String], Set.empty[String]), 0.0)
    assertEquals(Metrics.overlapCoefficient(x, Set.empty[String]), 0.0)
  }
