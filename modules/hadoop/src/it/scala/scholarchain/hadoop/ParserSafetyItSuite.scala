package scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scholarchain.core.*
import ItSupport.*

/** Entity table and XXE checks on the pure parser, using SYNTHETIC records. No job is launched. */
class ParserSafetyItSuite extends munit.FunSuite:
  private lazy val entities = DblpEntities.fromDtd(Files.readAllBytes(fixture("dblp-synth.dtd")))
  private def parse(xml: String) =
    DblpParser.parseRecord(xml.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, entities)

  test(
    "the entity table comes from the local DTD: numeric references only, no parameter entities"
  ) {
    assertEquals(
      entities.table,
      Map("uuml" -> "&#252;", "eacute" -> "&#233;", "ouml" -> "&#246;", "ndash" -> "&#8211;"),
    )
    val hostile =
      """<!ENTITY a SYSTEM "http://example.invalid/x"> <!ENTITY b "&a;&a;"> <!ENTITY % p "x"> <!ENTITY amp "&#38;"> <!ENTITY ok "&#65;">"""
    assertEquals(DblpEntities.fromDtd(hostile.getBytes("US-ASCII")).table, Map("ok" -> "&#65;"))
  }

  test("a declared entity decodes; an undeclared one is a malformed record") {
    val ok = parse(
      """<article key="k/1"><author>M&uuml;ller</author><title>T&ndash;U</title><year>2020</year></article>"""
    )
    assertEquals(
      ok.toOption.collect { case ParsedWork(w, _) => (w.authors.head.name, w.title) },
      Some(("Müller", "T–U")),
    )
    val bad = parse(
      """<article key="k/2"><author>A</author><title>&nope;</title><year>2020</year></article>"""
    )
    assertEquals(bad.left.toOption.map(_.reason), Some(RejectReason.MalformedRecord))
  }

  test(
    "XXE is impossible: DOCTYPE or ENTITY declarations inside a record are rejected, and nothing is read"
  ) {
    val file = parse(
      """<article key="k/3"><!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/hostname">]><author>A</author><title>&xxe;</title><year>2020</year></article>"""
    )
    assertEquals(file.left.toOption.map(_.reason), Some(RejectReason.MalformedRecord))
    assert(file.left.toOption.exists(_.message.contains("markup declarations")))
    val net = parse(
      """<article key="k/4"><!ENTITY e SYSTEM "http://127.0.0.1:9/x"><author>A</author><title>t</title><year>2020</year></article>"""
    )
    assertEquals(net.left.toOption.map(_.reason), Some(RejectReason.MalformedRecord))
    // an external reference with no declaration cannot resolve either
    val ref = parse(
      """<article key="k/5"><author>A</author><title>&xxe;</title><year>2020</year></article>"""
    )
    assertEquals(ref.left.toOption.map(_.reason), Some(RejectReason.MalformedRecord))
  }

  test("the pair expander yields exactly n(n-1)/2 canonical, distinct pairs") {
    val prov =
      Provenance(1, SourceType.Dblp, List(InputRef("x", "v", Some("a" * 64))), "c", Map.empty)
    (0 to 9).foreach { n =>
      val ps = PairExpander.expand("work:dblp:k/1", 2020, n, prov).map(_.toOption.get).toList
      assertEquals(ps.size.toLong, PairExpander.pairCount(n))
      assertEquals(ps.map(_.pair).distinct.size, ps.size)
      assert(ps.forall(p => p.pair.first.compareTo(p.pair.second) < 0))
    }
    assertEquals(PairExpander.pairCount(42), 861L)
  }
end ParserSafetyItSuite
