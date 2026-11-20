package scholarchain.core

import ValidationError.*

/** Table-driven arXiv identifier contract. The first four accepts are the examples observed by
  * domain-engineer (A1); the rest exercise the stated rule. All inputs are synthetic.
  */
class ArxivIdContractSuite extends ContractSupport:

  private val accepts: List[(String, String, Option[Int])] = List(
    // observed by A1
    ("arXiv:2101.00001v3", "2101.00001", Some(3)),
    ("https://arxiv.org/abs/hep-th/9901001v2", "hep-th/9901001", Some(2)),
    ("https://arxiv.org/pdf/1501.00001.pdf", "1501.00001", None),
    ("math.GT/0309136", "math.gt/0309136", None),
    // new style boundaries
    ("0704.0001", "0704.0001", None),
    ("1412.9999", "1412.9999", None),
    ("1501.00001", "1501.00001", None),
    ("2101.00001", "2101.00001", None),
    ("2112.12345v9999", "2112.12345", Some(9999)),
    ("2101.00001v1", "2101.00001", Some(1)),
    // prefix forms, ASCII case-insensitive
    ("arxiv: 2101.00001", "2101.00001", None),
    ("ARXIV:2101.00001", "2101.00001", None),
    ("HTTP://WWW.ARXIV.ORG/ABS/2101.00001", "2101.00001", None),
    ("http://arxiv.org/abs/2101.00001v2", "2101.00001", Some(2)),
    ("https://www.arxiv.org/pdf/2101.00001v2.pdf", "2101.00001", Some(2)),
    ("https://arxiv.org/pdf/hep-th/9901001", "hep-th/9901001", None),
    // surrounding whitespace (the Doi whitespace set)
    ("  2101.00001  ", "2101.00001", None),
    (" 2101.00001　", "2101.00001", None),
    // old style
    ("hep-th/9108001", "hep-th/9108001", None),
    ("math/0001001", "math/0001001", None),
    ("cs.LG/0701001", "cs.lg/0701001", None),
    ("q-bio.NC/0701001v3", "q-bio.nc/0701001", Some(3)),
  )

  accepts.foreach { (in, id, ver) =>
    test(s"accepts ${in.replace(" ", "<nbsp>").replace("　", "<ideographic-space>")}") {
      assertEquals(ArxivId.parse(in), Right(ArxivRef(id, ver)))
      assertEquals(ArxivId.normalize(in), Right(id))
    }
  }

  test("normalize is idempotent on every accepted input") {
    accepts.foreach { (in, _, _) =>
      val once = right(ArxivId.normalize(in))
      assertEquals(ArxivId.normalize(once), Right(once))
    }
  }

  test("a versioned and an unversioned form normalize to the same id") {
    assertEquals(ArxivId.normalize("2101.00001v1"), ArxivId.normalize("2101.00001v7"))
  }

  /** (input, expected reason when the report or rule states it exactly) */
  private val rejects: List[(String, Option[String])] = List(
    ("2101.0001", Some("identifiers from 1501 have 5 digits")), // observed by A1
    ("0703.0001", Some("new-style identifiers start at 0704")),
    ("1412.12345", Some("identifiers up to 1412 have 4 digits")),
    ("hep-th/0801001", Some("old-style identifiers cover 1991 to 2007")),
    ("hep-th/9001001", Some("old-style identifiers cover 1991 to 2007")),
    ("1413.0001", None),  // month 13
    ("2100.00001", None), // month 00
    ("2101.00001v0", None),
    ("2101.00001v10000", None),
    ("hep-th/9901001v0", None),
    ("hep-th/990100", None),
    ("hep-th/9913001", None),
    ("", None),
    ("   ", None),
    ("arxiv:", None),
    ("https://arxiv.org/abs/", None),
    ("https://arxiv.org/abs/2101.00001?x=1", None),
    ("https://arxiv.org/abs/2101.00001#frag", None),
    ("https://arxiv.org/abs/2101%2E00001", None),
    ("https://arxiv.org/abs/2101.00001.pdf", None),
    ("ftp://arxiv.org/abs/2101.00001", None),
    ("10.1000/abc", None),
    ("2101.00001 v2", None),
    ("arxiv:arxiv:2101.00001", None),
    ("2101.00001/", None),
    ("hep-th/9901001/extra", None),
  )

  rejects.foreach { (in, reason) =>
    test(s"rejects '$in' with InvalidArxiv") {
      expectErr(ArxivId.parse(in)) { case InvalidArxiv(i, r) =>
        assertEquals(i, in)
        reason.foreach(assertEquals(r, _))
      }
      expectErr(ArxivId.normalize(in)) { case InvalidArxiv(i, _) if i == in => () }
    }
  }

  test("InvalidArxiv has the code invalid-arxiv and a message naming the input") {
    val e = InvalidArxiv("x", "why")
    assertEquals(e.code, "invalid-arxiv")
    assert(e.message.contains("x") && e.message.contains("why"), e.message)
  }

  test("isArxivUrl recognises abs and pdf URLs only") {
    assert(ArxivId.isArxivUrl("https://arxiv.org/abs/2101.00001"))
    assert(ArxivId.isArxivUrl("  HTTP://WWW.ARXIV.ORG/PDF/2101.00001"))
    assert(!ArxivId.isArxivUrl("arXiv:2101.00001"))
    assert(!ArxivId.isArxivUrl("https://doi.org/10.1000/abc"))
  }
