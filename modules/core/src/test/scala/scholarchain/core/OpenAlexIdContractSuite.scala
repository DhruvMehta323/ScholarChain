package scholarchain.core

import ValidationError.*

/** OpenAlex identifier contract. The six accepts and the rejections are domain-engineer's A1r
  * lists, which A1r states were read from code and not executed.
  */
class OpenAlexIdContractSuite extends ContractSupport:

  private val accepts: List[(String, String)] = List(
    ("W2741809807", "W2741809807"),
    ("w2741809807", "W2741809807"),
    ("https://openalex.org/W2718513939", "W2718513939"),
    ("HTTP://OpenAlex.org/A5023888391", "A5023888391"),
    ("https://api.openalex.org/works/W2718513939", "W2718513939"),
    ("  A5023888391 ", "A5023888391"),
  )

  accepts.foreach { (in, out) =>
    test(s"accepts '$in'") {
      assertEquals(OpenAlexId.normalize(in), Right(out))
    }
  }

  test("accepts institutions, sources and topics, and 12 digits") {
    assertEquals(OpenAlexId.normalize("I123"), Right("I123"))
    assertEquals(OpenAlexId.normalize("s5"), Right("S5"))
    assertEquals(OpenAlexId.normalize("T10"), Right("T10"))
    assertEquals(OpenAlexId.normalize("W123456789012"), Right("W123456789012"))
    assertEquals(
      OpenAlexId.normalize("https://api.openalex.org/authors/A5023888391"),
      Right("A5023888391"),
    )
  }

  test("parse returns the entity kind") {
    assertEquals(OpenAlexId.parse("W1"), Right(OpenAlexRef(OpenAlexEntity.Work, "W1")))
    assertEquals(OpenAlexId.parse("a1"), Right(OpenAlexRef(OpenAlexEntity.Author, "A1")))
    assertEquals(OpenAlexId.parse("I1"), Right(OpenAlexRef(OpenAlexEntity.Institution, "I1")))
    assertEquals(OpenAlexId.parse("S1"), Right(OpenAlexRef(OpenAlexEntity.Source, "S1")))
    assertEquals(OpenAlexId.parse("T1"), Right(OpenAlexRef(OpenAlexEntity.Topic, "T1")))
  }

  test("normalize is idempotent on every accepted input") {
    accepts.foreach { (in, _) =>
      val once = right(OpenAlexId.normalize(in))
      assertEquals(OpenAlexId.normalize(once), Right(once))
    }
  }

  private val rejects: List[(String, String)] = List(
    ("", "empty"),
    ("W", "no digits"),
    ("2741809807", "no letter"),
    ("W0123", "leading zero"),
    ("W1234567890123", "13 digits"),
    ("C2741809807", "concept"),
    ("F4320", "funder"),
    ("https://openalex.org/W1/", "trailing slash"),
    ("https://openalex.org/W1?x=1", "query"),
    ("https://api.openalex.org/authors/W2718513939", "path and letter disagree"),
    ("https://api.openalex.org/concepts/C1", "concepts path"),
    ("ftp://openalex.org/W1", "ftp scheme"),
    ("W12 3", "inner space"),
  )

  rejects.foreach { (in, why) =>
    test(s"rejects '$in' ($why) with InvalidOpenAlex") {
      expectErr(OpenAlexId.parse(in)) { case InvalidOpenAlex(i, _) => assertEquals(i, in) }
      expectErr(OpenAlexId.normalize(in)) { case InvalidOpenAlex(i, _) if i == in => () }
    }
  }

  test("unsupported entity kinds are reported as such, path mismatch as such") {
    expectErr(OpenAlexId.parse("C2741809807")) { case InvalidOpenAlex(_, r) =>
      assert(r.contains("unsupported entity kind"), r)
    }
    expectErr(OpenAlexId.parse("https://api.openalex.org/authors/W2718513939")) {
      case InvalidOpenAlex(_, r) => assert(r.contains("API path"), r)
    }
  }

  test("InvalidOpenAlex has the code invalid-openalex and a message naming the input") {
    val e = InvalidOpenAlex("x", "why")
    assertEquals(e.code, "invalid-openalex")
    assert(e.message.contains("x") && e.message.contains("why"), e.message)
  }
