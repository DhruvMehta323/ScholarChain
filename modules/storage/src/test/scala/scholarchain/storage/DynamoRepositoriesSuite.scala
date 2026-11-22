package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.*

import StorageFx.*

/** DynamoDB key layout, access patterns, conditional writes and idempotent reload, against the
  * in-memory fake (which models the documented conditional-write semantics).
  */
class DynamoRepositoriesSuite extends munit.FunSuite:

  private val Ledger   = "t-ledger"
  private val Registry = "t-registry"
  private val Links    = "t-links"
  private val Sessions = "t-sessions"
  private val Evidence = "t-evidence"

  private val schemas = Map(
    Ledger   -> KvTableSchema(true),
    Registry -> KvTableSchema(true),
    Links    -> KvTableSchema(true),
    Sessions -> KvTableSchema(false),
    Evidence -> KvTableSchema(true),
  )

  private var now: Long = createdEpoch + 10L

  private final class Env(
      val kv: InMemoryKeyValueStore,
      val objects: FileObjectStore,
      val dir: java.nio.file.Path,
  ):
    val ledger   = RunLedgerRepository(kv, Ledger)
    val registry = ArtifactRegistryRepository(kv, Registry)
    val links    = EntityLinkCacheRepository(kv, Links, () => now)
    val sessions = QuerySessionRepository(kv, Sessions, () => now)
    val evidence = EvidenceBundleRepository(kv, Evidence, objects, bucket, "rag/evidence/")

    def state: Map[String, Map[(String, String), KvItem]] =
      schemas.keys.map(t => t -> kv.snapshot(t)).toMap

  private val env = FunFixture[Env](
    setup = { _ =>
      now = createdEpoch + 10L
      val dir = newTempDir("sc-dyn")
      Env(InMemoryKeyValueStore(schemas), FileObjectStore(dir), dir)
    },
    teardown = e => deleteTree(e.dir),
  )

  private def okv[A](r: StorageResult[A]): A = r.fold(e => fail(e.toString), identity)

  // ---- documented access patterns, one test each ----------------------------------------------

  env.test("[RunLedger A1] a run is fetched by its ID with one GetItem") { e =>
    val r = ledger()
    assertEquals(okv(e.ledger.record(r)), WriteResult.Written)
    assertEquals(okv(e.ledger.get(r.runId)), Some(r))
    assertEquals(okv(e.ledger.get("run:" + "0" * 64)), None)
    assert(e.kv.snapshot(Ledger).contains((s"run#${r.runId}", "META")))
  }

  env.test(
    "[RunLedger A2] the newest runs of a stage come back newest first, bounded by the limit"
  ) { e =>
    val runs = List(
      ledger(attempt = 1, startedAt = "2026-11-21T10:00:00Z"),
      ledger(attempt = 2, startedAt = "2026-11-21T12:00:00Z"),
      ledger(attempt = 3, startedAt = "2026-11-21T11:00:00Z"),
    )
    runs.foreach(r => okv(e.ledger.record(r)))
    okv(
      e.ledger.record(
        ledger(stage = "other-stage", attempt = 1, startedAt = "2026-11-21T13:00:00Z")
      )
    )
    assertEquals(
      okv(e.ledger.latestForStage("spark-graph", 10)),
      List(runs(1).runId, runs(2).runId, runs(0).runId),
    )
    assertEquals(okv(e.ledger.latestForStage("spark-graph", 2)), List(runs(1).runId, runs(2).runId))
    assertEquals(okv(e.ledger.latestForStage("nothing", 5)), Nil)
  }

  env.test("[ArtifactRegistry A1] all artifacts of a run are one Query on the run partition") { e =>
    val a1    = artifact(uri = s"s3://$bucket/graph/a.parquet")
    val a2    = artifact(tpe = "neptune-csv", uri = s"s3://$bucket/graph/b.csv")
    val other = artifact(run = runIdFor(attempt = 9), uri = s"s3://$bucket/graph/c.parquet")
    List(a1, a2, other).foreach(a => okv(e.registry.register(a)))
    assertEquals(okv(e.registry.forRun(run1, None, 10)).toSet, Set(a1, a2))
  }

  env.test("[ArtifactRegistry A2] artifacts of one type use begins_with(sk, \"<type>#\")") { e =>
    val a1 = artifact(uri = s"s3://$bucket/graph/a.parquet")
    val a2 = artifact(tpe = "parquet-table-extra", uri = s"s3://$bucket/graph/b.parquet")
    val a3 = artifact(tpe = "neptune-csv", uri = s"s3://$bucket/graph/c.csv")
    List(a1, a2, a3).foreach(a => okv(e.registry.register(a)))
    // "parquet-table#" must not match "parquet-table-extra#..."
    assertEquals(okv(e.registry.forRun(run1, Some("parquet-table"), 10)), List(a1))
    assertEquals(okv(e.registry.forRun(run1, Some("neptune-csv"), 10)), List(a3))
  }

  env.test(
    "[ArtifactRegistry A3] one artifact by run, type and URI; the long URI is hashed in the sort key"
  ) { e =>
    val long = artifact(uri = s"s3://$bucket/" + "k" * 1000)
    okv(e.registry.register(long))
    assertEquals(okv(e.registry.get(long.runId, long.artifactType, long.artifactUri)), Some(long))
    assertEquals(okv(e.registry.get(long.runId, long.artifactType, s"s3://$bucket/other")), None)
    val sk = e.kv.snapshot(Registry).keys.head._2
    assert(
      sk.getBytes(StandardCharsets.UTF_8).length <= DynamoKeys.MaxSortKeyBytes,
      sk.length.toString,
    )
    assertEquals(sk, s"parquet-table#${Cells.sha256(long.artifactUri)}")
  }

  env.test("[EntityLinkCache A1] decisions of a mention under one policy version") { e =>
    val m  = mention()
    val p1 = link(m, "er-policy.v1", "k1")
    val p2 = link(m, "er-policy.v2", "k2")
    List(p1, p2, link(mention(1), "er-policy.v1", "k3")).foreach(l => okv(e.links.put(l)))
    assertEquals(okv(e.links.forMention(m, Some("er-policy.v1"), 10)), List(p1))
    assertEquals(okv(e.links.forMention(m, Some("er-policy.v9"), 10)), Nil)
  }

  env.test(
    "[EntityLinkCache A2] all policy versions of a mention are one Query on the mention partition"
  ) { e =>
    val m  = mention()
    val p1 = link(m, "er-policy.v1", "k1")
    val p2 = link(m, "er-policy.v2", "k2")
    List(p2, p1).foreach(l => okv(e.links.put(l)))
    assertEquals(okv(e.links.forMention(m, None, 10)), List(p1, p2)) // sort key order
  }

  env.test(
    "[QuerySession A1] a session is fetched by ID; received moves to answered exactly once"
  ) { e =>
    val rec = session()
    assertEquals(okv(e.sessions.record(rec)), WriteResult.Written)
    assertEquals(okv(e.sessions.get(rec.sessionId)), Some(rec))
    val answered = session(status = QuerySessionStatus.Answered)
    assertEquals(okv(e.sessions.record(answered)), WriteResult.Written)
    assertEquals(okv(e.sessions.get(rec.sessionId)), Some(answered))
    // answered is final: going back to received is a conflict and changes nothing
    assert(okv(e.sessions.record(rec)).isInstanceOf[WriteResult.Conflict])
    assertEquals(okv(e.sessions.get(rec.sessionId)), Some(answered))
    assertEquals(okv(e.sessions.get("session-unknown")), None)
  }

  env.test(
    "[EvidenceBundle A1/A2] bundles of a query by Query pk, one bundle by query and content hash"
  ) { e =>
    val b1 = bundle(texts = List("alpha"))
    val b2 = bundle(texts = List("beta"))
    okv(e.evidence.put(b1))
    okv(e.evidence.put(b2))
    okv(e.evidence.put(bundle(queryId = "query:other")))
    assertEquals(okv(e.evidence.forQuery("query:q-0001", 10)).toSet, Set(b1, b2))
    val hash = Cells.sha256(okv(Wire.write(b1).left.map(x => StorageError.Failed(x.message))))
    assertEquals(okv(e.evidence.get("query:q-0001", hash)), Some(b1))
    assertEquals(okv(e.evidence.get("query:q-0001", "0" * 64)), None)
  }

  // ---- conditional writes ---------------------------------------------------------------------

  private def item(pk: String, sk: Option[String], attrs: (String, String)*) =
    KvItem(KvKey(pk, sk), attrs.toMap)

  env.test("NotExists: a second put fails with ConditionFailed carrying the existing item") { e =>
    val a = item("p", Some("s"), "v" -> "1")
    val b = item("p", Some("s"), "v" -> "2")
    assertEquals(okv(e.kv.put(Ledger, a, PutCondition.NotExists)), PutOutcome.Written)
    assertEquals(
      okv(e.kv.put(Ledger, b, PutCondition.NotExists)),
      PutOutcome.ConditionFailed(Some(a)),
    )
    assertEquals(okv(e.kv.get(Ledger, a.key)), Some(a)) // not overwritten
  }

  env.test(
    "AttributeEquals: writes only when the stored attribute matches, never on a missing item"
  ) { e =>
    val a = item("p", Some("s"), "status" -> "started")
    val b = item("p", Some("s"), "status" -> "succeeded")
    assertEquals(
      okv(e.kv.put(Ledger, a, PutCondition.AttributeEquals("status", "started"))),
      PutOutcome.ConditionFailed(None),
    )
    okv(e.kv.put(Ledger, a, PutCondition.NotExists))
    assertEquals(
      okv(e.kv.put(Ledger, b, PutCondition.AttributeEquals("status", "other"))),
      PutOutcome.ConditionFailed(Some(a)),
    )
    assertEquals(
      okv(e.kv.put(Ledger, b, PutCondition.AttributeEquals("status", "started"))),
      PutOutcome.Written,
    )
    assertEquals(okv(e.kv.get(Ledger, a.key)), Some(b))
  }

  env.test("Always replaces the whole item") { e =>
    okv(e.kv.put(Ledger, item("p", Some("s"), "a" -> "1", "b" -> "2"), PutCondition.Always))
    okv(e.kv.put(Ledger, item("p", Some("s"), "c" -> "3"), PutCondition.Always))
    assertEquals(
      okv(e.kv.get(Ledger, KvKey("p", Some("s")))).map(_.attributes),
      Some(Map("c" -> "3")),
    )
  }

  env.test(
    "store refuses a wrong key schema, an unknown table, reserved names and oversized keys"
  ) { e =>
    assert(
      e.kv
        .put(Sessions, item("p", Some("s")), PutCondition.Always)
        .left
        .exists(_.isInstanceOf[StorageError.InvalidRequest])
    )
    assert(e.kv.put(Ledger, item("p", None), PutCondition.Always).isLeft)
    assertEquals(
      e.kv.put("nope", item("p", None), PutCondition.Always),
      Left(StorageError.NotFound("table")),
    )
    List("pk", "sk", "expiresAt").foreach(n =>
      assert(e.kv.put(Ledger, item("p", Some("s"), n -> "x"), PutCondition.Always).isLeft, n)
    )
    assert(e.kv.put(Ledger, item("p" * 2049, Some("s")), PutCondition.Always).isLeft)
    assert(e.kv.put(Ledger, item("p", Some("s" * 1025)), PutCondition.Always).isLeft)
    assert(e.kv.put(Ledger, item("", Some("s")), PutCondition.Always).isLeft)
    assert(
      e.kv.put(Ledger, item("p", Some("s"), "big" -> ("x" * 410000)), PutCondition.Always).isLeft
    )
    assertEquals(e.kv.snapshot(Ledger), Map.empty)
  }

  env.test("query: sort conditions, descending order, UTF-8 byte order and pagination") { e =>
    List("a#1", "a#2", "b#1", "c#1", "é").foreach(s =>
      okv(e.kv.put(Ledger, item("p", Some(s)), PutCondition.Always))
    )
    def sks(c: Option[SortCondition], asc: Boolean = true) =
      okv(e.kv.query(Ledger, "p", c, 50, asc)).items.flatMap(_.key.sort)
    assertEquals(sks(Some(SortCondition.BeginsWith("a#"))), List("a#1", "a#2"))
    assertEquals(sks(Some(SortCondition.Between("a#2", "b#1"))), List("a#2", "b#1"))
    assertEquals(sks(Some(SortCondition.GreaterThan("b#1"))), List("c#1", "é"))
    assertEquals(sks(Some(SortCondition.LessThan("a#2"))), List("a#1"))
    assertEquals(sks(Some(SortCondition.Equals("c#1"))), List("c#1"))
    assertEquals(sks(None).last, "é") // non-ASCII sorts after ASCII by UTF-8 bytes
    assertEquals(sks(None, asc = false).head, "é")
    val p1 = okv(e.kv.query(Ledger, "p", None, 2))
    assertEquals(p1.items.flatMap(_.key.sort), List("a#1", "a#2"))
    val p2 = okv(e.kv.query(Ledger, "p", None, 2, continuationToken = p1.nextToken))
    assertEquals(p2.items.flatMap(_.key.sort), List("b#1", "c#1"))
    val p3 = okv(e.kv.query(Ledger, "p", None, 2, continuationToken = p2.nextToken))
    assertEquals((p3.items.flatMap(_.key.sort), p3.nextToken), (List("é"), None))
    assert(e.kv.query(Ledger, "p", None, 0).isLeft)
    assert(e.kv.query(Ledger, "p", None, 5, continuationToken = Some("!!!")).isLeft)
    assert(e.kv.query(Sessions, "p", Some(SortCondition.Equals("x")), 5).isLeft)
  }

  // ---- idempotency ----------------------------------------------------------------------------

  private def loadAll(e: Env): List[WriteResult] =
    val done = ledger(status = RunStatus.Succeeded)
    List(
      okv(e.ledger.record(done)),
      okv(e.registry.register(artifact())),
      okv(e.links.put(link())),
      okv(e.sessions.record(session(status = QuerySessionStatus.Answered))),
      okv(e.evidence.put(bundle())),
    )

  env.test(
    "idempotent reload: loading twice gives identical store state and the second load reports Unchanged"
  ) { e =>
    assert(loadAll(e).forall(_ == WriteResult.Written))
    val after1 = e.state
    assert(after1.values.forall(_.nonEmpty))
    val second = loadAll(e)
    assertEquals(second, List.fill(5)(WriteResult.Unchanged))
    assertEquals(e.state, after1)
  }

  env.test(
    "a reloaded run that is already finished stays Unchanged even after the stage index exists"
  ) { e =>
    val r = ledger(status = RunStatus.Succeeded)
    assertEquals(okv(e.ledger.record(r)), WriteResult.Written)
    val snap = e.state
    assertEquals(okv(e.ledger.record(r)), WriteResult.Unchanged)
    assertEquals(e.state, snap)
    assertEquals(snap(Ledger).size, 2) // META item + stage index item
  }

  env.test("a different payload under the same key is a Conflict and is never written") { e =>
    val a = artifact()
    assertEquals(okv(e.registry.register(a)), WriteResult.Written)
    val changed = a.copy(sizeBytes = a.sizeBytes + 1)
    val res     = okv(e.registry.register(changed))
    assert(res.isInstanceOf[WriteResult.Conflict], res.toString)
    assertEquals(okv(e.registry.get(a.runId, a.artifactType, a.artifactUri)), Some(a))
    val l = link()
    okv(e.links.put(l))
    assert(okv(e.links.put(l.copy(confidence = 0.1))).isInstanceOf[WriteResult.Conflict])
    assertEquals(okv(e.links.forMention(l.mentionId, None, 5)), List(l))
    okv(e.evidence.put(bundle()))
  }

  env.test(
    "a run that finished is not rewritten back to started, and a failed run cannot be re-succeeded"
  ) { e =>
    val started = ledger()
    val failed  = ledger(status = RunStatus.Failed)
    okv(e.ledger.record(started))
    assertEquals(okv(e.ledger.record(failed)), WriteResult.Written)
    assertEquals(okv(e.ledger.get(started.runId)), Some(failed))
    assert(okv(e.ledger.record(started)).isInstanceOf[WriteResult.Conflict])
    assert(
      okv(e.ledger.record(ledger(status = RunStatus.Succeeded))).isInstanceOf[WriteResult.Conflict]
    )
    assertEquals(okv(e.ledger.get(started.runId)), Some(failed))
  }

  env.test("the conflict message does not echo item content") { e =>
    val a = artifact()
    okv(e.registry.register(a))
    okv(e.registry.register(a.copy(sizeBytes = 5))) match
      case WriteResult.Conflict(r) =>
        assert(!r.contains(a.artifactUri) && !r.contains(hexA), r)
      case other => fail(other.toString)
  }

  // ---- TTL ------------------------------------------------------------------------------------

  env.test(
    "TTL is set only on disposable tables: QuerySession always, EntityLinkCache only when the entry expires"
  ) { e =>
    okv(e.ledger.record(ledger(status = RunStatus.Succeeded)))
    okv(e.registry.register(artifact()))
    okv(e.evidence.put(bundle()))
    okv(e.sessions.record(session()))
    okv(e.links.put(link(key = "kNoExpiry")))
    okv(e.links.put(link(key = "kExpiry", expires = Some(createdEpoch + 100))))
    def ttls(t: String) = e.kv.snapshot(t).values.toList.map(_.expiresAtEpochSeconds)
    assert(ttls(Ledger).forall(_.isEmpty), "RunLedger holds the audit trail: no TTL")
    assert(ttls(Registry).forall(_.isEmpty), "ArtifactRegistry: no TTL")
    assert(ttls(Evidence).forall(_.isEmpty), "EvidenceBundle backs audited answers: no TTL")
    assert(
      ttls(Sessions).nonEmpty && ttls(Sessions).forall(_.isDefined),
      "QuerySession is disposable: TTL",
    )
    assertEquals(ttls(Links).flatten, List(createdEpoch + 100))
    assertEquals(ttls(Links).count(_.isEmpty), 1)
  }

  env.test(
    "expired sessions and expired cache entries read as absent even before DynamoDB deletes them"
  ) { e =>
    val s = session()
    okv(e.sessions.record(s))
    val l = link(expires = Some(createdEpoch + 50))
    okv(e.links.put(l))
    assertEquals(okv(e.sessions.get(s.sessionId)), Some(s))
    assertEquals(okv(e.links.forMention(l.mentionId, None, 5)), List(l))
    now = createdEpoch + 5000
    assertEquals(okv(e.sessions.get(s.sessionId)), None)
    assertEquals(okv(e.links.forMention(l.mentionId, None, 5)), Nil)
    assertEquals(
      e.kv.snapshot(Sessions).size,
      1,
    ) // the fake never deletes (TTL deletion is asynchronous)
  }

  // ---- M2: expired TTL items are replaceable, under a condition on the stored expiry ----------

  /** Delegating store that records every put condition and can run a hook right before the first
    * `AttributeEquals` put (to model a concurrent writer).
    */
  private final class SpyKv(inner: InMemoryKeyValueStore, beforeConditional: () => Unit = () => ())
      extends KeyValueStore:
    val conditions     = scala.collection.mutable.ListBuffer.empty[PutCondition]
    private var hooked = false
    def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
      condition match
        case _: PutCondition.AttributeEquals if !hooked =>
          hooked = true
          beforeConditional()
        case _ => ()
      conditions += condition
      inner.put(table, item, condition)
    def get(table: String, key: KvKey): StorageResult[Option[KvItem]] = inner.get(table, key)
    def query(
        table: String,
        partition: String,
        sort: Option[SortCondition],
        limit: Int,
        ascending: Boolean,
        continuationToken: Option[String],
    ): StorageResult[KvPage] =
      inner.query(table, partition, sort, limit, ascending, continuationToken)

  private val Later: Long = 5000L

  private def newSession(ttl: Long, queryId: String = "query:q-0002") =
    session(ttlSeconds = ttl).copy(queryId = queryId)

  env.test(
    "[M2] an expired EntityLinkCache item is replaceable: Written, and the new payload is read back"
  ) { e =>
    val old = link(expires = Some(createdEpoch + 50))
    assertEquals(okv(e.links.put(old)), WriteResult.Written)
    now = createdEpoch + Later
    val fresh = old.copy(confidence = 0.1, expiresAtEpochSeconds = Some(createdEpoch + 90000))
    assertEquals(okv(e.links.put(fresh)), WriteResult.Written)
    assertEquals(okv(e.links.forMention(old.mentionId, None, 5)), List(fresh))
    assertEquals(e.kv.snapshot(Links).size, 1)
  }

  env.test(
    "[M2] an expired QuerySession is replaceable: Written, and get returns the new session"
  ) { e =>
    val old = newSession(100L, "query:q-0001")
    assertEquals(okv(e.sessions.record(old)), WriteResult.Written)
    now = createdEpoch + Later
    assertEquals(okv(e.sessions.get(old.sessionId)), None) // expired reads as absent
    val fresh = newSession(7200L)
    assertEquals(okv(e.sessions.record(fresh)), WriteResult.Written)
    assertEquals(okv(e.sessions.get(old.sessionId)), Some(fresh))
  }

  env.test("[M2] an UNEXPIRED item with a different payload is still a Conflict and unchanged") {
    e =>
      val old = link(expires = Some(createdEpoch + 50))
      okv(e.links.put(old))
      val res = okv(e.links.put(old.copy(confidence = 0.2)))
      assert(res.isInstanceOf[WriteResult.Conflict], res.toString)
      assertEquals(okv(e.links.forMention(old.mentionId, None, 5)), List(old))
      val s = newSession(3600L, "query:q-0001")
      okv(e.sessions.record(s))
      val res2 = okv(e.sessions.record(newSession(3600L)))
      assert(res2.isInstanceOf[WriteResult.Conflict], res2.toString)
      assertEquals(okv(e.sessions.get(s.sessionId)), Some(s))
  }

  env.test("[M2] the TTL item stores its expiry as a string attribute `expiry`") { e =>
    okv(e.links.put(link(expires = Some(createdEpoch + 50))))
    okv(e.sessions.record(session()))
    okv(e.links.put(link(key = "kNone")))
    val withTtl = e.kv.snapshot(Links).values.filter(_.expiresAtEpochSeconds.isDefined).toList
    assertEquals(withTtl.map(_.attributes.get("expiry")), List(Some((createdEpoch + 50).toString)))
    assertEquals(
      e.kv.snapshot(Sessions).values.head.attributes.get("expiry"),
      Some((createdEpoch + 3600L).toString),
    )
    // an item without a TTL carries no expiry attribute
    val noTtl = e.kv.snapshot(Links).values.filter(_.expiresAtEpochSeconds.isEmpty).toList
    assert(noTtl.forall(!_.attributes.contains("expiry")), noTtl.toString)
  }

  env.test(
    "[M2] the replacement condition is AttributeEquals(expiry, <stored expiry>), never Always"
  ) { e =>
    val spy  = SpyKv(e.kv)
    val repo = EntityLinkCacheRepository(spy, Links, () => now)
    val old  = link(expires = Some(createdEpoch + 50))
    okv(repo.put(old))
    now = createdEpoch + Later
    spy.conditions.clear()
    val fresh = old.copy(confidence = 0.1, expiresAtEpochSeconds = Some(createdEpoch + 90000))
    assertEquals(okv(repo.put(fresh)), WriteResult.Written)
    assertEquals(
      spy.conditions.toList,
      List(
        PutCondition.NotExists,
        PutCondition.AttributeEquals("expiry", (createdEpoch + 50).toString),
      ),
    )
    assert(!spy.conditions.contains(PutCondition.Always), "no unconditional overwrite")
  }

  env.test(
    "[M2] a concurrent change of the expiry between read and write is a Conflict (EntityLinkCache)"
  ) { e =>
    val old = link(expires = Some(createdEpoch + 50))
    okv(EntityLinkCacheRepository(e.kv, Links, () => now).put(old))
    now = createdEpoch + Later
    val key = KvKey(old.mentionId, Some(s"${old.policyVersion}#${old.cacheKey}"))
    // another writer refreshes the item (a new expiry) right before our conditional put
    val spy = SpyKv(
      e.kv,
      () =>
        val cur = okv(e.kv.get(Links, key)).get
        okv(
          e.kv.put(
            Links,
            cur.copy(
              attributes = cur.attributes.updated("expiry", (createdEpoch + 99999).toString),
              expiresAtEpochSeconds = Some(createdEpoch + 99999),
            ),
            PutCondition.Always,
          )
        )
        (),
    )
    val repo = EntityLinkCacheRepository(spy, Links, () => now)
    val res  =
      okv(repo.put(old.copy(confidence = 0.1, expiresAtEpochSeconds = Some(createdEpoch + 90000))))
    assert(res.isInstanceOf[WriteResult.Conflict], res.toString)
    assertEquals(
      okv(e.kv.get(Links, key)).get.attributes.get("expiry"),
      Some((createdEpoch + 99999).toString),
      "the concurrent writer's item must survive",
    )
  }

  env.test("[M2] a concurrent change of the expiry is a Conflict (QuerySession)") { e =>
    val old = newSession(100L, "query:q-0001")
    okv(QuerySessionRepository(e.kv, Sessions, () => now).record(old))
    now = createdEpoch + Later
    val key = KvKey(old.sessionId)
    val spy = SpyKv(
      e.kv,
      () =>
        val cur = okv(e.kv.get(Sessions, key)).get
        okv(
          e.kv.put(
            Sessions,
            cur.copy(attributes = cur.attributes.updated("expiry", "7777777777")),
            PutCondition.Always,
          )
        )
        (),
    )
    val res = okv(QuerySessionRepository(spy, Sessions, () => now).record(newSession(7200L)))
    assert(res.isInstanceOf[WriteResult.Conflict], res.toString)
    assertEquals(okv(e.kv.get(Sessions, key)).get.attributes.get("expiry"), Some("7777777777"))
  }

  env.test(
    "[M2] an expired item that carries no expiry attribute (written before the repair) stays a Conflict"
  ) { e =>
    val l   = link(expires = Some(createdEpoch + 50))
    val key = KvKey(l.mentionId, Some(s"${l.policyVersion}#${l.cacheKey}"))
    okv(
      e.kv.put(
        Links,
        KvItem(key, Map("payload" -> "{}", "payloadHash" -> "0" * 64), Some(createdEpoch + 50)),
        PutCondition.Always,
      )
    )
    now = createdEpoch + Later
    val res = okv(e.links.put(l))
    assert(res.isInstanceOf[WriteResult.Conflict], res.toString)
    assertEquals(okv(e.kv.get(Links, key)).get.attributes("payload"), "{}")
  }

  // ---- evidence spill and integrity -----------------------------------------------------------

  env.test(
    "a large bundle is spilled to the object store; the item holds only a URI and the hash"
  ) { e =>
    val big = bundle(texts = List("x" * 310000))
    assertEquals(okv(e.evidence.put(big)), WriteResult.Written)
    val item = e.kv.snapshot(Evidence).values.head
    assert(item.attributes.contains("payloadUri"))
    assert(!item.attributes.contains("payload"))
    assert(DynamoKeys.itemSize(item) < 1000)
    assertEquals(okv(e.evidence.forQuery("query:q-0001", 5)), List(big))
    assertEquals(okv(e.evidence.put(big)), WriteResult.Unchanged)
  }

  env.test("a tampered spilled object is detected as IntegrityMismatch, not returned") { e =>
    val big = bundle(texts = List("y" * 310000))
    okv(e.evidence.put(big))
    val uri = e.kv.snapshot(Evidence).values.head.attributes("payloadUri")
    val loc = ObjectLocation.parse(uri).fold(x => fail(x.message), identity)
    val p   = e.dir.resolve("data").resolve(loc.bucket).resolve(loc.objectKey)
    java.nio.file.Files.write(p, "{}".getBytes(StandardCharsets.UTF_8))
    val out = e.evidence.forQuery("query:q-0001", 5)
    assert(out.left.exists(_.isInstanceOf[StorageError.IntegrityMismatch]), out.toString)
  }

  env.test("a stored payload whose bytes were altered fails its hash check on read") { e =>
    val r = ledger(status = RunStatus.Succeeded)
    okv(e.ledger.record(r))
    val key = KvKey(s"run#${r.runId}", Some("META"))
    val it  = okv(e.kv.get(Ledger, key)).get
    okv(
      e.kv.put(
        Ledger,
        it.copy(attributes =
          it.attributes
            .updated("payload", it.attributes("payload").replace("spark-graph", "tampered"))
        ),
        PutCondition.Always,
      )
    )
    val out = e.ledger.get(r.runId)
    assert(out.left.exists(_.isInstanceOf[StorageError.IntegrityMismatch]), out.toString)
  }

  // ---- analytics item files -------------------------------------------------------------------

  private def specFor(name: String, pk: String, sk: List[String], rows: Int) =
    DynamoTableSpec(
      TableRef(name, s"scholarchain-graph/$name", "parquet", Nil, rows.toLong),
      pk,
      sk,
    )

  private val metricsTable = TableData(
    "researcher-metrics",
    Vector(
      Column("researcherId", "string"),
      Column("window", "string"),
      Column("coauthorCount", "int"),
      Column("provenance", "string"),
    ),
    Vector(
      Vector(Cell.S("r-1"), Cell.S("2000-2004"), Cell.I(2), Cell.S("""{"runId":"run:x"}""")),
      Vector(Cell.S("r-1"), Cell.S("2005-2009"), Cell.I(3), Cell.S("""{"runId":"run:x"}""")),
    ),
  )

  private def lines(f: AnalyticsItems.ItemFile): List[ujson.Value] =
    new String(f.bytes, StandardCharsets.UTF_8).linesIterator.map(ujson.read(_)).toList

  test(
    "analytics items: pk/sk follow the manifest and a researcher's windows share one partition"
  ) {
    val f = AnalyticsItems
      .build(specFor("researcher-metrics", "researcherId", List("window"), 2), metricsTable)
      .fold(e => fail(e), identity)
    val ls = lines(f)
    assertEquals(
      ls.map(l => (l("pk").str, l("sk").str)),
      List(("r-1", "2000-2004"), ("r-1", "2005-2009")),
    )
    assertEquals(f.path, "items/researcher-metrics.jsonl")
    assertEquals(ls.head("table").str, "researcher-metrics")
    assert(
      !ls.head("attributes").obj.contains("provenance"),
      "row provenance is replaced by a digest",
    )
    assertEquals(ls.head("attributes")("provenanceRunId").str, "run:x")
    assertEquals(ls.head("payloadHash").str, Cells.sha256(ujson.write(ls.head("attributes"))))
  }

  test("analytics items: output is byte-identical on a second build") {
    val spec = specFor("researcher-metrics", "researcherId", List("window"), 2)
    val a    = AnalyticsItems.build(spec, metricsTable).fold(e => fail(e), identity)
    val b    = AnalyticsItems.build(spec, metricsTable).fold(e => fail(e), identity)
    assertEquals(a.sha256, b.sha256)
  }

  test(
    "analytics items: composite sort keys are joined with '|' and separators inside values are escaped"
  ) {
    assertEquals(AnalyticsItems.encodeComponent("a|b%c"), "a%7Cb%25c")
    val t = TableData(
      "bridges",
      Vector(
        Column("researcherId", "string"),
        Column("window", "string"),
        Column("measure", "string"),
      ),
      Vector(Vector(Cell.S("r-1"), Cell.S("2000-2004"), Cell.S("a|b"))),
    )
    val f = AnalyticsItems
      .build(specFor("bridges", "researcherId", List("window", "measure"), 1), t)
      .fold(e => fail(e), identity)
    assertEquals(lines(f).head("sk").str, "2000-2004|a%7Cb")
  }

  test(
    "analytics items: duplicate keys, null key columns, missing columns and oversized keys are refused"
  ) {
    val spec = specFor("researcher-metrics", "researcherId", List("window"), 2)
    assert(
      AnalyticsItems
        .build(spec, metricsTable.copy(rows = metricsTable.rows :+ metricsTable.rows.head))
        .isLeft
    )
    assert(
      AnalyticsItems
        .build(spec, metricsTable.copy(rows = Vector(metricsTable.rows.head.updated(1, Cell.Null))))
        .isLeft
    )
    assert(AnalyticsItems.build(specFor("researcher-metrics", "nope", Nil, 2), metricsTable).isLeft)
    assert(
      AnalyticsItems
        .build(
          spec,
          metricsTable.copy(rows = Vector(metricsTable.rows.head.updated(0, Cell.S("k" * 3000)))),
        )
        .isLeft
    )
  }
