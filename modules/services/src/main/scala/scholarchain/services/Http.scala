package scholarchain.services

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{
  Executor,
  LinkedBlockingQueue,
  RejectedExecutionException,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit,
}
import java.util.concurrent.atomic.AtomicLong

import scala.util.control.NonFatal

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import scholarchain.core.*
import scholarchain.core.rest.*

/** Source of request, trace, job and run identifiers. The deterministic form (a counter) is for the
  * local session so that two runs can be compared; production uses random UUIDs.
  */
final class IdGen(deterministic: Boolean):
  private val counter              = new AtomicLong(0L)
  def next(prefix: String): String =
    if deterministic then f"$prefix-${counter.incrementAndGet()}%06d"
    else s"$prefix-${UUID.randomUUID().toString}"

/** Structured JSON logger: one line per event. Fields are fixed short strings: never a body, a key
  * or a secret.
  */
final class JsonLog(service: String, sink: String => Unit = s => System.out.println(s)):
  def log(
      level: String,
      traceId: String,
      requestId: String,
      event: String,
      fields: (String, String)*
  ): Unit =
    val o = ujson.Obj(
      "ts"        -> ujson.Str(Instant.now().toString),
      "level"     -> ujson.Str(level),
      "service"   -> ujson.Str(service),
      "event"     -> ujson.Str(event),
      "traceId"   -> ujson.Str(traceId),
      "requestId" -> ujson.Str(requestId),
    )
    fields.foreach((k, v) => o(k) = ujson.Str(v.take(200)))
    sink(ujson.write(o))

/** A request as the routes see it. Header names are lower case. */
final case class Req(
    method: String,
    path: String,
    segments: List[String],
    query: Map[String, List[String]],
    headers: Map[String, String],
    body: String,
    traceId: String,
    requestId: String,
):
  def header(name: String): Option[String] = headers.get(name.toLowerCase)

final case class Resp(status: Int, body: String, headers: Map[String, String] = Map.empty)

/** A typed failure that the HTTP layer turns into an `ApiError` body with the request's trace id.
  */
final case class ApiFailure(code: ApiErrorCode, message: String):
  def toResp(traceId: String): Resp =
    val body = RestJson
      .write(ApiError.of(code, message, traceId))
      .getOrElse(
        ujson.write(
          ujson.Obj(
            "error"     -> ujson.Str(code.wire),
            "message"   -> ujson.Str("error"),
            "traceId"   -> ujson.Str(traceId),
            "retryable" -> ujson.Bool(code.retryable),
          )
        )
      )
    Resp(code.httpStatus, body, Map(RestHeaders.Contract -> RestContracts.Error))

object ApiFailure:
  def from(r: RestRejection): ApiFailure = ApiFailure(r.code, r.message)

object Respond:
  /** Validates and serializes a view; an invalid view is an internal error, never sent. */
  def json[A](status: Int, a: A, contract: String)(using
      c: Contract[A],
      w: WirePickle.Writer[A],
  ): Either[ApiFailure, Resp] =
    RestJson
      .write(a)
      .left
      .map(_ => ApiFailure(ApiErrorCode.Internal, "the response could not be built"))
      .map(b => Resp(status, b, Map(RestHeaders.Contract -> contract)))

  def raw(status: Int, body: String, contract: String): Resp =
    Resp(status, body, Map(RestHeaders.Contract -> contract))

/** API key check by constant-time comparison of SHA-256 digests (so the length does not leak
  * either). `expected = None` accepts every caller: local mode only.
  */
final class ApiKeyAuth(expected: Option[String]):
  private def digest(s: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))
  private val expectedDigest: Option[Array[Byte]] = expected.map(digest)

  def required: Boolean = expected.isDefined

  def accepts(presented: Option[String]): Boolean = expectedDigest match
    case None    => true
    case Some(e) => MessageDigest.isEqual(e, digest(presented.getOrElse("")))

  /** A stable identity of the caller for idempotency scoping: a hash, never the key. */
  def scopeOf(presented: Option[String]): String =
    if expected.isEmpty then "local"
    else Hashing.digest("scholarchain.services.caller.v1", List(presented.getOrElse("")))

/** Minimal server on the JDK's HTTP server. Bodies are bounded; every request has a trace id and a
  * request id (accepted when valid, otherwise generated) returned as headers.
  */
final class HttpApp(
    service: String,
    host: String,
    port: Int,
    log: JsonLog,
    ids: IdGen,
    maxBodyBytes: Int,
    handler: Req => Resp,
    threads: Int = 16,
    queueCapacity: Int = 64,
    healthThreads: Int = 2,
):
  private var server: HttpServer = null

  private def pool(n: Int, q: Int, name: String): ThreadPoolExecutor =
    new ThreadPoolExecutor(
      n,
      n,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable](math.max(1, q)),
      daemon(name),
      new ThreadPoolExecutor.AbortPolicy,
    )

  /** Health and readiness run on their own small pool, so they are never queued behind query work;
    * every other request runs on a bounded pool and is answered 429 when its queue is full.
    */
  private def dispatching(on: ThreadPoolExecutor, overflow: HttpExchange => Unit): HttpHandler =
    new HttpHandler:
      def handle(ex: HttpExchange): Unit =
        try on.execute(() => serve(ex))
        catch case _: RejectedExecutionException => overflow(ex)

  def start(): Int =
    val s = HttpServer.create(new InetSocketAddress(host, port), 0)
    s.setExecutor(new Executor:
      def execute(r: Runnable): Unit = r.run())
    val main   = pool(threads, queueCapacity, s"$service-http")
    val health = pool(healthThreads, 8, s"$service-health")
    s.createContext("/", dispatching(main, overloaded))
    List("/healthz", "/health", "/readyz").foreach(c =>
      s.createContext(c, dispatching(health, overloaded))
    )
    s.start()
    server = s
    s.getAddress.getPort

  def stop(): Unit = if server != null then server.stop(0)

  private def daemon(name: String): ThreadFactory =
    val n = new AtomicLong(0)
    (r: Runnable) =>
      val t = new Thread(r, s"$name-${n.incrementAndGet()}")
      t.setDaemon(true)
      t

  private def overloaded(ex: HttpExchange): Unit =
    val traceId = ids.next("trace")
    val resp    =
      ApiFailure(ApiErrorCode.RateLimited, "the service is busy; retry later").toResp(traceId)
    val bytes = resp.body.getBytes(StandardCharsets.UTF_8)
    try
      ex.getRequestBody.close()
      val out = ex.getResponseHeaders
      out.set("Content-Type", "application/json")
      out.set("Cache-Control", "no-store")
      out.set(RestHeaders.TraceId, traceId)
      resp.headers.foreach((k, v) => out.set(k, v))
      ex.sendResponseHeaders(resp.status, bytes.length.toLong)
      ex.getResponseBody.write(bytes)
    catch case NonFatal(_) => ()
    finally ex.close()
    log.log("warn", traceId, traceId, "request.rejected", "status" -> resp.status.toString)

  private def parseQuery(raw: String | Null): Option[Map[String, List[String]]] =
    if raw == null || raw.isEmpty then Some(Map.empty)
    else
      try
        val pairs = raw.split("&", -1).toList.filter(_.nonEmpty).map { kv =>
          val i      = kv.indexOf('=')
          val (k, v) = if i < 0 then (kv, "") else (kv.substring(0, i), kv.substring(i + 1))
          URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(
            v,
            StandardCharsets.UTF_8,
          )
        }
        Some(pairs.groupBy(_._1).map((k, vs) => k -> vs.map(_._2)))
      catch case NonFatal(_) => None

  private def serve(ex: HttpExchange): Unit =
    val t0   = System.nanoTime()
    val hdrs = scala.collection.mutable.Map.empty[String, String]
    ex.getRequestHeaders.forEach((k, vs) => if !vs.isEmpty then hdrs(k.toLowerCase) = vs.get(0))
    val traceId   = TraceIds.accept(hdrs.get("x-trace-id")).getOrElse(ids.next("trace"))
    val requestId = TraceIds.accept(hdrs.get("x-request-id")).getOrElse(ids.next("req"))
    val method    = ex.getRequestMethod
    val rawPath   = ex.getRequestURI.getRawPath
    val resp      =
      try
        val body = ex.getRequestBody.readNBytes(maxBodyBytes + 1)
        if body.length > maxBodyBytes then
          ApiFailure(ApiErrorCode.MalformedRequest, "body: is too large").toResp(traceId)
        else
          val segs = rawPath.split("/", -1).toList.drop(1).filter(_.nonEmpty).map(RestPaths.decode)
          (segs.forall(_.isDefined), parseQuery(ex.getRequestURI.getRawQuery)) match
            case (true, Some(q)) =>
              handler(
                Req(
                  method,
                  rawPath,
                  segs.flatten,
                  q,
                  hdrs.toMap,
                  new String(body, StandardCharsets.UTF_8),
                  traceId,
                  requestId,
                )
              )
            case _ =>
              ApiFailure(ApiErrorCode.MalformedRequest, "path: is not a valid request path")
                .toResp(traceId)
      catch
        case NonFatal(e) =>
          log.log(
            "error",
            traceId,
            requestId,
            "unhandled",
            "exception" -> e.getClass.getSimpleName,
          )
          ApiFailure(ApiErrorCode.Internal, "unexpected internal failure").toResp(traceId)
    val bytes = resp.body.getBytes(StandardCharsets.UTF_8)
    val out   = ex.getResponseHeaders
    out.set("Content-Type", "application/json")
    out.set("Cache-Control", "no-store")
    out.set(RestHeaders.TraceId, traceId)
    out.set(RestHeaders.RequestId, requestId)
    resp.headers.foreach((k, v) => out.set(k, v))
    ex.sendResponseHeaders(resp.status, if bytes.isEmpty then -1L else bytes.length.toLong)
    if bytes.nonEmpty then
      val os = ex.getResponseBody
      os.write(bytes)
      os.close()
    ex.close()
    log.log(
      "info",
      traceId,
      requestId,
      "request",
      "method"    -> method,
      "path"      -> rawPath.take(120),
      "status"    -> resp.status.toString,
      "latencyMs" -> ((System.nanoTime() - t0) / 1000000L).toString,
    )
