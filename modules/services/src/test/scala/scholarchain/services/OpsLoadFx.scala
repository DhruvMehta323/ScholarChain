package scholarchain.services

import java.nio.charset.StandardCharsets

import scala.collection.mutable

import scholarchain.core.*
import scholarchain.storage.*

/** Fakes and fixtures for the `OpsLoad` / `OpsLoadMain` suites (Phase 14 Part A). No AWS call, no
  * credential, no network: an in-memory object store that records every write attempt, a scripted
  * bulk loader, a counting key-value store, a counting work queue and a fake clock. Synthetic
  * values only.
  */
object OpsFx:

  val Bucket: String        = "placeholder-scholarchain-bucket"
  val RunId: String         = "run-001"
  val CodeVersion: String   = "0.14.0-test"
  val Region: String        = "us-east-1"
  val NeptuneSource: String = s"s3://$Bucket/graph/neptune-load/$RunId/"
  val ItemsSource: String   = s"s3://$Bucket/graph/local-load/$RunId/"
  val ManifestKey: String   = s"graph/local-load/$RunId/ops-manifest.json"
  val SparkRunId: String    = "run:" + ("ab" * 32)
  val SparkCode: String     = "0.13.0-spark"
  val TextPrefix: String    = "rag/text"
  val StartMillis: Long     = 1700000000000L

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  def storageCfg: StorageModuleConfig =
    StorageConfigLoader.load(Map.empty).fold(e => throw AssertionError(e.toString), _.config)

  def embeddingSettings: EmbeddingSettings =
    EmbeddingConfigLoader.load(Map.empty).fold(e => throw AssertionError(e.toString), _.settings)

  def ctx(imageTag: Option[String] = None, poll: Long = 1L): OpsContext =
    OpsContext(storageCfg, embeddingSettings, Region, Bucket, TextPrefix, imageTag, poll)

  def args(
      maxVectors: Int = 10000,
      dry: Boolean = false,
      maxItems: Long = 1000000L,
      timeout: Long = 60L,
      runId: String = RunId,
      neptuneSource: String = NeptuneSource,
      itemsSource: String = ItemsSource,
  ): OpsArgs =
    OpsArgs(neptuneSource, itemsSource, runId, CodeVersion, maxVectors, timeout, maxItems, dry)

  // ---- fakes -----------------------------------------------------------------------------------

  /** In-memory object store. `writeAttempts` records every `put` and `putIfAbsent` call (even a
    * failed one). `seed` writes without recording (test setup).
    */
  class MemStore extends ObjectStore:
    val data: mutable.TreeMap[(String, String), Array[Byte]] =
      mutable.TreeMap.empty[(String, String), Array[Byte]]
    val writeAttempts: mutable.ListBuffer[String]         = mutable.ListBuffer.empty
    var gets: Int                                         = 0
    var heads: Int                                        = 0
    var failWrite: ObjectLocation => Option[StorageError] = _ => None
    var throwOnGet: Option[Throwable]                     = None

    private def meta(l: ObjectLocation, b: Array[Byte]) =
      ObjectMeta(l, b.length.toLong, Hashing.sha256Hex(b), None, None, None)

    def seed(location: ObjectLocation, bytes: Array[Byte]): Unit =
      synchronized { data((location.bucket, location.objectKey)) = bytes.clone() }

    def put(
        location: ObjectLocation,
        bytes: Array[Byte],
        contentType: Option[String],
        expectedHash: Option[String],
    ): StorageResult[ObjectMeta] =
      synchronized {
        writeAttempts += location.objectKey
        failWrite(location) match
          case Some(e) => Left(e)
          case None    =>
            data((location.bucket, location.objectKey)) = bytes.clone()
            Right(meta(location, bytes))
      }

    def putIfAbsent(
        location: ObjectLocation,
        bytes: Array[Byte],
        contentType: Option[String],
        expectedHash: Option[String],
    ): StorageResult[ObjectMeta] =
      synchronized {
        writeAttempts += location.objectKey
        failWrite(location) match
          case Some(e)                                                      => Left(e)
          case None if data.contains((location.bucket, location.objectKey)) =>
            Left(StorageError.AlreadyExists("object"))
          case None =>
            data((location.bucket, location.objectKey)) = bytes.clone()
            Right(meta(location, bytes))
      }

    def get(location: ObjectLocation): StorageResult[ObjectData] =
      synchronized {
        gets += 1
        throwOnGet.foreach(t => throw t)
        data
          .get((location.bucket, location.objectKey))
          .toRight(StorageError.NotFound("object"))
          .map(b => ObjectData(meta(location, b), b.clone()))
      }

    def head(location: ObjectLocation): StorageResult[Option[ObjectMeta]] =
      synchronized {
        heads += 1
        Right(data.get((location.bucket, location.objectKey)).map(meta(location, _)))
      }

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
            page.map(k => meta(ObjectLocation(bucket, k), data((bucket, k)))),
            if all.size > limit then page.lastOption else None,
          )
        )
      }

    def keys(prefix: String): List[String] =
      synchronized(data.keys.toList.filter(_._2.startsWith(prefix)).map(_._2).sorted)

    def bytesOf(key: String): Option[Array[Byte]] =
      synchronized(data.get((Bucket, key)))

    def textObjects: List[String] = keys(TextPrefix + "/")

  final class FakeLoader(
      startResult: StorageResult[BulkLoadHandle],
      statuses: List[StorageResult[BulkLoadStatus]],
  ) extends BulkLoader:
    var starts: Int                                                         = 0
    var statusCalls: Int                                                    = 0
    def start(request: NeptuneLoaderRequest): StorageResult[BulkLoadHandle] =
      starts += 1
      startResult
    def status(handle: BulkLoadHandle): StorageResult[BulkLoadStatus] =
      val r = statuses(math.min(statusCalls, statuses.size - 1))
      statusCalls += 1
      r
    def calls: Int = starts + statusCalls

  object FakeLoader:
    def completing(records: Long = 42L): FakeLoader =
      FakeLoader(
        Right(BulkLoadHandle("load-0001")),
        List(Right(BulkLoadStatus.Completed(Some(records)))),
      )
    def scripted(statuses: List[StorageResult[BulkLoadStatus]]): FakeLoader =
      FakeLoader(Right(BulkLoadHandle("load-0001")), statuses)
    def failingStart(e: StorageError): FakeLoader =
      FakeLoader(Left(e), List(Right(BulkLoadStatus.Submitted)))

  final class FakeClock:
    var now: Long        = StartMillis
    val clock: LiveClock = () => now

  final class RecordingSleeper(clock: FakeClock) extends Sleeper:
    var sleeps: Int               = 0
    def sleep(millis: Long): Unit =
      sleeps += 1
      clock.now += millis

  final class RecordingKv(inner: KeyValueStore) extends KeyValueStore:
    var puts: Int    = 0
    var gets: Int    = 0
    var queries: Int = 0
    def calls: Int   = puts + gets + queries
    def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
      puts += 1
      inner.put(table, item, condition)
    def get(table: String, key: KvKey): StorageResult[Option[KvItem]] =
      gets += 1
      inner.get(table, key)
    def query(
        table: String,
        partition: String,
        sort: Option[SortCondition],
        limit: Int,
        ascending: Boolean,
        continuationToken: Option[String],
    ): StorageResult[KvPage] =
      queries += 1
      inner.query(table, partition, sort, limit, ascending, continuationToken)

  class RecordingQueue(
      val inner: InMemoryWorkQueue = new InMemoryWorkQueue(),
      var failSend: Option[StorageError] = None,
  ) extends WorkQueue:
    var sends: Int                                         = 0
    val sent: mutable.ListBuffer[(String, Option[String])] = mutable.ListBuffer.empty
    def send(queue: String, body: String, deduplicationId: Option[String]): StorageResult[String] =
      sends += 1
      failSend match
        case Some(e) => Left(e)
        case None    =>
          sent += ((body, deduplicationId))
          inner.send(queue, body, deduplicationId)
    def receive(
        queue: String,
        maxMessages: Int,
        visibilityTimeoutSeconds: Int,
        waitSeconds: Int,
    ): StorageResult[List[QueueMessage]] =
      inner.receive(queue, maxMessages, visibilityTimeoutSeconds, waitSeconds)
    def delete(queue: String, receiptHandle: String): StorageResult[Unit] =
      inner.delete(queue, receiptHandle)

    def jobs: List[EmbeddingJob] =
      sent.toList.map((b, _) =>
        EmbeddingJobMessage.decode(b).fold(e => throw AssertionError(e), identity)
      )
    def documents: List[EmbeddingDocument] = jobs.flatMap(_.documents)

  /** One wired set of fakes plus the emitted lines. */
  final class Rig(
      val store: MemStore,
      val loader: FakeLoader,
      val kv: RecordingKv,
      val queue: RecordingQueue,
      val sleeper: RecordingSleeper,
      val fakeClock: FakeClock,
  ):
    val lines: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    def ports: OpsPorts =
      OpsPorts(LiveLoadPorts(store, loader, kv, sleeper, fakeClock.clock), queue)

    def run(a: OpsArgs, c: OpsContext = ctx()): OpsOutcome =
      OpsLoad.run(a, c, ports, l => lines += l)

    def json: List[ujson.Value]                 = lines.toList.map(ujson.read(_))
    def events(name: String): List[ujson.Value] =
      json.filter(_.obj.get("event").exists(_.str == name))
    def event(name: String): ujson.Value =
      events(name).headOption.getOrElse(throw AssertionError(s"no event $name in ${lines.toList}"))

    /** Every effect that must stay at zero when a run is refused in the plan. */
    def effects: (Int, Int, Int, Int, Int) =
      (loader.calls, kv.calls, queue.sends, store.textObjects.size, store.writeAttempts.size)

    def manifestStored: Option[Array[Byte]] = store.bytesOf(ManifestKey)

  def freshKv(): InMemoryKeyValueStore =
    InMemoryKeyValueStore(AnalyticsRepository.schemas(storageCfg.tables))

  def rig(
      loader: FakeLoader = FakeLoader.completing(),
      store: MemStore = new MemStore,
      kvInner: Option[InMemoryKeyValueStore] = None,
      queue: RecordingQueue = new RecordingQueue(),
  ): Rig =
    val clock = new FakeClock
    new Rig(
      store,
      loader,
      new RecordingKv(kvInner.getOrElse(freshKv())),
      queue,
      new RecordingSleeper(clock),
      clock,
    )

  // ---- source rows -----------------------------------------------------------------------------

  val ColumnTypes: Map[String, Map[String, String]] = Map(
    "semantic-summaries" -> Map(
      "entityId"     -> "string",
      "documentType" -> "string",
      "textHash"     -> "string",
      "text"         -> "string",
      "windowStart"  -> "int",
      "windowEnd"    -> "int",
      "runId"        -> "string",
    ),
    "works" -> Map(
      "workId"   -> "string",
      "title"    -> "string",
      "year"     -> "int",
      "doi"      -> "string",
      "venueId"  -> "string",
      "topicIds" -> "array<string>",
    ),
    "venues" -> Map("venueId" -> "string", "name" -> "string"),
    "topics" -> Map("topicId" -> "string", "label" -> "string"),
  )

  val VenueId: String = "venue:dblp:conf/x"
  val TopicId: String = "topic:dblp:graphs"

  def summaryRow(
      entity: String,
      text: String,
      typ: String = EmbeddingDocumentTypes.ResearcherProfile,
      ws: Int = 2000,
      we: Int = 2004,
  ): ujson.Obj =
    ujson.Obj(
      "documentType" -> typ,
      "entityId"     -> entity,
      "runId"        -> SparkRunId,
      "text"         -> text,
      "textHash"     -> Hashing.textHash(text),
      "windowEnd"    -> we,
      "windowStart"  -> ws,
    )

  def summaryN(i: Int): ujson.Obj =
    summaryRow(f"researcher:dblp:r$i%05d", s"Researcher number $i studied graphs and networks.")

  def workId(i: Int): String = f"work:dblp:conf/x/P$i%06d"

  def workRow(i: Int, title: Option[String] = None): ujson.Obj =
    ujson.Obj(
      "doi"      -> ujson.Null,
      "title"    -> title.getOrElse(s"Paper $i on graph mining"),
      "topicIds" -> ujson.Arr(TopicId),
      "venueId"  -> VenueId,
      "workId"   -> workId(i),
      "year"     -> 2001,
    )

  val venueRows: List[ujson.Obj] = List(ujson.Obj("name" -> "Conf X", "venueId" -> VenueId))
  val topicRows: List[ujson.Obj] = List(ujson.Obj("label" -> "Graph Theory", "topicId" -> TopicId))

  // ---- the LocalLoad-style output tree -----------------------------------------------------------

  private def spec(name: String, pk: String, sk: List[String], rows: Int) =
    DynamoTableSpec(
      TableRef(name, s"scholarchain-graph/$name", "parquet", Nil, rows.toLong),
      pk,
      sk,
    )

  private val windows: TableData = TableData(
    "window-summary",
    Vector(Column("window", "string"), Column("researchers", "bigint")),
    Vector(Vector(Cell.S("2000-2004"), Cell.L(10L)), Vector(Cell.S("2005-2009"), Cell.L(12L))),
  )
  private val clusters: TableData = TableData(
    "identity-clusters",
    Vector(Column("clusterId", "string"), Column("size", "int")),
    Vector(Vector(Cell.S("c-1"), Cell.I(2)), Vector(Cell.S("c-2"), Cell.I(1))),
  )

  val itemFiles: List[AnalyticsItems.ItemFile] = List(
    AnalyticsItems.build(spec("window-summary", "window", Nil, 2), windows),
    AnalyticsItems.build(spec("identity-clusters", "clusterId", Nil, 2), clusters),
  ).map(_.fold(e => throw AssertionError(e), identity))

  val ItemTotal: Long = itemFiles.map(_.rows).sum

  private val neptuneFiles: List[(String, Array[Byte], Long)] = List(
    ("neptune/vertices/Researcher.csv", utf8("~id,~label\nresearcher:1,Researcher\n"), 1L),
    ("neptune/edges/COAUTHOR.csv", utf8("~id,~from,~to,~label\ne1,a,b,COAUTHOR\n"), 1L),
  )

  private val InvariantNames: List[String] = List(
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

  private def sorted(o: ujson.Obj): ujson.Obj =
    ujson.Obj.from(o.value.toList.sortBy(_._1))

  /** A source tree. `render` derives manifest and checksums from the CURRENT contents, so a mutated
    * fixture is internally consistent; to model corruption after the fact, change bytes of the
    * rendered map. `extraLines` appends raw lines to a table file (malformed lines).
    */
  final case class Src(
      summaries: List[ujson.Obj] = List(summaryN(1), summaryN(2)),
      works: List[ujson.Obj] = List(workRow(1), workRow(2), workRow(3)),
      venues: List[ujson.Obj] = venueRows,
      topics: List[ujson.Obj] = topicRows,
      sparkRunId: String = SparkRunId,
      withEmbeds: Boolean = true,
      types: Map[String, Map[String, String]] = ColumnTypes,
      extraLines: Map[String, List[String]] = Map.empty,
      partDigest: String = "c" * 64,
      edit: ujson.Obj => Unit = _ => (),
  ):
    def rows(table: String): List[ujson.Obj] = table match
      case "semantic-summaries" => summaries
      case "works"              => works
      case "venues"             => venues
      case "topics"             => topics

    def tableBytes(table: String): Array[Byte] =
      utf8(
        (rows(table).map(r => ujson.write(sorted(r))) ++ extraLines.getOrElse(table, Nil))
          .map(_ + "\n")
          .mkString
      )

    private def lines(b: Array[Byte]): Long =
      new String(b, StandardCharsets.UTF_8).split("\n").count(_.nonEmpty).toLong

    def embedBytes: Map[String, Array[Byte]] =
      LocalLoad.EmbeddingTables.map(t => t -> tableBytes(t)).toMap

    def manifest: ujson.Obj =
      val m = ujson.Obj()
      m("codeVersion") = "0.13.0-local"
      m("dynamodb") = ujson.Obj(
        "files" -> ujson.Arr.from(
          itemFiles.map(f =>
            ujson.Obj(
              "path"   -> f.path,
              "rows"   -> f.rows.toDouble,
              "sha256" -> f.sha256,
              "table"  -> f.table,
            )
          )
        )
      )
      if withEmbeds then
        m("embeddingInputs") = ujson.Obj(
          "files" -> ujson.Arr.from(
            embedBytes.toList
              .sortBy(_._1)
              .map((t, b) =>
                ujson.Obj(
                  "columns" -> ujson.Arr.from(
                    types(t).toList
                      .sortBy(_._1)
                      .map((n, ty) => ujson.Obj("name" -> n, "sparkType" -> ty))
                  ),
                  "partFilesDigest" -> partDigest,
                  "path"            -> s"embedding-inputs/$t.jsonl",
                  "rows"            -> lines(b).toDouble,
                  "sha256"          -> Hashing.sha256Hex(b),
                  "table"           -> t,
                )
              )
          ),
          "sparkCodeVersion" -> SparkCode,
          "sparkRunId"       -> sparkRunId,
        )
      m("input") = ujson.Obj("sparkCodeVersion" -> SparkCode, "sparkRunId" -> sparkRunId)
      m("loadRunId") = "load:fixture-0001"
      m("loader") = "scholarchain.storage.LocalLoad"
      m("neptune") = ujson.Obj(
        "files" -> ujson.Arr.from(
          neptuneFiles
            .sortBy(_._1)
            .map((p, b, r) =>
              ujson.Obj("path" -> p, "rows" -> r.toDouble, "sha256" -> Hashing.sha256Hex(b))
            )
        ),
        "invariants" -> ujson.Obj.from(InvariantNames.map(_ -> ujson.Num(0.0))),
      )
      edit(m)
      m

    def render: Map[String, Array[Byte]] =
      val content: Map[String, Array[Byte]] =
        neptuneFiles.map((p, b, _) => p -> b).toMap ++
          itemFiles.map(f => s"dynamodb/${f.path}" -> f.bytes) ++
          (if withEmbeds then embedBytes.map((t, b) => s"embedding-inputs/$t.jsonl" -> b)
           else Map.empty)
      val man  = utf8(ujson.write(manifest, indent = 2) + "\n")
      val all  = content.updated("load-manifest.json", man)
      val sums = all.toList
        .sortBy(_._1)
        .map((p, b) => s"${Hashing.sha256Hex(b)}  $p\n")
        .mkString
      all.updated("checksums.sha256", utf8(sums))

  /** Uploads a rendered tree: `neptune/` files under the Neptune prefix (`neptune/` stripped),
    * everything else under the items prefix. Not recorded as a write attempt.
    */
  def upload(
      store: MemStore,
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
      store.seed(loc, bytes)
    }

  def rigWith(
      src: Src,
      loader: FakeLoader = FakeLoader.completing(),
      tamper: Map[String, Array[Byte]] => Map[String, Array[Byte]] = identity,
  ): Rig =
    val r = rig(loader)
    upload(r.store, tamper(src.render))
    r

  /** `n` summaries (types cycle through researcher/community) and `papers` works. */
  def sized(summaries: Int, papers: Int): Src =
    Src(
      summaries = (1 to summaries).toList.map(summaryN),
      works = (1 to papers).toList.map(i => workRow(i)),
    )

  def flip(b: Array[Byte]): Array[Byte] =
    val c = b.clone()
    c(c.length / 2) = (c(c.length / 2) ^ 0x01).toByte
    c

  // ---- reading manifests and lines ---------------------------------------------------------------

  def stripTimings(m: ujson.Value): ujson.Value =
    val c = ujson.read(ujson.write(m))
    c.obj.get("liveLoad").foreach { l =>
      l.obj.remove("durationsMillis")
      l.obj.remove("startedAtEpochMillis")
    }
    c

  /** Manifest without anything that depends on the input BYTES (their order may differ). */
  def stripInputs(m: ujson.Value): ujson.Value =
    val c = stripTimings(m)
    c.obj.remove("inputs")
    c.obj.get("liveLoad").foreach(_.obj.remove("inputs"))
    c

  def num(v: ujson.Value): Long = v.num.toLong

  extension (o: ujson.Obj)
    /** Fixture setter: sets one key and returns the same object. */
    def set(k: String, v: ujson.Value): ujson.Obj =
      o.value(k) = v
      o
