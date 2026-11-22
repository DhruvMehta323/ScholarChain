package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.*

/** Outcome of an idempotent write. `Unchanged` = an identical item (same content hash) was already
  * stored (a reload is a no-op). `Conflict` = the key exists with DIFFERENT content: reported,
  * never overwritten.
  */
enum WriteResult:
  case Written
  case Unchanged
  case Conflict(reason: String)

/** Item layout shared by the five core tables: the record is stored as its validated wire JSON in
  * `payload`, its SHA-256 in `payloadHash`, and (for records with a lifecycle) `status`. The
  * conditional-write rules live here, once:
  *   - immutable item: `NotExists`; if present with the same `payloadHash` -> `Unchanged`; if
  *     present with another hash -> `Conflict`.
  *   - lifecycle item (run ledger, query session): same, except that a present item whose `status`
  *     is in `allowedFrom` is replaced with the condition `status = <seen status>`, so two writers
  *     cannot both transition it.
  *   - disposable item (a TTL item): a present item whose expiry has passed (`expiresAt <= now`) is
  *     dead for readers but is only deleted by DynamoDB's asynchronous TTL sweep, so it is
  *     replaceable under the condition `expiry = <the expiry text seen>` (a string copy of the TTL,
  *     `Expiry`, written with every TTL item, because the port's `AttributeEquals` compares
  *     strings). Two writers cannot both replace it, and an item that was refreshed in between (a
  *     different expiry) is a `Conflict`. Never an unconditional overwrite.
  */
private[storage] object ItemWrites:
  val Payload     = "payload"
  val PayloadHash = "payloadHash"
  val Status      = "status"
  val Expiry      = "expiry"

  def item(
      key: KvKey,
      json: String,
      status: Option[String] = None,
      ttl: Option[Long] = None,
      extra: Map[String, String] = Map.empty,
  ): KvItem =
    KvItem(
      key,
      extra ++ Map(Payload -> json, PayloadHash -> Cells.sha256(json)) ++
        status.map(Status -> _) ++ ttl.map(t => Expiry -> t.toString),
      ttl,
    )

  def hashOf(i: KvItem): Option[String] = i.attributes.get(PayloadHash)

  private def current(
      kv: KeyValueStore,
      table: String,
      key: KvKey,
      seen: Option[KvItem],
  ): StorageResult[Option[KvItem]] =
    seen match
      case Some(i) => Right(Some(i))
      case None    => kv.get(table, key)

  /** Writes `item` under the rules above. */
  def write(
      kv: KeyValueStore,
      table: String,
      item: KvItem,
      allowedFrom: Set[String] = Set.empty,
      nowEpochSeconds: Option[Long] = None,
  ): StorageResult[WriteResult] =
    kv.put(table, item, PutCondition.NotExists).flatMap {
      case PutOutcome.Written               => Right(WriteResult.Written)
      case PutOutcome.ConditionFailed(seen) =>
        current(kv, table, item.key, seen).flatMap {
          case None =>
            Right(WriteResult.Conflict("write refused and the existing item could not be read"))
          case Some(existing) if expired(existing, nowEpochSeconds) =>
            existing.attributes.get(Expiry) match
              case Some(seenExpiry) =>
                kv.put(table, item, PutCondition.AttributeEquals(Expiry, seenExpiry)).map {
                  case PutOutcome.Written => WriteResult.Written
                  case _                  =>
                    WriteResult.Conflict("the expired item changed concurrently, retry the write")
                }
              case None =>
                Right(WriteResult.Conflict("an expired item without an expiry attribute exists"))
          case Some(existing) if hashOf(existing) == hashOf(item) => Right(WriteResult.Unchanged)
          case Some(existing)                                     =>
            existing.attributes.get(Status) match
              case Some(s) if allowedFrom.contains(s) && !item.attributes.get(Status).contains(s) =>
                kv.put(table, item, PutCondition.AttributeEquals(Status, s)).map {
                  case PutOutcome.Written => WriteResult.Written
                  case _                  =>
                    WriteResult.Conflict("the item changed concurrently, retry the transition")
                }
              case _ =>
                Right(
                  WriteResult.Conflict(
                    "an item with a different payload already exists under this key"
                  )
                )
        }
    }

  /** True when the stored item carries a TTL that has passed at `now`. */
  def expired(i: KvItem, now: Option[Long]): Boolean =
    now.exists(n => i.expiresAtEpochSeconds.exists(_ <= n))

  def notExpired(i: KvItem, nowEpochSeconds: Long): Boolean =
    i.expiresAtEpochSeconds.forall(_ > nowEpochSeconds)

  def decode[A](i: KvItem)(using Contract[A], WirePickle.Reader[A]): StorageResult[A] =
    i.attributes
      .get(Payload)
      .toRight(StorageError.Failed("stored item has no payload"))
      .flatMap(json =>
        if Some(Cells.sha256(json)) != hashOf(i) then
          Left(StorageError.IntegrityMismatch("stored payload does not match its hash"))
        else
          Wire
            .read[A](json)
            .left
            .map(e => StorageError.Failed(s"stored payload invalid (${e.code})"))
      )

  def encode[A](a: A)(using Contract[A], WirePickle.Writer[A]): StorageResult[String] =
    Wire.write(a).left.map(e => StorageError.InvalidRequest(e.message))

  def token(part: String): String = part

/** RunLedger table. Keys:
  *   - run item: `pk = run#<runId>`, `sk = META`
  *   - stage index item: `pk = stage#<stage>`, `sk = <startedAt>#<runId>` (immutable pointer)
  * Access patterns: A1 get a run by ID (GetItem); A2 the newest runs of a stage (Query on the stage
  * partition, descending). Lifecycle: `started` -> `succeeded` | `failed`.
  */
final class RunLedgerRepository(kv: KeyValueStore, table: String):
  private def runKey(runId: String) = KvKey(s"run#$runId", Some("META"))
  private def stageKey(stage: String, started: String, runId: String) =
    KvKey(s"stage#$stage", Some(s"$started#$runId"))

  def record(entry: RunLedgerEntry): StorageResult[WriteResult] =
    for
      json <- ItemWrites.encode(entry)
      main <- ItemWrites.write(
        kv,
        table,
        ItemWrites.item(runKey(entry.runId), json, Some(entry.status.wire)),
        allowedFrom = Set(RunStatus.Started.wire),
      )
      res <- main match
        case WriteResult.Conflict(_) => Right(main)
        case _                       =>
          val idx = ItemWrites.item(
            stageKey(entry.stage, entry.startedAt, entry.runId),
            ujson.write(ujson.Obj("runId" -> entry.runId, "startedAt" -> entry.startedAt)),
          )
          ItemWrites.write(kv, table, idx).map {
            case WriteResult.Conflict(r) => WriteResult.Conflict(s"stage index: $r")
            case _                       => main
          }
    yield res

  def get(runId: String): StorageResult[Option[RunLedgerEntry]] =
    kv.get(table, runKey(runId)).flatMap {
      case None    => Right(None)
      case Some(i) => ItemWrites.decode[RunLedgerEntry](i).map(Some(_))
    }

  /** Run IDs of a stage, newest first (by `startedAt`), at most `limit`. */
  def latestForStage(stage: String, limit: Int): StorageResult[List[String]] =
    kv.query(table, s"stage#$stage", None, limit, ascending = false)
      .map(_.items.flatMap { i =>
        i.key.sort.flatMap(_.split("#", 2).lift(1))
      })

/** ArtifactRegistry table. Keys: `pk = <runId>`, `sk = <artifactType>#<sha256(artifactUri)>` (the
  * URI itself may exceed the 1024-byte sort key limit). Access patterns: A1 all artifacts of a run
  * (Query pk); A2 artifacts of one type for a run (`begins_with(sk, "<type>#")`); A3 one artifact
  * by run, type and URI (GetItem). Items are immutable: a registered artifact is never replaced.
  */
final class ArtifactRegistryRepository(kv: KeyValueStore, table: String):
  private def key(runId: String, tpe: String, uri: String) =
    KvKey(runId, Some(s"$tpe#${Cells.sha256(uri)}"))

  def register(entry: ArtifactRegistryEntry): StorageResult[WriteResult] =
    ItemWrites
      .encode(entry)
      .flatMap(json =>
        ItemWrites.write(
          kv,
          table,
          ItemWrites.item(key(entry.runId, entry.artifactType, entry.artifactUri), json),
        )
      )

  def get(runId: String, tpe: String, uri: String): StorageResult[Option[ArtifactRegistryEntry]] =
    kv.get(table, key(runId, tpe, uri)).flatMap {
      case None    => Right(None)
      case Some(i) => ItemWrites.decode[ArtifactRegistryEntry](i).map(Some(_))
    }

  def forRun(
      runId: String,
      artifactType: Option[String],
      limit: Int,
  ): StorageResult[List[ArtifactRegistryEntry]] =
    kv.query(table, runId, artifactType.map(t => SortCondition.BeginsWith(s"$t#")), limit)
      .flatMap(p => sequence(p.items.map(ItemWrites.decode[ArtifactRegistryEntry])))

  private def sequence[A](xs: List[StorageResult[A]]): StorageResult[List[A]] =
    xs.foldRight[StorageResult[List[A]]](Right(Nil))((e, acc) => for x <- e; r <- acc yield x :: r)

private[storage] def sequenceResults[A](xs: List[StorageResult[A]]): StorageResult[List[A]] =
  xs.foldRight[StorageResult[List[A]]](Right(Nil))((e, acc) => for x <- e; r <- acc yield x :: r)

/** EntityLinkCache table. Keys: `pk = <mentionId>`, `sk = <policyVersion>#<cacheKey>`. Access
  * patterns: A1 the cached decision(s) for a mention under one policy version (Query with
  * `begins_with(sk, "<policy>#")`); A2 all policy versions for a mention (Query pk). TTL: only when
  * the entry carries `expiresAtEpochSeconds` (a decision that is valid for its policy version does
  * not expire). Reads skip expired entries (TTL deletion is asynchronous).
  */
final class EntityLinkCacheRepository(
    kv: KeyValueStore,
    table: String,
    nowEpochSeconds: () => Long,
):
  def put(entry: EntityLinkCacheEntry): StorageResult[WriteResult] =
    ItemWrites
      .encode(entry)
      .flatMap(json =>
        ItemWrites.write(
          kv,
          table,
          ItemWrites.item(
            KvKey(entry.mentionId, Some(s"${entry.policyVersion}#${entry.cacheKey}")),
            json,
            ttl = entry.expiresAtEpochSeconds,
          ),
          nowEpochSeconds = Some(nowEpochSeconds()),
        )
      )

  def forMention(
      mentionId: String,
      policyVersion: Option[String],
      limit: Int,
  ): StorageResult[List[EntityLinkCacheEntry]] =
    kv.query(table, mentionId, policyVersion.map(p => SortCondition.BeginsWith(s"$p#")), limit)
      .flatMap(p =>
        sequenceResults(
          p.items
            .filter(ItemWrites.notExpired(_, nowEpochSeconds()))
            .map(ItemWrites.decode[EntityLinkCacheEntry])
        )
      )

/** QuerySession table. Key: `pk = <sessionId>` (no sort key). Access pattern: get one session by
  * ID. Lifecycle: `received` -> `answered` | `rejected` | `failed`. TTL: always set (disposable, at
  * most 30 days by the record contract). Reads treat an expired session as absent.
  */
final class QuerySessionRepository(
    kv: KeyValueStore,
    table: String,
    nowEpochSeconds: () => Long,
):
  def record(rec: QuerySessionRecord): StorageResult[WriteResult] =
    ItemWrites
      .encode(rec)
      .flatMap(json =>
        ItemWrites.write(
          kv,
          table,
          ItemWrites.item(
            KvKey(rec.sessionId),
            json,
            status = Some(rec.status.wire),
            ttl = Some(rec.expiresAtEpochSeconds),
          ),
          allowedFrom = Set(QuerySessionStatus.Received.wire),
          nowEpochSeconds = Some(nowEpochSeconds()),
        )
      )

  def get(sessionId: String): StorageResult[Option[QuerySessionRecord]] =
    kv.get(table, KvKey(sessionId)).flatMap {
      case Some(i) if ItemWrites.notExpired(i, nowEpochSeconds()) =>
        ItemWrites.decode[QuerySessionRecord](i).map(Some(_))
      case _ => Right(None)
    }

/** EvidenceBundle table. Keys: `pk = <queryId>`, `sk = <bundleHash>` (SHA-256 of the bundle JSON).
  * Access patterns: A1 all stored bundles of a query (Query pk); A2 one bundle by query and hash
  * (GetItem). Items are content-addressed and immutable, so a reload is a no-op. No TTL: bundles
  * back audited answers and are not disposable. A bundle whose JSON exceeds `MaxInlineBytes` is
  * stored in the object store (`objectBucket`, `<objectPrefix><bundleHash>.json`) and the item
  * holds only its URI and hash.
  */
final class EvidenceBundleRepository(
    kv: KeyValueStore,
    table: String,
    objects: ObjectStore,
    objectBucket: String,
    objectPrefix: String,
):
  val MaxInlineBytes: Int = 300000

  private def location(hash: String) = ObjectLocation(objectBucket, s"$objectPrefix$hash.json")

  def put(bundle: EvidenceBundle): StorageResult[WriteResult] =
    ItemWrites.encode(bundle).flatMap { json =>
      val bytes = json.getBytes(StandardCharsets.UTF_8)
      val hash  = Cells.sha256(bytes)
      val key   = KvKey(bundle.queryId, Some(hash))
      if bytes.length <= MaxInlineBytes then ItemWrites.write(kv, table, ItemWrites.item(key, json))
      else
        for
          _ <- ObjectStores.putIdempotent(objects, location(hash), bytes, Some("application/json"))
          res <- ItemWrites.write(
            kv,
            table,
            KvItem(
              key,
              Map(ItemWrites.PayloadHash -> hash, "payloadUri" -> location(hash).uri),
            ),
          )
        yield res
    }

  def get(queryId: String, bundleHash: String): StorageResult[Option[EvidenceBundle]] =
    kv.get(table, KvKey(queryId, Some(bundleHash))).flatMap {
      case None    => Right(None)
      case Some(i) => load(i).map(Some(_))
    }

  def forQuery(queryId: String, limit: Int): StorageResult[List[EvidenceBundle]] =
    kv.query(table, queryId, None, limit).flatMap(p => sequenceResults(p.items.map(load)))

  private def load(i: KvItem): StorageResult[EvidenceBundle] =
    i.attributes.get("payloadUri") match
      case None      => ItemWrites.decode[EvidenceBundle](i)
      case Some(uri) =>
        for
          loc  <- ObjectLocation.parse(uri).left.map(e => StorageError.Failed(e.code))
          data <- objects.get(loc)
          _    <-
            if Some(data.meta.contentHash) == ItemWrites.hashOf(i) then Right(())
            else Left(StorageError.IntegrityMismatch("spilled bundle does not match its hash"))
          b <- Wire
            .read[EvidenceBundle](new String(data.bytes, StandardCharsets.UTF_8))
            .left
            .map(e => StorageError.Failed(s"stored bundle invalid (${e.code})"))
        yield b
