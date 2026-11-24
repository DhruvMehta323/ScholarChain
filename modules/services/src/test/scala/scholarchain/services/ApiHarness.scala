package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

import scholarchain.core.*
import scholarchain.core.rest.*

/** Phase 10 C test harness: the local-mode runtime (Phase 07 L4 and Phase 08 fixtures, fake model),
  * deterministic ids, a fixed clock, a log sink, and a loopback HTTP client. Dummy key values only.
  */
object ApiHarness:
  val PublicKey   = "dummy-public-key-for-tests-0001"
  val InternalKey = "dummy-internal-key-for-tests-0002"

  val FixedNow: Long = 1700000000000L

  def settings(deadlineMillis: Long = 60000L): ServiceSettings =
    ServiceSettings
      .fromEnv(Map("SCHOLARCHAIN_ENVIRONMENT" -> "local", "SCHOLARCHAIN_SERVICES_PORT" -> "0"))
      .fold(m => throw new IllegalStateException(m), identity)
      .copy(syncDeadlineMillis = deadlineMillis)

  lazy val base: ServiceRuntime =
    Composition
      .local(settings(), Files.createTempDirectory("scholarchain-services-test-audit"))
      .fold(m => throw new IllegalStateException(m), identity)

  /** The local runtime with some ports replaced (fake failing or slow ports). */
  def withPorts(
      kv: KeyValueStore = base.kv,
      graph: GraphStore = base.graph,
      vectors: VectorStore = base.vectors,
      needs: Set[String] = Set("key_value", "graph", "vectors"),
  ): ServiceRuntime =
    new ServiceRuntime(
      base.settings,
      base.tables,
      kv,
      graph,
      vectors,
      base.objects,
      base.embedder,
      base.model,
      base.mode,
      needs,
    )

  final case class Reply(status: Int, body: String, headers: Map[String, String]):
    def json: ujson.Value   = ujson.read(body)
    def traceHeader: String = headers.getOrElse("x-trace-id", "")

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  def call(
      port: Int,
      method: String,
      path: String,
      body: Option[String] = None,
      headers: Map[String, String] = Map.empty,
  ): Reply =
    val b = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
      .timeout(Duration.ofSeconds(90))
      .header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val req = method match
      case "POST" => b.POST(HttpRequest.BodyPublishers.ofString(body.getOrElse(""))).build()
      case _      => b.GET().build()
    val r  = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    val hs = scala.jdk.CollectionConverters
      .MapHasAsScala(r.headers().map())
      .asScala
      .map((k, v) => k.toLowerCase -> v.get(0))
      .toMap
    Reply(r.statusCode, r.body, hs)

  /** A running query-api on an ephemeral loopback port with a captured log. */
  final class Server(
      rt: ServiceRuntime,
      deadlineMillis: Long = 60000L,
      key: Option[String] = Some(PublicKey),
  ):
    private val sink = scala.collection.mutable.ListBuffer.empty[String]
    val log          = new JsonLog("query-api", l => sink.synchronized { sink += l; () })
    val ids          = new IdGen(true)
    private val s    = settings(deadlineMillis)
    private val svc  = new QueryService(rt, s, ids, log, () => FixedNow)
    private val app  = new HttpApp(
      "query-api",
      "127.0.0.1",
      0,
      log,
      ids,
      RestLimits.MaxBodyBytes,
      new QueryApiRoutes(rt, svc, new ApiKeyAuth(key)).handle,
    )
    val port: Int = app.start()

    def logs: List[String] = sink.synchronized(sink.toList)
    def stop(): Unit       = app.stop()

    def get(path: String, auth: Boolean = true, extra: Map[String, String] = Map.empty): Reply =
      call(port, "GET", path, None, hdrs(auth, extra))

    def post(
        path: String,
        body: String,
        auth: Boolean = true,
        idem: Option[String] = None,
        extra: Map[String, String] = Map.empty,
    ): Reply =
      call(
        port,
        "POST",
        path,
        Some(body),
        hdrs(auth, extra) ++ idem.map("Idempotency-Key" -> _),
      )

    private def hdrs(auth: Boolean, extra: Map[String, String]) =
      (if auth then Map("X-API-Key" -> PublicKey) else Map.empty[String, String]) ++ extra

  def query(q: String): String = ujson.write(ujson.Obj("question" -> q))

  val Q1 = "What is the largest team size in the dataset?"
  val Q2 = "What is the biggest team size?"

  def awaitJob(s: Server, jobId: String): ujson.Value =
    var last = s.get(s"/v1/jobs/${RestPaths.encode(jobId)}").json
    var n    = 0
    while !Set("SUCCEEDED", "FAILED").contains(last("state").str) && n < 600 do
      Thread.sleep(50)
      last = s.get(s"/v1/jobs/${RestPaths.encode(jobId)}").json
      n += 1
    last

  // ---- fake ports --------------------------------------------------------------------------

  final class FailingKv extends KeyValueStore:
    def put(t: String, i: KvItem, c: PutCondition): StorageResult[PutOutcome] =
      Left(StorageError.Failed("fake-failure"))
    def get(t: String, k: KvKey): StorageResult[Option[KvItem]] =
      Left(StorageError.Failed("fake-failure"))
    def query(
        t: String,
        p: String,
        s: Option[SortCondition],
        l: Int,
        a: Boolean,
        c: Option[String],
    ): StorageResult[KvPage] = Left(StorageError.Failed("fake-failure"))

  final class SlowKv(inner: KeyValueStore, millis: Long) extends KeyValueStore:
    private def nap(): Unit                                                   = Thread.sleep(millis)
    def put(t: String, i: KvItem, c: PutCondition): StorageResult[PutOutcome] =
      nap(); inner.put(t, i, c)
    def get(t: String, k: KvKey): StorageResult[Option[KvItem]] = { nap(); inner.get(t, k) }
    def query(
        t: String,
        p: String,
        s: Option[SortCondition],
        l: Int,
        a: Boolean,
        c: Option[String],
    ): StorageResult[KvPage] = { nap(); inner.query(t, p, s, l, a, c) }

  final class FailingGraph(inner: GraphStore) extends GraphStore:
    def query(q: GraphQuery): StorageResult[GraphResult] = Left(StorageError.Failed("fake-failure"))
    def submitBulkLoad(r: BulkLoadRequest): StorageResult[BulkLoadHandle] =
      inner.submitBulkLoad(r)
    def bulkLoadStatus(h: BulkLoadHandle): StorageResult[BulkLoadStatus] =
      inner.bulkLoadStatus(h)

  def guardKv(kv: KeyValueStore, timeoutMillis: Long, breakerThreshold: Int): KeyValueStore =
    new GuardedKv(
      kv,
      new DownstreamGuard("test-kv", timeoutMillis, 1, new CircuitBreaker(breakerThreshold, 60000L)),
    )
