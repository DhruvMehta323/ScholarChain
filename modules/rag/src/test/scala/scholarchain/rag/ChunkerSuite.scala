package scholarchain.rag

import RagFx.*

/** Chunk boundaries, ordinals, overlap, coverage and policy validation. */
class ChunkerSuite extends munit.FunSuite:

  private val Small = ChunkPolicy(200, 40)

  private def chunks(text: String, p: ChunkPolicy = Small): Vector[String] =
    Chunker.chunk(text, p).fold(e => fail(e), identity)

  /** Start offset of each chunk in `text` (the numbered-word texts make every chunk unique). */
  private def starts(text: String, cs: Vector[String]): Vector[Int] =
    cs.foldLeft((Vector.empty[Int], 0)) { case ((acc, from), c) =>
      val i = text.indexOf(c, from)
      assert(i >= 0, "chunk is not an exact substring of the text, in order")
      (acc :+ i, i + 1)
    }._1

  test("text of at most maxChars is one chunk, verbatim") {
    val t = words(30).take(200)
    assertEquals(t.length, 200)
    assertEquals(chunks(t), Vector(t))
    assertEquals(chunks("short"), Vector("short"))
  }

  test("text one character over maxChars is split in two or more chunks") {
    val t = words(30).take(200) + "x"
    assert(chunks(t).size >= 2)
  }

  test("every chunk is at most maxChars and is an exact substring (no trim)") {
    val t  = words(500)
    val cs = chunks(t)
    assert(cs.size > 5)
    cs.foreach(c => assert(c.length <= 200, c.length.toString))
    starts(t, cs) // asserts the substring property
  }

  test(
    "chunks cover the whole text: first starts at 0, last ends at the end, no gap between chunks"
  ) {
    val t  = words(500)
    val cs = chunks(t)
    val ss = starts(t, cs)
    assertEquals(ss.head, 0)
    assertEquals(ss.last + cs.last.length, t.length)
    ss.zip(cs).sliding(2).foreach { w =>
      val (s0, c0) = w(0)
      val (s1, _)  = w(1)
      assert(s1 <= s0 + c0.length, s"gap between chunks at $s0")
      assert(s1 > s0, "start must strictly increase")
    }
  }

  test("cuts fall on whitespace: no token is split") {
    val t  = words(500)
    val cs = chunks(t)
    val ss = starts(t, cs)
    ss.zip(cs).dropRight(1).foreach { (s, c) =>
      val end = s + c.length
      assert(
        t.charAt(end).isWhitespace || t.charAt(end - 1).isWhitespace,
        s"cut inside a token at $end",
      )
    }
    cs.tail
      .zip(ss.tail)
      .foreach((_, s) =>
        assert(s == 0 || t.charAt(s - 1).isWhitespace, s"start inside a token at $s")
      )
  }

  test("overlap is at most overlapChars and the next chunk starts at a token start") {
    val t  = words(500)
    val cs = chunks(t)
    val ss = starts(t, cs)
    ss.zip(cs).sliding(2).foreach { w =>
      val end0    = w(0)._1 + w(0)._2.length
      val overlap = end0 - w(1)._1
      assert(overlap <= Small.overlapChars, s"overlap $overlap")
      assert(overlap >= 0)
    }
  }

  test("zero overlap gives chunks that do not repeat text") {
    val t  = words(300)
    val cs = chunks(t, ChunkPolicy(200, 0))
    val ss = starts(t, cs)
    ss.zip(cs).sliding(2).foreach { w =>
      assert(w(1)._1 >= w(0)._1 + w(0)._2.length, "chunks must not overlap")
    }
  }

  test("chunking is deterministic") {
    val t = words(400)
    assertEquals(chunks(t), chunks(t))
  }

  test("a window with no whitespace is hard-cut at maxChars") {
    val t  = "x" * 450
    val cs = chunks(t)
    assert(cs.forall(_.length <= 200))
    assertEquals(cs.map(_.length).sum >= 450, true)
    assertEquals(cs.head.length, 200)
  }

  test("a hard cut never splits a surrogate pair") {
    val t  = "😀" * 400 // 800 UTF-16 chars, no whitespace
    val cs = chunks(t)
    assert(cs.size > 1)
    cs.foreach { c =>
      assert(!c.last.isHighSurrogate, "chunk ends inside a pair")
      assert(!c.head.isLowSurrogate, "chunk starts inside a pair")
      assertEquals(c, new String(c.getBytes("UTF-8"), "UTF-8"), "chunk is valid UTF-16")
    }
  }

  test(
    "invalid policies are Left: maxChars below 200 or above 20000, overlap outside 0 until maxChars/2"
  ) {
    val t = words(100)
    assert(Chunker.chunk(t, ChunkPolicy(199, 0)).isLeft)
    assert(Chunker.chunk(t, ChunkPolicy(20001, 0)).isLeft)
    assert(Chunker.chunk(t, ChunkPolicy(200, -1)).isLeft)
    assert(Chunker.chunk(t, ChunkPolicy(200, 100)).isLeft)
    assert(Chunker.chunk(t, ChunkPolicy(200, 99)).isRight)
    assert(Chunker.chunk(t, ChunkPolicy(20000, 0)).isRight)
    assertEquals(ChunkPolicy.Default, ChunkPolicy(2000, 200))
  }

  test("blank text is Left") {
    assert(Chunker.chunk("", Small).isLeft)
    assert(Chunker.chunk("   \n\t ", Small).isLeft)
  }

  test("more than 1000 chunks is Left; exactly at the bound is accepted") {
    val over = words(40000)
    assert(Chunker.chunk(over, ChunkPolicy(200, 0)).isLeft)
    val fine = Chunker.chunk(words(30000), ChunkPolicy(2000, 0))
    assert(fine.isRight, fine.left.toOption.toString)
    assert(fine.toOption.get.size <= 1000)
  }

  test("multi-byte text is measured in UTF-16 characters, not bytes") {
    val t  = ("é" * 150 + " ") * 4 // 604 chars, 2 bytes each in UTF-8
    val cs = chunks(t)
    assert(cs.forall(_.length <= 200))
  }
