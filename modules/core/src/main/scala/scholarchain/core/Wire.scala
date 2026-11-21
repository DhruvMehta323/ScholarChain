package scholarchain.core

import scala.util.control.NonFatal

/** The single uPickle flavour for ScholarChain JSON. Wire rules (frozen):
  *   - `None` is never written (key omitted); on read, an absent key or `null` is `None`.
  *   - Enums are written as their `wire` string.
  *   - Every record always writes `schemaVersion`.
  */
object WirePickle extends upickle.AttributeTagged:
  override given OptionWriter[T: Writer]: Writer[Option[T]] =
    summon[Writer[T]].comap[Option[T]] {
      case None    => null.asInstanceOf[T]
      case Some(x) => x
    }

  override given OptionReader[T: Reader]: Reader[Option[T]] =
    new Reader.Delegate[Any, Option[T]](summon[Reader[T]].map(Some(_))):
      override def visitNull(index: Int): Option[T] = None

/** Validation hook for one record type. `validate` is the smart constructor gate. */
trait Contract[A]:
  def validate(a: A): Either[ValidationError, A]

object Wire:
  private[core] def enumRw[E](
      name: String,
      all: Seq[E],
      wire: E => String,
  ): WirePickle.ReadWriter[E] =
    val base = WirePickle
      .readwriter[String]
      .bimap[E](
        wire,
        s => all.find(e => wire(e) == s).getOrElse(throw upickle.core.Abort(s"unknown $name '$s'")),
      )
    // A JSON null is never a valid enum value: reject it instead of reading a null reference.
    val nullRejectingReader: WirePickle.Reader[E] =
      new WirePickle.Reader.Delegate[Any, E](base):
        override def visitNull(index: Int): E =
          throw upickle.core.Abort(s"null is not a valid $name")
    WirePickle.ReadWriter.join(nullRejectingReader, base)

  /** Validates, then serializes. An invalid record is never written. */
  def write[A](
      a: A
  )(using c: Contract[A], w: WirePickle.Writer[A]): Either[ValidationError, String] =
    c.validate(a).map(v => WirePickle.write(v))

  /** Checks the schema version first, decodes, then validates. Total: never throws. */
  def read[A](
      json: String
  )(using c: Contract[A], r: WirePickle.Reader[A]): Either[ValidationError, A] =
    try
      val tree = ujson.read(json)
      for
        obj <- tree.objOpt.toRight(
          ValidationError.Malformed("top-level JSON value must be an object")
        )
        raw <- obj.value.get("schemaVersion").toRight(ValidationError.MissingField("schemaVersion"))
        ver <- raw.numOpt
          .filter(d => d.isWhole && d.abs < Int.MaxValue.toDouble)
          .map(_.toInt)
          .toRight(ValidationError.Malformed("schemaVersion must be an integer"))
        _       <- SchemaVersion.check(ver)
        decoded <- decode[A](json)
        valid   <- c.validate(decoded)
      yield valid
    catch case NonFatal(e) => Left(ValidationError.Malformed(e.getClass.getSimpleName))

  private val MissingKeys = """missing keys in dictionary: ([^,\s]+)""".r

  private def causes(e: Throwable): List[Throwable] =
    LazyList.iterate(e)(_.getCause).takeWhile(_ != null).take(8).toList

  /** uPickle reports an absent mandatory key as an abort (possibly wrapped in a trace exception)
    * whose message names the key.
    */
  private def decode[A](json: String)(using r: WirePickle.Reader[A]): Either[ValidationError, A] =
    try Right(WirePickle.read[A](json))
    catch
      case NonFatal(e) =>
        val messages = causes(e).flatMap(c => Option(c.getMessage))
        messages.flatMap(m => MissingKeys.findFirstMatchIn(m).map(_.group(1))).headOption match
          case Some(field) => Left(ValidationError.MissingField(field))
          case None        =>
            Left(ValidationError.Malformed(causes(e).last.getClass.getSimpleName))
