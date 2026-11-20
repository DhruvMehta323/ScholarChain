package scholarchain.core

import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.typesafe.config.{
  Config,
  ConfigFactory,
  ConfigParseOptions,
  ConfigResolveOptions,
  ConfigSyntax,
  ConfigValueType,
}

import ValidationError.*

/** The only code in ScholarChain that imports `com.typesafe.config`.
  *
  * It parses one explicit versioned HOCON file, flattens the declared keys into the dotted-key file
  * layer, and hands that layer plus an injectable environment to `ConfigResolver.resolve`.
  * Precedence is unchanged: environment first, then the file. Nothing else is consulted:
  *   - `ConfigFactory.load()` is never called, so JVM system properties, `application.conf` and any
  *     classpath `reference.conf` (for example one supplied by a cluster) are not merged.
  *   - HOCON substitution is resolved with the system environment switched off, so `${NAME}` can
  *     only refer to another key inside the same file. The process environment reaches the
  *     configuration solely through `SCHOLARCHAIN_<KEY>` variables.
  *   - Keys in the file that are not in `ConfigKeys` are ignored. An `include` statement is
  *     resolved by the HOCON parser relative to the file; versioned files should not use it.
  *
  * Failures are typed (`InvalidConfig`) and never carry configuration values or parser messages
  * (which may quote file content): only the key name or the word `config-file`.
  */
object ConfigLoader:
  /** Classpath name of the versioned reference file shipped in `core`. */
  val ReferenceResource: String = "scholarchain-config.v1.conf"

  private val FileKey = "config-file"

  private val parseOptions =
    ConfigParseOptions.defaults().setAllowMissing(false).setSyntax(ConfigSyntax.CONF)
  private val resolveOptions =
    ConfigResolveOptions.defaults().setUseSystemEnvironment(false).setAllowUnresolved(false)

  /** Loads the file at `path` and resolves it against `env`. */
  def loadFile(
      path: Path,
      env: Map[String, String] = sys.env,
  ): Either[List[ValidationError], ResolvedConfig] =
    parse(ConfigFactory.parseFile(path.toFile, parseOptions)).flatMap(resolveWith(env, _))

  /** Loads a classpath resource (default: the shipped `scholarchain-config.v1.conf`). */
  def loadResource(
      name: String = ReferenceResource,
      env: Map[String, String] = sys.env,
  ): Either[List[ValidationError], ResolvedConfig] =
    parse(ConfigFactory.parseResources(getClass.getClassLoader, name, parseOptions))
      .flatMap(resolveWith(env, _))

  /** Loads HOCON text. Same rules as `loadFile`. */
  def loadString(
      hocon: String,
      env: Map[String, String] = sys.env,
  ): Either[List[ValidationError], ResolvedConfig] =
    parse(ConfigFactory.parseString(hocon, parseOptions)).flatMap(resolveWith(env, _))

  /** Only the flattened file layer, for inspection. */
  def fileLayer(path: Path): Either[List[ValidationError], Map[String, String]] =
    parse(ConfigFactory.parseFile(path.toFile, parseOptions))

  private def resolveWith(
      env: Map[String, String],
      layer: Map[String, String],
  ): Either[List[ValidationError], ResolvedConfig] =
    ConfigResolver.resolve(env, layer)

  /** Runs the (throwing) HOCON parse and flattening inside one total boundary. */
  private def parse(load: => Config): Either[List[ValidationError], Map[String, String]] =
    val loaded =
      try Right(load.resolve(resolveOptions))
      catch
        case _: com.typesafe.config.ConfigException.IO =>
          Left(InvalidConfig(FileKey, "cannot be read"))
        case _: com.typesafe.config.ConfigException.Parse =>
          Left(InvalidConfig(FileKey, "is not valid HOCON"))
        case _: com.typesafe.config.ConfigException.UnresolvedSubstitution =>
          Left(InvalidConfig(FileKey, "has an unresolved substitution"))
        case NonFatal(_) =>
          Left(InvalidConfig(FileKey, "cannot be loaded"))
    loaded.left.map(List(_)).flatMap(flatten)

  /** Keeps the declared keys only. A required or optional key holding an object or a list is an
    * error. A `null` counts as absent.
    */
  private def flatten(cfg: Config): Either[List[ValidationError], Map[String, String]] =
    val entries = ConfigKeys.all.map { k =>
      try
        if !cfg.hasPathOrNull(k.key) || cfg.getIsNull(k.key) then Right(None)
        else
          val v = cfg.getValue(k.key)
          v.valueType match
            case ConfigValueType.STRING | ConfigValueType.NUMBER | ConfigValueType.BOOLEAN =>
              Right(Some(k.key -> v.unwrapped.toString))
            case _ => Left(InvalidConfig(k.key, "must be a scalar (string, number or boolean)"))
      catch case NonFatal(_) => Left(InvalidConfig(k.key, "cannot be read from the file"))
    }
    val errors = entries.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors)
    else Right(entries.collect { case Right(Some(kv)) => kv }.toMap)
