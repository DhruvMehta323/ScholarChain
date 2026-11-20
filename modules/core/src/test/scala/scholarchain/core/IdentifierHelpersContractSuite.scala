package scholarchain.core

import ValidationError.*
import PipelineFx.*

/** `Titles.titleKey`, `Doi.isDoiUrl`, `ExternalLinks` and the mention-pair helpers. */
class IdentifierHelpersContractSuite extends ContractSupport:

  // ---- Titles.titleKey ------------------------------------------------------------------------

  test("titleKey: trims, lowercases and collapses whitespace") {
    assertEquals(Titles.titleKey("  Graph  Mining "), "graph mining")
    assertEquals(Titles.titleKey("a\t\n b"), "a b")
    assertEquals(Titles.titleKey("graph mining"), "graph mining")
  }

  test("titleKey: NFKC folds ligatures, full-width forms and no-break spaces") {
    assertEquals(Titles.titleKey("ﬁne"), "fine")
    assertEquals(Titles.titleKey("ＡＢ"), "ab")
    assertEquals(Titles.titleKey(" x y "), "x y")
  }

  test("titleKey: lowercases with Locale.ROOT (U+0130 becomes i + U+0307)") {
    assertEquals(Titles.titleKey("İ"), "i̇")
  }

  test("titleKey: may be empty, and workIdFallback rejects that") {
    assertEquals(Titles.titleKey(""), "")
    assertEquals(Titles.titleKey("   "), "")
    expectErr(Ids.workIdFallback("   ", 2001, "a")) { case InvalidField("work", _) => () }
  }

  test("titleKey is the title component of workIdFallback") {
    assertEquals(
      Ids.workIdFallback("  Graph  Mining ", 2001, "a"),
      Ids.workIdFallback(Titles.titleKey("  Graph  Mining "), 2001, "a"),
    )
  }

  // ---- Doi.isDoiUrl ---------------------------------------------------------------------------

  test("isDoiUrl: the four resolver prefixes, case-insensitive, trimmed") {
    List(
      "https://doi.org/10.1000/abc",
      "http://doi.org/10.1000/abc",
      "https://dx.doi.org/10.1000/abc",
      "http://dx.doi.org/10.1000/abc",
      "HTTPS://DOI.ORG/10.1000/ABC",
      "  https://doi.org/10.1000/abc",
    ).foreach(u => assert(Doi.isDoiUrl(u), u))
  }

  test("isDoiUrl: says nothing about validity, and rejects non-URLs") {
    assert(Doi.isDoiUrl("https://doi.org/bad"))
    assert(!Doi.isDoiUrl("10.1000/abc"))
    assert(!Doi.isDoiUrl("doi:10.1000/abc"))
    assert(!Doi.isDoiUrl("https://arxiv.org/abs/2101.00001"))
    assert(!Doi.isDoiUrl(""))
    assert(!Doi.isDoiUrl("   "))
  }

  // ---- ExternalLinks --------------------------------------------------------------------------

  private val doiUrl = "https://doi.org/10.1145/3292500.3330701"

  test("doiFromEe: DOI URL gives the canonical DOI") {
    assertEquals(ExternalLinks.doiFromEe(doiUrl), Right(Some("10.1145/3292500.3330701")))
    assertEquals(ExternalLinks.doiFromEe("https://doi.org/10.1000/ABC"), Right(Some("10.1000/abc")))
  }
  test("doiFromEe: non-DOI links give Right(None)") {
    assertEquals(ExternalLinks.doiFromEe("db/journals/x.html"), Right(None))
    assertEquals(ExternalLinks.doiFromEe("https://arxiv.org/abs/2101.00001"), Right(None))
    assertEquals(ExternalLinks.doiFromEe(""), Right(None))
  }
  test("doiFromEe: DOI URL with an invalid DOI is InvalidDoi") {
    expectErr(ExternalLinks.doiFromEe("https://doi.org/bad")) {
      case InvalidDoi("https://doi.org/bad", _) => ()
    }
  }
  test("fromEe: DOI") {
    assertEquals(
      ExternalLinks.fromEe(doiUrl),
      Right(Some(ExternalId(IdentifierType.Doi, "10.1145/3292500.3330701"))),
    )
  }
  test("fromEe: arXiv abs and pdf") {
    assertEquals(
      ExternalLinks.fromEe("https://arxiv.org/abs/2101.00001"),
      Right(Some(ExternalId(IdentifierType.Arxiv, "2101.00001"))),
    )
    assertEquals(
      ExternalLinks.fromEe("https://arxiv.org/pdf/2101.00001v2.pdf"),
      Right(Some(ExternalId(IdentifierType.Arxiv, "2101.00001"))),
    )
  }
  test("fromEe: a non-identifier link is Right(None)") {
    assertEquals(ExternalLinks.fromEe("db/journals/x.html"), Right(None))
    assertEquals(ExternalLinks.fromEe("https://example.org/paper"), Right(None))
  }
  test("fromEe: invalid DOI URL and invalid arXiv URL are errors") {
    expectErr(ExternalLinks.fromEe("https://doi.org/bad")) { case InvalidDoi(_, _) => () }
    expectErr(ExternalLinks.fromEe("https://arxiv.org/abs/2101.0001")) {
      case InvalidArxiv("https://arxiv.org/abs/2101.0001", _) => ()
    }
  }

  // ---- OrderedPair mention helpers ------------------------------------------------------------

  test("canonicalMentions: order does not depend on argument order") {
    assertEquals(OrderedPair.canonicalMentions(M0, M1), Right(OrderedPair(M0, M1)))
    assertEquals(OrderedPair.canonicalMentions(M1, M0), Right(OrderedPair(M0, M1)))
  }
  test("canonicalMentions: self-pair") {
    expectErr(OrderedPair.canonicalMentions(M0, M0)) { case InvalidPair(M0, M0, "self-pair") => () }
  }
  test("canonicalMentions: a non-mention id is MalformedId(mention)") {
    expectErr(OrderedPair.canonicalMentions(Fx.orcidId, M0)) {
      case MalformedId("mention", id) if id == Fx.orcidId => ()
    }
    expectErr(OrderedPair.canonicalMentions(M0, Fx.scId)) {
      case MalformedId("mention", id) if id == Fx.scId => ()
    }
  }
  test("validateMentions: canonical pair passes") {
    assertEquals(OrderedPair.validateMentions(OrderedPair(M0, M1)), Right(OrderedPair(M0, M1)))
  }
  test("validateMentions: reversed pair is not in canonical order") {
    expectErr(OrderedPair.validateMentions(OrderedPair(M1, M0))) {
      case InvalidPair(M1, M0, "not in canonical order") => ()
    }
  }
  test("validateMentions: self-pair and non-mention ids") {
    expectErr(OrderedPair.validateMentions(OrderedPair(M1, M1))) {
      case InvalidPair(M1, M1, "self-pair") => ()
    }
    expectErr(OrderedPair.validateMentions(Fx.pair)) { case MalformedId("mention", _) => () }
    expectErr(OrderedPair.validateMentions(OrderedPair(M0, "mention:xyz"))) {
      case MalformedId("mention", "mention:xyz") => ()
    }
  }
