package scholarchain.storage

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  DeleteMessageRequest,
  MessageSystemAttributeName,
  ReceiveMessageRequest,
  SendMessageRequest,
}

/** Limits used by the queue adapters. `MaxMessageBytes` (1,048,576 bytes) and `MaxReceive` (valid
  * values 1 to 10 for `MaxNumberOfMessages`) are from
  * https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html
  * and
  * https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html
  * (both accessed 2026-11-21). The upper bounds of `WaitTimeSeconds` and `VisibilityTimeout` were
  * NOT VERIFIED on the fetched pages, so only `>= 0` is checked here and the service enforces the
  * rest.
  */
object WorkQueueLimits:
  val MaxMessageBytes: Int = 1048576
  val MaxReceive: Int      = 10

  def checkSend(body: String): Either[StorageError, Unit] =
    val n = body.getBytes(StandardCharsets.UTF_8).length
    if n >= 1 && n <= MaxMessageBytes then Right(())
    else Left(StorageError.InvalidRequest("message body must be 1 byte to 1 MiB"))

  def checkReceive(max: Int, visibility: Int, wait: Int): Either[StorageError, Unit] =
    if max < 1 || max > MaxReceive then
      Left(StorageError.InvalidRequest("maxMessages must be 1 to 10"))
    else if visibility < 0 || wait < 0 then
      Left(StorageError.InvalidRequest("visibility and wait seconds must be >= 0"))
    else Right(())

/** In-memory work queue. Semantics modelled: at-least-once delivery, visibility timeout (on an
  * injectable clock, in seconds), receive counts, deduplication by `deduplicationId` (a resend with
  * the same ID returns the first message ID and enqueues nothing), and delete by receipt handle. A
  * stale receipt handle (the message was received again meanwhile) is not accepted.
  */
final class InMemoryWorkQueue(nowSeconds: () => Long = () => System.currentTimeMillis() / 1000)
    extends WorkQueue:
  private final case class Msg(
      id: String,
      body: String,
      var visibleAt: Long,
      var receipt: String,
      var count: Int,
  )

  private val queues =
    scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.ListBuffer[Msg]]
  private val dedup = scala.collection.mutable.HashMap.empty[(String, String), String]
  private var seq   = 0L

  private def next(prefix: String): String =
    seq += 1
    s"$prefix-$seq"

  def send(queue: String, body: String, deduplicationId: Option[String]): StorageResult[String] =
    synchronized {
      for
        _ <-
          if queue.nonEmpty then Right(())
          else Left(StorageError.InvalidRequest("queue name is empty"))
        _ <- WorkQueueLimits.checkSend(body)
      yield deduplicationId.flatMap(d => dedup.get((queue, d))) match
        case Some(id) => id
        case None     =>
          val id = next("msg")
          queues.getOrElseUpdate(queue, scala.collection.mutable.ListBuffer.empty) += Msg(
            id,
            body,
            0L,
            "",
            0,
          )
          deduplicationId.foreach(d => dedup.update((queue, d), id))
          id
    }

  def receive(
      queue: String,
      maxMessages: Int,
      visibilityTimeoutSeconds: Int,
      waitSeconds: Int,
  ): StorageResult[List[QueueMessage]] =
    synchronized {
      WorkQueueLimits.checkReceive(maxMessages, visibilityTimeoutSeconds, waitSeconds).map { _ =>
        val now = nowSeconds()
        queues
          .get(queue)
          .toList
          .flatMap(_.toList)
          .filter(_.visibleAt <= now)
          .take(maxMessages)
          .map { m =>
            m.count += 1
            m.receipt = next("rh")
            m.visibleAt = now + visibilityTimeoutSeconds
            QueueMessage(m.id, m.receipt, m.body, m.count)
          }
      }
    }

  def delete(queue: String, receiptHandle: String): StorageResult[Unit] = synchronized {
    queues.get(queue) match
      case Some(buf) =>
        buf.indexWhere(_.receipt == receiptHandle) match
          case -1 => Left(StorageError.NotFound("message"))
          case i  =>
            buf.remove(i)
            Right(())
      case None => Left(StorageError.NotFound("queue"))
  }

  /** Number of messages still in the queue (visible or not). */
  def depth(queue: String): Int = synchronized(queues.get(queue).map(_.size).getOrElse(0))

/** SQS adapter. `queueUrls` maps a logical queue name to its URL and comes from configuration; no
  * URL is built or guessed here. A queue whose URL ends in `.fifo` gets a message group and the
  * deduplication ID. Nothing here builds a client.
  */
final class SqsWorkQueue(client: SqsClient, queueUrls: Map[String, String]) extends WorkQueue:
  private def url(queue: String): Either[StorageError, String] =
    queueUrls.get(queue).toRight(StorageError.NotFound("queue"))

  def send(queue: String, body: String, deduplicationId: Option[String]): StorageResult[String] =
    for
      u <- url(queue)
      _ <- WorkQueueLimits.checkSend(body)
      r <- AwsErrors.guard("sqs send") {
        val b = SendMessageRequest.builder().queueUrl(u).messageBody(body)
        if u.endsWith(".fifo") then
          b.messageGroupId("scholarchain")
          b.messageDeduplicationId(deduplicationId.getOrElse(Cells.sha256(body)))
        client.sendMessage(b.build())
      }
    yield r.messageId()

  def receive(
      queue: String,
      maxMessages: Int,
      visibilityTimeoutSeconds: Int,
      waitSeconds: Int,
  ): StorageResult[List[QueueMessage]] =
    for
      u <- url(queue)
      _ <- WorkQueueLimits.checkReceive(maxMessages, visibilityTimeoutSeconds, waitSeconds)
      r <- AwsErrors.guard("sqs receive")(
        client.receiveMessage(
          ReceiveMessageRequest
            .builder()
            .queueUrl(u)
            .maxNumberOfMessages(maxMessages)
            .visibilityTimeout(visibilityTimeoutSeconds)
            .waitTimeSeconds(waitSeconds)
            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            .build()
        )
      )
    yield r.messages().asScala.toList.map { m =>
      val count = Option(m.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
        .flatMap(_.toIntOption)
        .getOrElse(1)
      QueueMessage(m.messageId(), m.receiptHandle(), m.body(), count)
    }

  def delete(queue: String, receiptHandle: String): StorageResult[Unit] =
    for
      u <- url(queue)
      _ <- AwsErrors.guard("sqs delete")(
        client.deleteMessage(
          DeleteMessageRequest.builder().queueUrl(u).receiptHandle(receiptHandle).build()
        )
      )
    yield ()
