package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** The declared table registry against its documentation (SPARK-CONTRACTS.md) and its own rules. */
class SchemasContractSuite extends munit.FunSuite:

  private lazy val doc: List[String] =
    val p: Path =
      List(Paths.get("SPARK-CONTRACTS.md"), Paths.get("modules/spark/SPARK-CONTRACTS.md"))
        .find(Files.isRegularFile(_))
        .getOrElse(fail("SPARK-CONTRACTS.md not found from " + Paths.get("").toAbsolutePath))
    Files.readString(p, StandardCharsets.UTF_8).linesIterator.toList

  test("the registry has 30 unique tables and byName finds each") {
    assertEquals(Tables.all.size, 30)
    assertEquals(Tables.all.map(_.name).distinct.size, 30)
    Tables.all.foreach(t => assertEquals(Tables.byName(t.name), Some(t)))
    assertEquals(Tables.byName("no-such-table"), None)
  }

  test(
    "every windowed table starts with window, windowStart, windowEnd; others have no window column"
  ) {
    Tables.all.foreach { t =>
      if t.windowed then
        assertEquals(t.columns.take(3), List("window", "windowStart", "windowEnd"), t.name)
      else assert(!t.columns.contains("window"), t.name)
      assertEquals(t.columns.distinct.size, t.columns.size, t.name)
    }
    assertEquals(Tables.all.count(_.windowed), 15)
  }

  test("every declared table and column is documented in SPARK-CONTRACTS.md (contract drift)") {
    val section = doc.dropWhile(!_.startsWith("## 4.")).drop(1).takeWhile(!_.startsWith("## 5."))
    val rows    = section.filter(_.startsWith("| `"))
    Tables.all.foreach { t =>
      val row =
        rows.find(_.contains(s"`${t.name}`")).getOrElse(fail(s"table ${t.name} is not documented"))
      t.columns.filterNot(Cols.windowCols.contains).foreach { c =>
        assert(row.contains(c), s"column $c of ${t.name} is not in its documented row: $row")
      }
    }
  }

  test(
    "the documented algorithm-status and window-summary rows name the same columns as the registry"
  ) {
    val ws = doc.find(_.startsWith("| `window-summary` (W)")).get
    Tables.WindowSummary.columns.drop(3).foreach(c => assert(ws.contains(c), c))
    val st = doc.find(_.startsWith("| `algorithm-status`")).get
    Tables.AlgorithmStatus.columns.foreach(c => assert(st.contains(c), c))
  }

  test(
    "every output table that carries a window has all three window columns typed string, int, int"
  ) {
    Tables.all.filter(_.windowed).foreach { t =>
      assertEquals(
        t.schema.fields.take(3).map(_.dataType.catalogString).toList,
        List("string", "int", "int"),
        t.name,
      )
    }
  }

  test("input schemas carry the documented columns") {
    assertEquals(
      InputSchemas.Mentions.fieldNames.toList,
      List("mentionId", "workId", "position", "rawName", "orcid", "sourceType"),
    )
    assertEquals(
      InputSchemas.Decisions.fieldNames.toList,
      List("leftId", "rightId", "entityKind", "outcome", "policyVersion"),
    )
    assertEquals(
      InputSchemas.PairCandidates.fieldNames.toList,
      List("workId", "year", "first", "second"),
    )
    assertEquals(
      InputSchemas.Authorship.fieldNames.toList,
      List("mentionId", "workId", "position", "year"),
    )
  }

  test("cannot-link-blocked carries the unsuffixedVsSuffixedGroups column, last, typed int") {
    val cols = Tables.CannotLinkBlocked.columns
    assert(cols.contains("unsuffixedVsSuffixedGroups"), cols.toString)
    assertEquals(cols.last, "unsuffixedVsSuffixedGroups")
    assertEquals(
      Tables.CannotLinkBlocked.schema("unsuffixedVsSuffixedGroups").dataType.catalogString,
      "int",
    )
    assertEquals(
      cols,
      List(
        "componentKey",
        "memberBaseIds",
        "memberCount",
        "reason",
        "rejectedOrConflictPairs",
        "distinctOrcids",
        "distinctDblpHomonymGroups",
        "unsuffixedVsSuffixedGroups",
      ),
    )
  }
