package scholarchain.er

import java.text.Normalizer
import java.util.Locale

import scholarchain.core.Titles

/** Pure string helpers and the in-module string metrics (no library).
  *
  * Unicode policy (closes Phase 04 gaps 1 and 2):
  *   - NFC and NFD forms of the same text are equal after `nfkc` (compatibility composition), so
  *     "Müller" and "Müller" are one name for every feature.
  *   - Comparison strings additionally fold accents: NFD, combining marks removed, plus a small
  *     table for letters that do not decompose (ss, o, d, l, ae, oe, dotless i, eth, thorn).
  *   - Confusable characters (a Cyrillic U+0430 inside a Latin name) are NOT folded: folding would
  *     over-merge distinct strings. A token that mixes Latin, Cyrillic or Greek letters is flagged
  *     (`Names.ParsedName.mixedScript`, feature `name_mixed_script`) and may never be accepted by a
  *     deterministic rule.
  */
object Strings:
  def nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

  /** Dash punctuation that is read as an ASCII hyphen: U+2010 to U+2015 (hyphen, non-breaking
    * hyphen, figure dash, en dash, em dash, horizontal bar), U+2212 (minus), U+FE58, U+FE63 and
    * U+FF0D. U+00AD (soft hyphen) is invisible and is dropped.
    */
  private def isDash(c: Char): Boolean =
    (c >= '\u2010' && c <= '\u2015') || c == '\u2212' || c == '\uFE58' || c == '\uFE63' ||
      c == '\uFF0D'

  /** NFKC, then every Unicode dash becomes `-` and every soft hyphen is removed. Every name path
    * (tokens, name tokens, `Names.parse`) starts from this form, so a family name written with
    * U+2010, U+2013 or an ASCII hyphen is one name. A soft hyphen is removed without a separator,
    * so it joins the two parts ("hernandezsolana", same block key as the hyphenated form).
    */
  def canon(s: String): String =
    nfkc(s).flatMap {
      case '\u00AD'       => ""
      case c if isDash(c) => "-"
      case c              => c.toString
    }

  private val Special: Map[Char, String] = Map(
    'ß' -> "ss",
    'ø' -> "o",
    'đ' -> "d",
    'ł' -> "l",
    'æ' -> "ae",
    'œ' -> "oe",
    'ı' -> "i",
    'ð' -> "d",
    'þ' -> "th",
  )

  /** Lowercase, remove accents. Input is expected to be NFKC already. */
  def foldAccents(s: String): String =
    val d = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
    val b = StringBuilder()
    d.foreach { c =>
      if Character.getType(c) == Character.NON_SPACING_MARK.toInt then ()
      else b.append(Special.getOrElse(c, c.toString))
    }
    b.toString

  /** Lowercase letters and digits as space-separated tokens: accents folded, apostrophes dropped,
    * every other punctuation mark (including hyphen and period) becomes a space.
    */
  def tokens(s: String): List[String] = tokenize(s, keepHyphen = false)

  /** Like `tokens`, but a hyphen between two letters or digits stays inside the token
    * ("smith-jones"), so a hyphenated family name is one unit. Any other hyphen is a separator.
    */
  def nameTokens(s: String): List[String] = tokenize(s, keepHyphen = true)

  private def tokenize(s: String, keepHyphen: Boolean): List[String] =
    val cps = foldAccents(canon(s)).replace("'", "").replace("’", "").codePoints.toArray
    val b   = new java.lang.StringBuilder()
    var i   = 0
    while i < cps.length do
      val cp = cps(i)
      if Character.isLetterOrDigit(cp) then b.appendCodePoint(cp)
      else if keepHyphen && cp == '-' && i > 0 && i < cps.length - 1 &&
        Character.isLetterOrDigit(cps(i - 1)) && Character.isLetterOrDigit(cps(i + 1))
      then b.append('-')
      else b.append(' ')
      i += 1
    b.toString.split("\\s+").toList.filter(_.nonEmpty)

  private val Tag = "<[^>]*>".r

  /** Title for comparison: markup stripped, a few HTML entities decoded, then core's
    * `Titles.titleKey` (NFKC, lower case, trim, collapsed whitespace), then punctuation removed.
    */
  def titleNorm(title: String): String =
    val noMarkup = Tag
      .replaceAllIn(title, " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
    Titles
      .titleKey(noMarkup)
      .map(c => if Character.isLetterOrDigit(c) || Character.isWhitespace(c) then c else ' ')
      .replaceAll("\\s+", " ")
      .trim

  /** Free text (venue, affiliation) for comparison. */
  def textNorm(s: String): String = tokens(s).mkString(" ")

object Metrics:
  /** Jaro similarity in [0,1]. Two empty strings give 1.0, one empty string 0.0. */
  def jaro(a: String, b: String): Double =
    if a.isEmpty && b.isEmpty then 1.0
    else if a.isEmpty || b.isEmpty then 0.0
    else
      val window = math.max(0, math.max(a.length, b.length) / 2 - 1)
      val aM     = Array.fill(a.length)(false)
      val bM     = Array.fill(b.length)(false)
      var m      = 0
      var i      = 0
      while i < a.length do
        val lo    = math.max(0, i - window)
        val hi    = math.min(b.length - 1, i + window)
        var j     = lo
        var found = false
        while j <= hi && !found do
          if !bM(j) && a(i) == b(j) then
            aM(i) = true
            bM(j) = true
            m += 1
            found = true
          j += 1
        i += 1
      if m == 0 then 0.0
      else
        var k = 0
        var t = 0
        i = 0
        while i < a.length do
          if aM(i) then
            while !bM(k) do k += 1
            if a(i) != b(k) then t += 1
            k += 1
          i += 1
        val md = m.toDouble
        (md / a.length + md / b.length + (md - t / 2.0) / md) / 3.0

  /** Jaro-Winkler: Jaro plus a prefix boost (scaling 0.1, prefix at most 4) when Jaro > 0.7. */
  def jaroWinkler(a: String, b: String): Double =
    val j = jaro(a, b)
    if j <= 0.7 then j
    else
      val prefix = a.zip(b).takeWhile((x, y) => x == y).size.min(4)
      j + prefix * 0.1 * (1.0 - j)

  private def trigrams(s: String): Set[String] =
    val p = "  " + s + " "
    p.sliding(3).toSet

  /** Sorensen-Dice coefficient over the sets of character trigrams (padded). Empty input gives 0.
    */
  def trigramDice(a: String, b: String): Double =
    if a.isEmpty || b.isEmpty then 0.0
    else
      val x = trigrams(a)
      val y = trigrams(b)
      2.0 * x.intersect(y).size / (x.size + y.size)

  def jaccard[A](a: Set[A], b: Set[A]): Double =
    if a.isEmpty && b.isEmpty then 0.0 else a.intersect(b).size.toDouble / a.union(b).size

  /** |A and B| / min(|A|,|B|); 0 when either set is empty. */
  def overlapCoefficient[A](a: Set[A], b: Set[A]): Double =
    if a.isEmpty || b.isEmpty then 0.0 else a.intersect(b).size.toDouble / math.min(a.size, b.size)

object Names:
  /** A parsed personal name. `family` and `given` are comparison forms (accent-folded, lower case).
    * `suffix` is the DBLP homonym suffix ("0001"), which is identity evidence: it is kept here,
    * left out of every comparison string, and reported by the features.
    */
  final case class ParsedName(
      family: String,
      givens: List[String],
      suffix: Option[String],
      mixedScript: Boolean,
  ):
    def familyKey: String            = family.replace(" ", "")
    def initials: String             = givens.map(Names.firstCp).mkString
    def firstInitial: Option[String] = givens.headOption.map(Names.firstCp)
    def givenText: String            = givens.mkString(" ")

  /** The first code point of a non-empty string, as a string. */
  def firstCp(s: String): String = new String(Character.toChars(s.codePointAt(0)))

  private val Suffix    = """^(.*\S)\s+(\d{4})$""".r
  private val Particles = Set(
    "van",
    "von",
    "de",
    "der",
    "den",
    "da",
    "di",
    "del",
    "della",
    "du",
    "la",
    "le",
    "bin",
    "ibn",
    "al",
    "el",
    "dos",
    "das",
    "ten",
    "ter",
  )

  private val Scripts = Set(
    Character.UnicodeScript.LATIN,
    Character.UnicodeScript.CYRILLIC,
    Character.UnicodeScript.GREEK,
  )

  /** True when one whitespace-separated token mixes letters of two of Latin, Cyrillic, Greek. */
  def hasMixedScript(s: String): Boolean =
    s.split("\\s+").exists { tok =>
      tok.codePoints.toArray
        .filter(Character.isLetter(_))
        .map(Character.UnicodeScript.of(_))
        .filter(Scripts.contains)
        .distinct
        .length > 1
    }

  /** Parses "First Last", "Last, First", "F. Last", "J.-P. Last" and DBLP names with a four-digit
    * homonym suffix. A name with no letters or digits gives `None`.
    */
  def parse(raw: String): Option[ParsedName] =
    val s0          = Strings.canon(raw).trim
    val (body, sfx) = s0 match
      case Suffix(b, n) => (b, Some(n))
      case _            => (s0, None)
    val mixed = hasMixedScript(body)
    val comma = body.indexOf(',')
    // hyphens inside a token are kept while the family is chosen, then expanded
    def spaced(t: String): String                 = t.replace('-', ' ')
    def givenList(ts: List[String]): List[String] =
      ts.flatMap(_.split('-').toList).filter(_.nonEmpty)
    val (family, gv) =
      if comma >= 0 then
        (
          Strings.nameTokens(body.substring(0, comma)).map(spaced).mkString(" "),
          givenList(Strings.nameTokens(body.substring(comma + 1))),
        )
      else
        val toks = Strings.nameTokens(body)
        if toks.size <= 1 then (toks.map(spaced).mkString(" "), Nil)
        else
          var idx = toks.size - 1
          while idx > 1 && Particles.contains(toks(idx - 1)) do idx -= 1
          (toks.drop(idx).map(spaced).mkString(" "), givenList(toks.take(idx)))
    if family.isEmpty then None else Some(ParsedName(family, gv, sfx, mixed))

  /** 1.0 when the initials agree on every shared position, 0.25 when the first initial agrees and a
    * later one does not, 0.0 when the first initial differs; `None` when either side has no given
    * name.
    */
  def initialsCompat(a: ParsedName, b: ParsedName): Option[Double] =
    if a.givens.isEmpty || b.givens.isEmpty then None
    else
      val (x, y) = (a.initials.codePoints.toArray.toList, b.initials.codePoints.toArray.toList)
      val n      = math.min(x.length, y.length)
      if x.head != y.head then Some(0.0)
      else if x.take(n) == y.take(n) then Some(1.0)
      else Some(0.25)

  /** Name similarity in [0,1]: 0.6 * JaroWinkler(family) + 0.4 * given-name similarity. The
    * given-name part is JaroWinkler of the full given names, or an initial match (1.0 / 0.0) when
    * either side's first given token is a single letter. With no given name on a side only the
    * family part counts.
    */
  def similarity(a: ParsedName, b: ParsedName): Double =
    val fam = Metrics.jaroWinkler(a.family, b.family)
    if a.givens.isEmpty || b.givens.isEmpty then fam
    else
      val (ga, gb) = (a.givens.head, b.givens.head)
      val giv      =
        if ga.codePointCount(0, ga.length) == 1 || gb.codePointCount(0, gb.length) == 1 then
          (if firstCp(ga) == firstCp(gb) then 1.0 else 0.0
        )
        else Metrics.jaroWinkler(a.givenText, b.givenText)
      0.6 * fam + 0.4 * giv

  /** Person key used for coauthor comparison: family without spaces plus first initial. */
  def personKey(raw: String): Option[String] =
    parse(raw).map(p => p.familyKey + "|" + p.firstInitial.getOrElse("_"))
