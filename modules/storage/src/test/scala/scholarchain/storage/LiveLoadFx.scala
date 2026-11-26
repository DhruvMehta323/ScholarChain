package scholarchain.storage

import java.nio.charset.StandardCharsets

import scala.collection.mutable

import scholarchain.core.*

/** Fakes and fixtures for the `LiveLoad` suites. No AWS call, no credential: an in-memory object
  * store, a scripted bulk loader, a recording key-value store, a recording sleeper that advances a
  * fake clock. Synthetic values only.
  */
object LiveLoadFx:

  val Bucket: String        = "placeholder-scholarchain-bucket"
  val RunId: String         = "run-001"
  val CodeVersion: String   = "0.14.0-test"
  val Region: String        = "us-east-1"
  val NeptuneSource: String = s"s3://$Bucket/graph/neptune-load/$RunId/"
  val ItemsSource: String   = s"s3://$Bucket/graph/local-load/$RunId/"
  val StartMillis: Long     = 1700000000000L

  def cfg: StorageModuleConfig =
    StorageConfigLoader
      .load(Map.empty, Map.empty)
      .fold(e => throw AssertionError(e.toString), _.config)

  def params(
      maxItems: Long = 1000L,
      timeout: Long = 60L,
      poll: Long = 5L,
      dry: Boolean = false,
  ): LiveLoadParams =
    LiveLoadParams(NeptuneSource, ItemsSource, RunId, CodeVersion, maxItems, timeout, poll, dry)

  // ---- fakes -----------------------------------------------------------------------------------

  class MemObjectStore extends ObjectStore:
    val data: mutable.TreeMap[(String, String), Array[Byte]] =
      mutable.TreeMap.empty[(String, String), Array[Byte]]
    var gets: Int = 0

    private def meta(l: ObjectLocation, b: Array[Byte], ct: Option[String]) =
      ObjectMeta(l, b.length.toLong, Hashing.sha256Hex(b), ct, None, None)

    def put(
        location: ObjectLocation,
        bytes: Array[Byte],
        contentType: Option[String],
        expectedHash: Option[String],
    ): StorageResult[ObjectMeta] =
      synchronized {
        data((location.bucket, location.objectKey)) = bytes.clone()
        Right(meta(location, bytes, contentType))
      }

    def putIfAbsent(
        location: ObjectLocation,
        bytes: Array[Byte],
        contentType: Option[String],
        expectedHash: Option[String],
    ): StorageResult[ObjectMeta] =
      synchronized {
        if data.contains((location.bucket, location.objectKey)) then
          Left(StorageError.AlreadyExists("object"))
        else put(location, bytes, contentType, expectedHash)
      }

    def get(location: ObjectLocation): StorageResult[ObjectData] =
      synchronized {
        gets += 1
        data
          .get((location.bucket, location.objectKey))
          .toRight(StorageError.NotFound("object"))
          .map(b => ObjectData(meta(location, b, None), b.clone()))
      }

    def head(location: ObjectLocation): StorageResult[Option[ObjectMeta]] =
      synchronized(
        Right(data.get((location.bucket, location.objectKey)).map(meta(location, _, None)))
      )

    def list(
        bucket: String,
        prefix: String,
        limit: Int,
        continuationToken: Option[String],
    ): StorageResult[ObjectPage] =
      synchronized {
        val all = data.keys.toList
          .filter((b, k) => b == bucket && k.startsWith(prefix))
          .map(_._2)
          .sorted
          .filter(k => continuationToken.forall(k > _))
        val page = all.take(limit)
        Right(
          ObjectPage(
            page.map(k => meta(ObjectLocation(bucket, k), data((bucket, k)), None)),
            if all.size > limit then page.lastOption else None,
          )
        )
      }

    def keys(prefix: String): List[String] =
      data.keys.toList.filter(_._2.startsWith(prefix)).map(_._2).sorted

  /** Scripted loader: `statuses` are returned in order, the last one repeats. */
  final class FakeLoader(
      startResult: StorageResult[BulkLoadHandle],
      statuses: List[StorageResult[BulkLoadStatus]],
  ) extends BulkLoader:
    var starts: List[NeptuneLoaderRequest]                                  = Nil
    var statusCalls: Int                                                    = 0
    def start(request: NeptuneLoaderRequest): StorageResult[BulkLoadHandle] =
      starts = starts :+ request
      startResult
    def status(handle: BulkLoadHandle): StorageResult[BulkLoadStatus] =
      val r = statuses(math.min(statusCalls, statuses.size - 1))
      statusCalls += 1
      r
    def calls: Int = starts.size + statusCalls

  object FakeLoader:
    val LoadId                                      = "load-0001"
    def completing(records: Long = 42L): FakeLoader =
      FakeLoader(
        Right(BulkLoadHandle(LoadId)),
        List(Right(BulkLoadStatus.Completed(Some(records)))),
      )
    def scripted(statuses: List[StorageResult[BulkLoadStatus]]): FakeLoader =
      FakeLoader(Right(BulkLoadHandle(LoadId)), statuses)

  /** Thread-safe: parallel item puts reach the sleeper (retry) and the clock. */
  final class FakeClock:
    private val t                   = new java.util.concurrent.atomic.AtomicLong(StartMillis)
    def now: Long                   = t.get
    def now_=(v: Long): Unit        = t.set(v)
    def advance(millis: Long): Unit = t.addAndGet(millis)
    val clock: LiveClock            = () => now

  /** Records every wait and advances the fake clock by it (so a poll loop terminates). Thread-safe.
    */
  final class RecordingSleeper(clock: FakeClock) extends Sleeper:
    private val log        = new java.util.concurrent.CopyOnWriteArrayList[Long]()
    def sleeps: List[Long] = scala.jdk.CollectionConverters.ListHasAsScala(log).asScala.toList
    def sleep(millis: Long): Unit =
      log.add(millis)
      clock.advance(millis)

  /** Counts every call and can fail every `put`. Thread-safe counters (puts run in parallel). */
  final class RecordingKv(inner: KeyValueStore, val putError: Option[StorageError] = None)
      extends KeyValueStore:
    private val p    = new java.util.concurrent.atomic.AtomicInteger(0)
    private val g    = new java.util.concurrent.atomic.AtomicInteger(0)
    private val q    = new java.util.concurrent.atomic.AtomicInteger(0)
    def puts: Int    = p.get
    def gets: Int    = g.get
    def queries: Int = q.get
    def calls: Int   = puts + gets + queries
    def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
      p.incrementAndGet()
      putError.map(Left(_)).getOrElse(inner.put(table, item, condition))
    def get(table: String, key: KvKey): StorageResult[Option[KvItem]] =
      g.incrementAndGet()
      inner.get(table, key)
    def query(
        table: String,
        partition: String,
        sort: Option[SortCondition],
        limit: Int,
        ascending: Boolean,
        continuationToken: Option[String],
    ): StorageResult[KvPage] =
      q.incrementAndGet()
      inner.query(table, partition, sort, limit, ascending, continuationToken)

  /** One wired set of fakes. */
  final class Rig(
      val store: MemObjectStore,
      val loader: FakeLoader,
      val kvInner: InMemoryKeyValueStore,
      val kv: RecordingKv,
      val sleeper: RecordingSleeper,
      val fakeClock: FakeClock,
  ):
    def ports: LiveLoadPorts = LiveLoadPorts(store, loader, kv, sleeper, fakeClock.clock)
    def run(p: LiveLoadParams): LiveLoadReport = LiveLoad.run(ports, cfg, Region, p)
    def physical(logical: String): String      =
      cfg.tables.analyticsTable(logical).getOrElse(throw AssertionError(logical))

  def rig(
      loader: FakeLoader = FakeLoader.completing(),
      kvInner: Option[InMemoryKeyValueStore] = None,
      putError: Option[StorageError] = None,
      store: MemObjectStore = new MemObjectStore,
  ): Rig =
    val c     = cfg
    val inner = kvInner.getOrElse(InMemoryKeyValueStore(AnalyticsRepository.schemas(c.tables)))
    val clock = new FakeClock
    new Rig(
      store,
      loader,
      inner,
      new RecordingKv(inner, putError),
      new RecordingSleeper(clock),
      clock,
    )

  def freshKv(): InMemoryKeyValueStore = InMemoryKeyValueStore(
    AnalyticsRepository.schemas(cfg.tables)
  )

  // ---- fixture data ----------------------------------------------------------------------------

  private def spec(name: String, pk: String, sk: List[String], rows: Int) =
    DynamoTableSpec(
      TableRef(name, s"scholarchain-graph/$name", "parquet", Nil, rows.toLong),
      pk,
      sk,
    )

  private val metrics: TableData = TableData(
    "researcher-metrics",
    Vector(
      Column("researcherId", "string"),
      Column("window", "string"),
      Column("coauthorCount", "int"),
      Column("score", "double"),
      Column("topics", "array<string>"),
    ),
    Vector(
      Vector(
        Cell.S("r-1"),
        Cell.S("2000-2004"),
        Cell.I(2),
        Cell.D(0.5),
        Cell.Arr(List(Cell.S("a"))),
      ),
      Vector(Cell.S("r-1"), Cell.S("2005-2009"), Cell.I(3), Cell.Null, Cell.Arr(Nil)),
      Vector(Cell.S("r-2"), Cell.S("2000-2004"), Cell.I(9), Cell.D(1.25), Cell.Null),
    ),
  )

  private val windows: TableData = TableData(
    "window-summary",
    Vector(
      Column("window", "string"),
      Column("researchers", "bigint"),
      Column("provenance", "string"),
    ),
    Vector(
      Vector(Cell.S("2000-2004"), Cell.L(10L), Cell.S("""{"runId":"run:spark-fixture"}""")),
      Vector(Cell.S("2005-2009"), Cell.L(12L), Cell.S("""{"runId":"run:spark-fixture"}""")),
    ),
  )

  private val clusters: TableData = TableData(
    "identity-clusters",
    Vector(Column("clusterId", "string"), Column("size", "int")),
    Vector(Vector(Cell.S("c-1"), Cell.I(2)), Vector(Cell.S("c-2"), Cell.I(1))),
  )

  /** All analytics items of the fixture, in table order: 3 + 2 + 2 = 7. */
  val itemFiles: List[AnalyticsItems.ItemFile] = List(
    AnalyticsItems.build(spec("researcher-metrics", "researcherId", List("window"), 3), metrics),
    AnalyticsItems.build(spec("window-summary", "window", Nil, 2), windows),
    AnalyticsItems.build(spec("identity-clusters", "clusterId", Nil, 2), clusters),
  ).map(_.fold(e => throw AssertionError(e), identity))

  val ItemTotal: Long = itemFiles.map(_.rows).sum

  def allItems: List[AnalyticsItems.AnalyticsItem] = itemFiles.flatMap(_.items)

  private def utf8(s: String) = s.getBytes(StandardCharsets.UTF_8)

  private def jsonl(rows: List[ujson.Obj]): Array[Byte] =
    utf8(rows.map(r => ujson.write(r) + "\n").mkString)

  /** Embedding-input JSONL files in the `LocalLoad` line schema (keys ascending). */
  val embedFiles: Map[String, Array[Byte]] = Map(
    "semantic-summaries" -> jsonl(
      List(
        ujson.Obj(
          "documentType" -> "researcher-profile",
          "entityId"     -> "r-1",
          "runId"        -> "run:spark-fixture",
          "text"         -> "Ada works on graphs.",
          "textHash"     -> Hashing.sha256Hex(utf8("Ada works on graphs.")),
          "windowEnd"    -> 2004,
          "windowStart"  -> 2000,
        )
      )
    ),
    "topics" -> jsonl(List(ujson.Obj("label" -> "Graph mining", "topicId" -> "topic:1"))),
    "venues" -> jsonl(
      List(ujson.Obj("name" -> "SIGMOD", "venueId" -> "venue:1"))
    ),
    "works" -> jsonl(
      List(
        ujson.Obj(
          "doi"      -> ujson.Null,
          "title"    -> "Graph Mining",
          "topicIds" -> ujson.Arr("topic:1"),
          "venueId"  -> ujson.Null,
          "workId"   -> "work:dblp:conf/sigmod/Foo01",
          "year"     -> 2001,
        )
      )
    ),
  )

  /** `(path under neptune/, bytes, rows)` of the real `NeptuneCsv` output for the shared fixture.
    */
  val neptuneCsv: List[(String, Array[Byte], Long)] =
    NeptuneFx.fixture.build
      .fold(e => throw AssertionError(e.toString), identity)
      .files
      .map(f => (s"neptune/${f.relativePath}", f.bytes, f.rows))

  val InvariantNames: List[String] = List(
    "danglingEndpoints",
    "duplicateEdgeIds",
    "duplicateVertexIds",
    "edgeEndpointIdMismatches",
    "edgeIdMismatches",
    "endpointLabelMismatches",
    "headerProblems",
    "labelColumnMismatches",
    "vertexIdFormatViolations",
    "vertexIdMismatches",
  )

  /** A consistent `LocalLoad`-style output tree. `render` derives the manifest and checksums from
    * the CURRENT file contents, so a mutated fixture is still internally consistent; `edit` changes
    * the manifest JSON before it is serialized. To model corruption after the fact, change the
    * bytes of the rendered map.
    */
  final case class Fx(
      neptune: List[(String, Array[Byte], Long)] = neptuneCsv,
      items: Map[String, Array[Byte]] = itemFiles.map(f => f.table -> f.bytes).toMap,
      embeds: Map[String, Array[Byte]] = embedFiles,
      withEmbeds: Boolean = true,
      edit: ujson.Obj => Unit = _ => (),
  ):
    private def lines(b: Array[Byte]): Long =
      new String(b, StandardCharsets.UTF_8).split("\n").count(_.nonEmpty).toLong

    def mapNeptune(f: ((String, Array[Byte], Long)) => (String, Array[Byte], Long)): Fx =
      copy(neptune = neptune.map(f))

    def withEdit(g: ujson.Obj => Unit): Fx =
      val prev = edit
      copy(edit = m =>
        prev(m)
        g(m))

    def manifest: ujson.Obj =
      val m = ujson.Obj()
      m("codeVersion") = "0.13.0-local"
      m("dynamodb") = ujson.Obj(
        "files" -> ujson.Arr.from(
          items.toList
            .sortBy(_._1)
            .map((t, b) =>
              ujson.Obj(
                "path"   -> s"items/$t.jsonl",
                "rows"   -> lines(b).toDouble,
                "sha256" -> Cells.sha256(b),
                "table"  -> t,
              )
            )
        )
      )
      if withEmbeds then
        m("embeddingInputs") = ujson.Obj(
          "files" -> ujson.Arr.from(
            embeds.toList
              .sortBy(_._1)
              .map((t, b) =>
                ujson.Obj(
                  "path"   -> s"embedding-inputs/$t.jsonl",
                  "rows"   -> lines(b).toDouble,
                  "sha256" -> Cells.sha256(b),
                  "table"  -> t,
                )
              )
          ),
          "sparkCodeVersion" -> "0.13.0-spark",
          "sparkRunId"       -> "run:spark-fixture",
        )
      m("input") =
        ujson.Obj("sparkCodeVersion" -> "0.13.0-spark", "sparkRunId" -> "run:spark-fixture")
      m("loadRunId") = "load:fixture-0001"
      m("loader") = "scholarchain.storage.LocalLoad"
      m("neptune") = ujson.Obj(
        "files" -> ujson.Arr.from(
          neptune
            .sortBy(_._1)
            .map((p, b, r) =>
              ujson.Obj("path" -> p, "rows" -> r.toDouble, "sha256" -> Cells.sha256(b))
            )
        ),
        "invariants" -> ujson.Obj.from(InvariantNames.map(_ -> ujson.Num(0.0))),
      )
      edit(m)
      m

    /** Every object of the output tree, keyed by its path relative to the run (neptune files keep
      * their `neptune/` prefix). Includes `load-manifest.json` and `checksums.sha256`.
      */
    def render: Map[String, Array[Byte]] =
      val content: Map[String, Array[Byte]] =
        neptune.map((p, b, _) => p -> b).toMap ++
          items.map((t, b) => s"dynamodb/items/$t.jsonl" -> b) ++
          (if withEmbeds then embeds.map((t, b) => s"embedding-inputs/$t.jsonl" -> b)
           else Map.empty)
      val man  = utf8(ujson.write(manifest, indent = 2) + "\n")
      val all  = content.updated("load-manifest.json", man)
      val sums = all.toList.sortBy(_._1).map((p, b) => s"${Cells.sha256(b)}  $p\n").mkString
      all.updated("checksums.sha256", utf8(sums))

  def flip(b: Array[Byte]): Array[Byte] =
    val c = b.clone()
    c(c.length / 2) = (c(c.length / 2) ^ 0x01).toByte
    c

  /** Uploads a rendered tree in the agreed layout: `neptune/` files under the Neptune prefix with
    * `neptune/` stripped, everything else under the items prefix.
    */
  def upload(
      store: ObjectStore,
      files: Map[String, Array[Byte]],
      neptuneSource: String = NeptuneSource,
      itemsSource: String = ItemsSource,
  ): Unit =
    val n = ObjectLocation.parse(neptuneSource).fold(e => throw AssertionError(e.message), identity)
    val i = ObjectLocation.parse(itemsSource).fold(e => throw AssertionError(e.message), identity)
    files.foreach { (path, bytes) =>
      val loc =
        if path.startsWith("neptune/") then
          ObjectLocation(n.bucket, n.objectKey + path.stripPrefix("neptune/"))
        else ObjectLocation(i.bucket, i.objectKey + path)
      store.put(loc, bytes, None, None).fold(e => throw AssertionError(e.toString), identity)
    }

  def prov(r: LiveLoadReport): ujson.Value = ujson.read(r.provenanceJson)
end LiveLoadFx
