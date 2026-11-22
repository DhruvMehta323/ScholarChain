package scholarchain.storage

import scholarchain.core.{StorageError, StorageResult, StorageRetry}

/** Injectable wait, so tests never sleep. */
trait Sleeper:
  def sleep(millis: Long): Unit

object Sleeper:
  /** Real wait. Only the live adapters wire this in. */
  val System: Sleeper = (millis: Long) => Thread.sleep(millis)

/** Bounded exponential backoff: attempt n (1-based, after a failure) waits
  * `min(maxDelayMillis, baseDelayMillis * 2^(n-1))`. No jitter, so the schedule is reproducible.
  * The caps come from `StorageRetry` (itself capped by core: at most 10 attempts, 60 s).
  */
object Retry:
  def delayMillis(cfg: StorageRetry, failedAttempt: Int): Long =
    val shift = math.min(math.max(failedAttempt - 1, 0), 30)
    val raw   = cfg.baseDelayMillis.toDouble * (1L << shift).toDouble
    math.min(cfg.maxDelayMillis.toDouble, raw).toLong

  /** Runs `op` up to `cfg.maxAttempts` times. Only `retryable` errors are retried; the last error
    * is returned when the attempts are used up. `onRetry(attempt, error, delay)` is called before
    * each wait (the caller records it; it must not log secrets).
    */
  def run[A](
      cfg: StorageRetry,
      sleeper: Sleeper,
      onRetry: (Int, StorageError, Long) => Unit = (_, _, _) => (),
  )(op: => StorageResult[A]): StorageResult[A] =
    var attempt = 1
    var result  = op
    while attempt < cfg.maxAttempts && result.left.exists(_.retryable) do
      val d = delayMillis(cfg, attempt)
      onRetry(attempt, result.left.toOption.get, d)
      sleeper.sleep(d)
      attempt += 1
      result = op
    result
