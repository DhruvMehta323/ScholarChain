package scholarchain.core

import ValidationError.*

class DoiContractSuite extends ContractSupport:

  List(
    "10.1000/ABC.1",
    "  10.1000/abc.1\n",
    "https://doi.org/10.1000/ABC.1",
    "HTTP://DX.DOI.ORG/10.1000/ABC.1",
    "http://doi.org/10.1000/abc.1",
    "https://dx.doi.org/10.1000/abc.1",
    "doi:10.1000/ABC.1",
    "DOI: 10.1000/ABC.1",
  ).foreach { in =>
    test(s"normalize accepts ${in.trim.take(40)}") {
      assertEquals(Doi.normalize(in), Right("10.1000/abc.1"))
    }
  }

  test("normalize handles a realistic DOI with the doi: prefix") {
    assertEquals(Doi.normalize("doi:10.1145/3292500.3330701"), Right("10.1145/3292500.3330701"))
  }

  test("normalize is idempotent") {
    val once = right(Doi.normalize("https://doi.org/10.1000/ABC.1"))
    assertEquals(Doi.normalize(once), Right(once))
  }

  test("normalize does not decode percent-encoding in bare (non-URL) input") {
    assertEquals(Doi.normalize("10.1000/a%2Fb"), Right("10.1000/a%2fb"))
  }

  List(
    "",
    "   ",
    "10.100/abc",
    "10.1234567890/abc",
    "11.1000/abc",
    "10.1000/",
    "10.1000/a b",
    "https://example.org/10.1000/x",
    "doi:",
    "10.1000abc",
  ).foreach { in =>
    test(s"normalize rejects '${in}'") {
      expectErr(Doi.normalize(in)) { case InvalidDoi(i, _) if i == in => () }
    }
  }

  test("orcid: accepts valid ids, uppercases a lowercase check digit X") {
    assertEquals(Orcid.parse("0000-0002-1694-233X"), Right("0000-0002-1694-233X"))
    assertEquals(Orcid.parse("0000-0002-1694-233x"), Right("0000-0002-1694-233X"))
  }

  test("orcid: accepts the https://orcid.org/ url form") {
    assertEquals(Orcid.parse("https://orcid.org/0000-0001-5109-3700"), Right("0000-0001-5109-3700"))
  }

  test("orcid: rejects checksum mismatch and wrong shapes") {
    expectErr(Orcid.parse("0000-0002-1825-0098")) { case InvalidOrcid(_, "check digit mismatch") =>
      ()
    }
    expectErr(Orcid.parse("000000021825009")) { case InvalidOrcid(_, "wrong shape") => () }
    expectErr(Orcid.parse("abcd-0002-1825-0097")) { case InvalidOrcid(_, "wrong shape") => () }
  }
