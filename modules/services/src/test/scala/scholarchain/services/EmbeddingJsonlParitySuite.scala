package scholarchain.services

import java.nio.charset.StandardCharsets

import scholarchain.core.*
import scholarchain.storage.*

/** `EmbeddingJsonl` (the Spark-free production reader of the `LocalLoad` JSONL) against the
  * test-scope `ParquetInput` (the reference): the same table, written as `LocalLoad` writes it
  * (every column, keys ascending, `Cell.toJson`), must give the same records, the same row count
  * and the same reject map.
  */
class EmbeddingJsonlParitySuite extends munit.FunSuite:

  private val RunId = OpsFx.SparkRunId

  private def prov(table: String) = Provenance(
    1,
    SourceType.ScholarChain,
    List(InputRef(s"scholarchain-graph/$table", RunId, Some("c" * 64))),
    OpsFx.SparkCode,
    Map("table" -> table),
    Some(RunId),
    None,
  )

  /** The JSONL bytes `LocalLoad` writes for a table. */
  private def jsonl(t: TableData, extra: List[String] = Nil): Array[Byte] =
    val names = t.columns.map(_.name).zipWithIndex.sortBy(_._1)
    val body  = t.rows
      .map(r => ujson.write(ujson.Obj.from(names.map((n, i) => n -> r(i).toJson))) + "\n")
      .mkString
    (body + extra.map(_ + "\n").mkString).getBytes(StandardCharsets.UTF_8)

  private def types(t: TableData): Map[String, String] =
    t.columns.map(c => c.name -> c.sparkType).toMap

  private def parity[A](
      t: TableData,
      viaParquet: (TableData, Provenance) => ParquetInput.Converted[A],
      viaJsonl: (Array[Byte], Map[String, String], Provenance) => EmbeddingJsonl.Converted[A],
  ): (ParquetInput.Converted[A], EmbeddingJsonl.Converted[A]) =
    val p = prov(t.name)
    val a = viaParquet(t, p)
    val b = viaJsonl(jsonl(t), types(t), p)
    assertEquals(b.records, a.records, s"records differ for ${t.name}")
    assertEquals(b.rows, a.rows, s"row count differs for ${t.name}")
    assertEquals(b.rejects, a.rejects, s"rejects differ for ${t.name}")
    (a, b)

  private def worksTable(
      rows: Vector[Vector[Cell]],
      year: String = "int",
      withDoi: Boolean = true,
  ): TableData =
    val cols =
      Vector(
        Column("workId", "string"),
        Column("title", "string"),
        Column("year", year),
      ) ++ (if withDoi then Vector(Column("doi", "string")) else Vector.empty) ++ Vector(
        Column("venueId", "string"),
        Column("topicIds", "array<string>"),
      )
    TableData("works", cols, rows)

  private def work(
      id: Cell,
      title: Cell,
      year: Cell,
      doi: Cell = Cell.Null,
      venue: Cell = Cell.Null,
      topics: Cell = Cell.Arr(Nil),
  ): Vector[Cell] = Vector(id, title, year, doi, venue, topics)

  private val W1 = "work:dblp:conf/x/A01"

  // ---- cell level ----------------------------------------------------------------------------

  test("cellOf inverts Cell.toJson for every cell the readers accept") {
    val cases: List[(Cell, String)] = List(
      (Cell.Null, "string"),
      (Cell.S("abc"), "string"),
      (Cell.S(""), "string"),
      (Cell.I(2001), "int"),
      (Cell.I(Int.MinValue), "int"),
      (Cell.I(Int.MaxValue), "int"),
      (Cell.L(2001L), "bigint"),
      (Cell.L(-5L), "bigint"),
      (Cell.L((1L << 53) - 1), "bigint"),
      (Cell.L(1L << 53), "bigint"),
      (Cell.L((1L << 53) + 1), "bigint"),
      (Cell.L(Long.MaxValue), "bigint"),
      (Cell.D(1.5), "double"),
      (Cell.D(3.0), "double"),
      (Cell.D(-0.25), "double"),
      (Cell.D(Double.PositiveInfinity), "double"),
      (Cell.B(true), "boolean"),
      (Cell.Arr(Nil), "array<string>"),
      (Cell.Arr(List(Cell.S("a"), Cell.S("b"))), "array<string>"),
    )
    cases.foreach { (c, ty) =>
      assertEquals(EmbeddingJsonl.cellOf(c.toJson, ty), c, s"$c as $ty")
    }
  }

  test(
    "Long.MinValue survives Cell.toJson and cellOf (Cells.scala writes it through v.abs, which overflows)"
  ) {
    val c = Cell.L(Long.MinValue)
    assertEquals(EmbeddingJsonl.cellOf(c.toJson, "bigint"), c, s"toJson gave ${c.toJson}")
  }

  test("a long of 2^53 or more is written as a string and read back as the same long") {
    val big = (1L << 53) + 7
    assertEquals(Cell.L(big).toJson, ujson.Str(big.toString))
    assertEquals(EmbeddingJsonl.cellOf(ujson.Str(big.toString), "bigint"), Cell.L(big))
    assertEquals(
      EmbeddingJsonl.cellOf(ujson.Str("not-a-long"), "bigint"),
      EmbeddingJsonl.cellOf(ujson.Obj(), "string"),
    )
  }

  test("a value of the wrong JSON type is a cell no accessor accepts") {
    val mismatch = EmbeddingJsonl.cellOf(ujson.Obj(), "string")
    List(
      (ujson.Num(5), "string"),
      (ujson.Str("5"), "int"),
      (ujson.Num(2.5), "int"),
      (ujson.Num(3e10), "int"),
      (ujson.Bool(true), "string"),
      (ujson.Arr(ujson.Num(1)), "array<string>"),
      (ujson.Arr(ujson.Str("a"), ujson.Num(1)), "array<string>"),
      (ujson.Str("a"), "array<string>"),
    ).foreach { (v, ty) =>
      val c = EmbeddingJsonl.cellOf(v, ty)
      c match
        case Cell.Arr(xs) => assert(xs.contains(mismatch), s"$v as $ty -> $c")
        case other        => assertEquals(other, mismatch, s"$v as $ty")
    }
  }

  test("an unknown column type reads every non-null value as a mismatch, never as a value") {
    val mismatch = EmbeddingJsonl.cellOf(ujson.Obj(), "string")
    assertEquals(EmbeddingJsonl.cellOf(ujson.Str("x"), "weird"), mismatch)
    assertEquals(EmbeddingJsonl.cellOf(ujson.Null, "weird"), Cell.Null)
  }

  // ---- works -----------------------------------------------------------------------------------

  test("works: valid rows, nulls and an empty or null topicIds convert the same way") {
    val t = worksTable(
      Vector(
        work(Cell.S(W1), Cell.S("Graph Mining"), Cell.I(2001)),
        work(
          Cell.S("work:dblp:conf/x/A02"),
          Cell.S("Second"),
          Cell.I(2002),
          Cell.S("10.1000/xyz"),
          Cell.S("venue:dblp:conf/x"),
          Cell.Arr(List(Cell.S("topic:dblp:a"), Cell.S("topic:dblp:b"))),
        ),
        work(
          Cell.S("work:dblp:conf/x/A03"),
          Cell.S("Null topics"),
          Cell.I(2003),
          topics = Cell.Null,
        ),
        work(
          Cell.S("work:dblp:conf/x/A04"),
          Cell.S("Empty topics"),
          Cell.I(2004),
          topics = Cell.Arr(Nil),
        ),
      )
    )
    val (a, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(a.records.size, 4)
    assertEquals(
      b.records.map(_.topicIds),
      List(Nil, List("topic:dblp:a", "topic:dblp:b"), Nil, Nil),
    )
    assertEquals(b.rejects, Map.empty[String, Int])
  }

  test(
    "works: a long year inside the int range is accepted; at or above 2^53 (a string) is rejected"
  ) {
    val t = worksTable(
      Vector(
        work(Cell.S(W1), Cell.S("t"), Cell.L(2001L)),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.S("t"), Cell.L((1L << 53) + 1)),
        work(Cell.S("work:dblp:conf/x/A03"), Cell.S("t"), Cell.L(Long.MaxValue)),
        work(Cell.S("work:dblp:conf/x/A04"), Cell.S("t"), Cell.L(Int.MaxValue.toLong + 1)),
        work(Cell.S("work:dblp:conf/x/A05"), Cell.S("t"), Cell.L(-7L)),
      ),
      year = "bigint",
    )
    val (a, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(a.records.size, 1)
    assertEquals(b.records.map(_.year), List(2001))
    assertEquals(b.rows, 5)
    assertEquals(b.rejects.values.sum, 4)
  }

  test("works: a double year and a null year are rejected under the same reasons") {
    val t = worksTable(
      Vector(
        work(Cell.S(W1), Cell.S("t"), Cell.D(2001.0)),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.S("t"), Cell.Null),
        work(Cell.S("work:dblp:conf/x/A03"), Cell.S("t"), Cell.D(2.5)),
        work(Cell.S("work:dblp:conf/x/A04"), Cell.S("t"), Cell.D(1e20)),
      ),
      year = "double",
    )
    val (a, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(b.records, Nil)
    assertEquals(b.rows, 4)
    assertEquals(a.rejects, b.rejects)
  }

  test("works: an invalid year in an int column is an invalid-<code> reject, a valid one is kept") {
    val t = worksTable(
      Vector(
        work(Cell.S(W1), Cell.S("t"), Cell.I(0)),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.S("t"), Cell.I(-3)),
        work(Cell.S("work:dblp:conf/x/A03"), Cell.S("t"), Cell.I(2024)),
        work(Cell.S("work:dblp:conf/x/A04"), Cell.S("t"), Cell.Null),
      )
    )
    val (_, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(b.records.map(_.year), List(2024))
    assert(b.rejects.keys.exists(_.startsWith("invalid-")), b.rejects.toString)
    assertEquals(b.rejects.get("null-or-wrong-type"), Some(1))
  }

  test("works: a blank title, a blank id and a malformed doi are rejected the same way") {
    val t = worksTable(
      Vector(
        work(Cell.S(W1), Cell.S(""), Cell.I(2001)),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.S("   "), Cell.I(2001)),
        work(Cell.S(""), Cell.S("t"), Cell.I(2001)),
        work(Cell.S("not-a-work-id"), Cell.S("t"), Cell.I(2001)),
        work(Cell.S("work:dblp:conf/x/A05"), Cell.S("t"), Cell.I(2001), Cell.S("")),
        work(
          Cell.S("work:dblp:conf/x/A06"),
          Cell.S("t"),
          Cell.I(2001),
          Cell.S("HTTPS://DOI.ORG/10.1/X"),
        ),
        work(Cell.S("work:dblp:conf/x/A07"), Cell.S("ok"), Cell.I(2001)),
      )
    )
    val (_, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(b.records.map(_.workId), List("work:dblp:conf/x/A07"))
    assertEquals(b.rows, 7)
    assertEquals(b.rejects.values.sum, 6)
  }

  test("works: a null or wrong-type cell where a string is required") {
    val t = worksTable(
      Vector(
        work(Cell.Null, Cell.S("t"), Cell.I(2001)),
        work(Cell.S(W1), Cell.Null, Cell.I(2001)),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.I(5), Cell.I(2001)),
        work(Cell.S("work:dblp:conf/x/A03"), Cell.S("t"), Cell.S("2001")),
        work(Cell.S("work:dblp:conf/x/A04"), Cell.S("t"), Cell.I(2001), Cell.I(7)),
        work(Cell.S("work:dblp:conf/x/A05"), Cell.S("t"), Cell.I(2001), venue = Cell.B(true)),
        work(Cell.S("work:dblp:conf/x/A06"), Cell.S("t"), Cell.I(2001), topics = Cell.S("topic:x")),
      )
    )
    val (_, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(b.records, Nil)
    assertEquals(b.rows, 7)
    assertEquals(b.rejects.values.sum, 7)
    assert(b.rejects.keySet.subsetOf(Set("null-or-wrong-type", "wrong-type")), b.rejects.toString)
  }

  test("works: a non-string array element is a wrong-type reject; null elements too") {
    val t = worksTable(
      Vector(
        work(
          Cell.S(W1),
          Cell.S("t"),
          Cell.I(2001),
          topics = Cell.Arr(List(Cell.S("topic:a"), Cell.I(3))),
        ),
        work(
          Cell.S("work:dblp:conf/x/A02"),
          Cell.S("t"),
          Cell.I(2001),
          topics = Cell.Arr(List(Cell.S("topic:a"), Cell.Null)),
        ),
        work(
          Cell.S("work:dblp:conf/x/A03"),
          Cell.S("t"),
          Cell.I(2001),
          topics = Cell.Arr(List(Cell.D(1.5))),
        ),
        work(
          Cell.S("work:dblp:conf/x/A04"),
          Cell.S("t"),
          Cell.I(2001),
          topics = Cell.Arr(List(Cell.Arr(List(Cell.S("topic:a"))))),
        ),
        work(
          Cell.S("work:dblp:conf/x/A05"),
          Cell.S("t"),
          Cell.I(2001),
          topics = Cell.Arr(List(Cell.S("topic:a"))),
        ),
      )
    )
    val (_, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(b.records.map(_.workId), List("work:dblp:conf/x/A05"))
    assertEquals(b.rejects, Map("wrong-type" -> 4))
  }

  test("works: an invalid topic reference inside a string array is an invalid-<code> reject") {
    val t = worksTable(
      Vector(
        work(
          Cell.S(W1),
          Cell.S("t"),
          Cell.I(2001),
          topics = Cell.Arr(List(Cell.S("not a topic ref"))),
        ),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.S("t"), Cell.I(2001), venue = Cell.S("bad venue")),
      )
    )
    val (_, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(b.records, Nil)
    assert(b.rejects.keys.forall(_.startsWith("invalid-")), b.rejects.toString)
  }

  test("works: a missing column rejects every row as missing-column in both readers") {
    val t = worksTable(
      Vector(
        Vector(Cell.S(W1), Cell.S("t"), Cell.I(2001), Cell.Null, Cell.Arr(Nil)),
        Vector(Cell.S("work:dblp:conf/x/A02"), Cell.S("t"), Cell.I(2002), Cell.Null, Cell.Arr(Nil)),
      ),
      withDoi = false,
    )
    val (_, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals(b.records, Nil)
    assertEquals(b.rejects, Map("missing-column" -> 2))
  }

  test("works: a key missing from SOME lines only rejects those lines") {
    val full  = worksTable(Vector(work(Cell.S(W1), Cell.S("t"), Cell.I(2001))))
    val bytes =
      new String(
        jsonl(full),
        StandardCharsets.UTF_8,
      ) + """{"title":"x","workId":"work:dblp:conf/x/A02","year":2001}""" + "\n"
    val b = EmbeddingJsonl.works(bytes.getBytes(StandardCharsets.UTF_8), types(full), prov("works"))
    assertEquals(b.rows, 2)
    assertEquals(b.records.map(_.workId), List(W1))
    assertEquals(b.rejects, Map("missing-column" -> 1))
  }

  test("works: an empty file and an empty table are the same") {
    val t      = worksTable(Vector.empty)
    val (a, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assertEquals((a.rows, b.rows, b.records, b.rejects), (0, 0, Nil, Map.empty[String, Int]))
  }

  test(
    "works: a malformed line is counted as a row and a malformed-line reject; the rest converts"
  ) {
    val good = worksTable(
      Vector(
        work(Cell.S(W1), Cell.S("t"), Cell.I(2001)),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.S("t"), Cell.I(2002)),
      )
    )
    val broken =
      List("""{"workId":""", "[1,2,3]", "null", "\"just a string\"", "   ", "not json at all")
    val bytes = jsonl(good, broken)
    val a     = ParquetInput.works(good, prov("works"))
    val b     = EmbeddingJsonl.works(bytes, types(good), prov("works"))
    assertEquals(b.records, a.records)
    assertEquals(b.rows, a.rows + broken.size)
    assertEquals(b.rejects, Map("malformed-line" -> broken.size))
  }

  test("works: a malformed line in the middle does not stop later lines") {
    val good = worksTable(
      Vector(
        work(Cell.S(W1), Cell.S("t"), Cell.I(2001)),
        work(Cell.S("work:dblp:conf/x/A02"), Cell.S("t"), Cell.I(2002)),
      )
    )
    val ls    = new String(jsonl(good), StandardCharsets.UTF_8).split("\n").toList
    val bytes = (ls.head :: "{broken" :: ls.tail).mkString("", "\n", "\n")
    val b = EmbeddingJsonl.works(bytes.getBytes(StandardCharsets.UTF_8), types(good), prov("works"))
    assertEquals(b.records.map(_.workId), List(W1, "work:dblp:conf/x/A02"))
    assertEquals(b.rejects, Map("malformed-line" -> 1))
    assertEquals(b.rows, 3)
  }

  test("works: a line with only blank lines between records (empty lines) is not a row") {
    val good  = worksTable(Vector(work(Cell.S(W1), Cell.S("t"), Cell.I(2001))))
    val bytes = ("\n\n" + new String(jsonl(good), StandardCharsets.UTF_8) + "\n\n")
      .getBytes(StandardCharsets.UTF_8)
    val b = EmbeddingJsonl.works(bytes, types(good), prov("works"))
    assertEquals((b.rows, b.records.size, b.rejects), (1, 1, Map.empty[String, Int]))
  }

  test("works: a row content never appears in a reject key") {
    val secret = "ROW-CONTENT-SENTINEL-8841"
    val t      = worksTable(Vector(work(Cell.S(W1), Cell.S("  "), Cell.I(2001), Cell.S(secret))))
    val (_, b) = parity(t, ParquetInput.works, EmbeddingJsonl.works)
    assert(b.rejects.nonEmpty)
    assert(!b.rejects.toString.contains(secret), b.rejects.toString)
  }

  // ---- summaries -------------------------------------------------------------------------------

  private def summaryTable(rows: Vector[Vector[Cell]], ws: String = "int"): TableData =
    TableData(
      "semantic-summaries",
      Vector(
        Column("entityId", "string"),
        Column("documentType", "string"),
        Column("textHash", "string"),
        Column("text", "string"),
        Column("windowStart", ws),
        Column("windowEnd", ws),
        Column("runId", "string"),
      ),
      rows,
    )

  private def summary(
      entity: Cell = Cell.S("researcher:dblp:r1"),
      tpe: Cell = Cell.S("researcher_profile"),
      text: String = "Ada published papers.",
      hash: Option[Cell] = None,
      ws: Cell = Cell.I(2000),
      we: Cell = Cell.I(2004),
      run: Cell = Cell.S(RunId),
  ): Vector[Cell] =
    Vector(entity, tpe, hash.getOrElse(Cell.S(Hashing.textHash(text))), Cell.S(text), ws, we, run)

  test("summaries: valid rows, a long window, a hash mismatch, a blank text, a bad run id") {
    val t = summaryTable(
      Vector(
        summary(),
        summary(entity = Cell.S("researcher:dblp:r2"), ws = Cell.L(1990L), we = Cell.L(1994L)),
        summary(entity = Cell.S("researcher:dblp:r3"), hash = Some(Cell.S("0" * 64))),
        summary(entity = Cell.S("researcher:dblp:r4"), text = "   "),
        summary(entity = Cell.S("researcher:dblp:r5"), run = Cell.S("run:short")),
        summary(entity = Cell.S("researcher:dblp:r6"), ws = Cell.Null),
        summary(entity = Cell.S("researcher:dblp:r8"), ws = Cell.L(1L << 60)),
        summary(entity = Cell.S("researcher:dblp:r9"), tpe = Cell.Null),
      )
    )
    val (a, b) = parity(t, ParquetInput.summaries, EmbeddingJsonl.summaries)
    assertEquals(a.records.map(_.entityId), List("researcher:dblp:r1", "researcher:dblp:r2"))
    assertEquals(b.rows, 8)
  }

  test("summaries: a window end declared double is rejected by both readers") {
    val t = TableData(
      "semantic-summaries",
      Vector(
        Column("entityId", "string"),
        Column("documentType", "string"),
        Column("textHash", "string"),
        Column("text", "string"),
        Column("windowStart", "int"),
        Column("windowEnd", "double"),
        Column("runId", "string"),
      ),
      Vector(summary(we = Cell.D(2004.0)), summary(we = Cell.Null)),
    )
    val (_, b) = parity(t, ParquetInput.summaries, EmbeddingJsonl.summaries)
    assertEquals(b.records, Nil)
    assertEquals(b.rows, 2)
  }

  test("summaries: a long window column holding a value of 2^53 or more matches") {
    val t = summaryTable(
      Vector(
        summary(ws = Cell.L((1L << 53) + 1), we = Cell.L(2004L)),
        summary(entity = Cell.S("researcher:dblp:r2"), ws = Cell.L(2000L), we = Cell.L(2004L)),
      ),
      ws = "bigint",
    )
    val (_, b) = parity(t, ParquetInput.summaries, EmbeddingJsonl.summaries)
    assertEquals(b.records.map(_.entityId), List("researcher:dblp:r2"))
  }

  test("summaries: a missing column and a text with characters outside the BMP") {
    val noText = TableData(
      "semantic-summaries",
      Vector(Column("entityId", "string"), Column("documentType", "string")),
      Vector(Vector(Cell.S("researcher:dblp:r1"), Cell.S("researcher_profile"))),
    )
    val (_, b0) = parity(noText, ParquetInput.summaries, EmbeddingJsonl.summaries)
    assertEquals(b0.rejects, Map("missing-column" -> 1))
    val t      = summaryTable(Vector(summary(text = "Zoë wrote about 𝔸 graphs é中.")))
    val (_, b) = parity(t, ParquetInput.summaries, EmbeddingJsonl.summaries)
    assertEquals(b.records.size, 1)
  }

  // ---- venues and topics -------------------------------------------------------------------------

  test("venues: valid, blank name, null name, wrong-type name, a missing column, a bad id") {
    val t = TableData(
      "venues",
      Vector(Column("venueId", "string"), Column("name", "string")),
      Vector(
        Vector(Cell.S("venue:dblp:conf/a"), Cell.S("A")),
        Vector(Cell.S("venue:dblp:conf/b"), Cell.S("  ")),
        Vector(Cell.S("venue:dblp:conf/c"), Cell.Null),
        Vector(Cell.S("venue:dblp:conf/d"), Cell.I(4)),
        Vector(Cell.S("bad id"), Cell.S("E")),
        Vector(Cell.Null, Cell.S("F")),
      ),
    )
    val (a, b) = parity(t, ParquetInput.venues, EmbeddingJsonl.venues)
    assertEquals(a.records.size, 1)
    assertEquals(b.rows, 6)
    val noName = TableData(
      "venues",
      Vector(Column("venueId", "string")),
      Vector(Vector(Cell.S("venue:dblp:conf/a"))),
    )
    val (_, b2) = parity(noName, ParquetInput.venues, EmbeddingJsonl.venues)
    assertEquals(b2.rejects, Map("missing-column" -> 1))
  }

  test("topics: valid, blank label, null, wrong type, bad id and a missing column") {
    val t = TableData(
      "topics",
      Vector(Column("topicId", "string"), Column("label", "string")),
      Vector(
        Vector(Cell.S("topic:dblp:a"), Cell.S("A")),
        Vector(Cell.S("topic:dblp:b"), Cell.S("")),
        Vector(Cell.S("topic:dblp:c"), Cell.Null),
        Vector(Cell.S("topic:dblp:d"), Cell.L(4L)),
        Vector(Cell.S("topicless"), Cell.S("E")),
      ),
    )
    val (a, b) = parity(t, ParquetInput.topics, EmbeddingJsonl.topics)
    assertEquals(a.records.size, 1)
    assertEquals(b.rows, 5)
    val noLabel = TableData(
      "topics",
      Vector(Column("topicId", "string")),
      Vector(Vector(Cell.S("topic:dblp:a"))),
    )
    val (_, b2) = parity(noLabel, ParquetInput.topics, EmbeddingJsonl.topics)
    assertEquals(b2.rejects, Map("missing-column" -> 1))
  }

  test("venues and topics: a malformed line is a reject, the rest converts") {
    val v = TableData(
      "venues",
      Vector(Column("venueId", "string"), Column("name", "string")),
      Vector(Vector(Cell.S("venue:dblp:conf/a"), Cell.S("A"))),
    )
    val b = EmbeddingJsonl.venues(jsonl(v, List("{oops")), types(v), prov("venues"))
    assertEquals((b.rows, b.records.size, b.rejects), (2, 1, Map("malformed-line" -> 1)))
    val t = TableData(
      "topics",
      Vector(Column("topicId", "string"), Column("label", "string")),
      Vector(Vector(Cell.S("topic:dblp:a"), Cell.S("A"))),
    )
    val c = EmbeddingJsonl.topics(jsonl(t, List("{oops")), types(t), prov("topics"))
    assertEquals((c.rows, c.records.size, c.rejects), (2, 1, Map("malformed-line" -> 1)))
  }

  // ---- provenance ------------------------------------------------------------------------------

  test(
    "provenance (provFor shape): the record carries the table input, the run and the Spark code version"
  ) {
    val p = prov("works")
    assertEquals(p.inputs, List(InputRef("scholarchain-graph/works", RunId, Some("c" * 64))))
    val t = worksTable(Vector(work(Cell.S(W1), Cell.S("t"), Cell.I(2001))))
    val b = EmbeddingJsonl.works(jsonl(t), types(t), p)
    assertEquals(b.records.map(_.provenance), List(p))
    assertEquals(b.records.head.provenance, ParquetInput.works(t, p).records.head.provenance)
  }

  test(
    "provenance: a part-files digest that is not 64 lowercase hex rejects every row (the checksum is validated)"
  ) {
    val bad =
      prov("works").copy(inputs = List(InputRef("scholarchain-graph/works", RunId, Some("XYZ"))))
    val t = worksTable(Vector(work(Cell.S(W1), Cell.S("t"), Cell.I(2001))))
    val a = ParquetInput.works(t, bad)
    val b = EmbeddingJsonl.works(jsonl(t), types(t), bad)
    assertEquals(b.records, a.records)
    assertEquals(b.records, Nil)
    assertEquals(b.rejects, a.rejects)
  }
