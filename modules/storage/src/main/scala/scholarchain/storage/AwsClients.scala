package scholarchain.storage

import java.io.ByteArrayInputStream
import java.net.URI

import scholarchain.core.*

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.sqs.SqsClient

/** The only place AWS clients are built, and only on demand.
  *
  * AWS-off-by-default guarantees:
  *   - nothing in this module builds a client in an object initializer or a default code path;
  *   - an `AwsClients` instance holds the credentials provider and the region and builds each
  *     client lazily, the first time its accessor is used;
  *   - the credentials provider is INJECTED: no code here calls `DefaultCredentialsProvider`, reads
  *     environment variables, profile files or instance metadata. The caller (a live suite, the
  *     service wiring) decides which provider to pass, and it is invoked only when a request is
  *     signed;
  *   - the HTTP client is the pinned URL-connection client (no Apache or Netty);
  *   - the Neptune endpoint is passed in, never defaulted;
  *   - HERMETIC: every builder gets the client override configuration of `AwsClients.hermetic`: an
  *     explicitly supplied EMPTY profile file and a fixed profile name, so the SDK never reads the
  *     shared `~/.aws/config` or `~/.aws/credentials` for client defaults, plus an explicit region
  *     and the injected credentials provider. (Environment variables the SDK reads for client
  *     defaults, such as `AWS_ENDPOINT_URL*`, are not files and are not suppressed.)
  */
final class AwsClients(
    credentials: AwsCredentialsProvider,
    region: Region,
    neptuneEndpoint: Option[URI] = None,
):
  private def http = UrlConnectionHttpClient.builder()

  private def hermetic = AwsClients.hermetic

  lazy val s3: S3Client = S3Client
    .builder()
    .credentialsProvider(credentials)
    .region(region)
    .overrideConfiguration(hermetic)
    .httpClientBuilder(http)
    .build()

  lazy val dynamoDb: DynamoDbClient = DynamoDbClient
    .builder()
    .credentialsProvider(credentials)
    .region(region)
    .overrideConfiguration(hermetic)
    .httpClientBuilder(http)
    .build()

  lazy val s3Vectors: S3VectorsClient = S3VectorsClient
    .builder()
    .credentialsProvider(credentials)
    .region(region)
    .overrideConfiguration(hermetic)
    .httpClientBuilder(http)
    .build()

  lazy val sqs: SqsClient = SqsClient
    .builder()
    .credentialsProvider(credentials)
    .region(region)
    .overrideConfiguration(hermetic)
    .httpClientBuilder(http)
    .build()

  /** Needs the cluster endpoint; `Left` (not an exception) when none was given. */
  def neptuneData: Either[StorageError, NeptunedataClient] =
    neptuneEndpoint
      .toRight(StorageError.InvalidRequest("no Neptune endpoint was configured"))
      .map(_ => neptuneClient)

  private lazy val neptuneClient: NeptunedataClient = NeptunedataClient
    .builder()
    .credentialsProvider(credentials)
    .region(region)
    .overrideConfiguration(hermetic)
    .httpClientBuilder(http)
    .endpointOverride(neptuneEndpoint.get)
    .build()

  def objectStore: ObjectStore = new S3ObjectStore(s3)

  def keyValueStore: KeyValueStore = new DynamoKeyValueStore(dynamoDb)

  def vectorStore(bucketName: String, indexDimensions: Map[String, Int]): VectorStore =
    new S3VectorsStore(s3Vectors, bucketName, indexDimensions)

  def workQueue(queueUrls: Map[String, String]): WorkQueue = new SqsWorkQueue(sqs, queueUrls)

  def graphStore(cfg: StorageModuleConfig): Either[StorageError, GraphStore] =
    neptuneData.map { c =>
      new TemplateGraphStore(
        cfg.queryBounds,
        cfg.neptune,
        region.id(),
        NeptuneAws.executor(c),
        NeptuneAws.loader(c),
      )
    }

object AwsClients:
  /** Fixed profile name: no profile of this name exists in the supplied (empty) profile file. */
  val HermeticProfileName: String = "scholarchain-hermetic"

  /** An explicit profile file with no content: nothing on disk is read to build it. */
  def emptyProfileFile: ProfileFile =
    ProfileFile
      .builder()
      .content(new ByteArrayInputStream(Array.emptyByteArray))
      .`type`(ProfileFile.Type.CONFIGURATION)
      .build()

  /** The client override configuration every builder uses. Building it makes no AWS call and reads
    * no shared AWS file.
    */
  def hermetic: ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .defaultProfileFile(emptyProfileFile)
      .defaultProfileName(HermeticProfileName)
      .build()
