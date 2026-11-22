package scholarchain.rag

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Try
import scala.util.control.NonFatal

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import scholarchain.core.*

/** Calls one model with one JSON body. The seam between the Titan adapter and the SDK. */
trait BedrockInvoker:
  def invoke(modelId: String, body: String): Either[EmbeddingError, String]

/** The AWS SDK invoker. The `BedrockRuntimeClient` is created lazily, on the first `invoke`, and
  * only here; constructing this object makes no client and no call. Every client construction
  * increments `SdkBedrockInvoker.clientsConstructed`, so a test can assert it is 0 when
  * `SCHOLARCHAIN_AWS_IT` is unset.
  *
  * HERMETIC (as storage `AwsClients`): the credentials provider is injected, with no default; no
  * code here uses the default credential chain, so no shared AWS credentials file, `AWS_PROFILE` or
  * instance metadata is consulted. The client override configuration supplies an EMPTY profile file
  * and a fixed profile name, so no shared AWS config or credentials file is read for client
  * defaults. The provider is invoked only when a request is signed.
  *
  * Nothing in this phase calls it: it is created only by `EmbedderFactory.bedrock` on an explicit
  * live profile.
  */
final class SdkBedrockInvoker(region: String, credentials: AwsCredentialsProvider)
    extends BedrockInvoker:
  private lazy val client: BedrockRuntimeClient =
    SdkBedrockInvoker.constructed.incrementAndGet()
    BedrockRuntimeClient
      .builder()
      .region(Region.of(region))
      .credentialsProvider(credentials)
      .overrideConfiguration(SdkBedrockInvoker.hermetic)
      .httpClientBuilder(UrlConnectionHttpClient.builder())
      .build()

  def invoke(modelId: String, body: String): Either[EmbeddingError, String] =
    try
      val req = InvokeModelRequest
        .builder()
        .modelId(modelId)
        .contentType("application/json")
        .accept("application/json")
        .body(SdkBytes.fromUtf8String(body))
        .build()
      Right(client.invokeModel(req).body().asUtf8String())
    catch
      case e: software.amazon.awssdk.awscore.exception.AwsServiceException =>
        val code = e.statusCode()
        if code == 429 || e.isThrottlingException then Left(EmbeddingError.Throttled("http 429"))
        else if code == 408 || code >= 500 then
          Left(EmbeddingError.Unavailable(s"http ${code / 100}xx"))
        else Left(EmbeddingError.Failed(s"http ${code / 100}xx"))
      case _: SdkClientException => Left(EmbeddingError.Unavailable("client or network error"))
      case NonFatal(_)           => Left(EmbeddingError.Failed("unexpected error"))

object SdkBedrockInvoker:
  private val constructed = new AtomicInteger(0)

  val HermeticProfileName = "scholarchain-hermetic"

  private def emptyProfileFile(): ProfileFile =
    ProfileFile
      .builder()
      .content(new ByteArrayInputStream(Array.emptyByteArray))
      .`type`(ProfileFile.Type.CONFIGURATION)
      .build()

  /** Client override configuration used by the Bedrock client: empty profile file, fixed profile
    * name. Building it makes no AWS call and reads no shared AWS file.
    */
  def hermetic: ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .defaultProfileFile(emptyProfileFile())
      .defaultProfileName(HermeticProfileName)
      .build()

  /** How many `BedrockRuntimeClient`s this JVM has built. 0 unless a live profile ran a call. */
  def clientsConstructed: Int = constructed.get()

/** Amazon Titan Text Embeddings V2 behind the `Embedder` port (the default embedder).
  *
  * Model ID, version and dimension come from `embedding.model.*` (default
  * `amazon.titan-embed-text-v2:0`, `v2`, 1024). Titan V2 produces 256, 512 or 1024 dimensions; the
  * request sets `dimensions` to the configured value and `normalize` to true. One text per model
  * call (Titan V2 takes a single `inputText`), at most `maxBatchSize` texts per `embed`, each at
  * most 50,000 characters. Errors carry only a fixed phrase and an HTTP status class.
  */
final class BedrockEmbedder(
    invoker: BedrockInvoker,
    val modelId: String,
    val modelVersion: String,
    val dimension: Int,
    val maxBatchSize: Int = BedrockEmbedder.DefaultMaxBatchSize,
) extends Embedder:

  def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]] =
    if !BedrockEmbedder.TitanDimensions.contains(dimension) then
      Left(EmbeddingError.InvalidRequest("Titan V2 dimension must be 256, 512 or 1024"))
    else if texts.isEmpty then Left(EmbeddingError.InvalidRequest("empty batch"))
    else if texts.size > maxBatchSize then
      Left(EmbeddingError.InvalidRequest(s"batch larger than $maxBatchSize"))
    else if texts.exists(t => t.isBlank || t.length > BedrockEmbedder.MaxInputChars) then
      Left(EmbeddingError.InvalidRequest("blank text or text above 50000 characters"))
    else
      texts
        .foldLeft[EmbeddingResult[Vector[Vector[Float]]]](Right(Vector.empty)) { (acc, t) =>
          acc.flatMap(done => one(t).map(done :+ _))
        }
        .map(_.toList)

  private def one(text: String): EmbeddingResult[Vector[Float]] =
    val body = ujson
      .Obj("inputText" -> text, "dimensions" -> dimension, "normalize" -> true)
      .render()
    invoker.invoke(modelId, body).flatMap { reply =>
      Try(ujson.read(reply)("embedding").arr.map(_.num.toFloat).toVector).toOption match
        case None                             => Left(EmbeddingError.Failed("malformed response"))
        case Some(v) if v.length != dimension =>
          Left(EmbeddingError.DimensionMismatch(dimension, v.length))
        case Some(v) => Right(v)
    }

object BedrockEmbedder:
  val DefaultMaxBatchSize: Int  = 16
  val MaxInputChars: Int        = 50000
  val TitanDimensions: Set[Int] = Set(256, 512, 1024)

  /** Titan embedder from the validated settings, over a given invoker. */
  def fromSettings(s: EmbeddingSettings, invoker: BedrockInvoker): BedrockEmbedder =
    new BedrockEmbedder(invoker, s.modelId, s.modelVersion, s.dimension)
