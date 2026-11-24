package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.core.rest.*

/** JSON codecs of the internal retrieval protocol (graph, vector and embedding reads). Tagged
  * values keep `Long` and `Double` apart. Used by both the retrieval server and its HTTP clients.
  */
object RetrievalCodec:
  val Version: String = "v1"

  def gv(v: GraphValue): ujson.Value = v match
    case GraphValue.Str(s)  => ujson.Obj("t" -> "s", "v" -> s)
    case GraphValue.Num(n)  => ujson.Obj("t" -> "n", "v" -> n.toString)
    case GraphValue.Dbl(d)  => ujson.Obj("t" -> "d", "v" -> d.toString)
    case GraphValue.Bool(b) => ujson.Obj("t" -> "b", "v" -> b)
    case GraphValue.Null    => ujson.Obj("t" -> "z")
    case GraphValue.Lst(l)  => ujson.Obj("t" -> "l", "v" -> ujson.Arr.from(l.map(gv)))
    case GraphValue.Obj(o)  =>
      ujson.Obj("t" -> "o", "v" -> ujson.Obj.from(o.toList.map((k, x) => k -> gv(x))))

  def gvFrom(j: ujson.Value): GraphValue = j("t").str match
    case "s" => GraphValue.Str(j("v").str)
    case "n" => GraphValue.Num(j("v").str.toLong)
    case "d" => GraphValue.Dbl(j("v").str.toDouble)
    case "b" => GraphValue.Bool(j("v").bool)
    case "z" => GraphValue.Null
    case "l" => GraphValue.Lst(j("v").arr.toList.map(gvFrom))
    case "o" => GraphValue.Obj(j("v").obj.toList.map((k, x) => k -> gvFrom(x)).toMap)
    case _   => throw new IllegalArgumentException("graph value tag")

  def graphQuery(q: GraphQuery): ujson.Value = ujson.Obj(
    "template"   -> q.template,
    "parameters" -> ujson.Obj.from(q.parameters.toList.map((k, v) => k -> gv(v))),
    "bounds"     -> ujson.Obj(
      "maxDepth"      -> q.bounds.maxDepth,
      "maxResults"    -> q.bounds.maxResults,
      "timeoutMillis" -> q.bounds.timeoutMillis.toString,
    ),
  )

  def graphQueryFrom(j: ujson.Value): GraphQuery = GraphQuery(
    j("template").str,
    j("parameters").obj.toList.map((k, v) => k -> gvFrom(v)).toMap,
    QueryBounds(
      j("bounds")("maxDepth").num.toInt,
      j("bounds")("maxResults").num.toInt,
      j("bounds")("timeoutMillis").str.toLong,
    ),
  )

  def graphResult(r: GraphResult): ujson.Value = ujson.Obj(
    "rows" -> ujson.Arr.from(
      r.rows.map(row => ujson.Obj.from(row.columns.toList.map((k, v) => k -> gv(v))))
    ),
    "truncated" -> r.truncated,
  )

  def graphResultFrom(j: ujson.Value): GraphResult = GraphResult(
    j("rows").arr.toList.map(o => GraphRow(o.obj.toList.map((k, v) => k -> gvFrom(v)).toMap)),
    j("truncated").bool,
  )

  def filter(f: MetadataFilter): ujson.Value = f match
    case MetadataFilter.Equals(n, v) => ujson.Obj("op" -> "eq", "name" -> n, "value" -> v)
    case MetadataFilter.In(n, vs)    =>
      ujson.Obj("op" -> "in", "name" -> n, "values" -> ujson.Arr.from(vs.map(ujson.Str(_))))
    case MetadataFilter.And(fs) =>
      ujson.Obj("op" -> "and", "filters" -> ujson.Arr.from(fs.map(filter)))

  def filterFrom(j: ujson.Value): MetadataFilter = j("op").str match
    case "eq"  => MetadataFilter.Equals(j("name").str, j("value").str)
    case "in"  => MetadataFilter.In(j("name").str, j("values").arr.toList.map(_.str))
    case "and" => MetadataFilter.And(j("filters").arr.toList.map(filterFrom))
    case _     => throw new IllegalArgumentException("filter op")

  def floats(v: Vector[Float]): ujson.Value     = ujson.Arr.from(v.map(f => ujson.Num(f.toDouble)))
  def floatsFrom(j: ujson.Value): Vector[Float] = j.arr.toVector.map(_.num.toFloat)

  def vectorQuery(index: String, q: VectorQuery): ujson.Value =
    val o = ujson.Obj("index" -> index, "embedding" -> floats(q.embedding), "topK" -> q.topK)
    q.filter.foreach(f => o("filter") = filter(f))
    o

  def vectorQueryFrom(j: ujson.Value): (String, VectorQuery) =
    (
      j("index").str,
      VectorQuery(
        floatsFrom(j("embedding")),
        j("topK").num.toInt,
        j.obj.get("filter").map(filterFrom),
      ),
    )

  def hits(hs: List[VectorHit]): ujson.Value = ujson.Arr.from(
    hs.map(h =>
      ujson.Obj(
        "key"      -> h.vectorKey,
        "score"    -> h.score,
        "metadata" -> ujson.Obj.from(h.metadata.toList.map((k, v) => k -> ujson.Str(v))),
      )
    )
  )

  def hitsFrom(j: ujson.Value): List[VectorHit] = j.arr.toList.map(h =>
    VectorHit(
      h("key").str,
      h("score").num,
      h("metadata").obj.toList.map((k, v) => k -> v.str).toMap,
    )
  )

/** The retrieval service's handler: versioned internal routes for graph and vector reads, guarded
  * by `X-Internal-Api-Key`. No route here embeds retrieval logic: each one forwards to a core port.
  */
final class RetrievalRoutes(
    rt: ServiceRuntime,
    internalAuth: ApiKeyAuth,
    embedMaxTexts: Int = 16,
):
  import RetrievalCodec.*

  val HeaderName: String = "x-internal-api-key"

  def handle(req: Req): Resp =
    val result: Either[ApiFailure, Resp] = (req.method, req.segments) match
      case ("GET", List("healthz")) | ("GET", List("health")) =>
        Respond.json(200, HealthView.alive("retrieval", None), RestContracts.Health)
      case ("GET", List("readyz"))                 => Readiness.respond("retrieval", rt)
      case ("POST", List("internal", Version, op)) =>
        if !internalAuth.accepts(req.header(HeaderName)) then
          Left(ApiFailure(ApiErrorCode.Unauthenticated, "missing or invalid credentials"))
        else
          try internal(op, req.body)
          catch
            case NonFatal(_) =>
              Left(ApiFailure(ApiErrorCode.MalformedRequest, "body: is not a valid request"))
      case _ => Left(ApiFailure(ApiErrorCode.NotFound, "no such route"))
    result.fold(_.toResp(req.traceId), identity)

  /** A port that throws is a downstream failure (502), never a malformed request (400). */
  private def port[A](f: => StorageResult[A]): Either[ApiFailure, A] =
    try f.left.map(Downstream.toFailure)
    catch
      case NonFatal(_) =>
        Left(ApiFailure(ApiErrorCode.DownstreamFailure, "the downstream call failed"))

  private def internal(op: String, body: String): Either[ApiFailure, Resp] =
    val j = ujson.read(body)
    op match
      case "graph-query" =>
        val q = graphQueryFrom(j)
        if !GraphTemplates.all.contains(q.template) then
          Left(ApiFailure(ApiErrorCode.InvalidField, "template: is not allowlisted"))
        else
          port(rt.graph.query(q))
            .map(r => Respond.raw(200, ujson.write(graphResult(r)), "internal.graph-result/1"))
      case "vector-query" =>
        val (index, q) = vectorQueryFrom(j)
        port(rt.vectors.query(index, q))
          .map(h => Respond.raw(200, ujson.write(hits(h)), "internal.vector-hits/1"))
      case "vector-get" =>
        val (gi, gk) = (j("index").str, j("key").str)
        port(rt.vectors.get(gi, gk))
          .flatMap {
            case None => Right(Respond.raw(200, """{"found":false}""", "internal.vector-entry/1"))
            case Some(e) =>
              Wire.write(e.record) match
                case Left(_)  => Left(ApiFailure(ApiErrorCode.Internal, "record is not valid"))
                case Right(r) =>
                  Right(
                    Respond.raw(
                      200,
                      ujson.write(
                        ujson.Obj(
                          "found"     -> true,
                          "record"    -> ujson.read(r),
                          "embedding" -> floats(e.embedding),
                        )
                      ),
                      "internal.vector-entry/1",
                    )
                  )
          }
      case "embed" =>
        val texts = j("texts").arr.toList.map(_.str)
        if texts.isEmpty || texts.size > embedMaxTexts || texts.exists(_.length > 20000) then
          Left(ApiFailure(ApiErrorCode.InvalidField, "texts: is out of bounds"))
        else
          val embedded =
            try rt.embedder.embed(texts)
            catch case NonFatal(_) => Left(EmbeddingError.Failed("embedding failed"))
          embedded match
            case Right(vs) =>
              Right(
                Respond.raw(
                  200,
                  ujson.write(ujson.Obj("vectors" -> ujson.Arr.from(vs.map(floats)))),
                  "internal.embeddings/1",
                )
              )
            case Left(e) if e.retryable =>
              Left(ApiFailure(ApiErrorCode.Unavailable, "embedding is temporarily unavailable"))
            case Left(_) => Left(ApiFailure(ApiErrorCode.DownstreamFailure, "embedding failed"))
      case _ => Left(ApiFailure(ApiErrorCode.NotFound, "no such route"))

/** HTTP client for the retrieval service (query-api side). Never logs a body or the key. */
final class RetrievalClient(baseUrl: String, internalKey: String, timeoutMillis: Long):
  private val http =
    HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).build()

  def post(op: String, body: ujson.Value): Either[String, ujson.Value] =
    try
      val r = HttpRequest
        .newBuilder(
          URI.create(s"${baseUrl.stripSuffix("/")}/internal/${RetrievalCodec.Version}/$op")
        )
        .timeout(Duration.ofMillis(timeoutMillis))
        .header("Content-Type", "application/json")
        .header("X-Internal-Api-Key", internalKey)
        .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
        .build()
      val resp = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      if resp.statusCode == 200 then Right(ujson.read(resp.body))
      else if resp.statusCode == 504 then Left(Downstream.TimeoutReason)
      else if resp.statusCode == 503 then Left(Downstream.OpenReason)
      else if resp.statusCode == 400 || resp.statusCode == 422 then Left("rejected")
      else if resp.statusCode == 401 || resp.statusCode == 403 then Left("denied")
      else Left("failed")
    catch
      case _: java.net.http.HttpTimeoutException => Left(Downstream.TimeoutReason)
      case NonFatal(_)                           => Left("failed")

  def err(reason: String): StorageError =
    if reason == "rejected" then StorageError.InvalidRequest("retrieval rejected the request")
    else if reason == "denied" then StorageError.Failed("retrieval refused the credentials")
    else if reason == "failed" then StorageError.Unavailable("retrieval unreachable")
    else StorageError.Unavailable(reason)

final class RemoteGraphStore(c: RetrievalClient) extends GraphStore:
  def query(q: GraphQuery): StorageResult[GraphResult] =
    c.post("graph-query", RetrievalCodec.graphQuery(q)) match
      case Right(j) =>
        try Right(RetrievalCodec.graphResultFrom(j))
        catch case NonFatal(_) => Left(StorageError.Failed("bad retrieval reply"))
      case Left(why) => Left(c.err(why))
  def submitBulkLoad(request: BulkLoadRequest): StorageResult[BulkLoadHandle] =
    Left(StorageError.InvalidRequest("read-only"))
  def bulkLoadStatus(handle: BulkLoadHandle): StorageResult[BulkLoadStatus] =
    Left(StorageError.InvalidRequest("read-only"))

final class RemoteVectorStore(c: RetrievalClient) extends VectorStore:
  def putBatch(
      indexName: String,
      vectors: List[VectorEntry],
  ): StorageResult[List[VectorPutResult]] =
    Left(StorageError.InvalidRequest("read-only"))
  def query(indexName: String, q: VectorQuery): StorageResult[List[VectorHit]] =
    c.post("vector-query", RetrievalCodec.vectorQuery(indexName, q)) match
      case Right(j) =>
        try Right(RetrievalCodec.hitsFrom(j))
        catch case NonFatal(_) => Left(StorageError.Failed("bad retrieval reply"))
      case Left(why) => Left(c.err(why))
  def get(indexName: String, vectorKey: String): StorageResult[Option[VectorEntry]] =
    c.post("vector-get", ujson.Obj("index" -> indexName, "key" -> vectorKey)) match
      case Right(j) =>
        try
          if !j("found").bool then Right(None)
          else
            Wire
              .read[VectorRecord](ujson.write(j("record")))
              .left
              .map(_ => StorageError.Failed("bad retrieval reply"))
              .map(r => Some(VectorEntry(r, RetrievalCodec.floatsFrom(j("embedding")))))
        catch case NonFatal(_) => Left(StorageError.Failed("bad retrieval reply"))
      case Left(why) => Left(c.err(why))

final class RemoteEmbedder(
    c: RetrievalClient,
    val modelId: String,
    val modelVersion: String,
    val dimension: Int,
    val maxBatchSize: Int = 16,
) extends Embedder:
  def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]] =
    if texts.isEmpty || texts.size > maxBatchSize then
      Left(EmbeddingError.InvalidRequest("batch size out of bounds"))
    else
      c.post("embed", ujson.Obj("texts" -> ujson.Arr.from(texts.map(ujson.Str(_))))) match
        case Right(j) =>
          try
            val vs = j("vectors").arr.toList.map(RetrievalCodec.floatsFrom)
            if vs.size == texts.size && vs.forall(_.size == dimension) then Right(vs)
            else Left(EmbeddingError.Failed("bad retrieval reply"))
          catch case NonFatal(_) => Left(EmbeddingError.Failed("bad retrieval reply"))
        case Left(why) if why == "rejected" => Left(EmbeddingError.InvalidRequest("rejected"))
        case Left(why)                      =>
          // The embedder has no guard: record the failure so the run is not reported as a 200.
          Probe.current.foreach(p =>
            if why == Downstream.TimeoutReason then p.timeouts += 1
            else if why == Downstream.OpenReason then p.opens += 1
            else p.failures += 1
          )
          Left(EmbeddingError.Unavailable("retrieval unavailable"))
