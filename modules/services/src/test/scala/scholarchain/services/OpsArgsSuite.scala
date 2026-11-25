package scholarchain.services

/** `OpsArgs.parse`: defaults, bounds, argv over env, and error messages that name a variable and
  * never echo a value.
  */
class OpsArgsSuite extends munit.FunSuite:

  private val NS  = "s3://ns-sentinel-bucket/p/run-001/"
  private val IS  = "s3://is-sentinel-bucket/p/run-001/"
  private val RUN = "run-001"
  private val CV  = "cv-sentinel-1"

  private val baseEnv: Map[String, String] = Map(
    "SCHOLARCHAIN_OPS_NEPTUNE_SOURCE" -> NS,
    "SCHOLARCHAIN_OPS_ITEMS_SOURCE"   -> IS,
    "SCHOLARCHAIN_OPS_RUN_ID"         -> RUN,
    "SCHOLARCHAIN_OPS_CODE_VERSION"   -> CV,
  )

  private def ok(env: Map[String, String], argv: List[String] = Nil): OpsArgs =
    OpsArgs.parse(env, argv).fold(e => fail(s"unexpected Left: $e"), identity)

  private def bad(env: Map[String, String], argv: List[String] = Nil): String =
    OpsArgs.parse(env, argv).fold(identity, a => fail(s"unexpected Right: $a"))

  test("defaults: max vectors 10000, timeout 1800, max items 1000000, dry-run false") {
    val a = ok(baseEnv)
    assertEquals(a.maxVectors, 10000)
    assertEquals(OpsArgs.HardMaxVectors, 10000)
    assertEquals(a.neptuneTimeoutSeconds, 1800L)
    assertEquals(a.maxItems, 1000000L)
    assertEquals(a.dryRun, false)
    assertEquals(
      (a.neptuneSource, a.itemsSource, a.runId, a.codeVersion),
      (NS, IS, RUN, CV),
    )
  }

  test("max vectors 0 is a usage error naming the variable") {
    val m = bad(baseEnv + ("SCHOLARCHAIN_OPS_MAX_VECTORS" -> "0"))
    assert(m.contains("SCHOLARCHAIN_OPS_MAX_VECTORS"), m)
  }

  test("max vectors 10001 is a usage error naming the variable") {
    val m = bad(baseEnv + ("SCHOLARCHAIN_OPS_MAX_VECTORS" -> "10001"))
    assert(m.contains("SCHOLARCHAIN_OPS_MAX_VECTORS"), m)
  }

  test("max vectors: the bounds 1 and 10000 are accepted; -1, text and a decimal are refused") {
    assertEquals(ok(baseEnv + ("SCHOLARCHAIN_OPS_MAX_VECTORS" -> "1")).maxVectors, 1)
    assertEquals(ok(baseEnv + ("SCHOLARCHAIN_OPS_MAX_VECTORS" -> "10000")).maxVectors, 10000)
    List("-1", "abc", "5.5", "99999999999999999999").foreach { v =>
      val m = bad(baseEnv + ("SCHOLARCHAIN_OPS_MAX_VECTORS" -> v))
      assert(m.contains("SCHOLARCHAIN_OPS_MAX_VECTORS"), m)
      assert(!m.contains(v) || v.length < 2, s"the value is echoed: $m")
    }
  }

  test("max vectors by flag: 0 and 10001 are refused too") {
    List("0", "10001").foreach { v =>
      val m = bad(baseEnv, List("--max-vectors", v))
      assert(m.contains("SCHOLARCHAIN_OPS_MAX_VECTORS"), m)
    }
  }

  test("argv beats env for every flag") {
    val env = baseEnv ++ Map(
      "SCHOLARCHAIN_OPS_MAX_VECTORS"             -> "100",
      "SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS" -> "60",
      "SCHOLARCHAIN_OPS_MAX_ITEMS"               -> "500",
      "SCHOLARCHAIN_OPS_DRY_RUN"                 -> "false",
    )
    val a = ok(
      env,
      List(
        "--max-vectors",
        "7",
        "--neptune-timeout-seconds",
        "9",
        "--max-items",
        "11",
        "--dry-run",
        "--run-id",
        "run-argv",
        "--code-version",
        "cv-argv",
        "--neptune-source",
        "s3://argv-bucket/x/run-argv/",
        "--items-source",
        "s3://argv-bucket/y/run-argv/",
      ),
    )
    assertEquals(a.maxVectors, 7)
    assertEquals(a.neptuneTimeoutSeconds, 9L)
    assertEquals(a.maxItems, 11L)
    assertEquals(a.dryRun, true)
    assertEquals(a.runId, "run-argv")
    assertEquals(a.codeVersion, "cv-argv")
    assertEquals(a.neptuneSource, "s3://argv-bucket/x/run-argv/")
    assertEquals(a.itemsSource, "s3://argv-bucket/y/run-argv/")
  }

  test("env alone supplies the values (the ECS RunTask path)") {
    val a = ok(
      baseEnv ++ Map(
        "SCHOLARCHAIN_OPS_MAX_VECTORS" -> "250",
        "SCHOLARCHAIN_OPS_DRY_RUN"     -> "TRUE",
      )
    )
    assertEquals(a.maxVectors, 250)
    assertEquals(a.dryRun, true)
  }

  test("--dry-run: bare, with true, with false, and followed by another flag") {
    assert(ok(baseEnv, List("--dry-run")).dryRun)
    assert(ok(baseEnv, List("--dry-run", "true")).dryRun)
    assert(!ok(baseEnv + ("SCHOLARCHAIN_OPS_DRY_RUN" -> "true"), List("--dry-run", "false")).dryRun)
    val a = ok(baseEnv, List("--dry-run", "--max-vectors", "5"))
    assert(a.dryRun)
    assertEquals(a.maxVectors, 5)
  }

  test("a missing required value is a usage error naming the variable and no value") {
    val required = List(
      "SCHOLARCHAIN_OPS_NEPTUNE_SOURCE",
      "SCHOLARCHAIN_OPS_ITEMS_SOURCE",
      "SCHOLARCHAIN_OPS_RUN_ID",
      "SCHOLARCHAIN_OPS_CODE_VERSION",
    )
    required.foreach { name =>
      val m = bad(baseEnv - name)
      assert(m.contains(name), m)
      List(NS, IS, RUN, CV).foreach(v => assert(!m.contains(v), s"value echoed for $name: $m"))
    }
    required.foreach { name =>
      val m = bad(baseEnv + (name -> "   "))
      assert(m.contains(name), s"a blank value counts as missing: $m")
    }
  }

  test("unknown or malformed arguments are refused without echoing them") {
    val secret = "super-secret-flag-value"
    List(
      List(s"--bogus=$secret"),
      List("--bogus", secret),
      List(secret),
      List("--max-vectors"),
      List("--run-id", "--max-vectors", "5"),
    ).foreach { argv =>
      val m = bad(baseEnv, argv)
      assert(!m.contains(secret), s"argument echoed: $m")
    }
    assert(bad(baseEnv, List("--dry-run", "maybe")).nonEmpty)
  }

  test("a bad dry-run value in the environment names the variable only") {
    val m = bad(baseEnv + ("SCHOLARCHAIN_OPS_DRY_RUN" -> "perhaps"))
    assert(m.contains("SCHOLARCHAIN_OPS_DRY_RUN"), m)
    assert(!m.contains("perhaps"), m)
  }

  test("timeout and max items bounds") {
    assertEquals(
      ok(baseEnv + ("SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS" -> "21600")).neptuneTimeoutSeconds,
      21600L,
    )
    List("0", "21601").foreach { v =>
      val m = bad(baseEnv + ("SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS" -> v))
      assert(m.contains("SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS"), m)
    }
    List("0", "1000001").foreach { v =>
      val m = bad(baseEnv + ("SCHOLARCHAIN_OPS_MAX_ITEMS" -> v))
      assert(m.contains("SCHOLARCHAIN_OPS_MAX_ITEMS"), m)
    }
  }

  test("envName maps a flag to its variable") {
    assertEquals(OpsArgs.envName("max-vectors"), "SCHOLARCHAIN_OPS_MAX_VECTORS")
    assertEquals(OpsArgs.envName("dry-run"), "SCHOLARCHAIN_OPS_DRY_RUN")
  }
