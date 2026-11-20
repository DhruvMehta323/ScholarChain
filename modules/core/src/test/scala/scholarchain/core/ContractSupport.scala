package scholarchain.core

/** Shared assertion helpers for the contract suites. */
abstract class ContractSupport extends munit.FunSuite:

  /** The result must be a Left whose error is matched by `pf`. */
  def expectErr[A](e: Either[ValidationError, A])(pf: PartialFunction[ValidationError, Unit])(using
      munit.Location
  ): Unit =
    e match
      case Right(v)  => fail(s"expected a validation error but got Right($v)")
      case Left(err) =>
        if pf.isDefinedAt(err) then pf(err) else fail(s"unexpected validation error: $err")

  def right[A](e: Either[ValidationError, A])(using munit.Location): A = e match
    case Right(a)  => a
    case Left(err) => fail(s"expected Right but got Left($err)")

  def validate[A](a: A)(using c: Contract[A]): Either[ValidationError, A] = c.validate(a)
