package scholarchain.storage

import scholarchain.core.*

import AnalyticsItems.AnalyticsItem
import StorageFx.*

/** Phase 07 repair 01 (M8): the analytics item repository over `KeyValueStore`, against the
  * in-memory fake: conditional idempotent writes, lossless typed JSON, the "metrics of one
  * researcher in one window" access pattern, integrity checks and the configured table names.
  */
class AnalyticsRepositorySuite extends munit.FunSuite:

  private def cfg(env: Map[String, String] = Map.empty): StorageModuleConfig =
    StorageConfigLoader.load(env, Map.empty).fold(e => fail(e.toString), _.config)

  private def okv[A](r: StorageResult[A]): A = r.fold(e => fail(e.toString), identity)

  private def specFor(name: String, pk: String, sk: List[String], rows: Int) =
    DynamoTableSpec(
      TableRef(name, s"scholarchain-graph/$name", "parquet", Nil, rows.toLong),
      pk,
      sk,
    )

  private def metricsTable(rows: Vector[Vector[Cell]]): TableData = TableData(
    "researcher-metrics",
    Vector(
      Column("researcherId", "string"),
      Column("window", "string"),
      Column("coauthorCount", "int"),
      Column("score", "double"),
      Column("note", "string"),
      Column("topics", "array<string>"),
      Column("active", "boolean"),
      Column("bigCount", "bigint"),
    ),
    rows,
  )

  private def mrow(
      id: String,
      window: String,
      count: Int = 2,
      score: Cell = Cell.D(0.5),
      note: Cell = Cell.Null,
  ): Vector[Cell] =
    Vector(
      Cell.S(id),
      Cell.S(window),
      Cell.I(count),
      score,
      note,
      Cell.Arr(List(Cell.S("graphs"), Cell.S("ml"))),
      Cell.B(true),
      Cell.L(7L),
    )

  private val metricsSpec = specFor("researcher-metrics", "researcherId", List("window"), 3)

  private def items(spec: DynamoTableSpec, t: TableData): Vector[AnalyticsItem] =
    AnalyticsItems.build(spec, t).fold(e => fail(e), _.items)

  private val baseRows = Vector(
    mrow("r-1", "2000-2004"),
    mrow("r-1", "2005-2009", count = 3),
    mrow("r-1", "1990-1994", count = 1),
    mrow("r-2", "2000-2004", count = 9),
  )

  private def fresh(env: Map[String, String] = Map.empty) =
    val c  = cfg(env)
    val kv = InMemoryKeyValueStore(AnalyticsRepository.schemas(c.tables))
    (c, kv, AnalyticsRepository.fromConfig(kv, c.tables))

  private def metricItems = items(metricsSpec, metricsTable(baseRows))

  private def physical(c: StorageModuleConfig, logical: String): String =
    c.tables.analyticsTable(logical).getOrElse(fail(s"no table for $logical"))

  // ---- idempotent reload ----------------------------------------------------------------------

  test("first load writes every item, an identical reload is all Unchanged and changes nothing") {
    val (c, kv, repo) = fresh()
    val is            = metricItems
    val r1            = okv(repo.putAll(is))
    assertEquals((r1.written, r1.unchanged, r1.conflicts), (4L, 0L, 0L))
    val snap = kv.snapshot(physical(c, "researcher-metrics"))
    assertEquals(snap.size, 4)
    val r2 = okv(repo.putAll(is))
    assertEquals((r2.written, r2.unchanged, r2.conflicts), (0L, 4L, 0L))
    assert(r2.ok)
    assertEquals(kv.snapshot(physical(c, "researcher-metrics")), snap)
  }

  test("a single put reports Written, then Unchanged") {
    val (_, _, repo) = fresh()
    val i            = metricItems.head
    assertEquals(okv(repo.put(i)), WriteResult.Written)
    assertEquals(okv(repo.put(i)), WriteResult.Unchanged)
  }

  test("changed attributes under the same key are a Conflict and the stored item is unchanged") {
    val (c, kv, repo) = fresh()
    okv(repo.putAll(metricItems))
    val before  = kv.snapshot(physical(c, "researcher-metrics"))
    val changed = items(
      metricsSpec,
      metricsTable(baseRows.updated(0, mrow("r-1", "2000-2004", count = 5))),
    )
    val rep = okv(repo.putAll(changed))
    assertEquals((rep.written, rep.unchanged, rep.conflicts), (0L, 3L, 1L))
    assert(!rep.ok)
    assertEquals(rep.conflictExamples.size, 1)
    assert(
      rep.conflictExamples.head.startsWith("researcher-metrics"),
      rep.conflictExamples.toString,
    )
    assertEquals(kv.snapshot(physical(c, "researcher-metrics")), before)
    val stored = okv(repo.researcherMetrics("r-1", "2000-2004")).get
    assertEquals(stored("coauthorCount").num, 2.0)
  }

  test("putAll counts every conflict but describes at most five, and never overwrites") {
    val (c, kv, repo) = fresh()
    val rows          = (1 to 7).toVector.map(i => mrow(s"r-$i", "2000-2004"))
    okv(repo.putAll(items(metricsSpec, metricsTable(rows))))
    val before = kv.snapshot(physical(c, "researcher-metrics"))
    val rep    = okv(
      repo.putAll(
        items(metricsSpec, metricsTable(rows.map(r => r.updated(2, Cell.I(99)))))
      )
    )
    assertEquals(rep.conflicts, 7L)
    assertEquals(rep.conflictExamples.size, 5)
    assertEquals(kv.snapshot(physical(c, "researcher-metrics")), before)
  }

  test("the stored item holds pk, sk, `payload` (canonical JSON) and `payloadHash` only") {
    val (c, kv, repo) = fresh()
    val i             = metricItems.head
    okv(repo.put(i))
    val stored = kv.snapshot(physical(c, "researcher-metrics")).values.head
    assertEquals(stored.attributes.keySet, Set("payload", "payloadHash"))
    assertEquals(stored.attributes("payload"), i.attributesJson)
    assertEquals(stored.attributes("payloadHash"), i.payloadHash)
    assertEquals(stored.attributes("payloadHash"), Cells.sha256(stored.attributes("payload")))
    assertEquals(stored.key, KvKey(i.pk, i.sk))
    assertEquals(stored.expiresAtEpochSeconds, None)
  }

  // ---- typed JSON round trip ------------------------------------------------------------------

  test("typed JSON survives the round trip: ints, doubles, null, arrays, booleans, strings") {
    val (_, _, repo) = fresh()
    okv(
      repo.putAll(
        items(
          metricsSpec,
          metricsTable(Vector(mrow("r-1", "2000-2004", count = 2, score = Cell.D(0.25)))),
        )
      )
    )
    val v = okv(repo.researcherMetrics("r-1", "2000-2004")).getOrElse(fail("item not found"))
    assertEquals(v("coauthorCount").num, 2.0)
    assertEquals(ujson.write(v("coauthorCount")), "2") // an int stays an int, not "2.0" or "2"
    assertEquals(v("score").num, 0.25)
    assert(v("note").isNull)
    assertEquals(v("topics").arr.map(_.str).toList, List("graphs", "ml"))
    assertEquals(v("active").bool, true)
    assertEquals(v("bigCount").num, 7.0)
    assertEquals(v("researcherId").str, "r-1")
    assertEquals(v("window").str, "2000-2004")
  }

  test("the value read back equals the canonical JSON that was written, byte for byte") {
    val (_, _, repo) = fresh()
    val i            = metricItems.head
    okv(repo.put(i))
    val v = okv(repo.researcherMetrics("r-1", i.sk.get)).get
    assertEquals(ujson.write(v), i.attributesJson)
  }

  test("a nested object and a non-ASCII string round trip losslessly") {
    val t = TableData(
      "paths",
      Vector(
        Column("sourceId", "string"),
        Column("window", "string"),
        Column("detail", "struct<a:int>"),
      ),
      Vector(
        Vector(
          Cell.S("s-1"),
          Cell.S("2000-2004"),
          Cell.Obj(List("a" -> Cell.I(1), "name" -> Cell.S("Zoë \"Q\""), "none" -> Cell.Null)),
        )
      ),
    )
    val (_, _, repo) = fresh()
    val spec         = specFor("paths", "sourceId", List("window"), 1)
    okv(repo.putAll(items(spec, t)))
    val v = okv(repo.get("paths", "s-1", List("2000-2004"))).get
    assertEquals(v("detail")("name").str, "Zoë \"Q\"")
    assertEquals(v("detail")("a").num, 1.0)
    assert(v("detail")("none").isNull)
  }

  // ---- access patterns ------------------------------------------------------------------------

  test("researcherMetrics(id, window) is the one item of that researcher and window") {
    val (_, _, repo) = fresh()
    okv(repo.putAll(metricItems))
    assertEquals(okv(repo.researcherMetrics("r-1", "2005-2009")).get("coauthorCount").num, 3.0)
    assertEquals(okv(repo.researcherMetrics("r-2", "2000-2004")).get("coauthorCount").num, 9.0)
    assertEquals(okv(repo.researcherMetrics("r-2", "2005-2009")), None)
    assertEquals(okv(repo.researcherMetrics("r-9", "2000-2004")), None)
  }

  test("keys with '|' and '%' in a component are encoded consistently on write and read") {
    val (_, _, repo) = fresh()
    val spec         = metricsSpec
    val rows         = Vector(mrow("r|1%x", "2000-2004"))
    okv(repo.putAll(items(spec, metricsTable(rows))))
    assert(okv(repo.researcherMetrics("r|1%x", "2000-2004")).isDefined)
    assertEquals(okv(repo.researcherMetrics("r|1", "2000-2004")), None)
    assertEquals(okv(repo.researcherMetricsAllWindows("r|1%x", 10)).size, 1)
  }

  test("researcherMetricsAllWindows: one researcher, ascending by window label, bounded by limit") {
    val (_, _, repo) = fresh()
    okv(repo.putAll(metricItems))
    val all = okv(repo.researcherMetricsAllWindows("r-1", 10))
    assertEquals(all.map(_("window").str), List("1990-1994", "2000-2004", "2005-2009"))
    assert(all.forall(_("researcherId").str == "r-1"))
    assertEquals(
      okv(repo.researcherMetricsAllWindows("r-1", 2)).map(_("window").str),
      List("1990-1994", "2000-2004"),
    )
    assertEquals(okv(repo.researcherMetricsAllWindows("nobody", 5)), Nil)
    assert(repo.researcherMetricsAllWindows("r-1", 0).isLeft)
  }

  test("tables without a sort key are written and read by partition key alone") {
    val (c, kv, repo) = fresh()
    val t             = TableData(
      "window-summary",
      Vector(Column("window", "string"), Column("researchers", "int")),
      Vector(Vector(Cell.S("2000-2004"), Cell.I(12)), Vector(Cell.S("2005-2009"), Cell.I(14))),
    )
    val spec = specFor("window-summary", "window", Nil, 2)
    val rep  = okv(repo.putAll(items(spec, t)))
    assertEquals(rep.written, 2L)
    assertEquals(okv(repo.get("window-summary", "2000-2004")).get("researchers").num, 12.0)
    assertEquals(okv(repo.putAll(items(spec, t))).unchanged, 2L)
    assert(kv.snapshot(physical(c, "window-summary")).keys.forall(_._2 == ""))
  }

  // ---- errors ---------------------------------------------------------------------------------

  test("an unknown analytics table is InvalidRequest for put, get and the researcher patterns") {
    val (_, _, repo)             = fresh()
    def inv(r: StorageResult[?]) = r.left.exists(_.isInstanceOf[StorageError.InvalidRequest])
    assert(inv(repo.put(metricItems.head.copy(table = "not-a-table"))))
    assert(inv(repo.get("not-a-table", "r-1", List("w"))))
    assert(inv(repo.putAll(List(metricItems.head.copy(table = "not-a-table")))))
    val empty = AnalyticsRepository(InMemoryKeyValueStore(Map.empty), Map.empty)
    assert(inv(empty.researcherMetrics("r-1", "2000-2004")))
    assert(inv(empty.researcherMetricsAllWindows("r-1", 5)))
  }

  test("a store failure aborts putAll with the error (an item for a table the store lacks)") {
    val c    = cfg()
    val kv   = InMemoryKeyValueStore(Map.empty) // no physical tables at all
    val repo = AnalyticsRepository.fromConfig(kv, c.tables)
    assertEquals(repo.putAll(metricItems), Left(StorageError.NotFound("table")))
  }

  test(
    "a tampered payload (hash kept) is IntegrityMismatch on get, researcherMetrics and the query"
  ) {
    val (c, kv, repo) = fresh()
    okv(repo.putAll(metricItems))
    val t   = physical(c, "researcher-metrics")
    val key = KvKey("r-1", Some("2000-2004"))
    val it  = okv(kv.get(t, key)).get
    okv(
      kv.put(
        t,
        it.copy(attributes =
          it.attributes.updated("payload", it.attributes("payload").replace("\"r-1\"", "\"r-X\""))
        ),
        PutCondition.Always,
      )
    )
    def mismatch(r: StorageResult[?]) =
      r.left.exists(_.isInstanceOf[StorageError.IntegrityMismatch])
    assert(mismatch(repo.researcherMetrics("r-1", "2000-2004")), "researcherMetrics")
    assert(mismatch(repo.get("researcher-metrics", "r-1", List("2000-2004"))), "get")
    assert(mismatch(repo.researcherMetricsAllWindows("r-1", 10)), "query")
    // the untouched neighbour still reads
    assert(okv(repo.researcherMetrics("r-2", "2000-2004")).isDefined)
  }

  test("an item without a payload attribute is a Failed, not a crash or an empty value") {
    val (c, kv, repo) = fresh()
    okv(
      kv.put(
        physical(c, "researcher-metrics"),
        KvItem(KvKey("r-1", Some("w")), Map("x" -> "y")),
        PutCondition.Always,
      )
    )
    val r = repo.researcherMetrics("r-1", "w")
    assert(r.left.exists(_.isInstanceOf[StorageError.Failed]), r.toString)
  }

  // ---- configured table names -----------------------------------------------------------------

  test("fromConfig writes into cfg.tables.analytics names, so an overridden name is the one used") {
    val k = StorageConfigKeys.AnalyticsTableKeys.collectFirst { case ("bridges", k) => k }.get
    val (c, kv, repo) = fresh(Map(k.envName -> "custom-bridges-table"))
    assertEquals(c.tables.analyticsTable("bridges"), Some("custom-bridges-table"))
    val t = TableData(
      "bridges",
      Vector(
        Column("researcherId", "string"),
        Column("window", "string"),
        Column("measure", "double"),
      ),
      Vector(Vector(Cell.S("r-1"), Cell.S("2000-2004"), Cell.D(0.3))),
    )
    val spec = specFor("bridges", "researcherId", List("window"), 1)
    assertEquals(okv(repo.putAll(items(spec, t))).written, 1L)
    assertEquals(kv.snapshot("custom-bridges-table").size, 1)
    assertEquals(kv.snapshot("scholarchain-analytics-bridges"), Map.empty)
    assertEquals(okv(repo.get("bridges", "r-1", List("2000-2004"))).get("measure").num, 0.3)
  }

  test("schemas: one in-memory table per configured analytics name, sort key as in the manifest") {
    val c = cfg()
    val s = AnalyticsRepository.schemas(c.tables)
    assertEquals(s.keySet, c.tables.analyticsNames.toSet)
    assertEquals(s.size, 10)
    assertEquals(
      AnalyticsRepository.HasSortKey.keySet,
      StorageConfigKeys.AnalyticsLogicalNames.toSet,
    )
    StorageConfigKeys.AnalyticsLogicalNames.foreach { n =>
      assertEquals(
        s(physical(c, n)).hasSortKey,
        AnalyticsRepository.HasSortKey(n),
        n,
      )
    }
    assertEquals(AnalyticsRepository.HasSortKey.values.count(identity), 6)
  }

  test(
    "the repository never uses an unconditional overwrite (puts are NotExists or a status/expiry condition)"
  ) {
    val c     = cfg()
    val seen  = scala.collection.mutable.ListBuffer.empty[PutCondition]
    val inner = InMemoryKeyValueStore(AnalyticsRepository.schemas(c.tables))
    val spy   = new KeyValueStore:
      def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
        seen += condition
        inner.put(table, item, condition)
      def get(table: String, key: KvKey) = inner.get(table, key)
      def query(
          table: String,
          partition: String,
          sort: Option[SortCondition],
          limit: Int,
          ascending: Boolean,
          continuationToken: Option[String],
      ) = inner.query(table, partition, sort, limit, ascending, continuationToken)
    val repo = AnalyticsRepository.fromConfig(spy, c.tables)
    okv(repo.putAll(metricItems))
    okv(repo.putAll(metricItems))
    okv(
      repo.putAll(
        items(metricsSpec, metricsTable(baseRows.updated(0, mrow("r-1", "2000-2004", count = 5))))
      )
    )
    assert(seen.nonEmpty)
    assert(!seen.contains(PutCondition.Always), seen.toList.toString)
    assert(seen.forall(_ == PutCondition.NotExists), seen.toList.toString)
  }
