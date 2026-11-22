package scholarchain.rag

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

import scala.util.Try
import scala.util.control.NonFatal

import scholarchain.core.*

/** A reply from the transport. The body is parsed by the adapter and never put in an error. */
final case class HttpReply(status: Int, body: String)

/** Injectable HTTP transport for the Voyage adapter. `Left(kind)` is a transport failure with a
  * fixed short kind (`timeout`, `io`, `interrupted`, `error`); it must not carry an exception
  * message, a URL or a header.
  */
trait VoyageTransport:
  def post(url: String, headers: Map[String, String], body: String): Either[String, HttpReply]

/** Default transport on the JDK `java.net.http` client. Not constructed by tests that inject a
  * fake; no call is made until `post`.
  */
final class JdkVoyageTransport(
    connectTimeout: Duration = Duration.ofSeconds(10),
    requestTimeout: Duration = Duration.ofSeconds(60),
) extends VoyageTransport:
  private lazy val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

  def post(url: String, headers: Map[String, String], body: String): Either[String, HttpReply] =
    try
      val b = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
      headers.foreach((k, v) => b.header(k, v))
      val req = b.POST(HttpRequest.BodyPublishers.ofString(body)).build()
      val res = client.send(req, HttpResponse.BodyHandlers.ofString())
      Right(HttpReply(res.statusCode(), res.body()))
    catch
      case _: java.net.http.HttpTimeoutException => Left("timeout")
      case _: java.io.IOException                => Left("io")
      case _: InterruptedException               =>
        Thread.currentThread().interrupt()
        Left("interrupted")
      case NonFatal(_) => Left("error")

/** Voyage AI embedder, the approved fallback behind the `Embedder` port.
  *
  * SECRET HANDLING. The API key is read once, at construction, from the environment variable named
  * by the config (`SCHOLARCHAIN_VOYAGE_API_KEY` by default) through the injected lookup. It is held
  * in a private field, sent only as the `Authorization` header of the request handed to the
  * transport, and appears in no `toString`, no `EmbeddingError` reason and no exception message.
  * Errors carry only a fixed phrase and an HTTP status class (for example `http 429`).
  *
  * Identity (`modelId`, `modelVersion`, `dimension`) comes from the `embedding.model.*` settings
  * when Voyage is the chosen provider.
  */
final class VoyageEmbedder private (
    val modelId: String,
    val modelVersion: String,
    val dimension: Int,
    val maxBatchSize: Int,
    inputType: String,
    endpoint: String,
    apiKey: String,
    transport: VoyageTransport,
) extends Embedder:

  def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]] =
    if texts.isEmpty then Left(EmbeddingError.InvalidRequest("empty batch"))
    else if texts.size > maxBatchSize then
      Left(EmbeddingError.InvalidRequest(s"batch larger than $maxBatchSize"))
    else if texts.exists(_.isBlank) then Left(EmbeddingError.InvalidRequest("blank text"))
    else
      val body = ujson
        .Obj(
          "input"      -> ujson.Arr.from(texts.map(ujson.Str(_))),
          "model"      -> modelId,
          "input_type" -> inputType,
        )
        .render()
      val headers = Map(
        "Authorization" -> s"Bearer $apiKey",
        "Content-Type"  -> "application/json",
        "Accept"        -> "application/json",
      )
      transport.post(endpoint, headers, body) match
        case Left(kind) => Left(EmbeddingError.Unavailable(s"transport $kind"))
        case Right(rep) => interpret(rep, texts.size)

  private def interpret(rep: HttpReply, n: Int): EmbeddingResult[List[Vector[Float]]] =
    if rep.status == 429 then Left(EmbeddingError.Throttled("http 429"))
    else if rep.status == 408 || rep.status >= 500 then
      Left(EmbeddingError.Unavailable(s"http ${rep.status / 100}xx"))
    else if rep.status < 200 || rep.status >= 300 then
      Left(EmbeddingError.Failed(s"http ${rep.status / 100}xx"))
    else
      Try {
        val data = ujson.read(rep.body)("data").arr.toList
        data.sortBy(_("index").num.toInt).map(_("embedding").arr.map(_.num.toFloat).toVector)
      }.toOption match
        case None                     => Left(EmbeddingError.Failed("malformed response"))
        case Some(vs) if vs.size != n =>
          Left(EmbeddingError.Failed("vector count differs from input count"))
        case Some(vs) =>
          vs.find(_.length != dimension) match
            case Some(bad) => Left(EmbeddingError.DimensionMismatch(dimension, bad.length))
            case None      => Right(vs)

  override def toString: String =
    s"VoyageEmbedder(modelId=$modelId, modelVersion=$modelVersion, dimension=$dimension, apiKey=<redacted>)"

object VoyageEmbedder:
  val DefaultEndpoint: String  = "https://api.voyageai.com/v1/embeddings"
  val DefaultMaxBatchSize: Int = 64
  val DefaultInputType: String = "document"
  val QueryInputType: String   = "query"

  /** Trims only ASCII blanks (space, tab, CR, LF) at both ends, so a key that ends in any other
    * control character is left intact and then rejected by the control-character check. (Plain
    * `String.trim` would strip every character up to U+0020 and hide such a key.)
    */
  private def trimBlanks(s: String): String =
    val blank = (c: Char) => c == ' ' || c == '\t' || c == '\r' || c == '\n'
    s.dropWhile(blank).reverse.dropWhile(blank).reverse

  /** Builds the embedder, reading the key from `env(apiKeyEnvVar)` once. Left (code
    * `failed`/`invalid-request`) when the variable is unset, blank or holds a control character;
    * the reason names the variable, never a value.
    */
  def create(
      modelId: String,
      modelVersion: String,
      dimension: Int,
      apiKeyEnvVar: String,
      env: String => Option[String] = name => sys.env.get(name),
      transport: VoyageTransport = new JdkVoyageTransport(),
      endpoint: String = DefaultEndpoint,
      inputType: String = DefaultInputType,
      maxBatchSize: Int = DefaultMaxBatchSize,
  ): Either[EmbeddingError, VoyageEmbedder] =
    if dimension < 1 || dimension > VectorRecord.MaxDimension then
      Left(EmbeddingError.InvalidRequest("dimension is out of range"))
    else if !endpoint.startsWith("https://") then
      Left(EmbeddingError.InvalidRequest("endpoint must be https"))
    else if inputType != DefaultInputType && inputType != QueryInputType then
      Left(EmbeddingError.InvalidRequest("input type must be document or query"))
    else
      env(apiKeyEnvVar).map(trimBlanks).filter(_.nonEmpty) match
        case None => Left(EmbeddingError.Failed(s"environment variable $apiKeyEnvVar is not set"))
        case Some(key) =>
          if key.exists(c => c.isControl || c.isWhitespace) then
            Left(EmbeddingError.Failed(s"environment variable $apiKeyEnvVar is malformed"))
          else
            Right(
              new VoyageEmbedder(
                modelId,
                modelVersion,
                dimension,
                maxBatchSize,
                inputType,
                endpoint,
                key,
                transport,
              )
            )

  def fromSettings(
      s: EmbeddingSettings,
      env: String => Option[String] = name => sys.env.get(name),
      transport: VoyageTransport = new JdkVoyageTransport(),
  ): Either[EmbeddingError, VoyageEmbedder] =
    create(s.modelId, s.modelVersion, s.dimension, s.voyageApiKeyEnvVar, env, transport)
