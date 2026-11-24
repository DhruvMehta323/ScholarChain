package scholarchain.core.rest

import scala.util.chaining.*

import scholarchain.core.*

/** Phase 10 C: bounded neighbors parameters and the cursor (core side). */
class NeighborsBoundsContractSuite extends munit.FunSuite:

  private val node = "researcher:sc1:" + "a" * 64

  private def parse(kv: (String, String)*) =
    NeighborsRequest.fromParams(node, kv.toList.groupMap(_._1)(_._2))

  private def code(e: Either[RestRejection, NeighborsRequest]): Int =
    e.fold(_.httpStatus, _ => 200)

  test("defaults are depth 1, limit 50, direction both, no edge filter") {
    val r = parse().fold(e => fail(e.toString), identity)
    assertEquals((r.depth, r.limit, r.direction), (1, 50, NeighborDirection.Both))
    assertEquals(r.edgeTypes, Nil)
    assertEquals(r.effectiveEdgeTypes.size, 5)
  }

  test("depth 0 and depth 4 are 400, depth 3 is accepted") {
    assertEquals(code(parse("depth" -> "0")), 400)
    assertEquals(code(parse("depth" -> "4")), 400)
    assertEquals(code(parse("depth" -> "3")), 200)
  }

  test("limit 0 and limit 201 are 400, limit 200 is accepted") {
    assertEquals(code(parse("limit" -> "0")), 400)
    assertEquals(code(parse("limit" -> "201")), 400)
    assertEquals(code(parse("limit" -> "200")), 200)
  }

  test("non-digit depth and repeated parameter are 400") {
    assertEquals(code(parse("depth" -> "x")), 400)
    assertEquals(code(parse("depth" -> "1", "depth" -> "2")), 400)
  }

  test("unknown parameter and unknown direction are 400") {
    assertEquals(code(parse("bogus" -> "1")), 400)
    assertEquals(code(parse("direction" -> "sideways")), 400)
  }

  test("a well-formed edge type outside the allowlist is 422, a lower-case one is 400") {
    assertEquals(code(parse("edgeTypes" -> "FRIENDS_WITH")), 422)
    assertEquals(code(parse("edgeTypes" -> "coauthored_with")), 400)
    assertEquals(code(parse("edgeTypes" -> "COAUTHORED_WITH,COAUTHORED_WITH")), 400)
  }

  test("a cursor round-trips and binds to its request scope") {
    val a = parse("depth" -> "2").fold(e => fail(e.toString), identity)
    val c = NeighborCursor("researcher:sc1:" + "b" * 64, a.scopeHash).encode
    assert(c.length <= RestLimits.MaxCursorLength)
    val withCursor = parse("depth" -> "2", "cursor" -> c).fold(e => fail(e.toString), identity)
    assertEquals(
      withCursor.decodedCursor.map(_.map(_.afterKey)),
      Right(Some("researcher:sc1:" + "b" * 64)),
    )
  }

  test("a cursor from another scope is rejected with 400") {
    val a     = parse("depth" -> "2").fold(e => fail(e.toString), identity)
    val c     = NeighborCursor("researcher:sc1:" + "b" * 64, a.scopeHash).encode
    val other = parse("depth" -> "3", "cursor" -> c).fold(e => fail(e.toString), identity)
    assertEquals(other.decodedCursor.left.map(_.httpStatus), Left(400))
  }

  test("tampered, non-base64 and oversized cursors are rejected") {
    assert(NeighborCursor.decode("not a cursor!").isLeft)
    assert(NeighborCursor.decode("e30").isLeft) // base64 of {}
    assert(NeighborCursor.decode("A" * 513).isLeft)
    assert(
      NeighborCursor("researcher:sc1:" + "b" * 64, "zz").encode.pipe(NeighborCursor.decode).isLeft
    )
  }
