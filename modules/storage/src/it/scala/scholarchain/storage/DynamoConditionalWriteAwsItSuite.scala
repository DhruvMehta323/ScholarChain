package scholarchain.storage

import java.util.UUID

import scholarchain.core.*

/** Live: conditional writes against the QuerySession table (disposable data, TTL attribute set to
  * one hour). Run only with SCHOLARCHAIN_AWS_IT=1 in an approved environment.
  */
class DynamoConditionalWriteAwsItSuite extends munit.FunSuite:
  override def munitIgnore: Boolean = !AwsItSupport.enabled

  test("NotExists writes once, a second write reports the existing item, AttributeEquals guards") {
    val kv    = AwsItSupport.clients().keyValueStore
    val table = AwsItSupport.config.value.tables.querySession
    val key   = KvKey(s"it-probe-${UUID.randomUUID()}")
    val ttl   = Some(System.currentTimeMillis() / 1000 + 3600)
    val v1    = KvItem(key, Map("status" -> "received", "payloadHash" -> "h1"), ttl)

    assertEquals(kv.put(table, v1, PutCondition.NotExists), Right(PutOutcome.Written))
    kv.put(table, v1.copy(attributes = Map("status" -> "answered")), PutCondition.NotExists) match
      case Right(PutOutcome.ConditionFailed(Some(existing))) =>
        assertEquals(existing.attributes.get("status"), Some("received"))
      case other => fail(s"expected a failed condition with the existing item, got $other")
    val v2 = v1.copy(attributes = Map("status" -> "answered", "payloadHash" -> "h2"))
    assertEquals(
      kv.put(table, v2, PutCondition.AttributeEquals("status", "answered"))
        .map(_.getClass.getSimpleName),
      Right("ConditionFailed$"),
    )
    assertEquals(
      kv.put(table, v2, PutCondition.AttributeEquals("status", "received")),
      Right(PutOutcome.Written),
    )
    assertEquals(
      kv.get(table, key).map(_.flatMap(_.attributes.get("status"))),
      Right(Some("answered")),
    )
  }
