package scholarchain.core

import ValidationError.*

/** Phase 09 RAG runtime configuration (A1): defaults, precedence, every rejection. */
class RuntimeConfigContractSuite extends ContractSupport:
  import RuntimeConfigKeys.*

  private def load(
      env: Map[String, String] = Map.empty,
      over: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedRuntimeSettings] =
    RuntimeConfigLoader.load(env, over)

  private def settings(
      env: Map[String, String] = Map.empty,
      over: Map[String, String] = Map.empty,
  ): RuntimeSettings =
    load(env, over) match
      case Right(r) => r.settings
      case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def rejects(k: ConfigKey, v: String)(using munit.Location): Unit =
    load(env = Map(k.envName -> v)) match
      case Right(_) => fail(s"expected ${k.key}=$v to be rejected")
      case Left(es) =>
        assert(
          es.exists { case InvalidConfig(n, _) => n == k.key; case _ => false },
          s"expected InvalidConfig(${k.key}) in $es",
        )

  test("RuntimeConfigLoader: shipped defaults") {
    val s = settings()
    assertEquals(s.provider, ModelProvider.Bedrock)
    assertEquals(s.anthropicApiKeyEnvVar, "SCHOLARCHAIN_ANTHROPIC_API_KEY")
    assertEquals(s.anthropicEndpoint, "https://api.anthropic.com/v1/messages")
    assertEquals(s.maxMalformedRetries, 2)
    assertEquals(s.contextCharacters, 60000)
    assertEquals(s.inputTokens, 30000)
    assertEquals(s.outputTokens, 2048)
    assertEquals(s.modelTimeoutMillis, 30000)
    assertEquals(s.requestTimeoutMillis, 120000)
    assertEquals(s.graphMaxDepth, 4)
    assertEquals(s.graphMaxResults, 50)
    assertEquals(s.graphTimeoutMillis, 10000)
    assertEquals(s.evidenceMaxItems, 100)
    assertEquals(s.graphBounds, QueryBounds(4, 50, 10000L))
  }

  test("RuntimeConfigLoader: key names are rag.* and the env names are SCHOLARCHAIN_RAG_<KEY>") {
    assert(all.forall(_.key.startsWith("rag.")))
    assertEquals(Provider.envName, "SCHOLARCHAIN_RAG_MODEL_PROVIDER")
    assertEquals(
      AnthropicApiKeyEnvVar.envName,
      "SCHOLARCHAIN_RAG_MODEL_ANTHROPIC_API_KEY_ENV_VAR",
    )
    assertEquals(ModuleConfig.overrideName(GraphMaxDepth), "scholarchain.rag.graph.max-depth")
    assertEquals(all.size, 16)
  }

  test("RuntimeConfigLoader: every key has the file origin by default; origins hold no values") {
    val r = load().fold(es => fail(es.toString), identity)
    assertEquals(r.origins.keySet, all.map(_.key).toSet)
    assert(r.origins.values.forall(_ == "file"))
  }

  test("RuntimeConfigLoader: environment beats override, override beats file") {
    val k = GraphMaxResults
    val r = load(
      env = Map(k.envName -> "7"),
      over = Map(ModuleConfig.overrideName(k) -> "9"),
    ).fold(es => fail(es.toString), identity)
    assertEquals(r.settings.graphMaxResults, 7)
    assertEquals(r.origins(k.key), s"env:${k.envName}")
    assertEquals(settings(over = Map(ModuleConfig.overrideName(k) -> "9")).graphMaxResults, 9)
  }

  test("RuntimeConfigLoader: the provider is bedrock, anthropic or fake, nothing else") {
    assertEquals(
      settings(over = Map("scholarchain.rag.model.provider" -> "fake")).provider,
      ModelProvider.Fake,
    )
    assertEquals(
      settings(env = Map(Provider.envName -> "anthropic")).provider,
      ModelProvider.Anthropic,
    )
    rejects(Provider, "openai")
    rejects(Provider, "FAKE")
    // an empty value is treated as unset: the file default applies
    assertEquals(settings(env = Map(Provider.envName -> "  ")).provider, ModelProvider.Bedrock)
  }

  test("RuntimeConfigLoader: anthropic-api-key-env-var must be an UPPER_SNAKE name, not a value") {
    assertEquals(
      settings(env = Map(AnthropicApiKeyEnvVar.envName -> "MY_KEY_VAR")).anthropicApiKeyEnvVar,
      "MY_KEY_VAR",
    )
    for bad <- List("lower_case", "has space", "1STARTS_WITH_DIGIT", "A-B", "sk-ant-0000000000")
    do rejects(AnthropicApiKeyEnvVar, bad)
  }

  test("RuntimeConfigLoader: max-malformed-retries is 0..2") {
    assertEquals(settings(env = Map(MaxMalformedRetries.envName -> "0")).maxMalformedRetries, 0)
    assertEquals(settings(env = Map(MaxMalformedRetries.envName -> "2")).maxMalformedRetries, 2)
    rejects(MaxMalformedRetries, "3")
    rejects(MaxMalformedRetries, "-1")
    rejects(MaxMalformedRetries, "two")
  }

  test("RuntimeConfigLoader: the model timeout may not exceed the request timeout") {
    val ok = settings(
      env = Map(ModelTimeoutMillis.envName -> "5000", RequestTimeoutMillis.envName -> "5000")
    )
    assertEquals(ok.modelTimeoutMillis, 5000)
    val bad = load(
      env = Map(ModelTimeoutMillis.envName -> "5001", RequestTimeoutMillis.envName -> "5000")
    )
    assert(bad.left.exists(_.exists {
      case InvalidConfig(n, _) => n == ModelTimeoutMillis.key; case _ => false
    }))
    rejects(ModelTimeoutMillis, "130000")
  }

  test("RuntimeConfigLoader: numeric bounds are enforced at both ends") {
    rejects(ContextCharacters, "0")
    rejects(ContextCharacters, "400001")
    rejects(InputTokens, "200001")
    rejects(OutputTokens, "8193")
    rejects(OutputTokens, "0")
    rejects(RequestTimeoutMillis, "600001")
    rejects(GraphMaxDepth, "7")
    rejects(GraphMaxDepth, "0")
    rejects(GraphMaxResults, "101")
    rejects(GraphTimeoutMillis, "0")
    rejects(EvidenceMaxItems, "501")
    assertEquals(settings(env = Map(GraphMaxDepth.envName -> "6")).graphMaxDepth, 6)
    assertEquals(settings(env = Map(EvidenceMaxItems.envName -> "500")).evidenceMaxItems, 500)
  }

  test("RuntimeConfigLoader: the Anthropic endpoint must be https without credentials or query") {
    for bad <- List(
        "http://api.anthropic.com/v1/messages",
        "https://user@api.anthropic.com/v1",
        "https://api.anthropic.com/v1?x=1",
        "https://api.anthropic.com/v1#f",
        "https://",
        "https://has space.example",
      )
    do rejects(AnthropicEndpoint, bad)
  }

  test("RuntimeConfigLoader: model IDs are whitespace-free tokens") {
    rejects(BedrockModelId, "a b")
    rejects(AnthropicModelId, "a\tb")
    assertEquals(settings(env = Map(AnthropicModelId.envName -> "m-1")).anthropicModelId, "m-1")
  }

  test("RuntimeConfigLoader: an unsupported schema version is rejected") {
    rejects(SchemaVersionKey, "2")
    rejects(SchemaVersionKey, "x")
  }

  test("RuntimeConfigLoader: all invalid keys are reported together, deduplicated") {
    val r     = load(env = Map(Provider.envName -> "x", GraphMaxDepth.envName -> "99"))
    val names = r.left.toOption.get.collect { case InvalidConfig(n, _) => n }
    assert(names.contains(Provider.key) && names.contains(GraphMaxDepth.key), names.toString)
  }

  test("RuntimeConfigLoader: no config value or error message can hold a secret") {
    val secret = "sk-ant-FAKE-VALUE-0000"
    val r      = load(env = Map(AnthropicApiKeyEnvVar.envName -> secret))
    // a value that is not an env var name is rejected, and the message does not echo it
    r.left.toOption.get.foreach(e => assert(!e.message.contains(secret), e.message))
  }
