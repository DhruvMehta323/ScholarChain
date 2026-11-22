package scholarchain.core

import ValidationError.*

/** Phase 08 embedding configuration (A1): defaults, precedence, rejections. */
class EmbeddingConfigContractSuite extends ContractSupport:

  private def load(
      env: Map[String, String] = Map.empty,
      over: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedEmbeddingSettings] =
    EmbeddingConfigLoader.load(env, over)

  private def settings(
      env: Map[String, String] = Map.empty,
      over: Map[String, String] = Map.empty,
  ): EmbeddingSettings =
    load(env, over) match
      case Right(r) => r.settings
      case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def rejects(cfgKey: ConfigKey, v: String)(using munit.Location): Unit =
    load(env = Map(cfgKey.envName -> v)) match
      case Right(_) => fail(s"expected ${cfgKey.key}=$v to be rejected")
      case Left(es) =>
        assert(
          es.exists { case InvalidConfig(n, _) => n == cfgKey.key; case _ => false },
          s"expected InvalidConfig(${cfgKey.key}) in $es",
        )

  test("EmbeddingConfigLoader: shipped defaults") {
    val s = settings()
    assertEquals(s.modelId, "amazon.titan-embed-text-v2:0")
    assertEquals(s.modelVersion, "v2")
    assertEquals(s.dimension, 1024)
    assertEquals(s.voyageApiKeyEnvVar, "SCHOLARCHAIN_VOYAGE_API_KEY")
    assertEquals(s.queueName, "scholarchain-embedding-jobs")
    assertEquals(s.deadLetterName, "scholarchain-embedding-jobs-dlq")
    assertEquals(s.maxAttempts, 3)
    assertEquals(s.maxDocumentsPerJob, 50)
    assertEquals(s.testVectorIndex, "scholarchain-it-embedding-test")
  }

  test("EmbeddingConfigLoader: every key has the file origin by default; origins hold no values") {
    val r = load().fold(es => fail(es.toString), identity)
    assertEquals(r.origins.keySet, EmbeddingConfigKeys.all.map(_.key).toSet)
    assert(r.origins.values.forall(_ == "file"))
  }

  test("EmbeddingConfigLoader: environment beats override, override beats file") {
    val k = EmbeddingConfigKeys.MaxAttempts
    val r = load(
      env = Map(k.envName -> "4"),
      over = Map(ModuleConfig.overrideName(k) -> "5"),
    ).fold(es => fail(es.toString), identity)
    assertEquals(r.settings.maxAttempts, 4)
    assertEquals(r.origins(k.key), s"env:${k.envName}")
    assertEquals(settings(over = Map(ModuleConfig.overrideName(k) -> "5")).maxAttempts, 5)
  }

  test("EmbeddingConfigKeys: environment names use the SCHOLARCHAIN_EMBEDDING_ prefix") {
    assertEquals(
      EmbeddingConfigKeys.ModelDimension.envName,
      "SCHOLARCHAIN_EMBEDDING_MODEL_DIMENSION",
    )
    assertEquals(
      EmbeddingConfigKeys.TestVectorIndex.envName,
      "SCHOLARCHAIN_EMBEDDING_TEST_VECTOR_INDEX_NAME",
    )
  }

  test("EmbeddingConfigLoader: the dimension must be in 1..4096") {
    rejects(EmbeddingConfigKeys.ModelDimension, "0")
    rejects(EmbeddingConfigKeys.ModelDimension, "4097")
    rejects(EmbeddingConfigKeys.ModelDimension, "abc")
    val dimEnv = EmbeddingConfigKeys.ModelDimension.envName
    assertEquals(settings(env = Map(dimEnv -> "256")).dimension, 256)
    assertEquals(settings(env = Map(dimEnv -> "4096")).dimension, 4096)
  }

  test("EmbeddingConfigLoader: max attempts is 1..10 and documents per job is 1..50") {
    rejects(EmbeddingConfigKeys.MaxAttempts, "0")
    rejects(EmbeddingConfigKeys.MaxAttempts, "11")
    rejects(EmbeddingConfigKeys.MaxDocumentsPerJob, "0")
    rejects(EmbeddingConfigKeys.MaxDocumentsPerJob, "51")
  }

  test("EmbeddingConfigLoader: model id and version are tokens (no whitespace)") {
    rejects(EmbeddingConfigKeys.ModelId, "has space")
    rejects(EmbeddingConfigKeys.ModelVersion, "v 2")
  }

  test("EmbeddingConfigLoader: the Voyage setting is an environment variable NAME, not a value") {
    rejects(EmbeddingConfigKeys.VoyageApiKeyEnvVar, "pa-secret-looking-value-123")
    rejects(EmbeddingConfigKeys.VoyageApiKeyEnvVar, "lowercase_name")
    rejects(EmbeddingConfigKeys.VoyageApiKeyEnvVar, "1STARTS_WITH_DIGIT")
    val nameEnv = EmbeddingConfigKeys.VoyageApiKeyEnvVar.envName
    assertEquals(settings(env = Map(nameEnv -> "MY_KEY_NAME")).voyageApiKeyEnvVar, "MY_KEY_NAME")
  }

  test("EmbeddingConfigLoader: queue and dead-letter names must differ and be SQS-style names") {
    val same = Map(
      EmbeddingConfigKeys.QueueName.envName      -> "same-queue",
      EmbeddingConfigKeys.DeadLetterName.envName -> "same-queue",
    )
    load(env = same) match
      case Left(es) =>
        assert(es.exists {
          case InvalidConfig(n, _) => n == "embedding.queue.dead-letter-name"
          case _                   => false
        })
      case Right(_) => fail("equal queue names must be rejected")
    rejects(EmbeddingConfigKeys.QueueName, "bad name!")
    rejects(EmbeddingConfigKeys.QueueName, "q" * 81)
  }

  test("EmbeddingConfigLoader: the test index is never a production index") {
    VectorIndexes.all.foreach(i => rejects(EmbeddingConfigKeys.TestVectorIndex, i))
  }

  test(
    "EmbeddingConfigLoader: the test index name must be 3..63 lowercase letters, digits or '-'"
  ) {
    rejects(EmbeddingConfigKeys.TestVectorIndex, "ab")
    rejects(EmbeddingConfigKeys.TestVectorIndex, "Upper-Case-Index")
    rejects(EmbeddingConfigKeys.TestVectorIndex, "under_score")
    rejects(EmbeddingConfigKeys.TestVectorIndex, "a" * 64)
  }

  test("EmbeddingConfigLoader: errors from several keys are all reported") {
    val es = load(
      env = Map(
        EmbeddingConfigKeys.MaxAttempts.envName    -> "99",
        EmbeddingConfigKeys.ModelDimension.envName -> "0",
      )
    ).left.getOrElse(fail("expected Left"))
    assert(es.size >= 2, es.toString)
  }

  test("EmbeddingSettings.checkTestIndex: rejects a production or configured storage index name") {
    val vectors = StorageConfigLoader
      .load(Map.empty, Map.empty)
      .fold(es => fail(es.toString), _.config.vectors)
    VectorIndexes.all.foreach(i => assert(EmbeddingSettings.checkTestIndex(i, vectors).isLeft, i))
    vectors.indexes.foreach(i => assert(EmbeddingSettings.checkTestIndex(i, vectors).isLeft, i))
    assertEquals(
      EmbeddingSettings.checkTestIndex("scholarchain-it-embedding-test", vectors),
      Right("scholarchain-it-embedding-test"),
    )
    val custom = vectors.copy(researcherProfileIndex = "custom-index-name")
    assert(EmbeddingSettings.checkTestIndex("custom-index-name", custom).isLeft)
  }

  test("EmbeddingConfigLoader.resolve: a pure resolution over explicit layers needs no classpath") {
    val file = Map(
      "embedding.schema-version"              -> "1",
      "embedding.model.id"                    -> "m",
      "embedding.model.version"               -> "v",
      "embedding.model.dimension"             -> "8",
      "embedding.voyage.api-key-env-var"      -> "ENV_NAME",
      "embedding.queue.name"                  -> "jobs",
      "embedding.queue.dead-letter-name"      -> "dlq",
      "embedding.queue.max-attempts"          -> "2",
      "embedding.queue.max-documents-per-job" -> "5",
      "embedding.test.vector-index-name"      -> "my-test-index",
    )
    val r = EmbeddingConfigLoader
      .resolve(Map.empty, Map.empty, file)
      .fold(es => fail(es.toString), identity)
    assertEquals(r.settings.dimension, 8)
    assertEquals(r.settings.testVectorIndex, "my-test-index")
  }

  // ---- vector-key claims table (Phase 08 repair 01, M3) -------------------------------------------------

  private val claimsFile = Map(
    "embedding.schema-version"              -> "1",
    "embedding.model.id"                    -> "m",
    "embedding.model.version"               -> "v",
    "embedding.model.dimension"             -> "8",
    "embedding.voyage.api-key-env-var"      -> "ENV_NAME",
    "embedding.queue.name"                  -> "jobs",
    "embedding.queue.dead-letter-name"      -> "dlq",
    "embedding.queue.max-attempts"          -> "2",
    "embedding.queue.max-documents-per-job" -> "5",
    "embedding.test.vector-index-name"      -> "my-test-index",
  )

  test("claims table: the config key, the environment name and the shipped default") {
    assertEquals(EmbeddingConfigKeys.ClaimsTableName.key, "embedding.claims.table-name")
    assertEquals(
      EmbeddingConfigKeys.ClaimsTableName.envName,
      "SCHOLARCHAIN_EMBEDDING_CLAIMS_TABLE_NAME",
    )
    assert(EmbeddingConfigKeys.all.contains(EmbeddingConfigKeys.ClaimsTableName))
    assertEquals(EmbeddingSettings.DefaultClaimsTableName, "scholarchain-embedding-key-claims")
    assertEquals(settings().claimsTableName, "scholarchain-embedding-key-claims")
  }

  test("claims table: the environment beats the override, the override beats the file default") {
    val k = EmbeddingConfigKeys.ClaimsTableName
    val r = load(
      env = Map(k.envName -> "env-claims-table"),
      over = Map(ModuleConfig.overrideName(k) -> "override-claims-table"),
    ).fold(es => fail(es.toString), identity)
    assertEquals(r.settings.claimsTableName, "env-claims-table")
    assertEquals(r.origins(k.key), s"env:${k.envName}")
    assertEquals(
      settings(over = Map(ModuleConfig.overrideName(k) -> "override-claims-table")).claimsTableName,
      "override-claims-table",
    )
  }

  test("claims table: valid names are accepted, including the 3 and 255 character bounds") {
    val k = EmbeddingConfigKeys.ClaimsTableName
    List("abc", "My_claims.table-1", "a" * 255, "UPPER_and.lower-9").foreach { n =>
      assertEquals(settings(env = Map(k.envName -> n)).claimsTableName, n)
    }
  }

  test("claims table: invalid names are rejected as InvalidConfig on the claims entry") {
    val k = EmbeddingConfigKeys.ClaimsTableName
    List("ab", "a" * 256, "has space", "slash/name", "dollar$name", "colon:name", "naive-tablé")
      .foreach(rejects(k, _))
  }

  test("claims table: a blank environment value is ignored like any blank value (default stays)") {
    val k = EmbeddingConfigKeys.ClaimsTableName
    assertEquals(
      settings(env = Map(k.envName -> "   ")).claimsTableName,
      "scholarchain-embedding-key-claims",
    )
    assertEquals(
      settings(env = Map(k.envName -> "")).claimsTableName,
      "scholarchain-embedding-key-claims",
    )
    assertEquals(settings(env = Map(k.envName -> "  padded-name  ")).claimsTableName, "padded-name")
  }

  test("claims table: an invalid claims name is reported together with other bad entries") {
    val es = load(
      env = Map(
        EmbeddingConfigKeys.ClaimsTableName.envName -> "x",
        EmbeddingConfigKeys.MaxAttempts.envName     -> "99",
      )
    ).left.toOption.getOrElse(fail("expected errors"))
    val names = es.collect { case InvalidConfig(n, _) => n }.toSet
    assert(names.contains("embedding.claims.table-name"), names.toString)
    assert(names.contains(EmbeddingConfigKeys.MaxAttempts.key), names.toString)
  }

  test("claims table: resolve fills the default when an explicit file layer lacks the entry") {
    assert(!claimsFile.contains("embedding.claims.table-name"))
    val r = EmbeddingConfigLoader
      .resolve(Map.empty, Map.empty, claimsFile)
      .fold(es => fail(es.toString), identity)
    assertEquals(r.settings.claimsTableName, "scholarchain-embedding-key-claims")
  }

  test("claims table: resolve keeps an explicit file value and still validates it") {
    val file = claimsFile.updated("embedding.claims.table-name", "file-claims-table")
    assertEquals(
      EmbeddingConfigLoader
        .resolve(Map.empty, Map.empty, file)
        .fold(es => fail(es.toString), _.settings.claimsTableName),
      "file-claims-table",
    )
    assert(
      EmbeddingConfigLoader
        .resolve(Map.empty, Map.empty, file.updated("embedding.claims.table-name", "x!"))
        .isLeft
    )
  }

  test("claims table: EmbeddingSettings defaults the claims name to the shipped constant") {
    val s = EmbeddingSettings(1, "m", "v", 8, "ENV_NAME", "jobs", "dlq", 2, 5, "my-test-index")
    assertEquals(s.claimsTableName, EmbeddingSettings.DefaultClaimsTableName)
  }
