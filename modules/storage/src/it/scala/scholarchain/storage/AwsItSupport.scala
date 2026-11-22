package scholarchain.storage

import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.regions.Region

/** Shared gate and settings of the live AWS suites (`*AwsItSuite`).
  *
  * The suites run only when `SCHOLARCHAIN_AWS_IT=1`: the build filters them out otherwise, and each
  * suite also ignores itself (`munitIgnore`), so a stray run without the variable touches nothing.
  * Names, endpoints and the region are read from the environment at run time only (never at load
  * time of this object), and credentials come from the default AWS provider chain, created only
  * inside an enabled suite. Nothing is deleted: probe data is small, uniquely named and its cleanup
  * is documented in STORAGE-CONTRACTS.md.
  */
object AwsItSupport:
  def enabled: Boolean = sys.env.get("SCHOLARCHAIN_AWS_IT").contains("1")

  def env(name: String): String =
    sys.env
      .get(name)
      .filter(_.nonEmpty)
      .getOrElse(sys.error(s"environment variable $name is required"))

  def region: Region = Region.of(env("AWS_REGION"))

  /** Default provider chain; called only from an enabled suite. */
  def credentials: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build()

  def clients(neptuneEndpoint: Option[java.net.URI] = None): AwsClients =
    new AwsClients(credentials, region, neptuneEndpoint)

  def config: StorageModuleConfigHolder = StorageModuleConfigHolder(
    scholarchain.core.StorageConfigLoader
      .load()
      .fold(e => sys.error(e.map(_.message).mkString("; ")), _.config)
  )

final case class StorageModuleConfigHolder(value: scholarchain.core.StorageModuleConfig)
