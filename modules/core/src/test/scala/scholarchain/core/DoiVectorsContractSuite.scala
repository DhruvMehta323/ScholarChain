package scholarchain.core

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import ValidationError.*

/** Runs every row of the shared DOI vector file (the single source of truth for Scala and Python)
  * against `Doi.normalize`.
  *
  * Escape reader (mirrored by the Python reader), applied to the `input` and `expected` cells only:
  *   - file is strict UTF-8, no BOM, LF only (no CR); `#` lines and blank lines are skipped
  *   - first non-comment line is exactly `input<TAB>expected<TAB>note`; every row has 3 cells
  *   - a raw WS-set character, C0/C1 control, DEL or U+200B inside an input/expected cell is a hard
  *     reader error (they must be escaped)
  *   - escapes, left to right: `\\` backslash; `\uXXXX` exactly 4 hex digits = one UTF-16 unit (BMP
  *     or lone surrogate); `\UXXXXXXXX` exactly 8 hex digits = one non-surrogate code point (at
  *     most U+10FFFF); `\e` only as the whole cell = empty string; anything else is an error
  *   - a high-surrogate `\u` escape immediately followed by a low-surrogate `\u` escape is an error
  *   - hex digits are ASCII 0-9 a-f A-F only
  */
class DoiVectorsContractSuite extends ContractSupport:
  import DoiVectorsContractSuite.*

  private lazy val rows: List[Row] = loadRows()

  test("vector file has enough rows to not be empty or truncated") {
    assert(rows.size >= 80, s"only ${rows.size} data rows")
  }

  test("vector file covers both accepted and rejected rows") {
    assert(rows.exists(_.expected.isDefined))
    assert(rows.exists(_.expected.isEmpty))
  }

  test("escape reader rejects malformed cells loudly") {
    List(
      "\\x",
      "\\",
      "a\\",
      "\\u12",
      "\\u12G4",
      "\\U0001F60",
      "\\U0000D800",
      "\\U00110000",
      "a\\e",
      "\\ea",
      "\\uD83D\\uDE00",
      "a b",
      "a b",
      "a​b",
      "a\u0001b",
      "a\u007fb",
      "\\uFF1G",
    ).foreach { bad =>
      intercept[IllegalArgumentException](unescape(bad))
    }
    assertEquals(unescape("\\e"), "")
    assertEquals(unescape("\\\\"), "\\")
    assertEquals(unescape("\\U0001F600"), new String(Character.toChars(0x1f600)))
    assertEquals(unescape("\\uD800"), "\ud800")
    assertEquals(unescape("\\uD83D\\u0041"), "\ud83dA")
  }

  rowsAtDiscovery.foreach { r =>
    test(s"line ${r.line}: ${r.note}") {
      val out = Doi.normalize(r.input)
      r.expected match
        case Some(exp) =>
          assertEquals(out, Right(exp), s"input=${r.rawInput}")
          // idempotence of every accepted row
          assertEquals(Doi.normalize(exp), Right(exp), s"not idempotent: ${r.rawExpected}")
        case None =>
          expectErr(out) { case InvalidDoi(i, _) if i == r.input => () }
    }
  }

object DoiVectorsContractSuite:
  final case class Row(
      line: Int,
      rawInput: String,
      rawExpected: String,
      input: String,
      expected: Option[String],
      note: String,
  )

  private val Resource = "/doi-normalization-vectors.tsv"

  private def isWsCp(cp: Int): Boolean =
    (cp >= 0x9 && cp <= 0xd) || (cp >= 0x1c && cp <= 0x20) || cp == 0x85 || cp == 0xa0 ||
      cp == 0x1680 || (cp >= 0x2000 && cp <= 0x200a) || cp == 0x2028 || cp == 0x2029 ||
      cp == 0x202f || cp == 0x205f || cp == 0x3000

  private def isCtrlCp(cp: Int): Boolean = cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f)

  private def hexVal(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else throw new IllegalArgumentException(s"bad hex digit '$c'")

  private def hex(s: String, from: Int, n: Int): Int =
    if from + n > s.length then throw new IllegalArgumentException(s"truncated escape in '$s'")
    (from until from + n).foldLeft(0)((acc, i) => acc * 16 + hexVal(s.charAt(i)))

  /** Decode one input/expected cell. Throws IllegalArgumentException on any violation. */
  def unescape(cell: String): String =
    cell.codePoints().toArray.foreach { cp =>
      if isWsCp(cp) || isCtrlCp(cp) || cp == 0x200b then
        throw new IllegalArgumentException(
          f"raw character U+$cp%04X must be escaped in cell '$cell'"
        )
    }
    if cell == "\\e" then ""
    else
      val sb           = new StringBuilder
      var i            = 0
      var prevHighViaU = false
      while i < cell.length do
        val c = cell.charAt(i)
        if c != '\\' then
          sb.append(c)
          prevHighViaU = false
          i += 1
        else
          if i + 1 >= cell.length then
            throw new IllegalArgumentException(s"dangling backslash in '$cell'")
          cell.charAt(i + 1) match
            case '\\' =>
              sb.append('\\')
              prevHighViaU = false
              i += 2
            case 'u' =>
              val unit = hex(cell, i + 2, 4)
              if prevHighViaU && Character.isLowSurrogate(unit.toChar) then
                throw new IllegalArgumentException(
                  s"high and low surrogate \\u escapes adjacent in '$cell'; use \\U"
                )
              sb.append(unit.toChar)
              prevHighViaU = Character.isHighSurrogate(unit.toChar)
              i += 6
            case 'U' =>
              val cp = hex(cell, i + 2, 8)
              if cp < 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) then
                throw new IllegalArgumentException(f"invalid code point $cp%X in '$cell'")
              sb.appendAll(Character.toChars(cp))
              prevHighViaU = false
              i += 10
            case other =>
              throw new IllegalArgumentException(s"unknown escape \\$other in '$cell'")
      sb.toString

  private def loadRows(): List[Row] =
    val in = getClass.getResourceAsStream(Resource)
    if in == null then throw new IllegalStateException(s"missing test resource $Resource")
    val bytes = try in.readAllBytes()
    finally in.close()
    if bytes.length >= 3 && bytes(0) == 0xef.toByte && bytes(1) == 0xbb.toByte && bytes(
        2
      ) == 0xbf.toByte
    then throw new IllegalStateException("vector file has a UTF-8 BOM")
    val text =
      try
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      catch
        case e: CharacterCodingException =>
          throw new IllegalStateException("vector file is not valid UTF-8", e)
    if text.indexOf('\r') >= 0 then throw new IllegalStateException("vector file contains CR")
    var header = false
    val out    = List.newBuilder[Row]
    text.split("\n", -1).zipWithIndex.foreach { case (line, idx) =>
      val n = idx + 1
      if line.isEmpty || line.startsWith("#") then ()
      else
        val cells = line.split("\t", -1)
        if cells.length != 3 then
          throw new IllegalStateException(
            s"line $n: expected 3 tab-separated cells, got ${cells.length}"
          )
        if !header then
          if cells.toList != List("input", "expected", "note") then
            throw new IllegalStateException(s"line $n: bad header")
          header = true
        else
          val input = try unescape(cells(0))
          catch
            case e: IllegalArgumentException =>
              throw new IllegalStateException(s"line $n input: ${e.getMessage}", e)
          val expected =
            if cells(1) == "REJECT" then None
            else
              Some(try unescape(cells(1))
              catch
                case e: IllegalArgumentException =>
                  throw new IllegalStateException(s"line $n expected: ${e.getMessage}", e))
          if cells(2).isEmpty then throw new IllegalStateException(s"line $n: empty note")
          out += Row(n, cells(0), cells(1), input, expected, cells(2))
    }
    if !header then throw new IllegalStateException("vector file has no header")
    out.result()

  // munit registers tests while constructing the class, so rows are loaded eagerly here.
  private lazy val discovered: List[Row] = loadRows()
  def rowsAtDiscovery: List[Row]         = discovered
