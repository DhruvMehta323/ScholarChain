package scholarchain.core

import java.nio.file.{Path, Paths}

import ValidationError.*

class ConfigLoaderContractSuite extends ContractSupport:

  private val Sentinel = "SECRET-SENTINEL-123"

  /** Test resources may sit in a jar, so each is copied to a temporary file for loadFile. */
  private def res(name: String): Path =
    val in = getClass.getClassLoader.getResourceAsStream(name)
    try
      val tmp = java.nio.file.Files.createTempFile("loader-test-", ".conf")
      java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      tmp.toFile.deleteOnExit()
      tmp
    finally in.close()

  private def text(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(name)
    try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally in.close()

  private def ok(e: Either[List[ValidationError], ResolvedConfig]): ResolvedConfig = e match
    case Right(a) => a
    case Left(es) => fail(s"expected Right but got ${es.map(_.message)}")

  private def errs(e: Either[List[ValidationError], ResolvedConfig]): List[ValidationError] =
    e match
      case Left(es) => es
      case Right(_) => fail("expected Left but got Right")

  private def noLeak(es: List[ValidationError], what: String): Unit =
    assert(es.nonEmpty, what)
    es.foreach { e =>
      assert(!e.message.contains(Sentinel), s"$what message: ${e.message}")
      assert(!e.toString.contains(Sentinel), s"$what toString: ${e}")
    }

  private def checkValid(rc: ResolvedConfig): Unit =
    assertEquals(rc.config.graph.window, GraphWindow(2000, 2004))
    assertEquals(rc.config.retrieval.topK, 5)
    assertEquals(rc.config.graph.bridge.parameters, Map("k" -> "3"))
    assertEquals(rc.config.storage.rawBucket, "test-raw")
    assert(rc.origins.nonEmpty && rc.origins.values.forall(_ == "file"), rc.origins.toString)

  // 1
  test("file only: loadFile and loadResource resolve the file values with file origins") {
    val fromFile = ok(ConfigLoader.loadFile(res("loader-valid.conf"), Map.empty))
    checkValid(fromFile)
    val fromRes = ok(ConfigLoader.loadResource("loader-valid.conf", Map.empty))
    checkValid(fromRes)
    assertEquals(fromRes, fromFile)
  }

  // 2
  test("env override: SCHOLARCHAIN_RETRIEVAL_TOP_K beats the file, blank falls back") {
    val rc =
      ok(
        ConfigLoader.loadFile(res("loader-valid.conf"), Map("SCHOLARCHAIN_RETRIEVAL_TOP_K" -> "9"))
      )
    assertEquals(rc.config.retrieval.topK, 9)
    assertEquals(rc.origins("retrieval.top-k"), "env:SCHOLARCHAIN_RETRIEVAL_TOP_K")
    assertEquals(rc.origins("graph.window.start-year"), "file")
    List("", "  ").foreach { blank =>
      val b = ok(
        ConfigLoader.loadFile(
          res("loader-valid.conf"),
          Map("SCHOLARCHAIN_RETRIEVAL_TOP_K" -> blank),
        )
      )
      assertEquals(b.config.retrieval.topK, 5)
      assertEquals(b.origins("retrieval.top-k"), "file")
    }
  }

  // 3
  test("missing file: InvalidConfig(config-file) and nothing throws") {
    val r = ConfigLoader.loadFile(Paths.get("does-not-exist-test-fixture.conf"), Map.empty)
    expectErr(r.left.map(_.head)) { case InvalidConfig("config-file", _) => () }
    assertEquals(errs(r).size, 1)
  }

  // 4
  test("malformed HOCON: InvalidConfig(config-file)") {
    val r = ConfigLoader.loadFile(res("loader-malformed.conf"), Map.empty)
    assertEquals(
      errs(r).map { case InvalidConfig(k, _) => k; case other => other.code },
      List("config-file"),
    )
  }

  // 5
  test("non-scalar values are rejected naming the key and the word scalar") {
    val nested = errs(ConfigLoader.loadFile(res("loader-nested.conf"), Map.empty))
    val list   = errs(ConfigLoader.loadFile(res("loader-list.conf"), Map.empty))
    assertEquals(nested.collect { case InvalidConfig(k, _) => k }, List("storage.raw-bucket"))
    assertEquals(list.collect { case InvalidConfig(k, _) => k }, List("aws.region"))
    (nested ++ list).foreach { e =>
      assert(e.message.contains("scalar"), e.message)
    }
    assert(nested.head.message.contains("storage.raw-bucket"), nested.head.message)
    assert(list.head.message.contains("aws.region"), list.head.message)
  }

  // 6
  test("schema version 2 file is UnsupportedSchemaVersion(2, Set(1))") {
    val es = errs(ConfigLoader.loadFile(res("loader-schema2.conf"), Map.empty))
    assert(es.contains(UnsupportedSchemaVersion(2, Set(1))), es.toString)
  }

  // 7
  test("JVM system properties are not consulted") {
    System.setProperty("retrieval.top-k", "77")
    System.setProperty("aws.region", "SYSPROP")
    try
      val rc = ok(ConfigLoader.loadResource("loader-valid.conf", Map.empty))
      checkValid(rc)
      assertEquals(rc.config.awsRegion, "test-region-1")
    finally
      System.clearProperty("retrieval.top-k")
      System.clearProperty("aws.region")
  }

  test("the process environment is not consulted when an explicit env is passed") {
    val rc = ok(ConfigLoader.loadFile(res("loader-valid.conf"), Map.empty))
    assert(rc.origins.values.forall(_ == "file"), rc.origins.toString)
    assertEquals(rc.config.retrieval.topK, 5)
  }

  // 8
  test("HOCON substitution cannot read the process environment, but can refer within the file") {
    val r = ConfigLoader.loadString("environment = ${HOME}", Map.empty)
    expectErr(r.left.map(_.head)) { case InvalidConfig("config-file", _) => () }
    val valid = text("loader-valid.conf")
    val sub   = ok(
      ConfigLoader.loadString(
        valid.replace("environment = test", "x = abc\nenvironment = ${x}"),
        Map.empty,
      )
    )
    assertEquals(sub.config.environment, "abc")
  }

  // 9
  test("no value leakage in any loader error") {
    val nested = ConfigLoader.loadString(
      s"schema-version = 1\nstorage { raw-bucket { x = $Sentinel } }",
      Map.empty,
    )
    val list =
      ConfigLoader.loadString(s"schema-version = 1\naws { region = [$Sentinel, two] }", Map.empty)
    val malformed = ConfigLoader.loadString(s"a = = {{ $Sentinel", Map.empty)
    val subst     = ConfigLoader.loadString(s"environment = $${$Sentinel}", Map.empty)
    val valid     = text("loader-valid.conf")
    val badBucket = ConfigLoader.loadString(
      valid.replace("\"test-raw\"", "\"s3://" + Sentinel + "\""),
      Map.empty,
    )
    val badEnv = ConfigLoader.loadResource(
      "loader-valid.conf",
      Map("SCHOLARCHAIN_STORAGE_RAW_BUCKET" -> s"s3://$Sentinel"),
    )
    List(
      "nested"       -> nested,
      "list"         -> list,
      "malformed"    -> malformed,
      "substitution" -> subst,
      "bucket"       -> badBucket,
      "env bucket"   -> badEnv,
    )
      .foreach { (n, r) => noLeak(errs(r), n) }
    // the scalar-key error still names the key
    assertEquals(
      errs(badBucket).collect { case InvalidConfig(k, _) => k },
      List("storage.raw-bucket"),
    )
  }

  // 10
  test("unknown keys in the file are ignored") {
    val valid = text("loader-valid.conf")
    val rc    = ok(ConfigLoader.loadString(valid + "\nextra.thing = 1\n", Map.empty))
    checkValid(rc)
    assert(!rc.origins.contains("extra.thing"))
  }

  // 11
  test("shipped resource carries no values: exactly 19 missing keys, none for schema-version") {
    val es = errs(ConfigLoader.loadResource(env = Map.empty))
    assertEquals(es.size, 19)
    assert(
      es.forall { case InvalidConfig(k, _) => k != "schema-version"; case _ => false },
      es.toString,
    )
  }
