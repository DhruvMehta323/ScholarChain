package scholarchain.core.rest

import scala.util.control.NonFatal

import scholarchain.core.*

/** JSON entry points for the REST view records (which carry no `schemaVersion`). `write` validates
  * first, so an invalid view is never serialized. `read` is for clients and tests: it decodes, then
  * validates, and is total. It ignores unknown fields (the server-side request parser
  * `QueryRequest.parse` does not).
  *
  * For persisted payloads (`JobRecord`, `IdempotencyRecord`) use `Wire.write` and `Wire.read` like
  * every other stored record.
  */
object RestJson:
  def write[A](
      a: A
  )(using c: Contract[A], w: WirePickle.Writer[A]): Either[ValidationError, String] =
    c.validate(a).map(v => WirePickle.write(v))

  def read[A](
      json: String
  )(using c: Contract[A], r: WirePickle.Reader[A]): Either[ValidationError, A] =
    try c.validate(WirePickle.read[A](json))
    catch case NonFatal(e) => Left(ValidationError.Malformed(e.getClass.getSimpleName))
