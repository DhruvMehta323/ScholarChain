package scholarchain.hadoop

import scholarchain.core.*
import UnitSupport.*

/** UNIT tests of the pure DBLP parser on SYNTHETIC records (no job is launched). */
class DblpParserUnitSuite extends munit.FunSuite:

  test("unit: a DTD entity (&uuml;) decodes to the character in names and titles") {
    val w =
      work(parse(rec("<author>M&uuml;ller</author><title>T&ndash;U</title><year>2020</year>")))
    assertEquals(w.authors.map(_.name), List("Müller"))
    assertEquals(w.title, "T–U")
  }

  test("unit: a multiline title collapses whitespace runs to single spaces") {
    val w = work(
      parse(
        rec("<author>A</author><title>Line one\n   line two\n\tline three</title><year>2020</year>")
      )
    )
    assertEquals(w.title, "Line one line two line three")
  }

  test("unit: inline markup in a title is flattened to its text") {
    val w = work(
      parse(
        rec(
          "<author>A</author><title>Deep <i>Learning</i> of H<sub>2</sub>O</title><year>2020</year>"
        )
      )
    )
    assertEquals(w.title, "Deep Learning of H2O")
  }

  test("unit: a missing year is rejected as missing_year, carrying the DBLP key") {
    val r = parse(rec("<author>A</author><title>T</title>"))
    assertEquals(reason(r), Some(RejectReason.MissingYear))
    assertEquals(r.left.toOption.flatMap(_.sourceRecordId), Some("journals/unit/x1"))
  }

  test("unit: a non-numeric year is rejected as invalid_year") {
    assertEquals(
      reason(parse(rec("<author>A</author><title>T</title><year>20x0</year>"))),
      Some(RejectReason.InvalidYear),
    )
  }

  test("unit: a missing title is rejected as missing_title (blank title too)") {
    assertEquals(
      reason(parse(rec("<author>A</author><year>2020</year>"))),
      Some(RejectReason.MissingTitle),
    )
    assertEquals(
      reason(parse(rec("<author>A</author><title>  </title><year>2020</year>"))),
      Some(RejectReason.MissingTitle),
    )
  }

  test("unit: a repeated author string is one mention and the repeat is counted as dropped") {
    val w = work(
      parse(okRec("<author>Ann One</author><author>Bob Two</author><author>Ann One</author>"))
    )
    assertEquals(w.authors.map(_.name), List("Ann One", "Bob Two"))
    assertEquals(w.droppedDuplicateAuthors, 1)
  }

  test("unit: the orcid attribute is preserved in canonical form; a bad one is skipped") {
    val ok = work(parse(okRec("""<author orcid="0000-0002-1825-0097">Ann One</author>""")))
    assertEquals(ok.authors, List(DblpAuthor("Ann One", Some("0000-0002-1825-0097"))))
    val bad = parse(okRec("""<author orcid="0000-0002-1825-0098">Ann One</author>"""))
    assertEquals(work(bad).authors, List(DblpAuthor("Ann One", None)))
    assertEquals(warnings(bad), List("invalid_orcid"))
  }

  test("unit: DOI and arXiv come from ee links; other ee links are kept but yield no id") {
    val w = work(
      parse(
        okRec(extra =
          "<ee>https://doi.org/10.1000/ABC.1</ee><ee>https://arxiv.org/abs/2101.00001</ee><ee>https://example.invalid/page</ee>"
        )
      )
    )
    assertEquals(w.doi, Some("10.1000/abc.1"))
    assertEquals(w.arxiv, Some("2101.00001"))
    assertEquals(w.ees.size, 3)
  }

  test("unit: an invalid DOI in ee keeps the record, adds no DOI and warns invalid_identifier") {
    val r = parse(okRec(extra = "<ee>https://doi.org/10.1000/a%G1b</ee>"))
    assertEquals(work(r).doi, None)
    assertEquals(warnings(r), List("invalid_identifier"))
  }

  test("unit: every row of the shared DOI vector TSV, run through the Hadoop ee to DOI path") {
    val rows = DoiVectors.load()
    assert(rows.size >= 80, s"only ${rows.size} vector rows")
    var urlForm, bareForm, xmlIllegal = 0
    def illegalXml(c: Char): Boolean  =
      (c < 0x20 && c != '\t' && c != '\n' && c != '\r') ||
        c == 0xfffe || c == 0xffff
    rows.foreach { r =>
      val xml = rec(
        s"<author>A</author><title>T</title><year>2020</year><ee>${xmlEscape(r.input)}</ee>"
      )
      val res = parse(xml)
      if r.input.exists(illegalXml) then
        xmlIllegal += 1
        assertEquals(
          reason(res),
          Some(RejectReason.MalformedRecord),
          s"line ${r.line}: ${r.rawInput}",
        )
      else if Doi.isDoiUrl(r.input) then
        urlForm += 1
        r.expected match
          case Some(exp) =>
            assertEquals(work(res).doi, Some(exp), s"line ${r.line}: ${r.rawInput}")
          case None =>
            assertEquals(work(res).doi, None, s"line ${r.line}: ${r.rawInput}")
            assertEquals(warnings(res), List("invalid_identifier"), s"line ${r.line}")
      else
        bareForm += 1
        // a bare DOI or doi: scheme is not a link; the documented rule is that ee must be a DOI URL
        assertEquals(work(res).doi, None, s"line ${r.line}: ${r.rawInput}")
      // idempotent route: the canonical DOI inside a resolver URL comes back unchanged
      // (a literal % is decoded again inside a resolver URL, so those rows are not idempotent there)
      r.expected.filterNot(e => e.exists(illegalXml) || e.contains('%')).foreach { exp =>
        val again = parse(
          rec(
            s"<author>A</author><title>T</title><year>2020</year><ee>https://doi.org/${xmlEscape(exp)}</ee>"
          )
        )
        assertEquals(
          again.toOption.collect { case ParsedWork(w, _) => w.doi }.flatten,
          Some(exp),
          s"line ${r.line}: canonical ${r.rawExpected}",
        )
      }
    }
    assert(urlForm > 20, s"too few URL-form rows exercised: $urlForm")
    assert(bareForm > 20, s"too few bare rows exercised: $bareForm")
  }

  test("unit: editors are excluded from authors; an editors-only record is missing_authors") {
    val w = work(parse(okRec(extra = "<editor>Ed Itor</editor>")))
    assertEquals(w.authors.map(_.name), List("Ann One"))
    assertEquals(w.editors, List("Ed Itor"))
    assertEquals(
      reason(parse(rec("<editor>Ed Itor</editor><title>T</title><year>2020</year>"))),
      Some(RejectReason.MissingAuthors),
    )
  }

  test("unit: a www homepage record is routed to person aliases with names, orcid and urls") {
    val r = parse(
      rec(
        """<author>Ann One</author><author orcid="0000-0002-1825-0097">Ann Alias</author><url>https://example.invalid/ann</url>""",
        "www",
        "homepages/unit/h1",
      )
    )
    r match
      case Right(ParsedPersonRecord(p, _)) =>
        assertEquals(p.key, "homepages/unit/h1")
        assertEquals(p.names, List("Ann One", "Ann Alias"))
        assertEquals(p.orcids, List("0000-0002-1825-0097"))
        assertEquals(p.urls, List("https://example.invalid/ann"))
      case other => fail(s"expected a person record, got $other")
  }

  test("unit: each supported record type parses; www is a person; data is unsupported_type") {
    val text = resourceText("dblp-types-unit.xml")
    val recs =
      """(?s)<(article|inproceedings|proceedings|book|incollection|phdthesis|mastersthesis|www|data)\s.*?</\1>""".r
        .findAllIn(text)
        .toList
    assertEquals(recs.size, 9)
    val types = recs.map(parse).map {
      case Right(ParsedWork(w, _))         => w.recordType
      case Right(ParsedPersonRecord(_, _)) => "person"
      case Left(f)                         => s"reject:${f.reason.wire}"
    }
    assertEquals(
      types,
      List(
        "article",
        "inproceedings",
        "proceedings",
        "book",
        "incollection",
        "phdthesis",
        "mastersthesis",
        "person",
        "reject:unsupported_type",
      ),
    )
  }

  test("unit: an external-entity (XXE) attempt is not resolved and the record is rejected") {
    val marker = "UNIT_XXE_MARKER_SHOULD_NOT_APPEAR"
    val f      =
      java.nio.file.Files.createTempFile(repoRoot.resolve("modules/hadoop/target"), "xxe", ".txt")
    java.nio.file.Files.writeString(f, marker)
    val xxe =
      s"""<!DOCTYPE article [<!ENTITY xxe SYSTEM "${f.toUri}">]><article key="k/x"><author>A</author><title>&xxe;</title><year>2020</year></article>"""
    val r = parse(xxe)
    assertEquals(reason(r), Some(RejectReason.MalformedRecord))
    assert(!r.left.toOption.exists(_.message.contains(marker)))
    // an entity that is only referenced (declared nowhere in the DTD table) is also malformed
    val ref = parse(rec("<author>A</author><title>&xxe;</title><year>2020</year>"))
    assertEquals(reason(ref), Some(RejectReason.MalformedRecord))
  }

  private def xmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
