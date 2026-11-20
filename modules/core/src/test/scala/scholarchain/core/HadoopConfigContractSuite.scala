package scholarchain.core

import ValidationError.*
import HadoopConfigKeys.*

class HadoopConfigContractSuite extends ContractSupport:

  private val Sentinel = "SECRET-SENTINEL-123"

  private def names(ks: List[ConfigKey]): List[String] = ks.map { case ConfigKey(n, _) => n }

  private val fileLayer: Map[String, String] = Map(
    "hadoop.schema-version"          -> "1",
    "hadoop.large-team-threshold"    -> "50",
    "hadoop.pair-expansion-hard-cap" -> "499500",
    "hadoop.reducers.normalize"      -> "2",
    "hadoop.reducers.teams"          -> "2",
    "hadoop.reducers.pairs"          -> "2",
    "hadoop.reducers.identifiers"    -> "2",
    "hadoop.output-codec"            -> "gzip",
  )

  private def ok(e: Either[List[ValidationError], ResolvedHadoopConfig]): ResolvedHadoopConfig =
    e match
      case Right(a) => a
      case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def errs(e: Either[List[ValidationError], ResolvedHadoopConfig]): List[ValidationError] =
    e match
      case Left(es) => es
      case Right(_) => fail("expected Left but got Right")

  private def pure(
      env: Map[String, String] = Map.empty,
      overrides: Map[String, String] = Map.empty,
      file: Map[String, String] = fileLayer,
  ) = HadoopConfigLoader.resolve(env, overrides, file)

  private val Thr = "SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD"

  // ---- defaults (shipped resource) ------------------------------------------------------------

  test("defaults from scholarchain-hadoop.v1.conf") {
    val rc = ok(HadoopConfigLoader.load(Map.empty, Map.empty))
    assertEquals(
      rc.config,
      HadoopConfig(1, 50, 499500, HadoopReducers(2, 2, 2, 2), OutputCodec.Gzip),
    )
    assertEquals(rc.config.pairExpansionHardCap, 499500)
    assertEquals(rc.config.largeTeamThreshold, 50)
    assertEquals(rc.origins.keySet, names(HadoopConfigKeys.all).toSet)
    assert(rc.origins.values.forall(_ == "file"), rc.origins.toString)
  }

  test("the shipped resource name and key list are frozen") {
    assertEquals(HadoopConfigKeys.Resource, "scholarchain-hadoop.v1.conf")
    assertEquals(
      names(HadoopConfigKeys.all),
      List(
        "hadoop.schema-version",
        "hadoop.large-team-threshold",
        "hadoop.pair-expansion-hard-cap",
        "hadoop.reducers.normalize",
        "hadoop.reducers.teams",
        "hadoop.reducers.pairs",
        "hadoop.reducers.identifiers",
        "hadoop.output-codec",
      ),
    )
  }

  test("the shipped file layer equals the explicit test file layer") {
    assertEquals(HadoopConfigLoader.load(Map.empty, Map.empty), pure())
  }

  test("env var names and override names follow the documented rule") {
    assertEquals(LargeTeamThreshold.envName, Thr)
    assertEquals(
      PairExpansionHardCap.envName,
      "SCHOLARCHAIN_HADOOP_PAIR_EXPANSION_HARD_CAP",
    )
    assertEquals(ReducersNormalize.envName, "SCHOLARCHAIN_HADOOP_REDUCERS_NORMALIZE")
    assertEquals(OutputCodecKey.envName, "SCHOLARCHAIN_HADOOP_OUTPUT_CODEC")
    assertEquals(
      ModuleConfig.overrideName(LargeTeamThreshold),
      "scholarchain.hadoop.large-team-threshold",
    )
  }

  // ---- precedence -----------------------------------------------------------------------------

  test("precedence: env beats override beats file, origins say which") {
    val rc = ok(
      pure(
        env = Map(Thr -> "7"),
        overrides = Map(
          "scholarchain.hadoop.large-team-threshold" -> "9",
          "scholarchain.hadoop.output-codec"         -> "none",
        ),
      )
    )
    assertEquals(rc.config.largeTeamThreshold, 7)
    assertEquals(rc.config.outputCodec, OutputCodec.Uncompressed)
    assertEquals(rc.origins("hadoop.large-team-threshold"), s"env:$Thr")
    assertEquals(
      rc.origins("hadoop.output-codec"),
      "override:scholarchain.hadoop.output-codec",
    )
    assertEquals(rc.origins("hadoop.pair-expansion-hard-cap"), "file")
    assertEquals(rc.config.pairExpansionHardCap, 499500)
  }

  test("precedence: override beats file when no env value is set") {
    val rc = ok(pure(overrides = Map("scholarchain.hadoop.large-team-threshold" -> "9")))
    assertEquals(rc.config.largeTeamThreshold, 9)
    assertEquals(
      rc.origins("hadoop.large-team-threshold"),
      "override:scholarchain.hadoop.large-team-threshold",
    )
  }

  test("precedence: env alone beats the file, through load as well") {
    val rc = ok(HadoopConfigLoader.load(Map(Thr -> "7"), Map.empty))
    assertEquals(rc.config.largeTeamThreshold, 7)
    assertEquals(rc.origins("hadoop.large-team-threshold"), s"env:$Thr")
  }

  test("an env var of one key does not leak into another key (reducers)") {
    val rc = ok(pure(env = Map("SCHOLARCHAIN_HADOOP_REDUCERS_PAIRS" -> "5")))
    assertEquals(rc.config.reducers, HadoopReducers(2, 2, 5, 2))
  }

  test("values are trimmed") {
    val rc = ok(pure(env = Map(Thr -> " 7 ")))
    assertEquals(rc.config.largeTeamThreshold, 7)
  }

  test("blank env is treated as unset: falls through to the override, then the file") {
    List("", "  ", "\t").foreach { blank =>
      val viaOverride = ok(
        pure(
          env = Map(Thr -> blank),
          overrides = Map("scholarchain.hadoop.large-team-threshold" -> "9"),
        )
      )
      assertEquals(viaOverride.config.largeTeamThreshold, 9)
      assertEquals(
        viaOverride.origins("hadoop.large-team-threshold"),
        "override:scholarchain.hadoop.large-team-threshold",
      )
      val viaFile = ok(pure(env = Map(Thr -> blank)))
      assertEquals(viaFile.config.largeTeamThreshold, 50)
      assertEquals(viaFile.origins("hadoop.large-team-threshold"), "file")
    }
  }

  test("a blank override is treated as unset") {
    val rc = ok(pure(overrides = Map("scholarchain.hadoop.large-team-threshold" -> " ")))
    assertEquals(rc.config.largeTeamThreshold, 50)
    assertEquals(rc.origins("hadoop.large-team-threshold"), "file")
  }

  test("JVM system properties are not consulted") {
    System.setProperty("scholarchain.hadoop.large-team-threshold", "77")
    System.setProperty("hadoop.large-team-threshold", "78")
    try
      val rc = ok(HadoopConfigLoader.load(Map.empty, Map.empty))
      assertEquals(rc.config.largeTeamThreshold, 50)
      assertEquals(rc.origins("hadoop.large-team-threshold"), "file")
    finally
      System.clearProperty("scholarchain.hadoop.large-team-threshold")
      System.clearProperty("hadoop.large-team-threshold")
  }

  // ---- origins never carry values -------------------------------------------------------------

  test("origins name the source and never include a value") {
    val rc = ok(
      pure(
        env = Map(Thr -> "7777"),
        overrides = Map("scholarchain.hadoop.pair-expansion-hard-cap" -> "8888"),
      )
    )
    assertEquals(rc.config.largeTeamThreshold, 7777)
    assertEquals(rc.config.pairExpansionHardCap, 8888)
    rc.origins.values.foreach { o =>
      assert(!o.contains("7777") && !o.contains("8888") && !o.contains("499500"), o)
      assert(o == "file" || o.startsWith("env:") || o.startsWith("override:"), o)
    }
  }

  // ---- invalid values -------------------------------------------------------------------------

  private def invalidKey(env: Map[String, String], configKey: String): Unit =
    val es = errs(pure(env = env))
    assertEquals(es.collect { case InvalidConfig(k, _) => k }, List(configKey), es.toString)

  test("threshold below 2 is rejected, 2 is accepted") {
    invalidKey(Map(Thr -> "1"), "hadoop.large-team-threshold")
    invalidKey(Map(Thr -> "0"), "hadoop.large-team-threshold")
    invalidKey(Map(Thr -> "-5"), "hadoop.large-team-threshold")
    assertEquals(ok(pure(env = Map(Thr -> "2"))).config.largeTeamThreshold, 2)
  }

  test("pair expansion cap below 1 is rejected, 1 is accepted") {
    val k = "SCHOLARCHAIN_HADOOP_PAIR_EXPANSION_HARD_CAP"
    invalidKey(Map(k -> "0"), "hadoop.pair-expansion-hard-cap")
    invalidKey(Map(k -> "-1"), "hadoop.pair-expansion-hard-cap")
    assertEquals(ok(pure(env = Map(k -> "1"))).config.pairExpansionHardCap, 1)
  }

  test("unknown codec is rejected; none, gzip, snappy accepted") {
    invalidKey(Map("SCHOLARCHAIN_HADOOP_OUTPUT_CODEC" -> "lz4"), "hadoop.output-codec")
    Map(
      "none"   -> OutputCodec.Uncompressed,
      "gzip"   -> OutputCodec.Gzip,
      "snappy" -> OutputCodec.Snappy,
    ).foreach { (w, c) =>
      val rc = ok(pure(env = Map("SCHOLARCHAIN_HADOOP_OUTPUT_CODEC" -> w)))
      assertEquals(rc.config.outputCodec, c)
    }
  }

  test("OutputCodec wire names and parse") {
    assertEquals(OutputCodec.values.map(_.wire).toList, List("none", "gzip", "snappy"))
    assertEquals(OutputCodec.parse("lz4"), None)
    assertEquals(OutputCodec.parse(""), None)
    assertEquals(OutputCodec.parse("snappy"), Some(OutputCodec.Snappy))
  }

  test("reducer counts below 1 are rejected, each naming its own key") {
    invalidKey(Map("SCHOLARCHAIN_HADOOP_REDUCERS_NORMALIZE" -> "0"), "hadoop.reducers.normalize")
    invalidKey(Map("SCHOLARCHAIN_HADOOP_REDUCERS_TEAMS" -> "0"), "hadoop.reducers.teams")
    invalidKey(Map("SCHOLARCHAIN_HADOOP_REDUCERS_PAIRS" -> "0"), "hadoop.reducers.pairs")
    invalidKey(
      Map("SCHOLARCHAIN_HADOOP_REDUCERS_IDENTIFIERS" -> "0"),
      "hadoop.reducers.identifiers",
    )
  }

  test("non-integer values are rejected") {
    invalidKey(Map(Thr -> "abc"), "hadoop.large-team-threshold")
    invalidKey(Map(Thr -> "50.5"), "hadoop.large-team-threshold")
    invalidKey(Map(Thr -> "99999999999"), "hadoop.large-team-threshold")
  }

  test("unsupported schema version is rejected") {
    invalidKey(Map("SCHOLARCHAIN_HADOOP_SCHEMA_VERSION" -> "2"), "hadoop.schema-version")
    invalidKey(Map("SCHOLARCHAIN_HADOOP_SCHEMA_VERSION" -> "x"), "hadoop.schema-version")
  }

  test("several invalid keys are all reported") {
    val es = errs(
      pure(
        env = Map(
          Thr                                  -> "1",
          "SCHOLARCHAIN_HADOOP_OUTPUT_CODEC"   -> "lz4",
          "SCHOLARCHAIN_HADOOP_REDUCERS_TEAMS" -> "0",
        )
      )
    )
    assertEquals(
      es.collect { case InvalidConfig(k, _) => k }.toSet,
      Set("hadoop.large-team-threshold", "hadoop.output-codec", "hadoop.reducers.teams"),
    )
  }

  test("invalid override values are rejected the same way") {
    val es = errs(pure(overrides = Map("scholarchain.hadoop.large-team-threshold" -> "1")))
    assertEquals(es.map(_.code), List("invalid-config"))
  }

  test("a key missing from every layer is an error naming the key and the ways to set it") {
    val es = errs(pure(file = fileLayer - "hadoop.large-team-threshold"))
    assertEquals(es.collect { case InvalidConfig(k, _) => k }, List("hadoop.large-team-threshold"))
    val m = es.head.message
    assert(m.contains(Thr), m)
    assert(m.contains("scholarchain.hadoop.large-team-threshold"), m)
  }

  test("unknown keys in the file layer are ignored") {
    val rc = ok(pure(file = fileLayer + ("hadoop.extra" -> "1")))
    assert(!rc.origins.contains("hadoop.extra"))
  }

  test("no value leakage in any error") {
    val bads = List(
      pure(env = Map(Thr -> Sentinel)),
      pure(env = Map("SCHOLARCHAIN_HADOOP_OUTPUT_CODEC" -> Sentinel)),
      pure(overrides = Map("scholarchain.hadoop.pair-expansion-hard-cap" -> Sentinel)),
      pure(env = Map("SCHOLARCHAIN_HADOOP_SCHEMA_VERSION" -> Sentinel)),
      pure(file = fileLayer + ("hadoop.reducers.pairs" -> Sentinel)),
    )
    bads.foreach { r =>
      val es = errs(r)
      assert(es.nonEmpty)
      es.foreach { e =>
        assert(!e.message.contains(Sentinel), e.message)
        assert(!e.toString.contains(Sentinel), e.toString)
      }
    }
  }

  // ---- generic module resolver ----------------------------------------------------------------

  test("ModuleConfig.resolve honours optional keys and the same precedence") {
    val k = ConfigKey("mod.opt", required = false)
    val r = ModuleConfig.resolve(List(k), Map.empty, Map.empty, Map.empty)
    assertEquals(r.map(_.values), Right(Map.empty[String, String]))
    val r2 = ModuleConfig.resolve(
      List(k),
      Map("SCHOLARCHAIN_MOD_OPT" -> "e"),
      Map("scholarchain.mod.opt" -> "o"),
      Map("mod.opt"              -> "f"),
    )
    assertEquals(r2.map(_.values), Right(Map("mod.opt" -> "e")))
    assertEquals(r2.map(_.origins), Right(Map("mod.opt" -> "env:SCHOLARCHAIN_MOD_OPT")))
  }

  // ---- the 21-key loader is unaffected --------------------------------------------------------

  test("existing 21-key loader: key set is unchanged and has no hadoop keys") {
    // Existing assertions that also cover this: ConfigContractSuite "env names: 21 distinct
    // keys" and ConfigLoaderContractSuite "shipped resource carries no values: exactly 19
    // missing keys".
    assertEquals(ConfigKeys.all.size, 21)
    assert(names(ConfigKeys.all).forall(!_.startsWith("hadoop")))
  }

  test("existing 21-key loader ignores SCHOLARCHAIN_HADOOP_* variables") {
    val hadoopEnv = HadoopConfigKeys.all.map(k => k.envName -> "1").toMap
    val es        = ConfigLoader.loadResource(env = hadoopEnv).left.getOrElse(Nil)
    assertEquals(es.size, 19)
    assert(es.forall { case InvalidConfig(k, _) => !k.startsWith("hadoop"); case _ => false })
  }
