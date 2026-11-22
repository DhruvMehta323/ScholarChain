package scholarchain.rag

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import scholarchain.core.*

/** Which embedder a run uses. The default for production is `Bedrock`; `LocalTest` is for tests and
  * local runs; `Voyage` is the approved fallback.
  */
enum EmbedderChoice:
  case Bedrock, Voyage, LocalTest

/** Builds embedders. Every result is wrapped in `ValidatingEmbedder`.
  *
  * AWS OFF BY DEFAULT: `bedrock` returns `Left` unless `liveProfile` is true, and even then the SDK
  * client is created only on the first `embed` call. Pass `liveProfile = true` only from a live
  * profile (`SCHOLARCHAIN_AWS_IT=1` suites or the real service). Tests and local runs use
  * `LocalTest` and never reach the SDK (`SdkBedrockInvoker.clientsConstructed` stays 0).
  */
object EmbedderFactory:
  def bedrock(
      s: EmbeddingSettings,
      region: String,
      liveProfile: Boolean,
      credentials: AwsCredentialsProvider,
      invokerFor: (String, AwsCredentialsProvider) => BedrockInvoker = (r, c) =>
        new SdkBedrockInvoker(r, c),
  ): Either[EmbeddingError, Embedder] =
    if !liveProfile then Left(EmbeddingError.Failed("bedrock requires an explicit live profile"))
    else if region.isBlank then Left(EmbeddingError.InvalidRequest("region is blank"))
    else Right(ValidatingEmbedder(BedrockEmbedder.fromSettings(s, invokerFor(region, credentials))))

  def voyage(
      s: EmbeddingSettings,
      env: String => Option[String] = name => sys.env.get(name),
      transport: VoyageTransport = new JdkVoyageTransport(),
  ): Either[EmbeddingError, Embedder] =
    VoyageEmbedder.fromSettings(s, env, transport).map(ValidatingEmbedder(_))

  def localTest(dimension: Int = LocalHashEmbedder.DefaultDimension): Embedder =
    ValidatingEmbedder(new LocalHashEmbedder(dimension))
