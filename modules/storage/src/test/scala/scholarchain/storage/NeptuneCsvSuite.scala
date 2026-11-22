package scholarchain.storage

import java.nio.charset.StandardCharsets

import NeptuneFx.*

/** Neptune openCypher CSV: header and type syntax, RFC 4180 escaping, evidence pointer. */
class NeptuneCsvSuite extends munit.FunSuite:

  private def text(f: CsvFile): String = new String(f.bytes, StandardCharsets.UTF_8)

  private def records(f: CsvFile): Vector[Vector[String]] =
    CsvFormat.parse(text(f)) match
      case Right(r) => r
      case Left(e)  => fail(s"CSV did not parse: $e")

  private def vertexCsv(spec: VertexSpec, t: TableData): CsvFile =
    NeptuneCsv.vertexFile(spec, t).fold(e => fail(e), identity)

  private def edgeCsv(spec: EdgeSpec, t: TableData): CsvFile =
    NeptuneCsv.edgeFile(spec, t).fold(e => fail(e), identity)

  test("vertex CSV header: :ID, :LABEL, then name:Type columns with Neptune type names") {
    val f = vertexCsv(researcherSpec, researchers)
    assertEquals(
      text(f).linesIterator.next(),
      ":ID,:LABEL,displayName:String,orcids:String,paperCount:Int,score:Double,active:Bool",
    )
    assertEquals(f.relativePath, "vertices/Researcher.csv")
    assertEquals(f.rows, 3L)
  }

  test("vertex CSV: arrays are one JSON String property (no Type[] columns)") {
    val f = vertexCsv(researcherSpec, researchers)
    assert(!text(f).linesIterator.next().contains("[]"))
    val rows = records(f)
    assertEquals(rows(1)(3), """["0000-0002-1825-0097"]""")
    assertEquals(rows(2)(3), "[]")
  }

  test("vertex CSV: comma, quote and newline are RFC 4180 escaped and parse back unchanged") {
    val f = vertexCsv(researcherSpec, researchers)
    assert(text(f).contains(""""Ada, ""Countess"" Lovelace""""))
    val rows = records(f)
    assertEquals(rows.size, 4) // header + 3
    assertEquals(rows(1)(2), """Ada, "Countess" Lovelace""")
    assertEquals(rows(3)(2), "Grace\nHopper")
  }

  test("vertex CSV: null cells are empty fields; ints, doubles and bools are rendered plainly") {
    val rows = records(vertexCsv(researcherSpec, researchers))
    assertEquals(rows(3).drop(3), Vector("", "2", "", "true")) // orcids null, score null
    assertEquals(rows(1).drop(4), Vector("3", "1.5", "true"))
    assertEquals(rows(2).drop(4), Vector("1", "0.25", "false"))
  }

  test("vertex CSV: every row has the same number of fields as the header") {
    val rows = records(vertexCsv(researcherSpec, researchers))
    assert(rows.forall(_.size == rows.head.size), rows.map(_.size).toString)
  }

  test("edge CSV header: :ID, :START_ID, :END_ID, :TYPE then COAUTHORED_WITH properties") {
    val f = edgeCsv(coauthorSpec, coauthorEdges)
    assertEquals(
      text(f).linesIterator.next(),
      ":ID,:START_ID,:END_ID,:TYPE,window:String,jointPublicationCount:Int," +
        "firstCollaborationYear:Int,lastCollaborationYear:Int,supportingWorksDigest:String," +
        "supportingWorksTruncated:Bool,representativeWorkIds:String,evidenceTable:String," +
        "evidenceKey:String,evidencePartition:String",
    )
    assertEquals(f.relativePath, "edges/COAUTHORED_WITH.csv")
  }

  test("COAUTHORED_WITH rows: properties are carried and the evidence pointer is edgeId + window") {
    val rows                              = records(edgeCsv(coauthorSpec, coauthorEdges))
    val header                            = rows.head
    def get(r: Vector[String], n: String) = r(header.indexWhere(_.startsWith(n + ":")))
    val body                              = rows.tail
    assertEquals(body.size, 2)
    body.foreach { r =>
      assertEquals(r(3), "COAUTHORED_WITH")
      assertEquals(get(r, "evidenceTable"), "coauthor-edge-works")
      assertEquals(get(r, "evidenceKey"), r(0), "evidenceKey must equal the edge ID")
      assertEquals(get(r, "evidencePartition"), get(r, "window"), "partition must equal the window")
    }
    val first = body.find(_(0) == e1).get
    assertEquals(get(first, "window"), win1)
    assertEquals(get(first, "jointPublicationCount"), "2")
    assertEquals(get(first, "firstCollaborationYear"), "2001")
    assertEquals(get(first, "lastCollaborationYear"), "2003")
    assertEquals(get(first, "supportingWorksTruncated"), "false")
    assertEquals(get(first, "representativeWorkIds"), s"""["$w1","$w2"]""")
    assertEquals(get(body.find(_(0) == e2).get, "supportingWorksTruncated"), "true")
  }

  test("edge CSV: an edge without a Spark ID gets a derived nedge: ID and keeps its endpoints") {
    val rows = records(edgeCsv(authoredSpec, authored)).tail
    assertEquals(rows.size, 3)
    assert(rows.forall(_(0).startsWith("nedge:")))
    assertEquals(rows.map(r => (r(1), r(2))).toSet, Set((r1, w1), (r2, w1), (r2, w2)))
    assert(rows.forall(_(3) == "AUTHORED"))
  }

  test("CsvFormat: RFC 4180 escape and parse round trip over awkward fields") {
    val cases = List(
      Vector("plain", "with,comma"),
      Vector("say \"hi\"", "line1\nline2"),
      Vector("crlf\r\ninside", "trailing space "),
      Vector(" leading", "unicode é中文"),
      Vector("", ""),
      Vector("\"", "\"\""),
      Vector("a", "", "c"),
      Vector(",", "\n", "\r"),
    )
    cases.foreach { fields =>
      val line = CsvFormat.line(fields)
      assertEquals(CsvFormat.parse(line), Right(Vector(fields)), s"for $fields")
    }
    val many = cases.map(CsvFormat.line).mkString
    assertEquals(CsvFormat.parse(many), Right(cases.toVector))
  }

  test("CsvFormat.escape: quotes only when needed and doubles embedded quotes") {
    assertEquals(CsvFormat.escape("abc"), "abc")
    assertEquals(CsvFormat.escape("a,b"), "\"a,b\"")
    assertEquals(CsvFormat.escape("a\"b"), "\"a\"\"b\"")
    assertEquals(CsvFormat.escape(" x"), "\" x\"")
    assertEquals(CsvFormat.line(Seq("a", "b")), "a,b\n")
  }

  test("CsvFormat.parse accepts CRLF and rejects unterminated or stray quotes") {
    assertEquals(
      CsvFormat.parse("a,b\r\nc,d\r\n"),
      Right(Vector(Vector("a", "b"), Vector("c", "d"))),
    )
    assert(CsvFormat.parse("\"abc").isLeft)
    assert(CsvFormat.parse("ab\"c,d\n").isLeft)
    assert(CsvFormat.parse("\"ab\"c,d\n").isLeft)
  }

  test(
    "NeptuneCsv.cellText: control characters are rejected, tab is allowed, doubles use Neptune spellings"
  ) {
    val p = PropCol("x", PropType.Str, false, 0)
    assert(NeptuneCsv.cellText(Cell.S("a\u0001b"), p).isLeft)
    assertEquals(NeptuneCsv.cellText(Cell.S("a\tb"), p), Right("a\tb"))
    val d = PropCol("d", PropType.Dbl, false, 0)
    assertEquals(NeptuneCsv.cellText(Cell.D(Double.NaN), d), Right("NaN"))
    assertEquals(NeptuneCsv.cellText(Cell.D(Double.PositiveInfinity), d), Right("Infinity"))
    assertEquals(NeptuneCsv.cellText(Cell.D(Double.NegativeInfinity), d), Right("-Infinity"))
    assert(NeptuneCsv.cellText(Cell.Arr(List(Cell.S("a"))), p).isLeft) // nested but not JSON typed
  }

  test("PropType.of maps Spark types to Neptune types and flags JSON-typed columns") {
    assertEquals(PropType.of("string").map(_._1.header), Right("String"))
    assertEquals(PropType.of("int").map(_._1.header), Right("Int"))
    assertEquals(PropType.of("bigint").map(_._1.header), Right("Long"))
    assertEquals(PropType.of("double").map(_._1.header), Right("Double"))
    assertEquals(PropType.of("boolean").map(_._1.header), Right("Bool"))
    assertEquals(
      PropType.of("array<string>").map(r => (r._1.header, r._2)),
      Right(("String", true)),
    )
    assertEquals(PropType.of("map<string,int>").map(_._2), Right(true))
    assert(PropType.of("timestamp").isLeft)
  }

  test(
    "NeptuneCsv rejects an invalid property column name, an empty vertex ID and an empty endpoint"
  ) {
    val badName =
      researchers.copy(columns = researchers.columns.updated(1, Column("bad name", "string")))
    val spec = researcherSpec.copy(propertyColumns = List("bad name"))
    assert(NeptuneCsv.vertexFile(spec, badName).isLeft)
    val emptyId = researchers.copy(rows =
      researchers.rows.updated(0, researchers.rows(0).updated(0, Cell.S("")))
    )
    assert(NeptuneCsv.vertexFile(researcherSpec, emptyId).isLeft)
    val emptyEnd = authored.copy(rows = Vector(Vector(Cell.S(r1), Cell.S(""))))
    assert(NeptuneCsv.edgeFile(authoredSpec, emptyEnd).isLeft)
    val dupProp = researcherSpec.copy(propertyColumns = List("displayName", "displayName"))
    assert(NeptuneCsv.vertexFile(dupProp, researchers).isLeft)
  }

  // ---- M6: lineage columns (loadRunId, sparkRunId, provenanceDigest) -----------------------------

  private val LoadRun  = "run:" + "1" * 64
  private val SparkRun = "run:" + "2" * 64

  private def sha(text: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def tableDigest(t: String): String = sha(s"table-provenance-file:$t")

  private val lineage: Lineage =
    Lineage(LoadRun, SparkRun, ref => Right(tableDigest(ref.table)))

  private val LineageHeaders =
    Vector("loadRunId:String", "sparkRunId:String", "provenanceDigest:String")

  private def vertexLin(spec: VertexSpec, t: TableData): CsvFile =
    NeptuneCsv.vertexFile(spec, t, Some(lineage)).fold(e => fail(e), identity)

  private def edgeLin(spec: EdgeSpec, t: TableData): CsvFile =
    NeptuneCsv.edgeFile(spec, t, Some(lineage)).fold(e => fail(e), identity)

  test("lineage: every vertex header ends with the three lineage columns, every row carries them") {
    List((researcherSpec, researchers), (workSpec, works), (communitySpec, communities)).foreach {
      (spec, t) =>
        val rows = records(vertexLin(spec, t))
        assertEquals(rows.head.takeRight(3), LineageHeaders, spec.label)
        assert(rows.forall(_.size == rows.head.size), spec.label)
        assertEquals(rows.size, t.rows.size + 1)
        rows.tail.foreach { r =>
          assertEquals(r.takeRight(3), Vector(LoadRun, SparkRun, tableDigest(t.name)), spec.label)
        }
    }
  }

  test("lineage: every edge header ends with the three lineage columns, every row carries them") {
    List(
      (authoredSpec, authored),
      (coauthorSpec, coauthorEdges),
      (memberSpec, membership),
    ).foreach { (spec, t) =>
      val rows = records(edgeLin(spec, t))
      assertEquals(rows.head.takeRight(3), LineageHeaders, spec.label)
      assert(rows.forall(_.size == rows.head.size), spec.label)
      rows.tail.foreach(r =>
        assertEquals(r.takeRight(3), Vector(LoadRun, SparkRun, tableDigest(t.name)), spec.label)
      )
    }
  }

  test(
    "lineage: the lineage columns are appended after the manifest properties, which are unchanged"
  ) {
    val plain = records(edgeCsv(coauthorSpec, coauthorEdges))
    val lin   = records(edgeLin(coauthorSpec, coauthorEdges))
    assertEquals(lin.map(_.dropRight(3)), plain)
    assertEquals(lin.head.size, plain.head.size + 3)
  }

  test("lineage = None: the output is byte-identical to the call without the argument") {
    List(researcherSpec -> researchers, workSpec -> works).foreach { (spec, t) =>
      val a = NeptuneCsv.vertexFile(spec, t).fold(e => fail(e), identity)
      val b = NeptuneCsv.vertexFile(spec, t, None).fold(e => fail(e), identity)
      assertEquals(a.sha256, b.sha256)
      assertEquals(text(a), text(b))
      assert(!text(a).contains("loadRunId"), "no lineage column without lineage")
    }
    val ea = NeptuneCsv.edgeFile(coauthorSpec, coauthorEdges).fold(e => fail(e), identity)
    val eb = NeptuneCsv.edgeFile(coauthorSpec, coauthorEdges, None).fold(e => fail(e), identity)
    assertEquals(text(ea), text(eb))
    assert(!text(ea).contains("provenanceDigest"))
    // and with lineage the bytes differ (the columns are really added)
    assertNotEquals(edgeLin(coauthorSpec, coauthorEdges).sha256, ea.sha256)
  }

  private val provA = """{"runId":"run:a","step":"one"}"""
  private val provB = """{"runId":"run:a","step":"two"}"""

  private val withProv: TableData = TableData(
    "authored",
    Vector(
      Column("researcherId", "string"),
      Column("workId", "string"),
      Column("provenance", "string"),
    ),
    Vector(
      Vector(Cell.S(r1), Cell.S(w1), Cell.S(provA)),
      Vector(Cell.S(r2), Cell.S(w1), Cell.S(provB)),
      Vector(Cell.S(r2), Cell.S(w2), Cell.S(provA)),
    ),
  )

  test(
    "lineage: a table with a row-level provenance column uses the SHA-256 of that row's provenance"
  ) {
    val rows = records(edgeLin(authoredSpec, withProv)).tail
    assertEquals(rows.map(_.last), Vector(sha(provA), sha(provB), sha(provA)))
    assert(rows.forall(r => r(r.size - 3) == LoadRun && r(r.size - 2) == SparkRun))
    // the table-level digest is NOT used for such a table
    assert(!rows.exists(_.last == tableDigest("authored")))
    // the provenance text itself is not copied into the CSV (only its digest)
    assert(!text(edgeLin(authoredSpec, withProv)).contains("step"))
  }

  test("lineage: a vertex table with a row-level provenance column uses the row digest too") {
    val t = TableData(
      "works",
      Vector(Column("workId", "string"), Column("title", "string"), Column("provenance", "string")),
      Vector(Vector(Cell.S(w1), Cell.S("T"), Cell.S(provA))),
    )
    val spec = workSpec.copy(propertyColumns = List("title"))
    val rows = records(vertexLin(spec, t))
    assertEquals(rows(1).last, sha(provA))
  }

  test("lineage: a null or non-string row provenance is an error, not a silent empty digest") {
    val bad = withProv.copy(rows = Vector(Vector(Cell.S(r1), Cell.S(w1), Cell.Null)))
    assert(NeptuneCsv.edgeFile(authoredSpec, bad, Some(lineage)).isLeft)
    val num = withProv.copy(rows = Vector(Vector(Cell.S(r1), Cell.S(w1), Cell.I(3))))
    assert(NeptuneCsv.edgeFile(authoredSpec, num, Some(lineage)).isLeft)
  }

  test("lineage: a missing table provenance digest is an error") {
    val missing =
      Lineage(LoadRun, SparkRun, ref => Left(s"${ref.table}: no provenance file digest"))
    val r = NeptuneCsv.vertexFile(researcherSpec, researchers, Some(missing))
    assert(r.left.exists(_.contains("no provenance file digest")), r.toString)
  }

  test("lineage: a property column that uses a lineage name is rejected (vertex and edge)") {
    Lineage.Names.foreach { n =>
      val vt = researchers.copy(columns = researchers.columns.updated(1, Column(n, "string")))
      val vs = researcherSpec.copy(propertyColumns = List(n))
      assert(NeptuneCsv.vertexFile(vs, vt, Some(lineage)).isLeft, s"vertex $n")
      val et = authored.copy(columns = authored.columns.updated(1, Column(n, "string")))
      val es = authoredSpec.copy(
        propertyColumns = List(n),
        toColumn = n,
        keyColumns = List("researcherId", n),
      )
      assert(NeptuneCsv.edgeFile(es, et, Some(lineage)).isLeft, s"edge $n")
      // without lineage the same name is an ordinary property
      assert(NeptuneCsv.vertexFile(vs, vt).isRight, s"vertex without lineage $n")
      assert(NeptuneCsv.edgeFile(es, et).isRight, s"edge without lineage $n")
    }
    assertEquals(Lineage.Names, List("loadRunId", "sparkRunId", "provenanceDigest"))
    assertEquals(Lineage.Headers, LineageHeaders.toList)
  }

  test(
    "lineage: the invariants still hold and every row count is unchanged (NeptuneLoader.build)"
  ) {
    val plain = fixture.build.fold(e => fail(e.toString), identity)
    val lin   = NeptuneLoader
      .build(fixture.manifest, fixture.source, Some(lineage))
      .fold(e => fail(e.toString), identity)
    assert(lin.report.ok, lin.report.toString)
    assertEquals(lin.report.danglingEndpoints, 0L)
    assertEquals(lin.report.duplicateVertexIds, 0L)
    assertEquals(lin.report.duplicateEdgeIds, 0L)
    assertEquals(lin.report.headerProblems, 0L)
    assertEquals(
      lin.files.map(f => (f.relativePath, f.rows)),
      plain.files.map(f => (f.relativePath, f.rows)),
    )
    lin.files.foreach(f =>
      assertEquals(records(f).head.takeRight(3), LineageHeaders, f.relativePath)
    )
    assertEquals(lin.report.vertexRows, plain.report.vertexRows)
    assertEquals(lin.report.edgeRows, plain.report.edgeRows)
  }

  test("lineage: a lineage-less build is byte-identical to the build without the argument") {
    val a =
      NeptuneLoader.build(fixture.manifest, fixture.source).fold(e => fail(e.toString), identity)
    val b = NeptuneLoader
      .build(fixture.manifest, fixture.source, None)
      .fold(e => fail(e.toString), identity)
    assertEquals(
      a.files.map(f => (f.relativePath, f.sha256)),
      b.files.map(f => (f.relativePath, f.sha256)),
    )
  }
