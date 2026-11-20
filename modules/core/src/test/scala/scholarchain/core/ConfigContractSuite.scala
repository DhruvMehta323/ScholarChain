package scholarchain.core

import ValidationError.*

class ConfigContractSuite extends ContractSupport:

  /** Obviously synthetic values only. */
  private val base: Map[String, String] = Map(
    "schema-version"                     -> "1",
    "environment"                        -> "test",
    "aws.region"                         -> "test-region-1",
    "storage.raw-bucket"                 -> "test-raw",
    "storage.curated-bucket"             -> "test-curated",
    "storage.vector-bucket"              -> "test-vectors",
    "storage.vector-index"               -> "test-index",
    "storage.dynamo-table-prefix"        -> "test-",
    "storage.neptune-endpoint"           -> "neptune.test.invalid",
    "storage.opensearch-endpoint"        -> "search.test.invalid",
    "models.embedding-model-id"          -> "test-embed-model",
    "models.generation-model-id"         -> "test-gen-model",
    "graph.window.start-year"            -> "2000",
    "graph.window.end-year"              -> "2004",
    "graph.coauthor-policy-version"      -> "p1",
    "graph.bridge.algorithm"             -> "test-bridge",
    "graph.bridge.parameters"            -> """{"k":3,"flag":true,"name":"x"}""",
    "entity-resolution.policy-version"   -> "er-test",
    "entity-resolution.match-threshold"  -> "0.9",
    "entity-resolution.review-threshold" -> "0.5",
    "retrieval.top-k"                    -> "5",
  )

  private def envName(name: String): String =
    ConfigKeys.all.find { case ConfigKey(n, _) => n == name }.get.envName

  private def invalidKeys(r: Either[List[ValidationError], ResolvedConfig]): List[String] =
    r.swap.getOrElse(Nil).collect { case InvalidConfig(k, _) => k }

  private def ok(e: Either[List[ValidationError], ResolvedConfig]): ResolvedConfig = e match
    case Right(a) => a
    case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def bad(k: String, v: String) = ConfigResolver.resolve(Map.empty, base.updated(k, v))

  test("baseline: the file layer alone resolves to the typed configuration") {
    val rc = ok(ConfigResolver.resolve(Map.empty, base))
    val c  = rc.config
    assertEquals(c.schemaVersion, 1)
    assertEquals(c.environment, "test")
    assertEquals(c.awsRegion, "test-region-1")
    assertEquals(c.storage.rawBucket, "test-raw")
    assertEquals(c.storage.curatedBucket, "test-curated")
    assertEquals(c.storage.openSearchEndpoint, Some("search.test.invalid"))
    assertEquals(c.graph.window, GraphWindow(2000, 2004))
    assertEquals(c.graph.coauthorPolicyVersion, "p1")
    assertEquals(c.graph.bridge.algorithm, "test-bridge")
    assertEquals(c.graph.bridge.parameters, Map("k" -> "3", "flag" -> "true", "name" -> "x"))
    assertEquals(c.entityResolution, EntityResolutionConfig("er-test", 0.9, 0.5))
    assertEquals(c.retrieval.topK, 5)
    assert(rc.origins.nonEmpty && rc.origins.values.forall(_ == "file"), rc.origins.toString)
  }

  test("precedence: env overrides file and records its origin without the value") {
    val rc = ok(ConfigResolver.resolve(Map("SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR" -> "1999"), base))
    assertEquals(rc.config.graph.window, GraphWindow(1999, 2004))
    assertEquals(rc.origins("graph.window.start-year"), "env:SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR")
    assertEquals(rc.origins("graph.window.end-year"), "file")
  }

  test("precedence: a key present only in env resolves from env") {
    val rc = ok(
      ConfigResolver.resolve(Map("SCHOLARCHAIN_RETRIEVAL_TOP_K" -> "7"), base - "retrieval.top-k")
    )
    assertEquals(rc.config.retrieval.topK, 7)
    assertEquals(rc.origins("retrieval.top-k"), "env:SCHOLARCHAIN_RETRIEVAL_TOP_K")
  }

  test("precedence: blank env values fall back to the file") {
    List("", "  ").foreach { blank =>
      val rc = ok(ConfigResolver.resolve(Map("SCHOLARCHAIN_RETRIEVAL_TOP_K" -> blank), base))
      assertEquals(rc.config.retrieval.topK, 5)
      assertEquals(rc.origins("retrieval.top-k"), "file")
    }
  }

  test("precedence: an env var without the SCHOLARCHAIN_ prefix is ignored") {
    val rc = ok(ConfigResolver.resolve(Map("GRAPH_WINDOW_START_YEAR" -> "1999"), base))
    assertEquals(rc.config.graph.window.startYear, 2000)
  }

  test("precedence: an invalid env value is not masked by a valid file value") {
    val r = ConfigResolver.resolve(Map("SCHOLARCHAIN_RETRIEVAL_TOP_K" -> "x"), base)
    assertEquals(invalidKeys(r), List("retrieval.top-k"))
  }

  test("values are trimmed") {
    val rc =
      ok(ConfigResolver.resolve(Map.empty, base.updated("storage.raw-bucket", "  test-raw \n")))
    assertEquals(rc.config.storage.rawBucket, "test-raw")
  }

  test("an env-only configuration resolves with an empty file layer") {
    val env = base.map((k, v) => envName(k) -> v)
    val rc  = ok(ConfigResolver.resolve(env, Map.empty))
    assertEquals(rc.config.storage.curatedBucket, "test-curated")
    assert(rc.origins.values.forall(_.startsWith("env:SCHOLARCHAIN_")), rc.origins.toString)
  }

  test("env names: 21 distinct keys, derived by upper-casing and replacing . and -") {
    assertEquals(ConfigKeys.all.map(_.envName).distinct.size, 21)
    assertEquals(ConfigKeys.WindowStartYear.envName, "SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR")
    assertEquals(ConfigKeys.RawBucket.envName, "SCHOLARCHAIN_STORAGE_RAW_BUCKET")
  }

  test("missing: all missing required keys are reported together, naming each") {
    val r = ConfigResolver.resolve(Map.empty, base - "retrieval.top-k" - "aws.region")
    assertEquals(invalidKeys(r).sorted, List("aws.region", "retrieval.top-k"))
    assertEquals(r.swap.getOrElse(Nil).size, 2)
  }

  test("missing: the opensearch endpoint is optional") {
    val rc = ok(ConfigResolver.resolve(Map.empty, base - "storage.opensearch-endpoint"))
    assertEquals(rc.config.storage.openSearchEndpoint, None)
  }

  test("invalid: schema version") {
    assertEquals(bad("schema-version", "2"), Left(List(UnsupportedSchemaVersion(2, Set(1)))))
    assertEquals(invalidKeys(bad("schema-version", "x")), List("schema-version"))
  }

  test("invalid: graph window years") {
    val inverted =
      base.updated("graph.window.start-year", "2005").updated("graph.window.end-year", "2000")
    assertEquals(ConfigResolver.resolve(Map.empty, inverted), Left(List(InvalidWindow(2005, 2000))))
    assertEquals(
      invalidKeys(bad("graph.window.start-year", "abc")),
      List("graph.window.start-year"),
    )
    assertEquals(invalidKeys(bad("graph.window.end-year", "20.5")), List("graph.window.end-year"))
  }

  test("invalid: thresholds") {
    List("1.5", "-0.1", "abc").foreach { v =>
      assertEquals(
        invalidKeys(bad("entity-resolution.match-threshold", v)),
        List("entity-resolution.match-threshold"),
        v,
      )
    }
    assertEquals(
      invalidKeys(bad("entity-resolution.review-threshold", "0.95")),
      List("entity-resolution.review-threshold"),
    )
  }

  test("invalid: top-k") {
    assertEquals(invalidKeys(bad("retrieval.top-k", "0")), List("retrieval.top-k"))
    assertEquals(invalidKeys(bad("retrieval.top-k", "x")), List("retrieval.top-k"))
  }

  test("invalid: bucket names must be bare tokens") {
    List("s3://b", "a/b", "my bucket").foreach { v =>
      assertEquals(invalidKeys(bad("storage.raw-bucket", v)), List("storage.raw-bucket"), v)
    }
  }

  test("invalid: bridge parameters must be a flat JSON object of scalars") {
    List("[1]", "not json", """{"a":[1]}""").foreach { v =>
      assertEquals(
        invalidKeys(bad("graph.bridge.parameters", v)),
        List("graph.bridge.parameters"),
        v,
      )
    }
  }

  test("no leakage: error messages name the config key and never echo the offending value") {
    val cases = List(
      "storage.raw-bucket"                -> "s3://leaky-value-1",
      "entity-resolution.match-threshold" -> "leaky-value-2",
      "retrieval.top-k"                   -> "leaky-value-3",
      "graph.bridge.parameters"           -> "leaky-value-4",
      "graph.window.start-year"           -> "leaky-value-5",
    )
    cases.foreach { (k, v) =>
      val errs = bad(k, v).swap.getOrElse(Nil)
      assertEquals(errs.size, 1, k)
      errs.foreach { e =>
        assert(e.message.contains(k), e.message)
        assert(!e.message.contains(v), e.message)
      }
    }
  }
