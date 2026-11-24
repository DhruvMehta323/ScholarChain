package scholarchain.services

import java.nio.file.Paths

import scholarchain.core.rest.RestLimits
import scholarchain.storage.{EmbeddingWorker, EmbeddingWorkerConfig}

/** Shared start-up helpers. A start-up error is one JSON line on stderr with no value from the
  * environment, then exit code 2.
  */
object Startup:
  def fail(service: String, message: String): Nothing =
    System.err.println(
      ujson.write(
        ujson.Obj(
          "level"   -> "error",
          "service" -> service,
          "event"   -> "startup.refused",
          "message" -> message.take(500),
        )
      )
    )
    sys.exit(2)

  def settings(service: String): ServiceSettings =
    ServiceSettings.fromEnv(sys.env).fold(fail(service, _), identity)

  def awaitShutdown(app: HttpApp, log: JsonLog, service: String): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() =>
      log.log("info", "-", "-", "shutdown", "service" -> service)
      app.stop()
    ))
    Thread.currentThread().join()

/** A composed, not yet started HTTP service (the dry-start result of a main). */
final case class AssembledHttp(settings: ServiceSettings, runtime: ServiceRuntime, app: HttpApp)

object QueryApiApp:
  def create(
      rt: ServiceRuntime,
      s: ServiceSettings,
      publicKey: Option[String],
      ids: IdGen,
      log: JsonLog,
      host: String,
  ): HttpApp =
    val svc    = new QueryService(rt, s, ids, log)
    val routes = new QueryApiRoutes(rt, svc, new ApiKeyAuth(publicKey))
    new HttpApp("query-api", host, s.port, log, ids, RestLimits.MaxBodyBytes, routes.handle)

object RetrievalApp:
  def create(
      rt: ServiceRuntime,
      s: ServiceSettings,
      internalKey: Option[String],
      ids: IdGen,
      log: JsonLog,
      host: String,
  ): HttpApp =
    val routes = new RetrievalRoutes(rt, new ApiKeyAuth(internalKey))
    new HttpApp("retrieval", host, s.port, log, ids, RestLimits.MaxBodyBytes, routes.handle)

/** query-api: all public `/v1` routes on port 8080. Local mode (`SCHOLARCHAIN_ENVIRONMENT=local`)
  * wires fakes; any other value is AWS mode and refuses to start without its required settings.
  */
object QueryApiMain:
  /** Composition without side effects beyond building objects: nothing is started and no network
    * call is made. `main` calls it with the process environment and production seams.
    */
  def assemble(
      env: Map[String, String],
      seams: AwsSeams = AwsSeams.Production,
      host: String = "0.0.0.0",
  ): Either[String, AssembledHttp] =
    for
      s <- ServiceSettings.fromEnv(env)
      _ <- Either.cond(
        s.isLocal || s.publicApiKey.isDefined,
        (),
        "SCHOLARCHAIN_SERVICES_API_KEY is required outside local mode",
      )
      rt <-
        if s.isLocal then
          Composition.local(
            s,
            LocalFixturePaths.repoRoot.resolve("modules/services/target/local-audit"),
          )
        else Composition.awsQueryApi(s, env, seams)
    yield AssembledHttp(
      s,
      rt,
      QueryApiApp.create(rt, s, s.publicApiKey, new IdGen(false), new JsonLog("query-api"), host),
    )

  def main(args: Array[String]): Unit =
    val service = "query-api"
    val a       = assemble(sys.env).fold(Startup.fail(service, _), identity)
    val log     = new JsonLog(service)
    val p       = a.app.start()
    log.log("info", "-", "-", "startup.listening", "port" -> p.toString, "mode" -> a.runtime.mode)
    Startup.awaitShutdown(a.app, log, service)

/** retrieval: internal versioned graph and vector reads, authenticated by
  * `SCHOLARCHAIN_INTERNAL_API_KEY`. Neptune is required in AWS mode.
  */
object RetrievalMain:
  def assemble(
      env: Map[String, String],
      seams: AwsSeams = AwsSeams.Production,
      host: String = "0.0.0.0",
  ): Either[String, AssembledHttp] =
    for
      s <- ServiceSettings.fromEnv(env)
      _ <- Either.cond(
        s.isLocal || s.internalApiKey.isDefined,
        (),
        "SCHOLARCHAIN_INTERNAL_API_KEY is required outside local mode",
      )
      rt <-
        if s.isLocal then
          Composition
            .local(s, LocalFixturePaths.repoRoot.resolve("modules/services/target/local-audit"))
            .map(r =>
              new ServiceRuntime(
                r.settings,
                r.tables,
                r.kv,
                r.graph,
                r.vectors,
                r.objects,
                r.embedder,
                None,
                r.mode,
                Set("graph", "vectors"),
              )
            )
        else Composition.awsRetrieval(s, env, seams)
    yield AssembledHttp(
      s,
      rt,
      RetrievalApp.create(rt, s, s.internalApiKey, new IdGen(false), new JsonLog("retrieval"), host),
    )

  def main(args: Array[String]): Unit =
    val service = "retrieval"
    val a       = assemble(sys.env).fold(Startup.fail(service, _), identity)
    val log     = new JsonLog(service)
    val p       = a.app.start()
    log.log("info", "-", "-", "startup.listening", "port" -> p.toString, "mode" -> a.runtime.mode)
    Startup.awaitShutdown(a.app, log, service)

/** embedding-worker: no listener. Runs the Phase 08 `EmbeddingWorker` over the SQS queue port in a
  * loop until SIGTERM. AWS mode only (the local embedding run is the Phase 08 test harness).
  */
object EmbeddingWorkerMain:
  def assemble(
      env: Map[String, String],
      seams: AwsSeams = AwsSeams.Production,
  ): Either[String, (EmbeddingWorker, EmbeddingWorkerConfig)] =
    for
      s <- ServiceSettings.fromEnv(env)
      _ <- Either.cond(!s.isLocal, (), "the embedding worker has no local mode")
      w <- Composition.awsWorker(s, env, seams)
    yield w

  def main(args: Array[String]): Unit =
    val service           = "embedding-worker"
    val (worker, cfg)     = assemble(sys.env).fold(Startup.fail(service, _), identity)
    val log               = new JsonLog(service)
    @volatile var running = true
    Runtime.getRuntime.addShutdownHook(new Thread(() => running = false))
    log.log("info", "-", "-", "startup.running", "queue" -> cfg.queueName)
    while running do
      worker.runOnce() match
        case Right(c) if c.jobsProcessed > 0 =>
          log.log(
            "info",
            "-",
            "-",
            "worker.batch",
            "jobs"         -> c.jobsProcessed.toString,
            "written"      -> c.vectorsWritten.toString,
            "retried"      -> c.jobsRetried.toString,
            "deadLettered" -> c.jobsDeadLettered.toString,
          )
        case Right(_) => Thread.sleep(1000L)
        case Left(e)  =>
          log.log("warn", "-", "-", "worker.receive_failed", "code" -> e.code)
          Thread.sleep(5000L)
