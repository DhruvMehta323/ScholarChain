package scholarchain.rag

/** Checks that a rendered text invents no number. A "number" is a maximal run `\d+` optionally
  * followed by `.\d+`. Every number in the rendered text must also occur as a number in the source
  * row (all source field values, joined). The templates contain no numeric literal, so every digit
  * in a rendered text comes from a source field.
  *
  * A chunk boundary that cut a number in two would produce a number that is not in the source and
  * is reported here; the chunker cuts at whitespace so this does not happen unless a window holds
  * no whitespace at all.
  */
object NumberCheck:
  private val Number = """\d+(?:\.\d+)?""".r

  def numbers(text: String): List[String] = Number.findAllIn(text).toList

  /** Numbers of `rendered` that do not occur as numbers in `source`; distinct, sorted. */
  def unsupportedNumbers(rendered: String, source: String): List[String] =
    val known = numbers(source).toSet
    numbers(rendered).filterNot(known.contains).distinct.sorted

  def check(doc: RenderedDocument): Either[List[String], Unit] =
    unsupportedNumbers(doc.text, doc.sourceRow) match
      case Nil => Right(())
      case bad => Left(bad)
