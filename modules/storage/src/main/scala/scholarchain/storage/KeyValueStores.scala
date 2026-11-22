package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  ConditionalCheckFailedException,
  GetItemRequest,
  PutItemRequest,
  QueryRequest,
  ReturnValuesOnConditionCheckFailure,
}

/** Physical key layout shared by the fake and the DynamoDB adapter: every table has the string
  * partition key attribute `pk`, optionally the string sort key attribute `sk`, and the TTL
  * attribute `expiresAt` (number, epoch seconds) which is written only when the item is disposable.
  * These three names are reserved: callers cannot use them as attribute names.
  *
  * Limits (https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html,
  * accessed 2026-11-21): item size 400 KB, partition key value at most 2048 bytes, sort key value
  * at most 1024 bytes.
  */
object DynamoKeys:
  val Pk: String  = "pk"
  val Sk: String  = "sk"
  val Ttl: String = "expiresAt"

  val Reserved: Set[String] = Set(Pk, Sk, Ttl)

  val MaxItemBytes: Int         = 409600
  val MaxPartitionKeyBytes: Int = 2048
  val MaxSortKeyBytes: Int      = 1024

  private def bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  /** Approximate item size (names and values in UTF-8, 8 bytes for the TTL number). */
  def itemSize(item: KvItem): Int =
    bytes(Pk) + bytes(item.key.partition) +
      item.key.sort.map(s => bytes(Sk) + bytes(s)).getOrElse(0) +
      item.attributes.iterator.map((k, v) => bytes(k) + bytes(v)).sum +
      item.expiresAtEpochSeconds.map(_ => bytes(Ttl) + 8).getOrElse(0)

  /** Text of the problem when the item is unusable, else `None`. Never echoes values. */
  def problem(item: KvItem): Option[String] =
    if item.key.partition.isEmpty then Some("partition key is empty")
    else if bytes(item.key.partition) > MaxPartitionKeyBytes then Some("partition key too long")
    else if item.key.sort.exists(_.isEmpty) then Some("sort key is empty")
    else if item.key.sort.exists(bytes(_) > MaxSortKeyBytes) then Some("sort key too long")
    else if item.attributes.keys.exists(Reserved.contains) then
      Some("attribute name is reserved (pk, sk, expiresAt)")
    else if item.attributes.keys.exists(_.isEmpty) then Some("attribute name is empty")
    else if itemSize(item) > MaxItemBytes then Some("item exceeds the 400 KB item size limit")
    else None

  /** DynamoDB orders strings by their UTF-8 bytes (unsigned). */
  val StringOrder: Ordering[String] = (a: String, b: String) =>
    java.util.Arrays.compareUnsigned(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8),
    )

  def matches(c: SortCondition, sk: String): Boolean =
    val o = StringOrder
    c match
      case SortCondition.Equals(v)         => sk == v
      case SortCondition.BeginsWith(p)     => sk.startsWith(p)
      case SortCondition.Between(lo, hi)   => o.gteq(sk, lo) && o.lteq(sk, hi)
      case SortCondition.LessThan(v)       => o.lt(sk, v)
      case SortCondition.LessOrEqual(v)    => o.lteq(sk, v)
      case SortCondition.GreaterThan(v)    => o.gt(sk, v)
      case SortCondition.GreaterOrEqual(v) => o.gteq(sk, v)

  def encodeToken(sortKey: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(sortKey.getBytes(StandardCharsets.UTF_8))

  def decodeToken(token: String): Either[StorageError, String] =
    scala.util
      .Try(new String(Base64.getUrlDecoder.decode(token), StandardCharsets.UTF_8))
      .toEither
      .left
      .map(_ => StorageError.InvalidRequest("bad continuation token"))

/** Whether a table has a sort key. */
final case class KvTableSchema(hasSortKey: Boolean)

/** In-memory `KeyValueStore` with DynamoDB's conditional-write semantics:
  *   - `NotExists`: writes only when no item has the key; otherwise `ConditionFailed(existing)`.
  *   - `AttributeEquals(a, v)`: writes only when an item exists and its attribute `a` equals `v`;
  *     otherwise `ConditionFailed(existing or None)`.
  *   - `Always`: replaces the whole item.
  * Sort keys order by UTF-8 bytes. The fake never deletes anything, TTL included: DynamoDB deletes
  * expired items asynchronously (typically within days, see
  * https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html), so readers must
  * treat `expiresAtEpochSeconds` in the past as absent, and the repositories do.
  */
final class InMemoryKeyValueStore(schemas: Map[String, KvTableSchema]) extends KeyValueStore:
  private type Tbl = scala.collection.mutable.HashMap[(String, String), KvItem]

  private val tables: Map[String, Tbl] = schemas.keys.map(_ -> new Tbl).toMap

  private def slot(i: KvKey): (String, String) = (i.partition, i.sort.getOrElse(""))

  private def check(table: String, key: KvKey): Either[StorageError, Tbl] =
    for
      s <- schemas.get(table).toRight(StorageError.NotFound("table"))
      _ <-
        if s.hasSortKey == key.sort.isDefined then Right(())
        else Left(StorageError.InvalidRequest("key does not match the table key schema"))
    yield tables(table)

  def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
    synchronized {
      for
        m <- check(table, item.key)
        _ <- DynamoKeys.problem(item).toLeft(()).left.map(StorageError.InvalidRequest(_))
      yield
        val existing = m.get(slot(item.key))
        val allowed  = condition match
          case PutCondition.Always                => true
          case PutCondition.NotExists             => existing.isEmpty
          case PutCondition.AttributeEquals(n, v) =>
            existing.exists(_.attributes.get(n).contains(v))
        if allowed then
          m.update(slot(item.key), item)
          PutOutcome.Written
        else PutOutcome.ConditionFailed(existing)
    }

  def get(table: String, key: KvKey): StorageResult[Option[KvItem]] =
    synchronized(check(table, key).map(_.get(slot(key))))

  def query(
      table: String,
      partition: String,
      sort: Option[SortCondition],
      limit: Int,
      ascending: Boolean,
      continuationToken: Option[String],
  ): StorageResult[KvPage] =
    synchronized {
      for
        s <- schemas.get(table).toRight(StorageError.NotFound("table"))
        _ <-
          if limit >= 1 then Right(()) else Left(StorageError.InvalidRequest("limit must be >= 1"))
        _ <-
          if sort.isEmpty || s.hasSortKey then Right(())
          else Left(StorageError.InvalidRequest("table has no sort key"))
        after <- continuationToken match
          case None    => Right(None)
          case Some(t) => DynamoKeys.decodeToken(t).map(Some(_))
      yield
        val ord   = if ascending then DynamoKeys.StringOrder else DynamoKeys.StringOrder.reverse
        val items = tables(table).values
          .filter(i => i.key.partition == partition)
          .filter(i => sort.forall(c => i.key.sort.exists(DynamoKeys.matches(c, _))))
          .toList
          .sortBy(_.key.sort.getOrElse(""))(using ord)
          .filter(i => after.forall(a => ord.gt(i.key.sort.getOrElse(""), a)))
        val page = items.take(limit)
        val next =
          if items.size > limit then page.lastOption.flatMap(_.key.sort).map(DynamoKeys.encodeToken)
          else None
        KvPage(page, next)
    }

  /** All items of a table (test and diagnostics helper). */
  def snapshot(table: String): Map[(String, String), KvItem] =
    synchronized(tables.get(table).map(_.toMap).getOrElse(Map.empty))

/** DynamoDB adapter. Conditions are condition expressions with placeholders for every name and
  * value; nothing from a caller is concatenated into an expression.
  */
final class DynamoKeyValueStore(client: DynamoDbClient) extends KeyValueStore:
  import DynamoKeys.*

  private def toAttr(item: KvItem): java.util.Map[String, AttributeValue] =
    val m = scala.collection.mutable.LinkedHashMap.empty[String, AttributeValue]
    m.update(Pk, AttributeValue.fromS(item.key.partition))
    item.key.sort.foreach(s => m.update(Sk, AttributeValue.fromS(s)))
    item.expiresAtEpochSeconds.foreach(t => m.update(Ttl, AttributeValue.fromN(t.toString)))
    item.attributes.foreach((n, v) => m.update(n, AttributeValue.fromS(v)))
    m.asJava

  private def fromAttr(m: java.util.Map[String, AttributeValue]): Option[KvItem] =
    val mm = m.asScala
    mm.get(Pk).flatMap(a => Option(a.s())).map { pk =>
      val sk    = mm.get(Sk).flatMap(a => Option(a.s()))
      val ttl   = mm.get(Ttl).flatMap(a => Option(a.n())).flatMap(_.toLongOption)
      val attrs = mm.iterator.collect {
        case (n, a) if !Reserved.contains(n) && a.s() != null => n -> a.s()
      }.toMap
      KvItem(KvKey(pk, sk), attrs, ttl)
    }

  def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
    problem(item) match
      case Some(p) => Left(StorageError.InvalidRequest(p))
      case None    =>
        val base = PutItemRequest.builder().tableName(table).item(toAttr(item))
        val req  = condition match
          case PutCondition.Always    => base
          case PutCondition.NotExists =>
            base
              .conditionExpression("attribute_not_exists(#pk)")
              .expressionAttributeNames(Map("#pk" -> Pk).asJava)
              .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
          case PutCondition.AttributeEquals(n, v) =>
            base
              .conditionExpression("#a = :v")
              .expressionAttributeNames(Map("#a" -> n).asJava)
              .expressionAttributeValues(Map(":v" -> AttributeValue.fromS(v)).asJava)
              .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
        try
          client.putItem(req.build())
          Right(PutOutcome.Written)
        catch
          case c: ConditionalCheckFailedException =>
            Right(PutOutcome.ConditionFailed(Option(c.item()).filter(!_.isEmpty).flatMap(fromAttr)))
          case scala.util.control.NonFatal(e) => Left(AwsErrors.translate(e, "dynamodb put"))

  def get(table: String, key: KvKey): StorageResult[Option[KvItem]] =
    val keyMap = scala.collection.mutable.LinkedHashMap(Pk -> AttributeValue.fromS(key.partition))
    key.sort.foreach(s => keyMap.update(Sk, AttributeValue.fromS(s)))
    AwsErrors
      .guard("dynamodb get")(
        client.getItem(
          GetItemRequest.builder().tableName(table).key(keyMap.asJava).consistentRead(true).build()
        )
      )
      .map(r => if r.hasItem && !r.item().isEmpty then fromAttr(r.item()) else None)

  def query(
      table: String,
      partition: String,
      sort: Option[SortCondition],
      limit: Int,
      ascending: Boolean,
      continuationToken: Option[String],
  ): StorageResult[KvPage] =
    if limit < 1 then Left(StorageError.InvalidRequest("limit must be >= 1"))
    else
      val values = scala.collection.mutable.LinkedHashMap(":pk" -> AttributeValue.fromS(partition))
      val names  = scala.collection.mutable.LinkedHashMap("#pk" -> Pk)
      def s(n: String, v: String): Unit = values.update(n, AttributeValue.fromS(v))
      val cond                          = sort match
        case None    => "#pk = :pk"
        case Some(c) =>
          names.update("#sk", Sk)
          c match
            case SortCondition.Equals(v) =>
              s(":s1", v)
              "#pk = :pk AND #sk = :s1"
            case SortCondition.BeginsWith(p) =>
              s(":s1", p)
              "#pk = :pk AND begins_with(#sk, :s1)"
            case SortCondition.Between(lo, hi) =>
              s(":s1", lo)
              s(":s2", hi)
              "#pk = :pk AND #sk BETWEEN :s1 AND :s2"
            case SortCondition.LessThan(v) =>
              s(":s1", v)
              "#pk = :pk AND #sk < :s1"
            case SortCondition.LessOrEqual(v) =>
              s(":s1", v)
              "#pk = :pk AND #sk <= :s1"
            case SortCondition.GreaterThan(v) =>
              s(":s1", v)
              "#pk = :pk AND #sk > :s1"
            case SortCondition.GreaterOrEqual(v) =>
              s(":s1", v)
              "#pk = :pk AND #sk >= :s1"
      val start = continuationToken match
        case None    => Right(None)
        case Some(t) => decodeToken(t).map(Some(_))
      start.flatMap { after =>
        val b = QueryRequest
          .builder()
          .tableName(table)
          .keyConditionExpression(cond)
          .expressionAttributeNames(names.asJava)
          .expressionAttributeValues(values.asJava)
          .scanIndexForward(ascending)
          .consistentRead(true)
          .limit(limit)
        after.foreach(a =>
          b.exclusiveStartKey(
            Map(Pk -> AttributeValue.fromS(partition), Sk -> AttributeValue.fromS(a)).asJava
          )
        )
        AwsErrors.guard("dynamodb query")(client.query(b.build())).map { r =>
          val items = r.items().asScala.toList.flatMap(fromAttr)
          val next  =
            if r.hasLastEvaluatedKey && !r.lastEvaluatedKey().isEmpty then
              Option(r.lastEvaluatedKey().get(Sk)).flatMap(a => Option(a.s())).map(encodeToken)
            else None
          KvPage(items, next)
        }
      }
