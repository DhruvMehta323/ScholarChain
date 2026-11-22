package scholarchain.core

import ValidationError.*

/** Phase 07 repair 01 (M8, M3) core contracts: the ten analytics DynamoDB table-name keys, their
  * defaults and lookups, the 15-way distinctness rule, and `VectorRejection.EmbeddingMismatch`.
  */
class StorageAnalyticsConfigContractSuite extends ContractSupport:

  private val Logical = List(
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

  private def load(
      env: Map[String, String] = Map.empty
  ): Either[List[ValidationError], ResolvedStorageModuleConfig] =
    StorageConfigLoader.load(env, Map.empty)

  private def cfg(env: Map[String, String] = Map.empty): StorageModuleConfig =
    load(env) match
      case Right(r) => r.config
      case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def errs(env: Map[String, String]): List[ValidationError] =
    load(env) match
      case Left(es) => es
      case Right(_) => fail("expected Left but got Right")

  private def key(logical: String): ConfigKey =
    StorageConfigKeys.AnalyticsTableKeys
      .collectFirst { case (n, k) if n == logical => k }
      .getOrElse(fail(s"no analytics key for $logical"))

  // ---- keys and defaults ----------------------------------------------------------------------

  test("analytics keys: ten logical names in the documented order, one key each") {
    assertEquals(StorageConfigKeys.AnalyticsLogicalNames, Logical)
    assertEquals(StorageConfigKeys.AnalyticsTableKeys.map(_._1), Logical)
    assertEquals(
      StorageConfigKeys.AnalyticsTableKeys.map(_._2.key),
      Logical.map(n => s"storage.dynamodb.analytics.$n-table"),
    )
  }

  test("analytics keys: the key set grows from 27 to 37 and contains every analytics key once") {
    assertEquals(StorageConfigKeys.all.size, 37)
    assertEquals(StorageConfigKeys.all.map(_.key).distinct.size, 37)
    StorageConfigKeys.AnalyticsTableKeys.foreach((n, k) =>
      assert(StorageConfigKeys.all.contains(k), n)
    )
  }

  test("analytics defaults: scholarchain-analytics-<name> placeholders, from the file") {
    val r = load().fold(es => fail(es.map(_.message).toString), identity)
    Logical.foreach { n =>
      assertEquals(r.config.tables.analyticsTable(n), Some(s"scholarchain-analytics-$n"), n)
      assertEquals(r.origins(key(n).key), "file", n)
    }
  }

  test("analytics lookups: analytics map, names in order, allWithAnalytics has 15, all stays 5") {
    val t = cfg().tables
    assertEquals(t.all.size, 5)
    assertEquals(t.analytics.keySet, Logical.toSet)
    assertEquals(t.analyticsOrdered.map(_._1), Logical)
    assertEquals(t.analyticsNames, Logical.map(n => s"scholarchain-analytics-$n"))
    assertEquals(t.allWithAnalytics, t.all ++ t.analyticsNames)
    assertEquals(t.allWithAnalytics.size, 15)
    assertEquals(t.allWithAnalytics.distinct.size, 15)
    assertEquals(t.analyticsTable("not-a-table"), None)
    assertEquals(t.analyticsTable(""), None)
  }

  test("analytics keys: an environment value overrides one table name and only that one") {
    val k = key("bridges")
    val t = cfg(Map(k.envName -> "custom-bridges-table")).tables
    assertEquals(t.analyticsTable("bridges"), Some("custom-bridges-table"))
    assertEquals(t.analyticsTable("coreness"), Some("scholarchain-analytics-coreness"))
  }

  // ---- validation -----------------------------------------------------------------------------

  test("analytics keys: an invalid DynamoDB table name is rejected for each of the ten keys") {
    Logical.foreach { n =>
      val k = key(n)
      List("ab", "bad name", "has/slash", "x" * 256).foreach { bad =>
        val es = errs(Map(k.envName -> bad))
        assert(
          es.exists { case InvalidConfig(name, _) => name == k.key; case _ => false },
          s"$n / '$bad': $es",
        )
      }
    }
  }

  test("analytics keys: a name that repeats another analytics table is rejected (distinctness)") {
    val a  = key("bridges")
    val b  = key("coreness")
    val es = errs(Map(a.envName -> "dup-table-name-xyz", b.envName -> "dup-table-name-xyz"))
    assert(
      es.exists { case InvalidConfig(_, why) => why.contains("distinct"); case _ => false },
      es.toString,
    )
  }

  test("analytics keys: a name equal to one of the five original tables is rejected") {
    val k  = key("paths")
    val es = errs(
      Map(k.envName -> cfg().tables.runLedger)
    )
    assert(
      es.exists { case InvalidConfig(_, why) => why.contains("distinct"); case _ => false },
      es.toString,
    )
    // and the other direction: an original table renamed onto an analytics default
    val es2 =
      errs(Map(StorageConfigKeys.TableEvidenceBundle.envName -> "scholarchain-analytics-coreness"))
    assert(
      es2.exists { case InvalidConfig(_, why) => why.contains("distinct"); case _ => false },
      es2.toString,
    )
  }

  test("analytics keys: the 15 names are accepted when distinct, and rejected when any two match") {
    val names = StorageConfigKeys.AnalyticsTableKeys.map(_._2)
    val c     = cfg()
    assertEquals(c.tables.allWithAnalytics.distinct.size, 15)
    // every pair of analytics keys: setting both to the same value is a distinctness error
    for
      i <- names.indices
      j <- names.indices
      if i < j
    do
      val es = errs(Map(names(i).envName -> "same-name-aaa", names(j).envName -> "same-name-aaa"))
      assert(
        es.exists { case InvalidConfig(_, why) => why.contains("distinct"); case _ => false },
        s"$i/$j: $es",
      )
  }

  test("analytics keys: the distinctness error never contains the offending value") {
    val a      = key("bridges")
    val b      = key("coreness")
    val secret = "TOPSECRET-table-name-4711"
    val es     = errs(Map(a.envName -> secret, b.envName -> secret))
    assert(es.nonEmpty)
    es.foreach(e => assert(!e.message.contains(secret), e.message))
  }

  test("analytics keys: an invalid name error never contains the offending value") {
    val k      = key("window-summary")
    val secret = "TOPSECRET bad name 99"
    val es     = errs(Map(k.envName -> secret))
    assert(es.nonEmpty)
    es.foreach(e => assert(!e.message.contains(secret), e.message))
  }

  // ---- M3: the vector rejection case ----------------------------------------------------------

  test("VectorRejection.EmbeddingMismatch exists and is distinct from KeyCollision") {
    assert(VectorRejection.values.contains(VectorRejection.EmbeddingMismatch))
    assertNotEquals(VectorRejection.EmbeddingMismatch, VectorRejection.KeyCollision)
    assertEquals(VectorRejection.valueOf("EmbeddingMismatch"), VectorRejection.EmbeddingMismatch)
    assertEquals(VectorRejection.values.toList.distinct.size, VectorRejection.values.length)
    assertEquals(
      VectorPutOutcome.Rejected(VectorRejection.EmbeddingMismatch),
      VectorPutOutcome.Rejected(VectorRejection.EmbeddingMismatch),
    )
    assertNotEquals(
      VectorPutOutcome.Rejected(VectorRejection.EmbeddingMismatch),
      VectorPutOutcome.Rejected(VectorRejection.KeyCollision),
    )
  }
