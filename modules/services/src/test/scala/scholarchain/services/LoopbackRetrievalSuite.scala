package scholarchain.services

import scholarchain.core.*
import scholarchain.core.rest.*

import ApiHarness.*
import RepairHarness.*

/** Phase 10 repair R4, F9 and F1: the production retrieval path on loopback. Retrieval
  * (`RetrievalRoutes` on `HttpApp`, port 0) is called by query-api's `RetrievalClient` and
  * `Remote*` ports wired through `Composition.guarded`. Dummy keys, localhost only. A failure is a
  * production defect in `modules/services` (owner service-engineer).
  */
class LoopbackRetrievalSuite extends munit.FunSuite:

  private val GraphQ =
    "Who are the direct coauthors of Antonina Laposhina, and how many joint publications do they share?"

  private def errorOf(r: Reply): String = r.json("error").str

  private def startRetrieval(handler: Req => Resp): (HttpApp, Int) =
    val app = new HttpApp(
      "retrieval",
      "127.0.0.1",
      0,
      new JsonLog("retrieval", _ => ()),
      new IdGen(true),
      RestLimits.MaxBodyBytes,
      handler,
    )
    (app, app.start())

  private def realRetrieval(rt: ServiceRuntime): (HttpApp, Int) =
    startRetrieval(new RetrievalRoutes(rt, new ApiKeyAuth(Some(InternalKey))).handle)

  /** The query-api runtime: kv and objects local, graph, vectors and embeddings remote and guarded.
    */
  private def queryRuntime(
      port: Int,
      key: String,
      clientTimeoutMillis: Long,
      downstreamMillis: Long,
  ): ServiceRuntime =
    val client = new RetrievalClient(s"http://127.0.0.1:$port", key, clientTimeoutMillis)
    Composition.guarded(
      cfg().copy(downstreamTimeoutMillis = downstreamMillis),
      base.settings,
      base.tables,
      base.kv,
      new RemoteGraphStore(client),
      new RemoteVectorStore(client),
      base.objects,
      new RemoteEmbedder(
        client,
        base.embedder.modelId,
        base.embedder.modelVersion,
        base.embedder.dimension,
      ),
      base.model,
      "local",
    )

  private def withLoopback[A](
      retrieval: (HttpApp, Int),
      key: String,
      clientTimeoutMillis: Long = 4000L,
      downstreamMillis: Long = 5000L,
  )(f: Tuned => A): A =
    val (app, port) = retrieval
    val s = new Tuned(queryRuntime(port, key, clientTimeoutMillis, downstreamMillis), cfg())
    try f(s)
    finally
      s.stop()
      app.stop()

  test("F9: happy path, query-api reads graph, vectors and embeddings through retrieval") {
    withLoopback(realRetrieval(base), InternalKey) { s =>
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 200, r.body)
      assertEquals(r.json("abstained").bool, false, r.body)
      assert(r.json("claims").arr.nonEmpty, r.body)
      assert(RestJson.read[SyncQueryResponse](r.body).isRight, r.body)
    }
  }

  test("F1: a wrong internal key is 502 DOWNSTREAM_FAILURE, not a 200 abstention") {
    withLoopback(realRetrieval(base), "dummy-wrong-internal-key-0009") { s =>
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 502, r.body)
      assertEquals(errorOf(r), "DOWNSTREAM_FAILURE")
    }
  }

  test("F1: a retrieval 500 is 502 DOWNSTREAM_FAILURE") {
    val stub =
      startRetrieval(req => ApiFailure(ApiErrorCode.Internal, "stub failure").toResp(req.traceId))
    withLoopback(stub, InternalKey) { s =>
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 502, r.body)
      assertEquals(errorOf(r), "DOWNSTREAM_FAILURE")
    }
  }

  test("F1: a retrieval whose graph port fails is 502 through the real routes") {
    val failing = withPorts(graph = new FailingGraph(base.graph))
    withLoopback(realRetrieval(failing), InternalKey) { s =>
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 502, r.body)
    }
  }

  test("F1: a slow retrieval is 504 TIMEOUT") {
    val slow = withPorts(graph = new SlowGraph(base.graph, 2000L))
    withLoopback(
      realRetrieval(slow),
      InternalKey,
      clientTimeoutMillis = 200L,
      downstreamMillis = 400L,
    ) { s =>
      val r = s.post("/v1/query", query(GraphQ))
      assertEquals(r.status, 504, r.body)
      assertEquals(errorOf(r), "TIMEOUT")
    }
  }
