package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.*

import NeptuneFx.*
import StorageFx.{researcher, run1}

/** Neptune load invariants: positive checks on a fixture, plus negative controls on corrupted input
  * (the checker must DETECT a dangling endpoint, a wrong label and a changed ID).
  */
class NeptuneInvariantsSuite extends munit.FunSuite:

  private def built: NeptuneBuild = fixture.build.fold(e => fail(e.toString), identity)

  private def file(b: NeptuneBuild, path: String): CsvFile =
    b.files.find(_.relativePath == path).getOrElse(fail(s"no file $path"))

  private def parse(f: CsvFile): Vector[Vector[String]] =
    CsvFormat.parse(new String(f.bytes, StandardCharsets.UTF_8)).fold(e => fail(e), identity)

  private def check(
      vs: List[NeptuneInvariants.VertexInput],
      es: List[NeptuneInvariants.EdgeInput],
  ): InvariantReport = NeptuneInvariants.check(vs, es).fold(e => fail(e), identity)

  private def mutate(f: CsvFile)(g: String => String): CsvFile =
    f.copy(bytes = g(new String(f.bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))

  // ---- positive checks ------------------------------------------------------------------------

  test("fixture build passes every invariant, with exact row counts") {
    val b = built
    assert(b.report.ok, b.report.examples.toString)
    assertEquals(b.report.danglingEndpoints, 0L)
    assertEquals(b.report.endpointLabelMismatches, 0L)
    assertEquals(b.report.duplicateVertexIds, 0L)
    assertEquals(b.report.duplicateEdgeIds, 0L)
    assertEquals(b.report.vertexRows, Map("Researcher" -> 3L, "Work" -> 2L, "Community" -> 2L))
    assertEquals(
      b.report.edgeRows,
      Map("AUTHORED" -> 3L, "COAUTHORED_WITH" -> 2L, "MEMBER_OF" -> 3L),
    )
    assertEquals(b.files.size, 6)
  }

  test("every edge endpoint exists in the written vertex files (independent recheck)") {
    val b        = built
    val vertices = b.files.filter(_.kind == "vertex").flatMap(f => parse(f).tail.map(_(0))).toSet
    val edges    = b.files.filter(_.kind == "edge").flatMap(f => parse(f).tail)
    assertEquals(edges.size, 8)
    edges.foreach { r =>
      assert(vertices.contains(r(1)), s"dangling start ${r(1)}")
      assert(vertices.contains(r(2)), s"dangling end ${r(2)}")
    }
  }

  test("vertex IDs equal the Spark IDs byte for byte and keep the table order") {
    val b = built
    List(
      ("vertices/Researcher.csv", researchers, "researcherId"),
      ("vertices/Work.csv", works, "workId"),
      ("vertices/Community.csv", communities, "communityId"),
    ).foreach { (path, table, idCol) =>
      val csvIds   = parse(file(b, path)).tail.map(_(0))
      val sparkIds = table.rows.map(r => table.string(r, idCol).fold(e => fail(e), identity))
      assertEquals(csvIds, sparkIds, path)
      assert(
        csvIds.zip(sparkIds).forall((a, s) => a.getBytes("UTF-8").sameElements(s.getBytes("UTF-8")))
      )
    }
  }

  test("edge endpoints equal the Spark from/to columns and the edge ID equals the Spark edgeId") {
    val b    = built
    val rows = parse(file(b, "edges/COAUTHORED_WITH.csv")).tail
    val src  = coauthorEdges.rows.map(r =>
      (
        coauthorEdges.string(r, "edgeId").toOption.get,
        coauthorEdges.string(r, "researcherA").toOption.get,
        coauthorEdges.string(r, "researcherB").toOption.get,
      )
    )
    assertEquals(rows.map(r => (r(0), r(1), r(2))), src)
    assertEquals(rows.map(_(0)).toSet, Set(e1, e2))
  }

  test("Neptune IDs equal the core IDs of the researchers (core ID function, recomputed)") {
    val b   = built
    val ids = parse(file(b, "vertices/Researcher.csv")).tail.map(_(0)).toSet
    val exp = List("Ada Lovelace", "Alan Turing", "Grace Hopper")
      .map(n => Ids.researcherIdDblpPerson(n).fold(e => fail(e.message), identity))
      .toSet
    assertEquals(ids, exp)
  }

  test("derived edge IDs are deterministic, equal the documented digest and are valid nedge IDs") {
    val a = built
    val b = built
    assertEquals(
      a.files.map(f => (f.relativePath, f.sha256)),
      b.files.map(f => (f.relativePath, f.sha256)),
    )
    val ids = parse(file(a, "edges/AUTHORED.csv")).tail
    ids.foreach { r =>
      val expected = "nedge:" + Hashing.digest(NeptuneCsv.EdgeIdTag, List("AUTHORED", r(1), r(2)))
      assertEquals(r(0), expected)
    }
  }

  test("derived edge IDs of windowed edges differ across windows for the same researcher") {
    val rows = parse(file(built, "edges/MEMBER_OF.csv")).tail
    val ofR1 = rows.filter(_(1) == r1)
    assertEquals(ofR1.size, 2)
    assertNotEquals(ofR1(0)(0), ofR1(1)(0))
    assertEquals(rows.map(_(0)).distinct.size, rows.size)
  }

  test("COAUTHORED_WITH Spark edge IDs differ across windows and equal the core ID function") {
    assertNotEquals(e1, e2)
    assertEquals(
      e1,
      Ids.coauthorEdgeId(r1, r2, GraphWindow(2000, 2004), "coauthor.v1").toOption.get,
    )
    assertEquals(
      e1,
      Ids.coauthorEdgeId(r2, r1, GraphWindow(2000, 2004), "coauthor.v1").toOption.get,
    )
    assertNotEquals(
      Ids.coauthorEdgeId(r1, r2, GraphWindow(2000, 2004), "coauthor.v1").toOption.get,
      Ids.coauthorEdgeId(r1, r2, GraphWindow(2000, 2004), "coauthor.v2").toOption.get,
    )
  }

  // ---- negative controls on corrupted input ---------------------------------------------------

  test(
    "control: the checker passes the uncorrupted inputs (so the negative controls are meaningful)"
  ) {
    val (vs, es) = fixture.inputs
    assert(check(vs, es).ok)
  }

  test("negative control: a dangling edge endpoint is detected, and the build fails") {
    val ghost    = researcher("Nobody At All")
    val bad      = authored.copy(rows = authored.rows :+ Vector(Cell.S(ghost), Cell.S(w1)))
    val fx       = fixture.withTable(bad)
    val (vs, es) = fx.inputs
    val rep      = check(vs, es)
    assert(rep.danglingEndpoints >= 1L, rep.toString)
    assert(!rep.ok)
    val out = fx.build
    assert(out.isLeft)
    assert(out.left.toOption.get.exists(_.contains("invariants violated")), out.toString)
  }

  test("negative control: an edge endpoint with the wrong vertex label is detected") {
    // AUTHORED must end at a Work, here it ends at a Researcher (which exists as a vertex)
    val bad      = authored.copy(rows = Vector(Vector(Cell.S(r1), Cell.S(r2))))
    val fx       = fixture.withTable(bad)
    val (vs, es) = fx.inputs
    val rep      = check(vs, es)
    assert(rep.endpointLabelMismatches >= 1L, rep.toString)
    assertEquals(rep.danglingEndpoints, 0L)
    assert(!rep.ok)
    assert(fx.build.isLeft)
  }

  test("negative control: a changed vertex ID in the CSV is detected against the Spark ID") {
    val (vs, es) = fixture.inputs
    val tampered = vs.map(v =>
      if v.spec.label == "Work" then
        v.copy(file = mutate(v.file)(_.replace(w1, "work:dblp:conf/sigmod/Changed")))
      else v
    )
    val rep = check(tampered, es)
    assert(rep.vertexIdMismatches >= 1L, rep.toString)
    assert(!rep.ok)
  }

  test(
    "negative control: a changed edge endpoint in the CSV is detected against the Spark column"
  ) {
    val (vs, es) = fixture.inputs
    val tampered = es.map(e =>
      if e.spec.label == "AUTHORED" then
        e.copy(file = mutate(e.file)(_.replace(s",$r1,$w1,", s",$r3,$w1,")))
      else e
    )
    val rep = check(vs, tampered)
    assert(rep.edgeEndpointIdMismatches >= 1L, rep.toString)
    assertEquals(
      rep.danglingEndpoints,
      0L,
    ) // r3 is a real vertex: only the ID comparison can catch this
    assert(!rep.ok)
  }

  test("negative control: a changed edge ID in the CSV is detected") {
    val (vs, es) = fixture.inputs
    val tampered = es.map(e =>
      if e.spec.label == "COAUTHORED_WITH" then
        e.copy(file =
          mutate(e.file)(_.replaceFirst("edge:coauthor:[0-9a-f]{64}", "edge:coauthor:" + "0" * 64))
        )
      else e
    )
    val rep = check(vs, tampered)
    assert(rep.edgeIdMismatches >= 1L, rep.toString)
    assert(!rep.ok)
  }

  test("negative control: duplicate vertex IDs and a malformed researcher ID are detected") {
    val dup      = researchers.copy(rows = researchers.rows :+ researchers.rows.head)
    val (vs, es) = fixture.withTable(dup).inputs
    assert(check(vs, es).duplicateVertexIds >= 1L)
    val badShape = researchers.copy(rows =
      researchers.rows.updated(0, researchers.rows(0).updated(0, Cell.S("researcher:bogus")))
    )
    val (vs2, es2) = fixture.withTable(badShape).inputs
    val rep        = check(vs2, es2)
    assert(rep.vertexIdFormatViolations >= 1L, rep.toString)
  }

  test("negative control: a corrupted header and a corrupted label column are detected") {
    val (vs, es)  = fixture.inputs
    val badHeader = vs.map(v =>
      if v.spec.label == "Work" then
        v.copy(file = mutate(v.file)(_.replaceFirst(":LABEL", ":KIND")))
      else v
    )
    assert(check(badHeader, es).headerProblems >= 1L)
    val badLabel = vs.map(v =>
      if v.spec.label == "Work" then v.copy(file = mutate(v.file)(_.replace(",Work,", ",Paper,")))
      else v
    )
    assert(check(badLabel, es).labelColumnMismatches >= 1L)
  }

  // ---- loader failures ------------------------------------------------------------------------

  test("the loader fails on a manifest row-count mismatch and names the table") {
    val fx  = fixture.withTableKeepingManifest(works.copy(rows = works.rows.take(1)))
    val out = fx.build
    assert(out.isLeft)
    assert(
      out.left.toOption.get.exists(m => m.contains("works") && m.contains("manifest says 2")),
      out.toString,
    )
  }

  test("the loader fails when a table is missing, and when the manifest lists a label twice") {
    val missing = fixture.copy(tables = fixture.tables - "works")
    assert(missing.build.isLeft)
    val twice = fixture.copy(manifest =
      fixture.manifest.copy(vertices = fixture.manifest.vertices :+ workSpec)
    )
    val out = twice.build
    assert(out.isLeft)
    assert(out.left.toOption.get.exists(_.contains("twice")), out.toString)
  }

  test("the loader fails when an edge table lacks a manifest column") {
    val noCol = coauthorEdges.copy(
      columns = coauthorEdges.columns.filterNot(_.name == "evidenceKey"),
      rows = coauthorEdges.rows.map(_.take(11) :+ Cell.S(win1)),
    )
    val out = fixture.withTable(noCol).build
    assert(out.isLeft, out.toString)
  }

  // ---- bulk-load request ----------------------------------------------------------------------

  private val loadCfg = StorageNeptuneLoad(
    "arn:aws:iam::000000000000:role/placeholder",
    "opencypher",
    "s3://placeholder-bucket/graph/neptune-load/",
  )

  private def req(uri: String, format: String = "opencypher") = BulkLoadRequest(uri, format, run1)

  test("bulk-load request: built from configuration, with idempotent defaults") {
    val r = NeptuneBulkLoad
      .buildRequest(loadCfg, req("s3://placeholder-bucket/graph/neptune-load/L1/"), "us-east-1")
      .fold(e => fail(e.toString), identity)
    assertEquals(r.iamRoleArn, loadCfg.roleArn)
    assertEquals(r.format, "opencypher")
    assertEquals(r.mode, "AUTO")
    assert(r.failOnError)
    assert(!r.updateSingleCardinalityProperties)
    assert(!r.queueRequest)
  }

  test(
    "bulk-load request: foreign bucket, other prefix, '..', wrong format and bad region are refused"
  ) {
    def bad(r: BulkLoadRequest, region: String) =
      NeptuneBulkLoad.buildRequest(loadCfg, r, region)
    assert(
      bad(req("s3://other-bucket/graph/neptune-load/"), "us-east-1").left
        .exists(_.isInstanceOf[StorageError.InvalidRequest])
    )
    assert(bad(req("s3://placeholder-bucket/elsewhere/"), "us-east-1").isLeft)
    assert(bad(req("s3://placeholder-bucket/graph/neptune-load/../x/"), "us-east-1").isLeft)
    assert(
      bad(req("s3://placeholder-bucket/graph/neptune-load/x"), "us-east-1").isLeft
    ) // no trailing slash
    assert(bad(req("s3://placeholder-bucket/graph/neptune-load/", "csv"), "us-east-1").isLeft)
    assert(bad(req("s3://placeholder-bucket/graph/neptune-load/"), "not a region").isLeft)
  }

  test("bulk-load submit goes through the fake loader only when the request is valid") {
    val loader = FakeBulkLoader()
    val store  = TemplateGraphStore(
      QueryBounds(6, 100, 5000L),
      loadCfg,
      "us-east-1",
      RecordingCypherExecutor(),
      loader,
    )
    assert(store.submitBulkLoad(req("s3://evil/x/")).isLeft)
    assertEquals(loader.requests, Nil)
    val h = store.submitBulkLoad(req("s3://placeholder-bucket/graph/neptune-load/L1/"))
    assert(h.isRight)
    assertEquals(loader.requests.size, 1)
    assertEquals(store.bulkLoadStatus(h.toOption.get), Right(BulkLoadStatus.Completed(None)))
  }
