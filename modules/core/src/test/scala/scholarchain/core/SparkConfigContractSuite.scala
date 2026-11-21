package scholarchain.core

import ValidationError.*

/** Phase 06 Spark configuration contract: defaults, precedence and rejections. */
class SparkConfigContractSuite extends ContractSupport:

  private val file: Map[String, String] = Map(
    "spark.schema-version"                      -> "1",
    "spark.windows"                             -> "2000-2004,2005-2009",
    "spark.include-all-time"                    -> "true",
    "spark.bfs-max-depth"                       -> "6",
    "spark.path-sample-pairs"                   -> "20",
    "spark.path-sample-seed"                    -> "441",
    "spark.avg-path-sources-k"                  -> "50",
    "spark.avg-path-seed"                       -> "441",
    "spark.label-propagation-max-iterations"    -> "20",
    "spark.connected-components-max-iterations" -> "50",
    "spark.k-core-max-iterations"               -> "50",
    "spark.representative-work-ids-bound"       -> "5",
    "spark.shuffle-partitions"                  -> "8",
    "spark.output-partitions"                   -> "4",
    "spark.checkpoint-dir"                      -> "ck",
    "spark.output-root"                         -> "out",
    "spark.input.root-i-dir"                    -> "in",
    "spark.input.hj6a-entity-views-dir"         -> "views",
    "spark.input.match-decisions-final-path"    -> "dec.jsonl",
    "spark.policy-version"                      -> "coauthor-graph.v1",
    "spark.code-version"                        -> "dev",
  )

  private def res(
      over: Map[String, String] = Map.empty,
      f: Map[String, String] = file,
      env: Map[String, String] = Map.empty,
  ) =
    SparkConfigLoader.resolve(env, over.map((k, v) => s"scholarchain.$k" -> v), f)

  private def errs(e: Either[List[ValidationError], ResolvedSparkConfig]): List[ValidationError] =
    e match
      case Left(es) => es
      case Right(_) => fail("expected Left but got Right")

  private def okc(e: Either[List[ValidationError], ResolvedSparkConfig]): SparkConfig = e match
    case Right(r) => r.config
    case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def hasKey(es: List[ValidationError], name: String): Boolean =
    es.exists {
      case InvalidConfig(k, _) => k == name
      case _                   => false
    }

  private def rejectsKey(over: Map[String, String], name: String)(using munit.Location): Unit =
    val es = errs(res(over))
    assert(hasKey(es, name), s"expected InvalidConfig($name) in $es")

  // ---- defaults -------------------------------------------------------------------------------

  test("shipped defaults load, and the all-time window is appended") {
    val rc = SparkConfigLoader.load(Map.empty, Map.empty) match
      case Right(r) => r
      case Left(es) => fail(es.map(_.message).toString)
    val c = rc.config
    assertEquals(
      c.explicitWindows,
      List(
        GraphWindow(2000, 2004),
        GraphWindow(2005, 2009),
        GraphWindow(2010, 2014),
        GraphWindow(2015, 2019),
        GraphWindow(2020, 2024),
      ),
    )
    assert(c.includeAllTime)
    assertEquals(c.allWindows.last, GraphWindows.AllTime)
    assertEquals(c.allWindows.size, 6)
    assertEquals(
      (c.bfsMaxDepth, c.pathSamplePairs, c.pathSampleSeed, c.avgPathSourcesK, c.avgPathSeed),
      (6, 20, 441L, 50, 441L),
    )
    assertEquals(
      (c.labelPropagationMaxIterations, c.connectedComponentsMaxIterations, c.kCoreMaxIterations),
      (20, 50, 50),
    )
    assertEquals((c.representativeWorkIdsBound, c.shufflePartitions, c.outputPartitions), (5, 8, 4))
    assertEquals((c.policyVersion, c.codeVersion), ("coauthor-graph.v1", "dev"))
    assertEquals(rc.origins.keySet, SparkConfigKeys.all.map(k => k.key).toSet)
    assert(rc.origins.values.forall(_ == "file"))
  }

  test("the resource name and the 21 keys are frozen") {
    assertEquals(SparkConfigKeys.Resource, "scholarchain-spark.v1.conf")
    assertEquals(SparkConfigKeys.all.size, 21)
    assert(SparkConfigKeys.all.forall(k => k.key.startsWith("spark.")))
    assertEquals(SparkConfigKeys.all.map(k => k.key).distinct.size, 21)
  }

  test("precedence: environment beats override beats file") {
    val c1 = res(Map("spark.bfs-max-depth" -> "4"))
    assertEquals(okc(c1).bfsMaxDepth, 4)
    assertEquals(
      c1.toOption.get.origins("spark.bfs-max-depth"),
      "override:scholarchain.spark.bfs-max-depth",
    )
    val c2 =
      res(Map("spark.bfs-max-depth" -> "4"), env = Map("SCHOLARCHAIN_SPARK_BFS_MAX_DEPTH" -> "3"))
    assertEquals(okc(c2).bfsMaxDepth, 3)
    assertEquals(
      c2.toOption.get.origins("spark.bfs-max-depth"),
      "env:SCHOLARCHAIN_SPARK_BFS_MAX_DEPTH",
    )
  }

  test("includeAllTime=false leaves only the explicit windows") {
    val c = okc(res(Map("spark.include-all-time" -> "false")))
    assertEquals(c.allWindows, c.explicitWindows)
    assertEquals(c.allWindows.size, 2)
  }

  test("a window list with whitespace around items parses") {
    val c = okc(res(Map("spark.windows" -> " 2000-2004 , 2010-2014 ")))
    assertEquals(c.explicitWindows, List(GraphWindow(2000, 2004), GraphWindow(2010, 2014)))
  }

  // ---- windows --------------------------------------------------------------------------------

  test("a missing windows key is rejected") {
    val es = errs(res(f = file - "spark.windows"))
    assert(hasKey(es, "spark.windows"), es.toString)
  }

  test("an empty windows value is rejected") {
    // an empty value counts as absent for the resolver, so the required key is missing
    val es = errs(res(Map("spark.windows" -> ""), f = file - "spark.windows"))
    assert(hasKey(es, "spark.windows"), es.toString)
    rejectsKey(Map("spark.windows" -> "2000-2004,,2005-2009"), "spark.windows")
    rejectsKey(Map("spark.windows" -> ","), "spark.windows")
  }

  test("overlapping and duplicate windows are rejected") {
    rejectsKey(Map("spark.windows" -> "2000-2005,2005-2009"), "spark.windows")
    rejectsKey(Map("spark.windows" -> "2000-2009,2003-2004"), "spark.windows")
    rejectsKey(Map("spark.windows" -> "2000-2004,2000-2004"), "spark.windows")
  }

  test("invalid windows are rejected: start after end, non-integers, wrong shape, bad years") {
    rejectsKey(Map("spark.windows" -> "2005-2000"), "spark.windows")
    rejectsKey(Map("spark.windows" -> "abc-2000"), "spark.windows")
    rejectsKey(Map("spark.windows" -> "2000"), "spark.windows")
    rejectsKey(Map("spark.windows" -> "2000-2001-2002"), "spark.windows")
    rejectsKey(Map("spark.windows" -> "999-2000"), "spark.windows")
    rejectsKey(Map("spark.windows" -> "2000-10000"), "spark.windows")
  }

  test("an explicit all-time window is rejected when include-all-time is true, allowed otherwise") {
    rejectsKey(Map("spark.windows" -> "1000-9999"), "spark.windows")
    val c = okc(res(Map("spark.windows" -> "1000-9999", "spark.include-all-time" -> "false")))
    assertEquals(c.allWindows, List(GraphWindows.AllTime))
  }

  test("window errors never echo the offending value") {
    val es = errs(res(Map("spark.windows" -> "SENTINEL-999")))
    assert(es.nonEmpty)
    assert(es.forall(e => !e.message.contains("SENTINEL-999")), es.toString)
  }

  // ---- numeric bounds -------------------------------------------------------------------------

  test("bfs depth <= 0 is rejected") {
    rejectsKey(Map("spark.bfs-max-depth" -> "0"), "spark.bfs-max-depth")
    rejectsKey(Map("spark.bfs-max-depth" -> "-1"), "spark.bfs-max-depth")
    rejectsKey(Map("spark.bfs-max-depth" -> "deep"), "spark.bfs-max-depth")
  }

  test("k <= 0 (avg-path-sources-k) is rejected") {
    rejectsKey(Map("spark.avg-path-sources-k" -> "0"), "spark.avg-path-sources-k")
    rejectsKey(Map("spark.avg-path-sources-k" -> "-5"), "spark.avg-path-sources-k")
  }

  test("every other count below 1 is rejected") {
    List(
      "spark.label-propagation-max-iterations",
      "spark.connected-components-max-iterations",
      "spark.k-core-max-iterations",
      "spark.representative-work-ids-bound",
      "spark.shuffle-partitions",
      "spark.output-partitions",
    ).foreach(k => rejectsKey(Map(k -> "0"), k))
  }

  test("path-sample-pairs may be 0 (disabled) but not negative; seeds are any Long") {
    assertEquals(okc(res(Map("spark.path-sample-pairs" -> "0"))).pathSamplePairs, 0)
    rejectsKey(Map("spark.path-sample-pairs" -> "-1"), "spark.path-sample-pairs")
    val c = okc(res(Map("spark.path-sample-seed" -> "-7", "spark.avg-path-seed" -> "0")))
    assertEquals((c.pathSampleSeed, c.avgPathSeed), (-7L, 0L))
    rejectsKey(Map("spark.path-sample-seed" -> "1.5"), "spark.path-sample-seed")
  }

  test("a bad boolean, schema version and tokens are rejected") {
    rejectsKey(Map("spark.include-all-time" -> "maybe"), "spark.include-all-time")
    rejectsKey(Map("spark.schema-version" -> "2"), "spark.schema-version")
    rejectsKey(Map("spark.policy-version" -> "has space"), "spark.policy-version")
    rejectsKey(Map("spark.code-version" -> "a b"), "spark.code-version")
  }

  test("all errors are collected, not just the first") {
    val es = errs(res(Map("spark.bfs-max-depth" -> "0", "spark.avg-path-sources-k" -> "0")))
    assert(hasKey(es, "spark.bfs-max-depth") && hasKey(es, "spark.avg-path-sources-k"), es.toString)
  }
