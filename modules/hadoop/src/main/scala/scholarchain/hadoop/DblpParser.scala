package scholarchain.hadoop

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.{Charset, CharsetDecoder, CodingErrorAction, StandardCharsets}

import javax.xml.XMLConstants
import javax.xml.stream.{
  XMLInputFactory,
  XMLResolver,
  XMLStreamConstants,
  XMLStreamException,
  XMLStreamReader,
}

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import scholarchain.core.*

/** The DBLP named entities, taken from the LOCAL `dblp.dtd` text. Only declarations whose value is
  * a numeric character reference (`<!ENTITY uuml "&#252;">`) are accepted: an entity can never
  * expand to markup, to another entity or to an external resource. Parameter entities and every
  * other form are ignored.
  */
final case class DblpEntities(table: Map[String, String]):
  /** The internal DTD subset that declares exactly these entities (sorted by name). */
  def internalSubset: String =
    table.toList.sortBy(_._1).map((n, v) => s"""<!ENTITY $n "$v">""").mkString("\n")

object DblpEntities:
  private val Decl =
    """<!ENTITY\s+([A-Za-z][A-Za-z0-9._-]*)\s+"(&#(?:[0-9]+|x[0-9A-Fa-f]+);)"\s*>""".r
  private val Reserved = Set("lt", "gt", "amp", "quot", "apos")

  /** DBLP's DTD is ASCII, so ISO-8859-1 decoding is lossless for it. */
  def fromDtd(dtd: Array[Byte]): DblpEntities =
    val text = new String(dtd, StandardCharsets.ISO_8859_1)
    DblpEntities(
      Decl
        .findAllMatchIn(text)
        .map(m => m.group(1) -> m.group(2))
        .filterNot(e => Reserved(e._1))
        .toMap
    )

sealed trait ParsedRecord
final case class ParsedWork(work: DblpWork, warnings: List[String])             extends ParsedRecord
final case class ParsedPersonRecord(person: DblpPerson, warnings: List[String]) extends ParsedRecord

/** Pure DBLP record parser: the bytes of ONE top-level record in, a typed result out.
  *
  * Safety: the record is wrapped in a document whose internal subset is built from `entities`; the
  * StAX reader (JDK default implementation) has external entities and external DTD access disabled
  * and a resolver that always fails, so no network or file access is possible. A record that
  * contains any markup declaration (`<!DOCTYPE`, `<!ENTITY`, ...) is rejected.
  *
  * An instance holds only immutable state and one reader factory; create one per task.
  */
final class DblpParser(entities: DblpEntities, charset: Charset):
  import DblpParser.*

  private val factory: XMLInputFactory =
    val f = XMLInputFactory.newDefaultFactory()
    f.setProperty(XMLInputFactory.SUPPORT_DTD, true)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true)
    f.setProperty(XMLInputFactory.IS_COALESCING, true)
    f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    f.setXMLResolver(new XMLResolver:
      override def resolveEntity(p: String, s: String, b: String, n: String): AnyRef =
        throw new XMLStreamException("external resources are not allowed"))
    f

  private val prolog: String =
    s"""<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE dblp [${entities.internalSubset}]><dblp>"""

  def parse(bytes: Array[Byte]): Either[ParseFailure, ParsedRecord] =
    decode(bytes).flatMap(text =>
      if Declaration.findFirstIn(text).isDefined then
        Left(
          ParseFailure(
            RejectRecordReasons.Malformed,
            "markup declarations are not allowed in a record",
          )
        )
      else
        try
          val r = factory.createXMLStreamReader(new StringReader(prolog + text + "</dblp>"))
          try interpret(readRoot(r))
          finally r.close()
        catch
          case e: XMLStreamException =>
            Left(ParseFailure(RejectRecordReasons.Malformed, s"XML error: ${shortMessage(e)}"))
          case NonFatal(e) =>
            Left(
              ParseFailure(RejectRecordReasons.Malformed, s"XML error: ${e.getClass.getSimpleName}")
            )
    )

  private def decode(bytes: Array[Byte]): Either[ParseFailure, String] =
    val dec: CharsetDecoder = charset
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(dec.decode(ByteBuffer.wrap(bytes)).toString)
    catch
      case _: java.nio.charset.CharacterCodingException =>
        Left(ParseFailure(RejectRecordReasons.Encoding, s"bytes are not valid ${charset.name()}"))

  /** Reads `<dblp><record/></dblp>` and returns the one record element. */
  private def readRoot(r: XMLStreamReader): Node =
    var rec: Option[Node] = None
    var depth             = 0
    var done              = false
    while !done && r.hasNext do
      r.next() match
        case XMLStreamConstants.START_ELEMENT =>
          if depth == 0 then depth = 1 // the dblp wrapper
          else if rec.isEmpty then rec = Some(readElement(r, 0))
          else throw new XMLStreamException("more than one top-level element in the record")
        case XMLStreamConstants.END_ELEMENT => done = depth == 1
        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA
            if depth == 1 && rec.nonEmpty =>
          if r.getText.exists(!_.isWhitespace) then
            throw new XMLStreamException("text after the record element")
        case _ => ()
    rec.getOrElse(throw new XMLStreamException("no record element"))

  private def readElement(r: XMLStreamReader, depth: Int): Node =
    if depth > MaxDepth then throw new XMLStreamException("element nesting too deep")
    val name  = r.getLocalName
    val attrs = (0 until r.getAttributeCount)
      .map(i => r.getAttributeLocalName(i) -> r.getAttributeValue(i))
      .toMap
    val flat = new StringBuilder
    val kids = ListBuffer.empty[Node]
    var open = true
    while open do
      r.next() match
        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA | XMLStreamConstants.SPACE =>
          flat.append(r.getText)
        case XMLStreamConstants.START_ELEMENT =>
          val k = readElement(r, depth + 1)
          kids += k
          flat.append(k.text)
        case XMLStreamConstants.END_ELEMENT  => open = false
        case XMLStreamConstants.END_DOCUMENT => throw new XMLStreamException("unexpected end")
        case _                               => ()
    Node(name, attrs, flat.toString, kids.toList)

  private def interpret(root: Node): Either[ParseFailure, ParsedRecord] =
    root.name match
      case t if WorkTypes(t) => work(root)
      case "www"             => person(root)
      case other             =>
        Left(
          ParseFailure(
            RejectRecordReasons.Unsupported,
            s"record type '${other.take(40)}' is not a publication",
            root.attrs.get("key"),
          )
        )

  private def person(root: Node): Either[ParseFailure, ParsedRecord] =
    root.attrs.get("key").map(_.trim).filter(_.nonEmpty) match
      case None      => Left(ParseFailure(RejectRecordReasons.Malformed, "www record without key"))
      case Some(key) =>
        val warnings = ListBuffer.empty[String]
        val names  = root.kids.filter(_.name == "author").map(a => clean(a.text)).filter(_.nonEmpty)
        val urls   = root.kids.filter(_.name == "url").map(u => clean(u.text)).filter(_.nonEmpty)
        val orcids =
          (root.kids.filter(_.name == "author").flatMap(_.attrs.get("orcid")) ++
            urls.filter(_.toLowerCase.contains("orcid.org/"))).flatMap { raw =>
            Orcid.parse(raw) match
              case Right(o) => Some(o)
              case Left(_)  => warnings += "invalid_orcid"; None
          }
        Right(
          ParsedPersonRecord(
            DblpPerson(key, names.distinct, orcids.distinct.sorted, urls.distinct),
            warnings.toList,
          )
        )

  private def work(root: Node): Either[ParseFailure, ParsedRecord] =
    val fail = (reason: RejectReason, msg: String) =>
      Left(ParseFailure(reason, msg, root.attrs.get("key").map(_.trim).filter(_.nonEmpty)))
    root.attrs.get("key").map(_.trim).filter(_.nonEmpty) match
      case None => Left(ParseFailure(RejectRecordReasons.Malformed, "record without key attribute"))
      case Some(key) =>
        Ids.workIdFromSource("dblp", key) match
          case Left(e)       => fail(RejectRecordReasons.Malformed, s"key is not usable: ${e.code}")
          case Right(workId) =>
            def first(n: String): Option[String] =
              root.kids.find(_.name == n).map(k => clean(k.text)).filter(_.nonEmpty)
            def all(n: String): List[String] =
              root.kids.filter(_.name == n).map(k => clean(k.text)).filter(_.nonEmpty)
            val warnings = ListBuffer.empty[String]
            val title    = first("title")
            val yearText = first("year")
            val year     = yearText.flatMap(_.toIntOption)
            val rawAuth  = root.kids.filter(_.name == "author").flatMap { a =>
              val nm = clean(a.text)
              if nm.isEmpty then { warnings += "blank_author"; None }
              else
                val orc = a.attrs
                  .get("orcid")
                  .flatMap(o =>
                    Orcid.parse(o) match
                      case Right(v) => Some(v)
                      case Left(_)  => warnings += "invalid_orcid"; None
                  )
                Some(DblpAuthor(nm, orc))
            }
            // A repeated author string is one mention: homonym suffixes ("Wei Wang 0001") differ,
            // so they stay distinct. The first occurrence fixes the position; the ORCID kept is the
            // first one found on ANY occurrence of that string.
            val authors =
              rawAuth.groupBy(_.name).view.mapValues(_.flatMap(_.orcid).headOption).toMap
            val seen     = scala.collection.mutable.Set.empty[String]
            val distinct =
              rawAuth.filter(a => seen.add(a.name)).map(a => DblpAuthor(a.name, authors(a.name)))
            val ees = all("ee")
            val ids = ees.flatMap(e =>
              ExternalLinks.fromEe(e) match
                case Right(x) => x.toList
                case Left(_)  => warnings += "invalid_identifier"; Nil
            )
            val doi   = ids.collectFirst { case ExternalId(IdentifierType.Doi, v) => v }
            val arxiv = ids.collectFirst { case ExternalId(IdentifierType.Arxiv, v) => v }
            if title.isEmpty then fail(RejectRecordReasons.NoTitle, "title is missing or blank")
            else if yearText.isEmpty then fail(RejectRecordReasons.NoYear, "year is missing")
            else if year.isEmpty || PublicationYear.validate(year.get).isLeft then
              fail(RejectRecordReasons.BadYear, "year is not a four-digit year")
            else if distinct.isEmpty then
              fail(RejectRecordReasons.NoAuthors, "record has no authors")
            else
              Right(
                ParsedWork(
                  DblpWork(
                    key,
                    workId,
                    root.name,
                    title.get,
                    year.get,
                    distinct,
                    rawAuth.size - distinct.size,
                    all("editor"),
                    first("journal"),
                    first("booktitle"),
                    first("volume"),
                    first("number"),
                    first("pages"),
                    first("crossref"),
                    ees,
                    all("url"),
                    doi,
                    arxiv,
                  ),
                  warnings.toList,
                )
              )

object DblpParser:
  val WorkTypes: Set[String] =
    Set(
      "article",
      "inproceedings",
      "proceedings",
      "book",
      "incollection",
      "phdthesis",
      "mastersthesis",
    )

  /** Top-level element names the record reader synchronizes on (publications, www, data). */
  val RecordTags: Set[String] = WorkTypes + "www" + "data"

  private val MaxDepth    = 16
  private val Declaration = """<!(?!--|\[CDATA\[)""".r

  private final case class Node(
      name: String,
      attrs: Map[String, String],
      text: String,
      kids: List[Node],
  )

  /** Collapses whitespace runs (including newlines inside multiline titles) to one space. */
  def clean(s: String): String = s.replaceAll("\\s+", " ").trim

  private def shortMessage(e: XMLStreamException): String =
    val m = Option(e.getMessage).getOrElse("").replaceAll("\\s+", " ")
    m.take(160)

  /** Convenience for one-off use (tests): builds a parser and parses. */
  def parseRecord(
      bytes: Array[Byte],
      charset: Charset,
      entities: DblpEntities,
  ): Either[ParseFailure, ParsedRecord] = new DblpParser(entities, charset).parse(bytes)

/** Stable names for the reject reasons the parser uses. */
object RejectRecordReasons:
  val Malformed   = RejectReason.MalformedRecord
  val Encoding    = RejectReason.EncodingError
  val NoTitle     = RejectReason.MissingTitle
  val NoYear      = RejectReason.MissingYear
  val BadYear     = RejectReason.InvalidYear
  val NoAuthors   = RejectReason.MissingAuthors
  val Unsupported = RejectReason.UnsupportedType
