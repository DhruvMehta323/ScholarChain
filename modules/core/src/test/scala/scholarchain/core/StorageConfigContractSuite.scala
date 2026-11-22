package scholarchain.core

import ValidationError.*

/** Phase 07 storage configuration, query bounds, object locations and storage errors. */
class StorageConfigContractSuite extends ContractSupport:

  private def load(
      env: Map[String, String] = Map.empty,
      over: Map[String, String] = Map.empty,
  ): Either[List[ValidationError], ResolvedStorageModuleConfig] =
    StorageConfigLoader.load(env, over)

  private def cfg(
      env: Map[String, String] = Map.empty,
      over: Map[String, String] = Map.empty,
  ): StorageModuleConfig =
    load(env, over) match
      case Right(r) => r.config
      case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def errs(
      env: Map[String, String] = Map.empty,
      over: Map[String, String] = Map.empty,
  ): List[ValidationError] =
    load(env, over) match
      case Left(es) => es
      case Right(_) => fail("expected Left but got Right")

  private def envOf(k: ConfigKey): String = k.envName

  private def rejectsEnv(k: ConfigKey, v: String)(using munit.Location): Unit =
    val es = errs(env = Map(envOf(k) -> v))
    assert(
      es.exists { case InvalidConfig(n, _) => n == k.key; case _ => false },
      s"expected InvalidConfig(${k.key}) in $es",
    )

  // ---- defaults and precedence ----------------------------------------------------------------

  test("StorageConfigLoader: shipped defaults load and every key has the file origin") {
    val r = load() match
      case Right(r) => r
      case Left(es) => fail(es.map(_.message).toString)
    val c = r.config
    assertEquals(c.s3.all, List("raw/", "curated/", "graph/", "rag/", "reports/", "logs/"))
    assertEquals(c.neptune.format, "opencypher")
    assertEquals(c.queryBounds, QueryBounds(6, 100, 5000L))
    assertEquals(c.retry, StorageRetry(5, 100L, 5000L))
    assertEquals(c.vectors.indexes, VectorIndexes.all)
    assertEquals(c.tables.all.distinct.size, 5)
    assertEquals(r.origins.keySet, StorageConfigKeys.all.map(_.key).toSet)
    assert(r.origins.values.forall(_ == "file"))
  }

  test(
    "StorageConfigLoader: shipped defaults are placeholders (dummy account, placeholder bucket)"
  ) {
    val c = cfg()
    assert(c.neptune.roleArn.contains("000000000000"))
    assert(c.neptune.sourceS3Uri.contains("placeholder"))
    assert(c.vectors.bucketName.contains("placeholder"))
  }

  test("StorageConfigLoader: environment beats override, override beats file") {
    val k = StorageConfigKeys.QueryMaxDepth
    val r = load(env = Map(envOf(k) -> "3"), over = Map(ModuleConfig.overrideName(k) -> "4"))
      .fold(es => fail(es.toString), identity)
    assertEquals(r.config.queryBounds.maxDepth, 3)
    assertEquals(r.origins(k.key), s"env:${envOf(k)}")
    val r2 =
      load(over = Map(ModuleConfig.overrideName(k) -> "4")).fold(es => fail(es.toString), identity)
    assertEquals(r2.config.queryBounds.maxDepth, 4)
    assertEquals(r2.origins(k.key), s"override:scholarchain.storage.query.max-depth")
  }

  test("StorageConfigLoader: a blank environment value falls back to the lower layer") {
    val k = StorageConfigKeys.QueryMaxResults
    assertEquals(cfg(env = Map(envOf(k) -> "   ")).queryBounds.maxResults, 100)
  }

  test("StorageConfigLoader: environment variable names follow SCHOLARCHAIN_STORAGE_<KEY>") {
    assertEquals(
      StorageConfigKeys.RetryMaxAttempts.envName,
      "SCHOLARCHAIN_STORAGE_RETRY_MAX_ATTEMPTS",
    )
    assertEquals(
      StorageConfigKeys.NeptuneLoadRoleArn.envName,
      "SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_ROLE_ARN",
    )
  }

  // ---- rejections -----------------------------------------------------------------------------

  test("StorageConfigLoader: retry caps (attempts <= 10, delay <= 60000, base <= max)") {
    rejectsEnv(StorageConfigKeys.RetryMaxAttempts, "11")
    rejectsEnv(StorageConfigKeys.RetryMaxAttempts, "0")
    rejectsEnv(StorageConfigKeys.RetryMaxDelayMillis, "60001")
    rejectsEnv(StorageConfigKeys.RetryBaseDelayMillis, "0")
    // base above the default max of 5000: the ordering error is reported on the max-delay key
    rejectsEnv(StorageConfigKeys.RetryMaxDelayMillis, "50") // below the default base of 100
    assertEquals(
      cfg(env = Map(envOf(StorageConfigKeys.RetryMaxAttempts) -> "10")).retry.maxAttempts,
      10,
    )
  }

  test("StorageConfigLoader: query bounds must be positive integers") {
    rejectsEnv(StorageConfigKeys.QueryMaxDepth, "0")
    rejectsEnv(StorageConfigKeys.QueryMaxResults, "-1")
    rejectsEnv(StorageConfigKeys.QueryTimeoutMillis, "0")
    rejectsEnv(StorageConfigKeys.QueryTimeoutMillis, "abc")
  }

  test("StorageConfigLoader: prefixes must be relative, end in '/', and be distinct") {
    rejectsEnv(StorageConfigKeys.S3Raw, "raw")
    rejectsEnv(StorageConfigKeys.S3Raw, "/raw/")
    rejectsEnv(StorageConfigKeys.S3Raw, "a/../b/")
    rejectsEnv(StorageConfigKeys.S3Raw, "a//b/")
    rejectsEnv(StorageConfigKeys.S3Raw, "a b/")
    rejectsEnv(StorageConfigKeys.S3Raw, "curated/") // duplicates the curated prefix
  }

  test("StorageConfigLoader: role ARN, load format and source URI shapes") {
    rejectsEnv(StorageConfigKeys.NeptuneLoadRoleArn, "not-an-arn")
    rejectsEnv(StorageConfigKeys.NeptuneLoadRoleArn, "arn:aws:iam::123:role/x")
    rejectsEnv(StorageConfigKeys.NeptuneLoadFormat, "ntriples")
    rejectsEnv(StorageConfigKeys.NeptuneLoadSource, "s3://placeholder-scholarchain-bucket/graph")
    rejectsEnv(StorageConfigKeys.NeptuneLoadSource, "https://x/y/")
    assertEquals(
      cfg(env = Map(envOf(StorageConfigKeys.NeptuneLoadFormat) -> "csv")).neptune.format,
      "csv",
    )
  }

  test("StorageConfigLoader: table names and index names are validated and distinct") {
    rejectsEnv(StorageConfigKeys.TableRunLedger, "ab")
    rejectsEnv(StorageConfigKeys.TableRunLedger, "bad name")
    rejectsEnv(StorageConfigKeys.TableRunLedger, "scholarchain-ArtifactRegistry")
    rejectsEnv(StorageConfigKeys.IndexResearcher, "Upper-Case")
    rejectsEnv(StorageConfigKeys.IndexResearcher, "collaboration-community-v1")
    rejectsEnv(StorageConfigKeys.VectorsBucketName, "x")
  }

  test("StorageConfigLoader: an unsupported schema version is rejected") {
    rejectsEnv(StorageConfigKeys.SchemaVersionKey, "99")
    rejectsEnv(StorageConfigKeys.SchemaVersionKey, "one")
  }

  test("StorageConfigLoader: error messages never contain the offending values") {
    val secretish = "TOPSECRET-arn-value-9876"
    val es        = errs(
      env = Map(
        envOf(StorageConfigKeys.NeptuneLoadRoleArn) -> secretish,
        envOf(StorageConfigKeys.RetryMaxAttempts)   -> "99999",
        envOf(StorageConfigKeys.S3Raw)              -> secretish,
      )
    )
    assert(es.size >= 3)
    es.foreach { e =>
      assert(!e.message.contains(secretish), e.message)
      assert(!e.message.contains("99999"), e.message)
    }
  }

  test("StorageConfigLoader: all errors are reported together") {
    val es = errs(
      env = Map(
        envOf(StorageConfigKeys.QueryMaxDepth)   -> "0",
        envOf(StorageConfigKeys.QueryMaxResults) -> "0",
      )
    )
    assert(es.size >= 2)
  }

  // ---- QueryBounds ----------------------------------------------------------------------------

  test("QueryBounds.validate accepts minimal bounds and rejects zero or negative values") {
    assertEquals(QueryBounds.validate(QueryBounds(1, 1, 1L)), Right(QueryBounds(1, 1, 1L)))
    expectErr(QueryBounds.validate(QueryBounds(0, 1, 1L))) { case InvalidField("maxDepth", _) =>
      ()
    }
    expectErr(QueryBounds.validate(QueryBounds(1, 0, 1L))) { case InvalidField("maxResults", _) =>
      ()
    }
    expectErr(QueryBounds.validate(QueryBounds(1, 1, 0L))) {
      case InvalidField("timeoutMillis", _) =>
        ()
    }
    assert(QueryBounds.validate(QueryBounds(-1, -1, -1L)).isLeft)
  }

  // ---- ObjectLocation -------------------------------------------------------------------------

  test("ObjectLocation.parse splits bucket and key and round trips through uri") {
    val l = right(ObjectLocation.parse("s3://my-bucket/a/b/c.txt"))
    assertEquals(l, ObjectLocation("my-bucket", "a/b/c.txt"))
    assertEquals(l.uri, "s3://my-bucket/a/b/c.txt")
  }

  test("ObjectLocation.parse rejects malformed URIs") {
    List("my-bucket/a", "s3://my-bucket", "s3://my-bucket/", "s3://-b/x", "s3://B/x", "s3:///x", "")
      .foreach(u => assert(ObjectLocation.parse(u).isLeft, u))
    expectErr(ObjectLocation.parse("nope")) { case InvalidField("uri", _) => () }
  }

  // ---- StorageError ---------------------------------------------------------------------------

  test("StorageError.retryable is true only for Throttled and Unavailable") {
    import StorageError.*
    assert(Throttled("x").retryable)
    assert(Unavailable("x").retryable)
    List[StorageError](
      NotFound("x"),
      AlreadyExists("x"),
      IntegrityMismatch("x"),
      InvalidRequest("x"),
      Failed("x"),
    ).foreach(e => assert(!e.retryable, e.toString))
  }

  test("StorageError.code is distinct per case") {
    import StorageError.*
    val codes = List[StorageError](
      NotFound("x"),
      AlreadyExists("x"),
      IntegrityMismatch("x"),
      InvalidRequest("x"),
      Throttled("x"),
      Unavailable("x"),
      Failed("x"),
    ).map(_.code)
    assertEquals(codes.distinct.size, 7)
  }

  test("GraphTemplates.all lists the six allowlisted templates") {
    assertEquals(
      GraphTemplates.all.toSet,
      Set(
        "neighbors",
        "coauthor-edge",
        "shortest-path",
        "component",
        "community",
        "researcher-metrics",
      ),
    )
  }
