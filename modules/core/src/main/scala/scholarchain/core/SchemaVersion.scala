package scholarchain.core

/** Schema version carried by every record. Bump `Current` and extend `Supported` together with a
  * migration note; removing a value from `Supported` is a breaking change.
  */
object SchemaVersion:
  val Current: Int        = 1
  val Supported: Set[Int] = Set(1)

  def check(version: Int): Either[ValidationError, Int] =
    if Supported.contains(version) then Right(version)
    else Left(ValidationError.UnsupportedSchemaVersion(version, Supported))
