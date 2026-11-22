package scholarchain.core

import ValidationError.*

/** Storage module configuration keys, all under `storage.` (the module contract of Phase 07; the
  * frozen `StorageConfig` and the 21 keys of `ConfigKeys` are untouched). Defaults live in
  * `scholarchain-storage.v1.conf`, not in code. Environment variable: `SCHOLARCHAIN_STORAGE_<KEY>`;
  * override name: `scholarchain.storage.<key>`.
  *
  * No default is an account ID, a real ARN, an endpoint or a secret. The role ARN default carries
  * the dummy account `000000000000` and must be replaced per environment.
  */
object StorageConfigKeys:
  val Resource = "scholarchain-storage.v1.conf"

  val SchemaVersionKey = ConfigKey("storage.schema-version")

  val S3Raw     = ConfigKey("storage.s3.raw-prefix")
  val S3Curated = ConfigKey("storage.s3.curated-prefix")
  val S3Graph   = ConfigKey("storage.s3.graph-prefix")
  val S3Rag     = ConfigKey("storage.s3.rag-prefix")
  val S3Reports = ConfigKey("storage.s3.reports-prefix")
  val S3Logs    = ConfigKey("storage.s3.logs-prefix")

  val NeptuneLoadRoleArn = ConfigKey("storage.neptune.bulk-load.role-arn")
  val NeptuneLoadFormat  = ConfigKey("storage.neptune.bulk-load.format")
  val NeptuneLoadSource  = ConfigKey("storage.neptune.bulk-load.source-s3-uri")

  val TableRunLedger        = ConfigKey("storage.dynamodb.run-ledger-table")
  val TableArtifactRegistry = ConfigKey("storage.dynamodb.artifact-registry-table")
  val TableQuerySession     = ConfigKey("storage.dynamodb.query-session-table")
  val TableEntityLinkCache  = ConfigKey("storage.dynamodb.entity-link-cache-table")
  val TableEvidenceBundle   = ConfigKey("storage.dynamodb.evidence-bundle-table")

  /** Logical names of the ten analytics item tables, in stable order (they match the `table` values
    * of the Spark `dynamodb.json` load manifest). The physical-name key of logical name `n` is
    * `storage.dynamodb.analytics.<n>-table`.
    */
  val AnalyticsLogicalNames: List[String] = List(
    "researcher-metrics",
    "coauthor-edge-works",
    "bridges",
    "coreness",
    "temporal-changes",
    "paths",
    "path-estimates",
    "window-summary",
    "identity-clusters",
    "mention-researcher",
  )

  /** Logical analytics table name to its config key, in `AnalyticsLogicalNames` order. */
  val AnalyticsTableKeys: List[(String, ConfigKey)] =
    AnalyticsLogicalNames.map(n => n -> ConfigKey(s"storage.dynamodb.analytics.$n-table"))

  val VectorsBucketName     = ConfigKey("storage.vectors.bucket-name")
  val IndexResearcher       = ConfigKey("storage.vectors.index.researcher-profile")
  val IndexCommunity        = ConfigKey("storage.vectors.index.collaboration-community")
  val IndexPaperAbstract    = ConfigKey("storage.vectors.index.paper-abstract")
  val IndexPath             = ConfigKey("storage.vectors.index.collaboration-path")
  val IndexTopicInstitution = ConfigKey("storage.vectors.index.topic-institution-summary")

  val QueryMaxDepth      = ConfigKey("storage.query.max-depth")
  val QueryMaxResults    = ConfigKey("storage.query.max-results")
  val QueryTimeoutMillis = ConfigKey("storage.query.timeout-millis")

  val RetryMaxAttempts     = ConfigKey("storage.retry.max-attempts")
  val RetryBaseDelayMillis = ConfigKey("storage.retry.base-delay-millis")
  val RetryMaxDelayMillis  = ConfigKey("storage.retry.max-delay-millis")

  val all: List[ConfigKey] = List(
    SchemaVersionKey,
    S3Raw,
    S3Curated,
    S3Graph,
    S3Rag,
    S3Reports,
    S3Logs,
    NeptuneLoadRoleArn,
    NeptuneLoadFormat,
    NeptuneLoadSource,
    TableRunLedger,
    TableArtifactRegistry,
    TableQuerySession,
    TableEntityLinkCache,
    TableEvidenceBundle,
    VectorsBucketName,
    IndexResearcher,
    IndexCommunity,
    IndexPaperAbstract,
    IndexPath,
    IndexTopicInstitution,
    QueryMaxDepth,
    QueryMaxResults,
    QueryTimeoutMillis,
    RetryMaxAttempts,
    RetryBaseDelayMillis,
    RetryMaxDelayMillis,
  ) ++ AnalyticsTableKeys.map(_._2)

/** Object-key prefixes inside the data buckets: non-blank, relative, ending in `/`, without
  * whitespace, `..` or `//`, and pairwise distinct.
  */
final case class StorageS3Prefixes(
    raw: String,
    curated: String,
    graph: String,
    rag: String,
    reports: String,
    logs: String,
):
  def all: List[String] = List(raw, curated, graph, rag, reports, logs)

/** Neptune bulk load. `roleArn` has the IAM role ARN shape; `format` is `opencypher` or `csv`;
  * `sourceS3Uri` is an `s3://<bucket>/<prefix>/` URI ending in `/`.
  */
final case class StorageNeptuneLoad(roleArn: String, format: String, sourceS3Uri: String)

/** The five DynamoDB table names plus the ten analytics item tables (`analyticsOrdered`, logical
  * name to physical name, in `StorageConfigKeys.AnalyticsLogicalNames` order): all fifteen
  * distinct, 3 to 255 characters of letters, digits, `_`, `-`, `.`.
  */
final case class StorageDynamoTables(
    runLedger: String,
    artifactRegistry: String,
    querySession: String,
    entityLinkCache: String,
    evidenceBundle: String,
    analyticsOrdered: List[(String, String)] = Nil,
):
  /** The five original tables only (unchanged). */
  def all: List[String] =
    List(runLedger, artifactRegistry, querySession, entityLinkCache, evidenceBundle)

  /** Logical analytics table name to physical DynamoDB table name. */
  def analytics: Map[String, String] = analyticsOrdered.toMap

  /** Physical analytics table names in the stable logical-name order. */
  def analyticsNames: List[String] = analyticsOrdered.map(_._2)

  /** Physical name for a logical analytics table name, if it is one of the ten. */
  def analyticsTable(logicalName: String): Option[String] = analytics.get(logicalName)

  /** All fifteen physical table names (five original, then ten analytics). */
  def allWithAnalytics: List[String] = all ++ analyticsNames

/** S3 Vectors bucket and the five index names (each 3 to 63 characters of lowercase letters, digits
  * and `-`; the five names are distinct).
  */
final case class StorageVectors(
    bucketName: String,
    researcherProfileIndex: String,
    collaborationCommunityIndex: String,
    paperAbstractIndex: String,
    collaborationPathIndex: String,
    topicInstitutionSummaryIndex: String,
):
  def indexes: List[String] = List(
    researcherProfileIndex,
    collaborationCommunityIndex,
    paperAbstractIndex,
    collaborationPathIndex,
    topicInstitutionSummaryIndex,
  )

/** Retry caps. `maxAttempts` in 1..`MaxAttemptsCap`; `baseDelayMillis` >= 1; `maxDelayMillis` >=
  * `baseDelayMillis` and <= `MaxDelayCapMillis` (ScholarChain policy caps).
  */
final case class StorageRetry(maxAttempts: Int, baseDelayMillis: Long, maxDelayMillis: Long)

object StorageRetry:
  val MaxAttemptsCap: Int     = 10
  val MaxDelayCapMillis: Long = 60000L

final case class StorageModuleConfig(
    schemaVersion: Int,
    s3: StorageS3Prefixes,
    neptune: StorageNeptuneLoad,
    tables: StorageDynamoTables,
    vectors: StorageVectors,
    queryBounds: QueryBounds,
    retry: StorageRetry,
)

/** The resolved storage settings plus the origin of each key (never the values). */
final case class ResolvedStorageModuleConfig(
    config: StorageModuleConfig,
    origins: Map[String, String],
)

object StorageConfigLoader:
  import StorageConfigKeys.*

  private val RoleArn     = """^arn:aws[a-z\-]*:iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_/\-]+$""".r
  private val TableName   = """^[A-Za-z0-9_.\-]{3,255}$""".r
  private val VectorName  = """^[a-z0-9\-]{3,63}$""".r
  private val LoadFormats = Set("opencypher", "csv")

  /** Loads `scholarchain-storage.v1.conf` from the classpath and applies env and overrides. */
  def load(
      env: Map[String, String] = sys.env,
      overrides: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedStorageModuleConfig] =
    ModuleConfig.load(all, Resource, env, overrides).flatMap(build)

  /** Pure resolution over explicit layers (no classpath access). */
  def resolve(
      env: Map[String, String],
      overrides: Map[String, String],
      file: Map[String, String],
  ): Either[List[ValidationError], ResolvedStorageModuleConfig] =
    ModuleConfig.resolve(all, env, overrides, file).flatMap(build)

  private def bad(k: ConfigKey, why: String) = Left(InvalidConfig(k.key, why))

  private def prefix(k: ConfigKey, v: String): Either[ValidationError, String] =
    if v.nonEmpty && v.endsWith("/") && !v.startsWith("/") && !v.exists(_.isWhitespace) &&
      !v.contains("..") && !v.contains("//")
    then Right(v)
    else bad(k, "must be a relative prefix ending in '/', without whitespace, '..' or '//'")

  private def s3Prefix(k: ConfigKey, v: String): Either[ValidationError, String] =
    if StorageChecks.isS3Uri(v) && v.endsWith("/") then Right(v)
    else bad(k, "must be an s3://<bucket>/<prefix>/ URI")

  private def table(k: ConfigKey, v: String): Either[ValidationError, String] =
    if TableName.matches(v) then Right(v) else bad(k, "must be a valid DynamoDB table name")

  private def vectorName(k: ConfigKey, v: String): Either[ValidationError, String] =
    if VectorName.matches(v) then Right(v)
    else bad(k, "must be 3 to 63 lowercase letters, digits or '-'")

  private def distinct[A](k: ConfigKey, xs: List[String]): Either[ValidationError, Unit] =
    if xs.distinct.size == xs.size then Right(()) else bad(k, "values must be pairwise distinct")

  private def long(k: ConfigKey, v: String, min: Long): Either[ValidationError, Long] =
    v.toLongOption
      .toRight(InvalidConfig(k.key, "must be an integer"))
      .flatMap(n => if n >= min then Right(n) else bad(k, s"must be >= $min"))

  private def build(
      raw: ModuleConfig.Raw
  ): Either[List[ValidationError], ResolvedStorageModuleConfig] =
    def v(k: ConfigKey): String   = raw.values(k.key)
    def i(k: ConfigKey, min: Int) = ModuleConfig.int(k, v(k), min)

    val sv = v(SchemaVersionKey).toIntOption
      .toRight(InvalidConfig(SchemaVersionKey.key, "must be an integer"))
      .flatMap(
        SchemaVersion
          .check(_)
          .left
          .map(_ => InvalidConfig(SchemaVersionKey.key, "unsupported schema version"))
      )

    val prefixKeys = List(S3Raw, S3Curated, S3Graph, S3Rag, S3Reports, S3Logs)
    val prefixes   = prefixKeys.map(k => prefix(k, v(k)))
    val prefixDup  = distinct(S3Raw, prefixKeys.map(v))

    val arn = if RoleArn.matches(v(NeptuneLoadRoleArn)) then Right(v(NeptuneLoadRoleArn))
    else bad(NeptuneLoadRoleArn, "must be an IAM role ARN")
    val fmt = if LoadFormats.contains(v(NeptuneLoadFormat)) then Right(v(NeptuneLoadFormat))
    else bad(NeptuneLoadFormat, "must be one of opencypher, csv")
    val src = s3Prefix(NeptuneLoadSource, v(NeptuneLoadSource))

    val tableKeys = List(
      TableRunLedger,
      TableArtifactRegistry,
      TableQuerySession,
      TableEntityLinkCache,
      TableEvidenceBundle,
    )
    val tables   = tableKeys.map(k => table(k, v(k)))
    val anaKeys  = AnalyticsTableKeys.map(_._2)
    val anaTabs  = anaKeys.map(k => table(k, v(k)))
    val tableDup = distinct(TableRunLedger, (tableKeys ++ anaKeys).map(v))

    val vbucket   = vectorName(VectorsBucketName, v(VectorsBucketName))
    val indexKeys = List(
      IndexResearcher,
      IndexCommunity,
      IndexPaperAbstract,
      IndexPath,
      IndexTopicInstitution,
    )
    val indexes  = indexKeys.map(k => vectorName(k, v(k)))
    val indexDup = distinct(IndexResearcher, indexKeys.map(v))

    val depth   = i(QueryMaxDepth, 1)
    val results = i(QueryMaxResults, 1)
    val timeout = long(QueryTimeoutMillis, v(QueryTimeoutMillis), 1L)

    val attempts = i(RetryMaxAttempts, 1).flatMap(n =>
      if n <= StorageRetry.MaxAttemptsCap then Right(n)
      else bad(RetryMaxAttempts, s"must be <= ${StorageRetry.MaxAttemptsCap}")
    )
    val base = long(RetryBaseDelayMillis, v(RetryBaseDelayMillis), 1L)
    val maxD = long(RetryMaxDelayMillis, v(RetryMaxDelayMillis), 1L).flatMap(n =>
      if n <= StorageRetry.MaxDelayCapMillis then Right(n)
      else bad(RetryMaxDelayMillis, s"must be <= ${StorageRetry.MaxDelayCapMillis}")
    )
    val order = (base, maxD) match
      case (Right(b), Right(m)) if m < b =>
        bad(RetryMaxDelayMillis, "must be >= storage.retry.base-delay-millis")
      case _ => Right(())

    val errors =
      (List(
        sv,
        prefixDup,
        arn,
        fmt,
        src,
        tableDup,
        vbucket,
        indexDup,
        depth,
        results,
        timeout,
        attempts,
        base,
        maxD,
        order,
      ) ++ prefixes ++ tables ++ anaTabs ++ indexes).collect { case Left(e) =>
        e
      }
    if errors.nonEmpty then Left(errors.distinct)
    else
      (for
        s1 <- sv
        ps <- prefixes.foldRight[Either[ValidationError, List[String]]](Right(Nil))((e, acc) =>
          for x <- e; xs <- acc yield x :: xs
        )
        a  <- arn
        f  <- fmt
        so <- src
        ts <- tables.foldRight[Either[ValidationError, List[String]]](Right(Nil))((e, acc) =>
          for x <- e; xs <- acc yield x :: xs
        )
        an <- anaTabs.foldRight[Either[ValidationError, List[String]]](Right(Nil))((e, acc) =>
          for x <- e; xs <- acc yield x :: xs
        )
        vb <- vbucket
        ix <- indexes.foldRight[Either[ValidationError, List[String]]](Right(Nil))((e, acc) =>
          for x <- e; xs <- acc yield x :: xs
        )
        d  <- depth
        r  <- results
        t  <- timeout
        at <- attempts
        b  <- base
        m  <- maxD
      yield ResolvedStorageModuleConfig(
        StorageModuleConfig(
          s1,
          StorageS3Prefixes(ps(0), ps(1), ps(2), ps(3), ps(4), ps(5)),
          StorageNeptuneLoad(a, f, so),
          StorageDynamoTables(
            ts(0),
            ts(1),
            ts(2),
            ts(3),
            ts(4),
            AnalyticsLogicalNames.zip(an),
          ),
          StorageVectors(vb, ix(0), ix(1), ix(2), ix(3), ix(4)),
          QueryBounds(d, r, t),
          StorageRetry(at, b, m),
        ),
        raw.origins,
      )).left.map(List(_))
