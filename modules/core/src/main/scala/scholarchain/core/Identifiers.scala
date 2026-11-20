package scholarchain.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

import ValidationError.*
import WirePickle.{ReadWriter, given}

/** Hashing used by every hashed identifier (frozen contract).
  *
  * Canonical byte encoding: the sequence `tag :: fields` is written as, for each element, a 4-byte
  * big-endian length followed by its UTF-8 bytes. The encoding is prefix-free, so two different
  * field sequences can never produce the same bytes (no separator collisions). The digest is
  * SHA-256, rendered as 64 lowercase hex characters. Lists are encoded as a count field followed by
  * the elements. Text is hashed as raw UTF-8 with no Unicode normalization.
  */
object Hashing:
  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def textHash(text: String): String = sha256Hex(text.getBytes(StandardCharsets.UTF_8))

  def digest(tag: String, fields: Seq[String]): String =
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    (tag +: fields).foreach { f =>
      val b = f.getBytes(StandardCharsets.UTF_8)
      dos.writeInt(b.length)
      dos.write(b)
    }
    dos.flush()
    sha256Hex(out.toByteArray)

  private[core] val Hex64             = "^[0-9a-f]{64}$".r
  def isSha256Hex(s: String): Boolean = Hex64.matches(s)

/** DOI normalization. Canonical rule (ratified, frozen; Python and Scala must agree):
  *   1. Trim leading and trailing characters in WS = U+0009-000D, U+001C-001F, U+0020, U+0085,
  *      U+00A0, U+1680, U+2000-200A, U+2028, U+2029, U+202F, U+205F, U+3000. (`String.strip` and
  *      the regex class for whitespace are not used.) A lone surrogate anywhere in the input is
  *      rejected.
  *   2. Remove at most one prefix, ASCII-case-insensitively (no Unicode case folding):
  *      `https://doi.org/`, `http://doi.org/`, `https://dx.doi.org/`, `http://dx.doi.org/` (URL
  *      form), or `doi:` followed by zero or more U+0020 only (not URL form). Bare input is not URL
  *      form.
  *   3. URL form only: percent-decode exactly once. Every `%` must be followed by two hex digits
  *      (else reject). The resulting bytes are decoded as strict UTF-8 (malformed or unmappable
  *      input is rejected). `+` stays `+`. Bare and `doi:` input are never decoded; `%` there is
  *      literal.
  *   4. Lowercase ASCII `A-Z` only. Non-ASCII letters keep their case.
  *   5. Reject if empty. The result must fully match `10.` + 4 to 9 ASCII digits + `/` + one or
  *      more code points, none in WS and none in CTRL = U+0000-001F, U+007F-009F. No NFC/NFD, no
  *      query or fragment stripping; zero-width characters are accepted.
  *
  * The normalized form is the lowercase DOI without any prefix. Every rejection is
  * `Left(InvalidDoi(input, reason))` carrying the original, untrimmed input.
  */
object Doi:
  private val UrlPrefixes = List(
    "https://doi.org/",
    "http://doi.org/",
    "https://dx.doi.org/",
    "http://dx.doi.org/",
  )
  private val DoiScheme = "doi:"

  private[core] def isWs(c: Char): Boolean =
    (c >= '\u0009' && c <= '\u000d') || (c >= '\u001c' && c <= ' ') || c == '\u0085' ||
      c == ' ' || c == ' ' || (c >= ' ' && c <= ' ') || c == ' ' ||
      c == ' ' || c == ' ' || c == ' ' || c == '　'

  private def isCtrl(cp: Int): Boolean = (cp >= 0 && cp <= 0x1f) || (cp >= 0x7f && cp <= 0x9f)

  private def asciiLower(c: Char): Char = if c >= 'A' && c <= 'Z' then (c + 32).toChar else c

  private def startsWithAsciiCi(s: String, prefix: String): Boolean =
    s.length >= prefix.length &&
      prefix.indices.forall(i => asciiLower(s.charAt(i)) == prefix.charAt(i))

  private def hexVal(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else -1

  private def hasLoneSurrogate(s: String): Boolean =
    var i   = 0
    var bad = false
    while !bad && i < s.length do
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) then
        if i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) then i += 2
        else bad = true
      else if Character.isLowSurrogate(c) then bad = true
      else i += 1
    bad

  /** Strict single-pass percent-decoding followed by strict UTF-8 decoding. */
  private def percentDecode(s: String): Either[String, String] =
    val out = ByteArrayOutputStream()
    var i   = 0
    var err = Option.empty[String]
    while err.isEmpty && i < s.length do
      val c = s.charAt(i)
      if c == '%' then
        val h = if i + 1 < s.length then hexVal(s.charAt(i + 1)) else -1
        val l = if i + 2 < s.length then hexVal(s.charAt(i + 2)) else -1
        if h < 0 || l < 0 then err = Some("malformed percent escape")
        else
          out.write(h * 16 + l)
          i += 3
      else
        var j = i
        while j < s.length && s.charAt(j) != '%' do j += 1
        out.write(s.substring(i, j).getBytes(StandardCharsets.UTF_8))
        i = j
    err match
      case Some(e) => Left(e)
      case None    =>
        try
          Right(
            StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(out.toByteArray))
              .toString
          )
        catch case _: CharacterCodingException => Left("percent-decoded bytes are not valid UTF-8")

  private def shapeOk(s: String): Boolean =
    if !s.startsWith("10.") then false
    else
      var i = 3
      while i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9' do i += 1
      val digits = i - 3
      digits >= 4 && digits <= 9 && i + 1 < s.length && s.charAt(i) == '/' &&
      s.substring(i + 1)
        .codePoints()
        .toArray
        .forall(cp => !(cp <= 0xffff && isWs(cp.toChar)) && !isCtrl(cp))

  def normalize(input: String): Either[ValidationError, String] =
    def bad(reason: String) = Left(InvalidDoi(input, reason))
    var start               = 0
    var end                 = input.length
    while start < end && isWs(input.charAt(start)) do start += 1
    while end > start && isWs(input.charAt(end - 1)) do end -= 1
    val trimmed = input.substring(start, end)
    if hasLoneSurrogate(input) then bad("lone surrogate")
    else
      val (body, urlForm) =
        UrlPrefixes.find(startsWithAsciiCi(trimmed, _)) match
          case Some(p) => (trimmed.substring(p.length), true)
          case None    =>
            if startsWithAsciiCi(trimmed, DoiScheme) then
              (trimmed.substring(DoiScheme.length).dropWhile(_ == ' '), false)
            else (trimmed, false)
      val decoded = if urlForm then percentDecode(body) else Right(body)
      decoded match
        case Left(reason) => bad(reason)
        case Right(d)     =>
          val lower = d.map(asciiLower)
          if lower.isEmpty then bad("empty after normalization")
          else if shapeOk(lower) then Right(lower)
          else bad("does not match 10.<4-9 digits>/<suffix without whitespace or control>")

/** ORCID iD handling. Canonical form `dddd-dddd-dddd-dddX` (uppercase X) with the ISO 7064 mod 11-2
  * check digit verified. Accepts an optional `https://orcid.org/` or `http://orcid.org/` prefix.
  */
object Orcid:
  private val Pattern = """^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$""".r

  def parse(input: String): Either[ValidationError, String] =
    val s    = input.strip().toUpperCase(Locale.ROOT)
    val bare =
      List("HTTPS://ORCID.ORG/", "HTTP://ORCID.ORG/").find(s.startsWith) match
        case Some(p) => s.substring(p.length)
        case None    => s
    if !Pattern.matches(bare) then Left(InvalidOrcid(input, "wrong shape"))
    else if !checksumOk(bare) then Left(InvalidOrcid(input, "check digit mismatch"))
    else Right(bare)

  private def checksumOk(canonical: String): Boolean =
    val digits = canonical.filter(_ != '-')
    val total  = digits.take(15).foldLeft(0)((acc, c) => (acc + (c - '0')) * 2)
    val r      = (12 - total % 11) % 11
    val expect = if r == 10 then 'X' else ('0' + r).toChar
    digits(15) == expect

/** Inclusive year range used as a graph window. Invalid when start year exceeds end year. */
final case class GraphWindow(startYear: Int, endYear: Int) derives ReadWriter

object GraphWindow:
  def validate(w: GraphWindow): Either[ValidationError, GraphWindow] =
    if w.startYear < PublicationYear.Min || w.startYear > PublicationYear.Max then
      Left(InvalidYear(w.startYear))
    else if w.endYear < PublicationYear.Min || w.endYear > PublicationYear.Max then
      Left(InvalidYear(w.endYear))
    else if w.startYear > w.endYear then Left(InvalidWindow(w.startYear, w.endYear))
    else Right(w)

  /** Wire label used inside identifiers, for example `2000-2004`. */
  def label(w: GraphWindow): String = s"${w.startYear}-${w.endYear}"

/** Structural publication-year bound: four digits. Policy limits belong to configuration. */
object PublicationYear:
  val Min: Int = 1000
  val Max: Int = 9999

  def validate(year: Int): Either[ValidationError, Int] =
    if year >= Min && year <= Max then Right(year) else Left(InvalidYear(year))

/** Undirected researcher pair in canonical order: `first` < `second` by `String.compareTo` (UTF-16
  * code unit order, identical to byte order for the ASCII researcher IDs). Self-pairs are rejected.
  */
final case class OrderedPair(first: String, second: String) derives ReadWriter

object OrderedPair:
  /** Canonicalizes two researcher IDs regardless of argument order. */
  def canonical(x: String, y: String): Either[ValidationError, OrderedPair] =
    for
      _ <- Ids.checkResearcherId(x)
      _ <- Ids.checkResearcherId(y)
      p <-
        if x == y then Left(InvalidPair(x, y, "self-pair"))
        else if x.compareTo(y) < 0 then Right(OrderedPair(x, y))
        else Right(OrderedPair(y, x))
    yield p

  /** Rejects a pair that is not already in canonical order. */
  def validate(p: OrderedPair): Either[ValidationError, OrderedPair] =
    for
      _ <- Ids.checkResearcherId(p.first)
      _ <- Ids.checkResearcherId(p.second)
      v <-
        if p.first == p.second then Left(InvalidPair(p.first, p.second, "self-pair"))
        else if p.first.compareTo(p.second) > 0 then
          Left(InvalidPair(p.first, p.second, "not in canonical order"))
        else Right(p)
    yield v

/** Stable identifier functions. Every ID is `<kind prefix>:<body>`.
  *
  * Formulas (H = `Hashing.digest(tag, fields)`; all tags end in `.v1`):
  *   - work: `work:<ns>:<sourceId>` with ns in dblp|doi|openalex|crossref (doi sourceId is the
  *     normalized DOI); fallback without an authoritative id:
  *     `work:sc1:H("scholarchain.work.v1", titleKey, year, firstAuthorKey)` where titleKey is the
  *     NFKC, lowercase, whitespace-collapsed title.
  *   - researcher: `researcher:orcid:<canonical ORCID>` when the ORCID passes shape and checksum,
  *     else `researcher:sc1:H("scholarchain.researcher.v1", identityKey)`.
  *   - coauthor edge: `edge:coauthor:H("scholarchain.coauthor-edge.v1", first, second, windowLabel,
  *     policyVersion)` with (first, second) the canonical OrderedPair.
  *   - chunk or profile: `chunk:H("scholarchain.chunk.v1", entityId, sourceVersion, docType,
  *     ordinalOrDash, textHash)`.
  *   - graph edge: `gedge:H("scholarchain.graph-edge.v1", edgeType, from, to, directed,
  *     policyVersion, count, sortedDistinctEvidenceIds...)`; undirected endpoints are ordered.
  *   - evidence: `evidence:H("scholarchain.evidence.v1", evidenceTypeWire, sourceEntityId,
  *     sourceVersion, contentHash)`.
  *   - mention: `mention:H("scholarchain.mention.v1", workId, position)`.
  *   - run: `run:H("scholarchain.run.v1", stage, codeVersion, attempt, paramCount, k, v...,
  *     inputCount, inputId, version, checksumOrDash...)` with params sorted by key and inputs
  *     sorted. No timestamp and no randomness are involved.
  */
object Ids:
  val WorkNamespaces: Set[String] = Set("dblp", "doi", "openalex", "crossref")

  private val Generic                = """^[a-z][a-z0-9-]*:\S+$""".r
  private val WorkSourced            = """^work:(dblp|doi|openalex|crossref):(\S+)$""".r
  private val WorkFallback           = """^work:sc1:([0-9a-f]{64})$""".r
  private val ResOrcid               = """^researcher:orcid:(\S+)$""".r
  private val ResSc                  = """^researcher:sc1:([0-9a-f]{64})$""".r
  private def hashed(prefix: String) = s"^${java.util.regex.Pattern.quote(prefix)}[0-9a-f]{64}$$".r
  private val CoauthorEdge           = hashed("edge:coauthor:")
  private val Chunk                  = hashed("chunk:")
  private val GraphEdgeP             = hashed("gedge:")
  private val Run                    = hashed("run:")
  private val Evidence               = hashed("evidence:")
  private val Mention                = hashed("mention:")

  // ---- validators -------------------------------------------------------------------------

  private def check(kind: String, v: String, ok: Boolean): Either[ValidationError, String] =
    if ok then Right(v) else Left(MalformedId(kind, v))

  def checkWorkId(v: String): Either[ValidationError, String] =
    val ok = v match
      case WorkSourced("doi", d) => Doi.normalize(d).contains(d)
      case WorkSourced(_, _)     => true
      case WorkFallback(_)       => true
      case _                     => false
    check("work", v, ok)

  def checkResearcherId(v: String): Either[ValidationError, String] =
    val ok = v match
      case ResOrcid(o) => Orcid.parse(o).contains(o)
      case ResSc(_)    => true
      case _           => false
    check("researcher", v, ok)

  def checkCoauthorEdgeId(v: String): Either[ValidationError, String] =
    check("coauthor-edge", v, CoauthorEdge.matches(v))
  def checkChunkId(v: String): Either[ValidationError, String] = check("chunk", v, Chunk.matches(v))
  def checkGraphEdgeId(v: String): Either[ValidationError, String] =
    check("graph-edge", v, GraphEdgeP.matches(v))
  def checkRunId(v: String): Either[ValidationError, String]      = check("run", v, Run.matches(v))
  def checkEvidenceId(v: String): Either[ValidationError, String] =
    check("evidence", v, Evidence.matches(v))
  def checkMentionId(v: String): Either[ValidationError, String] =
    check("mention", v, Mention.matches(v))

  /** Any `<lowercase-kind>:<non-whitespace>` reference, for ids owned by other contracts. */
  def checkEntityRef(kind: String, v: String): Either[ValidationError, String] =
    check(kind, v, Generic.matches(v) && v.startsWith(kind + ":") && v.length > kind.length + 1)

  def checkAnyRef(v: String): Either[ValidationError, String] =
    check("entity", v, Generic.matches(v))

  // ---- generators -------------------------------------------------------------------------

  def workIdFromSource(ns: String, sourceId: String): Either[ValidationError, String] =
    if !WorkNamespaces.contains(ns) then Left(MalformedId("work-namespace", ns))
    else if ns == "doi" then Doi.normalize(sourceId).map(d => s"work:doi:$d")
    else if sourceId.isEmpty || sourceId.exists(_.isWhitespace) then
      Left(MalformedId("work", s"$ns:$sourceId"))
    else Right(s"work:$ns:$sourceId")

  def workIdFallback(
      title: String,
      year: Int,
      firstAuthorKey: String,
  ): Either[ValidationError, String] =
    for
      _ <- PublicationYear.validate(year)
      titleKey = Normalizer
        .normalize(title, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .trim
        .replaceAll("\\s+", " ")
      _ <-
        if titleKey.isEmpty || firstAuthorKey.trim.isEmpty then
          Left(InvalidField("work", "title and first author key must be non-blank"))
        else Right(())
    yield s"work:sc1:${Hashing.digest("scholarchain.work.v1", Seq(titleKey, year.toString, firstAuthorKey))}"

  def researcherIdFromOrcid(orcid: String): Either[ValidationError, String] =
    Orcid.parse(orcid).map(o => s"researcher:orcid:$o")

  /** `identityKey` is the stable cluster seed chosen by entity resolution under a match policy. */
  def researcherIdScholarChain(identityKey: String): Either[ValidationError, String] =
    if identityKey.trim.isEmpty then Left(InvalidField("identityKey", "must be non-blank"))
    else Right(s"researcher:sc1:${Hashing.digest("scholarchain.researcher.v1", Seq(identityKey))}")

  def coauthorEdgeId(
      a: String,
      b: String,
      window: GraphWindow,
      policyVersion: String,
  ): Either[ValidationError, String] =
    for
      p <- OrderedPair.canonical(a, b)
      w <- GraphWindow.validate(window)
      _ <- token("policyVersion", policyVersion)
    yield s"edge:coauthor:${Hashing.digest("scholarchain.coauthor-edge.v1", Seq(p.first, p.second, GraphWindow.label(w), policyVersion))}"

  def chunkId(
      entityId: String,
      sourceVersion: String,
      docType: String,
      ordinal: Option[Int],
      textHash: String,
  ): Either[ValidationError, String] =
    for
      _ <- checkAnyRef(entityId)
      _ <- token("sourceVersion", sourceVersion)
      _ <- token("docType", docType)
      _ <-
        if ordinal.exists(_ < 0) then Left(InvalidField("ordinal", "must be >= 0")) else Right(())
      _ <-
        if Hashing.isSha256Hex(textHash) then Right(())
        else Left(MalformedId("text-hash", textHash))
    yield s"chunk:${Hashing.digest("scholarchain.chunk.v1", Seq(entityId, sourceVersion, docType, ordinal.fold("-")(_.toString), textHash))}"

  def graphEdgeId(
      edgeType: String,
      from: String,
      to: String,
      directed: Boolean,
      policyVersion: String,
      evidenceIds: Seq[String],
  ): Either[ValidationError, String] =
    for
      _ <- token("edgeType", edgeType)
      _ <- checkAnyRef(from)
      _ <- checkAnyRef(to)
      _ <- token("policyVersion", policyVersion)
      _ <- if from == to then Left(InvalidPair(from, to, "self-pair")) else Right(())
      _ <- evidenceIds.foldLeft[Either[ValidationError, Unit]](Right(()))((acc, e) =>
        acc.flatMap(_ => checkEvidenceId(e).map(_ => ()))
      )
    yield
      val (l, r) = if directed || from.compareTo(to) < 0 then (from, to) else (to, from)
      val ev     = evidenceIds.distinct.sorted
      s"gedge:${Hashing.digest("scholarchain.graph-edge.v1", Seq(edgeType, l, r, directed.toString, policyVersion, ev.size.toString) ++ ev)}"

  def evidenceId(
      evidenceType: EvidenceType,
      sourceEntityId: String,
      sourceVersion: String,
      contentHash: String,
  ): Either[ValidationError, String] =
    for
      _ <- checkAnyRef(sourceEntityId)
      _ <- token("sourceVersion", sourceVersion)
      _ <-
        if Hashing.isSha256Hex(contentHash) then Right(())
        else Left(MalformedId("content-hash", contentHash))
    yield s"evidence:${Hashing.digest("scholarchain.evidence.v1", Seq(evidenceType.wire, sourceEntityId, sourceVersion, contentHash))}"

  def mentionId(workId: String, position: Int): Either[ValidationError, String] =
    for
      _ <- checkWorkId(workId)
      _ <- if position < 0 then Left(InvalidField("position", "must be >= 0")) else Right(())
    yield s"mention:${Hashing.digest("scholarchain.mention.v1", Seq(workId, position.toString))}"

  def runId(
      stage: String,
      codeVersion: String,
      attempt: Int,
      parameters: Map[String, String],
      inputs: Seq[InputRef],
  ): Either[ValidationError, String] =
    for
      _ <- token("stage", stage)
      _ <- token("codeVersion", codeVersion)
      _ <- if attempt < 1 then Left(InvalidField("attempt", "must be >= 1")) else Right(())
    yield
      val params = parameters.toList.sortBy(_._1).flatMap((k, v) => List(k, v))
      val ins    = inputs.toList
        .map(i => (i.inputId, i.version, i.checksum.getOrElse("-")))
        .sorted
        .flatMap((a, b, c) => List(a, b, c))
      val fields = List(stage, codeVersion, attempt.toString, parameters.size.toString) ++ params ++
        List(inputs.size.toString) ++ ins
      s"run:${Hashing.digest("scholarchain.run.v1", fields)}"

  /** Non-blank, no whitespace. */
  private[core] def token(field: String, v: String): Either[ValidationError, Unit] =
    if v.nonEmpty && !v.exists(_.isWhitespace) then Right(())
    else Left(InvalidField(field, "must be non-blank and contain no whitespace"))
