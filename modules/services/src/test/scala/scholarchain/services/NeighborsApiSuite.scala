package scholarchain.services

import scholarchain.core.rest.*

import ApiHarness.*

/** Phase 10 C category 3: bounded neighbors over HTTP (depth, limit, edge types, cursor round trip,
  * foreign and tampered cursors).
  */
class NeighborsApiSuite extends munit.FunSuite:

  private val server = new Server(base)

  override def afterAll(): Unit = server.stop()

  private lazy val node: String =
    val csv = LocalCsv.read(
      LocalFixturePaths
        .default(java.nio.file.Files.createTempDirectory("scholarchain-nb"))
        .l4
        .resolve("neptune/edges/COAUTHORED_WITH.csv")
    )
    csv.map(_(":START_ID")).distinct.take(30).maxBy(n => total(n))

  private def total(n: String): Int =
    val r = server.get(s"/v1/graph/nodes/${RestPaths.encode(n)}/neighbors?depth=3&limit=200")
    if r.status == 200 then r.json("items").arr.size else 0

  private def nb(q: String): Reply =
    server.get(s"/v1/graph/nodes/${RestPaths.encode(node)}/neighbors$q")

  test("the fixture node has at least three neighbors within depth 3") {
    assert(total(node) >= 3, s"neighbors: ${total(node)}")
  }

  test("default page is 200 and a valid NeighborsPage") {
    val r = nb("")
    assertEquals(r.status, 200, r.body)
    val p = RestJson.read[NeighborsPage](r.body).fold(e => fail(e.toString), identity)
    assertEquals((p.depth, p.limit), (1, 50))
  }

  test("depth 4 and depth 0 are 400") {
    assertEquals(nb("?depth=4").status, 400)
    assertEquals(nb("?depth=0").status, 400)
  }

  test("limit 0 and limit 201 are 400") {
    assertEquals(nb("?limit=0").status, 400)
    assertEquals(nb("?limit=201").status, 400)
  }

  test("an unknown query parameter is 400") {
    assertEquals(nb("?unbounded=true").status, 400)
  }

  test("a well-formed edge type outside the allowlist is 422") {
    assertEquals(nb("?edgeTypes=FRIENDS_WITH").status, 422)
  }

  test("an edge type that is allowed but not stored returns an empty page") {
    val r = nb("?edgeTypes=HAS_TOPIC&depth=3")
    assertEquals(r.status, 200, r.body)
    assertEquals(r.json("items").arr.size, 0)
  }

  test("COAUTHORED_WITH only returns the same items as no filter") {
    val a = nb("?depth=2&limit=200")
    val b = nb("?depth=2&limit=200&edgeTypes=COAUTHORED_WITH")
    assertEquals(b.json("items"), a.json("items"))
  }

  test("limit bounds the page size and every item is within the depth bound") {
    val r = nb("?depth=3&limit=2")
    assertEquals(r.status, 200, r.body)
    val items = r.json("items").arr.toList
    assert(items.size <= 2)
    items.foreach(i => assert(i("distance").num.toInt <= 3 && i("distance").num.toInt >= 1))
  }

  test("items are ordered by distance then node id") {
    val items = nb("?depth=3&limit=200").json("items").arr.toList
    val keys  = items.map(i => (i("distance").num.toInt, i("node")("nodeId").str))
    assertEquals(keys, keys.sorted)
  }

  test("cursor round trip: pages are disjoint and together equal the full list") {
    val all   = nb("?depth=3&limit=200").json("items").arr.map(_("node")("nodeId").str).toList
    val seen  = scala.collection.mutable.ListBuffer.empty[String]
    var q     = "?depth=3&limit=2"
    var pages = 0
    var more  = true
    while more && pages < 200 do
      val r = nb(q)
      assertEquals(r.status, 200, r.body)
      seen ++= r.json("items").arr.map(_("node")("nodeId").str)
      r.json.obj.get("nextCursor").filter(_ != ujson.Null) match
        case Some(c) => q = s"?depth=3&limit=2&cursor=${java.net.URLEncoder.encode(c.str, "UTF-8")}"
        case None    => more = false
      pages += 1
    assertEquals(seen.toList, all)
    assertEquals(seen.distinct.size, seen.size)
    assert(pages >= 2, s"pages: $pages")
  }

  test("the last page has no nextCursor") {
    val r = nb("?depth=3&limit=200")
    assert(r.json.obj.get("nextCursor").forall(_ == ujson.Null), r.body)
  }

  test("a cursor from a different depth is rejected with 400") {
    val c = nb("?depth=3&limit=1").json("nextCursor").str
    val r = nb(s"?depth=2&limit=1&cursor=${java.net.URLEncoder.encode(c, "UTF-8")}")
    assertEquals(r.status, 400, r.body)
  }

  test("a cursor from a different node is rejected with 400") {
    val c     = nb("?depth=3&limit=1").json("nextCursor").str
    val other = LocalCsv
      .read(
        LocalFixturePaths
          .default(java.nio.file.Files.createTempDirectory("scholarchain-nb2"))
          .l4
          .resolve("neptune/edges/COAUTHORED_WITH.csv")
      )
      .map(_(":START_ID"))
      .find(_ != node)
      .get
    val r = server.get(
      s"/v1/graph/nodes/${RestPaths.encode(other)}/neighbors?depth=3&limit=1&cursor=${java.net.URLEncoder.encode(c, "UTF-8")}"
    )
    assertEquals(r.status, 400, r.body)
  }

  test("garbage, oversized and re-encoded cursors are 400") {
    assertEquals(nb("?cursor=not-a-cursor").status, 400)
    assertEquals(nb("?cursor=" + "A" * 600).status, 400)
    val forged = NeighborCursor("researcher:sc1:" + "9" * 64, "0" * 64).encode
    assertEquals(nb(s"?cursor=$forged").status, 400)
  }

  test("neighbors of an unknown node is 404") {
    val r = server.get(s"/v1/graph/nodes/researcher:sc1:${"0" * 64}/neighbors")
    assertEquals(r.status, 404, r.body)
  }

  test("neighbors needs the API key") {
    assertEquals(
      server.get(s"/v1/graph/nodes/${RestPaths.encode(node)}/neighbors", auth = false).status,
      401,
    )
  }
