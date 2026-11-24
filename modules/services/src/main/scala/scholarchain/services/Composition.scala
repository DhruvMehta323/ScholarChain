package scholarchain.services

import java.net.URI
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.regions.Region

import scholarchain.core.*
import scholarchain.core.rest.*
import scholarchain.rag.EmbedderFactory
import scholarchain.rag.runtime.*
import scholarchain.storage.*

/** Process settings that are not part of a core configuration contract (ports, deadlines, the two
  * API keys, the retrieval URL). Everything else comes from the core loaders. The key values are
  * read here and nowhere logged.
  */
final case class ServiceSettings(
    environment: String,
    port: Int,
    syncDeadlineMillis: Long,
    downstreamTimeoutMillis: Long,
    publicApiKey: Option[String],
    internalApiKey: Option[String],
    retrievalUrl: Option[String],
    neptuneEndpoint: Option[String],
    region: Option[String],
    maxConcurrentQueries: Int = 4,
    maxQueuedQueries: Int = 16,
    asyncDeadlineMillis: Long = 120000L,
):
  def isLocal: Boolean = environment == "local"

object ServiceSettings:
  def fromEnv(env: Map[String, String]): Either[String, ServiceSettings] =
    def num(name: String, default: Long, lo: Long, hi: Long): Either[String, Long] =
      env.get(name).filter(_.nonEmpty) match
        case None    => Right(default)
        case Some(v) =>
          v.toLongOption
            .filter(n => n >= lo && n <= hi)
            .toRight(s"$name must be an integer in $lo..$hi")
    def opt(name: String): Option[String] = env.get(name).map(_.trim).filter(_.nonEmpty)
    for
      port     <- num("SCHOLARCHAIN_SERVICES_PORT", 8080L, 0L, 65535L)
      deadline <- num("SCHOLARCHAIN_SERVICES_SYNC_DEADLINE_MILLIS", 20000L, 0L, 120000L)
      dtimeout <- num("SCHOLARCHAIN_SERVICES_DOWNSTREAM_TIMEOUT_MILLIS", 8000L, 100L, 60000L)
      conc     <- num("SCHOLARCHAIN_SERVICES_MAX_CONCURRENT_QUERIES", 4L, 1L, 64L)
      queued   <- num("SCHOLARCHAIN_SERVICES_MAX_QUEUED_QUERIES", 16L, 1L, 1024L)
      adead    <- num("SCHOLARCHAIN_SERVICES_ASYNC_DEADLINE_MILLIS", 120000L, 1000L, 900000L)
    yield ServiceSettings(
      opt("SCHOLARCHAIN_ENVIRONMENT").getOrElse(""),
      port.toInt,
      deadline,
      dtimeout,
      opt("SCHOLARCHAIN_SERVICES_API_KEY"),
      opt("SCHOLARCHAIN_INTERNAL_API_KEY"),
      opt("SCHOLARCHAIN_SERVICES_RETRIEVAL_URL"),
      opt("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT"),
      opt("SCHOLARCHAIN_AWS_REGION").orElse(opt("AWS_REGION")),
      conc.toInt,
      queued.toInt,
      adead,
    )

/** The wired ports of one service process, every downstream port behind its guard. `checks` are the
  * readiness probes (a cheap read per dependency).
  */
final class ServiceRuntime(
    val settings: RuntimeSettings,
    val tables: RuntimeTables,
    val kv: KeyValueStore,
    val graph: GraphStore,
    val vectors: VectorStore,
    val objects: ObjectStore,
    val embedder: Embedder,
    val model: Option[ModelClient],
    val mode: String,
    val needs: Set[String] = Set("key_value", "graph", "vectors"),
    val promptSource: () => Either[RuntimeError, PromptRepository] = () =>
      PromptRepository.locate(),
    val missingConfig: () => List[String] = () => Nil,
):
  private lazy val prompts: PromptRepository =
    promptSource().fold(e => throw new IllegalStateException(e.message), identity)

  /** A new workflow on every call: its resolver keeps an unsynchronized cache, so each run (and
    * each thread) gets its own instance. Construction only wires objects together.
    */
  def workflow: QueryWorkflow =
    QueryWorkflow.create(
      settings,
      model.getOrElse(throw new IllegalStateException("no model client in this service")),
      kv,
      graph,
      vectors,
      objects,
      embedder,
      prompts,
      tables,
      if mode == "local" then RuntimeClock.Fixed(1700000000000L) else RuntimeClock.System,
    )

  /** Readiness probes: name to a reachability result (`Left(reasonToken)` when down). */
  def checks: List[(String, () => Either[String, Unit])] =
    val absent = "readyz-probe"
    List(
      "key_value" -> (() =>
        kv.get(tables.querySession, KvKey(absent)).left.map(Downstream.reasonCode).map(_ => ())
      ),
      "graph" -> (() =>
        graph
          .query(
            GraphQuery(
              GraphTemplates.Neighbors,
              Map(
                "researcherId" -> GraphValue.Str("researcher:sc1:" + "0" * 64),
                "window"       -> GraphValue.Str(RuntimeCode.AllTimeLabel),
              ),
              QueryBounds(1, 1, 2000L),
            )
          )
          .left
          .map(Downstream.reasonCode)
          .map(_ => ())
      ),
      "vectors" -> (() =>
        vectors
          .get(VectorIndexes.ResearcherProfile, absent)
          .left
          .map(Downstream.reasonCode)
          .map(_ => ())
      ),
      "prompts" -> (() => Readiness.promptsCheck(promptSource)),
      "config"  -> (() =>
        if missingConfig().isEmpty then Right(()) else Left(Readiness.ConfigMissing)
      ),
    ).filter((n, _) => needs.contains(n))

object Readiness:
  /** Stable reason codes of the `prompts` and `config` readiness checks. */
  val PromptsDirMissing: String    = "prompts_dir_missing"
  val PromptVersionMissing: String = "prompt_version_missing"
  val PromptInvalid: String        = "prompt_invalid"
  val ConfigMissing: String        = "config_missing"

  /** The prompt IDs (all at `PromptRepository.Version`) a query-api needs to answer. */
  val RequiredPrompts: List[(String, String)] =
    List(PromptRepository.PlannerId, PromptRepository.SynthesisId, PromptRepository.VerificationId)
      .map(_ -> PromptRepository.Version)

  /** `Right` when the prompts-and-schemas folder is found and every required prompt version loads
    * (prompt text, schema text, schema parses). Otherwise a stable reason code, never a path.
    */
  def promptsCheck(
      source: () => Either[RuntimeError, PromptRepository],
      required: List[(String, String)] = RequiredPrompts,
  ): Either[String, Unit] =
    source() match
      case Left(_)     => Left(PromptsDirMissing)
      case Right(repo) =>
        required.iterator
          .map((id, v) => repo.load(id, v))
          .collectFirst { case Left(e) => e }
          .fold[Either[String, Unit]](Right(()))(e =>
            Left(if e.code == "invalid-schema" then PromptInvalid else PromptVersionMissing)
          )

  /** `GET /readyz`: 200 when every dependency answers, 503 with the failing names otherwise. */
  def respond(service: String, rt: ServiceRuntime): Either[ApiFailure, Resp] =
    val checks = rt.checks.map { (name, probe) =>
      probe() match
        case Right(_)     => DependencyCheck(name, DependencyState.Up)
        case Left(reason) => DependencyCheck(name, DependencyState.Down, Some(reason))
    }
    val view = ReadinessView.of(service, checks)
    val code = if view.status == ReadinessState.Ready then 200 else 503
    Respond.json(code, view, RestContracts.Health)

/** Test seam for the AWS compositions. Production passes `AwsSeams.Production`: the default
  * credentials provider and no endpoint override. A test injects a static provider and a loopback
  * Neptune endpoint so the composition code runs up to the first network call. (`AwsClients`
  * accepts an endpoint override for Neptune only.)
  */
final case class AwsSeams(
    credentials: Option[AwsCredentialsProvider] = None,
    neptuneEndpoint: Option[URI] = None,
):
  def provider: AwsCredentialsProvider =
    credentials.getOrElse(DefaultCredentialsProvider.builder().build())

object AwsSeams:
  val Production: AwsSeams = AwsSeams()

object Composition:
  private def guards(rt: ServiceSettings): () => DownstreamGuard =
    var n = 0
    () =>
      n += 1
      new DownstreamGuard(
        s"port-$n",
        rt.downstreamTimeoutMillis,
        3,
        new CircuitBreaker(5, 10000L),
      )

  /** Wraps each port with timeout, bounded retries and a breaker. */
  def guarded(
      s: ServiceSettings,
      settings: RuntimeSettings,
      tables: RuntimeTables,
      kv: KeyValueStore,
      graph: GraphStore,
      vectors: VectorStore,
      objects: ObjectStore,
      embedder: Embedder,
      model: Option[ModelClient],
      mode: String,
      needs: Set[String] = Set("key_value", "graph", "vectors"),
      promptSource: () => Either[RuntimeError, PromptRepository] = () => PromptRepository.locate(),
      missingConfig: () => List[String] = () => Nil,
  ): ServiceRuntime =
    val g = guards(s)
    new ServiceRuntime(
      settings,
      tables,
      new GuardedKv(kv, g()),
      new GuardedGraph(graph, g()),
      new GuardedVectors(vectors, g()),
      new GuardedObjects(objects, g()),
      embedder,
      model,
      mode,
      needs,
      promptSource,
      missingConfig,
    )

  /** Local mode: fakes loaded from the Phase 07 L4 output and the Phase 08 vectors, the rag fake
    * model client. No AWS client and no network. Fails (never skips) when an input is missing.
    */
  def local(s: ServiceSettings, auditDir: Path): Either[String, ServiceRuntime] =
    val paths = LocalFixturePaths.default(auditDir)
    val miss  = LocalFixturePaths.missing(paths)
    if miss.nonEmpty then
      Left(s"local fixtures are missing (run the earlier phases first): ${miss.mkString(", ")}")
    else
      Files.createDirectories(auditDir)
      RuntimeConfigLoader
        .load(Map.empty, Map(ModuleConfig.overrideName(RuntimeConfigKeys.Provider) -> "fake"))
        .left
        .map(es => s"runtime configuration is invalid: ${es.size} error(s)")
        .map { r =>
          val tables = RuntimeTables.defaults
          val st     = new LocalStores(paths, tables)
          guarded(
            s,
            r.settings,
            tables,
            st.kv,
            st.graph,
            st.vectors,
            st.objects,
            st.embedder,
            Some(new FakeModelClient(responder = Some(LocalPlannerResponder.responder))),
            "local",
            Set("key_value", "graph", "vectors", "prompts"),
          )
        }

  private def configErrors(what: String, es: List[ValidationError]): String =
    s"$what configuration is invalid: " + es.map(_.toString).mkString("; ").take(400)

  /** Where the query audit documents go: the curated bucket (bare name from
    * `SCHOLARCHAIN_STORAGE_CURATED_BUCKET`) and the key prefix `<rag-prefix>/audit` (no trailing
    * slash, as `RuntimeTables.auditPrefix` is joined with `/`). A relative prefix is never parsed
    * as a bucket: a prefix that looks like a URI or an absolute path is refused.
    */
  def auditLocation(curatedBucket: String, ragPrefix: String): Either[String, (String, String)] =
    val b = curatedBucket.trim
    val p = ragPrefix.trim
    if b.isEmpty || b.contains("/") || b.toLowerCase.startsWith("s3:") then
      Left("the curated bucket must be a bare bucket name")
    else if p.isEmpty || p.startsWith("/") || p.toLowerCase.startsWith("s3:") || p.contains("..")
    then Left("the rag prefix must be a relative key prefix")
    else Right((b, p.stripSuffix("/") + "/audit"))

  private def tablesOf(
      cfg: StorageModuleConfig,
      curatedBucket: String,
  ): Either[String, RuntimeTables] =
    auditLocation(curatedBucket, cfg.s3.rag).map((bucket, prefix) =>
      RuntimeTables(
        cfg.tables.querySession,
        cfg.tables.evidenceBundle,
        cfg.tables.analytics,
        bucket,
        prefix,
      )
    )

  val CuratedBucketEnv: String = ConfigKeys.CuratedBucket.envName

  private def curated(env: Map[String, String]): Either[String, String] =
    require(CuratedBucketEnv, env.get(CuratedBucketEnv).map(_.trim).filter(_.nonEmpty))

  /** Names of the required environment values that are missing (or malformed) for an AWS role
    * (`query-api` or `retrieval`). Names only, never values.
    */
  def missingConfig(role: String, s: ServiceSettings, env: Map[String, String]): List[String] =
    val common = List(
      "SCHOLARCHAIN_AWS_REGION" -> s.region.isDefined,
      CuratedBucketEnv -> curated(env).exists(b => !b.contains("/") && !b.startsWith("s3:")),
    )
    val specific =
      if role == "retrieval" then
        List("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT" -> s.neptuneEndpoint.isDefined)
      else
        List(
          "SCHOLARCHAIN_SERVICES_RETRIEVAL_URL" -> s.retrievalUrl.isDefined,
          "SCHOLARCHAIN_INTERNAL_API_KEY"       -> s.internalApiKey.isDefined,
        )
    (common ++ specific).collect { case (n, false) => n }

  private def require(name: String, v: Option[String]): Either[String, String] =
    v.toRight(s"$name is required and is not set")

  /** AWS query-api: Dynamo and S3 directly, graph, vectors and embeddings through retrieval. */
  def awsQueryApi(
      s: ServiceSettings,
      env: Map[String, String],
      seams: AwsSeams = AwsSeams.Production,
  ): Either[String, ServiceRuntime] =
    for
      region <- require("SCHOLARCHAIN_AWS_REGION", s.region)
      url    <- require("SCHOLARCHAIN_SERVICES_RETRIEVAL_URL", s.retrievalUrl)
      key    <- require("SCHOLARCHAIN_INTERNAL_API_KEY", s.internalApiKey)
      cb     <- curated(env)
      rs     <- RuntimeConfigLoader.load(env).left.map(configErrors("runtime", _))
      sc     <- StorageConfigLoader.load(env).left.map(configErrors("storage", _))
      es     <- EmbeddingConfigLoader.load(env).left.map(configErrors("embedding", _))
      tables <- tablesOf(sc.config, cb)
      _      <-
        if rs.settings.provider == ModelProvider.Anthropic &&
          !env.get(rs.settings.anthropicApiKeyEnvVar).exists(_.nonEmpty)
        then Left(s"${rs.settings.anthropicApiKeyEnvVar} is required and is not set")
        else Right(())
      model <- ModelClientFactory
        .fromSettings(
          rs.settings,
          ModelClientFactory.bedrock(
            rs.settings,
            region,
            liveProfile = true,
            seams.provider,
          ),
          None,
        )
        .left
        .map(_ => "the model client could not be built")
    yield
      val clients = new AwsClients(seams.provider, Region.of(region))
      val remote  = new RetrievalClient(url, key, math.max(100L, s.downstreamTimeoutMillis - 500L))
      guarded(
        s,
        rs.settings,
        tables,
        clients.keyValueStore,
        new RemoteGraphStore(remote),
        new RemoteVectorStore(remote),
        clients.objectStore,
        new RemoteEmbedder(
          remote,
          es.settings.modelId,
          es.settings.modelVersion,
          es.settings.dimension,
        ),
        Some(model),
        "aws",
        Set("key_value", "graph", "vectors", "prompts", "config"),
        promptSource = () => PromptRepository.locate(),
        missingConfig = () => missingConfig("query-api", s, env),
      )

  /** AWS retrieval: Neptune (required), S3 Vectors and the embedder. Neptune is never optional. */
  def awsRetrieval(
      s: ServiceSettings,
      env: Map[String, String],
      seams: AwsSeams = AwsSeams.Production,
  ): Either[String, ServiceRuntime] =
    for
      region   <- require("SCHOLARCHAIN_AWS_REGION", s.region)
      endpoint <- require("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT", s.neptuneEndpoint)
      uri      <- Try(URI.create(endpoint)).toOption
        .filter(_.getHost != null || endpoint.nonEmpty)
        .toRight("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT is not a valid URI")
      cb     <- curated(env)
      rs     <- RuntimeConfigLoader.load(env).left.map(configErrors("runtime", _))
      sc     <- StorageConfigLoader.load(env).left.map(configErrors("storage", _))
      es     <- EmbeddingConfigLoader.load(env).left.map(configErrors("embedding", _))
      tables <- tablesOf(sc.config, cb)
      creds   = seams.provider
      clients = new AwsClients(creds, Region.of(region), Some(seams.neptuneEndpoint.getOrElse(uri)))
      graph <- clients
        .graphStore(sc.config)
        .left
        .map(_ => "the Neptune graph store could not be configured")
      embedder <-
        (if env.get("SCHOLARCHAIN_VOYAGE_API_KEY").exists(_.nonEmpty) then
           EmbedderFactory.voyage(es.settings, n => env.get(n))
         else EmbedderFactory.bedrock(es.settings, region, liveProfile = true, creds)).left
          .map(_ => "the embedder could not be configured")
    yield guarded(
      s,
      rs.settings,
      tables,
      clients.keyValueStore,
      graph,
      clients.vectorStore(
        sc.config.vectors.bucketName,
        VectorIndexes.all.map(_ -> es.settings.dimension).toMap,
      ),
      clients.objectStore,
      embedder,
      None,
      "aws",
      Set("graph", "vectors", "config"),
      missingConfig = () => missingConfig("retrieval", s, env),
    )

  /** The worker's ports (AWS only). */
  def awsWorker(
      s: ServiceSettings,
      env: Map[String, String],
      seams: AwsSeams = AwsSeams.Production,
  ): Either[String, (EmbeddingWorker, EmbeddingWorkerConfig)] =
    for
      region <- require("SCHOLARCHAIN_AWS_REGION", s.region)
      qurl   <- require(
        "SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL",
        env.get("SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL").filter(_.nonEmpty),
      )
      dlq <- require(
        "SCHOLARCHAIN_SERVICES_EMBEDDING_DLQ_URL",
        env.get("SCHOLARCHAIN_SERVICES_EMBEDDING_DLQ_URL").filter(_.nonEmpty),
      )
      sc <- StorageConfigLoader.load(env).left.map(configErrors("storage", _))
      es <- EmbeddingConfigLoader.load(env).left.map(configErrors("embedding", _))
      creds   = seams.provider
      clients = new AwsClients(creds, Region.of(region))
      embedder <-
        (if env.get("SCHOLARCHAIN_VOYAGE_API_KEY").exists(_.nonEmpty) then
           EmbedderFactory.voyage(es.settings, n => env.get(n))
         else EmbedderFactory.bedrock(es.settings, region, liveProfile = true, creds)).left
          .map(_ => "the embedder could not be configured")
    yield
      val cfg   = EmbeddingWorkerConfig.fromSettings(es.settings)
      val queue =
        clients.workQueue(Map(es.settings.queueName -> qurl, es.settings.deadLetterName -> dlq))
      val vecs = clients.vectorStore(
        sc.config.vectors.bucketName,
        VectorIndexes.all.map(_ -> es.settings.dimension).toMap,
      )
      (
        new EmbeddingWorker(cfg, queue, clients.objectStore, embedder, vecs, clients.keyValueStore),
        cfg,
      )
