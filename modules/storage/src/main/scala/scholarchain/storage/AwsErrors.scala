package scholarchain.storage

import scholarchain.core.StorageError

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkException

/** Translates AWS SDK failures into `StorageError`. The message holds only the exception class, the
  * HTTP status and the AWS error code, never `getMessage` (which may carry resource names, ARNs or
  * request IDs).
  */
object AwsErrors:
  private val ThrottleCodes = Set(
    "Throttling",
    "ThrottlingException",
    "ThrottledException",
    "SlowDown",
    "RequestLimitExceeded",
    "ProvisionedThroughputExceededException",
    "TooManyRequestsException",
    "TransactionConflictException",
    "RequestThrottled",
    "TooManyRequests",
  )

  def translate(e: Throwable, what: String): StorageError = e match
    case a: AwsServiceException =>
      val code =
        Option(a.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("unknown")
      val status = a.statusCode()
      val text   = s"$what: ${a.getClass.getSimpleName} status=$status code=$code"
      if ThrottleCodes.contains(code) || status == 429 then StorageError.Throttled(text)
      else if status == 404 then StorageError.NotFound(what)
      else if status >= 500 then StorageError.Unavailable(text)
      else if status == 400 && code.contains("Validation") then StorageError.InvalidRequest(text)
      else StorageError.Failed(text)
    case s: SdkException =>
      // connection, timeout and other client-side failures
      StorageError.Unavailable(s"$what: ${s.getClass.getSimpleName}")
    case other => StorageError.Failed(s"$what: ${other.getClass.getSimpleName}")

  /** Runs an SDK call, translating any non-fatal exception. */
  def guard[A](what: String)(op: => A): Either[StorageError, A] =
    try Right(op)
    catch case scala.util.control.NonFatal(e) => Left(translate(e, what))
