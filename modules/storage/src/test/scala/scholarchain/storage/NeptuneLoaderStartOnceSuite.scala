package scholarchain.storage

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import scholarchain.core.*

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  GetLoaderJobStatusResponse,
  StartLoaderJobResponse,
}

/** The Neptune bulk-load START call is attempted exactly once (a retry could queue a duplicate
  * job); every other Neptune call keeps the SDK default retries. Localhost only, dummy credentials.
  */
class NeptuneLoaderStartOnceSuite extends munit.FunSuite:

  // the SDK default retry of a 500 takes about 14 s per call (observed): allow it
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "s")

  private val dummy =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIADUMMY", "dummy"))

  private def storageCfg: StorageModuleConfig =
    StorageConfigLoader.load(Map.empty, Map.empty).fold(e => fail(e.toString), _.config)

  private def loaderRequest: NeptuneLoaderRequest =
    val n = storageCfg.neptune
    NeptuneBulkLoad
      .buildRequest(n, BulkLoadRequest(n.sourceS3Uri + "run1/", "opencypher", "run1"), "us-east-1")
      .fold(e => fail(e.toString), identity)

  private def bulkRequest: BulkLoadRequest =
    val n = storageCfg.neptune
    BulkLoadRequest(n.sourceS3Uri + "run1/", "opencypher", "run1")

  /** Localhost server counting every request and always answering 500 with a JSON error body. */
  private final class Always500:
    val count  = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      (ex: HttpExchange) =>
        count.incrementAndGet()
        ex.getRequestBody.readAllBytes()
        val body = """{"code":"InternalFailureException","detailedMessage":"boom"}""".getBytes(
          StandardCharsets.UTF_8
        )
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(500, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close(),
    )
    server.start()
    def uri: URI     = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}")
    def stop(): Unit = server.stop(0)

  private def withServer[A](f: Always500 => A): A =
    val s = Always500()
    try f(s)
    finally s.stop()

  // ---- 1. configuration --------------------------------------------------------------------------

  test("loaderStartOverride: no retries (one attempt), 120 s attempt and call timeouts") {
    val cfg = AwsClients.loaderStartOverride
    assertEquals(cfg.retryStrategy().toScala.map(_.maxAttempts()), Some(1))
    assertEquals(cfg.apiCallAttemptTimeout().toScala, Some(Duration.ofSeconds(120)))
    assertEquals(cfg.apiCallTimeout().toScala, Some(Duration.ofSeconds(120)))
    assertEquals(AwsClients.LoaderStartTimeout, Duration.ofSeconds(120))
  }

  test("loaderStartOverride keeps the hermetic profile settings") {
    assertEquals(
      AwsClients.loaderStartOverride.defaultProfileName().toScala,
      Some(AwsClients.HermeticProfileName),
    )
  }

  test("the start socket timeout is above the start call timeout") {
    assert(
      AwsClients.LoaderStartSocketTimeout.compareTo(AwsClients.LoaderStartTimeout) > 0,
      s"${AwsClients.LoaderStartSocketTimeout} must exceed ${AwsClients.LoaderStartTimeout}",
    )
    assertEquals(AwsClients.LoaderStartSocketTimeout, Duration.ofSeconds(125))
  }

  test("AwsClients.hermetic is unchanged: profile name, empty profile file, no retry strategy") {
    val h = AwsClients.hermetic
    assertEquals(h.defaultProfileName().toScala, Some("scholarchain-hermetic"))
    assertEquals(h.retryStrategy().toScala, None)
    assertEquals(h.apiCallTimeout().toScala, None)
    assertEquals(h.apiCallAttemptTimeout().toScala, None)
    assertEquals(
      h.defaultProfileFile().toScala.exists(_.profile("scholarchain-hermetic").isPresent),
      false,
    )
  }

  // ---- 2. attempt count over a real localhost HTTP 500 --------------------------------------------

  test("start through neptuneLoaderStart: exactly one request, Left(Unavailable)") {
    withServer { s =>
      val c = AwsClients(dummy, Region.US_EAST_1, Some(s.uri))
      val l = NeptuneAws.loader(
        c.neptuneData.fold(e => fail(e.toString), identity),
        c.neptuneLoaderStart.fold(e => fail(e.toString), identity),
      )
      val r = l.start(loaderRequest)
      r match
        case Left(StorageError.Unavailable(_)) => ()
        case other                             => fail(s"expected Left(Unavailable), got $other")
      assertEquals(s.count.get, 1)
    }
  }

  test("contrast: start via the default client is retried (more than one request)") {
    withServer { s =>
      val c       = AwsClients(dummy, Region.US_EAST_1, Some(s.uri))
      val default = c.neptuneData.fold(e => fail(e.toString), identity)
      assert(NeptuneAws.loader(default).start(loaderRequest).isLeft)
      assert(s.count.get > 1, s"default client should retry a 500, saw ${s.count.get} request(s)")
    }
  }

  // ---- 3. wiring ---------------------------------------------------------------------------------

  private final class Stub(startReply: Boolean):
    val calls                     = ListBuffer.empty[String]
    val client: NeptunedataClient = Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array[Class[?]](classOf[NeptunedataClient]),
        new InvocationHandler:
          def invoke(p: Object, m: Method, args: Array[Object]): Object =
            m.getName match
              case "startLoaderJob" =>
                calls.synchronized(calls += "start")
                StartLoaderJobResponse
                  .builder()
                  .payload(Map("loadId" -> "load-xyz").asJava)
                  .build()
              case "getLoaderJobStatus" =>
                calls.synchronized(calls += "status")
                val overall = Document.fromMap(
                  Map(
                    "status"       -> Document.fromString("LOAD_COMPLETED"),
                    "totalRecords" -> Document.fromNumber(7),
                  ).asJava
                )
                GetLoaderJobStatusResponse
                  .builder()
                  .payload(Document.fromMap(Map("overallStatus" -> overall).asJava))
                  .build()
              case "close"    => null
              case "toString" => "stub"
              case other      => throw UnsupportedOperationException(other),
      )
      .asInstanceOf[NeptunedataClient]
    def seen: List[String] = calls.synchronized(calls.toList)

  test("two-argument loader: start uses only the start client, status only the main client") {
    val main  = Stub(true)
    val start = Stub(true)
    val l     = NeptuneAws.loader(main.client, start.client)
    assertEquals(l.start(loaderRequest), Right(BulkLoadHandle("load-xyz")))
    assertEquals(start.seen, List("start"))
    assertEquals(main.seen, Nil)
    assertEquals(l.status(BulkLoadHandle("load-xyz")), Right(BulkLoadStatus.Completed(Some(7L))))
    assertEquals(main.seen, List("status"))
    assertEquals(start.seen, List("start"))
  }

  test("one-argument loader still works: one client serves start and status") {
    val only = Stub(true)
    val l    = NeptuneAws.loader(only.client)
    assertEquals(l.start(loaderRequest), Right(BulkLoadHandle("load-xyz")))
    assertEquals(l.status(BulkLoadHandle("load-xyz")), Right(BulkLoadStatus.Completed(Some(7L))))
    assertEquals(only.seen, List("start", "status"))
  }

  test("graphStore(cfg) wiring: submitBulkLoad is one HTTP attempt, bulkLoadStatus is retried") {
    withServer { s =>
      val c  = AwsClients(dummy, Region.US_EAST_1, Some(s.uri))
      val gs = c.graphStore(storageCfg).fold(e => fail(e.toString), identity)
      gs.submitBulkLoad(bulkRequest) match
        case Left(StorageError.Unavailable(_)) => ()
        case other                             => fail(s"expected Left(Unavailable), got $other")
      assertEquals(s.count.get, 1)
      assert(gs.bulkLoadStatus(BulkLoadHandle("load-1")).isLeft)
      assert(s.count.get > 2, s"status must keep default retries, total requests ${s.count.get}")
    }
  }

  test("LiveLoad.awsPorts (the ops load path): loader.start is exactly one HTTP attempt") {
    withServer { s =>
      val c = AwsClients(dummy, Region.US_EAST_1, Some(s.uri))
      val r = LiveLoad.awsPorts(c).map(_.loader.start(loaderRequest))
      r match
        case Right(Left(StorageError.Unavailable(_))) => ()
        case other => fail(s"expected Right(Left(Unavailable)), got $other")
      assertEquals(s.count.get, 1)
    }
  }

  // ---- 4. no endpoint ----------------------------------------------------------------------------

  test("neptuneLoaderStart without an endpoint is Left(InvalidRequest), like neptuneData") {
    val c = AwsClients(dummy, Region.US_EAST_1, None)
    assertEquals(c.neptuneLoaderStart.left.map(_.code), Left("invalid-request"))
    c.neptuneLoaderStart match
      case Left(StorageError.InvalidRequest(_)) => ()
      case other                                => fail(s"expected InvalidRequest, got $other")
    assert(c.graphStore(storageCfg).isLeft)
  }
