package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration

import scala.collection.mutable

/** Scripted local session. Starts query-api in local mode (fakes loaded from the Phase 07 L4 output
  * and the Phase 08 vectors, fake model client, no AWS client, no network beyond loopback), runs
  * the Phase 10 scenario and saves the transcript under `modules/services/target/local-run/<run>/`:
  *
  *   - `transcript.json`: every step with trace ids and timestamps as observed;
  *   - `normalized.json`: the same with trace ids, request ids and timestamps replaced by
  *     placeholders, for comparing two runs;
  *   - `logs.jsonl`: the structured log lines of the run.
  *
  * Usage: `sbt "services/runMain scholarchain.services.LocalSessionMain run-1"`. The API key below
  * is a dummy placeholder for this session only; it protects nothing.
  */
object LocalSessionMain:
  private val DummyKey = "local-session-dummy-key"

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private val IsoInstant = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z""".r
  private val IdLike     = """(trace|req)-\d+""".r

  def normalize(s: String): String =
    IdLike.replaceAllIn(IsoInstant.replaceAllIn(s, "<timestamp>"), m => s"<${m.group(1)}-id>")

  final private class Step(
      val name: String,
      val method: String,
      val path: String,
      val reqBody: Option[String],
      val status: Int,
      val traceId: String,
      val contract: String,
      val body: String,
  ):
    def toJson: ujson.Value =
      def parsed(s: String): ujson.Value = scala.util.Try(ujson.read(s)).getOrElse(ujson.Str(s))
      ujson.Obj(
        "step"    -> name,
        "request" -> ujson.Obj(
          "method" -> method,
          "path"   -> path,
          "body"   -> reqBody.map(parsed).getOrElse(ujson.Null),
        ),
        "response" -> ujson.Obj(
          "status"   -> status,
          "traceId"  -> traceId,
          "contract" -> contract,
          "body"     -> parsed(body),
        ),
      )

  def main(args: Array[String]): Unit =
    val run  = args.headOption.getOrElse("run-1")
    val root = LocalFixturePaths.repoRoot
    val out  = root.resolve(s"modules/services/target/local-run/$run")
    Files.createDirectories(out)
    val logs                 = mutable.ListBuffer.empty[String]
    val sink: String => Unit = l => logs.synchronized { logs += l; () }
    val log                  = new JsonLog("query-api", sink)

    val base = ServiceSettings
      .fromEnv(Map("SCHOLARCHAIN_ENVIRONMENT" -> "local", "SCHOLARCHAIN_SERVICES_PORT" -> "0"))
      .fold(m => throw new IllegalStateException(m), identity)
    val rt = Composition
      .local(base, out.resolve("audit"))
      .fold(m => throw new IllegalStateException(m), identity)

    val syncApp = QueryApiApp.create(
      rt,
      base.copy(syncDeadlineMillis = 60000L),
      Some(DummyKey),
      new IdGen(true),
      log,
      "127.0.0.1",
    )
    val asyncApp = QueryApiApp.create(
      rt,
      base.copy(syncDeadlineMillis = 0L),
      Some(DummyKey),
      new IdGen(true),
      log,
      "127.0.0.1",
    )
    val syncPort  = syncApp.start()
    val asyncPort = asyncApp.start()
    val steps     = mutable.ListBuffer.empty[Step]
    val checks    = mutable.LinkedHashMap.empty[String, Boolean]

    def call(
        name: String,
        port: Int,
        method: String,
        path: String,
        body: Option[String] = None,
        key: Option[String] = Some(DummyKey),
        idem: Option[String] = None,
    ): Step =
      val b = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .timeout(Duration.ofSeconds(90))
        .header("Content-Type", "application/json")
      key.foreach(k => b.header("X-API-Key", k))
      idem.foreach(i => b.header("Idempotency-Key", i))
      val req = method match
        case "POST" => b.POST(HttpRequest.BodyPublishers.ofString(body.getOrElse(""))).build()
        case _      => b.GET().build()
      val r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      val s = new Step(
        name,
        method,
        path,
        body,
        r.statusCode,
        r.headers.firstValue("X-Trace-Id").orElse(""),
        r.headers.firstValue("X-ScholarChain-Contract").orElse(""),
        r.body,
      )
      steps += s
      s

    def js(s: Step): ujson.Value = ujson.read(s.body)

    try
      val health = call("health", syncPort, "GET", "/healthz", key = None)
      val ready  = call("readiness", syncPort, "GET", "/readyz", key = None)
      checks("health_200") = health.status == 200
      checks("readiness_200") = ready.status == 200

      val q1 = ujson.write(ujson.Obj("question" -> "What is the largest team size in the dataset?"))
      val sync =
        call("query_sync", syncPort, "POST", "/v1/query", Some(q1), idem = Some("session-key-1"))
      checks("sync_query_200") = sync.status == 200 && js(sync)("mode").str == "synchronous"
      val queryId = js(sync)("queryId").str
      val ev      = call("query_evidence", syncPort, "GET", s"/v1/queries/$queryId/evidence")
      checks("query_evidence_200") = ev.status == 200
      val firstEv = js(ev)("items").arr.headOption.map(_("evidenceId").str)
      checks("evidence_listed") = firstEv.isDefined
      firstEv.foreach(id =>
        checks("evidence_by_id_200") =
          call("evidence", syncPort, "GET", s"/v1/evidence/$id").status == 200
      )
      checks("explain_200") =
        call("explain", syncPort, "GET", s"/v1/explain/$queryId").status == 200

      val repeat = call(
        "query_idempotent_repeat",
        syncPort,
        "POST",
        "/v1/query",
        Some(q1),
        idem = Some("session-key-1"),
      )
      checks("idempotent_repeat_identical") = repeat.status == 200 && repeat.body == sync.body
      val q2       = ujson.write(ujson.Obj("question" -> "What is the biggest team size?"))
      val conflict = call(
        "query_conflict",
        syncPort,
        "POST",
        "/v1/query",
        Some(q2),
        idem = Some("session-key-1"),
      )
      checks("idempotency_conflict_409") = conflict.status == 409

      val async =
        call("query_async", asyncPort, "POST", "/v1/query", Some(q2), idem = Some("session-key-2"))
      checks("async_query_202") = async.status == 202
      val jobId = js(async)("jobId").str
      var last  = call("job_poll", asyncPort, "GET", s"/v1/jobs/$jobId")
      var n     = 0
      steps -= last
      while !Set("SUCCEEDED", "FAILED").contains(js(last)("state").str) && n < 600 do
        Thread.sleep(50)
        last = call("job_poll", asyncPort, "GET", s"/v1/jobs/$jobId")
        steps -= last
        n += 1
      steps += last
      checks("job_succeeded") = js(last)("state").str == "SUCCEEDED"
      val result = call("job_result", asyncPort, "GET", s"/v1/jobs/$jobId/result")
      checks("job_result_200") = result.status == 200 && js(result)("mode").str == "synchronous"
      val aQuery = js(async)("queryId").str
      checks("async_evidence_200") = call(
        "async_query_evidence",
        asyncPort,
        "GET",
        s"/v1/queries/$aQuery/evidence",
      ).status == 200
      checks("async_explain_200") =
        call("async_explain", asyncPort, "GET", s"/v1/explain/$aQuery").status == 200

      val csv = LocalCsv.read(
        LocalFixturePaths
          .default(out.resolve("audit"))
          .l4
          .resolve("neptune/edges/COAUTHORED_WITH.csv")
      )
      val node = csv.head(":START_ID")
      checks("node_200") = call("node", syncPort, "GET", s"/v1/graph/nodes/$node").status == 200
      val p1 = call(
        "neighbors_page_1",
        syncPort,
        "GET",
        s"/v1/graph/nodes/$node/neighbors?depth=2&limit=2",
      )
      checks("neighbors_200") = p1.status == 200
      js(p1).obj.get("nextCursor").foreach { c =>
        val p2 = call(
          "neighbors_page_2",
          syncPort,
          "GET",
          s"/v1/graph/nodes/$node/neighbors?depth=2&limit=2&cursor=${c.str}",
        )
        checks("neighbors_page_2_200") = p2.status == 200
      }
      checks("neighbors_depth_4_400") = call(
        "neighbors_depth_too_large",
        syncPort,
        "GET",
        s"/v1/graph/nodes/$node/neighbors?depth=4",
      ).status == 400

      val unauth = call("unauthorized", syncPort, "POST", "/v1/query", Some(q1), key = None)
      checks("unauthorized_401") = unauth.status == 401 && js(unauth)("traceId").str.nonEmpty
      val bad = call(
        "unsupported_metric",
        syncPort,
        "POST",
        "/v1/query",
        Some(
          ujson.write(
            ujson.Obj(
              "question" -> "Who has the most citations?",
              "analysis" -> ujson.Obj("rankingMetric" -> "CITATIONS"),
            )
          )
        ),
      )
      checks("unsupported_422") = bad.status == 422
    finally
      syncApp.stop()
      asyncApp.stop()

    val transcript = ujson.Obj(
      "run"    -> run,
      "checks" -> ujson.Obj.from(checks.toList.map((k, v) => k -> ujson.Bool(v))),
      "steps"  -> ujson.Arr.from(steps.toList.map(_.toJson)),
    )
    val text = ujson.write(transcript, indent = 2)
    Files.writeString(out.resolve("transcript.json"), text + "\n")
    Files.writeString(out.resolve("normalized.json"), normalize(text) + "\n")
    Files.writeString(
      out.resolve("logs.jsonl"),
      logs.synchronized(logs.toList).mkString("\n") + "\n",
    )
    val failed = checks.filterNot(_._2).keys.toList
    println(s"local session $run: ${steps.size} steps, ${checks.size} checks, failed: ${
        if failed.isEmpty then "none" else failed.mkString(",")
      }")
    println(s"transcript: ${root.relativize(out)}")
    if failed.nonEmpty then sys.exit(1)
